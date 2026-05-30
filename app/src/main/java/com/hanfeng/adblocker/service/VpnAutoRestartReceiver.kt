package com.HanFeng.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
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
        val serviceIntent = Intent(context, AdBlockVpnService::class.java).apply {
            this.action = AdBlockVpnService.ACTION_START
            putExtra(AdBlockVpnService.EXTRA_USER_INITIATED, false)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            LogRepository.append(context, "VPN auto restart receiver triggered: $action")
        }.onFailure {
            LogRepository.append(context, "VPN auto restart receiver failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
