package com.HanFeng.core.network

import java.util.LinkedHashMap

object BridgeReaderSupport {
    data class ResetPacketState(
        val nextServerSequence: Long,
        val clientAcknowledgement: Long
    )

    fun resolveResetPacketState(
        serverInitialSequence: Long?,
        serverNextSequence: Long?,
        clientInitialSequence: Long?,
        clientNextSequence: Long?,
        synthesizeServerSequence: () -> Long
    ): ResetPacketState {
        val serverSeqBase = serverInitialSequence ?: synthesizeServerSequence()
        val nextServerSeq = serverNextSequence ?: (serverSeqBase + 1)
        val clientAck = clientNextSequence ?: ((clientInitialSequence ?: 0L) + 1)
        return ResetPacketState(
            nextServerSequence = nextServerSeq,
            clientAcknowledgement = clientAck
        )
    }

    fun <TFlow, TSession> completeBridgeReader(
        flowCache: MutableMap<String, TFlow>,
        sessionCache: MutableMap<String, TSession>,
        flowKey: String,
        now: Long,
        closeSession: (TSession) -> Unit,
        updateFlow: (TFlow, Long) -> TFlow
    ) {
        BridgeSessionSupport.removeAndClose(sessionCache, flowKey, closeSession)
        FlowCacheSupport.updateIfPresent(flowCache, flowKey) {
            updateFlow(it, now)
        }
    }
}
