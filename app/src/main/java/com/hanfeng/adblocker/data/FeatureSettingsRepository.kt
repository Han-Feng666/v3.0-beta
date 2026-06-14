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
    private const val KEY_PENDING_FEEDBACK_RULES = "pending_feedback_rules"
    private const val MAX_PENDING_FEEDBACK_RULES = 50
    private val gson = Gson()
    @Volatile private var cachedAdBlockEnabled: Boolean? = null
    @Volatile private var cachedHttpDecryptEnabled: Boolean? = null
    @Volatile private var cachedVpnRevokedByOtherVpn: Boolean? = null
    @Volatile private var cachedForceEncryptedDnsFallback: Boolean? = null
    @Volatile private var cachedMitmLearningMode: Boolean? = null
    @Volatile private var cachedProcNetOwnerFallback: Boolean? = null

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

    private fun savePendingFeedbackRules(context: Context, rules: List<PendingFeedbackRule>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_FEEDBACK_RULES, gson.toJson(rules))
            .apply()
    }
}
