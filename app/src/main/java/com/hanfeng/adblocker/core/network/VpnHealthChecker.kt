package com.HanFeng.core.network

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.HanFeng.data.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object VpnHealthChecker {
    data class Snapshot(
        val tunActive: Boolean = false,
        val dnsActive: Boolean = false,
        val rulesLoaded: Boolean = false,
        val mitmReady: Boolean = false,
        val shizukuReady: Boolean = false,
        val ruleCount: Int = 0,
        val whitelistCount: Int = 0,
        val dynamicDecryptRouteCount: Int = 0,
        val warning: String? = null,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val stateInternal = MutableLiveData(Snapshot())
    val state: LiveData<Snapshot> = stateInternal

    @Volatile private var vpnStartedAt = 0L
    @Volatile private var firstTunPacketAt = 0L
    @Volatile private var firstDnsPacketAt = 0L
    private var watchdogJob: Job? = null

    fun onVpnReady(
        context: Context,
        scope: CoroutineScope,
        httpDecryptEnabled: Boolean,
        mitmReady: Boolean,
        shizukuReady: Boolean,
        ruleCount: Int,
        whitelistCount: Int,
        dynamicDecryptRouteCount: Int
    ) {
        vpnStartedAt = System.currentTimeMillis()
        firstTunPacketAt = 0L
        firstDnsPacketAt = 0L
        publish(
            Snapshot(
                rulesLoaded = ruleCount > 0,
                mitmReady = !httpDecryptEnabled || mitmReady,
                shizukuReady = shizukuReady,
                ruleCount = ruleCount,
                whitelistCount = whitelistCount,
                dynamicDecryptRouteCount = dynamicDecryptRouteCount
            )
        )
        LogRepository.append(
            context,
            "VPN health initialized rules=$ruleCount whitelist=$whitelistCount dynamicDecryptRoutes=$dynamicDecryptRouteCount mitmReady=${!httpDecryptEnabled || mitmReady} shizukuReady=$shizukuReady"
        )
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(3_000L)
            if (firstTunPacketAt == 0L) warn(context, "TUN read loop has not observed packets after 3s")
            delay(7_000L)
            if (firstDnsPacketAt == 0L) warn(context, "DNS handler has not observed queries after 10s")
        }
    }

    fun onTunPacket(context: Context) {
        if (firstTunPacketAt == 0L) {
            firstTunPacketAt = System.currentTimeMillis()
            update(context) { it.copy(tunActive = true, warning = null) }
            LogRepository.append(context, "VPN health TUN active")
        }
    }

    fun onDnsQuery(context: Context) {
        if (firstDnsPacketAt == 0L) {
            firstDnsPacketAt = System.currentTimeMillis()
            update(context) { it.copy(dnsActive = true, warning = null) }
            LogRepository.append(context, "VPN health DNS active")
        }
    }

    fun updateRuntime(
        context: Context,
        mitmReady: Boolean? = null,
        shizukuReady: Boolean? = null,
        dynamicDecryptRouteCount: Int? = null
    ) {
        update(context) { current ->
            current.copy(
                mitmReady = mitmReady ?: current.mitmReady,
                shizukuReady = shizukuReady ?: current.shizukuReady,
                dynamicDecryptRouteCount = dynamicDecryptRouteCount ?: current.dynamicDecryptRouteCount,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun reset() {
        watchdogJob?.cancel()
        watchdogJob = null
        vpnStartedAt = 0L
        firstTunPacketAt = 0L
        firstDnsPacketAt = 0L
        publish(Snapshot())
    }

    private fun warn(context: Context, message: String) {
        update(context) { it.copy(warning = message, updatedAt = System.currentTimeMillis()) }
        LogRepository.append(context, "VPN health warning: $message")
    }

    private fun update(context: Context, transform: (Snapshot) -> Snapshot) {
        val next = transform(stateInternal.value ?: Snapshot())
        publish(next)
    }

    private fun publish(snapshot: Snapshot) {
        stateInternal.postValue(snapshot)
    }
}
