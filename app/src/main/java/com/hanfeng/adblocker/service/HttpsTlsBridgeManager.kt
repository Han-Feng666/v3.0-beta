package com.HanFeng.service

import android.content.Context
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.StatsRepository
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import com.HanFeng.service.HpackDecoder.HeaderField

object HttpsTlsBridgeManager {
    private const val ACCEPT_TIMEOUT_MILLIS = 1_000
    private const val CONNECT_TIMEOUT_MILLIS = 4_000
    private const val HTTP2_VERBOSE_FRAME_LIMIT = 12
    private const val HTTP2_SUMMARY_FRAME_INTERVAL = 25
    private const val HTTP2_HEADER_BLOCK_LARGE_THRESHOLD = 16 * 1024
    private val bridges = ConcurrentHashMap<String, RunningBridge>()
    private val http2LogStates = ConcurrentHashMap<String, Http2LogState>()
    private val http2FlowControls = ConcurrentHashMap<String, Http2FlowControl>()
    private val executor = Executors.newCachedThreadPool()

    fun ensureBridge(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        preparedContext: com.HanFeng.security.TlsMitmContextFactory.PreparedTlsContext,
        protectSocket: (Socket) -> Boolean
    ): TlsMitmSessionManager.BridgeBinding {
        bridges[session.flowKey]?.takeIf { !it.serverSocket.isClosed }?.let {
            return TlsMitmSessionManager.BridgeBinding(it.host, it.port)
        }
        val factory = preparedContext.sslContext.serverSocketFactory as SSLServerSocketFactory
        val serverSocket = factory.createServerSocket(
            0,
            50,
            InetAddress.getByName(LOOPBACK_HOST)
        ) as SSLServerSocket
        serverSocket.needClientAuth = false
        serverSocket.useClientMode = false
        serverSocket.soTimeout = ACCEPT_TIMEOUT_MILLIS
        val running = RunningBridge(
            flowKey = session.flowKey,
            host = LOOPBACK_HOST,
            port = serverSocket.localPort,
            serverSocket = serverSocket
        )
        val previous = bridges.put(session.flowKey, running)
        previous?.close()
        executor.execute {
            acceptLoop(context, session, running, protectSocket)
        }
        LogRepository.append(
            context,
            "Started HTTPS TLS bridge host=${session.host} app=${session.appName} upstream=${session.targetIp}:${session.targetPort} local=${running.host}:${running.port}"
        )
        return TlsMitmSessionManager.BridgeBinding(running.host, running.port)
    }

    fun closeBridge(context: Context, flowKey: String) {
        flushHttp2Summary(context, flowKey, "client", finalFlush = true)
        flushHttp2Summary(context, flowKey, "server", finalFlush = true)
        http2FlowControls.remove(flowKey)
        val bridge = bridges.remove(flowKey) ?: return
        bridge.close()
        LogRepository.append(context, "Closed HTTPS TLS bridge flow=$flowKey local=${bridge.host}:${bridge.port}")
    }

    fun closeAll(context: Context) {
        http2LogStates.keys.toList().forEach { key ->
            val flowKey = key.substringBefore('|')
            val direction = key.substringAfter('|', missingDelimiterValue = "")
            if (direction.isNotEmpty()) {
                flushHttp2Summary(context, flowKey, direction, finalFlush = true)
            }
        }
        val all = bridges.values.toList()
        bridges.clear()
        http2FlowControls.clear()
        all.forEach { it.close() }
        if (all.isNotEmpty()) {
            LogRepository.append(context, "Closed all HTTPS TLS bridges count=${all.size}")
        }
    }

    fun activeBridgeCount(): Int = bridges.size

    private fun acceptLoop(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        running: RunningBridge,
        protectSocket: (Socket) -> Boolean
    ) {
        try {
            while (!running.serverSocket.isClosed) {
                try {
                    val client = running.serverSocket.accept() as? SSLSocket ?: continue
                    executor.execute {
                        handleBridgeConnection(context, session, running, client, protectSocket)
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (error: IOException) {
            if (!running.serverSocket.isClosed) {
                LogRepository.append(
                    context,
                    "HTTPS TLS bridge crashed host=${session.host} flow=${session.flowKey}: ${error.message ?: error.javaClass.simpleName}"
                )
            }
        } finally {
            flushHttp2Summary(context, session.flowKey, "client", finalFlush = true)
            flushHttp2Summary(context, session.flowKey, "server", finalFlush = true)
            http2FlowControls.remove(session.flowKey)
            bridges.remove(session.flowKey, running)
            running.close()
        }
    }

    private fun handleBridgeConnection(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        running: RunningBridge,
        clientSocket: SSLSocket,
        protectSocket: (Socket) -> Boolean
    ) {
        try {
            clientSocket.use { localTls ->
                configureLocalTls(localTls, session.offeredAlpnProtocols)
                localTls.startHandshake()
                val rawUpstreamSocket = Socket()
                rawUpstreamSocket.tcpNoDelay = true
                if (!protectSocket(rawUpstreamSocket)) {
                    LogRepository.append(context, "Protect upstream socket failed for HTTPS bridge host=${session.host}")
                }
                rawUpstreamSocket.connect(InetSocketAddress(session.targetIp, session.targetPort), CONNECT_TIMEOUT_MILLIS)
                val upstreamTls = ((SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(rawUpstreamSocket, session.host, session.targetPort, true) as SSLSocket)
                upstreamTls.use { remoteTls ->
                    localTls.tcpNoDelay = true
                    remoteTls.tcpNoDelay = true
                    configureUpstreamTls(remoteTls, session.offeredAlpnProtocols)
                    remoteTls.startHandshake()
                    val negotiatedAlpn = readApplicationProtocol(remoteTls)
                    val negotiatedTls = remoteTls.session?.protocol
                    TlsMitmSessionManager.updateNegotiatedProtocol(context, session.flowKey, negotiatedAlpn, negotiatedTls)
                    LogRepository.append(
                        context,
                        "Accepted local HTTPS bridge client host=${session.host} flow=${session.flowKey} local=${running.host}:${running.port} alpn=${negotiatedAlpn ?: "none"} negotiatedHttp2=${negotiatedAlpn.equals("h2", ignoreCase = true)}"
                    )
                    val requestRef = java.util.concurrent.atomic.AtomicReference<HttpMitmFilter.RequestInspection?>()
                    val latch = CountDownLatch(2)
                    executor.execute {
                        try {
                            pipeClientToServer(context, session, localTls.inputStream, remoteTls.outputStream, requestRef, negotiatedAlpn)
                        } finally {
                            latch.countDown()
                            runCatching { remoteTls.shutdownOutput() }
                        }
                    }
                    try {
                        pipeServerToClient(context, session, remoteTls.inputStream, localTls.outputStream, requestRef, negotiatedAlpn)
                    } finally {
                        latch.countDown()
                        runCatching { localTls.shutdownOutput() }
                    }
                    latch.await()
                }
            }
        } catch (error: SSLHandshakeException) {
            TlsMitmSessionManager.markMitmBypass(context, session.flowKey, classifySslHandshakeBypassReason(error))
            LogRepository.append(context, "HTTPS MITM handshake bypass host=${session.host} flow=${session.flowKey}: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: IOException) {
            TlsMitmSessionManager.markMitmBypass(context, session.flowKey, "io-bridge:${error.message ?: error.javaClass.simpleName}")
            LogRepository.append(context, "HTTPS MITM bridge failure host=${session.host} flow=${session.flowKey}: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun pipeClientToServer(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        input: InputStream,
        output: OutputStream,
        requestRef: java.util.concurrent.atomic.AtomicReference<HttpMitmFilter.RequestInspection?>,
        negotiatedAlpn: String?
    ) {
        val buffer = ByteArray(16 * 1024)
        var inspected = false
        var http2State = Http2FrameLogger.StreamState(Http2FrameLogger.Direction.CLIENT_TO_SERVER)
        var pendingHttp2ClientRewrite = PendingHttp2ClientRewrite()
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            var payload = buffer.copyOf(count)
            if (negotiatedAlpn.equals("h2", ignoreCase = true)) {
                val inspection = Http2FrameLogger.inspectChunk(http2State, payload)
                http2State = inspection.nextState
                TlsMitmSessionManager.updateHttp2Observation(context, session.flowKey, Http2FrameLogger.Direction.CLIENT_TO_SERVER, http2State)
                val directives = logHttp2Events(context, session, inspection.events, "client")
                applyHttp2Directives(context, session, directives, output, resetPeer = "upstream")
                if (pendingHttp2ClientRewrite.active) {
                    pendingHttp2ClientRewrite.rawBytes += payload
                    pendingHttp2ClientRewrite.parsedFrames += inspection.parsedFrames
                    pendingHttp2ClientRewrite.openStreams.removeAll { streamId ->
                        !directionHasOpenClientHeaderBlock(session.flowKey, streamId)
                    }
                    if (pendingHttp2ClientRewrite.rawBytes.size > MAX_PENDING_HTTP2_CLIENT_REWRITE_BYTES) {
                        LogRepository.append(context, "HTTP/2 client rewrite bypass host=${session.host} flow=${session.flowKey} reason=buffer-overflow bytes=${pendingHttp2ClientRewrite.rawBytes.size}")
                        output.write(pendingHttp2ClientRewrite.rawBytes)
                        output.flush()
                        pendingHttp2ClientRewrite = PendingHttp2ClientRewrite()
                        continue
                    }
                    if (pendingHttp2ClientRewrite.openStreams.isNotEmpty() || http2State.pendingFrameBytes.isNotEmpty()) {
                        continue
                    }
                    payload = pendingHttp2ClientRewrite.rawBytes
                    val bufferedFrames = pendingHttp2ClientRewrite.parsedFrames.toList()
                    pendingHttp2ClientRewrite = PendingHttp2ClientRewrite()
                    payload = processHttp2ClientPayload(context, session, bufferedFrames, payload, directives.isNotEmpty())
                    if (payload.isEmpty()) {
                        LogRepository.append(context, "HTTP/2 payload suppressed host=${session.host} flow=${session.flowKey} direction=client")
                        continue
                    }
                    output.write(payload)
                    output.flush()
                    continue
                }
                val delayedRewriteStreams = findIncompleteClientHeaderStreams(session.flowKey, inspection.parsedFrames)
                if (delayedRewriteStreams.isNotEmpty()) {
                    pendingHttp2ClientRewrite = PendingHttp2ClientRewrite(
                        active = true,
                        rawBytes = payload,
                        parsedFrames = inspection.parsedFrames.toMutableList(),
                        openStreams = delayedRewriteStreams.toMutableSet()
                    )
                    LogRepository.append(context, "HTTP/2 client rewrite buffering host=${session.host} flow=${session.flowKey} streams=${delayedRewriteStreams.sorted().joinToString(",")} bytes=${payload.size} pendingFrameBytes=${http2State.pendingFrameBytes.size}")
                    continue
                }
                payload = processHttp2ClientPayload(context, session, inspection.parsedFrames, payload, directives.isNotEmpty())
                if (payload.isEmpty()) {
                    LogRepository.append(context, "HTTP/2 payload suppressed host=${session.host} flow=${session.flowKey} direction=client")
                    continue
                }
            }
            if (!inspected) {
                val inspection = HttpMitmFilter.inspectRequest(session, payload)
                if (inspection != null) {
                    requestRef.set(inspection)
                    if (HttpMitmFilter.shouldRewriteHttp1RequestHeaders(session, inspection)) {
                        payload = HttpMitmFilter.rewriteRequestForMitm(session, inspection, payload)
                    }
                    LogRepository.append(
                        context,
                        "HTTPS request host=${inspection.host} method=${inspection.method} path=${inspection.path} flow=${session.flowKey}"
                    )
                    inspected = true
                }
            }
            output.write(payload)
            output.flush()
        }
        if (pendingHttp2ClientRewrite.active && pendingHttp2ClientRewrite.rawBytes.isNotEmpty()) {
            val finalPayload = if (pendingHttp2ClientRewrite.openStreams.isEmpty() && http2State.pendingFrameBytes.isEmpty()) {
                processHttp2ClientPayload(
                    context,
                    session,
                    pendingHttp2ClientRewrite.parsedFrames,
                    pendingHttp2ClientRewrite.rawBytes,
                    directivesPresent = false
                )
            } else {
                LogRepository.append(context, "HTTP/2 client rewrite bypass host=${session.host} flow=${session.flowKey} reason=stream-ended-before-complete headersStreams=${pendingHttp2ClientRewrite.openStreams.sorted().joinToString(",").ifBlank { "none" }} pendingFrameBytes=${http2State.pendingFrameBytes.size}")
                pendingHttp2ClientRewrite.rawBytes
            }
            if (finalPayload.isNotEmpty()) {
                output.write(finalPayload)
                output.flush()
            }
        }
    }

    private fun pipeServerToClient(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        input: InputStream,
        output: OutputStream,
        requestRef: java.util.concurrent.atomic.AtomicReference<HttpMitmFilter.RequestInspection?>,
        negotiatedAlpn: String?
    ) {
        val buffer = ByteArray(16 * 1024)
        var filtered = false
        var pendingHttp1Bytes = ByteArray(0)
        val allowHttp1Filter = negotiatedAlpn.isNullOrBlank() || negotiatedAlpn == "http/1.1"
        var http2State = Http2FrameLogger.StreamState(Http2FrameLogger.Direction.SERVER_TO_CLIENT)
        if (!allowHttp1Filter) {
            LogRepository.append(
                context,
                "HTTPS response filter bypass host=${session.host} flow=${session.flowKey} negotiatedAlpn=${negotiatedAlpn ?: "unknown"} reason=http2-or-non-http1"
            )
        }
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            var payload = buffer.copyOf(count)
            if (negotiatedAlpn.equals("h2", ignoreCase = true)) {
                val inspection = Http2FrameLogger.inspectChunk(http2State, payload)
                http2State = inspection.nextState
                TlsMitmSessionManager.updateHttp2Observation(context, session.flowKey, Http2FrameLogger.Direction.SERVER_TO_CLIENT, http2State)
                val directives = logHttp2Events(context, session, inspection.events, "server")
                applyHttp2Directives(context, session, directives, output, resetPeer = "client")
                val parsedFrameBytes = inspection.parsedFrames.sumOf { it.rawBytes.size }
                if (parsedFrameBytes != payload.size && directives.isNotEmpty()) {
                    LogRepository.append(context, "HTTP/2 frame filtering tail-preserved host=${session.host} flow=${session.flowKey} direction=server reason=partial-frame-boundary parsedBytes=$parsedFrameBytes payloadBytes=${payload.size} tailBytes=${payload.size - parsedFrameBytes}")
                }
                payload = filterHttp2Payload(context, session, "server", inspection.parsedFrames, payload)
                if (payload.isEmpty()) {
                    LogRepository.append(context, "HTTP/2 payload suppressed host=${session.host} flow=${session.flowKey} direction=server")
                    continue
                }
            }
            if (!filtered && allowHttp1Filter) {
                val bufferedPayload = pendingHttp1Bytes + payload
                if (bufferedPayload.size > HttpMitmFilter.maxHttp1FilterBufferBytes()) {
                    LogRepository.append(context, "HTTPS response passthrough host=${session.host} reason=http1-buffer-overflow bytes=${bufferedPayload.size}")
                    payload = bufferedPayload
                    pendingHttp1Bytes = ByteArray(0)
                    filtered = true
                } else {
                    when (val assembled = HttpMitmFilter.inspectBufferedHttp1Response(bufferedPayload, requestRef.get())) {
                        HttpMitmFilter.BufferedHttp1Result.AwaitMore -> {
                            pendingHttp1Bytes = bufferedPayload
                            continue
                        }
                        is HttpMitmFilter.BufferedHttp1Result.Bypass -> {
                            LogRepository.append(context, "HTTPS response passthrough host=${session.host} reason=${assembled.reason}")
                            payload = bufferedPayload
                            pendingHttp1Bytes = ByteArray(0)
                            filtered = true
                        }
                        is HttpMitmFilter.BufferedHttp1Result.Ready -> {
                            pendingHttp1Bytes = ByteArray(0)
                            payload = applyHttp1Filter(context, session, assembled.responseBytes, requestRef.get())
                            if (assembled.remainderBytes.isNotEmpty()) {
                                payload += assembled.remainderBytes
                            }
                            filtered = true
                        }
                    }
                }
            }
            output.write(payload)
            output.flush()
        }
        if (!filtered && allowHttp1Filter && pendingHttp1Bytes.isNotEmpty()) {
            when (val assembled = HttpMitmFilter.finalizeBufferedHttp1Response(pendingHttp1Bytes)) {
                is HttpMitmFilter.BufferedHttp1Result.Ready -> {
                    val finalPayload = applyHttp1Filter(context, session, assembled.responseBytes, requestRef.get()) + assembled.remainderBytes
                    output.write(finalPayload)
                    output.flush()
                }
                is HttpMitmFilter.BufferedHttp1Result.Bypass -> {
                    LogRepository.append(context, "HTTPS response passthrough host=${session.host} reason=${assembled.reason}")
                    output.write(pendingHttp1Bytes)
                    output.flush()
                }
                HttpMitmFilter.BufferedHttp1Result.AwaitMore -> {
                    output.write(pendingHttp1Bytes)
                    output.flush()
                }
            }
        }
    }

    private fun applyHttp1Filter(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        payload: ByteArray,
        requestInspection: HttpMitmFilter.RequestInspection?
    ): ByteArray {
        return when (val result = HttpMitmFilter.filterResponse(session, payload, requestInspection)) {
            is HttpMitmFilter.FilterResult.PassThrough -> {
                result.payload
            }
            is HttpMitmFilter.FilterResult.Replaced -> {
                LogRepository.append(context, "HTTPS response neutralized host=${session.host} reason=${result.reason} originalBytes=${result.originalBytes}")
                val vendor = RuleRepository.classifyVendorFromHints(context, session.host, session.appName)
                StatsRepository.recordBlockedHttp(context, vendor, session.appName, result.originalBytes.toLong())
                result.payload
            }
        }
    }

    private fun processHttp2ClientPayload(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        parsedFrames: List<Http2FrameLogger.ParsedFrame>,
        payload: ByteArray,
        directivesPresent: Boolean
    ): ByteArray {
        if (parsedFrames.isEmpty()) return payload
        val parsedFrameBytes = parsedFrames.sumOf { it.rawBytes.size }
        val rewrittenPayload = rewriteHttp2ClientPayload(context, session, parsedFrames, payload)
        var nextPayload = rewrittenPayload
        if (rewrittenPayload.size != payload.size) {
            LogRepository.append(context, "HTTP/2 request payload rewritten host=${session.host} flow=${session.flowKey} direction=client originalBytes=${payload.size} rewrittenBytes=${rewrittenPayload.size} parsedBytes=$parsedFrameBytes")
        }
        if (parsedFrameBytes != nextPayload.size && directivesPresent) {
            LogRepository.append(context, "HTTP/2 frame filtering tail-preserved host=${session.host} flow=${session.flowKey} direction=client reason=partial-frame-boundary parsedBytes=$parsedFrameBytes payloadBytes=${nextPayload.size} tailBytes=${nextPayload.size - parsedFrameBytes}")
        }
        nextPayload = filterHttp2Payload(context, session, "client", parsedFrames, nextPayload)
        return nextPayload
    }

    private fun findIncompleteClientHeaderStreams(
        flowKey: String,
        parsedFrames: List<Http2FrameLogger.ParsedFrame>
    ): Set<Int> {
        if (parsedFrames.isEmpty()) return emptySet()
        val directionState = http2LogStates[http2LogKey(flowKey, "client")] ?: return emptySet()
        return parsedFrames
            .asSequence()
            .filter { it.type == 1 || it.type == 9 }
            .map { it.streamId }
            .filter { it > 0 }
            .filter { streamId -> directionState.streams[streamId]?.headerBlockOpen == true }
            .toCollection(linkedSetOf())
    }

    private fun directionHasOpenClientHeaderBlock(flowKey: String, streamId: Int): Boolean {
        val directionState = http2LogStates[http2LogKey(flowKey, "client")] ?: return false
        return directionState.streams[streamId]?.headerBlockOpen == true
    }

    private fun rewriteHttp2ClientPayload(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        parsedFrames: List<Http2FrameLogger.ParsedFrame>,
        originalPayload: ByteArray
    ): ByteArray {
        if (parsedFrames.isEmpty()) return originalPayload
        val directionState = http2LogStates[http2LogKey(session.flowKey, "client")] ?: return originalPayload
        val rebuiltFrames = ArrayList<ByteArray>(parsedFrames.size)
        var changed = false
        var index = 0
        while (index < parsedFrames.size) {
            val frame = parsedFrames[index]
            val canOpenRewriteSequence = frame.type == 1 &&
                frame.headerBlockFragment.isNotEmpty()
            if (!canOpenRewriteSequence) {
                rebuiltFrames += frame.rawBytes
                index += 1
                continue
            }
            val sequenceFrames = mutableListOf<Http2FrameLogger.ParsedFrame>()
            sequenceFrames += frame
            var sequenceIndex = index + 1
            var sequenceComplete = frame.endHeaders
            while (!sequenceComplete && sequenceIndex < parsedFrames.size) {
                val continuationFrame = parsedFrames[sequenceIndex]
                if (continuationFrame.streamId != frame.streamId || continuationFrame.type != 9) {
                    break
                }
                sequenceFrames += continuationFrame
                sequenceComplete = continuationFrame.endHeaders
                sequenceIndex += 1
            }
            val canRewriteSequence = sequenceComplete && sequenceFrames.all { candidate ->
                candidate.streamId == frame.streamId &&
                    (candidate.type == 1 || candidate.type == 9) &&
                    candidate.headerBlockFragment.isNotEmpty()
            }
            if (!canRewriteSequence) {
                rebuiltFrames += sequenceFrames.map { it.rawBytes }
                index += sequenceFrames.size
                continue
            }
            val streamState = directionState.streams[frame.streamId]
            val inspection = streamState?.lastHeaderInspection
            val decodedHeaders = streamState?.lastDecodedHeaders.orEmpty()
            if (inspection?.requestLike != true || decodedHeaders.isEmpty()) {
                rebuiltFrames += sequenceFrames.map { it.rawBytes }
                index += sequenceFrames.size
                continue
            }
            if (!HttpMitmFilter.shouldRewriteHttp2RequestHeaders(session, inspection)) {
                rebuiltFrames += sequenceFrames.map { it.rawBytes }
                index += sequenceFrames.size
                continue
            }
            val rewrite = HttpMitmFilter.rewriteHttp2RequestHeaders(session, inspection, decodedHeaders)
            if (!rewrite.changed) {
                rebuiltFrames += sequenceFrames.map { it.rawBytes }
                index += sequenceFrames.size
                continue
            }
            val encoded = HpackEncoder.encodeHeadersWithoutIndexing(rewrite.headers)
            rebuiltFrames += Http2FrameCodec.buildHeaderBlockFrames(
                streamId = frame.streamId,
                headerBlock = encoded,
                endStream = frame.endStream,
                padded = frame.padded,
                priorityFragment = frame.priorityFragment
            )
            changed = true
            LogRepository.append(
                context,
                "HTTP/2 request headers rewritten host=${session.host} flow=${session.flowKey} stream=${frame.streamId} headerCount=${rewrite.headers.size} originalHeaderCount=${decodedHeaders.size} originalFrames=${sequenceFrames.size} originalHeaderBytes=${sequenceFrames.sumOf { it.headerBlockFragment.size }} rewrittenHeaderBytes=${encoded.size} padded=${frame.padded} priority=${frame.priorityFragment.isNotEmpty()}"
            )
            index += sequenceFrames.size
        }
        if (!changed) return originalPayload
        val parsedBytes = parsedFrames.sumOf { it.rawBytes.size }
        val prefix = rebuiltFrames.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val tailBytes = if (parsedBytes >= originalPayload.size) ByteArray(0) else originalPayload.copyOfRange(parsedBytes, originalPayload.size)
        return prefix + tailBytes
    }

    private fun configureUpstreamTls(socket: SSLSocket, offeredAlpnProtocols: List<String>) {
        socket.useClientMode = true
        if (offeredAlpnProtocols.isNotEmpty()) {
            runCatching {
                val parameters = socket.sslParameters ?: SSLParameters()
                parameters.applicationProtocols = offeredAlpnProtocols.toTypedArray()
                socket.sslParameters = parameters
            }
        }
    }

    private fun configureLocalTls(socket: SSLSocket, offeredAlpnProtocols: List<String>) {
        socket.useClientMode = false
        if (offeredAlpnProtocols.isNotEmpty()) {
            runCatching {
                val parameters = socket.sslParameters ?: SSLParameters()
                parameters.applicationProtocols = offeredAlpnProtocols.toTypedArray()
                socket.sslParameters = parameters
            }
        }
    }

    private fun readApplicationProtocol(socket: SSLSocket): String? {
        return runCatching { socket.applicationProtocol }.getOrNull()?.ifBlank { null }
    }

    private fun logHttp2Events(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        events: List<Http2FrameLogger.FrameEvent>,
        direction: String
    ): List<Http2StreamDirective> {
        val key = http2LogKey(session.flowKey, direction)
        val state = http2LogStates.computeIfAbsent(key) { Http2LogState() }
        val directives = mutableListOf<Http2StreamDirective>()
        events.forEachIndexed { index, event ->
            when (event) {
                Http2FrameLogger.FrameEvent.ConnectionPreface -> {
                    state.prefaceSeen = true
                    LogRepository.append(
                        context,
                        "HTTP/2 preface host=${session.host} flow=${session.flowKey} direction=$direction"
                    )
                }

                is Http2FrameLogger.FrameEvent.FrameHeader -> {
                    state.frameCount += 1
                    state.totalPayloadBytes += event.length.toLong()
                    state.lastTypeName = event.typeName
                    state.lastStreamId = event.streamId
                    state.maxPayloadLength = maxOf(state.maxPayloadLength, event.length)
                    state.uniqueStreams += event.streamId.takeIf { it > 0 } ?: 0
                    when (event.type) {
                        0 -> state.dataFrames += 1
                        1 -> state.headersFrames += 1
                        4 -> state.settingsFrames += 1
                        6 -> state.pingFrames += 1
                        7 -> state.goAwayFrames += 1
                    }
                    if (event.endStream) state.endStreamFrames += 1
                    if (event.endHeaders) state.endHeadersFrames += 1
                    if (event.ack) state.ackFrames += 1
                    val shouldLogVerbose =
                        state.verboseFramesLogged < HTTP2_VERBOSE_FRAME_LIMIT ||
                            event.type == 7 ||
                            event.endStream ||
                            event.ack
                    if (shouldLogVerbose) {
                        state.verboseFramesLogged += 1
                        LogRepository.append(
                            context,
                            "HTTP/2 frame host=${session.host} flow=${session.flowKey} direction=$direction index=$index type=${event.typeName} length=${event.length} flags=0x${event.flags.toString(16)} flagNames=${event.flagNames.joinToString("|").ifBlank { "none" }} stream=${event.streamId} endStream=${event.endStream} endHeaders=${event.endHeaders} ack=${event.ack}"
                        )
                    }
                    if (state.frameCount % HTTP2_SUMMARY_FRAME_INTERVAL == 0) {
                        flushHttp2Summary(context, session.flowKey, direction, finalFlush = false, session = session)
                    }
                }

                is Http2FrameLogger.FrameEvent.StreamProgress -> {
                    val streamState = state.streams.getOrPut(event.streamId) {
                        Http2StreamLogState(streamId = event.streamId)
                    }
                    streamState.lastStage = event.stage

                    if (event.headerBlock) {
                        if (event.opensHeaderBlock && !streamState.headerBlockOpen) {
                            streamState.headerBlockOpen = true
                            streamState.headerBlockAbandoned = false
                            streamState.headerBlockCount += 1
                            streamState.currentHeaderBlockBytes = 0
                            streamState.currentHeaderBlock = ByteArray(0)
                        }
                        streamState.headerFrames += 1
                        streamState.headerBytes += event.payloadLength.toLong()
                        streamState.currentHeaderBlockBytes += event.payloadLength.toLong()
                        if (event.headerBlockFragment.isNotEmpty()) {
                            streamState.currentHeaderBlock += event.headerBlockFragment
                        }
                        streamState.maxHeaderBlockBytes = maxOf(
                            streamState.maxHeaderBlockBytes,
                            streamState.currentHeaderBlockBytes
                        )
                        if (streamState.currentHeaderBlockBytes >= HTTP2_HEADER_BLOCK_LARGE_THRESHOLD) {
                            streamState.largeHeaderBlockSeen = true
                        }
                    }

                    if (event.dataPayload) {
                        streamState.dataFrames += 1
                        streamState.dataBytes += event.payloadLength.toLong()
                        if (direction == "server" && event.dataFragment.isNotEmpty()) {
                            val dataInspection = HttpMitmFilter.inspectHttp2DataSample(
                                session = session,
                                headerInspection = streamState.lastHeaderInspection,
                                currentSample = streamState.lastDataSample,
                                incomingFragment = event.dataFragment
                            )
                            streamState.lastDataSample = dataInspection?.combinedSample
                                ?: trimHttp2DataSample(streamState.lastDataSample, event.dataFragment)
                            if (dataInspection != null) {
                                streamState.lastDataInspection = dataInspection
                                LogRepository.append(
                                    context,
                                    "HTTP/2 data inspection host=${session.host} flow=${session.flowKey} direction=$direction stream=${event.streamId} vendor=${dataInspection.vendor} suspiciousScore=${dataInspection.suspiciousScore} reasons=${dataInspection.suspiciousReasons.joinToString("|").ifBlank { "none" }} preview=${dataInspection.samplePreview.ifBlank { "none" }}"
                                )
                                if (!streamState.blockedByAction) {
                                    streamState.blockedByAction = true
                                    streamState.lastActionDecision = HttpMitmFilter.Http2ActionDecision(
                                        action = "response-data-candidate",
                                        confidence = dataInspection.confidence,
                                        shouldBlockCandidate = true,
                                        shouldSyntheticRespond = true
                                    )
                                    directives += Http2StreamDirective(
                                        streamId = event.streamId,
                                        action = "response-data-candidate",
                                        confidence = dataInspection.confidence,
                                        sendRst = false,
                                        syntheticResponse = Http2FrameCodec.buildNeutralizedResponseFrames(
                                            streamId = event.streamId,
                                            contentType = streamState.lastHeaderInspection?.contentType
                                        )
                                    )
                                    LogRepository.append(
                                        context,
                                        "HTTP/2 body-triggered block host=${session.host} flow=${session.flowKey} stream=${event.streamId} trigger=body confidence=${dataInspection.confidence} vendor=${dataInspection.vendor} reasons=${dataInspection.suspiciousReasons.joinToString("|").ifBlank { "none" }}"
                                    )
                                }
                            }
                        }
                    }

                    if (event.reset) {
                        streamState.reset = true
                        if (streamState.blockedByAction) {
                            streamState.terminalBlocked = true
                            streamState.streamClosed = true
                            if (streamState.headerBlockOpen) {
                                markBlockedHeaderBlockAbandoned(
                                    context,
                                    session,
                                    direction,
                                    streamState,
                                    reason = "rst-stream"
                                )
                            }
                        }
                    }

                    if (event.endHeaders) {
                        streamState.headersClosed = true
                        if (event.closesHeaderBlock && streamState.headerBlockOpen) {
                            streamState.headerBlockOpen = false
                            val decoded = HpackDecoder.decode(
                                streamState.currentHeaderBlock,
                                state.hpackDecoderState
                            )
                            streamState.lastDecodedHeaders = decoded.headers
                            streamState.lastDecodedHeaderError = decoded.error
                            streamState.lastDecodedHeaderTruncated = decoded.truncated
                            streamState.lastDecodedHeaderHuffmanCount = decoded.huffmanEncodedStrings
                            streamState.lastDecodedHeaderPreview = decoded.headers
                                .take(8)
                                .joinToString("; ") { "${it.name}=${it.value}" }
                            streamState.lastHeaderInspection = HttpMitmFilter.inspectHttp2Headers(
                                session,
                                decoded.headers
                            )
                            streamState.lastActionDecision = streamState.lastHeaderInspection
                                ?.let(HttpMitmFilter::decideHttp2Action)
                            LogRepository.append(
                                context,
                                "HTTP/2 headers complete host=${session.host} flow=${session.flowKey} direction=$direction stream=${event.streamId} blockIndex=${streamState.headerBlockCount} blockBytes=${streamState.currentHeaderBlockBytes} largeHeaderBlock=${streamState.currentHeaderBlockBytes >= HTTP2_HEADER_BLOCK_LARGE_THRESHOLD} totalHeaderBytes=${streamState.headerBytes}"
                            )
                            if (streamState.lastDecodedHeaders.isNotEmpty() || streamState.lastDecodedHeaderError != null) {
                                LogRepository.append(
                                    context,
                                    "HTTP/2 headers decoded host=${session.host} flow=${session.flowKey} direction=$direction stream=${event.streamId} decoded=${streamState.lastDecodedHeaders.size} huffmanStrings=${decoded.huffmanEncodedStrings} truncated=${decoded.truncated} error=${decoded.error ?: "none"} preview=${streamState.lastDecodedHeaderPreview.ifBlank { "none" }}"
                                )
                            }
                            streamState.lastHeaderInspection?.let { inspection ->
                                LogRepository.append(
                                    context,
                                    "HTTP/2 header inspection host=${session.host} flow=${session.flowKey} direction=$direction stream=${event.streamId} requestLike=${inspection.requestLike} responseLike=${inspection.responseLike} method=${inspection.method ?: "none"} authority=${inspection.authority} path=${inspection.path ?: "none"} status=${inspection.status ?: "none"} contentType=${inspection.contentType ?: "none"} location=${inspection.location ?: "none"} setCookie=${inspection.setCookie ?: "none"} vendor=${inspection.vendor} suspiciousScore=${inspection.suspiciousScore} reasons=${inspection.suspiciousReasons.joinToString("|").ifBlank { "none" }}"
                                )
                            }
                            streamState.lastActionDecision?.let { decision ->
                                val inspection = streamState.lastHeaderInspection
                                LogRepository.append(
                                    context,
                                    "HTTP/2 action decision host=${session.host} flow=${session.flowKey} direction=$direction stream=${event.streamId} action=${decision.action} confidence=${decision.confidence} blockCandidate=${decision.shouldBlockCandidate}"
                                )
                                if (decision.shouldBlockCandidate) {
                                    streamState.blockedByAction = true
                                    LogRepository.append(
                                        context,
                                        "HTTP/2 header-triggered block host=${session.host} flow=${session.flowKey} stream=${event.streamId} trigger=header action=${decision.action} confidence=${decision.confidence} vendor=${inspection?.vendor ?: "none"} reasons=${inspection?.suspiciousReasons?.joinToString("|")?.ifBlank { "none" } ?: "none"}"
                                    )
                                    directives += Http2StreamDirective(
                                        streamId = event.streamId,
                                        action = decision.action,
                                        confidence = decision.confidence,
                                        sendRst = !decision.shouldSyntheticRespond,
                                        syntheticResponse = if (decision.shouldSyntheticRespond) {
                                            Http2FrameCodec.buildNeutralizedResponseFrames(
                                                streamId = event.streamId,
                                                contentType = inspection?.contentType
                                            )
                                        } else {
                                            null
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (event.endStream) {
                        if (streamState.blockedByAction && streamState.headerBlockOpen) {
                            markBlockedHeaderBlockAbandoned(
                                context,
                                session,
                                direction,
                                streamState,
                                reason = "end-stream-before-end-headers"
                            )
                        }
                        if (streamState.blockedByAction) {
                            streamState.terminalBlocked = true
                        }
                        streamState.streamClosed = true
                        LogRepository.append(
                            context,
                            "HTTP/2 stream host=${session.host} flow=${session.flowKey} direction=$direction stream=${event.streamId} stage=${event.stage} headerFrames=${streamState.headerFrames} headerBytes=${streamState.headerBytes} dataFrames=${streamState.dataFrames} dataBytes=${streamState.dataBytes} headerBlocks=${streamState.headerBlockCount} maxHeaderBlockBytes=${streamState.maxHeaderBlockBytes} largeHeaderBlockSeen=${streamState.largeHeaderBlockSeen} decodedHeaders=${streamState.lastDecodedHeaders.size} headerDecodeError=${streamState.lastDecodedHeaderError ?: "none"} suspiciousScore=${streamState.lastHeaderInspection?.suspiciousScore ?: 0} action=${streamState.lastActionDecision?.action ?: "none"} headersClosed=${streamState.headersClosed} reset=${streamState.reset} closed=true"
                        )
                    }
                }
            }
        }
        return directives
    }

    private fun applyHttp2Directives(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        directives: List<Http2StreamDirective>,
        output: OutputStream,
        resetPeer: String
    ) {
        if (directives.isEmpty()) return
        val control = http2FlowControls.computeIfAbsent(session.flowKey) { Http2FlowControl() }
        directives.forEach { directive ->
            control.blockedStreams += directive.streamId
            if (directive.syntheticResponse != null && control.syntheticRespondedStreams.add(directive.streamId)) {
                output.write(directive.syntheticResponse)
                output.flush()
                control.terminalBlockedStreams += directive.streamId
                val vendor = RuleRepository.classifyVendorFromHints(context, session.host, session.appName)
                StatsRepository.recordBlockedHttp(context, vendor, session.appName, 100 * 1024)
                LogRepository.append(
                    context,
                    "HTTP/2 synthetic response host=${session.host} flow=${session.flowKey} stream=${directive.streamId} action=${directive.action} confidence=${directive.confidence} peer=$resetPeer bytes=${directive.syntheticResponse.size} sendRst=${directive.sendRst}"
                )
            }
            if (directive.sendRst && control.resetSentStreams.add(directive.streamId)) {
                writeHttp2RstStream(output, directive.streamId)
                val vendor = RuleRepository.classifyVendorFromHints(context, session.host, session.appName)
                StatsRepository.recordBlockedHttp(context, vendor, session.appName, 50 * 1024)
                LogRepository.append(
                    context,
                    "HTTP/2 stream reset host=${session.host} flow=${session.flowKey} stream=${directive.streamId} action=${directive.action} confidence=${directive.confidence} peer=$resetPeer"
                )
            }
        }
    }

    private fun filterHttp2Payload(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        parsedFrames: List<Http2FrameLogger.ParsedFrame>,
        originalPayload: ByteArray
    ): ByteArray {
        val flowKey = session.flowKey
        val control = http2FlowControls[flowKey] ?: return originalPayload
        if (control.blockedStreams.isEmpty() || parsedFrames.isEmpty()) return originalPayload
        val parsedBytes = parsedFrames.sumOf { it.rawBytes.size }
        val droppedFrames = parsedFrames.filter { frame ->
            frame.streamId > 0 && control.blockedStreams.contains(frame.streamId)
        }
        val keptFrames = parsedFrames.filter { frame ->
            frame.streamId <= 0 || !control.blockedStreams.contains(frame.streamId)
        }
        if (keptFrames.size == parsedFrames.size) return originalPayload
        val droppedBytes = droppedFrames.sumOf { it.rawBytes.size }
        val droppedStreams = droppedFrames.map { it.streamId }.distinct().sorted()
        val droppedTypes = droppedFrames.groupingBy { http2FrameTypeName(it.type) }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString("|") { "${it.key}:${it.value}" }
            .ifBlank { "none" }
        val syntheticTerminalStreams = control.syntheticRespondedStreams.intersect(droppedStreams.toSet())
        val terminalStreams = (droppedFrames.filter { it.type == 3 || it.endStream }.map { it.streamId } + syntheticTerminalStreams).distinct()
        val directionState = http2LogStates[http2LogKey(flowKey, direction)]
        droppedStreams.forEach { streamId ->
            val streamState = directionState?.streams?.get(streamId) ?: return@forEach
            if (streamState.headerBlockOpen) {
                markBlockedHeaderBlockAbandoned(context, session, direction, streamState, reason = "blocked-frame-filtered")
            }
            if (streamState.reset || streamState.terminalBlocked) {
                streamState.streamClosed = true
            }
        }
        if (terminalStreams.isNotEmpty()) {
            control.terminalBlockedStreams += terminalStreams
            terminalStreams.forEach { streamId ->
                directionState?.streams?.get(streamId)?.apply {
                    terminalBlocked = true
                    streamClosed = true
                }
            }
            // Record stats for newly blocked streams
            terminalStreams.forEach { streamId ->
                if (!control.terminalStatsRecorded.contains(streamId)) {
                    val vendor = RuleRepository.classifyVendorFromHints(context, session.host, session.appName)
                    StatsRepository.recordBlockedHttp(context, vendor, session.appName, 50 * 1024)
                    control.terminalStatsRecorded += streamId
                }
            }
        }
        val completedStreams = directionState?.streams
            ?.filterValues { it.streamClosed && (it.terminalBlocked || it.reset) }
            ?.keys
            .orEmpty()
        if (completedStreams.isNotEmpty()) {
            control.blockedStreams.removeAll(completedStreams)
            control.resetSentStreams.removeAll(completedStreams)
            control.syntheticRespondedStreams.removeAll(completedStreams)
            control.terminalStatsRecorded.removeAll(completedStreams)
            control.terminalBlockedStreams.removeAll(completedStreams)
        }
        val filteredPrefix = keptFrames.fold(ByteArray(0)) { acc, frame -> acc + frame.rawBytes }
        val tailBytes = if (parsedBytes >= originalPayload.size) ByteArray(0) else originalPayload.copyOfRange(parsedBytes, originalPayload.size)
        LogRepository.append(
            context,
            "HTTP/2 blocked frame drop host=${session.host} flow=$flowKey direction=$direction droppedFrames=${droppedFrames.size} droppedBytes=$droppedBytes droppedStreams=${droppedStreams.joinToString(",").ifBlank { "none" }} droppedTypes=$droppedTypes terminalStreams=${terminalStreams.joinToString(",").ifBlank { "none" }} tailBytes=${tailBytes.size}"
        )
        if (tailBytes.isEmpty()) return filteredPrefix
        return filteredPrefix + tailBytes
    }

    private fun http2FrameTypeName(type: Int): String {
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

    private fun markBlockedHeaderBlockAbandoned(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamState: Http2StreamLogState,
        reason: String
    ) {
        if (!streamState.headerBlockOpen) return
        streamState.headerBlockOpen = false
        streamState.headerBlockAbandoned = true
        streamState.headersClosed = false
        streamState.currentHeaderBlock = ByteArray(0)
        streamState.currentHeaderBlockBytes = 0
        LogRepository.append(
            context,
            "HTTP/2 blocked header block abandoned host=${session.host} flow=${session.flowKey} direction=$direction stream=${streamState.streamId} reason=$reason headerBlocks=${streamState.headerBlockCount} totalHeaderBytes=${streamState.headerBytes}"
        )
    }

    private fun writeHttp2RstStream(output: OutputStream, streamId: Int) {
        val frame = Http2FrameCodec.buildRstStreamFrame(streamId)
        output.write(frame)
        output.flush()
    }

    private fun flushHttp2Summary(
        context: Context,
        flowKey: String,
        direction: String,
        finalFlush: Boolean,
        session: TlsMitmSessionManager.TlsMitmSession? = null
    ) {
        val key = http2LogKey(flowKey, direction)
        val state = if (finalFlush) http2LogStates.remove(key) else http2LogStates[key]
        if (state == null || state.frameCount == 0 || state.frameCount == state.lastSummaryFrameCount) return
        val resolvedSession = session ?: TlsMitmSessionManager.getSession(flowKey)
        val host = resolvedSession?.host ?: "unknown"
        val flowControl = http2FlowControls[flowKey]
        LogRepository.append(
            context,
            "HTTP/2 summary host=$host flow=$flowKey direction=$direction frames=${state.frameCount} headers=${state.headersFrames} data=${state.dataFrames} settings=${state.settingsFrames} ping=${state.pingFrames} goAway=${state.goAwayFrames} endStream=${state.endStreamFrames} endHeaders=${state.endHeadersFrames} ack=${state.ackFrames} streams=${state.uniqueStreams.size} activeStreamProfiles=${state.streams.size} lastStream=${state.lastStreamId ?: 0} maxFramePayload=${state.maxPayloadLength} payloadBytes=${state.totalPayloadBytes} verboseLogged=${state.verboseFramesLogged} blockedStreams=${flowControl?.blockedStreams?.size ?: 0} syntheticStreams=${flowControl?.syntheticRespondedStreams?.size ?: 0} terminalBlockedStreams=${flowControl?.terminalBlockedStreams?.size ?: 0} final=$finalFlush"
        )
        if (finalFlush) {
            state.streams.values
                .sortedBy { it.streamId }
                .forEach { stream ->
                    LogRepository.append(
                        context,
                        "HTTP/2 stream summary host=$host flow=$flowKey direction=$direction stream=${stream.streamId} lastStage=${stream.lastStage ?: "unknown"} headerFrames=${stream.headerFrames} headerBytes=${stream.headerBytes} dataFrames=${stream.dataFrames} dataBytes=${stream.dataBytes} headerBlocks=${stream.headerBlockCount} maxHeaderBlockBytes=${stream.maxHeaderBlockBytes} largeHeaderBlockSeen=${stream.largeHeaderBlockSeen} headerBlockOpen=${stream.headerBlockOpen} headerBlockAbandoned=${stream.headerBlockAbandoned} decodedHeaders=${stream.lastDecodedHeaders.size} headerDecodeError=${stream.lastDecodedHeaderError ?: "none"} headerScore=${stream.lastHeaderInspection?.suspiciousScore ?: 0} headerReasons=${stream.lastHeaderInspection?.suspiciousReasons?.joinToString("|")?.ifBlank { "none" } ?: "none"} dataScore=${stream.lastDataInspection?.suspiciousScore ?: 0} dataReasons=${stream.lastDataInspection?.suspiciousReasons?.joinToString("|")?.ifBlank { "none" } ?: "none"} dataPreview=${stream.lastDataInspection?.samplePreview?.ifBlank { "none" } ?: "none"} action=${stream.lastActionDecision?.action ?: "none"} actionConfidence=${stream.lastActionDecision?.confidence ?: "none"} blocked=${stream.blockedByAction} terminalBlocked=${stream.terminalBlocked} headerPreview=${stream.lastDecodedHeaderPreview.ifBlank { "none" }} headersClosed=${stream.headersClosed} reset=${stream.reset} closed=${stream.streamClosed}"
                    )
                }
        }
        state.lastSummaryFrameCount = state.frameCount
    }

    private fun trimHttp2DataSample(existing: ByteArray, incoming: ByteArray): ByteArray {
        val maxBytes = 8 * 1024
        if (existing.size >= maxBytes) return existing.copyOf(maxBytes)
        val remaining = maxBytes - existing.size
        val addition = if (incoming.size <= remaining) incoming else incoming.copyOf(remaining)
        return existing + addition
    }

    private fun classifySslHandshakeBypassReason(error: SSLHandshakeException): String {
        val message = error.message?.trim().orEmpty()
        val normalized = message.lowercase()
        val category = when {
            listOf("pin", "certificate pin", "public key pin").any { normalized.contains(it) } -> "ssl-pinning"
            listOf("trust anchor", "unable to find valid certification path", "path validation", "certificate_unknown").any { normalized.contains(it) } -> "ssl-trust-anchor"
            listOf("hostname", "host name", "no subject alternative names", "subject alternative").any { normalized.contains(it) } -> "ssl-hostname"
            listOf("certificate expired", "notafter", "notbefore", "expired").any { normalized.contains(it) } -> "ssl-certificate-time"
            listOf("handshake_failure", "protocol_version", "unsupported protocol", "remote host terminated").any { normalized.contains(it) } -> "ssl-protocol"
            else -> "ssl-handshake"
        }
        val detail = if (message.isBlank()) error.javaClass.simpleName else message
        return "$category:$detail"
    }

    private fun http2LogKey(flowKey: String, direction: String): String = "$flowKey|$direction"

    private data class RunningBridge(
        val flowKey: String,
        val host: String,
        val port: Int,
        val serverSocket: SSLServerSocket
    ) {
        fun close() {
            runCatching { serverSocket.close() }
        }
    }

    private data class Http2LogState(
        var prefaceSeen: Boolean = false,
        var frameCount: Int = 0,
        var verboseFramesLogged: Int = 0,
        var headersFrames: Int = 0,
        var dataFrames: Int = 0,
        var settingsFrames: Int = 0,
        var pingFrames: Int = 0,
        var goAwayFrames: Int = 0,
        var endStreamFrames: Int = 0,
        var endHeadersFrames: Int = 0,
        var ackFrames: Int = 0,
        var maxPayloadLength: Int = 0,
        var totalPayloadBytes: Long = 0,
        var lastTypeName: String? = null,
        var lastStreamId: Int? = null,
        var lastSummaryFrameCount: Int = 0,
        val uniqueStreams: MutableSet<Int> = linkedSetOf(),
        val streams: MutableMap<Int, Http2StreamLogState> = linkedMapOf(),
        val hpackDecoderState: HpackDecoder.DecoderState = HpackDecoder.DecoderState()
    )

    private data class Http2FlowControl(
        val blockedStreams: MutableSet<Int> = linkedSetOf(),
        val resetSentStreams: MutableSet<Int> = linkedSetOf(),
        val syntheticRespondedStreams: MutableSet<Int> = linkedSetOf(),
        val terminalBlockedStreams: MutableSet<Int> = linkedSetOf(),
        val terminalStatsRecorded: MutableSet<Int> = linkedSetOf()
    )

    private data class Http2StreamDirective(
        val streamId: Int,
        val action: String,
        val confidence: String,
        val sendRst: Boolean,
        val syntheticResponse: ByteArray? = null
    )

    private data class PendingHttp2ClientRewrite(
        val active: Boolean = false,
        var rawBytes: ByteArray = ByteArray(0),
        val parsedFrames: MutableList<Http2FrameLogger.ParsedFrame> = mutableListOf(),
        val openStreams: MutableSet<Int> = linkedSetOf()
    )

    private data class Http2StreamLogState(
        val streamId: Int,
        var lastStage: String? = null,
        var headerFrames: Int = 0,
        var headerBytes: Long = 0,
        var dataFrames: Int = 0,
        var dataBytes: Long = 0,
        var headerBlockCount: Int = 0,
        var currentHeaderBlockBytes: Long = 0,
        var currentHeaderBlock: ByteArray = ByteArray(0),
        var maxHeaderBlockBytes: Long = 0,
        var headerBlockOpen: Boolean = false,
        var headerBlockAbandoned: Boolean = false,
        var largeHeaderBlockSeen: Boolean = false,
        var lastDecodedHeaders: List<HpackDecoder.HeaderField> = emptyList(),
        var lastDecodedHeaderPreview: String = "",
        var lastDecodedHeaderError: String? = null,
        var lastDecodedHeaderTruncated: Boolean = false,
        var lastDecodedHeaderHuffmanCount: Int = 0,
        var lastHeaderInspection: HttpMitmFilter.Http2HeaderInspection? = null,
        var lastDataInspection: HttpMitmFilter.Http2DataInspection? = null,
        var lastDataSample: ByteArray = ByteArray(0),
        var lastActionDecision: HttpMitmFilter.Http2ActionDecision? = null,
        var blockedByAction: Boolean = false,
        var terminalBlocked: Boolean = false,
        var headersClosed: Boolean = false,
        var reset: Boolean = false,
        var streamClosed: Boolean = false
    )

    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val MAX_PENDING_HTTP2_CLIENT_REWRITE_BYTES = 64 * 1024
}
