package com.HanFeng.core.network

import com.HanFeng.data.RuleRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * 学习引擎信号融合后的短期拦截缓存。
 *
 * 由 [MitmLearningEngine.observe] 在候选评分超过阈值时调用 [recordCandidate] 写入，
 * 供 [SniInterceptor.evaluate] 与 AdBlockVpnService 的 IP 拦截快速路径查询，
 * 让启发式识别真正参与拦截决策而非仅用于 MITM 学习路由。
 *
 * 写入策略：
 * - 域名候选：score ≥ [SCORE_THRESHOLD_DOMAIN] 才记入，TTL 跟随 MitmLearningEngine.ttlForScore
 * - IP 候选：score ≥ [SCORE_THRESHOLD_IP] 才记入，用于 IP 直连拦截
 * - 受保护/白名单/敏感认证域名禁止写入，避免误拦截
 * - 上限到达驱逐最老条目，按 accessOrder LRU 淘汰
 *
 * 读取零阻塞，写入轻量，适合 VPN 热路径调用。
 */
object ScoredBlockCache {

    const val SCORE_THRESHOLD_DOMAIN = 10
    const val SCORE_THRESHOLD_IP = 12

    private const val DOMAIN_CACHE_MAX = 2048
    private const val IP_CACHE_MAX = 1024
    private const val PRUNE_INTERVAL_MILLIS = 600_000L

    data class Entry(
        val expiresAt: Long,
        val score: Int,
        val vendor: String,
        val reason: String
    )

    private val domainBlocks = ConcurrentHashMap<String, Entry>()
    private val ipBlocks = ConcurrentHashMap<String, Entry>()
    @Volatile private var lastPruneAt = 0L

    fun recordCandidate(
        domain: String,
        ip: String?,
        score: Int,
        ttlMillis: Long,
        vendor: String,
        reason: String
    ) {
        if (score < SCORE_THRESHOLD_DOMAIN) return
        val normalizedDomain = domain.trim().lowercase().takeIf { it.isNotBlank() } ?: return
        if (RuleRepository.isWhitelistedDomain(normalizedDomain)) return
        if (RuleRepository.isSensitiveAuthDomain(normalizedDomain)) return
        if (RuleRepository.isSocialCoreDomain(normalizedDomain)) return
        if (RuleRepository.shouldProtectMediaTraffic(normalizedDomain)) return
        if (RuleRepository.shouldProtectBusinessTraffic(normalizedDomain)) return

        val expiresAt = System.currentTimeMillis() + ttlMillis.coerceAtLeast(60_000L)
        domainBlocks[normalizedDomain] = Entry(expiresAt, score, vendor, reason)

        if (ip != null && score >= SCORE_THRESHOLD_IP) {
            val normalizedIp = ip.trim().takeIf { it.isNotBlank() } ?: return
            ipBlocks[normalizedIp] = Entry(expiresAt, score, vendor, reason)
        }
        pruneIfNeeded()
    }

    fun isDomainBlocked(domain: String): Entry? {
        if (domain.isBlank()) return null
        val normalized = domain.trim().lowercase()
        val entry = domainBlocks[normalized] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            domainBlocks.remove(normalized, entry)
            return null
        }
        return entry
    }

    fun isIpBlocked(ip: String): Entry? {
        if (ip.isBlank()) return null
        val entry = ipBlocks[ip.trim()] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            ipBlocks.remove(ip.trim(), entry)
            return null
        }
        return entry
    }

    fun snapshot(): Snapshot {
        val now = System.currentTimeMillis()
        val domains = domainBlocks.entries
            .filter { it.value.expiresAt > now }
            .associate { it.key to it.value }
        val ips = ipBlocks.entries
            .filter { it.value.expiresAt > now }
            .associate { it.key to it.value }
        return Snapshot(domainCount = domains.size, ipCount = ips.size)
    }

    data class Snapshot(val domainCount: Int, val ipCount: Int)

    fun pruneIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastPruneAt < PRUNE_INTERVAL_MILLIS) return
        lastPruneAt = now
        domainBlocks.entries.removeIf { it.value.expiresAt <= now }
        ipBlocks.entries.removeIf { it.value.expiresAt <= now }
        if (domainBlocks.size > DOMAIN_CACHE_MAX) {
            domainBlocks.entries
                .sortedBy { it.value.expiresAt }
                .take(domainBlocks.size - DOMAIN_CACHE_MAX)
                .forEach { domainBlocks.remove(it.key, it.value) }
        }
        if (ipBlocks.size > IP_CACHE_MAX) {
            ipBlocks.entries
                .sortedBy { it.value.expiresAt }
                .take(ipBlocks.size - IP_CACHE_MAX)
                .forEach { ipBlocks.remove(it.key, it.value) }
        }
    }

    fun clear() {
        domainBlocks.clear()
        ipBlocks.clear()
        lastPruneAt = 0L
    }
}
