package com.HanFeng.core.network

object ServerAckStateSupport {
    data class RetransmitStateResult<TSegment>(
        val remainingSegments: List<TSegment>
    )

    data class AckStateTransition(
        val nextState: String,
        val lastSequenceNumber: Long,
        val lastAcknowledgementNumber: Long,
        val lastSeenAt: Long
    )

    fun <TSegment> trimPendingSegmentsForAck(
        pendingSegments: List<TSegment>,
        acknowledgementNumber: Long,
        trim: (List<TSegment>, Long) -> List<TSegment>
    ): RetransmitStateResult<TSegment>? {
        if (pendingSegments.isEmpty()) return null
        val remainingSegments = trim(pendingSegments, acknowledgementNumber)
        if (remainingSegments.isEmpty()) return null
        return RetransmitStateResult(remainingSegments)
    }

    fun nextAckState(
        currentState: String,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): AckStateTransition {
        return AckStateTransition(
            nextState = if (currentState == "bridge_fin_sent") "bridge_fin_acked" else "server_payload_acked",
            lastSequenceNumber = sequenceNumber,
            lastAcknowledgementNumber = acknowledgementNumber,
            lastSeenAt = now
        )
    }
}
