package com.hanfeng.adblocker.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.lang.reflect.Method

/**
 * Shizuku 增强工具 - 应用网络权限控制
 * 
 * 功能：
 * 1. 禁用应用的网络权限（阻止联网）
 * 2. 启用应用的网络权限
 * 3. 批量管理流氓应用
 */
class ShizukuNetworkPermissionController(private val context: Context) {
    
    companion object {
        private const val TAG = "ShizukuNetPerm"
        
        // 网络权限相关的 App Ops
        private const val OP_INTERNET = 106  // OP_INTERNET
    }
    
    data class AppNetworkStatus(
        val packageName: String,
        val appName: String,
        val networkEnabled: Boolean,
        val isSystemApp: Boolean
    )
    
    private lateinit var packageManager: PackageManager
    private var appOpsClass: Class<*>? = null
    private var appOpsInstance: Any? = null
    private var setUidModeMethod: Method? = null
    private var noteProxyOpMethod: Method? = null
    
    init {
        packageManager = context.packageManager
        initializeAppOps()
    }
    
    /**
     * 初始化 AppOps 反射
     */
    private fun initializeAppOps() {
        try {
            // 获取 AppOpsManager 类
            val appOpsClassName = "android.app.AppOpsManager"
            appOpsClass = Class.forName(appOpsClassName)
            
            // 通过 Shizuku 获取 AppOpsService
            // 这里需要 ShizukuBinderWrapper 来调用系统服务
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AppOps", e)
        }
    }
    
    /**
     * 禁用应用网络权限
     */
    fun disableNetwork(packageName: String): Boolean {
        return setNetworkPermission(packageName, false)
    }
    
    /**
     * 启用应用网络权限
     */
    fun enableNetwork(packageName: String): Boolean {
        return setNetworkPermission(packageName, true)
    }
    
    /**
     * 设置网络权限
     */
    private fun setNetworkPermission(packageName: String, enabled: Boolean): Boolean {
        return try {
            val uid = packageManager.getApplicationInfo(packageName, 0).uid
            
            // 使用 app_ops set 命令
            val command = if (enabled) {
                "appops set $packageName INTERNET allow"
            } else {
                "appops set $packageName INTERNET ignore"
            }
            
            val process = ProcessBuilder("su", "-c", command).start()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Log.d(TAG, "Network ${if (enabled) "enabled" else "disabled"} for $packageName")
                true
            } else {
                Log.e(TAG, "Failed to set network permission, exit code: $exitCode")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting network permission", e)
            false
        }
    }
    
    /**
     * 检查应用网络权限状态
     */
    fun isNetworkEnabled(packageName: String): Boolean {
        return try {
            val process = ProcessBuilder("appops get $packageName INTERNET").start()
            val result = process.inputStream.bufferedReader().readText()
            result.contains("allow", ignoreCase = true) && !result.contains("ignore", ignoreCase = true)
        } catch (e: Exception) {
            true // 默认允许
        }
    }
    
    /**
     * 获取应用网络状态
     */
    fun getAppNetworkStatus(packageName: String): AppNetworkStatus {
        val appInfo = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return AppNetworkStatus(
                packageName = packageName,
                appName = "Unknown",
                networkEnabled = true,
                isSystemApp = false
            )
        }
        
        val appName = packageManager.getApplicationLabel(appInfo).toString()
        val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        
        return AppNetworkStatus(
            packageName = packageName,
            appName = appName,
            networkEnabled = isNetworkEnabled(packageName),
            isSystemApp = isSystemApp
        )
    }
    
    /**
     * 获取所有已安装应用的网络状态列表
     */
    fun getAllAppsNetworkStatus(): List<AppNetworkStatus> {
        val apps = packageManager.getInstalledApplications(0)
        return apps.map { appInfo ->
            AppNetworkStatus(
                packageName = appInfo.packageName,
                appName = appInfo.loadLabel(packageManager).toString(),
                networkEnabled = isNetworkEnabled(appInfo.packageName),
                isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.sortedWith(compareBy({ !it.isSystemApp }, { it.appName }))
    }
    
    /**
     * 批量禁用多个应用的网络
     */
    fun batchDisableNetworks(packageNames: List<String>): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        packageNames.forEach { packageName ->
            results[packageName] = disableNetwork(packageName)
        }
        return results
    }
    
    /**
     * 恢复所有被禁用的应用网络
     */
    fun restoreAllNetworks(packageNames: List<String>): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        packageNames.forEach { packageName ->
            results[packageName] = enableNetwork(packageName)
        }
        return results
    }
}
