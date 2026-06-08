package com.HanFeng.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.ShizukuAdControlCatalog
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.databinding.ActivityPromoGovernScopeBinding
import com.HanFeng.databinding.ItemPromoGovernTargetBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RiskLevel { LOW, MEDIUM, HIGH }

class PromoGovernScopeActivity : BaseActivity() {
    private lateinit var binding: ActivityPromoGovernScopeBinding
    private lateinit var adapter: PromoGovernTargetAdapter
    private var currentScope = PromoGovernScope.ALL
    private var allTargets: List<PromoGovernTarget> = emptyList()

    enum class NotificationRiskLevel { HIGH, MEDIUM, LOW }

    private val SYSTEM_CRITICAL_APPS = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.providers.contacts",
        "com.android.providers.telephony",
        "com.android.settings",
        "com.miui.home",
        "com.android.launcher3",
        "com.huawei.android.launcher",
        "com.heytap.customizehome",
        "com.vivo.home",
        "com.samsung.android.onehome"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPromoGovernScopeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }
        adapter = PromoGovernTargetAdapter { target -> showPromoTargetActionDialog(target) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnScopeAll.setOnClickListener { applyScope(PromoGovernScope.ALL) }
        binding.btnScopeSystem.visibility = View.GONE
        binding.btnScopeThirdParty.setOnClickListener { applyScope(PromoGovernScope.THIRD_PARTY_ONLY) }
        loadTargets()
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
    }

    private fun loadTargets() {
        lifecycleScope.launch {
            LogRepository.append(this@PromoGovernScopeActivity, "loadTargets started")
            showShortToast("正在加载可治理 App 列表")
            if (!ensureShizukuReady()) {
                LogRepository.append(this@PromoGovernScopeActivity, "loadTargets failed: Shizuku not ready")
                return@launch
            }
            var installedCount = 0
            val loaded = withContext(Dispatchers.Default) {
                installedCount = packageManager.getInstalledApplications(0).size
                LogRepository.append(this@PromoGovernScopeActivity, "Installed apps: $installedCount")
                val result = discoverPromoGovernTargets(installedOnly = true)
                LogRepository.append(this@PromoGovernScopeActivity, "discoverPromoGovernTargets found ${result.size} targets (installed=$installedCount)")
                result
            }
            if (isFinishing || isDestroyed) return@launch
            allTargets = loaded
            if (loaded.isEmpty()) {
                LogRepository.append(this@PromoGovernScopeActivity, "loadTargets completed: no targets found, installed=$installedCount")
                showShortToast("未识别到可治理 App，请查看日志了解原因")
                buildAndShowNoTargetsMessage(installedCount)
                binding.emptyText.visibility = View.VISIBLE
                binding.list.visibility = View.GONE
            } else {
                LogRepository.append(this@PromoGovernScopeActivity, "loadTargets completed: ${loaded.size} targets")
                showShortToast("已加载 ${loaded.size} 个可治理 App")
                binding.emptyText.visibility = View.GONE
                binding.list.visibility = View.VISIBLE
            }
            applyScope(currentScope)
        }
    }

    private fun buildAndShowNoTargetsMessage(installedCount: Int) {
        val logMessages = buildString {
            appendLine("已安装应用总数：$installedCount")
            appendLine()
            appendLine("推广治理仅扫描第三方 App（含系统预装的淘宝、美团、京东、今日头条等），纯系统组件不参与治理。")
            appendLine()
            appendLine("可能原因：")
            appendLine("1. 当前设备未安装命中识别规则的第三方推广 App")
            appendLine("2. Shizuku 未正确启动（请检查 Shizuku App）")
            appendLine()
            appendLine("识别规则：")
            appendLine("- 知名第三方 App 包名前缀（com.taobao./com.meituan./com.jingdong./com.ss.android.ugc.aweme 等）")
            appendLine("- 应用名称或包名含「淘宝」「美团」「京东」「头条」「抖音」「快手」「微博」「支付宝」「饿了么」「携程」等关键词")
            appendLine("- 推广类标签：「应用商店」「浏览器」「小说」「视频」「活动」「福利」等")
        }
        
        LogRepository.append(this@PromoGovernScopeActivity, logMessages)
        
        try {
            StableDialog.builder(this)
                .setTitle("未识别到可治理 App")
                .setMessage("已安装 $installedCount 个应用，未识别到可治理的第三方推广 App。\n\n推广治理覆盖系统预装的淘宝、美团、京东、今日头条等常见第三方 App，纯系统组件不参与治理。")
                .setPositiveButton("我知道了", null)
                .setNegativeButton("查看日志", { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra("scroll_to_logs", true)
                    })
                })
                .showSafely(this, "Show no targets dialog failed")
        } catch (e: Exception) {
            LogRepository.append(this@PromoGovernScopeActivity, "Show no targets dialog failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun refreshTargetsSilently() {
        val loaded = withContext(Dispatchers.Default) {
            discoverPromoGovernTargets(installedOnly = true)
        }
        if (isFinishing || isDestroyed) return
        allTargets = loaded
        applyScope(currentScope)
    }

    private fun applyScope(scope: PromoGovernScope) {
        currentScope = scope
        val visibleTargets = allTargets.filter { target ->
            when (scope) {
                PromoGovernScope.ALL -> true
                PromoGovernScope.SYSTEM_ONLY -> target.systemApp
                PromoGovernScope.THIRD_PARTY_ONLY -> !target.systemApp
            }
        }
        adapter.submitList(visibleTargets)
        binding.emptyText.visibility = if (visibleTargets.isEmpty()) View.VISIBLE else View.GONE
        binding.summaryText.text = buildSummaryText(visibleTargets)
        updateScopeButtons()
    }

    private fun buildSummaryText(targets: List<PromoGovernTarget>): String {
        if (targets.isEmpty()) {
            return buildString {
                append("当前范围下没有识别到可治理的第三方 App。\n\n")
                append("说明：\n")
                append("推广治理覆盖系统预装的淘宝、美团、京东、今日头条等常见第三方 App，纯系统组件不参与治理。\n\n")
                append("可能原因：\n")
                append("1. 未安装疑似推广类第三方 App\n")
                append("2. 已安装的第三方 App 未命中识别规则\n\n")
                append("识别规则：\n")
                append("知名第三方包名（淘宝/美团/京东/头条/抖音/快手等）或含「应用商店」「浏览器」「小说」「视频」「活动」「福利」等关键词")
            }
        }
        return "已扫描第三方 App（含系统预装），共识别 ${targets.size} 个可治理推广 App。纯系统组件不参与治理。"
    }

    private fun updateScopeButtons() {
        updateScopeButtonState(binding.btnScopeAll, currentScope == PromoGovernScope.ALL)
        updateScopeButtonState(binding.btnScopeThirdParty, currentScope == PromoGovernScope.THIRD_PARTY_ONLY)
    }

    private fun updateScopeButtonState(view: View, active: Boolean) {
        view.alpha = if (active) 1f else 0.72f
    }

    private suspend fun ensureShizukuReady(): Boolean {
        if (!AppSettingsRepository.isShizukuEnabled(this)) {
            StableDialog.builder(this)
                .setTitle("Shizuku 未启用")
                .setMessage("请先开启设置中的 Shizuku 增强。")
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show ensure shizuku enabled dialog failed")
            return false
        }
        val readyState = queryShizukuReadyState(warmIfNeeded = true)
        if (!readyState.readyForEnhancedUse) {
            StableDialog.builder(this)
                .setTitle("Shizuku 暂不可用")
                .setMessage(buildShizukuUnavailableMessage(readyState))
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show shizuku unavailable dialog failed")
            return false
        }
        if (!readyState.adControlAlive) {
            StableDialog.builder(this)
                .setTitle("Shizuku 服务连接失败")
                .setMessage("Shizuku 已连接，但治理服务还未成功绑定。请稍后重试，或重新进入 Shizuku 后再回来。")
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show shizuku bind failed dialog failed")
            return false
        }
        return true
    }

    private fun discoverPromoGovernTargets(installedOnly: Boolean): List<PromoGovernTarget> {
        val installedApps = packageManager.getInstalledApplications(0)
        val selfPackage = packageName
        val presetPackages = ShizukuAdControlCatalog.allPresets().mapTo(linkedSetOf()) { it.packageName }
        var excludedPureSystem = 0
        var includedByPreset = 0
        var includedByWellKnown = 0
        val eligibleApps = installedApps.filter { appInfo ->
            if (appInfo.packageName == selfPackage) return@filter false
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem) return@filter true
            if (appInfo.packageName in presetPackages) {
                includedByPreset++
                return@filter true
            }
            val label = packageManager.getApplicationLabel(appInfo)?.toString().orEmpty()
            if (isWellKnownThirdPartyPromoApp(appInfo.packageName, label)) {
                includedByWellKnown++
                return@filter true
            }
            excludedPureSystem++
            false
        }
        LogRepository.append(this, "[PromoGovern] installedApps=${installedApps.size}, eligibleApps=${eligibleApps.size}, excludedPureSystem=$excludedPureSystem, includedByPreset=$includedByPreset, includedByWellKnown=$includedByWellKnown")
        var scanned = 0
        var hitByCategory = linkedMapOf<String, String>()
        val autoTargets = eligibleApps
            .asSequence()
            .mapNotNull { appInfo ->
                scanned++
                val target = buildThirdPartyPromoTarget(appInfo)
                if (target != null) {
                    hitByCategory[target.category] = (hitByCategory[target.category] ?: "") + "${appInfo.packageName},"
                    LogRepository.append(this, "[PromoGovern] thirdPartyHit: ${appInfo.packageName} label=${packageManager.getApplicationLabel(appInfo)} category=${target.category}")
                }
                target
            }
            .toList()
        LogRepository.append(this, "[PromoGovern] scanned=$scanned, autoTargets=${autoTargets.size}, categories=${hitByCategory.map { "${it.key}:${it.value.count { c -> c == ',' }}" }.joinToString()}")
        return autoTargets.sortedWith(compareBy<PromoGovernTarget> { it.category }.thenBy { it.title })
    }

    private fun buildThirdPartyPromoTarget(appInfo: ApplicationInfo): PromoGovernTarget? {
        val targetPackageName = appInfo.packageName
        val label = packageManager.getApplicationLabel(appInfo)?.toString().orEmpty().ifBlank { targetPackageName }
        val lowerLabel = label.lowercase()
        val lowerPackage = targetPackageName.lowercase()
        if (!looksLikeThirdPartyPromoApp(lowerLabel, lowerPackage)) return null
        val status = ShizukuAdControlRepository.queryPackageStatus(this, targetPackageName)
        if (!status.installed) return null
        val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val notificationRisk = assessNotificationRisk(lowerLabel, lowerPackage)
        return PromoGovernTarget(
            packageName = targetPackageName,
            title = label,
            category = inferPromoCategory(lowerLabel, lowerPackage),
            description = buildThirdPartyPromoDescription(notificationRisk),
            sourceLabel = if (isSystem) "系统预装第三方 App" else "已安装第三方 App",
            systemApp = isSystem,  // ← 修复：使用实际的 isSystem 值
            relatedPresets = emptyList(),
            packageStatus = status
        )
    }

    private fun isWellKnownThirdPartyPromoApp(packageName: String, label: String): Boolean {
        val lowerPackage = packageName.lowercase()
        val lowerLabel = label.lowercase()
        val wellKnownPrefixes = listOf(
            "com.taobao.", "com.tmall.", "com.alibaba.", "com.alipay.", "com.xiami.",
            "com.meituan.", "com.sankuai.", "com.dianping.",
            "com.jingdong.", "com.jd.",
            "com.ss.android.ugc.aweme", "com.ss.android.article.news", "com.ss.android.article.lite",
            "com.iesdouyin.", "com.zhiliaoapp.musically",
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.qqlive", "com.tencent.qqmusic",
            "com.tencent.news", "com.tencent.reading", "com.tencent.map", "com.tencent.mmwork",
            "com.qq.reader", "com.xiclient.", "com.tencent.tmgp",
            "com.sina.weibo", "com.sina.news",
            "com.xunlei.", "com.xunlei.kankan",
            "com.douyu.", "com.douyutv.", "com.douyin.",
            "tv.danmaku.bili", "com.bilibili.",
            "com.smile.gifmaker", "com.kuaishou.",
            "com.eleme.", "com.rajax.me",
            "com.didiglobal.", "com.didichuxing.", "com.sdt.jgcm",
            "com.eg.android.", "com.mybank.", "com.chinamworld.",
            "com.ctrip.", "com.Qunar", "com.tongcheng.",
            "com.baidu.netdisk", "com.baidu.searchbox", "com.baidu.BaiduMap",
            "com.wps.", "cn.wps.",
            "com.netease.cloudmusic", "com.netease.mail", "com.netease.newsreader",
            "com.163.mail", "com.netease.mobimail",
            "com.dragon.read", "com.qidian.",
            "com.UCMobile", "com.uc.", "com.quark.",
            "com.zhaopin.", "com.lietou.", "cn.tianya.", "com.nowcoder.",
            "com.autonavi.", "com.amap.",
            "com.ximalaya.", "com.xiaoyouxi.",
            "me.ele.", "com.xiaomi.mico",
            "com.miui.video", "com.miui.mediaeditor",
            "com.heytap.market", "com.heytap.themestore",
            "com.huawei.appmarket", "com.huawei.video",
            "com.oppo.market", "com.coloros.video"
        )
        val wellKnownContains = listOf(
            ".taobao.", ".alibaba.", ".meituan.", ".jd.", ".jingdong.", ".sankuai.",
            ".toutiao.", ".jinritoutiao.", ".douyin.", ".bytedance.",
            ".weibo.", ".sina.", ".qq.com", ".tencent.",
            ".bilibili.", ".kuaishou.", ".xunlei.", ".eleme.", ".didichuxing.",
            ".ctrip.", ".baidu.netdisk", ".wps.", ".netease.", ".163.mail",
            ".UCMobile", ".quark.", ".ximalaya.", ".autonavi.", ".amap."
        )
        val wellKnownLabelHints = listOf(
            "淘宝", "天猫", "美团", "大众点评", "京东", "京东到家", "拼多多", "唯品会",
            "今日头条", "头条", "抖音", "快手", "哔哩哔哩", "bilibili",
            "微博", "微信", "qq", "qq音乐", "腾讯视频", "爱奇艺", "优酷",
            "支付宝", "百度地图", "高德地图", "美团外卖", "饿了么",
            "滴滴", "曹操", "携程", "飞猪", "去哪儿",
            "百度网盘", "wps", "网易云音乐", "qq邮箱", "网易邮箱",
            "番茄小说", "起点", "uc浏览器", "夸克",
            "喜马拉雅", "蜻蜓fm", "迅雷"
        )
        if (wellKnownPrefixes.any { lowerPackage.startsWith(it) }) return true
        if (wellKnownContains.any { lowerPackage.contains(it) }) return true
        if (wellKnownLabelHints.any { lowerLabel.contains(it) }) return true
        return false
    }

    private fun buildThirdPartyPromoDescription(notificationRisk: NotificationRiskLevel): String {
        return when (notificationRisk) {
            NotificationRiskLevel.HIGH -> "适合治理该已安装推广 App 的通知广告、推荐流、营销入口和关联推广行为。建议关闭通知权限。"
            NotificationRiskLevel.MEDIUM -> "适合治理该已安装推广 App 的通知广告、推荐流、营销入口和关联推广行为。"
            NotificationRiskLevel.LOW -> "适合治理该已安装推广 App 的推荐流、营销入口和关联推广行为。"
        }
    }

    private fun assessNotificationRisk(lowerLabel: String, lowerPackage: String): NotificationRiskLevel {
        val highRiskKeywords = listOf(
            "资讯", "新闻", "热点", "推荐", "精选", "发现", "看看", "头条",
            "news", "hot", "feed", "recommend", "discover", "toutiao"
        )
        val activityWelfareKeywords = listOf(
            "活动", "优惠", "折扣", "秒杀", "特卖", "团购", "签到", "任务", "领奖", "抽奖", "庆典",
            "会员中心", "积分商城", "福利中心", "活动中心", "领券", "优惠券", "红包", "赚钱", "福利",
            "activity", "sale", "discount", "coupon", "bonus", "welfare", "lottery", "task",
            "member", "vip", "points", "center", "event", "campaign", "promotion"
        )
        val mediumRiskKeywords = listOf(
            "应用商店", "软件商店", "浏览器", "视频", "短剧", "直播", "漫画", "动漫",
            "游戏中心", "内容中心", "内容服务", "免费小说",
            "market", "browser", "video", "live", "comic", "anime", "gamecenter", "reward"
        )
        val appStoreKeywords = listOf(
            "应用商店", "软件商店", "应用市场", "游戏中心",
            "appstore", "appmarket", "market", "gamecenter"
        )
        val hasHighRiskKeyword = highRiskKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) }
        val hasActivityWelfareKeyword = activityWelfareKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) }
        val hasMediumRiskKeyword = mediumRiskKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) }
        val isAppStore = appStoreKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) }
        return when {
            hasHighRiskKeyword -> NotificationRiskLevel.HIGH
            isAppStore -> NotificationRiskLevel.HIGH
            hasActivityWelfareKeyword -> NotificationRiskLevel.HIGH  // 活动福利类也强制关闭
            hasMediumRiskKeyword -> NotificationRiskLevel.MEDIUM
            else -> NotificationRiskLevel.LOW
        }
    }

    private fun looksLikeThirdPartyPromoApp(lowerLabel: String, lowerPackage: String): Boolean {
        val highConfidenceLabelHints = listOf(
            "应用商店", "软件商店", "浏览器", "阅读", "小说", "免费小说", "短剧", "视频", "资讯", "新闻",
            "直播", "漫画", "动漫", "音乐", "电台", "游戏中心", "游戏盒子", "内容中心", "内容服务",
            "推荐", "精选", "热点", "发现", "看看", "赚钱", "福利", "红包", "免费", "营销", "广告",
            "活动", "优惠", "折扣", "秒杀", "特卖", "团购", "签到", "任务", "领奖", "抽奖", "庆典",
            "会员中心", "积分商城", "福利中心", "活动中心", "领券", "领券中心", "优惠券",
            "淘宝", "天猫", "美团", "京东", "拼多多", "唯品会",
            "今日头条", "头条", "抖音", "快手", "哔哩哔哩", "微博",
            "支付宝", "饿了么", "携程", "去哪儿", "百度网盘", "网易云音乐", "喜马拉雅"
        )
        val highConfidencePackageHints = listOf(
            "appstore", "market", "browser", "reader", "novel", "book", "video", "news",
            "comic", "anime", "music", "radio", "gamecenter", "gamebox", "content", "promo",
            "recommend", "discover", "hot", "reward", "benefit", "ad", "union", "feed",
            "marketing", "advert", "promotion", "live", "streaming", "mall", "shop",
            "activity", "sale", "discount", "coupon", "bonus", "welfare", "lottery", "task",
            "member", "vip", "points", "center", "event", "campaign",
            "jd.com", "jdmall", "jingdong", "sankuai", "meituan", "taobao", "tmall", "alibaba",
            "toutiao", "jinritoutiao", "douyin", "bytedance", "iesdouyin", "kuaishou", "bilibili",
            "weibo", "sina", "eleme", "ctrip", "qunar", "ximalaya",
            "android.taint", "com.android.browser", "com.android.thememanager", "com.android.filemanager"
        )
        val oemHints = listOf(
            "heytap", "coloros", "realme", "vivo", "iqoo", "oppo", "miui", "xiaomi", "redmi",
            "hyperos", "huawei", "honor", "magicui", "emui", "oneplus", "meizu", "zte", "nubia",
            "lenovo", "zuk", "samsung", "google", "android", "aosp"
        )
        val distributionHints = listOf(
            "contentcenter", "contentservice", "feed", "recommend", "discovery", "assistant",
            "gamecenter", "appstore", "appmarket", "launcherad", "adsdk", "union", "push",
            "message", "notification", "marketing", "promo", "campaign", "activity",
            "sale", "discount", "coupon", "bonus", "welfare", "lottery", "task", "member", "vip"
        )
        
        // 必须是知名第三方包名前缀才认为是可治理推广 App
        val knownThirdPartyPrefixes = listOf(
            "com.taobao.", "com.tmall.", "com.alibaba.", "com.jingdong.", "com.jd.",
            "com.meituan.", "com.sankuai.", "com.dianping.",
            "com.ss.android.", "com.iesdouyin.", "com.zhiliaoapp.",
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.qqlive",
            "com.smile.gifmaker", "com.kuaishou.", "tv.danmaku.bili",
            "com.sina.weibo", "com.xunlei.", "com.douyu.",
            "com.dragon.read", "com.qidian.".trimEnd('.'),
            "com.eg.android.", "com.mybank.", "com.eg.",
            "com.ctrip.", "com.qunar.", "com.tongcheng.",
            "com.netease.", "com.163."
        )
        val isKnownThirdParty = knownThirdPartyPrefixes.any { lowerPackage.startsWith(it) }
        
        // 高置信度：命中知名厂商包名或高置信度关键词
        val labelHighConfidence = highConfidenceLabelHints.any(lowerLabel::contains)
        val packageHighConfidence = highConfidencePackageHints.any(lowerPackage::contains)
        val oemDistributionMatched = oemHints.any(lowerPackage::contains) && distributionHints.any(lowerPackage::contains)
        
        // 只有满足以下条件才认为是可治理推广 App：
        // 1. 知名第三方厂商包名（最严格）
        // 2. 高置信度标签 + OEM 推广组件
        // 3. 纯高置信度包名关键词（不含常见系统包名）
        return isKnownThirdParty ||
               (labelHighConfidence && oemDistributionMatched) ||
               (packageHighConfidence && !lowerPackage.startsWith("com.android.") && !lowerPackage.contains("aosp"))
    }

    private fun inferPromoCategory(lowerLabel: String, lowerPackage: String): String {
        return when {
            listOf("浏览器", "browser").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "浏览器推荐"
            listOf("壁纸", "主题", "wallpaper", "theme").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "主题壁纸"
            listOf("锁屏", "lockscreen").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "锁屏推荐"
            listOf("小说", "阅读", "novel", "reader", "book").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "阅读推广"
            listOf("短剧", "视频", "video").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "视频推广"
            listOf("资讯", "新闻", "热点", "news", "hot", "头条").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "资讯推荐"
            listOf("淘宝", "京东", "美团", "拼多多", "商城", "mall", "jd.com", "jingdong", "taobao", "meituan").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "电商推广"
            listOf("饿了么", "外卖", "eleme").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "外卖推广"
            listOf("应用商店", "软件商店", "market", "appstore").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "系统推广"
            listOf("搜索", "助手", "search", "assistant").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "内容推荐"
            listOf("游戏中心", "gamecenter").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "系统推广"
            else -> "内容推荐"
        }
    }

    private fun isDisabledState(enabledState: Int): Boolean {
        return enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    private fun showPromoTargetActionDialog(target: PromoGovernTarget) {
        val status = ShizukuAdControlRepository.queryPackageStatus(this, target.packageName)
        val relatedPresets = target.relatedPresets
        val canDisable = status.installed && !isDisabledState(status.enabledState)
        val canEnable = status.installed && isDisabledState(status.enabledState)
        val canSuspend = status.installed && !status.suspended
        val canUnsuspend = status.installed && status.suspended
        val message = buildString {
            append(target.description)
            if (relatedPresets.size > 1) {
                append("\n\n同包治理标签：")
                append(relatedPresets.joinToString("、") { it.title })
            }
            ShizukuAdControlCatalog.batchProtectedReason(target.packageName)?.let { reason ->
                append("\n\n批量保护：")
                append("该项目属于")
                append(reason)
                append("，批量停用和智能治理会默认跳过，建议仅在确认风险后手动处理。")
            }
            append("\n\n来源：")
            append(target.sourceLabel)
            append("\n\n分类：")
            append(target.category)
            append("\n包名：")
            append(target.packageName)
            append("\n应用类型：")
            append(if (target.systemApp) "系统 App" else "第三方 App")
            append("\n已安装：")
            append(if (status.installed) "是" else "否")
            append("\n当前状态：")
            append(status.enabledLabel)
            append("\n暂停状态：")
            append(if (status.suspended) "已暂停" else "未暂停")
            append("\n服务状态：")
            append(if (status.alive) "已连接" else "未连接")
        }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (status.installed && (canDisable || canSuspend)) {
            actions += "智能治理" to {
                executeGovernAction {
                    val lightGoverned = ShizukuAdControlRepository.blockPackageNotifications(this@PromoGovernScopeActivity, target.packageName)
                    val disableRequested = if (!lightGoverned && canDisable) {
                        ShizukuAdControlRepository.disablePackage(this@PromoGovernScopeActivity, target.packageName)
                    } else {
                        false
                    }
                    val disabledStatus = queryPackageStatus(target.packageName)
                    val disableSuccess = isDisabledState(disabledStatus.enabledState)
                    if (lightGoverned) {
                        "治理成功，当前已关闭推送广告能力"
                    } else if (disableSuccess) {
                        "治理成功，当前已停用"
                    } else {
                        val suspendRequested = if (!disabledStatus.suspended && canSuspend) {
                            ShizukuAdControlRepository.suspendPackage(this@PromoGovernScopeActivity, target.packageName)
                        } else {
                            false
                        }
                        val suspendStatus = queryPackageStatus(target.packageName)
                        val suspendSuccess = suspendRequested && suspendStatus.suspended
                        if (suspendSuccess) "停用未生效，已自动回退为暂停" else "治理失败，请确认系统支持停用或暂停"
                    }
                }
            }
        }
        if (status.installed) {
            actions += "关闭推送广告" to {
                executePackageToggleAction(
                    packageName = target.packageName,
                    actionLabel = "关闭推送广告",
                    successMessage = "关闭推送广告成功",
                    failureMessage = "关闭推送广告失败，请确认系统支持通知权限治理"
                ) {
                    ShizukuAdControlRepository.blockPackageNotifications(this@PromoGovernScopeActivity, it)
                }
            }
            actions += "恢复推送广告" to {
                executePackageToggleAction(
                    packageName = target.packageName,
                    actionLabel = "恢复推送广告",
                    successMessage = "恢复推送广告成功",
                    failureMessage = "恢复推送广告失败，请确认系统支持通知权限治理"
                ) {
                    ShizukuAdControlRepository.allowPackageNotifications(this@PromoGovernScopeActivity, it)
                }
            }
        }
        if (canDisable) {
            actions += "停用" to {
                executePackageStateAction(
                    packageName = target.packageName,
                    actionLabel = "停用",
                    successMessage = "停用成功",
                    failureMessage = "停用失败，请确认该项目支持停用",
                    request = { ShizukuAdControlRepository.disablePackage(this@PromoGovernScopeActivity, it) },
                    verify = { refreshed -> isDisabledState(refreshed.enabledState) }
                )
            }
        }
        if (canEnable) {
            actions += "恢复" to {
                executePackageStateAction(
                    packageName = target.packageName,
                    actionLabel = "恢复",
                    successMessage = "恢复成功",
                    failureMessage = "恢复失败，请确认该项目仍然存在",
                    request = { ShizukuAdControlRepository.enablePackage(this@PromoGovernScopeActivity, it) },
                    verify = { refreshed ->
                        refreshed.enabledState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                            refreshed.enabledState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    }
                )
            }
        }
        if (canSuspend || canUnsuspend) {
            actions += (if (status.suspended) "恢复暂停" else "暂停") to {
                val actionText = if (status.suspended) "恢复暂停" else "暂停"
                executePackageStateAction(
                    packageName = target.packageName,
                    actionLabel = actionText,
                    successMessage = "${actionText}成功",
                    failureMessage = "${actionText}失败，请确认系统支持该操作",
                    request = {
                        if (status.suspended) {
                            ShizukuAdControlRepository.unsuspendPackage(this@PromoGovernScopeActivity, it)
                        } else {
                            ShizukuAdControlRepository.suspendPackage(this@PromoGovernScopeActivity, it)
                        }
                    },
                    verify = { refreshed -> if (status.suspended) !refreshed.suspended else refreshed.suspended }
                )
            }
        }
        if (status.installed) {
            actions += "组件治理" to {
                showComponentGovernDialog(target)
            }
        }
        if (actions.isEmpty()) {
            showOperationResult("当前项目暂无可执行治理动作，请先确认目标应用已安装且 Shizuku 服务状态正常")
            return
        }
        StableDialog.builder(this)
            .setTitle(target.title)
            .setMessage(message)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton("关闭", null)
            .showSafely(this, "Show govern target actions dialog failed")
    }

    private fun showComponentGovernDialog(target: PromoGovernTarget) {
        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.Default) {
                discoverPromoComponentCandidates(target.packageName)
            }
            if (isFinishing || isDestroyed) return@launch
            if (candidates.isNotEmpty()) {
                StableDialog.builder(this@PromoGovernScopeActivity)
                    .setTitle("组件治理")
                    .setMessage("已识别 ${candidates.size} 个高相关组件，可直接选择治理，也可以进入手动输入。")
                    .setItems(candidates.map { candidate ->
                        val state = if (candidate.enabled) "启用中" else "已停用"
                        "[${candidate.typeLabel}/$state] ${candidate.shortName}"
                    }.toTypedArray()) { _, which ->
                        showComponentActionDialog(target, candidates[which].componentName)
                    }
                    .setNeutralButton("手动输入") { _, _ ->
                        showManualComponentGovernDialog(target, candidates.firstOrNull()?.componentName.orEmpty())
                    }
                    .setNegativeButton("取消", null)
                    .showSafely(this@PromoGovernScopeActivity, "Show component candidates dialog failed")
                return@launch
            }
            showManualComponentGovernDialog(target, "${target.packageName}/")
        }
    }

    private fun showComponentActionDialog(target: PromoGovernTarget, componentName: String) {
        StableDialog.builder(this)
            .setTitle("组件治理")
            .setMessage(componentName)
            .setPositiveButton("停用组件") { _, _ ->
                executeComponentToggleAction(
                    componentName = componentName,
                    disable = true
                )
            }
            .setNeutralButton("恢复组件") { _, _ ->
                executeComponentToggleAction(
                    componentName = componentName,
                    disable = false
                )
            }
            .setNegativeButton("手动输入") { _, _ ->
                showManualComponentGovernDialog(target, componentName)
            }
            .showSafely(this, "Show component actions dialog failed")
    }

    private fun showManualComponentGovernDialog(target: PromoGovernTarget, initialValue: String) {
        val input = EditText(this).apply {
            hint = "输入完整组件名，如 ${target.packageName}/.SplashActivity"
            setText(initialValue.ifBlank { "${target.packageName}/" })
            setSelection(text.length)
        }
        val dialog = StableDialog.builder(this)
            .setTitle("组件治理")
            .setMessage("适合处理启动页 Activity、推荐页 Activity、广告 Service、推送 Receiver 等单个组件。请输入完整组件名后选择动作。")
            .setView(input)
            .setPositiveButton("停用组件", null)
            .setNeutralButton("恢复组件", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (runManualComponentAction(input, disable = true)) {
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (runManualComponentAction(input, disable = false)) {
                    dialog.dismiss()
                }
            }
        }
        dialog.showSafely(this, "Show manual component govern dialog failed") ?: return
    }

    private fun runManualComponentAction(input: EditText, disable: Boolean): Boolean {
        val componentName = input.text?.toString().orEmpty().trim()
        if (componentName.isBlank() || !componentName.contains('/')) {
            showShortToast("请输入完整组件名")
            return false
        }
        executeComponentToggleAction(componentName, disable)
        return true
    }

    private fun executePackageToggleAction(
        packageName: String,
        actionLabel: String,
        successMessage: String,
        failureMessage: String,
        action: (String) -> Boolean
    ) {
        val target = allTargets.find { it.packageName == packageName }
        if (target != null && assessRiskLevel(target, actionLabel) != RiskLevel.LOW) {
            showRiskConfirmationDialog(target, actionLabel) {
                executeGovernAction {
                    val success = action(packageName)
                    if (success) successMessage else failureMessage
                }
            }
        } else {
            executeGovernAction {
                val success = action(packageName)
                if (success) successMessage else failureMessage
            }
        }
    }

    private fun executeComponentToggleAction(componentName: String, disable: Boolean) {
        executeGovernAction {
            val success = if (disable) {
                ShizukuAdControlRepository.disableComponent(this@PromoGovernScopeActivity, componentName)
            } else {
                ShizukuAdControlRepository.enableComponent(this@PromoGovernScopeActivity, componentName)
            }
            val actionText = if (disable) "组件停用" else "组件恢复"
            if (success) "${actionText}成功" else "${actionText}失败，请确认组件名完整且系统支持该操作"
        }
    }

    private fun executePackageStateAction(
        packageName: String,
        actionLabel: String,
        successMessage: String,
        failureMessage: String,
        request: (String) -> Boolean,
        verify: (ShizukuAdControlRepository.PackageControlStatus) -> Boolean
    ) {
        val target = allTargets.find { it.packageName == packageName }
        if (target != null && assessRiskLevel(target, actionLabel) != RiskLevel.LOW) {
            showRiskConfirmationDialog(target, actionLabel) {
                executeGovernAction {
                    val requested = request(packageName)
                    val refreshed = queryPackageStatus(packageName)
                    if (requested && verify(refreshed)) successMessage else failureMessage
                }
            }
        } else {
            executeGovernAction {
                val requested = request(packageName)
                val refreshed = queryPackageStatus(packageName)
                if (requested && verify(refreshed)) successMessage else failureMessage
            }
        }
    }

    private fun queryPackageStatus(packageName: String): ShizukuAdControlRepository.PackageControlStatus {
        return ShizukuAdControlRepository.queryPackageStatus(this@PromoGovernScopeActivity, packageName)
    }

    private fun discoverPromoComponentCandidates(packageName: String): List<PromoComponentCandidate> {
        val packageInfo = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES)
        }.getOrNull() ?: return emptyList()
        val candidates = mutableListOf<PromoComponentCandidate>()
        packageInfo.activities.orEmpty().forEach { info ->
            val fullName = info.name.orEmpty()
            val shortName = fullName.substringAfterLast('.')
            val score = promoComponentScore(fullName.lowercase(), "Activity")
            if (score > 0) candidates += PromoComponentCandidate(fullName, shortName, "Activity", info.isEnabled, score)
        }
        packageInfo.receivers.orEmpty().forEach { info ->
            val fullName = info.name.orEmpty()
            val shortName = fullName.substringAfterLast('.')
            val score = promoComponentScore(fullName.lowercase(), "Receiver")
            if (score > 0) candidates += PromoComponentCandidate(fullName, shortName, "Receiver", info.isEnabled, score)
        }
        packageInfo.services.orEmpty().forEach { info ->
            val fullName = info.name.orEmpty()
            val shortName = fullName.substringAfterLast('.')
            val score = promoComponentScore(fullName.lowercase(), "Service")
            if (score > 0) candidates += PromoComponentCandidate(fullName, shortName, "Service", info.isEnabled, score)
        }
        return candidates.sortedWith(compareByDescending<PromoComponentCandidate> { it.score }.thenBy { it.shortName }).take(20)
    }

    private fun promoComponentScore(lowerName: String, typeLabel: String): Int {
        var score = 0
        val strongHints = listOf(
            "splash", "startup", "launchad", "advert", "adactivity", "adservice", "adreceiver",
            "push", "recommend", "promo", "feedad", "reward", "interstitial", "union"
        )
        val moderateHints = listOf(
            "guide", "popup", "notice", "message", "operation", "market", "discover", "hot", "brand"
        )
        strongHints.forEach { if (lowerName.contains(it)) score += 3 }
        moderateHints.forEach { if (lowerName.contains(it)) score += 1 }
        if (typeLabel == "Activity" && listOf("splash", "startup", "launch", "ad").any(lowerName::contains)) score += 2
        if (typeLabel == "Receiver" && listOf("push", "alarm", "recommend", "ad").any(lowerName::contains)) score += 2
        if (typeLabel == "Service" && listOf("push", "ad", "recommend", "job").any(lowerName::contains)) score += 2
        return score
    }

    private fun executeGovernAction(block: () -> String) {
        lifecycleScope.launch {
            if (!ensureShizukuReady()) return@launch
            val message = try {
                withContext(Dispatchers.IO) {
                    warmShizukuServicesBlocking()
                    runCatching {
                        block()
                    }.getOrElse { e ->
                        LogRepository.append(this@PromoGovernScopeActivity, "Govern action failed: ${e.message ?: e.javaClass.simpleName}")
                        "操作失败：${e.message ?: "未知错误"}"
                    }
                }
            } catch (e: Exception) {
                LogRepository.append(this@PromoGovernScopeActivity, "Govern action execution failed: ${e.message ?: e.javaClass.simpleName}")
                "操作执行失败：${e.message ?: "请检查 Shizuku 服务状态"}"
            }
            if (isFinishing || isDestroyed) return@launch
            refreshTargetsSilently()
            showOperationResult(message)
        }
    }

    private fun assessRiskLevel(target: PromoGovernTarget, action: String): RiskLevel {
        return when {
            target.packageName in SYSTEM_CRITICAL_APPS -> RiskLevel.HIGH
            target.systemApp && (action.contains("停用") || action.contains("卸载")) -> RiskLevel.HIGH
            target.systemApp && action.contains("暂停") -> RiskLevel.MEDIUM
            action.contains("卸载") -> RiskLevel.HIGH
            target.systemApp -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    private fun showRiskConfirmationDialog(
        target: PromoGovernTarget,
        action: String,
        onConfirm: () -> Unit
    ) {
        val riskLevel = assessRiskLevel(target, action)
        val message = when (riskLevel) {
            RiskLevel.HIGH -> buildString {
                append("⚠️ 高风险操作提示\n\n")
                append("应用：${target.title}\n")
                append("包名：${target.packageName}\n")
                append("操作：$action\n\n")
                if (target.packageName in SYSTEM_CRITICAL_APPS) {
                    append("警告：这是系统核心应用，操作可能导致系统不稳定或功能异常。\n\n")
                } else if (action.contains("卸载")) {
                    append("警告：卸载将删除用户数据且不可恢复。\n\n")
                } else {
                    append("警告：停用系统应用可能导致部分功能不可用。\n\n")
                }
                append("建议：操作前请确认已了解风险，必要时请先备份数据。\n\n")
                append("确认继续？")
            }
            RiskLevel.MEDIUM -> buildString {
                append("⚠️ 中等风险操作提示\n\n")
                append("应用：${target.title}\n")
                append("操作：$action\n\n")
                append("此操作可能影响部分系统功能，确认继续？")
            }
            RiskLevel.LOW -> "确定要对「${target.title}」执行 ${action} 吗？"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("操作确认")
            .setMessage(message)
            .setPositiveButton("确认") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showOperationResult(message: String) {
        NetworkKernel.reloadIfRunning(this)
        val operationSummary = ShizukuAdControlRepository.getLastOperationSummary(this)
            .takeIf { it.isNotBlank() && it != "idle" }
        StableDialog.builder(this)
            .setMessage(
                buildString {
                    append(message)
                    operationSummary?.let {
                        append("\n\n服务反馈：")
                        append(it)
                    }
                }
            )
            .setPositiveButton("确定", null)
            .showSafely(this, "Show govern result dialog failed")
    }

    private inner class PromoGovernTargetAdapter(
        private val onClick: (PromoGovernTarget) -> Unit
    ) : ListAdapter<PromoGovernTarget, PromoGovernTargetAdapter.ViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemPromoGovernTargetBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(private val binding: ItemPromoGovernTargetBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: PromoGovernTarget) {
                binding.titleText.text = item.title
                binding.metaText.text = listOf(item.category, item.sourceLabel, item.packageName).joinToString(" | ")
                binding.descText.text = item.description
                binding.stateText.text = buildStateText(item)
                binding.root.isClickable = true
                binding.root.isFocusable = false
                binding.root.setOnClickListener { 
                    showShortToast("点击条目：${item.title}")
                    onClick(item) 
                }
                val status = item.packageStatus
                if (status.installed) {
                    binding.btnGovern.isEnabled = true
                    binding.btnGovern.isClickable = true
                    binding.btnGovern.alpha = 1f
                    binding.btnGovern.setOnClickListener { v ->
                        v.isPressed = true
                        showShortToast("点击治理：${item.title}")
                        showPromoTargetActionDialog(item)
                    }
                } else {
                    binding.btnGovern.isEnabled = false
                    binding.btnGovern.isClickable = false
                    binding.btnGovern.alpha = 0.5f
                    binding.btnGovern.setOnClickListener(null)
                }
            }
        }
    }

    private fun buildStateText(target: PromoGovernTarget): String {
        return when {
            target.packageStatus.suspended -> "已暂停"
            isDisabledState(target.packageStatus.enabledState) -> "已停用"
            else -> if (target.systemApp) "系统" else "第三方"
        }
    }

    private enum class PromoGovernScope(val label: String) {
        ALL("全部"),
        SYSTEM_ONLY("系统推广项"),
        THIRD_PARTY_ONLY("第三方推广 App")
    }

    private data class PromoGovernTarget(
        val packageName: String,
        val title: String,
        val category: String,
        val description: String,
        val sourceLabel: String,
        val systemApp: Boolean,
        val relatedPresets: List<ShizukuAdControlCatalog.Preset>,
        val packageStatus: ShizukuAdControlRepository.PackageControlStatus
    )

    private data class PromoComponentCandidate(
        val componentName: String,
        val shortName: String,
        val typeLabel: String,
        val enabled: Boolean,
        val score: Int
    )

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, PromoGovernScopeActivity::class.java)

        private val DIFF = object : DiffUtil.ItemCallback<PromoGovernTarget>() {
            override fun areItemsTheSame(oldItem: PromoGovernTarget, newItem: PromoGovernTarget): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: PromoGovernTarget, newItem: PromoGovernTarget): Boolean {
                return oldItem == newItem
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
