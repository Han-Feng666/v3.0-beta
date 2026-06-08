package com.HanFeng.core.network

object ExpiringTargetCacheSupport {
    fun <T> pruneExpiredLocked(
        cache: LinkedHashMap<String, T>,
        now: Long,
        expiresAt: (T) -> Long
    ) {
        cache.entries.removeIf { expiresAt(it.value) <= now }
    }

    fun <T> putAllPrunedLocked(
        cache: LinkedHashMap<String, T>,
        entries: Iterable<Pair<String, T>>,
        maxSize: Int
    ) {
        entries.forEach { (key, value) ->
            cache[key] = value
        }
        while (cache.size > maxSize) {
            val firstKey = cache.entries.firstOrNull()?.key ?: break
            cache.remove(firstKey)
        }
    }
}
