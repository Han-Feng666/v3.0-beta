package com.HanFeng.shizuku

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

class ShizukuPerformanceUserService() : IPerformanceService.Stub() {

    private var serviceContext: Context? = null

    @Keep
    constructor(context: Context) : this() {
        serviceContext = context.applicationContext
    }

    override fun ping(): Boolean = true

    override fun getForegroundPackage(): String {
        try {
            val ctx = serviceContext ?: return ""
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return ""
            val tasks = am.getRunningTasks(1)
            val top = tasks?.firstOrNull()?.topActivity
            if (top != null && top.packageName.isNotBlank()) {
                return top.packageName
            }
        } catch (_: Throwable) {}
        try {
            val output = runShell(listOf("dumpsys", "activity", "activities"))
            val line = output.lineSequence().firstOrNull {
                it.contains("mResumedActivity") || it.contains("ResumedActivity")
            } ?: return ""
            val regex = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)")
            val match = regex.find(line) ?: return ""
            val pkg = match.groupValues[1]
            if (pkg.isNotBlank()) return pkg.trim()
        } catch (_: Throwable) {}
        return ""
    }

    override fun getRunningPackages(): Array<String> {
        try {
            val ctx = serviceContext ?: return emptyArray()
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return emptyArray()
            val list = am.runningAppProcesses ?: emptyList()
            val result = mutableListOf<String>()
            list.forEach { p ->
                p.processName?.let { name ->
                    if (name.isNotBlank() && !result.contains(name)) {
                        result.add(name)
                    }
                }
            }
            if (result.isNotEmpty()) return result.toTypedArray()
        } catch (_: Throwable) {}
        try {
            val output = runShell(listOf("ps", "-A", "-o", "NAME"))
            val result = output.lineSequence()
                .drop(1)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            if (result.isNotEmpty()) return result.toTypedArray()
        } catch (_: Throwable) {}
        return emptyArray()
    }

    override fun readProcessStat(pid: Int): String {
        if (pid <= 0) return ""
        return runCatching {
            val output = runShell(listOf("cat", "/proc/$pid/stat"))
            output.ifBlank { "" }.trim()
        }.getOrDefault("")
    }

    override fun dumpProcessInfo(): String {
        return runCatching {
            runShell(listOf("dumpsys", "meminfo", "--summary"))
        }.getOrDefault("")
    }

    override fun destroy() {
        serviceContext = null
    }

    private fun runShell(command: List<String>): String {
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching ""
            }
            process.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.appendLine(line)
                    line = reader.readLine()
                }
                sb.toString()
            }
        }.getOrDefault("")
    }
}
