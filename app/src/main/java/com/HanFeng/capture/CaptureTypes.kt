package com.HanFeng.capture

/**
 * 断点 (Breakpoint) 与改写相关数据模型。
 *
 * 引用 design.md Data Models 与 requirements R8:
 *  - 用户在详情页编辑请求/响应生产的草稿只本地存活
 *  - 草稿关联的"对该路径加断点"规则会写入 [BreakpointRepo], 命中后挂起 HttpMitmFilter 等用户 application
 *  - 用户三选一: 放行 / 替换后放行 / 丢弃, 编码为 [BreakpointAction]
 *  - 所有规则仅内存, 关抓包即清(design correctness 4)
 */

enum class BreakpointKind { REQUEST, RESPONSE }

/**
 * 断点命中规则。`null` 字段表示该维度为通配。
 *
 * - 仅 `host`: 命中该 host 的全部请求/响应
 * - `host + method`: 命中该 host + 该 method 的全部请求/响应
 * - `host + method + pathPrefix`: 最严格, 仅命中以 pathPrefix 开头的流量
 */
data class BreakpointMatchRule(
    val host: String,
    val method: String?,
    val pathPrefix: String?,
    val kind: BreakpointKind
) {
    fun match(host: String, method: String, path: String): Boolean {
        if (!this.host.equals(host, ignoreCase = true)) return false
        if (this.method != null && !this.method.equals(method, ignoreCase = true)) return false
        if (this.pathPrefix != null && !path.startsWith(this.pathPrefix)) return false
        return true
    }
}

/**
 * 用户在详情页对断点命中流量做出的最终动作。
 *
 * - [PassThrough]: 直接放行; useOriginal=true 时透传原字节, false 时透传原 chunk 但不进入替换分支(语义保留)
 * - [ReplaceWith]: 用草稿替换原字节后继续走 HttpMitmFilter 后续链路; 对响应应用时 executor 会强制重算 Content-Length 并删除 chunked 头
 * - [Drop]: 直接丢弃, 上游/下游都收到空(对请求侧 = 不发送; 对响应侧 = 给客户端空)
 */
sealed interface BreakpointAction {
    data class PassThrough(val useOriginal: Boolean) : BreakpointAction
    data class ReplaceWith(
        val replacement: ByteArray,
        val headersOverride: Map<String, String>? = null,
        val statusLineOverride: String? = null
    ) : BreakpointAction
    data object Drop : BreakpointAction
}

/** 改请求的本地草稿。仅存活在详情页生命周期内。 */
data class CaptureDraftRequest(
    val method: String,
    val host: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray?
)

/** 改响应的本地草稿。 */
data class CaptureDraftResponse(
    val statusLine: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

/**
 * 缺口 2: onDecoded* 出参。
 *
 * - [Inactive]: 抓包 inactive 或 app 不入选;HTBM 应原样放行
 * - [Pending] + action + txnId: 进入 HTBM 的动作裁决 — PassThrough 透传原 payload; Drop 截断(空写或直接 return); ReplaceWith 用 executor 替换原 chunk
 *
 * 草稿字段嵌入 BreakpointAction.ReplaceWith 由 GUI 端构造时填充; 不在此出参独立传输。
 */
sealed interface RequestOutcome {
    data object Inactive : RequestOutcome
    data class Pending(
        val txnId: Long,
        val action: BreakpointAction
    ) : RequestOutcome
}

sealed interface ResponseOutcome {
    data object Inactive : ResponseOutcome
    data object NotFound : ResponseOutcome
    data class Pending(
        val txnId: Long,
        val action: BreakpointAction
    ) : ResponseOutcome
}

/**
 * 请求模板: 用户从历史 entry 长按保存以便跨多次抓包会话重放同一接口。
 * 持久化到 [com.HanFeng.capture.template.CaptureTemplateRepository]。
 *
 * 引用 requirements R9.4。
 */
data class CaptureTemplate(
    val id: String,
    val label: String,
    val createdAt: Long,
    val method: String,
    val scheme: String,
    val host: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray?
)
