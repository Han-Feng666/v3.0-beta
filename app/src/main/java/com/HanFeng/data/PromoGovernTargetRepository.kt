package com.HanFeng.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object PromoGovernTargetRepository {
    data class DiscoveryResult(
        val targets: List<PromoGovernTarget>,
        val installedCount: Int,
        val eligibleCount: Int,
        val excludedPureSystemCount: Int,
        val includedByPresetCount: Int,
        val includedByWellKnownCount: Int,
        val scannedCount: Int,
        val categoryCounts: Map<String, Int>
    )

    private enum class NotificationRiskLevel { HIGH, MEDIUM, LOW }

    fun discover(context: Context): DiscoveryResult {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(packageQueryFlags())
        val selfPackage = context.packageName
        val presetPackages = ShizukuAdControlCatalog.allPresets().mapTo(linkedSetOf()) { it.packageName }
        var excludedPureSystem = 0
        var includedByPreset = 0
        var includedByWellKnown = 0
        val eligibleApps = installedApps.filter { appInfo ->
            if (appInfo.packageName == selfPackage) return@filter false
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem) return@filter true
            if (appInfo.packageName in presetPackages) {
                includedByPreset++
                return@filter true
            }
            val label = pm.getApplicationLabel(appInfo).toString()
            if (isWellKnownThirdPartyPromoApp(appInfo.packageName, label)) {
                includedByWellKnown++
                return@filter true
            }
            if (isLikelyOemPromoPackage(appInfo.packageName, label)) {
                includedByWellKnown++
                return@filter true
            }
            excludedPureSystem++
            false
        }
        var scanned = 0
        val categoryCounts = linkedMapOf<String, Int>()
        val strictTargets = eligibleApps.asSequence()
            .mapNotNull { appInfo ->
                scanned++
                buildTarget(context, appInfo)?.also { target ->
                    categoryCounts[target.category] = (categoryCounts[target.category] ?: 0) + 1
                }
            }
            .sortedWith(compareBy<PromoGovernTarget> { it.category }.thenBy { it.title })
            .toList()
        val discoveredTargets = strictTargets.ifEmpty {
            val fallbackPool = eligibleApps.ifEmpty {
                installedApps.filter { appInfo ->
                    appInfo.packageName != selfPackage && pm.getLaunchIntentForPackage(appInfo.packageName) != null
                }
            }
            fallbackPool.asSequence()
                .sortedBy { appInfo -> pm.getApplicationLabel(appInfo).toString() }
                .take(80)
                .mapNotNull { appInfo -> buildFallbackTarget(context, appInfo) }
                .toList()
                .also { fallbackTargets ->
                    if (fallbackTargets.isNotEmpty()) {
                        categoryCounts["可选治理"] = fallbackTargets.size
                    }
                }
        }
        val targets = includeLatestSnapshotTargetIfMissing(context, discoveredTargets)
        return DiscoveryResult(
            targets = targets,
            installedCount = installedApps.size,
            eligibleCount = eligibleApps.size,
            excludedPureSystemCount = excludedPureSystem,
            includedByPresetCount = includedByPreset,
            includedByWellKnownCount = includedByWellKnown,
            scannedCount = scanned,
            categoryCounts = categoryCounts
        )
    }

    private fun includeLatestSnapshotTargetIfMissing(
        context: Context,
        targets: List<PromoGovernTarget>
    ): List<PromoGovernTarget> {
        val snapshot = PromoGovernSnapshotRepository.latest(context) ?: return targets
        if (targets.any { it.packageName == snapshot.packageName }) return targets
        val status = ShizukuAdControlRepository.queryPackageStatus(context, snapshot.packageName)
        if (!status.installed) return targets
        val snapshotTarget = PromoGovernTarget(
            packageName = snapshot.packageName,
            title = snapshot.title,
            category = "最近治理",
            description = "这是最近被治理过的 App。即使冻结后桌面图标消失，也可以在这里解冻并恢复暂停状态和推送广告权限。",
            sourceLabel = "最近治理记录",
            systemApp = false,
            detectionTags = listOf("recent-governed", "restore-entry"),
            relatedPresets = ShizukuAdControlCatalog.allPresets().filter { it.packageName == snapshot.packageName },
            packageStatus = status
        )
        return listOf(snapshotTarget) + targets
    }

    private fun packageQueryFlags(): Int {
        return PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
    }

    private fun buildFallbackTarget(context: Context, appInfo: ApplicationInfo): PromoGovernTarget? {
        val pm = context.packageManager
        val packageName = appInfo.packageName
        val label = pm.getApplicationLabel(appInfo).toString().ifBlank { packageName }
        val status = ShizukuAdControlRepository.queryPackageStatus(context, packageName)
        if (!status.installed) return null
        return PromoGovernTarget(
            packageName = packageName,
            title = label,
            category = "可选治理",
            description = "未命中内置推广规则，但这是可启动的第三方 App。可按需关闭推送广告、暂停或进入组件治理，冻结前请确认不会影响正常使用。",
            sourceLabel = "第三方 App（手动确认）",
            systemApp = false,
            detectionTags = listOf("fallback", "launchable", "manual-confirm"),
            relatedPresets = emptyList(),
            packageStatus = status
        )
    }

    private fun buildTarget(context: Context, appInfo: ApplicationInfo): PromoGovernTarget? {
        val pm = context.packageManager
        val packageName = appInfo.packageName
        val label = pm.getApplicationLabel(appInfo).toString().ifBlank { packageName }
        val lowerLabel = label.lowercase()
        val lowerPackage = packageName.lowercase()
        val matchedPresets = ShizukuAdControlCatalog.allPresets().filter { it.packageName == packageName }
        val notificationRisk = assessNotificationRisk(lowerLabel, lowerPackage)
        val componentCandidates = PromoGovernComponentRepository.discoverCandidates(context, packageName)
        val matchedWellKnownApp = isWellKnownThirdPartyPromoApp(packageName, label)
        val looksLikePromo = looksLikeThirdPartyPromoApp(lowerLabel, lowerPackage)
        val hasPromoEvidence = matchedPresets.isNotEmpty() || matchedWellKnownApp || looksLikePromo || componentCandidates.isNotEmpty() || notificationRisk != NotificationRiskLevel.LOW
        if (!hasPromoEvidence) return null
        val status = ShizukuAdControlRepository.queryPackageStatus(context, packageName)
        if (!status.installed) return null
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val tags = buildDetectionTags(
            appInfo = appInfo,
            hasLauncher = pm.getLaunchIntentForPackage(packageName) != null,
            componentCandidates = componentCandidates,
            notificationRisk = notificationRisk,
            matchedWellKnownApp = matchedWellKnownApp,
            matchedPreset = matchedPresets.isNotEmpty()
        )
        return PromoGovernTarget(
            packageName = packageName,
            title = matchedPresets.firstOrNull()?.title ?: label,
            category = matchedPresets.firstOrNull()?.category ?: inferPromoCategory(lowerLabel, lowerPackage),
            description = matchedPresets.firstOrNull()?.description ?: buildDescription(notificationRisk, componentCandidates),
            sourceLabel = if (isSystem) "系统预装第三方 App" else "已安装第三方 App",
            systemApp = isSystem,
            detectionTags = tags,
            relatedPresets = matchedPresets,
            packageStatus = status
        )
    }

    private fun isWellKnownThirdPartyPromoApp(packageName: String, label: String): Boolean {
        val lowerPackage = packageName.lowercase()
        val lowerLabel = label.lowercase()
        val wellKnownPrefixes = listOf(
            "com.taobao.", "com.tmall.", "com.alibaba.", "com.alipay.",
            "com.meituan.", "com.sankuai.", "com.dianping.",
            "com.jingdong.", "com.jd.", "com.jd.lib.", "com.jd.my jd.", "com.jingdong.purplecat.",
            "com.ss.android.ugc.aweme", "com.ss.android.article.news", "com.ss.android.article.lite",
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.qqlive", "com.tencent.qqmusic",
            "com.sina.weibo", "com.eleme.", "com.ctrip.", "com.baidu.searchbox",
            "com.dragon.read", "com.qidian.", "com.UCMobile", "com.uc.", "com.quark.",
            "com.heytap.market", "com.heytap.themestore", "com.huawei.appmarket",
            "com.vipshop", "com.vip.", "com.sec.android.app.kidstoys",
            "com.fanqie", "com.shuqi", "com.xiaoshuo", "com.reading",
            "com.xiaomi.market", "com.huawei.appgallery", "com.oppo.market",
            "com.netease.cloudmusic", "music.163.com"
        )
        val labelHints = listOf(
            "淘宝", "天猫", "美团", "大众点评", "京东", "拼多多", "唯品会",
            "今日头条", "头条", "抖音", "快手", "微博", "微信", "qq", "支付宝",
            "百度地图", "高德地图", "美团外卖", "饿了么", "携程", "飞猪", "百度网盘",
            "wps", "网易云音乐", "番茄小说", "起点", "uc 浏览器", "夸克", "喜马拉雅",
            "书旗小说", "七猫小说", "掌阅", "咪咕阅读", "qq 阅读", "微信读书",
            "得物", "小红书", "豆瓣", "知乎", "b 站", "哔哩哔哩", "汽水音乐",
            "应用商店", "软件商店", "游戏中心", "手机商店"
        )
        return wellKnownPrefixes.any { lowerPackage.startsWith(it) } || labelHints.any { lowerLabel.contains(it) }
    }

    private fun isLikelyOemPromoPackage(packageName: String, label: String): Boolean {
        val lowerPackage = packageName.lowercase()
        val lowerLabel = label.lowercase()
        val vendorHints = listOf("miui", "xiaomi", "mipicks", "heytap", "oppo", "vivo", "bbk", "huawei", "honor", "samsung", "meizu", "flyme", "oneplus", "oxygenos", "hydrogenos")
        val promoPackageHints = listOf(
            "appstore", "market", "browser", "theme", "wallpaper", "pictorial", "content", "reader",
            "gamecenter", "quicksearch", "search", "assistant", "feed", "recommend", "ad", "ads", "systemad",
            "video", "music", "weather", "calendar", "clock", "calculator", "filemanager", "recorder"
        )
        val promoLabelHints = listOf("应用商店", "软件商店", "浏览器", "主题", "壁纸", "画报", "内容", "阅读", "游戏中心", "搜索", "助手", "推荐", "广告",
            "视频", "音乐", "天气", "日历", "时钟", "计算器", "文件管理", "录音机", "指南针", "手电筒", "锁屏", "画报", "智能助理", "负一屏")
        return (vendorHints.any(lowerPackage::contains) && promoPackageHints.any(lowerPackage::contains)) ||
            promoLabelHints.any(lowerLabel::contains)
    }

    private fun looksLikeThirdPartyPromoApp(lowerLabel: String, lowerPackage: String): Boolean {
        val knownThirdPartyPrefixes = listOf(
            "com.taobao.", "com.tmall.", "com.alibaba.", "com.jingdong.", "com.jd.",
            "com.meituan.", "com.sankuai.", "com.dianping.", "com.ss.android.", "com.iesdouyin.",
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.qqlive", "com.smile.gifmaker",
            "com.kuaishou.", "tv.danmaku.bili", "com.sina.weibo", "com.dragon.read",
            "com.eg.android.", "com.ctrip.", "com.qunar.", "com.tongcheng.", "com.netease.", "com.163.",
            "com.qidian.", "com.shuqi", "com.fanqie", "com.xiaoshuo.", "com.reading.",
            "com.vipshop.", "com.xiaomi.", "com.huawei.", "com.oppo.", "com.vivo.",
            "com.google.android.", "com.facebook.", "com.instagram.", "com.twitter.",
            "com.netflix.", "com.spotify.", "com.amazon.", "com.google.play.",
            "com.moji.", "com.moji.android", "com.zhangshang", "com.tianqi", "com.android.browser",
            "com.android.calendar", "com.android.thememanager", "com.android.deskclock"
        )
        val labelHints = listOf(
            "应用商店", "软件商店", "浏览器", "阅读", "小说", "短剧", "视频", "资讯", "新闻",
            "直播", "漫画", "音乐", "游戏中心", "内容中心", "推荐", "精选", "热点", "发现",
            "赚钱", "福利", "红包", "免费", "活动", "优惠", "折扣", "秒杀", "领券",
            "淘宝", "天猫", "美团", "京东", "拼多多", "今日头条", "抖音", "快手", "微博",
            "支付宝", "饿了么", "携程", "百度网盘", "网易云音乐", "喜马拉雅",
            "书旗", "七猫", "掌阅", "咪咕", "qq 阅读", "微信读书", "番茄小说", "起点",
            "唯品会", "得物", "小红书", "豆瓣", "知乎", "b 站", "哔哩哔哩", "汽水音乐",
            "天气", "日历", "时钟", "闹钟", "计算器", "文件管理", "录音机", "指南针"
        )
        val packageHints = listOf(
            "appstore", "market", "browser", "reader", "novel", "book", "video", "news",
            "gamecenter", "content", "promo", "recommend", "discover", "reward", "benefit", "ad",
            "marketing", "advert", "promotion", "mall", "shop", "activity", "sale", "discount",
            "coupon", "welfare", "lottery", "task", "jd.com", "jingdong", "sankuai", "meituan",
            "taobao", "tmall", "alibaba", "toutiao", "douyin", "bytedance", "kuaishou", "bilibili",
            "xiaomi", "huawei", "oppo", "vivo", "samsung", "sony", "lg", "motorola",
            "moji", "tianqi", "weather", "calendar", "clock", "alarm", "calculator", "filemanager",
            "recorder", "compass", "music", "player", "gallery", "photo", "camera"
        )
        val oemHints = listOf("heytap", "coloros", "realme", "vivo", "oppo", "miui", "xiaomi", "huawei", "honor", "samsung")
        val distributionHints = listOf("contentcenter", "contentservice", "feed", "recommend", "discovery", "gamecenter", "appstore", "market", "adsdk", "union", "push", "marketing", "promo")
        val knownThirdParty = knownThirdPartyPrefixes.any { lowerPackage.startsWith(it) }
        val labelHighConfidence = labelHints.any { lowerLabel.contains(it) }
        val packageHighConfidence = packageHints.any { lowerPackage.contains(it) }
        val oemDistributionMatched = oemHints.any { lowerPackage.contains(it) } && distributionHints.any { lowerPackage.contains(it) }
        return knownThirdParty || (labelHighConfidence && oemDistributionMatched) || (packageHighConfidence && !lowerPackage.startsWith("com.android.") && !lowerPackage.contains("aosp"))
    }

    private fun inferPromoCategory(lowerLabel: String, lowerPackage: String): String {
        return when {
            listOf("浏览器", "browser").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "浏览器推荐"
            listOf("壁纸", "主题", "wallpaper", "theme").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "主题壁纸"
            listOf("锁屏", "lockscreen").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "锁屏推荐"
            listOf("小说", "阅读", "novel", "reader", "book").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "阅读推广"
            listOf("短剧", "视频", "video").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "视频推广"
            listOf("资讯", "新闻", "热点", "news", "hot", "头条").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "资讯推荐"
            listOf("淘宝", "京东", "美团", "拼多多", "商城", "mall", "jingdong", "taobao", "meituan").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "电商推广"
            listOf("饿了么", "外卖", "eleme").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "外卖推广"
            listOf("应用商店", "软件商店", "market", "appstore").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "系统推广"
            else -> "内容推荐"
        }
    }

    private fun assessNotificationRisk(lowerLabel: String, lowerPackage: String): NotificationRiskLevel {
        val highRiskKeywords = listOf("资讯", "新闻", "热点", "推荐", "精选", "发现", "头条", "news", "hot", "feed", "recommend", "discover", "toutiao")
        val activityKeywords = listOf("活动", "优惠", "折扣", "秒杀", "特卖", "团购", "签到", "任务", "领奖", "抽奖", "福利", "红包", "赚钱", "coupon", "bonus", "welfare", "lottery", "promotion")
        val mediumRiskKeywords = listOf("应用商店", "软件商店", "浏览器", "视频", "短剧", "直播", "漫画", "游戏中心", "market", "browser", "video", "gamecenter",
            "小说", "阅读", "读书", "书屋", "看书", "追书", "novel", "reader", "book", "read", "shuqi", "fanqie", "qidian", "qimao")
        return when {
            highRiskKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> NotificationRiskLevel.HIGH
            activityKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> NotificationRiskLevel.HIGH
            mediumRiskKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> NotificationRiskLevel.MEDIUM
            else -> NotificationRiskLevel.LOW
        }
    }

    private fun buildDetectionTags(
        appInfo: ApplicationInfo,
        hasLauncher: Boolean,
        componentCandidates: List<PromoComponentCandidate>,
        notificationRisk: NotificationRiskLevel,
        matchedWellKnownApp: Boolean,
        matchedPreset: Boolean
    ): List<String> {
        val tags = mutableListOf<String>()
        if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) tags += "系统预装"
        if (hasLauncher) tags += "有启动入口"
        if (matchedPreset) tags += "预设目录命中"
        if (matchedWellKnownApp) tags += "知名第三方"
        when (notificationRisk) {
            NotificationRiskLevel.HIGH -> tags += "高通知风险"
            NotificationRiskLevel.MEDIUM -> tags += "中通知风险"
            NotificationRiskLevel.LOW -> Unit
        }
        val componentGroups = componentCandidates.map { it.groupLabel }.distinct().take(3)
        if (componentGroups.isNotEmpty()) {
            tags += "组件命中:${componentGroups.joinToString("/")}"
        }
        return tags.distinct()
    }

    private fun buildDescription(notificationRisk: NotificationRiskLevel, componentCandidates: List<PromoComponentCandidate>): String {
        val base = when (notificationRisk) {
            NotificationRiskLevel.HIGH -> "适合治理该已安装推广 App 的通知广告、推荐流、营销入口和关联推广行为。建议关闭通知权限。"
            NotificationRiskLevel.MEDIUM -> "适合治理该已安装推广 App 的通知广告、推荐流、营销入口和关联推广行为。"
            NotificationRiskLevel.LOW -> "适合治理该已安装推广 App 的推荐流、营销入口和关联推广行为。"
        }
        val componentSummary = componentCandidates.map { it.groupLabel }.distinct().take(2)
        return if (componentSummary.isEmpty()) {
            base
        } else {
            "$base 已识别${componentCandidates.size}个疑似推广组件：${componentSummary.joinToString("、")}。"
        }
    }
}
