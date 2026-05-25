package com.HanFeng.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.databinding.ActivityWhitelistBinding
import com.HanFeng.service.AdBlockVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WhitelistActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWhitelistBinding
    private var batchUpdating = false
    private val adapter = AppListAdapter { app, checked ->
        if (batchUpdating) return@AppListAdapter
        WhitelistRepository.toggle(this, app.packageName, checked)
        if (AdBlockVpnService.isRunning) {
            startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
        }
        Toast.makeText(this, if (checked) "已加入白名单并立即生效" else "已移出白名单并立即生效", Toast.LENGTH_SHORT).show()
        loadApps()
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

        binding.btnInvertSelection.setOnClickListener { invertSelection() }

        loadApps()
    }

    private fun loadApps() {
        binding.loadingOverlay.isVisible = true
        lifecycleScope.launch {
            val apps = runCatching {
                withContext(Dispatchers.Default) {
                    WhitelistRepository.loadInstalledApps(this@WhitelistActivity)
                }
            }.getOrElse {
                binding.loadingOverlay.isVisible = false
                Toast.makeText(this@WhitelistActivity, "应用列表加载失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            adapter.submit(apps)
            binding.loadingOverlay.isVisible = false
        }
    }

    private fun invertSelection() {
        lifecycleScope.launch {
            if (batchUpdating) return@launch
            val apps = adapter.currentList
            if (apps.isEmpty()) return@launch
            batchUpdating = true
            binding.btnInvertSelection.isEnabled = false
            binding.loadingOverlay.isVisible = true

            try {
                withContext(Dispatchers.Default) {
                    val updatedPackages = apps.asSequence()
                        .filterNot { it.whitelisted }
                        .mapTo(linkedSetOf()) { it.packageName }
                    WhitelistRepository.replacePackages(this@WhitelistActivity, updatedPackages)
                }

                if (AdBlockVpnService.isRunning) {
                    startService(Intent(this@WhitelistActivity, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
                }
                Toast.makeText(this@WhitelistActivity, "已反选并立即生效", Toast.LENGTH_SHORT).show()
                loadApps()
            } finally {
                batchUpdating = false
                binding.btnInvertSelection.isEnabled = true
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
