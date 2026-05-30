package com.HanFeng.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku

object ShizukuRepository {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    const val REQUEST_CODE = 4096

    data class Status(
        val installed: Boolean,
        val binderAlive: Boolean,
        val permissionGranted: Boolean,
        val runningMode: String
    )

    fun getStatus(context: Context): Status {
        val installed = isShizukuInstalled(context)
        val binderAlive = installed && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permissionGranted = binderAlive &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val runningMode = when {
            !installed -> "未安装"
            !binderAlive -> "未启动"
            !permissionGranted -> "未授权"
            runCatching { Shizuku.getUid() == 0 }.getOrDefault(false) -> "Root"
            runCatching { Shizuku.getUid() == 2000 }.getOrDefault(false) -> "ADB"
            else -> "已连接"
        }
        return Status(installed, binderAlive, permissionGranted, runningMode)
    }

    fun canUseEnhancedMode(context: Context): Boolean {
        val status = getStatus(context)
        return status.installed && status.binderAlive && status.permissionGranted
    }

    fun requestPermission(): Boolean {
        val canRequest = runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED && !Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)
        if (!canRequest) return false
        Shizuku.requestPermission(REQUEST_CODE)
        return true
    }

    fun openDownloadPage(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun isShizukuInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }
}
