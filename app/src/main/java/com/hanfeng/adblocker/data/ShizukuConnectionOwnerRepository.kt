package com.HanFeng.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.HanFeng.shizuku.ConnectionOwnerUserService
import com.HanFeng.shizuku.IConnectionOwnerService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ShizukuConnectionOwnerRepository {
    @Volatile private var service: IConnectionOwnerService? = null
    @Volatile private var binding = false
    @Volatile private var lastBindAttemptAt = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IConnectionOwnerService.Stub.asInterface(binder)
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.HanFeng", ConnectionOwnerUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("conn-owner")
        .debuggable(false)
        .version(1)

    fun isReady(context: Context): Boolean {
        return AppSettingsRepository.isShizukuEnabled(context) &&
            ShizukuRepository.canUseEnhancedMode(context) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
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
        }.getOrElse {
            binding = false
            false
        }
    }

    fun getConnectionOwnerUid(
        context: Context,
        protocol: Int,
        localHost: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int
    ): Int {
        if (!ensureBound(context)) return -1
        val connectedService = service
        if (connectedService != null) {
            return runCatching {
                connectedService.getConnectionOwnerUid(protocol, localHost, localPort, remoteHost, remotePort)
            }.getOrDefault(-1)
        }
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IConnectionOwnerService.Stub.asInterface(binder)
                binding = false
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                binding = false
            }
        }
        val holder = AtomicReference(-1)
        return runCatching {
            binding = true
            Shizuku.bindUserService(userServiceArgs, connection)
            latch.await(1200, TimeUnit.MILLISECONDS)
            val resolvedService = service
            val resolved = resolvedService?.getConnectionOwnerUid(protocol, localHost, localPort, remoteHost, remotePort) ?: -1
            holder.set(resolved)
            resolved
        }.getOrElse {
            binding = false
            holder.get()
        }
    }
}
