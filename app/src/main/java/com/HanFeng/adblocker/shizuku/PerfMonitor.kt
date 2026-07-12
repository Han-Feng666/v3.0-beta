package com.HanFeng.adblocker.shizuku

import android.content.Context
import com.HanFeng.data.ShizukuPerformanceRepository

/**
 * 性能采集器：一次 root cat 全部 /proc 下数字进程的 stat 即可拿到 PID、comm、state、utime/stime、vsize、rss，
 * 不再单独 cat statm，避免第二次 root 调用拖慢采集；前台 App 通过 Shizuku UserService 探测，失败降级到 dumpsys。
 *
 * 由于 Android 10+ /proc/[pid]/stat 对非自身进程仅 root 可读，这里通过 SuSession 一次 cat 全部进程。
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
    private const val PAGE_SIZE_KB = 4L

    /**
     * 一次性快照所有运行中进程的性能数据；CPU 占比基于与上次调用之间的节拍差分计算（初次调用全部为 0）。
     * 返回结果按 CPU 占比降序，CPU 相同则按内存 RSS 降序。
     */
    fun snapshot(context: Context): List<ProcessSnapshot> {
        val procStatRaw = runRootShell(
            "for p in /proc/[0-9]*/stat; do cat \"\$p\" 2>/dev/null; done 2>/dev/null",
            8
        ).output

        val currentTicks = HashMap<Int, Long>()
        val currentNames = HashMap<Int, String>()
        val currentStates = HashMap<Int, String>()
        val currentVssKb = HashMap<Int, Long>()
        val currentRssKb = HashMap<Int, Long>()

        procStatRaw.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val firstParen = line.indexOf('(')
            val lastParen = line.lastIndexOf(')')
            if (firstParen < 1 || lastParen <= firstParen) return@forEach
            val pid = line.substring(0, firstParen).trim().toIntOrNull() ?: return@forEach
            val comm = line.substring(firstParen + 1, lastParen)
            val rest = line.substring(lastParen + 1).trim().split(' ')
            if (rest.size < 24) return@forEach
            val state = rest.getOrNull(0) ?: "?"
            val utimeJiffies = rest.getOrNull(11)?.toLongOrNull() ?: 0L
            val stimeJiffies = rest.getOrNull(12)?.toLongOrNull() ?: 0L
            val vsizeBytes = rest.getOrNull(21)?.toLongOrNull() ?: 0L
            val rssPages = rest.getOrNull(22)?.toLongOrNull() ?: 0L

            currentTicks[pid] = utimeJiffies + stimeJiffies
            currentNames[pid] = comm
            currentStates[pid] = state
            currentVssKb[pid] = vsizeBytes / 1024L
            currentRssKb[pid] = rssPages * PAGE_SIZE_KB
        }

        if (currentTicks.isEmpty()) return emptyList()

        val nowNanos = System.nanoTime()
        val wallElapsedJiffies = ((nowNanos - lastWallClockNanos) / 10_000_000L).coerceAtLeast(1L)
        val previous = lastTickSnapshot
        val foregroundPackage = runCatching { detectForegroundPackageSafely(context) }.getOrNull()

        val results = currentTicks.entries.map { (pid, ticks) ->
            val previousTicks = previous[pid] ?: 0L
            val deltaTicks = (ticks - previousTicks).coerceAtLeast(0L)
            val cpuPercent = if (wallElapsedJiffies == 0L) {
                0f
            } else {
                ((deltaTicks.toDouble() / wallElapsedJiffies) * 100f).toFloat()
            }
            val name = currentNames[pid] ?: pid.toString()
            val mem = currentVssKb[pid] ?: 0L
            val rss = currentRssKb[pid] ?: 0L
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
        val pkg = runCatching { detectForegroundPackageSafely(context) }.getOrNull() ?: return null
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
            val outcome = runRootShell("dumpsys activity activities 2>/dev/null", 8)
            val line = outcome.output.lineSequence().firstOrNull {
                it.contains("mResumedActivity") || it.contains("ResumedActivity")
            } ?: return null
            val match = Regex("([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.]+").find(line) ?: return null
            val pkg = match.groupValues[1]
            if (pkg.isNotBlank()) return pkg
        } catch (_: Throwable) {}
        return null
    }

    private fun runRootShell(command: String, timeoutSeconds: Long = 8): ShellOutcome {
        val su = SuSession.getInstance()
        if (!su.isSessionOpen()) {
            su.open(timeoutSeconds = timeoutSeconds.coerceAtLeast(15))
        }
        val result = su.execute(command, timeoutSeconds)
        return ShellOutcome(result.exitCode, result.output)
    }

    private data class ShellOutcome(val exitCode: Int, val output: String)
}
