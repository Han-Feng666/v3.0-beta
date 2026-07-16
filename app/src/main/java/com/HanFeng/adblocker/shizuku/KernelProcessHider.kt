package com.HanFeng.adblocker.shizuku

import android.util.Log

class KernelProcessHider {

    companion object {
        private const val TAG = "KernelProcessHider"
        private const val KPM_PATH = "/data/adb/modules"
        private const val HIDDEN_PROC_DIR = "/data/local/tmp/.khd"
        private const val HIDDEN_PIDS_FILE = "/data/local/tmp/.hf_hidden_pids"
    }

    sealed class HideResult {
        data class Success(val method: String, val pid: Int) : HideResult()
        data class Failure(val reason: String, val triedMethods: List<String>) : HideResult()
    }

    fun hideProcessDeep(pid: Int, scriptPath: String = "/dev/null"): HideResult {
        if (pid <= 0) return HideResult.Failure("Invalid PID", emptyList())
        val triedMethods = mutableListOf<String>()

        triedMethods.add("pid_namespace")
        if (hideViaPidNamespace(pid, scriptPath)) {
            return HideResult.Success("pid_namespace", pid)
        }

        triedMethods.add("kernelsu_module")
        if (hideViaKernelSUModule(pid)) {
            return HideResult.Success("kernelsu_module", pid)
        }

        triedMethods.add("magisk_kpm")
        if (hideViaMagiskKpm(pid)) {
            return HideResult.Success("magisk_kpm", pid)
        }

        triedMethods.add("procfs_hook")
        if (hideViaProcfsHook(pid)) {
            return HideResult.Success("procfs_hook", pid)
        }

        triedMethods.add("init_mount_bind")
        if (hideViaInitMountBind(pid)) {
            return HideResult.Success("init_mount_bind", pid)
        }

        triedMethods.add("selinux_ctx_hide")
        if (hideViaSeLinuxCtx(pid)) {
            return HideResult.Success("selinux_ctx_hide", pid)
        }

        return HideResult.Failure("All kernel-level hiding methods failed", triedMethods)
    }

    /**
     * 在隔离的 PID namespace 中执行脚本，脚本的所有子进程对其他 App 不可见
     *
     * @param scriptPath 要执行的脚本路径
     * @return 新命名空间中主进程的 PID，失败返回 null
     */
    fun executeHiddenScript(scriptPath: String): Int? {
        return try {
            // 检查 unshare 是否可用
            val checkResult = runRootShell("command -v unshare > /dev/null 2>&1 && echo OK || echo FAIL", 5)
            if (checkResult.output.trim() != "OK") {
                Log.e(TAG, "unshare command not available")
                return null
            }

            // 在新的 PID namespace 中执行脚本
            // --pid: 创建新的 PID 命名空间
            // --fork: fork 子进程
            // --mount-proc: 挂载新的 proc 文件系统
            val result = runRootShell(
                "NEW_PID=\$(unshare --pid --fork --mount-proc sh -c \"exec sh $scriptPath\" & echo \$!)",
                10
            )

            if (result.exitCode == 0) {
                // 提取新进程的 PID
                val pidMatch = Regex("NEW_PID=(\\d+)").find(result.output)
                val newPid = pidMatch?.groupValues?.get(1)?.toIntOrNull()
                if (newPid != null && newPid > 0) {
                    Log.d(TAG, "Script executed in isolated namespace, PID=$newPid")
                    // 注册到隐藏列表
                    registerHiddenPid(newPid)
                    return newPid
                }
            }

            Log.e(TAG, "Failed to execute script in isolated namespace: ${result.output}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "executeHiddenScript failed: ${e.message}")
            null
        }
    }

    /**
     * 在隔离的 PID namespace 中执行命令，命令的所有子进程对其他 App 不可见
     *
     * @param command 要执行的命令
     * @return 新命名空间中主进程的 PID，失败返回 null
     */
    fun executeHiddenCommand(command: String): Int? {
        return try {
            val checkResult = runRootShell("command -v unshare > /dev/null 2>&1 && echo OK || echo FAIL", 5)
            if (checkResult.output.trim() != "OK") {
                return null
            }

            val escapedCmd = command.replace("'", "'\\''")
            val result = runRootShell(
                "NEW_PID=\$(unshare --pid --fork --mount-proc sh -c '$escapedCmd' & echo \$!)",
                10
            )

            if (result.exitCode == 0) {
                val pidMatch = Regex("NEW_PID=(\\d+)").find(result.output)
                val newPid = pidMatch?.groupValues?.get(1)?.toIntOrNull()
                if (newPid != null && newPid > 0) {
                    Log.d(TAG, "Command executed in isolated namespace, PID=$newPid")
                    registerHiddenPid(newPid)
                    return newPid
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "executeHiddenCommand failed: ${e.message}")
            null
        }
    }

    private fun hideViaPidNamespace(pid: Int, scriptPath: String): Boolean {
        return try {
            val result = runRootShell(
                "command -v unshare > /dev/null 2>&1 || exit 1\n" +
                "NEW_PID=\$(unshare --pid --fork --mount-proc sh -c \"exec sh $scriptPath\" & echo \$!)\n" +
                "echo \"NS_PID=\$NEW_PID\""
            )
            result.exitCode == 0 && result.output.contains("NS_PID=")
        } catch (e: Exception) {
            Log.e(TAG, "PID namespace hide failed: ${e.message}")
            false
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun hideViaKernelSUModule(pid: Int): Boolean {
        return try {
            if (!dirExists("/data/adb/ksu")) return false
            val probeResult = runRootShell(
                "if [ -f $KPM_PATH/hf_proc_hide/module.prop ]; then echo EXISTS; else installKpmModule; fi"
            )
            probeResult.exitCode == 0
        } catch (e: Exception) { false }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun hideViaMagiskKpm(pid: Int): Boolean {
        return try {
            if (!dirExists("/data/adb/magisk")) return false
            val result = runRootShell(
                "if [ -d $KPM_PATH/hf_proc_hide ]; then echo MODULE_EXISTS; else createMagiskModule; fi"
            )
            result.exitCode == 0
        } catch (e: Exception) { false }
    }

    private fun createMagiskModule(): Boolean {
        val script = buildString {
            appendLine("mkdir -p $KPM_PATH/hf_proc_hide")
            appendLine("cat > $KPM_PATH/hf_proc_hide/module.prop << 'EOPROP'")
            appendLine("id=hf_proc_hide")
            appendLine("name=HanFeng Process Hide")
            appendLine("version=v1")
            appendLine("versionCode=1")
            appendLine("description=Kernel-level process hiding for HanFeng AdBlocker")
            appendLine("EOPROP")
            appendLine("touch $KPM_PATH/hf_proc_hide/update")
        }
        return runRootShell(script).exitCode == 0
    }

    private fun installKpmModule(): Boolean {
        val kpmScript = buildString {
            appendLine("mkdir -p $KPM_PATH/hf_proc_hide")
            appendLine("cat > $KPM_PATH/hf_proc_hide/module.prop << 'EOPROP'")
            appendLine("id=hf_proc_hide")
            appendLine("name=HanFeng Process Hide")
            appendLine("version=v1")
            appendLine("versionCode=1")
            appendLine("description=Kernel-level process hiding for HanFeng AdBlocker")
            appendLine("EOPROP")
            appendLine("cat > $KPM_PATH/hf_proc_hide/service.sh << 'EOSVC'")
            appendLine("#!/system/bin/sh")
            appendLine("if [ -f $HIDDEN_PIDS_FILE ]; then")
            appendLine("  while IFS= read -r pid; do")
            appendLine("    [ -n \"\$pid\" ] && kill -0 \"\$pid\" 2>/dev/null && mount --bind $HIDDEN_PROC_DIR /proc/\$pid 2>/dev/null")
            appendLine("    [ -n \"\$pid\" ] && kill -0 \"\$pid\" 2>/dev/null && nsenter -t 1 -m -- mount --bind $HIDDEN_PROC_DIR /proc/\$pid 2>/dev/null")
            appendLine("  done < $HIDDEN_PIDS_FILE")
            appendLine("fi")
            appendLine("EOSVC")
            appendLine("chmod 755 $KPM_PATH/hf_proc_hide/service.sh")
            appendLine("touch $KPM_PATH/hf_proc_hide/update")
        }
        return runRootShell(kpmScript).exitCode == 0
    }

    private fun hideViaProcfsHook(pid: Int): Boolean {
        return try {
            ensureHiddenDir()
            val result = runRootShell(
                "echo \"$pid\" >> $HIDDEN_PIDS_FILE 2>/dev/null\n" +
                "mount --bind '$HIDDEN_PROC_DIR' /proc/$pid 2>/dev/null && echo OK || echo FAIL"
            )
            result.output.trim() == "OK"
        } catch (e: Exception) { false }
    }

    private fun hideViaInitMountBind(pid: Int): Boolean {
        return try {
            ensureHiddenDir()
            val result = runRootShell(
                "nsenter -t 1 -m -- mount --bind '$HIDDEN_PROC_DIR' /proc/$pid 2>/dev/null && echo OK || " +
                "(nsenter -t 1 -n -m -- mount --bind '$HIDDEN_PROC_DIR' /proc/$pid 2>/dev/null && echo OK || echo FAIL)"
            )
            result.output.trim() == "OK"
        } catch (e: Exception) { false }
    }

    private fun hideViaSeLinuxCtx(pid: Int): Boolean {
        return try {
            ensureHiddenDir()
            val randomCtx = "u:r:untrusted_app:s0:c${(100..999).random()},c${(100..999).random()}"
            val result = runRootShell(
                "chcon '$randomCtx' /proc/$pid/exe 2>/dev/null || true\n" +
                "echo '$randomCtx' > /proc/$pid/attr/current 2>/dev/null || true\n" +
                "mount --bind '$HIDDEN_PROC_DIR' /proc/$pid 2>/dev/null\n" +
                "echo OK"
            )
            result.output.trim() == "OK"
        } catch (e: Exception) { false }
    }

    fun registerHiddenPid(pid: Int): Boolean {
        return runRootShell("echo '$pid' >> $HIDDEN_PIDS_FILE 2>/dev/null && echo OK || echo FAIL")
            .output.trim() == "OK"
    }

    fun unhideProcess(pid: Int): Boolean {
        runRootShell("grep -v '^${pid}\$' $HIDDEN_PIDS_FILE 2>/dev/null > ${HIDDEN_PIDS_FILE}.tmp && mv ${HIDDEN_PIDS_FILE}.tmp $HIDDEN_PIDS_FILE 2>/dev/null || true")
        return runRootShell("umount /proc/$pid 2>/dev/null || nsenter -t 1 -m umount /proc/$pid 2>/dev/null && echo OK || echo FAIL")
            .output.trim() == "OK"
    }

    fun unhideAll(): Int {
        var count = 0
        val pids = runRootShell("cat $HIDDEN_PIDS_FILE 2>/dev/null || true").output.trim().lines().mapNotNull { it.trim().toIntOrNull() }
        for (pid in pids) {
            if (unhideProcess(pid)) count++
        }
        runRootShell("umount $HIDDEN_PROC_DIR 2>/dev/null || true")
        runRootShell("rm -f $HIDDEN_PIDS_FILE")
        Log.d(TAG, "Unhid $count processes")
        return count
    }

    fun cleanup(): Boolean {
        val success = runRootShell(
            "umount $HIDDEN_PROC_DIR 2>/dev/null || true\n" +
            "rm -f $HIDDEN_PIDS_FILE\n" +
            "rm -rf $HIDDEN_PROC_DIR\n" +
            "rm -rf $KPM_PATH/hf_proc_hide\n" +
            "echo CLEANED"
        ).output.contains("CLEANED")
        if (success) Log.d(TAG, "Cleanup completed")
        return success
    }

    fun isProcessHidden(pid: Int): Boolean {
        val result = runRootShell(
            "if mount | grep -q '/proc/$pid '; then echo HIDDEN; " +
            "elif grep -q '^${pid}\$' $HIDDEN_PIDS_FILE 2>/dev/null; then echo REGISTERED; " +
            "else echo VISIBLE; fi"
        )
        return result.output.trim() in listOf("HIDDEN", "REGISTERED")
    }

    fun installKernelModule(): Boolean {
        val kmodPath = "$KPM_PATH/hf_proc_hide/kmod.ko"
        val result = runRootShell(
            "if [ -f '$kmodPath' ]; then insmod '$kmodPath' 2>/dev/null && echo LOADED || echo FAIL_LOAD; " +
            "elif [ -f /data/adb/ksu/bin/kpm ]; then kpm install --name hf_proc_hide 2>/dev/null && echo KPM_OK || echo FAIL_KPM; " +
            "else echo NO_KERNEL_SUPPORT; fi"
        )
        return result.output.trim() in listOf("LOADED", "KPM_OK")
    }

    fun detectKernelHidingCapability(): String {
        val caps = mutableListOf<String>()

        if (runRootShell("command -v unshare > /dev/null 2>&1 && echo OK || echo FAIL").output.trim() == "OK") {
            caps.add("pid_namespace")
        }

        if (dirExists("/data/adb/ksu")) caps.add("kernelsu")
        if (dirExists("/data/adb/magisk")) caps.add("magisk")

        if (runRootShell("command -v nsenter > /dev/null 2>&1 && echo OK || echo FAIL").output.trim() == "OK") {
            caps.add("nsenter")
        }

        if (runRootShell("command -v insmod > /dev/null 2>&1 && echo OK || echo FAIL").output.trim() == "OK") {
            caps.add("insmod")
        }

        caps.add("mount_bind")
        return caps.joinToString(", ")
    }

    fun getHiddenPids(): List<Int> {
        return runRootShell("cat $HIDDEN_PIDS_FILE 2>/dev/null || true").output.trim().lines().mapNotNull { it.trim().toIntOrNull() }
    }

    fun restoreProcfs(): Boolean {
        return unhideAll() > 0
    }

    private fun ensureHiddenDir() {
        if (!dirExists(HIDDEN_PROC_DIR)) {
            runRootShell("mkdir -p '$HIDDEN_PROC_DIR' 2>/dev/null || true")
        }
        runRootShell("touch '$HIDDEN_PROC_DIR/.empty' 2>/dev/null || true")
    }

    private val suSession get() = SuSession.getInstance()

    private fun runRootShell(command: String, timeoutSeconds: Long = 30): ShellResult {
        if (!suSession.isSessionOpen()) {
            suSession.open(timeoutSeconds = timeoutSeconds)
        }
        val result = suSession.execute(command, timeoutSeconds)
        return ShellResult(result.exitCode, result.output)
    }

    private fun dirExists(path: String): Boolean {
        return runRootShell("test -d '$path' && echo OK || echo FAIL").output.trim() == "OK"
    }

    private data class ShellResult(val exitCode: Int, val output: String)
}
