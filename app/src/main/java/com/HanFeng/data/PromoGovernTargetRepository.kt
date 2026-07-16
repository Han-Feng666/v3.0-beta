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
            "com.ss.android.ugc.bubble", "com.ss.android.ugc.live", "com.ss.android.lark",
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.qqlive", "com.tencent.qqmusic",
            "com.tencent.tmgp", "com.tencent.game", "com.tencent.ig", "com.tencent.tmgp.pubgmhd",
            "com.sina.weibo", "com.eleme.", "com.ctrip.", "com.baidu.searchbox",
            "com.baidu.input", "com.baidu.browser", "com.baidu.maps",
            "com.dragon.read", "com.qidian.", "com.UCMobile", "com.uc.", "com.quark.",
            "com.heytap.market", "com.heytap.themestore", "com.huawei.appmarket",
            "com.vipshop", "com.vip.", "com.sec.android.app.kidstoys",
            "com.fanqie", "com.shuqi", "com.xiaoshuo", "com.reading",
            "com.xiaomi.market", "com.huawei.appgallery", "com.oppo.market",
            "com.netease.cloudmusic", "music.163.com",
            "com.smile.gifmaker", "com.kuaishou.nebula", "com.kuaishou.handwriting",
            "tv.danmaku.bili", "com.bilibili.", "com.moji.", "com.moji.android",
            "com.zhangshang", "com.tianqi", "com.android.browser",
            "com.android.calendar", "com.android.thememanager", "com.android.deskclock",
            "com.iflytek.", "com.ximalaya.", "com.qingting.", "com.dingtalk.",
            "com.lagou.", "com.zhipin.", "com.51job.", "com.zhilian.",
            "com.autonavi.", "com.amap.", "com.gaode.", "com.google.maps.",
            "com.sohu.", "com.sohu.news", "com.ifeng.", "com.thepaper.",
            "com.youku.", "com.iqiyi.", "com.tencent.peng", "com.le",
            "com.mgtv.", "com.pptv.", "com.tv.", "com.ku6.",
            "com.douban.", "com.zhihu.", "com.xiaohongshu.", "com.deyu.",
            "com.jiemian.", "com.nearme.", "com.coloros.", "com.oppo.",
            "com.vivo.", "com.bbk.", "com.meizu.", "com.flyme.",
            "com.oneplus.", "com.oxygenos.", "com.hydrogenos.",
            "com.samsung.", "com.samsung.android.", "com.sec.",
            "com.huawei.", "com.hihonor.", "com.honor.",
            "com.xiaomi.", "com.miui.", "com.mi.",
            "com.oppo.", "com.realme.", "com.realme.",
            "com.vivo.", "com.funtouch.", "com.originos.",
            "com.pinduoduo.", "com.xunmeng.", "com.mama.",
            "com.didiglobal.", "com.didichuxing.", "com.uber.",
            "com.t3go.", "com.t3.", "com.caocao.", "com.gocabs.",
            "com.58.", "com.ganji.", "com.anjuke.", "com.lianjia.", "com.ke.",
            "com.tongcheng.", "com.qunar.", "com.fliggy.", "com.mafengwo.",
            "com.12306.", "com.cainiao.", "com.yto.", "com.zto.", "com.sto.",
            "com.yuantong.", "com中通.", "com.shunfeng.", "com.sf.",
            "com.jd.logistics.", "com.danjuan.", "com.tiantian.", "com.xueqiu.",
            "com.antfortune.", "com.alipay.", "com.yu Ebao.", "com.tiantianfund.",
            "com.boc.", "com.icbc.", "com.ccb.", "com.abc.", "com.bankcomm.",
            "com.cmb.", "com.cmbchina.", "com.pingan.", "com.webank.",
            "com.mybank.", "com.netease.", "com.163.", "com.126.",
            "com.sohu.", "com.163.mail.", "com.qq.mail.", "com.139.",
            "com.tencent.edu.", "com.yuanfudao.", "com.zuoyebang.", "com.100.",
            "com.neworiental.", "com.gaotu.", "com.fenbi.", "com.duiyi.",
            "com.keep.", "com.nike.", "com.adidas.", "com.lululemon.",
            "com.dianping.", "com.meituan.retail.", "com.meituan.grocery.",
            "com.hema.", "com.freshippo.", "com.rt.", "com.wm.",
            "com.suning.", "com.gome.", "com.yhd.", "com.vip.",
            "com.amazon.", "com.ebay.", "com.wish.", "com.shopify.",
            "com.google.android.apps.maps", "com.google.android.youtube",
            "com.facebook.katana", "com.instagram.android", "com.twitter.android",
            "com.netflix.mediaclient", "com.spotify.music", "com.disney.disneyplus",
            "com.hbo.", "com.primevideo.", "com.hulu.",
            "com.tiktok.", "com.bytedance.", "com.bytecdn.", "com.pangle.",
            "com.gdt.", "com.qq.gdt.", "com.tencent.ads.",
            "com.umeng.", "com.umsns.", "com.umeng.analytics.",
            "com.jpush.", "com.igexin.", "com.getui.",
            "com.rongcloud.", "com.easemob.", "com.tencent.rtc.",
            "com.agora.", "com.zego.", "com.voicecloud."
        )
        val labelHints = listOf(
            "淘宝", "天猫", "美团", "大众点评", "京东", "拼多多", "唯品会",
            "今日头条", "头条", "抖音", "快手", "微博", "微信", "qq", "支付宝",
            "百度地图", "高德地图", "美团外卖", "饿了么", "携程", "飞猪", "百度网盘",
            "wps", "网易云音乐", "番茄小说", "起点", "uc 浏览器", "夸克", "喜马拉雅",
            "书旗小说", "七猫小说", "掌阅", "咪咕阅读", "qq 阅读", "微信读书",
            "得物", "小红书", "豆瓣", "知乎", "b 站", "哔哩哔哩", "汽水音乐",
            "应用商店", "软件商店", "游戏中心", "手机商店",
            "抖音极速", "快手极速", "头条极速", "百度极速", "qq 极速",
            "西瓜视频", "火山视频", "皮皮虾", "懂车帝", "悟空问答",
            "抖音盒子", "飞书", "轻颜相机", "剪映", "醒图", "即创",
            "番茄免费", "番茄畅听", "番茄小说", "番茄短篇",
            "起点读书", "起点中文", "潇湘书院", "红袖添香", "云起书院",
            "qq 音乐", "酷狗音乐", "酷我音乐", "全民 k 歌", "网易云",
            "汽水音乐", "波点音乐", "铃声多多", "喜马拉雅", "蜻蜓 fm",
            "懒人听书", "多看阅读", "掌阅 ireader", "咪咕阅读", "微信读书",
            "uc 浏览器", "夸克浏览器", "qq 浏览器", "360 浏览器", "搜狗浏览器",
            "2345 浏览器", "猎豹浏览器", "百度浏览器", "华为浏览器", "小米浏览器",
            "oppo 浏览器", "vivo 浏览器", "三星浏览器", "edge 浏览器",
            "高德地图", "百度地图", "腾讯地图", "搜狗地图", "凯立德",
            "滴滴出行", "高德打车", "美团打车", "曹操出行", "t3 出行",
            "首汽约车", "神州专车", "uber", "嘀嗒出行", "哈啰出行",
            "美团单车", "哈啰单车", "青桔单车", "永安行",
            "去哪儿", "飞猪旅行", "马蜂窝", "穷游", "同程旅行",
            "铁路 12306", "航旅纵横", "航班管家", "携程旅行",
            "菜鸟裹裹", "快递 100", "丰巢", "顺丰速运", "中通快递",
            "圆通速递", "申通快递", "韵达快递", "极兔速递", "京东物流",
            "饿了么", "美团外卖", "百度外卖", "肯德基", "麦当劳", "必胜客",
            "盒马", "叮咚买菜", "每日优鲜", "美团买菜", "京东到家",
            "多点", "永辉生活", "大润发", "沃尔玛", "家乐福",
            "贝壳找房", "链家", "安居客", "58 同城", "赶集网", "房天下",
            "Keep", "悦跑圈", "咕咚", "薄荷健康", "美柚", "大姨妈",
            "支付宝", "微信支付", "云闪付", "京东支付", "美团支付",
            "招商银行", "工商银行", "建设银行", "农业银行", "中国银行",
            "交通银行", "邮储银行", "平安银行", "中信银行", "浦发银行",
            "民生银行", "广发银行", "兴业银行", "光大银行",
            "东方财富", "同花顺", "雪球", "天天基金", "蚂蚁财富",
            "涨乐财富通", "华泰证券", "中信证券", "国泰君安",
            "作业帮", "猿辅导", "学而思", "新东方", "高途", "粉笔",
            "网易有道", "百词斩", "扇贝", "流利说", "多邻国",
            "大众点评", "口碑", "美味不用等", "开开",
            "得物", "识货", "毒", "Nice", "闲鱼", "转转",
            "爱奇艺", "腾讯视频", "优酷", "芒果 tv", "哔哩哔哩",
            "搜狐视频", "乐视视频", "pptv", "西瓜视频", "抖音",
            "知乎", "豆瓣", "简书", "小红书", "贴吧",
            "微信", "qq", "钉钉", "飞书", "企业微信", "钉钉",
            "微博", "绿洲", "微视", "快手", "抖音",
            "天气通", "墨迹天气", "彩云天气", "中国天气", "万年历",
            "日历", "闹钟", "时钟", "计算器", "文件管理", "录音机",
            "指南针", "手电筒", "相机", "相册", "图库", "视频播放器",
            "音乐播放器", "浏览器", "应用商店", "游戏中心", "主题商店",
            "钱包", "卡包", "优惠券", "会员卡", "积分", "签到",
            "活动", "福利", "红包", "抽奖", "秒杀", "拼团", "砍价",
            "领券", "折扣", "促销", "特惠", "优惠", "满减", "返现",
            "赚", "免费", "0 元", "一元", "九块九", "包邮",
            "赚钱", "提现", "佣金", "返利", "推广", "邀请", "分享",
            "任务", "签到", "打卡", "连续", "翻倍", "加倍",
            "会员", "vip", "svip", "黄金会员", "钻石会员", "铂金会员",
            "积分商城", "兑换", "礼品", "奖品", "实物", "现金奖励"
        )
        return wellKnownPrefixes.any { lowerPackage.startsWith(it) } || labelHints.any { lowerLabel.contains(it) }
    }

    private fun isLikelyOemPromoPackage(packageName: String, label: String): Boolean {
        val lowerPackage = packageName.lowercase()
        val lowerLabel = label.lowercase()
        val vendorHints = listOf(
            "miui", "xiaomi", "mipicks", "mi", "miphone",
            "heytap", "oppo", "coloros", "realme", "oneplus", "oxygenos", "hydrogenos",
            "vivo", "bbk", "funtouch", "originos", "iqoo",
            "huawei", "honor", "hihonor", "harmonyos", "emui", "magicui",
            "samsung", "oneui", "galaxy", "sec",
            "meizu", "flyme", "mzero",
            "sony", "xperia", "somx",
            "lg", "lge",
            "motorola", "moto", "lenovo",
            "nokia", "hmd",
            "asus", "rog", "zenfone",
            "htc",
            "zte", "nubia", "redmagic",
            "coolpad", "gionee", "letv", "leeco",
            "360", "qiku",
            "smartisan", "nut",
            "blackshark", "pocophone", "poco",
            "nothing", "essential",
            "redmi", "poco"
        )
        val promoPackageHints = listOf(
            "appstore", "market", "appmarket", "appgallery", "gamecenter", "gamestore",
            "browser", "web", "internet",
            "theme", "wallpaper", "pictorial", "wallpaper", "lockscreen",
            "content", "reader", "news", "feed", "recommend", "discover",
            "assistant", "voice", "ai", "smart", "intelligence",
            "quicksearch", "search", "globalsearch",
            "ad", "ads", "advert", "promotion", "promo", "marketing",
            "video", "music", "audio", "player",
            "weather", "calendar", "clock", "alarm", "calculator",
            "filemanager", "files", "recorder", "voice", "sound",
            "compass", "flashlight", "torch", "mirror",
            "health", "fitness", "sport", "step", "heart",
            "wallet", "pay", "card", "nfc",
            "remote", "ir", "infrared", "tv", "ac",
            "cleaner", "boost", "security", "antivirus", "phone", "manager",
            "backup", "restore", "sync", "cloud", "drive",
            "gallery", "photo", "camera", "screenshot",
            "music", "sound", "audio", "equalizer",
            "settings", "control", "center", "panel",
            "launcher", "desktop", "home", "screen",
            "push", "message", "notify", "notification",
            "update", "upgrade", "systemupdate", "ota",
            "feedback", "service", "support", "help", "guide",
            "shop", "store", "mall", "buy", "purchase",
            "coupon", "voucher", "discount", "deal", "offer",
            "game", "play", "gaming", "esports",
            "reading", "book", "novel", "comic", "manga",
            "shortvideo", "short", "clip", "live", "streaming",
            "social", "chat", "message", "community",
            "travel", "map", "navigate", "navigation", "gps",
            "shopping", "ecommerce", "e-commerce", "retail",
            "food", "delivery", "takeaway", "order",
            "hotel", "flight", "train", "ticket", "booking",
            "ride", "taxi", "car", "bike", "scooter",
            "finance", "bank", "investment", "stock", "fund", "insurance",
            "education", "learning", "course", "class", "study", "exam",
            "lifestyle", "fashion", "beauty", "health", "diet", "weight",
            "dating", "match", "social", "friend", "love",
            "job", "career", "recruit", "hire", "work", "resume",
            "house", "rent", "buy", "property", "real", "estate",
            "express", "logistics", "shipping", "delivery", "package",
            "systemad", "oem", "preinstall", "bloatware", "bloat"
        )
        val promoLabelHints = listOf(
            "应用商店", "软件商店", "游戏中心", "游戏商店", "应用市场", "应用汇",
            "浏览器", "网页", "上网", "搜索", "全球搜索",
            "主题", "壁纸", "画报", "锁屏", "桌面", " launcher",
            "内容", "阅读", "资讯", "新闻", "推荐", "发现", "精选", "热点", "头条",
            "助手", "语音助手", "智能助理", "ai 助手", "小爱", "小艺", "小布", "jovi", "breeno",
            "视频", "音乐", "音频", "播放器", "收音机",
            "天气", "日历", "时钟", "闹钟", "计算器",
            "文件管理", "文件", "录音机", "录音", "声音",
            "指南针", "手电筒", "镜子", "水平仪",
            "健康", "运动", "计步", "心率", "睡眠",
            "钱包", "支付", "卡包", "nfc", "门禁卡",
            "遥控", "红外", "电视", "空调", "智能家居",
            "清理", "加速", "安全", "杀毒", "手机管家", "优化",
            "备份", "恢复", "同步", "云盘", "云服务",
            "相册", "照片", "相机", "截图", "图库",
            "设置", "控制中心", "通知中心", "快捷面板",
            "推送", "消息", "通知", "提醒",
            "更新", "升级", "系统更新",
            "反馈", "服务", "帮助", "指南", "教程",
            "商城", "商店", "购物", "购买",
            "优惠券", "折扣", "特价", "活动", "促销",
            "游戏", "电竞", "开黑",
            "小说", "漫画", "书籍", "阅读",
            "短视频", "直播", "视频通话",
            "社交", "聊天", "社区", "论坛",
            "旅行", "地图", "导航", " gps",
            "外卖", "订餐", "点餐", "美食",
            "酒店", "机票", "火车票", "门票", "预订",
            "打车", "租车", "骑车", "出行",
            "理财", "银行", "股票", "基金", "保险", "投资",
            "教育", "学习", "课程", "课堂", "考试", "培训",
            "生活", "时尚", "美容", "健康", "减肥", "健身",
            "交友", "相亲", "恋爱", "约会",
            "招聘", "求职", "工作", "简历", "面试",
            "房产", "租房", "买房", "物业",
            "快递", "物流", "包裹", "寄件",
            "系统预装", "厂商", "预装", "内置",
            "福利", "红包", "签到", "任务", "积分", "兑换",
            "会员", "vip", "特权", "专属",
            "广告", "推广", "营销", "运营", "活动页", "落地页",
            "开屏", "启动页", " splash", "插屏", "横幅", "浮窗",
            "弹窗", "提示", "引导", "新手", "教程",
            "推送广告", "通知广告", "锁屏广告", "桌面广告",
            "负一屏", "智能推荐", "个性化推荐", "算法推荐",
            "内容分发", "信息流", "feed 流", "推荐流"
        )
        val vendorMatch = vendorHints.any { lowerPackage.contains(it) }
        val promoPackageMatch = promoPackageHints.any { lowerPackage.contains(it) }
        val promoLabelMatch = promoLabelHints.any { lowerLabel.contains(it) }
        val packageMatch = vendorMatch && promoPackageMatch
        return packageMatch || promoLabelMatch
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
            listOf("浏览器", "browser", "web", "internet").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "浏览器推荐"
            listOf("壁纸", "主题", "wallpaper", "theme", "美化").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "主题壁纸"
            listOf("锁屏", "lockscreen", "lock").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "锁屏推荐"
            listOf("小说", "阅读", "novel", "reader", "book", "读书", "看书", "追书").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "阅读推广"
            listOf("短剧", "视频", "video", "电影", "电视剧", "综艺").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "视频推广"
            listOf("资讯", "新闻", "热点", "news", "hot", "头条", "头条", "推荐", "发现").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "资讯推荐"
            listOf("直播", "live", "streaming").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "直播推广"
            listOf("漫画", "comic", "manga", "动漫", "二次元").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "漫画推广"
            listOf("音乐", "music", "audio", "歌曲", "铃声").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "音乐推广"
            listOf("游戏", "game", "gaming", "电竞").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "游戏推广"
            listOf("淘宝", "京东", "美团", "拼多多", "商城", "mall", "jingdong", "taobao", "meituan", "电商", "购物").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "电商推广"
            listOf("饿了么", "外卖", "eleme", "餐饮", "美食").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "外卖推广"
            listOf("旅行", "旅游", "travel", "携程", "去哪儿", "飞猪").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "旅行推广"
            listOf("打车", "出行", "ride", "taxi", "滴滴", "高德打车").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "出行推广"
            listOf("金融", "理财", "finance", "投资", "股票", "基金").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "金融推广"
            listOf("教育", "学习", "education", "课程", "培训", "考试").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "教育推广"
            listOf("健康", "医疗", "health", "健身", "运动", "减肥").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "健康推广"
            listOf("社交", "交友", "social", "聊天", " dating").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "社交推广"
            listOf("招聘", "求职", "job", "工作", "简历").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "招聘推广"
            listOf("房产", "租房", "house", "买房", "物业").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "房产推广"
            listOf("应用商店", "软件商店", "market", "appstore", "游戏中心").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "系统推广"
            listOf("工具", "utility", "cleaner", "boost", "security").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "工具推广"
            listOf("天气", "weather", "日历", "calendar", "时钟", "clock", "闹钟", "calculator", "计算器").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "系统工具推广"
            listOf("拍照", "相机", "camera", "photo", "图片", "gallery", "美图", "美颜").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "拍照推广"
            listOf("地图", "导航", "map", "gps", "高德", "百度地图").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "地图导航推广"
            else -> "内容推荐"
        }
    }

    private fun assessNotificationRisk(lowerLabel: String, lowerPackage: String): NotificationRiskLevel {
        val criticalRiskKeywords = listOf(
            "push", "推送", "通知", "提醒", "消息中心", "messagecenter", "notification",
            "营销", "marketing", "运营", "operation", "推广", "promotion", "advert",
            "内容分发", "contentdelivery", "信息流", "feed", "推荐流", "recommendfeed"
        )
        val highRiskKeywords = listOf(
            "资讯", "新闻", "热点", "推荐", "精选", "发现", "头条", "news", "hot",
            "recommend", "discover", "toutiao", "feed", "trending", "viral",
            "爆款", "热搜", "热搜榜", "排行榜", "榜单", "top", "ranking",
            "关注", "粉丝", "动态", "timeline", "moment", "circle",
            "社区", "论坛", "圈子", "community", "forum", "group",
            "直播", "主播", "打赏", "礼物", "live", "streamer", "tip", "gift"
        )
        val activityKeywords = listOf(
            "活动", "优惠", "折扣", "秒杀", "特卖", "团购", "签到", "任务", "领奖",
            "抽奖", "福利", "红包", "赚钱", "coupon", "bonus", "welfare", "lottery",
            "promotion", "sale", "discount", "deal", "offer", "event", "campaign",
            "限时", "限量", "抢购", "拼团", "砍价", "满减", "返现", "补贴",
            "领券", "优惠券", "代金券", "满减券", "折扣券", "免单", "0元购",
            "邀请", "拉新", "裂变", "分享", "转发", "助力", "加速", "助力",
            "积分", "签到", "打卡", "连续", "翻倍", "加倍", " multiplier",
            "会员", "vip", "svip", "特权", "专属", "premium", "exclusive",
            "赚", "提现", "佣金", "返利", "收益", "income", "cashout", "earn"
        )
        val mediumRiskKeywords = listOf(
            "应用商店", "软件商店", "浏览器", "视频", "短剧", "直播", "漫画", "游戏中心",
            "market", "browser", "video", "gamecenter", "gamestore",
            "小说", "阅读", "读书", "书屋", "看书", "追书",
            "novel", "reader", "book", "read", "shuqi", "fanqie", "qidian", "qimao",
            "音乐", "music", "audio", "歌曲", "铃声", "ringtone",
            "天气", "weather", "日历", "calendar", "时钟", "clock",
            "拍照", "相机", "camera", "photo", "图片", "gallery",
            "地图", "导航", "map", "gps", "navigation",
            "购物", "shop", "mall", "store", "buy", "purchase",
            "外卖", "eleme", "food", "delivery", "takeaway", "order",
            "旅行", "travel", "trip", "tour", "hotel", "flight", "ticket",
            "打车", "ride", "taxi", "出行", "transport",
            "金融", "finance", "理财", "投资", "stock", "fund", "investment",
            "教育", "education", "学习", "course", "class", "study", "exam",
            "健康", "health", "健身", "sport", "fitness", "diet", "weight",
            "社交", "social", "chat", "dating", "交友", "恋爱",
            "招聘", "job", "career", "work", "resume", "hire", "recruit",
            "房产", "house", "rent", "property", "real estate", "买房", "租房"
        )
        val lowRiskIndicators = listOf(
            "工具", "utility", "cleaner", "boost", "security", "antivirus",
            "文件", "file", "manager", "backup", "sync", "cloud",
            "设置", "setting", "config", "control", "panel",
            "系统", "system", "os", "firmware", "update", "ota"
        )
        return when {
            criticalRiskKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> NotificationRiskLevel.HIGH
            highRiskKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> NotificationRiskLevel.HIGH
            activityKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> NotificationRiskLevel.HIGH
            mediumRiskKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> NotificationRiskLevel.MEDIUM
            lowRiskIndicators.any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> NotificationRiskLevel.LOW
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
