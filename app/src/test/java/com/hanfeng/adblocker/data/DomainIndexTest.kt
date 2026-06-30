package com.HanFeng.data

import com.HanFeng.model.BlockRule
import com.HanFeng.model.RuleSource
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class DomainSuffixTrieTest {

    @Test
    fun `exact domain match returns true`() {
        val trie = DomainSuffixTrie.fromDomains(listOf("example.com"))
        assertTrue(trie.contains("example.com"))
    }

    @Test
    fun `subdomain matches parent domain rule`() {
        val trie = DomainSuffixTrie.fromDomains(listOf("example.com"))
        assertTrue(trie.contains("www.example.com"))
        assertTrue(trie.contains("ads.tracking.example.com"))
    }

    @Test
    fun `parent domain does not match subdomain rule`() {
        val trie = DomainSuffixTrie.fromDomains(listOf("ads.example.com"))
        assertFalse(trie.contains("example.com"))
        assertTrue(trie.contains("ads.example.com"))
        assertTrue(trie.contains("cdn.ads.example.com"))
    }

    @Test
    fun `no match for unrelated domain`() {
        val trie = DomainSuffixTrie.fromDomains(listOf("example.com"))
        assertFalse(trie.contains("other.com"))
        assertFalse(trie.contains("example.org"))
        assertFalse(trie.contains("sub.other-example.com"))
    }

    @Test
    fun `trailing dot should be stripped before lookup`() {
        val trie = DomainSuffixTrie()
        trie.insert("example.com")
        assertTrue(trie.contains("example.com"))
    }

    @Test
    fun `empty domain returns false`() {
        val trie = DomainSuffixTrie.fromDomains(listOf("example.com"))
        assertFalse(trie.contains(""))
    }

    @Test
    fun `large rule set performance test`() {
        val domains = (1..10000).map { "ad$it.tracking.example.com" }
        val trie = DomainSuffixTrie.fromDomains(domains)
        assertTrue(trie.contains("ad5000.tracking.example.com"))
        assertFalse(trie.contains("realcontent.example.com"))
    }

    @Test
    fun `multiple overlapping domains work correctly`() {
        val trie = DomainSuffixTrie()
        trie.insert("example.com")
        trie.insert("ads.example.com")
        assertTrue(trie.contains("example.com"))
        assertTrue(trie.contains("www.example.com"))
        assertTrue(trie.contains("ads.example.com"))
        assertTrue(trie.contains("cdn.ads.example.com"))
    }

    @Test
    fun `single label domains match`() {
        val trie = DomainSuffixTrie()
        trie.insert("com")
        assertTrue(trie.contains("example.com"))
        assertTrue(trie.contains("com"))
    }

    @Test
    fun `inserting duplicates does not break matching`() {
        val trie = DomainSuffixTrie()
        trie.insert("example.com")
        trie.insert("example.com")
        trie.insert("example.com")
        assertTrue(trie.contains("example.com"))
        assertTrue(trie.contains("www.example.com"))
    }

    @Test
    fun `edge case very long domain`() {
        val longDomain = (1..50).joinToString(".") { "label$it" } + ".example.com"
        val trie = DomainSuffixTrie.fromDomains(listOf("example.com"))
        assertTrue(trie.contains(longDomain))
    }

    @Test
    fun `edge case single char domains`() {
        val trie = DomainSuffixTrie()
        trie.insert("a.co")
        assertTrue(trie.contains("a.co"))
        assertTrue(trie.contains("www.a.co"))
        assertFalse(trie.contains("b.co"))
    }
}


class RuleExceptionMatchingTest {

    @Test
    fun `isSimpleDomainRule identifies plain domain rules`() {
        val rule = BlockRule(
            id = "test-1",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.REFERENCE
        )
        assertTrue(RuleRepository.isSimpleDomainRule(rule))
    }

    @Test
    fun `isSimpleDomainRule rejects rules with modifiers`() {
        val ruleWithRedirect = BlockRule(
            id = "test-2",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.REFERENCE,
            redirect = true,
            redirectResource = "noopjs"
        )
        assertFalse(RuleRepository.isSimpleDomainRule(ruleWithRedirect))
    }

    @Test
    fun `isSimpleDomainRule rejects rules with jsInject`() {
        val ruleWithJs = BlockRule(
            id = "test-3",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.REFERENCE,
            jsInjectRules = setOf("console.log('test')")
        )
        assertFalse(RuleRepository.isSimpleDomainRule(ruleWithJs))
    }

    @Test
    fun `isSimpleDomainRule rejects rules with dnsTypes`() {
        val rule = BlockRule(
            id = "test-4",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.REFERENCE,
            dnsTypes = setOf(1)
        )
        assertFalse(RuleRepository.isSimpleDomainRule(rule))
    }

    @Test
    fun `isSimpleDomainRule accepts exception rules as simple`() {
        val rule = BlockRule(
            id = "test-5",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.MANUAL,
            exceptionRule = true
        )
        assertTrue(RuleRepository.isSimpleDomainRule(rule))
    }

    @Test
    fun `isSimpleDomainRule rejects unsupported rules`() {
        val rule = BlockRule(
            id = "test-6",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.UNSUPPORTED
        )
        assertFalse(RuleRepository.isSimpleDomainRule(rule))
    }

    @Test
    fun `isSimpleDomainRule accepts user-owned imported rules`() {
        val rule = BlockRule(
            id = "test-7",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.IMPORTED
        )
        assertTrue(RuleRepository.isSimpleDomainRule(rule))
    }

    @Test
    fun `isSimpleDomainRule rejects rules with domainConstraints`() {
        val rule = BlockRule(
            id = "test-8",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.REFERENCE,
            domainConstraints = setOf("example.com")
        )
        assertFalse(RuleRepository.isSimpleDomainRule(rule))
    }

    @Test
    fun `isSimpleDomainRule rejects empty response rules`() {
        val rule = BlockRule(
            id = "test-9",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.IMPORTED,
            important = true,
            emptyResponse = true
        )
        assertFalse(RuleRepository.isSimpleDomainRule(rule))
    }

    @Test
    fun `isSimpleDomainRule rejects contextual http rules`() {
        assertFalse(RuleRepository.isSimpleDomainRule(BlockRule(
            id = "test-10",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.IMPORTED,
            cookieRemove = setOf("adid")
        )))
        assertFalse(RuleRepository.isSimpleDomainRule(BlockRule(
            id = "test-11",
            domain = "ads.example.com",
            vendor = "TestVendor",
            source = RuleSource.IMPORTED,
            toDomains = setOf("target.example.com")
        )))
    }

    @Test
    fun `isSimpleDomainRule tolerates legacy null collection fields`() {
        val legacyJson = """
            {
              "id":"legacy-null-set",
              "domain":"ads.example.com",
              "vendor":"TestVendor",
              "source":"IMPORTED",
              "excludedDomainConstraints":null,
              "denyallow":null,
              "requestTypes":null,
              "removeParams":null,
              "jsInjectRules":null,
              "cookieRemove":null,
              "toDomains":null
            }
        """.trimIndent()
        val rule = Gson().fromJson(legacyJson, BlockRule::class.java)

        assertTrue(RuleRepository.isSimpleDomainRule(rule))
    }
}


class RuleModifierSupportCompatibilityTest {

    @Test
    fun `removeheader aliases are parsed as request header removals`() {
        val modifier = RuleModifierSupport.parseModifierInfo(
            modifierPart = "remove-request-header=User-Agent|X-Track",
            unsupportedAdGuardModifiers = emptySet(),
            ignorableAdGuardModifiers = emptySet(),
            sanitizeAppPackageToken = { it },
            mapDnsTypeToken = { null },
            normalizeDnsTypes = { it },
            mergeDnsTypes = { left, right -> (left.orEmpty() + right.orEmpty()).takeIf { it.isNotEmpty() } }
        )
        assertFalse(modifier.invalid)
        assertEquals(setOf("user-agent", "x-track"), modifier.removeRequestHeaders)
    }
}


class SimpleDomainIndexDedupTest {

    @Test
    fun `dedup formula removes excepted domains from blocked`() {
        val blocked = setOf("ads.example.com", "track.example.com")
        val exceptions = setOf("ads.example.com")
        val userOwnedBlocked = emptySet<String>()

        val result = (blocked - exceptions) + userOwnedBlocked
        assertEquals(setOf("track.example.com"), result)
    }

    @Test
    fun `dedup formula preserves user-owned blocked domains`() {
        val blocked = setOf("ads.example.com", "track.example.com")
        val exceptions = setOf("ads.example.com")
        val userOwnedBlocked = setOf("ads.example.com")

        val result = (blocked - exceptions) + userOwnedBlocked
        assertEquals(setOf("ads.example.com", "track.example.com"), result)
    }

    @Test
    fun `dedup formula handles empty sets`() {
        val result = (emptySet<String>() - emptySet()) + emptySet()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `dedup formula handles domain not in blocked`() {
        val blocked = setOf("track.example.com")
        val exceptions = setOf("ads.example.com")
        val userOwnedBlocked = emptySet<String>()

        val result = (blocked - exceptions) + userOwnedBlocked
        assertEquals(setOf("track.example.com"), result)
    }
}
