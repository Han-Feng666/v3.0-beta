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
        binding.btnRandomizeIds.setOnClickListener { confirmRandomizeIds() }
        binding.btnShowLog.setOnClickListener { showWatcherLog() }
        binding.btnRestorePermission.setOnClickListener { restorePermissionManually() }

        binding.switchWatcher.isEnabled = false
        binding.btnPackageList.isEnabled = false
        binding.btnRandomizeIds.isEnabled = false
        binding.btnShowLog.isEnabled = false
        binding.btnRestorePermission.isEnabled = false

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val rootOk = withContext(Dispatchers.IO) {
                val s = SuSession.getInstance()
                s.isSessionOpen() || s.open(15)
            }
            binding.btnPackageList.isEnabled = rootOk
            binding.btnRandomizeIds.isEnabled = rootOk
            binding.btnShowLog.isEnabled = rootOk
            binding.btnRestorePermission.isEnabled = rootOk
            binding.switchWatcher.isEnabled = rootOk
            watcherReady = rootOk

            if (!rootOk) {
                binding.tvStatus.text = "Root 不可用。该功能依赖 Root（Magisk/KernelSU/APatch）。"
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

    private fun renderStatus(status: GameAntiMarkManager.WatcherStatus) {
        val text = buildString {
            append("监听守护状态：").append(if (status.running) "运行中" else "未运行")
            if (status.pid != null) append("（PID ${status.pid}）")
            appendLine()
            append("Root 方案：").append(SuSession.getInstance().rootSolution.name)
            if (SuSession.getInstance().rootVersion.isNotBlank()) {
                append(" ").append(SuSession.getInstance().rootVersion)
            }
            appendLine()
            if (status.sm8850Detected) {
                append("SoC：骁龙 8 Elite 5（SM8850）— 已检测，跳过权限修改防 TEE 损坏")
                appendLine()
            }
            append("游戏包名数量：").append(
                GameAntiMarkRepository.getTargetPackages(this@GameAntiMarkActivity).size
            ).appendLine()
            append("当前运行中游戏：").append(status.gamesRunning).appendLine()
            append("已清理次数：").append(status.cleanedCount).appendLine()
            append("/mnt/vendor/persist/data 权限：").append(status.persistPerm).appendLine()
            append("boot_completed：").append(if (status.bootCompleted) "是" else "否").appendLine()
            if (status.lastCleanedAt.isNotBlank()) {
                append("最近一次清理：").append(status.lastCleanedAt)
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
                    GameAntiMarkManager.start(this@GameAntiMarkActivity, sm8850Fallback = sm8850Detected)
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
                showShortToast(if (start) "防标记守护已启动" else "防标记守护已停止")
            }
            refreshStatus()
        }
    }

    private fun confirmRandomizeIds() {
        StableDialog.builder(this)
            .setTitle("随机修改 AndroidID/SSAID")
            .setMessage("将随机生成新的 android_id 并修改所有用户对每个游戏包名的 SSAID。\n\n需要重启手机后生效。建议在确认无重要签到/激活关系后再操作。\n\n是否继续？")
            .setPositiveButton("确认修改") { _, _ -> executeRandomizeIds() }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show randomize ids confirm failed")
    }

    private fun executeRandomizeIds() {
        lifecycleScope.launch {
            binding.btnRandomizeIds.isEnabled = false
            val (ok, msg) = withContext(Dispatchers.IO) {
                GameAntiMarkManager.randomizeDeviceIds(this@GameAntiMarkActivity)
            }
            binding.btnRandomizeIds.isEnabled = true
            StableDialog.builder(this@GameAntiMarkActivity)
                .setTitle(if (ok) "修改完成" else "修改失败")
                .setMessage(msg)
                .setPositiveButton("我知道了", null)
                .showSafely(this@GameAntiMarkActivity, "Show randomize result failed")
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
                val r = s.execute("chmod 700 '${GameAntiMarkRepository.TARGET_DIR}' 2>/dev/null && echo OK || echo NOSUCH", 5)
                r.output.trim().let { it.contains("OK") || it.contains("NOSUCH") }
            }
            LogRepository.append(this@GameAntiMarkActivity, "[GameAntiMark] manual restore permission result=$ok")
            showShortToast(if (ok) "已恢复 /mnt/vendor/persist/data 权限为 700" else "恢复权限失败，请检查 Root")
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
