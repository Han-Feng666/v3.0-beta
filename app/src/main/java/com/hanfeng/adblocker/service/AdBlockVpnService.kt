package com.HanFeng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.HanFeng.R
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpsDecryptRouteRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.HttpDecryptRouteRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.StatsRepository
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.dns.DnsMessageParser
import com.HanFeng.security.CertificateAuthorityManager
import com.HanFeng.security.TlsClientHelloParser
import com.HanFeng.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

class AdBlockVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetJob: Job? = null
    private val appNameCache = ConcurrentHashMap<String, String>(256)
    private val domainAppCache = ConcurrentHashMap<String, String>(256)
    private val sourcePortAppCache = ConcurrentHashMap<String, String>(256)
    private val appLabelCache = ConcurrentHashMap<Int, String>(128)
    private val dnsResponseCache = LinkedHashMap<String, CachedDnsResponse>(256, 0.75f, true)
    private val decisionLogCache = LinkedHashMap<String, Long>(256, 0.75f, true)
    private val httpDecryptIpCache = LinkedHashMap<String, HttpDecryptTarget>(512, 0.75f, true)
    private val httpsDecryptIpCache = LinkedHashMap<String, HttpsDecryptTarget>(512, 0.75f, true)
    private val quicRouteCache = LinkedHashMap<String, QuicRouteTarget>(1024, 0.75f, true)
    private val httpsProxyFlowCache = LinkedHashMap<String, HttpsProxyFlow>(256, 0.75f, true)
    private val httpsBridgeSocketCache = LinkedHashMap<String, HttpsBridgeSocketSession>(128, 0.75f, true)
    private val upstreamServerStates = linkedMapOf<String, UpstreamServerState>()
    private val dnsServerCacheLock = Any()
    private val tunWriteLock = Any()
    // DNS socket 连接池 - 复用 socket 避免每次创建开销
    private val dnsSocketPool = ConcurrentLinkedQueue<Pair<InetAddress, DatagramSocket>>()
    private val dnsSocketPoolLock = Any()
    private val localDnsV4 = "10.99.0.2"
    private val localDnsV6 = "fd66:66::2"
    private val staleCacheGraceMillis = 60_000L
    private val dnsServerCacheTtlMillis = 15_000L
    private val routeCachePruneIntervalMillis = 15_000L
    private var lastHttpRouteReloadAt = 0L
    @Volatile private var lastHttpDecryptPruneAt = 0L
    @Volatile private var lastHttpsDecryptPruneAt = 0L
    @Volatile private var lastQuicRoutePruneAt = 0L
    @Volatile private var tunOutputStream: FileOutputStream? = null
    private val blockedIpNetworks by lazy(LazyThreadSafetyMode.NONE) { loadBlockedIpNetworks() }
    private val upstreamFallbackDnsHosts = listOf(
        "223.5.5.5",
        "223.6.6.6",
        "114.114.114.114",
        "114.114.115.115",
        "101.226.4.6",
        "101.226.4.7",
        "117.50.10.10",
        "117.50.11.11",
        "180.76.76.76",
        "182.254.116.116",
        "119.29.29.29",
        "52.80.66.66",
        "45.90.28.0",
        "45.90.30.0",
        "9.9.9.9",
        "208.67.222.222",
        "208.67.220.220",
        "185.228.168.168",
        "185.228.169.168",
        "94.140.14.140",
        "94.140.15.140",
        "1.1.1.1",
        "1.0.0.1",
        "8.8.8.8",
        "8.8.4.4",
        "2001:4860:4860::8888",
        "2001:4860:4860::8844",
        "2606:4700:4700::1111",
        "2606:4700:4700::1001",
        "2620:fe::fe",
        "2620:fe::9",
        "2620:119:35::35",
        "2620:119:53::53",
        "2a10:50c0::ad1:ff",
        "2a10:50c0::ad2:ff"
    )
    private val upstreamFallbackDnsServers by lazy(LazyThreadSafetyMode.NONE) {
        upstreamFallbackDnsHosts.mapNotNull { host -> runCatching { InetAddress.getByName(host) }.getOrNull() }
    }
    @Volatile private var cachedDnsServers: CachedDnsServers? = null
    @Volatile private var httpDecryptEnabled = false
    @Volatile private var mitmCertificateInstalled = false
    private val handledDnsHosts by lazy(LazyThreadSafetyMode.NONE) {
        setOf(localDnsV4, localDnsV6).mapNotNull { host ->
            runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        }.toSet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val shouldStaySticky = when (intent?.action) {
            ACTION_STOP -> {
                FeatureSettingsRepository.setAdBlockEnabled(this, false)
                stopVpn()
                false
            }
            ACTION_RELOAD -> {
                reloadVpn()
                true
            }
            else -> {
                if (!FeatureSettingsRepository.isAdBlockEnabled(this)) {
                    LogRepository.append(this, "VPN start skipped: ad block disabled by user")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (vpnInterface == null || packetJob?.isActive != true) {
                    startVpn()
                } else {
                    LogRepository.append(this, "VPN start skipped: already running")
                }
                isRunning
            }
        }
        return if (shouldStaySticky && isRunning) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        StatsRepository.flushNow(this)
        LogRepository.flushAndClose()
        if (FeatureSettingsRepository.isAdBlockEnabled(this) && isRunning) {
            LogRepository.append(this, "VPN service destroyed while interception should stay enabled")
        }
        stopVpn(stopService = false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (FeatureSettingsRepository.isAdBlockEnabled(this) && isRunning) {
            LogRepository.append(this, "Task removed while VPN is running in background")
        } else if (!isRunning) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startVpn() {
        refreshRuntimeFeatureFlags()
        if (vpnInterface != null && packetJob?.isActive == true) {
            isRunning = true
            LogRepository.append(this, "VPN start called while already running")
            return
        }
        val foregroundStarted = runCatching {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        }.onFailure { error ->
            isRunning = false
            LogRepository.append(this, "VPN foreground start failed: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
        }.isSuccess
        if (!foregroundStarted) {
            return
        }
        vpnInterface = runCatching { buildInterface() }
            .onFailure { error ->
                LogRepository.append(this, "VPN establish failed: ${error.message ?: error.javaClass.simpleName}")
            }
            .getOrNull()
        if (vpnInterface == null) {
            isRunning = false
            clearRuntimeState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        isRunning = vpnInterface != null
        packetJob = scope.launch {
            runCatching { runPacketLoop() }
                .onFailure { error ->
                    LogRepository.append(this@AdBlockVpnService, "VPN loop crashed: ${error.message ?: error.javaClass.simpleName}")
                    FeatureSettingsRepository.setAdBlockEnabled(this@AdBlockVpnService, false)
                    stopVpn()
                }
        }
        HttpsMitmController.onVpnStarted(this)
        LogRepository.append(this, "VPN started")
        scope.launch {
            while (isRunning && scope.isActive) {
                kotlinx.coroutines.delay(30_000L)
                if (isRunning) {
                    LogRepository.append(this@AdBlockVpnService, "VPN heartbeat: still running")
                }
            }
        }
    }

    private fun reloadVpn() {
        refreshRuntimeFeatureFlags()
        LogRepository.append(this, "VPN reload requested")
        stopVpn(stopService = false)
        startVpn()
    }

    private fun stopVpn(stopService: Boolean = true) {
        isRunning = false
        packetJob?.cancel()
        packetJob = null
        vpnInterface?.close()
        vpnInterface = null
        clearRuntimeState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
        LogRepository.append(this, "VPN stopped")
    }

    private fun clearRuntimeState() {
        httpDecryptEnabled = false
        mitmCertificateInstalled = false
        TlsMitmSessionManager.clear(this)
        appNameCache.clear()
        domainAppCache.clear()
        sourcePortAppCache.clear()
        appLabelCache.clear()
        dnsResponseCache.clear()
        decisionLogCache.clear()
        httpDecryptIpCache.clear()
        httpsDecryptIpCache.clear()
        quicRouteCache.clear()
        httpsProxyFlowCache.clear()
        httpsBridgeSocketCache.values.forEach { it.close() }
        httpsBridgeSocketCache.clear()
        upstreamServerStates.clear()
        cachedDnsServers = null
        dnsSocketPool.forEach { (_, socket) -> socket.close() }
        dnsSocketPool.clear()
    }

    private fun buildInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.99.0.1", 24)
            .addAddress("fd66:66::1", 64)
            .addDnsServer(localDnsV4)
            .addDnsServer(localDnsV6)
            .addRoute(localDnsV4, 32)
            .addRoute(localDnsV6, 128)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.allowFamily(OsConstants.AF_INET)
            builder.allowFamily(OsConstants.AF_INET6)
        }

        blockedIpNetworks.forEach { network ->
            runCatching {
                builder.addRoute(network.routeAddress, network.prefixLength)
            }.onFailure {
                LogRepository.append(this, "Skip blocked route ${network.routeAddress}/${network.prefixLength}: ${it.message ?: it.javaClass.simpleName}")
            }
        }

        loadDynamicHttpDecryptRoutes().forEach { network ->
            runCatching {
                builder.addRoute(network.routeAddress, network.prefixLength)
            }.onFailure {
                LogRepository.append(this, "Skip HTTP decrypt route ${network.routeAddress}/${network.prefixLength}: ${it.message ?: it.javaClass.simpleName}")
            }
        }

        loadDynamicHttpsDecryptRoutes().forEach { network ->
            runCatching {
                builder.addRoute(network.routeAddress, network.prefixLength)
            }.onFailure {
                LogRepository.append(this, "Skip HTTPS decrypt route ${network.routeAddress}/${network.prefixLength}: ${it.message ?: it.javaClass.simpleName}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WhitelistRepository.getPackages(this).forEach { packageName ->
                runCatching { builder.addDisallowedApplication(packageName) }
            }
        }

        return builder.establish()
    }

    private fun runPacketLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val descriptor = vpnInterface ?: return
        FileInputStream(descriptor.fileDescriptor).use { input ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                tunOutputStream = output
                val buffer = ByteArray(32767)
                while (scope.isActive && isRunning) {
                    val length = input.read(buffer)
                    if (length <= 0) continue
                    runCatching {
                        handlePacket(buffer, length, output)
                    }.onFailure { error ->
                        LogRepository.append(this, "Packet handling failed: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
                tunOutputStream = null
            }
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        val info = PacketCodec.parse(packet, length) ?: return
        findBlockedIpNetwork(info.destinationAddress)?.let { network ->
            return
        }
        val isUdp = info.protocol == OsConstants.IPPROTO_UDP
        val isTcp = info.protocol == OsConstants.IPPROTO_TCP
        if (isUdp && info.destinationPort == 53) {
            if (!shouldHandleDns(info.destinationAddress)) {
                return
            }

            val question = DnsMessageParser.parseQuestion(info.payload) ?: return

            // 白名单域名直接放行，跳过规则匹配（避免冷加载开销）
            if (RuleRepository.isWhitelistedDomain(question.domain)) {
                val appName = resolveAppName(question.domain, info)
                RuleRepository.classifyVendorSimple(this, question.domain, appName)?.let { vendor ->
                    StatsRepository.recordRequest(this, vendor, appName)
                }
                readCachedDnsResponse(question, info.payload)?.let { cachedResponse ->
                    output.write(PacketCodec.buildUdpResponse(info, cachedResponse))
                    return
                }
                val upstreamResult = queryUpstreamDns(info.payload)
                val upstreamResponse = upstreamResult?.response
                    ?: readStaleCachedDnsResponse(question, info.payload)
                    ?: DnsMessageParser.buildServerFailureResponse(info.payload, question)
                cacheDnsResponse(question, upstreamResponse)
                output.write(PacketCodec.buildUdpResponse(info, upstreamResponse))
                return
            }
            val matchedRule = RuleRepository.findMatchingRule(this, question.domain, question.qType)
            val isBlocked = RuleRepository.isBlocked(this, question.domain, question.qType)
            val aggressiveNovelBlock = if (!isBlocked) {
                val appName = resolveAppName(question.domain, info)
                val vendor = matchedRule?.vendor ?: RuleRepository.classifyVendorFromHints(this, question.domain, appName)
                RuleRepository.shouldAggressivelyBlockForNovelApp(this, question.domain, appName, vendor)
            } else false

            if (isBlocked || aggressiveNovelBlock) {
                val appName = resolveAppName(question.domain, info)
                val vendor = matchedRule?.vendor ?: RuleRepository.classifyVendorFromHints(this, question.domain, appName)
                val response = DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return
                output.write(PacketCodec.buildUdpResponse(info, response))
                StatsRepository.recordBlockedDns(this, vendor, appName, 512)
                return
            }

            val appName = resolveAppName(question.domain, info)
            val vendor = matchedRule?.vendor ?: RuleRepository.classifyVendorFromHints(this, question.domain, appName)
            StatsRepository.recordRequest(this, vendor, appName)
            RuleRepository.reportUnknownVendorIfNeeded(this, vendor, question.domain, appName)

            readCachedDnsResponse(question, info.payload)?.let { cachedResponse ->
                output.write(PacketCodec.buildUdpResponse(info, cachedResponse))
                return
            }

            val upstreamResult = queryUpstreamDns(info.payload)
            val upstreamResponse = upstreamResult?.response
                ?: readStaleCachedDnsResponse(question, info.payload)
                ?: DnsMessageParser.buildServerFailureResponse(info.payload, question)

            val aliasTargets = DnsMessageParser.extractAliasTargets(upstreamResponse, question)
            val blockedAliasTarget = aliasTargets.firstOrNull { aliasTarget ->
                RuleRepository.isBlocked(this, aliasTarget)
            }
            if (blockedAliasTarget != null) {
                val sinkholeResponse = DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return
                output.write(PacketCodec.buildUdpResponse(info, sinkholeResponse))
                StatsRepository.recordBlockedDns(this, vendor, appName, 512)
                return
            }
            if (aliasTargets.isNotEmpty()) {
                rememberHttpDecryptAliasTargets(question, aliasTargets, upstreamResponse, appName)
                rememberHttpsDecryptAliasTargets(question, aliasTargets, upstreamResponse, appName)
                rememberQuicAliasTargets(question, aliasTargets, upstreamResponse, appName)
            }
            rememberQuicTargets(question, upstreamResponse, appName, vendor)
            rememberHttpDecryptTargets(question, upstreamResponse, appName, vendor)
            rememberHttpsDecryptTargets(question, upstreamResponse, appName, vendor)
            cacheDnsResponse(question, upstreamResponse)
            output.write(PacketCodec.buildUdpResponse(info, upstreamResponse))
            return
        }

        if (!httpDecryptEnabled) {
            return
        }
        if (isTcp) {
            if (shouldBlockHttpDecryptConnection(info)) {
                return
            }
            observeHttpsTransparentProxyFlow(info)
            if (handleHttpsProxyHandshake(info, output)) {
                return
            }
            observeHttpsClientHello(info)
            return
        }
        if (isUdp && info.destinationPort == 443 && shouldBlockQuicFlow(info)) {
            return
        }
    }

    private fun shouldBlockQuicFlow(info: com.HanFeng.model.PacketInfo): Boolean {
        if (info.protocol != OsConstants.IPPROTO_UDP || info.destinationPort != 443) return false
        val payload = info.payload
        if (payload.isEmpty()) return false
        if (!looksLikeQuicPacket(payload)) return false

        val destinationIp = formatAddress(info.destinationAddress)
        maybePruneRouteCaches()
        val route = synchronized(quicRouteCache) {
            quicRouteCache[destinationIp]
        }
        val httpsTarget = synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache[destinationIp]
        }
        val domain = httpsTarget?.domain ?: route?.domain ?: return false
        if (RuleRepository.isWhitelistedDomain(domain)) return false
        if (RuleRepository.isSensitiveAuthDomain(domain)) return false

        val appName = httpsTarget?.appName?.takeIf { it.isNotBlank() }
            ?: route?.appName?.takeIf { it.isNotBlank() }
            ?: resolveAppName(domain, info)
        val vendor = httpsTarget?.vendor?.takeIf { it.isNotBlank() }
            ?: route?.vendor?.takeIf { it.isNotBlank() }
            ?: RuleRepository.classifyVendorFromHints(this, domain, appName)
        val matchedRule = RuleRepository.findMatchingRule(this, domain)
        val aggressiveNovelBlock = RuleRepository.shouldAggressivelyBlockForNovelApp(this, domain, appName, vendor)
        val bypassReason = HttpsMitmRepository.getActiveBypassReason(this, domain)
        val shouldForceTcpFallback = httpDecryptEnabled && httpsTarget != null && bypassReason == null
        if (matchedRule == null && !aggressiveNovelBlock && !shouldForceTcpFallback) return false

        val reason = when {
            matchedRule != null -> "matched-rule"
            aggressiveNovelBlock -> "novel-aggressive"
            else -> "force-tcp-fallback"
        }
        StatsRepository.recordBlockedHttp(this, vendor, appName, 64 * 1024)
        logDecisionOnce(
            key = "quic-block:$domain:$destinationIp",
            message = "Blocked QUIC/HTTP3 flow domain=$domain ip=$destinationIp app=$appName vendor=$vendor reason=$reason route=${route?.source ?: httpsTarget?.source ?: "unknown"} bypass=${bypassReason ?: "none"}",
            minIntervalMillis = 30_000L
        )
        return true
    }

    private fun looksLikeQuicPacket(payload: ByteArray): Boolean {
        val firstByte = payload[0].toInt() and 0xFF
        if ((firstByte and 0x40) == 0) return false
        return (firstByte and 0x80) != 0 || firstByte in 0x40..0x7f
    }

    private fun shouldBlockHttpDecryptConnection(info: com.HanFeng.model.PacketInfo): Boolean {
        if (info.protocol != OsConstants.IPPROTO_TCP || info.destinationPort != 80) return false
        val ip = formatAddress(info.destinationAddress)
        maybePruneRouteCaches()
        val target = synchronized(httpDecryptIpCache) {
            httpDecryptIpCache[ip]
        } ?: return false
        if (RuleRepository.isSensitiveAuthDomain(target.domain)) return false
        val appName = resolveAppName(target.domain, info)
        StatsRepository.recordBlockedHttp(this, target.vendor, appName, 50 * 1024)
        logDecisionOnce(
            key = "blocked-http80:${target.domain}:$ip",
            message = "Blocked HTTP connection domain=${target.domain} ip=$ip app=$appName vendor=${target.vendor} source=${target.source} via=http-decrypt-entry",
            minIntervalMillis = 10_000L
        )
        return true
    }

    private fun observeHttpsClientHello(info: com.HanFeng.model.PacketInfo) {
        if (info.protocol != OsConstants.IPPROTO_TCP || info.destinationPort != 443) return
        if (info.payload.isEmpty()) return
        val destinationIp = formatAddress(info.destinationAddress)
        maybePruneRouteCaches()
        val decryptTarget = synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache[destinationIp]
        } ?: return
        if (RuleRepository.isWhitelistedDomain(decryptTarget.domain)) return
        if (RuleRepository.isSensitiveAuthDomain(decryptTarget.domain)) return
        val clientHelloInfo = TlsClientHelloParser.extractClientHelloInfo(info.payload) ?: return
        val sniHost = clientHelloInfo.sniHost ?: return
        if (RuleRepository.isWhitelistedDomain(sniHost)) return
        if (RuleRepository.isSensitiveAuthDomain(sniHost)) return
        val appName = resolveAppName(sniHost, info)
        val decryptSource = decryptTarget.source
        logDecisionOnce(
            key = "https-sni:$sniHost:${formatAddress(info.destinationAddress)}",
            message = "Observed HTTPS ClientHello SNI domain=$sniHost app=$appName target=$destinationIp source=$decryptSource alpn=${clientHelloInfo.offeredAlpnProtocols.joinToString(",").ifBlank { "none" }} prefersHttp2=${clientHelloInfo.offeredAlpnProtocols.any { it.equals("h2", ignoreCase = true) }} tls=${clientHelloInfo.handshakeVersion ?: "unknown"}",
            minIntervalMillis = 15_000L
        )
        RuleRepository.reportUnknownVendorIfNeeded(
            this,
            RuleRepository.classifyVendorFromHints(this, sniHost, appName),
            sniHost,
            appName
        )
        if (!mitmCertificateInstalled) {
            logDecisionOnce(
                key = "https-mitm-not-ready:$sniHost",
                message = "Skipped HTTPS MITM prewarm because CA is not installed domain=$sniHost app=$appName source=$decryptSource",
                minIntervalMillis = 30_000L
            )
            return
        }
        CertificateAuthorityManager.ensureLeafCertificate(this, sniHost)
            .onSuccess {
                TlsMitmSessionManager.registerObservedSession(
                    context = this,
                    flowKey = flowCacheKey(info),
                    host = sniHost,
                    appName = appName,
                    source = decryptSource,
                    targetIp = destinationIp,
                    targetPort = info.destinationPort,
                    certificatePath = it.filePath,
                    offeredAlpnProtocols = clientHelloInfo.offeredAlpnProtocols,
                    clientHelloTlsVersion = clientHelloInfo.handshakeVersion
                )
                TlsMitmSessionManager.prepareTlsBridge(this, flowCacheKey(info), this::protect)
                logDecisionOnce(
                    key = "https-leaf:$sniHost",
                    message = "Prepared HTTPS MITM leaf certificate for domain=$sniHost path=${it.filePath} source=$decryptSource",
                    minIntervalMillis = 60_000L
                )
            }
            .onFailure {
                LogRepository.append(this, "Prepare HTTPS leaf certificate failed for $sniHost: ${it.message ?: it.javaClass.simpleName}")
            }
    }

    private fun observeHttpsTransparentProxyFlow(info: com.HanFeng.model.PacketInfo) {
        if (info.protocol != OsConstants.IPPROTO_TCP || info.destinationPort != 443) return
        val ip = formatAddress(info.destinationAddress)
        maybePruneRouteCaches()
        val target = synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache[ip]
        } ?: return
        if (RuleRepository.isSensitiveAuthDomain(target.domain)) {
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache.remove(flowCacheKey(info))
            }
            return
        }
        
        val cooldownReason = HttpsMitmRepository.getActiveBypassReason(this, target.domain)
        if (cooldownReason != null) {
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache.remove(flowCacheKey(info))
            }
            return
        }
        
        val flowKey = flowCacheKey(info)
        val existingSession = TlsMitmSessionManager.getSession(flowKey)
        if (existingSession?.bypassMitm == true) {
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache.remove(flowKey)
            }
            return
        }
        
        val preparedSession = TlsMitmSessionManager.findPreparedSession(
            targetIp = ip,
            targetPort = info.destinationPort,
            appName = null
        )
        
        val flags = info.tcpFlags
        synchronized(httpsProxyFlowCache) {
            val current = httpsProxyFlowCache[flowKey]
            val nextState = when {
                flags.hasTcpFlag(TCP_FLAG_SYN) && !flags.hasTcpFlag(TCP_FLAG_ACK) -> "syn_seen"
                flags.hasTcpFlag(TCP_FLAG_SYN) && flags.hasTcpFlag(TCP_FLAG_ACK) -> "syn_ack_seen"
                flags.hasTcpFlag(TCP_FLAG_FIN) -> "fin_seen"
                flags.hasTcpFlag(TCP_FLAG_RST) -> "rst_seen"
                preparedSession?.localBridgePort != null -> "bridge_bound"
                info.payload.isNotEmpty() -> "payload_seen"
                else -> current?.state ?: "tracked"
            }
            httpsProxyFlowCache[flowKey] = HttpsProxyFlow(
                flowKey = flowKey,
                domain = target.domain,
                vendor = target.vendor,
                source = target.source,
                targetIp = ip,
                sourcePort = info.sourcePort,
                appName = preparedSession?.appName?.takeIf { it.isNotBlank() } ?: "",
                state = nextState,
                bridgeHost = preparedSession?.localBridgeHost,
                bridgePort = preparedSession?.localBridgePort,
                lastSequenceNumber = info.tcpSequenceNumber,
                lastAcknowledgementNumber = info.tcpAcknowledgementNumber,
                lastSeenAt = System.currentTimeMillis()
            )
            while (httpsProxyFlowCache.size > 512) {
                val firstKey = httpsProxyFlowCache.entries.firstOrNull()?.key ?: break
                httpsProxyFlowCache.remove(firstKey)
            }
        }
    }

    private fun handleHttpsProxyHandshake(info: com.HanFeng.model.PacketInfo, output: FileOutputStream): Boolean {
        if (info.protocol != OsConstants.IPPROTO_TCP || info.destinationPort != 443) return false
        val flowKey = flowCacheKey(info)
        val current = synchronized(httpsProxyFlowCache) {
            httpsProxyFlowCache[flowKey]
        } ?: return false
        if (current.bridgePort == null) return false
        val flags = info.tcpFlags
        val seq = info.tcpSequenceNumber ?: 0L
        val ack = info.tcpAcknowledgementNumber ?: 0L
        val payloadLength = info.payload.size.toLong()
        if (flags.hasTcpFlag(TCP_FLAG_SYN) && !flags.hasTcpFlag(TCP_FLAG_ACK)) {
            val serverSeq = current.serverInitialSequence ?: synthesizeServerSequence(flowKey)
            val next = current.copy(
                state = "syn_ack_sent",
                clientInitialSequence = seq,
                serverInitialSequence = serverSeq,
                lastSequenceNumber = seq,
                lastAcknowledgementNumber = ack,
                lastSeenAt = System.currentTimeMillis()
            )
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache[flowKey] = next
            }
            writeTunPacket(
                PacketCodec.buildTcpResponse(
                    request = info,
                    sequenceNumber = serverSeq,
                    acknowledgementNumber = seq + 1,
                    flags = TCP_FLAG_SYN or TCP_FLAG_ACK,
                    windowSize = info.tcpWindowSize ?: DEFAULT_TCP_WINDOW_SIZE
                )
            )
            logDecisionOnce(
                key = "https-proxy-handshake:$flowKey:synack",
                message = "Sent synthetic SYN-ACK for HTTPS proxy flow domain=${current.domain} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort}",
                minIntervalMillis = 5_000L
            )
            return true
        }
        if (current.state == "syn_ack_sent" && flags == TCP_FLAG_ACK && payloadLength == 0L) {
            ensureHttpsBridgeSocket(flowKey, current, info)
            val next = current.copy(
                state = "established",
                lastSequenceNumber = seq,
                lastAcknowledgementNumber = ack,
                clientNextSequence = seq,
                lastSeenAt = System.currentTimeMillis()
            )
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache[flowKey] = next
            }
            logDecisionOnce(
                key = "https-proxy-handshake:$flowKey:established",
                message = "Established synthetic HTTPS proxy flow domain=${current.domain} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort}",
                minIntervalMillis = 5_000L
            )
            return true
        }
        if ((current.state == "established" || current.state == "payload_acknowledged") && (payloadLength > 0 || flags.hasTcpFlag(TCP_FLAG_PSH))) {
            val serverSeq = current.serverInitialSequence ?: synthesizeServerSequence(flowKey)
            val expectedClientSeq = current.clientNextSequence ?: ((current.clientInitialSequence ?: seq) + 1)
            val isRetransmission = seq < expectedClientSeq || (current.lastClientPayloadSequence == seq && current.lastClientPayloadLength == payloadLength)
            if (seq > expectedClientSeq) {
                val updatedBufferedSegments = if (info.payload.isNotEmpty()) {
                    mergeBufferedClientSegments(
                        current.bufferedClientSegments,
                        ClientPayloadSegment(seq, info.payload)
                    )
                } else {
                    current.bufferedClientSegments
                }
                if (bufferedClientPayloadBytes(updatedBufferedSegments) > MAX_BUFFERED_CLIENT_BYTES) {
                    emitHttpsBridgeReset(flowKey, info, "Buffered client window overflow reset HTTPS proxy flow domain=${current.domain}")
                    return true
                }
                synchronized(httpsProxyFlowCache) {
                    val latest = httpsProxyFlowCache[flowKey] ?: return@synchronized
                    httpsProxyFlowCache[flowKey] = latest.copy(
                        bufferedClientSegments = updatedBufferedSegments,
                        lastSeenAt = System.currentTimeMillis()
                    )
                }
                writeTunPacket(
                    PacketCodec.buildTcpResponse(
                        request = info,
                        sequenceNumber = serverSeq + 1,
                        acknowledgementNumber = expectedClientSeq,
                        flags = TCP_FLAG_ACK,
                        windowSize = info.tcpWindowSize ?: DEFAULT_TCP_WINDOW_SIZE
                    )
                )
                logDecisionOnce(
                    key = "https-proxy-handshake:$flowKey:out-of-order",
                    message = "Buffered out-of-order HTTPS proxy payload domain=${current.domain} source=${current.source} seq=$seq expected=$expectedClientSeq bufferedSegments=${updatedBufferedSegments.size} bufferedBytes=${bufferedClientPayloadBytes(updatedBufferedSegments)}",
                    minIntervalMillis = 3_000L
                )
                return true
            }
            val inboundSegments = mutableListOf<ClientPayloadSegment>()
            var ackNumber = expectedClientSeq
            if (!isRetransmission && info.payload.isNotEmpty()) {
                inboundSegments += ClientPayloadSegment(seq, info.payload)
                ackNumber = maxOf(ackNumber, seq + payloadLength)
            }
            val flushResult = drainBufferedClientSegments(
                mergeBufferedClientSegments(current.bufferedClientSegments, inboundSegments),
                ackNumber
            )
            if (bufferedClientPayloadBytes(flushResult.remainingSegments) > MAX_BUFFERED_CLIENT_BYTES) {
                emitHttpsBridgeReset(flowKey, info, "Buffered client replay overflow reset HTTPS proxy flow domain=${current.domain}")
                return true
            }
            flushResult.forwardSegments.forEach { segment ->
                forwardPayloadToHttpsBridge(flowKey, segment.payload)
            }
            val lastForwardedSegment = flushResult.forwardSegments.lastOrNull()
            val next = current.copy(
                state = "payload_acknowledged",
                lastSequenceNumber = seq,
                lastAcknowledgementNumber = ack,
                clientNextSequence = flushResult.nextExpectedSequence,
                bufferedClientSegments = flushResult.remainingSegments,
                lastClientPayloadSequence = lastForwardedSegment?.sequenceNumber ?: current.lastClientPayloadSequence,
                lastClientPayloadLength = lastForwardedSegment?.payload?.size?.toLong() ?: current.lastClientPayloadLength,
                lastSeenAt = System.currentTimeMillis()
            )
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache[flowKey] = next
            }
            writeTunPacket(
                PacketCodec.buildTcpResponse(
                    request = info,
                    sequenceNumber = serverSeq + 1,
                    acknowledgementNumber = flushResult.nextExpectedSequence,
                    flags = TCP_FLAG_ACK,
                    windowSize = info.tcpWindowSize ?: DEFAULT_TCP_WINDOW_SIZE
                )
            )
            logDecisionOnce(
                key = "https-proxy-handshake:$flowKey:payload-ack",
                message = if (isRetransmission) {
                    "Acknowledged retransmitted HTTPS proxy payload domain=${current.domain} source=${current.source} size=$payloadLength bridge=${current.bridgeHost}:${current.bridgePort} nextClientSeq=${flushResult.nextExpectedSequence} bufferedSegments=${flushResult.remainingSegments.size} bufferedBytes=${bufferedClientPayloadBytes(flushResult.remainingSegments)}"
                } else {
                    "Acknowledged HTTPS proxy payload domain=${current.domain} source=${current.source} size=$payloadLength bridge=${current.bridgeHost}:${current.bridgePort} nextClientSeq=${flushResult.nextExpectedSequence} bufferedSegments=${flushResult.remainingSegments.size} bufferedBytes=${bufferedClientPayloadBytes(flushResult.remainingSegments)}"
                },
                minIntervalMillis = 5_000L
            )
            return true
        }
        if ((current.state == "server_payload_sent" || current.state == "payload_acknowledged") && flags == TCP_FLAG_ACK && payloadLength == 0L) {
            if (current.pendingServerSegments.isNotEmpty() && current.serverNextSequence != null && ack < current.serverNextSequence) {
                val remainingSegments = trimAcknowledgedServerSegments(current.pendingServerSegments, ack)
                if (remainingSegments.isNotEmpty()) {
                    resendPendingHttpsBridgePayload(info, current, remainingSegments)
                    synchronized(httpsProxyFlowCache) {
                        val latest = httpsProxyFlowCache[flowKey] ?: return@synchronized
                        httpsProxyFlowCache[flowKey] = latest.copy(
                            pendingServerSegments = remainingSegments,
                            lastSeenAt = System.currentTimeMillis()
                        )
                    }
                    logDecisionOnce(
                        key = "https-proxy-handshake:$flowKey:server-retransmit",
                        message = "Retransmitted synthetic HTTPS server payload window domain=${current.domain} source=${current.source} ack=$ack expected=${current.serverNextSequence} pendingSegments=${remainingSegments.size} pendingBytes=${pendingServerPayloadBytes(remainingSegments)}",
                        minIntervalMillis = 3_000L
                    )
                    return true
                }
            }
            synchronized(httpsProxyFlowCache) {
                val latest = httpsProxyFlowCache[flowKey] ?: return@synchronized
                httpsProxyFlowCache[flowKey] = latest.copy(
                    state = if (latest.state == "bridge_fin_sent") "bridge_fin_acked" else "server_payload_acked",
                    pendingServerSegments = emptyList(),
                    lastSequenceNumber = seq,
                    lastAcknowledgementNumber = ack,
                    lastSeenAt = System.currentTimeMillis()
                )
            }
            logDecisionOnce(
                key = "https-proxy-handshake:$flowKey:server-acked",
                message = "Acknowledged synthetic HTTPS server payload domain=${current.domain} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort} ack=$ack pendingSegments=0 pendingBytes=0",
                minIntervalMillis = 5_000L
            )
            return true
        }
        if (current.state == "bridge_fin_sent" && flags == TCP_FLAG_ACK && payloadLength == 0L) {
            closeHttpsProxyFlow(flowKey, current, "Closed bridge-finished HTTPS proxy flow domain=${current.domain}")
            return true
        }
        if (flags.hasTcpFlag(TCP_FLAG_FIN)) {
            val serverSeqBase = current.serverInitialSequence ?: synthesizeServerSequence(flowKey)
            val nextServerSeq = current.serverNextSequence ?: (serverSeqBase + 1)
            val ackNumber = seq + 1 + payloadLength
            synchronized(httpsProxyFlowCache) {
                val latest = httpsProxyFlowCache[flowKey] ?: current
                httpsProxyFlowCache[flowKey] = latest.copy(
                    state = "fin_ack_sent",
                    lastSequenceNumber = seq,
                    lastAcknowledgementNumber = ack,
                    serverNextSequence = nextServerSeq + 1,
                    clientNextSequence = ackNumber,
                    lastSeenAt = System.currentTimeMillis()
                )
            }
            writeTunPacket(
                PacketCodec.buildTcpResponse(
                    request = info,
                    sequenceNumber = nextServerSeq,
                    acknowledgementNumber = ackNumber,
                    flags = TCP_FLAG_FIN or TCP_FLAG_ACK,
                    windowSize = info.tcpWindowSize ?: DEFAULT_TCP_WINDOW_SIZE
                )
            )
            logDecisionOnce(
                key = "https-proxy-handshake:$flowKey:finack",
                message = "Sent synthetic FIN-ACK for HTTPS proxy flow domain=${current.domain} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort}",
                minIntervalMillis = 5_000L
            )
            synchronized(httpsBridgeSocketCache) {
                httpsBridgeSocketCache.remove(flowKey)?.close()
            }
            return true
        }
        if (current.state == "fin_ack_sent" && flags == TCP_FLAG_ACK && payloadLength == 0L) {
            closeHttpsProxyFlow(flowKey, current, "Closed synthetic HTTPS proxy flow domain=${current.domain}")
            return true
        }
        if (flags.hasTcpFlag(TCP_FLAG_RST)) {
            closeHttpsProxyFlow(flowKey, current, "Reset synthetic HTTPS proxy flow domain=${current.domain}")
            return true
        }
        return false
    }

    private fun ensureHttpsBridgeSocket(flowKey: String, flow: HttpsProxyFlow, request: com.HanFeng.model.PacketInfo) {
        synchronized(httpsBridgeSocketCache) {
            if (httpsBridgeSocketCache.containsKey(flowKey)) return
        }
        val host = flow.bridgeHost ?: return
        val port = flow.bridgePort ?: return
        scope.launch {
            runCatching {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), HTTPS_BRIDGE_CONNECT_TIMEOUT_MILLIS)
                val session = HttpsBridgeSocketSession(
                    flowKey = flowKey,
                    requestTemplate = request,
                    socket = socket,
                    input = socket.getInputStream(),
                    output = socket.getOutputStream()
                )
                synchronized(httpsBridgeSocketCache) {
                    httpsBridgeSocketCache[flowKey] = session
                }
                scope.launch {
                    runHttpsBridgeReader(session)
                }
                LogRepository.append(this@AdBlockVpnService, "Connected local HTTPS bridge socket flow=$flowKey domain=${flow.domain} source=${flow.source} local=$host:$port")
            }.onFailure {
                LogRepository.append(this@AdBlockVpnService, "Connect local HTTPS bridge socket failed flow=$flowKey: ${it.message ?: it.javaClass.simpleName}")
                HttpsMitmRepository.markBypassCooldown(
                    this@AdBlockVpnService,
                    flow.domain,
                    reason = "io-bridge-connect:${it.message ?: it.javaClass.simpleName}",
                    cooldownMillis = 5 * 60 * 1000L
                )
                emitHttpsBridgeReset(flowKey, request, "Bridge connect reset HTTPS proxy flow domain=${flow.domain}")
            }
        }
    }

    private fun forwardPayloadToHttpsBridge(flowKey: String, payload: ByteArray) {
        if (payload.isEmpty()) return
        val session = synchronized(httpsBridgeSocketCache) {
            httpsBridgeSocketCache[flowKey]
        } ?: return
        scope.launch {
            runCatching {
                session.output.write(payload)
            }.onFailure {
                LogRepository.append(this@AdBlockVpnService, "Forward HTTPS payload to bridge failed flow=$flowKey: ${it.message ?: it.javaClass.simpleName}")
                val domain = resolveHttpsProxyDomain(flowKey)
                if (domain != "unknown") {
                    HttpsMitmRepository.markBypassCooldown(
                        this@AdBlockVpnService,
                        domain,
                        reason = "io-bridge-write:${it.message ?: it.javaClass.simpleName}",
                        cooldownMillis = 2 * 60 * 1000L
                    )
                }
                emitHttpsBridgeReset(flowKey, session.requestTemplate, "Bridge write reset HTTPS proxy flow domain=${resolveHttpsProxyDomain(flowKey)}")
            }
        }
    }

    private fun mergeBufferedClientSegments(
        existing: List<ClientPayloadSegment>,
        additions: List<ClientPayloadSegment>
    ): List<ClientPayloadSegment> {
        if (additions.isEmpty()) return existing
        val allSegments = (existing + additions)
            .filter { it.payload.isNotEmpty() }
            .sortedBy { it.sequenceNumber }
        if (allSegments.isEmpty()) return emptyList()
        val normalized = mutableListOf<ClientPayloadSegment>()
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
                ClientPayloadSegment(
                    sequenceNumber = previous.sequenceNumber,
                    payload = previous.payload + appendPayload
                )
            }
        }
        return normalized.takeLast(MAX_BUFFERED_CLIENT_SEGMENTS)
    }

    private fun mergeBufferedClientSegments(
        existing: List<ClientPayloadSegment>,
        addition: ClientPayloadSegment
    ): List<ClientPayloadSegment> = mergeBufferedClientSegments(existing, listOf(addition))

    private fun drainBufferedClientSegments(
        segments: List<ClientPayloadSegment>,
        expectedSequence: Long
    ): ClientSegmentDrainResult {
        if (segments.isEmpty()) {
            return ClientSegmentDrainResult(
                nextExpectedSequence = expectedSequence,
                forwardSegments = emptyList(),
                remainingSegments = emptyList()
            )
        }
        val forwardSegments = mutableListOf<ClientPayloadSegment>()
        val remainingSegments = mutableListOf<ClientPayloadSegment>()
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
                forwardSegments += ClientPayloadSegment(nextExpectedSequence, forwardPayload)
                nextExpectedSequence += forwardPayload.size
            }
        }
        return ClientSegmentDrainResult(
            nextExpectedSequence = nextExpectedSequence,
            forwardSegments = forwardSegments,
            remainingSegments = remainingSegments.takeLast(MAX_BUFFERED_CLIENT_SEGMENTS)
        )
    }

    private fun runHttpsBridgeReader(session: HttpsBridgeSocketSession) {
        val buffer = ByteArray(16 * 1024)
        var resetSent = false
        while (scope.isActive && isRunning) {
            val count = try {
                session.input.read(buffer)
            } catch (error: Exception) {
                LogRepository.append(this, "Read HTTPS bridge payload failed flow=${session.flowKey}: ${error.message ?: error.javaClass.simpleName}")
                val domain = resolveHttpsProxyDomain(session.flowKey)
                if (domain != "unknown") {
                    HttpsMitmRepository.markBypassCooldown(
                        this,
                        domain,
                        reason = "io-bridge-read:${error.message ?: error.javaClass.simpleName}",
                        cooldownMillis = 2 * 60 * 1000L
                    )
                }
                emitHttpsBridgeReset(session.flowKey, session.requestTemplate, "Bridge read reset HTTPS proxy flow domain=${resolveHttpsProxyDomain(session.flowKey)}")
                resetSent = true
                break
            }
            if (count <= 0) break
            val payload = buffer.copyOf(count)
            emitHttpsBridgePayload(session.flowKey, session.requestTemplate, payload)
        }
        if (!resetSent) {
            emitHttpsBridgeFin(session.flowKey, session.requestTemplate)
        }
        synchronized(httpsBridgeSocketCache) {
            httpsBridgeSocketCache.remove(session.flowKey)?.close()
        }
        synchronized(httpsProxyFlowCache) {
            val current = httpsProxyFlowCache[session.flowKey] ?: return@synchronized
            httpsProxyFlowCache[session.flowKey] = current.copy(
                state = "bridge_socket_closed",
                lastSeenAt = System.currentTimeMillis()
            )
        }
    }

    private fun emitHttpsBridgePayload(flowKey: String, request: com.HanFeng.model.PacketInfo, payload: ByteArray) {
        val flow = synchronized(httpsProxyFlowCache) {
            httpsProxyFlowCache[flowKey]
        } ?: return
        val serverSeqBase = flow.serverInitialSequence ?: synthesizeServerSequence(flowKey)
        val nextServerSeq = flow.serverNextSequence ?: (serverSeqBase + 1)
        val clientAck = flow.clientNextSequence ?: ((flow.clientInitialSequence ?: 0L) + 1)
        val freshSegments = buildServerPayloadSegments(nextServerSeq, payload)
        writeServerPayloadSegments(request, clientAck, freshSegments)
        var pendingSummary = 0 to 0
        synchronized(httpsProxyFlowCache) {
            val current = httpsProxyFlowCache[flowKey] ?: return@synchronized
            val mergedPendingSegments = mergePendingServerSegments(current.pendingServerSegments, freshSegments)
            if (pendingServerPayloadBytes(mergedPendingSegments) > MAX_BUFFERED_SERVER_BYTES) {
                emitHttpsBridgeReset(flowKey, request, "Pending server window overflow reset HTTPS proxy flow domain=${current.domain}")
                return@synchronized
            }
            pendingSummary = mergedPendingSegments.size to pendingServerPayloadBytes(mergedPendingSegments)
            httpsProxyFlowCache[flowKey] = current.copy(
                state = "server_payload_sent",
                serverNextSequence = nextServerSeq + payload.size,
                lastServerPayloadSequence = nextServerSeq,
                lastServerPayload = payload,
                pendingServerSegments = mergedPendingSegments,
                lastSeenAt = System.currentTimeMillis()
            )
        }
        logDecisionOnce(
            key = "https-proxy-bridge-payload:$flowKey",
            message = "Forwarded HTTPS bridge payload flow=$flowKey domain=${flow.domain} source=${flow.source} size=${payload.size} pendingSegments=${pendingSummary.first} pendingBytes=${pendingSummary.second}",
            minIntervalMillis = 3_000L
        )
    }

    private fun resendPendingHttpsBridgePayload(
        request: com.HanFeng.model.PacketInfo,
        flow: HttpsProxyFlow,
        segments: List<PendingServerSegment>
    ) {
        if (segments.isEmpty()) return
        val clientAck = flow.clientNextSequence ?: ((flow.clientInitialSequence ?: 0L) + 1)
        writeServerPayloadSegments(request, clientAck, segments)
    }

    private fun trimAcknowledgedServerSegments(
        segments: List<PendingServerSegment>,
        acknowledgementNumber: Long
    ): List<PendingServerSegment> {
        if (segments.isEmpty()) return emptyList()
        val remainingSegments = ArrayList<PendingServerSegment>(segments.size)
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
                remainingSegments += PendingServerSegment(
                    sequenceNumber = acknowledgementNumber,
                    payload = remainingPayload
                )
            }
        }
        return remainingSegments
    }

    private fun mergePendingServerSegments(
        existing: List<PendingServerSegment>,
        additions: List<PendingServerSegment>
    ): List<PendingServerSegment> {
        if (additions.isEmpty()) return existing
        val allSegments = (existing + additions)
            .filter { it.payload.isNotEmpty() }
            .sortedBy { it.sequenceNumber }
        if (allSegments.isEmpty()) return emptyList()
        val normalized = mutableListOf<PendingServerSegment>()
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
                PendingServerSegment(
                    sequenceNumber = previous.sequenceNumber,
                    payload = previous.payload + appendPayload
                )
            }
        }
        return normalized.takeLast(MAX_BUFFERED_SERVER_SEGMENTS)
    }

    private fun buildServerPayloadSegments(sequenceNumber: Long, payload: ByteArray): List<PendingServerSegment> {
        if (payload.isEmpty()) return emptyList()
        val segments = ArrayList<PendingServerSegment>((payload.size / TCP_SEGMENT_PAYLOAD_SIZE) + 1)
        var sentBytes = 0
        var currentSeq = sequenceNumber
        while (sentBytes < payload.size) {
            val chunkSize = min(TCP_SEGMENT_PAYLOAD_SIZE, payload.size - sentBytes)
            val chunk = payload.copyOfRange(sentBytes, sentBytes + chunkSize)
            segments += PendingServerSegment(
                sequenceNumber = currentSeq,
                payload = chunk
            )
            sentBytes += chunkSize
            currentSeq += chunkSize
        }
        return segments
    }

    private fun writeServerPayloadSegments(
        request: com.HanFeng.model.PacketInfo,
        acknowledgementNumber: Long,
        segments: List<PendingServerSegment>
    ) {
        segments.forEach { segment ->
            val packet = PacketCodec.buildTcpResponse(
                request = request,
                sequenceNumber = segment.sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                flags = TCP_FLAG_PSH or TCP_FLAG_ACK,
                payload = segment.payload,
                windowSize = DEFAULT_TCP_WINDOW_SIZE
            )
            writeTunPacket(packet)
        }
    }

    private fun emitHttpsBridgeFin(flowKey: String, request: com.HanFeng.model.PacketInfo) {
        val flow = synchronized(httpsProxyFlowCache) {
            httpsProxyFlowCache[flowKey]
        } ?: return
        if (flow.state == "fin_ack_sent" || flow.state == "bridge_fin_sent" || flow.state == "bridge_fin_acked") return
        val serverSeqBase = flow.serverInitialSequence ?: synthesizeServerSequence(flowKey)
        val nextServerSeq = flow.serverNextSequence ?: (serverSeqBase + 1)
        val clientAck = flow.clientNextSequence ?: ((flow.clientInitialSequence ?: 0L) + 1)
        writeTunPacket(
            PacketCodec.buildTcpResponse(
                request = request,
                sequenceNumber = nextServerSeq,
                acknowledgementNumber = clientAck,
                flags = TCP_FLAG_FIN or TCP_FLAG_ACK,
                windowSize = DEFAULT_TCP_WINDOW_SIZE
            )
        )
        synchronized(httpsProxyFlowCache) {
            val current = httpsProxyFlowCache[flowKey] ?: return@synchronized
            httpsProxyFlowCache[flowKey] = current.copy(
                state = "bridge_fin_sent",
                serverNextSequence = nextServerSeq + 1,
                lastSeenAt = System.currentTimeMillis()
            )
        }
        logDecisionOnce(
            key = "https-proxy-bridge-fin:$flowKey",
            message = "Sent bridge-initiated FIN-ACK for HTTPS proxy flow domain=${flow.domain} source=${flow.source}",
            minIntervalMillis = 5_000L
        )
    }

    private fun closeHttpsProxyFlow(flowKey: String, flow: HttpsProxyFlow, message: String) {
        synchronized(httpsProxyFlowCache) {
            httpsProxyFlowCache.remove(flowKey)
        }
        synchronized(httpsBridgeSocketCache) {
            httpsBridgeSocketCache.remove(flowKey)?.close()
        }
        logDecisionOnce(
            key = "https-proxy-handshake:$flowKey:closed",
            message = message,
            minIntervalMillis = 5_000L
        )
    }

    private fun emitHttpsBridgeReset(flowKey: String, request: com.HanFeng.model.PacketInfo, message: String) {
        val flow = synchronized(httpsProxyFlowCache) {
            httpsProxyFlowCache[flowKey]
        } ?: return
        val serverSeqBase = flow.serverInitialSequence ?: synthesizeServerSequence(flowKey)
        val nextServerSeq = flow.serverNextSequence ?: (serverSeqBase + 1)
        val clientAck = flow.clientNextSequence ?: ((flow.clientInitialSequence ?: 0L) + 1)
        writeTunPacket(
            PacketCodec.buildTcpResponse(
                request = request,
                sequenceNumber = nextServerSeq,
                acknowledgementNumber = clientAck,
                flags = TCP_FLAG_RST or TCP_FLAG_ACK,
                windowSize = DEFAULT_TCP_WINDOW_SIZE
            )
        )
        closeHttpsProxyFlow(flowKey, flow, message)
    }

    private fun resolveHttpsProxyDomain(flowKey: String): String {
        return synchronized(httpsProxyFlowCache) {
            httpsProxyFlowCache[flowKey]?.domain
        } ?: "unknown"
    }

    private fun bufferedClientPayloadBytes(segments: List<ClientPayloadSegment>): Int {
        return segments.sumOf { it.payload.size }
    }

    private fun pendingServerPayloadBytes(segments: List<PendingServerSegment>): Int {
        return segments.sumOf { it.payload.size }
    }

    private fun writeTunPacket(packet: ByteArray) {
        val output = tunOutputStream ?: return
        synchronized(tunWriteLock) {
            output.write(packet)
        }
    }

    private fun rememberHttpDecryptTargets(
        question: com.HanFeng.model.DnsQuestion,
        response: ByteArray,
        appName: String,
        vendor: String
    ) {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return
        val matchedRule = RuleRepository.findMatchingRule(this, question.domain, question.qType)
        val aggressiveNovelBlock = RuleRepository.shouldAggressivelyBlockForNovelApp(this, question.domain, appName, vendor)
        if (matchedRule == null && !aggressiveNovelBlock) return
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 120_000L)
        val routeEntries = mutableListOf<HttpDecryptRouteRepository.RouteEntry>()
        synchronized(httpDecryptIpCache) {
            pruneHttpDecryptTargetsLocked()
            addresses.forEach { address ->
                val ip = formatAddress(address)
                val prefixLength = address.size * 8
                httpDecryptIpCache[ip] = HttpDecryptTarget(
                    domain = question.domain.lowercase(),
                    vendor = vendor,
                    appName = appName,
                    source = "direct",
                    expiresAt = expiresAt
                )
                routeEntries += HttpDecryptRouteRepository.RouteEntry(
                    ip = ip,
                    prefixLength = prefixLength,
                    domain = question.domain.lowercase(),
                    vendor = vendor,
                    expiresAt = expiresAt
                )
            }
            while (httpDecryptIpCache.size > 1024) {
                val firstKey = httpDecryptIpCache.entries.firstOrNull()?.key ?: break
                httpDecryptIpCache.remove(firstKey)
            }
        }
        if (HttpDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload()
        }
    }

    private fun pruneHttpDecryptTargetsLocked() {
        val now = System.currentTimeMillis()
        httpDecryptIpCache.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun maybePruneRouteCaches() {
        val now = System.currentTimeMillis()
        if (now - lastHttpDecryptPruneAt >= routeCachePruneIntervalMillis) {
            synchronized(httpDecryptIpCache) {
                if (now - lastHttpDecryptPruneAt >= routeCachePruneIntervalMillis) {
                    pruneHttpDecryptTargetsLocked()
                    lastHttpDecryptPruneAt = now
                }
            }
        }
        if (now - lastHttpsDecryptPruneAt >= routeCachePruneIntervalMillis) {
            synchronized(httpsDecryptIpCache) {
                if (now - lastHttpsDecryptPruneAt >= routeCachePruneIntervalMillis) {
                    pruneHttpsDecryptTargetsLocked()
                    lastHttpsDecryptPruneAt = now
                }
            }
        }
        if (now - lastQuicRoutePruneAt >= routeCachePruneIntervalMillis) {
            synchronized(quicRouteCache) {
                if (now - lastQuicRoutePruneAt >= routeCachePruneIntervalMillis) {
                    pruneQuicTargetsLocked()
                    lastQuicRoutePruneAt = now
                }
            }
        }
    }

    private fun rememberHttpDecryptAliasTargets(
        question: com.HanFeng.model.DnsQuestion,
        aliasTargets: List<String>,
        response: ByteArray,
        appName: String
    ) {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return
        val matchedAliases = aliasTargets.filter { aliasTarget ->
            RuleRepository.findMatchingRule(this, aliasTarget, question.qType) != null ||
                RuleRepository.shouldAggressivelyBlockForNovelApp(
                    this,
                    aliasTarget,
                    appName,
                    RuleRepository.classifyVendorFromHints(this, aliasTarget, appName)
                )
        }.distinct()
        if (matchedAliases.isEmpty()) return
        val matchedAlias = matchedAliases.first()
        val vendor = RuleRepository.classifyVendorFromHints(this, matchedAlias, appName)
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 120_000L)
        val routeEntries = mutableListOf<HttpDecryptRouteRepository.RouteEntry>()
        synchronized(httpDecryptIpCache) {
            pruneHttpDecryptTargetsLocked()
            addresses.forEach { address ->
                val ip = formatAddress(address)
                val prefixLength = address.size * 8
                httpDecryptIpCache[ip] = HttpDecryptTarget(
                    domain = matchedAlias,
                    vendor = vendor,
                    appName = appName,
                    source = "alias",
                    expiresAt = expiresAt
                )
                routeEntries += HttpDecryptRouteRepository.RouteEntry(
                    ip = ip,
                    prefixLength = prefixLength,
                    domain = matchedAlias,
                    vendor = vendor,
                    expiresAt = expiresAt
                )
            }
            while (httpDecryptIpCache.size > 1024) {
                val firstKey = httpDecryptIpCache.entries.firstOrNull()?.key ?: break
                httpDecryptIpCache.remove(firstKey)
            }
        }
        if (HttpDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload()
            logDecisionOnce(
                key = "http-decrypt-alias:${question.domain.lowercase()}",
                message = "Registered HTTP decrypt alias targets source=${question.domain} aliases=${matchedAliases.joinToString(",")} app=$appName vendor=$vendor ips=${routeEntries.joinToString(",") { it.ip }}",
                minIntervalMillis = 15_000L
            )
        } else {
            logDecisionOnce(
                key = "http-decrypt-alias-skip:${question.domain.lowercase()}",
                message = "HTTP decrypt alias targets already active source=${question.domain} aliases=${matchedAliases.joinToString(",")} app=$appName vendor=$vendor",
                minIntervalMillis = 15_000L
            )
        }
    }

    private fun rememberHttpsDecryptTargets(
        question: com.HanFeng.model.DnsQuestion,
        response: ByteArray,
        appName: String,
        vendor: String
    ) {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return
        val matchedRule = RuleRepository.findMatchingRule(this, question.domain, question.qType)
        val aggressiveNovelBlock = RuleRepository.shouldAggressivelyBlockForNovelApp(this, question.domain, appName, vendor)
        if (matchedRule == null && !aggressiveNovelBlock) return
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 120_000L)
        val routeEntries = mutableListOf<HttpsDecryptRouteRepository.RouteEntry>()
        synchronized(httpsDecryptIpCache) {
            pruneHttpsDecryptTargetsLocked()
            addresses.forEach { address ->
                val ip = formatAddress(address)
                val prefixLength = address.size * 8
                httpsDecryptIpCache[ip] = HttpsDecryptTarget(
                    domain = question.domain.lowercase(),
                    vendor = vendor,
                    appName = appName,
                    source = "direct",
                    expiresAt = expiresAt
                )
                routeEntries += HttpsDecryptRouteRepository.RouteEntry(
                    ip = ip,
                    prefixLength = prefixLength,
                    domain = question.domain.lowercase(),
                    vendor = vendor,
                    expiresAt = expiresAt
                )
            }
            while (httpsDecryptIpCache.size > 1024) {
                val firstKey = httpsDecryptIpCache.entries.firstOrNull()?.key ?: break
                httpsDecryptIpCache.remove(firstKey)
            }
        }
        if (HttpsDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload()
        }
    }

    private fun pruneHttpsDecryptTargetsLocked() {
        val now = System.currentTimeMillis()
        httpsDecryptIpCache.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun rememberQuicTargets(
        question: com.HanFeng.model.DnsQuestion,
        response: ByteArray,
        appName: String,
        vendor: String
    ) {
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 180_000L)
        synchronized(quicRouteCache) {
            pruneQuicTargetsLocked()
            addresses.forEach { address ->
                quicRouteCache[formatAddress(address)] = QuicRouteTarget(
                    domain = question.domain.lowercase(),
                    vendor = vendor,
                    appName = appName,
                    source = "direct",
                    expiresAt = expiresAt
                )
            }
            while (quicRouteCache.size > 2048) {
                val firstKey = quicRouteCache.entries.firstOrNull()?.key ?: break
                quicRouteCache.remove(firstKey)
            }
        }
    }

    private fun rememberQuicAliasTargets(
        question: com.HanFeng.model.DnsQuestion,
        aliasTargets: List<String>,
        response: ByteArray,
        appName: String
    ) {
        val firstAlias = aliasTargets.firstOrNull()?.lowercase() ?: return
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val vendor = RuleRepository.classifyVendorFromHints(this, firstAlias, appName)
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 180_000L)
        synchronized(quicRouteCache) {
            pruneQuicTargetsLocked()
            addresses.forEach { address ->
                quicRouteCache[formatAddress(address)] = QuicRouteTarget(
                    domain = firstAlias,
                    vendor = vendor,
                    appName = appName,
                    source = "alias",
                    expiresAt = expiresAt
                )
            }
            while (quicRouteCache.size > 2048) {
                val firstKey = quicRouteCache.entries.firstOrNull()?.key ?: break
                quicRouteCache.remove(firstKey)
            }
        }
    }

    private fun pruneQuicTargetsLocked() {
        val now = System.currentTimeMillis()
        quicRouteCache.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun rememberHttpsDecryptAliasTargets(
        question: com.HanFeng.model.DnsQuestion,
        aliasTargets: List<String>,
        response: ByteArray,
        appName: String
    ) {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return
        val matchedAliases = aliasTargets.filter { aliasTarget ->
            RuleRepository.findMatchingRule(this, aliasTarget, question.qType) != null ||
                RuleRepository.shouldAggressivelyBlockForNovelApp(
                    this,
                    aliasTarget,
                    appName,
                    RuleRepository.classifyVendorFromHints(this, aliasTarget, appName)
                )
        }.distinct()
        if (matchedAliases.isEmpty()) return
        val matchedAlias = matchedAliases.first()
        val vendor = RuleRepository.classifyVendorFromHints(this, matchedAlias, appName)
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 120_000L)
        val routeEntries = mutableListOf<HttpsDecryptRouteRepository.RouteEntry>()
        synchronized(httpsDecryptIpCache) {
            pruneHttpsDecryptTargetsLocked()
            addresses.forEach { address ->
                val ip = formatAddress(address)
                val prefixLength = address.size * 8
                httpsDecryptIpCache[ip] = HttpsDecryptTarget(
                    domain = matchedAlias,
                    vendor = vendor,
                    appName = appName,
                    source = "alias",
                    expiresAt = expiresAt
                )
                routeEntries += HttpsDecryptRouteRepository.RouteEntry(
                    ip = ip,
                    prefixLength = prefixLength,
                    domain = matchedAlias,
                    vendor = vendor,
                    expiresAt = expiresAt
                )
            }
            while (httpsDecryptIpCache.size > 1024) {
                val firstKey = httpsDecryptIpCache.entries.firstOrNull()?.key ?: break
                httpsDecryptIpCache.remove(firstKey)
            }
        }
        if (HttpsDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload()
            logDecisionOnce(
                key = "https-decrypt-alias:${question.domain.lowercase()}",
                message = "Registered HTTPS decrypt alias targets source=${question.domain} aliases=${matchedAliases.joinToString(",")} app=$appName vendor=$vendor ips=${routeEntries.joinToString(",") { it.ip }}",
                minIntervalMillis = 15_000L
            )
        } else {
            logDecisionOnce(
                key = "https-decrypt-alias-skip:${question.domain.lowercase()}",
                message = "HTTPS decrypt alias targets already active source=${question.domain} aliases=${matchedAliases.joinToString(",")} app=$appName vendor=$vendor",
                minIntervalMillis = 15_000L
            )
        }
    }

    private fun queryUpstreamDns(payload: ByteArray): UpstreamDnsResult? {
        resolveDnsServers().forEach { server ->
            repeat(2) { attempt ->
                val socket = acquireDnsSocket(server) ?: return@repeat
                runCatching {
                    socket.soTimeout = if (attempt == 0) 1200 else 1800
                    socket.connect(server, 53)
                    socket.send(DatagramPacket(payload, payload.size))
                    val receiveBuffer = ByteArray(4096)
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(packet)
                    markUpstreamSuccess(server)
                    releaseDnsSocket(server, socket)
                    return UpstreamDnsResult(server, packet.data.copyOf(packet.length))
                }.onFailure {
                    socket.disconnect()
                    markUpstreamFailure(server)
                    if (attempt == 1) {
                        LogRepository.append(this, "Upstream DNS ${server.hostAddress} failed: ${it.message ?: it.javaClass.simpleName}")
                    }
                    releaseDnsSocket(server, socket)
                }
            }
        }
        return null
    }

    private fun acquireDnsSocket(server: InetAddress): DatagramSocket? {
        synchronized(dnsSocketPoolLock) {
            val poolEntry = dnsSocketPool.poll()
            if (poolEntry != null) {
                if (poolEntry.first == server && !poolEntry.second.isClosed) {
                    return poolEntry.second
                }
                poolEntry.second.close()
            }
        }
        return runCatching {
            DatagramSocket().also { protect(it) }
        }.getOrNull()
    }

    private fun releaseDnsSocket(server: InetAddress, socket: DatagramSocket) {
        if (socket.isClosed) return
        synchronized(dnsSocketPoolLock) {
            if (dnsSocketPool.size < 4) {
                dnsSocketPool.offer(server to socket)
            } else {
                socket.close()
            }
        }
    }

    private fun logDecisionOnce(key: String, message: String, minIntervalMillis: Long) {
        val now = System.currentTimeMillis()
        synchronized(decisionLogCache) {
            val previous = decisionLogCache[key]
            if (previous != null && now - previous < minIntervalMillis) {
                return
            }
            decisionLogCache[key] = now
            while (decisionLogCache.size > 256) {
                val firstKey = decisionLogCache.entries.firstOrNull()?.key ?: break
                decisionLogCache.remove(firstKey)
            }
        }
        LogRepository.append(this, message)
    }

    private fun resolveDnsServers(): List<InetAddress> {
        val now = System.currentTimeMillis()
        cachedDnsServers?.takeIf { it.expiresAt > now }?.let { return it.servers }
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val linkProperties: LinkProperties? = connectivityManager.getLinkProperties(connectivityManager.activeNetwork)
        val dynamicServers = linkProperties?.dnsServers.orEmpty()
            .filterNot { handledDnsHosts.contains(it.hostAddress ?: "") }
        val candidates = (dynamicServers + upstreamFallbackDnsServers)
            .distinctBy { it.hostAddress ?: it.hostName }
        val sorted = candidates.sortedWith(
            compareBy<InetAddress> { currentUpstreamState(it).cooldownUntil > now }
                .thenBy { currentUpstreamState(it).failureCount }
                .thenByDescending { currentUpstreamState(it).lastSuccessAt }
                .thenBy { it.hostAddress ?: it.hostName }
        )
        synchronized(dnsServerCacheLock) {
            cachedDnsServers = CachedDnsServers(sorted, now + dnsServerCacheTtlMillis)
        }
        return sorted
    }

    private fun readCachedDnsResponse(question: com.HanFeng.model.DnsQuestion, queryPayload: ByteArray): ByteArray? {
        val now = System.currentTimeMillis()
        val key = DnsMessageParser.buildCacheKey(question)
        synchronized(dnsResponseCache) {
            dnsResponseCache.entries.removeIf { it.value.expiresAt + staleCacheGraceMillis <= now }
            val cached = dnsResponseCache[key] ?: return null
            if (cached.expiresAt <= now) return null
            return DnsMessageParser.restoreCachedResponseForQuery(cached.payload, queryPayload)
        }
    }

    private fun readStaleCachedDnsResponse(question: com.HanFeng.model.DnsQuestion, queryPayload: ByteArray): ByteArray? {
        val now = System.currentTimeMillis()
        val key = DnsMessageParser.buildCacheKey(question)
        synchronized(dnsResponseCache) {
            dnsResponseCache.entries.removeIf { it.value.expiresAt + staleCacheGraceMillis <= now }
            val cached = dnsResponseCache[key] ?: return null
            if (cached.expiresAt > now) return null
            return DnsMessageParser.restoreCachedResponseForQuery(cached.payload, queryPayload)
        }
    }

    private fun cacheDnsResponse(question: com.HanFeng.model.DnsQuestion, response: ByteArray) {
        val expiresAt = when {
            DnsMessageParser.isCacheableResponse(response, question) -> System.currentTimeMillis() + DnsMessageParser.extractCacheTtlMillis(response)
            DnsMessageParser.isNegativeCacheableResponse(response, question) -> System.currentTimeMillis() + DnsMessageParser.negativeCacheTtlMillis()
            else -> return
        }
        val normalized = DnsMessageParser.normalizeResponseForCache(response)
        synchronized(dnsResponseCache) {
            dnsResponseCache[DnsMessageParser.buildCacheKey(question)] = CachedDnsResponse(normalized, expiresAt)
            while (dnsResponseCache.size > 256) {
                val firstKey = dnsResponseCache.entries.firstOrNull()?.key ?: break
                dnsResponseCache.remove(firstKey)
            }
        }
    }

    private fun markUpstreamSuccess(server: InetAddress) {
        val key = server.hostAddress ?: server.hostName
        val now = System.currentTimeMillis()
        synchronized(upstreamServerStates) {
            upstreamServerStates[key] = UpstreamServerState(
                failureCount = 0,
                cooldownUntil = 0L,
                lastSuccessAt = now
            )
        }
    }

    private fun markUpstreamFailure(server: InetAddress) {
        val key = server.hostAddress ?: server.hostName
        val now = System.currentTimeMillis()
        synchronized(upstreamServerStates) {
            val current = upstreamServerStates[key] ?: UpstreamServerState()
            val failures = (current.failureCount + 1).coerceAtMost(6)
            val cooldown = now + (400L shl (failures - 1)).coerceAtMost(15_000L)
            upstreamServerStates[key] = current.copy(
                failureCount = failures,
                cooldownUntil = cooldown
            )
        }
    }

    private fun currentUpstreamState(server: InetAddress): UpstreamServerState {
        val key = server.hostAddress ?: server.hostName
        synchronized(upstreamServerStates) {
            return upstreamServerStates[key] ?: UpstreamServerState()
        }
    }

    private fun shouldHandleDns(address: ByteArray): Boolean {
        return handledDnsHosts.contains(formatAddress(address))
    }

    private fun loadDynamicHttpDecryptRoutes(): List<BlockedIpNetwork> {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return emptyList()
        HttpDecryptRouteRepository.pruneExpired(this)
        return HttpDecryptRouteRepository.getRoutes(this).mapNotNull { route ->
            val address = runCatching { InetAddress.getByName(route.ip) }.getOrNull() ?: return@mapNotNull null
            val maxPrefixLength = address.address.size * 8
            if (route.prefixLength !in 0..maxPrefixLength) return@mapNotNull null
            BlockedIpNetwork(
                addressBytes = address.address,
                prefixLength = route.prefixLength,
                routeAddress = address.hostAddress ?: route.ip
            )
        }
    }

    private fun loadDynamicHttpsDecryptRoutes(): List<BlockedIpNetwork> {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return emptyList()
        HttpsDecryptRouteRepository.pruneExpired(this)
        return HttpsDecryptRouteRepository.getRoutes(this).mapNotNull { route ->
            val address = runCatching { InetAddress.getByName(route.ip) }.getOrNull() ?: return@mapNotNull null
            val maxPrefixLength = address.address.size * 8
            if (route.prefixLength !in 0..maxPrefixLength) return@mapNotNull null
            BlockedIpNetwork(
                addressBytes = address.address,
                prefixLength = route.prefixLength,
                routeAddress = address.hostAddress ?: route.ip
            )
        }
    }

    private fun requestHttpDecryptRouteReload() {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        if (now - lastHttpRouteReloadAt < 3_000L) return
        lastHttpRouteReloadAt = now
        startService(Intent(this, AdBlockVpnService::class.java).setAction(ACTION_RELOAD))
        LogRepository.append(this, "Reloaded VPN for new HTTP decrypt routes")
    }

    private fun loadBlockedIpNetworks(): List<BlockedIpNetwork> {
        return resources.openRawResource(R.raw.default_blocked_ip_ranges).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val trimmed = line.substringBefore('#').trim()
                if (trimmed.isBlank()) return@mapNotNull null
                parseBlockedIpNetwork(trimmed)
            }.toList()
        }
    }

    private fun parseBlockedIpNetwork(raw: String): BlockedIpNetwork? {
        val addressPart = raw.substringBefore('/').trim()
        val prefixPart = raw.substringAfter('/', missingDelimiterValue = "").trim()
        val address = runCatching { InetAddress.getByName(addressPart) }.getOrNull() ?: return null
        val maxPrefixLength = address.address.size * 8
        val prefixLength = prefixPart.toIntOrNull() ?: maxPrefixLength
        if (prefixLength !in 0..maxPrefixLength) return null
        return BlockedIpNetwork(
            addressBytes = address.address,
            prefixLength = prefixLength,
            routeAddress = address.hostAddress ?: addressPart
        )
    }

    private fun findBlockedIpNetwork(address: ByteArray): BlockedIpNetwork? {
        return blockedIpNetworks.firstOrNull { network ->
            network.addressBytes.size == address.size && matchesPrefix(address, network.addressBytes, network.prefixLength)
        }
    }

    private fun matchesPrefix(address: ByteArray, networkAddress: ByteArray, prefixLength: Int): Boolean {
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until fullBytes) {
            if (address[index] != networkAddress[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (address[fullBytes].toInt() and mask) == (networkAddress[fullBytes].toInt() and mask)
    }

    private fun formatAddress(bytes: ByteArray): String = InetAddress.getByAddress(bytes).hostAddress ?: ""

    private fun resolveAppName(domain: String, info: com.HanFeng.model.PacketInfo): String {
        readCachedAppName(info)?.let { return it }
        readCachedDomainApp(domain)?.let {
            cacheAppName(info, domain, it)
            return it
        }
        readCachedSourcePortApp(info)?.let {
            cacheAppName(info, domain, it)
            return it
        }
        val resolved = resolveAppNameByUid(info)
        if (resolved != null) {
            cacheAppName(info, domain, resolved)
            return resolved
        }
        return readCachedPortAppName(info) ?: "未知应用"
    }

    private fun resolveAppNameByUid(info: com.HanFeng.model.PacketInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val protocol = if (info.protocol == OsConstants.IPPROTO_UDP) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(InetAddress.getByAddress(info.sourceAddress), info.sourcePort)
            val remote = InetSocketAddress(InetAddress.getByAddress(info.destinationAddress), info.destinationPort)
            val uid = connectivityManager.getConnectionOwnerUid(protocol, local, remote)
            if (uid <= 0) return@runCatching null
            buildAppLabel(uid)
        }.getOrElse {
            LogRepository.append(this, "Resolve app failed: ${it.message ?: it.javaClass.simpleName}")
            null
        }
    }

    private fun buildAppLabel(uid: Int): String? {
        appLabelCache[uid]?.let { return it }
        val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull()
            ?: packageManager.getNameForUid(uid)
            ?: return null
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        return if (label == packageName) packageName else "$label ($packageName)".also { appLabelCache[uid] = it }
    }

    private fun readCachedAppName(info: com.HanFeng.model.PacketInfo): String? {
        synchronized(appNameCache) {
            return appNameCache[flowCacheKey(info)]
        }
    }

    private fun readCachedPortAppName(info: com.HanFeng.model.PacketInfo): String? {
        synchronized(appNameCache) {
            return appNameCache[portCacheKey(info)]
        }
    }

    private fun readCachedSourcePortApp(info: com.HanFeng.model.PacketInfo): String? {
        synchronized(sourcePortAppCache) {
            return sourcePortAppCache[sourcePortCacheKey(info)]
        }
    }

    private fun readCachedDomainApp(domain: String): String? {
        val normalized = domain.lowercase()
        synchronized(domainAppCache) {
            return domainAppCache[normalized] ?: secondLevelDomain(normalized)?.let(domainAppCache::get)
        }
    }

    private fun cacheAppName(info: com.HanFeng.model.PacketInfo, domain: String, appName: String) {
        appNameCache[flowCacheKey(info)] = appName
        appNameCache[portCacheKey(info)] = appName
        sourcePortAppCache[sourcePortCacheKey(info)] = appName
        val normalized = domain.lowercase()
        domainAppCache[normalized] = appName
        secondLevelDomain(normalized)?.let { domainAppCache[it] = appName }
    }

    private fun secondLevelDomain(domain: String): String? {
        val parts = domain.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts.takeLast(2).joinToString(".")
    }

    private fun flowCacheKey(info: com.HanFeng.model.PacketInfo): String {
        return listOf(
            info.protocol.toString(),
            formatAddress(info.sourceAddress),
            info.sourcePort.toString(),
            formatAddress(info.destinationAddress),
            info.destinationPort.toString()
        ).joinToString(":")
    }

    private fun portCacheKey(info: com.HanFeng.model.PacketInfo): String {
        return "${info.protocol}:${formatAddress(info.sourceAddress)}:${info.sourcePort}"
    }

    private fun sourcePortCacheKey(info: com.HanFeng.model.PacketInfo): String {
        return "${formatAddress(info.sourceAddress)}:${info.sourcePort}"
    }

    private fun synthesizeServerSequence(flowKey: String): Long {
        return (flowKey.hashCode().toLong() and 0x7FFF_FFFFL) + 10_000L
    }

    private fun Int.hasTcpFlag(flag: Int): Boolean = (this and flag) == flag

    private fun describeTcpFlags(flags: Int): String {
        if (flags == 0) return "none"
        val labels = mutableListOf<String>()
        if (flags.hasTcpFlag(TCP_FLAG_FIN)) labels += "FIN"
        if (flags.hasTcpFlag(TCP_FLAG_SYN)) labels += "SYN"
        if (flags.hasTcpFlag(TCP_FLAG_RST)) labels += "RST"
        if (flags.hasTcpFlag(TCP_FLAG_PSH)) labels += "PSH"
        if (flags.hasTcpFlag(TCP_FLAG_ACK)) labels += "ACK"
        if (flags.hasTcpFlag(TCP_FLAG_URG)) labels += "URG"
        return labels.joinToString("|")
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val httpEnabled = httpDecryptEnabled
        val certInstalled = mitmCertificateInstalled
        val contentText = when {
            httpEnabled && certInstalled -> "MITM+DNS拦截"
            httpEnabled -> "DNS拦截 (待装证书)"
            else -> "DNS拦截模式"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("寒枫广告拦截运行中")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "广告拦截服务", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun refreshRuntimeFeatureFlags() {
        httpDecryptEnabled = FeatureSettingsRepository.isHttpDecryptEnabled(this)
        mitmCertificateInstalled = HttpsMitmRepository.isCertificateInstalled(this)
    }

    companion object {
        const val ACTION_STOP = "com.HanFeng.STOP"
        const val ACTION_RELOAD = "com.HanFeng.RELOAD"
        private const val CHANNEL_ID = "adblock_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val TCP_FLAG_FIN = 0x01
        private const val TCP_FLAG_SYN = 0x02
        private const val TCP_FLAG_RST = 0x04
        private const val TCP_FLAG_PSH = 0x08
        private const val TCP_FLAG_ACK = 0x10
        private const val TCP_FLAG_URG = 0x20
        private const val DEFAULT_TCP_WINDOW_SIZE = 65535
        private const val TCP_SEGMENT_PAYLOAD_SIZE = 1400
        private const val HTTPS_BRIDGE_CONNECT_TIMEOUT_MILLIS = 4_000
        private const val MAX_BUFFERED_CLIENT_SEGMENTS = 32
        private const val MAX_BUFFERED_SERVER_SEGMENTS = 32
        private const val MAX_BUFFERED_CLIENT_BYTES = 256 * 1024
        private const val MAX_BUFFERED_SERVER_BYTES = 256 * 1024

        @Volatile
        var isRunning: Boolean = false
    }

    private data class CachedDnsResponse(
        val payload: ByteArray,
        val expiresAt: Long
    )

    private data class UpstreamServerState(
        val failureCount: Int = 0,
        val cooldownUntil: Long = 0L,
        val lastSuccessAt: Long = 0L
    )

    private data class CachedDnsServers(
        val servers: List<InetAddress>,
        val expiresAt: Long
    )

    private data class UpstreamDnsResult(
        val server: InetAddress,
        val response: ByteArray
    )

    private data class BlockedIpNetwork(
        val addressBytes: ByteArray,
        val prefixLength: Int,
        val routeAddress: String
    )

    private data class HttpDecryptTarget(
        val domain: String,
        val vendor: String,
        val appName: String,
        val source: String,
        val expiresAt: Long
    )

    private data class HttpsDecryptTarget(
        val domain: String,
        val vendor: String,
        val appName: String,
        val source: String,
        val expiresAt: Long
    )

    private data class QuicRouteTarget(
        val domain: String,
        val vendor: String,
        val appName: String,
        val source: String,
        val expiresAt: Long
    )

    private data class HttpsProxyFlow(
        val flowKey: String,
        val domain: String,
        val vendor: String,
        val source: String,
        val targetIp: String,
        val sourcePort: Int,
        val appName: String,
        val state: String,
        val bridgeHost: String?,
        val bridgePort: Int?,
        val clientInitialSequence: Long? = null,
        val serverInitialSequence: Long? = null,
        val clientNextSequence: Long? = null,
        val serverNextSequence: Long? = null,
        val lastServerPayloadSequence: Long? = null,
        val lastServerPayload: ByteArray? = null,
        val pendingServerSegments: List<PendingServerSegment> = emptyList(),
        val bufferedClientSegments: List<ClientPayloadSegment> = emptyList(),
        val lastClientPayloadSequence: Long? = null,
        val lastClientPayloadLength: Long? = null,
        val lastSequenceNumber: Long?,
        val lastAcknowledgementNumber: Long?,
        val lastSeenAt: Long
    )

    private data class PendingServerSegment(
        val sequenceNumber: Long,
        val payload: ByteArray
    )

    private data class ClientPayloadSegment(
        val sequenceNumber: Long,
        val payload: ByteArray
    )

    private data class ClientSegmentDrainResult(
        val nextExpectedSequence: Long,
        val forwardSegments: List<ClientPayloadSegment>,
        val remainingSegments: List<ClientPayloadSegment>
    )

    private data class HttpsBridgeSocketSession(
        val flowKey: String,
        val requestTemplate: com.HanFeng.model.PacketInfo,
        val socket: Socket,
        val input: java.io.InputStream,
        val output: java.io.OutputStream
    ) {
        fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { socket.close() }
        }
    }
}
