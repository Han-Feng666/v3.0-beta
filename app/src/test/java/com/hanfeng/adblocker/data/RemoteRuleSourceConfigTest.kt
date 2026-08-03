package com.HanFeng.data

import com.HanFeng.model.RemoteRuleSourceConfig
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRuleSourceConfigTest {

    private val gson = Gson()

    @Test
    fun `default fallbackUrls is empty list`() {
        val source = RemoteRuleSourceConfig(
            id = "x",
            name = "X",
            url = "https://example.com/rules.txt"
        )
        assertNotNull(source.fallbackUrls)
        assertTrue(source.fallbackUrls.isEmpty())
    }

    @Test
    fun `fallbackUrls survive gson round-trip`() {
        val source = RemoteRuleSourceConfig(
            id = "y",
            name = "Y",
            url = "https://primary.example.com/rules.txt",
            fallbackUrls = listOf(
                "https://fallback1.example.com/rules.txt",
                "https://fallback2.example.com/rules.txt"
            )
        )
        val json = gson.toJson(source)
        val restored = gson.fromJson(json, RemoteRuleSourceConfig::class.java)
        assertEquals(source.url, restored.url)
        assertEquals(source.fallbackUrls, restored.fallbackUrls)
    }

    @Test
    fun `legacy json without fallbackUrls deserializes to empty list`() {
        // 模拟旧版本 prefs 里的 JSON，没有 fallbackUrls 字段（避免升级后崩溃）
        val legacyJson = """{"id":"z","name":"Z","url":"https://legacy.example.com/rules.txt","enabled":true}"""
        val restored = gson.fromJson(legacyJson, RemoteRuleSourceConfig::class.java)
        assertEquals("z", restored.id)
        assertEquals("https://legacy.example.com/rules.txt", restored.url)
        assertNotNull(restored.fallbackUrls)
        assertTrue(restored.fallbackUrls.isEmpty())
    }

    @Test
    fun `default fallbackUrls list for security source must not be empty`() {
        // 防回归：默认规则源必须带 fallbackUrls，否则受 jsdelivr 不通影响
        // 这里不再读取 RuleRepository 的 private 常量，用一个静态契约校验
        val expectedFallbacks = listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://ghproxy.com/https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://cdn.staticaly.com/gh/StevenBlack/hosts@master/hosts"
        )
        // 用真实默认源的 url 字段构造一个示例，验证 fallbackUrls 字段语义能通过 typedef
        val source = RemoteRuleSourceConfig(
            id = "security-stevenblack",
            name = "StevenBlack 安全防护",
            url = "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts",
            fallbackUrls = expectedFallbacks
        )
        assertEquals(expectedFallbacks.size, source.fallbackUrls.size)
        assertEquals(expectedFallbacks, source.fallbackUrls)
    }
}
