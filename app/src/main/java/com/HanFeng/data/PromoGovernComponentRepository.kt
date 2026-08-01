package com.HanFeng.data

import android.content.Context
import android.content.pm.PackageManager

object PromoGovernComponentRepository {
    fun discoverActivities(context: Context, packageName: String): List<PromoComponentCandidate> {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                componentQueryFlags(PackageManager.GET_ACTIVITIES)
            )
        }.getOrNull() ?: return emptyList()

        return packageInfo.activities.orEmpty()
            .mapNotNull { info ->
                val fullName = info.name.orEmpty()
                if (fullName.isBlank()) return@mapNotNull null
                buildCandidate(
                    packageName = packageName,
                    fullName = fullName,
                    shortName = fullName.substringAfterLast('.'),
                    typeLabel = "Activity",
                    enabled = info.isEnabled,
                    score = promoComponentScore(fullName.lowercase(), "Activity")
                )
            }
            .distinctBy { it.componentName }
            .sortedBy { it.shortName }
    }

    fun discoverCandidates(context: Context, packageName: String): List<PromoComponentCandidate> {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                componentQueryFlags(PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES)
            )
        }.getOrNull() ?: return emptyList()

        val candidates = mutableListOf<PromoComponentCandidate>()
        packageInfo.activities.orEmpty().forEach { info ->
            val fullName = info.name.orEmpty()
            val shortName = fullName.substringAfterLast('.')
            val score = promoComponentScore(fullName.lowercase(), "Activity")
            if (score > 0) candidates += buildCandidate(packageName, fullName, shortName, "Activity", info.isEnabled, score)
        }
        packageInfo.receivers.orEmpty().forEach { info ->
            val fullName = info.name.orEmpty()
            val shortName = fullName.substringAfterLast('.')
            val score = promoComponentScore(fullName.lowercase(), "Receiver")
            if (score > 0) candidates += buildCandidate(packageName, fullName, shortName, "Receiver", info.isEnabled, score)
        }
        packageInfo.services.orEmpty().forEach { info ->
            val fullName = info.name.orEmpty()
            val shortName = fullName.substringAfterLast('.')
            val score = promoComponentScore(fullName.lowercase(), "Service")
            if (score > 0) candidates += buildCandidate(packageName, fullName, shortName, "Service", info.isEnabled, score)
        }
        return candidates.sortedWith(compareByDescending<PromoComponentCandidate> { it.score }.thenBy { it.shortName }).take(20)
    }

    private fun componentQueryFlags(baseFlags: Int): Int {
        return baseFlags or PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
    }

    private fun buildCandidate(
        packageName: String,
        fullName: String,
        shortName: String,
        typeLabel: String,
        enabled: Boolean,
        score: Int
    ): PromoComponentCandidate {
        val lowerName = fullName.lowercase()
        val groupLabel = when {
            // 优先判定第三方 SDK 组件: 类名前缀不属于应用包名空间, 多为外包集成进来的
            // SDK (如穿山甲 com.bytedance.sdk.openadsdk.* / 优量汇 com.qq.e.ads.* / 个推 com.igexin.* /
            // 友盟 com.umeng.* / 极光 com.jpush.* / Bugly com.tencent.bugly.* / 小米 push com.xiaomi.mipush.*)
            !fullName.startsWith(packageName) && isLikelySdkNamespace(fullName) -> "${sdkVendorShort(fullName)} SDK 组件"
            typeLabel == "Activity" && listOf("splash", "startup", "launchad", "interstitial", "bootad", "openad", "opening").any(lowerName::contains) -> "启动广告 Activity"
            typeLabel == "Activity" && listOf("feed", "recommend", "discover", "hot", "operation", "market", "promo", "campaign", "event", "activity", "special", "topic").any(lowerName::contains) -> "推荐流/活动页 Activity"
            typeLabel == "Activity" && listOf("launcher", "mainactivity", "homeactivity", "main", "desktop", "homescreen").any(lowerName::contains) -> "主入口 Activity"
            typeLabel == "Activity" && listOf("login", "account", "passport", "auth", "oauth", "signin", "register", "signup").any(lowerName::contains) -> "账号登录 Activity"
            typeLabel == "Activity" && listOf("setting", "permission", "privacy", "security", "config", "preference", "option").any(lowerName::contains) -> "设置/权限 Activity"
            typeLabel == "Activity" && listOf("webview", "browser", "hybrid", "h5", "web", "html5", "webpage", "webactivity").any(lowerName::contains) -> "网页容器 Activity"
            typeLabel == "Activity" && listOf("pay", "payment", "wallet", "cashier", "checkout", "order", "bill").any(lowerName::contains) -> "支付/钱包 Activity"
            typeLabel == "Activity" && listOf("share", "invite", "deeplink", "scheme", "redirect", "jump", "navigate").any(lowerName::contains) -> "分享/跳转 Activity"
            typeLabel == "Activity" && listOf("video", "player", "media", "live", "stream", "play").any(lowerName::contains) -> "视频播放 Activity"
            typeLabel == "Activity" && listOf("image", "photo", "gallery", "picture", "album", "camera").any(lowerName::contains) -> "图片浏览 Activity"
            typeLabel == "Activity" && listOf("search", "find", "lookup", "query").any(lowerName::contains) -> "搜索 Activity"
            typeLabel == "Activity" && listOf("profile", "user", "mine", "me", "personal", "center").any(lowerName::contains) -> "个人中心 Activity"
            typeLabel == "Activity" && listOf("chat", "message", "im", "conversation", "talk").any(lowerName::contains) -> "聊天消息 Activity"
            typeLabel == "Activity" && listOf("notification", "notice", "alert", "tip", "toast", "dialog", "popup").any(lowerName::contains) -> "通知弹窗 Activity"
            typeLabel == "Activity" && listOf("guide", "tutorial", "intro", "walkthrough", "onboarding", "newbie").any(lowerName::contains) -> "新手引导 Activity"
            typeLabel == "Activity" && listOf("reward", "bonus", "coupon", "voucher", "gift", "prize", "lottery", "lucky").any(lowerName::contains) -> "奖励福利 Activity"
            typeLabel == "Receiver" && listOf("push", "alarm", "message", "notice", "notification").any(lowerName::contains) -> "推送 Receiver"
            typeLabel == "Receiver" && listOf("boot", "startup", "bootcompleted", "restart", "reboot").any(lowerName::contains) -> "开机启动 Receiver"
            typeLabel == "Receiver" && listOf("network", "connectivity", "wifi", "networkchange", "netstate").any(lowerName::contains) -> "网络变化 Receiver"
            typeLabel == "Receiver" && listOf("screen", "display", "lock", "unlock", "userpresent").any(lowerName::contains) -> "屏幕状态 Receiver"
            typeLabel == "Receiver" && listOf("package", "install", "uninstall", "update", "replace").any(lowerName::contains) -> "包管理 Receiver"
            typeLabel == "Receiver" && listOf("time", "clock", "alarm", "schedule", "timer").any(lowerName::contains) -> "定时任务 Receiver"
            typeLabel == "Receiver" && listOf("ad", "advert", "promo", "marketing", "track").any(lowerName::contains) -> "广告跟踪 Receiver"
            typeLabel == "Service" && listOf("push", "job", "message", "notice", "notification").any(lowerName::contains) -> "推送 Service"
            typeLabel == "Service" && listOf("download", "file", "transfer", "sync", "upload").any(lowerName::contains) -> "下载传输 Service"
            typeLabel == "Service" && listOf("location", "gps", "geofence", "track", "position").any(lowerName::contains) -> "定位追踪 Service"
            typeLabel == "Service" && listOf("sensor", "step", "health", "fitness", "activity").any(lowerName::contains) -> "传感器 Service"
            typeLabel == "Service" && listOf("music", "audio", "player", "media", "sound").any(lowerName::contains) -> "音频播放 Service"
            typeLabel == "Service" && listOf("ad", "advert", "union", "reward", "promo", "marketing", "track", "analytics").any(lowerName::contains) -> "广告 Service"
            typeLabel == "Service" && listOf("daemon", "foreground", "keepalive", "watchdog", "heartbeat", "alive").any(lowerName::contains) -> "保活 Service"
            else -> "疑似推广组件"
        }
        val riskLabel = when {
            // SDK 组件统一按中低风险处理: SDK 通常只影响广告展示/推送/上报, 不影响主功能入口
            groupLabel.contains("SDK 组件") && typeLabel == "Activity" -> "低风险"
            groupLabel.contains("SDK 组件") && (typeLabel == "Receiver" || typeLabel == "Service") -> "低风险"
            typeLabel == "Activity" && score >= 5 -> "低风险"
            groupLabel == "主入口 Activity" -> "高风险"
            groupLabel == "账号登录 Activity" -> "高风险"
            groupLabel == "支付/钱包 Activity" -> "高风险"
            groupLabel == "设置/权限 Activity" -> "中风险"
            groupLabel == "网页容器 Activity" -> "中风险"
            groupLabel == "聊天消息 Activity" -> "中风险"
            groupLabel == "个人中心 Activity" -> "中风险"
            groupLabel == "搜索 Activity" -> "中风险"
            groupLabel == "视频播放 Activity" -> "中风险"
            groupLabel == "图片浏览 Activity" -> "中风险"
            typeLabel == "Receiver" && (groupLabel == "推送 Receiver" || groupLabel == "广告跟踪 Receiver") && score >= 5 -> "低风险"
            typeLabel == "Receiver" && (groupLabel == "开机启动 Receiver" || groupLabel == "网络变化 Receiver" || groupLabel == "屏幕状态 Receiver") -> "中风险"
            typeLabel == "Receiver" && score >= 5 -> "中风险"
            typeLabel == "Service" && (groupLabel == "推送 Service" || groupLabel == "广告 Service" || groupLabel == "保活 Service") && score >= 5 -> "低风险"
            typeLabel == "Service" && (groupLabel == "定位追踪 Service" || groupLabel == "下载传输 Service") -> "中风险"
            typeLabel == "Service" && score >= 5 -> "中风险"
            else -> "需确认"
        }
        val recommendation = when {
            // SDK 组件统一推荐优先治理: 它们基本都是推送/广告/统计 SDK, 冻结后只损失对应推广/上报能力,
            // 主业务基本不受影响 (除了极少数 SDK 可能负责 push 接收需要保留 - 但那也是 App 集成的副产物, 用户大概率不在乎)
            groupLabel.contains("SDK 组件") && typeLabel == "Activity" -> "SDK 广告/推送落地页, 可优先冻结, 不影响主业务."
            groupLabel.contains("SDK 组件") && typeLabel == "Receiver" -> "SDK 推送/启动上报 Receiver, 可优先冻结, 减少后台唤醒."
            groupLabel.contains("SDK 组件") && typeLabel == "Service" -> "SDK 后台运行服务, 可优先冻结, 减少耗电和后台活动."
            else -> when (groupLabel) {
                "启动广告 Activity" -> "优先治理，通常影响启动页广告。"
                "推荐流/活动页 Activity" -> "谨慎治理，可能影响活动页或推荐入口。"
                "主入口 Activity" -> "冻结后图标或主页面可能不可用，适合做冻结式处理。"
                "账号登录 Activity" -> "通常影响登录授权，建议保留。"
                "设置/权限 Activity" -> "可能影响应用设置或权限引导，谨慎处理。"
                "网页容器 Activity" -> "可能承载广告页，也可能承载正文/业务网页，先确认来源。"
                "支付/钱包 Activity" -> "可能影响支付和钱包功能，建议保留。"
                "分享/跳转 Activity" -> "可能影响外链、分享和深链跳转，按需处理。"
                "视频播放 Activity" -> "可能影响视频播放功能，谨慎处理。"
                "图片浏览 Activity" -> "可能影响图片查看功能，谨慎处理。"
                "搜索 Activity" -> "可能影响搜索功能，谨慎处理。"
                "个人中心 Activity" -> "可能影响个人信息管理，谨慎处理。"
                "聊天消息 Activity" -> "可能影响消息收发，谨慎处理。"
                "通知弹窗 Activity" -> "适合治理弹窗广告，通常安全。"
                "新手引导 Activity" -> "可治理，影响新手引导流程。"
                "奖励福利 Activity" -> "优先治理，通常影响奖励弹窗。"
                "推送 Receiver" -> "适合治理推送广告，失败时可恢复组件。"
                "开机启动 Receiver" -> "适合治理，防止App开机自启。"
                "网络变化 Receiver" -> "谨慎治理，可能影响网络状态监听。"
                "屏幕状态 Receiver" -> "谨慎治理，可能影响屏幕状态监听。"
                "包管理 Receiver" -> "谨慎治理，可能影响App安装更新。"
                "定时任务 Receiver" -> "谨慎治理，可能影响定时任务。"
                "广告跟踪 Receiver" -> "优先治理，适合阻止广告跟踪。"
                "推送 Service" -> "适合治理后台推送广告，建议保留恢复路径。"
                "下载传输 Service" -> "谨慎治理，可能影响文件下载。"
                "定位追踪 Service" -> "适合治理，减少位置数据收集。"
                "传感器 Service" -> "谨慎治理，可能影响计步等功能。"
                "音频播放 Service" -> "谨慎治理，可能影响音频播放。"
                "广告 Service" -> "适合治理广告加载服务，操作前确认不是主业务服务。"
                "保活 Service" -> "优先治理，减少后台资源占用。"
                else -> "命中推广关键词，建议确认组件名称后处理。"
            }
        }
        val componentName = if (fullName.startsWith(packageName)) {
            packageName + "/" + fullName.removePrefix(packageName)
        } else {
            "$packageName/$fullName"
        }
        return PromoComponentCandidate(componentName, shortName, typeLabel, enabled, score, groupLabel, recommendation, riskLabel)
    }

    private fun promoComponentScore(lowerName: String, typeLabel: String): Int {
        var score = 0
        val strongHints = listOf(
            "splash", "startup", "launchad", "advert", "adactivity", "adservice", "adreceiver",
            "push", "recommend", "promo", "feedad", "reward", "interstitial", "union",
            "bootad", "openad", "opening", "boot", "ad_", "_ad", "ads_", "_ads",
            "marketing", "campaign", "promotion", "coupon", "bonus", "gift", "prize",
            "lottery", "lucky", "spin", "wheel", "draw", "raffle", "scratch",
            "popup", "pop", "dialog", "alert", "toast", "tip", "float", "overlay",
            "banner", "bannerad", "interstitialad", "nativead", "rewarded", "rewardad",
            "tracker", "track", "analytics", "report", "log", "event", "beacon",
            "daemon", "keepalive", "foreground", "watchdog", "heartbeat", "alive",
            "pangle", "gdt", "csj", "bytedance", "oceanengine", "gromore",
            "sigmob", "mintegral", "applovin", "ironsource", "unity", "vungle",
            "admob", "adcolony", "inmobi", "chartboost", "tapjoy", "startapp",
            "youmi", "adwo", "domob", "wqmobile", "mads", "tanx", "alimama",
            "umeng", "jpush", "getui", "igexin", "gepush", "unipush"
        )
        val moderateHints = listOf(
            "guide", "popup", "notice", "message", "operation", "market", "discover", "hot", "brand",
            "feed", "stream", "timeline", "moment", "circle", "community", "social",
            "video", "live", "stream", "player", "media", "audio", "music",
            "image", "photo", "gallery", "picture", "album", "camera",
            "search", "find", "lookup", "query", "suggest", "autocomplete",
            "profile", "user", "mine", "me", "personal", "center", "account",
            "chat", "im", "conversation", "talk", "message", "dm",
            "notification", "notice", "alert", "reminder", "schedule", "alarm",
            "tutorial", "intro", "walkthrough", "onboarding", "newbie", "help",
            "share", "invite", "deeplink", "scheme", "redirect", "jump", "navigate",
            "download", "file", "transfer", "sync", "upload", "backup", "restore",
            "location", "gps", "geofence", "position", "map", "navigate",
            "sensor", "step", "health", "fitness", "activity", "sport",
            "network", "connectivity", "wifi", "netstate", "connection",
            "screen", "display", "lock", "unlock", "userpresent", "package",
            "install", "uninstall", "update", "replace", "time", "clock", "timer"
        )
        val weakHints = listOf(
            "webview", "browser", "hybrid", "h5", "web", "html5", "webpage",
            "setting", "permission", "privacy", "security", "config", "preference",
            "pay", "payment", "wallet", "cashier", "checkout", "order", "bill",
            "login", "signin", "register", "signup", "auth", "oauth", "passport",
            "launcher", "mainactivity", "homeactivity", "main", "desktop", "homescreen"
        )
        strongHints.forEach { if (lowerName.contains(it)) score += 3 }
        moderateHints.forEach { if (lowerName.contains(it)) score += 1 }
        weakHints.forEach { if (lowerName.contains(it)) score += 0 }
        if (typeLabel == "Activity" && listOf("splash", "startup", "launch", "ad", "promo", "reward", "popup").any(lowerName::contains)) score += 2
        if (typeLabel == "Receiver" && listOf("push", "alarm", "recommend", "ad", "boot", "track").any(lowerName::contains)) score += 2
        if (typeLabel == "Service" && listOf("push", "ad", "recommend", "job", "daemon", "track", "location").any(lowerName::contains)) score += 2
        if (lowerName.contains("ad") && (lowerName.contains("service") || lowerName.contains("receiver") || lowerName.contains("activity"))) score += 1
        if (lowerName.contains("track") || lowerName.contains("analytics") || lowerName.contains("report")) score += 1
        if (lowerName.contains("pangle") || lowerName.contains("gdt") || lowerName.contains("csj")) score += 2
        // SDK 命名空间组件额外加分: 这类组件由广告/推送/统计 SDK 集成而来, 是推广治理的核心目标
        if (SDK_NAMESPACES.any { lowerName.startsWith(it) }) score += 4
        return score
    }

    /**
     * 判定一个类名是否属于第三方 SDK 命名空间 (区别于应用本身的包名空间).
     * 第三方 SDK 的类名前缀通常是独立的反向域名, 例如:
     *   - 穿山甲 (Pangle/CSJ): com.bytedance.sdk.openadsdk.*
     *   - 优量汇 (GDT): com.qq.e.ads.*
     *   - 友盟推送: com.umeng.message.* / com.umeng.analytics.*
     * 当应用包名是 com.taobao.* 但组件类名是 com.bytedance.sdk.* 时, 该组件明显是集成 SDK.
     */
    private fun isLikelySdkNamespace(className: String): Boolean {
        val lower = className.lowercase()
        return SDK_NAMESPACES.any { lower.startsWith(it) }
    }

    /**
     * 从类名中识别所属 SDK 厂商, 用于在 UI 上显示 "Pangle SDK 组件" / "友盟 SDK 组件" 等标签.
     */
    private fun sdkVendorShort(className: String): String {
        val lower = className.lowercase()
        for ((vendor, prefixes) in SDK_VENDOR_MAP) {
            if (prefixes.any { lower.startsWith(it) }) return vendor
        }
        val parts = className.split('.')
        return if (parts.size >= 2 && parts[0] == "com") parts[1] else "未知"
    }

    /**
     * 供 UI 直接调用: 判定一个组件类名是否属于第三方 SDK 组件.
     */
    fun isSdkComponent(componentClassName: String): Boolean {
        val lower = componentClassName.lowercase()
        return SDK_NAMESPACES.any { lower.startsWith(it) }
    }

    /**
     * 供 UI 调用: 获取 SDK 厂商短名. 类名不在已映射列表里时返回 "".
     */
    fun sdkVendorFor(componentClassName: String): String {
        val lower = componentClassName.lowercase()
        for ((vendor, prefixes) in SDK_VENDOR_MAP) {
            if (prefixes.any { lower.startsWith(it) }) return vendor
        }
        return ""
    }

    // 常见第三方 SDK 命名空间前缀 (小写), 命中即认为是 SDK 组件
    internal val SDK_NAMESPACES = listOf(
        "com.bytedance.sdk", "com.bytedance.adsdk", "com.bytedance.tools",
        "com.qq.e.ads", "com.qq.e.community", "com.qq.e.utils",
        "com.baidu.mobads",
        "com.qihoo.ads",
        "com.sigmob", "com.mintegral", "com.applovin", "com.ironsource",
        "com.unity3d.ads", "com.unity3d.player", "com.vungle.warren",
        "com.google.android.gms.ads", "com.adcolony", "com.inmobi",
        "com.chartboost", "com.tapjoy", "com.startapp.android",
        "com.umeng", "com.umeng.message", "com.umeng.analytics",
        "com.jpush", "cn.jpush", "cn.jiguang",
        "com.igexin", "com.getui.gismart", "com.getui",
        "com.xiaomi.mipush", "com.huawei.hms.push", "com.huawei.android.hms.push",
        "com.heytap", "com.vivo.push", "com.meizu.cloud.push",
        "com.tencent.bugly", "com.tencent.tdm", "com.testin", "com.umeng.share",
        "com.alibaba.baichuan", "com.alipay.sdk", "com.tencent.mm.sdk",
        "com.shareinstall", "com.tendcloud.tenddata", "com.growingio",
        "com.sensorsdata.analytics", "com.gtgj",
        "com.yandex.mobileads", "com.facebook.ads", "com.twitter.sdk.android"
    )

    // SDK 厂商短名映射 - 用于 UI 标签展示
    internal val SDK_VENDOR_MAP = linkedMapOf(
        "Pangle" to listOf("com.bytedance.sdk", "com.bytedance.adsdk", "com.bytedance.tools"),
        "优量汇" to listOf("com.qq.e.ads", "com.qq.e.community", "com.qq.e.utils"),
        "百度Mob" to listOf("com.baidu.mobads"),
        "Sigmob" to listOf("com.sigmob"),
        "Mintegral" to listOf("com.mintegral"),
        "AppLovin" to listOf("com.applovin"),
        "IronSource" to listOf("com.ironsource"),
        "UnityAds" to listOf("com.unity3d.ads", "com.unity3d.player"),
        "Vungle" to listOf("com.vungle.warren"),
        "AdMob" to listOf("com.google.android.gms.ads"),
        "AdColony" to listOf("com.adcolony"),
        "InMobi" to listOf("com.inmobi"),
        "Chartboost" to listOf("com.chartboost"),
        "Tapjoy" to listOf("com.tapjoy"),
        "StartApp" to listOf("com.startapp.android"),
        "友盟" to listOf("com.umeng"),
        "极光推送" to listOf("com.jpush", "cn.jpush", "cn.jiguang"),
        "个推" to listOf("com.igexin", "com.getui.gismart", "com.getui"),
        "小米推送" to listOf("com.xiaomi.mipush"),
        "华为推送" to listOf("com.huawei.hms.push", "com.huawei.android.hms.push"),
        "OPPO推送" to listOf("com.heytap"),
        "vivo推送" to listOf("com.vivo.push"),
        "魅族推送" to listOf("com.meizu.cloud.push"),
        "Bugly" to listOf("com.tencent.bugly", "com.tencent.tdm"),
        "Testin" to listOf("com.testin"),
        "阿里百川" to listOf("com.alibaba.baichuan"),
        "支付宝" to listOf("com.alipay.sdk"),
        "微信SDK" to listOf("com.tencent.mm.sdk"),
        "TalkingData" to listOf("com.tendcloud.tenddata"),
        "GrowingIO" to listOf("com.growingio"),
        "神策" to listOf("com.sensorsdata.analytics"),
        "Yandex" to listOf("com.yandex.mobileads"),
        "Facebook" to listOf("com.facebook.ads"),
        "Twitter" to listOf("com.twitter.sdk.android")
    )
}
