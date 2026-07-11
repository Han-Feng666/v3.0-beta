package com.HanFeng.data

import android.content.Context

object GameAntiMarkRepository {
    private const val PREFS = "game_anti_mark"
    private const val KEY_ENABLED = "anti_mark_enabled"
    private const val KEY_AUTO_WATCHER = "anti_mark_auto_watcher"
    private const val KEY_SM8850_DETECTED = "anti_mark_sm8850"
    private const val KEY_TARGET_PACKAGES = "anti_mark_target_packages"

    const val DEFAULT_TARGET_FILE_PATH = "/data/adb/GameAntiMark/target.txt"
    const val STATE_DIR = "/data/adb/GameAntiMark/running_state"
    const val LOG_FILE = "/data/adb/GameAntiMark/watcher.log"
    const val PID_FILE = "/data/adb/GameAntiMark/watcher.pid"
    const val WATCHER_SCRIPT = "/data/adb/GameAntiMark/watcher.sh"
    const val TARGET_DIR = "/mnt/vendor/persist/data"

    val DEFAULT_TARGET_PACKAGES = linkedSetOf(
        "com.activision.callofduty.shooter",
        "com.garena.game.df",
        "com.hottagames.yh.laohu",
        "com.levelinfinite.sgameGlobal",
        "com.levelinfinite.sgameGlobal.midaspay",
        "com.proximabeta.mf.liteuamo",
        "com.proximabeta.mf.uamo",
        "com.proxima.dfm",
        "com.pubg.imobile",
        "com.pubg.krmobile",
        "com.pubg.newstate",
        "com.rekoo.pubgm",
        "com.tencent.af",
        "com.tencent.baiye",
        "com.tencent.game.rhythmmaster",
        "com.tencent.hyrzol",
        "com.tencent.ig",
        "com.tencent.igce",
        "com.tencent.jkchess",
        "com.tencent.KiHan",
        "com.tencent.letsgo",
        "com.tencent.lolm",
        "com.tencent.lolmtyf",
        "com.tencent.mf.uam",
        "com.tencent.nfsonline",
        "com.tencent.nrc",
        "com.tencent.qqgame.xq",
        "com.tencent.stc.cfl",
        "com.tencent.tmgp.cf",
        "com.tencent.tmgp.cfalpha",
        "com.tencent.tmgp.cod",
        "com.tencent.tmgp.codev",
        "com.tencent.tmgp.dfm",
        "com.tencent.tmgp.dnf",
        "com.tencent.tmgp.eyou.eygy",
        "com.tencent.tmgp.gnyx",
        "com.tencent.tmgp.NBA",
        "com.tencent.tmgp.nshm",
        "com.tencent.tmgp.nz",
        "com.tencent.tmgp.party",
        "com.tencent.tmgp.projectg",
        "com.tencent.tmgp.pubgmhd",
        "com.tencent.tmgp.qjnn",
        "com.tencent.tmgp.sgame",
        "com.tencent.tmgp.sgamece",
        "com.tencent.tmgp.speedmobile",
        "com.tencent.tmgp.supercell.clashofclans",
        "com.tencent.tmgp.WePop",
        "com.tencent.YiRen",
        "com.vng.pubgmobile"
    )

    val MRPCS_EXTRA_PACKAGES = setOf(
        "com.levelinfinite.sgameGlobal",
        "com.levelinfinite.sgameGlobal.midaspay",
        "com.tencent.tmgp.sgame",
        "com.tencent.tmgp.sgamece"
    )

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isAutoWatcherEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_WATCHER, false)

    fun setAutoWatcherEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_WATCHER, enabled).apply()
    }

    fun isSm8850Detected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SM8850_DETECTED, false)

    fun setSm8850Detected(context: Context, detected: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SM8850_DETECTED, detected).apply()
    }

    fun getTargetPackages(context: Context): LinkedHashSet<String> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_TARGET_PACKAGES, null)
        return if (saved.isNullOrEmpty()) {
            DEFAULT_TARGET_PACKAGES
        } else {
            linkedSetOf<String>().apply { addAll(saved) }
        }
    }

    fun setTargetPackages(context: Context, packages: LinkedHashSet<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_TARGET_PACKAGES, packages.toSet()).apply()
    }

    fun addPackage(context: Context, packageName: String): Boolean {
        val current = getTargetPackages(context)
        if (packageName.isBlank() || current.contains(packageName)) return false
        current.add(packageName.trim())
        setTargetPackages(context, current)
        return true
    }

    fun removePackage(context: Context, packageName: String): Boolean {
        val current = getTargetPackages(context)
        if (!current.remove(packageName)) return false
        setTargetPackages(context, current)
        return true
    }

    fun resetToDefault(context: Context) {
        setTargetPackages(context, DEFAULT_TARGET_PACKAGES)
    }
}
