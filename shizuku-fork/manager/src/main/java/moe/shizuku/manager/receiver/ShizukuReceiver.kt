package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 老 Shizuku 兼容用 receiver，新版不用。保留空实现以让 manifest 通过。
 */
class ShizukuReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // no-op: 我们不再支持 v10 老 binder 请求
    }
}
