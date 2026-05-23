package com.HanFeng.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object HttpDecryptRouteRepository {
    private const val PREFS = "http_decrypt_routes"
    private const val KEY_ROUTES = "routes"
    private const val MAX_ROUTE_COUNT = 256
    private val gson = Gson()

    fun getRoutes(context: Context): List<RouteEntry> {
        pruneExpired(context)
        val type = object : TypeToken<List<RouteEntry>>() {}.type
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROUTES, "[]") ?: "[]"
        return gson.fromJson<List<RouteEntry>>(json, type)
            ?.filter { it.ip.isNotBlank() && it.prefixLength in 0..128 }
            ?.sortedBy { it.ip }
            ?: emptyList()
    }

    fun upsertRoutes(context: Context, routes: List<RouteEntry>): Boolean {
        if (routes.isEmpty()) return false
        val now = System.currentTimeMillis()
        val current = getRoutes(context)
            .filter { it.expiresAt > now }
            .associateByTo(linkedMapOf()) { routeKey(it.ip, it.prefixLength) }
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
        val type = object : TypeToken<List<RouteEntry>>() {}.type
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ROUTES, "[]") ?: "[]"
        val current = gson.fromJson<List<RouteEntry>>(json, type) ?: emptyList()
        val now = System.currentTimeMillis()
        val filtered = current.filter { it.expiresAt > now && it.ip.isNotBlank() && it.prefixLength in 0..128 }
        if (filtered.size == current.size) return false
        save(context, filtered.sortedBy { it.ip })
        return true
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, routes: List<RouteEntry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROUTES, gson.toJson(routes))
            .apply()
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
