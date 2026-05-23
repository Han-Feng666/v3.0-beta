package com.HanFeng.security

object TlsClientHelloParser {
    private const val TLS_HANDSHAKE = 22
    private const val CLIENT_HELLO = 1
    private const val EXT_SERVER_NAME = 0
    private const val EXT_ALPN = 16
    private const val NAME_TYPE_HOST_NAME = 0

    fun extractClientHelloInfo(payload: ByteArray): ClientHelloInfo? {
        if (payload.size < 5) return null
        if ((payload[0].toInt() and 0xFF) != TLS_HANDSHAKE) return null
        val recordLength = readShort(payload, 3)
        if (payload.size < 5 + recordLength) return null
        var offset = 5
        if (offset + 4 > payload.size) return null
        if ((payload[offset].toInt() and 0xFF) != CLIENT_HELLO) return null
        val helloLength = readMedium(payload, offset + 1)
        offset += 4
        if (offset + helloLength > payload.size) return null
        val handshakeVersion = if (offset + 2 <= payload.size) {
            formatTlsVersion(readShort(payload, offset))
        } else {
            null
        }
        if (offset + 2 + 32 > payload.size) return null
        offset += 2 + 32
        if (offset + 1 > payload.size) return null
        val sessionIdLength = payload[offset].toInt() and 0xFF
        offset += 1
        if (offset + sessionIdLength > payload.size) return null
        offset += sessionIdLength
        if (offset + 2 > payload.size) return null
        val cipherSuitesLength = readShort(payload, offset)
        offset += 2
        if (offset + cipherSuitesLength > payload.size) return null
        offset += cipherSuitesLength
        if (offset + 1 > payload.size) return null
        val compressionMethodsLength = payload[offset].toInt() and 0xFF
        offset += 1
        if (offset + compressionMethodsLength > payload.size) return null
        offset += compressionMethodsLength
        if (offset + 2 > payload.size) return null
        val extensionsLength = readShort(payload, offset)
        offset += 2
        val extensionsEnd = offset + extensionsLength
        if (extensionsEnd > payload.size) return null
        var sniHost: String? = null
        val alpnProtocols = mutableListOf<String>()
        while (offset + 4 <= extensionsEnd) {
            val extensionType = readShort(payload, offset)
            val extensionLength = readShort(payload, offset + 2)
            offset += 4
            if (offset + extensionLength > extensionsEnd) return null
            when (extensionType) {
                EXT_SERVER_NAME -> sniHost = parseServerNameExtension(payload, offset, extensionLength)
                EXT_ALPN -> alpnProtocols += parseAlpnExtension(payload, offset, extensionLength)
            }
            offset += extensionLength
        }
        return ClientHelloInfo(
            sniHost = sniHost,
            offeredAlpnProtocols = alpnProtocols.distinct(),
            handshakeVersion = handshakeVersion
        )
    }

    fun extractSniHost(payload: ByteArray): String? {
        return extractClientHelloInfo(payload)?.sniHost
    }

    private fun parseServerNameExtension(payload: ByteArray, offset: Int, extensionLength: Int): String? {
        if (extensionLength < 5 || offset + extensionLength > payload.size) return null
        val listLength = readShort(payload, offset)
        var cursor = offset + 2
        val listEnd = cursor + listLength
        if (listEnd > offset + extensionLength) return null
        while (cursor + 3 <= listEnd) {
            val nameType = payload[cursor].toInt() and 0xFF
            val nameLength = readShort(payload, cursor + 1)
            cursor += 3
            if (cursor + nameLength > listEnd) return null
            if (nameType == NAME_TYPE_HOST_NAME) {
                return payload.copyOfRange(cursor, cursor + nameLength)
                    .toString(Charsets.UTF_8)
                    .trim()
                    .lowercase()
                    .takeIf { it.isNotBlank() }
            }
            cursor += nameLength
        }
        return null
    }

    private fun parseAlpnExtension(payload: ByteArray, offset: Int, extensionLength: Int): List<String> {
        if (extensionLength < 2 || offset + extensionLength > payload.size) return emptyList()
        val listLength = readShort(payload, offset)
        var cursor = offset + 2
        val listEnd = cursor + listLength
        if (listEnd > offset + extensionLength) return emptyList()
        val result = mutableListOf<String>()
        while (cursor < listEnd) {
            val protocolLength = payload[cursor].toInt() and 0xFF
            cursor += 1
            if (protocolLength <= 0 || cursor + protocolLength > listEnd) return emptyList()
            val protocol = payload.copyOfRange(cursor, cursor + protocolLength)
                .toString(Charsets.US_ASCII)
                .trim()
            if (protocol.isNotBlank()) {
                result += protocol
            }
            cursor += protocolLength
        }
        return result
    }

    private fun formatTlsVersion(rawVersion: Int): String {
        return when (rawVersion) {
            0x0301 -> "TLSv1.0"
            0x0302 -> "TLSv1.1"
            0x0303 -> "TLSv1.2"
            0x0304 -> "TLSv1.3"
            else -> "0x${rawVersion.toString(16)}"
        }
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    private fun readMedium(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            (buffer[offset + 2].toInt() and 0xFF)
    }

    data class ClientHelloInfo(
        val sniHost: String?,
        val offeredAlpnProtocols: List<String>,
        val handshakeVersion: String?
    )
}
