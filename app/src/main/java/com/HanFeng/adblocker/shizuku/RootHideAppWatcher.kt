package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import com.HanFeng.data.LogRepository
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App 启动监听器：在 Root shell 中持续轮询进程中每个 PID 的命令行，
 * 当目标作用域 App 的进程出现时，立即对该进程的所有 PID 执行 mount namespace bind mount，
 * 让 App 启动瞬间就被隐藏 Root 痕迹（替代手动按按钮的延迟）。
 *
 * 设计要点：
 * - 后台守护进程式（root shell + nohup sh -c 持续运行），不依赖 App 主进程存活
 * - 持久状态写入 /data/adb/hanfeng/watcher_state，重启后自动恢复
 * - 进程出现后立即执行 mount bind，且记录 PID，避免重复操作
 */
object RootHideAppWatcher {

    private const val TAG = "RootHideAppWatcher"
    private const val WATCHER_PID_FILE = "/data/adb/hanfeng/watcher.pid"
    private const val WATCHER_SCRIPT_FILE = "/data/adb/hanfeng/watcher.sh"
    private const val HIDDEN_PIDS_FILE = "/data/adb/hanfeng/handled_pids.txt"
    private const val SYS_HIDE_DIR = "/data/local/tmp/.hf_sys_hide"

    private val running = AtomicBoolean(false)

    data class WatcherStatus(
        val running: Boolean,
        val pid: Int,
        val scopePackages: List<String>,
        val handledPids: Int
    )

    private val suSession get() = SuSession.getInstance()

    fun start(context: Context, scopePackages: Set<String>): Boolean {
        if (scopePackages.isEmpty()) {
            Log.w(TAG, "Cannot start watcher: scope is empty")
            return false
        }
        if (!suSession.isSessionOpen() && !suSession.open(30)) return false
        if (running.get()) {
            Log.d(TAG, "Watcher already running in this JVM")
            // 重启也可能在 root 端已运行；停止再启动来刷新 scope
            stop()
        }
        suSession.execute("mkdir -p /data/adb/hanfeng", 3)
        suSession.execute("mkdir -p '$SYS_HIDE_DIR' && touch '$SYS_HIDE_DIR/.empty'", 3)

        val pkgMatchers = scopePackages.joinToString(" ") { it.replace("'", "") }
        val pkgList = scopePackages.joinToString(",") { it }

        // 构造 watcher.sh：循环扫描进程中每个 PID 的命令行，匹配 scopePackages 的 PID 出现就 remount
        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# HanFeng RootHide Watcher — 自动启动隐藏脚本")
            appendLine("HANDLED='$HIDDEN_PIDS_FILE'")
            appendLine("HIDE_DIR='$SYS_HIDE_DIR'")
            appendLine("LOG='/data/adb/hanfeng/watcher.log'")
            appendLine("PACKAGES='$pkgList'")
            appendLine("MAX_HANDLED=5000")
            appendLine("touch \"\$HANDLED\" 2>/dev/null")
            appendLine("echo \"\$(date '+%Y-%m-%d %H:%M:%S') watcher started with \$PACKAGES\" >> \"\$LOG\" 2>/dev/null")
            appendLine("")
            appendLine("is_handled() { grep -qxF \"\$1\" \"\$HANDLED\" 2>/dev/null; }")
            appendLine("mark_handled() {")
            appendLine("  echo \"\$1\" >> \"\$HANDLED\" 2>/dev/null")
            appendLine("  count=\$(wc -l < \"\$HANDLED\" 2>/dev/null || echo 0)")
            appendLine("  if [ \"\$count\" -gt \"\$MAX_HANDLED\" ]; then")
            appendLine("    tail -n \$((MAX_HANDLED / 2)) \"\$HANDLED\" > \"\${HANDLED}.tmp\" 2>/dev/null && mv \"\${HANDLED}.tmp\" \"\$HANDLED\" 2>/dev/null")
            appendLine("  fi")
            appendLine("}")
            appendLine("mask_pid() {")
            appendLine("  local pid=\$1")
            appendLine("  local pkg=\$2")
            appendLine("  if is_handled \"\$pid\"; then return; fi")
            for (path in RootHidePaths.DETECTION_PATHS) {
                appendLine("  test -e '$path' && nsenter -t \"\$pid\" -m -- mount --bind \"\$HIDE_DIR\" '$path' 2>/dev/null")
            }
            appendLine("  echo \"\$(date '+%H:%M:%S') HID pid=\$pid pkg=\$pkg\" >> \"\$LOG\" 2>/dev/null")
            appendLine("  mark_handled \"\$pid\"")
            appendLine("}")
            appendLine("")
            appendLine("scan_once() {")
            appendLine("  for d in /proc/[0-9]*; do")
            appendLine("    pid=\${d#/proc/}")
            appendLine("    cmdline=\$(tr '\\0' ' ' < \"\$d/cmdline\" 2>/dev/null)")
            appendLine("    [ -z \"\$cmdline\" ] && continue")
            appendLine("    cmd_name=\${cmdline%% *}")
            appendLine("    IFS=',' read -ra pkg_arr <<< \"\$PACKAGES\"")
            appendLine("    for pkg in \"\${pkg_arr[@]}\"; do")
            appendLine("      case \"\$cmd_name\" in")
            appendLine("        \"\$pkg\") mask_pid \"\$pid\" \"\$pkg\" ;;")
            appendLine("        \"\$pkg\":*) mask_pid \"\$pid\" \"\$pkg\" ;;")
            appendLine("      esac")
            appendLine("    done")
            appendLine("  done")
            appendLine("}")
            appendLine("")
            appendLine("scan_once")
            appendLine("while true; do")
            appendLine("  scan_once")
            appendLine("  sleep 1")
            appendLine("done")
        }
        suSession.execute("cat > '$WATCHER_SCRIPT_FILE' << 'EOF_HF_WATCH'\n" + script + "\nEOF_HF_WATCH\nchmod 755 '$WATCHER_SCRIPT_FILE'", 8)

        val startRes = suSession.execute(
            "nohup sh '$WATCHER_SCRIPT_FILE' >/data/adb/hanfeng/watcher.log 2>&1 &\n" +
                "echo \$! > '$WATCHER_PID_FILE'\n" +
                "sleep 0.3 && cat '$WATCHER_PID_FILE' && echo STARTED", 8
        )
        val started = startRes.output.contains("STARTED")
        running.set(started)
        if (started) {
            LogRepository.append(context, "RootHide watcher started for ${scopePackages.size} packages: pid=${startRes.output.trim().lines().firstOrNull { it.toIntOrNull() != null }}")
        } else {
            LogRepository.append(context, "RootHide watcher failed to start: ${startRes.output}")
        }
        return started
    }

    fun isRunning(): Boolean = running.get()

    fun stop(): Boolean {
        if (!suSession.isSessionOpen() && !suSession.open(8)) return false
        val r = suSession.execute(
            "if [ -f '$WATCHER_PID_FILE' ]; then " +
                "kill \$(cat '$WATCHER_PID_FILE') 2>/dev/null; " +
                "kill -9 \$(cat '$WATCHER_PID_FILE') 2>/dev/null; " +
                "rm -f '$WATCHER_PID_FILE'; echo STOPPED; " +
                "else echo NOT_RUNNING; fi", 5
        )
        running.set(false)
        return r.output.contains("STOPPED")
    }

    fun refreshScope(context: Context, scopePackages: Set<String>): Boolean {
        // 重启 watcher 即可加载新 scope
        stop()
        return start(context, scopePackages)
    }

    fun status(): WatcherStatus {
        if (!suSession.isSessionOpen() && !suSession.open(8)) {
            return WatcherStatus(false, -1, emptyList(), 0)
        }
        val r = suSession.execute(
            "PID=\$(cat '$WATCHER_PID_FILE' 2>/dev/null); " +
                "if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then echo RUNNING:\$PID; " +
                "else echo NOT_RUNNING; fi", 3
        )
        val running = r.output.contains("RUNNING")
        val pid = r.output.substringAfter("RUNNING:").trim().toIntOrNull() ?: -1

        val handledCount = suSession.execute("wc -l < '$HIDDEN_PIDS_FILE' 2>/dev/null || echo 0", 3)
            .output.trim().toIntOrNull() ?: 0

        // 读取 scope（从 watcher.sh 第 5 行 PACKAGES 变量）
        val scopeRaw = suSession.execute(
            "grep '^PACKAGES=' '$WATCHER_SCRIPT_FILE' 2>/dev/null | sed \"s/^PACKAGES='//; s/'\\\$//\"", 3
        ).output.trim()
        val scope = if (scopeRaw.isBlank()) emptyList() else scopeRaw.split(",")
        return WatcherStatus(running, pid, scope, handledCount)
    }

    /** 读取 watcher.log 最近 N 行日志（给 UI 显示用） */
    fun dumpLog(tailLines: Int = 200): String {
        if (!suSession.isSessionOpen() && !suSession.open(8)) return "Root 不可用"
        val r = suSession.execute("tail -n $tailLines /data/adb/hanfeng/watcher.log 2>/dev/null || echo '(无日志)'", 5)
        return r.output.trim()
    }
}

object RootHidePaths {
    val DETECTION_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/data/adb/magisk.db",
        "/data/adb/magisk",
        "/data/adb/ksu",
        "/data/adb/ap",
        "/data/adb/lspd",
        "/data/adb/lsp",
        "/data/adb/tricky_store",
        "/data/adb/zygisk",
        "/dev/zygisk",
        "/debug_ramdisk",
        "/sbin/.magisk",
        "/system/etc/init/magisk",
        "/cache/.disable_magisk"
    )
}
