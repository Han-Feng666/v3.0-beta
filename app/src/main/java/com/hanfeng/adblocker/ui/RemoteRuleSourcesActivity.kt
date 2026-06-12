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
import com.HanFeng.core.network.NetworkKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class RemoteRuleSourcesActivity : BaseActivity() {

    private lateinit var binding: ActivityRemoteRuleSourcesBinding
    private var allSources: List<RemoteRuleSourceConfig> = emptyList()
    private var loadSourcesJob: Job? = null
    private var loadSourcesVersion = 0
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
        val requestVersion = ++loadSourcesVersion
        loadSourcesJob?.cancel()
        loadSourcesJob = lifecycleScope.launch {
            val sources = withContext(Dispatchers.Default) {
                RuleRepository.getRemoteRuleSources(applicationContext)
            }
            if (requestVersion != loadSourcesVersion || isFinishing || isDestroyed) return@launch
            allSources = sources
            applyFilter(binding.searchInput.text?.toString().orEmpty())
        }
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
        val sourceId = source.id.trim()
        if (sourceId.isBlank()) {
            showShortToast("规则源标识无效")
            return
        }
        val enabling = !source.enabled
        RuleRepository.updateRemoteRuleSource(this, source.copy(enabled = enabling, lastError = null))
        if (enabling) {
            loadSources()
            showShortToast("规则源已启用，正在同步规则")
            syncSources(sourceId, manual = true)
            return
        }
        val removedCount = RuleRepository.removeRulesForRemoteSource(this, sourceId)
        loadSources()
        reloadVpnIfRunning(removedCount > 0)
        showShortToast("已停用规则源，并移除 $removedCount 条规则")
    }

    private fun syncSources(sourceId: String? = null, manual: Boolean = false, allowWhitelistDomains: Boolean = false) {
        // 始终允许白名单规则：信任规则源作者，与 AdGuard 行为一致
        val whitelistImportMode = RemoteRuleSourceRepository.WhitelistImportMode.ALLOW  // 总是使用 ALLOW 模式
        syncSources(sourceId, manual, whitelistImportMode)
    }

    private fun syncSources(
        sourceId: String? = null,
        manual: Boolean = false,
        whitelistImportMode: RemoteRuleSourceRepository.WhitelistImportMode
    ) {
        binding.loadingOverlay.isVisible = true
        binding.loadingText.text = if (sourceId == null) "正在同步全部规则源..." else "正在同步规则源..."
        val syncTarget = if (sourceId == null) "all sources" else "source=$sourceId"
        LogRepository.append(this@RemoteRuleSourcesActivity, "[Sync] started: $syncTarget, manual=$manual, whitelistMode=$whitelistImportMode")
        LogRepository.append(this@RemoteRuleSourcesActivity, "[Sync] userAction=$manual, timeout=60s/120s")
        
        if (manual) {
            showShortToast(if (sourceId == null) "正在同步规则源" else "正在同步规则")
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (sourceId == null) {
                        RemoteRuleSourceRepository.syncEnabledSources(this@RemoteRuleSourcesActivity, whitelistImportMode) { current, total, source ->
                            lifecycleScope.launch {
                                binding.loadingText.text = "正在同步规则源 $current / $total\n${source.name}\n下载并导入中..."
                            }
                        }
                    } else {
                        val source = RuleRepository.getRemoteRuleSources(this@RemoteRuleSourcesActivity).firstOrNull { it.id == sourceId }
                            ?: throw IllegalStateException("规则源不存在")
                        lifecycleScope.launch {
                            binding.loadingText.text = "正在同步规则源\n${source.name}\n下载并导入中..."
                        }
                        listOf(RemoteRuleSourceRepository.syncSource(this@RemoteRuleSourcesActivity, source, whitelistImportMode))
                    }
                }
            }.onSuccess { results ->
                binding.loadingOverlay.isVisible = false
                LogRepository.append(this@RemoteRuleSourcesActivity, "[Sync] completed: results=${results.size}")
                
                results.forEach { result ->
                    LogRepository.append(this@RemoteRuleSourcesActivity, 
                        "[Sync] result: name=${result.source.name}, success=${result.success}, " +
                        "addedCount=${result.addedCount}, filteredCount=${result.filteredCount}")
                    
                    if (result.errorMessage != null) {
                        LogRepository.append(this@RemoteRuleSourcesActivity, "[Sync] error: ${result.errorMessage}")
                    }
                }
                
                val whitelistConflicts = results.filter { it.whitelistConflictRules > 0 }
                if (whitelistConflicts.isNotEmpty() && whitelistImportMode == RemoteRuleSourceRepository.WhitelistImportMode.BLOCK) {
                    LogRepository.append(this@RemoteRuleSourcesActivity, "[Sync] whitelist conflicts detected=${whitelistConflicts.size}")
                    binding.loadingOverlay.isVisible = false
                    showWhitelistConflictDialog(sourceId, manual, whitelistConflicts)
                    return@onSuccess
                }
                binding.loadingText.text = "正在刷新规则源列表..."
                loadSources()
                reloadVpnIfRunning(results.any { it.success })
                if (manual) {
                    runCatching {
                        val failureResult = results.firstOrNull { !it.success }
                        if (failureResult != null) {
                            LogRepository.append(this@RemoteRuleSourcesActivity, "[Sync] showing failure toast: ${failureResult.errorMessage}")
                            showShortToast(failureResult.errorMessage ?: "规则源同步失败")
                        } else {
                            showShortToast(buildSyncSummary(results, whitelistImportMode))
                        }
                    }.onFailure {
                        LogRepository.append(this@RemoteRuleSourcesActivity, "Show sync summary failed: ${it.message ?: it.javaClass.simpleName}")
                    }
                }
            }.onFailure {
                binding.loadingOverlay.isVisible = false
                val errorMsg = "[Sync] failed: ${it.message ?: it.javaClass.simpleName}"
                LogRepository.append(this@RemoteRuleSourcesActivity, errorMsg)
                LogRepository.append(this@RemoteRuleSourcesActivity, "[Sync] troubleshooting: 请检查网络连接、规则源 URL 是否正确")
                showShortToast(it.message ?: "规则源更新失败")
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
            .setPositiveButton(if (source == null) "添加" else "保存", null)
            .setNegativeButton("取消", null)
            .show()
            .also { dialog ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = nameInput.text?.toString().orEmpty().trim()
                    val url = urlInput.text?.toString().orEmpty().trim()
                    when {
                        name.isBlank() -> {
                            showShortToast("请输入规则源名称")
                        }
                        url.isBlank() -> {
                            showShortToast("请输入规则源地址")
                        }
                        !url.startsWith("http://") && !url.startsWith("https://") -> {
                            showShortToast("规则源地址格式不正确")
                        }
                        else -> {
                            lifecycleScope.launch {
                                val duplicated = withContext(Dispatchers.Default) {
                                    RuleRepository.getRemoteRuleSources(applicationContext).any {
                                        it.url.equals(url, ignoreCase = true) && it.id != source?.id
                                    }
                                }
                                if (duplicated) {
                                    showShortToast("这个规则源地址已经存在")
                                    return@launch
                                }
                                if (source == null) {
                                    addRemoteRuleSource(name, url)
                                } else {
                                    val oldUrl = source.url.trim()
                                    val updated = source.copy(name = name, url = url, lastError = null)
                                    RuleRepository.updateRemoteRuleSource(this@RemoteRuleSourcesActivity, updated)
                                    loadSources()
                                    if (!source.enabled) {
                                        showShortToast("规则源已更新，当前处于停用状态")
                                    } else if (!oldUrl.equals(url, ignoreCase = true)) {
                                        showShortToast("规则源地址已更新，正在重新拉取")
                                        syncSources(source.id, manual = true)
                                    } else {
                                        showShortToast("规则源已更新")
                                    }
                                }
                                dialog.dismiss()
                            }
                        }
                    }
                }
            }
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
        syncSources(source.id.trim(), manual = true, allowWhitelistDomains = true)
    }

    private fun showEditSourceDialog(source: RemoteRuleSourceConfig) {
        showSourceEditorDialog(source)
    }

    private fun confirmDeleteSource(source: RemoteRuleSourceConfig) {
        val sourceId = source.id.trim()
        if (sourceId.isBlank()) {
            showShortToast("规则源标识无效")
            return
        }
        
        val logPrefix = "[DeleteSource] sourceId=$sourceId, name=${source.name}"
        LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - 开始删除流程")
        
        try {
            // Step 1: 获取规则列表
            LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 1: 获取规则列表")
            
            lifecycleScope.launch {
                try {
                    val sourceRules = withContext(Dispatchers.Default) {
                        val rules = RuleRepository.getRulesForRemoteSource(applicationContext, sourceId)
                        LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 1 完成：找到 ${rules.size} 条规则")
                        rules
                    }
                    
                    if (isFinishing || isDestroyed) {
                        LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - 取消：Activity is finishing/destroyed")
                        return@launch
                    }
                    
                    // Step 2: 构建对话框消息
                    LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 2: 构建对话框")
                    
                    val sampleText = sourceRules.take(8).joinToString("\n") { rule -> "- ${rule.domain}" }
                    val message = buildString {
                        append("规则源：${source.name}\n")
                        append("地址：${source.url}\n")
                        append("\n当前已同步规则：${sourceRules.size} 条")
                        if (sampleText.isNotBlank()) {
                            append("\n\n规则示例：\n")
                            append(sampleText)
                        }
                        append("\n\n请选择删除方式。")
                    }
                    
                    // Step 3: 创建并显示对话框
                    LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 3: 显示对话框")
                    
                    val dialogBuilder = StableDialog.builder(this@RemoteRuleSourcesActivity)
                        .setTitle("删除规则源")
                        .setMessage(message)
                        .setPositiveButton("删除规则源和规则") { dialog, _ ->
                            LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 4: 点击 [删除规则源和规则]")
                            dialog.dismiss()
                            lifecycleScope.launch {
                                try {
                                    val removedCount = withContext(Dispatchers.Default) {
                                        val removed = RuleRepository.removeRulesForRemoteSource(applicationContext, sourceId)
                                        RuleRepository.removeRemoteRuleSource(applicationContext, sourceId)
                                        LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 5: 已删除规则源，rules=$removed")
                                        removed
                                    }
                                    if (isFinishing || isDestroyed) return@launch
                                    loadSources()
                                    reloadVpnIfRunning(removedCount > 0)
                                    LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 6: 删除完成，removed=$removedCount")
                                    showShortToast("已删除规则源，并移除 $removedCount 条规则")
                                } catch (e: Exception) {
                                    LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 5 失败：${e.message ?: e.javaClass.simpleName}")
                                    showShortToast("删除失败：${e.message}")
                                }
                            }
                        }
                        .setNeutralButton("只删规则源") { dialog, _ ->
                            LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 4: 点击 [只删规则源]")
                            dialog.dismiss()
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.Default) {
                                        RuleRepository.removeRemoteRuleSource(applicationContext, sourceId)
                                        LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 5: 已删除规则源配置")
                                    }
                                    if (isFinishing || isDestroyed) return@launch
                                    loadSources()
                                    LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 6: 删除完成")
                                    showShortToast("规则源已删除，规则已保留")
                                } catch (e: Exception) {
                                    LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 5 失败：${e.message ?: e.javaClass.simpleName}")
                                    showShortToast("删除失败：${e.message}")
                                }
                            }
                        }
                        .setNegativeButton("取消") { dialog, _ ->
                            LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 4: 点击 [取消]")
                            dialog.dismiss()
                        }
                    
                    try {
                        dialogBuilder.showSafely(this@RemoteRuleSourcesActivity, "Show delete dialog failed")
                        LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 3 完成：对话框已显示")
                    } catch (e: Exception) {
                        val errorMsg = "显示对话框失败：${e.message ?: e.javaClass.simpleName}"
                        LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - $errorMsg")
                        showShortToast(errorMsg)
                    }
                } catch (e: Exception) {
                    val errorMsg = "获取规则失败：${e.message ?: e.javaClass.simpleName}"
                    LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - Step 1 失败：$errorMsg")
                    showShortToast(errorMsg)
                }
            }
        } catch (e: Exception) {
            val errorMsg = "删除操作异常：${e.message ?: e.javaClass.simpleName}"
            LogRepository.append(this@RemoteRuleSourcesActivity, "$logPrefix - 异常：$errorMsg")
            showShortToast(errorMsg)
        }
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

        StableDialog.builder(this)
            .setTitle("发现疑似白名单规则")
            .setMessage(displayText)
            .setPositiveButton("继续拦截") { _, _ ->
                syncSources(sourceId, manual, RemoteRuleSourceRepository.WhitelistImportMode.ALLOW)
            }
            .setNeutralButton("删除白名单后继续") { _, _ ->
                syncSources(sourceId, manual, RemoteRuleSourceRepository.WhitelistImportMode.REMOVE_CONFLICTS)
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show remote whitelist conflict dialog failed")
    }

    private fun buildSyncSummary(
        results: List<RemoteRuleSourceRepository.RemoteRuleSyncResult>,
        whitelistImportMode: RemoteRuleSourceRepository.WhitelistImportMode
    ): String {
        val successCount = results.count { it.success }
        if (successCount == 0) {
            return results.firstOrNull()?.errorMessage ?: "规则源更新失败"
        }
        val totalAdded = results.filter { it.success }.sumOf { it.addedCount }
        val conflictCount = results.sumOf { it.whitelistConflictRules }
        val suffix = when {
            conflictCount <= 0 -> ""
            whitelistImportMode == RemoteRuleSourceRepository.WhitelistImportMode.ALLOW -> {
                "，含 $conflictCount 条已确认继续拦截的疑似白名单规则"
            }
            whitelistImportMode == RemoteRuleSourceRepository.WhitelistImportMode.REMOVE_CONFLICTS -> {
                "，已删除 $conflictCount 条疑似白名单规则"
            }
            else -> ""
        }
        return "规则源更新完成，共导入 $totalAdded 条规则，成功 $successCount / ${results.size}$suffix"
    }

    private fun reloadVpnIfRunning(shouldReload: Boolean) {
        if (!shouldReload) return
        NetworkKernel.reloadIfRunning(this)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
