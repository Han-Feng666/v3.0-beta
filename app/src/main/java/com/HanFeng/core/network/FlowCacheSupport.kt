package com.HanFeng.core.network

object FlowCacheSupport {
    /**
     * 缓存中对驱逐项需要执行的清理钩子（典型用法见 AdBlockVpnService 中带 Socket / Channel 的 sessionCache）。
     * LRU 在 prune 时如果不调用此钩子，会导致底层 fd / channel 泄漏，长时间运行后达到系统 fd 上限引发 OOM 或网络异常。
     */
    fun <T> putPruned(
        cache: MutableMap<String, T>,
        key: String,
        value: T,
        maxSize: Int,
        onEvict: (T) -> Unit = {}
    ) {
        synchronized(cache) {
            cache[key] = value
            pruneLocked(cache, maxSize, onEvict)
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

    fun <T> remove(cache: MutableMap<String, T>, key: String, onRemove: (T) -> Unit): T? {
        synchronized(cache) {
            val v = cache.remove(key) ?: return null
            runCatching { onRemove(v) }
            return v
        }
    }

    fun <T> clear(cache: MutableMap<String, T>, onRemove: (T) -> Unit = {}) {
        synchronized(cache) {
            cache.values.forEach { v -> runCatching { onRemove(v) } }
            cache.clear()
        }
    }

    private fun <T> pruneLocked(cache: MutableMap<String, T>, maxSize: Int, onEvict: (T) -> Unit) {
        if (cache.size <= maxSize) return
        val toEvict = cache.size - maxSize
        if (toEvict <= 0) return
        val evictedValues = ArrayList<T>(toEvict)
        val iter = cache.entries.iterator()
        var n = 0
        while (iter.hasNext() && n < toEvict) {
            val entry = iter.next()
            evictedValues += entry.value
            iter.remove()
            n++
        }
        for (v in evictedValues) {
            runCatching { onEvict(v) }
        }
    }
}
