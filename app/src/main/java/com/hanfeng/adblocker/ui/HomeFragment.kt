package com.HanFeng.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.service.AdBlockVpnService

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusRefreshRunnable = Runnable { updateAllStatus() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = requireActivity() as MainActivity
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
            updateAllStatus()
            uiHandler.postDelayed(statusRefreshRunnable, 300)
            uiHandler.postDelayed(statusRefreshRunnable, 1000)
            uiHandler.postDelayed(statusRefreshRunnable, 2200)
        }
        view.findViewById<Button>(R.id.btnGuide).setOnClickListener { activity.showGuideDialog() }
        view.findViewById<Button>(R.id.btnWhitelist).setOnClickListener { activity.openWhitelist() }
        view.findViewById<Button>(R.id.btnCoexist).setOnClickListener { activity.openCoexistApps() }
        view.findViewById<ImageView>(R.id.btnSettings).setOnClickListener { activity.openSettings() }
        view.findViewById<TextView>(R.id.textVersion)?.text = "v${BuildConfig.VERSION_NAME}"
        updateAllStatus()
        view.findViewById<View>(R.id.homeButtons).apply {
            post {
                val params = layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params.verticalBias = 0.53f
                layoutParams = params
            }
        }
        view.findViewById<TextView>(R.id.textHomeStatus)?.let(::updateStatusText)
        attachHttpDecryptSwitchListener()
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    override fun onDestroyView() {
        view?.findViewById<Switch>(R.id.switchHttpDecrypt)?.setOnCheckedChangeListener(null)
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
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
        toggle.text = when {
            AdBlockVpnService.isRunning -> "停止拦截"
            revokedByOtherVpn && enabled -> "VPN共存中"
            else -> "开启拦截"
        }
    }

    private fun updateStatusText(statusText: TextView) {
        val ctx = context ?: return
        val vpnRunning = AdBlockVpnService.isRunning
        val adBlockEnabled = FeatureSettingsRepository.isAdBlockEnabled(ctx)
        val revokedByOtherVpn = FeatureSettingsRepository.isVpnRevokedByOtherVpn(ctx)
        val httpDecryptEnabled = FeatureSettingsRepository.isHttpDecryptEnabled(ctx)
        val certificateInstalled = HttpsMitmRepository.isCertificateInstalled(ctx)
        val shizukuStatus = ShizukuRepository.getStatus(ctx)
        val shizukuEnabled = com.HanFeng.data.AppSettingsRepository.isShizukuEnabled(ctx)
        val shizukuServiceReady = if (shizukuEnabled && shizukuStatus.installed && shizukuStatus.binderAlive && shizukuStatus.permissionGranted) {
            ShizukuAdControlRepository.checkServiceHealth(ctx)
        } else {
            false
        }
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
            !shizukuStatus.installed -> "未安装"
            !shizukuStatus.binderAlive -> "未启动"
            !shizukuStatus.permissionGranted -> "未授权"
            shizukuServiceReady -> "已连接 (${shizukuStatus.runningMode})"
            else -> "服务待绑定 (${shizukuStatus.runningMode})"
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
                FeatureSettingsRepository.setHttpDecryptEnabled(ctx, isChecked)
                activity.onHttpDecryptSettingChanged(isChecked) { success ->
                    val currentView = view ?: return@onHttpDecryptSettingChanged
                    if (!isAdded) return@onHttpDecryptSettingChanged
                    if (!success) {
                        FeatureSettingsRepository.setHttpDecryptEnabled(ctx, false)
                        httpDecryptSwitch.setOnCheckedChangeListener(null)
                        httpDecryptSwitch.isChecked = false
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
}
