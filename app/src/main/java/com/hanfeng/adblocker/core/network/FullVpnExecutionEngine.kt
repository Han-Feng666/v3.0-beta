package com.HanFeng.core.network

import android.content.Context
import android.net.VpnService

object FullVpnExecutionEngine : ExecutionEngine {
    override val mode: NetworkMode = NetworkMode.FULL_VPN

    override fun isAvailable(context: Context): Boolean {
        return runCatching { VpnService.prepare(context) }.getOrNull() == null
    }
}
