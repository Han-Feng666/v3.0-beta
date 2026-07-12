package com.HanFeng.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.HanFeng.adblocker.shizuku.GameAntiMarkManager
import com.HanFeng.adblocker.shizuku.HotspotInterceptor
import com.HanFeng.adblocker.shizuku.RootHideAppWatcher
import com.HanFeng.core.network.NetworkKernel

object IdleShutdownController {

    private const val TAG = "IdleShutdown"
    private const val IDLE_THRESHOLD_MILLIS = 60_000L
    private const val TICK_INTERVAL_MILLIS = 15_000L

    @Volatile private var started: Boolean = false
    @Volatile private var activityCount: Int = 0
    @Volatile private var lastActiveAt: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var appRef: Application

    private val tickRunnable = object : Runnable {
        override fun run() {
            checkAndMaybeShutdown()
            handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
        override fun onActivityStarted(a: Activity) {
            activityCount++
            markActive()
        }
        override fun onActivityStopped(a: Activity) {
            if (activityCount > 0) activityCount--
        }
        override fun onActivityResumed(a: Activity) { markActive() }
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    fun init(application: Application) {
        if (started) return
        started = true
        appRef = application
        lastActiveAt = System.currentTimeMillis()
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MILLIS)
    }

    fun markActive() {
        lastActiveAt = System.currentTimeMillis()
    }

    private fun isAppInForeground(): Boolean = activityCount > 0

    private fun anyServiceInService(context: Context): Boolean {
        if (NetworkKernel.isRunning()) return true
        if (runCatching { HotspotInterceptor.isDnsHijackRunning() }.getOrDefault(false)) return true
        if (RootHideAppWatcher.isRunning()) return true
        if (GameAntiMarkManager.isRunning()) return true
        return false
    }

    private fun checkAndMaybeShutdown() {
        if (isAppInForeground()) return
        val idle = System.currentTimeMillis() - lastActiveAt
        if (idle < IDLE_THRESHOLD_MILLIS) return
        if (anyServiceInService(appRef)) return
        Log.i(TAG, "App idle for ${idle}ms and no interception running, shutting down...")
        performShutdown(appRef)
    }

    private fun performShutdown(context: Context) {
        handler.removeCallbacks(tickRunnable)
        try {
            RootHideAppWatcher.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop RootHide watcher fail: ${t.message}")
        }
        try {
            GameAntiMarkManager.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop GameAntiMark watcher fail: ${t.message}")
        }
        try {
            HotspotInterceptor.stopDnsHijack(context)
        } catch (t: Throwable) {
            Log.w(TAG, "stop HotspotInterceptor fail: ${t.message}")
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getAppTasks().forEach { it.finishAndRemoveTask() }
        } catch (t: Throwable) {
            Log.w(TAG, "finishAndRemoveTask fail: ${t.message}")
        }
        try {
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (t: Throwable) {
            Log.w(TAG, "killProcess fail: ${t.message}", )
        }
    }
}
