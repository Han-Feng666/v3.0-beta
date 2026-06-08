package com.HanFeng.service

import android.content.Context
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.security.TlsMitmContextFactory
import java.net.Socket

object TlsMitmSessionManager {
    private const val MAX_SESSIONS = 256
    private val sessions = LinkedHashMap<String, TlsMitmSession>(256, 0.75f, true)
    @Volatile private var appContext: Context? = null

    fun registerObservedSession(
        context: Context,
        flowKey: String,
        host: String,
        appName: String,
        source: String,
        targetIp: String,
        targetPort: Int,
        certificatePath: String,
        offeredAlpnProtocols: List<String> = emptyList(),
        clientHelloTlsVersion: String? = null,
        clientHelloSupportedTlsVersions: List<String> = emptyList(),
        encryptedClientHelloOffered: Boolean = false
    ) {
        appContext = context.applicationContext
        synchronized(sessions) {
            sessions[flowKey] = TlsMitmSession(
                flowKey = flowKey,
                host = host,
                appName = appName,
                source = source,
                targetIp = targetIp,
                targetPort = targetPort,
                certificatePath = certificatePath,
                observedAt = System.currentTimeMillis(),
                state = "ready_for_tls_bridge",
                offeredAlpnProtocols = offeredAlpnProtocols,
                prefersHttp2 = offeredAlpnProtocols.any { it.equals("h2", ignoreCase = true) },
                clientHelloTlsVersion = clientHelloTlsVersion,
                clientHelloSupportedTlsVersions = clientHelloSupportedTlsVersions,
                encryptedClientHelloOffered = encryptedClientHelloOffered
            )
            while (sessions.size > MAX_SESSIONS) {
                val firstKey = sessions.entries.firstOrNull()?.key ?: break
                sessions.remove(firstKey)
            }
        }
        LogRepository.append(
            context,
            "Registered TLS MITM session host=$host app=$appName source=$source target=$targetIp:$targetPort cert=$certificatePath alpn=${offeredAlpnProtocols.joinToString(",").ifBlank { "none" }} prefersHttp2=${offeredAlpnProtocols.any { it.equals("h2", ignoreCase = true) }} tls=${clientHelloTlsVersion ?: "unknown"} supportedTls=${clientHelloSupportedTlsVersions.joinToString(",").ifBlank { "none" }} ech=$encryptedClientHelloOffered"
        )
    }

    fun prepareTlsBridge(context: Context, flowKey: String, protectSocket: (Socket) -> Boolean): TlsMitmSession? {
        appContext = context.applicationContext
        val preparedState: Pair<TlsMitmSession, TlsMitmContextFactory.PreparedTlsContext?> = synchronized(sessions) {
            val current = sessions[flowKey] ?: return null
            val bypassReason = HttpsMitmRepository.getActiveBypassReason(context, current.host)
            if (bypassReason != null) {
                val bypassed = current.copy(
                    state = "bridge_bypassed",
                    bypassMitm = true,
                    bypassReason = bypassReason,
                    bypassMarkedAt = System.currentTimeMillis()
                )
                sessions[flowKey] = bypassed
                return@synchronized bypassed to null
            }
            val result = TlsMitmContextFactory.createServerContext(current.certificatePath).getOrNull()
                ?: return null
            current to result
        }
        val preparedContext = preparedState.second
        if (preparedContext == null) {
            LogRepository.append(
                context,
                "Skipped TLS MITM bridge due to active cooldown host=${preparedState.first.host} flow=$flowKey source=${preparedState.first.source} reason=${preparedState.first.bypassReason ?: "unknown"}"
            )
            return preparedState.first
        }
        val bridgeBinding = HttpsTlsBridgeManager.ensureBridge(context, preparedState.first, preparedContext, protectSocket)
        val prepared = synchronized(sessions) {
            val updated = preparedState.first.copy(
                state = "bridge_listening",
                tlsProtocol = preparedContext.protocol,
                localBridgeHost = bridgeBinding.host,
                localBridgePort = bridgeBinding.port,
                bridgePreparedAt = System.currentTimeMillis()
            )
            sessions[flowKey] = updated
            updated
        }
        LogRepository.append(
            context,
            "Prepared TLS MITM bridge host=${prepared.host} source=${prepared.source} target=${prepared.targetIp}:${prepared.targetPort} protocol=${prepared.tlsProtocol} local=${prepared.localBridgeHost}:${prepared.localBridgePort} clientTls=${prepared.clientHelloTlsVersion ?: "unknown"} supportedTls=${prepared.clientHelloSupportedTlsVersions.joinToString(",").ifBlank { "none" }} ech=${prepared.encryptedClientHelloOffered}"
        )
        return prepared
    }

    fun updateNegotiatedProtocol(
        context: Context,
        flowKey: String,
        negotiatedAlpnProtocol: String?,
        upstreamTlsVersion: String?
    ) {
        appContext = context.applicationContext
        val updated = synchronized(sessions) {
            val current = sessions[flowKey] ?: return
            val next = current.copy(
                state = "bridge_connected",
                negotiatedAlpnProtocol = negotiatedAlpnProtocol,
                upstreamTlsVersion = upstreamTlsVersion,
                negotiatedHttp2 = negotiatedAlpnProtocol.equals("h2", ignoreCase = true)
            )
            sessions[flowKey] = next
            next
        }
        LogRepository.append(
            context,
            "Connected HTTPS TLS bridge host=${updated.host} source=${updated.source} alpn=${updated.negotiatedAlpnProtocol ?: "unknown"} negotiatedHttp2=${updated.negotiatedHttp2} upstreamTls=${updated.upstreamTlsVersion ?: "unknown"}"
        )
    }

    fun markMitmBypass(
        context: Context,
        flowKey: String,
        reason: String
    ) {
        appContext = context.applicationContext
        val updated = synchronized(sessions) {
            val current = sessions[flowKey] ?: return
            val next = current.copy(
                state = "bridge_bypassed",
                bypassMitm = true,
                bypassReason = reason,
                bypassMarkedAt = System.currentTimeMillis()
            )
            sessions[flowKey] = next
            next
        }
        HttpsMitmRepository.markBypassCooldown(
            context,
            updated.host,
            reason,
            cooldownMillis = bypassCooldownMillisForReason(reason)
        )
        LogRepository.append(
            context,
            "Bypassed HTTPS MITM host=${updated.host} flow=$flowKey source=${updated.source} reason=$reason"
        )
    }

    private fun bypassCooldownMillisForReason(reason: String): Long {
        val normalized = reason.lowercase()
        return when {
            normalized.startsWith("ssl-pinning:") -> 45 * 60 * 1000L
            normalized.startsWith("ssl-trust-anchor:") || normalized.startsWith("ssl-hostname:") -> 30 * 60 * 1000L
            normalized.startsWith("ssl-certificate-time:") -> 20 * 60 * 1000L
            normalized.startsWith("ssl-protocol:") -> 10 * 60 * 1000L
            normalized.startsWith("ssl-handshake:") && listOf("certificate", "cert", "pin", "trust anchor", "hostname").any { normalized.contains(it) } -> 30 * 60 * 1000L
            normalized.startsWith("ssl-handshake:") -> 3 * 60 * 1000L
            normalized.startsWith("io-bridge:") && listOf("connection reset", "broken pipe", "eof").any { normalized.contains(it) } -> 60 * 1000L
            normalized.startsWith("io-bridge:") -> 30 * 1000L
            else -> 10 * 60 * 1000L
        }
    }

    fun updateHttp2Observation(
        context: Context,
        flowKey: String,
        direction: Http2FrameLogger.Direction,
        state: Http2FrameLogger.StreamState
    ) {
        appContext = context.applicationContext
        synchronized(sessions) {
            val current = sessions[flowKey] ?: return
            val next = when (direction) {
                Http2FrameLogger.Direction.CLIENT_TO_SERVER -> current.copy(
                    clientHttp2Frames = state.totalFrames,
                    clientHttp2HeadersFrames = state.headersFrames,
                    clientHttp2DataFrames = state.dataFrames,
                    clientHttp2SettingsFrames = state.settingsFrames,
                    clientHttp2PingFrames = state.pingFrames,
                    clientHttp2GoAwayFrames = state.goAwayFrames,
                    clientHttp2EndStreamFrames = state.endStreamFrames,
                    clientHttp2AckFrames = state.ackFrames,
                    clientHttp2LastStreamId = state.lastStreamId,
                    clientHttp2ActiveStreams = state.activeStreams.size,
                    clientHttp2ClosedStreams = state.closedStreams.size,
                    lastHttp2ObservedAt = System.currentTimeMillis()
                )
                Http2FrameLogger.Direction.SERVER_TO_CLIENT -> current.copy(
                    serverHttp2Frames = state.totalFrames,
                    serverHttp2HeadersFrames = state.headersFrames,
                    serverHttp2DataFrames = state.dataFrames,
                    serverHttp2SettingsFrames = state.settingsFrames,
                    serverHttp2PingFrames = state.pingFrames,
                    serverHttp2GoAwayFrames = state.goAwayFrames,
                    serverHttp2EndStreamFrames = state.endStreamFrames,
                    serverHttp2AckFrames = state.ackFrames,
                    serverHttp2LastStreamId = state.lastStreamId,
                    serverHttp2ActiveStreams = state.activeStreams.size,
                    serverHttp2ClosedStreams = state.closedStreams.size,
                    lastHttp2ObservedAt = System.currentTimeMillis()
                )
            }
            sessions[flowKey] = next
        }
    }

    fun clear(context: Context) {
        synchronized(sessions) {
            sessions.clear()
        }
        HttpsTlsBridgeManager.closeAll(context)
    }

    fun getContextOrNull(): Context? = appContext

    fun getSession(flowKey: String): TlsMitmSession? {
        synchronized(sessions) {
            return sessions[flowKey]
        }
    }

    fun findPreparedSession(targetIp: String, targetPort: Int, appName: String?): TlsMitmSession? {
        synchronized(sessions) {
            val matches = sessions.values
                .asSequence()
                .filter { it.targetIp == targetIp && it.targetPort == targetPort }
                .filter { !it.bypassMitm }
                .filter { it.localBridgePort != null }
                .toList()
            if (matches.isEmpty()) return null
            if (appName.isNullOrBlank()) {
                return matches.maxByOrNull { it.observedAt }
            }
            return matches
                .filter { it.appName == appName }
                .maxByOrNull { it.observedAt }
                ?: matches.maxByOrNull { it.observedAt }
        }
    }

    fun snapshot(): List<TlsMitmSession> {
        synchronized(sessions) {
            return sessions.values.toList()
        }
    }

    data class TlsMitmSession(
        val flowKey: String,
        val host: String,
        val appName: String,
        val source: String,
        val targetIp: String,
        val targetPort: Int,
        val certificatePath: String,
        val observedAt: Long,
        val state: String,
        val tlsProtocol: String? = null,
        val offeredAlpnProtocols: List<String> = emptyList(),
        val prefersHttp2: Boolean = false,
        val clientHelloTlsVersion: String? = null,
        val clientHelloSupportedTlsVersions: List<String> = emptyList(),
        val encryptedClientHelloOffered: Boolean = false,
        val negotiatedAlpnProtocol: String? = null,
        val negotiatedHttp2: Boolean = false,
        val upstreamTlsVersion: String? = null,
        val localBridgeHost: String? = null,
        val localBridgePort: Int? = null,
        val bridgePreparedAt: Long? = null,
        val bypassMitm: Boolean = false,
        val bypassReason: String? = null,
        val bypassMarkedAt: Long? = null,
        val clientHttp2Frames: Int = 0,
        val clientHttp2HeadersFrames: Int = 0,
        val clientHttp2DataFrames: Int = 0,
        val clientHttp2SettingsFrames: Int = 0,
        val clientHttp2PingFrames: Int = 0,
        val clientHttp2GoAwayFrames: Int = 0,
        val clientHttp2EndStreamFrames: Int = 0,
        val clientHttp2AckFrames: Int = 0,
        val clientHttp2LastStreamId: Int? = null,
        val clientHttp2ActiveStreams: Int = 0,
        val clientHttp2ClosedStreams: Int = 0,
        val serverHttp2Frames: Int = 0,
        val serverHttp2HeadersFrames: Int = 0,
        val serverHttp2DataFrames: Int = 0,
        val serverHttp2SettingsFrames: Int = 0,
        val serverHttp2PingFrames: Int = 0,
        val serverHttp2GoAwayFrames: Int = 0,
        val serverHttp2EndStreamFrames: Int = 0,
        val serverHttp2AckFrames: Int = 0,
        val serverHttp2LastStreamId: Int? = null,
        val serverHttp2ActiveStreams: Int = 0,
        val serverHttp2ClosedStreams: Int = 0,
        val lastHttp2ObservedAt: Long? = null
    )

    data class BridgeBinding(
        val host: String,
        val port: Int
    )
}
