package com.hanfeng.adblocker.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log

/**
 * Shizuku 增强工具 - 强制后台限制
 * 
 * 功能：
 * 1. 强制停止应用后台活动
 * 2. 设置应用待机模式
 * 3. 限制后台唤醒
 */
class ShizukuBackgroundRestrictor(private val context: Context) {
    
    companion object {
        private const val TAG = "ShizukuBgRestrict"
        
        // 待机模式等级
        const val STANDBY_ACTIVE = 10      // 活跃使用
        const val STANDBY_WORKING = 20     // 工作中
        const val STANDBY_FREQUENT = 30    // 频繁使用
        const val STANDBY_RARE = 40        // 偶尔使用
        const val STANDBY_RESTRICTED = 50  // 受限
        const val STANDBY_NEVER = 60       // 从不开启
    }
    
    private val packageManager = context.packageManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    /**
     * 强制停止应用
     */
    fun forceStopPackage(packageName: String): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "am force-stop $packageName").start()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Log.d(TAG, "Force stopped: $packageName")
                true
            } else {
                Log.e(TAG, "Failed to force stop $packageName, exit code: $exitCode")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception force stopping package", e)
            false
        }
    }
    
    /**
     * 设置应用待机模式
     */
    fun setAppStandbyMode(packageName: String, mode: Int): Boolean {
        return try {
            val modeName = when (mode) {
                STANDBY_ACTIVE -> "active"
                STANDBY_WORKING -> "working_set"
                STANDBY_FREQUENT -> "frequent"
                STANDBY_RARE -> "rare"
                STANDBY_RESTRICTED -> "restricted"
                STANDBY_NEVER -> "never"
                else -> "frequent"
            }
            
            val command = "appops set $packageName RUN_IN_BACKGROUND ignore"
            val process = ProcessBuilder("su", "-c", command).start()
            process.waitFor()
            
            val standbyCommand = "pm set-app-standby-bucket $packageName $modeName"
            val standbyProcess = ProcessBuilder("su", "-c", standbyCommand).start()
            val exitCode = standbyProcess.waitFor()
            
            if (exitCode == 0) {
                Log.d(TAG, "Set standby mode $modeName for $packageName")
                true
            } else {
                Log.e(TAG, "Failed to set standby mode")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting standby mode", e)
            false
        }
    }
    
    /**
     * 禁用应用的后台唤醒
     */
    fun disableBackgroundWakeup(packageName: String): Boolean {
        return try {
            // 禁用 WAKE_LOCK
            val process1 = ProcessBuilder("su", "-c", "appops set $packageName WAKE_LOCK ignore").start()
            process1.waitFor()
            
            // 禁用 RUN_ANY_IN_BACKGROUND
            val process2 = ProcessBuilder("su", "-c", "appops set $packageName RUN_ANY_IN_BACKGROUND ignore").start()
            process2.waitFor()
            
            Log.d(TAG, "Disabled background wakeup for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception disabling background wakeup", e)
            false
        }
    }
    
    /**
     * 启用应用的后台唤醒
     */
    fun enableBackgroundWakeup(packageName: String): Boolean {
        return try {
            // 启用 WAKE_LOCK
            val process1 = ProcessBuilder("su", "-c", "appops set $packageName WAKE_LOCK allow").start()
            process1.waitFor()
            
            // 启用 RUN_ANY_IN_BACKGROUND
            val process2 = ProcessBuilder("su", "-c", "appops set $packageName RUN_ANY_IN_BACKGROUND allow").start()
            process2.waitFor()
            
            Log.d(TAG, "Enabled background wakeup for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception enabling background wakeup", e)
            false
        }
    }
    
    /**
     * 应用深度休眠（冻结）
     */
    fun deepFreeze(packageName: String): Boolean {
        return try {
            // 强制停止
            forceStopPackage(packageName)
            
            // 禁用所有后台活动
            disableBackgroundWakeup(packageName)
            setAppStandbyMode(packageName, STANDBY_RESTRICTED)
            
            // 禁用通知（可选）
            val disableNotif = ProcessBuilder("su", "-c", "appops set $packageName POST_NOTIFICATION ignore").start()
            disableNotif.waitFor()
            
            Log.d(TAG, "Deep frozen: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception deep freezing package", e)
            false
        }
    }
    
    /**
     * 解冻应用
     */
    fun unfreezePackage(packageName: String): Boolean {
        return try {
            // 启用所有后台活动
            enableBackgroundWakeup(packageName)
            setAppStandbyMode(packageName, STANDBY_FREQUENT)
            
            // 启用通知
            val enableNotif = ProcessBuilder("su", "-c", "appops set $packageName POST_NOTIFICATION allow").start()
            enableNotif.waitFor()
            
            Log.d(TAG, "Unfrozen: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception unfreezing package", e)
            false
        }
    }
    
    /**
     * 获取应用当前待机模式
     */
    fun getStandbyMode(packageName: String): Int {
        return try {
            val process = ProcessBuilder("pm get-app-standby-bucket $packageName").start()
            val result = process.inputStream.bufferedReader().readText().trim().lowercase()
            
            when {
                result.contains("active") -> STANDBY_ACTIVE
                result.contains("working") -> STANDBY_WORKING
                result.contains("frequent") -> STANDBY_FREQUENT
                result.contains("rare") -> STANDBY_RARE
                result.contains("restrict") -> STANDBY_RESTRICTED
                result.contains("never") -> STANDBY_NEVER
                else -> STANDBY_FREQUENT
            }
        } catch (e: Exception) {
            STANDBY_FREQUENT
        }
    }
}
