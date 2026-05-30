package com.HanFeng.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.HanFeng.R
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RemoteRuleSourceRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.databinding.ActivityRemoteRuleSourcesBinding
import com.HanFeng.model.RemoteRuleSourceConfig
import com.HanFeng.service.AdBlockVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class RemoteRuleSourcesActivity : BaseActivity() {

    private lateinit var binding: ActivityRemoteRuleSourcesBinding
    private var allSources: List<RemoteRuleSourceConfig> = emptyList()
    private val adapter = RemoteRuleSourceListAdapter(
        onToggle = { source -> toggleSource(source) },
        onSync = { source -> syncSources(source.id, manual = true) },
        onEdit = { source -> showEditSourceDialog(source) },
        onDelete = { source -> confirmDeleteSource(source) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityRemoteRuleSourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.remoteRuleSourcesRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16.dp, systemBars.top + 16.dp, 16.dp, systemBars.bottom + 16.dp)
            insets
        }
        binding.sourceList.layoutManager = LinearLayoutManager(this)
        binding.sourceList.adapter = adapter
        binding.searchInput.doAfterTextChanged {
            applyFilter(it?.toString().orEmpty())
        }
        binding.btnAddSource.setOnClickListener { showSourceEditorDialog() }
        binding.btnSyncAll.setOnClickListener { syncSources(manual = true) }
        loadSources()
    }

    override fun onResume() {
        super.onResume()
        loadSources()
    }

    private fun loadSources() {
        allSources = RuleRepository.getRemoteRuleSources(this)
        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    private fun applyFilter(keyword: String) {
        val normalized = keyword.trim().lowercase()
        val filtered = if (normalized.isBlank()) {
            allSources
        } else {
            allSources.filter { source ->
                source.name.lowercase().contains(normalized) || source.url.lowercase().contains(normalized)
            }
        }
        adapter.submit(filtered)
        binding.emptyText.isVisible = filtered.isEmpty()
    }

    private fun toggleSource(source: RemoteRuleSourceConfig) {
        val enabling = !source.enabled
        RuleRepository.updateRemoteRuleSource(this, source.copy(enabled = enabling, lastError = null))
        if (enabling) {
            loadSources()
            syncSources(source.id, manual = true)
            return
        }
        val removedCount = RuleRepository.removeRulesForRemoteSource(this, source.id)
        loadSources()
        reloadVpnIfRunning(removedCount > 0)
        Toast.makeText(this, "已停用规则源，并移除 $removedCount 条规则", Toast.LENGTH_SHORT).show()
    }

    private fun syncSources(sourceId: String? = null, manual: Boolean = false, allowWhitelistDomains: Boolean = false) {
        binding.loadingOverlay.isVisible = true
        if (manual) {
            Toast.makeText(this, if (sourceId == null) "正在同步规则源" else "正在更新规则源", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (sourceId == null) {
                        RemoteRuleSourceRepository.syncEnabledSources(this@RemoteRuleSourcesActivity, allowWhitelistDomains)
                    } else {
                        val source = RuleRepository.getRemoteRuleSources(this@RemoteRuleSourcesActivity).firstOrNull { it.id == sourceId }
                            ?: throw IllegalStateException("规则源不存在")
                        listOf(RemoteRuleSourceRepository.syncSource(this@RemoteRuleSourcesActivity, source, allowWhitelistDomains))
                    }
                }
            }.onSuccess { results ->
                binding.loadingOverlay.isVisible = false
                val whitelistConflicts = results.filter { it.whitelistConflictRules > 0 }
                if (whitelistConflicts.isNotEmpty() && !allowWhitelistDomains) {
                    showWhitelistConflictDialog(sourceId, manual, whitelistConflicts)
                    return@onSuccess
                }
                loadSources()
                reloadVpnIfRunning(results.any { it.success })
                if (manual) {
                    val successCount = results.count { it.success }
                    val firstFailed = results.firstOrNull { !it.success }
                    if (firstFailed?.errorMessage != null && successCount == 0) {
                        Toast.makeText(this@RemoteRuleSourcesActivity, buildSyncSummary(results), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@RemoteRuleSourcesActivity, buildSyncSummary(results), Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure {
                binding.loadingOverlay.isVisible = false
                LogRepository.append(this@RemoteRuleSourcesActivity, "Remote rule source sync failed: ${it.message ?: it.javaClass.simpleName}")
                Toast.makeText(this@RemoteRuleSourcesActivity, it.message ?: "规则源更新失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSourceEditorDialog(source: RemoteRuleSourceConfig? = null) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = 20.dp
            setPadding(padding, padding, padding, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "规则源名称"
            setText(source?.name.orEmpty())
        }
        val urlInput = EditText(this).apply {
            hint = "https://example.com/rules.txt"
            setText(source?.url.orEmpty())
        }
        container.addView(nameInput)
        container.addView(urlInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (source == null) "添加规则源" else "编辑规则源")
            .setView(container)
            .setPositiveButton(if (source == null) "添加" else "保存") { _, _ ->
                val name = nameInput.text?.toString().orEmpty().trim()
                val url = urlInput.text?.toString().orEmpty().trim()
                when {
                    name.isBlank() -> {
                        Toast.makeText(this, "请输入规则源名称", Toast.LENGTH_SHORT).show()
                        showSourceEditorDialog(source)
                    }
                    url.isBlank() -> {
                        Toast.makeText(this, "请输入规则源地址", Toast.LENGTH_SHORT).show()
                        showSourceEditorDialog(source)
                    }
                    !url.startsWith("http://") && !url.startsWith("https://") -> {
                        Toast.makeText(this, "规则源地址格式不正确", Toast.LENGTH_SHORT).show()
                        showSourceEditorDialog(source)
                    }
                    RuleRepository.getRemoteRuleSources(this).any {
                        it.url.equals(url, ignoreCase = true) && it.id != source?.id
                    } -> {
                        Toast.makeText(this, "这个规则源地址已经存在", Toast.LENGTH_SHORT).show()
                    }
                    source == null -> addRemoteRuleSource(name, url)
                    else -> {
                        val oldUrl = source.url.trim()
                        val updated = source.copy(name = name, url = url, lastError = null)
                        RuleRepository.updateRemoteRuleSource(this, updated)
                        loadSources()
                        if (!source.enabled) {
                            Toast.makeText(this, "规则源已更新，当前处于停用状态", Toast.LENGTH_SHORT).show()
                        } else if (!oldUrl.equals(url, ignoreCase = true)) {
                            Toast.makeText(this, "规则源地址已更新，正在重新拉取", Toast.LENGTH_SHORT).show()
                            syncSources(source.id, manual = true)
                        } else {
                            Toast.makeText(this, "规则源已更新", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addRemoteRuleSource(name: String, url: String) {
        val source = RemoteRuleSourceConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            enabled = true
        )
        RuleRepository.addRemoteRuleSource(this, source)
        loadSources()
        syncSources(source.id, manual = true)
    }

    private fun showEditSourceDialog(source: RemoteRuleSourceConfig) {
        showSourceEditorDialog(source)
    }

    private fun confirmDeleteSource(source: RemoteRuleSourceConfig) {
        val sourceRules = RuleRepository.getRulesForRemoteSource(this, source.id)
        val sampleText = sourceRules.take(8).joinToString("\n") { rule -> "- ${rule.domain}" }
        val message = buildString {
            append("规则源：")
            append(source.name)
            append("\n地址：")
            append(source.url)
            append("\n\n当前已同步规则：")
            append(sourceRules.size)
            append(" 条")
            if (sampleText.isNotBlank()) {
                append("\n\n规则示例：\n")
                append(sampleText)
            }
            append("\n\n请选择删除方式。")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除规则源")
            .setMessage(message)
            .setPositiveButton("删除规则源和规则") { _, _ ->
                val removedCount = RuleRepository.removeRulesForRemoteSource(this, source.id)
                RuleRepository.removeRemoteRuleSource(this, source.id)
                loadSources()
                Toast.makeText(this, "已删除规则源，并移除 $removedCount 条规则", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("只删规则源") { _, _ ->
                RuleRepository.removeRemoteRuleSource(this, source.id)
                loadSources()
                Toast.makeText(this, "规则源已删除，规则已保留", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showWhitelistConflictDialog(
        sourceId: String?,
        manual: Boolean,
        results: List<RemoteRuleSourceRepository.RemoteRuleSyncResult>
    ) {
        val displayText = buildString {
            results.forEachIndexed { index, result ->
                if (index > 0) append("\n\n")
                append("规则源：")
                append(result.source.name)
                append("\n疑似白名单规则：")
                append(result.whitelistConflictRules)
                append(" 条\n")
                result.whitelistConflictSamples.take(12).forEach { sample ->
                    append("- ")
                    append(sample)
                    append('\n')
                }
                if (result.whitelistConflictRules > 12) {
                    append("- 其余 ")
                    append(result.whitelistConflictRules - 12)
                    append(" 条请后续继续确认\n")
                }
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("发现疑似白名单规则")
            .setMessage(displayText)
            .setPositiveButton("继续拦截") { _, _ ->
                syncSources(sourceId, manual, allowWhitelistDomains = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun reloadVpnIfRunning(shouldReload: Boolean) {
        if (!shouldReload || !AdBlockVpnService.isRunning) return
        startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
    }

    private fun buildSyncSummary(results: List<RemoteRuleSourceRepository.RemoteRuleSyncResult>): String {
        if (results.isEmpty()) return "规则源更新失败"
        val successCount = results.count { it.success }
        val failureCount = results.size - successCount
        val addedCount = results.sumOf { it.addedCount }
        val candidateCount = results.sumOf { it.whitelistConflictRules }
        val firstFailed = results.firstOrNull { !it.success }
        return buildString {
            append("规则源更新完成，成功 ")
            append(successCount)
            append(" / ")
            append(results.size)
            append("，同步规则 ")
            append(addedCount)
            append(" 条")
            if (failureCount > 0) {
                append("，失败 ")
                append(failureCount)
                append(" 个")
            }
            if (candidateCount > 0) {
                append("，待确认 ")
                append(candidateCount)
                append(" 条疑似白名单规则")
            }
            firstFailed?.let { failed ->
                failed.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    append("，首个失败源：")
                    append(failed.source.name)
                    append("（")
                    append(message)
                    append("）")
                }
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
