package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.data.LogRepository
import com.HanFeng.databinding.ActivityDecisionDomainsBinding
import com.HanFeng.databinding.ItemDecisionDomainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.HanFeng.core.network.NetworkKernel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DecisionDomainsActivity : BaseActivity() {
    private lateinit var binding: ActivityDecisionDomainsBinding
    private lateinit var adapter: DecisionDomainAdapter
    private var allEntries: List<LogRepository.DomainDecisionEntry> = emptyList()
    private var filter: LogRepository.DomainDecisionType? = null
    private var searchJob: Job? = null
    // SimpleDateFormat accessed only from main thread; no ThreadLocal needed
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDecisionDomainsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }
        adapter = DecisionDomainAdapter(dateFormat) { entry ->
            toggleDecision(entry)
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.searchInput.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(180)
                if (isFinishing || isDestroyed) return@launch
                applyFilters()
            }
        }
        binding.btnFilterAll.setOnClickListener {
            filter = null
            applyFilters()
        }
        binding.btnFilterBlocked.setOnClickListener {
            filter = LogRepository.DomainDecisionType.BLOCKED
            applyFilters()
        }
        binding.btnFilterAllowed.setOnClickListener {
            filter = LogRepository.DomainDecisionType.ALLOWED
            applyFilters()
        }
        loadEntries()
    }

    override fun onResume() {
        super.onResume()
        loadEntries()
    }

    private fun toggleDecision(entry: LogRepository.DomainDecisionEntry) {
        // 仅 DOMAIN scope 支持手工 toggle，非 DOMAIN（IP/CIDR/Port/EncryptedDNS/Learning）
        // 规则库不支持以 IP 作 host，提示用户该类事件不可手动切换。
        if (entry.scope != LogRepository.DecisionScope.DOMAIN) {
            val scopeLabel = scopeLabelOf(entry.scope)
            android.widget.Toast.makeText(
                applicationContext,
                "$scopeLabel 类拦截事件无法在此手工切换，请到「规则管理」中通过 IP-CIDR/端口规则管理",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val appContext = applicationContext
        val newAction = if (entry.type == LogRepository.DomainDecisionType.BLOCKED) "放行" else "拦截"
        // 切换决策涉及 RuleRepository SP/文件读+写 (可能数 MB 同步 IO)，必须放后台
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LogRepository.toggleDomainDecision(appContext, entry.domain, entry.type)
            }
            NetworkKernel.reloadIfRunning(appContext)
            android.widget.Toast.makeText(appContext, "已将该域名切换为 $newAction", android.widget.Toast.LENGTH_SHORT).show()
            loadEntries()
        }
    }

    private fun scopeLabelOf(scope: LogRepository.DecisionScope): String = when (scope) {
        LogRepository.DecisionScope.DOMAIN -> "域名"
        LogRepository.DecisionScope.IP_CIDR -> "IP / CIDR"
        LogRepository.DecisionScope.PORT_ONLY -> "端口"
        LogRepository.DecisionScope.ENCRYPTED_DNS_SNI -> "加密 DNS SNI"
        LogRepository.DecisionScope.LEARNING_FEEDBACK -> "学习反馈 IP"
    }

    private fun loadEntries() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { LogRepository.getDomainDecisionEntries(applicationContext) }
                    .getOrElse { emptyList() }
            }
            if (isFinishing || isDestroyed) return@launch
            allEntries = entries
            applyFilters()
        }
    }

    private fun applyFilters() {
        val query = binding.searchInput.text?.toString().orEmpty().trim().lowercase()
        val filtered = allEntries.filter { entry ->
            val typeMatched = filter == null || entry.type == filter
            val queryMatched = query.isBlank() ||
                entry.identifier.lowercase().contains(query) ||
                entry.domain.lowercase().contains(query) ||
                entry.message.lowercase().contains(query)
            typeMatched && queryMatched
        }
        adapter.submitList(filtered)
        val blockedCount = filtered.count { it.type == LogRepository.DomainDecisionType.BLOCKED }
        val allowedCount = filtered.count { it.type == LogRepository.DomainDecisionType.ALLOWED }
        binding.summaryText.text = "当前显示 ${filtered.size} 条，拦截 ${blockedCount} 条，放行 ${allowedCount} 条"
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        updateFilterButtons()
    }

    private fun updateFilterButtons() {
        updateFilterButton(binding.btnFilterAll, filter == null)
        updateFilterButton(binding.btnFilterBlocked, filter == LogRepository.DomainDecisionType.BLOCKED)
        updateFilterButton(binding.btnFilterAllowed, filter == LogRepository.DomainDecisionType.ALLOWED)
    }

    private fun updateFilterButton(view: View, selected: Boolean) {
        view.alpha = if (selected) 1f else 0.72f
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, DecisionDomainsActivity::class.java)

        private val DIFF = object : DiffUtil.ItemCallback<LogRepository.DomainDecisionEntry>() {
            override fun areItemsTheSame(
                oldItem: LogRepository.DomainDecisionEntry,
                newItem: LogRepository.DomainDecisionEntry
            ): Boolean =
                oldItem.type == newItem.type &&
                oldItem.scope == newItem.scope &&
                oldItem.identifier == newItem.identifier

            override fun areContentsTheSame(
                oldItem: LogRepository.DomainDecisionEntry,
                newItem: LogRepository.DomainDecisionEntry
            ): Boolean = oldItem == newItem
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private class DecisionDomainAdapter(
        private val dateFormat: SimpleDateFormat,
        private val onToggleRequest: (LogRepository.DomainDecisionEntry) -> Unit
    ) : ListAdapter<LogRepository.DomainDecisionEntry, DecisionDomainAdapter.ViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemDecisionDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false), dateFormat, onToggleRequest)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        class ViewHolder(
            private val binding: ItemDecisionDomainBinding,
            private val dateFormat: SimpleDateFormat,
            private val onToggleRequest: (LogRepository.DomainDecisionEntry) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: LogRepository.DomainDecisionEntry) {
                val isDomain = item.scope == LogRepository.DecisionScope.DOMAIN
                // 主标识：identifier（域名/IP/CIDR/加密 DNS SNI 等），更直观
                val display = item.identifier.ifBlank { item.domain }
                binding.textDomain.text = display

                val scopeText = when (item.scope) {
                    LogRepository.DecisionScope.DOMAIN -> "域名"
                    LogRepository.DecisionScope.IP_CIDR -> "IP/CIDR"
                    LogRepository.DecisionScope.PORT_ONLY -> "端口"
                    LogRepository.DecisionScope.ENCRYPTED_DNS_SNI -> "加密DNS"
                    LogRepository.DecisionScope.LEARNING_FEEDBACK -> "学习IP"
                }
                val blocked = item.type == LogRepository.DomainDecisionType.BLOCKED
                binding.textType.text = "${if (blocked) "拦截" else "放行"} · $scopeText"
                binding.textType.setBackgroundResource(
                    if (blocked) com.HanFeng.R.drawable.bg_decision_blocked else com.HanFeng.R.drawable.bg_decision_allowed
                )
                binding.textType.setTextColor(
                    ContextCompat.getColor(
                        binding.root.context,
                        if (blocked) com.HanFeng.R.color.hf_blocked_red else com.HanFeng.R.color.hf_allowed_green
                    )
                )
                binding.textAppName.text = item.appName
                binding.textTime.text = dateFormat.format(Date(item.timestamp))
                
                if (isDomain) {
                    // 仅域名类事件支持长按手工切换
                    binding.root.setOnLongClickListener {
                        val context = binding.root.context
                        val currentAction = if (blocked) "拦截" else "放行"
                        val newAction = if (blocked) "放行" else "拦截"
                        
                        runCatching {
                            android.app.AlertDialog.Builder(context)
                                .setTitle("切换决策")
                                .setMessage("将 $display\n从【${currentAction}】切换为【${newAction}】？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("确认") { _, _ ->
                                    onToggleRequest(item)
                                }
                                .show()
                        }
                        true
                    }
                } else {
                    // 非 DOMAIN 类拦截禁用长按，避免误触发无法 fallback 的切换路径
                    binding.root.setOnLongClickListener {
                        android.widget.Toast.makeText(
                            binding.root.context,
                            "$scopeText 类拦截事件无法在此手工切换",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        searchJob?.cancel()
        super.onDestroy()
    }
}
