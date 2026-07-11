package com.HanFeng.core.network

import android.content.Context
import com.HanFeng.data.RuleRepository

/**
 * SNI 级拦截器：在 TLS ClientHello 阶段判断 SNI 是否命中广告规则，
 * 若命中则直接 RST 连接，无需 MITM 解密，效率最高且对证书绑定的 App 也有效。
 *
 * 判断标准（任一命中即拦截）：
 * 1. SNI 域名直接命中拦截规则（BlockRule）
 * 2. SNI 域名被识别为通用广告流量
 * 3. SNI 域名命中广告 SDK 基础设施域名
 *
 * 排除标准（任一命中即不拦截）：
 * 1. 社交核心域名（微信/QQ 等核心功能）
 * 2. 白名单域名
 * 3. 敏感认证域名
 * 4. 受保护域名（由调用方 VpnService 判断，因为该方法在 VpnService 内为 private）
 */
object SniInterceptor {

    data class SniBlockDecision(
        val shouldBlock: Boolean,
        val domain: String,
        val vendor: String,
        val reason: String
    )

    private const val SNI_CACHE_TTL_MS = 10_000L
    private const val SNI_CACHE_MAX = 256

    private data class CachedSniDecision(
        val decision: SniBlockDecision,
        val timestamp: Long
    )

    private val sniCache = object : LinkedHashMap<String, CachedSniDecision>(SNI_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSniDecision>?): Boolean {
            return size > SNI_CACHE_MAX
        }
    }
    private val sniCacheLock = Any()

    fun evaluate(
        context: Context,
        sniHost: String,
        appName: String,
        isProtectedDomain: Boolean = false
    ): SniBlockDecision {
        if (sniHost.isBlank()) {
            return SniBlockDecision(false, sniHost, "", "empty-sni")
        }

        synchronized(sniCacheLock) {
            sniCache[sniHost]?.let { cached ->
                if (System.currentTimeMillis() - cached.timestamp < SNI_CACHE_TTL_MS) {
                    return cached.decision
                }
            }
        }

        // 域名快速排除：社交核心 / 白名单（在这两步命中时不需要 vendor 分类）
        if (RuleRepository.isSocialCoreDomain(sniHost)) {
            return makeDecision(false, sniHost, "", "social-core").also { cacheDecision(sniHost, it) }
        }

        if (isProtectedDomain) {
            return makeDecision(false, sniHost, "", "protected-domain").also { cacheDecision(sniHost, it) }
        }

        if (RuleRepository.isWhitelistedDomain(sniHost)) {
            return makeDecision(false, sniHost, "", "whitelisted").also { cacheDecision(sniHost, it) }
        }

        if (RuleRepository.isSensitiveAuthDomain(sniHost)) {
            return makeDecision(false, sniHost, "", "sensitive-auth").also { cacheDecision(sniHost, it) }
        }

        val vendor = RuleRepository.classifyVendor(context, sniHost)

        // 命中通用广告流量
        if (RuleRepository.shouldTreatAsGeneralAdTraffic(sniHost, vendor, appName)) {
            return makeDecision(true, sniHost, vendor, "general-ad-traffic").also { cacheDecision(sniHost, it) }
        }

        // 命中广告 SDK 基础设施域名
        if (RuleRepository.looksLikeAdSdkInfraDomain(sniHost, vendor)) {
            return makeDecision(true, sniHost, vendor, "ad-sdk-infra").also { cacheDecision(sniHost, it) }
        }

        return makeDecision(false, sniHost, vendor, "pass").also { cacheDecision(sniHost, it) }
    }

    private fun makeDecision(shouldBlock: Boolean, domain: String, vendor: String, reason: String): SniBlockDecision {
        return SniBlockDecision(shouldBlock, domain, vendor, reason)
    }

    private fun cacheDecision(sniHost: String, decision: SniBlockDecision) {
        synchronized(sniCacheLock) {
            sniCache[sniHost] = CachedSniDecision(decision, System.currentTimeMillis())
        }
    }
}
