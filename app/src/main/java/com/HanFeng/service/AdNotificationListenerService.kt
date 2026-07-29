package com.HanFeng.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.PromoGovernSnapshotRepository
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 通知拦击器：在用户授予"通知访问权限"后实时拦截推广治理 APP 的通知，
 * 以及含广告关键字的通知。这类通知通常是 APP 后台驻留持续推送的推广/广告条，
 * 普通的"一次性禁用通知渠道"操作会被 APP 重新发起新渠道绕过，
 * NotificationListenerService 能持续监听、即时 cancel。
 */
class AdNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "AdNotiListener"

        @Volatile private var listenerConnected: Boolean = false

        fun isListenerConnected(): Boolean = listenerConnected

        // 治理名单缓存：onListenerConnected 时拉一次，避免每条通知都读 SharedPreferences
        @Volatile private var cachedGovernedPackages: Set<String> = emptySet()
        // 治理名单 + 关键字缓存复算的时间窗，刷新一次成本不高，但不用每条通知重算
        @Volatile private var cachedKeywords: List<String> = emptyList()
        @Volatile private var cacheRefreshedAt: Long = 0L
        private const val CACHE_TTL_MS = 60_000L

        fun refreshGovernedCache(context: android.content.Context) {
            val now = System.currentTimeMillis()
            if (now - cacheRefreshedAt < 5_000L) return // 防止刷屏；最短 5 秒刷一次
            val pkgs = PromoGovernSnapshotRepository.getGovernedPackages(context)
                .map { it.lowercase() }
                .toSet()
            val rawKeywords = FeatureSettingsRepository.getNotificationAdBlockKeywords(context)
            val keywords = rawKeywords.split(',', '，', '\n')
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
            cachedGovernedPackages = CopyOnWriteArraySet(pkgs)
            cachedKeywords = keywords
            cacheRefreshedAt = now
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        Log.i(TAG, "Notification listener connected")
        refreshGovernedCache(this)
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        Log.i(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!FeatureSettingsRepository.isNotificationAdBlockEnabled(this)) return
        val packageName = sbn.packageName?.takeIf { it.isNotBlank() } ?: return
        if (shouldSkipOwnNotification(packageName)) return

        // 缓存过期则刷新（兜底用户改了治理列表 / 关键字也会被读到）
        if (System.currentTimeMillis() - cacheRefreshedAt > CACHE_TTL_MS) {
            refreshGovernedCache(this)
        }

        val notification = sbn.notification
        val title = extractTitle(notification)
        val text = extractText(notification)
        if (shouldBlock(packageName, title, text)) {
            runCatching { cancelNotification(sbn.key) }
            Log.d(TAG, "Blocked notification pkg=$packageName title=$title text=$text")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 无需处理
    }

    private fun shouldSkipOwnNotification(packageName: String): Boolean {
        // 自身通知（如 VPN 常驻通知、悬浮球通知）必须放行；系统 HardwareNotification 须放行
        // com.android.* 包通常是系统组件（SystemUI / Settings / phone / systemui），跳过
        return packageName == applicationContext.packageName ||
            packageName == "android" ||
            packageName.startsWith("com.android.") ||
            packageName.startsWith("com.google.android.gms") ||
            packageName.startsWith("com.qualcomm.")
    }

    private fun shouldBlock(packageName: String, title: String?, text: String?): Boolean {
        // 1) 治理名单内的 APP：通知一律拦截（推广治理目标 APP 的通知绝大多数是推广）
        if (cachedGovernedPackages.any { it == packageName.lowercase() }) return true

        // 2) 关键字匹配：促进通用性，避免漏掉尚未纳入治理列表但已经在推广告的 APP
        if (cachedKeywords.isEmpty()) return false
        val titleLower = title?.lowercase().orEmpty()
        val textLower = text?.lowercase().orEmpty()
        return cachedKeywords.any { keyword ->
            titleLower.contains(keyword) || textLower.contains(keyword)
        }
    }

    private fun extractTitle(notification: Notification): String? {
        return notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    }

    private fun extractText(notification: Notification): String? {
        val extras = notification.extras ?: return null
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
    }
}
