package com.HanFeng.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.HanFeng.R
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class RootTerminalActivity : BaseActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText
    private lateinit var terminalScroll: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var tvPrompt: TextView

    private val isRunning = AtomicBoolean(false)
    private val outputBuilder = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private val outputLock = Any()
    private var autoScrollEnabled = true
    private var initialScriptPath: String? = null
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var shellProcess: Process? = null
    private var shellStdin: OutputStream? = null

    private val pendingMarkers = ArrayDeque<MarkerWaiter>()
    private val markerLock = Any()

    private data class MarkerWaiter(val marker: String, val captured: StringBuilder, val done: java.util.concurrent.CountDownLatch)

    private companion object {
        private const val TMP_DIR = "/data/local/tmp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_root_terminal)

        terminalOutput = findViewById(R.id.terminalOutput)
        terminalInput = findViewById(R.id.terminalInput)
        terminalScroll = findViewById(R.id.terminalScroll)
        tvStatus = findViewById(R.id.tvTerminalStatus)
        tvPrompt = findViewById(R.id.tvPrompt)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        terminalOutput.movementMethod = ScrollingMovementMethod()
        initialScriptPath = intent.getStringExtra("script_path")

        terminalScroll.viewTreeObserver.addOnScrollChangedListener {
            val child = terminalScroll.getChildAt(0) ?: return@addOnScrollChangedListener
            val scrollY = terminalScroll.scrollY
            val childHeight = child.height
            val scrollViewHeight = terminalScroll.height
            autoScrollEnabled = scrollY + scrollViewHeight >= childHeight - 48
        }

        terminalInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val text = terminalInput.text.toString().trimEnd('\r', '\n')
                if (text.isNotEmpty()) {
                    if (commandHistory.isEmpty() || commandHistory.last() != text) {
                        commandHistory.add(text)
                        if (commandHistory.size > 100) commandHistory.removeAt(0)
                    }
                    historyIndex = commandHistory.size
                    sendCommandToShell(text)
                    terminalInput.text?.clear()
                }
                true
            } else false
        }

        terminalInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                if (historyIndex > 0) {
                    historyIndex--
                    terminalInput.setText(commandHistory.getOrNull(historyIndex) ?: "")
                    terminalInput.setSelection(terminalInput.text.length)
                }
                true
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                if (historyIndex < commandHistory.size - 1) {
                    historyIndex++
                    terminalInput.setText(commandHistory.getOrNull(historyIndex) ?: "")
                    terminalInput.setSelection(terminalInput.text.length)
                } else {
                    historyIndex = commandHistory.size
                    terminalInput.text?.clear()
                }
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.terminalTitleBar).setOnLongClickListener {
            clearScreen()
            true
        }

        startInteractiveShell()
    }

    private fun startInteractiveShell() {
        isRunning.set(true)
        Thread {
            try {
                val proc = ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start()
                shellProcess = proc
                shellStdin = proc.outputStream
                runOnUiThreadSafe {
                    appendOutput("正在请求 Root 权限...\n")
                }

                val readerThread = Thread {
                    try {
                        val input = proc.inputStream
                        val buf = ByteArray(4096)
                        var n: Int
                        while (proc.isAlive) {
                            n = try {
                                input.read(buf)
                            } catch (_: IOException) {
                                break
                            }
                            if (n < 0) break
                            if (n == 0) continue
                            val raw = String(buf, 0, n)
                            processShellOutput(raw)
                        }
                        drainRemainingMarkers()
                        runOnUiThreadSafe {
                            appendOutput("\n[Shell 已退出]\n")
                            tvStatus.text = "已断开"
                            tvPrompt.text = ""
                            terminalInput.isEnabled = false
                        }
                        isRunning.set(false)
                    } catch (_: Exception) {
                        isRunning.set(false)
                    }
                }
                readerThread.isDaemon = true
                readerThread.start()

                if (initialScriptPath == null) {
                    Thread.sleep(600)
                }
                runOnUiThreadSafe {
                    tvStatus.text = "已连接"
                    tvPrompt.text = "root# "
                    appendOutput("Root 终端已连接（交互模式，支持需要输入的脚本）\n")
                    val scriptPath = initialScriptPath
                    if (scriptPath != null) {
                        executeScriptInShell(scriptPath)
                    } else {
                        appendOutput("--- 直接输入命令，回车执行 ---\nroot# ")
                    }
                }
            } catch (e: Exception) {
                runOnUiThreadSafe {
                    appendOutput("\n[!] 启动失败: ${e.message}\n")
                    tvStatus.text = "未授权"
                    terminalInput.isEnabled = false
                }
                isRunning.set(false)
            }
        }.start()
    }

    private val outputBuffer = StringBuilder()

    @Synchronized
    private fun processShellOutput(raw: String) {
        outputBuffer.append(raw)
        var consumed = 0
        while (true) {
            val markerStart = findEarliestMarkerIndex(outputBuffer, consumed)
            if (markerStart < 0) break
            val before = outputBuffer.substring(consumed, markerStart)
            if (before.isNotEmpty()) {
                runOnUiThreadSafe { appendOutput(cleanOutput(before)) }
            }
            val markerName = readMarkerName(outputBuffer, markerStart)
            if (markerName == null) {
                break
            }
            val markerEnd = outputBuffer.indexOf(markerName, markerStart + markerName.length)
            if (markerEnd < 0) {
                break
            }
            val contentStart = markerStart + markerName.length + 1
            val payload = outputBuffer.substring(contentStart, markerEnd)
            deliverMarkerResult(markerName, payload)
            consumed = markerEnd + markerName.length
            outputBuffer.delete(0, consumed)
            consumed = 0
        }
        if (consumed > 0) {
            outputBuffer.delete(0, consumed)
        }
        if (outputBuffer.length > 65536) {
            val flushed = outputBuffer.toString()
            outputBuffer.setLength(0)
            runOnUiThreadSafe { appendOutput(cleanOutput(flushed)) }
        }
    }

    private fun findEarliestMarkerIndex(sb: StringBuilder, from: Int): Int {
        var earliest = -1
        synchronized(markerLock) {
            for (w in pendingMarkers) {
                val idx = sb.indexOf(w.marker, from)
                if (idx >= 0 && (earliest < 0 || idx < earliest)) {
                    earliest = idx
                }
            }
        }
        return earliest
    }

    private fun readMarkerName(sb: StringBuilder, start: Int): String? {
        synchronized(markerLock) {
            for (w in pendingMarkers) {
                if (sb.regionMatches(start, w.marker, 0, w.marker.length)) {
                    return w.marker
                }
            }
        }
        return null
    }

    private fun deliverMarkerResult(marker: String, payload: String) {
        synchronized(markerLock) {
            val it = pendingMarkers.iterator()
            while (it.hasNext()) {
                val w = it.next()
                if (w.marker == marker) {
                    w.captured.append(payload)
                    w.done.countDown()
                    it.remove()
                    return
                }
            }
        }
    }

    private fun drainRemainingMarkers() {
        synchronized(markerLock) {
            for (w in pendingMarkers) {
                w.done.countDown()
            }
            pendingMarkers.clear()
        }
    }

    private fun executeScriptInShell(scriptPath: String) {
        if (!isRunning.get()) return
        val displayName = scriptPath.substringAfterLast('/')
        runOnUiThreadSafe {
            appendOutput(">>> 执行脚本: $displayName\n")
            appendOutput(">>> 路径: $scriptPath\n\n--- 开始执行 ---\n")
        }
        writeRaw("rm -f '$TMP_DIR'/hf_shell_*.sh 2>/dev/null\n")
        runShellBlocking("rm -f '$TMP_DIR'/hf_shell_*.sh 2>/dev/null", 3)
        val remotePath = ensureScriptAccessible(scriptPath)
        if (remotePath == null) {
            runOnUiThreadSafe { appendOutput("[!] 无法读取脚本文件: $scriptPath\nroot# ") }
            return
        }
        val remoteEscaped = remotePath.replace("'", "'\\''")
        writeRaw("sh '$remoteEscaped'\necho \"[HF_SCRIPT_DONE=\$?]\"\n")
    }

    private fun ensureScriptAccessible(scriptPath: String): String? {
        val escapedPath = scriptPath.replace("'", "'\\''")
        if (!runShellBlocking("test -f '$escapedPath' && echo OK || echo NO", 5).contains("OK")) {
            return null
        }
        val remote = "$TMP_DIR/hf_shell_${System.currentTimeMillis()}.sh"
        val remoteEscaped = remote.replace("'", "'\\''")
        if (runShellBlocking("cp '$escapedPath' '$remoteEscaped' 2>/dev/null && chmod 755 '$remoteEscaped' && test -s '$remoteEscaped' && echo OK", 10).contains("OK")) {
            return remote
        }
        if (runShellBlocking("sed 's/\r\$//' '$escapedPath' > '$remoteEscaped' 2>/dev/null && chmod 755 '$remoteEscaped' && test -s '$remoteEscaped' && echo OK", 10).contains("OK")) {
            return remote
        }
        return null
    }

    private fun sendCommandToShell(cmd: String) {
        if (!isRunning.get()) return
        runOnUiThreadSafe { appendOutput("root# $cmd\n") }
        writeRaw("$cmd\n")
    }

    @Synchronized
    private fun writeRaw(data: String) {
        try {
            val stdin = shellStdin ?: return
            stdin.write(data.toByteArray())
            stdin.flush()
        } catch (_: Exception) {
            isRunning.set(false)
        }
    }

    private fun runShellBlocking(command: String, timeoutSeconds: Long): String {
        val marker = "HF_BLOCK_${System.currentTimeMillis()}_${Thread.currentThread().id}"
        val captured = StringBuilder()
        val latch = java.util.concurrent.CountDownLatch(1)
        val waiter = MarkerWaiter(marker, captured, latch)
        synchronized(markerLock) {
            pendingMarkers.addLast(waiter)
        }
        val markerCmd = "echo -n '${marker}:'; ($command) 2>/dev/null; echo -n '${marker}'\n"
        writeRaw(markerCmd)
        try {
            latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}
        synchronized(markerLock) {
            pendingMarkers.remove(waiter)
        }
        return captured.toString().replace("\r", "")
    }

    private fun cleanOutput(text: String): String {
        var cleaned = stripAnsi(text).replace("\r", "")
        cleaned = cleaned.replace(Regex("HF_BLOCK_\\d+_\\d+:?HF_BLOCK_\\d*_?\\d*"), "")
        cleaned = cleaned.replace(Regex("\\[HF_SCRIPT_DONE=-?\\d+\\]"), "")
        return cleaned
    }

    private fun clearScreen() {
        synchronized(outputLock) { outputBuilder.clear() }
        handler.post {
            if (isFinishing || isDestroyed) return@post
            terminalOutput.text = ""
            terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_UP) }
        }
    }

    private fun appendOutput(text: String) {
        synchronized(outputLock) {
            if (outputBuilder.length > 256 * 1024) {
                val half = outputBuilder.length / 2
                val tail = outputBuilder.substring(half)
                outputBuilder.clear()
                outputBuilder.append("--- 缓冲区已截断 ---\n")
                outputBuilder.append(tail)
            }
            outputBuilder.append(text)
        }
        handler.post {
            if (isFinishing || isDestroyed) return@post
            terminalOutput.text = outputBuilder.toString()
            if (autoScrollEnabled) {
                terminalScroll.post {
                    terminalScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun runOnUiThreadSafe(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!isFinishing && !isDestroyed) action()
        } else {
            handler.post {
                if (!isFinishing && !isDestroyed) action()
            }
        }
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z?]"), "")
            .replace(Regex("\u001B\\].*?\u0007"), "")
            .replace("\u001B[=]", "")
            .replace("\u001B[>]", "")
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")
    }

    override fun onDestroy() {
        isRunning.set(false)
        try {
            shellStdin?.close()
        } catch (_: Exception) {}
        try {
            shellProcess?.destroyForcibly()
        } catch (_: Exception) {}
        drainRemainingMarkers()
        super.onDestroy()
    }
}
