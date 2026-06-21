package com.HanFeng.data

import com.HanFeng.core.network.RegexCache

object RuleTextNormalizer {
    fun normalizeMessyRuleLine(rawLine: String): String {
        var line = rawLine.trim()
        if (line.isBlank()) return ""
        val appModifierReplacement = Regex.escapeReplacement("\$app=")
        val caretAppModifierReplacement = Regex.escapeReplacement("^\$app=")
        val allAppModifierReplacement = Regex.escapeReplacement("\$all,app=")
        line = line
            .replace(RegexCache.get("""@@[lI1|]+f""", RegexOption.IGNORE_CASE), "@@||")
            .replace(RegexCache.get("""(^|[^@])[lI1|]{2}f""", RegexOption.IGNORE_CASE), "$1||")
            .replace(RegexCache.get("""\$\s*[sS5]\s*app\s*=""", RegexOption.IGNORE_CASE), appModifierReplacement)
            .replace(RegexCache.get("""\^\s*[sS5$]\s*app\s*=""", RegexOption.IGNORE_CASE), caretAppModifierReplacement)
            .replace(RegexCache.get("""\$\s*all\s*,\s*app\s*=""", RegexOption.IGNORE_CASE), allAppModifierReplacement)
            .replace(RegexCache.get("""app\s*=\s*"""), "app=")
            .replace(RegexCache.get("""domain\s*=\s*""", RegexOption.IGNORE_CASE), "domain=")
            .replace(RegexCache.get("""denyallow\s*=\s*""", RegexOption.IGNORE_CASE), "denyallow=")
            .replace(RegexCache.get("""(\b[a-z]{2,})\s+\.\s*([a-z0-9_])""", RegexOption.IGNORE_CASE), "$1.$2")
            .replace(RegexCache.get("""\.\s+([a-z0-9_])""", RegexOption.IGNORE_CASE), ".$1")
            .replace(RegexCache.get("""([:/,=\$\^|@])\s+([a-z0-9_*.@|-])""", RegexOption.IGNORE_CASE), "$1$2")
            .replace(RegexCache.get("""([a-z0-9_*.])\s+([\^$,|])""", RegexOption.IGNORE_CASE), "$1$2")
            .replace(RegexCache.get("""\|\s+\|"""), "||")
            .replace(RegexCache.get("""\s*,\s*REJECT\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s*,\s*REJECT(?:-[A-Z0-9_-]+)?\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s*,\s*DIRECT\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s*,\s*PROXY\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s*,\s*MATCH\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s*,\s*FINAL\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s*,\s*RULE-SET\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s*,\s*RULE-PROVIDER\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s*,\s*DOMAIN-SET\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s*,\s*no-resolve\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(RegexCache.get("""\s+\^"""), "^")
            .replace(RegexCache.get("""\^\s+"""), "^")
            .replace(RegexCache.get("""\s+"""), " ")
            .trim()
        return line
    }
}
