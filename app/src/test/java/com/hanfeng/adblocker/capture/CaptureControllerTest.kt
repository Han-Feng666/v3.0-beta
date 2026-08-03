package com.hanfeng.adblocker.capture

import android.content.Context
import com.HanFeng.capture.BreakpointAction
import com.HanFeng.capture.BreakpointKind
import com.HanFeng.capture.BreakpointMatchRule
import com.HanFeng.capture.BreakpointRepo
import com.HanFeng.capture.CaptureController
import com.HanFeng.capture.MitmGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class CaptureControllerTest {

    private lateinit var fakeGate: MitmGate
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        // 重置单例状态
        BreakpointRepo.clearAll()
        CaptureController.disable()

        fakeGate = object : MitmGate {
            var certReady = true
            var certInstalled = true
            var httpsEnabled = false
            var setHttpsEnabledCalls = 0
            override fun isCertificateReady(context: Context): Boolean = certReady
            override fun isCertificateInstalled(context: Context): Boolean = certInstalled
            override fun isHttpDecryptEnabled(context: Context): Boolean = httpsEnabled
            override fun setHttpDecryptEnabled(context: Context, enabled: Boolean) {
                httpsEnabled = enabled
                setHttpsEnabledCalls++
            }
        }
        CaptureController.mitmGate = fakeGate
        ctx = Mockito.mock(Context::class.java)
    }

    @After
    fun tearDown() {
        CaptureController.disable()
        BreakpointRepo.clearAll()
    }

    @Test
    fun `初始状态 inactive 且 mode 默认 BY_APP`() {
        val s = CaptureController.current.value
        assertFalse(s.active)
        assertEquals(CaptureController.Mode.BY_APP, s.mode)
        assertEquals(0, CaptureController.snapshot().size)
    }

    @Test
    fun `enable 当证书未就绪返回 PENDING_CERT 且不切 active`() {
        fakeGate.certReady = false
        val r = CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        assertEquals(CaptureController.EnableResult.PENDING_CERT, r)
        assertFalse(CaptureController.current.value.active)
    }

    @Test
    fun `enable 当证书未安装返回 PENDING_CERT`() {
        fakeGate.certReady = true
        fakeGate.certInstalled = false
        val r = CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        assertEquals(CaptureController.EnableResult.PENDING_CERT, r)
        assertFalse(CaptureController.current.value.active)
    }

    @Test
    fun `enable 成功时强制打开 HTTPS MITM 共用同 CA`() {
        fakeGate.httpsEnabled = false
        val r = CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        assertEquals(CaptureController.EnableResult.OK, r)
        assertTrue(CaptureController.current.value.active)
        assertEquals(1, fakeGate.setHttpsEnabledCalls)
        assertTrue(fakeGate.httpsEnabled)
    }

    @Test
    fun `enable 成功时若 HTTPS 已开不再重复 setHttpDecryptEnabled`() {
        fakeGate.httpsEnabled = true
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        assertEquals(0, fakeGate.setHttpsEnabledCalls)
    }

    @Test
    fun `disable 清空 ring buffer 与断点规则且不动 HTTPS 全局开关`() {
        fakeGate.httpsEnabled = true
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        BreakpointRepo.addRule(
            BreakpointMatchRule("host.example.com", null, null, BreakpointKind.REQUEST)
        )
        assertTrue(BreakpointRepo.hasRules())

        CaptureController.disable()

        assertFalse(CaptureController.current.value.active)
        assertEquals(0, CaptureController.snapshot().size)
        // 断点规则被清空(design correctness 4)
        assertFalse(BreakpointRepo.hasRules())
        // HTTPS 全局开关保持开(requirements R3.4)
        assertTrue(fakeGate.httpsEnabled)
        assertEquals(1, fakeGate.setHttpsEnabledCalls) // 仍是 enable 时的一次
    }

    @Test
    fun `inactive 时 onDecodedRequest 静默返回 Inactive 不采样`() {
        val outcome = CaptureController.onDecodedRequest(
            method = "GET",
            host = "h.example.com",
            path = "/",
            scheme = "https",
            httpVersion = "HTTP/1.1",
            requestHeaders = emptyMap(),
            requestBodyPreview = null,
            requestBodyTruncated = false,
            appName = "Test",
            packageName = "com.test"
        )
        assertTrue(outcome is com.HanFeng.capture.RequestOutcome.Inactive)
        assertEquals(0, CaptureController.snapshot().size)
    }

    @Test
    fun `BY_APP 模式下不在 targetApps 中的包不采样`() {
        CaptureController.enable(ctx, CaptureController.Mode.BY_APP, setOf("com.allowed"))
        val outcome = CaptureController.onDecodedRequest(
            method = "GET",
            host = "h.example.com",
            path = "/",
            scheme = "https",
            httpVersion = "HTTP/1.1",
            requestHeaders = emptyMap(),
            requestBodyPreview = null,
            requestBodyTruncated = false,
            appName = "Other",
            packageName = "com.other"
        )
        assertTrue(outcome is com.HanFeng.capture.RequestOutcome.Inactive)
        assertEquals(0, CaptureController.snapshot().size)
    }

    @Test
    fun `BY_APP 模式下 targetApps 命中时采样并返回 Pending txnId`() {
        CaptureController.enable(ctx, CaptureController.Mode.BY_APP, setOf("com.allowed"))
        val outcome = CaptureController.onDecodedRequest(
            method = "GET",
            host = "h.example.com",
            path = "/",
            scheme = "https",
            httpVersion = "HTTP/1.1",
            requestHeaders = emptyMap(),
            requestBodyPreview = null,
            requestBodyTruncated = false,
            appName = "Allowed",
            packageName = "com.allowed"
        )
        assertTrue(outcome is com.HanFeng.capture.RequestOutcome.Pending)
        val txnId = (outcome as com.HanFeng.capture.RequestOutcome.Pending).txnId
        assertNotNull(txnId)
        assertEquals(1, CaptureController.snapshot().size)
        val entry = CaptureController.snapshot().first()
        assertEquals("h.example.com", entry.host)
        assertEquals("com.allowed", entry.packageName)
        assertFalse(entry.isComplete)
    }

    @Test
    fun `ALL_APPS 模式下任何包都被采样`() {
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        val outcome = CaptureController.onDecodedRequest(
            method = "POST",
            host = "api.example.com",
            path = "/v1/login",
            scheme = "https",
            httpVersion = "HTTP/2",
            requestHeaders = mapOf("Authorization" to "Bearer x"),
            requestBodyPreview = "{\"u\":\"a\"}".toByteArray(),
            requestBodyTruncated = false,
            appName = null,
            packageName = null
        )
        assertTrue(outcome is com.HanFeng.capture.RequestOutcome.Pending)
        val txnId = (outcome as com.HanFeng.capture.RequestOutcome.Pending).txnId
        assertNotNull(txnId)
        val entry = CaptureController.snapshot().first()
        assertEquals("POST", entry.method)
        assertEquals("api.example.com", entry.host)
        assertEquals("/v1/login", entry.path)
    }

    @Test
    fun `onDecodedResponse 在已知 txnId 上补全字段并标记 isComplete`() {
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        val reqOutcome = CaptureController.onDecodedRequest(
            method = "GET",
            host = "h.example.com",
            path = "/",
            scheme = "https",
            httpVersion = "HTTP/1.1",
            requestHeaders = emptyMap(),
            requestBodyPreview = null,
            requestBodyTruncated = false,
            appName = null,
            packageName = null
        )
        val txnId = (reqOutcome as com.HanFeng.capture.RequestOutcome.Pending).txnId
        CaptureController.onDecodedResponse(
            txnId = txnId,
            responseStatus = 200,
            responseHeaders = mapOf("Content-Type" to "application/json"),
            responseBodyPreview = "{\"ok\":true}".toByteArray(),
            responseBodyTruncated = false,
            durationMs = 120L,
            intercepted = false
        )
        val entry = CaptureController.get(txnId)
        assertNotNull(entry)
        assertEquals(200, entry!!.responseStatus)
        assertEquals(120L, entry.durationMs)
        assertTrue(entry.isComplete)
    }

    @Test
    fun `onDecodedResponse 对未知 txnId 静默无副作用`() {
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        // 不写任何请求, 直接喂响应
        val outcome = CaptureController.onDecodedResponse(
            txnId = 99999L,
            responseStatus = 200,
            responseHeaders = emptyMap(),
            responseBodyPreview = null,
            responseBodyTruncated = false,
            durationMs = 0L,
            intercepted = false
        )
        assertTrue(outcome is com.HanFeng.capture.ResponseOutcome.NotFound || outcome is com.HanFeng.capture.ResponseOutcome.Inactive)
        assertEquals(0, CaptureController.snapshot().size)
        assertNull(CaptureController.get(99999L))
    }

    @Test
    fun `onTrimMemoryLow 减半 ring buffer 容量`() {
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet(), maxEntries = 200)
        repeat(200) { i ->
            CaptureController.onDecodedRequest(
                method = "GET",
                host = "h$i.example.com",
                path = "/",
                scheme = "https",
                httpVersion = "HTTP/1.1",
                requestHeaders = emptyMap(),
                requestBodyPreview = null,
                requestBodyTruncated = false,
                appName = null,
                packageName = null
            )
        }
        assertEquals(200, CaptureController.snapshot().size)

        CaptureController.onTrimMemoryLow()

        // 容量减半后条目<=100
        assertTrue(CaptureController.snapshot().size <= 100)
    }

    @Test
    fun `body 超过 mode 预览上限时被截断并标记 truncated=true`() {
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        // ALL_APPS 默认 8KB
        val big = ByteArray(20 * 1024) { 65 }
        val outcome = CaptureController.onDecodedRequest(
            method = "POST",
            host = "h.example.com",
            path = "/upload",
            scheme = "https",
            httpVersion = "HTTP/1.1",
            requestHeaders = emptyMap(),
            requestBodyPreview = big,
            requestBodyTruncated = false,
            appName = null,
            packageName = null
        )
        val txnId = (outcome as com.HanFeng.capture.RequestOutcome.Pending).txnId
        val entry = CaptureController.get(txnId)!!
        assertTrue(entry.requestBodyTruncated)
        assertEquals(8 * 1024, entry.requestBodyPreview!!.size)
    }

    @Test
    fun `命中请求断点时 emit breakpointHits 且可被 resume 解锁`() {
        CaptureController.enable(ctx, CaptureController.Mode.ALL_APPS, emptySet())
        BreakpointRepo.addRule(
            BreakpointMatchRule("h.example.com", null, null, BreakpointKind.REQUEST)
        )
        // onDecodedRequest 会因命中断点阻塞 awaitResumeBlocking; 启线程跑
        lateinit var outcome: com.HanFeng.capture.RequestOutcome
        val t = Thread {
            outcome = CaptureController.onDecodedRequest(
                method = "GET",
                host = "h.example.com",
                path = "/",
                scheme = "https",
                httpVersion = "HTTP/1.1",
                requestHeaders = emptyMap(),
                requestBodyPreview = null,
                requestBodyTruncated = false,
                appName = null,
                packageName = null
            )
        }
        t.start()
        Thread.sleep(200)
        val captured = CaptureController.snapshot().firstOrNull()
        assertNotNull(captured)
        val txnId = captured!!.txnId
        // resume 放行
        val ok = CaptureController.resumeFromBreakpoint(txnId, BreakpointAction.PassThrough(true))
        assertTrue(ok)
        t.join(2000)
        assertFalse(t.isAlive)
        assertTrue(outcome is com.HanFeng.capture.RequestOutcome.Pending)
        val pending = outcome as com.HanFeng.capture.RequestOutcome.Pending
        assertEquals(txnId, pending.txnId)
        assertTrue(pending.action is BreakpointAction.PassThrough)
    }

    @Test
    fun `resumeFromBreakpoint 对未知 txnId 返回 false`() {
        assertFalse(CaptureController.resumeFromBreakpoint(88888L, BreakpointAction.PassThrough(true)))
    }
}
