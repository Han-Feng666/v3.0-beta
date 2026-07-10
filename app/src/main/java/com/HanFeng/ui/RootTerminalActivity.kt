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
import com.HanFeng.adblocker.shizuku.SuSession
import java.io.File
import java.util.concurrent.TimeUnit
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
    private var currentProcess: Process? = null

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
                val text = terminalInput.text.toString().trimEnd()
                if (text.isNotEmpty()) {
                    if (commandHistory.isEmpty() || commandHistory.last() != text) {
                        commandHistory.add(text)
                        if (commandHistory.size > 100) commandHistory.removeAt(0)
                    }
                    historyIndex = commandHistory.size
                    handleUserCommand(text)
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

        startShell()
    }

    private fun startShell() {
        isRunning.set(true)
        Thread {
            try {
                val session = SuSession.getInstance()
                if (!session.isSessionOpen() && !session.open(60)) {
                    runOnUiThread {
                        appendOutput("\n[!] Root 权限未获取，请在弹窗中授权后重试\n")
                        tvStatus.text = "未授权"
                        terminalInput.isEnabled = false
                    }
                    isRunning.set(false)
                    return@Thread
                }

                runOnUiThread {
                    tvStatus.text = "已连接"
                    tvPrompt.text = "root# "
                    appendOutput("Root 终端已连接\n\n")
                }

                val scriptPath = initialScriptPath
                if (scriptPath != null) {
                    runScriptDirectly(scriptPath)
                }

                runOnUiThread {
                    appendOutput("\n--- 可输入命令继续操作 ---\n\nroot# ")
                }

            } catch (e: Exception) {
                runOnUiThread {
                    appendOutput("\n[!] 启动失败: ${e.message}\n")
                    tvStatus.text = "错误"
                    terminalInput.isEnabled = false
                }
                isRunning.set(false)
            }
        }.start()
    }

    private fun runScriptDirectly(scriptPath: String) {
        val escapedPath = scriptPath.replace("'", "'\\''")

        val remotePath = ensureScriptAccessible(escapedPath)
        if (remotePath == null) {
            runOnUiThread { appendOutput("[!] 无法读取脚本文件: $scriptPath\n") }
            return
        }

        val displayName = scriptPath.substringAfterLast('/')
        runOnUiThread {
            appendOutput(">>> 执行脚本: $displayName\n")
            appendOutput(">>> 路径: $remotePath\n\n")
        }

        val remoteEscaped = remotePath.replace("'", "'\\''")

        runOnUiThread { appendOutput("--- 开始执行 ---\n") }

        try {
            val process = ProcessBuilder("su", "-c", "sh '$remoteEscaped' 2>&1")
                .redirectErrorStream(true)
                .start()
            currentProcess = process

            val readerThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    var n: Int
                    while (process.isAlive) {
                        n = process.inputStream.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        val raw = String(buf, 0, n)
                        val cleaned = stripAnsi(raw).replace("\r", "")
                        if (cleaned.isNotEmpty()) {
                            runOnUiThread { appendOutput(cleaned) }
                        }
                    }
                } catch (_: Exception) {}
            }
            readerThread.start()

            val completed = process.waitFor(10, TimeUnit.MINUTES)
            if (!completed) {
                process.destroyForcibly()
                runOnUiThread { appendOutput("\n[!] 脚本超时，已终止\n") }
            }
            readerThread.join(5000)

            val exitCode = if (completed) process.exitValue() else -1
            runOnUiThread {
                appendOutput("\n--- 执行完毕，退出码: $exitCode ---\n")
            }

            if (remotePath != scriptPath) {
                runShellNoOutput("rm -f '$remoteEscaped'", 5)
            }
            currentProcess = null

        } catch (e: Exception) {
            runOnUiThread {
                appendOutput("[!] 脚本执行失败: ${e.message}\n")
            }
        }
    }

    private fun ensureScriptAccessible(escapedPath: String): String? {
        val checkResult = runShell("test -f '$escapedPath' && echo EXIST || echo NOTFOUND", 5)
        if (!checkResult.contains("EXIST")) {
            return null
        }

        val remote = "$TMP_DIR/hf_shell_${System.currentTimeMillis()}.sh"
        val remoteEscaped = remote.replace("'", "'\\''")

        val copyCmd = "cp '$escapedPath' '$remoteEscaped' 2>/dev/null && chmod 755 '$remoteEscaped' && test -s '$remoteEscaped' && echo OK"
        var result = runShell(copyCmd, 10)

        if (!result.contains("OK")) {
            val sedCmd = "sed 's/\\r\$//' '$escapedPath' > '$remoteEscaped' 2>/dev/null && chmod 755 '$remoteEscaped' && test -s '$remoteEscaped' && echo OK"
            result = runShell(sedCmd, 10)
        }

        return if (result.contains("OK")) remote else null
    }

    private fun handleUserCommand(cmd: String) {
        if (!isRunning.get()) return

        runOnUiThread { appendOutput("root# $cmd\n") }

        Thread {
            runOneShotCommand(cmd)
        }.start()
    }

    private fun runOneShotCommand(cmd: String) {
        try {
            val escapedCmd = cmd.replace("'", "'\\''")
            val process = ProcessBuilder("su", "-c", "$escapedCmd 2>&1")
                .redirectErrorStream(true)
                .start()
            currentProcess = process

            val readerThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    var n: Int
                    while (process.isAlive) {
                        n = process.inputStream.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        val raw = String(buf, 0, n)
                        val cleaned = stripAnsi(raw).replace("\r", "")
                        if (cleaned.isNotEmpty()) {
                            runOnUiThread { appendOutput(cleaned) }
                        }
                    }
                } catch (_: Exception) {}
            }
            readerThread.start()

            val completed = process.waitFor(30, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                runOnUiThread { appendOutput("[!] 命令超时\n") }
            }
            readerThread.join(3000)

            runOnUiThread { appendOutput("root# ") }
            currentProcess = null
        } catch (e: Exception) {
            runOnUiThread {
                appendOutput("[!] 执行失败: ${e.message}\nroot# ")
            }
        }
    }

    private fun clearScreen() {
        synchronized(outputLock) { outputBuilder.clear() }
        handler.post {
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
            terminalOutput.text = outputBuilder.toString()
            if (autoScrollEnabled) {
                terminalScroll.post {
                    terminalScroll.fullScroll(View.FOCUS_DOWN)
                }
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

    private fun runShell(command: String, timeoutSeconds: Long): String {
        return try {
            val result = SuSession.getInstance().execute(command, timeoutSeconds)
            result.output.trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun runShellNoOutput(command: String, timeoutSeconds: Long) {
        try {
            SuSession.getInstance().execute(command, timeoutSeconds)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isRunning.set(false)
        try {
            currentProcess?.destroyForcibly()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
