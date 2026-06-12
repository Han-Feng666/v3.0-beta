package com.HanFeng.data

import android.content.Context
import android.content.pm.PackageManager

object PromoGovernSnapshotRepository {
    private const val PREFS = "promo_govern_snapshots"
    private const val KEY_PACKAGE = "package"
    private const val KEY_TITLE = "title"
    private const val KEY_ENABLED_STATE = "enabled_state"
    private const val KEY_SUSPENDED = "suspended"
    private const val KEY_NOTIFICATION_TOUCHED = "notification_touched"
    private const val KEY_COMPONENT = "component"
    private const val KEY_COMPONENT_WAS_ENABLED = "component_was_enabled"
    private const val KEY_CREATED_AT = "created_at"

    data class Snapshot(
        val packageName: String,
        val title: String,
        val enabledState: Int,
        val suspended: Boolean,
        val notificationTouched: Boolean,
        val componentName: String,
        val componentWasEnabled: Boolean,
        val createdAt: Long
    )

    fun savePackageSnapshot(context: Context, target: PromoGovernTarget, notificationTouched: Boolean = false) {
        saveSnapshot(
            context = context,
            packageName = target.packageName,
            title = target.title,
            status = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName),
            notificationTouched = notificationTouched,
            componentName = "",
            componentWasEnabled = false
        )
    }

    fun saveComponentSnapshot(
        context: Context,
        packageName: String,
        title: String,
        componentName: String,
        componentWasEnabled: Boolean
    ) {
        saveSnapshot(
            context = context,
            packageName = packageName,
            title = title,
            status = ShizukuAdControlRepository.queryPackageStatus(context, packageName),
            notificationTouched = false,
            componentName = componentName,
            componentWasEnabled = componentWasEnabled
        )
    }

    fun latest(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageName = prefs.getString(KEY_PACKAGE, "").orEmpty()
        if (packageName.isBlank()) return null
        return Snapshot(
            packageName = packageName,
            title = prefs.getString(KEY_TITLE, packageName).orEmpty().ifBlank { packageName },
            enabledState = prefs.getInt(KEY_ENABLED_STATE, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
            suspended = prefs.getBoolean(KEY_SUSPENDED, false),
            notificationTouched = prefs.getBoolean(KEY_NOTIFICATION_TOUCHED, false),
            componentName = prefs.getString(KEY_COMPONENT, "").orEmpty(),
            componentWasEnabled = prefs.getBoolean(KEY_COMPONENT_WAS_ENABLED, false),
            createdAt = prefs.getLong(KEY_CREATED_AT, 0L)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun saveSnapshot(
        context: Context,
        packageName: String,
        title: String,
        status: ShizukuAdControlRepository.PackageControlStatus,
        notificationTouched: Boolean,
        componentName: String,
        componentWasEnabled: Boolean
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_TITLE, title)
            .putInt(KEY_ENABLED_STATE, status.enabledState)
            .putBoolean(KEY_SUSPENDED, status.suspended)
            .putBoolean(KEY_NOTIFICATION_TOUCHED, notificationTouched)
            .putString(KEY_COMPONENT, componentName)
            .putBoolean(KEY_COMPONENT_WAS_ENABLED, componentWasEnabled)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .apply()
    }
}
