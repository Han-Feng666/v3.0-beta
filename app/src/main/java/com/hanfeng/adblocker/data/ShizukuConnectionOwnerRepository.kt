package com.HanFeng.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.HanFeng.shizuku.ConnectionOwnerUserService
import com.HanFeng.shizuku.IConnectionOwnerService
import rikka.shizuku.Shizuku

object ShizukuConnectionOwnerRepository {
    private const val BIND_RETRY_INTERVAL_MILLIS = 1500L
    private const val BIND_WAIT_TIMEOUT_MILLIS = 120L
    private const val BIND_WAIT_STEP_MILLIS = 20L
    private const val BIND_STALE_TIMEOUT_MILLIS = 3000L
    @Volatile private var service: IConnectionOwnerService? = null
    @Volatile private var binding = false
    @Volatile private var lastBindAttemptAt = 0L
    @Volatile private var lastBindLogAt = 0L
    @Volatile private var lastContext: Context? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IConnectionOwnerService.Stub.asInterface(binder)
            binding = false
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
            ComponentName(context.packageName, ConnectionOwnerUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("conn-owner")
            .debuggable(false)
            .version(1)
    }

    fun isReady(context: Context): Boolean {
        return AppSettingsRepository.isShizukuEnabled(context) &&
            ShizukuRepository.canAttemptUserService(context) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    fun ensureBound(context: Context): Boolean {
        lastContext = context.applicationContext
        if (!isReady(context)) return false
        if (hasLiveService()) return true
        if (!runCatching { Shizuku.pingBinder() || Shizuku.getBinder()?.isBinderAlive == true }.getOrDefault(false)) {
            return false
        }
        if (binding && System.currentTimeMillis() - lastBindAttemptAt > BIND_STALE_TIMEOUT_MILLIS) {
            maybeLog(context, "Shizuku connection owner binding stale, reset after ${System.currentTimeMillis() - lastBindAttemptAt}ms")
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
        }.getOrElse {
            binding = false
            maybeLog(context, "Bind Shizuku connection owner service failed: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    fun invalidateService() {
        service = null
        binding = false
    }

    fun getConnectionOwnerUid(
        context: Context,
        protocol: Int,
        localHost: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int
    ): Int {
        val connectedService = getService(context) ?: return -1
        return runCatching {
            connectedService.getConnectionOwnerUid(protocol, localHost, localPort, remoteHost, remotePort)
        }.getOrElse {
            invalidateService()
            -1
        }
    }

    private fun getService(context: Context): IConnectionOwnerService? {
        liveService()?.let { return it }
        ensureBound(context)
        val deadline = SystemClock.elapsedRealtime() + BIND_WAIT_TIMEOUT_MILLIS
        while (binding && SystemClock.elapsedRealtime() < deadline) {
            liveService()?.let { return it }
            SystemClock.sleep(BIND_WAIT_STEP_MILLIS)
        }
        if (binding) {
            maybeLog(context, "Wait Shizuku connection owner service timeout after ${BIND_WAIT_TIMEOUT_MILLIS}ms")
        }
        return liveService()
    }

    private fun hasLiveService(): Boolean = liveService() != null

    private fun liveService(): IConnectionOwnerService? {
        val current = service ?: return null
        return if (current.asBinder()?.isBinderAlive == true) {
            current
        } else {
            invalidateService()
            null
        }
    }

    private fun logBindEvent(name: ComponentName?, binder: IBinder?, state: String) {
        val context = lastContext ?: return
        maybeLog(
            context,
            "Shizuku connection owner service $state component=${name?.flattenToShortString() ?: "unknown"} binderAlive=${binder?.isBinderAlive == true}"
        )
    }

    private fun maybeLog(context: Context, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastBindLogAt < 1500L) return
        lastBindLogAt = now
        LogRepository.append(context, message)
    }
}
