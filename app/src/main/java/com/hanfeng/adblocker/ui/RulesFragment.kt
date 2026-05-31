package com.HanFeng.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.content.ContentResolver
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RemoteRuleSourceRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.databinding.FragmentRulesBinding
import com.HanFeng.databinding.ItemRuleDomainBinding
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RemoteRuleSourceConfig
import com.HanFeng.model.RuleListItem
import com.HanFeng.model.RuleSource
import com.HanFeng.service.AdBlockVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RulesFragment : Fragment(R.layout.fragment_rules) {
    private var mainActivity: MainActivity? = null
    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!
    private val expandedGroups = mutableSetOf<String>()
    private val selectedIds = mutableSetOf<String>()
    private var selectionMode = false
    private var filteredSelectionMode = false
    private var refreshVersion = 0
    private var searchQuery = ""
    private var pendingSearchJob: Job? = null
    private var pendingRefreshJob: Job? = null
    private var cachedRulesRef: List<BlockRule> = emptyList()
    private var cachedRulesSignature: Int = 0
    private var cachedGroupedRules = linkedMapOf<String, List<CachedRuleEntry>>()
    private val selectedRulesById = linkedMapOf<String, BlockRule>()
    private val remoteTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importRuleFile(uri)
    }

    private val suspiciousDomainsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshList()
        }
    }

    private val adapter by lazy {
        RuleListAdapter(
            onGroupClick = { vendor -> toggleGroup(vendor) },
            onGroupLongPress = { vendor ->
                val ctx = context ?: return@RuleListAdapter
                val rules = getGroupedRules(ctx)[vendor].orEmpty().map { it.rule }
                enterSelection(rules)
            },
            onDomainClick = { item ->
                if (selectionMode) {
                    toggleSelection(item.rule)
                } else {
                    showRuleActions(item.rule)
                }
            },
            onDomainLongPress = { item ->
                enterSelection(listOf(item.rule))
            },
            onSelectionChanged = { item, checked ->
                if (checked) {
                    selectedIds += item.rule.id
                    selectedRulesById[item.rule.id] = item.rule
                } else {
                    selectedIds -= item.rule.id
                    selectedRulesById.remove(item.rule.id)
                }
                updateSelectionUi()
            }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainActivity = activity as? MainActivity
        _binding = FragmentRulesBinding.bind(view)
        view.findViewById<ImageView>(R.id.rulesBackground).applyCustomAssetBackground("custom/rules_background")
        val initialTopPadding = binding.rulesContent.paddingTop
        val initialBottomPadding = binding.rulesContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.rulesContent) { content, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(
                content.paddingLeft,
                initialTopPadding + systemBars.top,
                content.paddingRight,
                initialBottomPadding + systemBars.bottom
            )
            insets
        }
        binding.ruleList.layoutManager = LinearLayoutManager(requireContext())
        binding.ruleList.adapter = adapter
        binding.ruleList.setHasFixedSize(true)
        binding.ruleList.itemAnimator = null
        binding.btnAddRule.setOnClickListener { addManualRule() }
        binding.inputRule.setOnEditorActionListener { _, actionId, event ->
            val imeTriggered = actionId == EditorInfo.IME_ACTION_DONE
            val enterTriggered = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (imeTriggered || enterTriggered) {
                addManualRule()
                true
            } else {
                false
            }
        }
        binding.btnPasteRule.setOnClickListener { pasteRuleInput() }
        binding.btnClearInput.setOnClickListener { clearRuleInput() }
        binding.btnRuleActions.setOnClickListener { showRuleActionPanel() }
        binding.btnTrafficCard.setOnClickListener { mainActivity?.openTrafficCardPage() }
        binding.btnJoinGroup.setOnClickListener { openRemoteRuleSourcesPage() }
        binding.btnSuspiciousDomains.setOnClickListener { openSuspiciousDomainsPage() }
        binding.btnFilter.setOnClickListener { deduplicateRules() }
        binding.btnRuleSources.setOnClickListener { showImpactNormalNetworkDialog() }
        binding.btnSelectAll.setOnClickListener { selectAllVisible() }
        binding.btnDeleteSelected.setOnClickListener { confirmDelete(selectedRulesById.values) }
        binding.btnCancelSelection.setOnClickListener { exitSelection() }
        binding.inputSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                pendingSearchJob?.cancel()
                pendingSearchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(180)
                    refreshListSoon(0L)
                }
            }
        })
        refreshListDelayed()
    }

    private fun refreshListDelayed() {
        refreshListSoon(200L)
    }

    private fun refreshListSoon(delayMillis: Long = 60L) {
        pendingRefreshJob?.cancel()
        pendingRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMillis)
            if (isAdded && _binding != null && view != null) {
                refreshList()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onDestroyView() {
        pendingSearchJob?.cancel()
        pendingRefreshJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun getGroupedRules(context: Context): LinkedHashMap<String, List<CachedRuleEntry>> {
        val rules = RuleRepository.getRules(context)
        val signature = buildRuleSnapshotSignature(rules)
        if (rules !== cachedRulesRef || signature != cachedRulesSignature) {
            cachedRulesRef = rules
            cachedRulesSignature = signature
            cachedGroupedRules = LinkedHashMap(
                rules.groupBy({ it.vendor }) { rule ->
                    CachedRuleEntry(
                        rule = rule,
                        domainLower = rule.domain.lowercase(),
                        vendorLower = rule.vendor.lowercase(),
                        keywordLower = rule.keywordPattern?.lowercase(),
                        regexLower = rule.regexPattern?.lowercase()
                    )
                }
            )
        }
        return cachedGroupedRules
    }

    private fun buildRuleSnapshotSignature(rules: List<BlockRule>): Int {
        var result = rules.size
        rules.take(64).forEach { rule ->
            result = 31 * result + rule.id.hashCode()
            result = 31 * result + rule.domain.hashCode()
            result = 31 * result + rule.vendor.hashCode()
        }
        return result
    }

    private fun invalidateRuleListCache() {
        cachedRulesRef = emptyList()
        cachedRulesSignature = 0
        cachedGroupedRules = linkedMapOf()
    }

    private fun safeContext(): Context? {
        return context?.takeIf { isAdded && it != null }
    }

    private fun safeDialogActivity(): androidx.fragment.app.FragmentActivity? {
        val host = activity ?: return null
        if (!isAdded || host.isFinishing || host.isDestroyed) return null
        return host
    }

    private fun addManualRule() {
        val ctx = safeContext() ?: return
        val rawInput = binding.inputRule.text?.toString().orEmpty()
        if (rawInput.isBlank()) {
            Toast.makeText(ctx, "请先输入或粘贴域名/规则内容", Toast.LENGTH_SHORT).show()
            return
        }
        val whitelistConflicts = RuleRepository.findWhitelistConflictsInManualInput(rawInput)
        if (whitelistConflicts.isNotEmpty()) {
            showWhitelistConflictDialog(
                title = "发现疑似白名单规则",
                domains = whitelistConflicts,
                onContinue = {
                    val added = RuleRepository.addRules(ctx, rawInput, RuleSource.MANUAL, allowWhitelistDomains = true)
                    handleManualRuleAddResult(ctx, added)
                },
                onDeleteWhitelistAndContinue = {
                    val sanitizedInput = RuleRepository.removeWhitelistConflictLines(rawInput)
                    val added = RuleRepository.addRules(ctx, sanitizedInput, RuleSource.MANUAL)
                    handleManualRuleAddResult(ctx, added)
                }
            )
            return
        }
        val added = RuleRepository.addRules(ctx, rawInput, RuleSource.MANUAL)
        handleManualRuleAddResult(ctx, added)
    }

    private fun handleManualRuleAddResult(ctx: Context, added: List<BlockRule>) {
        if (added.isEmpty()) {
            Toast.makeText(ctx, "未识别到可添加的有效域名，或规则已存在", Toast.LENGTH_SHORT).show()
            return
        }
        binding.inputRule.setText("")
        LogRepository.append(ctx, "Added ${added.size} manual rules")
        Toast.makeText(ctx, "已添加 ${added.size} 条规则", Toast.LENGTH_SHORT).show()
        invalidateRuleListCache()
        refreshListSoon()
        reloadVpnIfRunning(true)
    }

    private fun pasteRuleInput() {
        val ctx = safeContext() ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.let(::coerceClipText).orEmpty().trim()
        if (text.isBlank()) {
            Toast.makeText(ctx, "剪贴板里没有可用内容", Toast.LENGTH_SHORT).show()
            return
        }
        val current = binding.inputRule.text?.toString().orEmpty().trim()
        val merged = if (current.isBlank()) text else "$current\n$text"
        binding.inputRule.setText(merged)
        binding.inputRule.setSelection(merged.length)
        Toast.makeText(ctx, "已粘贴到输入框", Toast.LENGTH_SHORT).show()
    }

    private fun clearRuleInput() {
        val ctx = safeContext() ?: return
        if (binding.inputRule.text.isNullOrBlank()) {
            Toast.makeText(ctx, "输入框已经是空的", Toast.LENGTH_SHORT).show()
            return
        }
        binding.inputRule.setText("")
        Toast.makeText(ctx, "已清空输入内容", Toast.LENGTH_SHORT).show()
    }

    private fun coerceClipText(clipData: ClipData): String? {
        val ctx = context ?: return null
        return buildString {
            for (index in 0 until clipData.itemCount) {
                val text = clipData.getItemAt(index).coerceToText(ctx)?.toString().orEmpty().trim()
                if (text.isBlank()) continue
                if (isNotEmpty()) append('\n')
                append(text)
            }
        }.ifBlank { null }
    }

    private fun showRuleActionPanel() {
        val dialogContext = safeDialogActivity() ?: return
        runCatching {
            createDialogBuilder(dialogContext)
                .setTitle("规则工具")
                .setItems(arrayOf("导入本地规则", "规则源管理", "影响正常网络")) { _, which ->
                    when (which) {
                        0 -> launchImportRulePicker()
                        1 -> openRemoteRuleSourcesPage()
                        2 -> showImpactNormalNetworkDialog()
                    }
                }
                .show()
        }.onFailure {
            val ctx = safeContext() ?: return@onFailure
            LogRepository.append(ctx, "Open rule tool panel failed: ${it.message ?: it.javaClass.simpleName}")
            launchImportRulePicker()
        }
    }

    private fun launchImportRulePicker() {
        if (!isAdded) return
        try {
            importLauncher.launch(arrayOf("text/*", "application/octet-stream", "application/x-yaml", "application/yaml"))
        } catch (e: Exception) {
            val ctx = safeContext() ?: return
            LogRepository.append(ctx, "Launch import picker failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            Toast.makeText(ctx, "无法打开文件选择器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openRemoteRuleSourcesPage() {
        val ctx = safeContext() ?: return
        startActivity(Intent(ctx, RemoteRuleSourcesActivity::class.java))
    }

    private fun showRemoteRuleSourcesDialog() {
        val dialogContext = safeDialogActivity() ?: return
        runCatching {
            val sources = RuleRepository.getRemoteRuleSources(dialogContext)
            val builder = createRemoteRuleSourcesDialogBuilder(dialogContext)
                .setTitle("规则源管理")
                .setNeutralButton("添加规则源") { _, _ ->
                    showAddRemoteRuleSourceDialog()
                }
                .setPositiveButton("立即更新全部") { _, _ ->
                    syncRemoteRuleSources(manual = true)
                }
                .setNegativeButton("关闭", null)
            if (sources.isEmpty()) {
                builder.setMessage("当前还没有规则源，可以先添加一个 Git 规则源。")
            } else {
                val names = sources.map { source ->
                    runCatching { formatRemoteSourceSummary(source) }
                        .getOrElse { formatRemoteSourceTitle(source) }
                }.toTypedArray()
                builder.setItems(names) { _, which ->
                    sources.getOrNull(which)?.let(::showRemoteRuleSourceActions)
                }
            }
            val dialog = builder.show()
            styleDialogButtons(dialog)
        }.onFailure {
            val ctx = safeContext() ?: return@onFailure
            LogRepository.append(ctx, "Open remote rule source dialog failed: ${it.message ?: it.javaClass.simpleName}\nStack: ${it.stackTraceToString()}")
            openRemoteRuleSourcesDialogFallback()
        }
    }

    private fun openRemoteRuleSourcesDialogFallback() {
        val dialogContext = safeDialogActivity() ?: return
        runCatching {
            val sources = RuleRepository.getRemoteRuleSources(dialogContext)
            val builder = AlertDialog.Builder(dialogContext)
                .setTitle("规则源管理")
                .setNeutralButton("添加规则源") { _, _ ->
                    showAddRemoteRuleSourceDialog()
                }
                .setPositiveButton("立即更新全部") { _, _ ->
                    syncRemoteRuleSources(manual = true)
                }
                .setNegativeButton("关闭", null)
            if (sources.isEmpty()) {
                builder.setMessage("当前还没有规则源，可以先添加一个 Git 规则源。")
            } else {
                val names = sources.map { source ->
                    runCatching { formatRemoteSourceSummary(source) }
                        .getOrElse { formatRemoteSourceTitle(source) }
                }.toTypedArray()
                builder.setItems(names) { _, which ->
                    sources.getOrNull(which)?.let(::showRemoteRuleSourceActions)
                }
            }
            builder.show()
        }.onFailure {
            val ctx = safeContext() ?: return@onFailure
            LogRepository.append(ctx, "Open remote rule source dialog fallback failed: ${it.message ?: it.javaClass.simpleName}\nStack: ${it.stackTraceToString()}")
            Toast.makeText(ctx, "规则源页面打开失败，请重启后重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createRemoteRuleSourcesDialogBuilder(dialogContext: androidx.fragment.app.FragmentActivity): AlertDialog.Builder {
        return runCatching {
            MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_HanFeng_Dialog)
        }.getOrElse {
            AlertDialog.Builder(dialogContext)
        }
    }

    private fun showRemoteRuleSourceActions(source: RemoteRuleSourceConfig) {
        val dialogContext = safeDialogActivity() ?: return
        val toggleLabel = if (source.enabled) "停用规则源" else "启用规则源"
        runCatching {
            val dialog = createDialogBuilder(dialogContext)
                .setTitle(formatRemoteSourceTitle(source))
                .setMessage(buildString {
                    append(source.url)
                    source.authorId?.takeIf { it.isNotBlank() }?.let {
                        append("\n作者 ID：@")
                        append(it)
                    }
                    append("\n\n")
                    append("状态：")
                    append(if (source.enabled) "已启用" else "已停用")
                    append("\n上次更新时间：")
                    append(formatRemoteUpdatedAt(source.lastUpdatedAt))
                    append("\n当前规则数：")
                    append(source.lastRuleCount)
                    source.lastError?.takeIf { it.isNotBlank() }?.let {
                        append("\n最近错误：")
                        append(it)
                    }
                })
                .setItems(arrayOf(toggleLabel, "立即更新")) { _, which ->
                    when (which) {
                        0 -> {
                            val enabling = !source.enabled
                            RuleRepository.updateRemoteRuleSource(dialogContext, source.copy(enabled = enabling, lastError = null))
                            if (enabling) {
                                syncRemoteRuleSources(source.id, manual = true)
                            } else {
                                val removedCount = RuleRepository.removeRulesForRemoteSource(dialogContext, source.id)
                                invalidateRuleListCache()
                                refreshListSoon()
                                reloadVpnIfRunning(removedCount > 0)
                                Toast.makeText(dialogContext, "规则源已停用并移除对应规则", Toast.LENGTH_SHORT).show()
                            }
                        }
                        1 -> syncRemoteRuleSources(source.id, manual = true)
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
            styleDialogButtons(dialog)
        }.onFailure {
            val ctx = safeContext() ?: return@onFailure
            LogRepository.append(ctx, "Open remote rule source action dialog failed: ${it.message ?: it.javaClass.simpleName}\nStack: ${it.stackTraceToString()}")
            Toast.makeText(ctx, "规则源详情打开失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddRemoteRuleSourceDialog() {
        val dialogContext = safeDialogActivity() ?: return
        val nameInput = EditText(dialogContext).apply {
            hint = "规则源名称"
            setSingleLine()
        }
        createDialogBuilder(dialogContext)
            .setTitle("添加规则源")
            .setMessage("先输入规则源名称，下一步再输入规则源地址。")
            .setView(nameInput)
            .setPositiveButton("下一步") { _, _ ->
                val name = nameInput.text?.toString().orEmpty().trim()
                if (name.isBlank()) {
                    Toast.makeText(dialogContext, "请输入规则源名称", Toast.LENGTH_SHORT).show()
                    showAddRemoteRuleSourceDialog()
                } else {
                    showAddRemoteRuleSourceUrlDialog(name)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddRemoteRuleSourceUrlDialog(name: String) {
        val dialogContext = safeDialogActivity() ?: return
        val urlInput = EditText(dialogContext).apply {
            hint = "https://example.com/rules.txt"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        createDialogBuilder(dialogContext)
            .setTitle("规则源地址")
            .setMessage("添加后会立即同步。命中当前保护白名单的规则会先提示你确认，确认后也会继续参与拦截。")
            .setView(urlInput)
            .setPositiveButton("添加并同步") { _, _ ->
                val url = urlInput.text?.toString().orEmpty().trim()
                when {
                    url.isBlank() -> {
                        Toast.makeText(dialogContext, "请输入规则源地址", Toast.LENGTH_SHORT).show()
                        showAddRemoteRuleSourceUrlDialog(name)
                    }
                    !url.startsWith("https://") && !url.startsWith("http://") -> {
                        Toast.makeText(dialogContext, "规则源地址格式不正确", Toast.LENGTH_SHORT).show()
                        showAddRemoteRuleSourceUrlDialog(name)
                    }
                    RuleRepository.getRemoteRuleSources(dialogContext).any { it.url.equals(url, ignoreCase = true) } -> {
                        Toast.makeText(dialogContext, "这个规则源已经添加过了", Toast.LENGTH_SHORT).show()
                    }
                    else -> addRemoteRuleSource(name, url)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addRemoteRuleSource(name: String, url: String) {
        val ctx = safeContext() ?: return
        val source = RemoteRuleSourceConfig(
            id = "custom-${UUID.randomUUID()}",
            name = name,
            url = url,
            enabled = true
        )
        RuleRepository.addRemoteRuleSource(ctx, source)
        syncRemoteRuleSources(source.id, manual = true, justAdded = true)
    }

    private fun syncRemoteRuleSources(
        sourceId: String? = null,
        manual: Boolean = false,
        justAdded: Boolean = false,
        allowWhitelistDomains: Boolean = false
    ) {
        val whitelistImportMode = if (allowWhitelistDomains) {
            RemoteRuleSourceRepository.WhitelistImportMode.ALLOW
        } else {
            RemoteRuleSourceRepository.WhitelistImportMode.BLOCK
        }
        syncRemoteRuleSources(sourceId, manual, justAdded, whitelistImportMode)
    }

    private fun syncRemoteRuleSources(
        sourceId: String? = null,
        manual: Boolean = false,
        justAdded: Boolean = false,
        whitelistImportMode: RemoteRuleSourceRepository.WhitelistImportMode
    ) {
        val ctx = safeContext() ?: return
        if (manual && isAdded) {
            Toast.makeText(ctx, if (justAdded) "正在添加并同步规则源" else "正在同步规则源", Toast.LENGTH_SHORT).show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = ctx.applicationContext
            runCatching {
                val results = if (sourceId == null) {
                    RemoteRuleSourceRepository.syncEnabledSources(appContext, whitelistImportMode)
                } else {
                    val source = RuleRepository.getRemoteRuleSources(appContext).firstOrNull { it.id == sourceId }
                        ?: throw IllegalStateException("规则源不存在")
                    listOf(RemoteRuleSourceRepository.syncSource(appContext, source, whitelistImportMode))
                }
                results
            }.onSuccess { results ->
                val whitelistConflicts = results.filter { it.whitelistConflictRules > 0 }
                if (whitelistConflicts.isNotEmpty() && whitelistImportMode == RemoteRuleSourceRepository.WhitelistImportMode.BLOCK) {
                    showRemoteSourceWhitelistConflictDialog(sourceId, manual, justAdded, whitelistConflicts)
                    return@onSuccess
                }
                reloadVpnIfRunning(results.any { it.success })
                invalidateRuleListCache()
                refreshList()
                if (manual && isAdded) {
                    val successCount = results.count { result -> result.success }
                    val conflictCount = results.sumOf { result -> result.whitelistConflictRules }
                    if (successCount == 0) {
                        val firstError = results.firstOrNull()?.errorMessage ?: "规则源更新失败"
                        Toast.makeText(ctx, firstError, Toast.LENGTH_LONG).show()
                    } else {
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
                        Toast.makeText(ctx, "规则源更新完成，成功 $successCount / ${results.size}$suffix", Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure {
                LogRepository.append(appContext, "Remote rule source sync failed from rules page: ${it.message ?: it.javaClass.simpleName}")
                if (manual) {
                    Toast.makeText(ctx, it.message ?: "规则源更新失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun createDialogBuilder(dialogContext: androidx.fragment.app.FragmentActivity): AlertDialog.Builder {
        return runCatching {
            MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_HanFeng_Dialog)
        }.getOrElse {
            AlertDialog.Builder(dialogContext)
        }
    }

    private fun showRemoteSourceWhitelistConflictDialog(
        sourceId: String?,
        manual: Boolean,
        justAdded: Boolean,
        results: List<RemoteRuleSourceRepository.RemoteRuleSyncResult>
    ) {
        val dialogContext = safeDialogActivity() ?: return
        val message = buildString {
            append("以下规则源包含命中当前保护白名单的规则，继续拦截可能影响应用正常功能：\n\n")
            results.forEachIndexed { index, result ->
                if (index > 0) append("\n\n")
                append(result.source.name)
                append("：")
                append(result.whitelistConflictRules)
                append(" 条\n")
                result.whitelistConflictSamples.take(8).forEach { sample ->
                    append("- ")
                    append(sample)
                    append('\n')
                }
            }
            append("\n你可以直接继续拦截，也可以只删除这些白名单候选，其余规则照常导入。")
        }
        createDialogBuilder(dialogContext)
            .setTitle("发现疑似白名单规则")
            .setMessage(message)
            .setPositiveButton("继续拦截") { _, _ ->
                syncRemoteRuleSources(sourceId, manual, justAdded, RemoteRuleSourceRepository.WhitelistImportMode.ALLOW)
            }
            .setNeutralButton("删除白名单后继续") { _, _ ->
                syncRemoteRuleSources(sourceId, manual, justAdded, RemoteRuleSourceRepository.WhitelistImportMode.REMOVE_CONFLICTS)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatRemoteSourceSummary(source: RemoteRuleSourceConfig): String {
        return buildString {
            append(formatRemoteSourceTitle(source))
            append(if (source.enabled) " [已启用]" else " [已停用]")
            append("\n上次更新时间：")
            append(formatRemoteUpdatedAt(source.lastUpdatedAt))
            append("\n规则数：")
            append(source.lastRuleCount)
            source.lastError?.takeIf { it.isNotBlank() }?.let {
                append("\n错误：")
                append(it)
            }
        }
    }

    private fun formatRemoteUpdatedAt(timestamp: Long): String {
        if (timestamp <= 0L) return "未同步"
        return runCatching { remoteTimeFormatter.format(Date(timestamp)) }.getOrElse { "未同步" }
    }

    private fun formatRemoteSourceTitle(source: RemoteRuleSourceConfig): String {
        val authorId = source.authorId?.trim().orEmpty()
        return if (authorId.isEmpty()) source.name else "${source.name} (@$authorId)"
    }

    private fun spaceView(context: Context, heightDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightDp.dp(context))
        }
    }

    private fun Int.dp(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    private fun toggleGroup(vendor: String) {
        if (!expandedGroups.add(vendor)) expandedGroups.remove(vendor)
        refreshList()
    }

    private fun refreshList() {
        if (!isAdded || _binding == null) return
        val appContext = context?.applicationContext ?: return
        val currentVersion = ++refreshVersion
        val expandedSnapshot = expandedGroups.toSet()
        val selectedSnapshot = selectedIds.toSet()
        val currentSelectionMode = selectionMode
        val query = searchQuery.lowercase()
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.Default) {
                val grouped = getGroupedRules(appContext)
                val items = buildList {
                    grouped.forEach { (vendor, groupRules) ->
                        val vendorLower = groupRules.firstOrNull()?.vendorLower ?: vendor.lowercase()
                        val matchesVendor = query.isEmpty() || vendorLower.contains(query)
                        val filteredRules = if (query.isEmpty()) {
                            groupRules
                        } else {
                            groupRules.filter {
                                it.domainLower.contains(query) ||
                                    it.vendorLower.contains(query) ||
                                    it.keywordLower?.contains(query) == true ||
                                    it.regexLower?.contains(query) == true
                            }
                        }
                        if (filteredRules.isNotEmpty() || matchesVendor) {
                            val autoExpand = query.isNotEmpty()
                            add(RuleListItem.Group(vendor, filteredRules.size, if (autoExpand) true else expandedSnapshot.contains(vendor)))
                            val visibleRules = if (autoExpand) filteredRules else if (expandedSnapshot.contains(vendor)) filteredRules else emptyList()
                            val maxVisible = 500
                            if (visibleRules.size > maxVisible) {
                                visibleRules.take(maxVisible).forEach { entry ->
                                    add(RuleListItem.Domain(entry.rule, selectedSnapshot.contains(entry.rule.id), currentSelectionMode))
                                }
                                add(RuleListItem.More(vendor, visibleRules.size - maxVisible))
                            } else {
                                visibleRules.forEach { entry ->
                                    add(RuleListItem.Domain(entry.rule, selectedSnapshot.contains(entry.rule.id), currentSelectionMode))
                                }
                            }
                        }
                    }
                }
                val inventory = RuleRepository.getRuleInventory(appContext)
                RuleListState(inventory, items)
            }
            if (_binding == null || currentVersion != refreshVersion) return@launch
            binding.ruleSummary.text = buildString {
                if (query.isNotEmpty()) {
                    append("搜索 ${state.items.count { it is RuleListItem.Domain }} 条结果")
                } else {
                    append("已保存 ${state.inventory.totalSavedCount} 条规则")
                    append("  当前可拦截 ${state.inventory.totalSupportedCount} 条")
                    append("  用户导入 ${state.inventory.importedCount} 条")
                    if (state.inventory.manualCount > 0) append("  手动 ${state.inventory.manualCount} 条")
                    if (state.inventory.regexCount > 0) append("  正则 ${state.inventory.regexCount} 条")
                    if (state.inventory.cosmeticCount > 0) append("  Cosmetic ${state.inventory.cosmeticCount} 条")
                }
            }
            binding.selectionBar.isVisible = currentSelectionMode
            binding.selectionCount.text = if (filteredSelectionMode) {
                "已筛出 ${selectedIds.size} 项，可取消勾选后点删除所选"
            } else {
                "已选择 ${selectedIds.size} 项"
            }
            binding.btnDeleteSelected.text = if (filteredSelectionMode) "删除筛出项" else "删除所选"
            adapter.submit(state.items)
        }
    }

    private fun reloadVpnIfRunning(shouldReload: Boolean) {
        val ctx = context ?: return
        if (!shouldReload || !AdBlockVpnService.isRunning) return
        ctx.startService(Intent(ctx, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
    }

    private fun updateSelectionUi() {
        if (_binding == null) return
        binding.selectionBar.isVisible = selectionMode
        binding.selectionCount.text = if (filteredSelectionMode) {
            "已筛出 ${selectedIds.size} 项，可取消勾选后点删除所选"
        } else {
            "已选择 ${selectedIds.size} 项"
        }
        binding.btnDeleteSelected.text = if (filteredSelectionMode) "删除筛出项" else "删除所选"
    }

    private fun enterSelection(initialRules: Collection<BlockRule>) {
        selectionMode = true
        filteredSelectionMode = false
        initialRules.forEach { rule ->
            selectedIds += rule.id
            selectedRulesById[rule.id] = rule
        }
        refreshList()
    }

    private fun enterFilteredSelection(initialIds: Collection<String>) {
        selectionMode = true
        filteredSelectionMode = true
        selectedIds.clear()
        selectedRulesById.clear()
        selectedIds += initialIds
        refreshList()
    }

    private fun exitSelection() {
        selectionMode = false
        filteredSelectionMode = false
        selectedIds.clear()
        selectedRulesById.clear()
        refreshList()
    }

    private fun toggleSelection(rule: BlockRule) {
        if (!selectedIds.add(rule.id)) {
            selectedIds.remove(rule.id)
            selectedRulesById.remove(rule.id)
        } else {
            selectedRulesById[rule.id] = rule
        }
        refreshList()
    }

    private fun selectAllVisible() {
        visibleRules().forEach { rule ->
            selectedIds += rule.id
            selectedRulesById[rule.id] = rule
        }
        refreshList()
    }

    private fun confirmDelete(rules: Collection<BlockRule>) {
        val snapshot = rules.distinctBy { it.id.ifBlank { it.domain } }
        if (snapshot.isEmpty()) return
        val dialogContext = safeDialogActivity() ?: return
        try {
            createDialogBuilder(dialogContext)
                .setTitle("确认删除")
                .setMessage("确定删除这 ${snapshot.size} 条规则吗？删除后无法恢复。")
                .setPositiveButton("删除") { _, _ ->
                    val actionContext = context ?: return@setPositiveButton
                    val removedCount = RuleRepository.removeRules(actionContext, snapshot)
                    LogRepository.append(actionContext, "Requested remove=${snapshot.size} actualRemoved=$removedCount")
                    if (removedCount <= 0) {
                        Toast.makeText(actionContext, "未删除任何规则，旧规则数据已自动修复，请重试一次", Toast.LENGTH_SHORT).show()
                        invalidateRuleListCache()
                        refreshListSoon()
                        return@setPositiveButton
                    }
                    Toast.makeText(actionContext, "已删除 $removedCount 条规则", Toast.LENGTH_SHORT).show()
                    invalidateRuleListCache()
                    exitSelection()
                    refreshListSoon()
                    reloadVpnIfRunning(true)
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            LogRepository.append(dialogContext, "Delete dialog failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            Toast.makeText(dialogContext, "打开删除确认失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deduplicateRules() {
        val actionContext = safeContext() ?: return
        try {
            val removed = RuleRepository.deduplicateRules(actionContext)
            if (removed == 0) {
                Toast.makeText(actionContext, "没有检测到重复规则", Toast.LENGTH_SHORT).show()
                return
            }
            LogRepository.append(actionContext, "Removed $removed duplicate rules")
            invalidateRuleListCache()
            refreshListSoon()
            reloadVpnIfRunning(true)
            Toast.makeText(actionContext, "已清理 $removed 条重复规则", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogRepository.append(actionContext, "Deduplicate rules failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            Toast.makeText(actionContext, "清理失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun filterNonAds() {
        showImpactNormalNetworkDialog()
    }

    private fun openSuspiciousDomainsPage() {
        val host = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    RuleRepository.getSuspiciousDomainSamples(host.applicationContext)
                }
            }.onSuccess { samples ->
                val currentHost = activity as? MainActivity ?: return@onSuccess
                if (!isAdded) return@onSuccess
                if (samples.isEmpty()) {
                    Toast.makeText(currentHost, "当前没有需要分析的疑似广告域名", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                suspiciousDomainsLauncher.launch(SuspiciousDomainsActivity.createIntent(currentHost))
            }.onFailure {
                val currentHost = activity as? MainActivity ?: host
                LogRepository.append(currentHost, "Open suspicious domains page failed: ${it.message ?: it.javaClass.simpleName}\nStack: ${it.stackTraceToString()}")
                if (isAdded) {
                    Toast.makeText(currentHost, "打开分析页面失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showRuleActions(rule: BlockRule) {
        val dialogContext = safeDialogActivity() ?: return
        try {
            createDialogBuilder(dialogContext)
                .setTitle(rule.domain)
                .setItems(arrayOf("手动分类", "删除规则")) { _, which ->
                    when (which) {
                        0 -> showVendorPicker(rule)
                        1 -> confirmDelete(listOf(rule))
                    }
                }
                .show()
        } catch (e: Exception) {
            LogRepository.append(dialogContext, "Open rule action dialog failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            Toast.makeText(dialogContext, "打开规则操作失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showVendorPicker(rule: BlockRule) {
        val dialogContext = safeDialogActivity() ?: return
        val options = RuleRepository.availableVendors(dialogContext)
        runCatching {
            createDialogBuilder(dialogContext)
                .setTitle("选择厂商分组")
                .setItems((options + "新建分组").toTypedArray()) { _, which ->
                    if (which == options.size) {
                        showCreateVendorDialog(rule)
                    } else {
                        val actionContext = context ?: return@setItems
                        RuleRepository.updateRuleVendor(actionContext, rule.id, options[which])
                        refreshList()
                    }
                }
                .show()
        }.onFailure {
            LogRepository.append(dialogContext, "Open vendor picker failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(dialogContext, "打开分组选择失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateVendorDialog(rule: BlockRule) {
        val dialogContext = safeDialogActivity() ?: return
        val input = EditText(dialogContext).apply {
            hint = "例如：腾讯 (Tencent)"
            setText(rule.vendor)
            background = ContextCompat.getDrawable(dialogContext, R.drawable.bg_panel)
            setTextColor(ContextCompat.getColor(dialogContext, R.color.hf_text_primary))
            setHintTextColor(ContextCompat.getColor(dialogContext, R.color.hf_text_secondary))
            setPadding(24, 20, 24, 20)
        }
        val dialog = createDialogBuilder(dialogContext)
            .setTitle("新建分组")
            .setView(input)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val vendor = input.text?.toString().orEmpty().trim()
                if (vendor.isBlank()) {
                    Toast.makeText(dialogContext, "分组名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val actionContext = context ?: return@setOnClickListener
                RuleRepository.updateRuleVendor(actionContext, rule.id, vendor)
                refreshList()
                dialog.dismiss()
            }
        }
        runCatching {
            dialog.show()
            styleDialogButtons(dialog)
        }.onFailure {
            LogRepository.append(dialogContext, "Open create vendor dialog failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(dialogContext, "打开新建分组失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importRuleFile(uri: Uri) {
        val ctx = safeContext() ?: return
        val appContext = ctx.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val content = readRuleContent(appContext, uri)
                if (content == null) {
                    safeContext()?.let {
                        Toast.makeText(it, "读取规则文件失败", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                importAndAnalyzeRuleContent(uri, content)
            }.onFailure {
                LogRepository.append(appContext, "Import rule file failed: ${it.message ?: it.javaClass.simpleName}")
                if (isAdded) {
                    safeContext()?.let {
                        Toast.makeText(it, "导入规则失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private suspend fun readRuleContent(context: android.content.Context, uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val contentResolver = context.contentResolver
            val candidates = listOfNotNull(
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull(),
                readRuleContentFromTypedAsset(contentResolver, uri, "text/plain"),
                readRuleContentFromTypedAsset(contentResolver, uri, "*/*")
            )
            candidates.firstOrNull { it.isNotBlank() }
        }
    }

    private fun readRuleContentFromTypedAsset(contentResolver: ContentResolver, uri: Uri, mimeType: String): String? {
        return runCatching {
            contentResolver.openTypedAssetFileDescriptor(uri, mimeType, null)?.createInputStream()?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    private fun showImpactNormalNetworkDialog() {
        val ctx = safeDialogActivity() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val candidates = withContext(Dispatchers.Default) {
                    RuleRepository.getImpactNormalNetworkCandidates(ctx)
                }
                if (!isAdded || _binding == null) return@launch
                if (candidates.isEmpty()) {
                    Toast.makeText(ctx, "当前没有识别到明显影响正常网络的规则", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val displayCandidates = candidates.take(180)
                val selectedRules = linkedMapOf<String, BlockRule>().apply {
                    displayCandidates.forEach { candidate ->
                        put(candidate.rule.id, candidate.rule)
                    }
                }
                val items = displayCandidates.map { candidate ->
                    val domain = candidate.rule.domain.ifBlank { "(未识别域名)" }
                    val reason = candidate.reasons.joinToString("；")
                    "$domain\n$reason"
                }.toTypedArray()
                val checked = BooleanArray(displayCandidates.size) { true }
                val title = if (candidates.size > displayCandidates.size) {
                    "影响正常网络（仅显示前 ${displayCandidates.size} 条）"
                } else {
                    "影响正常网络"
                }
                createDialogBuilder(ctx)
                    .setTitle(title)
                    .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                        val rule = displayCandidates.getOrNull(which)?.rule ?: return@setMultiChoiceItems
                        if (isChecked) {
                            selectedRules[rule.id] = rule
                        } else {
                            selectedRules.remove(rule.id)
                        }
                    }
                    .setPositiveButton("删除所选") { _, _ ->
                        val actionContext = safeContext() ?: ctx
                        if (selectedRules.isEmpty()) {
                            Toast.makeText(actionContext, "请至少选择一条规则", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val removedCount = RuleRepository.removeRules(actionContext, selectedRules.values)
                        LogRepository.append(actionContext, "Requested remove impact-network candidates=${selectedRules.size} actualRemoved=$removedCount")
                        if (removedCount <= 0) {
                            Toast.makeText(actionContext, "未删除任何规则，旧规则数据已自动兼容，请重试一次", Toast.LENGTH_SHORT).show()
                            invalidateRuleListCache()
                            refreshListSoon()
                            return@setPositiveButton
                        }
                        Toast.makeText(actionContext, "已删除 $removedCount 条规则", Toast.LENGTH_SHORT).show()
                        invalidateRuleListCache()
                        refreshListSoon()
                        reloadVpnIfRunning(true)
                    }
                    .setNegativeButton("保留全部", null)
                    .show()
            }.onFailure {
                val actionContext = safeContext() ?: return@onFailure
                LogRepository.append(actionContext, "Open impact-normal-network dialog failed: ${it.message ?: it.javaClass.simpleName}\nStack: ${it.stackTraceToString()}")
                Toast.makeText(actionContext, "影响正常网络页面打开失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun importAndAnalyzeRuleContent(uri: Uri, content: String) {
        importAndAnalyzeRuleContent(sourceLabel = uri.toString(), sourceUri = uri, content = content)
    }

    private suspend fun importAndAnalyzeRuleContent(sourceLabel: String, sourceUri: Uri, content: String) {
        val appContext = context?.applicationContext ?: return
        val report = withContext(Dispatchers.Default) {
            RuleRepository.analyzeImportContent(appContext, content)
        }
        if (report.safeRuleCount <= 0) {
            if (!isAdded || _binding == null) return
            context?.let {
                Toast.makeText(it, "文件里没有识别到可导入规则", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (report.whitelistConflictRules > 0) {
            if (!isAdded || _binding == null) return
            val host = safeDialogActivity() ?: return
            showWhitelistConflictDialog(
                title = "发现疑似白名单规则",
                domains = report.sampleWhitelistConflictLines,
                onContinue = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.Default) {
                            RuleRepository.importRules(appContext, content, allowWhitelistDomains = true)
                        }
                        val inventory = withContext(Dispatchers.Default) {
                            RuleRepository.getRuleInventory(appContext)
                        }
                        if (!isAdded || _binding == null) return@launch
                        LogRepository.append(appContext, "Imported rules from $sourceLabel with whitelist conflicts accepted")
                        invalidateRuleListCache()
                        refreshListSoon()
                        reloadVpnIfRunning(true)
                        context?.let {
                            Toast.makeText(it, "规则已导入，正在展示分析结果", Toast.LENGTH_SHORT).show()
                        }
                        openImportAnalysisPage(inventory, report, sourceLabel, sourceUri)
                    }
                },
                onDeleteWhitelistAndContinue = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val sanitizedContent = withContext(Dispatchers.Default) {
                            RuleRepository.removeWhitelistConflictLines(content)
                        }
                        withContext(Dispatchers.Default) {
                            RuleRepository.importRules(appContext, sanitizedContent)
                        }
                        val inventory = withContext(Dispatchers.Default) {
                            RuleRepository.getRuleInventory(appContext)
                        }
                        val sanitizedReport = withContext(Dispatchers.Default) {
                            RuleRepository.analyzeImportContent(appContext, sanitizedContent)
                        }
                        if (!isAdded || _binding == null) return@launch
                        LogRepository.append(appContext, "Imported rules from $sourceLabel after removing whitelist conflict lines")
                        invalidateRuleListCache()
                        refreshListSoon()
                        reloadVpnIfRunning(true)
                        context?.let {
                            Toast.makeText(it, "已删除白名单候选，其余规则已导入", Toast.LENGTH_SHORT).show()
                        }
                        openImportAnalysisPage(inventory, sanitizedReport, sourceLabel, sourceUri)
                    }
                },
                host = host
            )
            return
        }
        withContext(Dispatchers.Default) {
            RuleRepository.importRules(appContext, content)
        }
        val inventory = withContext(Dispatchers.Default) {
            RuleRepository.getRuleInventory(appContext)
        }
        if (!isAdded || _binding == null) return
        LogRepository.append(appContext, "Imported rules from $sourceLabel")
        invalidateRuleListCache()
        refreshListSoon()
        reloadVpnIfRunning(true)
        context?.let {
            Toast.makeText(it, "规则已导入，正在展示分析结果", Toast.LENGTH_SHORT).show()
        }
        openImportAnalysisPage(inventory, report, sourceLabel, sourceUri)
    }

    private fun openImportAnalysisPage(
        inventory: RuleRepository.RuleInventory,
        report: RuleRepository.RuleAnalysisReport,
        sourceLabel: String,
        sourceUri: Uri
    ) {
        val host = activity as? MainActivity ?: return
        if (!isAdded || host.isFinishing || host.isDestroyed) return
        val content = buildString {
            append("来源：")
            append(sourceLabel)
            append("\n地址：")
            append(sourceUri)
            append("\n\n")
            append("导入完成，当前可拦截 ")
            append(inventory.totalSupportedCount)
            append(" 条规则")
            append("\n\n")
            append(buildAnalysisSummary(report))
            if (report.sampleUnsupportedLines.isNotEmpty() || report.sampleInvalidLines.isNotEmpty()) {
                append("\n\n")
                append(buildAnalysisSamples(report))
            }
        }
        runCatching {
            if (host.isFinishing || host.isDestroyed) {
                LogRepository.append(host, "Skip import analysis page: activity not ready")
                return
            }
            host.startActivity(GuideActivity.createIntent(host, "导入结果分析", content))
        }.onFailure {
            LogRepository.append(host, "Open import analysis failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(host, "规则已导入，请稍后重试查看分析结果", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAnalysisSamples(report: RuleRepository.RuleAnalysisReport): String {
        return buildString {
            if (report.sampleWhitelistConflictLines.isNotEmpty()) {
                append("疑似白名单规则示例：\n")
                append(report.sampleWhitelistConflictLines.joinToString("\n"))
            }
            if (report.sampleUnsupportedLines.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("当前仍未支持的规则示例：\n")
                append(report.sampleUnsupportedLines.joinToString("\n"))
            }
            if (report.sampleInvalidLines.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("无法识别的规则示例：\n")
                append(report.sampleInvalidLines.joinToString("\n"))
            }
        }
    }

    private fun buildAnalysisSummary(report: RuleRepository.RuleAnalysisReport): String {
        return buildString {
            append("本次文件总行数：${report.totalLines}\n")
            append("当前已有规则：${report.existingRules}\n")
            append("本次识别到可处理拦截规则：${report.safeBlockedRules} 条\n")
            append("本次识别到例外规则：${report.safeExceptionRules} 条\n")
            append("例外规则预计影响旧规则：${report.exceptionRemovalEstimate} 条\n")
            append("分析后预计规则总数：${report.estimatedFinalRules}\n\n")
            append("已跳过或已合并统计：\n")
            append("- 重复现有规则：${report.duplicateExistingRules}\n")
            append("- 文件内重复：${report.duplicateWithinFileRules}\n")
            append("- 当前仍未支持修饰符：${report.unsupportedModifierRules}\n")
            append("- 疑似白名单规则：${report.whitelistConflictRules}\n")
            append("- Cosmetic 规则：${report.cosmeticRules}\n")
            append("- 正则规则：${report.regexRules}\n")
            append("- 空行/注释：${report.blankOrCommentLines}\n")
            append("- 无法识别：${report.invalidRules}")
            append("\n\n说明：\n")
            append("1. 当前稳定支持：纯域名、Hosts、dnsmasq/SmartDNS/OpenWrt 域名规则、AdGuard/ABP 域名型规则、IP-CIDR、IP-CIDR6、携带 dst-port/src-port 的 IP 规则、域名加端口规则、*:41826\$network 这类端口专用规则。\n")
            append("2. 当前增强支持：URL-KEYWORD、URL-REGEX、path=、部分 regex、部分请求上下文规则、部分应用上下文规则、部分 cosmetic 规则。此类规则通常依赖 MITM 增强过滤。\n")
            append("3. 当前会跳过或部分跳过：复杂脚本、完整浏览器语义规则、远程脚本、复杂逻辑组合和无法安全降级的高级代理规则。")
            if (report.vendorSummary.isNotEmpty()) {
                append("\n\n可导入规则厂商分布：\n")
                append(report.vendorSummary.joinToString("\n") { "- ${it.vendor}: ${it.count}" })
            }
        }
    }

    private fun visibleRules(): List<BlockRule> {
        val ctx = context ?: return emptyList()
        val grouped = getGroupedRules(ctx)
        val query = searchQuery.lowercase()
        return if (query.isEmpty()) {
            grouped.values.asSequence().flatten().map { it.rule }.take(500).toList()
        } else {
            grouped.values.asSequence()
                .flatten()
                .filter {
                    it.domainLower.contains(query) ||
                        it.vendorLower.contains(query) ||
                        it.keywordLower?.contains(query) == true ||
                        it.regexLower?.contains(query) == true
                }
                .map { it.rule }
                .take(500)
                .toList()
        }
    }

    private fun styleDialogButtons(dialog: AlertDialog) {
        val buttonBackground = ContextCompat.getDrawable(dialog.context, R.drawable.bg_button)
        val textColor = ContextCompat.getColor(dialog.context, R.color.hf_text_primary)
        listOf(
            AlertDialog.BUTTON_POSITIVE,
            AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL
        ).forEach { which ->
            dialog.getButton(which)?.apply {
                backgroundTintList = null
                setBackground(buttonBackground?.constantState?.newDrawable()?.mutate())
                setTextColor(textColor)
            }
        }
    }

    private fun showWhitelistConflictDialog(
        title: String,
        domains: List<String>,
        onContinue: () -> Unit,
        onDeleteWhitelistAndContinue: () -> Unit,
        host: androidx.fragment.app.FragmentActivity? = safeDialogActivity()
    ) {
        val activityHost = host ?: return
        if (!isAdded || activityHost.isFinishing || activityHost.isDestroyed) return
        val sampleText = domains.take(12).joinToString("\n") { "- $it" }
        val message = buildString {
            append("以下规则命中了当前保护白名单，继续拦截可能影响应用正常功能：\n\n")
            append(sampleText)
            append("\n\n你可以直接继续拦截，也可以只删除这些白名单候选，其余规则照常导入。")
        }
        MaterialAlertDialogBuilder(activityHost)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("继续拦截") { _, _ -> onContinue() }
            .setNeutralButton("删除白名单后继续") { _, _ -> onDeleteWhitelistAndContinue() }
            .setNegativeButton("取消", null)
            .show()
    }

    private data class RuleListState(
        val inventory: RuleRepository.RuleInventory,
        val items: List<RuleListItem>
    )

    private data class CachedRuleEntry(
        val rule: BlockRule,
        val domainLower: String,
        val vendorLower: String,
        val keywordLower: String?,
        val regexLower: String?
    )

    private class FilterRuleAdapter(
        private val rules: List<BlockRule>,
        selectedIds: Set<String>,
        private val onCheckedChanged: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<FilterRuleAdapter.ViewHolder>() {
        private val checkedIds = selectedIds.toMutableSet()

        fun setAllChecked(checked: Boolean) {
            checkedIds.clear()
            if (checked) {
                checkedIds += rules.map { it.id }
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemRuleDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun getItemCount(): Int = rules.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(rules[position])
        }

        inner class ViewHolder(private val binding: ItemRuleDomainBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(rule: BlockRule) {
                binding.domainText.text = rule.domain
                binding.sourceTag.text = rule.vendor
                binding.sourceTag.visibility = View.VISIBLE
                binding.selectBox.visibility = View.VISIBLE
                binding.selectBox.setOnCheckedChangeListener(null)
                binding.selectBox.isChecked = checkedIds.contains(rule.id)
                binding.root.alpha = if (binding.selectBox.isChecked) 0.78f else 1f
                binding.root.setOnClickListener {
                    binding.selectBox.toggle()
                }
                binding.selectBox.setOnCheckedChangeListener { _, checked ->
                    if (checked) checkedIds += rule.id else checkedIds -= rule.id
                    binding.root.alpha = if (checked) 0.78f else 1f
                    onCheckedChanged(rule.id, checked)
                }
            }
        }
    }
}
