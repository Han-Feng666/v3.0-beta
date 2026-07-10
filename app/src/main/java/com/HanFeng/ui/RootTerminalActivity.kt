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

                if (!tryOpenInteractiveShell()) {
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
                    runScriptInInteractiveShell(scriptPath)
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

    private fun tryOpenInteractiveShell(): Boolean {
        for (cmd in listOf("su", "su -c sh")) {
            if (tryOpenInteractive(cmd)) return true
        }
        runOnUiThread {
            appendOutput("[!] 交互式 shell 无法启动\n")
            tvStatus.text = "错误"
            terminalInput.isEnabled = false
        }
        return false
    }

    private fun tryOpenInteractive(suCmd: String): Boolean {
        return try {
            val cmdParts = suCmd.split(" ").toTypedArray()
            val process = ProcessBuilder(*cmdParts)
                .redirectErrorStream(true)
                .start()
            suProcess = process
            stdin = process.outputStream

            val initCmds = buildString {
                append("export PS1='ROOTPROMPT'\n")
                append("export TERM=dumb\n")
                append("export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/data/adb/ksu/bin:/data/adb/magisk:\$PATH\n")
                append("echo 'SHELL_READY'\n")
            }

            stdin!!.write(initCmds.toByteArray())
            stdin!!.flush()

            val reader = process.inputStream
            val readyReceived = AtomicBoolean(false)

            val readThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    var n: Int
                    while (isRunning.get() && process.isAlive) {
                        n = reader.read(buf)
                        if (n < 0) break
                        val raw = String(buf, 0, n)
                        val cleaned = stripAnsi(raw)
                            .replace("SHELL_READY", "")
                            .replace("ROOTPROMPT", "")
                            .replace("\r", "")
                        if (!readyReceived.get() && raw.contains("SHELL_READY")) {
                            readyReceived.set(true)
                        }
                        if (cleaned.isNotBlank()) {
                            runOnUiThread {
                                appendOutput(cleaned)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            stdoutReader = readThread
            readThread.start()

            Thread.sleep(1500)
            if (readyReceived.get() && process.isAlive) {
                isInteractive = true
                runOnUiThread {
                    isRunning.set(true)
                }
                return true
            }

            process.destroyForcibly()
            readThread.interrupt()
            false
        } catch (_: Exception) { false }
    }

    private fun runScriptInInteractiveShell(scriptPath: String) {
        val realPath = resolveAccessiblePath(scriptPath)
        if (realPath == null) {
            runOnUiThread { appendOutput("[!] 无法读取脚本文件: $scriptPath\n") }
            return
        }

        val displayName = realPath.substringAfterLast('/')
        runOnUiThread { appendOutput(">>> 执行脚本: $displayName\n\n") }

        val cmd = buildString {
            append("echo '--- 开始执行 ---'\n")
            append("sh '").append(realPath).append("'\n")
            append("echo '--- 执行完毕，退出码: $? ---'\n")
        }

        try {
            stdin!!.write(cmd.toByteArray())
            stdin!!.flush()
        } catch (e: Exception) {
            runOnUiThread { appendOutput("[!] 发送脚本执行命令失败: ${e.message}\n") }
        }
    }

    private fun resolveAccessiblePath(scriptPath: String): String? {
        try {
            if (File(scriptPath).canRead()) {
                return scriptPath
            }
        } catch (_: Exception) {}

        val remote = copyScriptToTmp(scriptPath)
        return remote
    }

    private fun copyScriptToTmp(escapedPath: String): String? {
        val fileName = "hf_shell_${System.currentTimeMillis()}.sh"
        val remote = "$TMP_DIR/$fileName"

        val copyCmd = buildString {
            append("TMP='$remote'\n")
            append("if [ -f '$escapedPath' ]; then\n")
            append("  if file '$escapedPath' 2>/dev/null | grep -qi ascii; then\n")
            append("    sed 's/\\r\$//' '$escapedPath' > \"\$TMP\"\n")
            append("  else\n")
            append("    cp '$escapedPath' \"\$TMP\"\n")
            append("  fi\n")
            append("  chmod 755 \"\$TMP\"\n")
            append("  test -f \"\$TMP\" && echo OK\n")
            append("else\n")
            append("  echo FAIL\n")
            append("fi\n")
        }

        val result = runShell(copyCmd, 15)
        return if (result.contains("OK")) remote else null
    }

    private fun handleUserCommand(cmd: String) {
        if (!isInteractive || !isRunning.get()) return
        if (stdin == null || suProcess?.isAlive != true) return

        runOnUiThread { appendOutput("root# $cmd\n") }

        Thread {
            try {
                stdin!!.write("$cmd\n".toByteArray())
                stdin!!.flush()
            } catch (e: Exception) {
                runOnUiThread { appendOutput("[!] 执行失败: ${e.message}\nroot# ") }
            }
        }.start()
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
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
            output.trim()
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
            stdoutReader?.interrupt()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
