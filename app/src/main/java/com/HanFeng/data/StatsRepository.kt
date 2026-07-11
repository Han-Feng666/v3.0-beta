package com.HanFeng.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.HanFeng.model.DashboardStats
import com.HanFeng.model.RankingBundle
import com.HanFeng.model.RankingEntry
import com.HanFeng.model.RankingType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object StatsRepository {
    enum class BlockSource {
        DNS_RULE,
        URL_HEURISTIC,
        MITM_BODY,
        QUIC_BLOCK,
        USER_MANUAL,
        LEARNING_CANDIDATE,
        SNI_BLOCK
    }

    private const val PREFS = "stats_repo"
    private const val KEY_TOTAL_BLOCKED = "total_blocked"
    private const val KEY_REQUEST_TOTAL = "request_total"
    private const val KEY_RESPONSE_TOTAL = "response_total"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_TODAY_BLOCKED = "today_blocked"
    private const val KEY_DNS_BLOCKED = "dns_blocked"
    private const val KEY_HTTP_BLOCKED = "http_blocked"
    private const val KEY_MITM_BLOCKED = "mitm_blocked"
    private const val KEY_BYTES_SAVED = "bytes_saved"
    private const val KEY_VENDOR_BLOCKED = "vendor_blocked"
    private const val KEY_VENDOR_REQUEST = "vendor_request"
    private const val KEY_VENDOR_RESPONSE = "vendor_response"
    private const val KEY_APP_BLOCKED = "app_blocked"
    private const val KEY_APP_REQUEST = "app_request"
    private const val KEY_APP_RESPONSE = "app_response"
    private const val KEY_BLOCK_SOURCE = "block_source"
    private const val MAX_RANKING_ENTRIES = 300
    private val gson = Gson()
    private val dayFormatter = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    private val updatesInternal = MutableLiveData(0L)

    val updates: LiveData<Long> = updatesInternal

    // Memory-resident counters for hot-path performance (lock-free atomics)
    @Volatile private var todayBlocked = AtomicInteger(0)
    @Volatile private var totalBlocked = AtomicInteger(0)
    @Volatile private var dnsBlocked = AtomicInteger(0)
    @Volatile private var httpBlocked = AtomicInteger(0)
    @Volatile private var mitmBlocked = AtomicInteger(0)
    @Volatile private var requestTotal = AtomicInteger(0)
    @Volatile private var responseTotal = AtomicInteger(0)
    @Volatile private var bytesSaved = AtomicLong(0)

    // Memory-resident ranking maps (concurrent for lock-free access)
    private val vendorBlockedMap = ConcurrentHashMap<String, AtomicInteger>()
    private val vendorRequestMap = ConcurrentHashMap<String, AtomicInteger>()
    private val vendorResponseMap = ConcurrentHashMap<String, AtomicInteger>()
    private val appBlockedMap = ConcurrentHashMap<String, AtomicInteger>()
    private val appRequestMap = ConcurrentHashMap<String, AtomicInteger>()
    private val appResponseMap = ConcurrentHashMap<String, AtomicInteger>()
    private val blockSourceMap = ConcurrentHashMap<String, AtomicInteger>()

    object LatencyMetric {
        const val DNS = "dns"
        const val SNI = "sni"
    }

    private val latencyBuckets = ConcurrentHashMap<String, IntArray>()
    private val LATENCY_BUCKET_BOUNDS_MS = longArrayOf(5, 10, 20, 40, 80, 160, 320, 640, 1280)
    private const val LATENCY_FLUSH_INTERVAL = 30
    private val latencySampleSinceFlush = AtomicInteger(0)

    fun recordLatency(metric: String, durationMillis: Long) {
        if (durationMillis < 0) return
        val failsafe = durationMillis.toInt()
        val buckets = latencyBuckets.computeIfAbsent(metric) { IntArray(LATENCY_BUCKET_BOUNDS_MS.size + 1) }
        var idx = LATENCY_BUCKET_BOUNDS_MS.indexOfFirst { failsafe <= it }
        if (idx < 0) idx = LATENCY_BUCKET_BOUNDS_MS.size
        buckets[idx] = buckets[idx] + 1
        if (latencySampleSinceFlush.incrementAndGet() >= LATENCY_FLUSH_INTERVAL) {
            latencySampleSinceFlush.set(0)
        }
    }

    fun getLatencyPercentile(metric: String, percentile: Double): Long {
        val buckets = latencyBuckets[metric] ?: return 0L
        val total = buckets.sum()
        if (total == 0) return 0L
        val target = (total * percentile).toInt().coerceAtLeast(1)
        var cumulative = 0
        for (i in buckets.indices) {
            cumulative += buckets[i]
            if (cumulative >= target) {
                val lower = if (i == 0) 0 else LATENCY_BUCKET_BOUNDS_MS[i - 1].toInt()
                val upper = if (i < LATENCY_BUCKET_BOUNDS_MS.size) LATENCY_BUCKET_BOUNDS_MS[i].toInt() else 2048
                return ((lower + upper) / 2).toLong()
            }
        }
        return 0L
    }

    fun getLatencySnapshot(metric: String): LatencySnapshot {
        val buckets = latencyBuckets[metric] ?: IntArray(0)
        return LatencySnapshot(
            total = buckets.sum(),
            p50 = getLatencyPercentile(metric, 0.5),
            p95 = getLatencyPercentile(metric, 0.95)
        )
    }

    data class LatencySnapshot(val total: Int, val p50: Long, val p95: Long) {
        val isEmpty: Boolean get() = total == 0
    }

    // Async flush handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var flushPending = false
    private var initialized = false
    private val dirtyEvents = AtomicInteger(0)
    private var pendingFlushRunnable: Runnable? = null
    private val updateDispatchPending = AtomicBoolean(false)
    @Volatile private var lastUpdateDispatchAt = 0L
    private const val UI_UPDATE_MIN_INTERVAL_MILLIS = 5000L
    private const val FLUSH_DELAY_MILLIS = 30_000L
    private const val FLUSH_EVENT_THRESHOLD = 500

    private fun ensureInitialized(context: Context) {
        if (initialized) {
            ensureDayReset(context)
            return
        }
        synchronized(this) {
            if (initialized) return@synchronized
            val prefs = prefs(context)
            val today = dayFormatter.get().format(Date())
            val savedDate = prefs.getString(KEY_TODAY_DATE, null)
            if (savedDate != today) {
                writeTodayReset(prefs, today)
            }
            todayBlocked.set(prefs.getInt(KEY_TODAY_BLOCKED, 0))
            totalBlocked.set(prefs.getInt(KEY_TOTAL_BLOCKED, 0))
            dnsBlocked.set(prefs.getInt(KEY_DNS_BLOCKED, 0))
            httpBlocked.set(prefs.getInt(KEY_HTTP_BLOCKED, 0))
            mitmBlocked.set(prefs.getInt(KEY_MITM_BLOCKED, prefs.getInt(KEY_HTTP_BLOCKED, 0)))
            requestTotal.set(prefs.getInt(KEY_REQUEST_TOTAL, 0))
            responseTotal.set(prefs.getInt(KEY_RESPONSE_TOTAL, 0))
            bytesSaved.set(prefs.getLong(KEY_BYTES_SAVED, 0))

            readMapInto(context, prefs, KEY_VENDOR_BLOCKED, vendorBlockedMap)
            readMapInto(context, prefs, KEY_VENDOR_REQUEST, vendorRequestMap)
            readMapInto(context, prefs, KEY_VENDOR_RESPONSE, vendorResponseMap)
            readMapInto(context, prefs, KEY_APP_BLOCKED, appBlockedMap)
            readMapInto(context, prefs, KEY_APP_REQUEST, appRequestMap)
            readMapInto(context, prefs, KEY_APP_RESPONSE, appResponseMap)
            readMapInto(context, prefs, KEY_BLOCK_SOURCE, blockSourceMap)

            initialized = true
        }
    }

    private fun ensureDayReset(context: Context) {
        val prefs = prefs(context)
        val today = dayFormatter.get().format(Date())
        if (prefs.getString(KEY_TODAY_DATE, null) != today) {
            writeTodayReset(prefs, today)
            // Reset in-memory day counter
            if (todayBlocked.get() > 0) {
                todayBlocked.set(0)
                scheduleFlush(context)
            }
        }
    }

    fun recordRequest(context: Context, vendor: String, appName: String) {
        ensureInitialized(context)
        requestTotal.incrementAndGet()
        incrementMapInMemory(vendorRequestMap, vendor)
        incrementMapInMemory(appRequestMap, appName)
        notifyUpdated()
        scheduleFlush(context)
    }

    fun recordBlockedResponse(context: Context, vendor: String, appName: String, bytesSaved: Long = 0, source: BlockSource = BlockSource.URL_HEURISTIC) {
        ensureInitialized(context)
        recordBlockedEvent(context, vendor, appName, bytesSaved, source)
    }

    fun recordBlockedDns(context: Context, vendor: String, appName: String, bytesSaved: Long = 0, source: BlockSource = BlockSource.DNS_RULE) {
        ensureInitialized(context)
        recordBlockedEvent(context, vendor, appName, bytesSaved, source)
        dnsBlocked.incrementAndGet()
    }

    fun recordBlockedHttp(context: Context, vendor: String, appName: String, bytesSaved: Long = 0, source: BlockSource = BlockSource.URL_HEURISTIC) {
        ensureInitialized(context)
        recordBlockedEvent(context, vendor, appName, bytesSaved, source)
        httpBlocked.incrementAndGet()
    }

    fun recordBlockedMitm(context: Context, vendor: String, appName: String, bytesSaved: Long = 0, source: BlockSource = BlockSource.MITM_BODY) {
        ensureInitialized(context)
        recordBlockedEvent(context, vendor, appName, bytesSaved, source)
        httpBlocked.incrementAndGet()
        mitmBlocked.incrementAndGet()
    }

    private fun recordBlockedEvent(context: Context, vendor: String, appName: String, bytesSaved: Long, source: BlockSource) {
        todayBlocked.incrementAndGet()
        totalBlocked.incrementAndGet()
        responseTotal.incrementAndGet()
        this.bytesSaved.addAndGet(bytesSaved)
        incrementMapInMemory(vendorBlockedMap, vendor)
        incrementMapInMemory(vendorResponseMap, vendor)
        incrementMapInMemory(appBlockedMap, appName)
        incrementMapInMemory(appResponseMap, appName)
        incrementMapInMemory(blockSourceMap, source.name)
        notifyUpdated()
        scheduleFlush(context)
    }

    fun recordLearningCandidate(context: Context, vendor: String, appName: String) {
        ensureInitialized(context)
        incrementMapInMemory(blockSourceMap, BlockSource.LEARNING_CANDIDATE.name)
        incrementMapInMemory(vendorResponseMap, vendor)
        incrementMapInMemory(appResponseMap, appName)
        notifyUpdated()
        scheduleFlush(context)
    }

    private fun scheduleFlush(context: Context) {
        val dirtyCount = dirtyEvents.incrementAndGet()
        if (dirtyCount >= FLUSH_EVENT_THRESHOLD) {
            if (flushPending) {
                pendingFlushRunnable?.let(mainHandler::removeCallbacks)
                pendingFlushRunnable = null
                flushPending = false
            }
            persistAll(context)
            return
        }
        if (flushPending) return
        flushPending = true
        val appContext = context.applicationContext
        val flushRunnable = Runnable {
            flushPending = false
            pendingFlushRunnable = null
            persistAll(appContext)
        }
        pendingFlushRunnable = flushRunnable
        mainHandler.postDelayed(flushRunnable, FLUSH_DELAY_MILLIS)
    }

    private fun persistAll(context: Context) {
        dirtyEvents.set(0)
        val prefs = prefs(context)
        val editor = prefs.edit().apply {
            putCoreCounters(this)
        }

        putRankingMap(editor, KEY_VENDOR_BLOCKED, vendorBlockedMap)
        putRankingMap(editor, KEY_VENDOR_REQUEST, vendorRequestMap)
        putRankingMap(editor, KEY_VENDOR_RESPONSE, vendorResponseMap)
        putRankingMap(editor, KEY_APP_BLOCKED, appBlockedMap)
        putRankingMap(editor, KEY_APP_REQUEST, appRequestMap)
        putRankingMap(editor, KEY_APP_RESPONSE, appResponseMap)
        putRankingMap(editor, KEY_BLOCK_SOURCE, blockSourceMap)

        editor.apply()
    }

    private fun writeTodayReset(prefs: android.content.SharedPreferences, today: String) {
        prefs.edit()
            .putString(KEY_TODAY_DATE, today)
            .putInt(KEY_TODAY_BLOCKED, 0)
            .apply()
    }

    private fun putCoreCounters(editor: android.content.SharedPreferences.Editor) {
        editor
            .putInt(KEY_TODAY_BLOCKED, todayBlocked.get())
            .putInt(KEY_TOTAL_BLOCKED, totalBlocked.get())
            .putInt(KEY_DNS_BLOCKED, dnsBlocked.get())
            .putInt(KEY_HTTP_BLOCKED, httpBlocked.get())
            .putInt(KEY_MITM_BLOCKED, mitmBlocked.get())
            .putInt(KEY_REQUEST_TOTAL, requestTotal.get())
            .putInt(KEY_RESPONSE_TOTAL, responseTotal.get())
            .putLong(KEY_BYTES_SAVED, bytesSaved.get())
    }

    fun flushNow(context: Context) {
        if (flushPending) {
            pendingFlushRunnable?.let(mainHandler::removeCallbacks)
            pendingFlushRunnable = null
            flushPending = false
        }
        persistAll(context)
    }

    fun getDashboard(context: Context): DashboardStats {
        ensureInitialized(context)
        return DashboardStats(
            todayBlocked = todayBlocked.get(),
            totalBlocked = totalBlocked.get(),
            dnsBlocked = dnsBlocked.get(),
            mitmBlocked = mitmBlocked.get(),
            requestTotal = requestTotal.get(),
            responseTotal = responseTotal.get(),
            bytesSaved = bytesSaved.get()
        )
    }

    fun getRankings(context: Context): RankingBundle {
        ensureInitialized(context)
        return RankingBundle(
            vendorBlocked = rankingFromMap(vendorBlockedMap),
            vendorRequest = rankingFromMap(vendorRequestMap),
            vendorResponse = rankingFromMap(vendorResponseMap),
            appBlocked = rankingFromMap(appBlockedMap),
            appRequest = rankingFromMap(appRequestMap),
            appResponse = rankingFromMap(appResponseMap)
        )
    }

    fun getRanking(context: Context, type: RankingType): List<RankingEntry> {
        ensureInitialized(context)
        val map = when (type) {
            RankingType.VENDOR_BLOCKED -> vendorBlockedMap
            RankingType.VENDOR_REQUEST -> vendorRequestMap
            RankingType.VENDOR_RESPONSE -> vendorResponseMap
            RankingType.APP_BLOCKED -> appBlockedMap
            RankingType.APP_REQUEST -> appRequestMap
            RankingType.APP_RESPONSE -> appResponseMap
        }
        return rankingFromMap(map)
    }

    fun getBlockSourceRanking(context: Context): List<RankingEntry> {
        ensureInitialized(context)
        return rankingFromMap(blockSourceMap)
    }

    private fun incrementMapInMemory(map: ConcurrentHashMap<String, AtomicInteger>, name: String) {
        val finalName = name.ifBlank { "未知来源" }
        map.computeIfAbsent(finalName) { AtomicInteger(0) }.incrementAndGet()
        if (map.size > MAX_RANKING_ENTRIES * 5) {
            val keys = map.keys().toList().dropLast(MAX_RANKING_ENTRIES)
            keys.forEach { map.remove(it) }
        }
    }

    private fun sortedRankingEntries(map: ConcurrentHashMap<String, AtomicInteger>): List<Map.Entry<String, AtomicInteger>> {
        return map.entries.asSequence()
            .sortedWith(
                compareBy<Map.Entry<String, AtomicInteger>> { isFallbackName(it.key) }
                    .thenByDescending { it.value.get() }
                    .thenBy { it.key }
            )
            .toList()
    }

    private fun trimMap(map: ConcurrentHashMap<String, AtomicInteger>): Map<String, Int> {
        val sorted = sortedRankingEntries(map)
            .asSequence()
            .take(MAX_RANKING_ENTRIES)
            .map { it.key to it.value.get() }
            .toList()
        return sorted.toMap()
    }

    private fun rankingFromMap(map: ConcurrentHashMap<String, AtomicInteger>): List<RankingEntry> {
        return sortedRankingEntries(map)
            .asSequence()
            .map { RankingEntry(it.key, it.value.get()) }
            .toList()
    }

    private fun putRankingMap(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        map: ConcurrentHashMap<String, AtomicInteger>
    ) {
        editor.putString(key, gson.toJson(trimMap(map)))
    }

    private fun readMapInto(context: Context, prefs: android.content.SharedPreferences, key: String, target: ConcurrentHashMap<String, AtomicInteger>) {
        val json = prefs.getString(key, null) ?: return
        try {
            val type = object : TypeToken<Map<String, Int>>() {}.type
            val parsed = gson.fromJson(json, type) as? Map<String, Int>
            if (parsed != null) {
                target.clear()
                parsed.forEach { (k, v) -> target[k] = AtomicInteger(v) }
            }
        } catch (_: Exception) {
            // Ignore parse errors on startup
        }
    }

    private fun isFallbackName(name: String): Boolean {
        return name.startsWith("其它") || name.contains("未知") || name == "未识别厂商"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notifyUpdated() {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastUpdateDispatchAt
        if (sinceLast >= UI_UPDATE_MIN_INTERVAL_MILLIS) {
            lastUpdateDispatchAt = now
            updatesInternal.postValue(now)
            return
        }
        if (!updateDispatchPending.compareAndSet(false, true)) return
        val delayMillis = (UI_UPDATE_MIN_INTERVAL_MILLIS - sinceLast).coerceAtLeast(200L)
        mainHandler.postDelayed({
            updateDispatchPending.set(false)
            val dispatchAt = System.currentTimeMillis()
            lastUpdateDispatchAt = dispatchAt
            updatesInternal.postValue(dispatchAt)
        }, delayMillis)
    }
}
