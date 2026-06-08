package com.HanFeng.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.LogRepository

class VpnAutoRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (!FeatureSettingsRepository.isAdBlockEnabled(context)) return
        val vpnPermissionReady = runCatching { VpnService.prepare(context) }.getOrNull() == null
        if (!vpnPermissionReady) {
            FeatureSettingsRepository.setAdBlockEnabled(context, false)
            LogRepository.append(context, "VPN auto restart skipped: system VPN permission not ready, action=$action")
            return
        }
        runCatching {
            NetworkKernel.start(context, userInitiated = false)
            LogRepository.append(context, "VPN auto restart receiver triggered: $action")
        }.onFailure {
            LogRepository.append(context, "VPN auto restart receiver failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
