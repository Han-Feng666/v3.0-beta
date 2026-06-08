package com.HanFeng.core.network

import java.util.LinkedHashMap

object BridgeLifecycleSupport {
    data class ClientFinTransition(
        val state: String,
        val nextServerSequenceToSend: Long,
        val storedServerNextSequence: Long,
        val clientAcknowledgement: Long,
        val lastSeenAt: Long
    )

    fun resolveClientFinTransition(
        serverInitialSequence: Long?,
        serverNextSequence: Long?,
        clientSequenceNumber: Long,
        payloadLength: Long,
        now: Long,
        synthesizeServerSequence: () -> Long
    ): ClientFinTransition {
        val serverSeqBase = serverInitialSequence ?: synthesizeServerSequence()
        val nextServerSeq = serverNextSequence ?: (serverSeqBase + 1)
        val ackNumber = clientSequenceNumber + 1 + payloadLength
        return ClientFinTransition(
            state = "fin_ack_sent",
            nextServerSequenceToSend = nextServerSeq,
            storedServerNextSequence = nextServerSeq + 1,
            clientAcknowledgement = ackNumber,
            lastSeenAt = now
        )
    }

    fun <TSession> registerConnectedSession(
        cache: LinkedHashMap<String, TSession>,
        flowKey: String,
        session: TSession
    ) {
        synchronized(cache) {
            cache[flowKey] = session
        }
    }
}
