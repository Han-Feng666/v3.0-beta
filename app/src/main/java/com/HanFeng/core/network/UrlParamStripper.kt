package com.HanFeng.core.network

import com.HanFeng.data.RuleRepository

object UrlParamStripper {
    val defaultAdQueryParams = setOf(
        "ad", "ads", "adid", "ad_id", "adunit", "ad_unit", "adslot", "ad_slot", "adpos", "ad_pos",
        "adscene", "ad_scene", "adposition", "ad_position", "adtag", "ad_tag", "adfrom", "ad_from",
        "advertid", "advert_id", "promotion", "promo", "promoid", "promo_id", "materialid", "material_id",
        "creativeid", "creative_id", "clickid", "click_id", "requestid", "request_id", "traceid", "trace_id",
        "ecpm", "preroll", "midroll", "postroll", "insert_ad", "feed_ad", "bannerid", "banner_id",
        "watch_ad", "watch_ad_unlock", "unlock_by_ad", "reward_amount", "coin_reward", "task_reward",
        "material_url", "material_urls", "landing_url", "landing_urls", "click_url", "click_urls",
        "show_url", "show_urls", "impression_url", "impression_urls", "monitor_url", "monitor_urls",
        "callback_url", "target_url", "deep_link", "download_url", "open_screen", "startup_ad",
        "promotion_card", "promo_card", "discover_card", "recommend_card", "message_center_ad",
        "slot_id", "slotid", "slot_name", "slotname", "placement_id", "placementid",
        "app_id", "appid", "app_key", "appkey", "media_id", "mediaid",
        "union_id", "unionid", "vendor_id", "vendorid", "channel_id", "channelid",
        "partner_id", "partnerid", "publisher_id", "publisherid",
        "mediation_id", "waterfall_id", "auction_id", "bid_id",
        "bidfloor", "bid_price", "bid_response", "bid_object",
        "expire_time", "refresh_time", "load_time", "display_time",
        "show_time", "click_time", "download_time", "install_time",
        "open_time", "close_time", "finish_time", "start_time",
        "play_duration", "play_time", "video_duration", "audio_duration",
        "interstitial_type", "splash_type", "banner_type", "native_type",
        "reward_type", "video_type", "feed_type", "stream_type",
        "template_id", "tag_id", "pos_id", "page_id", "section_id",
        "scene", "scenario", "scene_type", "page_type", "page_name",
        "orientation", "screen_orientation", "device_orientation",
        "is_ad", "has_ad", "contain_ad", "include_ad", "with_ad",
        "ad_count", "ad_index", "ad_seq", "ad_sequence",
        "is_video", "has_video", "video_ad", "video_ads",
        "is_splash", "is_interstitial", "is_banner", "is_native",
        "is_reward", "is_feed", "is_stream", "is_fullscreen",
        "event_id", "event_type", "event_name", "event_action",
        "log_id", "log_type", "log_extra", "log_event",
        "pangle", "gdt", "csj", "pgl", "openudid",
        "gaid", "idfa", "oaid", "androidid", "imei", "mac",
        "req_id", "rid", "trans_id", "session_id", "seq_id",
        "server_side_verification", "ssv", "reward_verify",
        "reward_name", "reward_item", "reward_currency",
        "reward_custom_data", "reward_extra", "user_id",
        "ad_network", "adn_id", "sdk_version", "api_version",
        "mediation", "mediated", "ad_source", "ad_type",
        "render_type", "creative_type", "interaction_type",
        "download_type", "landing_type", "deeplink_type",
        "track", "tracking", "track_event", "track_type",
        "debug", "debug_mode", "test_mode", "sandbox",
        "preload", "prefetch", "precache", "cache",
        "autoplay", "auto_play", "muted", "volume",
        "skip_time", "countdown", "reward_time", "lock_time",
        "payload", "payload_data", "extra_data", "ext_data",
        "encrypt", "encrypted", "sign", "signature", "token",
        "secret", "secret_key", "access_token", "auth_token",
        "reward_total", "reward_remain", "reward_multiple",
        "task_id", "task_type", "task_status", "task_progress",
        "mission_id", "mission_type", "quest_id", "achievement_id",
        "live", "livestream", "live_id", "room_id", "show_id",
        "feed", "feed_id", "stream_id", "timeline_id",
        "search", "search_id", "query_id", "keyword_id",
        "recommend", "recommend_id", "rec_id", "rec_type",
        "commercial", "commerce", "sponsor", "sponsored",
        "promoted", "installed", "installation", "conversion",
        "retention", "retarget", "reattribution", "reinstall",
        "persecond", "persec", "per_second", "bidfloor",
        "is_charge", "has_charge", "is_paid", "has_paid",
        "charge_amount", "charge_times", "virtual_currency",
        "currency_amount", "currency_name", "currency_type",
        "subsidy", "subsidy_amount", "subsidy_policy",
        "cost_price", "cost_type", "price_type", "bid_type",
        "chartboost", "vungle", "unity", "ironsource",
        "applovin", "mintegral", "sigmob", "tapjoy", "fyber",
        "inmobi", "adcolony", "my_target", "startapp",
        "nativex", "lifestreet", "pubnative", "smaato",
        "sdk_data", "sdk_info", "sdk_config", "sdk_extra",
        "grs_session_id"
    )

    fun stripAdParams(path: String, removeParams: Set<String>, removeParamRegexes: Set<String>): String {
        if (removeParams.isEmpty() && removeParamRegexes.isEmpty()) return path
        val questionMark = path.indexOf('?')
        if (questionMark < 0) return path
        val base = path.substring(0, questionMark)
        val query = path.substring(questionMark + 1)
        val params = query.split("&").filter { param ->
            val key = param.substringBefore('=', "").lowercase()
            if (removeParams.contains(key) || removeParams.any { p -> key == p.lowercase() }) return@filter false
            if (removeParamRegexes.any { re -> re.toRegex().matches(key) }) return@filter false
            true
        }
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }
}
