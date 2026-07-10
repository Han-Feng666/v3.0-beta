package com.HanFeng.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.databinding.ActivitySuspiciousDomainsBinding
import com.HanFeng.databinding.ItemSuspiciousDomainBinding
import com.HanFeng.model.RuleSource
import com.HanFeng.service.AdBlockVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SuspiciousDomainsActivity : BaseActivity() {
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private lateinit var binding: ActivitySuspiciousDomainsBinding
    private val allSamples = mutableListOf<SuspiciousDomainItem>()
    private val visibleSamples = mutableListOf<SuspiciousDomainItem>()
    private val selectedDomains = linkedSetOf<String>()
    private var hasChanges = false
    private var syncingFilterChecks = false
    private var batchAdding = false

    private val adapter = SuspiciousDomainAdapter(
        onToggle = { sample, checked ->
            if (checked) selectedDomains += sample.domain else selectedDomains -= sample.domain
            updateSelectionSummary()
        },
        onActions = { sample -> addSingleRule(sample.domain) },
        onLongPress = { sample -> showDomainActions(sample) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySuspiciousDomainsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialTopPadding = binding.rootLayout.paddingTop
        val initialBottomPadding = binding.rootLayout.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                initialTopPadding + systemBars.top,
                view.paddingRight,
                initialBottomPadding + systemBars.bottom
            )
            insets
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSelectVisible.setOnClickListener {
            visibleSamples.filterNot { it.alreadyAdded }.forEach { selectedDomains += it.domain }
            adapter.setSelection(selectedDomains)
            updateSelectionSummary()
        }
        binding.btnClearSelection.setOnClickListener {
            selectedDomains.clear()
            adapter.setSelection(selectedDomains)
            updateSelectionSummary()
        }
        binding.btnAddRecommended.setOnClickListener { addRecommendedRules() }
        binding.btnAddSelected.setOnClickListener { addSelectedRules() }
        binding.searchInput.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }
        binding.checkOnlyUnadded.setOnCheckedChangeListener { _, _ ->
            if (!syncingFilterChecks) {
                syncExclusiveFilter(isUnadded = true)
            }
            applyFilter(binding.searchInput.text?.toString().orEmpty())
        }
        binding.checkOnlyAdded.setOnCheckedChangeListener { _, _ ->
            if (!syncingFilterChecks) {
                syncExclusiveFilter(isUnadded = false)
            }
            applyFilter(binding.searchInput.text?.toString().orEmpty())
        }
        binding.checkOnlyNovelApps.setOnCheckedChangeListener { _, _ ->
            applyFilter(binding.searchInput.text?.toString().orEmpty())
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
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = "正在加载可疑域名..."
        lifecycleScope.launch {
            val samples = withContext(Dispatchers.Default) {
                RuleRepository.getSuspiciousDomainSamples(this@SuspiciousDomainsActivity).map { sample ->
                    val confidenceScore = RuleRepository.suspiciousDomainConfidenceScore(
                        domain = sample.domain,
                        vendor = sample.lastVendor,
                        novelHits = sample.novelHits,
                        count = sample.count,
                        appName = sample.lastAppName
                    )
                    SuspiciousDomainItem(
                        domain = sample.domain,
                        count = sample.count,
                        lastSeenAt = sample.lastSeenAt,
                        alreadyAdded = RuleRepository.hasMatchingRule(this@SuspiciousDomainsActivity, sample.domain),
                        lastAppName = sample.lastAppName,
                        vendor = sample.lastVendor,
                        novelHits = sample.novelHits,
                        confidenceScore = confidenceScore,
                        highConfidence = confidenceScore >= 6
                    )
                }
            }
            allSamples.clear()
            allSamples += samples
            applyFilter(binding.searchInput.text?.toString().orEmpty())
        }
    }

    private fun applyFilter(query: String) {
        val keyword = query.trim().lowercase()
        val onlyUnadded = binding.checkOnlyUnadded.isChecked
        val onlyAdded = binding.checkOnlyAdded.isChecked
        val onlyNovelApps = binding.checkOnlyNovelApps.isChecked
        visibleSamples.clear()
        visibleSamples += allSamples.filter { item ->
            val matchesKeyword = keyword.isBlank() ||
                item.domain.contains(keyword) ||
                item.lastAppName.lowercase().contains(keyword) ||
                item.vendor.lowercase().contains(keyword)
            val matchesAddedState = when {
                onlyUnadded -> !item.alreadyAdded
                onlyAdded -> item.alreadyAdded
                else -> true
            }
            val matchesNovel = !onlyNovelApps || item.novelHits > 0 || RuleRepository.isNovelVendor(item.vendor)
            matchesKeyword && matchesAddedState && matchesNovel
        }.sortedWith(
            compareByDescending<SuspiciousDomainItem> { it.highConfidence }
                .thenByDescending { it.confidenceScore }
                .thenByDescending { it.novelHits }
                .thenByDescending { it.count }
                .thenByDescending { it.lastSeenAt }
                .thenBy { it.domain }
        )
        adapter.submit(visibleSamples, selectedDomains)
        binding.emptyText.text = if (allSamples.isEmpty()) "暂无可疑域名" else "当前筛选条件下暂无结果"
        binding.emptyText.visibility = if (visibleSamples.isEmpty()) View.VISIBLE else View.GONE
        updateSelectionSummary()
    }

    private fun updateSelectionSummary() {
        val selectableVisibleCount = visibleSamples.count { !it.alreadyAdded }
        val novelVisibleCount = visibleSamples.count { it.novelHits > 0 || RuleRepository.isNovelVendor(it.vendor) }
        val recommendedVisibleCount = visibleSamples.count { it.highConfidence && !it.alreadyAdded }
        binding.selectionSummary.text = "已选择 ${selectedDomains.size} 项，可选 ${selectableVisibleCount} 项，当前可见 ${visibleSamples.size} 项，推荐 ${recommendedVisibleCount} 项，小说专项 ${novelVisibleCount} 项"
        binding.btnSelectVisible.isEnabled = selectableVisibleCount > 0
        binding.btnSelectVisible.alpha = if (selectableVisibleCount > 0) 1f else 0.6f
        binding.btnAddRecommended.isEnabled = recommendedVisibleCount > 0 && !batchAdding
        binding.btnAddRecommended.alpha = if (recommendedVisibleCount > 0 && !batchAdding) 1f else 0.6f
        binding.btnClearSelection.isEnabled = selectedDomains.isNotEmpty()
        binding.btnClearSelection.alpha = if (selectedDomains.isNotEmpty()) 1f else 0.6f
        binding.btnAddSelected.isEnabled = selectedDomains.isNotEmpty() && !batchAdding
        binding.btnAddSelected.alpha = if (selectedDomains.isNotEmpty() && !batchAdding) 1f else 0.6f
    }

    private fun addRecommendedRules() {
        if (batchAdding) return
        val recommendedDomains = visibleSamples
            .filter { it.highConfidence && !it.alreadyAdded }
            .map { it.domain }
        if (recommendedDomains.isEmpty()) {
            Toast.makeText(this, "当前没有可直接添加的推荐规则", Toast.LENGTH_SHORT).show()
            return
        }
        addDomainsInBackground(recommendedDomains, "已新增 %d 条推荐拦截规则，拦截服务已刷新")
    }

    private fun syncExclusiveFilter(isUnadded: Boolean) {
        syncingFilterChecks = true
        if (isUnadded && binding.checkOnlyUnadded.isChecked) {
            binding.checkOnlyAdded.isChecked = false
        }
        if (!isUnadded && binding.checkOnlyAdded.isChecked) {
            binding.checkOnlyUnadded.isChecked = false
        }
        syncingFilterChecks = false
    }

    private fun addSelectedRules() {
        if (selectedDomains.isEmpty()) {
            Toast.makeText(this, "请先选择要添加的域名", Toast.LENGTH_SHORT).show()
            return
        }
        addDomainsInBackground(selectedDomains.toList(), "已新增 %d 条拦截规则，拦截服务已刷新")
    }

    private fun addDomainsInBackground(domains: List<String>, successMessageTemplate: String) {
        if (batchAdding) return
        batchAdding = true
        binding.btnAddSelected.isEnabled = false
        binding.btnAddSelected.alpha = 0.6f
        binding.btnAddRecommended.isEnabled = false
        binding.btnAddRecommended.alpha = 0.6f
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = "正在添加拦截规则..."
        lifecycleScope.launch {
            val added = withContext(Dispatchers.Default) {
                RuleRepository.addRules(this@SuspiciousDomainsActivity, domains, RuleSource.MANUAL)
            }
            batchAdding = false
            val addedCount = added.size
            if (addedCount <= 0) {
                binding.emptyText.visibility = if (visibleSamples.isEmpty()) View.VISIBLE else View.GONE
                Toast.makeText(this@SuspiciousDomainsActivity, "这些域名已经添加过了", Toast.LENGTH_SHORT).show()
                updateSelectionSummary()
                return@launch
            }
            hasChanges = true
            markDomainsAsAdded(added.map { it.domain })
            selectedDomains.clear()
            adapter.setSelection(selectedDomains)
            startService(Intent(this@SuspiciousDomainsActivity, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
            LogRepository.append(this@SuspiciousDomainsActivity, "Batch added $addedCount suspicious domain rules")
            Toast.makeText(this@SuspiciousDomainsActivity, successMessageTemplate.format(addedCount), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun markDomainsAsAdded(domains: Collection<String>) {
        if (domains.isEmpty()) return
        val targetSet = domains.toSet()
        allSamples.replaceAll { item ->
            if (targetSet.contains(item.domain)) item.copy(alreadyAdded = true) else item
        }
        visibleSamples.replaceAll { item ->
            if (targetSet.contains(item.domain)) item.copy(alreadyAdded = true) else item
        }
        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    private fun showDomainActions(sample: SuspiciousDomainItem) {
        val actions = arrayOf("添加拦截规则", "手动分类后添加", "复制域名")
        styleDialog(
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
                .setTitle(sample.domain)
                .setMessage(
                    "最近出现：${formatTimestamp(sample.lastSeenAt)}\n" +
                        "最近应用：${sample.lastAppName.ifBlank { "未知应用" }}\n" +
                        "最近厂商：${sample.vendor}\n" +
                        "累计出现：${sample.count} 次\n" +
                        "小说专项：${sample.novelHits} 次"
                )
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> addSingleRule(sample.domain)
                        1 -> showVendorDialog(sample.domain)
                        2 -> copyDomain(sample.domain)
                    }
                }
                .setNegativeButton("关闭", null)
                .create()
        )
    }

    private fun addSingleRule(domain: String) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.Default) {
                RuleRepository.addRule(this@SuspiciousDomainsActivity, domain, RuleSource.MANUAL)
            }
            if (added == null) {
                Toast.makeText(this@SuspiciousDomainsActivity, "规则无效或已存在", Toast.LENGTH_SHORT).show()
                return@launch
            }
            hasChanges = true
            markDomainsAsAdded(setOf(domain))
            startService(Intent(this@SuspiciousDomainsActivity, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
            Toast.makeText(this@SuspiciousDomainsActivity, "已添加拦截规则并刷新拦截服务", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVendorDialog(domain: String) {
        val input = EditText(this).apply {
            hint = "例如：QXM (QXM Ads)"
            setText(RuleRepository.classifyVendor(this@SuspiciousDomainsActivity, domain))
            background = ContextCompat.getDrawable(this@SuspiciousDomainsActivity, R.drawable.bg_panel)
            setTextColor(ContextCompat.getColor(this@SuspiciousDomainsActivity, R.color.hf_text_primary))
            setHintTextColor(ContextCompat.getColor(this@SuspiciousDomainsActivity, R.color.hf_text_secondary))
            setPadding(24, 20, 24, 20)
        }
        val dialog = styleDialog(
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
                .setTitle("添加并分类")
                .setView(input)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create()
        )
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val vendor = input.text?.toString().orEmpty().trim()
                if (vendor.isBlank()) {
                    Toast.makeText(this, "分组名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val targetRule = withContext(Dispatchers.Default) {
                        val added = RuleRepository.addRule(this@SuspiciousDomainsActivity, domain, RuleSource.MANUAL)
                        added ?: RuleRepository.findMatchingRule(this@SuspiciousDomainsActivity, domain)
                    }
                    if (targetRule == null) {
                        Toast.makeText(this@SuspiciousDomainsActivity, "规则无效", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    withContext(Dispatchers.Default) {
                        RuleRepository.updateRuleVendor(this@SuspiciousDomainsActivity, targetRule.id, vendor)
                    }
                    hasChanges = true
                    markDomainsAsAdded(setOf(domain))
                    startService(Intent(this@SuspiciousDomainsActivity, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
                    Toast.makeText(this@SuspiciousDomainsActivity, "已添加并分类，拦截服务已刷新", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun copyDomain(domain: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("suspicious-domain", domain))
        Toast.makeText(this, "域名已复制", Toast.LENGTH_SHORT).show()
    }

    private fun styleDialog(dialog: AlertDialog): AlertDialog {
        val buttonBackground = ContextCompat.getDrawable(dialog.context, R.drawable.bg_button)
        val textColor = ContextCompat.getColor(dialog.context, R.color.hf_text_primary)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                backgroundTintList = null
                setBackground(buttonBackground?.constantState?.newDrawable()?.mutate())
                setTextColor(textColor)
            }
        }
        dialog.show()
        return dialog
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "未知"
        return timeFormatter.format(Date(timestamp))
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, SuspiciousDomainsActivity::class.java)
    }
}

private data class SuspiciousDomainItem(
    val domain: String,
    val count: Int,
    val lastSeenAt: Long,
    val alreadyAdded: Boolean,
    val lastAppName: String,
    val vendor: String,
    val novelHits: Int,
    val confidenceScore: Int,
    val highConfidence: Boolean
)

private class SuspiciousDomainAdapter(
    private val onToggle: (SuspiciousDomainItem, Boolean) -> Unit,
    private val onActions: (SuspiciousDomainItem) -> Unit,
    private val onLongPress: (SuspiciousDomainItem) -> Unit
) : RecyclerView.Adapter<SuspiciousDomainAdapter.ViewHolder>() {
    private val items = mutableListOf<SuspiciousDomainItem>()
    private val selectedDomains = mutableSetOf<String>()

    fun submit(samples: List<SuspiciousDomainItem>, selected: Set<String>) {
        items.clear()
        items += samples
        selectedDomains.clear()
        selectedDomains += selected
        notifyDataSetChanged()
    }

    fun setSelection(selected: Set<String>) {
        selectedDomains.clear()
        selectedDomains += selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSuspiciousDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: ItemSuspiciousDomainBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SuspiciousDomainItem) {
            binding.domainText.text = item.domain
            val appPart = item.lastAppName.ifBlank { "未知应用" }
            binding.countText.text = "最近出现：${formatItemTimestamp(item.lastSeenAt)}  ·  应用：$appPart"
            val novelPart = if (item.novelHits > 0) "  ·  小说专项 ${item.novelHits} 次" else ""
            val confidencePart = if (item.highConfidence) "  ·  推荐 ${item.confidenceScore} 分" else "  ·  参考 ${item.confidenceScore} 分"
            binding.statusText.text = (if (item.alreadyAdded) "状态：已添加规则" else "状态：未添加规则") + "  ·  厂商：${item.vendor}  ·  累计出现 ${item.count} 次$novelPart$confidencePart"
            binding.selectBox.setOnCheckedChangeListener(null)
            binding.selectBox.isChecked = selectedDomains.contains(item.domain)
            binding.selectBox.isEnabled = !item.alreadyAdded
            binding.selectBox.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
            binding.root.setOnClickListener {
                if (!item.alreadyAdded) {
                    binding.selectBox.toggle()
                }
            }
            binding.root.setOnLongClickListener {
                onLongPress(item)
                true
            }
            binding.actionButton.text = if (item.alreadyAdded) "已添加" else "单条添加"
            binding.actionButton.isEnabled = !item.alreadyAdded
            binding.actionButton.alpha = if (item.alreadyAdded) 0.6f else 1f
            binding.actionButton.setOnClickListener {
                if (!item.alreadyAdded) {
                    onActions(item)
                }
            }
            binding.actionButton.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }

        private fun formatItemTimestamp(timestamp: Long): String {
            if (timestamp <= 0L) return "未知"
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
        }
    }
}
