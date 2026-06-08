package com.HanFeng.core.network

import java.util.LinkedHashMap

object BridgeTerminalStateSupport {
    data class BridgeFinTransition(
        val nextState: String,
        val nextServerSequence: Long,
        val lastSeenAt: Long
    )

    fun shouldEmitBridgeFin(state: String): Boolean {
        return state != "fin_ack_sent" && state != "bridge_fin_sent" && state != "bridge_fin_acked"
    }

    fun nextBridgeFinTransition(
        nextServerSequence: Long,
        now: Long
    ): BridgeFinTransition {
        return BridgeFinTransition(
            nextState = "bridge_fin_sent",
            nextServerSequence = nextServerSequence + 1,
            lastSeenAt = now
        )
    }

    fun <TFlow, TSession> closeFlow(
        flowCache: LinkedHashMap<String, TFlow>,
        sessionCache: LinkedHashMap<String, TSession>,
        flowKey: String,
        closeSession: (TSession) -> Unit
    ) {
        FlowCacheSupport.remove(flowCache, flowKey)
        BridgeSessionSupport.removeAndClose(sessionCache, flowKey, closeSession)
    }
}
