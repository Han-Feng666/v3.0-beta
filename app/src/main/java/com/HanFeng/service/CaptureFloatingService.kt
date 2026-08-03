package com.HanFeng.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.HanFeng.R
import com.HanFeng.capture.CaptureController
import com.HanFeng.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * 抓包实时悬浮窗(design Components #13 / requirements R1.3)。
 *
 * - 仅在 [CaptureController] active 且用户授权 overlay 权限时可见
 * - 显示当前条目计数; 点击跳到 MainActivity 的抓包 Tab
 * - 命中断点时变色 + 闪烁(简化: 仅变为红色文本)
 *
 * 引用 FloatingBallService 范本。
 */
class CaptureFloatingService : Service(), LifecycleOwner {

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasOverlayPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 批次 A 收尾: 必须先 startForeground, 否则 Android 8+ 调 startForegroundService 5s 内不调 startForeground 会崩溃
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(0)) }.onFailure {
            android.util.Log.e(TAG, "startForeground failed: ${it.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        attachBadge()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return START_STICKY
    }

    private fun buildNotification(count: Int): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.capture_floating_channel_name),
                        android.app.NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = getString(R.string.capture_floating_channel_desc)
                        setShowBadge(false)
                    }
                )
            }
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 1001,
            Intent(this, com.HanFeng.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(com.HanFeng.ui.MainActivity.EXTRA_OPEN_TAB, com.HanFeng.ui.MainActivity.TAB_INDEX_CAPTURE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_floating_notif_title))
            .setContentText(getString(R.string.capture_floating_notif_text, count))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        detachBadge()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun attachBadge() {
        if (rootView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.capture_floating_badge, null, false)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24; y = 220
        }
        attachTouchListener(view, params)
        windowManager.addView(view, params)
        rootView = view
        observeCount(view as TextView)
    }

    private fun getLayoutType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun detachBadge() {
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
    }

    private fun attachTouchListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = e.rawX
                    initialTouchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (e.rawX - initialTouchX).toInt()
                    params.y = initialY + (e.rawY - initialTouchY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(e.rawX - initialTouchX) < 10 &&
                        Math.abs(e.rawY - initialTouchY) < 10
                    ) {
                        launchMainActivity()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_INDEX_CAPTURE)
        }
        startActivity(intent)
    }

    private fun observeCount(badge: TextView) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureController.entries.collect {
                    val s = CaptureController.current.value
                    if (!s.active) {
                        stopSelf()
                        return@collect
                    }
                    val count = CaptureController.snapshot().size
                    badge.text = getString(R.string.capture_floating_count, count)
                    // 同步更新通知栏数字(批次 A 收尾)
                    runCatching {
                        val nm = getSystemService(android.app.NotificationManager::class.java)
                        nm?.notify(NOTIFICATION_ID, buildNotification(count))
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "CaptureFloatingService"
        private const val CHANNEL_ID = "capture_floating"
        private const val NOTIFICATION_ID = 0xCA01

        /**
         * 仅在抓包 active + 已拿到 overlay 权限时启动悬浮服务。
         * 调用点: [com.HanFeng.capture.CaptureController.enable] 成功后 (由 AdBlockVpnService.startVpn 路径触发)。
         */
        fun startIfCaptureActive(context: android.content.Context) {
            if (!CaptureController.current.value.active) return
            if (!hasOverlayPermission(context)) return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(Intent(context, CaptureFloatingService::class.java))
                } else {
                    context.startService(Intent(context, CaptureFloatingService::class.java))
                }
            }
        }

        fun stop(context: android.content.Context) {
            runCatching { context.stopService(Intent(context, CaptureFloatingService::class.java)) }
        }

        private fun hasOverlayPermission(context: android.content.Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    }
}
