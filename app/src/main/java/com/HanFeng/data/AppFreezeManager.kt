package com.HanFeng.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

object AppFreezeManager {

    data class FreezeEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val systemApp: Boolean,
        val frozen: Boolean,
        val suspended: Boolean,
        val critical: Boolean
    )

    private val CRITICAL_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.providers.contacts",
        "com.android.providers.telephony",
        "com.android.providers.media",
        "com.android.settings",
        "com.miui.home",
        "com.android.launcher3",
        "com.huawei.android.launcher",
        "com.heytap.customizehome",
        "com.vivo.home",
        "com.samsung.android.onehome",
        "com.android.inputmethod",
        "com.android.defcontainer",
        "com.android.shell",
        "com.android.systemui.plugin",
        "com.google.android.gms",
        "com.google.android.gsf"
    )

    fun isCritical(packageName: String): Boolean = packageName in CRITICAL_PACKAGES

    fun loadAllEntries(context: Context): List<FreezeEntry> {
        val pm = context.packageManager
        val selfPackage = context.packageName
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        val apps = runCatching { pm.getInstalledApplications(flags) }.getOrDefault(emptyList())
        return apps.asSequence()
            .filter { it.packageName != selfPackage && it.packageName.isNotBlank() }
            .mapNotNull { appInfo -> buildEntry(context, appInfo) }
            .sortedWith(
                compareBy<FreezeEntry> { it.critical }
                    .thenBy { it.frozen }
                    .thenBy { it.label.lowercase() }
            )
            .toList()
    }

    fun loadFrozenEntries(context: Context): List<FreezeEntry> =
        loadAllEntries(context).filter { it.frozen }

    private fun buildEntry(context: Context, appInfo: ApplicationInfo): FreezeEntry? {
        val pm = context.packageManager
        val packageName = appInfo.packageName
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName)
            .ifBlank { packageName }
        val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
        val status = ShizukuAdControlRepository.queryPackageStatus(context, packageName)
        if (!status.installed) return null
        val systemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val frozen = PromoGovernActionRepository.isDisabledState(status.enabledState)
        return FreezeEntry(
            packageName = packageName,
            label = label,
            icon = icon,
            systemApp = systemApp,
            frozen = frozen,
            suspended = status.suspended,
            critical = isCritical(packageName)
        )
    }

    fun freeze(context: Context, packageName: String): Boolean {
        if (isCritical(packageName)) return false
        return runCatching { ShizukuAdControlRepository.disablePackage(context, packageName) }
            .getOrDefault(false)
    }

    fun unfreeze(context: Context, packageName: String): Boolean {
        return runCatching { ShizukuAdControlRepository.enablePackage(context, packageName) }
            .getOrDefault(false)
    }

    fun suspend(context: Context, packageName: String): Boolean {
        if (isCritical(packageName)) return false
        return runCatching { ShizukuAdControlRepository.suspendPackage(context, packageName) }
            .getOrDefault(false)
    }

    fun unsuspend(context: Context, packageName: String): Boolean {
        return runCatching { ShizukuAdControlRepository.unsuspendPackage(context, packageName) }
            .getOrDefault(false)
    }

    data class BatchResult(
        val operated: List<String>,
        val failed: List<String>,
        val skipped: List<String>
    )

    fun batchFreeze(context: Context, packages: List<String>): BatchResult {
        val operated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        packages.forEach { pkg ->
            if (isCritical(pkg)) {
                skipped += "'" + pkg + "' [关键系统项跳过]"
                return@forEach
            }
            val ok = runCatching { ShizukuAdControlRepository.disablePackage(context, pkg) }
                .getOrDefault(false)
            if (ok) operated += "'$pkg'" else failed += "'$pkg'"
        }
        return BatchResult(operated, failed, skipped)
    }

    fun batchUnfreeze(context: Context, packages: List<String>): BatchResult {
        val operated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        packages.forEach { pkg ->
            val ok = runCatching { ShizukuAdControlRepository.enablePackage(context, pkg) }
                .getOrDefault(false)
            if (ok) operated += "'$pkg'" else failed += "'$pkg'"
        }
        return BatchResult(operated, failed, skipped)
    }

    fun isShizukuReady(context: Context): Boolean {
        return ShizukuAdControlRepository.isReady(context) &&
            ShizukuAdControlRepository.queryPackageStatus(context, context.packageName).alive
    }
}
