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
        PANGLE, GDT, KS, BAIDU, SIGMOB, MINTEGRAL, UNITY, ADMOB, VUNGLE, APPLOVIN, IRONSOURCE, GENERIC
    }

    fun identifyRewardSdk(domain: String, path: String, body: String?): RewardSdk? {
        val lower = domain.lowercase()
        return when {
            lower.contains("pangleglobal") || lower.contains("snssdk") || lower.contains("bytedance")
                || lower.contains("bytecdn") || lower.contains("pgl-stat") || body?.contains("pangle") == true -> RewardSdk.PANGLE
            lower.contains("gdt.qq") || lower.contains("qzs.gdt") || lower.contains("mi.gdt")
                || lower.contains("v2.gdt") || lower.contains("ylh") || body?.contains("优量汇") == true -> RewardSdk.GDT
            lower.contains("kuaishou") || lower.contains("yximgs") || lower.contains("kwai")
                || lower.contains("ksapis") || body?.contains("ksad") == true -> RewardSdk.KS
            lower.contains("mobads.baidu") || lower.contains("baidustatic") || lower.contains("cpro.baidu")
                || lower.contains("mssp.baidu") || body?.contains("baiduAd") == true -> RewardSdk.BAIDU
            lower.contains("sigmob") -> RewardSdk.SIGMOB
            lower.contains("mintegral") || lower.contains("mobvista") || lower.contains("mbridge") -> RewardSdk.MINTEGRAL
            lower.contains("unity3d") || lower.contains("unityads") -> RewardSdk.UNITY
            lower.contains("doubleclick") || lower.contains("googleads") -> RewardSdk.ADMOB
            lower.contains("vungle") -> RewardSdk.VUNGLE
            lower.contains("applovin") || lower.contains("applvn") -> RewardSdk.APPLOVIN
            lower.contains("ironsrc") || lower.contains("supersonicads") -> RewardSdk.IRONSOURCE
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
            "skip_ad", "no_ad", "vip_ad", "premium_ad"
        )
        return rewardKeywords.any { lower.contains(it) }
    }

    fun isRewardAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        val rewardDomains = listOf(
            "api-access.pangleglobal.com", "pangleglobal.com",
            "mi.gdt.qq.com", "v2.gdt.qq.com", "sdk.e.qq.com",
            "api.e.kuaishou.com", "api-ks.kuaishou.com",
            "mobads.baidu.com", "cpro.baidustatic.com",
            "access.sigmob.cn", "api.sigmob.cn",
            "api.mintegral.com", "mobvista.com",
            "configv2.unityads.unity3d.com", "auction.unityads.unity3d.com",
            "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
            "api.vungle.com", "vungle.com",
            "api.applovin.com", "d.applovin.com", "rt.applovin.com",
            "api.ironsrc.com", "init.ironsrc.com"
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
            "waterfall", "mediation", "ad_request"
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
            "log_reward", "report_reward", "record_reward"
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
            "earned", "collected", "received", "claim"
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

    fun generateFakeAdCompleteResponse(sdk: RewardSdk): String {
        val now = System.currentTimeMillis()
        return when (sdk) {
            RewardSdk.PANGLE -> """{"code":0,"message":"success","data":{"reward_verify":true,"reward_name":"金币","reward_amount":100,"extra":"$now","trans_id":"pangle_$now"}}"""
            RewardSdk.GDT -> """{"ret":0,"msg":"ok","data":{"is_rewarded":true,"reward_info":{"reward_type":1,"reward_count":100},"trans_id":"gdt_$now"}}"""
            RewardSdk.KS -> """{"result":1,"error_msg":"","data":{"ad_completed":true,"reward_amount":100,"reward_name":"金币","extra_info":"{}","click_id":"ks_$now"}}"""
            RewardSdk.BAIDU -> """{"error_code":0,"msg":"success","data":{"reward_verify":true,"reward_amount":100,"reward_name":"金币"}}"""
            RewardSdk.SIGMOB -> """{"code":0,"data":{"is_rewarded":true,"reward_amount":100,"reward_name":"金币","trans_id":"sm_$now"}}"""
            RewardSdk.MINTEGRAL -> """{"status":200,"msg":"","data":{"reward_status":1,"reward_name":"金币","reward_amount":100,"callback_id":"mtg_$now"}}"""
            RewardSdk.UNITY -> """{"status":"ok","data":{"completed":true,"reward":true,"reward_id":"unity_$now"}}"""
            RewardSdk.ADMOB -> """{"rewarded":true,"type":1,"amount":100,"reward_item":"coins"}"""
            RewardSdk.VUNGLE -> """{"status":"ok","events":[{"name":"AdCompleted","data":{"isCompletedView":true,"reward_name":"金币","reward_amount":100}}]}"""
            RewardSdk.APPLOVIN -> """{"code":200,"data":{"ad_status":"watched","reward_amount":100,"reward_label":"金币","placement_id":"al_$now"}}"""
            RewardSdk.IRONSOURCE -> """{"status":"success","reward_name":"金币","reward_amount":100,"transaction_id":"is_$now"}"""
            RewardSdk.GENERIC -> """{"code":0,"msg":"ok","data":{"reward_completed":true,"reward_amount":100,"reward_name":"金币","timestamp":$now}}"""
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
            RewardSdk.GENERIC -> """{"code":0,"msg":"no ad fill","data":{"ads":[]}}"""
        }
    }

    fun generateFakeRewardVerifyResponse(sdk: RewardSdk): String {
        return when (sdk) {
            RewardSdk.PANGLE -> """{"is_valid":true,"reward_name":"金币","reward_amount":100}"""
            RewardSdk.GDT -> """{"ret":0,"msg":"success","data":{"is_rewarded":true}}"""
            RewardSdk.KS -> """{"result":1,"data":{"rewarded":true}}"""
            RewardSdk.BAIDU -> """{"error_code":0,"msg":"success","data":{"verify":true}}"""
            RewardSdk.MINTEGRAL -> """{"status":200,"msg":"","data":{"is_rewarded":true}}"""
            RewardSdk.ADMOB -> """{"rewarded":true}"""
            RewardSdk.APPLOVIN -> """{"code":200,"data":{"valid":true}}"""
            RewardSdk.IRONSOURCE -> """{"status":"success","valid":true}"""
            else -> generateFakeAdCompleteResponse(sdk)
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
