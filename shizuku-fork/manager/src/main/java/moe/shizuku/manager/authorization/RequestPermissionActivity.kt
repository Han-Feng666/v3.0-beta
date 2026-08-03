package moe.shizuku.manager.authorization

import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RequestPermissionActivity : AppCompatActivity() {

    private lateinit var dialog: Dialog

    private fun setResult(requestUid: Int, requestPid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
        val data = Bundle()
        data.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        data.putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
        try {
            Shizuku.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
        } catch (_: Throwable) {
        }
    }

    private fun checkSelfPermission(): Boolean {
        return Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
    }

    private fun waitForBinder(): Boolean {
        val countDownLatch = CountDownLatch(1)
        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                countDownLatch.countDown()
                Shizuku.removeBinderReceivedListener(this)
            }
        }
        Shizuku.addBinderReceivedListenerSticky(listener, Handler(Looper.getMainLooper()))
        return try {
            countDownLatch.await(5, TimeUnit.SECONDS)
            true
        } catch (_: TimeoutException) {
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!waitForBinder()) {
            finish()
            return
        }

        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val requestCode = intent.getIntExtra("requestCode", -1)
        @Suppress("DEPRECATION")
        val ai = intent.getParcelableExtra<ApplicationInfo>("applicationInfo")
        if (uid == -1 || pid == -1 || ai == null) {
            finish()
            return
        }
        if (!checkSelfPermission()) {
            setResult(uid, pid, requestCode, allowed = false, onetime = true)
            finish()
            return
        }

        val label = try {
            ai.loadLabel(packageManager)
        } catch (_: Exception) {
            ai.packageName
        }

        // 构造对话框(简化版,不再依赖 Helps / Rikka html-core)
        val view = TextView(this).apply {
            text = "是否允许 \"$label\" 使用 Shizuku？\n\n该应用可能使用 Shizuku 执行特权系统操作，请仅对可信应用授权。"
            setPadding(48, 48, 48, 48)
            movementMethod = LinkMovementMethod.getInstance()
        }
        // 关键修复:不能用 MaterialAlertDialogBuilder,在 MIUI/HyperOS Android 16 上会 inflate m3_alert_dialog 必崩
        // 改用 androidx.appcompat.app.AlertDialog.Builder,与主 app 的 StableDialog 一致
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Shizuku 授权请求")
                .setView(view)
                .setPositiveButton("允许") { _, _ ->
                    setResult(uid, pid, requestCode, allowed = true, onetime = false)
                }
                .setNegativeButton("拒绝（仅本次）") { _, _ ->
                    setResult(uid, pid, requestCode, allowed = false, onetime = true)
                }
                .setCancelable(false)
                .setOnDismissListener {
                    try { (it as? androidx.appcompat.app.AlertDialog)?.findViewById<TextView>(android.R.id.message)?.movementMethod = null } catch (_: Throwable) {}
                    if (!isFinishing) finish()
                }
                .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }
}
