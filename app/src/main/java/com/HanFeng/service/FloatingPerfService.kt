package com.HanFeng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.HanFeng.adblocker.shizuku.PerfMonitor
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.PerfFloatMode
import kotlin.math.abs

class FloatingPerfService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var floatView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var monitoring = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!monitoring) return
            refreshFloatContent()
            handler.postDelayed(this, FeatureSettingsRepository.getPerfFloatIntervalMs(this@FloatingPerfService))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        showFloatingWindow()
        monitoring = FeatureSettingsRepository.isPerfFloatEnabled(this)
        if (monitoring) handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enabled = FeatureSettingsRepository.isPerfFloatEnabled(this)
        if (!enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!monitoring) {
            monitoring = true
            handler.post(tickRunnable)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        monitoring = false
        handler.removeCallbacks(tickRunnable)
        floatView?.let {
            runCatching { windowManager.removeView(it) }
        }
        floatView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("性能悬浮窗运行中")
            .setContentText("显示前台应用的性能占用")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
        return builder.build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "性能悬浮窗", NotificationManager.IMPORTANCE_MIN)
            channel.description = "显示前台应用性能占用的悬浮窗服务"
            nm.createNotificationChannel(channel)
        }
    }

    private fun showFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flag,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 24
        layoutParams.y = 180
        floatView = buildFloatView()
        runCatching { windowManager.addView(floatView, layoutParams) }
            .onFailure {
                FeatureSettingsRepository.setPerfFloatEnabled(this, false)
                stopSelf()
                return
            }
    }

    private fun buildFloatView(): View {
        val dp = { value: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            val r = 16f
            background = createRoundedBackgroundDrawable(r)
            elevation = 6f
        }
        val labelView = TextView(this).apply {
            text = "..."
            setTextColor(Color.WHITE)
            textSize = 11f
            setSingleLine(true)
            setHorizontallyScrolling(false)
        }
        val valueView = TextView(this).apply {
            text = "..."
            setTextColor(Color.parseColor("#FF3DDC84"))
            textSize = 15f
            setSingleLine(true)
            setPadding(0, dp(2f), 0, 0)
        }
        container.addView(labelView)
        container.addView(valueView)
        container.tag = FloatViewHolders(labelView, valueView)
        installTouchListener(container)
        return container
    }

    private fun createRoundedBackgroundDrawable(radius: Float): android.graphics.drawable.Drawable {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.cornerRadius = radius
        drawable.setColor(Color.argb(170, 0, 0, 0))
        drawable.setStroke(1, Color.argb(80, 255, 255, 255))
        return drawable
    }

    private fun installTouchListener(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 4 || abs(dy) > 4) moved = true
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, layoutParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleFloatMode()
                    moved
                }
                else -> false
            }
        }
    }

    private fun toggleFloatMode() {
        val current = FeatureSettingsRepository.getPerfFloatMode(this)
        val newMode = if (current == PerfFloatMode.CPU) PerfFloatMode.MEMORY else PerfFloatMode.CPU
        FeatureSettingsRepository.setPerfFloatMode(this, newMode)
        refreshFloatContent()
    }

    private fun refreshFloatContent() {
        val view = floatView ?: return
        val holders = view.tag as? FloatViewHolders ?: return
        Thread {
            val snapshot = PerfMonitor.snapshotForeground(this)
            handler.post {
                if (snapshot == null) {
                    holders.label.text = "前台应用"
                    holders.value.text = "..."
                    return@post
                }
                val mode = FeatureSettingsRepository.getPerfFloatMode(this)
                val value = if (mode == PerfFloatMode.MEMORY) {
                    formatMemory(snapshot.rssKb)
                } else {
                    "%.1f%%".format(snapshot.cpuPercent)
                }
                holders.label.text = snapshot.name
                holders.value.text = value
            }
        }.start()
    }

    private fun formatMemory(kb: Long): String {
        return if (kb < 1024L) "${kb}KB"
        else if (kb < 1024L * 1024L) "%.0fMB".format(kb / 1024.0)
        else "%.1fGB".format(kb / 1024.0 / 1024.0)
    }

    private data class FloatViewHolders(val label: TextView, val value: TextView)

    companion object {
        private const val CHANNEL_ID = "perf_panel"
        private const val NOTIFICATION_ID = 2002
    }
}
