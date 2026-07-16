package com.HanFeng.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.HanFeng.R
import com.HanFeng.adblocker.shizuku.RootHideManager
import com.HanFeng.adblocker.shizuku.SuSession
import java.io.File

class RootScriptActivity : BaseActivity() {
    private lateinit var etScriptPath: EditText
    private lateinit var scriptInput: EditText
    private lateinit var btnTerminal: Button
    private lateinit var btnLoad: Button
    private lateinit var btnEdit: Button
    private lateinit var btnBrowse: Button
    private lateinit var btnHiddenExecute: Button
    private var isEditing = false
    private var loadedFilePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_root_script)
        val root = findViewById<View>(R.id.scriptRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16.dp, systemBars.top + 16.dp, 16.dp, systemBars.bottom + 16.dp)
            insets
        }
        etScriptPath = findViewById(R.id.etScriptPath)
        scriptInput = findViewById(R.id.scriptInput)
        btnTerminal = findViewById(R.id.btnTerminal)
        btnLoad = findViewById(R.id.btnLoad)
        btnEdit = findViewById(R.id.btnEdit)
        btnBrowse = findViewById(R.id.btnBrowse)

        btnBrowse.setOnClickListener { openRootFileBrowser() }
        btnLoad.setOnClickListener { loadScriptFromPath() }
        btnEdit.setOnClickListener { toggleEditMode() }
        btnTerminal.setOnClickListener { launchTerminal() }
        btnHiddenExecute.setOnClickListener { launchHiddenExecute() }

        etScriptPath.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) etScriptPath.selectAll()
        }
        loadRememberedPath()
    }

    private fun loadRememberedPath() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remembered = prefs.getString(KEY_LAST_SCRIPT_PATH, null)
        if (!remembered.isNullOrBlank()) {
            etScriptPath.setText(remembered)
            autoLoadScript(remembered)
        }
    }

    private fun saveRememberedPath(path: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_SCRIPT_PATH, path).apply()
    }

    private fun openRootFileBrowser() {
        val currentPath = etScriptPath.text.toString().trim().ifBlank { "/sdcard" }
        val startDir = resolveDirectory(currentPath)
        showFileBrowserDialog(startDir)
    }

    private fun resolveDirectory(path: String): String {
        val f = File(path)
        return if (f.isDirectory) f.absolutePath else f.parent ?: "/sdcard"
    }

    private fun showFileBrowserDialog(dirPath: String) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        }
        val pathLabel = TextView(this).apply {
            text = dirPath
            textSize = 12f
            setTextColor(getColor(R.color.hf_text_secondary))
            setPadding(4.dp, 4.dp, 4.dp, 8.dp)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        val listView = ListView(this).apply {
            setBackgroundColor(0x0A000000)
        }
        val statusText = TextView(this).apply {
            text = "加载中..."
            textSize = 12f
            setTextColor(getColor(R.color.hf_text_secondary))
            setPadding(4.dp, 8.dp, 4.dp, 4.dp)
        }
        dialogView.addView(pathLabel)
        dialogView.addView(listView)
        dialogView.addView(statusText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择脚本文件")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()

        Thread {
            val result = listDirectory(dirPath)
            runOnUiThread {
                if (result == null) {
                    statusText.text = "无法读取目录"
                    return@runOnUiThread
                }
                val entries = result.second
                if (entries.isEmpty()) {
                    statusText.text = "目录为空"
                } else {
                    statusText.text = "${entries.size} 个项目"
                }
                val displayNames = entries.map { entry ->
                    val icon = if (entry.isDirectory) "[D] " else "[F] "
                    icon + entry.name
                }
                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
                listView.adapter = adapter
                listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                    val selected = entries.getOrNull(position) ?: return@OnItemClickListener
                    val newPath = if (selected.name == "..") {
                        File(dirPath).parent ?: "/"
                    } else {
                        File(dirPath, selected.name).absolutePath
                    }
                    dialog.dismiss()
                    if (selected.isDirectory || selected.name == "..") {
                        showFileBrowserDialog(newPath)
                    } else {
                        etScriptPath.setText(newPath)
                        saveRememberedPath(newPath)
                        loadScriptContent(newPath)
                    }
                }
            }
        }.start()
        dialog.show()
    }

    private fun listDirectory(path: String): Pair<String, List<FileEntry>>? {
        return try {
            val safePath = path.trim().replace("'", "'\\''")
            val cmd = if (safePath == "/") "ls -1ap '/'"
            else "ls -1ap '$safePath/'"
            val result = SuSession.getInstance().execute(cmd, 8)
            if (result.exitCode != 0) {
                val altCmd = "ls -1a '$safePath/' 2>/dev/null || ls -1 '$safePath/' 2>/dev/null"
                val altResult = SuSession.getInstance().execute(altCmd, 5)
                if (altResult.exitCode != 0) return null
                return parseDirListing(path, altResult.output)
            }
            parseDirListing(path, result.output)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDirListing(path: String, output: String): Pair<String, List<FileEntry>> {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val entries = mutableListOf<FileEntry>()
        if (path.trim() != "/") entries.add(FileEntry("..", true))
        for (line in lines) {
            if (line == "./" || line == "../") continue
            val isDir = line.endsWith("/")
            val name = if (isDir) line.dropLast(1) else line
            if (name.isBlank()) continue
            if (!isDir && !name.endsWith(".sh") && !name.endsWith(".bash") && !name.endsWith(".ksh")) continue
            entries.add(FileEntry(name, isDir))
        }
        entries.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        return path to entries
    }

    private fun loadScriptFromPath() {
        val path = etScriptPath.text.toString().trim()
        if (path.isBlank()) {
            Toast.makeText(this, "请输入脚本路径或点击「浏览」选择", Toast.LENGTH_SHORT).show()
            return
        }
        loadScriptContent(path)
    }

    private fun autoLoadScript(path: String) {
        Thread {
            val result = readFileContent(path)
            runOnUiThread {
                if (result != null) {
                    scriptInput.setText(result)
                    loadedFilePath = path
                    setEditMode(false)
                }
            }
        }.start()
    }

    private fun loadScriptContent(path: String) {
        Thread {
            val result = readFileContent(path)
            runOnUiThread {
                if (result != null) {
                    scriptInput.setText(result)
                    loadedFilePath = path
                    saveRememberedPath(path)
                    setEditMode(false)
                } else {
                    scriptInput.text.clear()
                    Toast.makeText(this, "无法读取文件: $path", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun readFileContent(path: String): String? {
        return try {
            val safePath = path.replace("'", "'\\''")

            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    return file.readText().take(65536)
                }
            } catch (_: Exception) {}

            val checkResult = SuSession.getInstance().execute("test -f '$safePath' && echo EXISTS || echo NOTFOUND", 5)
            if (!checkResult.output.contains("EXISTS")) {
                return null
            }

            val catResult = SuSession.getInstance().execute("cat '$safePath' 2>/dev/null | head -c 65536", 8)
            val content = catResult.output
            if (content.isBlank()) return null
            content
        } catch (_: Exception) {
            null
        }
    }

    private fun toggleEditMode() {
        isEditing = !isEditing
        scriptInput.isEnabled = isEditing
        scriptInput.isFocusable = isEditing
        scriptInput.isFocusableInTouchMode = isEditing
        btnEdit.text = if (isEditing) "完成编辑" else "编辑内容"
        if (isEditing) scriptInput.requestFocus()
    }

    private fun setEditMode(editable: Boolean) {
        isEditing = editable
        scriptInput.isEnabled = editable
        scriptInput.isFocusable = editable
        scriptInput.isFocusableInTouchMode = editable
        btnEdit.text = if (editable) "完成编辑" else "编辑内容"
        if (editable) scriptInput.requestFocus()
    }

    private fun launchTerminal() {
        val path = loadedFilePath
        if (path != null && !isEditing) {
            startActivity(Intent(this, RootTerminalActivity::class.java).apply {
                putExtra("script_path", path)
            })
            return
        }
        val content = scriptInput.text.toString().trim()
        if (content.isBlank()) {
            startActivity(Intent(this, RootTerminalActivity::class.java))
            return
        }
        val tmpFile = File(cacheDir, "terminal_script.sh")
        tmpFile.writeText(content)
        tmpFile.setReadable(true, false)
        tmpFile.setExecutable(true, false)
        val externalTmp = File(externalCacheDir ?: cacheDir, "terminal_script.sh")
        externalTmp.writeText(content)
        externalTmp.setReadable(true, false)
        externalTmp.setExecutable(true, false)
        startActivity(Intent(this, RootTerminalActivity::class.java).apply {
            putExtra("script_path", externalTmp.absolutePath)
        })
    }

    private fun launchHiddenExecute() {
        val path = loadedFilePath
        if (path == null) {
            Toast.makeText(this, "请先加载或输入脚本路径", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            val newPid = RootHideManager().executeHiddenScript(path)
            runOnUiThread {
                if (newPid != null) {
                    Toast.makeText(this, "脚本已在隔离环境中执行\nPID: $newPid\n对其他 App 不可见", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "隐藏执行失败\n请确认：\n1. 已授予 Root 权限\n2. 设备支持 unshare 命令", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private data class FileEntry(val name: String, val isDirectory: Boolean)

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "root_script_prefs"
        private const val KEY_LAST_SCRIPT_PATH = "last_script_path"
    }
}
