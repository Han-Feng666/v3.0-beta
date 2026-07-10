package com.HanFeng.core.network

object ClientPayloadReplaySupport {
    data class DrainResult<TSegment>(
        val nextExpectedSequence: Long,
        val forwardSegments: List<TSegment>,
        val remainingSegments: List<TSegment>
    )

    fun <TSegment> bufferOutOfOrderPayload(
        existingSegments: List<TSegment>,
        payload: ByteArray,
        sequenceNumber: Long,
        mergeWithSegment: (List<TSegment>, TSegment) -> List<TSegment>,
        createSegment: (Long, ByteArray) -> TSegment,
        bufferedBytes: (List<TSegment>) -> Int,
        maxBufferedClientBytes: Int
    ): List<TSegment>? {
        val updatedSegments = if (payload.isNotEmpty()) {
            mergeWithSegment(existingSegments, createSegment(sequenceNumber, payload))
        } else {
            existingSegments
        }
        if (bufferedBytes(updatedSegments) > maxBufferedClientBytes) {
            return null
        }
        return updatedSegments
    }

    fun <TSegment> drainReplayPayload(
        existingSegments: List<TSegment>,
        inboundSegments: List<TSegment>,
        acknowledgementNumber: Long,
        mergeSegments: (List<TSegment>, List<TSegment>) -> List<TSegment>,
        drainSegments: (List<TSegment>, Long) -> DrainResult<TSegment>,
        bufferedBytes: (List<TSegment>) -> Int,
        maxBufferedClientBytes: Int
    ): DrainResult<TSegment>? {
        val drainResult = drainSegments(
            mergeSegments(existingSegments, inboundSegments),
            acknowledgementNumber
        )
        if (bufferedBytes(drainResult.remainingSegments) > maxBufferedClientBytes) {
            return null
        }
        return drainResult
    }
}
