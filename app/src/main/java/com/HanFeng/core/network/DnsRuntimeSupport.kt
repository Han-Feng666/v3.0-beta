package com.HanFeng.core.network

import com.HanFeng.dns.DnsMessageParser
import com.HanFeng.model.DnsQuestion
import java.net.InetAddress
import java.util.LinkedHashMap

object DnsRuntimeSupport {
    private const val MIN_CACHE_TTL_MILLIS = 60_000L
    private const val MIN_NEGATIVE_CACHE_TTL_MILLIS = 30_000L

    data class CachedDnsResponse(
        val payload: ByteArray,
        val expiresAt: Long
    )

    data class UpstreamServerState(
        val failureCount: Int = 0,
        val cooldownUntil: Long = 0L,
        val lastSuccessAt: Long = 0L
    )

    data class CachedDnsServers(
        val servers: List<InetAddress>,
        val expiresAt: Long
    )

    fun readCachedDnsResponse(
        cache: MutableMap<String, CachedDnsResponse>,
        question: DnsQuestion,
        queryPayload: ByteArray,
        now: Long,
        staleCacheGraceMillis: Long
    ): ByteArray? {
        val key = DnsMessageParser.buildCacheKey(question)
        synchronized(cache) {
            val cached = cache[key] ?: return null
            if (cached.expiresAt <= now) return null
            return DnsMessageParser.restoreCachedResponseForQuery(cached.payload, queryPayload)
        }
    }

    fun readStaleCachedDnsResponse(
        cache: MutableMap<String, CachedDnsResponse>,
        question: DnsQuestion,
        queryPayload: ByteArray,
        now: Long,
        staleCacheGraceMillis: Long
    ): ByteArray? {
        val key = DnsMessageParser.buildCacheKey(question)
        synchronized(cache) {
            val cached = cache[key] ?: return null
            if (cached.expiresAt > now) return null
            if (cached.expiresAt + staleCacheGraceMillis <= now) return null
            return DnsMessageParser.restoreCachedResponseForQuery(cached.payload, queryPayload)
        }
    }

    fun cacheDnsResponse(
        cache: MutableMap<String, CachedDnsResponse>,
        question: DnsQuestion,
        response: ByteArray,
        now: Long,
        maxEntries: Int = 256
    ) {
        val expiresAt = when {
            DnsMessageParser.isCacheableResponse(response, question) -> {
                val ttl = DnsMessageParser.extractCacheTtlMillis(response)
                now + ttl.coerceAtLeast(MIN_CACHE_TTL_MILLIS)
            }
            DnsMessageParser.isNegativeCacheableResponse(response, question) -> {
                val nttl = DnsMessageParser.negativeCacheTtlMillis()
                now + nttl.coerceAtLeast(MIN_NEGATIVE_CACHE_TTL_MILLIS)
            }
            else -> return
        }
        val normalized = DnsMessageParser.normalizeResponseForCache(response)
        synchronized(cache) {
            cache[DnsMessageParser.buildCacheKey(question)] = CachedDnsResponse(normalized, expiresAt)
            while (cache.size > maxEntries) {
                val it = cache.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }

    fun markUpstreamSuccess(
        states: MutableMap<String, UpstreamServerState>,
        server: InetAddress,
        now: Long
    ) {
        val key = server.hostAddress ?: server.hostName
        synchronized(states) {
            states[key] = UpstreamServerState(
                failureCount = 0,
                cooldownUntil = 0L,
                lastSuccessAt = now
            )
        }
    }

    fun markUpstreamFailure(
        states: MutableMap<String, UpstreamServerState>,
        server: InetAddress,
        now: Long
    ) {
        val key = server.hostAddress ?: server.hostName
        synchronized(states) {
            val current = states[key] ?: UpstreamServerState()
            val failures = (current.failureCount + 1).coerceAtMost(6)
            val cooldown = now + (400L shl (failures - 1)).coerceAtMost(15_000L)
            states[key] = current.copy(
                failureCount = failures,
                cooldownUntil = cooldown
            )
        }
    }

    fun currentUpstreamState(
        states: Map<String, UpstreamServerState>,
        server: InetAddress
    ): UpstreamServerState {
        val key = server.hostAddress ?: server.hostName
        synchronized(states) {
            return states[key] ?: UpstreamServerState()
        }
    }

    fun cacheResolvedServers(
        lock: Any,
        servers: List<InetAddress>,
        now: Long,
        ttlMillis: Long
    ): CachedDnsServers {
        synchronized(lock) {
            return CachedDnsServers(servers, now + ttlMillis)
        }
    }
}
