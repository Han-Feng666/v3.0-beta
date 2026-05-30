package com.HanFeng.shizuku

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.Keep

class ShizukuAdControlUserService() : IAdControlService.Stub() {

    private var serviceContext: Context? = null

    @Keep
    constructor(context: Context) : this() {
        serviceContext = context.applicationContext
    }

    override fun ping(): Boolean = true

    override fun disablePackage(packageName: String): Boolean {
        return runPmCommand("disable-user", "--user", "0", packageName)
    }

    override fun enablePackage(packageName: String): Boolean {
        return runPmCommand("enable", packageName)
    }

    override fun suspendPackage(packageName: String): Boolean {
        return runPmCommand("suspend", "--user", "0", packageName)
    }

    override fun unsuspendPackage(packageName: String): Boolean {
        return runPmCommand("unsuspend", "--user", "0", packageName)
    }

    override fun uninstallPackageForUser(packageName: String, userId: Int): Boolean {
        val safeUserId = if (userId >= 0) userId else 0
        return runPmCommand("uninstall", "--user", safeUserId.toString(), packageName)
    }

    override fun isPackageInstalled(packageName: String): Boolean {
        val context = serviceContext ?: return false
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    override fun getPackageEnabledState(packageName: String): Int {
        val context = serviceContext ?: return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        return runCatching {
            context.packageManager.getApplicationEnabledSetting(packageName)
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    override fun isPackageSuspended(packageName: String): Boolean {
        val context = serviceContext ?: return false
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0).applicationInfo?.flags?.let { flags ->
                (flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
            } ?: false
        }.getOrDefault(false)
    }

    override fun destroy() {
        System.exit(0)
    }

    private fun runPmCommand(vararg args: String): Boolean {
        val command = listOf("pm") + args.toList()
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        }.getOrDefault(false)
    }
}
