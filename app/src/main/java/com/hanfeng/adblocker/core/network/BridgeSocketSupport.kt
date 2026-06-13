package com.HanFeng.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object BridgeSocketSupport {
    fun createConnectedSocket(
        host: String,
        port: Int,
        timeoutMillis: Int,
        protect: (Socket) -> Boolean,
        onProtectFailed: () -> Unit
    ): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        if (!protect(socket)) {
            onProtectFailed()
            runCatching { socket.close() }
            throw IOException("Protect bridge socket failed")
        }
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        return socket
    }

    fun launchWriter(
        scope: CoroutineScope,
        payload: ByteArray,
        write: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        if (payload.isEmpty()) return
        scope.launch {
            runCatching { write() }
                .onFailure(onFailure)
        }
    }

    fun runReaderLoop(
        input: InputStream,
        bufferSize: Int,
        shouldContinue: () -> Boolean,
        onPayload: (ByteArray) -> Unit,
        onFailure: (Exception) -> Unit,
        onComplete: (resetSent: Boolean) -> Unit
    ) {
        val buffer = ByteArray(bufferSize)
        var resetSent = false
        while (shouldContinue()) {
            val count = try {
                input.read(buffer)
            } catch (error: Exception) {
                onFailure(error)
                resetSent = true
                break
            }
            if (count <= 0) break
            onPayload(buffer.copyOf(count))
        }
        onComplete(resetSent)
    }
}
