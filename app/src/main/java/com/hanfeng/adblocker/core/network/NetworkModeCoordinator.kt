package com.HanFeng.core.network

import com.HanFeng.model.LocalProxyCoexistConfig

object NetworkModeCoordinator {
    fun resolveMode(flags: NetworkFeatureFlags): NetworkMode {
        return if (shouldUseLocalProxy(flags.localProxyConfig, flags.localProxyTargetPackages)) {
            NetworkMode.COEXIST_LOCAL_PROXY
        } else {
            NetworkMode.FULL_VPN
        }
    }

    fun shouldCaptureFullTraffic(flags: NetworkFeatureFlags): Boolean {
        return shouldUseLocalProxy(flags.localProxyConfig, flags.localProxyTargetPackages)
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

    private fun shouldUseLocalProxy(config: LocalProxyCoexistConfig, targetPackages: Set<String>): Boolean {
        return config.enabled && targetPackages.isNotEmpty()
    }
}
