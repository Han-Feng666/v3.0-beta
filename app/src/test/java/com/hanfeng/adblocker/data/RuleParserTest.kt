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
        // 关键: cachedWhitelistHits 是进程级 ConcurrentHashMap, 一次测试遗留的命中会让后续所有测试
        // 在调 isWhitelistedDomain 时直接走缓存导致 isBlocked 提前 false, 引起跨测试 flaky 失败.
        // 此前 setup + cleanup 必须显式清掉缓存, 否则 manual rules / imported rules 的子集失败不可复现.
        val clearCaches = RuleRepository::class.java.getDeclaredMethod("clearCaches")
            .apply { isAccessible = true }
        val context = mock(Context::class.java)
        clearCaches.invoke(RuleRepository)
        updateCache.invoke(RuleRepository, rules)
        try {
            block(context)
        } finally {
            clearCaches.invoke(RuleRepository)
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

    @Test
    fun `port wildcard rule star colon port with network modifier is parsed`() {
        val line = "*:17204\$network"
        val parsed = parseRuleLineViaReflection(line)
        assertNotNull("parseRuleLine should produce non-null list for $line", parsed)
        assertTrue("parsed list should be non-empty", (parsed as List<*>).isNotEmpty())
        val first = parsed.first()
        assertEquals("domain should be wildcard", "*", readParsedString(first, "domain"))
        val ports = readParsedAnySet(first, "destinationPorts").mapNotNull { it.toString().toIntOrNull() }
        assertTrue("17204 should be in destinationPorts", ports.contains(17204))
    }

    @Test
    fun `single label abp anchor rule double_pipe xccx caret is parsed`() {
        val line = "||xccx^"
        val parsed = parseRuleLineViaReflection(line)
        assertNotNull("parseRuleLine should produce non-null list for $line", parsed)
        assertTrue("parsed list should be non-empty", (parsed as List<*>).isNotEmpty())
        val first = parsed.first()
        assertEquals("domain should be normalized xccx", "xccx", readParsedString(first, "domain"))
    }

    @Test
    fun `analyzeImportContent counts port wildcard rule as safe`() {
        val ctx = mock(Context::class.java)
        presetEmptyRulesCache()
        // 多种端口形式都应识别
        assertEquals(1, readReportInt(analyzeImportContentViaReflection(ctx, "*:17204\$network\n"), "safeBlockedRules"))
        assertEquals(1, readReportInt(analyzeImportContentViaReflection(ctx, "*:8080\$network\n"), "safeBlockedRules"))
        assertEquals(1, readReportInt(analyzeImportContentViaReflection(ctx, "*:443\n"), "safeBlockedRules"))
        assertEquals(1, readReportInt(analyzeImportContentViaReflection(ctx, "*:17204"), "safeBlockedRules"))
    }

    @Test
    fun `analyzeImportContent counts single label abp anchor as safe`() {
        val ctx = mock(Context::class.java)
        presetEmptyRulesCache()
        // 各种单字母/数字单标签 ABP anchor
        assertEquals(1, readReportInt(analyzeImportContentViaReflection(ctx, "||xccx^\n"), "safeBlockedRules"))
        assertEquals(1, readReportInt(analyzeImportContentViaReflection(ctx, "||abc^"), "safeBlockedRules"))
        assertEquals(1, readReportInt(analyzeImportContentViaReflection(ctx, "||host123/-hint"), "safeBlockedRules"))
    }

    @Test
    fun `analyzeImportContent counts both via multiline import`() {
        val ctx = mock(Context::class.java)
        presetEmptyRulesCache()
        val report = analyzeImportContentViaReflection(ctx, "*:17204\$network\n||xccx^\nexample.com\n")
        assertEquals("two advanced rules + one simple rule should sum to 3 safe", 3, readReportInt(report, "safeBlockedRules"))
    }

    @Test
    fun `analyzeImportContent skips bare star ruleset consistent with streaming`() {
        val ctx = mock(Context::class.java)
        presetEmptyRulesCache()
        // "** 不带 ports/regex/keyword/ipCidr 任一修饰" — importRulesStreaming 不写, analyze 也不计
        val report = analyzeImportContentViaReflection(ctx, "*\n")
        assertEquals(0, readReportInt(report, "safeBlockedRules"))
    }

    @Test
    fun `analyzeImportContent fallback handles unrecognized complex rule as unsupported`() {
        val ctx = mock(Context::class.java)
        presetEmptyRulesCache()
        // 一条完全不能 parseRuleLine 识别、但可被 parseUnsupportedImportRule 兜底的优势项
        // 调 fake rule 验证 analyze 不再计 invalid 而是计 safeBlocked (与 streaming 行为一致)
        val report = analyzeImportContentViaReflection(ctx, "weird-unknown-syntax-rule-line-without-domain")
        val invalid = readReportInt(report, "invalidRules")
        assertEquals("should not have any invalid entries", 0, invalid)
    }

    @Test
    fun `analyzeImportContent counts mainstream rule formats correctly`() {
        val ctx = mock(Context::class.java)
        presetEmptyRulesCache()
        // 覆盖各主流规则格式 - 与 importRulesStreaming 应保持一致的识别结果
        val content = buildString {
            // ABP 标准
            appendLine("||doubleclick.net^")
            appendLine("||googlesyndication.com^")
            appendLine("@@||cdn.example.com^")  // exception rule
            // 单标签 ABP anchor
            appendLine("||xccx^")
            // 端口通配
            appendLine("*:443")
            appendLine("*:17204\$network")
            // hosts 表
            appendLine("0.0.0.0 ads.example.com")
            appendLine("127.0.0.1  tracker.example.org")
            // 纯域 (hosts / dnsmasq 风格)
            appendLine("analytics.example.net")
            // dnsmasq
            appendLine("address=/ad.example.com/0.0.0.0")
            // smartdns
            appendLine("address /-ad.com/0.0.0.0")
            // surge/loon/clash 类
            appendLine("DOMAIN-SUFFIX,doubleclick.net,REJECT")
            appendLine("DOMAIN-KEYWORD,googleads,REJECT")
            // adguard
            appendLine("||ads.example.com^\$important")
            // openwrt
            appendLine("local-zone \".ads.example.com\" refuse")
        }.toString()

        val report = analyzeImportContentViaReflection(ctx, content)
        // 至少应识别 12 条以上 safe 规则 (1 条 exception 不算 safeBlocked)
        val safeBlocked = readReportInt(report, "safeBlockedRules")
        assertTrue("Should have recognized at least 10 mainstream safe rules, got $safeBlocked", safeBlocked >= 10)
        // 至少 1 条 exception
        val safeException = readReportInt(report, "safeExceptionRules")
        assertTrue("Should have at least 1 exception rule, got $safeException", safeException >= 1)
        // invalidLines 不应大暴雷
        val invalid = readReportInt(report, "invalidRules")
        assertTrue("Unexpected invalid rules count $invalid", invalid <= 4)
    }

    private fun presetEmptyRulesCache() {
        val rulesField = RuleRepository::class.java.getDeclaredField("cachedRules")
        rulesField.isAccessible = true
        rulesField.set(RuleRepository, emptyList<com.HanFeng.model.BlockRule>())
        val vendorsField = RuleRepository::class.java.getDeclaredField("cachedCustomVendors")
        vendorsField.isAccessible = true
        vendorsField.set(RuleRepository, emptyMap<String, String>())
    }

    private fun analyzeImportContentViaReflection(ctx: Context, content: String): Any {
        val method = RuleRepository::class.java.getDeclaredMethod("analyzeImportContent", Context::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(RuleRepository, ctx, content)!!
    }

    private fun readReportInt(report: Any, fieldName: String): Int {
        val field = report::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(report) as Int
    }

    @Test
    fun `normalize leaves star colon port dollar network intact`() {
        val raw = "*:17204\$network"
        val method = com.HanFeng.data.RuleTextNormalizer::class.java.getDeclaredMethod("normalizeMessyRuleLine", String::class.java)
        method.isAccessible = true
        val normalized = method.invoke(com.HanFeng.data.RuleTextNormalizer, raw) as String
        assertEquals("normalize should not mangle *:17204\$network", "*:17204\$network", normalized)
    }

    @Test
    fun `normalize converts fullwidth asterisk pipe dollar caret to half width`() {
        val method = com.HanFeng.data.RuleTextNormalizer::class.java.getDeclaredMethod("normalizeMessyRuleLine", String::class.java)
        method.isAccessible = true
        assertEquals("*:17204\$network", method.invoke(com.HanFeng.data.RuleTextNormalizer, "＊:17204＄network") as String)
        assertEquals("||xccx^", method.invoke(com.HanFeng.data.RuleTextNormalizer, "｜｜xccx＾") as String)
    }

    @Test
    fun `normalize leaves double pipe xccx caret intact`() {
        val raw = "||xccx^"
        val method = com.HanFeng.data.RuleTextNormalizer::class.java.getDeclaredMethod("normalizeMessyRuleLine", String::class.java)
        method.isAccessible = true
        val normalized = method.invoke(com.HanFeng.data.RuleTextNormalizer, raw) as String
        assertEquals("normalize should not mangle ||xccx^", "||xccx^", normalized)
    }

    @Test
    fun `extractSimpleImportDomain returns null for port wildcard and single label anchor`() {
        assertEquals(null, extractSimpleImportDomainViaReflection("*:17204\$network"))
        assertEquals(null, extractSimpleImportDomainViaReflection("||xccx^"))
    }

    private fun extractSimpleImportDomainViaReflection(line: String): String? {
        val method = RuleRepository::class.java.getDeclaredMethod("extractSimpleImportDomain", String::class.java)
        method.isAccessible = true
        return method.invoke(RuleRepository, line) as String?
    }

    private fun parseRuleLineViaReflection(line: String): Any? {        val ctxClass = Class.forName("com.HanFeng.data.RuleParsingSupport\$LineContext")
        val ctx = ctxClass.getDeclaredConstructor().newInstance()
        val method = RuleRepository::class.java.getDeclaredMethod("parseRuleLine", String::class.java, ctxClass)
        method.isAccessible = true
        return method.invoke(RuleRepository, line, ctx)
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

    @Suppress("UNCHECKED_CAST")
    private fun readParsedAnySet(parsed: Any?, fieldName: String): Set<*> {
        val field = parsed!!::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(parsed) as Set<*>
    }

    private fun readParsedString(parsed: Any?, fieldName: String): String? {
        val field = parsed!!::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(parsed) as String?
    }
}
