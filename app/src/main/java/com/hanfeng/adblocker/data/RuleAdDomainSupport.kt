package com.HanFeng.data

import com.HanFeng.core.network.RegexCache

object RuleAdDomainSupport {
    private val alphanumericAdPattern = Regex("[0-9]+.*ad|ad.*[0-9]+")

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
            val labels = domain.split('.', '-', '_').filter { it.isNotBlank() }
            return labels.any { it == keyword }
        }
        return domain.contains(keyword) || normalizedTokens.contains(keyword)
    }

    fun looksLikePushRecommendationAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        val normalizedTokens = lower.replace(RegexCache.get("[^a-z0-9]"), "")
        val pushSignals = listOf("push", "pushad", "adpush", "notify", "notification", "message", "msg", "inbox")
        val recommendationSignals = listOf("recommend", "recommendation", "feed", "stream", "timeline", "discover")
        val adSignals = listOf(
            "ad", "ads", "promo", "promotion", "banner", "material", "creative", "offer", "offerwall",
            "campaign", "commercial", "sponsor", "market", "install", "download", "deeplink", "landing",
            "task", "mission", "welfare", "benefit", "coin", "reward", "game", "live", "search",
            "hotword", "recommend", "experiment", "abtest", "miniapp", "mini-program",
            "clipboard", "share", "serviceworker", "widget", "shortcut", "badge", "lockscreen",
            "comment", "reply", "danmaku", "profile", "follow", "inbox", "message",
            "template", "cloud", "asset", "resource", "bundle", "patch", "coupon", "redpacket",
            "commerce", "affiliate", "commission", "local", "nearby", "survey", "leadgen", "calendar",
            "browser", "startpage", "newtab", "appstore", "oem", "rom", "security", "tv", "wear", "car"
        )
        val hasPushOrRecommend = pushSignals.any { keywordMatches(lower, normalizedTokens, it) } ||
            recommendationSignals.any { keywordMatches(lower, normalizedTokens, it) }
        if (!hasPushOrRecommend) return false
        return adSignals.any { keywordMatches(lower, normalizedTokens, it) }
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
        val normalizedTokens = lower.replace(RegexCache.get("[^a-z0-9]"), "")
        val normalizedVendor = normalizeVendorName(vendor.ifBlank { defaultVendor })
        if (highConfidenceAdSdkDomains.any { normalized == it || normalized.endsWith(".$it") }) {
            return true
        }
        if (normalizedVendor in highConfidenceAdSdkVendors) {
            return true
        }
        val sdkInfraSignals = listOf(
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
            "http3ad", "h3ad", "udp443ad", "adudp443", "quicgatewayad", "http3gatewayad"
        )
        if (sdkInfraSignals.any { keywordMatches(lower, normalizedTokens, it) }) return true
        val sdkVendorSignals = listOf(
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
            "rayjump", "singular", "appsflyer", "adjust", "kochava", "branchmetrics"
        )
        return sdkVendorSignals.any { keywordMatches(lower, normalizedTokens, it) }
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
        val strongAdInfraMarkers = listOf(
            "pangolin", "pangle", "gromore", "adsdk", "adservice", "adserver", "adtrack",
            "reward", "splash", "offerwall", "unionad", "mediation", "waterfall", "bidding"
        )
        return strongAdInfraMarkers.none(lower::contains)
    }

    fun looksLikeWhitelistedRootAdSubdomain(
        domain: String,
        looksLikePushRecommendationAdDomain: (String) -> Boolean,
        looksLikeAdSdkInfraDomain: (String) -> Boolean
    ): Boolean {
        val lower = domain.lowercase()
        val strongAdSubdomainMarkers = listOf(
            "ad.", "ads.", "adx.", "adx-", "adservice.", "adserver.", "adtrack.", "adtracker.",
            "adsdk.", "sdkad.", "gdt.", "pangle.", "pangolin.", "gromore.", "sigmob.",
            "topon.", "tradplus.", "adscope.", "mobvista.", "mintegral.", "applovin.",
            "unityads.", "vungle.", "offerwall.", "rewardvideo.", "open_screen.", "startupad.",
            "launchad.", "splashad.", "feedad.", "nativead."
        )
        if (strongAdSubdomainMarkers.any { marker -> lower.startsWith(marker) || lower.contains(".$marker") }) return true
        if (looksLikePushRecommendationAdDomain(lower)) return true
        return looksLikeAdSdkInfraDomain(lower) && listOf(
            "showurl", "clickurl", "monitorurl", "impression", "playable", "endcard", "waterfall", "mediation", "bidding"
        ).any(lower::contains)
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
        val normalizedTokens = lower.replace(RegexCache.get("[^a-z0-9]"), "")
        val labels = lower.split('.', '-', '_').filter { it.isNotBlank() }
        if (isLowValueSuspiciousSampleDomain(lower)) return false
        val baseMatch = adKeywords.any { keyword ->
            if (keyword in weakAdKeywords) {
                labels.any { it == keyword || it.startsWith("$keyword-") || it.endsWith("-$keyword") }
            } else {
                keywordMatches(lower, normalizedTokens, keyword)
            }
        }
        if (baseMatch) return true

        val strongAdLabels = setOf(
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
            "http3ad", "h3ad", "udp443ad", "adudp443", "quicgatewayad", "http3gatewayad"
        )
        if (labels.any(strongAdLabels::contains)) return true

        val adSdkPatterns = listOf(
            ".adsdk.", ".adservice.", ".adnetwork.", ".adserver.",
            ".adtrack.", ".adtracker.", ".admanager.", ".adplatform.",
            ".ssp.", ".dsp.", ".adx.", ".rtb.", ".mediation.",
            ".bidding.", ".auction.", ".offerwall.", ".rewardvideo."
        )
        if (adSdkPatterns.any { it in lower }) return true

        val protectedCommunityDomains = listOf("coolapk.com", "coolapkmarket.com")
        if (protectedCommunityDomains.any { lower == it || lower.endsWith(".$it") }) return false

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
        if (novelAdInfraPatterns.any { pattern -> lower.contains(pattern) }) return true
        if (looksLikePushRecommendationAdDomain(lower)) return true
        return looksLikeAdSdkInfraDomain(lower)
    }
}
