package com.HanFeng.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.HanFeng.shizuku.IAdControlService
import com.HanFeng.shizuku.ShizukuAdControlUserService
import rikka.shizuku.Shizuku

object ShizukuAdControlRepository {
    @Volatile private var service: IAdControlService? = null
    @Volatile private var binding = false
    @Volatile private var lastBindAttemptAt = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IAdControlService.Stub.asInterface(binder)
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.HanFeng", ShizukuAdControlUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("ad-control")
        .debuggable(false)
        .version(1)

    data class PackageControlStatus(
        val installed: Boolean,
        val enabledState: Int,
        val enabledLabel: String,
        val suspended: Boolean,
        val alive: Boolean
    )

    fun isReady(context: Context): Boolean {
        return AppSettingsRepository.isShizukuEnabled(context) && ShizukuRepository.canUseEnhancedMode(context)
    }

    fun ensureBound(context: Context): Boolean {
        if (!isReady(context)) return false
        service?.let { return true }
        if (binding) return false
        val now = System.currentTimeMillis()
        if (now - lastBindAttemptAt < 1500L) return false
        lastBindAttemptAt = now
        binding = true
        return runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            true
        }.onFailure {
            binding = false
            LogRepository.append(context, "Bind Shizuku ad control service failed: ${it.message ?: it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    fun checkServiceHealth(context: Context): Boolean {
        val healthy = runCatching { getService(context)?.ping() == true }.getOrDefault(false)
        if (!healthy) {
            service = null
            binding = false
        }
        return healthy
    }

    fun disablePackage(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val result = getService(context)?.disablePackage(normalized) == true
        LogRepository.append(context, "Shizuku disable package package=$normalized success=$result")
        return result
    }

    fun enablePackage(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val result = getService(context)?.enablePackage(normalized) == true
        LogRepository.append(context, "Shizuku enable package package=$normalized success=$result")
        return result
    }

    fun suspendPackage(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val result = getService(context)?.suspendPackage(normalized) == true
        LogRepository.append(context, "Shizuku suspend package package=$normalized success=$result")
        return result
    }

    fun unsuspendPackage(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val result = getService(context)?.unsuspendPackage(normalized) == true
        LogRepository.append(context, "Shizuku unsuspend package package=$normalized success=$result")
        return result
    }

    fun uninstallPackageForUser(context: Context, packageName: String, userId: Int = 0): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val safeUserId = if (userId >= 0) userId else 0
        val result = getService(context)?.uninstallPackageForUser(normalized, safeUserId) == true
        LogRepository.append(context, "Shizuku uninstall package package=$normalized user=$safeUserId success=$result")
        return result
    }

    fun queryPackageStatus(context: Context, packageName: String): PackageControlStatus {
        val normalized = packageName.trim()
        if (normalized.isBlank()) {
            return PackageControlStatus(
                installed = false,
                enabledState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                enabledLabel = enabledStateLabel(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                suspended = false,
                alive = false
            )
        }
        val remote = getService(context)
        val alive = runCatching { remote?.ping() == true }.getOrDefault(false)
        val installed = remote?.isPackageInstalled(normalized)
            ?: runCatching {
                context.packageManager.getPackageInfo(normalized, 0)
                true
            }.getOrDefault(false)
        val enabledState = remote?.getPackageEnabledState(normalized)
            ?: runCatching {
                context.packageManager.getApplicationEnabledSetting(normalized)
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val suspended = remote?.isPackageSuspended(normalized)
            ?: runCatching {
                val info = context.packageManager.getPackageInfo(normalized, 0)
                (info.applicationInfo?.flags ?: 0 and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
            }.getOrDefault(false)
        return PackageControlStatus(
            installed = installed,
            enabledState = enabledState,
            enabledLabel = enabledStateLabel(enabledState),
            suspended = suspended,
            alive = alive
        )
    }

    private fun getService(context: Context): IAdControlService? {
        if (!ensureBound(context)) return service
        return service
    }

    private fun enabledStateLabel(state: Int): String {
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enabled"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disabled"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disabled_user"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "disabled_until_used"
            else -> "default"
        }
    }
}
