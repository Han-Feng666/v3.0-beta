package com.HanFeng.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.HanFeng.BuildConfig
import com.HanFeng.R
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusRefreshRunnable = Runnable { updateAllStatus() }
    private var cachedShizukuUiState: CachedShizukuUiState? = null
    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAdded || view == null) return
            if (intent?.action == NetworkKernel.statusChangedAction) {
                // vpn 状态变更可能频繁触发（reload/revoke/reacquire/wakelock 事件），但 UI 200ms 内不需要看多次
                // 这里 debounce 200ms 合并多次广播为一次刷新，避免主线程高频跑 SP read + 跨 binder
                uiHandler.removeCallbacks(statusRefreshRunnable)
                uiHandler.postDelayed(statusRefreshRunnable, 200L)
            }
        }
    }
    private var vpnStatusReceiverRegistered = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = activity as? MainActivity ?: return
        applyBackgroundImage(view.findViewById(R.id.homeBackground))
        val homeContent = view.findViewById<View>(R.id.homeContent)
        val toggle = view.findViewById<Button>(R.id.btnToggle)
        val initialTopPadding = homeContent.paddingTop
        val initialBottomPadding = homeContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(homeContent) { content, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(
                content.paddingLeft,
                initialTopPadding + systemBars.top,
                content.paddingRight,
                initialBottomPadding + systemBars.bottom
            )
            insets
        }
        toggle.setOnClickListener {
            activity.requestToggleVpn()
            uiHandler.removeCallbacks(statusRefreshRunnable)
            updateAllStatus()
        }
        view.findViewById<Button>(R.id.btnGuide).setOnClickListener { activity.showGuideDialog() }
        view.findViewById<Button>(R.id.btnWhitelist).setOnClickListener { activity.openWhitelist() }
        view.findViewById<ImageView>(R.id.btnSettings).setOnClickListener { activity.openSettings() }
        view.findViewById<TextView>(R.id.textVersion)?.text = "v${BuildConfig.VERSION_NAME}"
        updateAllStatus()
        view.findViewById<View>(R.id.homeButtons).apply {
            post {
                val params = layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@post
                params.verticalBias = 0.53f
                layoutParams = params
            }
        }
        view.findViewById<TextView>(R.id.textHomeStatus)?.let(::updateStatusText)
        attachHttpDecryptSwitchListener()
    }

    override fun onResume() {
        super.onResume()
        registerVpnStatusReceiverIfNeeded()
        view?.findViewById<ImageView>(R.id.homeBackground)?.let(::applyBackgroundImage)
        updateAllStatus()
    }

    override fun onPause() {
        unregisterVpnStatusReceiver()
        super.onPause()
    }

    override fun onDestroyView() {
        view?.findViewById<Switch>(R.id.switchHttpDecrypt)?.setOnCheckedChangeListener(null)
        unregisterVpnStatusReceiver()
        uiHandler.removeCallbacksAndMessages(null)
        cachedShizukuUiState = null
        super.onDestroyView()
    }

    private fun registerVpnStatusReceiverIfNeeded() {
        val ctx = context ?: return
        if (vpnStatusReceiverRegistered) return
        val filter = IntentFilter(NetworkKernel.statusChangedAction)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(vpnStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(vpnStatusReceiver, filter)
        }
        vpnStatusReceiverRegistered = true
    }

    private fun unregisterVpnStatusReceiver() {
        val ctx = context ?: return
        if (!vpnStatusReceiverRegistered) return
        runCatching { ctx.unregisterReceiver(vpnStatusReceiver) }
        vpnStatusReceiverRegistered = false
    }

    private fun updateAllStatus() {
        view?.findViewById<Button>(R.id.btnToggle)?.let(::updateToggleText)
        view?.findViewById<TextView>(R.id.textHomeStatus)?.let(::updateStatusText)
    }

    fun refreshStatusFromHost() {
        updateAllStatus()
    }

    private fun updateToggleText(toggle: Button) {
        val ctx = context ?: return
        val enabled = FeatureSettingsRepository.isAdBlockEnabled(ctx)
        val revokedByOtherVpn = FeatureSettingsRepository.isVpnRevokedByOtherVpn(ctx)
        val runtime = NetworkKernel.snapshot(ctx)
        toggle.text = when {
            runtime.isRunning -> "停止拦截"
            revokedByOtherVpn && enabled -> "等待恢复"
            enabled -> "正在开启"
            else -> "开启拦截"
        }
    }

    private fun updateStatusText(statusText: TextView) {
        val ctx = context ?: return
        val runtime = NetworkKernel.snapshot(ctx)
        val vpnRunning = runtime.isRunning
        val adBlockEnabled = runtime.adBlockEnabled
        val revokedByOtherVpn = runtime.revokedByOtherVpn
        val httpDecryptEnabled = FeatureSettingsRepository.isHttpDecryptEnabled(ctx)
        val certificateInstalled = HttpsMitmRepository.isCertificateInstalled(ctx)
        val shizukuEnabled = com.HanFeng.data.AppSettingsRepository.isShizukuEnabled(ctx)
        val shizukuUiState = resolveShizukuUiState(ctx, shizukuEnabled)
        val shizukuMode = shizukuUiState.mode
        val workStatus = when {
            vpnRunning -> "运行中"
            revokedByOtherVpn && adBlockEnabled -> "等待恢复"
            adBlockEnabled -> "启动中"
            else -> "未开启"
        }
        val interceptMode = when {
            revokedByOtherVpn -> "VPN 被系统替换，正在自动恢复"
            !vpnRunning && adBlockEnabled -> "正在建立 VPN"
            !vpnRunning && !adBlockEnabled -> "未启用"
            httpDecryptEnabled && certificateInstalled -> "MITM+DNS 拦截"
            httpDecryptEnabled -> "DNS 拦截 (待装证书)"
            else -> "DNS 拦截"
        }
        val certificateStatus = when {
            certificateInstalled -> "已安装"
            httpDecryptEnabled -> "未安装，需手动安装 HanFeng.crt (MITM)"
            else -> "当前未启用 MITM 模式"
        }
        val shizukuText = when {
            !shizukuEnabled -> "未启用"
            !shizukuUiState.installed -> "未安装"
            !shizukuUiState.binderAlive -> "未启动"
            shizukuUiState.serviceReady && !shizukuUiState.permissionGranted -> "已连接 (${shizukuMode} / 兼容模式)"
            shizukuUiState.serviceReady -> "已连接 (${shizukuMode})"
            !shizukuUiState.permissionStateKnown -> "权限状态异常"
            shizukuUiState.permissionGranted -> "已授权 (${shizukuMode}，服务连接中)"
            else -> "服务连接中 (${shizukuMode})"
        }
        statusText.text = buildString {
            append("工作状态：")
            append(workStatus)
            append('\n')
            append("拦截模式：")
            append(interceptMode)
            append('\n')
            append("证书状态：")
            append(certificateStatus)
            append('\n')
            append("Shizuku：")
            append(shizukuText)
        }
    }

    private fun attachHttpDecryptSwitchListener() {
        view?.findViewById<Switch>(R.id.switchHttpDecrypt)?.let { httpDecryptSwitch ->
            val ctx = context ?: return@let
            httpDecryptSwitch.isChecked = FeatureSettingsRepository.isHttpDecryptEnabled(ctx)
            httpDecryptSwitch.setOnCheckedChangeListener { _, isChecked ->
                val activity = activity as? MainActivity ?: return@setOnCheckedChangeListener
                val previousChecked = !isChecked
                FeatureSettingsRepository.setHttpDecryptEnabled(ctx, isChecked)
                activity.onHttpDecryptSettingChanged(isChecked) { success ->
                    val currentView = view ?: return@onHttpDecryptSettingChanged
                    if (!isAdded) return@onHttpDecryptSettingChanged
                    if (!success) {
                        FeatureSettingsRepository.setHttpDecryptEnabled(ctx, previousChecked)
                        httpDecryptSwitch.setOnCheckedChangeListener(null)
                        httpDecryptSwitch.isChecked = previousChecked
                        attachHttpDecryptSwitchListener()
                        currentView.findViewById<TextView>(R.id.textHomeStatus)?.let(::updateStatusText)
                        return@onHttpDecryptSettingChanged
                    }
                    val message = if (isChecked) {
                        null
                    } else {
                        "MITM 模式已关闭"
                    }
                    currentView.findViewById<TextView>(R.id.textHomeStatus)?.let(::updateStatusText)
                    message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun resolveShizukuUiState(context: Context, shizukuEnabled: Boolean): CachedShizukuUiState {
        val now = System.currentTimeMillis()
        cachedShizukuUiState?.takeIf {
            it.enabled == shizukuEnabled && now - it.cachedAt <= SHIZUKU_STATUS_CACHE_MILLIS
        }?.let { return it }

        // 缓存为空或已过期：先返回一个"加载中"占位状态，避免主线程阻塞
        val placeholder = cachedShizukuUiState?.copy(
            enabled = shizukuEnabled,
            cachedAt = now
        ) ?: CachedShizukuUiState(
            enabled = shizukuEnabled,
            installed = false,
            binderAlive = false,
            permissionGranted = false,
            permissionStateKnown = false,
            serviceReady = false,
            ready = false,
            mode = "查询中",
            cachedAt = now
        )
        cachedShizukuUiState = placeholder

        // 后台异步刷新真实状态，完成后重新刷新 UI
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val status = ShizukuRepository.getStatus(context)
            val serviceReady = if (shizukuEnabled && status.installed && status.binderAlive) {
                runCatching { ShizukuAdControlRepository.isServiceAlive() }.getOrDefault(false)
            } else {
                false
            }
            val mode = if (serviceReady && !status.permissionGranted) "UserService" else status.runningMode
            val fresh = CachedShizukuUiState(
                enabled = shizukuEnabled,
                installed = status.installed,
                binderAlive = status.binderAlive,
                permissionGranted = status.permissionGranted,
                permissionStateKnown = status.permissionStateKnown,
                serviceReady = serviceReady,
                ready = shizukuEnabled && status.installed && status.binderAlive && (status.permissionGranted || serviceReady),
                mode = mode,
                cachedAt = System.currentTimeMillis()
            )
            cachedShizukuUiState = fresh
            withContext(Dispatchers.Main) {
                if (isAdded && view != null) updateAllStatus()
            }
        }

        return placeholder
    }

    private data class CachedShizukuUiState(
        val enabled: Boolean,
        val installed: Boolean,
        val binderAlive: Boolean,
        val permissionGranted: Boolean,
        val permissionStateKnown: Boolean,
        val serviceReady: Boolean,
        val ready: Boolean,
        val mode: String,
        val cachedAt: Long
    )

    companion object {
        private const val SHIZUKU_STATUS_CACHE_MILLIS = 5_000L
    }

    private fun applyBackgroundImage(imageView: ImageView) {
        val ctx = imageView.context.applicationContext
        val customPath = FeatureSettingsRepository.getCustomBackgroundPath(ctx)
        if (!customPath.isNullOrEmpty()) {
            imageView.applyCustomFileBackground(customPath)
        } else {
            imageView.applyCustomAssetBackground("custom/background")
        }
    }
}
