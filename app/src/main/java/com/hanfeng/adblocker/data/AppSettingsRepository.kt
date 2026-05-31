package com.HanFeng.data

import android.content.Context

object AppSettingsRepository {
    private const val PREFS = "app_settings"
    private const val KEY_USE_SHIZUKU = "use_shizuku"
    private const val KEY_HIDE_BACKGROUND = "hide_background"

    fun isShizukuEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_SHIZUKU, false)
    }

    fun setShizukuEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_SHIZUKU, enabled)
            .apply()
    }

    fun isHideBackgroundEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BACKGROUND, false)
    }

    fun setHideBackgroundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_BACKGROUND, enabled)
            .apply()
    }
}
