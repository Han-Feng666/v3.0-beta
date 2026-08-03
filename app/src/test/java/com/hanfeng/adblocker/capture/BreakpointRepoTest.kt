package com.hanfeng.adblocker.capture

import com.HanFeng.capture.BreakpointAction
import com.HanFeng.capture.BreakpointKind
import com.HanFeng.capture.BreakpointMatchRule
import com.HanFeng.capture.BreakpointRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BreakpointRepoTest {

    @Before
    fun setUp() {
        BreakpointRepo.clearAll()
    }

    @After
    fun tearDown() {
        BreakpointRepo.clearAll()
    }

    @Test
    fun `addRule 后 hasRules 为 true, clearAll 后为 false`() {
        assertFalse(BreakpointRepo.hasRules())
        BreakpointRepo.addRule(BreakpointMatchRule("h.example.com", null, null, BreakpointKind.REQUEST))
        assertTrue(BreakpointRepo.hasRules())
        BreakpointRepo.clearAll()
        assertFalse(BreakpointRepo.hasRules())
    }

    @Test
    fun `matchRequest 仅命中 REQUEST 类规则, 栏同 host 响应规则不命中请求`() {
        BreakpointRepo.addRule(BreakpointMatchRule("h.example.com", "GET", null, BreakpointKind.REQUEST))
        BreakpointRepo.addRule(BreakpointMatchRule("h.example.com", null, null, BreakpointKind.RESPONSE))

        val reqRule = BreakpointRepo.matchRequest("h.example.com", "GET", "/")
        assertNotNull(reqRule)
        assertEquals(BreakpointKind.REQUEST, reqRule!!.kind)

        // method 不匹配 GET 应跳过(规则中要求 GET)
        assertNull(BreakpointRepo.matchRequest("h.example.com", "POST", "/"))
        // host 不匹配跳过
        assertNull(BreakpointRepo.matchRequest("other.example.com", "GET", "/"))
    }

    @Test
    fun `matchResponse 仅命中 RESPONSE 类规则, 大小写不敏感`() {
        BreakpointRepo.addRule(
            BreakpointMatchRule("H.Example.com", null, "/api", BreakpointKind.RESPONSE)
        )
        val rule = BreakpointRepo.matchResponse("h.example.com", "GET", "/api/v1")
        assertNotNull(rule)
        // 仅前缀匹配 path
        assertNull(BreakpointRepo.matchResponse("h.example.com", "GET", "/other"))
    }

    @Test
    fun `resolve 对挂起的 txnId 唤醒 awaitResumeBlocking 并返回动作`() {
        // 通过线程 await + resolve 解锁
        val txnId = 999_000L
        var result: BreakpointAction? = null
        val t = Thread {
            result = BreakpointRepo.awaitResumeBlocking(txnId)
        }
        t.start()
        Thread.sleep(200)
        val ok = BreakpointRepo.resolve(txnId, BreakpointAction.PassThrough(true))
        assertTrue(ok)
        t.join(2000)
        assertFalse(t.isAlive)
        assertEquals(BreakpointAction.PassThrough(true), result)
    }

    @Test
    fun `resolve 双重 resolve 返回 false (第二次找不到挂起项)`() {
        val txnId = 888_111L
        var b: BreakpointAction? = null
        val t = Thread { b = BreakpointRepo.awaitResumeBlocking(txnId) }
        t.start()
        Thread.sleep(200)
        assertTrue(BreakpointRepo.resolve(txnId, BreakpointAction.Drop))
        assertFalse(BreakpointRepo.resolve(txnId, BreakpointAction.PassThrough(true)))
        t.join(2000)
        assertEquals(BreakpointAction.Drop, b)
    }

    @Test
    fun `resolve 对未知 txnId 返回 false 不阻塞`() {
        assertFalse(BreakpointRepo.resolve(404_404L, BreakpointAction.PassThrough(true)))
    }

    @Test
    fun `clearAll 解锁挂起项以 PassThrough(useOriginal=true)`() {
        val txnId = 33_44L
        var result: BreakpointAction? = null
        var done = false
        val t = Thread {
            result = BreakpointRepo.awaitResumeBlocking(txnId)
            done = true
        }
        t.start()
        Thread.sleep(200)
        BreakpointRepo.clearAll()
        t.join(2000)
        assertTrue(done)
        assertEquals(BreakpointAction.PassThrough(true), result)
    }

    @Test
    fun `awaitResume 在协程挂起语义下也正确返回 resolve 结果`() {
        val txnId = 70001L
        runBlocking {
            // 线程 resolver, 主线 await
            val t = Thread {
                Thread.sleep(200)
                BreakpointRepo.resolve(txnId, BreakpointAction.ReplaceWith("{}".toByteArray()))
            }
            t.start()
            val action = BreakpointRepo.awaitResume(txnId)
            assertTrue(action is BreakpointAction.ReplaceWith)
            assertEquals("{}", String((action as BreakpointAction.ReplaceWith).replacement))
            t.join(2000)
        }
    }
}
