package com.HanFeng.core.network

object RouteCacheMaintenanceSupport {
    fun shouldRunCheck(
        now: Long,
        lastCheckAt: Long,
        minCheckIntervalMillis: Long = 5_000L
    ): Boolean {
        return now - lastCheckAt >= minCheckIntervalMillis
    }

    fun pruneIfDue(
        now: Long,
        lastPruneAt: Long,
        pruneIntervalMillis: Long,
        prune: () -> Unit
    ): Long {
        if (now - lastPruneAt < pruneIntervalMillis) return lastPruneAt
        prune()
        return now
    }
}
