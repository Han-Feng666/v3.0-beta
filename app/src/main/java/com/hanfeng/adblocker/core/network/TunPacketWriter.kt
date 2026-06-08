package com.HanFeng.core.network

import java.io.FileOutputStream

class TunPacketWriter {
    private val writeLock = Any()

    fun write(output: FileOutputStream?, packet: ByteArray) {
        val stream = output ?: return
        synchronized(writeLock) {
            stream.write(packet)
        }
    }
}
