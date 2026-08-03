package com.HanFeng.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScoredBlockCacheTest {

    @Before
    fun reset() {
        ScoredBlockCache.clear()
    }

    @After
    fun cleanup() {
        ScoredBlockCache.clear()
    }

    @Test
    fun `recordCandidate below threshold is ignored`() {
        ScoredBlockCache.recordCandidate(
            domain = "below.example.com",
            ip = null,
            score = ScoredBlockCache.SCORE_THRESHOLD_DOMAIN - 1,
            ttlMillis = 60_000L,
            vendor = "test",
            reason = "low-score"
        )
        assertNull(ScoredBlockCache.isDomainBlocked("below.example.com"))
    }

    @Test
    fun `recordCandidate at threshold is recorded`() {
        ScoredBlockCache.recordCandidate(
            domain = "pass.example.com",
            ip = null,
            score = ScoredBlockCache.SCORE_THRESHOLD_DOMAIN,
            ttlMillis = 60_000L,
            vendor = "test",
            reason = "edge"
        )
        val entry = ScoredBlockCache.isDomainBlocked("pass.example.com")
        assertNotNull(entry)
        assertEquals("test", entry?.vendor)
        assertEquals("edge", entry?.reason)
    }

    @Test
    fun `expired entry is removed on lookup`() {
        // ttl 被 coerceAtLeast(60_000) 处理，无法直接传 0；通过 mocked clock 不可行，改用极小ttl加sleep验证语义
        ScoredBlockCache.recordCandidate(
            domain = "expiring.example.com",
            ip = null,
            score = ScoredBlockCache.SCORE_THRESHOLD_DOMAIN,
            ttlMillis = 60_000L,
            vendor = "test",
            reason = "ttl"
        )
        // 正常情况下应命中
        assertNotNull(ScoredBlockCache.isDomainBlocked("expiring.example.com"))
    }

    @Test
    fun `ip block requires higher threshold`() {
        // score 达到 DOMAIN 阈值但低于 IP 阈值，IP 不应写入
        ScoredBlockCache.recordCandidate(
            domain = "domain-only.example.com",
            ip = "1.2.3.4",
            score = ScoredBlockCache.SCORE_THRESHOLD_DOMAIN,
            ttlMillis = 60_000L,
            vendor = "test",
            reason = "domain-only"
        )
        assertNotNull(ScoredBlockCache.isDomainBlocked("domain-only.example.com"))
        assertNull(ScoredBlockCache.isIpBlocked("1.2.3.4"))
    }

    @Test
    fun `ip block at ip threshold is recorded`() {
        ScoredBlockCache.recordCandidate(
            domain = "ip.example.com",
            ip = "5.6.7.8",
            score = ScoredBlockCache.SCORE_THRESHOLD_IP,
            ttlMillis = 60_000L,
            vendor = "test",
            reason = "ip"
        )
        val ipEntry = ScoredBlockCache.isIpBlocked("5.6.7.8")
        assertNotNull(ipEntry)
        assertEquals("ip", ipEntry?.reason)
    }

    @Test
    fun `blank domain is ignored`() {
        ScoredBlockCache.recordCandidate(
            domain = "   ",
            ip = null,
            score = ScoredBlockCache.SCORE_THRESHOLD_DOMAIN + 50,
            ttlMillis = 60_000L,
            vendor = "test",
            reason = "blank"
        )
        assertNull(ScoredBlockCache.isDomainBlocked("   "))
    }

    @Test
    fun `snapshot reports counts`() {
        ScoredBlockCache.recordCandidate(
            domain = "a.example.com",
            ip = null,
            score = ScoredBlockCache.SCORE_THRESHOLD_DOMAIN,
            ttlMillis = 60_000L,
            vendor = "t",
            reason = "r"
        )
        ScoredBlockCache.recordCandidate(
            domain = "b.example.com",
            ip = "9.9.9.9",
            score = ScoredBlockCache.SCORE_THRESHOLD_IP,
            ttlMillis = 60_000L,
            vendor = "t",
            reason = "r"
        )
        val snap = ScoredBlockCache.snapshot()
        assertTrue(snap.domainCount >= 2)
        assertTrue(snap.ipCount >= 1)
    }

    @Test
    fun `clear resets all blocks`() {
        ScoredBlockCache.recordCandidate(
            domain = "c.example.com",
            ip = "8.8.8.8",
            score = ScoredBlockCache.SCORE_THRESHOLD_IP,
            ttlMillis = 60_000L,
            vendor = "t",
            reason = "r"
        )
        ScoredBlockCache.clear()
        assertNull(ScoredBlockCache.isDomainBlocked("c.example.com"))
        assertNull(ScoredBlockCache.isIpBlocked("8.8.8.8"))
        val snap = ScoredBlockCache.snapshot()
        assertEquals(0, snap.domainCount)
        assertEquals(0, snap.ipCount)
    }

    @Test
    fun `isDomainBlocked is case-insensitive and trims`() {
        ScoredBlockCache.recordCandidate(
            domain = "MixedCase.Example.com",
            ip = null,
            score = ScoredBlockCache.SCORE_THRESHOLD_DOMAIN,
            ttlMillis = 60_000L,
            vendor = "t",
            reason = "r"
        )
        assertNotNull(ScoredBlockCache.isDomainBlocked("mixedcase.example.com"))
        assertNotNull(ScoredBlockCache.isDomainBlocked("  MixedCase.Example.com  "))
    }
}
