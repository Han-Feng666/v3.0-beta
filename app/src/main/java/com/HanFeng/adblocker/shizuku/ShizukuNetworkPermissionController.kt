package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

class ShizukuNetworkPermissionController(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuNetPerm"
    }

    data class AppNetworkStatus(
        val packageName: String,
        val appName: String,
        val networkEnabled: Boolean,
        val wifiEnabled: Boolean,
        val mobileEnabled: Boolean,
        val isSystemApp: Boolean
    )

    data class BatchResult(
        val success: List<String>,
        val failed: List<String>,
        val errors: Map<String, String>
    )

    private val packageManager: PackageManager = context.packageManager

    fun disableNetwork(packageName: String): Boolean {
        return setNetworkPermission(packageName, false)
    }

    fun enableNetwork(packageName: String): Boolean {
        return setNetworkPermission(packageName, true)
    }

    fun toggleNetwork(packageName: String): Boolean {
        val current = isNetworkEnabled(packageName)
        return setNetworkPermission(packageName, !current)
    }

    private fun setNetworkPermission(packageName: String, enabled: Boolean): Boolean {
        return try {
            val action = if (enabled) "allow" else "ignore"
            val result = SuSession.getInstance().execute("appops set $packageName INTERNET $action")
            val success = result.exitCode == 0
            if (success) {
                Log.d(TAG, "Network ${if (enabled) "enabled" else "disabled"} for $packageName")
            } else {
                Log.e(TAG, "Failed to set network permission for $packageName: ${result.output}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting network permission for $packageName", e)
            false
        }
    }

    fun isNetworkEnabled(packageName: String): Boolean {
        return try {
            val result = SuSession.getInstance().execute("appops get $packageName INTERNET")
            if (result.exitCode != 0) {
                Log.w(TAG, "Failed to query network status for $packageName: ${result.output}")
                return true
            }
            result.output.contains("allow", ignoreCase = true) &&
                !result.output.contains("ignore", ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking network status for $packageName: ${e.message}")
            true
        }
    }

    fun getAppNetworkStatus(packageName: String): AppNetworkStatus {
        val appInfo = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return AppNetworkStatus(
                packageName = packageName,
                appName = "Unknown",
                networkEnabled = true,
                wifiEnabled = true,
                mobileEnabled = true,
                isSystemApp = false
            )
        }

        val appName = packageManager.getApplicationLabel(appInfo).toString()
        val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

        return AppNetworkStatus(
            packageName = packageName,
            appName = appName,
            networkEnabled = isNetworkEnabled(packageName),
            wifiEnabled = isWifiEnabled(packageName),
            mobileEnabled = isMobileDataEnabled(packageName),
            isSystemApp = isSystemApp
        )
    }

    fun getAllAppsNetworkStatus(): List<AppNetworkStatus> {
        val apps = packageManager.getInstalledApplications(0)
        return apps.map { appInfo ->
            AppNetworkStatus(
                packageName = appInfo.packageName,
                appName = appInfo.loadLabel(packageManager).toString(),
                networkEnabled = isNetworkEnabled(appInfo.packageName),
                wifiEnabled = isWifiEnabled(appInfo.packageName),
                mobileEnabled = isMobileDataEnabled(appInfo.packageName),
                isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.sortedWith(compareBy({ !it.isSystemApp }, { it.appName }))
    }

    fun batchGetNetworkStatus(packageNames: List<String>): List<AppNetworkStatus> {
        if (packageNames.size <= 5) {
            return packageNames.map { getAppNetworkStatus(it) }
        }

        val query = packageNames.joinToString(" ") { pkg -> pkg }
        val result = SuSession.getInstance().execute(
            "for p in $query; do " +
            "echo \"\${p}:\$(appops get \$p INTERNET 2>/dev/null || echo UNKNOWN)\"; " +
            "done"
        )
        val statusMap = result.output.lines().filter { it.isNotBlank() }.associate { line ->
            val parts = line.split(":", limit = 2)
            parts[0] to (if (parts.getOrElse(1) { "" }.contains("allow") && !parts[1].contains("ignore")) true else false)
        }

        return packageNames.map { pkg ->
            val appInfo = try {
                packageManager.getApplicationInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) { null }
            AppNetworkStatus(
                packageName = pkg,
                appName = appInfo?.loadLabel(packageManager)?.toString() ?: "Unknown",
                networkEnabled = statusMap[pkg] ?: true,
                wifiEnabled = statusMap["$pkg:wifi"] ?: true,
                mobileEnabled = statusMap["$pkg:mobile"] ?: true,
                isSystemApp = appInfo?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false
            )
        }
    }

    fun batchDisableNetworks(packageNames: List<String>): BatchResult {
        val success = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val errors = mutableMapOf<String, String>()
        for (pkg in packageNames) {
            if (disableNetwork(pkg)) success.add(pkg) else {
                failed.add(pkg)
                errors[pkg] = "操作失败"
            }
        }
        return BatchResult(success, failed, errors)
    }

    fun batchDisableNetworksBulk(packageNames: List<String>): Boolean {
        val cmds = packageNames.joinToString(" ; ") { "appops set $it INTERNET ignore" }
        val result = SuSession.getInstance().execute(cmds)
        return result.exitCode == 0
    }

    fun restoreAllNetworks(packageNames: List<String>): BatchResult {
        val success = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val errors = mutableMapOf<String, String>()
        for (pkg in packageNames) {
            if (enableNetwork(pkg)) success.add(pkg) else {
                failed.add(pkg)
                errors[pkg] = "恢复失败"
            }
        }
        return BatchResult(success, failed, errors)
    }

    fun restoreAllNetworksBulk(packageNames: List<String>): Boolean {
        val cmds = packageNames.joinToString(" ; ") { "appops set $it INTERNET allow" }
        val result = SuSession.getInstance().execute(cmds)
        return result.exitCode == 0
    }

    fun getNetworkStats(packageName: String): NetworkStats? {
        val result = SuSession.getInstance().execute(
            "cat /proc/net/xt_qtaguid/stats 2>/dev/null | grep $(grep $packageName /proc/net/xt_qtaguid/stats 2>/dev/null || true) | awk '{s+=\$6}END{print s}' || echo 0"
        )
        val totalBytes = result.output.trim().toLongOrNull() ?: 0
        if (totalBytes == 0L) return null
        return NetworkStats(packageName, totalBytes, 0, 0)
    }

    data class NetworkStats(
        val packageName: String,
        val totalBytes: Long,
        val wifiBytes: Long,
        val mobileBytes: Long
    )

    private fun isWifiEnabled(packageName: String): Boolean {
        return try {
            val result = SuSession.getInstance().execute("appops get $packageName OP_WIFI_CHANGED 2>/dev/null || echo UNKNOWN")
            !result.output.contains("ignore")
        } catch (e: Exception) { true }
    }

    private fun isMobileDataEnabled(packageName: String): Boolean {
        return try {
            val result = SuSession.getInstance().execute("appops get $packageName OP_DATA_CONNECT_CHANGE 2>/dev/null || echo UNKNOWN")
            !result.output.contains("ignore")
        } catch (e: Exception) { true }
    }
}
