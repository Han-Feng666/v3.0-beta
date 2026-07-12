package com.HanFeng.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.service.AdBlockVpnService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.core.network.RegexCache
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepositoryExport
import com.HanFeng.data.ShizukuAdControlCatalog
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuConnectionOwnerRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.security.CertificateAuthorityManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class SettingsActivity : BaseActivity() {

    private lateinit var switchShizuku: Switch
    private lateinit var switchHideBackground: Switch
    private lateinit var switchStealthMode: Switch
    private lateinit var textStealthDesc: TextView
    private lateinit var stealthSubLayout: LinearLayout
    private lateinit var switchStealthStripParams: Switch
    private lateinit var switchStealthHideReferer: Switch
    private lateinit var switchStealthRemoveFingerprintHeaders: Switch
    private lateinit var switchAdFreeReward: Switch
    private lateinit var textAdFreeRewardDesc: TextView
    private lateinit var btnManageCustomTrackingParams: Button
    private lateinit var textCustomTrackingParamsPreview: TextView
    private lateinit var btnManageCustomTrackingHeaders: Button
    private lateinit var textCustomTrackingHeadersPreview: TextView
    private lateinit var textShizukuStatus: TextView
    private lateinit var btnShizukuAdControl: Button
    private lateinit var btnAppFreeze: Button
    private lateinit var btnGameAntiMark: Button
    private lateinit var btnPerformanceMonitor: Button
    private lateinit var btnCoexistSettings: Button
    private lateinit var btnTrafficCardSettings: Button
    private lateinit var btnJoinGroupSettings: Button
    private lateinit var btnResetHideBackground: Button
    private lateinit var btnExportLogs: Button
    private lateinit var btnExportRules: Button
    private lateinit var btnRewardDeveloper: Button
    private lateinit var btnExportCertificate: Button
    private lateinit var btnInstallSystemCert: Button
    private lateinit var btnModifyDeviceId: Button
    private lateinit var btnModifySerial: Button
    private lateinit var btnBack: Button
    private lateinit var settingsRoot: View
    private var cachedShizukuServiceHealthy = false
    private var lastShizukuServiceHealthCheckAt = 0L
    private var shizukuStatusRefreshJob: Job? = null
    
    private lateinit var btnHostsEditor: Button
    private lateinit var btnNetworkPermission: Button
    private lateinit var btnBackgroundRestrict: Button
    private lateinit var btnRootScript: Button
    private lateinit var btnRootHide: Button

    private lateinit var switchHotspotBlock: Switch
    private lateinit var hotspotModeLayout: LinearLayout
    private lateinit var rgHotspotMode: RadioGroup
    private lateinit var tvHotspotStatus: TextView
    
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != ShizukuRepository.REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if (granted) {
            prewarmShizukuIfPossible()
        }
        showShortToast(if (granted) "Shizuku 授权成功" else "Shizuku 授权失败")
        updateShizukuActionState()
    }

    private val certificateExportPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            exportCertificateToUser()
        } else {
            showShortToast("需要存储权限以导出证书")
        }
    }

    private val ruleExportPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            exportRulesToUser()
        } else {
            showShortToast("需要存储权限以导出规则")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        settingsRoot = findViewById(R.id.settingsRoot)
        switchShizuku = findViewById(R.id.switchUseShizuku)
        switchHideBackground = findViewById(R.id.switchHideBackground)
        switchStealthMode = findViewById(R.id.switchStealthMode)
        textStealthDesc = findViewById(R.id.textStealthDesc)
        stealthSubLayout = findViewById(R.id.stealthSubLayout)
        switchStealthStripParams = findViewById(R.id.switchStealthStripParams)
        switchStealthHideReferer = findViewById(R.id.switchStealthHideReferer)
        switchStealthRemoveFingerprintHeaders = findViewById(R.id.switchStealthRemoveFingerprintHeaders)
        switchAdFreeReward = findViewById(R.id.switchAdFreeReward)
        textAdFreeRewardDesc = findViewById(R.id.textAdFreeRewardDesc)
        btnManageCustomTrackingParams = findViewById(R.id.btnManageCustomTrackingParams)
        textCustomTrackingParamsPreview = findViewById(R.id.textCustomTrackingParamsPreview)
        btnManageCustomTrackingHeaders = findViewById(R.id.btnManageCustomTrackingHeaders)
        textCustomTrackingHeadersPreview = findViewById(R.id.textCustomTrackingHeadersPreview)
        textShizukuStatus = findViewById(R.id.textShizukuStatus)
        btnShizukuAdControl = findViewById(R.id.btnShizukuAdControl)
        btnAppFreeze = findViewById(R.id.btnAppFreeze)
        btnGameAntiMark = findViewById(R.id.btnGameAntiMark)
        btnPerformanceMonitor = findViewById(R.id.btnPerformanceMonitor)
        btnCoexistSettings = findViewById(R.id.btnCoexistSettings)
        btnTrafficCardSettings = findViewById(R.id.btnTrafficCardSettings)
        btnJoinGroupSettings = findViewById(R.id.btnJoinGroupSettings)
        btnResetHideBackground = findViewById(R.id.btnResetHideBackground)
        btnExportLogs = findViewById(R.id.btnExportLogs)
        btnExportRules = findViewById(R.id.btnExportRules)
        btnRewardDeveloper = findViewById(R.id.btnRewardDeveloper)
        btnExportCertificate = findViewById(R.id.btnExportCertificate)
        btnInstallSystemCert = findViewById(R.id.btnInstallSystemCert)
        btnModifyDeviceId = findViewById(R.id.btnModifyDeviceId)
        btnModifySerial = findViewById(R.id.btnModifySerial)
        btnBack = findViewById(R.id.btnBack)
        
        // Shizuku 增强
        btnHostsEditor = findViewById(R.id.btnHostsEditor)
        btnNetworkPermission = findViewById(R.id.btnNetworkPermission)
        btnBackgroundRestrict = findViewById(R.id.btnBackgroundRestrict)
        btnRootScript = findViewById(R.id.btnRootScript)
        btnRootHide = findViewById(R.id.btnRootHide)

        switchHotspotBlock = findViewById(R.id.switchHotspotBlock)
        hotspotModeLayout = findViewById(R.id.hotspotModeLayout)
        rgHotspotMode = findViewById(R.id.rgHotspotMode)
        tvHotspotStatus = findViewById(R.id.tvHotspotStatus)

        val initialTopPadding = settingsRoot.paddingTop
        val initialBottomPadding = settingsRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(settingsRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, initialTopPadding + systemBars.top, view.paddingRight, initialBottomPadding + systemBars.bottom)
            insets
        }

        switchShizuku.isChecked = AppSettingsRepository.isShizukuEnabled(this)
        switchHideBackground.isChecked = AppSettingsRepository.isHideBackgroundEnabled(this)
        switchStealthMode.isChecked = FeatureSettingsRepository.isStealthModeEnabled(this)
        stealthSubLayout.visibility = if (switchStealthMode.isChecked) View.VISIBLE else View.GONE
        switchStealthStripParams.isChecked = FeatureSettingsRepository.isStealthStripTrackingParamsEnabled(this)
        switchStealthHideReferer.isChecked = FeatureSettingsRepository.isStealthHideRefererEnabled(this)
        switchStealthRemoveFingerprintHeaders.isChecked = FeatureSettingsRepository.isStealthRemoveFingerprintHeadersEnabled(this)
        switchAdFreeReward.isChecked = FeatureSettingsRepository.isAdFreeRewardEnabled(this)
        updateShizukuActionState()

        switchShizuku.setOnCheckedChangeListener { _, isChecked ->
            AppSettingsRepository.setShizukuEnabled(this, isChecked)
            if (isChecked) {
                handleShizukuEnableRequested(fromUser = true)
            }
            updateShizukuActionState()
        }

        switchHideBackground.setOnCheckedChangeListener { _, isChecked ->
            AppSettingsRepository.setHideBackgroundEnabled(this, isChecked)
            applyHideBackgroundPolicy(isChecked)
            btnResetHideBackground.visibility = View.VISIBLE
            if (isChecked) showShortToast("返回桌面后将自动移除最近任务卡片")
        }
        btnResetHideBackground.setOnClickListener {
            AppSettingsRepository.resetHideBackground(this)
            switchHideBackground.setOnCheckedChangeListener(null)
            switchHideBackground.isChecked = false
            switchHideBackground.setOnCheckedChangeListener { _, isChecked ->
                AppSettingsRepository.setHideBackgroundEnabled(this, isChecked)
                applyHideBackgroundPolicy(isChecked)
                if (isChecked) showShortToast("返回桌面后将自动移除最近任务卡片")
            }
            btnResetHideBackground.visibility = View.GONE
            applyHideBackgroundPolicy(false)
            showShortToast("隐藏后台设置已重置")
        }
        switchStealthMode.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setStealthModeEnabled(this, isChecked)
            stealthSubLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        switchStealthStripParams.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setStealthStripTrackingParamsEnabled(this, isChecked)
        }
        switchStealthHideReferer.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setStealthHideRefererEnabled(this, isChecked)
        }
        switchStealthRemoveFingerprintHeaders.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setStealthRemoveFingerprintHeadersEnabled(this, isChecked)
        }
        switchAdFreeReward.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setAdFreeRewardEnabled(this, isChecked)
        }
        btnManageCustomTrackingParams.setOnClickListener {
            showCustomParamsDialog()
        }
        btnManageCustomTrackingHeaders.setOnClickListener {
            showCustomHeadersDialog()
        }
        refreshCustomTrackingPreviews()
        btnShizukuAdControl.setOnClickListener {
            openShizukuAdControlCatalog()
        }
        btnAppFreeze.setOnClickListener {
            launchActivitySafely(
                AppFreezeActivity.createIntent(this),
                failureMessage = "打开应用冻结失败"
            )
        }
        btnGameAntiMark.setOnClickListener {
            launchActivitySafely(
                GameAntiMarkActivity.createIntent(this),
                failureMessage = "打开腾讯游戏防标记失败"
            )
        }
        btnPerformanceMonitor.setOnClickListener {
            launchActivitySafely(
                PerformanceMonitorActivity.createIntent(this),
                failureMessage = "打开性能占用监控失败"
            )
        }

        val hotspotEnabled = FeatureSettingsRepository.isHotspotBlockEnabled(this)
        switchHotspotBlock.isChecked = hotspotEnabled
        hotspotModeLayout.visibility = if (hotspotEnabled) View.VISIBLE else View.GONE
        when (FeatureSettingsRepository.getHotspotBlockMode(this)) {
            "dns" -> rgHotspotMode.check(R.id.rbHotspotDns)
            else -> rgHotspotMode.check(R.id.rbHotspotVpn)
        }
        switchHotspotBlock.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setHotspotBlockEnabled(this, isChecked)
            hotspotModeLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            sendBroadcast(Intent(AdBlockVpnService.ACTION_RELOAD).setPackage(packageName))
        }
        rgHotspotMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbHotspotDns) "dns" else "vpn"
            FeatureSettingsRepository.setHotspotBlockMode(this, mode)
            if (mode == "dns") {
                tvHotspotStatus.visibility = View.VISIBLE
                tvHotspotStatus.text = "DNS 劫持模式需要 Root 权限及 dnsmasq，请确保设备已 Root"
            } else {
                tvHotspotStatus.visibility = View.GONE
            }
            sendBroadcast(Intent(AdBlockVpnService.ACTION_RELOAD).setPackage(packageName))
        }
        btnCoexistSettings.setOnClickListener {
            launchActivitySafely(
                Intent(this, WhitelistActivity::class.java).putExtra(
                    WhitelistActivity.EXTRA_MODE,
                    WhitelistActivity.MODE_COEXIST
                ),
                failureMessage = "打开共存设置失败"
            )
        }
        btnTrafficCardSettings.setOnClickListener {
            openJoinGroupPage()
        }
        btnJoinGroupSettings.setOnClickListener {
            openJoinGroupPage()
        }
        btnExportLogs.setOnClickListener {
            exportLogsToUser()
        }
        btnExportRules.setOnClickListener {
            exportRulesToUser()
        }
        btnRewardDeveloper.setOnClickListener {
            launchActivitySafely(
                RewardActivity.createIntent(this),
                failureMessage = "打开赞赏页失败"
            )
        }
        btnExportCertificate.setOnClickListener {
            exportCertificateToUser()
        }
        btnInstallSystemCert.setOnClickListener {
            installCertificateToSystem()
        }
        btnModifyDeviceId.setOnClickListener {
            showModifyDeviceIdDialog()
        }
        btnModifySerial.setOnClickListener {
            showModifySerialDialog()
        }
        refreshSystemCertStatus()
        
        // Shizuku 增强功能按钮
        btnHostsEditor.setOnClickListener {
            if (!Shizuku.pingBinder()) {
                showShortToast("请先授权 Shizuku")
                return@setOnClickListener
            }
            launchActivitySafely(Intent(this, HostsEditorActivity::class.java), failureMessage = "打开 Hosts 编辑失败")
        }
        
        btnNetworkPermission.setOnClickListener {
            if (!Shizuku.pingBinder()) {
                showShortToast("请先授权 Shizuku")
                return@setOnClickListener
            }
            launchActivitySafely(
                Intent(this, ShizukuEnhanceAppsActivity::class.java)
                    .putExtra(ShizukuEnhanceAppsActivity.EXTRA_MODE, ShizukuEnhanceAppsActivity.MODE_NETWORK),
                failureMessage = "打开网络权限管理失败"
            )
        }
        
        btnBackgroundRestrict.setOnClickListener {
            if (!Shizuku.pingBinder()) {
                showShortToast("请先授权 Shizuku")
                return@setOnClickListener
            }
            launchActivitySafely(
                Intent(this, ShizukuEnhanceAppsActivity::class.java)
                    .putExtra(ShizukuEnhanceAppsActivity.EXTRA_MODE, ShizukuEnhanceAppsActivity.MODE_BACKGROUND),
                failureMessage = "打开后台限制管理失败"
            )
        }

        btnRootScript.setOnClickListener {
            launchActivitySafely(Intent(this, RootScriptActivity::class.java), failureMessage = "打开 Root 脚本执行失败")
        }

        btnRootHide.setOnClickListener {
            launchActivitySafely(Intent(this, RootHideActivity::class.java), failureMessage = "打开 Root 隐藏失败")
        }
        
        textShizukuStatus.setOnClickListener {
            if (AppSettingsRepository.isShizukuEnabled(this)) {
                handleShizukuEnableRequested(fromUser = false)
            } else {
                StableDialog.builder(this)
                    .setTitle("Shizuku 未启用")
                    .setMessage("先打开“使用 Shizuku 增强”，再继续安装、启动或授权。")
                    .setPositiveButton("我知道了", null)
                    .showSafely(this, "Show shizuku disabled dialog failed")
            }
        }
        btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        syncShizukuSwitch()
        syncHideBackgroundSwitch()
        syncStealthModeSwitch()
        syncAdFreeRewardSwitch()
        updateShizukuActionState()
        refreshShizukuActionStateAsync(force = false)
        prewarmShizukuIfPossible()
    }

    override fun onDestroy() {
        shizukuStatusRefreshJob?.cancel()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun syncShizukuSwitch() {
        val enabled = AppSettingsRepository.isShizukuEnabled(this)
        if (switchShizuku.isChecked == enabled) return
        switchShizuku.setOnCheckedChangeListener(null)
        switchShizuku.isChecked = enabled
        switchShizuku.setOnCheckedChangeListener { _, isChecked ->
            AppSettingsRepository.setShizukuEnabled(this, isChecked)
            if (isChecked) {
                handleShizukuEnableRequested(fromUser = true)
            }
            updateShizukuActionState()
        }
    }

    private fun handleShizukuEnableRequested(fromUser: Boolean) {
        val status = ShizukuRepository.getStatus(this)
        val serviceHealthy = status.installed && status.binderAlive && cachedShizukuServiceHealthy
        when {
            !status.installed -> {
                StableDialog.builder(this)
                    .setTitle("需要先安装 Shizuku")
                    .setMessage("系统推广治理和连接增强依赖 Shizuku。安装完成后回到这里即可继续。")
                    .setPositiveButton("前往下载") { _, _ -> ShizukuRepository.openDownloadPage(this) }
                    .setNegativeButton("取消", null)
                    .showSafely(this, "Show install shizuku dialog failed")
            }
            !status.binderAlive -> {
                StableDialog.builder(this)
                    .setTitle("需要先启动 Shizuku")
                    .setMessage("请先在 Shizuku App 里启动服务。完成后回到设置页，状态会自动刷新。")
                    .setPositiveButton("我知道了", null)
                    .showSafely(this, "Show start shizuku dialog failed")
            }
            serviceHealthy && !status.permissionGranted -> {
                if (fromUser) {
                    showShortToast("Shizuku 已通过兼容模式连接")
                }
            }
            status.permissionGranted -> {
                refreshShizukuActionStateAsync(force = true)
                prewarmShizukuIfPossible()
                if (fromUser) {
                    showShortToast("Shizuku 已连接")
                }
            }
            ShizukuRepository.requestPermission() -> {
                showShortToast("正在请求 Shizuku 授权")
            }
            else -> {
                StableDialog.builder(this)
                    .setTitle("Shizuku 需要授权")
                    .setMessage("请确认 Shizuku 已运行，并在授权界面里允许寒枫访问。若之前拒绝过，请到 Shizuku 中清理授权状态后重试。")
                    .setPositiveButton("我知道了", null)
                    .showSafely(this, "Show shizuku permission dialog failed")
            }
        }
        updateShizukuActionState()
    }

    private fun syncHideBackgroundSwitch() {
        val enabled = AppSettingsRepository.isHideBackgroundEnabled(this)
        if (switchHideBackground.isChecked == enabled) return
        switchHideBackground.setOnCheckedChangeListener(null)
        switchHideBackground.isChecked = enabled
        switchHideBackground.setOnCheckedChangeListener { _, isChecked ->
            AppSettingsRepository.setHideBackgroundEnabled(this, isChecked)
            applyHideBackgroundPolicy(isChecked)
            if (isChecked) showShortToast("返回桌面后将自动移除最近任务卡片")
        }
    }

    private fun syncStealthModeSwitch() {
        val enabled = FeatureSettingsRepository.isStealthModeEnabled(this)
        stealthSubLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        if (switchStealthMode.isChecked == enabled) {
            syncStealthSubSwitches()
            return
        }
        switchStealthMode.setOnCheckedChangeListener(null)
        switchStealthMode.isChecked = enabled
        switchStealthMode.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setStealthModeEnabled(this, isChecked)
            stealthSubLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        syncStealthSubSwitches()
    }

    private fun syncStealthSubSwitches() {
        val stripParamsEnabled = FeatureSettingsRepository.isStealthStripTrackingParamsEnabled(this)
        if (switchStealthStripParams.isChecked != stripParamsEnabled) {
            switchStealthStripParams.setOnCheckedChangeListener(null)
            switchStealthStripParams.isChecked = stripParamsEnabled
            switchStealthStripParams.setOnCheckedChangeListener { _, isChecked ->
                FeatureSettingsRepository.setStealthStripTrackingParamsEnabled(this, isChecked)
            }
        }
        val hideRefererEnabled = FeatureSettingsRepository.isStealthHideRefererEnabled(this)
        if (switchStealthHideReferer.isChecked != hideRefererEnabled) {
            switchStealthHideReferer.setOnCheckedChangeListener(null)
            switchStealthHideReferer.isChecked = hideRefererEnabled
            switchStealthHideReferer.setOnCheckedChangeListener { _, isChecked ->
                FeatureSettingsRepository.setStealthHideRefererEnabled(this, isChecked)
            }
        }
        val removeHeadersEnabled = FeatureSettingsRepository.isStealthRemoveFingerprintHeadersEnabled(this)
        if (switchStealthRemoveFingerprintHeaders.isChecked != removeHeadersEnabled) {
            switchStealthRemoveFingerprintHeaders.setOnCheckedChangeListener(null)
            switchStealthRemoveFingerprintHeaders.isChecked = removeHeadersEnabled
            switchStealthRemoveFingerprintHeaders.setOnCheckedChangeListener { _, isChecked ->
                FeatureSettingsRepository.setStealthRemoveFingerprintHeadersEnabled(this, isChecked)
            }
        }
    }

    private fun syncAdFreeRewardSwitch() {
        val enabled = FeatureSettingsRepository.isAdFreeRewardEnabled(this)
        if (switchAdFreeReward.isChecked == enabled) return
        switchAdFreeReward.setOnCheckedChangeListener(null)
        switchAdFreeReward.isChecked = enabled
        switchAdFreeReward.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setAdFreeRewardEnabled(this, isChecked)
        }
    }

    private fun refreshCustomTrackingPreviews() {
        val params = FeatureSettingsRepository.getCustomTrackingParams(this)
        if (params.isNotEmpty()) {
            textCustomTrackingParamsPreview.visibility = View.VISIBLE
            textCustomTrackingParamsPreview.text = "当前自定义参数：${params.joinToString(", ")}"
        } else {
            textCustomTrackingParamsPreview.visibility = View.GONE
        }
        val headers = FeatureSettingsRepository.getCustomTrackingHeaders(this)
        if (headers.isNotEmpty()) {
            textCustomTrackingHeadersPreview.visibility = View.VISIBLE
            textCustomTrackingHeadersPreview.text = "当前自定义头：${headers.joinToString(", ")}"
        } else {
            textCustomTrackingHeadersPreview.visibility = View.GONE
        }
    }

    private fun showCustomParamsDialog() {
        val currentParams = FeatureSettingsRepository.getCustomTrackingParams(this)
        val items = currentParams.toMutableList()
        val itemsArray = (items + "添加新参数...").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("自定义追踪参数 ($items.size 个)")
            .setItems(itemsArray) { _, which ->
                if (which < items.size) {
                    showEditCustomParamDialog(items[which])
                } else {
                    showAddCustomParamDialog()
                }
            }
            .setNegativeButton("关闭", null)
            .showSafely(this, "Show custom params dialog failed")
    }

    private fun showEditCustomParamDialog(param: String) {
        AlertDialog.Builder(this)
            .setTitle("编辑 / 删除: $param")
            .setItems(arrayOf("删除此参数", "取消")) { _, which ->
                if (which == 0) {
                    FeatureSettingsRepository.removeCustomTrackingParam(this, param)
                    refreshCustomTrackingPreviews()
                    showShortToast("已移除: $param")
                }
            }
            .showSafely(this, "Show edit custom param dialog failed")
    }

    private fun showAddCustomParamDialog() {
        val input = EditText(this).apply {
            hint = "输入追踪参数名，如: track_id"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("添加自定义追踪参数")
            .setView(input)
            .setMessage("参数名仅使用小写字母、数字、下划线和连字符")
            .setPositiveButton("添加") { _, _ ->
                val text = input.text.toString().trim().lowercase()
                if (text.isNotBlank() && text.matches(RegexCache.get("[a-z0-9_-]+"))) {
                    FeatureSettingsRepository.addCustomTrackingParam(this, text)
                    refreshCustomTrackingPreviews()
                    showShortToast("已添加: $text")
                } else {
                    showShortToast("参数名格式无效")
                }
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show add custom param dialog failed")
    }

    private fun showCustomHeadersDialog() {
        val currentHeaders = FeatureSettingsRepository.getCustomTrackingHeaders(this)
        val items = currentHeaders.toMutableList()
        val itemsArray = (items + "添加新头...").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("自定义追踪头 ($items.size 个)")
            .setItems(itemsArray) { _, which ->
                if (which < items.size) {
                    showEditCustomHeaderDialog(items[which])
                } else {
                    showAddCustomHeaderDialog()
                }
            }
            .setNegativeButton("关闭", null)
            .showSafely(this, "Show custom headers dialog failed")
    }

    private fun showEditCustomHeaderDialog(header: String) {
        AlertDialog.Builder(this)
            .setTitle("编辑 / 删除: $header")
            .setItems(arrayOf("删除此头", "取消")) { _, which ->
                if (which == 0) {
                    FeatureSettingsRepository.removeCustomTrackingHeader(this, header)
                    refreshCustomTrackingPreviews()
                    showShortToast("已移除: $header")
                }
            }
            .showSafely(this, "Show edit custom header dialog failed")
    }

    private fun showAddCustomHeaderDialog() {
        val input = EditText(this).apply {
            hint = "输入请求头名，如: x-custom-tracker"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("添加自定义追踪头")
            .setView(input)
            .setMessage("头名仅使用小写字母、数字、连字符")
            .setPositiveButton("添加") { _, _ ->
                val text = input.text.toString().trim().lowercase()
                if (text.isNotBlank() && text.matches(RegexCache.get("[a-z0-9-]+"))) {
                    FeatureSettingsRepository.addCustomTrackingHeader(this, text)
                    refreshCustomTrackingPreviews()
                    showShortToast("已添加: $text")
                } else {
                    showShortToast("头名格式无效")
                }
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show add custom header dialog failed")
    }

    private fun updateShizukuActionState() {
        val shizukuEnabled = AppSettingsRepository.isShizukuEnabled(this)
        val status = if (shizukuEnabled) ShizukuRepository.getStatus(this) else null
        val baseReady = status?.let { it.installed && it.binderAlive } == true
        val serviceHealthy = shizukuEnabled && baseReady && cachedShizukuServiceHealthy
        val shizukuReady = status?.let { it.installed && it.binderAlive && (it.permissionGranted || serviceHealthy) } == true
        btnShizukuAdControl.isEnabled = true
        btnAppFreeze.isEnabled = true
        btnGameAntiMark.isEnabled = true
        btnCoexistSettings.isEnabled = true
        btnTrafficCardSettings.isEnabled = true
        btnJoinGroupSettings.isEnabled = true
        btnShizukuAdControl.alpha = if (shizukuReady) 1f else 0.72f
        btnAppFreeze.alpha = if (shizukuReady) 1f else 0.72f
        btnGameAntiMark.alpha = 1f
        textShizukuStatus.text = buildShizukuStatusText(shizukuEnabled, shizukuReady, serviceHealthy, status)
    }

    private fun refreshShizukuActionStateAsync(force: Boolean) {
        val shizukuEnabled = AppSettingsRepository.isShizukuEnabled(this)
        if (!shizukuEnabled) {
            cachedShizukuServiceHealthy = false
            lastShizukuServiceHealthCheckAt = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastShizukuServiceHealthCheckAt < SHIZUKU_STATUS_REFRESH_INTERVAL_MILLIS) return
        if (shizukuStatusRefreshJob?.isActive == true) return
        lastShizukuServiceHealthCheckAt = now
        shizukuStatusRefreshJob = lifecycleScope.launch {
            val serviceHealthy = withContext(Dispatchers.IO) {
                runCatching { ShizukuAdControlRepository.isServiceAlive() }.getOrDefault(false)
            }
            cachedShizukuServiceHealthy = serviceHealthy
            if (!isFinishing && !isDestroyed) {
                updateShizukuActionState()
            }
        }
    }

    private fun warmShizukuServices(): Boolean {
        return warmShizukuServicesBlocking()
    }

    private fun openJoinGroupPage() {
        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=573309536&card_type=group&source=qrcode")
                )
            )
        }.onFailure {
            LogRepository.append(this, "Show join group failed: ${it.message}")
            showMessageDialog(
                message = "未找到可用的 QQ 客户端，请先安装 QQ",
                errorTag = "Show join group unavailable dialog failed"
            )
        }
    }

    private fun exportLogsToUser() {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { LogRepository.exportZip(applicationContext) }
            }.getOrElse {
                LogRepository.append(this@SettingsActivity, "Export logs failed: ${it.message ?: it.javaClass.simpleName}")
                showShortToast("导出日志失败")
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                startActivity(Intent.createChooser(intent, "导出日志"))
            }.onFailure {
                LogRepository.append(this@SettingsActivity, "Export logs chooser failed: ${it.message}")
                showShortToast("打开日志导出失败")
            }
        }
    }

    private fun exportRulesToUser() {
        if (needsLegacyStoragePermission()) {
            ruleExportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        lifecycleScope.launch {
            btnExportRules.isEnabled = false
            try {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
                    .format(java.util.Date())
                val fileName = "hanfeng_rules_$timestamp.txt"
                val exported = withContext(Dispatchers.IO) {
                    RuleRepositoryExport.buildRulesText(
                        context = applicationContext,
                        includeWhitelist = true,
                        includeSmartScored = true
                    )
                }
                if (exported.count <= 0) {
                    showShortToast("当前没有规则可导出")
                    return@launch
                }
                val exportPath = withContext(Dispatchers.IO) {
                    CertificateAuthorityManager.exportTextFileToDownloads(
                        context = applicationContext,
                        fileName = fileName,
                        content = exported.content
                    )
                }
                if (exportPath != null) {
                    showLongToast("导出成功：${exported.count} 条规则\n文件位置：$exportPath")
                    LogRepository.append(this@SettingsActivity, "规则导出成功：文件=$exportPath, 规则数=${exported.count}")
                } else {
                    showShortToast("导出规则失败")
                    LogRepository.append(this@SettingsActivity, "Export rules failed: path is null")
                }
            } catch (e: Exception) {
                LogRepository.append(this@SettingsActivity, "Export rules failed: ${e.message ?: e.javaClass.simpleName}")
                showShortToast("导出规则失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                btnExportRules.isEnabled = true
            }
        }
    }

    private fun exportCertificateToUser() {
        if (needsLegacyStoragePermission()) {
            certificateExportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    CertificateAuthorityManager.ensureCaInstalledFiles(applicationContext)
                }
                result.onSuccess { cert ->
                    val publicCertFile = java.io.File(cert.filePath)
                    if (!publicCertFile.exists()) {
                        showShortToast("证书文件不存在")
                        return@onSuccess
                    }
                    val exportPath = withContext(Dispatchers.IO) {
                        CertificateAuthorityManager.exportCertificateToDownloads(
                            applicationContext,
                            publicCertFile
                        )
                    }
                    if (exportPath != null) {
                        HttpsMitmRepository.saveCertificateExportPath(this@SettingsActivity, exportPath)
                        showShortToast("证书已导出到：$exportPath")
                        LogRepository.append(
                            this@SettingsActivity,
                            "Certificate exported to: $exportPath"
                        )
                    } else {
                        showShortToast("导出证书失败")
                        LogRepository.append(this@SettingsActivity, "Export certificate failed: path is null")
                    }
                }.onFailure {
                    LogRepository.append(this@SettingsActivity, "Prepare certificate export failed: ${it.message ?: it.javaClass.simpleName}")
                    showShortToast("准备证书导出失败")
                }
            } catch (e: Exception) {
                LogRepository.append(this@SettingsActivity, "Export certificate failed: ${e.message ?: e.javaClass.simpleName}")
                showShortToast("导出证书失败：${e.message}")
            }
        }
    }

    private fun installCertificateToSystem() {
        if (!com.HanFeng.adblocker.shizuku.SystemCertInstaller.isRootAvailable()) {
            StableDialog.builder(this)
                .setTitle("需要 Root 权限")
                .setMessage("安装证书到系统需要 Root 权限，请确保设备已获取 Root。")
                .setPositiveButton("确定", null)
                .showSafely(this, "root-unavailable-cert-install")
            return
        }
        StableDialog.builder(this)
            .setTitle("安装证书到系统")
            .setMessage("将 MITM CA 证书安装到系统信任库。\n\n支持的安装方式：\n- 直接写入 /system（需可读写系统分区）\n- Magisk 模块（需安装 Magisk/KernelSU）\n- bind mount 挂载覆盖\n- Conscrypt 模块（Android 14+）\n\n安装成功后可拦截更多 HTTPS 广告。部分设备需重启后生效。\n\n是否继续？")
            .setPositiveButton("安装") { _, _ -> performSystemCertInstall() }
            .setNegativeButton("取消", null)
            .showSafely(this, "confirm-system-cert-install")
    }

    private fun performSystemCertInstall() {
        lifecycleScope.launch {
            try {
                val installer = com.HanFeng.adblocker.shizuku.SystemCertInstaller(this@SettingsActivity)
                val result = withContext(Dispatchers.Default) { installer.installToSystem() }
                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.HanFeng.adblocker.shizuku.SystemCertInstaller.InstallResult.Success -> {
                            val rebootHint = if (result.needsReboot) "\n\n建议重启设备使证书完全生效。" else "\n\n证书刷新信号已发送，无需重启。"
                            StableDialog.builder(this@SettingsActivity)
                                .setTitle("安装成功")
                                .setMessage("证书已通过 ${result.method} 方式安装到系统。\n证书 hash: ${result.hashName}$rebootHint")
                                .setPositiveButton("确定", null)
                                .showSafely(this@SettingsActivity, "system-cert-install-success")
                            refreshSystemCertStatus()
                        }
                        is com.HanFeng.adblocker.shizuku.SystemCertInstaller.InstallResult.Failure -> {
                            StableDialog.builder(this@SettingsActivity)
                                .setTitle("安装失败")
                                .setMessage("${result.reason}\n\n已尝试: ${result.triedMethods.joinToString(", ")}")
                                .setPositiveButton("确定", null)
                                .showSafely(this@SettingsActivity, "system-cert-install-failed")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("错误")
                        .setMessage("安装过程出错: ${e.message}")
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "system-cert-install-error")
                }
            }
        }
    }

    private fun refreshSystemCertStatus() {
        if (!com.HanFeng.adblocker.shizuku.SystemCertInstaller.isRootAvailable()) {
            btnInstallSystemCert.text = "安装证书到系统 (Root)"
            return
        }
        lifecycleScope.launch {
            val installer = com.HanFeng.adblocker.shizuku.SystemCertInstaller(this@SettingsActivity)
            val status = withContext(Dispatchers.Default) { installer.checkCurrentInstallStatus() }
            withContext(Dispatchers.Main) {
                val text = when (status) {
                    is com.HanFeng.adblocker.shizuku.SystemCertInstaller.CertInstallStatus.INSTALLED ->
                        "证书已安装到系统 (Root)"
                    is com.HanFeng.adblocker.shizuku.SystemCertInstaller.CertInstallStatus.NOT_INSTALLED ->
                        "安装证书到系统 (Root)"
                    is com.HanFeng.adblocker.shizuku.SystemCertInstaller.CertInstallStatus.NOT_GENERATED ->
                        "安装证书到系统 (Root) — 请先生成证书"
                }
                btnInstallSystemCert.text = text
            }
        }
    }

    private fun showModifyDeviceIdDialog() {
        if (!com.HanFeng.adblocker.shizuku.DeviceIdModifier.isRootAvailable()) {
            StableDialog.builder(this)
                .setTitle("需要 Root 权限")
                .setMessage("修改 Android ID 需要 Root 权限。")
                .setPositiveButton("确定", null)
                .showSafely(this, "root-unavailable-device-id")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 8.dp)
        }
        val etInput = EditText(this).apply {
            hint = "正在读取..."
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(getColor(com.HanFeng.R.color.hf_text_primary))
            setHintTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            setBackgroundResource(com.HanFeng.R.drawable.bg_panel)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 0, 0)
        }
        val btnRandom = Button(this).apply {
            text = "随机"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4.dp }
        }
        val btnReset = Button(this).apply {
            text = "恢复默认"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dp }
        }
        btnRow.addView(btnRandom)
        btnRow.addView(btnReset)
        container.addView(etInput)
        container.addView(btnRow)

        val dialog = StableDialog.builder(this)
            .setTitle("修改 Android ID")
            .setMessage("当前 Android ID（16 位十六进制）：")
            .setView(container)
            .setPositiveButton("确认修改") { _, _ ->
                val newId = etInput.text.toString().trim()
                if (newId.isBlank()) {
                    showShortToast("请输入 Android ID")
                    return@setPositiveButton
                }
                executeDeviceIdChange(newId)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        btnRandom.setOnClickListener {
            etInput.setText(com.HanFeng.adblocker.shizuku.DeviceIdModifier.generateRandomAndroidId())
            etInput.setSelection(etInput.text.length)
        }
        btnReset.setOnClickListener {
            etInput.setText("")
            etInput.hint = "正在恢复..."
            Thread {
                val result = com.HanFeng.adblocker.shizuku.DeviceIdModifier().readAndroidId()
                runOnUiThread {
                    val currentId = if (result.exitCode == 0 && result.output.isNotBlank()) result.output.trim() else "(无法读取)"
                    etInput.setText(currentId)
                    etInput.setSelection(etInput.text.length)
                }
            }.start()
        }

        Thread {
            val result = com.HanFeng.adblocker.shizuku.DeviceIdModifier().readAndroidId()
            runOnUiThread {
                val currentId = if (result.exitCode == 0 && result.output.isNotBlank()) result.output.trim() else "(无法读取)"
                etInput.setText(currentId)
                etInput.setSelection(etInput.text.length)
                dialog.setMessage("当前 Android ID（16 位十六进制）：")
            }
        }.start()
    }

    private fun executeDeviceIdChange(newId: String) {
        lifecycleScope.launch {
            val modifier = com.HanFeng.adblocker.shizuku.DeviceIdModifier()
            val result = withContext(Dispatchers.Default) { modifier.writeAndroidId(newId) }
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改成功")
                        .setMessage("Android ID 已修改为:\n$newId\n\n部分应用需清除数据或重启设备才能看到新的 Android ID。")
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "device-id-changed")
                } else {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改失败")
                        .setMessage(result.output.ifBlank { "修改失败 (exit=${result.exitCode})" })
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "device-id-change-failed")
                }
            }
        }
    }

    private fun showModifySerialDialog() {
        if (!com.HanFeng.adblocker.shizuku.DeviceIdModifier.isRootAvailable()) {
            StableDialog.builder(this)
                .setTitle("需要 Root 权限")
                .setMessage("修改主板序列号需要 Root 权限。")
                .setPositiveButton("确定", null)
                .showSafely(this, "root-unavailable-serial")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 8.dp)
        }
        val etInput = EditText(this).apply {
            hint = "正在读取..."
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(getColor(com.HanFeng.R.color.hf_text_primary))
            setHintTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            setBackgroundResource(com.HanFeng.R.drawable.bg_panel)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 0, 0)
        }
        val btnRandom = Button(this).apply {
            text = "随机"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4.dp }
        }
        val btnReset = Button(this).apply {
            text = "恢复默认"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dp }
        }
        btnRow.addView(btnRandom)
        btnRow.addView(btnReset)
        container.addView(etInput)
        container.addView(btnRow)

        val dialog = StableDialog.builder(this)
            .setTitle("修改主板序列号")
            .setMessage("当前主板序列号：")
            .setView(container)
            .setPositiveButton("确认修改") { _, _ ->
                val newSerial = etInput.text.toString().trim()
                if (newSerial.isBlank()) {
                    showShortToast("请输入序列号")
                    return@setPositiveButton
                }
                executeSerialChange(newSerial)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        btnRandom.setOnClickListener {
            etInput.setText(com.HanFeng.adblocker.shizuku.DeviceIdModifier.generateRandomSerial())
            etInput.setSelection(etInput.text.length)
        }
        btnReset.setOnClickListener {
            etInput.setText("")
            etInput.hint = "正在恢复..."
            Thread {
                val result = com.HanFeng.adblocker.shizuku.DeviceIdModifier().readSerialNo()
                runOnUiThread {
                    etInput.setText(result.output.trim())
                    etInput.setSelection(etInput.text.length)
                }
            }.start()
        }

        Thread {
            val result = com.HanFeng.adblocker.shizuku.DeviceIdModifier().readSerialNo()
            runOnUiThread {
                val currentSerial = result.output.trim().ifBlank { "(无法读取)" }
                etInput.setText(currentSerial)
                etInput.setSelection(etInput.text.length)
                dialog.setMessage("当前主板序列号：")
            }
        }.start()
    }

    private fun executeSerialChange(newSerial: String) {
        lifecycleScope.launch {
            val modifier = com.HanFeng.adblocker.shizuku.DeviceIdModifier()
            val result = withContext(Dispatchers.Default) { modifier.writeSerialNo(newSerial) }
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改成功")
                        .setMessage("主板序列号已修改为:\n$newSerial\n\n重启设备后生效。")
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "serial-changed")
                } else {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改失败")
                        .setMessage(result.output.ifBlank { "修改失败 (exit=${result.exitCode})" })
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "serial-change-failed")
                }
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun needsLegacyStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }

    private fun prewarmShizukuIfPossible() {
        val shizukuEnabled = AppSettingsRepository.isShizukuEnabled(this)
        if (!shizukuEnabled) return
        val status = ShizukuRepository.getStatus(this)
        if (!status.installed || !status.binderAlive) return
        if (!status.permissionGranted && !cachedShizukuServiceHealthy) {
            refreshShizukuActionStateAsync(force = false)
            return
        }
        lifecycleScope.launch {
            val warmed = withContext(Dispatchers.IO) {
                warmShizukuServices()
            }
            cachedShizukuServiceHealthy = warmed || cachedShizukuServiceHealthy
            if (!isFinishing && !isDestroyed && warmed) {
                updateShizukuActionState()
            }
        }
    }

    private fun buildShizukuStatusText(
        shizukuEnabled: Boolean,
        shizukuReady: Boolean,
        serviceHealthy: Boolean,
        status: ShizukuRepository.Status? = null
    ): String {
        if (!shizukuEnabled) return "Shizuku 状态：未启用"
        val currentStatus = status ?: ShizukuRepository.getStatus(this)
        val connectedMode = when {
            serviceHealthy && !currentStatus.permissionGranted -> "UserService"
            else -> currentStatus.runningMode
        }
        return when {
            !currentStatus.installed -> "Shizuku 状态：未安装，点击这里可打开下载指引"
            !currentStatus.binderAlive -> "Shizuku 状态：未启动，点击这里可查看启动提示"
            shizukuReady && serviceHealthy && !currentStatus.permissionGranted -> "Shizuku 状态：已连接 (${connectedMode} / 兼容模式)，可直接使用治理功能"
            shizukuReady && serviceHealthy -> "Shizuku 状态：已连接 (${connectedMode})，可直接使用治理功能"
            !currentStatus.permissionStateKnown -> "Shizuku 状态：权限状态异常，点击这里查看处理提示"
            !currentStatus.permissionGranted -> "Shizuku 状态：未授权，点击这里可重新请求授权"
            currentStatus.permissionGranted -> "Shizuku 状态：已授权 (${connectedMode}，服务连接中)"
            else -> "Shizuku 状态：服务连接中 (${connectedMode})"
        }
    }

    private fun openShizukuAdControlCatalog() {
        startActivity(PromoGovernScopeActivity.createIntent(this))
    }

    private fun showPromoGovernTargetList(
        targets: List<PromoGovernTarget>,
        installedPackages: Set<String>
    ) {
        if (targets.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("暂无可治理项目")
                .setMessage("当前没有可展示的治理目标。")
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show empty promo govern dialog failed")
            return
        }
        val labels = buildList {
            add("批量治理当前列表（${targets.size} 项）")
            addAll(buildPromoTargetLabels(targets, installedPackages))
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("推广治理")
            .setMessage("已识别到 ${targets.size} 个治理目标。首项用于批量治理，其余项用于单独治理。")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    openBatchGovernActionsForTargets(targets)
                } else {
                    showPromoTargetActionDialog(targets[which - 1])
                }
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show promo govern target list dialog failed")
    }

    private fun openBatchGovernActionsForTargets(targets: List<PromoGovernTarget>) {
        if (targets.isEmpty()) {
            showOperationResult("当前列表下没有识别到可批量治理的已安装推广项")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("批量治理")
            .setMessage("当前列表内识别到 ${targets.size} 个可批量处理的治理目标。")
            .setItems(arrayOf("智能治理当前列表", "关闭推送广告", "恢复推送广告", "整包冻结", "解冻已冻结项目", "整包暂停", "恢复暂停")) { _, which ->
                when (which) {
                    0 -> runBatchShizukuAdControl(targets, mode = BatchAdControlMode.SMART_GOVERN)
                    1 -> runBatchShizukuAdControl(targets, mode = BatchAdControlMode.BLOCK_NOTIFICATIONS)
                    2 -> runBatchShizukuAdControl(targets, mode = BatchAdControlMode.ALLOW_NOTIFICATIONS)
                    3 -> runBatchShizukuAdControl(targets, mode = BatchAdControlMode.DISABLE)
                    4 -> runBatchShizukuAdControl(targets, mode = BatchAdControlMode.ENABLE)
                    5 -> runBatchShizukuAdControl(targets, mode = BatchAdControlMode.SUSPEND)
                    6 -> runBatchShizukuAdControl(targets, mode = BatchAdControlMode.UNSUSPEND)
                }
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show batch govern actions dialog failed")
    }

    private fun runBatchShizukuAdControl(targets: List<PromoGovernTarget>, mode: BatchAdControlMode) {
        if (targets.isEmpty()) {
            showOperationResult("未发现可处理的已安装推广项")
            return
        }
        val installedPackageTargets = targets.map { target ->
            BatchPackageTarget(
                packageName = target.packageName,
                target = target
            )
        }.sortedWith(
            compareBy<BatchPackageTarget> { it.target.category }
                .thenBy { it.target.title }
                .thenBy { it.packageName }
        )
        val operated = mutableListOf<String>()
        val degradedToSuspend = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val hasHighRiskNotificationApps = targets.any { assessNotificationRisk(it.packageName, it.title) == NotificationRiskLevel.HIGH }
        installedPackageTargets.forEach { target ->
            val packageName = target.packageName
            val displayName = buildBatchTargetLabel(target)
            val notificationRisk = assessNotificationRisk(packageName, target.target.title)
            val shouldSkipDisable = mode == BatchAdControlMode.DISABLE &&
                ShizukuAdControlCatalog.shouldSkipBatchDisable(packageName)
            if (shouldSkipDisable) {
                val reason = ShizukuAdControlCatalog.batchProtectedReason(packageName) ?: "系统功能保护"
                skipped += "$displayName（已跳过：$reason）"
                return@forEach
            }
            val forceBlockNotifications = mode == BatchAdControlMode.SMART_GOVERN && 
                notificationRisk == NotificationRiskLevel.HIGH
            val requested = when {
                forceBlockNotifications -> {
                    LogRepository.append(this, "Smart govern high-risk notification app: $packageName, forcing block notifications")
                    ShizukuAdControlRepository.blockPackageNotifications(this, packageName)
                }
                mode == BatchAdControlMode.SMART_GOVERN -> ShizukuAdControlRepository.blockPackageNotifications(this, packageName)
                mode == BatchAdControlMode.BLOCK_NOTIFICATIONS -> ShizukuAdControlRepository.blockPackageNotifications(this, packageName)
                mode == BatchAdControlMode.ALLOW_NOTIFICATIONS -> ShizukuAdControlRepository.allowPackageNotifications(this, packageName)
                mode == BatchAdControlMode.DISABLE -> ShizukuAdControlRepository.disablePackage(this, packageName)
                mode == BatchAdControlMode.ENABLE -> ShizukuAdControlRepository.enablePackage(this, packageName)
                mode == BatchAdControlMode.SUSPEND -> ShizukuAdControlRepository.suspendPackage(this, packageName)
                mode == BatchAdControlMode.UNSUSPEND -> ShizukuAdControlRepository.unsuspendPackage(this, packageName)
                else -> false
            }
            val status = ShizukuAdControlRepository.queryPackageStatus(this, packageName)
            val blockDisableFallback = (mode == BatchAdControlMode.SMART_GOVERN || forceBlockNotifications) &&
                !requested &&
                !ShizukuAdControlCatalog.shouldSkipBatchDisable(packageName) &&
                !isDisabledState(status.enabledState) &&
                ShizukuAdControlRepository.disablePackage(this, packageName)
            val disabledStatus = if (blockDisableFallback) {
                ShizukuAdControlRepository.queryPackageStatus(this, packageName)
            } else {
                status
            }
            val suspendFallbackSucceeded = (mode == BatchAdControlMode.SMART_GOVERN || forceBlockNotifications) &&
                !requested &&
                !isDisabledState(disabledStatus.enabledState) &&
                !disabledStatus.suspended &&
                ShizukuAdControlRepository.suspendPackage(this, packageName) &&
                ShizukuAdControlRepository.queryPackageStatus(this, packageName).suspended
            val refreshedStatus = ShizukuAdControlRepository.queryPackageStatus(this, packageName)
            val succeeded = when (mode) {
                BatchAdControlMode.SMART_GOVERN,
                BatchAdControlMode.BLOCK_NOTIFICATIONS,
                BatchAdControlMode.ALLOW_NOTIFICATIONS -> requested
                BatchAdControlMode.DISABLE -> requested && isDisabledState(refreshedStatus.enabledState)
                BatchAdControlMode.ENABLE -> requested && (
                    refreshedStatus.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                        refreshedStatus.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    )
                BatchAdControlMode.SUSPEND -> requested && refreshedStatus.suspended
                BatchAdControlMode.UNSUSPEND -> requested && !refreshedStatus.suspended
            }
            if (succeeded) {
                if (forceBlockNotifications && notificationRisk == NotificationRiskLevel.HIGH) {
                    operated += "$displayName（已强制关闭通知权限）"
                } else {
                    operated += displayName
                }
            } else if (suspendFallbackSucceeded) {
                degradedToSuspend += displayName
            } else {
                val statusSuffix = buildString {
                    append("状态=")
                    append(refreshedStatus.enabledLabel)
                    append('/')
                    append(if (refreshedStatus.suspended) "suspended" else "active")
                    append('/')
                    append(if (refreshedStatus.alive) "service-ok" else "service-missing")
                }
                failed += "$displayName（$statusSuffix）"
            }
        }
        val actionLabel = when (mode) {
            BatchAdControlMode.SMART_GOVERN -> if (hasHighRiskNotificationApps) "智能治理（高风险通知已强制关闭）" else "智能治理"
            BatchAdControlMode.BLOCK_NOTIFICATIONS -> "关闭推送广告"
            BatchAdControlMode.ALLOW_NOTIFICATIONS -> "恢复推送广告"
            BatchAdControlMode.DISABLE -> "整包冻结"
            BatchAdControlMode.ENABLE -> "解冻"
            BatchAdControlMode.SUSPEND -> "整包暂停"
            BatchAdControlMode.UNSUSPEND -> "恢复暂停"
        }
        val message = buildString {
            if (operated.isNotEmpty()) {
                append("批量${actionLabel}成功 ${operated.size} 项：")
                append(operated.joinToString("、"))
            }
            if (degradedToSuspend.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("已自动回退为暂停 ${degradedToSuspend.size} 项：")
                append(degradedToSuspend.joinToString("、"))
            }
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("处理失败 ${failed.size} 项：")
                append(failed.joinToString("、"))
            }
            if (skipped.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("为保护正常功能已跳过 ${skipped.size} 项：")
                append(skipped.joinToString("、"))
            }
            if (isEmpty()) {
                append("批量${actionLabel}失败")
            }
        }
        showOperationResult(message)
    }

    private enum class BatchAdControlMode {
        SMART_GOVERN,
        BLOCK_NOTIFICATIONS,
        ALLOW_NOTIFICATIONS,
        DISABLE,
        ENABLE,
        SUSPEND,
        UNSUSPEND
    }

    private enum class PromoGovernScope {
        ALL,
        SYSTEM_ONLY,
        THIRD_PARTY_ONLY
    }

    private data class BatchPackageTarget(
        val packageName: String,
        val target: PromoGovernTarget
    )

    enum class NotificationRiskLevel { HIGH, MEDIUM, LOW }

    private data class PromoGovernTarget(
        val packageName: String,
        val title: String,
        val category: String,
        val description: String,
        val sourceLabel: String,
        val systemApp: Boolean,
        val relatedPresets: List<ShizukuAdControlCatalog.Preset>,
        val packageStatus: ShizukuAdControlRepository.PackageControlStatus
    )

    private fun assessNotificationRisk(packageName: String, targetAppLabel: String): NotificationRiskLevel {
        val lowerLabel = targetAppLabel.lowercase()
        val lowerPackage = packageName.lowercase()
        val highRiskKeywords = listOf(
            "资讯", "新闻", "热点", "推荐", "精选", "发现", "看看", "头条",
            "news", "hot", "feed", "recommend", "discover", "toutiao"
        )
        val activityWelfareKeywords = listOf(
            "活动", "优惠", "折扣", "秒杀", "特卖", "团购", "签到", "任务", "领奖", "抽奖", "庆典",
            "会员中心", "积分商城", "福利中心", "活动中心", "领券", "优惠券", "红包", "赚钱", "福利",
            "activity", "sale", "discount", "coupon", "bonus", "welfare", "lottery", "task",
            "member", "vip", "points", "center", "event", "campaign", "promotion"
        )
        val mediumRiskKeywords = listOf(
            "应用商店", "软件商店", "浏览器", "视频", "短剧", "直播", "漫画", "动漫",
            "游戏中心", "内容中心", "内容服务", "免费小说",
            "market", "browser", "video", "live", "comic", "anime", "gamecenter", "reward"
        )
        val appStoreKeywords = listOf(
            "应用商店", "软件商店", "应用市场", "游戏中心",
            "appstore", "appmarket", "market", "gamecenter"
        )
        val hasHighRiskKeyword = highRiskKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) }
        val hasActivityWelfareKeyword = activityWelfareKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) }
        val hasMediumRiskKeyword = mediumRiskKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) }
        val isAppStore = appStoreKeywords.any { lowerLabel.contains(it) || lowerPackage.contains(it) }
        return when {
            hasHighRiskKeyword -> NotificationRiskLevel.HIGH
            isAppStore -> NotificationRiskLevel.HIGH
            hasActivityWelfareKeyword -> NotificationRiskLevel.HIGH  // 活动福利类强制关闭
            hasMediumRiskKeyword -> NotificationRiskLevel.MEDIUM
            else -> NotificationRiskLevel.LOW
        }
    }

    private data class PromoComponentCandidate(
        val componentName: String,
        val shortName: String,
        val typeLabel: String,
        val enabled: Boolean,
        val score: Int
    )

    private fun isDisabledState(enabledState: Int): Boolean {
        return enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    private fun buildPromoTargetLabels(
        targets: List<PromoGovernTarget>,
        installedPackages: Set<String>
    ): List<String> {
        return targets.map { target ->
            val installed = target.packageName in installedPackages
            val badge = if (installed) "已安装" else "未安装"
            val systemBadge = if (target.systemApp) "系统" else "第三方"
            val stateBadge = when {
                target.packageStatus.suspended -> "已暂停"
                isDisabledState(target.packageStatus.enabledState) -> "已冻结"
                else -> "可治理"
            }
            "[$badge/$systemBadge/$stateBadge] ${target.title} (${target.category} / ${target.sourceLabel})"
        }
    }

    private fun buildBatchTargetLabel(target: BatchPackageTarget): String {
        val titles = target.target.relatedPresets.map { it.title }.distinct()
        return if (titles.size == 1) {
            titles.first()
        } else if (titles.size > 1) {
            "${target.target.title}（同包：${titles.joinToString("、")}）"
        } else {
            target.target.title
        }
    }


    private suspend fun ensureShizukuReady(): Boolean {
        if (!AppSettingsRepository.isShizukuEnabled(this)) {
            StableDialog.builder(this)
                .setTitle("Shizuku 未启用")
                .setMessage("请先开启设置中的 Shizuku 增强。")
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show ensure shizuku enabled dialog failed")
            return false
        }
        val readyState = queryShizukuReadyState(warmIfNeeded = true)
        if (!readyState.readyForEnhancedUse) {
            StableDialog.builder(this)
                .setTitle("Shizuku 暂不可用")
                .setMessage(buildShizukuUnavailableMessage(readyState))
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show shizuku unavailable dialog failed")
            return false
        }
        if (!readyState.adControlAlive) {
            StableDialog.builder(this)
                .setTitle("Shizuku 服务连接失败")
                .setMessage("Shizuku 已连接，但治理服务还未成功绑定。请稍后重试，或重新进入 Shizuku 后再回来。")
                .setPositiveButton("我知道了", null)
                .showSafely(this, "Show shizuku bind failed dialog failed")
            return false
        }
        return true
    }

    private fun showPromoTargetActionDialog(target: PromoGovernTarget) {
        val status = ShizukuAdControlRepository.queryPackageStatus(this, target.packageName)
        val relatedPresets = target.relatedPresets
        val canDisable = status.installed && !isDisabledState(status.enabledState)
        val canEnable = status.installed && isDisabledState(status.enabledState)
        val canSuspend = status.installed && !status.suspended
        val canUnsuspend = status.installed && status.suspended
        val message = buildString {
            append(target.description)
            if (relatedPresets.size > 1) {
                append("\n\n同包治理标签：")
                append(relatedPresets.joinToString("、") { it.title })
            }
            ShizukuAdControlCatalog.batchProtectedReason(target.packageName)?.let { reason ->
                append("\n\n批量保护：")
                append("该项目属于")
                append(reason)
                append("，批量冻结和智能治理会默认跳过，建议仅在确认风险后手动处理。")
            }
            append("\n\n来源：")
            append(target.sourceLabel)
            append("\n\n分类：")
            append(target.category)
            append("\n包名：")
            append(target.packageName)
            append("\n应用类型：")
            append(if (target.systemApp) "系统 App" else "第三方 App")
            append("\n已安装：")
            append(if (status.installed) "是" else "否")
            append("\n当前状态：")
            append(status.enabledLabel)
            append("\n暂停状态：")
            append(if (status.suspended) "已暂停" else "未暂停")
            append("\n服务状态：")
            append(if (status.alive) "已连接" else "未连接")
        }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (status.installed && (canDisable || canSuspend)) {
            actions += "智能治理" to {
                executeGovernAction {
                    val lightGoverned = ShizukuAdControlRepository.blockPackageNotifications(this@SettingsActivity, target.packageName)
                    if (!lightGoverned && canDisable) {
                        ShizukuAdControlRepository.disablePackage(this@SettingsActivity, target.packageName)
                    }
                    val disabledStatus = ShizukuAdControlRepository.queryPackageStatus(this@SettingsActivity, target.packageName)
                    val disableSuccess = isDisabledState(disabledStatus.enabledState)
                    if (lightGoverned) {
                        "治理成功，当前已关闭推送广告能力"
                    } else if (disableSuccess) {
                        "治理成功，当前已冻结"
                    } else {
                        val suspendRequested = if (!disabledStatus.suspended && canSuspend) {
                            ShizukuAdControlRepository.suspendPackage(this@SettingsActivity, target.packageName)
                        } else {
                            false
                        }
                        val suspendStatus = ShizukuAdControlRepository.queryPackageStatus(this@SettingsActivity, target.packageName)
                        val suspendSuccess = suspendRequested && suspendStatus.suspended
                        if (suspendSuccess) "冻结未生效，已自动回退为暂停" else "治理失败，请确认系统支持冻结或暂停"
                    }
                }
            }
        }
        if (status.installed) {
            actions += "关闭推送广告" to {
                executeGovernAction {
                    val success = ShizukuAdControlRepository.blockPackageNotifications(this@SettingsActivity, target.packageName)
                    if (success) "关闭推送广告成功" else "关闭推送广告失败，请确认系统支持通知权限治理"
                }
            }
            actions += "恢复推送广告" to {
                executeGovernAction {
                    val success = ShizukuAdControlRepository.allowPackageNotifications(this@SettingsActivity, target.packageName)
                    if (success) "恢复推送广告成功" else "恢复推送广告失败，请确认系统支持通知权限治理"
                }
            }
        }
        if (canDisable) {
            actions += "冻结" to {
                executeGovernAction {
                    val requested = ShizukuAdControlRepository.disablePackage(this@SettingsActivity, target.packageName)
                    val refreshed = ShizukuAdControlRepository.queryPackageStatus(this@SettingsActivity, target.packageName)
                    val success = requested && isDisabledState(refreshed.enabledState)
                    if (success) "冻结成功" else "冻结失败，请确认该项目支持冻结"
                }
            }
        }
        if (canEnable) {
            actions += "解冻" to {
                executeGovernAction {
                    val requested = ShizukuAdControlRepository.enablePackage(this@SettingsActivity, target.packageName)
                    val refreshed = ShizukuAdControlRepository.queryPackageStatus(this@SettingsActivity, target.packageName)
                    val success = requested && (
                        refreshed.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                            refreshed.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        )
                    if (success) "解冻成功" else "解冻失败，请确认该项目仍然存在"
                }
            }
        }
        if (canSuspend || canUnsuspend) {
            actions += (if (status.suspended) "恢复暂停" else "暂停") to {
                executeGovernAction {
                    val requested = if (status.suspended) {
                        ShizukuAdControlRepository.unsuspendPackage(this@SettingsActivity, target.packageName)
                    } else {
                        ShizukuAdControlRepository.suspendPackage(this@SettingsActivity, target.packageName)
                    }
                    val refreshed = ShizukuAdControlRepository.queryPackageStatus(this@SettingsActivity, target.packageName)
                    val success = requested && if (status.suspended) !refreshed.suspended else refreshed.suspended
                    val actionText = if (status.suspended) "恢复暂停" else "暂停"
                    if (success) "${actionText}成功" else "${actionText}失败，请确认系统支持该操作"
                }
            }
        }
        if (status.installed) {
            actions += "组件治理" to {
                launchActivitySafely(
                    PromoComponentGovernActivity.createIntent(this, target.packageName, target.title),
                    "打开组件治理页面失败"
                )
            }
        }
        if (actions.isEmpty()) {
            showOperationResult("当前项目暂无可执行治理动作，请先确认目标应用已安装且 Shizuku 服务状态正常")
            return
        }
        StableDialog.builder(this)
            .setTitle("选择治理方式：${target.title}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .setNeutralButton("详情") { _, _ ->
                showGovernTargetDetails(target.title, message)
            }
            .setNegativeButton("关闭", null)
            .showSafely(this, "Show govern target actions dialog failed")
    }

    private fun showGovernTargetDetails(title: String, message: String) {
        StableDialog.builder(this)
            .setTitle("治理详情：$title")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .showSafely(this, "Show govern target details dialog failed")
    }

    private fun showComponentGovernDialog(target: PromoGovernTarget) {
        lifecycleScope.launch {
            val (candidates, activities) = withContext(Dispatchers.Default) {
                discoverPromoComponentCandidates(target.packageName) to discoverAllActivityComponentCandidates(target.packageName)
            }
            if (isFinishing || isDestroyed) return@launch
            if (candidates.isNotEmpty() || activities.isNotEmpty()) {
                val selectableComponents = (candidates + activities)
                    .distinctBy { it.componentName }
                    .sortedWith(compareByDescending<PromoComponentCandidate> { it.score }.thenBy { it.typeLabel }.thenBy { it.shortName })
                val checked = BooleanArray(selectableComponents.size) { index ->
                    selectableComponents[index].enabled && selectableComponents[index].score > 0
                }
                val labels = selectableComponents.map { candidate ->
                    buildComponentChoiceLabel(candidate)
                }.toTypedArray()
                val dialog = StableDialog.builder(this@SettingsActivity)
                    .setTitle("勾选要冻结的组件（${selectableComponents.size} 个）")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("冻结选中", null)
                    .setNeutralButton("全部 Activity", null)
                    .setNegativeButton("更多", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val selected = selectableComponents.filterIndexed { index, _ -> checked[index] }
                        if (selected.isEmpty()) {
                            showShortToast("请先选择组件")
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        executeComponentBatchAction(selected, disable = true)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        if (activities.isEmpty()) {
                            showShortToast("未识别到 Activity")
                            return@setOnClickListener
                        }
                        showAllActivityGovernDialog(activities)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        val selected = selectableComponents.filterIndexed { index, _ -> checked[index] }
                        showComponentMoreDialog(target, selected, selectableComponents.firstOrNull()?.componentName.orEmpty())
                    }
                }
                dialog.showSafely(this@SettingsActivity, "Show component candidates dialog failed")
                return@launch
            }
            showManualComponentGovernDialog(target, "${target.packageName}/")
        }
    }

    private fun showComponentMoreDialog(
        target: PromoGovernTarget,
        selected: List<PromoComponentCandidate>,
        initialValue: String
    ) {
        val actions = arrayOf("解冻选中组件", "高级手动输入")
        StableDialog.builder(this)
            .setTitle("更多组件操作")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        if (selected.isEmpty()) {
                            showShortToast("请先选择组件")
                        } else {
                            executeComponentBatchAction(selected, disable = false)
                        }
                    }
                    1 -> showManualComponentGovernDialog(target, initialValue)
                }
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show component more actions dialog failed")
    }

    private fun buildComponentChoiceLabel(candidate: PromoComponentCandidate): String {
        val state = if (candidate.enabled) "启用中" else "已冻结"
        val lowerName = candidate.componentName.lowercase()
        val groupLabel = when {
            candidate.typeLabel == "Activity" && listOf("splash", "startup", "launchad", "interstitial").any(lowerName::contains) -> "启动广告 Activity"
            candidate.typeLabel == "Activity" && listOf("feed", "recommend", "discover", "hot", "operation", "market").any(lowerName::contains) -> "推荐流/活动页 Activity"
            candidate.typeLabel == "Activity" && listOf("launcher", "mainactivity", "homeactivity", "main").any(lowerName::contains) -> "主入口 Activity"
            candidate.typeLabel == "Activity" && listOf("login", "account", "passport", "auth", "oauth").any(lowerName::contains) -> "账号登录 Activity"
            candidate.typeLabel == "Activity" && listOf("setting", "permission", "privacy", "security").any(lowerName::contains) -> "设置/权限 Activity"
            candidate.typeLabel == "Activity" && listOf("webview", "browser", "hybrid", "h5").any(lowerName::contains) -> "网页容器 Activity"
            candidate.typeLabel == "Activity" && listOf("pay", "payment", "wallet", "cashier").any(lowerName::contains) -> "支付/钱包 Activity"
            candidate.typeLabel == "Activity" && listOf("share", "invite", "deeplink", "scheme").any(lowerName::contains) -> "分享/跳转 Activity"
            candidate.typeLabel == "Receiver" && listOf("push", "alarm", "message", "notice").any(lowerName::contains) -> "推送 Receiver"
            candidate.typeLabel == "Service" && listOf("push", "job", "message", "notice").any(lowerName::contains) -> "推送 Service"
            candidate.typeLabel == "Service" && listOf("ad", "advert", "union", "reward").any(lowerName::contains) -> "广告 Service"
            else -> "普通 ${candidate.typeLabel}"
        }
        val riskLabel = when (groupLabel) {
            "主入口 Activity", "账号登录 Activity", "支付/钱包 Activity" -> "高风险"
            "设置/权限 Activity", "网页容器 Activity", "推送 Receiver", "推送 Service", "广告 Service" -> "中风险"
            else -> if (candidate.score > 0) "需确认" else "普通"
        }
        val recommendation = when (groupLabel) {
            "启动广告 Activity" -> "通常影响启动页广告，可优先尝试冻结。"
            "推荐流/活动页 Activity" -> "可能影响活动页或推荐入口，按需冻结。"
            "主入口 Activity" -> "冻结后图标或主页面可能不可用，适合冻结式处理。"
            "账号登录 Activity" -> "通常影响登录授权，建议保留。"
            "设置/权限 Activity" -> "可能影响应用设置或权限引导，谨慎处理。"
            "网页容器 Activity" -> "可能承载广告页，也可能承载正文/业务网页，先确认来源。"
            "支付/钱包 Activity" -> "可能影响支付和钱包功能，建议保留。"
            "分享/跳转 Activity" -> "可能影响外链、分享和深链跳转，按需处理。"
            "推送 Receiver" -> "适合治理推送广告，失败时可解冻。"
            "推送 Service" -> "适合治理后台推送广告，建议保留解冻路径。"
            "广告 Service" -> "适合治理广告加载服务，确认后冻结。"
            else -> "未命中明显广告特征，建议确认用途后处理。"
        }
        return "[$groupLabel / $riskLabel / $state] ${candidate.shortName}\n${candidate.componentName}\n建议：$recommendation"
    }

    private fun showAllActivityGovernDialog(activities: List<PromoComponentCandidate>) {
        StableDialog.builder(this)
            .setTitle("全部 Activity 治理")
            .setMessage(
                "将处理 ${activities.size} 个 Activity。冻结全部 Activity 后，桌面图标可能消失，应用页面通常无法打开，效果接近冰箱冻结。解冻全部 Activity 可撤销该组件级处理。"
            )
            .setPositiveButton("冻结全部 Activity") { _, _ ->
                executeComponentBatchAction(activities, disable = true)
            }
            .setNeutralButton("解冻全部 Activity") { _, _ ->
                executeComponentBatchAction(activities, disable = false)
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show all activity govern dialog failed")
    }

    private fun showManualComponentGovernDialog(target: PromoGovernTarget, initialValue: String) {
        val input = EditText(this).apply {
            hint = "输入完整组件名，如 ${target.packageName}/.SplashActivity"
            setText(initialValue.ifBlank { "${target.packageName}/" })
            setSelection(text.length)
        }
        val dialog = StableDialog.builder(this)
            .setTitle("组件治理")
            .setMessage("适合处理启动页 Activity、推荐页 Activity、广告 Service、推送 Receiver 等单个组件。请输入完整组件名后选择动作。")
            .setView(input)
            .setPositiveButton("冻结组件", null)
            .setNeutralButton("解冻组件", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (runManualComponentAction(input, disable = true)) {
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (runManualComponentAction(input, disable = false)) {
                    dialog.dismiss()
                }
            }
        }
        dialog.showSafely(this, "Show manual component govern dialog failed") ?: return
    }

    private fun runManualComponentAction(input: EditText, disable: Boolean): Boolean {
        val componentName = input.text?.toString().orEmpty().trim()
        if (componentName.isBlank()) {
            showShortToast("请先输入完整组件名")
            return false
        }
        executeGovernAction {
            val success = if (disable) {
                ShizukuAdControlRepository.disableComponent(this@SettingsActivity, componentName)
            } else {
                ShizukuAdControlRepository.enableComponent(this@SettingsActivity, componentName)
            }
            if (success) {
                if (disable) "组件冻结成功" else "组件解冻成功"
            } else {
                if (disable) "组件冻结失败，请确认组件名完整且系统支持该操作" else "组件解冻失败，请确认组件名完整且系统支持该操作"
            }
        }
        return true
    }

    private fun executeComponentBatchAction(
        candidates: List<PromoComponentCandidate>,
        disable: Boolean
    ) {
        executeGovernAction {
            var successCount = 0
            val failed = mutableListOf<String>()
            candidates.forEach { candidate ->
                val success = if (disable) {
                    ShizukuAdControlRepository.disableComponent(this@SettingsActivity, candidate.componentName)
                } else {
                    ShizukuAdControlRepository.enableComponent(this@SettingsActivity, candidate.componentName)
                }
                if (success) {
                    successCount += 1
                } else {
                    failed += candidate.shortName
                }
            }
            val actionText = if (disable) "冻结" else "解冻"
            buildString {
                append("组件${actionText}完成：成功 ")
                append(successCount)
                append(" 个，失败 ")
                append(failed.size)
                append(" 个")
                if (failed.isNotEmpty()) {
                    append("\n失败组件：")
                    append(failed.joinToString("、"))
                }
            }
        }
    }

    private fun executeGovernAction(action: () -> String) {
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                action()
            }
            if (!isFinishing && !isDestroyed) {
                showOperationResult(message)
            }
        }
    }

    private fun discoverPromoComponentCandidates(packageName: String): List<PromoComponentCandidate> {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        (
                            PackageManager.GET_ACTIVITIES or
                                PackageManager.GET_RECEIVERS or
                                PackageManager.GET_SERVICES
                            ).toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES
                )
            }
        }.getOrNull() ?: return emptyList()
        val candidates = mutableListOf<PromoComponentCandidate>()
        fun addCandidates(components: Array<out ComponentInfo>?, typeLabel: String) {
            components.orEmpty()
                .filter { it.name.startsWith(packageName) }
                .mapNotNull { component ->
                    buildPromoComponentCandidate(packageName, component.name, typeLabel)
                }
                .forEach(candidates::add)
        }
        addCandidates(packageInfo.activities, "Activity")
        addCandidates(packageInfo.receivers, "Receiver")
        addCandidates(packageInfo.services, "Service")
        return candidates
            .distinctBy { it.componentName }
            .sortedWith(
                compareByDescending<PromoComponentCandidate> { it.score }
                    .thenBy { it.typeLabel }
                    .thenBy { it.shortName }
            )
            .take(12)
    }

    private fun discoverAllActivityComponentCandidates(packageName: String): List<PromoComponentCandidate> {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_ACTIVITIES or packageQueryFlags()).toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_ACTIVITIES or packageQueryFlags()
                )
            }
        }.getOrNull() ?: return emptyList()

        return packageInfo.activities.orEmpty()
            .filter { it.name.startsWith(packageName) }
            .map { component ->
                val className = component.name
                val flattenedComponentName = packageName + "/" + className.removePrefix(packageName)
                val enabled = packageManager.getComponentEnabledSetting(android.content.ComponentName(packageName, className))
                    .let { state -> state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
                PromoComponentCandidate(
                    componentName = flattenedComponentName,
                    shortName = className.removePrefix(packageName).ifBlank { className },
                    typeLabel = "Activity",
                    enabled = enabled,
                    score = promoComponentScore(className.lowercase(), "Activity")
                )
            }
            .distinctBy { it.componentName }
            .sortedBy { it.shortName }
    }

    private fun buildPromoComponentCandidate(packageName: String, className: String, typeLabel: String): PromoComponentCandidate? {
        val lowerName = className.lowercase()
        val score = promoComponentScore(lowerName, typeLabel)
        if (score <= 0) return null
        val flattenedComponentName = packageName + "/" + className.removePrefix(packageName)
        val shortName = className.removePrefix(packageName).ifBlank { className }
        val enabled = packageManager.getComponentEnabledSetting(android.content.ComponentName(packageName, className))
            .let { state -> state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        return PromoComponentCandidate(
            componentName = flattenedComponentName,
            shortName = shortName,
            typeLabel = typeLabel,
            enabled = enabled,
            score = score
        )
    }

    private fun promoComponentScore(lowerName: String, typeLabel: String): Int {
        var score = 0
        val strongHints = listOf(
            "splash", "startup", "launchad", "advert", "adactivity", "adservice", "adreceiver",
            "push", "recommend", "promo", "feedad", "reward", "interstitial", "union"
        )
        val moderateHints = listOf(
            "guide", "popup", "notice", "message", "operation", "market", "discover", "hot", "brand"
        )
        strongHints.forEach { if (lowerName.contains(it)) score += 3 }
        moderateHints.forEach { if (lowerName.contains(it)) score += 1 }
        if (typeLabel == "Activity" && listOf("splash", "startup", "launch", "ad").any(lowerName::contains)) score += 2
        if (typeLabel == "Receiver" && listOf("push", "alarm", "recommend", "ad").any(lowerName::contains)) score += 2
        if (typeLabel == "Service" && listOf("push", "ad", "recommend", "job").any(lowerName::contains)) score += 2
        return score
    }

    private fun discoverPromoGovernTargets(scope: PromoGovernScope): List<PromoGovernTarget> {
        val autoTargets = packageManager.getInstalledApplications(packageQueryFlags())
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { appInfo ->
                // 只治理第三方 App，不治理系统 App
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                !isSystem
            }
            .mapNotNull { appInfo ->
                buildThirdPartyPromoTarget(appInfo)
            }
            .toList()
        return autoTargets
            .filter { target ->
                when (scope) {
                    PromoGovernScope.ALL -> true
                    PromoGovernScope.SYSTEM_ONLY -> target.systemApp
                    PromoGovernScope.THIRD_PARTY_ONLY -> !target.systemApp
                }
            }
            .sortedWith(compareBy<PromoGovernTarget> { it.category }.thenBy { it.title })
    }

    private fun packageQueryFlags(): Int {
        return PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
    }

    private fun buildThirdPartyPromoTarget(appInfo: ApplicationInfo): PromoGovernTarget? {
        val packageName = appInfo.packageName
        val label = packageManager.getApplicationLabel(appInfo).toString().ifBlank { packageName }
        val lowerLabel = label.lowercase()
        val lowerPackage = packageName.lowercase()
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        
        // 系统 App 直接过滤，不治理
        if (isSystem) return null
        
        return PromoGovernTarget(
            packageName = packageName,
            title = label,
            category = inferPromoCategory(lowerLabel, lowerPackage),
            description = "适合治理该已安装推广 App 的通知广告、推荐流、营销入口和关联推广行为。",
            sourceLabel = "已安装第三方 App",
            systemApp = false,
            relatedPresets = emptyList(),
            packageStatus = ShizukuAdControlRepository.queryPackageStatus(this, packageName)
        )
    }

    private fun isWellKnownThirdPartyPromoApp(packageName: String, label: String): Boolean {
        val lowerPackage = packageName.lowercase()
        val lowerLabel = label.lowercase()
        val wellKnownPrefixes = listOf(
            "com.taobao.", "com.tmall.", "com.alibaba.", "com.alipay.", "com.xiami.",
            "com.meituan.", "com.sankuai.", "com.dianping.",
            "com.jingdong.", "com.jd.",
            "com.ss.android.ugc.aweme", "com.ss.android.article.news", "com.ss.android.article.lite",
            "com.iesdouyin.", "com.zhiliaoapp.musically",
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.qqlive", "com.tencent.qqmusic",
            "com.tencent.news", "com.tencent.reading", "com.tencent.map", "com.tencent.mmwork",
            "com.qq.reader", "com.sina.weibo", "com.sina.news",
            "com.smile.gifmaker", "com.kuaishou.",
            "tv.danmaku.bili", "com.bilibili.",
            "com.douyu.", "com.douyutv.", "com.douyin.",
            "com.eleme.", "me.ele.", "com.rajax.me",
            "com.didiglobal.", "com.didichuxing.",
            "com.eg.android.", "com.mybank.", "com.chinamworld.",
            "com.ctrip.", "com.Qunar", "com.tongcheng.",
            "com.baidu.netdisk", "com.baidu.searchbox", "com.baidu.BaiduMap",
            "com.wps.", "cn.wps.",
            "com.netease.cloudmusic", "com.netease.mail", "com.netease.newsreader",
            "com.163.mail", "com.netease.mobimail",
            "com.dragon.read", "com.qidian.",
            "com.UCMobile", "com.uc.", "com.quark.",
            "com.autonavi.", "com.amap.",
            "com.ximalaya.", "com.xunlei.", "com.xunlei.kankan"
        )
        val wellKnownLabelHints = listOf(
            "淘宝", "天猫", "美团", "大众点评", "京东", "京东到家", "拼多多", "唯品会",
            "今日头条", "头条", "抖音", "快手", "哔哩哔哩", "bilibili",
            "微博", "微信", "qq", "qq音乐", "腾讯视频", "爱奇艺", "优酷",
            "支付宝", "百度地图", "高德地图", "美团外卖", "饿了么",
            "滴滴", "携程", "去哪儿",
            "百度网盘", "wps", "网易云音乐", "qq邮箱", "网易邮箱",
            "番茄小说", "起点", "uc浏览器", "夸克", "喜马拉雅", "迅雷"
        )
        if (wellKnownPrefixes.any { lowerPackage.startsWith(it) }) return true
        if (wellKnownLabelHints.any { lowerLabel.contains(it) }) return true
        return false
    }

    private fun looksLikeThirdPartyPromoApp(lowerLabel: String, lowerPackage: String): Boolean {
        val labelHints = listOf(
            "应用商店", "软件商店", "浏览器", "阅读", "小说", "免费小说", "短剧", "视频", "资讯", "新闻",
            "壁纸", "主题", "锁屏", "搜索", "内容中心", "内容服务", "游戏中心", "游戏盒子", "助手",
            "推荐", "精选", "热点", "发现", "看看", "赚钱", "福利", "红包",
            "淘宝", "天猫", "美团", "京东", "拼多多", "唯品会",
            "今日头条", "头条", "抖音", "快手", "哔哩哔哩", "微博",
            "支付宝", "饿了么", "携程", "去哪儿", "百度网盘", "网易云音乐", "喜马拉雅"
        )
        val packageHints = listOf(
            "appstore", "market", "browser", "reader", "novel", "book", "video", "news", "wallpaper",
            "theme", "lockscreen", "search", "assistant", "gamecenter", "gamebox", "content", "promo",
            "recommend", "discover", "hot", "reward", "benefit", "ad", "union",
            "jd.com", "jdmall", "jingdong", "sankuai", "meituan", "taobao", "tmall", "alibaba",
            "toutiao", "jinritoutiao", "douyin", "bytedance", "iesdouyin", "kuaishou", "bilibili",
            "weibo", "sina", "eleme", "ctrip", "qunar", "ximalaya"
        )
        val oemHints = listOf(
            "heytap", "coloros", "realme", "vivo", "iqoo", "oppo", "miui", "xiaomi", "redmi",
            "hyperos", "huawei", "honor", "magicui", "emui", "oneplus", "meizu", "zte", "nubia",
            "lenovo", "zuk", "samsung"
        )
        val distributionHints = listOf(
            "contentcenter", "contentservice", "feed", "recommend", "discovery", "assistant",
            "gamecenter", "appstore", "appmarket", "launcherad", "adsdk", "union"
        )
        val labelMatched = labelHints.any(lowerLabel::contains)
        val packageMatched = packageHints.any(lowerPackage::contains)
        val oemDistributionMatched = oemHints.any(lowerPackage::contains) && distributionHints.any(lowerPackage::contains)
        return labelMatched || packageMatched || oemDistributionMatched
    }

    private fun inferPromoCategory(lowerLabel: String, lowerPackage: String): String {
        return when {
            listOf("浏览器", "browser").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "浏览器推荐"
            listOf("壁纸", "主题", "wallpaper", "theme").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "主题壁纸"
            listOf("锁屏", "lockscreen").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "锁屏推荐"
            listOf("小说", "阅读", "novel", "reader", "book").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "阅读推广"
            listOf("短剧", "视频", "video").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "视频推广"
            listOf("资讯", "新闻", "热点", "news", "hot", "头条").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "资讯推荐"
            listOf("淘宝", "京东", "美团", "拼多多", "商城", "mall", "jd.com", "jingdong", "taobao", "meituan").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "电商推广"
            listOf("饿了么", "外卖", "eleme").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "外卖推广"
            listOf("应用商店", "软件商店", "market", "appstore").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "系统推广"
            listOf("搜索", "助手", "search", "assistant").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "内容推荐"
            listOf("游戏中心", "gamecenter").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "系统推广"
            else -> "内容推荐"
        }
    }

    private fun showOperationResult(message: String) {
        NetworkKernel.reloadIfRunning(this)
        val operationSummary = ShizukuAdControlRepository.getLastOperationSummary(this)
            .takeIf { it.isNotBlank() && it != "idle" }
        StableDialog.builder(this)
            .setMessage(
                buildString {
                    append(message)
                    operationSummary?.let {
                        append("\n\n服务反馈：")
                        append(it)
                    }
                }
            )
            .setPositiveButton("确定", null)
            .showSafely(this, "Show govern result dialog failed")
    }

    companion object {
        private const val SHIZUKU_STATUS_REFRESH_INTERVAL_MILLIS = 3_000L
    }
    
}
