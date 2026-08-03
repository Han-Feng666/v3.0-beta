package com.HanFeng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.HanFeng.adblocker.shizuku.BuiltInShizukuStarter
import com.HanFeng.adblocker.shizuku.WirelessDebugPairingHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无线调试配对悬浮窗服务。
 *
 * 启动后:
 *   1. 跳开发者选项页 (用户能看到配对码和配对端口)
 *   2. 屏幕顶部加可拖动的悬浮窗:状态行 + 「6 位配对码」EditText + 「配对并激活」按钮
 *   3. 后台 mDNS _adb-tls-pairing._tcp 自动发现端口,发现后按钮启用
 *   4. 用户在开发者选项页输完 6 位码直接点悬浮窗按钮,同步走 SPAKE2+TLS/AdbClient.shellCommand 拉起 Shizuku
 */
class WirelessDebugFloatingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 20001
        private const val CHANNEL_ID = "wireless_debug_pairing_floating"
        private const val MAX_MDNS_WAIT_MS = 15_000L

        @Volatile private var running: Boolean = false
        fun isRunning(): Boolean = running

        fun start(context: Context) {
            val intent = Intent(context, WirelessDebugFloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var statusTv: TextView? = null
    private var activateBtn: Button? = null
    private var pairingHelper: WirelessDebugPairingHelper? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var discoveredHost: String? = null
    @Volatile private var discoveredPort: Int? = null
    @Volatile private var pairing: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        // 关键: 先把悬浮窗 addView 完, 再 startActivity 跳开发者选项。
        // awaitFrame 不保证 addView 完成在 startActivity 切换 Activity 之前;
        // 而 HyperOS 等 ROM 在 Activity 进入后台时对 TYPE_APPLICATION_OVERLAY 的渲染调度会变更保守,
        // 若 addView 还没 attached 就切到开发者选项, 悬浮窗可能被 SystemUI 临时屏蔽直到下次 reparent。
        // 这里同步 addView, 再延迟一点点切开发者选项确保 view 真正上屏。
        scope.launch {
            addFloatingWindow()
            kotlinx.coroutines.delay(150)
            jumpToWirelessDebuggingSettings()
            startMdnsDiscovery()
        }
    }

    private fun jumpToWirelessDebuggingSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        }.onFailure {
            android.util.Log.w("WirelessDebugFloating", "jump to dev options failed: ${it.message}")
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            runCatching { mgr.deleteNotificationChannel(CHANNEL_ID) }
            val channel = NotificationChannel(
                CHANNEL_ID,
                "无线调试配对悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在前台运行悬浮窗让用户在开发者选项页直接输配对码"
                setShowBadge(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            mgr.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在配对激活 Shizuku")
            .setContentText("返回开发者选项页面在悬浮框输配对码")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun addFloatingWindow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "未授予悬浮窗权限,请到设置开启", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        if (rootView != null) return

        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), Resources.getSystem().displayMetrics
            ).toInt()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF0181818.toInt())  // 94% 不透明黑
                setStroke(dp(1), 0x44FFFFFF)
            }
        }
        val matchWidth = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val title = TextView(this).apply {
            text = "Shizuku 配对激活"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(title, matchWidth)

        val status = TextView(this).apply {
            text = "正在搜索配对端口…"
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        statusTv = status
        root.addView(status, matchWidth)

        val pairCodeEt = EditText(this).apply {
            hint = "6 位配对码"
            setSingleLine(true)
            maxLines = 1
            inputType = EditorInfo.TYPE_CLASS_NUMBER
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0x22FFFFFF)
            }
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x88FFFFFF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(pairCodeEt, matchWidth)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnActivate = Button(this).apply {
            text = "配对并激活"
            setTextColor(0xFF181818.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            isEnabled = false  // 端口发现前禁用
        }
        activateBtn = btnActivate
        val btnCancel = Button(this).apply {
            text = "取消"
            setTextColor(0xFFB0B0B0.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x33FFFFFF)
            }
        }
        buttonsRow.addView(
            btnActivate,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = dp(8)
            }
        )
        buttonsRow.addView(btnCancel)
        root.addView(buttonsRow, matchWidth)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24)
            y = dp(80)
        }
        attachTouchListener(root, params)
        val prefs = this.getSharedPreferences("_wireless_pair_size", Context.MODE_PRIVATE)
        val widthPx = prefs.getInt("width_dp", 280)
        val widthPxScaled = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, widthPx.toFloat(), Resources.getSystem().displayMetrics
        ).toInt()
        params.width = widthPxScaled
        // 触发重新 layout
        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            windowManager.addView(root, params)
        }
        rootView = root

        btnActivate.setOnClickListener {
            if (pairing) return@setOnClickListener
            val code = pairCodeEt.text?.toString().orEmpty().trim()
            if (code.length != 6 || !code.all { it.isDigit() }) {
                Toast.makeText(this, "配对码应为 6 位数字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val host = discoveredHost ?: "127.0.0.1"
            val port = discoveredPort
            if (port == null) {
                Toast.makeText(this, "尚未发现配对端口,请稍候", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(pairCodeEt.windowToken, 0)
            pairCodeEt.clearFocus()
            pairAndActivate(host, port, code, btnActivate, status, progressBar)
        }
        btnCancel.setOnClickListener {
            cleanupAndStop()
        }
        pairCodeEt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnActivate.performClick()
                true
            } else false
        }
    }

    /** 让悬浮窗可拖动;触摸距离小于阈值算点击传给子 view (Button 点击才能触发) */
    private fun attachTouchListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = params.x
        var initialY = params.y
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = ev.rawX
                    initialTouchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - initialTouchX
                    val dy = ev.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100f) {  // 10px 阈值
                        moved = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                        false  // 让事件继续派给子 view (Button 才能触发 onClick)
                    } else {
                        true
                    }
                }
                else -> false
            }
        }
    }

    private fun startMdnsDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            updateStatus("无线调试需要 Android 11+")
            return
        }
        pairingHelper?.stopDiscovery()
        val helper = WirelessDebugPairingHelper(this)
        pairingHelper = helper
        helper.startDiscovery { host, port ->
            scope.launch {
                if (!running) return@launch
                discoveredHost = host
                discoveredPort = port
                updateStatus("已发现配对端口: $host:$port\n点'配对并激活'开始")
                activateBtn?.isEnabled = true
            }
        }
    }

    private fun updateStatus(text: String) {
        statusTv?.text = text
    }

    private fun pairAndActivate(
        host: String,
        port: Int,
        pairCode: String,
        btn: Button,
        statusTv: TextView,
        progressBar: ProgressBar
    ) {
        pairing = true
        btn.isEnabled = false
        btn.text = "配对中…"
        progressBar.visibility = View.VISIBLE
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BuiltInShizukuStarter.pairAndActivateViaWirelessDebug(pairCode, host, port)
                }.getOrDefault(
                    BuiltInShizukuStarter.ActivationResult(false, "wireless", "激活异常")
                )
            }
            pairing = false
            progressBar.visibility = View.GONE
            btn.isEnabled = true
            btn.text = "配对并激活"
            if (!running) return@launch
            statusTv.text = if (result.success) {
                "Shizuku 已激活"
            } else {
                "激活失败: ${result.message}"
            }
            Toast.makeText(
                this@WirelessDebugFloatingService,
                if (result.success) "Shizuku 已激活" else "激活失败,详见悬浮窗",
                Toast.LENGTH_LONG
            ).show()
            if (result.success) {
                delay(2000)
                cleanupAndStop()
            }
        }
    }

    private fun cleanupAndStop() {
        pairingHelper?.stopDiscovery()
        pairingHelper = null
        rootView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {}
        }
        rootView = null
        statusTv = null
        activateBtn = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        cleanupAndStop()
        scope.cancel()
        super.onDestroy()
    }
}
