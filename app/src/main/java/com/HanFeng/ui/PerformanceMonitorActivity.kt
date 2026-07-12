package com.HanFeng.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.HanFeng.R
import com.HanFeng.adblocker.shizuku.PerfMonitor
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.PerfFloatMode
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.data.ShizukuPerformanceRepository
import com.HanFeng.service.FloatingPerfService

class PerformanceMonitorActivity : BaseActivity() {

    private lateinit var tvStatusHint: TextView
    private lateinit var processListView: android.widget.ListView
    private lateinit var btnRefresh: View
    private lateinit var autoRefreshSwitch: Switch
    private lateinit var floatSwitch: Switch
    private lateinit var radioCpu: android.widget.RadioButton
    private lateinit var radioMemory: android.widget.RadioButton
    private lateinit var floatModeGroup: RadioGroup

    private val handler = Handler(Looper.getMainLooper())
    private val processAdapter = ProcessPerfAdapter(this)
    private var autoRefresh = false
    private var lastSnapshot: List<PerfMonitor.ProcessSnapshot> = emptyList()
    @Volatile private var collectingThreadId: Long = -1L

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            refreshSnapshot()
            if (autoRefresh) {
                handler.postDelayed(this, REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_performance_monitor)
        bindViews()
        setupListeners()
        floatSwitch.isChecked = FeatureSettingsRepository.isPerfFloatEnabled(this)
        autoRefreshSwitch.isChecked = false
        if (FeatureSettingsRepository.getPerfFloatMode(this) == PerfFloatMode.MEMORY) {
            radioMemory.isChecked = true
        } else {
            radioCpu.isChecked = true
        }
        refreshSnapshot()
    }

    private fun bindViews() {
        tvStatusHint = findViewById(R.id.tvStatusHint)
        processListView = findViewById(R.id.processList)
        btnRefresh = findViewById(R.id.btnRefresh)
        autoRefreshSwitch = findViewById(R.id.autoRefreshSwitch)
        floatSwitch = findViewById(R.id.floatSwitch)
        radioCpu = findViewById(R.id.radioCpu)
        radioMemory = findViewById(R.id.radioMemory)
        floatModeGroup = findViewById(R.id.floatModeGroup)
        processListView.adapter = processAdapter
    }

    private fun setupListeners() {
        btnRefresh.setOnClickListener {
            refreshSnapshot()
        }
        autoRefreshSwitch.setOnCheckedChangeListener { _, isChecked ->
            autoRefresh = isChecked
            if (autoRefresh) {
                handler.post(autoRefreshRunnable)
            } else {
                handler.removeCallbacks(autoRefreshRunnable)
            }
        }
        floatSwitch.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setPerfFloatEnabled(this, isChecked)
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                    floatSwitch.isChecked = false
                    FeatureSettingsRepository.setPerfFloatEnabled(this, false)
                    return@setOnCheckedChangeListener
                }
                startFloatingService()
            } else {
                stopFloatingService()
            }
        }
        floatModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.radioMemory) PerfFloatMode.MEMORY else PerfFloatMode.CPU
            FeatureSettingsRepository.setPerfFloatMode(this, mode)
        }
    }

    private fun refreshSnapshot() {
        if (collectingThreadId != -1L) {
            Log.w(TAG, "previous snapshot still running, skip")
            return
        }
        tvStatusHint.text = "正在采集性能数据..."
        Thread {
            collectingThreadId = Thread.currentThread().id
            try {
                val future = java.util.concurrent.FutureTask<List<PerfMonitor.ProcessSnapshot>> {
                    PerfMonitor.snapshot(this)
                }
                Thread(future, "perf-snapshot").start()
                val snapshot = try {
                    future.get(SNAPSHOT_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (te: java.util.concurrent.TimeoutException) {
                    future.cancel(true)
                    runOnUiThread {
                        tvStatusHint.text = "采集超时：可能是 Root 权限弹窗未确认，或 Shizuku 服务未启动。请到设置页确认授权后重试。"
                    }
                    PerfMonitor.reset()
                    return@Thread
                }
                lastSnapshot = snapshot
                runOnUiThread {
                    processAdapter.submit(snapshot)
                    val cpuSum = snapshot.sumOf { it.cpuPercent.toInt() }
                    val memSumKb = snapshot.sumOf { it.rssKb }
                    tvStatusHint.text = formatStatus(snapshot.size, cpuSum, memSumKb)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    tvStatusHint.text = "采集失败：${t.message ?: t.javaClass.simpleName}"
                }
            } finally {
                collectingThreadId = -1L
            }
        }.start()
    }

    private fun formatStatus(count: Int, cpuSum: Int, memSumKb: Long): String {
        return "共 $count 个进程  CPU 总占用 $cpuSum%  内存 RSS ${formatMem(memSumKb)}"
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoRefreshRunnable)
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingPerfService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingPerfService::class.java)
        stopService(intent)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            launchActivitySafely(intent, "无法跳转到悬浮窗权限设置")
        }
    }

    private class ProcessPerfAdapter(activity: Activity) : BaseAdapter() {
        private val ctx: Context = activity
        private var items: List<PerfMonitor.ProcessSnapshot> = emptyList()

        fun submit(list: List<PerfMonitor.ProcessSnapshot>) {
            items = list.take(200)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.item_process_perf, parent, false)
            val item = items[position]
            view.findViewById<TextView>(R.id.txtPid).text = item.pid.toString()
            view.findViewById<TextView>(R.id.txtName).text = item.name
            view.findViewById<TextView>(R.id.txtCpu).text = "%.1f%%".format(item.cpuPercent.coerceAtMost(999f))
            view.findViewById<TextView>(R.id.txtMem).text = formatMem(item.rssKb)
            view.findViewById<android.widget.ProgressBar>(R.id.cpuBar).progress =
                item.cpuPercent.toInt().coerceIn(0, 100)
            view.findViewById<TextView>(R.id.badgeForeground).visibility =
                if (item.foreground) View.VISIBLE else View.GONE
            return view
        }

        private fun formatMem(kb: Long): String {
            return if (kb < 1024) "${kb} KB"
            else if (kb < 1024 * 1024) "%.1f MB".format(kb / 1024.0)
            else "%.2f GB".format(kb / 1024.0 / 1024.0)
        }
    }

    private fun formatMem(kb: Long): String {
        return if (kb < 1024) "${kb} KB"
        else if (kb < 1024 * 1024) "%.1f MB".format(kb / 1024.0)
        else "%.2f GB".format(kb / 1024.0 / 1024.0)
    }

    companion object {
        private const val TAG = "PerfMonitorActivity"
        private const val REFRESH_INTERVAL_MILLIS = 2000L
        private const val SNAPSHOT_TIMEOUT_MILLIS = 12000L

        fun createIntent(context: Context): Intent {
            return Intent(context, PerformanceMonitorActivity::class.java)
        }
    }
}
