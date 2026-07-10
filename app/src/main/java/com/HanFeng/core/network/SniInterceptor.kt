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

    fun evaluate(
        context: Context,
        sniHost: String,
        appName: String,
        isProtectedDomain: Boolean = false
    ): SniBlockDecision {
        if (sniHost.isBlank()) {
            return SniBlockDecision(false, sniHost, "", "empty-sni")
        }

        // 排除：社交核心
        if (RuleRepository.isSocialCoreDomain(sniHost)) {
            return SniBlockDecision(false, sniHost, "", "social-core")
        }

        // 排除：受保护域名（由 VpnService 传入）
        if (isProtectedDomain) {
            return SniBlockDecision(false, sniHost, "", "protected-domain")
        }

        // 排除：白名单
        if (RuleRepository.isWhitelistedDomain(sniHost)) {
            return SniBlockDecision(false, sniHost, "", "whitelisted")
        }

        // 排除：敏感认证域名
        if (RuleRepository.isSensitiveAuthDomain(sniHost)) {
            return SniBlockDecision(false, sniHost, "", "sensitive-auth")
        }

        val vendor = RuleRepository.classifyVendor(context, sniHost)

        // 命中通用广告流量
        if (RuleRepository.shouldTreatAsGeneralAdTraffic(sniHost, vendor, appName)) {
            return SniBlockDecision(
                shouldBlock = true,
                domain = sniHost,
                vendor = vendor,
                reason = "general-ad-traffic"
            )
        }

        // 命中广告 SDK 基础设施域名
        if (RuleRepository.looksLikeAdSdkInfraDomain(sniHost, vendor)) {
            return SniBlockDecision(
                shouldBlock = true,
                domain = sniHost,
                vendor = vendor,
                reason = "ad-sdk-infra"
            )
        }

        return SniBlockDecision(false, sniHost, vendor, "pass")
    }
}
