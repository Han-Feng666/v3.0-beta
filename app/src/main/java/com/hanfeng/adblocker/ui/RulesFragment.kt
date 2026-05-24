package com.HanFeng.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import android.app.Activity
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
import com.HanFeng.data.RuleRepository
import com.HanFeng.databinding.FragmentRulesBinding
import com.HanFeng.databinding.ItemRuleDomainBinding
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RuleListItem
import com.HanFeng.model.RuleSource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RulesFragment : Fragment(R.layout.fragment_rules) {
    private var mainActivity: MainActivity? = null
    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!
    private val expandedGroups = mutableSetOf<String>()
    private val selectedIds = mutableSetOf<String>()
    private var selectionMode = false
    private var filteredSelectionMode = false
    private var refreshVersion = 0

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
                val ids = RuleRepository.getRules(ctx).filter { it.vendor == vendor }.map { it.id }
                enterSelection(ids)
            },
            onDomainClick = { item ->
                if (selectionMode) {
                    toggleSelection(item.rule.id)
                } else {
                    showRuleActions(item.rule)
                }
            },
            onDomainLongPress = { item ->
                enterSelection(setOf(item.rule.id))
            },
            onSelectionChanged = { item, checked ->
                if (checked) selectedIds += item.rule.id else selectedIds -= item.rule.id
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
        binding.btnJoinGroup.setOnClickListener { mainActivity?.joinQqGroup() }
        binding.btnSuspiciousDomains.setOnClickListener { openSuspiciousDomainsPage() }
        binding.btnFilter.setOnClickListener { filterNonAds() }
        binding.btnSelectAll.setOnClickListener { selectAllVisible() }
        binding.btnDeleteSelected.setOnClickListener { confirmDelete(selectedIds) }
        binding.btnCancelSelection.setOnClickListener { exitSelection() }
        refreshListDelayed()
    }

    private fun refreshListDelayed() {
        view?.postDelayed({
            if (isAdded && _binding != null && view != null) {
                refreshList()
            }
        }, 200)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun safeContext(): Context? {
        return context?.takeIf { isAdded && it != null }
    }

    private fun safeDialogActivity(): MainActivity? {
        val host = activity as? MainActivity ?: return null
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
        val added = RuleRepository.addRules(ctx, rawInput, RuleSource.MANUAL)
        if (added.isEmpty()) {
            Toast.makeText(ctx, "未识别到可添加的有效域名，或规则已存在", Toast.LENGTH_SHORT).show()
            return
        }
        binding.inputRule.setText("")
        LogRepository.append(ctx, "Added ${added.size} manual rules")
        Toast.makeText(ctx, "已添加 ${added.size} 条规则", Toast.LENGTH_SHORT).show()
        refreshList()
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
        launchImportRulePicker()
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
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.Default) {
                val rules = RuleRepository.getRules(appContext)
                // 优化：规则已按 domain 排序，按顺序分组避免额外排序
                val grouped = linkedMapOf<String, MutableList<BlockRule>>()
                rules.forEach { rule ->
                    grouped.getOrPut(rule.vendor) { mutableListOf() }.add(rule)
                }
                val items = buildList {
                    grouped.forEach { (vendor, groupRules) ->
                        add(RuleListItem.Group(vendor, groupRules.size, expandedSnapshot.contains(vendor)))
                        if (expandedSnapshot.contains(vendor)) {
                            val maxVisible = 500
                            if (groupRules.size > maxVisible) {
                                groupRules.take(maxVisible).forEach { rule ->
                                    add(RuleListItem.Domain(rule, selectedSnapshot.contains(rule.id), currentSelectionMode))
                                }
                                add(RuleListItem.More(vendor, groupRules.size - maxVisible))
                            } else {
                                groupRules.forEach { rule ->
                                    add(RuleListItem.Domain(rule, selectedSnapshot.contains(rule.id), currentSelectionMode))
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
                append("已保存 ${state.inventory.totalSavedCount} 条规则")
                append("  当前可拦截 ${state.inventory.totalSupportedCount} 条")
                append("  内置 ${state.inventory.referenceCount} 条")
                append("  用户导入 ${state.inventory.importedCount} 条")
                if (state.inventory.manualCount > 0) append("  手动 ${state.inventory.manualCount} 条")
                if (state.inventory.regexCount > 0) append("  正则 ${state.inventory.regexCount} 条")
                if (state.inventory.cosmeticCount > 0) append("  Cosmetic ${state.inventory.cosmeticCount} 条")
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

    private fun enterSelection(initialIds: Collection<String>) {
        selectionMode = true
        filteredSelectionMode = false
        selectedIds += initialIds
        refreshList()
    }

    private fun enterFilteredSelection(initialIds: Collection<String>) {
        selectionMode = true
        filteredSelectionMode = true
        selectedIds.clear()
        selectedIds += initialIds
        refreshList()
    }

    private fun exitSelection() {
        selectionMode = false
        filteredSelectionMode = false
        selectedIds.clear()
        refreshList()
    }

    private fun toggleSelection(id: String) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        refreshList()
    }

    private fun selectAllVisible() {
        visibleDomainIds().forEach { selectedIds += it }
        refreshList()
    }

    private fun confirmDelete(ids: Set<String>) {
        val snapshot = ids.toSet()
        if (snapshot.isEmpty()) return
        val dialogContext = safeDialogActivity() ?: return
        try {
            MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_HanFeng_Dialog)
                .setTitle("确认删除")
                .setMessage("确定删除这 ${snapshot.size} 条规则吗？删除后无法恢复。")
                .setPositiveButton("删除") { _, _ ->
                    val actionContext = context ?: return@setPositiveButton
                    RuleRepository.removeByIds(actionContext, snapshot)
                    LogRepository.append(actionContext, "Removed ${snapshot.size} rules")
                    Toast.makeText(actionContext, "已删除 ${snapshot.size} 条规则", Toast.LENGTH_SHORT).show()
                    exitSelection()
                    refreshList()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            LogRepository.append(dialogContext, "Delete dialog failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            Toast.makeText(dialogContext, "打开删除确认失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun filterNonAds() {
        val dialogContext = safeDialogActivity() ?: return
        try {
            viewLifecycleOwner.lifecycleScope.launch {
                val rules = withContext(Dispatchers.Default) {
                    RuleRepository.filterNonAds(dialogContext)
                }
                if (!isAdded || _binding == null) return@launch
                if (rules.isEmpty()) {
                    Toast.makeText(dialogContext, "没有检测到可清理规则", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showFilterNonAdsDialog(dialogContext, rules)
            }
        } catch (e: Exception) {
            LogRepository.append(dialogContext, "Filter non-ads failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            Toast.makeText(dialogContext, "筛选失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSuspiciousDomainsPage() {
        val host = activity as? MainActivity ?: return
        try {
            if (RuleRepository.getSuspiciousDomainSamples(host).isEmpty()) {
                Toast.makeText(host, "当前没有需要分析的疑似广告域名", Toast.LENGTH_SHORT).show()
                return
            }
            suspiciousDomainsLauncher.launch(SuspiciousDomainsActivity.createIntent(host))
        } catch (e: Exception) {
            LogRepository.append(host, "Open suspicious domains page failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            Toast.makeText(host, "打开分析页面失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFilterNonAdsDialog(dialogContext: MainActivity, rules: List<BlockRule>) {
        try {
            val selected = rules.map { it.id }.toMutableSet()
            val listView = RecyclerView(dialogContext).apply {
                layoutManager = LinearLayoutManager(dialogContext)
                setHasFixedSize(true)
                itemAnimator = null
                setPadding(0, 12, 0, 12)
                clipToPadding = false
                adapter = FilterRuleAdapter(rules, selected) { ruleId, checked ->
                    if (checked) selected += ruleId else selected -= ruleId
                }
            }
            val dialog = MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_HanFeng_Dialog)
                .setTitle("清理重复与无效规则")
                .setMessage("勾选表示要删除的规则。系统已自动筛选出疑似非广告域名和暂不支持的规则。")
                .setView(listView)
                .setPositiveButton("删除勾选", null)
                .setNeutralButton("全不选", null)
                .setNegativeButton("取消", null)
                .create()
            dialog.setOnShowListener {
                val listAdapter = listView.adapter as? FilterRuleAdapter
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    val actionContext = context ?: return@setOnClickListener
                    if (selected.isEmpty()) {
                        Toast.makeText(actionContext, "请先勾选要删除的规则", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    RuleRepository.removeByIds(actionContext, selected.toSet())
                    LogRepository.append(actionContext, "Removed ${selected.size} non-ad candidates")
                    exitSelection()
                    refreshList()
                    Toast.makeText(actionContext, "已删除 ${selected.size} 条规则", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    val shouldSelectAll = selected.isEmpty()
                    selected.clear()
                    if (shouldSelectAll) {
                        selected += rules.map { it.id }
                    }
                    listAdapter?.setAllChecked(shouldSelectAll)
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.text = if (shouldSelectAll) "全不选" else "全选"
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.text = if (selected.isEmpty()) "全选" else "全不选"
            }
            dialog.show()
            styleDialogButtons(dialog)
        } catch (e: Exception) {
            val ctx = safeContext() ?: return
            LogRepository.append(ctx, "Low-value rule cleanup dialog failed: ${e.message ?: e.javaClass.simpleName}\nStack: ${e.stackTraceToString()}")
            enterFilteredSelection(rules.map { it.id })
            Toast.makeText(ctx, "弹窗打开失败，已切换到多选删除模式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRuleActions(rule: BlockRule) {
        val dialogContext = safeDialogActivity() ?: return
        try {
            MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_HanFeng_Dialog)
                .setTitle(rule.domain)
                .setItems(arrayOf("手动分类", "删除规则")) { _, which ->
                    when (which) {
                        0 -> showVendorPicker(rule)
                        1 -> confirmDelete(setOf(rule.id))
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
            MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_HanFeng_Dialog)
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
        val dialog = MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_HanFeng_Dialog)
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
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
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
        withContext(Dispatchers.Default) {
            RuleRepository.importRules(appContext, content)
        }
        val inventory = withContext(Dispatchers.Default) {
            RuleRepository.getRuleInventory(appContext)
        }
        if (!isAdded || _binding == null) return
        LogRepository.append(appContext, "Imported rules from $sourceLabel")
        refreshList()
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
            if (host.isFinishing || host.isDestroyed) error("activity-not-ready")
            host.startActivity(GuideActivity.createIntent(host, "导入结果分析", content))
        }.onFailure {
            LogRepository.append(host, "Open import analysis failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(host, "规则已导入，请稍后重试查看分析结果", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAnalysisSamples(report: RuleRepository.RuleAnalysisReport): String {
        return buildString {
            if (report.sampleUnsupportedLines.isNotEmpty()) {
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
            append("- Cosmetic 规则：${report.cosmeticRules}\n")
            append("- 正则规则：${report.regexRules}\n")
            append("- 空行/注释：${report.blankOrCommentLines}\n")
            append("- 无法识别：${report.invalidRules}")
            if (report.vendorSummary.isNotEmpty()) {
                append("\n\n可导入规则厂商分布：\n")
                append(report.vendorSummary.joinToString("\n") { "- ${it.vendor}: ${it.count}" })
            }
        }
    }

    private fun visibleDomainIds(): List<String> {
        val ctx = context ?: return emptyList()
        val grouped = RuleRepository.getRules(ctx).groupBy { it.vendor }
        return grouped
            .filterKeys { expandedGroups.contains(it) }
            .values
            .flatMap { rules ->
                rules.map { it.id }.take(500)
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

    private data class RuleListState(
        val inventory: RuleRepository.RuleInventory,
        val items: List<RuleListItem>
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
