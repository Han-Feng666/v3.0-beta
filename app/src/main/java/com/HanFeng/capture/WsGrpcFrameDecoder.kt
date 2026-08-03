package com.HanFeng.capture

/**
 * 批次 C4: WebSocket 与 gRPC 帧解码(纯算法, 无 Android 依赖, 单测可验证)。
 *
 * ## WebSocket
 * RFC 6455: fin(1) + rsv(3) + opcode(4) + mask(1) + payload_len(7) + ext-len(0/16/64)
 *           + mask-key(0/4) + payload(mask 解除后字节)。客户端→服务端必须 mask, 服务端→客户端不 mask。
 *
 * ## gRPC
 * Length-Prefixed Message: compressed-flag(1, 0=no/compressed) + message-length(4, BE uint) + protobuf-message(N bytes)。
 * 完整的 trailer 带 `grpc-status` 二级头; HTTP/2 上 status=200 时 gRPC 错误码用 trailers 携带。
 *
 * 仅做"只读解码"展示用途, 不再回写网络。设计文档: requirements R4.5 多协议识别; 批次 C4 缺口补全。
 */
object WsGrpcFrameDecoder {

    /** WebSocket opcode 集合(RFC 6455 5.2)。 */
    enum class WsOpcode(val raw: Int) {
        CONTINUATION(0x0),
        TEXT(0x1),
        BINARY(0x2),
        CLOSE(0x8),
        PING(0x9),
        PONG(0xA),
        UNKNOWN(-1);

        companion object {
            fun from(raw: Int): WsOpcode = entries.firstOrNull { it.raw == (raw and 0x0F) } ?: UNKNOWN
        }
    }

    data class WsFrame(
        val fin: Boolean,
        val opcode: WsOpcode,
        /** 解 mask 后的 payload。 */
        val payload: ByteArray,
        /** mask 位(RFC: client→server 必为 true)。 */
        val maskedFromClient: Boolean,
        /** 解析到第几个字节, 用于多帧流式恢复。 */
        val consumed: Int
    )

    data class WsDecodeResult(
        val frames: List<WsFrame>,
        /** bytes consumed 总和; 剩余 = input.size - consumed 显示为 partial。 */
        val totalConsumed: Int,
        val error: String? = null
    )

    /** 解析一个 WebSocket 字节流(可含多帧)。半帧 → 截断入 frames 段已成功部分, error 标 partialTail。 */
    fun decodeWebSocketFrames(input: ByteArray): WsDecodeResult {
        if (input.isEmpty()) return WsDecodeResult(emptyList(), 0)
        val frames = mutableListOf<WsFrame>()
        var idx = 0
        try {
            while (idx < input.size) {
                if (idx + 2 > input.size) return partialWs(frames, idx, input.size, "truncated header")
                val b0 = input[idx].toInt() and 0xFF
                val b1 = input[idx + 1].toInt() and 0xFF
                val fin = (b0 and 0x80) != 0
                val opcode = WsOpcode.from(b0)
                val masked = (b1 and 0x80) != 0
                var payloadLen = (b1 and 0x7F).toLong()
                var headerLen = 2
                when {
                    payloadLen == 126L -> {
                        if (idx + 4 > input.size) return partialWs(frames, idx, input.size, "truncated ext-len-16")
                        payloadLen = ((input[idx + 2].toInt() and 0xFF) shl 8 or (input[idx + 3].toInt() and 0xFF)).toLong()
                        headerLen = 4
                    }
                    payloadLen == 127L -> {
                        if (idx + 10 > input.size) return partialWs(frames, idx, input.size, "truncated ext-len-64")
                        var v = 0L
                        for (i in 0 until 8) v = (v shl 8) or (input[idx + 2 + i].toLong() and 0xFF)
                        payloadLen = v
                        headerLen = 10
                    }
                }
                if (payloadLen > Int.MAX_VALUE.toLong()) return partialWs(frames, idx, input.size, "payload-too-large")
                val maskLen = if (masked) 4 else 0
                val frameEnd = idx + headerLen + maskLen + payloadLen.toInt()
                if (frameEnd > input.size) return partialWs(frames, idx, input.size, "truncated payload")
                val maskKey = if (masked) input.copyOfRange(idx + headerLen, idx + headerLen + 4) else null
                val rawPayload = input.copyOfRange(idx + headerLen + maskLen, frameEnd)
                val unmasked = if (maskKey != null) {
                    ByteArray(rawPayload.size) { i -> (rawPayload[i].toInt() xor maskKey[i % 4].toInt()).toByte() }
                } else rawPayload
                frames += WsFrame(fin = fin, opcode = opcode, payload = unmasked, maskedFromClient = masked, consumed = frameEnd - idx)
                idx = frameEnd
            }
        } catch (t: Throwable) {
            return WsDecodeResult(frames, idx, error = t.javaClass.simpleName + ":" + t.message)
        }
        return WsDecodeResult(frames, idx)
    }

    private fun partialWs(frames: List<WsFrame>, idx: Int, total: Int, reason: String): WsDecodeResult =
        WsDecodeResult(frames, idx, error = "partial:$reason:consumed=$idx:total=$total")

    // ---------------- gRPC ----------------

    data class GrpcFrame(
        val compressed: Boolean,
        /** gRPC length-prefixed payload(protobuf message 二进制)。 */
        val message: ByteArray,
        val consumed: Int
    )

    data class GrpcDecodeResult(
        val frames: List<GrpcFrame>,
        val totalConsumed: Int,
        val error: String? = null
    )

    fun decodeGrpcFrames(input: ByteArray): GrpcDecodeResult {
        if (input.isEmpty()) return GrpcDecodeResult(emptyList(), 0)
        val frames = mutableListOf<GrpcFrame>()
        var idx = 0
        try {
            while (idx + 5 <= input.size) {
                val compressed = (input[idx].toInt() and 0xFF) != 0
                val len = ((input[idx + 1].toInt() and 0xFF) shl 24) or
                    ((input[idx + 2].toInt() and 0xFF) shl 16) or
                    ((input[idx + 3].toInt() and 0xFF) shl 8) or
                    (input[idx + 4].toInt() and 0xFF)
                if (len < 0 || len > 16 * 1024 * 1024) {
                    return GrpcDecodeResult(frames, idx, error = "grpc-len-out-of-range:$len")
                }
                val frameEnd = idx + 5 + len
                if (frameEnd > input.size) {
                    return GrpcDecodeResult(frames, idx, error = "partial:truncated payload:consumed=$idx:total=${input.size}")
                }
                frames += GrpcFrame(
                    compressed = compressed,
                    message = input.copyOfRange(idx + 5, frameEnd),
                    consumed = frameEnd - idx
                )
                idx = frameEnd
            }
            // 剩余不足 5 字节 → partial header
            if (idx < input.size) {
                return GrpcDecodeResult(frames, idx, error = "partial:truncated header:consumed=$idx:total=${input.size}")
            }
        } catch (t: Throwable) {
            return GrpcDecodeResult(frames, idx, error = t.javaClass.simpleName + ":" + t.message)
        }
        return GrpcDecodeResult(frames, idx)
    }

    /** 把 WS 帧按 ASCII 可视化(用于 GUI 展示), 非 UTF-8 安全字符以 hex 0x??: 替代。 */
    fun renderWsFramesPretty(frames: List<WsFrame>): String {
        if (frames.isEmpty()) return "(empty ws)"
        val sb = StringBuilder()
        frames.forEachIndexed { i, f ->
            sb.appendFrameTitle(i, "fin=${f.fin} opcode=${f.opcode.name}${if (f.maskedFromClient) " [masked]" else ""}")
            sb.appendPayloadPretty(f.payload, f.opcode)
        }
        return sb.toString()
    }

    /** 把 gRPC 帧按十六进制 + 折叠打印法呈现。 */
    fun renderGrpcFramesPretty(frames: List<GrpcFrame>): String {
        if (frames.isEmpty()) return "(empty grpc)"
        val sb = StringBuilder()
        frames.forEachIndexed { i, f ->
            sb.appendFrameTitle(i, "compressed=${f.compressed} len=${f.message.size}")
            val previewMax = 256
            val preview = if (f.message.size > previewMax) f.message.copyOfRange(0, previewMax) else f.message
            val hex = preview.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
            sb.append("    ").appendLine(hex)
            if (f.message.size > previewMax) sb.appendLine("    ...(${f.message.size - previewMax} more bytes)")
        }
        return sb.toString()
    }

    private fun StringBuilder.appendFrameTitle(idx: Int, meta: String) {
        append("[").append(idx).append("] ").append(meta).append('\n')
    }

    private fun StringBuilder.appendPayloadPretty(payload: ByteArray, opcode: WsOpcode) {
        when (opcode) {
            WsOpcode.TEXT -> {
                val text = String(payload, Charsets.UTF_8)
                val display = if (text.length > 1024) text.substring(0, 1024) + "...(${text.length - 1024} more)" else text
                append("    ").appendLine(display)
            }
            WsOpcode.CLOSE -> {
                if (payload.size >= 2) {
                    val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    append("    close code=").append(code).appendLine()
                    if (payload.size > 2) append("    reason=").appendLine(String(payload.copyOfRange(2, payload.size), Charsets.UTF_8))
                } else appendLine("    (empty close)")
            }
            else -> {
                val previewMax = 256
                val preview = if (payload.size > previewMax) payload.copyOfRange(0, previewMax) else payload
                val hex = preview.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                append("    ").appendLine(hex)
                if (payload.size > previewMax) appendLine("    ...(${payload.size - previewMax} more bytes)")
            }
        }
    }
}
