package com.HanFeng.data

import android.content.Context
import com.HanFeng.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RuleSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RuleRepository {
    private const val PREFS = "rule_repo"
    private const val KEY_RULES = "rules"
    private const val KEY_CUSTOM_VENDORS = "custom_vendors"
    private const val KEY_UNKNOWN_VENDOR_SAMPLES = "unknown_vendor_samples"
    private const val KEY_BUNDLED_RULES_VERSION = "bundled_rules_version"
    private const val DEFAULT_VENDOR = "其它 (Other)"
    private const val GENERIC_AD_VENDOR = "通用广告/追踪 (Generic Ad/Tracking)"
    private const val BYPASS_PROTECTION_VENDOR = "加密 DNS 反绕过 (Encrypted DNS)"
    private const val REGEX_RULE_DOMAIN = "[Regex Rule]"
    private const val COSMETIC_RULE_DOMAIN = "[Cosmetic Rule]"
    private const val BUNDLED_RULES_VERSION = 24
    private const val SUSPICIOUS_SAMPLE_DEBOUNCE_MILLIS = 5_000L
    
    // 白名单域名 - 这些域名被拦截会导致 APP 断网
    // 策略：只保护基础服务，不保护纯广告域名
    // 包含主域名和通配符规则，防止 ||domain.com^ 这种规则导致整个域名被拦截
    private val whitelistDomains = setOf(
        // 微信小程序/支付宝 - 完全保护
        "servicewechat.com",
        "alipay.com",
        "alipay.cn",
        // 微信 DNS 服务
        "dns.weixin.qq.com.cn",
        "aedns.weixin.qq.com",
        // APP 启动核心域名（精确匹配，不做子域名通配）
        "clientservices.googleapis.com",
        "update.googleapis.com",
        "android.clients.google.com",
        "play.googleapis.com",
        "firebaseinstallations.googleapis.com",
        "app-measurement.com",
        "firebase-analytics.com",
        // 阿里系
        "alicdn.com",
        "alibaba.com",
        "taobao.com",
        "aliyun.com",
        // 网商银行/金融机构 - 完全保护
        "webank.com",
        "webankcdn.net",
        "wldservice.com",
        "constid.dingxiang-inc.com",
        // Google 服务 - 完全保护
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
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        // 隐私保护服务（误报）- 完全保护
        "ghostery.com",
        "ghostery.net",
        // 友盟统计 - 保护主域名和基础日志服务
        "umeng.com",
        "umengcloud.com",
        // 网飞猫/在线视频 - 完全保护
        "netease.com",
        "126.net",
        "127.net",
        "hdzixun.com",
        // 夸克/UC/阿里系 - 完全保护
        "uczzd.cn",
        "ucweb.com",
        "quark.cn",
        "alibaba-inc.com",
        // 豌豆荚 - 完全保护
        "wandoujia.com",
        "wdj.com",
        "wdjimg.com",
        // 百度贴吧/百度系 - 完全保护
        "baidu.com",
        "bdstatic.com",
        "tieba.com",
        "tiebaimg.com",
        "baidustatic.com",
        // 知乎 - 完全保护
        "zhihu.com",
        "zhimg.com",
        "zhihuimg.com",
        // 腾讯系 - 完全保护
        "qq.com",
        "tencent.com",
        "weixin.com",
        "wechat.com",
        "gtimg.cn",
        "qpic.cn",
        // 字节系 - 完全保护
        "snssdk.com",
        "toutiao.com",
        "iesdouyin.com",
        "amemv.com",
        "pstatp.com",
        // 通用 CDN
        "ghpym.com",
        "wscdns.com",
        "21vianet.com",
        "ksyuncdn.com",
        // 视频直播流媒体 CDN - 完全保护（防卡顿/缓冲异常）
        "douyinvod.com",
        "douyincdn.com",
        "bytegoofy.com",
        "video.qq.com",
        "qpic.cn",
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
        "letv.com",
        "lecloud.com",
        "letvcdn.com",
        "letvimg.com",
        "bilibili.com",
        "bilivideo.com",
        "biliapi.com",
        "biligame.com",
        "mcdn.bilivideo.com",
        "mgtv.com",
        "imgo.tv",
        "hitv.com",
        "hwcdn.net",
        "myqcloud.com",
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
    private val alphanumericCnRegex = Regex("[^a-z0-9\u4e00-\u9fff]")
    private val whitespaceRegex = Regex("\\s+")
    private val ipV4Regex = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val parensRegex = Regex("\\(([^)]+)\\)")
    private val splitWhitespaceRegex = Regex("[\\s,;]+")
    private val lineBreakRegex = Regex("[\\r\\n]+")
    @Volatile private var cachedRules: List<BlockRule>? = null
    @Volatile private var cachedBlockedDomains: Set<String>? = null
    @Volatile private var cachedRuleMap: Map<String, BlockRule>? = null
    @Volatile private var cachedRegexRules: List<BlockRule>? = null
    @Volatile private var cachedCosmeticRules: List<BlockRule>? = null
    @Volatile private var cachedCustomVendors: Map<String, String>? = null
    @Volatile private var cachedRuleInventory: RuleInventory? = null
    @Volatile private var cachedCompiledRegexRules: Map<String, java.util.regex.Pattern> = emptyMap()
    @Volatile private var cachedVendorMap: MutableMap<String, String> = ConcurrentHashMap()
    @Volatile private var cachedKeywordRules: List<BlockRule>? = null
    @Volatile private var cachedWhitelistHits = ConcurrentHashMap<String, Boolean>()
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
        "vungle"
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
        "七猫小说", "七猫免费小说", "qimao", "kmxs", "wtzw",
        "起点读书", "qidian", "qdreader", "yuewen",
        "qq阅读", "qqreader", "qqread", "weread",
        "书旗小说", "shuqi", "aliwx",
        "掌阅", "ireader", "zhangyue",
        "咪咕阅读", "migu", "cmread",
        "米读小说", "midu", "miduread", "lechuan",
        "纵横小说", "zongheng", "zhread",
        "17k", "17k小说", "book17k",
        "长读小说", "changdu"
    )
    private val novelAppProtectedSuffixes = setOf(
        "wtzw.com",
        "qimao.com",
        "kmxs.com",
        "fqnovel.com",
        "fanqienovel.com",
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
        "changdu.com"
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
        // 七猫小说
        "api.qimao.com",
        "webnovel.qimao.com",
        "reader-api.kmxs.com",
        // 起点读书
        "bookapi.qidian.com",
        "read.qidian.com",
        "trader.qidian.com",
        // QQ 阅读
        "book.qqreader.com",
        "reader.qq.com",
        "api.weread.qq.com",
        // 书旗小说
        "api.shuqi.com",
        "reader.aliwx.com",
        // 掌阅
        "api.ireader.com",
        "book.zhangyue.com",
        // 其他
        "api.cmread.com",
        "api.migu.com",
        "api.midu.com",
        "api.zongheng.com",
        "api.17k.com",
        "api.changdu.com"
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
        "p2-pro.a.yximgs.com"
    )
    private val fanqieProtectedAdPathKeywords = listOf(
        "/ad/", "/ads/", "/adx/", "/advert/", "/advertisement/", "/union/", "/sdk/union/",
        "/reward/", "/rewarded/", "/excitation/", "/inspire/", "/banner/", "/feed_ad/",
        "/bottom_banner/", "/floating_banner/", "/common/banner/", "/native/banner/",
        "/draw_ad/", "/ad_plan/", "/ad_request/", "/ad_style/", "/ad_config/", "/ad_info/",
        "/launch/", "/startup/", "/open_screen/", "/splash/", "/feed/banner/", "/popup/"
    )
    private val bypassProtectionDomains = setOf(
        "dns.alidns.com",
        "httpdns.alicdn.com",
        "doh.pub",
        "dot.pub",
        "dns.google",
        "dns.google.com",
        "dns64.dns.google",
        "cloudflare-dns.com",
        "one.one.one.one",
        "mozilla.cloudflare-dns.com",
        "chrome.cloudflare-dns.com",
        "security.cloudflare-dns.com",
        "family.cloudflare-dns.com",
        "dns.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        "dns.adguard-dns.com",
        "dns-family.adguard.com",
        "dns-unfiltered.adguard.com",
        "dns.nextdns.io",
        "doh.opendns.com",
        "dns.umbrella.com",
        "dns64.steward.net",
        "family-filter-dns.cleanbrowsing.org",
        "security-filter-dns.cleanbrowsing.org",
        "adult-filter-dns.cleanbrowsing.org",
        "httpdns.bcelive.com",
        "httpdns.baidu.com",
        "doh.baidu.com",
        "dns.srv.baidu.com",
        // 微信支付和小程序
        "tenpay.com",
        "wx.gtimg.com",
        "wx.qq.com",
        "file.weixin.qq.com",
        "mp.weixin.qq.com",
        "miniapp.qq.com",
        // 抖音/字节 CDN
        "douyin.com",
        "bytego.com.cn",
        "bytetos.com",
        "byteimg.com",
        "ibyteimg.com",
        "toscdn.com",
        "volces.com",
        // 支付宝小程序
        "myapp.com",
        // 通用 CDN
        "cdn.jsdelivr.net",
        "unpkg.com",
        "cdnjs.cloudflare.com",
        "fastly.jsdelivr.net",
        "qianxun.com",
        "ksyuncdn-k1.com"
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
        )
    )
    private val vendorAliases = mapOf(
        "Google (Google Ads)" to "Alphabet (Google)",
        "Meta (Facebook)" to "Meta (Meta Platforms)",
        "阿里 (Alibaba)" to "阿里巴巴集团 (Alibaba Group)",
        "友盟+ (Umeng+)" to "阿里巴巴集团 (Alibaba Group)",
        "优酷 (Youku)" to "阿里巴巴集团 (Alibaba Group)",
        "快手联盟 (Kwai Business)" to "快手 (Kuaishou)",
        "优量汇 (Tencent Marketing)" to "腾讯 (Tencent)",
        "腾讯广告 (Tencent Ads)" to "腾讯 (Tencent)",
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
        "replace",
        "cookie",
        "header",
        "method",
        "jsinject",
        "content",
        "extension"
    )

    fun getRules(context: Context): List<BlockRule> {
        cachedRules?.let { return it }
        synchronized(cacheLock) {
            cachedRules?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_RULES, "[]") ?: "[]"
            val type = object : TypeToken<List<BlockRule>>() {}.type
            val rules = (gson.fromJson<List<BlockRule>>(json, type) ?: emptyList())
                .map { it.copy(vendor = normalizeVendorName(it.vendor)) }
                .sortedBy { it.domain }
            updateRuleCache(rules)
            return rules
        }
    }

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
        val current = getRules(context).toMutableList()
        if (current.any { it.domain == domain }) return null
        val rule = BlockRule(
            id = UUID.randomUUID().toString(),
            domain = domain,
            vendor = classifyVendor(context, domain),
            source = source
        )
        current += rule
        save(context, current)
        return rule
    }

    fun addRules(context: Context, rawInput: String, source: RuleSource): List<BlockRule> {
        val current = getRules(context).toMutableList()
        val existingDomains = current.mapTo(linkedSetOf()) { it.domain }
        val added = mutableListOf<BlockRule>()
        parseManualInput(rawInput).forEach { domain ->
            if (existingDomains.add(domain)) {
                added += BlockRule(
                    id = UUID.randomUUID().toString(),
                    domain = domain,
                    vendor = classifyVendor(context, domain),
                    source = source
                )
            }
        }
        if (added.isNotEmpty()) {
            current += added
            save(context, current)
        }
        return added
    }

    fun importRules(context: Context, content: String, source: RuleSource = RuleSource.IMPORTED): Int {
        val current = getRules(context).toMutableList()
        val currentByDomain = current
            .filter { it.regexPattern == null && it.cosmeticSelector == null }
            .associateBy { it.domain }
            .toMutableMap()
        val existingRuleKeys = current.mapTo(linkedSetOf()) { buildRuleIdentityKey(it) }
        val parsed = parseImportLines(content)
        
        // 过滤会导致断网的白名单域名（使用智能匹配）
        val filteredBlockedRules = parsed.blockedRules.filterNot { blockedRule ->
            isWhitelistedDomain(blockedRule.domain)
        }

        parsed.badfilterRules.forEach { badfilter ->
            val existing = currentByDomain[badfilter.domain] ?: return@forEach
            subtractDnsTypeScope(existing, badfilter.dnsTypes, badfilter.excludedDnsTypes)?.let {
                currentByDomain[badfilter.domain] = it
            } ?: currentByDomain.remove(badfilter.domain)
        }

        filteredBlockedRules.forEach { blockedRule ->
            val ruleKey = buildParsedRuleKey(
                blockedRule.domain,
                blockedRule.dnsTypes,
                blockedRule.excludedDnsTypes,
                blockedRule.isBadfilter,
                blockedRule.regexPattern,
                blockedRule.cosmeticSelector,
                blockedRule.removeParams,
                blockedRule.cspValue,
                blockedRule.keywordPattern
            )
            if (!existingRuleKeys.add(ruleKey)) return@forEach
            val existing = if (blockedRule.regexPattern == null && blockedRule.cosmeticSelector == null) {
                currentByDomain[blockedRule.domain]
            } else {
                null
            }
            if (existing == null) {
                val addedRule = BlockRule(
                     id = UUID.randomUUID().toString(),
                     domain = blockedRule.domain,
                     vendor = classifyVendor(context, blockedRule.domain),
                     source = source,
                     dnsTypes = normalizeDnsTypes(blockedRule.dnsTypes),
                     excludedDnsTypes = normalizeDnsTypes(blockedRule.excludedDnsTypes),
                     thirdParty = blockedRule.thirdParty,
                      redirect = blockedRule.redirect,
                      denyallow = blockedRule.denyallow,
                      urlblock = blockedRule.urlblock,
                      appPackages = blockedRule.appPackages,
                      keywordPattern = blockedRule.keywordPattern,
                      regexPattern = blockedRule.regexPattern,
                      cosmeticSelector = blockedRule.cosmeticSelector,
                      removeParams = blockedRule.removeParams,
                      cspValue = blockedRule.cspValue
                  )
                current += addedRule
                if (addedRule.regexPattern == null && addedRule.cosmeticSelector == null) {
                    currentByDomain[addedRule.domain] = addedRule
                }
            } else {
                val merged = mergeRuleTypeScopes(existing, blockedRule)
                currentByDomain[blockedRule.domain] = merged
            }
        }

        parsed.exceptionRules.forEach { exceptionRule ->
            currentByDomain.entries.toList().forEach { (domain, existing) ->
                if (domain == exceptionRule.domain || domain.endsWith(".${exceptionRule.domain}")) {
                    subtractDnsTypeScope(existing, exceptionRule.dnsTypes, exceptionRule.excludedDnsTypes)?.let {
                        currentByDomain[domain] = it
                    } ?: currentByDomain.remove(domain)
                }
            }
        }

        val mergedRules = buildList {
            addAll(current.filter { it.regexPattern != null || it.cosmeticSelector != null })
            addAll(currentByDomain.values)
        }.distinctBy { buildRuleIdentityKey(it) }
        save(context, mergedRules.sortedBy { it.domain })
        return mergedRules.size
    }

    fun ensureBundledReferenceRules(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_BUNDLED_RULES_VERSION, 0) >= BUNDLED_RULES_VERSION) return 0
        // 版本不匹配时才加载规则（避免无必要的冷启动开销）
        val before = getRules(context).size
        val bundledResources = listOf(
            R.raw.default_safe_ad_rules,
            R.raw.bundled_rules_1,
            R.raw.bundled_rules_2,
            R.raw.bundled_rules_3,
            R.raw.bundled_rules_4
        )
        bundledResources.forEach { resourceId ->
            val content = context.resources.openRawResource(resourceId)
                .bufferedReader()
                .use { it.readText() }
            importRules(context, content, RuleSource.REFERENCE)
        }
        runCatching {
            context.assets.open("规则.txt").bufferedReader().use { it.readText() }
        }.getOrNull()?.let { content ->
            importRules(context, content, RuleSource.REFERENCE)
        }
        val after = getRules(context).size
        prefs.edit().putInt(KEY_BUNDLED_RULES_VERSION, BUNDLED_RULES_VERSION).apply()
        return (after - before).coerceAtLeast(0)
    }

    fun removeByIds(context: Context, ids: Set<String>) {
        save(context, getRules(context).filterNot { ids.contains(it.id) })
    }

    fun isBlocked(context: Context, domain: String, qType: Int? = null, appName: String? = null): Boolean {
        if (isWhitelistedDomain(domain)) return false
        val normalized = sanitizeDomain(domain) ?: return false
        val ruleMap = getRuleMap(context)
        val lowerDomain = normalized.lowercase()
        return buildDomainCandidates(normalized)
            .mapNotNull(ruleMap::get)
            .any { ruleMatches(it, qType, appName) } ||
            getRegexRules(context).any { matchesRegexRule(it, normalized) } ||
            getKeywordRules(context).any { rule ->
                val keyword = rule.keywordPattern?.lowercase() ?: return@any false
                lowerDomain.contains(keyword)
            }
    }

    fun isUrlBlocked(context: Context, host: String, path: String, appName: String? = null): Boolean {
        // 白名单域名直接放行
        if (isWhitelistedDomain(host)) return false
        val normalizedHost = sanitizeDomain(host) ?: return false
        val ruleMap = getRuleMap(context)
        val fullUrl = "$host$path".lowercase()
        return buildDomainCandidates(normalizedHost)
            .mapNotNull(ruleMap::get)
            .any { rule ->
                if (!ruleMatches(rule, null, appName)) return@any false
                if (rule.keywordPattern != null) {
                    return@any fullUrl.contains(rule.keywordPattern)
                }
                if (rule.urlblock && path.isNotBlank()) {
                    return@any looksLikeSuspiciousPath(path)
                }
                return@any rule.appPackages.isEmpty() || matchesAppPackage(rule.appPackages, appName)
            } || getRegexRules(context).any { matchesRegexRule(it, fullUrl) }
    }
    
    fun isWhitelistedDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lowerDomain = normalized.lowercase()
        cachedWhitelistHits[lowerDomain]?.let { return it }
        val result = checkDomainWhitelist(lowerDomain)
        cachedWhitelistHits[lowerDomain] = result
        return result
    }

    private fun checkDomainWhitelist(lowerDomain: String): Boolean {
        if (bypassProtectionDomains.contains(lowerDomain) || bypassProtectionDomains.any { lowerDomain.endsWith(".$it") }) return true
        if (whitelistDomains.contains(lowerDomain)) return true
        if (whitelistSuffixRoots.any { lowerDomain.endsWith(it) }) return true
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

    fun findMatchingRule(context: Context, domain: String, qType: Int? = null, appName: String? = null): BlockRule? {
        val normalized = sanitizeDomain(domain) ?: return null
        val ruleMap = getRuleMap(context)
        return buildDomainCandidates(normalized)
            .mapNotNull(ruleMap::get)
            .filter { ruleMatches(it, qType, appName) }
            .firstOrNull() ?: getRegexRules(context).firstOrNull { matchesRegexRule(it, normalized) }
    }

    fun getRequestRewriteDirectives(context: Context, host: String, path: String, appName: String? = null): RequestRewriteDirectives {
        val normalizedHost = sanitizeDomain(host) ?: return RequestRewriteDirectives()
        val matchedRules = buildDomainCandidates(normalizedHost)
            .mapNotNull(getRuleMap(context)::get)
            .filter { ruleMatches(it, null, appName) }
        val matchedRegexRules = getRegexRules(context).filter { matchesRegexRule(it, "$normalizedHost$path") || matchesRegexRule(it, normalizedHost) }
        val allRules = (matchedRules + matchedRegexRules).distinctBy { it.id }
        val removeParams = allRules.flatMap { it.removeParams }.toSet()
        val cspValue = allRules.mapNotNull { it.cspValue }.firstOrNull()
        return RequestRewriteDirectives(removeParams = removeParams, cspValue = cspValue)
    }

    fun getCosmeticSelectors(context: Context, host: String): List<String> {
        val normalizedHost = sanitizeDomain(host) ?: return emptyList()
        return getCosmeticRules(context)
            .filter { it.domain == COSMETIC_RULE_DOMAIN || normalizedHost == it.domain || normalizedHost.endsWith(".${it.domain}") }
            .mapNotNull { it.cosmeticSelector }
            .distinct()
    }

    fun shouldAggressivelyBlockForNovelApp(context: Context, domain: String, appName: String?, vendor: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (!isNovelAppHint(appName)) return false
        if (isWhitelistedDomain(normalized)) return false
        if (hasMatchingRule(context, normalized)) return false
        // 小说内容 API 域名不拦截
        if (novelContentApiDomains.contains(normalized) || novelContentApiDomains.any { normalized.endsWith(".$it") }) return false
        val normalizedVendor = normalizeVendorName(vendor)
        val lower = normalized.lowercase()
        // 增强广告域名信号检测 - 扩大关键词范围
        val hasAggressiveSignal = lower.contains("ad") || lower.contains("ads") || lower.contains("banner") || lower.contains("splash") || 
            lower.contains("promo") || lower.contains("tracking") || lower.contains("log") || lower.contains("stat") || 
            lower.contains("analytics") || lower.contains("monitor") || lower.contains("track") || lower.contains("count") ||
            lower.contains("report") || lower.contains("feed") || lower.contains("stream")
        // 广告供应商或广告信号立即拦截
        if (novelAggressiveVendorNames.contains(normalizedVendor) && hasAggressiveSignal) return true
        if (hasAggressiveSignal && looksLikeAdDomain(normalized)) return true
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
        val lowerPath = path?.lowercase().orEmpty()
        if (lowerPath.isBlank()) return false
        if (isUrlBlocked(context, normalizedHost, lowerPath, appName)) return true
        return fanqieProtectedAdPathKeywords.any { lowerPath.contains(it) } || looksLikeSuspiciousPath(lowerPath)
    }

    fun isSensitiveAuthDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (isWhitelistedDomain(normalized)) return true
        val lower = normalized.lowercase()
        val normalizedTokens = lower.replace(alphanumericRegex, "")
        val labels = lower.split('.').filter { it.isNotBlank() }
        return sensitiveAuthKeywords.any { keyword ->
            labels.any { it == keyword } || keywordMatches(lower, normalizedTokens, keyword)
        }
    }

    fun deduplicateRules(context: Context): Int {
        val rules = getRules(context)
        val seen = mutableSetOf<String>()
        val toRemove = mutableSetOf<String>()
        rules.forEach { rule ->
            val key = "${rule.domain}|${rule.vendor}|${rule.source}|${rule.keywordPattern}|${rule.regexPattern}|${rule.cosmeticSelector}"
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

    fun getRuleInventory(context: Context): RuleInventory {
        cachedRuleInventory?.let { return it }
        val rules = getRules(context)
        val inventory = RuleInventory(
            referenceCount = rules.count { it.source == RuleSource.REFERENCE },
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
        val lower = normalized.lowercase()
        val normalizedTokens = lower.replace(alphanumericRegex, "")
        vendorPatterns.entries.firstOrNull { (_, patterns) -> patterns.any { lower.contains(it) } }?.let {
            return normalizeVendorName(it.key).also { v -> cachedVendorMap[normalized] = v }
        }
        vendorKeywords.entries.firstOrNull { (_, keywords) -> keywords.any { keywordMatches(lower, normalizedTokens, it) } }?.let {
            return normalizeVendorName(it.key).also { v -> cachedVendorMap[normalized] = v }
        }
        vendorSdkIdentifiers.entries.firstOrNull { (_, identifiers) -> identifiers.any { identifierMatches(lower, normalizedTokens, it) } }?.let {
            return normalizeVendorName(it.key).also { v -> cachedVendorMap[normalized] = v }
        }
        val result = if (looksLikeAdDomain(lower)) GENERIC_AD_VENDOR else DEFAULT_VENDOR
        cachedVendorMap[normalized] = result
        return result
    }

    fun classifyVendorSimple(context: Context, domain: String, vararg hints: String?): String? {
        val normalized = sanitizeDomain(domain) ?: return null
        cachedVendorMap[normalized]?.let { return it }
        val lower = normalized.lowercase()
        val normalizedTokens = lower.replace(alphanumericRegex, "")
        vendorPatterns.entries.firstOrNull { (_, patterns) -> patterns.any { lower.contains(it) } }?.let {
            return normalizeVendorName(it.key).also { v -> cachedVendorMap[normalized] = v }
        }
        vendorKeywords.entries.firstOrNull { (_, keywords) -> keywords.any { keywordMatches(lower, normalizedTokens, it) } }?.let {
            return normalizeVendorName(it.key).also { v -> cachedVendorMap[normalized] = v }
        }
        val hintMatches = hints
            .asSequence()
            .filterNotNull()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .mapNotNull { hint ->
                val hintTokens = hint.replace(alphanumericCnRegex, "")
                vendorSdkIdentifiers.entries.firstOrNull { (_, identifiers) -> identifiers.any { identifierMatches(hint, hintTokens, it) } }?.key
            }
            .firstOrNull()
        hintMatches?.let { return normalizeVendorName(it).also { v -> cachedVendorMap[normalized] = v } }
        val result = if (looksLikeAdDomain(lower)) GENERIC_AD_VENDOR else DEFAULT_VENDOR
        cachedVendorMap[normalized] = result
        return result
    }

    fun classifyVendorFromHints(context: Context, domain: String, vararg hints: String?): String {
        val fromDomain = classifyVendor(context, domain)
        if (fromDomain != DEFAULT_VENDOR && fromDomain != GENERIC_AD_VENDOR) return fromDomain
        val matchedVendor = hints
            .asSequence()
            .filterNotNull()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .mapNotNull { hint ->
                val normalizedTokens = hint.replace(alphanumericCnRegex, "")
                vendorSdkIdentifiers.entries.firstOrNull { (_, identifiers) -> identifiers.any { identifierMatches(hint, normalizedTokens, it) } }?.key
            }
            .firstOrNull()
        return matchedVendor?.let(::normalizeVendorName) ?: fromDomain
    }

    fun reportUnknownVendorIfNeeded(context: Context, vendor: String, domain: String, appName: String? = null) {
        val normalizedVendor = normalizeVendorName(vendor)
        val normalized = sanitizeDomain(domain) ?: return
        if (hasMatchingRule(context, normalized)) return
        val normalizedAppName = normalizeSampleAppName(appName)
        val novelApp = isNovelAppHint(normalizedAppName)
        val shouldSample = normalizedVendor == DEFAULT_VENDOR ||
            normalizedVendor == GENERIC_AD_VENDOR ||
            (novelApp && looksLikeAdDomain(normalized))
        if (!shouldSample) return
        val samples = readUnknownVendorSamples(context).toMutableMap()
        val now = System.currentTimeMillis()
        val previous = samples[normalized]
        if (
            previous != null &&
            previous.lastAppName == normalizedAppName &&
            now - previous.lastSampleAt < SUSPICIOUS_SAMPLE_DEBOUNCE_MILLIS
        ) {
            return
        }
        val count = (previous?.count ?: 0) + 1
        val novelHits = (previous?.novelHits ?: 0) + if (novelApp) 1 else 0
        samples[normalized] = SuspiciousDomainRecord(
            count = count,
            lastSeenAt = now,
            lastAppName = normalizedAppName,
            lastVendor = normalizedVendor,
            novelHits = novelHits,
            lastSampleAt = now
        )
        val trimmed = samples.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> { it.value.novelHits }
                    .thenByDescending { it.value.count }
                    .thenByDescending { it.value.lastSeenAt }
                    .thenBy { it.key }
            )
            .take(120)
            .associate { it.key to it.value }
        saveUnknownVendorSamples(context, trimmed)
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
            append("domain,count,novel_hits,last_seen,last_app,last_vendor\n")
            samples.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> { it.value.novelHits }
                        .thenByDescending { it.value.count }
                        .thenByDescending { it.value.lastSeenAt }
                        .thenBy { it.key }
                )
                .forEach { entry ->
                    append(escapeCsvField(entry.key))
                    append(',')
                    append(entry.value.count)
                    append(',')
                    append(entry.value.novelHits)
                    append(',')
                    append(escapeCsvField(formatTimestamp(entry.value.lastSeenAt)))
                    append(',')
                    append(escapeCsvField(entry.value.lastAppName.ifBlank { "未知" }))
                    append(',')
                    append(escapeCsvField(entry.value.lastVendor.ifBlank { DEFAULT_VENDOR }))
                    append('\n')
                }
        }
    }

    fun getSuspiciousDomainSamples(context: Context): List<SuspiciousDomainSample> {
        return readUnknownVendorSamples(context)
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> { it.value.novelHits }
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
                    novelHits = it.value.novelHits
                )
            }
    }

    fun isNovelAppHint(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        val normalized = text.replace(alphanumericCnRegex, "")
        return novelAppIdentifiers.any { identifierMatches(text, normalized, it) }
    }

    fun isNovelVendor(vendor: String): Boolean = novelVendorNames.contains(normalizeVendorName(vendor))

    fun isProtectedNovelAppDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lower = normalized.lowercase()
        // 排除明显的广告子域名
        val adSubdomainPatterns = listOf("ad", "ads", "adserver", "adtrack", "adlog", "adx", "adv", "banner", "splash", "promotion", "promo", "marketing", "track", "tracking", "log", "logger", "stat", "stats", "analytics")
        if (adSubdomainPatterns.any { lower.startsWith("$it.") || lower.startsWith("$it-") || lower == it }) return false
        return buildDomainCandidates(normalized).any(novelAppProtectedSuffixes::contains)
    }

    fun hasMatchingRule(context: Context, domain: String): Boolean {
        return findMatchingRule(context, domain) != null
    }

    private fun keywordMatches(domain: String, normalizedTokens: String, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        if (keyword.length <= 2) {
            val labels = domain.split('.', '-', '_').filter { it.isNotBlank() }
            return labels.any { it == keyword }
        }
        return domain.contains(keyword) || normalizedTokens.contains(keyword)
    }

    private fun identifierMatches(text: String, normalizedTokens: String, identifier: String): Boolean {
        if (identifier.isBlank()) return false
        val lowerIdentifier = identifier.lowercase()
        val normalizedIdentifier = lowerIdentifier.replace(alphanumericCnRegex, "")
        return text.contains(lowerIdentifier) || normalizedTokens.contains(normalizedIdentifier)
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
            if (rule.id == id) rule.copy(vendor = targetVendor) else rule
        }
        val targetRule = updated.firstOrNull { it.id == id } ?: return
        val customMap = readCustomVendorMap(context).toMutableMap()
        customMap[targetRule.domain] = targetVendor
        save(context, updated)
        saveCustomVendorMap(context, customMap)
    }

    fun analyzeImportContent(context: Context, content: String): RuleAnalysisReport {
        val existingRules = getRules(context)
        val existingDomains = existingRules.map(BlockRule::domain).toMutableSet()
        val existingRuleKeys = existingRules.mapTo(linkedSetOf()) { buildRuleIdentityKey(it) }
        val simulatedDomains = existingDomains.toMutableSet()
        val seenBlocked = linkedSetOf<String>()
        val seenExceptions = linkedSetOf<String>()
        val unsupportedLines = mutableListOf<String>()
        val invalidLines = mutableListOf<String>()
        val vendorCount = linkedMapOf<String, Int>()
        var blankOrCommentLines = 0
        var safeBlockedRules = 0
        var safeExceptionRules = 0
        var duplicateExistingRules = 0
        var duplicateWithinFileRules = 0
        var unsupportedModifierRules = 0
        var cosmeticRules = 0
        var regexRules = 0
        var invalidRules = 0
        var exceptionRemovalEstimate = 0

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isBlank() || line.startsWith("#") || line.startsWith("!") -> {
                    blankOrCommentLines += 1
                }

                else -> {
                    val parsedRules = parseRuleLine(rawLine)
                    if (parsedRules.isEmpty()) {
                        val working = line.removePrefix("@@")
                        val candidate = extractDomainCandidate(working)
                        if (candidate == null) {
                            if (looksLikeComplexRulePattern(working)) {
                                unsupportedModifierRules += 1
                                unsupportedLines += "$line    [complex-pattern]"
                            } else {
                                invalidRules += 1
                                invalidLines += line
                            }
                            return@forEach
                        }
                        val modifierInfo = parseModifierInfo(candidate.second)
                        if (modifierInfo.unsupportedModifiers.isNotEmpty()) {
                            unsupportedModifierRules += 1
                            unsupportedLines += "$line    [${modifierInfo.unsupportedModifiers.joinToString(", ")}]"
                        } else if (modifierInfo.invalid) {
                            unsupportedModifierRules += 1
                            unsupportedLines += "$line    [invalid-modifier]"
                        } else {
                            invalidRules += 1
                            invalidLines += line
                        }
                        return@forEach
                    }

                    parsedRules.forEach parsedRuleLoop@{ parsedRule ->
                        if (parsedRule.regexPattern != null) regexRules += 1
                        if (parsedRule.cosmeticSelector != null) cosmeticRules += 1
                        val ruleKey = buildParsedRuleKey(
                            parsedRule.domain,
                            parsedRule.dnsTypes,
                            parsedRule.excludedDnsTypes,
                            parsedRule.isBadfilter,
                            parsedRule.regexPattern,
                            parsedRule.cosmeticSelector,
                            parsedRule.removeParams,
                            parsedRule.cspValue,
                            parsedRule.keywordPattern
                        )
                        if (parsedRule.isException) {
                            if (!seenExceptions.add(ruleKey)) {
                                duplicateWithinFileRules += 1
                                return@parsedRuleLoop
                            }
                            safeExceptionRules += 1
                            val removed = simulatedDomains.count { it == parsedRule.domain || it.endsWith(".${parsedRule.domain}") }
                            exceptionRemovalEstimate += removed
                            simulatedDomains.removeAll { it == parsedRule.domain || it.endsWith(".${parsedRule.domain}") }
                            return@parsedRuleLoop
                        }
                        if (parsedRule.isBadfilter) {
                            safeExceptionRules += 1
                            val removed = simulatedDomains.remove(parsedRule.domain)
                            if (removed) exceptionRemovalEstimate += 1
                            return@parsedRuleLoop
                        }
                        if (!seenBlocked.add(ruleKey)) {
                            duplicateWithinFileRules += 1
                            return@parsedRuleLoop
                        }
                        if (existingRuleKeys.contains(ruleKey)) {
                            duplicateExistingRules += 1
                            return@parsedRuleLoop
                        }
                        safeBlockedRules += 1
                        simulatedDomains += parsedRule.domain
                        val vendor = classifyVendor(context, parsedRule.domain)
                        vendorCount[vendor] = (vendorCount[vendor] ?: 0) + 1
                    }
                }
            }
        }

        return RuleAnalysisReport(
            totalLines = content.lineSequence().count(),
            existingRules = existingRules.size,
            estimatedFinalRules = simulatedDomains.size,
            blankOrCommentLines = blankOrCommentLines,
            safeBlockedRules = safeBlockedRules,
            safeExceptionRules = safeExceptionRules,
            duplicateExistingRules = duplicateExistingRules,
            duplicateWithinFileRules = duplicateWithinFileRules,
            unsupportedModifierRules = unsupportedModifierRules,
            cosmeticRules = cosmeticRules,
            regexRules = regexRules,
            invalidRules = invalidRules,
            exceptionRemovalEstimate = exceptionRemovalEstimate,
            vendorSummary = vendorCount.entries
                .sortedByDescending { it.value }
                .take(16)
                .map { VendorSummary(it.key, it.value) },
            sampleUnsupportedLines = unsupportedLines.distinct().take(10),
            sampleInvalidLines = invalidLines.distinct().take(10)
        )
    }

    private fun parseImportLines(content: String): ParsedRules {
        val blocked = linkedMapOf<String, ParsedRule>()
        val exceptions = linkedMapOf<String, ParsedRule>()
        val badfilters = linkedMapOf<String, ParsedRule>()

        content.lineSequence().forEach { rawLine ->
            parseRuleLine(rawLine).forEach { parsedRule ->
                when {
                    parsedRule.isBadfilter -> {
                        val existing = badfilters[parsedRule.domain]
                        badfilters[parsedRule.domain] = mergeParsedRule(existing, parsedRule)
                    }

                    parsedRule.isException -> {
                        val existing = exceptions[parsedRule.domain]
                        exceptions[parsedRule.domain] = mergeParsedRule(existing, parsedRule)
                    }

                    else -> {
                        val existing = blocked[parsedRule.domain]
                        blocked[parsedRule.domain] = mergeParsedRule(existing, parsedRule)
                    }
                }
            }
        }

        return ParsedRules(
            blockedRules = blocked.values.toList(),
            exceptionRules = exceptions.values.toList(),
            badfilterRules = badfilters.values.toList()
        )
    }

    fun parseManualInput(rawInput: String): List<String> {
        val blocked = linkedSetOf<String>()
        rawInput.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) return@forEach
            val parsedRules = parseRuleLine(trimmed)
            if (parsedRules.isNotEmpty()) {
                parsedRules.filterNot { it.isException || it.isBadfilter }.forEach { blocked += it.domain }
            } else {
                trimmed.split(splitWhitespaceRegex)
                    .mapNotNull { sanitizeDomain(normalizeDomainToken(it)) }
                    .forEach { blocked += it }
            }
        }
        return blocked.toList()
    }

    private fun parseRuleLine(rawLine: String): List<ParsedRule> {
        val line = stripInlineRuleComment(rawLine)
        if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) return emptyList()
        parseCosmeticRule(line)?.let { return listOf(it) }
        parseRegexRule(line)?.let { return listOf(it) }
        parseClashRule(line)?.let { return it }
        parseSurgeWildcardDomain(line)?.let { return it }
        parseLoonKeywordRule(line)?.let { return it }
        parseLoonUrlRegex(line)?.let { return it }
        parseAbpDomainRule(line)?.let { return it }
        parseShadowrocketRule(line)?.let { return it }
        parseSurgeUrlKeyword(line)?.let { return it }

        val trimmedLine = line.trim()
        if (trimmedLine.startsWith("+.") && trimmedLine.substring(2).isNotBlank()) {
            val suffixDomain = sanitizeDomain(trimmedLine.substring(2))
            if (suffixDomain != null) return listOf(ParsedRule(domain = suffixDomain, isException = false))
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

        return domains.map { domain ->
            ParsedRule(
                domain = domain,
                isException = isException,
                isBadfilter = modifierInfo.badfilter,
                dnsTypes = modifierInfo.dnsTypes,
                excludedDnsTypes = modifierInfo.excludedDnsTypes,
                thirdParty = modifierInfo.thirdParty,
                redirect = modifierInfo.redirect,
                denyallow = modifierInfo.denyallow,
                urlblock = modifierInfo.urlblock,
                appPackages = modifierInfo.appPackages,
                keywordPattern = keywordPattern,
                regexPattern = null,
                cosmeticSelector = null,
                removeParams = modifierInfo.removeParams,
                cspValue = modifierInfo.cspValue
            )
        }
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
        return ParsedRule(
            domain = parsedDomain ?: COSMETIC_RULE_DOMAIN,
            isException = marker == "#@#",
            cosmeticSelector = selector
        )
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
            !trimmed.startsWith("DOMAIN-REGEX:", ignoreCase = true)) return null
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
        val value = line.substring(colonIndex + 1).trim()
        if (value.isBlank()) return null
        return when (prefix.lowercase()) {
            "domain", "full", "full-domain", "host", "hostname" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-suffix", "host-suffix", "suffix" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-keyword", "host-keyword", "keyword" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "url-regex", "url-regexp", "regex" -> {
                listOf(ParsedRule(domain = value, isException = false, regexPattern = value))
            }
            "user-agent", "ua" -> {
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

    private fun parseClashRule(line: String): List<ParsedRule>? {
        if (!line.contains(',')) return null
        val segments = line.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return null
        val ruleType = segments[0].uppercase()
        val value = segments[1]
        if (value.isBlank()) return null
        return when (ruleType) {
            "DOMAIN-SUFFIX", "HOST-SUFFIX", "DOMAIN-SUFFIXES" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN", "HOST" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN-KEYWORD", "HOST-KEYWORD" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "DOMAIN-WILDCARD", "HOST-WILDCARD" -> {
                val cleaned = value.removePrefix("*.").removePrefix("*.")
                val domain = sanitizeDomain(cleaned) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "URL-REGEX", "URL-REGEXP" -> {
                listOf(ParsedRule(domain = value, isException = false, regexPattern = value))
            }
            "URL-KEYWORD" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "USER-AGENT", "UA" -> {
                emptyList()
            }
            "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "IP-ASN", "GEOIP", "GEOSITE" -> {
                emptyList()
            }
            "FINAL", "MATCH" -> {
                emptyList()
            }
            else -> null
        }
    }

    private fun extractRegexRuleDomain(pattern: String): String? {
        val normalized = pattern
            .replace("\\.", ".")
            .replace("\\-", "-")
            .replace("\\/", "/")
         val directMatch = domainExtractRegex
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
        val sanitizedDirect = directMatch?.let(::sanitizeDomain)
        if (sanitizedDirect != null) return sanitizedDirect

        val labels = domainSubdomainRegex
            .findAll(normalized)
            .map { it.value.lowercase() }
            .filter { it.any(Char::isLetterOrDigit) }
            .toList()
        if (labels.size < 2) return null
        val stableLabels = labels.filterNot { it.contains('*') }
        if (stableLabels.size < 2) return null
        return sanitizeDomain(stableLabels.takeLast(2).joinToString("."))
    }

    private fun extractDomainCandidate(line: String): Pair<String, String?>? {
        val patternPart = line.substringBefore('$').trim()
        val modifierPart = line.substringAfter('$', missingDelimiterValue = "").trim().ifBlank { null }
        if (patternPart.isBlank()) return null
        return patternPart to modifierPart
    }

    private fun stripInlineRuleComment(rawLine: String): String {
        val commentMarkers = listOf(" #", " !", " ;", " //")
        val cutIndex = commentMarkers
            .map { marker -> rawLine.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
            ?: rawLine.length
        return rawLine.substring(0, cutIndex).trim()
    }

    private fun parseDomainsFromPattern(patternPart: String): List<String> {
        val trimmed = stripYamlListPrefix(patternPart.trim())
        if (trimmed.equals("payload:", ignoreCase = true) || trimmed.equals("payload", ignoreCase = true)) return emptyList()
        val dnsmasqPrefix = dnsmasqPrefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        return when {
            trimmed.startsWith("||") -> listOfNotNull(parseDomainAnchorPattern(trimmed.removePrefix("||")))
            trimmed.startsWith("|") -> listOfNotNull(parseExactAnchorPattern(trimmed.removePrefix("|").removeSuffix("|")))
            dnsmasqPrefix != null -> parseDnsmasqDomains(trimmed, dnsmasqPrefix)
            else -> parseStructuredDomainRule(trimmed).ifEmpty { parseHostsOrPlainDomains(trimmed) }
        }
    }

    private fun parseDomainAnchorPattern(pattern: String): String? {
        val trimmed = pattern.trim()
        val slashIndex = trimmed.indexOf('/')
        val caretIndex = trimmed.indexOf('^')
        val boundaryIndex = sequenceOf(slashIndex, caretIndex)
            .filter { it >= 0 }
            .minOrNull()
            ?: trimmed.length
        val domainToken = trimmed.substring(0, boundaryIndex)
        val suffix = trimmed.substring(boundaryIndex)
        if (domainToken.isBlank()) return null
        return sanitizeDomain(normalizeDomainToken(domainToken))
            ?: parseWildcardDomainAnchorPattern(domainToken)
            ?: if (slashIndex > 0) {
                sanitizeDomain(normalizeDomainToken(trimmed.substring(0, slashIndex)))
            } else {
                null
            }
    }

    private fun parseExactAnchorPattern(pattern: String): String? {
        val trimmed = pattern.trim()
        val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
        val slashIndex = withoutScheme.indexOf('/')
        val questionIndex = withoutScheme.indexOf('?')
        val boundaryIndex = sequenceOf(slashIndex, questionIndex)
            .filter { it >= 0 }
            .minOrNull()
            ?: withoutScheme.length
        val domainToken = withoutScheme.substring(0, boundaryIndex)
        val suffix = withoutScheme.substring(boundaryIndex)
        if (domainToken.isBlank()) return null
        if (!isSafeDomainPatternSuffix(suffix)) return null
        return sanitizeDomain(normalizeDomainToken(domainToken))
            ?: parseWildcardDomainAnchorPattern(domainToken)
    }

    private fun parseWildcardDomainAnchorPattern(pattern: String): String? {
        val normalized = normalizeDomainToken(pattern)
        if (!normalized.contains('*')) return null
        val labels = normalized.split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return null
        val stableLabels = labels.filterNot { it.contains('*') }
        when {
            stableLabels.size >= 2 -> {
                val candidate = stableLabels.takeLast(2).joinToString(".")
                return sanitizeDomain(candidate)
            }
            stableLabels.size == 1 -> {
                val wildcardIndex = labels.indexOfFirst { it.contains('*') }
                if (wildcardIndex >= 0) {
                    val possibleDomain = if (wildcardIndex < labels.lastIndex) {
                        labels.drop(wildcardIndex + 1).takeLast(2).joinToString(".")
                    } else {
                        stableLabels.joinToString(".")
                    }
                    val fallback = stableLabels.firstOrNull()
                    return if (fallback != null) sanitizeDomain(possibleDomain.takeIf { it.isNotBlank() } ?: fallback) else null
                }
                return null
            }
            else -> {
                val candidate = labels.takeLast(2).joinToString(".")
                return sanitizeDomain(candidate)
            }
        }
    }

    private fun isSafeDomainPatternSuffix(suffix: String): Boolean {
        if (suffix.isBlank()) return true
        return suffix.all { it == '^' || it == '|' }
    }

    private fun parseHostsOrPlainDomains(patternPart: String): List<String> {
        val cleaned = patternPart.substringBefore('#').trim()
        val tokens = cleaned.split(whitespaceRegex).filter { it.isNotBlank() }
        if (tokens.size >= 2 && isHostsIpToken(tokens[0])) {
            return tokens.drop(1)
                .mapNotNull { sanitizeDomain(normalizeDomainToken(it)) }
                .distinct()
        }
        return listOfNotNull(sanitizeDomain(normalizeDomainToken(cleaned)))
    }

    private fun parseDnsmasqDomains(patternPart: String, matchedPrefix: String): List<String> {
        val body = patternPart.substring(matchedPrefix.length)
        return body.split('/')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !looksLikeIpAddress(it) }
            .mapNotNull { sanitizeDomain(normalizeDomainToken(it)) }
            .distinct()
            .toList()
    }

    private fun parseStructuredDomainRule(patternPart: String): List<String> {
        val normalized = stripYamlListPrefix(patternPart)
        parsePrefixedDomainRule(normalized)?.let { return listOf(it) }
        val segments = normalized.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return emptyList()
        val ruleType = segments.first().lowercase()
        val domainToken = segments.getOrNull(1) ?: return emptyList()
        return when (ruleType) {
            "domain-suffix", "domain", "host-suffix", "host", "hostname-suffix", "suffix" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "domain-wildcard", "host-wildcard", "hostname-wildcard" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken.removePrefix("*.")))
            }
            "full", "full-domain", "hostname", "host-full", "hostname-full", "domain-full", "domain-exact", "host-exact" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "domain-keyword", "host-keyword", "keyword" -> {
                emptyList()
            }
            "keyword", "domain-keyword", "host-keyword", "domain-regex", "host-regex", "url-regex",
            "ip-cidr", "ip-cidr6", "src-ip-cidr", "geoip", "geosite", "rule-set", "process-name",
            "process-path", "package-name", "user-agent", "dst-port", "src-port", "inbound", "network",
            "protocol", "and", "or", "not" -> {
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun parsePrefixedDomainRule(patternPart: String): String? {
        return parseDelimitedPrefixedDomainRule(patternPart, ':')
            ?: parseDelimitedPrefixedDomainRule(patternPart, '=')
    }

    private fun parseDelimitedPrefixedDomainRule(patternPart: String, delimiter: Char): String? {
        val exactPrefixes = listOf(
            "full",
            "full-domain",
            "domain",
            "host",
            "hostname",
            "domain-suffix",
            "host-suffix",
            "hostname-suffix",
            "suffix",
            "host-exact",
            "domain-exact",
            "domain-full",
            "hostname-full"
        )
        val wildcardPrefixes = listOf("domain-wildcard", "host-wildcard", "hostname-wildcard")
        val normalized = patternPart.trim()
        val exactPrefix = exactPrefixes.firstOrNull { normalized.startsWith("$it$delimiter", ignoreCase = true) }
        if (exactPrefix != null) {
            return parseStructuredDomainToken(normalized.substring(exactPrefix.length + 1))
        }
        val wildcardPrefix = wildcardPrefixes.firstOrNull { normalized.startsWith("$it$delimiter", ignoreCase = true) }
        if (wildcardPrefix != null) {
            return parseStructuredDomainToken(normalized.substring(wildcardPrefix.length + 1).removePrefix("*."))
        }
        return null
    }

    private fun stripYamlListPrefix(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("- ") -> trimmed.substring(2).trim()
            trimmed.startsWith("* ") -> trimmed.substring(2).trim()
            trimmed == "-" || trimmed == "*" -> ""
            else -> trimmed
        }
    }

    private val dnsmasqPrefixes = listOf("address=/", "server=/", "local=/", "ipset=/", "nftset=/")

    private fun parseStructuredDomainToken(raw: String): String? {
        return sanitizeDomain(
            normalizeDomainToken(
                raw.trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .removeSurrounding("[", "]")
                    .removePrefix("+.")
                    .removePrefix(".")
            )
        )
    }

    private fun normalizeDomainToken(raw: String): String {
        var current = raw.trim()
        current = current.removeSurrounding("\"").removeSurrounding("'").removeSurrounding("[", "]")
        current = current.removePrefix("*://").removePrefix("://")
        current = current.substringAfter("://", missingDelimiterValue = current)
        return current
            .removePrefix("*.")
            .removePrefix(".")
            .removePrefix("[")
            .removeSuffix("]")
            .trim('*')
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('^')
            .substringBefore('|')
            .substringBefore(':')
            .substringBefore('#')
            .substringBefore('@')
            .trim()
    }

    private fun isHostsIpToken(token: String): Boolean {
        return token == "0.0.0.0" || token == "127.0.0.1" || token == "::" || token == "::1"
    }

    private fun looksLikeIpAddress(token: String): Boolean {
        val value = token.trim().trim('[').trim(']')
        if (value.isBlank()) return false
        if (value.contains(':')) return true
        return value.matches(ipV4Regex)
    }

    private fun looksLikeAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        val normalizedTokens = lower.replace(alphanumericRegex, "")
        return adKeywords.any { keywordMatches(lower, normalizedTokens, it) }
    }

    private fun looksLikeBypassProtectionDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        return buildDomainCandidates(normalized).any(bypassProtectionDomains::contains)
    }

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
        if (modifierPart == null) return emptyList()
        val modifiers = modifierPart.split(',')
            .map { it.trim().removePrefix("~").substringBefore('=').lowercase() }
            .filter { it.isNotBlank() }
        if (modifiers.isEmpty()) return emptyList()
        return modifiers.filter { unsupportedAdGuardModifiers.contains(it) }
    }

    private fun parseModifierInfo(modifierPart: String?): ModifierInfo {
        if (modifierPart == null) return ModifierInfo()
        val unsupported = extractUnsupportedModifiers(modifierPart)
        if (unsupported.isNotEmpty()) return ModifierInfo(unsupportedModifiers = unsupported)

        var dnsTypes: Set<Int>? = null
        var excludedDnsTypes: Set<Int>? = null
        var badfilter = false
        var appScoped = false
        val appPackages = mutableSetOf<String>()
        var domainScoped = false
        var thirdParty = false
        var redirect = false
        val denyallow = mutableSetOf<String>()
        var urlblock = false
        var fromScoped = false
        var toScoped = false
        var pathScoped = false
        val removeParams = mutableSetOf<String>()
        var cspValue: String? = null
        modifierPart.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { rawModifier ->
                val inverted = rawModifier.startsWith("~")
                val modifier = rawModifier.removePrefix("~")
                val name = modifier.substringBefore('=').trim().lowercase()
                val value = modifier.substringAfter('=', missingDelimiterValue = "").trim()
                when (name) {
                    "important", "match-case" -> Unit
                    "badfilter" -> badfilter = true
                    "app" -> {
                        if (inverted || value.isBlank()) return ModifierInfo(invalid = true)
                        appScoped = true
                        appPackages.addAll(value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() })
                    }
                    "domain" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        domainScoped = true
                    }
                    "dnstype" -> {
                        if (inverted || value.isBlank()) return ModifierInfo(invalid = true)
                        val tokens = value.split('|').map { it.trim() }.filter { it.isNotBlank() }
                        val includeTokens = tokens.filterNot { it.startsWith("~") }
                        val excludeTokens = tokens.filter { it.startsWith("~") }.map { it.removePrefix("~").trim() }
                        if (includeTokens.isEmpty() && excludeTokens.isEmpty()) return ModifierInfo(invalid = true)
                        if (includeTokens.isNotEmpty()) {
                            val parsedTypes = includeTokens.mapNotNull(::mapDnsTypeToken).toSet()
                            if (parsedTypes.isEmpty() || parsedTypes.size != includeTokens.size) return ModifierInfo(invalid = true)
                            dnsTypes = mergeDnsTypes(dnsTypes, parsedTypes)
                        }
                        if (excludeTokens.isNotEmpty()) {
                            val parsedExcludedTypes = excludeTokens.mapNotNull(::mapDnsTypeToken).toSet()
                            if (parsedExcludedTypes.isEmpty() || parsedExcludedTypes.size != excludeTokens.size) return ModifierInfo(invalid = true)
                            excludedDnsTypes = mergeDnsTypes(excludedDnsTypes, parsedExcludedTypes)
                        }
                    }
                    "third-party" -> {
                        if (!inverted) thirdParty = true
                    }
                    "redirect", "redirect-rule" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        redirect = true
                    }
                    "denyallow" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val domains = value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (domains.isEmpty()) return ModifierInfo(invalid = true)
                        denyallow.addAll(domains)
                    }
                    "removeparam" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        removeParams.addAll(value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() })
                    }
                    "csp" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        cspValue = value
                    }
                    "urlblock" -> {
                        urlblock = true
                    }
                    "from" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        fromScoped = true
                    }
                    "to" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        toScoped = true
                    }
                    "path" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        pathScoped = true
                    }
                }
            }
        val normalizedIncluded = normalizeDnsTypes(dnsTypes)
        val normalizedExcluded = normalizeDnsTypes(excludedDnsTypes)
        if (normalizedIncluded != null && normalizedExcluded != null && normalizedIncluded.any(normalizedExcluded::contains)) {
            return ModifierInfo(invalid = true)
        }
        return ModifierInfo(
            dnsTypes = normalizedIncluded,
            excludedDnsTypes = normalizedExcluded,
            badfilter = badfilter,
            appScoped = appScoped,
            appPackages = appPackages.toSet(),
            domainScoped = domainScoped,
            thirdParty = thirdParty,
            redirect = redirect,
            denyallow = denyallow.toSet(),
            urlblock = urlblock,
            fromScoped = fromScoped,
            toScoped = toScoped,
            pathScoped = pathScoped,
            removeParams = removeParams.toSet(),
            cspValue = cspValue
        )
    }

    private fun extractKeywordPattern(pattern: String): String? {
        val trimmed = pattern.trim().removePrefix("*").removeSuffix("*").removeSuffix("^").removeSuffix("/")
        if (trimmed.isBlank()) return null
        return trimmed.lowercase()
    }

    private fun canSafelyApplyModifierContext(patternPart: String, modifierInfo: ModifierInfo): Boolean {
        val hasKeyword = patternPart.contains('*')
        if (hasKeyword) return true

        val needsAdCheck = modifierInfo.appScoped ||
            modifierInfo.domainScoped ||
            modifierInfo.thirdParty ||
            modifierInfo.redirect ||
            modifierInfo.denyallow.isNotEmpty() ||
            modifierInfo.fromScoped ||
            modifierInfo.toScoped ||
            modifierInfo.pathScoped

        if (!needsAdCheck) return true

        val domains = parseDomainsFromPattern(patternPart)
        if (domains.isEmpty()) return false
        return domains.all { domain ->
            looksLikeAdDomain(domain) || looksLikeBypassProtectionDomain(domain)
        }
    }

    private fun mapDnsTypeToken(token: String): Int? {
        return when (token.trim().uppercase()) {
            "A" -> 1
            "NS" -> 2
            "CNAME" -> 5
            "SOA" -> 6
            "PTR" -> 12
            "MX" -> 15
            "TXT" -> 16
            "AAAA" -> 28
            "SRV" -> 33
            "NAPTR" -> 35
            "SVCB" -> 64
            "HTTPS" -> 65
            "CAA" -> 257
            "ANY" -> 255
            else -> null
        }
    }

    private fun normalizeDnsTypes(dnsTypes: Set<Int>?): Set<Int>? {
        if (dnsTypes.isNullOrEmpty()) return null
        if (dnsTypes.contains(255)) return null
        return dnsTypes.toSortedSet()
    }

    private fun mergeDnsTypes(existing: Set<Int>?, incoming: Set<Int>?): Set<Int>? {
        val normalizedExisting = normalizeDnsTypes(existing)
        val normalizedIncoming = normalizeDnsTypes(incoming)
        if (normalizedExisting == null || normalizedIncoming == null) return null
        return (normalizedExisting + normalizedIncoming).toSortedSet()
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
        return rule.copy(
            dnsTypes = normalizeDnsTypes(remainedIncluded),
            excludedDnsTypes = normalizeDnsTypes(remainedExcluded)
        )
    }

    private fun buildParsedRuleKey(
        domain: String,
        dnsTypes: Set<Int>?,
        excludedDnsTypes: Set<Int>?,
        badfilter: Boolean,
        regexPattern: String? = null,
        cosmeticSelector: String? = null,
        removeParams: Set<String> = emptySet(),
        cspValue: String? = null,
        keywordPattern: String? = null
    ): String {
        val dnsKey = normalizeDnsTypes(dnsTypes)?.joinToString("|") ?: "*"
        val excludedDnsKey = normalizeDnsTypes(excludedDnsTypes)?.joinToString("|") ?: "-"
        val removeParamKey = removeParams.toSortedSet().joinToString("|")
        return listOf(
            domain,
            dnsKey,
            excludedDnsKey,
            badfilter.toString(),
            regexPattern.orEmpty(),
            cosmeticSelector.orEmpty(),
            removeParamKey,
            cspValue.orEmpty(),
            "kw:${keywordPattern.orEmpty()}"
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

    private fun save(context: Context, rules: List<BlockRule>) {
        val normalizedRules = rules.map { it.copy(vendor = normalizeVendorName(it.vendor)) }.sortedBy { it.domain }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RULES, gson.toJson(normalizedRules))
            .apply()
        updateRuleCache(normalizedRules)
    }

    private fun clearCaches() {
        cachedRules = null
        cachedBlockedDomains = null
        cachedRuleMap = null
        cachedRegexRules = null
        cachedCosmeticRules = null
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
        val prefsValue = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNKNOWN_VENDOR_SAMPLES, "{}") ?: "{}"
        runCatching {
            val type = object : TypeToken<Map<String, SuspiciousDomainRecord>>() {}.type
            gson.fromJson<Map<String, SuspiciousDomainRecord>>(prefsValue, type)
        }.getOrNull()?.let { parsed ->
            return parsed.filterValues { it.count > 0 }
        }
        val legacyType = object : TypeToken<Map<String, Int>>() {}.type
        val legacy = gson.fromJson<Map<String, Int>>(prefsValue, legacyType) ?: emptyMap()
        val migrated = legacy.mapValues { SuspiciousDomainRecord(count = it.value, lastSeenAt = 0L) }
        if (migrated.isNotEmpty()) {
            saveUnknownVendorSamples(context, migrated)
        }
        return migrated
    }

    private fun saveUnknownVendorSamples(context: Context, samples: Map<String, SuspiciousDomainRecord>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UNKNOWN_VENDOR_SAMPLES, gson.toJson(samples))
            .apply()
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "未知"
        return timeFormatter.format(Date(timestamp))
    }

    private fun normalizeSampleAppName(appName: String?): String {
        return appName
            ?.replace(lineBreakRegex, " ")
            ?.trim()
            ?.take(80)
            .orEmpty()
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
        if (vendor.isBlank()) return DEFAULT_VENDOR
        var current = vendor.trim()
        val seen = linkedSetOf<String>()
        while (seen.add(current)) {
            val next = vendorAliases[current] ?: break
            current = next
        }
        return current
    }

    private fun getBlockedDomainSet(context: Context): Set<String> {
        cachedBlockedDomains?.let { return it }
        return getRules(context).mapTo(linkedSetOf(), BlockRule::domain)
    }

    private fun getRuleMap(context: Context): Map<String, BlockRule> {
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
        cachedBlockedDomains = rules.mapTo(linkedSetOf(), BlockRule::domain)
        cachedRuleMap = rules.associateBy { it.domain }
        cachedRegexRules = rules.filter { !it.regexPattern.isNullOrBlank() }
        cachedCosmeticRules = rules.filter { !it.cosmeticSelector.isNullOrBlank() }
        cachedKeywordRules = rules.filter { !it.keywordPattern.isNullOrBlank() }
        cachedCompiledRegexRules = emptyMap()
        cachedVendorMap.clear()
        cachedRuleInventory = null
    }

    private fun ruleMatches(rule: BlockRule, qType: Int?, appName: String? = null): Boolean {
        if (!matchesAppPackage(rule.appPackages, appName)) return false
        if (qType == null) return true
        val dnsTypes = normalizeDnsTypes(rule.dnsTypes)
        val excludedDnsTypes = normalizeDnsTypes(rule.excludedDnsTypes)
        if (excludedDnsTypes != null && excludedDnsTypes.contains(qType)) return false
        return dnsTypes == null || dnsTypes.contains(qType)
    }

    private fun matchesRegexRule(rule: BlockRule, value: String): Boolean {
        val pattern = rule.regexPattern ?: return false
        val compiled = cachedCompiledRegexRules[pattern]
            ?: java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                .also { cachedCompiledRegexRules = cachedCompiledRegexRules + (pattern to it) }
        return compiled.matcher(value).find()
    }

    private fun buildRuleIdentityKey(rule: BlockRule): String {
        return buildParsedRuleKey(
            domain = rule.domain,
            dnsTypes = rule.dnsTypes,
            excludedDnsTypes = rule.excludedDnsTypes,
            badfilter = false,
            regexPattern = rule.regexPattern,
            cosmeticSelector = rule.cosmeticSelector,
            removeParams = rule.removeParams,
            cspValue = rule.cspValue,
            keywordPattern = rule.keywordPattern
        )
    }

    data class RequestRewriteDirectives(
        val removeParams: Set<String> = emptySet(),
        val cspValue: String? = null
    )

    private fun looksLikeSuspiciousPath(path: String): Boolean {
        val lowerPath = path.lowercase()
        val suspiciousKeywords = listOf("/ad", "/ads", "/advert", "/banner", "/splash", "/promo", "/tracker")
        return suspiciousKeywords.any { lowerPath.contains(it) }
    }

    private fun hasAggressiveNovelAdSignal(domain: String): Boolean {
        val lowerDomain = domain.lowercase()
        val strongSignals = listOf(
            "pangolin", "pangle", "gromore", "oceanengine", "adservice", "adserver", "adtrack",
            "adsdk", "unionad", "mediation", "rtb", "dsp", "ssp", "reward", "splash", "interstitial"
        )
        return strongSignals.any { lowerDomain.contains(it) }
    }

    private fun matchesAppPackage(appPackages: Set<String>, appName: String?): Boolean {
        if (appPackages.isEmpty()) return true
        val packageName = extractPackageName(appName) ?: return false
        return appPackages.contains(packageName)
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

    private fun mergeRuleTypeScopes(existing: BlockRule, incoming: ParsedRule): BlockRule {
        val mergedDenyallow = if (existing.denyallow.isEmpty() && incoming.denyallow.isEmpty()) {
            emptySet()
        } else if (existing.denyallow.isEmpty()) {
            incoming.denyallow
        } else if (incoming.denyallow.isEmpty()) {
            existing.denyallow
        } else {
            (existing.denyallow + incoming.denyallow).toSet()
        }
        val mergedAppPackages = if (existing.appPackages.isEmpty() && incoming.appPackages.isEmpty()) {
            emptySet()
        } else if (existing.appPackages.isEmpty()) {
            incoming.appPackages
        } else if (incoming.appPackages.isEmpty()) {
            existing.appPackages
        } else {
            (existing.appPackages + incoming.appPackages).toSet()
        }
        val mergedKeyword = if (existing.keywordPattern == null && incoming.keywordPattern == null) {
            null
        } else {
            incoming.keywordPattern ?: existing.keywordPattern
        }
        return existing.copy(
            dnsTypes = mergeDnsTypes(existing.dnsTypes, incoming.dnsTypes),
            excludedDnsTypes = mergeDnsTypes(existing.excludedDnsTypes, incoming.excludedDnsTypes),
            thirdParty = existing.thirdParty || incoming.thirdParty,
            redirect = existing.redirect || incoming.redirect,
            denyallow = mergedDenyallow,
            urlblock = existing.urlblock || incoming.urlblock,
            appPackages = mergedAppPackages,
            keywordPattern = mergedKeyword,
            regexPattern = incoming.regexPattern ?: existing.regexPattern,
            cosmeticSelector = incoming.cosmeticSelector ?: existing.cosmeticSelector,
            removeParams = (existing.removeParams + incoming.removeParams).toSet(),
            cspValue = incoming.cspValue ?: existing.cspValue
        )
    }

    private fun mergeParsedRule(existing: ParsedRule?, incoming: ParsedRule): ParsedRule {
        if (existing == null) return incoming.copy(
            dnsTypes = normalizeDnsTypes(incoming.dnsTypes),
            excludedDnsTypes = normalizeDnsTypes(incoming.excludedDnsTypes)
        )
        val mergedDenyallow = if (existing.denyallow.isEmpty() && incoming.denyallow.isEmpty()) {
            emptySet()
        } else if (existing.denyallow.isEmpty()) {
            incoming.denyallow
        } else if (incoming.denyallow.isEmpty()) {
            existing.denyallow
        } else {
            (existing.denyallow + incoming.denyallow).toSet()
        }
        val mergedAppPackages = if (existing.appPackages.isEmpty() && incoming.appPackages.isEmpty()) {
            emptySet()
        } else if (existing.appPackages.isEmpty()) {
            incoming.appPackages
        } else if (incoming.appPackages.isEmpty()) {
            existing.appPackages
        } else {
            (existing.appPackages + incoming.appPackages).toSet()
        }
        val mergedKeyword = incoming.keywordPattern ?: existing.keywordPattern
        return incoming.copy(
            dnsTypes = mergeDnsTypes(existing.dnsTypes, incoming.dnsTypes),
            excludedDnsTypes = mergeDnsTypes(existing.excludedDnsTypes, incoming.excludedDnsTypes),
            thirdParty = existing.thirdParty || incoming.thirdParty,
            redirect = existing.redirect || incoming.redirect,
            denyallow = mergedDenyallow,
            urlblock = existing.urlblock || incoming.urlblock,
            appPackages = mergedAppPackages,
            keywordPattern = mergedKeyword,
            regexPattern = incoming.regexPattern ?: existing.regexPattern,
            cosmeticSelector = incoming.cosmeticSelector ?: existing.cosmeticSelector,
            removeParams = (existing.removeParams + incoming.removeParams).toSet(),
            cspValue = incoming.cspValue ?: existing.cspValue
        )
    }

    private data class ParsedRules(
        val blockedRules: List<ParsedRule>,
        val exceptionRules: List<ParsedRule>,
        val badfilterRules: List<ParsedRule>
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
        val referenceCount: Int,
        val importedCount: Int,
        val manualCount: Int,
        val regexCount: Int,
        val cosmeticCount: Int,
        val keywordCount: Int
    ) {
        val totalSupportedCount: Int
            get() = referenceCount + importedCount + manualCount

        val totalSavedCount: Int
            get() = totalSupportedCount + regexCount + cosmeticCount
    }

    data class SuspiciousDomainSample(
        val domain: String,
        val count: Int,
        val lastSeenAt: Long,
        val lastAppName: String,
        val lastVendor: String,
        val novelHits: Int
    )

    private data class SuspiciousDomainRecord(
        val count: Int = 0,
        val lastSeenAt: Long = 0L,
        val lastAppName: String = "",
        val lastVendor: String = "",
        val novelHits: Int = 0,
        val lastSampleAt: Long = 0L
    )

    private data class ModifierInfo(
        val dnsTypes: Set<Int>? = null,
        val excludedDnsTypes: Set<Int>? = null,
        val badfilter: Boolean = false,
        val appScoped: Boolean = false,
        val appPackages: Set<String> = emptySet(),
        val domainScoped: Boolean = false,
        val thirdParty: Boolean = false,
        val redirect: Boolean = false,
        val denyallow: Set<String> = emptySet(),
        val urlblock: Boolean = false,
        val fromScoped: Boolean = false,
        val toScoped: Boolean = false,
        val pathScoped: Boolean = false,
        val removeParams: Set<String> = emptySet(),
        val cspValue: String? = null,
        val unsupportedModifiers: List<String> = emptyList(),
        val invalid: Boolean = false
    )

    private data class ParsedRule(
        val domain: String,
        val isException: Boolean,
        val isBadfilter: Boolean = false,
        val dnsTypes: Set<Int>? = null,
        val excludedDnsTypes: Set<Int>? = null,
        val thirdParty: Boolean = false,
        val redirect: Boolean = false,
        val denyallow: Set<String> = emptySet(),
        val urlblock: Boolean = false,
        val appPackages: Set<String> = emptySet(),
        val keywordPattern: String? = null,
        val regexPattern: String? = null,
        val cosmeticSelector: String? = null,
        val removeParams: Set<String> = emptySet(),
        val cspValue: String? = null
    )
}
