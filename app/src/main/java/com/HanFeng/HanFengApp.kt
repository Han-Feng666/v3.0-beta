package com.HanFeng

import android.app.Application
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.HanFeng.adblocker.shizuku.BuiltInShizukuStarter
import com.HanFeng.service.IdleShutdownController
import moe.shizuku.manager.`init` as initShizukuManager
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HanFengApp : Application() {

    companion object {
        @Volatile
        var appStartElapsedMs: Long = 0L
            private set

        /**
         * 全局前台 Activity 计数:每个 Activity onStart +1, onStop -1。
         * 用于 BaseActivity 判断 App 是否真的退到后台(全部 Activity 都 stopped),
         * 避免在 Activity 间导航时误触发 finishAndRemoveTask。
         *
         * 之前的实现用 BaseActivity 的静态 startedActivityCount,在新 Activity 的 onStart
         * 来不及执行时(比如子 Activity 启动慢、系统调度延迟),旧 Activity 的 1.2s 延迟 Runnable
         * 会误触发 finishAndRemoveTask 把自己干掉,导致用户从子页面返回时直接回到主界面。
         */
        private val globalStartedActivityCount = AtomicInteger(0)

        /**
         * App 是否在前台(至少有一个 started 状态的 Activity)。
         * BaseActivity.onStop 的延迟 Runnable 会读取这个值判断是否真的退到后台。
         */
        @JvmStatic
        fun isAppInForeground(): Boolean = globalStartedActivityCount.get() > 0
    }

    // 后台单线程执行器:用于启动日志/崩溃文件写入,避免阻塞主线程 IO
    private val backgroundExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HanFeng-bg").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        appStartElapsedMs = SystemClock.elapsedRealtime()
        writeStartupLog("Application.onCreate")
        installGlobalCrashHandler()
        writeStartupLog("CrashHandler installed")
        registerForegroundStateTracker()
        writeStartupLog("ForegroundStateTracker installed")
        IdleShutdownController.init(this)
        writeStartupLog("IdleShutdownController installed")
        backgroundExecutor.execute {
            runCatching { com.HanFeng.data.StatsRepository.warmup(this@HanFengApp) }
        }
        initializeBuiltInShizuku()
        writeStartupLog("BuiltInShizuku initialized")
    }

    /**
     * 注册全局 Activity 生命周期回调,维护 globalStartedActivityCount。
     * 这是判断 App 前后台状态的权威来源,BaseActivity 不再依赖自己的静态计数。
     */
    private fun registerForegroundStateTracker() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                globalStartedActivityCount.incrementAndGet()
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                globalStartedActivityCount.decrementAndGet()
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    /**
     * 初始化内置 Shizuku：
     * 1. BuiltInShizukuStarter.init —— 持有 appContext，后续 root / 无线配对激活时读取 nativeLibraryDir / sourceDir
     * 2. moe.shizuku.manager.init —— fork manager 持有 Application 引用，
     *    AuthorizationManager / RequestPermissionActivity 依赖这个全局 Application
     *    才能正确构建授权对话框 / 查询 PackageManager
     */
    private fun initializeBuiltInShizuku() {
        runCatching {
            BuiltInShizukuStarter.init(this)
        }.onFailure {
            android.util.Log.e("HanFengApp", "BuiltInShizukuStarter.init failed", it)
        }
        runCatching {
            initShizukuManager(this)
        }.onFailure {
            android.util.Log.e("HanFengApp", "moe.shizuku.manager.init failed", it)
        }
    }

    private fun installGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashToFile(thread, throwable)
            writeCrashToAndroidLog(thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashToAndroidLog(thread: Thread, throwable: Throwable) {
        runCatching {
            val report = buildString {
                appendLine("=== HanFeng Crash ===")
                appendLine("PID: ${Process.myPid()}")
                appendLine("Thread: ${thread.name}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
                val sw = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
                appendLine(sw.toString())
            }
            android.util.Log.e("HanFengCrash", report)
        }
    }

    fun writeStartupLog(milestone: String) {
        // 异步写入,避免主线程在 Application.onCreate 阶段阻塞刷盘
        val elapsed = SystemClock.elapsedRealtime() - appStartElapsedMs
        val entry = buildString {
            appendLine("=== HanFeng Startup Trace ===")
            appendLine("Milestone: $milestone")
            appendLine("Elapsed: ${elapsed}ms")
            appendLine("PID: ${Process.myPid()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
            appendLine()
        }
        backgroundExecutor.execute {
            runCatching {
                val traceFile = File(filesDir, "startup_trace.txt")
                // appendToFile + FileOutputStream(use=true) 比 appendText 更可控,appendText 内部走同样的流
                traceFile.appendText(entry)
            }
        }
    }

    private fun writeCrashToFile(thread: Thread, throwable: Throwable) {
        runCatching {
            val crashDir = File(filesDir, "crashes")
            crashDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
            val crashFile = File(crashDir, "crash_$timestamp.txt")
            val sw = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
            val report = buildString {
                appendLine("=== HanFeng Crash Report ===")
                appendLine("Time: $timestamp")
                appendLine("Thread: ${thread.name}")
                appendLine("PID: ${Process.myPid()}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                appendLine()
                appendLine(sw.toString())
                val cause = throwable.cause
                if (cause != null) {
                    appendLine()
                    appendLine("=== Caused by ===")
                    val csw = StringWriter().also { cause.printStackTrace(PrintWriter(it)) }
                    appendLine(csw.toString())
                }
            }
            FileOutputStream(crashFile).use { it.write(report.toByteArray()) }
        }
    }
}
