package com.HanFeng.data

object RuleSuspiciousSampleSupport {
    fun normalizeSuspiciousSampleDomain(
        raw: String,
        suspiciousSampleDecodeMaxLength: Int,
        suspiciousSampleMaxDecodeRounds: Int,
        sanitizeDomain: (String) -> String?,
        normalizeDomainToken: (String) -> String,
        domainExtractRegex: Regex,
        htmlNumericEntityRegex: Regex,
        unicodeEscapeRegex: Regex,
        looksLikeWhitelistedRootAdSubdomain: (String) -> Boolean,
        looksLikeAdSdkInfraDomain: (String) -> Boolean,
        looksLikePushRecommendationAdDomain: (String) -> Boolean,
        hasAggressiveNovelAdSignal: (String) -> Boolean,
        looksLikeAdDomain: (String) -> Boolean,
        isLowValueSuspiciousSampleDomain: (String) -> Boolean
    ): String? {
        val candidates = extractSuspiciousSampleDomainCandidates(
            raw = raw,
            suspiciousSampleDecodeMaxLength = suspiciousSampleDecodeMaxLength,
            suspiciousSampleMaxDecodeRounds = suspiciousSampleMaxDecodeRounds,
            sanitizeDomain = sanitizeDomain,
            normalizeDomainToken = normalizeDomainToken,
            domainExtractRegex = domainExtractRegex,
            htmlNumericEntityRegex = htmlNumericEntityRegex,
            unicodeEscapeRegex = unicodeEscapeRegex
        )
        if (candidates.isEmpty()) return null
        return candidates.maxWithOrNull(
            compareBy<String> {
                suspiciousSampleDomainPriority(
                    domain = it,
                    looksLikeWhitelistedRootAdSubdomain = looksLikeWhitelistedRootAdSubdomain,
                    looksLikeAdSdkInfraDomain = looksLikeAdSdkInfraDomain,
                    looksLikePushRecommendationAdDomain = looksLikePushRecommendationAdDomain,
                    hasAggressiveNovelAdSignal = hasAggressiveNovelAdSignal,
                    looksLikeAdDomain = looksLikeAdDomain,
                    isLowValueSuspiciousSampleDomain = isLowValueSuspiciousSampleDomain
                )
            }.thenByDescending { it.length }
        )
    }

    private fun extractSuspiciousSampleDomainCandidates(
        raw: String,
        suspiciousSampleDecodeMaxLength: Int,
        suspiciousSampleMaxDecodeRounds: Int,
        sanitizeDomain: (String) -> String?,
        normalizeDomainToken: (String) -> String,
        domainExtractRegex: Regex,
        htmlNumericEntityRegex: Regex,
        unicodeEscapeRegex: Regex
    ): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        val normalizedInputs = buildSuspiciousSampleExtractionInputs(
            raw = trimmed,
            suspiciousSampleDecodeMaxLength = suspiciousSampleDecodeMaxLength,
            suspiciousSampleMaxDecodeRounds = suspiciousSampleMaxDecodeRounds,
            htmlNumericEntityRegex = htmlNumericEntityRegex,
            unicodeEscapeRegex = unicodeEscapeRegex
        )
        val candidates = linkedSetOf<String>()
        normalizedInputs.forEach { normalized ->
            sanitizeDomain(normalized)?.let { candidates += it }
            extractDomainLikeTokens(normalized, normalizeDomainToken, domainExtractRegex).forEach { token ->
                sanitizeDomain(token)?.let { candidates += it }
            }
        }
        return candidates.toList()
    }

    private fun buildSuspiciousSampleExtractionInputs(
        raw: String,
        suspiciousSampleDecodeMaxLength: Int,
        suspiciousSampleMaxDecodeRounds: Int,
        htmlNumericEntityRegex: Regex,
        unicodeEscapeRegex: Regex
    ): List<String> {
        val normalized = normalizeSuspiciousSampleInput(raw, suspiciousSampleDecodeMaxLength)
        val inputs = linkedSetOf<String>()
        if (normalized.isBlank()) return emptyList()
        inputs += normalized
        collectDecodedSuspiciousSampleInputs(
            raw = normalized,
            collector = inputs,
            depth = 0,
            suspiciousSampleDecodeMaxLength = suspiciousSampleDecodeMaxLength,
            suspiciousSampleMaxDecodeRounds = suspiciousSampleMaxDecodeRounds,
            htmlNumericEntityRegex = htmlNumericEntityRegex,
            unicodeEscapeRegex = unicodeEscapeRegex
        )
        return inputs.toList()
    }

    private fun collectDecodedSuspiciousSampleInputs(
        raw: String,
        collector: LinkedHashSet<String>,
        depth: Int,
        suspiciousSampleDecodeMaxLength: Int,
        suspiciousSampleMaxDecodeRounds: Int,
        htmlNumericEntityRegex: Regex,
        unicodeEscapeRegex: Regex
    ) {
        if (depth >= suspiciousSampleMaxDecodeRounds) return
        decodeSuspiciousSampleVariants(raw, suspiciousSampleDecodeMaxLength, htmlNumericEntityRegex, unicodeEscapeRegex).forEach { decoded ->
            val normalized = normalizeSuspiciousSampleInput(decoded, suspiciousSampleDecodeMaxLength)
            if (normalized.isBlank()) return@forEach
            if (!collector.add(normalized)) return@forEach
            collectDecodedSuspiciousSampleInputs(
                raw = normalized,
                collector = collector,
                depth = depth + 1,
                suspiciousSampleDecodeMaxLength = suspiciousSampleDecodeMaxLength,
                suspiciousSampleMaxDecodeRounds = suspiciousSampleMaxDecodeRounds,
                htmlNumericEntityRegex = htmlNumericEntityRegex,
                unicodeEscapeRegex = unicodeEscapeRegex
            )
        }
    }

    private fun decodeSuspiciousSampleVariants(
        raw: String,
        suspiciousSampleDecodeMaxLength: Int,
        htmlNumericEntityRegex: Regex,
        unicodeEscapeRegex: Regex
    ): List<String> {
        if (raw.isBlank() || raw.length > suspiciousSampleDecodeMaxLength) return emptyList()
        val decoded = linkedSetOf<String>()
        decodeHtmlEntityValue(raw, suspiciousSampleDecodeMaxLength, htmlNumericEntityRegex)?.let { decoded += it }
        decodeJsonEscapedValue(raw, suspiciousSampleDecodeMaxLength, unicodeEscapeRegex)?.let { decoded += it }
        decodePercentEncodedValue(raw)?.let { decoded += it }
        extractEncodedValueCandidates(raw, suspiciousSampleDecodeMaxLength).forEach { token ->
            decodeHtmlEntityValue(token, suspiciousSampleDecodeMaxLength, htmlNumericEntityRegex)?.let { decoded += it }
            decodeJsonEscapedValue(token, suspiciousSampleDecodeMaxLength, unicodeEscapeRegex)?.let { decoded += it }
            decodePercentEncodedValue(token)?.let { decoded += it }
            decodeBase64LikeValue(token, suspiciousSampleDecodeMaxLength)?.let { decoded += it }
        }
        decodeBase64LikeValue(raw, suspiciousSampleDecodeMaxLength)?.let { decoded += it }
        return decoded.toList()
    }

    private fun extractEncodedValueCandidates(raw: String, suspiciousSampleDecodeMaxLength: Int): List<String> {
        if (raw.isBlank()) return emptyList()
        val candidates = linkedSetOf<String>()
        val markerRegex = Regex(
            """(?i)(?:url|uri|target|dest|destination|redirect|redirect_uri|redirect_url|redirecturl|location|origin|referer|referrer|landing|landing_url|landingurl|final|final_url|finalurl|jump|jump_url|jumpurl|to|u|r)\s*[:=]\s*([^\s,;\]\[\)\(\"']+)"""
        )
        markerRegex.findAll(raw).forEach { match ->
            match.groups[1]?.value?.trim()?.takeIf { it.isNotBlank() }?.let { candidates += it }
        }
        raw.split('&', '?').asSequence()
            .map { it.substringAfter('=', missingDelimiterValue = "").trim() }
            .filter { it.length in 8..suspiciousSampleDecodeMaxLength }
            .filter { it.any(Char::isLetter) }
            .forEach { candidates += it }
        return candidates.toList()
    }

    private fun decodePercentEncodedValue(raw: String): String? {
        if (!raw.contains('%') && !raw.contains('+')) return null
        return runCatching { java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8.name()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != raw }
    }

    private fun decodeHtmlEntityValue(raw: String, suspiciousSampleDecodeMaxLength: Int, htmlNumericEntityRegex: Regex): String? {
        if (!raw.contains('&')) return null
        if (raw.length > suspiciousSampleDecodeMaxLength) return null
        val numericDecoded = htmlNumericEntityRegex.replace(raw) { match ->
            val token = match.groupValues.getOrNull(1).orEmpty()
            val parsedCodePoint = if (token.startsWith("x", ignoreCase = true)) {
                token.drop(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            val codePoint = parsedCodePoint ?: return@replace match.value
            if (codePoint !in Character.MIN_CODE_POINT..Character.MAX_CODE_POINT) return@replace match.value
            runCatching { String(Character.toChars(codePoint)) }.getOrElse { match.value }
        }
        val decoded = numericDecoded
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#34;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&colon;", ":", ignoreCase = true)
            .replace("&sol;", "/", ignoreCase = true)
            .replace("&equals;", "=", ignoreCase = true)
        return decoded.takeIf {
            it.isNotBlank() && it != raw &&
                (it.contains('.') || it.contains("://") || it.contains("/") || it.contains("host=") || it.contains("url="))
        }
    }

    private fun decodeJsonEscapedValue(raw: String, suspiciousSampleDecodeMaxLength: Int, unicodeEscapeRegex: Regex): String? {
        if (!raw.contains('\\')) return null
        if (raw.length > suspiciousSampleDecodeMaxLength) return null
        val unicodeDecoded = unicodeEscapeRegex.replace(raw) { match ->
            val code = match.groupValues.getOrNull(1)?.toIntOrNull(16) ?: return@replace match.value
            code.toChar().toString()
        }
        val unescaped = unicodeDecoded
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u003F", "?", ignoreCase = true)
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
        return unescaped.takeIf {
            it.isNotBlank() && it != raw &&
                (it.contains('.') || it.contains("://") || it.contains("/") || it.contains("host=") || it.contains("url="))
        }
    }

    private fun decodeBase64LikeValue(raw: String, suspiciousSampleDecodeMaxLength: Int): String? {
        val token = raw.trim()
            .removePrefix("base64,")
            .removePrefix("Base64,")
            .removeSurrounding("\"")
            .removeSurrounding("'")
        if (token.length !in 12..suspiciousSampleDecodeMaxLength) return null
        if (!token.any { it == '.' || it == '/' || it == ':' }) {
            val compact = token.filterNot(Char::isWhitespace)
            if (!compact.matches(Regex("""[A-Za-z0-9_\-+/=]+"""))) return null
            val normalized = compact.replace('-', '+').replace('_', '/')
            val padded = normalized.padEnd(((normalized.length + 3) / 4) * 4, '=')
            return runCatching {
                String(java.util.Base64.getDecoder().decode(padded), java.nio.charset.StandardCharsets.UTF_8)
            }.getOrNull()?.takeIf { decoded ->
                decoded.isNotBlank() &&
                    decoded.length <= suspiciousSampleDecodeMaxLength &&
                    (decoded.contains('.') || decoded.contains("://") || decoded.contains("/") || decoded.contains("host=") || decoded.contains("url="))
            }
        }
        return null
    }

    private fun normalizeSuspiciousSampleInput(raw: String, suspiciousSampleDecodeMaxLength: Int): String {
        return raw.trim()
            .take(suspiciousSampleDecodeMaxLength)
            .replace('，', ',')
            .replace('；', ';')
            .replace('：', ':')
            .replace('（', '(')
            .replace('）', ')')
    }

    private fun extractDomainLikeTokens(raw: String, normalizeDomainToken: (String) -> String, domainExtractRegex: Regex): List<String> {
        if (raw.isBlank()) return emptyList()
        val candidates = linkedSetOf<String>()
        val markerRegex = Regex(
            """(?i)(?:host|domain|hostname|referer|referrer|origin|url|uri|location|redirect|target|dest|destination|final(?:[_-]?url)?|landing(?:[_-]?url)?)\s*[:=]\s*([^\s,;\]\[\)\(\"']+)"""
        )
        markerRegex.findAll(raw).forEach { match ->
            match.groups[1]?.value?.trim()?.takeIf { it.isNotBlank() }?.let { candidates += it }
        }
        raw.split(Regex("""[\s,;\|<>{}\[\]()\"']+"""))
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { token ->
                normalizeDomainToken(token).takeIf { it.contains('.') }?.let { candidates += it }
            }
        domainExtractRegex.findAll(raw).forEach { match ->
            match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { candidates += it }
        }
        return candidates.toList()
    }

    private fun suspiciousSampleDomainPriority(
        domain: String,
        looksLikeWhitelistedRootAdSubdomain: (String) -> Boolean,
        looksLikeAdSdkInfraDomain: (String) -> Boolean,
        looksLikePushRecommendationAdDomain: (String) -> Boolean,
        hasAggressiveNovelAdSignal: (String) -> Boolean,
        looksLikeAdDomain: (String) -> Boolean,
        isLowValueSuspiciousSampleDomain: (String) -> Boolean
    ): Int {
        var score = 0
        if (looksLikeWhitelistedRootAdSubdomain(domain)) score += 8
        if (looksLikeAdSdkInfraDomain(domain)) score += 6
        if (looksLikePushRecommendationAdDomain(domain)) score += 5
        if (hasAggressiveNovelAdSignal(domain)) score += 4
        if (looksLikeAdDomain(domain)) score += 3
        if (isLowValueSuspiciousSampleDomain(domain)) score -= 6
        return score
    }

    fun normalizeSampleAppName(appName: String?, lineBreakRegex: Regex): String {
        return appName
            ?.replace(lineBreakRegex, " ")
            ?.trim()
            ?.take(80)
            .orEmpty()
    }

    fun looksLikeSuspiciousPath(path: String): Boolean {
        val lowerPath = path.lowercase()
        val suspiciousKeywords = listOf("/ad", "/ads", "/advert", "/banner", "/splash", "/promo", "/tracker")
        return suspiciousKeywords.any { lowerPath.contains(it) }
    }

    fun suspiciousDomainConfidenceScore(
        domain: String,
        vendor: String,
        novelHits: Int,
        count: Int,
        appName: String? = null,
        dnsHits: Int = 0,
        aliasHits: Int = 0,
        tlsSniHits: Int = 0,
        httpHits: Int = 0,
        pathHits: Int = 0,
        redirectHits: Int = 0,
        appSignalHits: Int = 0,
        vendorSignalHits: Int = 0,
        confidenceBoost: Int = 0,
        refererDomain: String? = null,
        sanitizeDomain: (String) -> String?,
        normalizeVendorName: (String) -> String,
        isWhitelistedDomain: (String) -> Boolean,
        isProtectedNovelAppDomain: (String) -> Boolean,
        isNovelContentDomain: (String) -> Boolean,
        isLowValueSuspiciousSampleDomain: (String) -> Boolean,
        isBypassProtectionDomain: (String) -> Boolean,
        looksLikeAdDomain: (String) -> Boolean,
        looksLikePushRecommendationAdDomain: (String) -> Boolean,
        hasAggressiveNovelAdSignal: (String) -> Boolean,
        isNovelAppHint: (String?) -> Boolean,
        isAggressiveAdAppHint: (String?) -> Boolean,
        looksLikeAdSdkInfraDomain: (String, String) -> Boolean,
        defaultVendor: String,
        genericAdVendor: String,
        highConfidenceAdSdkVendors: Set<String>
    ): Int {
        val normalized = sanitizeDomain(domain) ?: return 0
        var score = 0
        val normalizedVendor = normalizeVendorName(vendor)
        if (isWhitelistedDomain(normalized) || isProtectedNovelAppDomain(normalized) || isNovelContentDomain(normalized)) {
            return 0
        }
        if (isLowValueSuspiciousSampleDomain(normalized)) return 0
        if (isBypassProtectionDomain(normalized)) score += 5
        if (looksLikeAdDomain(normalized)) score += 4
        if (looksLikePushRecommendationAdDomain(normalized)) score += 3
        if (hasAggressiveNovelAdSignal(normalized)) score += 3
        if (normalizedVendor == genericAdVendor) score += 3
        if (normalizedVendor in highConfidenceAdSdkVendors) score += 2
        if (novelHits >= 3) score += 3 else if (novelHits >= 1) score += 2
        if (count >= 8) score += 2 else if (count >= 3) score += 1
        if (dnsHits >= 5) score += 2 else if (dnsHits >= 2) score += 1
        if (aliasHits >= 2) score += 2 else if (aliasHits >= 1) score += 1
        if (tlsSniHits >= 2) score += 2 else if (tlsSniHits >= 1) score += 1
        if (httpHits >= 2) score += 2 else if (httpHits >= 1) score += 1
        if (pathHits >= 2) score += 2 else if (pathHits >= 1) score += 1
        if (redirectHits >= 1) score += 2
        if (appSignalHits >= 2) score += 1
        if (vendorSignalHits >= 2) score += 1
        if (isNovelAppHint(appName)) score += 1
        if (isAggressiveAdAppHint(appName)) score += 1
        val isCommunityApp = appName?.let { 
            it.contains("coolapk", ignoreCase = true) || 
            it.contains("酷安", ignoreCase = true) ||
            it.contains("贴吧", ignoreCase = true) ||
            it.contains("社区", ignoreCase = true)
        } == true
        if (isCommunityApp && (httpHits >= 1 || pathHits >= 1)) score += 2
        if (!refererDomain.isNullOrBlank()) score += 1
        if (httpHits == 0 && pathHits == 0 && redirectHits == 0 && dnsHits <= 1 && aliasHits == 0 && tlsSniHits == 0) {
            score -= 2
        }
        if (normalizedVendor == defaultVendor && !looksLikeAdSdkInfraDomain(normalized, normalizedVendor) && pathHits == 0 && redirectHits == 0) {
            score -= 1
        }
        score += confidenceBoost.coerceIn(0, 4)
        return score.coerceAtLeast(0)
    }

    fun isHighConfidenceSuspiciousDomain(
        domain: String,
        vendor: String,
        novelHits: Int,
        count: Int,
        appName: String? = null,
        dnsHits: Int = 0,
        aliasHits: Int = 0,
        tlsSniHits: Int = 0,
        httpHits: Int = 0,
        pathHits: Int = 0,
        redirectHits: Int = 0,
        appSignalHits: Int = 0,
        vendorSignalHits: Int = 0,
        confidenceBoost: Int = 0,
        refererDomain: String? = null,
        suspiciousDomainConfidenceScore: (
            String, String, Int, Int, String?, Int, Int, Int, Int, Int, Int, Int, Int, Int, String?
        ) -> Int
    ): Boolean {
        return suspiciousDomainConfidenceScore(
            domain,
            vendor,
            novelHits,
            count,
            appName,
            dnsHits,
            aliasHits,
            tlsSniHits,
            httpHits,
            pathHits,
            redirectHits,
            appSignalHits,
            vendorSignalHits,
            confidenceBoost,
            refererDomain
        ) >= 6
    }
}
