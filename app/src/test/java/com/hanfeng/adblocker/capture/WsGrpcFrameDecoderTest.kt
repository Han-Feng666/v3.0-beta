package com.hanfeng.adblocker.capture

import com.HanFeng.capture.WsGrpcFrameDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 C4: [WsGrpcFrameDecoder] 纯算法测试。
 * 引用 design requirements R4.5 / 批次 C4。
 */
class WsGrpcFrameDecoderTest {

    // -------------------- WebSocket --------------------

    @Test
    fun `text 帧 unmasked server-to-client 解码`() {
        // fin=1 opcode=0x1 len=5 unmasked "hello"
        val bytes = byteArrayOf(
            0x81.toByte(), 0x05,
            'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()
        )
        val r = WsGrpcFrameDecoder.decodeWebSocketFrames(bytes)
        assertNull(r.error)
        assertEquals(1, r.frames.size)
        val f = r.frames[0]
        assertTrue(f.fin)
        assertEquals(WsGrpcFrameDecoder.WsOpcode.TEXT, f.opcode)
        assertEquals(false, f.maskedFromClient)
        assertEquals("hello", String(f.payload, Charsets.UTF_8))
        assertEquals(bytes.size, r.totalConsumed)
    }

    @Test
    fun `text 帧 masked client-to-server 解码`() {
        // fin=1 opcode=0x1 len=5 mask=0x11223344 payload=m("hi")
        val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val plain = "hi".toByteArray()
        val masked = ByteArray(plain.size) { i -> (plain[i].toInt() xor mask[i % 4].toInt()).toByte() }
        val bytes = byteArrayOf(0x81.toByte(), (0x80 or 0x05).toByte()) + mask + masked
        val r = WsGrpcFrameDecoder.decodeWebSocketFrames(bytes)
        assertNull(r.error)
        assertEquals(1, r.frames.size)
        assertEquals("hi", String(r.frames[0].payload, Charsets.UTF_8))
        assertEquals(true, r.frames[0].maskedFromClient)
    }

    @Test
    fun `ext-len-16 帧`() {
        val payloadLen = 200
        val payload = ByteArray(payloadLen) { it.toByte() }
        val bytes = byteArrayOf(
            0x82.toByte(),
            126.toByte(),
            ((payloadLen ushr 8) and 0xFF).toByte(),
            (payloadLen and 0xFF).toByte()
        ) + payload
        val r = WsGrpcFrameDecoder.decodeWebSocketFrames(bytes)
        assertNull(r.error)
        assertEquals(1, r.frames.size)
        assertEquals(payloadLen, r.frames[0].payload.size)
        assertEquals(WsGrpcFrameDecoder.WsOpcode.BINARY, r.frames[0].opcode)
    }

    @Test
    fun `close 帧解析 code+reason`() {
        // fin=1 opcode=0x8 len=5 unmasked code=1000 "bye"
        val reason = "bye".toByteArray()
        val bytes = byteArrayOf(
            0x88.toByte(), (2 + reason.size).toByte(),
            0x03.toByte(), 0xE8.toByte()
        ) + reason
        val r = WsGrpcFrameDecoder.decodeWebSocketFrames(bytes)
        assertNull(r.error)
        assertEquals(WsGrpcFrameDecoder.WsOpcode.CLOSE, r.frames[0].opcode)
    }

    @Test
    fun `多帧流封到一个 buffer`() {
        val f1 = byteArrayOf(0x81.toByte(), 0x02, 'h'.code.toByte(), 'i'.code.toByte())
        val f2 = byteArrayOf(0x81.toByte(), 0x02, 'o'.code.toByte(), 'k'.code.toByte())
        val r = WsGrpcFrameDecoder.decodeWebSocketFrames(f1 + f2)
        assertNull(r.error)
        assertEquals(2, r.frames.size)
        assertEquals("hi", String(r.frames[0].payload))
        assertEquals("ok", String(r.frames[1].payload))
    }

    @Test
    fun `半帧 partial 标误并已收取前段不留`() {
        val f1 = byteArrayOf(0x81.toByte(), 0x02, 'h'.code.toByte(), 'i'.code.toByte())
        val partial = byteArrayOf(0x81.toByte(), 0x05, 'o'.code.toByte()) // len=5 但只有 1 字节 payload
        val r = WsGrpcFrameDecoder.decodeWebSocketFrames(f1 + partial)
        assertEquals(1, r.frames.size)
        assertEquals("hi", String(r.frames[0].payload))
        assertTrue(r.error!!.startsWith("partial:"))
        assertTrue(r.error!!.contains("truncated payload"))
    }

    @Test
    fun `renderWsFramesPretty text 帧 ASCII 安全`() {
        val fr = WsGrpcFrameDecoder.WsFrame(true, WsGrpcFrameDecoder.WsOpcode.TEXT, "你好".toByteArray(), false, 0)
        val s = WsGrpcFrameDecoder.renderWsFramesPretty(listOf(fr))
        assertTrue(s.contains("你好"))
    }

    @Test
    fun `renderWsFramesPretty binary 帧 hex 表示`() {
        val fr = WsGrpcFrameDecoder.WsFrame(true, WsGrpcFrameDecoder.WsOpcode.BINARY, byteArrayOf(0x00.toByte(), 0xFF.toByte(), 0x7F.toByte()), false, 0)
        val s = WsGrpcFrameDecoder.renderWsFramesPretty(listOf(fr))
        assertTrue(s.contains("00 ff 7f"))
    }

    // -------------------- gRPC --------------------

    @Test
    fun `gRPC uncompressed 单帧`() {
        val msg = "hello grpc".toByteArray()
        val header = byteArrayOf(
            0,
            ((msg.size ushr 24) and 0xFF).toByte(),
            ((msg.size ushr 16) and 0xFF).toByte(),
            ((msg.size ushr 8) and 0xFF).toByte(),
            (msg.size and 0xFF).toByte()
        )
        val r = WsGrpcFrameDecoder.decodeGrpcFrames(header + msg)
        assertNull(r.error)
        assertEquals(1, r.frames.size)
        assertEquals(false, r.frames[0].compressed)
        assertEquals("hello grpc", String(r.frames[0].message))
    }

    @Test
    fun `gRPC 多帧 + partial 末段留 error`() {
        val msg1 = " AAA".toByteArray()
        val msg2 = " B".toByteArray()
        fun hdr(c: Byte, size: Int) = byteArrayOf(c, (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte())
        val r = WsGrpcFrameDecoder.decodeGrpcFrames(hdr(0, msg1.size) + msg1 + hdr(0, msg2.size) + msg2)
        assertNull(r.error)
        assertEquals(2, r.frames.size)
        val part = byteArrayOf(0, 0, 0, 1)
        val r2 = WsGrpcFrameDecoder.decodeGrpcFrames(hdr(0, msg1.size) + msg1 + part)
        assertEquals(1, r2.frames.size)
        assertTrue(r2.error!!.startsWith("partial:"))
        assertTrue(r2.error!!.contains("truncated header"))
    }

    @Test
    fun `gRPC len 超限报错`() {
        val evil = byteArrayOf(0, 0x10, 0, 0, 0)
        val r = WsGrpcFrameDecoder.decodeGrpcFrames(evil)
        assertEquals(0, r.frames.size)
        assertTrue(r.error!!.startsWith("grpc-len-out-of-range"))
    }
}
