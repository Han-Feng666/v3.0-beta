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
import com.HanFeng.BuildConfig
import com.HanFeng.R
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuRepository

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusRefreshRunnable = Runnable { updateAllStatus() }
    private var cachedShizukuUiState: CachedShizukuUiState? = null
    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NetworkKernel.statusChangedAction) {
                updateAllStatus()
            }
        }
    }
    private var vpnStatusReceiverRegistered = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = activity as? MainActivity ?: return
        view.findViewById<ImageView>(R.id.homeBackground).applyCustomAssetBackground("custom/home_background")
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
            revokedByOtherVpn && enabled -> "VPN共存中"
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
            revokedByOtherVpn && adBlockEnabled -> "VPN共存中"
            else -> "未开启"
        }
        val interceptMode = when {
            revokedByOtherVpn -> "当前处于 VPN 共存中"
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
        val status = ShizukuRepository.getStatus(context)
        val serviceReady = if (shizukuEnabled && status.installed && status.binderAlive) {
            ShizukuAdControlRepository.isServiceAlive()
        } else {
            false
        }
        val mode = if (serviceReady && !status.permissionGranted) "UserService" else status.runningMode
        return CachedShizukuUiState(
            enabled = shizukuEnabled,
            installed = status.installed,
            binderAlive = status.binderAlive,
            permissionGranted = status.permissionGranted,
            permissionStateKnown = status.permissionStateKnown,
            serviceReady = serviceReady,
            ready = shizukuEnabled && status.installed && status.binderAlive && (status.permissionGranted || serviceReady),
            mode = mode,
            cachedAt = now
        ).also { cachedShizukuUiState = it }
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
}
