package com.HanFeng.data

import android.content.Context
import android.content.pm.PackageManager

object PromoGovernActionRepository {
    private const val SMART_COMPONENT_DISABLE_LIMIT = 6

    enum class GovernStrategy {
        LIGHT,
        STANDARD,
        AGGRESSIVE
    }

    fun smartGovern(context: Context, target: PromoGovernTarget, strategy: GovernStrategy = GovernStrategy.STANDARD): String {
        PromoGovernSnapshotRepository.savePackageSnapshot(context, target, notificationTouched = true)
        val status = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)

        val componentLimit = when (strategy) {
            GovernStrategy.LIGHT -> 3
            GovernStrategy.STANDARD -> 6
            GovernStrategy.AGGRESSIVE -> 10
        }

        val componentResult = disableSmartPromoComponents(context, target, componentLimit)

        return when (strategy) {
            GovernStrategy.LIGHT -> {
                val lightGoverned = ShizukuAdControlRepository.blockPackageNotifications(context, target.packageName)
                if (lightGoverned) {
                    buildSmartGovernSuccessMessage(
                        base = "轻量治理成功，已关闭推送广告",
                        componentResult = componentResult
                    )
                } else {
                    "轻量治理：关闭通知失败，请确认系统支持"
                }
            }
            GovernStrategy.STANDARD -> {
                val lightGoverned = ShizukuAdControlRepository.blockPackageNotifications(context, target.packageName)
                if (lightGoverned) {
                    buildSmartGovernSuccessMessage(
                        base = "标准治理成功，已关闭推送广告",
                        componentResult = componentResult
                    )
                }
                if (!isDisabledState(status.enabledState)) {
                    ShizukuAdControlRepository.disablePackage(context, target.packageName)
                    val disabledStatus = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
                    if (isDisabledState(disabledStatus.enabledState)) {
                        return buildSmartGovernSuccessMessage(
                            base = "标准治理成功，已冻结",
                            componentResult = componentResult
                        )
                    }
                }
                val refreshed = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
                if (!refreshed.suspended) {
                    val suspendRequested = ShizukuAdControlRepository.suspendPackage(context, target.packageName)
                    val suspendStatus = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
                    if (suspendRequested && suspendStatus.suspended) {
                        return buildSmartGovernSuccessMessage(
                            base = "标准治理：冻结未生效，已回退为暂停",
                            componentResult = componentResult
                        )
                    }
                }
                if (componentResult.successCount > 0) {
                    "标准治理部分成功，已冻结 ${componentResult.successCount} 个组件"
                } else {
                    "标准治理失败，请确认系统支持"
                }
            }
            GovernStrategy.AGGRESSIVE -> {
                ShizukuAdControlRepository.blockPackageNotifications(context, target.packageName)
                if (!isDisabledState(status.enabledState)) {
                    ShizukuAdControlRepository.disablePackage(context, target.packageName)
                    val disabledStatus = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
                    if (isDisabledState(disabledStatus.enabledState)) {
                        return buildSmartGovernSuccessMessage(
                            base = "激进治理成功，已冻结并关闭通知",
                            componentResult = componentResult
                        )
                    }
                }
                if (!status.suspended) {
                    ShizukuAdControlRepository.suspendPackage(context, target.packageName)
                    val suspendStatus = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
                    if (suspendStatus.suspended) {
                        return buildSmartGovernSuccessMessage(
                            base = "激进治理成功，已暂停",
                            componentResult = componentResult
                        )
                    }
                }
                if (componentResult.successCount > 0) {
                    "激进治理部分成功，已处理 ${componentResult.successCount} 个组件"
                } else {
                    "激进治理失败，请确认系统支持"
                }
            }
        }
    }

    private fun disableSmartPromoComponents(context: Context, target: PromoGovernTarget, limit: Int = SMART_COMPONENT_DISABLE_LIMIT): SmartComponentGovernResult {
        val candidates = PromoGovernComponentRepository.discoverCandidates(context, target.packageName)
            .filter(::isSmartGovernSafeComponent)
            .take(limit)
        if (candidates.isEmpty()) return SmartComponentGovernResult(0, 0)
        var successCount = 0
        candidates.forEach { candidate ->
            PromoGovernSnapshotRepository.saveComponentSnapshot(
                context = context,
                packageName = target.packageName,
                title = target.title,
                componentName = candidate.componentName,
                componentWasEnabled = candidate.enabled
            )
            if (ShizukuAdControlRepository.disableComponent(context, candidate.componentName)) {
                successCount++
            }
        }
        return SmartComponentGovernResult(successCount, candidates.size)
    }

    fun isSmartGovernSafeComponent(candidate: PromoComponentCandidate): Boolean {
        if (!candidate.enabled) return false
        if (candidate.riskLabel != "低风险" && candidate.riskLabel != "中风险") return false
        if (candidate.score < 5) return false
        val group = candidate.groupLabel
        if (group.contains("主入口") || group.contains("账号登录") || group.contains("支付") || group.contains("设置") || group.contains("网页容器")) {
            return false
        }
        return group.contains("启动广告") ||
            group.contains("推送") ||
            group.contains("广告 Service")
    }

    private fun buildSmartGovernSuccessMessage(base: String, componentResult: SmartComponentGovernResult): String {
        if (componentResult.successCount <= 0) return base
        return "$base，并冻结 ${componentResult.successCount} 个推广组件"
    }

    private data class SmartComponentGovernResult(
        val successCount: Int,
        val attemptedCount: Int
    )

    data class GovernStatistics(
        val totalGoverned: Int,
        val notificationBlocked: Int,
        val packagesDisabled: Int,
        val packagesSuspended: Int,
        val componentsDisabled: Int,
        val restored: Int,
        val lastGovernTime: Long,
        val categoryStats: Map<String, Int>
    )

    fun getGovernStatistics(context: Context): GovernStatistics {
        val snapshot = PromoGovernSnapshotRepository.latest(context)
        val governedPackages = PromoGovernSnapshotRepository.getGovernedPackages(context)
        val prefs = context.getSharedPreferences("promo_govern_stats", Context.MODE_PRIVATE)
        return GovernStatistics(
            totalGoverned = prefs.getInt("total_governed", 0),
            notificationBlocked = prefs.getInt("notification_blocked", 0),
            packagesDisabled = prefs.getInt("packages_disabled", 0),
            packagesSuspended = prefs.getInt("packages_suspended", 0),
            componentsDisabled = prefs.getInt("components_disabled", 0),
            restored = prefs.getInt("restored", 0),
            lastGovernTime = prefs.getLong("last_govern_time", 0),
            categoryStats = governedPackages.associateWith { pkg ->
                val status = ShizukuAdControlRepository.queryPackageStatus(context, pkg)
                if (isDisabledState(status.enabledState)) 1 else if (status.suspended) 2 else if (!status.notificationsEnabled) 3 else 0
            }
        )
    }

    fun recordGovernAction(context: Context, action: String, packageName: String) {
        val prefs = context.getSharedPreferences("promo_govern_stats", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        when (action) {
            "notification_blocked" -> editor.putInt("notification_blocked", prefs.getInt("notification_blocked", 0) + 1)
            "package_disabled" -> editor.putInt("packages_disabled", prefs.getInt("packages_disabled", 0) + 1)
            "package_suspended" -> editor.putInt("packages_suspended", prefs.getInt("packages_suspended", 0) + 1)
            "component_disabled" -> editor.putInt("components_disabled", prefs.getInt("components_disabled", 0) + 1)
            "restored" -> editor.putInt("restored", prefs.getInt("restored", 0) + 1)
            "governed" -> {
                editor.putInt("total_governed", prefs.getInt("total_governed", 0) + 1)
                editor.putLong("last_govern_time", System.currentTimeMillis())
            }
        }
        editor.apply()
    }

    fun resetStatistics(context: Context) {
        context.getSharedPreferences("promo_govern_stats", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun setNotificationsBlocked(context: Context, target: PromoGovernTarget, blocked: Boolean): String {
        if (blocked) PromoGovernSnapshotRepository.savePackageSnapshot(context, target, notificationTouched = true)
        val success = if (blocked) {
            ShizukuAdControlRepository.blockPackageNotifications(context, target.packageName)
        } else {
            ShizukuAdControlRepository.allowPackageNotifications(context, target.packageName)
        }
        val actionText = if (blocked) "关闭推送广告" else "恢复推送广告"
        return if (success) "${actionText}成功" else "${actionText}失败，请确认系统支持通知权限治理"
    }

    fun setPackageDisabled(context: Context, target: PromoGovernTarget, disabled: Boolean): String {
        PromoGovernSnapshotRepository.savePackageSnapshot(context, target)
        val requested = if (disabled) {
            ShizukuAdControlRepository.disablePackage(context, target.packageName)
        } else {
            ShizukuAdControlRepository.enablePackage(context, target.packageName)
        }
        val refreshed = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
        val verified = if (disabled) {
            isDisabledState(refreshed.enabledState)
        } else {
            refreshed.enabledState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                refreshed.enabledState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        val actionText = if (disabled) "冻结" else "解冻"
        return if (requested && verified) "${actionText}成功" else "${actionText}失败，请确认该项目支持此操作"
    }

    fun setPackageSuspended(context: Context, target: PromoGovernTarget, suspended: Boolean): String {
        PromoGovernSnapshotRepository.savePackageSnapshot(context, target)
        val requested = if (suspended) {
            ShizukuAdControlRepository.suspendPackage(context, target.packageName)
        } else {
            ShizukuAdControlRepository.unsuspendPackage(context, target.packageName)
        }
        val refreshed = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
        val actionText = if (suspended) "暂停" else "恢复暂停"
        return if (requested && refreshed.suspended == suspended) "${actionText}成功" else "${actionText}失败，请确认系统支持该操作"
    }

    fun setComponentDisabled(
        context: Context,
        packageName: String,
        title: String,
        componentName: String,
        disabled: Boolean,
        componentWasEnabled: Boolean
    ): String {
        PromoGovernSnapshotRepository.saveComponentSnapshot(context, packageName, title, componentName, componentWasEnabled)
        val success = if (disabled) {
            ShizukuAdControlRepository.disableComponent(context, componentName)
        } else {
            ShizukuAdControlRepository.enableComponent(context, componentName)
        }
        val actionText = if (disabled) "组件冻结" else "组件解冻"
        return if (success) "${actionText}成功" else "${actionText}失败，请确认组件名完整且系统支持该操作"
    }

    fun restoreLatest(context: Context): String {
        val snapshot = PromoGovernSnapshotRepository.latest(context) ?: return "没有可恢复的最近治理记录"
        val restored = mutableListOf<String>()
        if (snapshot.notificationTouched) {
            if (ShizukuAdControlRepository.allowPackageNotifications(context, snapshot.packageName)) {
                restored += "推送广告"
            }
        }
        if (snapshot.componentName.isNotBlank()) {
            val componentRestored = if (snapshot.componentWasEnabled) {
                ShizukuAdControlRepository.enableComponent(context, snapshot.componentName)
            } else {
                ShizukuAdControlRepository.disableComponent(context, snapshot.componentName)
            }
            if (componentRestored) restored += "组件状态"
        }
        val enabledRestored = if (isDisabledState(snapshot.enabledState)) {
            ShizukuAdControlRepository.disablePackage(context, snapshot.packageName)
        } else {
            ShizukuAdControlRepository.enablePackage(context, snapshot.packageName)
        }
        if (enabledRestored) restored += "应用启用状态"

        val suspendedRestored = if (snapshot.suspended) {
            ShizukuAdControlRepository.suspendPackage(context, snapshot.packageName)
        } else {
            ShizukuAdControlRepository.unsuspendPackage(context, snapshot.packageName)
        }
        if (suspendedRestored) restored += "暂停状态"

        return if (restored.isEmpty()) {
            "恢复未生效，请检查 Shizuku 服务状态和系统支持情况"
        } else {
            PromoGovernSnapshotRepository.unmarkPackageGoverned(context, snapshot.packageName)
            "已恢复 ${snapshot.title} 的${restored.joinToString("、")}"
        }
    }

    fun isDisabledState(enabledState: Int): Boolean {
        return enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }
}
