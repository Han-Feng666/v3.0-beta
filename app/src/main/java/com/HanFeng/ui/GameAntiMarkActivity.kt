package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.adblocker.shizuku.GameAntiMarkManager
import com.HanFeng.adblocker.shizuku.SuSession
import com.HanFeng.data.GameAntiMarkRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.databinding.ActivityGameAntiMarkBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameAntiMarkActivity : BaseActivity() {

    private lateinit var binding: ActivityGameAntiMarkBinding
    private var sm8850Detected = false
    private var watcherReady = false
    private val handler = Handler(Looper.getMainLooper())
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) fastRefreshStatus()
            if (watcherReady) handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityGameAntiMarkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.switchWatcher.setOnCheckedChangeListener { _, isChecked ->
            if (watcherReady) toggleWatcher(isChecked)
        }
        binding.btnPackageList.setOnClickListener {
            launchActivitySafely(
                GamePackageListActivity.createIntent(this),
                failureMessage = "打开游戏包名列表失败"
            )
        }
        binding.btnShowLog.setOnClickListener { showWatcherLog() }
        binding.btnRestorePermission.setOnClickListener { restorePermissionManually() }

        binding.switchWatcher.isEnabled = false
        binding.btnPackageList.isEnabled = false
        binding.btnShowLog.isEnabled = false
        binding.btnRestorePermission.isEnabled = false

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.removeCallbacks(statusRefreshRunnable)
        handler.postDelayed(statusRefreshRunnable, 3000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRefreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusRefreshRunnable)
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val rootOk = withContext(Dispatchers.IO) {
                val s = SuSession.getInstance()
                s.isSessionOpen() || s.open(15)
            }
            binding.btnPackageList.isEnabled = rootOk
            binding.btnShowLog.isEnabled = rootOk
            binding.btnRestorePermission.isEnabled = rootOk
            binding.switchWatcher.isEnabled = rootOk
            watcherReady = rootOk

            if (!rootOk) {
                binding.tvStatus.text = "Root 不可用。该功能依赖 Root（Magisk/KernelSU/APatch）。"
                handler.removeCallbacks(statusRefreshRunnable)
                return@launch
            }

            sm8850Detected = withContext(Dispatchers.IO) { GameAntiMarkManager.checkSm8850() }
            GameAntiMarkRepository.setSm8850Detected(this@GameAntiMarkActivity, sm8850Detected)

            val status = withContext(Dispatchers.IO) {
                GameAntiMarkManager.status(this@GameAntiMarkActivity)
            }
            renderStatus(status)
        }
    }

    private fun fastRefreshStatus() {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                GameAntiMarkManager.status(this@GameAntiMarkActivity)
            }
            if (!isFinishing && !isDestroyed) renderStatus(status)
        }
    }

    private fun renderStatus(status: GameAntiMarkManager.WatcherStatus) {
        val text = buildString {
            append("守护：").append(if (status.running) "运行中" else "未运行")
            if (status.pid != null) append("（PID ${status.pid}）")
            appendLine()
            append("运行中游戏：").append(status.gamesRunning).appendLine()
            append("已清理次数：").append(status.cleanedCount)
            if (status.lastCleanedAt.isNotBlank()) {
                append(" / 最近：").append(status.lastCleanedAt)
            }
        }
        binding.tvStatus.text = text

        binding.switchWatcher.setOnCheckedChangeListener(null)
        binding.switchWatcher.isChecked = status.running
        binding.switchWatcher.setOnCheckedChangeListener { _, isChecked ->
            if (watcherReady) toggleWatcher(isChecked)
        }
    }

    private fun toggleWatcher(start: Boolean) {
        lifecycleScope.launch {
            binding.switchWatcher.isEnabled = false
            val result = withContext(Dispatchers.IO) {
                if (start) {
                    val started = GameAntiMarkManager.start(this@GameAntiMarkActivity, sm8850Fallback = sm8850Detected)
                    if (started) {
                        runCatching { GameAntiMarkManager.randomizeDeviceIds(this@GameAntiMarkActivity) }
                    }
                    started
                } else {
                    GameAntiMarkManager.stopAndRestore(this@GameAntiMarkActivity)
                }
            }
            binding.switchWatcher.isEnabled = true
            if (!result) {
                showShortToast(if (start) "启动监听失败，请检查 Root 状态" else "停止监听失败")
                binding.switchWatcher.setOnCheckedChangeListener(null)
                binding.switchWatcher.isChecked = !start
                binding.switchWatcher.setOnCheckedChangeListener { _, isChecked ->
                    if (watcherReady) toggleWatcher(isChecked)
                }
            } else {
                showShortToast(if (start) "防标记守护已启动（已随机 AndroidID/SSAID，重启后生效）" else "防标记守护已停止")
            }
            refreshStatus()
        }
    }

    private fun showWatcherLog() {
        lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) {
                GameAntiMarkManager.dumpLog(200)
            }
            StableDialog.builder(this@GameAntiMarkActivity)
                .setTitle("守护日志（最近 200 行）")
                .setMessage(log.ifBlank { "(暂无日志)" })
                .setPositiveButton("关闭", null)
                .showSafely(this@GameAntiMarkActivity, "Show watcher log failed")
        }
    }

    private fun restorePermissionManually() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val s = SuSession.getInstance()
                if (!s.isSessionOpen() && !s.open(10)) return@withContext false
                val cmd = GameAntiMarkRepository.TARGET_DIR_CANDIDATES.joinToString(" && ") {
                    "chmod 700 '$it' 2>/dev/null"
                } + " && echo OK || echo NOSUCH"
                val r = s.execute(cmd, 5)
                r.output.trim().let { it.contains("OK") || it.contains("NOSUCH") }
            }
            LogRepository.append(this@GameAntiMarkActivity, "[GameAntiMark] manual restore permission result=$ok")
            showShortToast(if (ok) "已恢复 persist 目录权限为 700" else "恢复权限失败，请检查 Root")
            if (ok) refreshStatus()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, GameAntiMarkActivity::class.java)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
