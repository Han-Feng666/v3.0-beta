package com.HanFeng.core.network

object FlowCacheSupport {
    fun <T> putPruned(
        cache: MutableMap<String, T>,
        key: String,
        value: T,
        maxSize: Int
    ) {
        synchronized(cache) {
            cache[key] = value
            pruneLocked(cache, maxSize)
        }
    }

    fun <T> updateIfPresent(
        cache: MutableMap<String, T>,
        key: String,
        update: (T) -> T
    ): T? {
        synchronized(cache) {
            val current = cache[key] ?: return null
            val next = update(current)
            cache[key] = next
            return next
        }
    }

    fun <T> remove(cache: MutableMap<String, T>, key: String): T? {
        synchronized(cache) {
            return cache.remove(key)
        }
    }

    fun <T> clear(cache: MutableMap<String, T>, onRemove: (T) -> Unit = {}) {
        synchronized(cache) {
            cache.values.forEach(onRemove)
            cache.clear()
        }
    }

    private fun <T> pruneLocked(cache: MutableMap<String, T>, maxSize: Int) {
        while (cache.size > maxSize) {
            val firstKey = cache.entries.firstOrNull()?.key ?: break
            cache.remove(firstKey)
        }
    }
}
