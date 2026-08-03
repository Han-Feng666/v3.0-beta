package com.HanFeng.core.network

object BridgeFlowStateSupport {
    data class FlushBufferedClientResult<TSegment, TFlow>(
        val forwardSegments: List<TSegment>,
        val nextFlow: TFlow?
    )

    data class ServerPayloadUpdateResult<TSegment, TFlow>(
        val mergedPendingSegments: List<TSegment>,
        val nextFlow: TFlow
    )

    fun <TSegment, TFlow> flushBufferedClientPayload(
        flow: TFlow,
        bufferedSegments: List<TSegment>,
        lastSequenceOf: (TSegment) -> Long,
        payloadSizeOf: (TSegment) -> Int,
        updateFlow: (TFlow, List<TSegment>, Long?, Long?, Long) -> TFlow,
        now: Long
    ): FlushBufferedClientResult<TSegment, TFlow> {
        if (bufferedSegments.isEmpty()) {
            return FlushBufferedClientResult(
                forwardSegments = emptyList(),
                nextFlow = updateFlow(flow, emptyList(), null, null, now)
            )
        }
        val lastSegment = bufferedSegments.lastOrNull()
        return FlushBufferedClientResult(
            forwardSegments = bufferedSegments,
            nextFlow = updateFlow(
                flow,
                emptyList(),
                lastSegment?.let(lastSequenceOf),
                lastSegment?.let { payloadSizeOf(it).toLong() },
                now
            )
        )
    }

    fun <TSegment, TFlow> updateServerPayloadState(
        flow: TFlow,
        freshSegments: List<TSegment>,
        currentPendingSegments: List<TSegment>,
        mergePendingSegments: (List<TSegment>, List<TSegment>) -> List<TSegment>,
        pendingBytes: (List<TSegment>) -> Int,
        maxBufferedServerBytes: Int,
        nextServerSequence: Long,
        payload: ByteArray,
        now: Long,
        updateFlow: (TFlow, List<TSegment>, Long, Long, ByteArray, Long) -> TFlow
    ): ServerPayloadUpdateResult<TSegment, TFlow>? {
        val mergedPendingSegments = mergePendingSegments(currentPendingSegments, freshSegments)
        if (pendingBytes(mergedPendingSegments) > maxBufferedServerBytes) {
            return null
        }
        return ServerPayloadUpdateResult(
            mergedPendingSegments = mergedPendingSegments,
            nextFlow = updateFlow(
                flow,
                mergedPendingSegments,
                nextServerSequence + payload.size,
                nextServerSequence,
                payload,
                now
            )
        )
    }
}
