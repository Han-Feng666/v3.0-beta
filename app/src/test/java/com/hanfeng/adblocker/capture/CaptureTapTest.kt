package com.hanfeng.adblocker.capture

import android.content.Context
import com.HanFeng.capture.CaptureController
import com.HanFeng.capture.CaptureTap
import com.HanFeng.capture.TlsMeta
import com.HanFeng.service.RequestInspection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class CaptureTapTest {

    private lateinit var ctx: Context
    private val session by lazy {
        com.HanFeng.service.TlsMitmSessionManager.TlsMitmSession(
            flowKey = "test-flow",
            host = "h.example.com",
            appName = "TestApp",
            source = "uid-100",
            targetIp = "1.2.3.4",
            targetPort = 443,
            certificatePath = "/tmp/x",
            observedAt = 0L,
            state = "ready"
        )
    }

    @Before
    fun setUp() {
        CaptureController.disable()
        resetMitmGate()
        ctx = Mockito.mock(Context::class.java)
    }

    @After
    fun tearDown() {
        CaptureController.disable()
    }

    private fun resetMitmGate() {
        CaptureController.mitmGate = object : com.HanFeng.capture.MitmGate {
            override fun isCertificateReady(c: Context) = true
            override fun isCertificateInstalled(c: Context) = true
            override fun isHttpDecryptEnabled(c: Context) = true
            override fun setHttpDecryptEnabled(c: Context, e: Boolean) {}
        }
    }

    @Test
    fun `inactive 时 tapHttp1Request 不采样且静默返回 Inactive`() {
        // 此时 CaptureController 处于 disable 状态
        val inspection = RequestInspection(
            method = "GET", path = "/", host = "h.example.com",
            httpVersion = "HTTP/1.1", referer = null, origin = null, requestHeaders = emptyMap()
        )
        val outcome = CaptureTap.tapHttp1Request(ctx, session, inspection, ByteArray(0))
        assertTrue(outcome is com.HanFeng.capture.TapRequestOutcome.Inactive)
        assertEquals(0, CaptureController.snapshot().size)
    }

    @Test
    fun `active ALL_APPS 下 tapHttp1Request 写入 entry 并返回 Pending txnId`() {
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        val payload = (
            "GET /v1/hello HTTP/1.1\r\n" +
                "Host: h.example.com\r\n" +
                "Content-Length: 5\r\n" +
                "\r\n" +
                "hello"
            ).toByteArray()
        val inspection = RequestInspection(
            method = "GET", path = "/v1/hello", host = "h.example.com",
            httpVersion = "HTTP/1.1", referer = null, origin = null,
            requestHeaders = mapOf("host" to "h.example.com", "content-length" to "5")
        )
        val outcome = CaptureTap.tapHttp1Request(ctx, session, inspection, payload)
        assertTrue(outcome is com.HanFeng.capture.TapRequestOutcome.Pending)
        val txnId = (outcome as com.HanFeng.capture.TapRequestOutcome.Pending).txnId
        val entry = CaptureController.snapshot().first()
        assertEquals("GET", entry.method)
        assertEquals("h.example.com", entry.host)
        assertEquals("TestApp", entry.appName)
        assertEquals(5, entry.requestBodyPreview?.size)
        assertEquals(txnId, entry.txnId)
        // 无规则命中时 action 兜底为 PassThrough
        assertTrue((outcome).action is com.HanFeng.capture.BreakpointAction.PassThrough)
    }

    private fun inactiveByAppMatchesAppOnlyFlow() {
        // 占位(保留以备后续 BY_APP appName 匹配测试)
    }

    @Test
    fun `tapTlsHandshake 缓存 TLS 元数据, 后续 tapHttp1Request 合并到 entry`() {
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        val certs = listOf(
            com.HanFeng.capture.CertMeta(
                subject = "CN=h.example.com",
                issuer = "CN=Test CA",
                notBefore = 0L,
                notAfter = Long.MAX_VALUE,
                sha256Fingerprint = "AB"
            )
        )
        CaptureTap.tapTlsHandshake(
            ctx, session,
            protocol = "TLSv1.3",
            cipherSuite = "TLS_AES_128_GCM_SHA256",
            alpn = "h2",
            peerCertificates = certs
        )
        // 缓存可见
        val meta = CaptureTap.sessionTlsMeta(session.flowKey)
        assertNotNull(meta)
        assertEquals("TLSv1.3", meta!!.protocol)
        assertEquals("h2", meta.alpn)

        // 写入 entry 时元数据被并入
        val payload = "GET / HTTP/1.1\r\nHost: h.example.com\r\n\r\n".toByteArray()
        val inspection = RequestInspection(
            method = "GET", path = "/", host = "h.example.com",
            httpVersion = "HTTP/1.1", referer = null, origin = null, requestHeaders = emptyMap()
        )
        CaptureTap.tapHttp1Request(ctx, session, inspection, payload)
        val entry = CaptureController.snapshot().first()
        assertNotNull(entry.tlsMeta)
        assertEquals("TLSv1.3", entry.tlsMeta!!.protocol)
        assertEquals(1, entry.tlsMeta!!.peerCertificates.size)
    }

    @Test
    fun `tapHttp1Response 在已知 txnId 上补全响应`() {
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        val reqPayload = "GET /v1/x HTTP/1.1\r\nHost: h.example.com\r\n\r\n".toByteArray()
        val inspection = RequestInspection(
            method = "GET", path = "/v1/x", host = "h.example.com",
            httpVersion = "HTTP/1.1", referer = null, origin = null, requestHeaders = emptyMap()
        )
        val reqOutcome = CaptureTap.tapHttp1Request(ctx, session, inspection, reqPayload)
        assertTrue(reqOutcome is com.HanFeng.capture.TapRequestOutcome.Pending)
        val txnId = (reqOutcome as com.HanFeng.capture.TapRequestOutcome.Pending).txnId
        val respBytes = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "{\"k\":1}"
            ).toByteArray()
        CaptureTap.tapHttp1Response(ctx, session, txnId, inspection, respBytes)
        val entry = CaptureController.get(txnId)!!
        assertEquals(200, entry.responseStatus)
        assertEquals("application/json", entry.responseHeaders["content-type"])
        assertTrue(entry.isComplete)
        assertEquals("{\"k\":1}".toByteArray().size, entry.responseBodyPreview?.size)
    }

    @Test
    fun `input 所有字段为默认值时 不抛异常`() {
        // 这是 defensive-design 验证: tap 在异常输入下仍静默不传播 throwable
        var threw = false
        try {
            CaptureTap.tapHttp1Request(null, session, com.HanFeng.service.RequestInspection(
                method = "GET", path = "/", host = "h.example.com",
                httpVersion = "HTTP/1.1", referer = null, origin = null, requestHeaders = emptyMap()
            ), ByteArray(0))
        } catch (_: Throwable) {
            threw = true
        }
        assertFalse(threw)

        try {
            CaptureTap.tapHttp1Response(null, session, null, null, ByteArray(0))
        } catch (_: Throwable) {
            threw = true
        }
        assertFalse(threw)
    }
}
