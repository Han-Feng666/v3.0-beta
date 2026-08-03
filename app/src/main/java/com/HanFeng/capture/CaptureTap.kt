package com.HanFeng.capture

import android.content.Context
import com.HanFeng.service.Http2HeaderInspection
import com.HanFeng.service.RequestInspection
import com.HanFeng.service.TlsMitmSessionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * HttpsTlsBridgeManager 与 [CaptureController] 之间的薄隔离层。
 *
 * 职责:
 * - 在 HTBM 的 4 个 inspect 成功后, 把解析出的请求/响应字段汇总成 [CaptureController] 可消费的形态再调用旁路入口
 * - 全程 try/catch: 抓包是只读旁路, 任何异常都不能影响拦截链路(design correctness 1)
 * - 控制入口/出口字节数: body preview 由 CaptureController 按 mode 自行截断
 *
 * 引用 design.md 现有组件改动表 + Components #4 / requirements R4。
 */
object CaptureTap {

    /**
     * 每个 HTTPS 流(flowKey)的 HTTP/2 streamId→capture txnId 映射;
     * 用于在响应 headers 帧到达时找回对应请求阶段分配的 txnId。
     */
    private val h2StreamTxnMap =
        ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()
    private const val MAX_SESSIONS_TRACKED = 32

    /** HTTP/1.1 请求解码完成旁路。返回 [TapRequestOutcome] 包含 txnId + 命中后的 action(无规则时 PassThrough)。 */
    fun tapHttp1Request(
        context: Context?,
        session: TlsMitmSessionManager.TlsMitmSession,
        inspection: RequestInspection,
        payload: ByteArray
    ): TapRequestOutcome {
        return try {
            if (context == null) return TapRequestOutcome.Inactive
            val appName = resolveAppName(session)
            val packageName = resolvePackageName(session, context)
            val bodyPreview = extractHttp1Body(payload, inspection.requestHeaders)
            val outcome = CaptureController.onDecodedRequest(
                method = inspection.method,
                host = inspection.host,
                path = inspection.path,
                scheme = "https",
                httpVersion = inspection.httpVersion,
                requestHeaders = inspection.requestHeaders,
                requestBodyPreview = bodyPreview,
                requestBodyTruncated = false,
                appName = appName,
                packageName = packageName,
                tlsMetaOverride = sessionTlsMeta(session.flowKey),
                sessionId = session.flowKey
            )
            when (outcome) {
                is RequestOutcome.Inactive -> TapRequestOutcome.Inactive
                is RequestOutcome.Pending -> TapRequestOutcome.Pending(
                    txnId = outcome.txnId,
                    action = outcome.action
                )
            }
        } catch (_: Throwable) {
            TapRequestOutcome.Inactive
        }
    }

    /**
     * HTTP/1.1 响应解码完成旁路。[txnId] 来自此前 [tapHttp1Request] 的返回值;
     * 若为 null 表示此前请求阶段未采样(可能 inactive 或 BY_APP 过滤), 响应也跳过。
     */
    fun tapHttp1Response(
        context: Context?,
        session: TlsMitmSessionManager.TlsMitmSession,
        txnId: Long?,
        requestInspection: RequestInspection?,
        responseBytes: ByteArray
    ): TapResponseOutcome {
        try {
            if (context == null || txnId == null) return TapResponseOutcome.Inactive
            val parsed = parseHttp1Response(responseBytes) ?: return TapResponseOutcome.NotFound
            val outcome = CaptureController.onDecodedResponse(
                txnId = txnId,
                responseStatus = parsed.status,
                responseHeaders = parsed.headers,
                responseBodyPreview = parsed.body,
                responseBodyTruncated = false,
                durationMs = 0L,
                intercepted = false
            )
            return outcome.toTap()
        } catch (_: Throwable) {
            return TapResponseOutcome.Inactive
        }
    }

    /** HTTP/2 请求头部帧旁路, 返回 [TapRequestOutcome]。 */
    fun tapHttp2Request(
        context: Context?,
        session: TlsMitmSessionManager.TlsMitmSession,
        streamId: Int,
        inspection: Http2HeaderInspection
    ): TapRequestOutcome {
        return try {
            if (context == null) return TapRequestOutcome.Inactive
            if (!inspection.requestLike) return TapRequestOutcome.Inactive
            val appName = resolveAppName(session)
            val packageName = resolvePackageName(session, context)
            val outcome = CaptureController.onDecodedHttp2Headers(
                method = inspection.method,
                host = inspection.authority,
                path = inspection.path,
                scheme = inspection.scheme ?: "https",
                httpVersion = "HTTP/2",
                requestHeaders = emptyMap(),
                appName = appName,
                packageName = packageName,
                tlsMetaOverride = sessionTlsMeta(session.flowKey),
                sessionId = session.flowKey
            )
            when (outcome) {
                is RequestOutcome.Inactive -> TapRequestOutcome.Inactive
                is RequestOutcome.Pending -> {
                    streamTxnMapFor(session.flowKey)[streamId] = outcome.txnId
                    TapRequestOutcome.Pending(txnId = outcome.txnId, action = outcome.action)
                }
            }
        } catch (_: Throwable) {
            TapRequestOutcome.Inactive
        }
    }

    /** HTTP/2 响应 headers 帧旁路, 返回 [TapResponseOutcome]。 */
    fun tapHttp2ResponseHeaders(
        context: Context?,
        session: TlsMitmSessionManager.TlsMitmSession,
        streamId: Int,
        inspection: Http2HeaderInspection
    ): TapResponseOutcome {
        try {
            if (context == null) return TapResponseOutcome.Inactive
            if (!inspection.responseLike) return TapResponseOutcome.Inactive
            val txnId = streamTxnMapFor(session.flowKey)[streamId] ?: return TapResponseOutcome.NotFound
            val status = inspection.status?.toIntOrNull() ?: 0
            val headers = buildMap {
                if (!inspection.contentType.isNullOrBlank()) put("content-type", inspection.contentType)
                if (!inspection.contentEncoding.isNullOrBlank()) put("content-encoding", inspection.contentEncoding)
                if (!inspection.location.isNullOrBlank()) put("location", inspection.location)
                if (!inspection.userAgent.isNullOrBlank()) put("user-agent", inspection.userAgent)
            }
            val outcome = CaptureController.onDecodedResponse(
                txnId = txnId,
                responseStatus = status,
                responseHeaders = headers,
                responseBodyPreview = null,
                responseBodyTruncated = false,
                durationMs = 0L,
                intercepted = false
            )
            return outcome.toTap()
        } catch (_: Throwable) {
            return TapResponseOutcome.Inactive
        }
    }

    /** HTTP/2 数据帧旁路: 仅采样写入, 无断点等待。 */
    fun tapHttp2Data(
        context: Context?,
        session: TlsMitmSessionManager.TlsMitmSession,
        streamId: Int,
        sample: ByteArray?
    ) {
        try {
            if (context == null || sample == null) return
            val txnId = streamTxnMapFor(session.flowKey)[streamId] ?: return
            CaptureController.onDecodedHttp2Body(txnId, sample)
        } catch (_: Throwable) {
        }
    }

    /** HTTP/2 stream 结束(streamClosed / rst)时清理本 stream 的 txn 映射。 */
    fun tapHttp2StreamClosed(
        session: TlsMitmSessionManager.TlsMitmSession,
        streamId: Int
    ) {
        try {
            streamTxnMapFor(session.flowKey).remove(streamId)
        } catch (_: Throwable) {
        }
    }

    /** HTBM 桥接会话结束时整体清理本 session 的映射。 */
    fun tapSessionEnded(flowKey: String) {
        try {
            h2StreamTxnMap.remove(flowKey)
            clearSessionTlsMeta(flowKey)
        } catch (_: Throwable) {
        }
    }

    private fun streamTxnMapFor(flowKey: String): ConcurrentHashMap<Int, Long> {
        val existing = h2StreamTxnMap[flowKey]
        if (existing != null) return existing
        if (h2StreamTxnMap.size >= MAX_SESSIONS_TRACKED) {
            // 防止泄漏: 清最旧一条后再创建
            h2StreamTxnMap.keys.firstOrNull()?.let { h2StreamTxnMap.remove(it) }
        }
        val created = ConcurrentHashMap<Int, Long>()
        h2StreamTxnMap[flowKey] = created
        return created
    }

    // ==================== TLS 握手旁路 ====================

    /** 每个 flowKey → TLS 元数据缓存。会话级; 在该流内任何请求建立 CaptureEntry 时取走并入。 */
    private val flowTlsMeta = ConcurrentHashMap<String, TlsMeta>()

    fun sessionTlsMeta(flowKey: String): TlsMeta? = flowTlsMeta[flowKey]

    /**
     * TLS 握手完成后旁路, 缓存 SSLSession 的协议/Cipher/对端证书(requirements R11.2)。
     * 后续该 flowKey 下任意请求建立 CaptureEntry 时由 [tapHttp1Request] / [tapHttp2Request]
     * 把 meta 作为 tlsMeta 字段合并进去。
     */
    fun tapTlsHandshake(
        context: Context?,
        session: TlsMitmSessionManager.TlsMitmSession,
        protocol: String,
        cipherSuite: String,
        alpn: String?,
        peerCertificates: List<CertMeta>
    ) {
        try {
            if (context == null) return
            val meta = TlsMeta(
                sni = session.host,
                protocol = protocol,
                cipherSuite = cipherSuite,
                alpn = alpn,
                peerCertificates = peerCertificates,
                error = null
            )
            flowTlsMeta[session.flowKey] = meta
            if (flowTlsMeta.size > 64) {
                flowTlsMeta.keys.firstOrNull()?.let { flowTlsMeta.remove(it) }
            }
        } catch (_: Throwable) {
        }
    }

    private fun clearSessionTlsMeta(flowKey: String) {
        flowTlsMeta.remove(flowKey)
    }

    // ==================== 解析工具 ====================

    private fun resolveAppName(session: TlsMitmSessionManager.TlsMitmSession): String? =
        try { session.appName } catch (_: Throwable) { null }

    private fun resolvePackageName(
        session: TlsMitmSessionManager.TlsMitmSession,
        context: Context
    ): String? {
        // TlsMitmSession 当前未携带 packageName 字段; BY_APP 模式按 appName 匹配(design R2.2)。
        // 此处返回 null 让 CaptureController 走 appName 路径(CaptureController 持有 targetAppNames)。
        return null
    }

    /** 从原始 HTTP/1.1 请求字节中按 \r\n\r\n 切出 body 部分。 */
    private fun extractHttp1Body(
        payload: ByteArray,
        headers: Map<String, String>
    ): ByteArray? {
        val sep = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var idx = -1
        outer@ for (i in 0..payload.size - sep.size) {
            for (j in sep.indices) {
                if (payload[i + j] != sep[j]) continue@outer
            }
            idx = i
            break
        }
        if (idx < 0) return null
        val start = idx + sep.size
        val declared = headers["content-length"]?.toIntOrNull()
        val bodyEnd = if (declared != null && declared >= 0) (start + declared).coerceAtMost(payload.size) else payload.size
        val cap = 32 * 1024
        val end = bodyEnd.coerceAtMost(start + cap)
        return payload.copyOfRange(start, end)
    }

    private data class ParsedHttpResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray?
    )

    private fun parseHttp1Response(bytes: ByteArray): ParsedHttpResponse? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return null
        val statusLine = text.substringBefore("\r\n").ifBlank { return null }
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val headers = LinkedHashMap<String, String>()
        val headerBlock = text.substring(0, headerEnd)
        headerBlock.split("\r\n").drop(1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                if (name.isNotEmpty()) headers[name] = value
            }
        }
        val bodyStart = headerEnd + 4
        val body = if (bodyStart < bytes.size) {
            val cap = 32 * 1024
            val end = (bodyStart + cap).coerceAtMost(bytes.size)
            bytes.copyOfRange(bodyStart, end)
        } else null
        return ParsedHttpResponse(status, headers, body)
    }
}

/**
 * 缺口 2: HTBM 调用 CaptureTap 拿到的请求侧决策结果。
 *
 * - [Inactive]: inactive 或捕获异常;HTBM 按原 payload 放行
 * - [Pending] + action: 命中(或不命中但有 PassThrough 兜底)的 action;HTBM 据此决定 Drop/Replace/PassThrough
 */
sealed interface TapRequestOutcome {
    data object Inactive : TapRequestOutcome
    data class Pending(
        val txnId: Long,
        val action: BreakpointAction
    ) : TapRequestOutcome
}

/** HTBM 调用 CaptureTap 拿到的响应侧决策结果。语义同 [TapRequestOutcome]。 */
sealed interface TapResponseOutcome {
    data object Inactive : TapResponseOutcome
    data object NotFound : TapResponseOutcome
    data class Pending(
        val txnId: Long,
        val action: BreakpointAction
    ) : TapResponseOutcome
}

private fun RequestOutcome.toTap(): TapRequestOutcome = when (this) {
    is RequestOutcome.Inactive -> TapRequestOutcome.Inactive
    is RequestOutcome.Pending -> TapRequestOutcome.Pending(txnId, action)
}

private fun ResponseOutcome.toTap(): TapResponseOutcome = when (this) {
    is ResponseOutcome.Inactive -> TapResponseOutcome.Inactive
    is ResponseOutcome.NotFound -> TapResponseOutcome.NotFound
    is ResponseOutcome.Pending -> TapResponseOutcome.Pending(txnId, action)
}
