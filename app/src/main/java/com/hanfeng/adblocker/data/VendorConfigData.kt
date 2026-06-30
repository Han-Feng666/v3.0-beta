package com.HanFeng.data

internal object VendorConfigData {

    val novelVendorNames = setOf(
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

    val novelAppIdentifiers = listOf(
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

    val novelAppProtectedSuffixes = setOf(
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

    val gameCoreDomains = setOf(
        "gamehelper.com.cn", "act.qq.com", "imgcache.qq.com",
        "gamedl.qq.com", "game.qq.com", "gamesafe.qq.com", "gameinfo.qq.com",
        "gamecenter.qq.com", "sso.10.qq.com", "open.id.qq.com",
        "ssl.ptlogin2.qq.com", "ptlogin2.qq.com",
        "dl.dir.qq.com", "dlied1.qq.com", "dlied2.qq.com",
        "dlied3.qq.com", "dlied4.qq.com", "dlied5.qq.com", "dlied6.qq.com",
        "mihoyo.com", "mihayo.com", "yuanshen.com", "hoyolab.com",
        "hoyoverse.com", "bhsr.com", "starrails.com",
        "game.163.com",
        "cdndm.com", "cdn.hockeyapp.net", "fir.im"
    )

    val socialCoreDomains = setOf(
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
        "coolapk.com", "coolapkmarket.com",
        "qpay.tf.qq.com", "qpay.qq.com", "tenpay.com", "paipai.com"
    )

    val mediaCoreDomains = setOf(
        "music.qq.com", "y.qq.com", "qqmusic.qq.com", "stream.qqmusic.qq.com", "dl.stream.qqmusic.qq.com",
        "kg.qq.com", "kgimg.com", "kugou.com", "kugoucdn.com", "kglink.cn", "staticssl.kugou.com",
        "kuwo.cn", "kuwo.com", "kuwoapp.com", "kwimgs.com", "kuwo.cn",
        "music.163.com", "music.126.net", "126.net", "nosdn.127.net", "vod.126.net",
        "ximalaya.com", "ximaimg.com", "xmcdn.com", "ximaimg.cn",
        "qingting.fm", "qtfm.cn", "qingtingcdn.com",
        "lizhi.fm", "lizhi.io"
    )

    val businessCoreDomains = setOf(
        "alidrive.com", "aliyundrive.com", "aliyuncs.com", "drive.uc.cn",
        "cloud.189.cn", "115.com", "pan.baidu.com", "yunpan.360.cn",
        "docs.qq.com", "doc.weixin.qq.com", "shimo.im", "feishu.cn", "feishu.net",
        "larkoffice.com", "bytedocs.com", "yuque.com", "notion.so",
        "amap.com", "autonavi.com", "amapapis.com", "didialift.com", "didichuxing.com",
        "meituan.com", "sankuai.com", "ele.me", "eleme.cn",
        "alipay.com", "alipay.cn", "tenpay.com", "unionpay.com", "95516.com"
    )

    val novelContentApiDomains = setOf(
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
        "api.qimao.com",
        "webnovel.qimao.com",
        "reader-api.kmxs.com",
        "api-ks.wtzw.com",
        "bookapi.qidian.com",
        "read.qidian.com",
        "trader.qidian.com",
        "druidv6.if.qidian.com",
        "book.qqreader.com",
        "reader.qq.com",
        "api.weread.qq.com",
        "api.shuqi.com",
        "reader.aliwx.com",
        "capi.shuqireader.com",
        "api.ireader.com",
        "book.zhangyue.com",
        "api.cmread.com",
        "api.migu.com",
        "api.midu.com",
        "api.zongheng.com",
        "api.17k.com",
        "api.changdu.com",
        "api.dejian.com",
        "api.hongguo.com"
    )

    val novelAggressiveVendorNames = setOf(
        "优比客思 (UBIX Ads)",
        "QXM (QXM Ads)",
        "中关互动 (ZGHD)",
        "趣盟广告 (Qumeng Ads)",
        "AdScope 聚合广告 (AdScope)",
        "通用广告/追踪 (Generic Ad/Tracking)"
    )

    val novelAggressiveExactDomains = setOf(
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

    val highConfidenceAdSdkDomains = setOf(
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

    val byteDanceInfraProtectedSuffixes = setOf(
        "bytegecko.com",
        "pstatp.com",
        "snssdk.com",
        "fqnovelstatic.com",
        "byteimg.com",
        "ibytedtos.com",
        "bytedtos.com",
        "zijieapi.com"
    )

    val fanqieProtectedAdPathKeywords = listOf(
        "/ad/", "/ads/", "/adx/", "/advert/", "/advertisement/", "/union/", "/sdk/union/",
        "/reward/", "/rewarded/", "/excitation/", "/inspire/", "/banner/", "/feed_ad/",
        "/bottom_banner/", "/floating_banner/", "/common/banner/", "/native/banner/",
        "/draw_ad/", "/ad_plan/", "/ad_request/", "/ad_style/", "/ad_config/", "/ad_info/",
        "/launch/", "/startup/", "/open_screen/", "/splash/", "/feed/banner/", "/popup/",
        "/welfare/", "/task/", "/task_center/", "/coin/", "/bonus/", "/benefit/", "/offerwall/"
    )

    val bypassProtectionDomains = setOf(
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

    val vendorPatterns = linkedMapOf(
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

    val vendorKeywords = linkedMapOf(
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

    val vendorSdkIdentifiers = linkedMapOf(
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

    val vendorAliases = mapOf(
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

    val highConfidenceAdSdkVendors = setOf(
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
}
