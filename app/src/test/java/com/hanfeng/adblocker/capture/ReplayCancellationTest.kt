package com.hanfeng.adblocker.capture

import com.HanFeng.capture.CaptureReplayEngine
import com.HanFeng.capture.CaptureTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ReplayCancellationTest {

    private fun sampleTemplate(id: String): CaptureTemplate =
        CaptureTemplate(
            id = id,
            label = "test-$id",
            createdAt = System.currentTimeMillis(),
            method = "GET",
            scheme = "http",
            host = "127.0.0.1",
            path = "/$id",
            headers = emptyMap(),
            body = null
        )

    @Test
    fun `replayBatch 全部取消时返回全部 cancelled=true 且不引发任何网络`() {
        val token = AtomicBoolean(true) // 启动时即置位
        val templates = (0 until 3).map { sampleTemplate("t$it") }
        val results = CaptureReplayEngine.replayBatch(
            templates,
            intervalMs = 0L,
            cancellationToken = token
        )
        assertEquals(3, results.size)
        results.forEach { r ->
            assertFalse(r.success)
            assertTrue(r.cancelled)
            assertEquals("cancelled", r.errorMessage)
            assertEquals(0L, r.txnId)
        }
    }

    @Test
    fun `replayBatchStreaming 全部取消时 onProgress 收到 cancelled 列表`() {
        val token = AtomicBoolean(true)
        val templates = (0 until 5).map { sampleTemplate("s$it") }
        val seen = mutableListOf<Boolean>()
        CaptureReplayEngine.replayBatchStreaming(
            templates,
            intervalMs = 0L,
            onProgress = { _, _, r -> seen += r.cancelled },
            cancellationToken = token
        )
        assertEquals(5, seen.size)
        assertTrue(seen.all { it })
    }
}
