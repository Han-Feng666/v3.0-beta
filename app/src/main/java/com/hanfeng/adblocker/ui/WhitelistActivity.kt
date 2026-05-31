package com.HanFeng.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.HanFeng.R
import com.HanFeng.model.InstalledApp
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.databinding.ActivityWhitelistBinding
import com.HanFeng.service.AdBlockVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WhitelistActivity : BaseActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_WHITELIST = "whitelist"
        const val MODE_COEXIST = "coexist"
    }

    private lateinit var binding: ActivityWhitelistBinding
    private var batchUpdating = false
    private var pendingReloadJob: Job? = null
    private var loadAppsJob: Job? = null
    private var loadAppsVersion = 0
    private var allApps: List<InstalledApp> = emptyList()
    private val mode by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_WHITELIST }
    private val coexistMode by lazy { mode == MODE_COEXIST }
    private val adapter = AppListAdapter(
        checkedSelector = { app -> if (coexistMode) app.coexistSelected else app.whitelisted }
    ) { app, checked ->
        if (batchUpdating) return@AppListAdapter
        if (coexistMode) {
            WhitelistRepository.toggleCoexistPackage(this, app.packageName, checked)
        } else {
            WhitelistRepository.toggle(this, app.packageName, checked)
        }
        scheduleVpnReload()
        val message = if (coexistMode) {
            if (checked) "已加入加速器共存列表并立即生效" else "已移出加速器共存列表并立即生效"
        } else {
            if (checked) "已加入白名单并立即生效" else "已移出白名单并立即生效"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        updateAppSelection(app.packageName, checked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.whitelistRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16.dp, systemBars.top + 16.dp, 16.dp, systemBars.bottom + 16.dp)
            insets
        }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
        binding.titleText.text = if (coexistMode) "加速器共存" else "应用白名单"
        binding.descText.text = if (coexistMode) {
            "选中的加速器、VPN、代理应用将不走寒枫 VPN，让它们继续使用自己的通道。常见加速器和 VPN 应用会自动置顶；选中后会尽量自动带上同家族核心包。页面里的配置信息可用于填写 127.0.0.1、端口和代理应用包名。"
        } else {
            "默认所有第三方应用都会参与拦截，加入白名单的应用将完全放行。"
        }
        binding.searchInput.hint = if (coexistMode) "搜索加速器、VPN、代理或目标应用" else "搜索应用名或包名"
        binding.root.findViewById<Button>(R.id.btnLocalProxyCoexist)?.apply {
            isVisible = coexistMode
            setOnClickListener {
                startActivity(Intent(this@WhitelistActivity, LocalProxyCoexistActivity::class.java))
            }
        }
        binding.searchInput.doAfterTextChanged {
            applyFilter(it?.toString().orEmpty())
        }
        binding.btnSelectVisible.setOnClickListener {
            updateVisibleSelection(selectAll = true)
        }
        binding.btnInvertVisible.setOnClickListener {
            updateVisibleSelection(selectAll = false, invert = true)
        }

        loadApps()
    }

    private fun loadApps() {
        binding.loadingOverlay.isVisible = true
        val requestVersion = ++loadAppsVersion
        loadAppsJob?.cancel()
        loadAppsJob = lifecycleScope.launch {
            val apps = runCatching {
                withContext(Dispatchers.Default) {
                    WhitelistRepository.loadInstalledApps(applicationContext, prioritizeCoexist = coexistMode)
                }
            }.getOrElse {
                binding.loadingOverlay.isVisible = false
                Toast.makeText(this@WhitelistActivity, "应用列表加载失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (requestVersion != loadAppsVersion || isFinishing || isDestroyed) return@launch
            allApps = apps
            applyFilter(binding.searchInput.text?.toString().orEmpty())
            binding.loadingOverlay.isVisible = false
        }
    }

    private fun applyFilter(keyword: String) {
        val normalized = keyword.trim().lowercase()
        val filtered = if (normalized.isBlank()) {
            allApps
        } else {
            allApps.filter { app ->
                app.label.lowercase().contains(normalized) || app.packageName.lowercase().contains(normalized)
            }
        }
        adapter.submit(filtered)
    }

    private fun updateVisibleSelection(selectAll: Boolean, invert: Boolean = false) {
        val visibleApps = adapter.currentList
        if (visibleApps.isEmpty()) {
            Toast.makeText(this, "当前没有可操作的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val updatedPackages = mutableSetOf<String>()
        visibleApps.forEach { app ->
            val checked = if (coexistMode) app.coexistSelected else app.whitelisted
            val targetChecked = if (invert) !checked else selectAll
            if (targetChecked) {
                updatedPackages += app.packageName
            }
        }
        if (coexistMode) {
            val base = WhitelistRepository.getCoexistPackages(this).toMutableSet()
            visibleApps.forEach { base.remove(it.packageName) }
            base += updatedPackages
            WhitelistRepository.replaceCoexistPackages(this, base)
        } else {
            val base = WhitelistRepository.getPackages(this).toMutableSet()
            visibleApps.forEach { base.remove(it.packageName) }
            base += updatedPackages
            WhitelistRepository.replacePackages(this, base)
        }
        updateVisibleSelections(updatedPackages)
        scheduleVpnReload()
        Toast.makeText(this, if (invert) "已对当前可见应用执行反选" else "已更新当前可见应用选择", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        loadAppsJob?.cancel()
        pendingReloadJob?.cancel()
        super.onDestroy()
    }

    private fun updateAppSelection(packageName: String, checked: Boolean) {
        allApps = allApps.map { app ->
            if (app.packageName != packageName) {
                app
            } else if (coexistMode) {
                app.copy(coexistSelected = checked)
            } else {
                app.copy(whitelisted = checked)
            }
        }
        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    private fun updateVisibleSelections(updatedPackages: Set<String>) {
        val visiblePackageNames = adapter.currentList.mapTo(hashSetOf()) { it.packageName }
        allApps = allApps.map { app ->
            if (app.packageName !in visiblePackageNames) {
                app
            } else if (coexistMode) {
                app.copy(coexistSelected = app.packageName in updatedPackages)
            } else {
                app.copy(whitelisted = app.packageName in updatedPackages)
            }
        }
        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    private fun scheduleVpnReload() {
        if (!AdBlockVpnService.isRunning) return
        pendingReloadJob?.cancel()
        pendingReloadJob = lifecycleScope.launch {
            delay(350)
            startService(Intent(this@WhitelistActivity, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
