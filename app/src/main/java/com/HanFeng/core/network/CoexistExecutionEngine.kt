package com.HanFeng.core.network

import android.content.Context
import com.HanFeng.data.WhitelistRepository

object CoexistExecutionEngine : ExecutionEngine {
    override val mode: NetworkMode = NetworkMode.COEXIST_LOCAL_PROXY

    override fun isAvailable(context: Context): Boolean {
        val config = WhitelistRepository.getLocalProxyCoexistConfig(context)
        return config.enabled && !config.host.isNullOrBlank() && config.port != null
    }
}
