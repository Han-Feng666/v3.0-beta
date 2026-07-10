package com.HanFeng.core.network

import java.util.LinkedHashMap

object DecisionLogSupport {
    fun shouldLogLocked(
        cache: MutableMap<String, Long>,
        key: String,
        now: Long,
        minIntervalMillis: Long,
        maxEntries: Int = 256
    ): Boolean {
        val previous = cache[key]
        if (previous != null && now - previous < minIntervalMillis) {
            return false
        }
        cache[key] = now
        while (cache.size > maxEntries) {
            val firstKey = cache.entries.firstOrNull()?.key ?: break
            cache.remove(firstKey)
        }
        return true
    }
}
