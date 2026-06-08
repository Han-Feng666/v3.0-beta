package com.HanFeng.core.network

data class NetworkRuntimeSnapshot(
    val isRunning: Boolean,
    val adBlockEnabled: Boolean,
    val revokedByOtherVpn: Boolean,
    val mode: NetworkMode
)
