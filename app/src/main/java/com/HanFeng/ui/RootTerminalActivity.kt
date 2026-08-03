package com.HanFeng.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.HanFeng.R
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RootTerminalActivity : BaseActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText
    private lateinit var terminalScroll: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var btnFontSize: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnCopy: ImageButton
    private lateinit var btnTab: Button
    private lateinit var btnUp: Button
    private lateinit var btnDown: Button
    private lateinit var btnCtrlC: Button
    private lateinit var btnCtrlZ: Button
    private lateinit var btnCtrlD: Button

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
    private val fontSizeLevels = listOf(10f, 11f, 12f, 13f, 14f, 15f, 16f)
    private val fontSizeIndex = AtomicInteger(2)

    private var pastePending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_root_terminal)

        terminalOutput = findViewById(R.id.terminalOutput)
        terminalInput = findViewById(R.id.terminalInput)
        terminalScroll = findViewById(R.id.terminalScroll)
        tvStatus = findViewById(R.id.tvTerminalStatus)
        tvPrompt = findViewById(R.id.tvPrompt)
        btnFontSize = findViewById(R.id.btnFontSize)
        btnClear = findViewById(R.id.btnClear)
        btnCopy = findViewById(R.id.btnCopy)
        btnTab = findViewById(R.id.btnTab)
        btnUp = findViewById(R.id.btnUp)
        btnDown = findViewById(R.id.btnDown)
        btnCtrlC = findViewById(R.id.btnCtrlC)
        btnCtrlZ = findViewById(R.id.btnCtrlZ)
        btnCtrlD = findViewById(R.id.btnCtrlD)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        terminalOutput.movementMethod = ScrollingMovementMethod()
        initialScriptPath = intent.getStringExtra("script_path")

        applyFontSize()

        terminalScroll.viewTreeObserver.addOnScrollChangedListener {
            val child = terminalScroll.getChildAt(0) ?: return@addOnScrollChangedListener
            val scrollY = terminalScroll.scrollY
            val childHeight = child.height
            val scrollViewHeight = terminalScroll.height
            autoScrollEnabled = scrollY + scrollViewHeight >= childHeight - 48
        }

        val showKeyboardClickListener = View.OnClickListener {
            terminalInput.requestFocus()
            showKeyboard()
        }
        terminalScroll.setOnClickListener(showKeyboardClickListener)
        terminalOutput.setOnClickListener(showKeyboardClickListener)
        terminalOutput.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                terminalInput.requestFocus()
                showKeyboard()
                false
            } else false
        }

        terminalInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val text = terminalInput.text.toString().trimEnd('\r', '\n')
                if (text.isNotEmpty()) {
                    addToHistory(text)
                    sendCommand(text)
                    terminalInput.text?.clear()
                }
                true
            } else false
        }

        terminalInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                navigateHistory(-1)
                true
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                navigateHistory(1)
                true
            } else {
                false
            }
        }

        terminalInput.setOnLongClickListener {
            pasteFromClipboard()
            false
        }

        findViewById<View>(R.id.terminalTitleBar).setOnLongClickListener {
            synchronized(outputLock) { outputBuilder.clear() }
            terminalOutput.text = ""
            Toast.makeText(this, "输出已清空", Toast.LENGTH_SHORT).show()
            true
        }

        btnFontSize.setOnClickListener {
            cycleFontSize()
        }

        btnClear.setOnClickListener {
            synchronized(outputLock) { outputBuilder.clear() }
            terminalOutput.text = ""
            Toast.makeText(this, "输出已清空", Toast.LENGTH_SHORT).show()
        }

        btnCopy.setOnClickListener {
            copyOutputToClipboard()
        }

        btnTab.setOnClickListener {
            sendRaw("\t")
        }

        btnUp.setOnClickListener {
            navigateHistory(-1)
        }

        btnDown.setOnClickListener {
            navigateHistory(1)
        }

        btnCtrlC.setOnClickListener {
            sendRaw("\u0003")
        }

        btnCtrlZ.setOnClickListener {
            sendRaw("\u001A")
        }

        btnCtrlD.setOnClickListener {
            sendRaw("\u0004")
        }

        startShell()
        terminalInput.requestFocus()
        terminalInput.post {
            showKeyboard()
        }
    }

    private fun applyFontSize() {
        val size = fontSizeLevels[fontSizeIndex.get()]
        terminalOutput.textSize = size
        terminalInput.textSize = size
        tvPrompt.textSize = size
    }

    private fun cycleFontSize() {
        val next = (fontSizeIndex.get() + 1) % fontSizeLevels.size
        fontSizeIndex.set(next)
        applyFontSize()
        Toast.makeText(this, "字体大小: ${fontSizeLevels[next]}sp", Toast.LENGTH_SHORT).show()
    }

    private fun copyOutputToClipboard() {
        val text = terminalOutput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "没有可复制的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("终端输出", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val text = item?.text?.toString()
            if (!text.isNullOrBlank()) {
                val cleanText = text.replace("\n", " ").replace("\r", "")
                terminalInput.setText(cleanText)
                terminalInput.setSelection(terminalInput.text.length)
                Toast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        val newIndex = historyIndex + direction
        if (newIndex < 0 || newIndex > commandHistory.size) return
        historyIndex = newIndex
        if (newIndex == commandHistory.size) {
            terminalInput.text?.clear()
        } else {
            val cmd = commandHistory[newIndex]
            terminalInput.setText(cmd)
            terminalInput.setSelection(cmd.length)
        }
    }

    private fun addToHistory(cmd: String) {
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.add(cmd)
            if (commandHistory.size > 200) commandHistory.removeAt(0)
        }
        historyIndex = commandHistory.size
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(terminalInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun appendOutput(text: String) {
        synchronized(outputLock) {
            outputBuilder.append(text)
            if (outputBuilder.length > 512 * 1024) {
                val startIdx = outputBuilder.length - 256 * 1024
                outputBuilder.delete(0, startIdx)
            }
            terminalOutput.text = outputBuilder.toString()
            if (autoScrollEnabled) {
                terminalScroll.post { terminalScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun truncateToCharacterBoundary(bytes: ByteArray, length: Int): Int {
        if (length <= 0) return 0
        var idx = length - 1
        var trailBytesNeeded = 0
        while (idx >= 0 && (bytes[idx].toInt() and 0xC0) == 0x80) {
            trailBytesNeeded++
            idx--
            if (trailBytesNeeded >= 4) break
        }
        if (idx < 0) return 0
        val lead = bytes[idx].toInt() and 0xFF
        val expectedLen = when {
            lead and 0x80 == 0x00 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        if (trailBytesNeeded + 1 >= expectedLen) return length
        return idx
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
                        val decoder = java.nio.charset.Charset.forName("UTF-8").newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                        val byteBuf = java.nio.ByteBuffer.allocate(8192)
                        var pendingBytes = 0
                        while (true) {
                            val n: Int
                            try {
                                n = if (pendingBytes > 0) {
                                    System.arraycopy(byteBuf.array(), 8192 - pendingBytes, byteBuf.array(), 0, pendingBytes)
                                    input.read(byteBuf.array(), pendingBytes, 8192 - pendingBytes)
                                } else {
                                    input.read(byteBuf.array())
                                }
                            } catch (_: Exception) {
                                break
                            }
                            if (n < 0) break
                            if (n == 0) continue
                            val totalLen = if (pendingBytes > 0) pendingBytes + n else n
                            if (totalLen <= 0) { pendingBytes = 0; continue }

                            val safeLen = truncateToCharacterBoundary(byteBuf.array(), totalLen)
                            pendingBytes = totalLen - safeLen
                            if (pendingBytes > 0 && safeLen < totalLen) {
                                System.arraycopy(byteBuf.array(), safeLen, byteBuf.array(), 8192 - pendingBytes, pendingBytes)
                            }

                            if (safeLen > 0) {
                                byteBuf.limit(safeLen)
                                byteBuf.position(0)
                                val charBuf = decoder.decode(byteBuf)
                                decoder.reset()
                                val raw = charBuf.toString()
                                handler.post { appendOutput(raw) }
                            }
                        }
                        if (pendingBytes > 0) {
                            byteBuf.position(0)
                            byteBuf.limit(pendingBytes)
                            val charBuf = decoder.decode(byteBuf)
                            val raw = charBuf.toString()
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

    private fun sendRaw(data: String) {
        try {
            val stdin = shellStdin ?: return
            stdin.write(data.toByteArray())
            stdin.flush()
        } catch (_: Exception) {
            isRunning.set(false)
        }
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
