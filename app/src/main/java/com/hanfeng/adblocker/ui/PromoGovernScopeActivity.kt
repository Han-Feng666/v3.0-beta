package com.HanFeng.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RiskLevel { LOW, MEDIUM, HIGH }

class PromoGovernScopeActivity : BaseActivity() {
    private lateinit var binding: ActivityPromoGovernScopeBinding
    private lateinit var adapter: PromoGovernTargetAdapter
    private var currentScope = PromoGovernScope.ALL
    private var allTargets: List<PromoGovernTarget> = emptyList()

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
            if (!ensureShizukuReady()) {
                LogRepository.append(this@PromoGovernScopeActivity, "loadTargets failed: Shizuku not ready")
                return@launch
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
        }
        adapter.submitList(visibleTargets)
        binding.emptyText.visibility = if (visibleTargets.isEmpty()) View.VISIBLE else View.GONE
        binding.summaryText.text = buildSummaryText(visibleTargets)
        updateScopeButtons()
    }

    private fun buildSummaryText(targets: List<PromoGovernTarget>): String {
        if (targets.isEmpty()) {
            return buildString {
                append("当前范围下没有识别到可治理的第三方 App。\n\n")
                append("说明：\n")
                append("推广治理覆盖系统预装的淘宝、美团、京东、今日头条等常见第三方 App，纯系统组件不参与治理。\n\n")
                append("可能原因：\n")
                append("1. 未安装疑似推广类第三方 App\n")
                append("2. 已安装的第三方 App 未命中识别规则\n\n")
                append("识别规则：\n")
                append("知名第三方包名（淘宝/美团/京东/头条/抖音/快手等）或含「应用商店」「浏览器」「小说」「视频」「活动」「福利」等关键词")
            }
        }
        return "已扫描第三方 App（含系统预装），共识别 ${targets.size} 个可治理推广 App。纯系统组件不参与治理。"
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
        val relatedPresets = target.relatedPresets
        val canDisable = status.installed && !PromoGovernActionRepository.isDisabledState(status.enabledState)
        val canEnable = status.installed && PromoGovernActionRepository.isDisabledState(status.enabledState)
        val canSuspend = status.installed && !status.suspended
        val canUnsuspend = status.installed && status.suspended
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
                append("，批量停用和智能治理会默认跳过，建议仅在确认风险后手动处理。")
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
            append(if (status.installed) "是" else "否")
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
        if (status.installed && (canDisable || canSuspend)) {
            actions += "智能治理" to {
                executeGovernAction {
                    PromoGovernActionRepository.smartGovern(this@PromoGovernScopeActivity, target)
                }
            }
        }
        if (status.installed) {
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
            actions += "停用" to {
                executePackageActionWithRisk(target, "停用") {
                    PromoGovernActionRepository.setPackageDisabled(this@PromoGovernScopeActivity, target, disabled = true)
                }
            }
        }
        if (canEnable) {
            actions += "恢复" to {
                executePackageActionWithRisk(target, "恢复") {
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
        if (status.installed) {
            actions += "组件治理" to {
                showComponentGovernDialog(target)
            }
        }
        if (actions.isEmpty()) {
            showOperationResult("当前项目暂无可执行治理动作，请先确认目标应用已安装且 Shizuku 服务状态正常")
            return
        }
        StableDialog.builder(this)
            .setTitle(target.title)
            .setMessage(message)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton("关闭", null)
            .showSafely(this, "Show govern target actions dialog failed")
    }

    private fun showComponentGovernDialog(target: PromoGovernTarget) {
        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.Default) {
                PromoGovernComponentRepository.discoverCandidates(this@PromoGovernScopeActivity, target.packageName)
            }
            if (isFinishing || isDestroyed) return@launch
            if (candidates.isNotEmpty()) {
                StableDialog.builder(this@PromoGovernScopeActivity)
                    .setTitle("组件治理")
                    .setMessage("已识别 ${candidates.size} 个高相关组件，可直接选择治理，也可以进入手动输入。")
                    .setItems(candidates.map { candidate ->
                        val state = if (candidate.enabled) "启用中" else "已停用"
                        "[${candidate.groupLabel}/${candidate.riskLabel}/$state] ${candidate.shortName}"
                    }.toTypedArray()) { _, which ->
                        showComponentActionDialog(target, candidates[which])
                    }
                    .setNeutralButton("手动输入") { _, _ ->
                        showManualComponentGovernDialog(target, candidates.firstOrNull()?.componentName.orEmpty())
                    }
                    .setNegativeButton("取消", null)
                    .showSafely(this@PromoGovernScopeActivity, "Show component candidates dialog failed")
                return@launch
            }
            showManualComponentGovernDialog(target, "${target.packageName}/")
        }
    }

    private fun showComponentActionDialog(target: PromoGovernTarget, candidate: PromoComponentCandidate) {
        StableDialog.builder(this)
            .setTitle("组件治理")
            .setMessage(buildString {
                append(candidate.componentName)
                append("\n\n类型：")
                append(candidate.groupLabel)
                append("\n风险：")
                append(candidate.riskLabel)
                append("\n建议：")
                append(candidate.recommendation)
            })
            .setPositiveButton("停用组件") { _, _ ->
                executeComponentToggleAction(
                    packageName = target.packageName,
                    title = target.title,
                    componentWasEnabled = candidate.enabled,
                    componentName = candidate.componentName,
                    disable = true
                )
            }
            .setNeutralButton("恢复组件") { _, _ ->
                executeComponentToggleAction(
                    packageName = target.packageName,
                    title = target.title,
                    componentWasEnabled = candidate.enabled,
                    componentName = candidate.componentName,
                    disable = false
                )
            }
            .setNegativeButton("手动输入") { _, _ ->
                showManualComponentGovernDialog(target, candidate.componentName)
            }
            .showSafely(this, "Show component actions dialog failed")
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
            .setPositiveButton("停用组件", null)
            .setNeutralButton("恢复组件", null)
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

    private fun assessRiskLevel(target: PromoGovernTarget, action: String): RiskLevel {
        return when {
            target.packageName in SYSTEM_CRITICAL_APPS -> RiskLevel.HIGH
            target.systemApp && (action.contains("停用") || action.contains("卸载")) -> RiskLevel.HIGH
            target.systemApp && action.contains("暂停") -> RiskLevel.MEDIUM
            action.contains("卸载") -> RiskLevel.HIGH
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
                    append("警告：停用系统应用可能导致部分功能不可用。\n\n")
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

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("操作确认")
            .setMessage(message)
            .setPositiveButton("确认") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
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
                binding.root.isClickable = true
                binding.root.isFocusable = false
                binding.root.setOnClickListener { 
                    showShortToast("点击条目：${item.title}")
                    onClick(item) 
                }
                val status = item.packageStatus
                if (status.installed) {
                    binding.btnGovern.isEnabled = true
                    binding.btnGovern.isClickable = true
                    binding.btnGovern.alpha = 1f
                    binding.btnGovern.setOnClickListener { v ->
                        v.isPressed = true
                        showShortToast("点击治理：${item.title}")
                        showPromoTargetActionDialog(item)
                    }
                } else {
                    binding.btnGovern.isEnabled = false
                    binding.btnGovern.isClickable = false
                    binding.btnGovern.alpha = 0.5f
                    binding.btnGovern.setOnClickListener(null)
                }
            }
        }
    }

    private fun buildStateText(target: PromoGovernTarget): String {
        return when {
            target.packageStatus.suspended -> "已暂停"
            PromoGovernActionRepository.isDisabledState(target.packageStatus.enabledState) -> "已停用"
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
