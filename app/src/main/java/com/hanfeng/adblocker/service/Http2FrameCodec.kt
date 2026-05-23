package com.HanFeng.service

object Http2FrameCodec {
    fun buildHeadersFrame(streamId: Int, headerBlock: ByteArray, endStream: Boolean): ByteArray {
        val flags = 0x4 or if (endStream) 0x1 else 0x0
        return buildFrame(type = 0x1, flags = flags, streamId = streamId, payload = headerBlock)
    }

    fun buildHeaderBlockFrames(
        streamId: Int,
        headerBlock: ByteArray,
        endStream: Boolean,
        padded: Boolean = false,
        priorityFragment: ByteArray = ByteArray(0),
        maxFramePayloadSize: Int = DEFAULT_HEADER_FRAME_PAYLOAD_SIZE
    ): ByteArray {
        if (headerBlock.isEmpty()) {
            return buildHeadersFrame(streamId = streamId, headerBlock = headerBlock, endStream = endStream)
        }
        val framePayloadSize = maxFramePayloadSize.coerceAtLeast(1)
        val output = ArrayList<ByteArray>()
        var offset = 0
        var first = true
        while (offset < headerBlock.size) {
            val nextOffset = minOf(offset + framePayloadSize, headerBlock.size)
            val fragment = headerBlock.copyOfRange(offset, nextOffset)
            val isLast = nextOffset >= headerBlock.size
            val type = if (first) 0x1 else 0x9
            var flags = if (isLast) 0x4 else 0x0
            var payload = fragment
            if (first && endStream) {
                flags = flags or 0x1
            }
            if (first && padded) {
                flags = flags or 0x8
                payload = byteArrayOf(0) + payload
            }
            if (first && priorityFragment.size == 5) {
                flags = flags or 0x20
                payload = if (padded) {
                    byteArrayOf(0) + priorityFragment + fragment
                } else {
                    priorityFragment + fragment
                }
            }
            output += buildFrame(type = type, flags = flags, streamId = streamId, payload = payload)
            first = false
            offset = nextOffset
        }
        return output.fold(ByteArray(0)) { acc, frame -> acc + frame }
    }

    private val TRANSPARENT_1X1_GIF = byteArrayOf(
        0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(), 0x39.toByte(), 0x61.toByte(),
        0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x80.toByte(), 0x00.toByte(), 0x00.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x21.toByte(), 0xF9.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x2C.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x02.toByte(), 0x02.toByte(), 0x4C.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x3B.toByte()
    )

    fun buildNeutralizedResponseFrames(streamId: Int, contentType: String?): ByteArray {
        val normalizedContentType = if (contentType.isNullOrBlank()) {
            "text/plain; charset=utf-8"
        } else {
            contentType
        }
        val bodyBytes = buildNeutralizedBody(normalizedContentType)
        val headerBlock = HpackEncoder.encodeLiteralHeadersWithoutIndexing(
            listOf(
                HpackDecoder.HeaderField(":status", "200"),
                HpackDecoder.HeaderField("content-length", bodyBytes.size.toString()),
                HpackDecoder.HeaderField("cache-control", "no-store"),
                HpackDecoder.HeaderField("pragma", "no-cache"),
                HpackDecoder.HeaderField("expires", "0"),
                HpackDecoder.HeaderField("x-hanfeng-block", "1"),
                HpackDecoder.HeaderField("content-type", normalizedContentType)
            )
        )
        val headersFrame = buildHeadersFrame(streamId = streamId, headerBlock = headerBlock, endStream = false)
        val dataFrame = buildFrame(
            type = 0x0,
            flags = 0x1,
            streamId = streamId,
            payload = bodyBytes
        )
        return headersFrame + dataFrame
    }

    fun buildRstStreamFrame(streamId: Int, errorCode: Int = 0x8): ByteArray {
        val payload = byteArrayOf(
            ((errorCode ushr 24) and 0xFF).toByte(),
            ((errorCode ushr 16) and 0xFF).toByte(),
            ((errorCode ushr 8) and 0xFF).toByte(),
            (errorCode and 0xFF).toByte()
        )
        return buildFrame(type = 0x3, flags = 0x0, streamId = streamId, payload = payload)
    }

    private fun buildFrame(type: Int, flags: Int, streamId: Int, payload: ByteArray): ByteArray {
        val length = payload.size
        val header = byteArrayOf(
            ((length ushr 16) and 0xFF).toByte(),
            ((length ushr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
            (type and 0xFF).toByte(),
            (flags and 0xFF).toByte(),
            ((streamId ushr 24) and 0x7F).toByte(),
            ((streamId ushr 16) and 0xFF).toByte(),
            ((streamId ushr 8) and 0xFF).toByte(),
            (streamId and 0xFF).toByte()
        )
        return header + payload
    }

    private fun buildNeutralizedBody(contentType: String): ByteArray {
        val normalized = contentType.lowercase()
        return when {
            normalized.contains("json") -> "{}".toByteArray(Charsets.UTF_8)
            normalized.contains("javascript") -> "/* blocked */".toByteArray(Charsets.UTF_8)
            normalized.contains("html") -> """<html><head><script>try{window.open=function(){return{closed:true};};navigator.sendBeacon=function(){return true;};}catch(e){}</script></head><body></body></html>""".toByteArray(Charsets.UTF_8)
            normalized.contains("image") -> TRANSPARENT_1X1_GIF
            normalized.contains("text") -> ByteArray(0)
            else -> ByteArray(0)
        }
    }

    private const val DEFAULT_HEADER_FRAME_PAYLOAD_SIZE = 8 * 1024
}
