package com.HanFeng.service

import com.HanFeng.data.RuleRepository

/**
 * P1.7 智能场景模式
 */
enum class SceneMode(val displayName: String) {
    /**
     * 激进模式：小说/漫画/视频 App
     */
    AGGRESSIVE("激进模式"),
    
    /**
     * 平衡模式：大部分应用（默认）
     */
    BALANCED("平衡模式"),
    
    /**
     * 兼容模式：银行/支付/办公类 App
     */
    COMPATIBLE("兼容模式"),
    
    /**
     * 游戏模式：游戏类 App
     */
    GAME("游戏模式");
}

object SceneModeManager {
    private val sceneModeCache = hashMapOf<String, SceneMode>()
    
    // 小说类 App 包名特征
    private val novelAppKeywords = setOf(
        "novel", "book", "reader", "reading", "qidian", "jinjiang",
        "zongheng", "17k", "qimao", "fanqie", "migu", "reading",
        "comic", "manga", "manhua", "cartoon", "drama", "duanju", "shortdrama", "short_drama",
        "minidrama", "mini_drama", "episode", "hongguo", "story", "bookcity", "bookstore", "changdu", "shuqi", "ireader",
        "listen", "audio", "tingshu", "dongman"
    )
    
    // 视频类 App 包名特征
    private val videoAppKeywords = setOf(
        "video", "tv", "movie", "film", "iqiyi", "tencent", "youku",
        "mgtv", "pptv", "sohu", "bilibili", "douyin", "kuaishou"
    )
    
    // 游戏类 App 包名特征
    private val gameAppKeywords = setOf(
        "game", "play", "tencentgames", "netease", "miHoYo", "gamedev"
    )
    
    // 金融/支付类 App 包名特征
    private val financeAppKeywords = setOf(
        "bank", "pay", "alipay", "tenpay", "finance", "wallet",
        "stock", "fund", "insurance", "tax"
    )
    
    // 办公/效率类 App
    private val productivityAppKeywords = setOf(
        "office", "doc", "mail", "email", "calendar", "note",
        "meeting", "conference", "wps", "office"
    )

    /**
     * 根据 App 自动检测场景模式
     */
    fun autoDetect(appName: String, packageName: String): SceneMode {
        // 检查缓存
        val cacheKey = packageName.lowercase()
        sceneModeCache[cacheKey]?.let { return it }
        
        val lowerPackage = packageName.lowercase()
        val lowerName = appName.lowercase()
        
        // 优先级判断
        val mode = when {
            // 金融/支付类 -> 兼容模式
            isPackageMatch(lowerPackage, lowerName, financeAppKeywords) -> SceneMode.COMPATIBLE
            
            // 办公/效率类 -> 兼容模式
            isPackageMatch(lowerPackage, lowerName, productivityAppKeywords) -> SceneMode.COMPATIBLE
            
            // 游戏类 -> 游戏模式
            isPackageMatch(lowerPackage, lowerName, gameAppKeywords) -> SceneMode.GAME
            
            // 小说/阅读类 -> 激进模式
            isPackageMatch(lowerPackage, lowerName, novelAppKeywords) -> SceneMode.AGGRESSIVE
            
            // 视频类 -> 激进模式
            isPackageMatch(lowerPackage, lowerName, videoAppKeywords) -> SceneMode.AGGRESSIVE
            
            // 默认 -> 平衡模式
            else -> SceneMode.BALANCED
        }
        
        sceneModeCache[cacheKey] = mode
        return mode
    }

    /**
     * 检查包名是否匹配关键词
     */
    private fun isPackageMatch(packageName: String, appName: String, keywords: Set<String>): Boolean {
        return keywords.any { keyword ->
            packageName.contains(keyword) || appName.contains(keyword)
        }
    }

    /**
     * 根据场景模式获取拦截策略
     */
    fun getBlockingStrategy(mode: SceneMode): BlockingStrategy {
        return when (mode) {
            SceneMode.AGGRESSIVE -> BlockingStrategy(
                enableKeywordBlock = true,
                enablePathBlock = true,
                enableBodyInspection = true,
                enableDeepInspection = true,
                enableCosmeticFilter = true,
                enableRequestRewrite = true,
                protectWhitelist = false,
                aggressiveMode = true
            )
            
            SceneMode.BALANCED -> BlockingStrategy(
                enableKeywordBlock = true,
                enablePathBlock = true,
                enableBodyInspection = true,
                enableDeepInspection = false,
                enableCosmeticFilter = true,
                enableRequestRewrite = true,
                protectWhitelist = true,
                aggressiveMode = false
            )
            
            SceneMode.COMPATIBLE -> BlockingStrategy(
                enableKeywordBlock = false,
                enablePathBlock = true,
                enableBodyInspection = false,
                enableDeepInspection = false,
                enableCosmeticFilter = false,
                enableRequestRewrite = false,
                protectWhitelist = true,
                aggressiveMode = false
            )
            
            SceneMode.GAME -> BlockingStrategy(
                enableKeywordBlock = true,
                enablePathBlock = false,
                enableBodyInspection = false,
                enableDeepInspection = false,
                enableCosmeticFilter = false,
                enableRequestRewrite = false,
                protectWhitelist = true,
                aggressiveMode = false
            )
        }
    }

    /**
     * 清除缓存（用于 App 重新检测）
     */
    fun clearCache() {
        sceneModeCache.clear()
    }

    /**
     * 手动设置 App 的场景模式
     */
    fun setManualMode(packageName: String, mode: SceneMode) {
        sceneModeCache[packageName.lowercase()] = mode
    }

    /**
     * 获取当前场景模式
     */
    fun getMode(packageName: String): SceneMode {
        return sceneModeCache[packageName.lowercase()] ?: SceneMode.BALANCED
    }
}

/**
 * 拦截策略配置
 */
data class BlockingStrategy(
    val enableKeywordBlock: Boolean,      // 启用关键词拦截
    val enablePathBlock: Boolean,         // 启用路径特征拦截
    val enableBodyInspection: Boolean,    // 启用响应体检测
    val enableDeepInspection: Boolean,    // 启用深度检测
    val enableCosmeticFilter: Boolean,    // 启用 CSS 隐藏
    val enableRequestRewrite: Boolean,    // 启用请求重写
    val protectWhitelist: Boolean,        // 保护白名单域名
    val aggressiveMode: Boolean           // 激进模式（允许误拦截）
) {
    /**
     * 是否应该检测响应体
     */
    fun shouldInspectBody(): Boolean = enableBodyInspection || enableDeepInspection
    
    /**
     * 是否应该深度检测
     */
    fun shouldDeepInspect(): Boolean = enableDeepInspection && aggressiveMode
    
    /**
     * 是否应该拦截关键词
     */
    fun shouldBlockByKeyword(): Boolean = enableKeywordBlock
    
    /**
     * 是否应该保护域名
     */
    fun shouldProtectDomain(domain: String): Boolean {
        return protectWhitelist && RuleRepository.isWhitelistedDomain(domain)
    }
}
