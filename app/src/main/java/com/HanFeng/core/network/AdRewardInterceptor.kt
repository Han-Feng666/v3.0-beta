package com.HanFeng.core.network

import com.HanFeng.data.RuleRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object AdRewardInterceptor {
    data class RewardResult(val intercepted: Boolean, val fakeResponseBody: String? = null, val statusCode: Int = 200)
    data class RewardStats(val totalIntercepted: AtomicLong = AtomicLong(0), val lastInterceptTime: AtomicLong = AtomicLong(0))
    data class RewardAppRecord(val appName: String, val domain: String, val timestamp: Long, val rewardType: String)

    val stats = RewardStats()
    private val recentIntercepts = ConcurrentHashMap<String, Long>()
    private const val COOLDOWN_MS = 30_000L

    enum class RewardSdk {
        PANGLE, GDT, KS, BAIDU, SIGMOB, MINTEGRAL, UNITY, ADMOB, VUNGLE, APPLOVIN, IRONSOURCE,
        CHARTBOOST, INMOBI, TAPJOY, STARTAPP, FYBER, MOBUVISTA, TOPON, TRADPLUS,
        SINGULAR, APPSFLYER, ADJUST, KOCHAVA, BRANCH, TENJIN,
        HUAWEI, XIAOMI, OPPO, VIVO, SAMSUNG,
        FACEBOOK, META, FIREBASE, ANALYTICS,
        BEEAD, YOUSU, ADVIEW, DOMOB, GUOZHEN, AIRPUS,
        JIATUAN, WUBI, CHUANGYI, FEIYU, YIXUAN,
        GENERIC
    }

    enum class RewardType {
        COIN,
        DIAMOND,
        GOLD,
        GEM,
        ENERGY,
        STAMINA,
        POWER,
        LIFE,
        HEART,
        ITEM,
        EQUIPMENT,
        WEAPON,
        ARMOR,
        SKILL,
        ABILITY,
        BUFF,
        BOOST,
        CHEST,
        MYSTERY_BOX,
        SUPPLY_CRATE,
        UNLOCK_CHAPTER,
        UNLOCK_CONTENT,
        UNLOCK_FEATURE,
        UNLOCK_LEVEL,
        UNLOCK_EPISODE,
        UNLOCK_NOVEL,
        UNLOCK_PREMIUM,
        SKIP_WAIT,
        SKIP_AD,
        SPEED_UP,
        INSTANT_COMPLETE,
        EXTRA_MOVE,
        EXTRA_TIME,
        EXTRA_STEP,
        EXTRA_TURN,
        LOTTERY_CHANCE,
        SPIN_CHANCE,
        GACHA_PULL,
        SUMMON_CHANCE,
        CONTINUE_GAME,
        RETRY,
        REVIVE,
        RESURRECT,
        DOUBLE_REWARD,
        DOUBLE_COIN,
        FREE_TRIAL,
        VIP_TIME,
        PREMIUM_ACCESS,
        COUPON,
        VOUCHER,
        DISCOUNT,
        CASHBACK,
        REDPACKET,
        EVOLUTION,
        AWAKENING,
        TRANSCENDENCE,
        CUSTOM
    }

    fun identifyRewardSdk(domain: String, path: String, body: String?): RewardSdk? {
        val lower = domain.lowercase()
        val lowerPath = path.lowercase()
        val lowerBody = body?.lowercase() ?: ""
        return when {
            lower.contains("pangleglobal") || lower.contains("snssdk") || lower.contains("bytedance")
                || lower.contains("bytecdn") || lower.contains("pgl-stat") || lowerBody.contains("pangle") -> RewardSdk.PANGLE
            lower.contains("gdt.qq") || lower.contains("qzs.gdt") || lower.contains("mi.gdt")
                || lower.contains("v2.gdt") || lower.contains("ylh") || lowerBody.contains("优量汇") -> RewardSdk.GDT
            lower.contains("kuaishou") || lower.contains("yximgs") || lower.contains("kwai")
                || lower.contains("ksapis") || lowerBody.contains("ksad") == true -> RewardSdk.KS
            lower.contains("mobads.baidu") || lower.contains("baidustatic") || lower.contains("cpro.baidu")
                || lower.contains("mssp.baidu") || lowerBody.contains("baiduAd") == true -> RewardSdk.BAIDU
            lower.contains("sigmob") -> RewardSdk.SIGMOB
            lower.contains("mintegral") || lower.contains("mobvista") || lower.contains("mbridge") -> RewardSdk.MINTEGRAL
            lower.contains("unity3d") || lower.contains("unityads") || lower.contains("unity-ads") -> RewardSdk.UNITY
            lower.contains("doubleclick") || lower.contains("googleads") || lower.contains("googlesyndication")
                || lower.contains("admob") || lower.contains("googleadmob") -> RewardSdk.ADMOB
            lower.contains("vungle") -> RewardSdk.VUNGLE
            lower.contains("applovin") || lower.contains("applvn") -> RewardSdk.APPLOVIN
            lower.contains("ironsrc") || lower.contains("supersonicads") -> RewardSdk.IRONSOURCE
            lower.contains("chartboost") || lower.contains("dtscbn") -> RewardSdk.CHARTBOOST
            lower.contains("inmobi") -> RewardSdk.INMOBI
            lower.contains("tapjoy") || lower.contains("tapjoyads") -> RewardSdk.TAPJOY
            lower.contains("startapp") || lower.contains("startappads") -> RewardSdk.STARTAPP
            lower.contains("fyber") || lower.contains("ironSource") -> RewardSdk.FYBER
            lower.contains("mobvista") -> RewardSdk.MOBUVISTA
            lower.contains("topon") || lower.contains("anyads") -> RewardSdk.TOPON
            lower.contains("tradplus") || lower.contains("tpad") -> RewardSdk.TRADPLUS
            lower.contains("singular") || lower.contains("smngr") -> RewardSdk.SINGULAR
            lower.contains("appsflyer") || lower.contains("af") -> RewardSdk.APPSFLYER
            lower.contains("adjust") || lower.contains("adj") -> RewardSdk.ADJUST
            lower.contains("kochava") -> RewardSdk.KOCHAVA
            lower.contains("branch") || lower.contains("bnc.lt") -> RewardSdk.BRANCH
            lower.contains("tenjin") || lower.contains("tenjin.io") -> RewardSdk.TENJIN
            lower.contains("huawei") || lower.contains("hicloud") || lower.contains("hms")
                || lower.contains("hwad") || lowerBody.contains("huaweiads") -> RewardSdk.HUAWEI
            lower.contains("xiaomi") || lower.contains("miui") || lower.contains("mimob")
                || lowerBody.contains("xiaomiad") -> RewardSdk.XIAOMI
            lower.contains("oppo") || lower.contains("heytap") || lower.contains("nearme")
                || lowerBody.contains("oppoads") -> RewardSdk.OPPO
            lower.contains("vivo") || lower.contains("jovi") || lowerBody.contains("vivoad") -> RewardSdk.VIVO
            lower.contains("samsung") || lower.contains("galaxy") || lowerBody.contains("samsungads") -> RewardSdk.SAMSUNG
            lower.contains("facebook") || lower.contains("audience_network") || lower.contains("fbad")
                || lower.contains("meta") || lowerBody.contains("fb_redux") -> RewardSdk.FACEBOOK
            lower.contains("firebase") || lower.contains("google-analytics") || lower.contains("analytics")
                || lowerBody.contains("firebase") -> RewardSdk.FIREBASE
            lower.contains("beead") || lower.contains("bee-ad") || lowerBody.contains("beead") -> RewardSdk.BEEAD
            lower.contains("yousu") || lower.contains("yousuad") || lowerBody.contains("yousu") -> RewardSdk.YOUSU
            lower.contains("adview") || lower.contains("adviewcn") || lowerBody.contains("adview") -> RewardSdk.ADVIEW
            lower.contains("domob") || lower.contains("domobads") || lowerBody.contains("domob") -> RewardSdk.DOMOB
            lower.contains("guozhen") || lower.contains("guozhenad") || lowerBody.contains("guozhen") -> RewardSdk.GUOZHEN
            lower.contains("airpus") || lower.contains("airpusad") || lowerBody.contains("airpus") -> RewardSdk.AIRPUS
            lower.contains("jiatuan") || lower.contains("jiatuanad") || lowerBody.contains("jiatuan") -> RewardSdk.JIATUAN
            lower.contains("wubi") || lower.contains("wubiad") || lowerBody.contains("wubi") -> RewardSdk.WUBI
            lower.contains("chuangyi") || lower.contains("chuangyiad") || lowerBody.contains("chuangyi") -> RewardSdk.CHUANGYI
            lower.contains("feiyu") || lower.contains("feiyuad") || lowerBody.contains("feiyu") -> RewardSdk.FEIYU
            lower.contains("yixuan") || lower.contains("yixuanad") || lowerBody.contains("yixuan") -> RewardSdk.YIXUAN
            isRewardAdPath(path) || isRewardAdDomain(domain) -> RewardSdk.GENERIC
            else -> null
        }
    }

    fun identifyRewardType(path: String, body: String?): String {
        val lower = path.lowercase()
        val bodyLower = body?.lowercase() ?: ""
        return when {
            lower.contains("reward") && lower.contains("video") -> "reward_video"
            lower.contains("reward") && lower.contains("interstitial") -> "reward_interstitial"
            lower.contains("reward") || lower.contains("ad_reward") || lower.contains("watch_ad") -> "reward_ad"
            lower.contains("offerwall") || lower.contains("wall_ad") -> "offerwall"
            lower.contains("unlock_by_ad") || lower.contains("ad_unlock") -> "ad_unlock"
            bodyLower.contains("reward") && bodyLower.contains("amount") -> "reward_payload"
            lower.contains("chapter") && lower.contains("ad") -> "chapter_ad_reward"
            lower.contains("episode") && lower.contains("ad") -> "episode_ad_reward"
            lower.contains("drama") && lower.contains("ad") -> "drama_ad_reward"
            bodyLower.contains("watch_ad") || bodyLower.contains("video_completed") -> "video_completed"
            bodyLower.contains("ad_finished") || bodyLower.contains("ad_completed") -> "ad_completed"
            bodyLower.contains("reward_complete") || bodyLower.contains("reward_finish") -> "reward_complete"
            lower.contains("incentive") || bodyLower.contains("incentive") -> "incentive_reward"
            lower.contains("s2s") || lower.contains("server_to_server") || lower.contains("postback") -> "s2s_callback"
            lower.contains("conversion") || lower.contains("attribution") -> "conversion_reward"
            lower.contains("install") && lower.contains("callback") -> "install_reward"
            lower.contains("task") && (lower.contains("complete") || lower.contains("reward")) -> "task_reward"
            lower.contains("survey") && (lower.contains("complete") || lower.contains("reward")) -> "survey_reward"
            lower.contains("sign") && lower.contains("reward") -> "sign_in_reward"
            lower.contains("daily") && lower.contains("reward") -> "daily_reward"
            lower.contains("checkin") && lower.contains("reward") -> "checkin_reward"
            lower.contains("spin") || lower.contains("wheel") || lower.contains("lottery") -> "lucky_draw_reward"
            lower.contains("redpacket") || lower.contains("hongbao") -> "redpacket_reward"
            lower.contains("level") && lower.contains("reward") -> "level_reward"
            lower.contains("achievement") && lower.contains("reward") -> "achievement_reward"
            lower.contains("milestone") && lower.contains("reward") -> "milestone_reward"
            lower.contains("combo") && lower.contains("reward") -> "combo_reward"
            lower.contains("share") && lower.contains("reward") -> "share_reward"
            lower.contains("invite") && lower.contains("reward") -> "invite_reward"
            lower.contains("referral") && lower.contains("reward") -> "referral_reward"
            else -> "generic_reward"
        }
    }

    fun isRewardAdPath(path: String): Boolean {
        val lower = path.lowercase()
        val rewardKeywords = listOf(
            "reward", "reward_video", "rewarded_video", "reward_ad", "rewarded_ad",
            "watch_ad", "unlock_by_ad", "ad_unlock", "free_reward", "free_ad",
            "offerwall", "task_wall", "reward_wall", "offer_wall",
            "video_reward", "video_ad", "fullscreen_ad", "interstitial_ad",
            "get_reward", "claim_reward", "collect_reward", "receive_reward",
            "earn_reward", "grant_reward", "reward_callback", "reward_verify",
            "ssv_callback", "server_callback", "reward_notify",
            "sdk_reward", "mediation_reward", "adn_reward",
            "chapter_reward", "episode_reward", "drama_reward",
            "coin_ad", "coin_reward", "double_reward", "booster_ad",
            "revive_ad", "revival_ad", "bonus_ad", "gift_ad",
            "sign_reward", "daily_reward", "login_reward",
            "lucky", "spin_reward", "scratch_ad", "draw_ad",
            "redpacket_ad", "red_packet_ad", "hongbao_ad",
            "game_reward", "level_reward", "mission_reward", "quest_reward",
            "energy_ad", "stamina_ad", "lives_ad", "heart_ad",
            "gem_ad", "diamond_ad", "gold_ad", "currency_ad",
            "skip_ad", "no_ad", "vip_ad", "premium_ad",
            "incentive_video", "incentive_ad", "inspired_ad",
            "s2s_reward", "server_to_server", "postback",
            "ad_complete", "ad_finished", "ad_ended",
            "video_complete", "video_finished", "video_ended",
            "conversion_callback", "attribution_callback",
            "install_callback", "click_callback", "event_callback",
            "log_event", "reward_event", "earn_event",
            "task_complete", "task_reward", "survey_complete",
            "survey_reward", "install_verified",
            "click_tracked", "conversion_tracked",
            "offer_complete", "offerwall_complete",
            "milestone_reward", "combo_reward",
            "achievement_reward", "referral_reward",
            "invite_reward", "share_reward",
            "prize_grant", "lottery_reward", "wheel_reward",
            "checkin_reward", "checkin_ad",
            "ad_incentive", "incentive_reward",
            "reward_incentive", "video_incentive"
        )
        return rewardKeywords.any { lower.contains(it) }
    }

    fun isRewardAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        val rewardDomains = listOf(
            "api-access.pangleglobal.com", "pangleglobal.com", "pangle.com",
            "mi.gdt.qq.com", "v2.gdt.qq.com", "sdk.e.qq.com", "gdt.qq.com",
            "api.e.kuaishou.com", "api-ks.kuaishou.com", "kuaishou.com",
            "mobads.baidu.com", "cpro.baidustatic.com", "baidustatic.com",
            "access.sigmob.cn", "api.sigmob.cn", "sigmob.cn",
            "api.mintegral.com", "mobvista.com", "mintegral.com",
            "configv2.unityads.unity3d.com", "auction.unityads.unity3d.com", "unity3d.com",
            "googleads.g.doubleclick.net", "pagead2.googlesyndication.com", "doubleclick.net",
            "api.vungle.com", "vungle.com",
            "api.applovin.com", "d.applovin.com", "rt.applovin.com", "applovin.com",
            "api.ironsrc.com", "init.ironsrc.com", "ironsrc.com",
            "chartboost.com", "dtscbn.com",
            "inmobi.com", "tapjoy.com", "tapjoyads.com",
            "startapp.com", "startappads.com",
            "fyber.com", "supersonicads.com",
            "mobvista.com", "mbridge.com",
            "topon.com", "anyads.com",
            "tradplus.com", "tpad.com",
            "singular.net", "smngr.com",
            "appsflyer.com", "af.com",
            "adjust.com", "adj.com",
            "kochava.com",
            "branch.io", "bnc.lt",
            "tenjin.io",
            "huawei.com", "hicloud.com", "hwad.com",
            "xiaomi.com", "miui.com", "mimob.com",
            "oppo.com", "heytap.com", "nearme.com.cn",
            "vivo.com", "jovi.com",
            "samsung.com", "galaxy.com",
            "facebook.com", "audience_network.com", "fbad.com",
            "firebase.com", "google-analytics.com",
            "beead.com", "bee-ad.com",
            "yousu.com", "yousuad.com",
            "adview.com", "adview.cn",
            "domob.com", "domob.cn",
            "guozhen.com", "guozhenad.com",
            "airpus.com", "airpusad.com",
            "jiatuan.com", "jiatuanad.com",
            "wubi.com", "wubiad.com",
            "chuangyi.com", "chuangyiad.com",
            "feiyu.com", "feiyuad.com",
            "yixuan.com", "yixuanad.com"
        )
        return rewardDomains.any { lower == it || lower.endsWith(".$it") }
    }

    fun isAdLoadRequest(path: String, domain: String, method: String): Boolean {
        val lower = path.lowercase()
        if (!isRewardAdDomain(domain) && !isRewardAdPath(path)) return false
        val loadKeywords = listOf(
            "load", "fetch", "request", "preload", "prefetch",
            "get_ad", "show_ad", "display_ad", "play_ad",
            "start_ad", "init_ad", "prepare_ad", "cache_ad",
            "bidding", "bid_request", "auction_request",
            "waterfall", "mediation", "ad_request",
            "fill", "ad_fill", "ad_serve", "serve_ad",
            "render", "ad_render", "render_ad",
            "present", "ad_present", "present_ad",
            "queue", "ad_queue", "queue_ad",
            "ready", "ad_ready", "ready_ad",
            "available", "ad_available",
            "check", "ad_check", "check_ad",
            "status", "ad_status", "status_ad"
        )
        return loadKeywords.any { lower.contains(it) }
    }

    fun isRewardVerificationCallback(path: String, body: String?): Boolean {
        val lower = path.lowercase()
        val bodyLower = body?.lowercase() ?: ""
        val verifyKeywords = listOf(
            "verify", "verification", "validate", "validation",
            "callback", "notify", "notification", "complete",
            "finish", "done", "success", "succeed",
            "close", "dismiss", "end", "exit",
            "impression", "imp", "track", "tracking",
            "log_reward", "report_reward", "record_reward",
            "confirm", "confirmation", "authenticate", "authentication",
            "check", "verify_reward", "verify_ad",
            "ad_verify", "ad_confirm", "ad_check",
            "reward_verify", "reward_confirm", "reward_check",
            "postback", "s2s_callback", "server_callback",
            "conversion", "attribution", "install_verify",
            "click_verify", "install_postback", "click_postback"
        )
        return verifyKeywords.any { lower.contains(it) } && hasRewardField(bodyLower)
    }

    private fun hasRewardField(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val rewardFields = listOf(
            "reward", "coin", "gold", "gem", "diamond",
            "amount", "quantity", "count", "value",
            "grant", "award", "unlock", "give",
            "completed", "finished", "succeed", "success",
            "earned", "collected", "received", "claim",
            "bonus", "prize", "gift", "cash", "point",
            "score", "credit", "balance", "wallet",
            "currency", "item", "inventory", "loot",
            "chest", "box", "pack", "card",
            "energy", "stamina", "life", "heart", "hp",
            "exp", "experience", "level", "xp",
            "ticket", "key", "token", "badge",
            "achievement", "milestone", "combo",
            "daily", "checkin", "login", "share",
            "invite", "referral", "task", "mission",
            "quest", "challenge", "event",
            "spin", "wheel", "scratch", "draw",
            "lucky", "lottery", "raffle",
            "redpacket", "red_packet", "hongbao", "angpao"
        )
        return rewardFields.any { body.contains(it) }
    }

    fun isInCooldown(key: String): Boolean {
        val timestamp = recentIntercepts[key] ?: return false
        return System.currentTimeMillis() - timestamp < COOLDOWN_MS
    }

    fun recordIntercept(key: String) {
        recentIntercepts[key] = System.currentTimeMillis()
        stats.totalIntercepted.incrementAndGet()
        stats.lastInterceptTime.set(System.currentTimeMillis())
    }

    fun generateFakeAdCompleteResponse(sdk: RewardSdk, rewardType: RewardType = RewardType.COIN): String {
        val now = System.currentTimeMillis()
        val (rewardName, rewardAmount) = getRewardNameAndAmount(rewardType)
        return when (sdk) {
            RewardSdk.PANGLE -> """{"code":0,"message":"success","data":{"reward_verify":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}","extra":"$now","trans_id":"pangle_$now"}}"""
            RewardSdk.GDT -> """{"ret":0,"msg":"ok","data":{"is_rewarded":true,"reward_info":{"reward_type":1,"reward_count":$rewardAmount,"reward_name":"$rewardName"},"trans_id":"gdt_$now"}}"""
            RewardSdk.KS -> """{"result":1,"error_msg":"","data":{"ad_completed":true,"reward_amount":$rewardAmount,"reward_name":"$rewardName","reward_type":"${rewardType.name.lowercase()}","extra_info":"{}","click_id":"ks_$now"}}"""
            RewardSdk.BAIDU -> """{"error_code":0,"msg":"success","data":{"reward_verify":true,"reward_amount":$rewardAmount,"reward_name":"$rewardName","reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.SIGMOB -> """{"code":0,"data":{"is_rewarded":true,"reward_amount":$rewardAmount,"reward_name":"$rewardName","reward_type":"${rewardType.name.lowercase()}","trans_id":"sm_$now"}}"""
            RewardSdk.MINTEGRAL -> """{"status":200,"msg":"","data":{"reward_status":1,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}","callback_id":"mtg_$now"}}"""
            RewardSdk.UNITY -> """{"status":"ok","data":{"completed":true,"reward":true,"reward_id":"unity_$now","reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.ADMOB -> """{"rewarded":true,"type":1,"amount":$rewardAmount,"reward_item":"$rewardName","reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.VUNGLE -> """{"status":"ok","events":[{"name":"AdCompleted","data":{"isCompletedView":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}]}"""
            RewardSdk.APPLOVIN -> """{"code":200,"data":{"ad_status":"watched","reward_amount":$rewardAmount,"reward_label":"$rewardName","reward_type":"${rewardType.name.lowercase()}","placement_id":"al_$now"}}"""
            RewardSdk.IRONSOURCE -> """{"status":"success","reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}","transaction_id":"is_$now"}"""
            RewardSdk.CHARTBOOST -> """{"status":"success","reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"cb_$now"}}"""
            RewardSdk.INMOBI -> """{"status":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"im_$now"}}}"""
            RewardSdk.TAPJOY -> """{"status":"success","reward":{"currency_name":"$rewardName","currency_amount":$rewardAmount,"type":"${rewardType.name.lowercase()}","transaction_id":"tj_$now"}}"""
            RewardSdk.STARTAPP -> """{"status":"ok","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"sa_$now"}}}"""
            RewardSdk.FYBER -> """{"status":"success","reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"fy_$now"}}"""
            RewardSdk.MOBUVISTA -> """{"status":200,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"mv_$now"}}}"""
            RewardSdk.TOPON -> """{"status":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"tp_$now"}}}"""
            RewardSdk.TRADPLUS -> """{"status":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"tplus_$now"}}}"""
            RewardSdk.SINGULAR -> """{"status":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"sg_$now"}}}"""
            RewardSdk.APPSFLYER -> """{"status":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"af_$now"}}}"""
            RewardSdk.ADJUST -> """{"status":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"adj_$now"}}}"""
            RewardSdk.KOCHAVA -> """{"status":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"kv_$now"}}}"""
            RewardSdk.BRANCH -> """{"status":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"br_$now"}}}"""
            RewardSdk.TENJIN -> """{"status":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"tj_$now"}}}"""
            RewardSdk.HUAWEI -> """{"ret":0,"msg":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"hw_$now"}}}"""
            RewardSdk.XIAOMI -> """{"code":0,"msg":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"mi_$now"}}}"""
            RewardSdk.OPPO -> """{"code":0,"msg":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"oppo_$now"}}}"""
            RewardSdk.VIVO -> """{"code":0,"msg":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"vivo_$now"}}}"""
            RewardSdk.SAMSUNG -> """{"code":0,"msg":"success","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"sam_$now"}}}"""
            RewardSdk.FACEBOOK -> """{"rewarded":true,"type":1,"amount":$rewardAmount,"reward_item":"$rewardName","reward_type":"${rewardType.name.lowercase()}","transaction_id":"fb_$now"}"""
            RewardSdk.META -> """{"rewarded":true,"type":1,"amount":$rewardAmount,"reward_item":"$rewardName","reward_type":"${rewardType.name.lowercase()}","transaction_id":"meta_$now"}"""
            RewardSdk.FIREBASE -> """{"status":"ok","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"fb_$now"}}}"""
            RewardSdk.ANALYTICS -> """{"status":"ok","data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"ana_$now"}}}"""
            RewardSdk.BEEAD -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"bee_$now"}}}"""
            RewardSdk.YOUSU -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"ys_$now"}}}"""
            RewardSdk.ADVIEW -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"av_$now"}}}"""
            RewardSdk.DOMOB -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"dm_$now"}}}"""
            RewardSdk.GUOZHEN -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"gz_$now"}}}"""
            RewardSdk.AIRPUS -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"ap_$now"}}}"""
            RewardSdk.JIATUAN -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"jt_$now"}}}"""
            RewardSdk.WUBI -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"wb_$now"}}}"""
            RewardSdk.CHUANGYI -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"cy_$now"}}}"""
            RewardSdk.FEIYU -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"fy_$now"}}}"""
            RewardSdk.YIXUAN -> """{"code":0,"data":{"reward":{"amount":$rewardAmount,"name":"$rewardName","type":"${rewardType.name.lowercase()}","transaction_id":"yx_$now"}}}"""
            RewardSdk.GENERIC -> """{"code":0,"msg":"ok","data":{"reward_completed":true,"reward_amount":$rewardAmount,"reward_name":"$rewardName","reward_type":"${rewardType.name.lowercase()}","timestamp":$now}}"""
        }
    }

    private fun getRewardNameAndAmount(rewardType: RewardType): Pair<String, Int> {
        return when (rewardType) {
            RewardType.COIN -> "金币" to 100
            RewardType.DIAMOND -> "钻石" to 10
            RewardType.GOLD -> "黄金" to 50
            RewardType.GEM -> "宝石" to 10
            RewardType.ENERGY -> "能量" to 50
            RewardType.STAMINA -> "体力" to 30
            RewardType.POWER -> "电力" to 50
            RewardType.LIFE -> "生命" to 1
            RewardType.HEART -> "红心" to 1
            RewardType.ITEM -> "道具" to 1
            RewardType.EQUIPMENT -> "装备" to 1
            RewardType.WEAPON -> "武器" to 1
            RewardType.ARMOR -> "防具" to 1
            RewardType.SKILL -> "技能" to 1
            RewardType.ABILITY -> "能力" to 1
            RewardType.BUFF -> "增益效果" to 1
            RewardType.BOOST -> "加速效果" to 1
            RewardType.CHEST -> "宝箱" to 1
            RewardType.MYSTERY_BOX -> "神秘宝箱" to 1
            RewardType.SUPPLY_CRATE -> "补给箱" to 1
            RewardType.UNLOCK_CHAPTER -> "章节解锁" to 1
            RewardType.UNLOCK_CONTENT -> "内容解锁" to 1
            RewardType.UNLOCK_FEATURE -> "功能解锁" to 1
            RewardType.UNLOCK_LEVEL -> "关卡解锁" to 1
            RewardType.UNLOCK_EPISODE -> "剧集解锁" to 1
            RewardType.UNLOCK_NOVEL -> "小说解锁" to 1
            RewardType.UNLOCK_PREMIUM -> "高级内容解锁" to 1
            RewardType.SKIP_WAIT -> "跳过等待" to 1
            RewardType.SKIP_AD -> "跳过广告" to 1
            RewardType.SPEED_UP -> "加速" to 1
            RewardType.INSTANT_COMPLETE -> "瞬间完成" to 1
            RewardType.EXTRA_MOVE -> "额外步数" to 5
            RewardType.EXTRA_TIME -> "额外时间" to 60
            RewardType.EXTRA_STEP -> "额外步骤" to 5
            RewardType.EXTRA_TURN -> "额外回合" to 1
            RewardType.LOTTERY_CHANCE -> "抽奖机会" to 1
            RewardType.SPIN_CHANCE -> "转盘次数" to 1
            RewardType.GACHA_PULL -> "抽卡次数" to 1
            RewardType.SUMMON_CHANCE -> "召唤机会" to 1
            RewardType.CONTINUE_GAME -> "继续游戏" to 1
            RewardType.RETRY -> "重试机会" to 1
            RewardType.REVIVE -> "复活" to 1
            RewardType.RESURRECT -> "复活" to 1
            RewardType.DOUBLE_REWARD -> "双倍奖励" to 1
            RewardType.DOUBLE_COIN -> "双倍金币" to 1
            RewardType.FREE_TRIAL -> "免费试用" to 1
            RewardType.VIP_TIME -> "VIP时间" to 1
            RewardType.PREMIUM_ACCESS -> "高级访问" to 1
            RewardType.COUPON -> "优惠券" to 1
            RewardType.VOUCHER -> "代金券" to 1
            RewardType.DISCOUNT -> "折扣" to 1
            RewardType.CASHBACK -> "返现" to 1
            RewardType.REDPACKET -> "红包" to 1
            RewardType.EVOLUTION -> "进化" to 1
            RewardType.AWAKENING -> "觉醒" to 1
            RewardType.TRANSCENDENCE -> "突破" to 1
            RewardType.CUSTOM -> "奖励" to 1
        }
    }

    fun generateFakeAdLoadResponse(sdk: RewardSdk): String {
        return when (sdk) {
            RewardSdk.PANGLE -> """{"code":0,"message":"no_ad","data":null}"""
            RewardSdk.GDT -> """{"ret":0,"msg":"no fill","data":{"list":[]}}"""
            RewardSdk.KS -> """{"result":0,"error_msg":"no ad fill","data":null}"""
            RewardSdk.BAIDU -> """{"error_code":0,"msg":"no ad available","data":{"ads":[]}}"""
            RewardSdk.SIGMOB -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.MINTEGRAL -> """{"status":200,"msg":"","data":{"ads":[]}}"""
            RewardSdk.UNITY -> """{"status":"ok","data":{"ads":[],"no_fill":true}}"""
            RewardSdk.ADMOB -> """{"ads":[],"status":"no_fill"}"""
            RewardSdk.VUNGLE -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.APPLOVIN -> """{"code":204,"data":{"ads":[],"message":"no fill"}}"""
            RewardSdk.IRONSOURCE -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.CHARTBOOST -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.INMOBI -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.TAPJOY -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.STARTAPP -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.FYBER -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.MOBUVISTA -> """{"status":200,"data":{"ads":[]}}"""
            RewardSdk.TOPON -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.TRADPLUS -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.SINGULAR -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.APPSFLYER -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.ADJUST -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.KOCHAVA -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.BRANCH -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.TENJIN -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.HUAWEI -> """{"ret":0,"msg":"no fill","data":{"ads":[]}}"""
            RewardSdk.XIAOMI -> """{"code":0,"msg":"no fill","data":{"ads":[]}}"""
            RewardSdk.OPPO -> """{"code":0,"msg":"no fill","data":{"ads":[]}}"""
            RewardSdk.VIVO -> """{"code":0,"msg":"no fill","data":{"ads":[]}}"""
            RewardSdk.SAMSUNG -> """{"code":0,"msg":"no fill","data":{"ads":[]}}"""
            RewardSdk.FACEBOOK -> """{"ads":[],"status":"no_fill"}"""
            RewardSdk.META -> """{"ads":[],"status":"no_fill"}"""
            RewardSdk.FIREBASE -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.ANALYTICS -> """{"status":"no_fill","ads":[]}"""
            RewardSdk.BEEAD -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.YOUSU -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.ADVIEW -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.DOMOB -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.GUOZHEN -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.AIRPUS -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.JIATUAN -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.WUBI -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.CHUANGYI -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.FEIYU -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.YIXUAN -> """{"code":0,"data":{"ads":[],"reason":"no_fill"}}"""
            RewardSdk.GENERIC -> """{"code":0,"msg":"no ad fill","data":{"ads":[]}}"""
        }
    }

    fun generateFakeRewardVerifyResponse(sdk: RewardSdk, rewardType: RewardType = RewardType.COIN): String {
        val (rewardName, rewardAmount) = getRewardNameAndAmount(rewardType)
        return when (sdk) {
            RewardSdk.PANGLE -> """{"is_valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.GDT -> """{"ret":0,"msg":"success","data":{"is_rewarded":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.KS -> """{"result":1,"data":{"rewarded":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.BAIDU -> """{"error_code":0,"msg":"success","data":{"verify":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.SIGMOB -> """{"code":0,"data":{"is_rewarded":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.MINTEGRAL -> """{"status":200,"msg":"","data":{"is_rewarded":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.UNITY -> """{"status":"ok","data":{"verified":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.ADMOB -> """{"rewarded":true,"verified":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.VUNGLE -> """{"status":"ok","verified":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.APPLOVIN -> """{"code":200,"data":{"valid":true,"rewarded":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.IRONSOURCE -> """{"status":"success","valid":true,"rewarded":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.CHARTBOOST -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.INMOBI -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.TAPJOY -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.STARTAPP -> """{"status":"ok","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.FYBER -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.MOBUVISTA -> """{"status":200,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.TOPON -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.TRADPLUS -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.SINGULAR -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.APPSFLYER -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.ADJUST -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.KOCHAVA -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.BRANCH -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.TENJIN -> """{"status":"success","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.HUAWEI -> """{"ret":0,"msg":"success","data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.XIAOMI -> """{"code":0,"msg":"success","data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.OPPO -> """{"code":0,"msg":"success","data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.VIVO -> """{"code":0,"msg":"success","data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.SAMSUNG -> """{"code":0,"msg":"success","data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.FACEBOOK -> """{"rewarded":true,"verified":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.META -> """{"rewarded":true,"verified":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.FIREBASE -> """{"status":"ok","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.ANALYTICS -> """{"status":"ok","valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}"""
            RewardSdk.BEEAD -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.YOUSU -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.ADVIEW -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.DOMOB -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.GUOZHEN -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.AIRPUS -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.JIATUAN -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.WUBI -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.CHUANGYI -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.FEIYU -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            RewardSdk.YIXUAN -> """{"code":0,"data":{"valid":true,"reward_name":"$rewardName","reward_amount":$rewardAmount,"reward_type":"${rewardType.name.lowercase()}"}}"""
            else -> generateFakeAdCompleteResponse(sdk, rewardType)
        }
    }

    fun detectRewardTypeFromPath(path: String): RewardType {
        val lower = path.lowercase()
        return when {
            lower.contains("chapter") && lower.contains("unlock") -> RewardType.UNLOCK_CHAPTER
            lower.contains("content") && lower.contains("unlock") -> RewardType.UNLOCK_CONTENT
            lower.contains("feature") && lower.contains("unlock") -> RewardType.UNLOCK_FEATURE
            lower.contains("level") && lower.contains("unlock") -> RewardType.UNLOCK_LEVEL
            lower.contains("episode") && lower.contains("unlock") -> RewardType.UNLOCK_CHAPTER
            lower.contains("video") && lower.contains("unlock") -> RewardType.UNLOCK_CONTENT
            lower.contains("novel") && lower.contains("unlock") -> RewardType.UNLOCK_CHAPTER
            lower.contains("reading") && lower.contains("unlock") -> RewardType.UNLOCK_CHAPTER
            lower.contains("premium") && lower.contains("unlock") -> RewardType.UNLOCK_FEATURE
            lower.contains("skip") && (lower.contains("wait") || lower.contains("time")) -> RewardType.SKIP_WAIT
            lower.contains("skip_ad") || lower.contains("skipad") -> RewardType.SKIP_WAIT
            lower.contains("speed") || lower.contains("accelerate") -> RewardType.SPEED_UP
            lower.contains("instant") || lower.contains("immediate") -> RewardType.SPEED_UP
            lower.contains("extra") && lower.contains("move") -> RewardType.EXTRA_MOVE
            lower.contains("extra") && lower.contains("time") -> RewardType.EXTRA_TIME
            lower.contains("extra") && lower.contains("step") -> RewardType.EXTRA_MOVE
            lower.contains("extra") && lower.contains("turn") -> RewardType.EXTRA_MOVE
            lower.contains("lottery") || lower.contains("raffle") -> RewardType.LOTTERY_CHANCE
            lower.contains("spin") || lower.contains("wheel") -> RewardType.SPIN_CHANCE
            lower.contains("draw") || lower.contains("gacha") -> RewardType.LOTTERY_CHANCE
            lower.contains("continue") && lower.contains("game") -> RewardType.CONTINUE_GAME
            lower.contains("game") && lower.contains("continue") -> RewardType.CONTINUE_GAME
            lower.contains("retry") || lower.contains("restart") -> RewardType.CONTINUE_GAME
            lower.contains("revive") || lower.contains("revival") || lower.contains("resurrect") -> RewardType.REVIVE
            lower.contains("continue_life") || lower.contains("continue_heart") -> RewardType.REVIVE
            lower.contains("double") && lower.contains("reward") -> RewardType.DOUBLE_REWARD
            lower.contains("double_coin") || lower.contains("double_gold") -> RewardType.DOUBLE_REWARD
            lower.contains("free") && lower.contains("trial") -> RewardType.FREE_TRIAL
            lower.contains("free") && lower.contains("vip") -> RewardType.VIP_TIME
            lower.contains("vip") -> RewardType.VIP_TIME
            lower.contains("premium") && lower.contains("free") -> RewardType.FREE_TRIAL
            lower.contains("energy") -> RewardType.ENERGY
            lower.contains("stamina") -> RewardType.STAMINA
            lower.contains("power") && !lower.contains("powerup") -> RewardType.ENERGY
            lower.contains("life") || lower.contains("lives") -> RewardType.LIFE
            lower.contains("heart") || lower.contains("hearts") -> RewardType.HEART
            lower.contains("item") || lower.contains("道具") -> RewardType.ITEM
            lower.contains("chest") || lower.contains("宝箱") -> RewardType.CHEST
            lower.contains("box") && lower.contains("reward") -> RewardType.CHEST
            lower.contains("supply") || lower.contains("crate") -> RewardType.CHEST
            lower.contains("equipment") || lower.contains("weapon") -> RewardType.ITEM
            lower.contains("armor") || lower.contains("skill") -> RewardType.ITEM
            lower.contains("diamond") -> RewardType.DIAMOND
            lower.contains("gem") && !lower.contains("gem_") -> RewardType.DIAMOND
            lower.contains("gold") && !lower.contains("gold_reward") -> RewardType.GOLD
            lower.contains("coupon") || lower.contains("voucher") -> RewardType.COIN
            lower.contains("discount") || lower.contains("cashback") -> RewardType.COIN
            lower.contains("redpacket") || lower.contains("hongbao") -> RewardType.COIN
            lower.contains("mystery") || lower.contains("loot") -> RewardType.CHEST
            lower.contains("buff") || lower.contains("boost") -> RewardType.ENERGY
            lower.contains("evolution") || lower.contains("awakening") -> RewardType.ITEM
            else -> RewardType.COIN
        }
    }

    fun detectRewardTypeFromBody(body: String?): RewardType {
        if (body.isNullOrBlank()) return RewardType.COIN
        val lower = body.lowercase()
        return when {
            lower.contains("chapter_unlock") || lower.contains("unlock_chapter") -> RewardType.UNLOCK_CHAPTER
            lower.contains("content_unlock") || lower.contains("unlock_content") -> RewardType.UNLOCK_CONTENT
            lower.contains("feature_unlock") || lower.contains("unlock_feature") -> RewardType.UNLOCK_FEATURE
            lower.contains("level_unlock") || lower.contains("unlock_level") -> RewardType.UNLOCK_LEVEL
            lower.contains("episode_unlock") || lower.contains("unlock_episode") -> RewardType.UNLOCK_CHAPTER
            lower.contains("novel_unlock") || lower.contains("unlock_novel") -> RewardType.UNLOCK_CHAPTER
            lower.contains("reading_unlock") || lower.contains("unlock_reading") -> RewardType.UNLOCK_CHAPTER
            lower.contains("premium_unlock") || lower.contains("unlock_premium") -> RewardType.UNLOCK_FEATURE
            lower.contains("skip_wait") || lower.contains("skip_time") -> RewardType.SKIP_WAIT
            lower.contains("skip_ad") || lower.contains("skipad") -> RewardType.SKIP_WAIT
            lower.contains("speed_up") || lower.contains("accelerate") -> RewardType.SPEED_UP
            lower.contains("instant_complete") || lower.contains("immediate") -> RewardType.SPEED_UP
            lower.contains("extra_move") || lower.contains("extra_moves") -> RewardType.EXTRA_MOVE
            lower.contains("extra_time") -> RewardType.EXTRA_TIME
            lower.contains("extra_step") || lower.contains("extra_turn") -> RewardType.EXTRA_MOVE
            lower.contains("lottery") || lower.contains("raffle") -> RewardType.LOTTERY_CHANCE
            lower.contains("spin") || lower.contains("wheel") -> RewardType.SPIN_CHANCE
            lower.contains("gacha") || lower.contains("summon") -> RewardType.LOTTERY_CHANCE
            lower.contains("continue_game") || lower.contains("game_continue") -> RewardType.CONTINUE_GAME
            lower.contains("retry") || lower.contains("restart") -> RewardType.CONTINUE_GAME
            lower.contains("revive") || lower.contains("revival") -> RewardType.REVIVE
            lower.contains("resurrect") || lower.contains("resurrection") -> RewardType.REVIVE
            lower.contains("continue_life") || lower.contains("continue_heart") -> RewardType.REVIVE
            lower.contains("double_reward") || lower.contains("reward_double") -> RewardType.DOUBLE_REWARD
            lower.contains("double_coin") || lower.contains("double_gold") -> RewardType.DOUBLE_REWARD
            lower.contains("free_trial") || lower.contains("trial_free") -> RewardType.FREE_TRIAL
            lower.contains("vip_time") || lower.contains("vip_duration") -> RewardType.VIP_TIME
            lower.contains("premium_free") || lower.contains("free_premium") -> RewardType.FREE_TRIAL
            lower.contains("\"energy\"") || lower.contains("energy_value") -> RewardType.ENERGY
            lower.contains("\"stamina\"") || lower.contains("stamina_value") -> RewardType.STAMINA
            lower.contains("\"power\"") && !lower.contains("powerup") -> RewardType.ENERGY
            lower.contains("\"life\"") || lower.contains("\"lives\"") -> RewardType.LIFE
            lower.contains("\"heart\"") || lower.contains("\"hearts\"") -> RewardType.HEART
            lower.contains("\"item\"") || lower.contains("item_id") -> RewardType.ITEM
            lower.contains("\"chest\"") || lower.contains("chest_id") -> RewardType.CHEST
            lower.contains("\"box\"") && lower.contains("reward") -> RewardType.CHEST
            lower.contains("\"supply\"") || lower.contains("\"crate\"") -> RewardType.CHEST
            lower.contains("\"equipment\"") || lower.contains("\"weapon\"") -> RewardType.ITEM
            lower.contains("\"armor\"") || lower.contains("\"skill\"") -> RewardType.ITEM
            lower.contains("\"diamond\"") || lower.contains("diamond_count") -> RewardType.DIAMOND
            lower.contains("\"gem\"") && !lower.contains("gem_") -> RewardType.DIAMOND
            lower.contains("\"gold\"") && !lower.contains("gold_reward") -> RewardType.GOLD
            lower.contains("\"coupon\"") || lower.contains("\"voucher\"") -> RewardType.COIN
            lower.contains("\"discount\"") || lower.contains("\"cashback\"") -> RewardType.COIN
            lower.contains("\"redpacket\"") || lower.contains("\"hongbao\"") -> RewardType.COIN
            lower.contains("\"mystery\"") || lower.contains("\"loot\"") -> RewardType.CHEST
            lower.contains("\"buff\"") || lower.contains("\"boost\"") -> RewardType.ENERGY
            lower.contains("\"evolution\"") || lower.contains("\"awakening\"") -> RewardType.ITEM
            lower.contains("unlock") && lower.contains("chapter") -> RewardType.UNLOCK_CHAPTER
            lower.contains("unlock") && lower.contains("content") -> RewardType.UNLOCK_CONTENT
            lower.contains("unlock") && lower.contains("feature") -> RewardType.UNLOCK_FEATURE
            lower.contains("unlock") && lower.contains("level") -> RewardType.UNLOCK_LEVEL
            lower.contains("unlock") && lower.contains("episode") -> RewardType.UNLOCK_CHAPTER
            lower.contains("unlock") && lower.contains("novel") -> RewardType.UNLOCK_CHAPTER
            else -> RewardType.COIN
        }
    }
}

fun RuleRepository.isRewardAppHint(appName: String?): Boolean {
    if (appName.isNullOrBlank()) return false
    val lower = appName.lowercase()
    val rewardHintApps = setOf(
        "com.kuaishou", "com.ss.android", "com.smile.gifmaker",
        "com.tencent", "com.qq", "com.baidu",
        "com.xunmeng", "com.jingdong", "com.taobao",
        "com.eg.android", "com.netease", "com.sina",
        "com.zhihu", "com.douban", "com.xiaomi",
        "com.bytedance", "com.heytap", "com.vivo",
        "com.huawei", "com.oppo", "com.coloros",
        "com.pinduoduo", "com.alibaba"
    )
    return rewardHintApps.any { lower.startsWith(it) }
}
