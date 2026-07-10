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
import kotlinx.coroutines.withContext

open class BaseActivity : AppCompatActivity() {
    companion object {
        private val hideBackgroundHandler = Handler(Looper.getMainLooper())
        private var startedActivityCount = 0
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.appTasks.forEach { task ->
                    task.setExcludeFromRecents(enabled)
                }
                LogRepository.append(this, "applyHideBackgroundPolicy: enabled=$enabled tasks=${am.appTasks.size}")
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
        LogRepository.append(this, "BaseActivity onCreate: hideBackgroundEnabled=$hideBackgroundEnabled")
        applyHideBackgroundPolicy(hideBackgroundEnabled)
    }

    override fun onStart() {
        super.onStart()
        startedActivityCount += 1
        hideBackgroundHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        val hideBackgroundEnabled = AppSettingsRepository.isHideBackgroundEnabled(this)
        LogRepository.append(this, "BaseActivity onResume: hideBackgroundEnabled=$hideBackgroundEnabled")
        applyHideBackgroundPolicy(hideBackgroundEnabled)
    }

    override fun onStop() {
        super.onStop()
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount > 0 || isChangingConfigurations) return
        hideBackgroundHandler.postDelayed({
            if (startedActivityCount == 0) {
                removeTaskFromRecentsIfHidden()
            }
        }, 450L)
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
        val readyState = runCatching {
            val status = ShizukuRepository.getStatus(this)
            if (!status.installed || !status.binderAlive) {
                ShizukuReadyState(status, connectionOwnerAlive = false, adControlAlive = false)
            } else {
                warmShizukuServicesBlocking()
                ShizukuReadyState(
                    status = ShizukuRepository.getStatus(this),
                    connectionOwnerAlive = runCatching { ShizukuConnectionOwnerRepository.isServiceAlive() }.getOrDefault(false),
                    adControlAlive = runCatching { ShizukuAdControlRepository.checkServiceHealth(this) }.getOrDefault(false)
                )
            }
        }.getOrElse {
            ShizukuReadyState(
                status = ShizukuRepository.getStatus(this),
                connectionOwnerAlive = false,
                adControlAlive = false
            )
        }
        when {
            !readyState.status.installed -> {
                showShizukuGuideDialog(
                    title = "需要先安装 Shizuku",
                    message = "Shizuku 增强模式需要先安装并启动 Shizuku。安装完成后，再回到寒枫进行授权。",
                    positiveLabel = "前往下载"
                ) {
                    ShizukuRepository.openDownloadPage(this)
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
                warmShizukuServicesBlocking()
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

    protected fun handleShizukuPermissionResult(granted: Boolean, onStateChanged: () -> Unit = {}) {
        if (granted) {
            warmShizukuServicesBlocking()
        }
        val message = if (granted) "Shizuku 授权成功" else "Shizuku 授权失败"
        showShortToast(message)
        onStateChanged()
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
