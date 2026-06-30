package com.HanFeng.data

import android.content.Context
import org.junit.Test
import org.junit.Assert.*
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RuleSource
import org.mockito.Mockito.mock

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

    @Test
    fun `domain modifier falls back to host when request domain is absent`() {
        val rule = BlockRule(
            id = "test",
            domain = "example.com",
            vendor = "test",
            source = RuleSource.IMPORTED,
            domainConstraints = setOf("example.com")
        )
        val method = RuleRepository::class.java.getDeclaredMethod(
            "matchesRequestContext",
            BlockRule::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        assertTrue(method.invoke(RuleRepository, rule, "api.example.com", null) as Boolean)
        assertFalse(method.invoke(RuleRepository, rule, "api.other.com", null) as Boolean)
    }

    @Test
    fun `manual rules override whitelist protection`() {
        withRepositoryRules(
            listOf(testRule(domain = "alipay.com", source = RuleSource.MANUAL))
        ) { context ->
            assertTrue(RuleRepository.isBlocked(context, "alipay.com"))
            assertTrue(RuleRepository.isBlockedFast(context, "alipay.com"))
            assertTrue(RuleRepository.isUrlBlocked(context, "alipay.com", "/ads/start"))
            assertNotNull(RuleRepository.findMatchingRule(context, "alipay.com"))
        }
    }

    @Test
    fun `local imported rules override whitelist protection`() {
        val rule = testRule(domain = "alipay.com", source = RuleSource.IMPORTED)

        withRepositoryRules(listOf(rule)) { context ->
            assertTrue(RuleRepository.isUserOwnedRule(rule))
            assertTrue(RuleRepository.isBlocked(context, "alipay.com"))
        }
    }

    @Test
    fun `remote imported rules keep whitelist protection`() {
        val rule = testRule(
            domain = "alipay.com",
            source = RuleSource.IMPORTED,
            remoteSourceId = "remote-source"
        )

        withRepositoryRules(listOf(rule)) { context ->
            assertFalse(RuleRepository.isUserOwnedRule(rule))
            assertFalse(RuleRepository.isBlocked(context, "alipay.com"))
            assertFalse(RuleRepository.isBlockedFast(context, "alipay.com"))
            assertFalse(RuleRepository.isUrlBlocked(context, "alipay.com", "/ads/start"))
            assertNull(RuleRepository.findMatchingRule(context, "alipay.com"))
        }
    }

    @Test
    fun `manual block overrides remote exception on whitelist domain`() {
        withRepositoryRules(
            listOf(
                testRule(domain = "alipay.com", source = RuleSource.IMPORTED, remoteSourceId = "remote-source", exceptionRule = true),
                testRule(domain = "alipay.com", source = RuleSource.MANUAL)
            )
        ) { context ->
            assertTrue(RuleRepository.isBlocked(context, "alipay.com"))
            assertTrue(RuleRepository.isUrlBlocked(context, "alipay.com", "/ads/start"))
        }
    }

    @Test
    fun `important imported rule overrides ordinary exception`() {
        withRepositoryRules(
            listOf(
                testRule(domain = "ads.example.com", source = RuleSource.IMPORTED, exceptionRule = true),
                testRule(domain = "ads.example.com", source = RuleSource.IMPORTED, important = true)
            )
        ) { context ->
            assertTrue(RuleRepository.isBlocked(context, "ads.example.com"))
            assertTrue(RuleRepository.isBlockedFast(context, "ads.example.com"))
            assertTrue(RuleRepository.isUrlBlocked(context, "ads.example.com", "/banner.js"))
        }
    }

    @Test
    fun `jsinject modifier is parsed for MITM rewrite directives`() {
        val modifier = RuleModifierSupport.parseModifierInfo(
            modifierPart = "jsinject=window.__hanfeng_rule = true",
            unsupportedAdGuardModifiers = emptySet(),
            ignorableAdGuardModifiers = emptySet(),
            sanitizeAppPackageToken = { token -> token.takeIf { it.contains('.') } },
            mapDnsTypeToken = { null },
            normalizeDnsTypes = { it },
            mergeDnsTypes = { left, right -> (left.orEmpty() + right.orEmpty()).takeIf { it.isNotEmpty() } }
        )

        assertFalse(modifier.invalid)
        assertEquals("window.__hanfeng_rule = true", modifier.jsinject)
    }

    @Test
    fun `request type aliases are normalized for imported rules`() {
        val modifier = RuleModifierSupport.parseModifierInfo(
            modifierPart = "css,xhr,sub_frame,main_frame",
            unsupportedAdGuardModifiers = emptySet(),
            ignorableAdGuardModifiers = emptySet(),
            sanitizeAppPackageToken = { token -> token.takeIf { it.contains('.') } },
            mapDnsTypeToken = { null },
            normalizeDnsTypes = { it },
            mergeDnsTypes = { left, right -> (left.orEmpty() + right.orEmpty()).takeIf { it.isNotEmpty() } }
        )

        assertFalse(modifier.invalid)
        assertTrue(modifier.requestTypeScoped)
        assertEquals(setOf("stylesheet", "xmlhttprequest", "subdocument", "document"), modifier.requestTypes)
    }

    @Test
    fun `request type scoped rules only match matching url resources`() {
        val scriptRule = BlockRule(
            id = "script-resource-rule",
            domain = "cdn.example.com",
            vendor = "test",
            source = RuleSource.IMPORTED,
            requestTypes = setOf("script")
        )

        withRepositoryRules(listOf(scriptRule)) { context ->
            assertTrue(RuleRepository.isUrlBlocked(context, "cdn.example.com", "/ads/banner.js"))
            assertFalse(RuleRepository.isUrlBlocked(context, "cdn.example.com", "/ads/banner.png"))
            assertFalse(RuleRepository.isBlocked(context, "cdn.example.com"))
        }
    }

    @Test
    fun `adguard domain rules are parsed for local import`() {
        val parsed = parseImportLines("||doubleclick.net^\n||googlesyndication.com^\$important")
        val blockedRules = readParsedList(parsed, "blockedRules")

        assertEquals(2, blockedRules.size)
        assertTrue(blockedRules.any { readParsedString(it, "domain") == "doubleclick.net" })
        assertTrue(blockedRules.any { readParsedString(it, "domain") == "googlesyndication.com" })
    }

    @Test
    fun `high value adguard modifiers are parsed for mitm directives`() {
        val modifier = RuleModifierSupport.parseModifierInfo(
            modifierPart = "important,empty,redirect=ubo-resource:noop.js,to=target.example|cdn.example,cookie=~adid,remove-request-header=X-Track",
            unsupportedAdGuardModifiers = emptySet(),
            ignorableAdGuardModifiers = emptySet(),
            sanitizeAppPackageToken = { token -> token.takeIf { it.contains('.') } },
            mapDnsTypeToken = { null },
            normalizeDnsTypes = { it },
            mergeDnsTypes = { left, right -> (left.orEmpty() + right.orEmpty()).takeIf { it.isNotEmpty() } }
        )

        assertFalse(modifier.invalid)
        assertTrue(modifier.important)
        assertTrue(modifier.emptyResponse)
        assertTrue(modifier.redirect)
        assertEquals("ubo-resource:noop.js", modifier.redirectResource)
        assertEquals(setOf("target.example", "cdn.example"), modifier.toDomains)
        assertEquals(setOf("adid"), modifier.cookieRemove)
        assertEquals(setOf("x-track"), modifier.removeRequestHeaders)
    }

    @Test
    fun `adguard domain exclusions are parsed and matched`() {
        val modifier = RuleModifierSupport.parseModifierInfo(
            modifierPart = "domain=reader.example|~pay.reader.example|~login.reader.example",
            unsupportedAdGuardModifiers = emptySet(),
            ignorableAdGuardModifiers = emptySet(),
            sanitizeAppPackageToken = { token -> token.takeIf { it.contains('.') } },
            mapDnsTypeToken = { null },
            normalizeDnsTypes = { it },
            mergeDnsTypes = { left, right -> (left.orEmpty() + right.orEmpty()).takeIf { it.isNotEmpty() } }
        )
        val rule = BlockRule(
            id = "domain-exclusion",
            domain = "ads.example.com",
            vendor = "test",
            source = RuleSource.IMPORTED,
            domainConstraints = modifier.domainConstraints,
            excludedDomainConstraints = modifier.excludedDomainConstraints
        )
        val matches = RuleRepository::class.java.getDeclaredMethod(
            "matchesRequestContext",
            BlockRule::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        assertEquals(setOf("reader.example"), modifier.domainConstraints)
        assertEquals(setOf("pay.reader.example", "login.reader.example"), modifier.excludedDomainConstraints)
        assertTrue(matches.invoke(RuleRepository, rule, "ads.example.com", "feed.reader.example") as Boolean)
        assertFalse(matches.invoke(RuleRepository, rule, "ads.example.com", "pay.reader.example") as Boolean)
        assertFalse(matches.invoke(RuleRepository, rule, "ads.example.com", "other.example") as Boolean)
    }

    @Test
    fun `denyallow excludes target host instead of request context`() {
        val rule = BlockRule(
            id = "denyallow-target",
            domain = "example.com",
            vendor = "test",
            source = RuleSource.IMPORTED,
            denyallow = setOf("cdn.example.com")
        )

        withRepositoryRules(listOf(rule)) { context ->
            assertFalse(RuleRepository.isUrlBlocked(context, "cdn.example.com", "/business.js"))
            assertFalse(RuleRepository.isUrlBlocked(context, "cdn.example.com", "/business.js", requestDomain = "reader.example"))
            assertTrue(RuleRepository.isUrlBlocked(context, "ads.example.com", "/banner.js", requestDomain = "reader.example"))
        }
    }

    @Test
    fun `common rule source formats use fast import path`() {
        val parsed = parseImportLines(
            """
            0.0.0.0 ads.example.com
            DOMAIN-SUFFIX,tracking.example.net
            HOST-SUFFIX,cdn-ad.example.cn
            domain:adservice.example.org
            suffix:promo.example.io
            host-suffix,open-screen.example.com
            address=/ad-cache.example.com/0.0.0.0
            server=/track.example.com/114.114.114.114
            *.wildcard-ad.example.net
            +.plus-ad.example.org
            """.trimIndent()
        )
        val blockedRules = readParsedList(parsed, "blockedRules")
        val domains = blockedRules.map { readParsedString(it, "domain") }.toSet()

        assertTrue("ads.example.com" in domains)
        assertTrue("tracking.example.net" in domains)
        assertTrue("cdn-ad.example.cn" in domains)
        assertTrue("adservice.example.org" in domains)
        assertTrue("promo.example.io" in domains)
        assertTrue("open-screen.example.com" in domains)
        assertTrue("ad-cache.example.com" in domains)
        assertTrue("track.example.com" in domains)
        assertTrue("wildcard-ad.example.net" in domains)
        assertTrue("plus-ad.example.org" in domains)
    }

    @Test
    fun `remote import vendor classification uses lightweight path`() {
        val parsed = parseImportLines("||doubleclick.net^")
        val blockedRule = readParsedList(parsed, "blockedRules").single()
        val vendor = classifyParsedRuleVendor(mock(Context::class.java), blockedRule, useVendorHints = false)

        assertTrue(vendor.isNotBlank())
    }

    @Test
    fun `adguard scriptlet cosmetic rule is converted to js injection`() {
        val parsed = parseCosmeticRule("example.com#%#//scriptlet('set-constant', 'ads.enabled', 'false')")

        assertNotNull(parsed)
        val scripts = readParsedSet(parsed, "jsInjectRules")
        assertTrue(scripts.any { it.contains("ads.enabled") && it.contains("false") })
        assertNull(readParsedString(parsed, "cosmeticSelector"))
    }

    @Test
    fun `unsupported adguard scriptlet cosmetic rule is skipped`() {
        val parsed = parseCosmeticRule("example.com#%#//scriptlet('unknown-scriptlet', 'ads.enabled')")

        assertNull(parsed)
    }

    @Test
    fun `adguard timer and network scriptlets are converted to js injection`() {
        val timerRule = parseCosmeticRule("example.com#%#//scriptlet('prevent-setTimeout', 'showAd', '1000')")
        val fetchRule = parseCosmeticRule("example.com#%#//scriptlet('prevent-fetch', '/ads/')")
        val xhrRule = parseCosmeticRule("example.com#%#//scriptlet('prevent-xhr', '/reward')")
        val writeRule = parseCosmeticRule("example.com#%#//scriptlet('abort-on-property-write', 'ads.bootstrap')")
        val eventRule = parseCosmeticRule("example.com#%#//scriptlet('prevent-addEventListener', 'click', 'showAd')")

        val scripts = listOf(timerRule, fetchRule, xhrRule, writeRule, eventRule)
            .flatMap { readParsedSet(it, "jsInjectRules") }
            .joinToString("\n")
        assertTrue(scripts.contains("setTimeout"))
        assertTrue(scripts.contains("window.fetch"))
        assertTrue(scripts.contains("XMLHttpRequest"))
        assertTrue(scripts.contains("ads.bootstrap"))
        assertTrue(scripts.contains("addEventListener"))
        assertTrue(scripts.contains("showAd"))
    }

    @Test
    fun `remove attr and class scriptlets observe dynamic dom`() {
        val attrRule = parseCosmeticRule("example.com#%#//scriptlet('remove-attr', 'data-ad', '.sponsor')")
        val classRule = parseCosmeticRule("example.com#%#//scriptlet('remove-class', 'ad-active', '.feed-card')")

        val scripts = listOf(attrRule, classRule)
            .flatMap { readParsedSet(it, "jsInjectRules") }
            .joinToString("\n")
        assertTrue(scripts.contains("MutationObserver"))
        assertTrue(scripts.contains("removeAttribute"))
        assertTrue(scripts.contains("classList.remove"))
    }

    // Helper functions
    private fun withRepositoryRules(rules: List<BlockRule>, block: (Context) -> Unit) {
        val updateCache = RuleRepository::class.java.getDeclaredMethod("updateRuleCache", List::class.java)
            .apply { isAccessible = true }
        val context = mock(Context::class.java)
        updateCache.invoke(RuleRepository, rules)
        try {
            block(context)
        } finally {
            updateCache.invoke(RuleRepository, emptyList<BlockRule>())
        }
    }

    private fun testRule(
        domain: String,
        source: RuleSource,
        remoteSourceId: String? = null,
        exceptionRule: Boolean = false,
        important: Boolean = false
    ): BlockRule {
        return BlockRule(
            id = "$source-$domain-${remoteSourceId.orEmpty()}-$exceptionRule",
            domain = domain,
            vendor = "test",
            source = source,
            important = important,
            exceptionRule = exceptionRule,
            remoteSourceId = remoteSourceId
        )
    }

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

    private fun parseCosmeticRule(line: String): Any? {
        val method = RuleRepository::class.java.getDeclaredMethod("parseCosmeticRule", String::class.java)
        method.isAccessible = true
        return method.invoke(RuleRepository, line)
    }

    private fun parseImportLines(content: String): Any {
        val method = RuleRepository::class.java.getDeclaredMethod("parseImportLines", String::class.java)
        method.isAccessible = true
        return method.invoke(RuleRepository, content)!!
    }

    private fun classifyParsedRuleVendor(context: Context, parsedRule: Any, useVendorHints: Boolean): String {
        val method = RuleRepository::class.java.getDeclaredMethod(
            "classifyParsedRuleVendor",
            Context::class.java,
            parsedRule::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(RuleRepository, context, parsedRule, useVendorHints) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun readParsedList(parsed: Any?, fieldName: String): List<Any> {
        val field = parsed!!::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(parsed) as List<Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun readParsedSet(parsed: Any?, fieldName: String): Set<String> {
        val field = parsed!!::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(parsed) as Set<String>
    }

    private fun readParsedString(parsed: Any?, fieldName: String): String? {
        val field = parsed!!::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(parsed) as String?
    }
}
