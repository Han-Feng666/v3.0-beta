package com.HanFeng.core.network

object TcpSyntheticFlowEngine {
    data class ClientSegment(
        val sequenceNumber: Long,
        val payload: ByteArray
    )

    data class PendingSegment(
        val sequenceNumber: Long,
        val payload: ByteArray
    )

    data class ClientSegmentDrainResult(
        val nextExpectedSequence: Long,
        val forwardSegments: List<ClientSegment>,
        val remainingSegments: List<ClientSegment>
    )

    fun mergeBufferedClientSegments(
        existing: List<ClientSegment>,
        additions: List<ClientSegment>,
        maxSegments: Int
    ): List<ClientSegment> {
        if (additions.isEmpty()) return existing
        val allSegments = (existing + additions)
            .filter { it.payload.isNotEmpty() }
            .sortedBy { it.sequenceNumber }
        if (allSegments.isEmpty()) return emptyList()
        val normalized = mutableListOf<ClientSegment>()
        allSegments.forEach { segment ->
            if (normalized.isEmpty()) {
                normalized += segment
                return@forEach
            }
            val previous = normalized.removeAt(normalized.lastIndex)
            val previousEnd = previous.sequenceNumber + previous.payload.size
            val segmentEnd = segment.sequenceNumber + segment.payload.size
            if (segment.sequenceNumber > previousEnd) {
                normalized += previous
                normalized += segment
                return@forEach
            }
            if (segmentEnd <= previousEnd) {
                normalized += previous
                return@forEach
            }
            val overlap = (previousEnd - segment.sequenceNumber).toInt().coerceAtLeast(0)
            val appendPayload = if (overlap == 0) segment.payload else segment.payload.copyOfRange(overlap, segment.payload.size)
            normalized += if (appendPayload.isEmpty()) {
                previous
            } else {
                ClientSegment(
                    sequenceNumber = previous.sequenceNumber,
                    payload = previous.payload + appendPayload
                )
            }
        }
        return normalized.takeLast(maxSegments)
    }

    fun drainBufferedClientSegments(
        segments: List<ClientSegment>,
        expectedSequence: Long,
        maxSegments: Int
    ): ClientSegmentDrainResult {
        if (segments.isEmpty()) {
            return ClientSegmentDrainResult(
                nextExpectedSequence = expectedSequence,
                forwardSegments = emptyList(),
                remainingSegments = emptyList()
            )
        }
        val forwardSegments = mutableListOf<ClientSegment>()
        val remainingSegments = mutableListOf<ClientSegment>()
        var nextExpectedSequence = expectedSequence
        segments.sortedBy { it.sequenceNumber }.forEach { segment ->
            val segmentEnd = segment.sequenceNumber + segment.payload.size
            if (segment.sequenceNumber > nextExpectedSequence) {
                remainingSegments += segment
                return@forEach
            }
            if (segmentEnd <= nextExpectedSequence) {
                return@forEach
            }
            val overlap = (nextExpectedSequence - segment.sequenceNumber).toInt().coerceAtLeast(0)
            val forwardPayload = if (overlap == 0) segment.payload else segment.payload.copyOfRange(overlap, segment.payload.size)
            if (forwardPayload.isNotEmpty()) {
                forwardSegments += ClientSegment(nextExpectedSequence, forwardPayload)
                nextExpectedSequence += forwardPayload.size
            }
        }
        return ClientSegmentDrainResult(
            nextExpectedSequence = nextExpectedSequence,
            forwardSegments = forwardSegments,
            remainingSegments = remainingSegments.takeLast(maxSegments)
        )
    }

    fun buildServerPayloadSegments(
        sequenceNumber: Long,
        payload: ByteArray,
        segmentPayloadSize: Int
    ): List<PendingSegment> {
        if (payload.isEmpty()) return emptyList()
        val segments = ArrayList<PendingSegment>((payload.size / segmentPayloadSize) + 1)
        var sentBytes = 0
        var currentSeq = sequenceNumber
        while (sentBytes < payload.size) {
            val chunkSize = minOf(segmentPayloadSize, payload.size - sentBytes)
            val chunk = payload.copyOfRange(sentBytes, sentBytes + chunkSize)
            segments += PendingSegment(
                sequenceNumber = currentSeq,
                payload = chunk
            )
            sentBytes += chunkSize
            currentSeq += chunkSize
        }
        return segments
    }

    fun trimAcknowledgedServerSegments(
        segments: List<PendingSegment>,
        acknowledgementNumber: Long
    ): List<PendingSegment> {
        if (segments.isEmpty()) return emptyList()
        val remainingSegments = ArrayList<PendingSegment>(segments.size)
        segments.forEach { segment ->
            val segmentEnd = segment.sequenceNumber + segment.payload.size
            if (acknowledgementNumber <= segment.sequenceNumber) {
                remainingSegments += segment
                return@forEach
            }
            if (acknowledgementNumber >= segmentEnd) {
                return@forEach
            }
            val acknowledgedBytes = (acknowledgementNumber - segment.sequenceNumber).toInt().coerceAtLeast(0)
            val remainingPayload = segment.payload.copyOfRange(acknowledgedBytes, segment.payload.size)
            if (remainingPayload.isNotEmpty()) {
                remainingSegments += PendingSegment(
                    sequenceNumber = acknowledgementNumber,
                    payload = remainingPayload
                )
            }
        }
        return remainingSegments
    }

    fun mergePendingServerSegments(
        existing: List<PendingSegment>,
        additions: List<PendingSegment>,
        maxSegments: Int
    ): List<PendingSegment> {
        if (additions.isEmpty()) return existing
        val allSegments = (existing + additions)
            .filter { it.payload.isNotEmpty() }
            .sortedBy { it.sequenceNumber }
        if (allSegments.isEmpty()) return emptyList()
        val normalized = mutableListOf<PendingSegment>()
        allSegments.forEach { segment ->
            if (normalized.isEmpty()) {
                normalized += segment
                return@forEach
            }
            val previous = normalized.removeAt(normalized.lastIndex)
            val previousEnd = previous.sequenceNumber + previous.payload.size
            val segmentEnd = segment.sequenceNumber + segment.payload.size
            if (segment.sequenceNumber > previousEnd) {
                normalized += previous
                normalized += segment
                return@forEach
            }
            if (segmentEnd <= previousEnd) {
                normalized += previous
                return@forEach
            }
            val overlap = (previousEnd - segment.sequenceNumber).toInt().coerceAtLeast(0)
            val appendPayload = if (overlap == 0) segment.payload else segment.payload.copyOfRange(overlap, segment.payload.size)
            normalized += if (appendPayload.isEmpty()) {
                previous
            } else {
                PendingSegment(
                    sequenceNumber = previous.sequenceNumber,
                    payload = previous.payload + appendPayload
                )
            }
        }
        return normalized.takeLast(maxSegments)
    }

    fun payloadBytes(segments: List<ByteArray>): Int {
        return segments.sumOf { it.size }
    }
}
