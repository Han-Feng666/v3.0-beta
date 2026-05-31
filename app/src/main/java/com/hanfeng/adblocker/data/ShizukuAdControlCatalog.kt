package com.HanFeng.data

object ShizukuAdControlCatalog {

    private val batchProtectedCategories = setOf("浏览器推荐", "主题壁纸", "锁屏推荐", "系统应用推荐")

    data class Preset(
        val id: String,
        val title: String,
        val packageName: String,
        val description: String,
        val category: String
    )

    private val presets = listOf(
        Preset(
            id = "xiaomi-app-store",
            title = "小米应用商店推广",
            packageName = "com.xiaomi.mipicks",
            description = "适合治理应用商店推荐和推广流量。",
            category = "系统推广"
        ),
        Preset(
            id = "xiaomi-game-center",
            title = "小米游戏中心",
            packageName = "com.xiaomi.gamecenter",
            description = "适合治理游戏中心推广和推荐位。",
            category = "系统推广"
        ),
        Preset(
            id = "xiaomi-wallpaper-carousel",
            title = "小米锁屏画报",
            packageName = "com.miui.android.fashiongallery",
            description = "适合治理锁屏画报和锁屏推荐内容。",
            category = "锁屏推荐"
        ),
        Preset(
            id = "xiaomi-global-search",
            title = "小米智能助理",
            packageName = "com.miui.personalassistant",
            description = "适合治理负一屏推荐、搜索热词、资讯卡片和系统推荐内容。",
            category = "负一屏推荐"
        ),
        Preset(
            id = "xiaomi-browser",
            title = "小米浏览器推荐",
            packageName = "com.android.browser",
            description = "适合治理浏览器首页推荐、热榜和搜索推广。",
            category = "浏览器推荐"
        ),
        Preset(
            id = "xiaomi-weather",
            title = "小米天气推荐",
            packageName = "com.miui.weather2",
            description = "适合治理天气应用中的推荐内容和营销位。",
            category = "系统应用推荐"
        ),
        Preset(
            id = "xiaomi-video-wallpaper",
            title = "小米主题壁纸推荐",
            packageName = "com.android.thememanager",
            description = "适合治理主题商店、壁纸、动态壁纸推荐广告。",
            category = "主题壁纸"
        ),
        Preset(
            id = "xiaomi-content-center",
            title = "小米内容中心",
            packageName = "com.miui.contentextension",
            description = "适合治理系统内容分发、资讯推荐和搜索联动推广。",
            category = "内容推荐"
        ),
        Preset(
            id = "xiaomi-reader-promo",
            title = "小米阅读推荐",
            packageName = "com.duokan.phone.remotecontroller",
            description = "适合治理阅读内容推荐、书城活动位和营销入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "xiaomi-video",
            title = "小米视频推荐",
            packageName = "com.miui.video",
            description = "适合治理视频频道推荐、精选内容和营销卡片。",
            category = "内容推荐"
        ),
        Preset(
            id = "xiaomi-system-ad-service",
            title = "小米系统广告服务",
            packageName = "com.miui.systemAdSolution",
            description = "适合治理系统广告分发、应用内推荐投放和营销素材下发。",
            category = "系统推广"
        ),
        Preset(
            id = "oppo-app-market",
            title = "OPPO 软件商店",
            packageName = "com.heytap.market",
            description = "适合治理软件商店推荐和下载推广。",
            category = "系统推广"
        ),
        Preset(
            id = "oppo-browser",
            title = "OPPO 浏览器推荐",
            packageName = "com.heytap.browser",
            description = "适合治理浏览器首页推荐和搜索推广。",
            category = "浏览器推荐"
        ),
        Preset(
            id = "oppo-theme-store",
            title = "OPPO 主题商店推荐",
            packageName = "com.heytap.themestore",
            description = "适合治理主题、壁纸、字体等营销推荐。",
            category = "主题壁纸"
        ),
        Preset(
            id = "oppo-smart-assistant",
            title = "OPPO 智能助手",
            packageName = "com.heytap.pictorial",
            description = "适合治理负一屏、画报和智能推荐内容。",
            category = "负一屏推荐"
        ),
        Preset(
            id = "oppo-video",
            title = "OPPO 视频推荐",
            packageName = "com.heytap.yoli",
            description = "适合治理视频频道推荐、精选内容和营销卡片。",
            category = "内容推荐"
        ),
        Preset(
            id = "oppo-search",
            title = "OPPO 搜索推荐",
            packageName = "com.heytap.quicksearchbox",
            description = "适合治理系统搜索联想、热榜推荐和内容分发入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "oppo-content-service",
            title = "OPPO 内容服务推荐",
            packageName = "com.heytap.reader",
            description = "适合治理系统内容分发、阅读推荐和资讯联动入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "oppo-game-center",
            title = "OPPO 游戏中心",
            packageName = "com.nearme.gamecenter",
            description = "适合治理游戏推荐和活动推广。",
            category = "系统推广"
        ),
        Preset(
            id = "vivo-app-store",
            title = "vivo 应用商店",
            packageName = "com.bbk.appstore",
            description = "适合治理应用商店推荐和更新推广。",
            category = "系统推广"
        ),
        Preset(
            id = "vivo-browser",
            title = "vivo 浏览器推荐",
            packageName = "com.vivo.browser",
            description = "适合治理浏览器首页、搜索热词和信息流推荐。",
            category = "浏览器推荐"
        ),
        Preset(
            id = "vivo-reader",
            title = "vivo 内容推荐",
            packageName = "com.vivo.contentcatcher",
            description = "适合治理系统内容推荐、资讯卡片和营销流量。",
            category = "内容推荐"
        ),
        Preset(
            id = "vivo-search",
            title = "vivo 搜索推荐",
            packageName = "com.vivo.globalsearch",
            description = "适合治理系统搜索联想、热榜推荐和内容分发入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "vivo-system-ad-service",
            title = "vivo 系统广告服务",
            packageName = "com.vivo.abe",
            description = "适合治理系统广告分发、推荐素材下发和营销投放能力。",
            category = "系统推广"
        ),
        Preset(
            id = "vivo-jovi",
            title = "vivo Jovi 智慧场景",
            packageName = "com.vivo.assistant",
            description = "适合治理负一屏、智慧场景和桌面推荐。",
            category = "负一屏推荐"
        ),
        Preset(
            id = "vivo-theme-store",
            title = "vivo 主题商店",
            packageName = "com.bbk.theme",
            description = "适合治理主题、壁纸和个性化推荐。",
            category = "主题壁纸"
        ),
        Preset(
            id = "vivo-game-center",
            title = "vivo 游戏中心",
            packageName = "com.vivo.game",
            description = "适合治理游戏推荐和活动广告。",
            category = "系统推广"
        ),
        Preset(
            id = "huawei-app-market",
            title = "华为应用市场",
            packageName = "com.huawei.appmarket",
            description = "适合治理应用市场推广和推荐位。",
            category = "系统推广"
        ),
        Preset(
            id = "huawei-browser",
            title = "华为浏览器推荐",
            packageName = "com.huawei.browser",
            description = "适合治理浏览器首页推荐、热榜和搜索推广。",
            category = "浏览器推荐"
        ),
        Preset(
            id = "huawei-smart-assistant",
            title = "华为智慧助手",
            packageName = "com.huawei.intelligent",
            description = "适合治理负一屏、智慧推荐和卡片流。",
            category = "负一屏推荐"
        ),
        Preset(
            id = "huawei-themes",
            title = "华为主题推荐",
            packageName = "com.huawei.android.thememanager",
            description = "适合治理主题、壁纸和个性化推荐。",
            category = "主题壁纸"
        ),
        Preset(
            id = "huawei-video",
            title = "华为视频推荐",
            packageName = "com.huawei.himovie",
            description = "适合治理视频应用中的推荐流和内容推广。",
            category = "内容推荐"
        ),
        Preset(
            id = "huawei-reader",
            title = "华为阅读推荐",
            packageName = "com.huawei.ohos.books",
            description = "适合治理阅读书城推荐、内容分发和营销活动入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "huawei-search",
            title = "华为搜索推荐",
            packageName = "com.huawei.search",
            description = "适合治理系统搜索推荐、热榜词和内容分发入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "huawei-reader-service",
            title = "华为内容服务推荐",
            packageName = "com.huawei.contentsensor",
            description = "适合治理系统内容感知推荐、资讯分发和搜索联动入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "honor-app-market",
            title = "荣耀应用市场",
            packageName = "com.hihonor.appmarket",
            description = "适合治理应用市场推荐、更新推广和热榜入口。",
            category = "系统推广"
        ),
        Preset(
            id = "honor-browser",
            title = "荣耀浏览器推荐",
            packageName = "com.hihonor.browser",
            description = "适合治理浏览器首页推荐、搜索热榜和信息流推广。",
            category = "浏览器推荐"
        ),
        Preset(
            id = "honor-smart-assistant",
            title = "荣耀智慧助手推荐",
            packageName = "com.hihonor.intelligent",
            description = "适合治理负一屏资讯流、推荐卡片和系统内容推广。",
            category = "负一屏推荐"
        ),
        Preset(
            id = "honor-video",
            title = "荣耀视频推荐",
            packageName = "com.hihonor.video",
            description = "适合治理视频频道推荐、精选内容和营销位。",
            category = "内容推荐"
        ),
        Preset(
            id = "honor-search",
            title = "荣耀搜索推荐",
            packageName = "com.hihonor.search",
            description = "适合治理系统搜索联想、热榜推荐和内容分发入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "honor-reader-service",
            title = "荣耀内容服务推荐",
            packageName = "com.hihonor.contentsensor",
            description = "适合治理系统内容分发、资讯推荐和搜索联动入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "honor-smart-suggestion",
            title = "荣耀推荐服务",
            packageName = "com.hihonor.suggestion",
            description = "适合治理系统推荐建议、搜索联想和内容发现入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "realme-app-market",
            title = "realme 软件商店",
            packageName = "com.heytap.market",
            description = "适合治理 realme 系软件商店推荐和下载推广。",
            category = "系统推广"
        ),
        Preset(
            id = "realme-smart-assistant",
            title = "realme 负一屏推荐",
            packageName = "com.heytap.pictorial",
            description = "适合治理 realme 负一屏资讯、推荐卡片和画报内容。",
            category = "负一屏推荐"
        ),
        Preset(
            id = "realme-search",
            title = "realme 搜索推荐",
            packageName = "com.heytap.quicksearchbox",
            description = "适合治理 realme 系系统搜索推荐、热榜词和内容分发入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "realme-content-service",
            title = "realme 内容服务推荐",
            packageName = "com.heytap.reader",
            description = "适合治理 realme 系内容分发、阅读推荐和资讯联动入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "oneplus-app-market",
            title = "一加应用商店",
            packageName = "com.heytap.market",
            description = "适合治理一加系应用市场推荐和更新推广。",
            category = "系统推广"
        ),
        Preset(
            id = "oneplus-smart-recommend",
            title = "一加负一屏推荐",
            packageName = "com.heytap.pictorial",
            description = "适合治理一加负一屏资讯流、推荐卡片和画报推广。",
            category = "负一屏推荐"
        ),
        Preset(
            id = "oneplus-search",
            title = "一加搜索推荐",
            packageName = "com.heytap.quicksearchbox",
            description = "适合治理一加系系统搜索联想、热榜推荐和内容分发入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "oneplus-content-service",
            title = "一加内容服务推荐",
            packageName = "com.heytap.reader",
            description = "适合治理一加系内容分发、阅读推荐和资讯联动入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "samsung-galaxy-store",
            title = "三星 Galaxy Store",
            packageName = "com.sec.android.app.samsungapps",
            description = "适合治理应用商店推荐、精选内容和营销入口。",
            category = "系统推广"
        ),
        Preset(
            id = "samsung-free",
            title = "三星 Samsung Free",
            packageName = "com.samsung.android.app.spage",
            description = "适合治理负一屏资讯、视频和推荐卡片。",
            category = "负一屏推荐"
        ),
        Preset(
            id = "samsung-game-home",
            title = "三星游戏中心",
            packageName = "com.samsung.android.game.gamehome",
            description = "适合治理游戏中心推荐、活动位和营销入口。",
            category = "系统推广"
        ),
        Preset(
            id = "samsung-finder",
            title = "三星 Finder 推荐",
            packageName = "com.samsung.android.app.galaxyfinder",
            description = "适合治理系统搜索发现、热榜联想和内容分发入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "meizu-app-store",
            title = "魅族应用商店",
            packageName = "com.meizu.mstore",
            description = "适合治理应用商店推荐和更新推广。",
            category = "系统推广"
        ),
        Preset(
            id = "meizu-reader-service",
            title = "魅族内容服务推荐",
            packageName = "com.meizu.media.reader",
            description = "适合治理系统内容分发、阅读推荐和资讯联动入口。",
            category = "内容推荐"
        ),
        Preset(
            id = "meizu-browser",
            title = "魅族浏览器推荐",
            packageName = "com.android.browser",
            description = "适合治理魅族浏览器首页推荐、热榜和搜索推广。",
            category = "浏览器推荐"
        ),
        Preset(
            id = "smartisan-browser",
            title = "坚果浏览器推荐",
            packageName = "com.smartisan.browser",
            description = "适合治理浏览器首页推荐、热点资讯和搜索推广。",
            category = "浏览器推荐"
        )
    )

    private val presetsByPackage = presets.groupBy { it.packageName }
    private val exactLabels = presets.associateBy { "${it.title} (${it.packageName})".lowercase() }
    private val exactTitles = presets.associateBy { it.title.lowercase() }

    fun allPresets(): List<Preset> = presets

    fun installedFirstLabels(installedPackages: Set<String>): List<String> {
        return presets
            .sortedWith(
                compareByDescending<Preset> { it.packageName in installedPackages }
                    .thenBy { it.category }
                    .thenBy { it.title }
            )
            .map { preset ->
                val installed = preset.packageName in installedPackages
                val badge = if (installed) "已安装" else "未安装"
                "[$badge] ${preset.title} (${preset.packageName})"
            }
    }

    fun categories(): List<String> = presets.map { it.category }.distinct()

    fun findPresetByPackage(packageName: String?): Preset? {
        val normalized = packageName?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return presetsByPackage[normalized]?.firstOrNull()
    }

    fun findPresetsByPackage(packageName: String?): List<Preset> {
        val normalized = packageName?.trim().orEmpty()
        if (normalized.isBlank()) return emptyList()
        return presetsByPackage[normalized].orEmpty()
    }

    fun shouldSkipBatchDisable(packageName: String?): Boolean {
        return findPresetsByPackage(packageName).any { it.category in batchProtectedCategories }
    }

    fun batchProtectedReason(packageName: String?): String? {
        val categories = findPresetsByPackage(packageName)
            .map { it.category }
            .filter { it in batchProtectedCategories }
            .distinct()
        if (categories.isEmpty()) return null
        return categories.joinToString("、")
    }

    fun findPresetByAppName(appName: String?): Preset? {
        val normalized = appName?.trim().orEmpty()
        if (normalized.isBlank()) return null
        val packageCandidate = Regex("\\(([^()]+)\\)").find(normalized)?.groupValues?.getOrNull(1)
        findPresetByPackage(packageCandidate)?.let { return it }
        findPresetByPackage(normalized)?.let { return it }
        exactLabels[normalized.lowercase()]?.let { return it }
        exactTitles[normalized.lowercase()]?.let { return it }
        return presets.firstOrNull { preset ->
            normalized.contains("(${preset.packageName})", ignoreCase = true) ||
                normalized.equals(preset.packageName, ignoreCase = true)
        }
    }

    fun isManagedPromoAppHint(appName: String?): Boolean = findPresetByAppName(appName) != null

    fun labels(): List<String> {
        return presets.map { preset ->
            "${preset.title} (${preset.packageName})"
        }
    }
}
