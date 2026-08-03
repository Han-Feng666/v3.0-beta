package com.HanFeng.capture

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 抓包条目导出: HAR 1.2 (HTTP Archive) + cURL 单条 + 纯文本汇总。
 *
 * - HAR 不携带 TLS 元数据(标准未定义), 仅 HTTP/1.1 等价字段
 * - cURL 仅在单条导出时使用; 多条导出退化为 HAR
 * - 所有时间戳 ISO 8601 UTC
 *
 * 引用 design.md Components #8 / requirements R10。
 */
object CaptureExporter {

    enum class Format { HAR, CURL, PLAIN_SUMMARY }

    private val isoUtc by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun export(
        entries: List<CaptureEntry>,
        format: Format,
        preferLanguage: String = "zh",
        /** 控制是否脱敏 Authorization/Cookie/Set-Cookie/X-Token 字段(默认 true, R7.4)。 */
        redactMode: Boolean = true
    ): ByteArray {
        val sanitized = if (redactMode) entries.map(::redactEntry) else entries
        return when (format) {
            Format.HAR -> exportHar(sanitized).toByteArray(Charsets.UTF_8)
            Format.CURL -> if (sanitized.size == 1) exportCurl(sanitized.first()).toByteArray(Charsets.UTF_8)
            else sanitized.joinToString("\n\n") { exportCurl(it) }.toByteArray(Charsets.UTF_8)
            Format.PLAIN_SUMMARY -> exportPlainSummary(sanitized, preferLanguage).toByteArray(Charsets.UTF_8)
        }
    }

    /** 脱敏 entry 内敏感 header — 用 "**REDACTED**" 替换 Authorization/Cookie/Set-Cookie/X-Token 类。 */
    private fun redactEntry(e: CaptureEntry): CaptureEntry {
        val sensitiveKeys = setOf("authorization", "cookie", "set-cookie", "x-token", "x-auth-token", "proxy-authorization")
        fun redactKeys(m: Map<String, String>): Map<String, String> =
            m.mapValues { (k, v) ->
                if (sensitiveKeys.contains(k.lowercase())) "***" else v
            }
        return e.copy(
            requestHeaders = redactKeys(e.requestHeaders),
            responseHeaders = redactKeys(e.responseHeaders)
        )
    }

    private fun iso(ts: Long): String = isoUtc.format(Date(ts))

    private fun exportHar(entries: List<CaptureEntry>): String {
        val log = JSONArray()
        entries.forEach { entry ->
            val e = JSONObject()
            // request
            val req = JSONObject()
            req.put("method", entry.method)
            req.put("url", "${entry.scheme}://${entry.host}${entry.path}")
            req.put("httpVersion", entry.httpVersion)
            val reqHeaders = JSONArray()
            entry.requestHeaders.forEach { (k, v) ->
                reqHeaders.put(JSONObject().put("name", k).put("value", v))
            }
            req.put("headers", reqHeaders)
            // 批次 D: 解析 path 上的 query 字段为 HAR spec 要求的 queryString 数组
            req.put("queryString", buildQueryStringArray(entry.path))
            if (entry.requestBodyPreview != null) {
                val post = JSONObject()
                post.put("mimeType", entry.requestHeaders["content-type"] ?: "application/octet-stream")
                post.put("text", base64IfBinary(entry.requestBodyPreview))
                post.put("encoding", if (looksLikeText(entry.requestBodyPreview)) "" else "base64")
                req.put("postData", post)
            }
            req.put("headersSize", -1)
            req.put("bodySize", entry.requestBodyPreview?.size ?: 0)
            e.put("request", req)

            // response
            val resp = JSONObject()
            resp.put("status", entry.responseStatus)
            resp.put("statusText", statusText(entry.responseStatus))
            resp.put("httpVersion", entry.httpVersion)
            val respHeaders = JSONArray()
            entry.responseHeaders.forEach { (k, v) ->
                respHeaders.put(JSONObject().put("name", k).put("value", v))
            }
            resp.put("headers", respHeaders)
            if (entry.responseBodyPreview != null) {
                val content = JSONObject()
                content.put("size", entry.responseBodyPreview.size)
                content.put("mimeType", entry.responseHeaders["content-type"] ?: "application/octet-stream")
                content.put("text", base64IfBinary(entry.responseBodyPreview))
                content.put("encoding", if (looksLikeText(entry.responseBodyPreview)) "" else "base64")
                resp.put("content", content)
            }
            resp.put("redirectURL", entry.responseHeaders["location"] ?: "")
            resp.put("headersSize", -1)
            resp.put("bodySize", entry.responseBodyPreview?.size ?: 0)
            e.put("response", resp)

            // 批次 D: timings 完整化 — wait 用 durationMs, 其余按 rules of thumb 兜底
            val timings = JSONObject()
            timings.put("blocked", 0)
            timings.put("dns", -1)
            timings.put("connect", -1)
            timings.put("send", 0)
            timings.put("wait", entry.durationMs)
            timings.put("receive", 0)
            e.put("startedDateTime", iso(entry.timestampMs))
            e.put("time", entry.durationMs)
            e.put("cache", JSONObject())
            e.put("timings", timings)
            // 批次 D: 扩展下划线字段(HAR 1.2 允许 custom 字段, 兼容 Chromedevtools/Charles)
            if (entry.replayed) e.put("_replayed", true)
            if (entry.intercepted) e.put("_intercepted", true)
            if (entry.sessionId.isNotEmpty()) e.put("_sessionId", entry.sessionId)
            if (entry.error != null) e.put("_error", entry.error)
            entry.tlsMeta?.let { tm ->
                val ext = JSONObject()
                ext.put("sni", tm.sni ?: "")
                ext.put("protocol", tm.protocol)
                ext.put("cipherSuite", tm.cipherSuite)
                ext.put("alpn", tm.alpn ?: "")
                if (tm.error != null) ext.put("error", tm.error)
                if (tm.peerCertificates.isNotEmpty()) {
                    val arr = JSONArray()
                    tm.peerCertificates.forEach { c ->
                        arr.put(JSONObject()
                            .put("subject", c.subject)
                            .put("issuer", c.issuer)
                            .put("validFrom", iso(c.notBefore))
                            .put("validTo", iso(c.notAfter))
                            .put("sha256", c.sha256Fingerprint))
                    }
                    ext.put("certificates", arr)
                }
                e.put("_tls", ext)
            }
            log.put(e)
        }
        val root = JSONObject()
        val logObj = JSONObject()
        logObj.put("version", "1.2")
        logObj.put("creator", JSONObject()
            .put("name", "HanFengAdBlocker")
            .put("version", "1.0")
            .put("_ua", "HanFengAdBlocker/CaptureModule"))
        // 批次 D: log 内含导出版本号与注释, 便于下游工具识别来源
        logObj.put("_comment", "redact=default;exportedAt=${iso(System.currentTimeMillis())}")
        logObj.put("pages", JSONArray())
        logObj.put("entries", log)
        root.put("log", logObj)
        return root.toString(2)
    }

    /**
     * 批次 D: 把 path 中 `?k1=v1&k2=v2` 拆为 HAR query array(name/value 对象列表)。
     * - 不再编码: HAR spec 不要求 URL-encode, 原样保留(group '&' 切开, 第一段 '=' 切开)
     * - 无 path 或无 '?' 时返回空数组
     */
    private fun buildQueryStringArray(path: String): JSONArray {
        val arr = JSONArray()
        val q = path.substringAfter('?', "")
        if (q.isEmpty()) return arr
        q.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val eq = pair.indexOf('=')
            val (name, value) = if (eq > 0) pair.substring(0, eq) to pair.substring(eq + 1)
            else pair to ""
            arr.put(JSONObject().put("name", name).put("value", value))
        }
        return arr
    }

    private fun exportCurl(entry: CaptureEntry): String {
        val sb = StringBuilder()
        sb.append("curl -X ").append(entry.method)
        val headers = entry.requestHeaders.filterKeys { it.lowercase() !in setOf("host", "content-length") }
        headers.forEach { (k, v) ->
            sb.append(" \\\n  -H '").append(escapeSingle(k)).append(": ").append(escapeSingle(v)).append("'")
        }
        sb.append(" \\\n  '").append(escapeSingle("${entry.scheme}://${entry.host}${entry.path}")).append("'")
        if (entry.requestBodyPreview != null && entry.requestBodyPreview.isNotEmpty()) {
            sb.append(" \\\n  --data-raw '").append(escapeSingle(previewText(entry.requestBodyPreview))).append("'")
        }
        return sb.toString()
    }

    private fun exportPlainSummary(entries: List<CaptureEntry>, lang: String): String {
        val labels = labelsFor(lang)
        val sb = StringBuilder()
        sb.append(labels.summaryHeader).append("\n")
        sb.append("Total: ${entries.size}\n\n")
        entries.forEachIndexed { i, e ->
            sb.append("#${i + 1} ")
                .append(e.method).append(' ')
                .append(e.scheme).append("://").append(e.host).append(e.path)
                .append("  [").append(e.responseStatus).append("] ")
                .append(if (e.replayed) labels.replayMarker else "")
                .append('\n')
            if (e.error != null) sb.append(" error=${e.error}\n")
            if (e.tlsMeta != null) {
                sb.append(" TLS ${e.tlsMeta.protocol} ${e.tlsMeta.cipherSuite} alpn=${e.tlsMeta.alpn ?: "-"}\n")
            }
        }
        return sb.toString()
    }

    private fun labelsFor(lang: String) = when (lang) {
        "zh" -> Labels(
            summaryHeader = "抓包导出汇总",
            replayMarker = "(重放)"
        )
        else -> Labels(
            summaryHeader = "Capture Export Summary",
            replayMarker = "(replay)"
        )
    }

    private data class Labels(val summaryHeader: String, val replayMarker: String)

    private fun looksLikeText(body: ByteArray): Boolean {
        if (body.isEmpty()) return true
        var bin = 0
        for (i in body.indices) {
            val b = body[i].toInt() and 0xFF
            if (b < 0x09 || (b in 0x0E..0x1F) || b == 0x7F) bin++
        }
        return bin * 100 / body.size < 5
    }

    private fun previewText(body: ByteArray): String =
        if (looksLikeText(body)) String(body, Charsets.UTF_8)
        else android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP)

    private fun base64IfBinary(body: ByteArray): String =
        if (looksLikeText(body)) String(body, Charsets.UTF_8)
        else android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP)

    private fun escapeSingle(s: String): String = s.replace("'", "'\\''")

    private fun statusText(s: Int): String = when (s) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> ""
    }
}
