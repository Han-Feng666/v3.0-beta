package com.HanFeng.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.AppFreezeManager
import com.HanFeng.data.AppFreezeManager.FreezeEntry
import com.HanFeng.data.LogRepository
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.databinding.ActivityAppFreezeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppFreezeActivity : BaseActivity() {

    private lateinit var binding: ActivityAppFreezeBinding
    private lateinit var adapter: AppFreezeAdapter

    private enum class Tab { ALL, FROZEN }

    private var currentTab = Tab.ALL
    private var allEntries: List<FreezeEntry> = emptyList()
    private var visibleEntries: List<FreezeEntry> = emptyList()
    private var searchQuery: String = ""
    private var searchDebounceJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAppFreezeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }
        adapter = AppFreezeAdapter { entry -> toggleEntry(entry) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnTabAll.setOnClickListener { switchTab(Tab.ALL) }
        binding.btnTabFrozen.setOnClickListener { switchTab(Tab.FROZEN) }
        binding.btnFreezeVisible.setOnClickListener { batchFreezeVisible() }
        binding.btnUnfreezeAll.setOnClickListener { batchUnfreezeAll() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty().trim().lowercase()
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(180)
                    applyFilter()
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        loadEntries()
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        updateTabButtons()
        applyFilter()
    }

    private fun updateTabButtons() {
        binding.btnTabAll.alpha = if (currentTab == Tab.ALL) 1f else 0.72f
        binding.btnTabFrozen.alpha = if (currentTab == Tab.FROZEN) 1f else 0.72f
    }

    private fun loadEntries() {
        lifecycleScope.launch {
            if (!AppSettingsRepository.isShizukuEnabled(this@AppFreezeActivity)) {
                StableDialog.builder(this@AppFreezeActivity)
                    .setTitle("Shizuku 未启用")
                    .setMessage("应用冻结需要 Shizuku 支持。请先在设置中开启 Shizuku 增强。")
                    .setPositiveButton("我知道了", null)
                    .showSafely(this@AppFreezeActivity, "Show shizuku disabled dialog failed")
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { ShizukuAdControlRepository.ensureBoundAndWait(this@AppFreezeActivity) }
            }
            val entries = withContext(Dispatchers.Default) {
                runCatching {
                    AppFreezeManager.loadAllEntries(this@AppFreezeActivity)
                }.getOrElse {
                    LogRepository.append(this@AppFreezeActivity, "AppFreeze loadAllEntries failed: ${it.message ?: it.javaClass.simpleName}")
                    emptyList()
                }
            }
            if (isFinishing || isDestroyed) return@launch
            allEntries = entries
            updateTabButtons()
            applyFilter()
            updateStatusBar()
        }
    }

    private fun applyFilter() {
        visibleEntries = allEntries
            .asSequence()
            .filter { currentTab == Tab.ALL || it.frozen }
            .filter { entry ->
                if (searchQuery.isBlank()) return@filter true
                entry.label.lowercase().contains(searchQuery) || entry.packageName.lowercase().contains(searchQuery)
            }
            .toList()
        adapter.submit(visibleEntries)
        updateEmptyState()
        updateStatusBar()
    }

    private fun updateEmptyState() {
        if (visibleEntries.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.text = if (currentTab == Tab.FROZEN) {
                "当前没有已冻结的应用"
            } else if (searchQuery.isNotBlank()) {
                "未找到匹配的应用"
            } else {
                "当前列表为空"
            }
            binding.list.visibility = View.GONE
        } else {
            binding.emptyText.visibility = View.GONE
            binding.list.visibility = View.VISIBLE
        }
    }

    private fun updateStatusBar() {
        val frozenCount = allEntries.count { it.frozen }
        binding.statusBar.text = when (currentTab) {
            Tab.ALL -> "共 ${allEntries.size} 个应用，其中已冻结 ${frozenCount} 个"
            Tab.FROZEN -> "已冻结 ${frozenCount} 个应用"
        }
    }

    private fun toggleEntry(entry: FreezeEntry) {
        if (entry.critical) {
            showShortToast("关键系统应用不可冻结")
            return
        }
        lifecycleScope.launch {
            if (!ensureShizukuReady()) return@launch
            val result = withContext(Dispatchers.IO) {
                if (entry.frozen) {
                    AppFreezeManager.unfreeze(this@AppFreezeActivity, entry.packageName)
                } else {
                    AppFreezeManager.freeze(this@AppFreezeActivity, entry.packageName)
                }
            }
            if (result) {
                showShortToast(if (entry.frozen) "已解冻 ${entry.label}" else "已冻结 ${entry.label}")
                refreshSingleEntry(entry.packageName)
            } else {
                showShortToast("操作失败，请确认 Shizuku 服务正常")
            }
        }
    }

    private fun refreshSingleEntry(packageName: String) {
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.Default) {
                val pm = packageManager
                val flags = android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS or
                    android.content.pm.PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                val appInfo = runCatching { pm.getApplicationInfo(packageName, flags) }.getOrNull() ?: return@withContext null
                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(packageName).ifBlank { packageName }
                val status = ShizukuAdControlRepository.queryPackageStatus(this@AppFreezeActivity, packageName)
                val systemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                FreezeEntry(
                    packageName = packageName,
                    label = label,
                    icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull(),
                    systemApp = systemApp,
                    frozen = com.HanFeng.data.PromoGovernActionRepository.isDisabledState(status.enabledState),
                    suspended = status.suspended,
                    critical = AppFreezeManager.isCritical(packageName)
                )
            }
            if (isFinishing || isDestroyed) return@launch
            if (fresh != null) {
                allEntries = allEntries.map { if (it.packageName == packageName) fresh else it }
            }
            applyFilter()
        }
    }

    private fun batchFreezeVisible() {
        val candidates = visibleEntries.filter { !it.frozen && !it.critical }
        if (candidates.isEmpty()) {
            showShortToast("当前列表没有可冻结的应用")
            return
        }
        StableDialog.builder(this)
            .setTitle("批量冻结")
            .setMessage("将对当前列表中 ${candidates.size} 个未冻结应用执行冻结。冻结后桌面图标将隐藏。")
            .setPositiveButton("开始冻结") { _, _ -> executeBatchFreeze(candidates) }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show batch freeze dialog failed")
    }

    private fun executeBatchFreeze(candidates: List<FreezeEntry>) {
        lifecycleScope.launch {
            if (!ensureShizukuReady()) return@launch
            val result = withContext(Dispatchers.IO) {
                AppFreezeManager.batchFreeze(
                    this@AppFreezeActivity,
                    candidates.map { it.packageName }
                )
            }
            showBatchResult(
                actionLabel = "冻结",
                operated = result.operated.size,
                failed = result.failed.size,
                skipped = result.skipped.size
            )
            loadEntries()
        }
    }

    private fun batchUnfreezeAll() {
        val frozen = allEntries.filter { it.frozen }
        if (frozen.isEmpty()) {
            showShortToast("当前没有已冻结的应用")
            return
        }
        StableDialog.builder(this)
            .setTitle("解冻全部")
            .setMessage("将解冻当前已冻结的 ${frozen.size} 个应用。")
            .setPositiveButton("开始解冻") { _, _ -> executeBatchUnfreeze(frozen) }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show batch unfreeze dialog failed")
    }

    private fun executeBatchUnfreeze(frozen: List<FreezeEntry>) {
        lifecycleScope.launch {
            if (!ensureShizukuReady()) return@launch
            val result = withContext(Dispatchers.IO) {
                AppFreezeManager.batchUnfreeze(
                    this@AppFreezeActivity,
                    frozen.map { it.packageName }
                )
            }
            showBatchResult(
                actionLabel = "解冻",
                operated = result.operated.size,
                failed = result.failed.size,
                skipped = result.skipped.size
            )
            loadEntries()
        }
    }

    private fun showBatchResult(actionLabel: String, operated: Int, failed: Int, skipped: Int) {
        val message = buildString {
            append("批量${actionLabel}完成")
            append("\n成功：${operated} 项")
            if (failed > 0) append("\n失败：${failed} 项")
            if (skipped > 0) append("\n跳过：${skipped} 项（关键系统应用）")
        }
        StableDialog.builder(this)
            .setTitle("${actionLabel}结果")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .showSafely(this, "Show batch freeze result failed")
    }

    private suspend fun ensureShizukuReady(): Boolean {
        if (!AppSettingsRepository.isShizukuEnabled(this)) {
            showShortToast("请先在设置中开启 Shizuku 增强")
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
                .setMessage("Shizuku 已连接，但冻结服务还未成功绑定。请稍后重试，或重新进入 Shizuku 后再回来。")
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show shizuku bind failed dialog failed")
            return false
        }
        return true
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, AppFreezeActivity::class.java)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
