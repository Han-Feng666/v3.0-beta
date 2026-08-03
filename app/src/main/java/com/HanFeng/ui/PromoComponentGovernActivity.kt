package com.HanFeng.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.data.PromoComponentCandidate
import com.HanFeng.data.PromoGovernActionRepository
import com.HanFeng.data.PromoGovernComponentRepository
import com.HanFeng.databinding.ActivityPromoComponentGovernBinding
import com.HanFeng.databinding.ItemPromoComponentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PromoComponentGovernActivity : BaseActivity() {

    private lateinit var binding: ActivityPromoComponentGovernBinding
    private lateinit var adapter: ComponentAdapter
    private var packageNameValue: String = ""
    private var titleValue: String = ""
    private var recommendedComponents: List<PromoComponentCandidate> = emptyList()
    private var allActivities: List<PromoComponentCandidate> = emptyList()
    private var showAllActivities = false
    private var sdkOnly = false
    private var searchDebounceJob: Job? = null

    override fun onDestroy() {
        searchDebounceJob?.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPromoComponentGovernBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packageNameValue = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        titleValue = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { packageNameValue }
        if (packageNameValue.isBlank()) {
            showShortToast("缺少应用包名")
            finish()
            return
        }

        binding.titleText.text = "组件治理：$titleValue"
        adapter = ComponentAdapter { updateActionButtons() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRecommended.setOnClickListener {
            showAllActivities = false
            sdkOnly = false
            applyFilter()
            adapter.selectRecommendedVisible()
            showShortToast("已勾选推荐组件")
        }
        binding.btnSdkComponents.setOnClickListener {
            showAllActivities = true
            sdkOnly = true
            applyFilter()
            adapter.selectRecommendedVisible()
            showShortToast("已按 SDK 组件过滤并勾选")
        }
        binding.btnAllActivities.setOnClickListener {
            showAllActivities = true
            sdkOnly = false
            confirmShowAllActivitiesIfNeeded()
        }
        binding.btnManual.setOnClickListener { showManualComponentGovernDialog("$packageNameValue/") }
        binding.btnFreezeSelected.setOnClickListener { executeSelected(disable = true) }
        binding.btnUnfreezeSelected.setOnClickListener { executeSelected(disable = false) }
        binding.btnDisableAllSdk.setOnClickListener { executeDisableAllSdkComponents() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(180)
                    applyFilter()
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        updateActionButtons()
        loadComponents()
    }

    private fun loadComponents() {
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = "正在读取组件..."
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.Default) {
                val candidates = PromoGovernComponentRepository.discoverCandidates(this@PromoComponentGovernActivity, packageNameValue)
                val activities = PromoGovernComponentRepository.discoverActivities(this@PromoComponentGovernActivity, packageNameValue)
                candidates to activities
            }
            recommendedComponents = loaded.first
            allActivities = loaded.second
            binding.summaryText.text = "推荐组件 ${recommendedComponents.size} 个，全部 Activity ${allActivities.size} 个。默认只显示疑似推广组件，避免误冻结主入口、登录、支付等组件。"
            applyFilter()
            adapter.selectRecommendedVisible()
        }
    }

    private fun confirmShowAllActivitiesIfNeeded() {
        if (allActivities.size < 80) {
            applyFilter()
            return
        }
        StableDialog.builder(this)
            .setTitle("显示全部 Activity")
            .setMessage("该应用有 ${allActivities.size} 个 Activity。全部列表适合排查或做冰箱式冻结，普通治理优先使用推荐组件。")
            .setPositiveButton("显示") { _, _ -> applyFilter() }
            .setNegativeButton("取消") { _, _ ->
                showAllActivities = false
                applyFilter()
            }
            .showSafely(this, "Show all activities warning failed")
    }

    private fun applyFilter() {
        val query = binding.searchInput.text?.toString().orEmpty().trim().lowercase()
        val base = if (showAllActivities) {
            (recommendedComponents + allActivities).distinctBy { it.componentName }
                .sortedWith(compareByDescending<PromoComponentCandidate> { it.score }.thenBy { it.shortName })
        } else {
            recommendedComponents
        }
        // sdkOnly 模式: 基于基础集合只保留属于第三方 SDK 命名空间的组件.
        // 命名空间判定使用 PromoGovernComponentRepository.isSdkComponent,
        // 它扫描类名(com.xxx)前缀是否命中常见 SDK 厂商列表 (Pangle / YLH / JPush / Umeng 等).
        val sdkFiltered = if (sdkOnly) {
            base.filter { candidate ->
                // componentName 形如 "pkg/com.bytedance.sdk.openadsdk.activity.XActivity", 取 bar 之后即类名
                val className = candidate.componentName.substringAfter('/', "").trim()
                className.isNotBlank() && PromoGovernComponentRepository.isSdkComponent(className)
            }
        } else base
        val filtered = if (query.isBlank()) sdkFiltered else sdkFiltered.filter { candidate ->
            listOf(
                candidate.shortName,
                candidate.componentName,
                candidate.typeLabel,
                candidate.groupLabel,
                candidate.riskLabel,
                candidate.recommendation
            ).any { it.lowercase().contains(query) }
        }
        adapter.submit(filtered)
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = when {
            sdkOnly -> "该 App 当前没有识别到第三方 SDK 组件 (穿山甲/优量汇/友盟/极光等都不会显示在此)."
            showAllActivities -> "没有匹配的 Activity"
            else -> "没有推荐组件。可搜索组件名，或切换到全部 Activity。"
        }
        binding.btnRecommended.isEnabled = true
        binding.btnSdkComponents.isEnabled = !sdkOnly
        binding.btnAllActivities.isEnabled = !showAllActivities || sdkOnly
        updateActionButtons()
    }

    private fun updateActionButtons() {
        val count = adapter.selectedCandidates().size
        binding.btnFreezeSelected.text = if (count > 0) "冻结选中 ($count)" else "冻结选中"
        binding.btnUnfreezeSelected.text = if (count > 0) "解冻选中 ($count)" else "解冻选中"
    }

    private fun executeSelected(disable: Boolean) {
        val selected = adapter.selectedCandidates()
        if (selected.isEmpty()) {
            showShortToast("请先选择组件")
            return
        }
        val action = if (disable) "冻结" else "解冻"
        val message = if (showAllActivities && disable) {
            "将${action} ${selected.size} 个组件。冻结主入口、登录、支付或全部 Activity 可能导致 App 无法打开。"
        } else {
            "将${action} ${selected.size} 个组件。操作后可在本页重新勾选并解冻。"
        }
        StableDialog.builder(this)
            .setTitle("确认$action")
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> executeBatch(selected, disable) }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show component batch confirm failed")
    }

    private fun executeBatch(candidates: List<PromoComponentCandidate>, disable: Boolean) {
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = if (disable) "正在冻结组件..." else "正在解冻组件..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var successCount = 0
                val failed = mutableListOf<String>()
                candidates.forEach { candidate ->
                    val text = PromoGovernActionRepository.setComponentDisabled(
                        context = this@PromoComponentGovernActivity,
                        packageName = packageNameValue,
                        title = titleValue,
                        componentName = candidate.componentName,
                        disabled = disable,
                        componentWasEnabled = candidate.enabled
                    )
                    if (text.contains("成功")) successCount++ else failed += candidate.shortName
                }
                buildString {
                    append(if (disable) "冻结" else "解冻")
                    append("完成：成功 ")
                    append(successCount)
                    append(" / ")
                    append(candidates.size)
                    if (failed.isNotEmpty()) append("\n失败：${failed.take(8).joinToString("、")}")
                }
            }
            showMessageDialog("组件治理结果", result, "Show component govern result failed")
            adapter.clearSelection()
            updateActionButtons()
            loadComponents()
        }
    }

    /**
     * 一键禁用所有 SDK 组件: 扫描该应用的全部 Activity/Receiver/Service, 找出命名空间命中
     * PromoGovernComponentRepository.SDK_NAMESPACES 的组件, 批量 pm disable.
     *
     * 用例: 用户在淘宝/美团/今日头条里看到了穿山甲、优量汇、友盟等第三方 SDK, 不想逐项勾选,
     * 一键全部禁用, 减少广告/推送/上报/统计行为.
     */
    private fun executeDisableAllSdkComponents() {
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = "正在扫描 SDK 组件..."
        lifecycleScope.launch {
            val scanResult = withContext(Dispatchers.Default) {
                // 扫描更宽: 包括 Activity/Receiver/Service
                val packageInfo = runCatching {
                    this@PromoComponentGovernActivity.packageManager.getPackageInfo(
                        packageNameValue,
                        PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or
                            PackageManager.GET_SERVICES or
                            PackageManager.MATCH_DISABLED_COMPONENTS or
                            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                    )
                }.getOrNull()
                if (packageInfo == null) return@withContext emptyList<PromoComponentCandidate>()
                val collected = mutableListOf<PromoComponentCandidate>()
                packageInfo.activities.orEmpty().forEach { info ->
                    val fullName = info.name.orEmpty()
                    if (fullName.isNotBlank() && PromoGovernComponentRepository.isSdkComponent(fullName)) {
                        collected += PromoComponentCandidate(
                            componentName = "$packageNameValue/$fullName",
                            shortName = fullName.substringAfterLast('.'),
                            typeLabel = "Activity",
                            enabled = info.isEnabled,
                            score = 5,
                            groupLabel = "SDK 组件",
                            recommendation = "SDK 组件, 一键禁用命中.",
                            riskLabel = "低风险"
                        )
                    }
                }
                packageInfo.receivers.orEmpty().forEach { info ->
                    val fullName = info.name.orEmpty()
                    if (fullName.isNotBlank() && PromoGovernComponentRepository.isSdkComponent(fullName)) {
                        collected += PromoComponentCandidate(
                            componentName = "$packageNameValue/$fullName",
                            shortName = fullName.substringAfterLast('.'),
                            typeLabel = "Receiver",
                            enabled = info.isEnabled,
                            score = 5,
                            groupLabel = "SDK 组件",
                            recommendation = "SDK 组件, 一键禁用命中.",
                            riskLabel = "低风险"
                        )
                    }
                }
                packageInfo.services.orEmpty().forEach { info ->
                    val fullName = info.name.orEmpty()
                    if (fullName.isNotBlank() && PromoGovernComponentRepository.isSdkComponent(fullName)) {
                        collected += PromoComponentCandidate(
                            componentName = "$packageNameValue/$fullName",
                            shortName = fullName.substringAfterLast('.'),
                            typeLabel = "Service",
                            enabled = info.isEnabled,
                            score = 5,
                            groupLabel = "SDK 组件",
                            recommendation = "SDK 组件, 一键禁用命中.",
                            riskLabel = "低风险"
                        )
                    }
                }
                collected.distinctBy { it.componentName }.filter { it.enabled }
            }
            if (scanResult.isEmpty()) {
                showMessageDialog("一键禁用 SDK 组件", "该 App 当前没有识别到第三方 SDK 组件可直接禁用.", "Show empty SDK result failed")
                loadComponents()
                return@launch
            }
            val confirmMsg = buildString {
                append("已扫描出 ${scanResult.size} 个第三方 SDK 组件 (来自穿山甲/优量汇/友盟/极光/铃声等)\n\n")
                append("- 冻结后只损失对应 SDK 的广告展示、推送、统计、上报能力\n")
                append("- 应用主业务基本不受影响\n")
                append("- 部分集成推送 SDK 的应用可能收不到该 SDK 通道的推送通知\n")
                append("- 可在本页用「解冻选中」恢复\n\n")
                append("是否继续禁用全部 ${scanResult.size} 个?")
            }
            StableDialog.builder(this@PromoComponentGovernActivity)
                .setTitle("确认一键禁用 SDK 组件")
                .setMessage(confirmMsg)
                .setPositiveButton("禁用全部 SDK") { _, _ ->
                    executeBatch(scanResult, disable = true)
                }
                .setNegativeButton("取消", null)
                .showSafely(this@PromoComponentGovernActivity, "Show confirm disable all SDK failed")
        }
    }

    private fun showManualComponentGovernDialog(initialValue: String) {
        val input = EditText(this).apply {
            hint = "输入完整组件名，如 $packageNameValue/.SplashActivity"
            setText(initialValue)
            setSelection(text.length)
        }
        val dialog = StableDialog.builder(this)
            .setTitle("手动组件治理")
            .setMessage("适合处理列表里没有展示的组件。请输入完整组件名后选择动作。")
            .setView(input)
            .setPositiveButton("冻结组件", null)
            .setNeutralButton("解冻组件", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (runManualComponentAction(input, disable = true)) dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (runManualComponentAction(input, disable = false)) dialog.dismiss()
            }
        }
        dialog.showSafely(this, "Show manual component govern dialog failed")
    }

    private fun runManualComponentAction(input: EditText, disable: Boolean): Boolean {
        val componentName = input.text?.toString().orEmpty().trim()
        if (componentName.isBlank() || !componentName.contains('/')) {
            showShortToast("请输入完整组件名")
            return false
        }
        val candidate = PromoComponentCandidate(
            componentName = componentName,
            shortName = componentName.substringAfterLast('.').substringAfterLast('/'),
            typeLabel = "手动",
            enabled = disable,
            score = 0,
            groupLabel = "手动输入组件",
            recommendation = "由用户手动输入。",
            riskLabel = "需确认"
        )
        executeBatch(listOf(candidate), disable)
        return true
    }

    private class ComponentAdapter(
        private val onSelectionChanged: () -> Unit
    ) : ListAdapter<PromoComponentCandidate, ComponentAdapter.Holder>(DIFF) {
        private val selected = linkedSetOf<String>()

        fun submit(next: List<PromoComponentCandidate>) {
            selected.retainAll(next.map { it.componentName }.toSet())
            submitList(next)
            onSelectionChanged()
        }

        fun selectedCandidates(): List<PromoComponentCandidate> {
            return currentList.filter { selected.contains(it.componentName) }
        }

        fun clearSelection() {
            selected.clear()
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECT)
            onSelectionChanged()
        }

        fun selectRecommendedVisible() {
            selected.clear()
            currentList.filter { it.enabled && it.score > 0 }.forEach { selected += it.componentName }
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECT)
            onSelectionChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemPromoComponentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            onBindViewHolder(holder, position, mutableListOf())
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            val item = getItem(position)
            val checked = selected.contains(item.componentName)
            holder.bind(item, checked) { newChecked ->
                if (newChecked) selected += item.componentName else selected -= item.componentName
                onSelectionChanged()
            }
        }

        class Holder(private val binding: ItemPromoComponentBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: PromoComponentCandidate, checked: Boolean, onChecked: (Boolean) -> Unit) {
                binding.checkBox.setOnCheckedChangeListener(null)
                binding.checkBox.isChecked = checked
                binding.titleText.text = item.shortName
                val state = if (item.enabled) "启用中" else "已冻结"
                binding.metaText.text = "${item.groupLabel} / ${item.riskLabel} / $state / ${item.typeLabel}"
                binding.nameText.text = item.componentName
                binding.recommendText.text = item.recommendation
                binding.checkBox.setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
                binding.root.setOnClickListener { binding.checkBox.isChecked = !binding.checkBox.isChecked }
            }
        }

        companion object {
            private const val PAYLOAD_SELECT = 1
            private val DIFF = object : DiffUtil.ItemCallback<PromoComponentCandidate>() {
                override fun areItemsTheSame(oldItem: PromoComponentCandidate, newItem: PromoComponentCandidate): Boolean =
                    oldItem.componentName == newItem.componentName

                override fun areContentsTheSame(oldItem: PromoComponentCandidate, newItem: PromoComponentCandidate): Boolean =
                    oldItem == newItem
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_TITLE = "title"

        fun createIntent(context: Context, packageName: String, title: String): Intent {
            return Intent(context, PromoComponentGovernActivity::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_TITLE, title)
        }
    }
}
