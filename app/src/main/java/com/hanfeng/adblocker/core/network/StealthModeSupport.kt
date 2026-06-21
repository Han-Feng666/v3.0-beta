package com.HanFeng.core.network

object StealthModeSupport {

    val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "fbclid", "fb_action_ids", "fb_action_types", "fb_source", "fb_ref",
        "gclid", "gclsrc", "gbraid", "wbraid", "gad_source",
        "dclid",
        "msclkid",
        "igshid",
        "_ga", "_gl", "_gac", "_gcl_aw", "_gcl_au", "_gcl_dc", "_gcl_gf",
        "mc_cid", "mc_eid", "mc_tc",
        "oly_anon_id", "oly_enc_id",
        "vero_id", "vero_conv",
        "wickedid",
        "yclid",
        "_hsenc", "_hsmi",
        "__s",
        "ml_subscriber", "ml_subscriber_hash",
        "trk_contact", "trk_msg", "trk_module", "trk_sid",
        "_openstat",
        "_ke",
        "spm",
        "sc_campaign", "sc_channel", "sc_content", "sc_medium", "sc_source", "sc_term",
        "et_rid", "et_cid",
        "s_kwcid",
        "li_fat_id",
        "ttclid", "twclid",
        "rdt_cid",
        "irclickid", "irpid",
        "utm_custom",
        "mk_tok", "mtm_campaign", "mtm_cid", "mtm_content", "mtm_group", "mtm_keyword",
        "mtm_medium", "mtm_placement", "mtm_source", "pk_campaign", "pk_keyword",
        "pk_medium", "pk_source",
    )

    val TRACKING_HEADERS = setOf(
        "x-client-data",
        "sec-ch-ua",
        "sec-ch-ua-arch",
        "sec-ch-ua-bitness",
        "sec-ch-ua-form-factors",
        "sec-ch-ua-full-version",
        "sec-ch-ua-full-version-list",
        "sec-ch-ua-mobile",
        "sec-ch-ua-model",
        "sec-ch-ua-platform",
        "sec-ch-ua-platform-version",
        "sec-ch-ua-wow64",
        "sec-fetch-site",
        "sec-fetch-mode",
        "sec-fetch-dest",
        "sec-fetch-user",
        "sec-gpc",
        "x-requested-with",
        "x-forwarded-for",
        "x-real-ip",
        "true-client-ip",
        "x-originating-ip",
        "x-https",
        "x-forwarded-proto",
        "x-forwarded-host",
        "x-forwarded-port",
        "x-wap-profile",
        "x-att-deviceid",
        "x-uidh",
        "x-wap-network-client-msisdn",
        "x-up-calling-line-id",
        "x-nokiastr-gateway-id",
        "x-msisdn",
        "x-msgr-ua",
        "x-operamini-phone-ua",
        "x-operamini-phone",
        "x-device-user-agent",
        "profile",
        "imsi",
    )

    fun stripTrackingParams(url: String): String {
        if (url.isBlank()) return url
        val qIndex = url.indexOf('?')
        if (qIndex < 0 || qIndex == url.length - 1) return url
        val base = url.substring(0, qIndex)
        val query = url.substring(qIndex + 1)
        val params = query.split('&')
        val filtered = params.filter { param ->
            val key = param.substringBefore('=').lowercase().trim()
            key !in TRACKING_PARAMS && key.isNotBlank()
        }
        if (filtered.isEmpty()) return base
        return "$base?${filtered.joinToString("&")}"
    }

    fun sanitizeReferer(referer: String): String {
        if (referer.isBlank()) return referer
        val schemeEnd = referer.indexOf("://")
        if (schemeEnd < 0) return ""
        val pathStart = referer.indexOf('/', schemeEnd + 3)
        if (pathStart < 0) return referer
        return referer.substring(0, pathStart)
    }

    fun shouldRemoveRequestHeader(name: String): Boolean {
        return name.lowercase().trim() in TRACKING_HEADERS
    }

    fun shouldRemoveResponseHeader(name: String): Boolean {
        val lower = name.lowercase().trim()
        return lower == "set-cookie" ||
            lower == "x-content-duration" ||
            lower in TRACKING_HEADERS
    }

    fun applyStealthToRequest(
        method: String,
        url: String,
        headers: MutableMap<String, String>,
        referer: String?
    ) {
        val cleanedUrl = stripTrackingParams(url)
        if (cleanedUrl != url && headers.containsKey("host")) {
            headers[":cleaned-url"] = cleanedUrl
        }
        headers.keys.removeAll { shouldRemoveRequestHeader(it) }
        if (referer != null && referer.isNotBlank()) {
            headers["referer"] = sanitizeReferer(referer)
        }
    }

    fun applyStealthToResponse(headers: MutableMap<String, String>) {
        headers.keys.removeAll { shouldRemoveResponseHeader(it) }
    }
}
