package com.HanFeng.capture

/**
 * 抓包 (Traffic Capture) 核心数据模型与类型定义。
 *
 * 对齐 HTTPCanary 能力集, 参见 `.monkeycode/specs/2026-08-01-traffic-capture/design.md`。
 *
 * 本文件只承载纯数据类型, 不持有逻辑或 Android Framework 引用,
 * 方便在 JVM 单元测试中无 Android SDK 上下文使用。
 */

/**
 * 一条抓包记录: 一组请求-响应配对的聚合。
 *
 * - 仅请求阶段已达时 response* 字段为 0 / 空
 * - `error` 用于记录上游 TLS 握手失败 / 重放超时 / 改写越界回退 / 断点等待中等语义性状态
 *
 * 引用 design.md Data Models 与 requirements R4。
 */
data class CaptureEntry(
    val txnId: Long,
    val timestampMs: Long,
    val appName: String?,
    val packageName: String?,
    val scheme: String,
    val method: String,
    val host: String,
    val path: String,
    val httpVersion: String,
    val requestHeaders: Map<String, String>,
    val requestBodyPreview: ByteArray?,
    val requestBodyTruncated: Boolean,
    val responseStatus: Int,
    val responseHeaders: Map<String, String>,
    val responseBodyPreview: ByteArray?,
    val responseBodyTruncated: Boolean,
    val durationMs: Long,
    val error: String? = null,
    val intercepted: Boolean = false,
    val tlsMeta: TlsMeta? = null,
    val replayed: Boolean = false,
    /** 批次 C3: 多会话隔离 — 同一 VPN/TLS flowKey 的连续多条规一 sessionId, 方便 UI 按会话分组与导出按 session 聚合。 默认空串 = 旧 entry 不带分组。 */
    val sessionId: String = ""
) {
    /** 响应是否已达成。responseStatus==0 表示尚未到响应阶段。 */
    val isComplete: Boolean get() = responseStatus != 0

    /** 该 entry 是否正挂在断点等待用户裁决。 */
    val isPendingBreakpoint: Boolean get() = error == ERROR_BREAKPOINT_PENDING

    companion object {
        /** 断点等待中的 error 占位符, 用于派生 isPendingBreakpoint。 */
        const val ERROR_BREAKPOINT_PENDING = "breakpoint-pending"

        /** 重放截断的 error 占位符。 */
        const val ERROR_REPLAY_TRUNCATED = "replay-truncated"

        /**
         * taskId 命名空间前缀。重放产生的 entry 用此空间避免与正常抓包 txnId 冲突。
         * design correctness 5 约定重放用 `replay-${seq}` 命名空间。
         */
        const val TXN_NAMESPACE_REPLAY = "replay"

        /** 生成重放专用的 txnId 高位。取一个普通 session 不可能落入的固定前缀区间。 */
        fun makeReplayTxnId(seq: Long): Long = (1L shl 60) or (seq and ((1L shl 60) - 1))
    }
}

/** TLS 握手元数据, requirements R11。 */
data class TlsMeta(
    val sni: String?,
    val protocol: String,
    val cipherSuite: String,
    val alpn: String?,
    val peerCertificates: List<CertMeta>,
    /** 握手失败时填失败原因; 成功时为 null。 */
    val error: String? = null
)

data class CertMeta(
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val sha256Fingerprint: String
)
