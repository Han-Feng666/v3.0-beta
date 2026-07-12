package com.HanFeng

import android.app.Application
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.HanFeng.service.IdleShutdownController
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HanFengApp : Application() {

    companion object {
        @Volatile
        var appStartElapsedMs: Long = 0L
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appStartElapsedMs = SystemClock.elapsedRealtime()
        writeStartupLog("Application.onCreate")
        installGlobalCrashHandler()
        writeStartupLog("CrashHandler installed")
        IdleShutdownController.init(this)
        writeStartupLog("IdleShutdownController installed")
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
        runCatching {
            val elapsed = SystemClock.elapsedRealtime() - appStartElapsedMs
            val trace = buildString {
                appendLine("=== HanFeng Startup Trace ===")
                appendLine("Milestone: $milestone")
                appendLine("Elapsed: ${elapsed}ms")
                appendLine("PID: ${Process.myPid()}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
                appendLine()
            }
            val traceFile = File(filesDir, "startup_trace.txt")
            traceFile.appendText(trace)
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

