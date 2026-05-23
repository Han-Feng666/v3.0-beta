package com.HanFeng.data

import android.content.Context
import android.content.pm.ApplicationInfo
import com.HanFeng.model.InstalledApp

object WhitelistRepository {
    private const val PREFS = "whitelist_repo"
    private const val KEY_PACKAGES = "packages"

    fun getPackages(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun toggle(context: Context, packageName: String, enabled: Boolean) {
        val set = getPackages(context).toMutableSet()
        if (enabled) set += packageName else set -= packageName
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PACKAGES, set)
            .apply()
    }

    fun loadInstalledApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val white = getPackages(context)
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map {
                InstalledApp(
                    label = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    icon = pm.getApplicationIcon(it),
                    whitelisted = white.contains(it.packageName)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun hasAppListAccess(context: Context): Boolean {
        return runCatching {
            context.packageManager.getInstalledApplications(0)
                .asSequence()
                .any { it.packageName != context.packageName }
        }.getOrDefault(false)
    }
}
