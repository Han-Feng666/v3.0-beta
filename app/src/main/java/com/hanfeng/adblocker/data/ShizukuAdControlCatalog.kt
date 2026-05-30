package com.HanFeng.data

object ShizukuAdControlCatalog {

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
            title = "小米全局搜索",
            packageName = "com.miui.miwallpaper",
            description = "适合治理搜索推荐、下滑搜索页推荐和热词内容。",
            category = "搜索推荐"
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
            id = "realme-app-market",
            title = "realme 软件商店",
            packageName = "com.heytap.market",
            description = "适合治理 realme 系软件商店推荐和下载推广。",
            category = "系统推广"
        ),
        Preset(
            id = "oneplus-app-market",
            title = "一加应用商店",
            packageName = "com.heytap.market",
            description = "适合治理一加系应用市场推荐和更新推广。",
            category = "系统推广"
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
            id = "meizu-app-store",
            title = "魅族应用商店",
            packageName = "com.meizu.mstore",
            description = "适合治理应用商店推荐和更新推广。",
            category = "系统推广"
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

    private val presetsByPackage = presets.associateBy { it.packageName }

    fun allPresets(): List<Preset> = presets

    fun categories(): List<String> = presets.map { it.category }.distinct()

    fun findPresetByPackage(packageName: String?): Preset? {
        val normalized = packageName?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return presetsByPackage[normalized]
    }

    fun findPresetByAppName(appName: String?): Preset? {
        val normalized = appName?.trim().orEmpty()
        if (normalized.isBlank()) return null
        val packageCandidate = Regex("\\(([^()]+)\\)").find(normalized)?.groupValues?.getOrNull(1)
        findPresetByPackage(packageCandidate)?.let { return it }
        findPresetByPackage(normalized)?.let { return it }
        return presets.firstOrNull { preset ->
            normalized.contains(preset.title, ignoreCase = true) ||
                normalized.contains(preset.packageName, ignoreCase = true)
        }
    }

    fun isManagedPromoAppHint(appName: String?): Boolean = findPresetByAppName(appName) != null

    fun labels(): List<String> {
        return presets.map { preset ->
            "${preset.title} (${preset.packageName})"
        }
    }
}
