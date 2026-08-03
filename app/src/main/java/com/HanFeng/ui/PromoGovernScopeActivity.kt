package com.HanFeng.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.PromoComponentCandidate
import com.HanFeng.data.PromoGovernActionRepository
import com.HanFeng.data.PromoGovernComponentRepository
import com.HanFeng.data.PromoGovernSnapshotRepository
import com.HanFeng.data.PromoGovernTarget
import com.HanFeng.data.PromoGovernTargetRepository
import com.HanFeng.data.ShizukuAdControlCatalog
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.databinding.ActivityPromoGovernScopeBinding
import com.HanFeng.databinding.ItemPromoGovernTargetBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RiskLevel { LOW, MEDIUM, HIGH }

class PromoGovernScopeActivity : BaseActivity() {
    private lateinit var binding: ActivityPromoGovernScopeBinding
    private lateinit var adapter: PromoGovernTargetAdapter
    private var currentScope = PromoGovernScope.ALL
    private var allTargets: List<PromoGovernTarget> = emptyList()
    private var visibleTargets: List<PromoGovernTarget> = emptyList()
    private var searchQuery: String = ""
    private var searchDebounceJob: Job? = null

    private val SYSTEM_CRITICAL_APPS = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.providers.contacts",
        "com.android.providers.telephony",
        "com.android.settings",
        "com.miui.home",
        "com.android.launcher3",
        "com.huawei.android.launcher",
        "com.heytap.customizehome",
        "com.vivo.home",
        "com.samsung.android.onehome"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPromoGovernScopeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }
        adapter = PromoGovernTargetAdapter { target -> showPromoTargetActionDialog(target) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnScopeAll.setOnClickListener { applyScope(PromoGovernScope.ALL) }
        binding.btnScopeSystem.visibility = View.GONE
        binding.btnScopeThirdParty.setOnClickListener { applyScope(PromoGovernScope.THIRD_PARTY_ONLY) }
        binding.btnGovernVisible.setOnClickListener { showBatchGovernDialog() }

        // 打开页面时复核一次治理名单通知 (后台 IO)，让治理状态保持一一对应
        lifecycleScope.launch(Dispatchers.IO) {
            if (ShizukuAdControlRepository.checkServiceHealth(this@PromoGovernScopeActivity)) {
                runCatching { ShizukuAdControlRepository.refreshBlockedPackagesNotifications(this@PromoGovernScopeActivity) }
            }
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty().trim().lowercase()
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(180)
                    applyScope(currentScope)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        loadTargets()
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
    }

    private fun loadTargets() {
        lifecycleScope.launch {
            LogRepository.append(this@PromoGovernScopeActivity, "loadTargets started")
            showShortToast("正在加载可治理 App 列表")
            if (!AppSettingsRepository.isShizukuEnabled(this@PromoGovernScopeActivity)) {
                StableDialog.builder(this@PromoGovernScopeActivity)
                    .setTitle("Shizuku 未启用")
                    .setMessage("请先开启设置中的 Shizuku 增强。")
                    .setPositiveButton("我知道了", null)
                    .showSafely(this@PromoGovernScopeActivity, "Show shizuku disabled dialog failed")
                LogRepository.append(this@PromoGovernScopeActivity, "loadTargets failed: Shizuku disabled")
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { ShizukuAdControlRepository.ensureBoundAndWait(this@PromoGovernScopeActivity) }
            }
            val discovery = withContext(Dispatchers.Default) {
                PromoGovernTargetRepository.discover(this@PromoGovernScopeActivity)
            }
            val installedCount = discovery.installedCount
            val loaded = discovery.targets
            LogRepository.append(
                this@PromoGovernScopeActivity,
                "[PromoGovern] installedApps=${discovery.installedCount}, eligibleApps=${discovery.eligibleCount}, excludedPureSystem=${discovery.excludedPureSystemCount}, includedByPreset=${discovery.includedByPresetCount}, includedByWellKnown=${discovery.includedByWellKnownCount}, scanned=${discovery.scannedCount}, targets=${loaded.size}, categories=${discovery.categoryCounts}"
            )
            if (isFinishing || isDestroyed) return@launch
            allTargets = loaded
            if (loaded.isEmpty()) {
                LogRepository.append(this@PromoGovernScopeActivity, "loadTargets completed: no targets found, installed=$installedCount")
                showShortToast("未识别到可治理 App，请查看日志了解原因")
                buildAndShowNoTargetsMessage(installedCount)
                binding.emptyText.visibility = View.VISIBLE
                binding.list.visibility = View.GONE
            } else {
                LogRepository.append(this@PromoGovernScopeActivity, "loadTargets completed: ${loaded.size} targets")
                showShortToast("已加载 ${loaded.size} 个可治理 App")
                binding.emptyText.visibility = View.GONE
                binding.list.visibility = View.VISIBLE
            }
            applyScope(currentScope)
        }
    }

    private fun buildAndShowNoTargetsMessage(installedCount: Int) {
        val logMessages = buildString {
            appendLine("已安装应用总数：$installedCount")
            appendLine()
            appendLine("推广治理仅扫描第三方 App（含系统预装的淘宝、美团、京东、今日头条等），纯系统组件不参与治理。")
            appendLine()
            appendLine("可能原因：")
            appendLine("1. 当前设备未安装命中识别规则的第三方推广 App")
            appendLine("2. Shizuku 未正确启动（请检查 Shizuku App）")
            appendLine()
            appendLine("识别规则：")
            appendLine("- 知名第三方 App 包名前缀（com.taobao./com.meituan./com.jingdong./com.ss.android.ugc.aweme 等）")
            appendLine("- 应用名称或包名含「淘宝」「美团」「京东」「头条」「抖音」「快手」「微博」「支付宝」「饿了么」「携程」等关键词")
            appendLine("- 推广类标签：「应用商店」「浏览器」「小说」「视频」「活动」「福利」等")
        }
        
        LogRepository.append(this@PromoGovernScopeActivity, logMessages)
        
        try {
            StableDialog.builder(this)
                .setTitle("未识别到可治理 App")
                .setMessage("已安装 $installedCount 个应用，未识别到可治理的第三方推广 App。\n\n推广治理覆盖系统预装的淘宝、美团、京东、今日头条等常见第三方 App，纯系统组件不参与治理。")
                .setPositiveButton("我知道了", null)
                .setNegativeButton("查看日志", { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra("scroll_to_logs", true)
                    })
                })
                .showSafely(this, "Show no targets dialog failed")
        } catch (e: Exception) {
            LogRepository.append(this@PromoGovernScopeActivity, "Show no targets dialog failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun refreshTargetsSilently() {
        val loaded = withContext(Dispatchers.Default) {
            PromoGovernTargetRepository.discover(this@PromoGovernScopeActivity).targets
        }
        if (isFinishing || isDestroyed) return
        allTargets = loaded
        applyScope(currentScope)
    }

    private fun applyScope(scope: PromoGovernScope) {
        currentScope = scope
        val visibleTargets = allTargets.filter { target ->
            when (scope) {
                PromoGovernScope.ALL -> true
                PromoGovernScope.SYSTEM_ONLY -> target.systemApp
                PromoGovernScope.THIRD_PARTY_ONLY -> !target.systemApp
            }
        }.filter(::matchesSearchQuery)
        this.visibleTargets = visibleTargets
        adapter.submitList(visibleTargets)
        binding.emptyText.visibility = if (visibleTargets.isEmpty()) View.VISIBLE else View.GONE
        binding.btnGovernVisible.isEnabled = visibleTargets.isNotEmpty()
        binding.btnGovernVisible.alpha = if (visibleTargets.isNotEmpty()) 1f else 0.5f
        binding.summaryText.text = buildSummaryText(visibleTargets)
        updateScopeButtons()
    }

    private fun matchesSearchQuery(target: PromoGovernTarget): Boolean {
        val query = searchQuery
        if (query.isBlank()) return true
        return target.title.lowercase().contains(query) ||
            target.packageName.lowercase().contains(query) ||
            target.category.lowercase().contains(query) ||
            target.sourceLabel.lowercase().contains(query) ||
            target.detectionTags.any { it.lowercase().contains(query) }
    }

    private fun buildSummaryText(targets: List<PromoGovernTarget>): String {
        val serviceState = if (ShizukuAdControlRepository.isServiceAlive()) "Shizuku 治理服务：已连接" else "Shizuku 治理服务：等待连接，执行动作时会再次检查"
        if (targets.isEmpty()) {
            return buildString {
                append(serviceState)
                append("\n\n当前范围或搜索条件下没有可治理 App。\n\n")
                append("说明：\n")
                append("推广治理覆盖系统预装的淘宝、美团、京东、今日头条等常见第三方 App，纯系统组件不参与治理。\n\n")
                append("可能原因：\n")
                append("1. 搜索词过窄\n")
                append("2. 当前范围没有匹配目标\n\n")
                append("识别规则：\n")
                append("知名第三方包名（淘宝/美团/京东/头条/抖音/快手等）或含「应用商店」「浏览器」「小说」「视频」「活动」「福利」等关键词")
            }
        }
        return "$serviceState\n已显示 ${targets.size} 个可治理 App。可按 App 名、包名、分类继续搜索；手动确认项执行冻结或暂停前会提示风险。"
    }

    private fun showBatchGovernDialog() {
        val targets = visibleTargets
        if (targets.isEmpty()) {
            showOperationResult("当前列表下没有可治理 App")
            return
        }
        StableDialog.builder(this)
            .setTitle("治理当前显示 App")
            .setMessage("将对当前筛选出的 ${targets.size} 个 App 执行智能治理。智能治理会优先关闭推送广告，必要时再尝试冻结或暂停。")
            .setPositiveButton("开始治理") { _, _ -> runBatchSmartGovern(targets) }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show batch govern dialog failed")
    }

    private fun runBatchSmartGovern(targets: List<PromoGovernTarget>) {
        lifecycleScope.launch {
            if (!ensureShizukuReady()) return@launch
            val message = withContext(Dispatchers.IO) {
                warmShizukuServicesBlocking()
                val operated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val failed = mutableListOf<String>()
                targets.forEach { target ->
                    if (ShizukuAdControlCatalog.shouldSkipBatchDisable(target.packageName)) {
                        skipped += target.title
                        return@forEach
                    }
                    val result = runCatching {
                        PromoGovernActionRepository.smartGovern(this@PromoGovernScopeActivity, target)
                    }.getOrElse { e ->
                        LogRepository.append(this@PromoGovernScopeActivity, "Batch govern failed for ${target.packageName}: ${e.message ?: e.javaClass.simpleName}")
                        failed += target.title
                        return@forEach
                    }
                    if (result.contains("成功") || result.contains("已自动回退")) {
                        operated += target.title
                    } else {
                        failed += "${target.title}（$result）"
                    }
                }
                buildBatchGovernResult(operated, skipped, failed)
            }
            if (isFinishing || isDestroyed) return@launch
            refreshTargetsSilently()
            showOperationResult(message)
        }
    }

    private fun buildBatchGovernResult(
        operated: List<String>,
        skipped: List<String>,
        failed: List<String>
    ): String {
        return buildString {
            append("批量智能治理完成")
            append("\n成功：${operated.size} 个")
            append("\n跳过：${skipped.size} 个")
            append("\n失败：${failed.size} 个")
            if (operated.isNotEmpty()) {
                append("\n\n已治理：")
                append(operated.take(12).joinToString("、"))
                if (operated.size > 12) append(" 等 ${operated.size} 个")
            }
            if (skipped.isNotEmpty()) {
                append("\n\n已跳过：")
                append(skipped.take(8).joinToString("、"))
                if (skipped.size > 8) append(" 等 ${skipped.size} 个")
            }
            if (failed.isNotEmpty()) {
                append("\n\n失败项：")
                append(failed.take(8).joinToString("、"))
                if (failed.size > 8) append(" 等 ${failed.size} 个")
            }
        }
    }

    private fun updateScopeButtons() {
        updateScopeButtonState(binding.btnScopeAll, currentScope == PromoGovernScope.ALL)
        updateScopeButtonState(binding.btnScopeThirdParty, currentScope == PromoGovernScope.THIRD_PARTY_ONLY)
    }

    private fun updateScopeButtonState(view: View, active: Boolean) {
        view.alpha = if (active) 1f else 0.72f
    }

    private suspend fun ensureShizukuReady(): Boolean {
        if (!AppSettingsRepository.isShizukuEnabled(this)) {
            StableDialog.builder(this)
                .setTitle("Shizuku 未启用")
                .setMessage("请先开启设置中的 Shizuku 增强。")
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show ensure shizuku enabled dialog failed")
            return false
        }
        val readyState = queryShizukuReadyState(warmIfNeeded = true)
        if (!readyState.readyForEnhancedUse) {
            StableDialog.builder(this)
                .setTitle("Shizuku 暂不可用")
                .setMessage(buildShizukuUnavailableMessage(readyState))
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show shizuku unavailable dialog failed")
            return false
        }
        if (!readyState.adControlAlive) {
            StableDialog.builder(this)
                .setTitle("Shizuku 服务连接失败")
                .setMessage("Shizuku 已连接，但治理服务还未成功绑定。请稍后重试，或重新进入 Shizuku 后再回来。")
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show shizuku bind failed dialog failed")
            return false
        }
        return true
    }

    private fun showPromoTargetActionDialog(target: PromoGovernTarget) {
        val status = ShizukuAdControlRepository.queryPackageStatus(this, target.packageName)
        val installed = isPackageInstalledLocally(target.packageName) || status.installed || target.packageStatus.installed
        val relatedPresets = target.relatedPresets
        val canDisable = installed && !PromoGovernActionRepository.isDisabledState(status.enabledState)
        val canEnable = installed && PromoGovernActionRepository.isDisabledState(status.enabledState)
        val canSuspend = installed && !status.suspended
        val canUnsuspend = installed && status.suspended
        val message = buildString {
            append(target.description)
            if (relatedPresets.size > 1) {
                append("\n\n同包治理标签：")
                append(relatedPresets.joinToString("、") { it.title })
            }
            ShizukuAdControlCatalog.batchProtectedReason(target.packageName)?.let { reason ->
                append("\n\n批量保护：")
                append("该项目属于")
                append(reason)
                append("，批量冻结和智能治理会默认跳过，建议仅在确认风险后手动处理。")
            }
            append("\n\n来源：")
            append(target.sourceLabel)
            append("\n\n分类：")
            append(target.category)
            append("\n包名：")
            append(target.packageName)
            append("\n应用类型：")
            append(if (target.systemApp) "系统 App" else "第三方 App")
            append("\n已安装：")
            append(if (installed) "是" else "否")
            append("\n当前状态：")
            append(status.enabledLabel)
            append("\n暂停状态：")
            append(if (status.suspended) "已暂停" else "未暂停")
            append("\n服务状态：")
            append(if (status.alive) "已连接" else "未连接")
            if (target.detectionTags.isNotEmpty()) {
                append("\n识别特征：")
                append(target.detectionTags.joinToString("、"))
            }
        }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (PromoGovernSnapshotRepository.latest(this)?.packageName == target.packageName) {
            actions += "恢复最近治理" to {
                executeGovernAction {
                    PromoGovernActionRepository.restoreLatest(this@PromoGovernScopeActivity)
                }
            }
        }
        if (installed && (canDisable || canSuspend)) {
            actions += "智能治理" to {
                executeGovernAction {
                    PromoGovernActionRepository.smartGovern(this@PromoGovernScopeActivity, target)
                }
            }
        }
        if (installed) {
            actions += "关闭推送广告" to {
                executePackageActionWithRisk(target, "关闭推送广告") {
                    PromoGovernActionRepository.setNotificationsBlocked(this@PromoGovernScopeActivity, target, blocked = true)
                }
            }
            actions += "恢复推送广告" to {
                executePackageActionWithRisk(target, "恢复推送广告") {
                    PromoGovernActionRepository.setNotificationsBlocked(this@PromoGovernScopeActivity, target, blocked = false)
                }
            }
        }
        if (canDisable) {
            actions += "冻结" to {
                executePackageActionWithRisk(target, "冻结") {
                    PromoGovernActionRepository.setPackageDisabled(this@PromoGovernScopeActivity, target, disabled = true)
                }
            }
        }
        if (canEnable) {
            actions += "解冻" to {
                executePackageActionWithRisk(target, "解冻") {
                    PromoGovernActionRepository.setPackageDisabled(this@PromoGovernScopeActivity, target, disabled = false)
                }
            }
        }
        if (canSuspend || canUnsuspend) {
            actions += (if (status.suspended) "恢复暂停" else "暂停") to {
                val actionText = if (status.suspended) "恢复暂停" else "暂停"
                executePackageActionWithRisk(target, actionText) {
                    PromoGovernActionRepository.setPackageSuspended(this@PromoGovernScopeActivity, target, suspended = !status.suspended)
                }
            }
        }
        if (installed) {
            actions += "组件治理" to {
                launchActivitySafely(
                    PromoComponentGovernActivity.createIntent(this, target.packageName, target.title),
                    "打开组件治理页面失败"
                )
            }
        }
        if (actions.isEmpty()) {
            showOperationResult("当前项目暂无可执行治理动作，请先确认目标应用已安装且 Shizuku 服务状态正常")
            return
        }
        StableDialog.builder(this)
            .setTitle("选择治理方式：${target.title}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .setNeutralButton("详情") { _, _ ->
                showGovernTargetDetails(target.title, message)
            }
            .setNegativeButton("关闭", null)
            .showSafely(this, "Show govern target actions dialog failed")
    }

    private fun showGovernTargetDetails(title: String, message: String) {
        StableDialog.builder(this)
            .setTitle("治理详情：$title")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .showSafely(this, "Show govern target details dialog failed")
    }

    private fun showComponentGovernDialog(target: PromoGovernTarget) {
        lifecycleScope.launch {
            val (candidates, activities) = withContext(Dispatchers.Default) {
                PromoGovernComponentRepository.discoverCandidates(this@PromoGovernScopeActivity, target.packageName) to
                    PromoGovernComponentRepository.discoverActivities(this@PromoGovernScopeActivity, target.packageName)
            }
            if (isFinishing || isDestroyed) return@launch
            if (candidates.isNotEmpty() || activities.isNotEmpty()) {
                val selectableComponents = (candidates + activities)
                    .distinctBy { it.componentName }
                    .sortedWith(compareByDescending<PromoComponentCandidate> { it.score }.thenBy { it.typeLabel }.thenBy { it.shortName })
                val checked = BooleanArray(selectableComponents.size) { index ->
                    selectableComponents[index].enabled && selectableComponents[index].score > 0
                }
                val labels = selectableComponents.map { candidate ->
                    buildComponentChoiceLabel(
                        groupLabel = candidate.groupLabel,
                        riskLabel = candidate.riskLabel,
                        enabled = candidate.enabled,
                        shortName = candidate.shortName,
                        componentName = candidate.componentName,
                        recommendation = candidate.recommendation
                    )
                }.toTypedArray()
                val dialog = StableDialog.builder(this@PromoGovernScopeActivity)
                    .setTitle("勾选要冻结的组件（${selectableComponents.size} 个）")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("冻结选中", null)
                    .setNeutralButton("全部 Activity", null)
                    .setNegativeButton("更多", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val selected = selectableComponents.filterIndexed { index, _ -> checked[index] }
                        if (selected.isEmpty()) {
                            showShortToast("请先选择组件")
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        executeComponentBatchAction(target, selected, disable = true)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        if (activities.isEmpty()) {
                            showShortToast("未识别到 Activity")
                            return@setOnClickListener
                        }
                        showAllActivityGovernDialog(target, activities)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        val selected = selectableComponents.filterIndexed { index, _ -> checked[index] }
                        showComponentMoreDialog(target, selected, selectableComponents.firstOrNull()?.componentName.orEmpty())
                    }
                }
                dialog.showSafely(this@PromoGovernScopeActivity, "Show component candidates dialog failed")
                return@launch
            }
            showManualComponentGovernDialog(target, "${target.packageName}/")
        }
    }

    private fun showComponentMoreDialog(
        target: PromoGovernTarget,
        selected: List<PromoComponentCandidate>,
        initialValue: String
    ) {
        val actions = arrayOf("解冻选中组件", "高级手动输入")
        StableDialog.builder(this)
            .setTitle("更多组件操作")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        if (selected.isEmpty()) {
                            showShortToast("请先选择组件")
                        } else {
                            executeComponentBatchAction(target, selected, disable = false)
                        }
                    }
                    1 -> showManualComponentGovernDialog(target, initialValue)
                }
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show component more actions dialog failed")
    }

    private fun buildComponentChoiceLabel(
        groupLabel: String,
        riskLabel: String,
        enabled: Boolean,
        shortName: String,
        componentName: String,
        recommendation: String
    ): String {
        val state = if (enabled) "启用中" else "已冻结"
        return "[$groupLabel / $riskLabel / $state] $shortName\n$componentName\n建议：$recommendation"
    }

    private fun showAllActivityGovernDialog(target: PromoGovernTarget, activities: List<PromoComponentCandidate>) {
        StableDialog.builder(this)
            .setTitle("全部 Activity 治理")
            .setMessage(
                "将处理 ${activities.size} 个 Activity。冻结全部 Activity 后，桌面图标可能消失，应用页面通常无法打开，效果接近冰箱冻结。解冻全部 Activity 可撤销该组件级处理。"
            )
            .setPositiveButton("冻结全部 Activity") { _, _ ->
                executeComponentBatchAction(target, activities, disable = true)
            }
            .setNeutralButton("解冻全部 Activity") { _, _ ->
                executeComponentBatchAction(target, activities, disable = false)
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show all activity govern dialog failed")
    }

    private fun showManualComponentGovernDialog(target: PromoGovernTarget, initialValue: String) {
        val input = EditText(this).apply {
            hint = "输入完整组件名，如 ${target.packageName}/.SplashActivity"
            setText(initialValue.ifBlank { "${target.packageName}/" })
            setSelection(text.length)
        }
        val dialog = StableDialog.builder(this)
            .setTitle("组件治理")
            .setMessage("适合处理启动页 Activity、推荐页 Activity、广告 Service、推送 Receiver 等单个组件。请输入完整组件名后选择动作。")
            .setView(input)
            .setPositiveButton("冻结组件", null)
            .setNeutralButton("解冻组件", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (runManualComponentAction(target, input, disable = true)) {
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (runManualComponentAction(target, input, disable = false)) {
                    dialog.dismiss()
                }
            }
        }
        dialog.showSafely(this, "Show manual component govern dialog failed") ?: return
    }

    private fun runManualComponentAction(target: PromoGovernTarget, input: EditText, disable: Boolean): Boolean {
        val componentName = input.text?.toString().orEmpty().trim()
        if (componentName.isBlank() || !componentName.contains('/')) {
            showShortToast("请输入完整组件名")
            return false
        }
        executeComponentToggleAction(
            packageName = target.packageName,
            title = target.title,
            componentName = componentName,
            disable = disable,
            componentWasEnabled = disable
        )
        return true
    }

    private fun executePackageActionWithRisk(target: PromoGovernTarget, actionLabel: String, block: () -> String) {
        if (assessRiskLevel(target, actionLabel) != RiskLevel.LOW) {
            showRiskConfirmationDialog(target, actionLabel) {
                executeGovernAction(block)
            }
        } else {
            executeGovernAction(block)
        }
    }

    private fun executeComponentToggleAction(
        packageName: String,
        title: String,
        componentName: String,
        disable: Boolean,
        componentWasEnabled: Boolean
    ) {
        executeGovernAction {
            PromoGovernActionRepository.setComponentDisabled(
                context = this@PromoGovernScopeActivity,
                packageName = packageName,
                title = title,
                componentName = componentName,
                disabled = disable,
                componentWasEnabled = componentWasEnabled
            )
        }
    }

    private fun executeComponentBatchAction(
        target: PromoGovernTarget,
        candidates: List<PromoComponentCandidate>,
        disable: Boolean
    ) {
        executeGovernAction {
            var successCount = 0
            val failed = mutableListOf<String>()
            candidates.forEach { candidate ->
                val result = PromoGovernActionRepository.setComponentDisabled(
                    context = this@PromoGovernScopeActivity,
                    packageName = target.packageName,
                    title = target.title,
                    componentName = candidate.componentName,
                    disabled = disable,
                    componentWasEnabled = candidate.enabled
                )
                if (result.contains("成功")) {
                    successCount += 1
                } else {
                    failed += candidate.shortName
                }
            }
            val actionText = if (disable) "冻结" else "解冻"
            buildString {
                append("组件${actionText}完成：成功 ")
                append(successCount)
                append(" 个，失败 ")
                append(failed.size)
                append(" 个")
                if (failed.isNotEmpty()) {
                    append("\n失败组件：")
                    append(failed.joinToString("、"))
                }
            }
        }
    }

    private fun executeGovernAction(block: () -> String) {
        lifecycleScope.launch {
            if (!ensureShizukuReady()) return@launch
            val message = try {
                withContext(Dispatchers.IO) {
                    warmShizukuServicesBlocking()
                    runCatching {
                        block()
                    }.getOrElse { e ->
                        LogRepository.append(this@PromoGovernScopeActivity, "Govern action failed: ${e.message ?: e.javaClass.simpleName}")
                        "操作失败：${e.message ?: "未知错误"}"
                    }
                }
            } catch (e: Exception) {
                LogRepository.append(this@PromoGovernScopeActivity, "Govern action execution failed: ${e.message ?: e.javaClass.simpleName}")
                "操作执行失败：${e.message ?: "请检查 Shizuku 服务状态"}"
            }
            if (isFinishing || isDestroyed) return@launch
            refreshTargetsSilently()
            showOperationResult(message)
        }
    }

    private fun isPackageInstalledLocally(packageName: String): Boolean {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    private fun assessRiskLevel(target: PromoGovernTarget, action: String): RiskLevel {
        return when {
            target.packageName in SYSTEM_CRITICAL_APPS -> RiskLevel.HIGH
            target.systemApp && (action.contains("冻结") || action.contains("停用") || action.contains("卸载")) -> RiskLevel.HIGH
            target.systemApp && action.contains("暂停") -> RiskLevel.MEDIUM
            action.contains("卸载") -> RiskLevel.HIGH
            target.detectionTags.contains("manual-confirm") && (action.contains("冻结") || action.contains("停用") || action.contains("暂停")) -> RiskLevel.MEDIUM
            target.systemApp -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    private fun showRiskConfirmationDialog(
        target: PromoGovernTarget,
        action: String,
        onConfirm: () -> Unit
    ) {
        val riskLevel = assessRiskLevel(target, action)
        val message = when (riskLevel) {
            RiskLevel.HIGH -> buildString {
                append("高风险操作提示\n\n")
                append("应用：${target.title}\n")
                append("包名：${target.packageName}\n")
                append("操作：$action\n\n")
                if (target.packageName in SYSTEM_CRITICAL_APPS) {
                    append("警告：这是系统核心应用，操作可能导致系统不稳定或功能异常。\n\n")
                } else if (action.contains("卸载")) {
                    append("警告：卸载将删除用户数据且不可恢复。\n\n")
                } else {
                    append("警告：冻结系统应用可能导致部分功能不可用。\n\n")
                }
                append("建议：操作前请确认已了解风险，必要时请先备份数据。\n\n")
                append("确认继续？")
            }
            RiskLevel.MEDIUM -> buildString {
                append("中等风险操作提示\n\n")
                append("应用：${target.title}\n")
                append("操作：$action\n\n")
                append("此操作可能影响部分系统功能，确认继续？")
            }
            RiskLevel.LOW -> "确定要对「${target.title}」执行 ${action} 吗？"
        }

        StableDialog.builder(this)
            .setTitle("操作确认")
            .setMessage(message)
            .setPositiveButton("确认") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show govern risk confirmation dialog failed")
    }

    private fun showOperationResult(message: String) {
        NetworkKernel.reloadIfRunning(this)
        val operationSummary = ShizukuAdControlRepository.getLastOperationSummary(this)
            .takeIf { it.isNotBlank() && it != "idle" }
        StableDialog.builder(this)
            .setMessage(
                buildString {
                    append(message)
                    operationSummary?.let {
                        append("\n\n服务反馈：")
                        append(it)
                    }
                }
            )
            .setPositiveButton("确定", null)
            .showSafely(this, "Show govern result dialog failed")
    }

    private inner class PromoGovernTargetAdapter(
        private val onClick: (PromoGovernTarget) -> Unit
    ) : ListAdapter<PromoGovernTarget, PromoGovernTargetAdapter.ViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemPromoGovernTargetBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(private val binding: ItemPromoGovernTargetBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: PromoGovernTarget) {
                binding.titleText.text = item.title
                binding.metaText.text = listOf(item.category, item.sourceLabel, item.packageName).joinToString(" | ")
                binding.descText.text = item.description
                binding.stateText.text = buildStateText(item)
                val openActions = View.OnClickListener { onClick(item) }
                binding.root.isClickable = true
                binding.root.isFocusable = false
                binding.root.setOnClickListener(openActions)
                binding.titleText.setOnClickListener(openActions)
                binding.metaText.setOnClickListener(openActions)
                binding.descText.setOnClickListener(openActions)
                binding.stateText.setOnClickListener(openActions)
                binding.btnGovern.isEnabled = true
                binding.btnGovern.isClickable = true
                binding.btnGovern.alpha = 1f
                binding.btnGovern.setOnClickListener(openActions)
            }
        }
    }

    private fun buildStateText(target: PromoGovernTarget): String {
        return when {
            target.packageStatus.suspended -> "已暂停"
            PromoGovernActionRepository.isDisabledState(target.packageStatus.enabledState) -> "已冻结"
            else -> if (target.systemApp) "系统" else "第三方"
        }
    }

    private enum class PromoGovernScope(val label: String) {
        ALL("全部"),
        SYSTEM_ONLY("系统推广项"),
        THIRD_PARTY_ONLY("第三方推广 App")
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, PromoGovernScopeActivity::class.java)

        private val DIFF = object : DiffUtil.ItemCallback<PromoGovernTarget>() {
            override fun areItemsTheSame(oldItem: PromoGovernTarget, newItem: PromoGovernTarget): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: PromoGovernTarget, newItem: PromoGovernTarget): Boolean {
                return oldItem == newItem
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
