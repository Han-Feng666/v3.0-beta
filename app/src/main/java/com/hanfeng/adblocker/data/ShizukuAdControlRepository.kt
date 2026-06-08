package com.HanFeng.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import com.HanFeng.shizuku.IAdControlService
import com.HanFeng.shizuku.ShizukuAdControlUserService
import rikka.shizuku.Shizuku

object ShizukuAdControlRepository {
    private const val BIND_RETRY_INTERVAL_MILLIS = 1500L
    private const val BIND_WAIT_TIMEOUT_MILLIS = 1500L
    private const val BIND_WAIT_STEP_MILLIS = 40L
    private const val BIND_STALE_TIMEOUT_MILLIS = 3000L
    @Volatile private var service: IAdControlService? = null
    @Volatile private var binding = false
    @Volatile private var lastBindAttemptAt = 0L
    @Volatile private var lastBindLogAt = 0L
    @Volatile private var lastContext: Context? = null
    @Volatile private var serviceMarkedDead = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IAdControlService.Stub.asInterface(binder)
            binding = false
            serviceMarkedDead = false
            logBindEvent(name, binder, "connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            logBindEvent(name, null, "disconnected")
        }
    }

    private fun createUserServiceArgs(context: Context): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuAdControlUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("ad-control")
            .debuggable(false)
            .version(1)
    }

    data class PackageControlStatus(
        val installed: Boolean,
        val enabledState: Int,
        val enabledLabel: String,
        val suspended: Boolean,
        val alive: Boolean
    )

    fun isReady(context: Context): Boolean {
        return AppSettingsRepository.isShizukuEnabled(context) && ShizukuRepository.canAttemptUserService(context)
    }

    fun getServiceNoBind(): IAdControlService? = liveService()

    fun ensureBound(context: Context): Boolean {
        lastContext = context.applicationContext
        if (!isReady(context)) return false
        if (hasLiveService()) return true
        if (!runCatching { Shizuku.pingBinder() || Shizuku.getBinder()?.isBinderAlive == true }.getOrDefault(false)) {
            return false
        }
        if (binding && System.currentTimeMillis() - lastBindAttemptAt > BIND_STALE_TIMEOUT_MILLIS) {
            maybeLog(context, "Shizuku ad control binding stale, reset after ${System.currentTimeMillis() - lastBindAttemptAt}ms")
            binding = false
        }
        if (binding) return false
        val now = System.currentTimeMillis()
        if (now - lastBindAttemptAt < BIND_RETRY_INTERVAL_MILLIS) return false
        lastBindAttemptAt = now
        binding = true
        return runCatching {
            Shizuku.bindUserService(createUserServiceArgs(context), serviceConnection)
            true
        }.onFailure {
            binding = false
            LogRepository.append(context, "Bind Shizuku ad control service failed: ${it.message ?: it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    fun invalidateService() {
        service = null
        binding = false
    }

    fun checkServiceHealth(context: Context): Boolean {
        val healthy = runCatching { getService(context)?.ping() == true }.getOrDefault(false)
        if (!healthy) {
            invalidateService()
        }
        return healthy
    }

    fun isServiceAlive(): Boolean = liveService() != null

    fun getLastOperationSummary(context: Context): String {
        return runCatching { getService(context)?.getLastOperationSummary().orEmpty() }
            .getOrDefault("")
    }

    fun blockPackageNotifications(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val result = runCatching {
            getService(context)?.blockPackageNotifications(normalized) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku block package notifications failed: ${e.message ?: e.javaClass.simpleName} package=$normalized")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku block package notifications package=$normalized success=$result")
        return result
    }

    fun allowPackageNotifications(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val result = runCatching {
            getService(context)?.allowPackageNotifications(normalized) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku allow package notifications failed: ${e.message ?: e.javaClass.simpleName} package=$normalized")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku allow package notifications package=$normalized success=$result")
        return result
    }

    fun disablePackage(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val beforeStatus = queryPackageStatus(context, normalized)
        val result = runCatching {
            getService(context)?.disablePackage(normalized) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku disable package failed: ${e.message ?: e.javaClass.simpleName} package=$normalized before=${beforeStatus.enabledLabel}")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku disable package package=$normalized before=${beforeStatus.enabledLabel} success=$result")
        return result
    }

    fun enablePackage(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val beforeStatus = queryPackageStatus(context, normalized)
        val result = runCatching {
            getService(context)?.enablePackage(normalized) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku enable package failed: ${e.message ?: e.javaClass.simpleName} package=$normalized before=${beforeStatus.enabledLabel}")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku enable package package=$normalized before=${beforeStatus.enabledLabel} success=$result")
        return result
    }

    fun disableComponent(context: Context, componentName: String): Boolean {
        val normalized = componentName.trim()
        if (normalized.isBlank()) return false
        val result = runCatching {
            getService(context)?.disableComponent(normalized) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku disable component failed: ${e.message ?: e.javaClass.simpleName} component=$normalized")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku disable component component=$normalized success=$result")
        return result
    }

    fun enableComponent(context: Context, componentName: String): Boolean {
        val normalized = componentName.trim()
        if (normalized.isBlank()) return false
        val result = runCatching {
            getService(context)?.enableComponent(normalized) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku enable component failed: ${e.message ?: e.javaClass.simpleName} component=$normalized")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku enable component component=$normalized success=$result")
        return result
    }

    fun suspendPackage(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val beforeStatus = queryPackageStatus(context, normalized)
        val result = runCatching {
            getService(context)?.suspendPackage(normalized) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku suspend package failed: ${e.message ?: e.javaClass.simpleName} package=$normalized before=${if (beforeStatus.suspended) "suspended" else "active"}")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku suspend package package=$normalized before=${if (beforeStatus.suspended) "suspended" else "active"} success=$result")
        return result
    }

    fun unsuspendPackage(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val beforeStatus = queryPackageStatus(context, normalized)
        val result = runCatching {
            getService(context)?.unsuspendPackage(normalized) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku unsuspend package failed: ${e.message ?: e.javaClass.simpleName} package=$normalized before=${if (beforeStatus.suspended) "suspended" else "active"}")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku unsuspend package package=$normalized before=${if (beforeStatus.suspended) "suspended" else "active"} success=$result")
        return result
    }

    fun uninstallPackageForUser(context: Context, packageName: String, userId: Int = 0): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val safeUserId = if (userId >= 0) userId else 0
        val beforeStatus = queryPackageStatus(context, normalized)
        val result = runCatching {
            getService(context)?.uninstallPackageForUser(normalized, safeUserId) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku uninstall package failed: ${e.message ?: e.javaClass.simpleName} package=$normalized user=$safeUserId installed=${beforeStatus.installed}")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku uninstall package package=$normalized user=$safeUserId installed=${beforeStatus.installed} success=$result")
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
        val remote = if (!serviceMarkedDead) getServiceNoBind() else null
        val alive = runCatching { remote?.ping() == true }.getOrDefault(false)
        if (!alive && remote != null) {
            invalidateService()
            serviceMarkedDead = true
        }
        val installed = runCatching { remote?.isPackageInstalled(normalized) }
            .onFailure {
                invalidateService()
                serviceMarkedDead = true
                LogRepository.append(context, "[PromoGovern] service query failed for $normalized: ${it.message}")
            }
            .getOrNull()
            ?: runCatching {
                context.packageManager.getPackageInfo(normalized, 0)
                true
            }.getOrDefault(false)
        val enabledState = runCatching { remote?.getPackageEnabledState(normalized) }
            .onFailure {
                invalidateService()
                serviceMarkedDead = true
            }
            .getOrNull()
            ?: runCatching {
                context.packageManager.getApplicationEnabledSetting(normalized)
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val suspended = runCatching { remote?.isPackageSuspended(normalized) }
            .onFailure {
                invalidateService()
                serviceMarkedDead = true
            }
            .getOrNull()
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
        liveService()?.let { return it }
        ensureBound(context)
        val deadline = SystemClock.elapsedRealtime() + BIND_WAIT_TIMEOUT_MILLIS
        while (binding && SystemClock.elapsedRealtime() < deadline) {
            liveService()?.let { return it }
            SystemClock.sleep(BIND_WAIT_STEP_MILLIS)
        }
        if (binding) {
            maybeLog(context, "Wait Shizuku ad control service timeout after ${BIND_WAIT_TIMEOUT_MILLIS}ms")
        }
        return liveService()
    }

    private fun hasLiveService(): Boolean = liveService() != null

    private fun liveService(): IAdControlService? {
        val current = service ?: return null
        return if (current.asBinder()?.isBinderAlive == true) {
            current
        } else {
            invalidateService()
            null
        }
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

    private fun logBindEvent(name: ComponentName?, binder: IBinder?, state: String) {
        val context = lastContext ?: return
        maybeLog(
            context,
            "Shizuku ad control service $state component=${name?.flattenToShortString() ?: "unknown"} binderAlive=${binder?.isBinderAlive == true}"
        )
    }

    private fun maybeLog(context: Context, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastBindLogAt < 1500L) return
        lastBindLogAt = now
        LogRepository.append(context, message)
    }
}
