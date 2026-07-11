package com.HanFeng.adblocker.shizuku

import android.content.Context
import com.HanFeng.data.GameAntiMarkRepository
import com.HanFeng.data.LogRepository
import java.util.concurrent.atomic.AtomicBoolean

object GameAntiMarkManager {
    private const val TAG = "GameAntiMark"
    private val running = AtomicBoolean(false)
    private val suSession get() = SuSession.getInstance()

    data class WatcherStatus(
        val running: Boolean,
        val pid: String?,
        val gamesRunning: Int,
        val lastCleanedAt: String,
        val persistPerm: String,
        val cleanedCount: Int,
        val bootCompleted: Boolean,
        val sm8850Detected: Boolean
    )

    fun isRunning(): Boolean = running.get()

    fun checkSm8850(): Boolean {
        return try {
            val soc = suSession.execute("getprop | grep -i soc | grep -q 'SM8850' && echo MATCH || echo NOMATCH", 5)
                .output.trim()
            soc.contains("MATCH")
        } catch (_: Exception) {
            false
        }
    }

    fun start(context: Context, sm8850Fallback: Boolean = false): Boolean {
        val packages = GameAntiMarkRepository.getTargetPackages(context)
        if (packages.isEmpty()) return false
        if (!suSession.isSessionOpen() && !suSession.open(30)) return false
        if (running.get()) stop()

        LogRepository.append(context, "[$TAG] starting watcher with ${packages.size} packages, sm8850=$sm8850Fallback")

        suSession.execute("mkdir -p /data/adb/GameAntiMark", 3)

        writeTargetFile(packages)

        val mrpcsList = GameAntiMarkRepository.MRPCS_EXTRA_PACKAGES
            .filter { it in packages }
            .joinToString(",")

        val script = buildScript(
            targetFile = GameAntiMarkRepository.DEFAULT_TARGET_FILE_PATH,
            stateDir = GameAntiMarkRepository.STATE_DIR,
            targetDir = GameAntiMarkRepository.TARGET_DIR,
            logFile = GameAntiMarkRepository.LOG_FILE,
            sm8850Flag = if (sm8850Fallback) "1" else "0",
            mrpcsList = mrpcsList
        )

        suSession.execute(
            "cat > '${GameAntiMarkRepository.WATCHER_SCRIPT}' << 'EOF_HF_GAM'\n" +
                script + "\nEOF_HF_GAM\nchmod 755 '${GameAntiMarkRepository.WATCHER_SCRIPT}'", 8
        )

        val startResult = suSession.execute(
            "nohup sh '${GameAntiMarkRepository.WATCHER_SCRIPT}' > '${GameAntiMarkRepository.LOG_FILE}' 2>&1 &\n" +
                "echo \$! > '${GameAntiMarkRepository.PID_FILE}'\n" +
                "sleep 0.3 && cat '${GameAntiMarkRepository.PID_FILE}' && echo STARTED", 8
        )
        val started = startResult.output.contains("STARTED")
        running.set(started)
        if (started) {
            GameAntiMarkRepository.setAutoWatcherEnabled(context, true)
            LogRepository.append(context, "[$TAG] watcher started, pid=${startResult.output.trim().lines().firstOrNull { it.toIntOrNull() != null }}")
        } else {
            LogRepository.append(context, "[$TAG] watcher failed to start: ${startResult.output}")
        }
        return started
    }

    private fun writeTargetFile(packages: LinkedHashSet<String>) {
        val targetFile = GameAntiMarkRepository.DEFAULT_TARGET_FILE_PATH
        val body = packages.joinToString("\n")
        suSession.execute(
            "cat > '$targetFile.tmp' << 'EOF_TARGET'\n$body\nEOF_TARGET\n" +
                "mv -f '$targetFile.tmp' '$targetFile' && chmod 644 '$targetFile'", 5
        )
    }

    fun stop(): Boolean {
        if (!suSession.isSessionOpen() && !suSession.open(8)) return false
        val r = suSession.execute(
            "if [ -f '${GameAntiMarkRepository.PID_FILE}' ]; then " +
                "kill \$(cat '${GameAntiMarkRepository.PID_FILE}') 2>/dev/null; " +
                "kill -9 \$(cat '${GameAntiMarkRepository.PID_FILE}') 2>/dev/null; " +
                "rm -f '${GameAntiMarkRepository.PID_FILE}'; echo STOPPED; " +
                "else echo NOT_RUNNING; fi", 5
        )
        running.set(false)
        return r.output.contains("STOPPED") || r.output.contains("NOT_RUNNING")
    }

    fun stopAndRestore(context: Context): Boolean {
        val stopped = stop()
        if (stopped) {
            suSession.execute("chmod 700 '${GameAntiMarkRepository.TARGET_DIR}' 2>/dev/null", 3)
            suSession.execute("rm -rf '${GameAntiMarkRepository.STATE_DIR}' 2>/dev/null", 3)
            GameAntiMarkRepository.setAutoWatcherEnabled(context, false)
            LogRepository.append(context, "[$TAG] watcher stopped, permission restored to 700")
        }
        return stopped
    }

    fun status(context: Context): WatcherStatus {
        if (!suSession.isSessionOpen() && !suSession.open(8)) {
            return WatcherStatus(false, null, 0, "", "unknown", 0, false, GameAntiMarkRepository.isSm8850Detected(context))
        }
        val pidRaw = suSession.execute("cat '${GameAntiMarkRepository.PID_FILE}' 2>/dev/null || echo NONE", 3)
            .output.trim()
        val pidIsAlive = if (pidRaw != "NONE" && pidRaw.isNotBlank()) {
            suSession.execute("kill -0 '$pidRaw' 2>/dev/null && echo ALIVE || echo DEAD", 3)
                .output.trim().contains("ALIVE")
        } else false
        running.set(pidIsAlive)

        val gamesRunning = suSession.execute("ls -1 '${GameAntiMarkRepository.STATE_DIR}' 2>/dev/null | wc -l", 3)
            .output.trim().toIntOrNull() ?: 0
        val cleanedCount = suSession.execute("grep -c 'CLEANED' '${GameAntiMarkRepository.LOG_FILE}' 2>/dev/null || echo 0", 3)
            .output.trim().toIntOrNull() ?: 0
        val persistPerm = suSession.execute("stat -c '%a' '${GameAntiMarkRepository.TARGET_DIR}' 2>/dev/null || echo UNKNOWN", 3)
            .output.trim()
        val lastCleanedAt = suSession.execute("grep 'CLEANED' '${GameAntiMarkRepository.LOG_FILE}' 2>/dev/null | tail -1 | head -c 24", 3)
            .output.trim()
        val bootCompleted = suSession.execute("getprop sys.boot_completed", 3).output.trim() == "1"
        val sm8850 = GameAntiMarkRepository.isSm8850Detected(context)
        return WatcherStatus(
            running = pidIsAlive,
            pid = if (pidRaw == "NONE") null else pidRaw,
            gamesRunning = gamesRunning,
            lastCleanedAt = lastCleanedAt,
            persistPerm = persistPerm,
            cleanedCount = cleanedCount,
            bootCompleted = bootCompleted,
            sm8850Detected = sm8850
        )
    }

    fun dumpLog(tailLines: Int = 200): String {
        if (!suSession.isSessionOpen() && !suSession.open(8)) return "Root 不可用"
        return suSession.execute("tail -n $tailLines '${GameAntiMarkRepository.LOG_FILE}' 2>/dev/null || echo '(无日志)'", 5)
            .output.trim()
    }

    fun randomizeDeviceIds(context: Context): Pair<Boolean, String> {
        if (!suSession.isSessionOpen() && !suSession.open(30)) return false to "Root 不可用"
        return try {
            val newAndroidId = randomHex(16)
            suSession.execute("settings put secure android_id '$newAndroidId'", 5)

            val targetPackages = GameAntiMarkRepository.getTargetPackages(context)
            val userIds = listOf(0, 10, 11, 901, 999)

            for (userId in userIds) {
                val userDir = "/data/system/users/$userId"
                val exists = suSession.execute("test -d '$userDir' && echo EXISTS || echo NOSUCH", 3)
                    .output.trim()
                if (exists == "NOSUCH") continue
                val fileNames = listOf(
                    "settings_ssaid.xml",
                    "settings_ssaid.xml.fallback",
                    "settings_ssaid.xml.bptmp"
                )
                for (targetPkg in targetPackages) {
                    val newSsaid = randomHex(16)
                    for (fileName in fileNames) {
                        val filePath = "$userDir/$fileName"
                        val oldSsaidResult = suSession.execute(
                            "FILE='$filePath'\n" +
                                "[ -f \"\$FILE\" ] || exit 0\n" +
                                "grep -a -oE '[a-f0-9]{16}|${targetPkg}' \"\$FILE\" 2>/dev/null " +
                                "| grep -B1 -A1 '${targetPkg}' " +
                                "| grep -oE '[a-f0-9]{16}' | head -n 1", 5
                        ).output.trim()
                        if (oldSsaidResult.length == 16) {
                            suSession.execute("sed -i 's/$oldSsaidResult/$newSsaid/g' '$filePath'", 5)
                        }
                    }
                }
            }

            LogRepository.append(context, "[$TAG] androidId and SSAID randomized, reboot required")
            true to "AndroidID/SSAID 已随机修改。重启手机以应用更改。"
        } catch (e: Exception) {
            LogRepository.append(context, "[$TAG] randomizeDeviceIds failed: ${e.message}")
            false to "修改失败：${e.message ?: "未知错误"}"
        }
    }

    private fun randomHex(length: Int): String {
        val charset = "0123456789abcdef"
        return (1..length).map { charset.random() }.joinToString("")
    }

    private fun buildScript(
        targetFile: String,
        stateDir: String,
        targetDir: String,
        logFile: String,
        sm8850Flag: String,
        mrpcsList: String
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# HanFeng GameAntiMark Watcher — 防设备标记守护脚本")
        appendLine("LOG='$logFile'")
        appendLine("STATE_DIR='$stateDir'")
        appendLine("TARGET_PACKAGES_FILE='$targetFile'")
        appendLine("TARGET_DIR='$targetDir'")
        appendLine("IS_SM8850='$sm8850Flag'")
        appendLine("MRPCS_PACKAGES='$mrpcsList'")
        appendLine("CHECK_INTERVAL=2")
        appendLine("")
        appendLine("log_msg() { echo \"\$(date '+%Y-%m-%d %H:%M:%S') [\$1]\" >> \"\$LOG\" 2>/dev/null; }")
        appendLine("")
        appendLine("create_state_dir() {")
        appendLine("  rm -rf \"\$STATE_DIR\" 2>/dev/null")
        appendLine("  mkdir -p \"\$STATE_DIR\"")
        appendLine("}")
        appendLine("")
        appendLine("cleanup_ano_tmp() {")
        appendLine("  local pkg=\$1")
        appendLine("  [ -z \"\$pkg\" ] && return")
        appendLine("  for uid in 0 10 11 901 999; do")
        appendLine("    local d=\"/data/user/\$uid/\$pkg/files/ano_tmp\"")
        appendLine("    [ -d \"\$d\" ] && rm -rf \"\$d\" 2>/dev/null")
        appendLine("  done")
        appendLine("}")
        appendLine("")
        appendLine("cleanup_app_cache() {")
        appendLine("  local pkg=\$1")
        appendLine("  [ -z \"\$pkg\" ] && return")
        appendLine("  for uid in 0 10 11 901 999; do")
        appendLine("    local base=\"/data/user/\$uid/\$pkg\"")
        appendLine("    [ -d \"\$base\" ] || continue")
        appendLine("    rm -rf \"\$base/cache/\"* 2>/dev/null")
        appendLine("    rm -rf \"\$base/code_cache/\"* 2>/dev/null")
        appendLine("  done")
        appendLine("  rm -rf \"/sdcard/Android/data/\$pkg/cache/\"* 2>/dev/null")
        appendLine("}")
        appendLine("")
        appendLine("cleanup_mrpcs_files() {")
        appendLine("  local pkg=\$1")
        appendLine("  [ -z \"\$pkg\" ] && return")
        appendLine("  for uid in 0 10 11 901 999; do")
        appendLine("    local d=\"/data/user/\$uid/\$pkg/files\"")
        appendLine("    [ -d \"\$d\" ] || continue")
        appendLine("    find \"\$d\" -name '*mrpcs*' -exec rm -rf {} + 2>/dev/null")
        appendLine("  done")
        appendLine("}")
        appendLine("")
        appendLine("set_000_permissions() {")
        appendLine("  [ \"\$IS_SM8850\" = \"1\" ] && return")
        appendLine("  [ -d \"\$TARGET_DIR\" ] && chmod 000 \"\$TARGET_DIR\" 2>/dev/null")
        appendLine("}")
        appendLine("")
        appendLine("restore_to_700_permissions() {")
        appendLine("  [ \"\$IS_SM8850\" = \"1\" ] && return")
        appendLine("  [ -d \"\$TARGET_DIR\" ] && chmod 700 \"\$TARGET_DIR\" 2>/dev/null")
        appendLine("}")
        appendLine("")
        appendLine("try_cleanup_package() {")
        appendLine("  local pkg=\$1")
        appendLine("  cleanup_ano_tmp \"\$pkg\"")
        appendLine("  cleanup_app_cache \"\$pkg\"")
        appendLine("  case \"\$MRPCS_PACKAGES\" in *\"\$pkg\"*)")
        appendLine("    cleanup_mrpcs_files \"\$pkg\"")
        appendLine("    ;;")
        appendLine("  esac")
        appendLine("}")
        appendLine("")
        appendLine("while [ \"\$(getprop sys.boot_completed)\" != \"1\" ]; do")
        appendLine("  sleep 1")
        appendLine("done")
        appendLine("")
        appendLine("chmod 700 \"\$TARGET_DIR\" 2>/dev/null")
        appendLine("")
        appendLine("if [ \"\$IS_SM8850\" = \"1\" ]; then")
        appendLine("  log_msg \"SM8850 detected, skipping permission changes (TEE protection mode)\"")
        appendLine("fi")
        appendLine("")
        appendLine("create_state_dir")
        appendLine("log_msg \"watcher started packages=\$(wc -l < \"\$TARGET_PACKAGES_FILE\" 2>/dev/null || echo 0)\"")
        appendLine("")
        appendLine("games_were_running=0")
        appendLine("while true; do")
        appendLine("  [ -f \"\$TARGET_PACKAGES_FILE\" ] || { sleep 4; continue; }")
        appendLine("  games_running_now=0")
        appendLine("  while IFS= read -r pkg || [ -n \"\$pkg\" ]; do")
        appendLine("    [ -z \"\$pkg\" ] && continue")
        appendLine("    case \"\$pkg\" in '#'*) continue;; esac")
        appendLine("    if pidof \"\$pkg\" > /dev/null 2>&1; then")
        appendLine("      games_running_now=\$((games_running_now + 1))")
        appendLine("      touch \"\$STATE_DIR/\$pkg\" 2>/dev/null")
        appendLine("    else")
        appendLine("      if [ -f \"\$STATE_DIR/\$pkg\" ]; then")
        appendLine("        try_cleanup_package \"\$pkg\"")
        appendLine("        log_msg \"CLEANED \$pkg\"")
        appendLine("        rm -f \"\$STATE_DIR/\$pkg\" 2>/dev/null")
        appendLine("      fi")
        appendLine("    fi")
        appendLine("  done < \"\$TARGET_PACKAGES_FILE\"")
        appendLine("")
        appendLine("  if [ \"\$games_were_running\" -eq 0 ] && [ \"\$games_running_now\" -gt 0 ]; then")
        appendLine("    set_000_permissions")
        appendLine("    log_msg \"permission set to 000 (games started)\"")
        appendLine("    sleep 4")
        appendLine("  fi")
        appendLine("")
        appendLine("  if [ \"\$games_were_running\" -gt 0 ] && [ \"\$games_running_now\" -eq 0 ]; then")
        appendLine("    sleep \"\$CHECK_INTERVAL\"")
        appendLine("    restore_to_700_permissions")
        appendLine("    log_msg \"permission restored to 700 (games stopped)\"")
        appendLine("  fi")
        appendLine("")
        appendLine("  games_were_running=\$games_running_now")
        appendLine("  sleep \"\$CHECK_INTERVAL\"")
        appendLine("done")
    }
}
