package com.HanFeng.capture

/**
 * 抓包列表筛选纯逻辑。
 *
 * - 仅服务 UI 显示,不影响 ring buffer 内容
 * - 三个维度任一为 null/空表示"通配";维度间为 AND
 * - host 子串匹配大小写不敏感(列表展示更友好)
 *
 * 引用 requirements R1.2 / design 现有组件改动表。
 */
data class CaptureFilter(
    val hostContains: String? = null,
    val methods: Set<String> = emptySet(),
    val statusRanges: List<IntRange> = emptyList(),
    /** 批次 C3: 按 sessionId (= TlsMitmSession.flowKey) 过滤, 空 = 不过滤。 */
    val sessionId: String? = null,
    /** 批次 C3: 关键字对 path+host+requestHeaders+body 预览做 contains 不区分大小写, 空 = 不过滤。 */
    val keyword: String? = null,
    /** 批次 C3: 仅看被断点拦截的 entry。 */
    val interceptedOnly: Boolean = false,
    /** 批次 D: 仅看 durationMs 在此闭区间内的 entry, null = 不过滤。 */
    val durationMsRange: LongRange? = null,
    /** 批次 D: 按应用名(精确) 过滤, 空 = 不过滤。 */
    val appName: String? = null
) {
    val isActive: Boolean get() =
        !hostContains.isNullOrBlank() || methods.isNotEmpty() || statusRanges.isNotEmpty() ||
            !sessionId.isNullOrBlank() || !keyword.isNullOrBlank() || interceptedOnly ||
            durationMsRange != null || !appName.isNullOrBlank()

    fun matches(entry: CaptureEntry): Boolean {
        if (!isActive) return true
        if (!hostContains.isNullOrBlank() &&
            !entry.host.contains(hostContains, ignoreCase = true)
        ) return false
        if (methods.isNotEmpty() &&
            entry.method.uppercase() !in methods.map { it.uppercase() }.toSet()
        ) return false
        if (statusRanges.isNotEmpty()) {
            val s = entry.responseStatus
            if (s == 0) return false // 过滤中尚未到响应阶段的条目不通过状态码过滤
            if (statusRanges.none { s in it }) return false
        }
        if (!sessionId.isNullOrBlank() && entry.sessionId != sessionId) return false
        if (!keyword.isNullOrBlank()) {
            val kw = keyword
            val hit = entry.path.contains(kw, ignoreCase = true) ||
                entry.host.contains(kw, ignoreCase = true) ||
                entry.requestHeaders.entries.any { it.key.contains(kw, ignoreCase = true) || it.value.contains(kw, ignoreCase = true) } ||
                (entry.requestBodyPreview?.toString(Charsets.UTF_8)?.contains(kw, ignoreCase = true) == true) ||
                (entry.responseBodyPreview?.toString(Charsets.UTF_8)?.contains(kw, ignoreCase = true) == true)
            if (!hit) return false
        }
        if (interceptedOnly && !entry.intercepted) return false
        // 批次 D: 时长过滤(0..0 表示瞬时;未响应 entry durationMs=0 满足)
        if (durationMsRange != null && entry.durationMs !in durationMsRange) return false
        if (!appName.isNullOrBlank() && entry.appName != appName) return false
        return true
    }
}

/** 解析常用的状态码段表达式: "200,3xx,4xx,404" → IntRange 列表。 */
object CaptureFilterParser {
    fun parseStatusRanges(input: String?): List<IntRange> {
        val s = input?.trim().orEmpty()
        if (s.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        s.split(',').forEach { token ->
            val t = token.trim()
            if (t.isEmpty()) return@forEach
            val lower = t.lowercase()
            // 2xx/3xx/4xx/5xx 形式
            when {
                lower.endsWith("xx") -> {
                    val n = lower.removeSuffix("xx").toIntOrNull()
                    if (n != null) result.add(n * 100..(n * 100 + 99))
                }
                else -> {
                    val range = t.split("..", "-", ":")
                    val first = range.firstOrNull()?.trim()?.toIntOrNull()
                    val last = range.elementAtOrNull(1)?.trim()?.toIntOrNull() ?: first
                    if (first != null && last != null) result.add(first..last)
                }
            }
        }
        return result
    }
}
