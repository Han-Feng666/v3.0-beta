package com.HanFeng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.widget.TextViewCompat
import com.HanFeng.R
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.StatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮球服务
 * 通过 WindowManager 在屏幕上显示一个小型悬浮窗，展示单个信息项：
 * - 拦截数量：今日已拦截的请求次数
 * - 内存/CPU：当前前台应用的内存占用和 CPU 占比
 *
 * 支持两种形状：
 * - SHAPE_CIRCLE：圆形（默认）
 * - SHAPE_CAPSULE：横向胶囊（圆角矩形，宽 > 高）
 *
 * 展示形态由用户在设置中自选；缺少悬浮窗权限时自动停止。
 */
class FloatingBallService : Service() {

    companion object {
        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_CAPSULE = "capsule"

        const val DATA_BLOCK_COUNT = "block_count"
        const val DATA_MEMORY_CPU = "memory_cpu"

const val PREFS_NAME = "floating_ball_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_SHAPE = "shape"
        const val KEY_DATA_TYPE = "data_type"
        const val KEY_SCALE = "scale_level"
        const val KEY_PROCESS_MONITOR = "process_monitor"

        // 球体尺寸/字号档位：0=最小 4=最大，默认 2 = 1.0x
        const val SCALE_LEVEL_MIN = 0
        const val SCALE_LEVEL_MAX = 4
        const val SCALE_LEVEL_DEFAULT = 2
        private val SCALE_FACTORS = floatArrayOf(0.7f, 0.85f, 1.0f, 1.2f, 1.4f)

        private const val TAG = "FloatingBallService"
        private const val REFRESH_INTERVAL_MS = 1000L
        private const val CHANNEL_ID = "hf_floating_ball"

        @Volatile private var running: Boolean = false
        fun isRunning(): Boolean = running
        private const val NOTIFICATION_ID = 0xF8B1

        fun isEnabled(context: Context): Boolean {
            return prefs(context).getBoolean(KEY_ENABLED, false)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun isProcessMonitorEnabled(context: Context): Boolean {
            return prefs(context).getBoolean(KEY_PROCESS_MONITOR, true)
        }

        fun setProcessMonitorEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_PROCESS_MONITOR, enabled).apply()
        }

        fun getShape(context: Context): String {
            return prefs(context).getString(KEY_SHAPE, SHAPE_CIRCLE) ?: SHAPE_CIRCLE
        }

        fun setShape(context: Context, shape: String) {
            prefs(context).edit().putString(KEY_SHAPE, shape).apply()
        }

        fun getDataType(context: Context): String {
            return prefs(context).getString(KEY_DATA_TYPE, DATA_BLOCK_COUNT) ?: DATA_BLOCK_COUNT
        }

        fun setDataType(context: Context, type: String) {
            prefs(context).edit().putString(KEY_DATA_TYPE, type).apply()
        }

        fun getScaleLevel(context: Context): Int {
            val v = prefs(context).getInt(KEY_SCALE, SCALE_LEVEL_DEFAULT)
            return v.coerceIn(SCALE_LEVEL_MIN, SCALE_LEVEL_MAX)
        }

        fun getScaleFactor(context: Context): Float {
            val level = getScaleLevel(context)
            return SCALE_FACTORS[level.coerceIn(SCALE_LEVEL_MIN, SCALE_LEVEL_MAX)]
        }

        fun setScaleLevel(context: Context, level: Int) {
            val safe = level.coerceIn(SCALE_LEVEL_MIN, SCALE_LEVEL_MAX)
            prefs(context).edit().putInt(KEY_SCALE, safe).apply()
        }

        fun getScaleFactorLabel(context: Context): String {
            val factor = getScaleFactor(context)
            return "${(factor * 100).toInt()}%"
        }

        fun hasOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun startIfEnabled(context: Context) {
            if (!isEnabled(context)) return
            if (!hasOverlayPermission(context)) return
            runCatching {
                context.startForegroundService(Intent(context, FloatingBallService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingBallService::class.java)) }
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler

    // 用于订阅 ProcessMonitor Flow 的协程作用域
    private var processMonitorJob: kotlinx.coroutines.Job? = null
    private val processScope = CoroutineScope(Dispatchers.IO)

    private var rootView: View? = null

    // 缓存对当前 Layout 的 TextView 引用，避免每秒刷新都 findViewById 走 view hierarchy
    private var labelView: TextView? = null
    private var valueView: TextView? = null

    // 缓存配置，避免每秒读 SP
    @Volatile private var cachedShape: String = SHAPE_CIRCLE
    @Volatile private var cachedDataType: String = DATA_BLOCK_COUNT

    private var lastX = 0f
    private var lastY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = 12 // px

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshContent()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    @Volatile private var lastLabel: String? = null
    @Volatile private var lastValue: String? = null
    @Volatile private var lastForegroundApp: ProcessMonitor.ProcessInfo? = null

    private fun scheduleProcessMonitor() {
        // 只有当用户开启了进程监控且显示类型为内存/CPU时才启动
        if (!FloatingBallService.isProcessMonitorEnabled(this) || cachedDataType != DATA_MEMORY_CPU) {
            return
        }
        processMonitorJob?.cancel()
        processMonitorJob = processScope.launch {
            // 悬浮球场景不需要全设备扫描（耗电主因）。改用 FOREGROUND_ONLY 轻采样，
            // 只读 1 个前台 PID；findForegroundApp 取不到时，flow 不更新，悬浮球维持上次显示。
            ProcessMonitor.getInstance(this@FloatingBallService)
                .startSampling(processScope, ProcessMonitor.SamplingMode.FOREGROUND_ONLY)
            ProcessMonitor.getInstance(this@FloatingBallService).processFlow
                .collect { processList ->
                    // 更新缓存的前台应用
                    val fg = processList.firstOrNull { it.isForeground }
                        ?: processList.firstOrNull { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
                        ?: processList.firstOrNull()
                    if (fg != null) {
                        lastForegroundApp = fg
                    }
                }
        }
    }

    private fun scheduleMemoryRefresh() {
        // 不再需要单独的 memoryRefreshRunnable，ProcessMonitor 已经在 1s 间隔推送数据
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        cachedShape = getShape(this)
        cachedDataType = getDataType(this)
        // 必须在 5s 内 startForeground，否则 Android 7.0+ 启动的 ForegroundService 会被系统强杀整个进程
        runCatching { startForeground(NOTIFICATION_ID, buildSilentNotification()) }.onFailure {
            Log.e(TAG, "startForeground failed: ${it.message}")
        }
        try {
            addBall()
            handler.post(refreshRunnable)
            scheduleProcessMonitor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating ball: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 重新刷新缓存，确保用户修改设置后再启用时拿到最新值
        cachedShape = getShape(this)
        cachedDataType = getDataType(this)
        // 重新绑定对应的 view 引用
        rebindCachedViews()
        // 数据类型改变时重新调度进程监控
        scheduleProcessMonitor()
        return START_STICKY
    }

    private fun buildSilentNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "保持悬浮球持续显示"
                        setShowBadge(false)
                    }
                )
            }
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, com.HanFeng.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HanFeng 悬浮球")
            .setContentText("悬浮球正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }

    private fun rebindCachedViews() {
        val view = rootView ?: run {
            labelView = null
            valueView = null
            return
        }
        if (cachedShape == SHAPE_CAPSULE) {
            labelView = view.findViewById(R.id.tvBallLabelCapsule)
            valueView = view.findViewById(R.id.tvBallValueCapsule)
        } else {
            labelView = view.findViewById(R.id.tvBallTitleCircle)
            valueView = view.findViewById(R.id.tvBallValueCircle)
        }
        // 自适应字号：长数字（如 1.2K / 325M · 15%）能自动缩小到自适应范围，不会被 maxLines+ellipsize 截断
        runCatching {
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                valueView!!,
                7,  // 最小 7sp
                16, // 最大 16sp
                1,  // step 1sp
                TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        processMonitorJob?.cancel()
        ProcessMonitor.getInstance(this).stopSampling()
        removeBall()
        running = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 悬浮球服务的存活由公开 API (Enabled/stop) 控制，不在这里停止
    }

    private fun addBall() {
        val shape = cachedShape
        val layoutRes = if (shape == SHAPE_CAPSULE) {
            R.layout.floating_ball_capsule
        } else {
            R.layout.floating_ball_circle
        }

        val view = LayoutInflater.from(this).inflate(layoutRes, null, false)
        // 应用用户选择的尺寸/字号档位
        applyUserScale(view)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        attachTouchListener(view, params)
        windowManager.addView(view, params)
        rootView = view
        rebindCachedViews()
    }

    /**
     * 按 [getScaleFactor] 整体缩放悬浮球 LinearLayout 的尺寸、padding 与子 TextView 字号。
     * 不依赖 dimen 重打包，避免改 dimens 影响所有控件产生连锁尺寸异常。
     */
    private fun applyUserScale(root: View) {
        val factor = getScaleFactor(this)
        if (factor == 1.0f) return
        val density = resources.displayMetrics.density
        val rootBall = root.findViewById<android.view.ViewGroup>(
            if (cachedShape == SHAPE_CAPSULE) R.id.ballRootCapsule else R.id.ballRootCircle
        ) ?: return
        val newW = (rootBall.layoutWidth() * factor).toInt().coerceAtLeast((40 * density).toInt())
        val newH = (rootBall.layoutHeight() * factor).toInt().coerceAtLeast((40 * density).toInt())
        rootBall.layoutParams = rootBall.layoutParams.apply {
            width = newW
            height = newH
        }
        // padding 等比缩放
        val padOrig = rootBall.paddingLeft
        val padNew = (padOrig * factor).toInt()
        rootBall.setPaddingRelative(padNew, padNew, padNew, padNew)

        // 子 TextView 字号等比缩放
        val scaleTextSize: (TextView?) -> Unit = { tv ->
            if (tv != null) {
                val orig = tv.textSize // px
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, orig * factor)
            }
        }
        scaleTextSize(root.findViewById(R.id.tvBallLabelCapsule))
        scaleTextSize(root.findViewById(R.id.tvBallValueCapsule))
        scaleTextSize(root.findViewById(R.id.tvBallTitleCircle))
        scaleTextSize(root.findViewById(R.id.tvBallValueCircle))
    }

    private fun android.view.View.layoutWidth(): Int {
        val w = layoutParams?.width ?: return width
        return if (w > 0) w else width
    }

    private fun android.view.View.layoutHeight(): Int {
        val h = layoutParams?.height ?: return height
        return if (h > 0) h else height
    }

    private fun removeBall() {
        rootView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        rootView = null
        labelView = null
        valueView = null
    }

    private fun getLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun attachTouchListener(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    lastX = event.rawX
                    lastY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    if (kotlin.math.abs(event.rawX - initialTouchX) > touchSlop ||
                        kotlin.math.abs(event.rawY - initialTouchY) > touchSlop
                    ) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        openMainActivity()
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun openMainActivity() {
        runCatching {
            val intent = Intent(this, com.HanFeng.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun refreshContent() {
        if (rootView == null) return
        // 缓存的 shape 已在 onCreate / onStartCommand 时落地; 切到其它分支也只引用生效的 labelView/valueView
        val dataType = cachedDataType

        val (label, value) = computeDisplay(dataType)

        // 仅在实际变化时调 setText，避免每秒无意义 invalidate / layout / draw
        if (lastLabel != label) {
            labelView?.text = label
            lastLabel = label
        }
        if (lastValue != value) {
            valueView?.text = value
            lastValue = value
        }
    }

    private fun computeDisplay(dataType: String): Pair<String, String> {
        val running = runCatching { NetworkKernel.isRunning() }.getOrDefault(false)
        return when (dataType) {
            DATA_MEMORY_CPU -> {
                val app = lastForegroundApp
                if (app == null) {
                    "占用" to "--"
                } else {
                    val appLabel = resolveAppName(app.packageName)
                    val mem = formatMem(app.rssBytes)
                    val cpu = "%.0f%%".format(app.cpuPercent.coerceAtLeast(0f))
                    appLabel to "$mem/$cpu"
                }
            }
            else -> {
                val count = runCatching {
                    StatsRepository.peekTodayBlocked(this)
                }.getOrDefault(0)
                if (running || count > 0) {
                    "拦截" to formatNumber(count)
                } else {
                    "拦截" to "0"
                }
            }
        }
    }

    @Volatile private var lastForegroundPkg: String? = null
    @Volatile private var lastForegroundLabel: String? = null
    @Volatile private var labelLookupInProgress: Boolean = false

    /**
     * 从包名解析用户可读的应用名。Async resolve：第一次见到新包名在 IO 线程调 PackageManager.loadLabel,
     * 拿到结果前先用 packageName 兜底显示，避免悬浮球空白。
     * 比如 QQ 的 packageName = "com.tencent.mobileqq"，UI 上展示的应当是"QQ"用户可见名。
     */
    private fun resolveAppName(packageName: String): String {
        if (packageName.isEmpty()) return "本应用"
        if (packageName == lastForegroundPkg && lastForegroundLabel != null) {
            return lastForegroundLabel!!
        }
        if (labelLookupInProgress) return packageName
        labelLookupInProgress = true
        processScope.launch(Dispatchers.IO) {
            try {
                val label = runCatching {
                    val ai = packageManager.getApplicationInfo(packageName, 0)
                    val raw = ai.loadLabel(packageManager).toString()
                    raw.ifBlank { packageName }
                }.getOrDefault(packageName)
                if (label != lastForegroundLabel || packageName != lastForegroundPkg) {
                    lastForegroundPkg = packageName
                    lastForegroundLabel = label
                    // 触发一次悬浮球刷新 (label 变了)
                    handler.post { refreshContent() }
                }
            } catch (_: Exception) {
            } finally {
                labelLookupInProgress = false
            }
        }
        return packageName
    }

    private fun formatNumber(n: Int): String {
        return when {
            n >= 10000 -> "%.1fW".format(n / 10000.0)
            n >= 1000 -> "%.1fK".format(n / 1000.0)
            else -> n.toString()
        }
    }

    private fun formatMem(bytes: Long): String {
        if (bytes <= 0) return "0"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            "%.1fG".format(mb / 1024)
        } else {
            "%.0fM".format(mb)
        }
    }
}