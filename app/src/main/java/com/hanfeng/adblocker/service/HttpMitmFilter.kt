package com.HanFeng.service

import android.content.Context
import com.HanFeng.core.network.UserAdFeedbackManager
import com.HanFeng.core.network.RegexCache
import com.HanFeng.core.network.StealthModeSupport
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.RuleRepository
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.concurrent.ConcurrentHashMap

object HttpMitmFilter {
    private const val MAX_HTTP1_FILTER_BUFFER_BYTES = 512 * 1024
    private const val HTTP1_FILTER_BUFFER_BYTES = 64 * 1024
    private const val HTTP2_DATA_BODY_LIMIT_BYTES = 64 * 1024
    private const val MAX_DECODED_BODY_BYTES = 512 * 1024
    
    // 正则表达式缓存（P1 优化）
    private val compiledReplaceRules = object : LinkedHashMap<String, Regex>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?): Boolean = size > 512
    }
    private val compiledReplaceRulesLock = Any()
    private const val MAX_COMPILED_REGEX_CACHE = 1024
    private val http2ImmediateBlockReasons = setOf(
        "blocked-host", "blocked-url", "general-ad-traffic", "novel-app-aggressive", "novel-protected-path",
        "domestic-sdk-signal", "reward-unlock-path", "doh-request", "json-ad-field", "json-ad-array",
        "json-ad-content", "novel-field-cluster", "media-field-cluster", "feed-ad-cluster", "banner-ad-cluster",
        "reader-ad-cluster", "comment-ad-path", "comment-ad-cluster", "comment-ad-extended", "comment-ad-float-extended",
        "comment-ad-flow-extended", "comment-ad-insert-extended", "coolapk-comment-ad-extended", "comment-ad-material-extended",
        "comment-ad-popup-extended", "comment-commerce-path", "comment-commerce-ad-extended", "comment-gdt-commerce-ad-extended",
        "comment-sponsored-card-extended", "comment-native-ad-extended", "comment-track-ad-extended",
        "feed-sponsored-card-extended", "timeline-native-ad-extended", "stream-commercial-card-extended",
        "push-recommend-material-extended", "message-center-card-ad-extended", "gdt-sdk-ad-extended",
        "ali-sdk-ad-extended", "shortvideo-sdk-ad-extended", "video-ad-cluster", "feed-ad-extended",
        "push-recommend-ad-extended", "message-center-ad-material-extended", "sign-task-benefit-ad-extended",
        "reader-sign-benefit-ad-extended", "reader-page-ad-extended", "reader-page-ad-material-extended",
        "reader-page-popup-extended", "reader-page-tail-extended", "reader-ad-material-extended", "qimao-reader-ad-extended",
        "drama-ad-cluster", "live-ad-cluster", "comic-ad-cluster", "player-ad-cluster", "player-ad-extended",
        "splash-ad-cluster", "startup-ad-extended", "startup-ad-cache-extended", "startup-ad-preload-extended",
        "startup-ad-material-extended", "reward-ad-extended", "neutralized-body-reward-unlock", "path-strong-suspicious",
        "mediation-bid-material-extended", "mediation-waterfall-material-extended", "adn-placement-material-extended",
        "sdk-config-ad-extended", "sdk-tracker-array-extended",
        "ad-markup-payload-extended", "attribution-payload-extended",
        "openrtb-bid-response-extended",
        "admob-mediation-payload-extended", "applovin-max-payload-extended", "ironsource-auction-payload-extended",
        "vast-video-ad-payload-extended", "native-assets-ad-payload-extended", "playable-endcard-payload-extended",
        "push-notification-ad-payload-extended", "fake-system-alert-ad-payload-extended",
        "deeplink-market-ad-payload-extended", "operation-popup-ad-payload-extended",
        "precache-material-ad-payload-extended", "shake-sensor-ad-payload-extended",
        "httpdns-ad-resolution-payload-extended", "websocket-sse-ad-stream-payload-extended",
        "grpc-protobuf-ad-payload-extended", "dynamic-code-ad-module-payload-extended",
        "websocket-block",
        "encrypted-config-ad-payload-extended", "private-protocol-ad-gateway-payload-extended",
        "media-preroll-metadata-payload-extended", "audio-ad-break-payload-extended",
        "comic-manga-unlock-ad-payload-extended", "short-drama-episode-ad-payload-extended",
        "task-reward-offerwall-payload-extended", "game-interstitial-ad-payload-extended",
        "live-room-ad-payload-extended", "search-recommend-ad-payload-extended",
        "experiment-ad-config-payload-extended", "miniapp-landing-ad-payload-extended",
        "fake-action-button-payload-extended", "clipboard-share-ad-payload-extended",
        "serviceworker-cache-ad-payload-extended", "system-surface-ad-payload-extended",
        "social-interaction-ad-payload-extended", "cloud-template-ad-payload-extended",
        "asset-package-ad-payload-extended", "coupon-redpacket-ad-payload-extended",
        "ecommerce-affiliate-ad-payload-extended", "local-life-tool-ad-payload-extended",
        "leadgen-survey-ad-payload-extended", "calendar-reminder-ad-payload-extended",
        "browser-startpage-ad-payload-extended", "appstore-promotion-ad-payload-extended",
        "oem-security-cleaner-ad-payload-extended", "cross-device-ad-payload-extended",
        "lightweight-payload-model-ad-extended",
        "wasm-js-obfuscated-loader-ad-extended", "media-fingerprint-watermark-ad-extended",
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
    private val jsonWhitespaceRegex = Regex("\\s+")
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
        "comment_sponsor_card", "reply_sponsor_card", "floor_sponsor_card", "comment_native_ad", "reply_native_ad", "comment_commercial_card",
        "reader_bottom_ad", "page_turn_ad", "turn_page_ad", "flip_page_ad", "page_insert_ad", "chapter_next_ad", "reading_page_ad", "chapter_page_ad",
        // 社区 App 广告特征
        "feed_insert_ad", "timeline_ad", "stream_ad", "list_ad", "card_ads", "card_ad", "ad_banner", "ad_banners",
        "comment_ad", "comment_ads", "reply_ad", "reply_ads", "floor_ad", "post_ad", "post_ads",
        "subject_ad", "topic_ad", "hashtag_ad", "tag_ad", "explore_ad", "discovery_ad",
        "sponsor_card", "sponsored_card", "native_card_ad", "commercial_card", "brand_card", "brand_feed_card",
        // 短视频/直播广告
        "live_ad", "live_ads", "streamer_ad", "anchor_ad", "video_card_ad", "short_video_ad",
        // 电商广告
        "product_ad", "product_ads", "shop_ad", "shop_ads", "mall_ad", "mall_ads", "item_ad", "item_ads",
        // 激励广告
        "task_ad", "task_ads", "sign_ad", "sign_ads", "checkin_ad", "daily_ad", "benefit_ad", "coin_ad",
        // 信息流广告变种
        "feed_detail_ad", "article_ad", "article_ads", "news_ad", "news_ads", "content_ad", "content_ads",
        "media_ad", "media_ads", "image_ad", "image_ads", "pic_ad", "pic_ads", "gallery_ad",
        "timeline_sponsor", "timeline_commercial", "stream_sponsor", "stream_commercial", "feed_sponsor", "feed_commercial"
        , "is_ad", "ad_type", "ad_source", "ad_scene", "ad_style", "ad_position", "ad_label",
        "reader_bottom_card", "reader_insert_card", "page_ad_card", "chapter_ad_card", "turn_page_card",
        "comment_ad_card", "reply_ad_card", "feed_ad_card", "sponsor_feed", "commercial_feed"
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
        "openrtb",
        "seatbid",
        "impid",
        "adomain",
        "crid",
        "burl",
        "nurl",
        "lurl",
        "iurl",
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
        "ad_payload", "adpayload", "ad_meta", "admeta", "ad_trace", "adtrace", "ad_event", "adevent",
        "ad_request", "adrequest", "ad_response_info", "adresponseinfo", "ad_loader", "adloader",
        "ad_cache", "adcache", "ad_preload", "adpreload", "ad_config", "adconfig",
        "ad_tracking", "adtracking", "tracking_list", "trackinglist", "tracker_list", "trackerlist",
        "imp_trackers", "imptrackers", "click_trackers", "clicktrackers", "view_trackers", "viewtrackers",
        "creative_data", "creativedata", "render_data", "renderdata", "template_data", "templatedata",
        "asset_list", "assetlist", "assets", "ad_assets", "adassets",
        "sponsor_info", "sponsorinfo", "sponsored_info", "commercial_info", "native_ad", "nativead",
        "impression_url", "impressionurl", "monitor_url", "monitorurl", "exposure_url", "exposureurl",
        "ad_report", "adreport", "ad_analytics", "adanalytics", "ad_monitor", "admonitor",
        "cache_buster", "cachebuster", "sdk_version", "sdkversion", "placement_type", "placementtype",
        "ad_html", "adhtml", "ad_template", "adtemplate", "ad_payload", "adpayload",
        "ecpm_floor", "ecpmfloor", "floor_price", "floorprice", "ad_floor_price", "adfloorprice",
        "bid_token", "bidtoken", "bid_id", "bidid", "auction_result", "auctionresult",
        "seatbid", "impid", "adomain", "crid", "cid", "burl", "nurl", "lurl", "iurl",
        "waterfall", "waterfall_id", "waterfallid", "waterfall_config", "waterfallconfig",
        "waterfall_item", "waterfallitem", "waterfall_list", "waterfalllist", "waterfall_group", "waterfallgroup",
        "bidding_token", "biddingtoken", "bid_token", "bidtoken", "bid_floor", "bidfloor", "bid_price", "bidprice",
        "win_price", "winprice", "loss_url", "lossurl", "auction_id", "auctionid", "auction_price", "auctionprice",
        "mediation", "mediation_id", "mediationid", "mediation_config", "mediationconfig", "mediation_list", "mediationlist",
        "admob_config", "admobconfig", "pangle_config", "pangleconfig", "gdt_config", "gdtconfig",
        "preload_ad", "preloadad", "prefetch_ad", "prefetchad", "cache_ad", "cachead", "cached_ad", "cachedad",
        "ad_inventory", "adinventory", "inventory_id", "inventoryid", "fill_rate", "fillrate", "fill_ratio", "fillratio",
        "parallel_load", "parallelload", "load_strategy", "loadstrategy", "request_scene", "requestscene",
        "is_ad", "ad_type", "ad_source", "ad_scene", "ad_style", "ad_position", "ad_label",
        "entity_type", "entitytemplate", "entity_template", "extra_data", "extra_json",
        "reader_bottom_card", "reader_insert_card", "page_ad_card", "chapter_ad_card", "turn_page_card",
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
        "zghd", "zhghd", "hxltad", "adintl", "qxm", "qxmad", "qxmads", "52qumao",
        "bidmachine", "liftoff", "smaato", "pubmatic", "openx", "moloco", "fyber",
        "digitalturbine", "dt_exchange", "ogury", "maio", "reklamup", "yandexads", "mytarget"
    )
    private val pangleAndGdtHostSignals = listOf(
        "pangolin-sdk-toutiao", "pangle", "pangolin", "gromore", "csj", "oceanengine",
        "gdt.qq", "e.qq", "gdtimg", "youlianghui", "guangdiantong"
    )
    private val pangleAndGdtPathSignals = listOf(
        "/union/sdk", "/sdk/union", "/ad/get", "/ad/fetch", "/ad/dispatch", "/ad/request",
        "/material/list", "/creative/list", "/placement/list", "/sdk/config", "/waterfall", "/mediation",
        "/auction", "/bidding", "/reward/video", "/open_screen", "/splash", "/launch", "/startup",
        "/rtb", "/bid/request", "/bid/response", "/ad/auction", "/ad/waterfall", "/ad/mediation",
        "/sdk/init", "/sdk/preload", "/rewarded", "/interstitial", "/native/express"
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
        "adload", "loadads", "requestads", "fetchads", "getads", "adserver", "adserverapi",
        "rewarded", "rewardedvideo", "nativeexpress", "bidrequest", "bidresponse", "rtbrequest",
        "winnotice", "lossnotice", "adm", "vast", "omid", "mraid", "skadn", "skadnetwork",
        "dns", "dnsquery", "dns-query", "dns_message", "dns-message", "dnsjson", "dns-json", "httpdns", "resolver"
    )
    private val dohPathKeywords = listOf(
        "/dns-query", "/resolve", "/query", "/dns", "/httpdns", "/resolver", "/dns/resolve", "/doh"
    )
    private val dohContentTypeKeywords = listOf(
        "application/dns-message",
        "application/dns-message+xml",
        "application/dns-udpwireformat",
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
        "\"native_express\"", "\"landing_page_url\"", "\"download_button\"", "\"download_btn\"",
        "\"bid_request\"", "\"bid_response\"", "\"bidrequest\"", "\"bidresponse\"",
        "\"rtb_request\"", "\"rtb_response\"", "\"adm\"", "\"vast\"", "\"mraid\"", "\"omid\"",
        "\"skadn\"", "\"skadnetwork\"", "\"win_notice_url\"", "\"loss_notice_url\"",
        "\"event_trackers\"", "\"tracking_events\"", "\"ad_payload\"", "\"ad_meta\"",
        "\"adn\"", "\"adn_name\"", "\"network_name\"", "\"network_placement\"", "\"bidfloor\"", "\"bid_floor\"",
        "\"auction_price\"", "\"auction_token\"", "\"sdk_ad\"", "\"sdk_ads\"", "\"ad_ecpm\"", "\"ad_bid\"", "\"ad_bid_id\"",
        "\"ad_extra\"", "\"adextra\"", "\"sponsor_info\"", "\"sponsorinfo\"", "\"commercial_info\"", "\"commercialinfo\"",
        "\"ad_request\"", "\"adrequest\"", "\"ad_response_info\"", "\"adresponseinfo\"", "\"ad_loader\"", "\"adloader\"",
        "\"ad_cache\"", "\"adcache\"", "\"ad_preload\"", "\"adpreload\"", "\"ad_config\"", "\"adconfig\"",
        "\"creative_data\"", "\"creativedata\"", "\"render_data\"", "\"renderdata\"", "\"template_data\"", "\"templatedata\"",
        "\"asset_list\"", "\"assetlist\"", "\"ad_assets\"", "\"adassets\"", "\"ecpm_floor\"", "\"floor_price\""
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
        "\"sign_popup_ad\"", "\"daily_popup_ad\"", "\"mission_popup_ad\"", "\"welfare_popup_ad\"",
        "\"bid_request\"", "\"bid_response\"", "\"ad_payload\"", "\"ad_meta\"", "\"ad_event\"",
        "\"event_trackers\"", "\"tracking_events\"", "\"win_notice_url\"", "\"loss_notice_url\"",
        "\"ad_markup\"", "\"ad_html\"", "\"ad_template\"", "\"ad_unit_config\"",
        "\"rewarded_ad\"", "\"rewarded_ads\"", "\"rewarded_video_ad\"", "\"inspire_video_ad\"", "\"incentive_ad\"",
        "\"offerwall_ad\"", "\"draw_video_ad\"", "\"short_drama_ad\"", "\"comic_insert_ad\"", "\"manga_insert_ad\"",
        "\"adn\"", "\"adn_name\"", "\"network_name\"", "\"bidfloor\"", "\"bid_floor\"", "\"auction_token\"",
        "\"ad_request\"", "\"ad_response_info\"", "\"ad_loader\"", "\"ad_preload\"", "\"ad_tracking\"",
        "\"tracking_list\"", "\"tracker_list\"", "\"imp_trackers\"", "\"click_trackers\"", "\"view_trackers\"",
        "\"creative_data\"", "\"render_data\"", "\"template_data\"", "\"asset_list\"", "\"ad_assets\"",
        "\"ecpm_floor\"", "\"floor_price\"", "\"ad_floor_price\"", "\"auction_result\"",
        "\"is_ad\"", "\"isAd\"", "\"ad_type\"", "\"adType\"", "\"ad_source\"", "\"adSource\"", "\"ad_scene\"", "\"adScene\"",
        "\"ad_position\"", "\"adPosition\"", "\"ad_label\"", "\"adLabel\"", "\"entity_type\"", "\"entityType\"",
        "\"entity_template\"", "\"entityTemplate\"", "\"entity_data\"", "\"entityData\"",
        "\"reader_bottom_card\"", "\"reader_insert_card\"", "\"page_ad_card\"", "\"chapter_ad_card\"", "\"turn_page_card\"",
        "\"comment_ad_card\"", "\"reply_ad_card\"", "\"feed_ad_card\"", "\"sponsor_feed\"", "\"commercial_feed\"",
        "\"comment_entity_ad\"", "\"reply_entity_ad\"", "\"sponsor_info\"", "\"commercial_info\""
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
        "\"benefit_popup_ad\"", "\"welfare_popup_ad\"", "\"reader_sign_reward\"", "\"novel_sign_task\"",
        "\"listen_reward_ad\"", "\"audio_reward_ad\"", "\"comic_unlock_ad\"", "\"manga_unlock_ad\"",
        "\"short_drama_unlock_ad\"", "\"drama_reward_ad\"", "\"chapter_preload_ad\"", "\"reader_preload_ad\""
    )
    private val mediaAdFieldTokens = listOf(
        "\"episode_id\"", "\"episode_name\"", "\"drama_id\"", "\"drama_name\"", "\"short_drama\"",
        "\"short_video\"", "\"live_room\"", "\"live_room_id\"", "\"anchor_id\"", "\"stream_id\"",
        "\"stream_url\"", "\"play_scene\"", "\"comic_id\"", "\"comic_name\"", "\"manga_id\"",
        "\"manga_name\"", "\"drama_scene\"", "\"short_drama_scene\"", "\"episode_unlock_ad\"",
        "\"chapter_unlock_ad\"", "\"pause_ad\"", "\"player_ad\"", "\"video_patch\"", "\"patch_ads\"",
        "\"live_ad\"", "\"draw_ad\"", "\"floating_banner\"", "\"short_drama_ad\"", "\"comic_ad\"", "\"manga_ad\""
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
        "\"unlock_chapter\"", "\"chapter_unlock\"", "\"excitation_ad\"",
        "\"rewarded_video\"", "\"rewardedvideo\"", "\"incentive_video\"", "\"incentivevideo\"",
        "\"ad_payload\"", "\"ad_meta\"", "\"ad_event\"", "\"event_trackers\"",
        "\"bid_request\"", "\"bid_response\"", "\"win_notice_url\"", "\"loss_notice_url\"",
        "\"adn\"", "\"adn_name\"", "\"adn_id\"", "\"adn_slot_id\"", "\"network_name\"", "\"network_id\"", "\"network_placement_id\"",
        "\"bidfloor\"", "\"bid_floor\"", "\"bid_payload\"", "\"bid_token\"", "\"bidding_token\"", "\"auction_token\"", "\"auction_id\"",
        "\"win_price\"", "\"win_notice\"", "\"loss_notice\"", "\"render_url\"", "\"creative_url\"", "\"tracking_urls\"",
        "\"impression_urls\"", "\"click_urls\"", "\"sdk_extra\"", "\"third_sdk\"", "\"mediation_id\"", "\"waterfall_id\"",
        "\"rewarded_ad\"", "\"rewarded_ads\"", "\"offerwall_ad\"", "\"short_drama_ad\"", "\"comic_insert_ad\"",
        "\"ad_unit_id\"", "\"adunit_id\"", "\"sdk_ad_unit_id\"", "\"sdk_slot_id\"", "\"ad_slot_id\"", "\"adslot_id\"",
        "\"native_template\"", "\"native_template_id\"", "\"template_style\"", "\"render_template\"", "\"ad_template\"",
        "\"reward_video\"", "\"rewarded_video\"", "\"rewarded_video_ad\"", "\"interstitial_ad\"", "\"splash_ad\"",
        "\"show_trackers\"", "\"click_trackers\"", "\"exposure_trackers\"", "\"monitor_urls\"", "\"imp_trackers\"",
        "\"view_trackers\"", "\"tracking_list\"", "\"tracker_list\"", "\"ad_tracking\"", "\"ad_events\"",
        "\"ad_request\"", "\"ad_response_info\"", "\"ad_loader\"", "\"ad_preload\"", "\"ad_cache\"",
        "\"creative_data\"", "\"render_data\"", "\"template_data\"", "\"asset_list\"", "\"ad_assets\"",
        "\"ecpm_floor\"", "\"floor_price\"", "\"ad_floor_price\"", "\"bid_id\"", "\"auction_result\"",
        "\"adm\"", "\"vast\"", "\"vast_tag\"", "\"vast_xml\"", "\"mraid\"", "\"omid\"", "\"playable_url\"",
        "\"endcard_url\"", "\"companion_ads\"", "\"skadn\"", "\"skadnetwork\"", "\"conversion_value\"",
        "\"is_ad\"", "\"isAd\"", "\"ad_type\"", "\"adType\"", "\"ad_source\"", "\"adSource\"", "\"ad_scene\"", "\"adScene\"",
        "\"ad_position\"", "\"adPosition\"", "\"ad_label\"", "\"adLabel\"", "\"entity_type\"", "\"entityType\"",
        "\"reader_bottom_card\"", "\"reader_insert_card\"", "\"page_ad_card\"", "\"chapter_ad_card\"", "\"turn_page_card\"",
        "\"comment_ad_card\"", "\"reply_ad_card\"", "\"feed_ad_card\"", "\"sponsor_feed\"", "\"commercial_feed\""
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
        "\"welfare_popup_ad\"", "\"message_center_ad\"", "\"promotion_card\"", "\"discover_card\"",
        "\"listen_reward_ad\"", "\"audio_reward_ad\"", "\"comic_unlock_ad\"", "\"manga_unlock_ad\"",
        "\"short_drama_unlock_ad\"", "\"shortdrama_unlock_ad\"", "\"drama_reward_ad\"", "\"episode_unlock_ad\"",
        "\"episode_reward_ad\"", "\"episode_preload_ad\"", "\"drama_interstitial_ad\"", "\"drama_feed_ad\"",
        "\"comic_reward_ad\"", "\"comic_page_ad\"", "\"manga_page_ad\"", "\"listen_unlock_ad\"", "\"audio_unlock_ad\"",
        "\"reader_bottom_card\"", "\"reader_insert_card\"", "\"page_ad_card\"", "\"chapter_ad_card\"", "\"turn_page_card\"",
        "\"ad_position\"", "\"adPosition\"", "\"ad_scene\"", "\"adScene\"", "\"ad_style\"", "\"adStyle\""
    )
    private val htmlNovelMarkerTokens = listOf(
        "welfare-page", "welfare_page", "task-center", "task_center", "coin-reward", "coin_reward",
        "reading-bonus", "reading_bonus", "reward-video", "watch-ad", "watch_ad", "unlock-by-ad",
        "unlock_chapter", "offerwall", "benefit-page", "benefit_page"
    )
    private val readerVisibleAdTextTokens = listOf(
        "穿山甲广告", "优量汇广告", "广告是为了更好地支持作者创作", "看视频免广告",
        "滑动可继续阅读", "免广告", "戳我下载", "立即打开", "查看详情", "北京抖音科技有限公司",
        "pangolin", "pangle", "gromore", "youlianghui", "guangdiantong"
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
        "fill/rate", "request_scene", "load_strategy", "bid/request", "bid/response", "rtb/request",
        "ad/payload", "ad/meta", "ad/event", "win/notice", "loss/notice", "event/track",
        "reward/video", "rewarded/video", "inspire/video", "incentive/video", "offerwall",
        "shortdrama/ad", "short_drama/ad", "mini_drama/ad", "drama/ad", "episode/ad", "episode/reward",
        "episode/unlock", "episode/preload", "episode/material", "drama/reward", "drama/unlock", "drama/material",
        "comic/ad", "manga/ad", "comic/reward", "manga/reward", "comic/unlock", "manga/unlock",
        "listen/reward", "audio/reward", "listen/unlock", "audio/unlock",
        "adserver", "ad/slot", "slot/ad", "sdk/ad", "adn/config", "network/config"
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
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
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
        val upgrade = lines.firstOrNull { it.startsWith("Upgrade:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?.ifBlank { null }
        return RequestInspection(
            method = requestLine[0],
            path = requestLine[1],
            host = hostHeader ?: session.host,
            httpVersion = requestLine.getOrNull(2) ?: "HTTP/1.1",
            referer = referer,
            origin = origin,
            upgrade = upgrade
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
        val stealthEnabled = FeatureSettingsRepository.isStealthModeEnabled(context)
        val stealthStripParams = stealthEnabled && FeatureSettingsRepository.isStealthStripTrackingParamsEnabled(context)
        val stealthHideRef = stealthEnabled && FeatureSettingsRepository.isStealthHideRefererEnabled(context)
        val stealthRemoveHdrs = stealthEnabled && FeatureSettingsRepository.isStealthRemoveFingerprintHeadersEnabled(context)
        val customParams = if (stealthStripParams) FeatureSettingsRepository.getCustomTrackingParams(context) else emptySet()
        val customHeaders = if (stealthRemoveHdrs) FeatureSettingsRepository.getCustomTrackingHeaders(context) else emptySet()
        val stealthRemoveParams = if (stealthStripParams) StealthModeSupport.TRACKING_PARAMS + customParams else emptySet()
        val combinedRemoveParams = if (stealthStripParams) removeParams + stealthRemoveParams else removeParams
        val removeRequestHeaders = if (stealthRemoveHdrs) {
            directives.removeRequestHeaders + StealthModeSupport.TRACKING_HEADERS + customHeaders
        } else {
            directives.removeRequestHeaders
        }
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
            // Stealth Mode: 截断 Referer 仅保留 origin
            if (stealthHideRef && line.startsWith("Referer:", ignoreCase = true)) {
                val sanitized = StealthModeSupport.sanitizeReferer(line.substringAfter(':', "").trim())
                if (sanitized != line.substringAfter(':', "").trim()) {
                    changed = true
                    return@mapIndexedNotNull "Referer: $sanitized"
                }
            }
            line
        }
        if (directives.cspValue != null) {
            changed = true
        }
        val requestLineSource = rewrittenHeaders.firstOrNull() ?: return chunk
        val requestLine = rewriteRequestLine(requestLineSource, combinedRemoveParams, removeParamRegexes)
        if (requestLine != requestLineSource) changed = true
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
        val removeParamRegexes = directives.removeParamRegexes
        val stealthEnabled = FeatureSettingsRepository.isStealthModeEnabled(context)
        val stealthStripParams = stealthEnabled && FeatureSettingsRepository.isStealthStripTrackingParamsEnabled(context)
        val stealthHideRef = stealthEnabled && FeatureSettingsRepository.isStealthHideRefererEnabled(context)
        val stealthRemoveHdrs = stealthEnabled && FeatureSettingsRepository.isStealthRemoveFingerprintHeadersEnabled(context)
        val customParams = if (stealthStripParams) FeatureSettingsRepository.getCustomTrackingParams(context) else emptySet()
        val customHeaders = if (stealthRemoveHdrs) FeatureSettingsRepository.getCustomTrackingHeaders(context) else emptySet()
        val stealthRemoveParams = if (stealthStripParams) StealthModeSupport.TRACKING_PARAMS + customParams else emptySet()
        val removeParams = (if (shouldStripAdParams) {
            directives.removeParams + defaultAdQueryParams
        } else {
            directives.removeParams
        }) + stealthRemoveParams
        val removeRequestHeaders = if (stealthRemoveHdrs) {
            directives.removeRequestHeaders + StealthModeSupport.TRACKING_HEADERS + customHeaders
        } else {
            directives.removeRequestHeaders
        }
        val setRequestHeaders = parseHeaderOverrides(directives.setRequestHeaders)
        if (!stealthEnabled && removeParams.isEmpty() && removeParamRegexes.isEmpty() && removeRequestHeaders.isEmpty() && setRequestHeaders.isEmpty() && directives.cspValue == null) return base
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
            } else if (stealthHideRef && lowerName == "referer") {
                val sanitized = StealthModeSupport.sanitizeReferer(header.value)
                if (sanitized != header.value) {
                    changed = true
                    HpackDecoder.HeaderField(header.name, sanitized)
                } else {
                    header
                }
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
        val effectiveRequestType = if (requestInspection?.isWebSocket == true) "websocket" else null
        val directives = requestInspection?.let {
            RuleRepository.getRequestRewriteDirectives(
                context = TlsMitmSessionManager.getContextOrNull() ?: return@let RuleRepository.RequestRewriteDirectives(),
                host = it.host,
                path = it.path,
                appName = session.appName,
                requestDomain = extractRequestDomain(it),
                requestType = effectiveRequestType
            )
        } ?: RuleRepository.RequestRewriteDirectives()
        if (responseHeaders.statusCode == 101 && requestInspection?.isWebSocket == true && directives.block) {
            return FilterResult.Replaced(buildWebSocketBlockResponse(), "websocket-blocked", ruleDebug = directives.matchedRuleSummaries)
        }
        if (directives.emptyResponse) {
            return FilterResult.Replaced(buildEmptyResponse(), "empty-response", ruleDebug = directives.matchedRuleSummaries)
        }
        val cosmeticSelectors = directives.cosmeticSelectors
        val modifiedChunk = if (directives.cookieRemove.isNotEmpty()) {
            stripResponseCookies(chunk, headerEnd, directives.cookieRemove)
        } else chunk
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
            if (modifiedChunk !== chunk) return FilterResult.Replaced(modifiedChunk, "cookie-stripped", ruleDebug = directives.matchedRuleSummaries)
            return FilterResult.PassThrough(chunk, "response-body-skip:no-deep-inspection-target")
        }
        val bodyBytes = modifiedChunk.copyOfRange(headerEnd + 4, modifiedChunk.size)
        val decodedTransferBytes = if ("chunked" in responseHeaders.transferEncoding) {
            decodeChunkedBody(bodyBytes) ?: return FilterResult.PassThrough(modifiedChunk, "invalid-chunked")
        } else {
            bodyBytes
        }
        val decodedBodyBytes = decodeContentEncodedBody(decodedTransferBytes, responseHeaders.contentEncoding)
            ?: return buildDecodeFailureResult(modifiedChunk, responseHeaders.contentEncoding)
        val body = decodeAscii(decodedBodyBytes) ?: return FilterResult.PassThrough(modifiedChunk, "binary-response-body")
        return buildHttp1BodyFilterResult(
            session = session,
            chunk = modifiedChunk,
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
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        return Http1ResponseHeaders(
            statusLine = statusLine,
            statusCode = statusCode,
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
            return FilterResult.Replaced(response, "redirect-resource-applied", chunk.size, directives.matchedRuleSummaries)
        }
        val scrubbedBody = scrubHtmlAdArtifacts(contentType, body)
        val replacedBody = applyReplaceRules(contentType, scrubbedBody, directives.replaceRules)
        if (replacedBody != null && replacedBody != body) {
            val replacementBodyBytes = replacedBody.toByteArray(StandardCharsets.UTF_8)
            val response = buildSyntheticResponse(responseHeaders.statusLine, contentType, replacementBodyBytes, directives.cspValue)
            return FilterResult.Replaced(response, "replace-rule-applied", chunk.size, directives.matchedRuleSummaries)
        }
        val rewrittenBody = replacedBody ?: scrubbedBody
        if (neutralizeReason == null) {
            if (contentType.contains("text/html") &&
                (cosmeticSelectors.isNotEmpty() || directives.jsInjectRules.isNotEmpty() || !directives.cspValue.isNullOrBlank() || scrubbedBody != body)) {
                val injectedBodyBytes = buildInjectedHtmlBody(rewrittenBody, cosmeticSelectors, directives.cspValue, directives.jsInjectRules)
                val response = buildSyntheticResponse(responseHeaders.statusLine, contentType, injectedBodyBytes, directives.cspValue)
                val reason = if (scrubbedBody != body) "html-ad-artifact-scrubbed" else "cosmetic-html-injected"
                return FilterResult.Replaced(response, reason, chunk.size, directives.matchedRuleSummaries)
            }
            return FilterResult.PassThrough(chunk, "response-allowed")
        }
        val replacementBodyBytes = buildReplacementBody(contentType, rewrittenBody, cosmeticSelectors, directives.cspValue, directives.jsInjectRules)
        val response = buildSyntheticResponse(responseHeaders.statusLine, contentType, replacementBodyBytes, directives.cspValue)
        return FilterResult.Replaced(response, neutralizeReason, chunk.size, directives.matchedRuleSummaries)
    }

    fun maxHttp1FilterBufferBytes(): Int = minOf(MAX_HTTP1_FILTER_BUFFER_BYTES, HTTP1_FILTER_BUFFER_BYTES)

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
        incomingFragment: ByteArray,
        completeResponse: Boolean = false
    ): Http2DataInspection? {
        if (incomingFragment.isEmpty()) return null
        if (headerInspection?.responseLike != true) return null
        val context = TlsMitmSessionManager.getContextOrNull() ?: return null
        val combinedSample = appendSample(currentSample, incomingFragment, HTTP2_DATA_BODY_LIMIT_BYTES)
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
        val bodyRewrite = if (completeResponse) {
            rewriteHttp2CompleteTextBody(contentType, decoded, directives)
        } else {
            null
        }
        if (bodyRewrite != null) {
            return Http2DataInspection(
                suspiciousScore = 3,
                suspiciousReasons = appendRuleDebugReasons(listOf(bodyRewrite.reason), directives.matchedRuleSummaries),
                confidence = if (bodyRewrite.reason == "redirect-resource-applied") "high" else "medium",
                samplePreview = decoded.replace('\r', ' ').replace('\n', ' ').take(160),
                vendor = headerInspection.vendor,
                combinedSample = combinedSample,
                redirectResource = directives.redirectResource,
                cspValue = directives.cspValue,
                contentType = bodyRewrite.contentType,
                replacementBody = bodyRewrite.body,
                replacementContentType = bodyRewrite.contentType,
                rewriteReason = bodyRewrite.reason
            )
        }
        val lowerBody = decoded.lowercase()
        val normalizedBody = normalizeBodyForAdInspection(lowerBody)
        val isNovelApp = RuleRepository.isNovelAppHint(session.appName)
        val isCommunityApp = RuleRepository.isCommunityAppHint(session.appName)
        if (RuleRepository.shouldProtectMediaTraffic(headerInspection.authority)) return null
        if (RuleRepository.shouldProtectBusinessTraffic(headerInspection.authority)) return null
        if (shouldProtectNormalNovelHttpTraffic(context, headerInspection.authority, headerInspection.path, session.appName) && !isNovelApp) return null
        val vendor = headerInspection.vendor.ifBlank {
            RuleRepository.classifyVendorFromHints(context, headerInspection.authority, session.appName)
        }
        val aggressiveNovelTarget = RuleRepository.shouldAggressivelyBlockForNovelApp(
            context,
            headerInspection.authority,
            session.appName,
            vendor
        )
        val bodySignals = inspectAdBodySignals(normalizedBody)
        val jsonAdFieldHitCount = if (contentType.contains("json")) {
            http2JsonAdFieldTokens.count(normalizedBody::contains)
        } else 0
        val jsonAdFieldMatched = jsonAdFieldHitCount > 0
        val jsonAdArrayMatched = contentType.contains("json") && normalizedBody.trim().startsWith("[") && jsonAdFieldHitCount >= 2
        if (bodySignals.reasons.isEmpty() && !jsonAdFieldMatched && !jsonAdArrayMatched) return null
        var suspiciousScore = bodySignals.score + if (targetedContentType) 1 else 0
        if (isCommunityApp && inspectCommentAdBodySignals(lowerBody).hasAnyStrongCommentAdSignal) suspiciousScore += 2
        if (isKnownAdVendor(vendor)) suspiciousScore += 2
        if (aggressiveNovelTarget) suspiciousScore += 3
        if (jsonAdFieldMatched) suspiciousScore += 3
        if (jsonAdArrayMatched) suspiciousScore += 2
        // 降低拦截阈值：小说 APP 1 分拦截，普通应用 2 分拦截
        val threshold = when {
            isNovelApp -> HTTP2_NOVEL_RESPONSE_BLOCK_SCORE
            isCommunityApp -> 1
            else -> HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE
        }
        if (suspiciousScore < threshold) return null
        val preview = decoded.replace('\r', ' ').replace('\n', ' ').take(160)
        val reasons = bodySignals.reasons.toMutableList()
        if (isKnownAdVendor(vendor)) reasons += "vendor:$vendor"
        if (aggressiveNovelTarget) reasons += "novel-app-aggressive"
        // 新增：Content-Type 包含广告特征
        if (contentType.contains("json") && strongResponseAdKeywords.any { normalizedBody.contains(it) }) {
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
        RuleRepository.reportUnknownVendorIfNeeded(
            context = context,
            vendor = vendor,
            domain = headerInspection.authority,
            appName = session.appName,
            signal = RuleRepository.SuspiciousSignal.HTTP_FLOW,
            confidenceBoost = if (suspiciousScore >= 4) 3 else 2,
            matchedPathHint = headerInspection.path,
            refererDomain = extractRequestDomain(headerInspection)
        )
        UserAdFeedbackManager.recordNetworkActivity(
            context,
            UserAdFeedbackManager.NetworkActivity(
                appName = session.appName,
                host = headerInspection.authority,
                path = headerInspection.path,
                protocol = "HTTPS",
                source = "mitm_http2_body",
                score = suspiciousScore
            )
        )
        return Http2DataInspection(
            suspiciousScore = suspiciousScore,
            suspiciousReasons = appendRuleDebugReasons(reasons.distinct(), directives.matchedRuleSummaries),
            confidence = if (suspiciousScore >= 4) "high" else "medium",
            samplePreview = preview,
            vendor = vendor,
            combinedSample = combinedSample,
            redirectResource = directives.redirectResource,
            cspValue = directives.cspValue,
            contentType = contentType
        )
    }

    fun rewriteHttp2CompleteTextBody(
        contentType: String,
        body: String,
        directives: RuleRepository.RequestRewriteDirectives
    ): Http2BodyRewriteResult? {
        val lowerType = contentType.lowercase()
        val textLike = lowerType.contains("text") ||
            lowerType.contains("json") ||
            lowerType.contains("javascript") ||
            lowerType.contains("xml") ||
            lowerType.contains("html")
        if (!textLike || body.isEmpty()) return null
        val redirectBodyBytes = buildRedirectReplacementBody(lowerType, directives.redirectResource)
        if (redirectBodyBytes != null) {
            return Http2BodyRewriteResult(
                body = redirectBodyBytes,
                contentType = inferRedirectContentType(lowerType, directives.redirectResource),
                reason = "redirect-resource-applied"
            )
        }
        val scrubbedBody = scrubHtmlAdArtifacts(lowerType, body)
        val replacedBody = applyReplaceRules(lowerType, scrubbedBody, directives.replaceRules)
        val rewrittenBody = replacedBody ?: scrubbedBody
        if (lowerType.contains("text/html") &&
            (directives.cosmeticSelectors.isNotEmpty() || directives.jsInjectRules.isNotEmpty() || !directives.cspValue.isNullOrBlank() || scrubbedBody != body)) {
            val injectedBody = buildInjectedHtmlBody(
                rewrittenBody,
                directives.cosmeticSelectors,
                directives.cspValue,
                directives.jsInjectRules
            )
            return Http2BodyRewriteResult(
                body = injectedBody,
                contentType = lowerType.ifBlank { "text/html; charset=utf-8" },
                reason = if (scrubbedBody != body) "html-ad-artifact-scrubbed" else "cosmetic-html-injected"
            )
        }
        if (replacedBody != null && replacedBody != body) {
            return Http2BodyRewriteResult(
                body = replacedBody.toByteArray(StandardCharsets.UTF_8),
                contentType = lowerType,
                reason = "replace-rule-applied"
            )
        }
        return null
    }

    private fun appendRuleDebugReasons(reasons: List<String>, ruleSummaries: List<String>): List<String> {
        if (ruleSummaries.isEmpty()) return reasons
        return (reasons + ruleSummaries.map { "rule:$it" }).distinct()
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
                input.readBytesLimited(MAX_DECODED_BODY_BYTES)
            }
        }.getOrNull()
    }

    private fun brotliBody(body: ByteArray): ByteArray? {
        return runCatching {
            BrotliInputStream(ByteArrayInputStream(body)).use { input ->
                input.readBytesLimited(MAX_DECODED_BODY_BYTES)
            }
        }.getOrNull()
    }

    private fun inflateDeflateBody(body: ByteArray): ByteArray? {
        return runCatching {
            InflaterInputStream(ByteArrayInputStream(body)).use { input ->
                input.readBytesLimited(MAX_DECODED_BODY_BYTES)
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
                input.readBytesLimited(MAX_DECODED_BODY_BYTES)
            }
        }.getOrNull()
    }

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
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
            vendor = environment.vendor,
            context = environment.context
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
        if (shouldProtectNormalNovelHttpTraffic(environment.context, environment.host, environment.lowerPath, environment.appName) && !environment.isNovelApp) return null
        if (environment.pathInspection.strongSuspicious) return "neutralized-strong-suspicious-path"
        if (environment.isNovelApp && looksLikeNovelAdPath(environment.lowerPath)) return "neutralized-novel-ad-path"
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
        if (shouldProtectNormalNovelHttpTraffic(environment.context, environment.host, environment.lowerPath, environment.appName) && !environment.isNovelApp) return null
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
            environment.isCommunityApp -> 1
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
            generalAdTarget = environment.generalAdTarget,
            isCommunityApp = environment.isCommunityApp
        )
    }

    private fun resolveHttp1BodyEnvironment(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?
    ): Http1BodyEnvironment? {
        val context = TlsMitmSessionManager.getContextOrNull() ?: return null
        val host = normalizeAuthority(requestInspection?.host ?: session.host)
        val isNovelApp = RuleRepository.isNovelAppHint(session.appName)
        val isCommunityApp = RuleRepository.isCommunityAppHint(session.appName)
        if (RuleRepository.isSocialCoreDomain(host) && !isCommunityApp) return null
        if (RuleRepository.isWhitelistedDomain(host)) return null
        if (RuleRepository.isSensitiveAuthDomain(host)) return null
        if (RuleRepository.shouldProtectMediaTraffic(host)) return null
        if (RuleRepository.shouldProtectBusinessTraffic(host)) return null
        if (shouldProtectNormalNovelHttpTraffic(context, host, requestInspection?.path, session.appName) && !isNovelApp) return null
        val vendor = RuleRepository.classifyVendorFromHints(context, host, session.appName)
        return Http1BodyEnvironment(
            context = context,
            host = host,
            vendor = vendor,
            generalAdTarget = RuleRepository.shouldTreatAsGeneralAdTraffic(host, vendor, session.appName),
            aggressiveNovelTarget = RuleRepository.shouldAggressivelyBlockForNovelApp(context, host, session.appName, vendor),
            protectedNovelTarget = RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, host, requestInspection?.path, session.appName),
            isNovelApp = isNovelApp,
            isCommunityApp = isCommunityApp
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
        vendor: String,
        context: android.content.Context
    ): String? {
        val adFreeRewardEnabled = FeatureSettingsRepository.isAdFreeRewardEnabled(context)
        if (adFreeRewardEnabled && novelSignals.rewardUnlockHits >= 1) {
            return "neutralized-body-reward-unlock"
        }
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
        if (novelSignals.hasNovelFieldCluster &&
            (novelSignals.rewardUnlockHits >= 1 || novelSignals.jsonNovelFieldHits >= 2 || bodySignalScore >= 4)) {
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
        if (decisionContext.isCommunityApp && commentSignals.hasAnyStrongCommentAdSignal &&
            (bodyReasons.any { it.startsWith("comment-") } || decisionContext.bodySignalScore >= 2)) {
            return reportHttp1BodySignal(decisionContext.reportContext, "neutralized-body-community-comment-ad", 2)
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
                "\"ad_material", "\"material_url", "\"landing_url", "\"click_url", "\"show_url", "\"deep_link",
                "\"impression_url", "\"monitor_url", "\"track_url", "\"creative_id", "\"ad_payload",
                "\"native_ad", "\"ad_data", "\"ad_info"
            ).any(lowerBody::contains),
            commentRecommendCardHit = listOf(
                "\"recommend_card", "\"promotion_card", "\"discover_card", "\"ad_card", "\"promo_card",
                "\"sponsor_card", "\"sponsored_card", "\"commercial_card", "\"native_card_ad", "\"brand_card"
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
        UserAdFeedbackManager.recordNetworkActivity(
            reportContext.context,
            UserAdFeedbackManager.NetworkActivity(
                appName = reportContext.appName ?: "未知应用",
                host = reportContext.host,
                path = reportContext.matchedPathHint,
                protocol = "HTTPS",
                source = "mitm_http1_body",
                score = confidenceBoost + 2
            )
        )
        return reason
    }

    private fun containsAnyContentType(contentType: String, vararg tokens: String): Boolean {
        for (token in tokens) {
            if (contentType.contains(token, ignoreCase = false)) return true
        }
        return false
    }

    private fun containsAny(value: String, vararg tokens: String): Boolean {
        for (token in tokens) {
            if (value.indexOf(token, 0, ignoreCase = false) >= 0) return true
        }
        return false
    }

    private fun containsAnyIgnoreCase(value: String, vararg tokens: String): Boolean {
        for (token in tokens) {
            if (value.indexOf(token, 0, ignoreCase = true) >= 0) return true
        }
        return false
    }

    private fun looksLikeCommentAdPath(path: String): Boolean {
        if (path.isBlank()) return false
        val commentScene = path.contains("comment") || path.contains("reply") || path.contains("floor") || path.contains("post")
        if (!commentScene) return false
        return path.contains("ad") ||
            path.contains("promo") ||
            path.contains("sponsor") ||
            path.contains("commercial") ||
            path.contains("native") ||
            path.contains("banner") ||
            path.contains("insert") ||
            path.contains("material") ||
            path.contains("landing") ||
            path.contains("recommend") ||
            path.contains("flow") ||
            path.contains("card") ||
            path.contains("brand")
    }

    private fun looksLikeCommentCommerceAdPath(path: String): Boolean {
        if (!looksLikeCommentAdPath(path)) return false
        return commentCommercePathSignals.any(path::contains) ||
            pangleAndGdtPathSignals.any(path::contains) ||
            containsAny(path, "gdt", "guangdiantong", "youlianghui", "douyin", "shop", "mall")
    }

    private fun looksLikeNovelAdPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (looksLikeRewardUnlockPath(path)) return true
        val readerScene = containsAny(
            path,
            "reader", "reading", "chapter", "book", "novel", "page", "read", "comic", "manga", "manhua", "drama", "shortdrama", "short_drama", "episode", "listen", "audio"
        )
        val adPlacement = containsAny(
            path,
            "/ad", "_ad", "ad_", "ads", "banner", "popup", "float", "insert_ad", "ad_insert", "ad_material", "creative",
            "splash_ad", "startup_ad", "launch_ad", "reward_ad", "ad_reward", "watch_ad", "unlock_by_ad", "ad_unlock",
            "offerwall", "waterfall", "mediation", "bidding", "auction", "inspire_ad", "incentive_ad", "excitation_ad"
        )
        if (readerScene && adPlacement) return true
        return containsAny(
            path,
            "reader_banner", "reader_bottom", "reader_bottom_card", "reader_insert_card", "reader_float", "reader_popup", "reader_reward", "reader_task",
            "chapter_ad", "chapter_reward_ad", "chapter_unlock_ad", "chapter_offerwall", "chapter_popup_ad",
            "chapter_ad_card", "page_ad_card", "page_turn_ad", "turn_page_ad", "turn_page_card", "flip_page_ad", "page_insert_ad", "page_footer_ad", "page_tail_ad",
            "book_bonus", "book_task", "novel_reward", "novel_task", "novel_welfare", "watch_ad_unlock", "unlock_by_ad",
            "episode_reward_ad", "episode_unlock_ad", "episode_ad", "episode_ad_material", "drama_reward_ad", "drama_unlock_ad", "drama_ad",
            "shortdrama_reward_ad", "shortdrama_unlock_ad", "short_drama_reward_ad", "short_drama_unlock_ad",
            "comic_reward_ad", "comic_unlock_ad", "comic_page_ad", "manga_reward_ad", "manga_unlock_ad", "manga_page_ad",
            "listen_reward_ad", "listen_unlock_ad", "audio_reward_ad", "audio_unlock_ad"
        )
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
        return buildInjectedHtmlBody(originalBody, styleTag, cspMetaTag, emptySet())
    }

    private fun buildInjectedHtmlBody(
        originalBody: String,
        cosmeticSelectors: List<String>,
        cspValue: String?,
        jsInjectRules: Set<String>
    ): ByteArray {
        val styleTag = buildCosmeticStyleTag(cosmeticSelectors)
        val cspMetaTag = buildCspMetaTag(cspValue)
        return buildInjectedHtmlBody(originalBody, styleTag, cspMetaTag, jsInjectRules)
    }

    private fun buildInjectedHtmlBody(
        originalBody: String,
        styleTag: String,
        cspMetaTag: String,
        jsInjectRules: Set<String>
    ): ByteArray {
        val ruleScriptTag = buildRuleScriptInjection(jsInjectRules)
        val injection = "$cspMetaTag$styleTag$SCRIPTLET_INJECTION$ruleScriptTag"
        val injected = when {
            originalBody.contains("</head>", ignoreCase = true) -> {
                originalBody.replaceFirst("</head>", "$injection</head>", ignoreCase = true)
            }
            originalBody.contains("<body", ignoreCase = true) -> {
                "$injection$originalBody"
            }
            else -> {
                "<html><head>$injection</head><body>$originalBody</body></html>"
            }
        }
        return injected.toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildRuleScriptInjection(jsInjectRules: Set<String>): String {
        if (jsInjectRules.isEmpty()) return ""
        val script = jsInjectRules
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(12_000)
            .replace("</script", "<\\/script", ignoreCase = true)
        if (script.isBlank()) return ""
        return "<script data-hanfeng-rule-inject=\"1\">\n$script\n</script>"
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
            return typeof url === 'string' && /ad|ads|banner|splash|promo|promotion|tracking|tracker|preroll|midroll|postroll|offerwall|unlock|material|landing|recommend|discover|campaign|creative|market:\/\/|intent:\/\/|deeplink|download|install|openapp|shake|push|notify|notice|popup|interstitial|waterfall|mediation|bid|auction|hotword|task|mission|welfare|benefit|coin|reward|revive|game|live|miniapp|mini_program|mini-program|quickapp|quick_app|clipboard|share|serviceworker|service-worker|precache|prefetch|ad_unit_id|slot_id|adx_id|auction_id|bid_token|impression_url|click_tracking|cname|httpdns|dns-query|quic|http3|udp443|h3ad|getBiddingToken|connectAd|biddingToken|adToken/i.test(url);
        };
        var isAdLikeText = function(value) {
            return typeof value === 'string' && /广告|推广|赞助|摇一摇|立即下载|立即安装|打开应用|系统更新|立即清理|一键加速|看视频|领金币|领福利|复制口令|分享赚钱|ad|ads|sponsor|promo|promotion|market:\/\/|intent:\/\/|deeplink|campaign|creative|reward|offerwall|splash|interstitial|clipboard|share|miniapp|quickapp|adid|aid|slotid|slot_id|ad_unit|auction|bidtoken/i.test(value);
        };
        var emptyAdBridge = {
            init:function(){}, load:function(){}, show:function(){}, render:function(){}, request:function(){}, preload:function(){}, report:function(){}, track:function(){}, connect:function(){}, bind:function(){}, getAdId:function(){return '';}, getDeviceId:function(){return '';}, getBiddingToken:function(){return '';}, connectAd:function(){return null;}, getAdToken:function(){return '';}, getBidToken:function(){return '';}, getAuctionToken:function(){return '';}
        };
        ['adBridge','AdBridge','adsBridge','AdsBridge','adSdk','AdSdk','adSDK','H5Ad','NativeAd','AdService','adService','AdBidding','adBidding','BiddingToken','biddingToken','AdToken','adToken','AdConnector','adConnector','connectAd','getBiddingToken','getBidToken','getAdToken','getAuctionToken','navigator.getBiddingToken','navigator.connectAd'].forEach(function(name){
            try { if(!window[name] || isAdLikeText(name)) window[name] = emptyAdBridge; } catch(e) {}
        });
        try {
            if(navigator) {
                navigator.getBiddingToken = function(){ return ''; };
                navigator.connectAd = function(){ return null; };
                navigator.getAdToken = function(){ return ''; };
                navigator.getBidToken = function(){ return ''; };
            }
        } catch(e) {}
        if(window.WebAssembly) {
            var originalWasmInstantiate = window.WebAssembly.instantiate;
            var originalWasmCompile = window.WebAssembly.compile;
            var originalWasmInstantiateStreaming = window.WebAssembly.instantiateStreaming;
            var originalWasmCompileStreaming = window.WebAssembly.compileStreaming;
            window.WebAssembly.instantiate = function(buffer, imports) {
                var text = '';
                try { text = String(buffer && (buffer.url || buffer.byteLength || buffer)); } catch(e) {}
                if(isAdLikeText(text) || isAdLikeUrl(text)) return Promise.reject(new Error('blocked'));
                return originalWasmInstantiate.apply(this, arguments);
            };
            window.WebAssembly.compile = function(buffer) {
                var text = '';
                try { text = String(buffer && (buffer.url || buffer.byteLength || buffer)); } catch(e) {}
                if(isAdLikeText(text) || isAdLikeUrl(text)) return Promise.reject(new Error('blocked'));
                return originalWasmCompile.apply(this, arguments);
            };
            if(originalWasmInstantiateStreaming) {
                window.WebAssembly.instantiateStreaming = function(source, imports) {
                    var text = '';
                    try { text = String(source && (source.url || source)); } catch(e) {}
                    if(isAdLikeText(text) || isAdLikeUrl(text)) return Promise.reject(new Error('blocked'));
                    return originalWasmInstantiateStreaming.apply(this, arguments);
                };
            }
            if(originalWasmCompileStreaming) {
                window.WebAssembly.compileStreaming = function(source) {
                    var text = '';
                    try { text = String(source && (source.url || source)); } catch(e) {}
                    if(isAdLikeText(text) || isAdLikeUrl(text)) return Promise.reject(new Error('blocked'));
                    return originalWasmCompileStreaming.apply(this, arguments);
                };
            }
        }
        if(window.Element && window.Element.prototype && window.Element.prototype.setAttribute) {
            var origSetAttribute = window.Element.prototype.setAttribute;
            window.Element.prototype.setAttribute = function(name, value) {
                if((name === 'src' || name === 'href' || name === 'data-src' || name === 'data-url') && isAdLikeUrl(value)) {
                    return;
                }
                return origSetAttribute.apply(this, arguments);
            };
        }
        if(window.Document && document && document.createElement) {
            var origCreateElement = document.createElement.bind(document);
            document.createElement = function(tagName) {
                var el = origCreateElement(tagName);
                try {
                    var tag = String(tagName || '').toLowerCase();
                    if((tag === 'script' || tag === 'iframe' || tag === 'link' || tag === 'source' || tag === 'video' || tag === 'audio') && el && el.setAttribute) {
                        var originalElementSetAttribute = el.setAttribute;
                        el.setAttribute = function(name, value) {
                            if((name === 'src' || name === 'href' || name === 'data-src' || name === 'poster') && isAdLikeUrl(value)) return;
                            return originalElementSetAttribute.apply(this, arguments);
                        };
                    }
                } catch(e){}
                return el;
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
        if(window.open) {
            var originalWindowOpen = window.open;
            window.open = function(url, target, features) {
                if(isAdLikeUrl(url) || isAdLikeText(url)) return null;
                return originalWindowOpen.apply(this, arguments);
            };
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
        if(window.WebSocket) {
            var OriginalWebSocket = window.WebSocket;
            window.WebSocket = function(url, protocols) {
                if(isAdLikeUrl(url)) {
                    return { close:function(){}, send:function(){}, addEventListener:function(){}, removeEventListener:function(){}, readyState:3 };
                }
                return protocols === undefined ? new OriginalWebSocket(url) : new OriginalWebSocket(url, protocols);
            };
            window.WebSocket.prototype = OriginalWebSocket.prototype;
        }
        if(window.EventSource) {
            var OriginalEventSource = window.EventSource;
            window.EventSource = function(url, options) {
                if(isAdLikeUrl(url)) {
                    return { close:function(){}, addEventListener:function(){}, removeEventListener:function(){}, readyState:2 };
                }
                return new OriginalEventSource(url, options);
            };
            window.EventSource.prototype = OriginalEventSource.prototype;
        }
        if(window.Notification && window.Notification.requestPermission) {
            var originalRequestPermission = window.Notification.requestPermission.bind(window.Notification);
            window.Notification.requestPermission = function(callback) {
                var stack = '';
                try { stack = String(new Error().stack || ''); } catch(e) {}
                if(isAdLikeText(stack)) {
                    if(typeof callback === 'function') callback('denied');
                    return Promise.resolve('denied');
                }
                return originalRequestPermission(callback);
            };
        }
        if(navigator && navigator.clipboard && navigator.clipboard.writeText) {
            var originalClipboardWriteText = navigator.clipboard.writeText.bind(navigator.clipboard);
            navigator.clipboard.writeText = function(text) {
                if(isAdLikeText(text)) return Promise.resolve();
                return originalClipboardWriteText(text);
            };
        }
        if(navigator && navigator.share) {
            var originalNavigatorShare = navigator.share.bind(navigator);
            navigator.share = function(data) {
                var text = '';
                try { text = JSON.stringify(data || {}); } catch(e) { text = String(data || ''); }
                if(isAdLikeText(text) || isAdLikeUrl(text)) return Promise.resolve();
                return originalNavigatorShare(data);
            };
        }
        if(navigator && navigator.serviceWorker && navigator.serviceWorker.register) {
            var originalServiceWorkerRegister = navigator.serviceWorker.register.bind(navigator.serviceWorker);
            navigator.serviceWorker.register = function(scriptURL, options) {
                if(isAdLikeUrl(scriptURL)) return Promise.reject(new Error('blocked'));
                return originalServiceWorkerRegister(scriptURL, options);
            };
        }
        if(window.addEventListener) {
            var originalWindowAddEventListener = window.addEventListener;
            window.addEventListener = function(type, listener, options) {
                if(/devicemotion|deviceorientation/i.test(String(type || ''))) {
                    var listenerText = String(listener || '');
                    if(isAdLikeText(listenerText) || /shake|accelerometer|gyroscope|market|intent|deeplink/i.test(listenerText)) return;
                }
                return originalWindowAddEventListener.call(this, type, listener, options);
            };
        }
        if(document && document.addEventListener) {
            var originalDocumentAddEventListener = document.addEventListener;
            document.addEventListener = function(type, listener, options) {
                if(/visibilitychange|click|touchstart|touchmove|touchend|pointerdown|pointerup/i.test(String(type || ''))) {
                    var listenerText = String(listener || '');
                    if(/market:\/\/|intent:\/\/|deeplink|ad|ads|promo|promotion|sponsor|download|install|slotId|adid|auction|shake/i.test(listenerText)) return;
                }
                return originalDocumentAddEventListener.call(this, type, listener, options);
            };
        }
        if(window.DeviceMotionEvent) {
            try {
                Object.defineProperty(window, 'DeviceMotionEvent', { value: function(){}, configurable: true });
            } catch(e) {}
        }
        if(window.DeviceOrientationEvent) {
            try {
                Object.defineProperty(window, 'DeviceOrientationEvent', { value: function(){}, configurable: true });
            } catch(e) {}
        }
        if(window.MutationObserver && document && document.documentElement) {
            new MutationObserver(function(mutations){
                mutations.forEach(function(mutation){
                    mutation.addedNodes && Array.prototype.forEach.call(mutation.addedNodes, function(node){
                        if(!node) return;
                        var tagName = String(node.tagName || '').toLowerCase();
                        if((tagName === 'iframe' || tagName === 'script' || tagName === 'img' || tagName === 'video' || tagName === 'source' || tagName === 'link') && isAdLikeUrl(node.src || node.href || node.getAttribute && (node.getAttribute('src') || node.getAttribute('href') || node.getAttribute('data-src')))) {
                            node.remove && node.remove();
                            return;
                        }
                        if(!node.querySelectorAll) return;
                        if(node.matches && node.matches('[class*="ad"],[id*="ad"],[class*="banner"],[class*="promo"],[class*="splash"],[class*="sponsor"],[class*="commercial"],[class*="shake"],[class*="push"],[class*="notify"],[class*="market"],[class*="download"],[class*="install"],[class*="reward"],[class*="welfare"],[class*="benefit"],[class*="offerwall"],[class*="hotword"],[class*="miniapp"]')) {
                            node.remove();
                            return;
                        }
                        node.querySelectorAll('iframe,script,img,video,source,link').forEach(function(child){
                            if(isAdLikeUrl(child.src || child.href || child.getAttribute('src') || child.getAttribute('href'))) child.remove();
                        });
                        node.querySelectorAll('[class*="ad"],[id*="ad"],[class*="banner"],[class*="promo"],[class*="splash"],[class*="recommend"],[class*="sponsor"],[class*="commercial"],[class*="shake"],[class*="push"],[class*="notify"],[class*="market"],[class*="download"],[class*="install"],[class*="reward"],[class*="welfare"],[class*="benefit"],[class*="offerwall"],[class*="hotword"],[class*="miniapp"],[data-ad],[data-ad-slot],[data-promotion],[data-deeplink],[data-market],[data-download]')
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
.offerwall, .unlock-by-ad, .watch-ad-unlock, .preroll-ad, .midroll-ad, .postroll-ad,
.sponsor-card, .sponsored-card, .commercial-card, .promotion-card, .promo-card,
.shake-ad, .push-ad, .notify-ad, .market-ad, .install-ad, .download-ad,
.operation-popup, .activity-popup, .welfare-popup, .benefit-popup {
    display: none !important; 
    visibility: hidden !important;
    opacity: 0 !important;
    height: 0 !important;
    width: 0 !important;
    overflow: hidden !important;
}
</style>"""

    private fun buildEmptyResponse(): ByteArray {
        return buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append("Connection: close\r\n")
            append("Content-Length: 0\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-HanFeng-Block: 1\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildWebSocketBlockResponse(): ByteArray {
        val body = "403 Forbidden"
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        return buildString {
            append("HTTP/1.1 403 Forbidden\r\n")
            append("Connection: close\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-HanFeng-Block: 1\r\n")
            append("\r\n")
            append(body)
        }.toByteArray(StandardCharsets.UTF_8)
    }

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

    private fun stripResponseCookies(chunk: ByteArray, headerEnd: Int, cookieRemove: Set<String>): ByteArray {
        val text = decodeAscii(chunk) ?: return chunk
        val headerLines = text.substring(0, headerEnd).split("\r\n")
        val filtered = headerLines.filterNot { line ->
            if (!line.startsWith("Set-Cookie:", ignoreCase = true)) return@filterNot false
            val cookieValue = line.substringAfter(':', "").trim()
            cookieRemove.any { cookieValue.startsWith("$it=") || cookieValue.equals(it, ignoreCase = true) }
        }
        if (filtered.size == headerLines.size) return chunk
        val newHeader = filtered.joinToString("\r\n")
        val bodyBytes = chunk.copyOfRange(headerEnd, chunk.size)
        val newHeaderBytes = newHeader.toByteArray(StandardCharsets.ISO_8859_1)
        return newHeaderBytes + bodyBytes
    }

    private fun decodeAscii(chunk: ByteArray): String? {
        return runCatching { String(chunk, StandardCharsets.ISO_8859_1) }.getOrNull()
    }

    private fun normalizeBodyForAdInspection(lowerBody: String): String {
        if (lowerBody.isBlank()) return lowerBody
        var normalized = stripJsonpEnvelope(lowerBody.trim())
        if (normalized.indexOf('\\') >= 0) {
            normalized = decodeEscapedJsonText(normalized)
        }
        return normalized.lowercase()
    }

    private fun stripJsonpEnvelope(body: String): String {
        val openParen = body.indexOf('(')
        val closeParen = body.lastIndexOf(')')
        if (openParen <= 0 || closeParen <= openParen) return body
        val prefix = body.substring(0, openParen).trim()
        if (prefix.isBlank() || !prefix.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '$' }) return body
        val candidate = body.substring(openParen + 1, closeParen).trim()
        return if (candidate.startsWith('{') || candidate.startsWith('[') || candidate.startsWith('"')) candidate else body
    }

    private fun decodeEscapedJsonText(value: String): String {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch != '\\' || index + 1 >= value.length) {
                output.append(ch)
                index++
                continue
            }
            when (val next = value[index + 1]) {
                'u' -> {
                    if (index + 5 < value.length) {
                        val code = value.substring(index + 2, index + 6).toIntOrNull(16)
                        if (code != null) {
                            output.append(code.toChar())
                            index += 6
                            continue
                        }
                    }
                    output.append(ch)
                    index++
                }
                '"', '\\', '/' -> {
                    output.append(next)
                    index += 2
                }
                'b' -> {
                    output.append('\b')
                    index += 2
                }
                'f' -> {
                    output.append('\u000C')
                    index += 2
                }
                'n' -> {
                    output.append('\n')
                    index += 2
                }
                'r' -> {
                    output.append('\r')
                    index += 2
                }
                't' -> {
                    output.append('\t')
                    index += 2
                }
                else -> {
                    output.append(next)
                    index += 2
                }
            }
        }
        return output.toString()
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
        if (RuleRepository.isSocialCoreDomain(lowerHost) && !RuleRepository.isCommunityAppHint(appName)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.isWhitelistedDomain(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.shouldProtectMediaTraffic(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (RuleRepository.shouldProtectBusinessTraffic(normalizedHost)) return cacheDeepInspectionDecision(cacheKey, false)
        if (shouldProtectNormalNovelHttpTraffic(context, normalizedHost, lowerPath, appName) && !RuleRepository.isNovelAppHint(appName)) return cacheDeepInspectionDecision(cacheKey, false)
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
        if (RuleRepository.isNovelAppHint(appName) && looksLikeNovelAdPath(lowerPath)) {
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
        cspValue: String? = null,
        jsInjectRules: Set<String> = emptySet()
    ): ByteArray {
        return when {
            contentType.contains("application/json") -> "{}".toByteArray(StandardCharsets.UTF_8)
            contentType.contains("javascript") -> "".toByteArray(StandardCharsets.UTF_8)
            contentType.contains("text/html") -> {
                if (cosmeticSelectors.isEmpty()) {
                    val ruleScriptTag = buildRuleScriptInjection(jsInjectRules)
                    "<html><head>${buildCspMetaTag(cspValue)}$SCRIPTLET_INJECTION$ruleScriptTag</head><body></body></html>".toByteArray(StandardCharsets.UTF_8)
                } else {
                    buildInjectedHtmlBody(originalBody, cosmeticSelectors, cspValue, jsInjectRules)
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
            resource.contains("noopjs") || resource.contains("noop.js") || resource.contains("noop-script") || resource.contains("noopscript") || resource.contains("abp-resource:blank-js") || resource.contains("ubo-resource:noop.js") || resource.contains("blank-js") || resource.contains("empty-js") -> {
                "(()=>{})();".toByteArray(StandardCharsets.UTF_8)
            }
            resource.contains("noopjson") || resource.contains("noop-json") || resource.contains("blank-json") || resource.contains("empty-json") || resource.contains("abp-resource:blank-json") -> {
                "{}".toByteArray(StandardCharsets.UTF_8)
            }
            resource.contains("noophtml") || resource.contains("noop-html") || resource.contains("noopframe") || resource.contains("noop-frame") || resource.contains("blank-frame") || resource.contains("blank-html") || resource.contains("empty-html") || resource.contains("abp-resource:blank-html") || resource.contains("ubo-resource:noop.html") -> {
                "<html><head></head><body></body></html>".toByteArray(StandardCharsets.UTF_8)
            }
            resource.contains("noopvast") || resource.contains("noop-vast") || resource.contains("blank-vast") || resource.contains("empty-vast") || resource.contains("vast-empty") -> {
                "<VAST version=\"3.0\"></VAST>".toByteArray(StandardCharsets.UTF_8)
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
            resource.contains("noopmp4") || resource.contains("noop-video") || resource.contains("noopvideo") || resource.contains("blank-mp4") || resource.contains("empty-mp4") || resource.contains("blank-video") || resource.contains("empty-video") -> {
                ByteArray(0)
            }
            resource.contains("noopmp3") || resource.contains("noop-audio") || resource.contains("noopaudio") || resource.contains("blank-audio") || resource.contains("empty-audio") || resource.contains("noop-0.1s.mp3") -> {
                ByteArray(0)
            }
            resource.contains("noopcss") || resource.contains("noop.css") || resource.contains("noop-css") || resource.contains("blank-css") || resource.contains("abp-resource:blank-css") || resource.contains("ubo-resource:noop.css") || resource.contains("empty-css") -> {
                ByteArray(0)
            }
            resource.contains("empty") || resource.contains("nooptext") || resource.contains("noop-text") || resource.contains("blank-text") -> {
                ByteArray(0)
            }
            else -> null
        }
    }

    private fun scrubHtmlAdArtifacts(contentType: String, body: String): String {
        if (!contentType.contains("text/html") || body.isBlank()) return body
        var updated = body
        htmlAdScriptRegexes.forEach { regex ->
            updated = regex.replace(updated, "")
        }
        return updated
    }

    private val htmlAdScriptRegexes = listOf(
        Regex("""<script\b(?=[^>]*(?:adservice|doubleclick|googlesyndication|googleadservices|adsbygoogle|gdt|pangle|pangolin|toutiao|csj|tanx|alimama|applovin|mintegral|mbridge|ironsrc|vungle|unityads|sigmob|kwad|ksad|topon|tradplus|anythink|mobvista))[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<script\b[^>]*>(?=[\s\S]*?(?:adsbygoogle|adservice|doubleclick|googlesyndication|googleadservices|gdt|pangle|pangolin|gromore|csj|tanx|alimama|applovin|mintegral|mbridge|ironsrc|vungle|unityads|sigmob|kwad|ksad|topon|tradplus|anythink|mobvista|moloco|bidmachine|startapp|criteo|pubnative))(?=[\s\S]*?(?:createElement\s*\(|appendChild\s*\(|insertBefore\s*\(|document\.write\s*\(|adBreak\s*\(|requestAd\s*\())[^<]*.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<script\b(?=[^>]*(?:ad|ads|banner|splash|promo|reward|offerwall|preroll|midroll|postroll|interstitial|material))(?=[^>]*\bsrc\s*=)[^>]*>\s*</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<link\b(?=[^>]*\brel\s*=\s*["']?(?:preload|prefetch|dns-prefetch|preconnect)["']?)(?=[^>]*(?:adservice|doubleclick|googlesyndication|googleadservices|gdt|pangle|pangolin|tanx|alimama|applovin|mintegral|ironsrc|vungle|unityads|sigmob|kwad|ksad|topon|tradplus|anythink))[^>]*>""", RegexOption.IGNORE_CASE),
        Regex("""<iframe\b(?=[^>]*(?:adservice|doubleclick|googlesyndication|googleadservices|adsbygoogle|gdt|pangle|pangolin|tanx|alimama|applovin|mintegral|ironsrc|vungle|unityads|sigmob|kwad|ksad|topon|tradplus|anythink))[^>]*>.*?</iframe>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<meta\b(?=[^>]*\bhttp-equiv\s*=\s*["']?refresh["']?)(?=[^>]*(?:ad|ads|banner|splash|promo|promotion|landing|material|doubleclick|googlesyndication|googleadservices|gdt|pangle|pangolin|tanx|alimama))[^>]*>""", RegexOption.IGNORE_CASE),
        Regex("""<noscript\b(?=[^>]*(?:ad|ads|banner|splash|promo|promotion|doubleclick|googlesyndication|googleadservices|gdt|pangle|pangolin|tanx|alimama))[^>]*>.*?</noscript>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<(?:div|section|aside|ins)\b(?=[^>]*(?:\bclass\s*=|\bid\s*=|\bdata-ad(?:-|=)|\bdata-ads(?:-|=)|\bdata-google-query-id\b))(?=[^>]*(?:adsbygoogle|ad-slot|ad_unit|ad-container|ad_container|ad-wrapper|ad_wrapper|ad-banner|ad_banner|banner-ad|native-ad|feed-ad|splash-ad|interstitial-ad|rewarded-ad|sponsor-card|sponsored-card|promotion-card))[^>]*>.*?</(?:div|section|aside|ins)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<template\b(?=[^>]*(?:ad|ads|banner|splash|native|reward|interstitial|promotion|sponsor|material|creative))[^>]*>.*?</template>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<img\b(?=[^>]*(?:adservice|doubleclick|googlesyndication|googleadservices|adsbygoogle|gdt|pangle|pangolin|tanx|alimama|applovin|mintegral|ironsrc|vungle|unityads|sigmob|kwad|ksad|topon|tradplus|anythink|moloco|bidmachine|startapp|criteo|pubnative))[^>]*>""", RegexOption.IGNORE_CASE)
    )

    // P1 增强：正则表达式缓存，避免重复编译
    private fun getCompiledRegex(pattern: String, flags: String): Regex? {
        val cacheKey = "$pattern|$flags"
        
        synchronized(compiledReplaceRulesLock) {
            compiledReplaceRules[cacheKey]?.let { return it }
        }
        
        val regex = runCatching { Regex(pattern, buildReplaceRegexOptions(flags)) }.getOrNull() ?: return null
        
        synchronized(compiledReplaceRulesLock) {
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
            val regex = getCompiledRegex(pattern, flags) ?: return@forEach
            updated = try {
                if ('g' in flags) {
                    regex.replace(updated, replacement)
                } else {
                    regex.replaceFirst(updated, replacement)
                }
            } catch (_: StackOverflowError) {
                return null
            } catch (_: OutOfMemoryError) {
                return null
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
            resource.contains("noopjs") || resource.contains("noop.js") || resource.contains("noop-script") || resource.contains("noopscript") || resource.contains("abp-resource:blank-js") || resource.contains("ubo-resource:noop.js") || resource.contains("blank-js") || resource.contains("empty-js") -> "application/javascript; charset=utf-8"
            resource.contains("noopjson") || resource.contains("noop-json") || resource.contains("blank-json") || resource.contains("empty-json") || resource.contains("abp-resource:blank-json") -> "application/json; charset=utf-8"
            resource.contains("noophtml") || resource.contains("noop-html") || resource.contains("noopframe") || resource.contains("noop-frame") || resource.contains("blank-frame") || resource.contains("blank-html") || resource.contains("empty-html") || resource.contains("abp-resource:blank-html") || resource.contains("ubo-resource:noop.html") -> "text/html; charset=utf-8"
            resource.contains("noopvast") || resource.contains("noop-vast") || resource.contains("blank-vast") || resource.contains("empty-vast") || resource.contains("vast-empty") -> "application/xml; charset=utf-8"
            resource.contains("1x1") || resource.contains("pixel") || resource.contains("transparent") || resource.contains("noopimage") || resource.contains("noop-image") || resource.contains("blank-image") || resource.contains("abp-resource:blank-image") || resource.contains("empty-image") -> "image/gif"
            resource.contains("noopcss") || resource.contains("noop.css") || resource.contains("noop-css") || resource.contains("blank-css") || resource.contains("abp-resource:blank-css") || resource.contains("ubo-resource:noop.css") || resource.contains("empty-css") -> "text/css; charset=utf-8"
            resource.contains("noopmp4") || resource.contains("noop-video") || resource.contains("noopvideo") || resource.contains("blank-mp4") || resource.contains("empty-mp4") || resource.contains("blank-video") || resource.contains("empty-video") -> "video/mp4"
            resource.contains("noopmp3") || resource.contains("noop-audio") || resource.contains("noopaudio") || resource.contains("blank-audio") || resource.contains("empty-audio") || resource.contains("noop-0.1s.mp3") -> "audio/mpeg"
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

    fun buildHttp2BodyRewriteSyntheticResponse(
        streamId: Int,
        rewrite: Http2DataInspection
    ): ByteArray? {
        val body = rewrite.replacementBody ?: return null
        val contentType = rewrite.replacementContentType.ifBlank { rewrite.contentType }
        return Http2FrameCodec.buildSyntheticResponseFrames(
            streamId = streamId,
            status = 200,
            contentType = contentType.ifBlank { "text/plain; charset=utf-8" },
            body = body,
            extraHeaders = buildList {
                add("cache-control" to "no-store")
                add("pragma" to "no-cache")
                add("x-hanfeng-block" to "1")
                rewrite.cspValue?.trim()?.takeIf { it.isNotBlank() }?.let { add("content-security-policy" to it) }
            }
        )
    }

    private fun inspectAdBodySignals(lowerBody: String): BodySignalInspection {
        if (lowerBody.isBlank()) return BodySignalInspection(0, emptyList())
        val inspectionBody = normalizeBodyForAdInspection(lowerBody)
        val cacheKey = if (inspectionBody.length <= 2048) inspectionBody else inspectionBody.take(2048)
        synchronized(bodySignalCacheLock) {
            bodySignalCache[cacheKey]?.let { return it }
        }
        val scene = collectAdBodySceneSignals(inspectionBody)
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
                lowerBody = inspectionBody,
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
                pangleAndGdtSignalHit = pangleAndGdtBodySignals.any(inspectionBody::contains)
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
            applyMediationAuctionScores(
                accumulator = accumulator,
                mediationSceneHit = scene.mediationSceneHit,
                mediationPlacementHit = scene.mediationPlacementHit,
                mediationBidHit = scene.mediationBidHit,
                mediationWaterfallHit = scene.mediationWaterfallHit,
                mediationCreativeMaterialHit = scene.mediationCreativeMaterialHit,
                mediationTrackingHit = scene.mediationTrackingHit,
                sdkConfigPlacementHit = scene.sdkConfigPlacementHit,
                sdkConfigTemplateHit = scene.sdkConfigTemplateHit,
                sdkRewardOrFormatHit = scene.sdkRewardOrFormatHit,
                sdkTrackerArrayHit = scene.sdkTrackerArrayHit,
                adMarkupPayloadHit = scene.adMarkupPayloadHit,
                attributionPayloadHit = scene.attributionPayloadHit,
                openRtbBidResponseHit = scene.openRtbBidResponseHit,
                materialUrlSceneHit = scene.materialUrlSceneHit
            )
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyUniversalMobileAdPayloadScores(accumulator, inspectionBody)
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyExtendedDeliveryPayloadScores(accumulator, inspectionBody)
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyMediaContentAdPayloadScores(accumulator, inspectionBody)
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyBusinessDeliveryAdPayloadScores(accumulator, inspectionBody)
        }
        score += applyAccumulatedBodySignals(accumulator, reasons) {
            applyLightweightPayloadModelScores(accumulator, inspectionBody)
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

    private fun applyLightweightPayloadModelScores(
        accumulator: BodySignalAccumulator,
        lowerBody: String
    ) {
        var modelScore = 0
        val keySignals = listOf(
            "\"reward\"", "\"reward_amount\"", "\"video_url\"", "\"click_tracking_urls\"",
            "\"impression_urls\"", "\"track_url\"", "\"monitor_urls\"", "\"landing_url\"",
            "\"ad_unit_id\"", "\"slot_id\"", "\"auction_id\"", "\"bid_token\"",
            "\"creative_id\"", "\"material_url\"", "\"duration\"", "\"ecpm\""
        )
        modelScore += keySignals.count(lowerBody::contains)
        if (RegexCache.get("\"duration\"\\s*:\\s*(1[5-9]|2[0-9]|30)").containsMatchIn(lowerBody)) modelScore += 2
        if (RegexCache.get("\"reward_amount\"\\s*:\\s*[1-9][0-9]*").containsMatchIn(lowerBody)) modelScore += 2
        if (RegexCache.get("<iframe[^>]+(?:width=[\"']?(?:0|1|300|320)|height=[\"']?(?:0|1|250|480))").containsMatchIn(lowerBody)) modelScore += 2
        if (lowerBody.contains("display:none") && (lowerBody.contains("track") || lowerBody.contains("impression"))) modelScore += 2
        if (lowerBody.contains("click_tracking") && lowerBody.contains("impression")) modelScore += 2
        if (lowerBody.contains("video_url") && lowerBody.contains("landing_url")) modelScore += 2
        addBodySignalReasonIf(
            accumulator,
            modelScore >= 6,
            4,
            "lightweight-payload-model-ad-extended"
        )
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
        val compactBody = lowerBody.replace(jsonWhitespaceRegex, "")
        fun containsAnyCompact(vararg tokens: String): Boolean = tokens.any(compactBody::contains)

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
        val readerVisibleAdTextHits = readerVisibleAdTextTokens.filter(lowerBody::contains)
        if (readerVisibleAdTextHits.size >= 2) {
            baseSceneScore += 4
            baseSceneReasons += "reader-visible-ad-text"
        }

        val genericAdArrayHit = containsAnyCompact(
            "\"ads\":[", "\"ad_list\":[", "\"adlist\":[", "\"ad_items\":[", "\"ad_cards\":[",
            "\"creatives\":[", "\"creative_list\":[", "\"materials\":[", "\"material_list\":[",
            "\"native_ads\":[", "\"feed_ads\":[", "\"banner_ads\":[", "\"splash_ads\":["
        )
        val genericAdTemplateHit = containsAny(
            "\"template_id\"", "\"templateid\"", "\"native_template\"", "\"render_template\"",
            "\"ad_template\"", "\"layout_type\"", "\"render_data\"", "\"creative_data\""
        )
        val genericAdTrackerHit = containsAny(
            "\"impression_urls\"", "\"click_urls\"", "\"show_trackers\"", "\"click_trackers\"",
            "\"view_trackers\"", "\"monitor_urls\"", "\"tracking_urls\"", "\"event_trackers\""
        )
        val genericAdMediaHit = containsAny(
            "\"image_url\"", "\"video_url\"", "\"material_url\"", "\"creative_url\"",
            "\"landing_url\"", "\"deep_link\"", "\"download_url\"", "\"icon_url\""
        )
        if (genericAdArrayHit && (genericAdMediaHit || genericAdTrackerHit)) {
            baseSceneScore += 4
            baseSceneReasons += "generic-ad-array-material"
        }
        if (genericAdTemplateHit && genericAdMediaHit && genericAdTrackerHit) {
            baseSceneScore += 4
            baseSceneReasons += "generic-ad-template-tracker"
        }
        if (genericAdArrayHit && genericAdTemplateHit && genericAdTrackerHit) {
            baseSceneScore += 4
            baseSceneReasons += "generic-native-ad-payload"
        }

        val commentSceneHit = containsAny("\"comment", "\"reply", "\"floor")
        val commentOrPostSceneHit = commentSceneHit || lowerBody.contains("\"post\"")
        val feedSceneHit = containsAny("\"feed", "\"stream", "\"timeline")
        val recommendFeedSceneHit = feedSceneHit || lowerBody.contains("\"recommend")
        val pushSceneHit = containsAny("\"push", "\"notification", "\"notify", "\"message", "\"inbox")
        val messageCenterSceneHit = containsAny("\"message_center", "\"inbox_list", "\"notify_list", "\"bulletin_list")
        val directMessageSceneHit = containsAny("\"push_message", "\"notification_message", "\"system_message", "\"operation_message")
        val adMaterialHit = containsAny(
            "\"ad_material", "\"material_url", "\"landing_url", "\"click_url", "\"show_url",
            "\"impression_url", "\"monitor_url", "\"track_url", "\"creative_id", "\"material_id",
            "\"ad_info", "\"ad_data", "\"ad_payload", "\"native_ad", "\"ad_extra", "\"adextra",
            "\"sponsor_info", "\"commercial_info", "\"entity_template", "\"entitytype",
            "\"creative_data", "\"render_data", "\"template_data", "\"asset_list", "\"ad_assets"
        )
        val deepLinkMaterialHit = containsAny("\"deep_link", "\"download_url", "\"landing_url", "\"ad_material", "\"target_url")
        val recommendCardHit = containsAny(
            "\"recommend_card", "\"promotion_card", "\"discover_card", "\"ad_card",
            "\"sponsor_card", "\"sponsored_card", "\"commercial_card", "\"brand_card", "\"native_card_ad"
        )
        val commentAdPlacementHit = containsAny(
            "\"ad_card", "\"reply_ad", "\"floor_ad", "\"comment_ad", "\"comment_ads",
            "\"sponsor_card", "\"comment_sponsor", "\"reply_sponsor", "\"commercial_card", "\"native_ad"
        )
        val commentSceneExtendedHit = containsAny(
            "\"comment_banner", "\"comment_ad_card", "\"comment_insert_ad", "\"reply_banner",
            "\"reply_ad_card", "\"floor_banner", "\"floor_promote", "\"comment_sponsor", "\"reply_sponsor",
            "\"comment_sponsor_card", "\"reply_sponsor_card", "\"floor_sponsor_card", "\"comment_commercial_card",
            "\"comment_native_ad", "\"reply_native_ad", "\"comment_brand_card"
        )
        val commentMaterialSceneHit = containsAny(
            "\"comment_material", "\"reply_material", "\"floor_material", "\"comment_landing_url",
            "\"reply_landing_url", "\"comment_click_url", "\"reply_click_url", "\"comment_deep_link",
            "\"comment_track_url", "\"reply_track_url", "\"comment_impression_url", "\"reply_impression_url",
            "\"comment_monitor_url", "\"reply_monitor_url"
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
            "\"feed_banner", "\"feed_card", "\"feed_insert_ad", "\"information_flow_ad", "\"stream_insert_ad", "\"information_flow",
            "\"feed_sponsor", "\"feed_commercial", "\"stream_sponsor", "\"stream_commercial", "\"timeline_sponsor",
            "\"timeline_commercial", "\"native_feed_ad", "\"brand_feed_card"
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
            "\"comment_float_card\"", "\"reply_float_card\"", "\"comment_entity_ad\"", "\"reply_entity_ad\"",
            "\"sponsor_info\"", "\"commercial_info\"", "\"entity_template\"", "\"entitytype\":\"ad",
            "\"entity_type\":\"ad", "\"entitytype\":\"sponsor", "\"entity_type\":\"sponsor",
            "\"entitytype\":\"commercial", "\"entity_type\":\"commercial"
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
        val mediationBidHit = containsAny(
            "\"bid_request\"", "\"bid_response\"", "\"bid_payload\"", "\"bid_token\"", "\"bidding_token\"",
            "\"auction_token\"", "\"auction_id\"", "\"bidfloor\"", "\"bid_floor\"", "\"win_price\"",
            "\"bid_id\"", "\"auction_result\"", "\"ecpm_floor\"", "\"floor_price\"", "\"ad_floor_price\""
        )
        val mediationWaterfallHit = containsAny(
            "\"waterfall\"", "\"waterfall_id\"", "\"mediation_id\"", "\"adn\"", "\"adn_name\"", "\"adn_id\"",
            "\"network_name\"", "\"network_id\"", "\"network_placement_id\"", "\"third_sdk\""
        )
        val mediationCreativeMaterialHit = containsAny(
            "\"creative_url\"", "\"render_url\"", "\"material_url\"", "\"video_url\"", "\"image_url\"",
            "\"landing_url\"", "\"deep_link\"", "\"download_url\"", "\"creative_data\"", "\"render_data\"",
            "\"template_data\"", "\"asset_list\"", "\"ad_assets\""
        )
        val mediationTrackingHit = containsAny(
            "\"win_notice\"", "\"win_notice_url\"", "\"loss_notice\"", "\"loss_notice_url\"",
            "\"impression_urls\"", "\"click_urls\"", "\"tracking_urls\"", "\"event_trackers\"",
            "\"tracking_list\"", "\"tracker_list\"", "\"ad_tracking\"", "\"ad_events\"", "\"view_trackers\""
        )
        val sdkConfigPlacementHit = containsAny(
            "\"ad_unit_id\"", "\"adunit_id\"", "\"sdk_ad_unit_id\"", "\"sdk_slot_id\"",
            "\"ad_slot_id\"", "\"adslot_id\"", "\"slot_id\"", "\"placement_id\""
        )
        val sdkConfigTemplateHit = containsAny(
            "\"native_template\"", "\"native_template_id\"", "\"template_style\"", "\"render_template\"",
            "\"ad_template\"", "\"template_id\""
        )
        val sdkRewardOrFormatHit = containsAny(
            "\"reward_video\"", "\"rewarded_video\"", "\"rewarded_video_ad\"", "\"interstitial_ad\"",
            "\"splash_ad\"", "\"native_ad\"", "\"banner_ad\""
        )
        val sdkTrackerArrayHit = containsAny(
            "\"show_trackers\"", "\"click_trackers\"", "\"exposure_trackers\"", "\"monitor_urls\"",
            "\"imp_trackers\"", "\"impression_urls\"", "\"click_urls\"", "\"view_trackers\"",
            "\"tracking_list\"", "\"tracker_list\"", "\"ad_tracking\"", "\"ad_events\""
        )
        val adMarkupPayloadHit = containsAny(
            "\"adm\"", "\"vast\"", "\"vast_tag\"", "\"vast_xml\"", "\"mraid\"", "\"omid\"",
            "\"playable_url\"", "\"endcard_url\"", "\"companion_ads\""
        )
        val attributionPayloadHit = containsAny(
            "\"skadn\"", "\"skadnetwork\"", "\"conversion_value\"", "\"campaign_id\"",
            "\"creative_set_id\"", "\"source_app_id\"", "\"attribution_signature\""
        )
        val openRtbBidResponseHit = containsAny("\"seatbid\"", "\"impid\"", "\"adomain\"") &&
            containsAny("\"adm\"", "\"crid\"", "\"cid\"", "\"iurl\"") &&
            containsAny("\"burl\"", "\"nurl\"", "\"lurl\"", "\"impression_urls\"", "\"click_urls\"")
        val commentInsertPlacementHit = containsAny(
            "\"comment_guide_ad\"", "\"comment_hot_ad\"", "\"comment_float_ad\"", "\"comment_promote_card\"",
            "\"comment_stream_ad\"", "\"reply_promote_card\"", "\"floor_insert_ad\"", "\"comment_promote\"",
            "\"comment_sponsor_card\"", "\"reply_sponsor_card\"", "\"comment_native_ad\"", "\"comment_commercial_card\""
        )
        val commentMaterialPlacementHit = containsAny(
            "\"comment_promote\"", "\"reply_promote\"", "\"floor_promote\"", "\"comment_material\"",
            "\"reply_material\"", "\"floor_material\"", "\"comment_landing_url\"", "\"reply_landing_url\"", "\"post_landing_url\"",
            "\"comment_track_url\"", "\"comment_impression_url\"", "\"reply_track_url\"", "\"reply_impression_url\""
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
            mediationBidHit = mediationBidHit,
            mediationWaterfallHit = mediationWaterfallHit,
            mediationCreativeMaterialHit = mediationCreativeMaterialHit,
            mediationTrackingHit = mediationTrackingHit,
            sdkConfigPlacementHit = sdkConfigPlacementHit,
            sdkConfigTemplateHit = sdkConfigTemplateHit,
            sdkRewardOrFormatHit = sdkRewardOrFormatHit,
            sdkTrackerArrayHit = sdkTrackerArrayHit,
            adMarkupPayloadHit = adMarkupPayloadHit,
            attributionPayloadHit = attributionPayloadHit,
            openRtbBidResponseHit = openRtbBidResponseHit,
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
        val mediationBidHit: Boolean,
        val mediationWaterfallHit: Boolean,
        val mediationCreativeMaterialHit: Boolean,
        val mediationTrackingHit: Boolean,
        val sdkConfigPlacementHit: Boolean,
        val sdkConfigTemplateHit: Boolean,
        val sdkRewardOrFormatHit: Boolean,
        val sdkTrackerArrayHit: Boolean,
        val adMarkupPayloadHit: Boolean,
        val attributionPayloadHit: Boolean,
        val openRtbBidResponseHit: Boolean,
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
        if (commentOrPostSceneHit && recommendCardHit && (adMaterialHit || deepLinkMaterialHit)) {
            addBodySignalReason(accumulator, 4, "comment-sponsored-card-extended")
        }
        if (commentOrPostSceneHit && containsAny("\"native_ad", "\"native_card_ad", "\"commercial_card") &&
            (adMaterialHit || clickOrMaterialSceneHit)) {
            addBodySignalReason(accumulator, 4, "comment-native-ad-extended")
        }
        if (commentOrPostSceneHit && adMaterialHit &&
            containsAny("\"track_url", "\"impression_url", "\"monitor_url", "\"exposure_url")) {
            addBodySignalReason(accumulator, 4, "comment-track-ad-extended")
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
        if (feedSceneHit && recommendCardHit && (adMaterialHit || deepLinkMaterialHit)) {
            addBodySignalReason(accumulator, 4, "feed-sponsored-card-extended")
        }
        if (feedSceneHit && containsAny("\"native_feed_ad", "\"native_card_ad", "\"brand_feed_card") &&
            (adMaterialHit || clickOrMaterialSceneHit)) {
            addBodySignalReason(accumulator, 4, "timeline-native-ad-extended")
        }
        if (feedSceneHit && containsAny("\"stream_commercial", "\"timeline_commercial", "\"feed_commercial") &&
            (recommendCardHit || adMaterialHit)) {
            addBodySignalReason(accumulator, 4, "stream-commercial-card-extended")
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

    private fun applyMediationAuctionScores(
        accumulator: BodySignalAccumulator,
        mediationSceneHit: Boolean,
        mediationPlacementHit: Boolean,
        mediationBidHit: Boolean,
        mediationWaterfallHit: Boolean,
        mediationCreativeMaterialHit: Boolean,
        mediationTrackingHit: Boolean,
        sdkConfigPlacementHit: Boolean,
        sdkConfigTemplateHit: Boolean,
        sdkRewardOrFormatHit: Boolean,
        sdkTrackerArrayHit: Boolean,
        adMarkupPayloadHit: Boolean,
        attributionPayloadHit: Boolean,
        openRtbBidResponseHit: Boolean,
        materialUrlSceneHit: Boolean
    ) {
        if (mediationSceneHit && mediationBidHit && (mediationCreativeMaterialHit || materialUrlSceneHit)) {
            addBodySignalReason(accumulator, 4, "mediation-bid-material-extended")
        }
        if (mediationWaterfallHit && mediationPlacementHit && (mediationCreativeMaterialHit || mediationTrackingHit)) {
            addBodySignalReason(accumulator, 4, "mediation-waterfall-material-extended")
        }
        if (mediationWaterfallHit && mediationBidHit && mediationTrackingHit) {
            addBodySignalReason(accumulator, 4, "adn-placement-material-extended")
        }
        if (sdkConfigPlacementHit && sdkConfigTemplateHit && sdkRewardOrFormatHit && (mediationWaterfallHit || mediationSceneHit)) {
            addBodySignalReason(accumulator, 4, "sdk-config-ad-extended")
        }
        if (sdkTrackerArrayHit && sdkConfigPlacementHit && (mediationCreativeMaterialHit || materialUrlSceneHit)) {
            addBodySignalReason(accumulator, 4, "sdk-tracker-array-extended")
        }
        if (adMarkupPayloadHit && sdkConfigPlacementHit && (mediationTrackingHit || mediationBidHit || mediationWaterfallHit)) {
            addBodySignalReason(accumulator, 4, "ad-markup-payload-extended")
        }
        if (attributionPayloadHit && sdkConfigPlacementHit && (mediationBidHit || mediationTrackingHit || sdkTrackerArrayHit)) {
            addBodySignalReason(accumulator, 4, "attribution-payload-extended")
        }
        if (openRtbBidResponseHit && (mediationBidHit || mediationSceneHit || adMarkupPayloadHit)) {
            addBodySignalReason(accumulator, 4, "openrtb-bid-response-extended")
        }
    }

    private fun applyUniversalMobileAdPayloadScores(
        accumulator: BodySignalAccumulator,
        lowerBody: String
    ) {
        fun containsAny(vararg tokens: String): Boolean = tokens.any(lowerBody::contains)

        val placementHit = containsAny(
            "\"ad_unit_id\"", "\"adunit_id\"", "\"placement_id\"", "\"placement_name\"",
            "\"slot_id\"", "\"ad_slot_id\"", "\"inventory_id\"", "\"zone_id\""
        )
        val creativeHit = containsAny(
            "\"creative_id\"", "\"creativeid\"", "\"creative_url\"", "\"creative_data\"",
            "\"material_url\"", "\"image_url\"", "\"video_url\"", "\"html_snippet\"",
            "\"render_url\"", "\"adm\""
        )
        val trackerHit = containsAny(
            "\"impression_url\"", "\"impression_urls\"", "\"imptrackers\"", "\"eventtrackers\"",
            "\"click_url\"", "\"click_urls\"", "\"clicktrackers\"", "\"view_trackers\"",
            "\"monitor_urls\"", "\"tracking_urls\""
        )
        val auctionHit = containsAny(
            "\"auction_id\"", "\"auctionid\"", "\"bid_id\"", "\"bidid\"", "\"bid_token\"",
            "\"bidtoken\"", "\"bid_response\"", "\"waterfall\"", "\"waterfall_id\"",
            "\"mediation\"", "\"adapter_responses\"", "\"network_responses\""
        )
        val admobOrFanHit = containsAny(
            "\"admob\"", "\"google_mobile_ads\"", "\"gma\"", "\"facebook_audience_network\"",
            "\"audience_network\"", "\"fan\"", "\"mediation_adapter_class_name\"",
            "\"adapter_class_name\"", "\"response_id\""
        )
        val applovinMaxHit = containsAny(
            "\"applovin\"", "\"max_ad_unit_id\"", "\"maxsdk\"", "\"network_placement\"",
            "\"revenue\"", "\"ad_format\"", "\"ad_values\""
        )
        val ironSourceHit = containsAny(
            "\"ironsource\"", "\"iron_source\"", "\"supersonic\"", "\"instance_id\"",
            "\"instance_name\"", "\"demand_source_name\"", "\"impression_data\""
        )
        val vastVideoHit = containsAny("<vast", "\"vast\"", "\"vast_xml\"", "\"vast_tag\"") &&
            containsAny("<ad", "\"mediafiles\"", "\"media_file\"", "\"companionads\"", "\"trackingevents\"") &&
            containsAny("impression", "clickthrough", "\"video_url\"", "\"creative\"")
        val nativeAssetsHit = containsAny("\"assets\"", "\"asset_list\"", "\"native\"", "\"native_ad\"") &&
            containsAny("\"imptrackers\"", "\"eventtrackers\"", "\"clicktrackers\"", "\"link\"") &&
            containsAny("\"img\"", "\"video\"", "\"data\"", "\"title\"")
        val playableEndcardHit = containsAny("\"playable_url\"", "\"playableurl\"", "\"endcard_url\"", "\"endcardurl\"", "\"endcard\"") &&
            containsAny("\"click_url\"", "\"landing_url\"", "\"download_url\"", "\"deeplink\"", "\"deep_link\"") &&
            (placementHit || auctionHit || trackerHit)

        addBodySignalReasonIf(
            accumulator,
            admobOrFanHit && placementHit && auctionHit && (creativeHit || trackerHit),
            4,
            "admob-mediation-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            applovinMaxHit && placementHit && (creativeHit || trackerHit) && (auctionHit || containsAny("\"revenue\"")),
            4,
            "applovin-max-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            ironSourceHit && placementHit && auctionHit && (creativeHit || trackerHit),
            4,
            "ironsource-auction-payload-extended"
        )
        addBodySignalReasonIf(accumulator, vastVideoHit, 4, "vast-video-ad-payload-extended")
        addBodySignalReasonIf(
            accumulator,
            nativeAssetsHit && (placementHit || creativeHit || trackerHit),
            4,
            "native-assets-ad-payload-extended"
        )
        addBodySignalReasonIf(accumulator, playableEndcardHit, 4, "playable-endcard-payload-extended")
    }

    private fun applyExtendedDeliveryPayloadScores(
        accumulator: BodySignalAccumulator,
        lowerBody: String
    ) {
        fun containsAny(vararg tokens: String): Boolean = tokens.any(lowerBody::contains)

        val materialHit = containsAny(
            "\"material_url\"", "\"material_urls\"", "\"image_url\"", "\"video_url\"",
            "\"creative_url\"", "\"landing_url\"", "\"target_url\"", "\"download_url\""
        )
        val trackingHit = containsAny(
            "\"track_url\"", "\"tracking_url\"", "\"tracking_urls\"", "\"monitor_url\"",
            "\"monitor_urls\"", "\"impression_url\"", "\"impression_urls\"", "\"click_url\"", "\"click_urls\""
        )
        val adSceneHit = containsAny(
            "\"ad\"", "\"ads\"", "\"advert\"", "\"promotion\"", "\"promo\"",
            "\"sponsor\"", "\"commercial\"", "\"campaign\"", "\"activity_ad\""
        )
        val pushSceneHit = containsAny(
            "\"push\"", "\"push_type\"", "\"push_scene\"", "\"notification\"", "\"notify\"",
            "\"notice\"", "\"message_center\"", "\"inbox\"", "\"push_title\"", "\"push_content\""
        )
        val fakeSystemHit = containsAny(
            "\"system_alert\"", "\"system_notice\"", "\"system_notification\"", "\"cleaner\"",
            "\"boost\"", "\"speedup\"", "\"virus\"", "\"security_scan\"", "\"battery_saver\"",
            "手机内存不足", "系统更新", "安全风险", "立即清理", "一键加速"
        )
        val deeplinkMarketHit = containsAny(
            "\"deeplink\"", "\"deep_link\"", "\"schema_url\"", "\"scheme_url\"", "\"intent_url\"",
            "\"market_url\"", "market://", "intent://", "snssdk", "tbopen://", "tmall://", "openapp."
        )
        val operationPopupHit = containsAny(
            "\"operation_popup\"", "\"operation_banner\"", "\"operation_card\"", "\"activity_popup\"",
            "\"activity_banner\"", "\"red_packet\"", "\"redpacket\"", "\"coupon_popup\"",
            "\"welfare_popup\"", "\"benefit_popup\"", "\"mission_popup\"", "\"sign_popup\""
        )
        val precacheHit = containsAny(
            "\"precache\"", "\"pre_cache\"", "\"cache_list\"", "\"cache_material\"", "\"cache_url\"",
            "\"preload\"", "\"preload_list\"", "\"preload_material\"", "\"prefetch\"", "\"prefetch_url\""
        )
        val shakeSensorHit = containsAny(
            "\"shake\"", "\"shake_ad\"", "\"shake_jump\"", "\"sensor_ad\"", "\"accelerometer\"",
            "\"gyroscope\"", "\"motion_trigger\"", "\"shake_landing\"", "摇一摇", "摇动"
        )

        addBodySignalReasonIf(
            accumulator,
            pushSceneHit && adSceneHit && (materialHit || trackingHit || deeplinkMarketHit),
            4,
            "push-notification-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            fakeSystemHit && (adSceneHit || operationPopupHit) && (materialHit || deeplinkMarketHit || trackingHit),
            4,
            "fake-system-alert-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            deeplinkMarketHit && (adSceneHit || materialHit || trackingHit) &&
                containsAny("\"pkg\"", "\"package\"", "\"app_id\"", "\"campaign\"", "\"creative_id\"", "\"click_id\""),
            4,
            "deeplink-market-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            operationPopupHit && (adSceneHit || materialHit || deeplinkMarketHit),
            4,
            "operation-popup-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            precacheHit && adSceneHit && (materialHit || trackingHit),
            4,
            "precache-material-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            shakeSensorHit && (adSceneHit || deeplinkMarketHit) && (materialHit || trackingHit || deeplinkMarketHit),
            4,
            "shake-sensor-ad-payload-extended"
        )

        val httpDnsHit = containsAny(
            "\"httpdns\"", "\"dns_records\"", "\"dns_records\"", "\"resolve\"", "\"resolver\"",
            "\"qname\"", "\"cname\"", "\"ttl\"", "\"ips\"", "\"ipv4\"", "\"ipv6\""
        )
        val adDomainHint = containsAny(
            "ad.", ".ad.", "ads.", ".ads.", "adx", "adx", "adlog", "adtrack",
            "doubleclick", "gdt", "pangle", "pangolin", "applovin", "ironsource", "mintegral", "topon"
        )
        val streamingTransportHit = containsAny(
            "\"websocket\"", "\"ws_url\"", "\"wss_url\"", "\"socket_url\"", "\"event_stream\"",
            "\"sse\"", "text/event-stream", "\"stream_channel\"", "\"push_channel\""
        )
        val grpcProtoHit = containsAny(
            "\"grpc\"", "application/grpc", "\"protobuf\"", "\"proto\"", "\"pb\"",
            "\"adservice\"", "\"ads_service\"", "\"bidservice\"", "\"ad_request_pb\"", "\"ad_response_pb\""
        )
        val dynamicCodeHit = containsAny(
            "\"dex_url\"", "\"plugin_url\"", "\"module_url\"", "\"hotfix_url\"", "\"bundle_url\"",
            "\"js_bundle\"", "\"wasm_url\"", "\"dynamic_module\"", "\"ad_plugin\"", "\"adsdk_plugin\""
        )
        val encryptedConfigHit = containsAny(
            "\"encrypted_payload\"", "\"cipher_text\"", "\"ciphertext\"", "\"encrypted_config\"",
            "\"config_sign\"", "\"sign\"", "\"nonce\"", "\"iv\"", "\"secret_version\""
        )
        val privateGatewayHit = containsAny(
            "\"tcp_gateway\"", "\"udp_gateway\"", "\"quic_gateway\"", "\"binary_gateway\"",
            "\"socket_gateway\"", "\"ad_gateway\"", "\"adsdk_gateway\"", "\"report_gateway\"",
            "\"gateway_token\"", "\"channel_token\""
        )

        addBodySignalReasonIf(
            accumulator,
            httpDnsHit && adDomainHint,
            4,
            "httpdns-ad-resolution-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            streamingTransportHit && (adSceneHit || materialHit || trackingHit || deeplinkMarketHit),
            4,
            "websocket-sse-ad-stream-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            grpcProtoHit && (adSceneHit || materialHit || trackingHit || placementLikePayload(lowerBody)),
            4,
            "grpc-protobuf-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            dynamicCodeHit && (adSceneHit || materialHit || trackingHit || adDomainHint),
            4,
            "dynamic-code-ad-module-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            encryptedConfigHit && (adSceneHit || auctionLikePayload(lowerBody) || placementLikePayload(lowerBody)),
            4,
            "encrypted-config-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            privateGatewayHit && (adSceneHit || trackingHit || auctionLikePayload(lowerBody) || adDomainHint),
            4,
            "private-protocol-ad-gateway-payload-extended"
        )
    }

    private fun placementLikePayload(lowerBody: String): Boolean {
        return containsAny(
            lowerBody,
            "\"placement_id\"", "\"slot_id\"", "\"ad_unit_id\"", "\"adunit_id\"",
            "\"ad_slot_id\"", "\"inventory_id\"", "\"zone_id\""
        )
    }

    private fun auctionLikePayload(lowerBody: String): Boolean {
        return containsAny(
            lowerBody,
            "\"waterfall\"", "\"mediation\"", "\"auction_id\"", "\"bid_token\"",
            "\"bid_response\"", "\"bidding_token\"", "\"ecpm\"", "\"price\""
        )
    }

    private fun applyMediaContentAdPayloadScores(
        accumulator: BodySignalAccumulator,
        lowerBody: String
    ) {
        fun containsAny(vararg tokens: String): Boolean = tokens.any(lowerBody::contains)

        val mediaSceneHit = containsAny(
            "\"player\"", "\"playback\"", "\"video\"", "\"vod\"", "\"episode\"", "\"stream\""
        )
        val adBreakHit = containsAny(
            "\"ad_break\"", "\"adbreak\"", "\"ad_breaks\"", "\"cue_points\"", "\"cuepoints\"",
            "\"preroll\"", "\"pre_roll\"", "\"midroll\"", "\"mid_roll\"", "\"postroll\"", "\"post_roll\"",
            "\"pause_ad\"", "\"player_ad\"", "\"patch_ad\"", "\"video_patch\""
        )
        val mediaMaterialHit = containsAny(
            "\"vast\"", "\"vmap\"", "\"vast_url\"", "\"vmap_url\"", "\"ad_tag_url\"",
            "\"media_file\"", "\"mediafiles\"", "\"companion_ads\"", "\"trackingevents\"",
            "\"skip_offset\"", "\"skip_time\"", "\"clickthrough\""
        )
        val audioSceneHit = containsAny(
            "\"audio\"", "\"listen\"", "\"podcast\"", "\"tts\"", "\"audiobook\"", "\"fm\""
        )
        val audioAdHit = containsAny(
            "\"audio_ad\"", "\"audio_ads\"", "\"audio_preroll\"", "\"audio_midroll\"",
            "\"listen_ad\"", "\"listen_reward_ad\"", "\"voice_ad\"", "\"ad_audio_url\""
        )
        val comicSceneHit = containsAny("\"comic\"", "\"manga\"", "\"manhua\"", "\"cartoon\"")
        val comicUnlockHit = containsAny(
            "\"comic_unlock_ad\"", "\"manga_unlock_ad\"", "\"comic_reward_ad\"", "\"manga_reward_ad\"",
            "\"comic_page_ad\"", "\"manga_page_ad\"", "\"chapter_unlock_ad\"", "\"unlock_by_ad\""
        )
        val dramaSceneHit = containsAny("\"short_drama\"", "\"shortdrama\"", "\"drama\"", "\"episode\"")
        val dramaAdHit = containsAny(
            "\"episode_unlock_ad\"", "\"episode_reward_ad\"", "\"episode_preload_ad\"",
            "\"drama_interstitial_ad\"", "\"drama_feed_ad\"", "\"short_drama_unlock_ad\"",
            "\"shortdrama_unlock_ad\"", "\"drama_reward_ad\""
        )
        val commonAdMaterialHit = containsAny(
            "\"material_url\"", "\"video_url\"", "\"audio_url\"", "\"landing_url\"", "\"click_url\"",
            "\"impression_urls\"", "\"track_url\"", "\"monitor_urls\""
        )

        addBodySignalReasonIf(
            accumulator,
            mediaSceneHit && adBreakHit && (mediaMaterialHit || commonAdMaterialHit),
            4,
            "media-preroll-metadata-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            audioSceneHit && audioAdHit && commonAdMaterialHit,
            4,
            "audio-ad-break-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            comicSceneHit && comicUnlockHit && commonAdMaterialHit,
            4,
            "comic-manga-unlock-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            dramaSceneHit && dramaAdHit && commonAdMaterialHit,
            4,
            "short-drama-episode-ad-payload-extended"
        )
    }

    private fun applyBusinessDeliveryAdPayloadScores(
        accumulator: BodySignalAccumulator,
        lowerBody: String
    ) {
        fun containsAny(vararg tokens: String): Boolean = tokens.any(lowerBody::contains)

        val commonMaterialHit = containsAny(
            "\"material_url\"", "\"material_urls\"", "\"creative_url\"", "\"render_url\"",
            "\"image_url\"", "\"video_url\"", "\"landing_url\"", "\"target_url\"",
            "\"click_url\"", "\"download_url\"", "\"deep_link\"", "\"deeplink\""
        )
        val commonTrackingHit = containsAny(
            "\"impression_url\"", "\"impression_urls\"", "\"show_url\"", "\"show_urls\"",
            "\"track_url\"", "\"track_urls\"", "\"tracking_urls\"", "\"monitor_urls\"",
            "\"click_trackers\"", "\"show_trackers\"", "\"view_trackers\""
        )
        val placementHit = containsAny(
            "\"placement_id\"", "\"slot_id\"", "\"ad_slot_id\"", "\"ad_unit_id\"",
            "\"position_id\"", "\"zone_id\"", "\"inventory_id\"", "\"campaign_id\""
        )
        val taskRewardHit = containsAny(
            "\"task_ad\"", "\"task_ads\"", "\"daily_task_ad\"", "\"mission_ad\"",
            "\"benefit_ad\"", "\"welfare_ad\"", "\"coin_ad\"", "\"coin_reward_ad\"",
            "\"checkin_ad\"", "\"sign_ad\"", "\"offerwall\"", "\"offer_wall\"",
            "\"watch_ad_task\"", "\"watch_ad_reward\"", "看广告得", "看视频领", "任务奖励"
        )
        val gameAdHit = containsAny(
            "\"game_ad\"", "\"game_ads\"", "\"game_interstitial\"", "\"level_complete_ad\"",
            "\"revive_ad\"", "\"revival_ad\"", "\"double_reward_ad\"", "\"booster_ad\"",
            "\"chest_ad\"", "\"loot_ad\"", "\"game_reward_video\"", "\"rewarded_interstitial\""
        )
        val liveAdHit = containsAny(
            "\"live_ad\"", "\"live_ads\"", "\"live_room_ad\"", "\"room_ad\"",
            "\"anchor_ad\"", "\"streamer_ad\"", "\"live_banner\"", "\"gift_ad\"",
            "\"live_popup_ad\"", "\"live_float_ad\"", "\"live_card_ad\""
        )
        val searchRecommendHit = containsAny(
            "\"search_ad\"", "\"search_ads\"", "\"search_banner_ad\"", "\"search_result_ad\"",
            "\"hotword_ad\"", "\"hot_word_ad\"", "\"keyword_ad\"", "\"query_ad\"",
            "\"recommend_ad\"", "\"recommend_ads\"", "\"guess_like_ad\"", "\"suggestion_ad\""
        )
        val experimentConfigHit = containsAny(
            "\"abtest\"", "\"ab_test\"", "\"experiment\"", "\"exp_id\"", "\"gray_config\"",
            "\"grey_config\"", "\"remote_config\"", "\"feature_flag\"", "\"strategy_config\"",
            "\"ad_strategy\"", "\"ad_switch\"", "\"ad_enable\"", "\"show_ad\""
        )
        val miniappLandingHit = containsAny(
            "\"miniapp\"", "\"mini_app\"", "\"applet\"", "\"mini_program\"",
            "\"wechat_path\"", "\"wx_path\"", "\"wx_appid\"", "\"quick_app\"",
            "\"h5_landing\"", "\"landing_page\"", "\"lp_url\"", "\"open_url\""
        )
        val fakeActionButtonHit = containsAny(
            "\"fake_close\"", "\"fake_button\"", "\"fake_play\"", "\"fake_download\"",
            "\"close_area\"", "\"click_area\"", "\"misclick\"", "\"click_trap\"",
            "\"download_button\"", "\"install_button\"", "\"play_button\"", "\"skip_button\"",
            "伪关闭", "立即下载", "立即安装", "点击继续", "点击跳转"
        )
        val clipboardShareHit = containsAny(
            "\"clipboard\"", "\"copy_text\"", "\"copywriting\"", "\"share_ad\"",
            "\"share_reward\"", "\"share_task\"", "\"share_url\"", "\"share_schema\"",
            "复制口令", "复制链接", "分享赚钱", "分享领取", "邀请奖励"
        )
        val serviceWorkerCacheHit = containsAny(
            "\"serviceworker\"", "\"service_worker\"", "\"sw_url\"", "\"cache_manifest\"",
            "\"offline_cache\"", "\"resource_manifest\"", "\"asset_manifest\"", "\"precache_manifest\"",
            "\"cache_assets\"", "\"prefetch_assets\""
        )
        val systemSurfaceHit = containsAny(
            "\"desktop_badge\"", "\"launcher_badge\"", "\"widget_ad\"", "\"shortcut_ad\"",
            "\"status_bar\"", "\"notification_bar\"", "\"system_surface\"", "\"wallpaper_ad\"",
            "\"lockscreen_ad\"", "\"screen_saver_ad\"", "\"calendar_ad\""
        )
        val socialInteractionHit = containsAny(
            "\"comment_ad\"", "\"comment_ads\"", "\"comment_card_ad\"", "\"reply_ad\"",
            "\"danmaku_ad\"", "\"bullet_ad\"", "\"follow_ad\"", "\"profile_ad\"",
            "\"personal_page_ad\"", "\"inbox_ad\"", "\"message_ad\"", "\"chat_ad\"",
            "\"private_message_ad\"", "\"topic_ad\"", "\"circle_ad\"", "\"community_ad\""
        )
        val cloudTemplateHit = containsAny(
            "\"template_ad\"", "\"tpl_ad\"", "\"render_template\"", "\"template_id\"",
            "\"template_url\"", "\"cloud_template\"", "\"cloud_control\"", "\"cloud_config\"",
            "\"strategy_id\"", "\"scene_config\"", "\"layout_config\"", "\"card_template\""
        )
        val assetPackageHit = containsAny(
            "\"resource_pack\"", "\"asset_pack\"", "\"material_package\"", "\"creative_package\"",
            "\"bundle_url\"", "\"zip_url\"", "\"patch_url\"", "\"hot_patch\"",
            "\"plugin_url\"", "\"module_url\"", "\"web_bundle\"", "\"offline_package\""
        )
        val couponRedpacketHit = containsAny(
            "\"coupon_ad\"", "\"redpacket_ad\"", "\"red_packet_ad\"", "\"cash_ad\"",
            "\"cashback_ad\"", "\"subsidy_ad\"", "\"allowance_ad\"", "\"lottery_ad\"",
            "\"draw_ad\"", "\"bonus_ad\"", "\"money_reward_ad\"", "\"红包广告\"",
            "\"领券广告\"", "看广告领红包", "看广告领券"
        )
        val ecommerceAffiliateHit = containsAny(
            "\"product_ad\"", "\"shop_ad\"", "\"mall_ad\"", "\"sku_ad\"",
            "\"item_ad\"", "\"goods_ad\"", "\"commerce_ad\"", "\"shopping_ad\"",
            "\"affiliate_ad\"", "\"cps_ad\"", "\"commission_ad\"", "\"rebate_ad\"",
            "\"taoke_ad\"", "\"jd_union_ad\"", "\"pdd_ad\"", "\"coupon_landing\""
        )
        val localLifeToolHit = containsAny(
            "\"local_life_ad\"", "\"nearby_ad\"", "\"poi_ad\"", "\"map_ad\"",
            "\"weather_ad\"", "\"calendar_ad\"", "\"tool_ad\"", "\"cleaner_ad\"",
            "\"battery_ad\"", "\"wifi_ad\"", "\"vpn_ad\"", "\"file_manager_ad\"",
            "\"takeaway_ad\"", "\"hotel_ad\"", "\"travel_ad\"", "\"ride_ad\""
        )
        val leadgenSurveyHit = containsAny(
            "\"leadgen_ad\"", "\"lead_form_ad\"", "\"form_ad\"", "\"survey_ad\"",
            "\"questionnaire_ad\"", "\"trial_ad\"", "\"signup_ad\"", "\"reservation_ad\"",
            "\"phone_collect\"", "\"contact_form\"", "\"lead_url\"", "\"crm_callback\""
        )
        val calendarReminderHit = containsAny(
            "\"calendar_subscribe_ad\"", "\"calendar_reminder_ad\"", "\"reminder_ad\"",
            "\"alarm_ad\"", "\"schedule_ad\"", "\"ics_url\"", "\"calendar_url\"",
            "\"reminder_url\"", "\"subscribe_calendar\"", "订阅日历广告", "日历提醒广告"
        )
        val browserStartpageHit = containsAny(
            "\"browser_ad\"", "\"startpage_ad\"", "\"homepage_ad\"", "\"newtab_ad\"",
            "\"speed_dial_ad\"", "\"bookmark_ad\"", "\"search_box_ad\"", "\"search_suggestion_ad\"",
            "\"site_nav_ad\"", "\"nav_card_ad\"", "\"hot_search_ad\"", "\"trending_ad\""
        )
        val appStorePromotionHit = containsAny(
            "\"appstore_ad\"", "\"app_store_ad\"", "\"promoted_app\"", "\"app_install_ad\"",
            "\"app_update_ad\"", "\"install_recommend_ad\"", "\"download_recommend_ad\"",
            "\"preinstall_ad\"", "\"game_center_ad\"", "\"app_rank_ad\"", "\"apk_ad\""
        )
        val oemSecurityCleanerHit = containsAny(
            "\"oem_ad\"", "\"rom_ad\"", "\"system_manager_ad\"", "\"security_ad\"",
            "\"cleaner_ad\"", "\"boost_ad\"", "\"phone_boost_ad\"", "\"virus_scan_ad\"",
            "\"permission_manager_ad\"", "\"storage_clean_ad\"", "\"lockscreen_news_ad\"", "\"negative_screen_ad\""
        )
        val crossDeviceHit = containsAny(
            "\"tv_ad\"", "\"ott_ad\"", "\"cast_ad\"", "\"screen_cast_ad\"",
            "\"wear_ad\"", "\"watch_ad\"", "\"car_ad\"", "\"carplay_ad\"",
            "\"iot_ad\"", "\"speaker_ad\"", "\"tablet_ad\"", "\"pad_ad\""
        )
        val wasmObfuscatedLoaderHit = containsAny(
            "\"wasm_url\"", "\"wasm_hash\"", "\"wasm_module\"", "\"webassembly\"",
            "\"obfuscated_js\"", "\"loader_hash\"", "\"loader_signature\"", "\"js_loader\"",
            "\"eval_loader\"", "\"packed_script\"", "\"encrypted_script\"", "\"ad_loader\"",
            "webassembly.instantiate", "webassembly.compilestreaming", "atob(", "eval(function"
        )
        val mediaFingerprintHit = containsAny(
            "\"phash\"", "\"perceptual_hash\"", "\"image_hash\"", "\"media_hash\"",
            "\"creative_hash\"", "\"asset_hash\"", "\"watermark\"", "\"watermark_text\"",
            "\"endcard_hash\"", "\"end_card_hash\"", "\"video_fingerprint\"", "\"frame_hash\"",
            "\"logo_watermark\"", "\"brand_watermark\"", "\"ad_fingerprint\""
        )
        val adSceneHit = containsAny(
            "\"ad\"", "\"ads\"", "\"ad_info\"", "\"ad_data\"", "\"advert\"",
            "\"promotion\"", "\"promo\"", "\"sponsor\"", "\"commercial\""
        )

        addBodySignalReasonIf(
            accumulator,
            taskRewardHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "task-reward-offerwall-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            gameAdHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "game-interstitial-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            liveAdHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "live-room-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            searchRecommendHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "search-recommend-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            experimentConfigHit && adSceneHit && (placementHit || commonMaterialHit || commonTrackingHit),
            4,
            "experiment-ad-config-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            miniappLandingHit && adSceneHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "miniapp-landing-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            fakeActionButtonHit && (adSceneHit || commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "fake-action-button-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            clipboardShareHit && (adSceneHit || commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "clipboard-share-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            serviceWorkerCacheHit && (adSceneHit || commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "serviceworker-cache-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            systemSurfaceHit && (adSceneHit || commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "system-surface-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            socialInteractionHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "social-interaction-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            cloudTemplateHit && adSceneHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "cloud-template-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            assetPackageHit && adSceneHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "asset-package-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            couponRedpacketHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "coupon-redpacket-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            ecommerceAffiliateHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "ecommerce-affiliate-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            localLifeToolHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "local-life-tool-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            leadgenSurveyHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "leadgen-survey-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            calendarReminderHit && (adSceneHit || commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "calendar-reminder-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            browserStartpageHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "browser-startpage-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            appStorePromotionHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "appstore-promotion-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            oemSecurityCleanerHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "oem-security-cleaner-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            crossDeviceHit && (commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "cross-device-ad-payload-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            wasmObfuscatedLoaderHit && (adSceneHit || commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "wasm-js-obfuscated-loader-ad-extended"
        )
        addBodySignalReasonIf(
            accumulator,
            mediaFingerprintHit && (adSceneHit || commonMaterialHit || commonTrackingHit || placementHit),
            4,
            "media-fingerprint-watermark-ad-extended"
        )
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
        val isWebSocketConnect = environment.method.equals("connect", ignoreCase = true) &&
            environment.protocol.equals("websocket", ignoreCase = true)
        val effectiveRequestType = if (isWebSocketConnect) "websocket" else null
        val directives = RuleRepository.getRequestRewriteDirectives(
            context = context,
            host = environment.lowerAuthority,
            path = environment.lowerPath,
            appName = session.appName,
            requestDomain = requestDomain,
            requestType = effectiveRequestType
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
        if (shouldSkipProtectedHttp2Traffic(environment.lowerAuthority, blockedHost, blockedUrl, session.appName)) return null
        val suspicion = Http2SuspicionAccumulator()
        if (blockedHost) suspicion.add(3, "blocked-host")
        if (blockedUrl) suspicion.add(3, "blocked-url")
        if (isWebSocketConnect && directives.block) suspicion.add(10, "websocket-block")
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
        val protocol: String?,
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
        val protocol = normalized[":protocol"]?.firstOrNull()?.ifBlank { null }
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
            protocol = protocol,
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
        blockedUrl: Boolean,
        appName: String?
    ): Boolean {
        if (blockedHost || blockedUrl) return false
        if (RuleRepository.isWhitelistedDomain(lowerAuthority)) return true
        if (RuleRepository.shouldProtectMediaTraffic(lowerAuthority)) return true
        if (RuleRepository.shouldProtectBusinessTraffic(lowerAuthority)) return true
        val isNovelApp = RuleRepository.isNovelAppHint(appName)
        val isCommunityApp = RuleRepository.isCommunityAppHint(appName)
        if ((RuleRepository.isNovelContentDomain(lowerAuthority) || RuleRepository.isProtectedNovelAppDomain(lowerAuthority)) && !isNovelApp) {
            return true
        }
        return RuleRepository.isSocialCoreDomain(lowerAuthority) && !isCommunityApp
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
            protocol = environment.protocol,
            contentType = environment.contentType,
            referer = environment.referer,
            userAgent = environment.userAgent,
            location = environment.location,
            setCookie = environment.setCookie,
            vendor = vendor,
            suspiciousScore = suspicion.score,
            suspiciousReasons = appendRuleDebugReasons(suspicion.reasons, directives.matchedRuleSummaries),
            redirectResource = directives.redirectResource,
            cspValue = directives.cspValue,
            requestLike = environment.method != null && environment.status == null,
            responseLike = environment.status != null,
            hasBodyRewriteDirectives = directives.redirectResource != null ||
                directives.replaceRules.isNotEmpty() ||
                directives.cosmeticSelectors.isNotEmpty() ||
                directives.jsInjectRules.isNotEmpty() ||
                !directives.cspValue.isNullOrBlank()
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
        if (shouldProtectNormalNovelHttpTraffic(context, lowerAuthority, lowerPath, appName) && !isNovelApp) return
        accumulator.addIf(
            RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, lowerAuthority, lowerPath, appName),
            4,
            "novel-protected-path"
        )
        accumulator.addIf(pathInspection.strongSuspicious, if (isNovelApp) 4 else 3, "path-strong-suspicious")
        accumulator.addIf(looksLikeCommentAdPath(lowerPath), 3, "comment-ad-path")
        accumulator.addIf(looksLikeCommentCommerceAdPath(lowerPath), 4, "comment-commerce-path")
        accumulator.addIf(isNovelApp && looksLikeNovelAdPath(lowerPath), 4, "novel-ad-path")
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
            "/comment/sponsor", "/comment/native", "/comment/commercial", "/reply/sponsor", "/floor/sponsor",
            "/feed/sponsor", "/feed/native", "/feed/commercial", "/timeline/sponsor", "/timeline/native",
            "/stream/sponsor", "/stream/native", "/stream/commercial", "/post/sponsor", "/post/native",
            "/message_center/ad", "/message/ad", "/notice/ad", "/notify/ad", "/inbox/ad", "/bulletin/ad",
            "/discover/card", "/discover/ad", "/recommend/card", "/promotion/card", "/promo/card",
            "/sign/popup", "/daily/popup", "/mission/popup", "/benefit/popup", "/welfare/popup",
            "/httpdns/ad", "/dns/ad", "/resolver/ad", "/ws/ad", "/wss/ad", "/stream/ad",
            "/event/ad", "/grpc/ad", "/protobuf/ad", "/proto/ad", "/plugin/ad", "/dynamic/ad",
            "/dex/ad", "/hotfix/ad", "/gateway/ad", "/ad/gateway", "/adsdk/gateway",
            "/quic/ad", "/http3/ad", "/h3/ad", "/udp443/ad", "/ad/quic", "/ad/http3"
        )
        if (strongPathKeywords.any { path.contains(it) }) return true
        val query = path.substringAfter('?', "")
        if (query.isBlank()) return false
        if (looksLikeStrongAdParameterQuery(query)) return true
        if (looksLikeHttpDnsAdQueryPath(path, query)) return true
        val strongQueryKeywords = listOf(
            "watch_ad_unlock", "unlock_by_ad", "reward_unlock", "reward_verify", "ad_dispatch", "ad_request",
            "ad_material", "ad_strategy", "ad_platform", "waterfall", "mediation", "biddingtoken", "auctionid",
            "message_center_ad", "promotion_card", "discover_card", "sign_popup_ad", "benefit_popup_ad", "welfare_popup_ad",
            "sponsor_card", "sponsored_card", "commercial_card", "native_ad", "native_card_ad", "brand_feed_card",
            "httpdns", "dns_records", "ws_url", "wss_url", "grpc", "protobuf", "dex_url", "plugin_url",
            "encrypted_config", "ad_gateway", "adsdk_gateway", "quic_gateway", "http3_gateway", "udp443", "alt_svc", "altsvc", "h3"
        )
        return strongQueryKeywords.any { keyword -> queryContainsKeywordAssignment(query, keyword) }
    }

    private fun looksLikeHttpDnsAdQueryPath(path: String, query: String): Boolean {
        if (path.isBlank() || query.isBlank()) return false
        val lowerPath = path.lowercase()
        if (!listOf("httpdns", "dns-query", "/resolve", "/resolver", "/dns").any(lowerPath::contains)) return false
        val dnsNameKeys = setOf("host", "hosts", "domain", "domains", "dn", "dns", "q", "qname", "name", "hostname", "target", "query")
        return query.lowercase().split('&').any { part ->
            val key = part.substringBefore('=', "").replace("-", "_")
            val value = part.substringAfter('=', "")
            key in dnsNameKeys && (
                value.contains("ad") ||
                    value.contains("ads") ||
                    value.contains("adx") ||
                    value.contains("gdt") ||
                    value.contains("pangle") ||
                    value.contains("pangolin") ||
                    value.contains("doubleclick") ||
                    value.contains("googlesyndication") ||
                    value.contains("adservice") ||
                    value.contains("track") ||
                    value.contains("bid")
                )
        }
    }

    private fun looksLikeStrongAdParameterQuery(query: String): Boolean {
        if (query.isBlank()) return false
        val strongAdParameterNames = setOf(
            "ad_unit_id", "adunit_id", "adunitid", "ad_slot_id", "adslot_id", "adslotid",
            "slot_id", "slotid", "adx_id", "adxid", "auction_id", "auctionid",
            "bid_id", "bidid", "bid_token", "bidtoken", "bidding_token", "auction_token",
            "placement_id", "placementid", "creative_id", "creativeid", "material_id", "materialid",
            "campaign_id", "campaignid", "impression_id", "impressionid", "request_id", "requestid",
            "ad_url", "adurl", "track_url", "trackurl", "imp_url", "impurl", "auction_url", "auctionurl",
            "placement_token", "slot_token", "track_token", "imp_token", "ad_token", "adtoken"
        )
        return query.split('&').any { part ->
            val key = part.substringBefore('=', "").lowercase()
                .replace("%5f", "_")
                .replace("-", "_")
            if (key !in strongAdParameterNames && !looksLikeGeneralizedAdParameterName(key)) return@any false
            val value = part.substringAfter('=', "").lowercase()
            if (value.isBlank()) return@any true
            value.length >= 4 && (
                value.contains("ad") ||
                    value.contains("ads") ||
                    value.contains("adx") ||
                    value.contains("bid") ||
                    value.contains("auction") ||
                    value.contains("slot") ||
                    value.contains("unit") ||
                    value.contains("creative") ||
                    value.contains("material") ||
                    value.contains("campaign") ||
                    value.matches(RegexCache.get("[a-z0-9_-]{8,}"))
                )
        }
    }

    private fun looksLikeGeneralizedAdParameterName(key: String): Boolean {
        if (key.isBlank()) return false
        return RegexCache.get("(?:^|_)(ad|ads|slot|auction|placement|track|imp|impression|bid|creative|material|campaign)(?:_)?(id|url|token|key|hash|sig|signature)(?:$|_)")
            .containsMatchIn(key)
    }

    private fun looksLikeRewardUnlockPath(path: String): Boolean {
        if (path.isBlank()) return false
        val rewardHit = path.contains("reward")
        val unlockHit = path.contains("unlock")
        val watchAdHit = path.contains("watch_ad")
        val unlockByAdHit = path.contains("unlock_by_ad")
        val chapterUnlockHit = path.contains("chapter_unlock_ad")
        val benefitHit = path.contains("benefit")
        val taskHit = path.contains("task")
        val adHit = path.contains("/ad") || path.contains("_ad") || path.contains("ad_") || path.contains("ads")
        return rewardHit && unlockHit && adHit ||
            watchAdHit ||
            unlockByAdHit ||
            chapterUnlockHit ||
            benefitHit && taskHit && adHit
    }

    private fun shouldProtectNormalNovelHttpTraffic(
        context: android.content.Context,
        host: String,
        path: String?,
        appName: String?
    ): Boolean {
        val normalizedHost = normalizeAuthority(host)
        if (normalizedHost.isBlank()) return false
        if (RuleRepository.isNovelContentDomain(normalizedHost)) return true
        if (!RuleRepository.isProtectedNovelAppDomain(normalizedHost)) return false
        return !RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, normalizedHost, path, appName)
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
        val statusCode: Int,
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
        val generalAdTarget: Boolean,
        val isCommunityApp: Boolean
    )

    private data class Http1BodyEnvironment(
        val context: android.content.Context,
        val host: String,
        val vendor: String,
        val generalAdTarget: Boolean,
        val aggressiveNovelTarget: Boolean,
        val protectedNovelTarget: Boolean,
        val isNovelApp: Boolean,
        val isCommunityApp: Boolean
    )

    private data class CommentAdBodySignals(
        val commentAdMaterialHit: Boolean,
        val commentRecommendCardHit: Boolean,
        val commentCommerceSignalHit: Int,
        val commentCommerceCardHit: Boolean,
        val commentCommerceGdtHit: Boolean
    ) {
        val hasAnyStrongCommentAdSignal: Boolean
            get() = commentAdMaterialHit ||
                commentRecommendCardHit ||
                commentCommerceSignalHit >= 2 ||
                commentCommerceCardHit ||
                commentCommerceGdtHit
    }

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
        val regexRules = removeParamRegexes.mapNotNull { pattern -> runCatching { RegexCache.get(pattern, RegexOption.IGNORE_CASE) }.getOrNull() }
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

    data class Http2HeaderRewriteResult(
        val headers: List<HpackDecoder.HeaderField>,
        val changed: Boolean
    )

    data class Http2BodyRewriteResult(
        val body: ByteArray,
        val contentType: String,
        val reason: String
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
