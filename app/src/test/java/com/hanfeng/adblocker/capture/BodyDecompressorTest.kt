package com.hanfeng.adblocker.capture

import com.HanFeng.capture.BodyDecompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.DeflaterOutputStream

class BodyDecompressorTest {

    @Test
    fun `identity 编码 null 与空 表示未解压`() {
        val src = "hello".toByteArray()
        val r = BodyDecompressor.decompress(src, null)
        assertEquals("identity", r.encoding)
        assertFalse(r.decompressed)
        assertNull(r.error)
        assertEquals(src.size, r.body.size)
    }

    @Test
    fun `identity 编码字面值未解压`() {
        val src = "hello".toByteArray()
        val r = BodyDecompressor.decompress(src, "identity")
        assertFalse(r.decompressed)
        assertEquals("identity", r.encoding)
    }

    @Test
    fun `gzip 解压成功`() {
        val text = "HanFeng Capture gzip body sample".repeat(50)
        val src = ByteArrayOutputStream().also { ba ->
            GZIPOutputStream(ba).use { it.write(text.toByteArray()) }
        }.toByteArray()
        val r = BodyDecompressor.decompress(src, "gzip")
        assertTrue(r.decompressed)
        assertEquals("gzip", r.encoding)
        assertEquals(text, String(r.body, Charsets.UTF_8))
        assertNull(r.error)
    }

    @Test
    fun `gzip 解压失败保留原字节并报错`() {
        val fake = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00) + ByteArray(20) { 0x55 }
        val r = BodyDecompressor.decompress(fake, "gzip")
        assertFalse(r.decompressed)
        assertEquals("gzip", r.encoding)
        assertEquals(fake.size, r.body.size)
        assertTrue(r.error != null)
    }

    @Test
    fun `deflate 解压成功`() {
        val text = "HanFeng deflate sample ".repeat(50)
        val src = ByteArrayOutputStream().also { ba ->
            DeflaterOutputStream(ba).use { it.write(text.toByteArray()) }
        }.toByteArray()
        val r = BodyDecompressor.decompress(src, "deflate")
        assertTrue(r.decompressed)
        assertEquals("deflate", r.encoding)
        assertEquals(text, String(r.body, Charsets.UTF_8))
    }

    @Test
    fun `大小写不敏感 gzip 以及前导空白`() {
        val text = "ok"
        val src = ByteArrayOutputStream().also { ba ->
            GZIPOutputStream(ba).use { it.write(text.toByteArray()) }
        }.toByteArray()
        val r = BodyDecompressor.decompress(src, "  GZIP ")
        assertTrue(r.decompressed)
        assertEquals("gzip", r.encoding)
    }

    @Test
    fun `未知编码走未解压路径`() {
        val src = "raw".toByteArray()
        val r = BodyDecompressor.decompress(src, "bob")
        assertFalse(r.decompressed)
        assertEquals("bob", r.encoding)
        assertNull(r.error)
        assertEquals(src.size, r.body.size)
    }

    @Test
    fun `多层编码以第一段为准`() {
        val text = "ok"
        val src = ByteArrayOutputStream().also { ba ->
            GZIPOutputStream(ba).use { it.write(text.toByteArray()) }
        }.toByteArray()
        // 末尾逗号空格不视为下一编码, 第一段 gzip 仍然主导
        val r = BodyDecompressor.decompress(src, "gzip,")
        assertEquals("gzip", r.encoding)
        assertTrue(r.decompressed)
    }
}
