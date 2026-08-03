package com.HanFeng.ui.capture

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.HanFeng.R
import com.HanFeng.capture.CaptureController
import com.HanFeng.capture.CaptureFilter
import com.HanFeng.capture.CaptureRepository
import com.HanFeng.databinding.FragmentCaptureBinding
import com.HanFeng.databinding.ItemCaptureEntryBinding
import com.HanFeng.ui.applyCustomAssetBackground
import com.HanFeng.ui.applyCustomFileBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 抓包主入口 Fragment(第 4 个主 Tab)。
 *
 * - 顶部 Toolbar + 开关 + 模式过滤(全部/按 App)
 * - 中央条目列表; 订阅 [CaptureController.entries] Flow 增量更新
 * - 条目点击进入 [CaptureDetailActivity]
 *
 * 引用 design.md Components #11 / requirements R1 / R4。
 */
class CaptureFragment : Fragment(R.layout.fragment_capture) {

    private var _binding: FragmentCaptureBinding? = null
    private val binding get() = _binding!!

    private var adapter: CaptureEntryAdapter? = null
    private var filter: CaptureFilter = CaptureFilter()
    private var highlightTerm: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCaptureBinding.bind(view)
        adapter = CaptureEntryAdapter(
            onClick = { txnId -> openDetail(txnId) },
            onLongClick = { txnId -> promptRowContextualMenu(txnId) },
            onSelectionChanged = ::refreshSelectionCount
        )
        binding.captureList.layoutManager = LinearLayoutManager(requireContext())
        binding.captureList.adapter = adapter

        applyBackgroundImage(binding.captureBackground)

        // 批次 D4 联动: 批量重放入口 — 顶部"选择"按钮进入选择模式; 选择条 row 弹批量重放
        binding.btnEnterSelection.setOnClickListener { startSelectionMode() }
        binding.selectionBar.visibility = View.GONE

        binding.captureToggle.setOnCheckedChangeListener { _, isChecked ->
            onToggleChanged(isChecked)
        }

        binding.modeChipAllApps.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                binding.modeChipByApp.isChecked = false
                persistMode(CaptureController.Mode.ALL_APPS)
            }
        }
        binding.modeChipByApp.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                binding.modeChipAllApps.isChecked = false
                persistMode(CaptureController.Mode.BY_APP)
            }
        }

        binding.filterChip.setOnClickListener {
            CaptureFilterDialogFragment(filter) { newFilter ->
                filter = newFilter
                adapter?.applyFilter(newFilter, highlight = highlightTerm)
                refreshEmptyState()
            }.show(parentFragmentManager, "captureFilter")
        }

        // 批次 E5d: 顶部快速搜索框 — 实时把关键字写入 CaptureFilter.keyword 触发过滤,
        // 也把 keyword 透传给 adapter 用于 host/path 高亮(无关键字时不高亮)。
        binding.quickSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                val newFilter = filter.copy(keyword = q.ifEmpty { null })
                filter = newFilter
                highlightTerm = q.ifEmpty { null }
                adapter?.applyFilter(newFilter, highlight = q.ifEmpty { null })
                refreshEmptyState()
            }
        })

        observeEntries()
        loadHistoryFromStore()
        restoringConfig()
        refreshUiFromState()
    }

    /**
     * 批次 E1.3: 加载历史持久化条目 (用于用户重启 App 或 VPN 关停后回到抓包页时仍能看到旧条目)。
     * 仅当 ring buffer 当前为空才加载(主路若已有条目则优先 ring 增量推送, 避免重复显示)。
     * 加载默认 500 条按时间倒序 → 由 coarse UI scrollbar 浏览更多时通过 [loadMoreHistory] 追加。
     */
    private val historyOffset = IntArray(1)
    private fun loadHistoryFromStore() {
        if (adapter?.itemCount ?: 0 > 0) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val list = runCatching {
                CaptureController.listPersisted(requireContext(), offset = 0, limit = 500)
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (list.isNotEmpty()) {
                    list.forEach { adapter?.prepend(it) }
                    refreshEmptyState()
                }
            }
        }
    }

    /** E1 提供分页加载更多 (滚到底触发)。 */
    fun loadMoreHistory() {
        historyOffset[0] += 200
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val list = runCatching {
                CaptureController.listPersisted(requireContext(), offset = historyOffset[0], limit = 200)
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                list.forEach { adapter?.prepend(it) }
            }
        }
    }

    private fun restoringConfig() {
        // 读 SharedPreferences 上次的开关与模式, 同步 UI 状态(但 UI 自身的 Toggle 触发还是依赖用户点)
        val cfg = CaptureRepository.loadConfig(requireContext())
        binding.captureToggle.isChecked = cfg.enabled
        when (cfg.mode) {
            CaptureController.Mode.ALL_APPS -> binding.modeChipAllApps.isChecked = true
            CaptureController.Mode.BY_APP -> binding.modeChipByApp.isChecked = true
        }
    }

    private fun observeEntries() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureController.entries.collect { entry ->
                    adapter?.prepend(entry)
                    refreshEmptyState()
                }
            }
        }
        // 缺口 1+2: 断点命中时自动跳到详情页让用户裁决(PassThrough/ReplaceWith/Drop)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureController.breakpointHits.collect { txnId ->
                    openDetail(txnId)
                }
            }
        }
    }

    private fun onToggleChanged(isChecked: Boolean) {
        if (!isChecked) {
            CaptureController.disable()
            persistConfig(enabled = false)
            runCatching { com.HanFeng.service.CaptureFloatingService.stop(requireContext()) }
            refreshUiFromState()
            return
        }
        // 开启: 校验证书就绪后 enable; 失败显示文案并回退 toggle
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val result = CaptureController.enable(
                context = requireContext(),
                mode = selectedMode(),
                targetApps = currentTargetApps(),
                maxEntries = CaptureRingBufferDefaultEntries()
            )
            withContext(Dispatchers.Main) {
                if (result != CaptureController.EnableResult.OK) {
                    binding.captureToggle.isChecked = false
                    val ctx = requireContext()
                    val certReady = com.HanFeng.data.HttpsMitmRepository.isCertificateReady(ctx)
                    val certInstalled = com.HanFeng.data.HttpsMitmRepository.isCertificateInstalled(ctx)
                    binding.empty.text = when {
                        !certReady -> getString(R.string.capture_hint_cert_not_generated)
                        !certInstalled -> getString(R.string.capture_hint_cert_not_installed)
                        else -> getString(R.string.capture_pending_certificate_hint)
                    }
                } else {
                    persistConfig(enabled = true)
                    runCatching { com.HanFeng.service.CaptureFloatingService.startIfCaptureActive(requireContext()) }
                    refreshUiFromState()
                    // 抓包数据面挂在广告拦截的 VPN 管线上; 广告拦截未开则 VPN 不会启动, 拦不到任何包。
                    if (!com.HanFeng.data.FeatureSettingsRepository.isAdBlockEnabled(requireContext())) {
                        android.widget.Toast.makeText(
                            requireContext(), "抓包已开启，但需同时开启「广告拦截」才能拦截流量",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // 证书信任引导: 目标 App(Android 7+)默认不信任用户证书, TLS 握手直接失败 → 零条目。
                        // 有 root 且系统 CA 未装本 app CA 时, 引导一键安装系统证书, 否则抓不到 HTTPS。
                        maybePromptSystemCertInstall()
                    }
                }
            }
        }
    }

    private fun selectedMode(): CaptureController.Mode =
        if (binding.modeChipByApp.isChecked) CaptureController.Mode.BY_APP
        else CaptureController.Mode.ALL_APPS

    private fun currentTargetApps(): Set<String> =
        // 由用户在"筛选"对话框选定; 这里默认由筛选状态读取(初版空集, 表示按 App 但无目标 → 等于全采集占位)
        emptySet()

    /**
     * 证书信任引导: HTTPS 抓包要求目标 App 信任本 app 的 CA。若系统 CA 库未装本 CA
     * 且设备有 root, 引导一键安装系统证书 —— 否则 Android 7+ 目标 App 不信任用户证书,
     * TLS 握手直接失败被 bypass, 表现就是"抓包开启但零条目、不断网"。
     */
    private fun maybePromptSystemCertInstall() {
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.Default) {
            val sysCaInstalled = runCatching {
                com.HanFeng.security.CertificateAuthorityManager.isCaInstalledInSystem(ctx)
            }.getOrDefault(false)
            if (sysCaInstalled) return@launch
            val rootAvailable = runCatching {
                com.HanFeng.adblocker.shizuku.SystemCertInstaller.isRootAvailable()
            }.getOrDefault(false)
            if (!rootAvailable) return@launch
            withContext(Dispatchers.Main) {
                if (isDetached) return@withContext
                com.HanFeng.ui.StableDialog.builder(ctx)
                    .setTitle("抓不到 HTTPS？需要安装系统证书")
                    .setMessage(
                        "HTTPS 抓包要求目标 App 信任本 app 的 CA。\n\n" +
                            "当前证书未安装到系统 CA 库: Android 7+ 大部分 App 默认不信任用户证书, " +
                            "TLS 握手会直接失败, 表现为「抓包开启但一条记录都没有」(且不断网)。\n\n" +
                            "检测到 root, 是否一键安装系统证书? 安装后所有 App 均信任, 可正常抓包。"
                    )
                    .setPositiveButton("安装系统证书") { _, _ -> installSystemCertForCapture() }
                    .setNegativeButton("暂不", null)
                    .create()
                    .show()
            }
        }
    }

    private fun installSystemCertForCapture() {
        val ctx = requireContext()
        android.widget.Toast.makeText(ctx, "正在安装系统证书...", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { com.HanFeng.adblocker.shizuku.SystemCertInstaller(ctx).installToSystem() }.getOrNull()
            }
            if (isDetached) return@launch
            when (result) {
                is com.HanFeng.adblocker.shizuku.SystemCertInstaller.InstallResult.Success -> {
                    android.widget.Toast.makeText(
                        ctx,
                        "系统证书已安装(${result.method}), 重启抓包管线...",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    // 刷新安装状态并标记 installed, 再重启 VPN 让路由/证书状态重新生效
                    runCatching { com.HanFeng.security.CertificateAuthorityManager.syncInstalledState(ctx) }
                    com.HanFeng.data.HttpsMitmRepository.markCertificateInstalled(ctx)
                    runCatching { com.HanFeng.core.network.NetworkKernel.reload(ctx) }
                }
                is com.HanFeng.adblocker.shizuku.SystemCertInstaller.InstallResult.Failure -> {
                    android.widget.Toast.makeText(
                        ctx,
                        "系统证书安装失败: ${result.reason}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                else -> {
                    android.widget.Toast.makeText(ctx, "系统证书安装异常, 请查看日志", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun persistConfig(enabled: Boolean) {
        CaptureRepository.saveConfig(
            context = requireContext(),
            enabled = enabled,
            mode = selectedMode(),
            targetApps = currentTargetApps(),
            maxEntries = CaptureRingBufferDefaultEntries()
        )
    }

    private fun persistMode(mode: CaptureController.Mode) {
        CaptureRepository.saveConfig(
            context = requireContext(),
            enabled = binding.captureToggle.isChecked,
            mode = mode,
            targetApps = currentTargetApps(),
            maxEntries = CaptureRingBufferDefaultEntries()
        )
    }

    private fun refreshUiFromState() {
        val s = CaptureController.current.value
        binding.captureToggle.isChecked = s.active
        refreshEmptyState()
    }

    private fun refreshEmptyState() {
        val size = adapter?.itemCount ?: 0
        binding.empty.visibility = if (size == 0) View.VISIBLE else View.GONE
    }

    private fun applyBackgroundImage(imageView: ImageView) {
        val ctx = imageView.context.applicationContext
        val customPath = com.HanFeng.data.FeatureSettingsRepository.getCustomBackgroundPath(ctx)
        if (!customPath.isNullOrEmpty()) {
            if (!java.io.File(customPath).isFile) {
                com.HanFeng.data.FeatureSettingsRepository.removeCustomBackgroundPath(ctx, customPath)
            }
            imageView.applyCustomFileBackground(customPath)
        } else {
            imageView.applyCustomAssetBackground("custom/background")
        }
    }

    private fun openDetail(txnId: Long) {
        val intent = Intent(requireContext(), CaptureDetailActivity::class.java).apply {
            putExtra(CaptureDetailActivity.EXTRA_TXN_ID, txnId)
        }
        startActivity(intent)
    }

    /** 联动 + 批量入口: row 长按弹出菜单 (加入广告拦截 / 加入 host 黑名单 / 加入 path 拦截)。 */
    /**
     * 批次 E8: 长按列表条目弹出 BottomSheet 集中入口,与 HttpCanary 操作肌肉记忆一致。
     * - 查看/修改 → 跳详情页
     * - 重放 → 直接单条重放
     * - 保存为改写规则 (请求 / 响应) → 跳 RewriteRulesActivity 并预填 dialog
     * - 加入广告拦截 → 仍保留原 3 子菜单(WS 仅 Upgrade 请求时显示)
     * - 导出此条 → 跳详情页 ExportTab(单页)
     */
    private fun promptRowContextualMenu(txnId: Long) {
        val entry = adapter?.findEntry(txnId) ?: return
        val ctx = requireContext()

        val isWsUpgrade = entry.requestHeaders.entries.any {
            it.key.equals("upgrade", true) && it.value.equals("websocket", true)
        }
        val adBlockSubOptions = mutableListOf<Pair<String, () -> Unit>>()
        if (isWsUpgrade) {
            adBlockSubOptions += getString(R.string.capture_adblock_kind_ws) to { runAddToAdBlock(ctx) { c ->
                com.HanFeng.capture.CaptureAdBlockBridge.addWebSocketHostBlock(c, entry.host).getOrThrow()
            } }
        }
        adBlockSubOptions += getString(R.string.capture_adblock_kind_host) to { runAddToAdBlock(ctx) { c ->
            com.HanFeng.capture.CaptureAdBlockBridge.addHostBlocklist(c, entry.host).getOrThrow()
        } }
        adBlockSubOptions += getString(R.string.capture_adblock_kind_path) to { runAddToAdBlock(ctx) { c ->
            com.HanFeng.capture.CaptureAdBlockBridge.addPathBlocklist(c, entry.host, entry.path).getOrThrow()
        } }

        val labels = mutableListOf(
            getString(R.string.capture_quick_action_open_detail),
            getString(R.string.capture_quick_action_replay),
            getString(R.string.capture_quick_action_save_rule_req)
        )
        val actions = mutableListOf<() -> Unit>(
            { openDetail(txnId) },
            { launchReplaySingle(entry) },
            { launchSaveAsRuleFromFragment(entry, com.HanFeng.capture.BreakpointKind.REQUEST) }
        )
        if (entry.responseStatus != 0) {
            labels += getString(R.string.capture_quick_action_save_rule_rsp)
            actions += { launchSaveAsRuleFromFragment(entry, com.HanFeng.capture.BreakpointKind.RESPONSE) }
        }
        labels += getString(R.string.capture_quick_action_add_adblock)
        actions += {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.capture_adblock_prompt_title)
                .setItems(adBlockSubOptions.map { it.first }.toTypedArray()) { _, which ->
                    adBlockSubOptions[which].second.invoke()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        labels += getString(R.string.capture_quick_action_export)
        actions += { openDetail(txnId) }

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val listView = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt())
        }
        val titleView = android.widget.TextView(ctx).apply {
            text = getString(R.string.capture_quick_action_title)
            setPadding((24 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        listView.addView(titleView)
        labels.forEachIndexed { i, label ->
            val tv = android.widget.TextView(ctx).apply {
                text = label
                textSize = 15f
                setPadding((24 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(),
                    (24 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt())
                setOnClickListener {
                    sheet.dismiss()
                    actions[i].invoke()
                }
            }
            listView.addView(tv)
        }
        sheet.setContentView(listView)
        sheet.show()
    }

    /** 单条重放: 直接复用 [CaptureReplayEngine.replay] 流程并 Toast 进度。 */
    private fun launchReplaySingle(entry: com.HanFeng.capture.CaptureEntry) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val template = com.HanFeng.capture.CaptureTemplate(
                id = "single-${entry.txnId}",
                label = entry.host + entry.path,
                createdAt = System.currentTimeMillis(),
                method = entry.method,
                scheme = entry.scheme,
                host = entry.host,
                path = entry.path,
                headers = entry.requestHeaders,
                body = entry.requestBodyPreview
            )
            val r = com.HanFeng.capture.CaptureReplayEngine.replay(template)
            val msg = if (r.success) getString(R.string.capture_batch_done, 1, 0)
            else r.errorMessage?.let { getString(R.string.capture_adblock_add_failed_toast, it) } ?: "replay failed"
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 批次 E8: 从列表 BottomSheet "保存为改写规则" 子项跳 RewriteRulesActivity, 携带 entry txnId 与方向。
     */
    private fun launchSaveAsRuleFromFragment(
        entry: com.HanFeng.capture.CaptureEntry,
        kind: com.HanFeng.capture.BreakpointKind
    ) {
        val intent = android.content.Intent(requireContext(), RewriteRulesActivity::class.java).apply {
            putExtra(RewriteRulesActivity.EXTRA_PREFILL_ENTRY_TXN_ID, entry.txnId)
            putExtra(RewriteRulesActivity.EXTRA_PREFILL_KIND, kind.name)
        }
        startActivity(intent)
    }

    /** 在 IO 线程执行联动广告拦截操作然后 Toast 反馈。 */
    private fun runAddToAdBlock(
        ctx: android.content.Context,
        block: (android.content.Context) -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val summary = runCatching {
                block(ctx)
                "已加入广告拦截"
            }.fold(
                onSuccess = { "已加入广告拦截" },
                onFailure = { err ->
                    getString(R.string.capture_adblock_add_failed_toast, err.message ?: err.javaClass.simpleName)
                }
            )
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(ctx, summary, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 进入批量选择模式 — 用户点 list row 的"重放选中"块触发。 */
    private fun startSelectionMode() {
        if (adapter?.selectionMode == true) return
        adapter?.startSelection()
        binding.selectionBar.visibility = View.VISIBLE
        binding.btnReplaySelected.setOnClickListener { confirmBatchReplaySelected() }
        binding.btnClearSelection.setOnClickListener { exitSelectionMode() }
        refreshSelectionCount()
    }

    private fun exitSelectionMode() {
        adapter?.clearSelection()
        binding.selectionBar.visibility = View.GONE
    }

    private fun refreshSelectionCount() {
        val count = adapter?.selectedCount() ?: 0
        binding.replaySelectedLabel.text = getString(R.string.capture_batch_replay_selected, count)
    }

    private fun confirmBatchReplaySelected() {
        val selected = adapter?.collectSelected() ?: return
        if (selected.isEmpty()) return
        val cfg = CaptureRepository.loadConfig(requireContext())
        val interval = cfg.batchIntervalMs
        if (cfg.confirmRiskyReplay) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.capture_batch_confirm_title)
                .setMessage(getString(R.string.capture_batch_confirm_msg, selected.size, interval))
                .setPositiveButton(android.R.string.ok) { _, _ -> doBatchReplay(selected, interval) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            doBatchReplay(selected, interval)
        }
    }

    private fun doBatchReplay(
        selected: List<com.HanFeng.capture.CaptureEntry>,
        intervalMs: Long
    ) {
        val ctx = requireContext()
        val token = java.util.concurrent.atomic.AtomicBoolean(false)
        val templates = selected.map { e ->
            com.HanFeng.capture.CaptureTemplate(
                id = "batch-${e.txnId}",
                label = e.host + e.path,
                createdAt = System.currentTimeMillis(),
                method = e.method,
                scheme = e.scheme,
                host = e.host,
                path = e.path,
                headers = e.requestHeaders,
                body = e.requestBodyPreview
            )
        }

        val progressView = android.widget.TextView(ctx).apply {
            setPadding(48, 32, 48, 32)
            text = getString(R.string.capture_batch_running)
            textSize = 14f
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.capture_batch_running)
            .setView(progressView)
            .setCancelable(false)
            .setPositiveButton(R.string.capture_batch_cancel_btn) { _, _ ->
                token.set(true)
            }
            .create()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var success = 0
            var failure = 0
            withContext(Dispatchers.Main) { dialog.show() }
            com.HanFeng.capture.CaptureReplayEngine.replayBatchStreaming(
                templates,
                intervalMs,
                onProgress = { idx, total, r ->
                    if (r.success) success++ else failure++
                    val progress = getString(R.string.capture_batch_progress, idx + 1, total, success, failure)
                    val span = if (r.cancelled) " [cancelled]" else ""
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        if (dialog.isShowing) progressView.text = progress + span
                    }
                },
                cancellationToken = token
            )
            val msg = if (token.get()) {
                getString(R.string.capture_batch_cancelled, success + failure)
            } else {
                getString(R.string.capture_batch_done, success, failure)
            }
            withContext(Dispatchers.Main) {
                if (dialog.isShowing) dialog.dismiss()
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        adapter = null
    }
}

class CaptureEntryAdapter(
    private val onClick: (Long) -> Unit,
    private val onLongClick: ((Long) -> Unit)? = null,
    private val onSelectionChanged: (() -> Unit)? = null
) : androidx.recyclerview.widget.RecyclerView.Adapter<CaptureEntryAdapter.VH>() {

    private val allItems = mutableListOf<com.HanFeng.capture.CaptureEntry>()
    private var filter: com.HanFeng.capture.CaptureFilter = com.HanFeng.capture.CaptureFilter()
    private val visibleItems = mutableListOf<com.HanFeng.capture.CaptureEntry>()
    /** 批次 E5d: 当前用于高亮 host/path 子串的关键字; null 表示不高亮。 */
    private var highlightTerm: String? = null
    /** 批量选择模式(联动 D1 + D4) — 仅按选中 txnId 维护。 */
    private val selectedTxnIds: MutableSet<Long> = linkedSetOf()
    var selectionMode: Boolean = false
        private set

    /** 新条目插入头部并触发刷新。 */
    fun prepend(entry: com.HanFeng.capture.CaptureEntry) {
        allItems.add(0, entry)
        // 截到 200 条以保持与 ring buffer 一致
        val over = allItems.size - 200
        if (over > 0) {
            repeat(over) { allItems.removeAt(allItems.size - 1) }
        }
        if (filter.matches(entry)) {
            visibleItems.add(0, entry)
            notifyItemInserted(0)
        }
    }

    fun refreshAll(newItems: List<com.HanFeng.capture.CaptureEntry>) {
        allItems.clear()
        allItems.addAll(newItems)
        applyFilter(filter, highlight = highlightTerm)
    }

    fun applyFilter(newFilter: com.HanFeng.capture.CaptureFilter, highlight: String? = null) {
        filter = newFilter
        highlightTerm = highlight?.takeIf { it.isNotEmpty() }
        visibleItems.clear()
        visibleItems.addAll(allItems.filter(filter::matches))
        notifyDataSetChanged()
    }

    /** 按 txnId 查当前 visible 内的 entry; 用作菜单 contextual 入口。 */
    fun findEntry(txnId: Long): com.HanFeng.capture.CaptureEntry? = visibleItems.firstOrNull { it.txnId == txnId }

    /** 进入选择模式。 */
    fun startSelection() {
        selectionMode = true
        selectedTxnIds.clear()
        notifyDataSetChanged()
    }

    fun clearSelection() {
        val wasSelection = selectionMode
        selectionMode = false
        selectedTxnIds.clear()
        if (wasSelection) notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun selectedCount(): Int = selectedTxnIds.size

    fun collectSelected(): List<com.HanFeng.capture.CaptureEntry> =
        visibleItems.filter { it.txnId in selectedTxnIds }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val b = ItemCaptureEntryBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun getItemCount(): Int = visibleItems.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = visibleItems[position]
        holder.bind(entry, onClick, onLongClick, selectionMode, selectedTxnIds.contains(entry.txnId), highlightTerm) {
            toggleSelected(entry.txnId)
            onSelectionChanged?.invoke()
        }
    }

    private fun toggleSelected(txnId: Long) {
        if (!selectionMode) return
        if (selectedTxnIds.contains(txnId)) {
            selectedTxnIds.remove(txnId)
        } else {
            selectedTxnIds.add(txnId)
        }
        val pos = visibleItems.indexOfFirst { it.txnId == txnId }
        if (pos >= 0) notifyItemChanged(pos)
    }

    class VH(private val b: ItemCaptureEntryBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root) {
        fun bind(
            entry: com.HanFeng.capture.CaptureEntry,
            onClick: (Long) -> Unit,
            onLongClick: ((Long) -> Unit)?,
            selectionMode: Boolean,
            isSelected: Boolean,
            highlight: String? = null,
            onToggleSelected: () -> Unit
        ) {
            b.method.text = entry.method
            b.status.text = if (entry.responseStatus != 0) entry.responseStatus.toString() else "…"
            b.host.text = highlightIn(entry.host, highlight)
            b.path.text = highlightIn(entry.path, highlight)
            b.breakpointBadge.visibility =
                if (entry.isPendingBreakpoint) android.view.View.VISIBLE else android.view.View.GONE
            b.replayBadge.visibility =
                if (entry.replayed) android.view.View.VISIBLE else android.view.View.GONE
            // 选择模式下点击 row = 切换选中
            b.root.setOnClickListener {
                if (selectionMode) {
                    onToggleSelected()
                } else {
                    onClick(entry.txnId)
                }
            }
            b.root.setOnLongClickListener {
                if (selectionMode) {
                    onToggleSelected()
                    true
                } else {
                    onLongClick?.invoke(entry.txnId)
                    onLongClick != null
                }
            }
            // 选中高亮 — 用 background 透明色叠加, 不可用 state list anim(简单)
            b.root.background = if (isSelected) {
                android.graphics.Color.argb(40, 33, 150, 243).let { c ->
                    android.graphics.drawable.ColorDrawable(c)
                }
            } else null
        }

        private fun highlightIn(text: String, term: String?): CharSequence {
            val t = term?.trim().orEmpty()
            if (t.isEmpty()) return text
            val idx = text.indexOf(t, ignoreCase = true)
            if (idx < 0) return text
            val span = android.text.SpannableString(text)
            val bg = android.text.style.BackgroundColorSpan(android.graphics.Color.argb(180, 255, 235, 59))
            val fg = android.text.style.ForegroundColorSpan(android.graphics.Color.BLACK)
            span.setSpan(bg, idx, idx + t.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(fg, idx, idx + t.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return span
        }
    }
}

/** 默认 ring buffer 容量(与 CaptureRingBuffer.DEFAULT_CAPACITY 等价)。ISO isolate 避免再依赖 capture 包私有常量。 */
private fun CaptureRingBufferDefaultEntries(): Int = com.HanFeng.capture.CaptureRingBuffer.DEFAULT_CAPACITY
