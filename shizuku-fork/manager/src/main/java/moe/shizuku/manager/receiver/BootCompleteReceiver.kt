package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.starter.Starter

/**
 * 简化版 BOOT_COMPLETED receiver，只保留 Root 自启路径。
 * 调 Starter.internalCommand 通过 root shell 启动 Shizuku server。
 没有 libsu 依赖时直接 fork su 即可。
 */
class BootCompleteReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ShizukuBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }
        try {
            val cmd = arrayOf("su", "-c", Starter.internalCommand)
            val proc = Runtime.getRuntime().exec(cmd)
            // 10s 超时
            Thread {
                try {
                    proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                    if (proc.isAlive) proc.destroyForcibly()
                } catch (_: Throwable) {}
            }.start()
            Log.i(TAG, "Shizuku service started on boot via root")
        } catch (e: Exception) {
            Log.e(TAG, "boot start failed: ${e.message}")
        }
    }
}
