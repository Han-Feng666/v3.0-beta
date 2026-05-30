package com.HanFeng.service

import com.HanFeng.model.PacketInfo

object PacketCodec {
    private const val DEFAULT_TCP_WINDOW = 65535

    fun parse(packet: ByteArray): PacketInfo? {
        return parse(packet, packet.size)
    }

    fun parse(packet: ByteArray, length: Int): PacketInfo? {
        if (packet.isEmpty()) return null
        return when ((packet[0].toInt() ushr 4) and 0x0F) {
            4 -> parseIpv4(packet, length)
            6 -> parseIpv6(packet, length)
            else -> null
        }
    }

    fun buildUdpResponse(request: PacketInfo, responsePayload: ByteArray): ByteArray {
        return if (request.version == 4) buildIpv4UdpResponse(request, responsePayload) else buildIpv6UdpResponse(request, responsePayload)
    }

    fun buildTcpResponse(
        request: PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        windowSize: Int = DEFAULT_TCP_WINDOW
    ): ByteArray {
        return if (request.version == 4) {
            buildIpv4TcpResponse(request, sequenceNumber, acknowledgementNumber, flags, payload, windowSize)
        } else {
            buildIpv6TcpResponse(request, sequenceNumber, acknowledgementNumber, flags, payload, windowSize)
        }
    }

    private fun parseIpv4(packet: ByteArray, length: Int): PacketInfo? {
        if (length < 20) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl) return null
        val fragmentOffset = ((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF)
        if (fragmentOffset != 0) return null
        val protocol = packet[9].toInt() and 0xFF
        val src = packet.copyOfRange(12, 16)
        val dst = packet.copyOfRange(16, 20)
        val transport = packet.copyOfRange(ihl, length)
        return when (protocol) {
            17 -> {
                if (transport.size < 8) return null
                PacketInfo(4, src, dst, protocol, readShort(transport, 0), readShort(transport, 2), transport.copyOfRange(8, transport.size))
            }
            6 -> {
                if (transport.size < 20) return null
                val dataOffset = ((transport[12].toInt() ushr 4) and 0x0F) * 4
                if (transport.size < dataOffset) return null
                PacketInfo(
                    version = 4,
                    sourceAddress = src,
                    destinationAddress = dst,
                    protocol = protocol,
                    sourcePort = readShort(transport, 0),
                    destinationPort = readShort(transport, 2),
                    payload = transport.copyOfRange(dataOffset, transport.size),
                    tcpSequenceNumber = readInt(transport, 4),
                    tcpAcknowledgementNumber = readInt(transport, 8),
                    tcpFlags = transport[13].toInt() and 0x3F,
                    tcpWindowSize = readShort(transport, 14)
                )
            }
            else -> PacketInfo(4, src, dst, protocol, 0, 0, ByteArray(0))
        }
    }

    private fun parseIpv6(packet: ByteArray, length: Int): PacketInfo? {
        if (length < 40) return null
        val src = packet.copyOfRange(8, 24)
        val dst = packet.copyOfRange(24, 40)
        val transportInfo = parseIpv6Transport(packet, length) ?: return null
        val nextHeader = transportInfo.nextHeader
        val transport = transportInfo.payload
        return when (nextHeader) {
            17 -> {
                if (transport.size < 8) return null
                PacketInfo(6, src, dst, nextHeader, readShort(transport, 0), readShort(transport, 2), transport.copyOfRange(8, transport.size))
            }
            6 -> {
                if (transport.size < 20) return null
                val dataOffset = ((transport[12].toInt() ushr 4) and 0x0F) * 4
                if (transport.size < dataOffset) return null
                PacketInfo(
                    version = 6,
                    sourceAddress = src,
                    destinationAddress = dst,
                    protocol = nextHeader,
                    sourcePort = readShort(transport, 0),
                    destinationPort = readShort(transport, 2),
                    payload = transport.copyOfRange(dataOffset, transport.size),
                    tcpSequenceNumber = readInt(transport, 4),
                    tcpAcknowledgementNumber = readInt(transport, 8),
                    tcpFlags = transport[13].toInt() and 0x3F,
                    tcpWindowSize = readShort(transport, 14)
                )
            }
            else -> PacketInfo(6, src, dst, nextHeader, 0, 0, ByteArray(0))
        }
    }

    private fun parseIpv6Transport(packet: ByteArray, length: Int): Ipv6TransportInfo? {
        var nextHeader = packet[6].toInt() and 0xFF
        var offset = 40
        while (true) {
            when (nextHeader) {
                0, 43, 60 -> {
                    if (offset + 2 > length) return null
                    val headerLength = ((packet[offset + 1].toInt() and 0xFF) + 1) * 8
                    if (offset + headerLength > length) return null
                    nextHeader = packet[offset].toInt() and 0xFF
                    offset += headerLength
                }
                44 -> {
                    if (offset + 8 > length) return null
                    val fragmentOffset = ((packet[offset + 2].toInt() and 0xFF) shl 5) or ((packet[offset + 3].toInt() and 0xF8) ushr 3)
                    if (fragmentOffset != 0) return null
                    nextHeader = packet[offset].toInt() and 0xFF
                    offset += 8
                }
                51 -> {
                    if (offset + 2 > length) return null
                    val headerLength = ((packet[offset + 1].toInt() and 0xFF) + 2) * 4
                    if (offset + headerLength > length) return null
                    nextHeader = packet[offset].toInt() and 0xFF
                    offset += headerLength
                }
                50 -> {
                    return null
                }
                else -> {
                    if (offset > length) return null
                    return Ipv6TransportInfo(nextHeader = nextHeader, payload = packet.copyOfRange(offset, length))
                }
            }
        }
    }

    private fun buildIpv4UdpResponse(request: PacketInfo, responsePayload: ByteArray): ByteArray {
        val totalLength = 20 + 8 + responsePayload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[1] = 0
        writeShort(packet, 2, totalLength)
        writeShort(packet, 4, 0)
        writeShort(packet, 6, 0)
        packet[8] = 64
        packet[9] = 17
        request.destinationAddress.copyInto(packet, 12)
        request.sourceAddress.copyInto(packet, 16)
        writeShort(packet, 20, request.destinationPort)
        writeShort(packet, 22, request.sourcePort)
        writeShort(packet, 24, 8 + responsePayload.size)
        writeShort(packet, 26, 0)
        responsePayload.copyInto(packet, 28)
        val ipChecksum = checksum(packet, 0, 20)
        writeShort(packet, 10, ipChecksum)
        val udpChecksum = udpChecksumIpv4(packet, responsePayload.size)
        writeShort(packet, 26, udpChecksum)
        return packet
    }

    private fun buildIpv6UdpResponse(request: PacketInfo, responsePayload: ByteArray): ByteArray {
        val payloadLength = 8 + responsePayload.size
        val packet = ByteArray(40 + payloadLength)
        packet[0] = 0x60
        writeShort(packet, 4, payloadLength)
        packet[6] = 17
        packet[7] = 64
        request.destinationAddress.copyInto(packet, 8)
        request.sourceAddress.copyInto(packet, 24)
        writeShort(packet, 40, request.destinationPort)
        writeShort(packet, 42, request.sourcePort)
        writeShort(packet, 44, payloadLength)
        writeShort(packet, 46, 0)
        responsePayload.copyInto(packet, 48)
        val udpChecksum = udpChecksumIpv6(packet, responsePayload.size)
        writeShort(packet, 46, udpChecksum)
        return packet
    }

    private fun buildIpv4TcpResponse(
        request: PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        flags: Int,
        payload: ByteArray,
        windowSize: Int
    ): ByteArray {
        val tcpHeaderLength = 20
        val totalLength = 20 + tcpHeaderLength + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[1] = 0
        writeShort(packet, 2, totalLength)
        writeShort(packet, 4, 0)
        writeShort(packet, 6, 0)
        packet[8] = 64
        packet[9] = 6
        request.destinationAddress.copyInto(packet, 12)
        request.sourceAddress.copyInto(packet, 16)
        val tcpOffset = 20
        writeShort(packet, tcpOffset, request.destinationPort)
        writeShort(packet, tcpOffset + 2, request.sourcePort)
        writeInt(packet, tcpOffset + 4, sequenceNumber)
        writeInt(packet, tcpOffset + 8, acknowledgementNumber)
        packet[tcpOffset + 12] = (5 shl 4).toByte()
        packet[tcpOffset + 13] = flags.toByte()
        writeShort(packet, tcpOffset + 14, windowSize)
        writeShort(packet, tcpOffset + 16, 0)
        writeShort(packet, tcpOffset + 18, 0)
        payload.copyInto(packet, tcpOffset + tcpHeaderLength)
        writeShort(packet, 10, checksum(packet, 0, 20))
        writeShort(packet, tcpOffset + 16, tcpChecksumIpv4(packet, payload.size))
        return packet
    }

    private fun buildIpv6TcpResponse(
        request: PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        flags: Int,
        payload: ByteArray,
        windowSize: Int
    ): ByteArray {
        val tcpHeaderLength = 20
        val payloadLength = tcpHeaderLength + payload.size
        val packet = ByteArray(40 + payloadLength)
        packet[0] = 0x60
        writeShort(packet, 4, payloadLength)
        packet[6] = 6
        packet[7] = 64
        request.destinationAddress.copyInto(packet, 8)
        request.sourceAddress.copyInto(packet, 24)
        val tcpOffset = 40
        writeShort(packet, tcpOffset, request.destinationPort)
        writeShort(packet, tcpOffset + 2, request.sourcePort)
        writeInt(packet, tcpOffset + 4, sequenceNumber)
        writeInt(packet, tcpOffset + 8, acknowledgementNumber)
        packet[tcpOffset + 12] = (5 shl 4).toByte()
        packet[tcpOffset + 13] = flags.toByte()
        writeShort(packet, tcpOffset + 14, windowSize)
        writeShort(packet, tcpOffset + 16, 0)
        writeShort(packet, tcpOffset + 18, 0)
        payload.copyInto(packet, tcpOffset + tcpHeaderLength)
        writeShort(packet, tcpOffset + 16, tcpChecksumIpv6(packet, payload.size))
        return packet
    }

    private fun udpChecksumIpv4(packet: ByteArray, payloadSize: Int): Int {
        val pseudo = ByteArray(12 + 8 + payloadSize)
        packet.copyOfRange(12, 20).copyInto(pseudo, 0)
        pseudo[8] = 0
        pseudo[9] = 17
        writeShort(pseudo, 10, 8 + payloadSize)
        packet.copyOfRange(20, packet.size).copyInto(pseudo, 12)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun udpChecksumIpv6(packet: ByteArray, payloadSize: Int): Int {
        val pseudo = ByteArray(40 + 8 + payloadSize)
        packet.copyOfRange(8, 24).copyInto(pseudo, 0)
        packet.copyOfRange(24, 40).copyInto(pseudo, 16)
        pseudo[35] = (8 + payloadSize).toByte()
        pseudo[39] = 17
        packet.copyOfRange(40, packet.size).copyInto(pseudo, 40)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun tcpChecksumIpv4(packet: ByteArray, payloadSize: Int): Int {
        val tcpLength = 20 + payloadSize
        val pseudo = ByteArray(12 + tcpLength)
        packet.copyOfRange(12, 20).copyInto(pseudo, 0)
        pseudo[8] = 0
        pseudo[9] = 6
        writeShort(pseudo, 10, tcpLength)
        packet.copyOfRange(20, 20 + tcpLength).copyInto(pseudo, 12)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun tcpChecksumIpv6(packet: ByteArray, payloadSize: Int): Int {
        val tcpLength = 20 + payloadSize
        val pseudo = ByteArray(40 + tcpLength)
        packet.copyOfRange(8, 24).copyInto(pseudo, 0)
        packet.copyOfRange(24, 40).copyInto(pseudo, 16)
        writeInt(pseudo, 32, tcpLength.toLong())
        pseudo[39] = 6
        packet.copyOfRange(40, 40 + tcpLength).copyInto(pseudo, 40)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        while (index < offset + length - 1) {
            sum += readShort(buffer, index)
            index += 2
        }
        if (length % 2 == 1) {
            sum += (buffer[offset + length - 1].toInt() and 0xFF shl 8).toLong()
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    private fun readInt(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private data class Ipv6TransportInfo(
        val nextHeader: Int,
        val payload: ByteArray
    )
}
