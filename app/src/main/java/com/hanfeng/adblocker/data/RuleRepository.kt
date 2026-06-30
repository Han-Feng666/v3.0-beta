package com.HanFeng.data

import android.content.Context
import com.HanFeng.R
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.google.gson.reflect.TypeToken
import com.HanFeng.core.network.TrainingSampleExporter
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RemoteRuleSourceConfig
import com.HanFeng.model.RuleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RuleRepository {
    private const val PREFS = "rule_repo"
    private const val KEY_RULES = "rules"
    private const val KEY_RULE_COUNT = "rules_count"
    private const val KEY_REMOTE_RULE_SOURCES = "remote_rule_sources"
    private const val KEY_CUSTOM_VENDORS = "custom_vendors"
    private const val KEY_UNKNOWN_VENDOR_SAMPLES = "unknown_vendor_samples"
    private const val DEFAULT_VENDOR = "其它 (Other)"
    private const val GENERIC_AD_VENDOR = "通用广告/追踪 (Generic Ad/Tracking)"
    private const val BYPASS_PROTECTION_VENDOR = "加密 DNS 反绕过 (Encrypted DNS)"
    private const val REGEX_RULE_DOMAIN = "[Regex Rule]"
    private const val COSMETIC_RULE_DOMAIN = "[Cosmetic Rule]"
    private const val UNSUPPORTED_RULE_DOMAIN = "[Unsupported Rule]"
    private const val SUSPICIOUS_SAMPLE_DEBOUNCE_MILLIS = 10_000L
    private const val SUSPICIOUS_SAMPLE_PERSIST_DEBOUNCE_MILLIS = 30_000L
    private const val SUSPICIOUS_SAMPLE_DECODE_MAX_LENGTH = 2048
    private const val SUSPICIOUS_SAMPLE_MAX_DECODE_ROUNDS = 2
    private const val RULES_FILE_NAME = "rules.json"
    private const val BUILTIN_AD_SEED_SOURCE_ID = "builtin-ad-seed"
    private const val IMPORT_PARSE_BATCH_SIZE = 4_000
    private const val LARGE_RULE_CACHE_THRESHOLD = 20_000
    private const val MAX_STREAM_IMPORT_NEW_RULES = 300_000
    private const val MAX_BACKGROUND_ADVANCED_NEW_RULES = 50_000
    private const val MAX_CACHEABLE_RULES = 350_000
    private const val MAX_IMPORT_LINE_CHARS = 32 * 1024
    private const val MIN_IMPORT_FREE_HEAP_BYTES = 24L * 1024L * 1024L
    private const val MAX_CACHED_WHITELIST_ENTRIES = 500_000
    private const val MAX_CACHED_VENDOR_ENTRIES = 500_000
    private val BUILTIN_NOEVAL_SCRIPTLET = "(function(){try{window.eval=function(){throw new EvalError('eval blocked');};}catch(e){}})();"
    private val BUILTIN_NOWEBRTC_SCRIPTLET = "(function(){try{delete window.RTCPeerConnection;delete window.webkitRTCPeerConnection;delete window.mozRTCPeerConnection;}catch(e){}})();"
    private val SIMPLE_MODIFIER_NAMES = setOf("important", "badfilter")
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 白名单域名 - 这些域名被拦截会导致 APP 断网
    // 策略：只保护基础服务，不保护纯广告域名
    // 2026-06-06 优化：移除过度保护的泛域名，改为精确子域名保护
    private val whitelistDomains = setOf(
        // 微信/QQ 核心服务 - 精确保护（不再保护整个 qq.com）
        "servicewechat.com",
        "alipay.com",
        "alipay.cn",
        "dns.weixin.qq.com.cn",
        "aedns.weixin.qq.com",
        "wx.qq.com",
        "web.weixin.qq.com",
        "mp.weixin.qq.com",
        "work.weixin.qq.com",
        "long.weixin.qq.com",
        "szshort.weixin.qq.com",
        "wecom.qq.com",
        "wework.com",
        "weiyun.com",
        "weiyun.cn",
        "qqmail.com",
        "mail.qq.com",
        "exmail.qq.com",
        "docs.qq.com",
        "meeting.tencent.com",
        "voovmeeting.com",
        "tim.qq.com",
        "ftn.qq.com",
        "myqcloud.com",
        "qcloud.com",
        "tencentyun.com",
        "file.myqcloud.com",
        "cos.myqcloud.com",
        "tpns.tencent.com",
        // 微信 QQ 基础通信 - 不再保护 qlogo.cn/qpic.cn 等图片 CDN（常被用於广告）
        "qlogo.cn",
        "qlogo.com",
        // 支付相关 - 精确保护
        "qpay.tf.qq.com",
        "qpay.qq.com",
        "tenpay.com",
        "paipai.com",
        // 游戏核心登录/更新服务 - 不再保护活动域名
        "gamehelper.com.cn",
        "act.qq.com",
        "imgcache.qq.com",
        // 原神/米哈游游戏 - 精确保护登录/更新
        "miHoYo.com",
        "mihayo.com",
        "yuanshen.com",
        "hoyolab.com",
        "hoyoverse.com",
        "bhsr.com",
        "starrails.com",
        // 腾讯游戏登录服务 - 移除 dlied*.qq.com 下载域名（常被用於打包广告）
        "dnf.qq.com",
        "cf.qq.com",
        "lol.qq.com",
        "speed.qq.com",
        "fifa.qq.com",
        "2k.qq.com",
        "ssl.ptlogin2.qq.com",
        "ptlogin2.qq.com",
        // 网易游戏 - 只保护登录/支付，移除 163.com/netease.com 泛域名
        "game.163.com",
        // 通用 CDN - 只保护 Google 和阿里云核心 CDN
        "alicdn.com",
        "alibaba.com",
        "taobao.com",
        "aliyun.com",
        "cdndm.com",
        "cdn.hockeyapp.net",
        "fir.im",
        // 金融/银行 - 完全保护
        "webank.com",
        "webankcdn.net",
        "wldservice.com",
        "constid.dingxiang-inc.com",
        // Google 基础服务 - 完全保护（确保 Play 商店、推送正常）
        "firebaseinstallations.googleapis.com",
        "googleapis.com",
        "gstatic.com",
        "google.com",
        "googleapis.cn",
        "gvt1.com",
        "gvt2.com",
        "android.googleapis.com",
        "play.googleapis.com",
        "play.google.com",
        "clientservices.googleapis.com",
        "update.googleapis.com",
        "android.clients.google.com",
        "ssl.gstatic.com",
        // 隐私保护服务（误报）- 完全保护
        "ghostery.com",
        "ghostery.net",
        // 在线视频 CDN - 精确保护，移除泛域名
        "hdzixun.com",
        "douyinvod.com",
        "douyincdn.com",
        "bytegoofy.com",
        "video.qq.com",
        "qcloudimg.com",
        "cdn-go.cn",
        "bcebos.com",
        "bdstatic.com",
        "iqiyi.com",
        "71.am",
        "71edge.com",
        "gitv.tv",
        "youku.com",
        "ykimg.com",
        "cibntv.net",
        "mmstat.com",
        "soku.com",
        "le.com",
        "lecloud.com",
        "letvcdn.com",
        "letvimg.com",
        "bilibili.com",
        "bilivideo.com",
        "biliapi.com",
        "biligame.com",
        "mcdn.bilivideo.cn",
        "mgtv.com",
        "imgo.tv",
        "hitv.com",
        "hwcdn.net",
        "tcdn.qq.com",
        "liveplay.myqcloud.com"
    )
    
    // 友盟特殊处理 - 只保护基础服务子域名（日志相关）
    private val umengWhitelistSubDomains = setOf(
        "alog-default.umeng.com",
        "ulogs.umeng.com",
        "cnlogs.umeng.com",
        "errlog.umeng.com",
        "errnewlog.umeng.com",
        "aaid.umeng.com"
    )
    
    // QQ 基础服务 - 保护监控和日志服务
    private val qqWhitelistSubDomains = setOf(
        "rmonitor.qq.com",
        "monitor.qq.com",
        "aeventlog.beacon.qq.com",
        "fclog.baidu.com",
        "mugcdn.x5.qq.com"
    )
    
    // 网易工具服务
    private val neteaseWhitelistSubDomains = setOf(
        "c.nstool.ntes53.netease.com",
        "a.nstool.ntes53.netease.com",
        "b.nstool.ntes53.netease.com"
    )
    private val gson = Gson()
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val cacheLock = Any()
    private val fileWriteLock = Any()
    private val domainValidationRegex = Regex("[a-z0-9._-]+")
    private val alphanumericRegex = Regex("[^a-z0-9]")
    private val domainExtractRegex = Regex("([a-z0-9-]+(?:\\.[a-z0-9-]+)+)", RegexOption.IGNORE_CASE)
    private val domainSubdomainRegex = Regex("[a-z0-9*-]+", RegexOption.IGNORE_CASE)
    private val unicodeEscapeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
    private val htmlNumericEntityRegex = Regex("""&#(x?[0-9a-fA-F]+);?""")
    private val alphanumericCnRegex = Regex("[^a-z0-9\u4e00-\u9fff]")
    private val whitespaceRegex = Regex("\\s+")
    private val ipV4Regex = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val parensRegex = Regex("\\(([^)]+)\\)")
    private val splitWhitespaceRegex = Regex("[\\s,;]+")
    private val lineBreakRegex = Regex("[\\r\\n]+")
    @Volatile private var cachedRules: List<BlockRule>? = null
    @Volatile private var cachedRuleCount: Int? = null
    @Volatile private var cachedBlockedDomains: Set<String>? = null
    @Volatile private var cachedRuleMap: Map<String, List<BlockRule>>? = null
    @Volatile private var cachedSimpleDomainIndex: SimpleDomainIndex? = null
    @Volatile private var cachedTrieIndex: DomainTrieIndex? = null
    @Volatile private var cachedRegexRules: List<BlockRule>? = null
    @Volatile private var cachedCosmeticRules: List<BlockRule>? = null
    @Volatile private var cachedIpCidrRules: List<BlockRule>? = null
    @Volatile private var cachedPortOnlyRules: List<BlockRule>? = null
    @Volatile private var cachedCustomVendors: Map<String, String>? = null
    @Volatile private var cachedRuleInventory: RuleInventory? = null
    @Volatile private var cachedCompiledRegexRules: Map<String, java.util.regex.Pattern> = emptyMap()
    @Volatile private var cachedInvalidRegexRules: Set<String> = emptySet()
    @Volatile private var cachedVendorMap: MutableMap<String, String> = ConcurrentHashMap()
    @Volatile private var cachedKeywordRules: List<BlockRule>? = null
    @Volatile private var cachedCombinedKeywordPattern: java.util.regex.Pattern? = null
    @Volatile private var cachedRegexLiteralIndex: Map<String, List<BlockRule>>? = null
    @Volatile private var cachedWhitelistHits = ConcurrentHashMap<String, Boolean>()
    @Volatile private var cachedUnknownVendorSamples: Map<String, SuspiciousDomainRecord>? = null
    @Volatile private var cachedUnknownVendorSamplesLoaded = false
    // App-specific rule index: packageName → (domain → rules)
    @Volatile private var cachedAppRuleIndex: Map<String, Map<String, List<BlockRule>>>? = null
    // Universal rules (no appPackages): domain → rules
    @Volatile private var cachedUniversalRuleMap: Map<String, List<BlockRule>>? = null
    // CNAME rules: domain → BlockRule (for alias target matching in DNS resolution)
    @Volatile private var cachedCnameRuleIndex: Map<String, BlockRule>? = null
    @Volatile private var lastUnknownVendorSamplesPersistAt: Long = 0L

    private data class SimpleDomainIndex(
        val blocked: Set<String>,
        val userOwnedBlocked: Set<String>,
        val importantBlocked: Set<String>,
        val exceptions: Set<String>
    )
    private val adKeywords = listOf(
        "ad",
        "ads",
        "adn",
        "adnet",
        "adservice",
        "adserver",
        "adview",
        "admob",
        "adx",
        "adxlog",
        "adclick",
        "adpush",
        "adproxy",
        "admarket",
        "adscene",
        "adcore",
        "adstat",
        "track",
        "tracking",
        "analytics",
        "beacon",
        "monitor",
        "sdk",
        "ssp",
        "dsp",
        "rtb",
        "bid",
        "bidder",
        "union",
        "unionad",
        "promotion",
        "advert",
        "measure",
        "mediation",
        "interstitial",
        "reward",
        "splash",
        "nativead",
        "feedad",
        "brandad",
        "launchad",
        "screenad",
        "startupad",
        "openad",
        "intad",
        "mbridge",
        "pangle",
        "gdt",
        "qxm",
        "ubix",
        "zghd",
        "zhongguan",
        "doubleclick",
        "topon",
        "tradplus",
        "adscope",
        "sigmob",
        "mobvista",
        "mintegral",
        "applovin",
        "ironsource",
        "unityads",
        "vungle",
        "offerwall",
        "rewardvideo",
        "excitation",
        "inspire",
        "welfare",
        "benefit",
        "taskcenter",
        "taskreward",
        "coinreward",
        "readingbonus",
        "launch",
        "startup",
        "preload",
        "material",
        "creative",
        "landing",
        "showurl",
        "clickurl",
        "monitorurl",
        "impression",
        "playable",
        "endcard",
        "youlianghui",
        "guangdiantong",
        "adqq",
        "alimama",
        "tanx",
        "adash",
        "pangolin",
        "gromore",
        "snssdk",
        "ksad",
        "kuaishouad",
        "kwad",
        "beizi",
        "youmi",
        "mediav",
        "vpon",
        "domob",
        "duomeng",
        "adwo",
        "openalliance",
        "huaweiads",
        "mimo",
        "oppoads",
        "vivoads",
        "audiencenetwork",
        "maxads",
        "anythink",
        "tpbid",
        "aiclk",
        "openwrap",
        "inneractive",
        "colossusssp",
        "hbopenbid",
        "dtexchange",
        "moatads",
        "taboola",
        "outbrain",
        "pubmatic",
        "openx",
        "smaato",
        "tapjoy",
        "adcolony",
        "ogury"
    )
    private val weakAdKeywords = setOf(
        "ad",
        "ads",
        "track",
        "analytics",
        "monitor",
        "measure",
        "launch",
        "startup",
        "material",
        "creative",
        "landing"
    )
    private val sensitiveAuthKeywords = listOf(
        "login",
        "signin",
        "signup",
        "auth",
        "oauth",
        "sso",
        "passport",
        "account",
        "accounts",
        "session",
        "token",
        "verify",
        "captcha",
        "securelogin"
    )
    
    // 游戏核心服务域名（确保登录、联机、更新正常）
    
    // 社交 APP 核心域名（确保聊天、语音、视频正常）

    // 音乐/音频核心域名（确保播放、搜索、评论、账号同步正常）

    
    // 小说内容 API 白名单 (这些域名/子域名专门提供小说内容，不拦截)
    private val unsupportedAdGuardModifiers = emptySet<String>()
    private val ignorableAdGuardModifiers = setOf(
        "all",
        "content",
        "extension"
    )
    private val geositeAdCategoryTokens = setOf(
        "ad",
        "ads",
        "category-ads",
        "category-ads-all",
        "advertising",
        "tracker",
        "tracking",
        "malware",
        "phishing"
    )
    private val geositeAdSeedDomains = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "admob.com",
        "adnxs.com",
        "adsrvr.org",
        "pubmatic.com",
        "openx.net",
        "smaato.net",
        "taboola.com",
        "outbrain.com",
        "applovin.com",
        "ironsrc.com",
        "unityads.unity3d.com",
        "vungle.com",
        "mintegral.com",
        "pangolin-sdk-toutiao.com",
        "pglstatp-toutiao.com",
        "gdt.qq.com",
        "adsmind.apdcdn.tc.qq.com",
        "tanx.com",
        "alimama.com"
    )

    fun getRules(context: Context): List<BlockRule> {
        cachedRules?.let { return it }
        synchronized(cacheLock) {
            cachedRules?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val file = rulesFile(context)
            val loadedRules = try {
                if (file.exists()) {
                    readRulesFile(context, file)
                } else {
                    val json = readLegacyRulesJson(context, prefs)
                    val type = object : TypeToken<List<BlockRule>>() {}.type
                    gson.fromJson<List<BlockRule>>(json, type) ?: emptyList()
                }
            } catch (e: Exception) {
                LogRepository.append(context, "RuleRepository.getRules load failed: ${e.message ?: e.javaClass.simpleName}")
                cachedRules = emptyList()
                return emptyList()
            }
            var migrated = false
            val rules = loadedRules
                .map {
                    val stableId = it.id.trim().ifBlank {
                        migrated = true
                        UUID.randomUUID().toString()
                    }
                    copyBlockRule(
                        it,
                        id = stableId,
                        vendor = normalizeVendorName(it.vendor),
                        source = if (it.source == RuleSource.REFERENCE) RuleSource.IMPORTED else it.source
                    )
                }
                .sortedBy { it.domain }
            if (migrated) {
                runCatching { writeRulesFile(context, rules) }.onFailure { e ->
                    LogRepository.append(context, "RuleRepository.getRules migrate write failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            cachedRules = rules
            return rules
        }
    }

    fun getRuleCount(context: Context): Int {
        cachedRuleCount?.let { return it }
        cachedRules?.let { return it.size }
        synchronized(cacheLock) {
            cachedRuleCount?.let { return it }
            cachedRules?.let { return it.size }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val storedCount = prefs.getInt(KEY_RULE_COUNT, -1)
            if (storedCount >= 0) {
                cachedRuleCount = storedCount
                return storedCount
            }
            val rules = getRules(context)
            val count = rules.size
            prefs.edit().putInt(KEY_RULE_COUNT, count).apply()
            cachedRuleCount = count
            return count
        }
    }
    
    // DNS 拦截决策缓存（减少重复计算）
    private val dnsBlockDecisionCache = object : LinkedHashMap<String, Pair<Boolean, Long>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Boolean, Long>>?): Boolean {
            return size > 512
        }
    }
    private val dnsBlockDecisionLock = Any()
    private const val DECISION_TTL_MS = 5000L // 5 秒缓存

    fun prewarmCaches(context: Context) {
        if (cachedSimpleDomainIndex != null) return
        buildAllCachesFromFile(context)
    }

    private fun buildAllCachesFromFile(context: Context) {
        val file = rulesFile(context)
        if (!file.exists() || file.length() <= 2L) {
            synchronized(cacheLock) {
                cachedBlockedDomains = emptySet()
                cachedSimpleDomainIndex = SimpleDomainIndex(emptySet(), emptySet(), emptySet(), emptySet())
                cachedRuleMap = emptyMap()
                cachedRegexRules = emptyList()
                cachedKeywordRules = emptyList()
                cachedCombinedKeywordPattern = null
                cachedRegexLiteralIndex = null
                cachedCnameRuleIndex = null
                cachedCosmeticRules = emptyList()
                cachedIpCidrRules = emptyList()
                cachedPortOnlyRules = emptyList()
                cachedAppRuleIndex = emptyMap()
                cachedUniversalRuleMap = emptyMap()
                cachedCompiledRegexRules = emptyMap()
                cachedRuleCount = 0
            }
            return
        }
        val blocked = linkedSetOf<String>()
        val userOwnedBlocked = linkedSetOf<String>()
        val importantBlocked = linkedSetOf<String>()
        val exceptions = linkedSetOf<String>()
        val nonSimpleRules = mutableListOf<BlockRule>()
        val regexRules = mutableListOf<BlockRule>()
        val keywordRules = mutableListOf<BlockRule>()
        val cosmeticRules = mutableListOf<BlockRule>()
        val ipCidrRules = mutableListOf<BlockRule>()
        val portOnlyRules = mutableListOf<BlockRule>()
        val cnameRules = mutableListOf<BlockRule>()
        var ruleCount = 0
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                JsonReader(reader).use { jsonReader ->
                    jsonReader.beginArray()
                    while (jsonReader.hasNext()) {
                        val rule = gson.fromJson<BlockRule>(jsonReader, BlockRule::class.java)
                            ?.let(::normalizeRuleFromStorage)
                            ?: continue
                        ruleCount++
                        if (ruleCount > MAX_CACHEABLE_RULES || isImportHeapLow()) continue
                        if (isSimpleDomainRule(rule)) {
                            if (rule.exceptionRule) {
                                exceptions += rule.domain
                            } else {
                                blocked += rule.domain
                                if (isUserOwnedBlockingRule(rule)) userOwnedBlocked += rule.domain
                                if (isImportantBlockingRule(rule)) importantBlocked += rule.domain
                            }
                        } else {
                            nonSimpleRules += rule
                        }
                        if (rule.regexPattern != null) regexRules += rule
                        if (rule.keywordPattern != null) keywordRules += rule
                        if (rule.cosmeticSelector != null) cosmeticRules += rule
                        if (rule.ipCidr != null) ipCidrRules += rule
                        if (rule.domain == "*" && rule.ipCidr.isNullOrBlank()) portOnlyRules += rule
                        if (rule.cname) cnameRules += rule
                    }
                    jsonReader.endArray()
                }
            }
        } catch (e: Exception) {
            LogRepository.append(context, "buildAllCachesFromFile failed: ${e.message ?: e.javaClass.simpleName}")
            return
        } catch (e: OutOfMemoryError) {
            synchronized(cacheLock) {
                cachedBlockedDomains = emptySet()
                cachedSimpleDomainIndex = SimpleDomainIndex(emptySet(), emptySet(), emptySet(), emptySet())
                cachedTrieIndex = DomainTrieIndex(emptySet(), emptySet(), emptySet(), emptySet())
                cachedRuleMap = emptyMap()
                cachedRegexRules = emptyList()
                cachedKeywordRules = emptyList()
                cachedCosmeticRules = emptyList()
                cachedIpCidrRules = emptyList()
                cachedPortOnlyRules = emptyList()
                cachedCnameRuleIndex = emptyMap()
                cachedCompiledRegexRules = emptyMap()
                cachedRuleInventory = null
                cachedWhitelistHits.clear()
            }
            LogRepository.append(context, "buildAllCachesFromFile stopped by low memory: ${runtimeMemorySnapshot()}")
            return
        }
        synchronized(cacheLock) {
            cachedBlockedDomains = emptySet()
            cachedSimpleDomainIndex = SimpleDomainIndex(
                blocked = (blocked - exceptions) + userOwnedBlocked,
                userOwnedBlocked = userOwnedBlocked,
                importantBlocked = importantBlocked - exceptions,
                exceptions = exceptions
            )
            cachedTrieIndex = DomainTrieIndex(
                blocked = (blocked - exceptions) + userOwnedBlocked,
                userOwnedBlocked = userOwnedBlocked,
                importantBlocked = importantBlocked - exceptions,
                exceptions = exceptions
            )
            cachedRuleMap = nonSimpleRules.groupBy { it.domain }
            buildAppRuleIndex(nonSimpleRules)
            cachedRegexRules = regexRules
            cachedKeywordRules = keywordRules
            cachedCosmeticRules = cosmeticRules
            cachedIpCidrRules = ipCidrRules
            cachedPortOnlyRules = portOnlyRules
            cachedCnameRuleIndex = cnameRules.associateBy { it.domain }
            cachedCompiledRegexRules = emptyMap()
            cachedRuleCount = ruleCount
            cachedWhitelistHits.clear()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RULE_COUNT, ruleCount)
            .apply()
        LogRepository.append(context, "RuleRepository.buildAllCachesFromFile: rules=$ruleCount, cached=${minOf(ruleCount, MAX_CACHEABLE_RULES)} memory=${runtimeMemorySnapshot()}")
    }

    fun addRule(context: Context, rawDomain: String, source: RuleSource): BlockRule? {
        val domain = sanitizeDomain(rawDomain) ?: return null
        val existingDomains = getExistingDomainSet(context)
        if (!existingDomains.add(domain)) return null
        val rule = buildNormalizedBlockRule(context, domain, source)
        appendRulesToFile(context, listOf(rule))
        return rule
    }

    fun addExceptionRule(context: Context, rawDomain: String): BlockRule? {
        val domain = sanitizeDomain(rawDomain) ?: return null
        val simpleIndex = getSimpleDomainIndex(context)
        if (domain in simpleIndex.exceptions) {
            LogRepository.append(context, "addExceptionRule: $domain already in exceptions, skip")
            return null
        }
        val rule = BlockRule(
            id = UUID.randomUUID().toString(),
            domain = domain,
            vendor = classifyVendor(context, domain),
            source = RuleSource.MANUAL,
            exceptionRule = true
        )
        appendRulesToFile(context, listOf(rule))
        synchronized(cacheLock) {
            val current = cachedSimpleDomainIndex
            if (current != null) {
                cachedSimpleDomainIndex = current.copy(
                    blocked = current.blocked - domain,
                    userOwnedBlocked = current.userOwnedBlocked - domain,
                    exceptions = current.exceptions + domain
                )
                LogRepository.append(context, "addExceptionRule: $domain added to exceptions cache, size=${cachedSimpleDomainIndex?.exceptions?.size ?: 0}")
            } else {
                LogRepository.append(context, "addExceptionRule: cachedSimpleDomainIndex is null, cache correction skipped for $domain")
            }
            cachedTrieIndex = DomainTrieIndex(
                blocked = cachedSimpleDomainIndex!!.blocked,
                userOwnedBlocked = cachedSimpleDomainIndex!!.userOwnedBlocked,
                importantBlocked = cachedSimpleDomainIndex!!.importantBlocked,
                exceptions = cachedSimpleDomainIndex!!.exceptions
            )
            cachedRuleCount = null
            cachedWhitelistHits.clear()
        }
        return rule
    }

    fun addRules(context: Context, rawInput: String, source: RuleSource, allowWhitelistDomains: Boolean = false): List<BlockRule> {
        return addNormalizedRules(context, parseManualInput(rawInput), source, allowWhitelistDomains)
    }

    fun addRules(context: Context, domains: Collection<String>, source: RuleSource, allowWhitelistDomains: Boolean = false): List<BlockRule> {
        if (domains.isEmpty()) return emptyList()
        val userOwnedSource = source == RuleSource.MANUAL || source == RuleSource.IMPORTED
        val normalizedDomains = domains.mapNotNull(::sanitizeDomain)
            .filter { userOwnedSource || allowWhitelistDomains || !isWhitelistedDomain(it) }
            .distinct()
        return addNormalizedRules(context, normalizedDomains, source, allowWhitelistDomains = true)
    }

    fun getRemoteRuleSources(context: Context): List<RemoteRuleSourceConfig> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_REMOTE_RULE_SOURCES, null)
        if (json.isNullOrBlank()) {
            return emptyList()
        }
        val type = object : TypeToken<List<RemoteRuleSourceConfig>>() {}.type
        val stored = runCatching { gson.fromJson<List<RemoteRuleSourceConfig>>(json, type) }
            .getOrNull()
            .orEmpty()
            .mapNotNull(::sanitizeRemoteRuleSource)
            .filterNot(::isLegacyBuiltInRemoteRuleSource)
        if (stored.isEmpty() && json.isNotBlank()) {
            LogRepository.append(context, "Remote rule sources JSON is valid but stored list is empty after sanitization, preserving original JSON to avoid data loss")
        }
        return stored.sortedBy { it.name.lowercase() }
    }

    fun saveRemoteRuleSources(context: Context, sources: List<RemoteRuleSourceConfig>) {
        val normalizedSources = sources.mapNotNull(::sanitizeRemoteRuleSource)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMOTE_RULE_SOURCES, gson.toJson(normalizedSources.sortedBy { it.name.lowercase() }))
            .apply()
    }

    fun updateRemoteRuleSource(context: Context, updated: RemoteRuleSourceConfig) {
        val sanitized = normalizeRemoteRuleSource(updated) ?: return
        val current = getRemoteRuleSources(context)
        val next = current.map { source -> if (source.id == sanitized.id) sanitized else source }
        saveRemoteRuleSources(context, next)
    }

    fun addRemoteRuleSource(context: Context, source: RemoteRuleSourceConfig) {
        val normalizedSource = normalizeRemoteRuleSource(source) ?: return
        val current = getRemoteRuleSources(context)
        val exists = current.any {
            it.id == normalizedSource.id || it.url.equals(normalizedSource.url, ignoreCase = true)
        }
        if (exists) return
        saveRemoteRuleSources(context, current + normalizedSource)
    }

    fun removeRemoteRuleSource(context: Context, sourceId: String) {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        if (normalizedSourceId.isBlank()) return
        saveRemoteRuleSources(context, filterRemoteRuleSources(context, normalizedSourceId))
    }

    // 预置安全规则源 - 钓鱼/恶意域名拦截
    private const val SECURITY_SOURCE_ID = "security-stevenblack"
    private const val SECURITY_SOURCE_NAME = "StevenBlack 安全防护"
    private const val SECURITY_SOURCE_URL = "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts"

    fun ensureSecurityRuleSource(context: Context) {
        val sources = getRemoteRuleSources(context)
        if (sources.any { it.id == SECURITY_SOURCE_ID || it.url.equals(SECURITY_SOURCE_URL, ignoreCase = true) }) return
        addRemoteRuleSource(
            context,
            RemoteRuleSourceConfig(
                id = SECURITY_SOURCE_ID,
                name = SECURITY_SOURCE_NAME,
                url = SECURITY_SOURCE_URL,
                enabled = true,
                authorId = "stevenblack"
            )
        )
    }

    fun isBuiltInRemoteRuleSource(sourceId: String): Boolean {
        return false
    }

    fun getRulesForRemoteSource(context: Context, sourceId: String): List<BlockRule> {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        if (normalizedSourceId.isBlank()) return emptyList()
        return getRules(context).filter { hasRemoteSourceId(it, normalizedSourceId) }
    }

    fun getRemoteRuleSourceName(context: Context, sourceId: String?): String? {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        if (normalizedSourceId.isBlank()) return null
        return findRemoteRuleSource(context, normalizedSourceId)?.name
    }

    fun removeRulesForRemoteSource(context: Context, sourceId: String): Int {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        val current = getRules(context)
        val sourceRules = getRulesForRemoteSource(context, normalizedSourceId)
        if (sourceRules.isEmpty()) return 0
        val remaining = current.filterNot { hasRemoteSourceId(it, normalizedSourceId) }
        val removedCount = sourceRules.size
        if (removedCount > 0) {
            save(context, remaining)
        }
        return removedCount
    }

    fun replaceRulesForRemoteSource(context: Context, sourceId: String, content: String, allowWhitelistDomains: Boolean = false): Int {
        return replaceRulesForRemoteSourceStreaming(
            context = context,
            sourceId = sourceId,
            inputStream = content.byteInputStream(Charsets.UTF_8),
            allowWhitelistDomains = allowWhitelistDomains
        )
    }

    // P0.4 新增：流式替换规则源（使用 InputStream，避免大文件 OOM）
    fun replaceRulesForRemoteSourceStreaming(
        context: Context,
        sourceId: String,
        inputStream: InputStream,
        allowWhitelistDomains: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Int {
        val startTime = System.currentTimeMillis()
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        onProgress?.invoke("正在读取现有规则...")
        val baseStart = System.currentTimeMillis()
        synchronized(fileWriteLock) {
            val rewrite = beginStreamingRulesRewrite(context, excludeRemoteSourceId = normalizedSourceId)
            LogRepository.append(context, "replaceRulesForRemoteSourceStreaming [1/4]: retained=${rewrite.ruleCount}, time=${System.currentTimeMillis() - baseStart}ms")

            try {
                onProgress?.invoke("正在解析规则文件...")
                val parseStart = System.currentTimeMillis()
                var addedCount = 0
                var parsedBlockedCount = 0
                var lineCount = 0
                var parsedRuleCount = 0
                var lastProgressAt = 0L
                inputStream.bufferedReader().useLines { lines ->
                    lines.forEach lineLoop@{ rawLine ->
                        lineCount += 1
                        if (shouldStopStreamingImport(context, addedCount, MAX_STREAM_IMPORT_NEW_RULES)) return@useLines
                        if (rawLine.length > MAX_IMPORT_LINE_CHARS) return@lineLoop
                        RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach fragmentLoop@{ fragment ->
                            if (shouldStopStreamingImport(context, addedCount, MAX_STREAM_IMPORT_NEW_RULES)) return@fragmentLoop
                            val trimmed = fragment.trim()
                            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@fragmentLoop
                            val simpleDomain = extractSimpleImportDomain(trimmed)
                            if (simpleDomain != null && (allowWhitelistDomains || !isWhitelistedDomain(simpleDomain))) {
                                parsedBlockedCount += 1
                                parsedRuleCount += 1
                                rewrite.writeSimpleRule(simpleDomain, RuleSource.IMPORTED, normalizedSourceId)
                                addedCount += 1
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (lineCount % 2000 == 0 || now - lastProgressAt >= 750L) {
                            lastProgressAt = now
                            onProgress?.invoke("正在解析并导入规则...\n已读取 ${lineCount} 行，识别 ${parsedRuleCount} 条，新增 ${addedCount} 条")
                        }
                    }
                }
                LogRepository.append(
                    context,
                    "replaceRulesForRemoteSourceStreaming [3/4]: fast parsed blocked=$parsedBlockedCount, added=$addedCount, time=${System.currentTimeMillis() - parseStart}ms"
                )

                onProgress?.invoke("正在保存规则到本地...")
                val saveStart = System.currentTimeMillis()
                val finalCount = finishStreamingRulesRewrite(context, rewrite)
                LogRepository.append(context, "replaceRulesForRemoteSourceStreaming [4/4]: saved final=$finalCount, save time=${System.currentTimeMillis() - saveStart}ms")

                val totalTime = System.currentTimeMillis() - startTime
                LogRepository.append(
                    context,
                    "replaceRulesForRemoteSourceStreaming: source=$sourceId, added=$addedCount, time=${totalTime}ms"
                )
                return addedCount
            } catch (e: Exception) {
                abortStreamingRulesRewrite(rewrite)
                LogRepository.append(context, "规则源解析失败：${e.message ?: e.javaClass.simpleName}")
                throw e
            } catch (e: OutOfMemoryError) {
                abortStreamingRulesRewrite(rewrite)
                LogRepository.append(context, "规则源解析内存不足：${e.message ?: e.javaClass.simpleName}")
                throw e
            } finally {
                inputStream.close()
            }
        }
    }

    fun filterRemoteSourceNonAds(context: Context, sourceId: String): Int {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        val sourceRules = getRulesForRemoteSource(context, normalizedSourceId)
        if (sourceRules.isEmpty()) return 0
        val removableIds = getRemoteSourceNonAdCandidates(context, normalizedSourceId).map { it.rule.id }.toSet()
        if (removableIds.isEmpty()) return 0
        removeByIds(context, removableIds)
        return removableIds.size
    }

    fun getRemoteSourceNonAdCandidates(context: Context, sourceId: String): List<RemoteRuleRemovalCandidate> {
        val sourceRules = getRulesForRemoteSource(context, sourceId)
        if (sourceRules.isEmpty()) return emptyList()
        return sourceRules.mapNotNull { rule -> explainRemoteSourceNonAdCandidate(context, rule) }
    }

    fun importRules(
        context: Context,
        content: String,
        source: RuleSource = RuleSource.IMPORTED,
        allowWhitelistDomains: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Int {
        return importRulesStreaming(
            context = context,
            inputStream = content.byteInputStream(Charsets.UTF_8),
            source = source,
            allowWhitelistDomains = allowWhitelistDomains,
            onProgress = onProgress
        )
    }
    
    // P0.4 新增：大规则文件流式解析（避免 OOM）
    fun importRulesStreaming(
        context: Context,
        inputStream: InputStream,
        source: RuleSource = RuleSource.IMPORTED,
        allowWhitelistDomains: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Int {
        val startTime = System.currentTimeMillis()
        prepareForRuleImport(context, "local rule file streaming import")
        onProgress?.invoke("正在读取现有规则...")
        val currentStart = System.currentTimeMillis()
        synchronized(fileWriteLock) {
            val rewrite = beginStreamingRulesRewrite(context, excludeRemoteSourceId = null)
            LogRepository.append(context, "ImportRulesStreaming [1/4]: current=${rewrite.ruleCount}, time=${System.currentTimeMillis() - currentStart}ms")

            try {
                onProgress?.invoke("正在解析并导入规则...")
                val parseStart = System.currentTimeMillis()
                var addedCount = 0
                var parsedBlockedCount = 0
                var lineCount = 0
                var parsedRuleCount = 0
                var lastProgressAt = 0L
                inputStream.bufferedReader().useLines { lines ->
                    lines.forEach lineLoop@{ rawLine ->
                        lineCount += 1
                        if (shouldStopStreamingImport(context, addedCount, MAX_STREAM_IMPORT_NEW_RULES)) return@useLines
                        if (rawLine.length > MAX_IMPORT_LINE_CHARS) return@lineLoop
                        RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach fragmentLoop@{ fragment ->
                            if (shouldStopStreamingImport(context, addedCount, MAX_STREAM_IMPORT_NEW_RULES)) return@fragmentLoop
                            val trimmed = fragment.trim()
                            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@fragmentLoop
                            val simpleDomain = extractSimpleImportDomain(trimmed)
                            if (simpleDomain != null && (allowWhitelistDomains || !isWhitelistedDomain(simpleDomain))) {
                                parsedBlockedCount += 1
                                parsedRuleCount += 1
                                rewrite.writeSimpleRule(simpleDomain, source, null)
                                addedCount += 1
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (lineCount % 2000 == 0 || now - lastProgressAt >= 750L) {
                            lastProgressAt = now
                            onProgress?.invoke("正在解析并导入规则...\n已读取 ${lineCount} 行，识别 ${parsedRuleCount} 条，新增 ${addedCount} 条")
                        }
                    }
                }
                LogRepository.append(context, "ImportRulesStreaming [2/4]: fast parsed blocked=$parsedBlockedCount, added=$addedCount, time=${System.currentTimeMillis() - parseStart}ms")

                onProgress?.invoke("正在保存规则到本地...")
                val saveStart = System.currentTimeMillis()
                val finalCount = finishStreamingRulesRewrite(context, rewrite)
                LogRepository.append(context, "ImportRulesStreaming [4/4]: final=$finalCount, save time=${System.currentTimeMillis() - saveStart}ms")

                val elapsed = System.currentTimeMillis() - startTime
                LogRepository.append(context, "ImportRulesStreaming completed: finalRules=$finalCount, added=$addedCount TOTAL time=${elapsed}ms")

                return addedCount
            } catch (e: Exception) {
                abortStreamingRulesRewrite(rewrite)
                throw e
            } catch (e: OutOfMemoryError) {
                abortStreamingRulesRewrite(rewrite)
                throw e
            } finally {
                inputStream.close()
            }
        }
    }

    fun scheduleBackgroundAdvancedImport(
        context: Context,
        sourceLabel: String,
        source: RuleSource = RuleSource.IMPORTED,
        remoteSourceId: String? = null,
        allowWhitelistDomains: Boolean = false,
        deleteFileWhenDone: File? = null,
        openInputStream: () -> InputStream?
    ) {
        val appContext = context.applicationContext
        importScope.launch {
            val startTime = System.currentTimeMillis()
            runCatching {
                val stream = openInputStream() ?: throw IllegalStateException("无法重新打开规则内容")
                stream.use { input ->
                    importAdvancedRulesInBackground(
                        context = appContext,
                        inputStream = input,
                        source = source,
                        remoteSourceId = remoteSourceId,
                        allowWhitelistDomains = allowWhitelistDomains
                    )
                }
            }.onSuccess { added ->
                LogRepository.append(
                    appContext,
                    "Background advanced rule import completed: source=$sourceLabel, added=$added, time=${System.currentTimeMillis() - startTime}ms"
                )
            }.onFailure { error ->
                LogRepository.append(
                    appContext,
                    "Background advanced rule import failed: source=$sourceLabel, error=${error.message ?: error.javaClass.simpleName}, time=${System.currentTimeMillis() - startTime}ms"
                )
            }
            deleteFileWhenDone?.let { file -> runCatching { file.delete() } }
        }
    }

    private fun importAdvancedRulesInBackground(
        context: Context,
        inputStream: InputStream,
        source: RuleSource,
        remoteSourceId: String?,
        allowWhitelistDomains: Boolean
    ): Int {
        var addedCount = 0
        var parsedCount = 0
        var lineCount = 0
        var lineContext = RuleParsingSupport.LineContext()
        var lastLogAt = System.currentTimeMillis()

        synchronized(fileWriteLock) {
            val rewrite = beginStreamingRulesRewrite(context, excludeRemoteSourceId = null)
            try {
                inputStream.bufferedReader().useLines { lines ->
                    lines.forEach lineLoop@{ rawLine ->
                        lineCount += 1
                        if (shouldStopStreamingImport(context, addedCount, MAX_BACKGROUND_ADVANCED_NEW_RULES)) return@useLines
                        if (rawLine.length > MAX_IMPORT_LINE_CHARS) return@lineLoop
                        RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach fragmentLoop@{ fragment ->
                            if (shouldStopStreamingImport(context, addedCount, MAX_BACKGROUND_ADVANCED_NEW_RULES)) return@fragmentLoop
                            val trimmed = fragment.trim()
                            if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                                lineContext = RuleParsingSupport.parseRuleLineContext(trimmed)
                                return@fragmentLoop
                            }
                            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@fragmentLoop
                            if (extractSimpleImportDomain(trimmed) != null) return@fragmentLoop

                            val parsedLineRules = parseRuleLine(fragment, lineContext).ifEmpty {
                                listOfNotNull(parseUnsupportedImportRule(fragment, lineContext))
                            }
                            parsedLineRules.forEach parsedRuleLoop@{ parsedRule ->
                                if (parsedRule.isBadfilter) return@parsedRuleLoop
                                if (!allowWhitelistDomains && !parsedRule.isException && isWhitelistedDomain(parsedRule.domain)) return@parsedRuleLoop
                                rewrite.writeRule(buildBlockRuleFromParsedRule(
                                    context = context,
                                    parsedRule = parsedRule,
                                    source = source,
                                    remoteSourceId = remoteSourceId,
                                    useVendorHints = false
                                ))
                                parsedCount += 1
                                addedCount += 1
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (lineCount % 5000 == 0 || now - lastLogAt >= 5000L) {
                            lastLogAt = now
                            LogRepository.append(context, "Background advanced rule import progress: added=$addedCount, parsed=$parsedCount, lines=$lineCount, memory=${runtimeMemorySnapshot()}")
                        }
                    }
                }
                val finalCount = finishStreamingRulesRewrite(context, rewrite)
                LogRepository.append(context, "Background advanced rule import completed: added=$addedCount, parsed=$parsedCount, lines=$lineCount, final=$finalCount, memory=${runtimeMemorySnapshot()}")
                return addedCount
            } catch (e: Exception) {
                abortStreamingRulesRewrite(rewrite)
                throw e
            } catch (e: OutOfMemoryError) {
                abortStreamingRulesRewrite(rewrite)
                throw e
            }
        }
    }
    
    // 检测可能影响正常网络功能的规则（精准识别，只提醒真正的正常服务域名）
    private fun detectNetworkAffectingRules(content: String): List<String> {
        val affectingRules = mutableListOf<String>()
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@forEach
            
            // 检测是否包含网络层修饰符
            val hasNetworkModifier = trimmed.contains("\$network", ignoreCase = true) ||
                trimmed.contains("\$blockipv6", ignoreCase = true) ||
                trimmed.contains("\$blockipv4", ignoreCase = true) ||
                trimmed.contains("\$dnsrewrite=", ignoreCase = true) ||
                trimmed.contains("\$client=", ignoreCase = true) ||
                trimmed.contains("\$mac=", ignoreCase = true) ||
                trimmed.contains("\$asn=", ignoreCase = true)
            
            // 检测是否是宽泛规则
            val isWildcardRule = trimmed.startsWith("||*^") || trimmed == "*" || trimmed.startsWith("all:")
            
            if (hasNetworkModifier || isWildcardRule) {
                // 提取规则中的域名
                val domain = extractDomainFromRule(trimmed)
                if (domain != null) {
                    // 只有命中真正的保护域名才提醒（广告域名不提醒）
                    if (isProtectedNormalServiceDomain(domain)) {
                        affectingRules += "${extractRulePreview(trimmed)} → 命中保护域名：$domain"
                    }
                } else if (isWildcardRule) {
                    // 宽泛的全局规则直接提醒
                    affectingRules += "${extractRulePreview(trimmed)} → 全局规则"
                }
            }
        }
        return affectingRules
    }
    
    // 从规则中提取域名
    private fun extractDomainFromRule(rule: String): String? {
        // AdGuard 格式：||domain.com^
        val adguardMatch = Regex("""\|\|([a-z0-9.-]+\.[a-z]+)\^""").find(rule)
        if (adguardMatch != null) return adguardMatch.groupValues[1]
        
        // Hosts 格式：0.0.0.0 domain.com
        val hostsMatch = Regex("""^(?:0\.0\.0\.0|127\.0\.0\.1)\s+([a-z0-9.-]+\.[a-z]+)""", RegexOption.IGNORE_CASE).find(rule)
        if (hostsMatch != null) return hostsMatch.groupValues[1]
        
        // dnsmasq 格式：address=/domain.com/
        val dnsmasqMatch = Regex("""address=/([a-z0-9.-]+\.[a-z]+)/""").find(rule)
        if (dnsmasqMatch != null) return dnsmasqMatch.groupValues[1]
        
        // Clash 格式：DOMAIN-SUFFIX,domain.com
        val clashMatch = Regex("""(?:DOMAIN-SUFFIX|HOST-SUFFIX|DOMAIN|HOST),([a-z0-9.-]+\.[a-z]+)""", RegexOption.IGNORE_CASE).find(rule)
        if (clashMatch != null) return clashMatch.groupValues[1]
        
        return null
    }
    
    // 精准判断是否是真正的正常服务域名（不是广告域名）
    private fun isProtectedNormalServiceDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lower = normalized.lowercase()
        
        // 1. 首先检查是否是广告域名（广告域名不提醒）
        if (looksLikeAdDomain(normalized)) return false
        
        // 2. 检查是否在白名单域名列表（真正的正常服务）
        if (whitelistDomains.contains(lower)) return true
        if (whitelistDomains.any { lower.endsWith(".$it") }) return true
        
        // 3. 检查是否是游戏核心域名
        if (VendorConfigData.gameCoreDomains.contains(lower)) return true
        if (VendorConfigData.gameCoreDomains.any { lower.endsWith(".$it") }) return true
        
        // 4. 其他情况不提醒（让用户自己判断）
        return false
    }
    
    // 提取规则预览（截断过长内容）
    private fun extractRulePreview(rule: String): String {
        return rule.take(150).let { if (rule.length > 150) "$it..." else it }
    }

    private fun buildRemoteSourceReplacementBaseRules(
        context: Context,
        sourceId: String
    ): MutableList<BlockRule> {
        val normalizedSourceId = normalizeRemoteSourceId(sourceId)
        return getRules(context)
            .filterNot { hasRemoteSourceId(it, normalizedSourceId) }
            .toMutableList()
    }

    private fun filterRemoteRuleSources(
        context: Context,
        sourceId: String
    ): List<RemoteRuleSourceConfig> {
        return getRemoteRuleSources(context).filterNot { it.id == sourceId }
    }

    private fun findRemoteRuleSource(
        context: Context,
        sourceId: String
    ): RemoteRuleSourceConfig? {
        return getRemoteRuleSources(context).firstOrNull { it.id == sourceId }
    }

    private fun hasRemoteSourceId(rule: BlockRule, sourceId: String): Boolean {
        return normalizeRemoteSourceId(rule.remoteSourceId) == sourceId
    }

    private fun buildImportedRules(
        context: Context,
        current: MutableList<BlockRule>,
        parsed: ParsedRules,
        source: RuleSource,
        allowWhitelistDomains: Boolean
    ): List<BlockRule> {
        val importState = buildImportedRuleState(current)
        applyBadfilterScopes(importState.currentByDomain, parsed.badfilterRules)
        applyImportedBlockedRules(context, current, importState.currentByDomain, importState.existingRuleKeys, parsed.blockedRules, source, allowWhitelistDomains)
        applyExceptionScopes(importState.currentByDomain, parsed.exceptionRules)
        return finalizeImportedRules(
            buildImportedRuleCollections(
                current = current,
                currentByDomain = importState.currentByDomain,
                exceptionRules = parsed.exceptionRules
            )
        )
    }

    private fun normalizeRemoteSourceId(sourceId: String?): String {
        return sourceId?.trim().orEmpty()
    }

    private fun normalizeRemoteRuleSource(source: RemoteRuleSourceConfig): RemoteRuleSourceConfig? {
        return sanitizeRemoteRuleSource(source)
    }

    private data class ImportedRuleState(
        val currentByDomain: MutableMap<String, BlockRule>,
        val existingRuleKeys: MutableSet<String>
    )

    private data class ImportedRuleCollections(
        val mergedRules: List<BlockRule>,
        val exceptionRules: Collection<ParsedRule>
    )

    private fun buildImportedRuleState(current: List<BlockRule>): ImportedRuleState {
        return ImportedRuleState(
            currentByDomain = buildCurrentRuleDomainMap(current),
            existingRuleKeys = current.mapTo(linkedSetOf()) { buildRuleIdentityKey(it) }
        )
    }

    private fun buildSimpleRuleDomainSet(rules: List<BlockRule>): MutableSet<String> {
        return rules.asSequence()
            .filter { it.regexPattern == null && it.cosmeticSelector == null && it.ipCidr == null }
            .mapTo(linkedSetOf()) { it.domain }
    }

    private fun buildCompactImportedRule(domain: String, source: RuleSource, remoteSourceId: String?): BlockRule {
        return BlockRule(
            id = UUID.randomUUID().toString(),
            domain = domain,
            vendor = DEFAULT_VENDOR,
            source = source,
            remoteSourceId = remoteSourceId
        )
    }

    private fun extractSimpleImportDomain(line: String): String? {
        val normalizedLine = RuleParsingSupport.stripYamlListPrefix(RuleParsingSupport.unwrapRuleWrapper(line)).trim()
        if (normalizedLine.isBlank()) return null
        if (normalizedLine.startsWith("@@") || normalizedLine.contains("##") || normalizedLine.contains("#@#")) return null
        if (normalizedLine.contains('$')) return null
        val directDomainLine = normalizedLine

        if (directDomainLine.startsWith("||")) {
            val domainPart = directDomainLine.removePrefix("||")
                .substringBefore('^')
                .substringBefore('/')
                .trim()
            return sanitizeDomain(normalizeDomainToken(domainPart))
        }

        val parts = directDomainLine.split(splitWhitespaceRegex).filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val ip = parts[0]
            if (ip == "0.0.0.0" || ip == "127.0.0.1" || ip == "::" || ip == "::1") {
                return sanitizeDomain(normalizeDomainToken(parts[1]))
            }
        }

        listOf("address=/", "server=/", "local=/").firstOrNull { prefix ->
            directDomainLine.startsWith(prefix, ignoreCase = true)
        }?.let { prefix ->
            return sanitizeDomain(normalizeDomainToken(directDomainLine.substring(prefix.length).substringBefore('/').trim()))
        }

        if (directDomainLine.startsWith("bogus-nxdomain=", ignoreCase = true)) {
            return sanitizeDomain(normalizeDomainToken(directDomainLine.substringAfter('=').trim()))
        }

        val commaParts = directDomainLine.split(',').map { it.trim() }
        if (commaParts.size >= 2) {
            val type = commaParts[0].uppercase(Locale.US)
            if (type in setOf("DOMAIN", "DOMAIN-SUFFIX", "HOST", "HOST-SUFFIX")) {
                return sanitizeDomain(normalizeDomainToken(commaParts[1]))
            }
        }

        return sanitizeDomain(normalizeDomainToken(directDomainLine))
    }

    private fun addNormalizedRules(
        context: Context,
        domains: Collection<String>,
        source: RuleSource,
        allowWhitelistDomains: Boolean
    ): List<BlockRule> {
        if (domains.isEmpty()) return emptyList()
        val existingDomains = getExistingDomainSet(context)
        val added = mutableListOf<BlockRule>()
        val userOwnedSource = source == RuleSource.MANUAL || source == RuleSource.IMPORTED
        domains.forEach { domain ->
            if (!userOwnedSource && !allowWhitelistDomains && isWhitelistedDomain(domain)) return@forEach
            if (existingDomains.add(domain)) {
                added += buildNormalizedBlockRule(context, domain, source)
            }
        }
        if (added.isNotEmpty()) {
            appendRulesToFile(context, added)
        }
        return added
    }

    private fun buildNormalizedBlockRule(
        context: Context,
        domain: String,
        source: RuleSource
    ): BlockRule {
        return BlockRule(
            id = UUID.randomUUID().toString(),
            domain = domain,
            vendor = classifyVendor(context, domain),
            source = source
        )
    }

    private fun collectImportedBlockedRules(
        context: Context,
        blockedRules: Collection<ParsedRule>,
        existingRuleKeys: MutableSet<String>,
        source: RuleSource,
        allowWhitelistDomains: Boolean,
        remoteSourceId: String? = null,
        useVendorHints: Boolean,
        identityRemoteSourceId: String? = null
    ): List<BlockRule> {
        val added = mutableListOf<BlockRule>()
        forEachImportableBlockedRule(blockedRules, allowWhitelistDomains) { blockedRule ->
            val addedRule = addParsedRuleIfAbsent(
                context = context,
                existingRuleKeys = existingRuleKeys,
                parsedRule = blockedRule,
                source = source,
                remoteSourceId = remoteSourceId,
                useVendorHints = useVendorHints,
                identityRemoteSourceId = identityRemoteSourceId
            ) ?: return@forEachImportableBlockedRule
            added += addedRule
        }
        return added
    }

    private fun saveImportedRules(
        context: Context,
        rules: List<BlockRule>,
        exceptionRules: Collection<ParsedRule>
    ) {
        // 优化：跳过排序提升性能（规则顺序不影响匹配）
        // 仅当规则数量较少时才排序，减少 CPU 开销
        val finalRules = if (rules.size <= 1000) {
            finalizeImportedRules(ImportedRuleCollections(rules, exceptionRules))
        } else {
            // 大规则集不排序，直接保存
            applyCosmeticExceptionRules(rules, exceptionRules)
        }
        save(context, finalRules)
    }

    private fun buildCurrentRuleDomainMap(current: List<BlockRule>): MutableMap<String, BlockRule> {
        return current
            .filter { it.regexPattern == null && it.cosmeticSelector == null }
            .associateBy { it.domain }
            .toMutableMap()
    }

    private fun applyBadfilterScopes(
        currentByDomain: MutableMap<String, BlockRule>,
        badfilterRules: Collection<ParsedRule>
    ) {
        badfilterRules.forEach { badfilter ->
            val existing = currentByDomain[badfilter.domain] ?: return@forEach
            updateDomainRuleScope(currentByDomain, badfilter.domain, existing, badfilter.dnsTypes, badfilter.excludedDnsTypes)
        }
    }

    private fun applyImportedBlockedRules(
        context: Context,
        current: MutableList<BlockRule>,
        currentByDomain: MutableMap<String, BlockRule>,
        existingRuleKeys: MutableSet<String>,
        blockedRules: Collection<ParsedRule>,
        source: RuleSource,
        allowWhitelistDomains: Boolean
    ) {
        forEachImportableBlockedRule(blockedRules, allowWhitelistDomains) { blockedRule ->
            val ruleKey = buildParsedRuleIdentityKey(blockedRule)
            if (!existingRuleKeys.add(ruleKey)) return@forEachImportableBlockedRule
            val existing = resolveMergeableDomainRule(currentByDomain, blockedRule)
            if (existing == null) {
                val addedRule = buildBlockRuleFromParsedRule(
                    context = context,
                    parsedRule = blockedRule,
                    source = source,
                    useVendorHints = true
                )
                addImportedRule(current, currentByDomain, addedRule)
                return@forEachImportableBlockedRule
            }
            currentByDomain[blockedRule.domain] = mergeRuleTypeScopes(existing, blockedRule)
        }
    }

    private fun resolveMergeableDomainRule(
        currentByDomain: MutableMap<String, BlockRule>,
        blockedRule: ParsedRule
    ): BlockRule? {
        if (blockedRule.regexPattern != null || blockedRule.cosmeticSelector != null) return null
        return currentByDomain[blockedRule.domain]
    }

    private fun applyExceptionScopes(
        currentByDomain: MutableMap<String, BlockRule>,
        exceptionRules: Collection<ParsedRule>
    ) {
        if (exceptionRules.isEmpty() || currentByDomain.isEmpty()) return
        val exceptionsByDomain = exceptionRules.groupBy { it.domain }
        currentByDomain.keys.toList().forEach { domain ->
            currentByDomain[domain] ?: return@forEach
            forEachDomainSuffix(domain) { suffix ->
                exceptionsByDomain[suffix]?.forEach { exceptionRule ->
                    val before = currentByDomain[domain] ?: return@forEachDomainSuffix
                    updateDomainRuleScope(currentByDomain, domain, before, exceptionRule.dnsTypes, exceptionRule.excludedDnsTypes)
                    currentByDomain[domain] ?: return@forEachDomainSuffix
                }
            }
        }
    }

    private inline fun forEachDomainSuffix(domain: String, action: (String) -> Unit) {
        var suffix = domain
        while (suffix.isNotBlank()) {
            action(suffix)
            val dotIndex = suffix.indexOf('.')
            if (dotIndex < 0 || dotIndex == suffix.lastIndex) break
            suffix = suffix.substring(dotIndex + 1)
        }
    }

    private fun mergeImportedRuleCollections(
        current: List<BlockRule>,
        currentByDomain: MutableMap<String, BlockRule>
    ): List<BlockRule> {
        return buildList {
            addAll(current.filter { it.regexPattern != null || it.cosmeticSelector != null })
            addAll(currentByDomain.values)
        }.distinctBy { buildRuleIdentityKey(it) }
    }

    private fun buildImportedRuleCollections(
        current: List<BlockRule>,
        currentByDomain: MutableMap<String, BlockRule>,
        exceptionRules: Collection<ParsedRule>
    ): ImportedRuleCollections {
        return ImportedRuleCollections(
            mergedRules = mergeImportedRuleCollections(current, currentByDomain),
            exceptionRules = exceptionRules
        )
    }

    private fun finalizeImportedRules(
        collections: ImportedRuleCollections
    ): List<BlockRule> {
        return applyCosmeticExceptionRules(collections.mergedRules, collections.exceptionRules)
            .sortedBy { it.domain }
    }

    private data class ImportAnalysisState(
        val existingRuleKeys: MutableSet<String>,
        val simulatedDomains: MutableSet<String>,
        val seenBlocked: MutableSet<String> = linkedSetOf(),
        val seenExceptions: MutableSet<String> = linkedSetOf(),
        val unsupportedLines: MutableList<String> = mutableListOf(),
        val invalidLines: MutableList<String> = mutableListOf(),
        val whitelistConflictLines: MutableList<String> = mutableListOf(),
        val vendorCount: LinkedHashMap<String, Int> = linkedMapOf(),
        var blankOrCommentLines: Int = 0,
        var safeBlockedRules: Int = 0,
        var safeExceptionRules: Int = 0,
        var duplicateExistingRules: Int = 0,
        var duplicateWithinFileRules: Int = 0,
        var unsupportedModifierRules: Int = 0,
        var cosmeticRules: Int = 0,
        var regexRules: Int = 0,
        var invalidRules: Int = 0,
        var exceptionRemovalEstimate: Int = 0
    )

    private fun forEachImportableBlockedRule(
        blockedRules: Collection<ParsedRule>,
        allowWhitelistDomains: Boolean,
        action: (ParsedRule) -> Unit
    ) {
        // 移除白名单过滤：信任用户选择的规则源，与 AdGuard 行为一致
        // 规则源自带的白名单规则（@@||example.com）会自动保护重要域名
        blockedRules.forEach(action)
    }

    private fun addParsedRuleIfAbsent(
        context: Context,
        existingRuleKeys: MutableSet<String>,
        parsedRule: ParsedRule,
        source: RuleSource,
        remoteSourceId: String? = null,
        useVendorHints: Boolean,
        identityRemoteSourceId: String? = null
    ): BlockRule? {
        val ruleKey = buildParsedRuleIdentityKey(parsedRule, identityRemoteSourceId)
        if (!existingRuleKeys.add(ruleKey)) return null
        return buildBlockRuleFromParsedRule(
            context = context,
            parsedRule = parsedRule,
            source = source,
            remoteSourceId = remoteSourceId,
            useVendorHints = useVendorHints
        )
    }

    private fun addImportedRule(
        current: MutableList<BlockRule>,
        currentByDomain: MutableMap<String, BlockRule>,
        addedRule: BlockRule
    ) {
        current += addedRule
        if (addedRule.regexPattern == null && addedRule.cosmeticSelector == null) {
            currentByDomain[addedRule.domain] = addedRule
        }
    }

    private fun updateDomainRuleScope(
        currentByDomain: MutableMap<String, BlockRule>,
        domain: String,
        existing: BlockRule,
        dnsTypes: Set<Int>?,
        excludedDnsTypes: Set<Int>?
    ) {
        subtractDnsTypeScope(existing, dnsTypes, excludedDnsTypes)?.let {
            currentByDomain[domain] = it
        } ?: currentByDomain.remove(domain)
    }

    private fun buildBlockRuleFromParsedRule(
        context: Context,
        parsedRule: ParsedRule,
        source: RuleSource,
        remoteSourceId: String? = null,
        useVendorHints: Boolean
    ): BlockRule {
        val vendor = classifyParsedRuleVendor(context, parsedRule, useVendorHints)
        return BlockRule(
            id = UUID.randomUUID().toString(),
            domain = parsedRule.domain,
            vendor = vendor,
            source = if (parsedRule.isUnsupported) RuleSource.UNSUPPORTED else source,
            dnsTypes = normalizeDnsTypes(parsedRule.dnsTypes),
            excludedDnsTypes = normalizeDnsTypes(parsedRule.excludedDnsTypes),
            thirdParty = parsedRule.thirdParty,
            firstParty = parsedRule.firstParty,
            important = parsedRule.important,
            redirect = parsedRule.redirect,
            domainConstraints = parsedRule.domainConstraints,
            excludedDomainConstraints = parsedRule.excludedDomainConstraints,
            denyallow = parsedRule.denyallow,
            urlblock = parsedRule.urlblock,
            requestTypes = parsedRule.requestTypes,
            appPackages = parsedRule.appPackages,
            destinationPorts = parsedRule.destinationPorts,
            sourcePorts = parsedRule.sourcePorts,
            keywordPattern = parsedRule.keywordPattern,
            pathPattern = parsedRule.pathPattern,
            ipCidr = parsedRule.ipCidr,
            regexPattern = parsedRule.regexPattern,
            cosmeticSelector = parsedRule.cosmeticSelector,
            cosmeticException = parsedRule.isException,
            exceptionRule = parsedRule.isException,
            removeParams = parsedRule.removeParams,
            removeParamRegexes = parsedRule.removeParamRegexes,
            removeRequestHeaders = parsedRule.removeRequestHeaders,
            setRequestHeaders = parsedRule.setRequestHeaders,
            replaceRules = parsedRule.replaceRules,
            cspValue = parsedRule.cspValue,
            redirectResource = parsedRule.redirectResource,
            jsInjectRules = parsedRule.jsInjectRules,
            cookieRemove = parsedRule.cookieRemove,
            cookieSet = parsedRule.cookieSet,
            toDomains = parsedRule.toDomains,
            cname = parsedRule.cname,
            emptyResponse = parsedRule.emptyResponse,
            remoteSourceId = remoteSourceId
        )
    }

    private fun classifyParsedRuleVendor(
        context: Context,
        parsedRule: ParsedRule,
        useVendorHints: Boolean
    ): String {
        val hints = if (useVendorHints) parsedRule.vendorHints.toTypedArray() else emptyArray()
        return classifyVendorSimple(context, parsedRule.domain, *hints) ?: DEFAULT_VENDOR
    }

    private fun incrementVendorCount(vendorCount: MutableMap<String, Int>, vendor: String) {
        vendorCount[vendor] = (vendorCount[vendor] ?: 0) + 1
    }

    private fun countRemovedSimulatedDomains(simulatedDomains: MutableSet<String>, domain: String): Int {
        val removed = simulatedDomains.count { it == domain || it.endsWith(".$domain") }
        simulatedDomains.removeAll { it == domain || it.endsWith(".$domain") }
        return removed
    }

    private data class ParsedRuleAnalysisStepResult(
        val duplicateExistingDelta: Int = 0,
        val duplicateWithinFileDelta: Int = 0,
        val safeBlockedDelta: Int = 0,
        val safeExceptionDelta: Int = 0,
        val exceptionRemovalDelta: Int = 0,
        val vendor: String? = null,
        val shouldContinue: Boolean = true
    )

    private data class InvalidRuleAnalysisResult(
        val unsupportedModifierDelta: Int = 0,
        val invalidRuleDelta: Int = 0,
        val unsupportedLine: String? = null,
        val invalidLine: String? = null
    )

    private data class ParsedRulePreAnalysisResult(
        val regexDelta: Int = 0,
        val cosmeticDelta: Int = 0,
        val whitelistConflictLine: String? = null
    )

    private fun applyInvalidRuleAnalysis(
        result: InvalidRuleAnalysisResult,
        unsupportedLines: MutableList<String>,
        invalidLines: MutableList<String>
    ): Pair<Int, Int> {
        result.unsupportedLine?.let { unsupportedLines += it }
        result.invalidLine?.let { invalidLines += it }
        return result.unsupportedModifierDelta to result.invalidRuleDelta
    }

    private fun applyParsedRulePreAnalysis(
        result: ParsedRulePreAnalysisResult,
        whitelistConflictLines: MutableList<String>
    ): Pair<Int, Int> {
        result.whitelistConflictLine?.let { whitelistConflictLines += it }
        return result.regexDelta to result.cosmeticDelta
    }

    private fun analyzeParsedRuleStep(
        context: Context,
        parsedRule: ParsedRule,
        ruleKey: String,
        existingRuleKeys: Set<String>,
        seenBlocked: MutableSet<String>,
        seenExceptions: MutableSet<String>,
        simulatedDomains: MutableSet<String>
    ): ParsedRuleAnalysisStepResult {
        if (parsedRule.isException) {
            if (!seenExceptions.add(ruleKey)) {
                return ParsedRuleAnalysisStepResult(
                    duplicateWithinFileDelta = 1,
                    shouldContinue = false
                )
            }
            return ParsedRuleAnalysisStepResult(
                safeExceptionDelta = 1,
                exceptionRemovalDelta = countRemovedSimulatedDomains(simulatedDomains, parsedRule.domain),
                shouldContinue = false
            )
        }
        if (parsedRule.isBadfilter) {
            val removed = simulatedDomains.remove(parsedRule.domain)
            return ParsedRuleAnalysisStepResult(
                safeExceptionDelta = 1,
                exceptionRemovalDelta = if (removed) 1 else 0,
                shouldContinue = false
            )
        }
        if (!seenBlocked.add(ruleKey)) {
            return ParsedRuleAnalysisStepResult(
                duplicateWithinFileDelta = 1,
                shouldContinue = false
            )
        }
        if (existingRuleKeys.contains(ruleKey)) {
            return ParsedRuleAnalysisStepResult(
                duplicateExistingDelta = 1,
                shouldContinue = false
            )
        }
        simulatedDomains += parsedRule.domain
        return ParsedRuleAnalysisStepResult(
            safeBlockedDelta = 1,
            vendor = classifyParsedRuleVendor(context, parsedRule, useVendorHints = true)
        )
    }

    private fun analyzeInvalidImportRule(line: String): InvalidRuleAnalysisResult {
        val working = line.removePrefix("@@")
        val candidate = extractDomainCandidate(working)
        if (candidate == null) {
            return if (looksLikeComplexRulePattern(working)) {
                InvalidRuleAnalysisResult(
                    unsupportedModifierDelta = 1,
                    unsupportedLine = "$line    [complex-pattern]"
                )
            } else {
                InvalidRuleAnalysisResult(
                    invalidRuleDelta = 1,
                    invalidLine = line
                )
            }
        }
        val modifierInfo = parseModifierInfo(candidate.second)
        if (modifierInfo.unsupportedModifiers.isNotEmpty()) {
            return InvalidRuleAnalysisResult(
                unsupportedModifierDelta = 1,
                unsupportedLine = "$line    [${modifierInfo.unsupportedModifiers.joinToString(", ")}]"
            )
        }
        if (modifierInfo.invalid) {
            return InvalidRuleAnalysisResult(
                unsupportedModifierDelta = 1,
                unsupportedLine = "$line    [invalid-modifier]"
            )
        }
        return InvalidRuleAnalysisResult(
            invalidRuleDelta = 1,
            invalidLine = line
        )
    }

    private fun analyzeParsedRulePreStep(parsedRule: ParsedRule, line: String): ParsedRulePreAnalysisResult {
        return ParsedRulePreAnalysisResult(
            regexDelta = if (parsedRule.regexPattern != null) 1 else 0,
            cosmeticDelta = if (parsedRule.cosmeticSelector != null) 1 else 0,
            whitelistConflictLine = if (!parsedRule.isException && !parsedRule.isBadfilter && isWhitelistedDomain(parsedRule.domain)) line else null
        )
    }

    private fun buildParsedRuleIdentityKey(parsedRule: ParsedRule, remoteSourceId: String? = null): String {
        return buildParsedRuleKey(
            domain = parsedRule.domain,
            dnsTypes = parsedRule.dnsTypes,
            excludedDnsTypes = parsedRule.excludedDnsTypes,
            badfilter = parsedRule.isBadfilter,
            firstParty = parsedRule.firstParty,
            important = parsedRule.important,
            pathPattern = parsedRule.pathPattern,
            ipCidr = parsedRule.ipCidr,
            regexPattern = parsedRule.regexPattern,
            cosmeticSelector = parsedRule.cosmeticSelector,
            removeParams = parsedRule.removeParams,
            removeParamRegexes = parsedRule.removeParamRegexes,
            removeRequestHeaders = parsedRule.removeRequestHeaders,
            setRequestHeaders = parsedRule.setRequestHeaders,
            replaceRules = parsedRule.replaceRules,
            cspValue = parsedRule.cspValue,
            jsInjectRules = parsedRule.jsInjectRules,
            keywordPattern = parsedRule.keywordPattern,
            domainConstraints = parsedRule.domainConstraints,
            excludedDomainConstraints = parsedRule.excludedDomainConstraints,
            appPackages = parsedRule.appPackages,
            requestTypes = parsedRule.requestTypes,
            destinationPorts = parsedRule.destinationPorts,
            sourcePorts = parsedRule.sourcePorts,
            denyallow = parsedRule.denyallow,
            remoteSourceId = remoteSourceId,
            cosmeticException = parsedRule.isException
        )
    }

    fun removeByIds(context: Context, ids: Set<String>) {
        save(context, getRules(context).filterNot { ids.contains(it.id) })
    }

    fun removeRulesByIds(context: Context, ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val normalizedIds = ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalizedIds.isEmpty()) return 0
        val current = getRules(context)
        val removal = removeRulesInternal(current, normalizedIds, emptySet())
        if (removal.removedCount > 0) save(context, removal.remaining)
        return removal.removedCount
    }

    fun removeRules(context: Context, rules: Collection<BlockRule>): Int {
        if (rules.isEmpty()) return 0
        val normalizedIds = rules.asSequence()
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val identityKeys = rules.asSequence()
            .map(::buildRuleIdentityKey)
            .toSet()
        if (normalizedIds.isEmpty() && identityKeys.isEmpty()) return 0
        val current = getRules(context)
        val removal = removeRulesInternal(current, normalizedIds, identityKeys)
        if (removal.removedCount > 0) save(context, removal.remaining)
        return removal.removedCount
    }

    fun removeRules(context: Context, ids: Set<String>, rules: Collection<BlockRule>): Int {
        val normalizedIds = ids.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val identityKeys = rules.asSequence()
            .map(::buildRuleIdentityKey)
            .toSet()
        if (normalizedIds.isEmpty() && identityKeys.isEmpty()) return 0
        val current = getRules(context)
        val removal = removeRulesInternal(current, normalizedIds, identityKeys)
        if (removal.removedCount > 0) save(context, removal.remaining)
        return removal.removedCount
    }

    fun removeAllRules(context: Context): Int {
        val current = getRules(context)
        if (current.isEmpty()) return 0
        save(context, emptyList())
        return current.size
    }

    fun isBlocked(
        context: Context,
        domain: String,
        qType: Int? = null,
        appName: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val candidates = buildDomainCandidates(normalized).toList()
        val simpleIndex = getSimpleDomainIndex(context)
        val trie = getTrieIndex(context)
        val simpleImportantBlock = trie.hasImportantBlock(normalized)
        if (simpleImportantBlock) return true
        val simpleUserOwnedBlock = trie.hasUserOwnedBlock(normalized)
        if (isCoreTrafficProtectedDomain(normalized) && !simpleUserOwnedBlock) {
            val hasSimpleOnly = candidates.any { it in simpleIndex.blocked || it in simpleIndex.exceptions }
            if (hasSimpleOnly && getRuleMap(context).isEmpty() && getRegexRules(context).isEmpty() && getKeywordRules(context).isEmpty()) return false
        }
        getRuleMap(context)  // triggers buildAppRuleIndex
        val matchingRules = candidates.asSequence()
            .flatMap { candidate -> getFilteredRulesForApp(context, candidate, appName).asSequence() }
            .filter {
                it.source != RuleSource.UNSUPPORTED &&
                ruleMatches(it, qType, appName) &&
                    matchesPortScope(it.destinationPorts, destinationPort) &&
                    matchesPortScope(it.sourcePorts, sourcePort)
            }
            .toList()
        val lowerDomain = normalized.lowercase()

        // 合并正则规则扫描：一次遍历得出 important / userOwned / general 三类结果
        // 使用字面子串索引预过滤：仅扫描 domain 包含其字面子串的规则
        var regexImportant = false
        var regexUserOwned = false
        var regexGeneral = false
        val pkg = appName?.let { extractPackageName(it) }
        val regexIndex = getRegexLiteralIndex(context)
        val candidateRegexRules = regexIndex.entries.asSequence()
            .filter { (fragment, _) -> lowerDomain.contains(fragment) }
            .flatMap { it.value.asSequence() }
            .filter { rule -> pkg == null || rule.appPackages.isEmpty() || pkg in rule.appPackages }
            .distinct()
        for (rule in candidateRegexRules) {
            if (rule.source == RuleSource.UNSUPPORTED) continue
            if (!matchesRegexRule(rule, normalized)) continue
            if (isImportantBlockingRule(rule)) { regexImportant = true; break }
            if (isUserOwnedBlockingRule(rule)) regexUserOwned = true
            if (!rule.exceptionRule) regexGeneral = true
        }

        // 合并关键词规则扫描：使用组合正则预过滤后仅扫描命中的规则
        var keywordImportant = false
        var keywordUserOwned = false
        var keywordGeneral = false
        val combinedKwMatcher = getCombinedKeywordMatcher(context)
        if (combinedKwMatcher != null && combinedKwMatcher.matcher(lowerDomain).find()) {
            for (rule in getFilteredKeywordRules(context, appName)) {
                val keyword = rule.keywordPattern?.lowercase() ?: continue
                if (!lowerDomain.contains(keyword)) continue
                if (rule.source == RuleSource.UNSUPPORTED) continue
                if (isImportantBlockingRule(rule)) { keywordImportant = true; break }
                if (isUserOwnedBlockingRule(rule)) keywordUserOwned = true
                if (!rule.exceptionRule) keywordGeneral = true
            }
        }

        val hasImportantBlock = simpleImportantBlock || matchingRules.any(::isImportantBlockingRule) || regexImportant || keywordImportant
        if (hasImportantBlock) return true
        val hasUserOwnedBlock = simpleUserOwnedBlock || matchingRules.any(::isUserOwnedBlockingRule) || regexUserOwned || keywordUserOwned
        if (isCoreTrafficProtectedDomain(normalized) && !hasUserOwnedBlock) return false
        if (!hasUserOwnedBlock && (trie.hasException(normalized) || matchingRules.any { it.exceptionRule })) return false
        if (hasUserOwnedBlock) return true
        val matched = trie.hasBlocked(normalized) || matchingRules.any { !it.exceptionRule } || regexGeneral || keywordGeneral
        if (matched) return true
        if (isWhitelistedDomain(normalized)) return false
        return false
    }

    // 性能优化：快速拦截检查（跳过关键词规则，仅匹配精确规则和正则规则）
    fun isBlockedFast(context: Context, domain: String, qType: Int? = null): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val candidates = buildDomainCandidates(normalized).toList()
        val simpleIndex = getSimpleDomainIndex(context)
        val trie = getTrieIndex(context)
        val simpleImportantBlock = trie.hasImportantBlock(normalized)
        if (simpleImportantBlock) return true
        val simpleUserOwnedBlock = trie.hasUserOwnedBlock(normalized)
        getRuleMap(context)  // triggers buildAppRuleIndex
        val matchingRules = candidates.asSequence()
            .flatMap { candidate -> getFilteredRulesForApp(context, candidate, null).asSequence() }
            .filter { it.source != RuleSource.UNSUPPORTED && ruleMatches(it, qType, null) && it.destinationPorts.isEmpty() && it.sourcePorts.isEmpty() }
            .toList()

        // 合并正则规则扫描：使用字面子串索引预过滤
        var regexImportant = false
        var regexUserOwned = false
        var regexGeneral = false
        val fastLowerDomain = normalized.lowercase()
        val fastRegexIndex = getRegexLiteralIndex(context)
        val fastCandidateRegexRules = fastRegexIndex.entries.asSequence()
            .filter { (fragment, _) -> fastLowerDomain.contains(fragment) }
            .flatMap { it.value.asSequence() }
            .distinct()
        for (rule in fastCandidateRegexRules) {
            if (rule.source == RuleSource.UNSUPPORTED) continue
            if (!matchesRegexRule(rule, normalized)) continue
            if (isImportantBlockingRule(rule)) { regexImportant = true; break }
            if (isUserOwnedBlockingRule(rule)) regexUserOwned = true
            if (!rule.exceptionRule) regexGeneral = true
        }

        val hasUserOwnedBlock = simpleUserOwnedBlock || matchingRules.any(::isUserOwnedBlockingRule) || regexUserOwned
        val hasImportantBlock = simpleImportantBlock || matchingRules.any(::isImportantBlockingRule) || regexImportant
        if (hasImportantBlock) return true
        if (isCoreTrafficProtectedDomain(normalized) && !hasUserOwnedBlock) return false
        if (!hasUserOwnedBlock && (trie.hasException(normalized) || matchingRules.any { it.exceptionRule })) return false
        if (hasUserOwnedBlock) return true
        val matched = trie.hasBlocked(normalized) || matchingRules.any { !it.exceptionRule } || regexGeneral
        if (matched) return true
        if (isWhitelistedDomain(normalized)) return false
        return false
    }

    fun isUrlBlocked(
        context: Context,
        host: String,
        path: String,
        appName: String? = null,
        requestDomain: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null,
        requestType: String? = null
    ): Boolean {
        val normalizedHost = sanitizeDomain(host) ?: return false
        val candidates = buildDomainCandidates(normalizedHost).toList()
        val simpleIndex = getSimpleDomainIndex(context)
        val trie = getTrieIndex(context)
        val simpleImportantBlock = trie.hasImportantBlock(normalizedHost)
        if (simpleImportantBlock) return true
        val simpleUserOwnedBlock = trie.hasUserOwnedBlock(normalizedHost)
        getRuleMap(context)  // triggers buildAppRuleIndex
        val fullUrl = "$host$path".lowercase()
        val effectiveRequestType = requestType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: inferRequestTypeFromPath(path)
        val matchingRules = candidates.asSequence()
            .flatMap { candidate -> getFilteredRulesForApp(context, candidate, appName).asSequence() }
            .filter { rule ->
                if (rule.source == RuleSource.UNSUPPORTED) return@filter false
                if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@filter false
                if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@filter false
                if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@filter false
                if (rule.keywordPattern != null) {
                    return@filter fullUrl.contains(rule.keywordPattern)
                }
                if (!rule.pathPattern.isNullOrBlank() && path.isNotBlank()) {
                    return@filter pathMatchesPattern(path, rule.pathPattern)
                }
                if (rule.urlblock && path.isNotBlank()) {
                    return@filter looksLikeSuspiciousPath(path)
                }
                rule.appPackages.isEmpty() || matchesAppPackage(rule.appPackages, appName)
            }
            .toList()
        val hasUserOwnedBlock = simpleUserOwnedBlock || matchingRules.any(::isUserOwnedBlockingRule) || getFilteredRegexRules(context, appName).any { rule ->
            if (!isUserOwnedBlockingRule(rule)) return@any false
            if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@any false
            if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@any false
            if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@any false
            matchesRegexRule(rule, fullUrl)
        }
        val hasImportantBlock = simpleImportantBlock || matchingRules.any(::isImportantBlockingRule) || getFilteredRegexRules(context, appName).any { rule ->
            if (!isImportantBlockingRule(rule)) return@any false
            if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@any false
            if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@any false
            if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@any false
            matchesRegexRule(rule, fullUrl)
        }
        if (hasImportantBlock) return true
        if (isCoreTrafficProtectedDomain(normalizedHost) && !hasUserOwnedBlock) return false
        val hasExceptionMatch = trie.hasException(normalizedHost) || matchingRules.any { it.exceptionRule } || getFilteredRegexRules(context, appName).any { rule ->
            if (rule.source == RuleSource.UNSUPPORTED) return@any false
            if (!rule.exceptionRule) return@any false
            if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@any false
            if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@any false
            if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@any false
            matchesRegexRule(rule, fullUrl)
        }
        if (!hasUserOwnedBlock && hasExceptionMatch) return false
        if (hasUserOwnedBlock) return true
        val matched = trie.hasBlocked(normalizedHost) || matchingRules.any { !it.exceptionRule } || getFilteredRegexRules(context, appName).any { rule ->
            if (rule.source == RuleSource.UNSUPPORTED) return@any false
            if (rule.exceptionRule) return@any false
            if (!ruleMatches(rule, null, appName, normalizedHost, requestDomain, effectiveRequestType)) return@any false
            if (!matchesPortScope(rule.destinationPorts, destinationPort)) return@any false
            if (!matchesPortScope(rule.sourcePorts, sourcePort)) return@any false
            matchesRegexRule(rule, fullUrl)
        }
        if (matched) return true
        if (isWhitelistedDomain(normalizedHost)) return false
        return false
    }

    private fun inferRequestTypeFromPath(path: String): String? {
        val cleanPath = path.substringBefore('?').substringBefore('#').lowercase()
        return when {
            cleanPath.endsWith(".js") || cleanPath.endsWith(".mjs") -> "script"
            cleanPath.endsWith(".css") -> "stylesheet"
            cleanPath.endsWith(".png") || cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") ||
                cleanPath.endsWith(".gif") || cleanPath.endsWith(".webp") || cleanPath.endsWith(".avif") ||
                cleanPath.endsWith(".svg") || cleanPath.endsWith(".ico") -> "image"
            cleanPath.endsWith(".woff") || cleanPath.endsWith(".woff2") || cleanPath.endsWith(".ttf") ||
                cleanPath.endsWith(".otf") || cleanPath.endsWith(".eot") -> "font"
            cleanPath.endsWith(".mp4") || cleanPath.endsWith(".m4v") || cleanPath.endsWith(".webm") ||
                cleanPath.endsWith(".mp3") || cleanPath.endsWith(".m3u8") || cleanPath.endsWith(".ts") -> "media"
            cleanPath.endsWith("/") || cleanPath.endsWith(".html") || cleanPath.endsWith(".htm") ||
                !cleanPath.substringAfterLast('/').contains('.') -> "document"
            else -> null
        }
    }

    fun findMatchingIpRule(
        context: Context,
        ip: String,
        appName: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): BlockRule? {
        val normalizedIp = sanitizeIpLiteral(ip) ?: return null
        val address = runCatching { InetAddress.getByName(normalizedIp) }.getOrNull() ?: return null
        val ipRules = cachedIpCidrRules ?: getRules(context).filter { !it.ipCidr.isNullOrBlank() }.also { cachedIpCidrRules = it }
        val pkg = extractPackageName(appName)
        return ipRules
            .firstOrNull { rule ->
                rule.source != RuleSource.UNSUPPORTED &&
                    !rule.exceptionRule &&
                    (rule.appPackages.isEmpty() || (pkg != null && pkg in rule.appPackages)) &&
                    matchesPortScope(rule.destinationPorts, destinationPort) &&
                    matchesPortScope(rule.sourcePorts, sourcePort) &&
                    matchesIpCidr(address, rule.ipCidr)
            }
    }

    fun findMatchingPortOnlyRule(
        context: Context,
        appName: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): BlockRule? {
        if (destinationPort == null && sourcePort == null) return null
        val portRules = cachedPortOnlyRules ?: getRules(context)
            .filter { it.domain == "*" && it.ipCidr.isNullOrBlank() }
            .also { cachedPortOnlyRules = it }
        val pkg = extractPackageName(appName)
        return portRules
            .firstOrNull { rule ->
                rule.source != RuleSource.UNSUPPORTED &&
                    !rule.exceptionRule &&
                    (rule.appPackages.isEmpty() || (pkg != null && pkg in rule.appPackages)) &&
                    matchesPortScope(rule.destinationPorts, destinationPort) &&
                    matchesPortScope(rule.sourcePorts, sourcePort)
            }
    }

    fun hasIpRules(context: Context): Boolean {
        cachedIpCidrRules?.let { return it.isNotEmpty() }
        val rules = getRules(context).filter { !it.ipCidr.isNullOrBlank() }
        cachedIpCidrRules = rules
        return rules.isNotEmpty()
    }

    fun hasPortOnlyRules(context: Context): Boolean {
        cachedPortOnlyRules?.let { return it.isNotEmpty() }
        val rules = getRules(context).filter { it.domain == "*" && it.ipCidr.isNullOrBlank() }
        cachedPortOnlyRules = rules
        return rules.isNotEmpty()
    }
    
    fun isWhitelistedDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lowerDomain = normalized.lowercase()
        cachedWhitelistHits[lowerDomain]?.let { return it }
        val result = checkDomainWhitelist(lowerDomain)
        cachedWhitelistHits[lowerDomain] = result
        if (cachedWhitelistHits.size > MAX_CACHED_WHITELIST_ENTRIES) {
            clearWhitelistCache()
        }
        return result
    }

    fun isBypassProtectionDomain(domain: String): Boolean {
        return RuleVendorSupport.isBypassProtectionDomain(
            domain = domain,
            sanitizeDomain = ::sanitizeDomain,
            buildDomainCandidates = ::buildDomainCandidates,
            bypassProtectionDomains = VendorConfigData.bypassProtectionDomains
        )
    }

    fun isGameCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return gameCoreTrie.contains(normalized)
    }

    fun isSocialCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return socialCoreTrie.contains(normalized)
    }

    fun isMediaCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return mediaCoreTrie.contains(normalized)
    }

    fun shouldProtectMediaTraffic(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        if (!isMediaCoreDomain(normalized)) return false
        if (looksLikeAdDomain(normalized)) return false
        return true
    }

    fun isBusinessCoreDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        return businessCoreTrie.contains(normalized)
    }

    fun shouldProtectBusinessTraffic(domain: String): Boolean {
        val normalized = sanitizeDomain(domain)?.lowercase() ?: return false
        if (!isBusinessCoreDomain(normalized)) return false
        if (looksLikeAdDomain(normalized)) return false
        return true
    }

    private fun checkDomainWhitelist(lowerDomain: String): Boolean {
        if (whitelistTrie.contains(lowerDomain)) {
            if (looksLikeWhitelistedRootAdSubdomain(lowerDomain)) {
                return false
            }
            return true
        }
        if (lowerDomain.contains("umeng.com") || lowerDomain.contains("umengcloud.com")) {
            if (umengTrie.contains(lowerDomain)) {
                return true
            }
        }
        if (qqTrie.contains(lowerDomain)) {
            return true
        }
        if (neteaseTrie.contains(lowerDomain)) {
            return true
        }
        return false
    }

    val criticalStartupDomains: Set<String>
        get() = setOf(
            "clientservices.googleapis.com",
            "update.googleapis.com",
            "android.clients.google.com",
            "play.googleapis.com",
            "firebaseinstallations.googleapis.com",
            "app-measurement.com",
            "firebase-analytics.com",
            "android.googleapis.com",
            "ssl.gstatic.com",
            "fonts.googleapis.com",
            "fonts.gstatic.com"
        )

    private val whitelistSuffixRoots by lazy {
        whitelistDomains.map { ".$it" }.toSet()
    }

    // 域名后缀 Trie - 替代 endsWith 线性扫描，查询从 O(N) 降为 O(L)
    private val whitelistTrie by lazy { DomainSuffixTrie.fromDomains(whitelistDomains) }
    private val gameCoreTrie by lazy { DomainSuffixTrie.fromDomains(VendorConfigData.gameCoreDomains) }
    private val socialCoreTrie by lazy { DomainSuffixTrie.fromDomains(VendorConfigData.socialCoreDomains) }
    private val mediaCoreTrie by lazy { DomainSuffixTrie.fromDomains(VendorConfigData.mediaCoreDomains) }
    private val businessCoreTrie by lazy { DomainSuffixTrie.fromDomains(VendorConfigData.businessCoreDomains) }
    private val novelContentApiTrie by lazy { DomainSuffixTrie.fromDomains(VendorConfigData.novelContentApiDomains) }
    private val umengTrie by lazy { DomainSuffixTrie.fromDomains(umengWhitelistSubDomains) }
    private val qqTrie by lazy { DomainSuffixTrie.fromDomains(qqWhitelistSubDomains) }
    private val neteaseTrie by lazy { DomainSuffixTrie.fromDomains(neteaseWhitelistSubDomains) }

    // 合并保护域名 Trie - isCoreTrafficProtectedDomain 一次查询替代多次独立扫描
    private val protectedDomainTrie by lazy {
        DomainSuffixTrie().also { trie ->
            trie.insertAll(whitelistDomains)
            trie.insertAll(VendorConfigData.gameCoreDomains)
            trie.insertAll(VendorConfigData.socialCoreDomains)
            trie.insertAll(VendorConfigData.mediaCoreDomains)
            trie.insertAll(VendorConfigData.businessCoreDomains)
        }
    }

    fun findMatchingRule(
        context: Context,
        domain: String,
        qType: Int? = null,
        appName: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): BlockRule? {
        val normalized = sanitizeDomain(domain) ?: return null
        val candidates = buildDomainCandidates(normalized).toList()
        val simpleIndex = getSimpleDomainIndex(context)
        getRuleMap(context)  // triggers buildAppRuleIndex
        val hasException = candidates.any { it in simpleIndex.exceptions }
        val domainMatch = candidates.asSequence()
            .flatMap { candidate -> getFilteredRulesForApp(context, candidate, appName).asSequence() }
            .filter {
                it.source != RuleSource.UNSUPPORTED &&
                    ruleMatches(it, qType, appName) &&
                    !it.exceptionRule &&
                    matchesPortScope(it.destinationPorts, destinationPort) &&
                    matchesPortScope(it.sourcePorts, sourcePort) &&
                    (!hasException || isUserOwnedBlockingRule(it))
            }
            .firstOrNull()
        val regexMatch = getRegexRules(context).firstOrNull {
            it.source != RuleSource.UNSUPPORTED &&
                !it.exceptionRule &&
                matchesRegexRule(it, normalized) &&
                (!hasException || isUserOwnedBlockingRule(it))
        }
        val simpleMatch = if (!hasException) {
            candidates.firstOrNull { it in simpleIndex.blocked && it !in simpleIndex.exceptions }?.let { matchedDomain ->
                BlockRule(
                    id = "simple-index-$matchedDomain",
                    domain = matchedDomain,
                    vendor = classifyVendorSimple(context, matchedDomain) ?: DEFAULT_VENDOR,
                    source = if (matchedDomain in simpleIndex.userOwnedBlocked) RuleSource.IMPORTED else RuleSource.REFERENCE,
                    important = matchedDomain in simpleIndex.importantBlocked
                )
            }
        } else {
            candidates.firstOrNull { it in simpleIndex.exceptions && it in simpleIndex.blocked && it in simpleIndex.userOwnedBlocked }?.let { matchedDomain ->
                BlockRule(
                    id = "simple-index-$matchedDomain",
                    domain = matchedDomain,
                    vendor = classifyVendorSimple(context, matchedDomain) ?: DEFAULT_VENDOR,
                    source = RuleSource.IMPORTED,
                    important = matchedDomain in simpleIndex.importantBlocked
                )
            }
        }
        val match = domainMatch ?: regexMatch ?: simpleMatch
        if (isCoreTrafficProtectedDomain(normalized) && match?.let(::isUserOwnedBlockingRule) != true) return null
        return match
    }

    fun isDomainExcepted(context: Context, domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val result = buildDomainCandidates(normalized).any { it in getSimpleDomainIndex(context).exceptions }
        if (result) {
            LogRepository.append(context, "isDomainExcepted: $domain($normalized) -> true (exceptions=${getSimpleDomainIndex(context).exceptions.take(5).joinToString()})")
        }
        return result
    }

    internal fun isUserOwnedRule(rule: BlockRule): Boolean {
        return rule.source == RuleSource.MANUAL ||
            (rule.source == RuleSource.IMPORTED && rule.remoteSourceId.isNullOrBlank())
    }

    private fun isUserOwnedBlockingRule(rule: BlockRule): Boolean {
        return isUserOwnedRule(rule) && !rule.exceptionRule && rule.source != RuleSource.UNSUPPORTED
    }

    private fun isImportantBlockingRule(rule: BlockRule): Boolean {
        return rule.important && !rule.exceptionRule && rule.source != RuleSource.UNSUPPORTED
    }

    private fun isCoreTrafficProtectedDomain(domain: String): Boolean {
        return isWhitelistedDomain(domain) ||
            isSensitiveAuthDomain(domain) ||
            isGameCoreDomain(domain) ||
            isSocialCoreDomain(domain) ||
            shouldProtectMediaTraffic(domain) ||
            shouldProtectBusinessTraffic(domain) ||
            isNovelContentDomain(domain) ||
            isProtectedNovelAppDomain(domain)
    }

    fun getRequestRewriteDirectives(
        context: Context,
        host: String,
        path: String,
        appName: String? = null,
        requestDomain: String? = null,
        requestType: String? = null
    ): RequestRewriteDirectives {
        val normalizedHost = sanitizeDomain(host) ?: return RequestRewriteDirectives()
        val effectiveRequestType = requestType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: inferRequestTypeFromPath(path)
        val matchedRules = buildDomainCandidates(normalizedHost)
            .flatMap { candidate -> getRuleMap(context)[candidate].orEmpty().asSequence() }
            .filter { it.source != RuleSource.UNSUPPORTED && ruleMatches(it, null, appName, normalizedHost, requestDomain, effectiveRequestType) }
        val matchedRegexRules = getRegexRules(context).filter {
            it.source != RuleSource.UNSUPPORTED &&
                ruleMatches(it, null, appName, normalizedHost, requestDomain, effectiveRequestType) &&
                (matchesRegexRule(it, "$normalizedHost$path") || matchesRegexRule(it, normalizedHost))
        }
        val allRules = (matchedRules + matchedRegexRules).distinctBy { it.id }.toList()
        val importantActionableRules = allRules.filter(::isImportantBlockingRule)
        if (allRules.any { it.exceptionRule } && importantActionableRules.isEmpty()) {
            return RequestRewriteDirectives(cosmeticSelectors = getCosmeticSelectors(
                context = context,
                host = normalizedHost,
                path = path,
                appName = appName,
                requestDomain = requestDomain
            ))
        }
        val actionableRules = importantActionableRules.ifEmpty { allRules.filterNot { it.exceptionRule } }
        val removeParams = actionableRules.flatMap { it.removeParams }.toSet()
        val removeParamRegexes = actionableRules.flatMap { it.removeParamRegexes }.toSet()
        val removeRequestHeaders = actionableRules.flatMap { it.removeRequestHeaders }.toSet()
        val setRequestHeaders = actionableRules.flatMap { it.setRequestHeaders }.toSet()
        val replaceRules = actionableRules.flatMap { it.replaceRules }.toSet()
        val cspValue = actionableRules.mapNotNull { it.cspValue }.firstOrNull()
        val redirectResource = actionableRules.mapNotNull { it.redirectResource }.firstOrNull()
        val jsInjectRules = actionableRules.flatMap { it.jsInjectRules }.toSet()
        val cookieRemove = actionableRules.flatMap { it.cookieRemove }.toSet()
        val cookieSet = actionableRules.flatMap { it.cookieSet }.toSet()
        val matchedRuleSummaries = actionableRules.take(5).map(::buildRuleDebugSummary)
        val cosmeticSelectors = getCosmeticSelectors(
            context = context,
            host = normalizedHost,
            path = path,
            appName = appName,
            requestDomain = requestDomain
        )
        return RequestRewriteDirectives(
            removeParams = removeParams,
            removeParamRegexes = removeParamRegexes,
            removeRequestHeaders = removeRequestHeaders,
            setRequestHeaders = setRequestHeaders,
            replaceRules = replaceRules,
            cspValue = cspValue,
            redirectResource = redirectResource,
            jsInjectRules = jsInjectRules,
            cosmeticSelectors = cosmeticSelectors,
            cookieRemove = cookieRemove,
            cookieSet = cookieSet,
            block = importantActionableRules.isNotEmpty(),
            emptyResponse = importantActionableRules.isNotEmpty() && importantActionableRules.any { it.emptyResponse },
            matchedRuleSummaries = matchedRuleSummaries
        )
    }

    private fun buildRuleDebugSummary(rule: BlockRule): String {
        val actions = mutableListOf<String>()
        if (rule.important) actions += "block"
        if (rule.emptyResponse) actions += "empty"
        if (rule.redirectResource != null) actions += "redirect=${rule.redirectResource}"
        if (rule.removeParams.isNotEmpty()) actions += "removeparam"
        if (rule.removeRequestHeaders.isNotEmpty()) actions += "removeheader"
        if (rule.replaceRules.isNotEmpty()) actions += "replace"
        if (!rule.cspValue.isNullOrBlank()) actions += "csp"
        if (rule.jsInjectRules.isNotEmpty()) actions += "scriptlet"
        if (rule.cookieRemove.isNotEmpty() || rule.cookieSet.isNotEmpty()) actions += "cookie"
        if (rule.toDomains.isNotEmpty()) actions += "to"
        val actionText = actions.ifEmpty { listOf("match") }.joinToString("+")
        return "${rule.domain}[$actionText] source=${rule.source} vendor=${rule.vendor}"
    }

    fun hasAdvancedUrlRule(
        context: Context,
        host: String,
        path: String,
        appName: String? = null,
        requestDomain: String? = null,
        destinationPort: Int? = null,
        sourcePort: Int? = null
    ): Boolean {
        val normalizedHost = sanitizeDomain(host) ?: return false
        val normalizedPath = path.lowercase()
        val fullUrl = "$normalizedHost$normalizedPath"
        val matchedHostRules = buildDomainCandidates(normalizedHost)
            .flatMap { candidate -> getRuleMap(context)[candidate].orEmpty().asSequence() }
            .filter {
                it.source != RuleSource.UNSUPPORTED &&
                    ruleMatches(it, null, appName, normalizedHost, requestDomain) &&
                    !it.exceptionRule &&
                    matchesPortScope(it.destinationPorts, destinationPort) &&
                    matchesPortScope(it.sourcePorts, sourcePort)
            }
            .any { rule ->
                !rule.pathPattern.isNullOrBlank() ||
                    rule.urlblock ||
                    rule.removeParams.isNotEmpty() ||
                    rule.removeParamRegexes.isNotEmpty() ||
                    rule.removeRequestHeaders.isNotEmpty() ||
                    rule.setRequestHeaders.isNotEmpty() ||
                    rule.replaceRules.isNotEmpty() ||
                    !rule.cspValue.isNullOrBlank() ||
                    rule.jsInjectRules.isNotEmpty() ||
                    !rule.cosmeticSelector.isNullOrBlank() ||
                    (!rule.keywordPattern.isNullOrBlank() && fullUrl.contains(rule.keywordPattern))
        }
        if (matchedHostRules) return true
        return getRegexRules(context).any { it.source != RuleSource.UNSUPPORTED && !it.exceptionRule && matchesRegexRule(it, fullUrl) }
    }

    fun getCosmeticSelectors(
        context: Context,
        host: String,
        path: String? = null,
        appName: String? = null,
        requestDomain: String? = null
    ): List<String> {
        val normalizedHost = sanitizeDomain(host) ?: return emptyList()
        val normalizedPath = path?.lowercase().orEmpty()
        val fullUrl = "$normalizedHost$normalizedPath"
        val matchedRules = getCosmeticRules(context)
            .asSequence()
            .filter { rule ->
                (rule.domain == COSMETIC_RULE_DOMAIN || normalizedHost == rule.domain || normalizedHost.endsWith(".${rule.domain}")) &&
                    ruleMatches(rule, null, appName, normalizedHost, requestDomain)
            }
            .filter { rule ->
                when {
                    !rule.pathPattern.isNullOrBlank() -> pathMatchesPattern(normalizedPath, rule.pathPattern)
                    !rule.keywordPattern.isNullOrBlank() -> fullUrl.contains(rule.keywordPattern)
                    !rule.regexPattern.isNullOrBlank() -> matchesRegexRule(rule, fullUrl)
                    else -> true
                }
            }
            .toList()
        val excludedSelectors = matchedRules
            .asSequence()
            .filter { it.cosmeticException || it.source == RuleSource.UNSUPPORTED }
            .mapNotNull { it.cosmeticSelector }
            .toSet()
        return matchedRules
            .asSequence()
            .filter { it.source != RuleSource.UNSUPPORTED }
            .filterNot { it.cosmeticException }
            .mapNotNull { it.cosmeticSelector }
            .filterNot(excludedSelectors::contains)
            .distinct()
            .toList()
    }

    fun shouldAggressivelyBlockForNovelApp(context: Context, domain: String, appName: String?, vendor: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (!isNovelAppHint(appName)) return false
        if (isWhitelistedDomain(normalized)) return false
        if (isBypassProtectionDomain(normalized)) return true
        if (hasMatchingRule(context, normalized)) return false
        // 小说内容 API 域名不拦截
        if (VendorConfigData.novelContentApiDomains.contains(normalized) || VendorConfigData.novelContentApiDomains.any { normalized.endsWith(".$it") }) return false
        // 游戏核心服务不拦截（确保游戏正常运行）
        if (isGameCoreDomain(normalized)) return false
        // 社交 APP 核心服务不拦截（确保微信 QQ 正常）
        if (isSocialCoreDomain(normalized)) return false
        val normalizedVendor = normalizeVendorName(vendor)
        val lower = normalized.lowercase()
        // 增强广告域名信号检测 - 扩大关键词范围
        val hasAggressiveSignal = lower.contains("ad") || lower.contains("ads") || lower.contains("banner") || lower.contains("splash") || 
            lower.contains("promo") || lower.contains("tracking") || lower.contains("log") || lower.contains("stat") || 
            lower.contains("analytics") || lower.contains("monitor") || lower.contains("track") || lower.contains("count") ||
            lower.contains("report") || lower.contains("feed") || lower.contains("stream") || lower.contains("api") ||
            lower.contains("cdn") || lower.contains("dsp") || lower.contains("adx") || lower.contains("ssp")
        // 增强小说 APP 广告识别 - 包含广告域名特征立即拦截
        if (hasAggressiveSignal && looksLikeAdDomain(normalized)) return true
        // 广告供应商域名一律拦截（针对小说 APP）
        if (VendorConfigData.novelAggressiveVendorNames.contains(normalizedVendor)) return true
        // 包含 SDK、service、platform 等字样也拦截
        val hasSdkSignal = lower.contains("sdk") || lower.contains("service") || lower.contains("platform") || 
            lower.contains("manager") || lower.contains("network") || lower.contains("server")
        if (hasSdkSignal && hasAggressiveSignal) return true
        if (isProtectedNovelAppDomain(normalized)) return false
        val matchesExactAggressiveDomain = buildDomainCandidates(normalized).any(VendorConfigData.novelAggressiveExactDomains::contains)
        if (matchesExactAggressiveDomain) return true
        // 增强广告域名识别
        return looksLikeAdDomain(normalized) && hasAggressiveNovelAdSignal(normalized)
    }

    fun shouldAggressivelyBlockNovelProtectedUrl(context: Context, host: String, path: String?, appName: String?): Boolean {
        val normalizedHost = sanitizeDomain(host) ?: return false
        if (!isNovelAppHint(appName)) return false
        if (isWhitelistedDomain(normalizedHost)) return false
        if (!isProtectedNovelAppDomain(normalizedHost)) return false
        if (isProtectedByteDanceInfraDomain(normalizedHost)) return false
        val lowerPath = path?.lowercase().orEmpty()
        if (lowerPath.isBlank()) return false
        if (isUrlBlocked(context, normalizedHost, lowerPath, appName)) return true
        return VendorConfigData.fanqieProtectedAdPathKeywords.any { lowerPath.contains(it) } || looksLikeSuspiciousPath(lowerPath)
    }

    fun isSensitiveAuthDomain(domain: String): Boolean {
        return RuleProtectionSupport.isSensitiveAuthDomain(
            domain = domain,
            sanitizeDomain = ::sanitizeDomain,
            isWhitelistedDomain = ::isWhitelistedDomain,
            sensitiveAuthKeywords = sensitiveAuthKeywords,
            keywordMatches = ::keywordMatches
        )
    }

    fun deduplicateRules(context: Context): Int {
        val targetFile = rulesFile(context)
        if (!targetFile.exists()) {
            return deduplicateRulesInMemory(context)
        }
        val tempFile = File(context.filesDir, "$RULES_FILE_NAME.dedup.tmp")
        val seen = HashSet<Long>()
        var keptCount = 0
        var removedCount = 0
        var indexEnabled = true
        try {
            tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                JsonWriter(writer).use { jsonWriter ->
                    targetFile.bufferedReader(Charsets.UTF_8).use { reader ->
                        JsonReader(reader).use { jsonReader ->
                            jsonReader.beginArray()
                            jsonWriter.beginArray()
                            while (jsonReader.hasNext()) {
                                val rule = gson.fromJson<BlockRule>(jsonReader, BlockRule::class.java) ?: continue
                                val normalized = normalizeRuleForSave(rule)
                                val duplicate = if (indexEnabled) {
                                    try {
                                        !seen.add(buildDeduplicationKeyHash(normalized))
                                    } catch (error: OutOfMemoryError) {
                                        seen.clear()
                                        indexEnabled = false
                                        LogRepository.append(context, "Deduplicate rules disabled in-memory index after OOM; kept remaining rules to avoid crash")
                                        false
                                    }
                                } else {
                                    false
                                }
                                if (duplicate) {
                                    removedCount += 1
                                } else {
                                    gson.toJson(normalized, BlockRule::class.java, jsonWriter)
                                    keptCount += 1
                                    if (keptCount % 1_000 == 0) jsonWriter.flush()
                                }
                            }
                            jsonReader.endArray()
                            jsonWriter.endArray()
                        }
                    }
                }
            }
            if (removedCount > 0) {
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_RULES)
                    .putInt(KEY_RULE_COUNT, keptCount)
                    .apply()
                cachedRuleCount = keptCount
                cachedWhitelistHits.clear()
            } else {
                tempFile.delete()
            }
            return removedCount
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            throw e
        } catch (e: OutOfMemoryError) {
            runCatching { tempFile.delete() }
            throw e
        }
    }

    private fun deduplicateRulesInMemory(context: Context): Int {
        val rules = getRules(context)
        val seen = mutableSetOf<String>()
        val deduplicated = ArrayList<BlockRule>(rules.size)
        var removedCount = 0
        rules.forEach { rule ->
            val key = buildDeduplicationKey(rule)
            if (seen.add(key)) {
                deduplicated += rule
            } else {
                removedCount += 1
            }
        }
        if (removedCount > 0) {
            writeRulesFile(context, deduplicated)
            if (deduplicated.size >= LARGE_RULE_CACHE_THRESHOLD) {
                rebuildCachesFromRules(context, deduplicated)
            } else {
                updateRuleCache(deduplicated)
            }
        }
        return removedCount
    }

    private fun buildDeduplicationKeyHash(rule: BlockRule): Long {
        var hash = -0x340d631b7bdddcdbL
        buildDeduplicationKey(rule).forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    private fun buildDeduplicationKey(rule: BlockRule): String {
        return "${rule.domain}|${rule.vendor}|${rule.source}|${rule.keywordPattern}|${rule.regexPattern}|${rule.cosmeticSelector}|${rule.cosmeticException}"
    }

    fun filterNonAds(context: Context): List<BlockRule> {
        val regular = getRules(context).filter { rule ->
            val effectiveVendor = if (rule.vendor == DEFAULT_VENDOR) classifyVendor(context, rule.domain) else normalizeVendorName(rule.vendor)
            effectiveVendor == DEFAULT_VENDOR && !looksLikeAdDomain(rule.domain) && !looksLikeBypassProtectionDomain(rule.domain)
        }
        return regular
    }

    fun getImpactNormalNetworkCandidates(context: Context): List<RemoteRuleRemovalCandidate> {
        return getRules(context)
            .asSequence()
            .mapNotNull { rule ->
                runCatching { explainImpactNormalNetworkCandidate(context, rule) }
                    .onFailure {
                        LogRepository.append(
                            context,
                            "Skip impact-normal-network candidate domain=${rule.domain} reason=${it.message ?: it.javaClass.simpleName}"
                        )
                    }
                    .getOrNull()
            }
            .distinctBy { buildRuleIdentityKey(it.rule) }
            .sortedBy { it.rule.domain }
            .toList()
    }

    fun getRuleInventory(context: Context): RuleInventory {
        cachedRuleInventory?.let { return it }
        val rules = getRules(context)
        val inventory = RuleInventory(
            importedCount = rules.count { it.source == RuleSource.IMPORTED },
            manualCount = rules.count { it.source == RuleSource.MANUAL },
            regexCount = rules.count { !it.regexPattern.isNullOrBlank() },
            cosmeticCount = rules.count { !it.cosmeticSelector.isNullOrBlank() },
            keywordCount = rules.count { !it.keywordPattern.isNullOrBlank() }
        )
        cachedRuleInventory = inventory
        return inventory
    }

    fun classifyVendor(context: Context, domain: String): String {
        val normalized = sanitizeDomain(domain) ?: return DEFAULT_VENDOR
        cachedVendorMap[normalized]?.let { return it }
        if (looksLikeBypassProtectionDomain(normalized)) return BYPASS_PROTECTION_VENDOR.also { cachedVendorMap[normalized] = it }
        readCustomVendorMap(context)[normalized]?.let { return normalizeVendorName(it).also { v -> cachedVendorMap[normalized] = v } }
        findMatchingRule(context, normalized)?.vendor?.let { return normalizeVendorName(it).also { v -> cachedVendorMap[normalized] = v } }
        val result = RuleVendorSupport.classifyVendorByDomainSignals(
            normalizedDomain = normalized,
            defaultVendor = DEFAULT_VENDOR,
            genericAdVendor = GENERIC_AD_VENDOR,
            normalizeVendorName = ::normalizeVendorName,
            vendorPatterns = VendorConfigData.vendorPatterns,
            vendorKeywords = VendorConfigData.vendorKeywords,
            vendorSdkIdentifiers = VendorConfigData.vendorSdkIdentifiers,
            keywordMatches = ::keywordMatches,
            identifierMatches = ::identifierMatches,
            looksLikeAdDomain = ::looksLikeAdDomain
        )
        cachedVendorMap[normalized] = result
        if (cachedVendorMap.size > MAX_CACHED_VENDOR_ENTRIES) {
            val removeCount = cachedVendorMap.size / 4
            val iter = cachedVendorMap.entries.iterator()
            var removed = 0
            while (removed < removeCount && iter.hasNext()) {
                iter.next()
                iter.remove()
                removed++
            }
        }
        return result
    }

    fun classifyVendorSimple(context: Context, domain: String, vararg hints: String?): String? {
        val normalized = sanitizeDomain(domain) ?: return null
        cachedVendorMap[normalized]?.let { return it }
        val byDomain = RuleVendorSupport.classifyVendorByDomainSignals(
            normalizedDomain = normalized,
            defaultVendor = DEFAULT_VENDOR,
            genericAdVendor = GENERIC_AD_VENDOR,
            normalizeVendorName = ::normalizeVendorName,
            vendorPatterns = VendorConfigData.vendorPatterns,
            vendorKeywords = VendorConfigData.vendorKeywords,
            vendorSdkIdentifiers = emptyMap(),
            keywordMatches = ::keywordMatches,
            identifierMatches = ::identifierMatches,
            looksLikeAdDomain = ::looksLikeAdDomain
        )
        if (byDomain != DEFAULT_VENDOR || looksLikeAdDomain(normalized)) {
            cachedVendorMap[normalized] = byDomain
            return byDomain
        }
        val hintMatches = RuleVendorSupport.classifyVendorByHints(
            hints = hints,
            normalizeVendorName = ::normalizeVendorName,
            vendorSdkIdentifiers = VendorConfigData.vendorSdkIdentifiers,
            identifierMatches = ::identifierMatches
        )
        hintMatches?.let { return it.also { v -> cachedVendorMap[normalized] = v } }
        val result = byDomain
        cachedVendorMap[normalized] = result
        return result
    }

    fun classifyVendorFromHints(context: Context, domain: String, vararg hints: String?): String {
        val fromDomain = classifyVendor(context, domain)
        if (fromDomain != DEFAULT_VENDOR && fromDomain != GENERIC_AD_VENDOR) return fromDomain
        return RuleVendorSupport.classifyVendorByHints(
            hints = hints,
            normalizeVendorName = ::normalizeVendorName,
            vendorSdkIdentifiers = VendorConfigData.vendorSdkIdentifiers,
            identifierMatches = ::identifierMatches
        ) ?: fromDomain
    }

    fun reportUnknownVendorIfNeeded(context: Context, vendor: String, domain: String, appName: String? = null) {
        reportUnknownVendorIfNeeded(
            context = context,
            vendor = vendor,
            domain = domain,
            appName = appName,
            signal = SuspiciousSignal.DNS_QUERY,
            confidenceBoost = 0,
            matchedPathHint = null,
            refererDomain = null
        )
    }

    fun reportUnknownVendorIfNeeded(
        context: Context,
        vendor: String,
        domain: String,
        appName: String? = null,
        signal: SuspiciousSignal,
        confidenceBoost: Int = 0,
        matchedPathHint: String? = null,
        refererDomain: String? = null
    ) {
        val normalizedVendor = normalizeVendorName(vendor)
        val normalized = normalizeSuspiciousSampleDomain(domain) ?: return
        if (hasMatchingRule(context, normalized)) {
            LogRepository.append(context, "Skip suspicious sample: has matching rule domain=$normalized app=$appName vendor=$normalizedVendor")
            return
        }
        val httpPathStrongSignal = signal != SuspiciousSignal.DNS_QUERY &&
            (!matchedPathHint.isNullOrBlank() && looksLikeSuspiciousPath(matchedPathHint))
        if (isLowValueSuspiciousSampleDomain(normalized) && !httpPathStrongSignal) {
            LogRepository.append(context, "Skip suspicious sample: low value domain=$normalized app=$appName")
            return
        }
        val normalizedAppName = normalizeSampleAppName(appName)
        if (ShizukuAdControlCatalog.isManagedPromoAppHint(normalizedAppName)) {
            LogRepository.append(context, "Skip suspicious sample: managed promo app domain=$normalized app=$normalizedAppName")
            return
        }
        val novelApp = isNovelAppHint(normalizedAppName)
        val communityApp = isCommunityAppHint(normalizedAppName)
        val hasStrongDomainSignal = looksLikeAdDomain(normalized) ||
            looksLikePushRecommendationAdDomain(normalized) ||
            looksLikeAdSdkInfraDomain(normalized, normalizedVendor) ||
            hasAggressiveNovelAdSignal(normalized)
        val hasRequestSignal = signal != SuspiciousSignal.DNS_QUERY || !matchedPathHint.isNullOrBlank() || !refererDomain.isNullOrBlank()
        val isAggressiveAdApp = isAggressiveAdAppHint(normalizedAppName)
        val samples = readUnknownVendorSamples(context).toMutableMap()
        val previous = samples[normalized]
        val shouldSample = (normalizedVendor == GENERIC_AD_VENDOR && hasStrongDomainSignal) ||
            (normalizedVendor != DEFAULT_VENDOR && looksLikeAdSdkInfraDomain(normalized, normalizedVendor)) ||
            (novelApp && (hasStrongDomainSignal || hasRequestSignal)) ||
            (normalizedVendor == DEFAULT_VENDOR && hasStrongDomainSignal && hasRequestSignal) ||
            (isAggressiveAdApp && hasStrongDomainSignal) ||
            (isAggressiveAdApp && signal != SuspiciousSignal.DNS_QUERY) ||
            (hasStrongDomainSignal && confidenceBoost > 0) ||
            (communityApp && (hasRequestSignal || httpPathStrongSignal)) ||
            (communityApp && (previous?.count ?: 0) >= 2)
        if (!shouldSample) {
            LogRepository.append(context, "Skip suspicious sample: weak signals domain=$normalized app=$normalizedAppName vendor=$normalizedVendor signal=$signal novelApp=$novelApp communityApp=$communityApp hasStrongDomainSignal=$hasStrongDomainSignal hasRequestSignal=$hasRequestSignal isAggressiveAdApp=$isAggressiveAdApp")
            return
        }
        val now = System.currentTimeMillis()
        if (
            previous != null &&
            previous.lastAppName == normalizedAppName &&
            now - previous.lastSampleAt < SUSPICIOUS_SAMPLE_DEBOUNCE_MILLIS
        ) {
            return
        }
        val count = (previous?.count ?: 0) + 1
        val novelHits = (previous?.novelHits ?: 0) + if (novelApp) 1 else 0
        val dnsHits = (previous?.dnsHits ?: 0) + if (signal == SuspiciousSignal.DNS_QUERY) 1 else 0
        val aliasHits = (previous?.aliasHits ?: 0) + if (signal == SuspiciousSignal.DNS_ALIAS) 1 else 0
        val tlsSniHits = (previous?.tlsSniHits ?: 0) + if (signal == SuspiciousSignal.TLS_SNI) 1 else 0
        val httpHits = (previous?.httpHits ?: 0) + if (signal == SuspiciousSignal.HTTP_FLOW) 1 else 0
        val pathHits = (previous?.pathHits ?: 0) + if (!matchedPathHint.isNullOrBlank()) 1 else 0
        val redirectHits = (previous?.redirectHits ?: 0) + if (signal == SuspiciousSignal.HTTP_REDIRECT) 1 else 0
        val appSignalHits = (previous?.appSignalHits ?: 0) + if (isAggressiveAdAppHint(normalizedAppName)) 1 else 0
        val vendorSignalHits = (previous?.vendorSignalHits ?: 0) + if (normalizedVendor != DEFAULT_VENDOR) 1 else 0
        val boost = (previous?.confidenceBoost ?: 0) + confidenceBoost.coerceAtLeast(0)
        samples[normalized] = SuspiciousDomainRecord(
            count = count,
            lastSeenAt = now,
            lastAppName = normalizedAppName,
            lastVendor = normalizedVendor,
            novelHits = novelHits,
            dnsHits = dnsHits,
            aliasHits = aliasHits,
            tlsSniHits = tlsSniHits,
            httpHits = httpHits,
            pathHits = pathHits,
            redirectHits = redirectHits,
            appSignalHits = appSignalHits,
            vendorSignalHits = vendorSignalHits,
            confidenceBoost = boost,
            lastPathHint = matchedPathHint?.take(120) ?: previous?.lastPathHint.orEmpty(),
            refererDomain = normalizeSuspiciousSampleDomain(refererDomain.orEmpty()) ?: previous?.refererDomain.orEmpty(),
            lastSampleAt = now
        )
        TrainingSampleExporter.appendUnknownVendorSample(
            context = context,
            host = normalized,
            path = matchedPathHint,
            protocol = when (signal) {
                SuspiciousSignal.DNS_QUERY, SuspiciousSignal.DNS_ALIAS -> "DNS"
                SuspiciousSignal.TLS_SNI -> "HTTPS"
                SuspiciousSignal.HTTP_FLOW, SuspiciousSignal.HTTP_REDIRECT -> "HTTPS"
            },
            appName = normalizedAppName,
            isHttpdns = normalized.contains("httpdns", ignoreCase = true),
            hitAdToken = hasStrongDomainSignal,
            label = "unlabeled"
        )
        val trimmed = samples.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> {
                    suspiciousDomainConfidenceScore(
                        domain = it.key,
                        vendor = it.value.lastVendor,
                        novelHits = it.value.novelHits,
                        count = it.value.count,
                        appName = it.value.lastAppName,
                        dnsHits = it.value.dnsHits,
                        aliasHits = it.value.aliasHits,
                        tlsSniHits = it.value.tlsSniHits,
                        httpHits = it.value.httpHits,
                        pathHits = it.value.pathHits,
                        redirectHits = it.value.redirectHits,
                        appSignalHits = it.value.appSignalHits,
                        vendorSignalHits = it.value.vendorSignalHits,
                        confidenceBoost = it.value.confidenceBoost,
                        refererDomain = it.value.refererDomain
                    )
                }
                    .thenByDescending { it.value.novelHits }
                    .thenByDescending { it.value.count }
                    .thenByDescending { it.value.lastSeenAt }
                    .thenBy { it.key }
            )
            .take(300)
            .associate { it.key to it.value }
        saveUnknownVendorSamples(context, trimmed, force = count == 1 || count == 5 || count == 20)
        if (count == 1 || count == 5 || count == 20) {
            val scope = if (novelApp) "Novel app suspicious" else "Unknown vendor sample"
            LogRepository.append(context, "$scope x$count: $normalized app=$normalizedAppName vendor=$normalizedVendor")
        }
    }

    fun exportUnknownVendorSamples(context: Context): String {
        val samples = readUnknownVendorSamples(context)
        if (samples.isEmpty()) return "No suspicious ad-like domains sampled\n"
        return buildString {
            append("Suspicious ad-like domains\n")
            append("domain,score,count,novel_hits,dns_hits,alias_hits,tls_sni_hits,http_hits,path_hits,redirect_hits,app_signal_hits,vendor_signal_hits,confidence_boost,last_seen,last_app,last_vendor,last_path_hint,referer_domain\n")
            samples.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> {
                        suspiciousDomainConfidenceScore(
                            domain = it.key,
                            vendor = it.value.lastVendor,
                            novelHits = it.value.novelHits,
                            count = it.value.count,
                            appName = it.value.lastAppName,
                            dnsHits = it.value.dnsHits,
                            aliasHits = it.value.aliasHits,
                            tlsSniHits = it.value.tlsSniHits,
                            httpHits = it.value.httpHits,
                            pathHits = it.value.pathHits,
                            redirectHits = it.value.redirectHits,
                            appSignalHits = it.value.appSignalHits,
                            vendorSignalHits = it.value.vendorSignalHits,
                            confidenceBoost = it.value.confidenceBoost,
                            refererDomain = it.value.refererDomain
                        )
                    }
                        .thenByDescending { it.value.novelHits }
                        .thenByDescending { it.value.count }
                        .thenByDescending { it.value.lastSeenAt }
                        .thenBy { it.key }
                )
                .forEach { entry ->
                    val score = suspiciousDomainConfidenceScore(
                        domain = entry.key,
                        vendor = entry.value.lastVendor,
                        novelHits = entry.value.novelHits,
                        count = entry.value.count,
                        appName = entry.value.lastAppName,
                        dnsHits = entry.value.dnsHits,
                        aliasHits = entry.value.aliasHits,
                        tlsSniHits = entry.value.tlsSniHits,
                        httpHits = entry.value.httpHits,
                        pathHits = entry.value.pathHits,
                        redirectHits = entry.value.redirectHits,
                        appSignalHits = entry.value.appSignalHits,
                        vendorSignalHits = entry.value.vendorSignalHits,
                        confidenceBoost = entry.value.confidenceBoost,
                        refererDomain = entry.value.refererDomain
                    )
                    append(escapeCsvField(entry.key))
                    append(',')
                    append(score)
                    append(',')
                    append(entry.value.count)
                    append(',')
                    append(entry.value.novelHits)
                    append(',')
                    append(entry.value.dnsHits)
                    append(',')
                    append(entry.value.aliasHits)
                    append(',')
                    append(entry.value.tlsSniHits)
                    append(',')
                    append(entry.value.httpHits)
                    append(',')
                    append(entry.value.pathHits)
                    append(',')
                    append(entry.value.redirectHits)
                    append(',')
                    append(entry.value.appSignalHits)
                    append(',')
                    append(entry.value.vendorSignalHits)
                    append(',')
                    append(entry.value.confidenceBoost)
                    append(',')
                    append(escapeCsvField(formatTimestamp(entry.value.lastSeenAt)))
                    append(',')
                    append(escapeCsvField(entry.value.lastAppName.ifBlank { "未知" }))
                    append(',')
                    append(escapeCsvField(entry.value.lastVendor.ifBlank { DEFAULT_VENDOR }))
                    append(',')
                    append(escapeCsvField(entry.value.lastPathHint))
                    append(',')
                    append(escapeCsvField(entry.value.refererDomain))
                    append('\n')
                }
        }
    }

    fun getSuspiciousDomainSamples(context: Context): List<SuspiciousDomainSample> {
        return readUnknownVendorSamples(context)
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> {
                    suspiciousDomainConfidenceScore(
                        domain = it.key,
                        vendor = it.value.lastVendor,
                        novelHits = it.value.novelHits,
                        count = it.value.count,
                        appName = it.value.lastAppName,
                        dnsHits = it.value.dnsHits,
                        aliasHits = it.value.aliasHits,
                        tlsSniHits = it.value.tlsSniHits,
                        httpHits = it.value.httpHits,
                        pathHits = it.value.pathHits,
                        redirectHits = it.value.redirectHits,
                        appSignalHits = it.value.appSignalHits,
                        vendorSignalHits = it.value.vendorSignalHits,
                        confidenceBoost = it.value.confidenceBoost,
                        refererDomain = it.value.refererDomain
                    )
                }
                    .thenByDescending { it.value.novelHits }
                    .thenByDescending { it.value.count }
                    .thenByDescending { it.value.lastSeenAt }
                    .thenBy { it.key }
            )
            .map {
                SuspiciousDomainSample(
                    domain = it.key,
                    count = it.value.count,
                    lastSeenAt = it.value.lastSeenAt,
                    lastAppName = it.value.lastAppName,
                    lastVendor = it.value.lastVendor.ifBlank { DEFAULT_VENDOR },
                    novelHits = it.value.novelHits,
                    dnsHits = it.value.dnsHits,
                    aliasHits = it.value.aliasHits,
                    tlsSniHits = it.value.tlsSniHits,
                    httpHits = it.value.httpHits,
                    pathHits = it.value.pathHits,
                    redirectHits = it.value.redirectHits,
                    appSignalHits = it.value.appSignalHits,
                    vendorSignalHits = it.value.vendorSignalHits,
                    confidenceBoost = it.value.confidenceBoost,
                    lastPathHint = it.value.lastPathHint,
                    refererDomain = it.value.refererDomain
                )
            }
    }

    fun getPendingSuspiciousDomainsForPrompt(context: Context, limit: Int = 30): List<SuspiciousDomainSample> {
        if (limit <= 0) return emptyList()
        return getSuspiciousDomainSamples(context)
            .asSequence()
            .filterNot { hasMatchingRule(context, it.domain) }
            .filter { sample ->
                val score = suspiciousDomainConfidenceScore(
                    domain = sample.domain,
                    vendor = sample.lastVendor,
                    novelHits = sample.novelHits,
                    count = sample.count,
                    appName = sample.lastAppName,
                    dnsHits = sample.dnsHits,
                    aliasHits = sample.aliasHits,
                    tlsSniHits = sample.tlsSniHits,
                    httpHits = sample.httpHits,
                    pathHits = sample.pathHits,
                    redirectHits = sample.redirectHits,
                    appSignalHits = sample.appSignalHits,
                    vendorSignalHits = sample.vendorSignalHits,
                    confidenceBoost = sample.confidenceBoost,
                    refererDomain = sample.refererDomain
                )
                val isCommunityApp = isCommunityAppHint(sample.lastAppName)
                score >= 6 || (isCommunityApp && score >= 4 && sample.count >= 2)
            }
            .take(limit)
            .toList()
    }

    fun suspiciousDomainConfidenceScore(
        domain: String,
        vendor: String,
        novelHits: Int,
        count: Int,
        appName: String? = null,
        dnsHits: Int = 0,
        aliasHits: Int = 0,
        tlsSniHits: Int = 0,
        httpHits: Int = 0,
        pathHits: Int = 0,
        redirectHits: Int = 0,
        appSignalHits: Int = 0,
        vendorSignalHits: Int = 0,
        confidenceBoost: Int = 0,
        refererDomain: String? = null
    ): Int {
        val normalized = sanitizeDomain(domain) ?: return 0
        var score = 0
        val normalizedVendor = normalizeVendorName(vendor)
        if (isWhitelistedDomain(normalized) || isProtectedNovelAppDomain(normalized) || isNovelContentDomain(normalized)) {
            return 0
        }
        if (isLowValueSuspiciousSampleDomain(normalized)) return 0
        if (isBypassProtectionDomain(normalized)) score += 5
        
        // 检查域名是否具有广告特征
        if (looksLikeAdDomain(normalized)) score += 4
        if (looksLikePushRecommendationAdDomain(normalized)) score += 3
        if (hasAggressiveNovelAdSignal(normalized)) score += 3
        if (looksLikeDynamicAliasOrEncryptedDnsAdDomain(normalized)) score += 3
        if (looksLikeHighEntropyAdCandidate(normalized)) score += 2
        
        // 通用广告商识别
        if (normalizedVendor == GENERIC_AD_VENDOR) score += 3
        if (normalizedVendor in VendorConfigData.highConfidenceAdSdkVendors) score += 2
        
        // 访问频率评分
        if (novelHits >= 3) score += 3 else if (novelHits >= 1) score += 2
        if (count >= 8) score += 2 else if (count >= 3) score += 1
        if (dnsHits >= 5) score += 2 else if (dnsHits >= 2) score += 1
        if (aliasHits >= 2) score += 2 else if (aliasHits >= 1) score += 1
        if (aliasHits >= 1 && looksLikeDynamicAliasOrEncryptedDnsAdDomain(normalized)) score += 2
        if (tlsSniHits >= 2) score += 2 else if (tlsSniHits >= 1) score += 1
        if (httpHits >= 2) score += 2 else if (httpHits >= 1) score += 1
        if (pathHits >= 2) score += 2 else if (pathHits >= 1) score += 1
        if (redirectHits >= 1) score += 2
        if (appSignalHits >= 2) score += 1
        if (vendorSignalHits >= 2) score += 1
        
        // 应用类型识别
        if (isNovelAppHint(appName)) score += 1
        if (isAggressiveAdAppHint(appName)) score += 1
        
        // 社区 App 特别处理：评论区广告识别
        val isCommunityApp = appName?.let { 
            it.contains("coolapk", ignoreCase = true) || 
            it.contains("酷安", ignoreCase = true) ||
            it.contains("贴吧", ignoreCase = true) ||
            it.contains("社区", ignoreCase = true) ||
            it.contains("小红书", ignoreCase = true) ||
            it.contains("xiaohongshu", ignoreCase = true) ||
            it.contains("微博", ignoreCase = true) ||
            it.contains("weibo", ignoreCase = true)
        } == true
        
        // 社区 App 的 HTTP/路径命中加分
        if (isCommunityApp && (httpHits >= 1 || pathHits >= 1)) score += 2
        
        // 社区 App 具有广告特征的域名加分
        if (isCommunityApp && looksLikeAdDomain(normalized)) score += 3
        
        if (!refererDomain.isNullOrBlank()) score += 1
        
        // 如果只有 DNS 命中，没有 HTTP/路径命中，降低可信度
        if (httpHits == 0 && pathHits == 0 && redirectHits == 0 && dnsHits <= 1 && aliasHits == 0 && tlsSniHits == 0) {
            score -= 2
        }
        
        // 如果厂商是默认厂商且没有广告特征，降低可信度
        if (normalizedVendor == DEFAULT_VENDOR && !looksLikeAdSdkInfraDomain(normalized, normalizedVendor) && pathHits == 0 && redirectHits == 0) {
            score -= 1
        }
        
        score += confidenceBoost.coerceIn(0, 4)
        return score.coerceAtLeast(0)
    }

    private fun looksLikeDynamicAliasOrEncryptedDnsAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        val normalizedTokens = lower.replace(Regex("[^a-z0-9]"), "")
        val signals = listOf(
            "cnamead", "adalias", "aliasad", "cnamecloakad", "cloakedad", "adcloak",
            "httpdnsad", "dohad", "doqad", "dotad", "dnsqueryad", "encrypteddnsad",
            "adquic", "quicad", "adgateway", "adresolver", "adresolver"
        )
        return signals.any { lower.contains(it) || normalizedTokens.contains(it) }
    }

    private fun looksLikeHighEntropyAdCandidate(domain: String): Boolean {
        val lower = domain.lowercase()
        val labels = lower.split('.', '-', '_').filter { it.length >= 10 }
        if (labels.isEmpty()) return false
        return labels.any { label ->
            val digitCount = label.count(Char::isDigit)
            val uniqueCount = label.toSet().size
            val adHint = label.contains("ad") || label.contains("ads") || label.contains("adx") || label.contains("bid")
            adHint && digitCount >= 2 && uniqueCount >= 8
        }
    }

    fun shouldTreatAsGeneralAdTraffic(
        domain: String,
        vendor: String,
        appName: String? = null,
        sampleCount: Int = 1
    ): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (ShizukuAdControlCatalog.isManagedPromoAppHint(appName)) return true
        if (isWhitelistedDomain(normalized)) return false
        if (isSensitiveAuthDomain(normalized)) return false
        if (shouldProtectMediaTraffic(normalized)) return false
        if (shouldProtectBusinessTraffic(normalized)) return false
        if (isProtectedNovelAppDomain(normalized) && !looksLikeAdDomain(normalized)) return false
        val normalizedVendor = normalizeVendorName(vendor)
        val explicitAdTraffic = isBypassProtectionDomain(normalized) ||
            looksLikeAdDomain(normalized) ||
            looksLikeAdSdkInfraDomain(normalized, normalizedVendor) ||
            looksLikePushRecommendationAdDomain(normalized)
        if (isSocialCoreDomain(normalized) && !explicitAdTraffic) return false
        if (explicitAdTraffic) return true
        if (shouldForcePushRecommendInspection(normalized, appName, normalizedVendor)) return true
        return suspiciousDomainConfidenceScore(
            domain = normalized,
            vendor = normalizedVendor,
            novelHits = if (isNovelAppHint(appName)) 1 else 0,
            count = sampleCount,
            appName = appName
        ) >= 6
    }

    fun isHighConfidenceSuspiciousDomain(
        domain: String,
        vendor: String,
        novelHits: Int,
        count: Int,
        appName: String? = null,
        dnsHits: Int = 0,
        aliasHits: Int = 0,
        tlsSniHits: Int = 0,
        httpHits: Int = 0,
        pathHits: Int = 0,
        redirectHits: Int = 0,
        appSignalHits: Int = 0,
        vendorSignalHits: Int = 0,
        confidenceBoost: Int = 0,
        refererDomain: String? = null
    ): Boolean {
        return RuleSuspiciousSampleSupport.isHighConfidenceSuspiciousDomain(
            domain = domain,
            vendor = vendor,
            novelHits = novelHits,
            count = count,
            appName = appName,
            dnsHits = dnsHits,
            aliasHits = aliasHits,
            tlsSniHits = tlsSniHits,
            httpHits = httpHits,
            pathHits = pathHits,
            redirectHits = redirectHits,
            appSignalHits = appSignalHits,
            vendorSignalHits = vendorSignalHits,
            confidenceBoost = confidenceBoost,
            refererDomain = refererDomain,
            suspiciousDomainConfidenceScore = ::suspiciousDomainConfidenceScore
        )
    }

    fun isNovelAppHint(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        val normalized = text.replace(alphanumericCnRegex, "")
        return VendorConfigData.novelAppIdentifiers.any { identifierMatches(text, normalized, it) }
    }

    fun isCommunityAppHint(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        val normalized = text.replace(alphanumericCnRegex, "")
        val communityIdentifiers = listOf(
            "coolapk", "酷安", "贴吧", "tieba", "小红书", "xiaohongshu", "rednote",
            "微博", "weibo", "社区", "community", "论坛", "forum", "bbs", "post", "comment"
        )
        return communityIdentifiers.any { identifierMatches(text, normalized, it) }
    }

    fun isAggressiveAdAppHint(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        if (ShizukuAdControlCatalog.isManagedPromoAppHint(value)) return true
        val normalized = text.replace(alphanumericCnRegex, "")
        val identifiers = listOf(
            "小说", "阅读", "读书", "番茄", "七猫", "书旗", "掌阅", "起点", "纵横", "酷安",
            "资讯", "新闻", "头条", "浏览器", "短视频", "短剧", "短剧大全", "短剧场", "微短剧", "剧场", "小剧场", "漫画", "漫剧", "听书", "追书", "看书",
            "免费短剧", "免费漫画", "漫画大全", "免费小说", "小说大全", "小说阅读", "阅读器", "书城", "红果",
            "video", "reader", "novel", "comic", "manga", "manhua", "cartoon", "freebook", "bookreader",
            "drama", "duanju", "shortdrama", "short_drama", "minidrama", "mini_drama", "episode", "hongguo", "bookcity", "bookstore", "story", "xiaoshuo", "mianfei", "zhuishu", "kanshu"
        )
        return identifiers.any { identifierMatches(text, normalized, it) }
    }

    private fun looksLikePushRecommendationAdDomain(domain: String): Boolean {
        return RuleAdDomainSupport.looksLikePushRecommendationAdDomain(domain)
    }

    fun shouldForcePushRecommendInspection(domain: String, appName: String?, vendor: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (isWhitelistedDomain(normalized)) return false
        if (isSensitiveAuthDomain(normalized)) return false
        if (isGameCoreDomain(normalized)) return false
        if (isSocialCoreDomain(normalized) && !isCommunityAppHint(appName)) return false
        if (shouldProtectMediaTraffic(normalized) || shouldProtectBusinessTraffic(normalized)) return false
        val normalizedVendor = normalizeVendorName(vendor)
        if (looksLikePushRecommendationAdDomain(normalized)) return true
        if (!isAggressiveAdAppHint(appName)) return false
        if (looksLikeAdDomain(normalized)) return true
        return normalizedVendor in VendorConfigData.highConfidenceAdSdkVendors
    }

    fun isNovelVendor(vendor: String): Boolean = VendorConfigData.novelVendorNames.contains(normalizeVendorName(vendor))

    fun looksLikeAdSdkInfraDomain(domain: String, vendor: String = DEFAULT_VENDOR): Boolean {
        return RuleAdDomainSupport.looksLikeAdSdkInfraDomain(
            domain = domain,
            vendor = vendor,
            defaultVendor = DEFAULT_VENDOR,
            sanitizeDomain = ::sanitizeDomain,
            normalizeVendorName = ::normalizeVendorName,
            highConfidenceAdSdkDomains = VendorConfigData.highConfidenceAdSdkDomains,
            highConfidenceAdSdkVendors = VendorConfigData.highConfidenceAdSdkVendors
        )
    }

    fun isNovelContentDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        return novelContentApiTrie.contains(normalized)
    }

    fun shouldForceNovelQuicBlock(domain: String, appName: String?, vendor: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (!isNovelAppHint(appName)) return false
        if (isWhitelistedDomain(normalized)) return false
        if (isBypassProtectionDomain(normalized)) return true
        if (isSensitiveAuthDomain(normalized)) return false
        if (isNovelContentDomain(normalized)) return false
        if (isGameCoreDomain(normalized) || isSocialCoreDomain(normalized)) return false
        if (isProtectedNovelAppDomain(normalized)) return false
        if (hasMatchingRulePlaceholder(normalized)) return true
        val normalizedVendor = normalizeVendorName(vendor)
        if (VendorConfigData.novelAggressiveVendorNames.contains(normalizedVendor)) return true
        if (looksLikeAdDomain(normalized) && hasAggressiveNovelAdSignal(normalized)) return true
        val lower = normalized.lowercase()
        val strongNovelQuicSignals = listOf(
            "ad", "ads", "adx", "dsp", "ssp", "rtb", "bid", "bidding", "promo", "promotion",
            "splash", "reward", "excitation", "inspire", "offer", "offers", "preload", "launch",
            "startup", "tracking", "tracker", "analytics", "stat", "report", "monitor", "log",
            "welfare", "task", "coin", "bonus", "benefit", "offerwall", "monetize", "monetization"
        )
        return strongNovelQuicSignals.any { signal -> keywordMatches(lower, lower.replace(alphanumericRegex, ""), signal) }
    }

    fun isProtectedNovelAppDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val lower = normalized.lowercase()
        // 广告特征子域名不保护
        val adSubdomainPatterns = listOf(
            "ad", "ads", "adserver", "adtrack", "adlog", "adx", "adv", "banner", "splash",
            "promotion", "promo", "marketing", "track", "tracking", "log", "logger", "stat", "stats", "analytics"
        )
        if (adSubdomainPatterns.any { lower.startsWith("$it.") || lower.startsWith("$it-") || lower == it }) return false
        val firstLabel = lower.substringBefore('.')
        val aggressivePrefixPatterns = listOf(
            Regex("^ads?\\d+[-_].*"),
            Regex("^adx\\d*[-_].*"),
            Regex("^ad[-_]?.*"),
            Regex("^feed[-_]?ad.*"),
            Regex("^reward[-_]?.*"),
            Regex("^splash[-_]?.*"),
            Regex("^launch[-_]?ad.*"),
            Regex("^open[-_]?screen.*")
        )
        if (aggressivePrefixPatterns.any { it.containsMatchIn(firstLabel) }) return false
        // 具有强烈广告信号的域名不保护
        if (RuleProtectionSupport.hasAggressiveNovelAdSignal(normalized)) return false
        // 移除 looksLikeAdDomain 调用，避免与 isLowValueSuspiciousSampleDomain 形成循环
        return RuleProtectionSupport.matchesExactOrSubdomain(
            normalized,
            buildDomainCandidates(normalized).toSet().intersect(VendorConfigData.novelAppProtectedSuffixes.toSet())
        )
    }

    fun hasMatchingRule(context: Context, domain: String): Boolean {
        return findMatchingRule(context, domain) != null
    }

    private fun hasMatchingRulePlaceholder(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        return buildDomainCandidates(normalized).any(VendorConfigData.novelAggressiveExactDomains::contains)
    }

    private fun keywordMatches(domain: String, normalizedTokens: String, keyword: String): Boolean {
        return RuleAdDomainSupport.keywordMatches(domain, normalizedTokens, keyword)
    }

    private fun identifierMatches(text: String, normalizedTokens: String, identifier: String): Boolean {
        return RuleVendorSupport.identifierMatches(text, normalizedTokens, identifier)
    }

    fun availableVendors(context: Context): List<String> {
        return (VendorConfigData.vendorPatterns.keys + readCustomVendorMap(context).values + getRules(context).map { it.vendor })
            .map(::normalizeVendorName)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    fun updateRuleVendor(context: Context, id: String, vendor: String) {
        val targetVendor = normalizeVendorName(vendor.trim().ifBlank { DEFAULT_VENDOR })
        val updated = getRules(context).map { rule ->
            if (rule.id == id) copyBlockRule(rule, vendor = targetVendor) else rule
        }
        val targetRule = updated.firstOrNull { it.id == id } ?: return
        val customMap = readCustomVendorMap(context).toMutableMap()
        customMap[targetRule.domain] = targetVendor
        save(context, updated)
        saveCustomVendorMap(context, customMap)
    }

    fun analyzeImportContent(context: Context, content: String): RuleAnalysisReport {
        val existingRules = getRules(context)
        val state = createImportAnalysisState(existingRules)
        state.blankOrCommentLines = countBlankOrCommentImportLines(content)
        forEachAnalyzableImportLine(content) { rawLine, line, lineContext ->
            analyzeImportContentLine(context, rawLine, line, lineContext, state)
        }

        return RuleAnalysisReport(
            totalLines = content.lineSequence().count(),
            existingRules = existingRules.size,
            estimatedFinalRules = state.simulatedDomains.size,
            blankOrCommentLines = state.blankOrCommentLines,
            safeBlockedRules = state.safeBlockedRules,
            safeExceptionRules = state.safeExceptionRules,
            duplicateExistingRules = state.duplicateExistingRules,
            duplicateWithinFileRules = state.duplicateWithinFileRules,
            unsupportedModifierRules = state.unsupportedModifierRules,
            cosmeticRules = state.cosmeticRules,
            regexRules = state.regexRules,
            invalidRules = state.invalidRules,
            exceptionRemovalEstimate = state.exceptionRemovalEstimate,
            vendorSummary = state.vendorCount.entries
                .sortedByDescending { it.value }
                .take(16)
                .map { VendorSummary(it.key, it.value) },
            whitelistConflictRules = state.whitelistConflictLines.distinct().size,
            sampleWhitelistConflictLines = state.whitelistConflictLines.distinct().take(10),
            sampleUnsupportedLines = state.unsupportedLines.distinct().take(10),
            sampleInvalidLines = state.invalidLines.distinct().take(10)
        )
    }

    private fun countBlankOrCommentImportLines(content: String): Int {
        return content.lineSequence().count { rawLine ->
            val trimmed = rawLine.trim()
            trimmed.isBlank() ||
                trimmed.startsWith("#") ||
                trimmed.startsWith("!") ||
                trimmed.startsWith("#pkg=", ignoreCase = true)
        }
    }

    private fun forEachAnalyzableImportLine(
        content: String,
        block: (rawLine: String, line: String, lineContext: RuleParsingSupport.LineContext) -> Unit
    ) {
        var lineContext = RuleParsingSupport.LineContext()
        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                lineContext = RuleParsingSupport.parseRuleLineContext(trimmed)
                return@forEach
            }
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                return@forEach
            }
            block(rawLine, trimmed, lineContext)
        }
    }

    private fun createImportAnalysisState(existingRules: List<BlockRule>): ImportAnalysisState {
        val existingDomains = existingRules.map(BlockRule::domain).toMutableSet()
        return ImportAnalysisState(
            existingRuleKeys = existingRules.mapTo(linkedSetOf()) { buildRuleIdentityKey(it) },
            simulatedDomains = existingDomains.toMutableSet()
        )
    }

    private fun analyzeImportContentLine(
        context: Context,
        rawLine: String,
        line: String,
        lineContext: RuleParsingSupport.LineContext,
        state: ImportAnalysisState
    ) {
        val parsedRules = parseRuleLine(rawLine, lineContext)
        if (parsedRules.isEmpty()) {
            applyImportInvalidLineAnalysis(line, state)
            return
        }
        parsedRules.forEach { parsedRule ->
            applyImportParsedRuleAnalysis(context, parsedRule, line, state)
        }
    }

    private fun applyImportInvalidLineAnalysis(
        line: String,
        state: ImportAnalysisState
    ) {
        val invalidAnalysis = analyzeInvalidImportRule(line)
        val (unsupportedDelta, invalidDelta) = applyInvalidRuleAnalysis(
            result = invalidAnalysis,
            unsupportedLines = state.unsupportedLines,
            invalidLines = state.invalidLines
        )
        state.unsupportedModifierRules += unsupportedDelta
        state.invalidRules += invalidDelta
    }

    private fun applyImportParsedRuleAnalysis(
        context: Context,
        parsedRule: ParsedRule,
        line: String,
        state: ImportAnalysisState
    ) {
        val preAnalysis = analyzeParsedRulePreStep(parsedRule, line)
        val (regexDelta, cosmeticDelta) = applyParsedRulePreAnalysis(
            result = preAnalysis,
            whitelistConflictLines = state.whitelistConflictLines
        )
        state.regexRules += regexDelta
        state.cosmeticRules += cosmeticDelta
        val ruleKey = buildParsedRuleIdentityKey(parsedRule)
        val analysisStep = analyzeParsedRuleStep(
            context = context,
            parsedRule = parsedRule,
            ruleKey = ruleKey,
            existingRuleKeys = state.existingRuleKeys,
            seenBlocked = state.seenBlocked,
            seenExceptions = state.seenExceptions,
            simulatedDomains = state.simulatedDomains
        )
        state.duplicateExistingRules += analysisStep.duplicateExistingDelta
        state.duplicateWithinFileRules += analysisStep.duplicateWithinFileDelta
        state.safeBlockedRules += analysisStep.safeBlockedDelta
        state.safeExceptionRules += analysisStep.safeExceptionDelta
        state.exceptionRemovalEstimate += analysisStep.exceptionRemovalDelta
        analysisStep.vendor?.let { incrementVendorCount(state.vendorCount, it) }
    }

    private fun parseImportLines(content: String): ParsedRules {
        return parseImportLinesStreaming(content.lineSequence())
    }

    private fun parseImportLinesStreaming(
        lines: Sequence<String>,
        onProgress: ((lineCount: Int, parsedRuleCount: Int) -> Unit)? = null
    ): ParsedRules {
        val parsedRules = ParsedRuleBuckets()
        var lineContext = RuleParsingSupport.LineContext()
        var lineCount = 0
        var parsedRuleCount = 0
        var lastProgressAt = 0L
        lines.forEach { rawLine ->
            lineCount += 1
            RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach { fragment ->
                val trimmed = fragment.trim()
                if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                    lineContext = RuleParsingSupport.parseRuleLineContext(trimmed)
                    return@forEach
                }
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    return@forEach
                }
                parseFastImportRule(trimmed, lineContext)?.let { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                    parsedRuleCount += 1
                    return@forEach
                }
                val parsedLineRules = parseRuleLine(fragment, lineContext)
                parsedLineRules.forEach { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                    parsedRuleCount += 1
                }
                if (parsedLineRules.isEmpty()) {
                    parseUnsupportedImportRule(fragment, lineContext)?.let { parsedRule ->
                        mergeParsedImportRule(parsedRules, parsedRule)
                        parsedRuleCount += 1
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (onProgress != null && (lineCount % 2000 == 0 || now - lastProgressAt >= 750L)) {
                lastProgressAt = now
                onProgress.invoke(lineCount, parsedRuleCount)
            }
        }
        onProgress?.invoke(lineCount, parsedRuleCount)
        return ParsedRules(
            blockedRules = parsedRules.blocked.values.asListView(),
            exceptionRules = parsedRules.exceptions.values.asListView(),
            badfilterRules = parsedRules.badfilters.values.asListView()
        )
    }

    private fun <T> Collection<T>.asListView(): List<T> {
        return if (this is List<T>) this else ArrayList(this)
    }

    private fun parseImportLinesStreamingBatched(
        lines: Sequence<String>,
        batchSize: Int = IMPORT_PARSE_BATCH_SIZE,
        onProgress: ((lineCount: Int, parsedRuleCount: Int) -> Unit)? = null,
        onBatch: (ParsedRules) -> Unit
    ) {
        var parsedRules = ParsedRuleBuckets()
        var lineContext = RuleParsingSupport.LineContext()
        var lineCount = 0
        var parsedRuleCount = 0
        var pendingRuleCount = 0
        var lastProgressAt = 0L

        fun flushBatch() {
            if (pendingRuleCount <= 0) return
            onBatch(
                ParsedRules(
                    blockedRules = parsedRules.blocked.values.asListView(),
                    exceptionRules = parsedRules.exceptions.values.asListView(),
                    badfilterRules = parsedRules.badfilters.values.asListView()
                )
            )
            parsedRules = ParsedRuleBuckets()
            pendingRuleCount = 0
        }

        lines.forEach { rawLine ->
            lineCount += 1
            RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach { fragment ->
                val trimmed = fragment.trim()
                if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                    lineContext = RuleParsingSupport.parseRuleLineContext(trimmed)
                    return@forEach
                }
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    return@forEach
                }
                parseFastImportRule(trimmed, lineContext)?.let { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                    parsedRuleCount += 1
                    pendingRuleCount += 1
                    if (pendingRuleCount >= batchSize) flushBatch()
                    return@forEach
                }
                val parsedLineRules = parseRuleLine(fragment, lineContext)
                parsedLineRules.forEach { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                    parsedRuleCount += 1
                    pendingRuleCount += 1
                }
                if (parsedLineRules.isEmpty()) {
                    parseUnsupportedImportRule(fragment, lineContext)?.let { parsedRule ->
                        mergeParsedImportRule(parsedRules, parsedRule)
                        parsedRuleCount += 1
                        pendingRuleCount += 1
                    }
                }
                if (pendingRuleCount >= batchSize) flushBatch()
            }
            val now = System.currentTimeMillis()
            if (onProgress != null && (lineCount % 2000 == 0 || now - lastProgressAt >= 750L)) {
                lastProgressAt = now
                onProgress.invoke(lineCount, parsedRuleCount)
            }
        }
        flushBatch()
        onProgress?.invoke(lineCount, parsedRuleCount)
    }

    private fun parseUnsupportedImportRule(rawLine: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val line = RuleParsingSupport.stripInlineRuleComment(normalizeMessyRuleLine(rawLine)).trim()
        if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) return null
        val normalizedLine = RuleParsingSupport.stripYamlListPrefix(RuleParsingSupport.unwrapRuleWrapper(line)).trim()
        if (normalizedLine.isBlank()) return null
        val isException = normalizedLine.startsWith("@@")
        val working = if (isException) normalizedLine.removePrefix("@@") else normalizedLine
        val domain = extractDomainCandidate(working)
            ?.first
            ?.let(::parseDomainsFromPattern)
            ?.firstOrNull()
            ?: extractLooseDomainForUnsupportedRule(working)
            ?: UNSUPPORTED_RULE_DOMAIN
        return ParsedRule(
            domain = domain,
            isException = isException,
            cosmeticSelector = normalizedLine.take(500),
            isUnsupported = true,
            vendorHints = lineContext.vendorHints + "暂不支持规则"
        )
    }

    private fun extractLooseDomainForUnsupportedRule(line: String): String? {
        val match = Regex("""([a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+)""").find(line) ?: return null
        return sanitizeDomain(match.value)
    }

    // P0.4 新增：支持 Sequence 流式解析（避免大文件一次性加载）
    private fun parseImportLines(lines: Sequence<String>): ParsedRules {
        val parsedRules = ParsedRuleBuckets()
        val expandedLines = RuleParsingSupport.expandIndentedYamlPayloadBlocks(lines.toList())
        
        // 顺序处理规则解析（保证结果可预测，避免并行处理的线程安全问题）
        expandedLines.forEach { rawLine ->
            RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach { fragment ->
                val trimmed = fragment.trim()
                if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                    return@forEach
                }
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    return@forEach
                }
                parseFastImportRule(trimmed, RuleParsingSupport.LineContext())?.let { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                    return@forEach
                }
                parseRuleLine(fragment, RuleParsingSupport.LineContext()).forEach { parsedRule ->
                    mergeParsedImportRule(parsedRules, parsedRule)
                }
            }
        }

        return ParsedRules(
            blockedRules = parsedRules.blocked.values.asListView(),
            exceptionRules = parsedRules.exceptions.values.asListView(),
            badfilterRules = parsedRules.badfilters.values.asListView()
        )
    }

    private fun mergeParsedImportRule(
        buckets: ParsedRuleBuckets,
        parsedRule: ParsedRule
    ) {
        when {
            parsedRule.isBadfilter -> mergeParsedRuleInto(buckets.badfilters, parsedRule)
            parsedRule.isException -> mergeParsedRuleInto(buckets.exceptions, parsedRule)
            else -> mergeParsedRuleInto(buckets.blocked, parsedRule)
        }
    }

    private fun parseFastImportRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val normalizedLine = RuleParsingSupport.stripYamlListPrefix(RuleParsingSupport.unwrapRuleWrapper(line)).trim()
        if (normalizedLine.isBlank()) return null
        parseFastAdblockDomainRule(normalizedLine, lineContext)?.let { return it }
        parseFastHostsDomainRule(normalizedLine, lineContext)?.let { return it }
        parseFastDnsRedirectRule(normalizedLine, lineContext)?.let { return it }
        parseFastProviderDomainRule(normalizedLine, lineContext)?.let { return it }
        parseFastWildcardDomainRule(normalizedLine, lineContext)?.let { return it }
        sanitizeDomain(normalizeDomainToken(normalizedLine))?.let { domain ->
            return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
        }
        return null
    }

    private fun parseFastAdblockDomainRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val isException = line.startsWith("@@||")
        val prefix = if (isException) "@@||" else "||"
        if (!line.startsWith(prefix)) return null
        val body = line.removePrefix(prefix)
        val domainPart = body.substringBefore('^').substringBefore('/').substringBefore('$').trim()
        val domain = sanitizeDomain(normalizeDomainToken(domainPart)) ?: return null
        val modifierPart = line.substringAfter('$', missingDelimiterValue = "")
        if (modifierPart.isNotBlank()) {
            val hasComplexModifier = modifierPart.split(',').any { token ->
                val name = token.trim().removePrefix("~").substringBefore('=')
                name.isNotBlank() && name !in SIMPLE_MODIFIER_NAMES
            }
            if (hasComplexModifier) return null
        }
        return ParsedRule(
            domain = domain,
            isException = isException,
            important = modifierPart.split(',').any { it.equals("important", ignoreCase = true) },
            isBadfilter = modifierPart.split(',').any { it.equals("badfilter", ignoreCase = true) },
            vendorHints = lineContext.vendorHints
        )
    }

    private fun parseFastHostsDomainRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val parts = line.split(splitWhitespaceRegex).filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val ip = parts[0]
        if (ip != "0.0.0.0" && ip != "127.0.0.1" && ip != "::" && ip != "::1") return null
        val domain = sanitizeDomain(normalizeDomainToken(parts[1])) ?: return null
        return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
    }

    private fun parseFastDnsRedirectRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val trimmed = line.trim()
        val prefixes = listOf("address=/", "server=/", "local=/", "bogus-nxdomain=")
        val prefix = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return null
        val value = if (prefix.endsWith('/')) {
            trimmed.substring(prefix.length).substringBefore('/').trim()
        } else {
            trimmed.substringAfter('=').trim()
        }
        val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
        return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
    }

    private fun parseFastProviderDomainRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val trimmed = line.trim().removeSurrounding("\"").removeSurrounding("'")
        val value = when {
            trimmed.startsWith("DOMAIN-SUFFIX,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("HOST-SUFFIX,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("DOMAIN,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("HOST,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("DOMAIN-KEYWORD,", ignoreCase = true) -> return null
            trimmed.startsWith("HOST-KEYWORD,", ignoreCase = true) -> return null
            trimmed.startsWith("URL-REGEX,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("domain:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("domainSuffix:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("domain-full:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("full:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("suffix:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.startsWith("geosite:", ignoreCase = true) -> return null
            trimmed.startsWith("host-suffix,", ignoreCase = true) -> trimmed.substringAfter(',')
            trimmed.startsWith("host,", ignoreCase = true) -> trimmed.substringAfter(',')
            else -> return null
        }.substringBefore(',').trim()
        val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
        return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
    }

    private fun parseFastWildcardDomainRule(line: String, lineContext: RuleParsingSupport.LineContext): ParsedRule? {
        val trimmed = line.trim().removeSurrounding("\"").removeSurrounding("'")
        val value = when {
            trimmed.startsWith("+.") -> trimmed.substring(2)
            trimmed.startsWith("*.") -> trimmed.substring(2)
            trimmed.startsWith(".") -> trimmed.substring(1)
            trimmed.startsWith("||") -> trimmed.removePrefix("||").substringBefore('^').substringBefore('/').substringBefore('$')
            else -> return null
        }.trim()
        val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
        return ParsedRule(domain = domain, isException = false, vendorHints = lineContext.vendorHints)
    }

    fun parseManualInput(rawInput: String): List<String> {
        return collectManualInputDomains(rawInput).toList()
    }

    fun findWhitelistConflictsInManualInput(rawInput: String): List<String> {
        return collectManualInputDomains(rawInput)
            .filter(::isWhitelistedDomain)
            .distinct()
    }

    fun removeWhitelistConflictLines(content: String): String {
        val sanitizedLines = mutableListOf<String>()
        forEachExpandedRuleFragment(content, includeContextFragments = true) { fragment, lineContext ->
            val trimmed = fragment.trim()
            if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                sanitizedLines += fragment
                return@forEachExpandedRuleFragment
            }
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                sanitizedLines += fragment
                return@forEachExpandedRuleFragment
            }
            if (!hasWhitelistConflict(fragment, lineContext)) {
                sanitizedLines += fragment
            }
        }
        return sanitizedLines.joinToString("\n")
    }

    private fun collectManualInputDomains(rawInput: String): LinkedHashSet<String> {
        val blocked = linkedSetOf<String>()
        forEachExpandedRuleFragment(rawInput) { fragment, lineContext ->
            val trimmed = fragment.trim()
            if (trimmed.isBlank()) return@forEachExpandedRuleFragment
            val parsedRules = parseRuleLine(trimmed, lineContext)
            if (parsedRules.isNotEmpty()) {
                parsedRules.filterNot { it.isException || it.isBadfilter }.forEach { blocked += it.domain }
                return@forEachExpandedRuleFragment
            }
            trimmed.split(splitWhitespaceRegex)
                .mapNotNull { sanitizeDomain(normalizeDomainToken(it)) }
                .forEach { blocked += it }
        }
        return blocked
    }

    private fun hasWhitelistConflict(
        fragment: String,
        lineContext: RuleParsingSupport.LineContext
    ): Boolean {
        return parseRuleLine(fragment, lineContext).any { parsedRule ->
            !parsedRule.isException && !parsedRule.isBadfilter && isWhitelistedDomain(parsedRule.domain)
        }
    }

    private fun forEachExpandedRuleFragment(
        content: String,
        includeContextFragments: Boolean = false,
        block: (fragment: String, lineContext: RuleParsingSupport.LineContext) -> Unit
    ) {
        var lineContext = RuleParsingSupport.LineContext()
        RuleParsingSupport.expandIndentedYamlPayloadBlocks(content.lineSequence().toList()).forEach { rawLine ->
            RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach { fragment ->
                val trimmed = fragment.trim()
                if (trimmed.startsWith("#pkg=", ignoreCase = true)) {
                    lineContext = RuleParsingSupport.parseRuleLineContext(trimmed)
                    if (includeContextFragments) {
                        block(fragment, lineContext)
                    }
                    return@forEach
                }
                block(fragment, lineContext)
            }
        }
    }

    private fun parseStandardAdBlockRule(line: String, lineContext: RuleParsingSupport.LineContext): List<ParsedRule> {
        val isException = line.startsWith("@@")
        val working = if (isException) line.removePrefix("@@") else line

        val candidate = extractDomainCandidate(working) ?: return emptyList()
        val (patternPart, modifierPart) = candidate
        val modifierInfo = parseModifierInfo(modifierPart)
        if (modifierInfo.invalid || modifierInfo.unsupportedModifiers.isNotEmpty()) return emptyList()
        if (!canSafelyApplyModifierContext(patternPart, modifierInfo)) return emptyList()

        val domains = parseDomainsFromPattern(patternPart)
        val keywordPattern = if (patternPart.contains('*')) {
            extractKeywordPattern(patternPart)
        } else {
            null
        }
        val pathPattern = extractPathPattern(patternPart) ?: modifierInfo.pathPattern

        return domains.map { domain ->
            ParsedRule(
                domain = domain,
                isException = isException,
                isBadfilter = modifierInfo.badfilter,
                dnsTypes = modifierInfo.dnsTypes,
                excludedDnsTypes = modifierInfo.excludedDnsTypes,
                thirdParty = modifierInfo.thirdParty,
                firstParty = modifierInfo.firstParty,
                important = modifierInfo.important,
                redirect = modifierInfo.redirect,
                domainConstraints = modifierInfo.domainConstraints,
                excludedDomainConstraints = modifierInfo.excludedDomainConstraints,
                denyallow = modifierInfo.denyallow,
                urlblock = modifierInfo.urlblock,
                requestTypes = modifierInfo.requestTypes,
                appPackages = modifierInfo.appPackages,
                destinationPorts = modifierInfo.destinationPorts,
                sourcePorts = modifierInfo.sourcePorts,
                keywordPattern = keywordPattern,
                pathPattern = pathPattern,
                ipCidr = null,
                regexPattern = null,
                cosmeticSelector = null,
                removeParams = modifierInfo.removeParams,
                removeParamRegexes = modifierInfo.removeParamRegexes,
                removeRequestHeaders = modifierInfo.removeRequestHeaders,
                setRequestHeaders = modifierInfo.setRequestHeaders,
                replaceRules = modifierInfo.replaceRules,
                cspValue = modifierInfo.cspValue,
                redirectResource = modifierInfo.redirectResource,
                jsInjectRules = modifierInfo.jsinject?.let { setOf(it) }.orEmpty(),
                cookieRemove = modifierInfo.cookieRemove,
                cookieSet = modifierInfo.cookieSet,
                toDomains = modifierInfo.toDomains,
                cname = modifierInfo.cname,
                emptyResponse = modifierInfo.emptyResponse,
                vendorHints = lineContext.vendorHints
            )
        }
    }

    private fun parseRuleLine(rawLine: String, lineContext: RuleParsingSupport.LineContext = RuleParsingSupport.LineContext()): List<ParsedRule> {
        val line = RuleParsingSupport.stripInlineRuleComment(normalizeMessyRuleLine(rawLine))
        if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) return emptyList()
        val normalizedLine = RuleParsingSupport.stripYamlListPrefix(RuleParsingSupport.unwrapRuleWrapper(line))

        if (normalizedLine.startsWith("||") || normalizedLine.startsWith("@@||")) {
            return parseStandardAdBlockRule(line, lineContext)
        }
        
        parseCompositeRule(normalizedLine, lineContext)?.let { return it }
        parseCosmeticRule(normalizedLine)?.let { return listOf(it.withVendorHints(lineContext.vendorHints)) }
        parseRegexRule(normalizedLine)?.let { return listOf(it.withVendorHints(lineContext.vendorHints)) }
        parseInlinePayloadRule(normalizedLine, lineContext)?.let { return it }
        parseClashRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseSurgeWildcardDomain(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseLoonKeywordRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseLoonUrlRegex(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseAbpDomainRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseShadowrocketRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseSurgeUrlKeyword(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseHostsRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseDnsmasqRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseSmartdnsRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseOpenwrtRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseAdguardRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        parseEasyclashRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加端口通配符规则解析（如 *:443$network）
        parsePortWildcardRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加 V2Ray/Xray 格式规则解析（domain:xxx, domainSuffix:xxx, ip:xxx）
        parseV2RayRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加 Shadowrocket 格式规则解析（host-suffix, host-keyword, ip-cidr）
        parseShadowrocketFormatRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加 Quantumult X 格式规则解析（host, ip-cidr, ip6-cidr）
        parseQuantumultXRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加点前缀域名解析（.example.com）
        parseDotPrefixDomainRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }
        // 添加 IPv6 Hosts 规则解析
        parseIPv6HostsRule(normalizedLine)?.let { return it.withVendorHints(lineContext.vendorHints) }

        val trimmedLine = normalizedLine.trim()
        if (trimmedLine.startsWith("+.") && trimmedLine.substring(2).isNotBlank()) {
            val suffixDomain = sanitizeDomain(trimmedLine.substring(2))
            if (suffixDomain != null) return listOf(ParsedRule(domain = suffixDomain, isException = false, vendorHints = lineContext.vendorHints))
        }

        val isException = line.startsWith("@@")
        val working = if (isException) line.removePrefix("@@") else line

        val candidate = extractDomainCandidate(working) ?: return emptyList()
        val (patternPart, modifierPart) = candidate
        val modifierInfo = parseModifierInfo(modifierPart)
        if (modifierInfo.invalid || modifierInfo.unsupportedModifiers.isNotEmpty()) return emptyList()
        if (!canSafelyApplyModifierContext(patternPart, modifierInfo)) return emptyList()

        val domains = parseDomainsFromPattern(patternPart)
        val keywordPattern = if (patternPart.contains('*')) {
            extractKeywordPattern(patternPart)
        } else {
            null
        }
        val pathPattern = extractPathPattern(patternPart) ?: modifierInfo.pathPattern

        return domains.map { domain ->
            ParsedRule(
                domain = domain,
                isException = isException,
                isBadfilter = modifierInfo.badfilter,
                dnsTypes = modifierInfo.dnsTypes,
                excludedDnsTypes = modifierInfo.excludedDnsTypes,
                thirdParty = modifierInfo.thirdParty,
                firstParty = modifierInfo.firstParty,
                important = modifierInfo.important,
                redirect = modifierInfo.redirect,
                domainConstraints = modifierInfo.domainConstraints,
                excludedDomainConstraints = modifierInfo.excludedDomainConstraints,
                denyallow = modifierInfo.denyallow,
                urlblock = modifierInfo.urlblock,
                requestTypes = modifierInfo.requestTypes,
                appPackages = modifierInfo.appPackages,
                destinationPorts = modifierInfo.destinationPorts,
                sourcePorts = modifierInfo.sourcePorts,
                keywordPattern = keywordPattern,
                pathPattern = pathPattern,
                ipCidr = null,
                regexPattern = null,
                cosmeticSelector = null,
                removeParams = modifierInfo.removeParams,
                removeParamRegexes = modifierInfo.removeParamRegexes,
                removeRequestHeaders = modifierInfo.removeRequestHeaders,
                setRequestHeaders = modifierInfo.setRequestHeaders,
                replaceRules = modifierInfo.replaceRules,
                cspValue = modifierInfo.cspValue,
                redirectResource = modifierInfo.redirectResource,
                jsInjectRules = modifierInfo.jsinject?.let { setOf(it) }.orEmpty(),
                cookieRemove = modifierInfo.cookieRemove,
                cookieSet = modifierInfo.cookieSet,
                toDomains = modifierInfo.toDomains,
                cname = modifierInfo.cname,
                emptyResponse = modifierInfo.emptyResponse,
                vendorHints = lineContext.vendorHints
            )
        }
    }

    private fun ParsedRule.withVendorHints(vendorHints: Set<String>): ParsedRule {
        if (vendorHints.isEmpty()) return this
        if (this.vendorHints.isNotEmpty()) return copy(vendorHints = this.vendorHints + vendorHints)
        return copy(vendorHints = vendorHints)
    }

    private fun List<ParsedRule>.withVendorHints(vendorHints: Set<String>): List<ParsedRule> {
        if (vendorHints.isEmpty()) return this
        return map { it.withVendorHints(vendorHints) }
    }

    private data class ParsedRuleBuckets(
        val blocked: LinkedHashMap<String, ParsedRule> = linkedMapOf(),
        val exceptions: LinkedHashMap<String, ParsedRule> = linkedMapOf(),
        val badfilters: LinkedHashMap<String, ParsedRule> = linkedMapOf()
    )

    private fun mergeParsedRuleInto(target: MutableMap<String, ParsedRule>, parsedRule: ParsedRule) {
        val key = parsedRuleBucketKey(parsedRule)
        target[key] = mergeParsedRule(target[key], parsedRule)
    }

    private fun parsedRuleBucketKey(parsedRule: ParsedRule): String {
        if (parsedRule.isUnsupported ||
            parsedRule.regexPattern != null ||
            parsedRule.cosmeticSelector != null ||
            parsedRule.keywordPattern != null ||
            parsedRule.pathPattern != null ||
            parsedRule.ipCidr != null ||
            parsedRule.removeParams.isNotEmpty() ||
            parsedRule.removeParamRegexes.isNotEmpty() ||
            parsedRule.removeRequestHeaders.isNotEmpty() ||
            parsedRule.setRequestHeaders.isNotEmpty() ||
            parsedRule.replaceRules.isNotEmpty() ||
            parsedRule.cspValue != null ||
            parsedRule.jsInjectRules.isNotEmpty() ||
            parsedRule.redirectResource != null
        ) {
            return buildParsedRuleIdentity(parsedRule)
        }
        return parsedRule.domain
    }

    private fun parseCompositeRule(line: String, lineContext: RuleParsingSupport.LineContext): List<ParsedRule>? {
        val envelope = RuleSemanticParserSupport.parseCompositeEnvelope(line) ?: return null
        val parts = envelope.parts
        if (parts.isEmpty()) return emptyList()
        val parsed = parts.flatMap { part -> parseRuleLine(part, lineContext) }
        if (parsed.isEmpty()) return emptyList()
        return when (envelope.operator) {
            "AND" -> mergeCompositeAndRules(parsed)
            "OR" -> parsed.filter(::isSafelyActionableAdRule).distinctBy(::buildParsedRuleIdentity)
            else -> emptyList()
        }
    }

    private fun mergeCompositeAndRules(rules: List<ParsedRule>): List<ParsedRule> {
        val actionable = rules.filter(::isSafelyActionableAdRule)
        if (actionable.isEmpty()) return emptyList()
        val baseRule = actionable.firstOrNull { it.domain != "*" } ?: actionable.firstOrNull { it.ipCidr != null } ?: actionable.first()
        return listOf(actionable.fold(baseRule) { acc, rule -> mergeParsedRule(acc, rule) })
    }

    private fun buildParsedRuleIdentity(rule: ParsedRule): String {
        return listOf(
            rule.domain,
            rule.isException.toString(),
            rule.important.toString(),
            rule.keywordPattern.orEmpty(),
            rule.pathPattern.orEmpty(),
            rule.ipCidr.orEmpty(),
            rule.regexPattern.orEmpty(),
            rule.cosmeticSelector.orEmpty(),
            rule.appPackages.toSortedSet().joinToString("|"),
            rule.excludedDomainConstraints.toSortedSet().joinToString("|"),
            rule.requestTypes.toSortedSet().joinToString("|"),
            rule.destinationPorts.toSortedSet().joinToString("|"),
            rule.sourcePorts.toSortedSet().joinToString("|"),
            rule.removeParams.toSortedSet().joinToString("|"),
            rule.removeParamRegexes.toSortedSet().joinToString("|"),
            rule.removeRequestHeaders.toSortedSet().joinToString("|"),
            rule.setRequestHeaders.toSortedSet().joinToString("|"),
            rule.replaceRules.toSortedSet().joinToString("|"),
            rule.cspValue.orEmpty(),
            rule.jsInjectRules.toSortedSet().joinToString("|"),
            rule.redirectResource.orEmpty(),
            rule.denyallow.toSortedSet().joinToString("|")
        ).joinToString("::")
    }

    private fun isSafelyActionableAdRule(rule: ParsedRule): Boolean {
        if (rule.domain == "*") {
            return rule.destinationPorts.isNotEmpty() ||
                rule.sourcePorts.isNotEmpty() ||
                rule.appPackages.isNotEmpty()
        }
        if (rule.appPackages.isNotEmpty()) {
            return true
        }
        if (rule.ipCidr != null) return true
        if (rule.regexPattern != null || rule.keywordPattern != null || rule.pathPattern != null) return true
        return looksLikeAdDomain(rule.domain) || looksLikeBypassProtectionDomain(rule.domain)
    }

    private fun parseInlinePayloadRule(line: String, lineContext: RuleParsingSupport.LineContext): List<ParsedRule>? {
        val trimmed = line.trim()
        val payloadPrefix = when {
            trimmed.startsWith("payload:", ignoreCase = true) -> "payload:"
            trimmed.startsWith("payload=", ignoreCase = true) -> "payload="
            trimmed.startsWith("rules:", ignoreCase = true) -> "rules:"
            trimmed.startsWith("rules=", ignoreCase = true) -> "rules="
            trimmed.startsWith("payload-item:", ignoreCase = true) -> "payload-item:"
            trimmed.startsWith("payload-item=", ignoreCase = true) -> "payload-item="
            else -> null
        } ?: return null
        val payloadBody = trimmed.substring(payloadPrefix.length).trim()
        if (payloadBody.isBlank()) return emptyList()
        val payloadItems = if (payloadBody.startsWith("[") && payloadBody.endsWith("]")) {
            RuleSemanticParserSupport.extractInlinePayloadItems(payloadBody)
        } else {
            listOf(payloadBody)
        }
        return payloadItems.flatMap { item -> parseRuleLine(item, lineContext) }
    }

    private fun removeRulesInternal(
        current: List<BlockRule>,
        normalizedIds: Set<String>,
        identityKeys: Set<String>
    ): RuleRemovalResult {
        if (normalizedIds.isEmpty() && identityKeys.isEmpty()) {
            return RuleRemovalResult(current, 0)
        }
        val remaining = current.filterNot { rule ->
            val idMatched = normalizedIds.isNotEmpty() && normalizedIds.contains(rule.id.trim())
            val identityMatched = identityKeys.isNotEmpty() && identityKeys.contains(buildRuleIdentityKey(rule))
            idMatched || identityMatched
        }
        return RuleRemovalResult(remaining = remaining, removedCount = current.size - remaining.size)
    }

    private fun parseRegexRule(line: String): ParsedRule? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("/") || !trimmed.endsWith("/")) return null
        val body = trimmed.removePrefix("/").removeSuffix("/").trim()
        if (body.isBlank()) return null
        return ParsedRule(
            domain = extractRegexRuleDomain(body) ?: REGEX_RULE_DOMAIN,
            isException = false,
            regexPattern = body
        )
    }

    private fun parseCosmeticRule(line: String): ParsedRule? {
        val marker = listOf("#@#", "##", "#$#", "#%#").firstOrNull { line.contains(it) } ?: return null
        val domainPart = line.substringBefore(marker).trim()
        val selector = line.substringAfter(marker, "").trim()
        if (selector.isBlank()) return null
        val parsedDomain = sanitizeDomain(normalizeDomainToken(domainPart))
        if (marker == "#%#") {
            val scriptlet = buildAdGuardScriptletInjection(selector) ?: return null
            return ParsedRule(
                domain = parsedDomain ?: COSMETIC_RULE_DOMAIN,
                isException = false,
                jsInjectRules = setOf(scriptlet)
            )
        }
        return ParsedRule(
            domain = parsedDomain ?: COSMETIC_RULE_DOMAIN,
            isException = marker == "#@#",
            cosmeticSelector = selector
        )
    }

    private fun buildAdGuardScriptletInjection(selector: String): String? {
        val trimmed = selector.trim()
        val scriptletCall = trimmed.substringAfter("scriptlet(", missingDelimiterValue = "")
            .substringBeforeLast(')', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?: return null
        val args = splitScriptletArguments(scriptletCall)
        val name = args.firstOrNull()?.trim()?.trim('"', '\'')?.lowercase() ?: return null
        val scriptArgs = args.drop(1).map { it.trim().trim('"', '\'') }
        return when (name) {
            "remove-attr", "ubo-remove-attr" -> buildRemoveAttrScriptlet(scriptArgs)
            "remove-class", "ubo-remove-class" -> buildRemoveClassScriptlet(scriptArgs)
            "set-constant", "ubo-set-constant" -> buildSetConstantScriptlet(scriptArgs)
            "abort-on-property-read", "ubo-abort-on-property-read" -> buildAbortOnPropertyReadScriptlet(scriptArgs)
            "abort-on-property-write", "ubo-abort-on-property-write" -> buildAbortOnPropertyWriteScriptlet(scriptArgs)
            "prevent-settimeout", "prevent-set-timeout", "ubo-prevent-settimeout", "ubo-prevent-set-timeout" -> buildPreventTimerScriptlet("setTimeout", scriptArgs)
            "prevent-setinterval", "prevent-set-interval", "ubo-prevent-setinterval", "ubo-prevent-set-interval" -> buildPreventTimerScriptlet("setInterval", scriptArgs)
            "prevent-fetch", "ubo-prevent-fetch" -> buildPreventNetworkScriptlet("fetch", scriptArgs)
            "prevent-xhr", "prevent-xmlhttprequest", "ubo-prevent-xhr", "ubo-prevent-xmlhttprequest" -> buildPreventNetworkScriptlet("xhr", scriptArgs)
            "prevent-addeventlistener", "prevent-add-event-listener", "ubo-prevent-addeventlistener", "ubo-prevent-add-event-listener" -> buildPreventAddEventListenerScriptlet(scriptArgs)
            "noeval", "ubo-noeval" -> BUILTIN_NOEVAL_SCRIPTLET
            "nowebrtc", "ubo-nowebrtc" -> BUILTIN_NOWEBRTC_SCRIPTLET
            else -> null
        }
    }

    private fun splitScriptletArguments(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        input.forEach { char ->
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> {
                    current.append(char)
                    escaped = true
                }
                quote != null -> {
                    current.append(char)
                    if (char == quote) quote = null
                }
                char == '\'' || char == '"' -> {
                    current.append(char)
                    quote = char
                }
                char == ',' -> {
                    result += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        if (current.isNotBlank()) result += current.toString().trim()
        return result
    }

    private fun buildRemoveAttrScriptlet(args: List<String>): String? {
        val attr = args.getOrNull(0)?.takeIf(::isSafeDomToken) ?: return null
        val selector = args.getOrNull(1)?.takeIf(::isSafeSelectorToken) ?: "[$attr]"
        return """
            (function(){try{var run=function(){document.querySelectorAll(${jsString(selector)}).forEach(function(el){el.removeAttribute(${jsString(attr)});});};run();if(window.MutationObserver&&document.documentElement){new MutationObserver(run).observe(document.documentElement,{childList:true,subtree:true,attributes:true});}}catch(e){}})();
        """.trimIndent()
    }

    private fun buildRemoveClassScriptlet(args: List<String>): String? {
        val className = args.getOrNull(0)?.takeIf(::isSafeDomToken) ?: return null
        val selector = args.getOrNull(1)?.takeIf(::isSafeSelectorToken) ?: ".$className"
        return """
            (function(){try{var run=function(){document.querySelectorAll(${jsString(selector)}).forEach(function(el){el.classList.remove(${jsString(className)});});};run();if(window.MutationObserver&&document.documentElement){new MutationObserver(run).observe(document.documentElement,{childList:true,subtree:true,attributes:true});}}catch(e){}})();
        """.trimIndent()
    }

    private fun buildSetConstantScriptlet(args: List<String>): String? {
        val property = args.getOrNull(0)?.takeIf(::isSafePropertyPath) ?: return null
        val value = normalizeScriptletConstant(args.getOrNull(1) ?: "undefined") ?: return null
        return """
            (function(){try{var path=${jsString(property)}.split('.');var root=window;for(var i=0;i<path.length-1;i++){root[path[i]]=root[path[i]]||{};root=root[path[i]];}Object.defineProperty(root,path[path.length-1],{configurable:true,get:function(){return $value;},set:function(){}});}catch(e){}})();
        """.trimIndent()
    }

    private fun buildAbortOnPropertyReadScriptlet(args: List<String>): String? {
        val property = args.getOrNull(0)?.takeIf(::isSafePropertyPath) ?: return null
        return """
            (function(){try{var path=${jsString(property)}.split('.');var root=window;for(var i=0;i<path.length-1;i++){root[path[i]]=root[path[i]]||{};root=root[path[i]];}Object.defineProperty(root,path[path.length-1],{configurable:true,get:function(){throw new ReferenceError('Blocked by HanFeng scriptlet');},set:function(){}});}catch(e){}})();
        """.trimIndent()
    }

    private fun buildAbortOnPropertyWriteScriptlet(args: List<String>): String? {
        val property = args.getOrNull(0)?.takeIf(::isSafePropertyPath) ?: return null
        return """
            (function(){try{var path=${jsString(property)}.split('.');var root=window;for(var i=0;i<path.length-1;i++){root[path[i]]=root[path[i]]||{};root=root[path[i]];}Object.defineProperty(root,path[path.length-1],{configurable:true,get:function(){return undefined;},set:function(){throw new ReferenceError('Blocked by HanFeng scriptlet');}});}catch(e){}})();
        """.trimIndent()
    }

    private fun buildPreventTimerScriptlet(timerName: String, args: List<String>): String? {
        val pattern = args.firstOrNull()?.takeIf(::isSafeScriptletPattern) ?: return null
        val delay = args.getOrNull(1)?.trim()?.trim('"', '\'')?.toLongOrNull()
        val delayCheck = delay?.takeIf { it >= 0 }?.let { " && delay === $it" }.orEmpty()
        val timerKey = jsString(timerName)
        return """
            (function(){try{var original=window[$timerKey];window[$timerKey]=function(fn,delay){var source=String(fn);if(source.indexOf(${jsString(pattern)})!==-1$delayCheck){return 0;}return original.apply(this,arguments);};}catch(e){}})();
        """.trimIndent()
    }

    private fun buildPreventNetworkScriptlet(kind: String, args: List<String>): String? {
        val pattern = args.firstOrNull()?.takeIf(::isSafeScriptletPattern) ?: return null
        return when (kind) {
            "fetch" -> """
                (function(){try{if(!window.fetch)return;var original=window.fetch;window.fetch=function(input,init){var url=String(typeof input==='string'?input:(input&&input.url)||'');if(url.indexOf(${jsString(pattern)})!==-1){return Promise.resolve(new Response('',{status:204,statusText:'Blocked by HanFeng'}));}return original.apply(this,arguments);};}catch(e){}})();
            """.trimIndent()
            "xhr" -> """
                (function(){try{if(!window.XMLHttpRequest)return;var open=XMLHttpRequest.prototype.open;var send=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(method,url){this.__hanfengBlocked=String(url||'').indexOf(${jsString(pattern)})!==-1;return open.apply(this,arguments);};XMLHttpRequest.prototype.send=function(){if(this.__hanfengBlocked)return;return send.apply(this,arguments);};}catch(e){}})();
            """.trimIndent()
            else -> null
        }
    }

    private fun buildPreventAddEventListenerScriptlet(args: List<String>): String? {
        val eventPattern = args.getOrNull(0)?.takeIf(::isSafeScriptletPattern) ?: return null
        val handlerPattern = args.getOrNull(1)?.takeIf(::isSafeScriptletPattern)
        val handlerCheck = handlerPattern?.let { " && String(listener).indexOf(${jsString(it)})!==-1" }.orEmpty()
        return """
            (function(){try{var original=EventTarget.prototype.addEventListener;EventTarget.prototype.addEventListener=function(type,listener,options){if(String(type).indexOf(${jsString(eventPattern)})!==-1$handlerCheck){return;}return original.apply(this,arguments);};}catch(e){}})();
        """.trimIndent()
    }

    private fun normalizeScriptletConstant(raw: String): String? {
        return when (raw.trim().trim('"', '\'').lowercase()) {
            "undefined" -> "undefined"
            "null" -> "null"
            "true" -> "true"
            "false" -> "false"
            "noopfunc", "emptyfunc", "function" -> "function(){}"
            "nooparray", "emptyarr", "[]" -> "[]"
            "noopobject", "emptyobj", "{}" -> "{}"
            "0", "1" -> raw.trim().trim('"', '\'')
            else -> null
        }
    }

    private fun isSafeDomToken(value: String): Boolean {
        return value.length in 1..80 && value.matches(Regex("[A-Za-z0-9_-]+"))
    }

    private fun isSafePropertyPath(value: String): Boolean {
        return value.length in 1..160 && value.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
    }

    private fun isSafeSelectorToken(value: String): Boolean {
        return value.length in 1..240 && !value.contains('<') && !value.contains('>') && !value.contains("</script", ignoreCase = true)
    }

    private fun isSafeScriptletPattern(value: String): Boolean {
        return value.length in 1..160 && !value.contains('<') && !value.contains('>') && !value.contains("</script", ignoreCase = true)
    }

    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$escaped'"
    }

    private fun parseSurgeWildcardDomain(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("*.") && !trimmed.startsWith(".")) return null
        if (trimmed.startsWith("..") || trimmed.startsWith("*.")) {
            val domainPart = trimmed.removePrefix("*.").removePrefix(".")
            val domain = sanitizeDomain(domainPart) ?: return null
            return listOf(ParsedRule(domain = domain, isException = false))
        }
        return null
    }

    private fun parseLoonKeywordRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("KEYWORD:", ignoreCase = true) &&
            !trimmed.startsWith("DOMAIN-KEYWORD:", ignoreCase = true) &&
            !trimmed.startsWith("HOST-KEYWORD:", ignoreCase = true)) return null
        val value = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
        if (value.isBlank()) return null
        return listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
    }

    private fun parseLoonUrlRegex(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("URL-REGEX:", ignoreCase = true) &&
            !trimmed.startsWith("URL-REGEXP:", ignoreCase = true) &&
            !trimmed.startsWith("DOMAIN-REGEX:", ignoreCase = true) &&
            !trimmed.startsWith("DOMAIN-REGEXP:", ignoreCase = true)) return null
        val value = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
        if (value.isBlank()) return null
        val cleaned = value.removePrefix("\"").removeSuffix("\"")
        return listOf(ParsedRule(domain = cleaned, isException = false, regexPattern = cleaned))
    }

    private fun parseAbpDomainRule(line: String): List<ParsedRule>? {
        if (!line.contains(":") || !line.contains("domain=")) return null
        val colonIndex = line.indexOf(':')
        val prefix = line.substring(0, colonIndex).trim()
        if (!prefix.equals("abp", ignoreCase = true) && !prefix.equals("abp-inject", ignoreCase = true)) return null
        val abpPart = line.substring(colonIndex + 1).trim()
        if (!abpPart.startsWith("||")) return null
        val domain = abpPart.removePrefix("||").substringBefore('^').substringBefore('/').trim()
        if (domain.isBlank()) return null
        val sanitized = sanitizeDomain(domain) ?: return null
        return listOf(ParsedRule(domain = sanitized, isException = false))
    }

    private fun parseShadowrocketRule(line: String): List<ParsedRule>? {
        if (!line.contains(':')) return null
        val colonIndex = line.indexOf(':')
        val prefix = line.substring(0, colonIndex).trim()
        val value = normalizeStructuredRuleValue(line.substring(colonIndex + 1))
        if (value.isBlank()) return null
        return when (RuleSemanticParserSupport.normalizeStructuredRuleType(prefix)) {
            "domain", "full", "full-domain", "domain-full", "host", "hostname", "hostname-full", "host-full", "domain-exact", "host-exact", "hostname-exact", "domain-set", "domain-full-set", "host-set", "hostname-set" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-suffix", "domain-suffixes", "domain-suffix-set", "host-suffix", "host-suffix-set", "hostname-suffix", "hostname-suffix-set", "suffix" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-wildcard", "domain-wildcard-set", "host-wildcard", "host-wildcard-set", "hostname-wildcard", "hostname-wildcard-set" -> {
                val domain = sanitizeDomain(value.removePrefix("*.")) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "domain-keyword", "host-keyword", "hostname-keyword", "keyword" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "domain-regex", "domain-regexp", "host-regex", "host-regexp", "hostname-regex", "hostname-regexp", "url-regex", "url-regexp", "regex" -> {
                listOf(ParsedRule(domain = value, isException = false, regexPattern = value))
            }
            "url-keyword" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "url-wildcard", "url-wildcard-set" -> {
                parseUrlWildcardRuleValue(value)?.let(::listOf) ?: return null
            }
            "ip-cidr", "ip-cidr6", "ipcidr", "ipcidr6" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "rule-set", "ruleset", "rule-provider", "rule_provider", "domain-set", "domain-full-set", "host-set", "hostname-set" -> {
                val domainToken = findActionableStructuredToken(listOf(value)) ?: return emptyList()
                listOf(ParsedRule(domain = domainToken, isException = false))
            }
            "dest-port", "dst-port", "destination-port" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, destinationPorts = setOf(port)))
            }
            "src-port", "source-port" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, sourcePorts = setOf(port)))
            }
            "user-agent", "ua" -> {
                emptyList()
            }
            "process-name", "package-name" -> {
                val packageName = sanitizeAppPackageToken(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, appPackages = setOf(packageName)))
            }
            "geosite" -> parseGeositeCategoryRule(value)
            "src-ip-cidr", "src-ip-cidr6", "ip-asn", "asn", "geoip", "network", "inbound", "protocol" -> {
                emptyList()
            }
            "final", "match" -> {
                emptyList()
            }
            else -> null
        }
    }

    private fun parseSurgeUrlKeyword(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("URL-KEYWORD:", ignoreCase = true)) return null
        val value = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
        if (value.isBlank()) return null
        return listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
    }

    private fun parseGeositeCategoryRule(value: String): List<ParsedRule> {
        val normalized = value.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .substringBefore('@')
            .lowercase()
        if (normalized !in geositeAdCategoryTokens) return emptyList()
        return geositeAdSeedDomains.mapNotNull { seedDomain ->
            sanitizeDomain(seedDomain)?.let { domain ->
                ParsedRule(
                    domain = domain,
                    isException = false,
                    vendorHints = setOf("GEOSITE 广告类别")
                )
            }
        }
    }

    private fun parseHostsRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        
        // IPv4 Hosts 格式：0.0.0.0 example.com, 127.0.0.1 example.com
        val ipv4Pattern = """^(?:0\.0\.0\.0|127\.0\.0\.1)\s+(\S+)""".toRegex()
        ipv4Pattern.find(trimmed)?.let { match ->
            val domain = match.groupValues[1]
            if (domain.equals("localhost", ignoreCase = true)) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        // IPv6 Hosts 格式：::1 example.com, :: localhost
        val ipv6Pattern = """^(?:::+[0-9a-fA-F]*|[0-9a-fA-F]+(?::[0-9a-fA-F]*){2,})\s+(\S+)""".toRegex()
        ipv6Pattern.find(trimmed)?.let { match ->
            val domain = match.groupValues[1]
            if (domain.equals("localhost", ignoreCase = true)) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        return null
    }

    private fun parseDnsmasqRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("address=/", ignoreCase = true)) return null
        
        val addressValue = trimmed.substringAfter("address=/", "").trim()
        if (addressValue.isBlank()) return null
        
        val parts = addressValue.split("/", limit = 2)
        val domain = parts.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        
        if (domain.equals("localhost", ignoreCase = true)) return null
        val target = parts.getOrNull(1)?.trim()
        
        if (target == "127.0.0.1" || target == "0.0.0.0" || target == "::") {
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        val sanitized = sanitizeDomain(domain) ?: return null
        return listOf(ParsedRule(domain = sanitized, isException = false))
    }

    private fun parseSmartdnsRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("address ", ignoreCase = true) && 
            !trimmed.startsWith("nameserver ", ignoreCase = true) &&
            !trimmed.startsWith("ipset ", ignoreCase = true)) return null
        
        if (trimmed.startsWith("address ", ignoreCase = true)) {
            val addressValue = trimmed.substringAfter("address ", "").trim()
            val domain = addressValue.split(" ").firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            if (domain.equals("localhost", ignoreCase = true) || domain.startsWith("-")) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        if (trimmed.startsWith("nameserver ", ignoreCase = true)) {
            val nsValue = trimmed.substringAfter("nameserver ", "").trim()
            val domain = nsValue.split(" ").firstOrNull()?.takeIf { it.isNotBlank() && !it.contains(":") } ?: return null
            if (domain.equals("localhost", ignoreCase = true) || domain.startsWith("-")) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        if (trimmed.startsWith("ipset ", ignoreCase = true)) {
            val ipsetValue = trimmed.substringAfter("ipset ", "").trim()
            val parts = ipsetValue.split(" ")
            if (parts.size < 2) return null
            val domain = parts[1].trim().takeIf { it.isNotBlank() } ?: return null
            if (domain.equals("localhost", ignoreCase = true) || domain.startsWith("-")) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        return null
    }

    private fun parseOpenwrtRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        
        if (trimmed.startsWith("config rule", ignoreCase = true)) {
            return emptyList()
        }
        
        if (trimmed.startsWith("option name ", ignoreCase = true) ||
            trimmed.startsWith("option proto ", ignoreCase = true) ||
            trimmed.startsWith("option src ", ignoreCase = true) ||
            trimmed.startsWith("option dest ", ignoreCase = true)) {
            return emptyList()
        }
        
        if (trimmed.startsWith("option target ", ignoreCase = true)) {
            return emptyList()
        }
        
        return null
    }

    private fun parseAdguardRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        
        val mobileAppPattern = """^@@\|\|(\S+)\^.*app.*\$""".toRegex()
        val mobileAppMatch = mobileAppPattern.find(trimmed)
        if (mobileAppMatch != null) {
            val domain = mobileAppMatch.groupValues[1]
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        val basicPattern = """^\|\|(\S+)\^.*\$""".toRegex()
        val basicMatch = basicPattern.find(trimmed)
        if (basicMatch != null) {
            val domain = basicMatch.groupValues[1]
            if (domain.contains("/") || domain.contains("#")) return null
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        return null
    }

    private fun parseEasyclashRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        
        if (trimmed.startsWith("- ")) {
            val domain = trimmed.removePrefix("- ").trim()
            if (domain.isBlank() || domain.startsWith("#")) return null
            
            val ipCidrPattern = """^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d+)$""".toRegex()
            val ipMatch = ipCidrPattern.find(domain)
            if (ipMatch != null) {
                val cidr = ipMatch.groupValues[1]
                val sanitized = sanitizeIpCidr(cidr) ?: return null
                return listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            
            val sanitized = sanitizeDomain(domain) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        return null
    }

    private fun parsePortWildcardRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        
        // 支持格式：*:PORT$network 或 *:PORT
        // 例如：*:443$network *:444$network *:445$network
        // 匹配 *:(\d+) 可选择性地后跟 $network
        val portPattern = """^\*:(\d+)(?:[$]network)?$""".toRegex()
        val match = portPattern.find(trimmed) ?: return null
        
        val port = parseSinglePortValue(match.groupValues[1]) ?: return null
        
        return listOf(ParsedRule(domain = "*", isException = false, destinationPorts = setOf(port)))
    }

    private fun parseV2RayRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        
        // V2Ray/Xray 格式：domain:example.com
        val domainPattern = """^domain:(.+)$""".toRegex()
        domainPattern.matchEntire(trimmed)?.let { match ->
            val domain = match.groupValues[1].trim()
            val sanitized = sanitizeDomain(normalizeDomainToken(domain)) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        // V2Ray/Xray 格式：domainSuffix:example.com
        val domainSuffixPattern = """^domainSuffix:(.+)$""".toRegex()
        domainSuffixPattern.matchEntire(trimmed)?.let { match ->
            val domain = match.groupValues[1].trim()
            val sanitized = sanitizeDomain(normalizeDomainToken(domain)) ?: return null
            return listOf(ParsedRule(domain = sanitized, isException = false))
        }
        
        // V2Ray/Xray 格式：domainKeyword:keyword
        val domainKeywordPattern = """^domainKeyword:(.+)$""".toRegex()
        domainKeywordPattern.matchEntire(trimmed)?.let { match ->
            val keyword = match.groupValues[1].trim().lowercase()
            return listOf(ParsedRule(domain = keyword, isException = false, keywordPattern = keyword))
        }
        
        // V2Ray/Xray 格式：domainRegex:pattern
        val domainRegexPattern = """^domainRegex:(.+)$""".toRegex()
        domainRegexPattern.matchEntire(trimmed)?.let { match ->
            val regex = match.groupValues[1].trim()
            return listOf(ParsedRule(domain = regex, isException = false, regexPattern = regex))
        }
        
        // V2Ray/Xray 格式：ip:192.168.1.0/24
        val ipPattern = """^ip:(.+)$""".toRegex()
        ipPattern.matchEntire(trimmed)?.let { match ->
            val cidr = match.groupValues[1].trim()
            val sanitized = sanitizeIpCidr(cidr) ?: return null
            return listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
        }
        
        // V2Ray/Xray 格式：geosite:category 或 geoip:cn（不支持但返回空列表避免错误）
        if (trimmed.startsWith("geosite:", ignoreCase = true) || 
            trimmed.startsWith("geoip:", ignoreCase = true)) {
            return emptyList()
        }
        
        return null
    }

    private fun parseShadowrocketFormatRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        if (!trimmed.contains(",")) return null
        
        val segments = trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return null
        
        val ruleType = segments[0].lowercase()
        val value = normalizeStructuredRuleValue(segments[1])
        if (value.isBlank()) return null
        
        return when (ruleType) {
            "host-suffix", "hosts-suffix", "hostsuffix" -> {
                val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "host-keyword", "hosts-keyword", "hostkeyword" -> {
                val keyword = value.lowercase()
                listOf(ParsedRule(domain = keyword, isException = false, keywordPattern = keyword))
            }
            "host-wildcard", "hosts-wildcard", "hostwildcard" -> {
                val cleaned = value.removePrefix("*.").removePrefix("*.")
                val domain = sanitizeDomain(cleaned) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "ip-cidr", "ipcidr" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "ip-cidr6", "ipcidr6" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            else -> null
        }
    }

    private fun parseQuantumultXRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        if (!trimmed.contains(",")) return null
        
        val segments = trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return null
        
        val ruleType = segments[0].lowercase()
        val value = normalizeStructuredRuleValue(segments[1])
        if (value.isBlank()) return null
        
        return when (ruleType) {
            "host", "hosts" -> {
                val domain = sanitizeDomain(normalizeDomainToken(value)) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "host-wildcard", "hostwildcard" -> {
                val cleaned = value.removePrefix("*.").removePrefix("*.")
                val domain = sanitizeDomain(cleaned) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "ip-cidr", "ipcidr" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "ip6-cidr", "ip6cidr", "ipv6-cidr" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "ip-asn", "ipasn", "asn" -> {
                // ASN 规则暂时不支持但返回空列表避免错误
                emptyList()
            }
            else -> null
        }
    }

    private fun parseDotPrefixDomainRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        if (!trimmed.startsWith(".")) return null
        
        // 支持格式：.example.com（等同于 domainSuffix）
        val domain = trimmed.removePrefix(".")
        if (domain.isBlank() || domain.contains("/") || domain.contains("$")) return null
        
        val sanitized = sanitizeDomain(normalizeDomainToken(domain)) ?: return null
        return listOf(ParsedRule(domain = sanitized, isException = false))
    }

    private fun parseIPv6HostsRule(line: String): List<ParsedRule>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        
        // 支持格式：::1 example.com, :: localhost, fe80::1 example.com
        // IPv6 地址格式：多个冒号 + 可选的十六进制
        val ipv6Pattern = """^([0-9a-fA-F:]+(?:\d{1,2})?)\s+(\S+)""".toRegex()
        val match = ipv6Pattern.find(trimmed) ?: return null
        
        val address = match.groupValues[1]
        val domain = match.groupValues[2]
        
        // 验证是否为有效的 IPv6 地址（简单检查：包含冒号且至少两个冒号）
        if (!address.contains("::") && address.count { it == ':' } < 2) return null
        if (domain.equals("localhost", ignoreCase = true)) return null
        
        // 简单解析 IPv6 地址
        return runCatching {
            java.net.InetAddress.getByName(address)
            val sanitized = sanitizeDomain(normalizeDomainToken(domain)) ?: return null
            listOf(ParsedRule(domain = sanitized, isException = false))
        }.getOrNull()
    }

    private fun parseClashRule(line: String): List<ParsedRule>? {
        if (!line.contains(',')) return null
        val segments = line.split(',').map { it.trim().removeSurrounding("\"").removeSurrounding("'") }.filter { it.isNotBlank() }
        if (segments.size < 2) return null
        val ruleType = RuleSemanticParserSupport.normalizeStructuredRuleType(segments[0]).uppercase()
        val value = normalizeStructuredRuleValue(segments[1])
        if (value.isBlank()) return null
        return when (ruleType) {
            "DOMAIN-SUFFIX", "DOMAIN-SUFFIXES", "DOMAIN-SUFFIX-SET", "HOST-SUFFIX", "HOST-SUFFIX-SET", "HOSTNAME-SUFFIX", "HOSTNAME-SUFFIX-SET" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN", "HOST", "HOSTNAME", "HOST-FULL", "HOSTNAME-FULL", "DOMAIN-FULL", "DOMAIN-EXACT", "HOST-EXACT", "HOSTNAME-EXACT", "DOMAIN-SET", "DOMAIN-FULL-SET", "HOST-SET", "HOSTNAME-SET" -> {
                val domain = sanitizeDomain(value) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN-KEYWORD", "HOST-KEYWORD", "HOSTNAME-KEYWORD" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "DOMAIN-WILDCARD", "DOMAIN-WILDCARD-SET", "HOST-WILDCARD", "HOST-WILDCARD-SET", "HOSTNAME-WILDCARD", "HOSTNAME-WILDCARD-SET" -> {
                val cleaned = value.removePrefix("*.").removePrefix("*.")
                val domain = sanitizeDomain(cleaned) ?: return null
                listOf(ParsedRule(domain = domain, isException = false))
            }
            "DOMAIN-REGEX", "DOMAIN-REGEXP", "HOST-REGEX", "HOST-REGEXP", "HOSTNAME-REGEX", "HOSTNAME-REGEXP", "URL-REGEX", "URL-REGEXP" -> {
                listOf(ParsedRule(domain = value, isException = false, regexPattern = value))
            }
            "URL-KEYWORD" -> {
                listOf(ParsedRule(domain = value, isException = false, keywordPattern = value.lowercase()))
            }
            "URL-WILDCARD", "URL-WILDCARD-SET" -> {
                parseUrlWildcardRuleValue(value)?.let(::listOf) ?: return null
            }
            "DEST-PORT", "DST-PORT", "DESTINATION-PORT" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, destinationPorts = setOf(port)))
            }
            "SRC-PORT", "SOURCE-PORT" -> {
                val port = parseSinglePortValue(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, sourcePorts = setOf(port)))
            }
            "USER-AGENT", "UA" -> {
                emptyList()
            }
            "IP-CIDR", "IP-CIDR6", "IPCIDR", "IPCIDR6" -> {
                val cidr = sanitizeIpCidr(value) ?: return null
                listOf(ParsedRule(domain = cidr, isException = false, ipCidr = cidr))
            }
            "RULE-SET", "RULESET", "RULE-PROVIDER", "RULE_PROVIDER" -> {
                val adLikeDomain = findActionableStructuredToken(segments.drop(1)) ?: return emptyList()
                listOf(ParsedRule(domain = adLikeDomain, isException = false))
            }
            "PROCESS-NAME", "PACKAGE-NAME" -> {
                val packageName = sanitizeAppPackageToken(value) ?: return null
                listOf(ParsedRule(domain = "*", isException = false, appPackages = setOf(packageName)))
            }
            "GEOSITE" -> parseGeositeCategoryRule(value)
            "SRC-IP-CIDR", "SRC-IP-CIDR6", "IP-ASN", "ASN", "GEOIP", "NETWORK", "INBOUND", "PROTOCOL" -> emptyList()
            "FINAL", "MATCH" -> {
                emptyList()
            }
            else -> null
        }
    }

    private fun parseUrlWildcardRuleValue(value: String): ParsedRule? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null
        val domain = sanitizeDomain(normalizeDomainToken(normalized)) ?: return null
        val pathPattern = extractPathPattern(normalized)
        val keywordPattern = if (normalized.contains('*')) extractUrlWildcardKeywordPattern(normalized) else null
        return ParsedRule(
            domain = domain,
            isException = false,
            keywordPattern = keywordPattern,
            pathPattern = pathPattern
        )
    }

    private fun extractUrlWildcardKeywordPattern(pattern: String): String? {
        val pathPattern = extractPathPattern(pattern)
        if (pathPattern != null) {
            val cleaned = pathPattern
                .replace('*', ' ')
                .replace('^', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim()
            return cleaned.takeIf { it.isNotBlank() }?.lowercase()
        }
        return extractKeywordPattern(pattern)
    }

    private fun extractRegexRuleDomain(pattern: String): String? {
        return RuleAdDomainSupport.extractRegexRuleDomain(
            pattern = pattern,
            sanitizeDomain = ::sanitizeDomain,
            domainExtractRegex = domainExtractRegex,
            domainSubdomainRegex = domainSubdomainRegex
        )
    }

    private fun extractDomainCandidate(line: String): Pair<String, String?>? {
        val patternPart = line.substringBefore('$').trim()
        val modifierPart = line.substringAfter('$', missingDelimiterValue = "").trim().ifBlank { null }
        if (patternPart.isBlank()) return null
        return patternPart to modifierPart
    }

    private fun normalizeMessyRuleLine(rawLine: String): String = RuleTextNormalizer.normalizeMessyRuleLine(rawLine)

    private fun parseDomainsFromPattern(patternPart: String): List<String> {
        var trimmed = RuleParsingSupport.unwrapRuleWrapper(RuleParsingSupport.stripYamlListPrefix(patternPart.trim()))
        if (trimmed.startsWith("payload:", ignoreCase = true)) {
            trimmed = trimmed.substringAfter(':').trim()
        } else if (trimmed.startsWith("payload=", ignoreCase = true)) {
            trimmed = trimmed.substringAfter('=').trim()
        }
        if (trimmed.equals("payload:", ignoreCase = true) || trimmed.equals("payload", ignoreCase = true)) return emptyList()
        if (trimmed == "||*^" || trimmed == "||*" || trimmed == "*") return listOf("*")
        val dnsmasqPrefix = dnsmasqPrefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        return when {
            trimmed.startsWith("||") -> listOfNotNull(parseDomainAnchorPattern(trimmed.removePrefix("||")))
            trimmed.startsWith("|") -> listOfNotNull(parseExactAnchorPattern(trimmed.removePrefix("|").removeSuffix("|")))
            dnsmasqPrefix != null -> parseDnsmasqDomains(trimmed, dnsmasqPrefix)
            else -> parseStructuredDomainRule(trimmed).ifEmpty { parseHostsOrPlainDomains(trimmed) }
        }
    }

    private fun parseDomainAnchorPattern(pattern: String): String? {
        return RuleDomainParserSupport.parseDomainAnchorPattern(
            pattern = pattern,
            sanitizeDomain = ::sanitizeDomain,
            parseWildcardDomainAnchorPattern = ::parseWildcardDomainAnchorPattern
        )
    }

    private fun parseExactAnchorPattern(pattern: String): String? {
        return RuleDomainParserSupport.parseExactAnchorPattern(
            pattern = pattern,
            sanitizeDomain = ::sanitizeDomain,
            parseWildcardDomainAnchorPattern = ::parseWildcardDomainAnchorPattern
        )
    }

    private fun parseWildcardDomainAnchorPattern(pattern: String): String? {
        return RuleDomainParserSupport.parseWildcardDomainAnchorPattern(pattern, ::sanitizeDomain)
    }

    private fun isSafeDomainPatternSuffix(suffix: String): Boolean {
        return RuleDomainParserSupport.isSafeDomainPatternSuffix(suffix)
    }

    private fun parseHostsOrPlainDomains(patternPart: String): List<String> {
        return RuleDomainParserSupport.parseHostsOrPlainDomains(
            patternPart = patternPart,
            whitespaceRegex = whitespaceRegex,
            sanitizeDomain = ::sanitizeDomain,
            ipV4Regex = ipV4Regex
        )
    }

    private fun parseDnsmasqDomains(patternPart: String, matchedPrefix: String): List<String> {
        return RuleDomainParserSupport.parseDnsmasqDomains(
            patternPart = patternPart,
            matchedPrefix = matchedPrefix,
            sanitizeDomain = ::sanitizeDomain,
            ipV4Regex = ipV4Regex
        )
    }

    private fun parseStructuredDomainRule(patternPart: String): List<String> {
        val normalized = RuleSemanticParserSupport.unwrapCompositeRule(
            RuleParsingSupport.unwrapRuleWrapper(RuleParsingSupport.stripYamlListPrefix(patternPart))
        )
        if (normalized == "*") return listOf("*")
        RuleSemanticParserSupport.parseEmbeddedRuleCarrierDomain(normalized, ::findActionableStructuredToken)?.let { return listOf(it) }
        RuleSemanticParserSupport.parsePrefixedDomainRule(normalized, ::parseStructuredDomainToken)?.let { return listOf(it) }
        sanitizeDomain(RuleDomainParserSupport.normalizeDomainToken(normalized))?.let { directDomain ->
            if (extractPathPattern(normalized) != null) return listOf(directDomain)
        }
        val segments = normalized.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return emptyList()
        val ruleType = RuleSemanticParserSupport.normalizeStructuredRuleType(segments.first())
        val domainToken = findActionableStructuredToken(segments.drop(1))
            ?: segments.drop(1).mapNotNull(::parseStructuredDomainToken).firstOrNull()
            ?: return emptyList()
        return when (ruleType) {
            "domain-suffix", "domain-suffixes", "domain-suffix-set", "domain", "host-suffix", "host-suffix-set", "host", "hostname-suffix", "hostname-suffix-set", "hostname", "suffix" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "domain-wildcard", "domain-wildcard-set", "host-wildcard", "host-wildcard-set", "hostname-wildcard", "hostname-wildcard-set" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken.removePrefix("*.")))
            }
            "full", "full-domain", "hostname", "host-full", "hostname-full", "domain-full", "domain-exact", "host-exact", "hostname-exact", "domain-set", "domain-full-set", "host-set", "hostname-set" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "domain-keyword", "host-keyword", "hostname-keyword", "keyword" -> {
                emptyList()
            }
            "domain-regex", "domain-regexp", "host-regex", "host-regexp", "hostname-regex", "hostname-regexp", "url-regex", "url-regexp",
            "ip-cidr", "ip-cidr6", "ipcidr", "ipcidr6", "src-ip-cidr", "src-ip-cidr6", "ip-asn", "asn", "geoip", "geosite", "rule-set", "process-name",
            "process-path", "package-name", "user-agent", "inbound", "network",
            "protocol", "and", "or", "not" -> {
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun findActionableStructuredToken(values: List<String>): String? {
        return RuleDomainParserSupport.findActionableStructuredToken(
            values = values,
            parseStructuredDomainToken = ::parseStructuredDomainToken,
            looksLikeAdDomain = ::looksLikeAdDomain,
            looksLikeBypassProtectionDomain = ::looksLikeBypassProtectionDomain
        )
    }

    private fun normalizeStructuredRuleValue(value: String): String {
        return RuleSemanticParserSupport.normalizeStructuredRuleValue(value)
    }

    private val dnsmasqPrefixes = listOf("address=/", "server=/", "local=/", "ipset=/", "nftset=/")

    private fun parseStructuredDomainToken(raw: String): String? {
        return RuleDomainParserSupport.parseStructuredDomainToken(raw, ::sanitizeDomain)
    }

    private fun normalizeDomainToken(raw: String): String {
        return RuleDomainParserSupport.normalizeDomainToken(raw)
    }

    private fun isHostsIpToken(token: String): Boolean {
        return RuleDomainParserSupport.isHostsIpToken(token)
    }

    private fun looksLikeIpAddress(token: String): Boolean {
        return RuleDomainParserSupport.looksLikeIpAddress(token, ipV4Regex)
    }

    fun looksLikeAdDomain(domain: String): Boolean {
        return RuleAdDomainSupport.looksLikeAdDomain(
            domain = domain,
            adKeywords = adKeywords,
            weakAdKeywords = weakAdKeywords,
            isLowValueSuspiciousSampleDomain = ::isLowValueSuspiciousSampleDomain,
            looksLikePushRecommendationAdDomain = ::looksLikePushRecommendationAdDomain,
            looksLikeAdSdkInfraDomain = { candidate -> looksLikeAdSdkInfraDomain(candidate) }
        )
    }

    private fun isLowValueSuspiciousSampleDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return true
        // 基础服务白名单 - 这些永远不应该被拦截
        if (isWhitelistedDomain(normalized)) return true
        if (isSensitiveAuthDomain(normalized)) return true
        if (isGameCoreDomain(normalized)) return true
        // 社交核心域名直接过滤（避免与 looksLikeAdDomain 形成循环调用）
        if (isSocialCoreDomain(normalized)) return true
        if (isMediaCoreDomain(normalized)) return true
        if (isBusinessCoreDomain(normalized)) return true
        if (isNovelContentDomain(normalized)) return true
        if (isProtectedNovelAppDomain(normalized) && !RuleProtectionSupport.hasAggressiveNovelAdSignal(normalized)) return true
        if (isProtectedByteDanceInfraDomain(normalized) && !RuleProtectionSupport.hasAggressiveNovelAdSignal(normalized) && !looksLikePushRecommendationAdDomain(normalized)) return true
        return false
    }

    private fun isProtectedByteDanceInfraDomain(domain: String): Boolean {
        return RuleAdDomainSupport.isProtectedByteDanceInfraDomain(
            domain = domain,
            sanitizeDomain = ::sanitizeDomain,
            byteDanceInfraProtectedSuffixes = VendorConfigData.byteDanceInfraProtectedSuffixes,
            novelAggressiveExactDomains = VendorConfigData.novelAggressiveExactDomains
        )
    }

    private fun looksLikeWhitelistedRootAdSubdomain(domain: String): Boolean {
        return RuleAdDomainSupport.looksLikeWhitelistedRootAdSubdomain(
            domain = domain,
            looksLikePushRecommendationAdDomain = ::looksLikePushRecommendationAdDomain,
            looksLikeAdSdkInfraDomain = { candidate -> looksLikeAdSdkInfraDomain(candidate) }
        )
    }

    private fun looksLikeBypassProtectionDomain(domain: String): Boolean = isBypassProtectionDomain(domain)

    private fun looksLikeComplexRulePattern(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        return trimmed.contains("://") ||
            trimmed.contains('*') ||
            trimmed.contains('^') ||
            trimmed.contains('|') ||
            trimmed.contains('=') ||
            trimmed.contains('@')
    }

    private fun extractUnsupportedModifiers(modifierPart: String?): List<String> {
        return RuleModifierSupport.extractUnsupportedModifiers(
            modifierPart = modifierPart,
            unsupportedAdGuardModifiers = unsupportedAdGuardModifiers,
            ignorableAdGuardModifiers = ignorableAdGuardModifiers
        )
    }

    private fun parseModifierInfo(modifierPart: String?): RuleModifierSupport.ModifierInfo {
        return RuleModifierSupport.parseModifierInfo(
            modifierPart = modifierPart,
            unsupportedAdGuardModifiers = unsupportedAdGuardModifiers,
            ignorableAdGuardModifiers = ignorableAdGuardModifiers,
            sanitizeAppPackageToken = ::sanitizeAppPackageToken,
            mapDnsTypeToken = ::mapDnsTypeToken,
            normalizeDnsTypes = ::normalizeDnsTypes,
            mergeDnsTypes = ::mergeDnsTypes
        )
    }

    private fun parseRemoveParamToken(token: String): RuleModifierSupport.RemoveParamToken? {
        return RuleModifierSupport.parseRemoveParamToken(token)
    }

    private fun extractKeywordPattern(pattern: String): String? {
        val trimmed = pattern.trim().removePrefix("*").removeSuffix("*").removeSuffix("^").removeSuffix("/")
        if (trimmed.isBlank()) return null
        return trimmed.lowercase()
    }

    private fun parsePortModifierValues(value: String, inverted: Boolean): Set<Int>? {
        return RuleModifierSupport.parsePortModifierValues(value, inverted)
    }

    private fun parseSinglePortValue(value: String): Int? {
        val port = value.trim().toIntOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    private fun extractPathPattern(pattern: String): String? {
        val trimmed = pattern.trim()
        val withoutDomainAnchor = when {
            trimmed.startsWith("||") -> trimmed.removePrefix("||")
            trimmed.startsWith("|") -> trimmed.removePrefix("|")
            else -> trimmed
        }
        val withoutScheme = withoutDomainAnchor.removePrefix("https://").removePrefix("http://")
        val slashIndex = withoutScheme.indexOf('/')
        if (slashIndex < 0 || slashIndex >= withoutScheme.length - 1) return null
        val path = withoutScheme.substring(slashIndex)
            .substringBefore('$')
            .substringBefore('|')
            .substringBefore('?')
            .trim()
        if (path.isBlank() || path == "/") return null
        return path.lowercase()
    }

    private fun canSafelyApplyModifierContext(patternPart: String, modifierInfo: RuleModifierSupport.ModifierInfo): Boolean {
        // 用户导入的规则全部拦截，不做自作主张的放行检查
        // 只在导入时提醒可能影响正常网络的规则类型
        
        // 标记可能影响正常网络的修饰符（用于提醒用户）
        val mayAffectNetwork = modifierInfo.network ||
            modifierInfo.blockIpv6 ||
            modifierInfo.blockIpv4 ||
            modifierInfo.dnsrewrite != null ||
            !modifierInfo.client.isEmpty() ||
            !modifierInfo.notClient.isEmpty() ||
            !modifierInfo.mac.isEmpty() ||
            !modifierInfo.notMac.isEmpty() ||
            !modifierInfo.asn.isEmpty() ||
            !modifierInfo.notAsn.isEmpty()
        
        // 有网络层修饰符时可以添加提醒，但仍然放行规则导入
        // 提醒逻辑在导入时处理，这里始终返回 true 确保规则被执行
        return true
    }

    private fun isSimpleDomainScopePattern(patternPart: String): Boolean {
        val trimmed = RuleParsingSupport.stripYamlListPrefix(patternPart.trim())
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("||")) {
            val anchorBody = trimmed.removePrefix("||")
            val boundaryIndex = sequenceOf(anchorBody.indexOf('^'), anchorBody.indexOf('/'), anchorBody.indexOf('?'))
                .filter { it >= 0 }
                .minOrNull()
                ?: anchorBody.length
            val hostToken = anchorBody.substring(0, boundaryIndex).trim()
            return sanitizeDomain(normalizeDomainToken(hostToken)) != null || parseWildcardDomainAnchorPattern(hostToken) != null
        }
        if (trimmed.startsWith("|")) {
            return parseExactAnchorPattern(trimmed.removePrefix("|").removeSuffix("|")) != null
        }
        return parseStructuredDomainRule(trimmed).isNotEmpty() || parseHostsOrPlainDomains(trimmed).isNotEmpty()
    }

    private fun mapDnsTypeToken(token: String): Int? {
        return RuleModifierSupport.mapDnsTypeToken(token)
    }

    private fun normalizeDnsTypes(dnsTypes: Set<Int>?): Set<Int>? {
        return RuleModifierSupport.normalizeDnsTypes(dnsTypes)
    }

    private fun mergeDnsTypes(existing: Set<Int>?, incoming: Set<Int>?): Set<Int>? {
        return RuleModifierSupport.mergeDnsTypes(existing, incoming)
    }

    private fun subtractDnsTypeScope(rule: BlockRule, removed: Set<Int>?, removedExcluded: Set<Int>?): BlockRule? {
        val normalizedRemoved = normalizeDnsTypes(removed)
        val normalizedRemovedExcluded = normalizeDnsTypes(removedExcluded)
        val currentIncluded = normalizeDnsTypes(rule.dnsTypes)
        val currentExcluded = normalizeDnsTypes(rule.excludedDnsTypes)
        if (normalizedRemoved == null && normalizedRemovedExcluded == null) return null
        if (currentIncluded == null && currentExcluded == null) return null
        val remainedIncluded = currentIncluded?.minus(normalizedRemoved.orEmpty())?.toSortedSet()
        val remainedExcluded = currentExcluded?.minus(normalizedRemovedExcluded.orEmpty())?.toSortedSet()
        if (remainedIncluded.isNullOrEmpty() && remainedExcluded.isNullOrEmpty()) return null
        return copyBlockRule(
            rule,
            dnsTypes = normalizeDnsTypes(remainedIncluded),
            excludedDnsTypes = normalizeDnsTypes(remainedExcluded)
        )
    }

    private fun buildParsedRuleKey(
        domain: String,
        dnsTypes: Set<Int>?,
        excludedDnsTypes: Set<Int>?,
        badfilter: Boolean,
        firstParty: Boolean = false,
        important: Boolean = false,
        pathPattern: String? = null,
        ipCidr: String? = null,
        regexPattern: String? = null,
        cosmeticSelector: String? = null,
        removeParams: Set<String> = emptySet(),
        removeParamRegexes: Set<String> = emptySet(),
        removeRequestHeaders: Set<String> = emptySet(),
        setRequestHeaders: Set<String> = emptySet(),
        replaceRules: Set<String> = emptySet(),
        cspValue: String? = null,
        jsInjectRules: Set<String> = emptySet(),
        keywordPattern: String? = null,
        domainConstraints: Set<String>? = emptySet(),
        excludedDomainConstraints: Set<String> = emptySet(),
        requestTypes: Set<String> = emptySet(),
        appPackages: Set<String> = emptySet(),
        destinationPorts: Set<Int> = emptySet(),
        sourcePorts: Set<Int> = emptySet(),
        denyallow: Set<String> = emptySet(),
        remoteSourceId: String? = null,
        cosmeticException: Boolean = false
    ): String {
        val dnsKey = normalizeDnsTypes(dnsTypes)?.joinToString("|") ?: "*"
        val excludedDnsKey = normalizeDnsTypes(excludedDnsTypes)?.joinToString("|") ?: "-"
        val removeParamKey = removeParams.toSortedSet().joinToString("|")
        val removeParamRegexKey = removeParamRegexes.toSortedSet().joinToString("|")
        val removeRequestHeaderKey = removeRequestHeaders.toSortedSet().joinToString("|")
        val setRequestHeaderKey = setRequestHeaders.toSortedSet().joinToString("|")
        val replaceRuleKey = replaceRules.toSortedSet().joinToString("|")
        val jsInjectKey = jsInjectRules.toSortedSet().joinToString("|")
        val domainConstraintKey = (domainConstraints ?: emptySet()).toSortedSet().joinToString("|")
        val excludedDomainConstraintKey = excludedDomainConstraints.toSortedSet().joinToString("|")
        val requestTypeKey = requestTypes.toSortedSet().joinToString("|")
        val appPackageKey = appPackages.toSortedSet().joinToString("|")
        val destinationPortKey = destinationPorts.toSortedSet().joinToString("|")
        val sourcePortKey = sourcePorts.toSortedSet().joinToString("|")
        val denyallowKey = denyallow.toSortedSet().joinToString("|")
        return listOf(
            domain,
            dnsKey,
            excludedDnsKey,
            badfilter.toString(),
            "1p:$firstParty",
            "important:$important",
            pathPattern.orEmpty(),
            ipCidr.orEmpty(),
            regexPattern.orEmpty(),
            cosmeticSelector.orEmpty(),
            "cx:${cosmeticException}",
            removeParamKey,
            "removeparam-regex:$removeParamRegexKey",
            "removeheader:$removeRequestHeaderKey",
            "header:$setRequestHeaderKey",
            "replace:$replaceRuleKey",
            cspValue.orEmpty(),
            "jsinject:$jsInjectKey",
            "kw:${keywordPattern.orEmpty()}",
            "domains:$domainConstraintKey",
            "excluded-domains:$excludedDomainConstraintKey",
            "types:$requestTypeKey",
            "apps:$appPackageKey",
            "dports:$destinationPortKey",
            "sports:$sourcePortKey",
            "deny:$denyallowKey",
            "remote:${remoteSourceId.orEmpty()}"
        ).joinToString("#")
    }

    private fun sanitizeDomain(raw: String): String? {
        val value = raw.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('^')
            .substringBefore(':')
            .trim('.')
            .trim()
        if (value.isBlank() || !value.contains('.')) return null
        if (!value.matches(domainValidationRegex)) return null
        return value
    }

    private fun normalizeSuspiciousSampleDomain(raw: String): String? {
        return RuleSuspiciousSampleSupport.normalizeSuspiciousSampleDomain(
            raw = raw,
            suspiciousSampleDecodeMaxLength = SUSPICIOUS_SAMPLE_DECODE_MAX_LENGTH,
            suspiciousSampleMaxDecodeRounds = SUSPICIOUS_SAMPLE_MAX_DECODE_ROUNDS,
            sanitizeDomain = ::sanitizeDomain,
            normalizeDomainToken = ::normalizeDomainToken,
            domainExtractRegex = domainExtractRegex,
            htmlNumericEntityRegex = htmlNumericEntityRegex,
            unicodeEscapeRegex = unicodeEscapeRegex,
            looksLikeWhitelistedRootAdSubdomain = ::looksLikeWhitelistedRootAdSubdomain,
            looksLikeAdSdkInfraDomain = { domain -> looksLikeAdSdkInfraDomain(domain) },
            looksLikePushRecommendationAdDomain = ::looksLikePushRecommendationAdDomain,
            hasAggressiveNovelAdSignal = ::hasAggressiveNovelAdSignal,
            looksLikeAdDomain = ::looksLikeAdDomain,
            isLowValueSuspiciousSampleDomain = ::isLowValueSuspiciousSampleDomain
        )
    }

    private fun sanitizeIpLiteral(raw: String): String? {
        val value = raw.trim().substringBefore('/').trim()
        return runCatching { InetAddress.getByName(value).hostAddress }.getOrNull()
    }

    private fun sanitizeIpCidr(raw: String): String? {
        val value = raw.trim()
        val slashIndex = value.indexOf('/')
        if (slashIndex <= 0 || slashIndex >= value.length - 1) return null
        val ip = sanitizeIpLiteral(value.substring(0, slashIndex)) ?: return null
        val prefixLength = value.substring(slashIndex + 1).toIntOrNull() ?: return null
        val byteSize = runCatching { InetAddress.getByName(ip).address.size }.getOrNull() ?: return null
        val maxPrefix = byteSize * 8
        if (prefixLength !in 0..maxPrefix) return null
        return "$ip/$prefixLength"
    }

    private fun matchesIpCidr(address: InetAddress, ipCidr: String?): Boolean {
        val cidr = ipCidr ?: return false
        val slashIndex = cidr.indexOf('/')
        if (slashIndex <= 0 || slashIndex >= cidr.length - 1) return false
        val network = runCatching { InetAddress.getByName(cidr.substring(0, slashIndex)) }.getOrNull() ?: return false
        val prefixLength = cidr.substring(slashIndex + 1).toIntOrNull() ?: return false
        val addressBytes = address.address
        val networkBytes = network.address
        if (addressBytes.size != networkBytes.size) return false
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until fullBytes) {
            if (addressBytes[index] != networkBytes[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (-1 shl (8 - remainingBits)) and 0xFF
        return (addressBytes[fullBytes].toInt() and mask) == (networkBytes[fullBytes].toInt() and mask)
    }

    private fun getExistingDomainSet(context: Context): MutableSet<String> {
        return getSimpleDomainIndex(context).userOwnedBlocked.toMutableSet()
    }

    private fun streamDomainSetFromFile(context: Context): MutableSet<String> {
        val file = rulesFile(context)
        if (!file.exists() || file.length() <= 2L) return linkedSetOf()
        val domains = linkedSetOf<String>()
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                JsonReader(reader).use { jsonReader ->
                    jsonReader.beginArray()
                    while (jsonReader.hasNext()) {
                        jsonReader.beginObject()
                        while (jsonReader.hasNext()) {
                            if (jsonReader.nextName() == "domain") {
                                domains.add(jsonReader.nextString())
                            } else {
                                jsonReader.skipValue()
                            }
                        }
                        jsonReader.endObject()
                    }
                    jsonReader.endArray()
                }
            }
        } catch (e: Exception) {
            LogRepository.append(context, "streamDomainSetFromFile failed: ${e.message ?: e.javaClass.simpleName}")
            return linkedSetOf()
        }
        return domains
    }

    private fun appendRulesToFile(context: Context, newRules: List<BlockRule>) {
        val normalizedNew = newRules.map(::normalizeRuleForSave)
        val file = rulesFile(context)
        synchronized(fileWriteLock) {
            if (!file.exists() || file.length() <= 2L) {
                writeRulesFile(context, normalizedNew)
                updateRuleCache(normalizedNew)
                return
            }
            val raf = java.io.RandomAccessFile(file, "rw")
            try {
                var bracketPos = file.length() - 1
                while (bracketPos >= 0) {
                    raf.seek(bracketPos)
                    if (raf.read().toInt() == ']'.code) break
                    bracketPos--
                }
                if (bracketPos < 0) {
                    raf.close()
                    LogRepository.append(context, "RuleRepository.appendRulesToFile: corrupted rules file, rebuilding with new rules only")
                    writeRulesFile(context, normalizedNew)
                    updateRuleCache(normalizedNew)
                    return
                }
                raf.setLength(bracketPos)
                raf.seek(bracketPos)
                val bos = java.io.ByteArrayOutputStream()
                normalizedNew.forEach { rule ->
                    bos.write(','.code)
                    bos.write(gson.toJson(rule).toByteArray(Charsets.UTF_8))
                }
                bos.write('\n'.code)
                bos.write(']'.code)
                raf.write(bos.toByteArray())
            } finally {
                raf.close()
            }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldCount = prefs.getInt(KEY_RULE_COUNT, -1)
        prefs.edit().putInt(KEY_RULE_COUNT, if (oldCount >= 0) oldCount + normalizedNew.size else normalizedNew.size).apply()
        clearCachesAfterAppend(normalizedNew.map { it.domain }.toSet())
        synchronized(dnsBlockDecisionLock) {
            dnsBlockDecisionCache.clear()
        }
    }

    private fun save(context: Context, rules: List<BlockRule>) {
        val startTime = System.currentTimeMillis()
        val normalizedRules = normalizeRulesForSave(rules)

        val serializeStart = System.currentTimeMillis()
        synchronized(fileWriteLock) {
            writeRulesFile(context, normalizedRules)
        }
        val serializeTime = System.currentTimeMillis() - serializeStart
        
        updateRuleCacheAfterSave(context, normalizedRules)
        synchronized(dnsBlockDecisionLock) {
            dnsBlockDecisionCache.clear()
        }
        val totalTime = System.currentTimeMillis() - startTime
        LogRepository.append(context, "RuleRepository.save: rules=${normalizedRules.size} serializeTime=${serializeTime}ms totalTime=${totalTime}ms memory=${runtimeMemorySnapshot()}")
    }

    private fun updateRuleCacheAfterSave(context: Context, rules: List<BlockRule>) {
        if (rules.size >= LARGE_RULE_CACHE_THRESHOLD) {
            synchronized(cacheLock) {
                cachedRules = null
                cachedRuleCount = rules.size
                cachedBlockedDomains = null
                cachedRuleMap = null
                cachedSimpleDomainIndex = null
                cachedTrieIndex = null
                cachedRegexRules = null
                cachedCosmeticRules = null
                cachedIpCidrRules = null
                cachedPortOnlyRules = null
                cachedKeywordRules = null
                cachedCombinedKeywordPattern = null
                cachedRegexLiteralIndex = null
                cachedCnameRuleIndex = null
                cachedRuleInventory = null
                cachedAppRuleIndex = null
                cachedUniversalRuleMap = null
                cachedCompiledRegexRules = emptyMap()
                cachedWhitelistHits.clear()
            }
            buildAllCachesFromFile(context)
            return
        }
        updateRuleCache(rules)
    }

    private fun rebuildCachesFromRules(context: Context, rules: List<BlockRule>) {
        synchronized(cacheLock) {
            cachedRules = null
            cachedRuleCount = rules.size
            cachedBlockedDomains = emptySet()
            val blocked = linkedSetOf<String>()
            val userOwnedBlocked = linkedSetOf<String>()
            val importantBlocked = linkedSetOf<String>()
            val exceptions = linkedSetOf<String>()
            rules.asSequence()
                .filter(::isSimpleDomainRule)
                .forEach { rule ->
                    if (rule.exceptionRule) {
                        exceptions += rule.domain
                    } else {
                        blocked += rule.domain
                        if (isUserOwnedBlockingRule(rule)) userOwnedBlocked += rule.domain
                        if (isImportantBlockingRule(rule)) importantBlocked += rule.domain
                    }
                }
            cachedSimpleDomainIndex = SimpleDomainIndex(
                blocked = (blocked - exceptions) + userOwnedBlocked,
                userOwnedBlocked = userOwnedBlocked,
                importantBlocked = importantBlocked - exceptions,
                exceptions = exceptions
            )
            cachedTrieIndex = DomainTrieIndex(
                blocked = (blocked - exceptions) + userOwnedBlocked,
                userOwnedBlocked = userOwnedBlocked,
                importantBlocked = importantBlocked - exceptions,
                exceptions = exceptions
            )
            val nonSimpleRules = rules.asSequence()
                .filterNot(::isSimpleDomainRule)
                .toList()
            cachedRuleMap = nonSimpleRules.groupBy { it.domain }
            buildAppRuleIndex(nonSimpleRules)
            cachedRegexRules = rules.filter { it.regexPattern != null }
            cachedKeywordRules = rules.filter { it.keywordPattern != null }
            cachedCombinedKeywordPattern = null
            cachedRegexLiteralIndex = null
            cachedCosmeticRules = rules.filter { it.cosmeticSelector != null }
            cachedIpCidrRules = rules.filter { it.ipCidr != null }
            cachedPortOnlyRules = rules.filter { it.domain == "*" && it.ipCidr.isNullOrBlank() }
            cachedCnameRuleIndex = rules.filter { it.cname }.associateBy { it.domain }
            cachedRuleInventory = null
            cachedCompiledRegexRules = emptyMap()
            cachedWhitelistHits.clear()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RULE_COUNT, rules.size)
            .apply()
        LogRepository.append(context, "RuleRepository.save: large rule set indexed, rules=${rules.size} memory=${runtimeMemorySnapshot()}")
    }

    private fun normalizeRulesForSave(rules: List<BlockRule>): List<BlockRule> {
        val normalized = rules.mapRulesPreservingInstances { rule ->
            normalizeRuleForSave(rule)
        }
        return if (normalized.size <= 1000) normalized.sortedBy { it.domain } else normalized
    }

    private fun normalizeRuleForSave(rule: BlockRule): BlockRule {
        val stableId = rule.id.trim().ifBlank { UUID.randomUUID().toString() }
        val vendor = normalizeVendorName(rule.vendor)
        val source = if (rule.source == RuleSource.REFERENCE) RuleSource.IMPORTED else rule.source
        return if (stableId == rule.id && vendor == rule.vendor && source == rule.source) {
            rule
        } else {
            copyBlockRule(rule, id = stableId, vendor = vendor, source = source)
        }
    }

    private fun normalizeRuleFromStorage(rule: BlockRule): BlockRule? {
        val id = runCatching { rule.id.trim() }.getOrNull()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val domain = runCatching { rule.domain.trim() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val vendor = runCatching { normalizeVendorName(rule.vendor) }.getOrNull() ?: DEFAULT_VENDOR
        val source = runCatching { rule.source }.getOrNull() ?: RuleSource.IMPORTED
        return copyBlockRule(
            rule,
            id = id,
            domain = domain,
            vendor = vendor,
            source = if (source == RuleSource.REFERENCE) RuleSource.IMPORTED else source,
            excludedDomainConstraints = runCatching { rule.excludedDomainConstraints }.getOrNull(),
            denyallow = runCatching { rule.denyallow }.getOrNull(),
            requestTypes = runCatching { rule.requestTypes }.getOrNull(),
            appPackages = runCatching { rule.appPackages }.getOrNull(),
            destinationPorts = runCatching { rule.destinationPorts }.getOrNull(),
            sourcePorts = runCatching { rule.sourcePorts }.getOrNull(),
            removeParams = runCatching { rule.removeParams }.getOrNull(),
            removeParamRegexes = runCatching { rule.removeParamRegexes }.getOrNull(),
            removeRequestHeaders = runCatching { rule.removeRequestHeaders }.getOrNull(),
            setRequestHeaders = runCatching { rule.setRequestHeaders }.getOrNull(),
            replaceRules = runCatching { rule.replaceRules }.getOrNull(),
            jsInjectRules = runCatching { rule.jsInjectRules }.getOrNull(),
            cookieRemove = runCatching { rule.cookieRemove }.getOrNull(),
            cookieSet = runCatching { rule.cookieSet }.getOrNull(),
            toDomains = runCatching { rule.toDomains }.getOrNull()
        )
    }

    private inline fun List<BlockRule>.mapRulesPreservingInstances(transform: (BlockRule) -> BlockRule): List<BlockRule> {
        var mapped: ArrayList<BlockRule>? = null
        forEachIndexed { index, rule ->
            val next = transform(rule)
            val target = mapped
            when {
                target != null -> target += next
                next !== rule -> {
                    mapped = ArrayList<BlockRule>(size).apply {
                        addAll(this@mapRulesPreservingInstances.subList(0, index))
                        add(next)
                    }
                }
            }
        }
        return mapped ?: this
    }

    private fun rulesFile(context: Context): File {
        return File(context.filesDir, RULES_FILE_NAME)
    }

    private fun readRulesFile(context: Context, file: File): List<BlockRule> {
        val startTime = System.currentTimeMillis()
        return file.bufferedReader(Charsets.UTF_8).use { reader ->
            JsonReader(reader).use { jsonReader ->
                val rules = mutableListOf<BlockRule>()
                jsonReader.beginArray()
                var totalCount = 0
                var skippedCount = 0
                while (jsonReader.hasNext()) {
                    val rule = gson.fromJson<BlockRule>(jsonReader, BlockRule::class.java)
                        ?.let(::normalizeRuleFromStorage)
                    totalCount += 1
                    if (rule != null && rules.size < MAX_CACHEABLE_RULES && !isImportHeapLow()) {
                        rules += rule
                    } else {
                        skippedCount += 1
                    }
                }
                jsonReader.endArray()
                LogRepository.append(context, "RuleRepository.readRulesFile: loaded=${rules.size}, skipped=$skippedCount, total=$totalCount, time=${System.currentTimeMillis() - startTime}ms")
                rules
            }
        }
    }

    private fun readLegacyRulesJson(context: Context, prefs: android.content.SharedPreferences): String {
        val file = rulesFile(context)
        val legacyJson = prefs.getString(KEY_RULES, "[]") ?: "[]"
        if (legacyJson != "[]") {
            runCatching {
                file.bufferedWriter(Charsets.UTF_8).use { it.write(legacyJson) }
                prefs.edit().remove(KEY_RULES).apply()
                LogRepository.append(context, "规则存储已迁移到文件：${file.name}")
            }
        } else {
            val seedRules = buildBuiltInAdSeedRules(context)
            writeRulesFile(context, seedRules)
            LogRepository.append(context, "Initialized built-in ad seed rules: count=${seedRules.size}")
            return gson.toJson(seedRules)
        }
        return legacyJson
    }

    private fun buildBuiltInAdSeedRules(context: Context): List<BlockRule> {
        val domains = linkedSetOf<String>()
        runCatching {
            context.resources.openRawResource(R.raw.hanfeng_builtin_rules).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { rawLine ->
                    RuleParsingSupport.expandPossibleRuleFragments(rawLine).forEach { fragment ->
                        val trimmed = fragment.trim()
                        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@forEach
                        extractSimpleImportDomain(trimmed)?.let(domains::add)
                    }
                }
            }
        }.onFailure { error ->
            LogRepository.append(context, "Load built-in rule resource failed: ${error.message ?: error.javaClass.simpleName}")
        }
        geositeAdSeedDomains.mapNotNullTo(domains) { sanitizeDomain(it) }
        return domains.map { domain ->
            BlockRule(
                id = "builtin-ad-seed-$domain",
                domain = domain,
                vendor = GENERIC_AD_VENDOR,
                source = RuleSource.IMPORTED,
                remoteSourceId = BUILTIN_AD_SEED_SOURCE_ID
            )
        }
    }

    private fun writeRulesJson(context: Context, json: String, ruleCount: Int) {
        val file = rulesFile(context)
        val tempFile = File(context.filesDir, "$RULES_FILE_NAME.tmp")
        tempFile.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        if (!tempFile.renameTo(file)) {
            file.writeText(json, Charsets.UTF_8)
            tempFile.delete()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RULES)
            .putInt(KEY_RULE_COUNT, ruleCount)
            .apply()
    }

    private fun writeRulesFile(context: Context, rules: List<BlockRule>) {
        val file = rulesFile(context)
        val tempFile = File(context.filesDir, "$RULES_FILE_NAME.tmp")
        tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            JsonWriter(writer).use { jsonWriter ->
                jsonWriter.beginArray()
                rules.forEach { rule -> gson.toJson(rule, BlockRule::class.java, jsonWriter) }
                jsonWriter.endArray()
            }
        }
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RULES)
            .putInt(KEY_RULE_COUNT, rules.size)
            .apply()
    }

    private class StreamingRulesRewrite(
        val context: Context,
        val tempFile: File,
        val targetFile: File,
        val jsonWriter: JsonWriter,
        var ruleCount: Int
    ) {
        fun writeRule(rule: BlockRule) {
            gson.toJson(normalizeRuleForSave(rule), BlockRule::class.java, jsonWriter)
            ruleCount += 1
            if (ruleCount % 1_000 == 0) jsonWriter.flush()
        }

        fun writeSimpleRule(domain: String, source: RuleSource, remoteSourceId: String?) {
            writeRule(buildCompactImportedRule(domain, source, remoteSourceId))
        }
    }

    private fun beginStreamingRulesRewrite(
        context: Context,
        excludeRemoteSourceId: String?
    ): StreamingRulesRewrite {
        val targetFile = rulesFile(context)
        val tempFile = File(context.filesDir, "$RULES_FILE_NAME.streaming.tmp")
        val jsonWriter = JsonWriter(tempFile.bufferedWriter(Charsets.UTF_8))
        var ruleCount = 0
        try {
            jsonWriter.beginArray()
            fun keepAndWrite(rule: BlockRule) {
                val normalized = normalizeRuleForSave(rule)
                if (!excludeRemoteSourceId.isNullOrBlank() && hasRemoteSourceId(normalized, excludeRemoteSourceId)) return
                gson.toJson(normalized, BlockRule::class.java, jsonWriter)
                ruleCount += 1
                if (ruleCount % 1_000 == 0) jsonWriter.flush()
            }

            if (targetFile.exists()) {
                targetFile.bufferedReader(Charsets.UTF_8).use { reader ->
                    JsonReader(reader).use { jsonReader ->
                        jsonReader.beginArray()
                        while (jsonReader.hasNext()) {
                            gson.fromJson<BlockRule>(jsonReader, BlockRule::class.java)?.let(::keepAndWrite)
                        }
                        jsonReader.endArray()
                    }
                }
            } else {
                getRules(context).forEach(::keepAndWrite)
            }
            return StreamingRulesRewrite(context, tempFile, targetFile, jsonWriter, ruleCount)
        } catch (e: Exception) {
            runCatching { jsonWriter.close() }
            runCatching { tempFile.delete() }
            throw e
        } catch (e: OutOfMemoryError) {
            runCatching { jsonWriter.close() }
            runCatching { tempFile.delete() }
            throw e
        }
    }

    private fun finishStreamingRulesRewrite(context: Context, rewrite: StreamingRulesRewrite): Int {
        rewrite.jsonWriter.endArray()
        rewrite.jsonWriter.close()
        if (!rewrite.tempFile.renameTo(rewrite.targetFile)) {
            rewrite.tempFile.copyTo(rewrite.targetFile, overwrite = true)
            rewrite.tempFile.delete()
        }
        synchronized(cacheLock) {
            cachedRules = null
            cachedSimpleDomainIndex = null
            cachedRuleMap = null
            cachedRegexRules = null
            cachedCosmeticRules = null
            cachedIpCidrRules = null
            cachedPortOnlyRules = null
            cachedKeywordRules = null
            cachedCombinedKeywordPattern = null
            cachedRegexLiteralIndex = null
            cachedCnameRuleIndex = null
            cachedRuleInventory = null
            cachedCompiledRegexRules = emptyMap()
            cachedBlockedDomains = null
            cachedAppRuleIndex = null
            cachedUniversalRuleMap = null
            cachedWhitelistHits.clear()
        }
        cachedRuleCount = rewrite.ruleCount
        buildAllCachesFromFile(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RULES)
            .putInt(KEY_RULE_COUNT, rewrite.ruleCount)
            .apply()
        synchronized(dnsBlockDecisionLock) {
            dnsBlockDecisionCache.clear()
        }
        LogRepository.append(context, "RuleRepository.streamingSave: rules=${rewrite.ruleCount} memory=${runtimeMemorySnapshot()}")
        return rewrite.ruleCount
    }

    private fun abortStreamingRulesRewrite(rewrite: StreamingRulesRewrite) {
        runCatching { rewrite.jsonWriter.close() }
        runCatching { rewrite.tempFile.delete() }
    }

    private fun clearCaches() {
        synchronized(cacheLock) {
            cachedRules = null
            cachedRuleCount = null
            cachedBlockedDomains = null
            cachedRuleMap = null
            cachedSimpleDomainIndex = null
            cachedTrieIndex = null
            cachedRegexRules = null
            cachedCosmeticRules = null
            cachedIpCidrRules = null
            cachedPortOnlyRules = null
            cachedKeywordRules = null
            cachedCombinedKeywordPattern = null
            cachedRegexLiteralIndex = null
            cachedCnameRuleIndex = null
            cachedRuleInventory = null
            cachedCompiledRegexRules = emptyMap()
            cachedWhitelistHits.clear()
            cachedAppRuleIndex = null
            cachedUniversalRuleMap = null
        }
    }

    private fun clearWhitelistCache() {
        synchronized(cacheLock) {
            cachedWhitelistHits.clear()
        }
    }

    private fun clearCachesAfterAppend(newDomains: Set<String>) {
        synchronized(cacheLock) {
            val index = cachedSimpleDomainIndex
            if (index != null && newDomains.isNotEmpty()) {
                cachedSimpleDomainIndex = index.copy(
                    blocked = index.blocked + newDomains,
                    userOwnedBlocked = index.userOwnedBlocked + newDomains
                )
                cachedTrieIndex = DomainTrieIndex(
                    blocked = cachedSimpleDomainIndex!!.blocked,
                    userOwnedBlocked = cachedSimpleDomainIndex!!.userOwnedBlocked,
                    importantBlocked = cachedSimpleDomainIndex!!.importantBlocked,
                    exceptions = cachedSimpleDomainIndex!!.exceptions
                )
            }
            cachedRuleCount = null
            cachedWhitelistHits.clear()
        }
    }

    fun prepareForRuleImport(context: Context, reason: String) {
        synchronized(cacheLock) {
            cachedBlockedDomains = null
            cachedRuleMap = null
            cachedSimpleDomainIndex = null
            cachedRegexRules = null
            cachedCosmeticRules = null
            cachedIpCidrRules = null
            cachedPortOnlyRules = null
            cachedKeywordRules = null
            cachedCombinedKeywordPattern = null
            cachedRegexLiteralIndex = null
            cachedCnameRuleIndex = null
            cachedRuleInventory = null
            cachedAppRuleIndex = null
            cachedUniversalRuleMap = null
            cachedCompiledRegexRules = emptyMap()
            cachedVendorMap.clear()
            cachedWhitelistHits.clear()
        }
        LogRepository.append(context, "RuleRepository.prepareForRuleImport: reason=$reason memory=${runtimeMemorySnapshot()}")
    }

    fun runtimeMemorySnapshot(): String {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return "used=${formatMemoryBytes(used)}, free=${formatMemoryBytes(runtime.freeMemory())}, total=${formatMemoryBytes(runtime.totalMemory())}, max=${formatMemoryBytes(runtime.maxMemory())}"
    }

    private fun shouldStopStreamingImport(context: Context, addedCount: Int, maxNewRules: Int): Boolean {
        if (addedCount >= maxNewRules) {
            LogRepository.append(context, "Rule import stopped at safe rule limit: added=$addedCount, limit=$maxNewRules")
            return true
        }
        if (isImportHeapLow()) {
            LogRepository.append(context, "Rule import stopped before low-memory crash: added=$addedCount, memory=${runtimeMemorySnapshot()}")
            return true
        }
        return false
    }

    private fun isImportHeapLow(): Boolean {
        val runtime = Runtime.getRuntime()
        val availableHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        return availableHeap < MIN_IMPORT_FREE_HEAP_BYTES
    }

    private fun formatMemoryBytes(bytes: Long): String {
        return String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
    }

    private fun readCustomVendorMap(context: Context): Map<String, String> {
        cachedCustomVendors?.let { return it }
        synchronized(cacheLock) {
            cachedCustomVendors?.let { return it }
            val type = object : TypeToken<Map<String, String>>() {}.type
            val map = gson.fromJson<Map<String, String>>(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_VENDORS, "{}"),
                type
            ) ?: emptyMap()
            cachedCustomVendors = map
            return map
        }
    }

    private fun saveCustomVendorMap(context: Context, map: Map<String, String>) {
        val normalizedMap = map.mapValues { normalizeVendorName(it.value) }.toSortedMap()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_VENDORS, gson.toJson(normalizedMap))
            .apply()
        cachedCustomVendors = normalizedMap
    }

    private fun readUnknownVendorSamples(context: Context): Map<String, SuspiciousDomainRecord> {
        cachedUnknownVendorSamples?.let { return it }
        if (cachedUnknownVendorSamplesLoaded) return emptyMap()
        synchronized(cacheLock) {
            cachedUnknownVendorSamples?.let { return it }
            if (cachedUnknownVendorSamplesLoaded) return emptyMap()
            val prefsValue = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNKNOWN_VENDOR_SAMPLES, "{}") ?: "{}"
            runCatching {
                val type = object : TypeToken<Map<String, SuspiciousDomainRecord>>() {}.type
                gson.fromJson<Map<String, SuspiciousDomainRecord>>(prefsValue, type)
            }.getOrNull()?.let { parsed ->
                val filtered = parsed.filterValues { it.count > 0 }
                cachedUnknownVendorSamples = filtered
                cachedUnknownVendorSamplesLoaded = true
                return filtered
            }
            val legacyType = object : TypeToken<Map<String, Int>>() {}.type
            val legacy = gson.fromJson<Map<String, Int>>(prefsValue, legacyType) ?: emptyMap()
            val migrated = legacy.mapValues { SuspiciousDomainRecord(count = it.value, lastSeenAt = 0L) }
            if (migrated.isNotEmpty()) {
                saveUnknownVendorSamples(context, migrated, force = true)
            } else {
                cachedUnknownVendorSamples = emptyMap()
                cachedUnknownVendorSamplesLoaded = true
            }
            return migrated
        }
    }

    private fun saveUnknownVendorSamples(context: Context, samples: Map<String, SuspiciousDomainRecord>, force: Boolean = false) {
        cachedUnknownVendorSamples = samples
        cachedUnknownVendorSamplesLoaded = true
        val now = System.currentTimeMillis()
        if (!force && now - lastUnknownVendorSamplesPersistAt < SUSPICIOUS_SAMPLE_PERSIST_DEBOUNCE_MILLIS) {
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UNKNOWN_VENDOR_SAMPLES, gson.toJson(samples))
            .apply()
        lastUnknownVendorSamplesPersistAt = now
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "未知"
        return timeFormatter.format(Date(timestamp))
    }

    private fun normalizeSampleAppName(appName: String?): String {
        return RuleSuspiciousSampleSupport.normalizeSampleAppName(appName, lineBreakRegex)
    }

    private fun escapeCsvField(value: String): String {
        if (!value.contains(',') && !value.contains('"') && !value.contains('\n')) return value
        return buildString {
            append('"')
            value.forEach { ch ->
                if (ch == '"') append("\"\"") else append(ch)
            }
            append('"')
        }
    }

    private fun normalizeVendorName(vendor: String): String {
        return RuleVendorSupport.normalizeVendorName(
            vendor = vendor,
            defaultVendor = DEFAULT_VENDOR,
            vendorAliases = VendorConfigData.vendorAliases
        )
    }

    private fun getBlockedDomainSet(context: Context): Set<String> {
        cachedBlockedDomains?.let { return it }
        synchronized(cacheLock) {
            cachedBlockedDomains?.let { return it }
            val domains = getRules(context).filterNot { it.exceptionRule }.mapTo(linkedSetOf(), BlockRule::domain)
            cachedBlockedDomains = domains
            return domains
        }
    }

    private fun getRuleMap(context: Context): Map<String, List<BlockRule>> {
        cachedRuleMap?.let { return it }
        synchronized(cacheLock) {
            cachedRuleMap?.let { return it }
            if (rulesFile(context).exists()) {
                buildAllCachesFromFile(context)
                return cachedRuleMap ?: emptyMap()
            }
            val rules = getRules(context)
            val nonSimpleRules = rules.asSequence()
                .filterNot(::isSimpleDomainRule)
                .toList()
            cachedRuleMap = nonSimpleRules.groupBy { it.domain }
            // 同步构建 App-Specific 规则索引和通用规则映射
            buildAppRuleIndex(nonSimpleRules)
            return cachedRuleMap ?: emptyMap()
        }
    }

    private fun buildAppRuleIndex(rules: List<BlockRule>) {
        val appIndex = mutableMapOf<String, MutableMap<String, MutableList<BlockRule>>>()
        val universal = mutableMapOf<String, MutableList<BlockRule>>()
        for (rule in rules) {
            if (rule.appPackages.isEmpty()) {
                universal.getOrPut(rule.domain) { mutableListOf() }.add(rule)
            } else {
                for (pkg in rule.appPackages) {
                    appIndex.getOrPut(pkg) { mutableMapOf() }
                        .getOrPut(rule.domain) { mutableListOf() }
                        .add(rule)
                }
            }
        }
        cachedUniversalRuleMap = universal.mapValues { (_, v) -> v.toList() }
        cachedAppRuleIndex = appIndex.mapValues { (_, v) -> v.mapValues { (_, rules) -> rules.toList() } }
    }

    private fun getFilteredRulesForApp(context: Context, domain: String, appName: String?): List<BlockRule> {
        val universal = cachedUniversalRuleMap?.get(domain).orEmpty()
        if (appName == null) return universal
        val pkg = extractPackageName(appName) ?: return universal
        val appRules = cachedAppRuleIndex?.get(pkg)?.get(domain).orEmpty()
        return universal + appRules
    }

    private fun getSimpleDomainIndex(context: Context): SimpleDomainIndex {
        cachedSimpleDomainIndex?.let { return it }
        synchronized(cacheLock) {
            cachedSimpleDomainIndex?.let { return it }
            if (rulesFile(context).exists()) {
                buildAllCachesFromFile(context)
                return cachedSimpleDomainIndex ?: SimpleDomainIndex(emptySet(), emptySet(), emptySet(), emptySet())
            }
            val blocked = linkedSetOf<String>()
            val userOwnedBlocked = linkedSetOf<String>()
            val importantBlocked = linkedSetOf<String>()
            val exceptions = linkedSetOf<String>()
            getRules(context).asSequence()
                .filter(::isSimpleDomainRule)
                .forEach { rule ->
                    if (rule.exceptionRule) {
                        exceptions += rule.domain
                    } else {
                        blocked += rule.domain
                        if (isUserOwnedBlockingRule(rule)) userOwnedBlocked += rule.domain
                        if (isImportantBlockingRule(rule)) importantBlocked += rule.domain
                    }
                }
            val index = SimpleDomainIndex(
                blocked = blocked,
                userOwnedBlocked = userOwnedBlocked,
                importantBlocked = importantBlocked,
                exceptions = exceptions
            )
            cachedSimpleDomainIndex = index
            return index
        }
    }

    private fun getTrieIndex(context: Context): DomainTrieIndex {
        cachedTrieIndex?.let { return it }
        synchronized(cacheLock) {
            cachedTrieIndex?.let { return it }
            val index = getSimpleDomainIndex(context)
            val trie = DomainTrieIndex(
                blocked = index.blocked,
                userOwnedBlocked = index.userOwnedBlocked,
                importantBlocked = index.importantBlocked,
                exceptions = index.exceptions
            )
            cachedTrieIndex = trie
            return trie
        }
    }

    internal fun isSimpleDomainRule(rule: BlockRule): Boolean {
        val rawDomain = runCatching { rule.domain.trim() }.getOrNull() ?: return false
        if (rawDomain.isBlank()) return false
        val domain = rawDomain.removePrefix("*.")
        return runCatching { rule.source }.getOrNull() != RuleSource.UNSUPPORTED &&
            !domain.contains("*") &&
            rule.dnsTypes == null &&
            rule.excludedDnsTypes == null &&
            !rule.thirdParty &&
            !rule.firstParty &&
            !rule.redirect &&
            rule.domainConstraints.isNullOrEmpty() &&
            safeRuleSet(rule.excludedDomainConstraints).isEmpty() &&
            safeRuleSet(rule.denyallow).isEmpty() &&
            !rule.urlblock &&
            safeRuleSet(rule.requestTypes).isEmpty() &&
            safeRuleSet(rule.appPackages).isEmpty() &&
            safeRuleSet(rule.destinationPorts).isEmpty() &&
            safeRuleSet(rule.sourcePorts).isEmpty() &&
            rule.keywordPattern.isNullOrBlank() &&
            rule.pathPattern.isNullOrBlank() &&
            rule.ipCidr.isNullOrBlank() &&
            rule.regexPattern.isNullOrBlank() &&
            rule.cosmeticSelector.isNullOrBlank() &&
            !rule.cosmeticException &&
            safeRuleSet(rule.removeParams).isEmpty() &&
            safeRuleSet(rule.removeParamRegexes).isEmpty() &&
            safeRuleSet(rule.removeRequestHeaders).isEmpty() &&
            safeRuleSet(rule.setRequestHeaders).isEmpty() &&
            safeRuleSet(rule.replaceRules).isEmpty() &&
            rule.cspValue.isNullOrBlank() &&
            rule.redirectResource.isNullOrBlank() &&
            safeRuleSet(rule.jsInjectRules).isEmpty() &&
            safeRuleSet(rule.cookieRemove).isEmpty() &&
            safeRuleSet(rule.cookieSet).isEmpty() &&
            safeRuleSet(rule.toDomains).isEmpty() &&
            !rule.emptyResponse
    }

    private fun <T> safeRuleSet(value: Set<T>?): Set<T> = value.orEmpty()

    private fun getRegexRules(context: Context): List<BlockRule> {
        cachedRegexRules?.let { return it }
        synchronized(cacheLock) {
            cachedRegexRules?.let { return it }
            val rules = getRules(context).filter { !it.regexPattern.isNullOrBlank() }
            cachedRegexRules = rules
            return rules
        }
    }

    private fun getFilteredRegexRules(context: Context, appName: String?): List<BlockRule> {
        val all = getRegexRules(context)
        if (appName == null) return all
        val pkg = extractPackageName(appName) ?: return all
        return all.filter { it.appPackages.isEmpty() || pkg in it.appPackages }
    }

    private fun getFilteredKeywordRules(context: Context, appName: String?): List<BlockRule> {
        val all = getKeywordRules(context)
        if (appName == null) return all
        val pkg = extractPackageName(appName) ?: return all
        return all.filter { it.appPackages.isEmpty() || pkg in it.appPackages }
    }

    fun getCnameRuleIndex(context: Context): Map<String, BlockRule> {
        cachedCnameRuleIndex?.let { return it }
        synchronized(cacheLock) {
            cachedCnameRuleIndex?.let { return it }
            val rules = getRules(context).filter { it.cname }
            cachedCnameRuleIndex = rules.associateBy { it.domain }
            return cachedCnameRuleIndex!!
        }
    }

    private fun getCosmeticRules(context: Context): List<BlockRule> {
        cachedCosmeticRules?.let { return it }
        synchronized(cacheLock) {
            cachedCosmeticRules?.let { return it }
            val rules = getRules(context).filter { !it.cosmeticSelector.isNullOrBlank() }
            cachedCosmeticRules = rules
            return rules
        }
    }

    private fun getKeywordRules(context: Context): List<BlockRule> {
        cachedKeywordRules?.let { return it }
        synchronized(cacheLock) {
            cachedKeywordRules?.let { return it }
            val rules = getRules(context).filter { !it.keywordPattern.isNullOrBlank() }
            cachedKeywordRules = rules
            return rules
        }
    }

    private fun getCombinedKeywordMatcher(context: Context): java.util.regex.Pattern? {
        cachedCombinedKeywordPattern?.let { return it.takeIf { it.pattern().isNotEmpty() } }
        synchronized(cacheLock) {
            cachedCombinedKeywordPattern?.let { return it.takeIf { it.pattern().isNotEmpty() } }
            val keywords = getKeywordRules(context).mapNotNull { it.keywordPattern?.lowercase() }.distinct()
            if (keywords.isEmpty()) {
                cachedCombinedKeywordPattern = java.util.regex.Pattern.compile("")
                return null
            }
            val escaped = keywords.map { java.util.regex.Pattern.quote(it) }
            val combined = escaped.joinToString("|")
            cachedCombinedKeywordPattern = java.util.regex.Pattern.compile(combined)
            return cachedCombinedKeywordPattern
        }
    }

    private fun getRegexLiteralIndex(context: Context): Map<String, List<BlockRule>> {
        cachedRegexLiteralIndex?.let { return it }
        synchronized(cacheLock) {
            cachedRegexLiteralIndex?.let { return it }
            val index = linkedMapOf<String, MutableList<BlockRule>>()
            val unindexed = mutableListOf<BlockRule>()
            for (rule in getRegexRules(context)) {
                val fragment = extractRegexLiteralFragment(rule.regexPattern ?: continue)
                if (fragment != null) {
                    index.getOrPut(fragment) { mutableListOf() }.add(rule)
                } else {
                    unindexed.add(rule)
                }
            }
            if (unindexed.isNotEmpty()) {
                index[""] = unindexed
            }
            cachedRegexLiteralIndex = index
            return index
        }
    }

    private fun extractRegexLiteralFragment(pattern: String): String? {
        val raw = pattern.replace("\\.", ".").replace("\\-", "-")
        val clean = raw.removePrefix(".*").removePrefix("^").removeSuffix(".*").removeSuffix("$")
        val match = regexLiteralDomainExtractor.find(clean) ?: return null
        return match.value.lowercase().trim('.')
    }

    private val regexLiteralDomainExtractor = Regex("""[a-z0-9][a-z0-9.-]{4,}\.[a-z]{2,}""", RegexOption.IGNORE_CASE)

    private fun buildDomainCandidates(domain: String): Sequence<String> = sequence {
        yield(domain)
        var index = domain.indexOf('.')
        while (index in 1 until domain.lastIndex) {
            yield(domain.substring(index + 1))
            index = domain.indexOf('.', index + 1)
        }
    }

    private fun updateRuleCache(rules: List<BlockRule>) {
        cachedRules = rules
        cachedBlockedDomains = null
        val blocked = linkedSetOf<String>()
        val userOwnedBlocked = linkedSetOf<String>()
        val importantBlocked = linkedSetOf<String>()
        val exceptions = linkedSetOf<String>()
        rules.asSequence()
            .filter(::isSimpleDomainRule)
            .forEach { rule ->
                if (rule.exceptionRule) {
                    exceptions += rule.domain
                } else {
                    blocked += rule.domain
                    if (isUserOwnedBlockingRule(rule)) userOwnedBlocked += rule.domain
                    if (isImportantBlockingRule(rule)) importantBlocked += rule.domain
                }
            }
        cachedSimpleDomainIndex = SimpleDomainIndex(
            blocked = (blocked - exceptions) + userOwnedBlocked,
            userOwnedBlocked = userOwnedBlocked,
            importantBlocked = importantBlocked - exceptions,
            exceptions = exceptions
        )
        cachedTrieIndex = DomainTrieIndex(
            blocked = (blocked - exceptions) + userOwnedBlocked,
            userOwnedBlocked = userOwnedBlocked,
            importantBlocked = importantBlocked - exceptions,
            exceptions = exceptions
        )
        cachedRuleMap = null
        cachedRegexRules = null
        cachedCosmeticRules = null
        cachedIpCidrRules = null
        cachedPortOnlyRules = null
        cachedKeywordRules = null
        cachedCombinedKeywordPattern = null
        cachedRegexLiteralIndex = null
        cachedCompiledRegexRules = emptyMap()
        cachedVendorMap.clear()
        cachedRuleInventory = null
        cachedAppRuleIndex = null
        cachedUniversalRuleMap = null
        cachedCnameRuleIndex = null
    }

    private fun ruleMatches(
        rule: BlockRule,
        qType: Int?,
        appName: String? = null,
        host: String? = null,
        requestDomain: String? = null,
        requestType: String? = null
    ): Boolean {
        if (!matchesAppPackage(rule.appPackages, appName)) return false
        if (!matchesRequestContext(rule, host, requestDomain)) return false
        if (!matchesRequestType(rule.requestTypes, requestType)) return false
        if (qType == null) return true
        val dnsTypes = normalizeDnsTypes(rule.dnsTypes)
        val excludedDnsTypes = normalizeDnsTypes(rule.excludedDnsTypes)
        if (excludedDnsTypes != null && excludedDnsTypes.contains(qType)) return false
        return dnsTypes == null || dnsTypes.contains(qType)
    }

    private fun matchesRequestType(ruleRequestTypes: Set<String>, requestType: String?): Boolean {
        if (ruleRequestTypes.isEmpty()) return true
        val normalized = requestType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return normalized in ruleRequestTypes
    }

    private fun matchesRequestContext(rule: BlockRule, host: String?, requestDomain: String?): Boolean {
        val normalizedHost = host?.let(::sanitizeDomain)
        val normalizedRequestDomain = requestDomain?.let(::sanitizeDomain)
        if (rule.denyallow.isNotEmpty() && normalizedHost != null) {
            if (rule.denyallow.any { denied -> normalizedHost == denied || normalizedHost.endsWith(".$denied") }) {
                return false
            }
        }
        val contextDomain = normalizedRequestDomain ?: normalizedHost
        if (rule.excludedDomainConstraints.isNotEmpty() && contextDomain != null) {
            if (rule.excludedDomainConstraints.any { excluded -> contextDomain == excluded || contextDomain.endsWith(".$excluded") }) {
                return false
            }
        }
        if (rule.domainConstraints?.isNotEmpty() == true) {
            val scopedDomain = contextDomain ?: return false
            val allowed = rule.domainConstraints.any { allowedDomain ->
                scopedDomain == allowedDomain || scopedDomain.endsWith(".$allowedDomain")
            }
            if (!allowed) return false
        }
        if (rule.toDomains.isNotEmpty()) {
            if (normalizedHost == null) return false
            val matchesTo = rule.toDomains.any { toDomain ->
                normalizedHost == toDomain || normalizedHost.endsWith(".$toDomain")
            }
            if (!matchesTo) return false
        }
        if (rule.thirdParty) {
            if (normalizedHost == null || normalizedRequestDomain == null) return false
            val hostRoot = secondLevelDomain(normalizedHost) ?: normalizedHost
            val requestRoot = secondLevelDomain(normalizedRequestDomain) ?: normalizedRequestDomain
            val sameSite = normalizedHost == normalizedRequestDomain ||
                normalizedHost.endsWith(".$normalizedRequestDomain") ||
                normalizedRequestDomain.endsWith(".$normalizedHost") ||
                hostRoot == requestRoot
            if (sameSite) return false
        }
        if (rule.firstParty) {
            if (normalizedHost == null || normalizedRequestDomain == null) return false
            val hostRoot = secondLevelDomain(normalizedHost) ?: normalizedHost
            val requestRoot = secondLevelDomain(normalizedRequestDomain) ?: normalizedRequestDomain
            val sameSite = normalizedHost == normalizedRequestDomain ||
                normalizedHost.endsWith(".$normalizedRequestDomain") ||
                normalizedRequestDomain.endsWith(".$normalizedHost") ||
                hostRoot == requestRoot
            if (!sameSite) return false
        }
        return true
    }

    private fun sanitizeAppPackageToken(value: String): String? {
        val normalized = value.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace(" ", "")
            .lowercase()
        if (normalized.isBlank()) return null
        if (!normalized.contains('.')) return null
        if (!normalized.matches(Regex("[a-z0-9._-]+"))) return null
        return normalized
    }

    private fun secondLevelDomain(domain: String): String? {
        val parts = domain.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts.takeLast(2).joinToString(".")
    }

    private fun matchesRegexRule(rule: BlockRule, value: String): Boolean {
        val result = SafeRegexRuleMatcher.matches(
            pattern = rule.regexPattern,
            value = value,
            cacheState = SafeRegexRuleMatcher.CacheState(
                compiledPatterns = cachedCompiledRegexRules,
                invalidPatterns = cachedInvalidRegexRules
            )
        )
        cachedCompiledRegexRules = result.cacheState.compiledPatterns
        cachedInvalidRegexRules = result.cacheState.invalidPatterns
        return result.matched
    }

    private fun buildRuleIdentityKey(rule: BlockRule): String {
        return buildParsedRuleKey(
            domain = rule.domain,
            dnsTypes = rule.dnsTypes,
            excludedDnsTypes = rule.excludedDnsTypes,
            badfilter = false,
            firstParty = rule.firstParty,
            important = rule.important,
            pathPattern = rule.pathPattern,
            ipCidr = rule.ipCidr,
            regexPattern = rule.regexPattern,
            cosmeticSelector = rule.cosmeticSelector,
            removeParams = rule.removeParams,
            removeParamRegexes = rule.removeParamRegexes,
            removeRequestHeaders = rule.removeRequestHeaders,
            setRequestHeaders = rule.setRequestHeaders,
            replaceRules = rule.replaceRules,
            cspValue = rule.cspValue,
            jsInjectRules = rule.jsInjectRules,
            keywordPattern = rule.keywordPattern,
            domainConstraints = rule.domainConstraints.orEmpty(),
            excludedDomainConstraints = rule.excludedDomainConstraints,
            requestTypes = rule.requestTypes,
            appPackages = rule.appPackages,
            destinationPorts = rule.destinationPorts,
            sourcePorts = rule.sourcePorts,
            denyallow = rule.denyallow,
            remoteSourceId = rule.remoteSourceId,
            cosmeticException = rule.cosmeticException
        )
    }

    private fun explainRemoteSourceNonAdCandidate(context: Context, rule: BlockRule): RemoteRuleRemovalCandidate? {
        if (!rule.regexPattern.isNullOrBlank()) return null
        if (!rule.cosmeticSelector.isNullOrBlank()) return null
        if (!rule.keywordPattern.isNullOrBlank()) return null
        if (rule.pathPattern != null || rule.ipCidr != null || rule.cspValue != null) return null
        if (rule.appPackages.isNotEmpty() || rule.denyallow.isNotEmpty()) return null
        val reasons = mutableListOf<String>()
        val vendor = if (rule.vendor == DEFAULT_VENDOR) classifyVendor(context, rule.domain) else normalizeVendorName(rule.vendor)
        if (vendor == DEFAULT_VENDOR) reasons += "未识别到明确广告厂商"
        if (!looksLikeAdDomain(rule.domain)) reasons += "域名特征不像广告域名"
        if (!looksLikeBypassProtectionDomain(rule.domain)) reasons += "不属于加密 DNS 反绕过域名"
        if (!isProtectedNovelAppDomain(rule.domain)) reasons += "不属于小说保护广告域名"
        if (!isNovelContentDomain(rule.domain)) reasons += "不属于小说内容域名"
        val shouldRemove = vendor == DEFAULT_VENDOR &&
            !looksLikeAdDomain(rule.domain) &&
            !looksLikeBypassProtectionDomain(rule.domain) &&
            !isProtectedNovelAppDomain(rule.domain) &&
            !isNovelContentDomain(rule.domain)
        if (!shouldRemove) return null
        val sourceLabel = when {
            !rule.remoteSourceId.isNullOrBlank() -> getRemoteRuleSourceName(context, rule.remoteSourceId) ?: "远程规则源"
            else -> rule.source.label
        }
        val businessCategory = classifyBusinessCategory(rule.domain)
        return RemoteRuleRemovalCandidate(
            rule = rule,
            reasons = reasons.distinct(),
            vendor = vendor,
            sourceLabel = sourceLabel,
            riskLevel = CandidateRiskLevel.MEDIUM,
            businessCategory = businessCategory
        )
    }

    private fun explainImpactNormalNetworkCandidate(context: Context, rule: BlockRule): RemoteRuleRemovalCandidate? {
        if (!rule.regexPattern.isNullOrBlank()) return null
        if (!rule.cosmeticSelector.isNullOrBlank()) return null
        val lower = rule.domain.lowercase()
        val reasons = mutableListOf<String>()
        val vendor = if (rule.vendor == DEFAULT_VENDOR) classifyVendor(context, rule.domain) else normalizeVendorName(rule.vendor)
        var riskLevel = CandidateRiskLevel.MEDIUM
        when {
            lower.contains("qq") || lower.contains("weixin") || lower.contains("wechat") -> {
                reasons += "此域名影响微信、QQ 或企业微信的消息收发、登录或文件传输功能"
                riskLevel = CandidateRiskLevel.HIGH
            }
            lower.contains("music") || lower.contains("kugou") || lower.contains("kuwo") || lower.contains("spotify") || lower.contains("y.qq") -> {
                reasons += "此域名影响音乐应用播放、音频拉流或歌曲加载功能"
                riskLevel = CandidateRiskLevel.HIGH
            }
            lower.contains("alipay") || lower.contains("tenpay") || lower.contains("pay") || lower.contains("bank") -> {
                reasons += "此域名影响支付、鉴权或订单确认功能"
                riskLevel = CandidateRiskLevel.HIGH
            }
            lower.contains("game") || lower.contains("gamedl") || lower.contains("mihoyo") || lower.contains("hoyoverse") || lower.contains("steam") -> {
                reasons += "此域名影响游戏登录、资源下载或联机功能"
                riskLevel = CandidateRiskLevel.HIGH
            }
            vendor == DEFAULT_VENDOR && !looksLikeAdDomain(rule.domain) -> {
                reasons += "此域名未表现出明确广告特征，更像正常业务域名，可能影响应用联网"
                riskLevel = CandidateRiskLevel.MEDIUM
            }
        }
        if (reasons.isEmpty()) return null
        if (!looksLikeBypassProtectionDomain(rule.domain)) {
            reasons += "它不属于加密 DNS 反绕过目标，更适合保留正常联网能力"
        }
        val sourceLabel = when {
            !rule.remoteSourceId.isNullOrBlank() -> getRemoteRuleSourceName(context, rule.remoteSourceId) ?: "远程规则源"
            else -> rule.source.label
        }
        val businessCategory = classifyBusinessCategory(rule.domain)
        return RemoteRuleRemovalCandidate(
            rule = rule,
            reasons = reasons.distinct(),
            vendor = vendor,
            sourceLabel = sourceLabel,
            riskLevel = riskLevel,
            businessCategory = businessCategory
        )
    }

    data class RequestRewriteDirectives(
        val removeParams: Set<String> = emptySet(),
        val removeParamRegexes: Set<String> = emptySet(),
        val removeRequestHeaders: Set<String> = emptySet(),
        val setRequestHeaders: Set<String> = emptySet(),
        val replaceRules: Set<String> = emptySet(),
        val cspValue: String? = null,
        val redirectResource: String? = null,
        val jsInjectRules: Set<String> = emptySet(),
        val cosmeticSelectors: List<String> = emptyList(),
        val cookieRemove: Set<String> = emptySet(),
        val cookieSet: Set<String> = emptySet(),
        val block: Boolean = false,
        val emptyResponse: Boolean = false,
        val matchedRuleSummaries: List<String> = emptyList()
    )

    data class RemoteRuleRemovalCandidate(
        val rule: BlockRule,
        val reasons: List<String>,
        val vendor: String,
        val sourceLabel: String,
        val riskLevel: CandidateRiskLevel,
        val businessCategory: String
    )

    enum class CandidateRiskLevel(val label: String) {
        HIGH("高风险"),
        MEDIUM("中风险")
    }

    private fun classifyBusinessCategory(domain: String): String {
        val lower = domain.lowercase()
        return when {
            lower.contains("qq") || lower.contains("weixin") || lower.contains("wechat") -> "社交通信"
            lower.contains("alipay") || lower.contains("tenpay") || lower.contains("pay") || lower.contains("bank") -> "支付金融"
            lower.contains("game") || lower.contains("gamedl") || lower.contains("mihoyo") || lower.contains("hoyoverse") || lower.contains("steam") -> "游戏服务"
            lower.contains("music") || lower.contains("kugou") || lower.contains("kuwo") || lower.contains("spotify") || lower.contains("y.qq") -> "音乐音频"
            lower.contains("video") || lower.contains("vod") || lower.contains("cdn") || lower.contains("media") -> "内容分发"
            else -> "通用业务"
        }
    }

    private fun looksLikeSuspiciousPath(path: String): Boolean {
        return RuleSuspiciousSampleSupport.looksLikeSuspiciousPath(path)
    }

    private fun pathMatchesPattern(path: String, pathPattern: String): Boolean {
        val normalizedPath = path.lowercase()
        val normalizedPattern = pathPattern.lowercase()
        if (normalizedPattern.isBlank()) return false
        val cleanedPath = normalizedPath.substringBefore('?').substringBefore('#')
        val cleanedPattern = normalizedPattern.substringBefore('?').substringBefore('#')
        return when {
            cleanedPattern.contains("*") -> {
                val parts = cleanedPattern.split('*').filter { it.isNotBlank() }
                if (parts.isEmpty()) return true
                var searchStart = 0
                parts.forEachIndexed { index, part ->
                    val foundAt = cleanedPath.indexOf(part, startIndex = searchStart)
                    if (foundAt < 0) return false
                    if (index == 0 && !cleanedPattern.startsWith("*") && foundAt != 0) return false
                    searchStart = foundAt + part.length
                }
                if (!cleanedPattern.endsWith("*") && parts.isNotEmpty()) {
                    return cleanedPath.endsWith(parts.last())
                }
                true
            }
            cleanedPattern.endsWith("^") -> {
                val prefix = cleanedPattern.removeSuffix("^")
                cleanedPath.startsWith(prefix)
            }
            cleanedPattern.startsWith("/") -> cleanedPath.startsWith(cleanedPattern)
            else -> cleanedPath.contains(cleanedPattern)
        }
    }

    private fun hasAggressiveNovelAdSignal(domain: String): Boolean {
        return RuleProtectionSupport.hasAggressiveNovelAdSignal(domain)
    }

    private fun matchesAppPackage(appPackages: Set<String>, appName: String?): Boolean {
        if (appPackages.isEmpty()) return true
        val packageName = extractPackageName(appName) ?: return false
        return appPackages.contains(packageName)
    }

    private fun matchesPortScope(ports: Set<Int>, actualPort: Int?): Boolean {
        if (ports.isEmpty()) return true
        actualPort ?: return false
        return ports.contains(actualPort)
    }

    private fun extractPackageName(appName: String?): String? {
        if (appName == null) return null
        val match = parensRegex.find(appName)
        if (match != null) {
            val pkg = match.groupValues[1].trim()
            if (pkg.contains('.')) return pkg
        }
        if (appName.contains('.')) return appName.trim()
        return null
    }

    private fun applyCosmeticExceptionRules(
        rules: List<BlockRule>,
        exceptionRules: Collection<ParsedRule>
    ): List<BlockRule> {
        if (rules.isEmpty() || exceptionRules.isEmpty()) return rules
        val cosmeticExceptions = exceptionRules.filter { !it.cosmeticSelector.isNullOrBlank() }
        if (cosmeticExceptions.isEmpty()) return rules

        val excludedKeys = cosmeticExceptions
            .mapNotNull { exceptionRule ->
                val selector = exceptionRule.cosmeticSelector?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                buildCosmeticRuleScopeKeys(exceptionRule.domain)
                    .map { scope -> "$scope|$selector" }
            }
            .flatten()
            .toSet()
        if (excludedKeys.isEmpty()) return rules

        return rules.filterNot { rule ->
            if (rule.cosmeticException) return@filterNot false
            val selector = rule.cosmeticSelector?.trim()?.takeIf { it.isNotEmpty() } ?: return@filterNot false
            buildCosmeticRuleScopeKeys(rule.domain).any { scope ->
                excludedKeys.contains("$scope|$selector")
            }
        }
    }

    private fun buildCosmeticRuleScopeKeys(domain: String): List<String> {
        if (domain == COSMETIC_RULE_DOMAIN) return listOf(COSMETIC_RULE_DOMAIN)
        return buildDomainCandidates(domain).toList()
    }

    private fun mergeRuleTypeScopes(existing: BlockRule, incoming: ParsedRule): BlockRule {
        val mergedDenyallow = mergeRuleScopes(existing.denyallow, incoming.denyallow)
        val mergedAppPackages = mergeRuleScopes(existing.appPackages, incoming.appPackages)
        val mergedKeyword = incoming.keywordPattern ?: existing.keywordPattern
        val mergedDestinationPorts = (existing.destinationPorts + incoming.destinationPorts).toSet()
        val mergedSourcePorts = (existing.sourcePorts + incoming.sourcePorts).toSet()
        return copyBlockRule(
            existing,
            dnsTypes = mergeDnsTypes(existing.dnsTypes, incoming.dnsTypes),
            excludedDnsTypes = mergeDnsTypes(existing.excludedDnsTypes, incoming.excludedDnsTypes),
            thirdParty = existing.thirdParty || incoming.thirdParty,
            firstParty = existing.firstParty || incoming.firstParty,
            important = existing.important || incoming.important,
            redirect = existing.redirect || incoming.redirect,
            domainConstraints = (existing.domainConstraints.orEmpty() + incoming.domainConstraints).toSet(),
            excludedDomainConstraints = (existing.excludedDomainConstraints + incoming.excludedDomainConstraints).toSet(),
            denyallow = mergedDenyallow,
            urlblock = existing.urlblock || incoming.urlblock,
            requestTypes = mergeRequestTypeScopes(existing.requestTypes, incoming.requestTypes),
            appPackages = mergedAppPackages,
            destinationPorts = mergedDestinationPorts,
            sourcePorts = mergedSourcePorts,
            keywordPattern = mergedKeyword,
            pathPattern = incoming.pathPattern ?: existing.pathPattern,
            ipCidr = incoming.ipCidr ?: existing.ipCidr,
            regexPattern = incoming.regexPattern ?: existing.regexPattern,
            cosmeticSelector = incoming.cosmeticSelector ?: existing.cosmeticSelector,
            cosmeticException = incoming.isException || existing.cosmeticException,
            exceptionRule = incoming.isException || existing.exceptionRule,
            removeParams = (existing.removeParams + incoming.removeParams).toSet(),
            removeParamRegexes = (existing.removeParamRegexes + incoming.removeParamRegexes).toSet(),
            removeRequestHeaders = (existing.removeRequestHeaders + incoming.removeRequestHeaders).toSet(),
            setRequestHeaders = (existing.setRequestHeaders + incoming.setRequestHeaders).toSet(),
            replaceRules = (existing.replaceRules + incoming.replaceRules).toSet(),
            cspValue = incoming.cspValue ?: existing.cspValue,
            redirectResource = incoming.redirectResource ?: existing.redirectResource,
            jsInjectRules = (existing.jsInjectRules + incoming.jsInjectRules).toSet(),
            cookieRemove = (existing.cookieRemove + incoming.cookieRemove).toSet(),
            cookieSet = (existing.cookieSet + incoming.cookieSet).toSet(),
            toDomains = (existing.toDomains + incoming.toDomains).toSet(),
            cname = existing.cname || incoming.cname,
            emptyResponse = existing.emptyResponse || incoming.emptyResponse
        )
    }

    private fun copyBlockRule(
        rule: BlockRule,
        id: String = rule.id,
        domain: String = rule.domain,
        vendor: String = rule.vendor,
        source: RuleSource = rule.source,
        dnsTypes: Set<Int>? = rule.dnsTypes,
        excludedDnsTypes: Set<Int>? = rule.excludedDnsTypes,
        thirdParty: Boolean = rule.thirdParty,
        firstParty: Boolean = rule.firstParty,
        important: Boolean = rule.important,
        redirect: Boolean = rule.redirect,
        domainConstraints: Set<String>? = rule.domainConstraints,
        excludedDomainConstraints: Set<String>? = rule.excludedDomainConstraints,
        denyallow: Set<String>? = rule.denyallow,
        urlblock: Boolean = rule.urlblock,
        requestTypes: Set<String>? = rule.requestTypes,
        appPackages: Set<String>? = rule.appPackages,
        destinationPorts: Set<Int>? = rule.destinationPorts,
        sourcePorts: Set<Int>? = rule.sourcePorts,
        keywordPattern: String? = rule.keywordPattern,
        pathPattern: String? = rule.pathPattern,
        ipCidr: String? = rule.ipCidr,
        regexPattern: String? = rule.regexPattern,
        cosmeticSelector: String? = rule.cosmeticSelector,
        cosmeticException: Boolean = rule.cosmeticException,
        exceptionRule: Boolean = rule.exceptionRule,
        removeParams: Set<String>? = rule.removeParams,
        removeParamRegexes: Set<String>? = rule.removeParamRegexes,
        removeRequestHeaders: Set<String>? = rule.removeRequestHeaders,
        setRequestHeaders: Set<String>? = rule.setRequestHeaders,
        replaceRules: Set<String>? = rule.replaceRules,
        cspValue: String? = rule.cspValue,
        redirectResource: String? = rule.redirectResource,
        jsInjectRules: Set<String>? = rule.jsInjectRules,
        cookieRemove: Set<String>? = rule.cookieRemove,
        cookieSet: Set<String>? = rule.cookieSet,
        toDomains: Set<String>? = rule.toDomains,
        cname: Boolean = rule.cname,
        emptyResponse: Boolean = rule.emptyResponse,
        remoteSourceId: String? = rule.remoteSourceId
    ): BlockRule {
        return BlockRule(
            id = id,
            domain = domain,
            vendor = vendor,
            source = source,
            dnsTypes = dnsTypes,
            excludedDnsTypes = excludedDnsTypes,
            thirdParty = thirdParty,
            firstParty = firstParty,
            important = important,
            redirect = redirect,
            domainConstraints = domainConstraints,
            excludedDomainConstraints = excludedDomainConstraints.orEmpty(),
            denyallow = denyallow.orEmpty(),
            urlblock = urlblock,
            requestTypes = requestTypes.orEmpty(),
            appPackages = appPackages.orEmpty(),
            destinationPorts = destinationPorts.orEmpty(),
            sourcePorts = sourcePorts.orEmpty(),
            keywordPattern = keywordPattern,
            pathPattern = pathPattern,
            ipCidr = ipCidr,
            regexPattern = regexPattern,
            cosmeticSelector = cosmeticSelector,
            cosmeticException = cosmeticException,
            exceptionRule = exceptionRule,
            removeParams = removeParams.orEmpty(),
            removeParamRegexes = removeParamRegexes.orEmpty(),
            removeRequestHeaders = removeRequestHeaders.orEmpty(),
            setRequestHeaders = setRequestHeaders.orEmpty(),
            replaceRules = replaceRules.orEmpty(),
            cspValue = cspValue,
            redirectResource = redirectResource,
            jsInjectRules = jsInjectRules.orEmpty(),
            cookieRemove = cookieRemove.orEmpty(),
            cookieSet = cookieSet.orEmpty(),
            toDomains = toDomains.orEmpty(),
            cname = cname,
            emptyResponse = emptyResponse,
            remoteSourceId = remoteSourceId
        )
    }

    private fun isLegacyBuiltInRemoteRuleSource(source: RemoteRuleSourceConfig): Boolean {
        return source.id in setOf(
            "awavenue-hosts",
            "adhosts-master",
            "lingeringsound-10007",
            "anti-ad-domains",
            "adaway-hosts",
            "stevenblack-hosts",
            "jdlingyu-ad-wars"
        )
    }

    private fun sanitizeRemoteRuleSource(source: RemoteRuleSourceConfig?): RemoteRuleSourceConfig? {
        source ?: return null
        val id = source.id.trim()
        val name = source.name.trim()
        val url = source.url.trim()
        if (id.isBlank() || name.isBlank() || url.isBlank()) return null
        return source.copy(
            id = id,
            name = name,
            url = url,
            authorId = source.authorId?.trim()?.takeIf { it.isNotBlank() },
            lastError = source.lastError?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun mergeParsedRule(existing: ParsedRule?, incoming: ParsedRule): ParsedRule {
        if (existing == null) return incoming.copy(
            dnsTypes = normalizeDnsTypes(incoming.dnsTypes),
            excludedDnsTypes = normalizeDnsTypes(incoming.excludedDnsTypes)
        )
        val mergedDenyallow = mergeRuleScopes(existing.denyallow, incoming.denyallow)
        val mergedAppPackages = mergeRuleScopes(existing.appPackages, incoming.appPackages)
        val mergedKeyword = incoming.keywordPattern ?: existing.keywordPattern
        val mergedDestinationPorts = (existing.destinationPorts + incoming.destinationPorts).toSet()
        val mergedSourcePorts = (existing.sourcePorts + incoming.sourcePorts).toSet()
        return incoming.copy(
            dnsTypes = mergeDnsTypes(existing.dnsTypes, incoming.dnsTypes),
            excludedDnsTypes = mergeDnsTypes(existing.excludedDnsTypes, incoming.excludedDnsTypes),
            isException = incoming.isException || existing.isException,
            thirdParty = existing.thirdParty || incoming.thirdParty,
            firstParty = existing.firstParty || incoming.firstParty,
            redirect = existing.redirect || incoming.redirect,
            domainConstraints = (existing.domainConstraints + incoming.domainConstraints).toSet(),
            excludedDomainConstraints = (existing.excludedDomainConstraints + incoming.excludedDomainConstraints).toSet(),
            denyallow = mergedDenyallow,
            urlblock = existing.urlblock || incoming.urlblock,
            requestTypes = mergeRequestTypeScopes(existing.requestTypes, incoming.requestTypes),
            appPackages = mergedAppPackages,
            destinationPorts = mergedDestinationPorts,
            sourcePorts = mergedSourcePorts,
            keywordPattern = mergedKeyword,
            pathPattern = incoming.pathPattern ?: existing.pathPattern,
            ipCidr = incoming.ipCidr ?: existing.ipCidr,
            regexPattern = incoming.regexPattern ?: existing.regexPattern,
            cosmeticSelector = incoming.cosmeticSelector ?: existing.cosmeticSelector,
            removeParams = (existing.removeParams + incoming.removeParams).toSet(),
            removeParamRegexes = (existing.removeParamRegexes + incoming.removeParamRegexes).toSet(),
            removeRequestHeaders = (existing.removeRequestHeaders + incoming.removeRequestHeaders).toSet(),
            setRequestHeaders = (existing.setRequestHeaders + incoming.setRequestHeaders).toSet(),
            replaceRules = (existing.replaceRules + incoming.replaceRules).toSet(),
            cspValue = incoming.cspValue ?: existing.cspValue,
            redirectResource = incoming.redirectResource ?: existing.redirectResource,
            jsInjectRules = (existing.jsInjectRules + incoming.jsInjectRules).toSet(),
            cookieRemove = (existing.cookieRemove + incoming.cookieRemove).toSet(),
            cookieSet = (existing.cookieSet + incoming.cookieSet).toSet(),
            toDomains = (existing.toDomains + incoming.toDomains).toSet(),
            cname = existing.cname || incoming.cname,
            emptyResponse = existing.emptyResponse || incoming.emptyResponse
        )
    }

    private fun <T> mergeRuleScopes(existing: Set<T>, incoming: Set<T>): Set<T> {
        if (existing.isEmpty()) return incoming
        if (incoming.isEmpty()) return existing
        return (existing + incoming).toSet()
    }

    private fun mergeRequestTypeScopes(existing: Set<String>, incoming: Set<String>): Set<String> {
        if (existing.isEmpty() || incoming.isEmpty()) return emptySet()
        return (existing + incoming).toSet()
    }

    private data class ParsedRules(
        val blockedRules: List<ParsedRule>,
        val exceptionRules: List<ParsedRule>,
        val badfilterRules: List<ParsedRule>
    )

    private data class RuleRemovalResult(
        val remaining: List<BlockRule>,
        val removedCount: Int
    )

    data class RuleAnalysisReport(
        val totalLines: Int,
        val existingRules: Int,
        val estimatedFinalRules: Int,
        val blankOrCommentLines: Int,
        val safeBlockedRules: Int,
        val safeExceptionRules: Int,
        val duplicateExistingRules: Int,
        val duplicateWithinFileRules: Int,
        val unsupportedModifierRules: Int,
        val cosmeticRules: Int,
        val regexRules: Int,
        val invalidRules: Int,
        val exceptionRemovalEstimate: Int,
        val vendorSummary: List<VendorSummary>,
        val whitelistConflictRules: Int,
        val sampleWhitelistConflictLines: List<String>,
        val sampleUnsupportedLines: List<String>,
        val sampleInvalidLines: List<String>
    ) {
        val safeRuleCount: Int
            get() = safeBlockedRules + safeExceptionRules
    }

    data class VendorSummary(
        val vendor: String,
        val count: Int
    )

    data class RuleInventory(
        val importedCount: Int,
        val manualCount: Int,
        val regexCount: Int,
        val cosmeticCount: Int,
        val keywordCount: Int
    ) {
        val totalSupportedCount: Int
            get() = importedCount + manualCount

        val totalSavedCount: Int
            get() = totalSupportedCount + regexCount + cosmeticCount
    }

    data class SuspiciousDomainSample(
        val domain: String,
        val count: Int,
        val lastSeenAt: Long,
        val lastAppName: String,
        val lastVendor: String,
        val novelHits: Int,
        val dnsHits: Int,
        val aliasHits: Int,
        val tlsSniHits: Int,
        val httpHits: Int,
        val pathHits: Int,
        val redirectHits: Int,
        val appSignalHits: Int,
        val vendorSignalHits: Int,
        val confidenceBoost: Int,
        val lastPathHint: String,
        val refererDomain: String
    )

    private data class SuspiciousDomainRecord(
        val count: Int = 0,
        val lastSeenAt: Long = 0L,
        val lastAppName: String = "",
        val lastVendor: String = "",
        val novelHits: Int = 0,
        val dnsHits: Int = 0,
        val aliasHits: Int = 0,
        val tlsSniHits: Int = 0,
        val httpHits: Int = 0,
        val pathHits: Int = 0,
        val redirectHits: Int = 0,
        val appSignalHits: Int = 0,
        val vendorSignalHits: Int = 0,
        val confidenceBoost: Int = 0,
        val lastPathHint: String = "",
        val refererDomain: String = "",
        val lastSampleAt: Long = 0L
    )

    enum class SuspiciousSignal {
        DNS_QUERY,
        DNS_ALIAS,
        TLS_SNI,
        HTTP_FLOW,
        HTTP_REDIRECT
    }

    private data class ParsedRule(
        val domain: String,
        val isException: Boolean,
        val isBadfilter: Boolean = false,
        val dnsTypes: Set<Int>? = null,
        val excludedDnsTypes: Set<Int>? = null,
        val thirdParty: Boolean = false,
        val firstParty: Boolean = false,
        val important: Boolean = false,
        val redirect: Boolean = false,
        val domainConstraints: Set<String> = emptySet(),
        val excludedDomainConstraints: Set<String> = emptySet(),
        val denyallow: Set<String> = emptySet(),
        val urlblock: Boolean = false,
        val requestTypes: Set<String> = emptySet(),
        val appPackages: Set<String> = emptySet(),
        val destinationPorts: Set<Int> = emptySet(),
        val sourcePorts: Set<Int> = emptySet(),
        val keywordPattern: String? = null,
        val pathPattern: String? = null,
        val ipCidr: String? = null,
        val regexPattern: String? = null,
        val cosmeticSelector: String? = null,
        val removeParams: Set<String> = emptySet(),
        val removeParamRegexes: Set<String> = emptySet(),
        val removeRequestHeaders: Set<String> = emptySet(),
        val setRequestHeaders: Set<String> = emptySet(),
        val replaceRules: Set<String> = emptySet(),
        val cspValue: String? = null,
        val redirectResource: String? = null,
        val jsInjectRules: Set<String> = emptySet(),
        val vendorHints: Set<String> = emptySet(),
        val cookieRemove: Set<String> = emptySet(),
        val cookieSet: Set<String> = emptySet(),
        val toDomains: Set<String> = emptySet(),
        val cname: Boolean = false,
        val emptyResponse: Boolean = false,
        val isUnsupported: Boolean = false
    )

}
