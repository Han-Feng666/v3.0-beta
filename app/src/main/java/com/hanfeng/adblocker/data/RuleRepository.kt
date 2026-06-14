package com.HanFeng.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.stream.JsonWriter
import com.google.gson.reflect.TypeToken
import com.HanFeng.core.network.TrainingSampleExporter
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RemoteRuleSourceConfig
import com.HanFeng.model.RuleSource
import java.io.InputStream
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RuleRepository {
    private const val PREFS = "rule_repo"
    private const val KEY_RULES = "rules"
    private const val KEY_RULE_COUNT = "rules_count"
    private const val KEY_REMOTE_RULE_SOURCES = "remote_rule_sources"
    private const val KEY_CUSTOM_VENDORS = "custom_vendors"
    private const val KEY_UNKNOWN_VENDOR_SAMPLES = "unknown_vendor_samples"
    private const val DEFAULT_VENDOR = "其它 (Other)"
    private const val GENERIC_AD_VENDOR = "通用广告/追踪 (Generic Ad/Tracking)"
    private const val BYPASS_PROTECTION_VENDOR = "加密 DNS 反绕过 (Encrypted DNS)"
    private const val REGEX_RULE_DOMAIN = "[Regex Rule]"
    private const val COSMETIC_RULE_DOMAIN = "[Cosmetic Rule]"
    private const val UNSUPPORTED_RULE_DOMAIN = "[Unsupported Rule]"
    private const val SUSPICIOUS_SAMPLE_DEBOUNCE_MILLIS = 10_000L
    private const val SUSPICIOUS_SAMPLE_PERSIST_DEBOUNCE_MILLIS = 30_000L
    private const val SUSPICIOUS_SAMPLE_DECODE_MAX_LENGTH = 2048
    private const val SUSPICIOUS_SAMPLE_MAX_DECODE_ROUNDS = 2
    private const val RULES_FILE_NAME = "rules.json"
    private const val BUILTIN_AD_SEED_SOURCE_ID = "builtin-ad-seed"
    // 白名单域名 - 这些域名被拦截会导致 APP 断网
    // 策略：只保护基础服务，不保护纯广告域名
    // 2026-06-06 优化：移除过度保护的泛域名，改为精确子域名保护
    private val whitelistDomains = setOf(
        // 微信/QQ 核心服务 - 精确保护（不再保护整个 qq.com）
        "servicewechat.com",
        "alipay.com",
        "alipay.cn",
        "dns.weixin.qq.com.cn",
        "aedns.weixin.qq.com",
        "wx.qq.com",
        "web.weixin.qq.com",
        "mp.weixin.qq.com",
        "work.weixin.qq.com",
        "long.weixin.qq.com",
        "szshort.weixin.qq.com",
        "wecom.qq.com",
        "wework.com",
        "weiyun.com",
        "weiyun.cn",
        "qqmail.com",
        "mail.qq.com",
        "exmail.qq.com",
        "docs.qq.com",
        "meeting.tencent.com",
        "voovmeeting.com",
        "tim.qq.com",
        "ftn.qq.com",
        "myqcloud.com",
        "qcloud.com",
        "tencentyun.com",
        "file.myqcloud.com",
        "cos.myqcloud.com",
        "tpns.tencent.com",
        // 微信 QQ 基础通信 - 不再保护 qlogo.cn/qpic.cn 等图片 CDN（常被用於广告）
        "qlogo.cn",
        "qlogo.com",
        // 支付相关 - 精确保护
        "qpay.tf.qq.com",
        "qpay.qq.com",
        "tenpay.com",
        "paipai.com",
        // 游戏核心登录/更新服务 - 不再保护活动域名
        "gamehelper.com.cn",
        "act.qq.com",
        "imgcache.qq.com",
        // 原神/米哈游游戏 - 精确保护登录/更新
        "miHoYo.com",
        "mihayo.com",
        "yuanshen.com",
        "hoyolab.com",
        "hoyoverse.com",
        "bhsr.com",
        "starrails.com",
        // 腾讯游戏登录服务 - 移除 dlied*.qq.com 下载域名（常被用於打包广告）
        "dnf.qq.com",
        "cf.qq.com",
        "lol.qq.com",
        "speed.qq.com",
        "fifa.qq.com",
        "2k.qq.com",
        "ssl.ptlogin2.qq.com",
        "ptlogin2.qq.com",
        // 网易游戏 - 只保护登录/支付，移除 163.com/netease.com 泛域名
        "game.163.com",
        // 通用 CDN - 只保护 Google 和阿里云核心 CDN
        "alicdn.com",
        "alibaba.com",
        "taobao.com",
        "aliyun.com",
        "cdndm.com",
        "cdn.hockeyapp.net",
        "fir.im",
        // 金融/银行 - 完全保护
        "webank.com",
        "webankcdn.net",
        "wldservice.com",
        "constid.dingxiang-inc.com",
        // Google 基础服务 - 完全保护（确保 Play 商店、推送正常）
        "firebaseinstallations.googleapis.com",
        "googleapis.com",
        "gstatic.com",
        "google.com",
        "googleapis.cn",
        "gvt1.com",
        "gvt2.com",
        "android.googleapis.com",
        "play.googleapis.com",
        "play.google.com",
        "clientservices.googleapis.com",
        "update.googleapis.com",
        "android.clients.google.com",
        "ssl.gstatic.com",
        // 隐私保护服务（误报）- 完全保护
        "ghostery.com",
        "ghostery.net",
        // 在线视频 CDN - 精确保护，移除泛域名
        "hdzixun.com",
        "douyinvod.com",
        "douyincdn.com",
        "bytegoofy.com",
        "video.qq.com",
        "qcloudimg.com",
        "cdn-go.cn",
        "bcebos.com",
        "bdstatic.com",
        "iqiyi.com",
        "71.am",
        "71edge.com",
        "gitv.tv",
        "youku.com",
        "ykimg.com",
        "cibntv.net",
        "mmstat.com",
        "soku.com",
        "le.com",
        "lecloud.com",
        "letvcdn.com",
        "letvimg.com",
        "bilibili.com",
        "bilivideo.com",
        "biliapi.com",
        "biligame.com",
        "mcdn.bilivideo.cn",
        "mgtv.com",
        "imgo.tv",
        "hitv.com",
        "hwcdn.net",
        "tcdn.qq.com",
        "liveplay.myqcloud.com"
    )
    
    // 友盟特殊处理 - 只保护基础服务子域名（日志相关）
    private val umengWhitelistSubDomains = setOf(
        "alog-default.umeng.com",
        "ulogs.umeng.com",
        "cnlogs.umeng.com",
        "errlog.umeng.com",
        "errnewlog.umeng.com",
        "aaid.umeng.com"
    )
    
    // QQ 基础服务 - 保护监控和日志服务
    private val qqWhitelistSubDomains = setOf(
        "rmonitor.qq.com",
        "monitor.qq.com",
        "aeventlog.beacon.qq.com",
        "fclog.baidu.com",
        "mugcdn.x5.qq.com"
    )
    
    // 网易工具服务
    private val neteaseWhitelistSubDomains = setOf(
        "c.nstool.ntes53.netease.com",
        "a.nstool.ntes53.netease.com",
        "b.nstool.ntes53.netease.com"
    )
    private val gson = Gson()
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val cacheLock = Any()
    private val domainValidationRegex = Regex("[a-z0-9._-]+")
    private val alphanumericRegex = Regex("[^a-z0-9]")
    private val domainExtractRegex = Regex("([a-z0-9-]+(?:\\.[a-z0-9-]+)+)", RegexOption.IGNORE_CASE)
    private val domainSubdomainRegex = Regex("[a-z0-9*-]+", RegexOption.IGNORE_CASE)
    private val unicodeEscapeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
    private val htmlNumericEntityRegex = Regex("""&#(x?[0-9a-fA-F]+);?""")
    private val alphanumericCnRegex = Regex("[^a-z0-9\u4e00-\u9fff]")
    private val whitespaceRegex = Regex("\\s+")
    private val ipV4Regex = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val parensRegex = Regex("\\(([^)]+)\\)")
    private val splitWhitespaceRegex = Regex("[\\s,;]+")
    private val lineBreakRegex = Regex("[\\r\\n]+")
    @Volatile private var cachedRules: List<BlockRule>? = null
    @Volatile private var cachedRuleCount: Int? = null
    @Volatile private var cachedBlockedDomains: Set<String>? = null
    @Volatile private var cachedRuleMap: Map<String, List<BlockRule>>? = null
    @Volatile private var cachedRegexRules: List<BlockRule>? = null
    @Volatile private var cachedCosmeticRules: List<BlockRule>? = null
    @Volatile private var cachedIpCidrRules: List<BlockRule>? = null
    @Volatile private var cachedPortOnlyRules: List<BlockRule>? = null
    @Volatile private var cachedCustomVendors: Map<String, String>? = null
    @Volatile private var cachedRuleInventory: RuleInventory? = null
    @Volatile private var cachedCompiledRegexRules: Map<String, java.util.regex.Pattern> = emptyMap()
    @Volatile private var cachedInvalidRegexRules: Set<String> = emptySet()
    @Volatile private var cachedVendorMap: MutableMap<String, String> = ConcurrentHashMap()
    @Volatile private var cachedKeywordRules: List<BlockRule>? = null
    @Volatile private var cachedWhitelistHits = ConcurrentHashMap<String, Boolean>()
    @Volatile private var cachedUnknownVendorSamples: Map<String, SuspiciousDomainRecord>? = null
    @Volatile private var lastUnknownVendorSamplesPersistAt: Long = 0L
    private val adKeywords = listOf(
        "ad",
        "ads",
        "adn",
        "adnet",
        "adservice",
        "adserver",
        "adview",
        "admob",
        "adx",
        "adxlog",
        "adclick",
        "adpush",
        "adproxy",
        "admarket",
        "adscene",
        "adcore",
        "adstat",
        "track",
        "tracking",
        "analytics",
        "beacon",
        "monitor",
        "sdk",
        "ssp",
        "dsp",
        "rtb",
        "bid",
        "bidder",
        "union",
        "unionad",
        "promotion",
        "advert",
        "measure",
        "mediation",
        "interstitial",
        "reward",
        "splash",
        "nativead",
        "feedad",
        "brandad",
        "launchad",
        "screenad",
        "startupad",
        "openad",
        "intad",
        "mbridge",
        "pangle",
        "gdt",
        "qxm",
        "ubix",
        "zghd",
        "zhongguan",
        "doubleclick",
        "topon",
        "tradplus",
        "adscope",
        "sigmob",
        "mobvista",
        "mintegral",
        "applovin",
        "ironsource",
        "unityads",
        "vungle",
        "offerwall",
        "rewardvideo",
        "excitation",
        "inspire",
        "welfare",
        "benefit",
        "taskcenter",
        "taskreward",
        "coinreward",
        "readingbonus",
        "launch",
        "startup",
        "preload",
        "material",
        "creative",
        "landing",
        "showurl",
        "clickurl",
        "monitorurl",
        "impression",
        "playable",
        "endcard",
        "youlianghui",
        "guangdiantong",
        "adqq",
        "alimama",
        "tanx",
        "adash",
        "pangolin",
        "gromore",
        "snssdk",
        "ksad",
        "kuaishouad",
        "kwad",
        "beizi",
        "youmi",
        "mediav",
        "vpon",
        "domob",
        "duomeng",
        "adwo",
        "openalliance",
        "huaweiads",
        "mimo",
        "oppoads",
        "vivoads",
        "audiencenetwork",
        "maxads",
        "anythink",
        "tpbid",
        "aiclk",
        "openwrap",
        "inneractive",
        "colossusssp",
        "hbopenbid",
        "dtexchange",
        "moatads",
        "taboola",
        "outbrain",
        "pubmatic",
        "openx",
        "smaato",
        "tapjoy",
        "adcolony",
        "ogury"
    )
    private val weakAdKeywords = setOf(
        "ad",
        "ads",
        "track",
        "analytics",
        "monitor",
        "measure",
        "launch",
        "startup",
        "material",
        "creative",
        "landing"
    )
    private val sensitiveAuthKeywords = listOf(
        "login",
        "signin",
        "signup",
        "auth",
        "oauth",
        "sso",
        "passport",
        "account",
        "accounts",
        "session",
        "token",
        "verify",
        "captcha",
        "securelogin"
    )
    private val novelVendorNames = setOf(
        "番茄小说 (Fanqie Novel)",
        "七猫小说 (Qimao Novel)",
        "起点读书 (Qidian Reader)",
        "QQ阅读 (QQ Reader)",
        "书旗小说 (Shuqi Novel)",
        "掌阅 (iReader)",
        "咪咕阅读 (Migu Read)",
        "米读小说 (Midu Novel)",
        "纵横小说 (Zongheng Novel)",
        "17K 小说 (17K Novel)",
        "长读小说 (Changdu Novel)"
    )
    private val novelAppIdentifiers = listOf(
        "番茄小说", "番茄免费小说", "fanqie", "fqnovel", "dragon.read",
        "番茄畅听", "tomato.read", "tomatoread", "tomatonovel", "novel.snssdk", "fanqienovel",
        "七猫小说", "七猫免费小说", "qimao", "kmxs", "wtzw",
        "起点读书", "qidian", "qdreader", "yuewen",
        "qq阅读", "qqreader", "qqread", "weread",
        "书旗小说", "shuqi", "aliwx",
        "掌阅", "ireader", "zhangyue",
        "咪咕阅读", "migu", "cmread",
        "米读小说", "midu", "miduread", "lechuan", "duokan", "readnovel",
        "纵横小说", "zongheng", "zhread",
        "17k", "17k小说", "book17k",
        "长读小说", "changdu",
        "红果免费短剧", "红果短剧", "免费短剧", "短剧", "短剧大全", "短剧场", "微短剧", "剧场", "小剧场",
        "hongguo", "hongguoapp", "dejian", "duanju", "duanvideo", "shortdrama", "short_drama", "minidrama", "mini_drama", "drama", "episode",
        "漫画", "免费漫画", "漫画大全", "漫剧", "comic", "manga", "manhua", "cartoon", "kuaikan", "buka", "dongman",
        "听书", "有声书", "追书", "看书", "免费小说", "小说大全", "小说阅读", "阅读器", "书城",
        "bookcity", "bookstore", "story", "stories", "freebook", "bookreader", "bookread",
        "novelreader", "readapp", "yuedu", "xiaoshuo", "mianfei", "zhuishu", "kanshu"
    )
    private val novelAppProtectedSuffixes = setOf(
        "wtzw.com",
        "qimao.com",
        "kmxs.com",
        "fqnovel.com",
        "fanqienovel.com",
        "reading.snssdk.com",
        "novel.snssdk.com",
        "zijieapi.com",
        "qidian.com",
        "yuewen.com",
        "readnovel.com",
        "hongxiu.com",
        "xxsy.net",
        "qqreader.com",
        "reader.qq.com",
        "shuqi.com",
        "ireader.com",
        "zhangyue.com",
        "cmread.com",
        "migu.cn",
        "migu.com",
        "midu.com",
        "zongheng.com",
        "17k.com",
        "changdu.com",
        "hongguo.com",
        "dejian.com"
    )
    
    // 游戏核心服务域名（确保登录、联机、更新正常）
    private val gameCoreDomains = setOf(
        // 腾讯游戏
        "gamehelper.com.cn", "act.qq.com", "imgcache.qq.com",
        "gamedl.qq.com", "game.qq.com", "gamesafe.qq.com", "gameinfo.qq.com",
        "gamecenter.qq.com", "sso.10.qq.com", "open.id.qq.com",
        "ssl.ptlogin2.qq.com", "ptlogin2.qq.com",
        "dl.dir.qq.com", "dlied1.qq.com", "dlied2.qq.com",
        "dlied3.qq.com", "dlied4.qq.com", "dlied5.qq.com", "dlied6.qq.com",
        // 米哈游
        "mihoyo.com", "mihayo.com", "yuanshen.com", "hoyolab.com",
        "hoyoverse.com", "bhsr.com", "starrails.com",
        // 网易
        "game.163.com",
        // 通用下载 CDN
        "cdndm.com", "cdn.hockeyapp.net", "fir.im"
    )
    
    // 社交 APP 核心域名（确保聊天、语音、视频正常）
    private val socialCoreDomains = setOf(
        // 微信 QQ
        "qq.com", "weixin.qq.com", "wx.qq.com", "web.weixin.qq.com", "mp.weixin.qq.com",
        "work.weixin.qq.com", "long.weixin.qq.com", "szshort.weixin.qq.com",
        "weixinbridge.com", "wechat.com", "wechatpay.cn",
        "mqqurl.com", "qqurl.com", "qq.com.cn", "imqq.com",
        "wecom.qq.com", "wework.com", "weiyun.com", "weiyun.cn",
        "qqmail.com", "mail.qq.com", "exmail.qq.com", "docs.qq.com",
        "meeting.tencent.com", "voovmeeting.com", "tim.qq.com", "ftn.qq.com",
        "myqcloud.com", "qcloud.com", "tencentyun.com", "file.myqcloud.com", "cos.myqcloud.com", "tpns.tencent.com",
        "qlogo.cn", "qlogo.com", "qpic.cn", "qpic.com",
        "gtimg.com", "gtimg.cn",
        // 酷安社区
        "coolapk.com", "coolapkmarket.com",
        // 支付
        "qpay.tf.qq.com", "qpay.qq.com", "tenpay.com", "paipai.com"
    )

    // 音乐/音频核心域名（确保播放、搜索、评论、账号同步正常）
    private val mediaCoreDomains = setOf(
        "music.qq.com", "y.qq.com", "qqmusic.qq.com", "stream.qqmusic.qq.com", "dl.stream.qqmusic.qq.com",
        "kg.qq.com", "kgimg.com", "kugou.com", "kugoucdn.com", "kglink.cn", "staticssl.kugou.com",
        "kuwo.cn", "kuwo.com", "kuwoapp.com", "kwimgs.com", "kuwo.cn",
        "music.163.com", "music.126.net", "126.net", "nosdn.127.net", "vod.126.net",
        "ximalaya.com", "ximaimg.com", "xmcdn.com", "ximaimg.cn",
        "qingting.fm", "qtfm.cn", "qingtingcdn.com",
        "lizhi.fm", "lizhi.io"
    )

    private val businessCoreDomains = setOf(
        "alidrive.com", "aliyundrive.com", "aliyuncs.com", "drive.uc.cn",
        "cloud.189.cn", "115.com", "pan.baidu.com", "yunpan.360.cn",
        "docs.qq.com", "doc.weixin.qq.com", "shimo.im", "feishu.cn", "feishu.net",
        "larkoffice.com", "bytedocs.com", "yuque.com", "notion.so",
        "amap.com", "autonavi.com", "amapapis.com", "didialift.com", "didichuxing.com",
        "meituan.com", "sankuai.com", "ele.me", "eleme.cn",
        "alipay.com", "alipay.cn", "tenpay.com", "unionpay.com", "95516.com"
    )
    
    // 小说内容 API 白名单 (这些域名/子域名专门提供小说内容，不拦截)
    private val novelContentApiDomains = setOf(
        // 番茄小说
        "api.fanqienovel.com",
        "api1.fanqienovel.com",
        "api2.fanqienovel.com",
        "reader-api.fanqienovel.com",
        "api-access.fqnovel.com",
        "reader-api.fqnovel.com",
        "reading.snssdk.com",
        "novel.snssdk.com",
        "api5-normal-lf.fqnovel.com",
        "api3-normal-lf.fqnovel.com",
        // 七猫小说
        "api.qimao.com",
        "webnovel.qimao.com",
        "reader-api.kmxs.com",
        "api-ks.wtzw.com",
        // 起点读书
        "bookapi.qidian.com",
        "read.qidian.com",
        "trader.qidian.com",
        "druidv6.if.qidian.com",
        // QQ 阅读
        "book.qqreader.com",
        "reader.qq.com",
        "api.weread.qq.com",
        // 书旗小说
        "api.shuqi.com",
        "reader.aliwx.com",
        "capi.shuqireader.com",
        // 掌阅
        "api.ireader.com",
        "book.zhangyue.com",
        // 其他
        "api.cmread.com",
        "api.migu.com",
        "api.midu.com",
        "api.zongheng.com",
        "api.17k.com",
        "api.changdu.com",
        "api.dejian.com",
        "api.hongguo.com"
    )
    private val novelAggressiveVendorNames = setOf(
        "优比客思 (UBIX Ads)",
        "QXM (QXM Ads)",
        "中关互动 (ZGHD)",
        "趣盟广告 (Qumeng Ads)",
        "AdScope 聚合广告 (AdScope)",
        "通用广告/追踪 (Generic Ad/Tracking)"
    )
    private val novelAggressiveExactDomains = setOf(
        "ad.hunyuan.tencent.com",
        "dsp-creative.ubixioe.com",
        "adx-data-u1.ubixioe.com",
        "ade-rtb.netease.com",
        "adtrack.e.kuaishou.com",
        "v4-lm.adukwai.com",
        "log-api.pangolin-sdk-toutiao-b.com",
        "p2-pro.a.yximgs.com",
        "tnc3-aliec2.zijieapi.com",
        "tnc3-bjlgy.zijieapi.com",
        "tnc3-alisc1.snssdk.com",
        "tnc11-aliec2.zijieapi.com",
        "tnc11-bjlgy.zijieapi.com",
        "api-access.pangolin-sdk-toutiao.com",
        "api-access.pangolin-sdk-toutiao1.com",
        "tnc3-bjlgy.bytegecko.com",
        "log-api.pangolin-sdk-toutiao-b.com",
        "pangolin-sdk-toutiao.com",
        "pangolin-sdk-toutiao1.com",
        "gdt.qq.com",
        "gdtimg.com",
        "e.qq.com"
    )
    private val highConfidenceAdSdkDomains = setOf(
        "pangolin-sdk-toutiao.com",
        "pangolin-sdk-toutiao1.com",
        "pangolin-sdk-toutiao-b.com",
        "pangle.io",
        "csjplatform.com",
        "oceanengine.com",
        "gromore.com",
        "gdt.qq.com",
        "e.qq.com",
        "gdtimg.com",
        "guangdiantong.cn",
        "youlianghui.com"
    )
    private val byteDanceInfraProtectedSuffixes = setOf(
        "bytegecko.com",
        "pstatp.com",
        "snssdk.com",
        "fqnovelstatic.com",
        "byteimg.com",
        "ibytedtos.com",
        "bytedtos.com",
        "zijieapi.com"
    )
    private val fanqieProtectedAdPathKeywords = listOf(
        "/ad/", "/ads/", "/adx/", "/advert/", "/advertisement/", "/union/", "/sdk/union/",
        "/reward/", "/rewarded/", "/excitation/", "/inspire/", "/banner/", "/feed_ad/",
        "/bottom_banner/", "/floating_banner/", "/common/banner/", "/native/banner/",
        "/draw_ad/", "/ad_plan/", "/ad_request/", "/ad_style/", "/ad_config/", "/ad_info/",
        "/launch/", "/startup/", "/open_screen/", "/splash/", "/feed/banner/", "/popup/",
        "/welfare/", "/task/", "/task_center/", "/coin/", "/bonus/", "/benefit/", "/offerwall/"
    )
    private val bypassProtectionDomains = setOf(
        "dns.alidns.com",
        "httpdns.aliyun.com",
        "httpdns.alicdn.com",
        "httpdns-sc.aliyuncs.com",
        "httpdns-cn.aliyuncs.com",
        "httpdns-api.aliyuncs.com",
        "httpdns.m.aliyuncs.com",
        "doh.pub",
        "dot.pub",
        "doq.pub",
        "dns.google",
        "dns.google.com",
        "dns64.dns.google",
        "cloudflare-dns.com",
        "one.one.one.one",
        "1dot1dot1dot1.cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "chrome.cloudflare-dns.com",
        "security.cloudflare-dns.com",
        "family.cloudflare-dns.com",
        "dns.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        "dns.adguard-dns.com",
        "unfiltered.adguard-dns.com",
        "dns-family.adguard.com",
        "dns-unfiltered.adguard.com",
        "dns.nextdns.io",
        "dns.nextdns.io.sslip.io",
        "dns0.eu",
        "zero.dns0.eu",
        "doh.opendns.com",
        "doh.cleanbrowsing.org",
        "dns.umbrella.com",
        "dns64.steward.net",
        "family-filter-dns.cleanbrowsing.org",
        "security-filter-dns.cleanbrowsing.org",
        "adult-filter-dns.cleanbrowsing.org",
        "dns.adguard.com",
        "dns-family.adguard.com",
        "dns-unfiltered.adguard.com",
        "unfiltered.adguard-dns.com",
        "dns.nextdns.io",
        "dns0.eu",
        "zero.dns0.eu",
        "resolver1.opendns.com",
        "resolver2.opendns.com",
        "resolver3.opendns.com",
        "resolver4.opendns.com",
        "familyshield.opendns.com",
        "security.cloudflare-dns.com",
        "family.cloudflare-dns.com",
        "dns11.quad9.net",
        "dns10.quad9.net",
        "dns.quad9.net",
        "doh.dns.sb",
        "dot.dns.sb",
        "public.dns.iij.jp",
        "jp.tiar.app",
        "doh.apad.pro",
        "dot.apad.pro",
        "httpdns.bcelive.com",
        "httpdns.baidu.com",
        "doh.baidu.com",
        "dns.srv.baidu.com",
        "dns.weixin.qq.com",
        "dns.weixin.qq.com.cn",
        "httpdns.weixin.qq.com",
        "httpdns.qq.com",
        "httpdns.tencentyun.com",
        "httpdns.tencent-cloud.com",
        "resolver-a.privatelink.apple-dns.net",
        "mask.icloud.com",
        "mask-h2.icloud.com",
        "use-application-dns.net"
    )
    private val vendorPatterns = linkedMapOf(
        "腾讯 (Tencent)" to listOf(
            "gdt", "qq", "e.qq", "tencent", "wechat", "weixin", "qcloud", "bugly", "qzone", "qzs",
            "gtimg", "imtt", "myapp", "sogou", "iegcom", "tmead", "music.qq", "y.qq", "kuwo", "kugou", "kgimg",
            "qpic", "idqqimg", "tenpay", "tenvideo", "qlogo", "wechatpay", "qweather"
        ),
        "字节跳动 (ByteDance)" to listOf(
            "pangle", "pangolin", "oceanengine", "bytedance", "bytecdn", "toutiao", "snssdk", "douyin",
            "amemv", "volces", "tiktok", "musical.ly", "toutiaocloud", "jinritemai", "zijieapi", "isnssdk", "ibytedtos", "gromore", "csj",
            "lf3", "lf6", "ixigua", "bdxigua"
        ),
        "番茄小说 (Fanqie Novel)" to listOf(
            "fanqie", "fanqienovel", "fqnovel", "dragon.read", "reading.snssdk", "tomato.read", "fqnovelvod", "novel.snssdk"
        ),
        "七猫小说 (Qimao Novel)" to listOf(
            "qimao", "kmxs", "wtzw", "sevencat", "qmread", "qmks", "qimaoad"
        ),
        "起点读书 (Qidian Reader)" to listOf(
            "qidian", "qdreader", "yuewen", "readnovel", "hongxiu", "xxsy", "qdbook", "ywstatic", "qdmm"
        ),
        "QQ阅读 (QQ Reader)" to listOf(
            "qqreader", "reader.qq", "qqbook", "qqread", "weread", "book.qq"
        ),
        "书旗小说 (Shuqi Novel)" to listOf(
            "shuqi", "shuqiapi", "aliwx", "sqnovel", "shuqireader", "shuqiimg", "sqxs"
        ),
        "掌阅 (iReader)" to listOf(
            "ireader", "zhangyue", "zyreader", "chaozh", "iread", "zyad", "ireadad"
        ),
        "咪咕阅读 (Migu Read)" to listOf(
            "migu", "miguread", "cmread", "wap.cmread", "miguvideo", "migulive"
        ),
        "米读小说 (Midu Novel)" to listOf(
            "midu", "miduread", "miduoke", "lechuan", "midubook", "miduad", "midusdk"
        ),
        "纵横小说 (Zongheng Novel)" to listOf(
            "zongheng", "zongheng.com", "zhread", "zonghengad"
        ),
        "17K 小说 (17K Novel)" to listOf(
            "17k", "17k.com", "book17k", "read17k", "17kimg"
        ),
        "长读小说 (Changdu Novel)" to listOf(
            "changdu", "changdu.com", "changduad", "cdsdk"
        ),
        "阿里巴巴集团 (Alibaba Group)" to listOf(
            "alibaba", "alibabagroup", "alipay", "taobao", "tmall", "aliyun", "alimama", "tanx", "umeng",
            "ucads", "ucweb", "mmstat", "ut.taobao", "union.taobao", "ad.aliyun", "youku", "ykimg", "ykad", "amap", "eleme",
            "alicdn", "adashx", "koubei", "fliggy", "etao", "xiami", "gaode"
        ),
        "百度 (Baidu)" to listOf(
            "baidu", "mobads", "duapps", "baidustatic", "cpro", "dueros", "hao123", "baidubce", "bdimg",
            "bdstatic", "baidubcs", "tieba", "haokan", "quanmin", "box.baidu"
        ),
        "快手 (Kuaishou)" to listOf(
            "kuaishou", "kwai", "kwad", "kwaiad", "adkwai", "ksad", "yximgs", "gifshow"
        ),
        "华为 (Huawei)" to listOf(
            "huawei", "hicloud", "hispace", "hms", "hwcloud", "openalliance", "ads-drcn", "petal"
        ),
        "小米 (Xiaomi)" to listOf(
            "xiaomi", "miui", "mistat", "ad.xiaomi", "tracking.miui", "mi.com", "duokan"
        ),
        "OPPO (HeyTap)" to listOf(
            "oppo", "heytap", "coloros", "aps", "adx.ads.heytap", "cp01", "oppomobile"
        ),
        "vivo (vivo Ads)" to listOf(
            "vivo", "iqoo", "ads.vivo", "adlog.vivo", "vivoglobal", "bbk"
        ),
        "QXM (QXM Ads)" to listOf(
            "qxm", "qxmad", "qxmads", "52qumao", "qumao", "qmxad"
        ),
        "UBIX (UBIX Ads)" to listOf(
            "ubix", "ubixio", "ubixad", "ubxi", "ubixai", "ubiadx", "ubixioe"
        ),
        "中关互动 (ZGHD)" to listOf(
            "zghd", "zhongguan", "zgad", "zhghd", "hxltad", "adintl"
        ),
        "荣耀 (Honor)" to listOf("honor", "honormagic", "ads.honor", "hihonor"),
        "京东 (JD.com)" to listOf("jingdong", "jad", "jrad", "ads-union.jd", "3.cn", "jcloud", "jdcloud", "jdwl"),
        "美团 (Meituan)" to listOf("meituan", "dianping", "maoyan", "union.meituan", "ad.meituan", "media.meituan", "sankuai", "meituan.net", "meituanstatic", "meituanad"),
        "趣盟广告 (Qumeng Ads)" to listOf("qumeng", "qmob", "qtmojo", "qmadsdk", "qumengad"),
        "网易 (NetEase)" to listOf("netease", "163", "youdao", "music.126", "adgeo.163", "netease.im"),
        "微博 (Weibo)" to listOf("weibo", "sinaimg", "alitui.weibo", "ad.weibo", "sina.cn"),
        "哔哩哔哩 (Bilibili)" to listOf("bilibili", "biliapi", "bilivideo", "cm.bilibili", "hdslb"),
        "爱奇艺 (iQIYI)" to listOf("iqiyi", "qiyi", "pps", "adx.qiyi", "msg.qy.net"),
        "搜狐 (Sohu)" to listOf("sohu", "sohucs", "aty.sohu"),
        "芒果 (MangoTV)" to listOf("mgtv", "hunantv", "ad.mgtv"),
        "拼多多 (PDD)" to listOf("pinduoduo", "yangkeduo", "pddpic", "pddimg"),
        "小红书 (Xiaohongshu)" to listOf("xiaohongshu", "xhscdn", "xhslink", "xhsimg"),
        "携程 (Trip.com)" to listOf("ctrip", "trip.com", "qunar", "tieshujia"),
        "360 (Qihoo 360)" to listOf("360.cn", "qhimg", "qhmsg", "so.com", "360safe", "360buyimg"),
        "极光 (Jiguang)" to listOf("jiguang", "jpush", "jmessage", "aurora", "jiguang.cn"),
        "个推 (Getui)" to listOf("getui", "igexin", "gexin", "getui.net"),
        "TalkingData (TalkingData)" to listOf("talkingdata", "tendcloud", "talkingdata.net"),
        "神策数据 (Sensors Data)" to listOf("sensorsdata", "sa-sdk", "sensorsdata.cn"),
        "秒针系统 (Miaozhen)" to listOf("miaozhen", "miaozhen.com"),
        "AdMaster (AdMaster)" to listOf("admaster", "admasterapi"),
        "Sigmob (Sigmob)" to listOf("sigmob", "sigmob.cn", "sigmobads"),
        "MobTech (MobTech)" to listOf("mob.com", "mobpush", "sharesdk"),
        "Alphabet (Google)" to listOf(
            "google", "doubleclick", "admob", "googlesyndication", "googleadservices", "googleads", "gstatic",
            "googletagmanager", "google-analytics", "analytics.google", "firebase", "firebasead", "youtube",
            "ytimg", "crashlytics", "adservice.google"
        ),
        "Meta (Meta Platforms)" to listOf(
            "facebook", "fbcdn", "fbsbx", "meta", "instagram", "audiencenetwork", "whatsapp", "oculus"
        ),
        "Amazon (Amazon Ads)" to listOf("amazon", "amzn", "amazon-adsystem", "aaxads", "twitch", "imdb"),
        "Microsoft (Microsoft Ads)" to listOf(
            "microsoft", "msn", "bing", "xandr", "linkedin", "skype"
        ),
        "Apple (Apple Ads)" to listOf(
            "apple", "icloud", "itunes", "iad.apple", "appleadservices", "mzstatic", "cdn-apple"
        ),
        "Samsung (Samsung Ads)" to listOf(
            "samsung", "samsungads", "samsungacr", "samsungcloudcdn"
        ),
        "X (Twitter)" to listOf("twitter", "t.co", "twimg", "ads-twitter", "x.com"),
        "Snap (Snapchat)" to listOf("snapchat", "sc-cdn", "snapads", "snapkit", "feelinsonice"),
        "Pinterest (Pinterest)" to listOf("pinterest", "pinimg", "ads.pinterest"),
        "Reddit (Reddit)" to listOf("reddit", "redd.it", "redditmedia", "ads.reddit"),
        "Unity (Unity Ads)" to listOf("unityads", "unity3d", "unityads.unity3d", "delta-dna"),
        "AppLovin (AppLovin)" to listOf("applovin", "applvn", "applovinsdk", "maxads"),
        "ironSource (ironSource)" to listOf("ironsrc", "ironsource", "supersonicads", "unity-ironsource"),
        "Vungle (Liftoff)" to listOf("vungle", "liftoff", "vungleads", "liftoff.io"),
        "Chartboost (Chartboost)" to listOf("chartboost", "chartboosts"),
        "InMobi (InMobi)" to listOf("inmobi", "aerserv", "glancecdn"),
        "Mintegral (Mintegral)" to listOf("mintegral", "mobvista", "mbridge", "mtgads", "mbridgelab"),
        "Moloco (Moloco)" to listOf("moloco", "molocoads"),
        "The Trade Desk (TTD)" to listOf("thetradedesk", "adsrvr", "uidapi"),
        "PubMatic (PubMatic)" to listOf("pubmatic", "ads.pubmatic", "hbopenbid"),
        "PubNative (PubNative)" to listOf("pubnative", "pubnative.net", "hybid"),
        "Magnite (Magnite)" to listOf("magnite", "rubiconproject", "spotxchange", "spotx.tv"),
        "OpenX (OpenX)" to listOf("openx", "openx.net"),
        "Index Exchange (Index Exchange)" to listOf("indexww", "casalemedia", "indexexchange", "js-sec.indexww"),
        "Media.net (Media.net)" to listOf("media.net", "medianet", "contextual.media.net"),
        "Taboola (Taboola)" to listOf("taboola", "taboolasyndication"),
        "Outbrain (Outbrain)" to listOf("outbrain", "outbrainimg", "odb.outbrain"),
        "TripleLift (TripleLift)" to listOf("triplelift", "3lift"),
        "AdColony (AdColony)" to listOf("adcolony", "adc3"),
        "Ogury (Ogury)" to listOf("ogury", "adogy"),
        "Digital Turbine (DT Exchange)" to listOf("fyber", "inner-active", "iaacdn", "digitalturbine", "colossusssp"),
        "Smaato (Smaato)" to listOf("smaato", "smaato.net"),
        "Start.io (Start.io)" to listOf("startappservice", "start.io", "startapp", "startad"),
        "Tapjoy (Tapjoy)" to listOf("tapjoy", "tjvid", "ws.tapjoyads"),
        "Adjoe (adjoe)" to listOf("adjoe", "adjoe.zone"),
        "LoopMe (LoopMe)" to listOf("loopme", "loopme.me"),
        "Verve (Verve Group)" to listOf("verve", "adtilt", "vervewireless"),
        "HyprMX (HyprMX)" to listOf("hyprmx", "hyprmx.com"),
        "Smadex (Smadex)" to listOf("smadex", "smadex.com"),
        "Maio (Maio)" to listOf("maio", "maio.jp"),
        "Verizon Media (Yahoo/AOL)" to listOf("yahoo", "yimg", "aol", "flurry", "verizonmedia"),
        "Oracle (Oracle Ads)" to listOf("oracle", "moatads", "addthis", "bluekai"),
        "Criteo (Criteo)" to listOf("criteo", "criteo.net"),
        "Yandex (Yandex Ads)" to listOf("yandex", "yandexadexchange", "yastatic"),
        "VK (VK Ads)" to listOf("vk.com", "vkuser", "mytarget", "mail.ru"),
        "传音 (Transsion)" to listOf("transsion", "tecno", "infinix", "itel-mobile")
    )
    private val vendorKeywords = linkedMapOf(
        "腾讯 (Tencent)" to listOf(
            "tencent", "wechat", "weixin", "qq", "gdt", "bugly", "qcloud", "myapp", "kuwo", "kugou", "sogou", "tenpay", "qzone", "qimei", "guangdiantong", "adqq", "adexpo"
        ),
        "字节跳动 (ByteDance)" to listOf(
            "bytedance", "douyin", "tiktok", "toutiao", "pangle", "oceanengine", "snssdk", "amemv", "ixigua", "gromore", "csj"
        ),
        "番茄小说 (Fanqie Novel)" to listOf(
            "fanqie", "fanqienovel", "fqnovel", "dragonread", "tomatonovel", "tomatoread", "novelsnssdk", "fqnovelvod"
        ),
        "七猫小说 (Qimao Novel)" to listOf(
            "qimao", "kmxs", "wtzw", "sevencat", "qimaoreader", "qmread", "qimaoad"
        ),
        "起点读书 (Qidian Reader)" to listOf(
            "qidian", "qdreader", "yuewen", "readnovel", "hongxiu", "xxsy", "qdbook", "qdmm", "ywstatic"
        ),
        "QQ阅读 (QQ Reader)" to listOf(
            "qqreader", "qqread", "qqbook", "readerqq", "weread", "bookqq"
        ),
        "书旗小说 (Shuqi Novel)" to listOf(
            "shuqi", "shuqinovel", "sqnovel", "aliwx", "shuqireader", "shuqiimg"
        ),
        "掌阅 (iReader)" to listOf(
            "ireader", "zhangyue", "chaozh", "zyreader", "iread", "zyad"
        ),
        "咪咕阅读 (Migu Read)" to listOf(
            "migu", "miguread", "cmread"
        ),
        "米读小说 (Midu Novel)" to listOf(
            "midu", "miduread", "lechuan", "midubook", "miduad"
        ),
        "纵横小说 (Zongheng Novel)" to listOf(
            "zongheng", "zhread"
        ),
        "17K 小说 (17K Novel)" to listOf(
            "17k", "book17k", "read17k"
        ),
        "长读小说 (Changdu Novel)" to listOf(
            "changdu", "changduad", "cdsdk"
        ),
        "阿里巴巴集团 (Alibaba Group)" to listOf(
            "alibaba", "taobao", "tmall", "alipay", "aliyun", "alimama", "umeng", "uc", "youku", "amap", "gaode", "eleme", "fliggy", "tanx", "mmstat", "adash"
        ),
        "百度 (Baidu)" to listOf(
            "baidu", "mobads", "cpro", "duapp", "tieba", "hao123", "dueros", "haokan", "baidumobads", "bdunion"
        ),
        "快手 (Kuaishou)" to listOf(
            "kuaishou", "kwai", "kwad", "gifshow", "ksad", "kwaiads", "kwaicdn", "adukwai", "yximgs"
        ),
        "华为 (Huawei)" to listOf(
            "huawei", "hms", "hicloud", "petal", "honor", "hispace", "openalliance", "hwads", "appgallery", "hwclouds"
        ),
        "小米 (Xiaomi)" to listOf(
            "xiaomi", "miui", "mistat", "miad", "mishop", "mipush", "miglobal", "redmi", "mitv", "mibox", "duokan", "mi"
        ),
        "OPPO (HeyTap)" to listOf(
            "oppo", "heytap", "coloros", "realme", "breeno", "oppomobile", "nearme"
        ),
        "vivo (vivo Ads)" to listOf(
            "vivo", "iqoo", "bbk", "vivoglobal", "jovi"
        ),
        "荣耀 (Honor)" to listOf(
            "honor", "hihonor", "magicui"
        ),
        "京东 (JD.com)" to listOf(
            "jd", "jingdong", "jrad", "jad", "3cn", "jdcloud", "jingxi", "paipai"
        ),
        "美团 (Meituan)" to listOf(
            "meituan", "dianping", "sankuai", "maoyan", "kuailv", "wmapi", "meituanad"
        ),
        "趣盟广告 (Qumeng Ads)" to listOf(
            "qumeng", "qmob", "qtmojo", "qmadsdk", "qumengad", "qtadx"
        ),
        "网易 (NetEase)" to listOf(
            "netease", "163", "youdao", "lofter", "music126", "mail163"
        ),
        "微博 (Weibo)" to listOf(
            "weibo", "sina", "sinaimg", "weibocdn"
        ),
        "哔哩哔哩 (Bilibili)" to listOf(
            "bilibili", "bili", "hdslb", "bilivideo", "biliapi"
        ),
        "爱奇艺 (iQIYI)" to listOf(
            "iqiyi", "qiyi", "pps", "qy", "71edge"
        ),
        "搜狐 (Sohu)" to listOf(
            "sohu", "sohucs", "focus"
        ),
        "芒果 (MangoTV)" to listOf(
            "mgtv", "mango", "hunantv", "mgad"
        ),
        "拼多多 (PDD)" to listOf(
            "pinduoduo", "yangkeduo", "pdd", "jinbao", "pddpic"
        ),
        "小红书 (Xiaohongshu)" to listOf(
            "xiaohongshu", "xiaohong", "xhs", "xhscdn", "xhslink"
        ),
        "携程 (Trip.com)" to listOf(
            "ctrip", "trip", "qunar", "tripcdn", "qunarzz"
        ),
        "360 (Qihoo 360)" to listOf(
            "360", "qihoo", "360safe", "qhimg", "so"
        ),
        "极光 (Jiguang)" to listOf(
            "jiguang", "jpush", "aurora", "janalytics", "jverification"
        ),
        "个推 (Getui)" to listOf(
            "getui", "gexin", "igexin", "gtpush"
        ),
        "TalkingData (TalkingData)" to listOf(
            "talkingdata", "tendcloud", "tdid"
        ),
        "极光推送广告 (Jiguang Ads)" to listOf(
            "jpush", "jiguang", "aurora", "ad.jiguang", "jmessage"
        ),
        "个推广告 (Getui Ads)" to listOf(
            "getui", "gexin", "igexin", "sdk.open.phone.igexin"
        ),
        "神策数据 (Sensors Data)" to listOf(
            "sensorsdata", "sensors"
        ),
        "秒针系统 (Miaozhen)" to listOf(
            "miaozhen"
        ),
        "AdMaster (AdMaster)" to listOf(
            "admaster"
        ),
        "Sigmob (Sigmob)" to listOf(
            "sigmob", "sigmobads"
        ),
        "MobTech (MobTech)" to listOf(
            "mobtech", "sharesdk", "mobpush", "moblink", "mobsec"
        ),
        "热云数据 (Reyun)" to listOf(
            "reyun", "trackingio", "reyun.com", "reyunad"
        ),
        "友盟+ (Umeng+)" to listOf(
            "umeng", "utdevice", "uappstat", "umtrack", "umtrack2", "utsystem"
        ),
        "穿山甲 (Pangle)" to listOf(
            "pangle", "pangolin", "csj", "gromore", "pangleglobal"
        ),
        "腾讯广告 (Tencent Ads)" to listOf(
            "gdt", "tmead", "eqq", "qqe2", "gdtimg", "gdt.qq"
        ),
        "百度联盟 (Baidu Union)" to listOf(
            "mobads", "cpro", "baidubes", "baidustat", "hm.baidu"
        ),
        "优量汇 (Tencent Marketing)" to listOf(
            "gdt", "eqq", "qqe2", "youlianghui"
        ),
        "快手联盟 (Kwai Business)" to listOf(
            "kwai", "kwad", "kuaishou", "kwaibusiness"
        ),
        "磁力引擎 (Kwai Ads)" to listOf(
            "magneticengine", "kuaishouad", "kwaiad", "adukwai", "open.e.kuaishou"
        ),
        "阿里妈妈 (Alimama)" to listOf(
            "alimama", "tanx", "atanx", "adash", "simba.taobao"
        ),
        "华为广告 (Huawei Ads)" to listOf(
            "openalliance", "hwads", "ads-drcn", "huaweiads"
        ),
        "小米广告 (Xiaomi Ads)" to listOf(
            "miad", "mistat", "ad.xiaomi", "tracking.miui"
        ),
        "OPPO 广告 (OPPO Ads)" to listOf(
            "heytap", "oppo", "nearme", "ads.heytap"
        ),
        "vivo 广告 (vivo Ads)" to listOf(
            "ads.vivo", "adlog.vivo", "vivoad", "vivo"
        ),
        "百青藤 (Baidu Union)" to listOf(
            "baijingteng", "bqt", "mobads", "cpro", "cpu-openapi"
        ),
        "荣耀广告 (Honor Ads)" to listOf(
            "hihonor", "honorads", "ads.honor", "openads.hihonor"
        ),
        "魅族广告 (Meizu Ads)" to listOf(
            "meizu", "flyme", "aider-res", "bro.flyme"
        ),
        "趣头条广告 (Qutoutiao Ads)" to listOf(
            "qutoutiao", "qut", "qttad", "ad.qutoutiao"
        ),
        "搜狗广告 (Sogou Ads)" to listOf(
            "sogou", "sogoucdn", "theta.sogou"
        ),
        "360 广告联盟 (Qihoo Ads)" to listOf(
            "360ads", "adapi.360", "shuaji.360", "s.360"
        ),
        "讯飞广告 (iFlytek Ads)" to listOf(
            "voiceads", "iflyad", "iflytekad"
        ),
        "酷狗广告 (Kugou Ads)" to listOf(
            "kugouad", "adservice.kugou", "ads.service.kugou"
        ),
        "酷我广告 (Kuwo Ads)" to listOf(
            "kuwoad", "mobilead.kuwo", "rich.kuwo"
        ),
        "多盟 (Domob)" to listOf(
            "domob", "duomeng"
        ),
        "万普世纪 (Waps)" to listOf(
            "waps", "wapx"
        ),
        "艾德思奇 (adSage)" to listOf(
            "adsage", "adsage.cn", "adsage.com"
        ),
        "力美广告 (Limei)" to listOf(
            "limei", "adsalim"
        ),
        "触控广告 (Chukong)" to listOf(
            "imichuang", "chukong"
        ),
        "斗鱼广告 (Douyu Ads)" to listOf(
            "douyuad", "matchads.douyu", "rtbapi.douyu"
        ),
        "虎牙广告 (Huya Ads)" to listOf(
            "huyaad", "udblog.huya"
        ),
        "QXM (QXM Ads)" to listOf(
            "qxm", "qxmad", "qxmads", "52qumao", "qumao"
        ),
        "UBIX (UBIX Ads)" to listOf(
            "ubix", "ubixad", "ubixio", "ubxi", "ubixai", "ubiadx"
        ),
        "中关互动 (ZGHD)" to listOf(
            "zghd", "zhongguan", "zgad", "zhghd", "hxltad", "adintl"
        ),
        "Mintegral China (Mintegral)" to listOf(
            "mintegral", "mobvista", "mbridge", "mtgads"
        ),
        "TopOn (TopOn)" to listOf(
            "topon", "anythink", "toponad"
        ),
        "TradPlus (TradPlus)" to listOf(
            "tradplus", "tpbid", "tradplusad"
        ),
        "Beizi (Beizi)" to listOf(
            "beizi", "bzadx", "beizisdk"
        ),
        "AdScope (AdScope)" to listOf(
            "adscope", "aiclk", "adscopead"
        ),
        "Youmi (Youmi)" to listOf(
            "youmi", "adwo", "youmioffer"
        ),
        "多盟 (Domob)" to listOf(
            "domob", "duomeng"
        ),
        "易传媒 (Adsame)" to listOf(
            "adsame", "smartmad"
        ),
        "MediaV (MediaV)" to listOf(
            "mediav", "mvad", "mvads"
        ),
        "Bigo Ads (Bigo)" to listOf(
            "bigo", "bigo.sg", "likee"
        ),
        "Vpon (Vpon)" to listOf(
            "vpon", "vpadn"
        ),
        "Maticoo (Maticoo)" to listOf(
            "maticoo"
        ),
        "Kidoz (Kidoz)" to listOf(
            "kidoz"
        ),
        "Alphabet (Google)" to listOf(
            "google", "admob", "doubleclick", "firebase", "youtube", "gma", "adsense", "googleadmanager", "adservice"
        ),
        "Meta (Meta Platforms)" to listOf(
            "meta", "facebook", "instagram", "fb", "audiencenetwork", "whatsapp", "messenger"
        ),
        "Amazon (Amazon Ads)" to listOf(
            "amazon", "amzn", "aax", "twitch", "imdb", "aps.amazon"
        ),
        "Microsoft (Microsoft Ads)" to listOf(
            "microsoft", "msn", "bing", "xandr", "linkedin", "appnexus"
        ),
        "Apple (Apple Ads)" to listOf(
            "apple", "icloud", "itunes", "iad"
        ),
        "Samsung (Samsung Ads)" to listOf(
            "samsung"
        ),
        "X (Twitter)" to listOf(
            "twitter", "twimg", "tweet"
        ),
        "Snap (Snapchat)" to listOf(
            "snap", "snapchat"
        ),
        "Pinterest (Pinterest)" to listOf(
            "pinterest", "pin"
        ),
        "Reddit (Reddit)" to listOf(
            "reddit"
        ),
        "Unity (Unity Ads)" to listOf(
            "unity", "unityads", "delta-dna"
        ),
        "AppLovin (AppLovin)" to listOf(
            "applovin", "applvn", "max", "axon", "sparklabs"
        ),
        "ironSource (ironSource)" to listOf(
            "ironsource", "ironsrc", "supersonic", "levelplay"
        ),
        "Vungle (Liftoff)" to listOf(
            "vungle", "liftoff", "jetfuel"
        ),
        "Chartboost (Chartboost)" to listOf(
            "chartboost"
        ),
        "InMobi (InMobi)" to listOf(
            "inmobi", "aerserv"
        ),
        "Mintegral (Mintegral)" to listOf(
            "mintegral", "mobvista", "mbridge"
        ),
        "Moloco (Moloco)" to listOf(
            "moloco"
        ),
        "The Trade Desk (TTD)" to listOf(
            "ttd", "tradedesk", "adsrvr", "uid2", "uidapi"
        ),
        "PubMatic (PubMatic)" to listOf(
            "pubmatic", "openwrap", "hbopenbid"
        ),
        "PubNative (PubNative)" to listOf(
            "pubnative", "hybid"
        ),
        "Magnite (Magnite)" to listOf(
            "magnite", "rubicon", "spotx", "springserve"
        ),
        "OpenX (OpenX)" to listOf(
            "openx"
        ),
        "Index Exchange (Index Exchange)" to listOf(
            "indexexchange", "indexww", "casale", "jssecindexww"
        ),
        "Media.net (Media.net)" to listOf(
            "medianet", "contextualmedianet"
        ),
        "Taboola (Taboola)" to listOf(
            "taboola", "taboolasyndication"
        ),
        "Outbrain (Outbrain)" to listOf(
            "outbrain", "odboutbrain"
        ),
        "TripleLift (TripleLift)" to listOf(
            "triplelift"
        ),
        "AdColony (AdColony)" to listOf(
            "adcolony"
        ),
        "Ogury (Ogury)" to listOf(
            "ogury"
        ),
        "Digital Turbine (DT Exchange)" to listOf(
            "digitalturbine", "fyber", "inneractive", "dtexchange", "colossusssp"
        ),
        "Smaato (Smaato)" to listOf(
            "smaato"
        ),
        "Start.io (Start.io)" to listOf(
            "startio", "startapp"
        ),
        "Tapjoy (Tapjoy)" to listOf(
            "tapjoy"
        ),
        "Adjoe (adjoe)" to listOf(
            "adjoe"
        ),
        "LoopMe (LoopMe)" to listOf(
            "loopme"
        ),
        "Verve (Verve Group)" to listOf(
            "verve", "adtilt"
        ),
        "HyprMX (HyprMX)" to listOf(
            "hyprmx"
        ),
        "Smadex (Smadex)" to listOf(
            "smadex"
        ),
        "Maio (Maio)" to listOf(
            "maio"
        ),
        "Verizon Media (Yahoo/AOL)" to listOf(
            "yahoo", "aol", "flurry", "verizonmedia"
        ),
        "Oracle (Oracle Ads)" to listOf(
            "oracle", "moat", "bluekai", "addthis", "grapeshot"
        ),
        "Criteo (Criteo)" to listOf(
            "criteo", "hooklogic"
        ),
        "Yandex (Yandex Ads)" to listOf(
            "yandex", "appmetrica"
        ),
        "VK (VK Ads)" to listOf(
            "vk", "mytarget", "mailru", "vkad"
        ),
        "传音 (Transsion)" to listOf(
            "transsion", "tecno", "infinix", "itel", "phoenixbrowser"
        )
    )
    private val vendorSdkIdentifiers = linkedMapOf(
        "番茄小说 (Fanqie Novel)" to listOf(
            "番茄小说",
            "fanqie",
            "fqnovel",
            "com.dragon.read",
            "dragon.read"
        ),
        "七猫小说 (Qimao Novel)" to listOf(
            "七猫小说",
            "qimao",
            "com.kmxs.reader",
            "kmxs",
            "wtzw"
        ),
        "起点读书 (Qidian Reader)" to listOf(
            "起点读书",
            "qidian",
            "qdreader",
            "com.qidian.QDReader",
            "yuewen"
        ),
        "QQ阅读 (QQ Reader)" to listOf(
            "QQ阅读",
            "qqreader",
            "com.qq.reader",
            "qqread"
        ),
        "书旗小说 (Shuqi Novel)" to listOf(
            "书旗小说",
            "shuqi",
            "com.shuqi.controller",
            "aliwx"
        ),
        "掌阅 (iReader)" to listOf(
            "掌阅",
            "ireader",
            "com.chaozh.iReaderFree",
            "zhangyue"
        ),
        "咪咕阅读 (Migu Read)" to listOf(
            "咪咕阅读",
            "migu",
            "cmread",
            "com.ophone.reader.ui"
        ),
        "米读小说 (Midu Novel)" to listOf(
            "米读小说",
            "midu",
            "miduread",
            "com.lechuan.mdwz"
        ),
        "纵横小说 (Zongheng Novel)" to listOf(
            "纵横小说",
            "zongheng",
            "com.zongheng.reader"
        ),
        "17K 小说 (17K Novel)" to listOf(
            "17k小说",
            "17k",
            "book17k"
        ),
        "长读小说 (Changdu Novel)" to listOf(
            "长读小说",
            "changdu",
            "com.changdu.ereader"
        ),
        "QXM (QXM Ads)" to listOf(
            "qxm",
            "趣小猫广告",
            "com.qxm.ad",
            "com.qumao.ad",
            "52qumao"
        ),
        "UBIX (UBIX Ads)" to listOf(
            "ubxi",
            "ubix",
            "ubiadx",
            "ubixai",
            "com.ubix.ad",
            "com.ubixai.sdk"
        ),
        "中关互动 (ZGHD)" to listOf(
            "中关互动",
            "hxltad",
            "adintl",
            "com.zghd.ad",
            "com.hxltad.sdk",
            "com.adintl.ad"
        ),
        "趣盟广告 (Qumeng Ads)" to listOf(
            "qumeng",
            "qmob",
            "qtmojo",
            "qmadsdk",
            "com.qumeng.ad",
            "com.qumeng.advlib"
        ),
        "TopOn 聚合广告 (TopOn)" to listOf(
            "topon",
            "anythink",
            "toponad",
            "com.anythink",
            "com.topon"
        ),
        "TradPlus 聚合广告 (TradPlus)" to listOf(
            "tradplus",
            "tpbid",
            "tradplusad",
            "com.tradplus"
        ),
        "Beizi 广告 (Beizi)" to listOf(
            "beizi",
            "bzadx",
            "beizisdk",
            "com.beizi"
        ),
        "AdScope 聚合广告 (AdScope)" to listOf(
            "adscope",
            "aiclk",
            "adscopead",
            "com.adscope"
        ),
        "有米广告 (Youmi)" to listOf(
            "youmi",
            "adwo",
            "youmioffer",
            "com.youmi"
        ),
        "Meta 平台 (Meta Platforms)" to listOf(
            "audiencenetwork",
            "facebook",
            "fbcdn",
            "com.facebook.ads"
        )
    )
    private val vendorAliases = mapOf(
        "Google (Google Ads)" to "Alphabet (Google)",
        "Meta (Facebook)" to "Meta (Meta Platforms)",
        "阿里 (Alibaba)" to "阿里巴巴集团 (Alibaba Group)",
        "阿里妈妈 (Alimama)" to "阿里巴巴集团 (Alibaba Group)",
        "友盟+ (Umeng+)" to "阿里巴巴集团 (Alibaba Group)",
        "优酷 (Youku)" to "阿里巴巴集团 (Alibaba Group)",
        "快手联盟 (Kwai Business)" to "快手 (Kuaishou)",
        "磁力引擎 (Kwai Ads)" to "快手 (Kuaishou)",
        "优量汇 (Tencent Marketing)" to "腾讯 (Tencent)",
        "腾讯广告 (Tencent Ads)" to "腾讯 (Tencent)",
        "百青藤 (Baidu Union)" to "百度 (Baidu)",
        "京东 (JD)" to "京东 (JD.com)",
        "Fyber (Digital Turbine)" to "Digital Turbine (DT Exchange)",
        "穿山甲 (Pangle)" to "字节跳动 (ByteDance)",
        "百度联盟 (Baidu Union)" to "百度 (Baidu)",
        "华为广告 (Huawei Ads)" to "华为 (Huawei)",
        "小米广告 (Xiaomi Ads)" to "小米 (Xiaomi)",
        "OPPO 广告 (OPPO Ads)" to "OPPO (HeyTap)",
        "vivo 广告 (vivo Ads)" to "vivo (vivo Ads)",
        "Mintegral China (Mintegral)" to "Mintegral (Mintegral)",
        "VIVO (vivo Ads)" to "vivo (vivo Ads)",
        "QXM (QXM Ads)" to "趣小猫 (QXM Ads)",
        "UBIX (UBIX Ads)" to "优比客思 (UBIX Ads)",
        "TalkingData (TalkingData)" to "腾云天下 (TalkingData)",
        "AdMaster (AdMaster)" to "精硕科技 (AdMaster)",
        "Sigmob (Sigmob)" to "Sigmob 聚效广告 (Sigmob)",
        "MobTech (MobTech)" to "MobTech 魔方科技 (MobTech)",
        "TopOn (TopOn)" to "TopOn 聚合广告 (TopOn)",
        "TradPlus (TradPlus)" to "TradPlus 聚合广告 (TradPlus)",
        "Beizi (Beizi)" to "Beizi 广告 (Beizi)",
        "AdScope (AdScope)" to "AdScope 聚合广告 (AdScope)",
        "Youmi (Youmi)" to "有米广告 (Youmi)",
        "MediaV (MediaV)" to "MediaV 广告 (MediaV)",
        "Bigo Ads (Bigo)" to "Bigo 广告 (Bigo)",
        "Vpon (Vpon)" to "Vpon 广告 (Vpon)",
        "Maticoo (Maticoo)" to "Maticoo 广告 (Maticoo)",
        "Kidoz (Kidoz)" to "Kidoz 广告 (Kidoz)",
        "Alphabet (Google)" to "谷歌 (Google)",
        "Meta (Meta Platforms)" to "Meta 平台 (Meta Platforms)",
        "Amazon (Amazon Ads)" to "亚马逊 (Amazon Ads)",
        "Microsoft (Microsoft Ads)" to "微软 (Microsoft Ads)",
        "Apple (Apple Ads)" to "苹果 (Apple Ads)",
        "Samsung (Samsung Ads)" to "三星 (Samsung Ads)",
        "X (Twitter)" to "X 平台 (Twitter)",
        "Snap (Snapchat)" to "Snap 平台 (Snapchat)",
        "Pinterest (Pinterest)" to "Pinterest 平台 (Pinterest)",
        "Reddit (Reddit)" to "Reddit 平台 (Reddit)",
        "Unity (Unity Ads)" to "Unity 广告 (Unity Ads)",
        "AppLovin (AppLovin)" to "AppLovin 广告 (AppLovin)",
        "ironSource (ironSource)" to "ironSource 广告 (ironSource)",
        "Vungle (Liftoff)" to "Vungle 广告 (Liftoff)",
        "Chartboost (Chartboost)" to "Chartboost 广告 (Chartboost)",
        "InMobi (InMobi)" to "InMobi 广告 (InMobi)",
        "Mintegral (Mintegral)" to "Mintegral 广告 (Mintegral)",
        "Moloco (Moloco)" to "Moloco 广告 (Moloco)",
        "The Trade Desk (TTD)" to "Trade Desk 广告 (TTD)",
        "PubMatic (PubMatic)" to "PubMatic 广告 (PubMatic)",
        "Magnite (Magnite)" to "Magnite 广告 (Magnite)",
        "OpenX (OpenX)" to "OpenX 广告 (OpenX)",
        "Index Exchange (Index Exchange)" to "Index Exchange 广告 (Index Exchange)",
        "Media.net (Media.net)" to "Media.net 广告 (Media.net)",
        "Taboola (Taboola)" to "Taboola 广告 (Taboola)",
        "Outbrain (Outbrain)" to "Outbrain 广告 (Outbrain)",
        "TripleLift (TripleLift)" to "TripleLift 广告 (TripleLift)",
        "AdColony (AdColony)" to "AdColony 广告 (AdColony)",
        "Ogury (Ogury)" to "Ogury 广告 (Ogury)",
        "Digital Turbine (DT Exchange)" to "Digital Turbine 广告 (DT Exchange)",
        "Smaato (Smaato)" to "Smaato 广告 (Smaato)",
        "Start.io (Start.io)" to "Start.io 广告 (Start.io)",
        "Tapjoy (Tapjoy)" to "Tapjoy 广告 (Tapjoy)",
        "Verizon Media (Yahoo/AOL)" to "Verizon Media 广告 (Yahoo/AOL)",
        "Oracle (Oracle Ads)" to "甲骨文广告 (Oracle Ads)",
        "Criteo (Criteo)" to "Criteo 广告 (Criteo)",
        "Yandex (Yandex Ads)" to "Yandex 广告 (Yandex Ads)",
        "VK (VK Ads)" to "VK 广告 (VK Ads)"
    )
    private val unsupportedAdGuardModifiers = setOf(
        "content",
        "extension"
    )
    private val ignorableAdGuardModifiers = setOf(
        "all"
    )
    private val geositeAdCategoryTokens = setOf(
        "ad",
        "ads",
        "category-ads",
        "category-ads-all",
        "advertising",
        "tracker",
        "tracking",
        "malware",
        "phishing"
    )
    private val geositeAdSeedDomains = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "admob.com",
        "adnxs.com",
        "adsrvr.org",
        "pubmatic.com",
        "openx.net",
        "smaato.net",
        "taboola.com",
        "outbrain.com",
        "applovin.com",
        "ironsrc.com",
        "unityads.unity3d.com",
        "vungle.com",
        "mintegral.com",
        "pangolin-sdk-toutiao.com",
        "pglstatp-toutiao.com",
        "gdt.qq.com",
        "adsmind.apdcdn.tc.qq.com",
        "tanx.com",
        "alimama.com"
    )

    fun getRules(context: Context): List<BlockRule> {
        cachedRules?.let { return it }
        synchronized(cacheLock) {
            cachedRules?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = readRulesJson(context, prefs)
            val type = object : TypeToken<List<BlockRule>>() {}.type
            var migrated = false
            val rules = (gson.fromJson<List<BlockRule>>(json, type) ?: emptyList())
                .map {
                    val stableId = it.id.trim().ifBlank {
                        migrated = true
                        UUID.randomUUID().toString()
                    }
                    copyBlockRule(
                        it,
                        id = stableId,
                        vendor = normalizeVendorName(it.vendor),
                        source = if (it.source == RuleSource.REFERENCE) RuleSource.IMPORTED else it.source
                    )
                }
                .sortedBy { it.domain }
            if (migrated) {
                writeRulesFile(context, rules)
            }
            updateRuleCache(rules)
            return rules
        }
    }

    fun getRuleCount(context: Context): Int {
        cachedRules?.let { return it.size }
        cachedRuleCount?.let { return it }
        synchronized(cacheLock) {
            cachedRules?.let { return it.size }
            cachedRuleCount?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val storedCount = prefs.getInt(KEY_RULE_COUNT, -1)
            if (storedCount >= 0) {
                cachedRuleCount = storedCount
                return storedCount
            }
            val rules = getRules(context)
            val count = rules.size
            prefs.edit().putInt(KEY_RULE_COUNT, count).apply()
            cachedRuleCount = count
            return count
        }
    }
    
    // DNS 拦截决策缓存（减少重复计算）
    private val dnsBlockDecisionCache = object : LinkedHashMap<String, Pair<Boolean, Long>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Boolean, Long>>?): Boolean {
            return size > 512
        }
    }
    private val dnsBlockDecisionLock = Any()
    private const val DECISION_TTL_MS = 5000L // 5 秒缓存

    fun prewarmCaches(context: Context) {
        if (cachedRules != null) return
        getRules(context)
        getRuleMap(context)
        getRegexRules(context)
        getCosmeticRules(context)
        getKeywordRules(context)
    }

    fun addRule(context: Context, rawDomain: String, source: RuleSource): BlockRule? {
        val domain = sanitizeDomain(rawDomain) ?: return null
        val addState = buildManualAddState(context)
        if (!addState.existingDomains.add(domain)) return null
        val rule = buildNormalizedBlockRule(context, domain, source)
        appendAndSaveRules(context, addState.current, listOf(rule))
        return rule
    }

    fun addRules(context: Context, rawInput: String, source: RuleSource, allowWhitelistDomains: Boolean = false): List<BlockRule> {
        return addNormalizedRules(context, parseManualInput(rawInput), source, allowWhitelistDomains)
    }

    fun addRules(context: Context, domains: Collection<String>, source: RuleSource, allowWhitelistDomains: Boolean = false): List<BlockRule> {
        if (domains.isEmpty()) return emptyList()
        val userOwnedSource = source == RuleSource.MANUAL || source == RuleSource.IMPORTED
        val normalizedDomains = domains.mapNotNull(::sanitizeDomain)
            .filter { userOwnedSource || allowWhitelistDomains || !isWhitelistedDomain(it) }
            .distinct()
        return addNormalizedRules(context, normalizedDomains, source, allowWhitelistDomains = true)
    }

    fun getRemoteRuleSources(context: Context): List<RemoteRuleSourceConfig> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_REMOTE_RULE_SOURCES, null)
        if (json.isNullOrBlank()) {
            return emptyList()
        }
        val type = object : TypeToken<List<RemoteRuleSourceConfig>>() {}.type
        val stored = runCatching { gson.fromJson<List<RemoteRuleSourceConfig>>(json, type) }
            .getOrNull()
            .orEmpty()
            .mapNotNull(::sanitizeRemoteRuleSource)
            .filterNot(::isLegacyBuiltInRemoteRuleSource)
        if (stored.isEmpty() && json.isNotBlank()) {
            LogRepository.append(context, "Remote rule sources JSON is valid but stored list is empty after sanitization, preserving original JSON to avoid data loss")
        }
        return stored.sortedBy { it.name.lowercase() }
    }

    fun saveRemoteRuleSources(context: Context, sources: List<RemoteRuleSourceConfig>) {
        val normalizedSources = sources.mapNotNull(::sanitizeRemoteRuleSource)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMOTE_RULE_SOURCES, gson.toJson(normalizedSources.sortedBy { it.name.lowercase() }))
            .apply()
    }

    fun updateRemoteRuleSource(context: Context, updated: RemoteRuleSourceConfig) {
        val sanitized = normalizeRemoteRuleSource(updated) ?: return
        val current = getRemoteRuleSources(context)
        val next = current.map { source -> if (source.id == sanitized.id) sanitized else source }
        saveRemoteRuleSources(context, next)
    }

    fun addRemoteRuleSource(context: Context, source: RemoteRuleSourceConfig) {
        val normalizedSource = normalizeRemoteRuleSource(source) ?: return
        val current = getRemoteRuleSources(context)
        val exists = current.any {
            it.id == normalizedSource.id || it.url.equals(normalizedSource.url, ignoreCase = true)
        }
        if (exists) return
        saveRemoteRuleSources(context, current + normalizedSource)
    }

    fun removeRemoteRuleSource(context: Context, sourceId: String) {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        if (normalizedSourceId.isBlank()) return
        saveRemoteRuleSources(context, filterRemoteRuleSources(context, normalizedSourceId))
    }

    fun isBuiltInRemoteRuleSource(sourceId: String): Boolean {
        return false
    }

    fun getRulesForRemoteSource(context: Context, sourceId: String): List<BlockRule> {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        if (normalizedSourceId.isBlank()) return emptyList()
        return getRules(context).filter { hasRemoteSourceId(it, normalizedSourceId) }
    }

    fun getRemoteRuleSourceName(context: Context, sourceId: String?): String? {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        if (normalizedSourceId.isBlank()) return null
        return findRemoteRuleSource(context, normalizedSourceId)?.name
    }

    fun removeRulesForRemoteSource(context: Context, sourceId: String): Int {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        val current = getRules(context)
        val sourceRules = getRulesForRemoteSource(context, normalizedSourceId)
        if (sourceRules.isEmpty()) return 0
        val remaining = current.filterNot { hasRemoteSourceId(it, normalizedSourceId) }
        val removedCount = sourceRules.size
        if (removedCount > 0) {
            save(context, remaining)
        }
        return removedCount
    }

    fun replaceRulesForRemoteSource(context: Context, sourceId: String, content: String, allowWhitelistDomains: Boolean = false): Int {
        val startTime = System.currentTimeMillis()
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        
        // Step 1: 获取现有规则（排除要替换的规则源）
        val step1Start = System.currentTimeMillis()
        val baseRules = buildRemoteSourceReplacementBaseRules(context, normalizedSourceId)
        LogRepository.append(context, "replaceRulesForRemoteSource [Step1/4]: get base rules in ${System.currentTimeMillis() - step1Start}ms, baseRules=${baseRules.size}")
        
        // Step 2: 构建状态（复用 baseRules 的规则 keys）
        val step2Start = System.currentTimeMillis()
        val importState = buildImportedRuleState(baseRules)
        LogRepository.append(context, "replaceRulesForRemoteSource [Step2/4]: build state in ${System.currentTimeMillis() - step2Start}ms, existingKeys=${importState.existingRuleKeys.size}")
        
        // Step 3: 解析规则
        val step3Start = System.currentTimeMillis()
        val parsed = parseImportLines(content)
        LogRepository.append(context, "replaceRulesForRemoteSource [Step3/4]: parse in ${System.currentTimeMillis() - step3Start}ms, parsed=${parsed.blockedRules.size + parsed.exceptionRules.size + parsed.badfilterRules.size}")
        
        // Step 4: 收集并保存
        val step4Start = System.currentTimeMillis()
        val added = collectImportedBlockedRules(
            context = context,
            blockedRules = parsed.blockedRules,
            existingRuleKeys = importState.existingRuleKeys,
            source = RuleSource.IMPORTED,
            allowWhitelistDomains = allowWhitelistDomains,
            remoteSourceId = normalizedSourceId,
            useVendorHints = false,
            identityRemoteSourceId = normalizedSourceId
        )
        LogRepository.append(context, "replaceRulesForRemoteSource [Step4/4]: collect blocked in ${System.currentTimeMillis() - step4Start}ms, added=${added.size}")
        
        saveImportedRules(context, baseRules + added, parsed.exceptionRules)
        
        val totalTime = System.currentTimeMillis() - startTime
        LogRepository.append(context, "replaceRulesForRemoteSource: source=$sourceId, TOTAL time=${totalTime}ms, finalRules=${baseRules.size + added.size}")
        return added.size
    }

    // P0.4 新增：流式替换规则源（使用 InputStream，避免大文件 OOM）
    fun replaceRulesForRemoteSourceStreaming(
        context: Context,
        sourceId: String,
        inputStream: InputStream,
        allowWhitelistDomains: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Int {
        val startTime = System.currentTimeMillis()
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        onProgress?.invoke("正在读取现有规则...")
        val baseStart = System.currentTimeMillis()
        val baseRules = buildRemoteSourceReplacementBaseRules(context, normalizedSourceId)
        LogRepository.append(context, "replaceRulesForRemoteSourceStreaming [1/4]: base rules=${baseRules.size}, time=${System.currentTimeMillis() - baseStart}ms")

        onProgress?.invoke("正在建立去重索引...")
        val stateStart = System.currentTimeMillis()
        val importState = buildImportedRuleState(baseRules)
        LogRepository.append(context, "replaceRulesForRemoteSourceStreaming [2/4]: state keys=${importState.existingRuleKeys.size}, time=${System.currentTimeMillis() - stateStart}ms")

        try {
            onProgress?.invoke("正在解析规则文件...")
            val parseStart = System.currentTimeMillis()
            val parsed = parseImportLinesStreaming(inputStream.bufferedReader().lineSequence())
            LogRepository.append(
                context,
                "replaceRulesForRemoteSourceStreaming [3/4]: parsed blocked=${parsed.blockedRules.size}, exceptions=${parsed.exceptionRules.size}, badfilters=${parsed.badfilterRules.size}, time=${System.currentTimeMillis() - parseStart}ms"
            )

            onProgress?.invoke("正在整理规则并去重...")
            val collectStart = System.currentTimeMillis()
            val added = collectImportedBlockedRules(
                context = context,
                blockedRules = parsed.blockedRules,
                existingRuleKeys = importState.existingRuleKeys,
                source = RuleSource.IMPORTED,
                allowWhitelistDomains = allowWhitelistDomains,
                remoteSourceId = normalizedSourceId,
                useVendorHints = false,
                identityRemoteSourceId = normalizedSourceId
            )
            LogRepository.append(context, "replaceRulesForRemoteSourceStreaming [4/5]: collected added=${added.size}, time=${System.currentTimeMillis() - collectStart}ms")

            onProgress?.invoke("正在保存规则到本地...")
            val saveStart = System.currentTimeMillis()
            saveImportedRules(context, baseRules + added, parsed.exceptionRules)
            LogRepository.append(context, "replaceRulesForRemoteSourceStreaming [5/5]: saved final=${baseRules.size + added.size}, time=${System.currentTimeMillis() - saveStart}ms")

            val totalTime = System.currentTimeMillis() - startTime
            LogRepository.append(
                context,
                "replaceRulesForRemoteSourceStreaming: source=$sourceId, added=${added.size}, exceptions=${parsed.exceptionRules.size}, badfilters=${parsed.badfilterRules.size}, time=${totalTime}ms"
            )
            return added.size
        } catch (e: Exception) {
            LogRepository.append(context, "规则源解析失败：${e.message ?: e.javaClass.simpleName}")
            throw e
        } finally {
            inputStream.close()
        }
    }

    fun filterRemoteSourceNonAds(context: Context, sourceId: String): Int {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        val sourceRules = getRulesForRemoteSource(context, normalizedSourceId)
        if (sourceRules.isEmpty()) return 0
        val removableIds = getRemoteSourceNonAdCandidates(context, normalizedSourceId).map { it.rule.id }.toSet()
        if (removableIds.isEmpty()) return 0
        removeByIds(context, removableIds)
        return removableIds.size
    }

    fun getRemoteSourceNonAdCandidates(context: Context, sourceId: String): List<RemoteRuleRemovalCandidate> {
        val sourceRules = getRulesForRemoteSource(context, sourceId)
        if (sourceRules.isEmpty()) return emptyList()
        return sourceRules.mapNotNull { rule -> explainRemoteSourceNonAdCandidate(context, rule) }
    }

    fun importRules(
        context: Context,
        content: String,
        source: RuleSource = RuleSource.IMPORTED,
        allowWhitelistDomains: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Int {
        val startTime = System.currentTimeMillis()
        
        // 优化：使用 lineSequence 避免一次性加载计数
        var lineCount = 0
        content.lineSequence().forEach { lineCount++ }
        LogRepository.append(context, "ImportRules started: lines=$lineCount, source=$source, allowWhitelist=$allowWhitelistDomains")
        
        // 检测可能影响正常网络功能的规则（仅对真正的正常服务域名提醒，不对广告域名提醒）
        val networkAffectingRules = detectNetworkAffectingRules(content)
        if (networkAffectingRules.isNotEmpty()) {
            LogRepository.append(context, "⚠️ 提醒：检测到 ${networkAffectingRules.size} 条规则可能影响正常网络功能（命中保护域名）：")
            networkAffectingRules.take(10).forEach { detail ->
                LogRepository.append(context, "   - $detail")
            }
            if (networkAffectingRules.size > 10) {
                LogRepository.append(context, "   ... 还有 ${networkAffectingRules.size - 10} 条")
            }
            LogRepository.append(context, "⚠️ 这些规则会正常导入并拦截，如导致 App 功能异常请将相关域名加入白名单")
        }
        
        onProgress?.invoke("正在读取现有规则...")
        val step1Start = System.currentTimeMillis()
        val current = getRules(context).toMutableList()
        LogRepository.append(context, "ImportRules [Step1/4]: get existing rules in ${System.currentTimeMillis() - step1Start}ms, count=${current.size}")
        
        onProgress?.invoke("正在解析规则文件...")
        val step2Start = System.currentTimeMillis()
        val parsed = parseImportLines(content)
        LogRepository.append(context, "ImportRules [Step2/4]: parse in ${System.currentTimeMillis() - step2Start}ms, blocked=${parsed.blockedRules.size}, exceptions=${parsed.exceptionRules.size}, badfilter=${parsed.badfilterRules.size}")
        
        onProgress?.invoke("正在整理规则并去重...")
        val step3Start = System.currentTimeMillis()
        val finalRules = buildImportedRules(
            context = context,
            current = current,
            parsed = parsed,
            source = source,
            allowWhitelistDomains = allowWhitelistDomains
        )
        LogRepository.append(context, "ImportRules [Step3/4]: build in ${System.currentTimeMillis() - step3Start}ms, finalRules=${finalRules.size}")
        
        onProgress?.invoke("正在保存规则到本地...")
        val step4Start = System.currentTimeMillis()
        save(context, finalRules)
        val elapsed = System.currentTimeMillis() - startTime
        LogRepository.append(context, "ImportRules [Step4/4]: save in ${System.currentTimeMillis() - step4Start}ms")
        LogRepository.append(context, "ImportRules completed: finalRules=${finalRules.size} TOTAL time=${elapsed}ms")
        return finalRules.size
    }
    
    // P0.4 新增：大规则文件流式解析（避免 OOM）
    fun importRulesStreaming(
        context: Context,
        inputStream: InputStream,
        source: RuleSource = RuleSource.IMPORTED,
        allowWhitelistDomains: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Int {
        val startTime = System.currentTimeMillis()

        onProgress?.invoke("正在解析规则文件...")
        val parseOnlyStart = System.currentTimeMillis()
        val parsed = inputStream.bufferedReader().use { reader ->
            parseImportLinesStreaming(reader.lineSequence()) { lineCount, ruleCount ->
                onProgress?.invoke("正在解析规则文件...\n已读取 ${lineCount} 行，识别 ${ruleCount} 条")
            }
        }
        LogRepository.append(
            context,
            "ImportRulesStreaming [1/4]: parsed blocked=${parsed.blockedRules.size}, exceptions=${parsed.exceptionRules.size}, badfilter=${parsed.badfilterRules.size}, time=${System.currentTimeMillis() - parseOnlyStart}ms"
        )

        onProgress?.invoke("正在读取现有规则...")
        val currentStart = System.currentTimeMillis()
        val current = getRules(context).toMutableList()
        LogRepository.append(context, "ImportRulesStreaming [2/4]: current=${current.size}, time=${System.currentTimeMillis() - currentStart}ms")

        onProgress?.invoke("正在整理规则并去重...")
        val buildStart = System.currentTimeMillis()
        val finalRules = buildImportedRules(context, current, parsed, source, allowWhitelistDomains)
        LogRepository.append(context, "ImportRulesStreaming [3/4]: finalRules=${finalRules.size}, time=${System.currentTimeMillis() - buildStart}ms")

        onProgress?.invoke("正在保存规则到本地...")
        val saveStart = System.currentTimeMillis()
        save(context, finalRules)
        LogRepository.append(context, "ImportRulesStreaming [4/4]: save time=${System.currentTimeMillis() - saveStart}ms")
        
        val elapsed = System.currentTimeMillis() - startTime
        LogRepository.append(context, "ImportRulesStreaming completed: finalRules=${finalRules.size} TOTAL time=${elapsed}ms")
        
        return finalRules.size
    }
    
    // 检测可能影响正常网络功能的规则（精准识别，只提醒真正的正常服务域名）
    private fun detectNetworkAffectingRules(content: String): List<String> {
        val affectingRules = mutableListOf<String>()
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@forEach
            
            // 检测是否包含网络层修饰符
            val hasNetworkModifier = trimmed.contains("\$network", ignoreCase = true) ||
                trimmed.contains("\$blockipv6", ignoreCase = true) ||
                trimmed.contains("\$blockipv4", ignoreCase = true) ||
                trimmed.contains("\$dnsrewrite=", ignoreCase = true) ||
                trimmed.contains("\$client=", ignoreCase = true) ||
                trimmed.contains("\$mac=", ignoreCase = true) ||
                trimmed.contains("\$asn=", ignoreCase = true)
            
            // 检测是否是宽泛规则
            val isWildcardRule = trimmed.startsWith("||*^") || trimmed == "*" || trimmed.startsWith("all:")
            
            if (hasNetworkModifier || isWildcardRule) {
                // 提取规则中的域名
                val domain = extractDomainFromRule(trimmed)
                if (domain != null) {
                    // 只有命中真正的保护域名才提醒（广告域名不提醒）
                    if (isProtectedNormalServiceDomain(domain)) {
                        affectingRules += "${extractRulePreview(trimmed)} → 命中保护域名：$domain"
                    }
                } else if (isWildcardRule) {
                    // 宽泛的全局规则直接提醒
                    affectingRules += "${extractRulePreview(trimmed)} → 全局规则"
                }
            }
        }
        return affectingRules
    }
    
    // 从规则中提取域名
    private fun extractDomainFromRule(rule: String): String? {
        // AdGuard 格式：||domain.com^
        val adguardMatch = Regex("""\|\|([a-z0-9.-]+\.[a-z]+)\^""").find(rule)
        if (adguardMatch != null) return adguardMatch.groupValues[1]
        
        // Hosts 格式：0.0.0.0 domain.com
        val hostsMatch = Regex("""^(?:0\.0\.0\.0|127\.0\.0\.1)\s+([a-z0-9.-]+\.[a-z]+)""", RegexOption.IGNORE_CASE).find(rule)
        if (hostsMatch != null) return hostsMatch.groupValues[1]
        
        // dnsmasq 格式：address=/domain.com/
        val dnsmasqMatch = Regex("""address=/([a-z0-9.-]+\.[a-z]+)/""").find(rule)
        if (dnsmasqMatch != null) return dnsmasqMatch.groupValues[1]
        
        // Clash 格式：DOMAIN-SUFFIX,domain.com
        val clashMatch = Regex("""(?:DOMAIN-SUFFIX|HOST-SUFFIX|DOMAIN|HOST),([a-z0-9.-]+\.[a-z]+)""", RegexOption.IGNORE_CASE).find(rule)
        if (clashMatch != null) return clashMatch.groupValues[1]
        
        return null
    }
    
    // 精准判断是否是真正的正常服务域名（不是广告域名）
    private fun isProtectedNormalServiceDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lower = normalized.lowercase()
        
        // 1. 首先检查是否是广告域名（广告域名不提醒）
        if (looksLikeAdDomain(normalized)) return false
        
        // 2. 检查是否在白名单域名列表（真正的正常服务）
        if (whitelistDomains.contains(lower)) return true
        if (whitelistDomains.any { lower.endsWith(".$it") }) return true
        
        // 3. 检查是否是游戏核心域名
        if (gameCoreDomains.contains(lower)) return true
        if (gameCoreDomains.any { lower.endsWith(".$it") }) return true
        
        // 4. 其他情况不提醒（让用户自己判断）
        return false
    }
    
    // 提取规则预览（截断过长内容）
    private fun extractRulePreview(rule: String): String {
        return rule.take(150).let { if (rule.length > 150) "$it..." else it }
    }

    private fun buildRemoteSourceReplacementBaseRules(
        context: Context,
        sourceId: String
    ): MutableList<BlockRule> {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        return getRules(context)
            .filterNot { hasRemoteSourceId(it, normalizedSourceId) }
            .toMutableList()
    }

    private fun filterRemoteRuleSources(
        context: Context,
        sourceId: String
    ): List<RemoteRuleSourceConfig> {
        return getRemoteRuleSources(context).filterNot { it.id == sourceId }
    }

    private fun findRemoteRuleSource(
        context: Context,
        sourceId: String
    ): RemoteRuleSourceConfig? {
        return getRemoteRuleSources(context).firstOrNull { it.id == sourceId }
    }

    private fun hasRemoteSourceId(rule: BlockRule, sourceId: String): Boolean {
        return normalizeRemoteSourceId(rule.remoteSourceId) == sourceId
    }

    private fun buildImportedRules(
        context: Context,
        current: MutableList<BlockRule>,
        parsed: ParsedRules,
        source: RuleSource,
        allowWhitelistDomains: Boolean
    ): List<BlockRule> {
        val importState = buildImportedRuleState(current)
        applyBadfilterScopes(importState.currentByDomain, parsed.badfilterRules)
        applyImportedBlockedRules(context, current, importState.currentByDomain, importState.existingRuleKeys, parsed.blockedRules, source, allowWhitelistDomains)
        applyExceptionScopes(importState.currentByDomain, parsed.exceptionRules)
        return finalizeImportedRules(
            buildImportedRuleCollections(
                current = current,
                currentByDomain = importState.currentByDomain,
                exceptionRules = parsed.exceptionRules
            )
        )
    }

    private fun normalizeRemoteSourceId(sourceId: String?): String {
        return sourceId?.trim().orEmpty()
    }

    private fun normalizeRemoteRuleSource(source: RemoteRuleSourceConfig): RemoteRuleSourceConfig? {
        return sanitizeRemoteRuleSource(source)
    }

    private data class ImportedRuleState(
        val currentByDomain: MutableMap<String, BlockRule>,
        val existingRuleKeys: MutableSet<String>
    )

    private data class ImportedRuleCollections(
        val mergedRules: List<BlockRule>,
        val exceptionRules: List<ParsedRule>
    )

    private fun buildImportedRuleState(current: List<BlockRule>): ImportedRuleState {
        return ImportedRuleState(
            currentByDomain = buildCurrentRuleDomainMap(current),
            existingRuleKeys = current.mapTo(linkedSetOf()) { buildRuleIdentityKey(it) }
        )
    }

    private fun addNormalizedRules(
        context: Context,
        domains: Collection<String>,
        source: RuleSource,
        allowWhitelistDomains: Boolean
    ): List<BlockRule> {
        if (domains.isEmpty()) return emptyList()
        val addState = buildManualAddState(context)
        val added = mutableListOf<BlockRule>()
        val userOwnedSource = source == RuleSource.MANUAL || source == RuleSource.IMPORTED
        domains.forEach { domain ->
            if (!userOwnedSource && !allowWhitelistDomains && isWhitelistedDomain(domain)) return@forEach
            if (addState.existingDomains.add(domain)) {
                added += buildNormalizedBlockRule(context, domain, source)
            }
        }
        appendAndSaveRules(context, addState.current, added)
        return added
    }

    private data class ManualAddState(
        val current: MutableList<BlockRule>,
        val existingDomains: MutableSet<String>
    )

    private fun buildManualAddState(context: Context): ManualAddState {
        val current = getRules(context).toMutableList()
        return ManualAddState(
            current = current,
            existingDomains = current.mapTo(linkedSetOf()) { it.domain }
        )
    }

    private fun buildNormalizedBlockRule(
        context: Context,
        domain: String,
        source: RuleSource
    ): BlockRule {
        return BlockRule(
            id = UUID.randomUUID().toString(),
            domain = domain,
            vendor = classifyVendor(context, domain),
            source = source
        )
    }

    private fun appendAndSaveRules(
        context: Context,
        current: MutableList<BlockRule>,
        added: List<BlockRule>
    ) {
        if (added.isEmpty()) return
        current += added
        save(context, current)
    }

    private fun collectImportedBlockedRules(
        context: Context,
        blockedRules: List<ParsedRule>,
        existingRuleKeys: MutableSet<String>,
        source: RuleSource,
        allowWhitelistDomains: Boolean,
        remoteSourceId: String? = null,
        useVendorHints: Boolean,
        identityRemoteSourceId: String? = null
    ): List<BlockRule> {
        val added = mutableListOf<BlockRule>()
        forEachImportableBlockedRule(blockedRules, allowWhitelistDomains) { blockedRule ->
            val addedRule = addParsedRuleIfAbsent(
                context = context,
                existingRuleKeys = existingRuleKeys,
                parsedRule = blockedRule,
                source = source,
                remoteSourceId = remoteSourceId,
                useVendorHints = useVendorHints,
                identityRemoteSourceId = identityRemoteSourceId
            ) ?: return@forEachImportableBlockedRule
            added += addedRule
        }
        return added
    }

    private fun saveImportedRules(
        context: Context,
        rules: List<BlockRule>,
        exceptionRules: List<ParsedRule>
    ) {
        // 优化：跳过排序提升性能（规则顺序不影响匹配）
        // 仅当规则数量较少时才排序，减少 CPU 开销
        val finalRules = if (rules.size <= 1000) {
            finalizeImportedRules(ImportedRuleCollections(rules, exceptionRules))
        } else {
            // 大规则集不排序，直接保存
            applyCosmeticExceptionRules(rules, exceptionRules)
        }
        save(context, finalRules)
    }

    private fun buildCurrentRuleDomainMap(current: List<BlockRule>): MutableMap<String, BlockRule> {
        return current
            .filter { it.regexPattern == null && it.cosmeticSelector == null }
            .associateBy { it.domain }
            .toMutableMap()
    }

    private fun applyBadfilterScopes(
        currentByDomain: MutableMap<String, BlockRule>,
        badfilterRules: List<ParsedRule>
    ) {
        badfilterRules.forEach { badfilter ->
            val existing = currentByDomain[badfilter.domain] ?: return@forEach
            updateDomainRuleScope(currentByDomain, badfilter.domain, existing, badfilter.dnsTypes, badfilter.excludedDnsTypes)
        }
    }

    private fun applyImportedBlockedRules(
        context: Context,
        current: MutableList<BlockRule>,
        currentByDomain: MutableMap<String, BlockRule>,
        existingRuleKeys: MutableSet<String>,
        blockedRules: List<ParsedRule>,
        source: RuleSource,
        allowWhitelistDomains: Boolean
    ) {
        forEachImportableBlockedRule(blockedRules, allowWhitelistDomains) { blockedRule ->
            val ruleKey = buildParsedRuleIdentityKey(blockedRule)
            if (!existingRuleKeys.add(ruleKey)) return@forEachImportableBlockedRule
            val existing = resolveMergeableDomainRule(currentByDomain, blockedRule)
            if (existing == null) {
                val addedRule = buildBlockRuleFromParsedRule(
                    context = context,
                    parsedRule = blockedRule,
                    source = source,
                    useVendorHints = true
                )
                addImportedRule(current, currentByDomain, addedRule)
                return@forEachImportableBlockedRule
            }
            currentByDomain[blockedRule.domain] = mergeRuleTypeScopes(existing, blockedRule)
        }
    }

    private fun resolveMergeableDomainRule(
        currentByDomain: MutableMap<String, BlockRule>,
        blockedRule: ParsedRule
    ): BlockRule? {
        if (blockedRule.regexPattern != null || blockedRule.cosmeticSelector != null) return null
        return currentByDomain[blockedRule.domain]
    }

    private fun applyExceptionScopes(
        currentByDomain: MutableMap<String, BlockRule>,
        exceptionRules: List<ParsedRule>
    ) {
        if (exceptionRules.isEmpty() || currentByDomain.isEmpty()) return
        val exceptionsByDomain = exceptionRules.groupBy { it.domain }
        currentByDomain.keys.toList().forEach { domain ->
            currentByDomain[domain] ?: return@forEach
            forEachDomainSuffix(domain) { suffix ->
                exceptionsByDomain[suffix]?.forEach { exceptionRule ->
                    val before = currentByDomain[domain] ?: return@forEachDomainSuffix
                    updateDomainRuleScope(currentByDomain, domain, before, exceptionRule.dnsTypes, exceptionRule.excludedDnsTypes)
                    currentByDomain[domain] ?: return@forEachDomainSuffix
                }
            }
        }
    }

    private inline fun forEachDomainSuffix(domain: String, action: (String) -> Unit) {
        var suffix = domain
        while (suffix.isNotBlank()) {
            action(suffix)
            val dotIndex = suffix.indexOf('.')
            if (dotIndex < 0 || dotIndex == suffix.lastIndex) break
            suffix = suffix.substring(dotIndex + 1)
        }
    }

    private fun mergeImportedRuleCollections(
        current: List<BlockRule>,
        currentByDomain: MutableMap<String, BlockRule>
    ): List<BlockRule> {
        return buildList {
            addAll(current.filter { it.regexPattern != null || it.cosmeticSelector != null })
            addAll(currentByDomain.values)
        }.distinctBy { buildRuleIdentityKey(it) }
    }

    private fun buildImportedRuleCollections(
        current: List<BlockRule>,
        currentByDomain: MutableMap<String, BlockRule>,
        exceptionRules: List<ParsedRule>
    ): ImportedRuleCollections {
        return ImportedRuleCollections(
            mergedRules = mergeImportedRuleCollections(current, currentByDomain),
            exceptionRules = exceptionRules
        )
    }

    private fun finalizeImportedRules(
        collections: ImportedRuleCollections
    ): List<BlockRule> {
        return applyCosmeticExceptionRules(collections.mergedRules, collections.exceptionRules)
            .sortedBy { it.domain }
    }

    private data class ImportAnalysisState(
        val existingRuleKeys: MutableSet<String>,
        val simulatedDomains: MutableSet<String>,
        val seenBlocked: MutableSet<String> = linkedSetOf(),
        val seenExceptions: MutableSet<String> = linkedSetOf(),
        val unsupportedLines: MutableList<String> = mutableListOf(),
        val invalidLines: MutableList<String> = mutableListOf(),
        val whitelistConflictLines: MutableList<String> = mutableListOf(),
        val vendorCount: LinkedHashMap<String, Int> = linkedMapOf(),
        var blankOrCommentLines: Int = 0,
        var safeBlockedRules: Int = 0,
        var safeExceptionRules: Int = 0,
        var duplicateExistingRules: Int = 0,
        var duplicateWithinFileRules: Int = 0,
        var unsupportedModifierRules: Int = 0,
        var cosmeticRules: Int = 0,
        var regexRules: Int = 0,
        var invalidRules: Int = 0,
        var exceptionRemovalEstimate: Int = 0
    )

    private fun forEachImportableBlockedRule(
        blockedRules: List<ParsedRule>,
        allowWhitelistDomains: Boolean,
        action: (ParsedRule) -> Unit
    ) {
        // 移除白名单过滤：信任用户选择的规则源，与 AdGuard 行为一致
        // 规则源自带的白名单规则（@@||example.com）会自动保护重要域名
        blockedRules.forEach(action)
    }

    private fun addParsedRuleIfAbsent(
        context: Context,
        existingRuleKeys: MutableSet<String>,
        parsedRule: ParsedRule,
        source: RuleSource,
        remoteSourceId: String? = null,
        useVendorHints: Boolean,
        identityRemoteSourceId: String? = null
    ): BlockRule? {
        val ruleKey = buildParsedRuleIdentityKey(parsedRule, identityRemoteSourceId)
        if (!existingRuleKeys.add(ruleKey)) return null
        return buildBlockRuleFromParsedRule(
            context = context,
            parsedRule = parsedRule,
            source = source,
            remoteSourceId = remoteSourceId,
            useVendorHints = useVendorHints
        )
    }

    private fun addImportedRule(
        current: MutableList<BlockRule>,
        currentByDomain: MutableMap<String, BlockRule>,
        addedRule: BlockRule
    ) {
        current += addedRule
        if (addedRule.regexPattern == null && addedRule.cosmeticSelector == null) {
            currentByDomain[addedRule.domain] = addedRule
        }
    }

    private fun updateDomainRuleScope(
        currentByDomain: MutableMap<String, BlockRule>,
        domain: String,
        existing: BlockRule,
        dnsTypes: Set<Int>?,
        excludedDnsTypes: Set<Int>?
    ) {
        subtractDnsTypeScope(existing, dnsTypes, excludedDnsTypes)?.let {
            currentByDomain[domain] = it
        } ?: currentByDomain.remove(domain)
    }

    private fun buildBlockRuleFromParsedRule(
        context: Context,
        parsedRule: ParsedRule,
        source: RuleSource,
        remoteSourceId: String? = null,
        useVendorHints: Boolean
    ): BlockRule {
        val vendor = classifyParsedRuleVendor(context, parsedRule, useVendorHints)
        return BlockRule(
            id = UUID.randomUUID().toString(),
            domain = parsedRule.domain,
            vendor = vendor,
            source = if (parsedRule.isUnsupported) RuleSource.UNSUPPORTED else source,
            dnsTypes = normalizeDnsTypes(parsedRule.dnsTypes),
            excludedDnsTypes = normalizeDnsTypes(parsedRule.excludedDnsTypes),
            thirdParty = parsedRule.thirdParty,
            firstParty = parsedRule.firstParty,
            important = parsedRule.important,
            redirect = parsedRule.redirect,
            domainConstraints = parsedRule.domainConstraints,
            excludedDomainConstraints = parsedRule.excludedDomainConstraints,
            denyallow = parsedRule.denyallow,
            urlblock = parsedRule.urlblock,
            requestTypes = parsedRule.requestTypes,
            appPackages = parsedRule.appPackages,
            destinationPorts = parsedRule.destinationPorts,
            sourcePorts = parsedRule.sourcePorts,
            keywordPattern = parsedRule.keywordPattern,
            pathPattern = parsedRule.pathPattern,
            ipCidr = parsedRule.ipCidr,
            regexPattern = parsedRule.regexPattern,
            cosmeticSelector = parsedRule.cosmeticSelector,
            cosmeticException = parsedRule.isException,
            exceptionRule = parsedRule.isException,
            removeParams = parsedRule.removeParams,
            removeParamRegexes = parsedRule.removeParamRegexes,
            removeRequestHeaders = parsedRule.removeRequestHeaders,
            setRequestHeaders = parsedRule.setRequestHeaders,
            replaceRules = parsedRule.replaceRules,
            cspValue = parsedRule.cspValue,
            redirectResource = parsedRule.redirectResource,
            jsInjectRules = parsedRule.jsInjectRules,
            remoteSourceId = remoteSourceId
        )
    }

    private fun classifyParsedRuleVendor(
        context: Context,
        parsedRule: ParsedRule,
        useVendorHints: Boolean
    ): String {
        val hints = if (useVendorHints) parsedRule.vendorHints.toTypedArray() else emptyArray()
        return classifyVendorSimple(context, parsedRule.domain, *hints) ?: DEFAULT_VENDOR
    }

    private fun incrementVendorCount(vendorCount: MutableMap<String, Int>, vendor: String) {
        vendorCount[vendor] = (vendorCount[vendor] ?: 0) + 1
    }

    private fun countRemovedSimulatedDomains(simulatedDomains: MutableSet<String>, domain: String): Int {
        val removed = simulatedDomains.count { it == domain || it.endsWith(".$domain") }
        simulatedDomains.removeAll { it == domain || it.endsWith(".$domain") }
        return removed
    }

    private data class ParsedRuleAnalysisStepResult(
        val duplicateExistingDelta: Int = 0,
        val duplicateWithinFileDelta: Int = 0,
        val safeBlockedDelta: Int = 0,
        val safeExceptionDelta: Int = 0,
        val exceptionRemovalDelta: Int = 0,
        val vendor: String? = null,
        val shouldContinue: Boolean = true
    )

    private data class InvalidRuleAnalysisResult(
        val unsupportedModifierDelta: Int = 0,
        val invalidRuleDelta: Int = 0,
        val unsupportedLine: String? = null,
        val invalidLine: String? = null
    )

    private data class ParsedRulePreAnalysisResult(
        val regexDelta: Int = 0,
        val cosmeticDelta: Int = 0,
        val whitelistConflictLine: String? = null
    )

    private fun applyInvalidRuleAnalysis(
        result: InvalidRuleAnalysisResult,
        unsupportedLines: MutableList<String>,
        invalidLines: MutableList<String>
    ): Pair<Int, Int> {
        result.unsupportedLine?.let { unsupportedLines += it }
        result.invalidLine?.let { invalidLines += it }
        return result.unsupportedModifierDelta to result.invalidRuleDelta
    }

    private fun applyParsedRulePreAnalysis(
        result: ParsedRulePreAnalysisResult,
        whitelistConflictLines: MutableList<String>
    ): Pair<Int, Int> {
        result.whitelistConflictLine?.let { whitelistConflictLines += it }
        return result.regexDelta to result.cosmeticDelta
    }

    private fun analyzeParsedRuleStep(
        context: Context,
        parsedRule: ParsedRule,
        ruleKey: String,
        existingRuleKeys: Set<String>,
        seenBlocked: MutableSet<String>,
        seenExceptions: MutableSet<String>,
        simulatedDomains: MutableSet<String>
    ): ParsedRuleAnalysisStepResult {
        if (parsedRule.isException) {
            if (!seenExceptions.add(ruleKey)) {
                return ParsedRuleAnalysisStepResult(
                    duplicateWithinFileDelta = 1,
                    shouldContinue = false
                )
            }
            return ParsedRuleAnalysisStepResult(
                safeExceptionDelta = 1,
                exceptionRemovalDelta = countRemovedSimulatedDomains(simulatedDomains, parsedRule.domain),
                shouldContinue = false
            )
        }
        if (parsedRule.isBadfilter) {
            val removed = simulatedDomains.remove(parsedRule.domain)
            return ParsedRuleAnalysisStepResult(
                safeExceptionDelta = 1,
                exceptionRemovalDelta = if (removed) 1 else 0,
                shouldContinue = false
            )
        }
        if (!seenBlocked.add(ruleKey)) {
            return ParsedRuleAnalysisStepResult(
                duplicateWithinFileDelta = 1,
                shouldContinue = false
            )
        }
        if (existingRuleKeys.contains(ruleKey)) {
            return ParsedRuleAnalysisStepResult(
                duplicateExistingDelta = 1,
                shouldContinue = false
            )
        }
        simulatedDomains += parsedRule.domain
        return ParsedRuleAnalysisStepResult(
            safeBlockedDelta = 1,
            vendor = classifyParsedRuleVendor(context, parsedRule, useVendorHints = true)
        )
    }

    private fun analyzeInvalidImportRule(line: String): InvalidRuleAnalysisResult {
        val working = line.removePrefix("@@")
        val candidate = extractDomainCandidate(working)
        if (candidate == null) {
            return if (looksLikeComplexRulePattern(working)) {
                InvalidRuleAnalysisResult(
                    unsupportedModifierDelta = 1,
                    unsupportedLine = "$line    [complex-pattern]"
                )
            } else {
                InvalidRuleAnalysisResult(
                    invalidRuleDelta = 1,
                    invalidLine = line
                )
            }
        }
        val modifierInfo = parseModifierInfo(candidate.second)
        if (modifierInfo.unsupportedModifiers.isNotEmpty()) {
            return InvalidRuleAnalysisResult(
                unsupportedModifierDelta = 1,
                unsupportedLine = "$line    [${modifierInfo.unsupportedModifiers.joinToString(", ")}]"
            )
        }
        if (modifierInfo.invalid) {
            return InvalidRuleAnalysisResult(
                unsupportedModifierDelta = 1,
                unsupportedLine = "$line    [invalid-modifier]"
            )
        }
        return InvalidRuleAnalysisResult(
            invalidRuleDelta = 1,
            invalidLine = line
        )
    }

    private fun analyzeParsedRulePreStep(parsedRule: ParsedRule, line: String): ParsedRulePreAnalysisResult {
        return ParsedRulePreAnalysisResult(
            regexDelta = if (parsedRule.regexPattern != null) 1 else 0,
            cosmeticDelta = if (parsedRule.cosmeticSelector != null) 1 else 0,
            whitelistConflictLine = if (!parsedRule.isException && !parsedRule.isBadfilter && isWhitelistedDomain(parsedRule.domain)) line else null
        )
    }

    private fun buildParsedRuleIdentityKey(parsedRule: ParsedRule, remoteSourceId: String? = null): String {
        return buildParsedRuleKey(
            domain = parsedRule.domain,
            dnsTypes = parsedRule.dnsTypes,
            excludedDnsTypes = parsedRule.excludedDnsTypes,
            badfilter = parsedRule.isBadfilter,
            firstParty = parsedRule.firstParty,
            important = parsedRule.important,
            pathPattern = parsedRule.pathPattern,
            ipCidr = parsedRule.ipCidr,
            regexPattern = parsedRule.regexPattern,
            cosmeticSelector = parsedRule.cosmeticSelector,
            removeParams = parsedRule.removeParams,
            removeParamRegexes = parsedRule.removeParamRegexes,
            removeRequestHeaders = parsedRule.removeRequestHeaders,
            setRequestHeaders = parsedRule.setRequestHeaders,
            replaceRules = parsedRule.replaceRules,
            cspValue = parsedRule.cspValue,
            jsInjectRules = parsedRule.jsInjectRules,
            keywordPattern = parsedRule.keywordPattern,
            domainConstraints = parsedRule.domainConstraints,
            excludedDomainConstraints = parsedRule.excludedDomainConstraints,
            appPackages = parsedRule.appPackages,
            requestTypes = parsedRule.requestTypes,
            destinationPorts = parsedRule.destinationPorts,
            sourcePorts = parsedRule.sourcePorts,
            denyallow = parsedRule.denyallow,
            remoteSourceId = remoteSourceId,
            cosmeticException = parsedRule.isException
        )
    }

    fun removeByIds(context: Context, ids: Set<String>) {
        save(context, getRules(context).filterNot { ids.contains(it.id) })
    }

    fun removeRulesByIds(context: Context, ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val normalizedIds = ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalizedIds.isEmpty()) return 0
        val current = getRules(context)
        val removal = removeRulesInternal(current, normalizedIds, emptySet())
        if (removal.removedCount > 0) save(context, removal.remaining)
        return removal.removedCount
    }

    fun removeRules(context: Context, rules: Collection<BlockRule>): Int {
        if (rules.isEmpty()) return 0
        val normalizedIds = rules.asSequence()
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val identityKeys = rules.asSequence()
            .map(::buildRuleIdentityKey)
            .toSet()
        if (normalizedIds.isEmpty() && identityKeys.isEmpty()) return 0
        val current = getRules(context)
        val removal = removeRulesInternal(current, normalizedIds, identityKeys)
        if (removal.removedCount > 0) save(context, removal.remaining)
        return removal.removedCount
    }

    fun removeRules(context: Context, ids: Set<String>, rules: Collection<BlockRule>): Int {
        val normalizedIds = ids.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val identityKeys = rules.asSequence()
            .map(::buildRuleIdentityKey)
            .toSet()
        if (normalizedIds.isEmpty() && identityKeys.isEmpty()) return 0
        val current = getRules(context)
        val removal = removeRulesInternal(current, normalizedIds, identityKeys)
        if (removal.removedCount > 0) save(context, removal.remaining)
        return removal.removedCount
    }

    fun removeAllRules(context: Context): Int {
        val current = getRules(context)
        if (current.isEmpty()) return 0
        save(context, emptyList())
        return current.size
    }

    fun isBlocked(
        context: Context,
        domain: String,
        qType: Int? = null,
        appName: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val ruleMap = getRuleMap(context)
        val matchingRules = buildDomainCandidates(normalized)
            .flatMap { candidate -> ruleMap[candidate].orEmpty().asSequence() }
            .filter {
                it.source != RuleSource.UNSUPPORTED &&
                ruleMatches(it, qType, appName) &&
                    matchesPortScope(it.destinationPorts, destinationPort) &&
                    matchesPortScope(it.sourcePorts, sourcePort)
            }
            .toList()
        val lowerDomain = normalized.lowercase()
        val hasImportantBlock = matchingRules.any(::isImportantBlockingRule) ||
            getRegexRules(context).any { rule ->
                isImportantBlockingRule(rule) && matchesRegexRule(rule, normalized)
            } ||
            getKeywordRules(context).any { rule ->
                if (!isImportantBlockingRule(rule)) return@any false
                val keyword = rule.keywordPattern?.lowercase() ?: return@any false
                lowerDomain.contains(keyword)
            }
        if (hasImportantBlock) return true
        val hasUserOwnedBlock = matchingRules.any(::isUserOwnedBlockingRule) ||
            getRegexRules(context).any { rule ->
                isUserOwnedBlockingRule(rule) && matchesRegexRule(rule, normalized)
            } ||
            getKeywordRules(context).any { rule ->
                if (!isUserOwnedBlockingRule(rule)) return@any false
                val keyword = rule.keywordPattern?.lowercase() ?: return@any false
                lowerDomain.contains(keyword)
            }
        if (isCoreTrafficProtectedDomain(normalized) && !hasUserOwnedBlock) return false
        if (!hasUserOwnedBlock && matchingRules.any { it.exceptionRule }) return false
        if (hasUserOwnedBlock) return true
        val matched = matchingRules.any { !it.exceptionRule } ||
            getRegexRules(context).any { rule ->
                if (rule.exceptionRule) return@any false
                matchesRegexRule(rule, normalized)
            } ||
            getKeywordRules(context).any { rule ->
                if (rule.exceptionRule) return@any false
                val keyword = rule.keywordPattern?.lowercase() ?: return@any false
                lowerDomain.contains(keyword)
            }
        if (matched) return true
        if (isWhitelistedDomain(normalized)) return false
        return false
    }

    // 性能优化：快速拦截检查（跳过关键词规则，仅匹配精确规则和正则规则）
    fun isBlockedFast(context: Context, domain: String, qType: Int? = null): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val ruleMap = getRuleMap(context)
        val matchingRules = buildDomainCandidates(normalized)
            .flatMap { candidate -> ruleMap[candidate].orEmpty().asSequence() }
            .filter { it.source != RuleSource.UNSUPPORTED && ruleMatches(it, qType, null) && it.destinationPorts.isEmpty() && it.sourcePorts.isEmpty() }
            .toList()
        val hasUserOwnedBlock = matchingRules.any(::isUserOwnedBlockingRule) ||
            getRegexRules(context).any { rule ->
                isUserOwnedBlockingRule(rule) && matchesRegexRule(rule, normalized)
            }
        val hasImportantBlock = matchingRules.any(::isImportantBlockingRule) ||
            getRegexRules(context).any { rule ->
                isImportantBlockingRule(rule) && matchesRegexRule(rule, normalized)
            }
        if (hasImportantBlock) return true
        if (isCoreTrafficProtectedDomain(normalized) && !hasUserOwnedBlock) return false
        if (!hasUserOwnedBlock && matchingRules.any { it.exceptionRule }) return false
        if (hasUserOwnedBlock) return true
        val matched = matchingRules.any { !it.exceptionRule } ||
            getRegexRules(context).any { rule ->
                if (rule.exceptionRule) return@any false
                matchesRegexRule(rule, normalized)
            }
        if (matched) return true
        if (isWhitelistedDomain(normalized)) return false
        return false
    }

    fun isUrlBlocked(
        context: Context,
        host: String,
        path: String,
        appName: String? = null,
        requestDomain: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null,
        requestType: String? = null
    ): Boolean {
        val normalizedHost = sanitizeDomain(host) ?: return false
        val ruleMap = getRuleMap(context)
        val fullUrl = "$host$path".lowercase()
        val effectiveRequestType = requestType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: inferRequestTypeFromPath(path)
        val matchingRules = buildDomainCandidates(normalizedHost)
            .flatMap { candidate -> ruleMap[candidate].orEmpty().asSequence() }
            .filter { rule ->
                if (rule.source == RuleSource.UNSUPPORTED) return@filter false
                if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@filter false
                if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@filter false
                if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@filter false
                if (rule.keywordPattern != null) {
                    return@filter fullUrl.contains(rule.keywordPattern)
                }
                if (!rule.pathPattern.isNullOrBlank() && path.isNotBlank()) {
                    return@filter pathMatchesPattern(path, rule.pathPattern)
                }
                if (rule.urlblock && path.isNotBlank()) {
                    return@filter looksLikeSuspiciousPath(path)
                }
                rule.appPackages.isEmpty() || matchesAppPackage(rule.appPackages, appName)
            }
            .toList()
        val hasUserOwnedBlock = matchingRules.any(::isUserOwnedBlockingRule) || getRegexRules(context).any { rule ->
            if (!isUserOwnedBlockingRule(rule)) return@any false
            if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@any false
            if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@any false
            if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@any false
            matchesRegexRule(rule, fullUrl)
        }
        val hasImportantBlock = matchingRules.any(::isImportantBlockingRule) || getRegexRules(context).any { rule ->
            if (!isImportantBlockingRule(rule)) return@any false
            if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@any false
            if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@any false
            if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@any false
            matchesRegexRule(rule, fullUrl)
        }
        if (hasImportantBlock) return true
        if (isCoreTrafficProtectedDomain(normalizedHost) && !hasUserOwnedBlock) return false
        val hasExceptionMatch = matchingRules.any { it.exceptionRule } || getRegexRules(context).any { rule ->
            if (rule.source == RuleSource.UNSUPPORTED) return@any false
            if (!rule.exceptionRule) return@any false
            if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@any false
            if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@any false
            if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@any false
            matchesRegexRule(rule, fullUrl)
        }
        if (!hasUserOwnedBlock && hasExceptionMatch) return false
        if (hasUserOwnedBlock) return true
        val matched = matchingRules.any { !it.exceptionRule } || getRegexRules(context).any { rule ->
            if (rule.source == RuleSource.UNSUPPORTED) return@any false
            if (rule.exceptionRule) return@any false
            if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@any false
            if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@any false
            if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@any false
            matchesRegexRule(rule, fullUrl)
        }
        if (matched) return true
        if (isWhitelistedDomain(normalizedHost)) return false
        return false
    }

    private fun inferRequestTypeFromPath(path: String): String? {
        val cleanPath = path.substringBefore('?').substringBefore('#').lowercase()
        return when {
            cleanPath.endsWith(".js") || cleanPath.endsWith(".mjs") -> "script"
            cleanPath.endsWith(".css") -> "stylesheet"
            cleanPath.endsWith(".png") || cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") ||
                cleanPath.endsWith(".gif") || cleanPath.endsWith(".webp") || cleanPath.endsWith(".avif") ||
                cleanPath.endsWith(".svg") || cleanPath.endsWith(".ico") -> "image"
            cleanPath.endsWith(".woff") || cleanPath.endsWith(".woff2") || cleanPath.endsWith(".ttf") ||
                cleanPath.endsWith(".otf") || cleanPath.endsWith(".eot") -> "font"
            cleanPath.endsWith(".mp4") || cleanPath.endsWith(".m4v") || cleanPath.endsWith(".webm") ||
                cleanPath.endsWith(".mp3") || cleanPath.endsWith(".m3u8") || cleanPath.endsWith(".ts") -> "media"
            else -> null
        }
    }

    fun findMatchingIpRule(
        context: Context,
        ip: String,
        appName: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): BlockRule? {
        val normalizedIp = sanitizeIpLiteral(ip) ?: return null
        val address = runCatching { InetAddress.getByName(normalizedIp) }.getOrNull() ?: return null
        return (cachedIpCidrRules ?: getRules(context).filter { !it.ipCidr.isNullOrBlank() })
            .firstOrNull { rule ->
                rule.source != RuleSource.UNSUPPORTED &&
                    !rule.exceptionRule &&
                    matchesAppPackage(rule.appPackages, appName) &&
                    matchesPortScope(rule.destinationPorts, destinationPort) &&
                    matchesPortScope(rule.sourcePorts, sourcePort) &&
                    matchesIpCidr(address, rule.ipCidr)
            }
    }

    fun findMatchingPortOnlyRule(
        context: Context,
        appName: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): BlockRule? {
        if (destinationPort == null && sourcePort == null) return null
        return (cachedPortOnlyRules ?: getRules(context).filter { it.domain == "*" && it.ipCidr.isNullOrBlank() })
            .firstOrNull { rule ->
                rule.source != RuleSource.UNSUPPORTED &&
                    !rule.exceptionRule &&
                    matchesAppPackage(rule.appPackages, appName) &&
                    matchesPortScope(rule.destinationPorts, destinationPort) &&
                    matchesPortScope(rule.sourcePorts, sourcePort)
            }
    }

    fun hasIpRules(context: Context): Boolean {
        cachedIpCidrRules?.let { return it.isNotEmpty() }
        getRules(context)
        return cachedIpCidrRules?.isNotEmpty() == true
    }

    fun hasPortOnlyRules(context: Context): Boolean {
        cachedPortOnlyRules?.let { return it.isNotEmpty() }
        getRules(context)
        return cachedPortOnlyRules?.isNotEmpty() == true
    }
    
    fun isWhitelistedDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lowerDomain = normalized.lowercase()
        cachedWhitelistHits[lowerDomain]?.let { return it }
        val result = checkDomainWhitelist(lowerDomain)
        cachedWhitelistHits[lowerDomain] = result
        return result
    }

    fun isBypassProtectionDomain(domain: String): Boolean {
        return RuleVendorSupport.isBypassProtectionDomain(
            domain = domain,
            sanitizeDomain = ::sanitizeDomain,
            buildDomainCandidates = ::buildDomainCandidates,
            bypassProtectionDomains = bypassProtectionDomains
        )
    }

    fun isGameCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return RuleProtectionSupport.matchesExactOrSubdomain(normalized, gameCoreDomains)
    }

    fun isSocialCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return RuleProtectionSupport.matchesExactOrSubdomain(normalized, socialCoreDomains)
    }

    fun isMediaCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return RuleProtectionSupport.matchesExactOrSubdomain(normalized, mediaCoreDomains)
    }

    fun shouldProtectMediaTraffic(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        if (!isMediaCoreDomain(normalized)) return false
        if (looksLikeAdDomain(normalized)) return false
        return true
    }

    fun isBusinessCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return RuleProtectionSupport.matchesExactOrSubdomain(normalized, businessCoreDomains)
    }

    fun shouldProtectBusinessTraffic(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        if (!isBusinessCoreDomain(normalized)) return false
        if (looksLikeAdDomain(normalized)) return false
        return true
    }

    private fun checkDomainWhitelist(lowerDomain: String): Boolean {
        if (whitelistDomains.contains(lowerDomain)) return true
        if (whitelistSuffixRoots.any { lowerDomain.endsWith(it) }) {
            // Large protected roots still need to let only high-confidence ad/tracking subdomains fall through.
            if (looksLikeWhitelistedRootAdSubdomain(lowerDomain)) {
                return false
            }
            return true
        }
        if (lowerDomain.contains("umeng.com") || lowerDomain.contains("umengcloud.com")) {
            if (umengWhitelistSubDomains.contains(lowerDomain) || umengWhitelistSubDomains.any { lowerDomain.endsWith(".$it") }) {
                return true
            }
        }
        if (qqWhitelistSubDomains.contains(lowerDomain) || qqWhitelistSubDomains.any { lowerDomain.endsWith(".$it") }) {
            return true
        }
        if (neteaseWhitelistSubDomains.contains(lowerDomain) || neteaseWhitelistSubDomains.any { lowerDomain.endsWith(".$it") }) {
            return true
        }
        return false
    }

    val criticalStartupDomains: Set<String>
        get() = setOf(
            "clientservices.googleapis.com",
            "update.googleapis.com",
            "android.clients.google.com",
            "play.googleapis.com",
            "firebaseinstallations.googleapis.com",
            "app-measurement.com",
            "firebase-analytics.com",
            "android.googleapis.com",
            "ssl.gstatic.com",
            "fonts.googleapis.com",
            "fonts.gstatic.com"
        )

    private val whitelistSuffixRoots by lazy {
        whitelistDomains.map { ".$it" }.toSet()
    }

    fun findMatchingRule(
        context: Context,
        domain: String,
        qType: Int? = null,
        appName: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): BlockRule? {
        val normalized = sanitizeDomain(domain) ?: return null
        val ruleMap = getRuleMap(context)
        val domainMatch = buildDomainCandidates(normalized)
            .flatMap { candidate -> ruleMap[candidate].orEmpty().asSequence() }
            .filter {
                it.source != RuleSource.UNSUPPORTED &&
                    ruleMatches(it, qType, appName) &&
                    !it.exceptionRule &&
                    matchesPortScope(it.destinationPorts, destinationPort) &&
                    matchesPortScope(it.sourcePorts, sourcePort)
            }
            .firstOrNull()
        val regexMatch = getRegexRules(context).firstOrNull { it.source != RuleSource.UNSUPPORTED && !it.exceptionRule && matchesRegexRule(it, normalized) }
        val match = domainMatch ?: regexMatch
        if (isCoreTrafficProtectedDomain(normalized) && match?.let(::isUserOwnedBlockingRule) != true) return null
        return match
    }

    internal fun isUserOwnedRule(rule: BlockRule): Boolean {
        return rule.source == RuleSource.MANUAL ||
            (rule.source == RuleSource.IMPORTED && rule.remoteSourceId.isNullOrBlank())
    }

    private fun isUserOwnedBlockingRule(rule: BlockRule): Boolean {
        return isUserOwnedRule(rule) && !rule.exceptionRule && rule.source != RuleSource.UNSUPPORTED
    }

    private fun isImportantBlockingRule(rule: BlockRule): Boolean {
        return rule.important && !rule.exceptionRule && rule.source != RuleSource.UNSUPPORTED
    }

    private fun isCoreTrafficProtectedDomain(domain: String): Boolean {
        return isWhitelistedDomain(domain) ||
            isSensitiveAuthDomain(domain) ||
            isGameCoreDomain(domain) ||
            isSocialCoreDomain(domain) ||
            shouldProtectMediaTraffic(domain) ||
            shouldProtectBusinessTraffic(domain) ||
            isNovelContentDomain(domain) ||
            isProtectedNovelAppDomain(domain)
    }

    fun getRequestRewriteDirectives(
        context: Context,
        host: String,
        path: String,
        appName: String? = null,
        requestDomain: String? = null,
        requestType: String? = null
    ): RequestRewriteDirectives {
        val normalizedHost = sanitizeDomain(host) ?: return RequestRewriteDirectives()
        val effectiveRequestType = requestType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: inferRequestTypeFromPath(path)
        val matchedRules = buildDomainCandidates(normalizedHost)
            .flatMap { candidate -> getRuleMap(context)[candidate].orEmpty().asSequence() }
            .filter { it.source != RuleSource.UNSUPPORTED && ruleMatches(it, null, appName, normalizedHost, requestDomain, effectiveRequestType) }
        val matchedRegexRules = getRegexRules(context).filter {
            it.source != RuleSource.UNSUPPORTED &&
                ruleMatches(it, null, appName, normalizedHost, requestDomain, effectiveRequestType) &&
                (matchesRegexRule(it, "$normalizedHost$path") || matchesRegexRule(it, normalizedHost))
        }
        val allRules = (matchedRules + matchedRegexRules).distinctBy { it.id }.toList()
        val importantActionableRules = allRules.filter(::isImportantBlockingRule)
        if (allRules.any { it.exceptionRule } && importantActionableRules.isEmpty()) {
            return RequestRewriteDirectives(cosmeticSelectors = getCosmeticSelectors(
                context = context,
                host = normalizedHost,
                path = path,
                appName = appName,
                requestDomain = requestDomain
            ))
        }
        val actionableRules = importantActionableRules.ifEmpty { allRules.filterNot { it.exceptionRule } }
        val removeParams = actionableRules.flatMap { it.removeParams }.toSet()
        val removeParamRegexes = actionableRules.flatMap { it.removeParamRegexes }.toSet()
        val removeRequestHeaders = actionableRules.flatMap { it.removeRequestHeaders }.toSet()
        val setRequestHeaders = actionableRules.flatMap { it.setRequestHeaders }.toSet()
        val replaceRules = actionableRules.flatMap { it.replaceRules }.toSet()
        val cspValue = actionableRules.mapNotNull { it.cspValue }.firstOrNull()
        val redirectResource = actionableRules.mapNotNull { it.redirectResource }.firstOrNull()
        val jsInjectRules = actionableRules.flatMap { it.jsInjectRules }.toSet()
        val cosmeticSelectors = getCosmeticSelectors(
            context = context,
            host = normalizedHost,
            path = path,
            appName = appName,
            requestDomain = requestDomain
        )
        return RequestRewriteDirectives(
            removeParams = removeParams,
            removeParamRegexes = removeParamRegexes,
            removeRequestHeaders = removeRequestHeaders,
            setRequestHeaders = setRequestHeaders,
            replaceRules = replaceRules,
            cspValue = cspValue,
            redirectResource = redirectResource,
            jsInjectRules = jsInjectRules,
            cosmeticSelectors = cosmeticSelectors
        )
    }

    fun hasAdvancedUrlRule(
        context: Context,
        host: String,
        path: String,
        appName: String? = null,
        requestDomain: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): Boolean {
        val normalizedHost = sanitizeDomain(host) ?: return false
        val normalizedPath = path.lowercase()
        val fullUrl = "$normalizedHost$normalizedPath"
        val matchedHostRules = buildDomainCandidates(normalizedHost)
            .flatMap { candidate -> getRuleMap(context)[candidate].orEmpty().asSequence() }
            .filter {
                it.source != RuleSource.UNSUPPORTED &&
                    ruleMatches(it, null, appName, normalizedHost, requestDomain) &&
                    !it.exceptionRule &&
                    matchesPortScope(it.destinationPorts, destinationPort) &&
                    matchesPortScope(it.sourcePorts, sourcePort)
            }
            .any { rule ->
                !rule.pathPattern.isNullOrBlank() ||
                    rule.urlblock ||
                    rule.removeParams.isNotEmpty() ||
                    rule.removeParamRegexes.isNotEmpty() ||
                    rule.removeRequestHeaders.isNotEmpty() ||
                    rule.setRequestHeaders.isNotEmpty() ||
                    rule.replaceRules.isNotEmpty() ||
                    !rule.cspValue.isNullOrBlank() ||
                    rule.jsInjectRules.isNotEmpty() ||
                    !rule.cosmeticSelector.isNullOrBlank() ||
                    (!rule.keywordPattern.isNullOrBlank() && fullUrl.contains(rule.keywordPattern))
        }
        if (matchedHostRules) return true
        return getRegexRules(context).any { it.source != RuleSource.UNSUPPORTED && !it.exceptionRule && matchesRegexRule(it, fullUrl) }
    }

    fun getCosmeticSelectors(
        context: Context,
        host: String,
        path: String? = null,
        appName: String? = null,
        requestDomain: String? = null
    ): List<String> {
        val normalizedHost = sanitizeDomain(host) ?: return emptyList()
        val normalizedPath = path?.lowercase().orEmpty()
        val fullUrl = "$normalizedHost$normalizedPath"
        val matchedRules = getCosmeticRules(context)
            .asSequence()
            .filter { rule ->
                (rule.domain == COSMETIC_RULE_DOMAIN || normalizedHost == rule.domain || normalizedHost.endsWith(".${rule.domain}")) &&
                    ruleMatches(rule, null, appName, normalizedHost, requestDomain)
            }
            .filter { rule ->
                when {
                    !rule.pathPattern.isNullOrBlank() -> pathMatchesPattern(normalizedPath, rule.pathPattern)
                    !rule.keywordPattern.isNullOrBlank() -> fullUrl.contains(rule.keywordPattern)
                    !rule.regexPattern.isNullOrBlank() -> matchesRegexRule(rule, fullUrl)
                    else -> true
                }
            }
            .toList()
        val excludedSelectors = matchedRules
            .asSequence()
            .filter { it.cosmeticException || it.source == RuleSource.UNSUPPORTED }
            .mapNotNull { it.cosmeticSelector }
            .toSet()
        return matchedRules
            .asSequence()
            .filter { it.source != RuleSource.UNSUPPORTED }
            .filterNot { it.cosmeticException }
            .mapNotNull { it.cosmeticSelector }
            .filterNot(excludedSelectors::contains)
            .distinct()
            .toList()
    }

    fun shouldAggressivelyBlockForNovelApp(context: Context, domain: String, appName: String?, vendor: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (!isNovelAppHint(appName)) return false
        if (isWhitelistedDomain(normalized)) return false
        if (isBypassProtectionDomain(normalized)) return true
        if (hasMatchingRule(context, normalized)) return false
        // 小说内容 API 域名不拦截
        if (novelContentApiDomains.contains(normalized) || novelContentApiDomains.any { normalized.endsWith(".$it") }) return false
        // 游戏核心服务不拦截（确保游戏正常运行）
        if (isGameCoreDomain(normalized)) return false
        // 社交 APP 核心服务不拦截（确保微信 QQ 正常）
        if (isSocialCoreDomain(normalized)) return false
        val normalizedVendor = normalizeVendorName(vendor)
        val lower = normalized.lowercase()
        // 增强广告域名信号检测 - 扩大关键词范围
        val hasAggressiveSignal = lower.contains("ad") || lower.contains("ads") || lower.contains("banner") || lower.contains("splash") || 
            lower.contains("promo") || lower.contains("tracking") || lower.contains("log") || lower.contains("stat") || 
            lower.contains("analytics") || lower.contains("monitor") || lower.contains("track") || lower.contains("count") ||
            lower.contains("report") || lower.contains("feed") || lower.contains("stream") || lower.contains("api") ||
            lower.contains("cdn") || lower.contains("dsp") || lower.contains("adx") || lower.contains("ssp")
        // 增强小说 APP 广告识别 - 包含广告域名特征立即拦截
        if (hasAggressiveSignal && looksLikeAdDomain(normalized)) return true
        // 广告供应商域名一律拦截（针对小说 APP）
        if (novelAggressiveVendorNames.contains(normalizedVendor)) return true
        // 包含 SDK、service、platform 等字样也拦截
        val hasSdkSignal = lower.contains("sdk") || lower.contains("service") || lower.contains("platform") || 
            lower.contains("manager") || lower.contains("network") || lower.contains("server")
        if (hasSdkSignal && hasAggressiveSignal) return true
        if (isProtectedNovelAppDomain(normalized)) return false
        val matchesExactAggressiveDomain = buildDomainCandidates(normalized).any(novelAggressiveExactDomains::contains)
        if (matchesExactAggressiveDomain) return true
        // 增强广告域名识别
        return looksLikeAdDomain(normalized) && hasAggressiveNovelAdSignal(normalized)
    }

    fun shouldAggressivelyBlockNovelProtectedUrl(context: Context, host: String, path: String?, appName: String?): Boolean {
        val normalizedHost = sanitizeDomain(host) ?: return false
        if (!isNovelAppHint(appName)) return false
        if (isWhitelistedDomain(normalizedHost)) return false
        if (!isProtectedNovelAppDomain(normalizedHost)) return false
        if (isProtectedByteDanceInfraDomain(normalizedHost)) return false
        val lowerPath = path?.lowercase().orEmpty()
        if (lowerPath.isBlank()) return false
        if (isUrlBlocked(context, normalizedHost, lowerPath, appName)) return true
        return fanqieProtectedAdPathKeywords.any { lowerPath.contains(it) } || looksLikeSuspiciousPath(lowerPath)
    }

    fun isSensitiveAuthDomain(domain: String): Boolean {
        return RuleProtectionSupport.isSensitiveAuthDomain(
            domain = domain,
            sanitizeDomain = ::sanitizeDomain,
            isWhitelistedDomain = ::isWhitelistedDomain,
            sensitiveAuthKeywords = sensitiveAuthKeywords,
            keywordMatches = ::keywordMatches
        )
    }

    fun deduplicateRules(context: Context): Int {
        val rules = getRules(context)
        val seen = mutableSetOf<String>()
        val toRemove = mutableSetOf<String>()
        rules.forEach { rule ->
            val key = "${rule.domain}|${rule.vendor}|${rule.source}|${rule.keywordPattern}|${rule.regexPattern}|${rule.cosmeticSelector}|${rule.cosmeticException}"
            if (!seen.add(key)) {
                toRemove += rule.id
            }
        }
        if (toRemove.isNotEmpty()) {
            removeByIds(context, toRemove)
            clearCaches()
        }
        return toRemove.size
    }

    fun filterNonAds(context: Context): List<BlockRule> {
        val regular = getRules(context).filter { rule ->
            val effectiveVendor = if (rule.vendor == DEFAULT_VENDOR) classifyVendor(context, rule.domain) else normalizeVendorName(rule.vendor)
            effectiveVendor == DEFAULT_VENDOR && !looksLikeAdDomain(rule.domain) && !looksLikeBypassProtectionDomain(rule.domain)
        }
        return regular
    }

    fun getImpactNormalNetworkCandidates(context: Context): List<RemoteRuleRemovalCandidate> {
        return getRules(context)
            .asSequence()
            .mapNotNull { rule ->
                runCatching { explainImpactNormalNetworkCandidate(context, rule) }
                    .onFailure {
                        LogRepository.append(
                            context,
                            "Skip impact-normal-network candidate domain=${rule.domain} reason=${it.message ?: it.javaClass.simpleName}"
                        )
                    }
                    .getOrNull()
            }
            .distinctBy { buildRuleIdentityKey(it.rule) }
            .sortedBy { it.rule.domain }
            .toList()
    }

    fun getRuleInventory(context: Context): RuleInventory {
        cachedRuleInventory?.let { return it }
        val rules = getRules(context)
        val inventory = RuleInventory(
            importedCount = rules.count { it.source == RuleSource.IMPORTED },
            manualCount = rules.count { it.source == RuleSource.MANUAL },
            regexCount = rules.count { !it.regexPattern.isNullOrBlank() },
            cosmeticCount = rules.count { !it.cosmeticSelector.isNullOrBlank() },
            keywordCount = rules.count { !it.keywordPattern.isNullOrBlank() }
        )
        cachedRuleInventory = inventory
        return inventory
    }

    fun classifyVendor(context: Context, domain: String): String {
        val normalized = sanitizeDomain(domain) ?: return DEFAULT_VENDOR
        cachedVendorMap[normalized]?.let { return it }
        if (looksLikeBypassProtectionDomain(normalized)) return BYPASS_PROTECTION_VENDOR.also { cachedVendorMap[normalized] = it }
        readCustomVendorMap(context)[normalized]?.let { return normalizeVendorName(it).also { v -> cachedVendorMap[normalized] = v } }
        findMatchingRule(context, normalized)?.vendor?.let { return normalizeVendorName(it).also { v -> cachedVendorMap[normalized] = v } }
        val result = RuleVendorSupport.classifyVendorByDomainSignals(
            normalizedDomain = normalized,
            defaultVendor = DEFAULT_VENDOR,
            genericAdVendor = GENERIC_AD_VENDOR,
            normalizeVendorName = ::normalizeVendorName,
            vendorPatterns = vendorPatterns,
            vendorKeywords = vendorKeywords,
            vendorSdkIdentifiers = vendorSdkIdentifiers,
            keywordMatches = ::keywordMatches,
            identifierMatches = ::identifierMatches,
            looksLikeAdDomain = ::looksLikeAdDomain
        )
        cachedVendorMap[normalized] = result
        return result
    }

    fun classifyVendorSimple(context: Context, domain: String, vararg hints: String?): String? {
        val normalized = sanitizeDomain(domain) ?: return null
        cachedVendorMap[normalized]?.let { return it }
        val byDomain = RuleVendorSupport.classifyVendorByDomainSignals(
            normalizedDomain = normalized,
            defaultVendor = DEFAULT_VENDOR,
            genericAdVendor = GENERIC_AD_VENDOR,
            normalizeVendorName = ::normalizeVendorName,
            vendorPatterns = vendorPatterns,
            vendorKeywords = vendorKeywords,
            vendorSdkIdentifiers = emptyMap(),
            keywordMatches = ::keywordMatches,
            identifierMatches = ::identifierMatches,
            looksLikeAdDomain = ::looksLikeAdDomain
        )
        if (byDomain != DEFAULT_VENDOR || looksLikeAdDomain(normalized)) {
            cachedVendorMap[normalized] = byDomain
            return byDomain
        }
        val hintMatches = RuleVendorSupport.classifyVendorByHints(
            hints = hints,
            normalizeVendorName = ::normalizeVendorName,
            vendorSdkIdentifiers = vendorSdkIdentifiers,
            identifierMatches = ::identifierMatches
        )
        hintMatches?.let { return it.also { v -> cachedVendorMap[normalized] = v } }
        val result = byDomain
        cachedVendorMap[normalized] = result
        return result
    }

    fun classifyVendorFromHints(context: Context, domain: String, vararg hints: String?): String {
        val fromDomain = classifyVendor(context, domain)
        if (fromDomain != DEFAULT_VENDOR && fromDomain != GENERIC_AD_VENDOR) return fromDomain
        return RuleVendorSupport.classifyVendorByHints(
            hints = hints,
            normalizeVendorName = ::normalizeVendorName,
            vendorSdkIdentifiers = vendorSdkIdentifiers,
            identifierMatches = ::identifierMatches
        ) ?: fromDomain
    }

    fun reportUnknownVendorIfNeeded(context: Context, vendor: String, domain: String, appName: String? = null) {
        reportUnknownVendorIfNeeded(
            context = context,
            vendor = vendor,
            domain = domain,
            appName = appName,
            signal = SuspiciousSignal.DNS_QUERY,
            confidenceBoost = 0,
            matchedPathHint = null,
            refererDomain = null
        )
    }

    fun reportUnknownVendorIfNeeded(
        context: Context,
        vendor: String,
        domain: String,
        appName: String? = null,
        signal: SuspiciousSignal,
        confidenceBoost: Int = 0,
        matchedPathHint: String? = null,
        refererDomain: String? = null
    ) {
        val normalizedVendor = normalizeVendorName(vendor)
        val normalized = normalizeSuspiciousSampleDomain(domain) ?: return
        if (hasMatchingRule(context, normalized)) {
            LogRepository.append(context, "Skip suspicious sample: has matching rule domain=$normalized app=$appName vendor=$normalizedVendor")
            return
        }
        val httpPathStrongSignal = signal != SuspiciousSignal.DNS_QUERY &&
            (!matchedPathHint.isNullOrBlank() && looksLikeSuspiciousPath(matchedPathHint))
        if (isLowValueSuspiciousSampleDomain(normalized) && !httpPathStrongSignal) {
            LogRepository.append(context, "Skip suspicious sample: low value domain=$normalized app=$appName")
            return
        }
        val normalizedAppName = normalizeSampleAppName(appName)
        if (ShizukuAdControlCatalog.isManagedPromoAppHint(normalizedAppName)) {
            LogRepository.append(context, "Skip suspicious sample: managed promo app domain=$normalized app=$normalizedAppName")
            return
        }
        val novelApp = isNovelAppHint(normalizedAppName)
        val communityApp = isCommunityAppHint(normalizedAppName)
        val hasStrongDomainSignal = looksLikeAdDomain(normalized) ||
            looksLikePushRecommendationAdDomain(normalized) ||
            looksLikeAdSdkInfraDomain(normalized, normalizedVendor) ||
            hasAggressiveNovelAdSignal(normalized)
        val hasRequestSignal = signal != SuspiciousSignal.DNS_QUERY || !matchedPathHint.isNullOrBlank() || !refererDomain.isNullOrBlank()
        val isAggressiveAdApp = isAggressiveAdAppHint(normalizedAppName)
        val samples = readUnknownVendorSamples(context).toMutableMap()
        val previous = samples[normalized]
        val shouldSample = (normalizedVendor == GENERIC_AD_VENDOR && hasStrongDomainSignal) ||
            (normalizedVendor != DEFAULT_VENDOR && looksLikeAdSdkInfraDomain(normalized, normalizedVendor)) ||
            (novelApp && (hasStrongDomainSignal || hasRequestSignal)) ||
            (normalizedVendor == DEFAULT_VENDOR && hasStrongDomainSignal && hasRequestSignal) ||
            (isAggressiveAdApp && hasStrongDomainSignal) ||
            (isAggressiveAdApp && signal != SuspiciousSignal.DNS_QUERY) ||
            (hasStrongDomainSignal && confidenceBoost > 0) ||
            (communityApp && (hasRequestSignal || httpPathStrongSignal)) ||
            (communityApp && (previous?.count ?: 0) >= 2)
        if (!shouldSample) {
            LogRepository.append(context, "Skip suspicious sample: weak signals domain=$normalized app=$normalizedAppName vendor=$normalizedVendor signal=$signal novelApp=$novelApp communityApp=$communityApp hasStrongDomainSignal=$hasStrongDomainSignal hasRequestSignal=$hasRequestSignal isAggressiveAdApp=$isAggressiveAdApp")
            return
        }
        val now = System.currentTimeMillis()
        if (
            previous != null &&
            previous.lastAppName == normalizedAppName &&
            now - previous.lastSampleAt < SUSPICIOUS_SAMPLE_DEBOUNCE_MILLIS
        ) {
            return
        }
        val count = (previous?.count ?: 0) + 1
        val novelHits = (previous?.novelHits ?: 0) + if (novelApp) 1 else 0
        val dnsHits = (previous?.dnsHits ?: 0) + if (signal == SuspiciousSignal.DNS_QUERY) 1 else 0
        val aliasHits = (previous?.aliasHits ?: 0) + if (signal == SuspiciousSignal.DNS_ALIAS) 1 else 0
        val tlsSniHits = (previous?.tlsSniHits ?: 0) + if (signal == SuspiciousSignal.TLS_SNI) 1 else 0
        val httpHits = (previous?.httpHits ?: 0) + if (signal == SuspiciousSignal.HTTP_FLOW) 1 else 0
        val pathHits = (previous?.pathHits ?: 0) + if (!matchedPathHint.isNullOrBlank()) 1 else 0
        val redirectHits = (previous?.redirectHits ?: 0) + if (signal == SuspiciousSignal.HTTP_REDIRECT) 1 else 0
        val appSignalHits = (previous?.appSignalHits ?: 0) + if (isAggressiveAdAppHint(normalizedAppName)) 1 else 0
        val vendorSignalHits = (previous?.vendorSignalHits ?: 0) + if (normalizedVendor != DEFAULT_VENDOR) 1 else 0
        val boost = (previous?.confidenceBoost ?: 0) + confidenceBoost.coerceAtLeast(0)
        samples[normalized] = SuspiciousDomainRecord(
            count = count,
            lastSeenAt = now,
            lastAppName = normalizedAppName,
            lastVendor = normalizedVendor,
            novelHits = novelHits,
            dnsHits = dnsHits,
            aliasHits = aliasHits,
            tlsSniHits = tlsSniHits,
            httpHits = httpHits,
            pathHits = pathHits,
            redirectHits = redirectHits,
            appSignalHits = appSignalHits,
            vendorSignalHits = vendorSignalHits,
            confidenceBoost = boost,
            lastPathHint = matchedPathHint?.take(120) ?: previous?.lastPathHint.orEmpty(),
            refererDomain = normalizeSuspiciousSampleDomain(refererDomain.orEmpty()) ?: previous?.refererDomain.orEmpty(),
            lastSampleAt = now
        )
        TrainingSampleExporter.appendUnknownVendorSample(
            context = context,
            host = normalized,
            path = matchedPathHint,
            protocol = when (signal) {
                SuspiciousSignal.DNS_QUERY, SuspiciousSignal.DNS_ALIAS -> "DNS"
                SuspiciousSignal.TLS_SNI -> "HTTPS"
                SuspiciousSignal.HTTP_FLOW, SuspiciousSignal.HTTP_REDIRECT -> "HTTPS"
            },
            appName = normalizedAppName,
            isHttpdns = normalized.contains("httpdns", ignoreCase = true),
            hitAdToken = hasStrongDomainSignal,
            label = "unlabeled"
        )
        val trimmed = samples.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> {
                    suspiciousDomainConfidenceScore(
                        domain = it.key,
                        vendor = it.value.lastVendor,
                        novelHits = it.value.novelHits,
                        count = it.value.count,
                        appName = it.value.lastAppName,
                        dnsHits = it.value.dnsHits,
                        aliasHits = it.value.aliasHits,
                        tlsSniHits = it.value.tlsSniHits,
                        httpHits = it.value.httpHits,
                        pathHits = it.value.pathHits,
                        redirectHits = it.value.redirectHits,
                        appSignalHits = it.value.appSignalHits,
                        vendorSignalHits = it.value.vendorSignalHits,
                        confidenceBoost = it.value.confidenceBoost,
                        refererDomain = it.value.refererDomain
                    )
                }
                    .thenByDescending { it.value.novelHits }
                    .thenByDescending { it.value.count }
                    .thenByDescending { it.value.lastSeenAt }
                    .thenBy { it.key }
            )
            .take(300)
            .associate { it.key to it.value }
        saveUnknownVendorSamples(context, trimmed, force = count == 1 || count == 5 || count == 20)
        if (count == 1 || count == 5 || count == 20) {
            val scope = if (novelApp) "Novel app suspicious" else "Unknown vendor sample"
            LogRepository.append(context, "$scope x$count: $normalized app=$normalizedAppName vendor=$normalizedVendor")
        }
    }

    fun exportUnknownVendorSamples(context: Context): String {
        val samples = readUnknownVendorSamples(context)
        if (samples.isEmpty()) return "No suspicious ad-like domains sampled\n"
        return buildString {
            append("Suspicious ad-like domains\n")
            append("domain,score,count,novel_hits,dns_hits,alias_hits,tls_sni_hits,http_hits,path_hits,redirect_hits,app_signal_hits,vendor_signal_hits,confidence_boost,last_seen,last_app,last_vendor,last_path_hint,referer_domain\n")
            samples.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> {
                        suspiciousDomainConfidenceScore(
                            domain = it.key,
                            vendor = it.value.lastVendor,
                            novelHits = it.value.novelHits,
                            count = it.value.count,
                            appName = it.value.lastAppName,
                            dnsHits = it.value.dnsHits,
                            aliasHits = it.value.aliasHits,
                            tlsSniHits = it.value.tlsSniHits,
                            httpHits = it.value.httpHits,
                            pathHits = it.value.pathHits,
                            redirectHits = it.value.redirectHits,
                            appSignalHits = it.value.appSignalHits,
                            vendorSignalHits = it.value.vendorSignalHits,
                            confidenceBoost = it.value.confidenceBoost,
                            refererDomain = it.value.refererDomain
                        )
                    }
                        .thenByDescending { it.value.novelHits }
                        .thenByDescending { it.value.count }
                        .thenByDescending { it.value.lastSeenAt }
                        .thenBy { it.key }
                )
                .forEach { entry ->
                    val score = suspiciousDomainConfidenceScore(
                        domain = entry.key,
                        vendor = entry.value.lastVendor,
                        novelHits = entry.value.novelHits,
                        count = entry.value.count,
                        appName = entry.value.lastAppName,
                        dnsHits = entry.value.dnsHits,
                        aliasHits = entry.value.aliasHits,
                        tlsSniHits = entry.value.tlsSniHits,
                        httpHits = entry.value.httpHits,
                        pathHits = entry.value.pathHits,
                        redirectHits = entry.value.redirectHits,
                        appSignalHits = entry.value.appSignalHits,
                        vendorSignalHits = entry.value.vendorSignalHits,
                        confidenceBoost = entry.value.confidenceBoost,
                        refererDomain = entry.value.refererDomain
                    )
                    append(escapeCsvField(entry.key))
                    append(',')
                    append(score)
                    append(',')
                    append(entry.value.count)
                    append(',')
                    append(entry.value.novelHits)
                    append(',')
                    append(entry.value.dnsHits)
                    append(',')
                    append(entry.value.aliasHits)
                    append(',')
                    append(entry.value.tlsSniHits)
                    append(',')
                    append(entry.value.httpHits)
                    append(',')
                    append(entry.value.pathHits)
                    append(',')
                    append(entry.value.redirectHits)
                    append(',')
                    append(entry.value.appSignalHits)
                    append(',')
                    append(entry.value.vendorSignalHits)
                    append(',')
                    append(entry.value.confidenceBoost)
                    append(',')
                    append(escapeCsvField(formatTimestamp(entry.value.lastSeenAt)))
                    append(',')
                    append(escapeCsvField(entry.value.lastAppName.ifBlank { "未知" }))
                    append(',')
                    append(escapeCsvField(entry.value.lastVendor.ifBlank { DEFAULT_VENDOR }))
                    append(',')
                    append(escapeCsvField(entry.value.lastPathHint))
                    append(',')
                    append(escapeCsvField(entry.value.refererDomain))
                    append('\n')
                }
        }
    }

    fun getSuspiciousDomainSamples(context: Context): List<SuspiciousDomainSample> {
        return readUnknownVendorSamples(context)
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> {
                    suspiciousDomainConfidenceScore(
                        domain = it.key,
                        vendor = it.value.lastVendor,
                        novelHits = it.value.novelHits,
                        count = it.value.count,
                        appName = it.value.lastAppName,
                        dnsHits = it.value.dnsHits,
                        aliasHits = it.value.aliasHits,
                        tlsSniHits = it.value.tlsSniHits,
                        httpHits = it.value.httpHits,
                        pathHits = it.value.pathHits,
                        redirectHits = it.value.redirectHits,
                        appSignalHits = it.value.appSignalHits,
                        vendorSignalHits = it.value.vendorSignalHits,
                        confidenceBoost = it.value.confidenceBoost,
                        refererDomain = it.value.refererDomain
                    )
                }
                    .thenByDescending { it.value.novelHits }
                    .thenByDescending { it.value.count }
                    .thenByDescending { it.value.lastSeenAt }
                    .thenBy { it.key }
            )
            .map {
                SuspiciousDomainSample(
                    domain = it.key,
                    count = it.value.count,
                    lastSeenAt = it.value.lastSeenAt,
                    lastAppName = it.value.lastAppName,
                    lastVendor = it.value.lastVendor.ifBlank { DEFAULT_VENDOR },
                    novelHits = it.value.novelHits,
                    dnsHits = it.value.dnsHits,
                    aliasHits = it.value.aliasHits,
                    tlsSniHits = it.value.tlsSniHits,
                    httpHits = it.value.httpHits,
                    pathHits = it.value.pathHits,
                    redirectHits = it.value.redirectHits,
                    appSignalHits = it.value.appSignalHits,
                    vendorSignalHits = it.value.vendorSignalHits,
                    confidenceBoost = it.value.confidenceBoost,
                    lastPathHint = it.value.lastPathHint,
                    refererDomain = it.value.refererDomain
                )
            }
    }

    fun getPendingSuspiciousDomainsForPrompt(context: Context, limit: Int = 30): List<SuspiciousDomainSample> {
        if (limit <= 0) return emptyList()
        return getSuspiciousDomainSamples(context)
            .asSequence()
            .filterNot { hasMatchingRule(context, it.domain) }
            .filter { sample ->
                val score = suspiciousDomainConfidenceScore(
                    domain = sample.domain,
                    vendor = sample.lastVendor,
                    novelHits = sample.novelHits,
                    count = sample.count,
                    appName = sample.lastAppName,
                    dnsHits = sample.dnsHits,
                    aliasHits = sample.aliasHits,
                    tlsSniHits = sample.tlsSniHits,
                    httpHits = sample.httpHits,
                    pathHits = sample.pathHits,
                    redirectHits = sample.redirectHits,
                    appSignalHits = sample.appSignalHits,
                    vendorSignalHits = sample.vendorSignalHits,
                    confidenceBoost = sample.confidenceBoost,
                    refererDomain = sample.refererDomain
                )
                val isCommunityApp = isCommunityAppHint(sample.lastAppName)
                score >= 6 || (isCommunityApp && score >= 4 && sample.count >= 2)
            }
            .take(limit)
            .toList()
    }

    fun suspiciousDomainConfidenceScore(
        domain: String,
        vendor: String,
        novelHits: Int,
        count: Int,
        appName: String? = null,
        dnsHits: Int = 0,
        aliasHits: Int = 0,
        tlsSniHits: Int = 0,
        httpHits: Int = 0,
        pathHits: Int = 0,
        redirectHits: Int = 0,
        appSignalHits: Int = 0,
        vendorSignalHits: Int = 0,
        confidenceBoost: Int = 0,
        refererDomain: String? = null
    ): Int {
        val normalized = sanitizeDomain(domain) ?: return 0
        var score = 0
        val normalizedVendor = normalizeVendorName(vendor)
        if (isWhitelistedDomain(normalized) || isProtectedNovelAppDomain(normalized) || isNovelContentDomain(normalized)) {
            return 0
        }
        if (isLowValueSuspiciousSampleDomain(normalized)) return 0
        if (isBypassProtectionDomain(normalized)) score += 5
        
        // 检查域名是否具有广告特征
        if (looksLikeAdDomain(normalized)) score += 4
        if (looksLikePushRecommendationAdDomain(normalized)) score += 3
        if (hasAggressiveNovelAdSignal(normalized)) score += 3
        if (looksLikeDynamicAliasOrEncryptedDnsAdDomain(normalized)) score += 3
        if (looksLikeHighEntropyAdCandidate(normalized)) score += 2
        
        // 通用广告商识别
        if (normalizedVendor == GENERIC_AD_VENDOR) score += 3
        if (normalizedVendor in highConfidenceAdSdkVendors) score += 2
        
        // 访问频率评分
        if (novelHits >= 3) score += 3 else if (novelHits >= 1) score += 2
        if (count >= 8) score += 2 else if (count >= 3) score += 1
        if (dnsHits >= 5) score += 2 else if (dnsHits >= 2) score += 1
        if (aliasHits >= 2) score += 2 else if (aliasHits >= 1) score += 1
        if (aliasHits >= 1 && looksLikeDynamicAliasOrEncryptedDnsAdDomain(normalized)) score += 2
        if (tlsSniHits >= 2) score += 2 else if (tlsSniHits >= 1) score += 1
        if (httpHits >= 2) score += 2 else if (httpHits >= 1) score += 1
        if (pathHits >= 2) score += 2 else if (pathHits >= 1) score += 1
        if (redirectHits >= 1) score += 2
        if (appSignalHits >= 2) score += 1
        if (vendorSignalHits >= 2) score += 1
        
        // 应用类型识别
        if (isNovelAppHint(appName)) score += 1
        if (isAggressiveAdAppHint(appName)) score += 1
        
        // 社区 App 特别处理：评论区广告识别
        val isCommunityApp = appName?.let { 
            it.contains("coolapk", ignoreCase = true) || 
            it.contains("酷安", ignoreCase = true) ||
            it.contains("贴吧", ignoreCase = true) ||
            it.contains("社区", ignoreCase = true) ||
            it.contains("小红书", ignoreCase = true) ||
            it.contains("xiaohongshu", ignoreCase = true) ||
            it.contains("微博", ignoreCase = true) ||
            it.contains("weibo", ignoreCase = true)
        } == true
        
        // 社区 App 的 HTTP/路径命中加分
        if (isCommunityApp && (httpHits >= 1 || pathHits >= 1)) score += 2
        
        // 社区 App 具有广告特征的域名加分
        if (isCommunityApp && looksLikeAdDomain(normalized)) score += 3
        
        if (!refererDomain.isNullOrBlank()) score += 1
        
        // 如果只有 DNS 命中，没有 HTTP/路径命中，降低可信度
        if (httpHits == 0 && pathHits == 0 && redirectHits == 0 && dnsHits <= 1 && aliasHits == 0 && tlsSniHits == 0) {
            score -= 2
        }
        
        // 如果厂商是默认厂商且没有广告特征，降低可信度
        if (normalizedVendor == DEFAULT_VENDOR && !looksLikeAdSdkInfraDomain(normalized, normalizedVendor) && pathHits == 0 && redirectHits == 0) {
            score -= 1
        }
        
        score += confidenceBoost.coerceIn(0, 4)
        return score.coerceAtLeast(0)
    }

    private fun looksLikeDynamicAliasOrEncryptedDnsAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        val normalizedTokens = lower.replace(Regex("[^a-z0-9]"), "")
        val signals = listOf(
            "cnamead", "adalias", "aliasad", "cnamecloakad", "cloakedad", "adcloak",
            "httpdnsad", "dohad", "doqad", "dotad", "dnsqueryad", "encrypteddnsad",
            "adquic", "quicad", "adgateway", "adresolver", "adresolver"
        )
        return signals.any { lower.contains(it) || normalizedTokens.contains(it) }
    }

    private fun looksLikeHighEntropyAdCandidate(domain: String): Boolean {
        val lower = domain.lowercase()
        val labels = lower.split('.', '-', '_').filter { it.length >= 10 }
        if (labels.isEmpty()) return false
        return labels.any { label ->
            val digitCount = label.count(Char::isDigit)
            val uniqueCount = label.toSet().size
            val adHint = label.contains("ad") || label.contains("ads") || label.contains("adx") || label.contains("bid")
            adHint && digitCount >= 2 && uniqueCount >= 8
        }
    }

    fun shouldTreatAsGeneralAdTraffic(
        domain: String,
        vendor: String,
        appName: String? = null,
        sampleCount: Int = 1
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (ShizukuAdControlCatalog.isManagedPromoAppHint(appName)) return true
        if (isWhitelistedDomain(normalized)) return false
        if (isSensitiveAuthDomain(normalized)) return false
        if (shouldProtectMediaTraffic(normalized)) return false
        if (shouldProtectBusinessTraffic(normalized)) return false
        if (isProtectedNovelAppDomain(normalized) && !looksLikeAdDomain(normalized)) return false
        val normalizedVendor = normalizeVendorName(vendor)
        val explicitAdTraffic = isBypassProtectionDomain(normalized) ||
            looksLikeAdDomain(normalized) ||
            looksLikeAdSdkInfraDomain(normalized, normalizedVendor) ||
            looksLikePushRecommendationAdDomain(normalized)
        if (isSocialCoreDomain(normalized) && !explicitAdTraffic) return false
        if (explicitAdTraffic) return true
        if (shouldForcePushRecommendInspection(normalized, appName, normalizedVendor)) return true
        return suspiciousDomainConfidenceScore(
            domain = normalized,
            vendor = normalizedVendor,
            novelHits = if (isNovelAppHint(appName)) 1 else 0,
            count = sampleCount,
            appName = appName
        ) >= 6
    }

    fun isHighConfidenceSuspiciousDomain(
        domain: String,
        vendor: String,
        novelHits: Int,
        count: Int,
        appName: String? = null,
        dnsHits: Int = 0,
        aliasHits: Int = 0,
        tlsSniHits: Int = 0,
        httpHits: Int = 0,
        pathHits: Int = 0,
        redirectHits: Int = 0,
        appSignalHits: Int = 0,
        vendorSignalHits: Int = 0,
        confidenceBoost: Int = 0,
        refererDomain: String? = null
    ): Boolean {
        return RuleSuspiciousSampleSupport.isHighConfidenceSuspiciousDomain(
            domain = domain,
            vendor = vendor,
            novelHits = novelHits,
            count = count,
            appName = appName,
            dnsHits = dnsHits,
            aliasHits = aliasHits,
            tlsSniHits = tlsSniHits,
            httpHits = httpHits,
            pathHits = pathHits,
            redirectHits = redirectHits,
            appSignalHits = appSignalHits,
            vendorSignalHits = vendorSignalHits,
            confidenceBoost = confidenceBoost,
            refererDomain = refererDomain,
            suspiciousDomainConfidenceScore = ::suspiciousDomainConfidenceScore
        )
    }

    fun isNovelAppHint(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        val normalized = text.replace(alphanumericCnRegex, "")
        return novelAppIdentifiers.any { identifierMatches(text, normalized, it) }
    }

    fun isCommunityAppHint(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        val normalized = text.replace(alphanumericCnRegex, "")
        val communityIdentifiers = listOf(
            "coolapk", "酷安", "贴吧", "tieba", "小红书", "xiaohongshu", "rednote",
            "微博", "weibo", "社区", "community", "论坛", "forum", "bbs", "post", "comment"
        )
        return communityIdentifiers.any { identifierMatches(text, normalized, it) }
    }

    fun isAggressiveAdAppHint(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        if (ShizukuAdControlCatalog.isManagedPromoAppHint(value)) return true
        val normalized = text.replace(alphanumericCnRegex, "")
        val identifiers = listOf(
            "小说", "阅读", "读书", "番茄", "七猫", "书旗", "掌阅", "起点", "纵横", "酷安",
            "资讯", "新闻", "头条", "浏览器", "短视频", "短剧", "短剧大全", "短剧场", "微短剧", "剧场", "小剧场", "漫画", "漫剧", "听书", "追书", "看书",
            "免费短剧", "免费漫画", "漫画大全", "免费小说", "小说大全", "小说阅读", "阅读器", "书城", "红果",
            "video", "reader", "novel", "comic", "manga", "manhua", "cartoon", "freebook", "bookreader",
            "drama", "duanju", "shortdrama", "short_drama", "minidrama", "mini_drama", "episode", "hongguo", "bookcity", "bookstore", "story", "xiaoshuo", "mianfei", "zhuishu", "kanshu"
        )
        return identifiers.any { identifierMatches(text, normalized, it) }
    }

    private fun looksLikePushRecommendationAdDomain(domain: String): Boolean {
        return RuleAdDomainSupport.looksLikePushRecommendationAdDomain(domain)
    }

    fun shouldForcePushRecommendInspection(domain: String, appName: String?, vendor: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (isWhitelistedDomain(normalized)) return false
        if (isSensitiveAuthDomain(normalized)) return false
        if (isGameCoreDomain(normalized)) return false
        if (isSocialCoreDomain(normalized) && !isCommunityAppHint(appName)) return false
        if (shouldProtectMediaTraffic(normalized) || shouldProtectBusinessTraffic(normalized)) return false
        val normalizedVendor = normalizeVendorName(vendor)
        if (looksLikePushRecommendationAdDomain(normalized)) return true
        if (!isAggressiveAdAppHint(appName)) return false
        if (looksLikeAdDomain(normalized)) return true
        return normalizedVendor in highConfidenceAdSdkVendors
    }

    fun isNovelVendor(vendor: String): Boolean = novelVendorNames.contains(normalizeVendorName(vendor))

    private val highConfidenceAdSdkVendors = setOf(
        "穿山甲 (Pangle)",
        "优量汇 (Tencent Marketing)",
        "腾讯广告 (Tencent Ads)",
        "TopOn 聚合广告 (TopOn)",
        "TradPlus 聚合广告 (TradPlus)",
        "Beizi 广告 (Beizi)",
        "AdScope 聚合广告 (AdScope)",
        "有米广告 (Youmi)",
        "Sigmob (Sigmob)",
        "Unity (Unity Ads)",
        "AppLovin (AppLovin)",
        "ironSource (ironSource)",
        "Vungle (Liftoff)",
        "Chartboost (Chartboost)",
        "InMobi (InMobi)",
        "Mintegral (Mintegral)",
        "Meta 平台 (Meta Platforms)",
        "PubMatic (PubMatic)",
        "OpenX (OpenX)",
        "Taboola (Taboola)",
        "Outbrain (Outbrain)",
        "AdColony (AdColony)",
        "Ogury (Ogury)",
        "Digital Turbine (DT Exchange)",
        "Smaato (Smaato)",
        "Tapjoy (Tapjoy)"
    )

    fun looksLikeAdSdkInfraDomain(domain: String, vendor: String = DEFAULT_VENDOR): Boolean {
        return RuleAdDomainSupport.looksLikeAdSdkInfraDomain(
            domain = domain,
            vendor = vendor,
            defaultVendor = DEFAULT_VENDOR,
            sanitizeDomain = ::sanitizeDomain,
            normalizeVendorName = ::normalizeVendorName,
            highConfidenceAdSdkDomains = highConfidenceAdSdkDomains,
            highConfidenceAdSdkVendors = highConfidenceAdSdkVendors
        )
    }

    fun isNovelContentDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        return RuleProtectionSupport.matchesExactOrSubdomain(normalized, novelContentApiDomains)
    }

    fun shouldForceNovelQuicBlock(domain: String, appName: String?, vendor: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (!isNovelAppHint(appName)) return false
        if (isWhitelistedDomain(normalized)) return false
        if (isBypassProtectionDomain(normalized)) return true
        if (isSensitiveAuthDomain(normalized)) return false
        if (isNovelContentDomain(normalized)) return false
        if (isGameCoreDomain(normalized) || isSocialCoreDomain(normalized)) return false
        if (isProtectedNovelAppDomain(normalized)) return false
        if (hasMatchingRulePlaceholder(normalized)) return true
        val normalizedVendor = normalizeVendorName(vendor)
        if (novelAggressiveVendorNames.contains(normalizedVendor)) return true
        if (looksLikeAdDomain(normalized) && hasAggressiveNovelAdSignal(normalized)) return true
        val lower = normalized.lowercase()
        val strongNovelQuicSignals = listOf(
            "ad", "ads", "adx", "dsp", "ssp", "rtb", "bid", "bidding", "promo", "promotion",
            "splash", "reward", "excitation", "inspire", "offer", "offers", "preload", "launch",
            "startup", "tracking", "tracker", "analytics", "stat", "report", "monitor", "log",
            "welfare", "task", "coin", "bonus", "benefit", "offerwall", "monetize", "monetization"
        )
        return strongNovelQuicSignals.any { signal -> keywordMatches(lower, lower.replace(alphanumericRegex, ""), signal) }
    }

    fun isProtectedNovelAppDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lower = normalized.lowercase()
        // 广告特征子域名不保护
        val adSubdomainPatterns = listOf(
            "ad", "ads", "adserver", "adtrack", "adlog", "adx", "adv", "banner", "splash",
            "promotion", "promo", "marketing", "track", "tracking", "log", "logger", "stat", "stats", "analytics"
        )
        if (adSubdomainPatterns.any { lower.startsWith("$it.") || lower.startsWith("$it-") || lower == it }) return false
        val firstLabel = lower.substringBefore('.')
        val aggressivePrefixPatterns = listOf(
            Regex("^ads?\\d+[-_].*"),
            Regex("^adx\\d*[-_].*"),
            Regex("^ad[-_]?.*"),
            Regex("^feed[-_]?ad.*"),
            Regex("^reward[-_]?.*"),
            Regex("^splash[-_]?.*"),
            Regex("^launch[-_]?ad.*"),
            Regex("^open[-_]?screen.*")
        )
        if (aggressivePrefixPatterns.any { it.containsMatchIn(firstLabel) }) return false
        // 具有强烈广告信号的域名不保护
        if (RuleProtectionSupport.hasAggressiveNovelAdSignal(normalized)) return false
        // 移除 looksLikeAdDomain 调用，避免与 isLowValueSuspiciousSampleDomain 形成循环
        return RuleProtectionSupport.matchesExactOrSubdomain(
            normalized,
            buildDomainCandidates(normalized).toSet().intersect(novelAppProtectedSuffixes.toSet())
        )
    }

    fun hasMatchingRule(context: Context, domain: String): Boolean {
        return findMatchingRule(context, domain) != null
    }

    private fun hasMatchingRulePlaceholder(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        return buildDomainCandidates(normalized).any(novelAggressiveExactDomains::contains)
    }

    private fun keywordMatches(domain: String, normalizedTokens: String, keyword: String): Boolean {
        return RuleAdDomainSupport.keywordMatches(domain, normalizedTokens, keyword)
    }

    private fun identifierMatches(text: String, normalizedTokens: String, identifier: String): Boolean {
        return RuleVendorSupport.identifierMatches(text, normalizedTokens, identifier)
    }

    fun availableVendors(context: Context): List<String> {
        return (vendorPatterns.keys + readCustomVendorMap(context).values + getRules(context).map { it.vendor })
            .map(::normalizeVendorName)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    fun updateRuleVendor(context: Context, id: String, vendor: String) {
        val targetVendor = normalizeVendorName(vendor.trim().ifBlank { DEFAULT_VENDOR })
        val updated = getRules(context).map { rule ->
            if (rule.id == id) copyBlockRule(rule, vendor = targetVendor) else rule
        }
        val targetRule = updated.firstOrNull { it.id == id } ?: return
        val customMap = readCustomVendorMap(context).toMutableMap()
        customMap[targetRule.domain] = targetVendor
        save(context, updated)
        saveCustomVendorMap(context, customMap)
    }

    fun analyzeImportContent(context: Context, content: String): RuleAnalysisReport {
        val existingRules = getRules(context)
        val state = createImportAnalysisState(existingRules)
        state.blankOrCommentLines = countBlankOrCommentImportLines(content)
        forEachAnalyzableImportLine(content) { rawLine, line, lineContext ->
            analyzeImportContentLine(context, rawLine, line, lineContext, state)
        }

        return RuleAnalysisReport(
            totalLines = content.lineSequence().count(),
            existingRules = existingRules.size,
            estimatedFinalRules = state.simulatedDomains.size,
            blankOrCommentLines = state.blankOrCommentLines,
            safeBlockedRules = state.safeBlockedRules,
            safeExceptionRules = state.safeExceptionRules,
            duplicateExistingRules = state.duplicateExistingRules,
            duplicateWithinFileRules = state.duplicateWithinFileRules,
            unsupportedModifierRules = state.unsupportedModifierRules,
            cosmeticRules = state.cosmeticRules,
            regexRules = state.regexRules,
            invalidRules = state.invalidRules,
            exceptionRemovalEstimate = state.exceptionRemovalEstimate,
            vendorSummary = state.vendorCount.entries
                .sortedByDescending { it.value }
                .take(16)
                .map { VendorSummary(it.key, it.value) },
            whitelistConflictRules = state.whitelistConflictLines.distinct().size,
            sampleWhitelistConflictLines = state.whitelistConflictLines.distinct().take(10),
            sampleUnsupportedLines = state.unsupportedLines.distinct().take(10),
            sampleInvalidLines = state.invalidLines.distinct().take(10)
        )
    }

    private fun countBlankOrCommentImportLines(content: String): Int {
        return content.lineSequence().count { rawLine ->
            val trimmed = rawLine.trim()
            trimmed.isBlank() ||
                trimmed.startsWith("#") ||
                trimmed.startsWith("!") ||
                trimmed.startsWith("#pkg=", ignoreCase = true)
        }
    }

    private fun forEachAnalyzableImportLine(
        content: String,
        block: (rawLine: String, line: String, lineContext: RuleParsingSupport.LineContext) -> Unit
    ) {
        var lineContext = RuleParsingSupport.LineContext()
        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                lineContext = RuleParsingSupport.parseRuleLineContext(trimmed)
                return@forEach
            }
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                return@forEach
            }
            block(rawLine, trimmed, lineContext)
        }
    }

    private fun createImportAnalysisState(existingRules: List<BlockRule>): ImportAnalysisState {
        val existingDomains = existingRules.map(BlockRule::domain).toMutableSet()
        return ImportAnalysisState(
            existingRuleKeys = existingRules.mapTo(linkedSetOf()) { buildRuleIdentityKey(it) },
            simulatedDomains = existingDomains.toMutableSet()
        )
    }

    private fun analyzeImportContentLine(
        context: Context,
        rawLine: String,
        line: String,
        lineContext: RuleParsingSupport.LineContext,
        state: ImportAnalysisState
    ) {
        val parsedRules = parseRuleLine(rawLine, lineContext)
        if (parsedRules.isEmpty()) {
            applyImportInvalidLineAnalysis(line, state)
            return
        }
        parsedRules.forEach { parsedRule ->
            applyImportParsedRuleAnalysis(context, parsedRule, line, state)
        }
    }

    private fun applyImportInvalidLineAnalysis(
        line: String,
        state: ImportAnalysisState
    ) {
        val invalidAnalysis = analyzeInvalidImportRule(line)
        val (unsupportedDelta, invalidDelta) = applyInvalidRuleAnalysis(
            result = invalidAnalysis,
            unsupportedLines = state.unsupportedLines,
            invalidLines = state.invalidLines
        )
        state.unsupportedModifierRules += unsupportedDelta
        state.invalidRules += invalidDelta
    }

    private fun applyImportParsedRuleAnalysis(
        context: Context,
        parsedRule: ParsedRule,
        line: String,
        state: ImportAnalysisState
    ) {
        val preAnalysis = analyzeParsedRulePreStep(parsedRule, line)
        val (regexDelta, cosmeticDelta) = applyParsedRulePreAnalysis(
            result = preAnalysis,
            whitelistConflictLines = state.whitelistConflictLines
        )
        state.regexRules += regexDelta
        state.cosmeticRules += cosmeticDelta
        val ruleKey = buildParsedRuleIdentityKey(parsedRule)
        val analysisStep = analyzeParsedRuleStep(
            context = context,
            parsedRule = parsedRule,
            ruleKey = ruleKey,
            existingRuleKeys = state.existingRuleKeys,
            seenBlocked = state.seenBlocked,
            seenExceptions = state.seenExceptions,
            simulatedDomains = state.simulatedDomains
        )
        state.duplicateExistingRules += analysisStep.duplicateExistingDelta
        state.duplicateWithinFileRules += analysisStep.duplicateWithinFileDelta
        state.safeBlockedRules += analysisStep.safeBlockedDelta
        state.safeExceptionRules += analysisStep.safeExceptionDelta
        state.exceptionRemovalEstimate += analysisStep.exceptionRemovalDelta
        analysisStep.vendor?.let { incrementVendorCount(state.vendorCount, it) }
    }

    private fun parseImportLines(content: String): ParsedRules {
        return parseImportLines(content.lineSequence())
    }

    private fun parseImportLinesStreaming(
        lines: Sequence<String>,
        onProgress: ((lineCount: Int, parsedRuleCount: Int) -> Unit)? = null
    ): ParsedRules {
        val parsedRules = ParsedRuleBuckets()
        var lineContext = RuleParsingSupport.LineContext()
        var lineCount = 0
        var parsedRuleCount = 0
        var lastProgressAt = 0L
        lines.forEach { rawLine ->
            lineCount += 1
            RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach { fragment ->
                val trimmed = fragment.trim()
                if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                    lineContext = RuleParsingSupport.parseRuleLineContext(trimmed)
                    return@forEach
                }
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    return@forEach
                }
                parseFastImportRule(trimmed, lineContext)?.let { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                    parsedRuleCount += 1
                    return@forEach
                }
                val parsedLineRules = parseRuleLine(fragment, lineContext)
                parsedLineRules.forEach { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                    parsedRuleCount += 1
                }
                if (parsedLineRules.isEmpty()) {
                    parseUnsupportedImportRule(fragment, lineContext)?.let { parsedRule ->
                        mergeParsedImportRule(parsedRules, parsedRule)
                        parsedRuleCount += 1
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (onProgress != null && (lineCount % 2000 == 0 || now - lastProgressAt >= 750L)) {
                lastProgressAt = now
                onProgress.invoke(lineCount, parsedRuleCount)
            }
        }
        onProgress?.invoke(lineCount, parsedRuleCount)
        return ParsedRules(
            blockedRules = parsedRules.blocked.values.toList(),
            exceptionRules = parsedRules.exceptions.values.toList(),
            badfilterRules = parsedRules.badfilters.values.toList()
        )
    }

    private fun parseUnsupportedImportRule(rawLine: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val line = RuleParsingSupport.stripInlineRuleComment(normalizeMessyRuleLine(rawLine)).trim()
        if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) return null
        val normalizedLine = RuleParsingSupport.stripYamlListPrefix(RuleParsingSupport.unwrapRuleWrapper(line)).trim()
        if (normalizedLine.isBlank()) return null
        val isException = normalizedLine.startsWith("@@")
        val working = if (isException) normalizedLine.removePrefix("@@") else normalizedLine
        val domain = extractDomainCandidate(working)
            ?.first
            ?.let(::parseDomainsFromPattern)
            ?.firstOrNull()
            ?: extractLooseDomainForUnsupportedRule(working)
            ?: UNSUPPORTED_RULE_DOMAIN
        return ParsedRule(
            domain = domain,
            isException = isException,
            cosmeticSelector = normalizedLine.take(500),
            isUnsupported = true,
            vendorHints = lineContext.vendorHints + "暂不支持规则"
        )
    }

    private fun extractLooseDomainForUnsupportedRule(line: String): String? {
        val match = Regex("""([a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+)""").find(line) ?: return null
        return sanitizeDomain(match.value)
    }

    // P0.4 新增：支持 Sequence 流式解析（避免大文件一次性加载）
    private fun parseImportLines(lines: Sequence<String>): ParsedRules {
        val parsedRules = ParsedRuleBuckets()
        val expandedLines = RuleParsingSupport.expandIndentedYamlPayloadBlocks(lines.toList())
        
        // 顺序处理规则解析（保证结果可预测，避免并行处理的线程安全问题）
        expandedLines.forEach { rawLine ->
            RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach { fragment ->
                val trimmed = fragment.trim()
                if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                    return@forEach
                }
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    return@forEach
                }
                parseFastImportRule(trimmed, RuleParsingSupport.LineContext())?.let { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                    return@forEach
                }
                parseRuleLine(fragment, RuleParsingSupport.LineContext()).forEach { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                }
            }
        }

        return ParsedRules(
            blockedRules = parsedRules.blocked.values.toList(),
            exceptionRules = parsedRules.exceptions.values.toList(),
            badfilterRules = parsedRules.badfilters.values.toList()
        )
    }

    private fun mergeParsedImportRule(
        buckets: ParsedRuleBuckets,
        parsedRule: ParsedRule
    ) {
        when {
            parsedRule.isBadfilter -> mergeParsedRuleInto(buckets.badfilters, parsedRule)
            parsedRule.isException -> mergeParsedRuleInto(buckets.exceptions, parsedRule)
            else -> mergeParsedRuleInto(buckets.blocked, parsedRule)
        }
    }

    private fun parseFastImportRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val normalizedLine = RuleParsingSupport.stripYamlListPrefix(RuleParsingSupport.unwrapRuleWrapper(line)).trim()
        if (normalizedLine.isBlank()) return null
        parseFastAdblockDomainRule(normalizedLine, lineContext)?.let { return it }
        parseFastHostsDomainRule(normalizedLine, lineContext)?.let { return it }
        parseFastDnsRedirectRule(normalizedLine, lineContext)?.let { return it }
        parseFastProviderDomainRule(normalizedLine, lineContext)?.let { return it }
        parseFastWildcardDomainRule(normalizedLine, lineContext)?.let { return it }
        sanitizeDomain(normalizeDomainToken(normalizedLine))?.let { domain ->
            return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
        }
        return null
    }

    private fun parseFastAdblockDomainRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val isException = line.startsWith("@@||")
        val prefix = if (isException) "@@||" else "||"
        if (!line.startsWith(prefix)) return null
        val body = line.removePrefix(prefix)
        val domainPart = body.substringBefore('^').substringBefore('/').substringBefore('$').trim()
        val domain = sanitizeDomain(normalizeDomainToken(domainPart)) ?: return null
        val modifierPart = line.substringAfter('$', missingDelimiterValue = "")
        return ParsedRule(
            domain = domain,
            isException = isException,
            important = modifierPart.split(',').any { it.equals("important", ignoreCase = true) },
            isBadfilter = modifierPart.split(',').any { it.equals("badfilter", ignoreCase = true) },
            vendorHints = lineContext.vendorHints
        )
    }

    private fun parseFastHostsDomainRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val parts = line.split(splitWhitespaceRegex).filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val ip = parts[0]
        if (ip != "0.0.0.0" && ip != "127.0.0.1" && ip != "::" && ip != "::1") return null
        val domain = sanitizeDomain(normalizeDomainToken(parts[1])) ?: return null
        return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
    }

    private fun parseFastDnsRedirectRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val trimmed = line.trim()
        val prefixes = listOf("address=/", "server=/", "local=/", "bogus-nxdomain=")
        val prefix = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return null
        val value = if (prefix.endsWith('/')) {
            trimmed.substring(prefix.length).substringBefore('/').trim()
        } else {
            trimmed.substringAfter('=').trim()
        }
        val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
        return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
    }

    private fun parseFastProviderDomainRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val trimmed = line.trim().removeSurrounding("\"").removeSurrounding("'")
        val value = when {
            trimmed.startsWith("DOMAIN-SUFFIX,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("HOST-SUFFIX,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("DOMAIN,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("HOST,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("DOMAIN-KEYWORD,", ignoreCase = true) -> return null
            trimmed.startsWith("HOST-KEYWORD,", ignoreCase = true) -> return null
            trimmed.startsWith("URL-REGEX,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("domain:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("domainSuffix:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("domain-full:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("full:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("suffix:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("geosite:", ignoreCase = true) -> return null
            trimmed.startsWith("host-suffix,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("host,", ignoreCase = true) -> trimmed.substringAfter(',')
            else -> return null
        }.substringBefore(',').trim()
        val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
        return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
    }

    private fun parseFastWildcardDomainRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val trimmed = line.trim().removeSurrounding("\"").removeSurrounding("'")
        val value = when {
            trimmed.startsWith("+.") -> trimmed.substring(2)
            trimmed.startsWith("*.") -> trimmed.substring(2)
            trimmed.startsWith(".") -> trimmed.substring(1)
            trimmed.startsWith("||") -> trimmed.removePrefix("||").substringBefore('^').substringBefore('/').substringBefore('$')
            else -> return null
        }.trim()
        val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
        return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
    }

    fun parseManualInput(rawInput: String): List<String> {
        return collectManualInputDomains(rawInput).toList()
    }

    fun findWhitelistConflictsInManualInput(rawInput: String): List<String> {
        return collectManualInputDomains(rawInput)
            .filter(::isWhitelistedDomain)
            .distinct()
    }

    fun removeWhitelistConflictLines(content: String): String {
        val sanitizedLines = mutableListOf<String>()
        forEachExpandedRuleFragment(content, includeContextFragments = true) { fragment, lineContext ->
            val trimmed = fragment.trim()
            if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                sanitizedLines += fragment
                return@forEachExpandedRuleFragment
            }
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                sanitizedLines += fragment
                return@forEachExpandedRuleFragment
            }
            if (!hasWhitelistConflict(fragment, lineContext)) {
                sanitizedLines += fragment
            }
        }
        return sanitizedLines.joinToString("\n")
    }

    private fun collectManualInputDomains(rawInput: String): LinkedHashSet<String> {
        val blocked = linkedSetOf<String>()
        forEachExpandedRuleFragment(rawInput) { fragment, lineContext ->
            val trimmed = fragment.trim()
            if (trimmed.isBlank()) return@forEachExpandedRuleFragment
            val parsedRules = parseRuleLine(trimmed, lineContext)
            if (parsedRules.isNotEmpty()) {
                parsedRules.filterNot { it.isException || it.isBadfilter }.forEach { blocked += it.domain }
                return@forEachExpandedRuleFragment
            }
            trimmed.split(splitWhitespaceRegex)
                .mapNotNull { sanitizeDomain(normalizeDomainToken(it)) }
                .forEach { blocked += it }
        }
        return blocked
    }

    private fun hasWhitelistConflict(
        fragment: String,
        lineContext: RuleParsingSupport.LineContext
    ): Boolean {
        return parseRuleLine(fragment, lineContext).any { parsedRule ->
            !parsedRule.isException && !parsedRule.isBadfilter && isWhitelistedDomain(parsedRule.domain)
        }
    }

    private fun forEachExpandedRuleFragment(
        content: String,
        includeContextFragments: Boolean = false,
        block: (fragment: String, lineContext: RuleParsingSupport.LineContext) -> Unit
    ) {
        var lineContext = RuleParsingSupport.LineContext()
        RuleParsingSupport.expandIndentedYamlPayloadBlocks(content.lineSequence().toList()).forEach { rawLine ->
            RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach { fragment ->
                val trimmed = fragment.trim()
                if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                    lineContext = RuleParsingSupport.parseRuleLineContext(trimmed)
                    if (includeContextFragments) {
                        block(fragment, lineContext)
                    }
                    return@forEach
                }
                block(fragment, lineContext)
            }
        }
    }

    private fun parseRuleLine(rawLine: String, lineContext: RuleParsingSupport.LineContext = RuleParsingSupport.LineContext()): List<ParsedRule> {
        val line = RuleParsingSupport.stripInlineRuleComment(normalizeMessyRuleLine(rawLine))
        if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) return emptyList()
        val normalizedLine = RuleParsingSupport.stripYamlListPrefix(RuleParsingSupport.unwrapRuleWrapper(line))
        
        parseCompositeRule(normalizedLine, lineContext)?.let { return it }
        parseCosmeticRule(normalizedLine)?.let { return listOf(it.withVendorHints(lineContext.vendorHints)) }
        parseRegexRule(normalizedLine)?.let { return listOf(it.withVendorHints(lineContext.vendorHints)) }
        parseInlinePayloadRule(normalizedLine, lineContext)?.let { return it }
        parseClashRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseSurgeWildcardDomain(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseLoonKeywordRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseLoonUrlRegex(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseAbpDomainRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseShadowrocketRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseSurgeUrlKeyword(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseHostsRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseDnsmasqRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseSmartdnsRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseOpenwrtRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseAdguardRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseEasyclashRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加端口通配符规则解析（如 *:443$network）
        parsePortWildcardRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加 V2Ray/Xray 格式规则解析（domain:xxx, domainSuffix:xxx, ip:xxx）
        parseV2RayRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加 Shadowrocket 格式规则解析（host-suffix, host-keyword, ip-cidr）
        parseShadowrocketFormatRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加 Quantumult X 格式规则解析（host, ip-cidr, ip6-cidr）
        parseQuantumultXRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加点前缀域名解析（.example.com）
        parseDotPrefixDomainRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加 IPv6 Hosts 规则解析
        parseIPv6HostsRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }

        val trimmedLine = normalizedLine.trim()
        if (trimmedLine.startsWith("+.") && trimmedLine.substring(2).isNotBlank()) {
            val suffixDomain = sanitizeDomain(trimmedLine.substring(2))
            if (suffixDomain != null) return listOf(ParsedRule(domain = suffixDomain, isException = false, vendorHints = lineContext.vendorHints))
        }

        val isException = line.startsWith("@@")
        val working = if (isException) line.removePrefix("@@") else line

        val candidate = extractDomainCandidate(working) ?: return emptyList()
        val (patternPart, modifierPart) = candidate
        val modifierInfo = parseModifierInfo(modifierPart)
        if (modifierInfo.invalid || modifierInfo.unsupportedModifiers.isNotEmpty()) return emptyList()
        if (!canSafelyApplyModifierContext(patternPart, modifierInfo)) return emptyList()

        val domains = parseDomainsFromPattern(patternPart)
        val keywordPattern = if (patternPart.contains('*')) {
            extractKeywordPattern(patternPart)
        } else {
            null
        }
        val pathPattern = extractPathPattern(patternPart) ?: modifierInfo.pathPattern

        return domains.map { domain ->
            ParsedRule(
                domain = domain,
                isException = isException,
                isBadfilter = modifierInfo.badfilter,
                dnsTypes = modifierInfo.dnsTypes,
                excludedDnsTypes = modifierInfo.excludedDnsTypes,
                thirdParty = modifierInfo.thirdParty,
                firstParty = modifierInfo.firstParty,
                important = modifierInfo.important,
                redirect = modifierInfo.redirect,
                domainConstraints = modifierInfo.domainConstraints,
                excludedDomainConstraints = modifierInfo.excludedDomainConstraints,
                denyallow = modifierInfo.denyallow,
                urlblock = modifierInfo.urlblock,
                requestTypes = modifierInfo.requestTypes,
                appPackages = modifierInfo.appPackages,
                destinationPorts = modifierInfo.destinationPorts,
                sourcePorts = modifierInfo.sourcePorts,
                keywordPattern = keywordPattern,
                pathPattern = pathPattern,
                ipCidr = null,
                regexPattern = null,
                cosmeticSelector = null,
                removeParams = modifierInfo.removeParams,
                removeParamRegexes = modifierInfo.removeParamRegexes,
                removeRequestHeaders = modifierInfo.removeRequestHeaders,
                setRequestHeaders = modifierInfo.setRequestHeaders,
                replaceRules = modifierInfo.replaceRules,
                cspValue = modifierInfo.cspValue,
                redirectResource = modifierInfo.redirectResource,
                jsInjectRules = modifierInfo.jsinject?.let { setOf(it) }.orEmpty(),
                vendorHints = lineContext.vendorHints
            )
        }
    }

    private fun ParsedRule.withVendorHints(vendorHints: Set<String>): ParsedRule {
        if (vendorHints.isEmpty()) return this
        if (this.vendorHints.isNotEmpty()) return copy(vendorHints = this.vendorHints + vendorHints)
        return copy(vendorHints = vendorHints)
    }

    private fun List<ParsedRule>.withVendorHints(vendorHints: Set<String>): List<ParsedRule> {
        if (vendorHints.isEmpty()) return this
        return map { it.withVendorHints(vendorHints) }
    }

    private data class ParsedRuleBuckets(
        val blocked: LinkedHashMap<String, ParsedRule> = linkedMapOf(),
        val exceptions: LinkedHashMap<String, ParsedRule> = linkedMapOf(),
        val badfilters: LinkedHashMap<String, ParsedRule> = linkedMapOf()
    )

    private fun mergeParsedRuleInto(target: MutableMap<String, ParsedRule>, parsedRule: ParsedRule) {
        val key = parsedRuleBucketKey(parsedRule)
        target[key] = mergeParsedRule(target[key], parsedRule)
    }

    private fun parsedRuleBucketKey(parsedRule: ParsedRule): String {
        if (parsedRule.isUnsupported ||
            parsedRule.regexPattern != null ||
            parsedRule.cosmeticSelector != null ||
            parsedRule.keywordPattern != null ||
            parsedRule.pathPattern != null ||
            parsedRule.ipCidr != null ||
            parsedRule.removeParams.isNotEmpty() ||
            parsedRule.removeParamRegexes.isNotEmpty() ||
            parsedRule.removeRequestHeaders.isNotEmpty() ||
            parsedRule.setRequestHeaders.isNotEmpty() ||
            parsedRule.replaceRules.isNotEmpty() ||
            parsedRule.cspValue != null ||
            parsedRule.jsInjectRules.isNotEmpty() ||
            parsedRule.redirectResource != null
        ) {
            return buildParsedRuleIdentity(parsedRule)
        }
        return parsedRule.domain
    }

    private fun parseCompositeRule(line: String, lineContext: RuleParsingSupport.LineContext): List<ParsedRule>? {
        val envelope = RuleSemanticParserSupport.parseCompositeEnvelope(line) ?: return null
        val parts = envelope.parts
        if (parts.isEmpty()) return emptyList()
        val parsed = parts.flatMap { part -> parseRuleLine(part, lineContext) }
        if (parsed.isEmpty()) return emptyList()
        return when (envelope.operator) {
            "AND" -> mergeCompositeAndRules(parsed)
            "OR" -> parsed.filter(::isSafelyActionableAdRule).distinctBy(::buildParsedRuleIdentity)
            else -> emptyList()
        }
    }

    private fun mergeCompositeAndRules(rules: List<ParsedRule>): List<ParsedRule> {
        val actionable = rules.filter(::isSafelyActionableAdRule)
        if (actionable.isEmpty()) return emptyList()
        val baseRule = actionable.firstOrNull { it.domain != "*" } ?: actionable.firstOrNull { it.ipCidr != null } ?: actionable.first()
        return listOf(actionable.fold(baseRule) { acc, rule -> mergeParsedRule(acc, rule) })
    }

    private fun buildParsedRuleIdentity(rule: ParsedRule): String {
        return listOf(
            rule.domain,
            rule.isException.toString(),
            rule.important.toString(),
            rule.keywordPattern.orEmpty(),
            rule.pathPattern.orEmpty(),
            rule.ipCidr.orEmpty(),
            rule.regexPattern.orEmpty(),
            rule.cosmeticSelector.orEmpty(),
            rule.appPackages.toSortedSet().joinToString("|"),
            rule.excludedDomainConstraints.toSortedSet().joinToString("|"),
            rule.requestTypes.toSortedSet().joinToString("|"),
            rule.destinationPorts.toSortedSet().joinToString("|"),
            rule.sourcePorts.toSortedSet().joinToString("|"),
            rule.removeParams.toSortedSet().joinToString("|"),
            rule.removeParamRegexes.toSortedSet().joinToString("|"),
            rule.removeRequestHeaders.toSortedSet().joinToString("|"),
            rule.setRequestHeaders.toSortedSet().joinToString("|"),
            rule.replaceRules.toSortedSet().joinToString("|"),
            rule.cspValue.orEmpty(),
            rule.jsInjectRules.toSortedSet().joinToString("|"),
            rule.redirectResource.orEmpty(),
            rule.denyallow.toSortedSet().joinToString("|")
        ).joinToString("::")
    }

    private fun isSafelyActionableAdRule(rule: ParsedRule): Boolean {
        if (rule.domain == "*") {
            return rule.destinationPorts.isNotEmpty() ||
                rule.sourcePorts.isNotEmpty() ||
                rule.appPackages.isNotEmpty()
        }
        if (rule.appPackages.isNotEmpty()) {
            return true
        }
        if (rule.ipCidr != null) return true
        if (rule.regexPattern != null || rule.keywordPattern != null || rule.pathPattern != null) return true
        return looksLikeAdDomain(rule.domain) || looksLikeBypassProtectionDomain(rule.domain)
    }

    private fun parseInlinePayloadRule(line: String, lineContext: RuleParsingSupport.LineContext): List<ParsedRule>? {
        val trimmed = line.trim()
        val payloadPrefix = when {
            trimmed.startsWith("payload:", ignoreCase = true) -> "payload:"
            trimmed.startsWith("payload=", ignoreCase = true) -> "payload="
            trimmed.startsWith("rules:", ignoreCase = true) -> "rules:"
            trimmed.startsWith("rules=", ignoreCase = true) -> "rules="
            trimmed.startsWith("payload-item:", ignoreCase = true) -> "payload-item:"
            trimmed.startsWith("payload-item=", ignoreCase = true) -> "payload-item="
            else -> null
        } ?: return null
        val payloadBody = trimmed.substring(payloadPrefix.length).trim()
        if (payloadBody.isBlank()) return emptyList()
        val payloadItems = if (payloadBody.startsWith("[") && payloadBody.endsWith("]")) {
            RuleSemanticParserSupport.extractInlinePayloadItems(payloadBody)
        } else {
            listOf(payloadBody)
        }
        return payloadItems.flatMap { item -> parseRuleLine(item, lineContext) }
    }

    private fun removeRulesInternal(
        current: List<BlockRule>,
        normalizedIds: Set<String>,
        identityKeys: Set<String>
    ): RuleRemovalResult {
        if (normalizedIds.isEmpty() && identityKeys.isEmpty()) {
            return RuleRemovalResult(current, 0)
        }
        val remaining = current.filterNot { rule ->
            val idMatched = normalizedIds.isNotEmpty() && normalizedIds.contains(rule.id.trim())
            val identityMatched = identityKeys.isNotEmpty() && identityKeys.contains(buildRuleIdentityKey(rule))
            idMatched || identityMatched
        }
        return RuleRemovalResult(remaining = remaining, removedCount = current.size - remaining.size)
    }

    private fun parseRegexRule(line: String): ParsedRule? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("/") || !trimmed.endsWith("/")) return null
        val body = trimmed.removePrefix("/").removeSuffix("/").trim()
        if (body.isBlank()) return null
        return ParsedRule(
            domain = extractRegexRuleDomain(body) ?: REGEX_RULE_DOMAIN,
            isException = false,
            regexPattern = body
        )
    }

    private fun parseCosmeticRule(line: String): ParsedRule? {
        val marker = listOf("#@#", "##", "#$#", "#%#").firstOrNull { line.contains(it) } ?: return null
        val domainPart = line.substringBefore(marker).trim()
        val selector = line.substringAfter(marker, "").trim()
        if (selector.isBlank()) return null
        val parsedDomain = sanitizeDomain(normalizeDomainToken(domainPart))
        if (marker == "#%#") {
            val scriptlet = buildAdGuardScriptletInjection(selector) ?: return null
            return ParsedRule(
                domain = parsedDomain ?: COSMETIC_RULE_DOMAIN,
                isException = false,
                jsInjectRules = setOf(scriptlet)
            )
        }
        return ParsedRule(
            domain = parsedDomain ?: COSMETIC_RULE_DOMAIN,
            isException = marker == "#@#",
            cosmeticSelector = selector
        )
    }

    private fun buildAdGuardScriptletInjection(selector: String): String? {
        val trimmed = selector.trim()
        val scriptletCall = trimmed.substringAfter("scriptlet(", missingDelimiterValue = "")
            .substringBeforeLast(')', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?: return null
        val args = splitScriptletArguments(scriptletCall)
        val name = args.firstOrNull()?.trim()?.trim('"', '\'')?.lowercase() ?: return null
        val scriptArgs = args.drop(1).map { it.trim().trim('"', '\'') }
        return when (name) {
            "remove-attr", "ubo-remove-attr" -> buildRemoveAttrScriptlet(scriptArgs)
            "remove-class", "ubo-remove-class" -> buildRemoveClassScriptlet(scriptArgs)
            "set-constant", "ubo-set-constant" -> buildSetConstantScriptlet(scriptArgs)
            "abort-on-property-read", "ubo-abort-on-property-read" -> buildAbortOnPropertyReadScriptlet(scriptArgs)
            "abort-on-property-write", "ubo-abort-on-property-write" -> buildAbortOnPropertyWriteScriptlet(scriptArgs)
            "prevent-settimeout", "prevent-set-timeout", "ubo-prevent-settimeout", "ubo-prevent-set-timeout" -> buildPreventTimerScriptlet("setTimeout", scriptArgs)
            "prevent-setinterval", "prevent-set-interval", "ubo-prevent-setinterval", "ubo-prevent-set-interval" -> buildPreventTimerScriptlet("setInterval", scriptArgs)
            "prevent-fetch", "ubo-prevent-fetch" -> buildPreventNetworkScriptlet("fetch", scriptArgs)
            "prevent-xhr", "prevent-xmlhttprequest", "ubo-prevent-xhr", "ubo-prevent-xmlhttprequest" -> buildPreventNetworkScriptlet("xhr", scriptArgs)
            "prevent-addeventlistener", "prevent-add-event-listener", "ubo-prevent-addeventlistener", "ubo-prevent-add-event-listener" -> buildPreventAddEventListenerScriptlet(scriptArgs)
            else -> null
        }
    }

    private fun splitScriptletArguments(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        input.forEach { char ->
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> {
                    current.append(char)
                    escaped = true
                }
                quote != null -> {
                    current.append(char)
                    if (char == quote) quote = null
                }
                char == '\'' || char == '"' -> {
                    current.append(char)
                    quote = char
                }
                char == ',' -> {
                    result += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        if (current.isNotBlank()) result += current.toString().trim()
        return result
    }

    private fun buildRemoveAttrScriptlet(args: List<String>): String? {
        val attr = args.getOrNull(0)?.takeIf(::isSafeDomToken) ?: return null
        val selector = args.getOrNull(1)?.takeIf(::isSafeSelectorToken) ?: "[$attr]"
        return """
            (function(){try{var run=function(){document.querySelectorAll(${jsString(selector)}).forEach(function(el){el.removeAttribute(${jsString(attr)});});};run();if(window.MutationObserver&&document.documentElement){new MutationObserver(run).observe(document.documentElement,{childList:true,subtree:true,attributes:true});}}catch(e){}})();
        """.trimIndent()
    }

    private fun buildRemoveClassScriptlet(args: List<String>): String? {
        val className = args.getOrNull(0)?.takeIf(::isSafeDomToken) ?: return null
        val selector = args.getOrNull(1)?.takeIf(::isSafeSelectorToken) ?: ".$className"
        return """
            (function(){try{var run=function(){document.querySelectorAll(${jsString(selector)}).forEach(function(el){el.classList.remove(${jsString(className)});});};run();if(window.MutationObserver&&document.documentElement){new MutationObserver(run).observe(document.documentElement,{childList:true,subtree:true,attributes:true});}}catch(e){}})();
        """.trimIndent()
    }

    private fun buildSetConstantScriptlet(args: List<String>): String? {
        val property = args.getOrNull(0)?.takeIf(::isSafePropertyPath) ?: return null
        val value = normalizeScriptletConstant(args.getOrNull(1) ?: "undefined") ?: return null
        return """
            (function(){try{var path=${jsString(property)}.split('.');var root=window;for(var i=0;i<path.length-1;i++){root[path[i]]=root[path[i]]||{};root=root[path[i]];}Object.defineProperty(root,path[path.length-1],{configurable:true,get:function(){return $value;},set:function(){}});}catch(e){}})();
        """.trimIndent()
    }

    private fun buildAbortOnPropertyReadScriptlet(args: List<String>): String? {
        val property = args.getOrNull(0)?.takeIf(::isSafePropertyPath) ?: return null
        return """
            (function(){try{var path=${jsString(property)}.split('.');var root=window;for(var i=0;i<path.length-1;i++){root[path[i]]=root[path[i]]||{};root=root[path[i]];}Object.defineProperty(root,path[path.length-1],{configurable:true,get:function(){throw new ReferenceError('Blocked by HanFeng scriptlet');},set:function(){}});}catch(e){}})();
        """.trimIndent()
    }

    private fun buildAbortOnPropertyWriteScriptlet(args: List<String>): String? {
        val property = args.getOrNull(0)?.takeIf(::isSafePropertyPath) ?: return null
        return """
            (function(){try{var path=${jsString(property)}.split('.');var root=window;for(var i=0;i<path.length-1;i++){root[path[i]]=root[path[i]]||{};root=root[path[i]];}Object.defineProperty(root,path[path.length-1],{configurable:true,get:function(){return undefined;},set:function(){throw new ReferenceError('Blocked by HanFeng scriptlet');}});}catch(e){}})();
        """.trimIndent()
    }

    private fun buildPreventTimerScriptlet(timerName: String, args: List<String>): String? {
        val pattern = args.firstOrNull()?.takeIf(::isSafeScriptletPattern) ?: return null
        val delay = args.getOrNull(1)?.trim()?.trim('"', '\'')?.toLongOrNull()
        val delayCheck = delay?.takeIf { it >= 0 }?.let { " && delay === $it" }.orEmpty()
        val timerKey = jsString(timerName)
        return """
            (function(){try{var original=window[$timerKey];window[$timerKey]=function(fn,delay){var source=String(fn);if(source.indexOf(${jsString(pattern)})!==-1$delayCheck){return 0;}return original.apply(this,arguments);};}catch(e){}})();
        """.trimIndent()
    }

    private fun buildPreventNetworkScriptlet(kind: String, args: List<String>): String? {
        val pattern = args.firstOrNull()?.takeIf(::isSafeScriptletPattern) ?: return null
        return when (kind) {
            "fetch" -> """
                (function(){try{if(!window.fetch)return;var original=window.fetch;window.fetch=function(input,init){var url=String(typeof input==='string'?input:(input&&input.url)||'');if(url.indexOf(${jsString(pattern)})!==-1){return Promise.resolve(new Response('',{status:204,statusText:'Blocked by HanFeng'}));}return original.apply(this,arguments);};}catch(e){}})();
            """.trimIndent()
            "xhr" -> """
                (function(){try{if(!window.XMLHttpRequest)return;var open=XMLHttpRequest.prototype.open;var send=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(method,url){this.__hanfengBlocked=String(url||'').indexOf(${jsString(pattern)})!==-1;return open.apply(this,arguments);};XMLHttpRequest.prototype.send=function(){if(this.__hanfengBlocked)return;return send.apply(this,arguments);};}catch(e){}})();
            """.trimIndent()
            else -> null
        }
    }

    private fun buildPreventAddEventListenerScriptlet(args: List<String>): String? {
        val eventPattern = args.getOrNull(0)?.takeIf(::isSafeScriptletPattern) ?: return null
        val handlerPattern = args.getOrNull(1)?.takeIf(::isSafeScriptletPattern)
        val handlerCheck = handlerPattern?.let { " && String(listener).indexOf(${jsString(it)})!==-1" }.orEmpty()
        return """
            (function(){try{var original=EventTarget.prototype.addEventListener;EventTarget.prototype.addEventListener=function(type,listener,options){if(String(type).indexOf(${jsString(eventPattern)})!==-1$handlerCheck){return;}return original.apply(this,arguments);};}catch(e){}})();
        """.trimIndent()
    }

    private fun normalizeScriptletConstant(raw: String): String? {
        return when (raw.trim().trim('"', '\'').lowercase()) {
            "undefined" -> "undefined"
            "null" -> "null"
            "true" -> "true"
            "false" -> "false"
            "noopfunc", "emptyfunc", "function" -> "function(){}"
            "nooparray", "emptyarr", "[]" -> "[]"
            "noopobject", "emptyobj", "{}" -> "{}"
            "0", "1" -> raw.trim().trim('"', '\'')
            else -> null
        }
    }

    private fun isSafeDomToken(value: String): Boolean {
        return value.length in 1..80 && value.matches(Regex("[A-Za-z0-9_-]+"))
    }

    private fun isSafePropertyPath(value: String): Boolean {
        return value.length in 1..160 && value.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
    }

    private fun isSafeSelectorToken(value: String): Boolean {
        return value.length in 1..240 && !value.contains('<') && !value.contains('>') && !value.contains("</script", ignoreCase = true)
    }

    private fun isSafeScriptletPattern(value: String): Boolean {
        return value.length in 1..160 && !value.contains('<') && !value.contains('>') && !value.contains("</script", ignoreCase = true)
    }

    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$escaped'"
    }

    private fun parseSurgeWildcardDomain(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("*.") && !trimmed.startsWith(".")) return null
        if (trimmed.startsWith("..") || trimmed.startsWith("*.")) {
            val domainPart = trimmed.removePrefix("*.").removePrefix(".")
            val domain = sanitizeDomain(domainPart) ?: return null
            return listOf(ParsedRule(domain = domain, isException = false))
        }
        return null
    }

    private fun parseLoonKeywordRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("KEYWORD:", ignoreCase = true) &&
            !trimmed.startsWith("DOMAIN-KEYWORD:", ignoreCase = true) &&
            !trimmed.startsWith("HOST-KEYWORD:", ignoreCase = true)) return null
        val value = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
        if (value.isBlank()) return null
        return listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
    }

    private fun parseLoonUrlRegex(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("URL-REGEX:", ignoreCase = true) &&
            !trimmed.startsWith("URL-REGEXP:", ignoreCase = true) &&
            !trimmed.startsWith("DOMAIN-REGEX:", ignoreCase = true) &&
            !trimmed.startsWith("DOMAIN-REGEXP:", ignoreCase = true)) return null
        val value = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
        if (value.isBlank()) return null
        val cleaned = value.removePrefix("\"").removeSuffix("\"")
        return listOf(ParsedRule(domain = cleaned, isException = false, regexPattern = cleaned))
    }

    private fun parseAbpDomainRule(line: String): List<ParsedRule>? {
        if (!line.contains(":") || !line.contains("domain=")) return null
        val colonIndex = line.indexOf(':')
        val prefix = line.substring(0, colonIndex).trim()
        if (!prefix.equals("abp", ignoreCase = true) && !prefix.equals("abp-inject", ignoreCase = true)) return null
        val abpPart = line.substring(colonIndex + 1).trim()
        if (!abpPart.startsWith("||")) return null
        val domain = abpPart.removePrefix("||").substringBefore('^').substringBefore('/').trim()
        if (domain.isBlank()) return null
        val sanitized = sanitizeDomain(domain) ?: return null
        return listOf(ParsedRule(domain = sanitized, isException = false))
    }

    private fun parseShadowrocketRule(line: String): List<ParsedRule>? {
        if (!line.contains(':')) return null
        val colonIndex = line.indexOf(':')
        val prefix = line.substring(0, colonIndex).trim()
        val value = normalizeStructuredRuleValue(line.substring(colonIndex + 1))
        if (value.isBlank()) return null
        return when (RuleSemanticParserSupport.normalizeStructuredRuleType(prefix)) {
            "domain", "full", "full-domain", "domain-full", "host", "hostname", "hostname-full", "host-full", "domain-exact", "host-exact", "hostname-exact", "domain-set", "domain-full-set", "host-set", "hostname-set" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-suffix", "domain-suffixes", "domain-suffix-set", "host-suffix", "host-suffix-set", "hostname-suffix", "hostname-suffix-set", "suffix" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-wildcard", "domain-wildcard-set", "host-wildcard", "host-wildcard-set", "hostname-wildcard", "hostname-wildcard-set" -> {
                val domain = sanitizeDomain(value.removePrefix("*.")) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-keyword", "host-keyword", "hostname-keyword", "keyword" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "domain-regex", "domain-regexp", "host-regex", "host-regexp", "hostname-regex", "hostname-regexp", "url-regex", "url-regexp", "regex" -> {
                listOf(ParsedRule(domain = value, isException = false, regexPattern = value))
            }
            "url-keyword" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "url-wildcard", "url-wildcard-set" -> {
                parseUrlWildcardRuleValue(value)?.let(::listOf) ?: return null
            }
            "ip-cidr", "ip-cidr6", "ipcidr", "ipcidr6" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "rule-set", "ruleset", "rule-provider", "rule_provider", "domain-set", "domain-full-set", "host-set", "hostname-set" -> {
                val domainToken = findActionableStructuredToken(listOf(value)) ?: return emptyList()
                listOf(ParsedRule(domain = domainToken, isException = false))
            }
            "dest-port", "dst-port", "destination-port" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, destinationPorts = setOf(port)))
            }
            "src-port", "source-port" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, sourcePorts = setOf(port)))
            }
            "user-agent", "ua" -> {
                emptyList()
            }
            "process-name", "package-name" -> {
                val packageName = sanitizeAppPackageToken(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, appPackages = setOf(packageName)))
            }
            "geosite" -> parseGeositeCategoryRule(value)
            "src-ip-cidr", "src-ip-cidr6", "ip-asn", "asn", "geoip", "network", "inbound", "protocol" -> {
                emptyList()
            }
            "final", "match" -> {
                emptyList()
            }
            else -> null
        }
    }

    private fun parseSurgeUrlKeyword(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("URL-KEYWORD:", ignoreCase = true)) return null
        val value = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
        if (value.isBlank()) return null
        return listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
    }

    private fun parseGeositeCategoryRule(value: String): List<ParsedRule> {
        val normalized = value.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .substringBefore('@')
            .lowercase()
        if (normalized !in geositeAdCategoryTokens) return emptyList()
        return geositeAdSeedDomains.mapNotNull { seedDomain ->
            sanitizeDomain(seedDomain)?.let { domain ->
                ParsedRule(
                    domain = domain,
                    isException = false,
                    vendorHints = setOf("GEOSITE 广告类别")
                )
            }
        }
    }

    private fun parseHostsRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        
        // IPv4 Hosts 格式：0.0.0.0 example.com, 127.0.0.1 example.com
        val ipv4Pattern = """^(?:0\.0\.0\.0|127\.0\.0\.1)\s+(\S+)""".toRegex()
        ipv4Pattern.find(trimmed)?.let { match ->
            val domain = match.groupValues[1]
            if (domain.equals("localhost", ignoreCase = true)) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        // IPv6 Hosts 格式：::1 example.com, :: localhost
        val ipv6Pattern = """^(?:::+[0-9a-fA-F]*|[0-9a-fA-F]+(?::[0-9a-fA-F]*){2,})\s+(\S+)""".toRegex()
        ipv6Pattern.find(trimmed)?.let { match ->
            val domain = match.groupValues[1]
            if (domain.equals("localhost", ignoreCase = true)) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        return null
    }

    private fun parseDnsmasqRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("address=/", ignoreCase = true)) return null
        
        val addressValue = trimmed.substringAfter("address=/", "").trim()
        if (addressValue.isBlank()) return null
        
        val parts = addressValue.split("/", limit = 2)
        val domain = parts.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        
        if (domain.equals("localhost", ignoreCase = true)) return null
        val target = parts.getOrNull(1)?.trim()
        
        if (target == "127.0.0.1" || target == "0.0.0.0" || target == "::") {
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        val sanitized = sanitizeDomain(domain) ?: return null
        return listOf(ParsedRule(domain = sanitized, isException = false))
    }

    private fun parseSmartdnsRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("address ", ignoreCase = true) && 
            !trimmed.startsWith("nameserver ", ignoreCase = true) &&
            !trimmed.startsWith("ipset ", ignoreCase = true)) return null
        
        if (trimmed.startsWith("address ", ignoreCase = true)) {
            val addressValue = trimmed.substringAfter("address ", "").trim()
            val domain = addressValue.split(" ").firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            if (domain.equals("localhost", ignoreCase = true) || domain.startsWith("-")) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        if (trimmed.startsWith("nameserver ", ignoreCase = true)) {
            val nsValue = trimmed.substringAfter("nameserver ", "").trim()
            val domain = nsValue.split(" ").firstOrNull()?.takeIf { it.isNotBlank() && !it.contains(":") } ?: return null
            if (domain.equals("localhost", ignoreCase = true) || domain.startsWith("-")) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        if (trimmed.startsWith("ipset ", ignoreCase = true)) {
            val ipsetValue = trimmed.substringAfter("ipset ", "").trim()
            val parts = ipsetValue.split(" ")
            if (parts.size < 2) return null
            val domain = parts[1].trim().takeIf { it.isNotBlank() } ?: return null
            if (domain.equals("localhost", ignoreCase = true) || domain.startsWith("-")) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        return null
    }

    private fun parseOpenwrtRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        
        if (trimmed.startsWith("config rule", ignoreCase = true)) {
            return emptyList()
        }
        
        if (trimmed.startsWith("option name ", ignoreCase = true) ||
            trimmed.startsWith("option proto ", ignoreCase = true) ||
            trimmed.startsWith("option src ", ignoreCase = true) ||
            trimmed.startsWith("option dest ", ignoreCase = true)) {
            return emptyList()
        }
        
        if (trimmed.startsWith("option target ", ignoreCase = true)) {
            return emptyList()
        }
        
        return null
    }

    private fun parseAdguardRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        
        val mobileAppPattern = """^@@\|\|(\S+)\^.*app.*\$""".toRegex()
        val mobileAppMatch = mobileAppPattern.find(trimmed)
        if (mobileAppMatch != null) {
            val domain = mobileAppMatch.groupValues[1]
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        val basicPattern = """^\|\|(\S+)\^.*\$""".toRegex()
        val basicMatch = basicPattern.find(trimmed)
        if (basicMatch != null) {
            val domain = basicMatch.groupValues[1]
            if (domain.contains("/") || domain.contains("#")) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        return null
    }

    private fun parseEasyclashRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        
        if (trimmed.startsWith("- ")) {
            val domain = trimmed.removePrefix("- ").trim()
            if (domain.isBlank() || domain.startsWith("#")) return null
            
            val ipCidrPattern = """^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d+)$""".toRegex()
            val ipMatch = ipCidrPattern.find(domain)
            if (ipMatch != null) {
                val cidr = ipMatch.groupValues[1]
                val sanitized = sanitizeIpCidr(cidr) ?: return null
                return listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        return null
    }

    private fun parsePortWildcardRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        
        // 支持格式：*:PORT$network 或 *:PORT
        // 例如：*:443$network *:444$network *:445$network
        // 匹配 *:(\d+) 可选择性地后跟 $network
        val portPattern = """^\*:(\d+)(?:[$]network)?$""".toRegex()
        val match = portPattern.find(trimmed) ?: return null
        
        val port = parseSinglePortValue(match.groupValues[1]) ?: return null
        
        return listOf(ParsedRule(domain = "*", isException = false, destinationPorts = setOf(port)))
    }

    private fun parseV2RayRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        
        // V2Ray/Xray 格式：domain:example.com
        val domainPattern = """^domain:(.+)$""".toRegex()
        domainPattern.matchEntire(trimmed)?.let { match ->
            val domain = match.groupValues[1].trim()
            val sanitized = sanitizeDomain(normalizeDomainToken(domain)) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        // V2Ray/Xray 格式：domainSuffix:example.com
        val domainSuffixPattern = """^domainSuffix:(.+)$""".toRegex()
        domainSuffixPattern.matchEntire(trimmed)?.let { match ->
            val domain = match.groupValues[1].trim()
            val sanitized = sanitizeDomain(normalizeDomainToken(domain)) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        // V2Ray/Xray 格式：domainKeyword:keyword
        val domainKeywordPattern = """^domainKeyword:(.+)$""".toRegex()
        domainKeywordPattern.matchEntire(trimmed)?.let { match ->
            val keyword = match.groupValues[1].trim().lowercase()
            return listOf(ParsedRule(domain = keyword, isException = false, keywordPattern = keyword))
        }
        
        // V2Ray/Xray 格式：domainRegex:pattern
        val domainRegexPattern = """^domainRegex:(.+)$""".toRegex()
        domainRegexPattern.matchEntire(trimmed)?.let { match ->
            val regex = match.groupValues[1].trim()
            return listOf(ParsedRule(domain = regex, isException = false, regexPattern = regex))
        }
        
        // V2Ray/Xray 格式：ip:192.168.1.0/24
        val ipPattern = """^ip:(.+)$""".toRegex()
        ipPattern.matchEntire(trimmed)?.let { match ->
            val cidr = match.groupValues[1].trim()
            val sanitized = sanitizeIpCidr(cidr) ?: return null
            return listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
        }
        
        // V2Ray/Xray 格式：geosite:category 或 geoip:cn（不支持但返回空列表避免错误）
        if (trimmed.startsWith("geosite:", ignoreCase = true) || 
            trimmed.startsWith("geoip:", ignoreCase = true)) {
            return emptyList()
        }
        
        return null
    }

    private fun parseShadowrocketFormatRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        if (!trimmed.contains(",")) return null
        
        val segments = trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return null
        
        val ruleType = segments[0].lowercase()
        val value = normalizeStructuredRuleValue(segments[1])
        if (value.isBlank()) return null
        
        return when (ruleType) {
            "host-suffix", "hosts-suffix", "hostsuffix" -> {
                val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "host-keyword", "hosts-keyword", "hostkeyword" -> {
                val keyword = value.lowercase()
                listOf(ParsedRule(domain = keyword, isException = false, keywordPattern = keyword))
            }
            "host-wildcard", "hosts-wildcard", "hostwildcard" -> {
                val cleaned = value.removePrefix("*.").removePrefix("*.")
                val domain = sanitizeDomain(cleaned) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "ip-cidr", "ipcidr" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "ip-cidr6", "ipcidr6" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            else -> null
        }
    }

    private fun parseQuantumultXRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        if (!trimmed.contains(",")) return null
        
        val segments = trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return null
        
        val ruleType = segments[0].lowercase()
        val value = normalizeStructuredRuleValue(segments[1])
        if (value.isBlank()) return null
        
        return when (ruleType) {
            "host", "hosts" -> {
                val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "host-wildcard", "hostwildcard" -> {
                val cleaned = value.removePrefix("*.").removePrefix("*.")
                val domain = sanitizeDomain(cleaned) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "ip-cidr", "ipcidr" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "ip6-cidr", "ip6cidr", "ipv6-cidr" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "ip-asn", "ipasn", "asn" -> {
                // ASN 规则暂时不支持但返回空列表避免错误
                emptyList()
            }
            else -> null
        }
    }

    private fun parseDotPrefixDomainRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        if (!trimmed.startsWith(".")) return null
        
        // 支持格式：.example.com（等同于 domainSuffix）
        val domain = trimmed.removePrefix(".")
        if (domain.isBlank() || domain.contains("/") || domain.contains("$")) return null
        
        val sanitized = sanitizeDomain(normalizeDomainToken(domain)) ?: return null
        return listOf(ParsedRule(domain = sanitized, isException = false))
    }

    private fun parseIPv6HostsRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        
        // 支持格式：::1 example.com, :: localhost, fe80::1 example.com
        // IPv6 地址格式：多个冒号 + 可选的十六进制
        val ipv6Pattern = """^([0-9a-fA-F:]+(?:\d{1,2})?)\s+(\S+)""".toRegex()
        val match = ipv6Pattern.find(trimmed) ?: return null
        
        val address = match.groupValues[1]
        val domain = match.groupValues[2]
        
        // 验证是否为有效的 IPv6 地址（简单检查：包含冒号且至少两个冒号）
        if (!address.contains("::") && address.count { it == ':' } < 2) return null
        if (domain.equals("localhost", ignoreCase = true)) return null
        
        // 简单解析 IPv6 地址
        return runCatching {
            java.net.InetAddress.getByName(address)
            val sanitized = sanitizeDomain(normalizeDomainToken(domain)) ?: return null
            listOf(ParsedRule(domain = sanitized, isException = false))
        }.getOrNull()
    }

    private fun parseClashRule(line: String): List<ParsedRule>? {
        if (!line.contains(',')) return null
        val segments = line.split(',').map { it.trim().removeSurrounding("\"").removeSurrounding("'") }.filter { it.isNotBlank() }
        if (segments.size < 2) return null
        val ruleType = RuleSemanticParserSupport.normalizeStructuredRuleType(segments[0]).uppercase()
        val value = normalizeStructuredRuleValue(segments[1])
        if (value.isBlank()) return null
        return when (ruleType) {
            "DOMAIN-SUFFIX", "DOMAIN-SUFFIXES", "DOMAIN-SUFFIX-SET", "HOST-SUFFIX", "HOST-SUFFIX-SET", "HOSTNAME-SUFFIX", "HOSTNAME-SUFFIX-SET" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN", "HOST", "HOSTNAME", "HOST-FULL", "HOSTNAME-FULL", "DOMAIN-FULL", "DOMAIN-EXACT", "HOST-EXACT", "HOSTNAME-EXACT", "DOMAIN-SET", "DOMAIN-FULL-SET", "HOST-SET", "HOSTNAME-SET" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN-KEYWORD", "HOST-KEYWORD", "HOSTNAME-KEYWORD" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "DOMAIN-WILDCARD", "DOMAIN-WILDCARD-SET", "HOST-WILDCARD", "HOST-WILDCARD-SET", "HOSTNAME-WILDCARD", "HOSTNAME-WILDCARD-SET" -> {
                val cleaned = value.removePrefix("*.").removePrefix("*.")
                val domain = sanitizeDomain(cleaned) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN-REGEX", "DOMAIN-REGEXP", "HOST-REGEX", "HOST-REGEXP", "HOSTNAME-REGEX", "HOSTNAME-REGEXP", "URL-REGEX", "URL-REGEXP" -> {
                listOf(ParsedRule(domain = value, isException = false, regexPattern = value))
            }
            "URL-KEYWORD" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "URL-WILDCARD", "URL-WILDCARD-SET" -> {
                parseUrlWildcardRuleValue(value)?.let(::listOf) ?: return null
            }
            "DEST-PORT", "DST-PORT", "DESTINATION-PORT" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, destinationPorts = setOf(port)))
            }
            "SRC-PORT", "SOURCE-PORT" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, sourcePorts = setOf(port)))
            }
            "USER-AGENT", "UA" -> {
                emptyList()
            }
            "IP-CIDR", "IP-CIDR6", "IPCIDR", "IPCIDR6" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "RULE-SET", "RULESET", "RULE-PROVIDER", "RULE_PROVIDER" -> {
                val adLikeDomain = findActionableStructuredToken(segments.drop(1)) ?: return emptyList()
                listOf(ParsedRule(domain = adLikeDomain, isException = false))
            }
            "PROCESS-NAME", "PACKAGE-NAME" -> {
                val packageName = sanitizeAppPackageToken(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, appPackages = setOf(packageName)))
            }
            "GEOSITE" -> parseGeositeCategoryRule(value)
            "SRC-IP-CIDR", "SRC-IP-CIDR6", "IP-ASN", "ASN", "GEOIP", "NETWORK", "INBOUND", "PROTOCOL" -> emptyList()
            "FINAL", "MATCH" -> {
                emptyList()
            }
            else -> null
        }
    }

    private fun parseUrlWildcardRuleValue(value: String): ParsedRule? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null
        val domain = sanitizeDomain(normalizeDomainToken(normalized)) ?: return null
        val pathPattern = extractPathPattern(normalized)
        val keywordPattern = if (normalized.contains('*')) extractUrlWildcardKeywordPattern(normalized) else null
        return ParsedRule(
            domain = domain,
            isException = false,
            keywordPattern = keywordPattern,
            pathPattern = pathPattern
        )
    }

    private fun extractUrlWildcardKeywordPattern(pattern: String): String? {
        val pathPattern = extractPathPattern(pattern)
        if (pathPattern != null) {
            val cleaned = pathPattern
                .replace('*', ' ')
                .replace('^', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim()
            return cleaned.takeIf { it.isNotBlank() }?.lowercase()
        }
        return extractKeywordPattern(pattern)
    }

    private fun extractRegexRuleDomain(pattern: String): String? {
        return RuleAdDomainSupport.extractRegexRuleDomain(
            pattern = pattern,
            sanitizeDomain = ::sanitizeDomain,
            domainExtractRegex = domainExtractRegex,
            domainSubdomainRegex = domainSubdomainRegex
        )
    }

    private fun extractDomainCandidate(line: String): Pair<String, String?>? {
        val patternPart = line.substringBefore('$').trim()
        val modifierPart = line.substringAfter('$', missingDelimiterValue = "").trim().ifBlank { null }
        if (patternPart.isBlank()) return null
        return patternPart to modifierPart
    }

    private fun normalizeMessyRuleLine(rawLine: String): String = RuleTextNormalizer.normalizeMessyRuleLine(rawLine)

    private fun parseDomainsFromPattern(patternPart: String): List<String> {
        var trimmed = RuleParsingSupport.unwrapRuleWrapper(RuleParsingSupport.stripYamlListPrefix(patternPart.trim()))
        if (trimmed.startsWith("payload:", ignoreCase = true)) {
            trimmed = trimmed.substringAfter(':').trim()
        } else if (trimmed.startsWith("payload=", ignoreCase = true)) {
            trimmed = trimmed.substringAfter('=').trim()
        }
        if (trimmed.equals("payload:", ignoreCase = true) || trimmed.equals("payload", ignoreCase = true)) return emptyList()
        if (trimmed == "||*^" || trimmed == "||*" || trimmed == "*") return listOf("*")
        val dnsmasqPrefix = dnsmasqPrefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        return when {
            trimmed.startsWith("||") -> listOfNotNull(parseDomainAnchorPattern(trimmed.removePrefix("||")))
            trimmed.startsWith("|") -> listOfNotNull(parseExactAnchorPattern(trimmed.removePrefix("|").removeSuffix("|")))
            dnsmasqPrefix != null -> parseDnsmasqDomains(trimmed, dnsmasqPrefix)
            else -> parseStructuredDomainRule(trimmed).ifEmpty { parseHostsOrPlainDomains(trimmed) }
        }
    }

    private fun parseDomainAnchorPattern(pattern: String): String? {
        return RuleDomainParserSupport.parseDomainAnchorPattern(
            pattern = pattern,
            sanitizeDomain = ::sanitizeDomain,
            parseWildcardDomainAnchorPattern = ::parseWildcardDomainAnchorPattern
        )
    }

    private fun parseExactAnchorPattern(pattern: String): String? {
        return RuleDomainParserSupport.parseExactAnchorPattern(
            pattern = pattern,
            sanitizeDomain = ::sanitizeDomain,
            parseWildcardDomainAnchorPattern = ::parseWildcardDomainAnchorPattern
        )
    }

    private fun parseWildcardDomainAnchorPattern(pattern: String): String? {
        return RuleDomainParserSupport.parseWildcardDomainAnchorPattern(pattern, ::sanitizeDomain)
    }

    private fun isSafeDomainPatternSuffix(suffix: String): Boolean {
        return RuleDomainParserSupport.isSafeDomainPatternSuffix(suffix)
    }

    private fun parseHostsOrPlainDomains(patternPart: String): List<String> {
        return RuleDomainParserSupport.parseHostsOrPlainDomains(
            patternPart = patternPart,
            whitespaceRegex = whitespaceRegex,
            sanitizeDomain = ::sanitizeDomain,
            ipV4Regex = ipV4Regex
        )
    }

    private fun parseDnsmasqDomains(patternPart: String, matchedPrefix: String): List<String> {
        return RuleDomainParserSupport.parseDnsmasqDomains(
            patternPart = patternPart,
            matchedPrefix = matchedPrefix,
            sanitizeDomain = ::sanitizeDomain,
            ipV4Regex = ipV4Regex
        )
    }

    private fun parseStructuredDomainRule(patternPart: String): List<String> {
        val normalized = RuleSemanticParserSupport.unwrapCompositeRule(
            RuleParsingSupport.unwrapRuleWrapper(RuleParsingSupport.stripYamlListPrefix(patternPart))
        )
        if (normalized == "*") return listOf("*")
        RuleSemanticParserSupport.parseEmbeddedRuleCarrierDomain(normalized, ::findActionableStructuredToken)?.let { return listOf(it) }
        RuleSemanticParserSupport.parsePrefixedDomainRule(normalized, ::parseStructuredDomainToken)?.let { return listOf(it) }
        sanitizeDomain(RuleDomainParserSupport.normalizeDomainToken(normalized))?.let { directDomain ->
            if (extractPathPattern(normalized) != null) return listOf(directDomain)
        }
        val segments = normalized.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return emptyList()
        val ruleType = RuleSemanticParserSupport.normalizeStructuredRuleType(segments.first())
        val domainToken = findActionableStructuredToken(segments.drop(1))
            ?: segments.drop(1).mapNotNull(::parseStructuredDomainToken).firstOrNull()
            ?: return emptyList()
        return when (ruleType) {
            "domain-suffix", "domain-suffixes", "domain-suffix-set", "domain", "host-suffix", "host-suffix-set", "host", "hostname-suffix", "hostname-suffix-set", "hostname", "suffix" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "domain-wildcard", "domain-wildcard-set", "host-wildcard", "host-wildcard-set", "hostname-wildcard", "hostname-wildcard-set" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken.removePrefix("*.")))
            }
            "full", "full-domain", "hostname", "host-full", "hostname-full", "domain-full", "domain-exact", "host-exact", "hostname-exact", "domain-set", "domain-full-set", "host-set", "hostname-set" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "domain-keyword", "host-keyword", "hostname-keyword", "keyword" -> {
                emptyList()
            }
            "domain-regex", "domain-regexp", "host-regex", "host-regexp", "hostname-regex", "hostname-regexp", "url-regex", "url-regexp",
            "ip-cidr", "ip-cidr6", "ipcidr", "ipcidr6", "src-ip-cidr", "src-ip-cidr6", "ip-asn", "asn", "geoip", "geosite", "rule-set", "process-name",
            "process-path", "package-name", "user-agent", "inbound", "network",
            "protocol", "and", "or", "not" -> {
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun findActionableStructuredToken(values: List<String>): String? {
        return RuleDomainParserSupport.findActionableStructuredToken(
            values = values,
            parseStructuredDomainToken = ::parseStructuredDomainToken,
            looksLikeAdDomain = ::looksLikeAdDomain,
            looksLikeBypassProtectionDomain = ::looksLikeBypassProtectionDomain
        )
    }

    private fun normalizeStructuredRuleValue(value: String): String {
        return RuleSemanticParserSupport.normalizeStructuredRuleValue(value)
    }

    private val dnsmasqPrefixes = listOf("address=/", "server=/", "local=/", "ipset=/", "nftset=/")

    private fun parseStructuredDomainToken(raw: String): String? {
        return RuleDomainParserSupport.parseStructuredDomainToken(raw, ::sanitizeDomain)
    }

    private fun normalizeDomainToken(raw: String): String {
        return RuleDomainParserSupport.normalizeDomainToken(raw)
    }

    private fun isHostsIpToken(token: String): Boolean {
        return RuleDomainParserSupport.isHostsIpToken(token)
    }

    private fun looksLikeIpAddress(token: String): Boolean {
        return RuleDomainParserSupport.looksLikeIpAddress(token, ipV4Regex)
    }

    fun looksLikeAdDomain(domain: String): Boolean {
        return RuleAdDomainSupport.looksLikeAdDomain(
            domain = domain,
            adKeywords = adKeywords,
            weakAdKeywords = weakAdKeywords,
            isLowValueSuspiciousSampleDomain = ::isLowValueSuspiciousSampleDomain,
            looksLikePushRecommendationAdDomain = ::looksLikePushRecommendationAdDomain,
            looksLikeAdSdkInfraDomain = { candidate -> looksLikeAdSdkInfraDomain(candidate) }
        )
    }

    private fun isLowValueSuspiciousSampleDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return true
        // 基础服务白名单 - 这些永远不应该被拦截
        if (isWhitelistedDomain(normalized)) return true
        if (isSensitiveAuthDomain(normalized)) return true
        if (isGameCoreDomain(normalized)) return true
        // 社交核心域名直接过滤（避免与 looksLikeAdDomain 形成循环调用）
        if (isSocialCoreDomain(normalized)) return true
        if (isMediaCoreDomain(normalized)) return true
        if (isBusinessCoreDomain(normalized)) return true
        if (isNovelContentDomain(normalized)) return true
        if (isProtectedNovelAppDomain(normalized) && !RuleProtectionSupport.hasAggressiveNovelAdSignal(normalized)) return true
        if (isProtectedByteDanceInfraDomain(normalized) && !RuleProtectionSupport.hasAggressiveNovelAdSignal(normalized) && !looksLikePushRecommendationAdDomain(normalized)) return true
        return false
    }

    private fun isProtectedByteDanceInfraDomain(domain: String): Boolean {
        return RuleAdDomainSupport.isProtectedByteDanceInfraDomain(
            domain = domain,
            sanitizeDomain = ::sanitizeDomain,
            byteDanceInfraProtectedSuffixes = byteDanceInfraProtectedSuffixes,
            novelAggressiveExactDomains = novelAggressiveExactDomains
        )
    }

    private fun looksLikeWhitelistedRootAdSubdomain(domain: String): Boolean {
        return RuleAdDomainSupport.looksLikeWhitelistedRootAdSubdomain(
            domain = domain,
            looksLikePushRecommendationAdDomain = ::looksLikePushRecommendationAdDomain,
            looksLikeAdSdkInfraDomain = { candidate -> looksLikeAdSdkInfraDomain(candidate) }
        )
    }

    private fun looksLikeBypassProtectionDomain(domain: String): Boolean = isBypassProtectionDomain(domain)

    private fun looksLikeComplexRulePattern(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        return trimmed.contains("://") ||
            trimmed.contains('*') ||
            trimmed.contains('^') ||
            trimmed.contains('|') ||
            trimmed.contains('=') ||
            trimmed.contains('@')
    }

    private fun extractUnsupportedModifiers(modifierPart: String?): List<String> {
        return RuleModifierSupport.extractUnsupportedModifiers(
            modifierPart = modifierPart,
            unsupportedAdGuardModifiers = unsupportedAdGuardModifiers,
            ignorableAdGuardModifiers = ignorableAdGuardModifiers
        )
    }

    private fun parseModifierInfo(modifierPart: String?): RuleModifierSupport.ModifierInfo {
        return RuleModifierSupport.parseModifierInfo(
            modifierPart = modifierPart,
            unsupportedAdGuardModifiers = unsupportedAdGuardModifiers,
            ignorableAdGuardModifiers = ignorableAdGuardModifiers,
            sanitizeAppPackageToken = ::sanitizeAppPackageToken,
            mapDnsTypeToken = ::mapDnsTypeToken,
            normalizeDnsTypes = ::normalizeDnsTypes,
            mergeDnsTypes = ::mergeDnsTypes
        )
    }

    private fun parseRemoveParamToken(token: String): RuleModifierSupport.RemoveParamToken? {
        return RuleModifierSupport.parseRemoveParamToken(token)
    }

    private fun extractKeywordPattern(pattern: String): String? {
        val trimmed = pattern.trim().removePrefix("*").removeSuffix("*").removeSuffix("^").removeSuffix("/")
        if (trimmed.isBlank()) return null
        return trimmed.lowercase()
    }

    private fun parsePortModifierValues(value: String, inverted: Boolean): Set<Int>? {
        return RuleModifierSupport.parsePortModifierValues(value, inverted)
    }

    private fun parseSinglePortValue(value: String): Int? {
        val port = value.trim().toIntOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    private fun extractPathPattern(pattern: String): String? {
        val trimmed = pattern.trim()
        val withoutDomainAnchor = when {
            trimmed.startsWith("||") -> trimmed.removePrefix("||")
            trimmed.startsWith("|") -> trimmed.removePrefix("|")
            else -> trimmed
        }
        val withoutScheme = withoutDomainAnchor.removePrefix("https://").removePrefix("http://")
        val slashIndex = withoutScheme.indexOf('/')
        if (slashIndex < 0 || slashIndex >= withoutScheme.length - 1) return null
        val path = withoutScheme.substring(slashIndex)
            .substringBefore('$')
            .substringBefore('|')
            .substringBefore('?')
            .trim()
        if (path.isBlank() || path == "/") return null
        return path.lowercase()
    }

    private fun canSafelyApplyModifierContext(patternPart: String, modifierInfo: RuleModifierSupport.ModifierInfo): Boolean {
        // 用户导入的规则全部拦截，不做自作主张的放行检查
        // 只在导入时提醒可能影响正常网络的规则类型
        
        // 标记可能影响正常网络的修饰符（用于提醒用户）
        val mayAffectNetwork = modifierInfo.network ||
            modifierInfo.blockIpv6 ||
            modifierInfo.blockIpv4 ||
            modifierInfo.dnsrewrite != null ||
            !modifierInfo.client.isEmpty() ||
            !modifierInfo.notClient.isEmpty() ||
            !modifierInfo.mac.isEmpty() ||
            !modifierInfo.notMac.isEmpty() ||
            !modifierInfo.asn.isEmpty() ||
            !modifierInfo.notAsn.isEmpty()
        
        // 有网络层修饰符时可以添加提醒，但仍然放行规则导入
        // 提醒逻辑在导入时处理，这里始终返回 true 确保规则被执行
        return true
    }

    private fun isSimpleDomainScopePattern(patternPart: String): Boolean {
        val trimmed = RuleParsingSupport.stripYamlListPrefix(patternPart.trim())
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("||")) {
            val anchorBody = trimmed.removePrefix("||")
            val boundaryIndex = sequenceOf(anchorBody.indexOf('^'), anchorBody.indexOf('/'), anchorBody.indexOf('?'))
                .filter { it >= 0 }
                .minOrNull()
                ?: anchorBody.length
            val hostToken = anchorBody.substring(0, boundaryIndex).trim()
            return sanitizeDomain(normalizeDomainToken(hostToken)) != null || parseWildcardDomainAnchorPattern(hostToken) != null
        }
        if (trimmed.startsWith("|")) {
            return parseExactAnchorPattern(trimmed.removePrefix("|").removeSuffix("|")) != null
        }
        return parseStructuredDomainRule(trimmed).isNotEmpty() || parseHostsOrPlainDomains(trimmed).isNotEmpty()
    }

    private fun mapDnsTypeToken(token: String): Int? {
        return RuleModifierSupport.mapDnsTypeToken(token)
    }

    private fun normalizeDnsTypes(dnsTypes: Set<Int>?): Set<Int>? {
        return RuleModifierSupport.normalizeDnsTypes(dnsTypes)
    }

    private fun mergeDnsTypes(existing: Set<Int>?, incoming: Set<Int>?): Set<Int>? {
        return RuleModifierSupport.mergeDnsTypes(existing, incoming)
    }

    private fun subtractDnsTypeScope(rule: BlockRule, removed: Set<Int>?, removedExcluded: Set<Int>?): BlockRule? {
        val normalizedRemoved = normalizeDnsTypes(removed)
        val normalizedRemovedExcluded = normalizeDnsTypes(removedExcluded)
        val currentIncluded = normalizeDnsTypes(rule.dnsTypes)
        val currentExcluded = normalizeDnsTypes(rule.excludedDnsTypes)
        if (normalizedRemoved == null && normalizedRemovedExcluded == null) return null
        if (currentIncluded == null && currentExcluded == null) return null
        val remainedIncluded = currentIncluded?.minus(normalizedRemoved.orEmpty())?.toSortedSet()
        val remainedExcluded = currentExcluded?.minus(normalizedRemovedExcluded.orEmpty())?.toSortedSet()
        if (remainedIncluded.isNullOrEmpty() && remainedExcluded.isNullOrEmpty()) return null
        return copyBlockRule(
            rule,
            dnsTypes = normalizeDnsTypes(remainedIncluded),
            excludedDnsTypes = normalizeDnsTypes(remainedExcluded)
        )
    }

    private fun buildParsedRuleKey(
        domain: String,
        dnsTypes: Set<Int>?,
        excludedDnsTypes: Set<Int>?,
        badfilter: Boolean,
        firstParty: Boolean = false,
        important: Boolean = false,
        pathPattern: String? = null,
        ipCidr: String? = null,
        regexPattern: String? = null,
        cosmeticSelector: String? = null,
        removeParams: Set<String> = emptySet(),
        removeParamRegexes: Set<String> = emptySet(),
        removeRequestHeaders: Set<String> = emptySet(),
        setRequestHeaders: Set<String> = emptySet(),
        replaceRules: Set<String> = emptySet(),
        cspValue: String? = null,
        jsInjectRules: Set<String> = emptySet(),
        keywordPattern: String? = null,
        domainConstraints: Set<String>? = emptySet(),
        excludedDomainConstraints: Set<String> = emptySet(),
        requestTypes: Set<String> = emptySet(),
        appPackages: Set<String> = emptySet(),
        destinationPorts: Set<Int> = emptySet(),
        sourcePorts: Set<Int> = emptySet(),
        denyallow: Set<String> = emptySet(),
        remoteSourceId: String? = null,
        cosmeticException: Boolean = false
    ): String {
        val dnsKey = normalizeDnsTypes(dnsTypes)?.joinToString("|") ?: "*"
        val excludedDnsKey = normalizeDnsTypes(excludedDnsTypes)?.joinToString("|") ?: "-"
        val removeParamKey = removeParams.toSortedSet().joinToString("|")
        val removeParamRegexKey = removeParamRegexes.toSortedSet().joinToString("|")
        val removeRequestHeaderKey = removeRequestHeaders.toSortedSet().joinToString("|")
        val setRequestHeaderKey = setRequestHeaders.toSortedSet().joinToString("|")
        val replaceRuleKey = replaceRules.toSortedSet().joinToString("|")
        val jsInjectKey = jsInjectRules.toSortedSet().joinToString("|")
        val domainConstraintKey = (domainConstraints ?: emptySet()).toSortedSet().joinToString("|")
        val excludedDomainConstraintKey = excludedDomainConstraints.toSortedSet().joinToString("|")
        val requestTypeKey = requestTypes.toSortedSet().joinToString("|")
        val appPackageKey = appPackages.toSortedSet().joinToString("|")
        val destinationPortKey = destinationPorts.toSortedSet().joinToString("|")
        val sourcePortKey = sourcePorts.toSortedSet().joinToString("|")
        val denyallowKey = denyallow.toSortedSet().joinToString("|")
        return listOf(
            domain,
            dnsKey,
            excludedDnsKey,
            badfilter.toString(),
            "1p:$firstParty",
            "important:$important",
            pathPattern.orEmpty(),
            ipCidr.orEmpty(),
            regexPattern.orEmpty(),
            cosmeticSelector.orEmpty(),
            "cx:${cosmeticException}",
            removeParamKey,
            "removeparam-regex:$removeParamRegexKey",
            "removeheader:$removeRequestHeaderKey",
            "header:$setRequestHeaderKey",
            "replace:$replaceRuleKey",
            cspValue.orEmpty(),
            "jsinject:$jsInjectKey",
            "kw:${keywordPattern.orEmpty()}",
            "domains:$domainConstraintKey",
            "excluded-domains:$excludedDomainConstraintKey",
            "types:$requestTypeKey",
            "apps:$appPackageKey",
            "dports:$destinationPortKey",
            "sports:$sourcePortKey",
            "deny:$denyallowKey",
            "remote:${remoteSourceId.orEmpty()}"
        ).joinToString("#")
    }

    private fun sanitizeDomain(raw: String): String? {
        val value = raw.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('^')
            .substringBefore(':')
            .trim('.')
            .trim()
        if (value.isBlank() || !value.contains('.')) return null
        if (!value.matches(domainValidationRegex)) return null
        return value
    }

    private fun normalizeSuspiciousSampleDomain(raw: String): String? {
        return RuleSuspiciousSampleSupport.normalizeSuspiciousSampleDomain(
            raw = raw,
            suspiciousSampleDecodeMaxLength = SUSPICIOUS_SAMPLE_DECODE_MAX_LENGTH,
            suspiciousSampleMaxDecodeRounds = SUSPICIOUS_SAMPLE_MAX_DECODE_ROUNDS,
            sanitizeDomain = ::sanitizeDomain,
            normalizeDomainToken = ::normalizeDomainToken,
            domainExtractRegex = domainExtractRegex,
            htmlNumericEntityRegex = htmlNumericEntityRegex,
            unicodeEscapeRegex = unicodeEscapeRegex,
            looksLikeWhitelistedRootAdSubdomain = ::looksLikeWhitelistedRootAdSubdomain,
            looksLikeAdSdkInfraDomain = { domain -> looksLikeAdSdkInfraDomain(domain) },
            looksLikePushRecommendationAdDomain = ::looksLikePushRecommendationAdDomain,
            hasAggressiveNovelAdSignal = ::hasAggressiveNovelAdSignal,
            looksLikeAdDomain = ::looksLikeAdDomain,
            isLowValueSuspiciousSampleDomain = ::isLowValueSuspiciousSampleDomain
        )
    }

    private fun sanitizeIpLiteral(raw: String): String? {
        val value = raw.trim().substringBefore('/').trim()
        return runCatching { InetAddress.getByName(value).hostAddress }.getOrNull()
    }

    private fun sanitizeIpCidr(raw: String): String? {
        val value = raw.trim()
        val slashIndex = value.indexOf('/')
        if (slashIndex <= 0 || slashIndex >= value.length - 1) return null
        val ip = sanitizeIpLiteral(value.substring(0, slashIndex)) ?: return null
        val prefixLength = value.substring(slashIndex + 1).toIntOrNull() ?: return null
        val byteSize = runCatching { InetAddress.getByName(ip).address.size }.getOrNull() ?: return null
        val maxPrefix = byteSize * 8
        if (prefixLength !in 0..maxPrefix) return null
        return "$ip/$prefixLength"
    }

    private fun matchesIpCidr(address: InetAddress, ipCidr: String?): Boolean {
        val cidr = ipCidr ?: return false
        val slashIndex = cidr.indexOf('/')
        if (slashIndex <= 0 || slashIndex >= cidr.length - 1) return false
        val network = runCatching { InetAddress.getByName(cidr.substring(0, slashIndex)) }.getOrNull() ?: return false
        val prefixLength = cidr.substring(slashIndex + 1).toIntOrNull() ?: return false
        val addressBytes = address.address
        val networkBytes = network.address
        if (addressBytes.size != networkBytes.size) return false
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until fullBytes) {
            if (addressBytes[index] != networkBytes[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (-1 shl (8 - remainingBits)) and 0xFF
        return (addressBytes[fullBytes].toInt() and mask) == (networkBytes[fullBytes].toInt() and mask)
    }

    private fun save(context: Context, rules: List<BlockRule>) {
        val startTime = System.currentTimeMillis()
        
        // 优化：大规则集跳过排序，减少 CPU 开销
        val normalizedRules = if (rules.size <= 1000) {
            rules.map { copyBlockRule(it, vendor = normalizeVendorName(it.vendor)) }.sortedBy { it.domain }
        } else {
            // 大规则集不排序，仅标准化 vendor 名称
            rules.map { copyBlockRule(it, vendor = normalizeVendorName(it.vendor)) }
        }
        
        val serializeStart = System.currentTimeMillis()
        writeRulesFile(context, normalizedRules)
        val serializeTime = System.currentTimeMillis() - serializeStart
        
        updateRuleCache(normalizedRules)
        synchronized(dnsBlockDecisionLock) {
            dnsBlockDecisionCache.clear()
        }
        val totalTime = System.currentTimeMillis() - startTime
        LogRepository.append(context, "RuleRepository.save: rules=${normalizedRules.size} serializeTime=${serializeTime}ms totalTime=${totalTime}ms")
    }

    private fun rulesFile(context: Context): File {
        return File(context.filesDir, RULES_FILE_NAME)
    }

    private fun readRulesJson(context: Context, prefs: android.content.SharedPreferences): String {
        val file = rulesFile(context)
        if (file.exists()) {
            return file.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val legacyJson = prefs.getString(KEY_RULES, "[]") ?: "[]"
        if (legacyJson != "[]") {
            runCatching {
                file.bufferedWriter(Charsets.UTF_8).use { it.write(legacyJson) }
                prefs.edit().remove(KEY_RULES).apply()
                LogRepository.append(context, "规则存储已迁移到文件：${file.name}")
            }
        } else {
            val seedRules = buildBuiltInAdSeedRules()
            writeRulesFile(context, seedRules)
            LogRepository.append(context, "Initialized built-in ad seed rules: count=${seedRules.size}")
            return gson.toJson(seedRules)
        }
        return legacyJson
    }

    private fun buildBuiltInAdSeedRules(): List<BlockRule> {
        return geositeAdSeedDomains.mapNotNull { rawDomain ->
            sanitizeDomain(rawDomain)?.let { domain ->
                BlockRule(
                    id = "builtin-ad-seed-$domain",
                    domain = domain,
                    vendor = GENERIC_AD_VENDOR,
                    source = RuleSource.IMPORTED,
                    remoteSourceId = BUILTIN_AD_SEED_SOURCE_ID
                )
            }
        }.distinctBy { it.domain }
    }

    private fun writeRulesJson(context: Context, json: String, ruleCount: Int) {
        val file = rulesFile(context)
        val tempFile = File(context.filesDir, "$RULES_FILE_NAME.tmp")
        tempFile.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        if (!tempFile.renameTo(file)) {
            file.writeText(json, Charsets.UTF_8)
            tempFile.delete()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RULES)
            .putInt(KEY_RULE_COUNT, ruleCount)
            .apply()
    }

    private fun writeRulesFile(context: Context, rules: List<BlockRule>) {
        val file = rulesFile(context)
        val tempFile = File(context.filesDir, "$RULES_FILE_NAME.tmp")
        tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            JsonWriter(writer).use { jsonWriter ->
                jsonWriter.beginArray()
                rules.forEach { rule -> gson.toJson(rule, BlockRule::class.java, jsonWriter) }
                jsonWriter.endArray()
            }
        }
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RULES)
            .putInt(KEY_RULE_COUNT, rules.size)
            .apply()
    }

    private fun clearCaches() {
        cachedRules = null
        cachedRuleCount = null
        cachedBlockedDomains = null
        cachedRuleMap = null
        cachedRegexRules = null
        cachedCosmeticRules = null
        cachedIpCidrRules = null
        cachedPortOnlyRules = null
        cachedKeywordRules = null
        cachedRuleInventory = null
        cachedCompiledRegexRules = emptyMap()
        cachedWhitelistHits.clear()
    }

    private fun readCustomVendorMap(context: Context): Map<String, String> {
        cachedCustomVendors?.let { return it }
        synchronized(cacheLock) {
            cachedCustomVendors?.let { return it }
            val type = object : TypeToken<Map<String, String>>() {}.type
            val map = gson.fromJson<Map<String, String>>(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_VENDORS, "{}"),
                type
            ) ?: emptyMap()
            cachedCustomVendors = map
            return map
        }
    }

    private fun saveCustomVendorMap(context: Context, map: Map<String, String>) {
        val normalizedMap = map.mapValues { normalizeVendorName(it.value) }.toSortedMap()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_VENDORS, gson.toJson(normalizedMap))
            .apply()
        cachedCustomVendors = normalizedMap
    }

    private fun readUnknownVendorSamples(context: Context): Map<String, SuspiciousDomainRecord> {
        cachedUnknownVendorSamples?.let { return it }
        val prefsValue = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNKNOWN_VENDOR_SAMPLES, "{}") ?: "{}"
        runCatching {
            val type = object : TypeToken<Map<String, SuspiciousDomainRecord>>() {}.type
            gson.fromJson<Map<String, SuspiciousDomainRecord>>(prefsValue, type)
        }.getOrNull()?.let { parsed ->
            return parsed.filterValues { it.count > 0 }.also { cachedUnknownVendorSamples = it }
        }
        val legacyType = object : TypeToken<Map<String, Int>>() {}.type
        val legacy = gson.fromJson<Map<String, Int>>(prefsValue, legacyType) ?: emptyMap()
        val migrated = legacy.mapValues { SuspiciousDomainRecord(count = it.value, lastSeenAt = 0L) }
        if (migrated.isNotEmpty()) {
            saveUnknownVendorSamples(context, migrated, force = true)
        }
        cachedUnknownVendorSamples = migrated
        return migrated
    }

    private fun saveUnknownVendorSamples(context: Context, samples: Map<String, SuspiciousDomainRecord>, force: Boolean = false) {
        cachedUnknownVendorSamples = samples
        val now = System.currentTimeMillis()
        if (!force && now - lastUnknownVendorSamplesPersistAt < SUSPICIOUS_SAMPLE_PERSIST_DEBOUNCE_MILLIS) {
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UNKNOWN_VENDOR_SAMPLES, gson.toJson(samples))
            .apply()
        lastUnknownVendorSamplesPersistAt = now
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "未知"
        return timeFormatter.format(Date(timestamp))
    }

    private fun normalizeSampleAppName(appName: String?): String {
        return RuleSuspiciousSampleSupport.normalizeSampleAppName(appName, lineBreakRegex)
    }

    private fun escapeCsvField(value: String): String {
        if (!value.contains(',') && !value.contains('"') && !value.contains('\n')) return value
        return buildString {
            append('"')
            value.forEach { ch ->
                if (ch == '"') append("\"\"") else append(ch)
            }
            append('"')
        }
    }

    private fun normalizeVendorName(vendor: String): String {
        return RuleVendorSupport.normalizeVendorName(
            vendor = vendor,
            defaultVendor = DEFAULT_VENDOR,
            vendorAliases = vendorAliases
        )
    }

    private fun getBlockedDomainSet(context: Context): Set<String> {
        cachedBlockedDomains?.let { return it }
        return getRules(context).mapTo(linkedSetOf(), BlockRule::domain)
    }

    private fun getRuleMap(context: Context): Map<String, List<BlockRule>> {
        cachedRuleMap?.let { return it }
        synchronized(cacheLock) {
            cachedRuleMap?.let { return it }
            getRules(context)
            return cachedRuleMap ?: emptyMap()
        }
    }

    private fun getRegexRules(context: Context): List<BlockRule> {
        cachedRegexRules?.let { return it }
        synchronized(cacheLock) {
            cachedRegexRules?.let { return it }
            getRules(context)
            return cachedRegexRules ?: emptyList()
        }
    }

    private fun getCosmeticRules(context: Context): List<BlockRule> {
        cachedCosmeticRules?.let { return it }
        synchronized(cacheLock) {
            cachedCosmeticRules?.let { return it }
            getRules(context)
            return cachedCosmeticRules ?: emptyList()
        }
    }

    private fun getKeywordRules(context: Context): List<BlockRule> {
        cachedKeywordRules?.let { return it }
        synchronized(cacheLock) {
            cachedKeywordRules?.let { return it }
            getRules(context)
            return cachedKeywordRules ?: emptyList()
        }
    }

    private fun buildDomainCandidates(domain: String): Sequence<String> = sequence {
        yield(domain)
        var index = domain.indexOf('.')
        while (index in 1 until domain.lastIndex) {
            yield(domain.substring(index + 1))
            index = domain.indexOf('.', index + 1)
        }
    }

    private fun updateRuleCache(rules: List<BlockRule>) {
        cachedRules = rules
        cachedBlockedDomains = rules.filterNot { it.exceptionRule }.mapTo(linkedSetOf(), BlockRule::domain)
        cachedRuleMap = rules.groupBy { it.domain }
        cachedRegexRules = rules.filter { !it.regexPattern.isNullOrBlank() }
        cachedCosmeticRules = rules.filter { !it.cosmeticSelector.isNullOrBlank() }
        cachedIpCidrRules = rules.filter { !it.ipCidr.isNullOrBlank() }
        cachedPortOnlyRules = rules.filter {
            it.domain == "*" && it.ipCidr.isNullOrBlank() &&
                (it.destinationPorts.isNotEmpty() || it.sourcePorts.isNotEmpty())
        }
        cachedKeywordRules = rules.filter { !it.keywordPattern.isNullOrBlank() }
        cachedCompiledRegexRules = emptyMap()
        cachedVendorMap.clear()
        cachedRuleInventory = null
    }

    private fun ruleMatches(
        rule: BlockRule,
        qType: Int?,
        appName: String? = null,
        host: String? = null,
        requestDomain: String? = null,
        requestType: String? = null
    ): Boolean {
        if (!matchesAppPackage(rule.appPackages, appName)) return false
        if (!matchesRequestContext(rule, host, requestDomain)) return false
        if (!matchesRequestType(rule.requestTypes, requestType)) return false
        if (qType == null) return true
        val dnsTypes = normalizeDnsTypes(rule.dnsTypes)
        val excludedDnsTypes = normalizeDnsTypes(rule.excludedDnsTypes)
        if (excludedDnsTypes != null && excludedDnsTypes.contains(qType)) return false
        return dnsTypes == null || dnsTypes.contains(qType)
    }

    private fun matchesRequestType(ruleRequestTypes: Set<String>, requestType: String?): Boolean {
        if (ruleRequestTypes.isEmpty()) return true
        val normalized = requestType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return normalized in ruleRequestTypes
    }

    private fun matchesRequestContext(rule: BlockRule, host: String?, requestDomain: String?): Boolean {
        val normalizedHost = host?.let(::sanitizeDomain)
        val normalizedRequestDomain = requestDomain?.let(::sanitizeDomain)
        if (rule.denyallow.isNotEmpty() && normalizedHost != null) {
            if (rule.denyallow.any { denied -> normalizedHost == denied || normalizedHost.endsWith(".$denied") }) {
                return false
            }
        }
        val contextDomain = normalizedRequestDomain ?: normalizedHost
        if (rule.excludedDomainConstraints.isNotEmpty() && contextDomain != null) {
            if (rule.excludedDomainConstraints.any { excluded -> contextDomain == excluded || contextDomain.endsWith(".$excluded") }) {
                return false
            }
        }
        if (rule.domainConstraints?.isNotEmpty() == true) {
            val scopedDomain = contextDomain ?: return false
            val allowed = rule.domainConstraints.any { allowedDomain ->
                scopedDomain == allowedDomain || scopedDomain.endsWith(".$allowedDomain")
            }
            if (!allowed) return false
        }
        if (rule.thirdParty) {
            if (normalizedHost == null || normalizedRequestDomain == null) return false
            val hostRoot = secondLevelDomain(normalizedHost) ?: normalizedHost
            val requestRoot = secondLevelDomain(normalizedRequestDomain) ?: normalizedRequestDomain
            val sameSite = normalizedHost == normalizedRequestDomain ||
                normalizedHost.endsWith(".$normalizedRequestDomain") ||
                normalizedRequestDomain.endsWith(".$normalizedHost") ||
                hostRoot == requestRoot
            if (sameSite) return false
        }
        if (rule.firstParty) {
            if (normalizedHost == null || normalizedRequestDomain == null) return false
            val hostRoot = secondLevelDomain(normalizedHost) ?: normalizedHost
            val requestRoot = secondLevelDomain(normalizedRequestDomain) ?: normalizedRequestDomain
            val sameSite = normalizedHost == normalizedRequestDomain ||
                normalizedHost.endsWith(".$normalizedRequestDomain") ||
                normalizedRequestDomain.endsWith(".$normalizedHost") ||
                hostRoot == requestRoot
            if (!sameSite) return false
        }
        return true
    }

    private fun sanitizeAppPackageToken(value: String): String? {
        val normalized = value.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace(" ", "")
            .lowercase()
        if (normalized.isBlank()) return null
        if (!normalized.contains('.')) return null
        if (!normalized.matches(Regex("[a-z0-9._-]+"))) return null
        return normalized
    }

    private fun secondLevelDomain(domain: String): String? {
        val parts = domain.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts.takeLast(2).joinToString(".")
    }

    private fun matchesRegexRule(rule: BlockRule, value: String): Boolean {
        val result = SafeRegexRuleMatcher.matches(
            pattern = rule.regexPattern,
            value = value,
            cacheState = SafeRegexRuleMatcher.CacheState(
                compiledPatterns = cachedCompiledRegexRules,
                invalidPatterns = cachedInvalidRegexRules
            )
        )
        cachedCompiledRegexRules = result.cacheState.compiledPatterns
        cachedInvalidRegexRules = result.cacheState.invalidPatterns
        return result.matched
    }

    private fun buildRuleIdentityKey(rule: BlockRule): String {
        return buildParsedRuleKey(
            domain = rule.domain,
            dnsTypes = rule.dnsTypes,
            excludedDnsTypes = rule.excludedDnsTypes,
            badfilter = false,
            firstParty = rule.firstParty,
            important = rule.important,
            pathPattern = rule.pathPattern,
            ipCidr = rule.ipCidr,
            regexPattern = rule.regexPattern,
            cosmeticSelector = rule.cosmeticSelector,
            removeParams = rule.removeParams,
            removeParamRegexes = rule.removeParamRegexes,
            removeRequestHeaders = rule.removeRequestHeaders,
            setRequestHeaders = rule.setRequestHeaders,
            replaceRules = rule.replaceRules,
            cspValue = rule.cspValue,
            jsInjectRules = rule.jsInjectRules,
            keywordPattern = rule.keywordPattern,
            domainConstraints = rule.domainConstraints.orEmpty(),
            excludedDomainConstraints = rule.excludedDomainConstraints,
            requestTypes = rule.requestTypes,
            appPackages = rule.appPackages,
            destinationPorts = rule.destinationPorts,
            sourcePorts = rule.sourcePorts,
            denyallow = rule.denyallow,
            remoteSourceId = rule.remoteSourceId,
            cosmeticException = rule.cosmeticException
        )
    }

    private fun explainRemoteSourceNonAdCandidate(context: Context, rule: BlockRule): RemoteRuleRemovalCandidate? {
        if (!rule.regexPattern.isNullOrBlank()) return null
        if (!rule.cosmeticSelector.isNullOrBlank()) return null
        if (!rule.keywordPattern.isNullOrBlank()) return null
        if (rule.pathPattern != null || rule.ipCidr != null || rule.cspValue != null) return null
        if (rule.appPackages.isNotEmpty() || rule.denyallow.isNotEmpty()) return null
        val reasons = mutableListOf<String>()
        val vendor = if (rule.vendor == DEFAULT_VENDOR) classifyVendor(context, rule.domain) else normalizeVendorName(rule.vendor)
        if (vendor == DEFAULT_VENDOR) reasons += "未识别到明确广告厂商"
        if (!looksLikeAdDomain(rule.domain)) reasons += "域名特征不像广告域名"
        if (!looksLikeBypassProtectionDomain(rule.domain)) reasons += "不属于加密 DNS 反绕过域名"
        if (!isProtectedNovelAppDomain(rule.domain)) reasons += "不属于小说保护广告域名"
        if (!isNovelContentDomain(rule.domain)) reasons += "不属于小说内容域名"
        val shouldRemove = vendor == DEFAULT_VENDOR &&
            !looksLikeAdDomain(rule.domain) &&
            !looksLikeBypassProtectionDomain(rule.domain) &&
            !isProtectedNovelAppDomain(rule.domain) &&
            !isNovelContentDomain(rule.domain)
        if (!shouldRemove) return null
        val sourceLabel = when {
            !rule.remoteSourceId.isNullOrBlank() -> getRemoteRuleSourceName(context, rule.remoteSourceId) ?: "远程规则源"
            else -> rule.source.label
        }
        val businessCategory = classifyBusinessCategory(rule.domain)
        return RemoteRuleRemovalCandidate(
            rule = rule,
            reasons = reasons.distinct(),
            vendor = vendor,
            sourceLabel = sourceLabel,
            riskLevel = CandidateRiskLevel.MEDIUM,
            businessCategory = businessCategory
        )
    }

    private fun explainImpactNormalNetworkCandidate(context: Context, rule: BlockRule): RemoteRuleRemovalCandidate? {
        if (!rule.regexPattern.isNullOrBlank()) return null
        if (!rule.cosmeticSelector.isNullOrBlank()) return null
        val lower = rule.domain.lowercase()
        val reasons = mutableListOf<String>()
        val vendor = if (rule.vendor == DEFAULT_VENDOR) classifyVendor(context, rule.domain) else normalizeVendorName(rule.vendor)
        var riskLevel = CandidateRiskLevel.MEDIUM
        when {
            lower.contains("qq") || lower.contains("weixin") || lower.contains("wechat") -> {
                reasons += "此域名影响微信、QQ 或企业微信的消息收发、登录或文件传输功能"
                riskLevel = CandidateRiskLevel.HIGH
            }
            lower.contains("music") || lower.contains("kugou") || lower.contains("kuwo") || lower.contains("spotify") || lower.contains("y.qq") -> {
                reasons += "此域名影响音乐应用播放、音频拉流或歌曲加载功能"
                riskLevel = CandidateRiskLevel.HIGH
            }
            lower.contains("alipay") || lower.contains("tenpay") || lower.contains("pay") || lower.contains("bank") -> {
                reasons += "此域名影响支付、鉴权或订单确认功能"
                riskLevel = CandidateRiskLevel.HIGH
            }
            lower.contains("game") || lower.contains("gamedl") || lower.contains("mihoyo") || lower.contains("hoyoverse") || lower.contains("steam") -> {
                reasons += "此域名影响游戏登录、资源下载或联机功能"
                riskLevel = CandidateRiskLevel.HIGH
            }
            vendor == DEFAULT_VENDOR && !looksLikeAdDomain(rule.domain) -> {
                reasons += "此域名未表现出明确广告特征，更像正常业务域名，可能影响应用联网"
                riskLevel = CandidateRiskLevel.MEDIUM
            }
        }
        if (reasons.isEmpty()) return null
        if (!looksLikeBypassProtectionDomain(rule.domain)) {
            reasons += "它不属于加密 DNS 反绕过目标，更适合保留正常联网能力"
        }
        val sourceLabel = when {
            !rule.remoteSourceId.isNullOrBlank() -> getRemoteRuleSourceName(context, rule.remoteSourceId) ?: "远程规则源"
            else -> rule.source.label
        }
        val businessCategory = classifyBusinessCategory(rule.domain)
        return RemoteRuleRemovalCandidate(
            rule = rule,
            reasons = reasons.distinct(),
            vendor = vendor,
            sourceLabel = sourceLabel,
            riskLevel = riskLevel,
            businessCategory = businessCategory
        )
    }

    data class RequestRewriteDirectives(
        val removeParams: Set<String> = emptySet(),
        val removeParamRegexes: Set<String> = emptySet(),
        val removeRequestHeaders: Set<String> = emptySet(),
        val setRequestHeaders: Set<String> = emptySet(),
        val replaceRules: Set<String> = emptySet(),
        val cspValue: String? = null,
        val redirectResource: String? = null,
        val jsInjectRules: Set<String> = emptySet(),
        val cosmeticSelectors: List<String> = emptyList()
    )

    data class RemoteRuleRemovalCandidate(
        val rule: BlockRule,
        val reasons: List<String>,
        val vendor: String,
        val sourceLabel: String,
        val riskLevel: CandidateRiskLevel,
        val businessCategory: String
    )

    enum class CandidateRiskLevel(val label: String) {
        HIGH("高风险"),
        MEDIUM("中风险")
    }

    private fun classifyBusinessCategory(domain: String): String {
        val lower = domain.lowercase()
        return when {
            lower.contains("qq") || lower.contains("weixin") || lower.contains("wechat") -> "社交通信"
            lower.contains("alipay") || lower.contains("tenpay") || lower.contains("pay") || lower.contains("bank") -> "支付金融"
            lower.contains("game") || lower.contains("gamedl") || lower.contains("mihoyo") || lower.contains("hoyoverse") || lower.contains("steam") -> "游戏服务"
            lower.contains("music") || lower.contains("kugou") || lower.contains("kuwo") || lower.contains("spotify") || lower.contains("y.qq") -> "音乐音频"
            lower.contains("video") || lower.contains("vod") || lower.contains("cdn") || lower.contains("media") -> "内容分发"
            else -> "通用业务"
        }
    }

    private fun looksLikeSuspiciousPath(path: String): Boolean {
        return RuleSuspiciousSampleSupport.looksLikeSuspiciousPath(path)
    }

    private fun pathMatchesPattern(path: String, pathPattern: String): Boolean {
        val normalizedPath = path.lowercase()
        val normalizedPattern = pathPattern.lowercase()
        if (normalizedPattern.isBlank()) return false
        val cleanedPath = normalizedPath.substringBefore('?').substringBefore('#')
        val cleanedPattern = normalizedPattern.substringBefore('?').substringBefore('#')
        return when {
            cleanedPattern.contains("*") -> {
                val parts = cleanedPattern.split('*').filter { it.isNotBlank() }
                if (parts.isEmpty()) return true
                var searchStart = 0
                parts.forEachIndexed { index, part ->
                    val foundAt = cleanedPath.indexOf(part, startIndex = searchStart)
                    if (foundAt < 0) return false
                    if (index == 0 && !cleanedPattern.startsWith("*") && foundAt != 0) return false
                    searchStart = foundAt + part.length
                }
                if (!cleanedPattern.endsWith("*") && parts.isNotEmpty()) {
                    return cleanedPath.endsWith(parts.last())
                }
                true
            }
            cleanedPattern.endsWith("^") -> {
                val prefix = cleanedPattern.removeSuffix("^")
                cleanedPath.startsWith(prefix)
            }
            cleanedPattern.startsWith("/") -> cleanedPath.startsWith(cleanedPattern)
            else -> cleanedPath.contains(cleanedPattern)
        }
    }

    private fun hasAggressiveNovelAdSignal(domain: String): Boolean {
        return RuleProtectionSupport.hasAggressiveNovelAdSignal(domain)
    }

    private fun matchesAppPackage(appPackages: Set<String>, appName: String?): Boolean {
        if (appPackages.isEmpty()) return true
        val packageName = extractPackageName(appName) ?: return false
        return appPackages.contains(packageName)
    }

    private fun matchesPortScope(ports: Set<Int>, actualPort: Int?): Boolean {
        if (ports.isEmpty()) return true
        actualPort ?: return false
        return ports.contains(actualPort)
    }

    private fun extractPackageName(appName: String?): String? {
        if (appName == null) return null
        val match = parensRegex.find(appName)
        if (match != null) {
            val pkg = match.groupValues[1].trim()
            if (pkg.contains('.')) return pkg
        }
        if (appName.contains('.')) return appName.trim()
        return null
    }

    private fun applyCosmeticExceptionRules(
        rules: List<BlockRule>,
        exceptionRules: List<ParsedRule>
    ): List<BlockRule> {
        if (rules.isEmpty() || exceptionRules.isEmpty()) return rules
        val cosmeticExceptions = exceptionRules.filter { !it.cosmeticSelector.isNullOrBlank() }
        if (cosmeticExceptions.isEmpty()) return rules

        val excludedKeys = cosmeticExceptions
            .mapNotNull { exceptionRule ->
                val selector = exceptionRule.cosmeticSelector?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                buildCosmeticRuleScopeKeys(exceptionRule.domain)
                    .map { scope -> "$scope|$selector" }
            }
            .flatten()
            .toSet()
        if (excludedKeys.isEmpty()) return rules

        return rules.filterNot { rule ->
            if (rule.cosmeticException) return@filterNot false
            val selector = rule.cosmeticSelector?.trim()?.takeIf { it.isNotEmpty() } ?: return@filterNot false
            buildCosmeticRuleScopeKeys(rule.domain).any { scope ->
                excludedKeys.contains("$scope|$selector")
            }
        }
    }

    private fun buildCosmeticRuleScopeKeys(domain: String): List<String> {
        if (domain == COSMETIC_RULE_DOMAIN) return listOf(COSMETIC_RULE_DOMAIN)
        return buildDomainCandidates(domain).toList()
    }

    private fun mergeRuleTypeScopes(existing: BlockRule, incoming: ParsedRule): BlockRule {
        val mergedDenyallow = mergeRuleScopes(existing.denyallow, incoming.denyallow)
        val mergedAppPackages = mergeRuleScopes(existing.appPackages, incoming.appPackages)
        val mergedKeyword = incoming.keywordPattern ?: existing.keywordPattern
        val mergedDestinationPorts = (existing.destinationPorts + incoming.destinationPorts).toSet()
        val mergedSourcePorts = (existing.sourcePorts + incoming.sourcePorts).toSet()
        return copyBlockRule(
            existing,
            dnsTypes = mergeDnsTypes(existing.dnsTypes, incoming.dnsTypes),
            excludedDnsTypes = mergeDnsTypes(existing.excludedDnsTypes, incoming.excludedDnsTypes),
            thirdParty = existing.thirdParty || incoming.thirdParty,
            firstParty = existing.firstParty || incoming.firstParty,
            important = existing.important || incoming.important,
            redirect = existing.redirect || incoming.redirect,
            domainConstraints = (existing.domainConstraints.orEmpty() + incoming.domainConstraints).toSet(),
            excludedDomainConstraints = (existing.excludedDomainConstraints + incoming.excludedDomainConstraints).toSet(),
            denyallow = mergedDenyallow,
            urlblock = existing.urlblock || incoming.urlblock,
            requestTypes = mergeRequestTypeScopes(existing.requestTypes, incoming.requestTypes),
            appPackages = mergedAppPackages,
            destinationPorts = mergedDestinationPorts,
            sourcePorts = mergedSourcePorts,
            keywordPattern = mergedKeyword,
            pathPattern = incoming.pathPattern ?: existing.pathPattern,
            ipCidr = incoming.ipCidr ?: existing.ipCidr,
            regexPattern = incoming.regexPattern ?: existing.regexPattern,
            cosmeticSelector = incoming.cosmeticSelector ?: existing.cosmeticSelector,
            cosmeticException = incoming.isException || existing.cosmeticException,
            exceptionRule = incoming.isException || existing.exceptionRule,
            removeParams = (existing.removeParams + incoming.removeParams).toSet(),
            removeParamRegexes = (existing.removeParamRegexes + incoming.removeParamRegexes).toSet(),
            removeRequestHeaders = (existing.removeRequestHeaders + incoming.removeRequestHeaders).toSet(),
            setRequestHeaders = (existing.setRequestHeaders + incoming.setRequestHeaders).toSet(),
            replaceRules = (existing.replaceRules + incoming.replaceRules).toSet(),
            cspValue = incoming.cspValue ?: existing.cspValue,
            redirectResource = incoming.redirectResource ?: existing.redirectResource,
            jsInjectRules = (existing.jsInjectRules + incoming.jsInjectRules).toSet()
        )
    }

    private fun copyBlockRule(
        rule: BlockRule,
        id: String = rule.id,
        domain: String = rule.domain,
        vendor: String = rule.vendor,
        source: RuleSource = rule.source,
        dnsTypes: Set<Int>? = rule.dnsTypes,
        excludedDnsTypes: Set<Int>? = rule.excludedDnsTypes,
        thirdParty: Boolean = rule.thirdParty,
        firstParty: Boolean = rule.firstParty,
        important: Boolean = rule.important,
        redirect: Boolean = rule.redirect,
        domainConstraints: Set<String>? = rule.domainConstraints,
        excludedDomainConstraints: Set<String>? = rule.excludedDomainConstraints,
        denyallow: Set<String>? = rule.denyallow,
        urlblock: Boolean = rule.urlblock,
        requestTypes: Set<String>? = rule.requestTypes,
        appPackages: Set<String>? = rule.appPackages,
        destinationPorts: Set<Int>? = rule.destinationPorts,
        sourcePorts: Set<Int>? = rule.sourcePorts,
        keywordPattern: String? = rule.keywordPattern,
        pathPattern: String? = rule.pathPattern,
        ipCidr: String? = rule.ipCidr,
        regexPattern: String? = rule.regexPattern,
        cosmeticSelector: String? = rule.cosmeticSelector,
        cosmeticException: Boolean = rule.cosmeticException,
        exceptionRule: Boolean = rule.exceptionRule,
        removeParams: Set<String>? = rule.removeParams,
        removeParamRegexes: Set<String>? = rule.removeParamRegexes,
        removeRequestHeaders: Set<String>? = rule.removeRequestHeaders,
        setRequestHeaders: Set<String>? = rule.setRequestHeaders,
        replaceRules: Set<String>? = rule.replaceRules,
        cspValue: String? = rule.cspValue,
        redirectResource: String? = rule.redirectResource,
        jsInjectRules: Set<String>? = rule.jsInjectRules,
        remoteSourceId: String? = rule.remoteSourceId
    ): BlockRule {
        return BlockRule(
            id = id,
            domain = domain,
            vendor = vendor,
            source = source,
            dnsTypes = dnsTypes,
            excludedDnsTypes = excludedDnsTypes,
            thirdParty = thirdParty,
            firstParty = firstParty,
            important = important,
            redirect = redirect,
            domainConstraints = domainConstraints,
            excludedDomainConstraints = excludedDomainConstraints.orEmpty(),
            denyallow = denyallow.orEmpty(),
            urlblock = urlblock,
            requestTypes = requestTypes.orEmpty(),
            appPackages = appPackages.orEmpty(),
            destinationPorts = destinationPorts.orEmpty(),
            sourcePorts = sourcePorts.orEmpty(),
            keywordPattern = keywordPattern,
            pathPattern = pathPattern,
            ipCidr = ipCidr,
            regexPattern = regexPattern,
            cosmeticSelector = cosmeticSelector,
            cosmeticException = cosmeticException,
            exceptionRule = exceptionRule,
            removeParams = removeParams.orEmpty(),
            removeParamRegexes = removeParamRegexes.orEmpty(),
            removeRequestHeaders = removeRequestHeaders.orEmpty(),
            setRequestHeaders = setRequestHeaders.orEmpty(),
            replaceRules = replaceRules.orEmpty(),
            cspValue = cspValue,
            redirectResource = redirectResource,
            jsInjectRules = jsInjectRules.orEmpty(),
            remoteSourceId = remoteSourceId
        )
    }

    private fun isLegacyBuiltInRemoteRuleSource(source: RemoteRuleSourceConfig): Boolean {
        return source.id in setOf(
            "awavenue-hosts",
            "adhosts-master",
            "lingeringsound-10007",
            "anti-ad-domains",
            "adaway-hosts",
            "stevenblack-hosts",
            "jdlingyu-ad-wars"
        )
    }

    private fun sanitizeRemoteRuleSource(source: RemoteRuleSourceConfig?): RemoteRuleSourceConfig? {
        source ?: return null
        val id = source.id.trim()
        val name = source.name.trim()
        val url = source.url.trim()
        if (id.isBlank() || name.isBlank() || url.isBlank()) return null
        return source.copy(
            id = id,
            name = name,
            url = url,
            authorId = source.authorId?.trim()?.takeIf { it.isNotBlank() },
            lastError = source.lastError?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun mergeParsedRule(existing: ParsedRule?, incoming: ParsedRule): ParsedRule {
        if (existing == null) return incoming.copy(
            dnsTypes = normalizeDnsTypes(incoming.dnsTypes),
            excludedDnsTypes = normalizeDnsTypes(incoming.excludedDnsTypes)
        )
        val mergedDenyallow = mergeRuleScopes(existing.denyallow, incoming.denyallow)
        val mergedAppPackages = mergeRuleScopes(existing.appPackages, incoming.appPackages)
        val mergedKeyword = incoming.keywordPattern ?: existing.keywordPattern
        val mergedDestinationPorts = (existing.destinationPorts + incoming.destinationPorts).toSet()
        val mergedSourcePorts = (existing.sourcePorts + incoming.sourcePorts).toSet()
        return incoming.copy(
            dnsTypes = mergeDnsTypes(existing.dnsTypes, incoming.dnsTypes),
            excludedDnsTypes = mergeDnsTypes(existing.excludedDnsTypes, incoming.excludedDnsTypes),
            isException = incoming.isException || existing.isException,
            thirdParty = existing.thirdParty || incoming.thirdParty,
            firstParty = existing.firstParty || incoming.firstParty,
            redirect = existing.redirect || incoming.redirect,
            domainConstraints = (existing.domainConstraints + incoming.domainConstraints).toSet(),
            excludedDomainConstraints = (existing.excludedDomainConstraints + incoming.excludedDomainConstraints).toSet(),
            denyallow = mergedDenyallow,
            urlblock = existing.urlblock || incoming.urlblock,
            requestTypes = mergeRequestTypeScopes(existing.requestTypes, incoming.requestTypes),
            appPackages = mergedAppPackages,
            destinationPorts = mergedDestinationPorts,
            sourcePorts = mergedSourcePorts,
            keywordPattern = mergedKeyword,
            pathPattern = incoming.pathPattern ?: existing.pathPattern,
            ipCidr = incoming.ipCidr ?: existing.ipCidr,
            regexPattern = incoming.regexPattern ?: existing.regexPattern,
            cosmeticSelector = incoming.cosmeticSelector ?: existing.cosmeticSelector,
            removeParams = (existing.removeParams + incoming.removeParams).toSet(),
            removeParamRegexes = (existing.removeParamRegexes + incoming.removeParamRegexes).toSet(),
            removeRequestHeaders = (existing.removeRequestHeaders + incoming.removeRequestHeaders).toSet(),
            setRequestHeaders = (existing.setRequestHeaders + incoming.setRequestHeaders).toSet(),
            replaceRules = (existing.replaceRules + incoming.replaceRules).toSet(),
            cspValue = incoming.cspValue ?: existing.cspValue,
            redirectResource = incoming.redirectResource ?: existing.redirectResource,
            jsInjectRules = (existing.jsInjectRules + incoming.jsInjectRules).toSet()
        )
    }

    private fun <T> mergeRuleScopes(existing: Set<T>, incoming: Set<T>): Set<T> {
        if (existing.isEmpty()) return incoming
        if (incoming.isEmpty()) return existing
        return (existing + incoming).toSet()
    }

    private fun mergeRequestTypeScopes(existing: Set<String>, incoming: Set<String>): Set<String> {
        if (existing.isEmpty() || incoming.isEmpty()) return emptySet()
        return (existing + incoming).toSet()
    }

    private data class ParsedRules(
        val blockedRules: List<ParsedRule>,
        val exceptionRules: List<ParsedRule>,
        val badfilterRules: List<ParsedRule>
    )

    private data class RuleRemovalResult(
        val remaining: List<BlockRule>,
        val removedCount: Int
    )

    data class RuleAnalysisReport(
        val totalLines: Int,
        val existingRules: Int,
        val estimatedFinalRules: Int,
        val blankOrCommentLines: Int,
        val safeBlockedRules: Int,
        val safeExceptionRules: Int,
        val duplicateExistingRules: Int,
        val duplicateWithinFileRules: Int,
        val unsupportedModifierRules: Int,
        val cosmeticRules: Int,
        val regexRules: Int,
        val invalidRules: Int,
        val exceptionRemovalEstimate: Int,
        val vendorSummary: List<VendorSummary>,
        val whitelistConflictRules: Int,
        val sampleWhitelistConflictLines: List<String>,
        val sampleUnsupportedLines: List<String>,
        val sampleInvalidLines: List<String>
    ) {
        val safeRuleCount: Int
            get() = safeBlockedRules + safeExceptionRules
    }

    data class VendorSummary(
        val vendor: String,
        val count: Int
    )

    data class RuleInventory(
        val importedCount: Int,
        val manualCount: Int,
        val regexCount: Int,
        val cosmeticCount: Int,
        val keywordCount: Int
    ) {
        val totalSupportedCount: Int
            get() = importedCount + manualCount

        val totalSavedCount: Int
            get() = totalSupportedCount + regexCount + cosmeticCount
    }

    data class SuspiciousDomainSample(
        val domain: String,
        val count: Int,
        val lastSeenAt: Long,
        val lastAppName: String,
        val lastVendor: String,
        val novelHits: Int,
        val dnsHits: Int,
        val aliasHits: Int,
        val tlsSniHits: Int,
        val httpHits: Int,
        val pathHits: Int,
        val redirectHits: Int,
        val appSignalHits: Int,
        val vendorSignalHits: Int,
        val confidenceBoost: Int,
        val lastPathHint: String,
        val refererDomain: String
    )

    private data class SuspiciousDomainRecord(
        val count: Int = 0,
        val lastSeenAt: Long = 0L,
        val lastAppName: String = "",
        val lastVendor: String = "",
        val novelHits: Int = 0,
        val dnsHits: Int = 0,
        val aliasHits: Int = 0,
        val tlsSniHits: Int = 0,
        val httpHits: Int = 0,
        val pathHits: Int = 0,
        val redirectHits: Int = 0,
        val appSignalHits: Int = 0,
        val vendorSignalHits: Int = 0,
        val confidenceBoost: Int = 0,
        val lastPathHint: String = "",
        val refererDomain: String = "",
        val lastSampleAt: Long = 0L
    )

    enum class SuspiciousSignal {
        DNS_QUERY,
        DNS_ALIAS,
        TLS_SNI,
        HTTP_FLOW,
        HTTP_REDIRECT
    }

    private data class ParsedRule(
        val domain: String,
        val isException: Boolean,
        val isBadfilter: Boolean = false,
        val dnsTypes: Set<Int>? = null,
        val excludedDnsTypes: Set<Int>? = null,
        val thirdParty: Boolean = false,
        val firstParty: Boolean = false,
        val important: Boolean = false,
        val redirect: Boolean = false,
        val domainConstraints: Set<String> = emptySet(),
        val excludedDomainConstraints: Set<String> = emptySet(),
        val denyallow: Set<String> = emptySet(),
        val urlblock: Boolean = false,
        val requestTypes: Set<String> = emptySet(),
        val appPackages: Set<String> = emptySet(),
        val destinationPorts: Set<Int> = emptySet(),
        val sourcePorts: Set<Int> = emptySet(),
        val keywordPattern: String? = null,
        val pathPattern: String? = null,
        val ipCidr: String? = null,
        val regexPattern: String? = null,
        val cosmeticSelector: String? = null,
        val removeParams: Set<String> = emptySet(),
        val removeParamRegexes: Set<String> = emptySet(),
        val removeRequestHeaders: Set<String> = emptySet(),
        val setRequestHeaders: Set<String> = emptySet(),
        val replaceRules: Set<String> = emptySet(),
        val cspValue: String? = null,
        val redirectResource: String? = null,
        val jsInjectRules: Set<String> = emptySet(),
        val vendorHints: Set<String> = emptySet(),
        val isUnsupported: Boolean = false
    )

}
