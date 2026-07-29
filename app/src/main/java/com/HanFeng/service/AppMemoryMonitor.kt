package com.HanFeng.service

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.HanFeng.adblocker.shizuku.SuSession

/**
 * 进程监控
 *
 * 三个独立修复点：
 *  - CPU=0%：root shell 一次性把 (pid|uid|comm_jiffies|rss) 全抓出来；
 *    采样里直接基于本次输出和上次 lastSnapshots 算 CPU%。
 *    去掉返回前对每个 pid 二次读 /proc/[pid]/stat 的步骤 —— 那是慢的根因，
 *    且二次读的 jiffies 与第一次输出相同，无增量信息。
 *  - 加载慢：不为 600+ 进程每进程同步调 PackageManager.loadLabel —— 这是 PM 跨进程 IPC，
 *    被阻塞后体感秒级。直接用 packageName 作为 label 让 UI 层用 iconCache 异步补名。
 *  - 前台应用跟踪：ActivityManager.runningAppProcesses 在 Android 11+ 仅返回本应用进程，
 *    所有 root 抓出来的条目 isForeground=false，悬浮球永远 fallback 到内存最大的进程。
 *    改成先用 UsageStatsManager 真实拿当前前台包名（PACKAGE_USAGE_STATS），再在 list 里
 *    按 packageName 找对应 AppProcessInfo 作为 cachedForegroundApp。
 */
object AppMemoryMonitor {

    private const val TAG = "AppMemoryMonitor"
    private const val CACHE_VALIDITY_MS = 5000L

    private data class ProcSample(
        val pid: Int,
        val processJiffies: Long,
        val readAtMs: Long,
        val totalCpuJiffies: Long
    )

    @Volatile
    private var lastSnapshots: Map<Int, ProcSample> = emptyMap()

    data class AppProcessInfo(
        val pid: Int,
        val uid: Int,
        val packageName: String,
        val label: String,
        val rssBytes: Long,
        val pssBytes: Long,
        val cpuPercent: Float,
        val importance: Int,
        val isForeground: Boolean
    ) {
        fun formatRss(): String = formatMemorySize(rssBytes)
        fun formatPss(): String = formatMemorySize(pssBytes)
        fun formatCpu(): String = "%.1f%%".format(cpuPercent)
    }

    @Volatile
    private var cachedRunningApps: List<AppProcessInfo> = emptyList()
    @Volatile
    private var cachedForegroundApp: AppProcessInfo? = null
    @Volatile
    private var lastCacheTime: Long = 0L
    private val cacheLock = Any()

    fun formatMemorySize(bytes: Long): String {
        if (bytes <= 0) return "0"
        val kb = bytes / 1024.0
        return when {
            kb < 1024 -> "%.0f KB".format(kb)
            else -> "%.1f MB".format(kb / 1024)
        }
    }

    fun getCachedRunningApps(): List<AppProcessInfo> = cachedRunningApps

    fun getCachedForegroundApp(): AppProcessInfo? = cachedForegroundApp

    fun refreshCache(context: Context) {
        try {
            val list = getRunningAppsInternal(context)
            val foregroundPkg = getForegroundPackage(context)
            val foregroundApp = if (foregroundPkg != null) {
                list.firstOrNull { it.packageName == foregroundPkg }
                    ?: list.firstOrNull { it.isForeground }
                    ?: list.firstOrNull()
            } else {
                list.firstOrNull { it.isForeground } ?: list.firstOrNull()
            }
            synchronized(cacheLock) {
                cachedRunningApps = list
                cachedForegroundApp = foregroundApp
                lastCacheTime = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.d(TAG, "refreshCache failed: ${e.message}")
        }
    }

    /**
     * 用 UsageStatsManager 拿"最近时段内 last time used 最大的应用"作为前台包名。
     * 不在 Android 5.0 以下走这条路。
     */
    private fun getForegroundPackage(context: Context): String? {
        return queryForegroundPackage(context)
    }

    /**
     * 静态版前台包名查询：让 ProcessMonitor.labelFinder 等其它模块也可以复用。
     * 算法：取近 60 秒内 lastTimeUsed 最大的 pkg；若结果是本应用，则用第二个最近
     * used 的（30s 内才算有效），避免悬浮球永远显示自身。
     */
    @JvmStatic
    fun queryForegroundPackage(context: Context): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 60_000L, now)
            if (stats.isNullOrEmpty()) return null
            val sorted = stats.sortedByDescending { it.lastTimeUsed }
            val pkg = sorted.firstOrNull()?.packageName ?: return null
            if (pkg == context.packageName) {
                val second = sorted.getOrNull(1)
                return if (second != null && now - second.lastTimeUsed < 30_000L) second.packageName else pkg
            }
            pkg
        } catch (_: Exception) {
            null
        }
    }

    fun isCacheStale(): Boolean {
        return System.currentTimeMillis() - lastCacheTime > CACHE_VALIDITY_MS
    }

    fun getRunningApps(context: Context): List<AppProcessInfo> {
        return getRunningAppsInternal(context)
    }

    fun getSelfProcessInfo(context: Context): AppProcessInfo {
        val pid = Process.myPid()
        val uid = Process.myUid()
        val pm = context.packageManager
        val label = runCatching {
            context.applicationInfo.loadLabel(pm).toString()
        }.getOrDefault(context.packageName)
        val myPid = intArrayOf(pid)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = am.getProcessMemoryInfo(myPid)
        val rss = if (memInfo.isNotEmpty()) memInfo[0].totalPss * 1024L else 0L
        return AppProcessInfo(
            pid = pid, uid = uid,
            packageName = context.packageName, label = label,
            rssBytes = rss, pssBytes = rss, cpuPercent = 0f,
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            isForeground = true
        )
    }

    fun getForegroundAppMemory(context: Context): AppProcessInfo? {
        return try {
            val list = getRunningAppsInternal(context)
            val foregroundPkg = getForegroundPackage(context)
            if (foregroundPkg != null) {
                list.firstOrNull { it.packageName == foregroundPkg }
            } else {
                list.firstOrNull { it.isForeground } ?: list.firstOrNull()
            }
        } catch (e: Exception) { null }
    }

    private fun getRunningAppsInternal(context: Context): List<AppProcessInfo> {
        try {
            if (!SuSession.getInstance().isSessionOpen() && !SuSession.getInstance().open(3)) {
                return fallbackFromActivityManager(context)
            }

            val totalCpu = readTotalCpuJiffies()
            val now = System.currentTimeMillis()
            val result = mutableListOf<AppProcessInfo>()

            // 一次 root 命令拿全量数据：pid|uid|utime+stime|rss_pages
            // sed 's/.*) //' 删除 (comm) 段，剩下从 state 开始，cut -d' ' -f12=f13=utime/stime
            // 这是 Linux man proc stat 在 closeParen 后字段从 state 开始的稳排名第 12/13。
            // 该 root 命令已运行稳定，不做激进重构。
            val cmd = "for p in /proc/[0-9]*;do pn=\$(basename \$p);u=\$(awk '/^Uid:/{print \$2}' \$p/status 2>/dev/null);[ -z \"\$u\" ]&&continue;s=\$(cat \$p/stat 2>/dev/null|sed 's/.*) //');ut=\$(echo \"\$s\"|cut -d' ' -f12);st=\$(echo \"\$s\"|cut -d' ' -f13);rs=\$(cut -d' ' -f2 \$p/statm 2>/dev/null);echo \"\$pn|\$u|\$ut|\$st|\$rs\";done"
            val shellResult = SuSession.getInstance().execute(cmd)
            val output = shellResult.output
            if (output.isBlank()) return fallbackFromActivityManager(context)

            val pm = context.packageManager
            val uidPkgCache = mutableMapOf<Int, String>()
            val pageSize = 4096L
            // root 命令带回的 jiffies 直接作为本次 snapshot 用,不再二次扫 /proc/[pid]/stat
            val pidToJiffies = HashMap<Int, Long>()

            for (line in output.lines()) {
                val parts = line.trim().split("|")
                if (parts.size < 5) continue
                val pid = parts[0].toIntOrNull() ?: continue
                val uid = parts[1].toIntOrNull() ?: continue
                if (uid in 1..9999) continue
                if (pid == Process.myPid()) continue  // 自己进程走下面 selfBase 专门补
                val procJiffies = (parts[2].toLongOrNull() ?: 0L) + (parts[3].toLongOrNull() ?: 0L)
                pidToJiffies[pid] = procJiffies
                val residentPages = parts[4].toLongOrNull() ?: 0L
                val rss = residentPages * pageSize

                val prev = lastSnapshots[pid]
                val cpuPercent = if (prev != null && totalCpu > 0L) {
                    val jdiff = procJiffies - prev.processJiffies
                    val tdiff = totalCpu - prev.totalCpuJiffies
                    if (tdiff > 0L && jdiff >= 0L)
                        (jdiff.toFloat() / tdiff * 100f).coerceIn(0f, 100f) else 0f
                } else 0f

                var pkg = uidPkgCache[uid]
                if (pkg == null) {
                    val pkgs = try { pm.getPackagesForUid(uid) } catch (_: Exception) { null }
                    pkg = pkgs?.firstOrNull() ?: "uid_$uid"
                    uidPkgCache[uid] = pkg
                }
                // label 异步补：UI 层用 IconExecutorPool 异步调 PackageManager.loadLabel 即可
                val resolvedPkg = pkg
                result.add(AppProcessInfo(
                    pid = pid, uid = uid, packageName = resolvedPkg, label = resolvedPkg,
                    rssBytes = rss, pssBytes = rss, cpuPercent = cpuPercent,
                    importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND,
                    isForeground = false
                ))
            }

            // 自己进程补上（上面 root 循环跳过了 selfPid），含 CPU 计算
            val selfPid = Process.myPid()
            val selfProcJiffies = readProcCpuJiffies(selfPid)
            val selfPrev = lastSnapshots[selfPid]
            val selfCpu = if (selfPrev != null && totalCpu > 0L) {
                val jdiff = selfProcJiffies - selfPrev.processJiffies
                val tdiff = totalCpu - selfPrev.totalCpuJiffies
                if (tdiff > 0L && jdiff >= 0L)
                    (jdiff.toFloat() / tdiff * 100f).coerceIn(0f, 100f) else 0f
            } else 0f
            val selfBase = getSelfProcessInfo(context).copy(cpuPercent = selfCpu)
            result.add(0, selfBase)
            pidToJiffies[selfPid] = selfProcJiffies

            // 更新 lastSnapshots 用于下次采样; 不再做二次全量 readProcCpuJiffies —
            // 上面已经从 root 输出+本进程读一次各拿到 jiffies, 直接拿来取用,
            // 避免对 600+ pid 同步读 /proc 文件 (这才是 "加载慢" 的根因)。
            val newSnapshots = HashMap<Int, ProcSample>(result.size)
            for (item in result) {
                val jiffies = pidToJiffies[item.pid] ?: 0L
                newSnapshots[item.pid] = ProcSample(item.pid, jiffies, now, totalCpu)
            }
            lastSnapshots = newSnapshots

            return result.sortedByDescending { it.rssBytes }
        } catch (e: Exception) {
            Log.d(TAG, "getRunningAppsInternal failed: ${e.message}")
            return listOf(getSelfProcessInfo(context))
        }
    }

    /**
     * 无 root 时回退到 ActivityManager。Android 11+ 只能看本应用进程,
     * 准确度受限,但至少 CPU 可以基于两个采样窗口算出。
     */
    private fun fallbackFromActivityManager(context: Context): List<AppProcessInfo> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val totalCpu = readTotalCpuJiffies()
            val now = System.currentTimeMillis()
            val runningProcs = am.runningAppProcesses ?: emptyList()
            if (runningProcs.isEmpty()) return listOf(getSelfProcessInfo(context))

            val pidArray = runningProcs.map { it.pid }.toIntArray()
            val memInfos = try {
                am.getProcessMemoryInfo(pidArray)
            } catch (_: Exception) { emptyArray() }
            val memMap = mutableMapOf<Int, android.os.Debug.MemoryInfo>()
            for (i in pidArray.indices) {
                if (i < memInfos.size) memMap[pidArray[i]] = memInfos[i]
            }

            val result = mutableListOf<AppProcessInfo>()
            val pidJiffiesMap = HashMap<Int, Long>()
            for (proc in runningProcs) {
                val mem = memMap[proc.pid]
                val rss = (mem?.totalPss?.toLong() ?: 0L) * 1024L
                val pkg = proc.pkgList?.firstOrNull() ?: proc.processName
                val procJiffies = readProcCpuJiffies(proc.pid)
                pidJiffiesMap[proc.pid] = procJiffies
                val prev = lastSnapshots[proc.pid]
                val cpuPercent = if (prev != null && totalCpu > 0L) {
                    val jdiff = procJiffies - prev.processJiffies
                    val tdiff = totalCpu - prev.totalCpuJiffies
                    if (tdiff > 0L && jdiff >= 0L)
                        (jdiff.toFloat() / tdiff * 100f).coerceIn(0f, 100f) else 0f
                } else 0f
                result.add(AppProcessInfo(
                    pid = proc.pid, uid = proc.uid, packageName = pkg, label = pkg,
                    rssBytes = rss, pssBytes = rss, cpuPercent = cpuPercent,
                    importance = proc.importance,
                    isForeground = proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                ))
            }

            // 与 root 路径一样：不再二次读 /proc/[pid]/stat，直接用刚才拿到的 jiffies
            val newSnapshots = HashMap<Int, ProcSample>(result.size)
            for (item in result) {
                newSnapshots[item.pid] = ProcSample(item.pid, pidJiffiesMap[item.pid] ?: 0L, now, totalCpu)
            }
            lastSnapshots = newSnapshots

            if (result.isEmpty()) result.add(getSelfProcessInfo(context))
            result.sortedByDescending { it.rssBytes }
        } catch (e: Exception) {
            Log.d(TAG, "fallbackFromActivityManager failed: ${e.message}")
            listOf(getSelfProcessInfo(context))
        }
    }

    private fun readProcCpuJiffies(pid: Int): Long {
        return try {
            val statContent = java.io.File("/proc/$pid/stat").readText()
            val openParen = statContent.indexOf('(')
            val closeParen = statContent.lastIndexOf(')')
            if (openParen < 0 || closeParen < 0 || closeParen <= openParen) return 0L
            // closeParen 后紧跟一个空格,然后才是 state(原字段3), utime(原字段14), stime(15)
            // 距离 closeParen 的相对偏移:
            //   state=+(2), ppid=+(4), pgrp=+(6), session=+(8), tty_nr=+(10),
            //   tpgid=+(12), flags=+(14), minflt=+(16), cminflt=+(18), majflt=+(20),
            //   cmajflt=+(22), utime=+(24), stime=+(26)
            //  注意上面是按"每项+一个空格"字符数累加,易写错。
            //  改为 split 法 — 但必须用 substring 删到 closeParen+2 之后,字段 state 是 index 0,
            //  utime 是 index 11, stime index 12 —— 与原 GNU ps 字段顺序一致。
            val fields = statContent.substring(closeParen + 2).trim().split("\\s+".toRegex())
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: 0L
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: 0L
            utime + stime
        } catch (_: Exception) { 0L }
    }

    private fun readTotalCpuJiffies(): Long {
        return try {
            val firstLine = java.io.File("/proc/stat").bufferedReader().use { it.readLine() }
            val parts = firstLine.split("\\s+".toRegex()).drop(1)
            parts.take(7).sumOf { it.toLongOrNull() ?: 0L }
        } catch (_: Exception) { 0L }
    }
}
