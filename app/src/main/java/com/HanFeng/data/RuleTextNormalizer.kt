package com.HanFeng.data

import com.HanFeng.core.network.RegexCache

object RuleTextNormalizer {
    fun normalizeMessyRuleLine(rawLine: String): String {
        var line = rawLine.trim()
        if (line.isBlank()) return ""
        // 全角 → 半角归一化: 部分手机输入法或第三方维护的规则文件使用全角字符
        // (*: → ＊:, $ → ＄, | → ｜, ^ → ＾)。先统一转半角再走后续 OCR 修复路径,
        // 防止 ＊:17204＄network / ｜｜xccx＾ 等输入被静默丢弃。
        line = toHalfWidthAscii(line)
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

    /**
     * 将全角 ASCII 符号/数字/字母归一化为半角：
     *   ＊ → * ｜ → |  ＄ → $  ＾ → ^  ＝ → =  ／ → /
     *   ０-９ → 0-9  Ａ-Ｚ → A-Z  ａ-ｚ → a-z
     * 解决部分用户从 PDF/聊天工具复制规则时被自动转全角导致无法识别的问题。
     */
    private fun toHalfWidthAscii(value: String): String {
        if (value.isEmpty()) return value
        val sb = StringBuilder(value.length)
        for (c in value) {
            val cp = c.code
            sb.append(
                when (cp) {
                    in 0xFF01..0xFF5E -> (cp - 0xFEE0).toChar()
                    // 全角空格 U+3000 → 半角空格
                    0x3000 -> ' '
                    else -> c
                }
            )
        }
        return sb.toString()
    }
}
