package com.hanfeng.adblocker.shizuku

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

class RootScriptExecutor(private val context: Context) {

    companion object {
        private const val TAG = "RootScriptExecutor"
        private const val HIDDEN_DIR = "/data/local/tmp/.hsp"
        private const val SCRIPT_TMP_PREFIX = "/data/local/tmp/.hs_"
    }

    private val kernelHider = KernelProcessHider()
    private val suSession get() = SuSession.getInstance()

    data class ScriptResult(
        val pid: Int?,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val processHidden: Boolean,
        val processHiddenMethod: String? = null
    )

    data class CommandResult(
        val exitCode: Int,
        val output: String
    )

    fun executeScriptFile(scriptPath: String, hideProcess: Boolean = true, timeoutSeconds: Long = 120): ScriptResult {
        val scriptFile = File(scriptPath)
        if (!scriptFile.exists() || !scriptFile.canRead()) {
            return ScriptResult(null, -1, "", "Script file not found or not readable: $scriptPath", false, null)
        }
        val tmpScript = "$SCRIPT_TMP_PREFIX${System.currentTimeMillis()}.sh"
        val copyResult = runRootCommand("cp '$scriptPath' '$tmpScript' && chmod 700 '$tmpScript'")
        if (copyResult.exitCode != 0) {
            return ScriptResult(null, -1, "", "Failed to prepare script: ${copyResult.output}", false, null)
        }
        return executeTmpScript(tmpScript, hideProcess, timeoutSeconds)
    }

    fun executeScriptContent(content: String, hideProcess: Boolean = true, timeoutSeconds: Long = 120): ScriptResult {
        if (content.isBlank()) {
            return ScriptResult(null, -1, "", "Script content is empty", false, null)
        }
        val tmpScript = "$SCRIPT_TMP_PREFIX${System.currentTimeMillis()}.sh"
        val writeResult = runRootCommand("base64 -d <<< '${android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)}' > '$tmpScript' && chmod 700 '$tmpScript'")
        if (writeResult.exitCode != 0) {
            return ScriptResult(null, -1, "", "Failed to write script: ${writeResult.output}", false, null)
        }
        return executeTmpScript(tmpScript, hideProcess, timeoutSeconds)
    }

    private fun executeTmpScript(tmpScript: String, hideProcess: Boolean, timeoutSeconds: Long): ScriptResult {
        if (!hideProcess) {
            return executeTmpScriptDirect(tmpScript, timeoutSeconds)
        }
        return executeTmpScriptWithHide(tmpScript, timeoutSeconds)
    }

    private fun executeTmpScriptDirect(tmpScript: String, timeoutSeconds: Long): ScriptResult {
        val result = runRootCommand("sh '$tmpScript' 2>&1", timeoutSeconds)
        runRootCommand("rm -f '$tmpScript'")
        return ScriptResult(
            pid = null,
            exitCode = result.exitCode,
            stdout = result.output,
            stderr = "",
            processHidden = false
        )
    }

    private fun executeTmpScriptWithHide(tmpScript: String, timeoutSeconds: Long): ScriptResult {
        val outputFile = "$tmpScript.out"
        val pidFile = "$tmpScript.pid"
        runRootCommand("mkdir -p $HIDDEN_DIR && touch $HIDDEN_DIR/.empty")

        val execCmd = "nohup sh '$tmpScript' > '$outputFile' 2>&1 & PID=" + '$'.toString() + "! ; echo \$PID > '$pidFile' ; echo \$PID"
        val execResult = runRootCommand(execCmd, timeoutSeconds = 10)
        val pid = execResult.output.trim().lines().lastOrNull()?.trim()?.toIntOrNull()

        var processHidden = false
        var hiddenMethod: String? = null
        if (pid != null && pid > 0) {
            val hideResult = kernelHider.hideProcessDeep(pid, tmpScript)
            when (hideResult) {
                is KernelProcessHider.HideResult.Success -> {
                    processHidden = true
                    hiddenMethod = hideResult.method
                    Log.d(TAG, "Process $pid hidden via $hiddenMethod")
                }
                is KernelProcessHider.HideResult.Failure -> {
                    Log.e(TAG, "Kernel-level hiding failed for PID $pid: ${hideResult.reason}")
                    val fb = runRootCommand(
                        "mount --bind '$HIDDEN_DIR' /proc/$pid 2>/dev/null || nsenter -t 1 -m mount --bind '$HIDDEN_DIR' /proc/$pid 2>/dev/null && echo OK || echo FAIL"
                    )
                    processHidden = fb.output.trim() == "OK"
                    hiddenMethod = if (processHidden) "mount_bind_fallback" else null
                }
            }
        }

        val waitCmd = "PID=\$(cat '$pidFile' 2>/dev/null); [ -n \"\$PID\" ] && while kill -0 \"\$PID\" 2>/dev/null; do sleep 1; done; cat '$outputFile' 2>/dev/null"
        val outputResult = runRootCommand(waitCmd, timeoutSeconds)

        val outputText = outputResult.output
        val stdout = if (outputText.length > 64 * 1024) outputText.take(64 * 1024) else outputText

        runRootCommand("rm -f '$tmpScript' '$outputFile' '$pidFile'")
        return ScriptResult(
            pid = pid,
            exitCode = outputResult.exitCode,
            stdout = stdout,
            stderr = "",
            processHidden = processHidden,
            processHiddenMethod = hiddenMethod
        )
    }

    fun hideExistingProcess(pid: Int): Pair<Boolean, String> {
        if (pid <= 0) return false to "Invalid PID"
        val hideResult = kernelHider.hideProcessDeep(pid, "/dev/null")
        return when (hideResult) {
            is KernelProcessHider.HideResult.Success -> {
                Log.d(TAG, "Process $pid hidden via ${hideResult.method}")
                true to hideResult.method
            }
            is KernelProcessHider.HideResult.Failure -> {
                Log.e(TAG, "Kernel-level hiding failed for PID $pid: ${hideResult.reason}")
                val fallbackResult = runRootCommand(
                    "mkdir -p $HIDDEN_DIR && touch $HIDDEN_DIR/.empty && mount --bind $HIDDEN_DIR /proc/$pid 2>/dev/null && echo OK || echo FAIL"
                )
                val fallbackOk = fallbackResult.output.trim() == "OK"
                if (fallbackOk) {
                    true to "mount_bind_fallback"
                } else {
                    false to hideResult.reason
                }
            }
        }
    }

    fun unhideProcess(pid: Int): Boolean {
        if (pid <= 0) return false
        return kernelHider.unhideProcess(pid)
    }

    fun isProcessRunning(pid: Int): Boolean {
        val result = runRootCommand("kill -0 $pid 2>/dev/null && echo ALIVE || echo DEAD")
        return result.output.trim() == "ALIVE"
    }

    fun detectKernelHidingCapability(): String {
        return kernelHider.detectKernelHidingCapability()
    }

    fun isKernelHidingAvailable(): Boolean {
        return kernelHider.detectKernelHidingCapability().isNotBlank()
    }

    fun checkRootAvailable(): Boolean {
        return if (suSession.isSessionOpen()) {
            suSession.checkPermission()
        } else {
            suSession.open(timeoutSeconds = 30)
        }
    }

    fun ensureRootPermission(): Boolean {
        if (suSession.isSessionOpen() && suSession.checkPermission()) return true
        return suSession.open(timeoutSeconds = 30)
    }

    private fun runRootCommand(command: String, timeoutSeconds: Long = 30): CommandResult {
        if (!suSession.isSessionOpen()) {
            suSession.open(timeoutSeconds = timeoutSeconds)
        }
        val result = suSession.execute(command, timeoutSeconds)
        return CommandResult(result.exitCode, result.output)
    }
}
