package com.hanfeng.adblocker.capture

import com.HanFeng.service.Http2FrameCodec
import com.HanFeng.service.HpackDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 C 验证: H2 响应方向 [BreakpointAction.ReplaceWith] 命中时, HTBM 用
 * [Http2FrameCodec.buildSyntheticResponseFrames] 拼出"合成响应帧"; 本测试静态断言该帧字节结构:
 *
 * - 第一帧 type = 0x1 (HEADERS), flags 含 0x4 (END_HEADERS)
 * - 末帧 type = 0x0 (DATA), flags 含 0x1 (END_STREAM)
 * - HPACK 解码得 :status=200, content-length=<body 长度>, content-type=传入值
 * - extraHeaders 透传; content-length 由 buildSyntheticResponseFrames 自动重算 (设计文档 correctness 9)
 *
 * 对应 design correctness 9 + requirements R8.7。
 */
class H2ResponseReplaceWithFrameTest {

    @Test
    fun `buildSyntheticResponseFrames produces HEADERS+DATA with correct status and content-length`() {
        val streamId = 7
        val body = "hello h2".toByteArray()
        val bytes = Http2FrameCodec.buildSyntheticResponseFrames(
            streamId = streamId,
            status = 201,
            contentType = "application/json",
            body = body,
            extraHeaders = listOf("x-hanfeng-capture" to "replace")
        )

        var idx = 0
        val headersFrameEnd = readFrameLength(bytes, 0) + 9
        val typeHeader = bytes[3].toInt() and 0xFF
        val flagsHeader = bytes[4].toInt() and 0xFF
        assertEquals("first frame should be HEADERS (type 0x1)", 0x1, typeHeader)
        assertTrue("HEADERS frame should have END_HEADERS flag (0x4)", (flagsHeader and 0x4) != 0)
        assertEquals(streamId, readStreamId(bytes, 0))

        val dataFrameStart = headersFrameEnd
        val typeData = bytes[dataFrameStart + 3].toInt() and 0xFF
        val flagsData = bytes[dataFrameStart + 4].toInt() and 0xFF
        assertEquals("second frame should be DATA (type 0x0)", 0x0, typeData)
        assertTrue("DATA frame should have END_STREAM flag (0x1)", (flagsData and 0x1) != 0)

        val headerBlockLen = readFrameLength(bytes, 0)
        val headerBlock = bytes.copyOfRange(9, 9 + headerBlockLen)
        val decoded = HpackDecoder.decode(headerBlock, HpackDecoder.DecoderState()).headers
        val headers = decoded.associateBy({ it.name.lowercase() }, { it.value })
        assertEquals("201", headers[":status"])
        assertEquals(body.size.toString(), headers["content-length"])
        assertEquals("application/json", headers["content-type"])
        assertEquals("replace", headers["x-hanfeng-capture"])
    }

    @Test
    fun `empty body still produces END_STREAM on DATA frame`() {
        val bytes = Http2FrameCodec.buildSyntheticResponseFrames(
            streamId = 3, status = 204, contentType = "text/plain", body = ByteArray(0)
        )
        val typeHeader = bytes[3].toInt() and 0xFF
        val flagsHeader = bytes[4].toInt() and 0xFF
        assertEquals(0x1, typeHeader)
        val headersEnd = readFrameLength(bytes, 0) + 9
        val typeData = bytes[headersEnd + 3].toInt() and 0xFF
        val flagsData = bytes[headersEnd + 4].toInt() and 0xFF
        assertEquals(0x0, typeData)
        assertTrue((flagsData and 0x1) != 0)
        assertEquals(0, readFrameLength(bytes, headersEnd))
    }

    private fun readFrameLength(bytes: ByteArray, frameStart: Int): Int {
        return ((bytes[frameStart].toInt() and 0xFF) shl 16) or
            ((bytes[frameStart + 1].toInt() and 0xFF) shl 8) or
            (bytes[frameStart + 2].toInt() and 0xFF)
    }

    private fun readStreamId(bytes: ByteArray, frameStart: Int): Int {
        return ((bytes[frameStart + 5].toInt() and 0x7F) shl 24) or
            ((bytes[frameStart + 6].toInt() and 0xFF) shl 16) or
            ((bytes[frameStart + 7].toInt() and 0xFF) shl 8) or
            (bytes[frameStart + 8].toInt() and 0xFF)
    }
}
