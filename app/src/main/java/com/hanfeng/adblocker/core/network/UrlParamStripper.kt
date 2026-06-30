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
        "promotion_card", "promo_card", "discover_card", "recommend_card", "message_center_ad"
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
