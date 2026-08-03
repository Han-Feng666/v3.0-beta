package com.HanFeng.capture

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 重放引擎: 由模板出发, 用系统默认 CA 信任链发起一次同步 HTTP 请求, 把结果写入 [CaptureController]。
 *
 * - 真入网(design correctness 7): 不复用本地自签 CA, 走系统默认信任链。
 * - 重放产生的 entry 标记 `replayed=true`, 且 txnId 由 [CaptureEntry.makeReplayTxnId] 高位隔离, 不与正常抓包冲突。
 * - 仅在 active 时插入 ring buffer; inactive 时仍然执行请求但结果不入库(design note)。
 *
 * 引用 design.md Components #6 / requirements R9。
 */
object CaptureReplayEngine {

    private val replaySeq = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 设定单次重放超时 (ms)。响应体超过该阈值截断, 标记为 [CaptureEntry.ERROR_REPLAY_TRUNCATED]。
     */
    const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    const val DEFAULT_READ_TIMEOUT_MS = 15_000
    private const val BODY_PREVIEW_CAP = 32 * 1024

    data class ReplayResult(
        val txnId: Long,
        val success: Boolean,
        val errorMessage: String?,
        /** 批次 E5c: 表示该条目因用户取消而跳过, 未实际发起网络请求。 */
        val cancelled: Boolean = false
    )

    /**
     * 同步发起重放。
     *
     * @param template 用户保存的模板
     * @return 写入 [CaptureController] 后的 txnId
     */
    fun replay(template: CaptureTemplate): ReplayResult {
        val txnId = CaptureEntry.makeReplayTxnId(replaySeq.incrementAndGet())
        var conn: HttpURLConnection? = null
        try {
            val url = URL(template.scheme, template.host, if (template.path.startsWith("/")) template.path else "/${template.path}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = DEFAULT_CONNECT_TIMEOUT_MS
                readTimeout = DEFAULT_READ_TIMEOUT_MS
                requestMethod = template.method
                template.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (template.body != null && template.body.isNotEmpty() && template.method in setOf("POST", "PUT", "PATCH")) {
                    doOutput = true
                    outputStream.use { it.write(template.body) }
                }
            }
            // 触发包网/读响应
            val status = conn.responseCode
            val respHeaders = LinkedHashMap<String, String>()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && values.isNotEmpty()) {
                    respHeaders[key.lowercase()] = values.first()
                }
            }
            val bodyStream = if (status in 200..399) conn.inputStream else conn.errorStream
            val rawBody: ByteArray? = bodyStream?.use { runCatching { it.readBytes() }.getOrNull() }
            val rawBodyLen = rawBody?.size ?: 0
            val truncated: Boolean = rawBody != null && rawBodyLen > BODY_PREVIEW_CAP
            val body: ByteArray? = when {
                rawBody == null -> null
                truncated -> rawBody.copyOf(BODY_PREVIEW_CAP)
                else -> rawBody
            }

            // 写入 CaptureController 的 Phase=Request 阶段
            val reqEntry = CaptureEntry(
                txnId = txnId,
                timestampMs = System.currentTimeMillis(),
                appName = "CaptureReplay",
                packageName = null,
                scheme = template.scheme,
                method = template.method,
                host = template.host,
                path = template.path,
                httpVersion = "HTTP/1.1",
                requestHeaders = template.headers,
                requestBodyPreview = template.body,
                requestBodyTruncated = false,
                responseStatus = status,
                responseHeaders = respHeaders,
                responseBodyPreview = body,
                responseBodyTruncated = truncated,
                durationMs = 0L,
                error = if (body == null && status >= 400) CaptureEntry.ERROR_REPLAY_TRUNCATED else null,
                intercepted = false,
                tlsMeta = null,
                replayed = true,
                sessionId = "replay-$txnId"
            )
            CaptureController.insertReplayedEntry(reqEntry)
            return ReplayResult(txnId, success = status in 200..399, errorMessage = null)
        } catch (e: Throwable) {
            // 即使请求失败也写入一条 entry 供用户观察错误
            val reqEntry = CaptureEntry(
                txnId = txnId,
                timestampMs = System.currentTimeMillis(),
                appName = "CaptureReplay",
                packageName = null,
                scheme = template.scheme,
                method = template.method,
                host = template.host,
                path = template.path,
                httpVersion = "HTTP/1.1",
                requestHeaders = template.headers,
                requestBodyPreview = template.body,
                requestBodyTruncated = false,
                responseStatus = 0,
                responseHeaders = emptyMap(),
                responseBodyPreview = null,
                responseBodyTruncated = false,
                durationMs = 0L,
                error = "replay-failed:${e.javaClass.simpleName}:${e.message}",
                intercepted = false,
                tlsMeta = null,
                replayed = true,
                sessionId = "replay-$txnId"
            )
            CaptureController.insertReplayedEntry(reqEntry)
            return ReplayResult(txnId, success = false, errorMessage = e.message)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 批次 D: 批量重放。
     *
     * - 同步顺序执行, 默认每条间隔 [DEFAULT_BATCH_INTERVAL_MS] ms 防压垮上游
     * - 单条失败不影响后续执行, 全部结果按入参顺序回传
     * - 用于"HAR 中选多条 → 一键批量重放"的 GUI 路径(由 worker 线程调用)
     *
     * 批次 E5c: 可选 [cancellationToken] — 在每条执行前原子检查, 一旦置位即中止剩余条目,
     *       返回一条 [ReplayResult] 标记 cancelled=true, 跳过后续。
     */
    fun replayBatch(
        templates: List<CaptureTemplate>,
        intervalMs: Long = DEFAULT_BATCH_INTERVAL_MS,
        cancellationToken: java.util.concurrent.atomic.AtomicBoolean? = null
    ): List<ReplayResult> {
        if (templates.isEmpty()) return emptyList()
        val out = ArrayList<ReplayResult>(templates.size)
        var i = 0
        val n = templates.size
        while (i < n) {
            val t = templates[i]
            if (cancellationToken?.get() == true) {
                // 一次性中止, 剩余条目不再导入
                break
            }
            out += replay(t)
            if (i != n - 1 && intervalMs > 0) {
                runCatching { Thread.sleep(intervalMs) }
            }
            i++
        }
        // 把被取消掉剩余条目以 cancelled=true 占位返回(顺序保持一致)
        while (i < n) {
            out += ReplayResult(
                txnId = 0L,
                success = false,
                errorMessage = "cancelled",
                cancelled = true
            )
            i++
        }
        return out
    }

    /**
     * 批次 D: 顺序重放但回调每条完成事件, 用于 GUI 实时进度展示。
     * 在调用方线程同步执行 — 调用方应自行派 worker。
     *
     * 批次 E5c: 可选 [cancellationToken] — 置位后剩余条目跳过, onProgress 收到 cancelled=true 结果。
     */
    fun replayBatchStreaming(
        templates: List<CaptureTemplate>,
        intervalMs: Long = DEFAULT_BATCH_INTERVAL_MS,
        onProgress: (index: Int, total: Int, result: ReplayResult) -> Unit,
        cancellationToken: java.util.concurrent.atomic.AtomicBoolean? = null
    ) {
        val total = templates.size
        var i = 0
        while (i < total) {
            val t = templates[i]
            if (cancellationToken?.get() == true) break
            val r = replay(t)
            onProgress(i, total, r)
            if (i != total - 1 && intervalMs > 0) {
                runCatching { Thread.sleep(intervalMs) }
            }
            i++
        }
        while (i < total) {
            onProgress(
                i, total,
                ReplayResult(
                    txnId = 0L,
                    success = false,
                    errorMessage = "cancelled",
                    cancelled = true
                )
            )
            i++
        }
    }

    /** 批次 D: 批量重放默认间隔, 防止压垮上游与本地 socket table 耗尽。 */
    const val DEFAULT_BATCH_INTERVAL_MS = 200L
}
