package com.HanFeng.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RemoteRuleSourceConfig
import com.HanFeng.model.RuleSource
import java.net.InetAddress
import java.text.SimpleDateFormat
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
    private const val SUSPICIOUS_SAMPLE_DEBOUNCE_MILLIS = 5_000L
    private const val BUILT_IN_RULE_SOURCE_ID = "hanfeng-rules-txt"
    private val defaultRemoteRuleSources = listOf(
        RemoteRuleSourceConfig(
            id = BUILT_IN_RULE_SOURCE_ID,
            name = "寒枫规则",
            url = "https://raw.githubusercontent.com/Han-Feng666/-/d11cb99275785735906c58e3d2c6ebede1819097/%E8%A7%84%E5%88%99.txt",
            authorId = "Han-Feng666",
            enabled = true
        )
    )
    
    // 白名单域名 - 这些域名被拦截会导致 APP 断网
    // 策略：只保护基础服务，不保护纯广告域名
    // 包含主域名和通配符规则，防止 ||domain.com^ 这种规则导致整个域名被拦截
    private val whitelistDomains = setOf(
        // 微信/QQ 核心服务 - 完全保护（确保聊天、支付、小程序正常）
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
        "wa.gtimg.com",
        "qlogo.cn",
        "qlogo.com",
        "qpic.cn",
        "qpic.com",
        "gtimg.com",
        "gtimg.cn",
        "tencent.com",
        "qq.com",
        "weixin.com",
        "wechat.com",
        // 支付相关 - 完全保护
        "qpay.tf.qq.com",
        "qpay.qq.com",
        "tenpay.com",
        "paipai.com",
        // 游戏核心服务 - 完全保护（确保登录、联机、更新正常）
        "gamedl.qq.com",
        "game.qq.com",
        "gamesafe.qq.com",
        "gameinfo.qq.com",
        "gamecenter.qq.com",
        "sso.10.qq.com",
        "open.id.qq.com",
        "ssl.ptlogin2.qq.com",
        "ptlogin2.qq.com",
        "dl.dir.qq.com",
        "dlied1.qq.com",
        "dlied2.qq.com",
        "dlied3.qq.com",
        "dlied4.qq.com",
        "dlied5.qq.com",
        "dlied6.qq.com",
        // 王者荣耀/和平精英等游戏资源
        "gamehelper.com.cn",
        "act.qq.com",
        "imgcache.qq.com",
        // 原神/米哈游游戏
        "miHoYo.com",
        "mihayo.com",
        "yuanshen.com",
        "hoyolab.com",
        "hoyoverse.com",
        "bhsr.com",
        "starrails.com",
        "genshin impact.com",
        // 腾讯其他游戏
        "dnf.qq.com",
        "cf.qq.com",
        "lol.qq.com",
        "speed.qq.com",
        "fifa.qq.com",
        "2k.qq.com",
        // 网易游戏
        "game.163.com",
        "163.com",
        "netease.com",
        "126.net",
        "127.net",
        // 通用 CDN 和下载服务 - 完全保护
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
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        // 隐私保护服务（误报）- 完全保护
        "ghostery.com",
        "ghostery.net",
        // 友盟统计 - 保护主域名和基础日志服务
        "umeng.com",
        "umengcloud.com",
        // 在线视频 CDN - 完全保护（防卡顿/缓冲异常）
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
        "番茄畅听", "tomato.read", "tomatoread", "tomatonovel", "novel.snssdk",
        "七猫小说", "七猫免费小说", "qimao", "kmxs", "wtzw",
        "起点读书", "qidian", "qdreader", "yuewen",
        "qq阅读", "qqreader", "qqread", "weread",
        "书旗小说", "shuqi", "aliwx",
        "掌阅", "ireader", "zhangyue",
        "咪咕阅读", "migu", "cmread",
        "米读小说", "midu", "miduread", "lechuan",
        "纵横小说", "zongheng", "zhread",
        "17k", "17k小说", "book17k",
        "长读小说", "changdu",
        "红果免费短剧", "hongguo", "hongguoapp", "dejian"
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
        "wx.qq.com", "web.weixin.qq.com", "mp.weixin.qq.com",
        "work.weixin.qq.com", "long.weixin.qq.com", "szshort.weixin.qq.com",
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
        "tnc3-bjlgy.bytegecko.com"
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
        "replace",
        "cookie",
        "header",
        "method",
        "jsinject",
        "content",
        "extension"
    )
    private val ignorableAdGuardModifiers = setOf(
        "all",
        "document",
        "subdocument",
        "xmlhttprequest",
        "script",
        "stylesheet",
        "image",
        "media",
        "font",
        "other",
        "popup",
        "object",
        "object-subrequest",
        "ping",
        "websocket",
        "webrtc",
        "empty"
    )

    fun getRules(context: Context): List<BlockRule> {
        cachedRules?.let { return it }
        synchronized(cacheLock) {
            cachedRules?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_RULES, "[]") ?: "[]"
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
                prefs.edit()
                    .putString(KEY_RULES, gson.toJson(rules))
                    .putInt(KEY_RULE_COUNT, rules.size)
                    .apply()
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

    fun addRules(context: Context, rawInput: String, source: RuleSource, allowWhitelistDomains: Boolean = false): List<BlockRule> {
        val current = getRules(context).toMutableList()
        val existingDomains = current.mapTo(linkedSetOf()) { it.domain }
        val added = mutableListOf<BlockRule>()
        parseManualInput(rawInput).forEach { domain ->
            if (!allowWhitelistDomains && isWhitelistedDomain(domain)) return@forEach
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

    fun addRules(context: Context, domains: Collection<String>, source: RuleSource, allowWhitelistDomains: Boolean = false): List<BlockRule> {
        if (domains.isEmpty()) return emptyList()
        val normalizedDomains = domains.mapNotNull(::sanitizeDomain)
            .filter { allowWhitelistDomains || !isWhitelistedDomain(it) }
            .distinct()
        if (normalizedDomains.isEmpty()) return emptyList()
        val current = getRules(context).toMutableList()
        val existingDomains = current.mapTo(linkedSetOf()) { it.domain }
        val added = mutableListOf<BlockRule>()
        normalizedDomains.forEach { domain ->
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

    fun getRemoteRuleSources(context: Context): List<RemoteRuleSourceConfig> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_REMOTE_RULE_SOURCES, null)
        if (json.isNullOrBlank()) {
            saveRemoteRuleSources(context, defaultRemoteRuleSources)
            return defaultRemoteRuleSources
        }
        val type = object : TypeToken<List<RemoteRuleSourceConfig>>() {}.type
        val stored = runCatching { gson.fromJson<List<RemoteRuleSourceConfig>>(json, type) }
            .getOrNull()
            .orEmpty()
            .mapNotNull(::sanitizeRemoteRuleSource)
            .filterNot(::isLegacyBuiltInRemoteRuleSource)
        if (stored.isEmpty()) {
            saveRemoteRuleSources(context, defaultRemoteRuleSources)
            return defaultRemoteRuleSources
        }
        val merged = mergeDefaultRemoteRuleSources(stored)
        if (merged != stored) {
            saveRemoteRuleSources(context, merged)
        }
        return merged
    }

    fun saveRemoteRuleSources(context: Context, sources: List<RemoteRuleSourceConfig>) {
        val normalizedSources = sources.mapNotNull(::sanitizeRemoteRuleSource)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMOTE_RULE_SOURCES, gson.toJson(normalizedSources.sortedBy { it.name.lowercase() }))
            .apply()
    }

    fun updateRemoteRuleSource(context: Context, updated: RemoteRuleSourceConfig) {
        val current = getRemoteRuleSources(context)
        val next = current.map { source -> if (source.id == updated.id) updated else source }
        saveRemoteRuleSources(context, next)
    }

    fun addRemoteRuleSource(context: Context, source: RemoteRuleSourceConfig) {
        val current = getRemoteRuleSources(context)
        val normalizedUrl = source.url.trim()
        val exists = current.any {
            it.id == source.id || it.url.equals(normalizedUrl, ignoreCase = true)
        }
        if (exists) return
        saveRemoteRuleSources(context, current + source.copy(url = normalizedUrl))
    }

    fun removeRemoteRuleSource(context: Context, sourceId: String) {
        if (sourceId.trim() == BUILT_IN_RULE_SOURCE_ID) return
        val current = getRemoteRuleSources(context)
        saveRemoteRuleSources(context, current.filterNot { it.id == sourceId })
    }

    fun isBuiltInRemoteRuleSource(sourceId: String): Boolean {
        return sourceId.trim() == BUILT_IN_RULE_SOURCE_ID
    }

    fun getRulesForRemoteSource(context: Context, sourceId: String): List<BlockRule> {
        return getRules(context).filter { it.remoteSourceId == sourceId }
    }

    fun removeRulesForRemoteSource(context: Context, sourceId: String): Int {
        val current = getRules(context)
        val normalizedSourceId = sourceId.trim()
        val remaining = current.filterNot { it.remoteSourceId?.trim() == normalizedSourceId }
        val removedCount = current.size - remaining.size
        if (removedCount > 0) {
            save(context, remaining)
        }
        return removedCount
    }

    fun replaceRulesForRemoteSource(context: Context, sourceId: String, content: String, allowWhitelistDomains: Boolean = false): Int {
        val current = getRules(context).filterNot { it.remoteSourceId == sourceId }.toMutableList()
        val existingRuleKeys = current.mapTo(linkedSetOf()) { buildRuleIdentityKey(it) }
        val parsed = parseImportLines(content)
        val filteredBlockedRules = parsed.blockedRules.filter { blockedRule ->
            allowWhitelistDomains || !isWhitelistedDomain(blockedRule.domain)
        }
        val added = mutableListOf<BlockRule>()
        filteredBlockedRules.forEach { blockedRule ->
            val ruleKey = buildParsedRuleKey(
                domain = blockedRule.domain,
                dnsTypes = blockedRule.dnsTypes,
                excludedDnsTypes = blockedRule.excludedDnsTypes,
                badfilter = blockedRule.isBadfilter,
                firstParty = blockedRule.firstParty,
                pathPattern = blockedRule.pathPattern,
                ipCidr = blockedRule.ipCidr,
                regexPattern = blockedRule.regexPattern,
                cosmeticSelector = blockedRule.cosmeticSelector,
                removeParams = blockedRule.removeParams,
                cspValue = blockedRule.cspValue,
                keywordPattern = blockedRule.keywordPattern,
                domainConstraints = blockedRule.domainConstraints,
                appPackages = blockedRule.appPackages,
                destinationPorts = blockedRule.destinationPorts,
                sourcePorts = blockedRule.sourcePorts,
                denyallow = blockedRule.denyallow,
                remoteSourceId = sourceId,
                cosmeticException = blockedRule.isException
            )
            if (!existingRuleKeys.add(ruleKey)) return@forEach
            added += BlockRule(
                id = UUID.randomUUID().toString(),
                domain = blockedRule.domain,
                vendor = classifyVendor(context, blockedRule.domain),
                source = RuleSource.IMPORTED,
                dnsTypes = normalizeDnsTypes(blockedRule.dnsTypes),
                excludedDnsTypes = normalizeDnsTypes(blockedRule.excludedDnsTypes),
                thirdParty = blockedRule.thirdParty,
                firstParty = blockedRule.firstParty,
                redirect = blockedRule.redirect,
                domainConstraints = blockedRule.domainConstraints,
                denyallow = blockedRule.denyallow,
                urlblock = blockedRule.urlblock,
                appPackages = blockedRule.appPackages,
                destinationPorts = blockedRule.destinationPorts,
                sourcePorts = blockedRule.sourcePorts,
                keywordPattern = blockedRule.keywordPattern,
                pathPattern = blockedRule.pathPattern,
                ipCidr = blockedRule.ipCidr,
                regexPattern = blockedRule.regexPattern,
                cosmeticSelector = blockedRule.cosmeticSelector,
                cosmeticException = blockedRule.isException,
                removeParams = blockedRule.removeParams,
                cspValue = blockedRule.cspValue,
                remoteSourceId = sourceId
            )
        }
        current += added
        save(context, applyCosmeticExceptionRules(current, parsed.exceptionRules))
        return added.size
    }

    fun filterRemoteSourceNonAds(context: Context, sourceId: String): Int {
        val sourceRules = getRules(context).filter { it.remoteSourceId == sourceId }
        if (sourceRules.isEmpty()) return 0
        val removableIds = getRemoteSourceNonAdCandidates(context, sourceId).map { it.rule.id }.toSet()
        if (removableIds.isEmpty()) return 0
        removeByIds(context, removableIds)
        return removableIds.size
    }

    fun getRemoteSourceNonAdCandidates(context: Context, sourceId: String): List<RemoteRuleRemovalCandidate> {
        val sourceRules = getRules(context).filter { it.remoteSourceId == sourceId }
        if (sourceRules.isEmpty()) return emptyList()
        return sourceRules.mapNotNull { rule -> explainRemoteSourceNonAdCandidate(context, rule) }
    }

    fun importRules(context: Context, content: String, source: RuleSource = RuleSource.IMPORTED, allowWhitelistDomains: Boolean = false): Int {
        val current = getRules(context).toMutableList()
        val currentByDomain = current
            .filter { it.regexPattern == null && it.cosmeticSelector == null }
            .associateBy { it.domain }
            .toMutableMap()
        val existingRuleKeys = current.mapTo(linkedSetOf()) { buildRuleIdentityKey(it) }
        val parsed = parseImportLines(content)
        
        // 过滤会导致断网的白名单域名（使用智能匹配）
        val filteredBlockedRules = parsed.blockedRules.filter { blockedRule ->
            allowWhitelistDomains || !isWhitelistedDomain(blockedRule.domain)
        }

        parsed.badfilterRules.forEach { badfilter ->
            val existing = currentByDomain[badfilter.domain] ?: return@forEach
            subtractDnsTypeScope(existing, badfilter.dnsTypes, badfilter.excludedDnsTypes)?.let {
                currentByDomain[badfilter.domain] = it
            } ?: currentByDomain.remove(badfilter.domain)
        }

        filteredBlockedRules.forEach { blockedRule ->
            val ruleKey = buildParsedRuleKey(
                domain = blockedRule.domain,
                dnsTypes = blockedRule.dnsTypes,
                excludedDnsTypes = blockedRule.excludedDnsTypes,
                badfilter = blockedRule.isBadfilter,
                firstParty = blockedRule.firstParty,
                pathPattern = blockedRule.pathPattern,
                ipCidr = blockedRule.ipCidr,
                regexPattern = blockedRule.regexPattern,
                cosmeticSelector = blockedRule.cosmeticSelector,
                removeParams = blockedRule.removeParams,
                cspValue = blockedRule.cspValue,
                keywordPattern = blockedRule.keywordPattern,
                domainConstraints = blockedRule.domainConstraints,
                appPackages = blockedRule.appPackages,
                destinationPorts = blockedRule.destinationPorts,
                sourcePorts = blockedRule.sourcePorts,
                denyallow = blockedRule.denyallow,
                remoteSourceId = null,
                cosmeticException = blockedRule.isException
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
                     vendor = classifyVendorFromHints(context, blockedRule.domain, *blockedRule.vendorHints.toTypedArray()),
                     source = source,
                     dnsTypes = normalizeDnsTypes(blockedRule.dnsTypes),
                     excludedDnsTypes = normalizeDnsTypes(blockedRule.excludedDnsTypes),
                     thirdParty = blockedRule.thirdParty,
                     firstParty = blockedRule.firstParty,
                      redirect = blockedRule.redirect,
                     domainConstraints = blockedRule.domainConstraints,
                     denyallow = blockedRule.denyallow,
                     urlblock = blockedRule.urlblock,
                     appPackages = blockedRule.appPackages,
                     destinationPorts = blockedRule.destinationPorts,
                     sourcePorts = blockedRule.sourcePorts,
                     keywordPattern = blockedRule.keywordPattern,
                     pathPattern = blockedRule.pathPattern,
                     ipCidr = blockedRule.ipCidr,
                     regexPattern = blockedRule.regexPattern,
                     cosmeticSelector = blockedRule.cosmeticSelector,
                     cosmeticException = blockedRule.isException,
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
        val finalRules = applyCosmeticExceptionRules(mergedRules, parsed.exceptionRules).sortedBy { it.domain }
        save(context, finalRules)
        return finalRules.size
    }

    fun removeByIds(context: Context, ids: Set<String>) {
        save(context, getRules(context).filterNot { ids.contains(it.id) })
    }

    fun removeRulesByIds(context: Context, ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val normalizedIds = ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalizedIds.isEmpty()) return 0
        val current = getRules(context)
        val remaining = current.filterNot { normalizedIds.contains(it.id.trim()) }
        val removedCount = current.size - remaining.size
        if (removedCount > 0) {
            save(context, remaining)
        }
        return removedCount
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
        val remaining = current.filterNot { rule ->
            normalizedIds.contains(rule.id.trim()) || identityKeys.contains(buildRuleIdentityKey(rule))
        }
        val removedCount = current.size - remaining.size
        if (removedCount > 0) {
            save(context, remaining)
        }
        return removedCount
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
        val lowerDomain = normalized.lowercase()
        val matched = buildDomainCandidates(normalized)
            .flatMap { candidate -> ruleMap[candidate].orEmpty().asSequence() }
            .any {
                ruleMatches(it, qType, appName) &&
                    matchesPortScope(it.destinationPorts, destinationPort) &&
                    matchesPortScope(it.sourcePorts, sourcePort)
            } ||
            getRegexRules(context).any { matchesRegexRule(it, normalized) } ||
            getKeywordRules(context).any { rule ->
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
        val matched = buildDomainCandidates(normalized)
            .flatMap { candidate -> ruleMap[candidate].orEmpty().asSequence() }
            .any { ruleMatches(it, qType, null) && it.destinationPorts.isEmpty() && it.sourcePorts.isEmpty() } ||
            getRegexRules(context).any { matchesRegexRule(it, normalized) }
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
        sourcePort: Int? = null
    ): Boolean {
        val normalizedHost = sanitizeDomain(host) ?: return false
        val ruleMap = getRuleMap(context)
        val fullUrl = "$host$path".lowercase()
        val matched = buildDomainCandidates(normalizedHost)
            .flatMap { candidate -> ruleMap[candidate].orEmpty().asSequence() }
            .any { rule ->
                if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain)) return@any false
                if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@any false
                if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@any false
                if (rule.keywordPattern != null) {
                    return@any fullUrl.contains(rule.keywordPattern)
                }
                if (!rule.pathPattern.isNullOrBlank() && path.isNotBlank()) {
                    return@any pathMatchesPattern(path, rule.pathPattern)
                }
                if (rule.urlblock && path.isNotBlank()) {
                    return@any looksLikeSuspiciousPath(path)
                }
                return@any rule.appPackages.isEmpty() || matchesAppPackage(rule.appPackages, appName)
            } || getRegexRules(context).any { matchesRegexRule(it, fullUrl) }
        if (matched) return true
        if (isWhitelistedDomain(normalizedHost)) return false
        return false
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
        val normalized = sanitizeDomain(domain) ?: return false
        if (buildDomainCandidates(normalized).any(bypassProtectionDomains::contains)) return true
        val lower = normalized.lowercase()
        val bypassKeywords = listOf(
            "httpdns",
            "doh",
            "doq",
            "dot",
            "dns-query",
            "dnsquery",
            "resolver",
            "encrypted-dns"
        )
        val trustedDnsHints = listOf(
            "alidns",
            "aliyuncs",
            "cloudflare-dns",
            "nextdns",
            "adguard-dns",
            "quad9",
            "opendns",
            "cleanbrowsing",
            "umbrella",
            "dns.sb",
            "dns0",
            "google",
            "baidu"
        )
        return bypassKeywords.any(lower::contains) && trustedDnsHints.any(lower::contains)
    }

    fun isGameCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return gameCoreDomains.contains(normalized) || gameCoreDomains.any { normalized.endsWith(".$it") }
    }

    fun isSocialCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return socialCoreDomains.contains(normalized) || socialCoreDomains.any { normalized.endsWith(".$it") }
    }

    fun isMediaCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return mediaCoreDomains.contains(normalized) || mediaCoreDomains.any { normalized.endsWith(".$it") }
    }

    fun shouldProtectMediaTraffic(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        if (!isMediaCoreDomain(normalized)) return false
        if (looksLikeAdDomain(normalized)) return false
        return true
    }

    fun isBusinessCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return businessCoreDomains.contains(normalized) || businessCoreDomains.any { normalized.endsWith(".$it") }
    }

    fun shouldProtectBusinessTraffic(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        if (!isBusinessCoreDomain(normalized)) return false
        if (looksLikeAdDomain(normalized)) return false
        return true
    }

    private fun checkDomainWhitelist(lowerDomain: String): Boolean {
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
        return buildDomainCandidates(normalized)
            .flatMap { candidate -> ruleMap[candidate].orEmpty().asSequence() }
            .filter {
                ruleMatches(it, qType, appName) &&
                    matchesPortScope(it.destinationPorts, destinationPort) &&
                    matchesPortScope(it.sourcePorts, sourcePort)
            }
            .firstOrNull() ?: getRegexRules(context).firstOrNull { matchesRegexRule(it, normalized) }
    }

    fun getRequestRewriteDirectives(context: Context, host: String, path: String, appName: String? = null, requestDomain: String? = null): RequestRewriteDirectives {
        val normalizedHost = sanitizeDomain(host) ?: return RequestRewriteDirectives()
        val matchedRules = buildDomainCandidates(normalizedHost)
            .flatMap { candidate -> getRuleMap(context)[candidate].orEmpty().asSequence() }
            .filter { ruleMatches(it, null, appName, normalizedHost, requestDomain) }
        val matchedRegexRules = getRegexRules(context).filter { matchesRegexRule(it, "$normalizedHost$path") || matchesRegexRule(it, normalizedHost) }
        val allRules = (matchedRules + matchedRegexRules).distinctBy { it.id }
        val removeParams = allRules.flatMap { it.removeParams }.toSet()
        val cspValue = allRules.mapNotNull { it.cspValue }.firstOrNull()
        return RequestRewriteDirectives(removeParams = removeParams, cspValue = cspValue)
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
                ruleMatches(it, null, appName, normalizedHost, requestDomain) &&
                    matchesPortScope(it.destinationPorts, destinationPort) &&
                    matchesPortScope(it.sourcePorts, sourcePort)
            }
            .any { rule ->
                !rule.pathPattern.isNullOrBlank() ||
                    rule.urlblock ||
                    rule.removeParams.isNotEmpty() ||
                    !rule.cspValue.isNullOrBlank() ||
                    !rule.cosmeticSelector.isNullOrBlank() ||
                    (!rule.keywordPattern.isNullOrBlank() && fullUrl.contains(rule.keywordPattern))
            }
        if (matchedHostRules) return true
        return getRegexRules(context).any { matchesRegexRule(it, fullUrl) }
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
        val normalized = sanitizeDomain(domain) ?: return
        if (hasMatchingRule(context, normalized)) return
        val normalizedAppName = normalizeSampleAppName(appName)
        if (ShizukuAdControlCatalog.isManagedPromoAppHint(normalizedAppName)) return
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
            refererDomain = sanitizeDomain(refererDomain.orEmpty()) ?: previous?.refererDomain.orEmpty(),
            lastSampleAt = now
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
            .filter {
                suspiciousDomainConfidenceScore(
                    domain = it.domain,
                    vendor = it.lastVendor,
                    novelHits = it.novelHits,
                    count = it.count,
                    appName = it.lastAppName,
                    dnsHits = it.dnsHits,
                    aliasHits = it.aliasHits,
                    tlsSniHits = it.tlsSniHits,
                    httpHits = it.httpHits,
                    pathHits = it.pathHits,
                    redirectHits = it.redirectHits,
                    appSignalHits = it.appSignalHits,
                    vendorSignalHits = it.vendorSignalHits,
                    confidenceBoost = it.confidenceBoost,
                    refererDomain = it.refererDomain
                ) >= 6
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
        if (isBypassProtectionDomain(normalized)) score += 5
        if (looksLikeAdDomain(normalized)) score += 4
        if (looksLikePushRecommendationAdDomain(normalized)) score += 3
        if (hasAggressiveNovelAdSignal(normalized)) score += 3
        if (normalizedVendor == GENERIC_AD_VENDOR) score += 3
        if (normalizedVendor in highConfidenceAdSdkVendors) score += 2
        if (novelHits >= 3) score += 3 else if (novelHits >= 1) score += 2
        if (count >= 8) score += 2 else if (count >= 3) score += 1
        if (dnsHits >= 5) score += 2 else if (dnsHits >= 2) score += 1
        if (aliasHits >= 2) score += 2 else if (aliasHits >= 1) score += 1
        if (tlsSniHits >= 2) score += 2 else if (tlsSniHits >= 1) score += 1
        if (httpHits >= 2) score += 2 else if (httpHits >= 1) score += 1
        if (pathHits >= 2) score += 2 else if (pathHits >= 1) score += 1
        if (redirectHits >= 1) score += 2
        if (appSignalHits >= 2) score += 1
        if (vendorSignalHits >= 2) score += 1
        if (isNovelAppHint(appName)) score += 1
        if (isAggressiveAdAppHint(appName)) score += 1
        if (!refererDomain.isNullOrBlank()) score += 1
        score += confidenceBoost.coerceIn(0, 4)
        return score
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
        if (isSocialCoreDomain(normalized)) return false
        if (shouldProtectMediaTraffic(normalized)) return false
        if (shouldProtectBusinessTraffic(normalized)) return false
        if (isProtectedNovelAppDomain(normalized) && !looksLikeAdDomain(normalized)) return false
        val normalizedVendor = normalizeVendorName(vendor)
        if (isBypassProtectionDomain(normalized)) return true
        if (looksLikeAdDomain(normalized)) return true
        if (looksLikeAdSdkInfraDomain(normalized, normalizedVendor)) return true
        if (looksLikePushRecommendationAdDomain(normalized)) return true
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
        return suspiciousDomainConfidenceScore(
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
            refererDomain = refererDomain
        ) >= 6
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
        val communityIdentifiers = listOf("coolapk", "酷安")
        return communityIdentifiers.any { identifierMatches(text, normalized, it) }
    }

    fun isAggressiveAdAppHint(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        if (ShizukuAdControlCatalog.isManagedPromoAppHint(value)) return true
        val normalized = text.replace(alphanumericCnRegex, "")
        val identifiers = listOf(
            "小说", "阅读", "读书", "番茄", "七猫", "书旗", "掌阅", "起点", "纵横", "酷安",
            "资讯", "新闻", "头条", "浏览器", "短视频", "video", "reader", "novel", "comic"
        )
        return identifiers.any { identifierMatches(text, normalized, it) }
    }

    private fun looksLikePushRecommendationAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        val normalizedTokens = lower.replace(alphanumericRegex, "")
        val pushSignals = listOf("push", "pushad", "adpush", "notify", "notification", "message", "msg", "inbox")
        val recommendationSignals = listOf("recommend", "recommendation", "feed", "stream", "timeline", "discover")
        val adSignals = listOf("ad", "ads", "promo", "promotion", "banner", "material", "creative", "offer", "offerwall")
        val hasPushOrRecommend = pushSignals.any { keywordMatches(lower, normalizedTokens, it) } ||
            recommendationSignals.any { keywordMatches(lower, normalizedTokens, it) }
        if (!hasPushOrRecommend) return false
        return adSignals.any { keywordMatches(lower, normalizedTokens, it) }
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
        val normalized = sanitizeDomain(domain) ?: return false
        val lower = normalized.lowercase()
        val normalizedTokens = lower.replace(alphanumericRegex, "")
        val normalizedVendor = normalizeVendorName(vendor)
        if (normalizedVendor in highConfidenceAdSdkVendors) {
            return true
        }
        val sdkInfraSignals = listOf(
            "adsdk", "sdkad", "adservice", "adserver", "adnetwork", "adplatform", "admanager",
            "mediation", "waterfall", "bidding", "auction", "bidfloor", "bidder", "ssp", "dsp",
            "adx", "rtb", "exchange", "offerwall", "rewardvideo", "interstitial", "fullscreenad",
            "nativead", "feedad", "splashad", "startupad", "launchad", "open_screen", "material",
            "creative", "slotid", "placement", "templateid", "showurl", "clickurl", "monitorurl",
            "impression", "playable", "endcard", "tracking", "analytics", "stat", "report", "monetize"
        )
        if (sdkInfraSignals.any { keywordMatches(lower, normalizedTokens, it) }) return true
        val sdkVendorSignals = listOf(
            "pangle", "gromore", "pangolin", "csj", "gdt", "youlianghui", "guangdiantong", "sigmob",
            "mintegral", "mobvista", "mbridge", "applovin", "applvn", "maxads", "ironsource", "ironsrc",
            "unityads", "unity3d", "vungle", "liftoff", "chartboost", "inmobi", "aerserv", "topon",
            "anythink", "tradplus", "tpbid", "beizi", "bzadx", "adscope", "aiclk", "youmi", "adwo",
            "vpon", "pubmatic", "openx", "taboola", "outbrain", "adcolony", "ogury", "fyber",
            "inneractive", "digitalturbine", "colossusssp", "smaato", "tapjoy", "audiencenetwork"
        )
        return sdkVendorSignals.any { keywordMatches(lower, normalizedTokens, it) }
    }

    fun isNovelContentDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        return novelContentApiDomains.contains(normalized) || novelContentApiDomains.any { normalized.endsWith(".$it") }
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
        // 排除明显的广告子域名
        val adSubdomainPatterns = listOf("ad", "ads", "adserver", "adtrack", "adlog", "adx", "adv", "banner", "splash", "promotion", "promo", "marketing", "track", "tracking", "log", "logger", "stat", "stats", "analytics")
        if (adSubdomainPatterns.any { lower.startsWith("$it.") || lower.startsWith("$it-") || lower == it }) return false
        // 游戏核心服务不保护（避免误拦截）
        if (isGameCoreDomain(normalized)) return false
        // 社交核心服务不保护（避免误拦截）
        if (isSocialCoreDomain(normalized)) return false
        return buildDomainCandidates(normalized).any(novelAppProtectedSuffixes::contains)
    }

    fun hasMatchingRule(context: Context, domain: String): Boolean {
        return findMatchingRule(context, domain) != null
    }

    private fun hasMatchingRulePlaceholder(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        return buildDomainCandidates(normalized).any(novelAggressiveExactDomains::contains)
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
        val existingDomains = existingRules.map(BlockRule::domain).toMutableSet()
        val existingRuleKeys = existingRules.mapTo(linkedSetOf()) { buildRuleIdentityKey(it) }
        val simulatedDomains = existingDomains.toMutableSet()
        val seenBlocked = linkedSetOf<String>()
        val seenExceptions = linkedSetOf<String>()
        val unsupportedLines = mutableListOf<String>()
        val invalidLines = mutableListOf<String>()
        val whitelistConflictLines = mutableListOf<String>()
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

        var lineContext = RuleLineContext()
        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                blankOrCommentLines += 1
                lineContext = parseRuleLineContext(trimmed)
                return@forEach
            }
            val line = rawLine.trim()
            when {
                line.isBlank() || line.startsWith("#") || line.startsWith("!") -> {
                    blankOrCommentLines += 1
                }

                else -> {
                    val parsedRules = parseRuleLine(rawLine, lineContext)
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
                        if (!parsedRule.isException && !parsedRule.isBadfilter && isWhitelistedDomain(parsedRule.domain)) {
                            whitelistConflictLines += line
                        }
                        val ruleKey = buildParsedRuleKey(
                            parsedRule.domain,
                            parsedRule.dnsTypes,
                            parsedRule.excludedDnsTypes,
                            parsedRule.isBadfilter,
                            parsedRule.firstParty,
                            parsedRule.pathPattern,
                            parsedRule.ipCidr,
                            parsedRule.regexPattern,
                            parsedRule.cosmeticSelector,
                            parsedRule.removeParams,
                            parsedRule.cspValue,
                            parsedRule.keywordPattern,
                            parsedRule.domainConstraints,
                            parsedRule.appPackages,
                            parsedRule.destinationPorts,
                            parsedRule.sourcePorts,
                            parsedRule.denyallow,
                            null
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
                        val vendor = classifyVendorFromHints(context, parsedRule.domain, *parsedRule.vendorHints.toTypedArray())
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
            whitelistConflictRules = whitelistConflictLines.distinct().size,
            sampleWhitelistConflictLines = whitelistConflictLines.distinct().take(10),
            sampleUnsupportedLines = unsupportedLines.distinct().take(10),
            sampleInvalidLines = invalidLines.distinct().take(10)
        )
    }

    private fun parseImportLines(content: String): ParsedRules {
        val blocked = linkedMapOf<String, ParsedRule>()
        val exceptions = linkedMapOf<String, ParsedRule>()
        val badfilters = linkedMapOf<String, ParsedRule>()

        var lineContext = RuleLineContext()
        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                lineContext = parseRuleLineContext(trimmed)
                return@forEach
            }
            parseRuleLine(rawLine, lineContext).forEach { parsedRule ->
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
        var lineContext = RuleLineContext()
        rawInput.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) return@forEach
            if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                lineContext = parseRuleLineContext(trimmed)
                return@forEach
            }
            val parsedRules = parseRuleLine(trimmed, lineContext)
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

    fun findWhitelistConflictsInManualInput(rawInput: String): List<String> {
        return parseManualInput(rawInput)
            .filter(::isWhitelistedDomain)
            .distinct()
    }

    fun removeWhitelistConflictLines(content: String): String {
        val sanitizedLines = mutableListOf<String>()
        var lineContext = RuleLineContext()
        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                lineContext = parseRuleLineContext(trimmed)
                sanitizedLines += rawLine
                return@forEach
            }
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                sanitizedLines += rawLine
                return@forEach
            }
            val parsedRules = parseRuleLine(rawLine, lineContext)
            val hasWhitelistConflict = parsedRules.any { parsedRule ->
                !parsedRule.isException && !parsedRule.isBadfilter && isWhitelistedDomain(parsedRule.domain)
            }
            if (!hasWhitelistConflict) {
                sanitizedLines += rawLine
            }
        }
        return sanitizedLines.joinToString("\n")
    }

    private fun parseRuleLine(rawLine: String, lineContext: RuleLineContext = RuleLineContext()): List<ParsedRule> {
        val line = stripInlineRuleComment(rawLine)
        if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) return emptyList()
        val normalizedLine = stripYamlListPrefix(line)
        parseCompositeRule(normalizedLine, lineContext)?.let { return it }
        parseCosmeticRule(normalizedLine)?.let { return listOf(it.withVendorHints(lineContext.vendorHints)) }
        parseRegexRule(normalizedLine)?.let { return listOf(it.withVendorHints(lineContext.vendorHints)) }
        parseInlinePayloadRule(normalizedLine, lineContext)?.let { return it }
        parseClashRule(normalizedLine)?.let { return it.map { rule -> rule.withVendorHints(lineContext.vendorHints) } }
        parseSurgeWildcardDomain(normalizedLine)?.let { return it.map { rule -> rule.withVendorHints(lineContext.vendorHints) } }
        parseLoonKeywordRule(normalizedLine)?.let { return it.map { rule -> rule.withVendorHints(lineContext.vendorHints) } }
        parseLoonUrlRegex(normalizedLine)?.let { return it.map { rule -> rule.withVendorHints(lineContext.vendorHints) } }
        parseAbpDomainRule(normalizedLine)?.let { return it.map { rule -> rule.withVendorHints(lineContext.vendorHints) } }
        parseShadowrocketRule(normalizedLine)?.let { return it.map { rule -> rule.withVendorHints(lineContext.vendorHints) } }
        parseSurgeUrlKeyword(normalizedLine)?.let { return it.map { rule -> rule.withVendorHints(lineContext.vendorHints) } }

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
                redirect = modifierInfo.redirect,
                domainConstraints = modifierInfo.domainConstraints,
                denyallow = modifierInfo.denyallow,
                urlblock = modifierInfo.urlblock,
                appPackages = modifierInfo.appPackages,
                destinationPorts = modifierInfo.destinationPorts,
                sourcePorts = modifierInfo.sourcePorts,
                keywordPattern = keywordPattern,
                pathPattern = pathPattern,
                ipCidr = null,
                regexPattern = null,
                cosmeticSelector = null,
                removeParams = modifierInfo.removeParams,
                cspValue = modifierInfo.cspValue,
                vendorHints = lineContext.vendorHints
            )
        }
    }

    private fun parseRuleLineContext(line: String): RuleLineContext {
        val body = line.substringAfter('=', "").trim()
        if (body.isBlank()) return RuleLineContext()
        val hintPart = body.substringBefore(';').substringBefore('；').trim()
        val hints = hintPart
            .split(',', '|', '/', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && (it.contains('.') || it.length >= 3) }
            .toSet()
        return if (hints.isEmpty()) RuleLineContext() else RuleLineContext(vendorHints = hints)
    }

    private fun ParsedRule.withVendorHints(vendorHints: Set<String>): ParsedRule {
        if (vendorHints.isEmpty()) return this
        if (this.vendorHints.isNotEmpty()) return copy(vendorHints = this.vendorHints + vendorHints)
        return copy(vendorHints = vendorHints)
    }

    private fun parseCompositeRule(line: String, lineContext: RuleLineContext): List<ParsedRule>? {
        val trimmed = line.trim()
        val matchedPrefix = listOf("AND,", "AND:", "AND(", "OR,", "OR:", "OR(", "NOT,", "NOT:", "NOT(")
            .firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
            ?: return null
        val operator = matchedPrefix.trimEnd(',', ':', '(').uppercase()
        val body = trimmed.substring(matchedPrefix.length)
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .removeSuffix(")")
        if (body.isBlank()) return emptyList()
        val parts = splitCompositeRuleParts(body)
        if (parts.isEmpty()) return emptyList()
        val parsed = parts.flatMap { part -> parseRuleLine(part, lineContext) }
        if (parsed.isEmpty()) return emptyList()
        return when (operator) {
            "AND" -> parsed.filter(::isSafelyActionableAdRule)
            "OR" -> parsed.filter(::isSafelyActionableAdRule)
            else -> parsed.filter(::isSafelyActionableAdRule)
        }
    }

    private fun splitCompositeRuleParts(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var bracketDepth = 0
        var quote: Char? = null
        body.forEach { ch ->
            when {
                quote != null -> {
                    current.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    current.append(ch)
                }
                ch == '[' || ch == '(' || ch == '{' -> {
                    bracketDepth += 1
                    current.append(ch)
                }
                ch == ']' || ch == ')' || ch == '}' -> {
                    if (bracketDepth > 0) bracketDepth -= 1
                    current.append(ch)
                }
                ch == ',' && bracketDepth == 0 -> {
                    val item = current.toString().trim()
                    if (item.isNotBlank()) parts += item
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotBlank()) parts += tail
        return parts
    }

    private fun isSafelyActionableAdRule(rule: ParsedRule): Boolean {
        if (rule.domain == "*") {
            return rule.destinationPorts.isNotEmpty() ||
                rule.sourcePorts.isNotEmpty() ||
                rule.appPackages.isNotEmpty()
        }
        if (rule.ipCidr != null) return true
        if (rule.regexPattern != null || rule.keywordPattern != null || rule.pathPattern != null) return true
        return looksLikeAdDomain(rule.domain) || looksLikeBypassProtectionDomain(rule.domain)
    }

    private fun parseInlinePayloadRule(line: String, lineContext: RuleLineContext): List<ParsedRule>? {
        val trimmed = line.trim()
        val payloadPrefix = when {
            trimmed.startsWith("payload:", ignoreCase = true) -> "payload:"
            trimmed.startsWith("payload=", ignoreCase = true) -> "payload="
            else -> null
        } ?: return null
        val payloadBody = trimmed.substring(payloadPrefix.length).trim()
        if (payloadBody.isBlank()) return emptyList()
        val payloadItems = if (payloadBody.startsWith("[") && payloadBody.endsWith("]")) {
            extractInlinePayloadItems(payloadBody)
        } else {
            listOf(payloadBody)
        }
        return payloadItems.flatMap { item -> parseRuleLine(item, lineContext) }
    }

    private fun extractInlinePayloadItems(payloadBody: String): List<String> {
        val quotedItems = Regex("\"([^\"]+)\"|'([^']+)'")
            .findAll(payloadBody)
            .mapNotNull { match -> match.groups[1]?.value ?: match.groups[2]?.value }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (quotedItems.isNotEmpty()) return quotedItems
        val yamlStyleItems = payloadBody.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-") }
            .map { it.removePrefix("-").trim().removeSurrounding("\"").removeSurrounding("'") }
            .filter { it.isNotBlank() }
            .toList()
        if (yamlStyleItems.isNotEmpty()) return yamlStyleItems
        return payloadBody.removePrefix("[").removeSuffix("]")
            .split(',')
            .map {
                it.trim()
                    .removePrefix("-")
                    .trim()
                    .removeSurrounding("(", ")")
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
            }
            .filter { it.isNotBlank() }
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
        val value = line.substring(colonIndex + 1).trim()
        if (value.isBlank()) return null
        return when (prefix.lowercase()) {
            "domain", "full", "full-domain", "domain-full", "host", "hostname", "hostname-full", "host-full", "domain-exact", "host-exact", "hostname-exact" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-suffix", "domain-suffixes", "host-suffix", "hostname-suffix", "suffix" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-wildcard", "host-wildcard", "hostname-wildcard" -> {
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
            "ip-cidr", "ip-cidr6" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "dest-port", "dst-port" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, destinationPorts = setOf(port)))
            }
            "src-port" -> {
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
            "network", "inbound", "protocol" -> {
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
            "DOMAIN-SUFFIX", "DOMAIN-SUFFIXES", "HOST-SUFFIX", "HOSTNAME-SUFFIX" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN", "HOST", "HOSTNAME", "HOST-FULL", "HOSTNAME-FULL", "DOMAIN-FULL", "DOMAIN-EXACT", "HOST-EXACT", "HOSTNAME-EXACT", "DOMAIN-SET" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN-KEYWORD", "HOST-KEYWORD", "HOSTNAME-KEYWORD" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "DOMAIN-WILDCARD", "HOST-WILDCARD", "HOSTNAME-WILDCARD" -> {
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
            "DEST-PORT", "DST-PORT" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, destinationPorts = setOf(port)))
            }
            "SRC-PORT" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, sourcePorts = setOf(port)))
            }
            "USER-AGENT", "UA" -> {
                emptyList()
            }
            "IP-CIDR", "IP-CIDR6" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "RULE-SET", "RULESET" -> {
                val adLikeDomain = findAdLikeStructuredToken(segments.drop(1)) ?: return emptyList()
                listOf(ParsedRule(domain = adLikeDomain, isException = false))
            }
            "PROCESS-NAME", "PACKAGE-NAME" -> {
                val packageName = sanitizeAppPackageToken(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, appPackages = setOf(packageName)))
            }
            "SRC-IP-CIDR", "IP-ASN", "GEOIP", "GEOSITE", "NETWORK", "INBOUND", "PROTOCOL" -> {
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
        var trimmed = stripYamlListPrefix(patternPart.trim())
        if (trimmed.startsWith("payload:", ignoreCase = true)) {
            trimmed = trimmed.substringAfter(':').trim()
        } else if (trimmed.startsWith("payload=", ignoreCase = true)) {
            trimmed = trimmed.substringAfter('=').trim()
        }
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
        if (tokens.size >= 2 && (isHostsIpToken(tokens[0]) || looksLikeIpAddress(tokens[0]))) {
            return tokens.drop(1)
                .mapNotNull { token ->
                    val normalized = normalizeDomainToken(token)
                    when {
                        normalized.equals("localhost", ignoreCase = true) -> null
                        normalized.equals("hostname", ignoreCase = true) -> null
                        looksLikeIpAddress(normalized) -> null
                        else -> sanitizeDomain(normalized)
                    }
                }
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
        val normalized = unwrapCompositeRule(stripYamlListPrefix(patternPart))
        if (normalized == "*") return listOf("*")
        parseEmbeddedRuleCarrierDomain(normalized)?.let { return listOf(it) }
        parsePrefixedDomainRule(normalized)?.let { return listOf(it) }
        sanitizeDomain(normalizeDomainToken(normalized))?.let { directDomain ->
            if (extractPathPattern(normalized) != null) return listOf(directDomain)
        }
        val segments = normalized.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return emptyList()
        val ruleType = segments.first().lowercase()
        val domainToken = findAdLikeStructuredToken(segments.drop(1))
            ?: segments.drop(1).mapNotNull(::parseStructuredDomainToken).firstOrNull()
            ?: return emptyList()
        return when (ruleType) {
            "domain-suffix", "domain-suffixes", "domain", "host-suffix", "host", "hostname-suffix", "suffix" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "domain-wildcard", "host-wildcard", "hostname-wildcard" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken.removePrefix("*.")))
            }
            "full", "full-domain", "hostname", "host-full", "hostname-full", "domain-full", "domain-exact", "host-exact", "hostname-exact", "domain-set", "domain-full-set", "host-set", "hostname-set" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "domain-keyword", "host-keyword", "hostname-keyword", "keyword" -> {
                emptyList()
            }
            "domain-regex", "domain-regexp", "host-regex", "host-regexp", "hostname-regex", "hostname-regexp", "url-regex", "url-regexp",
            "ip-cidr", "ip-cidr6", "src-ip-cidr", "geoip", "geosite", "rule-set", "process-name",
            "process-path", "package-name", "user-agent", "inbound", "network",
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

    private fun parseEmbeddedRuleCarrierDomain(patternPart: String): String? {
        val normalized = patternPart.trim()
        val matchedPrefix = listOf(
            "RULE-SET",
            "RULESET",
            "RULE-PROVIDER",
            "RULE_PROVIDER",
            "ruleset=",
            "ruleset:",
            "ruleset,",
            "rule-provider=",
            "rule-provider:",
            "rule-provider,",
            "rule_provider=",
            "rule_provider:",
            "rule_provider,"
        ).firstOrNull { normalized.startsWith(it, ignoreCase = true) }
            ?: return null
        val body = if (matchedPrefix.contains('=') || matchedPrefix.contains(':') || matchedPrefix.endsWith(',')) {
            normalized.substring(matchedPrefix.length)
        } else {
            normalized.substringAfter(',', missingDelimiterValue = "")
        }.trim()
        if (body.isBlank()) {
            return null
        }
        val parts = body.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val directCandidate = findAdLikeStructuredToken(parts)
        if (directCandidate != null) return directCandidate
        return null
    }

    private fun findAdLikeStructuredToken(values: List<String>): String? {
        return values.asSequence()
            .mapNotNull(::parseStructuredDomainToken)
            .firstOrNull { looksLikeAdDomain(it) || looksLikeBypassProtectionDomain(it) }
    }

    private fun parseDelimitedPrefixedDomainRule(patternPart: String, delimiter: Char): String? {
        val exactPrefixes = listOf(
            "full",
            "full-domain",
            "domain",
            "domain-set",
            "domain-full-set",
            "host",
            "hostname",
            "host-full",
            "host-set",
            "hostname-set",
            "domain-suffix",
            "domain-suffixes",
            "host-suffix",
            "hostname-suffix",
            "hostname",
            "suffix",
            "host-exact",
            "hostname-exact",
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

    private fun unwrapCompositeRule(value: String): String {
        val trimmed = value.trim()
        val compositePrefixes = listOf("AND,", "OR,", "NOT,", "AND:", "OR:", "NOT:", "AND(", "OR(", "NOT(")
        val matchedPrefix = compositePrefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return trimmed
        return trimmed.substringAfter(matchedPrefix)
            .trim()
            .trimStart('(', '[')
            .trimEnd(')', ']')
            .trim()
    }

    private fun stripYamlListPrefix(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("- ") -> trimmed.substring(2).trim().removeSurrounding("\"").removeSurrounding("'")
            trimmed.startsWith("* ") -> trimmed.substring(2).trim().removeSurrounding("\"").removeSurrounding("'")
            trimmed == "-" || trimmed == "*" -> ""
            else -> trimmed.removeSurrounding("\"").removeSurrounding("'")
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
        val labels = lower.split('.', '-', '_').filter { it.isNotBlank() }
        // 基础关键词匹配
        val baseMatch = adKeywords.any { keywordMatches(lower, normalizedTokens, it) }
        if (baseMatch) return true

        val strongAdLabels = setOf(
            "ad", "ads", "adx", "ssp", "dsp", "rtb", "adn", "adnet", "adservice", "adserver",
            "adtrack", "adtracker", "adsdk", "banner", "promo", "promotion", "offerwall", "splash",
            "preroll", "midroll", "postroll", "interstitial", "reward", "rewarded", "monetize", "monetization"
        )
        if (labels.any(strongAdLabels::contains)) return true

        // 2. 广告 SDK 和追踪域名
        val adSdkPatterns = listOf(
            ".adsdk.", ".adservice.", ".adnetwork.", ".adserver.",
            ".adtrack.", ".adtracker.", ".admanager.", ".adplatform.",
            ".ssp.", ".dsp.", ".adx.", ".rtb.", ".mediation.",
            ".bidding.", ".auction.", ".offerwall.", ".rewardvideo."
        )
        if (adSdkPatterns.any { it in lower }) return true

        // 3. 广告 vendor 域名包含特定字符串
        val vendorAdDomains = listOf(
            "pangle.", "gromore.", "sigmob.", "mintegral.", "applovin.",
            "ironsrc.", "unity3d.", "vungle.", "topon.", "tradplus.",
            "jjye.", "bytedance.", "tiktok.", "kwai.", "kuaishou.",
            "qimao.", "kmxs.", "wtzw.", "fqnovel.", "fanqie.", "zijie."
        )
        if (vendorAdDomains.any { it in lower }) {
            // 如果包含广告相关后缀，判定为广告
            val adSuffixes = listOf("ad", "ads", "sdk", "api", "log", "stat", "track")
            if (adSuffixes.any { suffix -> lower.contains(".$suffix") || lower.endsWith(".$suffix") }) return true
        }

        val protectedCommunityDomains = listOf(
            "coolapk.com",
            "coolapkmarket.com"
        )
        if (protectedCommunityDomains.any { lower == it || lower.endsWith(".$it") }) return false

        // 4. 数字 + 广告关键词的域名（如 123ad.com, ad456.com）
        val alphanumericAdPattern = Regex("[0-9]+.*ad|ad.*[0-9]+")
        if (alphanumericAdPattern.containsMatchIn(lower) && !lower.contains("dad") && !lower.contains("grad")) return true

        val novelAdInfraPatterns = listOf(
            ".ad.", ".ads.", ".adx.", ".dsp.", ".ssp.", ".rtb.",
            ".tracking.", ".tracker.", ".analytics.", ".stat.", ".report.", ".monitor.",
            "adservice", "adserver", "adtrack", "adlog", "adreport", "adsdk", "sdkad",
            "reward", "excitation", "inspire", "splash", "launch", "startup", "preload",
            "welfare", "taskcenter", "task_center", "coinreward", "readingbonus", "offerwall", "monetize",
            "admaterial", "materialurl", "creative", "creativeid", "landingurl", "clickurl", "showurl",
            "monitorurl", "impression", "playable", "endcard", "waterfall", "mediation", "bidding",
            "auction", "placement", "slotid", "templateid", "rewardvideo", "open_screen", "startup_preload",
            "launch_preload", "commentflowad", "replyflowad", "feedinsertad", "timelineinsertad"
        )
        if (novelAdInfraPatterns.any { pattern -> lower.contains(pattern) }) return true
        
        return false
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
        if (modifierPart == null) return emptyList()
        val modifiers = modifierPart.split(',')
            .map { it.trim().removePrefix("~").substringBefore('=').lowercase() }
            .filter { it.isNotBlank() }
        if (modifiers.isEmpty()) return emptyList()
        return modifiers.filter { modifier ->
            unsupportedAdGuardModifiers.contains(modifier) && !ignorableAdGuardModifiers.contains(modifier)
        }
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
        val destinationPorts = mutableSetOf<Int>()
        val sourcePorts = mutableSetOf<Int>()
        var domainScoped = false
        var thirdParty = false
        var firstParty = false
        var redirect = false
        val domainConstraints = mutableSetOf<String>()
        val denyallow = mutableSetOf<String>()
        var urlblock = false
        var fromScoped = false
        var toScoped = false
        var pathScoped = false
        var pathPattern: String? = null
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
                    in ignorableAdGuardModifiers -> Unit
                    "important", "match-case" -> Unit
                    "badfilter" -> badfilter = true
                    "app" -> {
                        if (inverted || value.isBlank()) return ModifierInfo(invalid = true)
                        appScoped = true
                        appPackages.addAll(value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() })
                    }
                    "dst-port" -> {
                        val ports = parsePortModifierValues(value, inverted) ?: return ModifierInfo(invalid = true)
                        destinationPorts.addAll(ports)
                    }
                    "src-port" -> {
                        val ports = parsePortModifierValues(value, inverted) ?: return ModifierInfo(invalid = true)
                        sourcePorts.addAll(ports)
                    }
                    "domain" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        domainScoped = true
                        domainConstraints.addAll(
                            value.split('|')
                                .map { it.trim().lowercase().removePrefix("~") }
                                .filter { it.isNotBlank() }
                        )
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
                    "third-party", "3p" -> {
                        if (inverted) {
                            firstParty = true
                        } else {
                            thirdParty = true
                        }
                    }
                    "first-party", "1p" -> {
                        if (inverted) {
                            thirdParty = true
                        } else {
                            firstParty = true
                        }
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
                        pathPattern = value.lowercase()
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
            destinationPorts = destinationPorts.toSet(),
            sourcePorts = sourcePorts.toSet(),
            domainScoped = domainScoped,
            thirdParty = thirdParty,
            firstParty = firstParty,
            redirect = redirect,
            domainConstraints = domainConstraints.toSet(),
            denyallow = denyallow.toSet(),
            urlblock = urlblock,
            fromScoped = fromScoped,
            toScoped = toScoped,
            pathScoped = pathScoped,
            pathPattern = pathPattern,
            removeParams = removeParams.toSet(),
            cspValue = cspValue
        )
    }

    private fun extractKeywordPattern(pattern: String): String? {
        val trimmed = pattern.trim().removePrefix("*").removeSuffix("*").removeSuffix("^").removeSuffix("/")
        if (trimmed.isBlank()) return null
        return trimmed.lowercase()
    }

    private fun parsePortModifierValues(value: String, inverted: Boolean): Set<Int>? {
        if (inverted || value.isBlank()) return null
        val ports = value.split('|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { token ->
                val port = token.toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                port
            }
            .toSet()
        return ports.takeIf { it.isNotEmpty() }
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

    private fun canSafelyApplyModifierContext(patternPart: String, modifierInfo: ModifierInfo): Boolean {
        if (patternPart.trim() == "*" &&
            (modifierInfo.destinationPorts.isNotEmpty() || modifierInfo.sourcePorts.isNotEmpty())) {
            return true
        }
        val hasKeyword = patternPart.contains('*')
        val hasPathPattern = extractPathPattern(patternPart) != null || !modifierInfo.pathPattern.isNullOrBlank()
        if (hasKeyword || hasPathPattern) return true

        val needsAdCheck = modifierInfo.appScoped ||
            modifierInfo.domainScoped ||
            modifierInfo.thirdParty ||
            modifierInfo.firstParty ||
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
        pathPattern: String? = null,
        ipCidr: String? = null,
        regexPattern: String? = null,
        cosmeticSelector: String? = null,
        removeParams: Set<String> = emptySet(),
        cspValue: String? = null,
        keywordPattern: String? = null,
        domainConstraints: Set<String>? = emptySet(),
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
        val domainConstraintKey = (domainConstraints ?: emptySet()).toSortedSet().joinToString("|")
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
            pathPattern.orEmpty(),
            ipCidr.orEmpty(),
            regexPattern.orEmpty(),
            cosmeticSelector.orEmpty(),
            "cx:${cosmeticException}",
            removeParamKey,
            cspValue.orEmpty(),
            "kw:${keywordPattern.orEmpty()}",
            "domains:$domainConstraintKey",
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
        val normalizedRules = rules.map { copyBlockRule(it, vendor = normalizeVendorName(it.vendor)) }.sortedBy { it.domain }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RULES, gson.toJson(normalizedRules))
            .putInt(KEY_RULE_COUNT, normalizedRules.size)
            .apply()
        updateRuleCache(normalizedRules)
        synchronized(dnsBlockDecisionLock) {
            dnsBlockDecisionCache.clear()
        }
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
        cachedBlockedDomains = rules.mapTo(linkedSetOf(), BlockRule::domain)
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

    private fun ruleMatches(rule: BlockRule, qType: Int?, appName: String? = null, host: String? = null, requestDomain: String? = null): Boolean {
        if (!matchesAppPackage(rule.appPackages, appName)) return false
        if (!matchesRequestContext(rule, host, requestDomain)) return false
        if (qType == null) return true
        val dnsTypes = normalizeDnsTypes(rule.dnsTypes)
        val excludedDnsTypes = normalizeDnsTypes(rule.excludedDnsTypes)
        if (excludedDnsTypes != null && excludedDnsTypes.contains(qType)) return false
        return dnsTypes == null || dnsTypes.contains(qType)
    }

    private fun matchesRequestContext(rule: BlockRule, host: String?, requestDomain: String?): Boolean {
        val normalizedHost = host?.let(::sanitizeDomain)
        val normalizedRequestDomain = requestDomain?.let(::sanitizeDomain)
        if (rule.denyallow.isNotEmpty() && normalizedRequestDomain != null) {
            if (rule.denyallow.any { denied -> normalizedRequestDomain == denied || normalizedRequestDomain.endsWith(".$denied") }) {
                return false
            }
        }
        if (rule.domainConstraints?.isNotEmpty() == true) {
            if (normalizedRequestDomain == null) return false
            val allowed = rule.domainConstraints.any { allowedDomain ->
                normalizedRequestDomain == allowedDomain || normalizedRequestDomain.endsWith(".$allowedDomain")
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
            firstParty = rule.firstParty,
            pathPattern = rule.pathPattern,
            ipCidr = rule.ipCidr,
            regexPattern = rule.regexPattern,
            cosmeticSelector = rule.cosmeticSelector,
            removeParams = rule.removeParams,
            cspValue = rule.cspValue,
            keywordPattern = rule.keywordPattern,
            domainConstraints = rule.domainConstraints.orEmpty(),
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
        return RemoteRuleRemovalCandidate(rule = rule, reasons = reasons.distinct())
    }

    private fun explainImpactNormalNetworkCandidate(context: Context, rule: BlockRule): RemoteRuleRemovalCandidate? {
        if (!rule.regexPattern.isNullOrBlank()) return null
        if (!rule.cosmeticSelector.isNullOrBlank()) return null
        val lower = rule.domain.lowercase()
        val reasons = mutableListOf<String>()
        val vendor = if (rule.vendor == DEFAULT_VENDOR) classifyVendor(context, rule.domain) else normalizeVendorName(rule.vendor)
        when {
            lower.contains("qq") || lower.contains("weixin") || lower.contains("wechat") -> {
                reasons += "此域名影响微信、QQ 或企业微信的消息收发、登录或文件传输功能"
            }
            lower.contains("music") || lower.contains("kugou") || lower.contains("kuwo") || lower.contains("spotify") || lower.contains("y.qq") -> {
                reasons += "此域名影响音乐应用播放、音频拉流或歌曲加载功能"
            }
            lower.contains("alipay") || lower.contains("tenpay") || lower.contains("pay") || lower.contains("bank") -> {
                reasons += "此域名影响支付、鉴权或订单确认功能"
            }
            lower.contains("game") || lower.contains("gamedl") || lower.contains("mihoyo") || lower.contains("hoyoverse") || lower.contains("steam") -> {
                reasons += "此域名影响游戏登录、资源下载或联机功能"
            }
            vendor == DEFAULT_VENDOR && !looksLikeAdDomain(rule.domain) -> {
                reasons += "此域名未表现出明确广告特征，更像正常业务域名，可能影响应用联网"
            }
        }
        if (reasons.isEmpty()) return null
        if (!looksLikeBypassProtectionDomain(rule.domain)) {
            reasons += "它不属于加密 DNS 反绕过目标，更适合保留正常联网能力"
        }
        return RemoteRuleRemovalCandidate(rule = rule, reasons = reasons.distinct())
    }

    data class RequestRewriteDirectives(
        val removeParams: Set<String> = emptySet(),
        val cspValue: String? = null
    )

    data class RemoteRuleRemovalCandidate(
        val rule: BlockRule,
        val reasons: List<String>
    )

    private fun looksLikeSuspiciousPath(path: String): Boolean {
        val lowerPath = path.lowercase()
        val suspiciousKeywords = listOf("/ad", "/ads", "/advert", "/banner", "/splash", "/promo", "/tracker")
        return suspiciousKeywords.any { lowerPath.contains(it) }
    }

    private fun pathMatchesPattern(path: String, pathPattern: String): Boolean {
        val normalizedPath = path.lowercase()
        val normalizedPattern = pathPattern.lowercase()
        if (normalizedPattern.isBlank()) return false
        return when {
            normalizedPattern.contains("*") -> {
                val parts = normalizedPattern.split('*').filter { it.isNotBlank() }
                if (parts.isEmpty()) true else parts.all { normalizedPath.contains(it) }
            }
            else -> normalizedPath.contains(normalizedPattern)
        }
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
        val mergedDestinationPorts = (existing.destinationPorts + incoming.destinationPorts).toSet()
        val mergedSourcePorts = (existing.sourcePorts + incoming.sourcePorts).toSet()
        return copyBlockRule(
            existing,
            dnsTypes = mergeDnsTypes(existing.dnsTypes, incoming.dnsTypes),
            excludedDnsTypes = mergeDnsTypes(existing.excludedDnsTypes, incoming.excludedDnsTypes),
            thirdParty = existing.thirdParty || incoming.thirdParty,
            firstParty = existing.firstParty || incoming.firstParty,
            redirect = existing.redirect || incoming.redirect,
            domainConstraints = (existing.domainConstraints.orEmpty() + incoming.domainConstraints).toSet(),
            denyallow = mergedDenyallow,
            urlblock = existing.urlblock || incoming.urlblock,
            appPackages = mergedAppPackages,
            destinationPorts = mergedDestinationPorts,
            sourcePorts = mergedSourcePorts,
            keywordPattern = mergedKeyword,
            pathPattern = incoming.pathPattern ?: existing.pathPattern,
            ipCidr = incoming.ipCidr ?: existing.ipCidr,
            regexPattern = incoming.regexPattern ?: existing.regexPattern,
            cosmeticSelector = incoming.cosmeticSelector ?: existing.cosmeticSelector,
            cosmeticException = incoming.isException || existing.cosmeticException,
            removeParams = (existing.removeParams + incoming.removeParams).toSet(),
            cspValue = incoming.cspValue ?: existing.cspValue
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
        redirect: Boolean = rule.redirect,
        domainConstraints: Set<String>? = rule.domainConstraints,
        denyallow: Set<String> = rule.denyallow,
        urlblock: Boolean = rule.urlblock,
        appPackages: Set<String> = rule.appPackages,
        destinationPorts: Set<Int> = rule.destinationPorts,
        sourcePorts: Set<Int> = rule.sourcePorts,
        keywordPattern: String? = rule.keywordPattern,
        pathPattern: String? = rule.pathPattern,
        ipCidr: String? = rule.ipCidr,
        regexPattern: String? = rule.regexPattern,
        cosmeticSelector: String? = rule.cosmeticSelector,
        cosmeticException: Boolean = rule.cosmeticException,
        removeParams: Set<String> = rule.removeParams,
        cspValue: String? = rule.cspValue,
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
            redirect = redirect,
            domainConstraints = domainConstraints,
            denyallow = denyallow,
            urlblock = urlblock,
            appPackages = appPackages,
            destinationPorts = destinationPorts,
            sourcePorts = sourcePorts,
            keywordPattern = keywordPattern,
            pathPattern = pathPattern,
            ipCidr = ipCidr,
            regexPattern = regexPattern,
            cosmeticSelector = cosmeticSelector,
            cosmeticException = cosmeticException,
            removeParams = removeParams,
            cspValue = cspValue,
            remoteSourceId = remoteSourceId
        )
    }

    private fun mergeDefaultRemoteRuleSources(stored: List<RemoteRuleSourceConfig>): List<RemoteRuleSourceConfig> {
        val storedById = stored.associateBy { it.id }
        val merged = LinkedHashMap<String, RemoteRuleSourceConfig>()
        defaultRemoteRuleSources.forEach { defaultSource ->
            merged[defaultSource.id] = storedById[defaultSource.id]?.copy(
                name = defaultSource.name,
                url = defaultSource.url,
                authorId = defaultSource.authorId ?: storedById[defaultSource.id]?.authorId
            ) ?: defaultSource
        }
        stored.forEach { source ->
            merged.putIfAbsent(source.id, source)
        }
        return merged.values.sortedBy { it.name.lowercase() }
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
            denyallow = mergedDenyallow,
            urlblock = existing.urlblock || incoming.urlblock,
            appPackages = mergedAppPackages,
            destinationPorts = mergedDestinationPorts,
            sourcePorts = mergedSourcePorts,
            keywordPattern = mergedKeyword,
            pathPattern = incoming.pathPattern ?: existing.pathPattern,
            ipCidr = incoming.ipCidr ?: existing.ipCidr,
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

    private data class RuleLineContext(
        val vendorHints: Set<String> = emptySet()
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

    private data class ModifierInfo(
        val dnsTypes: Set<Int>? = null,
        val excludedDnsTypes: Set<Int>? = null,
        val badfilter: Boolean = false,
        val appScoped: Boolean = false,
        val appPackages: Set<String> = emptySet(),
        val destinationPorts: Set<Int> = emptySet(),
        val sourcePorts: Set<Int> = emptySet(),
        val domainScoped: Boolean = false,
        val thirdParty: Boolean = false,
        val firstParty: Boolean = false,
        val redirect: Boolean = false,
        val domainConstraints: Set<String> = emptySet(),
        val denyallow: Set<String> = emptySet(),
        val urlblock: Boolean = false,
        val fromScoped: Boolean = false,
        val toScoped: Boolean = false,
        val pathScoped: Boolean = false,
        val pathPattern: String? = null,
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
        val firstParty: Boolean = false,
        val redirect: Boolean = false,
        val domainConstraints: Set<String> = emptySet(),
        val denyallow: Set<String> = emptySet(),
        val urlblock: Boolean = false,
        val appPackages: Set<String> = emptySet(),
        val destinationPorts: Set<Int> = emptySet(),
        val sourcePorts: Set<Int> = emptySet(),
        val keywordPattern: String? = null,
        val pathPattern: String? = null,
        val ipCidr: String? = null,
        val regexPattern: String? = null,
        val cosmeticSelector: String? = null,
        val removeParams: Set<String> = emptySet(),
        val cspValue: String? = null,
        val vendorHints: Set<String> = emptySet()
    )
}
