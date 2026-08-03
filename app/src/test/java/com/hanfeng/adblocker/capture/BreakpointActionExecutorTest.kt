package com.hanfeng.adblocker.capture

import com.HanFeng.capture.BreakpointAction
import com.HanFeng.capture.BreakpointActionExecutor
import com.HanFeng.capture.CaptureDraftRequest
import com.HanFeng.capture.CaptureDraftResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakpointActionExecutorTest {

    private fun newRequestDraft(
        method: String = "GET",
        host: String = "h.example.com",
        path: String = "/v1",
        headers: Map<String, String> = mapOf("Accept" to "application/json"),
        body: ByteArray? = "{\"a\":1}".toByteArray()
    ) = CaptureDraftRequest(method, host, path, headers, body)

    private fun newResponseDraft(
        statusLine: String = "HTTP/1.1 200 OK",
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
        body: ByteArray = "{\"ok\":true}".toByteArray()
    ) = CaptureDraftResponse(statusLine, headers, body)

    @Test
    fun `请求方向 PassThrough(useOriginal=true) 透传原字节`() {
        val chunk = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        val out = BreakpointActionExecutor.applyToRequest(
            BreakpointAction.PassThrough(true), chunk, null
        )
        assertTrue(out is BreakpointActionExecutor.RequestActionOutcome.Passthrough)
        assertEquals(chunk, (out as BreakpointActionExecutor.RequestActionOutcome.Passthrough).bytes)
    }

    @Test
    fun `请求方向 ReplaceWith 替换字节包含重算的 Content-Length`() {
        val action = BreakpointAction.ReplaceWith(
            replacement = "{\"new\":true}".toByteArray(),
            headersOverride = mapOf("X-Test" to "yes")
        )
        val out = BreakpointActionExecutor.applyToRequest(
            action, "GET / HTTP/1.1\r\n\r\n".toByteArray(), newRequestDraft()
        )
        assertTrue(out is BreakpointActionExecutor.RequestActionOutcome.Replace)
        val bytes = String((out as BreakpointActionExecutor.RequestActionOutcome.Replace).bytes, Charsets.ISO_8859_1)
        // 必须包含 Content-Length + Host + Connection + headersOverride
        assertTrue(bytes.contains("Content-Length: 12"))
        assertTrue(bytes.contains("Host: h.example.com"))
        assertTrue(bytes.contains("X-Test: yes"))
        assertTrue(bytes.contains("POST /v1 HTTP/1.1"))
    }

    @Test
    fun `请求方向 Drop 返回 Drop`() {
        val out = BreakpointActionExecutor.applyToRequest(
            BreakpointAction.Drop, ByteArray(0), null
        )
        assertTrue(out is BreakpointActionExecutor.RequestActionOutcome.Drop)
    }

    @Test
    fun `响应方向 PassThrough 返回 PassthroughNative (HTBM 走原始路径)`() {
        val out = BreakpointActionExecutor.applyToResponse(
            BreakpointAction.PassThrough(true), null
        )
        assertTrue(out is BreakpointActionExecutor.ResponseActionOutcome.PassthroughNative)
    }

    @Test
    fun `响应方向 ReplaceWith 重算 Content-Length 并删除 Transfer-Encoding chunked`() {
        val draft = newResponseDraft(
            headers = mapOf(
                "Content-Type" to "application/json",
                "Transfer-Encoding" to "chunked"
            )
        )
        val newBody = "{\"different\":1}".toByteArray()
        val action = BreakpointAction.ReplaceWith(
            replacement = newBody,
            headersOverride = null,
            statusLineOverride = null
        )
        val out = BreakpointActionExecutor.applyToResponse(action, draft)
        assertTrue(out is BreakpointActionExecutor.ResponseActionOutcome.Replace)
        val bytes = String((out as BreakpointActionExecutor.ResponseActionOutcome.Replace).bytes, Charsets.ISO_8859_1)
        assertTrue(bytes.contains("HTTP/1.1 200 OK"))
        assertTrue(bytes.contains("Content-Length: " + newBody.size))
        assertFalseByText(bytes, "Transfer-Encoding")
        assertTrue(bytes.contains("Content-Type: application/json"))
    }

    @Test
    fun `响应方向 Drop 给客户端空响应`() {
        val out = BreakpointActionExecutor.applyToResponse(BreakpointAction.Drop, null)
        assertTrue(out is BreakpointActionExecutor.ResponseActionOutcome.Replace)
        val bytes = String((out as BreakpointActionExecutor.ResponseActionOutcome.Replace).bytes, Charsets.ISO_8859_1)
        assertTrue(bytes.startsWith("HTTP/1.1 200 OK"))
        assertTrue(bytes.contains("Content-Length: 0"))
    }

    @Test
    fun `响应方向 statusLineOverride 生效`() {
        val draft = newResponseDraft()
        val action = BreakpointAction.ReplaceWith(
            replacement = "{\"x\":1}".toByteArray(),
            statusLineOverride = "HTTP/1.1 418 I'm a teapot"
        )
        val out = BreakpointActionExecutor.applyToResponse(action, draft)
        val bytes = String((out as BreakpointActionExecutor.ResponseActionOutcome.Replace).bytes, Charsets.ISO_8859_1)
        assertTrue(bytes.startsWith("HTTP/1.1 418 I'm a teapot\r\n"))
    }

    @Test
    fun `响应方向 headersOverride 覆盖 draft headers`() {
        val draft = newResponseDraft(headers = mapOf("X-A" to "A", "X-B" to "B"))
        val action = BreakpointAction.ReplaceWith(
            replacement = "{}".toByteArray(),
            headersOverride = mapOf("X-B" to "OVERRIDDEN")
        )
        val out = BreakpointActionExecutor.applyToResponse(action, draft)
        val bytes = String((out as BreakpointActionExecutor.ResponseActionOutcome.Replace).bytes, Charsets.ISO_8859_1)
        assertTrue(bytes.contains("X-A: A"))
        assertTrue(bytes.contains("X-B: OVERRIDDEN"))
        assertFalseByText(bytes, "X-B: B\r")
    }

    private fun assertFalseByText(s: String, needle: String) {
        if (s.contains(needle)) {
            throw AssertionError("Expected \"$needle\" not to be in: $s")
        }
    }
}
