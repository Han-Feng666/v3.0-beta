package com.HanFeng.core.network

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.service.AdBlockVpnService

object NetworkKernel {
    val statusChangedAction: String
        get() = AdBlockVpnService.ACTION_STATUS_CHANGED

    fun isRunning(): Boolean = AdBlockVpnService.isRunning

    fun currentMode(context: Context): NetworkMode {
        return if (CoexistExecutionEngine.isAvailable(context)) {
            NetworkMode.COEXIST_LOCAL_PROXY
        } else {
            NetworkMode.FULL_VPN
        }
    }

    fun snapshot(context: Context): NetworkRuntimeSnapshot {
        return NetworkRuntimeSnapshot(
            isRunning = AdBlockVpnService.isRunning,
            adBlockEnabled = FeatureSettingsRepository.isAdBlockEnabled(context),
            revokedByOtherVpn = FeatureSettingsRepository.isVpnRevokedByOtherVpn(context),
            mode = currentMode(context)
        )
    }

    fun markStopped() {
        AdBlockVpnService.isRunning = false
    }

    fun start(context: Context, userInitiated: Boolean) {
        val serviceIntent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
            putExtra(AdBlockVpnService.EXTRA_USER_INITIATED, userInitiated)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP)
        )
    }

    fun reload(context: Context) {
        context.startService(
            Intent(context, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD)
        )
    }

    fun reloadIfRunning(context: Context) {
        if (!isRunning()) return
        reload(context)
    }
}
