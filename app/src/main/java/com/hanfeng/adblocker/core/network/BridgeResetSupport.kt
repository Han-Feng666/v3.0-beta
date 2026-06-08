package com.HanFeng.core.network

import com.HanFeng.model.PacketInfo
import com.HanFeng.service.PacketCodec

object BridgeResetSupport {
    fun buildResetPacket(
        request: PacketInfo,
        packetState: BridgeReaderSupport.ResetPacketState,
        rstFlag: Int,
        ackFlag: Int,
        windowSize: Int
    ): ByteArray {
        return PacketCodec.buildTcpResponse(
            request = request,
            sequenceNumber = packetState.nextServerSequence,
            acknowledgementNumber = packetState.clientAcknowledgement,
            flags = rstFlag or ackFlag,
            windowSize = windowSize
        )
    }
}
