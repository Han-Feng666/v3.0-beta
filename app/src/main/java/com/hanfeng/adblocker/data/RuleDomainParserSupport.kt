package com.HanFeng.data

object RuleDomainParserSupport {
    fun parseDomainAnchorPattern(
        pattern: String,
        sanitizeDomain: (String) -> String?,
        parseWildcardDomainAnchorPattern: (String) -> String?
    ): String? {
        val trimmed = pattern.trim()
        val slashIndex = trimmed.indexOf('/')
        val caretIndex = trimmed.indexOf('^')
        val boundaryIndex = sequenceOf(slashIndex, caretIndex)
            .filter { it >= 0 }
            .minOrNull()
            ?: trimmed.length
        val domainToken = trimmed.substring(0, boundaryIndex)
        val suffix = trimmed.substring(boundaryIndex)
        if (domainToken.isBlank()) return null
        return sanitizeDomain(normalizeDomainToken(domainToken))
            ?: parseWildcardDomainAnchorPattern(domainToken)
            ?: if (slashIndex > 0) {
                sanitizeDomain(normalizeDomainToken(trimmed.substring(0, slashIndex)))
            } else {
                null
            }
    }

    fun parseExactAnchorPattern(
        pattern: String,
        sanitizeDomain: (String) -> String?,
        parseWildcardDomainAnchorPattern: (String) -> String?
    ): String? {
        val trimmed = pattern.trim()
        val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
        val slashIndex = withoutScheme.indexOf('/')
        val questionIndex = withoutScheme.indexOf('?')
        val boundaryIndex = sequenceOf(slashIndex, questionIndex)
            .filter { it >= 0 }
            .minOrNull()
            ?: withoutScheme.length
        val domainToken = withoutScheme.substring(0, boundaryIndex)
        val suffix = withoutScheme.substring(boundaryIndex)
        if (domainToken.isBlank()) return null
        if (!isSafeDomainPatternSuffix(suffix)) return null
        return sanitizeDomain(normalizeDomainToken(domainToken))
            ?: parseWildcardDomainAnchorPattern(domainToken)
    }

    fun parseWildcardDomainAnchorPattern(pattern: String, sanitizeDomain: (String) -> String?): String? {
        val normalized = normalizeDomainToken(pattern)
        if (!normalized.contains('*')) return null
        val labels = normalized.split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return null
        val stableLabels = labels.filterNot { it.contains('*') }
        return when {
            stableLabels.size >= 2 -> sanitizeDomain(stableLabels.takeLast(2).joinToString("."))
            stableLabels.size == 1 -> {
                val wildcardIndex = labels.indexOfFirst { it.contains('*') }
                if (wildcardIndex >= 0) {
                    val possibleDomain = if (wildcardIndex < labels.lastIndex) {
                        labels.drop(wildcardIndex + 1).takeLast(2).joinToString(".")
                    } else {
                        stableLabels.joinToString(".")
                    }
                    val fallback = stableLabels.firstOrNull()
                    if (fallback != null) sanitizeDomain(possibleDomain.takeIf { it.isNotBlank() } ?: fallback) else null
                } else {
                    null
                }
            }
            else -> sanitizeDomain(labels.takeLast(2).joinToString("."))
        }
    }

    fun isSafeDomainPatternSuffix(suffix: String): Boolean {
        if (suffix.isBlank()) return true
        return suffix.all { it == '^' || it == '|' }
    }

    fun parseStructuredDomainToken(raw: String, sanitizeDomain: (String) -> String?): String? {
        return sanitizeDomain(
            normalizeDomainToken(
                raw.trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .removeSurrounding("[", "]")
                    .removePrefix("+.")
                    .removePrefix(".")
            )
        )
    }

    fun normalizeDomainToken(raw: String): String {
        var current = raw.trim()
        current = current.removeSurrounding("\"").removeSurrounding("'").removeSurrounding("[", "]")
        current = current.removePrefix("*://").removePrefix("://")
        current = current.substringAfter("://", missingDelimiterValue = current)
        return current
            .removePrefix("*.")
            .removePrefix(".")
            .removePrefix("[")
            .removeSuffix("]")
            .trim('*')
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('^')
            .substringBefore('|')
            .substringBefore(':')
            .substringBefore('#')
            .substringBefore('@')
            .trim()
    }

    fun isHostsIpToken(token: String): Boolean {
        return token == "0.0.0.0" || token == "127.0.0.1" || token == "::" || token == "::1"
    }

    fun looksLikeIpAddress(token: String, ipV4Regex: Regex): Boolean {
        val value = token.trim().trim('[').trim(']')
        if (value.isBlank()) return false
        if (value.contains(':')) return true
        return value.matches(ipV4Regex)
    }

    fun parseHostsOrPlainDomains(
        patternPart: String,
        whitespaceRegex: Regex,
        sanitizeDomain: (String) -> String?,
        ipV4Regex: Regex
    ): List<String> {
        val cleaned = patternPart.substringBefore('#').trim()
        val tokens = cleaned.split(whitespaceRegex).filter { it.isNotBlank() }
        if (tokens.size >= 2 && (isHostsIpToken(tokens[0]) || looksLikeIpAddress(tokens[0], ipV4Regex))) {
            return tokens.drop(1)
                .mapNotNull { token ->
                    val normalized = normalizeDomainToken(token)
                    when {
                        normalized.equals("localhost", ignoreCase = true) -> null
                        normalized.equals("hostname", ignoreCase = true) -> null
                        looksLikeIpAddress(normalized, ipV4Regex) -> null
                        else -> sanitizeDomain(normalized)
                    }
                }
                .distinct()
        }
        return listOfNotNull(sanitizeDomain(normalizeDomainToken(cleaned)))
    }

    fun parseDnsmasqDomains(
        patternPart: String,
        matchedPrefix: String,
        sanitizeDomain: (String) -> String?,
        ipV4Regex: Regex
    ): List<String> {
        val body = patternPart.substring(matchedPrefix.length)
        return body.split('/')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !looksLikeIpAddress(it, ipV4Regex) }
            .mapNotNull { sanitizeDomain(normalizeDomainToken(it)) }
            .distinct()
            .toList()
    }

    fun findAdLikeStructuredToken(
        values: List<String>,
        parseStructuredDomainToken: (String) -> String?,
        looksLikeAdDomain: (String) -> Boolean,
        looksLikeBypassProtectionDomain: (String) -> Boolean
    ): String? {
        return values.asSequence()
            .mapNotNull(parseStructuredDomainToken)
            .firstOrNull { looksLikeAdDomain(it) || looksLikeBypassProtectionDomain(it) }
    }

    fun findActionableStructuredToken(
        values: List<String>,
        parseStructuredDomainToken: (String) -> String?,
        looksLikeAdDomain: (String) -> Boolean,
        looksLikeBypassProtectionDomain: (String) -> Boolean
    ): String? {
        return findAdLikeStructuredToken(values, parseStructuredDomainToken, looksLikeAdDomain, looksLikeBypassProtectionDomain)
            ?: values.asSequence().mapNotNull(parseStructuredDomainToken).firstOrNull()
    }
}
