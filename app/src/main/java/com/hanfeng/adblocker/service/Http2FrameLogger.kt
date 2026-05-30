package com.HanFeng.service

object Http2FrameLogger {
    private val clientPreface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII)

    fun inspectChunk(
        state: StreamState,
        chunk: ByteArray
    ): InspectionResult {
        if (chunk.isEmpty()) return InspectionResult(state, emptyList(), emptyList())
        val events = mutableListOf<FrameEvent>()
        var nextState = state
        var working = chunk

        if (!nextState.prefaceLogged && nextState.direction == Direction.CLIENT_TO_SERVER) {
            val combinedPrefix = concatPrefix(nextState.pendingPrefix, working, clientPreface.size)
            if (combinedPrefix.contentEquals(clientPreface)) {
                events += FrameEvent.ConnectionPreface
                nextState = nextState.copy(prefaceLogged = true, pendingPrefix = ByteArray(0))
                working = if (working.size > clientPreface.size) {
                    working.copyOfRange(clientPreface.size, working.size)
                } else {
                    ByteArray(0)
                }
            } else if (combinedPrefix.size < clientPreface.size && clientPreface.copyOfRange(0, combinedPrefix.size).contentEquals(combinedPrefix)) {
                return InspectionResult(nextState.copy(pendingPrefix = combinedPrefix), events, emptyList())
            } else {
                nextState = nextState.copy(prefaceLogged = true, pendingPrefix = ByteArray(0))
            }
        }

        val combined = nextState.pendingFrameBytes + working
        var offset = 0
        val parsedEvents = mutableListOf<FrameEvent>()
        val parsedFrames = mutableListOf<ParsedFrame>()
        while (offset + 9 <= combined.size) {
            val length = readMedium(combined, offset)
            val frameType = combined[offset + 3].toInt() and 0xFF
            val flags = combined[offset + 4].toInt() and 0xFF
            val streamId = readInt(combined, offset + 5) and 0x7FFFFFFF.toInt()
            val totalLength = 9 + length
            if (offset + totalLength > combined.size) break
            val flagNames = decodeFlags(frameType, flags)
            val payload = combined.copyOfRange(offset + 9, offset + totalLength)
            val frameBytes = combined.copyOfRange(offset, offset + totalLength)
            parsedEvents += FrameEvent.FrameHeader(
                length = length,
                type = frameType,
                typeName = frameTypeName(frameType),
                flags = flags,
                streamId = streamId,
                flagNames = flagNames,
                endStream = flagNames.contains("END_STREAM"),
                endHeaders = flagNames.contains("END_HEADERS"),
                ack = flagNames.contains("ACK"),
                goAway = frameType == 7,
                closedStreamFrame = streamId > 0 && nextState.closedStreams.contains(streamId)
            )
            parsedFrames += ParsedFrame(
                streamId = streamId,
                type = frameType,
                flags = flags,
                endStream = flagNames.contains("END_STREAM"),
                endHeaders = flagNames.contains("END_HEADERS"),
                headerBlockFragment = extractHeaderBlockFragment(frameType, flagNames, payload),
                priorityFragment = extractPriorityFragment(frameType, flagNames, payload),
                padded = flagNames.contains("PADDED"),
                payloadFragment = extractDataPayloadFragment(frameType, flagNames, payload),
                rawBytes = frameBytes
            )
            buildStreamEvent(nextState, frameType, length, streamId, flagNames, payload)?.let { parsedEvents += it }
            nextState = nextState.recordFrame(frameType, streamId, flagNames)
            offset += totalLength
        }
        val remaining = if (offset < combined.size) combined.copyOfRange(offset, combined.size) else ByteArray(0)
        nextState = nextState.copy(pendingFrameBytes = remaining)
        events += parsedEvents
        return InspectionResult(nextState, events, parsedFrames)
    }

    private fun concatPrefix(existing: ByteArray, incoming: ByteArray, maxSize: Int): ByteArray {
        val merged = existing + incoming
        return if (merged.size <= maxSize) merged else merged.copyOfRange(0, maxSize)
    }

    private fun frameTypeName(type: Int): String {
        return when (type) {
            0 -> "DATA"
            1 -> "HEADERS"
            2 -> "PRIORITY"
            3 -> "RST_STREAM"
            4 -> "SETTINGS"
            5 -> "PUSH_PROMISE"
            6 -> "PING"
            7 -> "GOAWAY"
            8 -> "WINDOW_UPDATE"
            9 -> "CONTINUATION"
            else -> "TYPE_$type"
        }
    }

    private fun decodeFlags(type: Int, flags: Int): Set<String> {
        if (flags == 0) return emptySet()
        val names = linkedSetOf<String>()
        if ((flags and 0x1) != 0) {
            names += when (type) {
                4, 6 -> "ACK"
                else -> "END_STREAM"
            }
        }
        if ((flags and 0x4) != 0 && (type == 1 || type == 5 || type == 9)) names += "END_HEADERS"
        if ((flags and 0x8) != 0 && (type == 0 || type == 1 || type == 5)) names += "PADDED"
        if ((flags and 0x20) != 0 && type == 1) names += "PRIORITY"
        return names
    }

    private fun readMedium(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            (buffer[offset + 2].toInt() and 0xFF)
    }

    private fun readInt(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
    }

    private fun buildStreamEvent(
        state: StreamState,
        frameType: Int,
        length: Int,
        streamId: Int,
        flagNames: Set<String>,
        payload: ByteArray
    ): FrameEvent.StreamProgress? {
        if (streamId <= 0) return null
        val stage = when (frameType) {
            0 -> "DATA"
            1 -> "HEADERS"
            3 -> "RST_STREAM"
            5 -> "PUSH_PROMISE"
            9 -> "CONTINUATION"
            else -> return null
        }
        val opensHeaderBlock = frameType == 1 || frameType == 5
        val closesHeaderBlock = frameType == 1 || frameType == 5 || frameType == 9
        val headerBlockOpenBeforeFrame = state.openHeaderStreams.contains(streamId)
        val unexpectedContinuation = frameType == 9 && !headerBlockOpenBeforeFrame
        val replacedOpenHeaderBlock = opensHeaderBlock && headerBlockOpenBeforeFrame
        val headerBlockOpenAfterFrame = when {
            !closesHeaderBlock -> headerBlockOpenBeforeFrame
            flagNames.contains("END_HEADERS") -> false
            else -> true
        }
        return FrameEvent.StreamProgress(
            streamId = streamId,
            stage = stage,
            payloadLength = length,
            headerBlock = frameType == 1 || frameType == 5 || frameType == 9,
            dataPayload = frameType == 0,
            reset = frameType == 3,
            endStream = flagNames.contains("END_STREAM"),
            endHeaders = flagNames.contains("END_HEADERS"),
            opensHeaderBlock = opensHeaderBlock,
            closesHeaderBlock = closesHeaderBlock,
            headerBlockOpenBeforeFrame = headerBlockOpenBeforeFrame,
            headerBlockOpenAfterFrame = headerBlockOpenAfterFrame,
            unexpectedContinuation = unexpectedContinuation,
            replacedOpenHeaderBlock = replacedOpenHeaderBlock,
            headerBlockFragment = extractHeaderBlockFragment(frameType, flagNames, payload),
            dataFragment = extractDataPayloadFragment(frameType, flagNames, payload)
        )
    }

    private fun extractDataPayloadFragment(frameType: Int, flagNames: Set<String>, payload: ByteArray): ByteArray {
        if (frameType != 0) return ByteArray(0)
        if (!flagNames.contains("PADDED")) return payload
        if (payload.isEmpty()) return ByteArray(0)
        val padLength = payload[0].toInt() and 0xFF
        val start = 1
        val end = payload.size - padLength
        if (start >= end || end > payload.size) return ByteArray(0)
        return payload.copyOfRange(start, end)
    }

    private fun extractHeaderBlockFragment(frameType: Int, flagNames: Set<String>, payload: ByteArray): ByteArray {
        if (frameType == 9) return payload
        if (frameType != 1 && frameType != 5) return ByteArray(0)
        var start = 0
        var end = payload.size
        var padLength = 0
        if (flagNames.contains("PADDED") && payload.isNotEmpty()) {
            padLength = payload[0].toInt() and 0xFF
            start += 1
        }
        if (frameType == 1 && flagNames.contains("PRIORITY")) {
            start += 5
        }
        if (frameType == 5) {
            start += 4
        }
        end -= padLength
        if (start >= end || start < 0 || end > payload.size) return ByteArray(0)
        return payload.copyOfRange(start, end)
    }

    private fun extractPriorityFragment(frameType: Int, flagNames: Set<String>, payload: ByteArray): ByteArray {
        if (frameType != 1 || !flagNames.contains("PRIORITY")) return ByteArray(0)
        var start = 0
        if (flagNames.contains("PADDED") && payload.isNotEmpty()) {
            start += 1
        }
        val end = start + 5
        if (end > payload.size) return ByteArray(0)
        return payload.copyOfRange(start, end)
    }

    data class StreamState(
        val direction: Direction,
        val prefaceLogged: Boolean = false,
        val pendingPrefix: ByteArray = ByteArray(0),
        val pendingFrameBytes: ByteArray = ByteArray(0),
        val totalFrames: Int = 0,
        val dataFrames: Int = 0,
        val headersFrames: Int = 0,
        val settingsFrames: Int = 0,
        val pingFrames: Int = 0,
        val goAwayFrames: Int = 0,
        val endStreamFrames: Int = 0,
        val endHeadersFrames: Int = 0,
        val ackFrames: Int = 0,
        val activeStreams: Set<Int> = emptySet(),
        val closedStreams: Set<Int> = emptySet(),
        val openHeaderStreams: Set<Int> = emptySet(),
        val lastStreamId: Int? = null
    ) {
        fun recordFrame(frameType: Int, streamId: Int, flagNames: Set<String>): StreamState {
            var nextActive = activeStreams
            var nextClosed = closedStreams
            var nextOpenHeaderStreams = openHeaderStreams
            if (streamId > 0) {
                if (!flagNames.contains("END_STREAM")) {
                    nextActive = activeStreams + streamId
                } else {
                    nextActive = activeStreams - streamId
                    nextClosed = closedStreams + streamId
                }
                when (frameType) {
                    1, 5, 9 -> {
                        nextOpenHeaderStreams = if (flagNames.contains("END_HEADERS")) {
                            nextOpenHeaderStreams - streamId
                        } else {
                            nextOpenHeaderStreams + streamId
                        }
                    }
                    3 -> {
                        nextOpenHeaderStreams = nextOpenHeaderStreams - streamId
                    }
                }
                if (flagNames.contains("END_STREAM")) {
                    nextOpenHeaderStreams = nextOpenHeaderStreams - streamId
                }
            }
            return copy(
                totalFrames = totalFrames + 1,
                dataFrames = dataFrames + if (frameType == 0) 1 else 0,
                headersFrames = headersFrames + if (frameType == 1) 1 else 0,
                settingsFrames = settingsFrames + if (frameType == 4) 1 else 0,
                pingFrames = pingFrames + if (frameType == 6) 1 else 0,
                goAwayFrames = goAwayFrames + if (frameType == 7) 1 else 0,
                endStreamFrames = endStreamFrames + if (flagNames.contains("END_STREAM")) 1 else 0,
                endHeadersFrames = endHeadersFrames + if (flagNames.contains("END_HEADERS")) 1 else 0,
                ackFrames = ackFrames + if (flagNames.contains("ACK")) 1 else 0,
                activeStreams = nextActive,
                closedStreams = nextClosed,
                openHeaderStreams = nextOpenHeaderStreams,
                lastStreamId = if (streamId > 0) streamId else lastStreamId
            )
        }
    }

    data class InspectionResult(
        val nextState: StreamState,
        val events: List<FrameEvent>,
        val parsedFrames: List<ParsedFrame>
    )

    data class ParsedFrame(
        val streamId: Int,
        val type: Int,
        val flags: Int,
        val endStream: Boolean,
        val endHeaders: Boolean,
        val headerBlockFragment: ByteArray = ByteArray(0),
        val priorityFragment: ByteArray = ByteArray(0),
        val padded: Boolean = false,
        val payloadFragment: ByteArray = ByteArray(0),
        val rawBytes: ByteArray
    )

    enum class Direction {
        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT
    }

    sealed interface FrameEvent {
        data object ConnectionPreface : FrameEvent

        data class FrameHeader(
            val length: Int,
            val type: Int,
            val typeName: String,
            val flags: Int,
            val streamId: Int,
            val flagNames: Set<String>,
            val endStream: Boolean,
            val endHeaders: Boolean,
            val ack: Boolean,
            val goAway: Boolean,
            val closedStreamFrame: Boolean
        ) : FrameEvent

        data class StreamProgress(
            val streamId: Int,
            val stage: String,
            val payloadLength: Int,
            val headerBlock: Boolean,
            val dataPayload: Boolean,
            val reset: Boolean,
            val endStream: Boolean,
            val endHeaders: Boolean,
            val opensHeaderBlock: Boolean,
            val closesHeaderBlock: Boolean,
            val headerBlockOpenBeforeFrame: Boolean,
            val headerBlockOpenAfterFrame: Boolean,
            val unexpectedContinuation: Boolean,
            val replacedOpenHeaderBlock: Boolean,
            val headerBlockFragment: ByteArray = ByteArray(0),
            val dataFragment: ByteArray = ByteArray(0)
        ) : FrameEvent
    }
}
