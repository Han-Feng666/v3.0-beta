package com.HanFeng.data

import android.content.Context
import android.content.pm.ApplicationInfo
import com.HanFeng.model.InstalledApp
import com.HanFeng.model.LocalProxyCoexistConfig
import com.HanFeng.model.LocalProxySuggestion
import com.google.gson.Gson

object WhitelistRepository {
    private const val PREFS = "whitelist_repo"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_COEXIST_PACKAGES = "coexist_packages"
    private const val KEY_LOCAL_PROXY_COEXIST = "local_proxy_coexist"
    @Volatile private var cachedDisallowedPackages: Set<String>? = null
    @Volatile private var cachedInstalledApps: List<CachedInstalledApp>? = null
    private val gson = Gson()
    private val coexistKeywordHints = listOf(
        "加速", "加速器", "游戏空间", "游戏助手", "网络加速", "手游加速", "vpn", "proxy", "tunnel",
        "booster", "accelerator", "game booster", "代理", "翻墙", "专线", "节点", "隧道"
    )
    private val coexistPackageHints = listOf(
        "uu", "biubiu", "xunyou", "qiyou", "leigod", "ourplay", "ccspeed", "vpn", "proxy",
        "gamebooster", "game_booster", "accelerator", "speed", "clash", "v2ray", "wireguard",
        "openvpn", "surfshark", "expressvpn", "nordvpn", "outline", "singbox", "sing-box",
        "shadowsocks", "ssr", "trojan", "hiddify", "nekobox", "loon", "stash", "quantumult"
    )
    private val coexistFamilyHints = listOf(
        listOf("clash", "meta", "mihomo"),
        listOf("v2ray", "xray", "nekobox", "singbox", "sing-box", "hiddify", "trojan", "shadowsocks", "ssr"),
        listOf("wireguard", "wg"),
        listOf("openvpn", "ovpn"),
        listOf("outline"),
        listOf("loon"),
        listOf("stash"),
        listOf("quantumult"),
        listOf("uu", "netease.uu"),
        listOf("biubiu"),
        listOf("xunyou"),
        listOf("qiyou"),
        listOf("leigod"),
        listOf("ourplay"),
        listOf("gamebooster", "game_booster")
    )
    private val localProxyPortHints = listOf(
        ProxyPortHint(listOf("clash", "meta", "mihomo"), listOf(7890, 7891, 7892), "Clash/Mihomo 常见本地代理端口"),
        ProxyPortHint(listOf("singbox", "sing-box", "sfa", "nekobox", "v2ray", "xray", "hiddify"), listOf(1080, 10808, 2080, 7890), "V2Ray/sing-box 常见本地代理端口"),
        ProxyPortHint(listOf("shadowsocks", "ssr", "trojan"), listOf(1080, 1081, 8388), "Shadowsocks/Trojan 常见本地代理端口"),
        ProxyPortHint(listOf("surfboard"), listOf(6152, 7890), "Surfboard 常见本地代理端口"),
        ProxyPortHint(listOf("loon", "stash", "quantumult"), listOf(7890, 7891, 9090), "代理工具常见本地代理端口")
    )

    fun getPackages(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun getDisallowedPackages(context: Context): Set<String> {
        cachedDisallowedPackages?.let { return it }
        val installedPackages = getCachedInstalledApps(context)
            .asSequence()
            .map { it.packageName }
            .toSet()
        val whitelist = sanitizeSelectedPackages(context, KEY_PACKAGES, installedPackages)
        val coexist = getDirectCoexistPackages(context, installedPackages)
        return linkedSetOf<String>().apply {
            addAll(whitelist)
            addAll(coexist)
        }.also { cachedDisallowedPackages = it }
    }

    fun toggle(context: Context, packageName: String, enabled: Boolean) {
        val set = getPackages(context).toMutableSet()
        if (enabled) set += packageName else set -= packageName
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PACKAGES, set)
            .apply()
        cachedDisallowedPackages = null
    }

    fun replacePackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PACKAGES, packages.toSet())
            .apply()
        cachedDisallowedPackages = null
    }

    fun getCoexistPackages(context: Context): Set<String> {
        val installedPackages = getCachedInstalledApps(context)
            .asSequence()
            .map { it.packageName }
            .toSet()
        return getDisplayCoexistPackages(context, installedPackages)
    }

    fun getLocalProxyTargetPackages(context: Context): Set<String> {
        val installedPackages = getCachedInstalledApps(context)
            .asSequence()
            .map { it.packageName }
            .toSet()
        return getLocalProxyTargetPackages(context, installedPackages)
    }

    fun toggleCoexistPackage(context: Context, packageName: String, enabled: Boolean) {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_COEXIST_PACKAGES, emptySet())
            ?.toMutableSet()
            ?: linkedSetOf()
        if (enabled) set += packageName else set -= packageName
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_COEXIST_PACKAGES, set)
            .apply()
        cachedDisallowedPackages = null
    }

    fun replaceCoexistPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_COEXIST_PACKAGES, packages.toSet())
            .apply()
        cachedDisallowedPackages = null
    }

    fun getLocalProxyCoexistConfig(context: Context): LocalProxyCoexistConfig {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCAL_PROXY_COEXIST, null)
            ?: return LocalProxyCoexistConfig()
        return runCatching {
            gson.fromJson(json, LocalProxyCoexistConfig::class.java)
        }.getOrNull() ?: LocalProxyCoexistConfig()
    }

    fun saveLocalProxyCoexistConfig(context: Context, config: LocalProxyCoexistConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCAL_PROXY_COEXIST, gson.toJson(config))
            .apply()
        cachedDisallowedPackages = null
    }

    fun detectLocalProxySuggestions(context: Context): List<LocalProxySuggestion> {
        val installedApps = getCachedInstalledApps(context).map { it.label to it.packageName }
        return installedApps.flatMap { (label, packageName) ->
            val normalizedLabel = label.lowercase()
            val normalizedPackage = packageName.lowercase()
            localProxyPortHints.filter { hint ->
                hint.tokens.any { token -> normalizedLabel.contains(token) || normalizedPackage.contains(token) }
            }.flatMap { hint ->
                hint.ports.map { port ->
                    LocalProxySuggestion(
                        appLabel = label,
                        packageName = packageName,
                        host = "127.0.0.1",
                        port = port,
                        reason = hint.reason
                    )
                }
            }
        }.distinctBy { "${it.packageName}:${it.host}:${it.port}" }
            .sortedWith(compareBy<LocalProxySuggestion> { it.appLabel.lowercase() }.thenBy { it.port })
    }

    fun loadInstalledApps(context: Context, prioritizeCoexist: Boolean = false): List<InstalledApp> {
        val installedApps = getCachedInstalledApps(context)
        val installedPackages = installedApps.asSequence().map { it.packageName }.toSet()
        val white = sanitizeSelectedPackages(context, KEY_PACKAGES, installedPackages)
        val coexist = getDisplayCoexistPackages(context, installedPackages)
        return installedApps
            .asSequence()
            .map {
                val coexistRecommended = prioritizeCoexist && isRecommendedCoexistApp(it.label, it.packageName)
                Triple(
                    InstalledApp(
                        label = it.label,
                        packageName = it.packageName,
                        icon = it.icon,
                        whitelisted = white.contains(it.packageName),
                        coexistSelected = coexist.contains(it.packageName),
                        coexistRecommended = coexistRecommended
                    ),
                    it.isSystemApp,
                    coexistRecommended
                )
            }
            .filter { (_, isSystemApp, coexistRecommended) -> !isSystemApp || coexistRecommended }
            .sortedWith(
                compareByDescending<Triple<InstalledApp, Boolean, Boolean>> { prioritizeCoexist && it.first.coexistSelected }
                    .thenByDescending { prioritizeCoexist && it.first.coexistRecommended }
                    .thenBy { it.first.label.lowercase() }
            )
            .map { it.first }
            .toList()
    }

    private fun sanitizeSelectedPackages(context: Context, key: String, installedPackages: Set<String>): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(key, emptySet())?.toSet().orEmpty()
        val sanitized = current.filterTo(linkedSetOf()) { it in installedPackages }
        if (sanitized != current) {
            prefs.edit().putStringSet(key, sanitized).apply()
        }
        return sanitized
    }

    private fun getDisplayCoexistPackages(context: Context, installedPackages: Set<String>): Set<String> {
        val manualSelected = sanitizeSelectedPackages(context, KEY_COEXIST_PACKAGES, installedPackages)
        return linkedSetOf<String>().apply {
            addAll(getDirectCoexistPackages(context, installedPackages))
            addAll(getLocalProxyTargetPackages(context, installedPackages))
            addAll(manualSelected)
        }
    }

    private fun getDirectCoexistPackages(context: Context, installedPackages: Set<String>): Set<String> {
        val manualSelected = sanitizeSelectedPackages(context, KEY_COEXIST_PACKAGES, installedPackages)
        val config = getLocalProxyCoexistConfig(context)
        if (!config.enabled) {
            return expandCoexistPackages(installedPackages, manualSelected)
        }
        val controllerPackages = identifyLocalProxyControllerPackages(context, installedPackages, manualSelected, config)
        return expandCoexistPackages(installedPackages, controllerPackages)
    }

    private fun getLocalProxyTargetPackages(context: Context, installedPackages: Set<String>): Set<String> {
        val manualSelected = sanitizeSelectedPackages(context, KEY_COEXIST_PACKAGES, installedPackages)
        val config = getLocalProxyCoexistConfig(context)
        if (!config.enabled) return emptySet()
        val controllerPackages = identifyLocalProxyControllerPackages(context, installedPackages, manualSelected, config)
        return manualSelected.filterTo(linkedSetOf()) { it in installedPackages && it !in controllerPackages }
    }

    private fun identifyLocalProxyControllerPackages(
        context: Context,
        installedPackages: Set<String>,
        manualSelected: Set<String>,
        config: LocalProxyCoexistConfig
    ): Set<String> {
        val manualControllerPackage = config.controllerPackageName?.trim().orEmpty()
        if (manualControllerPackage.isNotBlank() && manualControllerPackage in installedPackages) {
            return setOf(manualControllerPackage)
        }
        val detectedPackageName = config.detectedPackageName?.trim().orEmpty()
        if (detectedPackageName.isNotBlank() && detectedPackageName in installedPackages) {
            return setOf(detectedPackageName)
        }
        val packageManager = context.packageManager
        return manualSelected.filterTo(linkedSetOf()) { packageName ->
            if (packageName !in installedPackages) return@filterTo false
            val label = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
            isRecommendedCoexistApp(label, packageName)
        }
    }

    private fun isRecommendedCoexistApp(label: String, packageName: String): Boolean {
        val normalizedLabel = label.lowercase()
        val normalizedPackage = packageName.lowercase()
        return coexistKeywordHints.any { hint ->
            normalizedLabel.contains(hint.lowercase())
        } || coexistPackageHints.any { hint ->
            normalizedPackage.contains(hint)
        }
    }

    private fun expandCoexistPackages(installedPackages: Set<String>, selectedPackages: Set<String>): Set<String> {
        if (selectedPackages.isEmpty()) return emptySet()
        val normalizedSelected = selectedPackages.mapTo(linkedSetOf()) { it.lowercase() }
        val derivedTokens = normalizedSelected
            .flatMapTo(linkedSetOf()) { packageName -> deriveCoexistTokens(packageName) }
        val matchedFamilyTokens = coexistFamilyHints
            .filter { family ->
                family.any { token -> normalizedSelected.any { selected -> selected.contains(token) } }
            }
            .flatten()
            .distinct()
        val allTokens = (matchedFamilyTokens + derivedTokens).distinct()
        if (allTokens.isEmpty()) return selectedPackages
        return installedPackages.filterTo(linkedSetOf()) { packageName ->
            val normalizedPackage = packageName.lowercase()
            normalizedPackage in normalizedSelected || allTokens.any(normalizedPackage::contains)
        }
    }

    private fun deriveCoexistTokens(packageName: String): Set<String> {
        val labels = packageName.lowercase().split('.', '-', '_')
        return labels.filterTo(linkedSetOf()) { token ->
            token.length >= 4 &&
                token !in commonPackageSegments &&
                token.any(Char::isLetter)
        }
    }

    private val commonPackageSegments = setOf(
        "com", "net", "org", "android", "app", "apps", "mobile", "client", "global",
        "service", "services", "proxy", "vpn", "tunnel", "release", "debug"
    )

    private data class ProxyPortHint(
        val tokens: List<String>,
        val ports: List<Int>,
        val reason: String
    )

    fun hasAppListAccess(context: Context): Boolean {
        return runCatching {
            getCachedInstalledApps(context)
                .asSequence()
                .any()
        }.getOrDefault(false)
    }

    private fun getCachedInstalledApps(context: Context): List<CachedInstalledApp> {
        cachedInstalledApps?.let { return it }
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { app ->
                CachedInstalledApp(
                    label = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName,
                    icon = pm.getApplicationIcon(app),
                    isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
            .also { cachedInstalledApps = it }
    }

    private data class CachedInstalledApp(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable,
        val isSystemApp: Boolean
    )
}
