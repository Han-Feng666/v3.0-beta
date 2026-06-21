package com.HanFeng.service

import android.content.Context
import com.HanFeng.core.network.MitmLearningEngine
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import com.HanFeng.service.HpackDecoder.HeaderField

object HttpsTlsBridgeManager {
    private const val ACCEPT_TIMEOUT_MILLIS = 1_000
    private const val CONNECT_TIMEOUT_MILLIS = 4_000
    private const val HTTP2_VERBOSE_FRAME_LIMIT = 4
    private const val HTTP2_SUMMARY_FRAME_INTERVAL = 100
    private const val HTTP2_HEADER_BLOCK_LARGE_THRESHOLD = 16 * 1024
    private const val HTTP2_DATA_DEEP_INSPECTION_SCORE_THRESHOLD = 2
    private const val HTTP2_DATA_BODY_LIMIT_BYTES = 64 * 1024
    private const val BRIDGE_EXECUTOR_CORE_THREADS = 2
    private const val BRIDGE_EXECUTOR_MAX_THREADS = 8
    private const val BRIDGE_EXECUTOR_QUEUE_CAPACITY = 64
    private val bridges = ConcurrentHashMap<String, RunningBridge>()
    private val http2LogStates = ConcurrentHashMap<String, Http2LogState>()
    private val http2FlowControls = ConcurrentHashMap<String, Http2FlowControl>()
    private val http2StateLock = Any()
    private val executor = ThreadPoolExecutor(
        BRIDGE_EXECUTOR_CORE_THREADS,
        BRIDGE_EXECUTOR_MAX_THREADS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(BRIDGE_EXECUTOR_QUEUE_CAPACITY),
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    fun ensureBridge(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        preparedContext: com.HanFeng.security.TlsMitmContextFactory.PreparedTlsContext,
        protectSocket: (Socket) -> Boolean
    ): TlsMitmSessionManager.BridgeBinding {
        bridges[session.flowKey]?.takeIf { !it.serverSocket.isClosed }?.let {
            return TlsMitmSessionManager.BridgeBinding(it.host, it.port)
        }
        val serverSocket = preparedContext.sslContext.serverSocketFactory.createServerSocket(
            0,
            50,
            InetAddress.getByName(LOOPBACK_HOST)
        ) as? SSLServerSocket ?: throw IOException("TLS server socket creation failed")
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
        if (!executeBridgeTask(context, session, running, "accept-loop") {
            acceptLoop(context, session, running, protectSocket)
        }) {
            bridges.remove(session.flowKey, running)
            running.close()
            throw IOException("HTTPS TLS bridge executor saturated")
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
                    executeBridgeTask(context, session, client, "client") {
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

    private fun executeBridgeTask(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        closeOnReject: AutoCloseable,
        stage: String,
        task: () -> Unit
    ): Boolean {
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            TlsMitmSessionManager.markMitmBypass(context, session.flowKey, "bridge-executor-saturated:$stage")
            LogRepository.append(
                context,
                "HTTPS TLS bridge overloaded stage=$stage host=${session.host} flow=${session.flowKey} active=${executor.activeCount} queue=${executor.queue.size}"
            )
            closeOnReject.close()
            false
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
                configureLocalTls(localTls, session.offeredAlpnProtocols, session.clientHelloSupportedTlsVersions)
                localTls.startHandshake()
                val upstreamTls = createUpstreamTlsSocket(context, session, protectSocket)
                upstreamTls.use { remoteTls ->
                    localTls.tcpNoDelay = true
                    remoteTls.tcpNoDelay = true
                    configureUpstreamTls(remoteTls, session.offeredAlpnProtocols, session.clientHelloSupportedTlsVersions)
                    remoteTls.startHandshake()
                    val negotiatedAlpn = readApplicationProtocol(remoteTls)
                    val negotiatedTls = remoteTls.session?.protocol
                    logNegotiatedTlsSession(context, session, running, negotiatedAlpn, negotiatedTls)
                    pipeBridgeTraffic(context, session, localTls, remoteTls, negotiatedAlpn)
                }
            }
        } catch (error: SSLHandshakeException) {
            TlsMitmSessionManager.markMitmBypass(context, session.flowKey, classifySslHandshakeBypassReason(error))
            MitmLearningEngine.markCertPinningFailure(session.host)
            LogRepository.append(context, "HTTPS MITM handshake bypass host=${session.host} flow=${session.flowKey}: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: IOException) {
            TlsMitmSessionManager.markMitmBypass(context, session.flowKey, "io-bridge:${error.message ?: error.javaClass.simpleName}")
            LogRepository.append(context, "HTTPS MITM bridge failure host=${session.host} flow=${session.flowKey}: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun createUpstreamTlsSocket(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        protectSocket: (Socket) -> Boolean
    ): SSLSocket {
        val rawUpstreamSocket = Socket()
        rawUpstreamSocket.tcpNoDelay = true
        if (!protectSocket(rawUpstreamSocket)) {
            LogRepository.append(context, "Protect upstream socket failed for HTTPS bridge host=${session.host}")
        }
        rawUpstreamSocket.connect(InetSocketAddress(session.targetIp, session.targetPort), CONNECT_TIMEOUT_MILLIS)
        val upstreamFactory = javax.net.ssl.SSLSocketFactory.getDefault() as? javax.net.ssl.SSLSocketFactory
            ?: throw IOException("Upstream TLS socket factory unavailable")
        return upstreamFactory
            .createSocket(rawUpstreamSocket, session.host, session.targetPort, true) as? SSLSocket
            ?: throw IOException("Upstream TLS socket creation failed")
    }

    private fun logNegotiatedTlsSession(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        running: RunningBridge,
        negotiatedAlpn: String?,
        negotiatedTls: String?
    ) {
        TlsMitmSessionManager.updateNegotiatedProtocol(context, session.flowKey, negotiatedAlpn, negotiatedTls)
        LogRepository.append(
            context,
            "Accepted local HTTPS bridge client host=${session.host} flow=${session.flowKey} local=${running.host}:${running.port} alpn=${negotiatedAlpn ?: "none"} negotiatedHttp2=${negotiatedAlpn.equals("h2", ignoreCase = true)}"
        )
    }

    private fun pipeBridgeTraffic(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        localTls: SSLSocket,
        remoteTls: SSLSocket,
        negotiatedAlpn: String?
    ) {
        val requestRef = java.util.concurrent.atomic.AtomicReference<RequestInspection?>()
        val latch = CountDownLatch(2)
        if (!executeBridgeTask(context, session, RunningBridgeHandle(localTls), "pipe-client") {
            try {
                pipeClientToServer(context, session, localTls.inputStream, remoteTls.outputStream, requestRef, negotiatedAlpn)
            } finally {
                latch.countDown()
                runCatching { remoteTls.shutdownOutput() }
            }
        }) {
            return
        }
        try {
            pipeServerToClient(context, session, remoteTls.inputStream, localTls.outputStream, requestRef, negotiatedAlpn)
        } finally {
            latch.countDown()
            runCatching { localTls.shutdownOutput() }
        }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun pipeClientToServer(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        input: InputStream,
        output: OutputStream,
        requestRef: java.util.concurrent.atomic.AtomicReference<RequestInspection?>,
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
                    pendingHttp2ClientRewrite = appendPendingHttp2ClientRewrite(
                        session,
                        pendingHttp2ClientRewrite,
                        payload,
                        inspection.parsedFrames
                    )
                    if (pendingHttp2ClientRewrite.rawBytes.size > MAX_PENDING_HTTP2_CLIENT_REWRITE_BYTES) {
                        flushPendingHttp2ClientRewriteBypass(context, session, output, pendingHttp2ClientRewrite, "buffer-overflow")
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
                    if (logAndCheckSuppressedHttp2Payload(context, session, payload, "client")) {
                        continue
                    }
                    writeAndFlush(output, payload)
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
                if (logAndCheckSuppressedHttp2Payload(context, session, payload, "client")) {
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
            writeAndFlush(output, payload)
        }
        if (pendingHttp2ClientRewrite.active && pendingHttp2ClientRewrite.rawBytes.isNotEmpty()) {
            val finalPayload = finalizePendingHttp2ClientRewrite(context, session, pendingHttp2ClientRewrite, http2State)
            if (finalPayload.isNotEmpty()) {
                writeAndFlush(output, finalPayload)
            }
        }
    }

    private fun pipeServerToClient(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        input: InputStream,
        output: OutputStream,
        requestRef: java.util.concurrent.atomic.AtomicReference<RequestInspection?>,
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
                    logHttp2TailPreserved(context, session, "server", parsedFrameBytes, payload.size)
                }
                payload = filterHttp2Payload(context, session, "server", inspection.parsedFrames, payload)
                if (logAndCheckSuppressedHttp2Payload(context, session, payload, "server")) {
                    continue
                }
            }
            if (!filtered && allowHttp1Filter) {
                val bufferedPayload = pendingHttp1Bytes + payload
                if (bufferedPayload.size > HttpMitmFilter.maxHttp1FilterBufferBytes()) {
                    logHttp1ResponsePassthrough(context, session, "http1-buffer-overflow bytes=${bufferedPayload.size}")
                    payload = bufferedPayload
                    pendingHttp1Bytes = ByteArray(0)
                    filtered = true
                } else {
                    when (val assembled = HttpMitmFilter.inspectBufferedHttp1Response(bufferedPayload, requestRef.get())) {
                        BufferedHttp1Result.AwaitMore -> {
                            pendingHttp1Bytes = bufferedPayload
                            continue
                        }
                        is BufferedHttp1Result.Bypass -> {
                            logHttp1ResponsePassthrough(context, session, assembled.reason)
                            payload = bufferedPayload
                            pendingHttp1Bytes = ByteArray(0)
                            filtered = true
                        }
                        is BufferedHttp1Result.Ready -> {
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
            writeAndFlush(output, payload)
        }
        if (!filtered && allowHttp1Filter && pendingHttp1Bytes.isNotEmpty()) {
            when (val assembled = HttpMitmFilter.finalizeBufferedHttp1Response(pendingHttp1Bytes)) {
                is BufferedHttp1Result.Ready -> {
                    val finalPayload = applyHttp1Filter(context, session, assembled.responseBytes, requestRef.get()) + assembled.remainderBytes
                    writeAndFlush(output, finalPayload)
                }
                is BufferedHttp1Result.Bypass -> {
                    logHttp1ResponsePassthrough(context, session, assembled.reason)
                    writeAndFlush(output, pendingHttp1Bytes)
                }
                BufferedHttp1Result.AwaitMore -> {
                    writeAndFlush(output, pendingHttp1Bytes)
                }
            }
        }
    }

    private fun applyHttp1Filter(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        payload: ByteArray,
        requestInspection: RequestInspection?
    ): ByteArray {
        return when (val result = HttpMitmFilter.filterResponse(session, payload, requestInspection)) {
            is FilterResult.PassThrough -> {
                result.payload
            }
            is FilterResult.Replaced -> {
                LogRepository.append(context, "HTTPS response neutralized host=${session.host} reason=${result.reason} originalBytes=${result.originalBytes}")
                val vendor = RuleRepository.classifyVendorFromHints(context, session.host, session.appName)
                StatsRepository.recordBlockedMitm(context, vendor, session.appName, result.originalBytes.toLong())
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
            logHttp2TailPreserved(context, session, "client", parsedFrameBytes, nextPayload.size)
        }
        nextPayload = filterHttp2Payload(context, session, "client", parsedFrames, nextPayload)
        return nextPayload
    }

    private fun writeAndFlush(output: OutputStream, payload: ByteArray) {
        output.write(payload)
        output.flush()
    }

    private fun logHttp1ResponsePassthrough(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        reason: String
    ) {
        LogRepository.append(context, "HTTPS response passthrough host=${session.host} reason=$reason")
    }

    private fun logHttp2TailPreserved(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        parsedBytes: Int,
        payloadBytes: Int
    ) {
        LogRepository.append(
            context,
            "HTTP/2 frame filtering tail-preserved host=${session.host} flow=${session.flowKey} direction=$direction reason=partial-frame-boundary parsedBytes=$parsedBytes payloadBytes=$payloadBytes tailBytes=${payloadBytes - parsedBytes}"
        )
    }

    private fun appendPendingHttp2ClientRewrite(
        session: TlsMitmSessionManager.TlsMitmSession,
        pendingRewrite: PendingHttp2ClientRewrite,
        payload: ByteArray,
        parsedFrames: List<Http2FrameLogger.ParsedFrame>
    ): PendingHttp2ClientRewrite {
        pendingRewrite.rawBytes += payload
        pendingRewrite.parsedFrames += parsedFrames
        pendingRewrite.openStreams.removeAll { streamId ->
            !directionHasOpenClientHeaderBlock(session.flowKey, streamId)
        }
        return pendingRewrite
    }

    private fun flushPendingHttp2ClientRewriteBypass(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        output: OutputStream,
        pendingRewrite: PendingHttp2ClientRewrite,
        reason: String
    ) {
        logHttp2ClientRewriteBypass(
            context = context,
            session = session,
            reason = reason,
            detail = "bytes=${pendingRewrite.rawBytes.size}"
        )
        writeAndFlush(output, pendingRewrite.rawBytes)
    }

    private fun logAndCheckSuppressedHttp2Payload(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        payload: ByteArray,
        direction: String
    ): Boolean {
        if (payload.isNotEmpty()) return false
        logHttp2PayloadSuppressed(context, session, direction)
        return true
    }

    private fun logHttp2PayloadSuppressed(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String
    ) {
        appendHttp2Log(context, session, "HTTP/2 payload suppressed direction=$direction")
    }

    private fun finalizePendingHttp2ClientRewrite(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        pendingRewrite: PendingHttp2ClientRewrite,
        http2State: Http2FrameLogger.StreamState
    ): ByteArray {
        if (pendingRewrite.openStreams.isEmpty() && http2State.pendingFrameBytes.isEmpty()) {
            return processHttp2ClientPayload(
                context,
                session,
                pendingRewrite.parsedFrames,
                pendingRewrite.rawBytes,
                directivesPresent = false
            )
        }
        logHttp2ClientRewriteBypass(
            context = context,
            session = session,
            reason = "stream-ended-before-complete",
            detail = "headersStreams=${pendingRewrite.openStreams.sorted().joinToString(",").ifBlank { "none" }} pendingFrameBytes=${http2State.pendingFrameBytes.size}"
        )
        return pendingRewrite.rawBytes
    }

    private fun logHttp2ClientRewriteBypass(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        reason: String,
        detail: String
    ) {
        LogRepository.append(
            context,
            "HTTP/2 client rewrite bypass host=${session.host} flow=${session.flowKey} reason=$reason $detail"
        )
    }

    private fun findIncompleteClientHeaderStreams(
        flowKey: String,
        parsedFrames: List<Http2FrameLogger.ParsedFrame>
    ): Set<Int> = synchronized(http2StateLock) {
        if (parsedFrames.isEmpty()) return@synchronized emptySet()
        val directionState = http2LogStates[http2LogKey(flowKey, "client")] ?: return@synchronized emptySet()
        parsedFrames
            .asSequence()
            .filter { it.type == 1 || it.type == 9 }
            .map { it.streamId }
            .filter { it > 0 }
            .filter { streamId -> directionState.streams[streamId]?.headerBlockOpen == true }
            .toCollection(linkedSetOf())
    }

    private fun directionHasOpenClientHeaderBlock(flowKey: String, streamId: Int): Boolean = synchronized(http2StateLock) {
        val directionState = http2LogStates[http2LogKey(flowKey, "client")] ?: return@synchronized false
        directionState.streams[streamId]?.headerBlockOpen == true
    }

    private fun rewriteHttp2ClientPayload(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        parsedFrames: List<Http2FrameLogger.ParsedFrame>,
        originalPayload: ByteArray
    ): ByteArray = synchronized(http2StateLock) {
        if (parsedFrames.isEmpty()) return@synchronized originalPayload
        val directionState = http2LogStates[http2LogKey(session.flowKey, "client")] ?: return@synchronized originalPayload
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
            logHttp2RequestHeadersRewritten(
                context = context,
                session = session,
                streamId = frame.streamId,
                headerCount = rewrite.headers.size,
                originalHeaderCount = decodedHeaders.size,
                originalFrames = sequenceFrames.size,
                originalHeaderBytes = sequenceFrames.sumOf { it.headerBlockFragment.size },
                rewrittenHeaderBytes = encoded.size,
                padded = frame.padded,
                priority = frame.priorityFragment.isNotEmpty()
            )
            index += sequenceFrames.size
        }
        if (!changed) return@synchronized originalPayload
        val parsedBytes = parsedFrames.sumOf { it.rawBytes.size }
        val prefix = concatByteArrays(rebuiltFrames)
        val tailBytes = if (parsedBytes >= originalPayload.size) ByteArray(0) else originalPayload.copyOfRange(parsedBytes, originalPayload.size)
        prefix + tailBytes
    }

    private fun logHttp2RequestHeadersRewritten(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        streamId: Int,
        headerCount: Int,
        originalHeaderCount: Int,
        originalFrames: Int,
        originalHeaderBytes: Int,
        rewrittenHeaderBytes: Int,
        padded: Boolean,
        priority: Boolean
    ) {
        LogRepository.append(
            context,
            "HTTP/2 request headers rewritten host=${session.host} flow=${session.flowKey} stream=$streamId headerCount=$headerCount originalHeaderCount=$originalHeaderCount originalFrames=$originalFrames originalHeaderBytes=$originalHeaderBytes rewrittenHeaderBytes=$rewrittenHeaderBytes padded=$padded priority=$priority"
        )
    }

    private fun configureUpstreamTls(socket: SSLSocket, offeredAlpnProtocols: List<String>, clientSupportedTlsVersions: List<String>) {
        socket.useClientMode = true
        val filteredProtocols = filterSupportedTlsProtocols(socket.supportedProtocols.orEmpty().toList(), clientSupportedTlsVersions)
        if (filteredProtocols.isNotEmpty()) {
            runCatching {
                socket.enabledProtocols = filteredProtocols.toTypedArray()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && offeredAlpnProtocols.isNotEmpty()) {
            runCatching {
                val parameters = socket.sslParameters ?: SSLParameters()
                parameters.applicationProtocols = offeredAlpnProtocols.distinct().toTypedArray()
                socket.sslParameters = parameters
            }
        }
    }

    private fun configureLocalTls(socket: SSLSocket, offeredAlpnProtocols: List<String>, clientSupportedTlsVersions: List<String>) {
        socket.useClientMode = false
        val filteredProtocols = filterSupportedTlsProtocols(socket.supportedProtocols.orEmpty().toList(), clientSupportedTlsVersions)
        if (filteredProtocols.isNotEmpty()) {
            runCatching {
                socket.enabledProtocols = filteredProtocols.toTypedArray()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && offeredAlpnProtocols.isNotEmpty()) {
            runCatching {
                val parameters = socket.sslParameters ?: SSLParameters()
                parameters.applicationProtocols = offeredAlpnProtocols.distinct().toTypedArray()
                socket.sslParameters = parameters
            }
        }
    }

    private fun filterSupportedTlsProtocols(socketProtocols: List<String>, clientSupportedTlsVersions: List<String>): List<String> {
        if (socketProtocols.isEmpty()) return emptyList()
        if (clientSupportedTlsVersions.isEmpty()) return socketProtocols
        val normalizedClient = clientSupportedTlsVersions
            .map { normalizeTlsProtocolName(it) }
            .filter { it.isNotBlank() }
            .toSet()
        if (normalizedClient.isEmpty()) return socketProtocols
        val filtered = socketProtocols.filter { normalizeTlsProtocolName(it) in normalizedClient }
        return if (filtered.isNotEmpty()) filtered else socketProtocols
    }

    private fun normalizeTlsProtocolName(value: String): String {
        return value.trim().uppercase()
            .replace("TLSV", "TLS")
            .replace("TLS ", "TLS")
    }

    private fun readApplicationProtocol(socket: SSLSocket): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
        return runCatching { socket.applicationProtocol }.getOrNull()?.ifBlank { null }
    }

    private fun logHttp2Events(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        events: List<Http2FrameLogger.FrameEvent>,
        direction: String
    ): List<Http2StreamDirective> = synchronized(http2StateLock) {
        val key = http2LogKey(session.flowKey, direction)
        val state = http2LogStates.computeIfAbsent(key) { Http2LogState() }
        val directives = mutableListOf<Http2StreamDirective>()
        events.forEachIndexed { index, event ->
            when (event) {
                Http2FrameLogger.FrameEvent.ConnectionPreface -> {
                    state.prefaceSeen = true
                    logHttp2Preface(context, session, direction)
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
                        logHttp2Frame(
                            context = context,
                            session = session,
                            direction = direction,
                            index = index,
                            event = event
                        )
                    }
                    if (event.goAway) {
                        http2FlowControls.computeIfAbsent(session.flowKey) { Http2FlowControl() }.goAwaySeen = true
                        cleanupTerminalHttp2Streams(context, session, direction, reason = "goaway-observed")
                        logHttp2GoAwayObserved(context, session, direction, index)
                    }
                    if (event.closedStreamFrame) {
                        logHttp2ClosedStreamFrame(context, session, direction, index, event.streamId, event.typeName)
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
                        if (event.unexpectedContinuation) {
                            streamState.headerBlockAbandoned = true
                            streamState.lastDecodedHeaderError = "unexpected-continuation"
                            logHttp2ContinuationWithoutHeaderBlock(context, session, direction, event.streamId)
                        }
                        if (event.replacedOpenHeaderBlock) {
                            streamState.headerBlockAbandoned = true
                            streamState.lastDecodedHeaderError = "interleaved-header-block"
                            logHttp2HeaderBlockReplaced(
                                context,
                                session,
                                direction,
                                event.streamId,
                                streamState.currentHeaderBlockBytes
                            )
                        }
                        if (event.opensHeaderBlock && !event.headerBlockOpenBeforeFrame) {
                            streamState.headerBlockOpen = true
                            streamState.headerBlockAbandoned = false
                            streamState.headerBlockCount += 1
                            streamState.currentHeaderBlockBytes = 0
                            streamState.currentHeaderBlock = ByteArray(0)
                            streamState.headersClosed = false
                        }
                        if (event.opensHeaderBlock && event.headerBlockOpenBeforeFrame && streamState.currentHeaderBlockBytes > 0L) {
                            streamState.headerBlockCount += 1
                            streamState.currentHeaderBlockBytes = 0
                            streamState.currentHeaderBlock = ByteArray(0)
                            streamState.headersClosed = false
                            streamState.headerBlockAbandoned = false
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
                        streamState.headerBlockOpen = event.headerBlockOpenAfterFrame
                    }

                    if (event.dataPayload) {
                        streamState.dataFrames += 1
                        streamState.dataBytes += event.payloadLength.toLong()
                        if (direction == "server" && event.dataFragment.isNotEmpty()) {
                            val headerInspection = streamState.lastHeaderInspection
                            val shouldInspectData = headerInspection != null &&
                                !streamState.blockedByAction &&
                                headerInspection.responseLike &&
                                (headerInspection.suspiciousScore >= HTTP2_DATA_DEEP_INSPECTION_SCORE_THRESHOLD ||
                                    (event.endStream && headerInspection.hasBodyRewriteDirectives && streamState.dataBytes <= HTTP2_DATA_BODY_LIMIT_BYTES))
                            if (!shouldInspectData) {
                                streamState.lastDataSample = trimHttp2DataSample(streamState.lastDataSample, event.dataFragment)
                                return@forEachIndexed
                            }
                            val dataInspection = HttpMitmFilter.inspectHttp2DataSample(
                                session = session,
                                headerInspection = headerInspection,
                                currentSample = streamState.lastDataSample,
                                incomingFragment = event.dataFragment,
                                completeResponse = event.endStream && streamState.dataBytes <= HTTP2_DATA_BODY_LIMIT_BYTES
                            )
                            streamState.lastDataSample = dataInspection?.combinedSample
                                ?: trimHttp2DataSample(streamState.lastDataSample, event.dataFragment)
                            if (dataInspection != null) {
                                streamState.lastDataInspection = dataInspection
                                logHttp2DataInspection(context, session, direction, event.streamId, dataInspection)
                                if (!streamState.blockedByAction) {
                                    val syntheticResponse = HttpMitmFilter.buildHttp2BodyRewriteSyntheticResponse(
                                        streamId = event.streamId,
                                        rewrite = dataInspection
                                    ) ?: HttpMitmFilter.buildRedirectHttp2SyntheticResponse(
                                        streamId = event.streamId,
                                        contentType = dataInspection.contentType,
                                        redirectResource = dataInspection.redirectResource,
                                        cspValue = dataInspection.cspValue
                                    ) ?: Http2FrameCodec.buildNeutralizedResponseFrames(
                                        streamId = event.streamId,
                                        contentType = streamState.lastHeaderInspection?.contentType
                                    )
                                    streamState.blockedByAction = true
                                    streamState.lastActionDecision = Http2ActionDecision(
                                        action = dataInspection.rewriteReason ?: if (dataInspection.redirectResource.isNullOrBlank()) "response-data-candidate" else "response-data-redirect",
                                        confidence = dataInspection.confidence,
                                        shouldBlockCandidate = true,
                                        shouldSyntheticRespond = true
                                    )
                                    directives += Http2StreamDirective(
                                        streamId = event.streamId,
                                        action = dataInspection.rewriteReason ?: if (dataInspection.redirectResource.isNullOrBlank()) "response-data-candidate" else "response-data-redirect",
                                        confidence = dataInspection.confidence,
                                        sendRst = false,
                                        syntheticResponse = syntheticResponse
                                    )
                                    logHttp2TriggeredBlock(
                                        context = context,
                                        session = session,
                                        streamId = event.streamId,
                                        trigger = "body",
                                        action = "body-candidate",
                                        confidence = dataInspection.confidence,
                                        vendor = dataInspection.vendor,
                                        redirectResource = dataInspection.redirectResource,
                                        reasons = dataInspection.suspiciousReasons
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
                        if (event.closesHeaderBlock && !streamState.headerBlockOpen) {
                            streamState.headerBlockAbandoned = true
                            streamState.lastDecodedHeaderError = "end-headers-without-open-block"
                            logHttp2EndHeadersWithoutOpenBlock(context, session, direction, event.streamId)
                        }
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
                            logHttp2HeadersComplete(context, session, direction, event.streamId, streamState)
                            if (streamState.currentHeaderBlockBytes == 0L) {
                                logHttp2EmptyHeaderBlock(context, session, direction, event.streamId, streamState.headerBlockCount)
                            }
                            if (streamState.lastDecodedHeaders.isNotEmpty() || streamState.lastDecodedHeaderError != null) {
                                logHttp2HeadersDecoded(context, session, direction, event.streamId, streamState, decoded)
                            }
                            if (streamState.lastDecodedHeaderError != null) {
                                logHttp2HeaderDecodeFailure(context, session, direction, event.streamId, streamState)
                            }
                            streamState.lastHeaderInspection?.let { inspection ->
                                logHttp2HeaderInspection(context, session, direction, event.streamId, inspection)
                            }
                            streamState.lastActionDecision?.let { decision ->
                                val inspection = streamState.lastHeaderInspection
                                logHttp2ActionDecision(context, session, direction, event.streamId, decision)
                                if (decision.shouldBlockCandidate) {
                                    val syntheticResponse = if (decision.shouldSyntheticRespond) {
                                        HttpMitmFilter.buildRedirectHttp2SyntheticResponse(
                                            streamId = event.streamId,
                                            contentType = decision.contentType ?: inspection?.contentType.orEmpty(),
                                            redirectResource = decision.redirectResource,
                                            cspValue = decision.cspValue
                                        ) ?: Http2FrameCodec.buildNeutralizedResponseFrames(
                                            streamId = event.streamId,
                                            contentType = inspection?.contentType
                                        )
                                    } else {
                                        null
                                    }
                                    streamState.blockedByAction = true
                                    logHttp2TriggeredBlock(
                                        context = context,
                                        session = session,
                                        streamId = event.streamId,
                                        trigger = "header",
                                        action = decision.action,
                                        confidence = decision.confidence,
                                        vendor = inspection?.vendor ?: "none",
                                        redirectResource = decision.redirectResource,
                                        reasons = inspection?.suspiciousReasons.orEmpty()
                                    )
                                    directives += Http2StreamDirective(
                                        streamId = event.streamId,
                                        action = decision.action,
                                        confidence = decision.confidence,
                                        sendRst = !decision.shouldSyntheticRespond,
                                        syntheticResponse = syntheticResponse
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
                        logHttp2StreamClosed(context, session, direction, event.streamId, event.stage, streamState)
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
    ) = synchronized(http2StateLock) {
        if (directives.isEmpty()) return@synchronized
        val control = http2FlowControls.computeIfAbsent(session.flowKey) { Http2FlowControl() }
        directives.forEach { directive ->
            control.blockedStreams += directive.streamId
            if (directive.syntheticResponse != null && control.syntheticRespondedStreams.add(directive.streamId)) {
                writeAndFlush(output, directive.syntheticResponse)
                control.terminalBlockedStreams += directive.streamId
                recordHttp2TerminalBlockedStat(context, session, control, directive.streamId, 100 * 1024)
                logHttp2SyntheticResponse(context, session, directive, resetPeer)
            }
            if (directive.sendRst && control.resetSentStreams.add(directive.streamId)) {
                writeHttp2RstStream(output, directive.streamId)
                recordHttp2TerminalBlockedStat(context, session, control, directive.streamId, 50 * 1024)
                logHttp2StreamReset(context, session, directive, resetPeer)
            }
        }
    }

    private fun logHttp2Preface(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String
    ) {
        appendHttp2Log(context, session, "HTTP/2 preface direction=$direction")
    }

    private fun logHttp2Frame(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        index: Int,
        event: Http2FrameLogger.FrameEvent.FrameHeader
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 frame direction=$direction index=$index type=${event.typeName} length=${event.length} flags=0x${event.flags.toString(16)} flagNames=${event.flagNames.joinToString("|").ifBlank { "none" }} stream=${event.streamId} endStream=${event.endStream} endHeaders=${event.endHeaders} ack=${event.ack}"
        )
    }

    private fun logHttp2GoAwayObserved(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        index: Int
    ) {
        appendHttp2Log(context, session, "HTTP/2 GOAWAY observed direction=$direction index=$index")
    }

    private fun logHttp2ClosedStreamFrame(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        index: Int,
        streamId: Int,
        typeName: String
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 frame on closed stream direction=$direction index=$index stream=$streamId type=$typeName"
        )
    }

    private fun logHttp2ContinuationWithoutHeaderBlock(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 continuation without open header block direction=$direction stream=$streamId"
        )
    }

    private fun logHttp2HeaderBlockReplaced(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int,
        previousBytes: Long
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 header block replaced before close direction=$direction stream=$streamId previousBytes=$previousBytes"
        )
    }

    private fun filterHttp2Payload(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        parsedFrames: List<Http2FrameLogger.ParsedFrame>,
        originalPayload: ByteArray
    ): ByteArray = synchronized(http2StateLock) {
        val flowKey = session.flowKey
        val control = http2FlowControls[flowKey] ?: return@synchronized originalPayload
        if (control.goAwaySeen) {
            cleanupTerminalHttp2Streams(context, session, direction, reason = "goaway-filter-pass")
        }
        if (control.goAwaySeen && parsedFrames.none { it.streamId > 0 && control.blockedStreams.contains(it.streamId) }) {
            return@synchronized originalPayload
        }
        if (control.blockedStreams.isEmpty() || parsedFrames.isEmpty()) return@synchronized originalPayload
        val parsedBytes = parsedFrames.sumOf { it.rawBytes.size }
        val droppedFrames = parsedFrames.filter { frame ->
            frame.streamId > 0 && control.blockedStreams.contains(frame.streamId)
        }
        val keptFrames = parsedFrames.filter { frame ->
            frame.streamId <= 0 || !control.blockedStreams.contains(frame.streamId)
        }
        if (keptFrames.size == parsedFrames.size) return@synchronized originalPayload
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
                streamState.terminalBlocked = true
                streamState.streamClosed = true
                streamState.lastDataSample = ByteArray(0)
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
            recordHttp2TerminalBlockedStats(context, session, control, terminalStreams)
        }
        cleanupTerminalHttp2Streams(context, session, direction, reason = "blocked-frame-filtered")
        val filteredPrefix = concatByteArrays(keptFrames.map { it.rawBytes })
        val tailBytes = if (parsedBytes >= originalPayload.size) ByteArray(0) else originalPayload.copyOfRange(parsedBytes, originalPayload.size)
        appendHttp2BlockedFrameDropLog(
            context = context,
            session = session,
            direction = direction,
            droppedFrames = droppedFrames.size,
            droppedBytes = droppedBytes,
            droppedStreams = droppedStreams,
            droppedTypes = droppedTypes,
            terminalStreams = terminalStreams,
            tailBytes = tailBytes.size
        )
        if (tailBytes.isEmpty()) return@synchronized filteredPrefix
        filteredPrefix + tailBytes
    }

    private fun appendHttp2Log(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        message: String
    ) {
        LogRepository.append(context, "$message host=${session.host} flow=${session.flowKey}")
    }

    private fun recordHttp2TerminalBlockedStats(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        control: Http2FlowControl,
        terminalStreams: List<Int>
    ) {
        val vendor = classifyHttp2SessionVendor(context, session)
        terminalStreams.forEach { streamId ->
            if (!control.terminalStatsRecorded.contains(streamId)) {
                StatsRepository.recordBlockedMitm(context, vendor, session.appName, 50 * 1024)
                control.terminalStatsRecorded += streamId
            }
        }
    }

    private fun recordHttp2TerminalBlockedStat(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        control: Http2FlowControl,
        streamId: Int,
        bytes: Int
    ) {
        if (control.terminalStatsRecorded.contains(streamId)) return
        val vendor = classifyHttp2SessionVendor(context, session)
        StatsRepository.recordBlockedMitm(context, vendor, session.appName, bytes.toLong())
        control.terminalStatsRecorded += streamId
    }

    private fun classifyHttp2SessionVendor(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession
    ): String {
        return RuleRepository.classifyVendorFromHints(context, session.host, session.appName)
    }

    private fun logHttp2DataInspection(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int,
        inspection: Http2DataInspection
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 data inspection direction=$direction stream=$streamId vendor=${inspection.vendor} suspiciousScore=${inspection.suspiciousScore} reasons=${inspection.suspiciousReasons.joinToString("|").ifBlank { "none" }} preview=${inspection.samplePreview.ifBlank { "none" }}"
        )
    }

    private fun logHttp2EndHeadersWithoutOpenBlock(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int
    ) {
        appendHttp2Log(context, session, "HTTP/2 END_HEADERS without open header block direction=$direction stream=$streamId")
    }

    private fun logHttp2HeadersComplete(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int,
        streamState: Http2StreamLogState
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 headers complete direction=$direction stream=$streamId blockIndex=${streamState.headerBlockCount} blockBytes=${streamState.currentHeaderBlockBytes} largeHeaderBlock=${streamState.currentHeaderBlockBytes >= HTTP2_HEADER_BLOCK_LARGE_THRESHOLD} totalHeaderBytes=${streamState.headerBytes}"
        )
    }

    private fun logHttp2EmptyHeaderBlock(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int,
        blockIndex: Int
    ) {
        appendHttp2Log(context, session, "HTTP/2 empty header block direction=$direction stream=$streamId blockIndex=$blockIndex")
    }

    private fun logHttp2HeadersDecoded(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int,
        streamState: Http2StreamLogState,
        decoded: HpackDecoder.DecodeResult
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 headers decoded direction=$direction stream=$streamId decoded=${streamState.lastDecodedHeaders.size} huffmanStrings=${decoded.huffmanEncodedStrings} truncated=${decoded.truncated} error=${decoded.error ?: "none"} preview=${streamState.lastDecodedHeaderPreview.ifBlank { "none" }}"
        )
    }

    private fun logHttp2HeaderDecodeFailure(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int,
        streamState: Http2StreamLogState
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 header decode failure direction=$direction stream=$streamId error=${streamState.lastDecodedHeaderError} blockBytes=${streamState.currentHeaderBlockBytes} truncated=${streamState.lastDecodedHeaderTruncated}"
        )
    }

    private fun logHttp2HeaderInspection(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int,
        inspection: Http2HeaderInspection
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 header inspection direction=$direction stream=$streamId requestLike=${inspection.requestLike} responseLike=${inspection.responseLike} method=${inspection.method ?: "none"} authority=${inspection.authority} path=${inspection.path ?: "none"} status=${inspection.status ?: "none"} contentType=${inspection.contentType ?: "none"} location=${inspection.location ?: "none"} setCookie=${inspection.setCookie ?: "none"} vendor=${inspection.vendor} suspiciousScore=${inspection.suspiciousScore} reasons=${inspection.suspiciousReasons.joinToString("|").ifBlank { "none" }}"
        )
    }

    private fun logHttp2ActionDecision(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int,
        decision: Http2ActionDecision
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 action decision direction=$direction stream=$streamId action=${decision.action} confidence=${decision.confidence} blockCandidate=${decision.shouldBlockCandidate}"
        )
    }

    private fun logHttp2TriggeredBlock(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        streamId: Int,
        trigger: String,
        action: String,
        confidence: String,
        vendor: String,
        redirectResource: String?,
        reasons: List<String>
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 ${trigger}-triggered block stream=$streamId trigger=$trigger action=$action confidence=$confidence vendor=$vendor redirect=${redirectResource ?: "none"} reasons=${reasons.joinToString("|").ifBlank { "none" }}"
        )
    }

    private fun logHttp2StreamClosed(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        streamId: Int,
        stage: String,
        streamState: Http2StreamLogState
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 stream direction=$direction stream=$streamId stage=$stage headerFrames=${streamState.headerFrames} headerBytes=${streamState.headerBytes} dataFrames=${streamState.dataFrames} dataBytes=${streamState.dataBytes} headerBlocks=${streamState.headerBlockCount} maxHeaderBlockBytes=${streamState.maxHeaderBlockBytes} largeHeaderBlockSeen=${streamState.largeHeaderBlockSeen} decodedHeaders=${streamState.lastDecodedHeaders.size} headerDecodeError=${streamState.lastDecodedHeaderError ?: "none"} suspiciousScore=${streamState.lastHeaderInspection?.suspiciousScore ?: 0} action=${streamState.lastActionDecision?.action ?: "none"} headersClosed=${streamState.headersClosed} reset=${streamState.reset} closed=true"
        )
    }

    private fun logHttp2SyntheticResponse(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        directive: Http2StreamDirective,
        resetPeer: String
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 synthetic response stream=${directive.streamId} action=${directive.action} confidence=${directive.confidence} peer=$resetPeer bytes=${directive.syntheticResponse?.size ?: 0} sendRst=${directive.sendRst}"
        )
    }

    private fun logHttp2StreamReset(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        directive: Http2StreamDirective,
        resetPeer: String
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 stream reset stream=${directive.streamId} action=${directive.action} confidence=${directive.confidence} peer=$resetPeer"
        )
    }

    private fun appendHttp2BlockedFrameDropLog(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        droppedFrames: Int,
        droppedBytes: Int,
        droppedStreams: List<Int>,
        droppedTypes: String,
        terminalStreams: List<Int>,
        tailBytes: Int
    ) {
        appendHttp2Log(
            context,
            session,
            "HTTP/2 blocked frame drop direction=$direction droppedFrames=$droppedFrames droppedBytes=$droppedBytes droppedStreams=${droppedStreams.joinToString(",").ifBlank { "none" }} droppedTypes=$droppedTypes terminalStreams=${terminalStreams.joinToString(",").ifBlank { "none" }} tailBytes=$tailBytes"
        )
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
        appendHttp2Log(
            context,
            session,
            "HTTP/2 blocked header block abandoned direction=$direction stream=${streamState.streamId} reason=$reason headerBlocks=${streamState.headerBlockCount} totalHeaderBytes=${streamState.headerBytes}"
        )
    }

    private fun cleanupTerminalHttp2Streams(
        context: Context,
        session: TlsMitmSessionManager.TlsMitmSession,
        direction: String,
        reason: String
    ) = synchronized(http2StateLock) {
        val flowKey = session.flowKey
        val control = http2FlowControls[flowKey] ?: return@synchronized
        val directionState = http2LogStates[http2LogKey(flowKey, direction)]
        val completedStreams = directionState?.streams
            ?.filterValues { it.streamClosed && (it.terminalBlocked || it.reset) }
            ?.keys
            .orEmpty()
        if (completedStreams.isEmpty()) return@synchronized
        control.blockedStreams.removeAll(completedStreams)
        control.resetSentStreams.removeAll(completedStreams)
        control.syntheticRespondedStreams.removeAll(completedStreams)
        control.terminalStatsRecorded.removeAll(completedStreams)
        control.terminalBlockedStreams.removeAll(completedStreams)
        directionState?.let { state ->
            completedStreams.forEach { streamId ->
                state.streams.remove(streamId)
            }
        }
        appendHttp2Log(
            context,
            session,
            "HTTP/2 terminal stream cleanup direction=$direction reason=$reason streams=${completedStreams.joinToString(",").ifBlank { "none" }}"
        )
    }

    private fun writeHttp2RstStream(output: OutputStream, streamId: Int) {
        val frame = Http2FrameCodec.buildRstStreamFrame(streamId)
        writeAndFlush(output, frame)
    }

    private fun flushHttp2Summary(
        context: Context,
        flowKey: String,
        direction: String,
        finalFlush: Boolean,
        session: TlsMitmSessionManager.TlsMitmSession? = null
    ) = synchronized(http2StateLock) {
        val key = http2LogKey(flowKey, direction)
        val state = if (finalFlush) http2LogStates.remove(key) else http2LogStates[key]
        if (state == null || state.frameCount == 0 || state.frameCount == state.lastSummaryFrameCount) return@synchronized
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

    private fun concatByteArrays(parts: Collection<ByteArray>): ByteArray {
        if (parts.isEmpty()) return ByteArray(0)
        val output = java.io.ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach(output::write)
        return output.toByteArray()
    }

    private fun trimHttp2DataSample(existing: ByteArray, incoming: ByteArray): ByteArray {
        val maxBytes = HTTP2_DATA_BODY_LIMIT_BYTES
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
    ) : AutoCloseable {
        override fun close() {
            runCatching { serverSocket.close() }
        }
    }

    private class RunningBridgeHandle(private val socket: SSLSocket) : AutoCloseable {
        override fun close() {
            runCatching { socket.close() }
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
        val terminalStatsRecorded: MutableSet<Int> = linkedSetOf(),
        var goAwaySeen: Boolean = false
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
        var lastHeaderInspection: Http2HeaderInspection? = null,
        var lastDataInspection: Http2DataInspection? = null,
        var lastDataSample: ByteArray = ByteArray(0),
        var lastActionDecision: Http2ActionDecision? = null,
        var blockedByAction: Boolean = false,
        var terminalBlocked: Boolean = false,
        var headersClosed: Boolean = false,
        var reset: Boolean = false,
        var streamClosed: Boolean = false
    )

    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val MAX_PENDING_HTTP2_CLIENT_REWRITE_BYTES = 64 * 1024
}
