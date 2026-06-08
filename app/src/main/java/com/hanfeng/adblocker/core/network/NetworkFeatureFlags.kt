package com.HanFeng.core.network

import com.HanFeng.model.LocalProxyCoexistConfig

data class NetworkFeatureFlags(
    val httpDecryptEnabled: Boolean,
    val mitmCertificateInstalled: Boolean,
    val shizukuConnectionOwnerReady: Boolean,
    val shizukuAdControlReady: Boolean,
    val shizukuStrictAppAdBlockEnabled: Boolean,
    val localProxyConfig: LocalProxyCoexistConfig,
    val localProxyTargetPackages: Set<String>,
    val lightweightPassThroughMode: Boolean
)
