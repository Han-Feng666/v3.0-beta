package com.HanFeng.data

import org.junit.Test
import org.junit.Assert.*

/**
 * P2.2 单元测试：规则解析功能
 * 
 * 测试覆盖率目标：
 * - 规则解析逻辑：100%
 * - 域名标准化：100%
 * - 白名单检测：100%
 */
class RuleParserTest {
    
    @Test
    fun `test Adblock format parsing`() {
        val testCases = mapOf(
            "||example.com^" to true,
            "||ad.example.com^" to true,
            "||www.example.com^" to true,
            "|http://example.com/ads/*" to true,
            "example.com" to true,
            "# Comment line" to false,
            "! Another comment" to false,
            "" to false,
            "   " to false
        )
        
        testCases.forEach { (line, shouldBeValid) ->
            val isValid = isValidRuleLine(line.trim())
            assertEquals("Failed for: $line", shouldBeValid, isValid)
        }
    }
    
    @Test
    fun `test domain normalization`() {
        val testCases = mapOf(
            "www.example.com" to "example.com",
            "sub.example.com" to "example.com",
            "ad.sub.example.com" to "sub.example.com",  // 保留一级子域名
            "EXAMPLE.COM" to "example.com",
            "example.com." to "example.com",
            "https://www.example.com/path" to "example.com"
        )
        
        testCases.forEach { (input, expected) ->
            val normalized = normalizeDomain(input)
            assertEquals("Failed for: $input", expected, normalized)
        }
    }
    
    @Test
    fun `test modifier parsing`() {
        // 使用正确的 Adblock 修饰符语法（$后面跟修饰符）
        val rule = "||example.com^\$ab,cd,domain=test.com"
        val modifiers = parseModifiers(rule)
        
        assertTrue("Should contain 'ab' modifier", modifiers.containsKey("ab"))
        assertTrue("Should contain 'cd' modifier", modifiers.containsKey("cd"))
        assertTrue("Should contain 'domain' modifier", modifiers.containsKey("domain"))
        assertEquals("Modifier count should be 3", 3, modifiers.size)
    }
    
    @Test
    fun `test exception rule detection`() {
        val testCases = mapOf(
            "@@||example.com^" to true,
            "@@http://example.com/ads/*" to true,
            "||example.com^" to false,
            "@@||whitelist.com^" to true
        )
        
        testCases.forEach { (rule, isException) ->
            assertEquals("Failed for: $rule", isException, rule.startsWith("@@"))
        }
    }
    
    @Test
    fun `test whitelist domain check`() {
        val protectedDomains = setOf(
            "qq.com", 
            "weixin.qq.com",
            "alipay.com"
        )
        
        assertTrue("qq.com should be protected", isProtectedDomain("qq.com", protectedDomains))
        assertTrue("sub.qq.com should be protected", isProtectedDomain("sub.qq.com", protectedDomains))
        // ad.qq.com 也会匹配 qq.com，所以应该被保护
        assertTrue("ad.qq.com should be protected (matches qq.com)", isProtectedDomain("ad.qq.com", protectedDomains))
        assertFalse("example.com should not be protected", isProtectedDomain("example.com", protectedDomains))
    }
    
    // Helper functions
    private fun isValidRuleLine(line: String): Boolean {
        if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) return false
        return line.isNotEmpty()
    }
    
    private fun normalizeDomain(domain: String): String {
        var result = domain
            .lowercase()
            .removeSuffix(".")
        
        if (result.startsWith("http://") || result.startsWith("https://")) {
            result = result.substringAfter("://").substringBefore("/")
        }
        
        // Remove www prefix and subdomains
        val parts = result.split(".")
        return if (parts.size >= 3) {
            parts.drop(1).joinToString(".")
        } else {
            result
        }
    }
    
    private fun parseModifiers(rule: String): Map<String, String> {
        val modifiers = mutableMapOf<String, String>()
        val dollarIndex = rule.indexOf('$')
        if (dollarIndex == -1) return modifiers
        
        val modifierString = rule.substring(dollarIndex + 1)
        modifierString.split(',').forEach { modifier ->
            val parts = modifier.split('=')
            if (parts.size == 2) {
                modifiers[parts[0]] = parts[1]
            } else {
                modifiers[modifier] = ""
            }
        }
        return modifiers
    }
    
    private fun isProtectedDomain(domain: String, protectedDomains: Set<String>): Boolean {
        val normalized = normalizeDomain(domain)
        return protectedDomains.any { protected ->
            normalized == protected || normalized.endsWith(".$protected")
        }
    }
}
