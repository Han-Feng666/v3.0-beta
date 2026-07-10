package com.HanFeng.core.network

import java.util.LinkedHashMap

object BridgeSessionSupport {
    fun <T> removeAndClose(
        cache: MutableMap<String, T>,
        key: String,
        close: (T) -> Unit
    ) {
        FlowCacheSupport.remove(cache, key)?.let(close)
    }
}
