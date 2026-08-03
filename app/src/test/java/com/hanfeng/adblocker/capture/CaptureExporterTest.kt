package com.hanfeng.adblocker.capture

import com.HanFeng.capture.CaptureEntry
import com.HanFeng.capture.CaptureExporter
import com.HanFeng.capture.TlsMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureExporterTest {

    private fun makeEntry(
        txnId: Long,
        method: String = "GET",
        host: String = "h.example.com",
        path: String = "/v1/x",
        status: Int = 200,
        body: ByteArray? = null,
        replayed: Boolean = false,
        tlsMeta: TlsMeta? = null
    ): CaptureEntry {
        val reqHeaders = LinkedHashMap<String, String>()
        reqHeaders["host"] = host
        if (body != null) reqHeaders["content-type"] = "application/json"
        return CaptureEntry(
            txnId = txnId,
            timestampMs = 1_700_000_000_000L + txnId * 1000,
            appName = "Test",
            packageName = "com.test",
            scheme = "https",
            method = method,
            host = host,
            path = path,
            httpVersion = "HTTP/1.1",
            requestHeaders = reqHeaders,
            requestBodyPreview = body,
            requestBodyTruncated = false,
            responseStatus = status,
            responseHeaders = if (status != 0) mapOf("content-type" to "application/json") else emptyMap(),
            responseBodyPreview = if (status != 0) (body ?: "{\"ok\":true}".toByteArray()) else null,
            responseBodyTruncated = false,
            durationMs = 100L,
            replayed = replayed,
            tlsMeta = tlsMeta
        )
    }

    @Before
    fun setUp() {
        // android.util.Base64 是 android stub: 单测中会被 mockito-core inline mock maker 拦不住;
        // 因此 Exporter 单测严格上必须用 robolectric 等。无 robolectric 时仅验证非 base64 文本路径。
    }

    @Test
    fun `HAR 导出含 log version 1_2 与至少一条 entry`() {
        val entries = listOf(makeEntry(1L))
        val out = CaptureExporter.export(entries, CaptureExporter.Format.HAR)
        val root = JSONObject(String(out, Charsets.UTF_8))
        assertEquals("1.2", root.getJSONObject("log").getString("version"))
        val arr = root.getJSONObject("log").getJSONArray("entries")
        assertEquals(1, arr.length())
        val e = arr.getJSONObject(0)
        assertEquals("GET", e.getJSONObject("request").getString("method"))
        assertEquals("https://h.example.com/v1/x", e.getJSONObject("request").getString("url"))
        assertEquals(200, e.getJSONObject("response").getInt("status"))
    }

    @Test
    fun `HAR 多条 entries 数量一致`() {
        val entries = (1L..5L).map { makeEntry(it) }
        val out = CaptureExporter.export(entries, CaptureExporter.Format.HAR)
        val arr = JSONObject(String(out, Charsets.UTF_8)).getJSONObject("log").getJSONArray("entries")
        assertEquals(5, arr.length())
    }

    @Test
    fun `HAR 标记 replayed=true 的 entry 在 __replayed 字段下记录`() {
        val entries = listOf(makeEntry(1L, replayed = true))
        val out = CaptureExporter.export(entries, CaptureExporter.Format.HAR)
        val e = JSONObject(String(out, Charsets.UTF_8)).getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        assertTrue(e.optBoolean("_replayed"))
    }

    @Test
    fun `CURL 单条导出包含 -X method 与 url`() {
        val entry = makeEntry(7L, method = "POST", body = "{\"a\":1}".toByteArray())
        val out = CaptureExporter.export(listOf(entry), CaptureExporter.Format.CURL)
        val s = String(out, Charsets.UTF_8)
        assertTrue(s.startsWith("curl -X POST"))
        assertTrue(s.contains("'https://h.example.com/v1/x'"))
        assertTrue(s.contains("--data-raw"))
    }

    @Test
    fun `CURL 包含 host 与 content-type headers 且自动忽略 content-length`() {
        val entry = makeEntry(8L).copy(
            requestHeaders = mapOf(
                "host" to "h.example.com",
                "content-type" to "application/json",
                "content-length" to "0",
                "x-test" to "test"
            )
        )
        val s = String(CaptureExporter.export(listOf(entry), CaptureExporter.Format.CURL), Charsets.UTF_8)
        assertTrue(s.contains("-H 'content-type: application/json'"))
        assertTrue(s.contains("-H 'x-test: test'"))
        // content-length 应被显式排除
        assertTrue(!s.contains("content-length"))
    }

    @Test
    fun `CURL 多条导出 以两条 curl 展现 各自 _可分_`() {
        val entries = listOf(makeEntry(1L), makeEntry(2L))
        val s = String(CaptureExporter.export(entries, CaptureExporter.Format.CURL), Charsets.UTF_8)
        // 两条都被导出, 即字符串中包含两个 "curl -X GET"
        assertEquals(2, s.split("curl -X GET", ignoreCase = false).size - 1)
    }

    @Test
    fun `PLAIN_SUMMARY zh 包含 正文标签 与 host 行`() {
        val entry = makeEntry(1L, replayed = true)
        val s = String(CaptureExporter.export(listOf(entry), CaptureExporter.Format.PLAIN_SUMMARY, "zh"), Charsets.UTF_8)
        assertTrue(s.contains("抓包导出汇总"))
        assertTrue(s.contains("(重放)"))
        assertTrue(s.contains("h.example.com"))
    }

    @Test
    fun `PLAIN_SUMMARY en 显示英文且含 replay`() {
        val entry = makeEntry(1L, replayed = true)
        val s = String(CaptureExporter.export(listOf(entry), CaptureExporter.Format.PLAIN_SUMMARY, "en"), Charsets.UTF_8)
        assertTrue(s.contains("Capture Export Summary"))
        assertTrue(s.contains("(replay)"))
    }

    @Test
    fun `PLAIN_SUMMARY 含 TLS 行 当 entry 携带 tlsMeta 时`() {
        val entry = makeEntry(1L).copy(
            tlsMeta = TlsMeta(
                sni = "h.example.com",
                protocol = "TLSv1.3",
                cipherSuite = "TLS_AES_128_GCM_SHA256",
                alpn = "h2",
                peerCertificates = emptyList()
            )
        )
        val s = String(CaptureExporter.export(listOf(entry), CaptureExporter.Format.PLAIN_SUMMARY, "en"), Charsets.UTF_8)
        assertTrue(s.contains("TLSv1.3"))
        assertTrue(s.contains("TLS_AES_128_GCM_SHA256"))
        assertTrue(s.contains("alpn=h2"))
    }

    // ---------- 批次 D: HAR 增强 ----------

    @Test
    fun `HAR 含 query 参数拆分 与 timings 全字段 与 comment 注释`() {
        val entry = makeEntry(1L, path = "/v1/list?x=1&y=2&z")
        val bytes = CaptureExporter.export(listOf(entry), CaptureExporter.Format.HAR)
        val s = String(bytes, Charsets.UTF_8)
        // query 参数: 3 个 name/value pairs
        assertTrue(s.contains("\"name\": \"x\""))
        assertTrue(s.contains("\"name\": \"y\""))
        assertTrue(s.contains("\"name\": \"z\""))
        // timings 全字段
        assertTrue(s.contains("\"blocked\""))
        assertTrue(s.contains("\"dns\""))
        assertTrue(s.contains("\"connect\""))
        assertTrue(s.contains("\"send\""))
        assertTrue(s.contains("\"wait\""))
        assertTrue(s.contains("\"receive\""))
        // log 级 _comment 注释
        assertTrue(s.contains("\"_comment\""))
        assertTrue(s.contains("redact=default"))
        // pages 字段存在(empty array)
        assertTrue(s.contains("\"pages\""))
    }

    @Test
    fun `HAR 含 _replayed _intercepted _sessionId _tls 扩展下划线字段`() {
        val entry = makeEntry(1L, replayed = true).copy(
            intercepted = true,
            sessionId = "flow-XYZ",
            tlsMeta = TlsMeta(
                sni = "h.example.com",
                protocol = "TLSv1.3",
                cipherSuite = "TLS_AES_128_GCM_SHA256",
                alpn = "h2",
                peerCertificates = listOf(
                    CertMeta(
                        subject = "CN=h.example.com",
                        issuer = "CN=HanFeng Root",
                        notBefore = 1_700_000_000_000L,
                        notAfter = 1_730_000_000_000L,
                        sha256Fingerprint = "ABCDEFFGHIJ"
                    )
                )
            ),
            error = null
        )
        val s = String(CaptureExporter.export(listOf(entry), CaptureExporter.Format.HAR), Charsets.UTF_8)
        assertTrue(s.contains("\"_replayed\": true"))
        assertTrue(s.contains("\"_intercepted\": true"))
        assertTrue(s.contains("\"_sessionId\": \"flow-XYZ\""))
        assertTrue(s.contains("\"_tls\""))
        assertTrue(s.contains("\"CN=h.example.com\""))
        assertTrue(s.contains("\"sha256\": \"ABCDEFFGHIJ\""))
    }

    // ---------- 联动 redactMode ----------

    @Test
    fun `export redactMode=true 脱敏 Authorization Cookie Set-Cookie 与 X-Token`() {
        val entry = makeEntry(1L).copy(
            requestHeaders = mapOf(
                "Host" to "h.example.com",
                "Authorization" to "Bearer real-secret-token",
                "Cookie" to "session=REAL_SESSION; csrf=REAL_CSRF",
                "X-Token" to "invented"
            ),
            responseHeaders = mapOf(
                "Content-Type" to "application/json",
                "Set-Cookie" to "uuid=REAL_UUID; Path=/"
            )
        )
        val s = String(CaptureExporter.export(listOf(entry), CaptureExporter.Format.HAR, redactMode = true), Charsets.UTF_8)
        // 脱敏后授权与 cookie 字段值已替换为 ***
        assertTrue(s.contains("\"***\""))
        // 原值不得出现在导出中
        assertTrue(!s.contains("REAL_SESSION"))
        assertTrue(!s.contains("REAL_CSRF"))
        assertTrue(!s.contains("REAL_UUID"))
        assertTrue(!s.contains("real-secret-token"))
        // 非敏感 key 仍带原值
        assertTrue(s.contains("\"application/json\""))
        assertTrue(s.contains("\"h.example.com\""))
        // X-Token 已脱敏 → 不再含原值 "invented"
        assertTrue(!s.contains("\"invented\""))
    }

    @Test
    fun `export redactMode=false 保留原 Authorization 与 Cookie 值`() {
        val entry = makeEntry(1L).copy(
            requestHeaders = mapOf(
                "Host" to "h.example.com",
                "Authorization" to "Bearer real-secret-token"
            )
        )
        val s = String(CaptureExporter.export(listOf(entry), CaptureExporter.Format.HAR, redactMode = false), Charsets.UTF_8)
        assertTrue(s.contains("real-secret-token"))
        assertTrue(!s.contains("\"***\""))
    }
}
