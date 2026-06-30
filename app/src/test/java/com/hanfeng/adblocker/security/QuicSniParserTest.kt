package com.HanFeng.security

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

class QuicSniParserTest {

    @Test
    fun `varint 1-byte encodes 0-63`() {
        val buf = byteArrayOf(0x00)
        val result = readVarInt(buf, 0)
        assertNotNull(result)
        assertEquals(0L, result!!.first)
        assertEquals(1, result.second)
    }

    @Test
    fun `varint 1-byte encodes value 63`() {
        val buf = byteArrayOf(0x3F.toByte())
        val result = readVarInt(buf, 0)
        assertNotNull(result)
        assertEquals(63L, result!!.first)
        assertEquals(1, result.second)
    }

    @Test
    fun `varint 2-byte encodes value 0x3FFF`() {
        val buf = byteArrayOf(0x7F.toByte(), 0xFF.toByte())
        val result = readVarInt(buf, 0)
        assertNotNull(result)
        assertEquals(16383L, result!!.first)
        assertEquals(2, result.second)
    }

    @Test
    fun `varint 4-byte encodes large value`() {
        val value = 0x3FFFFFFF
        val buf = byteArrayOf(
            0xBF.toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        val result = readVarInt(buf, 0)
        assertNotNull(result)
        assertEquals(value.toLong(), result!!.first)
        assertEquals(4, result.second)
    }

    @Test
    fun `varint 8-byte encodes max value`() {
        val value = 0x3FFFFFFFFFFFFFFFL
        val buf = byteArrayOf(
            0xFF.toByte(),
            ((value shr 48) and 0xFF).toByte(),
            ((value shr 40) and 0xFF).toByte(),
            ((value shr 32) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        val result = readVarInt(buf, 0)
        assertNotNull(result)
        assertEquals(value, result!!.first)
        assertEquals(8, result.second)
    }

    @Test
    fun `varint returns null for empty buffer`() {
        assertNull(readVarInt(ByteArray(0), 0))
    }

    @Test
    fun `varint returns null for offset past buffer`() {
        assertNull(readVarInt(ByteArray(1), 2))
    }

    @Test
    fun `varint returns null for truncated 2-byte encoding`() {
        val buf = byteArrayOf(0x7F.toByte())
        assertNull(readVarInt(buf, 0))
    }

    @Test
    fun `QUIC v1 version constant is correct`() {
        assertEquals(0x00000001, findPrivateIntField("QUIC_V1"))
    }

    @Test
    fun `QUIC v2 version constant is correct`() {
        assertEquals(0x6b3343cf.toInt(), findPrivateIntField("QUIC_V2"))
    }

    @Test
    fun `QUIC key derivation produces deterministic result`() {
        val dcid = byteArrayOf(
            0x83.toByte(), 0x94.toByte(), 0xc8.toByte(), 0xf0.toByte(),
            0x3e.toByte(), 0x51.toByte(), 0x57.toByte(), 0x08.toByte()
        )
        val keys1 = deriveInitialKeys(dcid)
        val keys2 = deriveInitialKeys(dcid)
        assertArrayEquals(getKeyBytes(keys1), getKeyBytes(keys2))
        assertArrayEquals(getIvBytes(keys1), getIvBytes(keys2))
        assertArrayEquals(getHpBytes(keys1), getHpBytes(keys2))
        assertEquals(16, getKeyBytes(keys1).size)
        assertEquals(12, getIvBytes(keys1).size)
        assertEquals(16, getHpBytes(keys1).size)
    }

    @Test
    fun `key cache returns same keys for same DCID`() {
        val dcid = byteArrayOf(
            0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte(),
            0x9a.toByte(), 0xbc.toByte(), 0xde.toByte(), 0xf0.toByte()
        )
        val keys1 = getCachedKeys(dcid)
        val keys2 = getCachedKeys(dcid)
        assertArrayEquals(getKeyBytes(keys1), getKeyBytes(keys2))
        assertArrayEquals(getIvBytes(keys1), getIvBytes(keys2))
        assertArrayEquals(getHpBytes(keys1), getHpBytes(keys2))
    }

    @Test
    fun `key cache returns different keys for different DCIDs`() {
        val dcid1 = byteArrayOf(
            0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(),
            0x05.toByte(), 0x06.toByte(), 0x07.toByte(), 0x08.toByte()
        )
        val dcid2 = byteArrayOf(
            0x08.toByte(), 0x07.toByte(), 0x06.toByte(), 0x05.toByte(),
            0x04.toByte(), 0x03.toByte(), 0x02.toByte(), 0x01.toByte()
        )
        val keys1 = getCachedKeys(dcid1)
        val keys2 = getCachedKeys(dcid2)
        assertFalse(getKeyBytes(keys1).contentEquals(getKeyBytes(keys2)))
        assertFalse(getIvBytes(keys1).contentEquals(getIvBytes(keys2)))
        assertFalse(getHpBytes(keys1).contentEquals(getHpBytes(keys2)))
    }

    @Test
    fun `extractSniHost returns null for empty payload`() {
        assertNull(QuicSniParser.extractSniHost(ByteArray(0)))
        assertNull(QuicSniParser.extractSniHost(ByteArray(10)))
    }

    @Test
    fun `extractSniHost returns null for non-QUIC data`() {
        val nonQuic = ByteArray(100) { it.toByte() }
        assertNull(QuicSniParser.extractSniHost(nonQuic))
    }

    @Test
    fun `extractSniHost returns null for TCP TLS ClientHello`() {
        val tlsPayload = byteArrayOf(
            0x16.toByte(), 0x03.toByte(), 0x01.toByte(), // TLS record
            0x00.toByte(), 0x20.toByte(), // length
            0x01.toByte() // handshake type
        ) + ByteArray(32)
        assertNull(QuicSniParser.extractSniHost(tlsPayload))
    }

    @Test
    fun `QUIC short header returns null`() {
        val shortHeader = ByteArray(50) { 0x00 }
        shortHeader[0] = 0x40.toByte() // short header (no long header bit)
        assertNull(QuicSniParser.extractSniHost(shortHeader))
    }

    @Test
    fun `QUIC version negotiation returns no SNI`() {
        val pkt = buildMinimalQuicInitialHeader(version = 0x00000000, dcidLen = 8)
        assertNull(QuicSniParser.extractSniHost(pkt))
    }

    @Test
    fun `QUIC v1 with malformed crypto data returns null`() {
        val pkt = buildMinimalQuicInitialHeader(version = 0x00000001, dcidLen = 8)
        assertNull(QuicSniParser.extractSniHost(pkt))
    }

    @Test
    fun `QUIC v2 packet passes version check but fails decrypt gracefully`() {
        val pkt = buildMinimalQuicInitialHeader(version = 0x6b3343cf.toInt(), dcidLen = 8)
        assertNull(QuicSniParser.extractSniHost(pkt))
    }

    // --- Reflection-based helpers to access private methods for testing ---

    @Suppress("UNCHECKED_CAST")
    private fun readVarInt(buffer: ByteArray, offset: Int): Pair<Long, Int>? {
        return invokePrivateMethod("readVarInt", buffer, offset) as? Pair<Long, Int>
    }

    private fun deriveInitialKeys(dcid: ByteArray): Any {
        return invokePrivateMethod("deriveInitialKeys", dcid)!!
    }

    private fun getCachedKeys(dcid: ByteArray): Any {
        return invokePrivateMethod("getCachedKeys", dcid)!!
    }

    private fun findPrivateIntField(name: String): Int {
        val field = QuicSniParser::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getInt(QuicSniParser)
    }

    private fun invokePrivateMethod(name: String, vararg args: Any): Any? {
        val method: Method = QuicSniParser::class.java.declaredMethods
            .first { it.name == name && it.parameterTypes.size == args.size }
        method.isAccessible = true
        return method.invoke(QuicSniParser, *args)
    }

    private fun getFieldValue(obj: Any, name: String): Any? {
        val field: Field = obj::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(obj)
    }

    private fun getKeyBytes(quicKeys: Any): ByteArray = getFieldValue(quicKeys, "key") as ByteArray

    private fun getIvBytes(quicKeys: Any): ByteArray = getFieldValue(quicKeys, "iv") as ByteArray

    private fun getHpBytes(quicKeys: Any): ByteArray = getFieldValue(quicKeys, "hp") as ByteArray

    private fun buildMinimalQuicInitialHeader(version: Int, dcidLen: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(0xC0) // Initial packet, pkt_num_len=0
        buf.write(byteArrayOf(
            ((version shr 24) and 0xFF).toByte(),
            ((version shr 16) and 0xFF).toByte(),
            ((version shr 8) and 0xFF).toByte(),
            (version and 0xFF).toByte()
        ))
        buf.write(dcidLen)
        buf.write(ByteArray(dcidLen))
        buf.write(0) // scidLen=0
        buf.write(0) // tokenLen=0
        buf.write(20 + 16) // payloadLen=36 (minimum for AEAD tag)
        buf.write(ByteArray(36)) // encrypted payload + auth tag
        return buf.toByteArray()
    }
}
