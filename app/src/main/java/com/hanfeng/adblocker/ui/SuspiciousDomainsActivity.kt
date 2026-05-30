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
import com.HanFeng.data.RuleRepository
import com.HanFeng.databinding.ActivitySuspiciousDomainsBinding
import com.HanFeng.databinding.ItemSuspiciousDomainBinding
import com.HanFeng.model.RuleSource
import com.HanFeng.service.AdBlockVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SuspiciousDomainsActivity : BaseActivity() {
    private lateinit var binding: ActivitySuspiciousDomainsBinding
    private var allSamples: List<RuleRepository.SuspiciousDomainSample> = emptyList()
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
            adapter.currentList.forEach { selectedDomains += it.domain }
            adapter.notifyDataSetChanged()
            updateSummary(adapter.currentList)
        }
        binding.btnClearSelection.setOnClickListener {
            selectedDomains.clear()
            adapter.notifyDataSetChanged()
            updateSummary(adapter.currentList)
        }
        binding.btnAddSelected.setOnClickListener {
            addDomains(selectedDomains.toList())
        }
        binding.btnAddRecommended.setOnClickListener {
            val recommended = adapter.currentList.filter {
                RuleRepository.isHighConfidenceSuspiciousDomain(
                    domain = it.domain,
                    vendor = it.lastVendor,
                    novelHits = it.novelHits,
                    count = it.count,
                    appName = it.lastAppName,
                    dnsHits = it.dnsHits,
                    aliasHits = it.aliasHits,
                    tlsSniHits = it.tlsSniHits,
                    httpHits = it.httpHits,
                    pathHits = it.pathHits,
                    redirectHits = it.redirectHits,
                    appSignalHits = it.appSignalHits,
                    vendorSignalHits = it.vendorSignalHits,
                    confidenceBoost = it.confidenceBoost,
                    refererDomain = it.refererDomain
                )
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
            allSamples = withContext(Dispatchers.Default) {
                RuleRepository.getSuspiciousDomainSamples(applicationContext)
            }
            applyFilters()
        }
    }

    private fun applyFilters() {
        val query = binding.searchInput.text?.toString().orEmpty().trim().lowercase()
        val filtered = allSamples.filter { sample ->
            val matchesQuery = query.isBlank() ||
                sample.domain.lowercase().contains(query) ||
                sample.lastAppName.lowercase().contains(query) ||
                sample.lastVendor.lowercase().contains(query)
            if (!matchesQuery) return@filter false
            if (binding.checkOnlyNovelApps.isChecked && sample.novelHits <= 0) return@filter false
            val alreadyAdded = RuleRepository.hasMatchingRule(this, sample.domain)
            if (binding.checkOnlyUnadded.isChecked && alreadyAdded) return@filter false
            if (binding.checkOnlyAdded.isChecked && !alreadyAdded) return@filter false
            true
        }
        adapter.submitList(filtered)
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        updateSummary(filtered)
    }

    private fun addDomains(domains: List<String>) {
        if (domains.isEmpty()) {
            Toast.makeText(this, "当前没有可添加的域名", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val added = withContext(Dispatchers.Default) {
                RuleRepository.addRules(applicationContext, domains, RuleSource.MANUAL)
            }
            if (added.isNotEmpty()) {
                hasChanges = true
                selectedDomains.removeAll(added.map { it.domain }.toSet())
                if (AdBlockVpnService.isRunning) {
                    startService(Intent(this@SuspiciousDomainsActivity, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
                }
                Toast.makeText(this@SuspiciousDomainsActivity, "已添加 ${added.size} 条拦截规则", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SuspiciousDomainsActivity, "这些域名已经在规则里了", Toast.LENGTH_SHORT).show()
            }
            loadSamples()
        }
    }

    private fun updateSummary(visible: List<RuleRepository.SuspiciousDomainSample>) {
        binding.selectionSummary.text = "已选择 ${selectedDomains.size} 项，可选 ${allSamples.size} 项，当前可见 ${visible.size} 项"
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, SuspiciousDomainsActivity::class.java)

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
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemSuspiciousDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
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
                val added = RuleRepository.hasMatchingRule(this@SuspiciousDomainsActivity, item.domain)
                binding.statusText.text = "最近应用：${item.lastAppName.ifBlank { "未知" }} | 厂商：${item.lastVendor} | ${if (added) "已添加" else "未添加"}"
                binding.selectBox.setOnCheckedChangeListener(null)
                binding.selectBox.isChecked = isSelected(item)
                binding.selectBox.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
                binding.root.setOnClickListener { binding.selectBox.toggle() }
                binding.actionButton.text = if (added) "已添加" else "单条添加"
                binding.actionButton.isEnabled = !added
                binding.actionButton.setOnClickListener { onAddSingle(item) }
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
