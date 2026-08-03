package com.HanFeng.adblocker.shizuku

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SuSession {

    companion object {
        private const val TAG = "SuSession"
        private const val FIRST_CALL_TIMEOUT_SEC = 60L
        private const val NORMAL_TIMEOUT_SEC = 15L
        private const val MAX_COMMAND_LOG = 200

        @Volatile
        private var instance: SuSession? = null

        fun getInstance(): SuSession {
            return instance ?: synchronized(this) {
                instance ?: SuSession().also { instance = it }
            }
        }
    }

    data class ShellResult(val exitCode: Int, val output: String)

    data class CommandLogEntry(
        val timestamp: Long,
        val command: String,
        val exitCode: Int,
        val truncated: Boolean,
        val durationMs: Long
    )

    enum class RootSolution {
        MAGISK, KERNELSU, APATCH, UNKNOWN_ROOT, NOT_ROOTED
    }

    private val permissionGranted = AtomicBoolean(false)
    private val permissionDenied = AtomicBoolean(false)
    private val commandLog = CopyOnWriteArrayList<CommandLogEntry>()
    private val totalCommands = AtomicLong(0)
    private val totalFailures = AtomicLong(0)

    @Volatile
    var rootSolution: RootSolution = RootSolution.NOT_ROOTED
        private set

    @Volatile
    var rootVersion: String = ""
        private set

    fun open(timeoutSeconds: Long = FIRST_CALL_TIMEOUT_SEC): Boolean {
        if (permissionGranted.get()) return true
        // 不再因 permissionDenied 永久拒绝后续重试: 让用户每次操作都有机会重新授权
        permissionDenied.set(false)

        Log.d(TAG, "Requesting root permission (timeout=${timeoutSeconds}s)...")
        val result = runRawInternal("echo SU_READY && id", timeoutSeconds)

        return if (result.contains("SU_READY") && (result.contains("uid=0") || result.contains("uid=0(root)"))) {
            permissionGranted.set(true)
            Log.d(TAG, "Root permission granted")
            detectRootSolution()
            true
        } else {
            permissionDenied.set(true)
            Log.e(TAG, "Root permission denied/timed out. Output: [${result.take(200)}]")
            false
        }
    }

    private fun detectRootSolution() {
        try {
            val magiskResult = runRawInternal("magisk -c", 5)
            if (magiskResult.isNotBlank() && !magiskResult.contains("not found")) {
                rootSolution = RootSolution.MAGISK
                rootVersion = magiskResult.trim()
                Log.d(TAG, "Detected Magisk: $rootVersion")
                return
            }
        } catch (_: Exception) {}

        try {
            val ksuResult = runRawInternal("ksud -v", 5)
            if (ksuResult.isNotBlank() && !ksuResult.contains("not found")) {
                rootSolution = RootSolution.KERNELSU
                rootVersion = ksuResult.trim()
                Log.d(TAG, "Detected KernelSU: $rootVersion")
                return
            }
        } catch (_: Exception) {}

        try {
            if (runRawInternal("test -f /data/adb/ap/bin/apd", 3).contains("ap/bin/apd")) {
                rootSolution = RootSolution.APATCH
                rootVersion = "APatch"
                Log.d(TAG, "Detected APatch")
                return
            }
        } catch (_: Exception) {}

        rootSolution = RootSolution.UNKNOWN_ROOT
        rootVersion = "Unknown"
    }

    fun execute(command: String, timeoutSeconds: Long = -1): ShellResult {
        val timeout = if (timeoutSeconds > 0) timeoutSeconds
        else if (!permissionGranted.get()) FIRST_CALL_TIMEOUT_SEC
        else NORMAL_TIMEOUT_SEC

        if (!permissionGranted.get() && !open(timeout)) {
            val result = ShellResult(-1, "su_permission_denied")
            logCommand(command, result, timeout)
            return result
        }

        val startTime = System.currentTimeMillis()
        val result = runRawWithExit(command, timeout)
        val duration = System.currentTimeMillis() - startTime
        logCommand(command, result, duration)
        return result
    }

    fun executeBypassDenied(command: String, timeoutSeconds: Long = -1): ShellResult {
        if (isPermissionDenied()) {
            close()
        }
        return execute(command, timeoutSeconds)
    }

    fun copyFile(from: String, to: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("cp -f '$from' '$to' && chmod 644 '$to'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun copyFileWithMode(from: String, to: String, mode: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("cp -f '$from' '$to' && chmod $mode '$to' && chown root:root '$to'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun deleteFile(path: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("rm -f '$path'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun deleteDir(path: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("rm -rf '$path'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun fileExists(path: String, timeoutSeconds: Long = 5): Boolean {
        return execute("test -e '$path' && echo EXISTS || echo NOT_FOUND", timeoutSeconds)
            .output.contains("EXISTS")
    }

    fun mountOverlay(source: String, target: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("mount --bind '$source' '$target'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun unmountOverlay(target: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("umount '$target' 2>/dev/null || umount -l '$target' 2>/dev/null", timeoutSeconds)
        return result.exitCode == 0
    }

    fun getProp(prop: String): String {
        return execute("getprop $prop", 5).output.trim()
    }

    fun checkPermission(): Boolean {
        if (permissionGranted.get()) return true
        if (permissionDenied.get()) return false
        return open(15)
    }

    fun isSessionOpen(): Boolean = permissionGranted.get()
    fun isPermissionDenied(): Boolean = permissionDenied.get()

    fun waitForSession(timeoutSeconds: Long = 30): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (permissionGranted.get() || permissionDenied.get()) break
            Thread.sleep(300)
        }
        return permissionGranted.get()
    }

    fun close() {
        permissionGranted.set(false)
        permissionDenied.set(false)
    }

    fun getCommandLog(): List<CommandLogEntry> = commandLog.toList()
    fun getTotalCommands(): Long = totalCommands.get()
    fun getTotalFailures(): Long = totalFailures.get()
    fun clearCommandLog() = commandLog.clear()

    fun resetStats() {
        totalCommands.set(0)
        totalFailures.set(0)
    }

    fun escapeShell(str: String): String {
        return str.replace("'", "'\\''")
    }

    fun listDirectory(dir: String, timeoutSeconds: Long = 10): List<String> {
        val result = execute("ls -1 '$dir' 2>/dev/null", timeoutSeconds)
        return result.output.lines().filter { it.isNotBlank() }
    }

    private fun logCommand(command: String, result: ShellResult, durationMs: Long) {
        totalCommands.incrementAndGet()
        if (result.exitCode != 0) {
            totalFailures.incrementAndGet()
        }
        val entry = CommandLogEntry(
            timestamp = System.currentTimeMillis(),
            command = command.take(200),
            exitCode = result.exitCode,
            truncated = command.length > 200,
            durationMs = durationMs
        )
        commandLog.add(entry)
        while (commandLog.size > MAX_COMMAND_LOG) {
            commandLog.removeAt(0)
        }
    }

    fun executeWithStdin(command: String, stdinLines: List<String>, timeoutSeconds: Long): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val stdin = process.outputStream.bufferedWriter()
            for (line in stdinLines) {
                stdin.write(line)
                stdin.newLine()
            }
            stdin.flush()
            stdin.close()

            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            var completed = false
            val readerThread = Thread {
                try {
                    reader.use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            if (output.length < 4 * 1024 * 1024) {
                                if (output.isNotEmpty()) output.append("\n")
                                output.append(line)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            readerThread.start()

            try {
                completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } finally {
                if (!completed) {
                    process.destroyForcibly()
                }
                readerThread.join(3000)
            }

            if (!completed) ShellResult(-1, output.toString())
            else ShellResult(process.exitValue(), output.toString())
        } catch (e: Exception) {
            Log.e(TAG, "executeWithStdin failed: ${e.message}")
            ShellResult(-1, e.message ?: "exception")
        }
    }

    fun startInteractiveSession(): InteractiveSession {
        return InteractiveSession()
    }

    inner class InteractiveSession {
        private var process: Process? = null
        private var stdin: BufferedWriter? = null
        private val stdoutQueue = LinkedBlockingQueue<String>()
        private val alive = AtomicBoolean(false)
        private val PS1_MARKER = "__SUSH_READY__"
        private val allOutput = StringBuilder()

        fun open(): Boolean {
            return try {
                process = ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start()
                stdin = process!!.outputStream.bufferedWriter()
                alive.set(true)

                Thread {
                    try {
                        process!!.inputStream.bufferedReader().use { reader ->
                            var line: String? = null
                            while (alive.get() && reader.readLine().also { line = it } != null) {
                                val l = line!!
                                synchronized(allOutput) {
                                    if (allOutput.length < 4 * 1024 * 1024) {
                                        if (allOutput.isNotEmpty()) allOutput.append("\n")
                                        allOutput.append(l)
                                    }
                                }
                                stdoutQueue.put(l)
                            }
                        }
                    } catch (_: Exception) {}
                }.start()

                sendLine("export PS1='$PS1_MARKER'")
                sendLine("echo $PS1_MARKER")
                waitForMarker(5000)
                true
            } catch (e: Exception) {
                Log.e(TAG, "InteractiveSession open failed: ${e.message}")
                false
            }
        }

        fun sendLine(line: String) {
            try {
                stdin?.let {
                    it.write(line)
                    it.newLine()
                    it.flush()
                }
            } catch (_: Exception) {}
        }

        fun sendInput(input: String) {
            try {
                stdin?.let {
                    it.write(input)
                    it.flush()
                }
            } catch (_: Exception) {}
        }

        fun readNextLine(timeoutMs: Long): String? {
            return try {
                stdoutQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
        }

        fun waitFor(matchText: String, timeoutMs: Long): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            val captured = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                val line = try {
                    stdoutQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { null }

                if (line == null) {
                    if (!alive.get()) return null
                    continue
                }
                if (captured.isNotEmpty()) captured.append("\n")
                captured.append(line)
                if (line.contains(matchText)) return captured.toString()
            }
            return null
        }

        fun drainUntil(matchText: String, timeoutMs: Long): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            val captured = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                val line = try {
                    stdoutQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { null }

                if (line == null) {
                    if (!alive.get()) return null
                    continue
                }
                if (captured.isNotEmpty()) captured.append("\n")
                captured.append(line)
                if (line == matchText || line.contains(matchText)) return captured.toString()
            }
            return if (captured.isNotEmpty()) captured.toString() else null
        }

        fun waitForMarker(timeoutMs: Long): Boolean {
            return waitFor(PS1_MARKER, timeoutMs) != null
        }

        fun expectAndRespond(expectedText: String, response: String, timeoutMs: Long): Boolean {
            val found = waitFor(expectedText, timeoutMs)
            if (found != null) {
                sendLine(response)
                return true
            }
            return false
        }

        fun getAllOutput(): String {
            synchronized(allOutput) {
                return allOutput.toString()
            }
        }

        fun isAlive(): Boolean = alive.get()

        fun close() {
            alive.set(false)
            try { sendLine("exit") } catch (_: Exception) {}
            try { stdin?.close() } catch (_: Exception) {}
            try { process?.destroy() } catch (_: Exception) {}
            stdoutQueue.clear()
        }
    }

    private fun runRawInternal(command: String, timeoutSeconds: Long): String {
        return runRawWithExit(command, timeoutSeconds).output
    }

    private fun runRaw(command: String, timeoutSeconds: Long): String {
        return runRawWithExit(command, timeoutSeconds).output
    }

    private fun runRawWithExit(command: String, timeoutSeconds: Long): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            var completed = false
            val readerThread = Thread {
                try {
                    reader.use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            if (output.length < 4 * 1024 * 1024) {
                                if (output.isNotEmpty()) output.append("\n")
                                output.append(line)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            readerThread.start()

            try {
                completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } finally {
                if (!completed) {
                    process.destroyForcibly()
                }
                readerThread.join(3000)
            }

            val result = output.toString()
            if (!completed) {
                Log.w(TAG, "su command timed out after ${timeoutSeconds}s")
                return ShellResult(-1, result)
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Log.w(TAG, "su exit code=$exitCode cmd=${command.take(80)}")
            }
            ShellResult(exitCode, result)
        } catch (e: Exception) {
            Log.e(TAG, "su execute failed: ${e.message}")
            ShellResult(-1, e.message ?: "exception")
        }
    }
}
