package com.HanFeng.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuConnectionOwnerRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.data.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

open class BaseActivity : AppCompatActivity() {
    companion object {
        private var lastAppliedHideBackground: Boolean? = null
        private var lastAppliedHideBackgroundAt: Long = 0L
    }

    private val hideBackgroundHandler = Handler(Looper.getMainLooper())

    protected data class ShizukuReadyState(
        val status: ShizukuRepository.Status,
        val connectionOwnerAlive: Boolean,
        val adControlAlive: Boolean
    ) {
        val serviceHealthy: Boolean
            get() = connectionOwnerAlive || adControlAlive

        val readyForEnhancedUse: Boolean
            get() = status.installed && status.binderAlive && (status.permissionGranted || serviceHealthy)
    }

    protected fun applyHideBackgroundPolicy(enabled: Boolean) {
        lastAppliedHideBackground = enabled
        lastAppliedHideBackgroundAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 5 秒节流:同一 enabled 状态在 5s 内不重复调用 appTasks.forEach(setExcludeFromRecents)
            // am.appTasks 是跨进程 IPC,每次都开销不小,连续 Activity 切换时无需重复跑
            runCatching {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.appTasks.forEach { task ->
                    task.setExcludeFromRecents(enabled)
                }
            }.onFailure {
                LogRepository.append(this, "applyHideBackgroundPolicy excludeFromRecents failed: ${it.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (enabled) {
                setRecentsScreenshotEnabled(false)
            } else {
                setRecentsScreenshotEnabled(true)
            }
        }
    }

    private fun applyHideBackgroundPolicyIfNeeded(enabled: Boolean) {
        val now = System.currentTimeMillis()
        val lastEnabled = lastAppliedHideBackground
        if (lastEnabled == enabled && now - lastAppliedHideBackgroundAt < 5_000L) return
        applyHideBackgroundPolicy(enabled)
    }

    private fun removeTaskFromRecentsIfHidden() {
        if (!AppSettingsRepository.isHideBackgroundEnabled(this)) return
        applyHideBackgroundPolicy(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                finishAndRemoveTask()
                LogRepository.append(this, "HideBackground removed task from recents")
            }.onFailure {
                LogRepository.append(this, "HideBackground finishAndRemoveTask failed: ${it.message ?: it.javaClass.simpleName}")
            }
        } else {
            runCatching { finish() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hideBackgroundEnabled = AppSettingsRepository.isHideBackgroundEnabled(this)
        applyHideBackgroundPolicyIfNeeded(hideBackgroundEnabled)
    }

    override fun onStart() {
        super.onStart()
        hideBackgroundHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        val hideBackgroundEnabled = AppSettingsRepository.isHideBackgroundEnabled(this)
        applyHideBackgroundPolicyIfNeeded(hideBackgroundEnabled)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        hideBackgroundHandler.postDelayed({
            // 关键修复:只有 App 真的退到后台(全局 started Activity 计数为 0)才执行隐藏逻辑。
            // 之前用本类静态 startedActivityCount 判断,在新 Activity 的 onStart 来不及执行时
            // (比如子 Activity 启动慢、系统调度延迟),1.2s 后会误触发 finishAndRemoveTask
            // 把自己干掉,导致用户从子页面返回时直接回到主界面。
            if (!com.HanFeng.HanFengApp.isAppInForeground() && !isFinishing && !isDestroyed) {
                removeTaskFromRecentsIfHidden()
            }
        }, 1_200L)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBackgroundHandler.removeCallbacksAndMessages(null)
    }

    protected fun showShortToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    protected fun showLongToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    protected fun showMessageDialog(title: String? = null, message: String, errorTag: String) {
        val builder = StableDialog.builder(this)
        if (!title.isNullOrBlank()) {
            builder.setTitle(title)
        }
        builder
            .setMessage(message)
            .setPositiveButton("确定", null)
            .showSafely(this, errorTag)
    }

    protected fun openExternalUrl(url: String, unavailableMessage: String = "未找到可用应用") {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            showShortToast(unavailableMessage)
        }
    }

    protected fun launchActivitySafely(intent: Intent, failureMessage: String) {
        runCatching {
            startActivity(intent)
        }.onFailure {
            showShortToast(failureMessage)
        }
    }

    protected fun warmShizukuServicesBlocking(): Boolean {
        repeat(3) {
            runCatching { ShizukuConnectionOwnerRepository.ensureBound(this) }
            runCatching { ShizukuAdControlRepository.ensureBoundAndWait(this) }
            val ownerAlive = runCatching { ShizukuConnectionOwnerRepository.isServiceAlive() }.getOrDefault(false)
            val adAlive = runCatching { ShizukuAdControlRepository.checkServiceHealth(this) }.getOrDefault(false)
            if (ownerAlive || adAlive) return true
        }
        return false
    }

    protected suspend fun queryShizukuReadyState(warmIfNeeded: Boolean): ShizukuReadyState {
        val status = ShizukuRepository.getStatus(this)
        if (!status.installed || !status.binderAlive) {
            return ShizukuReadyState(
                status = status,
                connectionOwnerAlive = false,
                adControlAlive = false
            )
        }
        if (warmIfNeeded) {
            withContext(Dispatchers.IO) {
                warmShizukuServicesBlocking()
            }
        }
        return ShizukuReadyState(
            status = ShizukuRepository.getStatus(this),
            connectionOwnerAlive = runCatching { ShizukuConnectionOwnerRepository.isServiceAlive() }.getOrDefault(false),
            adControlAlive = runCatching { ShizukuAdControlRepository.checkServiceHealth(this@BaseActivity) }.getOrDefault(false)
        )
    }

    protected fun buildShizukuUnavailableMessage(state: ShizukuReadyState): String {
        return when {
            !state.status.installed -> "请先安装 Shizuku。"
            !state.status.binderAlive -> "请先启动 Shizuku。"
            !state.status.permissionStateKnown && !state.serviceHealthy -> "当前 Shizuku 可以连通，但权限状态读取异常，且增强服务尚未就绪。请重新打开 Shizuku 后重试。"
            !state.status.permissionStateKnown -> "当前 Shizuku 可以连通，但权限状态读取异常。请重新打开 Shizuku 后重试，必要时更换兼容性更好的版本。"
            else -> "请先授权 Shizuku。"
        }
    }

    protected fun handleShizukuAccessRequest(onStateChanged: () -> Unit = {}) {
        if (!AppSettingsRepository.isShizukuEnabled(this)) {
            showShortToast("Shizuku 增强已在设置中关闭")
            onStateChanged()
            return
        }
        // warmShizukuServicesBlocking() 最多 4.5s runBlocking 同步等待 binder，主线程会卡 1-5 秒，丢到 IO 协程
        lifecycleScope.launch {
            val readyState = withContext(Dispatchers.IO) {
                runCatching {
                    val status = ShizukuRepository.getStatus(this@BaseActivity)
                    if (!status.installed || !status.binderAlive) {
                        ShizukuReadyState(status, connectionOwnerAlive = false, adControlAlive = false)
                    } else {
                        warmShizukuServicesBlocking()
                        ShizukuReadyState(
                            status = ShizukuRepository.getStatus(this@BaseActivity),
                            connectionOwnerAlive = runCatching { ShizukuConnectionOwnerRepository.isServiceAlive() }.getOrDefault(false),
                            adControlAlive = runCatching { ShizukuAdControlRepository.checkServiceHealth(this@BaseActivity) }.getOrDefault(false)
                        )
                    }
                }.getOrElse {
                    ShizukuReadyState(
                        status = ShizukuRepository.getStatus(this@BaseActivity),
                        connectionOwnerAlive = false,
                        adControlAlive = false
                    )
                }
            }
            if (isFinishing || isDestroyed) return@launch
            // lifecycleScope.launch 默认就在 Main，无需再切换
            when {
                !readyState.status.installed -> {
                    showShizukuGuideDialog(
                        title = "需要先安装 Shizuku",
                        message = "Shizuku 增强模式需要先安装并启动 Shizuku。安装完成后，再回到寒枫进行授权。",
                        positiveLabel = "前往下载"
                    ) {
                        ShizukuRepository.openDownloadPage(this@BaseActivity)
                    }
                }
                !readyState.status.binderAlive -> {
                    showShizukuGuideDialog(
                        title = "需要先启动 Shizuku",
                        message = "请先在 Shizuku App 中启动服务。Android 11 及以上通常可通过无线调试启动，已 Root 设备也可以直接启动。",
                        positiveLabel = "我知道了"
                    ) {
                        onStateChanged()
                    }
                }
                readyState.serviceHealthy && !readyState.status.permissionGranted -> {
                    showShortToast("Shizuku 已可用，当前按兼容模式接入增强能力")
                    onStateChanged()
                }
                !readyState.status.permissionStateKnown -> {
                    showShizukuGuideDialog(
                        title = "Shizuku 权限状态异常",
                        message = buildShizukuUnavailableMessage(readyState),
                        positiveLabel = "我知道了"
                    ) {
                        onStateChanged()
                    }
                }
                readyState.status.permissionGranted -> {
                    showShortToast("Shizuku 已授权，增强服务会继续完成连接")
                    onStateChanged()
                }
                ShizukuRepository.requestPermission() -> {
                    showShortToast("正在请求 Shizuku 授权")
                }
                else -> {
                    showShizukuGuideDialog(
                        title = "Shizuku 需要手动授权",
                        message = "请确认 Shizuku 已运行，并在弹出的授权界面中允许寒枫访问。如果之前拒绝过，需要先在 Shizuku 中清理授权状态。",
                        positiveLabel = "我知道了"
                    ) {
                        onStateChanged()
                    }
                }
            }
        }
    }

    protected fun handleShizukuPermissionResult(granted: Boolean, onStateChanged: () -> Unit = {}) {
        if (granted) {
            // warmShizukuServicesBlocking() 内最多 3 × 1.5 = 4.5s runBlocking，主线程会卡顿，丢到 IO 协程
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { warmShizukuServicesBlocking() }
                // lifecycleScope.launch 默认就走回 Main，无需再切
                showShortToast("Shizuku 授权成功")
                onStateChanged()
            }
        } else {
            showShortToast("Shizuku 授权失败")
            onStateChanged()
        }
    }

    private fun showShizukuGuideDialog(title: String, message: String, positiveLabel: String, action: () -> Unit) {
        StableDialog.builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveLabel) { _, _ -> action() }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show shizuku guide dialog failed")
    }

}
