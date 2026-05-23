package com.HanFeng.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.service.AdBlockVpnService

class VpnAutoRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!FeatureSettingsRepository.isAdBlockEnabled(context)) {
            LogRepository.append(context, "Skipped VPN auto restart action=$action because interception is disabled")
            return
        }
        val serviceIntent = Intent(context, AdBlockVpnService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onSuccess {
            LogRepository.append(context, "Requested VPN auto restart action=$action")
        }.onFailure {
            LogRepository.append(context, "VPN auto restart failed action=$action: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
