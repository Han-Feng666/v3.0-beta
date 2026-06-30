package com.HanFeng.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.HanFeng.core.network.MitmAppFullCaptureTargetSupport
import com.HanFeng.data.LogRepository
import com.HanFeng.data.PromoGovernSnapshotRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.ShizukuAdControlCatalog
import com.HanFeng.data.ShizukuAdControlRepository

private const val MITM_APP_FULL_CAPTURE_TARGET_LIMIT = 4

fun resolveMitmAppFullCaptureTargets(
    context: Context,
    ownPackageName: String,
    shizukuAdControlReady: Boolean
): Set<String> {
    return runCatching {
        val packageManagerTargets = context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { app -> app.packageName != ownPackageName }
            .mapNotNull { app ->
                val label = runCatching { context.packageManager.getApplicationLabel(app).toString() }.getOrNull()
                val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                app.packageName.takeIf {
                    MitmAppFullCaptureTargetSupport.isSafeTarget(
                        packageName = app.packageName,
                        label = label,
                        isSystemApp = isSystemApp
                    )
                }
            }
            .distinct()
            .toSet()

        val shizukuPresetTargets = resolveShizukuManagedMitmAppTargets(context, ownPackageName, shizukuAdControlReady)
        val governedTargets = resolveGovernedMitmAppTargets(context, ownPackageName)

        (packageManagerTargets + shizukuPresetTargets + governedTargets)
            .asSequence()
            .sortedByDescending { packagePriorityScore(context, it) }
            .take(MITM_APP_FULL_CAPTURE_TARGET_LIMIT)
            .toSet()
    }.onFailure {
        LogRepository.append(context, "Load MITM app full-capture targets failed: ${it.message ?: it.javaClass.simpleName}")
    }.getOrDefault(emptySet())
}

private fun resolveShizukuManagedMitmAppTargets(
    context: Context,
    ownPackageName: String,
    shizukuAdControlReady: Boolean
): Set<String> {
    if (!shizukuAdControlReady) return emptySet()
    val serviceReady = runCatching { ShizukuAdControlRepository.ensureBoundAndWait(context) }
        .getOrDefault(false)
    if (!serviceReady) return emptySet()

    return ShizukuAdControlCatalog.allPresets()
        .asSequence()
        .filter { preset -> preset.packageName != ownPackageName }
        .filter { preset ->
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = preset.packageName,
                label = preset.title,
                isSystemApp = false,
                managedPromoCategory = preset.category
            )
        }
        .filter { preset -> isShizukuPackageActive(context, preset.packageName) }
        .map { it.packageName }
        .distinct()
        .toSet()
}

private fun resolveGovernedMitmAppTargets(context: Context, ownPackageName: String): Set<String> {
    return PromoGovernSnapshotRepository.getGovernedPackages(context)
        .asSequence()
        .filter { it != ownPackageName }
        .filter { pkg -> isShizukuPackageActive(context, pkg) }
        .toSet()
}

private fun isShizukuPackageActive(context: Context, packageName: String): Boolean {
    val status = ShizukuAdControlRepository.queryPackageStatus(context, packageName)
    if (!status.installed || status.suspended) return false
    return status.enabledState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
        status.enabledState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
        status.enabledState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
}

fun packagePriorityScore(context: Context, packageName: String): Int {
    var score = 0
    if (!packageName.startsWith("com.android.") && !packageName.startsWith("android.")) score += 3
    if (!packageName.contains(":") && !packageName.endsWith(".service") && !packageName.endsWith(".provider")) score += 2
    if (ShizukuAdControlCatalog.findPresetByPackage(packageName) != null) score += 6
    if (RuleRepository.isNovelAppHint(packageName)) score += 5
    if (RuleRepository.isAggressiveAdAppHint(packageName) || packageName in PromoGovernSnapshotRepository.getGovernedPackages(context)) score += 4
    if (RuleRepository.isCommunityAppHint(packageName)) score += 2
    return score
}
