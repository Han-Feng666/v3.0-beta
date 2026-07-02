package com.HanFeng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.system.OsConstants
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.HanFeng.R
import com.HanFeng.core.network.FullCaptureRoutingSupport
import com.HanFeng.core.network.HttpsHandshakeEngine
import com.HanFeng.core.network.HttpsBridgeFailureSupport
import com.HanFeng.core.network.HttpsPipeline
import com.HanFeng.core.network.LocalProxyBridgeConnectSupport
import com.HanFeng.core.network.MitmAppFullCaptureTargetSupport
import com.HanFeng.core.network.NetworkModeCoordinator
import com.HanFeng.core.network.NetworkRuntimeSettingsStore
import com.HanFeng.core.network.DnsRuntimeSupport
import com.HanFeng.core.network.BridgeSocketSupport
import com.HanFeng.core.network.BridgeSessionSupport
import com.HanFeng.core.network.DnsOverHttpsClient
import com.HanFeng.core.network.DecisionLogSupport
import com.HanFeng.core.network.BridgeConnectionPool
import com.HanFeng.core.network.BridgeFlowStateSupport
import com.HanFeng.core.network.BridgeFailureSupport
import com.HanFeng.core.network.BridgeLifecycleSupport
import com.HanFeng.core.network.BridgeReaderSupport
import com.HanFeng.core.network.BridgeResetSupport
import com.HanFeng.core.network.BridgeTerminalStateSupport
import com.HanFeng.core.network.ClientPayloadReplaySupport
import com.HanFeng.core.network.EncryptedDnsBlocker
import com.HanFeng.core.network.ExpiringTargetCacheSupport
import com.HanFeng.core.network.FlowCacheSupport
import com.HanFeng.core.network.RouteCacheMaintenanceSupport
import com.HanFeng.core.network.ServerAckStateSupport
import com.HanFeng.core.network.TcpSyntheticFlowEngine
import com.HanFeng.core.network.TunPacketWriter
import com.HanFeng.core.network.TrafficDecisionEngine
import com.HanFeng.core.network.UpstreamDnsSupport
import com.HanFeng.core.network.MitmLearningEngine
import com.HanFeng.core.network.ProcNetResolver
import com.HanFeng.core.network.UserAdFeedbackManager
import com.HanFeng.core.network.VpnHealthChecker
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpsDecryptRouteRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.HttpDecryptRouteRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.PromoGovernSnapshotRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuAdControlCatalog
import com.HanFeng.data.ShizukuConnectionOwnerRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.data.StatsRepository
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.dns.DnsMessageParser
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RuleSource
import com.HanFeng.model.DnsQuestion
import com.HanFeng.model.LocalProxyCoexistConfig
import com.HanFeng.security.CertificateAuthorityManager
import com.HanFeng.security.QuicSniParser
import com.HanFeng.security.TlsClientHelloParser
import com.HanFeng.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.min

class AdBlockVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetJob: Job? = null
    private var pendingRouteReloadJob: Job? = null
    private var pendingVpnReloadJob: Job? = null
    private var pendingReacquireJob: Job? = null
    private var pendingLocalProxyProbeJob: Job? = null
    private var pendingDeferredSetupJob: Job? = null
    @Volatile private var startInProgress = false
    @Volatile private var foregroundShown = false
    @Volatile private var activeTunGeneration = 0L
    private val appNameCache = ConcurrentHashMap<String, String>(VpnConstants.APP_NAME_CACHE_MAX_SIZE)
    private val domainAppCache = ConcurrentHashMap<String, String>(VpnConstants.DOMAIN_APP_CACHE_MAX_SIZE)
    private val sourcePortAppCache = ConcurrentHashMap<String, String>(VpnConstants.SOURCE_PORT_APP_CACHE_MAX_SIZE)
    private val ownerUidCache = ConcurrentHashMap<String, Int>(VpnConstants.OWNER_UID_CACHE_MAX_SIZE)
    private val ownerUidFailureCache = ConcurrentHashMap<String, Long>(VpnConstants.OWNER_UID_FAILURE_CACHE_MAX_SIZE)
    private val appLabelCache = ConcurrentHashMap<Int, String>(VpnConstants.APP_LABEL_CACHE_MAX_SIZE)
    private val vendorHintCache = ConcurrentHashMap<String, String>(VpnConstants.VENDOR_HINT_CACHE_MAX_SIZE)
    private val dnsResponseCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, DnsRuntimeSupport.CachedDnsResponse>(VpnConstants.DNS_RESPONSE_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, DnsRuntimeSupport.CachedDnsResponse>
    private val decisionLogCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>(VpnConstants.DECISION_LOG_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, Long>
    private val adIpTargetCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, AdIpTarget>(VpnConstants.AD_IP_TARGET_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, AdIpTarget>
    private val httpDecryptIpCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, HttpDecryptTarget>(VpnConstants.HTTP_DECRYPT_IP_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, HttpDecryptTarget>
    private val httpsDecryptIpCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, HttpsDecryptTarget>(VpnConstants.HTTPS_DECRYPT_IP_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, HttpsDecryptTarget>
    private val quicRouteCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, QuicRouteTarget>(VpnConstants.QUIC_ROUTE_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, QuicRouteTarget>
    private val httpsProxyFlowCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, HttpsProxyFlow>(VpnConstants.HTTPS_PROXY_FLOW_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, HttpsProxyFlow>
    private val httpsBridgeSocketCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, HttpsBridgeSocketSession>(VpnConstants.HTTPS_BRIDGE_SOCKET_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, HttpsBridgeSocketSession>
    private val localProxyTcpFlowCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, LocalProxyTcpFlow>(VpnConstants.LOCAL_PROXY_TCP_FLOW_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, LocalProxyTcpFlow>
    private val localProxyBridgeSocketCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, LocalProxyBridgeSocketSession>(VpnConstants.LOCAL_PROXY_BRIDGE_SOCKET_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, LocalProxyBridgeSocketSession>
    private val passthroughTcpFlowCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, PassthroughTcpFlow>(VpnConstants.PASSTHROUGH_TCP_FLOW_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, PassthroughTcpFlow>
    private val passthroughTcpSocketCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, PassthroughTcpSocketSession>(VpnConstants.PASSTHROUGH_TCP_SOCKET_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, PassthroughTcpSocketSession>
    private val passthroughUdpSessionCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, PassthroughUdpSession>(VpnConstants.PASSTHROUGH_UDP_SESSION_CACHE_MAX_SIZE, 0.75f, true)) as MutableMap<String, PassthroughUdpSession>
    private val localProxyTargetAppCache = ConcurrentHashMap<String, Boolean>(VpnConstants.LOCAL_PROXY_TARGET_APP_CACHE_MAX_SIZE)
    @Volatile private var tunDebugWindowStartedAt = 0L
    @Volatile private var tunDebugPacketsInWindow = 0
    @Volatile private var tunDebugBytesInWindow = 0L
    @Volatile private var tunStormDisabledUntil = 0L
    private var packetWakelock: PowerManager.WakeLock? = null
    @Volatile private var lastPacketTimestamp = 0L
    
    // HTTPS 增强：SSL Pinning 绕过
    private lateinit var sslPinningBypasser: SslPinningBypasser
    private val upstreamServerStates = linkedMapOf<String, DnsRuntimeSupport.UpstreamServerState>()
    private val dnsServerCacheLock = Any()
    private val tunPacketWriter = TunPacketWriter()
    // DNS socket 连接池 - 复用 socket 避免每次创建开销
    private val dnsSocketPool = ConcurrentLinkedQueue<Pair<InetAddress, DatagramSocket>>()
    private val dnsSocketPoolLock = Any()
    private val localDnsV4 = VpnConstants.LOCAL_DNS_V4
    private val localDnsV6 = VpnConstants.LOCAL_DNS_V6
    private val staleCacheGraceMillis = VpnConstants.STALE_CACHE_GRACE_MILLIS
    private val dnsServerCacheTtlMillis = VpnConstants.DNS_SERVER_CACHE_TTL_MILLIS
    private val routeCachePruneIntervalMillis = VpnConstants.ROUTE_CACHE_PRUNE_INTERVAL_MILLIS
    private var lastHttpRouteReloadAt = 0L
    @Volatile private var lastHttpDecryptPruneAt = 0L
    @Volatile private var lastHttpsDecryptPruneAt = 0L
    @Volatile private var lastQuicRoutePruneAt = 0L
    @Volatile private var lastRouteCachePruneCheckAt = 0L
    @Volatile private var lastUnderlyingNetworkRefreshAt = 0L
    @Volatile private var tunOutputStream: FileOutputStream? = null

    // DNS Async Worker — 将阻塞的 DNS 上游查询从主线程剥离
    private val dnsTaskIn = LinkedBlockingQueue<DnsAsyncTask>(VpnConstants.DNS_ASYNC_TASK_QUEUE_CAPACITY)
    private val dnsResultOut = LinkedBlockingQueue<DnsAsyncResult>(VpnConstants.DNS_ASYNC_RESULT_QUEUE_CAPACITY)
    @Volatile private var dnsWorkerActive = false
    private var dnsWorkerThread: Thread? = null

    private fun startDnsWorker(): Thread {
        val thread = Thread({
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            while (dnsWorkerActive && isRunning) {
                try {
                    val task = dnsTaskIn.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (task != null && task.generation == activeTunGeneration) {
                        val result = processDnsTaskAsync(task)
                        if (result != null) {
                            dnsResultOut.offer(result)
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (error: Exception) {
                    LogRepository.append(this@AdBlockVpnService,
                        "DnsWorker error: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }, "DnsWorker").apply { isDaemon = true }
        dnsWorkerThread = thread
        return thread
    }

    private data class DnsAsyncTask(
        val generation: Long,
        val payload: ByteArray,
        val info: com.HanFeng.model.PacketInfo,
        val question: DnsQuestion
    )

    private data class DnsAsyncResult(
        val generation: Long,
        val info: com.HanFeng.model.PacketInfo,
        val question: DnsQuestion,
        val domainContext: DomainDecisionContext,
        val upstreamResult: UpstreamDnsSupport.UpstreamDnsResult?,
        val staleResponse: ByteArray?,
        val failureResponse: ByteArray?,
        val protectedQuestion: Boolean,
        val shouldUseActiveMitmRouting: Boolean
    )
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
    private val forcedDnsRouteHosts by lazy(LazyThreadSafetyMode.NONE) {
        upstreamFallbackDnsHosts.toSet()
    }
    @Volatile private var cachedDnsServers: DnsRuntimeSupport.CachedDnsServers? = null
    @Volatile private var httpDecryptEnabled = false
    @Volatile private var forceEncryptedDnsFallbackEnabled = true
    @Volatile private var mitmLearningModeEnabled = false
    @Volatile private var procNetOwnerFallbackEnabled = false
    @Volatile private var mitmCertificateInstalled = false
    @Volatile private var shizukuConnectionOwnerReady = false
    @Volatile private var shizukuAdControlReady = false
    @Volatile private var shizukuStrictAppAdBlockEnabled = false
    @Volatile private var lastShizukuConnectionOwnerRetryAt = 0L
    @Volatile private var lastShizukuAdControlRetryAt = 0L
    @Volatile private var localProxyCoexistConfig = LocalProxyCoexistConfig()
    @Volatile private var localProxyTargetPackages: Set<String> = emptySet()
    @Volatile private var mitmAppFullCaptureTargetPackages: Set<String> = emptySet()
    @Volatile private var localProxyReachable: Boolean? = null
    @Volatile private var lightweightPassThroughMode = false
    @Volatile private var activeFullCaptureMode = FullCaptureRoutingSupport.Mode.NONE
    private val passthroughHealthLock = Any()
    @Volatile private var mitmFullCaptureDisabledUntil = 0L
    private var passthroughHealthWindowStartedAt = 0L
    private var passthroughHealthAttempts = 0
    private var passthroughHealthFailures = 0
    @Volatile private var networkCallbackRegistered = false
    @Volatile private var lastForegroundNotificationRefreshAt = 0L
    @Volatile private var lastAppliedUnderlyingNetworkSummary: String? = null
    private var connectivityNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val handledDnsHosts by lazy(LazyThreadSafetyMode.NONE) {
        setOf(localDnsV4, localDnsV6).mapNotNull { host ->
            runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        }.toSet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val userInitiated = intent?.getBooleanExtra(EXTRA_USER_INITIATED, false) == true
        if (action == ACTION_REPORT_AD) {
            handleUserAdFeedbackAction()
            return START_STICKY
        }
        if (action == ACTION_STOP) {
            FeatureSettingsRepository.setAdBlockEnabled(this, false)
            FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
            pendingReacquireJob?.cancel()
            pendingReacquireJob = null
            stopVpn()
            return START_NOT_STICKY
        }
        if (!hasVpnPermissionReady()) {
            LogRepository.append(this, "VPN start skipped: system VPN permission not ready")
            FeatureSettingsRepository.setAdBlockEnabled(this, false)
            FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
            isRunning = false
            notifyRuntimeStatusChanged()
            stopVpn(stopService = false)
            stopSelf()
            return START_NOT_STICKY
        }
        val shouldStaySticky = when (action) {
            ACTION_RELOAD -> {
                if (!FeatureSettingsRepository.isAdBlockEnabled(this)) {
                    LogRepository.append(this, "VPN reload skipped: ad block disabled by user")
                    stopSelf()
                    return START_NOT_STICKY
                }
                scheduleVpnReload()
                true
            }
            ACTION_START -> {
                showPendingForegroundNotification()
                if (vpnInterface == null || packetJob?.isActive != true) {
                    launchVpnStart(userInitiated = userInitiated)
                } else {
                    LogRepository.append(this, "VPN start skipped: already running")
                }
                true
            }
            else -> {
                if (!FeatureSettingsRepository.isAdBlockEnabled(this)) {
                    LogRepository.append(this, "VPN start skipped: ad block disabled by user")
                    stopSelf()
                    return START_NOT_STICKY
                }
                showPendingForegroundNotification()
                if (vpnInterface == null || packetJob?.isActive != true) {
                    launchVpnStart()
                } else {
                    LogRepository.append(this, "VPN start skipped: already running")
                }
                true
            }
        }
        return if (shouldStaySticky && (isRunning || isWaitingForReacquire() || startInProgress)) START_STICKY else START_NOT_STICKY
    }

    private fun handleUserAdFeedbackAction() {
        val sample = UserAdFeedbackManager.captureFeedbackSample(this)
        if (sample == null) {
            LogRepository.append(this, "User ad feedback action completed without sample")
        } else {
            LogRepository.append(this, "User ad feedback action completed app=${sample.appName} host=${sample.host ?: sample.sni ?: "none"} protocol=${sample.protocol}")
        }
        refreshForegroundNotification()
    }

    private fun hasVpnPermissionReady(): Boolean {
        return runCatching { VpnService.prepare(this) }
            .getOrNull() == null
    }

    override fun onDestroy() {
        unregisterNetworkMonitoring()
        StatsRepository.flushNow(this)
        LogRepository.flushAndClose()
        if (FeatureSettingsRepository.isAdBlockEnabled(this) && (isRunning || isWaitingForReacquire())) {
            LogRepository.append(this, "VPN service destroyed while interception should stay enabled")
        }
        stopVpn(stopService = false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isRunning && !isWaitingForReacquire()) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        LogRepository.append(this, "VPN revoked by system or replaced by another VPN")
        FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, true)
        AdBlockVpnService.isRunning = false
        notifyRuntimeStatusChanged()
        stopVpn(stopService = false, keepForeground = true)
        scheduleVpnReacquireAfterRevoke()
        super.onRevoke()
    }

    private fun startVpn(userInitiated: Boolean = false, preserveUserIntentOnFailure: Boolean = false) {
        val startAt = System.currentTimeMillis()
        val fastStart = userInitiated
        refreshRuntimeFeatureFlags(fastStart)
        val flagsReadyAt = System.currentTimeMillis()
        if (vpnInterface != null && packetJob?.isActive == true) {
            handleAlreadyRunningVpnStart()
            return
        }
        if (!startForegroundIfNeeded()) return
        val foregroundReadyAt = System.currentTimeMillis()
        vpnInterface = runCatching { buildInterface(fastStart) }
            .onFailure { error ->
                LogRepository.append(this, "VPN establish failed: ${error.message ?: error.javaClass.simpleName}")
            }
            .getOrNull()
        val interfaceReadyAt = System.currentTimeMillis()
        if (vpnInterface == null && handleVpnEstablishFailure(preserveUserIntentOnFailure)) return
        isRunning = true
        notifyRuntimeStatusChanged()
        startInProgress = false
        FeatureSettingsRepository.setAdBlockEnabled(this, true)
        FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
        finishPendingReacquireJob()
        activeTunGeneration += 1L
        val tunGeneration = activeTunGeneration
        if (httpDecryptEnabled && mitmCertificateInstalled) {
            HttpsMitmRepository.clearRuntimeState(this)
        }
        packetJob = scope.launch {
            runCatching { runPacketLoop(tunGeneration) }
                .onFailure { error ->
                    handlePacketLoopFailure(tunGeneration, error)
                }
        }
        LogRepository.append(
            this,
            "VPN start timing flags=${flagsReadyAt - startAt}ms foreground=${foregroundReadyAt - flagsReadyAt}ms establish=${interfaceReadyAt - foregroundReadyAt}ms total=${System.currentTimeMillis() - startAt}ms"
        )
        pendingDeferredSetupJob = scope.launch { completeVpnSetupDeferred(userInitiated, fastStart) }
    }

    private suspend fun completeVpnSetupDeferred(userInitiated: Boolean, fastStart: Boolean) {
        if (!isRunning) return
        runCatching {
            if (fastStart) refreshFullRuntimeAfterFastStart()
            registerNetworkMonitoringIfNeeded()
            invalidateDnsServerCache()
            lastAppliedUnderlyingNetworkSummary = null
            applyUnderlyingNetworks()
            probeLocalProxyCoexistAsync()
            refreshForegroundNotification()
            LogRepository.append(
                this@AdBlockVpnService,
                "VPN runtime ready routeMode=$activeFullCaptureMode dns=$localDnsV4,$localDnsV6 httpDecrypt=$httpDecryptEnabled cert=$mitmCertificateInstalled localProxyIgnored=${localProxyCoexistConfig.enabled}"
            )
            VpnHealthChecker.onVpnReady(
                context = this@AdBlockVpnService,
                scope = scope,
                httpDecryptEnabled = httpDecryptEnabled,
                mitmReady = mitmCertificateInstalled,
                shizukuReady = shizukuConnectionOwnerReady || shizukuAdControlReady,
                ruleCount = RuleRepository.getRuleCount(this@AdBlockVpnService),
                whitelistCount = WhitelistRepository.getPackages(this@AdBlockVpnService).size,
                dynamicDecryptRouteCount = HttpsDecryptRouteRepository.getRoutes(this@AdBlockVpnService).size
            )
        }.onFailure {
            LogRepository.append(this@AdBlockVpnService, "VPN deferred setup failed: ${it.message ?: it.javaClass.simpleName}")
        }
        logAndWarmAfterVpnStart(userInitiated)
    }

    private fun refreshFullRuntimeAfterFastStart() {
        val before = runtimeRouteFingerprint()
        refreshRuntimeFeatureFlags(fastStart = false)
        val after = runtimeRouteFingerprint()
        if (before != after && FeatureSettingsRepository.isAdBlockEnabled(this)) {
            scheduleVpnReload(delayMillis = 350L)
            LogRepository.append(this, "Scheduled precise VPN reload after fast start")
        } else {
            LogRepository.append(this, "Fast VPN start runtime refresh completed without reload")
        }
    }

    private fun runtimeRouteFingerprint(): Int {
        var result = 17
        result = 31 * result + httpDecryptEnabled.hashCode()
        result = 31 * result + mitmCertificateInstalled.hashCode()
        result = 31 * result + shizukuAdControlReady.hashCode()
        result = 31 * result + shizukuConnectionOwnerReady.hashCode()
        result = 31 * result + localProxyCoexistConfig.hashCode()
        result = 31 * result + localProxyTargetPackages.hashCode()
        result = 31 * result + lightweightPassThroughMode.hashCode()
        return result
    }

    private fun handleAlreadyRunningVpnStart() {
        isRunning = true
        notifyRuntimeStatusChanged()
        startInProgress = false
        LogRepository.append(this, "VPN start called while already running")
    }

    private fun startForegroundIfNeeded(): Boolean {
        if (foregroundShown) return true
        return runCatching {
            createChannel()
            startForeground(NOTIFICATION_ID, buildPendingNotification())
            foregroundShown = true
        }.onFailure { error ->
            isRunning = false
            notifyRuntimeStatusChanged()
            startInProgress = false
            LogRepository.append(this, "VPN foreground start failed: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
        }.isSuccess
    }

    private fun handleVpnEstablishFailure(preserveUserIntentOnFailure: Boolean): Boolean {
        isRunning = false
        notifyRuntimeStatusChanged()
        startInProgress = false
        if (preserveUserIntentOnFailure) {
            FeatureSettingsRepository.setAdBlockEnabled(this, true)
            FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, true)
            scheduleVpnReacquireAfterRevoke()
            refreshForegroundNotification()
            LogRepository.append(this, "VPN establish deferred: keep waiting for external VPN release")
            return true
        }
        FeatureSettingsRepository.setAdBlockEnabled(this, false)
        FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
        finishPendingReacquireJob()
        clearRuntimeState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundShown = false
        stopSelf()
        return true
    }

    private fun applyRuntimeSettingsFlags() {
        mitmLearningModeEnabled = FeatureSettingsRepository.isMitmLearningModeEnabled(this)
        httpDecryptEnabled = FeatureSettingsRepository.isHttpDecryptEnabled(this)
    }

    private fun handlePacketLoopFailure(tunGeneration: Long, error: Throwable) {
        if (tunGeneration != activeTunGeneration || !isRunning) {
            LogRepository.append(
                this,
                "Ignore stale VPN loop shutdown generation=$tunGeneration active=$activeTunGeneration reason=${error.message ?: error.javaClass.simpleName}"
            )
            return
        }
        LogRepository.append(this, "VPN loop crashed: ${error.message ?: error.javaClass.simpleName}")
        FeatureSettingsRepository.setAdBlockEnabled(this, false)
        FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
        finishPendingReacquireJob()
        stopVpn(stopService = true, keepForeground = false)
    }

    private fun logAndWarmAfterVpnStart(userInitiated: Boolean) {
        LogRepository.append(this, if (userInitiated) "VPN started from user action" else "VPN started from background restore")
        HttpsMitmController.onVpnStarted(this)
        LogRepository.append(this, "VPN started")
        scope.launch {
            runCatching { RuleRepository.prewarmCaches(this@AdBlockVpnService) }
                .onSuccess {
                    LogRepository.append(this@AdBlockVpnService, "Rules cache warmed after VPN start")
                }
                .onFailure {
                    LogRepository.append(this@AdBlockVpnService, "Rules cache warm failed after VPN start: ${it.message ?: it.javaClass.simpleName}")
                }
        }
        LogRepository.append(
            this,
            "Shizuku warm skipped on VPN start connectionOwnerReady=$shizukuConnectionOwnerReady adControlReady=$shizukuAdControlReady"
        )
    }

    private fun reloadVpn() {
        clearPendingVpnReloadJob()
        refreshRuntimeFeatureFlags()
        LogRepository.append(this, "VPN reload requested")
        if (!performSeamlessReload()) {
            stopVpn(stopService = false)
            startVpn(preserveUserIntentOnFailure = isWaitingForReacquire())
        }
    }

    private fun scheduleVpnReload(delayMillis: Long = 220L) {
        pendingVpnReloadJob?.cancel()
        pendingVpnReloadJob = scope.launch {
            delay(delayMillis)
            if (!FeatureSettingsRepository.isAdBlockEnabled(this@AdBlockVpnService)) return@launch
            reloadVpn()
        }
    }

    private fun performSeamlessReload(): Boolean {
        val previousInterface = vpnInterface ?: return false
        val previousPacketJob = packetJob
        val previousTunOutput = tunOutputStream
        refreshRuntimeFeatureFlags()
        val nextInterface = runCatching { buildInterface() }
            .onFailure { error ->
                LogRepository.append(this, "VPN seamless reload establish failed: ${error.message ?: error.javaClass.simpleName}")
            }
            .getOrNull() ?: return false
        activeTunGeneration += 1L
        val tunGeneration = activeTunGeneration
        vpnInterface = nextInterface
        packetJob = scope.launch {
            runCatching { runPacketLoop(tunGeneration) }
                .onFailure { error ->
                    handleSeamlessReloadPacketLoopFailure(tunGeneration, error)
                }
        }
        previousPacketJob?.cancel()
        closePreviousSeamlessReloadResources(previousTunOutput, previousInterface)
        scope.launch {
            registerNetworkMonitoringIfNeeded()
            invalidateDnsServerCache()
            lastAppliedUnderlyingNetworkSummary = null
            applyUnderlyingNetworks()
            refreshForegroundNotification()
            HttpsMitmController.onVpnStarted(this@AdBlockVpnService)
            probeLocalProxyCoexistAsync()
            LogRepository.append(this@AdBlockVpnService, "VPN seamlessly reloaded")
        }
        return true
    }

    private fun handleSeamlessReloadPacketLoopFailure(tunGeneration: Long, error: Throwable) {
        if (tunGeneration != activeTunGeneration || !isRunning) {
            LogRepository.append(
                this,
                "Ignore stale VPN loop shutdown after reload generation=$tunGeneration active=$activeTunGeneration reason=${error.message ?: error.javaClass.simpleName}"
            )
            return
        }
        LogRepository.append(this, "VPN loop crashed after seamless reload: ${error.message ?: error.javaClass.simpleName}")
        FeatureSettingsRepository.setAdBlockEnabled(this, false)
        FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
        finishPendingReacquireJob()
        stopVpn(stopService = true, keepForeground = false)
    }

    private fun closePreviousSeamlessReloadResources(
        previousTunOutput: FileOutputStream?,
        previousInterface: ParcelFileDescriptor
    ) {
        runCatching { previousTunOutput?.close() }
        runCatching { previousInterface.close() }
    }

    private fun stopVpn(stopService: Boolean = true, keepForeground: Boolean = false) {
        isRunning = false
        notifyRuntimeStatusChanged()
        startInProgress = false
        cancelPendingRuntimeJobs()
        cancelPacketLoop()
        releasePacketWakelock()
        activeTunGeneration += 1L
        unregisterNetworkMonitoring()
        lastAppliedUnderlyingNetworkSummary = null
        vpnInterface?.close()
        vpnInterface = null
        clearRuntimeState()
        if (keepForeground) {
            refreshForegroundNotification()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundShown = false
        }
        if (stopService) stopSelf()
        LogRepository.append(this, "VPN stopped")
    }

    private fun launchVpnStart(userInitiated: Boolean = false, preserveUserIntentOnFailure: Boolean = false) {
        if (startInProgress) {
            LogRepository.append(this, "VPN start skipped: start already in progress")
            return
        }
        startInProgress = true
        scope.launch {
            runCatching {
                startVpn(userInitiated = userInitiated, preserveUserIntentOnFailure = preserveUserIntentOnFailure)
            }.onFailure { error ->
                handleAsyncVpnStartFailure(error)
            }
        }
    }

    private fun handleAsyncVpnStartFailure(error: Throwable) {
        startInProgress = false
        isRunning = false
        notifyRuntimeStatusChanged()
        LogRepository.append(this, "VPN async start failed: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun notifyRuntimeStatusChanged() {
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED).setPackage(packageName)
        )
    }

    private fun scheduleVpnReacquireAfterRevoke() {
        clearPendingReacquireJob()
        refreshForegroundNotification()
        pendingReacquireJob = scope.launch {
            repeat(REACQUIRE_ATTEMPT_COUNT) { attempt ->
                val delayMillis = when {
                    attempt < 5 -> 1_000L
                    attempt < 20 -> 2_000L
                    else -> 5_000L
                }
                delay(delayMillis)
                if (!FeatureSettingsRepository.isAdBlockEnabled(this@AdBlockVpnService)) {
                    finishPendingReacquireJob()
                    return@launch
                }
                if (!FeatureSettingsRepository.isVpnRevokedByOtherVpn(this@AdBlockVpnService)) {
                    finishPendingReacquireJob()
                    return@launch
                }
                val vpnPermissionReady = runCatching { VpnService.prepare(this@AdBlockVpnService) }.getOrNull() == null
                if (!vpnPermissionReady) return@repeat
                LogRepository.append(this@AdBlockVpnService, "Attempting VPN reacquire after external VPN release #${attempt + 1}")
                startVpn(userInitiated = false, preserveUserIntentOnFailure = true)
                if (isRunning) {
                    schedulePostReacquireRecoveryRefresh()
                    finishPendingReacquireJob()
                    return@launch
                }
            }
            LogRepository.append(this@AdBlockVpnService, "VPN reacquire window expired after external VPN revoke")
            finishPendingReacquireJob()
            refreshForegroundNotification()
        }
    }

    private fun finishPendingReacquireJob() {
        pendingReacquireJob = null
    }

    private fun schedulePostReacquireRecoveryRefresh() {
        clearPendingRouteReloadJob()
        pendingRouteReloadJob = scope.launch { runPostReacquireRecoveryRefresh() }
    }

    private suspend fun runPostReacquireRecoveryRefresh() {
        delay(600)
        if (isRunning) {
            refreshRuntimeFeatureFlags()
            applyUnderlyingNetworks()
            probeLocalProxyCoexistAsync()
            refreshForegroundNotification()
            LogRepository.append(this, "VPN coexist recovery refresh completed after external VPN release")
        }
    }

    private fun isWaitingForReacquire(): Boolean {
        return NetworkRuntimeSettingsStore.isWaitingForReacquire(this, isRunning)
    }

    private fun clearRuntimeState() {
        httpDecryptEnabled = false
        mitmCertificateInstalled = false
        shizukuConnectionOwnerReady = false
        shizukuAdControlReady = false
        lastShizukuConnectionOwnerRetryAt = 0L
        lastShizukuAdControlRetryAt = 0L
        lightweightPassThroughMode = false
        localProxyTargetPackages = emptySet()
        localProxyReachable = null
        clearPendingLocalProxyProbeJob()
        TlsMitmSessionManager.clear(this)
        appNameCache.clear()
        domainAppCache.clear()
        sourcePortAppCache.clear()
        ownerUidCache.clear()
        ownerUidFailureCache.clear()
        localProxyTargetAppCache.clear()
        appLabelCache.clear()
        vendorHintCache.clear()
        synchronized(dnsResponseCache) { dnsResponseCache.clear() }
        synchronized(decisionLogCache) { decisionLogCache.clear() }
        synchronized(adIpTargetCache) { adIpTargetCache.clear() }
        synchronized(httpDecryptIpCache) { httpDecryptIpCache.clear() }
        synchronized(httpsDecryptIpCache) { httpsDecryptIpCache.clear() }
        synchronized(quicRouteCache) { quicRouteCache.clear() }
        synchronized(httpsProxyFlowCache) { httpsProxyFlowCache.clear() }
        FlowCacheSupport.clear(httpsBridgeSocketCache) { it.close() }
        synchronized(localProxyTcpFlowCache) { localProxyTcpFlowCache.clear() }
        FlowCacheSupport.clear(localProxyBridgeSocketCache) { it.close() }
        synchronized(passthroughTcpFlowCache) { passthroughTcpFlowCache.clear() }
        FlowCacheSupport.clear(passthroughTcpSocketCache) { it.close() }
        FlowCacheSupport.clear(passthroughUdpSessionCache) { it.close() }
        upstreamServerStates.clear()
        cachedDnsServers = null
        dnsSocketPool.forEach { (_, socket) -> socket.close() }
        dnsSocketPool.clear()
        lastUnderlyingNetworkRefreshAt = 0L
        lastForegroundNotificationRefreshAt = 0L
    }

    private fun cancelPendingRuntimeJobs() {
        clearPendingVpnReloadJob()
        clearPendingReacquireJob()
        clearPendingRouteReloadJob()
        clearPendingLocalProxyProbeJob()
        clearPendingDeferredSetupJob()
    }

    private fun cancelPacketLoop() {
        packetJob?.cancel()
        packetJob = null
    }

    private fun clearPendingVpnReloadJob() {
        pendingVpnReloadJob?.cancel()
        pendingVpnReloadJob = null
    }

    private fun clearPendingReacquireJob() {
        pendingReacquireJob?.cancel()
        pendingReacquireJob = null
    }

    private fun clearPendingRouteReloadJob() {
        pendingRouteReloadJob?.cancel()
        pendingRouteReloadJob = null
    }

    private fun clearPendingLocalProxyProbeJob() {
        pendingLocalProxyProbeJob?.cancel()
        pendingLocalProxyProbeJob = null
    }

    private fun clearPendingDeferredSetupJob() {
        pendingDeferredSetupJob?.cancel()
        pendingDeferredSetupJob = null
    }

    private fun buildInterface(fastStart: Boolean = false): ParcelFileDescriptor? {
        return runCatching { buildInterfaceInternal(stableMode = false, fastStart = fastStart) }
            .onFailure {
                LogRepository.append(this, "VPN full interface failed, fallback to stable mode: ${it.message ?: it.javaClass.simpleName}")
            }
            .getOrElse {
                buildInterfaceInternal(stableMode = true, fastStart = fastStart)
            }
    }

    private fun buildInterfaceInternal(stableMode: Boolean, fastStart: Boolean): ParcelFileDescriptor? {
        val fullCaptureMode = resolveFullCaptureRouteMode(stableMode)
        activeFullCaptureMode = fullCaptureMode
        val localProxyFullCapture = fullCaptureMode == FullCaptureRoutingSupport.Mode.LOCAL_PROXY
        val mitmAppFullCapture = fullCaptureMode == FullCaptureRoutingSupport.Mode.MITM_APP
        val mitmFullCapture = fullCaptureMode == FullCaptureRoutingSupport.Mode.MITM_GLOBAL
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
            runCatching { builder.allowBypass() }
                .onFailure {
                    LogRepository.append(this, "VPN allowBypass failed: ${it.message ?: it.javaClass.simpleName}")
                }
            builder.allowFamily(OsConstants.AF_INET)
            builder.allowFamily(OsConstants.AF_INET6)
        }

        if (localProxyFullCapture || mitmAppFullCapture || mitmFullCapture) {
            runCatching {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }.onFailure {
                val routeMode = when {
                    localProxyFullCapture -> "local proxy"
                    mitmAppFullCapture -> "MITM app"
                    else -> "MITM"
                }
                LogRepository.append(this, "Enable $routeMode full-capture routes failed: ${it.message ?: it.javaClass.simpleName}")
            }
        } else if (!stableMode && !fastStart) {
            blockedIpNetworks.forEach { network ->
                runCatching {
                    builder.addRoute(network.routeAddress, network.prefixLength)
                }.onFailure {
                    LogRepository.append(this, "Skip blocked route ${network.routeAddress}/${network.prefixLength}: ${it.message ?: it.javaClass.simpleName}")
                }
            }

            loadForcedDnsRoutes().forEach { network ->
                runCatching {
                    builder.addRoute(network.routeAddress, network.prefixLength)
                }.onFailure {
                    LogRepository.append(this, "Skip forced DNS route ${network.routeAddress}/${network.prefixLength}: ${it.message ?: it.javaClass.simpleName}")
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
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true)
        }

        applyVpnApplicationScope(builder, fullCaptureMode, stableMode, fastStart)

        if (localProxyFullCapture) {
            LogRepository.append(this, "VPN established with local proxy full-capture routes")
        } else if (mitmAppFullCapture) {
            LogRepository.append(this, "VPN established with MITM app full-capture routes targetCount=${mitmAppFullCaptureTargetPackages.size}")
        } else if (mitmFullCapture) {
            LogRepository.append(this, "VPN established with global MITM full-capture routes")
        } else if (stableMode) {
            LogRepository.append(this, "VPN established with stable fallback mode")
        } else {
            LogRepository.append(this, "VPN established with targeted routes only")
        }
        return builder.establish()
    }

    private fun applyVpnApplicationScope(
        builder: Builder,
        fullCaptureMode: FullCaptureRoutingSupport.Mode,
        stableMode: Boolean,
        fastStart: Boolean
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (fullCaptureMode == FullCaptureRoutingSupport.Mode.LOCAL_PROXY ||
            fullCaptureMode == FullCaptureRoutingSupport.Mode.MITM_APP
        ) {
            val targetPackages = when (fullCaptureMode) {
                FullCaptureRoutingSupport.Mode.LOCAL_PROXY -> localProxyTargetPackages.sorted()
                FullCaptureRoutingSupport.Mode.MITM_APP -> mitmAppFullCaptureTargetPackages.sorted()
                else -> emptyList()
            }
            var appliedCount = 0
            targetPackages.forEach { packageName ->
                runCatching {
                    builder.addAllowedApplication(packageName)
                    appliedCount++
                }.onFailure {
                    LogRepository.append(this, "Skip ${fullCaptureMode.name.lowercase()} allowed app $packageName: ${it.message ?: it.javaClass.simpleName}")
                }
            }
            if (appliedCount == 0) {
                LogRepository.append(this, "No ${fullCaptureMode.name.lowercase()} target apps applied, falling back to targeted mode")
                return
            }
            logDecisionOnce(
                key = "vpn-allowed-${fullCaptureMode.name.lowercase()}-apps:$appliedCount",
                message = "Applied ${fullCaptureMode.name.lowercase()} allowed apps count=$appliedCount targetCount=${targetPackages.size}",
                minIntervalMillis = 15_000L
            )
            return
        }

        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure {
                LogRepository.append(this, "Skip self disallowed app $packageName: ${it.message ?: it.javaClass.simpleName}")
            }
        val disallowedPackages = if (fastStart) {
            WhitelistRepository.getDisallowedPackagesFast(this)
        } else {
            WhitelistRepository.getDisallowedPackages(this)
        }
        disallowedPackages.forEach { packageName ->
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure {
                    LogRepository.append(this, "Skip disallowed app $packageName: ${it.message ?: it.javaClass.simpleName}")
                }
        }
        logDecisionOnce(
            key = "vpn-disallowed-apps:${if (stableMode) "stable" else "full"}",
            message = "Applied disallowed apps count=${disallowedPackages.size} mode=${if (stableMode) "stable" else "full"}",
            minIntervalMillis = 15_000L
        )
    }

    private fun currentUnderlyingNetworks(): Array<Network>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = selectEligibleUnderlyingNetworks(connectivityManager)
        return networks.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun currentLinkProperties(): List<LinkProperties> {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val networks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            selectEligibleUnderlyingNetworks(connectivityManager)
        } else {
            listOfNotNull(connectivityManager.activeNetwork)
        }
        return networks.mapNotNull(connectivityManager::getLinkProperties)
    }

    private fun applyUnderlyingNetworks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val networks = currentUnderlyingNetworks()
        val summary = describeUnderlyingNetworks()
        if (summary == lastAppliedUnderlyingNetworkSummary) return
        lastAppliedUnderlyingNetworkSummary = summary
        runCatching { setUnderlyingNetworks(networks) }
            .onSuccess {
                logDecisionOnce(
                    key = "underlying-network-apply:${summary}:${networks?.size ?: 0}",
                    message = "Applied underlying networks count=${networks?.size ?: 0} summary=$summary",
                    minIntervalMillis = 3_000L
                )
            }
            .onFailure {
                LogRepository.append(this, "Set underlying networks failed: ${it.message ?: it.javaClass.simpleName}")
            }
    }

    @Suppress("DEPRECATION")
    private fun selectEligibleUnderlyingNetworks(connectivityManager: ConnectivityManager): List<Network> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return listOfNotNull(connectivityManager.activeNetwork)
        }
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return@mapNotNull null
            }
            UnderlyingNetworkCandidate(
                network = network,
                capabilities = capabilities
            )
        }
        val validated = candidates.filter { it.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        val selected = if (validated.isNotEmpty()) validated else candidates
        return selected
            .sortedWith(compareByDescending<UnderlyingNetworkCandidate> { underlyingNetworkPriority(it.capabilities) }
                .thenBy { it.network.hashCode() })
            .take(1)
            .map { it.network }
    }

    private fun underlyingNetworkPriority(capabilities: NetworkCapabilities): Int {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 4
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 1
            else -> 0
        }
    }

    @Suppress("DEPRECATION")
    private fun describeUnderlyingNetworks(): String {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return "none"
        val networks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.allNetworks.toList()
        } else {
            listOfNotNull(connectivityManager.activeNetwork)
        }
        val entries = networks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return@mapNotNull null
            }
            val transports = buildList {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            }.ifEmpty { listOf("other") }
            val dnsServers = connectivityManager.getLinkProperties(network)
                ?.dnsServers
                .orEmpty()
                .mapNotNull { it.hostAddress }
                .distinct()
                .joinToString("|")
                .ifBlank { "none" }
            "${transports.joinToString("+")}:dns=$dnsServers"
        }
        return entries.distinct().joinToString(", ").ifBlank { "none" }
    }

    private fun registerNetworkMonitoringIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (networkCallbackRegistered) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isEligibleUnderlyingNetwork(connectivityManager, network)) return
                handleUnderlyingNetworkChanged()
            }

            override fun onLost(network: Network) {
                handleUnderlyingNetworkChanged()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!isEligibleUnderlyingNetwork(networkCapabilities)) return
                handleUnderlyingNetworkChanged()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (!isEligibleUnderlyingNetwork(connectivityManager, network)) return
                handleUnderlyingNetworkChanged()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            connectivityNetworkCallback = callback
            networkCallbackRegistered = true
        }.onFailure {
            LogRepository.append(this, "Register network callback failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun unregisterNetworkMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = connectivityNetworkCallback ?: return
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
        connectivityNetworkCallback = null
        networkCallbackRegistered = false
    }

    private fun handleUnderlyingNetworkChanged() {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        if (now - lastUnderlyingNetworkRefreshAt < UNDERLYING_NETWORK_REFRESH_MIN_INTERVAL_MILLIS) {
            logDecisionOnce(
                key = "underlying-network-changed-throttled",
                message = "Skipped underlying network refresh due to throttle summary=${describeUnderlyingNetworks()}",
                minIntervalMillis = UNDERLYING_NETWORK_REFRESH_MIN_INTERVAL_MILLIS
            )
            return
        }
        lastUnderlyingNetworkRefreshAt = now
        invalidateNetworkDependentCaches(reason = "connectivity-change")
        applyUnderlyingNetworks()
        probeLocalProxyCoexistAsync()
        logDecisionOnce(
            key = "underlying-network-changed",
            message = "Underlying networks updated after connectivity change summary=${describeUnderlyingNetworks()}",
            minIntervalMillis = 5_000L
        )
    }

    private fun isEligibleUnderlyingNetwork(connectivityManager: ConnectivityManager, network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isEligibleUnderlyingNetwork(capabilities)
    }

    private fun isEligibleUnderlyingNetwork(capabilities: NetworkCapabilities): Boolean {
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun invalidateDnsServerCache() {
        synchronized(dnsServerCacheLock) {
            cachedDnsServers = null
        }
    }

    private fun invalidateNetworkDependentCaches(reason: String) {
        invalidateDnsServerCache()
        synchronized(adIpTargetCache) {
            adIpTargetCache.clear()
        }
        synchronized(httpDecryptIpCache) {
            httpDecryptIpCache.clear()
        }
        synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache.clear()
        }
        synchronized(quicRouteCache) {
            quicRouteCache.clear()
        }
        lastHttpDecryptPruneAt = 0L
        lastHttpsDecryptPruneAt = 0L
        lastQuicRoutePruneAt = 0L
        lastRouteCachePruneCheckAt = 0L
        logDecisionOnce(
            key = "invalidate-network-dependent-caches:$reason",
            message = "Cleared network-dependent route caches reason=$reason",
            minIntervalMillis = 5_000L
        )
    }

    private suspend fun runPacketLoop(tunGeneration: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        dnsWorkerActive = true
        dnsTaskIn.clear()
        dnsResultOut.clear()
        startDnsWorker().start()
        val descriptor = vpnInterface ?: return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakelockTag = "HanFeng:packet_loop"
        FileInputStream(descriptor.fileDescriptor).use { input ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                if (tunGeneration == activeTunGeneration) {
                    tunOutputStream = output
                }
                val buffer = ByteArray(32767)
                var idleMs = 0L
                while (scope.isActive && isRunning) {
                    val length = input.read(buffer)
                    val now = System.currentTimeMillis()
                    if (length <= 0) {
                        drainDnsAsyncResults(output, tunGeneration)
                        idleMs += 20L
                        if (idleMs >= 3000L && packetWakelock?.isHeld == true) {
                            releasePacketWakelock()
                        }
                        delay(20L)
                        continue
                    }
                    idleMs = 0L
                    lastPacketTimestamp = now
                    acquirePacketWakelock(powerManager, wakelockTag, tunGeneration)
                    if (!recordTunPacketForDiagnostics(buffer, length, tunGeneration)) {
                        delay(TUN_STORM_BACKOFF_MILLIS)
                        continue
                    }
                    runCatching {
                        handlePacket(buffer, length, output)
                        drainDnsAsyncResults(output, tunGeneration)
                    }.onFailure { error ->
                        LogRepository.append(this, "Packet handling failed: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
                if (tunGeneration == activeTunGeneration) {
                    tunOutputStream = null
                }
            }
        }
        dnsWorkerActive = false
        dnsWorkerThread?.interrupt()
        dnsTaskIn.clear()
        dnsResultOut.clear()
        releasePacketWakelock()
    }

    private fun acquirePacketWakelock(powerManager: PowerManager, tag: String, tunGeneration: Long) {
        val wl = packetWakelock
        if (wl?.isHeld == true) return
        if (tunGeneration != activeTunGeneration) return
        runCatching {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
                setReferenceCounted(false)
                acquire(5000L)
            }
        }.onSuccess { acquired ->
            packetWakelock = acquired
        }.onFailure { error ->
            packetWakelock = null
            logDecisionOnce(
                key = "packet-wakelock-unavailable",
                message = "Packet wakelock unavailable: ${error.message ?: error.javaClass.simpleName}",
                minIntervalMillis = 60_000L
            )
        }
    }

    private fun releasePacketWakelock() {
        val wl = packetWakelock
        if (wl?.isHeld == true) {
            runCatching { wl.release() }.onFailure { error ->
                logDecisionOnce(
                    key = "packet-wakelock-release-failed",
                    message = "Packet wakelock release failed: ${error.message ?: error.javaClass.simpleName}",
                    minIntervalMillis = 60_000L
                )
            }
        }
        packetWakelock = null
    }

    private fun recordTunPacketForDiagnostics(packet: ByteArray, length: Int, tunGeneration: Long): Boolean {
        val now = System.currentTimeMillis()
        if (tunStormDisabledUntil > now) return false
        if (tunDebugWindowStartedAt == 0L || now - tunDebugWindowStartedAt >= TUN_DEBUG_WINDOW_MILLIS) {
            logTunWindowSummary(tunDebugPacketsInWindow, tunDebugBytesInWindow, packet, length)
            tunDebugWindowStartedAt = now
            tunDebugPacketsInWindow = 0
            tunDebugBytesInWindow = 0L
        }
        tunDebugPacketsInWindow += 1
        tunDebugBytesInWindow += length.toLong()
        if (tunDebugPacketsInWindow <= TUN_STORM_PACKET_THRESHOLD_PER_SECOND) return true

        tunStormDisabledUntil = now + TUN_STORM_COOLDOWN_MILLIS
        LogRepository.append(
            this,
            "TUN storm guard tripped packetsPerSecond=$tunDebugPacketsInWindow bytesPerSecond=$tunDebugBytesInWindow sample=${formatTunDebugSample(packet, length)}"
        )
        tripMitmFullCaptureCircuit("tun packet storm packetsPerSecond=$tunDebugPacketsInWindow")
        if (tunGeneration == activeTunGeneration && FeatureSettingsRepository.isAdBlockEnabled(this)) {
            scheduleVpnReload(delayMillis = TUN_STORM_RELOAD_DELAY_MILLIS)
        }
        return false
    }

    private fun logTunWindowSummary(packetCount: Int, byteCount: Long, packet: ByteArray, length: Int) {
        if (packetCount <= 0) return
        val sample = formatTunDebugSample(packet, length)
        val highRate = packetCount >= TUN_DEBUG_HIGH_RATE_PACKET_THRESHOLD
        if (highRate) {
            LogRepository.append(this, "TUN debug rate packetsPerSecond=$packetCount bytesPerSecond=$byteCount sample=$sample")
        } else {
            logDecisionOnce(
                key = "tun-debug-rate",
                message = "TUN debug rate packetsPerSecond=$packetCount bytesPerSecond=$byteCount sample=$sample",
                minIntervalMillis = 30_000L
            )
        }
    }

    private fun formatTunDebugSample(packet: ByteArray, length: Int): String {
        val info = PacketCodec.parse(packet, length)
        if (info == null) return "invalid length=$length"
        val protocol = when (info.protocol) {
            OsConstants.IPPROTO_TCP -> "tcp"
            OsConstants.IPPROTO_UDP -> "udp"
            else -> info.protocol.toString()
        }
        return "protocol=$protocol src=${formatAddress(info.sourceAddress)}:${info.sourcePort} dst=${formatAddress(info.destinationAddress)}:${info.destinationPort} length=$length payload=${info.payload.size}"
    }

    private fun handlePacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        val info = PacketCodec.parse(packet, length) ?: return
        VpnHealthChecker.onTunPacket(this)
        val isUdp = info.protocol == OsConstants.IPPROTO_UDP
        val isTcp = info.protocol == OsConstants.IPPROTO_TCP
        if (shouldBypassPacketHandling(info, isUdp)) return
        if (handleBlockedPacketTargets(info)) return
        if (handleDnsPacket(info, output, isUdp)) return
        if (handleLocalProxyPacket(info, isTcp, isUdp)) return
        if (handleHttpDecryptPacket(info, output, isTcp, isUdp)) return
        handlePassthroughPacket(info, isTcp, isUdp)
    }

    private fun shouldBypassPacketHandling(
        info: com.HanFeng.model.PacketInfo,
        isUdp: Boolean
    ): Boolean {
        return lightweightPassThroughMode && !(isUdp && info.destinationPort == 53)
    }

    private fun handleBlockedPacketTargets(info: com.HanFeng.model.PacketInfo): Boolean {
        findBlockedIpNetwork(info.destinationAddress)?.let { return true }
        findMatchingIpRule(info)?.let { match ->
            StatsRepository.recordBlockedHttp(this, match.rule.vendor, match.appName, 32 * 1024)
            logDecisionOnce(
                key = "ip-cidr-block:${match.rule.id}:${formatAddress(info.destinationAddress)}:${info.destinationPort}",
                message = "Blocked IP-CIDR flow ip=${formatAddress(info.destinationAddress)} port=${info.destinationPort} app=${match.appName} vendor=${match.rule.vendor} cidr=${match.rule.ipCidr ?: "unknown"}",
                minIntervalMillis = 30_000L
            )
            return true
        }
        findMatchingPortOnlyRule(info)?.let { match ->
            StatsRepository.recordBlockedHttp(this, match.rule.vendor, match.appName, 32 * 1024)
            logDecisionOnce(
                key = "port-only-block:${match.rule.id}:${formatAddress(info.destinationAddress)}:${info.destinationPort}:${info.sourcePort}",
                message = "Blocked port-only flow ip=${formatAddress(info.destinationAddress)} port=${info.destinationPort} sourcePort=${info.sourcePort} app=${match.appName} vendor=${match.rule.vendor}",
                minIntervalMillis = 30_000L
            )
            return true
        }
        findAdIpTarget(info)?.let { target ->
            StatsRepository.recordBlockedHttp(this, target.vendor, target.appName, 32 * 1024)
            RuleRepository.reportUnknownVendorIfNeeded(
                context = this,
                vendor = target.vendor,
                domain = target.domain,
                appName = target.appName,
                signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
                confidenceBoost = 2,
                matchedPathHint = "direct-ip:${formatAddress(info.destinationAddress)}:${info.destinationPort}"
            )
            logDecisionOnce(
                key = "ad-ip-block:${target.domain}:${formatAddress(info.destinationAddress)}:${info.destinationPort}",
                message = "Blocked ad IP flow ip=${formatAddress(info.destinationAddress)} port=${info.destinationPort} domain=${target.domain} app=${target.appName} vendor=${target.vendor} source=${target.source}",
                minIntervalMillis = 30_000L
            )
            return true
        }
        return false
    }

    private fun handleDnsPacket(
        info: com.HanFeng.model.PacketInfo,
        output: FileOutputStream,
        isUdp: Boolean
    ): Boolean {
        if (!(isUdp && info.destinationPort == 53)) return false
        VpnHealthChecker.onDnsQuery(this)
        if (!shouldHandleDns(info.destinationAddress)) {
            logDecisionOnce(
                key = "dns-non-local-endpoint:${formatAddress(info.destinationAddress)}",
                message = "Observed DNS query to non-local endpoint ip=${formatAddress(info.destinationAddress)}, fallback to local DNS handler",
                minIntervalMillis = 30_000L
            )
        }
        val question = DnsMessageParser.parseQuestion(info.payload) ?: return true
        if (lightweightPassThroughMode) {
            handlePassThroughDnsQuery(info, question, output)
            return true
        }
        if (RuleRepository.criticalStartupDomains.contains(question.domain)) {
            handleCriticalStartupDnsQuery(info, question, output)
            return true
        }
        // Fast path: 响应缓存命中 → 主线程直接回复，无需阻塞
        val cachedResponse = readCachedDnsResponse(question, info.payload)
        if (cachedResponse != null) {
            output.write(PacketCodec.buildUdpResponse(info, cachedResponse))
            val cachedApp = readCachedAppName(buildCacheKeys(info))
                ?: readCachedDomainApp(normalizeDomain(question.domain))
                ?: "未知应用"
            StatsRepository.recordRequest(this, classifyVendorCached(question.domain, cachedApp), cachedApp)
            return true
        }
        // Slow path: 缓存未命中 → 派发到异步 Worker，主线程继续处理后续包
        dnsTaskIn.offer(DnsAsyncTask(
            generation = activeTunGeneration,
            payload = info.payload.copyOf(),
            info = info.copy(payload = info.payload.copyOf()),
            question = question
        ))
        return true
    }

    // 异步路径用：worker 调用 queryUpstreamDns 等全部阻塞逻辑
    // 主线程消费队列结果写入 TUN，见 drainDnsAsyncResults / handleDnsAsyncResult

    private fun handlePassThroughDnsQuery(
        info: com.HanFeng.model.PacketInfo,
        question: DnsQuestion,
        output: FileOutputStream
    ) {
        StatsRepository.recordRequest(this, "System", "系统")
        readCachedDnsResponse(question, info.payload)?.let { cachedResponse ->
            output.write(PacketCodec.buildUdpResponse(info, cachedResponse))
            return
        }
        val upstreamResponse = queryUpstreamDns(info.payload)?.response
            ?: readStaleCachedDnsResponse(question, info.payload)
            ?: DnsMessageParser.buildServerFailureResponse(info.payload, question)
        cacheDnsResponse(question, upstreamResponse)
        output.write(PacketCodec.buildUdpResponse(info, upstreamResponse))
    }

    private fun handleCriticalStartupDnsQuery(
        info: com.HanFeng.model.PacketInfo,
        question: DnsQuestion,
        output: FileOutputStream
    ) {
        val appName = resolveAppName(question.domain, info)
        StatsRepository.recordRequest(this, "System", appName)
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
    }

    private fun handleDnsRewriteResponse(
        info: com.HanFeng.model.PacketInfo,
        question: DnsQuestion,
        rewriteTarget: String,
        output: FileOutputStream,
        vendor: String,
        appName: String
    ) {
        val rewriteIp = if (looksLikeIpAddress(rewriteTarget)) {
            rewriteTarget
        } else {
            resolveUpstreamIp(rewriteTarget) ?: "0.0.0.0"
        }
        val rewriteResponse = DnsMessageParser.buildRewriteResponse(info.payload, question, rewriteIp)
        if (rewriteResponse != null) {
            output.write(PacketCodec.buildUdpResponse(info, rewriteResponse))
            StatsRepository.recordBlockedDns(this, vendor, appName, 512, source = StatsRepository.BlockSource.DNS_RULE)
            logDecisionOnce(
                key = "dns-rewrite:${question.domain}:${question.qType}:${appName}",
                message = "DNS rewrite domain=${question.domain} -> $rewriteTarget ($rewriteIp) qType=${question.qType} app=$appName vendor=$vendor",
                minIntervalMillis = 20_000L
            )
        } else {
            output.write(PacketCodec.buildUdpResponse(info, DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return))
            StatsRepository.recordBlockedDns(this, vendor, appName, 512, source = StatsRepository.BlockSource.DNS_RULE)
        }
    }

    private fun looksLikeIpAddress(value: String): Boolean {
        return runCatching { java.net.InetAddress.getByName(value) }.isSuccess &&
            (value.count { it == '.' } == 3 || value.contains(':'))
    }

    private fun resolveUpstreamIp(domain: String): String? {
        val addresses = runCatching { java.net.InetAddress.getAllByName(domain) }.getOrNull()
        return addresses?.firstOrNull()?.hostAddress
    }

    private fun handleManagedDnsQuery(
        info: com.HanFeng.model.PacketInfo,
        question: DnsQuestion,
        output: FileOutputStream
    ) {
        val domainContext = resolveDomainDecisionContext(
            domain = question.domain,
            info = info,
            qType = question.qType
        )
        val appName = domainContext.appName
        val vendor = domainContext.vendor
        UserAdFeedbackManager.recordNetworkActivity(
            this,
            UserAdFeedbackManager.NetworkActivity(
                appName = appName,
                host = question.domain,
                protocol = "DNS",
                source = "dns_query",
                score = if (RuleRepository.looksLikeAdDomain(question.domain)) 5 else 0
            )
        )
        val protectedQuestion = if (domainContext.matchedRule?.let(RuleRepository::isUserOwnedRule) == true) {
            false
        } else {
            (isProtectedTrafficDomain(question.domain) &&
                !shouldTreatAsGeneralAdTraffic(question.domain, vendor, appName)) ||
                RuleRepository.isWhitelistedDomain(question.domain) ||
                RuleRepository.isSensitiveAuthDomain(question.domain)
        }

        if (domainContext.matchedRule != null && !protectedQuestion) {
            val rewriteTarget = domainContext.matchedRule.dnsrewrite
            if (!rewriteTarget.isNullOrBlank()) {
                handleDnsRewriteResponse(info, question, rewriteTarget, output, vendor, appName)
                return
            }
            output.write(PacketCodec.buildUdpResponse(info, DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return))
            StatsRepository.recordBlockedDns(this, vendor, appName, 512, source = StatsRepository.BlockSource.DNS_RULE)
            logDecisionOnce(
                key = "dns-block:${question.domain}:${question.qType}:${appName}",
                message = "Blocked DNS domain=${question.domain} qType=${question.qType} app=$appName vendor=$vendor reason=${domainContext.reason}",
                minIntervalMillis = 20_000L
            )
            return
        }

        if (!protectedQuestion && shouldTreatAsGeneralAdTraffic(question.domain, vendor, appName)) {
            output.write(PacketCodec.buildUdpResponse(info, DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return))
            StatsRepository.recordBlockedDns(this, vendor, appName, 512, source = StatsRepository.BlockSource.URL_HEURISTIC)
            logDecisionOnce(
                key = "dns-heuristic-block:${question.domain}:${question.qType}:${appName}",
                message = "Blocked DNS by ad heuristic domain=${question.domain} qType=${question.qType} app=$appName vendor=$vendor reason=${domainContext.reason}",
                minIntervalMillis = 20_000L
            )
            return
        }

        readCachedDnsResponse(question, info.payload)?.let { cachedResponse ->
            StatsRepository.recordRequest(this, vendor, appName)
            logDecisionOnce(
                key = "dns-pass-cache:${question.domain}:${question.qType}:${appName}",
                message = "Passed DNS domain=${question.domain} qType=${question.qType} app=$appName vendor=$vendor reason=cache-hit",
                minIntervalMillis = 30_000L
            )
            output.write(PacketCodec.buildUdpResponse(info, cachedResponse))
            return
        }

        val upstreamResult = queryUpstreamDns(info.payload)
        val upstreamResponse = upstreamResult?.response
            ?: readStaleCachedDnsResponse(question, info.payload)
            ?: DnsMessageParser.buildServerFailureResponse(info.payload, question)

        val aliasTargets = DnsMessageParser.extractAliasTargets(upstreamResponse, question)
        val addresses = DnsMessageParser.extractAnswerAddresses(upstreamResponse, question)

        // 先缓存 IP 和目标信息（无论是否拦截）
        if (shouldUseActiveMitmRouting() && addresses.isNotEmpty() && !protectedQuestion) {
            rememberQuicTargets(question, upstreamResponse, appName, vendor)
            rememberHttpDecryptTargets(question, upstreamResponse, appName, vendor)
            rememberHttpsDecryptTargets(question, upstreamResponse, appName, vendor)
            rememberAdIpTargets(question, upstreamResponse, appName, vendor)
            maybeApplyMitmLearningSignals(
                appName = appName,
                domain = question.domain,
                addresses = addresses,
                signalType = MitmLearningEngine.SignalType.DNS_UNKNOWN
            )
        }
        if (shouldUseActiveMitmRouting() && aliasTargets.isNotEmpty() && !protectedQuestion) {
            rememberHttpDecryptAliasTargets(question, aliasTargets, upstreamResponse, appName)
            rememberHttpsDecryptAliasTargets(question, aliasTargets, upstreamResponse, appName)
            rememberQuicAliasTargets(question, aliasTargets, upstreamResponse, appName)
            rememberAdIpTargetsForAliases(question, aliasTargets, upstreamResponse, appName)
        } else if (addresses.isNotEmpty() && !protectedQuestion) {
            rememberAdIpTargets(question, upstreamResponse, appName, vendor)
        } else if (aliasTargets.isNotEmpty() && !protectedQuestion) {
            rememberAdIpTargetsForAliases(question, aliasTargets, upstreamResponse, appName)
        }
        
        // 放行：记录统计和日志
        StatsRepository.recordRequest(this, vendor, appName)
        logDecisionOnce(
            key = "dns-pass:${question.domain}:${question.qType}:${appName}",
            message = "Passed DNS domain=${question.domain} qType=${question.qType} app=$appName vendor=$vendor reason=${domainContext.reason}",
            minIntervalMillis = 30_000L
        )

        // 检查别名目标是否需要拦截
        if (handleBlockedDnsAliasTargets(info, question, appName, vendor, aliasTargets, output)) return
        
        // 写入 DNS 缓存并放行
        cacheDnsResponse(question, upstreamResponse)
        output.write(PacketCodec.buildUdpResponse(info, upstreamResponse))
    }

    // DNS Async Worker — 在后台线程执行 DNS 查询，主线程仅提交任务和消费结果
    private fun processDnsTaskAsync(task: DnsAsyncTask): DnsAsyncResult? {
        val info = task.info
        val question = task.question
        val domainContext = resolveDomainDecisionContext(
            domain = question.domain,
            info = info,
            qType = question.qType
        )
        val appName = domainContext.appName
        val vendor = domainContext.vendor

        val protectedQuestion = if (domainContext.matchedRule?.let(RuleRepository::isUserOwnedRule) == true) {
            false
        } else {
            (isProtectedTrafficDomain(question.domain) &&
                !shouldTreatAsGeneralAdTraffic(question.domain, vendor, appName)) ||
                RuleRepository.isWhitelistedDomain(question.domain) ||
                RuleRepository.isSensitiveAuthDomain(question.domain)
        }

        if (domainContext.matchedRule != null && !protectedQuestion) {
            val rewriteTarget = domainContext.matchedRule.dnsrewrite
            val rewrittenResponse = if (!rewriteTarget.isNullOrBlank()) {
                val rewriteIp = if (looksLikeIpAddress(rewriteTarget)) {
                    rewriteTarget
                } else {
                    resolveUpstreamIp(rewriteTarget) ?: "0.0.0.0"
                }
                DnsMessageParser.buildRewriteResponse(task.payload, question, rewriteIp)
            } else {
                null
            }
            val sinkhole = rewrittenResponse ?: DnsMessageParser.buildSinkholeResponse(task.payload, question)
            if (sinkhole != null) {
                dnsResultOut.offer(DnsAsyncResult(
                    generation = task.generation,
                    info = info,
                    question = question,
                    domainContext = domainContext,
                    upstreamResult = null,
                    staleResponse = sinkhole,
                    failureResponse = null,
                    protectedQuestion = protectedQuestion,
                    shouldUseActiveMitmRouting = false
                ))
            }
            return null
        }

        if (!protectedQuestion && shouldTreatAsGeneralAdTraffic(question.domain, vendor, appName)) {
            val sinkhole = DnsMessageParser.buildSinkholeResponse(task.payload, question)
            if (sinkhole != null) {
                dnsResultOut.offer(DnsAsyncResult(
                    generation = task.generation,
                    info = info,
                    question = question,
                    domainContext = domainContext,
                    upstreamResult = null,
                    staleResponse = sinkhole,
                    failureResponse = null,
                    protectedQuestion = protectedQuestion,
                    shouldUseActiveMitmRouting = false
                ))
            }
            return null
        }

        val staleResp = readStaleCachedDnsResponse(question, task.payload)
        val failureResp = DnsMessageParser.buildServerFailureResponse(task.payload, question)
        val upstreamResult = queryUpstreamDns(task.payload)

        return DnsAsyncResult(
            generation = task.generation,
            info = info,
            question = question,
            domainContext = domainContext,
            upstreamResult = upstreamResult,
            staleResponse = staleResp,
            failureResponse = failureResp,
            protectedQuestion = protectedQuestion,
            shouldUseActiveMitmRouting = shouldUseActiveMitmRouting()
        )
    }

    // Main thread: 消费 DNS 异步结果，写入 TUN 输出
    private fun drainDnsAsyncResults(output: FileOutputStream, tunGeneration: Long) {
        while (true) {
            val result = dnsResultOut.poll() ?: break
            if (result.generation != tunGeneration) continue
            handleDnsAsyncResult(result, output)
        }
    }

    private fun handleDnsAsyncResult(result: DnsAsyncResult, output: FileOutputStream) {
        val info = result.info
        val question = result.question
        val domainContext = result.domainContext
        val appName = domainContext.appName
        val vendor = domainContext.vendor
        val protectedQuestion = result.protectedQuestion

        UserAdFeedbackManager.recordNetworkActivity(
            this,
            UserAdFeedbackManager.NetworkActivity(
                appName = appName,
                host = question.domain,
                protocol = "DNS",
                source = "dns_query",
                score = if (RuleRepository.looksLikeAdDomain(question.domain)) 5 else 0
            )
        )

        // Blocked cases handled inline in processDnsTaskAsync, result was already queued
        if (result.staleResponse != null && result.upstreamResult == null) {
            // Sinkhole — blocked response
            output.write(PacketCodec.buildUdpResponse(info, result.staleResponse))
            StatsRepository.recordBlockedDns(this, vendor, appName, 512, source = StatsRepository.BlockSource.DNS_RULE)
            logDecisionOnce(
                key = "dns-block-async:${question.domain}:${question.qType}:${appName}",
                message = "Blocked DNS (async) domain=${question.domain} qType=${question.qType} app=$appName vendor=$vendor reason=${domainContext.reason}",
                minIntervalMillis = 20_000L
            )
            return
        }

        // Cache hit — handled in dispatch path, shouldn't reach here
        val upstreamResponse = result.upstreamResult?.response
            ?: result.staleResponse
            ?: result.failureResponse
            ?: return

        val aliasTargets = DnsMessageParser.extractAliasTargets(upstreamResponse, question)
        val addresses = DnsMessageParser.extractAnswerAddresses(upstreamResponse, question)

        if (result.shouldUseActiveMitmRouting && addresses.isNotEmpty() && !protectedQuestion) {
            rememberQuicTargets(question, upstreamResponse, appName, vendor)
            rememberHttpDecryptTargets(question, upstreamResponse, appName, vendor)
            rememberHttpsDecryptTargets(question, upstreamResponse, appName, vendor)
            rememberAdIpTargets(question, upstreamResponse, appName, vendor)
            maybeApplyMitmLearningSignals(
                appName = appName,
                domain = question.domain,
                addresses = addresses,
                signalType = MitmLearningEngine.SignalType.DNS_UNKNOWN
            )
        }
        if (result.shouldUseActiveMitmRouting && aliasTargets.isNotEmpty() && !protectedQuestion) {
            rememberHttpDecryptAliasTargets(question, aliasTargets, upstreamResponse, appName)
            rememberHttpsDecryptAliasTargets(question, aliasTargets, upstreamResponse, appName)
            rememberQuicAliasTargets(question, aliasTargets, upstreamResponse, appName)
            rememberAdIpTargetsForAliases(question, aliasTargets, upstreamResponse, appName)
        } else if (addresses.isNotEmpty() && !protectedQuestion) {
            rememberAdIpTargets(question, upstreamResponse, appName, vendor)
        } else if (aliasTargets.isNotEmpty() && !protectedQuestion) {
            rememberAdIpTargetsForAliases(question, aliasTargets, upstreamResponse, appName)
        }

        StatsRepository.recordRequest(this, vendor, appName)
        logDecisionOnce(
            key = "dns-pass-async:${question.domain}:${question.qType}:${appName}",
            message = "Passed DNS (async) domain=${question.domain} qType=${question.qType} app=$appName vendor=$vendor reason=${domainContext.reason}",
            minIntervalMillis = 30_000L
        )

        if (handleBlockedDnsAliasTargets(info, question, appName, vendor, aliasTargets, output)) return

        cacheDnsResponse(question, upstreamResponse)
        output.write(PacketCodec.buildUdpResponse(info, upstreamResponse))
    }

    private fun handleBlockedDnsAliasTargets(
        info: com.HanFeng.model.PacketInfo,
        question: DnsQuestion,
        appName: String,
        vendor: String,
        aliasTargets: List<String>,
        output: FileOutputStream
    ): Boolean {
        val blockedAliasTarget = findBlockedDnsAliasTarget(info, question, appName, aliasTargets)
        if (blockedAliasTarget == null) return false
        output.write(PacketCodec.buildUdpResponse(info, DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return true))
        StatsRepository.recordBlockedDns(this, vendor, appName, 512)
        return true
    }

    private fun findBlockedDnsAliasTarget(
        info: com.HanFeng.model.PacketInfo,
        question: DnsQuestion,
        appName: String,
        aliasTargets: List<String>
    ): String? {
        if (aliasTargets.isEmpty()) return null
        val cnameIndex = RuleRepository.getCnameRuleIndex(this)
        for (aliasTarget in aliasTargets) {
            if (isProtectedTrafficDomain(aliasTarget)) continue
            val cnameHit = cnameIndex[aliasTarget.lowercase()] ?: cnameIndex.entries.firstOrNull { (domain, _) ->
                aliasTarget.endsWith(".$domain") || aliasTarget.equals(domain, ignoreCase = true)
            }?.value
            if (cnameHit != null && !cnameHit.exceptionRule) {
                RuleRepository.reportUnknownVendorIfNeeded(
                    context = this,
                    vendor = cnameHit.vendor,
                    domain = aliasTarget,
                    appName = appName,
                    signal = RuleRepository.SuspiciousSignal.DNS_ALIAS,
                    confidenceBoost = 2,
                    refererDomain = question.domain
                )
                return aliasTarget
            }
        }
        for (aliasTarget in aliasTargets) {
            if (isProtectedTrafficDomain(aliasTarget)) continue
            val aliasContext = resolveDomainDecisionContext(
                domain = aliasTarget,
                info = info,
                qType = question.qType,
                knownAppName = appName
            )
            val matchedAliasRule = aliasContext.matchedRule
            val aliasVendor = aliasContext.vendor
            if (matchedAliasRule != null && shouldTreatAsTrackedAdTarget(
                    domain = aliasTarget,
                    appName = appName,
                    vendor = aliasVendor,
                    matchedRule = matchedAliasRule,
                    includeProtectedNovelUrl = false,
                    includeForceNovelQuic = false
                )) {
                RuleRepository.reportUnknownVendorIfNeeded(
                    context = this,
                    vendor = aliasVendor,
                    domain = aliasTarget,
                    appName = appName,
                    signal = RuleRepository.SuspiciousSignal.DNS_ALIAS,
                    confidenceBoost = 1,
                    refererDomain = question.domain
                )
                return aliasTarget
            }
        }
        return null
    }

    private fun handleLocalProxyPacket(
        info: com.HanFeng.model.PacketInfo,
        isTcp: Boolean,
        isUdp: Boolean
    ): Boolean {
        if (isTcp && shouldRouteViaLocalProxy(info)) {
            observeLocalProxyTcpFlow(info)
            if (handleLocalProxyTcpHandshake(info)) return true
        }
        if (isUdp) {
            observeLocalProxyUdpFlow(info)
        }
        return false
    }

    private fun handleHttpDecryptPacket(
        info: com.HanFeng.model.PacketInfo,
        output: FileOutputStream,
        isTcp: Boolean,
        isUdp: Boolean
    ): Boolean {
        if (httpDecryptEnabled && shouldBlockEncryptedDnsDirectFlow(info)) return true
        if (!httpDecryptEnabled) return false
        if (!shouldUseActiveMitmRouting()) return false
        if (isTcp) {
            if (shouldBlockHttpDecryptConnection(info)) return true
            observeHttpsClientHello(info)
            observeHttpsTransparentProxyFlow(info)
            return handleHttpsProxyHandshake(info, output)
        }
        return isUdp && info.destinationPort == 443 && shouldBlockQuicFlow(info)
    }

    private fun shouldBlockQuicFlow(info: com.HanFeng.model.PacketInfo): Boolean {
        val payload = info.payload
        val payloadLooksLikeQuic = looksLikeQuicPacket(payload)

        // MITM 模式启用时才拦截 QUIC
        if (!httpDecryptEnabled) return false

        val cacheKeys = buildCacheKeys(info)
        val localProxyTarget = belongsToLocalProxyTargetUid(cacheKeys)

        val destinationIp = formatAddress(info.destinationAddress)
        if (forceEncryptedDnsFallbackEnabled && EncryptedDnsBlocker.matchesKnownEncryptedDnsIp(destinationIp)) {
            StatsRepository.recordBlockedHttp(this, "加密DNS", "QUIC-DNS", 8 * 1024, source = StatsRepository.BlockSource.QUIC_BLOCK)
            logDecisionOnce(
                key = "quic-encrypted-dns-ip-block:$destinationIp:${info.sourcePort}",
                message = "Blocked QUIC/HTTP3 flow to known encrypted DNS IP ip=$destinationIp sourcePort=${info.sourcePort} via=quic-encrypted-dns-guard",
                minIntervalMillis = 30_000L
            )
            return true
        }
        maybePruneRouteCaches()
        if (payloadLooksLikeQuic) {
            val quicSni = QuicSniParser.extractQuicSni(payload)
            if (quicSni != null) {
                if (quicSni.echOffered) {
                    StatsRepository.recordBlockedHttp(this, "ECH-QUIC", resolveAppName(destinationIp, info), 8 * 1024, source = StatsRepository.BlockSource.QUIC_BLOCK)
                    logDecisionOnce(
                        key = "quic-ech-block:$destinationIp:${info.sourcePort}",
                        message = "Blocked QUIC flow with ECH ip=$destinationIp sourcePort=${info.sourcePort} to force TCP fallback for SNI inspection",
                        minIntervalMillis = 30_000L
                    )
                    return true
                }
                val quicSniHost = quicSni.sniHost
                if (quicSniHost != null && quicSniHost.isNotBlank()) {
                    val routeEntry = synchronized(quicRouteCache) { quicRouteCache[destinationIp] }
                    if (routeEntry?.domain.isNullOrBlank()) {
                        val newRoute = QuicRouteTarget(
                            domain = quicSniHost,
                            vendor = GENERIC_AD_VENDOR_LABEL,
                            appName = resolveAppName(quicSniHost, info),
                            source = "quic-sni",
                            expiresAt = System.currentTimeMillis() + 10 * 60 * 1000L
                        )
                        synchronized(quicRouteCache) {
                            ExpiringTargetCacheSupport.putAllPrunedLocked(quicRouteCache, listOf(destinationIp to newRoute), 1024)
                        }
                    }
                }
            }
        }

        val route = synchronized(quicRouteCache) {
            quicRouteCache[destinationIp]
        }
        val httpsTarget = synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache[destinationIp]
        }
        if (localProxyTarget) {
            UserAdFeedbackManager.recordNetworkActivity(
                this,
                UserAdFeedbackManager.NetworkActivity(
                    appName = "本地代理目标",
                    ip = destinationIp,
                    protocol = "QUIC",
                    source = "quic_local_proxy_fallback",
                    score = 2
                )
            )
            logDecisionOnce(
                key = "local-proxy-quic-fallback:$destinationIp:${info.sourcePort}",
                message = "Blocked QUIC for local proxy target app ip=$destinationIp sourcePort=${info.sourcePort} to force TCP fallback through single-VPN local proxy bridge",
                minIntervalMillis = 15_000L
            )
            return true
        }
        val domain = httpsTarget?.domain ?: route?.domain
        if (domain == null && isMitmFullCaptureRoutingActive()) {
            val appName = resolveAppName(destinationIp, info)
            val decision = TrafficDecisionEngine.shouldBlockQuicFlow(
                TrafficDecisionEngine.QuicBlockInput(
                    packet = info,
                    payloadLooksLikeQuic = payloadLooksLikeQuic,
                    localProxyTarget = localProxyTarget,
                    domain = null,
                    appName = appName,
                    vendor = GENERIC_AD_VENDOR_LABEL,
                    matchedRule = null,
                    bypassReason = null,
                    httpDecryptEnabled = httpDecryptEnabled,
                    hasHttpsTarget = false,
                    globalMitmFullCapture = true
                )
            )
            if (!decision.blocked) return false
            StatsRepository.recordBlockedHttp(this, GENERIC_AD_VENDOR_LABEL, appName, 64 * 1024, source = StatsRepository.BlockSource.QUIC_BLOCK)
            UserAdFeedbackManager.recordNetworkActivity(
                this,
                UserAdFeedbackManager.NetworkActivity(
                    appName = appName,
                    ip = destinationIp,
                    protocol = "QUIC",
                    source = "quic_global_mitm_block",
                    score = 4
                )
            )
            logDecisionOnce(
                key = "quic-block-global-mitm:$destinationIp:${info.sourcePort}",
                message = "Blocked QUIC/HTTP3 flow ip=$destinationIp app=$appName reason=${decision.reason ?: "global-mitm-force-tcp"} route=global-mitm",
                minIntervalMillis = 30_000L
            )
            return true
        }
        val resolvedDomain = domain ?: return false

        val domainContext = resolveDomainDecisionContext(
            domain = resolvedDomain,
            info = info,
            knownAppName = httpsTarget?.appName?.takeIf { it.isNotBlank() }
                ?: route?.appName?.takeIf { it.isNotBlank() },
            knownVendor = httpsTarget?.vendor?.takeIf { it.isNotBlank() }
                ?: route?.vendor?.takeIf { it.isNotBlank() },
            destinationPort = info.destinationPort,
            sourcePort = info.sourcePort
        )
        val appName = domainContext.appName
        val vendor = domainContext.vendor
        val matchedRule = domainContext.matchedRule
        val bypassReason = HttpsMitmRepository.getActiveBypassReason(this, domain)
        val decision = TrafficDecisionEngine.shouldBlockQuicFlow(
            TrafficDecisionEngine.QuicBlockInput(
                packet = info,
                payloadLooksLikeQuic = payloadLooksLikeQuic,
                localProxyTarget = localProxyTarget,
                domain = resolvedDomain,
                appName = appName,
                vendor = vendor,
                matchedRule = matchedRule,
                bypassReason = bypassReason,
                httpDecryptEnabled = httpDecryptEnabled,
                hasHttpsTarget = httpsTarget != null,
                globalMitmFullCapture = isMitmFullCaptureRoutingActive()
            )
        )
        if (!decision.blocked) return false

        val reason = decision.reason ?: "unknown"
        StatsRepository.recordBlockedHttp(this, vendor, appName, 64 * 1024, source = StatsRepository.BlockSource.QUIC_BLOCK)
        UserAdFeedbackManager.recordNetworkActivity(
            this,
            UserAdFeedbackManager.NetworkActivity(
                appName = appName,
                host = resolvedDomain,
                ip = destinationIp,
                protocol = "QUIC",
                source = "quic_block",
                score = 6
            )
        )
        logDecisionOnce(
            key = "quic-block:$resolvedDomain:$destinationIp",
            message = "Blocked QUIC/HTTP3 flow domain=$resolvedDomain ip=$destinationIp app=$appName vendor=$vendor reason=$reason route=${route?.source ?: httpsTarget?.source ?: "unknown"} bypass=${bypassReason ?: "none"}",
            minIntervalMillis = 30_000L
        )
        maybeApplyMitmLearningSignal(
            MitmLearningEngine.Signal(
                appName = appName,
                domain = resolvedDomain,
                ip = destinationIp,
                type = MitmLearningEngine.SignalType.QUIC_DOWNGRADE
            )
        )
        return true
    }

    private fun handlePassthroughPacket(
        info: com.HanFeng.model.PacketInfo,
        isTcp: Boolean,
        isUdp: Boolean
    ): Boolean {
        if (!isFullCaptureRoutingActive()) return false
        if (isTcp) return handlePassthroughTcpPacket(info)
        if (isUdp) return handlePassthroughUdpPacket(info)
        return false
    }

    private fun handlePassthroughTcpPacket(info: com.HanFeng.model.PacketInfo): Boolean {
        if (info.destinationPort <= 0) return false
        val flowKey = buildCacheKeys(info).flowKey
        if (info.tcpFlags.hasTcpFlag(TCP_FLAG_SYN) && !info.tcpFlags.hasTcpFlag(TCP_FLAG_ACK)) {
            trackPassthroughTcpFlow(flowKey, info)
        }
        return executeCachedSyntheticHandshake(
            info = info,
            flowCache = passthroughTcpFlowCache,
            flowKey = flowKey,
            destinationPort = info.destinationPort,
            buildHandlers = { packetState, activeCurrent ->
                buildPassthroughHandshakeHandlers(
                    flowKey = flowKey,
                    current = activeCurrent,
                    info = info,
                    sequenceNumber = packetState.sequenceNumber,
                    acknowledgementNumber = packetState.acknowledgementNumber,
                    payloadLength = packetState.payloadLength,
                    now = packetState.now
                )
            }
        )
    }

    private fun handlePassthroughUdpPacket(info: com.HanFeng.model.PacketInfo): Boolean {
        if (info.destinationPort <= 0 || info.destinationPort == 53) return false
        val flowKey = buildCacheKeys(info).flowKey
        val session = ensurePassthroughUdpSession(flowKey, info) ?: return true
        scope.launch {
            runCatching {
                session.socket.send(DatagramPacket(info.payload, info.payload.size))
                recordPassthroughSuccess("udp-write")
            }.onFailure { error ->
                recordPassthroughFailure("udp-write", "target=${session.targetIp}:${session.targetPort} error=${error.message ?: error.javaClass.simpleName}")
                closePassthroughUdpSession(flowKey, "Forward passthrough UDP failed target=${session.targetIp}:${session.targetPort} error=${error.message ?: error.javaClass.simpleName}")
            }
        }
        return true
    }

    private fun ensurePassthroughUdpSession(flowKey: String, info: com.HanFeng.model.PacketInfo): PassthroughUdpSession? {
        synchronized(passthroughUdpSessionCache) {
            passthroughUdpSessionCache[flowKey]?.let { existing ->
                passthroughUdpSessionCache[flowKey] = existing.copy(lastSeenAt = System.currentTimeMillis())
                return existing
            }
        }
        val targetIp = formatAddress(info.destinationAddress)
        val socket = runCatching {
            DatagramSocket().apply {
                soTimeout = PASSTHROUGH_UDP_READ_TIMEOUT_MILLIS
                if (!protect(this)) {
                    LogRepository.append(this@AdBlockVpnService, "Protect passthrough UDP socket failed target=$targetIp:${info.destinationPort}")
                    recordPassthroughFailure("udp-protect", "target=$targetIp:${info.destinationPort}")
                    close()
                    throw java.io.IOException("Protect passthrough UDP socket failed")
                }
                connect(InetAddress.getByAddress(info.destinationAddress), info.destinationPort)
            }
        }.getOrElse { error ->
            recordPassthroughFailure("udp-connect", "target=$targetIp:${info.destinationPort} error=${error.message ?: error.javaClass.simpleName}")
            logDecisionOnce(
                key = "passthrough-udp-connect-failed:$targetIp:${info.destinationPort}",
                message = "Connect passthrough UDP failed target=$targetIp:${info.destinationPort} error=${error.message ?: error.javaClass.simpleName}",
                minIntervalMillis = 10_000L
            )
            return null
        }
        recordPassthroughSuccess("udp-connect")
        val session = PassthroughUdpSession(
            flowKey = flowKey,
            requestTemplate = info,
            socket = socket,
            targetIp = targetIp,
            targetPort = info.destinationPort,
            lastSeenAt = System.currentTimeMillis()
        )
        FlowCacheSupport.putPruned(passthroughUdpSessionCache, flowKey, session, 256)
        scope.launch { runPassthroughUdpReader(session) }
        return session
    }

    private fun runPassthroughUdpReader(session: PassthroughUdpSession) {
        val buffer = ByteArray(16 * 1024)
        while (scope.isActive && isRunning) {
            val packet = DatagramPacket(buffer, buffer.size)
            val count = try {
                session.socket.receive(packet)
                packet.length
            } catch (_: java.net.SocketTimeoutException) {
                if (System.currentTimeMillis() - session.lastSeenAt > PASSTHROUGH_UDP_IDLE_TIMEOUT_MILLIS) break
                continue
            } catch (error: Exception) {
                recordPassthroughFailure("udp-read", "target=${session.targetIp}:${session.targetPort} error=${error.message ?: error.javaClass.simpleName}")
                logDecisionOnce(
                    key = "passthrough-udp-read-failed:${session.flowKey}",
                    message = "Read passthrough UDP failed target=${session.targetIp}:${session.targetPort} error=${error.message ?: error.javaClass.simpleName}",
                    minIntervalMillis = 10_000L
                )
                break
            }
            if (count > 0) {
                recordPassthroughSuccess("udp-read")
                writeTunPacket(PacketCodec.buildUdpResponse(session.requestTemplate, packet.data.copyOf(count)))
            }
        }
        closePassthroughUdpSession(session.flowKey, "Closed passthrough UDP session target=${session.targetIp}:${session.targetPort}")
    }

    private fun closePassthroughUdpSession(flowKey: String, message: String) {
        FlowCacheSupport.remove(passthroughUdpSessionCache, flowKey)?.close()
        logDecisionOnce(
            key = buildBridgeCloseLogKey("passthrough-udp", flowKey),
            message = message,
            minIntervalMillis = 5_000L
        )
    }

    private fun observeLocalProxyUdpFlow(info: com.HanFeng.model.PacketInfo) {
        if (info.protocol != OsConstants.IPPROTO_UDP) return
        if (info.destinationPort == 53) return
        val cacheKeys = buildCacheKeys(info)
        val localProxyTarget = localProxyTargetAppCache[cacheKeys.sourcePortKey]
            ?: belongsToLocalProxyTargetUid(cacheKeys)
        if (!localProxyTarget) return
        val destinationIp = formatAddress(info.destinationAddress)
        if ((destinationIp == localProxyCoexistConfig.host || destinationIp == "127.0.0.1" || destinationIp == "::1") && info.destinationPort == localProxyCoexistConfig.port) {
            return
        }
        val flavor = if (info.destinationPort == 443 && looksLikeQuicPacket(info.payload)) {
            "quic"
        } else {
            "plain-udp"
        }
        logDecisionOnce(
            key = "local-proxy-udp-observe:$flavor:$destinationIp:${info.destinationPort}:${info.sourcePort}",
            message = "Observed local proxy target UDP flow flavor=$flavor ip=$destinationIp port=${info.destinationPort} sourcePort=${info.sourcePort} bytes=${info.payload.size} singleVpnLocalProxyCurrentlyTcpOnly=true",
            minIntervalMillis = 20_000L
        )
    }

    private fun shouldRouteViaLocalProxy(info: com.HanFeng.model.PacketInfo): Boolean {
        val config = localProxyCoexistConfig
        val cacheKeys = buildCacheKeys(info)
        val cachedFlowDecision = localProxyTargetAppCache[cacheKeys.flowKey]
        val cachedSourcePortDecision = localProxyTargetAppCache[cacheKeys.sourcePortKey]
        val targetContext = resolveDestinationAppContext(info)
        val matched = TrafficDecisionEngine.shouldRouteViaLocalProxy(
            TrafficDecisionEngine.LocalProxyRouteInput(
                packet = info,
                configHost = config.host,
                configPort = config.port,
                configEnabled = config.enabled,
                localProxyReachable = localProxyReachable,
                targetPackages = localProxyTargetPackages,
                cachedFlowDecision = cachedFlowDecision,
                cachedSourcePortDecision = cachedSourcePortDecision,
                destinationIp = targetContext?.destinationIp,
                appName = targetContext?.appName,
                belongsToTargetApp = targetContext?.appName?.let(::belongsToLocalProxyTarget) == true,
                belongsToTargetUid = belongsToLocalProxyTargetUid(cacheKeys)
            )
        )
        if (targetContext != null && TrafficDecisionEngine.isLocalProxyEndpoint(
                targetContext.destinationIp,
                info.destinationPort,
                config.host,
                config.port
            )) {
            localProxyTargetAppCache[cacheKeys.flowKey] = false
            logDecisionOnce(
                key = "local-proxy-route-skip:${targetContext.destinationIp}:${info.destinationPort}",
                message = "Skipped local proxy reroute for direct proxy endpoint ip=${targetContext.destinationIp} port=${info.destinationPort}",
                minIntervalMillis = 15_000L
            )
            return false
        }
        localProxyTargetAppCache[cacheKeys.flowKey] = matched
        localProxyTargetAppCache[cacheKeys.sourcePortKey] = matched
        return matched
    }

    private fun belongsToLocalProxyTarget(appName: String): Boolean {
        return NetworkModeCoordinator.belongsToLocalProxyTarget(appName, localProxyTargetPackages)
    }

    private fun belongsToLocalProxyTargetUid(cacheKeys: AppResolveCacheKeys): Boolean {
        val uid = readCachedOwnerUid(cacheKeys) ?: return false
        if (uid <= 0) return false
        cacheOwnerUid(cacheKeys, uid)
        clearOwnerUidFailure(cacheKeys)
        val packages = packageManager.getPackagesForUid(uid)
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        val selectedPackage = selectBestPackageForUid(packages)
        return NetworkModeCoordinator.belongsToLocalProxyUid(packages, selectedPackage, localProxyTargetPackages)
    }

    private fun shouldBlockEncryptedDnsDirectFlow(info: com.HanFeng.model.PacketInfo): Boolean {
        val targetContext = resolveDestinationAppContext(info) ?: return false
        val destinationIp = targetContext.destinationIp
        val port = info.destinationPort
        val httpsTarget = synchronized(httpsDecryptIpCache) { httpsDecryptIpCache[destinationIp] }
        val quicTarget = synchronized(quicRouteCache) { quicRouteCache[destinationIp] }
        val trackedTargetDomain = httpsTarget?.domain ?: quicTarget?.domain
        val appName = httpsTarget?.appName?.takeIf { it.isNotBlank() }
            ?: quicTarget?.appName?.takeIf { it.isNotBlank() }
            ?: targetContext.appName
        val blockerDecision = EncryptedDnsBlocker.shouldBlockDirectFlow(
            packet = info,
            destinationIp = destinationIp,
            appName = appName,
            forceEncryptedDnsFallback = forceEncryptedDnsFallbackEnabled,
            protectedApp = isProtectedEncryptedDnsApp(appName),
            trackedTargetDomain = trackedTargetDomain
        )
        if (blockerDecision.blocked) {
            StatsRepository.recordBlockedHttp(
                this,
                "加密DNS",
                appName,
                8 * 1024,
                source = if (info.protocol == OsConstants.IPPROTO_UDP) StatsRepository.BlockSource.QUIC_BLOCK else StatsRepository.BlockSource.URL_HEURISTIC
            )
            logDecisionOnce(
                key = "encrypted-dns-blocker:$destinationIp:$port:${blockerDecision.reason}",
                message = "Blocked encrypted DNS by blocker ip=$destinationIp port=$port app=$appName reason=${blockerDecision.reason} matched=${blockerDecision.matchedHost ?: "none"}",
                minIntervalMillis = 15_000L
            )
            return true
        }
        val currentDnsEndpoint = isCurrentDnsEndpoint(destinationIp)
        val knownPublicDnsEndpoint = isKnownPublicDnsEndpoint(destinationIp)
        val blocked = TrafficDecisionEngine.shouldBlockEncryptedDnsDirectFlow(
            input = TrafficDecisionEngine.EncryptedDnsDirectInput(
                destinationIp = destinationIp,
                port = port,
                appName = appName,
                trackedTargetDomain = trackedTargetDomain
            ),
            isCurrentDnsEndpoint = currentDnsEndpoint,
            isKnownPublicDnsEndpoint = knownPublicDnsEndpoint
        )
        if (!blocked) return false
        StatsRepository.recordBlockedHttp(this, "加密DNS", appName, 8 * 1024, source = StatsRepository.BlockSource.URL_HEURISTIC)
        logDecisionOnce(
            key = "encrypted-dns-direct:$destinationIp:$port",
            message = "Blocked encrypted DNS direct flow ip=$destinationIp port=$port app=$appName knownPublic=$knownPublicDnsEndpoint currentDns=$currentDnsEndpoint",
            minIntervalMillis = 15_000L
        )
        return true
    }

    private fun shouldBlockEncryptedDnsClientHello(
        sniHost: String,
        alpnProtocols: List<String>,
        destinationIp: String,
        appName: String
    ): Boolean {
        val isKnownPublicDnsEndpoint = isKnownPublicDnsEndpoint(destinationIp)
        val isCurrentDnsEndpoint = isCurrentDnsEndpoint(destinationIp)
        val blockerDecision = EncryptedDnsBlocker.shouldBlockClientHello(
            destinationIp = destinationIp,
            metadata = EncryptedDnsBlocker.ClientHelloMetadata(sniHost, alpnProtocols),
            appName = appName,
            forceEncryptedDnsFallback = forceEncryptedDnsFallbackEnabled,
            protectedApp = isProtectedEncryptedDnsApp(appName)
        )
        if (blockerDecision.blocked) {
            StatsRepository.recordBlockedHttp(this, "加密DNS", appName, 8 * 1024, source = StatsRepository.BlockSource.URL_HEURISTIC)
            logDecisionOnce(
                key = "encrypted-dns-clienthello-blocker:${sniHost.lowercase()}:$destinationIp",
                message = "Blocked encrypted DNS ClientHello by blocker sni=${sniHost.lowercase()} ip=$destinationIp app=$appName reason=${blockerDecision.reason} alpn=${alpnProtocols.joinToString(",").ifBlank { "none" }}",
                minIntervalMillis = 15_000L
            )
            return true
        }
        val knownDnsEndpoint = isKnownPublicDnsEndpoint || isCurrentDnsEndpoint
        val blocked = TrafficDecisionEngine.shouldBlockEncryptedDnsClientHello(
            input = TrafficDecisionEngine.EncryptedDnsClientHelloInput(
                sniHost = sniHost,
                alpnProtocols = alpnProtocols,
                destinationIp = destinationIp
            ),
            isCurrentDnsEndpoint = isCurrentDnsEndpoint,
            isKnownPublicDnsEndpoint = isKnownPublicDnsEndpoint
        )
        if (!blocked) return false
        val normalizedSni = sniHost.lowercase()
        StatsRepository.recordBlockedHttp(this, "加密DNS", appName, 8 * 1024, source = StatsRepository.BlockSource.URL_HEURISTIC)
        logDecisionOnce(
            key = "encrypted-dns-clienthello:$normalizedSni:$destinationIp",
            message = "Blocked encrypted DNS ClientHello sni=$normalizedSni ip=$destinationIp app=$appName alpn=${alpnProtocols.joinToString(",").ifBlank { "none" }}",
            minIntervalMillis = 15_000L
        )
        return true
    }

    private fun looksLikeQuicPacket(payload: ByteArray): Boolean {
        if (payload.size < 5) return false
        val firstByte = payload[0].toInt() and 0xFF
        // QUIC 包特征：header form bit (0x80) + fixed bit (0x40)
        // Initial 包：0xC0-0xCF, Handshake 包：0xD0-0xDF, 0-RTT 包：0xE0-0xEF, 1-RTT 包：0x40-0x7F
        val isQuicHeader = (firstByte and 0xC0) == 0xC0 || (firstByte and 0xC0) == 0x80 || (firstByte and 0x80) == 0x40
        if (!isQuicHeader) return false
        // 检查是否是 QUIC Initial 包（最常见于连接建立）
        val packetType = (firstByte and 0x30) shr 4
        val isInitialOrHandshake = packetType == 0x00 || packetType == 0x01 || packetType == 0x02
        // 1-RTT 包（加密数据包）通过 range 0x40-0x7F 识别
        val is1Rtt = (firstByte and 0xC0) == 0x40
        return isInitialOrHandshake || is1Rtt
    }

    private fun shouldBlockHttpDecryptConnection(info: com.HanFeng.model.PacketInfo): Boolean {
        val ip = formatAddress(info.destinationAddress)
        maybePruneRouteCaches()
        val target = synchronized(httpDecryptIpCache) {
            httpDecryptIpCache[ip]
        } ?: return false
        val domainContext = resolveDomainDecisionContext(
            domain = target.domain,
            info = info,
            knownAppName = target.appName.takeIf { it.isNotBlank() },
            destinationPort = info.destinationPort,
            sourcePort = info.sourcePort
        )
        val appName = domainContext.appName
        val matchedRule = domainContext.matchedRule
        val vendor = domainContext.vendor
        val decision = TrafficDecisionEngine.shouldBlockHttpDecryptConnection(
            input = TrafficDecisionEngine.HttpDecryptBlockInput(
                packet = info,
                domain = target.domain,
                appName = appName,
                vendor = vendor,
                matchedRule = matchedRule
            ),
            shouldTreatAsTrackedAdTarget = { domain, resolvedAppName, resolvedVendor, resolvedMatchedRule ->
                shouldTreatAsTrackedAdTarget(
                    domain = domain,
                    appName = resolvedAppName,
                    vendor = resolvedVendor,
                    matchedRule = resolvedMatchedRule,
                    includeProtectedNovelUrl = false,
                    includeForceNovelQuic = false
                )
            }
        )
        if (!decision.blocked) {
            logDecisionOnce(
                key = "http-pass:${target.domain}:$ip:${info.sourcePort}",
                message = "Passed HTTP connection domain=${target.domain} ip=$ip app=$appName vendor=$vendor reason=${domainContext.reason} source=${target.source}",
                minIntervalMillis = 30_000L
            )
            return false
        }
        // 需要拦截
        StatsRepository.recordBlockedMitm(this, vendor, appName, 50 * 1024)
        LogRepository.append(
            this,
            "Blocked HTTP connection domain=${target.domain} ip=$ip app=$appName vendor=$vendor reason=${domainContext.reason} source=${target.source} via=http-decrypt-entry"
        )
        return true
    }

    private fun observeHttpsClientHello(info: com.HanFeng.model.PacketInfo) {
        if (info.protocol != OsConstants.IPPROTO_TCP || info.destinationPort != 443) return
        if (info.payload.isEmpty()) return
        val destinationIp = formatAddress(info.destinationAddress)
        if (isLocalLoopOrProxyEndpoint(destinationIp, info.destinationPort)) {
            logDecisionOnce(
                key = "https-clienthello-skip-local:$destinationIp:${info.destinationPort}",
                message = "Skipped HTTPS ClientHello MITM tracking for local endpoint ip=$destinationIp port=${info.destinationPort}",
                minIntervalMillis = 15_000L
            )
            return
        }
        maybePruneRouteCaches()
        val clientHelloInfo = TlsClientHelloParser.extractClientHelloInfo(info.payload) ?: return
        val sniHost = clientHelloInfo.sniHost ?: return
        val cachedDecryptTarget = synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache[destinationIp]
        }
        val decryptTarget = cachedDecryptTarget ?: if (isMitmFullCaptureRoutingActive()) {
            HttpsDecryptTarget(
                domain = sniHost,
                vendor = GENERIC_AD_VENDOR_LABEL,
                appName = resolveAppName(sniHost, info),
                source = "global-mitm-sni",
                expiresAt = System.currentTimeMillis() + 10 * 60 * 1000L
            ).also { target ->
                synchronized(httpsDecryptIpCache) {
                    ExpiringTargetCacheSupport.putAllPrunedLocked(httpsDecryptIpCache, listOf(destinationIp to target), 1024)
                }
            }
        } else {
            return
        }
        val targetContext = resolveDomainDecisionContext(
            domain = decryptTarget.domain,
            info = info,
            knownAppName = decryptTarget.appName.takeIf { it.isNotBlank() },
            knownVendor = decryptTarget.vendor.takeIf { it.isNotBlank() },
            destinationPort = info.destinationPort,
            sourcePort = info.sourcePort
        )
        val appName = targetContext.appName
        if (RuleRepository.isSocialCoreDomain(decryptTarget.domain)) return
        if (RuleRepository.isSocialCoreDomain(sniHost)) return
        if (isProtectedTrafficDomain(decryptTarget.domain)) return
        if (isProtectedTrafficDomain(sniHost)) return
        val targetMatchedRule = targetContext.matchedRule
        val blockedTarget = targetMatchedRule != null
        val sniContext = resolveDomainDecisionContext(
            domain = sniHost,
            info = info,
            knownAppName = appName,
            destinationPort = info.destinationPort,
            sourcePort = info.sourcePort
        )
        val blockedSni = sniContext.matchedRule != null
        val targetVendor = targetContext.vendor
        val targetGeneralAd = shouldTreatAsGeneralAdTraffic(decryptTarget.domain, targetVendor, appName)
        val sniVendor = sniContext.vendor
        val sniGeneralAd = shouldTreatAsGeneralAdTraffic(sniHost, sniVendor, appName)
        if (shouldBlockEncryptedDnsClientHello(sniHost, clientHelloInfo.offeredAlpnProtocols, destinationIp, appName)) {
            return
        }
        val clientHelloDecision = HttpsPipeline.decideClientHello(
            HttpsPipeline.ClientHelloInput(
                targetDomain = decryptTarget.domain,
                sniHost = sniHost,
                blockedTarget = blockedTarget,
                blockedSni = blockedSni,
                targetGeneralAd = targetGeneralAd,
                sniGeneralAd = sniGeneralAd,
                targetSocialCore = RuleRepository.isSocialCoreDomain(decryptTarget.domain),
                sniSocialCore = RuleRepository.isSocialCoreDomain(sniHost),
                targetProtected = isProtectedTrafficDomain(decryptTarget.domain),
                sniProtected = isProtectedTrafficDomain(sniHost),
                targetWhitelisted = RuleRepository.isWhitelistedDomain(decryptTarget.domain),
                sniWhitelisted = RuleRepository.isWhitelistedDomain(sniHost),
                targetSensitive = RuleRepository.isSensitiveAuthDomain(decryptTarget.domain),
                sniSensitive = RuleRepository.isSensitiveAuthDomain(sniHost)
            )
        )
        maybeApplyMitmLearningSignal(
            MitmLearningEngine.Signal(
                appName = appName,
                domain = sniHost,
                ip = destinationIp,
                sniHost = sniHost,
                alpnProtocols = clientHelloInfo.offeredAlpnProtocols,
                type = MitmLearningEngine.SignalType.TLS_FINGERPRINT
            )
        )
        if (!clientHelloDecision.shouldObserve) return
        val decryptSource = decryptTarget.source
        val flowKey = buildCacheKeys(info).flowKey
        logDecisionOnce(
            key = "https-sni:$sniHost:${formatAddress(info.destinationAddress)}",
            message = "Observed HTTPS ClientHello SNI domain=$sniHost app=$appName target=$destinationIp source=$decryptSource alpn=${clientHelloInfo.offeredAlpnProtocols.joinToString(",").ifBlank { "none" }} prefersHttp2=${clientHelloInfo.offeredAlpnProtocols.any { it.equals("h2", ignoreCase = true) }} tls=${clientHelloInfo.handshakeVersion ?: "unknown"} supportedTls=${clientHelloInfo.supportedTlsVersions.joinToString(",").ifBlank { "none" }} ech=${clientHelloInfo.encryptedClientHelloOffered}",
            minIntervalMillis = 15_000L
        )
        UserAdFeedbackManager.recordNetworkActivity(
            this,
            UserAdFeedbackManager.NetworkActivity(
                appName = appName,
                host = sniHost,
                sni = sniHost,
                ip = destinationIp,
                protocol = "HTTPS",
                source = "tls_sni",
                score = if (sniGeneralAd) 5 else 0
            )
        )
        RuleRepository.reportUnknownVendorIfNeeded(
            context = this,
            vendor = sniVendor,
            domain = sniHost,
            appName = appName,
            signal = RuleRepository.SuspiciousSignal.TLS_SNI,
            confidenceBoost = if (sniGeneralAd) 1 else 0,
            refererDomain = decryptTarget.domain
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
                    flowKey = flowKey,
                    host = sniHost,
                    appName = appName,
                    source = decryptSource,
                    targetIp = destinationIp,
                    targetPort = info.destinationPort,
                    certificatePath = it.filePath,
                    offeredAlpnProtocols = clientHelloInfo.offeredAlpnProtocols,
                    clientHelloTlsVersion = clientHelloInfo.handshakeVersion,
                    clientHelloSupportedTlsVersions = clientHelloInfo.supportedTlsVersions,
                    encryptedClientHelloOffered = clientHelloInfo.encryptedClientHelloOffered
                )
                TlsMitmSessionManager.prepareTlsBridge(this, flowKey, this::protect)
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
        val flowKey = buildCacheKeys(info).flowKey
        val ip = formatAddress(info.destinationAddress)
        if (shouldSkipHttpsTransparentProxyTracking(flowKey, ip, info.destinationPort)) return
        maybePruneRouteCaches()
        val target = synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache[ip]
        }
        if (target == null) {
            if (!shouldTrackPendingGlobalMitmHttpsSyn(info, ip)) return
            val appName = resolveAppName(ip, info)
            trackHttpsTransparentProxyFlow(
                flowKey = flowKey,
                target = HttpsDecryptTarget(
                    domain = PENDING_GLOBAL_MITM_DOMAIN,
                    vendor = GENERIC_AD_VENDOR_LABEL,
                    appName = appName,
                    source = "global-mitm-pending",
                    expiresAt = System.currentTimeMillis() + 30_000L
                ),
                ip = ip,
                info = info,
                preparedBridge = PreparedTlsMitmSessionInfo(
                    appName = appName,
                    bridgeHost = null,
                    bridgePort = null
                )
            )
            return
        }
        val transparentDecision = HttpsPipeline.decideTransparentProxy(
            HttpsPipeline.TransparentProxyInput(
                domain = target.domain,
                sensitive = RuleRepository.isSensitiveAuthDomain(target.domain),
                socialCore = RuleRepository.isSocialCoreDomain(target.domain),
                tcpFlags = info.tcpFlags,
                hasPreparedBridgePort = TlsMitmSessionManager.findPreparedSession(
                    targetIp = ip,
                    targetPort = info.destinationPort,
                    appName = target.appName.takeIf { it.isNotBlank() }
                )?.localBridgePort != null,
                hasPayload = info.payload.isNotEmpty(),
                currentState = synchronized(httpsProxyFlowCache) { httpsProxyFlowCache[flowKey]?.state }
            )
        )
        if (!transparentDecision.shouldTrack) {
            FlowCacheSupport.remove(httpsProxyFlowCache, flowKey)
            return
        }
        if (shouldBlockHttpsTransparentProxySyn(flowKey, target, ip, info)) return
        if (shouldBypassHttpsTransparentProxyTracking(flowKey, target.domain)) return
        val preparedBridge = resolveHttpsTransparentPreparedBridge(flowKey, ip, info, target)
        trackHttpsTransparentProxyFlow(flowKey, target, ip, info, preparedBridge)
    }

    private fun shouldTrackPendingGlobalMitmHttpsSyn(info: com.HanFeng.model.PacketInfo, ip: String): Boolean {
        if (!isMitmFullCaptureRoutingActive()) return false
        if (!info.tcpFlags.hasTcpFlag(TCP_FLAG_SYN) || info.tcpFlags.hasTcpFlag(TCP_FLAG_ACK)) return false
        if (isLocalLoopOrProxyEndpoint(ip, info.destinationPort)) return false
        val appName = resolveAppName(ip, info)
        return shouldAggressivelyMitmUnknownHttpsApp(appName)
    }

    private fun shouldAggressivelyMitmUnknownHttpsApp(appName: String): Boolean {
        return isAggressiveAdAppContext(appName) ||
            RuleRepository.isCommunityAppHint(appName) ||
            ShizukuAdControlCatalog.isManagedPromoAppHint(appName)
    }

    private fun observeLocalProxyTcpFlow(info: com.HanFeng.model.PacketInfo) {
        if (info.protocol != OsConstants.IPPROTO_TCP) return
        val flowKey = buildCacheKeys(info).flowKey
        val targetContext = resolveDestinationAppContext(info) ?: return
        trackLocalProxyTcpFlow(flowKey, targetContext, info)
    }

    private fun handleLocalProxyTcpHandshake(info: com.HanFeng.model.PacketInfo): Boolean {
        val flowKey = buildCacheKeys(info).flowKey
        return executeCachedSyntheticHandshake(
            info = info,
            flowCache = localProxyTcpFlowCache,
            flowKey = flowKey,
            destinationPort = 443,
            validateCurrent = {
                val bridgePort = bridgePort
                bridgePort in 1..65535
            },
            buildHandlers = { packetState, activeCurrent ->
                buildLocalProxyHandshakeHandlers(
                    flowKey = flowKey,
                    current = activeCurrent,
                    info = info,
                    sequenceNumber = packetState.sequenceNumber,
                    acknowledgementNumber = packetState.acknowledgementNumber,
                    payloadLength = packetState.payloadLength,
                    now = packetState.now
                )
            }
        )
    }

    private fun resendPendingLocalProxyBridgePayload(
        request: com.HanFeng.model.PacketInfo,
        flow: LocalProxyTcpFlow,
        segments: List<PendingServerSegment>
    ) {
        if (segments.isEmpty()) return
        val clientAck = flow.clientNextSequence ?: ((flow.clientInitialSequence ?: 0L) + 1)
        writeServerPayloadSegments(request, clientAck, segments)
    }

    private fun resendPendingPassthroughTcpPayload(
        request: com.HanFeng.model.PacketInfo,
        flow: PassthroughTcpFlow,
        segments: List<PendingServerSegment>
    ) {
        if (segments.isEmpty()) return
        val clientAck = flow.clientNextSequence ?: ((flow.clientInitialSequence ?: 0L) + 1)
        writeServerPayloadSegments(request, clientAck, segments)
    }

    private fun ensureLocalProxyBridgeSocket(flowKey: String, flow: LocalProxyTcpFlow, request: com.HanFeng.model.PacketInfo) {
        ensureBridgeSocketConnected(
            sessionCache = localProxyBridgeSocketCache,
            flowKey = flowKey,
            bridgeHost = flow.bridgeHost,
            bridgePort = flow.bridgePort,
            connectBridge = { host, port ->
                val socket = BridgeSocketSupport.createConnectedSocket(
                    host = host,
                    port = port,
                    timeoutMillis = LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS,
                    protect = this@AdBlockVpnService::protect,
                    onProtectFailed = {
                        logBridgeProtectFailure("local proxy bridge socket", flowKey, "target=${flow.targetIp}:${flow.targetPort}")
                    }
                )
                LocalProxyBridgeConnectSupport.performLocalProxyConnect(
                    socket = socket,
                    request = request,
                    host = host,
                    port = port,
                    timeoutMillis = LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS,
                    protect = this@AdBlockVpnService::protect,
                    onFallbackProtectFailed = {
                        LogRepository.append(this@AdBlockVpnService, "Protect local proxy HTTP CONNECT socket failed target=${formatAddress(request.destinationAddress)}:${request.destinationPort}")
                    }
                )
            },
            createSession = { connectedBridge ->
                LocalProxyBridgeSocketSession(
                    flowKey = flowKey,
                    requestTemplate = request,
                    socket = connectedBridge.socket,
                    input = connectedBridge.socket.getInputStream(),
                    output = connectedBridge.socket.getOutputStream()
                )
            },
            onConnected = { session, connectedBridge ->
                flushBufferedLocalProxyPayload(flowKey)
                scope.launch { runLocalProxyBridgeReader(session) }
                logBridgeConnected("local proxy bridge", flowKey, "target=${flow.targetIp}:${flow.targetPort} via=${flow.bridgeHost}:${flow.bridgePort} protocol=${connectedBridge.protocol}")
            },
            onFailure = buildLocalProxyBridgeFailureHandler(
                action = "Connect local proxy bridge failed",
                flowKey = flowKey,
                request = request,
                stage = "connect",
                target = "${flow.targetIp}:${flow.targetPort}"
            )
        )
    }

    private fun trackPassthroughTcpFlow(flowKey: String, info: com.HanFeng.model.PacketInfo) {
        val targetIp = formatAddress(info.destinationAddress)
        val appName = resolveDestinationAppContext(info)?.appName ?: "未知应用"
        synchronized(passthroughTcpFlowCache) {
            val current = passthroughTcpFlowCache[flowKey]
            FlowCacheSupport.putPruned(
                cache = passthroughTcpFlowCache,
                key = flowKey,
                value = PassthroughTcpFlow(
                    flowKey = flowKey,
                    targetIp = targetIp,
                    targetPort = info.destinationPort,
                    sourcePort = info.sourcePort,
                    appName = appName,
                    state = resolveLocalProxyTcpFlowState(current?.state, info.tcpFlags, info.payload.isNotEmpty()),
                    clientInitialSequence = current?.clientInitialSequence,
                    serverInitialSequence = current?.serverInitialSequence,
                    clientNextSequence = current?.clientNextSequence,
                    serverNextSequence = current?.serverNextSequence,
                    lastServerPayloadSequence = current?.lastServerPayloadSequence,
                    lastServerPayload = current?.lastServerPayload,
                    pendingServerSegments = current?.pendingServerSegments.orEmpty(),
                    bufferedClientSegments = current?.bufferedClientSegments.orEmpty(),
                    lastClientPayloadSequence = current?.lastClientPayloadSequence,
                    lastClientPayloadLength = current?.lastClientPayloadLength,
                    lastSequenceNumber = info.tcpSequenceNumber,
                    lastAcknowledgementNumber = info.tcpAcknowledgementNumber,
                    lastSeenAt = System.currentTimeMillis()
                ),
                maxSize = 512
            )
        }
    }

    private fun ensurePassthroughTcpSocket(flowKey: String, flow: PassthroughTcpFlow, request: com.HanFeng.model.PacketInfo) {
        ensureBridgeSocketConnected(
            sessionCache = passthroughTcpSocketCache,
            flowKey = flowKey,
            bridgeHost = flow.targetIp,
            bridgePort = flow.targetPort,
            connectBridge = { host, port ->
                BridgeSocketSupport.createConnectedSocket(
                    host = host,
                    port = port,
                    timeoutMillis = PASSTHROUGH_TCP_CONNECT_TIMEOUT_MILLIS,
                    protect = this@AdBlockVpnService::protect,
                    onProtectFailed = {
                        logBridgeProtectFailure("passthrough TCP socket", flowKey, "target=${flow.targetIp}:${flow.targetPort}")
                        recordPassthroughFailure("tcp-protect", "target=${flow.targetIp}:${flow.targetPort}")
                    }
                )
            },
            createSession = { socket ->
                PassthroughTcpSocketSession(
                    flowKey = flowKey,
                    requestTemplate = request,
                    socket = socket,
                    input = socket.getInputStream(),
                    output = socket.getOutputStream()
                )
            },
            onConnected = { session, _ ->
                recordPassthroughSuccess("tcp-connect")
                flushBufferedPassthroughPayload(flowKey)
                scope.launch { runPassthroughTcpReader(session) }
                logBridgeConnected("passthrough TCP socket", flowKey, "target=${flow.targetIp}:${flow.targetPort} app=${flow.appName}")
            },
            onFailure = buildPassthroughTcpFailureHandler(
                action = "Connect passthrough TCP failed",
                flowKey = flowKey,
                request = request,
                stage = "connect",
                target = "${flow.targetIp}:${flow.targetPort}"
            )
        )
    }

    private fun forwardPayloadToPassthroughTcp(flowKey: String, payload: ByteArray) {
        forwardPayloadToBridge(
            sessionCache = passthroughTcpSocketCache,
            flowKey = flowKey,
            payload = payload,
            writePayload = { session, bytes -> session.output.write(bytes) },
            onFailure = buildSessionBridgeFailureHandler(
                failureHandlerOfRequest = { request ->
                    buildPassthroughTcpFailureHandler(
                        action = "Forward passthrough TCP payload failed",
                        flowKey = flowKey,
                        request = request,
                        stage = "write"
                    )
                },
                requestOfSession = { it.requestTemplate }
            )
        )
    }

    private fun flushBufferedPassthroughPayload(flowKey: String) {
        val segmentsToFlush = synchronized(passthroughTcpFlowCache) {
            val current = passthroughTcpFlowCache[flowKey] ?: return
            val flushResult = BridgeFlowStateSupport.flushBufferedClientPayload(
                flow = current,
                bufferedSegments = current.bufferedClientSegments,
                lastSequenceOf = { it.sequenceNumber },
                payloadSizeOf = { it.payload.size },
                now = System.currentTimeMillis(),
                updateFlow = ::updateFlushedPassthroughBufferedPayloadFlow
            )
            passthroughTcpFlowCache[flowKey] = flushResult.nextFlow ?: current
            flushResult.forwardSegments
        }
        segmentsToFlush.forEach { segment ->
            forwardPayloadToPassthroughTcp(flowKey, segment.payload)
        }
    }

    private fun runPassthroughTcpReader(session: PassthroughTcpSocketSession) {
        runBridgeReaderConfigured(
            session = session,
            input = session.input,
            flowKey = session.flowKey,
            config = buildPassthroughTcpReaderConfig(session)
        )
    }

    private fun emitPassthroughTcpPayload(flowKey: String, request: com.HanFeng.model.PacketInfo, payload: ByteArray) {
        recordPassthroughSuccess("tcp-read")
        val selectors = BridgeFlowSelectors<PassthroughTcpFlow>(
            sequence = passthroughSequenceSelectors(),
            pendingSegmentsOf = { it.pendingServerSegments }
        )
        emitBridgePayloadCommon(
            flowCache = passthroughTcpFlowCache,
            flowKey = flowKey,
            request = request,
            payload = payload,
            selectors = selectors,
            onOverflow = {
                emitPassthroughTcpReset(flowKey, request, "Pending server window overflow target=${it.targetIp}:${it.targetPort}")
            },
            updateFlow = ::updatePassthroughBridgePayloadFlow
        )
    }

    private fun emitPassthroughTcpFin(flowKey: String, request: com.HanFeng.model.PacketInfo) {
        emitBridgeFinCommon(
            flowCache = passthroughTcpFlowCache,
            flowKey = flowKey,
            request = request,
            selectors = BridgeFlowSelectors(
                sequence = passthroughSequenceSelectors(),
                stateOf = { it.state }
            ),
            updateFlow = ::updatePassthroughBridgeFinFlow
        )
    }

    private fun emitPassthroughTcpReset(flowKey: String, request: com.HanFeng.model.PacketInfo, message: String) {
        emitBridgeResetCommon(
            flowCache = passthroughTcpFlowCache,
            flowKey = flowKey,
            request = request,
            message = message,
            selectors = BridgeFlowSelectors(sequence = passthroughSequenceSelectors()),
            closeFlow = ::closePassthroughTcpFlow
        )
    }

    private fun closePassthroughTcpFlow(flowKey: String, message: String) {
        closeBridgeFlowCommon(
            flowCache = passthroughTcpFlowCache,
            sessionCache = passthroughTcpSocketCache,
            flowKey = flowKey,
            message = message,
            logKey = buildBridgeCloseLogKey("passthrough-tcp", flowKey)
        )
    }


    private fun handleHttpsProxyHandshake(info: com.HanFeng.model.PacketInfo, output: FileOutputStream): Boolean {
        val flowKey = buildCacheKeys(info).flowKey
        return executeCachedSyntheticHandshake(
            info = info,
            flowCache = httpsProxyFlowCache,
            flowKey = flowKey,
            destinationPort = info.destinationPort,
            buildHandlers = { packetState, activeCurrent ->
                buildHttpsHandshakeHandlers(
                    flowKey = flowKey,
                    current = activeCurrent,
                    info = info,
                    sequenceNumber = packetState.sequenceNumber,
                    acknowledgementNumber = packetState.acknowledgementNumber,
                    payloadLength = packetState.payloadLength,
                    now = packetState.now
                )
            }
        )
    }

    private fun <T> executeCachedSyntheticHandshake(
        info: com.HanFeng.model.PacketInfo,
        flowCache: MutableMap<String, T>,
        flowKey: String,
        destinationPort: Int,
        validateCurrent: T.() -> Boolean = { true },
        buildHandlers: (SyntheticPacketState, T) -> SyntheticHandshakeHandlers
    ): Boolean {
        val current = synchronized(flowCache) {
            flowCache[flowKey]
        } ?: return false
        return executeSyntheticHandshake(
            info = info,
            destinationPort = destinationPort,
            current = current,
            currentState = resolveSyntheticFlowState(current),
            validateCurrent = { current.validateCurrent() },
            buildHandlers = buildHandlers
        )
    }

    private fun resolveSyntheticFlowState(current: Any): String {
        return when (current) {
            is LocalProxyTcpFlow -> current.state
            is HttpsProxyFlow -> current.state
            is PassthroughTcpFlow -> current.state
            else -> ""
        }
    }

    private fun ensureHttpsBridgeSocket(flowKey: String, flow: HttpsProxyFlow, request: com.HanFeng.model.PacketInfo) {
        val bridgeHost = flow.bridgeHost
        val bridgePort = flow.bridgePort
        if (bridgeHost.isNullOrBlank() || bridgePort !in 1..65535) {
            logDecisionOnce(
                key = "https-bridge-pending:$flowKey",
                message = "Deferred HTTPS bridge connect until TLS bridge is prepared domain=${flow.domain} target=${flow.targetIp}:${request.destinationPort} app=${flow.appName.ifBlank { "unknown" }} source=${flow.source}",
                minIntervalMillis = 10_000L
            )
            return
        }
        ensureBridgeSocketConnected(
            sessionCache = httpsBridgeSocketCache,
            flowKey = flowKey,
            bridgeHost = bridgeHost,
            bridgePort = bridgePort,
            connectBridge = { host, port ->
                BridgeSocketSupport.createConnectedSocket(
                    host = host,
                    port = port,
                    timeoutMillis = HTTPS_BRIDGE_CONNECT_TIMEOUT_MILLIS,
                    protect = this@AdBlockVpnService::protect,
                    onProtectFailed = {
                        logBridgeProtectFailure("local HTTPS bridge socket", flowKey, "domain=${flow.domain} app=${flow.appName.ifBlank { "unknown" }} source=${flow.source}")
                    }
                )
            },
            createSession = { socket ->
                HttpsBridgeSocketSession(
                    flowKey = flowKey,
                    requestTemplate = request,
                    socket = socket,
                    input = socket.getInputStream(),
                    output = socket.getOutputStream()
                )
            },
            onConnected = { session, _ ->
                scope.launch { runHttpsBridgeReader(session) }
                logBridgeConnected("local HTTPS bridge socket", flowKey, "domain=${flow.domain} source=${flow.source} local=${flow.bridgeHost}:${flow.bridgePort}")
            },
            onFailure = buildHttpsBridgeFailureHandler(
                action = "Connect local HTTPS bridge socket failed",
                flowKey = flowKey,
                request = request,
                stage = "connect"
            )
        )
    }

    private fun forwardPayloadToHttpsBridge(flowKey: String, payload: ByteArray) {
        forwardPayloadToBridge(
            sessionCache = httpsBridgeSocketCache,
            flowKey = flowKey,
            payload = payload,
            writePayload = { session, bytes -> session.output.write(bytes) },
            onFailure = buildSessionBridgeFailureHandler(
                failureHandlerOfRequest = { request ->
                    buildHttpsBridgeFailureHandler(
                        action = "Forward HTTPS payload to bridge failed",
                        flowKey = flowKey,
                        request = request,
                        stage = "write"
                    )
                },
                requestOfSession = { it.requestTemplate }
            )
        )
    }

    private fun isHttpsBridgeConnected(flowKey: String): Boolean {
        return synchronized(httpsBridgeSocketCache) {
            httpsBridgeSocketCache[flowKey]?.socket?.isClosed == false
        }
    }

    private fun mergeBufferedClientSegments(
        existing: List<ClientPayloadSegment>,
        additions: List<ClientPayloadSegment>
    ): List<ClientPayloadSegment> {
        return TcpSyntheticFlowEngine.mergeBufferedClientSegments(
            existing = existing.map { TcpSyntheticFlowEngine.ClientSegment(it.sequenceNumber, it.payload) },
            additions = additions.map { TcpSyntheticFlowEngine.ClientSegment(it.sequenceNumber, it.payload) },
            maxSegments = MAX_BUFFERED_CLIENT_SEGMENTS
        ).map { ClientPayloadSegment(it.sequenceNumber, it.payload) }
    }

    private fun mergeBufferedClientSegments(
        existing: List<ClientPayloadSegment>,
        addition: ClientPayloadSegment
    ): List<ClientPayloadSegment> = mergeBufferedClientSegments(existing, listOf(addition))

    private fun logBridgeProtectFailure(kind: String, flowKey: String, detail: String) {
        LogRepository.append(this, "Protect $kind failed flow=$flowKey $detail")
    }

    private fun logBridgeConnected(kind: String, flowKey: String, detail: String) {
        LogRepository.append(this, "Connected $kind flow=$flowKey $detail")
    }

    private fun logBridgeFailure(action: String, flowKey: String, error: Throwable) {
        LogRepository.append(this, BridgeFailureSupport.buildFailureLog(action, flowKey, error))
    }

    private fun buildHttpsBridgeFailureHandler(
        action: String,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        stage: String
    ): (Throwable) -> Unit {
        return { error ->
            handleHttpsBridgeFailure(
                action = action,
                flowKey = flowKey,
                request = request,
                stage = stage,
                error = error
            )
        }
    }

    private fun buildLocalProxyBridgeFailureHandler(
        action: String,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        stage: String,
        target: String? = null
    ): (Throwable) -> Unit {
        return { error ->
            handleLocalProxyBridgeFailure(
                action = action,
                flowKey = flowKey,
                request = request,
                stage = stage,
                error = error,
                target = target ?: resolveLocalProxyTarget(flowKey)
            )
        }
    }

    private fun buildPassthroughTcpFailureHandler(
        action: String,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        stage: String,
        target: String? = null
    ): (Throwable) -> Unit {
        return { error ->
            recordPassthroughFailure("tcp-$stage", "target=${target ?: resolvePassthroughTcpTarget(flowKey)} error=${error.message ?: error.javaClass.simpleName}")
            logBridgeFailure(action, flowKey, error)
            emitPassthroughTcpReset(
                flowKey = flowKey,
                request = request,
                message = "Bridge $stage reset passthrough TCP target=${target ?: resolvePassthroughTcpTarget(flowKey)}"
            )
        }
    }

    private fun <TSession> buildSessionBridgeFailureHandler(
        failureHandlerOfRequest: (com.HanFeng.model.PacketInfo) -> (Throwable) -> Unit,
        requestOfSession: (TSession) -> com.HanFeng.model.PacketInfo
    ): (TSession, Throwable) -> Unit {
        return { session, error ->
            failureHandlerOfRequest(requestOfSession(session))(error)
        }
    }

    private fun handleHttpsBridgeFailure(
        action: String,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        stage: String,
        error: Throwable
    ) {
        logBridgeFailure(action, flowKey, error)
        val domain = resolveHttpsProxyDomain(flowKey)
        if (domain != "unknown") {
            HttpsMitmRepository.markBypassCooldown(
                this,
                domain,
                reason = HttpsBridgeFailureSupport.buildBypassReason(error)
            )
        }
        emitHttpsBridgeReset(flowKey, request, HttpsBridgeFailureSupport.buildResetMessage(stage, domain))
    }

    private fun handleLocalProxyBridgeFailure(
        action: String,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        stage: String,
        error: Throwable,
        target: String = resolveLocalProxyTarget(flowKey)
    ) {
        logBridgeFailure(action, flowKey, error)
        emitLocalProxyBridgeReset(flowKey, request, buildBridgeStageResetMessage("local proxy flow target=$target", "Bridge $stage"))
    }

    private fun buildBridgeStageResetMessage(subject: String, stage: String): String {
        return "$stage reset $subject"
    }

    private fun buildLocalProxyFlowLabel(target: String): String {
        return "local proxy flow target=$target"
    }

    private fun buildHttpsFlowLabel(domain: String): String {
        return "HTTPS proxy flow domain=$domain"
    }

    private fun resolvePassthroughTcpTarget(flowKey: String): String {
        return synchronized(passthroughTcpFlowCache) {
            passthroughTcpFlowCache[flowKey]?.let { "${it.targetIp}:${it.targetPort}" }
        } ?: "unknown"
    }

    private fun buildLocalProxyResetMessage(stage: String, target: String): String {
        return buildBridgeStageResetMessage(buildLocalProxyFlowLabel(target), stage)
    }

    private fun emitLocalProxyTargetReset(
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        stage: String,
        target: String
    ) {
        emitLocalProxyBridgeReset(
            flowKey,
            request,
            buildLocalProxyResetMessage(stage, target)
        )
    }

    private fun buildHttpsProxyResetMessage(stage: String, domain: String): String {
        return buildBridgeStageResetMessage(buildHttpsFlowLabel(domain), stage)
    }

    private fun buildBridgeLogKey(prefix: String, flowKey: String, suffix: String): String {
        return "$prefix:$flowKey:$suffix"
    }

    private fun buildBridgeCloseLogKey(prefix: String, flowKey: String): String {
        return buildBridgeLogKey(prefix, flowKey, "closed")
    }

    private fun logBridgeFinSent(flowKey: String, domain: String, source: String) {
        logDecisionOnce(
            key = "https-proxy-bridge-fin:$flowKey",
            message = "Sent bridge-initiated FIN-ACK for HTTPS proxy flow domain=$domain source=$source",
            minIntervalMillis = 5_000L
        )
    }

    private data class BridgePayloadState(
        val state: String,
        val serverNextSequence: Long,
        val lastServerPayloadSequence: Long,
        val lastServerPayload: ByteArray,
        val pendingServerSegments: List<PendingServerSegment>,
        val lastSeenAt: Long
    )

    private fun buildBridgePayloadState(
        pendingSegments: List<PendingServerSegment>,
        serverNextSequence: Long,
        lastServerPayloadSequence: Long,
        lastServerPayload: ByteArray,
        lastSeenAt: Long
    ): BridgePayloadState {
        return BridgePayloadState(
            state = "server_payload_sent",
            serverNextSequence = serverNextSequence,
            lastServerPayloadSequence = lastServerPayloadSequence,
            lastServerPayload = lastServerPayload,
            pendingServerSegments = pendingSegments,
            lastSeenAt = lastSeenAt
        )
    }

    private data class BridgeSocketClosedState(
        val state: String,
        val lastSeenAt: Long
    )

    private fun buildBridgeSocketClosedState(lastSeenAt: Long): BridgeSocketClosedState {
        return BridgeSocketClosedState(
            state = "bridge_socket_closed",
            lastSeenAt = lastSeenAt
        )
    }

    private data class BridgeFinState(
        val state: String,
        val serverNextSequence: Long,
        val lastSeenAt: Long
    )

    private fun buildBridgeFinState(
        transition: BridgeTerminalStateSupport.BridgeFinTransition
    ): BridgeFinState {
        return BridgeFinState(
            state = transition.nextState,
            serverNextSequence = transition.nextServerSequence,
            lastSeenAt = transition.lastSeenAt
        )
    }

    private fun updateHttpsBridgePayloadFlow(
        flowState: HttpsProxyFlow,
        pendingSegments: List<PendingServerSegment>,
        serverNextSequence: Long,
        lastServerPayloadSequence: Long,
        lastServerPayload: ByteArray,
        lastSeenAt: Long
    ): HttpsProxyFlow {
        val payloadState = buildBridgePayloadState(
            pendingSegments = pendingSegments,
            serverNextSequence = serverNextSequence,
            lastServerPayloadSequence = lastServerPayloadSequence,
            lastServerPayload = lastServerPayload,
            lastSeenAt = lastSeenAt
        )
        return flowState.copy(
            state = payloadState.state,
            serverNextSequence = payloadState.serverNextSequence,
            lastServerPayloadSequence = payloadState.lastServerPayloadSequence,
            lastServerPayload = payloadState.lastServerPayload,
            pendingServerSegments = payloadState.pendingServerSegments,
            lastSeenAt = payloadState.lastSeenAt
        )
    }

    private fun updateLocalProxyBridgePayloadFlow(
        flowState: LocalProxyTcpFlow,
        pendingSegments: List<PendingServerSegment>,
        serverNextSequence: Long,
        lastServerPayloadSequence: Long,
        lastServerPayload: ByteArray,
        lastSeenAt: Long
    ): LocalProxyTcpFlow {
        val payloadState = buildBridgePayloadState(
            pendingSegments = pendingSegments,
            serverNextSequence = serverNextSequence,
            lastServerPayloadSequence = lastServerPayloadSequence,
            lastServerPayload = lastServerPayload,
            lastSeenAt = lastSeenAt
        )
        return flowState.copy(
            state = payloadState.state,
            serverNextSequence = payloadState.serverNextSequence,
            lastServerPayloadSequence = payloadState.lastServerPayloadSequence,
            lastServerPayload = payloadState.lastServerPayload,
            pendingServerSegments = payloadState.pendingServerSegments,
            lastSeenAt = payloadState.lastSeenAt
        )
    }

    private fun updatePassthroughBridgePayloadFlow(
        flowState: PassthroughTcpFlow,
        pendingSegments: List<PendingServerSegment>,
        serverNextSequence: Long,
        lastServerPayloadSequence: Long,
        lastServerPayload: ByteArray,
        lastSeenAt: Long
    ): PassthroughTcpFlow {
        val payloadState = buildBridgePayloadState(
            pendingSegments = pendingSegments,
            serverNextSequence = serverNextSequence,
            lastServerPayloadSequence = lastServerPayloadSequence,
            lastServerPayload = lastServerPayload,
            lastSeenAt = lastSeenAt
        )
        return flowState.copy(
            state = payloadState.state,
            serverNextSequence = payloadState.serverNextSequence,
            lastServerPayloadSequence = payloadState.lastServerPayloadSequence,
            lastServerPayload = payloadState.lastServerPayload,
            pendingServerSegments = payloadState.pendingServerSegments,
            lastSeenAt = payloadState.lastSeenAt
        )
    }

    private fun updateHttpsBridgeFinFlow(
        flowState: HttpsProxyFlow,
        transition: BridgeTerminalStateSupport.BridgeFinTransition
    ): HttpsProxyFlow {
        val finState = buildBridgeFinState(transition)
        return flowState.copy(
            state = finState.state,
            serverNextSequence = finState.serverNextSequence,
            lastSeenAt = finState.lastSeenAt
        )
    }

    private fun updateLocalProxyBridgeFinFlow(
        flowState: LocalProxyTcpFlow,
        transition: BridgeTerminalStateSupport.BridgeFinTransition
    ): LocalProxyTcpFlow {
        val finState = buildBridgeFinState(transition)
        return flowState.copy(
            state = finState.state,
            serverNextSequence = finState.serverNextSequence,
            lastSeenAt = finState.lastSeenAt
        )
    }

    private fun updatePassthroughBridgeFinFlow(
        flowState: PassthroughTcpFlow,
        transition: BridgeTerminalStateSupport.BridgeFinTransition
    ): PassthroughTcpFlow {
        val finState = buildBridgeFinState(transition)
        return flowState.copy(
            state = finState.state,
            serverNextSequence = finState.serverNextSequence,
            lastSeenAt = finState.lastSeenAt
        )
    }

    private fun updateHttpsBridgeSocketClosedFlow(
        flowState: HttpsProxyFlow,
        lastSeenAt: Long
    ): HttpsProxyFlow {
        val closedState = buildBridgeSocketClosedState(lastSeenAt)
        return flowState.copy(
            state = closedState.state,
            lastSeenAt = closedState.lastSeenAt
        )
    }

    private fun updateLocalProxyBridgeSocketClosedFlow(
        flowState: LocalProxyTcpFlow,
        lastSeenAt: Long
    ): LocalProxyTcpFlow {
        val closedState = buildBridgeSocketClosedState(lastSeenAt)
        return flowState.copy(
            state = closedState.state,
            lastSeenAt = closedState.lastSeenAt
        )
    }

    private fun updatePassthroughBridgeSocketClosedFlow(
        flowState: PassthroughTcpFlow,
        lastSeenAt: Long
    ): PassthroughTcpFlow {
        val closedState = buildBridgeSocketClosedState(lastSeenAt)
        return flowState.copy(
            state = closedState.state,
            lastSeenAt = closedState.lastSeenAt
        )
    }

    private fun updateFlushedLocalProxyBufferedPayloadFlow(
        flowState: LocalProxyTcpFlow,
        bufferedSegments: List<ClientPayloadSegment>,
        lastSequence: Long?,
        lastLength: Long?,
        lastSeenAt: Long
    ): LocalProxyTcpFlow {
        return flowState.copy(
            state = "established",
            bufferedClientSegments = bufferedSegments,
            lastClientPayloadSequence = lastSequence ?: flowState.lastClientPayloadSequence,
            lastClientPayloadLength = lastLength ?: flowState.lastClientPayloadLength,
            lastSeenAt = lastSeenAt
        )
    }

    private fun updateFlushedPassthroughBufferedPayloadFlow(
        flowState: PassthroughTcpFlow,
        bufferedSegments: List<ClientPayloadSegment>,
        lastSequence: Long?,
        lastLength: Long?,
        lastSeenAt: Long
    ): PassthroughTcpFlow {
        return flowState.copy(
            state = "established",
            bufferedClientSegments = bufferedSegments,
            lastClientPayloadSequence = lastSequence ?: flowState.lastClientPayloadSequence,
            lastClientPayloadLength = lastLength ?: flowState.lastClientPayloadLength,
            lastSeenAt = lastSeenAt
        )
    }

    private data class BridgeReaderConfig<TSession, TFlow>(
        val flowCache: MutableMap<String, TFlow>,
        val sessionCache: MutableMap<String, TSession>,
        val onPayload: (ByteArray) -> Unit,
        val onFailure: (Throwable) -> Unit,
        val onResetNotSent: () -> Unit,
        val closeSession: (TSession) -> Unit,
        val updateFlow: (TFlow, Long) -> TFlow
    )

    private fun drainBufferedClientSegments(
        segments: List<ClientPayloadSegment>,
        expectedSequence: Long
    ): ClientSegmentDrainResult {
        val result = TcpSyntheticFlowEngine.drainBufferedClientSegments(
            segments = segments.map { TcpSyntheticFlowEngine.ClientSegment(it.sequenceNumber, it.payload) },
            expectedSequence = expectedSequence,
            maxSegments = MAX_BUFFERED_CLIENT_SEGMENTS
        )
        return ClientSegmentDrainResult(
            nextExpectedSequence = result.nextExpectedSequence,
            forwardSegments = result.forwardSegments.map { ClientPayloadSegment(it.sequenceNumber, it.payload) },
            remainingSegments = result.remainingSegments.map { ClientPayloadSegment(it.sequenceNumber, it.payload) }
        )
    }

    private fun runHttpsBridgeReader(session: HttpsBridgeSocketSession) {
        runBridgeReaderConfigured(
            session = session,
            input = session.input,
            flowKey = session.flowKey,
            config = buildHttpsBridgeReaderConfig(session)
        )
    }

    private fun emitHttpsBridgePayload(flowKey: String, request: com.HanFeng.model.PacketInfo, payload: ByteArray) {
        val selectors = BridgeFlowSelectors<HttpsProxyFlow>(
            sequence = httpsSequenceSelectors(),
            pendingSegmentsOf = { it.pendingServerSegments }
        )
        val emission = emitBridgePayloadCommon(
            flowCache = httpsProxyFlowCache,
            flowKey = flowKey,
            request = request,
            payload = payload,
            selectors = selectors,
            onOverflow = {
                emitHttpsBridgeReset(flowKey, request, HttpsBridgeFailureSupport.buildResetMessage("pending-server-window-overflow", it.domain))
            },
            updateFlow = ::updateHttpsBridgePayloadFlow
        ) ?: return
        val flow = emission.flow
        val pendingSummary = emission.pendingSummary
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
        return TcpSyntheticFlowEngine.trimAcknowledgedServerSegments(
            segments = segments.map { TcpSyntheticFlowEngine.PendingSegment(it.sequenceNumber, it.payload) },
            acknowledgementNumber = acknowledgementNumber
        ).map { PendingServerSegment(it.sequenceNumber, it.payload) }
    }

    private fun mergePendingServerSegments(
        existing: List<PendingServerSegment>,
        additions: List<PendingServerSegment>
    ): List<PendingServerSegment> {
        return TcpSyntheticFlowEngine.mergePendingServerSegments(
            existing = existing.map { TcpSyntheticFlowEngine.PendingSegment(it.sequenceNumber, it.payload) },
            additions = additions.map { TcpSyntheticFlowEngine.PendingSegment(it.sequenceNumber, it.payload) },
            maxSegments = MAX_BUFFERED_SERVER_SEGMENTS
        ).map { PendingServerSegment(it.sequenceNumber, it.payload) }
    }

    private fun buildServerPayloadSegments(sequenceNumber: Long, payload: ByteArray): List<PendingServerSegment> {
        return TcpSyntheticFlowEngine.buildServerPayloadSegments(
            sequenceNumber = sequenceNumber,
            payload = payload,
            segmentPayloadSize = TCP_SEGMENT_PAYLOAD_SIZE
        ).map { PendingServerSegment(it.sequenceNumber, it.payload) }
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
        val selectors = BridgeFlowSelectors<HttpsProxyFlow>(
            sequence = httpsSequenceSelectors(),
            stateOf = { it.state }
        )
        val flow = emitBridgeFinCommon(
            flowCache = httpsProxyFlowCache,
            flowKey = flowKey,
            request = request,
            selectors = selectors,
            updateFlow = ::updateHttpsBridgeFinFlow
        ) ?: return
        logBridgeFinSent(flowKey, flow.domain, flow.source)
    }

    private fun closeHttpsProxyFlow(flowKey: String, message: String) {
        closeBridgeFlowCommon(
            flowCache = httpsProxyFlowCache,
            sessionCache = httpsBridgeSocketCache,
            flowKey = flowKey,
            message = message,
            logKey = buildBridgeCloseLogKey("https-proxy-handshake", flowKey)
        )
    }

    private fun emitHttpsBridgeReset(flowKey: String, request: com.HanFeng.model.PacketInfo, message: String) {
        val selectors = BridgeFlowSelectors<HttpsProxyFlow>(
            sequence = httpsSequenceSelectors()
        )
        emitBridgeResetCommon(
            flowCache = httpsProxyFlowCache,
            flowKey = flowKey,
            request = request,
            message = message,
            selectors = selectors,
            closeFlow = ::closeHttpsProxyFlow
        )
    }

    private fun resolveHttpsProxyDomain(flowKey: String): String {
        return synchronized(httpsProxyFlowCache) {
            httpsProxyFlowCache[flowKey]?.domain
        } ?: "unknown"
    }

    private fun forwardPayloadToLocalProxyBridge(flowKey: String, payload: ByteArray) {
        forwardPayloadToBridge(
            sessionCache = localProxyBridgeSocketCache,
            flowKey = flowKey,
            payload = payload,
            writePayload = { session, bytes -> session.output.write(bytes) },
            onFailure = buildSessionBridgeFailureHandler(
                failureHandlerOfRequest = { request ->
                    buildLocalProxyBridgeFailureHandler(
                        action = "Forward local proxy payload failed",
                        flowKey = flowKey,
                        request = request,
                        stage = "write"
                    )
                },
                requestOfSession = { it.requestTemplate }
            )
        )
    }

    private fun flushBufferedLocalProxyPayload(flowKey: String) {
        val segmentsToFlush = synchronized(localProxyTcpFlowCache) {
            val current = localProxyTcpFlowCache[flowKey] ?: return
            val flushResult = BridgeFlowStateSupport.flushBufferedClientPayload(
                flow = current,
                bufferedSegments = current.bufferedClientSegments,
                lastSequenceOf = { it.sequenceNumber },
                payloadSizeOf = { it.payload.size },
                now = System.currentTimeMillis(),
                updateFlow = ::updateFlushedLocalProxyBufferedPayloadFlow
            )
            localProxyTcpFlowCache[flowKey] = flushResult.nextFlow ?: current
            flushResult.forwardSegments
        }
        segmentsToFlush.forEach { segment ->
            forwardPayloadToLocalProxyBridge(flowKey, segment.payload)
        }
    }

    private fun runLocalProxyBridgeReader(session: LocalProxyBridgeSocketSession) {
        runBridgeReaderConfigured(
            session = session,
            input = session.input,
            flowKey = session.flowKey,
            config = buildLocalProxyBridgeReaderConfig(session)
        )
    }

    private fun emitLocalProxyBridgePayload(flowKey: String, request: com.HanFeng.model.PacketInfo, payload: ByteArray) {
        val selectors = BridgeFlowSelectors<LocalProxyTcpFlow>(
            sequence = localProxySequenceSelectors(),
            pendingSegmentsOf = { it.pendingServerSegments }
        )
        emitBridgePayloadCommon(
            flowCache = localProxyTcpFlowCache,
            flowKey = flowKey,
            request = request,
            payload = payload,
            selectors = selectors,
            onOverflow = {
                emitLocalProxyTargetReset(
                    flowKey,
                    request,
                    stage = "Pending server window overflow",
                    target = "${it.targetIp}:${it.targetPort}"
                )
            },
            updateFlow = ::updateLocalProxyBridgePayloadFlow
        )
    }

    private fun emitLocalProxyBridgeFin(flowKey: String, request: com.HanFeng.model.PacketInfo) {
        val selectors = BridgeFlowSelectors<LocalProxyTcpFlow>(
            sequence = localProxySequenceSelectors(),
            stateOf = { it.state }
        )
        emitBridgeFinCommon(
            flowCache = localProxyTcpFlowCache,
            flowKey = flowKey,
            request = request,
            selectors = selectors,
            updateFlow = ::updateLocalProxyBridgeFinFlow
        )
    }

    private fun closeLocalProxyTcpFlow(flowKey: String, message: String) {
        closeBridgeFlowCommon(
            flowCache = localProxyTcpFlowCache,
            sessionCache = localProxyBridgeSocketCache,
            flowKey = flowKey,
            message = message,
            logKey = buildBridgeCloseLogKey("local-proxy-flow", flowKey)
        )
    }

    private fun emitLocalProxyBridgeReset(flowKey: String, request: com.HanFeng.model.PacketInfo, message: String) {
        val selectors = BridgeFlowSelectors<LocalProxyTcpFlow>(
            sequence = localProxySequenceSelectors()
        )
        emitBridgeResetCommon(
            flowCache = localProxyTcpFlowCache,
            flowKey = flowKey,
            request = request,
            message = message,
            selectors = selectors,
            closeFlow = ::closeLocalProxyTcpFlow
        )
    }

    private fun resolveLocalProxyTarget(flowKey: String): String {
        return synchronized(localProxyTcpFlowCache) {
            localProxyTcpFlowCache[flowKey]?.let { "${it.targetIp}:${it.targetPort}" }
        } ?: "unknown"
    }

    private fun decideSyntheticHandshake(
        info: com.HanFeng.model.PacketInfo,
        destinationPort: Int,
        hasFlow: Boolean,
        bridgePort: Int?,
        state: String
    ): HttpsHandshakeEngine.Decision {
        return HttpsHandshakeEngine.decide(
            HttpsHandshakeEngine.Input(
                protocol = info.protocol,
                destinationPort = destinationPort,
                hasFlow = hasFlow,
                bridgePort = bridgePort,
                state = state,
                syn = info.tcpFlags.hasTcpFlag(TCP_FLAG_SYN),
                ack = info.tcpFlags.hasTcpFlag(TCP_FLAG_ACK),
                fin = info.tcpFlags.hasTcpFlag(TCP_FLAG_FIN),
                rst = info.tcpFlags.hasTcpFlag(TCP_FLAG_RST),
                psh = info.tcpFlags.hasTcpFlag(TCP_FLAG_PSH),
                payloadLength = info.payload.size.toLong()
            )
        )
    }

    private fun shouldSkipHttpsTransparentProxyTracking(
        flowKey: String,
        ip: String,
        destinationPort: Int
    ): Boolean {
        if (!isLocalLoopOrProxyEndpoint(ip, destinationPort)) return false
        FlowCacheSupport.remove(httpsProxyFlowCache, flowKey)
        logDecisionOnce(
            key = "https-transparent-skip-local:$ip:$destinationPort",
            message = "Skipped HTTPS transparent proxy tracking for local endpoint ip=$ip port=$destinationPort",
            minIntervalMillis = 15_000L
        )
        return true
    }

    private fun shouldBlockHttpsTransparentProxySyn(
        flowKey: String,
        target: HttpsDecryptTarget,
        ip: String,
        info: com.HanFeng.model.PacketInfo
    ): Boolean {
        if (!info.tcpFlags.hasTcpFlag(TCP_FLAG_SYN) || info.tcpFlags.hasTcpFlag(TCP_FLAG_ACK)) return false
        val domainContext = resolveDomainDecisionContext(
            domain = target.domain,
            info = info,
            knownAppName = target.appName.takeIf { it.isNotBlank() },
            destinationPort = info.destinationPort,
            sourcePort = info.sourcePort
        )
        val appName = domainContext.appName
        val matchedRule = domainContext.matchedRule
        val vendor = domainContext.vendor
        if (!shouldTreatAsTrackedAdTarget(
                target.domain,
                appName,
                vendor,
                matchedRule,
                includeProtectedNovelUrl = false,
                includeForceNovelQuic = false
            )) {
            logDecisionOnce(
                key = "https-pass-syn:${target.domain}:$ip:${info.sourcePort}",
                message = "Passed HTTPS SYN domain=${target.domain} ip=$ip app=$appName vendor=$vendor reason=${domainContext.reason} source=${target.source}",
                minIntervalMillis = 30_000L
            )
            return false
        }
        StatsRepository.recordBlockedMitm(this, vendor, appName, 64 * 1024)
        LogRepository.append(
            this,
            "Blocked HTTPS connection at SYN domain=${target.domain} ip=$ip app=$appName vendor=$vendor reason=${domainContext.reason} source=${target.source} via=https-decrypt-entry"
        )
        FlowCacheSupport.remove(httpsProxyFlowCache, flowKey)
        return true
    }

    private fun shouldBypassHttpsTransparentProxyTracking(flowKey: String, domain: String): Boolean {
        val cooldownReason = HttpsMitmRepository.getActiveBypassReason(this, domain)
        if (cooldownReason != null) {
            FlowCacheSupport.remove(httpsProxyFlowCache, flowKey)
            return true
        }
        val existingSession = TlsMitmSessionManager.getSession(flowKey)
        if (existingSession?.bypassMitm == true) {
            FlowCacheSupport.remove(httpsProxyFlowCache, flowKey)
            return true
        }
        return false
    }

    private fun resolveHttpsTransparentPreparedBridge(
        flowKey: String,
        ip: String,
        info: com.HanFeng.model.PacketInfo,
        target: HttpsDecryptTarget
    ): PreparedTlsMitmSessionInfo {
        val currentProxyAppName = synchronized(httpsProxyFlowCache) {
            httpsProxyFlowCache[flowKey]?.appName?.takeIf { it.isNotBlank() }
        }
        val targetAppName = target.appName.takeIf { it.isNotBlank() }
            ?: currentProxyAppName
        val preparedSession = TlsMitmSessionManager.findPreparedSession(
            targetIp = ip,
            targetPort = info.destinationPort,
            appName = targetAppName
        )
        val resolvedProxyAppName = currentProxyAppName
            ?: preparedSession?.appName?.takeIf { it.isNotBlank() }
            ?: targetAppName
            ?: resolveAppName(target.domain, info)
        return PreparedTlsMitmSessionInfo(
            appName = resolvedProxyAppName,
            bridgeHost = preparedSession?.localBridgeHost,
            bridgePort = preparedSession?.localBridgePort
        )
    }

    private fun trackHttpsTransparentProxyFlow(
        flowKey: String,
        target: HttpsDecryptTarget,
        ip: String,
        info: com.HanFeng.model.PacketInfo,
        preparedBridge: PreparedTlsMitmSessionInfo
    ) {
        val flags = info.tcpFlags
        synchronized(httpsProxyFlowCache) {
            val current = httpsProxyFlowCache[flowKey]
            val nextState = HttpsPipeline.nextTransparentProxyState(
                flags = flags,
                hasPreparedBridgePort = preparedBridge.bridgePort != null,
                hasPayload = info.payload.isNotEmpty(),
                currentState = current?.state
            )
            FlowCacheSupport.putPruned(
                cache = httpsProxyFlowCache,
                key = flowKey,
                value = HttpsProxyFlow(
                    flowKey = flowKey,
                    domain = target.domain,
                    vendor = target.vendor,
                    source = target.source,
                    targetIp = ip,
                    sourcePort = info.sourcePort,
                    appName = preparedBridge.appName,
                    state = nextState,
                    bridgeHost = preparedBridge.bridgeHost,
                    bridgePort = preparedBridge.bridgePort,
                    clientInitialSequence = current?.clientInitialSequence,
                    serverInitialSequence = current?.serverInitialSequence,
                    clientNextSequence = current?.clientNextSequence,
                    serverNextSequence = current?.serverNextSequence,
                    lastServerPayloadSequence = current?.lastServerPayloadSequence,
                    lastServerPayload = current?.lastServerPayload,
                    pendingServerSegments = current?.pendingServerSegments.orEmpty(),
                    bufferedClientSegments = current?.bufferedClientSegments.orEmpty(),
                    lastClientPayloadSequence = current?.lastClientPayloadSequence,
                    lastClientPayloadLength = current?.lastClientPayloadLength,
                    lastSequenceNumber = info.tcpSequenceNumber,
                    lastAcknowledgementNumber = info.tcpAcknowledgementNumber,
                    lastSeenAt = System.currentTimeMillis()
                ),
                maxSize = 512
            )
        }
    }

    private fun trackLocalProxyTcpFlow(
        flowKey: String,
        targetContext: DestinationAppContext,
        info: com.HanFeng.model.PacketInfo
    ) {
        val flags = info.tcpFlags
        synchronized(localProxyTcpFlowCache) {
            val current = localProxyTcpFlowCache[flowKey]
            FlowCacheSupport.putPruned(
                cache = localProxyTcpFlowCache,
                key = flowKey,
                value = LocalProxyTcpFlow(
                    flowKey = flowKey,
                    targetIp = targetContext.destinationIp,
                    targetPort = info.destinationPort,
                    sourcePort = info.sourcePort,
                    appName = targetContext.appName,
                    state = resolveLocalProxyTcpFlowState(current?.state, flags, info.payload.isNotEmpty()),
                    bridgeHost = localProxyCoexistConfig.host,
                    bridgePort = localProxyCoexistConfig.port,
                    lastSequenceNumber = info.tcpSequenceNumber,
                    lastAcknowledgementNumber = info.tcpAcknowledgementNumber,
                    lastSeenAt = System.currentTimeMillis()
                ),
                maxSize = 512
            )
        }
    }

    private fun resolveLocalProxyTcpFlowState(
        currentState: String?,
        flags: Int,
        hasPayload: Boolean
    ): String {
        return when {
            flags.hasTcpFlag(TCP_FLAG_SYN) && !flags.hasTcpFlag(TCP_FLAG_ACK) -> "syn_seen"
            flags.hasTcpFlag(TCP_FLAG_SYN) && flags.hasTcpFlag(TCP_FLAG_ACK) -> "syn_ack_seen"
            flags.hasTcpFlag(TCP_FLAG_FIN) -> "fin_seen"
            flags.hasTcpFlag(TCP_FLAG_RST) -> "rst_seen"
            hasPayload -> "payload_seen"
            else -> currentState ?: "tracked"
        }
    }

    private fun buildSyntheticPacketState(info: com.HanFeng.model.PacketInfo): SyntheticPacketState {
        return SyntheticPacketState(
            sequenceNumber = info.tcpSequenceNumber ?: 0L,
            acknowledgementNumber = info.tcpAcknowledgementNumber ?: 0L,
            payloadLength = info.payload.size.toLong(),
            now = System.currentTimeMillis()
        )
    }

    private fun <TFlow> handleSyntheticSynOpen(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long,
        serverInitialSequenceOf: (TFlow) -> Long?,
        updateFlow: (TFlow, Long, Long) -> TFlow
    ) {
        val current = synchronized(flowCache) {
            flowCache[flowKey]
        } ?: return
        val serverSeq = serverInitialSequenceOf(current) ?: synthesizeServerSequence(flowKey)
        FlowCacheSupport.putPruned(flowCache, flowKey, updateFlow(current, serverSeq, now), 512)
        writeTunPacket(
            PacketCodec.buildTcpResponse(
                request = request,
                sequenceNumber = serverSeq,
                acknowledgementNumber = sequenceNumber + 1,
                flags = TCP_FLAG_SYN or TCP_FLAG_ACK,
                windowSize = request.tcpWindowSize ?: DEFAULT_TCP_WINDOW_SIZE
            )
        )
    }

    private fun <TFlow> handleSyntheticAckEstablish(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        now: Long,
        updateFlow: (TFlow) -> TFlow
    ) {
        synchronized(flowCache) {
            val current = flowCache[flowKey] ?: return@synchronized
            FlowCacheSupport.putPruned(flowCache, flowKey, updateFlow(current), 512)
        }
    }

    private fun <TFlow, TSession> handleSyntheticClientFin(
        flowCache: MutableMap<String, TFlow>,
        sessionCache: MutableMap<String, TSession>,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        clientSequenceNumber: Long,
        payloadLength: Long,
        now: Long,
        serverInitialSequenceOf: (TFlow) -> Long?,
        serverNextSequenceOf: (TFlow) -> Long?,
        updateFlow: (TFlow, BridgeLifecycleSupport.ClientFinTransition) -> TFlow,
        closeSession: (TSession) -> Unit
    ) {
        val current = synchronized(flowCache) {
            flowCache[flowKey]
        } ?: return
        val transition = BridgeLifecycleSupport.resolveClientFinTransition(
            serverInitialSequence = serverInitialSequenceOf(current),
            serverNextSequence = serverNextSequenceOf(current),
            clientSequenceNumber = clientSequenceNumber,
            payloadLength = payloadLength,
            now = now,
            synthesizeServerSequence = { synthesizeServerSequence(flowKey) }
        )
        FlowCacheSupport.updateIfPresent(flowCache, flowKey) {
            updateFlow(it, transition)
        }
        writeTunPacket(
            PacketCodec.buildTcpResponse(
                request = request,
                sequenceNumber = transition.nextServerSequenceToSend,
                acknowledgementNumber = transition.clientAcknowledgement,
                flags = TCP_FLAG_FIN or TCP_FLAG_ACK,
                windowSize = request.tcpWindowSize ?: DEFAULT_TCP_WINDOW_SIZE
            )
        )
        BridgeSessionSupport.removeAndClose(sessionCache, flowKey, closeSession)
    }

    private fun handleSyntheticTerminalClose(
        flowKey: String,
        message: String,
        closeFlow: (String, String) -> Unit
    ) {
        closeFlow(flowKey, message)
    }

    private fun closeSyntheticFlowAndReturnTrue(
        flowKey: String,
        message: String,
        closeFlow: (String, String) -> Unit
    ): Boolean {
        handleSyntheticTerminalClose(flowKey, message, closeFlow)
        return true
    }

    private data class SyntheticSynAckState(
        val sequenceNumber: Long,
        val acknowledgementNumber: Long,
        val serverSequenceNumber: Long,
        val lastSeenAt: Long
    )

    private fun buildSyntheticSynAckState(
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        serverSequenceNumber: Long,
        lastSeenAt: Long
    ): SyntheticSynAckState {
        return SyntheticSynAckState(
            sequenceNumber,
            acknowledgementNumber,
            serverSequenceNumber,
            lastSeenAt
        )
    }

    private fun applyLocalProxySynAckState(
        flowState: LocalProxyTcpFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        serverSequenceNumber: Long,
        lastSeenAt: Long
    ): LocalProxyTcpFlow {
        val synAckState = buildSyntheticSynAckState(
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            serverSequenceNumber = serverSequenceNumber,
            lastSeenAt = lastSeenAt
        )
        return flowState.copy(
            state = "syn_ack_sent",
            clientInitialSequence = synAckState.sequenceNumber,
            serverInitialSequence = synAckState.serverSequenceNumber,
            lastSequenceNumber = synAckState.sequenceNumber,
            lastAcknowledgementNumber = synAckState.acknowledgementNumber,
            lastSeenAt = synAckState.lastSeenAt
        )
    }

    private fun applyHttpsSynAckState(
        flowState: HttpsProxyFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        serverSequenceNumber: Long,
        lastSeenAt: Long
    ): HttpsProxyFlow {
        val synAckState = buildSyntheticSynAckState(
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            serverSequenceNumber = serverSequenceNumber,
            lastSeenAt = lastSeenAt
        )
        return flowState.copy(
            state = "syn_ack_sent",
            clientInitialSequence = synAckState.sequenceNumber,
            serverInitialSequence = synAckState.serverSequenceNumber,
            lastSequenceNumber = synAckState.sequenceNumber,
            lastAcknowledgementNumber = synAckState.acknowledgementNumber,
            lastSeenAt = synAckState.lastSeenAt
        )
    }

    private fun applyPassthroughSynAckState(
        flowState: PassthroughTcpFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        serverSequenceNumber: Long,
        lastSeenAt: Long
    ): PassthroughTcpFlow {
        val synAckState = buildSyntheticSynAckState(
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            serverSequenceNumber = serverSequenceNumber,
            lastSeenAt = lastSeenAt
        )
        return flowState.copy(
            state = "syn_ack_sent",
            clientInitialSequence = synAckState.sequenceNumber,
            serverInitialSequence = synAckState.serverSequenceNumber,
            lastSequenceNumber = synAckState.sequenceNumber,
            lastAcknowledgementNumber = synAckState.acknowledgementNumber,
            lastSeenAt = synAckState.lastSeenAt
        )
    }

    private data class SyntheticAckEstablishedState(
        val state: String,
        val sequenceNumber: Long,
        val acknowledgementNumber: Long,
        val lastSeenAt: Long
    )

    private fun buildSyntheticAckEstablishedState(
        state: String,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): SyntheticAckEstablishedState {
        return SyntheticAckEstablishedState(
            state = state,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            lastSeenAt = now
        )
    }

    private fun applyLocalProxyAckEstablishedState(
        flowState: LocalProxyTcpFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): LocalProxyTcpFlow {
        val establishedState = buildSyntheticAckEstablishedState(
            state = "bridge_connecting",
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            now = now
        )
        return flowState.copy(
            state = establishedState.state,
            lastSequenceNumber = establishedState.sequenceNumber,
            lastAcknowledgementNumber = establishedState.acknowledgementNumber,
            clientNextSequence = establishedState.sequenceNumber,
            lastSeenAt = establishedState.lastSeenAt
        )
    }

    private fun applyHttpsAckEstablishedState(
        flowState: HttpsProxyFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): HttpsProxyFlow {
        val establishedState = buildSyntheticAckEstablishedState(
            state = "established",
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            now = now
        )
        return flowState.copy(
            state = establishedState.state,
            lastSequenceNumber = establishedState.sequenceNumber,
            lastAcknowledgementNumber = establishedState.acknowledgementNumber,
            clientNextSequence = establishedState.sequenceNumber,
            lastSeenAt = establishedState.lastSeenAt
        )
    }

    private fun applyPassthroughAckEstablishedState(
        flowState: PassthroughTcpFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): PassthroughTcpFlow {
        val establishedState = buildSyntheticAckEstablishedState(
            state = "established",
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            now = now
        )
        return flowState.copy(
            state = establishedState.state,
            lastSequenceNumber = establishedState.sequenceNumber,
            lastAcknowledgementNumber = establishedState.acknowledgementNumber,
            clientNextSequence = establishedState.sequenceNumber,
            lastSeenAt = establishedState.lastSeenAt
        )
    }

    private fun <TFlow> buildSyntheticAckedServerState(
        nextAckState: ServerAckStateSupport.AckStateTransition,
        updateFlow: (
            state: String,
            lastSequenceNumber: Long,
            lastAcknowledgementNumber: Long,
            lastSeenAt: Long
        ) -> TFlow
    ): TFlow {
        return updateFlow(
            nextAckState.nextState,
            nextAckState.lastSequenceNumber,
            nextAckState.lastAcknowledgementNumber,
            nextAckState.lastSeenAt
        )
    }

    private fun <TFlow> buildSyntheticClientFinState(
        transition: BridgeLifecycleSupport.ClientFinTransition,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        updateFlow: (
            state: String,
            lastSequenceNumber: Long,
            lastAcknowledgementNumber: Long,
            serverNextSequence: Long?,
            clientNextSequence: Long,
            lastSeenAt: Long
        ) -> TFlow
    ): TFlow {
        return updateFlow(
            transition.state,
            sequenceNumber,
            acknowledgementNumber,
            transition.storedServerNextSequence,
            transition.clientAcknowledgement,
            transition.lastSeenAt
        )
    }

    private fun <TFlow> buildBufferedClientSegmentsState(
        updatedBufferedSegments: List<ClientPayloadSegment>,
        now: Long,
        updateFlow: (bufferedClientSegments: List<ClientPayloadSegment>, lastSeenAt: Long) -> TFlow
    ): TFlow {
        return updateFlow(updatedBufferedSegments, now)
    }

    private fun <TFlow> buildRetransmittedServerSegmentsState(
        remainingSegments: List<PendingServerSegment>,
        now: Long,
        updateFlow: (pendingServerSegments: List<PendingServerSegment>, lastSeenAt: Long) -> TFlow
    ): TFlow {
        return updateFlow(remainingSegments, now)
    }

    private fun <TFlow> applySyntheticAckedServerState(
        flowState: TFlow,
        nextAckState: ServerAckStateSupport.AckStateTransition,
        updateFlow: (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow
    ): TFlow {
        return updateFlow(flowState, nextAckState)
    }

    private fun <TFlow> applyBufferedClientSegmentsState(
        flowState: TFlow,
        updatedBufferedSegments: List<ClientPayloadSegment>,
        now: Long,
        updateFlow: (TFlow, List<ClientPayloadSegment>, Long) -> TFlow
    ): TFlow {
        return updateFlow(flowState, updatedBufferedSegments, now)
    }

    private fun <TFlow> applyRetransmittedServerSegmentsState(
        flowState: TFlow,
        remainingSegments: List<PendingServerSegment>,
        now: Long,
        updateFlow: (TFlow, List<PendingServerSegment>, Long) -> TFlow
    ): TFlow {
        return updateFlow(flowState, remainingSegments, now)
    }

    private fun <TFlow> applySyntheticClientFinState(
        flowState: TFlow,
        transition: BridgeLifecycleSupport.ClientFinTransition,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        updateFlow: (TFlow, BridgeLifecycleSupport.ClientFinTransition, Long, Long) -> TFlow
    ): TFlow {
        return updateFlow(flowState, transition, sequenceNumber, acknowledgementNumber)
    }

    private fun resolveClientPayloadState(bridgeConnected: Boolean): String {
        return if (bridgeConnected) "payload_acknowledged" else "bridge_connecting"
    }

    private fun resolveClientPayloadBufferedSegments(
        flushResult: ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>,
        bridgeConnected: Boolean
    ): List<ClientPayloadSegment> {
        return if (bridgeConnected) {
            flushResult.remainingSegments
        } else {
            mergeBufferedClientSegments(flushResult.remainingSegments, flushResult.forwardSegments)
        }
    }

    private fun resolveClientPayloadSequence(
        flowSequence: Long?,
        lastForwardedSegment: ClientPayloadSegment?,
        bridgeConnected: Boolean
    ): Long? {
        return if (bridgeConnected) {
            lastForwardedSegment?.sequenceNumber ?: flowSequence
        } else {
            flowSequence
        }
    }

    private fun resolveClientPayloadLength(
        flowLength: Long?,
        lastForwardedSegment: ClientPayloadSegment?,
        bridgeConnected: Boolean
    ): Long? {
        return if (bridgeConnected) {
            lastForwardedSegment?.payload?.size?.toLong() ?: flowLength
        } else {
            flowLength
        }
    }

    private fun applySyntheticAckedServerState(
        flowState: LocalProxyTcpFlow,
        nextAckState: ServerAckStateSupport.AckStateTransition
    ): LocalProxyTcpFlow {
        return applySyntheticAckedServerState(flowState, nextAckState) { activeFlowState, activeAckState ->
            buildSyntheticAckedServerState(activeAckState) { state, lastSequenceNumber, lastAcknowledgementNumber, lastSeenAt ->
                activeFlowState.copy(
                    state = state,
                    pendingServerSegments = emptyList(),
                    lastSequenceNumber = lastSequenceNumber,
                    lastAcknowledgementNumber = lastAcknowledgementNumber,
                    lastSeenAt = lastSeenAt
                )
            }
        }
    }

    private fun applySyntheticAckedServerState(
        flowState: HttpsProxyFlow,
        nextAckState: ServerAckStateSupport.AckStateTransition
    ): HttpsProxyFlow {
        return applySyntheticAckedServerState(flowState, nextAckState) { activeFlowState, activeAckState ->
            buildSyntheticAckedServerState(activeAckState) { state, lastSequenceNumber, lastAcknowledgementNumber, lastSeenAt ->
                activeFlowState.copy(
                    state = state,
                    pendingServerSegments = emptyList(),
                    lastSequenceNumber = lastSequenceNumber,
                    lastAcknowledgementNumber = lastAcknowledgementNumber,
                    lastSeenAt = lastSeenAt
                )
            }
        }
    }

    private fun applySyntheticAckedServerState(
        flowState: PassthroughTcpFlow,
        nextAckState: ServerAckStateSupport.AckStateTransition
    ): PassthroughTcpFlow {
        return applySyntheticAckedServerState(flowState, nextAckState) { activeFlowState, activeAckState ->
            buildSyntheticAckedServerState(activeAckState) { state, lastSequenceNumber, lastAcknowledgementNumber, lastSeenAt ->
                activeFlowState.copy(
                    state = state,
                    pendingServerSegments = emptyList(),
                    lastSequenceNumber = lastSequenceNumber,
                    lastAcknowledgementNumber = lastAcknowledgementNumber,
                    lastSeenAt = lastSeenAt
                )
            }
        }
    }

    private fun applySyntheticClientFinState(
        flowState: LocalProxyTcpFlow,
        transition: BridgeLifecycleSupport.ClientFinTransition,
        sequenceNumber: Long,
        acknowledgementNumber: Long
    ): LocalProxyTcpFlow {
        return applySyntheticClientFinState(flowState, transition, sequenceNumber, acknowledgementNumber) {
                activeFlowState,
                activeTransition,
                activeSequenceNumber,
                activeAcknowledgementNumber ->
            buildSyntheticClientFinState(activeTransition, activeSequenceNumber, activeAcknowledgementNumber) {
                    state,
                    lastSequenceNumber,
                    lastAcknowledgementNumber,
                    serverNextSequence,
                    clientNextSequence,
                    lastSeenAt ->
                activeFlowState.copy(
                    state = state,
                    lastSequenceNumber = lastSequenceNumber,
                    lastAcknowledgementNumber = lastAcknowledgementNumber,
                    serverNextSequence = serverNextSequence,
                    clientNextSequence = clientNextSequence,
                    lastSeenAt = lastSeenAt
                )
            }
        }
    }

    private fun applySyntheticClientFinState(
        flowState: HttpsProxyFlow,
        transition: BridgeLifecycleSupport.ClientFinTransition,
        sequenceNumber: Long,
        acknowledgementNumber: Long
    ): HttpsProxyFlow {
        return applySyntheticClientFinState(flowState, transition, sequenceNumber, acknowledgementNumber) {
                activeFlowState,
                activeTransition,
                activeSequenceNumber,
                activeAcknowledgementNumber ->
            buildSyntheticClientFinState(activeTransition, activeSequenceNumber, activeAcknowledgementNumber) {
                    state,
                    lastSequenceNumber,
                    lastAcknowledgementNumber,
                    serverNextSequence,
                    clientNextSequence,
                    lastSeenAt ->
                activeFlowState.copy(
                    state = state,
                    lastSequenceNumber = lastSequenceNumber,
                    lastAcknowledgementNumber = lastAcknowledgementNumber,
                    serverNextSequence = serverNextSequence,
                    clientNextSequence = clientNextSequence,
                    lastSeenAt = lastSeenAt
                )
            }
        }
    }

    private fun applySyntheticClientFinState(
        flowState: PassthroughTcpFlow,
        transition: BridgeLifecycleSupport.ClientFinTransition,
        sequenceNumber: Long,
        acknowledgementNumber: Long
    ): PassthroughTcpFlow {
        return applySyntheticClientFinState(flowState, transition, sequenceNumber, acknowledgementNumber) {
                activeFlowState,
                activeTransition,
                activeSequenceNumber,
                activeAcknowledgementNumber ->
            buildSyntheticClientFinState(activeTransition, activeSequenceNumber, activeAcknowledgementNumber) {
                    state,
                    lastSequenceNumber,
                    lastAcknowledgementNumber,
                    serverNextSequence,
                    clientNextSequence,
                    lastSeenAt ->
                activeFlowState.copy(
                    state = state,
                    lastSequenceNumber = lastSequenceNumber,
                    lastAcknowledgementNumber = lastAcknowledgementNumber,
                    serverNextSequence = serverNextSequence,
                    clientNextSequence = clientNextSequence,
                    lastSeenAt = lastSeenAt
                )
            }
        }
    }

    private fun applyBufferedClientSegmentsState(
        flowState: LocalProxyTcpFlow,
        updatedBufferedSegments: List<ClientPayloadSegment>,
        now: Long
    ): LocalProxyTcpFlow {
        return applyBufferedClientSegmentsState(flowState, updatedBufferedSegments, now) { activeFlowState, bufferedClientSegments, lastSeenAt ->
            buildBufferedClientSegmentsState(bufferedClientSegments, lastSeenAt) { nextBufferedClientSegments, nextLastSeenAt ->
                activeFlowState.copy(
                    bufferedClientSegments = nextBufferedClientSegments,
                    lastSeenAt = nextLastSeenAt
                )
            }
        }
    }

    private fun applyBufferedClientSegmentsState(
        flowState: HttpsProxyFlow,
        updatedBufferedSegments: List<ClientPayloadSegment>,
        now: Long
    ): HttpsProxyFlow {
        return applyBufferedClientSegmentsState(flowState, updatedBufferedSegments, now) { activeFlowState, bufferedClientSegments, lastSeenAt ->
            buildBufferedClientSegmentsState(bufferedClientSegments, lastSeenAt) { nextBufferedClientSegments, nextLastSeenAt ->
                activeFlowState.copy(
                    bufferedClientSegments = nextBufferedClientSegments,
                    lastSeenAt = nextLastSeenAt
                )
            }
        }
    }

    private fun applyBufferedClientSegmentsState(
        flowState: PassthroughTcpFlow,
        updatedBufferedSegments: List<ClientPayloadSegment>,
        now: Long
    ): PassthroughTcpFlow {
        return applyBufferedClientSegmentsState(flowState, updatedBufferedSegments, now) { activeFlowState, bufferedClientSegments, lastSeenAt ->
            buildBufferedClientSegmentsState(bufferedClientSegments, lastSeenAt) { nextBufferedClientSegments, nextLastSeenAt ->
                activeFlowState.copy(
                    bufferedClientSegments = nextBufferedClientSegments,
                    lastSeenAt = nextLastSeenAt
                )
            }
        }
    }

    private fun applyRetransmittedServerSegmentsState(
        flowState: LocalProxyTcpFlow,
        remainingSegments: List<PendingServerSegment>,
        now: Long
    ): LocalProxyTcpFlow {
        return applyRetransmittedServerSegmentsState(flowState, remainingSegments, now) { activeFlowState, pendingServerSegments, lastSeenAt ->
            buildRetransmittedServerSegmentsState(pendingServerSegments, lastSeenAt) { nextPendingServerSegments, nextLastSeenAt ->
                activeFlowState.copy(
                    pendingServerSegments = nextPendingServerSegments,
                    lastSeenAt = nextLastSeenAt
                )
            }
        }
    }

    private fun applyRetransmittedServerSegmentsState(
        flowState: HttpsProxyFlow,
        remainingSegments: List<PendingServerSegment>,
        now: Long
    ): HttpsProxyFlow {
        return applyRetransmittedServerSegmentsState(flowState, remainingSegments, now) { activeFlowState, pendingServerSegments, lastSeenAt ->
            buildRetransmittedServerSegmentsState(pendingServerSegments, lastSeenAt) { nextPendingServerSegments, nextLastSeenAt ->
                activeFlowState.copy(
                    pendingServerSegments = nextPendingServerSegments,
                    lastSeenAt = nextLastSeenAt
                )
            }
        }
    }

    private fun applyRetransmittedServerSegmentsState(
        flowState: PassthroughTcpFlow,
        remainingSegments: List<PendingServerSegment>,
        now: Long
    ): PassthroughTcpFlow {
        return applyRetransmittedServerSegmentsState(flowState, remainingSegments, now) { activeFlowState, pendingServerSegments, lastSeenAt ->
            buildRetransmittedServerSegmentsState(pendingServerSegments, lastSeenAt) { nextPendingServerSegments, nextLastSeenAt ->
                activeFlowState.copy(
                    pendingServerSegments = nextPendingServerSegments,
                    lastSeenAt = nextLastSeenAt
                )
            }
        }
    }

    private fun applyLocalProxyClientPayloadState(
        flowState: LocalProxyTcpFlow,
        flushResult: ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>,
        bridgeConnected: Boolean,
        lastForwardedSegment: ClientPayloadSegment?,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): LocalProxyTcpFlow {
        return flowState.copy(
            state = resolveClientPayloadState(bridgeConnected),
            lastSequenceNumber = sequenceNumber,
            lastAcknowledgementNumber = acknowledgementNumber,
            clientNextSequence = flushResult.nextExpectedSequence,
            bufferedClientSegments = resolveClientPayloadBufferedSegments(flushResult, bridgeConnected),
            lastClientPayloadSequence = resolveClientPayloadSequence(
                flowSequence = flowState.lastClientPayloadSequence,
                lastForwardedSegment = lastForwardedSegment,
                bridgeConnected = bridgeConnected
            ),
            lastClientPayloadLength = resolveClientPayloadLength(
                flowLength = flowState.lastClientPayloadLength,
                lastForwardedSegment = lastForwardedSegment,
                bridgeConnected = bridgeConnected
            ),
            lastSeenAt = now
        )
    }

    private fun applyHttpsClientPayloadState(
        flowState: HttpsProxyFlow,
        flushResult: ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>,
        bridgeConnected: Boolean,
        lastForwardedSegment: ClientPayloadSegment?,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): HttpsProxyFlow {
        return flowState.copy(
            state = "payload_acknowledged",
            lastSequenceNumber = sequenceNumber,
            lastAcknowledgementNumber = acknowledgementNumber,
            clientNextSequence = flushResult.nextExpectedSequence,
            bufferedClientSegments = resolveClientPayloadBufferedSegments(
                flushResult = flushResult,
                bridgeConnected = bridgeConnected
            ),
            lastClientPayloadSequence = resolveClientPayloadSequence(
                flowSequence = flowState.lastClientPayloadSequence,
                lastForwardedSegment = lastForwardedSegment,
                bridgeConnected = bridgeConnected
            ),
            lastClientPayloadLength = resolveClientPayloadLength(
                flowLength = flowState.lastClientPayloadLength,
                lastForwardedSegment = lastForwardedSegment,
                bridgeConnected = bridgeConnected
            ),
            lastSeenAt = now
        )
    }

    private fun applyPassthroughClientPayloadState(
        flowState: PassthroughTcpFlow,
        flushResult: ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>,
        bridgeConnected: Boolean,
        lastForwardedSegment: ClientPayloadSegment?,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): PassthroughTcpFlow {
        return flowState.copy(
            state = resolveClientPayloadState(bridgeConnected),
            lastSequenceNumber = sequenceNumber,
            lastAcknowledgementNumber = acknowledgementNumber,
            clientNextSequence = flushResult.nextExpectedSequence,
            bufferedClientSegments = resolveClientPayloadBufferedSegments(flushResult, bridgeConnected),
            lastClientPayloadSequence = resolveClientPayloadSequence(
                flowSequence = flowState.lastClientPayloadSequence,
                lastForwardedSegment = lastForwardedSegment,
                bridgeConnected = bridgeConnected
            ),
            lastClientPayloadLength = resolveClientPayloadLength(
                flowLength = flowState.lastClientPayloadLength,
                lastForwardedSegment = lastForwardedSegment,
                bridgeConnected = bridgeConnected
            ),
            lastSeenAt = now
        )
    }

    private fun logHandshakeDecisionAndReturnTrue(
        key: String,
        message: String,
        minIntervalMillis: Long
    ): Boolean {
        logDecisionOnce(key, message, minIntervalMillis)
        return true
    }

    private fun <TSession, TConnected> ensureBridgeSocketConnected(
        sessionCache: MutableMap<String, TSession>,
        flowKey: String,
        bridgeHost: String?,
        bridgePort: Int?,
        connectBridge: (String, Int) -> TConnected,
        createSession: (TConnected) -> TSession,
        onConnected: (TSession, TConnected) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        synchronized(sessionCache) {
            if (sessionCache.containsKey(flowKey)) return
        }
        val host = bridgeHost ?: return
        val port = bridgePort ?: return
        scope.launch {
            runCatching {
                val connected = connectBridge(host, port)
                val session = createSession(connected)
                BridgeLifecycleSupport.registerConnectedSession(sessionCache, flowKey, session)
                onConnected(session, connected)
            }.onFailure(onFailure)
        }
    }

    private fun <TSession> forwardPayloadToBridge(
        sessionCache: MutableMap<String, TSession>,
        flowKey: String,
        payload: ByteArray,
        writePayload: (TSession, ByteArray) -> Unit,
        onFailure: (TSession, Throwable) -> Unit
    ) {
        val session = synchronized(sessionCache) {
            sessionCache[flowKey]
        } ?: return
        BridgeSocketSupport.launchWriter(
            scope = scope,
            payload = payload,
            write = { writePayload(session, payload) },
            onFailure = { onFailure(session, it) }
        )
    }

    private fun buildHttpsBridgeReaderConfig(
        session: HttpsBridgeSocketSession
    ): BridgeReaderConfig<HttpsBridgeSocketSession, HttpsProxyFlow> {
        return BridgeReaderConfig(
            flowCache = httpsProxyFlowCache,
            sessionCache = httpsBridgeSocketCache,
            onPayload = { payload ->
                emitHttpsBridgePayload(session.flowKey, session.requestTemplate, payload)
            },
            onFailure = buildHttpsBridgeFailureHandler(
                action = "Read HTTPS bridge payload failed",
                flowKey = session.flowKey,
                request = session.requestTemplate,
                stage = "read"
            ),
            onResetNotSent = {
                emitHttpsBridgeFin(session.flowKey, session.requestTemplate)
            },
            closeSession = { it.close() },
            updateFlow = ::updateHttpsBridgeSocketClosedFlow
        )
    }

    private fun buildLocalProxyBridgeReaderConfig(
        session: LocalProxyBridgeSocketSession
    ): BridgeReaderConfig<LocalProxyBridgeSocketSession, LocalProxyTcpFlow> {
        return BridgeReaderConfig(
            flowCache = localProxyTcpFlowCache,
            sessionCache = localProxyBridgeSocketCache,
            onPayload = { payload ->
                emitLocalProxyBridgePayload(session.flowKey, session.requestTemplate, payload)
            },
            onFailure = buildLocalProxyBridgeFailureHandler(
                action = "Read local proxy bridge payload failed",
                flowKey = session.flowKey,
                request = session.requestTemplate,
                stage = "read"
            ),
            onResetNotSent = {
                emitLocalProxyBridgeFin(session.flowKey, session.requestTemplate)
            },
            closeSession = { it.close() },
            updateFlow = ::updateLocalProxyBridgeSocketClosedFlow
        )
    }

    private fun buildPassthroughTcpReaderConfig(
        session: PassthroughTcpSocketSession
    ): BridgeReaderConfig<PassthroughTcpSocketSession, PassthroughTcpFlow> {
        return BridgeReaderConfig(
            flowCache = passthroughTcpFlowCache,
            sessionCache = passthroughTcpSocketCache,
            onPayload = { payload ->
                emitPassthroughTcpPayload(session.flowKey, session.requestTemplate, payload)
            },
            onFailure = buildPassthroughTcpFailureHandler(
                action = "Read passthrough TCP payload failed",
                flowKey = session.flowKey,
                request = session.requestTemplate,
                stage = "read"
            ),
            onResetNotSent = {
                emitPassthroughTcpFin(session.flowKey, session.requestTemplate)
            },
            closeSession = { it.close() },
            updateFlow = ::updatePassthroughBridgeSocketClosedFlow
        )
    }

    private fun <TSession, TFlow> runBridgeReaderConfigured(
        session: TSession,
        input: InputStream,
        flowKey: String,
        config: BridgeReaderConfig<TSession, TFlow>
    ) {
        runBridgeReader(
            session = session,
            input = input,
            flowCache = config.flowCache,
            sessionCache = config.sessionCache,
            flowKey = flowKey,
            onPayload = config.onPayload,
            onFailure = config.onFailure,
            onResetNotSent = config.onResetNotSent,
            closeSession = config.closeSession,
            updateFlow = config.updateFlow
        )
    }

    private fun <TSession, TFlow> runBridgeReader(
        session: TSession,
        input: InputStream,
        flowCache: MutableMap<String, TFlow>,
        sessionCache: MutableMap<String, TSession>,
        flowKey: String,
        onPayload: (ByteArray) -> Unit,
        onFailure: (Throwable) -> Unit,
        onResetNotSent: () -> Unit,
        closeSession: (TSession) -> Unit,
        updateFlow: (TFlow, Long) -> TFlow
    ) {
        BridgeSocketSupport.runReaderLoop(
            input = input,
            bufferSize = 16 * 1024,
            shouldContinue = { scope.isActive && isRunning },
            onPayload = onPayload,
            onFailure = onFailure,
            onComplete = { resetSent ->
                if (!resetSent) {
                    onResetNotSent()
                }
                BridgeReaderSupport.completeBridgeReader(
                    flowCache = flowCache,
                    sessionCache = sessionCache,
                    flowKey = flowKey,
                    now = System.currentTimeMillis(),
                    closeSession = closeSession,
                    updateFlow = updateFlow
                )
            }
        )
    }

    private fun runSyntheticHandshakeStep(step: () -> Unit): Boolean {
        step()
        return true
    }

    private data class PreparedTlsMitmSessionInfo(
        val appName: String,
        val bridgeHost: String?,
        val bridgePort: Int?
    )

    private data class SyntheticPacketState(
        val sequenceNumber: Long,
        val acknowledgementNumber: Long,
        val payloadLength: Long,
        val now: Long
    )

    private enum class SyntheticFlowCloseAction {
        BRIDGE_FIN,
        CLOSED,
        RESET
    }

    private fun <TFlow> handleSyntheticSynOpenAndReturnTrue(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long,
        serverInitialSequenceOf: (TFlow) -> Long?,
        updateFlow: (TFlow, Long, Long) -> TFlow,
        afterStep: (() -> Unit)? = null
    ): Boolean {
        return runSyntheticHandshakeStep {
            handleSyntheticSynOpen(
                flowCache = flowCache,
                flowKey = flowKey,
                request = request,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                now = now,
                serverInitialSequenceOf = serverInitialSequenceOf,
                updateFlow = updateFlow
            )
            afterStep?.invoke()
        }
    }

    private fun <TFlow> handleSyntheticAckEstablishAndReturnTrue(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        now: Long,
        ensureBridge: () -> Unit,
        updateFlow: (TFlow) -> TFlow,
        afterStep: (() -> Unit)? = null
    ): Boolean {
        return runSyntheticHandshakeStep {
            ensureBridge()
            handleSyntheticAckEstablish(
                flowCache = flowCache,
                flowKey = flowKey,
                now = now,
                updateFlow = updateFlow
            )
            afterStep?.invoke()
        }
    }

    private fun <TFlow, TSession> handleSyntheticClientFinAndReturnTrue(
        flowCache: MutableMap<String, TFlow>,
        sessionCache: MutableMap<String, TSession>,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        clientSequenceNumber: Long,
        payloadLength: Long,
        now: Long,
        serverInitialSequenceOf: (TFlow) -> Long?,
        serverNextSequenceOf: (TFlow) -> Long?,
        updateFlow: (TFlow, BridgeLifecycleSupport.ClientFinTransition) -> TFlow,
        closeSession: (TSession) -> Unit,
        afterStep: (() -> Unit)? = null
    ): Boolean {
        handleSyntheticClientFin(
            flowCache = flowCache,
            sessionCache = sessionCache,
            flowKey = flowKey,
            request = request,
            clientSequenceNumber = clientSequenceNumber,
            payloadLength = payloadLength,
            now = now,
            serverInitialSequenceOf = serverInitialSequenceOf,
            serverNextSequenceOf = serverNextSequenceOf,
            updateFlow = updateFlow,
            closeSession = closeSession
        )
        afterStep?.invoke()
        return true
    }

    private fun <TFlow> handleSyntheticClientPayloadResult(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        current: TFlow,
        request: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long,
        serverInitialSequenceOf: (TFlow) -> Long?,
        clientInitialSequenceOf: (TFlow) -> Long?,
        clientNextSequenceOf: (TFlow) -> Long?,
        bufferedSegmentsOf: (TFlow) -> List<ClientPayloadSegment>,
        lastClientPayloadSequenceOf: (TFlow) -> Long?,
        lastClientPayloadLengthOf: (TFlow) -> Long?,
        isBridgeConnected: () -> Boolean,
        forwardPayload: (ByteArray) -> Unit,
        onBufferOverflow: () -> Unit,
        onReplayOverflow: () -> Unit,
        updateOutOfOrderFlow: (TFlow, List<ClientPayloadSegment>) -> TFlow,
        updateFlushFlow: (TFlow, ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>, Boolean, ClientPayloadSegment?) -> TFlow,
        onResult: ((SyntheticClientPayloadResult) -> Boolean)? = null
    ): Boolean {
        return handleSyntheticResult(onResult) {
            handleSyntheticClientPayload(
                flowCache = flowCache,
                flowKey = flowKey,
                current = current,
                request = request,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                payloadLength = payloadLength,
                now = now,
                serverInitialSequenceOf = serverInitialSequenceOf,
                clientInitialSequenceOf = clientInitialSequenceOf,
                clientNextSequenceOf = clientNextSequenceOf,
                bufferedSegmentsOf = bufferedSegmentsOf,
                lastClientPayloadSequenceOf = lastClientPayloadSequenceOf,
                lastClientPayloadLengthOf = lastClientPayloadLengthOf,
                isBridgeConnected = isBridgeConnected,
                forwardPayload = forwardPayload,
                onBufferOverflow = onBufferOverflow,
                onReplayOverflow = onReplayOverflow,
                updateOutOfOrderFlow = updateOutOfOrderFlow,
                updateFlushFlow = updateFlushFlow
            )
        }
    }

    private fun <TFlow> handleSyntheticServerAckResult(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        current: TFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long,
        pendingSegmentsOf: (TFlow) -> List<PendingServerSegment>,
        serverNextSequenceOf: (TFlow) -> Long?,
        stateOf: (TFlow) -> String,
        resendPendingSegments: (List<PendingServerSegment>) -> Unit,
        updateRetransmitFlow: (TFlow, List<PendingServerSegment>) -> TFlow,
        updateAckedFlow: (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow,
        onResult: ((SyntheticServerAckResult) -> Boolean)? = null
    ): Boolean {
        return handleSyntheticResult(onResult) {
            handleSyntheticServerAck(
                flowCache = flowCache,
                flowKey = flowKey,
                current = current,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                now = now,
                pendingSegmentsOf = pendingSegmentsOf,
                serverNextSequenceOf = serverNextSequenceOf,
                stateOf = stateOf,
                resendPendingSegments = resendPendingSegments,
                updateRetransmitFlow = updateRetransmitFlow,
                updateAckedFlow = updateAckedFlow
            )
        }
    }

    private fun <TResult> handleSyntheticResult(
        onResult: ((TResult) -> Boolean)?,
        computeResult: () -> TResult
    ): Boolean {
        val result = computeResult()
        return onResult?.invoke(result) ?: true
    }

    private fun logHttpsPayloadHandshakeResult(
        flowKey: String,
        current: HttpsProxyFlow,
        sequenceNumber: Long,
        payloadLength: Long,
        payloadResult: SyntheticClientPayloadResult
    ): Boolean {
        val updatedBufferedSegments = payloadResult.outOfOrderBufferedSegments
        if (updatedBufferedSegments != null) {
            return logHandshakeDecisionAndReturnTrue(
                key = "https-proxy-handshake:$flowKey:out-of-order",
                message = "Buffered out-of-order HTTPS proxy payload domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} seq=$sequenceNumber expected=${payloadResult.expectedClientSequence} bufferedSegments=${updatedBufferedSegments.size} bufferedBytes=${bufferedClientPayloadBytes(updatedBufferedSegments)}",
                minIntervalMillis = 3_000L
            )
        }
        val flushResult = payloadResult.flushResult ?: return true
        return logHandshakeDecisionAndReturnTrue(
            key = "https-proxy-handshake:$flowKey:payload-ack",
            message = if (payloadResult.isRetransmission) {
                "Acknowledged retransmitted HTTPS proxy payload domain=${current.domain} source=${current.source} size=$payloadLength bridge=${current.bridgeHost}:${current.bridgePort} nextClientSeq=${flushResult.nextExpectedSequence} bufferedSegments=${flushResult.remainingSegments.size} bufferedBytes=${bufferedClientPayloadBytes(flushResult.remainingSegments)}"
            } else {
                "Acknowledged HTTPS proxy payload domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} size=$payloadLength bridge=${current.bridgeHost}:${current.bridgePort} nextClientSeq=${flushResult.nextExpectedSequence} bufferedSegments=${flushResult.remainingSegments.size} bufferedBytes=${bufferedClientPayloadBytes(flushResult.remainingSegments)}"
            },
            minIntervalMillis = 5_000L
        )
    }

    private fun logHttpsServerAckHandshakeResult(
        flowKey: String,
        current: HttpsProxyFlow,
        acknowledgementNumber: Long,
        ackResult: SyntheticServerAckResult
    ): Boolean {
        val retransmittedSegments = ackResult.retransmittedSegments
        if (retransmittedSegments != null) {
            return logHandshakeDecisionAndReturnTrue(
                key = "https-proxy-handshake:$flowKey:server-retransmit",
                message = "Retransmitted synthetic HTTPS server payload window domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} ack=$acknowledgementNumber expected=${current.serverNextSequence} pendingSegments=${retransmittedSegments.size} pendingBytes=${pendingServerPayloadBytes(retransmittedSegments)}",
                minIntervalMillis = 3_000L
            )
        }
        return logHandshakeDecisionAndReturnTrue(
            key = "https-proxy-handshake:$flowKey:server-acked",
            message = "Acknowledged synthetic HTTPS server payload domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort} ack=$acknowledgementNumber pendingSegments=0 pendingBytes=0",
            minIntervalMillis = 5_000L
        )
    }

    private fun logHttpsSynAckHandshakeResult(
        flowKey: String,
        current: HttpsProxyFlow
    ): Boolean {
        return logHandshakeDecisionAndReturnTrue(
            key = "https-proxy-handshake:$flowKey:synack",
            message = "Sent synthetic SYN-ACK for HTTPS proxy flow domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort}",
            minIntervalMillis = 5_000L
        )
    }

    private fun logHttpsEstablishedHandshakeResult(
        flowKey: String,
        current: HttpsProxyFlow
    ): Boolean {
        return logHandshakeDecisionAndReturnTrue(
            key = "https-proxy-handshake:$flowKey:established",
            message = "Established synthetic HTTPS proxy flow domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort}",
            minIntervalMillis = 5_000L
        )
    }

    private fun logHttpsFinAckHandshakeResult(
        flowKey: String,
        current: HttpsProxyFlow
    ): Boolean {
        return logHandshakeDecisionAndReturnTrue(
            key = "https-proxy-handshake:$flowKey:finack",
            message = "Sent synthetic FIN-ACK for HTTPS proxy flow domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort}",
            minIntervalMillis = 5_000L
        )
    }

    private fun closeLocalProxyFlowForHandshake(
        flowKey: String,
        current: LocalProxyTcpFlow,
        action: SyntheticFlowCloseAction
    ): Boolean {
        return closeSyntheticFlowForHandshake(
            flowKey = flowKey,
            current = current,
            action = action,
            buildMessage = ::buildLocalProxyFlowCloseMessage,
            closeFlow = ::closeLocalProxyTcpFlow
        )
    }

    private fun closeHttpsFlowForHandshake(
        flowKey: String,
        current: HttpsProxyFlow,
        action: SyntheticFlowCloseAction
    ): Boolean {
        return closeSyntheticFlowForHandshake(
            flowKey = flowKey,
            current = current,
            action = action,
            buildMessage = ::buildHttpsFlowCloseMessage,
            closeFlow = ::closeHttpsProxyFlow
        )
    }

    private fun closePassthroughTcpFlowForHandshake(
        flowKey: String,
        current: PassthroughTcpFlow,
        action: SyntheticFlowCloseAction
    ): Boolean {
        return closeSyntheticFlowForHandshake(
            flowKey = flowKey,
            current = current,
            action = action,
            buildMessage = ::buildPassthroughTcpFlowCloseMessage,
            closeFlow = ::closePassthroughTcpFlow
        )
    }

    private fun closeSyntheticFlowForHandshake(
        flowKey: String,
        message: String,
        closeFlow: (String, String) -> Unit
    ): Boolean {
        return closeSyntheticFlowAndReturnTrue(
            flowKey = flowKey,
            message = message,
            closeFlow = closeFlow
        )
    }

    private fun buildSyntheticFlowCloseMessage(
        action: SyntheticFlowCloseAction,
        bridgeFinMessage: String,
        closedMessage: String,
        resetMessage: String
    ): String {
        return when (action) {
            SyntheticFlowCloseAction.BRIDGE_FIN -> bridgeFinMessage
            SyntheticFlowCloseAction.CLOSED -> closedMessage
            SyntheticFlowCloseAction.RESET -> resetMessage
        }
    }

    private fun <TFlow> closeSyntheticFlowForHandshake(
        flowKey: String,
        current: TFlow,
        action: SyntheticFlowCloseAction,
        buildMessage: (TFlow, SyntheticFlowCloseAction) -> String,
        closeFlow: (String, String) -> Unit
    ): Boolean {
        return closeSyntheticFlowForHandshake(
            flowKey = flowKey,
            message = buildMessage(current, action),
            closeFlow = closeFlow
        )
    }

    private fun buildLocalProxyFlowCloseMessage(
        current: LocalProxyTcpFlow,
        action: SyntheticFlowCloseAction
    ): String {
        return buildSyntheticFlowCloseMessage(
            action = action,
            bridgeFinMessage = "Closed bridge-finished ${buildLocalProxyFlowLabel("${current.targetIp}:${current.targetPort}")}",
            closedMessage = "Closed synthetic ${buildLocalProxyFlowLabel("${current.targetIp}:${current.targetPort}")}",
            resetMessage = "Reset synthetic ${buildLocalProxyFlowLabel("${current.targetIp}:${current.targetPort}")}"
        )
    }

    private fun buildHttpsFlowCloseMessage(
        current: HttpsProxyFlow,
        action: SyntheticFlowCloseAction
    ): String {
        return buildSyntheticFlowCloseMessage(
            action = action,
            bridgeFinMessage = "Closed bridge-finished ${buildHttpsFlowLabel(current.domain)}",
            closedMessage = "Closed synthetic ${buildHttpsFlowLabel(current.domain)}",
            resetMessage = "Reset synthetic ${buildHttpsFlowLabel(current.domain)}"
        )
    }

    private fun buildPassthroughTcpFlowCloseMessage(
        current: PassthroughTcpFlow,
        action: SyntheticFlowCloseAction
    ): String {
        val target = "${current.targetIp}:${current.targetPort}"
        return buildSyntheticFlowCloseMessage(
            action = action,
            bridgeFinMessage = "Closed bridge-finished passthrough TCP target=$target",
            closedMessage = "Closed synthetic passthrough TCP target=$target",
            resetMessage = "Reset synthetic passthrough TCP target=$target"
        )
    }

    private fun buildLocalProxyOverflowReset(
        flowKey: String,
        info: com.HanFeng.model.PacketInfo,
        current: LocalProxyTcpFlow,
        stage: String
    ): () -> Unit {
        return buildOverflowResetAction {
            emitLocalProxyTargetReset(
                flowKey = flowKey,
                request = info,
                stage = stage,
                target = "${current.targetIp}:${current.targetPort}"
            )
        }
    }

    private fun buildHttpsOverflowReset(
        flowKey: String,
        info: com.HanFeng.model.PacketInfo,
        current: HttpsProxyFlow,
        stage: String
    ): () -> Unit {
        return buildOverflowResetAction {
            emitHttpsBridgeReset(
                flowKey,
                info,
                buildHttpsProxyResetMessage(stage = stage, domain = current.domain)
            )
        }
    }

    private fun buildOverflowResetAction(reset: () -> Unit): () -> Unit = reset

    private fun buildCloseFlowAction(closeFlow: () -> Boolean): () -> Boolean = closeFlow

    private fun buildSyntheticCloseActions(
        onBridgeFinSentAck: () -> Boolean,
        onFinAckSentBridgeAck: () -> Boolean,
        onClientRst: () -> Boolean
    ): SyntheticHandshakeCloseActions {
        return SyntheticHandshakeCloseActions(
            onBridgeFinSentAck = onBridgeFinSentAck,
            onFinAckSentBridgeAck = onFinAckSentBridgeAck,
            onClientRst = onClientRst
        )
    }

    private fun <TFlow> buildSyntheticCloseActions(
        flowKey: String,
        current: TFlow,
        closeFlowAction: (String, TFlow, SyntheticFlowCloseAction) -> () -> Boolean
    ): SyntheticHandshakeCloseActions {
        return buildSyntheticCloseActions(
            onBridgeFinSentAck = closeFlowAction(flowKey, current, SyntheticFlowCloseAction.BRIDGE_FIN),
            onFinAckSentBridgeAck = closeFlowAction(flowKey, current, SyntheticFlowCloseAction.CLOSED),
            onClientRst = closeFlowAction(flowKey, current, SyntheticFlowCloseAction.RESET)
        )
    }

    private fun closeLocalProxyFlowAction(
        flowKey: String,
        current: LocalProxyTcpFlow,
        action: SyntheticFlowCloseAction
    ): () -> Boolean = buildCloseFlowAction {
        closeLocalProxyFlowForHandshake(flowKey, current, action)
    }

    private fun closeHttpsFlowAction(
        flowKey: String,
        current: HttpsProxyFlow,
        action: SyntheticFlowCloseAction
    ): () -> Boolean = buildCloseFlowAction {
        closeHttpsFlowForHandshake(flowKey, current, action)
    }

    private fun closePassthroughTcpFlowAction(
        flowKey: String,
        current: PassthroughTcpFlow,
        action: SyntheticFlowCloseAction
    ): () -> Boolean = buildCloseFlowAction {
        closePassthroughTcpFlowForHandshake(flowKey, current, action)
    }

    private fun buildLocalProxyCloseActions(
        flowKey: String,
        current: LocalProxyTcpFlow
    ): SyntheticHandshakeCloseActions {
        return buildSyntheticCloseActions(flowKey = flowKey, current = current, closeFlowAction = ::closeLocalProxyFlowAction)
    }

    private fun buildHttpsCloseActions(
        flowKey: String,
        current: HttpsProxyFlow
    ): SyntheticHandshakeCloseActions {
        return buildSyntheticCloseActions(flowKey = flowKey, current = current, closeFlowAction = ::closeHttpsFlowAction)
    }

    private fun buildPassthroughTcpCloseActions(
        flowKey: String,
        current: PassthroughTcpFlow
    ): SyntheticHandshakeCloseActions {
        return buildSyntheticCloseActions(flowKey = flowKey, current = current, closeFlowAction = ::closePassthroughTcpFlowAction)
    }

    private fun buildHttpsHandshakeCallbacks(
        flowKey: String,
        current: HttpsProxyFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long
    ): SyntheticHandshakeCallbacks {
        return SyntheticHandshakeCallbacks(
            afterSynOpen = {
                logHttpsSynAckHandshakeResult(flowKey, current)
            },
            afterAckEstablish = {
                logHttpsEstablishedHandshakeResult(flowKey, current)
            },
            onPayloadResult = { payloadResult ->
                logHttpsPayloadHandshakeResult(
                    flowKey = flowKey,
                    current = current,
                    sequenceNumber = sequenceNumber,
                    payloadLength = payloadLength,
                    payloadResult = payloadResult
                )
            },
            onServerAckResult = { ackResult ->
                logHttpsServerAckHandshakeResult(
                    flowKey = flowKey,
                    current = current,
                    acknowledgementNumber = acknowledgementNumber,
                    ackResult = ackResult
                )
            },
            afterClientFin = {
                logHttpsFinAckHandshakeResult(flowKey, current)
            }
        )
    }

    private fun buildLocalProxyHandshakeConfig(
        flowKey: String,
        current: LocalProxyTcpFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): SyntheticHandshakeAssemblyConfig<LocalProxyTcpFlow, LocalProxyBridgeSocketSession> {
        return buildLocalProxyHandshakeAssemblyConfig(
            flowKey = flowKey,
            current = current,
            info = info,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            now = now
        )
    }

    private fun buildHttpsHandshakeConfig(
        flowKey: String,
        current: HttpsProxyFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long
    ): SyntheticHandshakeAssemblyConfig<HttpsProxyFlow, HttpsBridgeSocketSession> {
        return buildHttpsHandshakeAssemblyConfig(
            flowKey = flowKey,
            current = current,
            info = info,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            payloadLength = payloadLength,
            now = now
        )
    }

    private fun buildPassthroughHandshakeConfig(
        flowKey: String,
        current: PassthroughTcpFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): SyntheticHandshakeAssemblyConfig<PassthroughTcpFlow, PassthroughTcpSocketSession> {
        return SyntheticHandshakeAssemblyConfig(
            flowCache = passthroughTcpFlowCache,
            sessionCache = passthroughTcpSocketCache,
            selectors = buildSyntheticFlowSelectors(
                sequence = passthroughSequenceSelectors(),
                bufferedSegmentsOf = { it.bufferedClientSegments },
                lastClientPayloadSequenceOf = { it.lastClientPayloadSequence },
                lastClientPayloadLengthOf = { it.lastClientPayloadLength },
                pendingSegmentsOf = { it.pendingServerSegments },
                stateOf = { it.state }
            ),
            ensureBridge = {
                ensurePassthroughTcpSocket(flowKey, current, info)
            },
            isBridgeConnected = { isPassthroughTcpConnected(flowKey) },
            forwardPayload = { payload -> forwardPayloadToPassthroughTcp(flowKey, payload) },
            onBufferOverflow = {
                emitPassthroughTcpReset(flowKey, info, "Buffered client window overflow target=${current.targetIp}:${current.targetPort}")
            },
            onReplayOverflow = {
                emitPassthroughTcpReset(flowKey, info, "Buffered client replay overflow target=${current.targetIp}:${current.targetPort}")
            },
            updateSynAckFlow = buildSynAckStateUpdater(sequenceNumber, acknowledgementNumber, ::applyPassthroughSynAckState),
            updateAckEstablishedFlow = buildAckEstablishedStateUpdater(sequenceNumber, acknowledgementNumber, now, ::applyPassthroughAckEstablishedState),
            updateOutOfOrderFlow = { flowState, updatedBufferedSegments ->
                applyBufferedClientSegmentsState(flowState, updatedBufferedSegments, now)
            },
            updateFlushFlow = buildClientPayloadFlushUpdater(
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                now = now,
                updateFlow = ::applyPassthroughClientPayloadState
            ),
            resendPendingSegments = { segments ->
                resendPendingPassthroughTcpPayload(info, current, segments)
            },
            updateRetransmitFlow = { flowState, remainingSegments ->
                applyRetransmittedServerSegmentsState(flowState, remainingSegments, now)
            },
            updateAckedFlow = { flowState, nextAckState ->
                applySyntheticAckedServerState(flowState, nextAckState)
            },
            updateClientFinFlow = { flowState, transition ->
                applySyntheticClientFinState(flowState, transition, sequenceNumber, acknowledgementNumber)
            },
            closeActions = buildPassthroughTcpCloseActions(flowKey, current)
        )
    }

    private fun buildLocalProxyHandshakeAssemblyConfig(
        flowKey: String,
        current: LocalProxyTcpFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long
    ): SyntheticHandshakeAssemblyConfig<LocalProxyTcpFlow, LocalProxyBridgeSocketSession> {
        return SyntheticHandshakeAssemblyConfig(
            flowCache = localProxyTcpFlowCache,
            sessionCache = localProxyBridgeSocketCache,
            selectors = buildSyntheticFlowSelectors(
                sequence = localProxySequenceSelectors(),
                bufferedSegmentsOf = { it.bufferedClientSegments },
                lastClientPayloadSequenceOf = { it.lastClientPayloadSequence },
                lastClientPayloadLengthOf = { it.lastClientPayloadLength },
                pendingSegmentsOf = { it.pendingServerSegments },
                stateOf = { it.state }
            ),
            ensureBridge = {
                ensureLocalProxyBridgeSocket(flowKey, current, info)
            },
            isBridgeConnected = { isLocalProxyBridgeConnected(flowKey) },
            forwardPayload = { payload -> forwardPayloadToLocalProxyBridge(flowKey, payload) },
            onBufferOverflow = buildLocalProxyOverflowReset(flowKey, info, current, "Buffered client window overflow"),
            onReplayOverflow = buildLocalProxyOverflowReset(flowKey, info, current, "Buffered client replay overflow"),
            updateSynAckFlow = buildSynAckStateUpdater(sequenceNumber, acknowledgementNumber, ::applyLocalProxySynAckState),
            updateAckEstablishedFlow = buildAckEstablishedStateUpdater(sequenceNumber, acknowledgementNumber, now, ::applyLocalProxyAckEstablishedState),
            updateOutOfOrderFlow = { flowState, updatedBufferedSegments ->
                applyBufferedClientSegmentsState(flowState, updatedBufferedSegments, now)
            },
            updateFlushFlow = buildClientPayloadFlushUpdater(
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                now = now,
                updateFlow = ::applyLocalProxyClientPayloadState
            ),
            resendPendingSegments = { segments ->
                resendPendingLocalProxyBridgePayload(info, current, segments)
            },
            updateRetransmitFlow = { flowState, remainingSegments ->
                applyRetransmittedServerSegmentsState(flowState, remainingSegments, now)
            },
            updateAckedFlow = { flowState, nextAckState ->
                applySyntheticAckedServerState(flowState, nextAckState)
            },
            updateClientFinFlow = { flowState, transition ->
                applySyntheticClientFinState(flowState, transition, sequenceNumber, acknowledgementNumber)
            },
            closeActions = buildLocalProxyCloseActions(flowKey, current)
        )
    }

    private fun buildHttpsHandshakeAssemblyConfig(
        flowKey: String,
        current: HttpsProxyFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long
    ): SyntheticHandshakeAssemblyConfig<HttpsProxyFlow, HttpsBridgeSocketSession> {
        return SyntheticHandshakeAssemblyConfig(
            flowCache = httpsProxyFlowCache,
            sessionCache = httpsBridgeSocketCache,
            selectors = buildSyntheticFlowSelectors(
                sequence = httpsSequenceSelectors(),
                bufferedSegmentsOf = { it.bufferedClientSegments },
                lastClientPayloadSequenceOf = { it.lastClientPayloadSequence },
                lastClientPayloadLengthOf = { it.lastClientPayloadLength },
                pendingSegmentsOf = { it.pendingServerSegments },
                stateOf = { it.state }
            ),
            ensureBridge = {
                ensureHttpsBridgeSocket(flowKey, current, info)
            },
            isBridgeConnected = { isHttpsBridgeConnected(flowKey) },
            forwardPayload = { payload -> forwardPayloadToHttpsBridge(flowKey, payload) },
            onBufferOverflow = buildHttpsOverflowReset(flowKey, info, current, "Buffered client window overflow"),
            onReplayOverflow = buildHttpsOverflowReset(flowKey, info, current, "Buffered client replay overflow"),
            updateSynAckFlow = buildSynAckStateUpdater(sequenceNumber, acknowledgementNumber, ::applyHttpsSynAckState),
            updateAckEstablishedFlow = buildAckEstablishedStateUpdater(sequenceNumber, acknowledgementNumber, now, ::applyHttpsAckEstablishedState),
            updateOutOfOrderFlow = { flowState, updatedBufferedSegments ->
                applyBufferedClientSegmentsState(flowState, updatedBufferedSegments, now)
            },
            updateFlushFlow = buildClientPayloadFlushUpdater(
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                now = now,
                updateFlow = ::applyHttpsClientPayloadState
            ),
            resendPendingSegments = { segments ->
                resendPendingHttpsBridgePayload(info, current, segments)
            },
            updateRetransmitFlow = { flowState, remainingSegments ->
                applyRetransmittedServerSegmentsState(flowState, remainingSegments, now)
            },
            updateAckedFlow = { flowState, nextAckState ->
                applySyntheticAckedServerState(flowState, nextAckState)
            },
            updateClientFinFlow = { flowState, transition ->
                applySyntheticClientFinState(flowState, transition, sequenceNumber, acknowledgementNumber)
            },
            closeActions = buildHttpsCloseActions(flowKey, current),
            callbacks = buildHttpsHandshakeCallbacks(
                flowKey = flowKey,
                current = current,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                payloadLength = payloadLength
            )
        )
    }

    private fun <TFlow> buildBufferedClientSegmentsUpdater(
        now: Long,
        updateFlow: (TFlow, List<ClientPayloadSegment>, Long) -> TFlow
    ): (TFlow, List<ClientPayloadSegment>) -> TFlow {
        return { flowState, updatedBufferedSegments ->
            updateFlow(flowState, updatedBufferedSegments, now)
        }
    }

    private fun <TFlow> buildRetransmittedServerSegmentsUpdater(
        now: Long,
        updateFlow: (TFlow, List<PendingServerSegment>, Long) -> TFlow
    ): (TFlow, List<PendingServerSegment>) -> TFlow {
        return { flowState, remainingSegments ->
            updateFlow(flowState, remainingSegments, now)
        }
    }

    private fun <TFlow> buildAckedServerStateUpdater(
        updateFlow: (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow
    ): (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow {
        return { flowState, nextAckState ->
            updateFlow(flowState, nextAckState)
        }
    }

    private fun <TFlow> buildClientFinStateUpdater(
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        updateFlow: (TFlow, BridgeLifecycleSupport.ClientFinTransition, Long, Long) -> TFlow
    ): (TFlow, BridgeLifecycleSupport.ClientFinTransition) -> TFlow {
        return { flowState, transition ->
            updateFlow(flowState, transition, sequenceNumber, acknowledgementNumber)
        }
    }

    private fun <TFlow> buildClientPayloadFlushUpdater(
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long,
        updateFlow: (TFlow, ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>, Boolean, ClientPayloadSegment?, Long, Long, Long) -> TFlow
    ): (TFlow, ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>, Boolean, ClientPayloadSegment?) -> TFlow {
        return { flowState, flushResult, bridgeConnected, lastForwardedSegment ->
            updateFlow(flowState, flushResult, bridgeConnected, lastForwardedSegment, sequenceNumber, acknowledgementNumber, now)
        }
    }

    private fun <TFlow> buildSynAckStateUpdater(
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        updateFlow: (TFlow, Long, Long, Long, Long) -> TFlow
    ): (TFlow, Long, Long) -> TFlow {
        return { flowState, serverSequenceNumber, lastSeenAt ->
            updateFlow(
                flowState,
                sequenceNumber,
                acknowledgementNumber,
                serverSequenceNumber,
                lastSeenAt
            )
        }
    }

    private fun <TFlow> buildAckEstablishedStateUpdater(
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long,
        updateFlow: (TFlow, Long, Long, Long) -> TFlow
    ): (TFlow) -> TFlow {
        return { flowState ->
            updateFlow(flowState, sequenceNumber, acknowledgementNumber, now)
        }
    }

    private fun buildSyntheticAction(action: () -> Boolean): () -> Boolean = action

    private fun <TFlow> buildSynOpenAction(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long,
        serverInitialSequenceOf: (TFlow) -> Long?,
        updateFlow: (TFlow, Long, Long) -> TFlow,
        afterStep: (() -> Unit)? = null
    ): () -> Boolean = buildSyntheticAction {
        handleSyntheticSynOpenAndReturnTrue(
            flowCache = flowCache,
            flowKey = flowKey,
            request = request,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            now = now,
            serverInitialSequenceOf = serverInitialSequenceOf,
            updateFlow = updateFlow,
            afterStep = afterStep
        )
    }

    private fun <TFlow> buildAckEstablishAction(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        now: Long,
        ensureBridge: () -> Unit,
        updateFlow: (TFlow) -> TFlow,
        afterStep: (() -> Unit)? = null
    ): () -> Boolean = buildSyntheticAction {
        handleSyntheticAckEstablishAndReturnTrue(
            flowCache = flowCache,
            flowKey = flowKey,
            now = now,
            ensureBridge = ensureBridge,
            updateFlow = updateFlow,
            afterStep = afterStep
        )
    }

    private fun <TFlow, TSession : ClosableBridgeSession> buildClientFinAction(
        flowCache: MutableMap<String, TFlow>,
        sessionCache: MutableMap<String, TSession>,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        clientSequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long,
        serverInitialSequenceOf: (TFlow) -> Long?,
        serverNextSequenceOf: (TFlow) -> Long?,
        updateFlow: (TFlow, BridgeLifecycleSupport.ClientFinTransition) -> TFlow,
        afterStep: (() -> Unit)? = null
    ): () -> Boolean = buildSyntheticAction {
        handleSyntheticClientFinAndReturnTrue(
            flowCache = flowCache,
            sessionCache = sessionCache,
            flowKey = flowKey,
            request = request,
            clientSequenceNumber = clientSequenceNumber,
            payloadLength = payloadLength,
            now = now,
            serverInitialSequenceOf = serverInitialSequenceOf,
            serverNextSequenceOf = serverNextSequenceOf,
            updateFlow = updateFlow,
            closeSession = { it.close() },
            afterStep = afterStep
        )
    }

    private fun <TFlow> buildClientPayloadAction(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        current: TFlow,
        request: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long,
        serverInitialSequenceOf: (TFlow) -> Long?,
        clientInitialSequenceOf: (TFlow) -> Long?,
        clientNextSequenceOf: (TFlow) -> Long?,
        bufferedSegmentsOf: (TFlow) -> List<ClientPayloadSegment>,
        lastClientPayloadSequenceOf: (TFlow) -> Long?,
        lastClientPayloadLengthOf: (TFlow) -> Long?,
        ensureBridge: () -> Unit,
        isBridgeConnected: () -> Boolean,
        forwardPayload: (ByteArray) -> Unit,
        onBufferOverflow: () -> Unit,
        onReplayOverflow: () -> Unit,
        updateOutOfOrderFlow: (TFlow, List<ClientPayloadSegment>) -> TFlow,
        updateFlushFlow: (TFlow, ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>, Boolean, ClientPayloadSegment?) -> TFlow,
        onResult: ((SyntheticClientPayloadResult) -> Boolean)? = null
    ): () -> Boolean = buildSyntheticAction {
        ensureBridge()
        handleSyntheticClientPayloadResult(
            flowCache = flowCache,
            flowKey = flowKey,
            current = current,
            request = request,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            payloadLength = payloadLength,
            now = now,
            serverInitialSequenceOf = serverInitialSequenceOf,
            clientInitialSequenceOf = clientInitialSequenceOf,
            clientNextSequenceOf = clientNextSequenceOf,
            bufferedSegmentsOf = bufferedSegmentsOf,
            lastClientPayloadSequenceOf = lastClientPayloadSequenceOf,
            lastClientPayloadLengthOf = lastClientPayloadLengthOf,
            isBridgeConnected = isBridgeConnected,
            forwardPayload = forwardPayload,
            onBufferOverflow = onBufferOverflow,
            onReplayOverflow = onReplayOverflow,
            updateOutOfOrderFlow = updateOutOfOrderFlow,
            updateFlushFlow = updateFlushFlow,
            onResult = onResult
        )
    }

    private fun <TFlow> buildServerAckAction(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        current: TFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long,
        pendingSegmentsOf: (TFlow) -> List<PendingServerSegment>,
        serverNextSequenceOf: (TFlow) -> Long?,
        stateOf: (TFlow) -> String,
        resendPendingSegments: (List<PendingServerSegment>) -> Unit,
        updateRetransmitFlow: (TFlow, List<PendingServerSegment>) -> TFlow,
        updateAckedFlow: (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow,
        onResult: ((SyntheticServerAckResult) -> Boolean)? = null
    ): () -> Boolean = buildSyntheticAction {
        handleSyntheticServerAckResult(
            flowCache = flowCache,
            flowKey = flowKey,
            current = current,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            now = now,
            pendingSegmentsOf = pendingSegmentsOf,
            serverNextSequenceOf = serverNextSequenceOf,
            stateOf = stateOf,
            resendPendingSegments = resendPendingSegments,
            updateRetransmitFlow = updateRetransmitFlow,
            updateAckedFlow = updateAckedFlow,
            onResult = onResult
        )
    }

    private fun isLocalProxyBridgeConnected(flowKey: String): Boolean {
        return synchronized(localProxyBridgeSocketCache) {
            localProxyBridgeSocketCache.containsKey(flowKey)
        }
    }

    private fun isPassthroughTcpConnected(flowKey: String): Boolean {
        return synchronized(passthroughTcpSocketCache) {
            passthroughTcpSocketCache.containsKey(flowKey)
        }
    }

    private data class SyntheticHandshakeCloseActions(
        val onBridgeFinSentAck: () -> Boolean,
        val onFinAckSentBridgeAck: () -> Boolean,
        val onClientRst: () -> Boolean
    )

    private data class SyntheticHandshakeCallbacks(
        val afterSynOpen: (() -> Unit)? = null,
        val afterAckEstablish: (() -> Unit)? = null,
        val onPayloadResult: ((SyntheticClientPayloadResult) -> Boolean)? = null,
        val onServerAckResult: ((SyntheticServerAckResult) -> Boolean)? = null,
        val afterClientFin: (() -> Unit)? = null
    )

    private data class FlowSequenceSelectors<TFlow>(
        val serverInitialSequenceOf: (TFlow) -> Long?,
        val serverNextSequenceOf: (TFlow) -> Long?,
        val clientInitialSequenceOf: (TFlow) -> Long?,
        val clientNextSequenceOf: (TFlow) -> Long?
    )

    private data class SyntheticFlowSelectors<TFlow>(
        val sequence: FlowSequenceSelectors<TFlow>,
        val bufferedSegmentsOf: (TFlow) -> List<ClientPayloadSegment>,
        val lastClientPayloadSequenceOf: (TFlow) -> Long?,
        val lastClientPayloadLengthOf: (TFlow) -> Long?,
        val pendingSegmentsOf: (TFlow) -> List<PendingServerSegment>,
        val stateOf: (TFlow) -> String
    )

    private data class BridgeFlowSelectors<TFlow>(
        val sequence: FlowSequenceSelectors<TFlow>,
        val pendingSegmentsOf: (TFlow) -> List<PendingServerSegment>? = { emptyList() },
        val stateOf: ((TFlow) -> String)? = null
    )

    private fun localProxySequenceSelectors(): FlowSequenceSelectors<LocalProxyTcpFlow> {
        return FlowSequenceSelectors(
            serverInitialSequenceOf = { it.serverInitialSequence },
            serverNextSequenceOf = { it.serverNextSequence },
            clientInitialSequenceOf = { it.clientInitialSequence },
            clientNextSequenceOf = { it.clientNextSequence }
        )
    }

    private fun httpsSequenceSelectors(): FlowSequenceSelectors<HttpsProxyFlow> {
        return FlowSequenceSelectors(
            serverInitialSequenceOf = { it.serverInitialSequence },
            serverNextSequenceOf = { it.serverNextSequence },
            clientInitialSequenceOf = { it.clientInitialSequence },
            clientNextSequenceOf = { it.clientNextSequence }
        )
    }

    private fun passthroughSequenceSelectors(): FlowSequenceSelectors<PassthroughTcpFlow> {
        return FlowSequenceSelectors(
            serverInitialSequenceOf = { it.serverInitialSequence },
            serverNextSequenceOf = { it.serverNextSequence },
            clientInitialSequenceOf = { it.clientInitialSequence },
            clientNextSequenceOf = { it.clientNextSequence }
        )
    }

    private fun <TFlow> buildSyntheticFlowSelectors(
        sequence: FlowSequenceSelectors<TFlow>,
        bufferedSegmentsOf: (TFlow) -> List<ClientPayloadSegment>,
        lastClientPayloadSequenceOf: (TFlow) -> Long?,
        lastClientPayloadLengthOf: (TFlow) -> Long?,
        pendingSegmentsOf: (TFlow) -> List<PendingServerSegment>,
        stateOf: (TFlow) -> String
    ): SyntheticFlowSelectors<TFlow> {
        return SyntheticFlowSelectors(
            sequence = sequence,
            bufferedSegmentsOf = bufferedSegmentsOf,
            lastClientPayloadSequenceOf = lastClientPayloadSequenceOf,
            lastClientPayloadLengthOf = lastClientPayloadLengthOf,
            pendingSegmentsOf = pendingSegmentsOf,
            stateOf = stateOf
        )
    }

    private data class SyntheticHandshakeAssembly<TFlow, TSession : ClosableBridgeSession>(
        val flowCache: MutableMap<String, TFlow>,
        val sessionCache: MutableMap<String, TSession>,
        val flowKey: String,
        val current: TFlow,
        val request: com.HanFeng.model.PacketInfo,
        val sequenceNumber: Long,
        val acknowledgementNumber: Long,
        val payloadLength: Long,
        val now: Long,
        val ensureBridge: () -> Unit,
        val isBridgeConnected: () -> Boolean,
        val forwardPayload: (ByteArray) -> Unit,
        val onBufferOverflow: () -> Unit,
        val onReplayOverflow: () -> Unit,
        val selectors: SyntheticFlowSelectors<TFlow>,
        val updateSynAckFlow: (TFlow, Long, Long) -> TFlow,
        val updateAckEstablishedFlow: (TFlow) -> TFlow,
        val updateOutOfOrderFlow: (TFlow, List<ClientPayloadSegment>) -> TFlow,
        val updateFlushFlow: (TFlow, ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>, Boolean, ClientPayloadSegment?) -> TFlow,
        val resendPendingSegments: (List<PendingServerSegment>) -> Unit,
        val updateRetransmitFlow: (TFlow, List<PendingServerSegment>) -> TFlow,
        val updateAckedFlow: (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow,
        val updateClientFinFlow: (TFlow, BridgeLifecycleSupport.ClientFinTransition) -> TFlow,
        val closeActions: SyntheticHandshakeCloseActions,
        val callbacks: SyntheticHandshakeCallbacks = SyntheticHandshakeCallbacks()
    )

    private data class SyntheticHandshakeAssemblyConfig<TFlow, TSession : ClosableBridgeSession>(
        val flowCache: MutableMap<String, TFlow>,
        val sessionCache: MutableMap<String, TSession>,
        val selectors: SyntheticFlowSelectors<TFlow>,
        val ensureBridge: () -> Unit,
        val isBridgeConnected: () -> Boolean,
        val forwardPayload: (ByteArray) -> Unit,
        val onBufferOverflow: () -> Unit,
        val onReplayOverflow: () -> Unit,
        val updateSynAckFlow: (TFlow, Long, Long) -> TFlow,
        val updateAckEstablishedFlow: (TFlow) -> TFlow,
        val updateOutOfOrderFlow: (TFlow, List<ClientPayloadSegment>) -> TFlow,
        val updateFlushFlow: (TFlow, ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>, Boolean, ClientPayloadSegment?) -> TFlow,
        val resendPendingSegments: (List<PendingServerSegment>) -> Unit,
        val updateRetransmitFlow: (TFlow, List<PendingServerSegment>) -> TFlow,
        val updateAckedFlow: (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow,
        val updateClientFinFlow: (TFlow, BridgeLifecycleSupport.ClientFinTransition) -> TFlow,
        val closeActions: SyntheticHandshakeCloseActions,
        val callbacks: SyntheticHandshakeCallbacks = SyntheticHandshakeCallbacks()
    )

    private fun <TFlow, TSession : ClosableBridgeSession> buildSyntheticHandshakeAssemblyBase(
        flowCache: MutableMap<String, TFlow>,
        sessionCache: MutableMap<String, TSession>,
        flowKey: String,
        current: TFlow,
        request: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long,
        ensureBridge: () -> Unit,
        isBridgeConnected: () -> Boolean,
        forwardPayload: (ByteArray) -> Unit,
        onBufferOverflow: () -> Unit,
        onReplayOverflow: () -> Unit,
        selectors: SyntheticFlowSelectors<TFlow>,
        updateSynAckFlow: (TFlow, Long, Long) -> TFlow,
        updateAckEstablishedFlow: (TFlow) -> TFlow,
        updateOutOfOrderFlow: (TFlow, List<ClientPayloadSegment>) -> TFlow,
        updateFlushFlow: (TFlow, ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>, Boolean, ClientPayloadSegment?) -> TFlow,
        resendPendingSegments: (List<PendingServerSegment>) -> Unit,
        updateRetransmitFlow: (TFlow, List<PendingServerSegment>) -> TFlow,
        updateAckedFlow: (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow,
        updateClientFinFlow: (TFlow, BridgeLifecycleSupport.ClientFinTransition) -> TFlow,
        closeActions: SyntheticHandshakeCloseActions,
        callbacks: SyntheticHandshakeCallbacks = SyntheticHandshakeCallbacks()
    ): SyntheticHandshakeAssembly<TFlow, TSession> {
        return SyntheticHandshakeAssembly(
            flowCache = flowCache,
            sessionCache = sessionCache,
            flowKey = flowKey,
            current = current,
            request = request,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            payloadLength = payloadLength,
            now = now,
            ensureBridge = ensureBridge,
            isBridgeConnected = isBridgeConnected,
            forwardPayload = forwardPayload,
            onBufferOverflow = onBufferOverflow,
            onReplayOverflow = onReplayOverflow,
            selectors = selectors,
            updateSynAckFlow = updateSynAckFlow,
            updateAckEstablishedFlow = updateAckEstablishedFlow,
            updateOutOfOrderFlow = updateOutOfOrderFlow,
            updateFlushFlow = updateFlushFlow,
            resendPendingSegments = resendPendingSegments,
            updateRetransmitFlow = updateRetransmitFlow,
            updateAckedFlow = updateAckedFlow,
            updateClientFinFlow = updateClientFinFlow,
            closeActions = closeActions,
            callbacks = callbacks
        )
    }

    private fun <TFlow, TSession : ClosableBridgeSession> buildSyntheticHandshakeAssemblyCommon(
        flowKey: String,
        current: TFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long,
        config: SyntheticHandshakeAssemblyConfig<TFlow, TSession>
    ): SyntheticHandshakeAssembly<TFlow, TSession> {
        return buildSyntheticHandshakeAssemblyBase(
            flowCache = config.flowCache,
            sessionCache = config.sessionCache,
            flowKey = flowKey,
            current = current,
            request = info,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            payloadLength = payloadLength,
            now = now,
            ensureBridge = config.ensureBridge,
            isBridgeConnected = config.isBridgeConnected,
            forwardPayload = config.forwardPayload,
            onBufferOverflow = config.onBufferOverflow,
            onReplayOverflow = config.onReplayOverflow,
            selectors = config.selectors,
            updateSynAckFlow = config.updateSynAckFlow,
            updateAckEstablishedFlow = config.updateAckEstablishedFlow,
            updateOutOfOrderFlow = config.updateOutOfOrderFlow,
            updateFlushFlow = config.updateFlushFlow,
            resendPendingSegments = config.resendPendingSegments,
            updateRetransmitFlow = config.updateRetransmitFlow,
            updateAckedFlow = config.updateAckedFlow,
            updateClientFinFlow = config.updateClientFinFlow,
            closeActions = config.closeActions,
            callbacks = config.callbacks
        )
    }

    private fun <TFlow, TSession : ClosableBridgeSession> buildSyntheticLifecycleActions(
        assembly: SyntheticHandshakeAssembly<TFlow, TSession>
    ): Triple<() -> Boolean, () -> Boolean, () -> Boolean> {
        return Triple(
            buildSynOpenAction(
                flowCache = assembly.flowCache,
                flowKey = assembly.flowKey,
                request = assembly.request,
                sequenceNumber = assembly.sequenceNumber,
                acknowledgementNumber = assembly.acknowledgementNumber,
                now = assembly.now,
                serverInitialSequenceOf = assembly.selectors.sequence.serverInitialSequenceOf,
                updateFlow = assembly.updateSynAckFlow,
                afterStep = assembly.callbacks.afterSynOpen
            ),
            buildAckEstablishAction(
                flowCache = assembly.flowCache,
                flowKey = assembly.flowKey,
                now = assembly.now,
                ensureBridge = assembly.ensureBridge,
                updateFlow = assembly.updateAckEstablishedFlow,
                afterStep = assembly.callbacks.afterAckEstablish
            ),
            buildClientFinAction(
                flowCache = assembly.flowCache,
                sessionCache = assembly.sessionCache,
                flowKey = assembly.flowKey,
                request = assembly.request,
                clientSequenceNumber = assembly.sequenceNumber,
                acknowledgementNumber = assembly.acknowledgementNumber,
                payloadLength = assembly.payloadLength,
                now = assembly.now,
                serverInitialSequenceOf = assembly.selectors.sequence.serverInitialSequenceOf,
                serverNextSequenceOf = assembly.selectors.sequence.serverNextSequenceOf,
                updateFlow = assembly.updateClientFinFlow,
                afterStep = assembly.callbacks.afterClientFin
            )
        )
    }

    private fun <TFlow, TSession : ClosableBridgeSession> buildSyntheticDataActions(
        assembly: SyntheticHandshakeAssembly<TFlow, TSession>
    ): Pair<() -> Boolean, () -> Boolean> {
        return Pair(
            buildClientPayloadAction(
                flowCache = assembly.flowCache,
                flowKey = assembly.flowKey,
                current = assembly.current,
                request = assembly.request,
                sequenceNumber = assembly.sequenceNumber,
                acknowledgementNumber = assembly.acknowledgementNumber,
                payloadLength = assembly.payloadLength,
                now = assembly.now,
                serverInitialSequenceOf = assembly.selectors.sequence.serverInitialSequenceOf,
                clientInitialSequenceOf = assembly.selectors.sequence.clientInitialSequenceOf,
                clientNextSequenceOf = assembly.selectors.sequence.clientNextSequenceOf,
                bufferedSegmentsOf = assembly.selectors.bufferedSegmentsOf,
                lastClientPayloadSequenceOf = assembly.selectors.lastClientPayloadSequenceOf,
                lastClientPayloadLengthOf = assembly.selectors.lastClientPayloadLengthOf,
                ensureBridge = assembly.ensureBridge,
                isBridgeConnected = assembly.isBridgeConnected,
                forwardPayload = assembly.forwardPayload,
                onBufferOverflow = assembly.onBufferOverflow,
                onReplayOverflow = assembly.onReplayOverflow,
                updateOutOfOrderFlow = assembly.updateOutOfOrderFlow,
                updateFlushFlow = assembly.updateFlushFlow,
                onResult = assembly.callbacks.onPayloadResult
            ),
            buildServerAckAction(
                flowCache = assembly.flowCache,
                flowKey = assembly.flowKey,
                current = assembly.current,
                sequenceNumber = assembly.sequenceNumber,
                acknowledgementNumber = assembly.acknowledgementNumber,
                now = assembly.now,
                pendingSegmentsOf = assembly.selectors.pendingSegmentsOf,
                serverNextSequenceOf = assembly.selectors.sequence.serverNextSequenceOf,
                stateOf = assembly.selectors.stateOf,
                resendPendingSegments = assembly.resendPendingSegments,
                updateRetransmitFlow = assembly.updateRetransmitFlow,
                updateAckedFlow = assembly.updateAckedFlow,
                onResult = assembly.callbacks.onServerAckResult
            )
        )
    }

    private fun <TFlow, TSession : ClosableBridgeSession> buildSyntheticHandshakeHandlers(
        assembly: SyntheticHandshakeAssembly<TFlow, TSession>
    ): SyntheticHandshakeHandlers {
        val (onSynOpen, onAckEstablish, onClientFin) = buildSyntheticLifecycleActions(assembly)
        val (onClientPayload, onServerAck) = buildSyntheticDataActions(assembly)
        return SyntheticHandshakeHandlers(
            onSynOpen = onSynOpen,
            onAckEstablish = onAckEstablish,
            onClientPayload = onClientPayload,
            onServerAck = onServerAck,
            onBridgeFinSentAck = assembly.closeActions.onBridgeFinSentAck,
            onClientFin = onClientFin,
            onFinAckSentBridgeAck = assembly.closeActions.onFinAckSentBridgeAck,
            onClientRst = assembly.closeActions.onClientRst
        )
    }

    private fun <TFlow, TSession : ClosableBridgeSession> buildSyntheticHandshakeAssembly(
        flowKey: String,
        current: TFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long,
        config: SyntheticHandshakeAssemblyConfig<TFlow, TSession>
    ): SyntheticHandshakeAssembly<TFlow, TSession> {
        return buildSyntheticHandshakeAssemblyCommon(
            flowKey = flowKey,
            current = current,
            info = info,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            payloadLength = payloadLength,
            now = now,
            config = config
        )
    }

    private fun <TFlow, TSession : ClosableBridgeSession> buildSyntheticHandshakeHandlers(
        flowKey: String,
        current: TFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long,
        config: SyntheticHandshakeAssemblyConfig<TFlow, TSession>
    ): SyntheticHandshakeHandlers {
        return buildSyntheticHandshakeHandlers(
            buildSyntheticHandshakeAssembly(
                flowKey = flowKey,
                current = current,
                info = info,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                payloadLength = payloadLength,
                now = now,
                config = config
            )
        )
    }

    private fun buildLocalProxyHandshakeHandlers(
        flowKey: String,
        current: LocalProxyTcpFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long
    ): SyntheticHandshakeHandlers {
        return buildSyntheticHandshakeHandlers(
            flowKey = flowKey,
            current = current,
            info = info,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            payloadLength = payloadLength,
            now = now,
            config = buildLocalProxyHandshakeConfig(
                flowKey = flowKey,
                current = current,
                info = info,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                now = now
            )
        )
    }

    private fun buildHttpsHandshakeHandlers(
        flowKey: String,
        current: HttpsProxyFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long
    ): SyntheticHandshakeHandlers {
        return buildSyntheticHandshakeHandlers(
            flowKey = flowKey,
            current = current,
            info = info,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            payloadLength = payloadLength,
            now = now,
            config = buildHttpsHandshakeConfig(
                flowKey = flowKey,
                current = current,
                info = info,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                payloadLength = payloadLength,
                now = now
            )
        )
    }

    private fun buildPassthroughHandshakeHandlers(
        flowKey: String,
        current: PassthroughTcpFlow,
        info: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long
    ): SyntheticHandshakeHandlers {
        return buildSyntheticHandshakeHandlers(
            flowKey = flowKey,
            current = current,
            info = info,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            payloadLength = payloadLength,
            now = now,
            config = buildPassthroughHandshakeConfig(
                flowKey = flowKey,
                current = current,
                info = info,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                now = now
            )
        )
    }

    private data class SyntheticHandshakeHandlers(
        val onSynOpen: () -> Boolean,
        val onAckEstablish: () -> Boolean,
        val onClientPayload: () -> Boolean,
        val onServerAck: () -> Boolean,
        val onBridgeFinSentAck: () -> Boolean,
        val onClientFin: () -> Boolean,
        val onFinAckSentBridgeAck: () -> Boolean,
        val onClientRst: () -> Boolean
    )

    private fun dispatchSyntheticHandshakeEvent(
        event: HttpsHandshakeEngine.Event,
        currentState: String,
        handlers: SyntheticHandshakeHandlers
    ): Boolean {
        return when (event) {
            HttpsHandshakeEngine.Event.NONE -> false
            HttpsHandshakeEngine.Event.SYN_OPEN -> handlers.onSynOpen()
            HttpsHandshakeEngine.Event.ACK_ESTABLISH -> handlers.onAckEstablish()
            HttpsHandshakeEngine.Event.CLIENT_PAYLOAD -> handlers.onClientPayload()
            HttpsHandshakeEngine.Event.SERVER_ACK -> handlers.onServerAck()
            HttpsHandshakeEngine.Event.BRIDGE_FIN_ACK -> when (currentState) {
                "bridge_fin_sent" -> handlers.onBridgeFinSentAck()
                "fin_ack_sent" -> handlers.onFinAckSentBridgeAck()
                else -> false
            }
            HttpsHandshakeEngine.Event.CLIENT_FIN -> handlers.onClientFin()
            HttpsHandshakeEngine.Event.CLIENT_RST -> handlers.onClientRst()
        }
    }

    private fun <TFlow> executeSyntheticHandshake(
        info: com.HanFeng.model.PacketInfo,
        destinationPort: Int,
        current: TFlow?,
        currentState: String,
        validateCurrent: () -> Boolean = { true },
        buildHandlers: (SyntheticPacketState, TFlow) -> SyntheticHandshakeHandlers
    ): Boolean {
        val handshakeDecision = decideSyntheticHandshake(info, destinationPort, current != null, bridgePortOf(current), currentState)
        if (!handshakeDecision.shouldHandle) return false
        val activeCurrent = current ?: return false
        if (!validateCurrent()) return false
        val packetState = buildSyntheticPacketState(info)
        return dispatchSyntheticHandshakeEvent(
            event = handshakeDecision.event,
            currentState = currentState,
            handlers = buildHandlers(packetState, activeCurrent)
        )
    }

    private fun bridgePortOf(flow: Any?): Int? {
        return when (flow) {
            is LocalProxyTcpFlow -> flow.bridgePort
            is HttpsProxyFlow -> flow.bridgePort
            is PassthroughTcpFlow -> flow.targetPort
            else -> null
        }
    }

    private data class SyntheticClientPayloadResult(
        val expectedClientSequence: Long,
        val isRetransmission: Boolean,
        val outOfOrderBufferedSegments: List<ClientPayloadSegment>?,
        val flushResult: ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>?
    )

    private data class SyntheticServerAckResult(
        val retransmittedSegments: List<PendingServerSegment>?
    )

    private data class SyntheticClientPayloadContext<TFlow>(
        val flowCache: MutableMap<String, TFlow>,
        val flowKey: String,
        val current: TFlow,
        val request: com.HanFeng.model.PacketInfo,
        val sequenceNumber: Long,
        val acknowledgementNumber: Long,
        val payloadLength: Long,
        val now: Long,
        val bufferedSegmentsOf: (TFlow) -> List<ClientPayloadSegment>,
        val lastClientPayloadSequenceOf: (TFlow) -> Long?,
        val lastClientPayloadLengthOf: (TFlow) -> Long?,
        val isBridgeConnected: () -> Boolean,
        val forwardPayload: (ByteArray) -> Unit,
        val onBufferOverflow: () -> Unit,
        val onReplayOverflow: () -> Unit,
        val updateOutOfOrderFlow: (TFlow, List<ClientPayloadSegment>) -> TFlow,
        val updateFlushFlow: (TFlow, ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>, Boolean, ClientPayloadSegment?) -> TFlow
    )

    private data class SyntheticServerAckContext<TFlow>(
        val flowCache: MutableMap<String, TFlow>,
        val flowKey: String,
        val current: TFlow,
        val sequenceNumber: Long,
        val acknowledgementNumber: Long,
        val now: Long,
        val pendingSegmentsOf: (TFlow) -> List<PendingServerSegment>,
        val serverNextSequenceOf: (TFlow) -> Long?,
        val stateOf: (TFlow) -> String,
        val resendPendingSegments: (List<PendingServerSegment>) -> Unit,
        val updateRetransmitFlow: (TFlow, List<PendingServerSegment>) -> TFlow,
        val updateAckedFlow: (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow
    )

    private fun <TFlow> handleSyntheticClientPayload(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        current: TFlow,
        request: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        payloadLength: Long,
        now: Long,
        serverInitialSequenceOf: (TFlow) -> Long?,
        clientInitialSequenceOf: (TFlow) -> Long?,
        clientNextSequenceOf: (TFlow) -> Long?,
        bufferedSegmentsOf: (TFlow) -> List<ClientPayloadSegment>,
        lastClientPayloadSequenceOf: (TFlow) -> Long?,
        lastClientPayloadLengthOf: (TFlow) -> Long?,
        isBridgeConnected: () -> Boolean,
        forwardPayload: (ByteArray) -> Unit,
        onBufferOverflow: () -> Unit,
        onReplayOverflow: () -> Unit,
        updateOutOfOrderFlow: (TFlow, List<ClientPayloadSegment>) -> TFlow,
        updateFlushFlow: (TFlow, ClientPayloadReplaySupport.DrainResult<ClientPayloadSegment>, Boolean, ClientPayloadSegment?) -> TFlow
    ): SyntheticClientPayloadResult {
        val context = SyntheticClientPayloadContext(
            flowCache = flowCache,
            flowKey = flowKey,
            current = current,
            request = request,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            payloadLength = payloadLength,
            now = now,
            bufferedSegmentsOf = bufferedSegmentsOf,
            lastClientPayloadSequenceOf = lastClientPayloadSequenceOf,
            lastClientPayloadLengthOf = lastClientPayloadLengthOf,
            isBridgeConnected = isBridgeConnected,
            forwardPayload = forwardPayload,
            onBufferOverflow = onBufferOverflow,
            onReplayOverflow = onReplayOverflow,
            updateOutOfOrderFlow = updateOutOfOrderFlow,
            updateFlushFlow = updateFlushFlow
        )
        val serverSeq = serverInitialSequenceOf(current) ?: synthesizeServerSequence(flowKey)
        val expectedClientSeq = clientNextSequenceOf(current) ?: ((clientInitialSequenceOf(current) ?: sequenceNumber) + 1)
        val isRetransmission = sequenceNumber < expectedClientSeq || (
            lastClientPayloadSequenceOf(current) == sequenceNumber &&
                lastClientPayloadLengthOf(current) == payloadLength
            )
        if (sequenceNumber > expectedClientSeq) {
            return handleSyntheticOutOfOrderClientPayload(context, serverSeq, expectedClientSeq, isRetransmission)
        }
        val inboundSegments = mutableListOf<ClientPayloadSegment>()
        var nextAckNumber = expectedClientSeq
        if (!isRetransmission && request.payload.isNotEmpty()) {
            inboundSegments += ClientPayloadSegment(sequenceNumber, request.payload)
            nextAckNumber = maxOf(nextAckNumber, sequenceNumber + payloadLength)
        }
        return handleSyntheticFlushableClientPayload(
            context = context,
            serverSeq = serverSeq,
            expectedClientSeq = expectedClientSeq,
            isRetransmission = isRetransmission,
            inboundSegments = inboundSegments,
            nextAckNumber = nextAckNumber
        )
    }

    private fun <TFlow> handleSyntheticServerAck(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        current: TFlow,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        now: Long,
        pendingSegmentsOf: (TFlow) -> List<PendingServerSegment>,
        serverNextSequenceOf: (TFlow) -> Long?,
        stateOf: (TFlow) -> String,
        resendPendingSegments: (List<PendingServerSegment>) -> Unit,
        updateRetransmitFlow: (TFlow, List<PendingServerSegment>) -> TFlow,
        updateAckedFlow: (TFlow, ServerAckStateSupport.AckStateTransition) -> TFlow
    ): SyntheticServerAckResult {
        val context = SyntheticServerAckContext(
            flowCache = flowCache,
            flowKey = flowKey,
            current = current,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            now = now,
            pendingSegmentsOf = pendingSegmentsOf,
            serverNextSequenceOf = serverNextSequenceOf,
            stateOf = stateOf,
            resendPendingSegments = resendPendingSegments,
            updateRetransmitFlow = updateRetransmitFlow,
            updateAckedFlow = updateAckedFlow
        )
        return handleSyntheticRetransmitServerAck(context)
            ?: handleSyntheticAdvancedServerAck(context)
    }

    private fun <TFlow> handleSyntheticOutOfOrderClientPayload(
        context: SyntheticClientPayloadContext<TFlow>,
        serverSeq: Long,
        expectedClientSeq: Long,
        isRetransmission: Boolean
    ): SyntheticClientPayloadResult {
        val updatedBufferedSegments = ClientPayloadReplaySupport.bufferOutOfOrderPayload(
            existingSegments = context.bufferedSegmentsOf(context.current),
            payload = context.request.payload,
            sequenceNumber = context.sequenceNumber,
            mergeWithSegment = ::mergeBufferedClientSegments,
            createSegment = ::ClientPayloadSegment,
            bufferedBytes = ::bufferedClientPayloadBytes,
            maxBufferedClientBytes = MAX_BUFFERED_CLIENT_BYTES
        )
        if (updatedBufferedSegments == null) {
            context.onBufferOverflow()
            return SyntheticClientPayloadResult(expectedClientSeq, isRetransmission, null, null)
        }
        FlowCacheSupport.putPruned(
            context.flowCache,
            context.flowKey,
            context.updateOutOfOrderFlow(context.current, updatedBufferedSegments),
            512
        )
        writeSyntheticClientAck(context.request, serverSeq + 1, expectedClientSeq)
        return SyntheticClientPayloadResult(expectedClientSeq, isRetransmission, updatedBufferedSegments, null)
    }

    private fun <TFlow> handleSyntheticFlushableClientPayload(
        context: SyntheticClientPayloadContext<TFlow>,
        serverSeq: Long,
        expectedClientSeq: Long,
        isRetransmission: Boolean,
        inboundSegments: List<ClientPayloadSegment>,
        nextAckNumber: Long
    ): SyntheticClientPayloadResult {
        val flushResult = ClientPayloadReplaySupport.drainReplayPayload(
            existingSegments = context.bufferedSegmentsOf(context.current),
            inboundSegments = inboundSegments,
            acknowledgementNumber = nextAckNumber,
            mergeSegments = ::mergeBufferedClientSegments,
            drainSegments = { segments, expectedSequence ->
                drainBufferedClientSegments(segments, expectedSequence).let {
                    ClientPayloadReplaySupport.DrainResult(
                        nextExpectedSequence = it.nextExpectedSequence,
                        forwardSegments = it.forwardSegments,
                        remainingSegments = it.remainingSegments
                    )
                }
            },
            bufferedBytes = ::bufferedClientPayloadBytes,
            maxBufferedClientBytes = MAX_BUFFERED_CLIENT_BYTES
        )
        if (flushResult == null) {
            context.onReplayOverflow()
            return SyntheticClientPayloadResult(expectedClientSeq, isRetransmission, null, null)
        }
        val bridgeConnected = context.isBridgeConnected()
        if (bridgeConnected) {
            flushResult.forwardSegments.forEach { segment ->
                context.forwardPayload(segment.payload)
            }
        }
        val lastForwardedSegment = flushResult.forwardSegments.lastOrNull()
        FlowCacheSupport.putPruned(
            context.flowCache,
            context.flowKey,
            context.updateFlushFlow(context.current, flushResult, bridgeConnected, lastForwardedSegment),
            512
        )
        writeSyntheticClientAck(context.request, serverSeq + 1, flushResult.nextExpectedSequence)
        return SyntheticClientPayloadResult(expectedClientSeq, isRetransmission, null, flushResult)
    }

    private fun writeSyntheticClientAck(
        request: com.HanFeng.model.PacketInfo,
        sequenceNumber: Long,
        acknowledgementNumber: Long
    ) {
        writeTunPacket(
            PacketCodec.buildTcpResponse(
                request = request,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                flags = TCP_FLAG_ACK,
                windowSize = request.tcpWindowSize ?: DEFAULT_TCP_WINDOW_SIZE
            )
        )
    }

    private fun <TFlow> handleSyntheticRetransmitServerAck(
        context: SyntheticServerAckContext<TFlow>
    ): SyntheticServerAckResult? {
        val pendingSegments = context.pendingSegmentsOf(context.current)
        val serverNextSequence = context.serverNextSequenceOf(context.current)
        if (pendingSegments.isEmpty() || serverNextSequence == null || context.acknowledgementNumber >= serverNextSequence) {
            return null
        }
        val retransmitState = ServerAckStateSupport.trimPendingSegmentsForAck(
            pendingSegments = pendingSegments,
            acknowledgementNumber = context.acknowledgementNumber,
            trim = ::trimAcknowledgedServerSegments
        ) ?: return null
        context.resendPendingSegments(retransmitState.remainingSegments)
        FlowCacheSupport.updateIfPresent(context.flowCache, context.flowKey) {
            context.updateRetransmitFlow(it, retransmitState.remainingSegments)
        }
        return SyntheticServerAckResult(retransmitState.remainingSegments)
    }

    private fun <TFlow> handleSyntheticAdvancedServerAck(
        context: SyntheticServerAckContext<TFlow>
    ): SyntheticServerAckResult {
        val nextAckState = ServerAckStateSupport.nextAckState(
            currentState = context.stateOf(context.current),
            sequenceNumber = context.sequenceNumber,
            acknowledgementNumber = context.acknowledgementNumber,
            now = context.now
        )
        FlowCacheSupport.updateIfPresent(context.flowCache, context.flowKey) {
            context.updateAckedFlow(it, nextAckState)
        }
        return SyntheticServerAckResult(null)
    }

    private data class BridgePayloadEmission<TFlow>(
        val flow: TFlow,
        val pendingSummary: Pair<Int, Int>
    )

    private data class BridgePayloadDispatch(
        val freshSegments: List<PendingServerSegment>
    )

    private data class BridgeSequenceState(
        val nextServerSequence: Long,
        val clientAcknowledgement: Long
    )

    private data class BridgeFlowSequenceContext<TFlow>(
        val flow: TFlow,
        val sequenceState: BridgeSequenceState
    )

    private fun resolveBridgeSequenceState(
        flowKey: String,
        serverInitialSequence: Long?,
        serverNextSequence: Long?,
        clientInitialSequence: Long?,
        clientNextSequence: Long?
    ): BridgeSequenceState {
        val serverSeqBase = serverInitialSequence ?: synthesizeServerSequence(flowKey)
        return BridgeSequenceState(
            nextServerSequence = serverNextSequence ?: (serverSeqBase + 1),
            clientAcknowledgement = clientNextSequence ?: ((clientInitialSequence ?: 0L) + 1)
        )
    }

    private fun <TFlow> resolveBridgeFlowSequenceContext(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        serverInitialSequenceOf: (TFlow) -> Long?,
        serverNextSequenceOf: (TFlow) -> Long?,
        clientInitialSequenceOf: (TFlow) -> Long?,
        clientNextSequenceOf: (TFlow) -> Long?
    ): BridgeFlowSequenceContext<TFlow>? {
        val flow = synchronized(flowCache) {
            flowCache[flowKey]
        } ?: return null
        return BridgeFlowSequenceContext(
            flow = flow,
            sequenceState = resolveBridgeSequenceState(
                flowKey = flowKey,
                serverInitialSequence = serverInitialSequenceOf(flow),
                serverNextSequence = serverNextSequenceOf(flow),
                clientInitialSequence = clientInitialSequenceOf(flow),
                clientNextSequence = clientNextSequenceOf(flow)
            )
        )
    }

    private fun <TFlow> resolveFlowFromCache(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String
    ): TFlow? {
        return synchronized(flowCache) {
            flowCache[flowKey]
        }
    }

    private fun closeBridgeSession(session: Any) {
        when (session) {
            is HttpsBridgeSocketSession -> session.close()
            is LocalProxyBridgeSocketSession -> session.close()
            is PassthroughTcpSocketSession -> session.close()
        }
    }

    private fun writeBridgeFinPacket(
        request: com.HanFeng.model.PacketInfo,
        sequenceState: BridgeSequenceState
    ) {
        writeTunPacket(
            PacketCodec.buildTcpResponse(
                request = request,
                sequenceNumber = sequenceState.nextServerSequence,
                acknowledgementNumber = sequenceState.clientAcknowledgement,
                flags = TCP_FLAG_FIN or TCP_FLAG_ACK,
                windowSize = DEFAULT_TCP_WINDOW_SIZE
            )
        )
    }

    private fun <TFlow> updateBridgeFinFlow(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        sequenceState: BridgeSequenceState,
        updateFlow: (TFlow, BridgeTerminalStateSupport.BridgeFinTransition) -> TFlow
    ) {
        val transition = BridgeTerminalStateSupport.nextBridgeFinTransition(
            nextServerSequence = sequenceState.nextServerSequence,
            now = System.currentTimeMillis()
        )
        FlowCacheSupport.updateIfPresent(flowCache, flowKey) {
            updateFlow(it, transition)
        }
    }

    private fun <TFlow> resolveBridgeResetPacketState(
        flow: TFlow,
        flowKey: String,
        serverInitialSequenceOf: (TFlow) -> Long?,
        serverNextSequenceOf: (TFlow) -> Long?,
        clientInitialSequenceOf: (TFlow) -> Long?,
        clientNextSequenceOf: (TFlow) -> Long?
    ): BridgeReaderSupport.ResetPacketState {
        return BridgeReaderSupport.resolveResetPacketState(
            serverInitialSequence = serverInitialSequenceOf(flow),
            serverNextSequence = serverNextSequenceOf(flow),
            clientInitialSequence = clientInitialSequenceOf(flow),
            clientNextSequence = clientNextSequenceOf(flow),
            synthesizeServerSequence = { synthesizeServerSequence(flowKey) }
        )
    }

    private fun writeBridgeResetPacket(
        request: com.HanFeng.model.PacketInfo,
        packetState: BridgeReaderSupport.ResetPacketState
    ) {
        writeTunPacket(
            BridgeResetSupport.buildResetPacket(
                request,
                packetState,
                TCP_FLAG_RST,
                TCP_FLAG_ACK,
                DEFAULT_TCP_WINDOW_SIZE
            )
        )
    }

    private fun <TFlow> dispatchBridgeReset(
        flow: TFlow,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        serverInitialSequenceOf: (TFlow) -> Long?,
        serverNextSequenceOf: (TFlow) -> Long?,
        clientInitialSequenceOf: (TFlow) -> Long?,
        clientNextSequenceOf: (TFlow) -> Long?
    ) {
        val packetState = resolveBridgeResetPacketState(
            flow = flow,
            flowKey = flowKey,
            serverInitialSequenceOf = serverInitialSequenceOf,
            serverNextSequenceOf = serverNextSequenceOf,
            clientInitialSequenceOf = clientInitialSequenceOf,
            clientNextSequenceOf = clientNextSequenceOf
        )
        writeBridgeResetPacket(request, packetState)
    }

    private fun dispatchBridgePayload(
        request: com.HanFeng.model.PacketInfo,
        payload: ByteArray,
        sequenceState: BridgeSequenceState
    ): BridgePayloadDispatch {
        val freshSegments = buildServerPayloadSegments(sequenceState.nextServerSequence, payload)
        writeServerPayloadSegments(request, sequenceState.clientAcknowledgement, freshSegments)
        return BridgePayloadDispatch(freshSegments)
    }

    private fun <TFlow> buildBridgePayloadEmission(
        flow: TFlow,
        mergedPendingSegments: List<PendingServerSegment>
    ): BridgePayloadEmission<TFlow> {
        return BridgePayloadEmission(
            flow = flow,
            pendingSummary = mergedPendingSegments.size to pendingServerPayloadBytes(mergedPendingSegments)
        )
    }

    private fun <TFlow> updateBridgePayloadFlow(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        flow: TFlow,
        payload: ByteArray,
        sequenceState: BridgeSequenceState,
        dispatch: BridgePayloadDispatch,
        pendingSegmentsOf: (TFlow) -> List<PendingServerSegment>,
        onOverflow: (TFlow) -> Unit,
        updateFlow: (TFlow, List<PendingServerSegment>, Long, Long, ByteArray, Long) -> TFlow
    ): BridgePayloadEmission<TFlow>? {
        var emission: BridgePayloadEmission<TFlow>? = null
        synchronized(flowCache) {
            val current = flowCache[flowKey] ?: return@synchronized
            val updateResult = BridgeFlowStateSupport.updateServerPayloadState(
                flow = current,
                freshSegments = dispatch.freshSegments,
                currentPendingSegments = pendingSegmentsOf(current),
                mergePendingSegments = ::mergePendingServerSegments,
                pendingBytes = ::pendingServerPayloadBytes,
                maxBufferedServerBytes = MAX_BUFFERED_SERVER_BYTES,
                nextServerSequence = sequenceState.nextServerSequence,
                payload = payload,
                now = System.currentTimeMillis(),
                updateFlow = updateFlow
            )
            if (updateResult == null) {
                onOverflow(current)
                return@synchronized
            }
            FlowCacheSupport.putPruned(flowCache, flowKey, updateResult.nextFlow, 512)
            emission = buildBridgePayloadEmission(flow, updateResult.mergedPendingSegments)
        }
        return emission
    }

    private fun <TFlow> emitBridgePayloadCommon(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        payload: ByteArray,
        selectors: BridgeFlowSelectors<TFlow>,
        onOverflow: (TFlow) -> Unit,
        updateFlow: (TFlow, List<PendingServerSegment>, Long, Long, ByteArray, Long) -> TFlow
    ): BridgePayloadEmission<TFlow>? {
        val context = resolveBridgeFlowSequenceContext(
            flowCache = flowCache,
            flowKey = flowKey,
            serverInitialSequenceOf = selectors.sequence.serverInitialSequenceOf,
            serverNextSequenceOf = selectors.sequence.serverNextSequenceOf,
            clientInitialSequenceOf = selectors.sequence.clientInitialSequenceOf,
            clientNextSequenceOf = selectors.sequence.clientNextSequenceOf
        ) ?: return null
        val flow = context.flow
        val sequenceState = context.sequenceState
        val dispatch = dispatchBridgePayload(request, payload, sequenceState)
        return updateBridgePayloadFlow(
            flowCache = flowCache,
            flowKey = flowKey,
            flow = flow,
            payload = payload,
            sequenceState = sequenceState,
            dispatch = dispatch,
            pendingSegmentsOf = { selectors.pendingSegmentsOf(it) ?: emptyList() },
            onOverflow = onOverflow,
            updateFlow = updateFlow
        )
    }

    private fun <TFlow> emitBridgeFinCommon(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        selectors: BridgeFlowSelectors<TFlow>,
        updateFlow: (TFlow, BridgeTerminalStateSupport.BridgeFinTransition) -> TFlow
    ): TFlow? {
        val context = resolveBridgeFlowSequenceContext(
            flowCache = flowCache,
            flowKey = flowKey,
            serverInitialSequenceOf = selectors.sequence.serverInitialSequenceOf,
            serverNextSequenceOf = selectors.sequence.serverNextSequenceOf,
            clientInitialSequenceOf = selectors.sequence.clientInitialSequenceOf,
            clientNextSequenceOf = selectors.sequence.clientNextSequenceOf
        ) ?: return null
        val flow = context.flow
        val stateOf = selectors.stateOf ?: return null
        if (!BridgeTerminalStateSupport.shouldEmitBridgeFin(stateOf(flow))) return null
        val sequenceState = context.sequenceState
        writeBridgeFinPacket(request, sequenceState)
        updateBridgeFinFlow(flowCache, flowKey, sequenceState, updateFlow)
        return flow
    }

    private fun <TFlow, TSession : Any> closeBridgeFlowCommon(
        flowCache: MutableMap<String, TFlow>,
        sessionCache: MutableMap<String, TSession>,
        flowKey: String,
        message: String,
        logKey: String
    ) {
        BridgeTerminalStateSupport.closeFlow(
            flowCache = flowCache,
            sessionCache = sessionCache,
            flowKey = flowKey,
            closeSession = ::closeBridgeSession
        )
        logDecisionOnce(
            key = logKey,
            message = message,
            minIntervalMillis = 5_000L
        )
    }

    private fun <TFlow> emitBridgeResetCommon(
        flowCache: MutableMap<String, TFlow>,
        flowKey: String,
        request: com.HanFeng.model.PacketInfo,
        message: String,
        selectors: BridgeFlowSelectors<TFlow>,
        closeFlow: (String, String) -> Unit
    ) {
        val flow = resolveFlowFromCache(flowCache, flowKey) ?: return
        dispatchBridgeReset(
            flow = flow,
            flowKey = flowKey,
            request = request,
            serverInitialSequenceOf = selectors.sequence.serverInitialSequenceOf,
            serverNextSequenceOf = selectors.sequence.serverNextSequenceOf,
            clientInitialSequenceOf = selectors.sequence.clientInitialSequenceOf,
            clientNextSequenceOf = selectors.sequence.clientNextSequenceOf
        )
        closeFlow(flowKey, message)
    }

    private fun bufferedClientPayloadBytes(segments: List<ClientPayloadSegment>): Int {
        return TcpSyntheticFlowEngine.payloadBytes(segments.map { it.payload })
    }

    private fun pendingServerPayloadBytes(segments: List<PendingServerSegment>): Int {
        return TcpSyntheticFlowEngine.payloadBytes(segments.map { it.payload })
    }

    private fun writeTunPacket(packet: ByteArray) {
        tunPacketWriter.write(tunOutputStream, packet)
    }

    private fun isMitmFullCaptureCircuitOpen(now: Long = System.currentTimeMillis()): Boolean {
        return mitmFullCaptureDisabledUntil > now
    }

    private fun recordPassthroughSuccess(stage: String) {
        if (!isFullCaptureRoutingActive()) return
        synchronized(passthroughHealthLock) {
            refreshPassthroughHealthWindowLocked(System.currentTimeMillis())
            passthroughHealthAttempts++
        }
        logDecisionOnce(
            key = "passthrough-success:$stage",
            message = "Passthrough $stage succeeded during full-capture routing",
            minIntervalMillis = 30_000L
        )
    }

    private fun recordPassthroughFailure(stage: String, detail: String) {
        val now = System.currentTimeMillis()
        if (!isMitmFullCaptureRoutingActive()) return
        if (isMitmFullCaptureCircuitOpen(now)) return
        val shouldTrip = synchronized(passthroughHealthLock) {
            refreshPassthroughHealthWindowLocked(now)
            passthroughHealthAttempts++
            passthroughHealthFailures++
            shouldTripPassthroughCircuitLocked()
        }
        logDecisionOnce(
            key = "passthrough-failure:$stage:$detail",
            message = "Passthrough $stage failed detail=$detail attempts=$passthroughHealthAttempts failures=$passthroughHealthFailures",
            minIntervalMillis = 10_000L
        )
        if (shouldTrip) {
            tripMitmFullCaptureCircuit("passthrough $stage failed: $detail")
        }
    }

    private fun refreshPassthroughHealthWindowLocked(now: Long) {
        if (passthroughHealthWindowStartedAt == 0L || now - passthroughHealthWindowStartedAt > PASSTHROUGH_HEALTH_WINDOW_MILLIS) {
            passthroughHealthWindowStartedAt = now
            passthroughHealthAttempts = 0
            passthroughHealthFailures = 0
        }
    }

    private fun shouldTripPassthroughCircuitLocked(): Boolean {
        if (passthroughHealthFailures >= PASSTHROUGH_HEALTH_ABSOLUTE_FAILURES) return true
        if (passthroughHealthAttempts < PASSTHROUGH_HEALTH_MIN_ATTEMPTS) return false
        return passthroughHealthFailures * 100 >= passthroughHealthAttempts * PASSTHROUGH_HEALTH_FAILURE_PERCENT
    }

    private fun tripMitmFullCaptureCircuit(reason: String) {
        val now = System.currentTimeMillis()
        val shouldReload = synchronized(passthroughHealthLock) {
            val alreadyOpen = mitmFullCaptureDisabledUntil > now
            mitmFullCaptureDisabledUntil = now + MITM_FULL_CAPTURE_CIRCUIT_COOLDOWN_MILLIS
            passthroughHealthWindowStartedAt = now
            passthroughHealthAttempts = 0
            passthroughHealthFailures = 0
            !alreadyOpen
        }
        LogRepository.append(
            this,
            "MITM full-capture circuit opened for ${MITM_FULL_CAPTURE_CIRCUIT_COOLDOWN_MILLIS}ms reason=$reason"
        )
        FlowCacheSupport.clear(passthroughTcpSocketCache) { it.close() }
        FlowCacheSupport.clear(passthroughUdpSessionCache) { it.close() }
        FlowCacheSupport.clear(passthroughTcpFlowCache)
        if (shouldReload && FeatureSettingsRepository.isAdBlockEnabled(this)) {
            scheduleVpnReload(delayMillis = 80L)
        }
    }

    private fun rememberHttpDecryptTargets(
        question: com.HanFeng.model.DnsQuestion,
        response: ByteArray,
        appName: String,
        vendor: String
    ) {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isProtectedNovelAppDomain(question.domain)) return
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isNovelContentDomain(question.domain)) return
        val domainContext = resolveDomainDecisionContextForApp(question.domain, appName, question.qType)
        val effectiveVendor = domainContext.vendor.takeIf { it.isNotBlank() } ?: vendor
        val matchedRule = domainContext.matchedRule
        val aggressiveNovelBlock = RuleRepository.shouldAggressivelyBlockForNovelApp(this, question.domain, appName, effectiveVendor)
        val generalAdTraffic = shouldTreatAsGeneralAdTraffic(question.domain, effectiveVendor, appName)
        if (matchedRule == null && !aggressiveNovelBlock && !generalAdTraffic) {
            if (isProtectedTrafficDomain(question.domain)) return
            return
        }
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 120_000L)
        val routeEntries = mutableListOf<HttpDecryptRouteRepository.RouteEntry>()
        synchronized(httpDecryptIpCache) {
            pruneHttpDecryptTargetsLocked()
            val cacheEntries = mutableListOf<Pair<String, HttpDecryptTarget>>()
            addresses.forEach { address ->
                val ip = formatAddress(address)
                val prefixLength = address.size * 8
                cacheEntries += ip to HttpDecryptTarget(
                    domain = question.domain.lowercase(),
                    vendor = effectiveVendor,
                    appName = appName,
                    source = "direct",
                    expiresAt = expiresAt
                )
                routeEntries += HttpDecryptRouteRepository.RouteEntry(
                    ip = ip,
                    prefixLength = prefixLength,
                    domain = question.domain.lowercase(),
                    vendor = effectiveVendor,
                    expiresAt = expiresAt
                )
            }
            ExpiringTargetCacheSupport.putAllPrunedLocked(httpDecryptIpCache, cacheEntries, 1024)
        }
        if (HttpDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload(
                forceImmediate = shouldForceImmediateDecryptRouteReload(question.domain, appName, effectiveVendor, matchedRule)
            )
        }
    }

    private fun maybeApplyMitmLearningSignals(
        appName: String,
        domain: String,
        addresses: List<ByteArray>,
        signalType: MitmLearningEngine.SignalType
    ) {
        if (!mitmLearningModeEnabled || !shouldUseActiveMitmRouting()) return
        addresses.forEach { address ->
            maybeApplyMitmLearningSignal(
                MitmLearningEngine.Signal(
                    appName = appName,
                    domain = domain,
                    ip = formatAddress(address),
                    type = signalType
                )
            )
        }
    }

    private fun maybeApplyMitmLearningSignal(signal: MitmLearningEngine.Signal) {
        if (!mitmLearningModeEnabled || !shouldUseActiveMitmRouting()) return
        val candidate = MitmLearningEngine.observe(signal, enabled = true) ?: return
        val address = runCatching { InetAddress.getByName(candidate.ip) }.getOrNull() ?: return
        val prefixLength = address.address.size * 8
        val expiresAt = System.currentTimeMillis() + candidate.ttlMillis
        synchronized(httpsDecryptIpCache) {
            pruneHttpsDecryptTargetsLocked()
            ExpiringTargetCacheSupport.putAllPrunedLocked(
                httpsDecryptIpCache,
                listOf(
                    candidate.ip to HttpsDecryptTarget(
                        domain = candidate.domain,
                        vendor = GENERIC_AD_VENDOR_LABEL,
                        appName = candidate.appName,
                        source = "learning:${candidate.reasons.joinToString("+")}",
                        expiresAt = expiresAt
                    )
                ),
                1024
            )
        }
        val changed = HttpsDecryptRouteRepository.upsertRoutes(
            this,
            listOf(
                HttpsDecryptRouteRepository.RouteEntry(
                    ip = candidate.ip,
                    prefixLength = prefixLength,
                    domain = candidate.domain,
                    vendor = GENERIC_AD_VENDOR_LABEL,
                    expiresAt = expiresAt
                )
            )
        )
        StatsRepository.recordLearningCandidate(this, GENERIC_AD_VENDOR_LABEL, candidate.appName)
        UserAdFeedbackManager.recordNetworkActivity(
            this,
            UserAdFeedbackManager.NetworkActivity(
                appName = candidate.appName,
                host = candidate.domain,
                ip = candidate.ip,
                protocol = "HTTPS",
                source = "mitm_learning_candidate",
                score = candidate.score,
                learningCandidate = true
            )
        )
        LogRepository.append(
            this,
            "MITM learning candidate domain=${candidate.domain} ip=${candidate.ip} app=${candidate.appName} score=${candidate.score} ttl=${candidate.ttlMillis} reasons=${candidate.reasons.joinToString(",")} routeChanged=$changed"
        )
        VpnHealthChecker.updateRuntime(this, dynamicDecryptRouteCount = HttpsDecryptRouteRepository.getRoutes(this).size)
        if (changed) requestHttpDecryptRouteReload(forceImmediate = candidate.score >= 10)
    }

    private fun pruneHttpDecryptTargetsLocked() {
        ExpiringTargetCacheSupport.pruneExpiredLocked(
            cache = httpDecryptIpCache,
            now = System.currentTimeMillis(),
            expiresAt = { it.expiresAt }
        )
    }

    private fun maybePruneRouteCaches() {
        val now = System.currentTimeMillis()
        if (!RouteCacheMaintenanceSupport.shouldRunCheck(now, lastRouteCachePruneCheckAt)) return
        lastRouteCachePruneCheckAt = now
        synchronized(adIpTargetCache) {
            pruneAdIpTargetsLocked()
        }
        synchronized(httpDecryptIpCache) {
            lastHttpDecryptPruneAt = RouteCacheMaintenanceSupport.pruneIfDue(
                now = now,
                lastPruneAt = lastHttpDecryptPruneAt,
                pruneIntervalMillis = routeCachePruneIntervalMillis,
                prune = ::pruneHttpDecryptTargetsLocked
            )
        }
        synchronized(httpsDecryptIpCache) {
            lastHttpsDecryptPruneAt = RouteCacheMaintenanceSupport.pruneIfDue(
                now = now,
                lastPruneAt = lastHttpsDecryptPruneAt,
                pruneIntervalMillis = routeCachePruneIntervalMillis,
                prune = ::pruneHttpsDecryptTargetsLocked
            )
        }
        synchronized(quicRouteCache) {
            lastQuicRoutePruneAt = RouteCacheMaintenanceSupport.pruneIfDue(
                now = now,
                lastPruneAt = lastQuicRoutePruneAt,
                pruneIntervalMillis = routeCachePruneIntervalMillis,
                prune = ::pruneQuicTargetsLocked
            )
        }
    }

    private fun rememberHttpDecryptAliasTargets(
        question: com.HanFeng.model.DnsQuestion,
        aliasTargets: List<String>,
        response: ByteArray,
        appName: String
    ) {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return
        if (shouldSkipDecryptAliasChain(question.domain, appName)) return
        val aliasContexts = HashMap<String, DomainDecisionContext>(aliasTargets.size)
        val matchedAliases = aliasTargets.filter { aliasTarget ->
            if (isProtectedTrafficDomain(aliasTarget)) return@filter false
            if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isProtectedNovelAppDomain(aliasTarget)) return@filter false
            if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isNovelContentDomain(aliasTarget)) return@filter false
            val aliasContext = resolveAliasDecisionContext(aliasContexts, aliasTarget, appName, question.qType)
            shouldTreatAsTrackedAdTarget(
                domain = aliasTarget,
                appName = appName,
                vendor = aliasContext.vendor,
                matchedRule = aliasContext.matchedRule,
                includeProtectedNovelUrl = false,
                includeForceNovelQuic = false
            )
        }.distinct()
        if (matchedAliases.isEmpty()) return
        val matchedAliasContexts = matchedAliases.associateWith { matchedAlias ->
            resolveAliasDecisionContext(aliasContexts, matchedAlias, appName, question.qType)
        }
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 120_000L)
        val routeEntries = mutableListOf<HttpDecryptRouteRepository.RouteEntry>()
        synchronized(httpDecryptIpCache) {
            pruneHttpDecryptTargetsLocked()
            val cacheEntries = mutableListOf<Pair<String, HttpDecryptTarget>>()
            matchedAliases.forEach { matchedAlias ->
                val vendor = matchedAliasContexts.getValue(matchedAlias).vendor
                addresses.forEach { address ->
                    val ip = formatAddress(address)
                    val prefixLength = address.size * 8
                    cacheEntries += ip to HttpDecryptTarget(
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
            }
            ExpiringTargetCacheSupport.putAllPrunedLocked(httpDecryptIpCache, cacheEntries, 1024)
        }
        val shouldForceImmediateReload = matchedAliases.any { matchedAlias ->
            val aliasContext = matchedAliasContexts.getValue(matchedAlias)
            shouldForceImmediateDecryptRouteReload(matchedAlias, appName, aliasContext.vendor, aliasContext.matchedRule)
        }
        val logVendor = matchedAliases.firstOrNull()?.let { matchedAliasContexts.getValue(it).vendor } ?: ""
        if (HttpDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload(
                forceImmediate = shouldForceImmediateReload
            )
            logDecisionOnce(
                key = "http-decrypt-alias:${question.domain.lowercase()}",
                message = "Registered HTTP decrypt alias targets source=${question.domain} aliases=${matchedAliases.joinToString(",")} app=$appName vendor=$logVendor ips=${routeEntries.joinToString(",") { it.ip }}",
                minIntervalMillis = 15_000L
            )
        } else {
            logDecisionOnce(
                key = "http-decrypt-alias-skip:${question.domain.lowercase()}",
                message = "HTTP decrypt alias targets already active source=${question.domain} aliases=${matchedAliases.joinToString(",")} app=$appName vendor=$logVendor",
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
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isProtectedNovelAppDomain(question.domain)) return
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isNovelContentDomain(question.domain)) return
        val domainContext = resolveDomainDecisionContextForApp(question.domain, appName, question.qType)
        val effectiveVendor = domainContext.vendor.takeIf { it.isNotBlank() } ?: vendor
        if (!shouldTrackHttpsMitmTarget(question.domain, question.qType, appName, effectiveVendor, domainContext.matchedRule)) {
            if (isProtectedTrafficDomain(question.domain)) return
            return
        }
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 120_000L)
        val routeEntries = mutableListOf<HttpsDecryptRouteRepository.RouteEntry>()
        synchronized(httpsDecryptIpCache) {
            pruneHttpsDecryptTargetsLocked()
            val cacheEntries = mutableListOf<Pair<String, HttpsDecryptTarget>>()
            addresses.forEach { address ->
                val ip = formatAddress(address)
                val prefixLength = address.size * 8
                cacheEntries += ip to HttpsDecryptTarget(
                    domain = question.domain.lowercase(),
                    vendor = effectiveVendor,
                    appName = appName,
                    source = "direct",
                    expiresAt = expiresAt
                )
                routeEntries += HttpsDecryptRouteRepository.RouteEntry(
                    ip = ip,
                    prefixLength = prefixLength,
                    domain = question.domain.lowercase(),
                    vendor = effectiveVendor,
                    expiresAt = expiresAt
                )
            }
            ExpiringTargetCacheSupport.putAllPrunedLocked(httpsDecryptIpCache, cacheEntries, 1024)
        }
        if (HttpsDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload(
                forceImmediate = shouldForceImmediateDecryptRouteReload(question.domain, appName, effectiveVendor, domainContext.matchedRule)
            )
        }
    }

    private fun pruneHttpsDecryptTargetsLocked() {
        ExpiringTargetCacheSupport.pruneExpiredLocked(
            cache = httpsDecryptIpCache,
            now = System.currentTimeMillis(),
            expiresAt = { it.expiresAt }
        )
    }

    private fun rememberQuicTargets(
        question: com.HanFeng.model.DnsQuestion,
        response: ByteArray,
        appName: String,
        vendor: String
    ) {
        val domainContext = resolveDomainDecisionContextForApp(question.domain, appName, question.qType)
        val effectiveVendor = domainContext.vendor.takeIf { it.isNotBlank() } ?: vendor
        val shouldTrack = domainContext.matchedRule != null ||
            RuleRepository.shouldAggressivelyBlockForNovelApp(this, question.domain, appName, effectiveVendor) ||
            RuleRepository.shouldForceNovelQuicBlock(question.domain, appName, effectiveVendor)
        if (!shouldTrack) return
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 180_000L)
        synchronized(quicRouteCache) {
            pruneQuicTargetsLocked()
            ExpiringTargetCacheSupport.putAllPrunedLocked(
                cache = quicRouteCache,
                entries = addresses.map { address ->
                    formatAddress(address) to QuicRouteTarget(
                        domain = question.domain.lowercase(),
                        vendor = effectiveVendor,
                        appName = appName,
                        source = "direct",
                        expiresAt = expiresAt
                    )
                },
                maxSize = 2048
            )
        }
    }

    private fun rememberAdIpTargets(
        question: com.HanFeng.model.DnsQuestion,
        response: ByteArray,
        appName: String,
        vendor: String
    ) {
        val domain = question.domain.lowercase()
        val domainContext = resolveDomainDecisionContextForApp(domain, appName, question.qType)
        val effectiveVendor = domainContext.vendor.takeIf { it.isNotBlank() } ?: vendor
        if (!shouldTrackAdIpTarget(domain, appName, effectiveVendor, question.qType, domainContext.matchedRule)) return
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 180_000L)
        synchronized(adIpTargetCache) {
            pruneAdIpTargetsLocked()
            ExpiringTargetCacheSupport.putAllPrunedLocked(
                cache = adIpTargetCache,
                entries = addresses.map { address ->
                    formatAddress(address) to AdIpTarget(
                        domain = domain,
                        vendor = effectiveVendor,
                        appName = appName,
                        source = "direct",
                        expiresAt = expiresAt
                    )
                },
                maxSize = 2048
            )
        }
    }

    private fun rememberAdIpTargetsForAliases(
        question: com.HanFeng.model.DnsQuestion,
        aliasTargets: List<String>,
        response: ByteArray,
        appName: String
    ) {
        val aliasContexts = HashMap<String, DomainDecisionContext>(aliasTargets.size)
        val matchedAliases = aliasTargets.filter { aliasTarget ->
            val aliasContext = resolveAliasDecisionContext(aliasContexts, aliasTarget, appName, question.qType)
            shouldTrackAdIpTarget(aliasTarget, appName, aliasContext.vendor, question.qType, aliasContext.matchedRule)
        }.map { it.lowercase() }.distinct()
        if (matchedAliases.isEmpty()) return
        val matchedAliasContexts = matchedAliases.associateWith { matchedAlias ->
            resolveAliasDecisionContext(aliasContexts, matchedAlias, appName, question.qType)
        }
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 180_000L)
        synchronized(adIpTargetCache) {
            pruneAdIpTargetsLocked()
            val cacheEntries = mutableListOf<Pair<String, AdIpTarget>>()
            matchedAliases.forEach { matchedAlias ->
                val vendor = matchedAliasContexts.getValue(matchedAlias).vendor
                addresses.forEach { address ->
                    cacheEntries += formatAddress(address) to AdIpTarget(
                        domain = matchedAlias,
                        vendor = vendor,
                        appName = appName,
                        source = "alias",
                        expiresAt = expiresAt
                    )
                }
            }
            ExpiringTargetCacheSupport.putAllPrunedLocked(adIpTargetCache, cacheEntries, 2048)
        }
    }

    private fun shouldTrackAdIpTarget(
        domain: String,
        appName: String,
        vendor: String,
        qType: Int?,
        matchedRule: BlockRule?
    ): Boolean {
        val resolvedRule = matchedRule ?: RuleRepository.findMatchingRule(this, domain, qType)
        if (RuleRepository.isWhitelistedDomain(domain)) return false
        if (RuleRepository.isSensitiveAuthDomain(domain)) return false
        if (isProtectedTrafficDomain(domain) && resolvedRule == null) return false
        return resolvedRule != null
    }

    private fun rememberQuicAliasTargets(
        question: com.HanFeng.model.DnsQuestion,
        aliasTargets: List<String>,
        response: ByteArray,
        appName: String
    ) {
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val aliasContexts = HashMap<String, DomainDecisionContext>(aliasTargets.size)
        val matchedAliases = aliasTargets.map { it.lowercase() }.filter { alias ->
            val aliasContext = resolveAliasDecisionContext(aliasContexts, alias, appName, question.qType)
            val novelSignals = evaluateNovelBlockingSignals(
                domain = alias,
                appName = appName,
                vendor = aliasContext.vendor,
                includeProtectedNovelUrl = false,
                includeForceNovelQuic = true
            )
            aliasContext.matchedRule != null ||
                novelSignals.aggressiveNovelBlock ||
                novelSignals.forcedNovelQuicBlock
        }.distinct()
        if (matchedAliases.isEmpty()) return
        val matchedAliasContexts = matchedAliases.associateWith { matchedAlias ->
            resolveAliasDecisionContext(aliasContexts, matchedAlias, appName, question.qType)
        }
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 180_000L)
        synchronized(quicRouteCache) {
            pruneQuicTargetsLocked()
            val cacheEntries = mutableListOf<Pair<String, QuicRouteTarget>>()
            matchedAliases.forEach { matchedAlias ->
                val vendor = matchedAliasContexts.getValue(matchedAlias).vendor
                addresses.forEach { address ->
                    cacheEntries += formatAddress(address) to QuicRouteTarget(
                        domain = matchedAlias,
                        vendor = vendor,
                        appName = appName,
                        source = "alias",
                        expiresAt = expiresAt
                    )
                }
            }
            ExpiringTargetCacheSupport.putAllPrunedLocked(quicRouteCache, cacheEntries, 2048)
        }
    }

    private fun pruneQuicTargetsLocked() {
        ExpiringTargetCacheSupport.pruneExpiredLocked(
            cache = quicRouteCache,
            now = System.currentTimeMillis(),
            expiresAt = { it.expiresAt }
        )
    }

    private fun pruneAdIpTargetsLocked() {
        ExpiringTargetCacheSupport.pruneExpiredLocked(
            cache = adIpTargetCache,
            now = System.currentTimeMillis(),
            expiresAt = { it.expiresAt }
        )
    }

    private fun rememberHttpsDecryptAliasTargets(
        question: com.HanFeng.model.DnsQuestion,
        aliasTargets: List<String>,
        response: ByteArray,
        appName: String
    ) {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return
        if (shouldSkipDecryptAliasChain(question.domain, appName)) return
        val aliasContexts = HashMap<String, DomainDecisionContext>(aliasTargets.size)
        val matchedAliases = aliasTargets.filter { aliasTarget ->
            if (isProtectedTrafficDomain(aliasTarget)) return@filter false
            if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isProtectedNovelAppDomain(aliasTarget)) return@filter false
            if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isNovelContentDomain(aliasTarget)) return@filter false
            val aliasContext = resolveAliasDecisionContext(aliasContexts, aliasTarget, appName, question.qType)
            shouldTrackHttpsMitmTarget(aliasTarget, question.qType, appName, aliasContext.vendor, aliasContext.matchedRule)
        }.distinct()
        if (matchedAliases.isEmpty()) return
        val matchedAliasContexts = matchedAliases.associateWith { matchedAlias ->
            resolveAliasDecisionContext(aliasContexts, matchedAlias, appName, question.qType)
        }
        val addresses = DnsMessageParser.extractAnswerAddresses(response, question)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val expiresAt = min(now + DnsMessageParser.extractCacheTtlMillis(response), now + 120_000L)
        val routeEntries = mutableListOf<HttpsDecryptRouteRepository.RouteEntry>()
        synchronized(httpsDecryptIpCache) {
            pruneHttpsDecryptTargetsLocked()
            val cacheEntries = mutableListOf<Pair<String, HttpsDecryptTarget>>()
            matchedAliases.forEach { matchedAlias ->
                val vendor = matchedAliasContexts.getValue(matchedAlias).vendor
                addresses.forEach { address ->
                    val ip = formatAddress(address)
                    val prefixLength = address.size * 8
                    cacheEntries += ip to HttpsDecryptTarget(
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
            }
            ExpiringTargetCacheSupport.putAllPrunedLocked(httpsDecryptIpCache, cacheEntries, 1024)
        }
        val shouldForceImmediateReload = matchedAliases.any { matchedAlias ->
            val aliasContext = matchedAliasContexts.getValue(matchedAlias)
            shouldForceImmediateDecryptRouteReload(matchedAlias, appName, aliasContext.vendor, aliasContext.matchedRule)
        }
        val logVendor = matchedAliases.firstOrNull()?.let { matchedAliasContexts.getValue(it).vendor } ?: ""
        if (HttpsDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload(
                forceImmediate = shouldForceImmediateReload
            )
            logDecisionOnce(
                key = "https-decrypt-alias:${question.domain.lowercase()}",
                message = "Registered HTTPS decrypt alias targets source=${question.domain} aliases=${matchedAliases.joinToString(",")} app=$appName vendor=$logVendor ips=${routeEntries.joinToString(",") { it.ip }}",
                minIntervalMillis = 15_000L
            )
        } else {
            logDecisionOnce(
                key = "https-decrypt-alias-skip:${question.domain.lowercase()}",
                message = "HTTPS decrypt alias targets already active source=${question.domain} aliases=${matchedAliases.joinToString(",")} app=$appName vendor=$logVendor",
                minIntervalMillis = 15_000L
            )
        }
    }

    private fun shouldTrackHttpsMitmTarget(
        domain: String,
        qType: Int?,
        appName: String,
        vendor: String,
        matchedRule: BlockRule? = null
    ): Boolean {
        val resolvedRule = matchedRule ?: RuleRepository.findMatchingRule(this, domain, qType)
        val novelApp = RuleRepository.isNovelAppHint(appName)
        if (novelApp && RuleRepository.isProtectedNovelAppDomain(domain)) return false
        if (novelApp && RuleRepository.isNovelContentDomain(domain)) return false
        if (isProtectedTrafficDomain(domain) && resolvedRule == null) return false
        return resolvedRule != null
    }

    private fun shouldSkipDecryptAliasChain(domain: String, appName: String): Boolean {
        if (isProtectedTrafficDomain(domain)) return true
        if (RuleRepository.isWhitelistedDomain(domain)) return true
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isProtectedNovelAppDomain(domain)) return true
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isNovelContentDomain(domain)) return true
        return false
    }

    private fun shouldForceImmediateDecryptRouteReload(
        domain: String,
        appName: String,
        vendor: String,
        matchedRule: BlockRule?
    ): Boolean {
        return shouldTreatAsTrackedAdTarget(
            domain = domain,
            appName = appName,
            vendor = vendor,
            matchedRule = matchedRule
        )
    }

    private fun resolveDomainDecisionContextForApp(
        domain: String,
        appName: String,
        qType: Int? = null
    ): DomainDecisionContext {
        val matchedRule = RuleRepository.findMatchingRule(
            context = this,
            domain = domain,
            qType = qType,
            appName = appName
        )
        val vendor = matchedRule?.vendor ?: classifyVendorCached(domain, appName)
        val hasException = matchedRule == null && RuleRepository.isDomainExcepted(this, domain)
        val smartRule = matchedRule
            ?: if (hasException) null else smartDomainScoreAndCreateRule(domain, appName, vendor)
            ?: if (hasException) null else generalAdTrafficRule(domain, appName, vendor)
        
        return DomainDecisionContext(
            appName = appName,
            matchedRule = smartRule,
            vendor = vendor,
            reason = if (matchedRule != null) "matched-rule:${matchedRule.id.take(8)}" 
                     else if (smartRule != null) "smart-score-suspicious"
                     else explainDomainDecisionReason(domain, appName, vendor)
        )
    }
    
    /**
     * 智能域名评分：即使规则库没有，也能识别可疑广告域名
     * @return 如果评分达到阈值，返回一个虚拟的规则；否则返回 null
     */
    private fun smartDomainScoreAndCreateRule(domain: String, appName: String, vendor: String): BlockRule? {
        val normalizedDomain = domain.trim().lowercase()
        if (normalizedDomain.isBlank()) return null
        
        if (RuleRepository.isDomainExcepted(this, normalizedDomain)) return null
        // 白名单保护：这些域名不参与智能评分
        if (RuleRepository.isWhitelistedDomain(normalizedDomain)) return null
        if (RuleRepository.isSensitiveAuthDomain(normalizedDomain)) return null
        if (RuleRepository.shouldProtectMediaTraffic(normalizedDomain)) return null
        if (RuleRepository.shouldProtectBusinessTraffic(normalizedDomain)) return null
        if (RuleRepository.isGameCoreDomain(normalizedDomain)) return null
        if (RuleRepository.isSocialCoreDomain(normalizedDomain)) return null
        val novelApp = RuleRepository.isNovelAppHint(appName)
        if (novelApp && RuleRepository.isProtectedNovelAppDomain(normalizedDomain)) return null
        if (novelApp && RuleRepository.isNovelContentDomain(normalizedDomain)) return null
        
        var score = 0
        val reasons = mutableListOf<String>()
        
        // 1. 子域名特征（权重：3 分）
        val adSubdomainPatterns = listOf(
            "ad", "ads", "adserver", "adx", "adv", "adnet",
            "banner", "splash", "promo", "promotion",
            "track", "tracking", "analytics", "beacon",
            "log", "logger", "stat", "stats", "metric",
            "sdk", "material", "creative"
        )
        val domainPrefix = normalizedDomain.substringBefore('.')
        val matchedSubdomain = adSubdomainPatterns.find { 
            domainPrefix.startsWith(it) || domainPrefix == it || domainPrefix.startsWith("$it-") 
        }
        if (matchedSubdomain != null) {
            score += 3
            reasons += "subdomain:$matchedSubdomain"
        }
        
        // 2. 域名关键词（权重：2 分）
        val adKeywords = listOf("ads", "banner", "promo", "track", "tracking", "sdk", "material", "creative")
        val matchedKeyword = adKeywords.find { normalizedDomain.contains(it) }
        if (matchedKeyword != null) {
            score += 2
            reasons += "keyword:$matchedKeyword"
        }
        
        // 3. 已知广告 SDK 域名模式（权重：4 分）
        val sdkPatterns = listOf(
            "pangolin", "pangle", "gromore", "csj", "cjs", "oceanengine",
            "gdt", "sigmob", "mobvista", "mintegral", "topon", "tradplus",
            "adscope", "kswad", "tanx", "alimama", "umeng", "mobads",
            "baidumobads", "huaweiads", "oppoads", "vivo_ad", "xiaomi_ad"
        )
        val matchedSdk = sdkPatterns.find { normalizedDomain.contains(it) }
        if (matchedSdk != null) {
            score += 4
            reasons += "sdk:$matchedSdk"
        }
        
        // 4. 路径式域名特征（权重：2 分）
        val pathLikePatterns = listOf("/ad/", "/ads/", "/banner/", "/promo/", "/tracking/")
        val matchedPath = pathLikePatterns.find { normalizedDomain.contains(it) }
        if (matchedPath != null) {
            score += 2
            reasons += "path:$matchedPath"
        }
        
        // 5. App 类型加成（小说/视频/社区 App 的可疑域名更可信）
        if (novelApp && score >= 2) {
            score += 2
            reasons += "novel-app-boost"
        }
        if (isAggressiveAdAppContext(appName) && score >= 2) {
            score += 1
            reasons += "aggressive-app-boost"
        }
        
        // 6. 供应商信号加成（未知供应商 + 可疑域名 = 更可疑）
        if (vendor == "其它 (Other)" && score >= 3) {
            score += 1
            reasons += "unknown-vendor-boost"
        }
        
        // 评分达到阈值才拦截
        val threshold = if (novelApp) 3 else 5
        
        return if (score >= threshold) {
            // 创建一个虚拟规则用于标记（使用 RuleSource.REFERENCE 标记为智能识别）
            BlockRule(
                id = "smart-score-$domain",
                domain = domain,
                vendor = vendor,
                source = RuleSource.REFERENCE,
                keywordPattern = reasons.joinToString(", ")
            )
        } else {
            null
        }
    }

    private fun generalAdTrafficRule(domain: String, appName: String, vendor: String): BlockRule? {
        val normalizedDomain = domain.trim().lowercase()
        if (normalizedDomain.isBlank()) return null
        if (!shouldTreatAsGeneralAdTraffic(normalizedDomain, vendor, appName)) return null
        return BlockRule(
            id = "general-ad-$normalizedDomain",
            domain = normalizedDomain,
            vendor = vendor,
            source = RuleSource.REFERENCE,
            keywordPattern = "general-ad-traffic"
        )
    }

    private fun resolveAliasDecisionContext(
        aliasContexts: MutableMap<String, DomainDecisionContext>,
        aliasTarget: String,
        appName: String,
        qType: Int?
    ): DomainDecisionContext {
        return aliasContexts.getOrPut(aliasTarget) {
            resolveDomainDecisionContextForApp(aliasTarget, appName, qType)
        }
    }

    private fun shouldTreatAsTrackedAdTarget(
        domain: String,
        appName: String,
        vendor: String,
        matchedRule: Any?,
        includeProtectedNovelUrl: Boolean = true,
        includeForceNovelQuic: Boolean = true
    ): Boolean {
        if (matchedRule != null) return true
        if (!shizukuStrictAppAdBlockEnabled) return false

        val normalizedDomain = domain.trim().lowercase()
        if (normalizedDomain.isBlank()) return false
        if (RuleRepository.isWhitelistedDomain(normalizedDomain)) return false
        if (RuleRepository.isSensitiveAuthDomain(normalizedDomain)) return false
        if (RuleRepository.shouldProtectMediaTraffic(normalizedDomain)) return false
        if (RuleRepository.shouldProtectBusinessTraffic(normalizedDomain)) return false
        if (RuleRepository.isGameCoreDomain(normalizedDomain)) return false
        if (RuleRepository.isSocialCoreDomain(normalizedDomain) && !RuleRepository.isCommunityAppHint(appName)) return false

        val novelSignals = evaluateNovelBlockingSignals(
            domain = normalizedDomain,
            appName = appName,
            vendor = vendor,
            includeProtectedNovelUrl = includeProtectedNovelUrl,
            includeForceNovelQuic = includeForceNovelQuic
        )
        if (novelSignals.aggressiveNovelBlock || novelSignals.forcedNovelQuicBlock || novelSignals.protectedNovelUrlBlock) {
            return true
        }

        if (shouldTreatAsGeneralAdTraffic(normalizedDomain, vendor, appName)) {
            return true
        }

        val aggressiveApp = isAggressiveAdAppContext(appName)
        if (!aggressiveApp) return false
        if (RuleRepository.shouldForcePushRecommendInspection(normalizedDomain, appName, vendor)) return true
        if (RuleRepository.looksLikeAdSdkInfraDomain(normalizedDomain, vendor)) return true
        return false
    }

    private fun evaluateNovelBlockingSignals(
        domain: String,
        appName: String,
        vendor: String,
        includeProtectedNovelUrl: Boolean = true,
        includeForceNovelQuic: Boolean = true
    ): NovelBlockingSignals {
        val aggressiveNovelBlock = RuleRepository.shouldAggressivelyBlockForNovelApp(this, domain, appName, vendor)
        val forcedNovelQuicBlock = includeForceNovelQuic && RuleRepository.shouldForceNovelQuicBlock(domain, appName, vendor)
        val protectedNovelUrlBlock = includeProtectedNovelUrl &&
            RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(this, domain, null, appName)
        return NovelBlockingSignals(
            aggressiveNovelBlock = aggressiveNovelBlock,
            forcedNovelQuicBlock = forcedNovelQuicBlock,
            protectedNovelUrlBlock = protectedNovelUrlBlock
        )
    }

    private fun queryUpstreamDns(payload: ByteArray): UpstreamDnsSupport.UpstreamDnsResult? {
        val dohResult = queryUpstreamDnsOverHttps(payload)
        if (dohResult != null) return dohResult

        return UpstreamDnsSupport.queryUpstreamDns(
            payload = payload,
            servers = resolveDnsServers(),
            acquireSocket = ::acquireDnsSocket,
            releaseSocket = ::releaseDnsSocket,
            markSuccess = ::markUpstreamSuccess,
            markFailure = ::markUpstreamFailure,
            onServerFailed = { server, error ->
                logDecisionOnce(
                    key = "upstream-dns-failed:${server.hostAddress}",
                    message = "Upstream DNS ${server.hostAddress} failed: ${error.message ?: error.javaClass.simpleName}",
                    minIntervalMillis = 60_000L
                )
            }
        )
    }

    private fun queryUpstreamDnsOverHttps(payload: ByteArray): UpstreamDnsSupport.UpstreamDnsResult? {
        for (dohUrl in DnsOverHttpsClient.DOH_SERVERS) {
            val result = DnsOverHttpsClient.query(this, payload, dohUrl)
            if (result != null) {
                val serverHost = runCatching { java.net.URL(dohUrl).host }.getOrDefault(dohUrl)
                logDecisionOnce(
                    key = "doh-success:$dohUrl",
                    message = "DoH query succeeded via $serverHost",
                    minIntervalMillis = 120_000L
                )
                return UpstreamDnsSupport.UpstreamDnsResult(
                    server = java.net.InetAddress.getLoopbackAddress(),
                    response = result.response
                )
            }
        }
        return null
    }

    private fun acquireDnsSocket(server: InetAddress): DatagramSocket? {
        return UpstreamDnsSupport.acquireDnsSocket(
            pool = dnsSocketPool,
            lock = dnsSocketPoolLock,
            server = server,
            createSocket = {
                runCatching {
                    DatagramSocket().also { socket ->
                        if (!protect(socket)) {
                            runCatching { socket.close() }
                            throw java.io.IOException("Protect upstream DNS socket failed")
                        }
                    }
                }.getOrNull()
            }
        )
    }

    private fun releaseDnsSocket(server: InetAddress, socket: DatagramSocket) {
        UpstreamDnsSupport.releaseDnsSocket(
            pool = dnsSocketPool,
            lock = dnsSocketPoolLock,
            server = server,
            socket = socket
        )
    }

    private fun logDecisionOnce(key: String, message: String, minIntervalMillis: Long) {
        val now = System.currentTimeMillis()
        val shouldLog = synchronized(decisionLogCache) {
            DecisionLogSupport.shouldLogLocked(
                cache = decisionLogCache,
                key = key,
                now = now,
                minIntervalMillis = minIntervalMillis
            )
        }
        if (!shouldLog) return
        LogRepository.append(this, message)
    }

    private fun resolveDnsServers(): List<InetAddress> {
        val now = System.currentTimeMillis()
        cachedDnsServers?.takeIf { it.expiresAt > now }?.let { return it.servers }
        val dynamicServers = currentLinkProperties()
            .asSequence()
            .flatMap { it.dnsServers.asSequence() }
            .distinctBy { it.hostAddress ?: it.hostName }
            .filterNot { handledDnsHosts.contains(it.hostAddress ?: "") }
            .toList()
        val candidates = (dynamicServers + upstreamFallbackDnsServers)
            .distinctBy { it.hostAddress ?: it.hostName }
        val sorted = candidates.sortedWith(
            compareBy<InetAddress> { currentUpstreamState(it).cooldownUntil > now }
                .thenBy { currentUpstreamState(it).failureCount }
                .thenByDescending { currentUpstreamState(it).lastSuccessAt }
                .thenBy { it.hostAddress ?: it.hostName }
        )
        synchronized(dnsServerCacheLock) {
            cachedDnsServers = DnsRuntimeSupport.cacheResolvedServers(
                lock = dnsServerCacheLock,
                servers = sorted,
                now = now,
                ttlMillis = dnsServerCacheTtlMillis
            )
        }
        logDecisionOnce(
            key = "resolve-dns-servers:${sorted.joinToString(",") { it.hostAddress ?: it.hostName }}",
            message = "Resolved upstream DNS servers dynamic=${dynamicServers.joinToString("|") { it.hostAddress ?: it.hostName }} final=${sorted.joinToString("|") { it.hostAddress ?: it.hostName }} networks=${describeUnderlyingNetworks()}",
            minIntervalMillis = 5_000L
        )
        return sorted
    }

    private fun readCachedDnsResponse(question: com.HanFeng.model.DnsQuestion, queryPayload: ByteArray): ByteArray? {
        return DnsRuntimeSupport.readCachedDnsResponse(
            cache = dnsResponseCache,
            question = question,
            queryPayload = queryPayload,
            now = System.currentTimeMillis(),
            staleCacheGraceMillis = staleCacheGraceMillis
        )
    }

    private fun readStaleCachedDnsResponse(question: com.HanFeng.model.DnsQuestion, queryPayload: ByteArray): ByteArray? {
        return DnsRuntimeSupport.readStaleCachedDnsResponse(
            cache = dnsResponseCache,
            question = question,
            queryPayload = queryPayload,
            now = System.currentTimeMillis(),
            staleCacheGraceMillis = staleCacheGraceMillis
        )
    }

    private fun cacheDnsResponse(question: com.HanFeng.model.DnsQuestion, response: ByteArray) {
        DnsRuntimeSupport.cacheDnsResponse(
            cache = dnsResponseCache,
            question = question,
            response = response,
            now = System.currentTimeMillis()
        )
    }

    private fun markUpstreamSuccess(server: InetAddress) {
        DnsRuntimeSupport.markUpstreamSuccess(upstreamServerStates, server, System.currentTimeMillis())
    }

    private fun markUpstreamFailure(server: InetAddress) {
        DnsRuntimeSupport.markUpstreamFailure(upstreamServerStates, server, System.currentTimeMillis())
    }

    private fun currentUpstreamState(server: InetAddress): DnsRuntimeSupport.UpstreamServerState {
        return DnsRuntimeSupport.currentUpstreamState(upstreamServerStates, server)
    }

    private fun isLocalProxyEndpoint(host: String, port: Int): Boolean {
        return TrafficDecisionEngine.isLocalProxyEndpoint(
            host = host,
            port = port,
            configHost = localProxyCoexistConfig.host,
            configPort = localProxyCoexistConfig.port
        )
    }

    private fun isLocalLoopOrProxyEndpoint(host: String, port: Int): Boolean {
        return TrafficDecisionEngine.isLocalLoopOrProxyEndpoint(
            host = host,
            port = port,
            configHost = localProxyCoexistConfig.host,
            configPort = localProxyCoexistConfig.port
        )
    }

    private fun isKnownPublicDnsEndpoint(host: String): Boolean {
        return forcedDnsRouteHosts.contains(host)
    }

    private fun isCurrentDnsEndpoint(host: String): Boolean {
        if (isKnownPublicDnsEndpoint(host)) return true
        return currentLinkProperties().any { linkProperties ->
            linkProperties.dnsServers.any { it.hostAddress == host }
        }
    }

    private fun shouldHandleDns(address: ByteArray): Boolean {
        val host = formatAddress(address)
        if (handledDnsHosts.contains(host)) return true
        if (isCurrentDnsEndpoint(host)) return true
        if (isKnownPublicDnsEndpoint(host)) return true
        return true
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

    private fun loadForcedDnsRoutes(): List<BlockedIpNetwork> {
        val dynamicServers = currentLinkProperties()
            .asSequence()
            .flatMap { it.dnsServers.asSequence() }
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()
        return (forcedDnsRouteHosts + dynamicServers)
            .distinct()
            .mapNotNull { host ->
                val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@mapNotNull null
                BlockedIpNetwork(
                    addressBytes = address.address,
                    prefixLength = address.address.size * 8,
                    routeAddress = address.hostAddress ?: host
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

    private fun requestHttpDecryptRouteReload(forceImmediate: Boolean = false) {
        if (!isRunning) return
        if (!httpDecryptEnabled) return
        if (!shouldUseActiveMitmRouting()) return
        if (isFullCaptureRoutingActive()) {
            pendingRouteReloadJob?.cancel()
            pendingRouteReloadJob = null
            logDecisionOnce(
                key = "vpn-reload-http-decrypt-routes-skip-full-capture",
                message = "Skipped VPN reload for HTTP decrypt routes because full-capture routing is active",
                minIntervalMillis = 15_000L
            )
            return
        }
        val now = System.currentTimeMillis()
        val elapsed = now - lastHttpRouteReloadAt
        if (forceImmediate && elapsed >= HTTP_DECRYPT_ROUTE_FORCE_MIN_INTERVAL_MILLIS) {
            performHttpDecryptRouteReload("forced")
            return
        }
        if (!forceImmediate && elapsed >= HTTP_DECRYPT_ROUTE_RELOAD_MIN_INTERVAL_MILLIS) {
            performHttpDecryptRouteReload("immediate")
            return
        }
        if (pendingRouteReloadJob?.isActive == true) return
        val baseDelayMillis = if (forceImmediate) {
            (HTTP_DECRYPT_ROUTE_FORCE_MIN_INTERVAL_MILLIS - elapsed).coerceAtLeast(HTTP_DECRYPT_ROUTE_BATCH_WINDOW_MILLIS)
        } else {
            (HTTP_DECRYPT_ROUTE_RELOAD_MIN_INTERVAL_MILLIS - elapsed).coerceAtLeast(HTTP_DECRYPT_ROUTE_BATCH_WINDOW_MILLIS)
        }
        val delayMillis = maxOf(baseDelayMillis, HTTP_DECRYPT_ROUTE_BATCH_WINDOW_MILLIS)
        pendingRouteReloadJob = scope.launch {
            delay(delayMillis)
            if (!isRunning || !httpDecryptEnabled || !FeatureSettingsRepository.isAdBlockEnabled(this@AdBlockVpnService)) {
                return@launch
            }
            performHttpDecryptRouteReload(if (forceImmediate) "forced-batched" else "scheduled-batched")
        }
        logDecisionOnce(
            key = if (forceImmediate) "vpn-reload-http-decrypt-routes-forced-scheduled" else "vpn-reload-http-decrypt-routes-scheduled",
            message = "Scheduled VPN reload for new HTTP decrypt routes delay=${delayMillis}ms forceImmediate=$forceImmediate",
            minIntervalMillis = if (forceImmediate) HTTP_DECRYPT_ROUTE_FORCE_MIN_INTERVAL_MILLIS else HTTP_DECRYPT_ROUTE_RELOAD_MIN_INTERVAL_MILLIS
        )
    }

    private fun performHttpDecryptRouteReload(reason: String) {
        pendingRouteReloadJob?.cancel()
        pendingRouteReloadJob = null
        lastHttpRouteReloadAt = System.currentTimeMillis()
        startService(Intent(this, AdBlockVpnService::class.java).setAction(ACTION_RELOAD))
        logDecisionOnce(
            key = "vpn-reload-http-decrypt-routes",
            message = "Reloaded VPN for new HTTP decrypt routes reason=$reason",
            minIntervalMillis = HTTP_DECRYPT_ROUTE_RELOAD_MIN_INTERVAL_MILLIS
        )
    }

    private fun isFullCaptureRoutingActive(): Boolean {
        return activeFullCaptureMode != FullCaptureRoutingSupport.Mode.NONE
    }

    private fun isMitmFullCaptureRoutingActive(): Boolean {
        return activeFullCaptureMode == FullCaptureRoutingSupport.Mode.MITM_APP ||
            activeFullCaptureMode == FullCaptureRoutingSupport.Mode.MITM_GLOBAL
    }

    private fun shouldUseActiveMitmRouting(): Boolean {
        return ENABLE_ACTIVE_MITM_ROUTING && httpDecryptEnabled &&
            HttpsMitmRepository.isCertificateInstalled(this) &&
            !isMitmFullCaptureCircuitOpen()
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

    private fun findMatchingIpRule(info: com.HanFeng.model.PacketInfo): MatchedIpRule? {
        if (!RuleRepository.hasIpRules(this)) return null
        val targetContext = resolveDestinationAppContext(info) ?: return null
        val destinationIp = targetContext.destinationIp
        val appName = targetContext.appName
        val rule = RuleRepository.findMatchingIpRule(
            context = this,
            ip = destinationIp,
            appName = appName,
            destinationPort = info.destinationPort,
            sourcePort = info.sourcePort
        ) ?: return null
        return MatchedIpRule(rule = rule, appName = appName)
    }

    private fun findMatchingPortOnlyRule(info: com.HanFeng.model.PacketInfo): MatchedIpRule? {
        if (!RuleRepository.hasPortOnlyRules(this)) return null
        val targetContext = resolveDestinationAppContext(info) ?: return null
        val appName = targetContext.appName
        val rule = RuleRepository.findMatchingPortOnlyRule(
            context = this,
            appName = appName,
            destinationPort = info.destinationPort,
            sourcePort = info.sourcePort
        ) ?: return null
        return MatchedIpRule(rule = rule, appName = appName)
    }

    private fun findAdIpTarget(info: com.HanFeng.model.PacketInfo): AdIpTarget? {
        maybePruneRouteCaches()
        val destinationIp = formatAddress(info.destinationAddress)
        if (destinationIp.isBlank()) return null
        return synchronized(adIpTargetCache) {
            adIpTargetCache[destinationIp]?.takeIf {
                it.expiresAt > System.currentTimeMillis() &&
                    !RuleRepository.isWhitelistedDomain(it.domain) &&
                    !isProtectedTrafficDomain(it.domain) &&
                    !RuleRepository.isSensitiveAuthDomain(it.domain)
            }
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

    private val ipv4FormatCache = ConcurrentHashMap<Int, String>(512)

    private fun formatAddress(bytes: ByteArray): String {
        if (bytes.size == 4) {
            val key = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            ipv4FormatCache[key]?.let { return it }
            val result = "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"
            ipv4FormatCache[key] = result
            return result
        }
        if (bytes.size == 16) {
            val sb = StringBuilder(45)
            for (i in bytes.indices step 2) {
                if (i > 0) sb.append(':')
                val hi = bytes[i].toInt() and 0xFF
                val lo = bytes[i + 1].toInt() and 0xFF
                val combined = (hi shl 8) or lo
                if (combined != 0) sb.append(combined.toString(16))
            }
            return sb.toString()
        }
        return InetAddress.getByAddress(bytes).hostAddress ?: ""
    }

    private fun resolveDestinationAppContext(info: com.HanFeng.model.PacketInfo): DestinationAppContext? {
        val destinationIp = formatAddress(info.destinationAddress)
        if (destinationIp.isBlank()) return null
        return DestinationAppContext(
            destinationIp = destinationIp,
            appName = resolveAppName(destinationIp, info)
        )
    }

    private fun resolveAppName(domain: String, info: com.HanFeng.model.PacketInfo): String {
        val cacheKeys = buildCacheKeys(info)
        val normalizedDomain = normalizeDomain(domain)
        readCachedAppName(cacheKeys)?.let { return it }
        readCachedDomainApp(normalizedDomain)?.let {
            cacheAppName(cacheKeys, normalizedDomain, it)
            return it
        }
        readCachedSourcePortApp(cacheKeys)?.let {
            cacheAppName(cacheKeys, normalizedDomain, it)
            return it
        }
        if (info.protocol == OsConstants.IPPROTO_UDP && info.destinationPort != 53 && info.destinationPort != 443) {
            return readCachedPortAppName(cacheKeys) ?: "未知应用"
        }
        val resolved = resolveAppNameByUid(info, cacheKeys)
        if (resolved != null) {
            cacheAppName(cacheKeys, normalizedDomain, resolved)
            return resolved
        }
        return readCachedPortAppName(cacheKeys) ?: "未知应用"
    }

    private fun resolveDomainDecisionContext(
        domain: String,
        info: com.HanFeng.model.PacketInfo,
        qType: Int? = null,
        knownAppName: String? = null,
        knownVendor: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): DomainDecisionContext {
        val appName = knownAppName?.takeIf { it.isNotBlank() } ?: resolveAppName(domain, info)
        val matchedRule = RuleRepository.findMatchingRule(
            context = this,
            domain = domain,
            qType = qType,
            appName = appName,
            destinationPort = destinationPort,
            sourcePort = sourcePort
        )
        val vendor = knownVendor?.takeIf { it.isNotBlank() } ?: matchedRule?.vendor ?: classifyVendorCached(domain, appName)
        val hasException = matchedRule == null && RuleRepository.isDomainExcepted(this, domain)
        val smartRule = matchedRule
            ?: if (hasException) null else smartDomainScoreAndCreateRule(domain, appName, vendor)
            ?: if (hasException) null else generalAdTrafficRule(domain, appName, vendor)
        return DomainDecisionContext(
            appName = appName,
            matchedRule = smartRule,
            vendor = vendor,
            reason = if (matchedRule != null) "matched-rule:${matchedRule.id.take(8)}"
                else if (smartRule != null) "smart-score-suspicious"
                else explainDomainDecisionReason(domain, appName, vendor)
        )
    }

    private fun explainDomainDecisionReason(domain: String, appName: String, vendor: String): String {
        val normalizedDomain = domain.trim().lowercase()
        if (normalizedDomain.isBlank()) return "empty-domain"
        if (RuleRepository.isWhitelistedDomain(normalizedDomain)) return "whitelist-domain"
        if (RuleRepository.isSensitiveAuthDomain(normalizedDomain)) return "sensitive-auth-domain"
        if (RuleRepository.shouldProtectMediaTraffic(normalizedDomain)) return "protected-media-domain"
        if (RuleRepository.shouldProtectBusinessTraffic(normalizedDomain)) return "protected-business-domain"
        if (RuleRepository.isGameCoreDomain(normalizedDomain)) return "protected-game-domain"
        if (RuleRepository.isSocialCoreDomain(normalizedDomain)) return "protected-social-domain"
        if (RuleRepository.isNovelContentDomain(normalizedDomain)) return "protected-novel-content-domain"
        if (RuleRepository.isProtectedNovelAppDomain(normalizedDomain)) return "protected-novel-app-domain"
        if (isGovernedPromoApp(appName)) return "governed-promo-app"
        if (!shizukuStrictAppAdBlockEnabled) return "pass"
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(this, normalizedDomain, appName, vendor)) return "shizuku-novel-aggressive"
        if (RuleRepository.shouldForceNovelQuicBlock(normalizedDomain, appName, vendor)) return "shizuku-novel-quic"
        if (RuleRepository.shouldForcePushRecommendInspection(normalizedDomain, appName, vendor)) return "shizuku-push-recommend"
        if (RuleRepository.looksLikeAdSdkInfraDomain(normalizedDomain, vendor) && isAggressiveAdAppContext(appName)) {
            return "shizuku-sdk-infra"
        }
        return "pass"
    }

    private fun shouldTreatAsGeneralAdTraffic(domain: String, vendor: String, appName: String?): Boolean {
        if (RuleRepository.isDomainExcepted(this, domain)) return false
        if (RuleRepository.shouldTreatAsGeneralAdTraffic(domain, vendor, appName)) return true
        if (!isGovernedPromoApp(appName)) return false
        val normalizedDomain = domain.trim().lowercase()
        if (normalizedDomain.isBlank()) return false
        if (RuleRepository.isWhitelistedDomain(normalizedDomain)) return false
        if (RuleRepository.isSensitiveAuthDomain(normalizedDomain)) return false
        if (isProtectedTrafficDomain(normalizedDomain)) return false
        return RuleRepository.looksLikeAdSdkInfraDomain(normalizedDomain, vendor) ||
            RuleRepository.shouldForcePushRecommendInspection(normalizedDomain, appName, vendor)
    }

    private fun isAggressiveAdAppContext(appName: String?): Boolean {
        return RuleRepository.isAggressiveAdAppHint(appName) || isGovernedPromoApp(appName)
    }

    private fun isGovernedPromoApp(appName: String?): Boolean {
        return PromoGovernSnapshotRepository.isGovernedAppHint(this, appName)
    }

    private fun isProtectedTrafficDomain(domain: String): Boolean {
        return RuleRepository.shouldProtectMediaTraffic(domain) ||
            RuleRepository.shouldProtectBusinessTraffic(domain) ||
            RuleRepository.isGameCoreDomain(domain) ||
            RuleRepository.isSocialCoreDomain(domain) ||
            RuleRepository.isNovelContentDomain(domain) ||
            RuleRepository.isProtectedNovelAppDomain(domain)
    }

    private fun isProtectedEncryptedDnsApp(appName: String?): Boolean {
        val text = appName?.lowercase().orEmpty()
        if (text.isBlank()) return false
        return listOf(
            "微信", "wechat", "weixin", "支付", "pay", "alipay", "支付宝", "银行", "bank",
            "银联", "unionpay", "tenpay", "安全", "系统", "system", "android"
        ).any(text::contains)
    }

    private data class DomainDecisionContext(
        val appName: String,
        val matchedRule: BlockRule?,
        val vendor: String,
        val reason: String
    )

    private data class NovelBlockingSignals(
        val aggressiveNovelBlock: Boolean,
        val forcedNovelQuicBlock: Boolean,
        val protectedNovelUrlBlock: Boolean
    )

    private data class DestinationAppContext(
        val destinationIp: String,
        val appName: String
    )

    private fun resolveAppNameByUid(info: com.HanFeng.model.PacketInfo, cacheKeys: AppResolveCacheKeys): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return resolveAppNameByProcNet(info, cacheKeys)
        if (hasRecentOwnerUidFailure(cacheKeys)) return null
        readCachedOwnerUid(cacheKeys)?.let { uid ->
            if (uid > 0) return buildAppLabel(uid)
            return null
        }
        refreshShizukuConnectionOwnerReadyIfNeeded()
        return runCatching {
            val protocol = if (info.protocol == OsConstants.IPPROTO_UDP) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
            val localHost = InetAddress.getByAddress(info.sourceAddress).hostAddress ?: return@runCatching null
            val remoteHost = InetAddress.getByAddress(info.destinationAddress).hostAddress ?: return@runCatching null
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(localHost, info.sourcePort)
            val remote = InetSocketAddress(remoteHost, info.destinationPort)
            var uid = connectivityManager.getConnectionOwnerUid(protocol, local, remote)
            if (uid <= 0 && shizukuConnectionOwnerReady) {
                uid = ShizukuConnectionOwnerRepository.getConnectionOwnerUidIfBound(
                    protocol = protocol,
                    localHost = localHost,
                    localPort = info.sourcePort,
                    remoteHost = remoteHost,
                    remotePort = info.destinationPort
                )
                if (uid <= 0) {
                    ShizukuConnectionOwnerRepository.invalidateService()
                    shizukuConnectionOwnerReady = false
                    lastShizukuConnectionOwnerRetryAt = System.currentTimeMillis()
                }
            }
            cacheOwnerUid(cacheKeys, uid)
            if (uid > 0) {
                clearOwnerUidFailure(cacheKeys)
            } else {
                cacheOwnerUidFailure(cacheKeys)
            }
            if (uid <= 0) return@runCatching null
            buildAppLabel(uid)
        }.getOrElse {
            cacheOwnerUidFailure(cacheKeys)
            shizukuConnectionOwnerReady = false
            lastShizukuConnectionOwnerRetryAt = System.currentTimeMillis()
            logDecisionOnce(
                key = "resolve-app-failed:${info.sourcePort}:${info.destinationPort}",
                message = "Resolve app failed: ${it.message ?: it.javaClass.simpleName}",
                minIntervalMillis = 60_000L
            )
            null
        } ?: resolveAppNameByProcNet(info, cacheKeys)
    }

    private fun resolveAppNameByProcNet(info: com.HanFeng.model.PacketInfo, cacheKeys: AppResolveCacheKeys): String? {
        if (!procNetOwnerFallbackEnabled) return null
        val result = ProcNetResolver.resolve(info) ?: return null
        if (result.uid <= 0) return null
        cacheOwnerUid(cacheKeys, result.uid)
        clearOwnerUidFailure(cacheKeys)
        val label = buildAppLabel(result.uid) ?: return null
        logDecisionOnce(
            key = "proc-net-owner:${info.protocol}:${info.sourcePort}:${info.destinationPort}",
            message = "Resolved app via proc net uid=${result.uid} app=$label state=${result.state} source=${result.source}",
            minIntervalMillis = 60_000L
        )
        return label
    }

    private fun buildAppLabel(uid: Int): String? {
        appLabelCache[uid]?.let { return it }
        val packages = packageManager.getPackagesForUid(uid)
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        val selectedPackage = selectBestPackageForUid(packages)
            ?: packageManager.getNameForUid(uid)
            ?: return null
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(selectedPackage, 0)).toString()
        }.getOrDefault(selectedPackage)
        val appLabel = if (label == selectedPackage) selectedPackage else "$label ($selectedPackage)"
        if (appLabelCache.size > 2048) appLabelCache.clear()
        appLabelCache[uid] = appLabel
        return appLabel
    }

    private fun selectBestPackageForUid(packages: List<String>): String? {
        if (packages.isEmpty()) return null
        if (packages.size == 1) return packages.first()
        return packages.maxWithOrNull(
            compareBy<String> { packagePriorityScore(this, it) }
                .thenByDescending { it.length }
        )
    }


    private fun readCachedOwnerUid(cacheKeys: AppResolveCacheKeys): Int? {
        synchronized(ownerUidCache) {
            return ownerUidCache[cacheKeys.flowKey] ?: ownerUidCache[cacheKeys.sourcePortKey]
        }
    }

    private fun hasRecentOwnerUidFailure(cacheKeys: AppResolveCacheKeys): Boolean {
        val now = System.currentTimeMillis()
        synchronized(ownerUidFailureCache) {
            val flowExpiresAt = ownerUidFailureCache[cacheKeys.flowKey]
            if (flowExpiresAt != null) {
                if (flowExpiresAt > now) return true
                ownerUidFailureCache.remove(cacheKeys.flowKey)
            }
            val sourceExpiresAt = ownerUidFailureCache[cacheKeys.sourcePortKey]
            if (sourceExpiresAt != null) {
                if (sourceExpiresAt > now) return true
                ownerUidFailureCache.remove(cacheKeys.sourcePortKey)
            }
            return false
        }
    }

    private fun readCachedAppName(cacheKeys: AppResolveCacheKeys): String? {
        synchronized(appNameCache) {
            return appNameCache[cacheKeys.flowKey]
        }
    }

    private fun readCachedPortAppName(cacheKeys: AppResolveCacheKeys): String? {
        synchronized(appNameCache) {
            return appNameCache[cacheKeys.portKey]
        }
    }

    private fun readCachedSourcePortApp(cacheKeys: AppResolveCacheKeys): String? {
        synchronized(sourcePortAppCache) {
            return sourcePortAppCache[cacheKeys.sourcePortKey]
        }
    }

    private fun readCachedDomainApp(domainInfo: NormalizedDomainInfo): String? {
        synchronized(domainAppCache) {
            return domainAppCache[domainInfo.normalized] ?: domainInfo.secondLevelDomain?.let(domainAppCache::get)
        }
    }

    private fun cacheAppName(cacheKeys: AppResolveCacheKeys, domainInfo: NormalizedDomainInfo, appName: String) {
        if (appNameCache.size > 4096) appNameCache.clear()
        if (sourcePortAppCache.size > 2048) sourcePortAppCache.clear()
        if (domainAppCache.size > 2048) domainAppCache.clear()
        appNameCache[cacheKeys.flowKey] = appName
        appNameCache[cacheKeys.portKey] = appName
        sourcePortAppCache[cacheKeys.sourcePortKey] = appName
        domainAppCache[domainInfo.normalized] = appName
        domainInfo.secondLevelDomain?.let { domainAppCache[it] = appName }
    }

    private fun cacheOwnerUid(cacheKeys: AppResolveCacheKeys, uid: Int) {
        if (ownerUidCache.size > 4096) ownerUidCache.clear()
        ownerUidCache[cacheKeys.flowKey] = uid
        ownerUidCache[cacheKeys.sourcePortKey] = uid
    }

    private fun cacheOwnerUidFailure(cacheKeys: AppResolveCacheKeys) {
        if (ownerUidFailureCache.size > 4096) ownerUidFailureCache.clear()
        val expiresAt = System.currentTimeMillis() + OWNER_UID_FAILURE_TTL_MILLIS
        ownerUidFailureCache[cacheKeys.flowKey] = expiresAt
        ownerUidFailureCache[cacheKeys.sourcePortKey] = expiresAt
    }

    private fun clearOwnerUidFailure(cacheKeys: AppResolveCacheKeys) {
        ownerUidFailureCache.remove(cacheKeys.flowKey)
        ownerUidFailureCache.remove(cacheKeys.sourcePortKey)
    }

    private fun buildCacheKeys(info: com.HanFeng.model.PacketInfo): AppResolveCacheKeys {
        val sourceAddress = formatAddress(info.sourceAddress)
        val destinationAddress = formatAddress(info.destinationAddress)
        return AppResolveCacheKeys(
            flowKey = listOf(
                info.protocol.toString(),
                sourceAddress,
                info.sourcePort.toString(),
                destinationAddress,
                info.destinationPort.toString()
            ).joinToString(":"),
            portKey = "${info.protocol}:$sourceAddress:${info.sourcePort}",
            sourcePortKey = "$sourceAddress:${info.sourcePort}"
        )
    }

    private fun normalizeDomain(domain: String): NormalizedDomainInfo {
        val normalized = domain.trim().lowercase()
        return NormalizedDomainInfo(
            normalized = normalized,
            secondLevelDomain = secondLevelDomain(normalized)
        )
    }

    private fun classifyVendorCached(domain: String, appName: String?): String {
        val domainInfo = normalizeDomain(domain)
        val normalizedApp = appName?.trim().orEmpty()
        val cacheKey = "${domainInfo.normalized}|$normalizedApp"
        vendorHintCache[cacheKey]?.let { return it }
        val resolved = RuleRepository.classifyVendorFromHints(this, domain, appName)
        if (vendorHintCache.size > 4096) vendorHintCache.clear()
        vendorHintCache[cacheKey] = resolved
        return resolved
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
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_AUTO_REMOVE_FROM_RECENTS, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleAction = if (isRunning) ACTION_STOP else ACTION_START
        val toggleLabel = if (isRunning) "关闭拦截" else "打开拦截"
        val toggleIntent = Intent(this, AdBlockVpnService::class.java).apply {
            action = toggleAction
            putExtra(EXTRA_USER_INITIATED, true)
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reportAdPendingIntent = PendingIntent.getService(
            this,
            104,
            Intent(this, AdBlockVpnService::class.java).apply { action = ACTION_REPORT_AD },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val customView = RemoteViews(packageName, R.layout.notification_vpn_status).apply {
            setTextViewText(R.id.tvNotificationStatus, notificationStatusText())
            setTextViewText(R.id.tvNotificationMode, notificationModeText())
            setTextViewText(R.id.btnNotificationToggle, toggleLabel)
            setOnClickPendingIntent(R.id.notificationRoot, contentPendingIntent)
            setOnClickPendingIntent(R.id.btnNotificationToggle, togglePendingIntent)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(notificationStatusText())
            .setContentText(notificationModeText())
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .addAction(android.R.drawable.ic_menu_report_image, "此页面有广告", reportAdPendingIntent)
            .build()
    }

    private fun showPendingForegroundNotification() {
        runCatching {
            createChannel()
            startForeground(NOTIFICATION_ID, buildPendingNotification())
            foregroundShown = true
        }.onFailure { error ->
            LogRepository.append(this, "Show pending foreground notification failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun buildPendingNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_AUTO_REMOVE_FROM_RECENTS, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            102,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val customView = RemoteViews(packageName, R.layout.notification_vpn_status).apply {
            setTextViewText(R.id.tvNotificationStatus, "运行状态：启动中")
            setTextViewText(R.id.tvNotificationMode, "当前模式：正在建立拦截服务")
            setTextViewText(R.id.btnNotificationToggle, "关闭拦截")
            setOnClickPendingIntent(R.id.notificationRoot, contentPendingIntent)
            setOnClickPendingIntent(
                R.id.btnNotificationToggle,
                PendingIntent.getService(
                    this@AdBlockVpnService,
                    103,
                    Intent(this@AdBlockVpnService, AdBlockVpnService::class.java).apply { action = ACTION_STOP },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("运行状态：启动中")
            .setContentText("当前模式：正在建立拦截服务")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .build()
    }

    private fun notificationStatusText(): String {
        return when {
            isWaitingForReacquire() -> "运行状态：等待恢复"
            isRunning -> "运行状态：已开启"
            else -> "运行状态：已关闭"
        }
    }

    private fun notificationModeText(): String {
        val localProxyText = localProxyNotificationText()
        return when {
            isWaitingForReacquire() -> appendModeSuffix("当前模式：VPN 被系统替换，正在自动恢复", localProxyText)
            !isRunning -> appendModeSuffix("当前模式：已关闭", localProxyText)
            httpDecryptEnabled && mitmCertificateInstalled -> appendModeSuffix("当前模式：MITM + DNS 拦截", localProxyText)
            httpDecryptEnabled -> appendModeSuffix("当前模式：DNS/IP 拦截，MITM 等待证书安装", localProxyText)
            else -> appendModeSuffix("当前模式：DNS 拦截", localProxyText)
        }
    }

    private fun localProxyNotificationText(): String? {
        val config = localProxyCoexistConfig
        val port = config.port ?: return null
        if (!config.enabled) return null
        val status = when (localProxyReachable) {
            true -> "可用"
            false -> "未连通"
            null -> "检测中"
        }
        return "本地代理 ${config.host}:$port $status"
    }

    private fun appendModeSuffix(base: String, vararg suffixes: String?): String {
        val activeSuffixes = suffixes.filterNot { it.isNullOrBlank() }
        if (activeSuffixes.isEmpty()) return base
        return "$base | ${activeSuffixes.joinToString(" | ")}"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "广告拦截服务", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun refreshForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val now = System.currentTimeMillis()
        if (foregroundShown && now - lastForegroundNotificationRefreshAt < FOREGROUND_NOTIFICATION_REFRESH_MIN_INTERVAL_MILLIS) {
            return
        }
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification())
            lastForegroundNotificationRefreshAt = now
        }.onFailure {
            LogRepository.append(this, "Refresh foreground notification failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun refreshRuntimeFeatureFlags(fastStart: Boolean = false) {
        val flags = if (fastStart) {
            NetworkRuntimeSettingsStore.loadFast(this)
        } else {
            NetworkRuntimeSettingsStore.load(this)
        }
        httpDecryptEnabled = flags.httpDecryptEnabled
        forceEncryptedDnsFallbackEnabled = FeatureSettingsRepository.isForceEncryptedDnsFallbackEnabled(this)
        mitmLearningModeEnabled = FeatureSettingsRepository.isMitmLearningModeEnabled(this)
        procNetOwnerFallbackEnabled = FeatureSettingsRepository.isProcNetOwnerFallbackEnabled(this)
        mitmCertificateInstalled = flags.mitmCertificateInstalled
        shizukuConnectionOwnerReady = flags.shizukuConnectionOwnerReady
        shizukuAdControlReady = flags.shizukuAdControlReady
        if (shizukuConnectionOwnerReady) {
            lastShizukuConnectionOwnerRetryAt = 0L
        }
        if (shizukuAdControlReady) {
            lastShizukuAdControlRetryAt = 0L
        }
        shizukuStrictAppAdBlockEnabled = flags.shizukuStrictAppAdBlockEnabled
        localProxyCoexistConfig = flags.localProxyConfig
        localProxyTargetPackages = flags.localProxyTargetPackages
        lightweightPassThroughMode = flags.lightweightPassThroughMode
        applyRuntimeSettingsFlags()
        
        // SSL Pinning 绕过
        sslPinningBypasser = SslPinningBypasser(this)
    }

    private fun refreshShizukuConnectionOwnerReadyIfNeeded() {
        if (shizukuConnectionOwnerReady) return
        val now = System.currentTimeMillis()
        if (now - lastShizukuConnectionOwnerRetryAt < SHIZUKU_CONNECTION_OWNER_RETRY_INTERVAL_MILLIS) return
        lastShizukuConnectionOwnerRetryAt = now
        if (!ShizukuConnectionOwnerRepository.isReady(this)) return
        runCatching {
            ShizukuConnectionOwnerRepository.ensureBound(this)
            ShizukuConnectionOwnerRepository.isServiceAlive()
        }.onSuccess { alive ->
            shizukuConnectionOwnerReady = alive
        }
    }

    private fun probeLocalProxyCoexistAsync() {
        pendingLocalProxyProbeJob?.cancel()
        pendingLocalProxyProbeJob = null
        val config = localProxyCoexistConfig
        val port = config.port
        if (!config.enabled || port == null || port !in 1..65535) {
            localProxyReachable = null
            refreshForegroundNotification()
            return
        }
        localProxyReachable = null
        refreshForegroundNotification()
        scope.launch {
            val reachable = runCatching {
                Socket().use { socket ->
                    socket.soTimeout = LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS
                    if (!protect(socket)) {
                        LogRepository.append(this@AdBlockVpnService, "Protect local proxy coexist socket failed host=${config.host} port=$port")
                        throw java.io.IOException("Protect local proxy coexist socket failed")
                    }
                    socket.connect(InetSocketAddress(config.host, port), LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS)
                    true
                }
            }.getOrElse {
                LogRepository.append(this@AdBlockVpnService, "Local proxy coexist probe failed host=${config.host} port=$port error=${it.message ?: it.javaClass.simpleName}")
                false
            }
            localProxyReachable = reachable
            if (reachable) {
                LogRepository.append(this@AdBlockVpnService, "Local proxy coexist reachable host=${config.host} port=$port package=${config.detectedPackageName ?: "manual"}")
            } else {
                scheduleLocalProxyProbeRetry(config.host, port)
            }
            refreshForegroundNotification()
        }
    }

    private fun scheduleLocalProxyProbeRetry(host: String, port: Int) {
        if (!isRunning && !isWaitingForReacquire()) return
        if (pendingLocalProxyProbeJob?.isActive == true) return
        pendingLocalProxyProbeJob = scope.launch {
            repeat(5) { attempt ->
                delay((attempt + 1) * 2_000L)
                val currentConfig = localProxyCoexistConfig
                if (!currentConfig.enabled || currentConfig.host != host || currentConfig.port != port) {
                    pendingLocalProxyProbeJob = null
                    return@launch
                }
                val reachable = runCatching {
                    Socket().use { socket ->
                        socket.soTimeout = LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS
                        if (!protect(socket)) {
                            LogRepository.append(this@AdBlockVpnService, "Protect local proxy retry probe socket failed host=$host port=$port")
                            throw java.io.IOException("Protect local proxy retry probe socket failed")
                        }
                        socket.connect(InetSocketAddress(host, port), LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS)
                        true
                    }
                }.getOrElse {
                    false
                }
                localProxyReachable = reachable
                refreshForegroundNotification()
                if (reachable) {
                    LogRepository.append(this@AdBlockVpnService, "Local proxy coexist reachable after retry host=$host port=$port attempt=${attempt + 1}")
                    pendingLocalProxyProbeJob = null
                    return@launch
                }
            }
            pendingLocalProxyProbeJob = null
        }
    }

    private fun shouldCaptureFullTrafficForLocalProxy(): Boolean {
        return false
    }

    private fun resolveFullCaptureRouteMode(stableMode: Boolean): FullCaptureRoutingSupport.Mode {
        val localProxyFullCapture = shouldCaptureFullTrafficForLocalProxy()
        val mitmCircuitOpen = isMitmFullCaptureCircuitOpen()
        val mitmAppTargets = if (ENABLE_MITM_APP_FULL_CAPTURE && !stableMode && !localProxyFullCapture && httpDecryptEnabled && mitmCertificateInstalled && !mitmCircuitOpen) {
            resolveMitmAppFullCaptureTargets(this, packageName, shizukuAdControlReady)
        } else {
            emptySet()
        }
        mitmAppFullCaptureTargetPackages = mitmAppTargets
        val mode = FullCaptureRoutingSupport.resolve(
            FullCaptureRoutingSupport.Input(
                stableMode = stableMode,
                localProxyFullCapture = localProxyFullCapture,
                httpDecryptEnabled = httpDecryptEnabled,
                mitmCertificateInstalled = mitmCertificateInstalled,
                mitmAppFullCaptureEnabled = mitmAppTargets.isNotEmpty(),
                mitmFullCaptureEnabled = ENABLE_GLOBAL_HTTPS_MITM_CAPTURE,
                mitmCircuitOpen = mitmCircuitOpen
            )
        )
        when {
            mode == FullCaptureRoutingSupport.Mode.MITM_APP -> {
                logDecisionOnce(
                    key = "mitm-full-capture-app-enabled:${mitmAppTargets.size}",
                    message = "Enabled MITM app full-capture routes targetCount=${mitmAppTargets.size} targets=${mitmAppTargets.take(8).joinToString(",")}",
                    minIntervalMillis = 60_000L
                )
            }
            mode == FullCaptureRoutingSupport.Mode.MITM_GLOBAL -> {
                logDecisionOnce(
                    key = "mitm-full-capture-global-enabled",
                    message = "Enabled global MITM full-capture routes for all non-excluded apps",
                    minIntervalMillis = 60_000L
                )
            }
            mode == FullCaptureRoutingSupport.Mode.NONE && stableMode -> Unit
            mode == FullCaptureRoutingSupport.Mode.NONE && httpDecryptEnabled && mitmCertificateInstalled && mitmCircuitOpen -> {
                val remainingMillis = (mitmFullCaptureDisabledUntil - System.currentTimeMillis()).coerceAtLeast(0L)
                logDecisionOnce(
                    key = "mitm-full-capture-circuit-open",
                    message = "Skipped MITM full-capture routes because passthrough circuit is open remaining=${remainingMillis}ms",
                    minIntervalMillis = 15_000L
                )
            }
        }
        return mode
    }

    companion object {
        const val ACTION_START = "com.HanFeng.START"
        const val ACTION_STOP = "com.HanFeng.STOP"
        const val ACTION_RELOAD = "com.HanFeng.RELOAD"
        const val ACTION_REPORT_AD = "com.HanFeng.REPORT_AD"
        const val ACTION_STATUS_CHANGED = "com.HanFeng.STATUS_CHANGED"
        const val EXTRA_USER_INITIATED = "extra_user_initiated"
        private const val CHANNEL_ID = "adblock_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val GENERIC_AD_VENDOR_LABEL = "通用广告/追踪 (Generic Ad/Tracking)"
        private const val PENDING_GLOBAL_MITM_DOMAIN = "pending.global-mitm.local"
        private const val REACQUIRE_ATTEMPT_COUNT = 48
        private const val LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS = 1200
        private const val TCP_FLAG_FIN = 0x01
        private const val TCP_FLAG_SYN = 0x02
        private const val TCP_FLAG_RST = 0x04
        private const val TCP_FLAG_PSH = 0x08
        private const val TCP_FLAG_ACK = 0x10
        private const val TCP_FLAG_URG = 0x20
        private const val DEFAULT_TCP_WINDOW_SIZE = 65535
        private const val TCP_SEGMENT_PAYLOAD_SIZE = 1400
        private const val HTTP_DECRYPT_ROUTE_RELOAD_MIN_INTERVAL_MILLIS = 75_000L
        private const val HTTP_DECRYPT_ROUTE_FORCE_MIN_INTERVAL_MILLIS = 45_000L
        private const val HTTP_DECRYPT_ROUTE_BATCH_WINDOW_MILLIS = 15_000L
        private const val UNDERLYING_NETWORK_REFRESH_MIN_INTERVAL_MILLIS = 1_500L
        private const val FOREGROUND_NOTIFICATION_REFRESH_MIN_INTERVAL_MILLIS = 3_000L
        private const val OWNER_UID_FAILURE_TTL_MILLIS = 60_000L
        private const val SHIZUKU_CONNECTION_OWNER_RETRY_INTERVAL_MILLIS = 45_000L
        private const val HTTPS_BRIDGE_CONNECT_TIMEOUT_MILLIS = 4_000
        private const val PASSTHROUGH_TCP_CONNECT_TIMEOUT_MILLIS = 5_000
        private const val PASSTHROUGH_UDP_READ_TIMEOUT_MILLIS = 1_000
        private const val PASSTHROUGH_UDP_IDLE_TIMEOUT_MILLIS = 30_000L
        private const val PASSTHROUGH_HEALTH_WINDOW_MILLIS = 30_000L
        private const val PASSTHROUGH_HEALTH_MIN_ATTEMPTS = 10
        private const val PASSTHROUGH_HEALTH_ABSOLUTE_FAILURES = 8
        private const val PASSTHROUGH_HEALTH_FAILURE_PERCENT = 45
        private const val MITM_FULL_CAPTURE_CIRCUIT_COOLDOWN_MILLIS = 180_000L
        private const val TUN_DEBUG_WINDOW_MILLIS = 1_000L
        private const val TUN_DEBUG_HIGH_RATE_PACKET_THRESHOLD = 1_000
        private const val TUN_STORM_PACKET_THRESHOLD_PER_SECOND = 5_000
        private const val TUN_STORM_COOLDOWN_MILLIS = 10_000L
        private const val TUN_STORM_BACKOFF_MILLIS = 50L
        private const val TUN_STORM_RELOAD_DELAY_MILLIS = 500L
        private const val ENABLE_ACTIVE_MITM_ROUTING = true
        private const val ENABLE_MITM_APP_FULL_CAPTURE = false
        private const val ENABLE_GLOBAL_HTTPS_MITM_CAPTURE = false
        private const val MAX_BUFFERED_CLIENT_SEGMENTS = 32
        private const val MAX_BUFFERED_SERVER_SEGMENTS = 32
        private const val MAX_BUFFERED_CLIENT_BYTES = 256 * 1024
        private const val MAX_BUFFERED_SERVER_BYTES = 256 * 1024

        @Volatile
        var isRunning: Boolean = false
    }

}
