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
        val userServiceReachable = baseStatus.installed && baseStatus.binderAlive &&
            ShizukuAdControlRepository.isServiceAlive()
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
        return ShizukuAdControlRepository.isServiceAlive()
    }

    fun canAttemptUserService(context: Context): Boolean {
        val status = readBaseStatus(context)
        return status.installed && status.binderAlive
    }

    fun requestPermission(): Boolean {
        if (!isBinderReachable()) return false
        val permission = runCatching { Shizuku.checkSelfPermission() }.getOrNull()
        if (permission == PackageManager.PERMISSION_GRANTED) return false
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
        // A 路线:本 app 内置 starter + server,不需要外部 Shizuku APK
        // 主 app 自己就是 Shizuku 管理端,视为已安装
        if (com.HanFeng.adblocker.shizuku.BuiltInShizukuStarter.isShizukuBuiltin()) return true
        if (com.HanFeng.adblocker.shizuku.BuiltInShizukuStarter.isShizukuAppInstalled(context)) return true
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

    @Volatile private var cachedBinderAlive: Boolean? = null
    @Volatile private var cachedBinderAliveAtMs: Long = 0L
    private const val BINDER_ALIVE_CACHE_TTL_MS = 200L

    fun isBinderReachable(): Boolean {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val cached = cachedBinderAlive
        if (cached != null && nowMs - cachedBinderAliveAtMs < BINDER_ALIVE_CACHE_TTL_MS) {
            return cached
        }
        // 单 pingBinder() 已经够：它本身就是 binder transact，alive=true 立即返回，dead 立即 false
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        cachedBinderAlive = alive
        cachedBinderAliveAtMs = nowMs
        return alive
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
