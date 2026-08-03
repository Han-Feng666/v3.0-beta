package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore

internal class WirelessDebugPairer(private val context: Context) {

    companion object {
        private const val TAG = "WirelessDebugPairer"
        private const val STARTER_BINARY_NAME = "libshizuku.so"
    }

    sealed class Result {
        data class Success(val message: String) : Result()
        data class Failure(val message: String) : Result()
    }

    fun pairAndActivate(pairingCode: String, host: String, port: Int): BuiltInShizukuStarter.ActivationResult {
        if (pairingCode.length != 6) {
            return BuiltInShizukuStarter.ActivationResult(false, "wireless", "配对码应为 6 位数字")
        }

        // 1. 准备本 app 的 ADB RSA Key
        val prefs = context.getSharedPreferences("wireless_adb_key", Context.MODE_PRIVATE)
        val key = try {
            AdbKey(PreferenceAdbKeyStore(prefs), context.packageName)
        } catch (t: Throwable) {
            return BuiltInShizukuStarter.ActivationResult(false, "wireless", "ADB Key 生成失败: ${t.message}")
        }

        // 2. 跟本机 adbd 做 SPAKE2+TLS 握手
        try {
            AdbPairingClient(host, port, pairingCode, key).use { client ->
                if (!client.start()) {
                    return BuiltInShizukuStarter.ActivationResult(false, "wireless", "ADB 配对返回 false")
                }
                Log.d(TAG, "adb pair succeeded, key written to /data/misc/adb/adb_keys")
            }
        } catch (t: Throwable) {
            return BuiltInShizukuStarter.ActivationResult(false, "wireless", "ADB 配对失败: ${t.message}")
        }

        // 3. mDNS 发现 connect 端口, 最长 5 秒
        val helper = WirelessDebugPairingHelper(context)
        val connectPort = discoverConnectPortSynchronous(helper, 5000L)
        helper.stopDiscovery()
        if (connectPort == null) {
            return BuiltInShizukuStarter.ActivationResult(
                false, "wireless",
                "配对成功但 5 秒内未发现 ADB 连接端口, 请稍后重试"
            )
        }
        Log.d(TAG, "adb connect port discovered: $connectPort")

        // 4. AdbClient connect + shell 跑 starter
        val apkPath = context.applicationInfo.sourceDir
        val soPath = pickStarterBinaryPath(context.applicationInfo.nativeLibraryDir)
            ?: return BuiltInShizukuStarter.ActivationResult(
                false, "wireless",
                "无法定位内置 starter ($STARTER_BINARY_NAME). 请重新安装本 app"
            )

        val starterShellCmd = "chmod 755 '$soPath' 2>/dev/null; exec '$soPath' --apk='$apkPath'"
        val buffer = StringBuilder()
        try {
            AdbClient(host, connectPort, key).use { client ->
                client.connect()
                client.shellCommand(starterShellCmd) { bytes ->
                    if (buffer.length < 2048) buffer.append(String(bytes))
                }
            }
        } catch (t: Throwable) {
            return BuiltInShizukuStarter.ActivationResult(
                false, "wireless",
                "ADB 连接/shell 执行失败: ${t.message}\n[starter out]\n$buffer"
            )
        }
        Log.d(TAG, "starter shell exec launched, out=${buffer.take(500)}")

        // 5. 等 binder 上来
        val binderUp = waitForBinderAlive(8_000L)
        return if (binderUp) {
            BuiltInShizukuStarter.ActivationResult(true, "wireless", "已通过无线调试激活 Shizuku 服务")
        } else {
            BuiltInShizukuStarter.ActivationResult(
                false, "wireless",
                "Starter 已发起, 但 8 秒内未收到 Binder, 可能 adbd 入口启动失败.\n[starter out]\n${buffer.take(500)}"
            )
        }
    }

    private fun discoverConnectPortSynchronous(
        helper: WirelessDebugPairingHelper,
        timeoutMs: Long
    ): Int? {
        val latch = CountDownLatch(1)
        val portRef = AtomicReference<Int?>(null)
        helper.startConnectDiscovery({ port ->
            if (port != null) {
                portRef.set(port)
                latch.countDown()
            } else {
                latch.countDown()
            }
        }, timeoutMs + 1000L)
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return portRef.get()
    }

    private fun waitForBinderAlive(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false) ||
                runCatching { rikka.shizuku.Shizuku.getBinder()?.isBinderAlive == true }.getOrDefault(false)
            if (alive) return true
            try { Thread.sleep(300) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun pickStarterBinaryPath(nativeLibraryDir: String): String? {
        if (nativeLibraryDir.isBlank()) return null
        val direct = "$nativeLibraryDir/$STARTER_BINARY_NAME"
        if (File(direct).isFile) return direct
        val dir = File(nativeLibraryDir)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { f ->
                if (f.name == STARTER_BINARY_NAME && f.isFile && f.canRead()) {
                    return f.absolutePath
                }
            }
        }
        return null
    }
}
