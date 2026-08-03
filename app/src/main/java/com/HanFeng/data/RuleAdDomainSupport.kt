package com.HanFeng.data

import com.HanFeng.core.network.RegexCache

object RuleAdDomainSupport {
    private val alphanumericAdPattern = Regex("[0-9]+.*ad|ad.*[0-9]+")

    private val normalizedTokenRegex = RegexCache.get("[^a-z0-9]")

    private val labelBoundaryCache = HashMap<String, Regex>()

    private fun labelBoundaryRegex(keyword: String): Regex {
        return labelBoundaryCache.getOrPut(keyword) {
            val escaped = Regex.escape(keyword)
            Regex("(?:^|[.\\-_])$escaped(?:[.\\-_]|$)", RegexOption.IGNORE_CASE)
        }
    }

    private fun labelBoundaryContains(domain: String, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        return labelBoundaryRegex(keyword).containsMatchIn(domain)
    }

    private val pushSignals = listOf(
        "push", "pushad", "adpush", "notify", "notification", "message", "msg", "inbox",
        "pmpush", "pnsdk", "pnapp", "pnsystem", "pnconfig", "pnroute",
        "hostpush", "hostpushnotify", "hostpushadnet",
        "apppush", "apppushx", "apppushad",
        "devicepush", "syspush", "sytempush", "sytempushnotify",
        "silentpush", "silentnotify", "silentmessage",
        "notif-sdk", "notifpush", "notif-pn",
        "fpnpmobad", "fpnsdk",
        "pushappnsdk", "clientpush",
        "alarmnotify", "alarmentry"
    )
    private val recommendationSignals = listOf(
        "recommend", "recommendation", "feed", "stream", "timeline", "discover",
        "feedflow", "feedcard", "feedslot",
        "feedrender", "feedlayout", "feedcardadnet",
        "feedlistadnet", "feedlistad",
        "explore", "discovery", "hottopic", "trending",
        "recommendfeedad", "recommendfeedadnet",
        "explore-feed", "discoveryad",
        "rankingad", "chardad",
        "ranksad", "pgrankad",
        "feedad-collect", "feedreportad",
        "feed-mobad", "feedadnet-mob",
        "recommend-sysad", "feedmobsysad",
        "dpaad", "dpax", "dpac",
        "feed-feature-ad", "feed-creative-ad",
        "feed-imp-ad", "feed-click-ad",
        "feed-install-ad", "feed-click-track",
        "feedimpressiontrackad",
        "hotsearch-ad", "hotwordfeedad",
        "recsysadnet", "recsysad",
        "recoadnet", "recovadnet",
        "rec-engine-adnet",
        "feedadgateway",
        "explore-adnet", "discover-adnet",
        "trendingadnet",
        "rankingadnet", "pgrankadnet",
        "feedadbus",
        "host-feedad",
        "topicsadnet",
        "detailed-recommend",
        "detailed-feed"
    )
    private val pushRecommendAdSignals = listOf(
        "ad", "ads", "promo", "promotion", "banner", "material", "creative", "offer", "offerwall",
        "campaign", "commercial", "sponsor", "market", "install", "download", "deeplink", "landing",
        "task", "mission", "welfare", "benefit", "coin", "reward", "game", "live", "search",
        "hotword", "recommend", "experiment", "abtest", "miniapp", "mini-program",
        "clipboard", "share", "serviceworker", "widget", "shortcut", "badge", "lockscreen",
        "comment", "reply", "danmaku", "profile", "follow", "inbox", "message",
        "template", "cloud", "asset", "resource", "bundle", "patch", "coupon", "redpacket",
        "commerce", "affiliate", "commission", "local", "nearby", "survey", "leadgen", "calendar",
        "browser", "startpage", "newtab", "appstore", "oem", "rom", "security", "tv", "wear", "car",
        "ksad", "ksadx", "ksadsdk",
        "wxad", "wxadx", "wxadsdk",
        "qqad", "qqadx", "qqadsdk",
        "pangle-ad", "pangolin-ad",
        "pangolin-adnet", "pangle-adnet",
        "gdtadnet", "gdt-ad", "gdt-adnet",
        "pmp-pangle", "pmp-gdt",
        "admic", "admicadnet",
        "admic-feed", "admic-feedadnet",
        "rec-ad", "rec-ad-feed",
        "feedadnet", "feed-adnet",
        "recommendad", "recommendadadnet",
        "feedadsource", "rec-system-ad"
    )


    private val sdkInfraSignals = listOf(
        "adsdk", "sdkad", "adservice", "adserver", "adnetwork", "adplatform", "admanager",
        "mediation", "waterfall", "bidding", "auction", "bidfloor", "bidder", "ssp", "dsp",
        "adx", "rtb", "exchange", "offerwall", "rewardvideo", "interstitial", "fullscreenad",
        "nativead", "feedad", "splashad", "startupad", "launchad", "open_screen", "material",
        "creative", "slotid", "placement", "templateid", "showurl", "clickurl", "monitorurl",
        "impression", "playable", "endcard", "tracking", "analytics", "stat", "report", "monetize",
        "adapi", "adsapi", "adgateway", "adloader", "adload", "adrequest", "adrequester",
        "adlog", "adslog", "adreport", "admetric", "admetrics", "adtracking", "eventtrack",
        "eventtracker", "imptrack", "imptracker", "clicktrack", "clicktracker", "viewtrack",
        "adcreative", "admaterial", "adcache", "adpreload", "adconfig", "sdkconfig",
        "creativeapi", "materialapi", "trackers", "trackingevent", "conversion", "attribution",
        "adsrvr", "adtag", "adtagging", "adrequestapi", "adresponse", "adresponseapi",
        "adrelay", "adrouter", "adsrouter", "adbridge", "adsbridge", "adtrace", "adtraceapi",
        "bidapi", "bidswitch", "bidrequest", "bidresponse", "auctionapi", "winnotice",
        "lossnotice", "eventtrackers", "impressiontrack", "conversiontrack", "skadnetwork", "skadn",
        "httpdns", "dnsresolve", "dnsresolver", "adresolve", "adresolver", "adhttpdns",
        "websocket", "wsad", "adws", "ssead", "adeventstream", "adstream", "pushstream",
        "grpcad", "adgrpc", "protobufad", "adprotobuf", "adproto", "adpb",
        "dynamicad", "addynamic", "adplugin", "adsplugin", "adsdkplugin", "admodule",
        "dexad", "addex", "adbundle", "adwasm", "encryptedad", "adcrypto", "adcipher",
        "privatead", "adgateway", "adsgateway", "adquic", "adtcp", "adudp", "binaryad",
        "marketad", "installad", "downloadad", "deeplinkad", "shakead", "sensorad",
        "fakealert", "systemad", "cleanerad", "boostad", "notifyad", "pushad",
        "taskad", "missionad", "welfaread", "benefitad", "coinad", "offerwallad",
        "gamead", "gameinterstitial", "revivead", "revivalad", "livead", "liveroomad",
        "searchad", "hotwordad", "keywordad", "recommendad", "suggestionad",
        "experimentad", "abtestad", "grayad", "greyad", "remoteadconfig",
        "miniappad", "miniprogramad", "landingad", "h5ad",
        "fakebuttonad", "fakeclosead", "misclickad", "clicktrapad",
        "clipboardad", "sharead", "servicworkerad", "serviceworkerad", "precachead",
        "widgetad", "shortcutad", "badgead", "lockscreenad", "wallpaperad", "systemsurfacead",
        "commentad", "replyad", "danmakuad", "bulletad", "profilead", "followad",
        "inboxad", "messagead", "chatad", "topicad", "communityad", "socialad",
        "templatead", "tplad", "cloudad", "cloudcontrolad", "cloudconfigad", "layoutad",
        "assetpackad", "resourcepackad", "materialpackad", "creativepackad", "bundlead",
        "patchad", "hotpatchad", "couponad", "redpacketad", "cashad", "cashbackad",
        "subsidyad", "allowancead", "lotteryad", "bonusad",
        "productad", "shopad", "mallad", "goodsad", "itemad", "commercead", "shoppingad",
        "affiliatead", "cpsad", "commissionad", "rebatead", "taokead", "unionad",
        "locallifead", "nearbyad", "poiad", "mapad", "weatherad", "toolad", "cleanerad",
        "batteryad", "wifiad", "filemanagerad", "takeawayad", "hotelad", "travelad", "ridead",
        "leadgenad", "leadformad", "formad", "surveyad", "questionnairead", "trialad",
        "signupad", "reservationad", "calendarreminderad", "calendarsubscribead", "reminderad", "alarmad",
        "browserad", "startpagead", "homepagead", "newtabad", "speeddialad", "bookmarkad",
        "searchboxad", "hotsrchad", "hotsearchad", "trendingad", "sitenavad", "navcardad",
        "appstoread", "appinstallad", "appupdatead", "promotedappad", "preinstallad",
        "gamecenterad", "apkrankad", "apkad", "oemad", "romad", "systemmanagerad",
        "securityad", "boostad", "virusscanad", "storagecleanad", "negativescreenad",
        "tvad", "ottad", "castad", "screencastad", "wearad", "watchad", "carad",
        "carplayad", "iotad", "speakerad", "tabletad", "padad",
        "cnamead", "adalias", "aliasad", "cnamecloakad", "cloakedad", "adcloak",
        "dohad", "doqad", "dotad", "dnsqueryad", "encrypteddnsad", "httpdnsad",
        "alihttpdnsad", "tencenthttpdnsad", "baiduhttpdnsad", "adquic443", "quicad443"
        , "wasmad", "adwasm", "wasmloaderad", "jsloaderad", "obfuscatedad", "packedad",
        "encryptedjsad", "adfingerprint", "phashad", "imagehashad", "mediahashad",
        "watermarkad", "videofingerprintad", "endcardhashad", "framehashad",
        "http3ad", "h3ad", "udp443ad", "adudp443", "quicgatewayad", "http3gatewayad",
        "adtoken", "adnobi", "adnobicn", "adtoken-cn",
        "adcpa", "adclickx", "adclickpath", "adcleanup",
        "admixer", "admixeadnet", "admixer-data",
        "admost", "admostadsystem", "admostadnet",
        "appbigo", "bigo_ad", "bigoad", "bigobroadcastad",
        "bigolive_ad", "bigoadnet", "bigoadnetwork",
        "bigovideos_ad", "bigoandroidad",
        "hwloudad", "hwloudad",
        "sjtuad", "sjtuads", "sjtumobad",
        "qqbrowserad", "qqbadsystem", "qqbadnet",
        "ucadnet", "ucad", "ucadsystem", "ucadvert",
        "adsdktool", "adsdkcollect", "adsdktracker",
        "adsdkreport", "adsdkreporting", "adsdkevent",
        "adsdkinfo", "adsdkdata", "adsdkapi",
        "adsdklicense", "adsdkversion", "adsdkbuild",
        "adsdksource", "adsdkbuild", "adsdkplatform",
        "adstracker", "adstracking", "adseventtrack",
        "adsanalytics", "adsreport", "adsreporting",
        "adsplatform", "adsmob", "adsmanager",
        "adsbidding", "adsreward",
        "adsmediation", "adsinterstitial", "adssplash",
        "adsplashadmob", "adsrewardedvideo",
        "adstoad", "adtrackevent", "adtracereport",
        "adtraceevent", "adtraceanalytics",
        "admonetize", "adpayments", "adspend",
        "adimpression", "adimpressions",
        "adviewability",
        "admutation", "admutationnet", "admutationadnet",
        "adbyss", "adbyssadnet",
        "adadvertisement",
        "adbannernew", "adbannermob",
        "adsignal", "adsignals",
        "adinterstitialmob", "adinterstitialmobad",
        "adapplovinx", "adapplovinmob", "adapplovinmax",
        "adunityx", "adunitymob", "adunityvideoad",
        "advunglex", "advunglemob",
        "adchartboostx", "adchartboostmob",
        "admintegralmob", "admobvista",
        "adadcolonyx", "adadcolonymob",
        "adtapjoyx", "adtapjoymob",
        "adironsrcx", "adironsrcmob",
        "adsmaatox", "adsmaatomob",
        "adinmobiad", "admobinmob",
        "adcriteox", "adcriteomob",
        "adstartappx", "adstartappmob",
        "adfacebookx", "adfbanmarket", "admetaudnx",
        "adgooglex", "addoubleclickmob", "adgoogleadslinks",
        "adadmobx", "adfacebookmob",
        "admalix", "admatax", "admatabx",
        "adyandexmob", "adyandexdirectx",
        "admailrumobx", "admailruadsystem",
        "adqualcomx", "adqualcomadsys",
        "adztorchmob", "adztorchx",
        "ztorchmobad", "ztorchx",
        "adsdkopenudid", "adsdkidfa",
        "adsdkidfv", "adsdkimei", "adsdkandroidid",
        "adsdkga", "adsdkgadfp",
        "adsdkgadmobx", "adsdkgadsmob",
        "adsdkdfpadx", "adsdkdfpadmob",
        "adsdk_secure", "adsdk_secure_id",
        "adsdkfam", "adsdkpanglecn", "adsdkpangle",
        // 与广告聚合/出价/曝光监测/闭环归因相关的常见 SDK 路径与子域标识
        "adsdktrack", "adsdktracking", "adsdkreport",
        "adsdkevent", "adsdklog", "adsdkeventlog",
        "adsdkrequest", "adsdkserve", "adsdkbid",
        "adsdkbidreq", "adsdkbidres", "adsdkauction",
        "adsdkimp", "adsdkimpression", "adsdkshow",
        "adsdkclick", "adsdkclicktrack", "adsdkclk",
        "adsdkconversion", "adsdkcv", "adsdkskan",
        "adsdkatt", "adsdkidat", "adsdkgaid",
        "adsdkappset", "adsdkref", "adsdkreferr",
        "adsdkretarget", "adsdkretargeting", "adsdkrtb",
        "adsdkview", "adsdkviewtrack", "adsdkviewability",
        "adsdkvast", "adsdkvasttag", "adsdkvastxml",
        "adsdkvmap", "adsdkmraid", "adsdkoma",
        "adsdkbanner", "adsdkinterstitial", "adsdkrewarded",
        "adsdknative", "adsdknativead", "adsdkfeedad",
        "adsdkappopen", "adsdkdraw", "adsdkfullscreen",
        "adsdksplash", "adsdkprebid", "adsdkheaderbidding",
        "adsdks2s", "adsdkc2s", "adsdksdkconfig",
        "adsdksdksetting", "adsdksdkinit", "adsdksdkenv",
        // 归因/监测大厂链路补强
        "adsdkadjust", "adsdkappsflyer", "adsdkbranch",
        "adsdkkochava", "adsdksingular", "adsdktune",
        "adsdkmatomo", "adsdkmixpanel", "adsdkamplitude",
        "adsdksegment", "adsdkbraze", "adsdklocalytics"
    )

    private val sdkVendorSignals = listOf(
        "pangle", "gromore", "pangolin", "csj", "gdt", "youlianghui", "guangdiantong", "sigmob",
        "mintegral", "mobvista", "mbridge", "applovin", "applvn", "maxads", "ironsource", "ironsrc",
        "unityads", "unity3d", "vungle", "liftoff", "chartboost", "inmobi", "aerserv", "topon",
        "anythink", "tradplus", "tpbid", "beizi", "bzadx", "adscope", "aiclk", "youmi", "adwo",
        "vpon", "pubmatic", "openx", "taboola", "outbrain", "adcolony", "ogury", "fyber",
        "inneractive", "digitalturbine", "colossusssp", "smaato", "tapjoy", "audiencenetwork",
        "moloco", "bidmachine", "adtiming", "adjoe", "startapp", "criteo", "mytarget",
        "maio", "nend", "tapdaq", "yeahmobi", "adtelligent", "pubnative", "hyprmx",
        "bidswitch", "loopme", "verve", "vervegroup", "smadex", "sonobi", "gumgum",
        "sharethrough", "triplelift", "yieldmo", "indexexchange", "rubicon", "magnite",
        "doubleclick", "googlesyndication", "googleadservices", "imasdk", "mopub", "snapads",
        "rayjump", "singular", "appsflyer", "adjust", "kochava", "branchmetrics",
        "beead", "bee-ad", "yousu", "yousuad", "adview", "adviewcn",
        "domob", "domobcn", "guozhen", "guozhenad", "airpus", "airpusad",
        "jiatuan", "jiatuanad", "wubi", "wubiad", "chuangyi", "chuangyiad",
        "feiyu", "feiyuad", "yixuan", "yixuanad", "youmeng", "ymob",
        "dianru", "drmob", "guoan", "gaad", "admaster", "mdotm",
        "wqmobile", "wqmob", "mads", "madhouse", "miit", "miitbeian",
        "cnzz", "cnad", "allyes", "alimama", "tanx",
        "csbew", "jmads", "jpush", "jiguang", "aurora",
        "getui", "igexin", "gepush", "unipush", "umeng", "umengad",
        "umengads", "baidumob", "baidumobads", "bdmob", "bd-mob",
        "huaweiads", "huawei-ads", "hwads", "hw-ads", "hicloud",
        "xiaomiad", "xiaomi-ad", "miad", "mi-ad", "miuiad",
        "oppoads", "oppo-ads", "oppoad", "heytap", "nearme",
        "vivoad", "vivo-ad", "vivoads", "vivo-ads", "jovi",
        "samsungads", "samsung-ads", "galaxyad", "galaxy-ad",
        "kswad", "kswads", "ks-ad", "ks-wad", "kuaishouad",
        "snssdk", "sns-sdk", "bytedance", "byte-dance", "byted",
        "oceanengine", "ocean-engine", "ocneng", "bytecdn", "byteimg",
        "facebook", "audience_network", "meta", "fbad", "fban",
        "firebase", "google-analytics", "analytics", "crashlytics",
        "amplitude", "mixpanel", "flurry", "bugsnag", "newrelic",
        "braze", "clevertap", "moengage", "leanplum",
        "onesignal", "airship", "urban", "pushwoosh",
        "countly", "localytics", "apsalar", "taplytics",
        "tenjin", "sensortower", "appsmarket", "appmonsta",
        "adcolonycn", "yandexads", "yandexad", "mailru", "mailruads",
        "ironsourcecn", "ironsrccn", "mintegralcn", "mobvistacn",
        "huaweiad", "huawei_ad", "honorad", "honor_ad",
        "lenovomobad", "lenovoads", "ztead", "zteads",
        "meizumobad", "meizuad", "meizuads",
        "bilibiliad", "bilibili_ad", "bilibiliads", "bworks",
        "zhihuad", "zhihu_ad", "zhihudata",
        "sogoumobad", "sogouads", "sogouad", "sogoudata",
        "360ad", "360ads", "360mobad", "360mob",
        "tencentad", "tencentads", "tencentmobad", "qqad", "qqads", "qqmobad",
        "wangxinads", "wangxincn", "wangxinvalid",
        "mintegralmob", "mintegralad", "mintgo",
        "ksadsdk", "ksadsdk", "kwaimobad", "kwaiad", "kwaiads",
        "weixinsdkad", "weixinad", "weixinads", "wxad",
        "wechatad", "wechatads", "mpweixinad", "mpweixin", "servicewxad",
        "wechatappad", "wxappad", "wxappads", "wxadvideo", "wxadcdn",
        "miniappadad", "miniprogramadds", "wxminigamead", "wxminiad",
        "bytedancetest", "bytedancecn", "bytedancetoads", "oceanengineads",
        "volcengine", "volcad", "volcads", "volcdnsad",
        "bytedm", "bytedto", "bytetx", "bytedfs",
        "bytetcc", "bytedx", "bytedadapi", "bytedck",
        "appled", "tnetad", "tnetads", "tnetmobad",
        "thanad", "thanads", "thandata",
        "wuaifangad", "wuaifangads", "wuaifangdata",
        "tplkingwayad", "tplkingwayads", "tplkingwaydata",
        "shandianad", "shandianads", "shandianaddata",
        "dmendad", "dmendads", "dmenddata",
        "mingchao", "mingchaoad", "mingchaoads",
        "wuajiangad", "wuajiangads",
        "xiaoyingad", "xiaoyingads", "xiaoyingmobad",
        "youmengdata", "umengdata",
        "umengshare", "umengsocial", "umengmessage",
        "ad Touch", "antaiad", "antaiads", "antaimobad",
        "antutudata", "antutuad", "antutuads", "antutumobad",
        "intocad", "intocads", "intocaiad",
        "pdfjcp", "pdfjc",
        "imedmobad", "imedad", "imedads",
        "adinfobase", "adsinfobase",
        "inmobicn", "mobvistads", "mbridgecn",
        "iqiyicn", "iqiyiad", "iqiyiads", "qiyiad", "qiyiads",
        "youkuad", "youkuads", "youkumobad",
        "mgtvad", "mgtvads", "mgtvmobad",
        "letvad", "letvads", "letvmobad",
        "pptvad", "pptvads", "pptvmobad",
        "funshionad", "funshionads",
        "baofengad", "baofengads",
        "sohuad", "sohuads", "sohumobad",
        "sinaad", "sinaads", "sinamobad", "sinedata",
        "ifengad", "ifengads", "ifengmobad",
        "163ad", "163ads", "163mobad", "neteasead", "neteaseads", "neteaseui",
        "21cnad", "21cnads", "21cnmobad",
        "chinadailyad", "chinadailyads",
        "chinanewsad", "chinanewsads",
        "huanqiuad", "huanqiuads", "huanqiumobad",
        "tmtyad", "tmtyads", "tmtydata",
        "kuaiboad", "kuaiboads", "kuaibomobad",
        "btimead", "btimeads", "btimemobad",
        "bendiyad", "bendiyads", "bendiydata",
        "kxbyad", "kxbyads", "kxbydata",
        "jianyad", "jianyads", "jianymobad",
        "mediav", "mediavads", "mediavmobad",
        "mediavoicead", "mediavoiceads",
        "yeadad", "yeadads", "yeadmobad",
        "easouad", "easouads", "easoudata",
        "juxiaoanad", "juxiaoanads", "juxiaoanmobad",
        "yimonad", "yimonads", "yimonmobad",
        "miaozhenad", "miaozhenads", "miaozhenmobad",
        "miaozhen", "miaozhendata", "miaozhensys",
        "irs01ad", "irs01ads", "irs01",
        "irs02ad", "irs02ads", "irs02",
        "irs03ad", "irs03ads", "irs03",
        "irs05ad", "irs05ads", "irs05",
        "irs06ad", "irs06ads", "irs06",
        "irs07ad", "irs07ads", "irs07",
        "maxad", "maxads", "maxmobad",
        "maxvaluead", "maxvalueads", "maxvaluemobad",
        "mediads", "mediadssid", "mediadcdn",
        "ipinyouad", "ipinyouads", "ipinyoumobad",
        "ipinyou", "ipinyoudata", "ipinyousys",
        "yoyiad", "yoyiads", "yoyimobad",
        "yoyisys", "yoyidata",
        "yoyicloud", "yoyimobiads", "yoyimob",
        "madhouseads", "madhousemobad", "madhousecn",
        "madhousecloud", "madhousetech",
        "yiyouad", "yiyouads", "yiyoumobad",
        "辉锐", "huiruiad", "huiruiads", "huiruimobad",
        "xuanfengad", "xuanfengads", "xuanfengmobad",
        "baiduiad", "baiduiads", "baidumobad",
        "baidnuju", "baidunut", "baidumobcn",
        "baiducls", "baiduvmobad",
        "qihuad", "qihuads", "qihumobad",
        "qihuneituiad", "qihuneituiads", "qihooneitui",
        "qihoo360ad", "qihoo360ads", "360mobcn",
        "360neituiad", "360neituiads", "360neituimobad",
        "servicead-eu",  "servicead-us", "suyouad",
        "tanxadsys", "tanxcn", "tanxads", "tanxmobad",
        "ganjiad", "ganjiads", "ganjimobad",
        "58tongchengad", "58tongchengads", "58ads",
        "58mobad", "wubaad", "wubaads", "wubamobad",
        "58ganjiad", "58ganjiads", "58ganjimobad",
        "dianpingad", "dianpingads", "dianpingmobad",
        "meituandataad", "meituanads", "meituanmobad",
        "meituanadvert", "meituanad",
        "dianxinglyad", "dianxingladads", "dianxinglmobad",
        "waimaiad", "meituanwaimaiad",
        "elemead", "elemeads", "elememobad",
        "doubanad", "doubanads", "doubandata",
        "vistorad", "vistorads", "vistordata",
        "dianpingReviewad", "dianpingReviewads",
        "koubeiad", "koubeiads", "koubeidata",
        "qidianad", "qidianads", "qidianmobad",
        "zhangyuead", "zhangyueads", "zhangyuemobad",
        "ztezhuaad", "ztezhuaads", "ztezhuamobad",
        "zhubajiead", "zhubajieads", "zhubajiemobad",
        "tpdad", "tpdads", "tpdmobad",
        "tpdcs", "tpdcscn", "tpdcloud",
        "wannabe", "beijihuad", "beijihuads",
        "mingdianad", "mingdianads", "mingdianmobad",
        "limeiad", "limeiads", "limeimobad",
        "limeicn", "limeidata", "limeicloud",
        "qin huangdaocn", "qhdad", "qhdads",
        "xmaxad", "xmaxads", "xmaxmobad",
        "unclead", "unclecloudmobad", "unclecloud",
        "beamobad", "beamob", "beamobmob",
        "beihaimobad", "beihaimob",
        "wechatadsnew", "weoxinads", "wxadx",
        "wxadapp", "wxadhome", "wxadsystem",
        "qqadsystem", "qqadmob", "qqadx",
        "qqadxservice", "qqadnet", "qqadgroup",
        "qzonead", "qzoneads", "qzonemobad",
        "pengyouad", "pengyouads", "pengyoumobad",
        "qcloudad", "qcloudads", "qcloudmobad",
        "tencloudad", "tencloudads", "tencloudmobad",
        "qbytedancead", "qbytedanceads",
        "tadnetworkad", "tadnetworkads",
        "amazon-adsystem", "amazonadsystem", "amznad", "amznads",
        "amazonad", "amazonads", "amznassoc",
        "yahooad", "yahooads", "yahooflurry",
        "msnads", "msnadvert", "msnneituiad",
        "bingad", "bingads", "bingneituiad",
        "microsoftad", "microsoftads", "microsoftmobad",
        "msadvert", "msadnet",
        "yandexdirect", "yandexadnet", "yandexadnetwork",
        "mailruad", "mailruads", "mailruadvert",
        "okadvert", "okadnet", "vkrugruad",
        "tutbuad", "tutbyadvert", "tutbyadnet",
        "xiomi", "xiaomi_appstoread", "xiaomi_marketad",
        "miuiappstoread", "miuiads", "miuiadvert",
        "miadbiz", "miadmobcn", "miadmobadvert",
        "mzadcn", "meizuadvert", "meizuadsystem",
        "flymead", "flymeadvert",
        "emuiadvert", "emuiadmob", "emuiad",
        "magicuiadvert", "magicuiads", "magicuiadmob",
        "colorosadvert", "colorosadsystem", "colorosad",
        "originosadvert", "originosadsystem", "originosad",
        "nubiaad", "nubiaads", "nubiaadvert",
        "realmeadsystem", "realmeads", "realmeadvert",
        "iqooadvert", "iqooadsystem", "iqooads",
        "oneplusad", "oneplusads", "oneplusadvert",
        "oppoappmarketad", "oppoadsystem",
        "vivolbl", "vivolblcn", "vivobrowserad",
        "vivoappstoread", "vivoappstoreadvert",
        "bigmobad", "bigmobiads", "bigmobadnet",
        "adcolonyadsystem", "adcolonyadnet", "adcolonysdk",
        "yoyiadx", "yoyiadsystem", "yoyiadnet",
        "iqiyipartnerad", "iqiyipartnerads",
        "tencentax", "tencentaxad", "tencentaxads",
        "tenuscadnetad", "tenuscadnetads",
        "tencentcosmobad", "tencentcosmobads",
        "tencentcloudad", "tencentcloudads",
        "tencentintsmsad", "tencentintsmsads",
        "odm-ad", "odmadnet", "odmads",
        "appinsidead", "appinsideads",
        "flurryadnet", "flurryadnetad", "flurryadnetads",
        "umengloganalytics", "umenglog",
        "umengapptrack", "umengaft",
        "umengmessagechannel", "umengpush",
        "umengadsdk", "umengsharead",
        "umengproad", "umengproads",
        "umengdanalytics", "umengda", "umengdas",
        "channelad", "channelads", "channeladmob",
        "servicead-cn", "servicead-cn", "serviceadadvert",
        "serviceadwebview", "serviceadsystem",
        "xuanwuad", "xuanwuads",
        "xuanwumobad", "xuanwumobads",
        "baocaiad", "baocaiads", "baocaimobad",
        "baicao", "caizhiad", "caizhiads",
        "maizuo", "maizuoad", "maizuoads",
        "modao", "modaoad", "modaoads",
        "antutuadnet", "antutuadnetad", "antutuadnetads",
        "imoocad", "imoocads",
        "moocad", "moocads", "moocmobad",
        "maoresmobad", "maoresad", "maoresads",
        "suyoucn", "suyouadnet",
        "tangxoo", "tangxooad", "tangxooads",
        "hudongad", "hudongads", "hudongmobad",
        "hudongbkad", "hudongbkads",
        "vendor-ad", "vendoradnet", "vendorads"
    )

    private val protectedByteDanceStrongAdInfraMarkers = listOf(
        "pangolin", "pangle", "gromore", "adsdk", "adservice", "adserver", "adtrack",
        "reward", "splash", "offerwall", "unionad", "mediation", "waterfall", "bidding"
    )

    private val whitelistedRootAdSubdomainMarkers = listOf(
        "ad.", "ads.", "adx.", "adx-", "adservice.", "adserver.", "adtrack.", "adtracker.",
        "adsdk.", "sdkad.", "gdt.", "pangle.", "pangolin.", "gromore.", "sigmob.",
        "topon.", "tradplus.", "adscope.", "mobvista.", "mintegral.", "applovin.",
        "unityads.", "vungle.", "offerwall.", "rewardvideo.", "open_screen.", "startupad.",
        "launchad.", "splashad.", "feedad.", "nativead."
    )
    private val whitelistedRootAdSubdomainExtraSignals = listOf(
        "showurl", "clickurl", "monitorurl", "impression", "playable", "endcard", "waterfall", "mediation", "bidding"
    )

    private val strongAdLabels = setOf(
        "ad", "ads", "adx", "ssp", "dsp", "rtb", "adn", "adnet", "adservice", "adserver",
        "adtrack", "adtracker", "adsdk", "banner", "promo", "promotion", "offerwall", "splash",
        "preroll", "midroll", "postroll", "interstitial", "reward", "rewarded", "monetize", "monetization",
        "adapi", "adlog", "adslog", "adreport", "adloader", "adrequest", "adgateway", "adcache", "adconfig",
        "adrelay", "adrouter", "adbridge", "adtrace", "adsrvr", "adtag", "skadn",
        "httpdns", "adresolver", "adstream", "wsad", "ssead", "grpcad", "protobufad",
        "adplugin", "admodule", "dynamicad", "adgateway", "adquic", "adtcp", "adudp",
        "marketad", "installad", "downloadad", "deeplinkad", "shakead", "notifyad", "pushad",
        "taskad", "missionad", "welfaread", "benefitad", "coinad", "offerwallad",
        "gamead", "revivead", "livead", "liveroomad", "searchad", "hotwordad",
        "recommendad", "experimentad", "abtestad", "miniappad", "miniprogramad", "landingad",
        "fakebuttonad", "fakeclosead", "clipboardad", "sharead", "serviceworkerad",
        "precachead", "widgetad", "shortcutad", "badgead", "lockscreenad", "wallpaperad",
        "commentad", "replyad", "danmakuad", "bulletad", "profilead", "followad",
        "inboxad", "messagead", "chatad", "topicad", "communityad", "socialad",
        "templatead", "tplad", "cloudad", "cloudcontrolad", "cloudconfigad", "layoutad",
        "assetpackad", "resourcepackad", "materialpackad", "creativepackad", "bundlead",
        "patchad", "hotpatchad", "couponad", "redpacketad", "cashad", "cashbackad",
        "subsidyad", "allowancead", "lotteryad", "bonusad",
        "productad", "shopad", "mallad", "goodsad", "itemad", "commercead", "shoppingad",
        "affiliatead", "cpsad", "commissionad", "rebatead", "taokead", "unionad",
        "locallifead", "nearbyad", "poiad", "mapad", "weatherad", "toolad", "cleanerad",
        "batteryad", "wifiad", "filemanagerad", "takeawayad", "hotelad", "travelad", "ridead",
        "leadgenad", "leadformad", "formad", "surveyad", "questionnairead", "trialad",
        "signupad", "reservationad", "calendarreminderad", "calendarsubscribead", "reminderad", "alarmad",
        "browserad", "startpagead", "homepagead", "newtabad", "speeddialad", "bookmarkad",
        "searchboxad", "hotsrchad", "hotsearchad", "trendingad", "sitenavad", "navcardad",
        "appstoread", "appinstallad", "appupdatead", "promotedappad", "preinstallad",
        "gamecenterad", "apkrankad", "apkad", "oemad", "romad", "systemmanagerad",
        "securityad", "boostad", "virusscanad", "storagecleanad", "negativescreenad",
        "tvad", "ottad", "castad", "screencastad", "wearad", "watchad", "carad",
        "carplayad", "iotad", "speakerad", "tabletad", "padad",
        "cnamead", "adalias", "aliasad", "cnamecloakad", "cloakedad", "adcloak",
        "dohad", "doqad", "dotad", "dnsqueryad", "encrypteddnsad", "httpdnsad",
        "alihttpdnsad", "tencenthttpdnsad", "baiduhttpdnsad", "adquic443", "quicad443"
        , "wasmad", "adwasm", "wasmloaderad", "jsloaderad", "obfuscatedad", "packedad",
        "encryptedjsad", "adfingerprint", "phashad", "imagehashad", "mediahashad",
        "watermarkad", "videofingerprintad", "endcardhashad", "framehashad",
        "http3ad", "h3ad", "udp443ad", "adudp443", "quicgatewayad", "http3gatewayad",
        "dataad", "data-ad", "addata", "ad-data", "logad", "log-ad", "adlog",
        "eventad", "event-ad", "trackad", "track-ad", "analyticad", "analytic-ad",
        "metricad", "metric-ad", "reportad", "report-ad", "collectad", "collect-ad",
        "uploadad", "upload-ad", "syncad", "sync-ad", "fetchad", "fetch-ad",
        "beead", "bee-ad", "yousuad", "yousu-ad", "adviewad", "adview-ad",
        "domobad", "domob-ad", "guozhenad", "guozhen-ad", "airpusad", "airpus-ad",
        "jiatuanad", "jiatuan-ad", "wubiad", "wubi-ad", "chuangyiad", "chuangyi-ad",
        "feiyuad", "feiyu-ad", "yixuanad", "yixuan-ad", "youmengad", "youmeng-ad",
        "dianruad", "dianru-ad", "guoanad", "guoan-ad", "admasterad", "admaster-ad",
        "jmadsad", "jmads-ad", "jpushad", "jpush-ad", "jiguangad", "jiguang-ad",
        "getuiad", "getui-ad", "igexinad", "igexin-ad", "gepushad", "gepush-ad",
        "unipushad", "unipush-ad", "cnzzad", "cnzz-ad", "allyesad", "allyes-ad",
        "mdotmad", "mdotm-ad", "miitad", "miit-ad", "madhousead", "madhouse-ad",
        "novelad", "novel-ad", "readingad", "reading-ad", "chapterad", "chapter-ad",
        "contentad", "content-ad", "episodead", "episode-ad", "dramaad", "drama-ad",
        "videoad", "video-ad", "audiobookad", "audiobook-ad", "podcastad", "podcast-ad",
        "streamad", "stream-ad", "livead", "live-ad", "replayad", "replay-ad",
        "highlightad", "highlight-ad", "clipad", "clip-ad", "shortad", "short-ad",
        "feedad", "feed-ad", "timelinead", "timeline-ad", "storyad", "story-ad",
        "momentad", "moment-ad", "postad", "post-ad", "articlead", "article-ad",
        "newsad", "news-ad", "infoad", "info-ad", "noticead", "notice-ad",
        "alertad", "alert-ad", "toastad", "toast-ad", "dialogad", "dialog-ad",
        "popupad", "popup-ad", "overlayad", "overlay-ad", "floatad", "float-ad",
        "stickyad", "sticky-ad", "anchorad", "anchor-ad", "cornerad", "corner-ad",
        "cornerad", "corner-ad", "edgead", "edge-ad", "borderad", "border-ad",
        "slidead", "slide-ad", "swipead", "swipe-ad", "scrollad", "scroll-ad",
        "pinchad", "pinch-ad", "zoomad", "zoom-ad", "rotatead", "rotate-ad",
        "flipad", "flip-ad", "fadead", "fade-ad", "slideinad", "slidein-ad",
        "slideoutad", "slideout-ad", "expandad", "expand-ad", "collapsead", "collapse-ad",
        "resizead", "resize-ad", "movead", "move-ad", "dragad", "drag-ad",
        "dropad", "drop-ad", "hoverad", "hover-ad", "clickad", "click-ad",
        "tapad", "tap-ad", "touchad", "touch-ad", "gesturead", "gesture-ad",
        "swipead", "swipe-ad", "longpressad", "longpress-ad", "doubletapad", "doubletap-ad",
        "ksadx", "ksad", "ksadnet", "ksadsystem",
        "wxadx", "wxadmob", "wxadsystem", "wxadnet",
        "qqadx", "qqadmob", "qqadsystem", "qqadnet",
        "bytedadx", "btdx", "bdad", "bdadsystem",
        "pangolinx", "pangleplus", "panglepro",
        "admost1x", "admostpro", "admostmax",
        "applovinx", "applovinmaxx", "applovinmaxsdk",
        "vunglex", "vunglepro", "vungle-max",
        "ironsrcx", "ironsrcpro", "ironsrcmax",
        "mintegralx", "mintegralpro", "mintegralmax",
        "appsflyertrack", "kochavatrk", "branchmetricsx",
        "tenjinsdk", "sensortowerx",
        "admixerx", "admixerpro",
        "malixx", "matabxx",
        "yandexx", "yandexpro", "yandexmax",
        "mailrux", "mailrupro",
        "amazonadx", "amazonpro",
        "yahooadx", "yahoopro", "yahoomax",
        "cntvad", "cntvadx", "cntvadnet",
        "tvbnadx", "tvbadsystem",
        "hunantvad", "hunantvadx",
        "iflytekad", "iflytekadx",
        "sogouinputad", "sogouinputadx",
        "baiduinputad", "baiduinputadx",
        "ucbrowserad", "ucbrowseradx",
        "qqbrowseradx", "qqbrowseradnet",
        "edgebrowserad", "edgebrowseradx",
        "samsunginternetad", "samsunginternetadx",
        "miuibrowserad", "miuibrowseradx",
        "huaweibrowserad", "huaweibrowseradx",
        "sellad", "sellads", "selladmob",
        "buyad", "buyads", "buyadmob",
        "purchasead", "purchaseads",
        "orderad", "orderads",
        "cartad", "cartads",
        "wishlistad", "wishlistads",
        "productpagead", "productpageads",
        "pdpad", "pdads",
        "tradead", "tradeadnet",
        "skuad", "skuads",
        "asinad", "asinads",
        "gpad", "gpadnet",
        "appid", "appidad",
        "guidad", "guidadnet",
        "queryad", "queryads",
        "breadcrumbad", "breadcrumbads",
        "reviewadnet", "reviewadads",
        "vipad", "vipads", "vipadx",
        "membershad", "memberad", "memberads",
        "newusersad", "newuserad",
        "oldusersad", "olduserad",
        "rebuyadnet", "rebuyads",
        "upsellad", "upsellads",
        "crosssellad", "crosssellads",
        "bundlead", "bundleads",
        "flashsalead", "flashsaleads",
        "limitedad", "limitedads",
        "couponadnet",
        "reward-pointsad",
        "clubadnet", "clubadads",
        "addressad", "addressads",
        "profileadnet", "profileadads",
        "orderstatusad",
        "trackadnet", "trackadads",
        "trackadapi",
        "trackadnetadnet", "trackadsgateway",
        "recommadnet",
        "homerecommendad", "feedrecommendad",
        "discoverrecommendad", "explorecommendad",
        "pindxad", "pinduoduoad",
        "taobaoad", "tmallad", "jingdongad",
        "mlsad", "mlsads",
        "tmalladnet", "taobaoadnet",
        "meishijd", "meishijdad",
        "dianpingx", "dianpingadx",
        "xunfeiad", "xunfeiadx",
        "qingtingad", "qingtingads",
        "ximalayaad", "ximalayaadx",
        "neteasecloudad", "neteasecloudadx",
        "qmusicad", "qmusicadx",
        "miguad", "miguadx",
        "kugo-adx", "kugou-ad-net",
        "lizhiad", "lizhiadx",
        "huayangad", "huayangadx",
        "zhihuzhad",
        "qidianx", "qidianxadx",
        "qidianadadx",
        "falooxad", "falooxadx",
        "jjwxcx", "jjwxad",
        "faloodad", "faloodadx",
        "chaoxingad", "chaoxingadx",
        "zhihuadnet", "zhihuadadx",
        "missevanad", "missevanadx",
        "weiboqx", "weiboqxad",
        "wbsdkad", "wbsdkadx",
        "ifmoad", "ifmoadx",
        "ifload", "ifloadx",
        "ifmadsystem", "ifmoadnet",
        "ifloadnet", "ifloadadsystem"
    )

    private val adSdkPatterns = listOf(
        ".adsdk.", ".adservice.", ".adnetwork.", ".adserver.",
        ".adtrack.", ".adtracker.", ".admanager.", ".adplatform.",
        ".ssp.", ".dsp.", ".adx.", ".rtb.", ".mediation.",
        ".bidding.", ".auction.", ".offerwall.", ".rewardvideo."
    )

    private val protectedCommunityDomains = listOf("coolapk.com", "coolapkmarket.com")

    private val novelAdInfraPatterns = listOf(
        ".ad.", ".ads.", ".adx.", ".dsp.", ".ssp.", ".rtb.",
        ".tracking.", ".tracker.", ".analytics.", ".stat.", ".report.", ".monitor.",
        "adservice", "adserver", "adtrack", "adlog", "adreport", "adsdk", "sdkad",
        "reward", "excitation", "inspire", "splash", "launch", "startup", "preload",
        "welfare", "taskcenter", "task_center", "coinreward", "readingbonus", "offerwall", "monetize",
        "admaterial", "materialurl", "creative", "creativeid", "landingurl", "clickurl", "showurl",
        "monitorurl", "impression", "playable", "endcard", "waterfall", "mediation", "bidding",
        "auction", "placement", "slotid", "templateid", "rewardvideo", "open_screen", "startup_preload",
        "launch_preload", "commentflowad", "replyflowad", "feedinsertad", "timelineinsertad",
        "adapi", "adsapi", "adgateway", "adloader", "adrequest", "adlog", "adreport",
        "admetrics", "imptrack", "clicktrack", "viewtrack", "adcache", "adconfig", "sdkconfig",
        "adrouter", "adrelay", "adbridge", "adtrace", "bidrequest", "bidresponse", "skadnetwork", "skadn",
        "httpdns", "adresolver", "adstream", "wsad", "ssead", "grpcad", "protobufad",
        "adplugin", "admodule", "dynamicad", "adquic", "adtcp", "adudp", "binaryad",
        "marketad", "installad", "downloadad", "deeplinkad", "shakead", "notifyad", "pushad",
        "taskad", "missionad", "welfaread", "benefitad", "coinad", "offerwallad",
        "gamead", "revivead", "livead", "liveroomad", "searchad", "hotwordad",
        "recommendad", "experimentad", "abtestad", "miniappad", "miniprogramad", "landingad",
        "fakebuttonad", "fakeclosead", "clipboardad", "sharead", "serviceworkerad",
        "precachead", "widgetad", "shortcutad", "badgead", "lockscreenad", "wallpaperad",
        "commentad", "replyad", "danmakuad", "bulletad", "profilead", "followad",
        "inboxad", "messagead", "chatad", "topicad", "communityad", "socialad",
        "templatead", "tplad", "cloudad", "cloudcontrolad", "cloudconfigad", "layoutad",
        "assetpackad", "resourcepackad", "materialpackad", "creativepackad", "bundlead",
        "patchad", "hotpatchad", "couponad", "redpacketad", "cashad", "cashbackad",
        "subsidyad", "allowancead", "lotteryad", "bonusad",
        "productad", "shopad", "mallad", "goodsad", "itemad", "commercead", "shoppingad",
        "affiliatead", "cpsad", "commissionad", "rebatead", "taokead", "unionad",
        "locallifead", "nearbyad", "poiad", "mapad", "weatherad", "toolad", "cleanerad",
        "batteryad", "wifiad", "filemanagerad", "takeawayad", "hotelad", "travelad", "ridead",
        "leadgenad", "leadformad", "formad", "surveyad", "questionnairead", "trialad",
        "signupad", "reservationad", "calendarreminderad", "calendarsubscribead", "reminderad", "alarmad",
        "browserad", "startpagead", "homepagead", "newtabad", "speeddialad", "bookmarkad",
        "searchboxad", "hotsrchad", "hotsearchad", "trendingad", "sitenavad", "navcardad",
        "appstoread", "appinstallad", "appupdatead", "promotedappad", "preinstallad",
        "gamecenterad", "apkrankad", "apkad", "oemad", "romad", "systemmanagerad",
        "securityad", "boostad", "virusscanad", "storagecleanad", "negativescreenad",
        "tvad", "ottad", "castad", "screencastad", "wearad", "watchad", "carad",
        "carplayad", "iotad", "speakerad", "tabletad", "padad",
        "cnamead", "adalias", "aliasad", "cnamecloakad", "cloakedad", "adcloak",
        "dohad", "doqad", "dotad", "dnsqueryad", "encrypteddnsad", "httpdnsad",
        "alihttpdnsad", "tencenthttpdnsad", "baiduhttpdnsad", "adquic443", "quicad443"
        , "wasmad", "adwasm", "wasmloaderad", "jsloaderad", "obfuscatedad", "packedad",
        "encryptedjsad", "adfingerprint", "phashad", "imagehashad", "mediahashad",
        "watermarkad", "videofingerprintad", "endcardhashad", "framehashad",
        "http3ad", "h3ad", "udp443ad", "adudp443", "quicgatewayad", "http3gatewayad"
    )

    fun extractRegexRuleDomain(
        pattern: String,
        sanitizeDomain: (String) -> String?,
        domainExtractRegex: Regex,
        domainSubdomainRegex: Regex
    ): String? {
        val normalized = pattern
            .replace("\\.", ".")
            .replace("\\-", "-")
            .replace("\\/", "/")
        val directMatch = domainExtractRegex
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
        val sanitizedDirect = directMatch?.let(sanitizeDomain)
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

    fun keywordMatches(domain: String, normalizedTokens: String, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        if (keyword.length <= 2) {
            // 单遍遍历找分隔 label，避免 split+filter 分配
            var i = 0
            val n = domain.length
            while (i < n) {
                while (i < n && (domain[i] == '.' || domain[i] == '-' || domain[i] == '_')) i++
                val start = i
                while (i < n && domain[i] != '.' && domain[i] != '-' && domain[i] != '_') i++
                if (i > start && i - start == keyword.length && domain.regionMatches(start, keyword, 0, keyword.length)) {
                    return true
                }
            }
            return false
        }
        return domain.contains(keyword) || normalizedTokens.contains(keyword)
    }

    fun looksLikePushRecommendationAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        val normalizedTokens = lower.replace(normalizedTokenRegex, "")
        val pushHits = pushSignals.filter { keywordMatches(lower, normalizedTokens, it) }
        val recommendHits = recommendationSignals.filter { keywordMatches(lower, normalizedTokens, it) }
        val triggerHits = pushHits + recommendHits
        if (triggerHits.isEmpty()) return false
        return pushRecommendAdSignals.any { adSignal ->
            keywordMatches(lower, normalizedTokens, adSignal) && adSignal !in triggerHits
        }
    }

    fun looksLikeAdSdkInfraDomain(
        domain: String,
        vendor: String,
        defaultVendor: String,
        sanitizeDomain: (String) -> String?,
        normalizeVendorName: (String) -> String,
        highConfidenceAdSdkDomains: Set<String>,
        highConfidenceAdSdkVendors: Set<String>
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lower = normalized.lowercase()
        val normalizedTokens = lower.replace(normalizedTokenRegex, "")
        val normalizedVendor = normalizeVendorName(vendor.ifBlank { defaultVendor })
        if (highConfidenceAdSdkDomains.any { normalized == it || normalized.endsWith(".$it") }) {
            return true
        }
        if (normalizedVendor in highConfidenceAdSdkVendors) {
            return true
        }
        if (sdkInfraSignals.any { matchesSdkTokenStrict(lower, normalizedTokens, it) }) return true
        return sdkVendorSignals.any { matchesSdkTokenStrict(lower, normalizedTokens, it) }
    }

    private fun matchesSdkTokenStrict(domain: String, normalizedTokens: String, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        if (keyword.length <= 5 && '.' !in keyword && '-' !in keyword && '_' !in keyword) {
            return labelBoundaryContains(domain, keyword)
        }
        return keywordMatches(domain, normalizedTokens, keyword)
    }

    fun isProtectedByteDanceInfraDomain(
        domain: String,
        sanitizeDomain: (String) -> String?,
        byteDanceInfraProtectedSuffixes: Set<String>,
        novelAggressiveExactDomains: Set<String>
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (!byteDanceInfraProtectedSuffixes.any { normalized == it || normalized.endsWith(".$it") }) return false
        if (novelAggressiveExactDomains.contains(normalized)) return false
        val lower = normalized.lowercase()
        return protectedByteDanceStrongAdInfraMarkers.none(lower::contains)
    }

    fun looksLikeWhitelistedRootAdSubdomain(
        domain: String,
        looksLikePushRecommendationAdDomain: (String) -> Boolean,
        looksLikeAdSdkInfraDomain: (String) -> Boolean
    ): Boolean {
        val lower = domain.lowercase()
        if (whitelistedRootAdSubdomainMarkers.any { marker -> lower.startsWith(marker) || lower.contains(".$marker") }) return true
        if (looksLikePushRecommendationAdDomain(lower)) return true
        return looksLikeAdSdkInfraDomain(lower) && whitelistedRootAdSubdomainExtraSignals.any(lower::contains)
    }

    fun looksLikeAdDomain(
        domain: String,
        adKeywords: List<String>,
        weakAdKeywords: Set<String>,
        isLowValueSuspiciousSampleDomain: (String) -> Boolean,
        looksLikePushRecommendationAdDomain: (String) -> Boolean,
        looksLikeAdSdkInfraDomain: (String) -> Boolean
    ): Boolean {
        val lower = domain.lowercase()
        if (isLowValueSuspiciousSampleDomain(lower)) return false

        // 单遍 char 遍历同时计算 normalized string 和 labels list，避免 `replace(regex)` + `split+filter` 双份分配
        val normalizedBuilder = StringBuilder(lower.length)
        val labelsAccumulator = ArrayList<String>(8)
        val labelBuilder = StringBuilder(16)
        for (i in 0 until lower.length) {
            val c = lower[i]
            if (c == '.' || c == '-' || c == '_') {
                if (labelBuilder.isNotEmpty()) {
                    labelsAccumulator.add(labelBuilder.toString())
                    labelBuilder.setLength(0)
                }
            } else {
                normalizedBuilder.append(c)
                labelBuilder.append(c)
            }
        }
        if (labelBuilder.isNotEmpty()) {
            labelsAccumulator.add(labelBuilder.toString())
        }
        val normalizedTokens = normalizedBuilder.toString()
        val labels = labelsAccumulator

        val baseMatch = adKeywords.any { keyword ->
            if (keyword in weakAdKeywords) {
                labels.any { it == keyword || it.startsWith("$keyword-") || it.endsWith("-$keyword") }
            } else {
                keywordMatches(lower, normalizedTokens, keyword)
            }
        }
        if (baseMatch) return true

        if (labels.any(strongAdLabels::contains)) return true

        if (adSdkPatterns.any { it in lower }) return true

        if (protectedCommunityDomains.any { lower == it || lower.endsWith(".$it") }) return false

        if (alphanumericAdPattern.containsMatchIn(lower)
            && !lower.contains("dad")
            && !lower.contains("grad")
            && !lower.contains("ead")
            && !lower.contains("jade")
            && !lower.contains("ladder")
            && !lower.contains("saddle")
        ) return true

        if (novelAdInfraPatterns.any { pattern -> lower.contains(pattern) }) return true
        if (looksLikePushRecommendationAdDomain(lower)) return true
        return looksLikeAdSdkInfraDomain(lower)
    }
}
