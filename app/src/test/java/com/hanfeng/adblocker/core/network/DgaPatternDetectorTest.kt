package com.HanFeng.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DgaPatternDetectorTest {

    @Test
    fun `empty domain is not dga`() {
        assertFalse(DgaPatternDetector.looksLikeDga(""))
        assertFalse(DgaPatternDetector.looksLikeDga("   "))
    }

    @Test
    fun `short leftmost label is not dga`() {
        // 7 chars低于阈值8，不应判定为DGA
        assertFalse(DgaPatternDetector.looksLikeDga("short.example.com"))
    }

    @Test
    fun `publish host suffixes are never dga`() {
        // CDN/edge域名即使前缀随机也不记为DGA
        assertFalse(DgaPatternDetector.looksLikeDga("abcdefghijakamaihd.akamaaihd.net"))
        assertFalse(DgaPatternDetector.looksLikeDga("1234567890abcdef.edgesuite.net"))
        assertFalse(DgaPatternDetector.looksLikeDga("1234567890abcdef.cloudfront.net"))
        assertFalse(DgaPatternDetector.looksLikeDga("1234567890abcdef.fastly.net"))
    }

    @Test
    fun `hex like leftmost label matches dga`() {
        assertTrue(DgaPatternDetector.looksLikeDga("a1b2c3d4e5f6.example.com"))
    }

    @Test
    fun `base32 like leftmost label matches dga`() {
        assertTrue(DgaPatternDetector.looksLikeDga("abcefghi2345.tracker.com"))
    }

    @Test
    fun `pure digits leftmost label matches dga`() {
        assertTrue(DgaPatternDetector.looksLikeDga("12345678.tracker.com"))
    }

    @Test
    fun `consonant cluster matches dga`() {
        // 5个连续辅音
        assertTrue(DgaPatternDetector.looksLikeDga("abcdfghtest.example.com"))
    }

    @Test
    fun `repeated dashes pattern matches dga`() {
        assertTrue(DgaPatternDetector.looksLikeDga("a-bc-a-bc-a-bc-x.example.com"))
    }

    @Test
    fun `ipv6 bracket is rejected`() {
        // 转换实现里contains(":")拒绝
        assertFalse(DgaPatternDetector.looksLikeDga("[2001:db8::1]"))
    }

    @Test
    fun `normal short subdomain is not dga`() {
        // 常见广告 SDK 长度的真实子域名，但构成"自然"应不命中
        assertFalse(DgaPatternDetector.looksLikeDga("api.weixin.qq.com"))
        assertFalse(DgaPatternDetector.looksLikeDga("static.example-cdn.com"))
    }
}
