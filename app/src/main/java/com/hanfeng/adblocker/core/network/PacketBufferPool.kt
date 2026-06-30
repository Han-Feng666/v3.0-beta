package com.HanFeng.core.network

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

object PacketBufferPool {
    private const val BUFFER_SIZE = 32767
    private const val MAX_POOLED_BUFFERS = 8

    private val pool = ConcurrentLinkedQueue<ByteBuffer>()

    fun obtain(): ByteBuffer {
        val buffer = pool.poll()
        if (buffer != null) {
            buffer.clear()
            return buffer
        }
        return ByteBuffer.allocateDirect(BUFFER_SIZE)
    }

    fun recycle(buffer: ByteBuffer) {
        if (pool.size >= MAX_POOLED_BUFFERS) return
        buffer.clear()
        pool.offer(buffer)
    }
}
