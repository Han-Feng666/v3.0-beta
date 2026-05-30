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
        val requestLine = rewriteRequestLine(rewrittenHeaders.first(), removeParams)
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
        if (removeParams.isEmpty() && directives.cspValue == null) return base
        var changed = base.changed
        val rewritten = base.headers.map { header ->
            if (header.name == ":path") {
                val updated = rewritePathOnly(header.value, removeParams)
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
        val cosmeticSelectors = requestInspection?.let {
            RuleRepository.getCosmeticSelectors(
                context = TlsMitmSessionManager.requireContext(),
                host = it.host,
                path = it.path,
                appName = session.appName,
                requestDomain = extractRequestDomain(it)
            )
        }.orEmpty()
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
        if (neutralizeReason == null) {
            if (contentType.contains("text/html") && cosmeticSelectors.isNotEmpty()) {
                val injectedBodyBytes = buildInjectedHtmlBody(body, cosmeticSelectors)
                val response = buildSyntheticResponse(statusLine, contentType, injectedBodyBytes)
                return FilterResult.Replaced(response, "cosmetic-html-injected", chunk.size)
            }
            return FilterResult.PassThrough(chunk, "response-allowed")
        }
        val replacementBodyBytes = buildReplacementBody(contentType, body, cosmeticSelectors)
        val response = buildSyntheticResponse(statusLine, contentType, replacementBodyBytes)
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
        val combinedSample = appendSample(currentSample, incomingFragment, MAX_HTTP2_DATA_SAMPLE_BYTES)
        val contentType = headerInspection.contentType?.lowercase().orEmpty()
        val targetedContentType = contentType.contains("json") ||
            contentType.contains("javascript") ||
            contentType.contains("html") ||
            contentType.contains("text")
        if (contentType.isNotBlank() && !targetedContentType) {
            return null
        }
        val decoded = decodeAscii(combinedSample) ?: return null
        val lowerBody = decoded.lowercase()
        val context = TlsMitmSessionManager.requireContext()
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
            combinedSample = combinedSample
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
        val destinationPort = when {
            session.targetPort > 0 -> session.targetPort
            session.isHttps -> 443
            else -> 80
        }
        if (RuleRepository.isBlocked(context, host, appName = session.appName, destinationPort = destinationPort)) return "neutralized-blocked-host"
        if (RuleRepository.isUrlBlocked(context, host, lowerPath, session.appName, requestDomain, destinationPort = destinationPort)) return "neutralized-blocked-url"
        if (RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, host, lowerPath, session.appName)) {
            return "neutralized-novel-protected-path"
        }
        val vendor = RuleRepository.classifyVendorFromHints(context, host, session.appName)
        val locationStrongHeader = strongHeaderKeywords.any { location.contains(it) }
        val cookieStrongHeader = strongHeaderKeywords.any { setCookie.contains(it) }
        val locationStrongKeyword = strongResponseAdKeywords.any { location.contains(it) }
        val cookieStrongKeyword = strongResponseAdKeywords.any { setCookie.contains(it) }
        val aggressiveAdApp = RuleRepository.isAggressiveAdAppHint(session.appName)
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(context, host, session.appName, vendor)) {
            return "neutralized-novel-app-aggressive"
        }
        if (RuleRepository.shouldForcePushRecommendInspection(host, session.appName, vendor) &&
            (location.contains("recommend_card") ||
                location.contains("promo_card") ||
                location.contains("ad_card") ||
                location.contains("material_url") ||
                location.contains("landing_url") ||
                setCookie.contains("ad_material") ||
                setCookie.contains("material_url")) &&
            (locationStrongKeyword || cookieStrongKeyword || isKnownAdVendor(vendor))) {
            return "neutralized-push-recommend-header"
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
            (location.contains("recommend_card") || location.contains("promo_card") || location.contains("sponsor")) &&
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
        val targetedContentType = contentType.contains("text/html") ||
            contentType.contains("json") ||
            contentType.contains("javascript")
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
        if (RuleRepository.isCommunityAppHint(session.appName)) return null
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
        if (contentType.contains("html") && cosmeticSelectors.isNotEmpty()) {
            return "neutralized-cosmetic-rule"
        }
        if (contentType.contains("text/html") || contentType.contains("json") || contentType.contains("javascript")) {
            val lowerBody = body.lowercase()
            val bodySignals = inspectAdBodySignals(lowerBody)
            val bodyReasons = bodySignals.reasons.toSet()
            val jsonNovelFieldHits = if (contentType.contains("json") || contentType.contains("javascript")) {
                jsonNovelFieldTokens.count(lowerBody::contains)
            } else 0
            val htmlNovelMarkerHits = if (contentType.contains("html")) {
                htmlNovelMarkerTokens.count(lowerBody::contains)
            } else 0
            // 降低拦截阈值：普通应用 2 分拦截，小说 APP 1 分拦截
            val domesticSdkHits = domesticAdSdkKeywords.count { keyword ->
                lowerBody.contains(keyword) || host.contains(keyword)
            }
            val threshold = when {
                isNovelApp -> HTTP1_NOVEL_RESPONSE_BLOCK_SCORE
                mitmAggressive && domesticSdkHits >= 1 -> HTTP1_MITM_AGGRESSIVE_RESPONSE_BLOCK_SCORE
                else -> HTTP1_RESPONSE_BLOCK_SCORE
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

    private fun buildInjectedHtmlBody(originalBody: String, cosmeticSelectors: List<String>): ByteArray {
        val styleTag = buildCosmeticStyleTag(cosmeticSelectors)
        val injected = when {
            originalBody.contains("</head>", ignoreCase = true) -> {
                originalBody.replaceFirst("</head>", "$styleTag$SCRIPTLET_INJECTION</head>", ignoreCase = true)
            }
            originalBody.contains("<body", ignoreCase = true) -> {
                "$styleTag$SCRIPTLET_INJECTION$originalBody"
            }
            else -> {
                "<html><head>$styleTag$SCRIPTLET_INJECTION</head><body>$originalBody</body></html>"
            }
        }
        return injected.toByteArray(StandardCharsets.UTF_8)
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

    private fun buildSyntheticResponse(statusLine: String, contentType: String, bodyBytes: ByteArray): ByteArray {
        val actualStatusLine = if (statusLine.startsWith("HTTP/1.")) {
            "${statusLine.substringBefore(' ')} 200 OK"
        } else {
            "HTTP/1.1 200 OK"
        }
        val contentTypeValue = if (contentType.isBlank()) "text/plain; charset=utf-8" else contentType
        val headerBytes = buildString {
            append(actualStatusLine).append("\r\n")
            append("Connection: close\r\n")
            append("Content-Type: ").append(contentTypeValue).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
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
        val lowerPath = path?.lowercase().orEmpty()
        val cacheKey = "$normalizedHost|$lowerPath|${appName.orEmpty()}|${vendorHint.orEmpty()}|${requestDomain.orEmpty()}"
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
        // 游戏和社交 APP 核心服务跳过深度检查（提升性能，降低延迟）
        val lowerHost = normalizedHost.lowercase()
        if (RuleRepository.isGameCoreDomain(lowerHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.isSocialCoreDomain(lowerHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.isCommunityAppHint(appName)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.isWhitelistedDomain(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.shouldProtectMediaTraffic(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.shouldProtectBusinessTraffic(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.isBypassProtectionDomain(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, true)
        val vendor = vendorHint?.takeIf { it.isNotBlank() }
            ?: RuleRepository.classifyVendorFromHints(context, normalizedHost, appName)
        if (RuleRepository.shouldForcePushRecommendInspection(normalizedHost, appName, vendor) &&
            (lowerPath.contains("material") ||
                lowerPath.contains("landing") ||
                lowerPath.contains("ad_card") ||
                lowerPath.contains("promo_card") ||
                lowerPath.contains("recommend_card") ||
                lowerPath.contains("show_url") ||
                lowerPath.contains("click_url"))) {
            return cacheDeepInspectionDecision(cacheKey, true)
        }
        if (lowerPath.contains("message") ||
            lowerPath.contains("notice") ||
            lowerPath.contains("inbox") ||
            lowerPath.contains("notify") ||
            lowerPath.contains("bulletin") ||
            lowerPath.contains("discover") ||
            lowerPath.contains("guess_like") ||
            lowerPath.contains("sign") ||
            lowerPath.contains("benefit") ||
            lowerPath.contains("welfare") ||
            lowerPath.contains("mission")) {
            if (lowerPath.contains("ad") ||
                lowerPath.contains("promo") ||
                lowerPath.contains("recommend") ||
                lowerPath.contains("material") ||
                lowerPath.contains("landing") ||
                lowerPath.contains("popup") ||
                lowerPath.contains("task") ||
                lowerPath.contains("reward")) {
                return cacheDeepInspectionDecision(cacheKey, true)
            }
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
        if (lowerPath.isBlank()) return cacheDeepInspectionDecision(cacheKey, false)
        if (adInfraRequestSignals.any { lowerPath.contains(it) }) return cacheDeepInspectionDecision(cacheKey, true)
        if (lowerPath.contains("?") && (lowerPath.contains("ad") || lowerPath.contains("promo") || lowerPath.contains("reward") || lowerPath.contains("banner"))) {
            return cacheDeepInspectionDecision(cacheKey, true)
        }
        return cacheDeepInspectionDecision(
            cacheKey,
            lowerPath.contains("/api/") &&
                (lowerPath.contains("feed") || lowerPath.contains("splash") || lowerPath.contains("popup") || lowerPath.contains("insert")) &&
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

    private fun buildReplacementBody(contentType: String, originalBody: String, cosmeticSelectors: List<String>): ByteArray {
        return when {
            contentType.contains("application/json") -> "{}".toByteArray(StandardCharsets.UTF_8)
            contentType.contains("javascript") -> "".toByteArray(StandardCharsets.UTF_8)
            contentType.contains("text/html") -> {
                if (cosmeticSelectors.isEmpty()) {
                    "<html><head>$SCRIPTLET_INJECTION</head><body></body></html>".toByteArray(StandardCharsets.UTF_8)
                } else {
                    buildInjectedHtmlBody(originalBody, cosmeticSelectors)
                }
            }
            contentType.contains("image") -> TRANSPARENT_1X1_GIF
            else -> "".toByteArray(StandardCharsets.UTF_8)
        }
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
        if (lowerBody.contains("\"task_") && lowerBody.contains("\"reward")) {
            score += 1
            reasons += "novel-task-reward"
        }
        if (lowerBody.contains("\"coin") && (lowerBody.contains("\"bonus") || lowerBody.contains("\"reward"))) {
            score += 1
            reasons += "novel-coin-reward"
        }
        if (lowerBody.contains("\"video") && (lowerBody.contains("\"ad") || lowerBody.contains("\"preroll") || lowerBody.contains("\"midroll"))) {
            score += 1
            reasons += "video-ad-cluster"
        }
        if (lowerBody.contains("\"comment") && (lowerBody.contains("\"ad_card") || lowerBody.contains("\"reply_ad") || lowerBody.contains("\"floor_ad"))) {
            score += 1
            reasons += "comment-ad-cluster"
        }
        if ((lowerBody.contains("\"comment") || lowerBody.contains("\"reply") || lowerBody.contains("\"floor")) &&
            (lowerBody.contains("\"comment_banner") ||
                lowerBody.contains("\"comment_ad_card") ||
                lowerBody.contains("\"comment_insert_ad") ||
                lowerBody.contains("\"reply_banner") ||
                lowerBody.contains("\"reply_ad_card") ||
                lowerBody.contains("\"floor_banner") ||
                lowerBody.contains("\"floor_promote") ||
                lowerBody.contains("\"comment_sponsor") ||
                lowerBody.contains("\"reply_sponsor"))) {
            score += 2
            reasons += "comment-ad-extended"
        }
        if ((lowerBody.contains("\"comment") || lowerBody.contains("\"reply") || lowerBody.contains("\"floor") || lowerBody.contains("\"post\"")) &&
            (lowerBody.contains("\"comment_float_layer\"") ||
                lowerBody.contains("\"comment_float_card\"") ||
                lowerBody.contains("\"reply_float_card\"") ||
                lowerBody.contains("\"floor_float_card\"") ||
                lowerBody.contains("\"comment_overlay_ad\"") ||
                lowerBody.contains("\"reply_overlay_ad\""))) {
            score += 2
            reasons += "comment-ad-float-extended"
        }
        if ((lowerBody.contains("\"comment") || lowerBody.contains("\"reply") || lowerBody.contains("\"floor") || lowerBody.contains("\"post\"")) &&
            (lowerBody.contains("\"comment_feed_ad\"") ||
                lowerBody.contains("\"comment_flow_ad\"") ||
                lowerBody.contains("\"reply_flow_ad\"") ||
                lowerBody.contains("\"floor_flow_ad\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "comment-ad-flow-extended"
        }
        if ((lowerBody.contains("\"feed") || lowerBody.contains("\"stream") || lowerBody.contains("\"timeline")) &&
            (lowerBody.contains("\"ad_card") || lowerBody.contains("\"insert_ad") || lowerBody.contains("\"feed_ad"))) {
            score += 1
            reasons += "feed-ad-cluster"
        }
        if ((lowerBody.contains("\"feed") || lowerBody.contains("\"stream") || lowerBody.contains("\"timeline") || lowerBody.contains("\"recommend")) &&
            (lowerBody.contains("\"stream_card_ad") ||
                lowerBody.contains("\"timeline_ad") ||
                lowerBody.contains("\"timeline_insert_ad") ||
                lowerBody.contains("\"recommend_ad") ||
                lowerBody.contains("\"recommend_card_ad") ||
                lowerBody.contains("\"feed_banner") ||
                lowerBody.contains("\"feed_card") ||
                lowerBody.contains("\"feed_insert_ad") ||
                lowerBody.contains("\"information_flow_ad") ||
                lowerBody.contains("\"stream_insert_ad") ||
                lowerBody.contains("\"information_flow"))) {
            score += 2
            reasons += "feed-ad-extended"
        }
        if ((lowerBody.contains("\"push") ||
                lowerBody.contains("\"notification") ||
                lowerBody.contains("\"notify") ||
                lowerBody.contains("\"message") ||
                lowerBody.contains("\"inbox")) &&
            (lowerBody.contains("\"recommend_ad") ||
                lowerBody.contains("\"recommend_card_ad") ||
                lowerBody.contains("\"promotion") ||
                lowerBody.contains("\"promo") ||
                lowerBody.contains("\"ad_material") ||
                lowerBody.contains("\"material_url") ||
                lowerBody.contains("\"landing_url") ||
                lowerBody.contains("\"click_url"))) {
            score += 3
            reasons += "push-recommend-ad-extended"
        }
        if ((lowerBody.contains("\"push_message") ||
                lowerBody.contains("\"notification_message") ||
                lowerBody.contains("\"system_message") ||
                lowerBody.contains("\"operation_message")) &&
            (lowerBody.contains("\"ad_card") ||
                lowerBody.contains("\"promo_card") ||
                lowerBody.contains("\"recommend_card") ||
                lowerBody.contains("\"show_url") ||
                lowerBody.contains("\"click_url") ||
                lowerBody.contains("\"material_url")) &&
            (lowerBody.contains("\"ad_material" ) ||
                lowerBody.contains("\"landing_url") ||
                lowerBody.contains("\"deep_link") ||
                lowerBody.contains("\"download_url"))) {
            score += 4
            reasons += "push-message-ad-card-extended"
        }
        if ((lowerBody.contains("\"message_center") ||
                lowerBody.contains("\"inbox_list") ||
                lowerBody.contains("\"notify_list") ||
                lowerBody.contains("\"bulletin_list")) &&
            (lowerBody.contains("\"recommend_card") ||
                lowerBody.contains("\"promotion_card") ||
                lowerBody.contains("\"discover_card") ||
                lowerBody.contains("\"ad_card") ||
                lowerBody.contains("\"material_url") ||
                lowerBody.contains("\"click_url")) &&
            (lowerBody.contains("\"show_url") ||
                lowerBody.contains("\"landing_url") ||
                lowerBody.contains("\"deep_link") ||
                lowerBody.contains("\"download_url"))) {
            score += 4
            reasons += "message-center-recommend-ad-extended"
        }
        if ((lowerBody.contains("\"discover") ||
                lowerBody.contains("\"recommend") ||
                lowerBody.contains("\"guess_like") ||
                lowerBody.contains("\"you_may_like")) &&
            (lowerBody.contains("\"promotion_card") ||
                lowerBody.contains("\"sponsor_card") ||
                lowerBody.contains("\"ad_card") ||
                lowerBody.contains("\"landing_url") ||
                lowerBody.contains("\"material_url") ||
                lowerBody.contains("\"show_url")) &&
            (lowerBody.contains("\"click_url") ||
                lowerBody.contains("\"deep_link") ||
                lowerBody.contains("\"download_url") ||
                lowerBody.contains("\"ad_material"))) {
            score += 4
            reasons += "discover-recommend-ad-extended"
        }
        if ((lowerBody.contains("\"message_center") ||
                lowerBody.contains("\"inbox") ||
                lowerBody.contains("\"notify") ||
                lowerBody.contains("\"bulletin") ||
                lowerBody.contains("\"notice")) &&
            (lowerBody.contains("\"message_center_ad\"") ||
                lowerBody.contains("\"message_center_banner\"") ||
                lowerBody.contains("\"inbox_ad\"") ||
                lowerBody.contains("\"notify_ad\"") ||
                lowerBody.contains("\"promotion_card\"") ||
                lowerBody.contains("\"promo_card\"") ||
                lowerBody.contains("\"operation_banner\"") ||
                lowerBody.contains("\"operation_card\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\"") ||
                lowerBody.contains("\"download_url\""))) {
            score += 4
            reasons += "message-center-ad-material-extended"
        }
        if ((lowerBody.contains("\"sign") ||
                lowerBody.contains("\"daily") ||
                lowerBody.contains("\"mission") ||
                lowerBody.contains("\"task") ||
                lowerBody.contains("\"benefit") ||
                lowerBody.contains("\"welfare")) &&
            (lowerBody.contains("\"sign_popup_ad\"") ||
                lowerBody.contains("\"daily_popup_ad\"") ||
                lowerBody.contains("\"mission_popup_ad\"") ||
                lowerBody.contains("\"task_popup_ad\"") ||
                lowerBody.contains("\"benefit_popup_ad\"") ||
                lowerBody.contains("\"welfare_popup_ad\"") ||
                lowerBody.contains("\"watch_ad_task\"") ||
                lowerBody.contains("\"coin_bonus\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 4
            reasons += "sign-task-benefit-ad-extended"
        }
        if ((lowerBody.contains("\"reader") ||
                lowerBody.contains("\"chapter") ||
                lowerBody.contains("\"reading") ||
                lowerBody.contains("\"book")) &&
            (lowerBody.contains("\"reader_sign_reward\"") ||
                lowerBody.contains("\"novel_sign_task\"") ||
                lowerBody.contains("\"sign_popup_ad\"") ||
                lowerBody.contains("\"benefit_popup_ad\"") ||
                lowerBody.contains("\"welfare_popup_ad\"") ||
                lowerBody.contains("\"mission_popup_ad\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 4
            reasons += "reader-sign-benefit-ad-extended"
        }
        val startupSceneHit = lowerBody.contains("\"startup") ||
            lowerBody.contains("\"launch") ||
            lowerBody.contains("\"splash") ||
            lowerBody.contains("\"popup") ||
            lowerBody.contains("\"interstitial")
        val startupConfigHit = lowerBody.contains("\"startup_config") ||
            lowerBody.contains("\"launch_config") ||
            lowerBody.contains("\"splash_config") ||
            lowerBody.contains("\"popup_config") ||
            lowerBody.contains("\"interstitial_config") ||
            lowerBody.contains("\"open_screen") ||
            lowerBody.contains("\"open_screen_ad") ||
            lowerBody.contains("\"launch_ad") ||
            lowerBody.contains("\"startup_ad") ||
            lowerBody.contains("\"interstitial_ad") ||
            lowerBody.contains("\"open_screen_cache") ||
            lowerBody.contains("\"splash_cache") ||
            lowerBody.contains("\"startup_cache") ||
            lowerBody.contains("\"launch_cache") ||
            lowerBody.contains("\"startup_banner")
        val adMaterialHit = lowerBody.contains("\"show_url") ||
            lowerBody.contains("\"click_url") ||
            lowerBody.contains("\"landing_url") ||
            lowerBody.contains("\"download_url") ||
            lowerBody.contains("\"ad_material") ||
            lowerBody.contains("\"material_url") ||
            lowerBody.contains("\"ad_dispatch")
        if (startupSceneHit && startupConfigHit && adMaterialHit) {
            score += 2
            reasons += "startup-ad-extended"
        }
        if ((lowerBody.contains("\"startup") || lowerBody.contains("\"launch") || lowerBody.contains("\"splash") || lowerBody.contains("\"open_screen")) &&
            (lowerBody.contains("\"startup_page_ad\"") ||
                lowerBody.contains("\"launch_screen_ad\"") ||
                lowerBody.contains("\"open_screen_material\"") ||
                lowerBody.contains("\"splash_material\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "startup-ad-material-extended"
        }
        if ((lowerBody.contains("\"startup") || lowerBody.contains("\"launch") || lowerBody.contains("\"splash") || lowerBody.contains("\"open_screen\"")) &&
            (lowerBody.contains("\"open_screen_cache\"") ||
                lowerBody.contains("\"startup_cache_material\"") ||
                lowerBody.contains("\"launch_cache_material\"") ||
                lowerBody.contains("\"splash_cache_material\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "startup-ad-cache-extended"
        }
        if ((lowerBody.contains("\"startup") || lowerBody.contains("\"launch") || lowerBody.contains("\"splash") || lowerBody.contains("\"open_screen\"")) &&
            (lowerBody.contains("\"startup_preload_ad\"") ||
                lowerBody.contains("\"launch_preload_ad\"") ||
                lowerBody.contains("\"splash_template_ad\"") ||
                lowerBody.contains("\"open_screen_dispatch\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "startup-ad-preload-extended"
        }
        if ((lowerBody.contains("\"qimao\"") || lowerBody.contains("\"kmxs\"") || lowerBody.contains("\"wtzw\"") || lowerBody.contains("\"reader\"")) &&
            (lowerBody.contains("\"chapter_unlock\"") ||
                lowerBody.contains("\"watch_ad_unlock\"") ||
                lowerBody.contains("\"free_read_popup\"") ||
                lowerBody.contains("\"reader_reward_popup\"") ||
                lowerBody.contains("\"novel_welfare_center\"") ||
                lowerBody.contains("\"novel_task_center\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 4
            reasons += "qimao-reader-ad-extended"
        }
        if ((lowerBody.contains("\"banner") || lowerBody.contains("\"bottom_banner") || lowerBody.contains("\"floating_banner")) &&
            (lowerBody.contains("\"show_url") || lowerBody.contains("\"click_url") || lowerBody.contains("\"material"))) {
            score += 1
            reasons += "banner-ad-cluster"
        }
        if ((lowerBody.contains("\"reader") || lowerBody.contains("\"chapter") || lowerBody.contains("\"reading")) &&
            (lowerBody.contains("\"bottom_banner") || lowerBody.contains("\"insert_ad") || lowerBody.contains("\"watch_ad_unlock") || lowerBody.contains("\"unlock_by_ad"))) {
            score += 2
            reasons += "reader-ad-cluster"
        }
        if ((lowerBody.contains("\"reader") || lowerBody.contains("\"chapter") || lowerBody.contains("\"page") || lowerBody.contains("\"reading")) &&
            (lowerBody.contains("\"reader_bottom_ad") ||
                lowerBody.contains("\"reader_bottom_banner") ||
                lowerBody.contains("\"page_turn_ad") ||
                lowerBody.contains("\"turn_page_ad") ||
                lowerBody.contains("\"flip_page_ad") ||
                lowerBody.contains("\"page_insert_ad") ||
                lowerBody.contains("\"chapter_next_ad") ||
                lowerBody.contains("\"reading_interstitial"))) {
            score += 2
            reasons += "reader-page-ad-extended"
        }
        if ((lowerBody.contains("\"reader") || lowerBody.contains("\"chapter") || lowerBody.contains("\"page") || lowerBody.contains("\"reading") || lowerBody.contains("\"book\"")) &&
            (lowerBody.contains("\"page_footer_ad\"") ||
                lowerBody.contains("\"chapter_footer_ad\"") ||
                lowerBody.contains("\"reader_footer_ad\"") ||
                lowerBody.contains("\"bottom_float_ad\"") ||
                lowerBody.contains("\"page_swipe_ad\"") ||
                lowerBody.contains("\"swipe_page_ad\"") ||
                lowerBody.contains("\"next_page_ad\"") ||
                lowerBody.contains("\"turn_page_banner\"") ||
                lowerBody.contains("\"page_corner_ad\"") ||
                lowerBody.contains("\"chapter_end_ad\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "reader-page-ad-material-extended"
        }
        if ((lowerBody.contains("\"reader") || lowerBody.contains("\"chapter") || lowerBody.contains("\"page") || lowerBody.contains("\"reading") || lowerBody.contains("\"book\"")) &&
            (lowerBody.contains("\"page_end_popup\"") ||
                lowerBody.contains("\"reader_page_popup\"") ||
                lowerBody.contains("\"chapter_page_popup\"") ||
                lowerBody.contains("\"page_tail_ad\"") ||
                lowerBody.contains("\"chapter_tail_ad\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "reader-page-popup-extended"
        }
        if ((lowerBody.contains("\"reader") || lowerBody.contains("\"chapter") || lowerBody.contains("\"page") || lowerBody.contains("\"reading") || lowerBody.contains("\"book\"")) &&
            (lowerBody.contains("\"page_tail_popup\"") ||
                lowerBody.contains("\"chapter_tail_popup\"") ||
                lowerBody.contains("\"reader_tail_popup\"") ||
                lowerBody.contains("\"page_end_card\"") ||
                lowerBody.contains("\"chapter_end_card\"") ||
                lowerBody.contains("\"swipe_reward_ad\"") ||
                lowerBody.contains("\"page_flip_reward\"") ||
                lowerBody.contains("\"reader_next_popup\"") ||
                lowerBody.contains("\"chapter_next_popup\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "reader-page-tail-extended"
        }
        if ((lowerBody.contains("\"coolapk\"") || lowerBody.contains("\"comment\"") || lowerBody.contains("\"reply\"") || lowerBody.contains("\"post\"")) &&
            (lowerBody.contains("\"comment_feed_ad\"") ||
                lowerBody.contains("\"comment_flow_ad\"") ||
                lowerBody.contains("\"reply_flow_ad\"") ||
                lowerBody.contains("\"comment_overlay_ad\"") ||
                lowerBody.contains("\"comment_float_card\"") ||
                lowerBody.contains("\"reply_float_card\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "coolapk-comment-ad-extended"
        }
        if ((lowerBody.contains("\"gdt\"") || lowerBody.contains("\"youlianghui\"") || lowerBody.contains("\"guangdiantong\"") || lowerBody.contains("\"adqq\"")) &&
            (lowerBody.contains("\"waterfall\"") ||
                lowerBody.contains("\"mediation\"") ||
                lowerBody.contains("\"bidding_token\"") ||
                lowerBody.contains("\"auction_id\"") ||
                lowerBody.contains("\"ad_material\"") ||
                lowerBody.contains("\"placement_id\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 4
            reasons += "gdt-sdk-ad-extended"
        }
        if ((lowerBody.contains("\"alipay\"") || lowerBody.contains("\"alimama\"") || lowerBody.contains("\"tanx\"") || lowerBody.contains("\"adash\"")) &&
            (lowerBody.contains("\"ad_material\"") ||
                lowerBody.contains("\"ad_strategy\"") ||
                lowerBody.contains("\"waterfall\"") ||
                lowerBody.contains("\"placement_id\"") ||
                lowerBody.contains("\"template_id\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 4
            reasons += "ali-sdk-ad-extended"
        }
        if ((lowerBody.contains("\"pangolin\"") || lowerBody.contains("\"pangle\"") || lowerBody.contains("\"gromore\"") || lowerBody.contains("\"snssdk\"")) &&
            (lowerBody.contains("\"waterfall\"") ||
                lowerBody.contains("\"mediation\"") ||
                lowerBody.contains("\"preload_ad\"") ||
                lowerBody.contains("\"ad_material\"") ||
                lowerBody.contains("\"ad_slot\"") ||
                lowerBody.contains("\"rit\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 4
            reasons += "shortvideo-sdk-ad-extended"
        }
        if ((lowerBody.contains("\"drama") || lowerBody.contains("\"episode") || lowerBody.contains("\"short_video") || lowerBody.contains("\"short_drama")) &&
            (lowerBody.contains("\"reward_popup") || lowerBody.contains("\"patch_ad") || lowerBody.contains("\"insert_ad") || lowerBody.contains("\"ad_material"))) {
            score += 2
            reasons += "drama-ad-cluster"
        }
        if ((lowerBody.contains("\"live") || lowerBody.contains("\"stream") || lowerBody.contains("\"anchor")) &&
            (lowerBody.contains("\"live_ad") || lowerBody.contains("\"floating_banner") || lowerBody.contains("\"show_url") || lowerBody.contains("\"material"))) {
            score += 2
            reasons += "live-ad-cluster"
        }
        if ((lowerBody.contains("\"comic") || lowerBody.contains("\"manga") || lowerBody.contains("\"chapter")) &&
            (lowerBody.contains("\"unlock_by_ad") || lowerBody.contains("\"chapter_unlock_ad") || lowerBody.contains("\"reward_popup"))) {
            score += 2
            reasons += "comic-ad-cluster"
        }
        val rewardSceneHit = lowerBody.contains("\"reward") ||
            lowerBody.contains("\"unlock") ||
            lowerBody.contains("\"bonus") ||
            lowerBody.contains("\"task")
        val rewardPlacementHit = lowerBody.contains("\"reward_popup") ||
            lowerBody.contains("\"watch_ad_unlock") ||
            lowerBody.contains("\"unlock_by_ad") ||
            lowerBody.contains("\"chapter_unlock_ad") ||
            lowerBody.contains("\"free_read_card") ||
            lowerBody.contains("\"task_reward")
        if (rewardSceneHit && rewardPlacementHit && (adMaterialHit || lowerBody.contains("\"reader") || lowerBody.contains("\"chapter") || lowerBody.contains("\"comic"))) {
            score += 2
            reasons += "reward-ad-extended"
        }
        if ((lowerBody.contains("\"task") || lowerBody.contains("\"benefit") || lowerBody.contains("\"welfare") || lowerBody.contains("\"coin")) &&
            (lowerBody.contains("\"task_center\"") ||
                lowerBody.contains("\"benefit_center\"") ||
                lowerBody.contains("\"welfare_center\"") ||
                lowerBody.contains("\"watch_ad_task\"") ||
                lowerBody.contains("\"daily_reward\"") ||
                lowerBody.contains("\"coin_bonus\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "task-benefit-ad-extended"
        }
        if ((lowerBody.contains("\"waterfall\"") || lowerBody.contains("\"mediation\"") || lowerBody.contains("\"bidding\"") || lowerBody.contains("\"auction\"")) &&
            (lowerBody.contains("\"placement_id\"") ||
                lowerBody.contains("\"slot_id\"") ||
                lowerBody.contains("\"template_id\"") ||
                lowerBody.contains("\"ad_strategy\"") ||
                lowerBody.contains("\"ad_dispatch\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "mediation-ad-extended"
        }
        if ((lowerBody.contains("\"comment") || lowerBody.contains("\"reply") || lowerBody.contains("\"floor")) &&
            (lowerBody.contains("\"comment_guide_ad") ||
                lowerBody.contains("\"comment_hot_ad") ||
                lowerBody.contains("\"comment_float_ad") ||
                lowerBody.contains("\"comment_promote_card") ||
                lowerBody.contains("\"comment_stream_ad") ||
                lowerBody.contains("\"reply_promote_card") ||
                lowerBody.contains("\"floor_insert_ad") ||
                lowerBody.contains("\"comment_promote"))) {
            score += 2
            reasons += "comment-ad-insert-extended"
        }
        if ((lowerBody.contains("\"comment") || lowerBody.contains("\"reply") || lowerBody.contains("\"floor") || lowerBody.contains("\"post")) &&
            (lowerBody.contains("\"comment_promote\"") ||
                lowerBody.contains("\"reply_promote\"") ||
                lowerBody.contains("\"floor_promote\"") ||
                lowerBody.contains("\"comment_material\"") ||
                lowerBody.contains("\"reply_material\"") ||
                lowerBody.contains("\"floor_material\"") ||
                lowerBody.contains("\"comment_landing_url\"") ||
                lowerBody.contains("\"reply_landing_url\"") ||
                lowerBody.contains("\"post_landing_url\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "comment-ad-material-extended"
        }
        if ((lowerBody.contains("\"comment") || lowerBody.contains("\"reply") || lowerBody.contains("\"floor") || lowerBody.contains("\"post\"")) &&
            (lowerBody.contains("\"comment_popup_ad\"") ||
                lowerBody.contains("\"comment_bottom_ad\"") ||
                lowerBody.contains("\"reply_bottom_ad\"") ||
                lowerBody.contains("\"floor_bottom_ad\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 3
            reasons += "comment-ad-popup-extended"
        }
        if ((lowerBody.contains("pause-ad") || lowerBody.contains("player-ad") || lowerBody.contains("reward-pop") || lowerBody.contains("offerwall")) &&
            (lowerBody.contains("click_url") || lowerBody.contains("show_url") || lowerBody.contains("material") || lowerBody.contains("landing"))) {
            score += 2
            reasons += "player-ad-cluster"
        }
        if ((lowerBody.contains("\"pause_ad\"") ||
                lowerBody.contains("\"player_ad\"") ||
                lowerBody.contains("\"preroll_ad\"") ||
                lowerBody.contains("\"midroll_ad\"") ||
                lowerBody.contains("\"postroll_ad\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
            score += 2
            reasons += "player-ad-extended"
        }
        if ((lowerBody.contains("splash-ad") || lowerBody.contains("open-screen") || lowerBody.contains("startup-banner") || lowerBody.contains("launch-ad")) &&
            (lowerBody.contains("show_url") || lowerBody.contains("click_url") || lowerBody.contains("ad_material") || lowerBody.contains("ad_dispatch"))) {
            score += 2
            reasons += "splash-ad-cluster"
        }
        if ((lowerBody.contains("\"reader\"") || lowerBody.contains("\"chapter\"") || lowerBody.contains("\"reading\"") || lowerBody.contains("\"book\"")) &&
            (lowerBody.contains("\"reader_reward_popup\"") ||
                lowerBody.contains("\"chapter_offerwall\"") ||
                lowerBody.contains("\"free_read_popup\"") ||
                lowerBody.contains("\"reader_float_ad\"") ||
                lowerBody.contains("\"chapter_card_ad\"") ||
                lowerBody.contains("\"novel_task_center\"") ||
                lowerBody.contains("\"novel_welfare_center\"")) &&
            (lowerBody.contains("\"click_url\"") ||
                lowerBody.contains("\"show_url\"") ||
                lowerBody.contains("\"material_url\"") ||
                lowerBody.contains("\"landing_url\""))) {
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
        if (!blockedHost && !blockedUrl && RuleRepository.isCommunityAppHint(session.appName)) return null
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
        val domesticSdkHits = domesticAdSdkKeywords.count { keyword ->
            lowerAuthority.contains(keyword) || lowerPath.contains(keyword) || lowerReferer.contains(keyword) || lowerUserAgent.contains(keyword)
        }
        if (RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, lowerAuthority, lowerPath, session.appName)) {
            suspiciousScore += 4
            reasons += "novel-protected-path"
        }
        if (pathInspection.strongSuspicious) {
            suspiciousScore += if (isNovelApp) 4 else 3
            reasons += "path-strong-suspicious"
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
        if (isKnownAdVendor(vendor)) {
            suspiciousScore += 2
            reasons += "vendor:$vendor"
        }
        if (domesticSdkHits > 0) {
            suspiciousScore += if (domesticSdkHits >= 2) 3 else 2
            reasons += "domestic-sdk-signal"
        }
        // 小说 APP 激进拦截 - 增加权重
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(context, lowerAuthority, session.appName, vendor)) {
            suspiciousScore += if (isNovelApp) 4 else 3
            reasons += "novel-app-aggressive"
        }
        if (pathInspection.suspicious) {
            suspiciousScore += if (isNovelApp) 3 else 2
            reasons += "path-keyword"
        }
        if (suspiciousHeaderKeywords.any { lowerReferer.contains(it) }) {
            suspiciousScore += if (isNovelApp) 2 else 1
            reasons += "referer-keyword"
        }
        if (suspiciousHeaderKeywords.any { lowerLocation.contains(it) }) {
            suspiciousScore += if (isNovelApp) 2 else 1
            reasons += "location-keyword"
        }
        if (suspiciousHeaderKeywords.any { lowerSetCookie.contains(it) }) {
            suspiciousScore += if (isNovelApp) 2 else 1
            reasons += "set-cookie-keyword"
        }
        if (strongHeaderKeywords.any { lowerLocation.contains(it) }) {
            suspiciousScore += 3
            reasons += "location-strong-header"
        }
        if (strongHeaderKeywords.any { lowerSetCookie.contains(it) }) {
            suspiciousScore += 3
            reasons += "set-cookie-strong-header"
        }
        if (strongResponseAdKeywords.any { lowerPath.contains(it) }) {
            suspiciousScore += 3
            reasons += "path-strong-keyword"
        }
        if (strongResponseAdKeywords.any { lowerLocation.contains(it) }) {
            suspiciousScore += 3
            reasons += "location-strong-keyword"
        }
        if (strongResponseAdKeywords.any { lowerSetCookie.contains(it) }) {
            suspiciousScore += 3
            reasons += "set-cookie-strong-keyword"
        }
        // 增强追踪字段检测
        val headerTrackingHits = adTrackingHeaderFields.filter { field ->
            lowerLocation.contains(field) || lowerSetCookie.contains(field)
        }
        if (headerTrackingHits.isNotEmpty()) {
            suspiciousScore += if (headerTrackingHits.size >= 2) 4 else (if (isNovelApp) 3 else 2)
            reasons += "header-tracking"
        }
        if (strongResponseAdKeywords.any { lowerContentType.contains(it) }) {
            suspiciousScore += 1
            reasons += "content-type-keyword"
        }
        if (responseAdKeywords.any { lowerContentType.contains(it) }) {
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
                shouldSyntheticRespond = false
            )
        }
        val shouldBlock = shouldBlockHttp2ResponseFromHeaders(inspection, isNovelApp)
        return Http2ActionDecision(
            action = if (shouldBlock) "block" else "monitor",
            confidence = if (inspection.suspiciousScore >= 4) "high" else "medium",
            shouldBlockCandidate = shouldBlock,
            shouldSyntheticRespond = shouldBlock && inspection.responseLike
        )
    }

    private fun shouldBlockHttp2ResponseFromHeaders(inspection: Http2HeaderInspection, isNovelApp: Boolean = false): Boolean {
        val context = TlsMitmSessionManager.requireContext()
        if (RuleRepository.isCommunityAppHint(inspection.appName) && !RuleRepository.isBlocked(context, inspection.authority, appName = inspection.appName)) {
            return false
        }
        val threshold = if (isNovelApp) HTTP2_NOVEL_RESPONSE_BLOCK_SCORE else HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE
        if (inspection.suspiciousScore < threshold) return false
        // 小说 APP 降低拦截门槛
        if (isNovelApp && inspection.suspiciousScore >= 2) return true
        if (inspection.suspiciousScore >= 4) return true
        val reasons = inspection.suspiciousReasons.toSet()
        if (reasons.any { reason ->
                reason == "blocked-host" ||
                    reason == "blocked-url" ||
                    reason == "general-ad-traffic" ||
                    reason == "novel-app-aggressive" ||
                    reason == "novel-protected-path" ||
                    reason == "domestic-sdk-signal" ||
                    reason == "reward-unlock-path" ||
                    reason == "doh-request" ||
                    reason == "json-ad-field" ||
                    reason == "json-ad-array" ||
                    reason == "json-ad-content" ||
                    reason == "novel-field-cluster" ||
                    reason == "media-field-cluster" ||
                    reason == "feed-ad-cluster" ||
                    reason == "banner-ad-cluster" ||
                    reason == "reader-ad-cluster" ||
                    reason == "comment-ad-cluster" ||
                    reason == "comment-ad-extended" ||
                    reason == "comment-ad-float-extended" ||
                    reason == "comment-ad-flow-extended" ||
                    reason == "comment-ad-insert-extended" ||
                    reason == "coolapk-comment-ad-extended" ||
                    reason == "comment-ad-material-extended" ||
                    reason == "comment-ad-popup-extended" ||
                    reason == "gdt-sdk-ad-extended" ||
                    reason == "ali-sdk-ad-extended" ||
                    reason == "shortvideo-sdk-ad-extended" ||
                    reason == "video-ad-cluster" ||
                    reason == "feed-ad-extended" ||
                    reason == "push-recommend-ad-extended" ||
                    reason == "message-center-ad-material-extended" ||
                    reason == "sign-task-benefit-ad-extended" ||
                    reason == "reader-sign-benefit-ad-extended" ||
                    reason == "reader-page-ad-extended" ||
                    reason == "reader-page-ad-material-extended" ||
                    reason == "reader-page-popup-extended" ||
                    reason == "reader-page-tail-extended" ||
                    reason == "reader-ad-material-extended" ||
                    reason == "qimao-reader-ad-extended" ||
                    reason == "drama-ad-cluster" ||
                    reason == "live-ad-cluster" ||
                    reason == "comic-ad-cluster" ||
                    reason == "player-ad-cluster" ||
                    reason == "player-ad-extended" ||
                    reason == "splash-ad-cluster" ||
                    reason == "startup-ad-extended" ||
                    reason == "startup-ad-cache-extended" ||
                    reason == "startup-ad-preload-extended" ||
                    reason == "startup-ad-material-extended" ||
                    reason == "reward-ad-extended" ||
                    reason == "neutralized-body-reward-unlock" ||
                    reason == "path-strong-suspicious" ||
                    reason == "location-strong-header" ||
                    reason == "set-cookie-strong-header" ||
                    reason.startsWith("header-field:") ||
                    reason == "path-strong-keyword" ||
                    reason == "location-strong-keyword" ||
                    reason == "set-cookie-strong-keyword"
            }
        ) {
            return true
        }
        return inspection.suspiciousScore >= 3 &&
            reasons.any { it == "path-keyword" || it == "location-keyword" || it == "set-cookie-keyword" } &&
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
        if (strongSuspicious) {
            val rewardUnlock = looksLikeRewardUnlockPath(path)
            return cachePathInspection(path, PathInspection(suspicious = true, strongSuspicious = true, rewardUnlock = rewardUnlock))
        }
        val suspicious = suspiciousPathKeywords.any { path.contains(it) }
        val query = path.substringAfter('?', "")
        if (query.isBlank()) {
            return cachePathInspection(path, PathInspection(
                suspicious = suspicious,
                strongSuspicious = false,
                rewardUnlock = looksLikeRewardUnlockPath(path)
            ))
        }
        val querySuspicious = suspiciousQueryKeywords.any { keyword ->
            query.contains("$keyword=") || query.contains("_$keyword=") || query.contains("-$keyword=") || query.contains(keyword)
        }
        return cachePathInspection(path, PathInspection(
            suspicious = suspicious || querySuspicious,
            strongSuspicious = false,
            rewardUnlock = looksLikeRewardUnlockPath(path)
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
        return strongQueryKeywords.any { keyword ->
            query.contains("$keyword=") || query.contains("_$keyword=") || query.contains("-$keyword=")
        }
    }

    private fun looksLikeRewardUnlockPath(path: String): Boolean {
        if (path.isBlank()) return false
        return path.contains("reward") && path.contains("unlock") ||
            path.contains("watch_ad") ||
            path.contains("unlock_by_ad") ||
            path.contains("chapter_unlock") ||
            path.contains("benefit") && path.contains("task")
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

    private fun rewriteRequestLine(requestLine: String, removeParams: Set<String>): String {
        if (removeParams.isEmpty()) return requestLine
        val parts = requestLine.split(' ')
        if (parts.size < 2) return requestLine
        val updatedPath = rewritePathOnly(parts[1], removeParams)
        if (updatedPath == parts[1]) return requestLine
        return buildString {
            append(parts[0]).append(' ').append(updatedPath)
            if (parts.size > 2) append(' ').append(parts.drop(2).joinToString(" "))
        }
    }

    private fun rewritePathOnly(path: String, removeParams: Set<String>): String {
        if (removeParams.isEmpty() || !path.contains('?')) return path
        val base = path.substringBefore('?')
        val fragment = path.substringAfter('#', "")
        val query = path.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return path
        val filtered = query.split('&')
            .filter { it.isNotBlank() }
            .filterNot { part -> removeParams.contains(part.substringBefore('=').trim().lowercase()) }
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
        val requestLike: Boolean,
        val responseLike: Boolean
    )

    data class Http2ActionDecision(
        val action: String,
        val confidence: String,
        val shouldBlockCandidate: Boolean,
        val shouldSyntheticRespond: Boolean = false
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
        val combinedSample: ByteArray
    )

    private data class BodySignalInspection(
        val score: Int,
        val reasons: List<String>
    )
}
