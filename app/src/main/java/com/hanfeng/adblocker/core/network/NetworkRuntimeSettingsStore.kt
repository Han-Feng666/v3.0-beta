package com.HanFeng.core.network

import android.content.Context
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuConnectionOwnerRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.data.WhitelistRepository

object NetworkRuntimeSettingsStore {
    fun load(context: Context): NetworkFeatureFlags {
        val httpDecryptEnabled = FeatureSettingsRepository.isHttpDecryptEnabled(context)
        val localProxyConfig = WhitelistRepository.getLocalProxyCoexistConfig(context)
        val shizukuConnectionOwnerReady = loadShizukuConnectionOwnerReady(context)
        val shizukuAdControlReady = loadShizukuAdControlReady(context)
        return NetworkFeatureFlags(
            httpDecryptEnabled = httpDecryptEnabled,
            mitmCertificateInstalled = HttpsMitmRepository.isCertificateInstalled(context),
            shizukuConnectionOwnerReady = shizukuConnectionOwnerReady,
            shizukuAdControlReady = shizukuAdControlReady,
            shizukuStrictAppAdBlockEnabled = shizukuAdControlReady &&
                AppSettingsRepository.isShizukuStrictAppAdBlockEnabled(context),
            localProxyConfig = localProxyConfig,
            localProxyTargetPackages = WhitelistRepository.getLocalProxyTargetPackages(context),
            lightweightPassThroughMode = RuleRepository.getRuleCount(context) == 0 &&
                !httpDecryptEnabled &&
                !localProxyConfig.enabled
        )
    }

    private fun loadShizukuConnectionOwnerReady(context: Context): Boolean {
        if (!ShizukuConnectionOwnerRepository.isReady(context) || !ShizukuRepository.canAttemptUserService(context)) {
            return false
        }
        if (runCatching { ShizukuConnectionOwnerRepository.isServiceAlive() }.getOrDefault(false)) {
            return true
        }
        runCatching { ShizukuConnectionOwnerRepository.ensureBound(context) }
        return runCatching { ShizukuConnectionOwnerRepository.isServiceAlive() }.getOrDefault(false)
    }

    private fun loadShizukuAdControlReady(context: Context): Boolean {
        if (!ShizukuAdControlRepository.isReady(context) || !ShizukuRepository.canUseEnhancedMode(context)) {
            return false
        }
        if (runCatching { ShizukuAdControlRepository.checkServiceHealth(context) }.getOrDefault(false)) {
            return true
        }
        runCatching { ShizukuAdControlRepository.ensureBound(context) }
        return runCatching { ShizukuAdControlRepository.checkServiceHealth(context) }.getOrDefault(false)
    }

    fun isWaitingForReacquire(context: Context, isRunning: Boolean): Boolean {
        return !isRunning &&
            FeatureSettingsRepository.isAdBlockEnabled(context) &&
            FeatureSettingsRepository.isVpnRevokedByOtherVpn(context)
    }
}
