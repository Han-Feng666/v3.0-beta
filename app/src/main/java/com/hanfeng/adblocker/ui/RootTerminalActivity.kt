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
import com.hanfeng.adblocker.shizuku.SuSession
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RootTerminalActivity : BaseActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText
    private lateinit var terminalScroll: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var tvPrompt: TextView

    private var suProcess: Process? = null
    private var stdin: java.io.OutputStream? = null
    private var stdoutReader: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private var isInteractive = false
    private val outputBuilder = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private val outputLock = Any()
    private var autoScrollEnabled = true
    private var initialScriptPath: String? = null
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var currentCmdProcess: Process? = null

    private companion object {
        private const val TMP_DIR = "/data/local/tmp"
        private const val SCRIPT_TIMEOUT_MIN = 10L
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
                    val escaped = scriptPath.replace("'", "'\\''")
                    runOnUiThread {
                        appendOutput("$ sh '$escaped'\n\n")
                    }
                    runStreamingScript(escaped)
                }

                runOnUiThread {
                    appendOutput("\n--- 进入交互模式 ---\n")
                }
                tryOpenInteractiveShell()

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

    private fun runStreamingScript(escapedPath: String) {
        try {
            val remotePath = copyScriptToTmp(escapedPath)
            val execPath = remotePath ?: escapedPath

            val process = ProcessBuilder("su", "-c", "sh '$execPath' 2>&1")
                .redirectErrorStream(true)
                .start()
            currentCmdProcess = process

            val readerThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    var n: Int
                    while (true) {
                        n = process.inputStream.read(buf)
                        if (n < 0) break
                        val raw = String(buf, 0, n)
                        val cleaned = stripAnsi(raw)
                        runOnUiThread { appendOutput(cleaned) }
                    }
                } catch (_: Exception) {}
            }
            readerThread.start()

            val completed = process.waitFor(SCRIPT_TIMEOUT_MIN * 60, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                runOnUiThread { appendOutput("\n[!] 脚本超时，已终止\n") }
            }
            readerThread.join(5000)

            val exitCode = if (completed) process.exitValue() else -1
            runOnUiThread {
                appendOutput("\n>>> 脚本执行完毕，退出码: $exitCode\n")
            }

            if (remotePath != null) {
                runShell("rm -f '$remotePath'", 5)
            }
            currentCmdProcess = null

        } catch (e: Exception) {
            runOnUiThread {
                appendOutput("[!] 脚本执行失败: ${e.message}\n")
            }
        }
    }

    private fun copyScriptToTmp(escapedPath: String): String? {
        val fileName = "hf_shell_${System.currentTimeMillis()}.sh"
        val remote = "$TMP_DIR/$fileName"
        val result = runShell(
            "sed 's/\\r$//' '$escapedPath' > '$remote' 2>/dev/null && chmod 755 '$remote' && test -f '$remote' && echo OK",
            15
        )
        return if (result.contains("OK")) remote else null
    }

    private fun tryOpenInteractiveShell() {
        try {
            val process = ProcessBuilder("su", "-c", "sh")
                .redirectErrorStream(true)
                .start()
            suProcess = process
            stdin = process.outputStream

            val initCmds = """
                export PS1='ROOTPROMPT'
                export TERM=dumb
                stty raw -echo 2>/dev/null || true
                echo 'SHELL_READY'
            """.trimIndent() + "\n"

            stdin!!.write(initCmds.toByteArray())
            stdin!!.flush()

            val reader = process.inputStream
            var readyReceived = false

            val readThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    var n: Int
                    while (isRunning.get() && isInteractive) {
                        n = reader.read(buf)
                        if (n < 0) break
                        val raw = String(buf, 0, n)
                        val cleaned = stripAnsi(raw)
                            .replace("SHELL_READY", "")
                            .replace("ROOTPROMPT", "")
                            .replace("\r", "")
                        if (!readyReceived && raw.contains("SHELL_READY")) {
                            readyReceived = true
                        }
                        if (cleaned.isNotBlank()) {
                            runOnUiThread {
                                appendOutput(cleaned)
                                appendOutput("root# ")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            stdoutReader = readThread
            readThread.start()

            Thread.sleep(1000)
            if (readyReceived && process.isAlive) {
                isInteractive = true
                runOnUiThread {
                    isRunning.set(true)
                }
                return
            }

            process.destroyForcibly()
            readThread.interrupt()
            isInteractive = false
            runOnUiThread {
                appendOutput("[!] 交互式 shell 无法启动，将使用逐条命令执行模式\n")
            }

        } catch (e: Exception) {
            isInteractive = false
            runOnUiThread {
                appendOutput("[!] 交互式模式启动失败: ${e.message}，将使用逐条命令执行模式\n")
            }
        }
    }

    private fun handleUserCommand(cmd: String) {
        if (!isRunning.get() && !isInteractive) return
        runOnUiThread { appendOutput("root# $cmd\n") }

        Thread {
            try {
                if (isInteractive && stdin != null && suProcess?.isAlive == true) {
                    stdin!!.write("$cmd\n".toByteArray())
                    stdin!!.flush()
                } else {
                    runOneShotWithOutput(cmd)
                }
            } catch (e: Exception) {
                runOnUiThread { appendOutput("[!] 执行失败: ${e.message}\nroot# ") }
            }
        }.start()
    }

    private fun runOneShotWithOutput(cmd: String) {
        try {
            val process = ProcessBuilder("su", "-c", "$cmd 2>&1")
                .redirectErrorStream(true)
                .start()
            currentCmdProcess = process

            val readerThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    var n: Int
                    while (true) {
                        n = process.inputStream.read(buf)
                        if (n < 0) break
                        val raw = String(buf, 0, n)
                        val cleaned = stripAnsi(raw)
                        runOnUiThread { appendOutput(cleaned) }
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
            currentCmdProcess = null
        } catch (e: Exception) {
            runOnUiThread { appendOutput("[!] 执行失败: ${e.message}\nroot# ") }
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
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            if (output.length < 64 * 1024) {
                                if (output.isNotEmpty()) output.append("\n")
                                output.append(line)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            readerThread.start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            readerThread.join(2000)
            output.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    override fun onDestroy() {
        isRunning.set(false)
        isInteractive = false
        try {
            stdin?.write("exit\n".toByteArray())
            stdin?.flush()
            stdin?.close()
        } catch (_: Exception) {}
        try {
            suProcess?.destroyForcibly()
            currentCmdProcess?.destroyForcibly()
            stdoutReader?.interrupt()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
