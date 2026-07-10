package com.HanFeng.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.HanFeng.shizuku.IAdControlService
import com.HanFeng.shizuku.ShizukuAdControlUserService
import rikka.shizuku.Shizuku
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

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

    fun ensureBoundAndWait(context: Context): Boolean {
        if (hasLiveService()) return true
        ensureBound(context)
        val result = runBlocking {
            try {
                withTimeout(BIND_WAIT_TIMEOUT_MILLIS) {
                    while (binding) {
                        if (hasLiveService()) return@withTimeout true
                        delay(BIND_WAIT_STEP_MILLIS)
                    }
                    false
                }
            } catch (_: TimeoutCancellationException) {
                false
            }
        }
        return result || checkServiceHealth(context)
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
        return runCatching { localizeOperationSummary(getService(context)?.getLastOperationSummary().orEmpty()) }
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

    fun setNetworkBlocked(context: Context, packageName: String, blocked: Boolean): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val result = runCatching {
            getService(context)?.setNetworkBlocked(normalized, blocked) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku network control failed: ${e.message ?: e.javaClass.simpleName} package=$normalized blocked=$blocked")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku network control package=$normalized blocked=$blocked success=$result")
        return result
    }

    fun setBackgroundRestricted(context: Context, packageName: String, restricted: Boolean): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val result = runCatching {
            getService(context)?.setBackgroundRestricted(normalized, restricted) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku background control failed: ${e.message ?: e.javaClass.simpleName} package=$normalized restricted=$restricted")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku background control package=$normalized restricted=$restricted success=$result")
        return result
    }

    fun syncHostsBlocklist(context: Context, domains: List<String>): Boolean {
        val normalized = domains.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        val result = runCatching {
            getService(context)?.syncHostsBlocklist(normalized.toTypedArray()) == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku hosts sync failed: ${e.message ?: e.javaClass.simpleName} count=${normalized.size}")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku hosts sync count=${normalized.size} success=$result")
        return result
    }

    fun clearHostsBlocklist(context: Context): Boolean {
        val result = runCatching {
            getService(context)?.clearHostsBlocklist() == true
        }.onFailure { e ->
            LogRepository.append(context, "Shizuku hosts clear failed: ${e.message ?: e.javaClass.simpleName}")
        }.getOrDefault(false)
        LogRepository.append(context, "Shizuku hosts clear success=$result")
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
        val packageManagerInstalled = runCatching {
            context.packageManager.getPackageInfo(normalized, packageQueryFlags())
            true
        }.getOrDefault(false)
        val installed = runCatching { remote?.isPackageInstalled(normalized) }
            .onFailure {
                invalidateService()
                serviceMarkedDead = true
                LogRepository.append(context, "[PromoGovern] service query failed for $normalized: ${it.message}")
            }
            .getOrNull()
            ?.let { remoteInstalled -> remoteInstalled || packageManagerInstalled }
            ?: packageManagerInstalled
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
                val info = context.packageManager.getPackageInfo(normalized, packageQueryFlags())
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
        val result = runBlocking {
            try {
                withTimeout(BIND_WAIT_TIMEOUT_MILLIS) {
                    while (binding) {
                        liveService()?.let { return@withTimeout it }
                        delay(BIND_WAIT_STEP_MILLIS)
                    }
                    null
                }
            } catch (_: TimeoutCancellationException) {
                null
            }
        }
        if (result == null && binding) {
            maybeLog(context, "Wait Shizuku ad control service timeout after ${BIND_WAIT_TIMEOUT_MILLIS}ms")
        }
        return result ?: liveService()
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
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "已启用"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "已冻结"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "已冻结"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "已冻结"
            else -> "默认"
        }
    }

    private fun packageQueryFlags(): Int {
        return PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
    }

    private fun localizeOperationSummary(raw: String): String {
        if (raw.isBlank() || raw == "idle") return raw
        return raw
            .replace("try", "第")
            .replace(Regex("第(\\d+):"), "第$1次：")
            .replace("success", "执行成功")
            .replace("failed", "执行失败")
            .replace("unsupported", "系统不支持")
            .replace("permission", "权限不足")
            .replace("security", "系统安全限制")
            .replace("package-missing", "未找到目标应用")
            .replace("exception", "执行异常")
            .replace("command=", "命令=")
            .replace(" exit=", "，退出码=")
            .replace(" output=", "，输出=")
            .replace(" error=", "，错误=")
            .replace("POST_NOTIFICATION", "通知权限")
            .replace("RUN_ANY_IN_BACKGROUND", "后台运行权限")
            .replace("RUN_IN_BACKGROUND", "后台活动权限")
            .replace("WAKE_LOCK", "唤醒锁权限")
            .replace("INTERNET", "联网权限")
            .replace("no-success", "未成功")
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
