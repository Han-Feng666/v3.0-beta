package com.HanFeng.core.network

import com.HanFeng.model.LocalProxyCoexistConfig

object NetworkModeCoordinator {
    fun resolveMode(flags: NetworkFeatureFlags): NetworkMode {
        return if (isLocalProxyConfigured(flags.localProxyConfig)) {
            NetworkMode.COEXIST_LOCAL_PROXY
        } else {
            NetworkMode.FULL_VPN
        }
    }

    fun shouldCaptureFullTraffic(flags: NetworkFeatureFlags): Boolean {
        return isLocalProxyConfigured(flags.localProxyConfig) && flags.localProxyTargetPackages.isNotEmpty()
    }

    fun belongsToLocalProxyTarget(appName: String, targetPackages: Set<String>): Boolean {
        if (appName.isBlank() || targetPackages.isEmpty()) return false
        return targetPackages.any { packageName ->
            appName == packageName || appName.endsWith("($packageName)")
        }
    }

    fun belongsToLocalProxyUid(packages: List<String>, selectedPackage: String?, targetPackages: Set<String>): Boolean {
        if (selectedPackage != null && selectedPackage in targetPackages) return true
        return packages.any { packageName -> packageName in targetPackages }
    }

    private fun isLocalProxyConfigured(config: LocalProxyCoexistConfig): Boolean {
        return config.enabled && config.host.isNotBlank() && config.port in 1..65535
    }
}
