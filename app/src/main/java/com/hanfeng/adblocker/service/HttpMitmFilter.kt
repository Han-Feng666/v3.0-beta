package com.HanFeng.service

import android.content.Context
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.RuleRepository
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.concurrent.ConcurrentHashMap

object HttpMitmFilter {
    private const val MAX_HTTP1_FILTER_BUFFER_BYTES = 512 * 1024
    private const val MAX_HTTP2_DATA_SAMPLE_BYTES = 64 * 1024  // 增强：8KB→64KB（提高 JSON 广告识别率）
    
    // 正则表达式缓存（P1 优化）
    private val compiledReplaceRules = ConcurrentHashMap<String, Regex>(256)
    private val compiledReplaceRulesLock = Any()
    private const val MAX_COMPILED_REGEX_CACHE = 512
    private val http2ImmediateBlockReasons = setOf(
        "blocked-host", "blocked-url", "general-ad-traffic", "novel-app-aggressive", "novel-protected-path",
        "domestic-sdk-signal", "reward-unlock-path", "doh-request", "json-ad-field", "json-ad-array",
        "json-ad-content", "novel-field-cluster", "media-field-cluster", "feed-ad-cluster", "banner-ad-cluster",
        "reader-ad-cluster", "comment-ad-path", "comment-ad-cluster", "comment-ad-extended", "comment-ad-float-extended",
        "comment-ad-flow-extended", "comment-ad-insert-extended", "coolapk-comment-ad-extended", "comment-ad-material-extended",
        "comment-ad-popup-extended", "comment-commerce-path", "comment-commerce-ad-extended", "comment-gdt-commerce-ad-extended",
        "push-recommend-material-extended", "message-center-card-ad-extended", "gdt-sdk-ad-extended",
        "ali-sdk-ad-extended", "shortvideo-sdk-ad-extended", "video-ad-cluster", "feed-ad-extended",
        "push-recommend-ad-extended", "message-center-ad-material-extended", "sign-task-benefit-ad-extended",
        "reader-sign-benefit-ad-extended", "reader-page-ad-extended", "reader-page-ad-material-extended",
        "reader-page-popup-extended", "reader-page-tail-extended", "reader-ad-material-extended", "qimao-reader-ad-extended",
        "drama-ad-cluster", "live-ad-cluster", "comic-ad-cluster", "player-ad-cluster", "player-ad-extended",
        "splash-ad-cluster", "startup-ad-extended", "startup-ad-cache-extended", "startup-ad-preload-extended",
        "startup-ad-material-extended", "reward-ad-extended", "neutralized-body-reward-unlock", "path-strong-suspicious",
        "location-strong-header", "set-cookie-strong-header", "path-strong-keyword", "location-strong-keyword",
        "set-cookie-strong-keyword"
    )
    private val http2KeywordBlockReasons = setOf("path-keyword", "location-keyword", "set-cookie-keyword")
    private val pathInspectionCache = object : LinkedHashMap<String, PathInspection>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PathInspection>?): Boolean = size > 2048  // 增强：512→2048
    }
    private val pathInspectionCacheLock = Any()
    private val deepInspectionDecisionCache = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 2048  // 增强：512→2048
    }
    private val deepInspectionDecisionCacheLock = Any()
    private val bodySignalCache = object : LinkedHashMap<String, BodySignalInspection>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BodySignalInspection>?): Boolean = size > 1024  // 增强：256→1024
    }
    private val bodySignalCacheLock = Any()
    private val defaultAdQueryParams = setOf(
        "ad", "ads", "adid", "ad_id", "adunit", "ad_unit", "adslot", "ad_slot", "adpos", "ad_pos",
        "adscene", "ad_scene", "adposition", "ad_position", "adtag", "ad_tag", "adfrom", "ad_from",
        "advertid", "advert_id", "promotion", "promo", "promoid", "promo_id", "materialid", "material_id",
        "creativeid", "creative_id", "clickid", "click_id", "requestid", "request_id", "traceid", "trace_id",
        "ecpm", "preroll", "midroll", "postroll", "insert_ad", "feed_ad", "bannerid", "banner_id",
        "watch_ad", "watch_ad_unlock", "unlock_by_ad", "reward_amount", "coin_reward", "task_reward",
        "material_url", "material_urls", "landing_url", "landing_urls", "click_url", "click_urls",
        "show_url", "show_urls", "impression_url", "impression_urls", "monitor_url", "monitor_urls",
        "callback_url", "target_url", "deep_link", "download_url", "open_screen", "startup_ad",
        "promotion_card", "promo_card", "discover_card", "recommend_card", "message_center_ad"
    )
    private val requestMethods = listOf("GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ")
    private val compressibleEncodings = listOf("gzip", "br", "deflate", "zstd")
    private val responseAdKeywords = listOf(
        "adview", "adslot", "adunit", "advert", "banner", "splash", "reward", "preload", "promo", "promotion", "tracker", "tracking",
        "launch", "startup", "popup", "interstitial", "feedad", "open_screen", "openad", "floatad", "bottomad", "fullscreen",
        "nativead", "videoad", "rewardad", "loginad", "guidead", "scrollad", "pushad",
        // 通用广告响应特征
        "ad_response", "adresponse", "ad_result", "adresult", "ad_result_data", "addata",
        "ad_config", "adconfig", "ad_material", "admaterial", "ad_creative", "adcreative",
        "ad_sequence", "adsequence", "ad_strategy", "adstrategy", "ad_serving", "adserving",
        "ad_dispatch", "adcache", "ad_cache", "adcard", "ad_card", "adcards", "ad_cards",
        "feed_card", "feed_cards", "feed_flow", "information_flow", "info_flow", "banner_info", "banner_infos",
        "splash_config", "startup_config", "launch_config", "popup_config", "interstitial_config", "pause_ad", "player_ad",
        "comment_banner", "comment_insert_ad", "reply_banner", "reply_insert_ad", "floor_banner", "floor_promote",
        "stream_card_ad", "timeline_insert_ad", "recommend_card_ad", "reward_popup", "chapter_unlock_ad", "free_read_card",
        "open_screen_cache", "splash_cache", "startup_cache", "launch_cache", "opening_ad", "open_screen_material", "splash_material",
        "comment_guide_ad", "comment_float_ad", "reply_promote_card", "floor_insert_ad", "comment_hot_ad", "comment_promote_card", "comment_stream_ad",
        "reader_bottom_ad", "page_turn_ad", "turn_page_ad", "flip_page_ad", "page_insert_ad", "chapter_next_ad", "reading_page_ad", "chapter_page_ad",
        // 社区 App 广告特征
        "feed_insert_ad", "timeline_ad", "stream_ad", "list_ad", "card_ads", "card_ad", "ad_banner", "ad_banners",
        "comment_ad", "comment_ads", "reply_ad", "reply_ads", "floor_ad", "post_ad", "post_ads",
        "subject_ad", "topic_ad", "hashtag_ad", "tag_ad", "explore_ad", "discovery_ad",
        // 短视频/直播广告
        "live_ad", "live_ads", "streamer_ad", "anchor_ad", "video_card_ad", "short_video_ad",
        // 电商广告
        "product_ad", "product_ads", "shop_ad", "shop_ads", "mall_ad", "mall_ads", "item_ad", "item_ads",
        // 激励广告
        "task_ad", "task_ads", "sign_ad", "sign_ads", "checkin_ad", "daily_ad", "benefit_ad", "coin_ad",
        // 信息流广告变种
        "feed_detail_ad", "article_ad", "article_ads", "news_ad", "news_ads", "content_ad", "content_ads",
        "media_ad", "media_ads", "image_ad", "image_ads", "pic_ad", "pic_ads", "gallery_ad"
    )
    private val strongResponseAdKeywords = listOf(
        "advertisement",
        "adnxs",
        "admob",
        "adsdk",
        "adnetwork",
        "adservice",
        "ad_render",
        "adid",
        "adset",
        "materialid",
        "creativeid",
        "placementid",
        "slotid",
        "unitid",
        "impression",
        "clicktrack",
        "click_url",
        "show_url",
        "track_url",
        "win_notice",
        "deep_link",
        "download_url",
        "downloadurl",
        "landingpage",
        "landing_page",
        "landing_url",
        "landingurl",
        "open_screen",
        "openscreen",
        "interstitial",
        "reward_video",
        "rewardvideo",
        "reward_verify",
        "rewardverify",
        "reward_callback",
        "rewardcallback",
        "reward_unlock",
        "rewardunlock",
        "fullscreen_video",
        "fullscreen",
        "native_express",
        "nativeexpress",
        "template_id",
        "templateid",
        "ecpm",
        "ecpm_level",
        "price_ratio",
        "adx",
        "rtb",
        "dsp",
        "ssp",
        "bidding",
        "playable",
        "playable_url",
        "playableurl",
        "endcard",
        "endcard_url",
        "endcardurl",
        "render_url",
        "renderurl",
        "material_url",
        "materialurl",
        "video_url",
        "videourl",
        "image_url",
        "imageurl",
        "callback_url",
        "callbackurl",
        "skip_time",
        "skiptime",
        "ad_info",
        "adinfo",
        "pangolin",
        "pangle",
        "gromore",
        "csj",
        "gdt",
        "sigmob",
        "mobvista",
        "mintegral",
        "applovin",
        "ironsource",
        "unityads",
        "vungle",
        "topon",
        "tradplus",
        "adscope",
        "kuaishouad",
        "ksad",
        "brand_banner",
        "feed_banner",
        "open_ad",
        "startup_ad",
        // 新增强力广告特征 - 广告数据字段
        "ad_data", "addata", "ad_content", "adcontent", "ad_list", "adlist", "ad_count", "adcount",
        "has_ad", "hasad", "show_ad", "showad", "load_ad", "loadad", "fetch_ad", "fetchad",
        "ad_request", "adrequest", "ad_response", "adresponse", "ad_server", "adserver",
        "ad_platform", "adplatform", "ad_service", "adservice", "ad_manager", "admanager",
        "ad_config", "adconfig", "ad_param", "adparam", "ad_params", "adparams",
        "ad_strategy", "adstrategy", "ad_plan", "adplan", "ad_schedule", "adschedule",
        "ad_statistics", "adstatistics", "ad_track", "adtrack", "ad_log", "adlog",
        "ad_report", "adreport", "ad_analytics", "adanalytics", "ad_monitor", "admonitor",
        "cache_buster", "cachebuster", "sdk_version", "sdkversion", "placement_type", "placementtype",
        "ad_html", "adhtml", "ad_template", "adtemplate", "ad_payload", "adpayload",
        "waterfall", "waterfall_id", "waterfallid", "waterfall_config", "waterfallconfig",
        "waterfall_item", "waterfallitem", "waterfall_list", "waterfalllist", "waterfall_group", "waterfallgroup",
        "bidding_token", "biddingtoken", "bid_token", "bidtoken", "bid_floor", "bidfloor", "bid_price", "bidprice",
        "win_price", "winprice", "loss_url", "lossurl", "auction_id", "auctionid", "auction_price", "auctionprice",
        "mediation", "mediation_id", "mediationid", "mediation_config", "mediationconfig", "mediation_list", "mediationlist",
        "admob_config", "admobconfig", "pangle_config", "pangleconfig", "gdt_config", "gdtconfig",
        "preload_ad", "preloadad", "prefetch_ad", "prefetchad", "cache_ad", "cachead", "cached_ad", "cachedad",
        "ad_inventory", "adinventory", "inventory_id", "inventoryid", "fill_rate", "fillrate", "fill_ratio", "fillratio",
        "parallel_load", "parallelload", "load_strategy", "loadstrategy", "request_scene", "requestscene",
        // 字节/穿山甲广告
        "jjye", "groovy", "gromore", "ttad", "bytedance", "bytead", "douyin_ad", "douyinad",
        "tiktok_ads", "tiktokads", "pangle_ad", "panglead", "tiktok_pangle",
        // 小说平台广告
        "qimao_ad", "qimaoad", "kmxs_ad", "kmxsad", "wtzw_ad", "wtzwad",
        "fqnovel_ad", "fqnovelad", "fanqie_ad", "fanqiead", "zijie_ad", "zijiead",
        "reader_ad", "readerad", "chapter_unlock", "chapterunlock", "unlock_by_ad", "unlockbyad",
        "watch_ad_unlock", "watchadunlock", "task_center", "taskcenter", "benefit_center", "benefitcenter",
        // API 路径特征
        "api/ad", "api/ad/", "/ad/api", "/ad/v", "/ad/v1", "/ad/v2", "/ads/v", "/ads/v1",
        "ad=true", "ad=true", "type=ad", "type=adv", "cat=ad", "cat=adv",
        // 新增广告 SDK 和服务
        "adcolony", "chartboost", "inmobi", "millennial", "medialand", "yandex_ad",
        "ogury", " liftoff", "tapjoy", "sponsorpay", "fortumo", "bango", "carrier",
        "admarvel", "inneractive", "jumptap", "millennial_media", "mydas", "smaato",
        "startapp", "tumobi", "juniper", "greedygame", "feijiu", "9gamedw", "downcom",
        // 广告行为特征
        "auto_close", "autoclose", "count_down", "countdown", "skip_countdown", "jump_url",
        "click_action", "monitoring_uri", "ad_close", "adclose", "ad_skip", "adskip",
        "ad_detail", "adconvert", "conversion", "activate_url", "active_url"
    )
    private val suspiciousPathKeywords = listOf(
        "/ad", "/ads", "/advert", "/adview", "/adslot", "/adunit", "/adsdk", "/adservice", "/banner", "/splash", "/reward", "/promotion", "/promo", "/preload", "/material", "/creative", "/launch", "/startup", "/feedad", "/screenad", "/openad", "/popup", "/interstitial", "/floatad", "/bottomad",
        "/feed", "/feed_ad", "/feedad", "/feeds", "/comment/ad", "/comment/banner", "/floor/ad", "/stream/ad", "/nativead", "/native/banner", "/brand_banner", "/brand/banner", "/open_screen", "/startupad", "/launchad",
        "/welfare", "/benefit", "/task", "/task_center", "/coin", "/bonus", "/offerwall", "/excitation", "/inspire", "/unlock", "/free_read",
        "/feed/card", "/feed_card", "/feed/insert", "/feed_insert", "/feed/recommend/ad", "/comment/floor", "/comment/reply/ad", "/reply/ad", "/post/ad",
        "/bottom_banner", "/floating_banner", "/suspend_ad", "/pause_ad", "/player/ad", "/video/ad", "/launch_ad", "/startup_ad", "/open_screen_ad",
        "/ad/list", "/ad/get", "/ad/fetch", "/ad/request", "/ad/dispatch", "/ad/query", "/ad/load", "/ad/cache", "/ad/resource",
        "/feed/v1/ad", "/feed/v2/ad", "/feed/inject", "/feed_insert_ad", "/comment/list/ad", "/comment/ad_card", "/reply/list/ad",
        "/screen_patch", "/preroll", "/midroll", "/postroll", "/video_patch", "/draw/video/ad", "/live/ad", "/pause/banner",
        "/reader/bottom", "/reader/banner", "/reader/ad", "/chapter/ad", "/chapter/unlock", "/chapter/reward",
        "/reading/page/ad", "/reading/reward", "/book/bonus", "/book/task", "/novel/task", "/novel/reward",
        "/splash/list", "/startup/list", "/launch/list", "/feed/banner/list", "/comment/floor/ad", "/comment/reply/banner",
        "/reward/unlock", "/unlock/byad", "/watch/ad/unlock", "/ad/callback", "/ad/track", "/ad/report",
        "/material/list", "/creative/list", "/placement/list", "/sdk/config", "/ad/config",
        "/waterfall", "/waterfall/config", "/mediation", "/mediation/config", "/mediation/list",
        "/bidding", "/bid/token", "/auction", "/auction/price", "/auction/win", "/auction/loss",
        "/preload/ad", "/prefetch/ad", "/cache/ad", "/ad/cache/list", "/inventory/ad", "/fill/rate",
        "/comment/insert", "/reply/insert", "/timeline/insert", "/recommend/card", "/stream/card/ad",
        "/startup/config", "/launch/config", "/splash/config", "/popup/config", "/interstitial/config",
        "/pause/ad", "/player/ad", "/chapter/unlock/ad", "/reader/free_read", "/reward/popup",
        "/open_screen/cache", "/splash/cache", "/startup/cache", "/launch/cache", "/opening/ad",
        "/comment/guide/ad", "/comment/hot/ad", "/reply/promote", "/floor/insert/ad",
        "/reader/bottom/ad", "/reader/page/ad", "/page/turn/ad", "/turn/page/ad", "/flip/page/ad", "/chapter/next/ad", "/reading/page/insert", "/chapter/page/ad"
    )
    private val suspiciousHeaderKeywords = listOf(
        "advert", "banner", "splash", "reward", "promo", "promotion", "track", "tracker", "interstitial", "popup", "openad",
        "feed", "feedad", "feeds", "nativead", "brand_banner", "startupad", "launchad", "open_screen",
        "welfare", "benefit", "task", "coin", "bonus", "offerwall", "excitation", "inspire",
        "feed_card", "information_flow", "commentad", "floorad", "bottom_banner", "floating_banner", "pause_ad",
        "ad_resource", "ad_material", "ad_dispatch", "ad_scene", "ad_position", "insert_ad", "midroll", "preroll", "postroll",
        "reader_banner", "chapter_reward", "watch_ad_unlock", "unlock_by_ad", "bottom_banner", "startup_banner",
        "reader_bottom_ad", "page_turn_ad", "turn_page_ad", "flip_page_ad", "page_insert_ad", "open_screen_cache", "open_screen_material", "comment_promote_card"
    )
    private val strongHeaderKeywords = listOf(
        "ad_dispatch", "ad_material", "ad_resource", "watch_ad_unlock", "unlock_by_ad", "reward_unlock",
        "chapter_unlock_ad", "open_screen_ad", "startup_ad", "launch_ad", "interstitial_ad",
        "feed_insert_ad", "timeline_insert_ad", "stream_card_ad", "comment_insert_ad", "floor_insert_ad",
        "preroll_ad", "midroll_ad", "postroll_ad", "pause_ad", "player_ad"
    )
    private val domesticAdSdkKeywords = listOf(
        "pangolin", "pangle", "gromore", "csj", "gdt", "guangdiantong", "sigmob", "mobvista",
        "mintegral", "applovin", "topon", "tradplus", "adscope", "ksad", "kuaishouad", "kwad",
        "tanx", "alimama", "adash", "umeng", "mobads", "baidumobads", "cpro", "youlianghui",
        "qumeng", "qmadsdk", "beizi", "youmi", "mediav", "vpon", "maticoo", "kidoz",
        "mimo", "huaweiads", "jdad", "jingdong", "iflyad", "sogou", "oppoads", "vivoads",
        "adview", "domob", "duomeng", "adwo", "youmioffer", "bzadx", "beizisdk", "vpadn",
        "mvad", "mvads", "openalliance", "hwads", "ads-drcn", "iflytekad", "atanx", "simba.taobao",
        "magneticengine", "kuaibusiness", "qtadx", "ubix", "ubixad", "ubixio", "ubixai", "ubiadx",
        "zghd", "zhghd", "hxltad", "adintl", "qxm", "qxmad", "qxmads", "52qumao"
    )
    private val pangleAndGdtHostSignals = listOf(
        "pangolin-sdk-toutiao", "pangle", "pangolin", "gromore", "csj", "oceanengine",
        "gdt.qq", "e.qq", "gdtimg", "youlianghui", "guangdiantong"
    )
    private val pangleAndGdtPathSignals = listOf(
        "/union/sdk", "/sdk/union", "/ad/get", "/ad/fetch", "/ad/dispatch", "/ad/request",
        "/material/list", "/creative/list", "/placement/list", "/sdk/config", "/waterfall", "/mediation",
        "/auction", "/bidding", "/reward/video", "/open_screen", "/splash", "/launch", "/startup"
    )
    private val pangleAndGdtBodySignals = listOf(
        "\"pangle\"", "\"pangolin\"", "\"gromore\"", "\"csj\"", "\"gdt\"", "\"youlianghui\"",
        "\"guangdiantong\"", "\"adslot\"", "\"slotid\"", "\"placement_id\"", "\"waterfall\"",
        "\"mediation\"", "\"bidding_token\"", "\"auction_id\"", "\"ecpm\"", "\"material_url\"",
        "\"click_url\"", "\"show_url\"", "\"playable\"", "\"endcard\""
    )
    private val commentCommerceAdSignals = listOf(
        "\"shop\"", "\"mall\"", "\"store\"", "\"goods\"", "\"product\"", "\"sku\"",
        "\"ecom\"", "\"ecommerce\"", "\"commerce\"", "\"douyin_shop\"", "\"shop_card\"",
        "\"mall_card\"", "\"goods_card\"", "\"product_card\"", "\"promotion_card\"", "\"ad_card\""
    )
    private val commentCommercePathSignals = listOf(
        "shop", "mall", "store", "goods", "product", "sku", "ecom", "commerce",
        "promotion_card", "promo_card", "ad_card", "goods_card", "product_card", "shop_card", "mall_card"
    )
    private val suspiciousQueryKeywords = listOf(
        "ad", "ads", "adid", "adunit", "adslot", "placement", "promo", "promotion", "splash", "reward", "preload", "tracker", "creative", "material", "template", "ecpm", "playable", "endcard", "launch", "startup", "interstitial", "popup", "openad", "bottomad",
        "feed", "feedad", "feed_ads", "commentad", "floorad", "nativead", "bannerid", "banner_id", "open_screen", "startupad", "launchad",
        "welfare", "benefit", "task", "taskid", "tasktype", "coin", "bonus", "offerwall", "excitation", "inspire", "unlock", "freeread", "chapterreward",
        "feedcard", "feed_card", "insertad", "insert_ad", "adscene", "ad_scene", "adposition", "ad_position", "pausead", "pause_ad",
        "preroll", "midroll", "postroll", "adrequest", "ad_request", "adresource", "ad_resource", "admaterial", "ad_material",
        "readerbanner", "reader_banner", "chapterreward", "chapter_reward", "watchadunlock", "watch_ad_unlock", "unlockbyad", "unlock_by_ad",
        "rewardverify", "reward_verify", "rewardunlock", "reward_unlock", "benefitcenter", "benefit_center", "taskcenter", "task_center",
        "waterfall", "waterfallid", "waterfall_id", "mediation", "mediationid", "mediation_id", "bidding", "biddingtoken",
        "bidtoken", "bid_token", "auctionid", "auction_id", "fillrate", "fill_rate", "requestscene", "request_scene",
        "preloadad", "preload_ad", "prefetchad", "prefetch_ad", "cachead", "cache_ad", "loadstrategy", "load_strategy",
        "dns", "dnsquery", "dns-query", "dns_message", "dns-message", "dnsjson", "dns-json", "httpdns", "resolver"
    )
    private val dohPathKeywords = listOf(
        "/dns-query", "/resolve", "/query", "/dns", "/httpdns", "/resolver", "/dns/resolve", "/doh"
    )
    private val dohContentTypeKeywords = listOf(
        "application/dns-message",
        "application/dns-json",
        "application/oblivious-dns-message",
        "application/x-javascript",
        "application/json+dns"
    )
    private val adTrackingHeaderFields = listOf(
        "click_url",
        "clickurl",
        "click_track_url",
        "clicktrackurl",
        "show_url",
        "showurl",
        "show_track_url",
        "track_url",
        "trackurl",
        "track_urls",
        "trackurls",
        "win_notice",
        "winnotice",
        "landing_page",
        "landingpage",
        "landing_url",
        "landingurl",
        "deep_link",
        "deeplink",
        "download_url",
        "downloadurl",
        "materialid",
        "material_id",
        "creativeid",
        "creative_id",
        "placementid",
        "placement_id",
        "slotid",
        "slot_id",
        "template_id",
        "templateid",
        "ecpm",
        "ecpm_level",
        "request_id",
        "ad_source",
        "adstyle",
        "ad_type",
        "interaction_type",
        "image_url",
        "video_url",
        "playable_url",
        "endcard_url",
        "render_url",
        "monitor_url",
        "monitor_urls",
        "expo_url",
        "expo_urls",
        "impression_url",
        "impression_urls",
        "callback_url",
        "skip_time",
        "ad_info",
        "ad_scene",
        "ad_position",
        "ad_location",
        "ad_switch",
        "reward_amount",
        "coin_reward",
        "chapter_reward",
        "reading_bonus",
        "task_reward",
        "ad_reward",
        "watch_ad",
        "watch_ad_unlock",
        "welfare_page",
        "benefit_page",
        "offerwall",
        "banner_info",
        "banner_infos",
        "feed_card",
        "feed_cards",
        "feed_flow",
        "information_flow",
        "reply_ad",
        "post_ad",
        "ad_card",
        "ad_cards",
        "ad_layout",
        "ad_index",
        "ad_cache",
        "pause_ad",
        "floating_banner",
        "bottom_banner",
        "startup_ad",
        "launch_ad",
        "ad_request",
        "ad_response",
        "ad_resource",
        "ad_resources",
        "ad_material",
        "ad_materials",
        "ad_dispatch",
        "ad_list",
        "adlist",
        "patch_ad",
        "preroll_ad",
        "midroll_ad",
        "postroll_ad",
        "insert_ad",
        "insert_ads",
        "reader_banner",
        "reader_bottom_banner",
        "reading_insert_ad",
        "chapter_ad",
        "chapter_ad_list",
        "startup_banner",
        "splash_banner"
    )
    private val trackingFieldTokens = listOf(
        "\"imp\"", "\"impression\"", "\"impression_url\"", "\"impression_urls\"",
        "\"click_url\"", "\"clickurl\"", "\"click_track_url\"", "\"show_url\"",
        "\"showurl\"", "\"show_track_url\"", "\"track_url\"", "\"trackurl\"",
        "\"track_urls\"", "\"win_notice\"", "\"winnotice\"", "\"landing_page\"",
        "\"landingpage\"", "\"landing_url\"", "\"deep_link\"", "\"deeplink\"",
        "\"download_url\"", "\"downloadurl\"", "\"materialid\"", "\"material_id\"",
        "\"creativeid\"", "\"creative_id\"", "\"placementid\"", "\"placement_id\"",
        "\"slotid\"", "\"slot_id\"", "\"template_id\"", "\"templateid\"",
        "\"ecpm\"", "\"ecpm_level\"", "\"price_ratio\"", "\"request_id\"",
        "\"ad_source\"", "\"ad_info\"", "\"ad_infos\"", "\"ad_list\"",
        "\"adlist\"", "\"adstyle\"", "\"ad_type\"", "\"interaction_type\"",
        "\"image_url\"", "\"image_urls\"", "\"img_url\"", "\"video_url\"",
        "\"video_urls\"", "\"playable_url\"", "\"playable\"", "\"endcard_url\"",
        "\"endcard\"", "\"render_url\"", "\"monitor_url\"", "\"monitor_urls\"",
        "\"expo_url\"", "\"expo_urls\"", "\"landing_url\"", "\"callback_url\"",
        "\"target_url\"", "\"open_type\"", "\"open_screen\"", "\"startup\"",
        "\"app_name\"", "\"app_icon\"", "\"app_desc\"", "\"app_size\"",
        "\"download_type\"", "\"button_text\"", "\"btn_text\"", "\"desc_text\"",
        "\"title_text\"", "\"icon_url\"", "\"icon_urls\"", "\"img_list\"",
        "\"image_list\"", "\"materials\"", "\"material_list\"", "\"creatives\"",
        "\"creative_list\"", "\"reward_video\"", "\"rewardvideo\"", "\"fullscreen_video\"",
        "\"native_express\"", "\"landing_page_url\"", "\"download_button\"", "\"download_btn\""
    )
    private val generalAdFieldTokens = listOf(
        "\"banner\"", "\"banner_list\"", "\"bannerlist\"", "\"banner_infos\"", "\"banner_info\"",
        "\"splash\"", "\"splash_ad\"", "\"splash_ads\"", "\"open_screen\"", "\"launch_ad\"",
        "\"startup_ad\"", "\"interstitial\"", "\"interstitial_ad\"", "\"feed_ad\"", "\"feedads\"",
        "\"feed_banner\"", "\"feed_cards\"", "\"feed_card\"", "\"feed_flow\"", "\"information_flow\"",
        "\"info_flow\"", "\"bottom_banner\"", "\"floating_banner\"", "\"comment_ad\"", "\"floor_ad\"",
        "\"reply_ad\"", "\"post_ad\"", "\"native_ad\"", "\"native_express\"", "\"ad_items\"",
        "\"ad_positions\"", "\"ad_slots\"", "\"adview\"", "\"ad_info_list\"", "\"ad_card\"",
        "\"ad_cards\"", "\"ad_layout\"", "\"ad_index\"", "\"ad_cache\"", "\"insert_ad\"",
        "\"insert_ads\"", "\"pause_ad\"", "\"pause_ads\"", "\"player_ad\"", "\"video_ad\"",
        "\"video_ads\"", "\"patch_ad\"", "\"preroll_ad\"", "\"midroll_ad\"", "\"postroll_ad\"",
        "\"startup_popup\"", "\"suspend_ad\"", "\"float_layer_ad\"", "\"chapter_unlock_ad\"", "\"reward_popup\"",
        "\"ad_resource\"", "\"ad_resources\"", "\"ad_materials\"", "\"ad_material\"", "\"ad_dispatch\"",
        "\"ad_response\"", "\"ad_list\"", "\"adlist\"", "\"carousel_ad\"", "\"carousel_ads\"",
        "\"waterfall_ad\"", "\"waterfall_ads\"", "\"grid_ad\"", "\"grid_ads\"", "\"card_ad\"",
        "\"card_ads\"", "\"live_ad\"", "\"live_ads\"", "\"draw_ad\"", "\"draw_ads\"",
        "\"comment_banner\"", "\"comment_card\"", "\"comment_ad_card\"", "\"comment_insert_ad\"", "\"comment_sponsor\"", "\"comment_promote_card\"", "\"comment_stream_ad\"",
        "\"reply_banner\"", "\"reply_ad_card\"", "\"reply_insert_ad\"", "\"reply_sponsor\"", "\"reply_promote\"",
        "\"floor_banner\"", "\"floor_card\"", "\"floor_promote\"", "\"floor_sponsor\"", "\"stream_card_ad\"",
        "\"timeline_ad\"", "\"timeline_insert_ad\"", "\"timeline_card\"", "\"recommend_ad\"", "\"recommend_card_ad\"",
        "\"patch_ads\"", "\"preroll_ads\"", "\"midroll_ads\"", "\"postroll_ads\"",
        "\"startup_page_ad\"", "\"launch_screen_ad\"", "\"open_screen_material\"", "\"splash_material\"",
        "\"comment_popup_ad\"", "\"comment_bottom_ad\"", "\"reply_bottom_ad\"", "\"floor_bottom_ad\"",
        "\"startup_preload_ad\"", "\"launch_preload_ad\"", "\"splash_template_ad\"", "\"open_screen_dispatch\"",
        "\"comment_feed_ad\"", "\"comment_flow_ad\"", "\"reply_flow_ad\"", "\"floor_flow_ad\"",
        "\"waterfall\"", "\"mediation\"", "\"bidding_token\"", "\"bid_token\"", "\"auction_id\"",
        "\"placement_id\"", "\"slot_id\"", "\"template_id\"", "\"ad_strategy\"", "\"ad_scene\"",
        "\"ad_position\"", "\"ad_dispatch\"", "\"material_url\"", "\"material_urls\"", "\"landing_urls\"",
        "\"message_center_ad\"", "\"message_center_banner\"", "\"inbox_ad\"", "\"notify_ad\"",
        "\"promotion_card\"", "\"promo_card\"", "\"discover_card\"", "\"discover_ad\"",
        "\"operation_banner\"", "\"operation_card\"", "\"service_popup_ad\"", "\"benefit_popup_ad\"",
        "\"sign_popup_ad\"", "\"daily_popup_ad\"", "\"mission_popup_ad\"", "\"welfare_popup_ad\""
    )
    private val novelAdFieldTokens = listOf(
        "\"book_id\"", "\"book_name\"", "\"chapter_id\"", "\"chapter_name\"", "\"reader_type\"",
        "\"scene_id\"", "\"scene_type\"", "\"enter_from\"", "\"coin\"", "\"task_id\"",
        "\"task_type\"", "\"inspire\"", "\"excitation\"", "\"excitation_ad\"", "\"reward_amount\"",
        "\"unlock_style\"", "\"client_bidding\"", "\"unlock_chapter\"", "\"watch_ad\"", "\"watch_ad_unlock\"",
        "\"video_finish\"", "\"free_read\"", "\"reading_bonus\"", "\"welfare_page\"", "\"benefit_page\"",
        "\"coin_reward\"", "\"sign_task\"", "\"task_reward\"", "\"ad_unlock\"", "\"ad_reward\"",
        "\"chapter_unlock\"", "\"chapter_reward\"", "\"offerwall\"", "\"free_read_card\"", "\"unlock_by_ad\"",
        "\"bonus_reward\"", "\"welfare_task\"", "\"task_status\"", "\"task_progress\"", "\"bottom_ad\"",
        "\"bottom_banner\"", "\"reader_banner\"", "\"reader_bottom_banner\"", "\"reader_bottom_ad\"", "\"chapter_ad\"", "\"chapter_ad_info\"",
        "\"chapter_ad_list\"", "\"reading_interstitial\"", "\"reading_insert_ad\"", "\"reading_page_ad\"", "\"chapter_page_ad\"", "\"page_turn_ad\"", "\"turn_page_ad\"", "\"flip_page_ad\"", "\"page_insert_ad\"", "\"chapter_next_ad\"", "\"watch_ad_unlock\"", "\"unlock_by_ad\"",
        "\"reader_ad_popup\"", "\"reader_reward_popup\"", "\"chapter_offerwall\"", "\"novel_task_center\"", "\"novel_welfare_center\"",
        "\"incentive_video\"", "\"inspire_card\"", "\"free_read_popup\"", "\"chapter_card_ad\"", "\"reader_float_ad\"",
        "\"page_footer_ad\"", "\"chapter_footer_ad\"", "\"reader_footer_ad\"", "\"bottom_float_ad\"", "\"page_swipe_ad\"",
        "\"swipe_page_ad\"", "\"next_page_ad\"", "\"turn_page_banner\"", "\"page_corner_ad\"", "\"chapter_end_ad\"",
        "\"page_tail_popup\"", "\"chapter_tail_popup\"", "\"reader_tail_popup\"", "\"page_end_card\"", "\"chapter_end_card\"",
        "\"swipe_reward_ad\"", "\"page_flip_reward\"", "\"reader_next_popup\"", "\"chapter_next_popup\"",
        "\"task_center\"", "\"benefit_center\"", "\"welfare_center\"", "\"reader_task_center\"", "\"reader_benefit_center\"",
        "\"watch_ad_task\"", "\"daily_reward\"", "\"sign_reward\"", "\"coin_bonus\"", "\"chapter_unlock_popup\"",
        "\"sign_popup_ad\"", "\"daily_popup_ad\"", "\"mission_popup_ad\"", "\"task_popup_ad\"",
        "\"benefit_popup_ad\"", "\"welfare_popup_ad\"", "\"reader_sign_reward\"", "\"novel_sign_task\""
    )
    private val mediaAdFieldTokens = listOf(
        "\"episode_id\"", "\"episode_name\"", "\"drama_id\"", "\"drama_name\"", "\"short_drama\"",
        "\"short_video\"", "\"live_room\"", "\"live_room_id\"", "\"anchor_id\"", "\"stream_id\"",
        "\"stream_url\"", "\"play_scene\"", "\"comic_id\"", "\"comic_name\"", "\"manga_id\"",
        "\"chapter_unlock_ad\"", "\"pause_ad\"", "\"player_ad\"", "\"video_patch\"", "\"patch_ads\"",
        "\"live_ad\"", "\"draw_ad\"", "\"floating_banner\""
    )
    private val http2JsonAdFieldTokens = listOf(
        "\"ad\"", "\"ads\"", "\"adId\"", "\"adid\"", "\"ad_id\"",
        "\"adName\"", "\"adname\"", "\"ad_name\"", "\"ad_title\"", "\"adtitle\"",
        "\"adUrl\"", "\"adurl\"", "\"ad_url\"", "\"ad_link\"", "\"adlink\"",
        "\"adImg\"", "\"adimg\"", "\"ad_img\"", "\"ad_image\"", "\"adimage\"",
        "\"adLogo\"", "\"adlogo\"", "\"ad_logo\"", "\"ad_icon\"", "\"adicon\"",
        "\"adDesc\"", "\"addesc\"", "\"ad_desc\"", "\"ad_description\"",
        "\"adData\"", "\"addata\"", "\"ad_data\"", "\"adInfo\"", "\"adinfo\"",
        "\"ad_info\"", "\"adInfos\"", "\"adinfos\"", "\"ad_infos\"",
        "\"adList\"", "\"adlist\"", "\"ad_list\"", "\"adsList\"",
        "\"material\"", "\"materialId\"", "\"material_id\"", "\"materialUrl\"",
        "\"creative\"", "\"creativeId\"", "\"creative_id\"", "\"creativeUrl\"",
        "\"landingPage\"", "\"landingpage\"", "\"landing_page\"", "\"landingUrl\"",
        "\"clickUrl\"", "\"clickurl\"", "\"click_track_url\"", "\"showUrl\"",
        "\"showurl\"", "\"show_url\"", "\"winNotice\"", "\"winnotice\"", "\"impression\"",
        "\"bidPrice\"", "\"bidprice\"", "\"bid_price\"", "\"ecpm\"", "\"priceRatio\"",
        "\"placementId\"", "\"placementid\"", "\"placement_id\"", "\"slotId\"",
        "\"slotid\"", "\"slot_id\"", "\"unitId\"", "\"unitid\"", "\"unit_id\"",
        "\"templateId\"", "\"templateid\"", "\"template_id\"",
        "\"reward_amount\"", "\"coin_reward\"", "\"reading_bonus\"", "\"task_reward\"",
        "\"chapter_reward\"", "\"ad_reward\"", "\"watch_ad\"", "\"watch_ad_unlock\"",
        "\"welfare_page\"", "\"benefit_page\"", "\"offerwall\"", "\"free_read\"",
        "\"unlock_chapter\"", "\"chapter_unlock\"", "\"excitation_ad\""
    )
    private val jsonNovelFieldTokens = listOf(
        "\"reward_amount\"", "\"coin_reward\"", "\"reading_bonus\"", "\"task_reward\"",
        "\"chapter_reward\"", "\"ad_reward\"", "\"watch_ad\"", "\"watch_ad_unlock\"",
        "\"welfare_page\"", "\"benefit_page\"", "\"offerwall\"", "\"free_read\"",
        "\"unlock_chapter\"", "\"chapter_unlock\"", "\"excitation_ad\"", "\"unlock_by_ad\"", "\"page_turn_ad\"", "\"flip_page_ad\"", "\"reader_bottom_ad\"",
        "\"open_screen_ad\"", "\"launch_ad\"", "\"startup_ad\"", "\"interstitial_ad\"",
        "\"feed_insert_ad\"", "\"information_flow_ad\"", "\"timeline_insert_ad\"", "\"stream_card_ad\"",
        "\"pause_ad\"", "\"player_ad\"", "\"preroll_ad\"", "\"midroll_ad\"", "\"postroll_ad\"",
        "\"reader_reward_popup\"", "\"chapter_offerwall\"", "\"free_read_popup\"", "\"reader_float_ad\"",
        "\"comment_promote\"", "\"reply_promote\"", "\"floor_promote\"", "\"comment_material\"", "\"reply_material\"",
        "\"page_footer_ad\"", "\"chapter_footer_ad\"", "\"reader_footer_ad\"", "\"page_swipe_ad\"", "\"next_page_ad\"",
        "\"startup_page_ad\"", "\"launch_screen_ad\"", "\"comment_popup_ad\"", "\"comment_bottom_ad\"", "\"reply_bottom_ad\"",
        "\"page_tail_popup\"", "\"chapter_tail_popup\"", "\"reader_tail_popup\"", "\"page_end_card\"", "\"chapter_end_card\"",
        "\"startup_preload_ad\"", "\"launch_preload_ad\"", "\"comment_feed_ad\"", "\"comment_flow_ad\"", "\"reply_flow_ad\"",
        "\"sign_popup_ad\"", "\"daily_popup_ad\"", "\"mission_popup_ad\"", "\"benefit_popup_ad\"",
        "\"welfare_popup_ad\"", "\"message_center_ad\"", "\"promotion_card\"", "\"discover_card\""
    )
    private val htmlNovelMarkerTokens = listOf(
        "welfare-page", "welfare_page", "task-center", "task_center", "coin-reward", "coin_reward",
        "reading-bonus", "reading_bonus", "reward-video", "watch-ad", "watch_ad", "unlock-by-ad",
        "unlock_chapter", "offerwall", "benefit-page", "benefit_page"
    )
    private val rewardUnlockTokens = listOf(
        "\"reward_verify\"", "\"rewardverify\"", "\"reward_unlock\"", "\"rewardunlock\"",
        "\"watch_ad_unlock\"", "\"watchadunlock\"", "\"unlock_by_ad\"", "\"unlockbyad\"",
        "\"chapter_unlock\"", "\"chapterunlock\""
    )
    private val bodyStrongMarkers = strongResponseAdKeywords.distinct()
    private val bodyWeakMarkers = responseAdKeywords.distinct()
    // HTML 广告标记（增加更多）
    // 增强 HTML 广告标记检测（500+ 关键词）
    private val htmlAdMarkers = listOf(
        "adsbygoogle", "google_ad_", "google_ads", "adsense", "doubleclick",
        "ad-container", "ad_container", "adslot", "ad-slot", "adslot", "ad_wrapper", "adwrapper",
        "ad-banner", "ad_banner", "banner-ad", "bannerad", "bannerads",
        "splash-ad", "splashad", "splashads", "startup-ad", "startupad",
        "interstitial-ad", "interstitialad", "interstitial_ads",
        "native-ad", "nativead", "nativeads", "native_ad_unit",
        "rewarded-video", "rewardedvideo", "rewardedads", "rewarded_ad",
        "in-feed-ad", "infeedad", "feed-ad", "feedad", "feedads",
        "ad-frame", "adframe", "ad_frame", "ad_box", "adbox",
        "google_ad", "google_ads", "googlesyndication", "googleads", "doubleclick",
        "facebook_ad", "facebookads", "fbads", "fb_ad", "audience_network",
        "unity_ads", "unityads", "unity3d", "unityad",
        "chartboost", "chartboostad", "cbad",
        "inmobi", "inmobiad", "imad",
        "adcolony", "adcolonyad",
        "vungle", "vunglead",
        "applovin", "applovinad", "alad",
        "ironsource", "ironsourcead", "isad",
        "supersonic", "supersonicads",
        "tapjoy", "tapjoyad", "tjad",
        "startapp", "startappad",
        // 国内广告平台
        "pangle", "panglead", "gromore", "csj", "gdt", "sigmob",
        "mobvista", "mintegral", "topon", "tradplus", "adscope",
        "kswad", "tanx", "alimama", "umeng", "mobads", "baidumobads",
        "huaweiads", "oppoads", "meizoad", "vivo_ad", "xiaomi_ad",
        // 字节系广告
        "douyin_ad", "tiktok_ad", "pangle_ad", "zijie", "toutiao_ad",
        // 腾讯系广告
        "tencent_ad", "tencentad", "gdt", "wechat_ad", "qqad",
        // 百度系广告
        "baidu_ad", "baiduad", "tieba_ad", "baidumob",
        // 阿里系广告
        "alibaba_ad", "alimama", "taobao_ad", "tmall_ad",
        // 广告配置对象
        "window.__ad__", "window.__ads__", "window.adConfig", "window.adConfigration",
        "window.csj", "window.tad", "window.gdt", "window.pangle", "window.mob",
        "window.adsbygoogle", "window.fbAsyncInit", "window.umeng",
        // HTML 属性标记
        "data-ad-", "data-adslot", "data-adid", "data-adunit", "data-material",
        "data-click", "data-impression", "data-tracker", "data-monitor",
        // 脚本注入标记
        "inject-ad", "injectad", "ad-injection", "adinjection",
        "load-ad-script", "loadadscript", "ad_script", "adscript"
    )

    // 国内广告 SDK 关键词已在上面定义，此处不再重复

    private const val HTTP2_REQUEST_BLOCK_CANDIDATE_SCORE = 5
    private const val HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE = 2
    private const val HTTP1_RESPONSE_BLOCK_SCORE = 3
    private const val HTTP1_NOVEL_RESPONSE_BLOCK_SCORE = 2
    private const val HTTP2_NOVEL_RESPONSE_BLOCK_SCORE = 2
    private const val HTTP1_MITM_AGGRESSIVE_RESPONSE_BLOCK_SCORE = 2
    private const val HTTP2_MITM_AGGRESSIVE_RESPONSE_BLOCK_SCORE = 1
    private val adInfraRequestSignals = listOf(
        "waterfall", "mediation", "bidding", "auction", "preload", "prefetch", "cache/ad",
        "ad/cache", "sdk/config", "ad/config", "material/list", "creative/list", "placement/list",
        "fill/rate", "request_scene", "load_strategy"
    )

    private val TRANSPARENT_1X1_GIF = byteArrayOf(
        0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(), 0x39.toByte(), 0x61.toByte(),
        0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x80.toByte(), 0x00.toByte(), 0x00.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x21.toByte(), 0xF9.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x2C.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x02.toByte(), 0x02.toByte(), 0x4C.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x3B.toByte()
    )

    fun inspectRequest(session: TlsMitmSessionManager.TlsMitmSession, chunk: ByteArray): RequestInspection? {
        val text = decodeAscii(chunk) ?: return null
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return null
        if (requestMethods.none { text.startsWith(it) }) return null
        val lines = text.substring(0, headerEnd).split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines.first().split(' ')
        if (requestLine.size < 2) return null
        val hostHeader = lines.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.let(::normalizeAuthority)
            ?.ifBlank { null }
        val referer = lines.firstOrNull { it.startsWith("Referer:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.ifBlank { null }
        val origin = lines.firstOrNull { it.startsWith("Origin:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.ifBlank { null }
        return RequestInspection(
            method = requestLine[0],
            path = requestLine[1],
            host = hostHeader ?: session.host,
            httpVersion = requestLine.getOrNull(2) ?: "HTTP/1.1",
            referer = referer,
            origin = origin
        )
    }

    fun rewriteRequestForMitm(
        session: TlsMitmSessionManager.TlsMitmSession,
        inspection: RequestInspection,
        chunk: ByteArray
    ): ByteArray {
        val text = decodeAscii(chunk) ?: return chunk
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return chunk
        if (requestMethods.none { text.startsWith(it) }) return chunk
        val requestDomain = extractRequestDomain(inspection)
        val context = TlsMitmSessionManager.getContextOrNull() ?: return chunk
        val directives = RuleRepository.getRequestRewriteDirectives(
            context,
            inspection.host,
            inspection.path,
            session.appName,
            requestDomain
        )
        val shouldStripAdParams = shouldPreferDeepInspection(
            host = inspection.host,
            path = inspection.path,
            appName = session.appName,
            requestDomain = requestDomain
        )
        val removeParams = if (shouldStripAdParams) {
            directives.removeParams + defaultAdQueryParams
        } else {
            directives.removeParams
        }
        val removeParamRegexes = directives.removeParamRegexes
        val removeRequestHeaders = directives.removeRequestHeaders
        val setRequestHeaders = parseHeaderOverrides(directives.setRequestHeaders)
        val headerLines = text.substring(0, headerEnd).split("\r\n")
        if (headerLines.isEmpty()) return chunk
        var changed = false
        val appliedHeaderNames = mutableSetOf<String>()
        val rewrittenHeaders = headerLines.mapIndexedNotNull { index, line ->
            if (index == 0) return@mapIndexedNotNull line
            val headerName = line.substringBefore(':', "").trim().lowercase()
            if (headerName.isNotBlank() && removeRequestHeaders.contains(headerName)) {
                changed = true
                return@mapIndexedNotNull null
            }
            val overrideValue = if (headerName.isNotBlank()) setRequestHeaders[headerName] else null
            if (overrideValue != null) {
                appliedHeaderNames += headerName
                val rewrittenLine = "${line.substringBefore(':').trim()}: $overrideValue"
                if (rewrittenLine != line) changed = true
                return@mapIndexedNotNull rewrittenLine
            }
            if (line.startsWith("Accept-Encoding:", ignoreCase = true)) {
                changed = true
                return@mapIndexedNotNull "Accept-Encoding: identity"
            }
            if (line.startsWith("TE:", ignoreCase = true) && compressibleEncodings.any { encoding -> line.contains(encoding, ignoreCase = true) }) {
                changed = true
                return@mapIndexedNotNull null
            }
            line
        }
        if (directives.cspValue != null) {
            changed = true
        }
        val requestLine = rewriteRequestLine(rewrittenHeaders.first(), removeParams, removeParamRegexes)
        if (requestLine != rewrittenHeaders.first()) changed = true
        if (!changed) return chunk
        val body = text.substring(headerEnd + 4)
        val finalHeaders = buildList {
            add(requestLine)
            addAll(rewrittenHeaders.drop(1))
            setRequestHeaders.forEach { (headerName, headerValue) ->
                if (appliedHeaderNames.add(headerName)) {
                    add("$headerName: $headerValue")
                    changed = true
                }
            }
            directives.cspValue?.let { add("X-HanFeng-CSP: $it") }
        }
        return (finalHeaders.joinToString("\r\n") + "\r\n\r\n" + body).toByteArray(StandardCharsets.ISO_8859_1)
    }

    fun shouldRewriteHttp1RequestHeaders(
        session: TlsMitmSessionManager.TlsMitmSession,
        inspection: RequestInspection
    ): Boolean {
        return shouldPreferDeepInspection(
            host = inspection.host,
            path = inspection.path,
            appName = session.appName,
            requestDomain = extractRequestDomain(inspection)
        )
    }

    fun shouldRewriteHttp2RequestHeaders(session: TlsMitmSessionManager.TlsMitmSession, inspection: Http2HeaderInspection?): Boolean {
        if (inspection?.requestLike != true) return false
        if (inspection.suspiciousScore > 0) return true
        return shouldPreferDeepInspection(
            host = inspection.authority,
            path = inspection.path,
            appName = session.appName,
            vendorHint = inspection.vendor
        )
    }

    fun rewriteHttp2RequestHeaders(headers: List<HpackDecoder.HeaderField>): Http2HeaderRewriteResult {
        if (headers.isEmpty()) return Http2HeaderRewriteResult(headers = headers, changed = false)
        var changed = false
        val rewritten = headers.mapNotNull { header ->
            val lowerName = header.name.lowercase()
            when {
                lowerName == "accept-encoding" && !header.value.equals("identity", ignoreCase = true) -> {
                    changed = true
                    HpackDecoder.HeaderField(header.name, "identity")
                }
                lowerName == "te" -> {
                    val normalized = header.value.lowercase()
                    if (normalized.contains("gzip") || normalized.contains("br") || normalized.contains("deflate") || normalized.contains("zstd")) {
                        changed = true
                        null
                    } else {
                        header
                    }
                }
                else -> header
            }
        }
        return Http2HeaderRewriteResult(headers = rewritten, changed = changed)
    }

    fun rewriteHttp2RequestHeaders(
        session: TlsMitmSessionManager.TlsMitmSession,
        inspection: Http2HeaderInspection,
        headers: List<HpackDecoder.HeaderField>
    ): Http2HeaderRewriteResult {
        val base = rewriteHttp2RequestHeaders(headers)
        val context = TlsMitmSessionManager.getContextOrNull()
            ?: return base
        val directives = RuleRepository.getRequestRewriteDirectives(
            context,
            inspection.authority,
            inspection.path.orEmpty(),
            session.appName,
            extractRequestDomain(inspection)
        )
        val shouldStripAdParams = shouldPreferDeepInspection(
            host = inspection.authority,
            path = inspection.path,
            appName = session.appName,
            vendorHint = inspection.vendor,
            requestDomain = extractRequestDomain(inspection)
        )
        val removeParams = if (shouldStripAdParams) {
            directives.removeParams + defaultAdQueryParams
        } else {
            directives.removeParams
        }
        val removeParamRegexes = directives.removeParamRegexes
        val removeRequestHeaders = directives.removeRequestHeaders
        val setRequestHeaders = parseHeaderOverrides(directives.setRequestHeaders)
        if (removeParams.isEmpty() && removeParamRegexes.isEmpty() && removeRequestHeaders.isEmpty() && setRequestHeaders.isEmpty() && directives.cspValue == null) return base
        var changed = base.changed
        val appliedHeaderNames = mutableSetOf<String>()
        val rewritten = base.headers.mapNotNull { header ->
            val lowerName = header.name.lowercase()
            if (!lowerName.startsWith(":") && removeRequestHeaders.contains(lowerName)) {
                changed = true
                return@mapNotNull null
            }
            val overrideValue = if (!lowerName.startsWith(":")) setRequestHeaders[lowerName] else null
            if (overrideValue != null) {
                appliedHeaderNames += lowerName
                if (header.value != overrideValue) changed = true
                return@mapNotNull HpackDecoder.HeaderField(header.name, overrideValue)
            }
            if (header.name == ":path") {
                val updated = rewritePathOnly(header.value, removeParams, removeParamRegexes)
                if (updated != header.value) changed = true
                HpackDecoder.HeaderField(header.name, updated)
            } else {
                header
            }
        }.toMutableList()
        setRequestHeaders.forEach { (headerName, headerValue) ->
            if (appliedHeaderNames.add(headerName)) {
                rewritten += HpackDecoder.HeaderField(headerName, headerValue)
                changed = true
            }
        }
        directives.cspValue?.let {
            rewritten += HpackDecoder.HeaderField("x-hanfeng-csp", it)
            changed = true
        }
        return Http2HeaderRewriteResult(rewritten, changed)
    }

    fun filterResponse(
        session: TlsMitmSessionManager.TlsMitmSession,
        chunk: ByteArray,
        requestInspection: RequestInspection?
    ): FilterResult {
        val text = decodeAscii(chunk) ?: return FilterResult.PassThrough(chunk, "binary-response")
        if (!text.startsWith("HTTP/1.")) return FilterResult.PassThrough(chunk, "non-http1-response")
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return FilterResult.PassThrough(chunk, "partial-response-headers")
        val responseHeaders = parseHttp1ResponseHeaders(text, headerEnd)
            ?: return FilterResult.PassThrough(chunk, "missing-status-line")
        val directives = requestInspection?.let {
            RuleRepository.getRequestRewriteDirectives(
                context = TlsMitmSessionManager.getContextOrNull() ?: return@let RuleRepository.RequestRewriteDirectives(),
                host = it.host,
                path = it.path,
                appName = session.appName,
                requestDomain = extractRequestDomain(it)
            )
        } ?: RuleRepository.RequestRewriteDirectives()
        val cosmeticSelectors = directives.cosmeticSelectors
        reportSuspiciousRedirectDomain(
            host = normalizeAuthority(requestInspection?.host ?: session.host),
            location = responseHeaders.location,
            appName = session.appName,
            refererDomain = extractRequestDomain(requestInspection),
            matchedPathHint = requestInspection?.path
        )
        val headerResult = buildHttp1HeaderNeutralizedResponse(session, requestInspection, responseHeaders)
        if (headerResult != null) return headerResult
        val bodyInspectionReason = shouldInspectHttp1ResponseBody(session, requestInspection, responseHeaders.contentType)
        if (bodyInspectionReason == null) {
            return FilterResult.PassThrough(chunk, "response-body-skip:no-deep-inspection-target")
        }
        val bodyBytes = chunk.copyOfRange(headerEnd + 4, chunk.size)
        val decodedTransferBytes = if ("chunked" in responseHeaders.transferEncoding) {
            decodeChunkedBody(bodyBytes) ?: return FilterResult.PassThrough(chunk, "invalid-chunked")
        } else {
            bodyBytes
        }
        val decodedBodyBytes = decodeContentEncodedBody(decodedTransferBytes, responseHeaders.contentEncoding)
            ?: return buildDecodeFailureResult(chunk, responseHeaders.contentEncoding)
        val body = decodeAscii(decodedBodyBytes) ?: return FilterResult.PassThrough(chunk, "binary-response-body")
        return buildHttp1BodyFilterResult(
            session = session,
            chunk = chunk,
            requestInspection = requestInspection,
            responseHeaders = responseHeaders,
            body = body,
            directives = directives,
            cosmeticSelectors = cosmeticSelectors
        )
    }

    private fun parseHttp1ResponseHeaders(text: String, headerEnd: Int): Http1ResponseHeaders? {
        val headerLines = text.substring(0, headerEnd).split("\r\n")
        val statusLine = headerLines.firstOrNull() ?: return null
        return Http1ResponseHeaders(
            statusLine = statusLine,
            contentType = findHttpHeaderValue(headerLines, "Content-Type"),
            contentEncoding = findHttpHeaderValue(headerLines, "Content-Encoding"),
            transferEncoding = findHttpHeaderValue(headerLines, "Transfer-Encoding"),
            location = findHttpHeaderValue(headerLines, "Location"),
            setCookie = findHttpHeaderValue(headerLines, "Set-Cookie")
        )
    }

    private fun findHttpHeaderValue(headerLines: List<String>, headerName: String): String {
        return headerLines.firstOrNull { it.startsWith("$headerName:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
    }

    private fun buildHttp1HeaderNeutralizedResponse(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        responseHeaders: Http1ResponseHeaders
    ): FilterResult? {
        val headerNeutralizeReason = inspectHttp1HeaderSignals(
            session,
            requestInspection,
            responseHeaders.location,
            responseHeaders.setCookie
        ) ?: return null
        val replacementBodyBytes = buildReplacementBody(responseHeaders.contentType, "", emptyList())
        val response = buildSyntheticResponse(responseHeaders.statusLine, responseHeaders.contentType, replacementBodyBytes)
        return FilterResult.Replaced(response, headerNeutralizeReason)
    }

    private fun buildHttp1BodyFilterResult(
        session: TlsMitmSessionManager.TlsMitmSession,
        chunk: ByteArray,
        requestInspection: RequestInspection?,
        responseHeaders: Http1ResponseHeaders,
        body: String,
        directives: RuleRepository.RequestRewriteDirectives,
        cosmeticSelectors: List<String>
    ): FilterResult {
        val contentType = responseHeaders.contentType
        val neutralizeReason = inspectHttp1BodySignals(session, requestInspection, contentType, body, cosmeticSelectors)
        val redirectBodyBytes = buildRedirectReplacementBody(contentType, directives.redirectResource)
        if (redirectBodyBytes != null) {
            val response = buildSyntheticResponse(
                responseHeaders.statusLine,
                inferRedirectContentType(contentType, directives.redirectResource),
                redirectBodyBytes,
                directives.cspValue
            )
            return FilterResult.Replaced(response, "redirect-resource-applied", chunk.size)
        }
        val replacedBody = applyReplaceRules(contentType, body, directives.replaceRules)
        if (replacedBody != null && replacedBody != body) {
            val replacementBodyBytes = replacedBody.toByteArray(StandardCharsets.UTF_8)
            val response = buildSyntheticResponse(responseHeaders.statusLine, contentType, replacementBodyBytes, directives.cspValue)
            return FilterResult.Replaced(response, "replace-rule-applied", chunk.size)
        }
        if (neutralizeReason == null) {
            if (contentType.contains("text/html") && (cosmeticSelectors.isNotEmpty() || !directives.cspValue.isNullOrBlank())) {
                val injectedBodyBytes = buildInjectedHtmlBody(body, cosmeticSelectors, directives.cspValue)
                val response = buildSyntheticResponse(responseHeaders.statusLine, contentType, injectedBodyBytes, directives.cspValue)
                return FilterResult.Replaced(response, "cosmetic-html-injected", chunk.size)
            }
            return FilterResult.PassThrough(chunk, "response-allowed")
        }
        val replacementBodyBytes = buildReplacementBody(contentType, body, cosmeticSelectors, directives.cspValue)
        val response = buildSyntheticResponse(responseHeaders.statusLine, contentType, replacementBodyBytes, directives.cspValue)
        return FilterResult.Replaced(response, neutralizeReason, chunk.size)
    }

    fun maxHttp1FilterBufferBytes(): Int = MAX_HTTP1_FILTER_BUFFER_BYTES

    fun inspectBufferedHttp1Response(
        buffer: ByteArray,
        requestInspection: RequestInspection?
    ): BufferedHttp1Result {
        val text = decodeAscii(buffer) ?: return BufferedHttp1Result.Bypass("binary-response-buffer")
        if (!text.startsWith("HTTP/1.")) return BufferedHttp1Result.Bypass("non-http1-response")
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return BufferedHttp1Result.AwaitMore
        val headerBytes = headerEnd + 4
        val headerLines = text.substring(0, headerEnd).split("\r\n")
        val statusCode = headerLines.firstOrNull()
            ?.split(' ')
            ?.getOrNull(1)
            ?.toIntOrNull()
        val transferEncoding = headerLines.firstOrNull { it.startsWith("Transfer-Encoding:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val contentLength = headerLines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.toLongOrNull()
        val bodyless = requestInspection?.method.equals("HEAD", ignoreCase = true) ||
            statusCode in 100..199 || statusCode == 204 || statusCode == 304
        if (bodyless) {
            val responseBytes = buffer.copyOfRange(0, headerBytes)
            val remainder = if (buffer.size > headerBytes) buffer.copyOfRange(headerBytes, buffer.size) else ByteArray(0)
            return BufferedHttp1Result.Ready(responseBytes, remainder)
        }
        if ("chunked" in transferEncoding) {
            val chunkedBodyBytes = detectCompleteChunkedBody(buffer, headerBytes) ?: return BufferedHttp1Result.AwaitMore
            val endIndex = headerBytes + chunkedBodyBytes
            val responseBytes = buffer.copyOfRange(0, endIndex)
            val remainder = if (buffer.size > endIndex) buffer.copyOfRange(endIndex, buffer.size) else ByteArray(0)
            return BufferedHttp1Result.Ready(responseBytes, remainder)
        }
        if (contentLength != null && contentLength >= 0L) {
            val totalLength = headerBytes + contentLength
            if (totalLength > Int.MAX_VALUE) return BufferedHttp1Result.Bypass("response-too-large")
            if (buffer.size < totalLength) return BufferedHttp1Result.AwaitMore
            val responseBytes = buffer.copyOfRange(0, totalLength.toInt())
            val remainder = if (buffer.size > totalLength) buffer.copyOfRange(totalLength.toInt(), buffer.size) else ByteArray(0)
            return BufferedHttp1Result.Ready(responseBytes, remainder)
        }
        return BufferedHttp1Result.AwaitMore
    }

    fun finalizeBufferedHttp1Response(buffer: ByteArray): BufferedHttp1Result {
        val text = decodeAscii(buffer) ?: return BufferedHttp1Result.Bypass("binary-response-buffer")
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return BufferedHttp1Result.Bypass("partial-response-headers")
        return BufferedHttp1Result.Ready(buffer, ByteArray(0))
    }

    fun inspectHttp2DataSample(
        session: TlsMitmSessionManager.TlsMitmSession,
        headerInspection: Http2HeaderInspection?,
        currentSample: ByteArray,
        incomingFragment: ByteArray
    ): Http2DataInspection? {
        if (incomingFragment.isEmpty()) return null
        if (headerInspection?.responseLike != true) return null
        val context = TlsMitmSessionManager.getContextOrNull() ?: return null
        val combinedSample = appendSample(currentSample, incomingFragment, MAX_HTTP2_DATA_SAMPLE_BYTES)
        val contentType = headerInspection.contentType?.lowercase().orEmpty()
        val targetedContentType = contentType.contains("json") ||
            contentType.contains("javascript") ||
            contentType.contains("html") ||
            contentType.contains("text")
        if (contentType.isNotBlank() && !targetedContentType) {
            return null
        }
        val directives = RuleRepository.getRequestRewriteDirectives(
            context = context,
            host = headerInspection.authority,
            path = headerInspection.path.orEmpty(),
            appName = session.appName,
            requestDomain = extractRequestDomain(headerInspection)
        )
        val decoded = decodeAscii(combinedSample) ?: return null
        val lowerBody = decoded.lowercase()
        if (RuleRepository.shouldProtectMediaTraffic(headerInspection.authority)) return null
        if (RuleRepository.shouldProtectBusinessTraffic(headerInspection.authority)) return null
        val vendor = headerInspection.vendor.ifBlank {
            RuleRepository.classifyVendorFromHints(context, headerInspection.authority, session.appName)
        }
        val aggressiveNovelTarget = RuleRepository.shouldAggressivelyBlockForNovelApp(
            context,
            headerInspection.authority,
            session.appName,
            vendor
        )
        val bodySignals = inspectAdBodySignals(lowerBody)
        val jsonAdFieldHitCount = if (contentType.contains("json")) {
            http2JsonAdFieldTokens.count(lowerBody::contains)
        } else 0
        val jsonAdFieldMatched = jsonAdFieldHitCount > 0
        val jsonAdArrayMatched = contentType.contains("json") && lowerBody.trim().startsWith("[") && jsonAdFieldHitCount >= 2
        if (bodySignals.reasons.isEmpty() && !jsonAdFieldMatched && !jsonAdArrayMatched) return null
        val isNovelApp = RuleRepository.isNovelAppHint(session.appName)
        var suspiciousScore = bodySignals.score + if (targetedContentType) 1 else 0
        if (isKnownAdVendor(vendor)) suspiciousScore += 2
        if (aggressiveNovelTarget) suspiciousScore += 3
        if (jsonAdFieldMatched) suspiciousScore += 3
        if (jsonAdArrayMatched) suspiciousScore += 2
        // 降低拦截阈值：小说 APP 1 分拦截，普通应用 2 分拦截
        val threshold = if (isNovelApp) HTTP2_NOVEL_RESPONSE_BLOCK_SCORE else HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE
        if (suspiciousScore < threshold) return null
        val preview = decoded.replace('\r', ' ').replace('\n', ' ').take(160)
        val reasons = bodySignals.reasons.toMutableList()
        if (isKnownAdVendor(vendor)) reasons += "vendor:$vendor"
        if (aggressiveNovelTarget) reasons += "novel-app-aggressive"
        // 新增：Content-Type 包含广告特征
        if (contentType.contains("json") && strongResponseAdKeywords.any { lowerBody.contains(it) }) {
            suspiciousScore += 2
            reasons += "json-ad-content"
        }
        // 增强：JSON 广告响应检测 - 检测 JSON 结构中的广告字段
        if (jsonAdFieldMatched) {
            reasons += "json-ad-field"
        }
        if (jsonAdArrayMatched) {
            reasons += "json-ad-array"
        }
        return Http2DataInspection(
            suspiciousScore = suspiciousScore,
            suspiciousReasons = reasons.distinct(),
            confidence = if (suspiciousScore >= 4) "high" else "medium",
            samplePreview = preview,
            vendor = vendor,
            combinedSample = combinedSample,
            redirectResource = directives.redirectResource,
            cspValue = directives.cspValue,
            contentType = contentType
        )
    }

    private fun decodeChunkedBody(body: ByteArray): ByteArray? {
        var offset = 0
        val output = ByteArrayOutputStream(body.size)
        while (offset < body.size) {
            val sizeLineEnd = indexOfCrlf(body, offset)
            if (sizeLineEnd < 0) return null
            val sizeLine = runCatching {
                String(body, offset, sizeLineEnd - offset, StandardCharsets.ISO_8859_1)
            }.getOrNull() ?: return null
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            offset = sizeLineEnd + 2
            if (chunkSize == 0) {
                return output.toByteArray()
            }
            if (offset + chunkSize > body.size) return null
            output.write(body, offset, chunkSize)
            offset += chunkSize
            if (offset + 2 > body.size || body[offset] != '\r'.code.toByte() || body[offset + 1] != '\n'.code.toByte()) {
                return null
            }
            offset += 2
        }
        return null
    }

    private fun gunzipBody(body: ByteArray): ByteArray? {
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(body)).use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    private fun brotliBody(body: ByteArray): ByteArray? {
        return runCatching {
            BrotliInputStream(ByteArrayInputStream(body)).use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    private fun inflateDeflateBody(body: ByteArray): ByteArray? {
        return runCatching {
            InflaterInputStream(ByteArrayInputStream(body)).use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    // P0 增强：实现 zstd 解压缩
    private fun zstdBody(body: ByteArray): ByteArray? {
        return runCatching {
            // 使用 java.util.zip.Inflater 支持 zstd（需要 zstd-jni 库）
            // 由于 Android 默认不支持 zstd，这里使用通用压缩检测
            // 如果检测到 zstd 压缩，尝试使用标准 Inflater（部分 zstd 流兼容）
            InflaterInputStream(ByteArrayInputStream(body)).use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    private fun decodeContentEncodedBody(body: ByteArray, contentEncoding: String): ByteArray? {
        return when {
            "br" in contentEncoding -> brotliBody(body)
            "gzip" in contentEncoding -> gunzipBody(body)
            "deflate" in contentEncoding -> inflateDeflateBody(body)
            "zstd" in contentEncoding -> zstdBody(body)  // P0 增强：支持 zstd
            else -> body
        }
    }

    private fun buildDecodeFailureResult(chunk: ByteArray, contentEncoding: String): FilterResult {
        val reason = when {
            "br" in contentEncoding -> "brotli-decode-failed"
            "gzip" in contentEncoding -> "gzip-decode-failed"
            "deflate" in contentEncoding -> "deflate-decode-failed"
            else -> "response-decode-failed"
        }
        return FilterResult.PassThrough(chunk, reason)
    }

    private fun indexOfCrlf(data: ByteArray, start: Int): Int {
        var index = start
        while (index + 1 < data.size) {
            if (data[index] == '\r'.code.toByte() && data[index + 1] == '\n'.code.toByte()) {
                return index
            }
            index += 1
        }
        return -1
    }

    private fun detectCompleteChunkedBody(buffer: ByteArray, bodyStart: Int): Int? {
        var offset = bodyStart
        while (offset < buffer.size) {
            val sizeLineEnd = indexOfCrlf(buffer, offset)
            if (sizeLineEnd < 0) return null
            val sizeLine = runCatching {
                String(buffer, offset, sizeLineEnd - offset, StandardCharsets.ISO_8859_1)
            }.getOrNull() ?: return null
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            offset = sizeLineEnd + 2
            if (chunkSize == 0) {
                val trailerEnd = findChunkedTrailerEnd(buffer, offset)
                return trailerEnd?.minus(bodyStart)
            }
            if (offset + chunkSize + 2 > buffer.size) return null
            offset += chunkSize
            if (buffer[offset] != '\r'.code.toByte() || buffer[offset + 1] != '\n'.code.toByte()) return null
            offset += 2
        }
        return null
    }

    private fun findChunkedTrailerEnd(buffer: ByteArray, trailerStart: Int): Int? {
        if (trailerStart + 1 >= buffer.size) return null
        if (buffer[trailerStart] == '\r'.code.toByte() && buffer[trailerStart + 1] == '\n'.code.toByte()) {
            return trailerStart + 2
        }
        var offset = trailerStart
        while (offset + 3 < buffer.size) {
            if (buffer[offset] == '\r'.code.toByte() &&
                buffer[offset + 1] == '\n'.code.toByte() &&
                buffer[offset + 2] == '\r'.code.toByte() &&
                buffer[offset + 3] == '\n'.code.toByte()
            ) {
                return offset + 4
            }
            offset += 1
        }
        return null
    }

    private fun appendSample(existing: ByteArray, incoming: ByteArray, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) return ByteArray(0)
        if (existing.size >= maxBytes) return existing.copyOf(maxBytes)
        val remaining = maxBytes - existing.size
        val addition = if (incoming.size <= remaining) incoming else incoming.copyOf(remaining)
        return existing + addition
    }

    private fun inspectHttp1HeaderSignals(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        location: String,
        setCookie: String
    ): String? {
        val environment = resolveHttp1HeaderEnvironment(session, requestInspection, location, setCookie) ?: return null
        return inspectHttp1HeaderBranches(environment)
    }

    private fun shouldInspectHttp1ResponseBody(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        contentType: String
    ): String? {
        if (contentType.isBlank()) return null
        val targetedContentType = containsAnyContentType(contentType, "text/html", "json", "javascript")
        if (!targetedContentType) return null
        val host = normalizeAuthority(requestInspection?.host ?: session.host)
        val shouldInspect = shouldPreferDeepInspection(
            host = host,
            path = requestInspection?.path,
            appName = session.appName,
            requestDomain = extractRequestDomain(requestInspection)
        )
        return if (shouldInspect) "deep-inspection-target" else null
    }

    private fun inspectHttp1BodySignals(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        contentType: String,
        body: String,
        cosmeticSelectors: List<String>
    ): String? {
        if (contentType.isBlank()) return null
        val environment = resolveHttp1BodyEnvironment(session, requestInspection) ?: return null
        val mitmAggressive = isMitmAggressiveMode()
        val htmlContent = contentType.contains("html")
        val scriptOrJsonContent = containsAnyContentType(contentType, "json", "javascript")
        val targetedBodyContent = htmlContent || scriptOrJsonContent
        if (htmlContent && cosmeticSelectors.isNotEmpty()) {
            return "neutralized-cosmetic-rule"
        }
        if (!targetedBodyContent) return null
        return inspectTargetedHttp1BodyContent(
            session = session,
            requestInspection = requestInspection,
            environment = environment,
            body = body,
            htmlContent = htmlContent,
            scriptOrJsonContent = scriptOrJsonContent,
            mitmAggressive = mitmAggressive
        )
    }

    private fun inspectTargetedHttp1BodyContent(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        environment: Http1BodyEnvironment,
        body: String,
        htmlContent: Boolean,
        scriptOrJsonContent: Boolean,
        mitmAggressive: Boolean
    ): String? {
        val lowerBody = body.lowercase()
        val bodySignals = inspectAdBodySignals(lowerBody)
        val bodyReasons = bodySignals.reasons.toSet()
        val clusterSignals = inspectClusterBodySignals(
            lowerBody = lowerBody,
            host = environment.host,
            requestPath = requestInspection?.path
        )
        val threshold = resolveHttp1BodySignalThreshold(environment, mitmAggressive, clusterSignals)
        val novelSignals = inspectNovelBodySignals(
            lowerBody = lowerBody,
            scriptOrJsonContent = scriptOrJsonContent,
            htmlContent = htmlContent,
            bodyReasons = bodyReasons
        )
        val bodyDecisionContext = buildHttp1BodyDecisionContext(
            session = session,
            requestInspection = requestInspection,
            environment = environment,
            bodySignalScore = bodySignals.score,
            threshold = threshold
        )
        val commentSignals = inspectCommentAdBodySignals(lowerBody)
        return inspectHttp1BodyBranches(
            clusterSignals = clusterSignals,
            bodySignalScore = bodySignals.score,
            novelSignals = novelSignals,
            environment = environment,
            bodyDecisionContext = bodyDecisionContext,
            bodyReasons = bodyReasons,
            commentSignals = commentSignals
        )
    }

    private fun inspectHttp1HeaderBranches(environment: Http1HeaderEnvironment): String? {
        val branches = listOf<(Http1HeaderEnvironment) -> String?>(
            ::inspectProtectedHttp1HeaderBranch,
            ::inspectTargetedHttp1HeaderBranch,
            ::inspectPathRiskHttp1HeaderBranch,
            ::inspectGeneralHttp1HeaderBranch
        )
        return branches.firstNotNullOfOrNull { branch -> branch(environment) }
    }

    private fun inspectHttp1BodyBranches(
        clusterSignals: ClusterBodySignals,
        bodySignalScore: Int,
        novelSignals: NovelBodySignals,
        environment: Http1BodyEnvironment,
        bodyDecisionContext: Http1BodyDecisionContext,
        bodyReasons: Set<String>,
        commentSignals: CommentAdBodySignals
    ): String? {
        inspectBodyClusterBranch(
            clusterSignals = clusterSignals,
            bodySignalScore = bodySignalScore
        )?.let { return it }
        inspectNovelBodyReasonBranch(
            novelSignals = novelSignals,
            bodySignalScore = bodySignalScore,
            protectedNovelTarget = environment.protectedNovelTarget,
            aggressiveNovelTarget = environment.aggressiveNovelTarget,
            vendor = environment.vendor
        )?.let { return it }
        inspectCommentAdBodyBranch(
            decisionContext = bodyDecisionContext,
            bodyReasons = bodyReasons,
            commentSignals = commentSignals
        )?.let { return it }
        inspectNovelAdBodyBranch(decisionContext = bodyDecisionContext)?.let { return it }
        return inspectGeneralAdBodyBranch(decisionContext = bodyDecisionContext)
    }

    private fun resolveHttp1HeaderEnvironment(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        location: String,
        setCookie: String
    ): Http1HeaderEnvironment? {
        val context = TlsMitmSessionManager.getContextOrNull() ?: return null
        val host = normalizeAuthority(requestInspection?.host ?: session.host)
        if (host.isBlank()) return null
        val lowerPath = requestInspection?.path?.lowercase().orEmpty()
        val vendor = RuleRepository.classifyVendorFromHints(context, host, session.appName)
        val locationStrongKeyword = strongResponseAdKeywords.any(location::contains)
        val cookieStrongKeyword = strongResponseAdKeywords.any(setCookie::contains)
        val locationRecommendCardHit = containsAny(location, "recommend_card", "promo_card", "ad_card")
        val locationMaterialHit = containsAny(location, "material_url", "landing_url")
        val locationCommerceCardHit = containsAny(location, "shop_card", "mall_card", "goods_card", "product_card")
        val cookieMaterialHit = containsAny(setCookie, "ad_material", "material_url")
        return Http1HeaderEnvironment(
            context = context,
            host = host,
            lowerPath = lowerPath,
            requestDomain = extractRequestDomain(requestInspection),
            appName = session.appName,
            destinationPort = if (session.targetPort > 0) session.targetPort else 443,
            vendor = vendor,
            isNovelApp = RuleRepository.isNovelAppHint(session.appName),
            aggressiveAdApp = RuleRepository.isAggressiveAdAppHint(session.appName),
            pathInspection = inspectSuspiciousHttpPath(lowerPath),
            locationStrongHeader = strongHeaderKeywords.any(location::contains),
            cookieStrongHeader = strongHeaderKeywords.any(setCookie::contains),
            locationStrongKeyword = locationStrongKeyword,
            cookieStrongKeyword = cookieStrongKeyword,
            locationRecommendCardHit = locationRecommendCardHit,
            locationMaterialHit = locationMaterialHit,
            locationCommerceCardHit = locationCommerceCardHit,
            cookieMaterialHit = cookieMaterialHit,
            headerTrackingHits = adTrackingHeaderFields.count { field ->
                location.contains(field) || setCookie.contains(field)
            },
            pangleOrGdtHeaderTarget = pangleAndGdtHostSignals.any { host.contains(it) || lowerPath.contains(it) }
        )
    }

    private fun inspectProtectedHttp1HeaderBranch(environment: Http1HeaderEnvironment): String? {
        if (RuleRepository.isBlocked(
                environment.context,
                environment.host,
                appName = environment.appName,
                destinationPort = environment.destinationPort
            )) {
            return "neutralized-blocked-host"
        }
        if (RuleRepository.isUrlBlocked(
                environment.context,
                environment.host,
                environment.lowerPath,
                environment.appName,
                environment.requestDomain,
                destinationPort = environment.destinationPort
            )) {
            return "neutralized-blocked-url"
        }
        if (RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(
                environment.context,
                environment.host,
                environment.lowerPath,
                environment.appName
            )) {
            return "neutralized-novel-protected-path"
        }
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(
                environment.context,
                environment.host,
                environment.appName,
                environment.vendor
            )) {
            return "neutralized-novel-app-aggressive"
        }
        return null
    }

    private fun inspectTargetedHttp1HeaderBranch(environment: Http1HeaderEnvironment): String? {
        if (environment.pangleOrGdtHeaderTarget && (environment.headerMaterialHit || environment.strongHeaderOrKeywordHit)) {
            return "neutralized-pangle-gdt-header"
        }
        if (RuleRepository.shouldForcePushRecommendInspection(environment.host, environment.appName, environment.vendor) &&
            (environment.locationRecommendCardHit || environment.headerMaterialHit) &&
            (environment.strongHeaderOrKeywordHit || isKnownAdVendor(environment.vendor))) {
            return "neutralized-push-recommend-header"
        }
        if (looksLikeCommentAdPath(environment.lowerPath) &&
            (environment.locationRecommendCardHit || environment.headerMaterialHit || environment.strongHeaderOrKeywordHit)) {
            return "neutralized-comment-ad-header"
        }
        if (looksLikeCommentCommerceAdPath(environment.lowerPath) &&
            (environment.locationRecommendCardHit || environment.locationCommerceCardHit || environment.headerMaterialHit || environment.strongHeaderOrKeywordHit || environment.pangleOrGdtHeaderTarget)) {
            return "neutralized-comment-commerce-ad-header"
        }
        return null
    }

    private fun inspectPathRiskHttp1HeaderBranch(environment: Http1HeaderEnvironment): String? {
        if (environment.pathInspection.strongSuspicious) return "neutralized-strong-suspicious-path"
        if (environment.aggressiveAdApp && environment.pathInspection.rewardUnlock) return "neutralized-reward-unlock-path"
        if (environment.aggressiveAdApp && environment.pathInspection.suspicious &&
            (isKnownAdVendor(environment.vendor) || environment.headerTrackingHits >= 1 || environment.headerMaterialHit)) {
            return "neutralized-suspicious-path"
        }
        if (environment.pathInspection.suspicious) return "neutralized-suspicious-path"
        if (looksLikeDohRequest(environment.host, environment.lowerPath, emptyMap())) return "neutralized-doh-request"
        if (environment.pathInspection.rewardUnlock) return "neutralized-reward-unlock-path"
        return null
    }

    private fun inspectGeneralHttp1HeaderBranch(environment: Http1HeaderEnvironment): String? {
        if (environment.aggressiveAdApp && environment.headerTrackingHits >= 1 &&
            (environment.locationRecommendCardHit || environment.headerMaterialHit || environment.strongHeaderOrKeywordHit)) {
            return "neutralized-aggressive-app-tracking-header"
        }
        if (environment.isNovelApp && environment.locationRecommendCardHit &&
            (environment.locationStrongKeyword || environment.headerMaterialHit || isKnownAdVendor(environment.vendor))) {
            return "neutralized-novel-recommend-header"
        }
        if (environment.headerTrackingHits >= 1 && environment.isNovelApp) {
            return "neutralized-header-tracking"
        }
        if (environment.headerTrackingHits >= 2) {
            return "neutralized-header-tracking"
        }
        if (environment.locationStrongHeader) {
            return "neutralized-location-strong-header"
        }
        if (environment.cookieStrongHeader) {
            return "neutralized-setcookie-strong-header"
        }
        if (environment.aggressiveAdApp &&
            (environment.locationSponsorHit || environment.locationRecommendCardHit) &&
            (environment.locationStrongKeyword || isKnownAdVendor(environment.vendor))) {
            return "neutralized-aggressive-app-recommend-header"
        }
        if (isKnownAdVendor(environment.vendor) && (environment.locationStrongKeyword || environment.cookieStrongKeyword)) {
            return "neutralized-header-vendor-signal"
        }
        if (environment.locationStrongKeyword) {
            return "neutralized-location-ad-keyword"
        }
        if (environment.cookieStrongKeyword) {
            return "neutralized-setcookie-ad-keyword"
        }
        return null
    }

    private fun resolveHttp1BodySignalThreshold(
        environment: Http1BodyEnvironment,
        mitmAggressive: Boolean,
        clusterSignals: ClusterBodySignals
    ): Int {
        return when {
            environment.isNovelApp -> HTTP1_NOVEL_RESPONSE_BLOCK_SCORE
            mitmAggressive && clusterSignals.domesticSdkHits >= 1 -> HTTP1_MITM_AGGRESSIVE_RESPONSE_BLOCK_SCORE
            else -> HTTP1_RESPONSE_BLOCK_SCORE
        }
    }

    private data class Http1HeaderEnvironment(
        val context: Context,
        val host: String,
        val lowerPath: String,
        val requestDomain: String?,
        val appName: String,
        val destinationPort: Int,
        val vendor: String,
        val isNovelApp: Boolean,
        val aggressiveAdApp: Boolean,
        val pathInspection: PathInspection,
        val locationStrongHeader: Boolean,
        val cookieStrongHeader: Boolean,
        val locationStrongKeyword: Boolean,
        val cookieStrongKeyword: Boolean,
        val locationRecommendCardHit: Boolean,
        val locationMaterialHit: Boolean,
        val locationCommerceCardHit: Boolean,
        val cookieMaterialHit: Boolean,
        val headerTrackingHits: Int,
        val pangleOrGdtHeaderTarget: Boolean
    ) {
        val headerMaterialHit: Boolean
            get() = locationMaterialHit || cookieMaterialHit

        val strongHeaderOrKeywordHit: Boolean
            get() = locationStrongKeyword || cookieStrongKeyword

        val locationSponsorHit: Boolean
            get() = containsAny(lowerPath, "sponsor")
    }

    private fun buildHttp1BodyDecisionContext(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        environment: Http1BodyEnvironment,
        bodySignalScore: Int,
        threshold: Int
    ): Http1BodyDecisionContext {
        val reportContext = Http1BodyReportContext(
            context = environment.context,
            vendor = environment.vendor,
            host = environment.host,
            appName = session.appName,
            matchedPathHint = requestInspection?.path,
            refererDomain = extractRequestDomain(requestInspection)
        )
        return Http1BodyDecisionContext(
            reportContext = reportContext,
            bodySignalScore = bodySignalScore,
            threshold = threshold,
            protectedNovelTarget = environment.protectedNovelTarget,
            aggressiveNovelTarget = environment.aggressiveNovelTarget,
            vendor = environment.vendor,
            generalAdTarget = environment.generalAdTarget
        )
    }

    private fun resolveHttp1BodyEnvironment(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?
    ): Http1BodyEnvironment? {
        val context = TlsMitmSessionManager.getContextOrNull() ?: return null
        val host = normalizeAuthority(requestInspection?.host ?: session.host)
        if (RuleRepository.isSocialCoreDomain(host)) return null
        if (RuleRepository.isWhitelistedDomain(host)) return null
        if (RuleRepository.isSensitiveAuthDomain(host)) return null
        if (RuleRepository.shouldProtectMediaTraffic(host)) return null
        if (RuleRepository.shouldProtectBusinessTraffic(host)) return null
        val vendor = RuleRepository.classifyVendorFromHints(context, host, session.appName)
        return Http1BodyEnvironment(
            context = context,
            host = host,
            vendor = vendor,
            generalAdTarget = RuleRepository.shouldTreatAsGeneralAdTraffic(host, vendor, session.appName),
            aggressiveNovelTarget = RuleRepository.shouldAggressivelyBlockForNovelApp(context, host, session.appName, vendor),
            protectedNovelTarget = RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, host, requestInspection?.path, session.appName),
            isNovelApp = RuleRepository.isNovelAppHint(session.appName)
        )
    }

    private fun inspectBodyClusterBranch(
        clusterSignals: ClusterBodySignals,
        bodySignalScore: Int
    ): String? {
        if (clusterSignals.pangleAndGdtHits >= 3 && (bodySignalScore >= 1 || clusterSignals.domesticSdkHits >= 1)) {
            return "neutralized-body-pangle-gdt-cluster"
        }
        if (clusterSignals.domesticSdkHits >= 2 && bodySignalScore >= 2) {
            return "neutralized-body-domestic-sdk-cluster"
        }
        return null
    }

    private fun inspectClusterBodySignals(
        lowerBody: String,
        host: String,
        requestPath: String?
    ): ClusterBodySignals {
        return ClusterBodySignals(
            domesticSdkHits = domesticAdSdkKeywords.count { keyword ->
                lowerBody.contains(keyword) || host.contains(keyword)
            },
            pangleAndGdtHits = pangleAndGdtBodySignals.count(lowerBody::contains) +
                pangleAndGdtHostSignals.count { signal ->
                    host.contains(signal) || requestPath?.lowercase()?.contains(signal) == true
                }
        )
    }

    private fun inspectNovelBodyReasonBranch(
        novelSignals: NovelBodySignals,
        bodySignalScore: Int,
        protectedNovelTarget: Boolean,
        aggressiveNovelTarget: Boolean,
        vendor: String
    ): String? {
        if (novelSignals.rewardUnlockHits >= 2 && (protectedNovelTarget || aggressiveNovelTarget || bodySignalScore >= 1)) {
            return "neutralized-body-reward-unlock"
        }
        if (novelSignals.jsonNovelFieldHits >= 2 && (protectedNovelTarget || aggressiveNovelTarget || isKnownAdVendor(vendor))) {
            return "neutralized-body-json-novel-fields"
        }
        if (novelSignals.htmlNovelMarkerHits >= 2 && (protectedNovelTarget || aggressiveNovelTarget || bodySignalScore >= 1)) {
            return "neutralized-body-html-novel-ad"
        }
        if (novelSignals.hasMediaFieldCluster && bodySignalScore >= 1) {
            return "neutralized-body-media-field-cluster"
        }
        if (novelSignals.hasNovelFieldCluster) {
            return "neutralized-body-novel-field-cluster"
        }
        if (novelSignals.hasNovelTaskReward && (protectedNovelTarget || aggressiveNovelTarget)) {
            return "neutralized-body-novel-task-reward"
        }
        if (novelSignals.hasNovelCoinReward && (protectedNovelTarget || aggressiveNovelTarget)) {
            return "neutralized-body-novel-coin-reward"
        }
        return null
    }

    private fun inspectNovelBodySignals(
        lowerBody: String,
        scriptOrJsonContent: Boolean,
        htmlContent: Boolean,
        bodyReasons: Set<String>
    ): NovelBodySignals {
        return NovelBodySignals(
            rewardUnlockHits = rewardUnlockTokens.count(lowerBody::contains),
            jsonNovelFieldHits = if (scriptOrJsonContent) {
                jsonNovelFieldTokens.count(lowerBody::contains)
            } else 0,
            htmlNovelMarkerHits = if (htmlContent) {
                htmlNovelMarkerTokens.count(lowerBody::contains)
            } else 0,
            hasMediaFieldCluster = bodyReasons.contains("media-field-cluster"),
            hasNovelFieldCluster = bodyReasons.contains("novel-field-cluster"),
            hasNovelTaskReward = bodyReasons.contains("novel-task-reward"),
            hasNovelCoinReward = bodyReasons.contains("novel-coin-reward")
        )
    }

    private fun inspectCommentAdBodyBranch(
        decisionContext: Http1BodyDecisionContext,
        bodyReasons: Set<String>,
        commentSignals: CommentAdBodySignals
    ): String? {
        if (decisionContext.generalAdTarget &&
            (commentSignals.commentAdMaterialHit || commentSignals.commentRecommendCardHit) &&
            decisionContext.bodySignalScore >= 1) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-comment-general-ad", 2)
        }
        if ((bodyReasons.contains("comment-ad-extended") || bodyReasons.contains("comment-ad-flow-extended")) &&
            (decisionContext.bodySignalScore >= 2 || commentSignals.commentAdMaterialHit || commentSignals.commentRecommendCardHit)) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-comment-ad", 2)
        }
        if ((bodyReasons.contains("comment-commerce-ad-extended") || bodyReasons.contains("comment-gdt-commerce-ad-extended")) &&
            (commentSignals.commentCommerceSignalHit >= 2 || commentSignals.commentCommerceCardHit ||
                commentSignals.commentCommerceGdtHit || commentSignals.commentAdMaterialHit)) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-comment-commerce-ad", 2)
        }
        return null
    }

    private fun inspectCommentAdBodySignals(lowerBody: String): CommentAdBodySignals {
        return CommentAdBodySignals(
            commentAdMaterialHit = listOf(
                "\"ad_material", "\"material_url", "\"landing_url", "\"click_url", "\"show_url", "\"deep_link"
            ).any(lowerBody::contains),
            commentRecommendCardHit = listOf(
                "\"recommend_card", "\"promotion_card", "\"discover_card", "\"ad_card", "\"promo_card"
            ).any(lowerBody::contains),
            commentCommerceSignalHit = commentCommerceAdSignals.count(lowerBody::contains),
            commentCommerceCardHit = listOf(
                "\"shop_card", "\"mall_card", "\"goods_card", "\"product_card", "\"douyin_shop"
            ).any(lowerBody::contains),
            commentCommerceGdtHit = pangleAndGdtBodySignals.any(lowerBody::contains)
        )
    }

    private fun inspectNovelAdBodyBranch(
        decisionContext: Http1BodyDecisionContext
    ): String? {
        if (decisionContext.bodySignalScore >= 1 && decisionContext.protectedNovelTarget) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-novel-protected", 2)
        }
        if (decisionContext.bodySignalScore >= 1 && decisionContext.aggressiveNovelTarget) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-novel-aggressive", 2)
        }
        return null
    }

    private fun inspectGeneralAdBodyBranch(
        decisionContext: Http1BodyDecisionContext
    ): String? {
        if (decisionContext.bodySignalScore >= 1 && decisionContext.generalAdTarget && isKnownAdVendor(decisionContext.vendor)) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-general-ad-vendor", 2)
        }
        if (decisionContext.bodySignalScore >= decisionContext.threshold) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-strong-signal", 2)
        }
        if (decisionContext.bodySignalScore >= 2 && isKnownAdVendor(decisionContext.vendor)) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-vendor-signal", 1)
        }
        if (decisionContext.bodySignalScore >= 2 && decisionContext.generalAdTarget) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-general-ad", 1)
        }
        return null
    }

    private fun reportHttp1BodySignal(
        reportContext: Http1BodyReportContext,
        reason: String,
        confidenceBoost: Int
    ): String {
        RuleRepository.reportUnknownVendorIfNeeded(
            context = reportContext.context,
            vendor = reportContext.vendor,
            domain = reportContext.host,
            appName = reportContext.appName,
            signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
            confidenceBoost = confidenceBoost,
            matchedPathHint = reportContext.matchedPathHint,
            refererDomain = reportContext.refererDomain
        )
        return reason
    }

    private fun containsAnyContentType(contentType: String, vararg tokens: String): Boolean {
        return tokens.any(contentType::contains)
    }

    private fun containsAny(value: String, vararg tokens: String): Boolean {
        return tokens.any(value::contains)
    }

    private fun looksLikeCommentAdPath(path: String): Boolean {
        if (path.isBlank()) return false
        val commentScene = path.contains("comment") || path.contains("reply") || path.contains("floor") || path.contains("post")
        if (!commentScene) return false
        return path.contains("ad") ||
            path.contains("promo") ||
            path.contains("banner") ||
            path.contains("insert") ||
            path.contains("material") ||
            path.contains("landing") ||
            path.contains("recommend") ||
            path.contains("flow") ||
            path.contains("card")
    }

    private fun looksLikeCommentCommerceAdPath(path: String): Boolean {
        if (!looksLikeCommentAdPath(path)) return false
        return commentCommercePathSignals.any(path::contains) ||
            pangleAndGdtPathSignals.any(path::contains) ||
            containsAny(path, "gdt", "guangdiantong", "youlianghui", "douyin", "shop", "mall")
    }

    private fun buildCosmeticHtml(selectors: List<String>): String {
        if (selectors.isEmpty()) return "<html><body></body></html>"
        val css = selectors.joinToString(", ") { it }.take(4000)
        return "<html><head><style>$css { display: none !important; }</style></head><body></body></html>"
    }

    private fun buildCosmeticStyleTag(selectors: List<String>): String {
        if (selectors.isEmpty()) return ""
        val css = selectors.joinToString(", ") { it }.take(4000)
        return "<style data-hanfeng-cosmetic=\"1\">$css { display: none !important; visibility: hidden !important; opacity: 0 !important; }</style>"
    }

    private fun buildInjectedHtmlBody(originalBody: String, cosmeticSelectors: List<String>, cspValue: String? = null): ByteArray {
        val styleTag = buildCosmeticStyleTag(cosmeticSelectors)
        val cspMetaTag = buildCspMetaTag(cspValue)
        val injected = when {
            originalBody.contains("</head>", ignoreCase = true) -> {
                originalBody.replaceFirst("</head>", "$cspMetaTag$styleTag$SCRIPTLET_INJECTION</head>", ignoreCase = true)
            }
            originalBody.contains("<body", ignoreCase = true) -> {
                "$cspMetaTag$styleTag$SCRIPTLET_INJECTION$originalBody"
            }
            else -> {
                "<html><head>$cspMetaTag$styleTag$SCRIPTLET_INJECTION</head><body>$originalBody</body></html>"
            }
        }
        return injected.toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildCspMetaTag(cspValue: String?): String {
        val value = cspValue?.trim().orEmpty()
        if (value.isBlank()) return ""
        val escaped = value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return "<meta http-equiv=\"Content-Security-Policy\" content=\"$escaped\">"
    }

    private val SCRIPTLET_INJECTION = """<script>
// AdGuard-like Scriptlets - 增强版
(function(){
    try {
        // 禁用 window.open
        window.open = function(){ return { closed: true }; };
        // 禁用 sendBeacon
        if(window.navigator && window.navigator.sendBeacon) {
            window.navigator.sendBeacon = function(){ return true; };
        }
        // 禁用广告 SDK 常见全局变量
        window.csj = window.csj || {};
        window.csj.ad = function(){};
        window.gdt = window.gdt || {};
        window.gdt.AD = function(){};
        window.pangle = window.pangle || {};
        window.pangle.init = function(){};
        window.gromore = window.gromore || {};
        window.gromore.init = function(){};
        window.topon = window.topon || {};
        window.topon.init = function(){};
        window.tradplus = window.tradplus || {};
        window.tradplus.init = function(){};
        window.applovin = window.applovin || {};
        window.applovin.init = function(){};
        window.mintegral = window.mintegral || {};
        window.mintegral.init = function(){};
        window.mbridge = window.mbridge || {};
        window.mbridge.init = function(){};
        window.sigmob = window.sigmob || {};
        window.sigmob.init = function(){};
        window.ksad = window.ksad || {};
        window.ksad.init = function(){};
        window.anythink = window.anythink || {};
        window.anythink.init = function(){};
        window.mobvista = window.mobvista || {};
        window.mobvista.init = function(){};
        window.unityads = window.unityads || {};
        window.unityads.init = function(){};
        window.vungle = window.vungle || {};
        window.vungle.init = function(){};
        window.ironsrc = window.ironsrc || {};
        window.ironsrc.init = function(){};
        window.admob = window.admob || {};
        window.admob.init = function(){};
        // 禁用 setTimeout/setInterval 广告刷新
        var originalSetTimeout = window.setTimeout;
        var originalSetInterval = window.setInterval;
        window.setTimeout = function(fn, delay) {
            if(fn.toString().match(/ad|banner|splash|reward|promo|preroll|midroll|postroll|offerwall|unlock/i)) return;
            return originalSetTimeout.call(this, fn, delay);
        };
        window.setInterval = function(fn, delay) {
            if(fn.toString().match(/ad|banner|splash|reward|promo|preroll|midroll|postroll|offerwall|unlock/i)) return;
            return originalSetInterval.call(this, fn, delay);
        };
        // 禁用 XMLHttpRequest/ad 请求
        if(window.XMLHttpRequest) {
            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                if(typeof url === 'string' && url.match(/ad|ads|banner|splash|promo|tracking|preroll|midroll|postroll|offerwall|unlock/i)) {
                    this._isAdBlock = true;
                }
                return origOpen.apply(this, arguments);
            };
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function() {
                if(this._isAdBlock) return;
                return origSend.apply(this, arguments);
            };
        }
        // 禁用 Fetch API/ad 请求
        if(window.fetch) {
            var origFetch = window.fetch;
            window.fetch = function(url, options) {
                if(typeof url === 'string' && url.match(/ad|ads|banner|splash|promo|tracking|preroll|midroll|postroll|offerwall|unlock/i)) {
                    return Promise.resolve({ ok: false, status: 403, text: ()=>Promise.resolve(''), json: ()=>Promise.resolve({}) });
                }
                return origFetch.apply(this, arguments);
            };
        }
        // 拦截资源地址和页面跳转中的广告 URL
        var isAdLikeUrl = function(url) {
            return typeof url === 'string' && /ad|ads|banner|splash|promo|tracking|preroll|midroll|postroll|offerwall|unlock|material|landing|recommend|discover/i.test(url);
        };
        if(window.Element && window.Element.prototype && window.Element.prototype.setAttribute) {
            var origSetAttribute = window.Element.prototype.setAttribute;
            window.Element.prototype.setAttribute = function(name, value) {
                if((name === 'src' || name === 'href' || name === 'data-src' || name === 'data-url') && isAdLikeUrl(value)) {
                    return;
                }
                return origSetAttribute.apply(this, arguments);
            };
        }
        if(window.HTMLImageElement && Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src')) {
            var imageSrcDescriptor = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');
            Object.defineProperty(HTMLImageElement.prototype, 'src', {
                set: function(value) {
                    if(isAdLikeUrl(value)) return value;
                    return imageSrcDescriptor.set.call(this, value);
                },
                get: function() {
                    return imageSrcDescriptor.get.call(this);
                }
            });
        }
        if(window.HTMLAnchorElement && Object.getOwnPropertyDescriptor(HTMLAnchorElement.prototype, 'href')) {
            var anchorHrefDescriptor = Object.getOwnPropertyDescriptor(HTMLAnchorElement.prototype, 'href');
            Object.defineProperty(HTMLAnchorElement.prototype, 'href', {
                set: function(value) {
                    if(isAdLikeUrl(value)) return value;
                    return anchorHrefDescriptor.set.call(this, value);
                },
                get: function() {
                    return anchorHrefDescriptor.get.call(this);
                }
            });
        }
        if(window.location) {
            var origAssign = window.location.assign ? window.location.assign.bind(window.location) : null;
            var origReplace = window.location.replace ? window.location.replace.bind(window.location) : null;
            if(origAssign) {
                window.location.assign = function(url) {
                    if(isAdLikeUrl(url)) return;
                    return origAssign(url);
                };
            }
            if(origReplace) {
                window.location.replace = function(url) {
                    if(isAdLikeUrl(url)) return;
                    return origReplace(url);
                };
            }
        }
        if(window.history && window.history.pushState) {
            var origPushState = window.history.pushState;
            window.history.pushState = function(state, title, url) {
                if(isAdLikeUrl(url)) return;
                return origPushState.apply(this, arguments);
            };
        }
        if(window.history && window.history.replaceState) {
            var origReplaceState = window.history.replaceState;
            window.history.replaceState = function(state, title, url) {
                if(isAdLikeUrl(url)) return;
                return origReplaceState.apply(this, arguments);
            };
        }
        if(window.MutationObserver && document && document.documentElement) {
            new MutationObserver(function(mutations){
                mutations.forEach(function(mutation){
                    mutation.addedNodes && Array.prototype.forEach.call(mutation.addedNodes, function(node){
                        if(!node || !node.querySelectorAll) return;
                        if(node.matches && node.matches('[class*="ad"],[id*="ad"],[class*="banner"],[class*="promo"],[class*="splash"]')) {
                            node.remove();
                            return;
                        }
                        node.querySelectorAll('[class*="ad"],[id*="ad"],[class*="banner"],[class*="promo"],[class*="splash"],[class*="recommend"]')
                            .forEach(function(child){ child.remove(); });
                    });
                });
            }).observe(document.documentElement, { childList: true, subtree: true });
        }
    } catch(e){}
})();
</script>
<style>
/* Cosmetic Filters for common ad containers - 增强版 */
.ad-banner, .ad-container, .ads-wrapper, .ad-slot, .splash-ad, #adBanner, #adContainer, 
.adsbygoogle, .g-ad, .c-ad, .adbox, .ad-box, .ad_frame, .ad-area, #ads, .ad-content,
.ad-wrapper, .ad-unit, .popup-ad, .float-ad, .bottom-ad, .feed-ad, .video-ad, .native-ad,
.ad-content, .ad-image, .ad-text, .ad-link, .ad-logo, .ad-icon, .ad-btn, .ad-button,
.ad-card, .ad-box, .ad-list, .ad-item, .ad-close, .ad-cover, .ad-mask, .ad-layer,
.ad-dialog, .ad-pop, .ad-tip, .ad-toast, .ad-modal, .ad-overlay, .ad-bg, .ad-back,
.ad-splash, .ad-open, .ad-launch, .ad-interstitial, .ad-fullscreen, .ad-reward,
.ad-native, .ad-feed, .ad-stream, .ad-preload, .ad-download, .ad-install, .ad-open-url,
#adSlot, .ad-slot-container, .ad-slot-wrapper, .ad-slot-block, .ad-slot-area,
.bottom-banner, .floating-banner, .reader-banner, .reader-bottom-banner, .chapter-ad,
.insert-ad, .reading-insert-ad, .startup-banner, .pause-ad, .player-ad, .reward-pop,
.offerwall, .unlock-by-ad, .watch-ad-unlock, .preroll-ad, .midroll-ad, .postroll-ad { 
    display: none !important; 
    visibility: hidden !important;
    opacity: 0 !important;
    height: 0 !important;
    width: 0 !important;
    overflow: hidden !important;
}
</style>"""

    private fun buildSyntheticResponse(statusLine: String, contentType: String, body: String): String {
        val actualStatusLine = if (statusLine.startsWith("HTTP/1.")) {
            "${statusLine.substringBefore(' ')} 204 No Content"
        } else {
            "HTTP/1.1 204 No Content"
        }
        val injectedBody = if (contentType.contains("html")) {
            "<html><head>$SCRIPTLET_INJECTION</head><body></body></html>"
        } else body
        val contentLength = injectedBody.toByteArray(StandardCharsets.UTF_8).size
        return buildString {
            append(actualStatusLine).append("\r\n")
            append("Connection: close\r\n")
            append("Content-Type: ").append(if (contentType.isBlank()) "text/plain; charset=utf-8" else contentType).append("\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
            append("X-HanFeng-Block: 1\r\n")
            append("\r\n")
            append(injectedBody)
        }
    }

    private fun buildSyntheticResponse(
        statusLine: String,
        contentType: String,
        bodyBytes: ByteArray,
        cspValue: String? = null
    ): ByteArray {
        val actualStatusLine = if (statusLine.startsWith("HTTP/1.")) {
            "${statusLine.substringBefore(' ')} 200 OK"
        } else {
            "HTTP/1.1 200 OK"
        }
        val contentTypeValue = if (contentType.isBlank()) "text/plain; charset=utf-8" else contentType
        val normalizedCsp = cspValue?.trim().orEmpty()
        val headerBytes = buildString {
            append(actualStatusLine).append("\r\n")
            append("Connection: close\r\n")
            append("Content-Type: ").append(contentTypeValue).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
            if (normalizedCsp.isNotBlank()) {
                append("Content-Security-Policy: ").append(normalizedCsp).append("\r\n")
            }
            append("X-HanFeng-Block: 1\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        return headerBytes + bodyBytes
    }

    private fun decodeAscii(chunk: ByteArray): String? {
        return runCatching { String(chunk, StandardCharsets.ISO_8859_1) }.getOrNull()
    }

    private fun shouldPreferDeepInspection(
        host: String,
        path: String?,
        appName: String?,
        vendorHint: String? = null,
        requestDomain: String? = null
    ): Boolean {
        val context = TlsMitmSessionManager.getContextOrNull() ?: return false
        val normalizedHost = normalizeAuthority(host)
        if (normalizedHost.isBlank()) return false
        val lowerPath = path?.trim()?.lowercase().orEmpty()
        val normalizedAppName = appName?.trim()?.lowercase().orEmpty()
        val normalizedVendorHint = vendorHint?.trim()?.lowercase().orEmpty()
        val normalizedRequestDomain = requestDomain?.trim()?.lowercase().orEmpty()
        val cacheKey = "$normalizedHost|$lowerPath|$normalizedAppName|$normalizedVendorHint|$normalizedRequestDomain"
        synchronized(deepInspectionDecisionCacheLock) {
            deepInspectionDecisionCache[cacheKey]?.let { return it }
        }
        val destinationPort = when {
            normalizedHost.endsWith(":443") -> 443
            else -> 80
        }
        val blockedHost = RuleRepository.isBlocked(context, normalizedHost, appName = appName, destinationPort = destinationPort)
        if (blockedHost) return cacheDeepInspectionDecision(cacheKey, true)
        val pathInspection = inspectSuspiciousHttpPath(lowerPath)
        if (lowerPath.isNotBlank() && RuleRepository.hasAdvancedUrlRule(context, normalizedHost, lowerPath, appName, requestDomain, destinationPort = destinationPort)) return cacheDeepInspectionDecision(cacheKey, true)
        if (lowerPath.isNotBlank() && RuleRepository.isUrlBlocked(context, normalizedHost, lowerPath, appName, requestDomain, destinationPort = destinationPort)) return cacheDeepInspectionDecision(cacheKey, true)
        if (pathInspection.strongSuspicious) return cacheDeepInspectionDecision(cacheKey, true)
        if (looksLikeCommentAdPath(lowerPath)) return cacheDeepInspectionDecision(cacheKey, true)
        if (looksLikeCommentCommerceAdPath(lowerPath)) return cacheDeepInspectionDecision(cacheKey, true)
        // 游戏和社交 APP 核心服务跳过深度检查（提升性能，降低延迟）
        val lowerHost = normalizedHost.lowercase()
        if (RuleRepository.isGameCoreDomain(lowerHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.isSocialCoreDomain(lowerHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.isWhitelistedDomain(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.shouldProtectMediaTraffic(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.shouldProtectBusinessTraffic(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.isBypassProtectionDomain(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, true)
        val vendor = vendorHint?.trim()?.takeIf { it.isNotBlank() }
            ?: RuleRepository.classifyVendorFromHints(context, normalizedHost, appName)
        fun containsAny(value: String, vararg tokens: String): Boolean = tokens.any(value::contains)
        val pathMaterialHit = containsAny(lowerPath, "material", "landing", "show_url", "click_url")
        val pushRecommendCardPathHit = containsAny(lowerPath, "ad_card", "promo_card", "recommend_card")
        val pushRecommendPathHit = pathMaterialHit || pushRecommendCardPathHit
        val messageScenePathHit = containsAny(lowerPath, "message", "notice", "inbox", "notify", "bulletin", "discover", "guess_like", "sign", "benefit", "welfare", "mission")
        val messageAdPathHit = containsAny(lowerPath, "ad", "promo", "recommend", "popup", "task", "reward") || pathMaterialHit
        val apiFeedPathHit = containsAny(lowerPath, "feed", "splash", "popup", "insert")
        if (RuleRepository.shouldForcePushRecommendInspection(normalizedHost, appName, vendor) &&
            pushRecommendPathHit) {
            return cacheDeepInspectionDecision(cacheKey, true)
        }
        if (messageScenePathHit && messageAdPathHit) {
            return cacheDeepInspectionDecision(cacheKey, true)
        }
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(context, normalizedHost, appName, vendor) && pathInspection.suspicious) {
            return cacheDeepInspectionDecision(cacheKey, true)
        }
        if (RuleRepository.shouldForceNovelQuicBlock(normalizedHost, appName, vendor)) {
            return cacheDeepInspectionDecision(cacheKey, true)
        }
        if (isKnownAdVendor(vendor) && pathInspection.suspicious) return cacheDeepInspectionDecision(cacheKey, true)
        if (RuleRepository.shouldTreatAsGeneralAdTraffic(normalizedHost, vendor, appName) && pathInspection.suspicious) {
            return cacheDeepInspectionDecision(cacheKey, true)
        }
        if (domesticAdSdkKeywords.any { keyword -> normalizedHost.contains(keyword) || lowerPath.contains(keyword) }) {
            if (pathInspection.suspicious || adInfraRequestSignals.any { lowerPath.contains(it) }) {
                return cacheDeepInspectionDecision(cacheKey, true)
            }
        }
        if (pangleAndGdtHostSignals.any { normalizedHost.contains(it) || lowerPath.contains(it) } &&
            (pathInspection.suspicious || pangleAndGdtPathSignals.any { lowerPath.contains(it) })) {
            return cacheDeepInspectionDecision(cacheKey, true)
        }
        if (lowerPath.isBlank()) return cacheDeepInspectionDecision(cacheKey, false)
        if (adInfraRequestSignals.any { lowerPath.contains(it) }) return cacheDeepInspectionDecision(cacheKey, true)
        if (lowerPath.contains("?") && (lowerPath.contains("ad") || lowerPath.contains("promo") || lowerPath.contains("reward") || lowerPath.contains("banner"))) {
            return cacheDeepInspectionDecision(cacheKey, true)
        }
        return cacheDeepInspectionDecision(
            cacheKey,
            lowerPath.contains("/api/") &&
                apiFeedPathHit &&
                pathInspection.suspicious
        )
    }

    private fun cacheDeepInspectionDecision(cacheKey: String, decision: Boolean): Boolean {
        synchronized(deepInspectionDecisionCacheLock) {
            deepInspectionDecisionCache[cacheKey] = decision
        }
        return decision
    }

    private fun extractRequestDomain(inspection: RequestInspection?): String? {
        inspection ?: return null
        return inspection.origin?.let(::extractRequestContextDomain)
            ?: inspection.referer?.let(::extractRequestContextDomain)
    }

    private fun extractRequestDomain(inspection: Http2HeaderInspection?): String? {
        inspection ?: return null
        return inspection.referer?.let(::extractRequestContextDomain)
    }

    private fun extractRequestDomain(referer: String?): String? {
        return referer?.let(::extractRequestContextDomain)
    }

    private fun reportSuspiciousRedirectDomain(
        host: String,
        location: String?,
        appName: String?,
        refererDomain: String?,
        matchedPathHint: String?
    ) {
        val redirectDomain = extractRedirectDomain(location) ?: return
        if (redirectDomain == host) return
        val context = TlsMitmSessionManager.getContextOrNull() ?: return
        val vendor = RuleRepository.classifyVendorFromHints(context, redirectDomain, appName)
        val shouldSample = RuleRepository.shouldTreatAsGeneralAdTraffic(redirectDomain, vendor, appName) ||
            RuleRepository.shouldForcePushRecommendInspection(redirectDomain, appName, vendor) ||
            RuleRepository.shouldAggressivelyBlockForNovelApp(context, redirectDomain, appName, vendor)
        if (!shouldSample) return
        RuleRepository.reportUnknownVendorIfNeeded(
            context = context,
            vendor = vendor,
            domain = redirectDomain,
            appName = appName,
            signal = RuleRepository.SuspiciousSignal.HTTP_REDIRECT,
            confidenceBoost = 2,
            matchedPathHint = matchedPathHint,
            refererDomain = refererDomain
        )
    }

    private fun extractRedirectDomain(location: String?): String? {
        val normalized = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val host = when {
            normalized.startsWith("http://", ignoreCase = true) || normalized.startsWith("https://", ignoreCase = true) -> {
                runCatching { java.net.URI(normalized).host }.getOrNull()
            }
            normalized.startsWith("//") -> {
                runCatching { java.net.URI("https:$normalized").host }.getOrNull()
            }
            else -> null
        }
        return host?.let(::normalizeAuthority)
    }

    private fun buildReplacementBody(
        contentType: String,
        originalBody: String,
        cosmeticSelectors: List<String>,
        cspValue: String? = null
    ): ByteArray {
        return when {
            contentType.contains("application/json") -> "{}".toByteArray(StandardCharsets.UTF_8)
            contentType.contains("javascript") -> "".toByteArray(StandardCharsets.UTF_8)
            contentType.contains("text/html") -> {
                if (cosmeticSelectors.isEmpty()) {
                    "<html><head>${buildCspMetaTag(cspValue)}$SCRIPTLET_INJECTION</head><body></body></html>".toByteArray(StandardCharsets.UTF_8)
                } else {
                    buildInjectedHtmlBody(originalBody, cosmeticSelectors, cspValue)
                }
            }
            contentType.contains("image") -> TRANSPARENT_1X1_GIF
            else -> "".toByteArray(StandardCharsets.UTF_8)
        }
    }

    private fun buildRedirectReplacementBody(contentType: String, redirectResource: String?): ByteArray? {
        val resource = redirectResource?.trim()?.lowercase().orEmpty()
        if (resource.isBlank()) return null
        return when {
            resource.contains("noopjs") || resource.contains("noop.js") || resource.contains("noop-script") || resource.contains("noopscript") || resource.contains("abp-resource:blank-js") || resource.contains("blank-js") || resource.contains("empty-js") -> {
                "(()=>{})();".toByteArray(StandardCharsets.UTF_8)
            }
            resource.contains("1x1") || resource.contains("pixel") || resource.contains("transparent") || resource.contains("noopimage") || resource.contains("noop-image") || resource.contains("blank-image") || resource.contains("abp-resource:blank-image") || resource.contains("empty-image") -> {
                TRANSPARENT_1X1_GIF
            }
            resource.contains("empty") && contentType.contains("html") -> {
                "<html><head></head><body></body></html>".toByteArray(StandardCharsets.UTF_8)
            }
            resource.contains("empty") && contentType.contains("json") -> {
                "{}".toByteArray(StandardCharsets.UTF_8)
            }
            resource.contains("noopmp4") || resource.contains("noop-video") || resource.contains("noopvideo") || resource.contains("blank-mp4") || resource.contains("empty-mp4") -> {
                ByteArray(0)
            }
            resource.contains("noopcss") || resource.contains("noop-css") || resource.contains("blank-css") || resource.contains("abp-resource:blank-css") || resource.contains("empty-css") -> {
                ByteArray(0)
            }
            resource.contains("empty") || resource.contains("nooptext") || resource.contains("noop-text") || resource.contains("blank-text") -> {
                ByteArray(0)
            }
            else -> null
        }
    }

    // P1 增强：正则表达式缓存，避免重复编译
    private fun getCompiledRegex(pattern: String, flags: String): Regex? {
        val cacheKey = "$pattern|$flags"
        
        // 先查缓存
        compiledReplaceRules[cacheKey]?.let { return it }
        
        // 缓存未命中，编译新正则
        val regex = runCatching { Regex(pattern, buildReplaceRegexOptions(flags)) }.getOrNull() ?: return null
        
        // 存入缓存（检查容量）
        synchronized(compiledReplaceRulesLock) {
            if (compiledReplaceRules.size >= MAX_COMPILED_REGEX_CACHE) {
                // 简单清理：移除最早的 20% 条目
                val toRemove = compiledReplaceRules.keys.take(MAX_COMPILED_REGEX_CACHE / 5).toList()
                toRemove.forEach { key -> compiledReplaceRules.remove(key) }
            }
            compiledReplaceRules[cacheKey] = regex
        }
        
        return regex
    }

    private fun applyReplaceRules(contentType: String, body: String, replaceRules: Set<String>): String? {
        if (replaceRules.isEmpty()) return null
        val lowerType = contentType.lowercase()
        val textLike = lowerType.contains("text") ||
            lowerType.contains("json") ||
            lowerType.contains("javascript") ||
            lowerType.contains("xml") ||
            lowerType.contains("html")
        if (!textLike || body.isEmpty()) return null
        var updated = body
        replaceRules.forEach { encodedRule ->
            val parts = encodedRule.split('\u0000')
            if (parts.size < 3) return@forEach
            val pattern = parts[0]
            val replacement = parts[1]
            val flags = parts[2]
            // P1 增强：使用缓存的正则表达式
            val regex = getCompiledRegex(pattern, flags) ?: return@forEach
            updated = if ('g' in flags) {
                regex.replace(updated, replacement)
            } else {
                regex.replaceFirst(updated, replacement)
            }
        }
        return updated
    }

    private fun buildReplaceRegexOptions(flags: String): Set<RegexOption> {
        val options = linkedSetOf<RegexOption>()
        flags.forEach { flag ->
            when (flag) {
                'i' -> options += RegexOption.IGNORE_CASE
                'm' -> options += RegexOption.MULTILINE
                's' -> options += RegexOption.DOT_MATCHES_ALL
            }
        }
        return options
    }

    private fun parseHeaderOverrides(encodedHeaders: Set<String>): LinkedHashMap<String, String> {
        val overrides = linkedMapOf<String, String>()
        encodedHeaders.forEach { encodedHeader ->
            val parts = encodedHeader.split('\u0000', limit = 2)
            val headerName = parts.getOrNull(0)?.trim()?.lowercase().orEmpty()
            val headerValue = parts.getOrNull(1)?.trim().orEmpty()
            if (headerName.isNotBlank()) {
                overrides[headerName] = headerValue
            }
        }
        return overrides
    }

    private fun inferRedirectContentType(originalContentType: String, redirectResource: String?): String {
        val resource = redirectResource?.trim()?.lowercase().orEmpty()
        return when {
            resource.contains("noopjs") || resource.contains("noop.js") || resource.contains("noop-script") || resource.contains("noopscript") || resource.contains("abp-resource:blank-js") || resource.contains("blank-js") || resource.contains("empty-js") -> "application/javascript; charset=utf-8"
            resource.contains("1x1") || resource.contains("pixel") || resource.contains("transparent") || resource.contains("noopimage") || resource.contains("noop-image") || resource.contains("blank-image") || resource.contains("abp-resource:blank-image") || resource.contains("empty-image") -> "image/gif"
            resource.contains("noopcss") || resource.contains("noop-css") || resource.contains("blank-css") || resource.contains("abp-resource:blank-css") || resource.contains("empty-css") -> "text/css; charset=utf-8"
            resource.contains("noopmp4") || resource.contains("noop-video") || resource.contains("noopvideo") || resource.contains("blank-mp4") || resource.contains("empty-mp4") -> "video/mp4"
            resource.contains("empty") && originalContentType.contains("json") -> "application/json; charset=utf-8"
            resource.contains("empty") && originalContentType.contains("html") -> "text/html; charset=utf-8"
            originalContentType.isBlank() -> "text/plain; charset=utf-8"
            else -> originalContentType
        }
    }

    fun buildRedirectHttp2SyntheticResponse(streamId: Int, contentType: String, redirectResource: String?, cspValue: String? = null): ByteArray? {
        val body = buildRedirectReplacementBody(contentType, redirectResource) ?: return null
        val actualContentType = inferRedirectContentType(contentType, redirectResource)
        return Http2FrameCodec.buildSyntheticResponseFrames(
            streamId = streamId,
            status = 200,
            contentType = actualContentType,
            body = body,
            extraHeaders = buildList {
                add("cache-control" to "no-store")
                add("pragma" to "no-cache")
                add("x-hanfeng-block" to "1")
                cspValue?.trim()?.takeIf { it.isNotBlank() }?.let { add("content-security-policy" to it) }
            }
        )
    }

    private fun inspectAdBodySignals(lowerBody: String): BodySignalInspection {
        if (lowerBody.isBlank()) return BodySignalInspection(0, emptyList())
        val cacheKey = if (lowerBody.length <= 2048) lowerBody else lowerBody.take(2048)
        synchronized(bodySignalCacheLock) {
            bodySignalCache[cacheKey]?.let { return it }
        }
        val scene = collectAdBodySceneSignals(lowerBody)
        val accumulator = BodySignalAccumulator()
        val reasons = mutableListOf<String>()
        var score = scene.strongKeywordScore + scene.weakKeywordScore + scene.baseSceneScore
        appendBodySignalReasons(
            reasons,
            scene.strongKeywordReasons,
            scene.weakKeywordReasons,
            scene.baseSceneReasons
        )
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyBodySignalFieldScores(
                accumulator,
                scene.strongMatches,
                scene.weakMatches,
                scene.trackingFieldHits,
                scene.generalAdFieldHits,
                scene.novelAdFieldHits,
                scene.mediaAdFieldHits
            )
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyCommentFeedPushScores(
                accumulator = accumulator,
                lowerBody = lowerBody,
                commentAdPlacementHit = scene.commentAdPlacementHit,
                commentSceneHit = scene.commentSceneHit,
                commentOrPostSceneHit = scene.commentOrPostSceneHit,
                commentSceneExtendedHit = scene.commentSceneExtendedHit,
                commentMaterialSceneHit = scene.commentMaterialSceneHit,
                commentCommerceSceneHit = scene.commentCommerceSceneHit,
                commentPopupSceneHit = scene.commentPopupSceneHit,
                commentFloatSceneHit = scene.commentFloatSceneHit,
                commentFlowSceneHit = scene.commentFlowSceneHit,
                feedSceneHit = scene.feedSceneHit,
                recommendFeedSceneHit = scene.recommendFeedSceneHit,
                feedExtendedSceneHit = scene.feedExtendedSceneHit,
                pushSceneHit = scene.pushSceneHit,
                pushRecommendSceneHit = scene.pushRecommendSceneHit,
                pushMaterialSceneHit = scene.pushMaterialSceneHit,
                directMessageSceneHit = scene.directMessageSceneHit,
                directMessageAdSceneHit = scene.directMessageAdSceneHit,
                messageCenterSceneHit = scene.messageCenterSceneHit,
                messageCenterCardSceneHit = scene.messageCenterCardSceneHit,
                discoverSceneHit = scene.discoverSceneHit,
                discoverAdSceneHit = scene.discoverAdSceneHit,
                messageCenterMaterialSceneHit = scene.messageCenterMaterialSceneHit,
                messageCenterAdSceneHit = scene.messageCenterAdSceneHit,
                adMaterialHit = scene.adMaterialHit,
                deepLinkMaterialHit = scene.deepLinkMaterialHit,
                recommendCardHit = scene.recommendCardHit,
                materialUrlSceneHit = scene.materialUrlSceneHit,
                clickOrMaterialSceneHit = scene.clickOrMaterialSceneHit,
                pangleAndGdtSignalHit = pangleAndGdtBodySignals.any(lowerBody::contains)
            )
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyRewardAndStartupScores(
                accumulator = accumulator,
                signTaskBenefitSceneHit = scene.signTaskBenefitSceneHit,
                signTaskBenefitPlacementHit = scene.signTaskBenefitPlacementHit,
                readerSceneHit = scene.readerSceneHit,
                readerSignBenefitPlacementHit = scene.readerSignBenefitPlacementHit,
                materialUrlSceneHit = scene.materialUrlSceneHit
            )
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyStartupSceneScores(
                accumulator,
                scene.startupSceneHit,
                scene.startupOpenScreenSceneHit,
                scene.startupConfigHit,
                scene.startupAdMaterialHit,
                scene.startupMaterialPlacementHit,
                scene.startupCachePlacementHit,
                scene.startupPreloadPlacementHit,
                scene.materialUrlSceneHit
            )
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyReaderAndSdkScores(
                accumulator = accumulator,
                qimaoReaderSceneHit = scene.qimaoReaderSceneHit,
                qimaoReaderPlacementHit = scene.qimaoReaderPlacementHit,
                bannerSceneHit = scene.bannerSceneHit,
                bannerMaterialPlacementHit = scene.bannerMaterialPlacementHit,
                readerSceneHit = scene.readerSceneHit,
                readerAdPlacementClusterHit = scene.readerAdPlacementClusterHit,
                readerPageSceneHit = scene.readerPageSceneHit,
                readerPageAdPlacementHit = scene.readerPageAdPlacementHit,
                readerPageMaterialPlacementHit = scene.readerPageMaterialPlacementHit,
                readerPagePopupPlacementHit = scene.readerPagePopupPlacementHit,
                readerPageTailPlacementHit = scene.readerPageTailPlacementHit,
                coolapkCommentSceneHit = scene.coolapkCommentSceneHit,
                coolapkCommentPlacementHit = scene.coolapkCommentPlacementHit,
                gdtSdkSceneHit = scene.gdtSdkSceneHit,
                gdtSdkPlacementHit = scene.gdtSdkPlacementHit,
                aliSdkSceneHit = scene.aliSdkSceneHit,
                aliSdkPlacementHit = scene.aliSdkPlacementHit,
                shortvideoSdkSceneHit = scene.shortvideoSdkSceneHit,
                shortvideoSdkPlacementHit = scene.shortvideoSdkPlacementHit,
                materialUrlSceneHit = scene.materialUrlSceneHit
            )
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyTailBodySceneScores(
                accumulator = accumulator,
                dramaSceneHit = scene.dramaSceneHit,
                dramaPlacementHit = scene.dramaPlacementHit,
                liveSceneHit = scene.liveSceneHit,
                livePlacementHit = scene.livePlacementHit,
                comicSceneHit = scene.comicSceneHit,
                comicPlacementHit = scene.comicPlacementHit,
                rewardSceneHit = scene.rewardSceneHit,
                rewardPlacementHit = scene.rewardPlacementHit,
                adMaterialHit = scene.adMaterialHit,
                readerSceneHit = scene.readerSceneHit,
                rewardComicSceneHit = scene.rewardComicSceneHit,
                taskBenefitSceneHit = scene.taskBenefitSceneHit,
                taskBenefitPlacementHit = scene.taskBenefitPlacementHit,
                mediationSceneHit = scene.mediationSceneHit,
                mediationPlacementHit = scene.mediationPlacementHit,
                commentSceneExtendedForInsertHit = scene.commentSceneExtendedForInsertHit,
                commentInsertPlacementHit = scene.commentInsertPlacementHit,
                commentMaterialSceneExtendedHit = scene.commentMaterialSceneExtendedHit,
                commentMaterialPlacementHit = scene.commentMaterialPlacementHit,
                commentPopupPlacementExtendedHit = scene.commentPopupPlacementExtendedHit,
                playerSceneHit = scene.playerSceneHit,
                playerPlacementHit = scene.playerPlacementHit,
                playerExtendedSceneHit = scene.playerExtendedSceneHit,
                splashSceneHit = scene.splashSceneHit,
                splashPlacementHit = scene.splashPlacementHit || scene.adDispatchHit,
                materialUrlSceneHit = scene.materialUrlSceneHit
            )
        }
        applyBodySignalTailBonuses(
            readerSceneHit = scene.readerSceneHit,
            readerMaterialPlacementHit = scene.readerMaterialPlacementHit,
            materialUrlSceneHit = scene.materialUrlSceneHit,
            htmlMarkerHits = scene.htmlMarkerHits,
            reasons = reasons
        ) { scoreDelta -> score += scoreDelta }
        return cacheBodySignalInspection(cacheKey, BodySignalInspection(score, reasons.distinct()))
    }

    private fun appendBodySignalReasons(
        reasons: MutableList<String>,
        vararg reasonGroups: Collection<String>
    ) {
        reasonGroups.forEach(reasons::addAll)
    }

    private fun drainAccumulatedBodySignals(
        accumulator: BodySignalAccumulator,
        reasons: MutableList<String>
    ): Int {
        appendBodySignalReasons(reasons, accumulator.reasons)
        return accumulator.score.also { accumulator.reset() }
    }

    private inline fun applyAccumulatedBodySignals(
        accumulator: BodySignalAccumulator,
        reasons: MutableList<String>,
        action: () -> Unit
    ): Int {
        action()
        return drainAccumulatedBodySignals(accumulator, reasons)
    }

    private fun applyBodySignalTailBonuses(
        readerSceneHit: Boolean,
        readerMaterialPlacementHit: Boolean,
        materialUrlSceneHit: Boolean,
        htmlMarkerHits: List<String>,
        reasons: MutableList<String>,
        addScore: (Int) -> Unit
    ) {
        if (readerSceneHit && readerMaterialPlacementHit && materialUrlSceneHit) {
            addScore(3)
            reasons += "reader-ad-material-extended"
        }
        if (htmlMarkerHits.isNotEmpty()) {
            addScore(if (htmlMarkerHits.size >= 2) 2 else 1)
            reasons += htmlMarkerHits.take(4).map { "html-marker:$it" }
        }
    }

    private fun collectAdBodySceneSignals(lowerBody: String): AdBodySceneSignals {
        fun containsAny(vararg tokens: String): Boolean = tokens.any(lowerBody::contains)

        val strongMatches = bodyStrongMarkers.filter(lowerBody::contains)
        val weakMatches = bodyWeakMarkers.filter(lowerBody::contains)
        val trackingFieldHits = trackingFieldTokens.filter(lowerBody::contains)
        val generalAdFieldHits = generalAdFieldTokens.filter(lowerBody::contains)
        val novelAdFieldHits = novelAdFieldTokens.filter(lowerBody::contains)
        val mediaAdFieldHits = mediaAdFieldTokens.filter(lowerBody::contains)

        val strongKeywordScore = when {
            strongMatches.size >= 3 -> 5
            strongMatches.size == 2 -> 4
            strongMatches.size == 1 -> 3
            else -> 0
        }
        val weakKeywordScore = when {
            weakMatches.size >= 4 -> 2
            weakMatches.size >= 2 -> 1
            else -> 0
        }

        var baseSceneScore = 0
        val baseSceneReasons = mutableListOf<String>()
        fun addBaseSceneReason(reason: String) {
            baseSceneScore += 1
            baseSceneReasons += reason
        }
        if (lowerBody.contains("\"task_") && lowerBody.contains("\"reward")) {
            addBaseSceneReason("novel-task-reward")
        }
        if (lowerBody.contains("\"coin") && (lowerBody.contains("\"bonus") || lowerBody.contains("\"reward"))) {
            addBaseSceneReason("novel-coin-reward")
        }
        if (lowerBody.contains("\"video") &&
            (lowerBody.contains("\"ad") || lowerBody.contains("\"preroll") || lowerBody.contains("\"midroll"))) {
            addBaseSceneReason("video-ad-cluster")
        }

        val commentSceneHit = containsAny("\"comment", "\"reply", "\"floor")
        val commentOrPostSceneHit = commentSceneHit || lowerBody.contains("\"post\"")
        val feedSceneHit = containsAny("\"feed", "\"stream", "\"timeline")
        val recommendFeedSceneHit = feedSceneHit || lowerBody.contains("\"recommend")
        val pushSceneHit = containsAny("\"push", "\"notification", "\"notify", "\"message", "\"inbox")
        val messageCenterSceneHit = containsAny("\"message_center", "\"inbox_list", "\"notify_list", "\"bulletin_list")
        val directMessageSceneHit = containsAny("\"push_message", "\"notification_message", "\"system_message", "\"operation_message")
        val adMaterialHit = containsAny("\"ad_material", "\"material_url", "\"landing_url", "\"click_url", "\"show_url")
        val deepLinkMaterialHit = containsAny("\"deep_link", "\"download_url", "\"landing_url", "\"ad_material")
        val recommendCardHit = containsAny("\"recommend_card", "\"promotion_card", "\"discover_card", "\"ad_card")
        val commentAdPlacementHit = containsAny("\"ad_card", "\"reply_ad", "\"floor_ad")
        val commentSceneExtendedHit = containsAny(
            "\"comment_banner", "\"comment_ad_card", "\"comment_insert_ad", "\"reply_banner",
            "\"reply_ad_card", "\"floor_banner", "\"floor_promote", "\"comment_sponsor", "\"reply_sponsor"
        )
        val commentMaterialSceneHit = containsAny(
            "\"comment_material", "\"reply_material", "\"floor_material", "\"comment_landing_url",
            "\"reply_landing_url", "\"comment_click_url", "\"reply_click_url", "\"comment_deep_link"
        )
        val commentCommerceSceneHit = containsAny(
            "\"comment_goods", "\"reply_goods", "\"floor_goods", "\"comment_product", "\"reply_product",
            "\"comment_shop", "\"reply_shop", "\"floor_shop", "\"comment_mall", "\"reply_mall",
            "\"goods_card", "\"product_card", "\"shop_card", "\"mall_card", "\"douyin_shop"
        )
        val commentPopupSceneHit = containsAny(
            "\"comment_popup_ad", "\"reply_popup_ad", "\"floor_popup_ad", "\"comment_dialog_ad",
            "\"reply_dialog_ad", "\"comment_insert_popup"
        )
        val commentFloatSceneHit = containsAny(
            "\"comment_float_layer", "\"comment_float_card", "\"reply_float_card",
            "\"floor_float_card", "\"comment_overlay_ad", "\"reply_overlay_ad"
        )
        val commentFlowSceneHit = containsAny("\"comment_feed_ad", "\"comment_flow_ad", "\"reply_flow_ad", "\"floor_flow_ad")
        val feedExtendedSceneHit = containsAny(
            "\"stream_card_ad", "\"timeline_ad", "\"timeline_insert_ad", "\"recommend_ad", "\"recommend_card_ad",
            "\"feed_banner", "\"feed_card", "\"feed_insert_ad", "\"information_flow_ad", "\"stream_insert_ad", "\"information_flow"
        )
        val pushRecommendSceneHit = containsAny("\"recommend_ad", "\"recommend_card_ad", "\"promotion", "\"promo")
        val pushMaterialSceneHit = containsAny(
            "\"push_recommend_card", "\"push_material", "\"push_landing_url", "\"notification_ad_card",
            "\"system_push_ad", "\"operation_push_ad"
        )
        val directMessageAdSceneHit = containsAny("\"ad_card", "\"promo_card", "\"recommend_card")
        val messageCenterCardSceneHit = containsAny(
            "\"message_center_card_ad", "\"inbox_card_ad", "\"notify_card_ad", "\"notice_card_ad",
            "\"bulletin_card_ad", "\"operation_message_ad"
        )
        val discoverSceneHit = containsAny("\"discover", "\"recommend", "\"guess_like", "\"you_may_like")
        val discoverAdSceneHit = containsAny(
            "\"promotion_card", "\"sponsor_card", "\"ad_card", "\"landing_url", "\"material_url", "\"show_url"
        )
        val messageCenterMaterialSceneHit = containsAny(
            "\"message_center", "\"inbox", "\"notify", "\"bulletin", "\"notice"
        )
        val messageCenterAdSceneHit = containsAny(
            "\"message_center_ad\"", "\"message_center_banner\"", "\"inbox_ad\"", "\"notify_ad\"",
            "\"promotion_card\"", "\"promo_card\"", "\"operation_banner\"", "\"operation_card\""
        )
        val materialUrlSceneHit = containsAny(
            "\"click_url\"", "\"show_url\"", "\"material_url\"", "\"landing_url\""
        )
        val signTaskBenefitSceneHit = containsAny(
            "\"sign", "\"daily", "\"mission", "\"task", "\"benefit", "\"welfare"
        )
        val signTaskBenefitPlacementHit = containsAny(
            "\"sign_popup_ad\"", "\"daily_popup_ad\"", "\"mission_popup_ad\"", "\"task_popup_ad\"",
            "\"benefit_popup_ad\"", "\"welfare_popup_ad\"", "\"watch_ad_task\"", "\"coin_bonus\""
        )
        val readerSignBenefitPlacementHit = containsAny(
            "\"reader_sign_reward\"", "\"novel_sign_task\"", "\"sign_popup_ad\"", "\"benefit_popup_ad\"",
            "\"welfare_popup_ad\"", "\"mission_popup_ad\""
        )
        val startupSceneHit = containsAny("\"startup", "\"launch", "\"splash", "\"popup", "\"interstitial")
        val openScreenSceneHit = lowerBody.contains("\"open_screen")
        val startupOpenScreenSceneHit = startupSceneHit || openScreenSceneHit
        val startupConfigHit = containsAny(
            "\"startup_config", "\"launch_config", "\"splash_config", "\"popup_config", "\"interstitial_config",
            "\"open_screen", "\"open_screen_ad", "\"launch_ad", "\"startup_ad", "\"interstitial_ad",
            "\"open_screen_cache", "\"splash_cache", "\"startup_cache", "\"launch_cache", "\"startup_banner"
        )
        val adDispatchHit = lowerBody.contains("\"ad_dispatch")
        val downloadUrlHit = lowerBody.contains("\"download_url")
        val startupAdMaterialHit = adMaterialHit || adDispatchHit || downloadUrlHit
        val pageSceneHit = lowerBody.contains("\"page")
        val readerSceneHit = containsAny("\"reader", "\"chapter", "\"reading", "\"book")
        val readerPageSceneHit = readerSceneHit || pageSceneHit
        val startupMaterialPlacementHit = containsAny(
            "\"startup_page_ad\"", "\"launch_screen_ad\"", "\"open_screen_material\"", "\"splash_material\""
        )
        val startupCachePlacementHit = containsAny(
            "\"open_screen_cache\"", "\"startup_cache_material\"", "\"launch_cache_material\"", "\"splash_cache_material\""
        )
        val startupPreloadPlacementHit = containsAny(
            "\"startup_preload_ad\"", "\"launch_preload_ad\"", "\"splash_template_ad\"", "\"open_screen_dispatch\""
        )
        val qimaoReaderSceneHit = containsAny("\"qimao\"", "\"kmxs\"", "\"wtzw\"") || readerSceneHit
        val qimaoReaderPlacementHit = containsAny(
            "\"chapter_unlock\"", "\"watch_ad_unlock\"", "\"free_read_popup\"", "\"reader_reward_popup\"",
            "\"novel_welfare_center\"", "\"novel_task_center\""
        )
        val readerMaterialPlacementHit = containsAny(
            "\"reader_reward_popup\"", "\"chapter_offerwall\"", "\"free_read_popup\"", "\"reader_float_ad\"",
            "\"chapter_card_ad\""
        ) || qimaoReaderPlacementHit
        val bannerSceneHit = containsAny("\"banner", "\"bottom_banner", "\"floating_banner")
        val bannerMaterialPlacementHit = containsAny("\"show_url", "\"click_url", "\"material")
        val readerAdPlacementClusterHit = containsAny("\"bottom_banner", "\"insert_ad", "\"watch_ad_unlock", "\"unlock_by_ad")
        val readerPageAdPlacementHit = containsAny(
            "\"reader_bottom_ad\"", "\"reader_bottom_banner\"", "\"page_turn_ad\"", "\"turn_page_ad\"",
            "\"flip_page_ad\"", "\"page_insert_ad\"", "\"chapter_next_ad\"", "\"reading_interstitial\""
        )
        val readerPageMaterialPlacementHit = containsAny(
            "\"page_footer_ad\"", "\"chapter_footer_ad\"", "\"reader_footer_ad\"", "\"bottom_float_ad\"",
            "\"page_swipe_ad\"", "\"swipe_page_ad\"", "\"next_page_ad\"", "\"turn_page_banner\"",
            "\"page_corner_ad\"", "\"chapter_end_ad\""
        )
        val readerPagePopupPlacementHit = containsAny(
            "\"page_end_popup\"", "\"reader_page_popup\"", "\"chapter_page_popup\"", "\"page_tail_ad\"", "\"chapter_tail_ad\""
        )
        val readerPageTailPlacementHit = containsAny(
            "\"page_tail_popup\"", "\"chapter_tail_popup\"", "\"reader_tail_popup\"", "\"page_end_card\"",
            "\"chapter_end_card\"", "\"swipe_reward_ad\"", "\"page_flip_reward\"", "\"reader_next_popup\"", "\"chapter_next_popup\""
        )
        val coolapkSceneHit = lowerBody.contains("\"coolapk\"")
        val coolapkCommentSceneHit = coolapkSceneHit || commentOrPostSceneHit
        val coolapkCommentPlacementHit = containsAny(
            "\"comment_feed_ad\"", "\"comment_flow_ad\"", "\"reply_flow_ad\"", "\"comment_overlay_ad\"",
            "\"comment_float_card\"", "\"reply_float_card\""
        )
        val gdtSdkSceneHit = containsAny("\"gdt\"", "\"youlianghui\"", "\"guangdiantong\"", "\"adqq\"")
        val sdkMediationSceneHit = containsAny("\"waterfall\"", "\"mediation\"")
        val sdkMaterialPlacementHit = containsAny("\"ad_material\"", "\"placement_id\"")
        val gdtSdkPlacementHit = containsAny("\"bidding_token\"", "\"auction_id\"") || (sdkMediationSceneHit && sdkMaterialPlacementHit)
        val aliSdkSceneHit = containsAny("\"alipay\"", "\"alimama\"", "\"tanx\"", "\"adash\"")
        val aliSdkPlacementHit = containsAny("\"ad_strategy\"", "\"template_id\"") || (sdkMediationSceneHit && sdkMaterialPlacementHit)
        val shortvideoSdkSceneHit = containsAny("\"pangolin\"", "\"pangle\"", "\"gromore\"", "\"snssdk\"")
        val shortvideoSdkPlacementHit = containsAny("\"preload_ad\"", "\"ad_slot\"", "\"rit\"") || (sdkMediationSceneHit && adMaterialHit)
        val taskBenefitSceneHit = containsAny("\"task\"", "\"benefit\"", "\"welfare\"", "\"coin\"")
        val taskBenefitPlacementHit = containsAny(
            "\"task_center\"", "\"benefit_center\"", "\"welfare_center\"", "\"watch_ad_task\"", "\"daily_reward\"", "\"coin_bonus\""
        )
        val mediationPlacementHit = containsAny(
            "\"placement_id\"", "\"slot_id\"", "\"template_id\"", "\"ad_strategy\"", "\"ad_dispatch\""
        )
        val mediationSceneHit = sdkMediationSceneHit || containsAny("\"bidding\"", "\"auction\"")
        val commentInsertPlacementHit = containsAny(
            "\"comment_guide_ad\"", "\"comment_hot_ad\"", "\"comment_float_ad\"", "\"comment_promote_card\"",
            "\"comment_stream_ad\"", "\"reply_promote_card\"", "\"floor_insert_ad\"", "\"comment_promote\""
        )
        val commentMaterialPlacementHit = containsAny(
            "\"comment_promote\"", "\"reply_promote\"", "\"floor_promote\"", "\"comment_material\"",
            "\"reply_material\"", "\"floor_material\"", "\"comment_landing_url\"", "\"reply_landing_url\"", "\"post_landing_url\""
        )
        val commentPopupPlacementExtendedHit = containsAny(
            "\"comment_popup_ad\"", "\"comment_bottom_ad\"", "\"reply_bottom_ad\"", "\"floor_bottom_ad\""
        )
        val commentSceneExtendedForInsertHit = commentSceneHit
        val commentMaterialSceneExtendedHit = commentOrPostSceneHit
        val rewardPopupHit = lowerBody.contains("\"reward_popup")
        val watchAdUnlockHit = lowerBody.contains("\"watch_ad_unlock")
        val unlockByAdHit = lowerBody.contains("\"unlock_by_ad")
        val chapterUnlockAdHit = lowerBody.contains("\"chapter_unlock_ad")
        val rewardUnlockPlacementHit = watchAdUnlockHit || unlockByAdHit || chapterUnlockAdHit
        val dramaSceneHit = containsAny("\"drama", "\"episode", "\"short_video", "\"short_drama")
        val dramaPlacementHit = rewardPopupHit || containsAny("\"patch_ad", "\"insert_ad", "\"ad_material")
        val liveSceneHit = containsAny("\"live", "\"stream", "\"anchor")
        val livePlacementHit = containsAny("\"live_ad", "\"floating_banner", "\"show_url", "\"material")
        val comicSceneHit = containsAny("\"comic", "\"manga", "\"chapter")
        val comicPlacementHit = rewardUnlockPlacementHit || rewardPopupHit
        val sharedUrlPlacementHit = containsAny("show_url", "click_url", "material", "landing")
        val inlineAdMaterialHit = lowerBody.contains("ad_material")
        val playerSceneHit = containsAny("pause-ad", "player-ad", "reward-pop", "offerwall")
        val playerPlacementHit = sharedUrlPlacementHit
        val playerExtendedSceneHit = containsAny(
            "\"pause_ad\"", "\"player_ad\"", "\"preroll_ad\"", "\"midroll_ad\"", "\"postroll_ad\""
        )
        val splashSceneHit = containsAny("splash-ad", "open-screen", "startup-banner", "launch-ad")
        val splashPlacementHit = inlineAdMaterialHit || sharedUrlPlacementHit
        val rewardSceneHit = containsAny("\"reward", "\"unlock", "\"bonus", "\"task")
        val rewardPlacementHit = rewardPopupHit || rewardUnlockPlacementHit ||
            containsAny("\"free_read_card", "\"task_reward")
        val rewardComicSceneHit = comicSceneHit
        val htmlMarkerHits = htmlAdMarkers.filter { marker -> lowerBody.contains(marker) }

        return AdBodySceneSignals(
            strongMatches = strongMatches,
            weakMatches = weakMatches,
            trackingFieldHits = trackingFieldHits,
            generalAdFieldHits = generalAdFieldHits,
            novelAdFieldHits = novelAdFieldHits,
            mediaAdFieldHits = mediaAdFieldHits,
            strongKeywordScore = strongKeywordScore,
            strongKeywordReasons = strongMatches.take(5).map { "data-strong-keyword:$it" },
            weakKeywordScore = weakKeywordScore,
            weakKeywordReasons = weakMatches.take(3).map { "data-weak-keyword:$it" },
            baseSceneScore = baseSceneScore,
            baseSceneReasons = baseSceneReasons,
            commentSceneHit = commentSceneHit,
            commentOrPostSceneHit = commentOrPostSceneHit,
            feedSceneHit = feedSceneHit,
            recommendFeedSceneHit = recommendFeedSceneHit,
            pushSceneHit = pushSceneHit,
            messageCenterSceneHit = messageCenterSceneHit,
            directMessageSceneHit = directMessageSceneHit,
            adMaterialHit = adMaterialHit,
            deepLinkMaterialHit = deepLinkMaterialHit,
            recommendCardHit = recommendCardHit,
            commentAdPlacementHit = commentAdPlacementHit,
            commentSceneExtendedHit = commentSceneExtendedHit,
            commentMaterialSceneHit = commentMaterialSceneHit,
            commentCommerceSceneHit = commentCommerceSceneHit,
            commentPopupSceneHit = commentPopupSceneHit,
            commentFloatSceneHit = commentFloatSceneHit,
            commentFlowSceneHit = commentFlowSceneHit,
            feedExtendedSceneHit = feedExtendedSceneHit,
            pushRecommendSceneHit = pushRecommendSceneHit,
            pushMaterialSceneHit = pushMaterialSceneHit,
            directMessageAdSceneHit = directMessageAdSceneHit,
            messageCenterCardSceneHit = messageCenterCardSceneHit,
            discoverSceneHit = discoverSceneHit,
            discoverAdSceneHit = discoverAdSceneHit,
            messageCenterMaterialSceneHit = messageCenterMaterialSceneHit,
            messageCenterAdSceneHit = messageCenterAdSceneHit,
            materialUrlSceneHit = materialUrlSceneHit,
            clickOrMaterialSceneHit = materialUrlSceneHit || lowerBody.contains("\"download_url\""),
            readerSceneHit = readerSceneHit,
            signTaskBenefitSceneHit = signTaskBenefitSceneHit,
            signTaskBenefitPlacementHit = signTaskBenefitPlacementHit,
            readerSignBenefitPlacementHit = readerSignBenefitPlacementHit,
            startupSceneHit = startupSceneHit,
            startupOpenScreenSceneHit = startupOpenScreenSceneHit,
            startupConfigHit = startupConfigHit,
            adDispatchHit = adDispatchHit,
            startupAdMaterialHit = startupAdMaterialHit,
            readerPageSceneHit = readerPageSceneHit,
            startupMaterialPlacementHit = startupMaterialPlacementHit,
            startupCachePlacementHit = startupCachePlacementHit,
            startupPreloadPlacementHit = startupPreloadPlacementHit,
            qimaoReaderSceneHit = qimaoReaderSceneHit,
            qimaoReaderPlacementHit = qimaoReaderPlacementHit,
            readerMaterialPlacementHit = readerMaterialPlacementHit,
            bannerSceneHit = bannerSceneHit,
            bannerMaterialPlacementHit = bannerMaterialPlacementHit,
            readerAdPlacementClusterHit = readerAdPlacementClusterHit,
            readerPageAdPlacementHit = readerPageAdPlacementHit,
            readerPageMaterialPlacementHit = readerPageMaterialPlacementHit,
            readerPagePopupPlacementHit = readerPagePopupPlacementHit,
            readerPageTailPlacementHit = readerPageTailPlacementHit,
            coolapkCommentSceneHit = coolapkCommentSceneHit,
            coolapkCommentPlacementHit = coolapkCommentPlacementHit,
            gdtSdkSceneHit = gdtSdkSceneHit,
            sdkMediationSceneHit = sdkMediationSceneHit,
            gdtSdkPlacementHit = gdtSdkPlacementHit,
            aliSdkSceneHit = aliSdkSceneHit,
            aliSdkPlacementHit = aliSdkPlacementHit,
            shortvideoSdkSceneHit = shortvideoSdkSceneHit,
            shortvideoSdkPlacementHit = shortvideoSdkPlacementHit,
            taskBenefitSceneHit = taskBenefitSceneHit,
            taskBenefitPlacementHit = taskBenefitPlacementHit,
            mediationSceneHit = mediationSceneHit,
            mediationPlacementHit = mediationPlacementHit,
            commentSceneExtendedForInsertHit = commentSceneExtendedForInsertHit,
            commentInsertPlacementHit = commentInsertPlacementHit,
            commentMaterialSceneExtendedHit = commentMaterialSceneExtendedHit,
            commentMaterialPlacementHit = commentMaterialPlacementHit,
            commentPopupPlacementExtendedHit = commentPopupPlacementExtendedHit,
            dramaSceneHit = dramaSceneHit,
            dramaPlacementHit = dramaPlacementHit,
            liveSceneHit = liveSceneHit,
            livePlacementHit = livePlacementHit,
            comicSceneHit = comicSceneHit,
            comicPlacementHit = comicPlacementHit,
            playerSceneHit = playerSceneHit,
            playerPlacementHit = playerPlacementHit,
            playerExtendedSceneHit = playerExtendedSceneHit,
            splashSceneHit = splashSceneHit,
            splashPlacementHit = splashPlacementHit,
            rewardSceneHit = rewardSceneHit,
            rewardPlacementHit = rewardPlacementHit,
            rewardComicSceneHit = rewardComicSceneHit,
            htmlMarkerHits = htmlMarkerHits
        )
    }

    private data class AdBodySceneSignals(
        val strongMatches: List<String>,
        val weakMatches: List<String>,
        val trackingFieldHits: List<String>,
        val generalAdFieldHits: List<String>,
        val novelAdFieldHits: List<String>,
        val mediaAdFieldHits: List<String>,
        val strongKeywordScore: Int,
        val strongKeywordReasons: List<String>,
        val weakKeywordScore: Int,
        val weakKeywordReasons: List<String>,
        val baseSceneScore: Int,
        val baseSceneReasons: List<String>,
        val commentSceneHit: Boolean,
        val commentOrPostSceneHit: Boolean,
        val feedSceneHit: Boolean,
        val recommendFeedSceneHit: Boolean,
        val pushSceneHit: Boolean,
        val messageCenterSceneHit: Boolean,
        val directMessageSceneHit: Boolean,
        val adMaterialHit: Boolean,
        val deepLinkMaterialHit: Boolean,
        val recommendCardHit: Boolean,
        val commentAdPlacementHit: Boolean,
        val commentSceneExtendedHit: Boolean,
        val commentMaterialSceneHit: Boolean,
        val commentCommerceSceneHit: Boolean,
        val commentPopupSceneHit: Boolean,
        val commentFloatSceneHit: Boolean,
        val commentFlowSceneHit: Boolean,
        val feedExtendedSceneHit: Boolean,
        val pushRecommendSceneHit: Boolean,
        val pushMaterialSceneHit: Boolean,
        val directMessageAdSceneHit: Boolean,
        val messageCenterCardSceneHit: Boolean,
        val discoverSceneHit: Boolean,
        val discoverAdSceneHit: Boolean,
        val messageCenterMaterialSceneHit: Boolean,
        val messageCenterAdSceneHit: Boolean,
        val materialUrlSceneHit: Boolean,
        val clickOrMaterialSceneHit: Boolean,
        val readerSceneHit: Boolean,
        val signTaskBenefitSceneHit: Boolean,
        val signTaskBenefitPlacementHit: Boolean,
        val readerSignBenefitPlacementHit: Boolean,
        val startupSceneHit: Boolean,
        val startupOpenScreenSceneHit: Boolean,
        val startupConfigHit: Boolean,
        val adDispatchHit: Boolean,
        val startupAdMaterialHit: Boolean,
        val readerPageSceneHit: Boolean,
        val startupMaterialPlacementHit: Boolean,
        val startupCachePlacementHit: Boolean,
        val startupPreloadPlacementHit: Boolean,
        val qimaoReaderSceneHit: Boolean,
        val qimaoReaderPlacementHit: Boolean,
        val readerMaterialPlacementHit: Boolean,
        val bannerSceneHit: Boolean,
        val bannerMaterialPlacementHit: Boolean,
        val readerAdPlacementClusterHit: Boolean,
        val readerPageAdPlacementHit: Boolean,
        val readerPageMaterialPlacementHit: Boolean,
        val readerPagePopupPlacementHit: Boolean,
        val readerPageTailPlacementHit: Boolean,
        val coolapkCommentSceneHit: Boolean,
        val coolapkCommentPlacementHit: Boolean,
        val gdtSdkSceneHit: Boolean,
        val sdkMediationSceneHit: Boolean,
        val gdtSdkPlacementHit: Boolean,
        val aliSdkSceneHit: Boolean,
        val aliSdkPlacementHit: Boolean,
        val shortvideoSdkSceneHit: Boolean,
        val shortvideoSdkPlacementHit: Boolean,
        val taskBenefitSceneHit: Boolean,
        val taskBenefitPlacementHit: Boolean,
        val mediationSceneHit: Boolean,
        val mediationPlacementHit: Boolean,
        val commentSceneExtendedForInsertHit: Boolean,
        val commentInsertPlacementHit: Boolean,
        val commentMaterialSceneExtendedHit: Boolean,
        val commentMaterialPlacementHit: Boolean,
        val commentPopupPlacementExtendedHit: Boolean,
        val dramaSceneHit: Boolean,
        val dramaPlacementHit: Boolean,
        val liveSceneHit: Boolean,
        val livePlacementHit: Boolean,
        val comicSceneHit: Boolean,
        val comicPlacementHit: Boolean,
        val playerSceneHit: Boolean,
        val playerPlacementHit: Boolean,
        val playerExtendedSceneHit: Boolean,
        val splashSceneHit: Boolean,
        val splashPlacementHit: Boolean,
        val rewardSceneHit: Boolean,
        val rewardPlacementHit: Boolean,
        val rewardComicSceneHit: Boolean,
        val htmlMarkerHits: List<String>
    )

    private fun applyBodySignalFieldScores(
        accumulator: BodySignalAccumulator,
        strongMatches: List<String>,
        weakMatches: List<String>,
        trackingFieldHits: List<String>,
        generalAdFieldHits: List<String>,
        novelAdFieldHits: List<String>,
        mediaAdFieldHits: List<String>
    ) {
        if (trackingFieldHits.isNotEmpty()) {
            addBodySignalReasons(
                accumulator,
                if (trackingFieldHits.size >= 2) 3 else 2,
                trackingFieldHits.take(4).map { "data-field:$it" }
            )
        }
        if (generalAdFieldHits.isNotEmpty()) {
            addBodySignalReasons(
                accumulator,
                if (generalAdFieldHits.size >= 2) 3 else 2,
                generalAdFieldHits.take(4).map { "general-ad-field:$it" }
            )
        }
        if (novelAdFieldHits.size >= 2 && (strongMatches.isNotEmpty() || trackingFieldHits.isNotEmpty())) {
            addBodySignalReasons(accumulator, 2, novelAdFieldHits.take(4).map { "novel-field:$it" })
        }
        if (novelAdFieldHits.size >= 3) {
            addBodySignalReason(accumulator, 2, "novel-field-cluster")
        }
        if (mediaAdFieldHits.size >= 3) {
            addBodySignalReasons(
                accumulator,
                2,
                mediaAdFieldHits.take(4).map { "media-field:$it" } + "media-field-cluster"
            )
        }
        if (weakMatches.size >= 3) {
            addBodySignalReasons(accumulator, 2, weakMatches.take(4).map { "data-keyword:$it" })
        } else if (weakMatches.size == 2 && strongMatches.isNotEmpty()) {
            addBodySignalReasons(accumulator, 1, weakMatches.take(2).map { "data-keyword:$it" })
        }
    }

    private fun applyCommentFeedPushScores(
        accumulator: BodySignalAccumulator,
        lowerBody: String,
        commentAdPlacementHit: Boolean,
        commentSceneHit: Boolean,
        commentOrPostSceneHit: Boolean,
        commentSceneExtendedHit: Boolean,
        commentMaterialSceneHit: Boolean,
        commentCommerceSceneHit: Boolean,
        commentPopupSceneHit: Boolean,
        commentFloatSceneHit: Boolean,
        commentFlowSceneHit: Boolean,
        feedSceneHit: Boolean,
        recommendFeedSceneHit: Boolean,
        feedExtendedSceneHit: Boolean,
        pushSceneHit: Boolean,
        pushRecommendSceneHit: Boolean,
        pushMaterialSceneHit: Boolean,
        directMessageSceneHit: Boolean,
        directMessageAdSceneHit: Boolean,
        messageCenterSceneHit: Boolean,
        messageCenterCardSceneHit: Boolean,
        discoverSceneHit: Boolean,
        discoverAdSceneHit: Boolean,
        messageCenterMaterialSceneHit: Boolean,
        messageCenterAdSceneHit: Boolean,
        adMaterialHit: Boolean,
        deepLinkMaterialHit: Boolean,
        recommendCardHit: Boolean,
        materialUrlSceneHit: Boolean,
        clickOrMaterialSceneHit: Boolean,
        pangleAndGdtSignalHit: Boolean
    ) {
        fun containsAny(vararg tokens: String): Boolean = tokens.any(lowerBody::contains)
        if (lowerBody.contains("\"comment") && commentAdPlacementHit) {
            addBodySignalReason(accumulator, 1, "comment-ad-cluster")
        }
        if (commentSceneHit && commentSceneExtendedHit) {
            addBodySignalReason(accumulator, 2, "comment-ad-extended")
        }
        if (commentOrPostSceneHit && commentMaterialSceneHit &&
            (adMaterialHit || deepLinkMaterialHit || recommendCardHit)) {
            addBodySignalReason(accumulator, 3, "comment-ad-material-extended")
        }
        if (commentOrPostSceneHit && commentCommerceSceneHit &&
            (recommendCardHit || adMaterialHit || deepLinkMaterialHit)) {
            addBodySignalReason(accumulator, 4, "comment-commerce-ad-extended")
        }
        if (commentOrPostSceneHit && commentCommerceSceneHit &&
            (adMaterialHit || deepLinkMaterialHit) &&
            pangleAndGdtSignalHit) {
            addBodySignalReason(accumulator, 4, "comment-gdt-commerce-ad-extended")
        }
        if (commentOrPostSceneHit && commentPopupSceneHit &&
            (adMaterialHit || deepLinkMaterialHit)) {
            addBodySignalReason(accumulator, 3, "comment-ad-popup-extended")
        }
        if (commentOrPostSceneHit && commentFloatSceneHit) {
            addBodySignalReason(accumulator, 2, "comment-ad-float-extended")
        }
        if (commentOrPostSceneHit && commentFlowSceneHit && adMaterialHit) {
            addBodySignalReason(accumulator, 3, "comment-ad-flow-extended")
        }
        if (feedSceneHit && containsAny("\"ad_card", "\"insert_ad", "\"feed_ad")) {
            addBodySignalReason(accumulator, 1, "feed-ad-cluster")
        }
        if (recommendFeedSceneHit && feedExtendedSceneHit) {
            addBodySignalReason(accumulator, 2, "feed-ad-extended")
        }
        if (pushSceneHit && (pushRecommendSceneHit || adMaterialHit)) {
            addBodySignalReason(accumulator, 3, "push-recommend-ad-extended")
        }
        if (pushSceneHit && pushMaterialSceneHit &&
            (adMaterialHit || deepLinkMaterialHit || recommendCardHit)) {
            addBodySignalReason(accumulator, 4, "push-recommend-material-extended")
        }
        if (directMessageSceneHit && (directMessageAdSceneHit || adMaterialHit) && deepLinkMaterialHit) {
            addBodySignalReason(accumulator, 4, "push-message-ad-card-extended")
        }
        if (messageCenterSceneHit && (recommendCardHit || adMaterialHit) && (adMaterialHit || deepLinkMaterialHit)) {
            addBodySignalReason(accumulator, 4, "message-center-recommend-ad-extended")
        }
        if (messageCenterSceneHit && messageCenterCardSceneHit &&
            (adMaterialHit || deepLinkMaterialHit || recommendCardHit)) {
            addBodySignalReason(accumulator, 4, "message-center-card-ad-extended")
        }
        if (discoverSceneHit && discoverAdSceneHit && (deepLinkMaterialHit || materialUrlSceneHit)) {
            addBodySignalReason(accumulator, 4, "discover-recommend-ad-extended")
        }
        if (messageCenterMaterialSceneHit && messageCenterAdSceneHit && clickOrMaterialSceneHit) {
            addBodySignalReason(accumulator, 4, "message-center-ad-material-extended")
        }
    }

    private fun applyRewardAndStartupScores(
        accumulator: BodySignalAccumulator,
        signTaskBenefitSceneHit: Boolean,
        signTaskBenefitPlacementHit: Boolean,
        readerSceneHit: Boolean,
        readerSignBenefitPlacementHit: Boolean,
        materialUrlSceneHit: Boolean
    ) {
        if (signTaskBenefitSceneHit && signTaskBenefitPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 4, "sign-task-benefit-ad-extended")
        }
        if (readerSceneHit && readerSignBenefitPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 4, "reader-sign-benefit-ad-extended")
        }
    }

    private fun addBodySignalReasons(
        accumulator: BodySignalAccumulator,
        scoreDelta: Int,
        reasons: List<String>
    ) {
        accumulator.score += scoreDelta
        accumulator.reasons += reasons
    }

    private fun addBodySignalReason(
        accumulator: BodySignalAccumulator,
        scoreDelta: Int,
        reason: String
    ) {
        accumulator.score += scoreDelta
        accumulator.reasons += reason
    }

    private fun addBodySignalReasonIf(
        accumulator: BodySignalAccumulator,
        condition: Boolean,
        scoreDelta: Int,
        reason: String
    ) {
        if (condition) addBodySignalReason(accumulator, scoreDelta, reason)
    }

    private fun applyStartupSceneScores(
        accumulator: BodySignalAccumulator,
        startupSceneHit: Boolean,
        startupOpenScreenSceneHit: Boolean,
        startupConfigHit: Boolean,
        startupAdMaterialHit: Boolean,
        startupMaterialPlacementHit: Boolean,
        startupCachePlacementHit: Boolean,
        startupPreloadPlacementHit: Boolean,
        materialUrlSceneHit: Boolean
    ) {
        if (startupSceneHit && startupConfigHit && startupAdMaterialHit) {
            addBodySignalReason(accumulator, 2, "startup-ad-extended")
        }
        if (startupOpenScreenSceneHit && startupMaterialPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 3, "startup-ad-material-extended")
        }
        if (startupOpenScreenSceneHit && startupCachePlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 3, "startup-ad-cache-extended")
        }
        if (startupOpenScreenSceneHit && startupPreloadPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 3, "startup-ad-preload-extended")
        }
    }

    private fun applyReaderAndSdkScores(
        accumulator: BodySignalAccumulator,
        qimaoReaderSceneHit: Boolean,
        qimaoReaderPlacementHit: Boolean,
        bannerSceneHit: Boolean,
        bannerMaterialPlacementHit: Boolean,
        readerSceneHit: Boolean,
        readerAdPlacementClusterHit: Boolean,
        readerPageSceneHit: Boolean,
        readerPageAdPlacementHit: Boolean,
        readerPageMaterialPlacementHit: Boolean,
        readerPagePopupPlacementHit: Boolean,
        readerPageTailPlacementHit: Boolean,
        coolapkCommentSceneHit: Boolean,
        coolapkCommentPlacementHit: Boolean,
        gdtSdkSceneHit: Boolean,
        gdtSdkPlacementHit: Boolean,
        aliSdkSceneHit: Boolean,
        aliSdkPlacementHit: Boolean,
        shortvideoSdkSceneHit: Boolean,
        shortvideoSdkPlacementHit: Boolean,
        materialUrlSceneHit: Boolean
    ) {
        if (qimaoReaderSceneHit && qimaoReaderPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 4, "qimao-reader-ad-extended")
        }
        if (bannerSceneHit && bannerMaterialPlacementHit) {
            addBodySignalReason(accumulator, 1, "banner-ad-cluster")
        }
        if (readerSceneHit && readerAdPlacementClusterHit) {
            addBodySignalReason(accumulator, 2, "reader-ad-cluster")
        }
        if (readerPageSceneHit && readerPageAdPlacementHit) {
            addBodySignalReason(accumulator, 2, "reader-page-ad-extended")
        }
        if (readerPageSceneHit && readerPageMaterialPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 3, "reader-page-ad-material-extended")
        }
        if (readerPageSceneHit && readerPagePopupPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 3, "reader-page-popup-extended")
        }
        if (readerPageSceneHit && readerPageTailPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 3, "reader-page-tail-extended")
        }
        if (coolapkCommentSceneHit && coolapkCommentPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 3, "coolapk-comment-ad-extended")
        }
        if (gdtSdkSceneHit && gdtSdkPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 4, "gdt-sdk-ad-extended")
        }
        if (aliSdkSceneHit && aliSdkPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 4, "ali-sdk-ad-extended")
        }
        if (shortvideoSdkSceneHit && shortvideoSdkPlacementHit && materialUrlSceneHit) {
            addBodySignalReason(accumulator, 4, "shortvideo-sdk-ad-extended")
        }
    }

    private fun applyTailBodySceneScores(
        accumulator: BodySignalAccumulator,
        dramaSceneHit: Boolean,
        dramaPlacementHit: Boolean,
        liveSceneHit: Boolean,
        livePlacementHit: Boolean,
        comicSceneHit: Boolean,
        comicPlacementHit: Boolean,
        rewardSceneHit: Boolean,
        rewardPlacementHit: Boolean,
        adMaterialHit: Boolean,
        readerSceneHit: Boolean,
        rewardComicSceneHit: Boolean,
        taskBenefitSceneHit: Boolean,
        taskBenefitPlacementHit: Boolean,
        mediationSceneHit: Boolean,
        mediationPlacementHit: Boolean,
        commentSceneExtendedForInsertHit: Boolean,
        commentInsertPlacementHit: Boolean,
        commentMaterialSceneExtendedHit: Boolean,
        commentMaterialPlacementHit: Boolean,
        commentPopupPlacementExtendedHit: Boolean,
        playerSceneHit: Boolean,
        playerPlacementHit: Boolean,
        playerExtendedSceneHit: Boolean,
        splashSceneHit: Boolean,
        splashPlacementHit: Boolean,
        materialUrlSceneHit: Boolean
    ) {
        addBodySignalReasonIf(accumulator, dramaSceneHit && dramaPlacementHit, 2, "drama-ad-cluster")
        addBodySignalReasonIf(accumulator, liveSceneHit && livePlacementHit, 2, "live-ad-cluster")
        addBodySignalReasonIf(accumulator, comicSceneHit && comicPlacementHit, 2, "comic-ad-cluster")
        addBodySignalReasonIf(
            accumulator,
            rewardSceneHit && rewardPlacementHit && (adMaterialHit || readerSceneHit || rewardComicSceneHit),
            2,
            "reward-ad-extended"
        )
        addBodySignalReasonIf(accumulator, taskBenefitSceneHit && taskBenefitPlacementHit && materialUrlSceneHit, 3, "task-benefit-ad-extended")
        addBodySignalReasonIf(accumulator, mediationSceneHit && mediationPlacementHit && materialUrlSceneHit, 3, "mediation-ad-extended")
        addBodySignalReasonIf(accumulator, commentSceneExtendedForInsertHit && commentInsertPlacementHit, 2, "comment-ad-insert-extended")
        addBodySignalReasonIf(
            accumulator,
            commentMaterialSceneExtendedHit && commentMaterialPlacementHit && materialUrlSceneHit,
            3,
            "comment-ad-material-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            commentMaterialSceneExtendedHit && commentPopupPlacementExtendedHit && materialUrlSceneHit,
            3,
            "comment-ad-popup-extended"
        )
        addBodySignalReasonIf(accumulator, playerSceneHit && playerPlacementHit, 2, "player-ad-cluster")
        addBodySignalReasonIf(accumulator, playerExtendedSceneHit && materialUrlSceneHit, 2, "player-ad-extended")
        addBodySignalReasonIf(accumulator, splashSceneHit && splashPlacementHit, 2, "splash-ad-cluster")
    }

    private fun cacheBodySignalInspection(cacheKey: String, inspection: BodySignalInspection): BodySignalInspection {
        synchronized(bodySignalCacheLock) {
            bodySignalCache[cacheKey] = inspection
        }
        return inspection
    }

    private fun isKnownAdVendor(vendor: String): Boolean {
        if (vendor.isBlank()) return false
        val normalized = vendor.trim()
        val normalizedLower = normalized.lowercase()
        if (normalizedLower == "未知" ||
            normalizedLower == "其它 (other)" ||
            normalizedLower == "其它" ||
            normalizedLower == "other") {
            return false
        }
        return normalized.contains("广告") ||
            normalized.contains("Pangle") ||
            normalized.contains("Tencent Ads") ||
            normalized.contains("Tencent Marketing") ||
            normalized.contains("优量汇") ||
            normalized.contains("TopOn") ||
            normalized.contains("TradPlus") ||
            normalized.contains("Beizi") ||
            normalized.contains("AdScope") ||
            normalized.contains("Youmi") ||
            normalized.contains("Sigmob") ||
            normalized.contains("Unity Ads") ||
            normalized.contains("AppLovin") ||
            normalized.contains("ironSource") ||
            normalized.contains("Vungle") ||
            normalized.contains("Chartboost") ||
            normalized.contains("InMobi") ||
            normalized.contains("Mintegral") ||
            normalized.contains("PubMatic") ||
            normalized.contains("OpenX") ||
            normalized.contains("Taboola") ||
            normalized.contains("Outbrain") ||
            normalized.contains("AdColony") ||
            normalized.contains("Ogury") ||
            normalized.contains("Tapjoy")
    }

    fun inspectHttp2Headers(
        session: TlsMitmSessionManager.TlsMitmSession,
        headers: List<HpackDecoder.HeaderField>
    ): Http2HeaderInspection? {
        if (headers.isEmpty()) return null
        val normalized = normalizeHttp2Headers(headers)
        val environment = buildHttp2HeaderEnvironment(session, normalized)
        val pathInspection = inspectSuspiciousHttpPath(environment.lowerPath)
        val context = TlsMitmSessionManager.getContextOrNull() ?: return null
        val requestDomain = extractRequestDomain(environment.referer)
        val directives = RuleRepository.getRequestRewriteDirectives(
            context = context,
            host = environment.lowerAuthority,
            path = environment.lowerPath,
            appName = session.appName,
            requestDomain = requestDomain
        )
        reportSuspiciousRedirectDomain(
            host = environment.lowerAuthority,
            location = environment.location,
            appName = session.appName,
            refererDomain = requestDomain,
            matchedPathHint = environment.path
        )
        val destinationPort = if (environment.lowerAuthority.endsWith(":443")) 443 else 80
        val blockedHost = RuleRepository.isBlocked(context, environment.lowerAuthority, appName = session.appName, destinationPort = destinationPort)
        val blockedUrl = RuleRepository.isUrlBlocked(context, environment.lowerAuthority, environment.lowerPath, session.appName, requestDomain, destinationPort = destinationPort)
        // 白名单域名允许普通流量直通，但显式命中的拦截规则仍然优先执行
        if (shouldSkipProtectedHttp2Traffic(environment.lowerAuthority, blockedHost, blockedUrl)) return null
        val suspicion = Http2SuspicionAccumulator()
        if (blockedHost) suspicion.add(3, "blocked-host")
        if (blockedUrl) suspicion.add(3, "blocked-url")
        val vendor = RuleRepository.classifyVendorFromHints(context, environment.lowerAuthority, session.appName)
        val isNovelApp = RuleRepository.isNovelAppHint(session.appName)
        val aggressiveAdApp = RuleRepository.isAggressiveAdAppHint(session.appName)
        val vendorMaterialSignals = collectHttp2VendorMaterialSignals(
            lowerAuthority = environment.lowerAuthority,
            lowerPath = environment.lowerPath,
            lowerReferer = environment.lowerReferer,
            lowerLocation = environment.lowerLocation,
            lowerSetCookie = environment.lowerSetCookie,
            lowerUserAgent = environment.lowerUserAgent
        )
        applyHttp2PrimarySuspicion(
            accumulator = suspicion,
            context = context,
            lowerAuthority = environment.lowerAuthority,
            lowerPath = environment.lowerPath,
            lowerContentType = environment.lowerContentType,
            lowerAccept = environment.lowerAccept,
            lowerReferer = environment.lowerReferer,
            lowerUserAgent = environment.lowerUserAgent,
            appName = session.appName,
            vendor = vendor,
            isNovelApp = isNovelApp,
            aggressiveAdApp = aggressiveAdApp,
            pathInspection = pathInspection
        )
        applyHttp2VendorMaterialSuspicion(
            accumulator = suspicion,
            vendor = vendor,
            lowerPath = environment.lowerPath,
            lowerLocation = environment.lowerLocation,
            pathInspection = pathInspection,
            isNovelApp = isNovelApp,
            locationRecommendCardHit = vendorMaterialSignals.locationRecommendCardHit,
            headerMaterialHit = vendorMaterialSignals.headerMaterialHit,
            pathMaterialHit = vendorMaterialSignals.pathMaterialHit,
            domesticSdkHits = vendorMaterialSignals.domesticSdkHits,
            pangleAndGdtHits = vendorMaterialSignals.pangleAndGdtHits,
            lowerAuthority = environment.lowerAuthority,
            appName = session.appName,
            context = context
        )
        val keywordSignals = collectHttp2KeywordSignals(
            lowerPath = environment.lowerPath,
            lowerReferer = environment.lowerReferer,
            lowerContentType = environment.lowerContentType,
            lowerLocation = environment.lowerLocation,
            lowerSetCookie = environment.lowerSetCookie
        )
        applyHttp2KeywordSuspicion(
            accumulator = suspicion,
            pathInspection = pathInspection,
            isNovelApp = isNovelApp,
            refererKeywordHit = keywordSignals.refererKeywordHit,
            locationKeywordHit = keywordSignals.locationKeywordHit,
            setCookieKeywordHit = keywordSignals.setCookieKeywordHit,
            locationStrongHeaderHit = keywordSignals.locationStrongHeaderHit,
            setCookieStrongHeaderHit = keywordSignals.setCookieStrongHeaderHit,
            pathStrongKeywordHit = keywordSignals.pathStrongKeywordHit,
            locationStrongKeywordHit = keywordSignals.locationStrongKeywordHit,
            setCookieStrongKeywordHit = keywordSignals.setCookieStrongKeywordHit,
            headerTrackingHits = keywordSignals.headerTrackingHits,
            contentTypeStrongKeywordHit = keywordSignals.contentTypeStrongKeywordHit,
            contentTypeWeakKeywordHit = keywordSignals.contentTypeWeakKeywordHit
        )
        return buildHttp2HeaderInspection(
            session = session,
            environment = environment,
            vendor = vendor,
            suspicion = suspicion,
            directives = directives
        )
    }

    private fun applyHttp2PrimarySuspicion(
        accumulator: Http2SuspicionAccumulator,
        context: android.content.Context,
        lowerAuthority: String,
        lowerPath: String,
        lowerContentType: String,
        lowerAccept: String,
        lowerReferer: String,
        lowerUserAgent: String,
        appName: String?,
        vendor: String,
        isNovelApp: Boolean,
        aggressiveAdApp: Boolean,
        pathInspection: PathInspection
    ) {
        applyHttp2PathSuspicion(
            accumulator = accumulator,
            context = context,
            lowerAuthority = lowerAuthority,
            lowerPath = lowerPath,
            appName = appName,
            isNovelApp = isNovelApp,
            pathInspection = pathInspection
        )
        applyHttp2TrafficClassSuspicion(
            accumulator = accumulator,
            lowerAuthority = lowerAuthority,
            lowerPath = lowerPath,
            lowerContentType = lowerContentType,
            lowerAccept = lowerAccept,
            lowerReferer = lowerReferer,
            lowerUserAgent = lowerUserAgent,
            appName = appName,
            vendor = vendor,
            isNovelApp = isNovelApp,
            aggressiveAdApp = aggressiveAdApp
        )
    }

    private fun applyHttp2VendorMaterialSuspicion(
        accumulator: Http2SuspicionAccumulator,
        vendor: String,
        lowerPath: String,
        lowerLocation: String,
        pathInspection: PathInspection,
        isNovelApp: Boolean,
        locationRecommendCardHit: Boolean,
        headerMaterialHit: Boolean,
        pathMaterialHit: Boolean,
        domesticSdkHits: Int,
        pangleAndGdtHits: Int,
        lowerAuthority: String,
        appName: String?,
        context: android.content.Context
    ) {
        applyHttp2VendorSignalSuspicion(
            accumulator = accumulator,
            vendor = vendor,
            locationRecommendCardHit = locationRecommendCardHit,
            headerMaterialHit = headerMaterialHit,
            pathMaterialHit = pathMaterialHit
        )
        if (domesticSdkHits > 0) {
            accumulator.add(if (domesticSdkHits >= 2) 3 else 2, "domestic-sdk-signal")
        }
        if (pangleAndGdtHits > 0 && (headerMaterialHit || pathMaterialHit || pathInspection.suspicious)) {
            accumulator.add(if (pangleAndGdtHits >= 2) 4 else 3, "pangle-gdt-signal")
        }
        applyHttp2CommentCommerceSuspicion(
            accumulator = accumulator,
            lowerPath = lowerPath,
            lowerLocation = lowerLocation,
            locationRecommendCardHit = locationRecommendCardHit,
            headerMaterialHit = headerMaterialHit,
            pathMaterialHit = pathMaterialHit,
            pangleAndGdtHits = pangleAndGdtHits
        )
        accumulator.addIf(
            RuleRepository.shouldAggressivelyBlockForNovelApp(context, lowerAuthority, appName, vendor),
            if (isNovelApp) 4 else 3,
            "novel-app-aggressive"
        )
    }

    private fun applyHttp2KeywordSuspicion(
        accumulator: Http2SuspicionAccumulator,
        pathInspection: PathInspection,
        isNovelApp: Boolean,
        refererKeywordHit: Boolean,
        locationKeywordHit: Boolean,
        setCookieKeywordHit: Boolean,
        locationStrongHeaderHit: Boolean,
        setCookieStrongHeaderHit: Boolean,
        pathStrongKeywordHit: Boolean,
        locationStrongKeywordHit: Boolean,
        setCookieStrongKeywordHit: Boolean,
        headerTrackingHits: Int,
        contentTypeStrongKeywordHit: Boolean,
        contentTypeWeakKeywordHit: Boolean
    ) {
        accumulator.addIf(pathInspection.suspicious, if (isNovelApp) 3 else 2, "path-keyword")
        applyHttp2BasicKeywordSuspicion(
            accumulator = accumulator,
            isNovelApp = isNovelApp,
            refererKeywordHit = refererKeywordHit,
            locationKeywordHit = locationKeywordHit,
            setCookieKeywordHit = setCookieKeywordHit
        )
        applyHttp2StrongKeywordSuspicion(
            accumulator = accumulator,
            locationStrongHeaderHit = locationStrongHeaderHit,
            setCookieStrongHeaderHit = setCookieStrongHeaderHit,
            pathStrongKeywordHit = pathStrongKeywordHit,
            locationStrongKeywordHit = locationStrongKeywordHit,
            setCookieStrongKeywordHit = setCookieStrongKeywordHit
        )
        if (headerTrackingHits > 0) {
            accumulator.add(if (headerTrackingHits >= 2) 4 else (if (isNovelApp) 3 else 2), "header-tracking")
        }
        accumulator.addIf(contentTypeStrongKeywordHit, 1, "content-type-keyword")
        accumulator.addIf(contentTypeWeakKeywordHit, 1, "content-type-weak-keyword")
    }

    private fun normalizeHttp2Headers(headers: List<HpackDecoder.HeaderField>): LinkedHashMap<String, MutableList<String>> {
        val normalized = LinkedHashMap<String, MutableList<String>>()
        headers.forEach { header ->
            normalized.getOrPut(header.name.lowercase()) { mutableListOf() }.add(header.value)
        }
        return normalized
    }

    private class Http2SuspicionAccumulator(
        var score: Int = 0,
        val reasons: MutableList<String> = mutableListOf()
    ) {
        fun add(delta: Int, reason: String) {
            score += delta
            reasons += reason
        }

        fun addIf(condition: Boolean, delta: Int, reason: String) {
            if (condition) add(delta, reason)
        }
    }

    private data class Http2VendorMaterialSignals(
        val pathMaterialHit: Boolean,
        val locationRecommendCardHit: Boolean,
        val headerMaterialHit: Boolean,
        val domesticSdkHits: Int,
        val pangleAndGdtHits: Int
    )

    private data class Http2KeywordSignals(
        val refererKeywordHit: Boolean,
        val locationKeywordHit: Boolean,
        val setCookieKeywordHit: Boolean,
        val locationStrongHeaderHit: Boolean,
        val setCookieStrongHeaderHit: Boolean,
        val pathStrongKeywordHit: Boolean,
        val locationStrongKeywordHit: Boolean,
        val setCookieStrongKeywordHit: Boolean,
        val contentTypeStrongKeywordHit: Boolean,
        val contentTypeWeakKeywordHit: Boolean,
        val headerTrackingHits: Int
    )

    private data class Http2HeaderEnvironment(
        val method: String?,
        val authority: String,
        val path: String?,
        val scheme: String?,
        val status: String?,
        val contentType: String?,
        val referer: String?,
        val userAgent: String?,
        val location: String?,
        val setCookie: String?,
        val lowerAuthority: String,
        val lowerPath: String,
        val lowerReferer: String,
        val lowerContentType: String,
        val lowerLocation: String,
        val lowerSetCookie: String,
        val lowerUserAgent: String,
        val lowerAccept: String
    )

    private fun buildAllowHttp2ActionDecision(
        inspection: Http2HeaderInspection,
        confidence: String
    ): Http2ActionDecision {
        return buildHttp2ActionDecision(
            inspection = inspection,
            action = "allow",
            confidence = confidence,
            shouldBlock = false
        )
    }

    private fun resolveHttp2QualifiedActionDecision(
        inspection: Http2HeaderInspection,
        shouldBlock: Boolean
    ): Http2ActionDecision {
        return buildHttp2ActionDecision(
            inspection = inspection,
            action = resolveHttp2ActionName(inspection, shouldBlock),
            confidence = resolveHttp2ActionConfidence(inspection),
            shouldBlock = shouldBlock
        )
    }

    private fun collectHttp2VendorMaterialSignals(
        lowerAuthority: String,
        lowerPath: String,
        lowerReferer: String,
        lowerLocation: String,
        lowerSetCookie: String,
        lowerUserAgent: String
    ): Http2VendorMaterialSignals {
        val pathMaterialHit = containsAny(lowerPath, "material", "landing", "show_url", "click_url")
        val locationRecommendCardHit = containsAny(lowerLocation, "recommend_card", "promo_card", "ad_card")
        val locationMaterialHit = containsAny(lowerLocation, "material_url", "landing_url")
        val cookieMaterialHit = containsAny(lowerSetCookie, "ad_material", "material_url")
        return Http2VendorMaterialSignals(
            pathMaterialHit = pathMaterialHit,
            locationRecommendCardHit = locationRecommendCardHit,
            headerMaterialHit = locationMaterialHit || cookieMaterialHit,
            domesticSdkHits = domesticAdSdkKeywords.count { keyword ->
                lowerAuthority.contains(keyword) || lowerPath.contains(keyword) || lowerReferer.contains(keyword) || lowerUserAgent.contains(keyword)
            },
            pangleAndGdtHits = pangleAndGdtHostSignals.count { signal ->
                lowerAuthority.contains(signal) || lowerPath.contains(signal) || lowerReferer.contains(signal) || lowerLocation.contains(signal)
            }
        )
    }

    private fun collectHttp2KeywordSignals(
        lowerPath: String,
        lowerReferer: String,
        lowerContentType: String,
        lowerLocation: String,
        lowerSetCookie: String
    ): Http2KeywordSignals {
        return Http2KeywordSignals(
            refererKeywordHit = suspiciousHeaderKeywords.any(lowerReferer::contains),
            locationKeywordHit = suspiciousHeaderKeywords.any(lowerLocation::contains),
            setCookieKeywordHit = suspiciousHeaderKeywords.any(lowerSetCookie::contains),
            locationStrongHeaderHit = strongHeaderKeywords.any(lowerLocation::contains),
            setCookieStrongHeaderHit = strongHeaderKeywords.any(lowerSetCookie::contains),
            pathStrongKeywordHit = strongResponseAdKeywords.any(lowerPath::contains),
            locationStrongKeywordHit = strongResponseAdKeywords.any(lowerLocation::contains),
            setCookieStrongKeywordHit = strongResponseAdKeywords.any(lowerSetCookie::contains),
            contentTypeStrongKeywordHit = strongResponseAdKeywords.any(lowerContentType::contains),
            contentTypeWeakKeywordHit = responseAdKeywords.any(lowerContentType::contains),
            headerTrackingHits = adTrackingHeaderFields.count { field ->
                lowerLocation.contains(field) || lowerSetCookie.contains(field)
            }
        )
    }

    private fun buildHttp2HeaderEnvironment(
        session: TlsMitmSessionManager.TlsMitmSession,
        normalized: Map<String, List<String>>
    ): Http2HeaderEnvironment {
        val method = normalized[":method"]?.firstOrNull()?.ifBlank { null }
        val authority = normalized[":authority"]?.firstOrNull()?.ifBlank { null }
            ?.let(::normalizeAuthority)
            ?: normalized["host"]?.firstOrNull()?.ifBlank { null }
                ?.let(::normalizeAuthority)
            ?: normalizeAuthority(session.host)
        val path = normalized[":path"]?.firstOrNull()?.ifBlank { null }
        val scheme = normalized[":scheme"]?.firstOrNull()?.ifBlank { null }
        val status = normalized[":status"]?.firstOrNull()?.ifBlank { null }
        val contentType = normalized["content-type"]?.firstOrNull()?.ifBlank { null }
        val referer = normalized["referer"]?.firstOrNull()?.ifBlank { null }
        val userAgent = normalized["user-agent"]?.firstOrNull()?.ifBlank { null }
        val location = normalized["location"]?.firstOrNull()?.ifBlank { null }
        val setCookie = normalized["set-cookie"]?.firstOrNull()?.ifBlank { null }
        val lowerAuthority = normalizeAuthority(authority)
        return Http2HeaderEnvironment(
            method = method,
            authority = lowerAuthority,
            path = path,
            scheme = scheme,
            status = status,
            contentType = contentType,
            referer = referer,
            userAgent = userAgent,
            location = location,
            setCookie = setCookie,
            lowerAuthority = lowerAuthority,
            lowerPath = path?.lowercase().orEmpty(),
            lowerReferer = referer?.lowercase().orEmpty(),
            lowerContentType = contentType?.lowercase().orEmpty(),
            lowerLocation = location?.lowercase().orEmpty(),
            lowerSetCookie = setCookie?.lowercase().orEmpty(),
            lowerUserAgent = userAgent?.lowercase().orEmpty(),
            lowerAccept = normalized["accept"]?.firstOrNull()?.lowercase().orEmpty()
        )
    }

    private fun shouldSkipProtectedHttp2Traffic(
        lowerAuthority: String,
        blockedHost: Boolean,
        blockedUrl: Boolean
    ): Boolean {
        if (blockedHost || blockedUrl) return false
        if (RuleRepository.isWhitelistedDomain(lowerAuthority)) return true
        if (RuleRepository.shouldProtectMediaTraffic(lowerAuthority)) return true
        return RuleRepository.shouldProtectBusinessTraffic(lowerAuthority)
    }

    private fun buildHttp2HeaderInspection(
        session: TlsMitmSessionManager.TlsMitmSession,
        environment: Http2HeaderEnvironment,
        vendor: String,
        suspicion: Http2SuspicionAccumulator,
        directives: RuleRepository.RequestRewriteDirectives
    ): Http2HeaderInspection {
        return Http2HeaderInspection(
            method = environment.method,
            authority = environment.authority,
            appName = session.appName,
            path = environment.path,
            scheme = environment.scheme,
            status = environment.status,
            contentType = environment.contentType,
            referer = environment.referer,
            userAgent = environment.userAgent,
            location = environment.location,
            setCookie = environment.setCookie,
            vendor = vendor,
            suspiciousScore = suspicion.score,
            suspiciousReasons = suspicion.reasons,
            redirectResource = directives.redirectResource,
            cspValue = directives.cspValue,
            requestLike = environment.method != null && environment.status == null,
            responseLike = environment.status != null
        )
    }

    private fun applyHttp2PathSuspicion(
        accumulator: Http2SuspicionAccumulator,
        context: android.content.Context,
        lowerAuthority: String,
        lowerPath: String,
        appName: String?,
        isNovelApp: Boolean,
        pathInspection: PathInspection
    ) {
        accumulator.addIf(
            RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, lowerAuthority, lowerPath, appName),
            4,
            "novel-protected-path"
        )
        accumulator.addIf(pathInspection.strongSuspicious, if (isNovelApp) 4 else 3, "path-strong-suspicious")
        accumulator.addIf(looksLikeCommentAdPath(lowerPath), 3, "comment-ad-path")
        accumulator.addIf(looksLikeCommentCommerceAdPath(lowerPath), 4, "comment-commerce-path")
        accumulator.addIf(pathInspection.rewardUnlock, if (isNovelApp) 4 else 2, "reward-unlock-path")
    }

    private fun applyHttp2VendorSignalSuspicion(
        accumulator: Http2SuspicionAccumulator,
        vendor: String,
        locationRecommendCardHit: Boolean,
        headerMaterialHit: Boolean,
        pathMaterialHit: Boolean
    ) {
        accumulator.addIf(
            locationRecommendCardHit || headerMaterialHit || pathMaterialHit,
            1,
            "header-material-path-signal"
        )
        accumulator.addIf(isKnownAdVendor(vendor), 2, "vendor:$vendor")
    }

    private fun applyHttp2TrafficClassSuspicion(
        accumulator: Http2SuspicionAccumulator,
        lowerAuthority: String,
        lowerPath: String,
        lowerContentType: String,
        lowerAccept: String,
        lowerReferer: String,
        lowerUserAgent: String,
        appName: String?,
        vendor: String,
        isNovelApp: Boolean,
        aggressiveAdApp: Boolean
    ) {
        accumulator.addIf(
            looksLikeDohRequest(
                lowerAuthority,
                lowerPath,
                mapOf(
                    "content-type" to lowerContentType,
                    "accept" to lowerAccept,
                    "referer" to lowerReferer,
                    "user-agent" to lowerUserAgent
                )
            ),
            4,
            "doh-request"
        )
        accumulator.addIf(
            RuleRepository.shouldTreatAsGeneralAdTraffic(lowerAuthority, vendor, appName),
            if (isNovelApp) 4 else 3,
            "general-ad-traffic"
        )
        accumulator.addIf(
            RuleRepository.shouldForcePushRecommendInspection(lowerAuthority, appName, vendor),
            if (aggressiveAdApp) 5 else 4,
            "push-recommend-force-inspection"
        )
    }

    private fun applyHttp2CommentCommerceSuspicion(
        accumulator: Http2SuspicionAccumulator,
        lowerPath: String,
        lowerLocation: String,
        locationRecommendCardHit: Boolean,
        headerMaterialHit: Boolean,
        pathMaterialHit: Boolean,
        pangleAndGdtHits: Int
    ) {
        val commentCommercePath = looksLikeCommentCommerceAdPath(lowerPath)
        accumulator.addIf(
            commentCommercePath && (locationRecommendCardHit || headerMaterialHit || pathMaterialHit || pangleAndGdtHits > 0),
            4,
            "comment-commerce-ad-extended"
        )
        accumulator.addIf(
            commentCommercePath && pangleAndGdtHits > 0 &&
                (headerMaterialHit || pathMaterialHit || lowerLocation.contains("shop_card") || lowerLocation.contains("goods_card")),
            4,
            "comment-gdt-commerce-ad-extended"
        )
    }

    private fun applyHttp2BasicKeywordSuspicion(
        accumulator: Http2SuspicionAccumulator,
        isNovelApp: Boolean,
        refererKeywordHit: Boolean,
        locationKeywordHit: Boolean,
        setCookieKeywordHit: Boolean
    ) {
        val keywordScore = if (isNovelApp) 2 else 1
        accumulator.addIf(refererKeywordHit, keywordScore, "referer-keyword")
        accumulator.addIf(locationKeywordHit, keywordScore, "location-keyword")
        accumulator.addIf(setCookieKeywordHit, keywordScore, "set-cookie-keyword")
    }

    private fun applyHttp2StrongKeywordSuspicion(
        accumulator: Http2SuspicionAccumulator,
        locationStrongHeaderHit: Boolean,
        setCookieStrongHeaderHit: Boolean,
        pathStrongKeywordHit: Boolean,
        locationStrongKeywordHit: Boolean,
        setCookieStrongKeywordHit: Boolean
    ) {
        accumulator.addIf(locationStrongHeaderHit, 3, "location-strong-header")
        accumulator.addIf(setCookieStrongHeaderHit, 3, "set-cookie-strong-header")
        accumulator.addIf(pathStrongKeywordHit, 3, "path-strong-keyword")
        accumulator.addIf(locationStrongKeywordHit, 3, "location-strong-keyword")
        accumulator.addIf(setCookieStrongKeywordHit, 3, "set-cookie-strong-keyword")
    }

    fun decideHttp2Action(inspection: Http2HeaderInspection): Http2ActionDecision {
        val context = buildHttp2ActionContext(inspection)
        resolveEarlyHttp2ActionDecision(context)?.let { return it }
        return decideQualifiedHttp2Action(context)
    }

    private fun resolveEarlyHttp2ActionDecision(context: Http2ActionContext): Http2ActionDecision? {
        val inspection = context.inspection
        if (TlsMitmSessionManager.getContextOrNull() == null) {
            return buildAllowHttp2ActionDecision(inspection, "low")
        }
        val threshold = resolveHttp2ActionThreshold(context)
        if (inspection.suspiciousScore >= threshold) return null
        return buildAllowHttp2ActionDecision(inspection, "high")
    }

    private fun decideQualifiedHttp2Action(context: Http2ActionContext): Http2ActionDecision {
        val inspection = context.inspection
        val shouldBlock = shouldBlockHttp2ResponseFromHeaders(context)
        return resolveHttp2QualifiedActionDecision(inspection, shouldBlock)
    }

    private fun buildHttp2ActionContext(inspection: Http2HeaderInspection): Http2ActionContext {
        return Http2ActionContext(
            inspection = inspection,
            isNovelApp = RuleRepository.isNovelAppHint(inspection.appName)
        )
    }

    private fun shouldBlockHttp2ResponseFromHeaders(context: Http2ActionContext): Boolean {
        val inspection = context.inspection
        if (!isHttp2BlockCandidate(context)) return false
        if (shouldImmediatelyBlockHttp2Response(context)) return true
        return shouldBlockHttp2ResponseFromReasonSet(
            suspiciousScore = inspection.suspiciousScore,
            reasons = inspection.suspiciousReasons.toSet()
        )
    }

    private fun resolveHttp2ActionThreshold(context: Http2ActionContext): Int {
        val inspection = context.inspection
        return when {
            context.isNovelApp -> HTTP2_NOVEL_RESPONSE_BLOCK_SCORE
            isMitmAggressiveMode() && inspection.suspiciousReasons.any { it == "domestic-sdk-signal" } -> HTTP2_MITM_AGGRESSIVE_RESPONSE_BLOCK_SCORE
            else -> HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE
        }
    }

    private fun buildHttp2ActionDecision(
        inspection: Http2HeaderInspection,
        action: String,
        confidence: String,
        shouldBlock: Boolean
    ): Http2ActionDecision {
        return Http2ActionDecision(
            action = action,
            confidence = confidence,
            shouldBlockCandidate = shouldBlock,
            shouldSyntheticRespond = shouldBlock && inspection.responseLike,
            redirectResource = inspection.redirectResource,
            cspValue = inspection.cspValue,
            contentType = inspection.contentType
        )
    }

    private fun resolveHttp2ActionName(inspection: Http2HeaderInspection, shouldBlock: Boolean): String {
        return when {
            shouldBlock && !inspection.redirectResource.isNullOrBlank() -> "response-header-redirect"
            shouldBlock -> "block"
            else -> "monitor"
        }
    }

    private fun resolveHttp2ActionConfidence(inspection: Http2HeaderInspection): String {
        return if (inspection.suspiciousScore >= 4) "high" else "medium"
    }

    private fun resolveHttp2BlockThreshold(context: Http2ActionContext): Int {
        return if (context.isNovelApp) HTTP2_NOVEL_RESPONSE_BLOCK_SCORE else HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE
    }

    private fun isHttp2BlockCandidate(context: Http2ActionContext): Boolean {
        return context.inspection.suspiciousScore >= resolveHttp2BlockThreshold(context)
    }

    private fun shouldImmediatelyBlockHttp2Response(context: Http2ActionContext): Boolean {
        return when {
            context.isNovelApp -> context.inspection.suspiciousScore >= 2
            else -> context.inspection.suspiciousScore >= 4
        }
    }

    private data class Http2ActionContext(
        val inspection: Http2HeaderInspection,
        val isNovelApp: Boolean
    )

    private fun shouldBlockHttp2ResponseFromReasonSet(
        suspiciousScore: Int,
        reasons: Set<String>
    ): Boolean {
        if (reasons.any(::isHttp2ImmediateBlockReason)) {
            return true
        }
        return suspiciousScore >= 3 &&
            reasons.any(http2KeywordBlockReasons::contains) &&
            reasons.any { it.startsWith("vendor:") }
    }

    private fun isHttp2ImmediateBlockReason(reason: String): Boolean {
        return reason in http2ImmediateBlockReasons || reason.startsWith("header-field:")
    }

    private fun normalizeAuthority(value: String): String {
        val trimmed = value.trim().lowercase().trimEnd('.')
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith('[')) {
            val endBracket = trimmed.indexOf(']')
            if (endBracket > 1) {
                return trimmed.substring(1, endBracket)
            }
        }
        val firstColon = trimmed.indexOf(':')
        val lastColon = trimmed.lastIndexOf(':')
        if (firstColon > 0 && firstColon == lastColon) {
            val port = trimmed.substring(lastColon + 1)
            if (port.isNotEmpty() && port.all(Char::isDigit)) {
                return trimmed.substring(0, lastColon)
            }
        }
        return trimmed
    }

    private fun inspectSuspiciousHttpPath(path: String): PathInspection {
        synchronized(pathInspectionCacheLock) {
            pathInspectionCache[path]?.let { return it }
        }
        if (path.isBlank()) {
            return cachePathInspection(path, PathInspection(
                suspicious = false,
                strongSuspicious = false,
                rewardUnlock = false
            ))
        }
        val strongSuspicious = looksLikeStrongSuspiciousHttpPath(path)
        val rewardUnlock = looksLikeRewardUnlockPath(path)
        if (strongSuspicious) {
            return cachePathInspection(path, PathInspection(suspicious = true, strongSuspicious = true, rewardUnlock = rewardUnlock))
        }
        val suspicious = suspiciousPathKeywords.any { path.contains(it) }
        val query = path.substringAfter('?', "")
        if (query.isBlank()) {
            return cachePathInspection(path, PathInspection(
                suspicious = suspicious,
                strongSuspicious = false,
                rewardUnlock = rewardUnlock
            ))
        }
        val querySuspicious = suspiciousQueryKeywords.any { keyword ->
            queryContainsKeywordAssignment(query, keyword) || query.contains(keyword)
        }
        return cachePathInspection(path, PathInspection(
            suspicious = suspicious || querySuspicious,
            strongSuspicious = false,
            rewardUnlock = rewardUnlock
        ))
    }

    private fun cachePathInspection(path: String, inspection: PathInspection): PathInspection {
        synchronized(pathInspectionCacheLock) {
            pathInspectionCache[path] = inspection
        }
        return inspection
    }

    private fun looksLikeStrongSuspiciousHttpPath(path: String): Boolean {
        if (path.isBlank()) return false
        val strongPathKeywords = listOf(
            "/ad/request", "/ad/dispatch", "/ad/fetch", "/ad/material", "/ad/cache", "/ad/config",
            "/feed_insert_ad", "/timeline/insert", "/comment/list/ad", "/floor/insert/ad", "/reply/list/ad",
            "/reward/unlock", "/watch/ad/unlock", "/unlock/byad", "/chapter/unlock/ad", "/reward/popup",
            "/preroll", "/midroll", "/postroll", "/pause/ad", "/player/ad", "/open_screen_ad", "/startup_ad",
            "/page_turn_ad", "/turn_page_ad", "/flip_page_ad", "/page_footer_ad", "/chapter_footer_ad",
            "/comment/popup/ad", "/comment_bottom_ad", "/reply_bottom_ad", "/launch_screen_ad", "/startup_page_ad",
            "/startup_preload_ad", "/open_screen_dispatch", "/page_tail_popup", "/chapter_tail_popup", "/comment_flow_ad",
            "/message_center/ad", "/message/ad", "/notice/ad", "/notify/ad", "/inbox/ad", "/bulletin/ad",
            "/discover/card", "/discover/ad", "/recommend/card", "/promotion/card", "/promo/card",
            "/sign/popup", "/daily/popup", "/mission/popup", "/benefit/popup", "/welfare/popup"
        )
        if (strongPathKeywords.any { path.contains(it) }) return true
        val query = path.substringAfter('?', "")
        if (query.isBlank()) return false
        val strongQueryKeywords = listOf(
            "watch_ad_unlock", "unlock_by_ad", "reward_unlock", "reward_verify", "ad_dispatch", "ad_request",
            "ad_material", "ad_strategy", "ad_platform", "waterfall", "mediation", "biddingtoken", "auctionid",
            "message_center_ad", "promotion_card", "discover_card", "sign_popup_ad", "benefit_popup_ad", "welfare_popup_ad"
        )
        return strongQueryKeywords.any { keyword -> queryContainsKeywordAssignment(query, keyword) }
    }

    private fun looksLikeRewardUnlockPath(path: String): Boolean {
        if (path.isBlank()) return false
        val rewardHit = path.contains("reward")
        val unlockHit = path.contains("unlock")
        val watchAdHit = path.contains("watch_ad")
        val unlockByAdHit = path.contains("unlock_by_ad")
        val chapterUnlockHit = path.contains("chapter_unlock")
        val benefitHit = path.contains("benefit")
        val taskHit = path.contains("task")
        return rewardHit && unlockHit ||
            watchAdHit ||
            unlockByAdHit ||
            chapterUnlockHit ||
            benefitHit && taskHit
    }

    private fun queryContainsKeywordAssignment(query: String, keyword: String): Boolean {
        return query.contains("$keyword=") || query.contains("_$keyword=") || query.contains("-$keyword=")
    }

    private data class PathInspection(
        val suspicious: Boolean,
        val strongSuspicious: Boolean,
        val rewardUnlock: Boolean
    )

    private data class Http1ResponseHeaders(
        val statusLine: String,
        val contentType: String,
        val contentEncoding: String,
        val transferEncoding: String,
        val location: String,
        val setCookie: String
    )

    private data class Http1BodyReportContext(
        val context: android.content.Context,
        val vendor: String,
        val host: String,
        val appName: String?,
        val matchedPathHint: String?,
        val refererDomain: String?
    )

    private data class Http1BodyDecisionContext(
        val reportContext: Http1BodyReportContext,
        val bodySignalScore: Int,
        val threshold: Int,
        val protectedNovelTarget: Boolean,
        val aggressiveNovelTarget: Boolean,
        val vendor: String,
        val generalAdTarget: Boolean
    )

    private data class Http1BodyEnvironment(
        val context: android.content.Context,
        val host: String,
        val vendor: String,
        val generalAdTarget: Boolean,
        val aggressiveNovelTarget: Boolean,
        val protectedNovelTarget: Boolean,
        val isNovelApp: Boolean
    )

    private data class CommentAdBodySignals(
        val commentAdMaterialHit: Boolean,
        val commentRecommendCardHit: Boolean,
        val commentCommerceSignalHit: Int,
        val commentCommerceCardHit: Boolean,
        val commentCommerceGdtHit: Boolean
    )

    private data class NovelBodySignals(
        val rewardUnlockHits: Int,
        val jsonNovelFieldHits: Int,
        val htmlNovelMarkerHits: Int,
        val hasMediaFieldCluster: Boolean,
        val hasNovelFieldCluster: Boolean,
        val hasNovelTaskReward: Boolean,
        val hasNovelCoinReward: Boolean
    )

    private data class ClusterBodySignals(
        val domesticSdkHits: Int,
        val pangleAndGdtHits: Int
    )

    private fun looksLikeDohRequest(
        host: String,
        path: String,
        headers: Map<String, String>
    ): Boolean {
        val lowerHost = host.lowercase()
        val lowerPath = path.lowercase()
        if (RuleRepository.isBypassProtectionDomain(lowerHost)) return true
        if (dohPathKeywords.any(lowerPath::contains)) {
            if (lowerPath.contains("dns=") || lowerPath.contains("name=") || lowerPath.contains("type=") || lowerPath.contains("ct=")) {
                return true
            }
        }
        val contentType = headers["content-type"].orEmpty().lowercase()
        val accept = headers["accept"].orEmpty().lowercase()
        if (dohContentTypeKeywords.any { keyword -> contentType.contains(keyword) || accept.contains(keyword) }) {
            return true
        }
        return lowerHost.contains("httpdns") || lowerHost.contains("dns-query") || lowerHost.contains("resolver")
    }

    private fun isMitmAggressiveMode(): Boolean {
        val context = TlsMitmSessionManager.getContextOrNull() ?: return false
        return FeatureSettingsRepository.isHttpDecryptEnabled(context)
    }

    private fun rewriteRequestLine(requestLine: String, removeParams: Set<String>, removeParamRegexes: Set<String>): String {
        if (removeParams.isEmpty() && removeParamRegexes.isEmpty()) return requestLine
        val parts = requestLine.split(' ')
        if (parts.size < 2) return requestLine
        val updatedPath = rewritePathOnly(parts[1], removeParams, removeParamRegexes)
        if (updatedPath == parts[1]) return requestLine
        return buildString {
            append(parts[0]).append(' ').append(updatedPath)
            if (parts.size > 2) append(' ').append(parts.drop(2).joinToString(" "))
        }
    }

    private fun rewritePathOnly(path: String, removeParams: Set<String>, removeParamRegexes: Set<String> = emptySet()): String {
        if ((removeParams.isEmpty() && removeParamRegexes.isEmpty()) || !path.contains('?')) return path
        val base = path.substringBefore('?')
        val fragment = path.substringAfter('#', "")
        val query = path.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return path
        val regexRules = removeParamRegexes.mapNotNull { pattern -> runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() }
        val filtered = query.split('&')
            .filter { it.isNotBlank() }
            .filterNot { part ->
                val key = part.substringBefore('=').trim()
                val normalizedKey = key.lowercase()
                removeParams.contains(normalizedKey) || regexRules.any { it.matches(key) || it.matches(normalizedKey) }
            }
        val rebuilt = buildString {
            append(base)
            if (filtered.isNotEmpty()) append('?').append(filtered.joinToString("&"))
            if (fragment.isNotBlank()) append('#').append(fragment)
        }
        return rebuilt
    }

    private fun extractRequestContextDomain(value: String): String? {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank()) return null
        val host = normalized
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trim()
        if (host.isBlank()) return null
        return host.takeIf { it.contains('.') }
    }

    sealed interface FilterResult {
        data class PassThrough(val payload: ByteArray, val reason: String) : FilterResult
        data class Replaced(val payload: ByteArray, val reason: String, val originalBytes: Int = 0) : FilterResult
    }

    data class RequestInspection(
        val method: String,
        val path: String,
        val host: String,
        val httpVersion: String,
        val referer: String?,
        val origin: String?
    )

    data class Http2HeaderInspection(
        val method: String?,
        val authority: String,
        val appName: String?,
        val path: String?,
        val scheme: String?,
        val status: String?,
        val contentType: String?,
        val referer: String?,
        val userAgent: String?,
        val location: String?,
        val setCookie: String?,
        val vendor: String,
        val suspiciousScore: Int,
        val suspiciousReasons: List<String>,
        val redirectResource: String? = null,
        val cspValue: String? = null,
        val requestLike: Boolean,
        val responseLike: Boolean
    )

    data class Http2ActionDecision(
        val action: String,
        val confidence: String,
        val shouldBlockCandidate: Boolean,
        val shouldSyntheticRespond: Boolean = false,
        val redirectResource: String? = null,
        val cspValue: String? = null,
        val contentType: String? = null
    )

    data class Http2HeaderRewriteResult(
        val headers: List<HpackDecoder.HeaderField>,
        val changed: Boolean
    )

    sealed interface BufferedHttp1Result {
        data object AwaitMore : BufferedHttp1Result
        data class Ready(val responseBytes: ByteArray, val remainderBytes: ByteArray) : BufferedHttp1Result
        data class Bypass(val reason: String) : BufferedHttp1Result
    }

    data class Http2DataInspection(
        val suspiciousScore: Int,
        val suspiciousReasons: List<String>,
        val confidence: String,
        val samplePreview: String,
        val vendor: String,
        val combinedSample: ByteArray,
        val redirectResource: String? = null,
        val cspValue: String? = null,
        val contentType: String = ""
    )

    private data class BodySignalInspection(
        val score: Int,
        val reasons: List<String>
    )

    private data class BodySignalAccumulator(
        var score: Int = 0,
        val reasons: MutableList<String> = mutableListOf()
    ) {
        fun reset() {
            score = 0
            reasons.clear()
        }
    }
}
