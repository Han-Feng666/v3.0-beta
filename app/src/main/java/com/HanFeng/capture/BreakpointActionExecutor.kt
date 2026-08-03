package com.HanFeng.capture

/**
 * 将用户在详情页应用 [BreakpointAction] 后, 把该动作转成 HttpMitmFilter / HTBM 实际可消费的字节流
 *
 * - 改请求方向: 仅在 [BreakpointAction.ReplaceWith] 下生效, 透传替换后的字节真入网(design correctness 7)
 * - 改响应方向: 替换响应字节, 重算 Content-Length, 删除 Transfer-Encoding: chunked(design correctness 9)
 * - Drop 在请求方向 = 不发送; 响应方向 = 给客户端空 0-length
 * - PassThrough(useOriginal=true) = 透传原字节, 不动
 *
 * 注意: 与 HttpMitmFilter 的 buildSyntheticResponse 通过字符串搭建响应一致, 这里复用同源约定, 避免 unification drift。
 *
 * 引用 design.md Components #5 / requirements R8 / R10。
 */
object BreakpointActionExecutor {

    /**
     * 计算请求方向 wire action 后的效果。
     *
     * @param action 用户裁决的动作
     * @param originalChunk 原 HTTP/1.1 请求字节
     * @param draft 用户在详情页编辑的请求草稿(仅 ReplaceWith 时使用; Replace 字节流优先于 draft)
     */
    fun applyToRequest(
        action: BreakpointAction,
        originalChunk: ByteArray,
        draftRequest: CaptureDraftRequest?
    ): RequestActionOutcome {
        return when (action) {
            is BreakpointAction.PassThrough -> {
                if (action.useOriginal) RequestActionOutcome.Passthrough(originalChunk)
                else RequestActionOutcome.Passthrough(originalChunk)
            }
            is BreakpointAction.ReplaceWith -> {
                // 请求方向: 直接把替换字节真入网(design correctness 7)
                RequestActionOutcome.Replace(buildHttpRequestFromDraft(draftRequest, action) ?: action.replacement)
            }
            BreakpointAction.Drop -> RequestActionOutcome.Drop
        }
    }

    /**
     * HTBM-side 简化版: 不要求草稿, 直接消费 [BreakpointAction.ReplaceWith.replacement] 字节。
     * - PassThrough -> 返回 originalChunk
     * - ReplaceWith -> 返回 action.replacement(已为完整 HTTP/1.1 wire 字节, 由 GUI 端 [CaptureDraftRequest] 衔接已封装头/正文)
     * - Drop -> 由调用方决定, 这里兜底返回空字节
     */
    fun applyToRequest(
        action: BreakpointAction,
        originalChunk: ByteArray
    ): ByteArray {
        return when (action) {
            is BreakpointAction.PassThrough -> originalChunk
            is BreakpointAction.ReplaceWith -> action.replacement.takeIf { it.isNotEmpty() } ?: originalChunk
            BreakpointAction.Drop -> ByteArray(0)
        }
    }

    /**
     * 计算响应方向 wire action 后的效果。
     * - ReplaceWith draft.replacement 时, 重算 Content-Length + 删除 Transfer-Encoding: chunked。
     * 否则直接使用 draft.body 作为响应体。
     * - Drop 给客户端空响应。
     */
    fun applyToResponse(
        action: BreakpointAction,
        draftResponse: CaptureDraftResponse?
    ): ResponseActionOutcome {
        return when (action) {
            is BreakpointAction.PassThrough -> ResponseActionOutcome.PassthroughNative
            is BreakpointAction.ReplaceWith -> {
                val modified = buildHttpResponseWithContentLength(
                    draftResponse = draftResponse,
                    overrideBody = action.replacement,
                    headersOverride = action.headersOverride,
                    statusLineOverride = action.statusLineOverride
                )
                ResponseActionOutcome.Replace(modified)
            }
            BreakpointAction.Drop -> {
                val empty = buildEmptyHttpResponse()
                ResponseActionOutcome.Replace(empty)
            }
        }
    }

    /**
     * HTBM-side 简化版: 不要求草稿, 直接消费 [BreakpointAction.ReplaceWith.replacement] + headersOverride/statusLineOverride。
     * - PassThrough -> 返回 originalChunk
     * - Drop -> 空 0-length 响应
     * - ReplaceWith -> 用 action 字段 + 原响应字节拼出新响应体(重算 Content-Length, 去 chunked)
     */
    fun applyToResponse(
        action: BreakpointAction,
        originalChunk: ByteArray
    ): ByteArray {
        return when (action) {
            is BreakpointAction.PassThrough -> originalChunk
            is BreakpointAction.ReplaceWith -> action.replacement.takeIf { it.isNotEmpty() } ?: buildHttpResponseWithContentLengthFromAction(
                    originalChunk = originalChunk,
                    replacement = action.replacement,
                    headersOverride = action.headersOverride,
                    statusLineOverride = action.statusLineOverride
                )
            BreakpointAction.Drop -> buildEmptyHttpResponse()
        }
    }

    sealed interface RequestActionOutcome {
        /** 用 Initiating取消字节透传(design correctness 13)。 */
        data class Passthrough(val bytes: ByteArray) : RequestActionOutcome
        /** 用新字节真入网(design correctness 7)。 */
        data class Replace(val bytes: ByteArray) : RequestActionOutcome
        /** 丢弃请求; HTBM 会升级断开该连接。 */
        data object Drop : RequestActionOutcome
    }

    sealed interface ResponseActionOutcome {
        /** 让 HTBM 走原始路径 (R7)。 */
        data object PassthroughNative : ResponseActionOutcome
        /** 用新字节返给client且不入网(design correctness 7)。 */
        data class Replace(val bytes: ByteArray) : ResponseActionOutcome
    }

    private fun buildHttpRequestFromDraft(
        draft: CaptureDraftRequest?,
        action: BreakpointAction.ReplaceWith
    ): ByteArray? {
        val d = draft ?: return null
        val sb = StringBuilder()
        sb.append(d.method).append(' ').append(d.path).append(" HTTP/1.1\r\n")
        val merged = LinkedHashMap(d.headers)
        action.headersOverride?.let { merged.putAll(it) }
        val body = action.replacement.takeIf { it.isNotEmpty() } ?: d.body
        merged["Host"] = d.host
        // design correctness 9: 请求侧也强制重算 Content-Length(大小写不敏感移除旧 Content-Length)
        val toRemove = merged.keys.filter { it.equals("content-length", true) }
        toRemove.forEach { merged.remove(it) }
        merged["Content-Length"] = body?.size?.toString() ?: "0"
        merged["Connection"] = "close"
        merged.forEach { (k, v) -> sb.append(k).append(':').append(' ').append(v).append("\r\n") }
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return body?.takeIf { it.isNotEmpty() }?.let { head + it } ?: head
    }

    private fun buildHttpResponseWithContentLength(
        draftResponse: CaptureDraftResponse?,
        overrideBody: ByteArray,
        headersOverride: Map<String, String>?,
        statusLineOverride: String?
    ): ByteArray {
        val statusLine = statusLineOverride ?: draftResponse?.statusLine ?: "HTTP/1.1 200 OK"
        val headers = LinkedHashMap<String, String>(draftResponse?.headers ?: emptyMap())
        headersOverride?.let { headers.putAll(it) }
        // design correctness 9: 强制重算 Content-Length, 删除 chunked
        // HTTP 头大小写不敏感, 但 Map 大小写敏感 → 遍历按 lowercase 比较, 移除旧 Transfer-Encoding 与 Content-Length 后再灌新 Content-Length
        val toRemove = headers.keys.filter {
            it.equals("transfer-encoding", true) || it.equals("content-length", true)
        }
        toRemove.forEach { headers.remove(it) }
        headers["Content-Length"] = overrideBody.size.toString()
        val sb = StringBuilder()
        sb.append(statusLine).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(':').append(' ').append(v).append("\r\n") }
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return head + overrideBody
    }

    /** 空 0-length 响应。 */
    private fun buildEmptyHttpResponse(): ByteArray {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Length: 0\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * 把 [BreakpointAction.ReplaceWith] 解析成完整 HTTP/1.1 响应字节。
     * 字段优先级: action.headersOverride / action.statusLineOverride → 从 [originalChunk] 解析同名值保底。
     */
    private fun buildHttpResponseWithContentLengthFromAction(
        originalChunk: ByteArray,
        replacement: ByteArray,
        headersOverride: Map<String, String>?,
        statusLineOverride: String?
    ): ByteArray {
        val parsed = parseHttpResponseHead(originalChunk)
        val statusLine = statusLineOverride ?: parsed.statusLine ?: "HTTP/1.1 200 OK"
        val headers = LinkedHashMap<String, String>(parsed.headers)
        headersOverride?.let { headers.putAll(it) }
        // design correctness 9: 强制重算 Content-Length, 删除 chunked(大小写不敏感遍历)
        val toRemove = headers.keys.filter {
            it.equals("transfer-encoding", true) || it.equals("content-length", true)
        }
        toRemove.forEach { headers.remove(it) }
        headers["Content-Length"] = replacement.size.toString()
        val sb = StringBuilder()
        sb.append(statusLine).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(':').append(' ').append(v).append("\r\n") }
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return head + replacement
    }

    private data class ParsedResponseHead(
        val statusLine: String?,
        val headers: Map<String, String>
    )

    /** 切出 statusLine + headers dict, body 不返回。 */
    private fun parseHttpResponseHead(bytes: ByteArray): ParsedResponseHead {
        val text = String(bytes, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        if (split <= 0) return ParsedResponseHead(null, emptyMap())
        val statusLine = text.substringBefore("\r\n").ifBlank { null }
        val headers = LinkedHashMap<String, String>()
        text.substring(0, split).split("\r\n").drop(1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                if (name.isNotEmpty()) headers[name] = value
            }
        }
        return ParsedResponseHead(statusLine, headers)
    }
}
