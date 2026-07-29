package com.HanFeng.data

import android.content.Context

object AppSettingsRepository {
    private const val PREFS = "app_settings"
    private const val KEY_USE_SHIZUKU = "use_shizuku"
    private const val KEY_HIDE_BACKGROUND = "hide_background"
    private const val KEY_HIDE_BACKGROUND_CONFIGURED = "hide_background_configured"
    private const val KEY_SHIZUKU_STRICT_APP_AD_BLOCK = "shizuku_strict_app_ad_block"

    // 内存缓存：BaseActivity.onCreate/onResume 每次都 read 3 个 SP key，加 @Volatile 内存布尔避免每次走 binder
    @Volatile private var cachedHideBackground: Boolean? = null

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
        cachedHideBackground?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val configured = prefs.getBoolean(KEY_HIDE_BACKGROUND_CONFIGURED, false)
        val enabled = if (!configured) false else prefs.getBoolean(KEY_HIDE_BACKGROUND, false)
        cachedHideBackground = enabled
        return enabled
    }

    fun setHideBackgroundEnabled(context: Context, enabled: Boolean) {
        LogRepository.append(context, "HideBackground setting changed: enabled=$enabled")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_BACKGROUND_CONFIGURED, true)
            .putBoolean(KEY_HIDE_BACKGROUND, enabled)
            .apply()
        cachedHideBackground = enabled
    }

    fun resetHideBackground(context: Context) {
        LogRepository.append(context, "HideBackground setting reset to defaults")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HIDE_BACKGROUND_CONFIGURED)
            .remove(KEY_HIDE_BACKGROUND)
            .apply()
    }

    fun isShizukuStrictAppAdBlockEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHIZUKU_STRICT_APP_AD_BLOCK, true)
    }

    fun setShizukuStrictAppAdBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHIZUKU_STRICT_APP_AD_BLOCK, enabled)
            .apply()
    }
}
