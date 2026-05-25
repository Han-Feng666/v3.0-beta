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
    private const val PREFS = "stats_repo"
    private const val KEY_TOTAL_BLOCKED = "total_blocked"
    private const val KEY_REQUEST_TOTAL = "request_total"
    private const val KEY_RESPONSE_TOTAL = "response_total"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_TODAY_BLOCKED = "today_blocked"
    private const val KEY_DNS_BLOCKED = "dns_blocked"
    private const val KEY_HTTP_BLOCKED = "http_blocked"
    private const val KEY_BYTES_SAVED = "bytes_saved"
    private const val KEY_VENDOR_BLOCKED = "vendor_blocked"
    private const val KEY_VENDOR_REQUEST = "vendor_request"
    private const val KEY_VENDOR_RESPONSE = "vendor_response"
    private const val KEY_APP_BLOCKED = "app_blocked"
    private const val KEY_APP_REQUEST = "app_request"
    private const val KEY_APP_RESPONSE = "app_response"
    private const val MAX_RANKING_ENTRIES = 300
    private val gson = Gson()
    private val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val updatesInternal = MutableLiveData(0L)

    val updates: LiveData<Long> = updatesInternal

    // Memory-resident counters for hot-path performance (lock-free atomics)
    @Volatile private var todayBlocked = AtomicInteger(0)
    @Volatile private var totalBlocked = AtomicInteger(0)
    @Volatile private var dnsBlocked = AtomicInteger(0)
    @Volatile private var httpBlocked = AtomicInteger(0)
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

    // Async flush handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var flushPending = false
    private var initialized = false
    private val dirtyEvents = AtomicInteger(0)
    private var pendingFlushRunnable: Runnable? = null
    private val updateDispatchPending = AtomicBoolean(false)
    @Volatile private var lastUpdateDispatchAt = 0L
    private const val UI_UPDATE_MIN_INTERVAL_MILLIS = 2500L
    private const val FLUSH_DELAY_MILLIS = 45_000L
    private const val FLUSH_EVENT_THRESHOLD = 80

    private fun ensureInitialized(context: Context) {
        if (initialized) {
            ensureDayReset(context)
            return
        }
        synchronized(this) {
            if (initialized) return@synchronized
            val prefs = prefs(context)
            val today = dayFormatter.format(Date())
            val savedDate = prefs.getString(KEY_TODAY_DATE, null)
            if (savedDate != today) {
                prefs.edit().putString(KEY_TODAY_DATE, today)
                    .putInt(KEY_TODAY_BLOCKED, 0).apply()
            }
            todayBlocked.set(prefs.getInt(KEY_TODAY_BLOCKED, 0))
            totalBlocked.set(prefs.getInt(KEY_TOTAL_BLOCKED, 0))
            dnsBlocked.set(prefs.getInt(KEY_DNS_BLOCKED, 0))
            httpBlocked.set(prefs.getInt(KEY_HTTP_BLOCKED, 0))
            requestTotal.set(prefs.getInt(KEY_REQUEST_TOTAL, 0))
            responseTotal.set(prefs.getInt(KEY_RESPONSE_TOTAL, 0))
            bytesSaved.set(prefs.getLong(KEY_BYTES_SAVED, 0))

            readMapInto(context, prefs, KEY_VENDOR_BLOCKED, vendorBlockedMap)
            readMapInto(context, prefs, KEY_VENDOR_REQUEST, vendorRequestMap)
            readMapInto(context, prefs, KEY_VENDOR_RESPONSE, vendorResponseMap)
            readMapInto(context, prefs, KEY_APP_BLOCKED, appBlockedMap)
            readMapInto(context, prefs, KEY_APP_REQUEST, appRequestMap)
            readMapInto(context, prefs, KEY_APP_RESPONSE, appResponseMap)

            initialized = true
        }
    }

    private fun ensureDayReset(context: Context) {
        val prefs = prefs(context)
        val today = dayFormatter.format(Date())
        if (prefs.getString(KEY_TODAY_DATE, null) != today) {
            prefs.edit().putString(KEY_TODAY_DATE, today).putInt(KEY_TODAY_BLOCKED, 0).apply()
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

    fun recordBlockedResponse(context: Context, vendor: String, appName: String, bytesSaved: Long = 0) {
        ensureInitialized(context)
        todayBlocked.incrementAndGet()
        totalBlocked.incrementAndGet()
        responseTotal.incrementAndGet()
        this.bytesSaved.addAndGet(bytesSaved)
        incrementMapInMemory(vendorBlockedMap, vendor)
        incrementMapInMemory(vendorResponseMap, vendor)
        incrementMapInMemory(appBlockedMap, appName)
        incrementMapInMemory(appResponseMap, appName)
        notifyUpdated()
        scheduleFlush(context)
    }

    fun recordBlockedDns(context: Context, vendor: String, appName: String, bytesSaved: Long = 0) {
        ensureInitialized(context)
        todayBlocked.incrementAndGet()
        totalBlocked.incrementAndGet()
        dnsBlocked.incrementAndGet()
        responseTotal.incrementAndGet()
        this.bytesSaved.addAndGet(bytesSaved)
        incrementMapInMemory(vendorBlockedMap, vendor)
        incrementMapInMemory(vendorResponseMap, vendor)
        incrementMapInMemory(appBlockedMap, appName)
        incrementMapInMemory(appResponseMap, appName)
        notifyUpdated()
        scheduleFlush(context)
    }

    fun recordBlockedHttp(context: Context, vendor: String, appName: String, bytesSaved: Long = 0) {
        ensureInitialized(context)
        todayBlocked.incrementAndGet()
        totalBlocked.incrementAndGet()
        httpBlocked.incrementAndGet()
        responseTotal.incrementAndGet()
        this.bytesSaved.addAndGet(bytesSaved)
        incrementMapInMemory(vendorBlockedMap, vendor)
        incrementMapInMemory(vendorResponseMap, vendor)
        incrementMapInMemory(appBlockedMap, appName)
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
        val editor = prefs.edit()
            .putInt(KEY_TODAY_BLOCKED, todayBlocked.get())
            .putInt(KEY_TOTAL_BLOCKED, totalBlocked.get())
            .putInt(KEY_DNS_BLOCKED, dnsBlocked.get())
            .putInt(KEY_HTTP_BLOCKED, httpBlocked.get())
            .putInt(KEY_REQUEST_TOTAL, requestTotal.get())
            .putInt(KEY_RESPONSE_TOTAL, responseTotal.get())
            .putLong(KEY_BYTES_SAVED, bytesSaved.get())

            editor.putString(KEY_VENDOR_BLOCKED, gson.toJson(trimMap(vendorBlockedMap)))
            editor.putString(KEY_VENDOR_REQUEST, gson.toJson(trimMap(vendorRequestMap)))
            editor.putString(KEY_VENDOR_RESPONSE, gson.toJson(trimMap(vendorResponseMap)))
            editor.putString(KEY_APP_BLOCKED, gson.toJson(trimMap(appBlockedMap)))
            editor.putString(KEY_APP_REQUEST, gson.toJson(trimMap(appRequestMap)))
            editor.putString(KEY_APP_RESPONSE, gson.toJson(trimMap(appResponseMap)))

        editor.apply()
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
            httpBlocked = httpBlocked.get(),
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

    private fun incrementMapInMemory(map: ConcurrentHashMap<String, AtomicInteger>, name: String) {
        val finalName = name.ifBlank { "未知来源" }
        map.computeIfAbsent(finalName) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun trimMap(map: ConcurrentHashMap<String, AtomicInteger>): Map<String, Int> {
        val sorted = map.entries.asSequence()
            .sortedWith(
                compareBy<Map.Entry<String, AtomicInteger>> { isFallbackName(it.key) }
                    .thenByDescending { it.value.get() }
                    .thenBy { it.key }
            )
            .take(MAX_RANKING_ENTRIES)
            .map { it.key to it.value.get() }
            .toList()
        return sorted.toMap()
    }

    private fun rankingFromMap(map: ConcurrentHashMap<String, AtomicInteger>): List<RankingEntry> {
        return map.entries.asSequence()
            .sortedWith(
                compareBy<Map.Entry<String, AtomicInteger>> { isFallbackName(it.key) }
                    .thenByDescending { it.value.get() }
                    .thenBy { it.key }
            )
            .map { RankingEntry(it.key, it.value.get()) }
            .toList()
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
