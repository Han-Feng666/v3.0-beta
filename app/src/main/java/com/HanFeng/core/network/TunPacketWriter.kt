package com.HanFeng.core.network

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class TunPacketWriter {
    private val writeLock = Any()

    fun write(output: FileOutputStream?, packet: ByteArray) {
        val stream = output ?: return
        synchronized(writeLock) {
            stream.write(packet)
        }
    }

    fun write(output: FileOutputStream?, buffer: ByteBuffer) {
        val stream = output ?: return
        val channel = stream.channel
        synchronized(writeLock) {
            buffer.flip()
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }
    }
}
