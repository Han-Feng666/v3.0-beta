package com.hanfeng.adblocker.capture

import com.HanFeng.service.Http2FrameCodec
import com.HanFeng.service.HpackDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Http2SyntheticRequestFrameTest {

    private fun decodeHeaders(headerBlock: ByteArray): List<HpackDecoder.HeaderField> {
        val result = HpackDecoder.decode(headerBlock, HpackDecoder.DecoderState())
        assertTrue("decode error: ${result.error}", result.error == null)
        return result.headers
    }

    private fun decodeAllFrames(bytes: ByteArray): List<Triple<Int, Int, ByteArray>> {
        val out = mutableListOf<Triple<Int, Int, ByteArray>>()
        var i = 0
        while (i + 9 <= bytes.size) {
            val length = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            val type = bytes[i + 3].toInt() and 0xFF
            val flags = bytes[i + 4].toInt() and 0xFF
            // streamId 4 字节, 略
            val payload = bytes.copyOfRange(i + 9, i + 9 + length)
            out += Triple(type, flags, payload)
            i += 9 + length
        }
        return out
    }

    @Test
    fun `buildSyntheticRequestFrames GET 空体 仅 HEADERS 且 endStream=true`() {
        val frames = Http2FrameCodec.buildSyntheticRequestFrames(
            streamId = 3,
            method = "GET",
            scheme = "https",
            authority = "api.example.com",
            path = "/v1/users",
            body = ByteArray(0)
        )
        val parsed = decodeAllFrames(frames)
        assertEquals(1, parsed.size)
        val (type, flags, payload) = parsed.first()
        assertEquals(1, type)
        assertTrue("END_STREAM expected", (flags and 0x1) != 0)
        assertTrue("END_HEADERS expected", (flags and 0x4) != 0)
        val hf = decodeHeaders(payload)
        assertEquals("GET", hf.first { it.name == ":method" }.value)
        assertEquals("https", hf.first { it.name == ":scheme" }.value)
        assertEquals("api.example.com", hf.first { it.name == ":authority" }.value)
        assertEquals("/v1/users", hf.first { it.name == ":path" }.value)
        assertTrue(hf.none { it.name.equals("content-length", true) })
    }

    @Test
    fun `buildSyntheticRequestFrames POST 带体 HEADERS + DATA endStream 在 DATA`() {
        val body = "ping=pong".toByteArray(Charsets.UTF_8)
        val frames = Http2FrameCodec.buildSyntheticRequestFrames(
            streamId = 5,
            method = "POST",
            scheme = "https",
            authority = "h.example.com",
            path = "/api",
            body = body
        )
        val parsed = decodeAllFrames(frames)
        assertEquals(2, parsed.size)
        val (hType, hFlags, hPayload) = parsed[0]
        val (dType, dFlags, dPayload) = parsed[1]
        assertEquals(1, hType)
        assertEquals(0, dType)
        assertTrue((hFlags and 0x4) != 0)
        assertTrue((hFlags and 0x1) == 0)
        assertTrue((dFlags and 0x1) != 0)
        assertEquals(body.size, dPayload.size)
        val hf = decodeHeaders(hPayload)
        assertEquals(body.size.toString(), hf.first { it.name.equals("content-length", true) }.value)
        assertEquals("POST", hf.first { it.name == ":method" }.value)
        assertEquals("/api", hf.first { it.name == ":path" }.value)
    }

    @Test
    fun `buildSyntheticRequestFrames extraHeaders 过滤 content-length 与 伪头叠放`() {
        val frames = Http2FrameCodec.buildSyntheticRequestFrames(
            streamId = 7,
            method = "PUT",
            scheme = "https",
            authority = "auth.exe.com",
            path = "/cfg",
            body = "x=1".toByteArray(Charsets.UTF_8),
            extraHeaders = listOf(
                "content-length" to "999",
                ":authority" to "evil.com",
                "accept" to "application/json",
                "x-trace" to "123"
            )
        )
        val parsed = decodeAllFrames(frames)
        assertEquals(2, parsed.size)
        val (_, _, hPayload) = parsed[0]
        val hf = decodeHeaders(hPayload)
        assertEquals("application/json", hf.first { it.name == "accept" }.value)
        assertEquals("123", hf.first { it.name == "x-trace" }.value)
        assertEquals("3", hf.first { it.name == "content-length" }.value)
        assertEquals("auth.exe.com", hf.single { it.name == ":authority" }.value)
        assertEquals(1, hf.count { it.name == ":authority" })
    }

    @Test
    fun `buildSyntheticRequestFrames 大体片段提交 总长度验和`() {
        val big = ByteArray(64) { 0x41 }
        val frames = Http2FrameCodec.buildSyntheticRequestFrames(
            streamId = 11,
            method = "POST",
            scheme = "https",
            authority = "bull.example.com",
            path = "/upload",
            body = big
        )
        val parsed = decodeAllFrames(frames)
        assertTrue(parsed.any { it.first == 1 })
        assertTrue(parsed.any { it.first == 0 })
        assertTrue((parsed.last().second and 0x1) != 0)
        // 提取 DATA payload 总和 == big.size
        val dataPayloadSum = parsed.filter { it.first == 0 }.sumOf { it.third.size }
        assertEquals(big.size, dataPayloadSum)
    }
}

