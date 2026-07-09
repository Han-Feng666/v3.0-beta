package com.hanfeng.adblocker.shizuku

import android.content.Context
import com.HanFeng.data.WhitelistRepository

object RootHideRepository {
    private const val PREFS = "root_hide_repo"
    private const val KEY_SCOPE_PACKAGES = "root_hide_scope"
    private const val KEY_MODULE_KEYS = "root_hide_module_keys"

    fun getScopePackages(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SCOPE_PACKAGES, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun toggleScope(context: Context, packageName: String, enabled: Boolean) {
        val set = getScopePackages(context).toMutableSet()
        if (enabled) set += packageName else set -= packageName
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SCOPE_PACKAGES, set)
            .apply()
    }

    fun replaceScopePackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SCOPE_PACKAGES, packages.toSet())
            .apply()
    }

    fun getHiddenModuleKeys(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_MODULE_KEYS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun setHiddenModuleKeys(context: Context, keys: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_MODULE_KEYS, keys)
            .apply()
    }

    fun loadInstalledAppsWithHideState(context: Context): List<com.HanFeng.model.InstalledApp> {
        val apps = WhitelistRepository.loadInstalledApps(context, prioritizeCoexist = false)
        val scopePackages = getScopePackages(context)
        return apps.map { it.copy(rootHideSelected = it.packageName in scopePackages) }
    }
}
