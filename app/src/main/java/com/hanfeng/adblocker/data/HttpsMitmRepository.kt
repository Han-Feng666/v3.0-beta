package com.HanFeng.data

import android.content.Context

object HttpsMitmRepository {
    private const val PREFS = "https_mitm_settings"
    private const val KEY_CERT_READY = "cert_ready"
    private const val KEY_CERT_INSTALLED = "cert_installed"
    private const val KEY_CERT_INSTALL_PENDING = "cert_install_pending"
    private const val KEY_CERT_ALIAS = "cert_alias"
    private const val KEY_CERT_PASSWORD = "cert_password"
    private const val KEY_CERT_FILE = "cert_file"
    private const val KEY_CA_KEYSTORE_FILE = "ca_keystore_file"
    private const val KEY_CERT_EXPORT_PATH = "cert_export_path"
    private const val KEY_BYPASS_UNTIL_PREFIX = "bypass_until:"
    private const val KEY_BYPASS_REASON_PREFIX = "bypass_reason:"
    private const val DEFAULT_BYPASS_COOLDOWN_MILLIS = 10 * 60 * 1000L
    @Volatile private var cachedCertificateReady: Boolean? = null
    @Volatile private var cachedCertificateInstalled: Boolean? = null
    @Volatile private var cachedCertificateInstallPending: Boolean? = null

    fun isCertificateReady(context: Context): Boolean {
        cachedCertificateReady?.let { return it }
        return prefs(context).getBoolean(KEY_CERT_READY, false).also { cachedCertificateReady = it }
    }

    fun isCertificateInstalled(context: Context): Boolean {
        cachedCertificateInstalled?.let { return it }
        return prefs(context).getBoolean(KEY_CERT_INSTALLED, false).also { cachedCertificateInstalled = it }
    }

    fun isCertificateInstallPending(context: Context): Boolean {
        cachedCertificateInstallPending?.let { return it }
        return prefs(context).getBoolean(KEY_CERT_INSTALL_PENDING, false).also { cachedCertificateInstallPending = it }
    }

    fun saveCertificateMeta(context: Context, alias: String, password: String, fileName: String, caKeystoreFileName: String) {
        val existingPrefs = prefs(context)
        val alreadyInstalled = existingPrefs.getBoolean(KEY_CERT_INSTALLED, false)
        val installPending = existingPrefs.getBoolean(KEY_CERT_INSTALL_PENDING, false)
        val exportPath = existingPrefs.getString(KEY_CERT_EXPORT_PATH, null)
        prefs(context).edit()
            .putBoolean(KEY_CERT_READY, true)
            .putBoolean(KEY_CERT_INSTALLED, alreadyInstalled)
            .putBoolean(KEY_CERT_INSTALL_PENDING, installPending)
            .putString(KEY_CERT_ALIAS, alias)
            .putString(KEY_CERT_PASSWORD, password)
            .putString(KEY_CERT_FILE, fileName)
            .putString(KEY_CA_KEYSTORE_FILE, caKeystoreFileName)
            .putString(KEY_CERT_EXPORT_PATH, exportPath)
            .apply()
        cachedCertificateReady = true
        cachedCertificateInstalled = alreadyInstalled
        cachedCertificateInstallPending = installPending
    }

    fun markCertificateInstallRequested(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_CERT_READY, true)
            .putBoolean(KEY_CERT_INSTALL_PENDING, true)
            .apply()
        cachedCertificateReady = true
        cachedCertificateInstallPending = true
    }

    fun markCertificateInstalled(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_CERT_READY, true)
            .putBoolean(KEY_CERT_INSTALLED, true)
            .putBoolean(KEY_CERT_INSTALL_PENDING, false)
            .apply()
        cachedCertificateReady = true
        cachedCertificateInstalled = true
        cachedCertificateInstallPending = false
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        cachedCertificateReady = null
        cachedCertificateInstalled = null
        cachedCertificateInstallPending = null
    }

    fun clearRuntimeState(context: Context) {
        val prefs = prefs(context)
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_BYPASS_UNTIL_PREFIX) || it.startsWith(KEY_BYPASS_REASON_PREFIX) }
            .forEach(editor::remove)
        editor.apply()
    }

    fun markBypassCooldown(
        context: Context,
        host: String,
        reason: String,
        cooldownMillis: Long = DEFAULT_BYPASS_COOLDOWN_MILLIS
    ) {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isBlank()) return
        val until = System.currentTimeMillis() + cooldownMillis.coerceAtLeast(30_000L)
        prefs(context).edit()
            .putLong(KEY_BYPASS_UNTIL_PREFIX + normalizedHost, until)
            .putString(KEY_BYPASS_REASON_PREFIX + normalizedHost, reason)
            .apply()
    }

    fun getActiveBypassReason(context: Context, host: String): String? {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isBlank()) return null
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        var currentHost = normalizedHost
        while (currentHost.isNotBlank()) {
            val until = prefs.getLong(KEY_BYPASS_UNTIL_PREFIX + currentHost, 0L)
            if (until > now) {
                return prefs.getString(KEY_BYPASS_REASON_PREFIX + currentHost, null)
            }
            if (until > 0L) {
                clearExpiredBypass(context, currentHost)
            }
            val nextDot = currentHost.indexOf('.')
            if (nextDot < 0 || nextDot >= currentHost.lastIndex) break
            currentHost = currentHost.substring(nextDot + 1)
        }
        return null
    }

    fun isBypassCoolingDown(context: Context, host: String): Boolean {
        return getActiveBypassReason(context, host) != null
    }

    fun pruneExpiredBypassEntries(context: Context) {
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        val expiredHosts = prefs.all.keys
            .asSequence()
            .filter { it.startsWith(KEY_BYPASS_UNTIL_PREFIX) }
            .map { it.removePrefix(KEY_BYPASS_UNTIL_PREFIX) }
            .filter { host -> prefs.getLong(KEY_BYPASS_UNTIL_PREFIX + host, 0L) <= now }
            .toList()
        if (expiredHosts.isEmpty()) return
        val editor = prefs.edit()
        expiredHosts.forEach { host ->
            editor.remove(KEY_BYPASS_UNTIL_PREFIX + host)
            editor.remove(KEY_BYPASS_REASON_PREFIX + host)
        }
        editor.apply()
    }

    fun getCertificateFileName(context: Context): String? = prefs(context).getString(KEY_CERT_FILE, null)

    fun getCaKeystoreFileName(context: Context): String? = prefs(context).getString(KEY_CA_KEYSTORE_FILE, null)

    fun getCertificateExportPath(context: Context): String? = prefs(context).getString(KEY_CERT_EXPORT_PATH, null)

    fun saveCertificateExportPath(context: Context, exportPath: String?) {
        prefs(context).edit().putString(KEY_CERT_EXPORT_PATH, exportPath).apply()
    }

    fun clearCertificateExportPath(context: Context) {
        prefs(context).edit().remove(KEY_CERT_EXPORT_PATH).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clearExpiredBypass(context: Context, host: String) {
        prefs(context).edit()
            .remove(KEY_BYPASS_UNTIL_PREFIX + host)
            .remove(KEY_BYPASS_REASON_PREFIX + host)
            .apply()
    }
}
