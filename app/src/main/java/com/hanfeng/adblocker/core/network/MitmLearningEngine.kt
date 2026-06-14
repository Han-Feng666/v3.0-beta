package com.HanFeng.core.network

import com.HanFeng.data.RuleRepository
import java.util.concurrent.ConcurrentHashMap

object MitmLearningEngine {
    enum class SignalType { DNS_UNKNOWN, QUIC_DOWNGRADE, TLS_FINGERPRINT, HTTPDNS_HINT, BODY_CONFIRMED_AD, CERT_PINNING_FAILURE }

    data class Signal(
        val appName: String?,
        val domain: String?,
        val ip: String?,
        val sniHost: String? = null,
        val alpnProtocols: List<String> = emptyList(),
        val type: SignalType,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class Candidate(
        val appName: String,
        val domain: String,
        val ip: String,
        val score: Int,
        val ttlMillis: Long,
        val reasons: List<String>
    )

    private data class Bucket(
        val appName: String,
        val domain: String,
        val ip: String,
        val firstSeenAt: Long,
        var lastSeenAt: Long,
        val reasons: MutableSet<String> = linkedSetOf(),
        var unknownDnsCount: Int = 0,
        var quicDowngradeCount: Int = 0,
        var tlsFingerprintCount: Int = 0,
        var httpDnsHintCount: Int = 0,
        var bodyConfirmedCount: Int = 0
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val certPinningCooldown = ConcurrentHashMap<String, Long>()
    private const val WINDOW_MILLIS = 5 * 60_000L
    private const val COOLDOWN_MILLIS = 10 * 60_000L
    private const val BASE_THRESHOLD = 7

    fun observe(signal: Signal, enabled: Boolean): Candidate? {
        if (!enabled) return null
        val appName = signal.appName?.takeIf { it.isNotBlank() } ?: return null
        val domain = signal.domain?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: signal.sniHost?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return null
        val ip = signal.ip?.takeIf { it.isNotBlank() } ?: return null
        if (RuleRepository.isWhitelistedDomain(domain) || RuleRepository.isSensitiveAuthDomain(domain)) return null
        if (RuleRepository.shouldProtectBusinessTraffic(domain) || RuleRepository.shouldProtectMediaTraffic(domain)) return null
        if (isInCertPinningCooldown(domain)) return null
        val now = signal.timestamp
        val key = "$appName|$domain|$ip"
        val bucket = buckets.compute(key) { _, current ->
            if (current == null || now - current.firstSeenAt > WINDOW_MILLIS) {
                Bucket(appName = appName, domain = domain, ip = ip, firstSeenAt = now, lastSeenAt = now)
            } else {
                current.lastSeenAt = now
                current
            }
        } ?: return null
        applySignal(bucket, signal)
        val score = score(bucket)
        if (score < thresholdForApp(appName)) return null
        return Candidate(
            appName = appName,
            domain = domain,
            ip = ip,
            score = score,
            ttlMillis = ttlForScore(score),
            reasons = bucket.reasons.toList()
        )
    }

    fun markCertPinningFailure(domain: String?) {
        val normalized = domain?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        certPinningCooldown[normalized] = System.currentTimeMillis() + COOLDOWN_MILLIS
    }

    fun isInCertPinningCooldown(domain: String): Boolean {
        val now = System.currentTimeMillis()
        val expiresAt = certPinningCooldown[domain] ?: return false
        if (expiresAt <= now) {
            certPinningCooldown.remove(domain)
            return false
        }
        return true
    }

    fun prune() {
        val now = System.currentTimeMillis()
        buckets.entries.removeIf { now - it.value.lastSeenAt > WINDOW_MILLIS }
        certPinningCooldown.entries.removeIf { it.value <= now }
    }

    private fun applySignal(bucket: Bucket, signal: Signal) {
        when (signal.type) {
            SignalType.DNS_UNKNOWN -> {
                bucket.unknownDnsCount++
                bucket.reasons += "dns-unknown"
            }
            SignalType.QUIC_DOWNGRADE -> {
                bucket.quicDowngradeCount++
                bucket.reasons += "quic-downgrade"
            }
            SignalType.TLS_FINGERPRINT -> {
                bucket.tlsFingerprintCount++
                if (signal.sniHost.isNullOrBlank()) bucket.reasons += "empty-sni"
                if (signal.alpnProtocols.any { it.equals("h3", true) || it.startsWith("h3-", true) }) bucket.reasons += "h3-alpn"
            }
            SignalType.HTTPDNS_HINT -> {
                bucket.httpDnsHintCount++
                bucket.reasons += "httpdns-hint"
            }
            SignalType.BODY_CONFIRMED_AD -> {
                bucket.bodyConfirmedCount++
                bucket.reasons += "mitm-body-confirmed"
            }
            SignalType.CERT_PINNING_FAILURE -> Unit
        }
    }

    private fun score(bucket: Bucket): Int {
        var score = 0
        if (RuleRepository.isAggressiveAdAppHint(bucket.appName)) score += 3
        if (RuleRepository.isCommunityAppHint(bucket.appName)) score += 1
        if (RuleRepository.looksLikeAdSdkInfraDomain(bucket.domain)) score += 4
        score += (bucket.unknownDnsCount.coerceAtMost(4))
        score += bucket.quicDowngradeCount.coerceAtMost(3) * 2
        score += bucket.tlsFingerprintCount.coerceAtMost(2)
        score += bucket.httpDnsHintCount.coerceAtMost(2) * 2
        score += bucket.bodyConfirmedCount.coerceAtMost(2) * 4
        return score
    }

    private fun thresholdForApp(appName: String): Int {
        return if (RuleRepository.isAggressiveAdAppHint(appName)) BASE_THRESHOLD - 2 else BASE_THRESHOLD
    }

    private fun ttlForScore(score: Int): Long {
        return when {
            score >= 12 -> 10 * 60_000L
            score >= 9 -> 5 * 60_000L
            else -> 2 * 60_000L
        }
    }
}
