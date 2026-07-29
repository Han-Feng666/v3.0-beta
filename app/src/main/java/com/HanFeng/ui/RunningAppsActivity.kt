package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.service.ProcessMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class RunningAppsActivity : AppCompatActivity() {

    private lateinit var rvList: RecyclerView
    private lateinit var tvSummary: TextView
    private lateinit var rgSort: RadioGroup
    private lateinit var adapter: RunningAppsAdapter

    @Volatile private var sortMode: Int = 0
    private var collectionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_running_apps)

        rvList = findViewById(R.id.rvRunningApps)
        tvSummary = findViewById(R.id.tvRunningSummary)
        rgSort = findViewById(R.id.rgSort)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        // 隐藏 "授权" 按钮 — Shizuku /proc 扫描不需要使用情况权限
        findViewById<Button>(R.id.btnGrantUsage).visibility = View.GONE

        adapter = RunningAppsAdapter(this)
        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = adapter
        rvList.setHasFixedSize(true)

        tvSummary.text = "正在刷新进程列表..."

        rgSort.setOnCheckedChangeListener { _, checkedId ->
            sortMode = if (checkedId == R.id.rbSortCpu) 1 else 0
            adapter.updateSortMode(sortMode)
        }
    }

    override fun onResume() {
        super.onResume()
        startCollection()
    }

    override fun onPause() {
        super.onPause()
        stopCollection()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCollection()
        adapter.shutdown()
    }

    private fun startCollection() {
        if (collectionJob?.isActive == true) return

        val monitor = ProcessMonitor.getInstance(this)
        monitor.startSampling(lifecycleScope)

        collectionJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                monitor.processFlow.collect { list ->
                    if (isFinishing || isDestroyed) return@collect

                    val sorted = if (sortMode == 1) {
                        list.sortedWith(compareByDescending<ProcessMonitor.ProcessInfo> { it.cpuPercent }
                            .thenByDescending { it.rssBytes })
                    } else {
                        list.sortedWith(compareByDescending<ProcessMonitor.ProcessInfo> { it.rssBytes }
                            .thenByDescending { it.cpuPercent })
                    }
                    adapter.submit(sorted)

                    val totalMem = list.sumOf { it.rssBytes }
                    val hint = if (list.size <= 1) " · Android 11+ 仅显示本应用, 需 Shizuku 授权看全部"
                               else ""
                    tvSummary.text = "运行中进程 ${list.size} 个 · 总占用 ${ProcessMonitor.formatMemorySize(totalMem)}$hint"
                }
            }
        }
    }

    private fun stopCollection() {
        collectionJob?.cancel()
        collectionJob = null
        val monitor = ProcessMonitor.getInstance(this)
        monitor.stopSampling()
    }
}

class RunningAppsAdapter(private val context: Context) :
    ListAdapter<ProcessMonitor.ProcessInfo, RunningAppsAdapter.Holder>(DIFF) {

    private val iconCache = androidx.collection.LruCache<String, Drawable>(64)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var sortMode: Int = 0

    fun submit(list: List<ProcessMonitor.ProcessInfo>) {
        submitList(list)
    }

    fun updateSortMode(mode: Int) {
        sortMode = mode
        // Re-sort and re-submit current list
        val current = currentList
        if (current.isNotEmpty()) {
            val sorted = if (mode == 1) {
                current.sortedWith(compareByDescending<ProcessMonitor.ProcessInfo> { it.cpuPercent }
                    .thenByDescending { it.rssBytes })
            } else {
                current.sortedWith(compareByDescending<ProcessMonitor.ProcessInfo> { it.rssBytes }
                    .thenByDescending { it.cpuPercent })
            }
            submitList(sorted)
        }
    }

    fun shutdown() {
        iconCache.evictAll()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_running_app, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvMem: TextView = itemView.findViewById(R.id.tvMemUsage)
        private val tvCpu: TextView = itemView.findViewById(R.id.tvCpuUsage)

        fun bind(item: ProcessMonitor.ProcessInfo) {
            tvName.text = if (item.isForeground) "${item.label} (前台)" else item.label
            tvMem.text = item.formatRss()
            tvCpu.text = item.formatCpu()

            val cached = iconCache[item.packageName]
            if (cached != null) {
                ivIcon.setImageDrawable(cached)
                return
            }
            ivIcon.setImageDrawable(null)
            val pkg = item.packageName
            if (pkg.isEmpty() || !pkg.contains(".") || pkg.startsWith("/") || pkg.startsWith("[")) {
                return
            }
            IconExecutorPool.executor.execute {
                val dr = runCatching {
                    context.packageManager.getApplicationIcon(pkg)
                }.getOrNull()
                if (dr != null) {
                    iconCache.put(pkg, dr)
                    mainHandler.post {
                        val pos = bindingAdapterPosition
                        if (pos in 0 until itemCount && getItem(pos).packageName == pkg) {
                            ivIcon.setImageDrawable(dr)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProcessMonitor.ProcessInfo>() {
            override fun areItemsTheSame(
                oldItem: ProcessMonitor.ProcessInfo,
                newItem: ProcessMonitor.ProcessInfo
            ): Boolean = oldItem.packageName == newItem.packageName && oldItem.pid == newItem.pid

            override fun areContentsTheSame(
                oldItem: ProcessMonitor.ProcessInfo,
                newItem: ProcessMonitor.ProcessInfo
            ): Boolean = oldItem == newItem
        }
    }
}