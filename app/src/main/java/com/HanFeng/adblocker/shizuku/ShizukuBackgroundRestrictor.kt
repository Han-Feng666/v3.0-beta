package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

class ShizukuBackgroundRestrictor(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuBgRestrict"

        const val STANDBY_ACTIVE = 10
        const val STANDBY_WORKING = 20
        const val STANDBY_FREQUENT = 30
        const val STANDBY_RARE = 40
        const val STANDBY_RESTRICTED = 50
        const val STANDBY_NEVER = 60

        private fun standbyModeName(mode: Int): String = when (mode) {
            STANDBY_ACTIVE -> "active"
            STANDBY_WORKING -> "working_set"
            STANDBY_FREQUENT -> "frequent"
            STANDBY_RARE -> "rare"
            STANDBY_RESTRICTED -> "restricted"
            STANDBY_NEVER -> "never"
            else -> "frequent"
        }

        private fun parseStandbyOutput(output: String): Int {
            val trimmed = output.trim().lowercase()
            return when {
                trimmed == "10" || trimmed.contains("active") -> STANDBY_ACTIVE
                trimmed == "20" || trimmed.contains("working_set") || trimmed.contains("working") -> STANDBY_WORKING
                trimmed == "30" || trimmed.contains("frequent") -> STANDBY_FREQUENT
                trimmed == "40" || trimmed.contains("rare") -> STANDBY_RARE
                trimmed == "50" || trimmed.contains("restricted") -> STANDBY_RESTRICTED
                trimmed == "60" || trimmed.contains("never") -> STANDBY_NEVER
                else -> STANDBY_FREQUENT
            }
        }
    }

    data class AppBackgroundState(
        val packageName: String,
        val standbyMode: Int,
        val wakeLockEnabled: Boolean,
        val backgroundRunEnabled: Boolean,
        val notificationEnabled: Boolean
    )

    data class FreezeResult(
        val success: Boolean,
        val stepsCompleted: List<String>,
        val stepsFailed: List<String>,
        val originalState: AppBackgroundState?
    )

    private val packageManager = context.packageManager
    private val frozenStates = mutableMapOf<String, AppBackgroundState>()

    fun forceStopPackage(packageName: String): Boolean {
        return try {
            val result = SuSession.getInstance().execute("am force-stop $packageName")
            val success = result.exitCode == 0
            if (success) Log.d(TAG, "Force stopped: $packageName")
            else Log.e(TAG, "Failed to force stop $packageName")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception force stopping $packageName", e)
            false
        }
    }

    fun setAppStandbyMode(packageName: String, mode: Int): Boolean {
        return try {
            val modeName = standbyModeName(mode)
            val result = SuSession.getInstance().execute("pm set-app-standby-bucket $packageName $modeName")
            val success = result.exitCode == 0
            if (success) Log.d(TAG, "Set standby mode $modeName for $packageName")
            else Log.e(TAG, "Failed to set standby mode for $packageName")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting standby mode for $packageName", e)
            false
        }
    }

    fun disableBackgroundWakeup(packageName: String): Boolean {
        return try {
            val wakeLockResult = SuSession.getInstance().execute("appops set $packageName WAKE_LOCK ignore")
            val runAnyResult = SuSession.getInstance().execute("appops set $packageName RUN_ANY_IN_BACKGROUND ignore")
            val success = wakeLockResult.exitCode == 0 || runAnyResult.exitCode == 0
            if (success) Log.d(TAG, "Disabled background wakeup for $packageName")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception disabling background wakeup for $packageName", e)
            false
        }
    }

    fun enableBackgroundWakeup(packageName: String): Boolean {
        return try {
            val wakeLockResult = SuSession.getInstance().execute("appops set $packageName WAKE_LOCK allow")
            val runAnyResult = SuSession.getInstance().execute("appops set $packageName RUN_ANY_IN_BACKGROUND allow")
            val success = wakeLockResult.exitCode == 0 || runAnyResult.exitCode == 0
            if (success) Log.d(TAG, "Enabled background wakeup for $packageName")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception enabling background wakeup for $packageName", e)
            false
        }
    }

    fun saveState(packageName: String): AppBackgroundState {
        return AppBackgroundState(
            packageName = packageName,
            standbyMode = getStandbyMode(packageName),
            wakeLockEnabled = isWakeLockEnabled(packageName),
            backgroundRunEnabled = isBackgroundRunEnabled(packageName),
            notificationEnabled = isNotificationEnabled(packageName)
        )
    }

    fun deepFreeze(packageName: String): FreezeResult {
        val stepsCompleted = mutableListOf<String>()
        val stepsFailed = mutableListOf<String>()

        val originalState = saveState(packageName)
        frozenStates[packageName] = originalState

        if (forceStopPackage(packageName)) stepsCompleted.add("force_stop") else stepsFailed.add("force_stop")

        if (disableBackgroundWakeup(packageName)) stepsCompleted.add("disable_wakeup") else {
            stepsFailed.add("disable_wakeup")
            rollbackDeepFreeze(packageName, originalState)
            return FreezeResult(false, stepsCompleted, stepsFailed, originalState)
        }

        if (setAppStandbyMode(packageName, STANDBY_RESTRICTED)) stepsCompleted.add("standby_restricted") else {
            stepsFailed.add("standby_restricted")
            rollbackDeepFreeze(packageName, originalState)
            return FreezeResult(false, stepsCompleted, stepsFailed, originalState)
        }

        val notifResult = SuSession.getInstance().execute("appops set $packageName POST_NOTIFICATION ignore")
        if (notifResult.exitCode == 0) stepsCompleted.add("disable_notification") else stepsFailed.add("disable_notification")

        Log.d(TAG, "Deep frozen: $packageName, completed=${stepsCompleted.size}, failed=${stepsFailed.size}")
        return FreezeResult(true, stepsCompleted, stepsFailed, originalState)
    }

    fun unfreezePackage(packageName: String): Boolean {
        val savedState = frozenStates.remove(packageName)
        val targetMode = savedState?.standbyMode ?: STANDBY_FREQUENT

        return try {
            val wakeupEnabled = enableBackgroundWakeup(packageName)
            val standbySet = setAppStandbyMode(packageName, targetMode)
            val notificationResult = SuSession.getInstance().execute("appops set $packageName POST_NOTIFICATION allow")
            val notifOk = notificationResult.exitCode == 0

            Log.d(TAG, "Unfrozen: $packageName, restored to mode=$targetMode")
            wakeupEnabled || standbySet || notifOk
        } catch (e: Exception) {
            Log.e(TAG, "Exception unfreezing $packageName", e)
            false
        }
    }

    fun listFrozenApps(): List<String> {
        return frozenStates.keys.toList()
    }

    fun getFrozenState(packageName: String): AppBackgroundState? {
        return frozenStates[packageName]
    }

    fun getStandbyMode(packageName: String): Int {
        return try {
            val result = SuSession.getInstance().execute("pm get-app-standby-bucket $packageName")
            parseStandbyOutput(result.output.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting standby mode for $packageName: ${e.message}")
            STANDBY_FREQUENT
        }
    }

    fun isWakeLockEnabled(packageName: String): Boolean {
        return try {
            val result = SuSession.getInstance().execute("appops get $packageName WAKE_LOCK 2>/dev/null || echo UNKNOWN")
            !result.output.contains("ignore")
        } catch (e: Exception) { true }
    }

    fun isBackgroundRunEnabled(packageName: String): Boolean {
        return try {
            val result = SuSession.getInstance().execute("appops get $packageName RUN_ANY_IN_BACKGROUND 2>/dev/null || echo UNKNOWN")
            !result.output.contains("ignore")
        } catch (e: Exception) { true }
    }

    fun isNotificationEnabled(packageName: String): Boolean {
        return try {
            val result = SuSession.getInstance().execute("appops get $packageName POST_NOTIFICATION 2>/dev/null || echo UNKNOWN")
            !result.output.contains("ignore")
        } catch (e: Exception) { true }
    }

    fun disableAll(packageName: String): Boolean {
        try {
            val cmds = listOf(
                "am force-stop $packageName",
                "appops set $packageName WAKE_LOCK ignore",
                "appops set $packageName RUN_ANY_IN_BACKGROUND ignore",
                "appops set $packageName RUN_IN_BACKGROUND ignore",
                "appops set $packageName POST_NOTIFICATION ignore",
                "pm set-app-standby-bucket $packageName restricted"
            )
            val result = SuSession.getInstance().execute(cmds.joinToString(" ; "))
            return result.exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Exception in disableAll for $packageName", e)
            return false
        }
    }

    private fun rollbackDeepFreeze(packageName: String, originalState: AppBackgroundState) {
        Log.w(TAG, "Rolling back deep freeze for $packageName")
        if (originalState.wakeLockEnabled) enableBackgroundWakeup(packageName)
        setAppStandbyMode(packageName, originalState.standbyMode)
    }
}
