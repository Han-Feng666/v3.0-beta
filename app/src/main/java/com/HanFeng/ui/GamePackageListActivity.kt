package com.HanFeng.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.HanFeng.adblocker.shizuku.GameAntiMarkManager
import com.HanFeng.data.GameAntiMarkRepository
import com.HanFeng.databinding.ActivityGamePackageListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GamePackageListActivity : BaseActivity() {

    private lateinit var binding: ActivityGamePackageListBinding
    private lateinit var adapter: GamePackageAdapter
    private var currentPackages: LinkedHashSet<String> = linkedSetOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityGamePackageListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }

        adapter = GamePackageAdapter { pkg -> removePackage(pkg) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener {
            val newPkg = binding.etNewPackage.text?.toString()?.trim().orEmpty()
            if (newPkg.isBlank()) {
                showShortToast("请输入游戏包名")
                return@setOnClickListener
            }
            if (currentPackages.contains(newPkg)) {
                showShortToast("已存在该包名")
                return@setOnClickListener
            }
            if (!looksLikePackageName(newPkg)) {
                showShortToast("包名格式不正确，应仅包含字母、数字、下划线和点")
                return@setOnClickListener
            }
            currentPackages.add(newPkg)
            GameAntiMarkRepository.setTargetPackages(this, currentPackages)
            binding.etNewPackage.text?.clear()
            reloadList()
            pushWatcherIfRunning()
        }
        binding.btnResetDefault.setOnClickListener {
            StableDialog.builder(this)
                .setTitle("恢复默认列表")
                .setMessage("将清空当前自定义包名并恢复为内置的 ${GameAntiMarkRepository.DEFAULT_TARGET_PACKAGES.size} 个腾讯游戏包名。是否继续？")
                .setPositiveButton("恢复默认") { _, _ -> resetToDefault() }
                .setNegativeButton("取消", null)
                .showSafely(this, "Show reset default dialog failed")
        }
        binding.btnClearAll.setOnClickListener {
            if (currentPackages.isEmpty()) {
                showShortToast("列表已为空")
                return@setOnClickListener
            }
            StableDialog.builder(this)
                .setTitle("清空列表")
                .setMessage("将清空所有游戏包名。监听守护将不再监控任何游戏。是否继续？")
                .setPositiveButton("确认清空") { _, _ -> clearAll() }
                .setNegativeButton("取消", null)
                .showSafely(this, "Show clear all dialog failed")
        }

        reloadList()
    }

    private fun reloadList() {
        currentPackages = GameAntiMarkRepository.getTargetPackages(this)
        adapter.submitList(currentPackages.toList())
        binding.tvCount.text = "当前共 ${currentPackages.size} 个游戏包名"
        binding.emptyText.visibility = if (currentPackages.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (currentPackages.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun removePackage(pkg: String) {
        if (!currentPackages.remove(pkg)) {
            showShortToast("包名不存在，可能已被删除")
            return
        }
        GameAntiMarkRepository.setTargetPackages(this, currentPackages)
        reloadList()
        pushWatcherIfRunning()
    }

    private fun resetToDefault() {
        GameAntiMarkRepository.resetToDefault(this)
        reloadList()
        pushWatcherIfRunning()
        showShortToast("已恢复默认列表")
    }

    private fun clearAll() {
        GameAntiMarkRepository.setTargetPackages(this, linkedSetOf())
        reloadList()
        pushWatcherIfRunning()
        showShortToast("列表已清空")
    }

    private fun pushWatcherIfRunning() {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                GameAntiMarkManager.status(this@GamePackageListActivity)
            }
            if (status.running) {
                withContext(Dispatchers.IO) {
                    GameAntiMarkManager.stop()
                    GameAntiMarkManager.start(
                        this@GamePackageListActivity,
                        sm8850Fallback = status.sm8850Detected
                    )
                }
            }
        }
    }

    private fun looksLikePackageName(text: String): Boolean {
        if (text.length > 200) return false
        val regex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        return regex.matches(text)
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, GamePackageListActivity::class.java)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
