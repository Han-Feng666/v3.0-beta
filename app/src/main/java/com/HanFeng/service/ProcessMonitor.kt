package com.HanFeng.service

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Shizuku-based process monitor that reads /proc directly via shell commands.
 * Exposes real-time CPU% and VmRSS for all processes as a Flow.
 *
 * Data sources:
 * - /proc/[pid]/ directories for process list
 * - /proc/[pid]/status VmRSS for memory (kB -> bytes)
 * - /proc/[pid]/stat + /proc/stat double-sample (1s interval) for CPU%
 * - /proc/[pid]/comm for process name (fallback to /proc/[pid]/cmdline)
 */
class ProcessMonitor private constructor(private val context: Context) {

    enum class SamplingMode {
        /**
         * 全设备扫描：枚举 /proc/[0-9]* 所有进程。RunningAppsActivity 用。
         * 采样间隔 [SAMPLING_INTERVAL_FULL_MS]。
         */
        FULL,
        /**
         * 前台轻采样：只采样前台 App PID。FloatingBallService 用。
         * 采样间隔 [SAMPLING_INTERVAL_FOREGROUND_MS]，开销极低。
         */
        FOREGROUND_ONLY
    }

    companion object {
        private const val TAG = "ProcessMonitor"
        /** 完整扫描间隔：用户主动打开「运行中进程」页才生效，间隔放宽到 3 秒，足以反映 CPU% */
        private const val SAMPLING_INTERVAL_FULL_MS = 3000L
        /** 前台轻采样间隔：悬浮球场景，5 秒一次足够反映前台 App 占用变化 */
        private const val SAMPLING_INTERVAL_FOREGROUND_MS = 5000L
        private const val PAGE_SIZE = 4096L

        @Volatile
        private var INSTANCE: ProcessMonitor? = null

        fun getInstance(context: Context): ProcessMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProcessMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun formatMemorySize(bytes: Long): String {
            if (bytes <= 0) return "0"
            val kb = bytes / 1024.0
            return when {
                kb < 1024 -> "%.0f KB".format(kb)
                else -> "%.1f MB".format(kb / 1024)
            }
        }
    }

    data class ProcessInfo(
        val pid: Int,
        val uid: Int,
        val packageName: String,
        val label: String,
        val rssBytes: Long,
        val cpuPercent: Float,
        val isForeground: Boolean,
        val importance: Int = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND
    ) {
        fun formatRss(): String = formatMemorySize(rssBytes)
        fun formatCpu(): String = "%.1f%%".format(cpuPercent)
    }

    private data class ProcSnapshot(
        val pid: Int,
        val jiffies: Long,
        val totalCpuJiffies: Long,
        val timestampMs: Long
    )

    private data class ProcRawData(
        val pid: Int,
        val uid: Int,
        val comm: String,
        val rssPages: Long,
        val utime: Long,
        val stime: Long
    ) {
        val jiffies: Long get() = utime + stime
    }

    private val _processFlow = MutableStateFlow<List<ProcessInfo>>(emptyList())
    val processFlow = _processFlow

    private val lastSnapshots = mutableMapOf<Int, ProcSnapshot>()
    private var samplingJob: kotlinx.coroutines.Job? = null
    private val uidPkgCache = mutableMapOf<Int, String>()

    /**
     * 启动采样。mode 决定采样策略：
     * - FULL：扫描 /proc/[0-9]* 全部进程，间隔 3s。RunningAppsActivity 使用。
     * - FOREGROUND_ONLY：只采样当前前台 App 1 个 PID，间隔 5s，开销极低。FloatingBallService 使用。
     */
    fun startSampling(scope: kotlinx.coroutines.CoroutineScope, mode: SamplingMode = SamplingMode.FULL) {
        // 同模式重复启动直接返回；模式不同则替换为新模式
        samplingJob?.let { if (it.isActive && currentMode == mode) return else it.cancel() }
        currentMode = mode

        samplingJob = scope.launch(Dispatchers.IO) {
            // Initial sample
            sampleOnce(isFirstSample = true, mode = mode)

            val interval = if (mode == SamplingMode.FULL) SAMPLING_INTERVAL_FULL_MS else SAMPLING_INTERVAL_FOREGROUND_MS
            while (coroutineContext[ kotlinx.coroutines.Job ]?.isActive == true) {
                try {
                    delay(interval)
                    if (coroutineContext[ kotlinx.coroutines.Job ]?.isActive != true) break
                    sampleOnce(isFirstSample = false, mode = mode)
                } catch (e: InterruptedException) {
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Sampling loop error: ${e.message}")
                }
            }
        }
    }

    fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
        // 保留 lastSnapshots 不清，下次启动可继续做差分；但清掉前台专用 trace 避免脏数据
    }

    @Volatile private var currentMode: SamplingMode = SamplingMode.FULL

    private suspend fun sampleOnce(isFirstSample: Boolean, mode: SamplingMode) {
        if (mode == SamplingMode.FOREGROUND_ONLY) {
            sampleForegroundOnce(isFirstSample)
        } else {
            sampleFullOnce(isFirstSample)
        }
    }

    /**
     * 只采样"真正的前台 App"一个进程。算法：
     *   1. 用 UsageStatsManager 取最近 60s 内 lastTimeUsed 最大的包名（项目已有，免新增权限）
     *   2. Shizuku/root shell 一条命令 `pm list packages -U <pkg>` 反查该包对应的 uid
     *   3. 再 `pgrep -u <uid>` 拿该 uid 下的所有 pid（可能多进程，全部采样求和 CPU、求最大 RSS）
     *   4. Shizuku/root 命令一次性读取这些 pid 的 stat/statm
     * 失败链：拿不到前台 pkg → 退化到自身 pid；uid/pid 反查失败 → 退化到自身 pid。
     * 自身 App 时直接走自身路径（悬浮球展示本应用资源占用，对监控广告拦截有意义）。
     */
    private suspend fun sampleForegroundOnce(isFirstSample: Boolean) {
        val pids = resolveForegroundPids() ?: listOf(Process.myPid())
        val rawList = readProcRawForPids(pids, skipSelfFilter = false).ifEmpty {
            // 兜底：root/shizuku 读不到（罕见），就只能显示自身
            readProcRawSelfOnly()
        }
        if (rawList.isEmpty()) return

        val totalCpuJiffies = readTotalCpuJiffies()
        val now = System.currentTimeMillis()
        val result = mutableListOf<ProcessInfo>()
        val newSnapshots = mutableMapOf<Int, ProcSnapshot>()
        val pm = context.packageManager

        for (raw in rawList) {
            val prev = lastSnapshots[raw.pid]
            val cpuPercent = if (!isFirstSample && prev != null && totalCpuJiffies > 0) {
                val jdiff = raw.jiffies - prev.jiffies
                val tdiff = totalCpuJiffies - prev.totalCpuJiffies
                if (tdiff > 0 && jdiff >= 0) (jdiff.toFloat() / tdiff * 100f).coerceIn(0f, 100f) else 0f
            } else 0f

            val rssBytes = raw.rssPages * PAGE_SIZE
            var pkgName = uidPkgCache[raw.uid]
            if (pkgName == null) {
                pkgName = try {
                    pm.getPackagesForUid(raw.uid)?.firstOrNull() ?: "uid_${raw.uid}"
                } catch (_: Exception) {
                    "uid_${raw.uid}"
                }
                uidPkgCache[raw.uid] = pkgName!!
            }

            // 前台 App 整个 uid 的进程都标 isForeground，UI 端会取首条作为"前台应用"展示
            val isForeground = true
            result.add(ProcessInfo(
                pid = raw.pid,
                uid = raw.uid,
                packageName = pkgName!!,
                label = pkgName!!,
                rssBytes = rssBytes,
                cpuPercent = cpuPercent,
                isForeground = isForeground,
                importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            ))
            newSnapshots[raw.pid] = ProcSnapshot(raw.pid, raw.jiffies, totalCpuJiffies, now)
        }

        // 前台专用采样只保留前台 pid 的 snapshot；其它 pid 的 baseline 丢掉不要污染下次差分
        lastSnapshots.keys.retainAll(newSnapshots::containsKey)
        lastSnapshots.putAll(newSnapshots)

        if (result.isNotEmpty()) {
            // 同 uid 多进程：合并显示为一条（RSS 取最大值，CPU 合计）
            _processFlow.value = mergeSameUid(result)
        }
    }

    /**
     * 把同一 uid 的多个 ProcessInfo 合成一条（同 App 多进程场景，如 :pushservice）。
     * RSS 取最大值更接近"该 App 实际驻留内存"，CPU 合计反映该 App 总 CPU 占用。
     */
    private fun mergeSameUid(list: List<ProcessInfo>): List<ProcessInfo> {
        val byUid = list.groupBy { it.uid }
        if (byUid.size == list.size) return list
        return byUid.mapValues { (_, ps) ->
            val head = ps.first()
            head.copy(
                rssBytes = ps.maxOf { it.rssBytes },
                cpuPercent = ps.sumOf { it.cpuPercent.toDouble() }.toFloat().coerceAtMost(100f)
            )
        }.values.toList().sortedByDescending { it.rssBytes }
    }

    /**
     * 解析前台 App 的所有 PID。
     * 走 UsageStatsManager → Shizuku shell 反查 pkg→uid→pid。失败则返回 null。
     */
    private suspend fun resolveForegroundPids(): List<Int>? {
        val pkg = AppMemoryMonitor.queryForegroundPackage(context) ?: return null
        if (pkg == context.packageName) return null  // 让外层走 myPid fallback
        val uid = queryUidForPackage(pkg) ?: return null
        val pids = queryPidsForUid(uid)
        return pids.takeIf { it.isNotEmpty() }
    }

    /** 用 Shizuku/root `pm list packages -U <pkg>` 反查 uid（输出形如 `package:<pkg> uid:<n>`）。 */
    private suspend fun queryUidForPackage(pkg: String): Int? {
        val out = executeShell("pm list packages -U $pkg 2>/dev/null")
        return out.firstNotNullOfOrNull { line ->
            val m = Regex("uid:(\\d+)").find(line)
            m?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /** 用 Shizuku/root `pgrep -u <uid>` 拿该 uid 下所有 pid。Android 自带 toybox 都有 pgrep。 */
    private suspend fun queryPidsForUid(uid: Int): List<Int> {
        val out = executeShell("pgrep -u $uid 2>/dev/null")
        return out.mapNotNull { it.trim().toIntOrNull() }
    }

    /** 不带 self 过滤的 readProcRawForPids（前台采样用），自身进程也要采到。 */
    private suspend fun readProcRawSelfOnly(): List<ProcRawData> {
        val myPid = Process.myPid()
        return readProcRawForPids(listOf(myPid), skipSelfFilter = false)
    }

    private suspend fun readProcRawForPids(pids: List<Int>, skipSelfFilter: Boolean = true): List<ProcRawData> {
        if (pids.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val cmd = buildString {
                append("for p in ")
                pids.joinTo(this, " ")
                append("; do\n")
                append("    if [ -d \"/proc/\$p\" ]; then\n")
                append("        u=\$(awk '/^Uid:/{print \$2}' /proc/\$p/status 2>/dev/null)\n")
                append("        [ -z \"\$u\" ] && continue\n")
                append("        c=\$(cat /proc/\$p/comm 2>/dev/null | head -c 31)\n")
                append("        s=\$(cat /proc/\$p/stat 2>/dev/null | sed 's/.*) //')\n")
                append("        ut=\$(echo \"\$s\" | cut -d' ' -f12)\n")
                append("        st=\$(echo \"\$s\" | cut -d' ' -f13)\n")
                append("        rs=\$(cut -d' ' -f2 /proc/\$p/statm 2>/dev/null)\n")
                append("        echo \"\$p|\$u|\$c|\$ut|\$st|\$rs\"\n")
                append("    fi\n")
                append("done")
            }
            executeShell(cmd).map { parseProcLine(it, skipSelfFilter = skipSelfFilter) }.filterNotNull()
        }
    }

    /**
     * 全设备采样：原逻辑保留给 RunningAppsActivity 使用。
     * 关键改动：
     *  - 自身进程不再跳过（root shell 路径会丢掉自身）；
     *  - 只保留 App 进程（system app uid + 第三方 App uid + 自身），过滤内核线程/daemon；
     *  - 同一个 uid 的多个 ProcessInfo 合并为一条（App 而非按 pid 列出）。
     */
    private suspend fun sampleFullOnce(isFirstSample: Boolean) {
        val rawList = withContext(Dispatchers.IO) { readProcRaw() }
        if (rawList.isEmpty()) return

        val totalCpuJiffies = readTotalCpuJiffies()
        val now = System.currentTimeMillis()

        val result = mutableListOf<ProcessInfo>()
        val newSnapshots = mutableMapOf<Int, ProcSnapshot>()

        val pm = context.packageManager
        val myPid = Process.myPid()

        for (raw in rawList) {
            val prev = lastSnapshots[raw.pid]
            val cpuPercent = if (!isFirstSample && prev != null && totalCpuJiffies > 0) {
                val jdiff = raw.jiffies - prev.jiffies
                val tdiff = totalCpuJiffies - prev.totalCpuJiffies
                if (tdiff > 0 && jdiff >= 0) (jdiff.toFloat() / tdiff * 100f).coerceIn(0f, 100f) else 0f
            } else 0f

            val rssBytes = raw.rssPages * PAGE_SIZE
            var pkgName = uidPkgCache[raw.uid]
            if (pkgName == null) {
                pkgName = try {
                    pm.getPackagesForUid(raw.uid)?.firstOrNull() ?: "uid_${raw.uid}"
                } catch (_: Exception) {
                    "uid_${raw.uid}"
                }
                uidPkgCache[raw.uid] = pkgName!!
            }

            // RunningAppsActivity 的前台标记交给 UsageStats-based filtering;
            // 此路径仅按上下文判断本进程是否前台（保证悬浮球前台显示自身能匹配）
            val isForeground = raw.pid == myPid

            result.add(ProcessInfo(
                pid = raw.pid,
                uid = raw.uid,
                packageName = pkgName!!,
                label = pkgName!!,
                rssBytes = rssBytes,
                cpuPercent = cpuPercent,
                isForeground = isForeground
            ))

            newSnapshots[raw.pid] = ProcSnapshot(raw.pid, raw.jiffies, totalCpuJiffies, now)
        }

        // 合并同 uid 多进程为一条 App，便于列表展示
        val merged = mergeSameUid(result)

        lastSnapshots.keys.retainAll(newSnapshots::containsKey)
        lastSnapshots.putAll(newSnapshots)

        if (merged.isNotEmpty()) {
            _processFlow.value = merged
        }
    }

    private suspend fun readProcRaw(): List<ProcRawData> {
        return withContext(Dispatchers.IO) {
            val cmd = buildProcReadCommand()
            executeShell(cmd).map { parseProcLine(it) }.filterNotNull()
        }
    }

    /**
     * 构造一条 sh 命令：遍历 /proc/[0-9]* ，仅输出 App 进程
     * （uid=1000 system app 系列或 uid>=10000 的第三方 App；root(0)、shell(2000)、daemon 一律跳过）。
     * 自身进程也输出（前台采样要它）。
     */
    private fun buildProcReadCommand(): String {
        val sb = StringBuilder()
        sb.append("for p in /proc/[0-9]*; do\n")
        sb.append("    pn=\$(basename \"\$p\")\n")
        sb.append("    u=\$(awk '/^Uid:/{print \$2}' \"\$p\"/status 2>/dev/null)\n")
        sb.append("    [ -z \"\$u\" ] && continue\n")
        // 只保留 App 进程：system_app(1000) 或第三方 App(uid>=10000)；root/shell/daemon 过滤掉
        sb.append("    if [ \"\$u\" != \"1000\" ] && [ \"\$u\" -lt 10000 ] 2>/dev/null; then continue; fi\n")
        sb.append("    c=\$(cat \"\$p\"/comm 2>/dev/null | head -c 31)\n")
        sb.append("    s=\$(cat \"\$p\"/stat 2>/dev/null | sed 's/.*) //')\n")
        sb.append("    ut=\$(echo \"\$s\" | cut -d' ' -f12)\n")
        sb.append("    st=\$(echo \"\$s\" | cut -d' ' -f13)\n")
        sb.append("    rs=\$(cut -d' ' -f2 \"\$p\"/statm 2>/dev/null)\n")
        sb.append("    echo \"\$pn|\$u|\$c|\$ut|\$st|\$rs\"\n")
        sb.append("done")
        return sb.toString()
    }

    private fun parseProcLine(line: String, skipSelfFilter: Boolean = true): ProcRawData? {
        val parts = line.trim().split("|")
        if (parts.size < 6) return null

        val pid = parts[0].toIntOrNull() ?: return null
        if (skipSelfFilter && pid == Process.myPid()) return null

        val uid = parts[1].toIntOrNull() ?: return null
        if (uid <= 0) return null
        // 二次防御：FULL shell 已过滤过；精准 PID 路径无需再过滤
        if (skipSelfFilter && uid in 1..9999) {
            // 已被 shell 过滤过的不应该到这里；再保险一遍跳掉 daemon
            if (uid != 1000) return null
        }

        val comm = parts[2].ifBlank { "unknown" }
        val utime = parts[3].toLongOrNull() ?: 0L
        val stime = parts[4].toLongOrNull() ?: 0L
        val rssPages = parts[5].toLongOrNull() ?: 0L

        return ProcRawData(
            pid = pid,
            uid = uid,
            comm = comm,
            rssPages = rssPages,
            utime = utime,
            stime = stime
        )
    }

    private suspend fun readTotalCpuJiffies(): Long {
        return withContext(Dispatchers.IO) {
            try {
                val output = executeShell("cat /proc/stat | head -1")
                val firstLine = output.firstOrNull() ?: return@withContext 0L
                val parts = firstLine.trim().split("\\s+".toRegex()).drop(1)
                parts.take(7).sumOf { it.toLongOrNull() ?: 0L }
            } catch (e: Exception) {
                Log.w(TAG, "readTotalCpuJiffies failed: ${e.message}")
                0L
            }
        }
    }

    /**
     * Execute a shell command via Shizuku (preferred) or root fallback.
     * Uses reflection to call private Shizuku.newProcess() method.
     * Falls back to SuSession if Shizuku binder is unavailable.
     */
    private suspend fun executeShell(command: String): List<String> {
        return withContext(Dispatchers.IO) {
            // 优先走 Shizuku
            if (Shizuku.pingBinder()) {
                try {
                    val newProcessMethod = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                    newProcessMethod.isAccessible = true
                    val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as ShizukuRemoteProcess
                    val writer = OutputStreamWriter(process.outputStream)
                    writer.flush()
                    writer.close()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = mutableListOf<String>()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.add(line!!)
                    }
                    reader.close()
                    process.waitForTimeout(5, TimeUnit.SECONDS)
                    return@withContext output
                } catch (e: Exception) {
                    Log.w(TAG, "Shizuku newProcess failed, trying root: ${e.message}")
                }
            }

            // 回退到 root
            try {
                val session = com.HanFeng.adblocker.shizuku.SuSession.getInstance()
                if (session.isSessionOpen() || session.open()) {
                    val result = session.execute(command, timeoutSeconds = 5)
                    if (result.exitCode == 0) {
                        return@withContext result.output.lines()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Root shell also failed: ${e.message}")
            }

            emptyList()
        }
    }
}