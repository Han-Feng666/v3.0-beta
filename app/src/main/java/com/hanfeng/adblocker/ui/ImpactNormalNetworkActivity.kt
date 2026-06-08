package com.HanFeng.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.databinding.ActivityImpactNormalNetworkBinding
import com.HanFeng.databinding.ItemImpactNormalNetworkBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImpactNormalNetworkActivity : BaseActivity() {
    private lateinit var binding: ActivityImpactNormalNetworkBinding
    private lateinit var adapter: CandidateAdapter
    private var hasChanges = false
    private var candidates: List<RuleRepository.RemoteRuleRemovalCandidate> = emptyList()
    private var visibleCandidates: List<RuleRepository.RemoteRuleRemovalCandidate> = emptyList()
    private val selectedRuleIds = linkedSetOf<String>()
    private var isLoading = false
    private var currentFilter = CandidateFilter.ALL
    private var currentGrouping = CandidateGrouping.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityImpactNormalNetworkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }
        adapter = CandidateAdapter(
            isSelected = { selectedRuleIds.contains(it.rule.id) },
            onToggle = { candidate, checked ->
                val ruleId = candidate.rule.id
                if (checked && !selectedRuleIds.contains(ruleId)) {
                    selectedRuleIds += ruleId
                } else if (!checked) {
                    selectedRuleIds -= ruleId
                }
                updateDeleteButton()
            }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSelectAll.setOnClickListener { selectAll() }
        binding.btnClearSelection.setOnClickListener { clearSelection() }
        binding.btnFilterAll.setOnClickListener { applyFilter(CandidateFilter.ALL) }
        binding.btnFilterHighRisk.setOnClickListener { applyFilter(CandidateFilter.HIGH_RISK) }
        binding.btnFilterRemote.setOnClickListener { applyFilter(CandidateFilter.REMOTE_SOURCE) }
        binding.btnFilterSystem.setOnClickListener { applyFilter(CandidateFilter.SYSTEM_SERVICE) }
        binding.btnGroupNone.setOnClickListener { applyGrouping(CandidateGrouping.NONE) }
        binding.btnGroupSource.setOnClickListener { applyGrouping(CandidateGrouping.SOURCE) }
        binding.btnGroupVendor.setOnClickListener { applyGrouping(CandidateGrouping.VENDOR) }
        binding.btnGroupBusiness.setOnClickListener { applyGrouping(CandidateGrouping.BUSINESS) }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelectedRules() }
        loadCandidates()
    }

    override fun finish() {
        if (hasChanges) {
            setResult(Activity.RESULT_OK)
        }
        super.finish()
    }

    private fun loadCandidates() {
        if (isLoading) return
        isLoading = true
        setLoadingState(true)
        
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) {
                    RuleRepository.getImpactNormalNetworkCandidates(applicationContext)
                }
                if (isFinishing || isDestroyed) return@launch
                candidates = loaded
                isLoading = false
                setLoadingState(false)
                applyFilter(currentFilter, preserveSelection = false)
                updateDeleteButton()
            } catch (e: Exception) {
                isLoading = false
                setLoadingState(false)
                Toast.makeText(this@ImpactNormalNetworkActivity, "加载失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoadingState(loading: Boolean) {
        binding.loadingProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.list.visibility = if (loading) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (loading) View.GONE else if (visibleCandidates.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun applyFilter(filter: CandidateFilter, preserveSelection: Boolean = false) {
        currentFilter = filter
        val filtered = candidates.filter { candidate ->
            when (filter) {
                CandidateFilter.ALL -> true
                CandidateFilter.HIGH_RISK -> candidate.riskLevel == RuleRepository.CandidateRiskLevel.HIGH
                CandidateFilter.REMOTE_SOURCE -> !candidate.rule.remoteSourceId.isNullOrBlank()
                CandidateFilter.SYSTEM_SERVICE -> isSystemServiceCandidate(candidate)
            }
        }
        visibleCandidates = sortCandidatesForGrouping(filtered)
        if (!preserveSelection) {
            selectedRuleIds.retainAll(visibleCandidates.map { it.rule.id }.toSet())
        }
        adapter.submitListWithForceUpdate(visibleCandidates)
        binding.emptyText.visibility = if (visibleCandidates.isEmpty()) View.VISIBLE else View.GONE
        binding.summaryText.text = buildSummaryText(visibleCandidates.size)
        updateFilterButtons()
        updateGroupingButtons()
        updateDeleteButton()
    }

    private fun applyGrouping(grouping: CandidateGrouping) {
        currentGrouping = grouping
        visibleCandidates = sortCandidatesForGrouping(visibleCandidates)
        adapter.submitList(visibleCandidates)
        binding.summaryText.text = buildSummaryText(visibleCandidates.size)
        updateGroupingButtons()
    }

    private fun sortCandidatesForGrouping(
        items: List<RuleRepository.RemoteRuleRemovalCandidate>
    ): List<RuleRepository.RemoteRuleRemovalCandidate> {
        val riskOrder: (RuleRepository.RemoteRuleRemovalCandidate) -> Int = {
            when (it.riskLevel) {
                RuleRepository.CandidateRiskLevel.HIGH -> 0
                RuleRepository.CandidateRiskLevel.MEDIUM -> 1
            }
        }
        return when (currentGrouping) {
            CandidateGrouping.NONE -> items.sortedWith(
                compareBy<RuleRepository.RemoteRuleRemovalCandidate>(riskOrder)
                    .thenBy { it.rule.domain.lowercase() }
            )
            CandidateGrouping.SOURCE -> items.sortedWith(
                compareBy<RuleRepository.RemoteRuleRemovalCandidate> { it.sourceLabel.lowercase() }
                    .thenBy(riskOrder)
                    .thenBy { it.businessCategory.lowercase() }
                    .thenBy { it.rule.domain.lowercase() }
            )
            CandidateGrouping.VENDOR -> items.sortedWith(
                compareBy<RuleRepository.RemoteRuleRemovalCandidate> { it.vendor.lowercase() }
                    .thenBy(riskOrder)
                    .thenBy { it.businessCategory.lowercase() }
                    .thenBy { it.rule.domain.lowercase() }
            )
            CandidateGrouping.BUSINESS -> items.sortedWith(
                compareBy<RuleRepository.RemoteRuleRemovalCandidate> { it.businessCategory.lowercase() }
                    .thenBy(riskOrder)
                    .thenBy { it.sourceLabel.lowercase() }
                    .thenBy { it.rule.domain.lowercase() }
            )
        }
    }

    private fun selectAll() {
        val visibleIds = visibleCandidates.map { it.rule.id }.toSet()
        val unselectedInVisible = visibleIds - selectedRuleIds
        if (unselectedInVisible.isNotEmpty()) {
            selectedRuleIds += unselectedInVisible
        }
        adapter.notifyDataSetChanged()
        updateDeleteButton()
    }

    private fun clearSelection() {
        selectedRuleIds.clear()
        adapter.notifyDataSetChanged()
        updateDeleteButton()
    }

    private fun confirmDeleteSelectedRules() {
        if (isLoading) {
            Toast.makeText(this, "正在加载中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = candidates.filter { it.rule.id in selectedRuleIds }
        LogRepository.append(this, "confirmDeleteSelectedRules: selectedRuleIds=${selectedRuleIds.size}, filtered=${selected.size}")
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选需要删除的规则", Toast.LENGTH_SHORT).show()
            return
        }
        val preview = selected.take(8).joinToString("\n") { candidate ->
            "- ${candidate.rule.domain.ifBlank { "(未识别域名)" }} [${candidate.riskLevel.label}]"
        }
        val message = buildString {
            append("即将删除 ")
            append(selected.size)
            append(" 条规则，删除后会立刻重载拦截规则。\n\n")
            append("示例：\n")
            append(preview)
            if (selected.size > 8) {
                append("\n- 其余 ")
                append(selected.size - 8)
                append(" 条已省略")
            }
        }
        try {
            val dialog = runCatching {
                StableDialog.builder(this@ImpactNormalNetworkActivity)
                    .setTitle("确认删除所选规则")
                    .setMessage(message)
                    .setPositiveButton("确认删除") { _, _ -> 
                        LogRepository.append(this@ImpactNormalNetworkActivity, "User confirmed delete, calling deleteSelectedRules")
                        deleteSelectedRules(selected) 
                    }
                    .setNegativeButton("取消", null)
            }.getOrNull()
            
            if (dialog != null) {
                runCatching {
                    dialog.show()
                }.onFailure { e ->
                    LogRepository.append(this@ImpactNormalNetworkActivity, "Dialog show failed: ${e.message ?: e.javaClass.simpleName}")
                    Toast.makeText(this@ImpactNormalNetworkActivity, "对话框显示失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } else {
                LogRepository.append(this@ImpactNormalNetworkActivity, "Dialog builder failed")
                Toast.makeText(this@ImpactNormalNetworkActivity, "无法显示确认对话框", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            LogRepository.append(this@ImpactNormalNetworkActivity, "Show delete confirmation dialog failed: ${e.message ?: e.javaClass.simpleName}")
            Toast.makeText(this@ImpactNormalNetworkActivity, "对话框错误：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteSelectedRules(selected: List<RuleRepository.RemoteRuleRemovalCandidate>) {
        val selectedIds = selected.asSequence()
            .map { it.rule.id.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        LogRepository.append(this, "deleteSelectedRules: requested=${selected.size}, ids=${selectedIds.size}")
        val removedCount = RuleRepository.removeRules(this, selectedIds, selected.map { it.rule })
        LogRepository.append(this, "Delete impact-network candidates result: requested=${selected.size} actualRemoved=$removedCount")
        if (removedCount <= 0) {
            Toast.makeText(this, "未删除任何规则，请重试一次", Toast.LENGTH_SHORT).show()
            loadCandidates()
            return
        }
        hasChanges = true
        NetworkKernel.reloadIfRunning(this)
        Toast.makeText(this, "已删除 $removedCount 条规则", Toast.LENGTH_SHORT).show()
        selectedRuleIds.clear()
        loadCandidates()  // 重新加载列表
        adapter.submitListWithForceUpdate(emptyList())  // 先清空
        adapter.submitListWithForceUpdate(visibleCandidates)  // 再刷新
    }

    private fun updateDeleteButton() {
        val count = selectedRuleIds.size
        binding.btnDeleteSelected.isEnabled = count > 0
        binding.btnDeleteSelected.alpha = if (count > 0) 1f else 0.6f
        binding.btnDeleteSelected.text = if (count > 0) "删除当前所选规则（$count）" else "删除当前所选规则"
        binding.btnClearSelection.isEnabled = count > 0
        binding.btnClearSelection.alpha = if (count > 0) 1f else 0.6f
        val visibleIds = visibleCandidates.map { it.rule.id }.toSet()
        val hasUnselectedInVisible = visibleIds.any { it !in selectedRuleIds }
        binding.btnSelectAll.isEnabled = hasUnselectedInVisible
        binding.btnSelectAll.alpha = if (hasUnselectedInVisible) 1f else 0.6f
    }

    private fun buildSummaryText(count: Int): String {
        if (count <= 0) {
            return "当前没有识别到明显影响正常网络的规则。"
        }
        val highRiskCount = visibleCandidates.count { it.riskLevel == RuleRepository.CandidateRiskLevel.HIGH }
        val filterLabel = currentFilter.label
        val groupingLabel = currentGrouping.label
        return "当前筛选：$filterLabel，当前分组：$groupingLabel，共 $count 条，其中高风险 $highRiskCount 条。建议先看来源、厂商和原因，再决定删除。"
    }

    private fun updateFilterButtons() {
        updateFilterButtonState(binding.btnFilterAll, currentFilter == CandidateFilter.ALL)
        updateFilterButtonState(binding.btnFilterHighRisk, currentFilter == CandidateFilter.HIGH_RISK)
        updateFilterButtonState(binding.btnFilterRemote, currentFilter == CandidateFilter.REMOTE_SOURCE)
        updateFilterButtonState(binding.btnFilterSystem, currentFilter == CandidateFilter.SYSTEM_SERVICE)
    }

    private fun updateGroupingButtons() {
        updateFilterButtonState(binding.btnGroupNone, currentGrouping == CandidateGrouping.NONE)
        updateFilterButtonState(binding.btnGroupSource, currentGrouping == CandidateGrouping.SOURCE)
        updateFilterButtonState(binding.btnGroupVendor, currentGrouping == CandidateGrouping.VENDOR)
        updateFilterButtonState(binding.btnGroupBusiness, currentGrouping == CandidateGrouping.BUSINESS)
    }

    private fun updateFilterButtonState(view: View, active: Boolean) {
        view.alpha = if (active) 1f else 0.72f
    }

    private fun isSystemServiceCandidate(candidate: RuleRepository.RemoteRuleRemovalCandidate): Boolean {
        val lower = candidate.rule.domain.lowercase()
        return lower.contains("qq") ||
            lower.contains("weixin") ||
            lower.contains("wechat") ||
            lower.contains("alipay") ||
            lower.contains("tenpay") ||
            lower.contains("bank") ||
            lower.contains("game") ||
            lower.contains("mihoyo") ||
            lower.contains("hoyoverse") ||
            lower.contains("music")
    }

    private inner class CandidateAdapter(
        private val isSelected: (RuleRepository.RemoteRuleRemovalCandidate) -> Boolean,
        private val onToggle: (RuleRepository.RemoteRuleRemovalCandidate, Boolean) -> Unit
    ) : ListAdapter<RuleRepository.RemoteRuleRemovalCandidate, CandidateAdapter.ViewHolder>(DIFF) {

        fun submitListWithForceUpdate(newList: List<RuleRepository.RemoteRuleRemovalCandidate>) {
            submitList(newList.map { it })
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemImpactNormalNetworkBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(private val binding: ItemImpactNormalNetworkBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: RuleRepository.RemoteRuleRemovalCandidate) {
                binding.domainText.text = item.rule.domain.ifBlank { "(未识别域名)" }
                binding.metaText.text = buildMetaText(item)
                binding.reasonText.text = item.reasons.joinToString("；")
                binding.selectBox.setOnCheckedChangeListener(null)
                val selected = isSelected(item)
                binding.selectBox.isChecked = selected
                LogRepository.append(binding.root.context, "CandidateAdapter bind: domain=${item.rule.domain}, selected=$selected")
                binding.selectBox.setOnCheckedChangeListener { _, checked -> 
                    LogRepository.append(binding.root.context, "Candidate checkbox changed: domain=${item.rule.domain}, checked=$checked")
                    onToggle(item, checked) 
                }
                binding.root.setOnClickListener { 
                    LogRepository.append(binding.root.context, "Candidate root clicked: domain=${item.rule.domain}, will toggle checkbox")
                    binding.selectBox.toggle() 
                }
                binding.root.setOnLongClickListener {
                    showCandidateDetail(item)
                    true
                }
            }
        }
    }

    private fun buildMetaText(item: RuleRepository.RemoteRuleRemovalCandidate): String {
        val groupTag = when (currentGrouping) {
            CandidateGrouping.NONE -> null
            CandidateGrouping.SOURCE -> "分组: ${item.sourceLabel}"
            CandidateGrouping.VENDOR -> "分组: ${item.vendor}"
            CandidateGrouping.BUSINESS -> "分组: ${item.businessCategory}"
        }
        return listOfNotNull(item.riskLevel.label, item.businessCategory, item.sourceLabel, item.vendor, groupTag)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }

    private fun showCandidateDetail(item: RuleRepository.RemoteRuleRemovalCandidate) {
        val details = buildString {
            append("域名：")
            append(item.rule.domain.ifBlank { "(未识别域名)" })
            append("\n风险级别：")
            append(item.riskLevel.label)
            append("\n规则来源：")
            append(item.sourceLabel)
            append("\n厂商：")
            append(item.vendor)
            append("\n业务类型：")
            append(item.businessCategory)
            append("\n规则类型：")
            append(item.rule.source.label)
            append("\n\n判断原因：\n")
            append(item.reasons.joinToString("\n") { "- $it" })
        }
        StableDialog.builder(this)
            .setTitle("规则详情")
            .setMessage(details)
            .setPositiveButton("我知道了", null)
            .showSafely(this, "Show impact rule detail dialog failed")
    }

    private enum class CandidateFilter(val label: String) {
        ALL("全部"),
        HIGH_RISK("高风险"),
        REMOTE_SOURCE("远程规则源"),
        SYSTEM_SERVICE("系统业务")
    }

    private enum class CandidateGrouping(val label: String) {
        NONE("不分组"),
        SOURCE("按规则源"),
        VENDOR("按厂商"),
        BUSINESS("按业务类型")
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, ImpactNormalNetworkActivity::class.java)

        private val DIFF = object : DiffUtil.ItemCallback<RuleRepository.RemoteRuleRemovalCandidate>() {
            override fun areItemsTheSame(
                oldItem: RuleRepository.RemoteRuleRemovalCandidate,
                newItem: RuleRepository.RemoteRuleRemovalCandidate
            ): Boolean = oldItem.rule.id == newItem.rule.id

            override fun areContentsTheSame(
                oldItem: RuleRepository.RemoteRuleRemovalCandidate,
                newItem: RuleRepository.RemoteRuleRemovalCandidate
            ): Boolean = oldItem == newItem
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
