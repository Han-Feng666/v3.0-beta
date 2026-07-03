package com.HanFeng.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.HanFeng.model.PendingFeedbackRule

object FeatureSettingsRepository {
    private const val PREFS = "feature_settings"
    private const val KEY_AD_BLOCK_ENABLED = "ad_block_enabled"
    private const val KEY_HTTP_DECRYPT_ENABLED = "http_decrypt_enabled"
    private const val KEY_VPN_REVOKED_BY_OTHER_VPN = "vpn_revoked_by_other_vpn"
    private const val KEY_FORCE_ENCRYPTED_DNS_FALLBACK = "force_encrypted_dns_fallback"
    private const val KEY_MITM_LEARNING_MODE = "mitm_learning_mode"
    private const val KEY_PROC_NET_OWNER_FALLBACK = "proc_net_owner_fallback"
    private const val KEY_STEALTH_MODE = "stealth_mode"
    private const val KEY_STEALTH_STRIP_TRACKING_PARAMS = "stealth_strip_tracking_params"
    private const val KEY_STEALTH_HIDE_REFERER = "stealth_hide_referer"
    private const val KEY_STEALTH_REMOVE_FINGERPRINT_HEADERS = "stealth_remove_fingerprint_headers"
    private const val KEY_CUSTOM_TRACKING_PARAMS = "custom_tracking_params"
    private const val KEY_CUSTOM_TRACKING_HEADERS = "custom_tracking_headers"
    private const val KEY_PENDING_FEEDBACK_RULES = "pending_feedback_rules"
    private const val KEY_AD_FREE_REWARD_ENABLED = "ad_free_reward_enabled"
    private const val MAX_PENDING_FEEDBACK_RULES = 50
    private val gson = Gson()
    @Volatile private var cachedAdFreeRewardEnabled: Boolean? = null
    @Volatile private var cachedAdBlockEnabled: Boolean? = null
    @Volatile private var cachedHttpDecryptEnabled: Boolean? = null
    @Volatile private var cachedVpnRevokedByOtherVpn: Boolean? = null
    @Volatile private var cachedForceEncryptedDnsFallback: Boolean? = null
    @Volatile private var cachedMitmLearningMode: Boolean? = null
    @Volatile private var cachedProcNetOwnerFallback: Boolean? = null
    @Volatile private var cachedStealthMode: Boolean? = null
    @Volatile private var cachedStealthStripTrackingParams: Boolean? = null
    @Volatile private var cachedStealthHideReferer: Boolean? = null
    @Volatile private var cachedStealthRemoveFingerprintHeaders: Boolean? = null

    fun isAdBlockEnabled(context: Context): Boolean {
        cachedAdBlockEnabled?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AD_BLOCK_ENABLED, false)
            .also { cachedAdBlockEnabled = it }
    }

    fun setAdBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AD_BLOCK_ENABLED, enabled)
            .apply()
        cachedAdBlockEnabled = enabled
    }

    fun isHttpDecryptEnabled(context: Context): Boolean {
        cachedHttpDecryptEnabled?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HTTP_DECRYPT_ENABLED, false)
            .also { cachedHttpDecryptEnabled = it }
    }

    fun setHttpDecryptEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HTTP_DECRYPT_ENABLED, enabled)
            .apply()
        cachedHttpDecryptEnabled = enabled
    }

    fun isVpnRevokedByOtherVpn(context: Context): Boolean {
        cachedVpnRevokedByOtherVpn?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VPN_REVOKED_BY_OTHER_VPN, false)
            .also { cachedVpnRevokedByOtherVpn = it }
    }

    fun setVpnRevokedByOtherVpn(context: Context, revoked: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VPN_REVOKED_BY_OTHER_VPN, revoked)
            .apply()
        cachedVpnRevokedByOtherVpn = revoked
    }

    fun isForceEncryptedDnsFallbackEnabled(context: Context): Boolean {
        cachedForceEncryptedDnsFallback?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_ENCRYPTED_DNS_FALLBACK, true)
            .also { cachedForceEncryptedDnsFallback = it }
    }

    fun setForceEncryptedDnsFallbackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_ENCRYPTED_DNS_FALLBACK, enabled)
            .apply()
        cachedForceEncryptedDnsFallback = enabled
    }

    fun isMitmLearningModeEnabled(context: Context): Boolean {
        cachedMitmLearningMode?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MITM_LEARNING_MODE, false)
            .also { cachedMitmLearningMode = it }
    }

    fun setMitmLearningModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MITM_LEARNING_MODE, enabled)
            .apply()
        cachedMitmLearningMode = enabled
    }

    fun isProcNetOwnerFallbackEnabled(context: Context): Boolean {
        cachedProcNetOwnerFallback?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROC_NET_OWNER_FALLBACK, false)
            .also { cachedProcNetOwnerFallback = it }
    }

    fun setProcNetOwnerFallbackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROC_NET_OWNER_FALLBACK, enabled)
            .apply()
        cachedProcNetOwnerFallback = enabled
    }

    fun isStealthModeEnabled(context: Context): Boolean {
        cachedStealthMode?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEALTH_MODE, false)
            .also { cachedStealthMode = it }
    }

    fun setStealthModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEALTH_MODE, enabled)
            .apply()
        cachedStealthMode = enabled
    }

    fun isStealthStripTrackingParamsEnabled(context: Context): Boolean {
        cachedStealthStripTrackingParams?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEALTH_STRIP_TRACKING_PARAMS, true)
            .also { cachedStealthStripTrackingParams = it }
    }

    fun setStealthStripTrackingParamsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEALTH_STRIP_TRACKING_PARAMS, enabled)
            .apply()
        cachedStealthStripTrackingParams = enabled
    }

    fun isStealthHideRefererEnabled(context: Context): Boolean {
        cachedStealthHideReferer?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEALTH_HIDE_REFERER, true)
            .also { cachedStealthHideReferer = it }
    }

    fun setStealthHideRefererEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEALTH_HIDE_REFERER, enabled)
            .apply()
        cachedStealthHideReferer = enabled
    }

    fun isStealthRemoveFingerprintHeadersEnabled(context: Context): Boolean {
        cachedStealthRemoveFingerprintHeaders?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEALTH_REMOVE_FINGERPRINT_HEADERS, true)
            .also { cachedStealthRemoveFingerprintHeaders = it }
    }

    fun setStealthRemoveFingerprintHeadersEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEALTH_REMOVE_FINGERPRINT_HEADERS, enabled)
            .apply()
        cachedStealthRemoveFingerprintHeaders = enabled
    }

    fun getCustomTrackingParams(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_TRACKING_PARAMS, "") ?: ""
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    }

    fun setCustomTrackingParams(context: Context, params: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_TRACKING_PARAMS, params.joinToString(",") { it.trim().lowercase() })
            .apply()
    }

    fun addCustomTrackingParam(context: Context, param: String) {
        val current = getCustomTrackingParams(context).toMutableSet()
        current += param.trim().lowercase()
        setCustomTrackingParams(context, current)
    }

    fun removeCustomTrackingParam(context: Context, param: String) {
        val current = getCustomTrackingParams(context).toMutableSet()
        current -= param.trim().lowercase()
        setCustomTrackingParams(context, current)
    }

    fun getCustomTrackingHeaders(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_TRACKING_HEADERS, "") ?: ""
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    }

    fun setCustomTrackingHeaders(context: Context, headers: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_TRACKING_HEADERS, headers.joinToString(",") { it.trim().lowercase() })
            .apply()
    }

    fun addCustomTrackingHeader(context: Context, header: String) {
        val current = getCustomTrackingHeaders(context).toMutableSet()
        current += header.trim().lowercase()
        setCustomTrackingHeaders(context, current)
    }

    fun removeCustomTrackingHeader(context: Context, header: String) {
        val current = getCustomTrackingHeaders(context).toMutableSet()
        current -= header.trim().lowercase()
        setCustomTrackingHeaders(context, current)
    }

    fun getPendingFeedbackRules(context: Context): List<PendingFeedbackRule> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_FEEDBACK_RULES, null)
            ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<PendingFeedbackRule>>() {}.type
            gson.fromJson<List<PendingFeedbackRule>>(json, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun addPendingFeedbackRule(context: Context, rule: PendingFeedbackRule) {
        val current = getPendingFeedbackRules(context)
            .filterNot { it.ruleText == rule.ruleText }
        savePendingFeedbackRules(context, (listOf(rule) + current).take(MAX_PENDING_FEEDBACK_RULES))
    }

    fun removePendingFeedbackRule(context: Context, id: String) {
        savePendingFeedbackRules(context, getPendingFeedbackRules(context).filterNot { it.id == id })
    }

    fun isAdFreeRewardEnabled(context: Context): Boolean {
        cachedAdFreeRewardEnabled?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AD_FREE_REWARD_ENABLED, false)
            .also { cachedAdFreeRewardEnabled = it }
    }

    fun setAdFreeRewardEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AD_FREE_REWARD_ENABLED, enabled)
            .apply()
        cachedAdFreeRewardEnabled = enabled
    }

    private fun savePendingFeedbackRules(context: Context, rules: List<PendingFeedbackRule>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_FEEDBACK_RULES, gson.toJson(rules))
            .apply()
    }
}
