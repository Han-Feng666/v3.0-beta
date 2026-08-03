package com.hanfeng.adblocker.capture

import com.HanFeng.capture.CaptureEntry
import com.HanFeng.capture.CaptureRingBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureRingBufferTest {

    private lateinit var buffer: CaptureRingBuffer

    @Before
    fun setUp() {
        buffer = CaptureRingBuffer(initialCapacity = 3)
    }

    @After
    fun tearDown() {
        buffer.clear()
    }

    private fun newEntry(txnId: Long, host: String = "h$txnId.example.com"): CaptureEntry {
        return CaptureEntry(
            txnId = txnId,
            timestampMs = txnId * 1000L,
            appName = "test",
            packageName = "com.test",
            scheme = "https",
            method = "GET",
            host = host,
            path = "/v$txnId",
            httpVersion = "HTTP/1.1",
            requestHeaders = emptyMap(),
            requestBodyPreview = null,
            requestBodyTruncated = false,
            responseStatus = 0,
            responseHeaders = emptyMap(),
            responseBodyPreview = null,
            responseBodyTruncated = false,
            durationMs = 0
        )
    }

    @Test
    fun `putRequest 在空 buffer 内立即出现 head`() {
        val entry = newEntry(1L)
        buffer.putRequest(entry)
        assertEquals(1, buffer.size())
        assertEquals(entry, buffer.get(1L))
        assertEquals(entry, buffer.snapshot().first())
    }

    @Test
    fun `超过 capacity 时丢弃最旧条目 Tail`() {
        buffer.putRequest(newEntry(1L))
        buffer.putRequest(newEntry(2L))
        buffer.putRequest(newEntry(3L))
        // 此时满(容量3),再插会淘汰 txnId=1
        buffer.putRequest(newEntry(4L))

        assertEquals(3, buffer.size())
        assertNull(buffer.get(1L))
        assertNotNull(buffer.get(2L))
        assertNotNull(buffer.get(3L))
        assertNotNull(buffer.get(4L))
        // snapshot 顺序 = 最新→最旧, 最新是 4
        assertEquals(4L, buffer.snapshot().first().txnId)
        assertEquals(2L, buffer.snapshot().last().txnId)
    }

    @Test
    fun `putResponse 在已存在 txnId 上补全响应字段`() {
        buffer.putRequest(newEntry(10L))
        buffer.putResponse(
            txnId = 10L,
            responseStatus = 200,
            responseHeaders = mapOf("Content-Type" to "application/json"),
            responseBodyPreview = "{\"k\":1}".toByteArray(),
            responseBodyTruncated = false,
            durationMs = 142L,
            intercepted = false
        )

        val updated = buffer.get(10L)
        assertNotNull(updated)
        assertEquals(200, updated!!.responseStatus)
        assertEquals(142L, updated.durationMs)
        assertEquals("application/json", updated.responseHeaders["Content-Type"])
        assertTrue(updated.isComplete)
    }

    @Test
    fun `putResponse 命中已被淘汰的 txnId 时静默无变化`() {
        buffer.putRequest(newEntry(1L))
        buffer.putRequest(newEntry(2L))
        buffer.putRequest(newEntry(3L))
        buffer.putRequest(newEntry(4L)) // 淘汰 1L

        buffer.putResponse(
            txnId = 1L,
            responseStatus = 200,
            responseHeaders = emptyMap(),
            responseBodyPreview = null,
            responseBodyTruncated = false,
            durationMs = 0,
            intercepted = false
        )
        // 不会重新出现 1L
        assertNull(buffer.get(1L))
        assertEquals(3, buffer.size())
    }

    @Test
    fun `clear 释放全部条目和索引`() {
        buffer.putRequest(newEntry(1L))
        buffer.putRequest(newEntry(2L))
        buffer.clear()
        assertEquals(0, buffer.size())
        assertNull(buffer.get(1L))
        assertNull(buffer.get(2L))
    }

    @Test
    fun `trimToLowMemory 把容量减半并淘汰最旧条目`() {
        val b = CaptureRingBuffer(initialCapacity = 200)
        repeat(200) { b.putRequest(newEntry(it.toLong())) }
        assertEquals(200, b.size())

        b.trimToLowMemory()
        // 容量减半 + 淘汰超出部分
        assertEquals(100, b.capacity)
        assertTrue(b.size() <= 100)
        // 旧 txnId 应被淘汰, 较新的应保留(最大 txnId = 199 应在)
        assertNotNull(b.get(199L))
        assertNull(b.get(5L)) // 较旧
    }

    @Test
    fun `setCapacity 低于当前条目数时同步淘汰超出部分`() {
        buffer.putRequest(newEntry(1L))
        buffer.putRequest(newEntry(2L))
        buffer.putRequest(newEntry(3L))
        buffer.setCapacity(2)
        assertEquals(2, buffer.capacity)
        assertEquals(2, buffer.size())
        assertNotNull(buffer.get(3L))
        assertNotNull(buffer.get(2L))
        assertNull(buffer.get(1L))
    }

    @Test
    fun `putRequest 对同 txnId 不重写已有 entry`() {
        val first = newEntry(1L, host = "first.example.com")
        buffer.putRequest(first)
        // 同 txnId 再调一次,本次 body 不同
        val second = newEntry(1L, host = "second.example.com")
        buffer.putRequest(second)
        // 易出错: 不应被覆盖
        assertEquals(1, buffer.size())
        assertEquals("first.example.com", buffer.get(1L)?.host)
    }

    @Test
    fun `txnId 命名空间隔离 - makeReplayTxnId 不与正常 txnId 冲突`() {
        // 正常 txnId 从 nextTxnId 自增, 数量级小; 重放 txnId 高位带 (1L shl 60) 前缀
        val normal = buffer.nextTxnId()
        val replay = CaptureEntry.makeReplayTxnId(1L)
        assertTrue(replay > normal)
        assertTrue(replay shr 60 == 1L)
    }
}
