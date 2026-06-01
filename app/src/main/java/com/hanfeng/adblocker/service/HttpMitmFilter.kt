package com.HanFeng.service

import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.RuleRepository
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

object HttpMitmFilter {
    private const val MAX_HTTP1_FILTER_BUFFER_BYTES = 512 * 1024
    private const val MAX_HTTP2_DATA_SAMPLE_BYTES = 8 * 1024
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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PathInspection>?): Boolean = size > 512
    }
    private val pathInspectionCacheLock = Any()
    private val deepInspectionDecisionCache = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 512
    }
    private val deepInspectionDecisionCacheLock = Any()
    private val bodySignalCache = object : LinkedHashMap<String, BodySignalInspection>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BodySignalInspection>?): Boolean = size > 256
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
        "nativead", "nativead", "videoad", "rewardad", "loginad", "guidead", "scrollad", "pushad",
        // 新增广告响应特征
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
        "reader_bottom_ad", "page_turn_ad", "turn_page_ad", "flip_page_ad", "page_insert_ad", "chapter_next_ad", "reading_page_ad", "chapter_page_ad"
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
    private val htmlAdMarkers = listOf(
        "adsbygoogle",
        "google_ad",
        "ad-container",
        "ad-wrapper",
        "ad-banner",
        "adslot",
        "ad-unit",
        "adunit",
        "adservice",
        "splash-ad",
        "open-screen",
        "reward-video",
        "window.__slot__",
        "window.__ad__",
        "window.__ads__",
        "window.csj",
        "window.gdt",
        "window.pangle",
        "window.gromore",
        "window.topon",
        "window.tradplus",
        "window.applovin",
        "window.mintegral",
        "window.ksad",
        "window.mobvista",
        "window.mbridge",
        "window.anythink",
        // 新增广告框架标记
        "window.byted",
        "window.ttad",
        "window.admar",
        "window.sigmob",
        "window.kwad",
        "window.mimo",
        "window.unityads",
        "window.vungle",
        "window.ironsrc",
        ".ad-banner",
        ".adBox",
        "#adContainer",
        "#adWrapper",
        ".popup-ad",
        ".float-ad",
        ".bottom-ad",
        ".feed-ad",
        ".video-ad",
        ".native-ad",
        "feed-card-ad",
        "information-flow-ad",
        "comment-ad",
        "floor-ad",
        "reply-ad",
        "comment-guide-ad",
        "comment-hot-ad",
        "post-ad",
        "bottom-banner",
        "floating-banner",
        "open-screen-ad",
        "startup-ad",
        "launch-ad",
        "interstitial-ad",
        "native-express",
        "ad-card",
        "ad-item",
        "ad-layout",
        "banner-layout",
        "feed-insert-ad",
        "insert-ad-card",
        "pause-ad",
        "player-ad",
        "reward-pop",
        "chapter-unlock-ad",
        "watch-ad-unlock",
        "reader-bottom-ad",
        "page-turn-ad",
        "turn-page-ad",
        "flip-page-ad"
    )
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
        val directives = RuleRepository.getRequestRewriteDirectives(
            TlsMitmSessionManager.requireContext(),
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
        val headerLines = text.substring(0, headerEnd).split("\r\n")
        if (headerLines.isEmpty()) return chunk
        var changed = false
        val rewrittenHeaders = headerLines.mapIndexedNotNull { index, line ->
            if (index == 0) return@mapIndexedNotNull line
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
        val directives = RuleRepository.getRequestRewriteDirectives(
            TlsMitmSessionManager.requireContext(),
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
        if (removeParams.isEmpty() && removeParamRegexes.isEmpty() && directives.cspValue == null) return base
        var changed = base.changed
        val rewritten = base.headers.map { header ->
            if (header.name == ":path") {
                val updated = rewritePathOnly(header.value, removeParams, removeParamRegexes)
                if (updated != header.value) changed = true
                HpackDecoder.HeaderField(header.name, updated)
            } else {
                header
            }
        }.toMutableList()
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
        val headerText = text.substring(0, headerEnd)
        val headerLines = headerText.split("\r\n")
        val statusLine = headerLines.firstOrNull() ?: return FilterResult.PassThrough(chunk, "missing-status-line")
        val contentType = headerLines.firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val contentEncoding = headerLines.firstOrNull { it.startsWith("Content-Encoding:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val transferEncoding = headerLines.firstOrNull { it.startsWith("Transfer-Encoding:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val location = headerLines.firstOrNull { it.startsWith("Location:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val setCookie = headerLines.firstOrNull { it.startsWith("Set-Cookie:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val directives = requestInspection?.let {
            RuleRepository.getRequestRewriteDirectives(
                context = TlsMitmSessionManager.requireContext(),
                host = it.host,
                path = it.path,
                appName = session.appName,
                requestDomain = extractRequestDomain(it)
            )
        } ?: RuleRepository.RequestRewriteDirectives()
        val cosmeticSelectors = directives.cosmeticSelectors
        reportSuspiciousRedirectDomain(
            host = normalizeAuthority(requestInspection?.host ?: session.host),
            location = location,
            appName = session.appName,
            refererDomain = extractRequestDomain(requestInspection),
            matchedPathHint = requestInspection?.path
        )
        val headerNeutralizeReason = inspectHttp1HeaderSignals(session, requestInspection, location, setCookie)
        if (headerNeutralizeReason != null) {
            val replacementBodyBytes = buildReplacementBody(contentType, "", emptyList())
            val response = buildSyntheticResponse(statusLine, contentType, replacementBodyBytes)
            return FilterResult.Replaced(response, headerNeutralizeReason)
        }
        val bodyInspectionReason = shouldInspectHttp1ResponseBody(session, requestInspection, contentType)
        if (bodyInspectionReason == null) {
            return FilterResult.PassThrough(chunk, "response-body-skip:no-deep-inspection-target")
        }
        val bodyBytes = chunk.copyOfRange(headerEnd + 4, chunk.size)
        val decodedTransferBytes = if ("chunked" in transferEncoding) {
            decodeChunkedBody(bodyBytes) ?: return FilterResult.PassThrough(chunk, "invalid-chunked")
        } else {
            bodyBytes
        }
        val decodedBodyBytes = when {
            "br" in contentEncoding -> brotliBody(decodedTransferBytes) ?: return FilterResult.PassThrough(chunk, "brotli-decode-failed")
            "gzip" in contentEncoding -> gunzipBody(decodedTransferBytes) ?: return FilterResult.PassThrough(chunk, "gzip-decode-failed")
            "deflate" in contentEncoding -> inflateDeflateBody(decodedTransferBytes) ?: return FilterResult.PassThrough(chunk, "deflate-decode-failed")
            else -> decodedTransferBytes
        }
        val body = decodeAscii(decodedBodyBytes) ?: return FilterResult.PassThrough(chunk, "binary-response-body")
        val neutralizeReason = inspectHttp1BodySignals(session, requestInspection, contentType, body, cosmeticSelectors)
        val redirectBodyBytes = buildRedirectReplacementBody(contentType, directives.redirectResource)
        if (redirectBodyBytes != null) {
            val response = buildSyntheticResponse(statusLine, inferRedirectContentType(contentType, directives.redirectResource), redirectBodyBytes, directives.cspValue)
            return FilterResult.Replaced(response, "redirect-resource-applied", chunk.size)
        }
        if (neutralizeReason == null) {
            if (contentType.contains("text/html") && (cosmeticSelectors.isNotEmpty() || !directives.cspValue.isNullOrBlank())) {
                val injectedBodyBytes = buildInjectedHtmlBody(body, cosmeticSelectors, directives.cspValue)
                val response = buildSyntheticResponse(statusLine, contentType, injectedBodyBytes, directives.cspValue)
                return FilterResult.Replaced(response, "cosmetic-html-injected", chunk.size)
            }
            return FilterResult.PassThrough(chunk, "response-allowed")
        }
        val replacementBodyBytes = buildReplacementBody(contentType, body, cosmeticSelectors, directives.cspValue)
        val response = buildSyntheticResponse(statusLine, contentType, replacementBodyBytes, directives.cspValue)
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
        val context = TlsMitmSessionManager.requireContext()
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
        val context = TlsMitmSessionManager.requireContext()
        val host = normalizeAuthority(requestInspection?.host ?: session.host)
        if (host.isBlank()) return null
        val lowerPath = requestInspection?.path?.lowercase().orEmpty()
        val requestDomain = extractRequestDomain(requestInspection)
        val isNovelApp = RuleRepository.isNovelAppHint(session.appName)
        val pathInspection = inspectSuspiciousHttpPath(lowerPath)
        fun containsAny(value: String, vararg tokens: String): Boolean = tokens.any(value::contains)
        val destinationPort = when {
            session.targetPort > 0 -> session.targetPort
            else -> 443
        }
        if (RuleRepository.isBlocked(context, host, appName = session.appName, destinationPort = destinationPort)) return "neutralized-blocked-host"
        if (RuleRepository.isUrlBlocked(context, host, lowerPath, session.appName, requestDomain, destinationPort = destinationPort)) return "neutralized-blocked-url"
        if (RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, host, lowerPath, session.appName)) {
            return "neutralized-novel-protected-path"
        }
        val vendor = RuleRepository.classifyVendorFromHints(context, host, session.appName)
        val locationStrongHeader = strongHeaderKeywords.any(location::contains)
        val cookieStrongHeader = strongHeaderKeywords.any(setCookie::contains)
        val locationStrongKeyword = strongResponseAdKeywords.any(location::contains)
        val cookieStrongKeyword = strongResponseAdKeywords.any(setCookie::contains)
        val locationRecommendCardHit = containsAny(location, "recommend_card", "promo_card", "ad_card")
        val locationMaterialHit = containsAny(location, "material_url", "landing_url")
        val locationCommerceCardHit = containsAny(location, "shop_card", "mall_card", "goods_card", "product_card")
        val cookieMaterialHit = containsAny(setCookie, "ad_material", "material_url")
        val headerMaterialHit = locationMaterialHit || cookieMaterialHit
        val strongHeaderOrKeywordHit = locationStrongKeyword || cookieStrongKeyword
        val aggressiveAdApp = RuleRepository.isAggressiveAdAppHint(session.appName)
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(context, host, session.appName, vendor)) {
            return "neutralized-novel-app-aggressive"
        }
        val pangleOrGdtHeaderTarget = pangleAndGdtHostSignals.any { host.contains(it) || lowerPath.contains(it) }
        if (pangleOrGdtHeaderTarget && (headerMaterialHit || strongHeaderOrKeywordHit)) {
            return "neutralized-pangle-gdt-header"
        }
        if (RuleRepository.shouldForcePushRecommendInspection(host, session.appName, vendor) &&
            (locationRecommendCardHit || headerMaterialHit) &&
            (strongHeaderOrKeywordHit || isKnownAdVendor(vendor))) {
            return "neutralized-push-recommend-header"
        }
        if (looksLikeCommentAdPath(lowerPath) &&
            (locationRecommendCardHit || headerMaterialHit || strongHeaderOrKeywordHit)) {
            return "neutralized-comment-ad-header"
        }
        if (looksLikeCommentCommerceAdPath(lowerPath) &&
            (locationRecommendCardHit || locationCommerceCardHit || headerMaterialHit || strongHeaderOrKeywordHit || pangleOrGdtHeaderTarget)) {
            return "neutralized-comment-commerce-ad-header"
        }
        if (pathInspection.strongSuspicious) {
            return "neutralized-strong-suspicious-path"
        }
        if (pathInspection.suspicious) {
            return "neutralized-suspicious-path"
        }
        if (looksLikeDohRequest(host, lowerPath, emptyMap())) {
            return "neutralized-doh-request"
        }
        if (pathInspection.rewardUnlock) {
            return "neutralized-reward-unlock-path"
        }
        // 增强 Header 追踪字段检测
        val headerTrackingHits = adTrackingHeaderFields.count { field ->
            location.contains(field) || setCookie.contains(field)
        }
        if (headerTrackingHits >= 1 && isNovelApp) {
            return "neutralized-header-tracking"
        }
        if (headerTrackingHits >= 2) {
            return "neutralized-header-tracking"
        }
        if (locationStrongHeader) {
            return "neutralized-location-strong-header"
        }
        if (cookieStrongHeader) {
            return "neutralized-setcookie-strong-header"
        }
        if (aggressiveAdApp &&
            (containsAny(location, "sponsor") || locationRecommendCardHit) &&
            (locationStrongKeyword || isKnownAdVendor(vendor))) {
            return "neutralized-aggressive-app-recommend-header"
        }
        // 增强广告 Vendor 检测
        if (isKnownAdVendor(vendor) && (locationStrongKeyword || cookieStrongKeyword)) {
            return "neutralized-header-vendor-signal"
        }
        // 新增：Location/Response Header 中包含广告强特征
        if (locationStrongKeyword) {
            return "neutralized-location-ad-keyword"
        }
        if (cookieStrongKeyword) {
            return "neutralized-setcookie-ad-keyword"
        }
        return null
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
        val context = TlsMitmSessionManager.requireContext()
        val host = normalizeAuthority(requestInspection?.host ?: session.host)
        if (RuleRepository.isSocialCoreDomain(host)) return null
        if (RuleRepository.isWhitelistedDomain(host)) return null
        if (RuleRepository.isSensitiveAuthDomain(host)) return null
        if (RuleRepository.shouldProtectMediaTraffic(host)) return null
        if (RuleRepository.shouldProtectBusinessTraffic(host)) return null
        val vendor = RuleRepository.classifyVendorFromHints(context, host, session.appName)
        val generalAdTarget = RuleRepository.shouldTreatAsGeneralAdTraffic(host, vendor, session.appName)
        val aggressiveNovelTarget = RuleRepository.shouldAggressivelyBlockForNovelApp(context, host, session.appName, vendor)
        val protectedNovelTarget = RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, host, requestInspection?.path, session.appName)
        val isNovelApp = RuleRepository.isNovelAppHint(session.appName)
        val mitmAggressive = isMitmAggressiveMode()
        val htmlContent = contentType.contains("html")
        val scriptOrJsonContent = containsAnyContentType(contentType, "json", "javascript")
        val targetedBodyContent = htmlContent || scriptOrJsonContent
        if (htmlContent && cosmeticSelectors.isNotEmpty()) {
            return "neutralized-cosmetic-rule"
        }
        if (targetedBodyContent) {
            val lowerBody = body.lowercase()
            val bodySignals = inspectAdBodySignals(lowerBody)
            val bodyReasons = bodySignals.reasons.toSet()
            val jsonNovelFieldHits = if (scriptOrJsonContent) {
                jsonNovelFieldTokens.count(lowerBody::contains)
            } else 0
            val htmlNovelMarkerHits = if (htmlContent) {
                htmlNovelMarkerTokens.count(lowerBody::contains)
            } else 0
            // 降低拦截阈值：普通应用 2 分拦截，小说 APP 1 分拦截
            val domesticSdkHits = domesticAdSdkKeywords.count { keyword ->
                lowerBody.contains(keyword) || host.contains(keyword)
            }
            val pangleAndGdtHits = pangleAndGdtBodySignals.count(lowerBody::contains) +
                pangleAndGdtHostSignals.count { signal -> host.contains(signal) || requestInspection?.path?.lowercase()?.contains(signal) == true }
            val threshold = when {
                isNovelApp -> HTTP1_NOVEL_RESPONSE_BLOCK_SCORE
                mitmAggressive && domesticSdkHits >= 1 -> HTTP1_MITM_AGGRESSIVE_RESPONSE_BLOCK_SCORE
                else -> HTTP1_RESPONSE_BLOCK_SCORE
            }
            if (pangleAndGdtHits >= 3 && (bodySignals.score >= 1 || domesticSdkHits >= 1)) {
                return "neutralized-body-pangle-gdt-cluster"
            }
            if (domesticSdkHits >= 2 && bodySignals.score >= 2) {
                return "neutralized-body-domestic-sdk-cluster"
            }
            val rewardUnlockHits = rewardUnlockTokens.count(lowerBody::contains)
            if (rewardUnlockHits >= 2 && (protectedNovelTarget || aggressiveNovelTarget || bodySignals.score >= 1)) {
                return "neutralized-body-reward-unlock"
            }
            if (jsonNovelFieldHits >= 2 && (protectedNovelTarget || aggressiveNovelTarget || isKnownAdVendor(vendor))) {
                return "neutralized-body-json-novel-fields"
            }
            if (htmlNovelMarkerHits >= 2 && (protectedNovelTarget || aggressiveNovelTarget || bodySignals.score >= 1)) {
                return "neutralized-body-html-novel-ad"
            }
            if (bodyReasons.contains("media-field-cluster") && bodySignals.score >= 1) {
                return "neutralized-body-media-field-cluster"
            }
            if (bodyReasons.contains("novel-field-cluster")) {
                return "neutralized-body-novel-field-cluster"
            }
            if (bodyReasons.contains("novel-task-reward") && (protectedNovelTarget || aggressiveNovelTarget)) {
                return "neutralized-body-novel-task-reward"
            }
            if (bodyReasons.contains("novel-coin-reward") && (protectedNovelTarget || aggressiveNovelTarget)) {
                return "neutralized-body-novel-coin-reward"
            }
            val commentAdMaterialHit = listOf(
                "\"ad_material", "\"material_url", "\"landing_url", "\"click_url", "\"show_url", "\"deep_link"
            ).any(lowerBody::contains)
            val commentRecommendCardHit = listOf(
                "\"recommend_card", "\"promotion_card", "\"discover_card", "\"ad_card", "\"promo_card"
            ).any(lowerBody::contains)
            val commentCommerceSignalHit = commentCommerceAdSignals.count(lowerBody::contains)
            val commentCommerceCardHit = listOf(
                "\"shop_card", "\"mall_card", "\"goods_card", "\"product_card", "\"douyin_shop"
            ).any(lowerBody::contains)
            val commentCommerceGdtHit = pangleAndGdtBodySignals.any(lowerBody::contains)
            if ((bodyReasons.contains("comment-ad-extended") || bodyReasons.contains("comment-ad-flow-extended")) &&
                (bodySignals.score >= 2 || commentAdMaterialHit || commentRecommendCardHit)) {
                RuleRepository.reportUnknownVendorIfNeeded(
                    context = context,
                    vendor = vendor,
                    domain = host,
                    appName = session.appName,
                    signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
                    confidenceBoost = 2,
                    matchedPathHint = requestInspection?.path,
                    refererDomain = extractRequestDomain(requestInspection)
                )
                return "neutralized-body-comment-ad"
            }
            if ((bodyReasons.contains("comment-commerce-ad-extended") || bodyReasons.contains("comment-gdt-commerce-ad-extended")) &&
                (commentCommerceSignalHit >= 2 || commentCommerceCardHit || commentCommerceGdtHit || commentAdMaterialHit)) {
                RuleRepository.reportUnknownVendorIfNeeded(
                    context = context,
                    vendor = vendor,
                    domain = host,
                    appName = session.appName,
                    signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
                    confidenceBoost = 2,
                    matchedPathHint = requestInspection?.path,
                    refererDomain = extractRequestDomain(requestInspection)
                )
                return "neutralized-body-comment-commerce-ad"
            }
            if (bodySignals.score >= threshold) {
                RuleRepository.reportUnknownVendorIfNeeded(
                    context = context,
                    vendor = vendor,
                    domain = host,
                    appName = session.appName,
                    signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
                    confidenceBoost = 2,
                    matchedPathHint = requestInspection?.path,
                    refererDomain = extractRequestDomain(requestInspection)
                )
                return "neutralized-body-strong-signal"
            }
            if (bodySignals.score >= 1 && protectedNovelTarget) {
                RuleRepository.reportUnknownVendorIfNeeded(
                    context = context,
                    vendor = vendor,
                    domain = host,
                    appName = session.appName,
                    signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
                    confidenceBoost = 2,
                    matchedPathHint = requestInspection?.path,
                    refererDomain = extractRequestDomain(requestInspection)
                )
                return "neutralized-body-novel-protected"
            }
            if (bodySignals.score >= 1 && aggressiveNovelTarget) {
                RuleRepository.reportUnknownVendorIfNeeded(
                    context = context,
                    vendor = vendor,
                    domain = host,
                    appName = session.appName,
                    signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
                    confidenceBoost = 2,
                    matchedPathHint = requestInspection?.path,
                    refererDomain = extractRequestDomain(requestInspection)
                )
                return "neutralized-body-novel-aggressive"
            }
            if (bodySignals.score >= 2 && isKnownAdVendor(vendor)) {
                RuleRepository.reportUnknownVendorIfNeeded(
                    context = context,
                    vendor = vendor,
                    domain = host,
                    appName = session.appName,
                    signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
                    confidenceBoost = 1,
                    matchedPathHint = requestInspection?.path,
                    refererDomain = extractRequestDomain(requestInspection)
                )
                return "neutralized-body-vendor-signal"
            }
            if (bodySignals.score >= 2 && generalAdTarget) {
                RuleRepository.reportUnknownVendorIfNeeded(
                    context = context,
                    vendor = vendor,
                    domain = host,
                    appName = session.appName,
                    signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
                    confidenceBoost = 1,
                    matchedPathHint = requestInspection?.path,
                    refererDomain = extractRequestDomain(requestInspection)
                )
                return "neutralized-body-general-ad"
            }
        }
        return null
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
        val context = TlsMitmSessionManager.requireContext()
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
        val context = TlsMitmSessionManager.requireContext()
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
            resource.contains("noopjs") || resource.contains("noop.js") || resource.contains("noop-script") -> {
                "(()=>{})();".toByteArray(StandardCharsets.UTF_8)
            }
            resource.contains("1x1") || resource.contains("pixel") || resource.contains("transparent") || resource.contains("noopimage") -> {
                TRANSPARENT_1X1_GIF
            }
            resource.contains("empty") && contentType.contains("html") -> {
                "<html><head></head><body></body></html>".toByteArray(StandardCharsets.UTF_8)
            }
            resource.contains("empty") && contentType.contains("json") -> {
                "{}".toByteArray(StandardCharsets.UTF_8)
            }
            resource.contains("empty") || resource.contains("nooptext") -> {
                ByteArray(0)
            }
            else -> null
        }
    }

    private fun inferRedirectContentType(originalContentType: String, redirectResource: String?): String {
        val resource = redirectResource?.trim()?.lowercase().orEmpty()
        return when {
            resource.contains("noopjs") || resource.contains("noop.js") || resource.contains("noop-script") -> "application/javascript; charset=utf-8"
            resource.contains("1x1") || resource.contains("pixel") || resource.contains("transparent") || resource.contains("noopimage") -> "image/gif"
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
        val strongMatches = mutableListOf<String>()
        for (keyword in bodyStrongMarkers) {
            if (lowerBody.contains(keyword)) strongMatches += keyword
        }
        val weakMatches = mutableListOf<String>()
        for (keyword in bodyWeakMarkers) {
            if (lowerBody.contains(keyword)) weakMatches += keyword
        }
        val reasons = mutableListOf<String>()
        var score = 0
        // 增强强特征评分权重
        if (strongMatches.isNotEmpty()) {
            score += when {
                strongMatches.size >= 3 -> 5
                strongMatches.size == 2 -> 4
                strongMatches.size == 1 -> 3
                else -> 0
            }
            reasons += strongMatches.take(5).map { "data-strong-keyword:$it" }
        }
        // 弱特征也计分
        if (weakMatches.isNotEmpty()) {
            score += when {
                weakMatches.size >= 4 -> 2
                weakMatches.size >= 2 -> 1
                else -> 0
            }
            reasons += weakMatches.take(3).map { "data-weak-keyword:$it" }
        }
        val trackingFieldHits = trackingFieldTokens.filter(lowerBody::contains)
        val generalAdFieldHits = generalAdFieldTokens.filter(lowerBody::contains)
        val novelAdFieldHits = novelAdFieldTokens.filter(lowerBody::contains)
        val mediaAdFieldHits = mediaAdFieldTokens.filter(lowerBody::contains)
        if (trackingFieldHits.isNotEmpty()) {
            score += if (trackingFieldHits.size >= 2) 3 else 2
            reasons += trackingFieldHits.take(4).map { "data-field:$it" }
        }
        if (generalAdFieldHits.isNotEmpty()) {
            score += if (generalAdFieldHits.size >= 2) 3 else 2
            reasons += generalAdFieldHits.take(4).map { "general-ad-field:$it" }
        }
        if (novelAdFieldHits.size >= 2 && (strongMatches.isNotEmpty() || trackingFieldHits.isNotEmpty())) {
            score += 2
            reasons += novelAdFieldHits.take(4).map { "novel-field:$it" }
        }
        if (novelAdFieldHits.size >= 3) {
            score += 2
            reasons += "novel-field-cluster"
        }
        if (mediaAdFieldHits.size >= 3) {
            score += 2
            reasons += mediaAdFieldHits.take(4).map { "media-field:$it" }
            reasons += "media-field-cluster"
        }
        if (weakMatches.size >= 3) {
            score += 2
            reasons += weakMatches.take(4).map { "data-keyword:$it" }
        } else if (weakMatches.size == 2 && strongMatches.isNotEmpty()) {
            score += 1
            reasons += weakMatches.take(2).map { "data-keyword:$it" }
        }
        val taskRewardSceneHit = lowerBody.contains("\"task_")
        val rewardTokenHit = lowerBody.contains("\"reward")
        val coinTokenHit = lowerBody.contains("\"coin")
        val bonusTokenHit = lowerBody.contains("\"bonus")
        val videoTokenHit = lowerBody.contains("\"video")
        val adTokenHit = lowerBody.contains("\"ad")
        val prerollTokenHit = lowerBody.contains("\"preroll")
        val midrollTokenHit = lowerBody.contains("\"midroll")
        if (taskRewardSceneHit && rewardTokenHit) {
            score += 1
            reasons += "novel-task-reward"
        }
        if (coinTokenHit && (bonusTokenHit || rewardTokenHit)) {
            score += 1
            reasons += "novel-coin-reward"
        }
        if (videoTokenHit && (adTokenHit || prerollTokenHit || midrollTokenHit)) {
            score += 1
            reasons += "video-ad-cluster"
        }
        fun containsAny(vararg tokens: String): Boolean = tokens.any(lowerBody::contains)
        val commentSceneHit = containsAny("\"comment", "\"reply", "\"floor")
        val postSceneHit = lowerBody.contains("\"post\"")
        val commentOrPostSceneHit = commentSceneHit || postSceneHit
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
        val clickOrMaterialSceneHit = materialUrlSceneHit || lowerBody.contains("\"download_url\"")
        if (lowerBody.contains("\"comment") && commentAdPlacementHit) {
            score += 1
            reasons += "comment-ad-cluster"
        }
        if (commentSceneHit && commentSceneExtendedHit) {
            score += 2
            reasons += "comment-ad-extended"
        }
        if (commentOrPostSceneHit && commentMaterialSceneHit &&
            (adMaterialHit || deepLinkMaterialHit || recommendCardHit)) {
            score += 3
            reasons += "comment-ad-material-extended"
        }
        if (commentOrPostSceneHit && commentCommerceSceneHit &&
            (recommendCardHit || adMaterialHit || deepLinkMaterialHit)) {
            score += 4
            reasons += "comment-commerce-ad-extended"
        }
        if (commentOrPostSceneHit && commentCommerceSceneHit &&
            (adMaterialHit || deepLinkMaterialHit) &&
            pangleAndGdtBodySignals.any(lowerBody::contains)) {
            score += 4
            reasons += "comment-gdt-commerce-ad-extended"
        }
        if (commentOrPostSceneHit && commentPopupSceneHit &&
            (adMaterialHit || deepLinkMaterialHit)) {
            score += 3
            reasons += "comment-ad-popup-extended"
        }
        if (commentOrPostSceneHit && commentFloatSceneHit) {
            score += 2
            reasons += "comment-ad-float-extended"
        }
        if (commentOrPostSceneHit && commentFlowSceneHit &&
            adMaterialHit) {
            score += 3
            reasons += "comment-ad-flow-extended"
        }
        if (feedSceneHit &&
            containsAny("\"ad_card", "\"insert_ad", "\"feed_ad")) {
            score += 1
            reasons += "feed-ad-cluster"
        }
        if (recommendFeedSceneHit && feedExtendedSceneHit) {
            score += 2
            reasons += "feed-ad-extended"
        }
        if (pushSceneHit && (pushRecommendSceneHit || adMaterialHit)) {
            score += 3
            reasons += "push-recommend-ad-extended"
        }
        if (pushSceneHit && pushMaterialSceneHit &&
            (adMaterialHit || deepLinkMaterialHit || recommendCardHit)) {
            score += 4
            reasons += "push-recommend-material-extended"
        }
        if (directMessageSceneHit &&
            (directMessageAdSceneHit || adMaterialHit) &&
            deepLinkMaterialHit) {
            score += 4
            reasons += "push-message-ad-card-extended"
        }
        if (messageCenterSceneHit &&
            (recommendCardHit || adMaterialHit) &&
            (adMaterialHit || deepLinkMaterialHit)) {
            score += 4
            reasons += "message-center-recommend-ad-extended"
        }
        if (messageCenterSceneHit && messageCenterCardSceneHit &&
            (adMaterialHit || deepLinkMaterialHit || recommendCardHit)) {
            score += 4
            reasons += "message-center-card-ad-extended"
        }
        if (discoverSceneHit && discoverAdSceneHit &&
            (deepLinkMaterialHit || materialUrlSceneHit)) {
            score += 4
            reasons += "discover-recommend-ad-extended"
        }
        if (messageCenterMaterialSceneHit && messageCenterAdSceneHit && clickOrMaterialSceneHit) {
            score += 4
            reasons += "message-center-ad-material-extended"
        }
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
        val readerSceneHit = containsAny("\"reader", "\"chapter", "\"reading", "\"book")
        if (signTaskBenefitSceneHit && signTaskBenefitPlacementHit && materialUrlSceneHit) {
            score += 4
            reasons += "sign-task-benefit-ad-extended"
        }
        if (readerSceneHit && readerSignBenefitPlacementHit && materialUrlSceneHit) {
            score += 4
            reasons += "reader-sign-benefit-ad-extended"
        }
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
        val readerPageSceneHit = readerSceneHit || pageSceneHit
        if (startupSceneHit && startupConfigHit && startupAdMaterialHit) {
            score += 2
            reasons += "startup-ad-extended"
        }
        val startupMaterialPlacementHit = containsAny(
            "\"startup_page_ad\"", "\"launch_screen_ad\"", "\"open_screen_material\"", "\"splash_material\""
        )
        val startupCachePlacementHit = containsAny(
            "\"open_screen_cache\"", "\"startup_cache_material\"", "\"launch_cache_material\"", "\"splash_cache_material\""
        )
        val startupPreloadPlacementHit = containsAny(
            "\"startup_preload_ad\"", "\"launch_preload_ad\"", "\"splash_template_ad\"", "\"open_screen_dispatch\""
        )
        if (startupOpenScreenSceneHit && startupMaterialPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "startup-ad-material-extended"
        }
        if (startupOpenScreenSceneHit && startupCachePlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "startup-ad-cache-extended"
        }
        if (startupOpenScreenSceneHit && startupPreloadPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "startup-ad-preload-extended"
        }
        val qimaoReaderSceneHit = containsAny("\"qimao\"", "\"kmxs\"", "\"wtzw\"") || readerSceneHit
        val qimaoReaderPlacementHit = containsAny(
            "\"chapter_unlock\"", "\"watch_ad_unlock\"", "\"free_read_popup\"", "\"reader_reward_popup\"",
            "\"novel_welfare_center\"", "\"novel_task_center\""
        )
        if (qimaoReaderSceneHit && qimaoReaderPlacementHit && materialUrlSceneHit) {
            score += 4
            reasons += "qimao-reader-ad-extended"
        }
        val bannerSceneHit = containsAny("\"banner", "\"bottom_banner", "\"floating_banner")
        val bannerMaterialPlacementHit = containsAny("\"show_url", "\"click_url", "\"material")
        if (bannerSceneHit && bannerMaterialPlacementHit) {
            score += 1
            reasons += "banner-ad-cluster"
        }
        val readerAdPlacementClusterHit = containsAny("\"bottom_banner", "\"insert_ad", "\"watch_ad_unlock", "\"unlock_by_ad")
        if (readerSceneHit && readerAdPlacementClusterHit) {
            score += 2
            reasons += "reader-ad-cluster"
        }
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
        val sdkAdMaterialPlacementHit = containsAny("\"ad_material\"", "\"placement_id\"")
        val gdtSdkPlacementHit = containsAny(
            "\"bidding_token\"", "\"auction_id\""
        ) || (sdkMediationSceneHit && sdkAdMaterialPlacementHit)
        val aliSdkSceneHit = containsAny("\"alipay\"", "\"alimama\"", "\"tanx\"", "\"adash\"")
        val aliSdkPlacementHit = containsAny(
            "\"ad_strategy\"", "\"template_id\""
        ) || (sdkMediationSceneHit && sdkAdMaterialPlacementHit)
        val shortvideoSdkSceneHit = containsAny("\"pangolin\"", "\"pangle\"", "\"gromore\"", "\"snssdk\"")
        val shortvideoSdkPlacementHit = containsAny(
            "\"preload_ad\"", "\"ad_slot\"", "\"rit\""
        ) || (sdkMediationSceneHit && adMaterialHit)
        val taskBenefitSceneHit = containsAny("\"task\"", "\"benefit\"", "\"welfare\"", "\"coin\"")
        val taskBenefitPlacementHit = containsAny(
            "\"task_center\"", "\"benefit_center\"", "\"welfare_center\"", "\"watch_ad_task\"", "\"daily_reward\"", "\"coin_bonus\""
        )
        val mediationSceneHit = sdkMediationSceneHit || containsAny("\"bidding\"", "\"auction\"")
        val mediationPlacementHit = containsAny(
            "\"placement_id\"", "\"slot_id\"", "\"template_id\"", "\"ad_strategy\"", "\"ad_dispatch\""
        )
        val commentSceneExtendedForInsertHit = commentSceneHit
        val commentInsertPlacementHit = containsAny(
            "\"comment_guide_ad\"", "\"comment_hot_ad\"", "\"comment_float_ad\"", "\"comment_promote_card\"",
            "\"comment_stream_ad\"", "\"reply_promote_card\"", "\"floor_insert_ad\"", "\"comment_promote\""
        )
        val commentMaterialSceneExtendedHit = commentOrPostSceneHit
        val commentMaterialPlacementHit = containsAny(
            "\"comment_promote\"", "\"reply_promote\"", "\"floor_promote\"", "\"comment_material\"",
            "\"reply_material\"", "\"floor_material\"", "\"comment_landing_url\"", "\"reply_landing_url\"", "\"post_landing_url\""
        )
        val commentPopupPlacementExtendedHit = containsAny(
            "\"comment_popup_ad\"", "\"comment_bottom_ad\"", "\"reply_bottom_ad\"", "\"floor_bottom_ad\""
        )
        if (readerPageSceneHit && readerPageAdPlacementHit) {
            score += 2
            reasons += "reader-page-ad-extended"
        }
        if (readerPageSceneHit && readerPageMaterialPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "reader-page-ad-material-extended"
        }
        if (readerPageSceneHit && readerPagePopupPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "reader-page-popup-extended"
        }
        if (readerPageSceneHit && readerPageTailPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "reader-page-tail-extended"
        }
        if (coolapkCommentSceneHit && coolapkCommentPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "coolapk-comment-ad-extended"
        }
        if (gdtSdkSceneHit && gdtSdkPlacementHit && materialUrlSceneHit) {
            score += 4
            reasons += "gdt-sdk-ad-extended"
        }
        if (aliSdkSceneHit && aliSdkPlacementHit && materialUrlSceneHit) {
            score += 4
            reasons += "ali-sdk-ad-extended"
        }
        if (shortvideoSdkSceneHit && shortvideoSdkPlacementHit && materialUrlSceneHit) {
            score += 4
            reasons += "shortvideo-sdk-ad-extended"
        }
        val rewardPopupHit = lowerBody.contains("\"reward_popup")
        val watchAdUnlockHit = lowerBody.contains("\"watch_ad_unlock")
        val unlockByAdHit = lowerBody.contains("\"unlock_by_ad")
        val chapterUnlockAdHit = lowerBody.contains("\"chapter_unlock_ad")
        val dramaSceneHit = containsAny("\"drama", "\"episode", "\"short_video", "\"short_drama")
        val dramaPlacementHit = rewardPopupHit || containsAny("\"patch_ad", "\"insert_ad", "\"ad_material")
        val liveSceneHit = containsAny("\"live", "\"stream", "\"anchor")
        val livePlacementHit = containsAny("\"live_ad", "\"floating_banner", "\"show_url", "\"material")
        val comicSceneHit = containsAny("\"comic", "\"manga", "\"chapter")
        val comicPlacementHit = unlockByAdHit || chapterUnlockAdHit || rewardPopupHit
        val sharedUrlPlacementHit = containsAny("show_url", "click_url", "material", "landing")
        val playerSceneHit = containsAny("pause-ad", "player-ad", "reward-pop", "offerwall")
        val playerPlacementHit = sharedUrlPlacementHit
        val playerExtendedSceneHit = containsAny(
            "\"pause_ad\"", "\"player_ad\"", "\"preroll_ad\"", "\"midroll_ad\"", "\"postroll_ad\""
        )
        val splashSceneHit = containsAny("splash-ad", "open-screen", "startup-banner", "launch-ad")
        val splashPlacementHit = lowerBody.contains("ad_material") || sharedUrlPlacementHit || adDispatchHit
        if (dramaSceneHit && dramaPlacementHit) {
            score += 2
            reasons += "drama-ad-cluster"
        }
        if (liveSceneHit && livePlacementHit) {
            score += 2
            reasons += "live-ad-cluster"
        }
        if (comicSceneHit && comicPlacementHit) {
            score += 2
            reasons += "comic-ad-cluster"
        }
        val rewardSceneHit = containsAny("\"reward", "\"unlock", "\"bonus", "\"task")
        val rewardPlacementHit = rewardPopupHit || watchAdUnlockHit || unlockByAdHit || chapterUnlockAdHit ||
            containsAny("\"free_read_card", "\"task_reward")
        val rewardComicSceneHit = lowerBody.contains("\"comic")
        if (rewardSceneHit && rewardPlacementHit && (adMaterialHit || readerSceneHit || rewardComicSceneHit)) {
            score += 2
            reasons += "reward-ad-extended"
        }
        if (taskBenefitSceneHit && taskBenefitPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "task-benefit-ad-extended"
        }
        if (mediationSceneHit && mediationPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "mediation-ad-extended"
        }
        if (commentSceneExtendedForInsertHit && commentInsertPlacementHit) {
            score += 2
            reasons += "comment-ad-insert-extended"
        }
        if (commentMaterialSceneExtendedHit && commentMaterialPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "comment-ad-material-extended"
        }
        if (commentMaterialSceneExtendedHit && commentPopupPlacementExtendedHit && materialUrlSceneHit) {
            score += 3
            reasons += "comment-ad-popup-extended"
        }
        if (playerSceneHit && playerPlacementHit) {
            score += 2
            reasons += "player-ad-cluster"
        }
        if (playerExtendedSceneHit && materialUrlSceneHit) {
            score += 2
            reasons += "player-ad-extended"
        }
        if (splashSceneHit && splashPlacementHit) {
            score += 2
            reasons += "splash-ad-cluster"
        }
        val readerMaterialPlacementHit = containsAny(
            "\"reader_reward_popup\"", "\"chapter_offerwall\"", "\"free_read_popup\"", "\"reader_float_ad\"",
            "\"chapter_card_ad\""
        ) || qimaoReaderPlacementHit
        if (readerSceneHit && readerMaterialPlacementHit && materialUrlSceneHit) {
            score += 3
            reasons += "reader-ad-material-extended"
        }
        val htmlMarkerHits = htmlAdMarkers.filter { marker -> lowerBody.contains(marker) }
        if (htmlMarkerHits.isNotEmpty()) {
            score += if (htmlMarkerHits.size >= 2) 2 else 1
            reasons += htmlMarkerHits.take(4).map { "html-marker:$it" }
        }
        return cacheBodySignalInspection(cacheKey, BodySignalInspection(score, reasons.distinct()))
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
        val normalized = LinkedHashMap<String, MutableList<String>>()
        headers.forEach { header ->
            normalized.getOrPut(header.name.lowercase()) { mutableListOf() }.add(header.value)
        }
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
        val lowerPath = path?.lowercase().orEmpty()
        val lowerReferer = referer?.lowercase().orEmpty()
        val lowerContentType = contentType?.lowercase().orEmpty()
        val lowerLocation = location?.lowercase().orEmpty()
        val lowerSetCookie = setCookie?.lowercase().orEmpty()
        val lowerUserAgent = userAgent?.lowercase().orEmpty()
        val lowerAccept = normalized["accept"]?.firstOrNull()?.lowercase().orEmpty()
        val pathInspection = inspectSuspiciousHttpPath(lowerPath)
        val context = TlsMitmSessionManager.requireContext()
        val requestDomain = extractRequestDomain(referer)
        val directives = RuleRepository.getRequestRewriteDirectives(
            context = context,
            host = lowerAuthority,
            path = lowerPath,
            appName = session.appName,
            requestDomain = requestDomain
        )
        fun containsAny(value: String, vararg tokens: String): Boolean = tokens.any(value::contains)
        reportSuspiciousRedirectDomain(
            host = lowerAuthority,
            location = location,
            appName = session.appName,
            refererDomain = requestDomain,
            matchedPathHint = path
        )
        val destinationPort = if (lowerAuthority.endsWith(":443")) 443 else 80
        val blockedHost = RuleRepository.isBlocked(context, lowerAuthority, appName = session.appName, destinationPort = destinationPort)
        val blockedUrl = RuleRepository.isUrlBlocked(context, lowerAuthority, lowerPath, session.appName, requestDomain, destinationPort = destinationPort)
        // 白名单域名允许普通流量直通，但显式命中的拦截规则仍然优先执行
        if (!blockedHost && !blockedUrl && RuleRepository.isWhitelistedDomain(lowerAuthority)) return null
        if (!blockedHost && !blockedUrl && RuleRepository.shouldProtectMediaTraffic(lowerAuthority)) return null
        if (!blockedHost && !blockedUrl && RuleRepository.shouldProtectBusinessTraffic(lowerAuthority)) return null
        var suspiciousScore = 0
        val reasons = mutableListOf<String>()
        if (blockedHost) {
            suspiciousScore += 3
            reasons += "blocked-host"
        }
        if (blockedUrl) {
            suspiciousScore += 3
            reasons += "blocked-url"
        }
        val vendor = RuleRepository.classifyVendorFromHints(context, lowerAuthority, session.appName)
        val isNovelApp = RuleRepository.isNovelAppHint(session.appName)
        val aggressiveAdApp = RuleRepository.isAggressiveAdAppHint(session.appName)
        val mitmAggressive = isMitmAggressiveMode()
        val pathMaterialHit = containsAny(lowerPath, "material", "landing", "show_url", "click_url")
        val locationRecommendCardHit = containsAny(lowerLocation, "recommend_card", "promo_card", "ad_card")
        val locationMaterialHit = containsAny(lowerLocation, "material_url", "landing_url")
        val cookieMaterialHit = containsAny(lowerSetCookie, "ad_material", "material_url")
        val headerMaterialHit = locationMaterialHit || cookieMaterialHit
        val domesticSdkHits = domesticAdSdkKeywords.count { keyword ->
            lowerAuthority.contains(keyword) || lowerPath.contains(keyword) || lowerReferer.contains(keyword) || lowerUserAgent.contains(keyword)
        }
        val pangleAndGdtHits = pangleAndGdtHostSignals.count { signal ->
            lowerAuthority.contains(signal) || lowerPath.contains(signal) || lowerReferer.contains(signal) || lowerLocation.contains(signal)
        }
        if (RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, lowerAuthority, lowerPath, session.appName)) {
            suspiciousScore += 4
            reasons += "novel-protected-path"
        }
        if (pathInspection.strongSuspicious) {
            suspiciousScore += if (isNovelApp) 4 else 3
            reasons += "path-strong-suspicious"
        }
        if (looksLikeCommentAdPath(lowerPath)) {
            suspiciousScore += 3
            reasons += "comment-ad-path"
        }
        if (looksLikeCommentCommerceAdPath(lowerPath)) {
            suspiciousScore += 4
            reasons += "comment-commerce-path"
        }
        if (pathInspection.rewardUnlock) {
            suspiciousScore += if (isNovelApp) 4 else 2
            reasons += "reward-unlock-path"
        }
        if (looksLikeDohRequest(
                lowerAuthority,
                lowerPath,
                mapOf(
                    "content-type" to lowerContentType,
                    "accept" to lowerAccept,
                    "referer" to lowerReferer,
                    "user-agent" to lowerUserAgent
                )
            )) {
            suspiciousScore += 4
            reasons += "doh-request"
        }
        if (RuleRepository.shouldTreatAsGeneralAdTraffic(lowerAuthority, vendor, session.appName)) {
            suspiciousScore += if (isNovelApp) 4 else 3
            reasons += "general-ad-traffic"
        }
        if (RuleRepository.shouldForcePushRecommendInspection(lowerAuthority, session.appName, vendor)) {
            suspiciousScore += if (aggressiveAdApp) 5 else 4
            reasons += "push-recommend-force-inspection"
        }
        if (locationRecommendCardHit || headerMaterialHit || pathMaterialHit) {
            suspiciousScore += 1
            reasons += "header-material-path-signal"
        }
        if (isKnownAdVendor(vendor)) {
            suspiciousScore += 2
            reasons += "vendor:$vendor"
        }
        if (domesticSdkHits > 0) {
            suspiciousScore += if (domesticSdkHits >= 2) 3 else 2
            reasons += "domestic-sdk-signal"
        }
        if (pangleAndGdtHits > 0 && (headerMaterialHit || pathMaterialHit || pathInspection.suspicious)) {
            suspiciousScore += if (pangleAndGdtHits >= 2) 4 else 3
            reasons += "pangle-gdt-signal"
        }
        if (looksLikeCommentCommerceAdPath(lowerPath) &&
            (locationRecommendCardHit || headerMaterialHit || pathMaterialHit || pangleAndGdtHits > 0)) {
            suspiciousScore += 4
            reasons += "comment-commerce-ad-extended"
        }
        if (looksLikeCommentCommerceAdPath(lowerPath) && pangleAndGdtHits > 0 &&
            (headerMaterialHit || pathMaterialHit || lowerLocation.contains("shop_card") || lowerLocation.contains("goods_card"))) {
            suspiciousScore += 4
            reasons += "comment-gdt-commerce-ad-extended"
        }
        // 小说 APP 激进拦截 - 增加权重
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(context, lowerAuthority, session.appName, vendor)) {
            suspiciousScore += if (isNovelApp) 4 else 3
            reasons += "novel-app-aggressive"
        }
        val refererKeywordHit = suspiciousHeaderKeywords.any(lowerReferer::contains)
        val locationKeywordHit = suspiciousHeaderKeywords.any(lowerLocation::contains)
        val setCookieKeywordHit = suspiciousHeaderKeywords.any(lowerSetCookie::contains)
        val locationStrongHeaderHit = strongHeaderKeywords.any(lowerLocation::contains)
        val setCookieStrongHeaderHit = strongHeaderKeywords.any(lowerSetCookie::contains)
        val pathStrongKeywordHit = strongResponseAdKeywords.any(lowerPath::contains)
        val locationStrongKeywordHit = strongResponseAdKeywords.any(lowerLocation::contains)
        val setCookieStrongKeywordHit = strongResponseAdKeywords.any(lowerSetCookie::contains)
        val contentTypeStrongKeywordHit = strongResponseAdKeywords.any(lowerContentType::contains)
        val contentTypeWeakKeywordHit = responseAdKeywords.any(lowerContentType::contains)
        if (pathInspection.suspicious) {
            suspiciousScore += if (isNovelApp) 3 else 2
            reasons += "path-keyword"
        }
        if (refererKeywordHit) {
            suspiciousScore += if (isNovelApp) 2 else 1
            reasons += "referer-keyword"
        }
        if (locationKeywordHit) {
            suspiciousScore += if (isNovelApp) 2 else 1
            reasons += "location-keyword"
        }
        if (setCookieKeywordHit) {
            suspiciousScore += if (isNovelApp) 2 else 1
            reasons += "set-cookie-keyword"
        }
        if (locationStrongHeaderHit) {
            suspiciousScore += 3
            reasons += "location-strong-header"
        }
        if (setCookieStrongHeaderHit) {
            suspiciousScore += 3
            reasons += "set-cookie-strong-header"
        }
        if (pathStrongKeywordHit) {
            suspiciousScore += 3
            reasons += "path-strong-keyword"
        }
        if (locationStrongKeywordHit) {
            suspiciousScore += 3
            reasons += "location-strong-keyword"
        }
        if (setCookieStrongKeywordHit) {
            suspiciousScore += 3
            reasons += "set-cookie-strong-keyword"
        }
        // 增强追踪字段检测
        val headerTrackingHits = adTrackingHeaderFields.count { field ->
            lowerLocation.contains(field) || lowerSetCookie.contains(field)
        }
        if (headerTrackingHits > 0) {
            suspiciousScore += if (headerTrackingHits >= 2) 4 else (if (isNovelApp) 3 else 2)
            reasons += "header-tracking"
        }
        if (contentTypeStrongKeywordHit) {
            suspiciousScore += 1
            reasons += "content-type-keyword"
        }
        if (contentTypeWeakKeywordHit) {
            suspiciousScore += 1
            reasons += "content-type-weak-keyword"
        }
        return Http2HeaderInspection(
            method = method,
            authority = lowerAuthority,
            appName = session.appName,
            path = path,
            scheme = scheme,
            status = status,
            contentType = contentType,
            referer = referer,
            userAgent = userAgent,
            location = location,
            setCookie = setCookie,
            vendor = vendor,
            suspiciousScore = suspiciousScore,
            suspiciousReasons = reasons,
            redirectResource = directives.redirectResource,
            cspValue = directives.cspValue,
            requestLike = method != null && status == null,
            responseLike = status != null
        )
    }

    fun decideHttp2Action(inspection: Http2HeaderInspection): Http2ActionDecision {
        val context = TlsMitmSessionManager.requireContext()
        val isNovelApp = RuleRepository.isNovelAppHint(inspection.appName)
        val threshold = when {
            isNovelApp -> HTTP2_NOVEL_RESPONSE_BLOCK_SCORE
            isMitmAggressiveMode() && inspection.suspiciousReasons.any { it == "domestic-sdk-signal" } -> HTTP2_MITM_AGGRESSIVE_RESPONSE_BLOCK_SCORE
            else -> HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE
        }
        
        if (inspection.suspiciousScore < threshold) {
            return Http2ActionDecision(
                action = "allow",
                confidence = "high",
                shouldBlockCandidate = false,
                shouldSyntheticRespond = false,
                redirectResource = inspection.redirectResource,
                cspValue = inspection.cspValue,
                contentType = inspection.contentType
            )
        }
        val shouldBlock = shouldBlockHttp2ResponseFromHeaders(inspection, isNovelApp)
        return Http2ActionDecision(
            action = when {
                shouldBlock && !inspection.redirectResource.isNullOrBlank() -> "response-header-redirect"
                shouldBlock -> "block"
                else -> "monitor"
            },
            confidence = if (inspection.suspiciousScore >= 4) "high" else "medium",
            shouldBlockCandidate = shouldBlock,
            shouldSyntheticRespond = shouldBlock && inspection.responseLike,
            redirectResource = inspection.redirectResource,
            cspValue = inspection.cspValue,
            contentType = inspection.contentType
        )
    }

    private fun shouldBlockHttp2ResponseFromHeaders(inspection: Http2HeaderInspection, isNovelApp: Boolean = false): Boolean {
        val threshold = if (isNovelApp) HTTP2_NOVEL_RESPONSE_BLOCK_SCORE else HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE
        if (inspection.suspiciousScore < threshold) return false
        // 小说 APP 降低拦截门槛
        if (isNovelApp && inspection.suspiciousScore >= 2) return true
        if (inspection.suspiciousScore >= 4) return true
        val reasons = inspection.suspiciousReasons.toSet()
        if (reasons.any { reason ->
                reason in http2ImmediateBlockReasons ||
                    reason.startsWith("header-field:")
            }
        ) {
            return true
        }
        return inspection.suspiciousScore >= 3 &&
            reasons.any(http2KeywordBlockReasons::contains) &&
            reasons.any { it.startsWith("vendor:") }
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
        val context = TlsMitmSessionManager.requireContext()
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
}
