package com.HanFeng.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object HttpDecryptRouteRepository {
    private const val PREFS = "http_decrypt_routes"
    private const val KEY_ROUTES = "routes"
    private const val MAX_ROUTE_COUNT = 256
    private val gson = Gson()
    @Volatile private var cachedRoutes: List<RouteEntry>? = null
    private val cacheLock = Any()

    fun getRoutes(context: Context): List<RouteEntry> {
        pruneExpired(context)
        cachedRoutes?.let { return it }
        synchronized(cacheLock) {
            cachedRoutes?.let { return it }
            val loaded = readRoutesFromPrefs(context)
            cachedRoutes = loaded
            return loaded
        }
    }

    fun upsertRoutes(context: Context, routes: List<RouteEntry>): Boolean {
        if (routes.isEmpty()) return false
        val now = System.currentTimeMillis()
        val current = synchronized(cacheLock) {
            (cachedRoutes ?: readRoutesFromPrefs(context))
                .filter { it.expiresAt > now }
                .associateByTo(linkedMapOf()) { routeKey(it.ip, it.prefixLength) }
        }
        var changed = false
        routes.forEach { route ->
            if (route.ip.isBlank() || route.prefixLength !in 0..128 || route.expiresAt <= now) return@forEach
            val key = routeKey(route.ip, route.prefixLength)
            val existing = current[key]
            if (existing == null || existing.expiresAt < route.expiresAt || existing.domain != route.domain || existing.vendor != route.vendor) {
                current[key] = route
                changed = true
            }
        }
        if (!changed) return false
        val trimmed = current.values
            .sortedByDescending { it.expiresAt }
            .take(MAX_ROUTE_COUNT)
            .sortedBy { it.ip }
        save(context, trimmed)
        return true
    }

    fun pruneExpired(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val current = synchronized(cacheLock) {
            cachedRoutes ?: readRoutesFromPrefs(context)
        }
        val filtered = current.filter { it.expiresAt > now && it.ip.isNotBlank() && it.prefixLength in 0..128 }
        if (filtered.size == current.size) {
            synchronized(cacheLock) {
                if (cachedRoutes == null) cachedRoutes = current
            }
            return false
        }
        save(context, filtered.sortedBy { it.ip })
        return true
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, routes: List<RouteEntry>) {
        val normalized = routes
            .mapNotNull(::sanitizeRouteEntry)
            .filter { it.ip.isNotBlank() && it.prefixLength in 0..128 }
            .sortedBy { it.ip }
        synchronized(cacheLock) {
            cachedRoutes = normalized
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROUTES, gson.toJson(normalized))
            .apply()
    }

    private fun readRoutesFromPrefs(context: Context): List<RouteEntry> {
        val type = object : TypeToken<List<RouteEntry>>() {}.type
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROUTES, "[]") ?: "[]"
        return gson.fromJson<List<RouteEntry>>(json, type)
            ?.mapNotNull(::sanitizeRouteEntry)
            ?.filter { it.ip.isNotBlank() && it.prefixLength in 0..128 }
            ?.sortedBy { it.ip }
            ?: emptyList()
    }

    private fun sanitizeRouteEntry(route: RouteEntry?): RouteEntry? {
        route ?: return null
        val ip = route.ip.trim()
        if (ip.isEmpty()) return null
        return route.copy(
            ip = ip,
            domain = route.domain.trim(),
            vendor = route.vendor.trim()
        )
    }

    private fun routeKey(ip: String, prefixLength: Int): String = "$ip/$prefixLength"

    data class RouteEntry(
        val ip: String,
        val prefixLength: Int,
        val domain: String,
        val vendor: String,
        val expiresAt: Long
    )
}
