package com.HanFeng.core.network

import java.util.concurrent.ConcurrentHashMap

object RegexCache {

    private val cache = ConcurrentHashMap<String, Regex>(1024)

    fun get(pattern: String): Regex =
        cache.getOrPut(pattern) { Regex(pattern) }

    fun get(pattern: String, option: RegexOption): Regex {
        val key = "i:$pattern"
        return cache.getOrPut(key) { Regex(pattern, option) }
    }

    fun get(pattern: String, options: Set<RegexOption>): Regex {
        val key = "${options.sortedBy { it.name }.joinToString(",")}:$pattern"
        return cache.getOrPut(key) { Regex(pattern, options) }
    }

    fun clear() {
        cache.clear()
    }
}
