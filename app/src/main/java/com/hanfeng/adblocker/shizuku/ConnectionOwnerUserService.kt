package com.HanFeng.shizuku

import android.content.Context
import android.net.ConnectivityManager
import android.system.OsConstants
import androidx.annotation.Keep
import java.net.InetSocketAddress

class ConnectionOwnerUserService() : IConnectionOwnerService.Stub() {

    private var serviceContext: Context? = null

    @Keep
    constructor(context: Context) : this() {
        serviceContext = context.applicationContext
    }

    override fun getConnectionOwnerUid(protocol: Int, localHost: String, localPort: Int, remoteHost: String, remotePort: Int): Int {
        val context = serviceContext ?: return -1
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return -1
        val safeProtocol = if (protocol == OsConstants.IPPROTO_UDP) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
        val local = InetSocketAddress(localHost, localPort)
        val remote = InetSocketAddress(remoteHost, remotePort)
        return runCatching {
            connectivityManager.getConnectionOwnerUid(safeProtocol, local, remote)
        }.getOrDefault(-1)
    }

    override fun destroy() {
        System.exit(0)
    }
}
