package com.hanfeng.adblocker.shizuku

import android.util.Log

class KernelProcessHider {

    companion object {
        private const val TAG = "KernelProcessHider"
        private const val KPM_PATH = "/data/adb/modules"
        private const val HIDDEN_PROC_DIR = "/data/local/tmp/.khd"
    }

    sealed class HideResult {
        data class Success(val method: String, val pid: Int) : HideResult()
        data class Failure(val reason: String, val triedMethods: List<String>) : HideResult()
    }

    data class ShellResult(val exitCode: Int, val output: String)

    fun hideProcessDeep(pid: Int, scriptPath: String): HideResult {
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

    @Suppress("UNUSED_PARAMETER")
    private fun hideViaPidNamespace(pid: Int, scriptPath: String): Boolean {
        return try {
            val result = runRootShell("""
                command -v unshare > /dev/null 2>&1 || exit 1
                unshare --pid --fork --mount-proc sh -c '
                    exec sh '"'$scriptPath'"'
                ' &
                NEW_PID=${'$'}!
                echo "NS_PID=${'$'}NEW_PID"
            """.trimIndent())
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
            val probeResult = runRootShell("""
                if [ -f /data/adb/modules/hf_proc_hide/module.prop ]; then
                    echo "EXISTS"
                else
                    installKpmModule()
                fi
            """.trimIndent())
            probeResult.exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun hideViaMagiskKpm(pid: Int): Boolean {
        return try {
            if (!dirExists("/data/adb/magisk")) return false
            val result = runRootShell("""
                if [ -d /data/adb/modules/hf_proc_hide ]; then
                    echo "MODULE_EXISTS"
                    exit 0
                fi
                mkdir -p /data/adb/modules/hf_proc_hide
                echo "id=hf_proc_hide" > /data/adb/modules/hf_proc_hide/module.prop
                echo "name=HanFeng Process Hide" >> /data/adb/modules/hf_proc_hide/module.prop
                echo "version=v1" >> /data/adb/modules/hf_proc_hide/module.prop
                echo "versionCode=1" >> /data/adb/modules/hf_proc_hide/module.prop
                echo "description=Kernel-level process hiding for HanFeng AdBlocker" >> /data/adb/modules/hf_proc_hide/module.prop
                touch /data/adb/modules/hf_proc_hide/update
                echo "MODULE_CREATED"
            """.trimIndent())
            result.exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun installKpmModule(): Boolean {
        val kpmScript = """
mkdir -p /data/adb/modules/hf_proc_hide
cat > /data/adb/modules/hf_proc_hide/module.prop << 'EOPROP'
id=hf_proc_hide
name=HanFeng Process Hide
version=v1
versionCode=1
description=Kernel-level process hiding for HanFeng AdBlocker
EOPROP
cat > /data/adb/modules/hf_proc_hide/service.sh << 'EOSVC'
#!/system/bin/sh
HIDDEN_PIDS_FILE="/data/local/tmp/.hf_hidden_pids"
if [ -f "${'$'}HIDDEN_PIDS_FILE" ]; then
    while IFS= read -r pid; do
        if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then
            mount --bind /data/local/tmp/.khd /proc/${'$'}pid 2>/dev/null
            nsenter -t 1 -m -- mount --bind /data/local/tmp/.khd /proc/${'$'}pid 2>/dev/null
        fi
    done < "${'$'}HIDDEN_PIDS_FILE"
fi
EOSVC
chmod 755 /data/adb/modules/hf_proc_hide/service.sh
touch /data/adb/modules/hf_proc_hide/update
        """.trimIndent()
        return runRootShell(kpmScript).exitCode == 0
    }

    private fun hideViaProcfsHook(pid: Int): Boolean {
        return try {
            mkdirIfNeeded(HIDDEN_PROC_DIR)
            touchFile("$HIDDEN_PROC_DIR/.empty")
            val result = runRootShell("""
                echo "$pid" >> /data/local/tmp/.hf_hidden_pids
                mount --bind '$HIDDEN_PROC_DIR' /proc/$pid 2>/dev/null && echo OK || echo FAIL
            """.trimIndent())
            result.output.trim() == "OK"
        } catch (e: Exception) {
            false
        }
    }

    private fun hideViaInitMountBind(pid: Int): Boolean {
        return try {
            mkdirIfNeeded(HIDDEN_PROC_DIR)
            touchFile("$HIDDEN_PROC_DIR/.empty")
            val result = runRootShell("""
                nsenter -t 1 -m -- mount --bind '$HIDDEN_PROC_DIR' /proc/$pid 2>/dev/null && echo OK || (
                    nsenter -t 1 -n -m -- mount --bind '$HIDDEN_PROC_DIR' /proc/$pid 2>/dev/null && echo OK || echo FAIL
                )
            """.trimIndent())
            result.output.trim() == "OK"
        } catch (e: Exception) {
            false
        }
    }

    private fun hideViaSeLinuxCtx(pid: Int): Boolean {
        return try {
            val randomCtx = "u:r:untrusted_app:s0:c${(100..999).random()},c${(100..999).random()}"
            val result = runRootShell("""
                chcon '$randomCtx' /proc/$pid/exe 2>/dev/null || true
                if [ -f /proc/$pid/attr/current ]; then
                    echo '$randomCtx' > /proc/$pid/attr/current 2>/dev/null || true
                fi
                mount --bind '$HIDDEN_PROC_DIR' /proc/$pid 2>/dev/null
                echo OK
            """.trimIndent())
            result.output.trim() == "OK"
        } catch (e: Exception) {
            false
        }
    }

    fun registerHiddenPid(pid: Int): Boolean {
        return runRootShell("echo '$pid' >> /data/local/tmp/.hf_hidden_pids 2>/dev/null && echo OK || echo FAIL")
            .output.trim() == "OK"
    }

    fun unhideProcess(pid: Int): Boolean {
        runRootShell("sed -i '/^${pid}\$/d' /data/local/tmp/.hf_hidden_pids 2>/dev/null || true")
        return runRootShell("umount /proc/$pid 2>/dev/null || nsenter -t 1 -m umount /proc/$pid 2>/dev/null && echo OK || echo FAIL")
            .output.trim() == "OK"
    }

    fun isProcessHidden(pid: Int): Boolean {
        val result = runRootShell("""
            if mount | grep -q '/proc/$pid'; then
                echo HIDDEN
            elif grep -q '^$pid\$' /data/local/tmp/.hf_hidden_pids 2>/dev/null; then
                echo REGISTERED
            else
                echo VISIBLE
            fi
        """.trimIndent())
        return result.output.trim() in listOf("HIDDEN", "REGISTERED")
    }

    fun installKernelModule(): Boolean {
        val kmodPath = "/data/adb/modules/hf_proc_hide/kmod.ko"
        val result = runRootShell("""
            if [ -f '$kmodPath' ]; then
                insmod '$kmodPath' 2>/dev/null && echo LOADED || echo FAIL_LOAD
            elif [ -f /data/adb/ksu/bin/kpm ]; then
                kpm install --name hf_proc_hide 2>/dev/null && echo KPM_OK || echo FAIL_KPM
            else
                echo NO_KERNEL_SUPPORT
            fi
        """.trimIndent())
        return result.output.trim() in listOf("LOADED", "KPM_OK")
    }

    fun detectKernelHidingCapability(): String {
        val caps = mutableListOf<String>()

        if (runRootShell("command -v unshare > /dev/null 2>&1 && echo OK || echo FAIL").output.trim() == "OK") {
            caps.add("pid_namespace")
        }

        if (dirExists("/data/adb/ksu")) {
            caps.add("kernelsu")
        }

        if (dirExists("/data/adb/magisk")) {
            caps.add("magisk")
        }

        if (runRootShell("command -v nsenter > /dev/null 2>&1 && echo OK || echo FAIL").output.trim() == "OK") {
            caps.add("nsenter")
        }

        if (runRootShell("command -v insmod > /dev/null 2>&1 && echo OK || echo FAIL").output.trim() == "OK") {
            caps.add("insmod")
        }

        caps.add("mount_bind")

        return caps.joinToString(", ")
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

    private fun mkdirIfNeeded(path: String) {
        runRootShell("mkdir -p '$path' 2>/dev/null || true")
    }

    private fun touchFile(path: String) {
        runRootShell("touch '$path' 2>/dev/null || true")
    }
}
