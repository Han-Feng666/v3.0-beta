package com.HanFeng.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuEnhanceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HostsEditorActivity : BaseActivity() {
    private lateinit var hostsInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_hosts_editor)
        val root = findViewById<android.view.View>(R.id.hostsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16.dp, systemBars.top + 16.dp, 16.dp, systemBars.bottom + 16.dp)
            insets
        }
        hostsInput = findViewById(R.id.hostsInput)
        hostsInput.setText(ShizukuEnhanceRepository.getHostsDomains(this).joinToString("\n"))
        findViewById<Button>(R.id.btnSyncHosts).setOnClickListener { saveAndSync() }
        findViewById<Button>(R.id.btnClearHosts).setOnClickListener { clearSyncedHosts() }
    }

    private fun saveAndSync() {
        val domains = parseDomains()
        ShizukuEnhanceRepository.saveHostsDomains(this, domains)
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = ShizukuAdControlRepository.syncHostsBlocklist(this@HostsEditorActivity, domains)
            val summary = ShizukuAdControlRepository.getLastOperationSummary(this@HostsEditorActivity)
            withContext(Dispatchers.Main) {
                showResult(if (ok) "Hosts 已同步" else "Hosts 同步失败", summary)
            }
        }
    }

    private fun clearSyncedHosts() {
        ShizukuEnhanceRepository.saveHostsDomains(this, emptyList())
        hostsInput.setText("")
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = ShizukuAdControlRepository.clearHostsBlocklist(this@HostsEditorActivity)
            val summary = ShizukuAdControlRepository.getLastOperationSummary(this@HostsEditorActivity)
            withContext(Dispatchers.Main) {
                showResult(if (ok) "Hosts 同步内容已清除" else "清除失败", summary)
            }
        }
    }

    private fun parseDomains(): List<String> {
        return hostsInput.text?.toString().orEmpty()
            .lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore(' ').substringBefore('\t') }
            .filter { domain -> domain.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' } }
            .distinct()
            .toList()
    }

    private fun showResult(title: String, summary: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(summary.ifBlank { "没有服务反馈。" })
            .setPositiveButton("确定", null)
            .showSafely(this, "Show hosts sync result dialog failed")
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
