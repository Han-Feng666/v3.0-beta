package com.HanFeng.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.HanFeng.shizuku.IPerformanceService
import com.HanFeng.shizuku.ShizukuPerformanceUserService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

object ShizukuPerformanceRepository {
    private const val BIND_RETRY_INTERVAL_MILLIS = 1500L
    private const val BIND_STALE_TIMEOUT_MILLIS = 3000L

    @Volatile private var service: IPerformanceService? = null
    @Volatile private var serviceBound = false
    @Volatile private var lastBindAttemptAt = 0L

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                "com.HanFeng.adblocker",
                ShizukuPerformanceUserService::class.java.name
            )
        )
            .daemon(false)
            .processNameSuffix("perf")
            .version(1)
    }

    private fun pingBinder(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    private fun tryPing(): Boolean {
        return runCatching {
            val binder = Shizuku.getBinder() ?: return@runCatching false
            binder.isBinderAlive
        }.getOrDefault(false)
    }

    val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null) {
                this@ShizukuPerformanceRepository.service = IPerformanceService.Stub.asInterface(service)
                serviceBound = true
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            this@ShizukuPerformanceRepository.service = null
        }
    }

    fun isReady(context: Context): Boolean {
        return AppSettingsRepository.isShizukuEnabled(context) &&
            ShizukuRepository.canAttemptUserService(context)
    }

    fun invalidateService() {
        runCatching { if (serviceBound) Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        service = null
        serviceBound = false
    }

    fun checkServiceHealth(context: Context): Boolean {
        return runCatching { getService(context)?.ping() == true }.getOrDefault(false)
    }

    fun ensureBound(context: Context): Boolean {
        if (serviceBound && service != null) return true
        val now = System.currentTimeMillis()
        if (now - lastBindAttemptAt < BIND_RETRY_INTERVAL_MILLIS) return false
        lastBindAttemptAt = now
        if (!isReady(context)) return false
        return runCatching {
            val binderAlive = pingBinder() || tryPing()
            if (!binderAlive) return false
            val args = Shizuku.bindUserService(userServiceArgs, serviceConnection)
            true
        }.getOrDefault(false)
    }

    fun ensureBoundAndWait(context: Context): Boolean {
        if (serviceBound && service != null) return true
        if (!ensureBound(context)) return false
        runBlocking {
            withTimeout(BIND_STALE_TIMEOUT_MILLIS) {
                var tries = 0
                while (!serviceBound && tries < 30) {
                    kotlinx.coroutines.delay(100)
                    tries++
                }
            }
        }
        return serviceBound && service != null
    }

    private fun getService(context: Context): IPerformanceService? {
        service?.let { return it }
        ensureBound(context)
        if (!serviceBound && !ensureBoundAndWait(context)) return null
        return service
    }

    fun getForegroundPackage(context: Context): String? {
        return runCatching { getService(context)?.getForegroundPackage() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun getRunningPackages(context: Context): List<String> {
        return runCatching {
            getService(context)?.getRunningPackages()?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun readProcessStatString(context: Context, pid: Int): String? {
        return runCatching { getService(context)?.readProcessStat(pid) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun dumpProcessInfo(context: Context): String? {
        return runCatching { getService(context)?.dumpProcessInfo() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
