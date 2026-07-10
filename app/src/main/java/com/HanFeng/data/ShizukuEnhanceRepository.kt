package com.HanFeng.data

import android.content.Context

object ShizukuEnhanceRepository {
    private const val PREFS = "shizuku_enhance"
    private const val KEY_NETWORK_BLOCKED = "network_blocked_packages"
    private const val KEY_BACKGROUND_RESTRICTED = "background_restricted_packages"
    private const val KEY_HOSTS_DOMAINS = "hosts_domains"

    fun getNetworkBlockedPackages(context: Context): Set<String> {
        return getPackageSet(context, KEY_NETWORK_BLOCKED)
    }

    fun setNetworkBlocked(context: Context, packageName: String, blocked: Boolean) {
        updatePackageSet(context, KEY_NETWORK_BLOCKED, packageName, blocked)
    }

    fun replaceNetworkBlockedPackages(context: Context, packages: Set<String>) {
        replacePackageSet(context, KEY_NETWORK_BLOCKED, packages)
    }

    fun getBackgroundRestrictedPackages(context: Context): Set<String> {
        return getPackageSet(context, KEY_BACKGROUND_RESTRICTED)
    }

    fun setBackgroundRestricted(context: Context, packageName: String, restricted: Boolean) {
        updatePackageSet(context, KEY_BACKGROUND_RESTRICTED, packageName, restricted)
    }

    fun replaceBackgroundRestrictedPackages(context: Context, packages: Set<String>) {
        replacePackageSet(context, KEY_BACKGROUND_RESTRICTED, packages)
    }

    fun getHostsDomains(context: Context): List<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOSTS_DOMAINS, "")
            .orEmpty()
            .lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .distinct()
            .toList()
    }

    fun saveHostsDomains(context: Context, domains: List<String>) {
        val normalized = domains.map { it.trim().lowercase() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .distinct()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOSTS_DOMAINS, normalized.joinToString("\n"))
            .apply()
    }

    private fun getPackageSet(context: Context, key: String): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    private fun updatePackageSet(context: Context, key: String, packageName: String, enabled: Boolean) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return
        val updated = getPackageSet(context, key).toMutableSet()
        if (enabled) updated += normalized else updated -= normalized
        replacePackageSet(context, key, updated)
    }

    private fun replacePackageSet(context: Context, key: String, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(key, packages.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .apply()
    }
}
