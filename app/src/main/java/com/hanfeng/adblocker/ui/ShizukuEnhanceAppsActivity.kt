package com.HanFeng.ui

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.HanFeng.R
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuEnhanceRepository
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.databinding.ActivityWhitelistBinding
import com.HanFeng.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShizukuEnhanceAppsActivity : BaseActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_NETWORK = "network"
        const val MODE_BACKGROUND = "background"
    }

    private lateinit var binding: ActivityWhitelistBinding
    private val mode by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_NETWORK }
    private var loadJob: Job? = null
    private var allApps: List<InstalledApp> = emptyList()
    private var selectedPackages: MutableSet<String> = linkedSetOf()

    private val adapter = AppListAdapter(
        checkedSelector = { app -> selectedPackages.contains(app.packageName) }
    ) { app, checked ->
        updateOnePackage(app.packageName, checked)
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
        binding.titleText.text = if (mode == MODE_BACKGROUND) "后台限制管理" else "应用网络权限管理"
        binding.descText.text = if (mode == MODE_BACKGROUND) {
            "勾选应用后，将通过 Shizuku 限制后台运行、后台唤醒和待机桶。取消勾选会恢复这些限制。"
        } else {
            "勾选应用后，将通过 Shizuku 禁用联网权限。取消勾选会恢复联网权限。"
        }
        binding.searchInput.hint = "搜索应用名或包名"
        binding.btnLocalProxyCoexist.isVisible = true
        binding.btnLocalProxyCoexist.text = "恢复全部"
        binding.btnLocalProxyCoexist.setOnClickListener { restoreAllSelected() }
        binding.searchInput.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }
        binding.btnSelectVisible.setOnClickListener { updateVisibleSelection(selectAll = true) }
        binding.btnInvertVisible.setOnClickListener { updateVisibleSelection(selectAll = false, invert = true) }
        loadApps()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    private fun loadApps() {
        selectedPackages = if (mode == MODE_BACKGROUND) {
            ShizukuEnhanceRepository.getBackgroundRestrictedPackages(this).toMutableSet()
        } else {
            ShizukuEnhanceRepository.getNetworkBlockedPackages(this).toMutableSet()
        }
        binding.loadingOverlay.isVisible = true
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val apps = withContext(Dispatchers.Default) {
                WhitelistRepository.loadInstalledApps(applicationContext)
            }
            if (isFinishing || isDestroyed) return@launch
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

    private fun updateOnePackage(packageName: String, checked: Boolean) {
        if (checked) selectedPackages += packageName else selectedPackages -= packageName
        persistSelection()
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = applyPackage(packageName, checked)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ShizukuEnhanceAppsActivity, if (ok) "已应用" else "应用失败，请查看日志", Toast.LENGTH_SHORT).show()
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun updateVisibleSelection(selectAll: Boolean, invert: Boolean = false) {
        val visibleApps = adapter.currentList
        if (visibleApps.isEmpty()) {
            Toast.makeText(this, "当前没有可操作的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val targets = visibleApps.associate { app ->
            val current = selectedPackages.contains(app.packageName)
            app.packageName to if (invert) !current else selectAll
        }
        targets.forEach { (packageName, checked) ->
            if (checked) selectedPackages += packageName else selectedPackages -= packageName
        }
        persistSelection()
        adapter.notifyDataSetChanged()
        lifecycleScope.launch(Dispatchers.IO) {
            var success = 0
            targets.forEach { (packageName, checked) -> if (applyPackage(packageName, checked)) success++ }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ShizukuEnhanceAppsActivity, "已处理 $success/${targets.size} 个应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun restoreAllSelected() {
        val targets = selectedPackages.toList()
        if (targets.isEmpty()) {
            Toast.makeText(this, "当前没有需要恢复的应用", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPackages.clear()
        persistSelection()
        adapter.notifyDataSetChanged()
        lifecycleScope.launch(Dispatchers.IO) {
            var success = 0
            targets.forEach { packageName -> if (applyPackage(packageName, false)) success++ }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ShizukuEnhanceAppsActivity, "已恢复 $success/${targets.size} 个应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistSelection() {
        if (mode == MODE_BACKGROUND) {
            ShizukuEnhanceRepository.replaceBackgroundRestrictedPackages(this, selectedPackages)
        } else {
            ShizukuEnhanceRepository.replaceNetworkBlockedPackages(this, selectedPackages)
        }
    }

    private fun applyPackage(packageName: String, checked: Boolean): Boolean {
        return if (mode == MODE_BACKGROUND) {
            ShizukuAdControlRepository.setBackgroundRestricted(this, packageName, checked)
        } else {
            ShizukuAdControlRepository.setNetworkBlocked(this, packageName, checked)
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
