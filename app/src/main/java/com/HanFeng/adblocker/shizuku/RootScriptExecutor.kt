package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Base64
import android.util.Log

class RootScriptExecutor(private val context: Context) {

    companion object {
        private const val TAG = "RootScriptExecutor"
        private const val HIDDEN_DIR = "/data/local/tmp/.hsp"
        private const val SCRIPT_TMP_PREFIX = "/data/local/tmp/.hs_"
        private const val MAX_STDOUT_SIZE = 64 * 1024
    }

    private val kernelHider = KernelProcessHider()
    private val suSession get() = SuSession.getInstance()
    private var activePid: Int? = null
    private var interactiveSession: SuSession.InteractiveSession? = null
    private val lock = Any()

    data class ScriptResult(
        val pid: Int?,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val processHidden: Boolean,
        val processHiddenMethod: String? = null
    )

    data class InteractiveStep(
        val matchText: String,
        val response: String,
        val timeoutMs: Long = 10000
    )

    data class InteractiveResult(
        val exitCode: Int,
        val finalOutput: String,
        val stepsCompleted: List<String>,
        val stepsFailed: List<String>
    )

    fun executeScriptFile(scriptPath: String, hideProcess: Boolean = true, timeoutSeconds: Long = 120): ScriptResult {
        val scriptFile = java.io.File(scriptPath)
        if (!scriptFile.exists() || !scriptFile.canRead()) {
            return ScriptResult(null, -1, "", "Script file not found or not readable: $scriptPath", false, null)
        }
        val tmpScript = "${SCRIPT_TMP_PREFIX}${System.currentTimeMillis()}.sh"
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
        val tmpScript = "${SCRIPT_TMP_PREFIX}${System.currentTimeMillis()}.sh"
        val encoded = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        val writeResult = runRootCommand("echo '$encoded' | base64 -d > '$tmpScript' && chmod 700 '$tmpScript'")
        if (writeResult.exitCode != 0) {
            return ScriptResult(null, -1, "", "Failed to write script: ${writeResult.output}", false, null)
        }
        return executeTmpScript(tmpScript, hideProcess, timeoutSeconds)
    }

    fun cancelScript(): Boolean {
        synchronized(lock) {
            val pid = activePid ?: return false
            val killed = runRootCommand("kill -TERM $pid 2>/dev/null; sleep 1; kill -KILL $pid 2>/dev/null").exitCode == 0
            if (killed) {
                kernelHider.unhideProcess(pid)
                activePid = null
                Log.d(TAG, "Script cancelled: PID=$pid")
            }
            return killed
        }
    }

    private fun executeTmpScript(tmpScript: String, hideProcess: Boolean, timeoutSeconds: Long): ScriptResult {
        if (!hideProcess) return executeTmpScriptDirect(tmpScript, timeoutSeconds)
        return executeTmpScriptWithHide(tmpScript, timeoutSeconds)
    }

    private fun executeTmpScriptDirect(tmpScript: String, timeoutSeconds: Long): ScriptResult {
        val outputFile = "${tmpScript}.out"
        val result = runRootCommand(
            "sh '$tmpScript' > '$outputFile' 2>&1; EXIT=\$?; cat '$outputFile'; rm -f '$tmpScript' '$outputFile'; exit \$EXIT",
            timeoutSeconds
        )
        return ScriptResult(
            pid = null,
            exitCode = result.exitCode,
            stdout = clipOutput(result.output),
            stderr = "",
            processHidden = false
        )
    }

    private fun executeTmpScriptWithHide(tmpScript: String, timeoutSeconds: Long): ScriptResult {
        val outputFile = "${tmpScript}.out"
        val pidFile = "${tmpScript}.pid"

        runRootCommand("mkdir -p $HIDDEN_DIR && touch $HIDDEN_DIR/.empty")

        val execCmd = "nohup sh '$tmpScript' > '$outputFile' 2>&1 & PID=\$!; echo \$PID > '$pidFile'; echo \$PID"
        val execResult = runRootCommand(execCmd, timeoutSeconds = 10)
        val pid = execResult.output.trim().lines().lastOrNull()?.trim()?.toIntOrNull()

        synchronized(lock) { activePid = pid }

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

        val waitCmd = buildString {
            append("PID=\$(cat '$pidFile' 2>/dev/null); ")
            append("if [ -n \"\$PID\" ]; then ")
            append("  ELAPSED=0; ")
            append("  while [ \$ELAPSED -lt $timeoutSeconds ] && kill -0 \"\$PID\" 2>/dev/null; do sleep 1; ELAPSED=\$((ELAPSED + 1)); done; ")
            append("  if kill -0 \"\$PID\" 2>/dev/null; then kill -KILL \"\$PID\" 2>/dev/null; echo \"[TIMEOUT]\"; fi; ")
            append("fi; ")
            append("cat '$outputFile' 2>/dev/null")
        }
        val outputResult = runRootCommand(waitCmd, timeoutSeconds + 10)

        synchronized(lock) { activePid = null }

        val stdout = clipOutput(outputResult.output)
        val wasTimeout = stdout.contains("[TIMEOUT]")
        val actualExitCode = if (wasTimeout) -2 else outputResult.exitCode

        runRootCommand("rm -f '$tmpScript' '$outputFile' '$pidFile'")

        return ScriptResult(
            pid = pid,
            exitCode = actualExitCode,
            stdout = stdout,
            stderr = if (wasTimeout) "Script timed out after ${timeoutSeconds}s" else "",
            processHidden = processHidden,
            processHiddenMethod = hiddenMethod
        )
    }

    /**
     * Execute a script that requires interactive input (e.g. read/select prompts).
     * Responses are pre-fed via stdin pipe, suitable for scripts like MT Manager
     * menus that ask for choice 1/2/3/4.
     *
     * Example:
     *   executor.executeScriptWithResponses(
     *       scriptContent,
     *       listOf("1", "y")
     *   )
     */
    fun executeScriptWithResponses(
        content: String,
        responses: List<String>,
        hideProcess: Boolean = false,
        timeoutSeconds: Long = 120
    ): ScriptResult {
        if (content.isBlank()) {
            return ScriptResult(null, -1, "", "Script content is empty", false, null)
        }

        if (!ensureRootPermission()) {
            return ScriptResult(null, -1, "", "Root permission denied", false, null)
        }

        val tmpScript = "${SCRIPT_TMP_PREFIX}${System.currentTimeMillis()}.sh"
        val encoded = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        val writeResult = runRootCommand("echo '$encoded' | base64 -d > '$tmpScript' && chmod 700 '$tmpScript'")
        if (writeResult.exitCode != 0) {
            return ScriptResult(null, -1, "", "Failed to write script: ${writeResult.output}", false, null)
        }

        val result = suSession.executeWithStdin("sh '$tmpScript'", responses, timeoutSeconds)
        runRootCommand("rm -f '$tmpScript'")

        return ScriptResult(
            pid = null,
            exitCode = result.exitCode,
            stdout = clipOutput(result.output),
            stderr = "",
            processHidden = false
        )
    }

    fun executeScriptFileWithResponses(
        scriptPath: String,
        responses: List<String>,
        timeoutSeconds: Long = 120
    ): ScriptResult {
        val scriptFile = java.io.File(scriptPath)
        if (!scriptFile.exists() || !scriptFile.canRead()) {
            return ScriptResult(null, -1, "", "Script file not found or not readable: $scriptPath", false, null)
        }
        val content = scriptFile.readText()
        return executeScriptWithResponses(content, responses, false, timeoutSeconds)
    }

    /**
     * Execute a script with step-by-step interactive prompt matching.
     * Instead of pre-feeding all responses, this opens a persistent su session,
     * waits for each prompt match, and sends the corresponding response.
     *
     * Use this when script output depends on earlier choices
     * (e.g. nested menus where options change based on previous selections).
     */
    fun executeInteractiveScript(
        content: String,
        steps: List<InteractiveStep>,
        timeoutSeconds: Long = 120
    ): InteractiveResult {
        if (content.isBlank()) {
            return InteractiveResult(-1, "Script content is empty", emptyList(), emptyList())
        }

        if (!ensureRootPermission()) {
            return InteractiveResult(-1, "Root permission denied", emptyList(), emptyList())
        }

        val session = suSession.startInteractiveSession()
        if (!session.open()) {
            return InteractiveResult(-1, "Failed to open interactive session", emptyList(), emptyList())
        }

        synchronized(lock) { interactiveSession = session }

        val stepsCompleted = mutableListOf<String>()
        val stepsFailed = mutableListOf<String>()

        try {
            session.sendLine("sh << 'SCRIPT_EOF'")
            session.sendLine(content)
            session.sendLine("SCRIPT_EOF")

            for ((index, step) in steps.withIndex()) {
                val label = "step_${index + 1}"
                if (session.expectAndRespond(step.matchText, step.response, step.timeoutMs)) {
                    stepsCompleted.add(label)
                    Log.d(TAG, "Interactive step $label: matched '${step.matchText}' -> '${step.response}'")
                } else {
                    stepsFailed.add(label)
                    Log.w(TAG, "Interactive step $label: timeout waiting for '${step.matchText}'")
                }
            }

            val remaining = session.drainUntil("", 5000) ?: ""
            val fullOutput = session.getAllOutput()

            session.close()
            synchronized(lock) { interactiveSession = null }

            return InteractiveResult(
                exitCode = 0,
                finalOutput = clipOutput(fullOutput),
                stepsCompleted = stepsCompleted,
                stepsFailed = stepsFailed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Interactive script failed: ${e.message}")
            session.close()
            synchronized(lock) { interactiveSession = null }
            return InteractiveResult(-1, e.message ?: "exception", stepsCompleted, stepsFailed)
        }
    }

    fun cancelInteractiveSession() {
        synchronized(lock) {
            interactiveSession?.close()
            interactiveSession = null
        }
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
                if (fallbackOk) true to "mount_bind_fallback" else false to hideResult.reason
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
        return if (suSession.isSessionOpen()) suSession.checkPermission() else suSession.open(timeoutSeconds = 30)
    }

    fun ensureRootPermission(): Boolean {
        if (suSession.isSessionOpen() && suSession.checkPermission()) return true
        return suSession.open(timeoutSeconds = 30)
    }

    fun getActivePid(): Int? = activePid

    private fun clipOutput(output: String): String {
        return if (output.length > MAX_STDOUT_SIZE) output.take(MAX_STDOUT_SIZE) else output
    }

    private fun runRootCommand(command: String, timeoutSeconds: Long = 30): CommandResult {
        if (!suSession.isSessionOpen()) {
            suSession.open(timeoutSeconds = timeoutSeconds)
        }
        val result = suSession.execute(command, timeoutSeconds)
        return CommandResult(result.exitCode, result.output)
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    )
}
