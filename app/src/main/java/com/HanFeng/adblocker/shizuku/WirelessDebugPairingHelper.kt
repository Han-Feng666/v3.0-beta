package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * 无线调试配对端口自动识别助手。
 *
 * Shizuku 官方通过 mDNS 协议发现「使用配对码配对设备」时手机暴露的
 * `_adb-tls-pairing._tcp` 服务,直接拿到配对端口,免去用户手动看一眼再复制 IP:端口的麻烦。
 *
 * 本类把 fork 内 moe.shizuku.manager.adb.AdbMdns 的核心逻辑独立实现一份,
 * 主 app 无需依赖 fork 的 adb pairing native 库就能用 mDNS 发现端口。
 */
@RequiresApi(Build.VERSION_CODES.R)
class WirelessDebugPairingHelper(private val context: Context) {

    companion object {
        private const val TAG = "WirelessDebugPair"
        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp"
    }

    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val discoveryListenerRef = AtomicReference<DiscoveryListener?>(null)

    /**
     * 已发现的配对端端口;null 表示尚未发现。
     * 同一时刻可能扫到多个服务,只取本机网卡上且端口可用的那个。
     */
    val discoveredPort = AtomicReference<Int?>(null)

    /**
     * 同时记录 host —— mDNS 服务发现的 host 可能为 IPv6 link-local 地址,
     * 对 adb pair 来说直接用 127.0.0.1 更稳(配对服务和 adbd 在本机)。
     */
    private val discoveredHost = AtomicReference<String?>("127.0.0.1")

    /**
     * 已发现的 connect 端端口(配对完成后再走 mDNS 发现 _adb-tls-connect._tcp 拿到);
     * null 表示尚未发现。
     */
    val discoveredConnectPort = AtomicReference<Int?>(null)

    /**
     * 启动 mDNS 服务发现。
     * @param onDiscovered 端口被发现时的回调(主线程回调取决于 NsdManager 实现)
     */
    fun startDiscovery(onDiscovered: (host: String, port: Int) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "mDNS discovery requires Android 11+")
            return
        }
        stopDiscovery()
        val listener = DiscoveryListener(onDiscovered)
        discoveryListenerRef.set(listener)
        try {
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices failed: ${e.message}")
        }
    }

    /**
     * 发现 pairing 端口后,再用此方法发现 ADB 连接端口(_adb-tls-connect._tcp)。
     * @param onDiscovered 端口被发现时回调
     * @param timeoutMs 最长等待时长,超时后回调 null
     */
    fun startConnectDiscovery(onDiscovered: (port: Int?) -> Unit, timeoutMs: Long = 5_000L) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onDiscovered(null)
            return
        }
        stopDiscovery()
        discoveredConnectPort.set(null)
        val listener = DiscoveryListener { _, port ->
            // mDNS 回调在内部线程,把结果转发到 onDiscovered
            if (discoveredConnectPort.compareAndSet(null, port)) {
                onDiscovered(port)
            }
        }
        discoveryListenerRef.set(listener)
        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices (connect) failed: ${e.message}")
            onDiscovered(null)
            return
        }
        // 超时回退
        Thread {
            try {
                Thread.sleep(timeoutMs)
                if (discoveredConnectPort.get() == null) {
                    onDiscovered(null)
                }
            } catch (_: InterruptedException) {}
        }.start()
    }

    fun stopDiscovery() {
        discoveryListenerRef.getAndSet(null)?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 拿最近一次发现的 host:port 字符串,如 "127.0.0.1:43254";
     * 未发现时返回 null。
     */
    fun getDiscoveredHostPort(): String? {
        val port = discoveredPort.get() ?: return null
        return "${discoveredHost.get() ?: "127.0.0.1"}:$port"
    }

    private inner class DiscoveryListener(
        private val onDiscovered: (host: String, port: Int) -> Unit
    ) : NsdManager.DiscoveryListener {

        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "mDNS discovery started: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "mDNS discovery start failed: $serviceType, error=$errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "mDNS discovery stopped: $serviceType")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "mDNS discovery stop failed: $serviceType, error=$errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "mDNS service found: ${serviceInfo.serviceName}")
            try {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        // 过滤本机网卡上的服务,避免连到隔壁电脑
                        val hostAddress = info.host?.hostAddress
                        val isLocal = isLocalAddress(hostAddress)
                        Log.d(TAG, "mDNS resolved: name=${info.serviceName}, host=$hostAddress, port=${info.port}, isLocal=$isLocal")
                        if (!isLocal) return
                        if (info.port <= 0 || info.port > 65535) return
                        if (discoveredPort.compareAndSet(null, info.port)) {
                            // adb pair 在本机执行,host 用 127.0.0.1 最稳;
                            // (即使 mDNS 拿到的是 192.168.x.x,adb pair 仍连本机 adbd 配对端口)
                            onDiscovered("127.0.0.1", info.port)
                        }
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS resolve failed: ${serviceInfo.serviceName}, error=$errorCode")
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "resolveService failed: ${e.message}")
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "mDNS service lost: ${serviceInfo.serviceName}")
            discoveredPort.set(null)
        }
    }

    /**
     * 判断 hostAddress 是否为本机网卡地址。
     * adbd 暴露的 mDNS 服务通常会同时出现在多个网卡上(loopback/wlan),
     * 这里只要本机网卡上找到就认为可配对。
     */
    private fun isLocalAddress(hostAddress: String?): Boolean {
        if (hostAddress == null) return false
        if (hostAddress == "127.0.0.1" || hostAddress == "::1") return true
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence().any { ni ->
                ni.inetAddresses.asSequence().any { it.hostAddress == hostAddress }
            }
        } catch (_: Exception) {
            false
        }
    }
}
