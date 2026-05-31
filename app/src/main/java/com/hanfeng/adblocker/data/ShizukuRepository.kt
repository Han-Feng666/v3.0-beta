package com.HanFeng.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku

object ShizukuRepository {
    private val shizukuPackages = listOf(
        "roro.stellar.manager",
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager",
        "moe.shizuku.redirectstorage",
        "moe.shizuku.starter",
        "stellar.shizuku",
        "moe.zeroposed.shizuku",
        "moe.shizuku",
        "rikka.shizuku",
        "dev.rikka.shizuku",
        "com.shizuku.manager",
        "cn.shizuku.manager"
    )
    private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    const val REQUEST_CODE = 4096

    data class Status(
        val installed: Boolean,
        val binderAlive: Boolean,
        val permissionGranted: Boolean,
        val runningMode: String,
        val permissionStateKnown: Boolean
    )

    fun getStatus(context: Context): Status {
        val baseStatus = readBaseStatus(context)
        val serviceUid = if (baseStatus.binderAlive) runCatching { Shizuku.getUid() }.getOrNull() else null
        val userServiceReachable = baseStatus.installed && baseStatus.binderAlive && runCatching {
            ShizukuAdControlRepository.ensureBound(context)
            ShizukuAdControlRepository.checkServiceHealth(context)
        }.getOrDefault(false)
        val runningMode = when {
            !baseStatus.installed -> "未安装"
            !baseStatus.binderAlive -> "未启动"
            userServiceReachable && !baseStatus.permissionGranted -> "UserService"
            !baseStatus.permissionStateKnown -> "权限状态异常"
            !baseStatus.permissionGranted -> "未授权"
            serviceUid == 0 -> "Root"
            serviceUid == 2000 -> "ADB"
            serviceUid != null && serviceUid > 0 -> "UserService"
            else -> "已连接"
        }
        return baseStatus.copy(runningMode = runningMode)
    }

    fun canUseEnhancedMode(context: Context): Boolean {
        val status = readBaseStatus(context)
        if (!status.installed || !status.binderAlive) return false
        if (status.permissionGranted) return true
        return runCatching {
            ShizukuAdControlRepository.ensureBound(context)
            ShizukuAdControlRepository.checkServiceHealth(context)
        }.getOrDefault(false)
    }

    fun canAttemptUserService(context: Context): Boolean {
        val status = readBaseStatus(context)
        return status.installed && status.binderAlive
    }

    fun requestPermission(): Boolean {
        val canRequest = isBinderReachable() && runCatching {
            val permission = Shizuku.checkSelfPermission()
            permission != PackageManager.PERMISSION_GRANTED && !Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)
        if (!canRequest) return false
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    fun openDownloadPage(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun isShizukuInstalled(context: Context): Boolean {
        return shizukuPackages.any { packageName ->
            runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        } || runCatching {
            context.packageManager.getInstalledPackages(0).any { installedPackage ->
                val packageName = installedPackage.packageName.orEmpty().lowercase()
                packageName.contains("shizuku") ||
                    packageName.contains("stellar.manager") ||
                    packageName.contains("privileged.api")
            }
        }.getOrDefault(false)
    }

    private fun isBinderReachable(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false) ||
            runCatching { Shizuku.getBinder()?.pingBinder() == true }.getOrDefault(false) ||
            runCatching { Shizuku.getBinder()?.isBinderAlive == true }.getOrDefault(false) ||
            runCatching { Shizuku.getVersion() > 0 }.getOrDefault(false) ||
            runCatching { Shizuku.getUid() > 0 }.getOrDefault(false)
    }

    private fun readBaseStatus(context: Context): Status {
        val binderAlive = isBinderReachable()
        val installed = binderAlive || isShizukuInstalled(context)
        val permissionCheckResult = if (binderAlive) {
            runCatching { Shizuku.checkSelfPermission() }
        } else {
            null
        }
        val permissionStateKnown = permissionCheckResult?.isSuccess == true
        val permissionGranted = permissionCheckResult?.getOrNull() == PackageManager.PERMISSION_GRANTED
        return Status(
            installed = installed,
            binderAlive = binderAlive,
            permissionGranted = permissionGranted,
            runningMode = "",
            permissionStateKnown = permissionStateKnown
        )
    }
}
