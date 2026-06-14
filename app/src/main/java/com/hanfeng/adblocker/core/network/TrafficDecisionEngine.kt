package com.HanFeng.core.network

import android.system.OsConstants
import com.HanFeng.data.RuleRepository
import com.HanFeng.model.PacketInfo

object TrafficDecisionEngine {
    data class QuicBlockInput(
        val packet: PacketInfo,
        val payloadLooksLikeQuic: Boolean,
        val localProxyTarget: Boolean,
        val domain: String?,
        val appName: String?,
        val vendor: String?,
        val matchedRule: Any?,
        val bypassReason: String?,
        val httpDecryptEnabled: Boolean,
        val hasHttpsTarget: Boolean,
        val globalMitmFullCapture: Boolean = false
    )

    data class HttpDecryptBlockInput(
        val packet: PacketInfo,
        val domain: String?,
        val appName: String?,
        val vendor: String?,
        val matchedRule: Any?
    )

    data class LocalProxyRouteInput(
        val packet: PacketInfo,
        val configHost: String,
        val configPort: Int?,
        val configEnabled: Boolean,
        val localProxyReachable: Boolean?,
        val targetPackages: Set<String>,
        val cachedFlowDecision: Boolean?,
        val cachedSourcePortDecision: Boolean?,
        val destinationIp: String?,
        val appName: String?,
        val belongsToTargetApp: Boolean,
        val belongsToTargetUid: Boolean
    )

    data class EncryptedDnsDirectInput(
        val destinationIp: String,
        val port: Int,
        val appName: String,
        val trackedTargetDomain: String?
    )

    data class EncryptedDnsClientHelloInput(
        val sniHost: String,
        val alpnProtocols: List<String>,
        val destinationIp: String
    )

    data class HttpDecryptDecision(
        val blocked: Boolean,
        val reason: String
    )

    data class QuicDecision(
        val blocked: Boolean,
        val reason: String?
    )

    fun shouldBlockQuicFlow(input: QuicBlockInput): QuicDecision {
        if (input.packet.protocol != OsConstants.IPPROTO_UDP || input.packet.destinationPort != 443) {
            return QuicDecision(blocked = false, reason = null)
        }
        if (input.packet.payload.isEmpty()) return QuicDecision(blocked = false, reason = null)
        if (!input.payloadLooksLikeQuic) return QuicDecision(blocked = false, reason = null)
        if (input.localProxyTarget) {
            return QuicDecision(blocked = true, reason = "local-proxy-force-tcp")
        }
        val domain = input.domain?.trim().orEmpty()
        val appName = input.appName.orEmpty()
        val vendor = input.vendor.orEmpty()
        if (domain.isBlank()) {
            if (input.globalMitmFullCapture && input.bypassReason == null) {
                if (RuleRepository.isAggressiveAdAppHint(appName) || RuleRepository.isCommunityAppHint(appName)) {
                    return QuicDecision(blocked = true, reason = "global-mitm-force-tcp")
                }
            }
            return QuicDecision(blocked = false, reason = null)
        }
        if (RuleRepository.isWhitelistedDomain(domain)) return QuicDecision(blocked = false, reason = null)
        if (RuleRepository.isSensitiveAuthDomain(domain)) return QuicDecision(blocked = false, reason = null)
        if (RuleRepository.shouldProtectMediaTraffic(domain) || RuleRepository.shouldProtectBusinessTraffic(domain)) {
            return QuicDecision(blocked = false, reason = null)
        }
        if (RuleRepository.isBypassProtectionDomain(domain)) {
            return QuicDecision(blocked = true, reason = "encrypted-dns-force-tcp")
        }
        if (RuleRepository.isGameCoreDomain(domain) || RuleRepository.isSocialCoreDomain(domain)) {
            return QuicDecision(blocked = false, reason = null)
        }
        if (RuleRepository.shouldForceNovelQuicBlock(domain, appName, vendor)) {
            return QuicDecision(blocked = true, reason = "novel-force-quic-block")
        }
        if (RuleRepository.shouldTreatAsGeneralAdTraffic(domain, vendor, appName)) {
            return QuicDecision(blocked = true, reason = "general-ad-traffic")
        }
        if (input.globalMitmFullCapture && input.hasHttpsTarget && input.bypassReason == null) {
            if (RuleRepository.isAggressiveAdAppHint(appName) || RuleRepository.isCommunityAppHint(appName)) {
                return QuicDecision(blocked = true, reason = "global-mitm-force-tcp")
            }
        }
        val shouldForceTcpFallback = input.httpDecryptEnabled && input.hasHttpsTarget && input.matchedRule != null && input.bypassReason == null
        if (input.matchedRule == null && !shouldForceTcpFallback) {
            return QuicDecision(blocked = false, reason = null)
        }
        return QuicDecision(
            blocked = true,
            reason = if (input.matchedRule != null) "matched-rule" else "force-tcp-fallback"
        )
    }

    fun shouldBlockHttpDecryptConnection(
        input: HttpDecryptBlockInput,
        shouldTreatAsTrackedAdTarget: (domain: String, appName: String, vendor: String, matchedRule: Any?) -> Boolean
    ): HttpDecryptDecision {
        if (input.packet.protocol != OsConstants.IPPROTO_TCP || input.packet.destinationPort != 80) {
            return HttpDecryptDecision(blocked = false, reason = "not-http")
        }
        val domain = input.domain?.trim().orEmpty()
        if (domain.isBlank()) return HttpDecryptDecision(blocked = false, reason = "empty-domain")
        if (RuleRepository.isSensitiveAuthDomain(domain)) return HttpDecryptDecision(blocked = false, reason = "sensitive-auth-domain")
        if (RuleRepository.isSocialCoreDomain(domain)) return HttpDecryptDecision(blocked = false, reason = "social-core-domain")
        if (RuleRepository.shouldProtectMediaTraffic(domain) || RuleRepository.shouldProtectBusinessTraffic(domain)) {
            return HttpDecryptDecision(blocked = false, reason = "protected-domain")
        }
        val appName = input.appName.orEmpty()
        val vendor = input.vendor.orEmpty()
        val blocked = shouldTreatAsTrackedAdTarget(domain, appName, vendor, input.matchedRule)
        return HttpDecryptDecision(
            blocked = blocked,
            reason = if (blocked) "tracked-ad-target" else "pass"
        )
    }

    fun shouldRouteViaLocalProxy(input: LocalProxyRouteInput): Boolean {
        if (input.packet.protocol != OsConstants.IPPROTO_TCP) return false
        if (!input.configEnabled) return false
        if (input.localProxyReachable != true) return false
        val port = input.configPort ?: return false
        if (port !in 1..65535) return false
        
        if (input.targetPackages.isEmpty()) {
            return false
        }
        
        val destinationIp = input.destinationIp ?: return false
        if (isLocalProxyEndpoint(destinationIp, input.packet.destinationPort, input.configHost, port)) {
            return false
        }
        val appMatched = input.appName?.let {
            NetworkModeCoordinator.belongsToLocalProxyTarget(it, input.targetPackages)
        } == true
        return appMatched || input.belongsToTargetApp || input.belongsToTargetUid
    }

    fun shouldBlockEncryptedDnsDirectFlow(
        input: EncryptedDnsDirectInput,
        isCurrentDnsEndpoint: Boolean,
        isKnownPublicDnsEndpoint: Boolean
    ): Boolean {
        val trackedTargetBypassProtection = input.trackedTargetDomain
            ?.let(RuleRepository::isBypassProtectionDomain) == true
        if (!isCurrentDnsEndpoint && !isKnownPublicDnsEndpoint) {
            if (input.port != 443 && input.port != 853 && input.port != 784 && input.port != 8853) return false
            if (!trackedTargetBypassProtection) return false
        }
        return when (input.port) {
            853 -> true
            784, 8853 -> isKnownPublicDnsEndpoint
            443 -> isKnownPublicDnsEndpoint || trackedTargetBypassProtection
            else -> false
        }
    }

    fun shouldBlockEncryptedDnsClientHello(
        input: EncryptedDnsClientHelloInput,
        isCurrentDnsEndpoint: Boolean,
        isKnownPublicDnsEndpoint: Boolean
    ): Boolean {
        val normalizedSni = input.sniHost.lowercase()
        val looksLikeEncryptedDnsHost = RuleRepository.isBypassProtectionDomain(normalizedSni)
        val offersHttp2OrHttp3 = input.alpnProtocols.any { protocol ->
            protocol.equals("h2", ignoreCase = true) ||
                protocol.equals("h3", ignoreCase = true) ||
                protocol.startsWith("h3-", ignoreCase = true)
        }
        val knownDnsEndpoint = isKnownPublicDnsEndpoint || isCurrentDnsEndpoint
        if (!looksLikeEncryptedDnsHost) return false
        if (!offersHttp2OrHttp3 && !knownDnsEndpoint) return false
        return true
    }

    fun isLocalProxyEndpoint(host: String, port: Int, configHost: String, configPort: Int?): Boolean {
        return port == configPort && (host == configHost || host == "127.0.0.1" || host == "::1")
    }

    fun isLocalLoopOrProxyEndpoint(host: String, port: Int, configHost: String, configPort: Int?): Boolean {
        if (host == "127.0.0.1" || host == "::1") return true
        return isLocalProxyEndpoint(host, port, configHost, configPort)
    }
}
