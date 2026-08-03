package com.HanFeng.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 抓包配置 + 快照持久化(跨 VPN 重启)。
 *
 * - 关抓包时不持久化条目;enable 配置可持久化以便下次 VPN 启动 syncFromPrefs 恢复
 * - snapshot 条目采用 HAR 等价的 JSON, 但带解析器熟悉的字段; 仅保留最近 [SNAPSHOT_MAX] 条限制大小
 *
 * 引用 design.md Components #9 / requirements R3.5。
 */
object CaptureRepository {

    private const val PREFS_NAME = "capture_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODE = "mode"
    private const val KEY_TARGET_APPS = "target_apps"
    private const val KEY_MAX_ENTRIES = "max_entries"
    private const val KEY_RECENT_ENTRIES = "recent_entries_json"
    private const val KEY_BREAKPOINT_RULES = "breakpoint_rules_json"
    // —— 批次 D 联动: 抓包专用设置项 ——
    private const val KEY_BODY_PREVIEW_BYTES_ALL = "body_preview_bytes_all"
    private const val KEY_BODY_PREVIEW_BYTES_BY_APP = "body_preview_bytes_by_app"
    private const val KEY_BATCH_INTERVAL_MS = "batch_interval_ms"
    private const val KEY_AUTO_SCROLL = "auto_scroll"
    private const val KEY_REDACT_EXPORT = "redact_export"
    private const val KEY_CONFIRM_RISKY_REPLAY = "confirm_risky_replay"
    private const val KEY_NOTIFY_ACTIVE = "notify_active"
    // —— 批次 E1 历史落盘 retention —
    private const val KEY_MAX_STORE_ENTRIES = "max_store_entries"
    private const val KEY_MAX_AGE_DAYS = "max_age_days"
    private const val SNAPSHOT_MAX = 50

    data class PersistedConfig(
        val enabled: Boolean,
        val mode: CaptureController.Mode,
        val targetApps: Set<String>,
        val maxEntries: Int,
        /** 25%/50%/75%/100% → 8KB/32KB/256KB/1MB(粗量纲 50/70/90 三档 UI 抽象回填字节上限)。 */
        val bodyPreviewBytesAll: Int = 8 * 1024,
        val bodyPreviewBytesByApp: Int = 32 * 1024,
        val batchIntervalMs: Long = 200L,
        val autoScroll: Boolean = true,
        val redactExport: Boolean = true,
        val confirmRiskyReplay: Boolean = true,
        val notifyActive: Boolean = true,
        /** 批次 E1: 持久化层最多保留条数; 0 = 不限制(磁盘自管)。 */
        val maxStoreEntries: Int = 5000,
        /** 批次 E1: 持久化层最多保留天数; 0 = 永久。 */
        val maxAgeDays: Int = 7
    )

    fun saveConfig(
        context: Context,
        enabled: Boolean,
        mode: CaptureController.Mode,
        targetApps: Set<String>,
        maxEntries: Int
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_MODE, mode.name)
            .putStringSet(KEY_TARGET_APPS, targetApps)
            .putInt(KEY_MAX_ENTRIES, maxEntries)
            .apply()
    }

    /** 批次 D 联动: 仅保存抓包设置项(不动 enabled/mode/maxEntries/targetApps)。 */
    fun saveCaptureSettings(
        context: Context,
        bodyPreviewBytesAll: Int,
        bodyPreviewBytesByApp: Int,
        batchIntervalMs: Long,
        autoScroll: Boolean,
        redactExport: Boolean,
        confirmRiskyReplay: Boolean,
        notifyActive: Boolean,
        maxStoreEntries: Int = 5000,
        maxAgeDays: Int = 7
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_BODY_PREVIEW_BYTES_ALL, bodyPreviewBytesAll)
            .putInt(KEY_BODY_PREVIEW_BYTES_BY_APP, bodyPreviewBytesByApp)
            .putLong(KEY_BATCH_INTERVAL_MS, batchIntervalMs)
            .putBoolean(KEY_AUTO_SCROLL, autoScroll)
            .putBoolean(KEY_REDACT_EXPORT, redactExport)
            .putBoolean(KEY_CONFIRM_RISKY_REPLAY, confirmRiskyReplay)
            .putBoolean(KEY_NOTIFY_ACTIVE, notifyActive)
            .putInt(KEY_MAX_STORE_ENTRIES, maxStoreEntries)
            .putInt(KEY_MAX_AGE_DAYS, maxAgeDays)
            .apply()
    }

    // ==================== 断点规则持久化(缺口 1) ====================

    /**
     * 将当前 [BreakpointRepo.rules] 写入 prefs(design correctness 4)。
     * 调用时机: 用户在断点管理界面 add/remove 规则后; VPN 关停时。
     */
    fun snapshotBreakpointRules(context: Context, rules: List<BreakpointMatchRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            val o = JSONObject()
            o.put("host", r.host)
            o.put("method", r.method ?: JSONObject.NULL)
            o.put("pathPrefix", r.pathPrefix ?: JSONObject.NULL)
            o.put("kind", r.kind.name)
            arr.put(o)
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BREAKPOINT_RULES, arr.toString()).apply()
    }

    /**
     * VPN 启动后读 prefs 把规则回填到 [BreakpointRepo]。
     * 与 [CaptureController.syncFromPrefs] 同步流程触发。
     */
    fun loadBreakpointRules(context: Context): List<BreakpointMatchRule> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_BREAKPOINT_RULES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val method = if (o.isNull("method")) null else o.getString("method")
                val pathPrefix = if (o.isNull("pathPrefix")) null else o.getString("pathPrefix")
                BreakpointMatchRule(
                    host = o.getString("host"),
                    method = method,
                    pathPrefix = pathPrefix,
                    kind = runCatching { BreakpointKind.valueOf(o.getString("kind")) }.getOrDefault(BreakpointKind.REQUEST)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun loadConfig(context: Context): PersistedConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PersistedConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            mode = runCatching { CaptureController.Mode.valueOf(prefs.getString(KEY_MODE, null) ?: "BY_APP") }
                .getOrDefault(CaptureController.Mode.BY_APP),
            targetApps = prefs.getStringSet(KEY_TARGET_APPS, emptySet()) ?: emptySet(),
            maxEntries = prefs.getInt(KEY_MAX_ENTRIES, CaptureRingBuffer.DEFAULT_CAPACITY),
            bodyPreviewBytesAll = prefs.getInt(KEY_BODY_PREVIEW_BYTES_ALL, 8 * 1024),
            bodyPreviewBytesByApp = prefs.getInt(KEY_BODY_PREVIEW_BYTES_BY_APP, 32 * 1024),
            batchIntervalMs = prefs.getLong(KEY_BATCH_INTERVAL_MS, 200L),
            autoScroll = prefs.getBoolean(KEY_AUTO_SCROLL, true),
            redactExport = prefs.getBoolean(KEY_REDACT_EXPORT, true),
            confirmRiskyReplay = prefs.getBoolean(KEY_CONFIRM_RISKY_REPLAY, true),
            notifyActive = prefs.getBoolean(KEY_NOTIFY_ACTIVE, true),
            maxStoreEntries = prefs.getInt(KEY_MAX_STORE_ENTRIES, 5000),
            maxAgeDays = prefs.getInt(KEY_MAX_AGE_DAYS, 7)
        )
    }

    /**
     * 把当前 ring buffer 内最近条目快照写盘(design 现有组件改动表 syncFromPrefs; 上限 [SNAPSHOT_MAX])。
     * 注意: ring buffer 中 body preview 已被截断, 持久化占用可控(<32KB / 条)。
     */
    fun snapshotRingBuffer(context: Context, entries: List<CaptureEntry>) {
        val arr = JSONArray()
        entries.take(SNAPSHOT_MAX).forEach { e ->
            arr.put(encodeEntry(e))
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECENT_ENTRIES, arr.toString()).apply()
    }

    fun loadSnapshot(context: Context): List<CaptureEntry> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RECENT_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { decodeEntry(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun clearSnapshot(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_RECENT_ENTRIES).apply()
    }

    private fun encodeEntry(e: CaptureEntry): JSONObject {
        val o = JSONObject()
        o.put("txnId", e.txnId)
        o.put("timestampMs", e.timestampMs)
        if (e.appName != null) o.put("appName", e.appName)
        if (e.packageName != null) o.put("packageName", e.packageName)
        o.put("scheme", e.scheme)
        o.put("method", e.method)
        o.put("host", e.host)
        o.put("path", e.path)
        o.put("httpVersion", e.httpVersion)
        val rh = JSONArray()
        e.requestHeaders.forEach { (k, v) -> rh.put(JSONObject().put("k", k).put("v", v)) }
        o.put("requestHeaders", rh)
        if (e.requestBodyPreview != null) {
            o.put("requestBody_b64",
                android.util.Base64.encodeToString(e.requestBodyPreview, android.util.Base64.NO_WRAP))
        }
        o.put("requestBodyTruncated", e.requestBodyTruncated)
        o.put("responseStatus", e.responseStatus)
        val rsh = JSONArray()
        e.responseHeaders.forEach { (k, v) -> rsh.put(JSONObject().put("k", k).put("v", v)) }
        o.put("responseHeaders", rsh)
        if (e.responseBodyPreview != null) {
            o.put("responseBody_b64",
                android.util.Base64.encodeToString(e.responseBodyPreview, android.util.Base64.NO_WRAP))
        }
        o.put("responseBodyTruncated", e.responseBodyTruncated)
        o.put("durationMs", e.durationMs)
        if (e.error != null) o.put("error", e.error)
        o.put("intercepted", e.intercepted)
        o.put("replayed", e.replayed)
        // 批次 C3: 会话隔离持久化
        if (e.sessionId.isNotEmpty()) o.put("sessionId", e.sessionId)
        if (e.tlsMeta != null) {
            val t = JSONObject()
            t.put("sni", e.tlsMeta.sni ?: "")
            t.put("protocol", e.tlsMeta.protocol)
            t.put("cipherSuite", e.tlsMeta.cipherSuite)
            t.put("alpn", e.tlsMeta.alpn ?: "")
            t.put("error", e.tlsMeta.error ?: "")
            t.put("certs", e.tlsMeta.peerCertificates.size)
            o.put("tlsMeta", t)
        }
        return o
    }

    private fun decodeEntry(o: JSONObject): CaptureEntry {
        val rh = LinkedHashMap<String, String>()
        val rha = o.optJSONArray("requestHeaders")
        if (rha != null) for (i in 0 until rha.length()) {
            val h = rha.getJSONObject(i); rh[h.getString("k")] = h.getString("v")
        }
        val rsh = LinkedHashMap<String, String>()
        val rsha = o.optJSONArray("responseHeaders")
        if (rsha != null) for (i in 0 until rsha.length()) {
            val h = rsha.getJSONObject(i); rsh[h.getString("k")] = h.getString("v")
        }
        val reqBody = o.optString("requestBody_b64", "").let {
            if (it.isBlank()) null else android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        }
        val respBody = o.optString("responseBody_b64", "").let {
            if (it.isBlank()) null else android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        }
        val tlsMeta: TlsMeta? = o.optJSONObject("tlsMeta")?.let { t ->
            val certCount = t.optInt("certs", 0)
            TlsMeta(
                sni = optStrOrNull(t, "sni"),
                protocol = t.optString("protocol"),
                cipherSuite = t.optString("cipherSuite"),
                alpn = optStrOrNull(t, "alpn"),
                peerCertificates = List(certCount) { CertMeta(
                    subject = "", issuer = "", notBefore = 0, notAfter = 0, sha256Fingerprint = ""
                ) },
                error = optStrOrNull(t, "error")
            )
        }
        return CaptureEntry(
            txnId = o.getLong("txnId"),
            timestampMs = o.getLong("timestampMs"),
            appName = optStrOrNull(o, "appName"),
            packageName = optStrOrNull(o, "packageName"),
            scheme = o.getString("scheme"),
            method = o.getString("method"),
            host = o.getString("host"),
            path = o.getString("path"),
            httpVersion = o.getString("httpVersion"),
            requestHeaders = rh,
            requestBodyPreview = reqBody,
            requestBodyTruncated = o.optBoolean("requestBodyTruncated"),
            responseStatus = o.optInt("responseStatus"),
            responseHeaders = rsh,
            responseBodyPreview = respBody,
            responseBodyTruncated = o.optBoolean("responseBodyTruncated"),
            durationMs = o.optLong("durationMs"),
            error = optStrOrNull(o, "error"),
            intercepted = o.optBoolean("intercepted"),
            tlsMeta = tlsMeta,
            replayed = o.optBoolean("replayed"),
            sessionId = o.optString("sessionId", "")
        )
    }

    private fun optStrOrNull(o: JSONObject, k: String): String? {
        val v = o.optString(k, "")
        return v.ifBlank { null }
    }
}
