package com.HanFeng.core.network

import com.HanFeng.data.RuleRepository

object MitmAppFullCaptureTargetSupport {
    private val protectedPackageTokens = listOf(
        "android", "systemui", "settings", "permissioncontroller", "packageinstaller",
        "browser", "chrome", "webview", "mibrowser", "heytapbrowser", "huawei.browser",
        "wallet", "pay", "alipay", "wechat", "tencent.mm", "qq", "bank", "unionpay",
        "market", "installer", "download", "push", "gms", "google", "miui"
    )

    private val protectedLabelTokens = listOf(
        "系统", "设置", "权限", "安装", "浏览器", "钱包", "支付", "银行", "微信", "qq", "应用商店", "下载", "推送"
    )

    private val managedPromoAllowedCategories = setOf(
        "负一屏推荐", "内容推荐", "锁屏推荐", "主题壁纸", "系统应用推荐"
    )

    fun isSafeTarget(
        packageName: String,
        label: String?,
        isSystemApp: Boolean,
        managedPromoCategory: String? = null
    ): Boolean {
        val normalizedPackage = packageName.trim().lowercase()
        if (normalizedPackage.isBlank()) return false

        val category = managedPromoCategory?.trim().orEmpty()
        val allowManagedSystemTarget = category in managedPromoAllowedCategories
        if (isSystemApp && !allowManagedSystemTarget) return false

        val normalizedLabel = label?.trim()?.lowercase().orEmpty()
        if (normalizedPackage.contains("coolapk") || normalizedLabel.contains("酷安")) return true

        if (allowManagedSystemTarget) return true

        if (protectedPackageTokens.any(normalizedPackage::contains)) return false
        if (protectedLabelTokens.any(normalizedLabel::contains)) return false

        val identity = listOf(normalizedPackage, normalizedLabel).joinToString(" ")
        if (RuleRepository.isNovelAppHint(identity)) return false
        return RuleRepository.isCommunityAppHint(identity) && !looksLikeSocialOrPayment(identity)
    }

    private fun looksLikeSocialOrPayment(identity: String): Boolean {
        val tokens = listOf(
            "wechat", "weixin", "tencent.mm", "qq", "weibo", "xiaohongshu", "rednote",
            "alipay", "wallet", "pay", "bank", "微信", "微博", "小红书", "支付宝", "钱包", "支付", "银行"
        )
        return tokens.any(identity::contains)
    }
}
