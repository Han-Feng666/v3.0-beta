package com.HanFeng.data

import android.content.Context
import android.content.pm.PackageManager

object PromoGovernActionRepository {
    fun smartGovern(context: Context, target: PromoGovernTarget): String {
        PromoGovernSnapshotRepository.savePackageSnapshot(context, target, notificationTouched = true)
        val status = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
        val lightGoverned = ShizukuAdControlRepository.blockPackageNotifications(context, target.packageName)
        if (lightGoverned) return "治理成功，当前已关闭推送广告能力"

        if (!isDisabledState(status.enabledState)) {
            ShizukuAdControlRepository.disablePackage(context, target.packageName)
            val disabledStatus = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
            if (isDisabledState(disabledStatus.enabledState)) return "治理成功，当前已冻结"
        }

        val refreshed = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
        if (!refreshed.suspended) {
            val suspendRequested = ShizukuAdControlRepository.suspendPackage(context, target.packageName)
            val suspendStatus = ShizukuAdControlRepository.queryPackageStatus(context, target.packageName)
            if (suspendRequested && suspendStatus.suspended) return "冻结未生效，已自动回退为暂停"
        }
        return "治理失败，请确认系统支持冻结或暂停"
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
            "已恢复 ${snapshot.title} 的${restored.joinToString("、")}"
        }
    }

    fun isDisabledState(enabledState: Int): Boolean {
        return enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }
}
