package com.HanFeng.data

object RuleSemanticParserSupport {
    data class CompositeEnvelope(
        val operator: String,
        val parts: List<String>
    )

    fun parseCompositeEnvelope(line: String): CompositeEnvelope? {
        val trimmed = line.trim()
        val matchedPrefix = listOf("AND,", "AND:", "AND(", "OR,", "OR:", "OR(", "NOT,", "NOT:", "NOT(")
            .firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
            ?: return null
        val operator = matchedPrefix.trimEnd(',', ':', '(').uppercase()
        val body = trimmed.substring(matchedPrefix.length)
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .removeSuffix(")")
        if (body.isBlank()) return CompositeEnvelope(operator, emptyList())
        return CompositeEnvelope(operator, splitCompositeRuleParts(body))
    }

    fun splitCompositeRuleParts(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var bracketDepth = 0
        var quote: Char? = null
        body.forEach { ch ->
            when {
                quote != null -> {
                    current.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    current.append(ch)
                }
                ch == '[' || ch == '(' || ch == '{' -> {
                    bracketDepth += 1
                    current.append(ch)
                }
                ch == ']' || ch == ')' || ch == '}' -> {
                    if (bracketDepth > 0) bracketDepth -= 1
                    current.append(ch)
                }
                ch == ',' && bracketDepth == 0 -> {
                    val item = current.toString().trim()
                    if (item.isNotBlank()) parts += item
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotBlank()) parts += tail
        return parts
    }

    fun extractInlinePayloadItems(payloadBody: String): List<String> {
        val quotedItems = Regex("\"([^\"]+)\"|'([^']+)'")
            .findAll(payloadBody)
            .mapNotNull { match -> match.groups[1]?.value ?: match.groups[2]?.value }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (quotedItems.isNotEmpty()) return quotedItems
        val yamlStyleItems = payloadBody.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-") }
            .map { it.removePrefix("-").trim().removeSurrounding("\"").removeSurrounding("'") }
            .filter { it.isNotBlank() }
            .toList()
        if (yamlStyleItems.isNotEmpty()) return yamlStyleItems
        return payloadBody.removePrefix("[").removeSuffix("]")
            .split(',')
            .map {
                it.trim()
                    .removePrefix("-")
                    .trim()
                    .removeSurrounding("(", ")")
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
            }
            .filter { it.isNotBlank() }
    }

    fun normalizeStructuredRuleType(raw: String): String {
        return raw.trim().lowercase().replace('_', '-')
    }

    fun unwrapCompositeRule(value: String): String {
        val trimmed = value.trim()
        val compositePrefixes = listOf("AND,", "OR,", "NOT,", "AND:", "OR:", "NOT:", "AND(", "OR(", "NOT(")
        val matchedPrefix = compositePrefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return trimmed
        return trimmed.substringAfter(matchedPrefix)
            .trim()
            .trimStart('(', '[')
            .trimEnd(')', ']')
            .trim()
    }

    fun normalizeStructuredRuleValue(value: String): String {
        return value.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .substringBefore(" //")
            .substringBefore(" #")
            .substringBefore(" ;")
            .replace(Regex("""\s*,\s*REJECT(?:-[A-Z0-9_-]+)?\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*DIRECT\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*PROXY\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*MATCH\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*FINAL\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*RULE-SET\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*RULE-PROVIDER\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*DOMAIN-SET\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*no-resolve\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*:\s*"""), ":")
            .trim()
    }

    fun parsePrefixedDomainRule(patternPart: String, parseStructuredDomainToken: (String) -> String?): String? {
        return parseDelimitedPrefixedDomainRule(patternPart, ':', parseStructuredDomainToken)
            ?: parseDelimitedPrefixedDomainRule(patternPart, '=', parseStructuredDomainToken)
    }

    fun parseEmbeddedRuleCarrierDomain(
        patternPart: String,
        findActionableStructuredToken: (List<String>) -> String?
    ): String? {
        val normalized = patternPart.trim()
        val matchedPrefix = listOf(
            "RULE-SET",
            "RULESET",
            "RULE-PROVIDER",
            "RULE_PROVIDER",
            "ruleset=",
            "ruleset:",
            "ruleset,",
            "rule-provider=",
            "rule-provider:",
            "rule-provider,",
            "rule_provider=",
            "rule_provider:",
            "rule_provider,"
        ).firstOrNull { normalized.startsWith(it, ignoreCase = true) }
            ?: return null
        val body = if (matchedPrefix.contains('=') || matchedPrefix.contains(':') || matchedPrefix.endsWith(',')) {
            normalized.substring(matchedPrefix.length)
        } else {
            normalized.substringAfter(',', missingDelimiterValue = "")
        }.trim()
        if (body.isBlank()) return null
        val parts = body.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return findActionableStructuredToken(parts)
    }

    private fun parseDelimitedPrefixedDomainRule(
        patternPart: String,
        delimiter: Char,
        parseStructuredDomainToken: (String) -> String?
    ): String? {
        val exactPrefixes = listOf(
            "full", "full-domain", "full_domain", "domain", "domain-set", "domain_set",
            "domain-full-set", "domain_full_set", "domain-full", "domain_full", "domain-exact", "domain_exact",
            "host", "hostname", "host-full", "host_full", "host-set", "host_set", "hostname-set", "hostname_set",
            "domain-suffix", "domain_suffix", "domain-suffixes", "domain-suffix-set", "domain_suffix_set",
            "host-suffix", "host_suffix", "host-suffix-set", "host_suffix_set", "hostname-suffix", "hostname_suffix",
            "hostname-suffix-set", "hostname_suffix_set", "suffix", "host-exact", "host_exact", "hostname-exact",
            "hostname_exact", "hostname-full"
        )
        val wildcardPrefixes = listOf(
            "domain-wildcard", "domain_wildcard", "domain-wildcard-set", "domain_wildcard_set",
            "host-wildcard", "host_wildcard", "host-wildcard-set", "host_wildcard_set",
            "hostname-wildcard", "hostname_wildcard", "hostname-wildcard-set", "hostname_wildcard_set"
        )
        val normalized = patternPart.trim()
        val exactPrefix = exactPrefixes.firstOrNull { normalized.startsWith("$it$delimiter", ignoreCase = true) }
        if (exactPrefix != null) {
            return parseStructuredDomainToken(normalized.substring(exactPrefix.length + 1))
        }
        val wildcardPrefix = wildcardPrefixes.firstOrNull { normalized.startsWith("$it$delimiter", ignoreCase = true) }
        if (wildcardPrefix != null) {
            return parseStructuredDomainToken(normalized.substring(wildcardPrefix.length + 1).removePrefix("*."))
        }
        return null
    }
}
