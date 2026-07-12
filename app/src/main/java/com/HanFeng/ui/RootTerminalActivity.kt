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
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class RootTerminalActivity : BaseActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText
    private lateinit var terminalScroll: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var tvPrompt: TextView

    private val isRunning = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var autoScrollEnabled = true
    private var initialScriptPath: String? = null
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var shellProcess: Process? = null
    private var shellStdin: OutputStream? = null

    private val outputLock = Any()
    private val outputBuilder = StringBuilder()

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
                    sendCommand(text)
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
            synchronized(outputLock) { outputBuilder.clear() }
            terminalOutput.text = ""
            true
        }

        startShell()
    }

    private fun appendOutput(text: String) {
        synchronized(outputLock) {
            outputBuilder.append(text)
            if (outputBuilder.length > 256 * 1024) {
                outputBuilder.delete(0, outputBuilder.length - 256 * 1024)
            }
            terminalOutput.text = outputBuilder.toString()
            if (autoScrollEnabled) {
                terminalScroll.post { terminalScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun startShell() {
        isRunning.set(true)
        appendOutput("正在连接 Root 终端...\n")
        Thread {
            try {
                val proc = ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start()
                shellProcess = proc
                shellStdin = proc.outputStream

                Thread {
                    try {
                        val input = proc.inputStream
                        val buf = ByteArray(4096)
                        var n: Int
                        while (true) {
                            n = try {
                                input.read(buf)
                            } catch (_: Exception) {
                                break
                            }
                            if (n < 0) break
                            if (n == 0) continue
                            val raw = String(buf, 0, n)
                            handler.post { appendOutput(raw) }
                        }
                        handler.post {
                            appendOutput("\n[Shell 已退出]\n")
                            tvStatus.text = "已断开"
                            tvPrompt.text = ""
                            terminalInput.isEnabled = false
                        }
                        isRunning.set(false)
                    } catch (_: Exception) {
                        isRunning.set(false)
                    }
                }.also { it.isDaemon = true }.start()

                val scriptPath = initialScriptPath
                if (scriptPath != null) {
                    val escaped = scriptPath.replace("'", "'\\''")
                    handler.post {
                        tvStatus.text = "已连接"
                        tvPrompt.text = "# "
                        terminalInput.isEnabled = true
                        appendOutput("Root 终端已连接，正在执行脚本...\n\n")
                    }
                    writeRaw("sh '$escaped'\n")
                } else {
                    handler.post {
                        tvStatus.text = "已连接"
                        tvPrompt.text = "# "
                        terminalInput.isEnabled = true
                        appendOutput("Root 终端已连接\n# ")
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    appendOutput("\n[!] 启动失败: ${e.message}\n")
                    tvStatus.text = "未授权"
                    terminalInput.isEnabled = false
                }
                isRunning.set(false)
            }
        }.start()
    }

    private fun sendCommand(cmd: String) {
        appendOutput("# $cmd\n")
        writeRaw("$cmd\n")
    }

    private fun writeRaw(data: String) {
        try {
            val stdin = shellStdin ?: return
            stdin.write(data.toByteArray())
            stdin.flush()
        } catch (_: Exception) {
            isRunning.set(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        try { shellStdin?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
    }
}
