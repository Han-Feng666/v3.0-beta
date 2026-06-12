package com.HanFeng.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.content.ContentResolver
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.HanFeng.core.network.NetworkKernel
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
import com.HanFeng.ui.SuspiciousDomainsActivity
import com.HanFeng.model.RuleSource
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

    private data class ProgressDialogHandle(
        val dialog: AlertDialog,
        val textView: TextView
    )

    private fun showShortToast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    private fun showProgressDialog(title: String, initialMessage: String): ProgressDialogHandle? {
        val dialogContext = safeDialogActivity() ?: return null
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val progressBar = ProgressBar(dialogContext).apply {
            isIndeterminate = true
        }
        val textView = TextView(dialogContext).apply {
            text = initialMessage
            setPadding(0, 24, 0, 0)
            setTextColor(ContextCompat.getColor(dialogContext, R.color.hf_text_primary))
        }
        container.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        container.addView(textView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = createDialogBuilder(dialogContext)
            .setTitle(title)
            .setView(container)
            .create()
        dialog.setCancelable(false)
        dialog.showSafely(dialogContext, "Show progress dialog failed") ?: return null
        return ProgressDialogHandle(dialog, textView)
    }

    private fun updateProgressDialog(handle: ProgressDialogHandle?, message: String) {
        if (!isAdded || handle == null) return
        handle.textView.text = message
    }

    private fun dismissProgressDialog(handle: ProgressDialogHandle?) {
        runCatching { handle?.dialog?.dismiss() }
    }

    private fun formatRemoteSyncProgress(progress: RemoteRuleSourceRepository.RemoteRuleSyncProgress): String {
        val prefix = "正在同步规则源 ${progress.current} / ${progress.total}\n${formatRemoteSourceTitle(progress.source)}"
        return when (progress.stage) {
            RemoteRuleSourceRepository.RemoteRuleSyncProgress.Stage.CONNECTING -> "$prefix\n正在连接规则源..."
            RemoteRuleSourceRepository.RemoteRuleSyncProgress.Stage.DOWNLOADING -> {
                val total = progress.totalBytes?.let { " / ${formatBytes(it)}" }.orEmpty()
                "$prefix\n正在下载：${formatBytes(progress.bytesRead)}$total"
            }
            RemoteRuleSourceRepository.RemoteRuleSyncProgress.Stage.IMPORTING -> "$prefix\n下载完成，正在导入规则..."
            RemoteRuleSourceRepository.RemoteRuleSyncProgress.Stage.COMPLETED -> "$prefix\n导入完成：${progress.addedCount ?: 0} 条规则"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }
    }

    private fun String.lineCount(): Int = this.lineSequence().count()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importRuleFile(uri)
    }

    private val suspiciousDomainsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshList()
        }
    }

    private val impactNormalNetworkLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            invalidateRuleListCache()
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
        val context = context ?: return
        binding.ruleList.layoutManager = LinearLayoutManager(context)
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
        binding.btnRuleActions.setOnClickListener { launchImportRulePicker() }
        binding.btnDecisionDomains.setOnClickListener { openDecisionDomainsPage() }
        binding.btnJoinGroup.setOnClickListener { openRemoteRuleSourcesPage() }
        binding.btnSuspiciousDomains.setOnClickListener { openSuspiciousDomainsPage() }
        binding.btnFilter.setOnClickListener { deduplicateRules() }
        binding.btnRuleSources.setOnClickListener { openImpactNormalNetworkPage() }
        binding.btnSelectAll.setOnClickListener { selectAllVisible() }
        binding.btnDeleteSelected.setOnClickListener { deleteSelectedRulesDirectly() }
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

    private fun openDecisionDomainsPage() {
        val ctx = context ?: return
        runCatching {
            startActivity(DecisionDomainsActivity.createIntent(ctx))
        }.onFailure {
            Toast.makeText(ctx, "打开拦截与放行失败", Toast.LENGTH_SHORT).show()
        }
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
            showShortToast("请先输入或粘贴域名/规则内容")
            return
        }
        val looksLikeRuleBatch = rawInput.contains('\n') ||
            rawInput.contains("||") ||
            rawInput.contains("@@") ||
            rawInput.contains("^") ||
            rawInput.contains("/") ||
            rawInput.contains("$")
        if (looksLikeRuleBatch) {
            importManualRuleBatch(ctx, rawInput)
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

    private fun importManualRuleBatch(ctx: Context, rawInput: String) {
        val report = RuleRepository.analyzeImportContent(ctx, rawInput)
        if (report.safeRuleCount <= 0) {
            showShortToast("未识别到可导入规则，建议改用导入规则按钮选择文件")
            return
        }
        if (report.whitelistConflictRules > 0) {
            showWhitelistConflictDialog(
                title = "发现疑似白名单规则",
                domains = report.sampleWhitelistConflictLines,
                onContinue = {
                    val imported = RuleRepository.importRules(ctx, rawInput, RuleSource.MANUAL, allowWhitelistDomains = true)
                    handleManualRuleBatchImportResult(ctx, imported)
                },
                onDeleteWhitelistAndContinue = {
                    val sanitizedInput = RuleRepository.removeWhitelistConflictLines(rawInput)
                    val imported = RuleRepository.importRules(ctx, sanitizedInput, RuleSource.MANUAL)
                    handleManualRuleBatchImportResult(ctx, imported)
                }
            )
            return
        }
        val imported = RuleRepository.importRules(ctx, rawInput, RuleSource.MANUAL)
        handleManualRuleBatchImportResult(ctx, imported)
    }

    private fun handleManualRuleBatchImportResult(ctx: Context, imported: Int) {
        if (imported <= 0) {
            showShortToast("未识别到可导入规则，或规则已存在")
            return
        }
        binding.inputRule.setText("")
        LogRepository.append(ctx, "Imported $imported manual batch rules")
        showShortToast("已导入 $imported 条规则")
        invalidateRuleListCache()
        refreshListSoon()
        reloadVpnIfRunning(true)
    }

    private fun handleManualRuleAddResult(ctx: Context, added: List<BlockRule>) {
        if (added.isEmpty()) {
            showShortToast("未识别到可添加的有效域名，或规则已存在")
            return
        }
        binding.inputRule.setText("")
        LogRepository.append(ctx, "Added ${added.size} manual rules")
        showShortToast("已添加 ${added.size} 条规则")
        invalidateRuleListCache()
        refreshListSoon()
        reloadVpnIfRunning(true)
    }

    private fun pasteRuleInput() {
        val ctx = safeContext() ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            showShortToast("系统剪贴板当前不可用")
            return
        }
        val text = clipboard.primaryClip?.let(::coerceClipText).orEmpty().trim()
        if (text.isBlank()) {
            showShortToast("剪贴板里没有可用内容")
            return
        }
        val current = binding.inputRule.text?.toString().orEmpty().trim()
        val merged = if (current.isBlank()) text else "$current\n$text"
        binding.inputRule.setText(merged)
        binding.inputRule.setSelection(merged.length)
        showShortToast("已粘贴到输入框")
    }

    private fun clearRuleInput() {
        val ctx = safeContext() ?: return
        if (binding.inputRule.text.isNullOrBlank()) {
            showShortToast("输入框已经是空的")
            return
        }
        binding.inputRule.setText("")
        showShortToast("已清空输入内容")
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
                .setItems(arrayOf("导入本地规则", "规则源管理", "影响正常网络", "删除所有规则")) { _, which ->
                    when (which) {
                        0 -> launchImportRulePicker()
                        1 -> openRemoteRuleSourcesPage()
                        2 -> openImpactNormalNetworkPage()
                        3 -> confirmDeleteAllRules()
                    }
                }
                .showSafely(dialogContext, "Show rule tools dialog failed")
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
            LogRepository.append(ctx, "Launch import picker failed: ${e.message ?: e.javaClass.simpleName}")
            showShortToast("无法打开文件选择器")
        }
    }

    private fun openRemoteRuleSourcesPage() {
        val ctx = safeContext() ?: return
        runCatching {
            startActivity(Intent(ctx, RemoteRuleSourcesActivity::class.java))
        }.onFailure {
            LogRepository.append(ctx, "Open remote rule sources page failed: ${it.message ?: it.javaClass.simpleName}")
            showShortToast("打开规则源页面失败")
        }
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
            builder.showSafely(dialogContext, "Show remote rule sources dialog failed")?.let(::styleDialogButtons)
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
            builder.showSafely(dialogContext, "Show remote rule sources fallback dialog failed")
        }.onFailure {
            val ctx = safeContext() ?: return@onFailure
            LogRepository.append(ctx, "Open remote rule source dialog fallback failed: ${it.message ?: it.javaClass.simpleName}")
            showShortToast("规则源页面打开失败，请重启后重试")
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
                .showSafely(dialogContext, "Show remote rule source actions dialog failed")
            if (dialog != null) {
                styleDialogButtons(dialog)
            }
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
            .showSafely(dialogContext, "Show add remote rule source name dialog failed")
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
            .showSafely(dialogContext, "Show add remote rule source url dialog failed")
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
        val progress = if (manual && isAdded) {
            showProgressDialog("同步规则源", if (justAdded) "正在添加并同步规则源..." else "正在同步规则源...")
        } else null
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = ctx.applicationContext
            runCatching {
                val results = if (sourceId == null) {
                    RemoteRuleSourceRepository.syncEnabledSources(
                        context = appContext,
                        whitelistImportMode = whitelistImportMode,
                        onProgress = { current, total, source ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                updateProgressDialog(progress, "正在同步规则源 $current / $total\n${formatRemoteSourceTitle(source)}\n准备下载...")
                            }
                        },
                        onDetailedProgress = { syncProgress ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                updateProgressDialog(progress, formatRemoteSyncProgress(syncProgress))
                            }
                        }
                    )
                } else {
                    val source = RuleRepository.getRemoteRuleSources(appContext).firstOrNull { it.id == sourceId }
                    if (source == null) {
                        listOf(
                            RemoteRuleSourceRepository.RemoteRuleSyncResult(
                                source = RemoteRuleSourceConfig(
                                    id = sourceId,
                                    name = "未知规则源",
                                    url = "",
                                    enabled = false
                                ),
                                success = false,
                                addedCount = 0,
                                errorMessage = "规则源不存在，列表可能已经更新"
                            )
                        )
                    } else {
                        updateProgressDialog(progress, "正在同步规则源\n${formatRemoteSourceTitle(source)}\n准备下载...")
                        listOf(RemoteRuleSourceRepository.syncSource(appContext, source, whitelistImportMode) { syncProgress ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                updateProgressDialog(progress, formatRemoteSyncProgress(syncProgress))
                            }
                        })
                    }
                }
                results
            }.onSuccess { results ->
                val whitelistConflicts = results.filter { it.whitelistConflictRules > 0 }
                if (whitelistConflicts.isNotEmpty() && whitelistImportMode == RemoteRuleSourceRepository.WhitelistImportMode.BLOCK) {
                    dismissProgressDialog(progress)
                    showRemoteSourceWhitelistConflictDialog(sourceId, manual, justAdded, whitelistConflicts)
                    return@onSuccess
                }
                updateProgressDialog(progress, "正在刷新规则列表...")
                reloadVpnIfRunning(results.any { it.success })
                invalidateRuleListCache()
                refreshList()
                dismissProgressDialog(progress)
                if (manual && isAdded) {
                    val successCount = results.count { result -> result.success }
                    val conflictCount = results.sumOf { result -> result.whitelistConflictRules }
                    val importedCount = results.sumOf { result -> result.addedCount }
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
                        Toast.makeText(ctx, "规则源更新完成，成功 $successCount / ${results.size}，导入 $importedCount 条规则$suffix", Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure {
                dismissProgressDialog(progress)
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
            .showSafely(dialogContext, "Show remote source whitelist conflict dialog failed")
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
                "已筛出 ${selectedIds.size} 项，可继续取消勾选后再删除"
            } else {
                "已选择 ${selectedIds.size} 项"
            }
            binding.btnDeleteSelected.text = if (filteredSelectionMode) {
                if (selectedIds.isEmpty()) "删除筛出项" else "删除筛出项（${selectedIds.size}）"
            } else {
                if (selectedIds.isEmpty()) "删除所选" else "删除所选（${selectedIds.size}）"
            }
            adapter.submit(state.items)
        }
    }

    private fun reloadVpnIfRunning(shouldReload: Boolean) {
        val ctx = context ?: return
        if (!shouldReload) return
        NetworkKernel.reloadIfRunning(ctx)
    }

    private fun updateSelectionUi() {
        if (_binding == null) return
        binding.selectionBar.isVisible = selectionMode
        binding.selectionCount.text = if (filteredSelectionMode) {
            "已筛出 ${selectedIds.size} 项，可继续取消勾选后再删除"
        } else {
            "已选择 ${selectedIds.size} 项"
        }
        binding.btnDeleteSelected.text = if (filteredSelectionMode) {
            if (selectedIds.isEmpty()) "删除筛出项" else "删除筛出项（${selectedIds.size}）"
        } else {
            if (selectedIds.isEmpty()) "删除所选" else "删除所选（${selectedIds.size}）"
        }
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
        val groupedBySource = snapshot.groupingBy { it.source.label }.eachCount().entries
            .sortedByDescending { it.value }
        val sourceSummary = groupedBySource.take(3).joinToString("，") { "${it.key} ${it.value} 条" }
        val regexCount = snapshot.count { !it.regexPattern.isNullOrBlank() }
        val cosmeticCount = snapshot.count { !it.cosmeticSelector.isNullOrBlank() }
        val appScopedCount = snapshot.count { it.appPackages.isNotEmpty() }
        val exceptionCount = snapshot.count { it.exceptionRule }
        val preview = snapshot.take(8).joinToString("\n") { rule ->
            val domain = rule.domain.ifBlank { "(未识别域名)" }
            val tags = buildList {
                add(rule.source.label)
                if (rule.exceptionRule) add("例外")
                if (!rule.regexPattern.isNullOrBlank()) add("正则")
                if (!rule.cosmeticSelector.isNullOrBlank()) add("Cosmetic")
                if (rule.appPackages.isNotEmpty()) add("App 定向")
            }.joinToString("/")
            "- $domain [$tags]"
        }
        val message = buildString {
            append("将删除 ")
            append(snapshot.size)
            append(" 条规则，删除后会立即重载拦截规则。\n\n")
            if (sourceSummary.isNotBlank()) {
                append("来源分布：")
                append(sourceSummary)
                append("\n")
            }
            append("规则类型：")
            append("普通 ")
            append(snapshot.size - regexCount - cosmeticCount)
            append(" 条")
            if (regexCount > 0) append("，正则 $regexCount 条")
            if (cosmeticCount > 0) append("，Cosmetic $cosmeticCount 条")
            if (appScopedCount > 0) append("，App 定向 $appScopedCount 条")
            if (exceptionCount > 0) append("，例外 $exceptionCount 条")
            append("\n\n示例：\n")
            append(preview)
            if (snapshot.size > 8) {
                append("\n其余 ")
                append(snapshot.size - 8)
                append(" 条已省略")
            }
        }
        try {
            createDialogBuilder(dialogContext)
                .setTitle("确认删除")
                .setMessage(message)
                .setPositiveButton("删除") { _, _ ->
                    val actionContext = context ?: return@setPositiveButton
                    val selectedIdsSnapshot = snapshot.asSequence()
                        .map { it.id.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    val removedCount = RuleRepository.removeRules(actionContext, selectedIdsSnapshot, snapshot)
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
                .showSafely(dialogContext, "Show rule delete confirmation dialog failed")
        } catch (e: Exception) {
            LogRepository.append(dialogContext, "Delete dialog failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            Toast.makeText(dialogContext, "打开删除确认失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteSelectedRules() {
        confirmDelete(resolveSelectedRules())
    }

    private fun confirmDeleteAllRules() {
        val ctx = context ?: return
        val allRules = RuleRepository.getRules(ctx)
        if (allRules.isEmpty()) {
            Toast.makeText(ctx, "当前没有规则可删除", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogContext = safeDialogActivity() ?: return
        val sourceSummary = allRules.groupingBy { it.source.label }.eachCount().entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString("，") { "${it.key} ${it.value} 条" }
        val regexCount = allRules.count { !it.regexPattern.isNullOrBlank() }
        val cosmeticCount = allRules.count { !it.cosmeticSelector.isNullOrBlank() }
        val appScopedCount = allRules.count { it.appPackages.isNotEmpty() }
        val exceptionCount = allRules.count { it.exceptionRule }
        val preview = allRules.take(8).joinToString("\n") { rule ->
            val domain = rule.domain.ifBlank { "(未识别域名)" }
            val tags = buildList {
                add(rule.source.label)
                if (rule.exceptionRule) add("例外")
                if (!rule.regexPattern.isNullOrBlank()) add("正则")
                if (!rule.cosmeticSelector.isNullOrBlank()) add("Cosmetic")
                if (rule.appPackages.isNotEmpty()) add("App 定向")
            }.joinToString("/")
            "- $domain [$tags]"
        }
        val message = buildString {
            append("将删除全部 ")
            append(allRules.size)
            append(" 条规则，删除后无法恢复，请谨慎操作。\n\n")
            if (sourceSummary.isNotBlank()) {
                append("来源分布：")
                append(sourceSummary)
                append("\n")
            }
            append("规则类型：")
            append("普通 ")
            append(allRules.size - regexCount - cosmeticCount)
            append(" 条")
            if (regexCount > 0) append("，正则 $regexCount 条")
            if (cosmeticCount > 0) append("，Cosmetic $cosmeticCount 条")
            if (appScopedCount > 0) append("，App 定向 $appScopedCount 条")
            if (exceptionCount > 0) append("，例外 $exceptionCount 条")
            append("\n\n示例：\n")
            append(preview)
            if (allRules.size > 8) {
                append("\n其余 ")
                append(allRules.size - 8)
                append(" 条已省略")
            }
            append("\n\n警告：此操作会清空所有规则，包括手动添加的规则和导入的规则。")
        }
        try {
            createDialogBuilder(dialogContext)
                .setTitle("确认删除所有规则")
                .setMessage(message)
                .setPositiveButton("删除全部") { _, _ ->
                    val actionContext = context ?: return@setPositiveButton
                    lifecycleScope.launch {
                        val removedCount = withContext(Dispatchers.Default) {
                            RuleRepository.removeAllRules(actionContext)
                        }
                        LogRepository.append(actionContext, "Requested removeAll actualRemoved=$removedCount")
                        if (removedCount <= 0) {
                            Toast.makeText(actionContext, "未删除任何规则，请重试一次", Toast.LENGTH_SHORT).show()
                            invalidateRuleListCache()
                            refreshListSoon()
                            return@launch
                        }
                        Toast.makeText(actionContext, "已删除全部 $removedCount 条规则", Toast.LENGTH_LONG).show()
                        invalidateRuleListCache()
                        exitSelection()
                        refreshListSoon()
                        reloadVpnIfRunning(true)
                    }
                }
                .setNegativeButton("取消", null)
                .showSafely(dialogContext, "Show delete all rules confirmation dialog failed")
        } catch (e: Exception) {
            LogRepository.append(dialogContext, "Delete all dialog failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            Toast.makeText(dialogContext, "打开删除确认失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteSelectedRulesDirectly() {
        val actionContext = context ?: return
        val snapshot = resolveSelectedRules().distinctBy { it.id.ifBlank { it.domain } }
        if (snapshot.isEmpty()) {
            Toast.makeText(actionContext, "请至少选择一条规则", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedIdsSnapshot = snapshot.asSequence()
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val removedCount = RuleRepository.removeRules(actionContext, selectedIdsSnapshot, snapshot)
        LogRepository.append(actionContext, "Requested direct remove=${snapshot.size} actualRemoved=$removedCount")
        if (removedCount <= 0) {
            Toast.makeText(actionContext, "未删除任何规则，旧规则数据已自动修复，请重试一次", Toast.LENGTH_SHORT).show()
            invalidateRuleListCache()
            refreshListSoon()
            return
        }
        Toast.makeText(actionContext, "已删除 $removedCount 条规则", Toast.LENGTH_SHORT).show()
        invalidateRuleListCache()
        exitSelection()
        refreshListSoon()
        reloadVpnIfRunning(true)
    }

    private fun resolveSelectedRules(): List<BlockRule> {
        val ctx = context ?: return emptyList()
        if (selectedIds.isEmpty()) return emptyList()
        if (selectedRulesById.isNotEmpty()) {
            val snapshot = selectedIds.mapNotNull(selectedRulesById::get)
            if (snapshot.size == selectedIds.size) {
                return snapshot
            }
        }
        val currentRules = getGroupedRules(ctx).values.flatten().map { it.rule }
        return currentRules.filter { it.id in selectedIds }
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
        openImpactNormalNetworkPage()
    }

    private fun openImpactNormalNetworkPage() {
        val host = activity as? MainActivity ?: return
        runCatching {
            impactNormalNetworkLauncher.launch(ImpactNormalNetworkActivity.createIntent(host))
        }.onFailure {
            LogRepository.append(host, "Open impact normal network page failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(host, "打开影响正常网络页面失败", Toast.LENGTH_SHORT).show()
        }
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
                runCatching {
                    suspiciousDomainsLauncher.launch(SuspiciousDomainsActivity.createIntent(currentHost))
                }.onFailure {
                    LogRepository.append(currentHost, "Launch suspicious domains page failed: ${it.message ?: it.javaClass.simpleName}")
                    Toast.makeText(currentHost, "打开分析页面失败", Toast.LENGTH_SHORT).show()
                }
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
        val detail = buildString {
            append("来源：")
            append(rule.source.label)
            append("\n厂商：")
            append(rule.vendor)
            if (rule.exceptionRule) append("\n类型：例外规则")
            if (!rule.regexPattern.isNullOrBlank()) append("\n正则：${rule.regexPattern}")
            if (!rule.keywordPattern.isNullOrBlank()) append("\n关键词：${rule.keywordPattern}")
            if (!rule.pathPattern.isNullOrBlank()) append("\n路径：${rule.pathPattern}")
            if (!rule.cosmeticSelector.isNullOrBlank()) append("\nCosmetic：${rule.cosmeticSelector}")
            if (rule.appPackages.isNotEmpty()) append("\nApp 定向：${rule.appPackages.joinToString(", ")}")
            if (rule.remoteSourceId != null) append("\n规则源 ID：${rule.remoteSourceId}")
        }
        try {
            createDialogBuilder(dialogContext)
                .setTitle(rule.domain)
                .setMessage(detail)
                .setPositiveButton("手动分类") { _, _ -> showVendorPicker(rule) }
                .setNeutralButton("删除规则") { _, _ -> confirmDelete(listOf(rule)) }
                .setNegativeButton("关闭", null)
                .showSafely(dialogContext, "Show rule actions dialog failed")
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
                .showSafely(dialogContext, "Show vendor picker dialog failed")
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
            dialog.showSafely(dialogContext, "Show create vendor dialog failed")?.let(::styleDialogButtons)
        }.onFailure {
            LogRepository.append(dialogContext, "Open create vendor dialog failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(dialogContext, "打开新建分组失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importRuleFile(uri: Uri) {
        val ctx = safeContext() ?: return
        val appContext = ctx.applicationContext
        LogRepository.append(appContext, "Import rule file requested: uri=$uri")
        val progress = showProgressDialog("导入规则", "正在读取规则文件...")
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                updateProgressDialog(progress, "正在打开规则文件...")
                delay(80)
                updateProgressDialog(progress, "正在读取规则文件...\n大文件可能需要等待几秒")
                val content = readRuleContent(appContext, uri)
                if (content == null) {
                    LogRepository.append(appContext, "Import rule file failed: unable to read content from URI")
                    dismissProgressDialog(progress)
                    safeContext()?.let {
                        Toast.makeText(it, "读取规则文件失败：无法从选择的文件读取内容", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                LogRepository.append(appContext, "Import rule file succeeded: content length=${content.length}")
                importAndAnalyzeRuleContent(sourceLabel = uri.toString(), sourceUri = uri, content = content, progress = progress)
            }.onFailure { e ->
                dismissProgressDialog(progress)
                LogRepository.append(appContext, "Import rule file failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
                if (isAdded) {
                    safeContext()?.let { ctx ->
                        Toast.makeText(ctx, "导入规则失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private suspend fun readRuleContent(context: android.content.Context, uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            LogRepository.append(context, "Reading rule content from URI: $uri")
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                LogRepository.append(context, "Persistable URI permission granted")
            }.onFailure {
                LogRepository.append(context, "Failed to take persistable URI permission: ${it.message ?: it.javaClass.simpleName}")
            }
            val contentResolver = context.contentResolver
            val directContent = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.also { content ->
                    LogRepository.append(context, "Read content via openInputStream: length=${content?.length ?: 0}")
                }
            }.getOrNull()
            if (!directContent.isNullOrBlank()) {
                LogRepository.append(context, "Successfully read content via openInputStream")
                return@withContext directContent
            }
            val textContent = runCatching {
                readRuleContentFromTypedAsset(contentResolver, uri, "text/plain").also { content ->
                    LogRepository.append(context, "Read content via typedAsset text/plain: length=${content?.length ?: 0}")
                }
            }.getOrNull()
            if (!textContent.isNullOrBlank()) {
                LogRepository.append(context, "Successfully read content via typedAsset text/plain")
                return@withContext textContent
            }
            val anyContent = runCatching {
                readRuleContentFromTypedAsset(contentResolver, uri, "*/*").also { content ->
                    LogRepository.append(context, "Read content via typedAsset */*: length=${content?.length ?: 0}")
                }
            }.getOrNull()
            if (!anyContent.isNullOrBlank()) {
                LogRepository.append(context, "Successfully read content via typedAsset */*")
                return@withContext anyContent
            }
            LogRepository.append(context, "All read methods failed or returned blank content")
            return@withContext null
        }
    }

    private fun readRuleContentFromTypedAsset(contentResolver: ContentResolver, uri: Uri, mimeType: String): String? {
        return runCatching {
            contentResolver.openTypedAssetFileDescriptor(uri, mimeType, null)?.createInputStream()?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    private suspend fun importAndAnalyzeRuleContent(uri: Uri, content: String) {
        importAndAnalyzeRuleContent(sourceLabel = uri.toString(), sourceUri = uri, content = content, progress = null)
    }

    private suspend fun importAndAnalyzeRuleContent(
        sourceLabel: String,
        sourceUri: Uri,
        content: String,
        progress: ProgressDialogHandle? = null
    ) {
        val appContext = context?.applicationContext ?: return
        LogRepository.append(appContext, "Starting import analysis: content length=${content.length}, lines=${content.lineCount()}")
        
        if (!isAdded || _binding == null) return
        updateProgressDialog(progress, "正在分析规则文件...\n大小：${content.length / 1024}KB\n行数：${content.lineCount()}")
        
        val report = withContext(Dispatchers.Default) {
            RuleRepository.analyzeImportContent(appContext, content)
        }
        
        LogRepository.append(appContext, "Analysis complete: totalLines=${report.totalLines}, safeRules=${report.safeRuleCount}, whitelistConflicts=${report.whitelistConflictRules}")
        
        if (report.safeRuleCount <= 0) {
            if (!isAdded || _binding == null) return
            dismissProgressDialog(progress)
            context?.let {
                Toast.makeText(it, "文件里没有识别到可导入规则", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        if (report.whitelistConflictRules > 0) {
            if (!isAdded || _binding == null) return
            val host = safeDialogActivity() ?: return
            dismissProgressDialog(progress)
            showWhitelistConflictDialog(
                title = "发现疑似白名单规则",
                domains = report.sampleWhitelistConflictLines,
                onContinue = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val importProgress = showProgressDialog("导入规则", "正在导入规则...\n识别到 ${report.safeRuleCount} 条可处理规则")
                        withContext(Dispatchers.Default) {
                            RuleRepository.importRules(appContext, content, allowWhitelistDomains = true)
                        }
                        updateProgressDialog(importProgress, "正在刷新规则列表...")
                        val inventory = withContext(Dispatchers.Default) {
                            RuleRepository.getRuleInventory(appContext)
                        }
                        if (!isAdded || _binding == null) return@launch
                        LogRepository.append(appContext, "Imported rules from $sourceLabel with whitelist conflicts accepted")
                        invalidateRuleListCache()
                        refreshListSoon()
                        reloadVpnIfRunning(true)
                        dismissProgressDialog(importProgress)
                        context?.let {
                            Toast.makeText(it, "规则已导入 ${report.safeRuleCount} 条，当前可拦截 ${inventory.totalSupportedCount} 条", Toast.LENGTH_LONG).show()
                        }
                        openImportAnalysisPage(inventory, report, sourceLabel, sourceUri)
                    }
                },
                onDeleteWhitelistAndContinue = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val importProgress = showProgressDialog("导入规则", "正在删除疑似白名单规则...")
                        val sanitizedContent = withContext(Dispatchers.Default) {
                            RuleRepository.removeWhitelistConflictLines(content)
                        }
                        updateProgressDialog(importProgress, "正在导入剩余规则...")
                        withContext(Dispatchers.Default) {
                            RuleRepository.importRules(appContext, sanitizedContent)
                        }
                        updateProgressDialog(importProgress, "正在刷新规则列表...")
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
                        dismissProgressDialog(importProgress)
                        context?.let {
                            Toast.makeText(it, "已删除白名单候选，已导入 ${sanitizedReport.safeRuleCount} 条，当前可拦截 ${inventory.totalSupportedCount} 条", Toast.LENGTH_LONG).show()
                        }
                        openImportAnalysisPage(inventory, sanitizedReport, sourceLabel, sourceUri)
                    }
                },
                host = host
            )
            return
        }
        
        LogRepository.append(appContext, "Starting rule import: safeRules=${report.safeRuleCount}")
        updateProgressDialog(progress, "正在导入规则...\n识别到 ${report.safeRuleCount} 条可处理规则")
        withContext(Dispatchers.Default) {
            RuleRepository.importRules(appContext, content)
        }
        LogRepository.append(appContext, "Rule import complete")
        
        updateProgressDialog(progress, "正在刷新规则列表...")
        val inventory = withContext(Dispatchers.Default) {
            RuleRepository.getRuleInventory(appContext)
        }
        if (!isAdded || _binding == null) return
        LogRepository.append(appContext, "Import successful: totalRules=${inventory.totalSupportedCount}")
        invalidateRuleListCache()
        refreshListSoon()
        reloadVpnIfRunning(true)
        dismissProgressDialog(progress)
        context?.let {
            Toast.makeText(it, "规则已导入 ${report.safeRuleCount} 条，当前可拦截 ${inventory.totalSupportedCount} 条", Toast.LENGTH_LONG).show()
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
            append("\n\n支持的规则类型（24+ 种格式）：\n")
            append("1. 纯域名：example.com\n")
            append("2. Hosts: 0.0.0.0 ads.com / 127.0.0.1 tracker.com\n")
            append("3. dnsmasq: address=/domain.com/0.0.0.0\n")
            append("4. SmartDNS: address /domain.com/0.0.0.0 / ipset=/domain.com/adblock\n")
            append("5. AdGuard/ABP: ||domain^ / ||domain^\$modifier / @@||whitelist^\n")
            append("6. ABP 修饰符 (30+ 种): third-party/3p, first-party/1p, domain=, path=, removeparam=, csp=, redirect=, denyallow=, cookie=, header=, removeheader=, replace=, app=, dnstype=, urlblock, from, to, jsinject, network, blockipv6, blockipv4, dnsrewrite, generichide, ctag, client, mac, asn, important, match-case, badfilter, script, image 等\n")
            append("7. IP-CIDR/IP-CIDR6: ip-cidr,192.168.1.0/24 / ip6-cidr,::1/128\n")
            append("8. Clash: DOMAIN-SUFFIX,com / DOMAIN-KEYWORD,ad / IP-CIDR,... / GEOSITE,category-ads-all\n")
            append("9. Surge: HOST-SUFFIX,com / HOST-KEYWORD,ad / IP-CIDR,... / GEOSITE:category-ads-all\n")
            append("10. Loon: DOMAIN-SUFFIX,com / DOMAIN-KEYWORD,ad / ip-cidr,...\n")
            append("11. Quantumult X: host example.com / ip-cidr 192.168.1.0/24 / host-keyword ad\n")
            append("12. Shadowrocket: host-suffix,com / host-keyword,ad / url-regexp pattern\n")
            append("13. V2Ray/Xray: domain:xxx / domainSuffix:xxx / ip:xxx / ipCIDR:xxx\n")
            append("14. 域名 + 端口：example.com:8080\n")
            append("15. 端口通配符：*:443\$network / *:80\$network\n")
            append("16. 路径规则：domain.com/ads/* / api.com/v1/ad/*\n")
            append("17. 关键词规则：*ad* / *tracker* / *analytics*\n")
            append("18. 正则规则：/^https?:.*ad.*\\.example\\.com/ / /.*\\.(ads?|banner)\\..*/\n")
            append("19. CSP 规则：domain##^csp:script-src 'self'\n")
            append("20. CSS 规则：domain##.ad-banner / domain###sidebar-ads\n")
            append("21. 复合规则：AND(domain.com, /ads/) / OR(ads1.com, ads2.com)\n")
            append("22. 例外规则：@@||domain^ / @@0.0.0.0 whitelist.com\n")
            append("23. 包名规则：package:com.example.app / ||ads.com^\$package=...\n")
            append("24. 点前缀域名：.example.com（匹配所有子域名）\n")
            append("25. IPv6 Hosts: 2001:db8::1 ads.example.com\n")
            append("\n部分支持：redirect 类、removeparam 类、header 修改、replace 类、cookie 类、CSS 隐藏规则（需 MITM 增强）\n")
            append("会跳过：复杂脚本、远程脚本、://schema 规则、GEOIP/IP-ASN 等需要本地数据库的高级代理规则\n")
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
        val expandedSnapshot = expandedGroups.toSet()
        val autoExpand = query.isNotEmpty()
        return buildList {
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
                if (filteredRules.isEmpty() && !matchesVendor) return@forEach
                val visibleRules = when {
                    autoExpand -> filteredRules
                    expandedSnapshot.contains(vendor) -> filteredRules
                    else -> emptyList()
                }
                visibleRules.take(500).forEach { add(it.rule) }
            }
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
            .showSafely(activityHost, "Show whitelist conflict dialog failed")
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
