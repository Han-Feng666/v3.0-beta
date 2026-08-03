package com.HanFeng.service

import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Socket

data class UnderlyingNetworkCandidate(
    val network: Network,
    val capabilities: NetworkCapabilities
)

data class MatchedIpRule(
    val rule: com.HanFeng.model.BlockRule,
    val appName: String
)

data class BlockedIpNetwork(
    val addressBytes: ByteArray,
    val prefixLength: Int,
    val routeAddress: String
)

data class HttpDecryptTarget(
    val domain: String,
    val vendor: String,
    val appName: String,
    val source: String,
    val expiresAt: Long
)

data class HttpsDecryptTarget(
    val domain: String,
    val vendor: String,
    val appName: String,
    val source: String,
    val expiresAt: Long
)

data class QuicRouteTarget(
    val domain: String,
    val vendor: String,
    val appName: String,
    val source: String,
    val expiresAt: Long
)

data class AdIpTarget(
    val domain: String,
    val vendor: String,
    val appName: String,
    val source: String,
    val expiresAt: Long
)

data class AppResolveCacheKeys(
    val flowKey: String,
    val portKey: String,
    val sourcePortKey: String
)

data class NormalizedDomainInfo(
    val normalized: String,
    val secondLevelDomain: String?
)

data class HttpsProxyFlow(
    val flowKey: String,
    val domain: String,
    val vendor: String,
    val source: String,
    val targetIp: String,
    val sourcePort: Int,
    val appName: String,
    val state: String,
    val bridgeHost: String?,
    val bridgePort: Int?,
    val clientInitialSequence: Long? = null,
    val serverInitialSequence: Long? = null,
    val clientNextSequence: Long? = null,
    val serverNextSequence: Long? = null,
    val lastServerPayloadSequence: Long? = null,
    val lastServerPayload: ByteArray? = null,
    val pendingServerSegments: List<PendingServerSegment> = emptyList(),
    val bufferedClientSegments: List<ClientPayloadSegment> = emptyList(),
    val lastClientPayloadSequence: Long? = null,
    val lastClientPayloadLength: Long? = null,
    val lastSequenceNumber: Long?,
    val lastAcknowledgementNumber: Long?,
    val lastSeenAt: Long
)

data class PendingServerSegment(
    val sequenceNumber: Long,
    val payload: ByteArray
)

interface ClosableBridgeSession {
    fun close()
}

data class ClientPayloadSegment(
    val sequenceNumber: Long,
    val payload: ByteArray
)

data class ClientSegmentDrainResult(
    val nextExpectedSequence: Long,
    val forwardSegments: List<ClientPayloadSegment>,
    val remainingSegments: List<ClientPayloadSegment>
)

data class HttpsBridgeSocketSession(
    val flowKey: String,
    val requestTemplate: com.HanFeng.model.PacketInfo,
    val socket: Socket,
    val input: java.io.InputStream,
    val output: java.io.OutputStream
) : ClosableBridgeSession {
    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }
}

data class LocalProxyTcpFlow(
    val flowKey: String,
    val targetIp: String,
    val targetPort: Int,
    val sourcePort: Int,
    val appName: String,
    val state: String,
    val bridgeHost: String?,
    val bridgePort: Int?,
    val clientInitialSequence: Long? = null,
    val serverInitialSequence: Long? = null,
    val clientNextSequence: Long? = null,
    val serverNextSequence: Long? = null,
    val lastServerPayloadSequence: Long? = null,
    val lastServerPayload: ByteArray? = null,
    val pendingServerSegments: List<PendingServerSegment> = emptyList(),
    val bufferedClientSegments: List<ClientPayloadSegment> = emptyList(),
    val lastClientPayloadSequence: Long? = null,
    val lastClientPayloadLength: Long? = null,
    val lastSequenceNumber: Long?,
    val lastAcknowledgementNumber: Long?,
    val lastSeenAt: Long
)

data class LocalProxyBridgeSocketSession(
    val flowKey: String,
    val requestTemplate: com.HanFeng.model.PacketInfo,
    val socket: Socket,
    val input: java.io.InputStream,
    val output: java.io.OutputStream
) : ClosableBridgeSession {
    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }
}

data class PassthroughTcpFlow(
    val flowKey: String,
    val targetIp: String,
    val targetPort: Int,
    val sourcePort: Int,
    val appName: String,
    val state: String,
    val clientInitialSequence: Long? = null,
    val serverInitialSequence: Long? = null,
    val clientNextSequence: Long? = null,
    val serverNextSequence: Long? = null,
    val lastServerPayloadSequence: Long? = null,
    val lastServerPayload: ByteArray? = null,
    val pendingServerSegments: List<PendingServerSegment> = emptyList(),
    val bufferedClientSegments: List<ClientPayloadSegment> = emptyList(),
    val lastClientPayloadSequence: Long? = null,
    val lastClientPayloadLength: Long? = null,
    val lastSequenceNumber: Long?,
    val lastAcknowledgementNumber: Long?,
    val lastSeenAt: Long
)

data class PassthroughTcpSocketSession(
    val flowKey: String,
    val requestTemplate: com.HanFeng.model.PacketInfo,
    val socket: Socket,
    val input: java.io.InputStream,
    val output: java.io.OutputStream
) : ClosableBridgeSession {
    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }
}

data class PassthroughUdpSession(
    val flowKey: String,
    val requestTemplate: com.HanFeng.model.PacketInfo,
    val socket: DatagramSocket,
    val targetIp: String,
    val targetPort: Int,
    val lastSeenAt: Long
) : ClosableBridgeSession {
    override fun close() {
        runCatching { socket.close() }
    }
}
