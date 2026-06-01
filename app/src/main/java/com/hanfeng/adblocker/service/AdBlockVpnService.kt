package com.HanFeng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.HanFeng.R
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpsDecryptRouteRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.HttpDecryptRouteRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuAdControlCatalog
import com.HanFeng.data.ShizukuConnectionOwnerRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.data.StatsRepository
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.dns.DnsMessageParser
import com.HanFeng.model.BlockRule
import com.HanFeng.model.LocalProxyCoexistConfig
import com.HanFeng.security.CertificateAuthorityManager
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
import java.io.ByteArrayOutputStream
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
    private var pendingRouteReloadJob: Job? = null
    private var pendingVpnReloadJob: Job? = null
    private var pendingReacquireJob: Job? = null
    private var pendingLocalProxyProbeJob: Job? = null
    @Volatile private var startInProgress = false
    @Volatile private var foregroundShown = false
    @Volatile private var activeTunGeneration = 0L
    private val appNameCache = ConcurrentHashMap<String, String>(256)
    private val domainAppCache = ConcurrentHashMap<String, String>(256)
    private val sourcePortAppCache = ConcurrentHashMap<String, String>(256)
    private val ownerUidCache = ConcurrentHashMap<String, Int>(512)
    private val ownerUidFailureCache = ConcurrentHashMap<String, Long>(512)
    private val appLabelCache = ConcurrentHashMap<Int, String>(128)
    private val vendorHintCache = ConcurrentHashMap<String, String>(512)
    private val dnsResponseCache = LinkedHashMap<String, CachedDnsResponse>(256, 0.75f, true)
    private val decisionLogCache = LinkedHashMap<String, Long>(256, 0.75f, true)
    private val adIpTargetCache = LinkedHashMap<String, AdIpTarget>(1024, 0.75f, true)
    private val httpDecryptIpCache = LinkedHashMap<String, HttpDecryptTarget>(512, 0.75f, true)
    private val httpsDecryptIpCache = LinkedHashMap<String, HttpsDecryptTarget>(512, 0.75f, true)
    private val quicRouteCache = LinkedHashMap<String, QuicRouteTarget>(1024, 0.75f, true)
    private val httpsProxyFlowCache = LinkedHashMap<String, HttpsProxyFlow>(256, 0.75f, true)
    private val httpsBridgeSocketCache = LinkedHashMap<String, HttpsBridgeSocketSession>(128, 0.75f, true)
    private val localProxyTcpFlowCache = LinkedHashMap<String, LocalProxyTcpFlow>(256, 0.75f, true)
    private val localProxyBridgeSocketCache = LinkedHashMap<String, LocalProxyBridgeSocketSession>(128, 0.75f, true)
    private val localProxyTargetAppCache = ConcurrentHashMap<String, Boolean>(512)
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
    private val routeCachePruneIntervalMillis = 60_000L
    private var lastHttpRouteReloadAt = 0L
    @Volatile private var lastHttpDecryptPruneAt = 0L
    @Volatile private var lastHttpsDecryptPruneAt = 0L
    @Volatile private var lastQuicRoutePruneAt = 0L
    @Volatile private var lastRouteCachePruneCheckAt = 0L
    @Volatile private var lastUnderlyingNetworkRefreshAt = 0L
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
    private val forcedDnsRouteHosts by lazy(LazyThreadSafetyMode.NONE) {
        upstreamFallbackDnsHosts.toSet()
    }
    @Volatile private var cachedDnsServers: CachedDnsServers? = null
    @Volatile private var httpDecryptEnabled = false
    @Volatile private var mitmCertificateInstalled = false
    @Volatile private var shizukuConnectionOwnerReady = false
    @Volatile private var shizukuAdControlReady = false
    @Volatile private var localProxyCoexistConfig = LocalProxyCoexistConfig()
    @Volatile private var localProxyTargetPackages: Set<String> = emptySet()
    @Volatile private var localProxyReachable: Boolean? = null
    @Volatile private var lightweightPassThroughMode = false
    @Volatile private var networkCallbackRegistered = false
    @Volatile private var lastForegroundNotificationRefreshAt = 0L
    private var connectivityNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val handledDnsHosts by lazy(LazyThreadSafetyMode.NONE) {
        setOf(localDnsV4, localDnsV6).mapNotNull { host ->
            runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        }.toSet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val userInitiated = intent?.getBooleanExtra(EXTRA_USER_INITIATED, false) == true
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
            ACTION_STOP -> {
                FeatureSettingsRepository.setAdBlockEnabled(this, false)
                FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
                pendingReacquireJob?.cancel()
                pendingReacquireJob = null
                stopVpn()
                false
            }
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
        refreshRuntimeFeatureFlags()
        if (vpnInterface != null && packetJob?.isActive == true) {
            isRunning = true
            notifyRuntimeStatusChanged()
            startInProgress = false
            LogRepository.append(this, "VPN start called while already running")
            return
        }
        if (!foregroundShown) {
            val foregroundStarted = runCatching {
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
            if (!foregroundStarted) {
                return
            }
        }
        vpnInterface = runCatching { buildInterface() }
            .onFailure { error ->
                LogRepository.append(this, "VPN establish failed: ${error.message ?: error.javaClass.simpleName}")
            }
            .getOrNull()
        if (vpnInterface == null) {
            isRunning = false
            notifyRuntimeStatusChanged()
            startInProgress = false
            if (preserveUserIntentOnFailure) {
                FeatureSettingsRepository.setAdBlockEnabled(this, true)
                FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, true)
                scheduleVpnReacquireAfterRevoke()
                refreshForegroundNotification()
                LogRepository.append(this, "VPN establish deferred: keep waiting for external VPN release")
                return
            }
            FeatureSettingsRepository.setAdBlockEnabled(this, false)
            FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
            pendingReacquireJob?.cancel()
            pendingReacquireJob = null
            clearRuntimeState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundShown = false
            stopSelf()
            return
        }
        isRunning = vpnInterface != null
        notifyRuntimeStatusChanged()
        startInProgress = false
        FeatureSettingsRepository.setAdBlockEnabled(this, true)
        FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
        pendingReacquireJob?.cancel()
        pendingReacquireJob = null
        registerNetworkMonitoringIfNeeded()
        invalidateDnsServerCache()
        refreshForegroundNotification()
        applyUnderlyingNetworks()
        probeLocalProxyCoexistAsync()
        activeTunGeneration += 1L
        val tunGeneration = activeTunGeneration
        if (httpDecryptEnabled && mitmCertificateInstalled) {
            HttpsMitmRepository.clearRuntimeState(this)
        }
        packetJob = scope.launch {
            runCatching { runPacketLoop(tunGeneration) }
                .onFailure { error ->
                    if (tunGeneration != activeTunGeneration || !isRunning) {
                        LogRepository.append(
                            this@AdBlockVpnService,
                            "Ignore stale VPN loop shutdown generation=$tunGeneration active=$activeTunGeneration reason=${error.message ?: error.javaClass.simpleName}"
                        )
                        return@onFailure
                    }
                    LogRepository.append(this@AdBlockVpnService, "VPN loop crashed: ${error.message ?: error.javaClass.simpleName}")
                    FeatureSettingsRepository.setAdBlockEnabled(this@AdBlockVpnService, false)
                    FeatureSettingsRepository.setVpnRevokedByOtherVpn(this@AdBlockVpnService, false)
                    pendingReacquireJob?.cancel()
                    pendingReacquireJob = null
                    stopVpn(stopService = false)
                }
        }
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
        scope.launch {
            if (shizukuConnectionOwnerReady) {
                runCatching { ShizukuConnectionOwnerRepository.ensureBound(this@AdBlockVpnService) }
                    .onSuccess { bound ->
                        if (!bound) {
                            shizukuConnectionOwnerReady = false
                        }
                        LogRepository.append(
                            this@AdBlockVpnService,
                            if (bound) "Shizuku connection owner service warmed after VPN start" else "Shizuku connection owner service warm deferred after VPN start"
                        )
                    }
                    .onFailure {
                        shizukuConnectionOwnerReady = false
                        LogRepository.append(
                            this@AdBlockVpnService,
                            "Shizuku connection owner warm failed after VPN start: ${it.message ?: it.javaClass.simpleName}"
                        )
                    }
            }
        }
        scope.launch {
            if (shizukuAdControlReady) {
                runCatching { ShizukuAdControlRepository.ensureBound(this@AdBlockVpnService) }
                    .onSuccess { bound ->
                        if (!bound) {
                            shizukuAdControlReady = false
                        }
                        LogRepository.append(
                            this@AdBlockVpnService,
                            if (bound) "Shizuku ad control service warmed after VPN start" else "Shizuku ad control service warm deferred after VPN start"
                        )
                    }
                    .onFailure {
                        shizukuAdControlReady = false
                        LogRepository.append(
                            this@AdBlockVpnService,
                            "Shizuku ad control warm failed after VPN start: ${it.message ?: it.javaClass.simpleName}"
                        )
                    }
            }
        }
    }

    private fun reloadVpn() {
        pendingVpnReloadJob?.cancel()
        pendingVpnReloadJob = null
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
        registerNetworkMonitoringIfNeeded()
        invalidateDnsServerCache()
        applyUnderlyingNetworks()
        packetJob = scope.launch {
            runCatching { runPacketLoop(tunGeneration) }
                .onFailure { error ->
                    if (tunGeneration != activeTunGeneration || !isRunning) {
                        LogRepository.append(
                            this@AdBlockVpnService,
                            "Ignore stale VPN loop shutdown after reload generation=$tunGeneration active=$activeTunGeneration reason=${error.message ?: error.javaClass.simpleName}"
                        )
                        return@onFailure
                    }
                    LogRepository.append(this@AdBlockVpnService, "VPN loop crashed after seamless reload: ${error.message ?: error.javaClass.simpleName}")
                    stopVpn(stopService = false)
                }
        }
        previousPacketJob?.cancel()
        runCatching { previousTunOutput?.close() }
        runCatching { previousInterface.close() }
        refreshForegroundNotification()
        HttpsMitmController.onVpnStarted(this)
        probeLocalProxyCoexistAsync()
        LogRepository.append(this, "VPN seamlessly reloaded")
        return true
    }

    private fun stopVpn(stopService: Boolean = true, keepForeground: Boolean = false) {
        isRunning = false
        notifyRuntimeStatusChanged()
        startInProgress = false
        pendingVpnReloadJob?.cancel()
        pendingVpnReloadJob = null
        pendingReacquireJob?.cancel()
        pendingReacquireJob = null
        pendingRouteReloadJob?.cancel()
        pendingRouteReloadJob = null
        pendingLocalProxyProbeJob?.cancel()
        pendingLocalProxyProbeJob = null
        packetJob?.cancel()
        packetJob = null
        activeTunGeneration += 1L
        unregisterNetworkMonitoring()
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
                startInProgress = false
                isRunning = false
                notifyRuntimeStatusChanged()
                LogRepository.append(this@AdBlockVpnService, "VPN async start failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun notifyRuntimeStatusChanged() {
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED).setPackage(packageName)
        )
    }

    private fun scheduleVpnReacquireAfterRevoke() {
        pendingReacquireJob?.cancel()
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
                    pendingReacquireJob = null
                    return@launch
                }
                if (!FeatureSettingsRepository.isVpnRevokedByOtherVpn(this@AdBlockVpnService)) {
                    pendingReacquireJob = null
                    return@launch
                }
                val vpnPermissionReady = runCatching { VpnService.prepare(this@AdBlockVpnService) }.getOrNull() == null
                if (!vpnPermissionReady) return@repeat
                LogRepository.append(this@AdBlockVpnService, "Attempting VPN reacquire after external VPN release #${attempt + 1}")
                startVpn(userInitiated = false, preserveUserIntentOnFailure = true)
                if (isRunning) {
                    pendingRouteReloadJob?.cancel()
                    pendingRouteReloadJob = scope.launch {
                        delay(600)
                        if (isRunning) {
                            refreshRuntimeFeatureFlags()
                            applyUnderlyingNetworks()
                            probeLocalProxyCoexistAsync()
                            refreshForegroundNotification()
                            LogRepository.append(this@AdBlockVpnService, "VPN coexist recovery refresh completed after external VPN release")
                        }
                    }
                    pendingReacquireJob = null
                    return@launch
                }
            }
            LogRepository.append(this@AdBlockVpnService, "VPN reacquire window expired after external VPN revoke")
            pendingReacquireJob = null
            refreshForegroundNotification()
        }
    }

    private fun isWaitingForReacquire(): Boolean {
        return !isRunning &&
            FeatureSettingsRepository.isAdBlockEnabled(this) &&
            FeatureSettingsRepository.isVpnRevokedByOtherVpn(this)
    }

    private fun clearRuntimeState() {
        httpDecryptEnabled = false
        mitmCertificateInstalled = false
        shizukuConnectionOwnerReady = false
        shizukuAdControlReady = false
        lightweightPassThroughMode = false
        localProxyTargetPackages = emptySet()
        localProxyReachable = null
        pendingLocalProxyProbeJob?.cancel()
        pendingLocalProxyProbeJob = null
        TlsMitmSessionManager.clear(this)
        appNameCache.clear()
        domainAppCache.clear()
        sourcePortAppCache.clear()
        ownerUidCache.clear()
        ownerUidFailureCache.clear()
        localProxyTargetAppCache.clear()
        appLabelCache.clear()
        vendorHintCache.clear()
        dnsResponseCache.clear()
        decisionLogCache.clear()
        adIpTargetCache.clear()
        httpDecryptIpCache.clear()
        httpsDecryptIpCache.clear()
        quicRouteCache.clear()
        httpsProxyFlowCache.clear()
        httpsBridgeSocketCache.values.forEach { it.close() }
        httpsBridgeSocketCache.clear()
        localProxyTcpFlowCache.clear()
        localProxyBridgeSocketCache.values.forEach { it.close() }
        localProxyBridgeSocketCache.clear()
        upstreamServerStates.clear()
        cachedDnsServers = null
        dnsSocketPool.forEach { (_, socket) -> socket.close() }
        dnsSocketPool.clear()
        lastUnderlyingNetworkRefreshAt = 0L
        lastForegroundNotificationRefreshAt = 0L
    }

    private fun buildInterface(): ParcelFileDescriptor? {
        return runCatching { buildInterfaceInternal(stableMode = false) }
            .onFailure {
                LogRepository.append(this, "VPN full interface failed, fallback to stable mode: ${it.message ?: it.javaClass.simpleName}")
            }
            .getOrElse {
                buildInterfaceInternal(stableMode = true)
            }
    }

    private fun buildInterfaceInternal(stableMode: Boolean): ParcelFileDescriptor? {
        val localProxyFullCapture = shouldCaptureFullTrafficForLocalProxy()
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

        if (localProxyFullCapture) {
            runCatching {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }.onFailure {
                LogRepository.append(this, "Enable local proxy full-capture routes failed: ${it.message ?: it.javaClass.simpleName}")
            }
        } else if (!stableMode) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val disallowedPackages = WhitelistRepository.getDisallowedPackages(this)
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

        if (localProxyFullCapture) {
            LogRepository.append(this, "VPN established with local proxy full-capture routes")
        } else if (stableMode) {
            LogRepository.append(this, "VPN established with stable fallback mode")
        } else {
            LogRepository.append(this, "VPN established with targeted routes only")
        }
        return builder.establish()
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
                handleUnderlyingNetworkChanged()
            }

            override fun onLost(network: Network) {
                handleUnderlyingNetworkChanged()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handleUnderlyingNetworkChanged()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                handleUnderlyingNetworkChanged()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

    private fun runPacketLoop(tunGeneration: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val descriptor = vpnInterface ?: return
        FileInputStream(descriptor.fileDescriptor).use { input ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                if (tunGeneration == activeTunGeneration) {
                    tunOutputStream = output
                }
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
                if (tunGeneration == activeTunGeneration) {
                    tunOutputStream = null
                }
            }
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        val info = PacketCodec.parse(packet, length) ?: return
        val isUdp = info.protocol == OsConstants.IPPROTO_UDP
        val isTcp = info.protocol == OsConstants.IPPROTO_TCP
        if (lightweightPassThroughMode && !(isUdp && info.destinationPort == 53)) {
            return
        }
        findBlockedIpNetwork(info.destinationAddress)?.let { network ->
            return
        }
        findMatchingIpRule(info)?.let { match ->
            StatsRepository.recordBlockedHttp(this, match.rule.vendor, match.appName, 32 * 1024)
            logDecisionOnce(
                key = "ip-cidr-block:${match.rule.id}:${formatAddress(info.destinationAddress)}:${info.destinationPort}",
                message = "Blocked IP-CIDR flow ip=${formatAddress(info.destinationAddress)} port=${info.destinationPort} app=${match.appName} vendor=${match.rule.vendor} cidr=${match.rule.ipCidr ?: "unknown"}",
                minIntervalMillis = 30_000L
            )
            return
        }
        findMatchingPortOnlyRule(info)?.let { match ->
            StatsRepository.recordBlockedHttp(this, match.rule.vendor, match.appName, 32 * 1024)
            logDecisionOnce(
                key = "port-only-block:${match.rule.id}:${formatAddress(info.destinationAddress)}:${info.destinationPort}:${info.sourcePort}",
                message = "Blocked port-only flow ip=${formatAddress(info.destinationAddress)} port=${info.destinationPort} sourcePort=${info.sourcePort} app=${match.appName} vendor=${match.rule.vendor}",
                minIntervalMillis = 30_000L
            )
            return
        }
        findAdIpTarget(info)?.let { target ->
            StatsRepository.recordBlockedHttp(this, target.vendor, target.appName, 32 * 1024)
            logDecisionOnce(
                key = "ad-ip-block:${target.domain}:${formatAddress(info.destinationAddress)}:${info.destinationPort}",
                message = "Blocked ad IP flow ip=${formatAddress(info.destinationAddress)} port=${info.destinationPort} domain=${target.domain} app=${target.appName} vendor=${target.vendor} source=${target.source}",
                minIntervalMillis = 30_000L
            )
            return
        }
        if (isUdp && info.destinationPort == 53) {
            if (!shouldHandleDns(info.destinationAddress)) {
                logDecisionOnce(
                    key = "dns-non-local-endpoint:${formatAddress(info.destinationAddress)}",
                    message = "Observed DNS query to non-local endpoint ip=${formatAddress(info.destinationAddress)}, fallback to local DNS handler",
                    minIntervalMillis = 30_000L
                )
            }

            val question = DnsMessageParser.parseQuestion(info.payload) ?: return

            if (lightweightPassThroughMode) {
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
                return
            }

            // App 启动核心域名快速放行（避免冷加载开销，只针对少数关键域名）
            if (RuleRepository.criticalStartupDomains.contains(question.domain)) {
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
                return
            }

            // DNS 拦截决策优化：复用计算结果，避免重复调用
            val domainContext = resolveDomainDecisionContext(
                domain = question.domain,
                info = info,
                qType = question.qType
            )
            val appName = domainContext.appName
            val matchedRule = domainContext.matchedRule
            val isBlocked = matchedRule != null
            val vendor = domainContext.vendor
            if (isBlocked) {
                output.write(PacketCodec.buildUdpResponse(info, DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return))
                StatsRepository.recordBlockedDns(this, vendor, appName, 512)
                return
            }

            StatsRepository.recordRequest(this, vendor, appName)
            RuleRepository.reportUnknownVendorIfNeeded(
                context = this,
                vendor = vendor,
                domain = question.domain,
                appName = appName,
                signal = RuleRepository.SuspiciousSignal.DNS_QUERY
            )

            readCachedDnsResponse(question, info.payload)?.let { cachedResponse ->
                output.write(PacketCodec.buildUdpResponse(info, cachedResponse))
                return
            }

            val upstreamResult = queryUpstreamDns(info.payload)
            val upstreamResponse = upstreamResult?.response
                ?: readStaleCachedDnsResponse(question, info.payload)
                ?: DnsMessageParser.buildServerFailureResponse(info.payload, question)

            // CNAME 别名检查优化：复用 vendor 缓存
            // CNAME 别名链式检测（防止多层 CNAME 绕过）
            val aliasTargets = DnsMessageParser.extractAliasTargets(upstreamResponse, question)
            var blockedAliasTarget: String? = null
            if (aliasTargets.isNotEmpty()) {
                // 检查所有 CNAME 目标（包括多级 CNAME）
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
                        blockedAliasTarget = aliasTarget
                        break
                    }
                }
            }
            if (blockedAliasTarget != null) {
                output.write(PacketCodec.buildUdpResponse(info, DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return))
                StatsRepository.recordBlockedDns(this, vendor, appName, 512)
                return
            }
            if (aliasTargets.isNotEmpty()) {
                rememberHttpDecryptAliasTargets(question, aliasTargets, upstreamResponse, appName)
                rememberHttpsDecryptAliasTargets(question, aliasTargets, upstreamResponse, appName)
                rememberQuicAliasTargets(question, aliasTargets, upstreamResponse, appName)
                rememberAdIpTargetsForAliases(question, aliasTargets, upstreamResponse, appName)
            }
            rememberQuicTargets(question, upstreamResponse, appName, vendor)
            rememberHttpDecryptTargets(question, upstreamResponse, appName, vendor)
            rememberHttpsDecryptTargets(question, upstreamResponse, appName, vendor)
            rememberAdIpTargets(question, upstreamResponse, appName, vendor)
            cacheDnsResponse(question, upstreamResponse)
            output.write(PacketCodec.buildUdpResponse(info, upstreamResponse))
            return
        }

        if (isTcp && shouldRouteViaLocalProxy(info)) {
            observeLocalProxyTcpFlow(info)
            if (handleLocalProxyTcpHandshake(info)) {
                return
            }
        }

        if (isUdp) {
            observeLocalProxyUdpFlow(info)
        }

        if (httpDecryptEnabled && shouldBlockEncryptedDnsDirectFlow(info)) {
            return
        }

        if (!httpDecryptEnabled) {
            return
        }
        if (isTcp) {
            if (shouldBlockHttpDecryptConnection(info)) {
                return
            }
            // Observe ClientHello first so the same packet can prewarm the TLS bridge
            // before synthetic HTTPS proxy state attempts to bind and consume it.
            observeHttpsClientHello(info)
            observeHttpsTransparentProxyFlow(info)
            if (handleHttpsProxyHandshake(info, output)) {
                return
            }
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

        val cacheKeys = buildCacheKeys(info)
        val localProxyTarget = belongsToLocalProxyTargetUid(cacheKeys)

        val destinationIp = formatAddress(info.destinationAddress)
        maybePruneRouteCaches()
        val route = synchronized(quicRouteCache) {
            quicRouteCache[destinationIp]
        }
        val httpsTarget = synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache[destinationIp]
        }
        if (localProxyTarget) {
            logDecisionOnce(
                key = "local-proxy-quic-fallback:$destinationIp:${info.sourcePort}",
                message = "Blocked QUIC for local proxy target app ip=$destinationIp sourcePort=${info.sourcePort} to force TCP fallback through single-VPN local proxy bridge",
                minIntervalMillis = 15_000L
            )
            return true
        }
        val domain = httpsTarget?.domain ?: route?.domain ?: return false
        if (RuleRepository.isWhitelistedDomain(domain)) return false
        if (RuleRepository.isSensitiveAuthDomain(domain)) return false

        val domainContext = resolveDomainDecisionContext(
            domain = domain,
            info = info,
            knownAppName = httpsTarget?.appName?.takeIf { it.isNotBlank() }
                ?: route?.appName?.takeIf { it.isNotBlank() },
            knownVendor = httpsTarget?.vendor?.takeIf { it.isNotBlank() }
                ?: route?.vendor?.takeIf { it.isNotBlank() },
            destinationPort = info.destinationPort,
            sourcePort = info.sourcePort
        )
        val appName = domainContext.appName
        if (RuleRepository.isGameCoreDomain(domain) || RuleRepository.isCommunityAppHint(appName) || RuleRepository.isSocialCoreDomain(domain)) {
            return false
        }
        val vendor = domainContext.vendor
        val matchedRule = domainContext.matchedRule
        val bypassReason = HttpsMitmRepository.getActiveBypassReason(this, domain)
        val shouldForceTcpFallback = httpDecryptEnabled && httpsTarget != null && matchedRule != null && bypassReason == null
        if (matchedRule == null && !shouldForceTcpFallback) return false

        val reason = when {
            localProxyTarget -> "local-proxy-force-tcp"
            matchedRule != null -> "matched-rule"
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
        if (info.protocol != OsConstants.IPPROTO_TCP) return false
        val config = localProxyCoexistConfig
        if (!config.enabled) return false
        if (localProxyReachable != true) return false
        val port = config.port ?: return false
        if (port !in 1..65535) return false
        if (localProxyTargetPackages.isEmpty()) return false
        val cacheKeys = buildCacheKeys(info)
        localProxyTargetAppCache[cacheKeys.flowKey]?.let { return it }
        localProxyTargetAppCache[cacheKeys.sourcePortKey]?.let { return it }
        val targetContext = resolveDestinationAppContext(info) ?: return false
        val destinationIp = targetContext.destinationIp
        if (isLocalProxyEndpoint(destinationIp, info.destinationPort)) {
            localProxyTargetAppCache[cacheKeys.flowKey] = false
            logDecisionOnce(
                key = "local-proxy-route-skip:$destinationIp:${info.destinationPort}",
                message = "Skipped local proxy reroute for direct proxy endpoint ip=$destinationIp port=${info.destinationPort}",
                minIntervalMillis = 15_000L
            )
            return false
        }
        val appName = targetContext.appName
        val matched = belongsToLocalProxyTarget(appName) || belongsToLocalProxyTargetUid(cacheKeys)
        localProxyTargetAppCache[cacheKeys.flowKey] = matched
        localProxyTargetAppCache[cacheKeys.sourcePortKey] = matched
        return matched
    }

    private fun belongsToLocalProxyTarget(appName: String): Boolean {
        if (appName.isBlank() || localProxyTargetPackages.isEmpty()) return false
        return localProxyTargetPackages.any { packageName ->
            appName == packageName || appName.endsWith("($packageName)")
        }
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
        if (selectedPackage != null && selectedPackage in localProxyTargetPackages) return true
        return packages.any { packageName -> packageName in localProxyTargetPackages }
    }

    private fun shouldBlockEncryptedDnsDirectFlow(info: com.HanFeng.model.PacketInfo): Boolean {
        val targetContext = resolveDestinationAppContext(info) ?: return false
        val destinationIp = targetContext.destinationIp
        val port = info.destinationPort
        val currentDnsEndpoint = isCurrentDnsEndpoint(destinationIp)
        val knownPublicDnsEndpoint = isKnownPublicDnsEndpoint(destinationIp)
        val httpsTarget = synchronized(httpsDecryptIpCache) { httpsDecryptIpCache[destinationIp] }
        val quicTarget = synchronized(quicRouteCache) { quicRouteCache[destinationIp] }
        val trackedTargetDomain = httpsTarget?.domain ?: quicTarget?.domain
        val trackedTargetBypassProtection = trackedTargetDomain?.let(RuleRepository::isBypassProtectionDomain) == true
        if (!currentDnsEndpoint && !knownPublicDnsEndpoint) {
            if (port != 443 && port != 853 && port != 784 && port != 8853) return false
            if (!trackedTargetBypassProtection) return false
        }
        val encryptedDnsPort = when (port) {
            853 -> true
            784, 8853 -> knownPublicDnsEndpoint
            443 -> knownPublicDnsEndpoint || trackedTargetBypassProtection
            else -> false
        }
        if (!encryptedDnsPort) return false
        val appName = httpsTarget?.appName?.takeIf { it.isNotBlank() }
            ?: quicTarget?.appName?.takeIf { it.isNotBlank() }
            ?: targetContext.appName
        StatsRepository.recordBlockedHttp(this, "加密DNS", appName, 8 * 1024)
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
        val normalizedSni = sniHost.lowercase()
        val looksLikeEncryptedDnsHost = RuleRepository.isBypassProtectionDomain(normalizedSni)
        val offersHttp2OrHttp3 = alpnProtocols.any { protocol ->
            protocol.equals("h2", ignoreCase = true) ||
                protocol.equals("h3", ignoreCase = true) ||
                protocol.startsWith("h3-", ignoreCase = true)
        }
        val knownDnsEndpoint = isKnownPublicDnsEndpoint(destinationIp) || isCurrentDnsEndpoint(destinationIp)
        if (!looksLikeEncryptedDnsHost) return false
        if (!offersHttp2OrHttp3 && !knownDnsEndpoint) return false
        StatsRepository.recordBlockedHttp(this, "加密DNS", appName, 8 * 1024)
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
        if (info.protocol != OsConstants.IPPROTO_TCP || info.destinationPort != 80) return false
        val ip = formatAddress(info.destinationAddress)
        maybePruneRouteCaches()
        val target = synchronized(httpDecryptIpCache) {
            httpDecryptIpCache[ip]
        } ?: return false
        if (RuleRepository.isSensitiveAuthDomain(target.domain)) return false
        if (RuleRepository.isSocialCoreDomain(target.domain)) return false
        if (isProtectedTrafficDomain(target.domain)) return false
        // HTTP 连接拦截优化：复用计算结果，避免重复调用
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
        if (!shouldTreatAsTrackedAdTarget(target.domain, appName, vendor, matchedRule, includeProtectedNovelUrl = false, includeForceNovelQuic = false)) {
            return false
        }
        // 需要拦截
        StatsRepository.recordBlockedMitm(this, vendor, appName, 50 * 1024)
        LogRepository.append(
            this,
            "Blocked HTTP connection domain=${target.domain} ip=$ip app=$appName vendor=$vendor source=${target.source} via=http-decrypt-entry"
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
        val decryptTarget = synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache[destinationIp]
        } ?: return
        val clientHelloInfo = TlsClientHelloParser.extractClientHelloInfo(info.payload) ?: return
        val sniHost = clientHelloInfo.sniHost ?: return
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
        val targetGeneralAd = RuleRepository.shouldTreatAsGeneralAdTraffic(decryptTarget.domain, targetVendor, appName)
        val sniVendor = sniContext.vendor
        val sniGeneralAd = RuleRepository.shouldTreatAsGeneralAdTraffic(sniHost, sniVendor, appName)
        if (shouldBlockEncryptedDnsClientHello(sniHost, clientHelloInfo.offeredAlpnProtocols, destinationIp, appName)) {
            return
        }
        if (!blockedTarget && !targetGeneralAd && RuleRepository.isWhitelistedDomain(decryptTarget.domain)) return
        if (!blockedSni && !sniGeneralAd && RuleRepository.isWhitelistedDomain(sniHost)) return
        if (RuleRepository.isSensitiveAuthDomain(decryptTarget.domain)) return
        if (RuleRepository.isSensitiveAuthDomain(sniHost)) return
        val decryptSource = decryptTarget.source
        val flowKey = buildCacheKeys(info).flowKey
        logDecisionOnce(
            key = "https-sni:$sniHost:${formatAddress(info.destinationAddress)}",
            message = "Observed HTTPS ClientHello SNI domain=$sniHost app=$appName target=$destinationIp source=$decryptSource alpn=${clientHelloInfo.offeredAlpnProtocols.joinToString(",").ifBlank { "none" }} prefersHttp2=${clientHelloInfo.offeredAlpnProtocols.any { it.equals("h2", ignoreCase = true) }} tls=${clientHelloInfo.handshakeVersion ?: "unknown"} supportedTls=${clientHelloInfo.supportedTlsVersions.joinToString(",").ifBlank { "none" }} ech=${clientHelloInfo.encryptedClientHelloOffered}",
            minIntervalMillis = 15_000L
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
        if (isLocalLoopOrProxyEndpoint(ip, info.destinationPort)) {
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache.remove(flowKey)
            }
            logDecisionOnce(
                key = "https-transparent-skip-local:$ip:${info.destinationPort}",
                message = "Skipped HTTPS transparent proxy tracking for local endpoint ip=$ip port=${info.destinationPort}",
                minIntervalMillis = 15_000L
            )
            return
        }
        maybePruneRouteCaches()
        val target = synchronized(httpsDecryptIpCache) {
            httpsDecryptIpCache[ip]
        } ?: return
        if (RuleRepository.isSensitiveAuthDomain(target.domain)) {
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache.remove(flowKey)
            }
            return
        }
        if (RuleRepository.isSocialCoreDomain(target.domain)) {
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache.remove(flowKey)
            }
            return
        }
        // TCP SYN 阶段检查规则（早期拦截广告连接）优化：复用计算结果
        if (info.tcpFlags.hasTcpFlag(TCP_FLAG_SYN) && !info.tcpFlags.hasTcpFlag(TCP_FLAG_ACK)) {
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
            if (shouldTreatAsTrackedAdTarget(target.domain, appName, vendor, matchedRule, includeProtectedNovelUrl = false, includeForceNovelQuic = false)) {
                StatsRepository.recordBlockedMitm(this, vendor, appName, 64 * 1024)
                LogRepository.append(
                    this,
                    "Blocked HTTPS connection at SYN domain=${target.domain} ip=$ip app=$appName vendor=$vendor source=${target.source} via=https-decrypt-entry"
                )
                synchronized(httpsProxyFlowCache) {
                    httpsProxyFlowCache.remove(flowKey)
                }
                return
            }
        }
        
        val cooldownReason = HttpsMitmRepository.getActiveBypassReason(this, target.domain)
        if (cooldownReason != null) {
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache.remove(flowKey)
            }
            return
        }

        val existingSession = TlsMitmSessionManager.getSession(flowKey)
        if (existingSession?.bypassMitm == true) {
            synchronized(httpsProxyFlowCache) {
                httpsProxyFlowCache.remove(flowKey)
            }
            return
        }
        
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
                appName = resolvedProxyAppName,
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

    private fun observeLocalProxyTcpFlow(info: com.HanFeng.model.PacketInfo) {
        if (info.protocol != OsConstants.IPPROTO_TCP) return
        val flowKey = buildCacheKeys(info).flowKey
        val targetContext = resolveDestinationAppContext(info) ?: return
        val targetIp = targetContext.destinationIp
        val appName = targetContext.appName
        val flags = info.tcpFlags
        synchronized(localProxyTcpFlowCache) {
            val current = localProxyTcpFlowCache[flowKey]
            val nextState = when {
                flags.hasTcpFlag(TCP_FLAG_SYN) && !flags.hasTcpFlag(TCP_FLAG_ACK) -> "syn_seen"
                flags.hasTcpFlag(TCP_FLAG_SYN) && flags.hasTcpFlag(TCP_FLAG_ACK) -> "syn_ack_seen"
                flags.hasTcpFlag(TCP_FLAG_FIN) -> "fin_seen"
                flags.hasTcpFlag(TCP_FLAG_RST) -> "rst_seen"
                info.payload.isNotEmpty() -> "payload_seen"
                else -> current?.state ?: "tracked"
            }
            localProxyTcpFlowCache[flowKey] = LocalProxyTcpFlow(
                flowKey = flowKey,
                targetIp = targetIp,
                targetPort = info.destinationPort,
                sourcePort = info.sourcePort,
                appName = appName,
                state = nextState,
                bridgeHost = localProxyCoexistConfig.host,
                bridgePort = localProxyCoexistConfig.port,
                lastSequenceNumber = info.tcpSequenceNumber,
                lastAcknowledgementNumber = info.tcpAcknowledgementNumber,
                lastSeenAt = System.currentTimeMillis()
            )
            while (localProxyTcpFlowCache.size > 512) {
                val firstKey = localProxyTcpFlowCache.entries.firstOrNull()?.key ?: break
                localProxyTcpFlowCache.remove(firstKey)
            }
        }
    }

    private fun handleLocalProxyTcpHandshake(info: com.HanFeng.model.PacketInfo): Boolean {
        if (info.protocol != OsConstants.IPPROTO_TCP) return false
        val flowKey = buildCacheKeys(info).flowKey
        val current = synchronized(localProxyTcpFlowCache) {
            localProxyTcpFlowCache[flowKey]
        } ?: return false
        val bridgePort = current.bridgePort ?: return false
        if (bridgePort !in 1..65535) return false
        val flags = info.tcpFlags
        val seq = info.tcpSequenceNumber ?: 0L
        val ack = info.tcpAcknowledgementNumber ?: 0L
        val payloadLength = info.payload.size.toLong()
        if (flags.hasTcpFlag(TCP_FLAG_SYN) && !flags.hasTcpFlag(TCP_FLAG_ACK)) {
            val serverSeq = current.serverInitialSequence ?: synthesizeServerSequence(flowKey)
            synchronized(localProxyTcpFlowCache) {
                localProxyTcpFlowCache[flowKey] = current.copy(
                    state = "syn_ack_sent",
                    clientInitialSequence = seq,
                    serverInitialSequence = serverSeq,
                    lastSequenceNumber = seq,
                    lastAcknowledgementNumber = ack,
                    lastSeenAt = System.currentTimeMillis()
                )
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
            return true
        }
        if (current.state == "syn_ack_sent" && flags == TCP_FLAG_ACK && payloadLength == 0L) {
            ensureLocalProxyBridgeSocket(flowKey, current, info)
            synchronized(localProxyTcpFlowCache) {
                localProxyTcpFlowCache[flowKey] = current.copy(
                    state = "bridge_connecting",
                    lastSequenceNumber = seq,
                    lastAcknowledgementNumber = ack,
                    clientNextSequence = seq,
                    lastSeenAt = System.currentTimeMillis()
                )
            }
            return true
        }
        if ((current.state == "bridge_connecting" || current.state == "established" || current.state == "payload_acknowledged") && (payloadLength > 0 || flags.hasTcpFlag(TCP_FLAG_PSH))) {
            val serverSeq = current.serverInitialSequence ?: synthesizeServerSequence(flowKey)
            val expectedClientSeq = current.clientNextSequence ?: ((current.clientInitialSequence ?: seq) + 1)
            val isRetransmission = seq < expectedClientSeq || (current.lastClientPayloadSequence == seq && current.lastClientPayloadLength == payloadLength)
            if (seq > expectedClientSeq) {
                val updatedBufferedSegments = if (info.payload.isNotEmpty()) {
                    mergeBufferedClientSegments(current.bufferedClientSegments, ClientPayloadSegment(seq, info.payload))
                } else {
                    current.bufferedClientSegments
                }
                if (bufferedClientPayloadBytes(updatedBufferedSegments) > MAX_BUFFERED_CLIENT_BYTES) {
                    emitLocalProxyBridgeReset(flowKey, info, "Buffered client window overflow reset local proxy flow target=${current.targetIp}:${current.targetPort}")
                    return true
                }
                synchronized(localProxyTcpFlowCache) {
                    val latest = localProxyTcpFlowCache[flowKey] ?: return@synchronized
                    localProxyTcpFlowCache[flowKey] = latest.copy(
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
                emitLocalProxyBridgeReset(flowKey, info, "Buffered client replay overflow reset local proxy flow target=${current.targetIp}:${current.targetPort}")
                return true
            }
            val bridgeConnected = synchronized(localProxyBridgeSocketCache) {
                localProxyBridgeSocketCache.containsKey(flowKey)
            }
            if (bridgeConnected) {
                flushResult.forwardSegments.forEach { segment ->
                    forwardPayloadToLocalProxyBridge(flowKey, segment.payload)
                }
            }
            val lastForwardedSegment = flushResult.forwardSegments.lastOrNull()
            synchronized(localProxyTcpFlowCache) {
                localProxyTcpFlowCache[flowKey] = current.copy(
                    state = if (bridgeConnected) "payload_acknowledged" else "bridge_connecting",
                    lastSequenceNumber = seq,
                    lastAcknowledgementNumber = ack,
                    clientNextSequence = flushResult.nextExpectedSequence,
                    bufferedClientSegments = if (bridgeConnected) {
                        flushResult.remainingSegments
                    } else {
                        mergeBufferedClientSegments(flushResult.remainingSegments, flushResult.forwardSegments)
                    },
                    lastClientPayloadSequence = if (bridgeConnected) {
                        lastForwardedSegment?.sequenceNumber ?: current.lastClientPayloadSequence
                    } else {
                        current.lastClientPayloadSequence
                    },
                    lastClientPayloadLength = if (bridgeConnected) {
                        lastForwardedSegment?.payload?.size?.toLong() ?: current.lastClientPayloadLength
                    } else {
                        current.lastClientPayloadLength
                    },
                    lastSeenAt = System.currentTimeMillis()
                )
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
            return true
        }
        if ((current.state == "server_payload_sent" || current.state == "payload_acknowledged") && flags == TCP_FLAG_ACK && payloadLength == 0L) {
            if (current.pendingServerSegments.isNotEmpty() && current.serverNextSequence != null && ack < current.serverNextSequence) {
                val remainingSegments = trimAcknowledgedServerSegments(current.pendingServerSegments, ack)
                if (remainingSegments.isNotEmpty()) {
                    resendPendingLocalProxyBridgePayload(info, current, remainingSegments)
                    synchronized(localProxyTcpFlowCache) {
                        val latest = localProxyTcpFlowCache[flowKey] ?: return@synchronized
                        localProxyTcpFlowCache[flowKey] = latest.copy(
                            pendingServerSegments = remainingSegments,
                            lastSeenAt = System.currentTimeMillis()
                        )
                    }
                    return true
                }
            }
            synchronized(localProxyTcpFlowCache) {
                val latest = localProxyTcpFlowCache[flowKey] ?: return@synchronized
                localProxyTcpFlowCache[flowKey] = latest.copy(
                    state = if (latest.state == "bridge_fin_sent") "bridge_fin_acked" else "server_payload_acked",
                    pendingServerSegments = emptyList(),
                    lastSequenceNumber = seq,
                    lastAcknowledgementNumber = ack,
                    lastSeenAt = System.currentTimeMillis()
                )
            }
            return true
        }
        if (current.state == "bridge_fin_sent" && flags == TCP_FLAG_ACK && payloadLength == 0L) {
            closeLocalProxyTcpFlow(flowKey, current, "Closed bridge-finished local proxy flow target=${current.targetIp}:${current.targetPort}")
            return true
        }
        if (flags.hasTcpFlag(TCP_FLAG_FIN)) {
            val serverSeqBase = current.serverInitialSequence ?: synthesizeServerSequence(flowKey)
            val nextServerSeq = current.serverNextSequence ?: (serverSeqBase + 1)
            val ackNumber = seq + 1 + payloadLength
            synchronized(localProxyTcpFlowCache) {
                localProxyTcpFlowCache[flowKey] = current.copy(
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
            synchronized(localProxyBridgeSocketCache) {
                localProxyBridgeSocketCache.remove(flowKey)?.close()
            }
            return true
        }
        if (current.state == "fin_ack_sent" && flags == TCP_FLAG_ACK && payloadLength == 0L) {
            closeLocalProxyTcpFlow(flowKey, current, "Closed synthetic local proxy flow target=${current.targetIp}:${current.targetPort}")
            return true
        }
        if (flags.hasTcpFlag(TCP_FLAG_RST)) {
            closeLocalProxyTcpFlow(flowKey, current, "Reset synthetic local proxy flow target=${current.targetIp}:${current.targetPort}")
            return true
        }
        return false
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

    private fun ensureLocalProxyBridgeSocket(flowKey: String, flow: LocalProxyTcpFlow, request: com.HanFeng.model.PacketInfo) {
        synchronized(localProxyBridgeSocketCache) {
            if (localProxyBridgeSocketCache.containsKey(flowKey)) return
        }
        val host = flow.bridgeHost ?: return
        val port = flow.bridgePort ?: return
        scope.launch {
            runCatching {
                val socket = Socket()
                socket.tcpNoDelay = true
                if (!protect(socket)) {
                    LogRepository.append(this@AdBlockVpnService, "Protect local proxy bridge socket failed flow=$flowKey target=${flow.targetIp}:${flow.targetPort}")
                }
                socket.connect(InetSocketAddress(host, port), LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS)
                val connectedBridge = performLocalProxyConnect(socket, request, host, port)
                val session = LocalProxyBridgeSocketSession(
                    flowKey = flowKey,
                    requestTemplate = request,
                    socket = connectedBridge.socket,
                    input = connectedBridge.socket.getInputStream(),
                    output = connectedBridge.socket.getOutputStream()
                )
                synchronized(localProxyBridgeSocketCache) {
                    localProxyBridgeSocketCache[flowKey] = session
                }
                flushBufferedLocalProxyPayload(flowKey)
                scope.launch { runLocalProxyBridgeReader(session) }
                LogRepository.append(this@AdBlockVpnService, "Connected local proxy bridge flow=$flowKey target=${flow.targetIp}:${flow.targetPort} via=$host:$port protocol=${connectedBridge.protocol}")
            }.onFailure {
                LogRepository.append(this@AdBlockVpnService, "Connect local proxy bridge failed flow=$flowKey: ${it.message ?: it.javaClass.simpleName}")
                emitLocalProxyBridgeReset(flowKey, request, "Bridge connect reset local proxy flow target=${flow.targetIp}:${flow.targetPort}")
            }
        }
    }

    private fun performSocks5Connect(socket: Socket, request: com.HanFeng.model.PacketInfo) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val methodReply = ByteArray(2)
        input.readFully(methodReply)
        check(methodReply[0].toInt() == 0x05 && methodReply[1].toInt() == 0x00) { "SOCKS5 method negotiation failed" }
        val addressType = if (request.version == 6) 0x04 else 0x01
        val connectRequest = ByteArray(4 + request.destinationAddress.size + 2)
        connectRequest[0] = 0x05
        connectRequest[1] = 0x01
        connectRequest[2] = 0x00
        connectRequest[3] = addressType.toByte()
        request.destinationAddress.copyInto(connectRequest, 4)
        val portOffset = 4 + request.destinationAddress.size
        connectRequest[portOffset] = ((request.destinationPort ushr 8) and 0xFF).toByte()
        connectRequest[portOffset + 1] = (request.destinationPort and 0xFF).toByte()
        output.write(connectRequest)
        output.flush()
        val header = ByteArray(4)
        input.readFully(header)
        check(header[0].toInt() == 0x05 && header[1].toInt() == 0x00) { "SOCKS5 connect failed code=${header[1].toInt() and 0xFF}" }
        val addressLength = when (header[3].toInt() and 0xFF) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> input.read().takeIf { it >= 0 } ?: throw IllegalStateException("SOCKS5 domain length missing")
            else -> throw IllegalStateException("SOCKS5 atyp unsupported")
        }
        val skip = ByteArray(addressLength + 2)
        input.readFully(skip)
    }

    private fun performLocalProxyConnect(socket: Socket, request: com.HanFeng.model.PacketInfo, host: String, port: Int): LocalProxyConnectedSocket {
        socket.soTimeout = LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS
        val socksError = runCatching {
            performSocks5Connect(socket, request)
        }.exceptionOrNull()
        if (socksError == null) return LocalProxyConnectedSocket(socket = socket, protocol = "socks5")

        runCatching { socket.close() }
        val fallbackSocket = Socket()
        fallbackSocket.tcpNoDelay = true
        fallbackSocket.soTimeout = LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS
        if (!protect(fallbackSocket)) {
            LogRepository.append(this, "Protect local proxy HTTP CONNECT socket failed target=${formatAddress(request.destinationAddress)}:${request.destinationPort}")
        }
        fallbackSocket.connect(InetSocketAddress(host, port), LOCAL_PROXY_CONNECT_TIMEOUT_MILLIS)
        runCatching {
            performHttpConnect(fallbackSocket, request)
        }.onFailure {
            runCatching { fallbackSocket.close() }
            throw IllegalStateException(
                "Local proxy connect failed socks=${socksError.message ?: socksError.javaClass.simpleName}, http=${it.message ?: it.javaClass.simpleName}"
            )
        }
        return LocalProxyConnectedSocket(socket = fallbackSocket, protocol = "http_connect")
    }

    private fun performHttpConnect(socket: Socket, request: com.HanFeng.model.PacketInfo) {
        val host = formatAddress(request.destinationAddress)
        val connectRequest = buildString {
            append("CONNECT ")
            append(host)
            append(':')
            append(request.destinationPort)
            append(" HTTP/1.1\r\n")
            append("Host: ")
            append(host)
            append(':')
            append(request.destinationPort)
            append("\r\n")
            append("Proxy-Connection: Keep-Alive\r\n")
            append("Connection: Keep-Alive\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(connectRequest)
        output.flush()
        val responseBytes = input.readUntilHeaderTerminator(8192)
        val responseText = responseBytes.toString(Charsets.US_ASCII)
        val statusLine = responseText.lineSequence().firstOrNull()?.trim().orEmpty()
        check(statusLine.startsWith("HTTP/1.1 200") || statusLine.startsWith("HTTP/1.0 200")) {
            "HTTP CONNECT failed status=${statusLine.ifBlank { "unknown" }}"
        }
    }

    private fun handleHttpsProxyHandshake(info: com.HanFeng.model.PacketInfo, output: FileOutputStream): Boolean {
        if (info.protocol != OsConstants.IPPROTO_TCP || info.destinationPort != 443) return false
        val flowKey = buildCacheKeys(info).flowKey
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
                message = "Sent synthetic SYN-ACK for HTTPS proxy flow domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort}",
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
                message = "Established synthetic HTTPS proxy flow domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort}",
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
                    message = "Buffered out-of-order HTTPS proxy payload domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} seq=$seq expected=$expectedClientSeq bufferedSegments=${updatedBufferedSegments.size} bufferedBytes=${bufferedClientPayloadBytes(updatedBufferedSegments)}",
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
                    "Acknowledged HTTPS proxy payload domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} size=$payloadLength bridge=${current.bridgeHost}:${current.bridgePort} nextClientSeq=${flushResult.nextExpectedSequence} bufferedSegments=${flushResult.remainingSegments.size} bufferedBytes=${bufferedClientPayloadBytes(flushResult.remainingSegments)}"
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
                        message = "Retransmitted synthetic HTTPS server payload window domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} ack=$ack expected=${current.serverNextSequence} pendingSegments=${remainingSegments.size} pendingBytes=${pendingServerPayloadBytes(remainingSegments)}",
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
                message = "Acknowledged synthetic HTTPS server payload domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort} ack=$ack pendingSegments=0 pendingBytes=0",
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
                message = "Sent synthetic FIN-ACK for HTTPS proxy flow domain=${current.domain} app=${current.appName.ifBlank { "unknown" }} source=${current.source} bridge=${current.bridgeHost}:${current.bridgePort}",
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
                if (!protect(socket)) {
                    LogRepository.append(this@AdBlockVpnService, "Protect local HTTPS bridge socket failed flow=$flowKey domain=${flow.domain} app=${flow.appName.ifBlank { "unknown" }} source=${flow.source}")
                }
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
                    reason = "io-bridge:${it.message ?: it.javaClass.simpleName}"
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
                        reason = "io-bridge:${it.message ?: it.javaClass.simpleName}"
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
                        reason = "io-bridge:${error.message ?: error.javaClass.simpleName}"
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

    private fun forwardPayloadToLocalProxyBridge(flowKey: String, payload: ByteArray) {
        if (payload.isEmpty()) return
        val session = synchronized(localProxyBridgeSocketCache) {
            localProxyBridgeSocketCache[flowKey]
        } ?: return
        scope.launch {
            runCatching {
                session.output.write(payload)
            }.onFailure {
                LogRepository.append(this@AdBlockVpnService, "Forward local proxy payload failed flow=$flowKey: ${it.message ?: it.javaClass.simpleName}")
                emitLocalProxyBridgeReset(flowKey, session.requestTemplate, "Bridge write reset local proxy flow target=${resolveLocalProxyTarget(flowKey)}")
            }
        }
    }

    private fun flushBufferedLocalProxyPayload(flowKey: String) {
        val flow = synchronized(localProxyTcpFlowCache) {
            val current = localProxyTcpFlowCache[flowKey] ?: return
            if (current.bufferedClientSegments.isEmpty()) {
                localProxyTcpFlowCache[flowKey] = current.copy(
                    state = "established",
                    lastSeenAt = System.currentTimeMillis()
                )
                return
            }
            localProxyTcpFlowCache[flowKey] = current.copy(
                state = "established",
                bufferedClientSegments = emptyList(),
                lastClientPayloadSequence = current.bufferedClientSegments.lastOrNull()?.sequenceNumber ?: current.lastClientPayloadSequence,
                lastClientPayloadLength = current.bufferedClientSegments.lastOrNull()?.payload?.size?.toLong() ?: current.lastClientPayloadLength,
                lastSeenAt = System.currentTimeMillis()
            )
            current
        }
        flow.bufferedClientSegments.forEach { segment ->
            forwardPayloadToLocalProxyBridge(flowKey, segment.payload)
        }
    }

    private fun runLocalProxyBridgeReader(session: LocalProxyBridgeSocketSession) {
        val buffer = ByteArray(16 * 1024)
        var resetSent = false
        while (scope.isActive && isRunning) {
            val count = try {
                session.input.read(buffer)
            } catch (error: Exception) {
                LogRepository.append(this, "Read local proxy bridge payload failed flow=${session.flowKey}: ${error.message ?: error.javaClass.simpleName}")
                emitLocalProxyBridgeReset(session.flowKey, session.requestTemplate, "Bridge read reset local proxy flow target=${resolveLocalProxyTarget(session.flowKey)}")
                resetSent = true
                break
            }
            if (count <= 0) break
            emitLocalProxyBridgePayload(session.flowKey, session.requestTemplate, buffer.copyOf(count))
        }
        if (!resetSent) {
            emitLocalProxyBridgeFin(session.flowKey, session.requestTemplate)
        }
        synchronized(localProxyBridgeSocketCache) {
            localProxyBridgeSocketCache.remove(session.flowKey)?.close()
        }
        synchronized(localProxyTcpFlowCache) {
            val current = localProxyTcpFlowCache[session.flowKey] ?: return@synchronized
            localProxyTcpFlowCache[session.flowKey] = current.copy(
                state = "bridge_socket_closed",
                lastSeenAt = System.currentTimeMillis()
            )
        }
    }

    private fun emitLocalProxyBridgePayload(flowKey: String, request: com.HanFeng.model.PacketInfo, payload: ByteArray) {
        val flow = synchronized(localProxyTcpFlowCache) {
            localProxyTcpFlowCache[flowKey]
        } ?: return
        val serverSeqBase = flow.serverInitialSequence ?: synthesizeServerSequence(flowKey)
        val nextServerSeq = flow.serverNextSequence ?: (serverSeqBase + 1)
        val clientAck = flow.clientNextSequence ?: ((flow.clientInitialSequence ?: 0L) + 1)
        val freshSegments = buildServerPayloadSegments(nextServerSeq, payload)
        writeServerPayloadSegments(request, clientAck, freshSegments)
        synchronized(localProxyTcpFlowCache) {
            val current = localProxyTcpFlowCache[flowKey] ?: return@synchronized
            val mergedPendingSegments = mergePendingServerSegments(current.pendingServerSegments, freshSegments)
            if (pendingServerPayloadBytes(mergedPendingSegments) > MAX_BUFFERED_SERVER_BYTES) {
                emitLocalProxyBridgeReset(flowKey, request, "Pending server window overflow reset local proxy flow target=${current.targetIp}:${current.targetPort}")
                return@synchronized
            }
            localProxyTcpFlowCache[flowKey] = current.copy(
                state = "server_payload_sent",
                serverNextSequence = nextServerSeq + payload.size,
                lastServerPayloadSequence = nextServerSeq,
                lastServerPayload = payload,
                pendingServerSegments = mergedPendingSegments,
                lastSeenAt = System.currentTimeMillis()
            )
        }
    }

    private fun emitLocalProxyBridgeFin(flowKey: String, request: com.HanFeng.model.PacketInfo) {
        val flow = synchronized(localProxyTcpFlowCache) {
            localProxyTcpFlowCache[flowKey]
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
        synchronized(localProxyTcpFlowCache) {
            val current = localProxyTcpFlowCache[flowKey] ?: return@synchronized
            localProxyTcpFlowCache[flowKey] = current.copy(
                state = "bridge_fin_sent",
                serverNextSequence = nextServerSeq + 1,
                lastSeenAt = System.currentTimeMillis()
            )
        }
    }

    private fun closeLocalProxyTcpFlow(flowKey: String, flow: LocalProxyTcpFlow, message: String) {
        synchronized(localProxyTcpFlowCache) {
            localProxyTcpFlowCache.remove(flowKey)
        }
        synchronized(localProxyBridgeSocketCache) {
            localProxyBridgeSocketCache.remove(flowKey)?.close()
        }
        logDecisionOnce(
            key = "local-proxy-flow:$flowKey:closed",
            message = message,
            minIntervalMillis = 5_000L
        )
    }

    private fun emitLocalProxyBridgeReset(flowKey: String, request: com.HanFeng.model.PacketInfo, message: String) {
        val flow = synchronized(localProxyTcpFlowCache) {
            localProxyTcpFlowCache[flowKey]
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
        closeLocalProxyTcpFlow(flowKey, flow, message)
    }

    private fun resolveLocalProxyTarget(flowKey: String): String {
        return synchronized(localProxyTcpFlowCache) {
            localProxyTcpFlowCache[flowKey]?.let { "${it.targetIp}:${it.targetPort}" }
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
        if (isProtectedTrafficDomain(question.domain)) return
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isProtectedNovelAppDomain(question.domain)) return
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isNovelContentDomain(question.domain)) return
        val domainContext = resolveDomainDecisionContextForApp(question.domain, appName, question.qType)
        val effectiveVendor = domainContext.vendor.takeIf { it.isNotBlank() } ?: vendor
        val matchedRule = domainContext.matchedRule
        val aggressiveNovelBlock = RuleRepository.shouldAggressivelyBlockForNovelApp(this, question.domain, appName, effectiveVendor)
        val generalAdTraffic = RuleRepository.shouldTreatAsGeneralAdTraffic(question.domain, effectiveVendor, appName)
        if (matchedRule == null && !aggressiveNovelBlock && !generalAdTraffic) return
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
            while (httpDecryptIpCache.size > 1024) {
                val firstKey = httpDecryptIpCache.entries.firstOrNull()?.key ?: break
                httpDecryptIpCache.remove(firstKey)
            }
        }
        if (HttpDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload(
                forceImmediate = shouldForceImmediateDecryptRouteReload(question.domain, appName, effectiveVendor, matchedRule)
            )
        }
    }

    private fun pruneHttpDecryptTargetsLocked() {
        val now = System.currentTimeMillis()
        httpDecryptIpCache.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun maybePruneRouteCaches() {
        val now = System.currentTimeMillis()
        if (now - lastRouteCachePruneCheckAt < 5_000L) return
        lastRouteCachePruneCheckAt = now
        synchronized(adIpTargetCache) {
            pruneAdIpTargetsLocked()
        }
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
            matchedAliases.forEach { matchedAlias ->
                val vendor = matchedAliasContexts.getValue(matchedAlias).vendor
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
            }
            while (httpDecryptIpCache.size > 1024) {
                val firstKey = httpDecryptIpCache.entries.firstOrNull()?.key ?: break
                httpDecryptIpCache.remove(firstKey)
            }
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
        if (isProtectedTrafficDomain(question.domain)) return
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isProtectedNovelAppDomain(question.domain)) return
        if (RuleRepository.isNovelAppHint(appName) && RuleRepository.isNovelContentDomain(question.domain)) return
        val domainContext = resolveDomainDecisionContextForApp(question.domain, appName, question.qType)
        val effectiveVendor = domainContext.vendor.takeIf { it.isNotBlank() } ?: vendor
        if (!shouldTrackHttpsMitmTarget(question.domain, question.qType, appName, effectiveVendor, domainContext.matchedRule)) return
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
            while (httpsDecryptIpCache.size > 1024) {
                val firstKey = httpsDecryptIpCache.entries.firstOrNull()?.key ?: break
                httpsDecryptIpCache.remove(firstKey)
            }
        }
        if (HttpsDecryptRouteRepository.upsertRoutes(this, routeEntries)) {
            requestHttpDecryptRouteReload(
                forceImmediate = shouldForceImmediateDecryptRouteReload(question.domain, appName, effectiveVendor, domainContext.matchedRule)
            )
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
            addresses.forEach { address ->
                quicRouteCache[formatAddress(address)] = QuicRouteTarget(
                    domain = question.domain.lowercase(),
                    vendor = effectiveVendor,
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
            addresses.forEach { address ->
                adIpTargetCache[formatAddress(address)] = AdIpTarget(
                    domain = domain,
                    vendor = effectiveVendor,
                    appName = appName,
                    source = "direct",
                    expiresAt = expiresAt
                )
            }
            while (adIpTargetCache.size > 2048) {
                val firstKey = adIpTargetCache.entries.firstOrNull()?.key ?: break
                adIpTargetCache.remove(firstKey)
            }
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
            matchedAliases.forEach { matchedAlias ->
                val vendor = matchedAliasContexts.getValue(matchedAlias).vendor
                addresses.forEach { address ->
                    adIpTargetCache[formatAddress(address)] = AdIpTarget(
                        domain = matchedAlias,
                        vendor = vendor,
                        appName = appName,
                        source = "alias",
                        expiresAt = expiresAt
                    )
                }
            }
            while (adIpTargetCache.size > 2048) {
                val firstKey = adIpTargetCache.entries.firstOrNull()?.key ?: break
                adIpTargetCache.remove(firstKey)
            }
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
            matchedAliases.forEach { matchedAlias ->
                val vendor = matchedAliasContexts.getValue(matchedAlias).vendor
                addresses.forEach { address ->
                    quicRouteCache[formatAddress(address)] = QuicRouteTarget(
                        domain = matchedAlias,
                        vendor = vendor,
                        appName = appName,
                        source = "alias",
                        expiresAt = expiresAt
                    )
                }
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

    private fun pruneAdIpTargetsLocked() {
        val now = System.currentTimeMillis()
        adIpTargetCache.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun rememberHttpsDecryptAliasTargets(
        question: com.HanFeng.model.DnsQuestion,
        aliasTargets: List<String>,
        response: ByteArray,
        appName: String
    ) {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return
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
            matchedAliases.forEach { matchedAlias ->
                val vendor = matchedAliasContexts.getValue(matchedAlias).vendor
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
            }
            while (httpsDecryptIpCache.size > 1024) {
                val firstKey = httpsDecryptIpCache.entries.firstOrNull()?.key ?: break
                httpsDecryptIpCache.remove(firstKey)
            }
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
        return DomainDecisionContext(
            appName = appName,
            matchedRule = matchedRule,
            vendor = vendor
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
        return matchedRule != null
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
                        logDecisionOnce(
                            key = "upstream-dns-failed:${server.hostAddress}",
                            message = "Upstream DNS ${server.hostAddress} failed: ${it.message ?: it.javaClass.simpleName}",
                            minIntervalMillis = 60_000L
                        )
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
            cachedDnsServers = CachedDnsServers(sorted, now + dnsServerCacheTtlMillis)
        }
        logDecisionOnce(
            key = "resolve-dns-servers:${sorted.joinToString(",") { it.hostAddress ?: it.hostName }}",
            message = "Resolved upstream DNS servers dynamic=${dynamicServers.joinToString("|") { it.hostAddress ?: it.hostName }} final=${sorted.joinToString("|") { it.hostAddress ?: it.hostName }} networks=${describeUnderlyingNetworks()}",
            minIntervalMillis = 5_000L
        )
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

    private fun isLocalProxyEndpoint(host: String, port: Int): Boolean {
        val configPort = localProxyCoexistConfig.port
        return port == configPort && (host == localProxyCoexistConfig.host || host == "127.0.0.1" || host == "::1")
    }

    private fun isLocalLoopOrProxyEndpoint(host: String, port: Int): Boolean {
        if (host == "127.0.0.1" || host == "::1") return true
        return isLocalProxyEndpoint(host, port)
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
        val now = System.currentTimeMillis()
        val elapsed = now - lastHttpRouteReloadAt
        if (forceImmediate && elapsed >= HTTP_DECRYPT_ROUTE_FORCE_MIN_INTERVAL_MILLIS) {
            performHttpDecryptRouteReload("forced")
            return
        }
        if (elapsed >= HTTP_DECRYPT_ROUTE_RELOAD_MIN_INTERVAL_MILLIS) {
            performHttpDecryptRouteReload("immediate")
            return
        }
        if (pendingRouteReloadJob?.isActive == true) return
        val delayMillis = if (forceImmediate) {
            (HTTP_DECRYPT_ROUTE_FORCE_MIN_INTERVAL_MILLIS - elapsed).coerceAtLeast(80L)
        } else {
            HTTP_DECRYPT_ROUTE_RELOAD_MIN_INTERVAL_MILLIS - elapsed
        }
        pendingRouteReloadJob = scope.launch {
            delay(delayMillis)
            if (!isRunning || !httpDecryptEnabled || !FeatureSettingsRepository.isAdBlockEnabled(this@AdBlockVpnService)) {
                return@launch
            }
            performHttpDecryptRouteReload(if (forceImmediate) "forced-scheduled" else "scheduled")
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
                it.expiresAt > System.currentTimeMillis() && !RuleRepository.isWhitelistedDomain(it.domain)
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

    private fun formatAddress(bytes: ByteArray): String = InetAddress.getByAddress(bytes).hostAddress ?: ""

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
        return DomainDecisionContext(
            appName = appName,
            matchedRule = matchedRule,
            vendor = vendor
        )
    }

    private fun isProtectedTrafficDomain(domain: String): Boolean {
        return RuleRepository.shouldProtectMediaTraffic(domain) ||
            RuleRepository.shouldProtectBusinessTraffic(domain)
    }

    private data class DomainDecisionContext(
        val appName: String,
        val matchedRule: BlockRule?,
        val vendor: String
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (hasRecentOwnerUidFailure(cacheKeys)) return null
        readCachedOwnerUid(cacheKeys)?.let { uid ->
            if (uid > 0) return buildAppLabel(uid)
            return null
        }
        return runCatching {
            val protocol = if (info.protocol == OsConstants.IPPROTO_UDP) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
            val localHost = InetAddress.getByAddress(info.sourceAddress).hostAddress ?: return@runCatching null
            val remoteHost = InetAddress.getByAddress(info.destinationAddress).hostAddress ?: return@runCatching null
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(localHost, info.sourcePort)
            val remote = InetSocketAddress(remoteHost, info.destinationPort)
            var uid = connectivityManager.getConnectionOwnerUid(protocol, local, remote)
            if (uid <= 0 && shizukuConnectionOwnerReady) {
                uid = ShizukuConnectionOwnerRepository.getConnectionOwnerUid(
                    context = this,
                    protocol = protocol,
                    localHost = localHost,
                    localPort = info.sourcePort,
                    remoteHost = remoteHost,
                    remotePort = info.destinationPort
                )
                if (uid <= 0) {
                    ShizukuConnectionOwnerRepository.invalidateService()
                    shizukuConnectionOwnerReady = false
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
            logDecisionOnce(
                key = "resolve-app-failed:${info.sourcePort}:${info.destinationPort}",
                message = "Resolve app failed: ${it.message ?: it.javaClass.simpleName}",
                minIntervalMillis = 60_000L
            )
            null
        }
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
        if (appLabelCache.size > 512) appLabelCache.clear()
        appLabelCache[uid] = appLabel
        return appLabel
    }

    private fun selectBestPackageForUid(packages: List<String>): String? {
        if (packages.isEmpty()) return null
        if (packages.size == 1) return packages.first()
        return packages.maxWithOrNull(
            compareBy<String> { packagePriorityScore(it) }
                .thenByDescending { it.length }
        )
    }

    private fun packagePriorityScore(packageName: String): Int {
        var score = 0
        if (!packageName.startsWith("com.android.") && !packageName.startsWith("android.")) score += 3
        if (!packageName.contains(":") && !packageName.endsWith(".service") && !packageName.endsWith(".provider")) score += 2
        if (ShizukuAdControlCatalog.findPresetByPackage(packageName) != null) score += 6
        if (RuleRepository.isNovelAppHint(packageName)) score += 5
        if (RuleRepository.isAggressiveAdAppHint(packageName)) score += 4
        if (RuleRepository.isCommunityAppHint(packageName)) score += 2
        return score
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
        if (appNameCache.size > 2048) appNameCache.clear()
        if (sourcePortAppCache.size > 1024) sourcePortAppCache.clear()
        if (domainAppCache.size > 1024) domainAppCache.clear()
        appNameCache[cacheKeys.flowKey] = appName
        appNameCache[cacheKeys.portKey] = appName
        sourcePortAppCache[cacheKeys.sourcePortKey] = appName
        domainAppCache[domainInfo.normalized] = appName
        domainInfo.secondLevelDomain?.let { domainAppCache[it] = appName }
    }

    private fun cacheOwnerUid(cacheKeys: AppResolveCacheKeys, uid: Int) {
        if (ownerUidCache.size > 2048) ownerUidCache.clear()
        ownerUidCache[cacheKeys.flowKey] = uid
        ownerUidCache[cacheKeys.sourcePortKey] = uid
    }

    private fun cacheOwnerUidFailure(cacheKeys: AppResolveCacheKeys) {
        if (ownerUidFailureCache.size > 2048) ownerUidFailureCache.clear()
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
        if (vendorHintCache.size > 2048) vendorHintCache.clear()
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
            isWaitingForReacquire() -> "运行状态：VPN共存中"
            isRunning -> "运行状态：已开启"
            else -> "运行状态：已关闭"
        }
    }

    private fun notificationModeText(): String {
        val localProxyText = localProxyNotificationText()
        return when {
            isWaitingForReacquire() -> appendModeSuffix("当前处于 VPN 共存中", localProxyText)
            !isRunning -> appendModeSuffix("当前模式：已关闭", localProxyText)
            httpDecryptEnabled && mitmCertificateInstalled -> appendModeSuffix("当前模式：MITM + DNS 拦截", localProxyText)
            httpDecryptEnabled -> appendModeSuffix("当前模式：DNS 拦截，等待证书安装", localProxyText)
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

    private fun appendModeSuffix(base: String, suffix: String?): String {
        if (suffix.isNullOrBlank()) return base
        return "$base | $suffix"
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

    private fun refreshRuntimeFeatureFlags() {
        httpDecryptEnabled = FeatureSettingsRepository.isHttpDecryptEnabled(this)
        mitmCertificateInstalled = HttpsMitmRepository.isCertificateInstalled(this)
        shizukuConnectionOwnerReady = ShizukuConnectionOwnerRepository.isReady(this) &&
            ShizukuRepository.canAttemptUserService(this)
        shizukuAdControlReady = ShizukuAdControlRepository.isReady(this) &&
            ShizukuRepository.canUseEnhancedMode(this)
        localProxyCoexistConfig = WhitelistRepository.getLocalProxyCoexistConfig(this)
        localProxyTargetPackages = WhitelistRepository.getLocalProxyTargetPackages(this)
        lightweightPassThroughMode = RuleRepository.getRuleCount(this) == 0 &&
            !httpDecryptEnabled &&
            !localProxyCoexistConfig.enabled
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
        return localProxyCoexistConfig.enabled && localProxyTargetPackages.isNotEmpty()
    }

    companion object {
        const val ACTION_START = "com.HanFeng.START"
        const val ACTION_STOP = "com.HanFeng.STOP"
        const val ACTION_RELOAD = "com.HanFeng.RELOAD"
        const val ACTION_STATUS_CHANGED = "com.HanFeng.STATUS_CHANGED"
        const val EXTRA_USER_INITIATED = "extra_user_initiated"
        private const val CHANNEL_ID = "adblock_vpn"
        private const val NOTIFICATION_ID = 1001
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
        private const val HTTP_DECRYPT_ROUTE_RELOAD_MIN_INTERVAL_MILLIS = 1_500L
        private const val HTTP_DECRYPT_ROUTE_FORCE_MIN_INTERVAL_MILLIS = 250L
        private const val UNDERLYING_NETWORK_REFRESH_MIN_INTERVAL_MILLIS = 1_500L
        private const val FOREGROUND_NOTIFICATION_REFRESH_MIN_INTERVAL_MILLIS = 3_000L
        private const val OWNER_UID_FAILURE_TTL_MILLIS = 60_000L
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

    private data class UnderlyingNetworkCandidate(
        val network: Network,
        val capabilities: NetworkCapabilities
    )

    private data class MatchedIpRule(
        val rule: com.HanFeng.model.BlockRule,
        val appName: String
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

    private data class AdIpTarget(
        val domain: String,
        val vendor: String,
        val appName: String,
        val source: String,
        val expiresAt: Long
    )

    private data class AppResolveCacheKeys(
        val flowKey: String,
        val portKey: String,
        val sourcePortKey: String
    )

    private data class NormalizedDomainInfo(
        val normalized: String,
        val secondLevelDomain: String?
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

    private data class LocalProxyTcpFlow(
        val flowKey: String,
        val targetIp: String,
        val targetPort: Int,
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

    private data class LocalProxyBridgeSocketSession(
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

    private data class LocalProxyConnectedSocket(
        val socket: Socket,
        val protocol: String
    )

    private fun java.io.InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count <= 0) throw IllegalStateException("Unexpected EOF")
            offset += count
        }
    }

    private fun java.io.InputStream.readUntilHeaderTerminator(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < maxBytes) {
            val next = read()
            if (next < 0) throw IllegalStateException("Unexpected EOF")
            output.write(next)
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return output.toByteArray()
        }
        throw IllegalStateException("HTTP CONNECT header too large")
    }
}
