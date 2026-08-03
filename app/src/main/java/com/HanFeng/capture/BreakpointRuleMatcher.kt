package com.HanFeng.capture

/**
 * 批次 E6: 多规则匹配调度器。
 *
 * - 输入: 全部 [BreakpointRule] + 当前请求/响应元数据
 * - 输出: 首条命中规则的 [BreakpointAction], 或 null 表示放行 (无规则匹配)
 *
 * 调度规则:
 *   1. 仅评估 [BreakpointRule.enabled] == true 的规则
 *   2. 按 (priority asc, order asc) 排序后逐条评估 [BreakpointMatch.match]
 *   3. 首条命中即终止并返回其 action; 同 priority/order 下还要做稳定性 — 用 stableSort
 *
 * 与 [BreakpointRepo.awaitResumeBlocking] 的关系: 后者处理"用户实时在详情页加断点"路径;
 * 此处处理"持久规则在 wire 上自动命中"路径。两条路径互不冲突, 详见 [BreakpointActionRouter]。
 *
 * 引用 design correctness 13 / requirements R8。
 */
object BreakpointRuleMatcher {

    fun filterEnabled(rules: List<BreakpointRule>): List<BreakpointRule> =
        rules.filter { it.enabled }

    fun sortByPriority(rules: List<BreakpointRule>): List<BreakpointRule> =
        rules.sortedWith(compareBy({ it.priority }, { it.order }))

    /**
     * 同步评估请求方向规则。
     *
     * @param rules 全部持久规则 (启用与停用都入参, 内部过滤)
     * @param host 实际 host (大小写不敏感)
     * @param method 实际 method
     * @param path 实际 path (含 query 段将自动拆出)
     * @param contentType 实际 request content-type header 值; 可为 null
     */
    fun resolveRequest(
        rules: List<BreakpointRule>,
        host: String,
        method: String,
        path: String,
        contentType: String?
    ): BreakpointAction? {
        val (p, q) = splitPathAndQuery(path)
        val sorted = sortByPriority(filterEnabled(rules.filter { it.match.kind == BreakpointKind.REQUEST }))
        for (r in sorted) {
            if (r.match.match(host, method, p, contentType, q)) return r.action
        }
        return null
    }

    /**
     * 同步评估响应方向规则。
     *
     * @param rules 全部持久规则
     * @param host 实际 host
     * @param method 实际 method (响应的 method 跟随请求)
     * @param path 实际 path
     * @param responseStatus HTTP 响应状态码; 暂仅用作日志, 未参与匹配
     * @param contentType 实际 response content-type 值, 可为 null
     */
    fun resolveResponse(
        rules: List<BreakpointRule>,
        host: String,
        method: String,
        path: String,
        responseStatus: Int,
        contentType: String?
    ): BreakpointAction? {
        val (p, q) = splitPathAndQuery(path)
        val sorted = sortByPriority(filterEnabled(rules.filter { it.match.kind == BreakpointKind.RESPONSE }))
        for (r in sorted) {
            if (r.match.match(host, method, p, contentType, q)) return r.action
        }
        return null
    }

    private fun splitPathAndQuery(path: String): Pair<String, String> {
        val idx = path.indexOf('?')
        return if (idx < 0) path to "" else path.substring(0, idx) to path.substring(idx + 1)
    }
}
