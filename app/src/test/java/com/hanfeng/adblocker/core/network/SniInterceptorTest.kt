package com.HanFeng.core.network

import com.HanFeng.data.RuleRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Ignore("待引入 mockito-inline 并验证对 Kotlin object 实例方法的拦截能力后启用")
class SniInterceptorTest {

    private lateinit var mockedRuleRepo: MockedStatic<RuleRepository>

    @Before
    fun setUp() {
        ScoredBlockCache.clear()
        clearSniCache()
        mockedRuleRepo = Mockito.mockStatic(RuleRepository::class.java)
        // 默认所有 RuleRepository 静态判断均返回 false / null，避免命中早返回路径
        whenever(RuleRepository.isSocialCoreDomain(any())).thenReturn(false)
        whenever(RuleRepository.isWhitelistedDomain(any())).thenReturn(false)
        whenever(RuleRepository.isSensitiveAuthDomain(any())).thenReturn(false)
        whenever(RuleRepository.findMatchingRule(any(), any(), any())).thenReturn(null)
        whenever(RuleRepository.isUserOwnedRule(any())).thenReturn(false)
        whenever(RuleRepository.shouldTreatAsGeneralAdTraffic(any(), any(), any())).thenReturn(false)
        whenever(RuleRepository.looksLikeAdSdkInfraDomain(any(), any())).thenReturn(false)
        whenever(RuleRepository.shouldForceNovelQuicBlock(any(), any(), any())).thenReturn(false)
        whenever(RuleRepository.classifyVendor(any(), any())).thenReturn(null)
        whenever(RuleRepository.classifyVendorSimple(any(), any())).thenReturn(null)
    }

    @After
    fun tearDown() {
        mockedRuleRepo.close()
        ScoredBlockCache.clear()
        clearSniCache()
    }

    @Suppress("UNCHECKED_CAST")
    private fun clearSniCache() {
        runCatching {
            // SniInterceptor 是 object，sniCache 是私有 LinkedHashMap；通过反射清空避免跨用例串扰
            val field = SniInterceptor::class.java.getDeclaredField("sniCache")
            field.isAccessible = true
            (field.get(SniInterceptor) as MutableMap<*, *>).clear()
        }
    }

    private fun evaluate(sniHost: String, isProtectedDomain: Boolean = false): SniInterceptor.SniBlockDecision {
        // Context 在 mockStatic 路径下不会被实际调用方法
        val context = mock<android.content.Context>()
        return SniInterceptor.evaluate(context = context, sniHost = sniHost, appName = "any-app", isProtectedDomain = isProtectedDomain)
    }

    @Test
    fun `empty sni is never blocked`() {
        val d = evaluate("")
        assertFalse(d.shouldBlock)
        assertEquals("empty-sni", d.reason)
    }

    @Test
    fun `protected domain is never blocked when no user owned rule`() {
        val d = evaluate("secure.example.com", isProtectedDomain = true)
        assertFalse(d.shouldBlock)
        assertEquals("protected-domain", d.reason)
    }

    @Test
    fun `social core domain is never blocked`() {
        whenever(RuleRepository.isSocialCoreDomain("weixin.qq.com")).thenReturn(true)
        val d = evaluate("weixin.qq.com")
        assertFalse(d.shouldBlock)
        assertEquals("social-core", d.reason)
    }

    @Test
    fun `whitelisted domain is never blocked`() {
        whenever(RuleRepository.isWhitelistedDomain("safe.example.com")).thenReturn(true)
        val d = evaluate("safe.example.com")
        assertFalse(d.shouldBlock)
        assertEquals("whitelisted", d.reason)
    }

    @Test
    fun `sensitive auth domain is never blocked`() {
        whenever(RuleRepository.isSensitiveAuthDomain("login.example.com")).thenReturn(true)
        val d = evaluate("login.example.com")
        assertFalse(d.shouldBlock)
        assertEquals("sensitive-auth", d.reason)
    }

    @Test
    fun `dga-looking domain is blocked via dga-heuristic`() {
        // 选一个能稳过 DGA 阈值的域名：前缀长度 ≥8 且熵高，且不在 publishHostSuffix 列表
        val dgaDomain = "zx1q2w3e4r5t6t.tracker.com"
        val d = evaluate(dgaDomain)
        assertTrue("DGA-looking domain should be blocked, got reason=${d.reason}", d.shouldBlock)
        assertEquals("dga-heuristic", d.reason)
    }

    @Test
    fun `normal looking domain is allowed when no rule hits`() {
        // static.example-cdn.com: 前缀长度 6（static）+ example-cdn + com 总体熵低，不应命中 DGA
        val d = evaluate("static.example-cdn.com")
        assertFalse("normal domain should pass, got reason=${d.reason}", d.shouldBlock)
        assertEquals("pass", d.reason)
    }

    @Test
    fun `learned domain block from ScoredBlockCache short-circuits to learning-feedback`() {
        ScoredBlockCache.recordCandidate(
            domain = "learned.example.com",
            ip = null,
            score = ScoredBlockCache.SCORE_THRESHOLD_DOMAIN,
            ttlMillis = 60_000L,
            vendor = "test-vendor",
            reason = "manual-preset"
        )
        val d = evaluate("learned.example.com")
        assertTrue(d.shouldBlock)
        assertEquals("learning-feedback", d.reason)
    }
}
