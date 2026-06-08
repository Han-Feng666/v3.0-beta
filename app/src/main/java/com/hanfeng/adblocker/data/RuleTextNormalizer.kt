package com.HanFeng.data

object RuleTextNormalizer {
    fun normalizeMessyRuleLine(rawLine: String): String {
        var line = rawLine.trim()
        if (line.isBlank()) return ""
        val appModifierReplacement = Regex.escapeReplacement("\$app=")
        val caretAppModifierReplacement = Regex.escapeReplacement("^\$app=")
        val allAppModifierReplacement = Regex.escapeReplacement("\$all,app=")
        line = line
            .replace(Regex("""@@[lI1|]+f""", RegexOption.IGNORE_CASE), "@@||")
            .replace(Regex("""(^|[^@])[lI1|]{2}f""", RegexOption.IGNORE_CASE), "$1||")
            .replace(Regex("""\$\s*[sS5]\s*app\s*=""", RegexOption.IGNORE_CASE), appModifierReplacement)
            .replace(Regex("""\^\s*[sS5$]\s*app\s*=""", RegexOption.IGNORE_CASE), caretAppModifierReplacement)
            .replace(Regex("""\$\s*all\s*,\s*app\s*=""", RegexOption.IGNORE_CASE), allAppModifierReplacement)
            .replace(Regex("""app\s*=\s*"""), "app=")
            .replace(Regex("""domain\s*=\s*""", RegexOption.IGNORE_CASE), "domain=")
            .replace(Regex("""denyallow\s*=\s*""", RegexOption.IGNORE_CASE), "denyallow=")
            .replace(Regex("""(\b[a-z]{2,})\s+\.\s*([a-z0-9_])""", RegexOption.IGNORE_CASE), "$1.$2")
            .replace(Regex("""\.\s+([a-z0-9_])""", RegexOption.IGNORE_CASE), ".$1")
            .replace(Regex("""([:/,=\$\^|@])\s+([a-z0-9_*.@|-])""", RegexOption.IGNORE_CASE), "$1$2")
            .replace(Regex("""([a-z0-9_*.])\s+([\^$,|])""", RegexOption.IGNORE_CASE), "$1$2")
            .replace(Regex("""\|\s+\|"""), "||")
            .replace(Regex("""\s*,\s*REJECT\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*REJECT(?:-[A-Z0-9_-]+)?\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*DIRECT\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*PROXY\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*MATCH\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*FINAL\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*RULE-SET\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*RULE-PROVIDER\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*DOMAIN-SET\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,\s*no-resolve\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\^"""), "^")
            .replace(Regex("""\^\s+"""), "^")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return line
    }
}
