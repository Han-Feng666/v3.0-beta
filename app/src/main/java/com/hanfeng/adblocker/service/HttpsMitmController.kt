package com.HanFeng.service

import android.content.Context
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.security.CertificateAuthorityManager

object HttpsMitmController {
    fun currentStatus(context: Context): String {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(context)) {
            return "disabled"
        }
        if (!HttpsMitmRepository.isCertificateReady(context)) {
            return "waiting_ca_generate"
        }
        val certificateInstalled = HttpsMitmRepository.isCertificateInstalled(context)
        if (!certificateInstalled) {
            return "waiting_ca_install"
        }
        if (HttpsTlsBridgeManager.activeBridgeCount() > 0) {
            return "bridge_listening"
        }
        return "ready_for_https_mitm"
    }

    fun onVpnStarted(context: Context) {
        when (currentStatus(context)) {
            "waiting_ca_generate" -> LogRepository.append(context, "HTTPS MITM pending: local CA has not been generated yet")
            "waiting_ca_install" -> LogRepository.append(context, "HTTPS MITM pending: local CA created, waiting for system installation")
            "bridge_listening" -> LogRepository.append(context, "HTTPS MITM bridge active: local TLS bridge is listening for prepared sessions")
            "ready_for_https_mitm" -> LogRepository.append(context, "HTTPS MITM scaffold ready: CA prepared, TLS interception pipeline pending")
        }
    }
}
