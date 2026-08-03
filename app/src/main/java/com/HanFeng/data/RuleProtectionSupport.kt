package com.HanFeng.data

import com.HanFeng.core.network.RegexCache

object RuleProtectionSupport {
    private val adTokenNormalizeRegex = RegexCache.get("[^a-z0-9]")
    private val protectedNovelAppAdSubdomainPatterns = listOf(
        "ad", "ads", "adserver", "adtrack", "adlog", "adx", "adv", "banner", "splash",
        "promotion", "promo", "marketing", "track", "tracking", "log", "logger", "stat", "stats", "analytics"
    )
    private val aggressiveNovelAdStrongSignals = listOf(
        "pangolin", "pangle", "gromore", "oceanengine", "adservice", "adserver", "adtrack",
        "adsdk", "unionad", "mediation", "rtb", "dsp", "ssp", "reward", "splash", "interstitial"
    )

    fun matchesExactOrSubdomain(domain: String, protectedDomains: Set<String>): Boolean {
        return protectedDomains.contains(domain) || protectedDomains.any { domain.endsWith(".$it") }
    }

    fun matchesExactOrSubdomain(domain: String, trie: DomainSuffixTrie): Boolean {
        return trie.contains(domain)
    }

    fun isSensitiveAuthDomain(
        domain: String,
        sanitizeDomain: (String) -> String?,
        isWhitelistedDomain: (String) -> Boolean,
        sensitiveAuthKeywords: List<String>,
        keywordMatches: (String, String, String) -> Boolean
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (isWhitelistedDomain(normalized)) return true
        val lower = normalized.lowercase()
        val normalizedTokens = lower.replace(adTokenNormalizeRegex, "")
        val labels = lower.split('.').filter { it.isNotBlank() }
        return sensitiveAuthKeywords.any { keyword ->
            labels.any { it == keyword } || keywordMatches(lower, normalizedTokens, keyword)
        }
    }

    fun isProtectedNovelAppDomain(
        domain: String,
        sanitizeDomain: (String) -> String?,
        isGameCoreDomain: (String) -> Boolean,
        isSocialCoreDomain: (String) -> Boolean,
        matchesProtectedDomain: (String) -> Boolean
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lower = normalized.lowercase()
        if (protectedNovelAppAdSubdomainPatterns.any { lower.startsWith("$it.") || lower.startsWith("$it-") || lower == it }) return false
        if (isGameCoreDomain(normalized)) return false
        if (isSocialCoreDomain(normalized)) return false
        return matchesProtectedDomain(normalized)
    }

    fun hasAggressiveNovelAdSignal(domain: String): Boolean {
        val lowerDomain = domain.lowercase()
        return aggressiveNovelAdStrongSignals.any { lowerDomain.contains(it) }
    }

    fun isLowValueSuspiciousSampleDomain(
        domain: String,
        sanitizeDomain: (String) -> String?,
        isWhitelistedDomain: (String) -> Boolean,
        isSensitiveAuthDomain: (String) -> Boolean,
        isGameCoreDomain: (String) -> Boolean,
        isSocialCoreDomain: (String) -> Boolean,
        isMediaCoreDomain: (String) -> Boolean,
        isBusinessCoreDomain: (String) -> Boolean,
        isNovelContentDomain: (String) -> Boolean,
        isProtectedNovelAppDomain: (String) -> Boolean,
        hasAggressiveNovelAdSignal: (String) -> Boolean,
        isProtectedByteDanceInfraDomain: (String) -> Boolean,
        looksLikePushRecommendationAdDomain: (String) -> Boolean
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return true
        if (isWhitelistedDomain(normalized)) return true
        if (isSensitiveAuthDomain(normalized)) return true
        if (isGameCoreDomain(normalized)) return true
        if (isSocialCoreDomain(normalized)) return true
        if (isMediaCoreDomain(normalized)) return true
        if (isBusinessCoreDomain(normalized)) return true
        if (isNovelContentDomain(normalized)) return true
        if (isProtectedNovelAppDomain(normalized) && !hasAggressiveNovelAdSignal(normalized)) return true
        if (isProtectedByteDanceInfraDomain(normalized) && !hasAggressiveNovelAdSignal(normalized) && !looksLikePushRecommendationAdDomain(normalized)) return true
        return false
    }
}
