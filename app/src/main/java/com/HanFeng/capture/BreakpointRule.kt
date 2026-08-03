package com.HanFeng.capture

import org.json.JSONArray
import org.json.JSONObject

/**
 * 批次 E6: 持久改写规则模型。
 *
 * 一条 [BreakpointRule] = 全维度匹配条件 [match] + 命中后执行的 [BreakpointAction] + 调度参数 [priority]/[order]/[enabled]。
 *
 * - 数据属主: [BreakpointRuleRepository] (JSON 单文件, 配合 FileProvider 走 ACTION_SEND/ACTION_OPEN_DOCUMENT 导入导出)
 * - 命中调度: [BreakpointRuleMatcher.resolve] 在 HttpMitmFilter/HTBM 出口处被调用, 按 priority(asc) + order(asc) 取首条命中并终止 (除非 match.combinator == ALL_OF)
 * - 规则可独立启用/停用 (enabled=false 时 matcher 跳过)
 *
 * 引用 requirements R8 / design correctness 13。
 */
data class BreakpointRule(
    /** 稳定 id, 由 [BreakpointRuleRepository.nextId] 分配, 跨导入保留。 */
    val id: Long,
    /** 规则名称, 仅 GUI 显示与导出可读性。 */
    val name: String,
    /** 优先级: 数字越小越先评估 (默认 100, 同 priority 内按 order 排序)。 */
    val priority: Int = 100,
    /** 同 priority 内的次序; 0 起。 */
    val order: Int = 0,
    /** 是否启用; false 时 matcher 跳过。 */
    val enabled: Boolean = true,
    /** 命中条件。 */
    val match: BreakpointMatch,
    /** 命中后执行的动作 (与现有详情页 [BreakpointAction] 同枚举体系)。 */
    val action: BreakpointAction
) {
    companion object {
        /**
         * 批次 E7a: 从一条抓包记录生成 "默认骨架规则":
         *   - host 大小写敏感填入(用户可后续在 cron 模式 / 后缀通配间调整)
         *   - method 全保留
         *   - path 取 `path` 段中第一段(忽略 query) 作前缀匹配, 用户可改成 Contains/Equals
         *   - content-type / query 字段留空让用户在 dialog 中补充
         *   - action 默认 PassThrough (动作由用户再选 ReplaceWith / Drop / PassThrough)
         *
         * @param entry 来源条目
         * @param kind  REQUEST 或 RESPONSE; 响应方向需 entry.responseStatus != 0
         * @param newId 调 [BreakpointRuleRepository.nextId] 提供的新 id
         * @param nameSuffix 用于默认名称后缀 (默认 "")
         */
        fun fromCaptureEntry(
            entry: CaptureEntry,
            kind: BreakpointKind,
            newId: Long,
            nameSuffix: String = ""
        ): BreakpointRule {
            val pathNoQuery = entry.path.substringBefore('?')
            val firstSeg = pathNoQuery.substringBeforeLast('/').ifEmpty { "/" }
            val name = buildString {
                append(if (kind == BreakpointKind.REQUEST) "req-" else "rsp-")
                append(entry.host)
                append(' ')
                append(entry.method)
                append(' ')
                append(pathNoQuery.take(24))
                if (nameSuffix.isNotEmpty()) {
                    append(' ')
                    append(nameSuffix)
                }
            }
            return BreakpointRule(
                id = newId,
                name = name,
                priority = 100,
                order = 0,
                enabled = true,
                match = BreakpointMatch(
                    kind = kind,
                    host = entry.host,
                    method = entry.method,
                    pathMatcher = PathMatcher.Prefix(firstSeg),
                    contentTypeContains = null,
                    queryContains = null
                ),
                action = BreakpointAction.PassThrough(useOriginal = true)
            )
        }
    }
}

/**
 * 全维度匹配条件; 所有维度 AND 关系。
 *
 * - host: 精确 (大小写不敏感), 或后缀通配 (`.example.com` 匹配 `a.b.example.com`)
 * - method: 不为空时必须等价匹配 (大小写不敏感). null 通配
 * - pathMatcher: 决定 path 匹配语义
 * - contentTypeContains: 不为空时, request/response headers content-type 必须包含子串 (大小写不敏感)
 * - kind: 仅匹配 REQUEST 或 RESPONSE
 * - queryContains: 不为空时, 必须出现在 ?query 段
 */
data class BreakpointMatch(
    val kind: BreakpointKind,
    val host: String,
    val method: String? = null,
    val pathMatcher: PathMatcher = PathMatcher.Any,
    val contentTypeContains: String? = null,
    val queryContains: String? = null
) {
    /**
     * 同步判定命中。所有维度 AND, 任一不满足返回 false。
     *
     * @param host 实际 host (大小写不敏感)
     * @param method 实际 method
     * @param path 实际 path (不含 query)
     * @param contentType 实际 content-type header 值, 可为 null
     * @param query 实际 query string (不含 ?), 可为空
     */
    fun match(host: String, method: String, path: String, contentType: String?, query: String): Boolean {
        if (!matchHost(this.host, host)) return false
        if (this.method != null && !this.method.equals(method, ignoreCase = true)) return false
        if (!pathMatcher.match(path)) return false
        if (this.contentTypeContains != null) {
            val ct = contentType?.lowercase() ?: ""
            if (this.contentTypeContains.lowercase() !in ct) return false
        }
        if (this.queryContains != null && this.queryContains.isNotEmpty()) {
            if (this.queryContains.equals(query, ignoreCase = true).not() &&
                !query.contains(this.queryContains, ignoreCase = true)
            ) return false
        }
        return true
    }

    private fun matchHost(ruleHost: String, actualHost: String): Boolean {
        if (ruleHost.isEmpty()) return true
        val r = ruleHost.lowercase()
        val a = actualHost.lowercase()
        if (r.startsWith(".")) {
            // 后缀通配: ".example.com" → a 以 ".example.com" 结尾 或 a == "example.com"
            return a == r.substring(1) || a.endsWith(r)
        }
        if (r.startsWith("*.")) {
            val tail = r.substring(1)
            return a.endsWith(tail) && a.length > tail.length
        }
        if (r == "*") return true
        return a == r
    }
}

/**
 * path 匹配策略。
 * - [Any]: 通配任意 path
 * - [Prefix]: path 以 [value] 开头
 * - [Contains]: path 包含 [value]
 * - [Suffix]: path 以 [value] 结尾
 * - [Equals]: path 等于 [value]
 * - [Regex]: Java 正则全匹配 (区分大小写; 用 matches 不用 find)
 */
sealed interface PathMatcher {
    fun match(path: String): Boolean

    data object Any : PathMatcher {
        override fun match(path: String): Boolean = true
    }

    data class Prefix(val value: String) : PathMatcher {
        override fun match(path: String): Boolean = path.startsWith(value)
    }

    data class Suffix(val value: String) : PathMatcher {
        override fun match(path: String): Boolean = path.endsWith(value)
    }

    data class Contains(val value: String) : PathMatcher {
        override fun match(path: String): Boolean = path.contains(value)
    }

    data class Equals(val value: String) : PathMatcher {
        override fun match(path: String): Boolean = path == value
    }

    data class Regex(val value: String) : PathMatcher {
        private val compiled by lazy { value.toRegex() }
        override fun match(path: String): Boolean = compiled.matches(path)
    }
}

/**
 * JSON (反)序列化器; 与 [CaptureTemplateRepository] 同 JSON 风格。
 * 字节数组字段以 Base64 编码, headers 用 JSONObject, 避免二进制溢出。
 */
object BreakpointRuleCodec {

    fun toJson(rules: List<BreakpointRule>): String {
        val arr = JSONArray()
        rules.forEach { r -> arr.put(ruleToJson(r)) }
        val root = JSONObject()
        root.put("version", 1)
        root.put("kind", "hanfeng.breakpoints")
        root.put("rules", arr)
        return root.toString(2)
    }

    fun fromJson(json: String): List<BreakpointRule> {
        val root = JSONObject(json)
        val kind = root.optString("kind", "")
        if (kind != "hanfeng.breakpoints") return emptyList()
        val arr = root.getJSONArray("rules")
        val out = ArrayList<BreakpointRule>(arr.length())
        for (i in 0 until arr.length()) {
            runCatching { out += ruleFromJson(arr.getJSONObject(i)) }
        }
        return out
    }

    private fun ruleToJson(r: BreakpointRule): JSONObject {
        val o = JSONObject()
        o.put("id", r.id)
        o.put("name", r.name)
        o.put("priority", r.priority)
        o.put("order", r.order)
        o.put("enabled", r.enabled)
        o.put("match", matchToJson(r.match))
        o.put("action", actionToJson(r.action))
        return o
    }

    private fun ruleFromJson(o: JSONObject): BreakpointRule {
        return BreakpointRule(
            id = o.optLong("id"),
            name = o.optString("name", ""),
            priority = o.optInt("priority", 100),
            order = o.optInt("order", 0),
            enabled = o.optBoolean("enabled", true),
            match = matchFromJson(o.getJSONObject("match")),
            action = actionFromJson(o.getJSONObject("action"))
        )
    }

    private fun matchToJson(m: BreakpointMatch): JSONObject {
        val o = JSONObject()
        o.put("kind", m.kind.name)
        o.put("host", m.host)
        m.method?.let { o.put("method", it) }
        o.put("path", pathMatcherToJson(m.pathMatcher))
        m.contentTypeContains?.let { o.put("contentTypeContains", it) }
        m.queryContains?.let { o.put("queryContains", it) }
        return o
    }

    private fun matchFromJson(o: JSONObject): BreakpointMatch {
        return BreakpointMatch(
            kind = runCatching { BreakpointKind.valueOf(o.optString("kind", "REQUEST")) }
                .getOrDefault(BreakpointKind.REQUEST),
            host = o.optString("host", ""),
            method = o.optString("method", "").ifEmpty { null },
            pathMatcher = pathMatcherFromJson(o.getJSONObject("path")),
            contentTypeContains = o.optString("contentTypeContains", "").ifEmpty { null },
            queryContains = o.optString("queryContains", "").ifEmpty { null }
        )
    }

    private fun pathMatcherToJson(p: PathMatcher): JSONObject {
        val o = JSONObject()
        when (p) {
            is PathMatcher.Any -> o.put("type", "any")
            is PathMatcher.Prefix -> {
                o.put("type", "prefix"); o.put("value", p.value)
            }
            is PathMatcher.Suffix -> {
                o.put("type", "suffix"); o.put("value", p.value)
            }
            is PathMatcher.Contains -> {
                o.put("type", "contains"); o.put("value", p.value)
            }
            is PathMatcher.Equals -> {
                o.put("type", "equals"); o.put("value", p.value)
            }
            is PathMatcher.Regex -> {
                o.put("type", "regex"); o.put("value", p.value)
            }
        }
        return o
    }

    private fun pathMatcherFromJson(o: JSONObject): PathMatcher {
        return when (o.optString("type")) {
            "prefix" -> PathMatcher.Prefix(o.optString("value", ""))
            "suffix" -> PathMatcher.Suffix(o.optString("value", ""))
            "contains" -> PathMatcher.Contains(o.optString("value", ""))
            "equals" -> PathMatcher.Equals(o.optString("value", ""))
            "regex" -> PathMatcher.Regex(o.optString("value", ""))
            else -> PathMatcher.Any
        }
    }

    private fun actionToJson(a: BreakpointAction): JSONObject {
        val o = JSONObject()
        when (a) {
            is BreakpointAction.PassThrough -> {
                o.put("kind", "pass")
                o.put("useOriginal", a.useOriginal)
            }
            is BreakpointAction.ReplaceWith -> {
                o.put("kind", "replace")
                o.put("replacement", android.util.Base64.encodeToString(a.replacement, android.util.Base64.NO_WRAP))
                a.headersOverride?.let {
                    val h = JSONObject()
                    it.forEach { (k, v) -> h.put(k, v) }
                    o.put("headersOverride", h)
                }
                a.statusLineOverride?.let { o.put("statusLineOverride", it) }
            }
            BreakpointAction.Drop -> o.put("kind", "drop")
        }
        return o
    }

    private fun actionFromJson(o: JSONObject): BreakpointAction {
        return when (o.optString("kind")) {
            "replace" -> BreakpointAction.ReplaceWith(
                replacement = android.util.Base64.decode(
                    o.optString("replacement", ""),
                    android.util.Base64.NO_WRAP
                ),
                headersOverride = runCatching { o.getJSONObject("headersOverride").keys().asSequence().associateWith { k -> o.getJSONObject("headersOverride").optString(k) } }.getOrNull(),
                statusLineOverride = o.optString("statusLineOverride", "").ifEmpty { null }
            )
            "drop" -> BreakpointAction.Drop
            else -> BreakpointAction.PassThrough(useOriginal = o.optBoolean("useOriginal", true))
        }
    }
}
