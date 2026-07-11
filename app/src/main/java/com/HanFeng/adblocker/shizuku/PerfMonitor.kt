package com.HanFeng.adblocker.shizuku

import android.content.Context
import com.HanFeng.data.ShizukuPerformanceRepository

/**
 * 性能采集器：Root 路径读 /proc/_pid_/stat + /proc/_pid_/statm，
 * Shizuku 备路径仅探测前台 package 与运行中的包名列表。
 *
 * 由于 Android 10+ /proc/_pid_/stat 对非自身进程仅 root 可读，
 * 这里通过 SuSession 一次性 cat /proc/__/stat 收集全部进程快照。
 */
object PerfMonitor {

    data class ProcessSnapshot(
        val pid: Int,
        val name: String,
        val cpuPercent: Float,
        val memoryKb: Long,
        val rssKb: Long,
        val state: String,
        val foreground: Boolean
    )

    @Volatile private var lastTickSnapshot: Map<Int, Long> = emptyMap()
    @Volatile private var lastWallClockNanos: Long = 0L

    /**
     * 一次性快照所有运行中进程的性能数据；CPU 占比基于与上次调用之间的节拍差分计算（初次调用全部为 0）。
     * 返回结果按 CPU 占比降序，CPU 相同则按内存 RSS 降序。
     */
    fun snapshot(context: Context): List<ProcessSnapshot> {
        val procStatLines = runRootShell(
            "for p in /proc/[0-9]*/stat; do " +
                "cat \"\$p\" 2>/dev/null; " +
                "done 2>/dev/null"
        ).output
        val procStatmLines = runRootShell(
            "for p in /proc/[0-9]*/statm; do " +
                "head -c 128 \"\$p\" 2>/dev/null | sed 's~^~'\$(echo \"\$p\" | sed 's~/proc/~~; s~/statm~~')' ~'; " +
                "echo; " +
                "done 2>/dev/null"
        ).output
        val pageSize = 4L
        val statmVssKb = HashMap<Int, Long>()
        val statmRssKb = HashMap<Int, Long>()
        procStatmLines.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val parts = trimmed.split(' ', limit = 6)
            val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val size = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val resident = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            statmVssKb[pid] = size * pageSize
            statmRssKb[pid] = resident * pageSize
        }

        val currentTicks = HashMap<Int, Long>()
        val currentNames = HashMap<Int, String>()
        val currentStates = HashMap<Int, String>()
        procStatLines.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val firstParen = line.indexOf('(')
            val lastParen = line.lastIndexOf(')')
            if (firstParen < 1 || lastParen <= firstParen) return@forEach
            val pidStr = line.substring(0, firstParen).trim()
            val pid = pidStr.toIntOrNull() ?: return@forEach
            val comm = line.substring(firstParen + 1, lastParen)
            val rest = line.substring(lastParen + 1).trim().split(' ')
            if (rest.size < 13) return@forEach
            val state = rest.getOrNull(0) ?: "?"
            val utimeJiffies = rest.getOrNull(11)?.toLongOrNull() ?: 0L
            val stimeJiffies = rest.getOrNull(12)?.toLongOrNull() ?: 0L
            val totalJiffies = utimeJiffies + stimeJiffies
            currentTicks[pid] = totalJiffies
            currentNames[pid] = comm
            currentStates[pid] = state
        }

        val nowNanos = System.nanoTime()
        val wallElapsedJiffies = ((nowNanos - lastWallClockNanos) / 10_000_000L).coerceAtLeast(1L)
        val previous = lastTickSnapshot
        val foregroundPackage = detectForegroundPackageSafely(context)

        val results = currentTicks.entries.map { (pid, ticks) ->
            val previousTicks = previous[pid] ?: 0L
            val deltaTicks = (ticks - previousTicks).coerceAtLeast(0L)
            val cpuPercent = if (wallElapsedJiffies == 0L) {
                0f
            } else {
                ((deltaTicks.toDouble() / wallElapsedJiffies) * 100f).toFloat()
            }
            val name = currentNames[pid] ?: pid.toString()
            val mem = statmVssKb[pid] ?: 0L
            val rss = statmRssKb[pid] ?: 0L
            val state = currentStates[pid] ?: "?"
            val foreground = name == foregroundPackage
            ProcessSnapshot(
                pid = pid,
                name = name,
                cpuPercent = cpuPercent,
                memoryKb = mem,
                rssKb = rss,
                state = state,
                foreground = foreground
            )
        }
        lastTickSnapshot = currentTicks
        lastWallClockNanos = nowNanos
        return results.sortedWith(
            compareByDescending<ProcessSnapshot> { it.cpuPercent }
                .thenByDescending { it.rssKb }
        )
    }

    /** 仅返回前台 App 的快照，用于悬浮窗显示。 */
    fun snapshotForeground(context: Context): ProcessSnapshot? {
        val pkg = detectForegroundPackageSafely(context) ?: return null
        return snapshot(context).firstOrNull { it.name == pkg }
    }

    fun reset() {
        lastTickSnapshot = emptyMap()
        lastWallClockNanos = 0L
    }

    private fun detectForegroundPackageSafely(context: Context): String? {
        try {
            val viaShizuku = ShizukuPerformanceRepository.getForegroundPackage(context)
            if (!viaShizuku.isNullOrBlank()) return viaShizuku
        } catch (_: Throwable) {}
        try {
            val outcome = runRootShell("dumpsys activity activities 2>/dev/null")
            val line = outcome.output.lineSequence().firstOrNull {
                it.contains("mResumedActivity") || it.contains("ResumedActivity")
            } ?: return null
            val match = Regex("([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.]+").find(line) ?: return null
            val pkg = match.groupValues[1]
            if (pkg.isNotBlank()) return pkg
        } catch (_: Throwable) {}
        return null
    }

    private fun runRootShell(command: String, timeoutSeconds: Long = 15): ShellOutcome {
        val su = SuSession.getInstance()
        if (!su.isSessionOpen()) {
            su.open(timeoutSeconds = timeoutSeconds)
        }
        val result = su.execute(command, timeoutSeconds)
        return ShellOutcome(result.exitCode, result.output)
    }

    private data class ShellOutcome(val exitCode: Int, val output: String)
}
