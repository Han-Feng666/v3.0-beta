package com.HanFeng.data

object RuleVendorSupport {
    private val bypassKeywords = listOf(
        "httpdns", "doh", "doq", "dot", "dns-query", "dnsquery", "resolver", "encrypted-dns"
    )
    private val trustedDnsHints = listOf(
        "alidns", "aliyuncs", "cloudflare-dns", "nextdns", "adguard-dns", "quad9", "opendns",
        "cleanbrowsing", "umbrella", "dns.sb", "dns0", "google", "baidu"
    )

    fun isBypassProtectionDomain(
        domain: String,
        sanitizeDomain: (String) -> String?,
        buildDomainCandidates: (String) -> Sequence<String>,
        bypassProtectionDomains: Set<String>
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (buildDomainCandidates(normalized).any(bypassProtectionDomains::contains)) return true
        val lower = normalized.lowercase()
        return bypassKeywords.any(lower::contains) && trustedDnsHints.any(lower::contains)
    }

    fun normalizeVendorName(
        vendor: String,
        defaultVendor: String,
        vendorAliases: Map<String, String>
    ): String {
        if (vendor.isBlank()) return defaultVendor
        var current = vendor.trim()
        val seen = linkedSetOf<String>()
        while (seen.add(current)) {
            val next = vendorAliases[current] ?: break
            current = next
        }
        return current
    }

    fun identifierMatches(text: String, normalizedTokens: String, identifier: String): Boolean {
        if (identifier.isBlank()) return false
        val lowerIdentifier = identifier.lowercase()
        val normalizedIdentifier = lowerIdentifier.replace(Regex("[^a-z0-9\u4e00-\u9fff]"), "")
        return text.contains(lowerIdentifier) || normalizedTokens.contains(normalizedIdentifier)
    }

    fun classifyVendorByDomainSignals(
        normalizedDomain: String,
        defaultVendor: String,
        genericAdVendor: String,
        normalizeVendorName: (String) -> String,
        vendorPatterns: Map<String, List<String>>,
        vendorKeywords: Map<String, List<String>>,
        vendorSdkIdentifiers: Map<String, List<String>>,
        keywordMatches: (String, String, String) -> Boolean,
        identifierMatches: (String, String, String) -> Boolean,
        looksLikeAdDomain: (String) -> Boolean
    ): String {
        val lower = normalizedDomain.lowercase()
        val normalizedTokens = lower.replace(Regex("[^a-z0-9]"), "")
        vendorPatterns.entries.firstOrNull { (_, patterns) -> patterns.any { lower.contains(it) } }?.let {
            return normalizeVendorName(it.key)
        }
        vendorKeywords.entries.firstOrNull { (_, keywords) -> keywords.any { keywordMatches(lower, normalizedTokens, it) } }?.let {
            return normalizeVendorName(it.key)
        }
        vendorSdkIdentifiers.entries.firstOrNull { (_, identifiers) -> identifiers.any { identifierMatches(lower, normalizedTokens, it) } }?.let {
            return normalizeVendorName(it.key)
        }
        return if (looksLikeAdDomain(lower)) genericAdVendor else defaultVendor
    }

    fun classifyVendorByHints(
        hints: Array<out String?>,
        normalizeVendorName: (String) -> String,
        vendorSdkIdentifiers: Map<String, List<String>>,
        identifierMatches: (String, String, String) -> Boolean
    ): String? {
        return hints
            .asSequence()
            .filterNotNull()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .mapNotNull { hint ->
                val hintTokens = hint.replace(Regex("[^a-z0-9\u4e00-\u9fff]"), "")
                vendorSdkIdentifiers.entries.firstOrNull { (_, identifiers) -> identifiers.any { identifierMatches(hint, hintTokens, it) } }?.key
            }
            .firstOrNull()
            ?.let(normalizeVendorName)
    }
}
