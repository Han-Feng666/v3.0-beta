package com.HanFeng.core.network

import com.HanFeng.model.PacketInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

object LocalProxyBridgeConnectSupport {
    data class ConnectedSocket(
        val socket: Socket,
        val protocol: String
    )

    fun performSocks5Connect(socket: Socket, request: PacketInfo) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val methodReply = ByteArray(2)
        input.readFully(methodReply)
        check(methodReply[0].toInt() == 0x05 && methodReply[1].toInt() == 0x00) { "SOCKS5 method negotiation failed" }
        val addressType = if (request.version == 6) 0x04 else 0x01
        val connectRequest = ByteArray(4 + request.destinationAddress.size + 2)
        connectRequest[0] = 0x05
        connectRequest[1] = 0x01
        connectRequest[2] = 0x00
        connectRequest[3] = addressType.toByte()
        request.destinationAddress.copyInto(connectRequest, 4)
        val portOffset = 4 + request.destinationAddress.size
        connectRequest[portOffset] = ((request.destinationPort ushr 8) and 0xFF).toByte()
        connectRequest[portOffset + 1] = (request.destinationPort and 0xFF).toByte()
        output.write(connectRequest)
        output.flush()
        val header = ByteArray(4)
        input.readFully(header)
        check(header[0].toInt() == 0x05 && header[1].toInt() == 0x00) { "SOCKS5 connect failed code=${header[1].toInt() and 0xFF}" }
        val addressLength = when (header[3].toInt() and 0xFF) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> input.read().takeIf { it >= 0 } ?: throw IOException("SOCKS5 domain length missing")
            else -> throw IOException("SOCKS5 atyp unsupported")
        }
        val skip = ByteArray(addressLength + 2)
        input.readFully(skip)
    }

    fun performLocalProxyConnect(
        socket: Socket,
        request: PacketInfo,
        host: String,
        port: Int,
        timeoutMillis: Int,
        protect: (Socket) -> Boolean,
        onFallbackProtectFailed: () -> Unit
    ): ConnectedSocket {
        socket.soTimeout = timeoutMillis
        val socksError = runCatching {
            performSocks5Connect(socket, request)
        }.exceptionOrNull()
        if (socksError == null) return ConnectedSocket(socket = socket, protocol = "socks5")

        runCatching { socket.close() }
        val fallbackSocket = Socket()
        fallbackSocket.tcpNoDelay = true
        fallbackSocket.soTimeout = timeoutMillis
        if (!protect(fallbackSocket)) {
            onFallbackProtectFailed()
        }
        fallbackSocket.connect(InetSocketAddress(host, port), timeoutMillis)
        runCatching {
            performHttpConnect(fallbackSocket, request)
        }.onFailure {
            runCatching { fallbackSocket.close() }
            throw IOException(
                "Local proxy connect failed socks=${BridgeFailureSupport.formatFailureDetail(socksError)}, http=${BridgeFailureSupport.formatFailureDetail(it)}"
            )
        }
        return ConnectedSocket(socket = fallbackSocket, protocol = "http_connect")
    }

    fun performHttpConnect(socket: Socket, request: PacketInfo) {
        val host = formatAddress(request.destinationAddress)
        val connectRequest = buildString {
            append("CONNECT ")
            append(host)
            append(':')
            append(request.destinationPort)
            append(" HTTP/1.1\r\n")
            append("Host: ")
            append(host)
            append(':')
            append(request.destinationPort)
            append("\r\n")
            append("Proxy-Connection: Keep-Alive\r\n")
            append("Connection: Keep-Alive\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(connectRequest)
        output.flush()
        val responseBytes = input.readUntilHeaderTerminator(8192)
        val responseText = responseBytes.toString(Charsets.US_ASCII)
        val statusLine = responseText.lineSequence().firstOrNull()?.trim().orEmpty()
        check(statusLine.startsWith("HTTP/1.1 200") || statusLine.startsWith("HTTP/1.0 200")) {
            "HTTP CONNECT failed status=${statusLine.ifBlank { "unknown" }}"
        }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count <= 0) throw IOException("Unexpected EOF")
            offset += count
        }
    }

    private fun InputStream.readUntilHeaderTerminator(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < maxBytes) {
            val next = read()
            if (next < 0) throw IOException("Unexpected EOF")
            output.write(next)
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return output.toByteArray()
        }
        throw IOException("HTTP CONNECT header too large")
    }

    private fun formatAddress(address: ByteArray): String {
        return if (address.size == 4) {
            address.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            address.toList()
                .chunked(2)
                .joinToString(":") { chunk ->
                    val high = chunk.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
                    val low = chunk.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
                    ((high shl 8) or low).toString(16)
                }
        }
    }
}
