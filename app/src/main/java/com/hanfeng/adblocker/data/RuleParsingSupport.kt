package com.HanFeng.data

object RuleParsingSupport {
    data class LineContext(
        val vendorHints: Set<String> = emptySet()
    )

    fun expandIndentedYamlPayloadBlocks(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val expanded = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            val isPayloadHeader = trimmed.equals("payload:", ignoreCase = true) ||
                trimmed.equals("payload=", ignoreCase = true) ||
                trimmed.equals("rules:", ignoreCase = true) ||
                trimmed.equals("rules=", ignoreCase = true)
            if (!isPayloadHeader) {
                expanded += line
                index += 1
                continue
            }
            var consumed = false
            var nextIndex = index + 1
            while (nextIndex < lines.size) {
                val nextLine = lines[nextIndex]
                val nextTrimmed = nextLine.trim()
                if (nextTrimmed.isBlank()) {
                    nextIndex += 1
                    continue
                }
                if (nextTrimmed.startsWith("#") || nextTrimmed.startsWith("!")) {
                    nextIndex += 1
                    continue
                }
                if (!nextTrimmed.startsWith("- ") && !nextTrimmed.startsWith("* ")) break
                expanded += "payload: ${nextTrimmed.removePrefix("- ").removePrefix("* ").trim()}"
                consumed = true
                nextIndex += 1
            }
            if (!consumed) {
                expanded += line
            }
            index = nextIndex
        }
        return expanded
    }

    fun parseRuleLineContext(line: String): LineContext {
        val body = line.substringAfter('=', "").trim()
        if (body.isBlank()) return LineContext()
        val hintPart = body.substringBefore(';').substringBefore('；').trim()
        val hints = hintPart
            .split(',', '|', '/', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && (it.contains('.') || it.length >= 3) }
            .toSet()
        return if (hints.isEmpty()) LineContext() else LineContext(vendorHints = hints)
    }

    fun stripInlineRuleComment(rawLine: String): String {
        val commentMarkers = listOf(" #", " !", " ;", " //")
        val cutIndex = commentMarkers
            .map { marker -> rawLine.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
            ?: rawLine.length
        return rawLine.substring(0, cutIndex).trim()
    }

    fun expandPossibleRuleFragments(rawLine: String): List<String> {
        val normalized = RuleTextNormalizer.normalizeMessyRuleLine(rawLine)
        if (normalized.isBlank()) return emptyList()
        if (normalized.startsWith("#pkg=", ignoreCase = true)) return listOf(normalized)
        val matches = Regex("""(?:@@)?\|\|.*?(?=(?:\s+(?:@@)?\|\|)|$)""")
            .findAll(normalized)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()
        return if (matches.isNotEmpty()) matches else listOf(normalized)
    }

    fun unwrapRuleWrapper(value: String): String {
        val trimmed = value.trim()
        val prefixes = listOf("rule:", "rule=", "value:", "value=")
        val prefix = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return trimmed
        return trimmed.substring(prefix.length).trim()
    }

    fun stripYamlListPrefix(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("- ") -> trimmed.substring(2).trim().removeSurrounding("\"").removeSurrounding("'")
            trimmed.startsWith("* ") -> trimmed.substring(2).trim().removeSurrounding("\"").removeSurrounding("'")
            trimmed == "-" || trimmed == "*" -> ""
            else -> trimmed.removeSurrounding("\"").removeSurrounding("'")
        }
    }
}
