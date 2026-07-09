package com.hanfeng.adblocker.shizuku

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SuSession {

    companion object {
        private const val TAG = "SuSession"
        private const val FIRST_CALL_TIMEOUT_SEC = 60L
        private const val NORMAL_TIMEOUT_SEC = 15L

        @Volatile
        private var instance: SuSession? = null

        fun getInstance(): SuSession {
            return instance ?: synchronized(this) {
                instance ?: SuSession().also { instance = it }
            }
        }
    }

    data class ShellResult(val exitCode: Int, val output: String)

    private val permissionGranted = AtomicBoolean(false)
    private val permissionDenied = AtomicBoolean(false)

    fun open(timeoutSeconds: Long = FIRST_CALL_TIMEOUT_SEC): Boolean {
        if (permissionGranted.get()) return true
        if (permissionDenied.get()) return false

        Log.d(TAG, "Requesting root permission (timeout=${timeoutSeconds}s)...")
        val result = runRaw("echo SU_READY && id", timeoutSeconds)

        return if (result.contains("SU_READY") && (result.contains("uid=0") || result.contains("uid=0(root)"))) {
            permissionGranted.set(true)
            Log.d(TAG, "Root permission granted")
            true
        } else {
            permissionDenied.set(true)
            Log.e(TAG, "Root permission denied/timed out. Output: [${result.take(200)}]")
            false
        }
    }

    fun execute(command: String, timeoutSeconds: Long = -1): ShellResult {
        val timeout = if (timeoutSeconds > 0) timeoutSeconds
        else if (!permissionGranted.get()) FIRST_CALL_TIMEOUT_SEC
        else NORMAL_TIMEOUT_SEC

        if (!permissionGranted.get() && !open(timeout)) {
            return ShellResult(-1, "su_permission_denied")
        }

        return runRawWithExit(command, timeout)
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
                            if (output.length < 256 * 1024) {
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
