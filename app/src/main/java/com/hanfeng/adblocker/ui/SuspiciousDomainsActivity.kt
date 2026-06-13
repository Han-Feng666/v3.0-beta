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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.RuleRepository
import com.HanFeng.databinding.ActivitySuspiciousDomainsBinding
import com.HanFeng.databinding.ItemSuspiciousDomainBinding
import com.HanFeng.model.RuleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SuspiciousDomainsActivity : BaseActivity() {
    private lateinit var binding: ActivitySuspiciousDomainsBinding
    private var allSamples: List<RuleRepository.SuspiciousDomainSample> = emptyList()
    private var addedDomains: Set<String> = emptySet()
    private var hasChanges = false
    private val selectedDomains = linkedSetOf<String>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private lateinit var adapter: SuspiciousDomainAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySuspiciousDomainsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }
        adapter = SuspiciousDomainAdapter(
            isSelected = { selectedDomains.contains(it.domain) },
            onToggle = { sample, checked ->
                if (checked) selectedDomains += sample.domain else selectedDomains -= sample.domain
                updateSummary(adapter.currentList)
            },
            onAddSingle = { sample -> addDomains(listOf(sample.domain)) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.searchInput.doAfterTextChanged { applyFilters() }
        binding.checkOnlyUnadded.setOnCheckedChangeListener { _, checked ->
            if (checked) binding.checkOnlyAdded.isChecked = false
            applyFilters()
        }
        binding.checkOnlyAdded.setOnCheckedChangeListener { _, checked ->
            if (checked) binding.checkOnlyUnadded.isChecked = false
            applyFilters()
        }
        binding.checkOnlyNovelApps.setOnCheckedChangeListener { _, _ -> applyFilters() }
        binding.btnSelectVisible.setOnClickListener {
            val changedDomains = adapter.currentList
                .asSequence()
                .filter { it.domain !in addedDomains }
                .map { it.domain }
                .filter { selectedDomains.add(it) }
                .toSet()
            adapter.syncSelection(changedDomains)
            updateSummary(adapter.currentList)
        }
        binding.btnClearSelection.setOnClickListener {
            val changedDomains = selectedDomains.toSet()
            selectedDomains.clear()
            adapter.syncSelection(changedDomains)
            updateSummary(adapter.currentList)
        }
        binding.btnAddSelected.setOnClickListener {
            addDomains(selectedDomains.toList())
        }
        binding.btnAddRecommended.setOnClickListener {
            val recommended = adapter.currentList.filter { sample ->
                val isHighConfidence = RuleRepository.isHighConfidenceSuspiciousDomain(
                    domain = sample.domain,
                    vendor = sample.lastVendor,
                    novelHits = sample.novelHits,
                    count = sample.count,
                    appName = sample.lastAppName,
                    dnsHits = sample.dnsHits,
                    aliasHits = sample.aliasHits,
                    tlsSniHits = sample.tlsSniHits,
                    httpHits = sample.httpHits,
                    pathHits = sample.pathHits,
                    redirectHits = sample.redirectHits,
                    appSignalHits = sample.appSignalHits,
                    vendorSignalHits = sample.vendorSignalHits,
                    confidenceBoost = sample.confidenceBoost,
                    refererDomain = sample.refererDomain
                )
                val isCommunityApp = RuleRepository.isCommunityAppHint(sample.lastAppName)
                val hasSomeSignals = sample.count >= 2 || sample.httpHits >= 1 || sample.pathHits >= 1
                isHighConfidence || (isCommunityApp && hasSomeSignals)
            }.map { it.domain }
            addDomains(recommended)
        }
        loadSamples()
    }

    override fun finish() {
        if (hasChanges) {
            setResult(Activity.RESULT_OK)
        }
        super.finish()
    }

    private fun loadSamples() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                val samples = RuleRepository.getSuspiciousDomainSamples(applicationContext)
                val added = samples.asSequence()
                    .map { it.domain }
                    .filter { RuleRepository.hasMatchingRule(applicationContext, it) }
                    .toSet()
                samples to added
            }
            if (isFinishing || isDestroyed) return@launch
            allSamples = snapshot.first
            addedDomains = snapshot.second
            selectedDomains.removeAll(addedDomains)
            applyFilters()
        }
    }

    private fun applyFilters() {
        val query = binding.searchInput.text?.toString().orEmpty().trim().lowercase()
        val filtered = allSamples.filter { sample ->
            val matchesQuery = query.isBlank() ||
                sample.domain.lowercase().contains(query) ||
                sample.lastAppName.lowercase().contains(query) ||
                sample.lastVendor.lowercase().contains(query) ||
                sample.refererDomain.lowercase().contains(query) ||
                sample.lastPathHint.lowercase().contains(query)
            if (!matchesQuery) return@filter false
            val isNovelSample = sample.novelHits > 0 || RuleRepository.isNovelVendor(sample.lastVendor)
            if (binding.checkOnlyNovelApps.isChecked && !isNovelSample) return@filter false
            val alreadyAdded = sample.domain in addedDomains
            if (binding.checkOnlyUnadded.isChecked && alreadyAdded) return@filter false
            if (binding.checkOnlyAdded.isChecked && !alreadyAdded) return@filter false
            true
        }
        adapter.submitList(filtered)
        binding.emptyText.text = if (allSamples.isEmpty()) "暂无可疑域名" else "当前筛选条件下暂无结果"
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        updateSummary(filtered)
    }

    private fun addDomains(domains: List<String>) {
        val pendingDomains = domains.distinct().filter { it !in addedDomains }
        if (pendingDomains.isEmpty()) {
            Toast.makeText(this, "当前没有可添加的域名", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val added = withContext(Dispatchers.Default) {
                RuleRepository.addRules(applicationContext, pendingDomains, RuleSource.MANUAL)
            }
            if (isFinishing || isDestroyed) return@launch
            if (added.isNotEmpty()) {
                hasChanges = true
                selectedDomains.removeAll(added.map { it.domain }.toSet())
                NetworkKernel.reloadIfRunning(this@SuspiciousDomainsActivity)
                Toast.makeText(this@SuspiciousDomainsActivity, "已添加 ${added.size} 条拦截规则", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SuspiciousDomainsActivity, "这些域名已经在规则里了", Toast.LENGTH_SHORT).show()
            }
            loadSamples()
        }
    }

    private fun updateSummary(visible: List<RuleRepository.SuspiciousDomainSample>) {
        val recommendCount = visible.count { sample ->
            val isHighConfidence = RuleRepository.isHighConfidenceSuspiciousDomain(
                domain = sample.domain,
                vendor = sample.lastVendor,
                novelHits = sample.novelHits,
                count = sample.count,
                appName = sample.lastAppName,
                dnsHits = sample.dnsHits,
                aliasHits = sample.aliasHits,
                tlsSniHits = sample.tlsSniHits,
                httpHits = sample.httpHits,
                pathHits = sample.pathHits,
                redirectHits = sample.redirectHits,
                appSignalHits = sample.appSignalHits,
                vendorSignalHits = sample.vendorSignalHits,
                confidenceBoost = sample.confidenceBoost,
                refererDomain = sample.refererDomain
            )
            val isCommunityApp = RuleRepository.isCommunityAppHint(sample.lastAppName)
            val hasSomeSignals = sample.count >= 2 || sample.httpHits >= 1 || sample.pathHits >= 1
            isHighConfidence || (isCommunityApp && hasSomeSignals)
        }
        binding.selectionSummary.text = buildString {
            append("共 ")
            append(visible.size)
            append(" 条")
            if (recommendCount > 0) {
                append("，推荐添加 ")
                append(recommendCount)
                append(" 条")
            }
        }
        val selectableVisibleCount = visible.count { it.domain !in addedDomains }
        val recommendedVisibleCount = recommendCount - visible.count { it.domain in addedDomains && RuleRepository.isHighConfidenceSuspiciousDomain(it.domain, it.lastVendor, it.novelHits, it.count, it.lastAppName, it.dnsHits, it.aliasHits, it.tlsSniHits, it.httpHits, it.pathHits, it.redirectHits, it.appSignalHits, it.vendorSignalHits, it.confidenceBoost, it.refererDomain) }
        val actionableSelectedCount = selectedDomains.count { domain ->
            visible.any { it.domain == domain && domain !in addedDomains }
        }
        binding.btnSelectVisible.isEnabled = selectableVisibleCount > 0
        binding.btnSelectVisible.alpha = if (selectableVisibleCount > 0) 1f else 0.6f
        binding.btnClearSelection.isEnabled = actionableSelectedCount > 0
        binding.btnClearSelection.alpha = if (actionableSelectedCount > 0) 1f else 0.6f
        binding.btnAddSelected.isEnabled = actionableSelectedCount > 0
        binding.btnAddSelected.alpha = if (actionableSelectedCount > 0) 1f else 0.6f
        binding.btnAddRecommended.isEnabled = recommendedVisibleCount > 0
        binding.btnAddRecommended.alpha = if (recommendedVisibleCount > 0) 1f else 0.6f
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, SuspiciousDomainsActivity::class.java)

        private const val SELECTION_PAYLOAD = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<RuleRepository.SuspiciousDomainSample>() {
            override fun areItemsTheSame(
                oldItem: RuleRepository.SuspiciousDomainSample,
                newItem: RuleRepository.SuspiciousDomainSample
            ): Boolean = oldItem.domain == newItem.domain

            override fun areContentsTheSame(
                oldItem: RuleRepository.SuspiciousDomainSample,
                newItem: RuleRepository.SuspiciousDomainSample
            ): Boolean = oldItem == newItem
        }
    }

    private inner class SuspiciousDomainAdapter(
        private val isSelected: (RuleRepository.SuspiciousDomainSample) -> Boolean,
        private val onToggle: (RuleRepository.SuspiciousDomainSample, Boolean) -> Unit,
        private val onAddSingle: (RuleRepository.SuspiciousDomainSample) -> Unit
    ) : ListAdapter<RuleRepository.SuspiciousDomainSample, SuspiciousDomainAdapter.ViewHolder>(DIFF) {
        fun syncSelection(changedDomains: Set<String>) {
            if (changedDomains.isEmpty()) return
            currentList.forEachIndexed { index, item ->
                if (item.domain in changedDomains) {
                    notifyItemChanged(index, SELECTION_PAYLOAD)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemSuspiciousDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(SELECTION_PAYLOAD)) {
                holder.bindSelectionState(getItem(position))
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        inner class ViewHolder(private val binding: ItemSuspiciousDomainBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: RuleRepository.SuspiciousDomainSample) {
                val score = RuleRepository.suspiciousDomainConfidenceScore(
                    domain = item.domain,
                    vendor = item.lastVendor,
                    novelHits = item.novelHits,
                    count = item.count,
                    appName = item.lastAppName,
                    dnsHits = item.dnsHits,
                    aliasHits = item.aliasHits,
                    tlsSniHits = item.tlsSniHits,
                    httpHits = item.httpHits,
                    pathHits = item.pathHits,
                    redirectHits = item.redirectHits,
                    appSignalHits = item.appSignalHits,
                    vendorSignalHits = item.vendorSignalHits,
                    confidenceBoost = item.confidenceBoost,
                    refererDomain = item.refererDomain
                )
                binding.domainText.text = item.domain
                binding.countText.text = "评分 $score，命中 ${item.count} 次，小说专项 ${item.novelHits} 次，最近 ${dateFormat.format(Date(item.lastSeenAt))}"
                val added = item.domain in addedDomains
                val reasonHints = buildReasonHints(item)
                binding.statusText.text = buildString {
                    append("最近应用：${item.lastAppName.ifBlank { "未知" }} | 厂商：${item.lastVendor} | ${if (added) "已添加" else "未添加"}")
                    if (reasonHints.isNotBlank()) {
                        append("\n线索：")
                        append(reasonHints)
                    }
                }
                bindSelectionState(item)
                binding.root.setOnClickListener {
                    if (!added) {
                        binding.selectBox.toggle()
                    }
                }
                binding.actionButton.text = if (added) "已添加" else "单条添加"
                binding.actionButton.isEnabled = !added
                binding.actionButton.setOnClickListener { onAddSingle(item) }
            }

            fun bindSelectionState(item: RuleRepository.SuspiciousDomainSample) {
                val added = item.domain in addedDomains
                binding.selectBox.setOnCheckedChangeListener(null)
                binding.selectBox.isChecked = isSelected(item)
                binding.selectBox.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
                binding.selectBox.isEnabled = !added
                binding.root.isEnabled = true
            }

            private fun buildReasonHints(item: RuleRepository.SuspiciousDomainSample): String {
                val hints = mutableListOf<String>()
                item.lastPathHint.takeIf { it.isNotBlank() }?.let { hints += "path=${it.take(48)}" }
                item.refererDomain.takeIf { it.isNotBlank() }?.let { hints += "referer=$it" }
                if (item.tlsSniHits > 0) hints += "SNI=${item.tlsSniHits}"
                if (item.httpHits > 0) hints += "HTTP=${item.httpHits}"
                if (item.redirectHits > 0) hints += "Redirect=${item.redirectHits}"
                if (item.aliasHits > 0) hints += "CNAME=${item.aliasHits}"
                return hints.joinToString(" | ")
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
