package com.HanFeng.core.network

import com.HanFeng.data.RuleRepository
import java.util.concurrent.ConcurrentHashMap

object MitmLearningEngine {
    enum class SignalType {
        DNS_UNKNOWN, QUIC_DOWNGRADE, TLS_FINGERPRINT, HTTPDNS_HINT,
        BODY_CONFIRMED_AD, CERT_PINNING_FAILURE,
        REWARD_AD_API, AD_LOAD_REQUEST, AD_IMPRESSION_TRACK,
        AD_SDK_CONFIG, AD_INTERSTITIAL_TRIGGER, AD_SPLASH_PRELOAD,
        AD_MEDIATION_WATERFALL, AD_VIDEO_STREAM, AD_FEED_INSERT,
        AD_OPENRTB_BID, AD_VAST_XML, AD_GAME_OFFERWALL,
        AD_CONTENT_CLUSTER,
        ECH_OUTER_SNI, EMPTY_SNI_FLOW, NXDOMAIN_CLUSTER, DGA_DOMAIN_HINT
    }

    data class Signal(
        val appName: String?,
        val domain: String?,
        val ip: String?,
        val sniHost: String? = null,
        val alpnProtocols: List<String> = emptyList(),
        val type: SignalType,
        val timestamp: Long = System.currentTimeMillis(),
        val confidence: Int = 1,
        val matchedPattern: String? = null
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
        var bodyConfirmedCount: Int = 0,
        var rewardAdApiCount: Int = 0,
        var adLoadRequestCount: Int = 0,
        var adImpressionTrackCount: Int = 0,
        var adSdkConfigCount: Int = 0,
        var adInterstitialTriggerCount: Int = 0,
        var adSplashPreloadCount: Int = 0,
        var adMediationWaterfallCount: Int = 0,
        var adVideoStreamCount: Int = 0,
        var adFeedInsertCount: Int = 0,
        var adOpenRtbBidCount: Int = 0,
        var adVastXmlCount: Int = 0,
        var adGameOfferwallCount: Int = 0,
        var adContentClusterCount: Int = 0,
        var certPinningFailureCount: Int = 0,
        var echOuterSniCount: Int = 0,
        var emptySniCount: Int = 0,
        var nxdomainClusterCount: Int = 0,
        var dgaDomainHintCount: Int = 0
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val certPinningCooldown = ConcurrentHashMap<String, Long>()
    private val certPinningFailureRecords = ConcurrentHashMap<String, Int>()
    private val nxdomainDomains = ConcurrentHashMap.newKeySet<String>()
    private val sharedIpToDomains = ConcurrentHashMap<String, MutableSet<String>>()
    private const val WINDOW_MILLIS = 5 * 60_000L
    private const val EXTENDED_WINDOW_MILLIS = 10 * 60_000L
    private const val COOLDOWN_MILLIS = 10 * 60_000L
    private const val PINNING_FAILURE_BOOST_THRESHOLD = 2
    private const val BASE_THRESHOLD = 10

    fun observe(signal: Signal, enabled: Boolean): Candidate? {
        if (!enabled) return null
        val appName = signal.appName?.takeIf { it.isNotBlank() } ?: return null
        val domain = signal.domain?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: signal.sniHost?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: run {
                if (signal.type == SignalType.EMPTY_SNI_FLOW) {
                    val ip = signal.ip?.takeIf { it.isNotBlank() } ?: return null
                    return observeEmptySniCluster(appName, ip, signal)
                }
                return null
            }
        val ip = signal.ip?.takeIf { it.isNotBlank() } ?: return null

        if (RuleRepository.isWhitelistedDomain(domain) || RuleRepository.isSensitiveAuthDomain(domain)) {
            if (signal.type == SignalType.TLS_FINGERPRINT || signal.type == SignalType.AD_LOAD_REQUEST) {
                if (hasStrongAdBodySignal(appName, domain, ip)) {
                    return observeOverridingWhitelist(appName, domain, ip, signal)
                }
            }
            return null
        }
        if (RuleRepository.shouldProtectBusinessTraffic(domain) || RuleRepository.shouldProtectMediaTraffic(domain)) {
            if (signal.type == SignalType.AD_LOAD_REQUEST || signal.type == SignalType.REWARD_AD_API) {
                if (hasStrongAdBodySignal(appName, domain, ip)) {
                    return observeOverridingWhitelist(appName, domain, ip, signal)
                }
            }
            return null
        }

        val isInCooldown = isInCertPinningCooldown(domain)
        if (isInCooldown && !shouldStillRecordPinnedHost(domain)) return null

        val now = signal.timestamp
        val key = "$appName|$domain|$ip"
        val effectiveWindow = if (isInCooldown) EXTENDED_WINDOW_MILLIS else WINDOW_MILLIS
        val bucket = buckets.compute(key) { _, current ->
            if (current == null || now - current.firstSeenAt > effectiveWindow) {
                Bucket(appName = appName, domain = domain, ip = ip, firstSeenAt = now, lastSeenAt = now)
            } else {
                current.lastSeenAt = now
                current
            }
        } ?: return null
        applySignal(bucket, signal)

        recordSharedIp(domain, ip)

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

    private fun observeEmptySniCluster(appName: String, ip: String, signal: Signal): Candidate? {
        val key = "$appName|<empty-sni>|$ip"
        val now = signal.timestamp
        val bucket = buckets.compute(key) { _, current ->
            if (current == null || now - current.firstSeenAt > WINDOW_MILLIS) {
                Bucket(appName = appName, domain = "<empty-sni>", ip = ip, firstSeenAt = now, lastSeenAt = now)
            } else {
                current.lastSeenAt = now
                current
            }
        } ?: return null
        applySignal(bucket, signal)
        recordSharedIp("<empty-sni>", ip)
        val score = score(bucket)
        if (score < thresholdForApp(appName)) return null
        return Candidate(
            appName = appName,
            domain = "<empty-sni>",
            ip = ip,
            score = score,
            ttlMillis = ttlForScore(score),
            reasons = bucket.reasons.toList()
        )
    }

    private fun observeOverridingWhitelist(appName: String, domain: String, ip: String, signal: Signal): Candidate? {
        val now = signal.timestamp
        val key = "$appName|$domain|$ip"
        val bucket = buckets.compute(key) { _, current ->
            if (current == null || now - current.firstSeenAt > EXTENDED_WINDOW_MILLIS) {
                Bucket(appName = appName, domain = domain, ip = ip, firstSeenAt = now, lastSeenAt = now)
            } else {
                current.lastSeenAt = now
                current
            }
        } ?: return null
        applySignal(bucket, signal)
        recordSharedIp(domain, ip)
        val score = score(bucket) + 4
        if (score < thresholdForApp(appName)) return null
        return Candidate(
            appName = appName,
            domain = domain,
            ip = ip,
            score = score,
            ttlMillis = ttlForScore(score),
            reasons = (bucket.reasons + "whitelist-override").toList()
        )
    }

    private fun hasStrongAdBodySignal(appName: String, domain: String, ip: String): Boolean {
        val key = "$appName|$domain|$ip"
        val bucket = buckets[key] ?: return false
        return bucket.bodyConfirmedCount >= 1 ||
            bucket.rewardAdApiCount >= 1 ||
            bucket.adVastXmlCount >= 1 ||
            bucket.adGameOfferwallCount >= 1
    }

    private fun recordSharedIp(domain: String, ip: String) {
        val set = sharedIpToDomains.computeIfAbsent(ip) { ConcurrentHashMap.newKeySet() }
        set.add(domain)
    }

    fun sharedIpAdHostsCount(ip: String): Int {
        val set = sharedIpToDomains[ip] ?: return 0
        return set.count { candidateDomain ->
            RuleRepository.looksLikeAdSdkInfraDomain(candidateDomain)
        }
    }

    fun registerNxdomain(domain: String) {
        val normalized = domain.trim().lowercase().takeIf { it.isNotBlank() } ?: return
        nxdomainDomains.add(normalized)
    }

    fun isNxdomain(domain: String): Boolean {
        val normalized = domain.trim().lowercase()
        return nxdomainDomains.contains(normalized)
    }

    fun markCertPinningFailure(domain: String?) {
        val normalized = domain?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        val previous = certPinningFailureRecords[normalized] ?: 0
        certPinningFailureRecords[normalized] = previous + 1
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

    fun shouldStillRecordPinnedHost(domain: String): Boolean {
        val failureCount = certPinningFailureRecords[domain] ?: 0
        return failureCount >= PINNING_FAILURE_BOOST_THRESHOLD
    }

    fun clearCertPinningState(domain: String) {
        certPinningCooldown.remove(domain)
        certPinningFailureRecords.remove(domain)
    }

    fun recordDgaPattern(domain: String): Boolean {
        val normalized = domain.trim().lowercase().takeIf { it.isNotBlank() } ?: return false
        return DgaPatternDetector.looksLikeDga(normalized)
    }

    fun prune() {
        val now = System.currentTimeMillis()
        buckets.entries.removeIf { now - it.value.lastSeenAt > EXTENDED_WINDOW_MILLIS }
        certPinningCooldown.entries.removeIf { it.value <= now }
        if (nxdomainDomains.size > 4096) nxdomainDomains.clear()
        val activeIps = buckets.values.map { it.ip }.toHashSet()
        sharedIpToDomains.entries.removeIf { it.key !in activeIps }
    }

    private fun applySignal(bucket: Bucket, signal: Signal) {
        when (signal.type) {
            SignalType.DNS_UNKNOWN -> {
                bucket.unknownDnsCount += signal.confidence
                bucket.reasons += "dns-unknown"
            }
            SignalType.QUIC_DOWNGRADE -> {
                bucket.quicDowngradeCount += signal.confidence
                bucket.reasons += "quic-downgrade"
            }
            SignalType.TLS_FINGERPRINT -> {
                bucket.tlsFingerprintCount += signal.confidence
                if (signal.sniHost.isNullOrBlank()) bucket.reasons += "empty-sni"
                if (signal.alpnProtocols.any { it.equals("h3", true) || it.startsWith("h3-", true) }) bucket.reasons += "h3-alpn"
            }
            SignalType.HTTPDNS_HINT -> {
                bucket.httpDnsHintCount += signal.confidence
                bucket.reasons += "httpdns-hint"
            }
            SignalType.BODY_CONFIRMED_AD -> {
                bucket.bodyConfirmedCount += signal.confidence
                bucket.reasons += "mitm-body-confirmed"
            }
            SignalType.CERT_PINNING_FAILURE -> {
                bucket.certPinningFailureCount += signal.confidence
                bucket.reasons += "cert-pinning-failure"
            }
            SignalType.REWARD_AD_API -> {
                bucket.rewardAdApiCount += signal.confidence
                bucket.reasons += "reward-ad-api"
            }
            SignalType.AD_LOAD_REQUEST -> {
                bucket.adLoadRequestCount += signal.confidence
                bucket.reasons += "ad-load-request"
            }
            SignalType.AD_IMPRESSION_TRACK -> {
                bucket.adImpressionTrackCount += signal.confidence
                bucket.reasons += "ad-impression-track"
            }
            SignalType.AD_SDK_CONFIG -> {
                bucket.adSdkConfigCount += signal.confidence
                bucket.reasons += "ad-sdk-config"
            }
            SignalType.AD_INTERSTITIAL_TRIGGER -> {
                bucket.adInterstitialTriggerCount += signal.confidence
                bucket.reasons += "ad-interstitial-trigger"
            }
            SignalType.AD_SPLASH_PRELOAD -> {
                bucket.adSplashPreloadCount += signal.confidence
                bucket.reasons += "ad-splash-preload"
            }
            SignalType.AD_MEDIATION_WATERFALL -> {
                bucket.adMediationWaterfallCount += signal.confidence
                bucket.reasons += "ad-mediation-waterfall"
            }
            SignalType.AD_VIDEO_STREAM -> {
                bucket.adVideoStreamCount += signal.confidence
                bucket.reasons += "ad-video-stream"
            }
            SignalType.AD_FEED_INSERT -> {
                bucket.adFeedInsertCount += signal.confidence
                bucket.reasons += "ad-feed-insert"
            }
            SignalType.AD_OPENRTB_BID -> {
                bucket.adOpenRtbBidCount += signal.confidence
                bucket.reasons += "ad-openrtb-bid"
            }
            SignalType.AD_VAST_XML -> {
                bucket.adVastXmlCount += signal.confidence
                bucket.reasons += "ad-vast-xml"
            }
            SignalType.AD_GAME_OFFERWALL -> {
                bucket.adGameOfferwallCount += signal.confidence
                bucket.reasons += "ad-game-offerwall"
            }
            SignalType.AD_CONTENT_CLUSTER -> {
                bucket.adContentClusterCount += signal.confidence
                bucket.reasons += "ad-content-cluster"
            }
            SignalType.ECH_OUTER_SNI -> {
                bucket.echOuterSniCount += signal.confidence
                bucket.reasons += "ech-outer-sni-hidden"
            }
            SignalType.EMPTY_SNI_FLOW -> {
                bucket.emptySniCount += signal.confidence
                bucket.reasons += "empty-sni-tls"
            }
            SignalType.NXDOMAIN_CLUSTER -> {
                bucket.nxdomainClusterCount += signal.confidence
                bucket.reasons += "nxdomain-cluster"
            }
            SignalType.DGA_DOMAIN_HINT -> {
                bucket.dgaDomainHintCount += signal.confidence
                bucket.reasons += "dga-domain-hint"
            }
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
        score += bucket.rewardAdApiCount.coerceAtMost(2) * 3
        score += bucket.adLoadRequestCount.coerceAtMost(3) * 2
        score += bucket.adImpressionTrackCount.coerceAtMost(2) * 2
        score += bucket.adSdkConfigCount.coerceAtMost(2) * 2
        score += bucket.adInterstitialTriggerCount.coerceAtMost(2) * 3
        score += bucket.adSplashPreloadCount.coerceAtMost(1) * 2
        score += bucket.adMediationWaterfallCount.coerceAtMost(2) * 2
        score += bucket.adVideoStreamCount.coerceAtMost(2) * 3
        score += bucket.adFeedInsertCount.coerceAtMost(2) * 2
        score += bucket.adOpenRtbBidCount.coerceAtMost(1) * 4
        score += bucket.adVastXmlCount.coerceAtMost(1) * 3
        score += bucket.adGameOfferwallCount.coerceAtMost(2) * 3
        score += bucket.adContentClusterCount.coerceAtMost(2) * 2
        if (bucket.certPinningFailureCount >= PINNING_FAILURE_BOOST_THRESHOLD) {
            score += bucket.certPinningFailureCount.coerceAtMost(3) * 2
        }
        score += bucket.echOuterSniCount.coerceAtMost(2) * 2
        score += bucket.emptySniCount.coerceAtMost(2) * 2
        score += bucket.nxdomainClusterCount.coerceAtMost(3) * 2
        score += bucket.dgaDomainHintCount.coerceAtMost(2) * 3
        val sharedAdHosts = sharedIpAdHostsCount(bucket.ip)
        if (sharedAdHosts >= 2) score += sharedAdHosts.coerceAtMost(3)
        return score
    }

    private fun thresholdForApp(appName: String): Int {
        return if (RuleRepository.isAggressiveAdAppHint(appName)) BASE_THRESHOLD - 3 else BASE_THRESHOLD
    }

    private fun ttlForScore(score: Int): Long {
        return when {
            score >= 20 -> 30 * 60_000L
            score >= 15 -> 15 * 60_000L
            score >= 12 -> 10 * 60_000L
            score >= 10 -> 5 * 60_000L
            else -> 3 * 60_000L
        }
    }
}
