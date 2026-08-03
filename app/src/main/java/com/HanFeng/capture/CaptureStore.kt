package com.HanFeng.capture

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 抓包 entry 历史 SQLite 仓储 (E1.1)。
 *
 * 与 [CaptureRingBuffer] 互补: ring 仍为热活内存缓冲供 UI 实时刷新,
 * 此仓持久全部 entry 与大块 body 解除 32KB ring 预览上限(解 E2)。
 *
 * API 与 [CaptureRingBuffer] 对齐:
 * - [insertEntry] / [upsertResponse] / [upsertError] / [putBodyChunk]
 * - [get] / [list] / [count]
 * - [deleteEntry] / [deleteOlderThan] / [trimToMaxEntries] (LRU)
 *
 * 引用 design correctness 14 / requirements R12, 配合 E1.4 用户可配 maxEntries/maxAgeDays。
 *
 * 所有方法同步阻塞由调用方决定线程; 推荐从 IO executor 调用。
 */
object CaptureStore {

    private val gson = Gson()
    private val headersType = object : TypeToken<Map<String, String>>() {}.type
    private val tlsMetaType = object : TypeToken<TlsMeta>() {}.type
    private val certListType = object : TypeToken<List<CertMeta>>() {}.type

    private fun db(context: Context): SQLiteDatabase =
        helper(context).writableDatabase

    /** 进程级 singleton helper (用 applicationContext 避免每个 Activity 持有)。 */
    @Volatile private var helperRef: CaptureDb.Helper? = null
    private val helperLock = Any()
    private fun helper(context: Context): CaptureDb.Helper {
        helperRef?.let { return it }
        synchronized(helperLock) {
            helperRef?.let { return it }
            val h = CaptureDb.Helper(context.applicationContext)
            helperRef = h
            return h
        }
    }

    // ============== 写入 ==============

    /** 写一条 entry (txnId 主键冲突时 ignore)。 返回 rowId 或 -1。 */
    fun insertEntry(context: Context, e: CaptureEntry): Long {
        val w = db(context)
        val cv = entryToCv(e)
        return try {
            w.insertWithOnConflict(CaptureDb.TABLE_ENTRIES, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } catch (_: Exception) { -1L }
    }

    /** upsert 一条 entry (txnId 存在则覆写)。 */
    fun upsertEntry(context: Context, e: CaptureEntry): Long {
        val w = db(context)
        val cv = entryToCv(e)
        return w.insertWithOnConflict(
            CaptureDb.TABLE_ENTRIES, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** 仅补丁响应字段(响应解码完成时, request 部分不动)。 */
    fun upsertResponse(
        context: Context,
        txnId: Long,
        responseStatus: Int,
        responseHeaders: Map<String, String>,
        responseBodyPreview: ByteArray?,
        responseBodyTruncated: Boolean,
        durationMs: Long,
        intercepted: Boolean
    ): Int {
        val w = db(context)
        val full = ContentValues().apply {
            put(CaptureDb.COL_RESPONSE_STATUS, responseStatus)
            put(CaptureDb.COL_RESPONSE_HEADERS_JSON, gson.toJson(responseHeaders))
            put(CaptureDb.COL_RESPONSE_CONTENT_TYPE, extractContentType(responseHeaders))
            put(CaptureDb.COL_RESPONSE_BODY_TRUNCATED, if (responseBodyTruncated) 1 else 0)
            put(CaptureDb.COL_DURATION_MS, durationMs)
            put(CaptureDb.COL_INTERCEPTED, if (intercepted) 1 else 0)
        }
        w.beginTransaction()
        try {
            val n = w.update(
                CaptureDb.TABLE_ENTRIES,
                full,
                "${CaptureDb.COL_TXN_ID}=?",
                arrayOf(txnId.toString())
            )
            if (n > 0 && responseBodyPreview != null && responseBodyPreview.isNotEmpty()) {
                w.delete(
                    CaptureDb.TABLE_BODY_CHUNKS,
                    "${CaptureDb.COL_CHUNK_ENTRY_ID}=? AND ${CaptureDb.COL_CHUNK_DIRECTION}=?",
                    arrayOf(txnId.toString(), CaptureChunkDirection.RESPONSE.toString())
                )
                var ord = 0
                var off = 0
                while (off < responseBodyPreview.size) {
                    val next = minOf(off + CaptureDb.CHUNK_SIZE, responseBodyPreview.size)
                    val c = ContentValues().apply {
                        put(CaptureDb.COL_CHUNK_ENTRY_ID, txnId)
                        put(CaptureDb.COL_CHUNK_DIRECTION, CaptureChunkDirection.RESPONSE)
                        put(CaptureDb.COL_CHUNK_ORDINAL, ord)
                        put(CaptureDb.COL_CHUNK_BLOB, responseBodyPreview.copyOfRange(off, next))
                    }
                    w.insertWithOnConflict(CaptureDb.TABLE_BODY_CHUNKS, null, c, SQLiteDatabase.CONFLICT_REPLACE)
                    ord += 1
                    off = next
                }
            }
            w.setTransactionSuccessful()
            return n
        } finally {
            w.endTransaction()
        }
    }

    /** 仅补丁 error 字段(请求失败/TLS 错误)。 */
    fun upsertError(context: Context, txnId: Long, error: String?): Int {
        val w = db(context)
        val cv = ContentValues().apply {
            put(CaptureDb.COL_ERROR, error)
        }
        return w.update(
            CaptureDb.TABLE_ENTRIES,
            cv,
            "${CaptureDb.COL_TXN_ID}=?",
            arrayOf(txnId.toString())
        )
    }

    /** 插入 body chunk(方向 + 顺序。一次只插一个 chunk, 不去重)。 */
    fun putBodyChunk(
        context: Context,
        txnId: Long,
        direction: Int,
        chunk: ByteArray,
        ordinal: Int = -1
    ) {
        val w = db(context)
        val ord = if (ordinal < 0) nextOrdinal(w, txnId, direction) else ordinal
        val cv = ContentValues().apply {
            put(CaptureDb.COL_CHUNK_ENTRY_ID, txnId)
            put(CaptureDb.COL_CHUNK_DIRECTION, direction)
            put(CaptureDb.COL_CHUNK_ORDINAL, ord)
            put(CaptureDb.COL_CHUNK_BLOB, chunk)
        }
        w.insertWithOnConflict(
            CaptureDb.TABLE_BODY_CHUNKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ============== 读取 ==============

    fun get(context: Context, txnId: Long): CaptureEntry? {
        val r = db(context)
        val c = r.query(
            CaptureDb.TABLE_ENTRIES,
            null,
            "${CaptureDb.COL_TXN_ID}=?",
            arrayOf(txnId.toString()),
            null, null, null,
            "1"
        )
        return c.use { if (it.moveToFirst()) cvToEntry(it) else null }
    }

    /** 列表查询: 默认时间倒序, 范围限制 offset/limit, 可按 host/session/keyword 过滤。 */
    fun list(
        context: Context,
        offset: Int = 0,
        limit: Int = 500,
        hostLike: String? = null,
        session: String? = null,
        msMin: Long = 0,
        msMax: Long = 0,
        orderDesc: Boolean = true
    ): List<CaptureEntry> {
        val w = db(context)
        val where = StringBuilder()
        val args = mutableListOf<String>()
        if (!hostLike.isNullOrBlank()) {
            where.append(" AND ${CaptureDb.COL_HOST} LIKE ?")
            args.add("%${hostLike}%")
        }
        if (!session.isNullOrBlank()) {
            where.append(" AND ${CaptureDb.COL_SESSION_ID}=?")
            args.add(session)
        }
        if (msMin > 0) {
            where.append(" AND ${CaptureDb.COL_TIMESTAMP}>=?")
            args.add(msMin.toString())
        }
        if (msMax > 0) {
            where.append(" AND ${CaptureDb.COL_TIMESTAMP}<=?")
            args.add(msMax.toString())
        }
        val whereClause = if (where.isNotEmpty()) where.removePrefix(" AND ").toString() else null
        val orderBy = if (orderDesc) "${CaptureDb.COL_TIMESTAMP} DESC" else "${CaptureDb.COL_TIMESTAMP} ASC"
        val c = w.query(
            CaptureDb.TABLE_ENTRIES,
            null,
            whereClause,
            args.toTypedArray(),
            null, null, orderBy,
            "${if (offset > 0) offset.toString() + "," else ""}${limit}"
        )
        return c.use {
            val out = mutableListOf<CaptureEntry>()
            while (it.moveToNext()) out += cvToEntry(it)
            out
        }
    }

    fun count(context: Context): Long {
        val w = db(context)
        val c = w.rawQuery("SELECT COUNT(*) FROM ${CaptureDb.TABLE_ENTRIES}", null)
        return c.use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    /** 读取 entry 的全部 body chunk (按 direction + ordinal 拼成单字节数组)。 */
    fun readBody(context: Context, txnId: Long, direction: Int): ByteArray {
        val w = db(context)
        val c = w.query(
            CaptureDb.TABLE_BODY_CHUNKS,
            arrayOf(CaptureDb.COL_CHUNK_BLOB),
            "${CaptureDb.COL_CHUNK_ENTRY_ID}=? AND ${CaptureDb.COL_CHUNK_DIRECTION}=?",
            arrayOf(txnId.toString(), direction.toString()),
            null, null,
            "${CaptureDb.COL_CHUNK_ORDINAL} ASC"
        )
        return c.use {
            val acc = java.io.ByteArrayOutputStream()
            while (it.moveToNext()) {
                val blob = it.getBlob(0)
                acc.write(blob)
            }
            acc.toByteArray()
        }
    }

    // ============== 删除 / 清理 ==============

    fun deleteEntry(context: Context, txnId: Long): Int {
        val w = db(context)
        return deleteEntryTx(w, txnId)
    }

    /** 删除 timestampMs 早于 [cutoffMs] 的全部 entry 与其 chunks。 */
    fun deleteOlderThan(context: Context, cutoffMs: Long): Int {
        val w = db(context)
        w.beginTransaction()
        try {
            val ids = mutableListOf<Long>()
            w.rawQuery(
                "SELECT ${CaptureDb.COL_TXN_ID} FROM ${CaptureDb.TABLE_ENTRIES} WHERE ${CaptureDb.COL_TIMESTAMP}<?",
                arrayOf(cutoffMs.toString())
            ).use {
                while (it.moveToNext()) ids += it.getLong(0)
            }
            ids.forEach { id -> deleteEntryTx(w, id) }
            w.setTransactionSuccessful()
            return ids.size
        } finally {
            w.endTransaction()
        }
    }

    /** LRU trim 至最多 [maxEntries] 条(保留最新 N 条)。 返回删除条数。 */
    fun trimToMaxEntries(context: Context, maxEntries: Int): Int {
        if (maxEntries <= 0) return 0
        val w = db(context)
        w.beginTransaction()
        try {
            val ids = mutableListOf<Long>()
            w.rawQuery(
                """SELECT ${CaptureDb.COL_TXN_ID} FROM ${CaptureDb.TABLE_ENTRIES}
                   ORDER BY ${CaptureDb.COL_TIMESTAMP} DESC
                   LIMIT -1 OFFSET ?""".trimIndent(),
                arrayOf(maxEntries.toString())
            ).use {
                while (it.moveToNext()) ids += it.getLong(0)
            }
            ids.forEach { id -> deleteEntryTx(w, id) }
            w.setTransactionSuccessful()
            return ids.size
        } finally {
            w.endTransaction()
        }
    }

    fun clearAll(context: Context): Int {
        val w = db(context)
        w.beginTransaction()
        try {
            val n1 = w.delete(CaptureDb.TABLE_ENTRIES, null, null)
            w.delete(CaptureDb.TABLE_BODY_CHUNKS, null, null)
            w.delete(CaptureDb.TABLE_BREAKPOINT_RULES, null, null)
            w.setTransactionSuccessful()
            return n1
        } finally {
            w.endTransaction()
        }
    }

    // ============== 内部 helper ==============

    private fun deleteEntryTx(w: SQLiteDatabase, txnId: Long): Int {
        w.delete(CaptureDb.TABLE_BODY_CHUNKS, "${CaptureDb.COL_CHUNK_ENTRY_ID}=?", arrayOf(txnId.toString()))
        return w.delete(CaptureDb.TABLE_ENTRIES, "${CaptureDb.COL_TXN_ID}=?", arrayOf(txnId.toString()))
    }

    private fun nextOrdinal(w: SQLiteDatabase, txnId: Long, direction: Int): Int {
        val c = w.rawQuery(
            "SELECT MAX(${CaptureDb.COL_CHUNK_ORDINAL}) FROM ${CaptureDb.TABLE_BODY_CHUNKS} WHERE ${CaptureDb.COL_CHUNK_ENTRY_ID}=? AND ${CaptureDb.COL_CHUNK_DIRECTION}=?",
            arrayOf(txnId.toString(), direction.toString())
        )
        return c.use { if (it.moveToFirst()) (it.getInt(0) + 1) else 0 }
    }

    private fun entryToCv(e: CaptureEntry): ContentValues = ContentValues().apply {
        put(CaptureDb.COL_TXN_ID, e.txnId)
        put(CaptureDb.COL_TIMESTAMP, e.timestampMs)
        put(CaptureDb.COL_APP_NAME, e.appName)
        put(CaptureDb.COL_PACKAGE_NAME, e.packageName)
        put(CaptureDb.COL_SCHEME, e.scheme.ifBlank { "https" })
        put(CaptureDb.COL_METHOD, e.method.ifBlank { "GET" })
        put(CaptureDb.COL_HOST, e.host)
        put(CaptureDb.COL_PATH, e.path)
        put(CaptureDb.COL_HTTP_VERSION, e.httpVersion)
        put(CaptureDb.COL_REQUEST_HEADERS_JSON, gson.toJson(e.requestHeaders))
        put(CaptureDb.COL_REQUEST_CONTENT_TYPE, extractContentType(e.requestHeaders))
        put(CaptureDb.COL_REQUEST_BODY_TRUNCATED, if (e.requestBodyTruncated) 1 else 0)
        put(CaptureDb.COL_RESPONSE_STATUS, e.responseStatus)
        put(CaptureDb.COL_RESPONSE_HEADERS_JSON, gson.toJson(e.responseHeaders))
        put(CaptureDb.COL_RESPONSE_CONTENT_TYPE, extractContentType(e.responseHeaders))
        put(CaptureDb.COL_RESPONSE_BODY_TRUNCATED, if (e.responseBodyTruncated) 1 else 0)
        put(CaptureDb.COL_DURATION_MS, e.durationMs)
        put(CaptureDb.COL_ERROR, e.error)
        put(CaptureDb.COL_INTERCEPTED, if (e.intercepted) 1 else 0)
        put(CaptureDb.COL_TLS_META_JSON, if (e.tlsMeta != null) gson.toJson(e.tlsMeta) else null)
        put(CaptureDb.COL_REPLAYED, if (e.replayed) 1 else 0)
        put(CaptureDb.COL_SESSION_ID, e.sessionId)
    }

    private fun cvToEntry(c: Cursor): CaptureEntry {
        fun col(name: String): Int = c.getColumnIndexOrThrow(name)
        fun str(name: String): String = c.getString(col(name)) ?: ""
        fun n(name: String): Long = if (c.isNull(col(name))) 0L else c.getLong(col(name))
        fun i(name: String): Int = if (c.isNull(col(name))) 0 else c.getInt(col(name))
        fun b(name: String): Boolean = c.getInt(col(name)) != 0
        fun nullableStr(name: String): String? =
            if (c.isNull(col(name))) null else c.getString(col(name))

        val reqHeadersJson = str(CaptureDb.COL_REQUEST_HEADERS_JSON)
        val reqHeaders: Map<String, String> =
            if (reqHeadersJson.isBlank()) emptyMap()
            else try { gson.fromJson(reqHeadersJson, headersType) ?: emptyMap() } catch (_: Exception) { emptyMap() }

        val respHeadersJson = str(CaptureDb.COL_RESPONSE_HEADERS_JSON)
        val respHeaders: Map<String, String> =
            if (respHeadersJson.isBlank()) emptyMap()
            else try { gson.fromJson(respHeadersJson, headersType) ?: emptyMap() } catch (_: Exception) { emptyMap() }

        val tlsJson = nullableStr(CaptureDb.COL_TLS_META_JSON)
        val tls: TlsMeta? = if (tlsJson.isNullOrBlank()) null
            else try { gson.fromJson(tlsJson, tlsMetaType) } catch (_: Exception) { null }

        return CaptureEntry(
            txnId = n(CaptureDb.COL_TXN_ID),
            timestampMs = n(CaptureDb.COL_TIMESTAMP),
            appName = nullableStr(CaptureDb.COL_APP_NAME),
            packageName = nullableStr(CaptureDb.COL_PACKAGE_NAME),
            scheme = str(CaptureDb.COL_SCHEME).ifBlank { "https" },
            method = str(CaptureDb.COL_METHOD).ifBlank { "GET" },
            host = str(CaptureDb.COL_HOST),
            path = str(CaptureDb.COL_PATH),
            httpVersion = str(CaptureDb.COL_HTTP_VERSION),
            requestHeaders = reqHeaders,
            requestBodyPreview = null,
            requestBodyTruncated = b(CaptureDb.COL_REQUEST_BODY_TRUNCATED),
            responseStatus = i(CaptureDb.COL_RESPONSE_STATUS),
            responseHeaders = respHeaders,
            responseBodyPreview = null,
            responseBodyTruncated = b(CaptureDb.COL_RESPONSE_BODY_TRUNCATED),
            durationMs = n(CaptureDb.COL_DURATION_MS),
            error = nullableStr(CaptureDb.COL_ERROR),
            intercepted = b(CaptureDb.COL_INTERCEPTED),
            tlsMeta = tls,
            replayed = b(CaptureDb.COL_REPLAYED),
            sessionId = str(CaptureDb.COL_SESSION_ID)
        )
    }

    /** 从 headers 提取首位 content-type(大小写不敏感)。 */
    private fun extractContentType(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value

    /** 把 body chunks 拆为 [CaptureDb.CHUNK_SIZE] 块入库。 */
    fun putBodyChunked(
        context: Context,
        txnId: Long,
        direction: Int,
        body: ByteArray
    ) {
        if (body.isEmpty()) return
        val w = db(context)
        w.use {
            w.beginTransaction()
            try {
                var ord = nextOrdinal(w, txnId, direction)
                var off = 0
                while (off < body.size) {
                    val next = minOf(off + CaptureDb.CHUNK_SIZE, body.size)
                    val frame = body.copyOfRange(off, next)
                    val cv = ContentValues().apply {
                        put(CaptureDb.COL_CHUNK_ENTRY_ID, txnId)
                        put(CaptureDb.COL_CHUNK_DIRECTION, direction)
                        put(CaptureDb.COL_CHUNK_ORDINAL, ord)
                        put(CaptureDb.COL_CHUNK_BLOB, frame)
                    }
                    w.insertWithOnConflict(CaptureDb.TABLE_BODY_CHUNKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    ord += 1
                    off = next
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
    }

    /**
     * 将 entry 全套(req/resp) 同步入体; 提高不全 entry 的整包导出能力。
     * 先 upsert entries 表, 再分别建 req/resp 各部 body chunk。
     */
    fun upsertEntryWithBody(
        context: Context,
        e: CaptureEntry,
        requestBody: ByteArray?,
        responseBody: ByteArray?
    ) {
        upsertEntry(context, e)
        val w = db(context)
        w.use {
            w.beginTransaction()
            try {
                if (requestBody != null && requestBody.isNotEmpty()) {
                    w.delete(CaptureDb.TABLE_BODY_CHUNKS, "${CaptureDb.COL_CHUNK_ENTRY_ID}=? AND ${CaptureDb.COL_CHUNK_DIRECTION}=?",
                        arrayOf(e.txnId.toString(), CaptureChunkDirection.REQUEST.toString()))
                    var ord = 0
                    var off = 0
                    while (off < requestBody.size) {
                        val next = minOf(off + CaptureDb.CHUNK_SIZE, requestBody.size)
                        val cv = ContentValues().apply {
                            put(CaptureDb.COL_CHUNK_ENTRY_ID, e.txnId)
                            put(CaptureDb.COL_CHUNK_DIRECTION, CaptureChunkDirection.REQUEST)
                            put(CaptureDb.COL_CHUNK_ORDINAL, ord)
                            put(CaptureDb.COL_CHUNK_BLOB, requestBody.copyOfRange(off, next))
                        }
                        w.insertWithOnConflict(CaptureDb.TABLE_BODY_CHUNKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                        ord += 1
                        off = next
                    }
                }
                if (responseBody != null && responseBody.isNotEmpty()) {
                    w.delete(CaptureDb.TABLE_BODY_CHUNKS, "${CaptureDb.COL_CHUNK_ENTRY_ID}=? AND ${CaptureDb.COL_CHUNK_DIRECTION}=?",
                        arrayOf(e.txnId.toString(), CaptureChunkDirection.RESPONSE.toString()))
                    var ord = 0
                    var off = 0
                    while (off < responseBody.size) {
                        val next = minOf(off + CaptureDb.CHUNK_SIZE, responseBody.size)
                        val cv = ContentValues().apply {
                            put(CaptureDb.COL_CHUNK_ENTRY_ID, e.txnId)
                            put(CaptureDb.COL_CHUNK_DIRECTION, CaptureChunkDirection.RESPONSE)
                            put(CaptureDb.COL_CHUNK_ORDINAL, ord)
                            put(CaptureDb.COL_CHUNK_BLOB, responseBody.copyOfRange(off, next))
                        }
                        w.insertWithOnConflict(CaptureDb.TABLE_BODY_CHUNKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                        ord += 1
                        off = next
                    }
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
    }
}
