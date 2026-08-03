package com.hanfeng.adblocker.capture

import com.HanFeng.capture.CaptureEntry
import com.HanFeng.capture.CaptureFilter
import com.HanFeng.capture.CaptureFilterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFilterTest {

    private fun entry(
        host: String = "h.example.com",
        method: String = "GET",
        status: Int = 200,
        sessionId: String = "",
        path: String = "/",
        requestHeaders: Map<String, String> = emptyMap(),
        requestBody: ByteArray? = null,
        responseBody: ByteArray? = null,
        intercepted: Boolean = false,
        durationMs: Long = 0L,
        appName: String? = null
    ): CaptureEntry = CaptureEntry(
        txnId = 1L, timestampMs = 0L, appName = appName, packageName = null,
        scheme = "https", method = method, host = host, path = path, httpVersion = "HTTP/1.1",
        requestHeaders = requestHeaders, requestBodyPreview = requestBody, requestBodyTruncated = false,
        responseStatus = status, responseHeaders = emptyMap(), responseBodyPreview = responseBody,
        responseBodyTruncated = false, durationMs = durationMs,
        intercepted = intercepted,
        sessionId = sessionId
    )

    @Test
    fun `空 filter isActive=false 且 matches 永远 true`() {
        val f = CaptureFilter()
        assertFalse(f.isActive)
        assertTrue(f.matches(entry()))
        assertTrue(f.matches(entry(host = "anything", method = "POST", status = 404)))
    }

    @Test
    fun `hostContains 大小写不敏感子串匹配`() {
        val f = CaptureFilter(hostContains = "API")
        assertTrue(f.matches(entry(host = "api.example.com")))
        assertTrue(f.matches(entry(host = "sub.api.example.com")))
        assertFalse(f.matches(entry(host = "static.example.com")))
    }

    @Test
    fun `methods 命中其中一个即放行`() {
        val f = CaptureFilter(methods = setOf("POST", "PUT"))
        assertTrue(f.matches(entry(method = "POST")))
        assertTrue(f.matches(entry(method = "put"))) // 大小写不敏感
        assertFalse(f.matches(entry(method = "GET")))
    }

    @Test
    fun `statusRanges 命中段内才放行; responseStatus==0 的条目不通过`() {
        val f = CaptureFilter(statusRanges = listOf(200..299, 400..499))
        assertTrue(f.matches(entry(status = 200)))
        // 503 不在 2xx 或 4xx 段内 → false
        assertFalse(f.matches(entry(status = 503)))
        assertFalse(f.matches(entry(status = 301)))
        assertFalse(f.matches(entry(status = 0)))
    }

    @Test
    fun `三个维度 AND 关系`() {
        val f = CaptureFilter(
            hostContains = "api",
            methods = setOf("POST"),
            statusRanges = listOf(200..299)
        )
        assertTrue(f.matches(entry(host = "api.example.com", method = "POST", status = 200)))
        assertFalse(f.matches(entry(host = "api.example.com", method = "GET", status = 200)))
        assertFalse(f.matches(entry(host = "api.example.com", method = "POST", status = 404)))
    }

    @Test
    fun `parser 解析 x 段 + 单码 + 区间`() {
        assertEquals(listOf(200..299), CaptureFilterParser.parseStatusRanges("2xx"))
        assertEquals(listOf(400..499, 500..599), CaptureFilterParser.parseStatusRanges("4xx,5xx"))
        assertEquals(listOf(404..404), CaptureFilterParser.parseStatusRanges("404"))
        assertEquals(listOf(304..308), CaptureFilterParser.parseStatusRanges("304..308"))
        assertEquals(listOf(200..299, 404..404), CaptureFilterParser.parseStatusRanges("2xx,404"))
    }

    @Test
    fun `parser 空或非法返回空`() {
        assertEquals(emptyList<IntRange>(), CaptureFilterParser.parseStatusRanges(""))
        assertEquals(emptyList<IntRange>(), CaptureFilterParser.parseStatusRanges(null))
        assertEquals(emptyList<IntRange>(), CaptureFilterParser.parseStatusRanges("xx,kk,foo"))
    }

    @Test
    fun `applyFilter 后 visibleItems 仅显示匹配条目`() {
        val adapter = object {
            val allItems = mutableListOf<CaptureEntry>()
            val visibleItems = mutableListOf<CaptureEntry>()

            fun prepend(e: CaptureEntry) { allItems.add(0, e) }
            fun applyFilter(filter: CaptureFilter) {
                visibleItems.clear()
                visibleItems.addAll(allItems.filter(filter::matches))
            }
        }
        adapter.prepend(entry(host = "api.example.com", method = "POST"))
        adapter.prepend(entry(host = "static.example.com", method = "GET"))
        adapter.prepend(entry(host = "api.example.com", method = "GET"))

        adapter.applyFilter(CaptureFilter(hostContains = "api"))
        assertEquals(2, adapter.visibleItems.size)
    }

    // ---------- 批次 C3: sessionId / keyword / interceptedOnly ----------

    @Test
    fun `sessionId 过滤只匹配同 session 条目`() {
        val f = CaptureFilter(sessionId = "flow-A")
        assertTrue(f.matches(entry(sessionId = "flow-A")))
        assertFalse(f.matches(entry(sessionId = "flow-B")))
        assertFalse(f.matches(entry(sessionId = "")))
    }

    @Test
    fun `keyword 命中 path 角色 host body 不区分大小写`() {
        val f = CaptureFilter(keyword = "TOKEN")
        assertTrue(f.matches(entry(path = "/v1/user_token")))
        assertTrue(f.matches(entry(host = "auth.example.com", path = "/login")))
        assertTrue(f.matches(entry(requestHeaders = mapOf("Authorization" to "Bearer TOKEN_xyz"))))
        assertTrue(f.matches(entry(requestBody = "{token:1}".toByteArray())))
        assertFalse(f.matches(entry(path = "/v1/list")))
    }

    @Test
    fun `interceptedOnly 仅断点拦截条目通过`() {
        val f = CaptureFilter(interceptedOnly = true)
        assertTrue(f.matches(entry(intercepted = true)))
        assertFalse(f.matches(entry(intercepted = false)))
    }

    @Test
    fun `所有新维度 AND 关系`() {
        val f = CaptureFilter(
            hostContains = "api",
            sessionId = "flow-A",
            keyword = "abc",
            interceptedOnly = true
        )
        assertTrue(f.matches(entry(host = "api.x.com", sessionId = "flow-A", path = "/v1/abc", intercepted = true)))
        assertFalse(f.matches(entry(host = "api.x.com", sessionId = "flow-A", path = "/v1/abc", intercepted = false)))
        assertFalse(f.matches(entry(host = "static.x.com", sessionId = "flow-A", path = "/v1/abc", intercepted = true)))
        assertFalse(f.matches(entry(host = "api.x.com", sessionId = "flow-B", path = "/v1/abc", intercepted = true)))
        assertFalse(f.matches(entry(host = "api.x.com", sessionId = "flow-A", path = "/v1/zzz", intercepted = true)))
    }

    // ---------- 批次 D: durationMsRange / appName ----------

    @Test
    fun `duration range 闭区间过滤`() {
        val f = CaptureFilter(durationMsRange = 100L..500L)
        assertTrue(f.matches(entry()))
        assertFalse(f.matches(entry(durationMs = 999L)))
        assertFalse(f.matches(entry(durationMs = 99L)))
        assertTrue(f.matches(entry(durationMs = 100L)))
        assertTrue(f.matches(entry(durationMs = 500L)))
    }

    @Test
    fun `appName 精确过滤`() {
        val f = CaptureFilter(appName = "TestApp")
        assertTrue(f.matches(entry(appName = "TestApp")))
        assertFalse(f.matches(entry(appName = "Other")))
        assertFalse(f.matches(entry(appName = null)))
    }
}
