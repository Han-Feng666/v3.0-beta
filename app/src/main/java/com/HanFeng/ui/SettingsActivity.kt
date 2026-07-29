package com.HanFeng.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.HanFeng.R
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.core.network.RegexCache
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepositoryExport
import com.HanFeng.data.ShizukuAdControlCatalog
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.security.CertificateAuthorityManager
import com.HanFeng.service.AdBlockVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : BaseActivity() {

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
    private lateinit var btnShizukuPermissionManage: Button
    private lateinit var btnShizukuAdControl: Button
    private lateinit var btnAppFreeze: Button
    private lateinit var btnGameAntiMark: Button
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
    private lateinit var btnModifyMainboardId: Button
    private lateinit var btnModifyModel: Button
    private lateinit var btnModifySn: Button
    private lateinit var btnModifyImei: Button
    private lateinit var btnModifyMeid: Button
    private lateinit var btnConvertSystemApp: Button
    private lateinit var btnBack: Button
    private lateinit var settingsRoot: View
    private lateinit var btnHostsEditor: Button
    private lateinit var btnNetworkPermission: Button
    private lateinit var btnBackgroundRestrict: Button
    private lateinit var btnRootScript: Button
    private lateinit var btnRootHide: Button
    private lateinit var cbAutoInstallSystemCert: CheckBox

    private lateinit var switchHotspotBlock: Switch
    private lateinit var switchIdleShutdown: Switch
    private lateinit var spinnerIdleShutdownInterval: Spinner
    private lateinit var textIdleShutdownDesc: TextView
    private lateinit var switchNotificationAdBlock: Switch
    private lateinit var btnOpenNotificationAccess: Button
    private lateinit var textNotificationAdBlockDesc: TextView
    private lateinit var hotspotModeLayout: LinearLayout
    private lateinit var rgHotspotMode: RadioGroup
    private lateinit var tvHotspotStatus: TextView
    private lateinit var tvHotspotDevices: TextView
    private lateinit var tvHotspotBlocked: TextView
    private lateinit var tvHotspotRuntime: TextView

    private lateinit var btnChooseBackground: Button
    private lateinit var btnRemoveBackground: Button
    private lateinit var textCustomBgPreview: TextView
    private lateinit var btnFloatingBall: Button
    private lateinit var btnRunningApps: Button

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        settingsRoot = findViewById(R.id.settingsRoot)
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
        btnShizukuAdControl = findViewById(R.id.btnShizukuAdControl)
        btnAppFreeze = findViewById(R.id.btnAppFreeze)
        btnGameAntiMark = findViewById(R.id.btnGameAntiMark)
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
        btnModifyMainboardId = findViewById(R.id.btnModifyMainboardId)
        btnModifyModel = findViewById(R.id.btnModifyModel)
        btnModifySn = findViewById(R.id.btnModifySn)
        btnModifyImei = findViewById(R.id.btnModifyImei)
        btnModifyMeid = findViewById(R.id.btnModifyMeid)
        btnConvertSystemApp = findViewById(R.id.btnConvertSystemApp)
        btnBack = findViewById(R.id.btnBack)
        
        // Shizuku 增强
        btnHostsEditor = findViewById(R.id.btnHostsEditor)
        btnNetworkPermission = findViewById(R.id.btnNetworkPermission)
        btnBackgroundRestrict = findViewById(R.id.btnBackgroundRestrict)
        btnRootScript = findViewById(R.id.btnRootScript)
        btnRootHide = findViewById(R.id.btnRootHide)
        btnShizukuPermissionManage = findViewById(R.id.btnShizukuPermissionManage)
        cbAutoInstallSystemCert = findViewById(R.id.cbAutoInstallSystemCert)

        switchHotspotBlock = findViewById(R.id.switchHotspotBlock)
        switchIdleShutdown = findViewById(R.id.switchIdleShutdown)
        spinnerIdleShutdownInterval = findViewById(R.id.spinnerIdleShutdownInterval)
        textIdleShutdownDesc = findViewById(R.id.textIdleShutdownDesc)
        switchNotificationAdBlock = findViewById(R.id.switchNotificationAdBlock)
        btnOpenNotificationAccess = findViewById(R.id.btnOpenNotificationAccess)
        textNotificationAdBlockDesc = findViewById(R.id.textNotificationAdBlockDesc)
        hotspotModeLayout = findViewById(R.id.hotspotModeLayout)
        rgHotspotMode = findViewById(R.id.rgHotspotMode)
        tvHotspotStatus = findViewById(R.id.tvHotspotStatus)
        tvHotspotDevices = findViewById(R.id.tvHotspotDevices)
        tvHotspotBlocked = findViewById(R.id.tvHotspotBlocked)
        tvHotspotRuntime = findViewById(R.id.tvHotspotRuntime)

        btnChooseBackground = findViewById(R.id.btnChooseBackground)
        btnRemoveBackground = findViewById(R.id.btnRemoveBackground)
        textCustomBgPreview = findViewById(R.id.textCustomBgPreview)
        btnFloatingBall = findViewById(R.id.btnFloatingBall)
        btnRunningApps = findViewById(R.id.btnRunningApps)

        val initialTopPadding = settingsRoot.paddingTop
        val initialBottomPadding = settingsRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(settingsRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, initialTopPadding + systemBars.top, view.paddingRight, initialBottomPadding + systemBars.bottom)
            insets
        }

        switchHideBackground.isChecked = AppSettingsRepository.isHideBackgroundEnabled(this)
        stealthSubLayout.visibility = if (switchStealthMode.isChecked) View.VISIBLE else View.GONE
        switchStealthMode.isChecked = FeatureSettingsRepository.isStealthModeEnabled(this)
        switchStealthStripParams.isChecked = FeatureSettingsRepository.isStealthStripTrackingParamsEnabled(this)
        switchStealthHideReferer.isChecked = FeatureSettingsRepository.isStealthHideRefererEnabled(this)
        switchStealthRemoveFingerprintHeaders.isChecked = FeatureSettingsRepository.isStealthRemoveFingerprintHeadersEnabled(this)
        switchAdFreeReward.isChecked = FeatureSettingsRepository.isAdFreeRewardEnabled(this)

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

        refreshCustomBackgroundPreview()
        btnChooseBackground.setOnClickListener {
            chooseBackgroundImage()
        }
        btnRemoveBackground.setOnClickListener {
            removeCustomBackground()
        }
        btnFloatingBall.setOnClickListener {
            startActivity(Intent(this, FloatingBallSettingsActivity::class.java))
        }

        btnRunningApps.setOnClickListener {
            startActivity(Intent(this, RunningAppsActivity::class.java))
        }

        btnManageCustomTrackingParams.setOnClickListener {
            showCustomParamsDialog()
        }
        btnManageCustomTrackingHeaders.setOnClickListener {
            showCustomHeadersDialog()
        }
        refreshCustomTrackingPreviews()
        btnShizukuPermissionManage.setOnClickListener {
            startActivity(Intent(this, ShizukuPermissionManageActivity::class.java))
        }
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

        setupIdleShutdownSetting()
        setupNotificationAdBlockSetting()

        startHotspotStatusRefresh()
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
        cbAutoInstallSystemCert.setOnClickListener {
            FeatureSettingsRepository.setAutoInstallSystemCertEnabled(this, cbAutoInstallSystemCert.isChecked)
        }
        btnModifyDeviceId.setOnClickListener {
            requireRootThen("root-unavailable-device-id", "修改 Android ID") { granted ->
                if (granted) showModifyDeviceIdDialog()
            }
        }
        btnModifySerial.setOnClickListener {
            requireRootThen("root-unavailable-serial", "修改主板序列号") { granted ->
                if (granted) showModifySerialDialog()
            }
        }
        btnModifyMainboardId.setOnClickListener {
            requireRootThen("root-unavailable-mainboard", "修改 CPUID 和 CELLID") { granted ->
                if (granted) showModifyMainboardIdDialog()
            }
        }
        btnModifyModel.setOnClickListener {
            requireRootThen("root-unavailable-model", "修改手机型号") { granted ->
                if (granted) showModifyModelDialog()
            }
        }
        btnModifySn.setOnClickListener {
            requireRootThen("root-unavailable-sn", "修改 SN 码") { granted ->
                if (granted) showModifySnDialog()
            }
        }
        btnModifyImei.setOnClickListener {
            requireRootThen("root-unavailable-imei", "修改 IMEI") { granted ->
                if (granted) showModifyImeiDialog()
            }
        }
        btnModifyMeid.setOnClickListener {
            requireRootThen("root-unavailable-meid", "修改 MEID") { granted ->
                if (granted) showModifyMeidDialog()
            }
        }
        btnConvertSystemApp.setOnClickListener {
            requireRootThen("root-unavailable-convert-sys-app", "第三方应用转系统应用") { granted ->
                if (granted) showConvertSystemAppDialog()
            }
        }
        refreshSystemCertStatus()
        
        // Shizuku 增强功能按钮
        btnHostsEditor.setOnClickListener {
            if (!ShizukuRepository.isBinderReachable()) {
                showShortToast("请先授权 Shizuku")
                return@setOnClickListener
            }
            launchActivitySafely(Intent(this, HostsEditorActivity::class.java), failureMessage = "打开 Hosts 编辑失败")
        }

        btnNetworkPermission.setOnClickListener {
            if (!ShizukuRepository.isBinderReachable()) {
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
            if (!ShizukuRepository.isBinderReachable()) {
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

        btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        syncHideBackgroundSwitch()
        syncStealthModeSwitch()
        syncAdFreeRewardSwitch()
        refreshCustomBackgroundPreview()
        refreshNotificationAccessHintAsync()
    }

    override fun onDestroy() {
        hotspotStatusJob?.cancel()
        super.onDestroy()
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
        requireRootThen("root-unavailable-cert-install", "安装证书到系统") { granted ->
            if (!granted) return@requireRootThen
            StableDialog.builder(this)
                .setTitle("安装证书到系统")
                .setMessage("将 MITM CA 证书安装到系统信任库。\n\n安装方式：\n- Magisk 模块（推荐）：重启后自动生效，无需重新安装\n- bind mount：当前会话生效，重启后失效\n- 系统写入：直接写入系统分区\n\n安装成功后可拦截更多 HTTPS 广告。\n\n是否继续？")
                .setPositiveButton("安装") { _, _ -> performSystemCertInstall() }
                .setNegativeButton("取消", null)
                .showSafely(this, "confirm-system-cert-install")
        }
    }

    private fun performSystemCertInstall() {
        lifecycleScope.launch {
            try {
                val installer = com.HanFeng.adblocker.shizuku.SystemCertInstaller(this@SettingsActivity)
                val result = withContext(Dispatchers.Default) { installer.installToSystem() }
                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.HanFeng.adblocker.shizuku.SystemCertInstaller.InstallResult.Success -> {
                            val persistentHint = if (result.persistent) {
                                "\n\n此安装方式会在重启后自动生效，无需重新安装。"
                            } else {
                                "\n\n此安装方式在重启后会失效，需要重新安装。建议使用 Magisk 模块方式。"
                            }
                            StableDialog.builder(this@SettingsActivity)
                                .setTitle("安装成功")
                                .setMessage("证书已通过 ${result.method} 方式安装到系统。\n证书 hash: ${result.hashName}$persistentHint")
                                .setPositiveButton("确定", null)
                                .showSafely(this@SettingsActivity, "system-cert-install-success")
                            refreshSystemCertStatus()
                        }
                        is com.HanFeng.adblocker.shizuku.SystemCertInstaller.InstallResult.Failure -> {
                            val diagText = if (result.diagnostics.isNotEmpty()) {
                                "\n\n--- 诊断信息 ---\n" + result.diagnostics.entries.joinToString("\n") { (k, v) ->
                                    "$k:\n  ${v.take(200)}"
                                }
                            } else ""
                            StableDialog.builder(this@SettingsActivity)
                                .setTitle("安装失败")
                                .setMessage("${result.reason}\n\n已尝试: ${result.triedMethods.joinToString(", ")}$diagText")
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
        lifecycleScope.launch {
            val granted = withContext(Dispatchers.Default) {
                com.HanFeng.adblocker.shizuku.SystemCertInstaller.isRootAvailable()
            }
            if (!granted) {
                btnInstallSystemCert.text = "安装证书到系统 (Root)"
                return@launch
            }
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

        // 刷新自动安装证书开关状态
        cbAutoInstallSystemCert.isChecked = FeatureSettingsRepository.isAutoInstallSystemCertEnabled(this)
    }

    private fun showModifyDeviceIdDialog() {
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
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    com.HanFeng.adblocker.shizuku.DeviceIdModifier().readAndroidId()
                }
                if (isFinishing || isDestroyed) return@launch
                val currentId = if (result.exitCode == 0 && result.output.isNotBlank()) result.output.trim() else "(无法读取)"
                etInput.setText(currentId)
                etInput.setSelection(etInput.text.length)
            }
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.HanFeng.adblocker.shizuku.DeviceIdModifier().readAndroidId()
            }
            if (isFinishing || isDestroyed) return@launch
            if (!dialog.isShowing) return@launch
            val currentId = if (result.exitCode == 0 && result.output.isNotBlank()) result.output.trim() else "(无法读取)"
            etInput.setText(currentId)
            etInput.setSelection(etInput.text.length)
            dialog.setMessage("当前 Android ID（16 位十六进制）：")
        }
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
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    com.HanFeng.adblocker.shizuku.DeviceIdModifier().readSerialNo()
                }
                if (isFinishing || isDestroyed) return@launch
                etInput.setText(result.output.trim())
                etInput.setSelection(etInput.text.length)
            }
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.HanFeng.adblocker.shizuku.DeviceIdModifier().readSerialNo()
            }
            if (isFinishing || isDestroyed) return@launch
            if (!dialog.isShowing) return@launch
            val currentSerial = result.output.trim().ifBlank { "(无法读取)" }
            etInput.setText(currentSerial)
            etInput.setSelection(etInput.text.length)
            dialog.setMessage("当前主板序列号：")
        }
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

    // ==================== 主板 ID ====================
    private fun showModifyMainboardIdDialog() {
        val modifier = com.HanFeng.adblocker.shizuku.DeviceIdModifier()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 8.dp)
        }
        val tvCurrent = TextView(this).apply {
            text = "正在读取..."
            setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 8.dp)
            maxLines = 8
        }
        container.addView(tvCurrent)

        data class FieldGroup(
            val propKey: String,
            val label: String,
            val placeholder: String,
            val etInput: EditText,
            val btnRandom: Button,
            val btnReset: Button,
            var originalValue: String? = null
        )

        val cpuidField = FieldGroup(
            propKey = "ro.boot.cpuid",
            label = "CPUID",
            placeholder = "新 CPUID，留空保持不变",
            etInput = EditText(this).apply {
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_CLASS_TEXT
                setTextColor(getColor(com.HanFeng.R.color.hf_text_primary))
                setHintTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
                setBackgroundResource(com.HanFeng.R.drawable.bg_panel)
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            },
            btnRandom = Button(this).apply {
                text = "随机"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4.dp }
            },
            btnReset = Button(this).apply {
                text = "恢复默认"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dp }
            }
        )
        val cellIdField = FieldGroup(
            propKey = "ro.boot.cell_id",
            label = "CELLID",
            placeholder = "新 CELLID，留空保持不变",
            etInput = EditText(this).apply {
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_CLASS_TEXT
                setTextColor(getColor(com.HanFeng.R.color.hf_text_primary))
                setHintTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
                setBackgroundResource(com.HanFeng.R.drawable.bg_panel)
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            },
            btnRandom = Button(this).apply {
                text = "随机"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4.dp }
            },
            btnReset = Button(this).apply {
                text = "恢复默认"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dp }
            }
        )

        fun renderField(group: FieldGroup) {
            val tvLabel = TextView(this).apply {
                text = group.label
                setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
                textSize = 12f
                setPadding(0, 8.dp, 0, 4.dp)
            }
            group.etInput.hint = group.placeholder
            // 输入框
            container.addView(tvLabel)
            container.addView(group.etInput)
            // 按钮行：随机 + 恢复默认，并排放输入框下方
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6.dp, 0, 4.dp)
            }
            row.addView(group.btnRandom)
            row.addView(group.btnReset)
            container.addView(row)

            group.btnRandom.setOnClickListener {
                val seed = group.originalValue
                val generated = if (!seed.isNullOrBlank()) {
                    com.HanFeng.adblocker.shizuku.DeviceIdModifier.generateRandomMainboardId(seed)
                } else {
                    com.HanFeng.adblocker.shizuku.DeviceIdModifier.generateRandomMainboardId(null)
                }
                group.etInput.setText(generated)
                group.etInput.setSelection(group.etInput.text.length)
            }
            group.btnReset.setOnClickListener {
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        modifier.clearMainboardIdForProp(group.propKey)
                    }
                    if (isFinishing || isDestroyed) return@launch
                    if (result.exitCode == 0) {
                        showShortToast("已清除 ${group.label}，重启后恢复默认")
                        group.etInput.setText("")
                    } else {
                        showShortToast("清除失败: ${result.output}")
                    }
                }
            }
        }
        renderField(cpuidField)
        renderField(cellIdField)

        val dialog = StableDialog.builder(this)
            .setTitle("修改 CPUID 和 CELLID")
            .setView(container)
            .setPositiveButton("确认修改") { _, _ ->
                val newCpuid = cpuidField.etInput.text.toString().trim()
                val newCellId = cellIdField.etInput.text.toString().trim()
                if (newCpuid.isBlank() && newCellId.isBlank()) {
                    showShortToast("请至少输入一项")
                    return@setPositiveButton
                }
                executeMainboardIdChange(newCpuid, newCellId)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()

        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                modifier.readMainboardIdForProp("ro.boot.cpuid") to modifier.readMainboardIdForProp("ro.boot.cell_id")
            }
            if (isFinishing || isDestroyed) return@launch
            if (!dialog.isShowing) return@launch
            val cpuidVal = pair.first
            val cellIdVal = pair.second
            val sb = StringBuilder("当前 CPUID/CELLID：")
            sb.append('\n').append("CPUID = ").append(cpuidVal.ifBlank { "(空)" })
            sb.append('\n').append("CELLID = ").append(cellIdVal.ifBlank { "(空)" })
            tvCurrent.text = sb.toString()
            cpuidField.originalValue = cpuidVal.takeIf { it.isNotBlank() }
            cellIdField.originalValue = cellIdVal.takeIf { it.isNotBlank() }
            if (cpuidField.originalValue == null && cellIdField.originalValue == null) {
                tvCurrent.text = "当前 CPUID/CELLID：\n(无法读取，请检查 root 权限)"
            }
        }
    }


    private fun executeMainboardIdChange(newCpuid: String, newCellId: String) {
        lifecycleScope.launch {
            val modifier = com.HanFeng.adblocker.shizuku.DeviceIdModifier()
            val cpuidResult = if (newCpuid.isNotBlank())
                withContext(Dispatchers.Default) { modifier.writeMainboardIdForProp("ro.boot.cpuid", newCpuid) }
            else null
            val cellIdResult = if (newCellId.isNotBlank())
                withContext(Dispatchers.Default) { modifier.writeMainboardIdForProp("ro.boot.cell_id", newCellId) }
            else null

            val sb = StringBuilder()
            if (cpuidResult != null) {
                if (cpuidResult.exitCode == 0) sb.append("CPUID → ").append(newCpuid)
                else sb.append("CPUID 修改失败: ").append(cpuidResult.output.ifBlank { "exit=${cpuidResult.exitCode}" })
            }
            if (cellIdResult != null) {
                if (sb.isNotBlank()) sb.append('\n')
                if (cellIdResult.exitCode == 0) sb.append("CELLID → ").append(newCellId)
                else sb.append("CELLID 修改失败: ").append(cellIdResult.output.ifBlank { "exit=${cellIdResult.exitCode}" })
            }
            val success = (cpuidResult == null || cpuidResult.exitCode == 0) &&
                (cellIdResult == null || cellIdResult.exitCode == 0)
            withContext(Dispatchers.Main) {
                if (success) {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改成功")
                        .setMessage("$sb\n\n重启设备后全面生效。")
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "mainboard-changed")
                } else {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改失败")
                        .setMessage(sb.toString().ifBlank { "修改失败" })
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "mainboard-change-failed")
                }
            }
        }
    }

    // ==================== 手机型号 ====================
    private fun showModifyModelDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 8.dp)
        }

        // 当前值展示区
        val tvCurrent = TextView(this).apply {
            text = "正在读取..."
            setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 8.dp)
            maxLines = 10
        }
        container.addView(tvCurrent)

        // 4 个字段输入框生成器: 留空 = 该字段保留原值
        fun makeField(label: String, hint: String): EditText {
            val tvLabel = TextView(this).apply {
                text = label
                setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
                textSize = 12f
                setPadding(0, 8.dp, 0, 4.dp)
            }
            val et = EditText(this).apply {
                this.hint = hint
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_CLASS_TEXT
                setTextColor(getColor(com.HanFeng.R.color.hf_text_primary))
                setHintTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
                setBackgroundResource(com.HanFeng.R.drawable.bg_panel)
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                tag = label
            }
            container.addView(tvLabel)
            container.addView(et)
            return et
        }

        val etManufacturer = makeField("厂商", "如 OnePlus、Xiaomi、Google")
        val etBrand = makeField("品牌", "如 OnePlus、Xiaomi、Google")
        val etModel = makeField("型号代号", "如 PLZ110、23127PN0CC")
        val etMarketname = makeField("市场名", "如 OnePlus 15 T、小米 15")

        // 恢复默认按钮
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12.dp, 0, 0)
        }
        val btnReset = Button(this).apply {
            text = "恢复默认"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(btnReset)
        container.addView(btnRow)

        val dialog = StableDialog.builder(this)
            .setTitle("修改手机型号")
            .setMessage("全部字段自定义，留空的字段保留原值不动。")
            .setView(container)
            .setPositiveButton("确认修改") { _, _ ->
                val fields = com.HanFeng.adblocker.shizuku.DeviceIdModifier.ModelFields(
                    manufacturer = etManufacturer.text.toString().trim(),
                    brand = etBrand.text.toString().trim(),
                    model = etModel.text.toString().trim(),
                    marketname = etMarketname.text.toString().trim()
                )
                if (fields.manufacturer.isBlank() && fields.brand.isBlank() &&
                    fields.model.isBlank() && fields.marketname.isBlank()
                ) {
                    showShortToast("至少填写一个字段")
                    return@setPositiveButton
                }
                executeModelChange(fields)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        btnReset.setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    com.HanFeng.adblocker.shizuku.DeviceIdModifier().clearModelModule()
                }
                if (isFinishing || isDestroyed) return@launch
                if (result.exitCode == 0) {
                    showShortToast("已清除型号持久化模块，重启后恢复默认")
                } else {
                    showShortToast("清除失败: ${result.output}")
                }
            }
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.HanFeng.adblocker.shizuku.DeviceIdModifier().readModel()
            }
            if (isFinishing || isDestroyed) return@launch
            if (!dialog.isShowing) return@launch
            val lines = result.output.trim().lines().map { it.trim() }.filter { it.contains('=') }
            // prop key -> 中文 label
            fun propLabel(key: String): String = when (key) {
                "ro.product.manufacturer" -> "厂商"
                "ro.product.brand" -> "品牌"
                "ro.product.model" -> "型号代号"
                "ro.product.marketname" -> "市场名"
                else -> key
            }
            val display = lines.joinToString("\n") {
                val k = it.substringBefore('=').trim()
                val v = it.substringAfter('=').trim()
                "${propLabel(k)}：$v"
            }.ifBlank { "(无法读取)" }
            tvCurrent.text = "当前型号信息：\n$display"
            // 把当前 4 字段值填入对应输入框作为起点, 方便用户只改其中一项
            fun fill(et: EditText, propName: String) {
                val v = lines.firstOrNull { it.startsWith("$propName=") }
                    ?.substringAfter('=')?.trim()
                if (!v.isNullOrBlank() && v != "(空)") {
                    et.setText(v)
                    et.setSelection(et.text.length)
                }
            }
            fill(etManufacturer, "ro.product.manufacturer")
            fill(etBrand, "ro.product.brand")
            fill(etModel, "ro.product.model")
            fill(etMarketname, "ro.product.marketname")
        }
    }

    private fun executeModelChange(fields: com.HanFeng.adblocker.shizuku.DeviceIdModifier.ModelFields) {
        lifecycleScope.launch {
            val modifier = com.HanFeng.adblocker.shizuku.DeviceIdModifier()
            val result = withContext(Dispatchers.Default) { modifier.writeModel(fields) }
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    val summary = buildString {
                        if (fields.manufacturer.isNotBlank()) append("厂商: ${fields.manufacturer}\n")
                        if (fields.brand.isNotBlank()) append("品牌: ${fields.brand}\n")
                        if (fields.model.isNotBlank()) append("型号: ${fields.model}\n")
                        if (fields.marketname.isNotBlank()) append("市场名: ${fields.marketname}\n")
                    }.trimEnd()
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改成功")
                        .setMessage("已修改字段:\n$summary\n\n重启设备后全面生效。")
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "model-changed")
                } else {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改失败")
                        .setMessage(result.output.ifBlank { "修改失败 (exit=${result.exitCode})" })
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "model-change-failed")
                }
            }
        }
    }

    // ==================== SN 码 (拨号盘 *#06# 显示的 SN 行) ====================
    private fun showModifySnDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 8.dp)
        }
        val tvCurrent = TextView(this).apply {
            text = "正在读取..."
            setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 8.dp)
            maxLines = 8
        }
        val etInput = EditText(this).apply {
            hint = "如 ABCDE1234FG"
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
        val btnRestore = Button(this).apply {
            text = "恢复备份"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dp }
        }
        btnRow.addView(btnRandom)
        btnRow.addView(btnRestore)
        container.addView(tvCurrent)
        container.addView(etInput)
        container.addView(btnRow)

        val dialog = StableDialog.builder(this)
            .setTitle("修改 SN 码")
            .setView(container)
            .setPositiveButton("确认修改") { _, _ ->
                val newSn = etInput.text.toString().trim()
                if (newSn.isBlank()) {
                    showShortToast("请输入 SN 码")
                    return@setPositiveButton
                }
                executeSnChange(newSn)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        btnRandom.setOnClickListener {
            etInput.setText(com.HanFeng.adblocker.shizuku.DeviceIdModifier.generateRandomSn())
            etInput.setSelection(etInput.text.length)
        }
        btnRestore.setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    com.HanFeng.adblocker.shizuku.DeviceIdModifier().restoreSn()
                }
                if (isFinishing || isDestroyed) return@launch
                val hint = if (result.exitCode == 0) "已恢复备份，重启后生效" else "恢复失败：${result.output}"
                showShortToast(hint)
            }
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.HanFeng.adblocker.shizuku.DeviceIdModifier().readSn()
            }
            if (isFinishing || isDestroyed) return@launch
            if (!dialog.isShowing) return@launch
            val lines = result.output.trim().lines()
            // 不暴露 prop key 给用户 - 只显示 "SN：xxx" 一行
            val snVal = lines.firstOrNull { it.startsWith("gsm.sn=") }
                ?.substringAfter('=')?.trim()
                ?.takeIf { it.isNotBlank() && it != "(空)" }
                ?: lines.firstOrNull { it.startsWith("persist.sys.sn=") }
                    ?.substringAfter('=')?.trim()
                    ?.takeIf { it.isNotBlank() && it != "(空)" }
                ?: lines.firstOrNull { it.startsWith("ril.sn=") }
                    ?.substringAfter('=')?.trim()
                    ?.takeIf { it.isNotBlank() && it != "(空)" }
                ?: lines.firstOrNull()
                    ?.substringAfter('=')?.trim()
                    ?.takeIf { it.isNotBlank() && it != "(空)" }
            tvCurrent.text = "当前 SN：\n${snVal ?: "(无法读取)"}"
            if (!snVal.isNullOrBlank()) {
                etInput.setText(snVal)
                etInput.setSelection(etInput.text.length)
            }
        }
    }

    private fun executeSnChange(newSn: String) {
        lifecycleScope.launch {
            val modifier = com.HanFeng.adblocker.shizuku.DeviceIdModifier()
            val result = withContext(Dispatchers.Default) { modifier.writeSn(newSn) }
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改成功")
                        .setMessage("SN 码已修改为:\n$newSn\n\n注：拨号盘 *#06# 显示需重启系统重新加载；IMEI1/IMEI2/MEID 本功能不在范围。")
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "sn-changed")
                } else {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改失败")
                        .setMessage(result.output.ifBlank { "修改失败 (exit=${result.exitCode})" })
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "sn-change-failed")
                }
            }
        }
    }

    // ==================== IMEI ====================
    private fun showModifyImeiDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 8.dp)
        }
        val tvCurrent = TextView(this).apply {
            text = "正在读取..."
            setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 8.dp)
            maxLines = 8
        }
        val etInput = EditText(this).apply {
            hint = "15 位数字 (末位 Luhn 校验)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(getColor(com.HanFeng.R.color.hf_text_primary))
            setHintTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            setBackgroundResource(com.HanFeng.R.drawable.bg_panel)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }
        val tvHint = TextView(this).apply {
            text = "本工具先尝试 NV 真 IMEI 写入, 失败 (常见 OEM 锁) 自动降级 prop 伪装。" +
                "prop 伪装对 TelephonyManager.getImei() 等 API 在 com.android.phone 重启后才生效; " +
                "拨号盘 *#06# 在部分机型不会刷新。修改 IMEI 风险较高, 已自动备份原值, " +
                "失败可用\"恢复备份\"按钮。"
            setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            textSize = 11f
            setPadding(0, 8.dp, 0, 0)
            maxLines = 10
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
        val btnRestore = Button(this).apply {
            text = "恢复备份"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dp }
        }
        btnRow.addView(btnRandom)
        btnRow.addView(btnRestore)
        container.addView(tvCurrent)
        container.addView(etInput)
        container.addView(tvHint)
        container.addView(btnRow)

        val dialog = StableDialog.builder(this)
            .setTitle("修改 IMEI")
            .setView(container)
            .setPositiveButton("确认修改") { _, _ ->
                val newImei = etInput.text.toString().trim()
                if (newImei.isBlank()) {
                    showShortToast("请输入 IMEI")
                    return@setPositiveButton
                }
                executeImeiChange(newImei)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        btnRandom.setOnClickListener {
            etInput.setText(com.HanFeng.adblocker.shizuku.DeviceIdModifier.generateRandomImei())
            etInput.setSelection(etInput.text.length)
        }
        btnRestore.setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    com.HanFeng.adblocker.shizuku.DeviceIdModifier().restoreImei()
                }
                if (isFinishing || isDestroyed) return@launch
                val hint = if (result.exitCode == 0) "已恢复备份，重启后生效" else "恢复提示：${result.output}"
                showShortToast(hint)
            }
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.HanFeng.adblocker.shizuku.DeviceIdModifier().readImei()
            }
            if (isFinishing || isDestroyed) return@launch
            if (!dialog.isShowing) return@launch
            val lines = result.output.trim().lines()
            // 优先 RIL 真值 (slot0)
            val rilVal = lines.firstOrNull { it.startsWith("RIL(slot0)=") }
                ?.substringAfter('=')?.trim()
                ?.takeIf { it.isNotBlank() && it != "(空)" }
            val propVal = lines.firstOrNull { it.startsWith("gsm.imei=") }
                ?.substringAfter('=')?.trim()
                ?.takeIf { it.isNotBlank() && it != "(空)" }
                ?: lines.firstOrNull { it.startsWith("persist.sys.imei=") }
                    ?.substringAfter('=')?.trim()
                    ?.takeIf { it.isNotBlank() && it != "(空)" }
                ?: lines.firstOrNull { it.startsWith("ril.imei=") }
                    ?.substringAfter('=')?.trim()
                    ?.takeIf { it.isNotBlank() && it != "(空)" }
            val displayVal = rilVal ?: propVal
            tvCurrent.text = "当前 IMEI：\n${displayVal ?: "(无法读取)"}"
            if (!propVal.isNullOrBlank()) {
                etInput.setText(propVal)
                etInput.setSelection(etInput.text.length)
            } else if (!rilVal.isNullOrBlank()) {
                etInput.setText(rilVal)
                etInput.setSelection(etInput.text.length)
            }
        }
    }

    private fun executeImeiChange(newImei: String) {
        lifecycleScope.launch {
            val modifier = com.HanFeng.adblocker.shizuku.DeviceIdModifier()
            val result = withContext(Dispatchers.Default) { modifier.writeImei(newImei) }
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改结果")
                        .setMessage("已尝试修改 IMEI。\n\n${result.output}")
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "imei-changed")
                } else {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改提示")
                        .setMessage(result.output.ifBlank { "修改失败 (exit=${result.exitCode})" })
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "imei-change-failed")
                }
            }
        }
    }

    // ==================== MEID ====================
    private fun showModifyMeidDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 8.dp)
        }
        val tvCurrent = TextView(this).apply {
            text = "正在读取..."
            setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 8.dp)
            maxLines = 8
        }
        val etInput = EditText(this).apply {
            hint = "14 位十六进制 (0-9A-F)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(getColor(com.HanFeng.R.color.hf_text_primary))
            setHintTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            setBackgroundResource(com.HanFeng.R.drawable.bg_panel)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }
        val tvHint = TextView(this).apply {
            text = "MEID NV 写入需 OEM 鉴权, 几乎必失败, 此工具仅做 prop 伪装层与日志展示。" +
                "结果以 prop 伪装生效为准; 原值已自动备份, 失败用\"恢复备份\"回退。"
            setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            textSize = 11f
            setPadding(0, 8.dp, 0, 0)
            maxLines = 10
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
        val btnRestore = Button(this).apply {
            text = "恢复备份"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dp }
        }
        btnRow.addView(btnRandom)
        btnRow.addView(btnRestore)
        container.addView(tvCurrent)
        container.addView(etInput)
        container.addView(tvHint)
        container.addView(btnRow)

        val dialog = StableDialog.builder(this)
            .setTitle("修改 MEID")
            .setView(container)
            .setPositiveButton("确认修改") { _, _ ->
                val newMeid = etInput.text.toString().trim()
                if (newMeid.isBlank()) {
                    showShortToast("请输入 MEID")
                    return@setPositiveButton
                }
                executeMeidChange(newMeid)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        btnRandom.setOnClickListener {
            etInput.setText(com.HanFeng.adblocker.shizuku.DeviceIdModifier.generateRandomMeid())
            etInput.setSelection(etInput.text.length)
        }
        btnRestore.setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    com.HanFeng.adblocker.shizuku.DeviceIdModifier().restoreMeid()
                }
                if (isFinishing || isDestroyed) return@launch
                val hint = if (result.exitCode == 0) "已恢复备份，重启后生效" else "恢复提示：${result.output}"
                showShortToast(hint)
            }
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.HanFeng.adblocker.shizuku.DeviceIdModifier().readMeid()
            }
            if (isFinishing || isDestroyed) return@launch
            if (!dialog.isShowing) return@launch
            val lines = result.output.trim().lines()
            val rilVal = lines.firstOrNull { it.startsWith("RIL(slot0)=") }
                ?.substringAfter('=')?.trim()
                ?.takeIf { it.isNotBlank() && it != "(空)" }
            val propVal = lines.firstOrNull { it.startsWith("gsm.meid=") }
                ?.substringAfter('=')?.trim()
                ?.takeIf { it.isNotBlank() && it != "(空)" }
                ?: lines.firstOrNull { it.startsWith("persist.sys.meid=") }
                    ?.substringAfter('=')?.trim()
                    ?.takeIf { it.isNotBlank() && it != "(空)" }
                ?: lines.firstOrNull { it.startsWith("ril.meid=") }
                    ?.substringAfter('=')?.trim()
                    ?.takeIf { it.isNotBlank() && it != "(空)" }
            val displayVal = rilVal ?: propVal
            tvCurrent.text = "当前 MEID：\n${displayVal ?: "(无法读取)"}"
            if (!propVal.isNullOrBlank()) {
                etInput.setText(propVal)
                etInput.setSelection(etInput.text.length)
            } else if (!rilVal.isNullOrBlank()) {
                etInput.setText(rilVal)
                etInput.setSelection(etInput.text.length)
            }
        }
    }

    private fun executeMeidChange(newMeid: String) {
        lifecycleScope.launch {
            val modifier = com.HanFeng.adblocker.shizuku.DeviceIdModifier()
            val result = withContext(Dispatchers.Default) { modifier.writeMeid(newMeid) }
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改结果")
                        .setMessage("已尝试修改 MEID。\n\n${result.output}")
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "meid-changed")
                } else {
                    StableDialog.builder(this@SettingsActivity)
                        .setTitle("修改提示")
                        .setMessage(result.output.ifBlank { "修改失败 (exit=${result.exitCode})" })
                        .setPositiveButton("确定", null)
                        .showSafely(this@SettingsActivity, "meid-change-failed")
                }
            }
        }
    }

    // ==================== 第三方应用转系统应用 ====================
    /**
     * 列出已安装的第三方应用(provider 走 pm 列表避免 PackageManager 一直取 icon 卡顿)
     * 让用户勾选要转成系统应用的目标
     */
    private fun showConvertSystemAppDialog() {
        // 先弹一个加载框 (因为 listApp 可能要 1-2s)
        val loadingDialog = StableDialog.builder(this)
            .setTitle("加载应用列表...")
            .setMessage("正在枚举已安装第三方应用, 请稍候...")
            .setCancelable(false)
            .setPositiveButton("取消") { _, _ -> }
            .create()
        loadingDialog.show()

        lifecycleScope.launch {
            // 异步列包 — 优先用 RootScriptExecutor 的 pm list packages -3 拿干净列表
            val apps = withContext(Dispatchers.IO) {
                val su = com.HanFeng.adblocker.shizuku.SuSession.getInstance()
                val pm = packageManager
                val res = su.execute("pm list packages -3 2>/dev/null")
                if (res.exitCode == 0) {
                    res.output.lines().mapNotNull { line ->
                        if (!line.startsWith("package:")) return@mapNotNull null
                        val pkg = line.removePrefix("package:").trim()
                        if (pkg.isBlank()) return@mapNotNull null
                        try {
                            val info = pm.getApplicationInfo(pkg, 0)
                            val label = info.loadLabel(pm).toString()
                            Triple(pkg, label, info.sourceDir)
                        } catch (_: Throwable) {
                            null
                        }
                    }.sortedBy { it.second.lowercase() }
                } else {
                    // 退化用 PackageManager 直接列
                    pm.getInstalledApplications(0)
                        .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                        .filter { it.packageName != packageName }
                        .map { Triple(it.packageName, it.loadLabel(pm).toString(), it.sourceDir) }
                        .sortedBy { it.second.lowercase() }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            loadingDialog.dismiss()
            if (apps.isEmpty()) {
                StableDialog.builder(this@SettingsActivity)
                    .setTitle("无可转换的应用")
                    .setMessage("没有检测到第三方应用, 或 root 权限不足")
                    .setPositiveButton("确定", null)
                    .showSafely(this@SettingsActivity, "convert-no-apps")
                return@launch
            }

            showAppPickerDialog(apps)
        }
    }

    private data class ThirdPartyAppEntry(
        val pkg: String,
        val label: String,
        val sourceDir: String
    )

    private fun showAppPickerDialog(apps: List<Triple<String, String, String>>) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 8.dp)
        }

        // 搜索框过滤应用
        val etFilter = android.widget.EditText(this).apply {
            hint = "搜索应用名/包名"
            setSingleLine(true)
            setBackgroundResource(com.HanFeng.R.drawable.bg_panel)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            setTextColor(getColor(com.HanFeng.R.color.hf_text_primary))
            setHintTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
        }
        container.addView(etFilter)

        // 列表容器 (用户可滚动选择)
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listLayout)
        container.addView(scroll)

        // 风险告知
        val tvWarn = android.widget.TextView(this).apply {
            text = "此操作将把所选应用以 root 权限复制到 /system/priv-app 并卸载用户态副本。\n" +
                "1) 该应用的当前登录态会丢失, 需重新登录;\n" +
                "2) 大多数 Android 14+ A/B 设备 /system 只读 squashfs, remount 失败会失败;\n" +
                "3) 转换成功需重启系统才完全生效;\n" +
                "4) HyperOS / MIUI 等定制系统可能仍会杀该后台, 转为系统应用只是降低概率;\n" +
                "5) 可在 动作完成后用 恢复用户态 按钮撤销。"
            setTextColor(getColor(com.HanFeng.R.color.hf_text_secondary))
            textSize = 11f
            setPadding(0, 8.dp, 0, 8.dp)
            maxLines = 10
        }
        container.addView(tvWarn)

        val selectionMap = HashMap<String, ThirdPartyAppEntry>() // pkg -> entry

        fun rebuildList(filter: String) {
            listLayout.removeAllViews()
            selectionMap.clear()
            val lower = filter.lowercase().trim()
            apps.forEach { (pkg, label, sourceDir) ->
                if (lower.isNotBlank() &&
                    !label.lowercase().contains(lower) &&
                    !pkg.lowercase().contains(lower)
                ) return@forEach
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 6.dp, 0, 6.dp)
                }
                val cb = android.widget.CheckBox(this).apply {
                    text = "$label\n（$pkg）"
                    textSize = 12f
                    setOnCheckedChangeListener { _, isChecked ->
                        val entry = ThirdPartyAppEntry(pkg, label, sourceDir)
                        if (isChecked) selectionMap[pkg] = entry else selectionMap.remove(pkg)
                    }
                }
                row.addView(cb)
                listLayout.addView(row)
            }
        }
        rebuildList("")
        etFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rebuildList(s?.toString() ?: "") }
        })

        val dialog = StableDialog.builder(this)
            .setTitle("选择要转换为系统应用的应用")
            .setView(container)
            .setPositiveButton("确认转换") { _, _ ->
                if (selectionMap.isEmpty()) {
                    showShortToast("请至少勾选一个应用")
                    return@setPositiveButton
                }
                executeConvertSystemAppBatch(selectionMap.values.toList())
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复选中应用的普通态") { _, _ ->
                if (selectionMap.isEmpty()) {
                    showShortToast("请至少勾选一个应用")
                    return@setNeutralButton
                }
                executeRevertSystemAppBatch(selectionMap.values.toList())
            }
            .create()
        dialog.show()
    }

    private fun executeConvertSystemAppBatch(targets: List<ThirdPartyAppEntry>) {
        val progressSb = StringBuilder()
        val progressDialog = StableDialog.builder(this)
            .setTitle("正在转换...")
            .setMessage("正在处理...")
            .setCancelable(false)
            .setPositiveButton("确定", null)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val converter = com.HanFeng.adblocker.shizuku.SystemAppConverter()
            val reports = withContext(Dispatchers.IO) {
                targets.map { entry ->
                    withContext(Dispatchers.Default) { converter.convertToSystemApp(this@SettingsActivity, entry.pkg) }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            progressDialog.dismiss()

            val sb = StringBuilder()
            sb.appendLine("共处理 ${targets.size} 个应用, ${reports.count { it.allSucceed }} 个成功\n")
            for (r in reports) {
                sb.append("─── ").append(r.packageName).appendLine(" ───")
                sb.appendLine(r.summary)
                sb.appendLine()
            }
            val okCount = reports.count { it.allSucceed }
            val needsReboot = reports.any { it.needsReboot }
            StableDialog.builder(this@SettingsActivity)
                .setTitle("转换结果 (${okCount}/${targets.size})" + if (needsReboot) " 需重启" else "")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .showSafely(this@SettingsActivity, "convert-batch-done")
            progressSb.append(sb)
        }
    }

    private fun executeRevertSystemAppBatch(targets: List<ThirdPartyAppEntry>) {
        val progressDialog = StableDialog.builder(this)
            .setTitle("正在还原...")
            .setMessage("正在处理...")
            .setCancelable(false)
            .setPositiveButton("确定", null)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val converter = com.HanFeng.adblocker.shizuku.SystemAppConverter()
            val reports = withContext(Dispatchers.IO) {
                targets.map { entry ->
                    withContext(Dispatchers.Default) { converter.revertFromSystemApp(this@SettingsActivity, entry.pkg) }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            progressDialog.dismiss()

            val sb = StringBuilder()
            sb.appendLine("共处理 ${targets.size} 个应用\n")
            for (r in reports) {
                sb.append("─── ").append(r.packageName).appendLine(" ───")
                sb.appendLine(r.summary)
                sb.appendLine()
            }
            StableDialog.builder(this@SettingsActivity)
                .setTitle("还原结果")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .showSafely(this@SettingsActivity, "revert-batch-done")
        }
    }


    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun needsLegacyStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * 异步确认 Root 权限已授权，避免在主线程同步等待 su 提示导致 UI 卡死。
     * 已开 session 时立即回调 granted；未开时后台异步触发 SuSession.open()，
     * 最多等待 60s 弹出 root 授权框，结果通过 onResult 在主线程回调。
     * 弹出 loading toast 提示用户当前正在请求授权。
     */
    private fun requireRootThen(
        tag: String,
        featureName: String,
        onResult: (granted: Boolean) -> Unit
    ) {
        lifecycleScope.launch {
            // 已授权直接走
            if (com.HanFeng.adblocker.shizuku.DeviceIdModifier.isRootAvailable()) {
                onResult(true)
                return@launch
            }
            // 否则给出 loading 提示，异步等待 root 授权
            val toastJob = lifecycleScope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(250)
                if (lifecycleScope.isActive) showShortToast("请允许 Root 授权以使用 $featureName")
            }
            val granted = withContext(Dispatchers.Default) {
                com.HanFeng.adblocker.shizuku.DeviceIdModifier.isRootAvailable()
            }
            toastJob.cancel()
            if (isFinishing || isDestroyed) return@launch
            if (!granted) {
                StableDialog.builder(this@SettingsActivity)
                    .setTitle("需要 Root 权限")
                    .setMessage("$featureName 需要 Root 权限，请在 Root 授权框同意授权后再试。")
                    .setPositiveButton("确定", null)
                    .showSafely(this@SettingsActivity, tag)
            }
            onResult(granted)
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
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
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

    private var hotspotStatusJob: Job? = null

    private fun startHotspotStatusRefresh() {
        hotspotStatusJob?.cancel()
        // 使用 repeatOnLifecycle 当 Activity 进入 STOPPED 时自动取消，回到 STARTED 时再启动，
        // 避免 Activity 退到后台仍然 5s 轮询 shell SuSession.execute
        hotspotStatusJob = lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshHotspotStatus()
                    delay(5_000L)
                }
            }
        }
    }

    private suspend fun refreshHotspotStatus() {
        if (!FeatureSettingsRepository.isHotspotBlockEnabled(this)) {
            tvHotspotDevices.text = "设备数: 0"
            tvHotspotBlocked.text = "拦截: 0"
            tvHotspotRuntime.text = "运行: 0分钟"
            return
        }

        val mode = FeatureSettingsRepository.getHotspotBlockMode(this)
        if (mode == "dns") {
            // getHotspotStatus 会调多次 SuSession.execute 同步 shell 命令，必须放 IO；同步主线程会导致 ANR
            val status: com.HanFeng.adblocker.shizuku.HotspotInterceptor.HotspotStatus
            val startTime: Long
            try {
                status = withContext(Dispatchers.IO) {
                    com.HanFeng.adblocker.shizuku.HotspotInterceptor.getHotspotStatus(this@SettingsActivity)
                }
                startTime = FeatureSettingsRepository.getHotspotStartTime(this@SettingsActivity)
            } catch (e: Exception) {
                // Silent fail for status refresh
                return
            }
            if (isFinishing || isDestroyed) return
            try {
                tvHotspotDevices.text = "设备数: ${status.connectedDevices.size}"
                tvHotspotBlocked.text = "拦截: ${status.blockedQueries}"
                val minutes = if (startTime > 0) {
                    (System.currentTimeMillis() - startTime) / 60_000
                } else 0
                tvHotspotRuntime.text = "运行: ${minutes}分钟"

                FeatureSettingsRepository.updateHotspotDeviceCount(this, status.connectedDevices.size)
                if (status.blockedQueries > 0) {
                    FeatureSettingsRepository.incrementHotspotBlockedCount(this, 0)
                }
            } catch (e: Exception) {
                // Silent fail for status refresh
            }
        } else {
            val deviceCount = FeatureSettingsRepository.getHotspotDeviceCount(this)
            val blockedCount = FeatureSettingsRepository.getHotspotBlockedCount(this)
            val startTime = FeatureSettingsRepository.getHotspotStartTime(this)
            val minutes = if (startTime > 0) {
                (System.currentTimeMillis() - startTime) / 60_000
            } else 0
            tvHotspotDevices.text = "设备数: $deviceCount"
            tvHotspotBlocked.text = "拦截: $blockedCount"
            tvHotspotRuntime.text = "运行: ${minutes}分钟"
        }
    }

    companion object {
        private const val SHIZUKU_STATUS_REFRESH_INTERVAL_MILLIS = 3_000L
    }

    private val backgroundPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val appContext = applicationContext
        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开选择的图片")
                val bgDir = File(appContext.filesDir, "backgrounds")
                if (!bgDir.exists()) bgDir.mkdirs()
                val bgFile = File(bgDir, "bg_" + System.currentTimeMillis() + ".jpg")
                withContext(Dispatchers.IO) {
                    inputStream.use { input ->
                        bgFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                FeatureSettingsRepository.appendCustomBackgroundPath(appContext, bgFile.absolutePath)
                refreshCustomBackgroundPreview()
                showShortToast("背景图已添加")
            } catch (e: Exception) {
                showShortToast("添加背景图失败：${e.message}")
            }
        }
    }

    private fun chooseBackgroundImage() {
        backgroundPickerLauncher.launch("image/*")
    }

    private fun removeCustomBackground() {
        val appContext = applicationContext
        // 删除所有已上传的背景图文件
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val paths = FeatureSettingsRepository.getCustomBackgroundPaths(appContext)
                paths.forEach { runCatching { java.io.File(it).delete() } }
                // 清空 SP 中的列表
                FeatureSettingsRepository.setCustomBackgroundPath(appContext, null)
            }
            runOnUiThread {
                refreshCustomBackgroundPreview()
                showShortToast("已移除所有自定义背景图")
            }
        }
    }

    private fun refreshCustomBackgroundPreview() {
        val count = FeatureSettingsRepository.getCustomBackgroundPaths(this).size
        if (count > 0) {
            textCustomBgPreview.text = "已上传 $count 张背景图，点击下方缩略图切换"
            btnRemoveBackground.visibility = View.VISIBLE
        } else {
            textCustomBgPreview.text = "未设置自定义背景图，点击「选择图片」上传"
            btnRemoveBackground.visibility = View.GONE
        }
        refreshBackgroundSwitcher()
    }

    private fun refreshBackgroundSwitcher() {
        val row = findViewById<LinearLayout?>(R.id.bgSwitcherRow) ?: return
        val appContext = applicationContext
        row.removeAllViews()
        val paths = FeatureSettingsRepository.getCustomBackgroundPaths(appContext)
        val activeIdx = FeatureSettingsRepository.getActiveBackgroundIndex(appContext)
        val inflater = LayoutInflater.from(appContext)

        for ((index, path) in paths.withIndex()) {
            val item = inflater.inflate(R.layout.item_background_switcher, row, false)
            val thumb = item.findViewById<ImageView>(R.id.ivThumb)
            val activeMarker = item.findViewById<View>(R.id.viewActive)
            val removeBtn = item.findViewById<ImageButton>(R.id.btnRemove)
            lifecycleScope.launch {
                val drawable = withContext(Dispatchers.IO) {
                    runCatching {
                        decodeSampledBitmapDrawable(appContext, path, 256, 256)
                    }.getOrNull()
                }
                if (drawable != null) thumb.setImageDrawable(drawable)
            }
            if (index == activeIdx) activeMarker.visibility = View.VISIBLE
            thumb.setOnClickListener {
                FeatureSettingsRepository.setActiveBackgroundIndex(appContext, index)
                refreshCustomBackgroundPreview()
                showShortToast("已切换为图片 ${index + 1}")
            }
            removeBtn.setOnClickListener {
                val appCtx = appContext
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { java.io.File(path).delete() }
                        FeatureSettingsRepository.removeCustomBackgroundPath(appCtx, path)
                    }
                    runOnUiThread {
                        refreshCustomBackgroundPreview()
                        showShortToast("已删除该背景")
                    }
                }
            }
            row.addView(item)
        }
    }

    private data class IdleShutdownOption(val label: String, val millis: Long)

    private fun setupIdleShutdownSetting() {
        val options = listOf(
            IdleShutdownOption("1 分钟", 60_000L),
            IdleShutdownOption("2 分钟", 2 * 60_000L),
            IdleShutdownOption("5 分钟", 5 * 60_000L),
            IdleShutdownOption("10 分钟", 10 * 60_000L),
            IdleShutdownOption("15 分钟", 15 * 60_000L),
            IdleShutdownOption("30 分钟", 30 * 60_000L),
            IdleShutdownOption("60 分钟", 60 * 60_000L),
            IdleShutdownOption("自定义", -2L),
            IdleShutdownOption("关闭", -1L)
        )
        val labels = options.map { it.label }.toMutableList()
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerIdleShutdownInterval.adapter = spinnerAdapter

        val enabled = FeatureSettingsRepository.isIdleShutdownEnabled(this)
        val savedThreshold = FeatureSettingsRepository.getIdleShutdownThreshold(this)
        val isPreset = options.any { it.millis == savedThreshold && it.millis > 0 }
        val selected = when {
            !enabled || savedThreshold <= 0 -> options.lastIndex
            isPreset -> options.indexOfFirst { it.millis == savedThreshold }
            else -> options.indexOfFirst { it.millis == -2L }
        }.coerceAtLeast(0)
        spinnerIdleShutdownInterval.setSelection(selected, false)
        updateIdleShutdownCustomLabel(labels, spinnerAdapter, savedThreshold)

        switchIdleShutdown.isChecked = enabled
        textIdleShutdownDesc.text = if (enabled)
            "未开启拦截且 APP 在后台空闲一定时长后自动结束进程，省电省内存"
        else
            "已关闭，APP 后台将一直常驻"

        spinnerIdleShutdownInterval.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val opt = options[position]
                when {
                    opt.millis == -2L -> {
                        showCustomIdleShutdownInputDialog(labels, options, spinnerAdapter)
                    }
                    opt.millis == -1L -> {
                        switchIdleShutdown.isChecked = false
                        FeatureSettingsRepository.setIdleShutdownEnabled(this@SettingsActivity, false)
                        textIdleShutdownDesc.text = "已关闭，APP 后台将一直常驻"
                    }
                    else -> {
                        switchIdleShutdown.isChecked = true
                        FeatureSettingsRepository.setIdleShutdownEnabled(this@SettingsActivity, true)
                        FeatureSettingsRepository.setIdleShutdownThreshold(this@SettingsActivity, opt.millis)
                        textIdleShutdownDesc.text = "未开启拦截且 APP 在后台空闲一定时长后自动结束进程，省电省内存"
                    }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        switchIdleShutdown.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val currPos = spinnerIdleShutdownInterval.selectedItemPosition
                val currOpt = options.getOrNull(currPos)
                if (currOpt == null || currOpt.millis <= 0) {
                    spinnerIdleShutdownInterval.setSelection(options.indexOfFirst { it.millis == 5 * 60_000L })
                } else {
                    FeatureSettingsRepository.setIdleShutdownEnabled(this, true)
                }
                textIdleShutdownDesc.text = "未开启拦截且 APP 在后台空闲一定时长后自动结束进程，省电省内存"
            } else {
                FeatureSettingsRepository.setIdleShutdownEnabled(this, false)
                textIdleShutdownDesc.text = "已关闭，APP 后台将一直常驻"
            }
        }
    }

    private fun showCustomIdleShutdownInputDialog(
        labels: MutableList<String>,
        options: List<IdleShutdownOption>,
        spinnerAdapter: ArrayAdapter<String>
    ) {
        val editText = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "请输入 1-180 分钟"
            filters = arrayOf(android.text.InputFilter.LengthFilter(3))
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(editText)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
            .setTitle("自定义空闲时长")
            .setMessage("输入 1 到 180 之间的数字(分钟)")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val raw = editText.text?.toString().orEmpty().trim()
                val minutes = raw.toIntOrNull()
                if (minutes == null || minutes < 1 || minutes > 180) {
                    showShortToast("请输入 1-180 之间的数字")
                    restoreIdleShutdownSpinnerSelection(labels, options)
                    return@setPositiveButton
                }
                val millis = minutes.toLong() * 60_000L
                FeatureSettingsRepository.setIdleShutdownThreshold(this, millis)
                FeatureSettingsRepository.setIdleShutdownEnabled(this, true)
                switchIdleShutdown.isChecked = true
                textIdleShutdownDesc.text = "未开启拦截且 APP 在后台空闲一定时长后自动结束进程，省电省内存"
                updateIdleShutdownCustomLabel(labels, spinnerAdapter, millis)
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                restoreIdleShutdownSpinnerSelection(labels, options)
                val saved = FeatureSettingsRepository.getIdleShutdownThreshold(this)
                updateIdleShutdownCustomLabel(labels, spinnerAdapter, saved)
            }
            .setCancelable(false)
            .show()
    }

    private fun restoreIdleShutdownSpinnerSelection(
        labels: MutableList<String>,
        options: List<IdleShutdownOption>
    ) {
        val enabled = FeatureSettingsRepository.isIdleShutdownEnabled(this)
        val savedThreshold = FeatureSettingsRepository.getIdleShutdownThreshold(this)
        val restoreIdx = when {
            !enabled || savedThreshold <= 0 -> options.lastIndex
            options.any { it.millis == savedThreshold && it.millis > 0 } ->
                options.indexOfFirst { it.millis == savedThreshold }
            else -> options.indexOfFirst { o -> o.millis == -2L }
        }
        spinnerIdleShutdownInterval.setSelection(restoreIdx.coerceAtLeast(0), false)
    }

    private fun updateIdleShutdownCustomLabel(
        labels: MutableList<String>,
        spinnerAdapter: ArrayAdapter<String>,
        savedThreshold: Long
    ) {
        val customIdx = labels.size - 2 // "自定义"在 options 倒数第二项
        if (customIdx < 0) return
        val presetMillis = setOf(
            60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L,
            15 * 60_000L, 30 * 60_000L, 60 * 60_000L
        )
        labels[customIdx] = if (savedThreshold > 0 && savedThreshold !in presetMillis) {
            "自定义 ${savedThreshold / 60_000L} 分钟"
        } else {
            "自定义"
        }
        spinnerAdapter.notifyDataSetChanged()
    }

    private fun setupNotificationAdBlockSetting() {
        val enabled = FeatureSettingsRepository.isNotificationAdBlockEnabled(this)
        // 在设置 listener 之前设置 isChecked，避免 listener 被初始设置触发
        switchNotificationAdBlock.isChecked = enabled
        // 进入设置页时异步检查通知访问授权，避免 Settings.Secure.getString 阻塞主线程 (跨 binder IPC)
        refreshNotificationAccessHintAsync()
        switchNotificationAdBlock.setOnCheckedChangeListener { _, isChecked ->
            FeatureSettingsRepository.setNotificationAdBlockEnabled(this, isChecked)
            if (isChecked) {
                lifecycleScope.launch {
                    if (!isNotificationListenerEnabledAsync()) {
                        showShortToast("已开启，但还需要授予通知访问权限才能真正生效")
                    }
                    refreshNotificationAccessHintAsync()
                }
            } else {
                refreshNotificationAccessHintAsync()
            }
        }

        btnOpenNotificationAccess.setOnClickListener {
            // 跳转到系统"通知访问权限"页让用户勾选本 App
            runCatching {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.onFailure {
                showShortToast("无法打开通知访问设置，请手动前往 设置 → 通知访问")
            }
        }
    }

    private fun refreshNotificationAccessHintAsync() {
        val switchOnNow = switchNotificationAdBlock.isChecked
        if (!switchOnNow) {
            // 开关关着，直接更新文案即可，不需要拿监听状态
            textNotificationAdBlockDesc.text = "开关未开启；可授予通知访问权限后让监听器一并接管已治理 APP 的通知"
            btnOpenNotificationAccess.visibility = View.VISIBLE
            return
        }
        lifecycleScope.launch {
            val listenerReady = isNotificationListenerEnabledAsync()
            if (!isActive) return@launch
            textNotificationAdBlockDesc.text = if (listenerReady) {
                "实时监听通知栏，已治理 APP 与含广告关键字的通知会被自动移除"
            } else {
                "已开启，但通知访问权限未授予。请点击右侧按钮授予后系统才会推送通知给本 App"
            }
            btnOpenNotificationAccess.visibility = if (listenerReady) View.INVISIBLE else View.VISIBLE
        }
    }

    private suspend fun isNotificationListenerEnabledAsync(): Boolean = withContext(Dispatchers.IO) {
        val flat = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return@withContext false
        val expectedComponent = packageName + "/" + com.HanFeng.service.AdNotificationListenerService::class.java.name
        flat.split(":").any { it == expectedComponent }
    }

    /**
     * 解码图片为缩略图并裁成圆角 Drawable。
     * 相比直接 BitmapFactory.decodeFile（原图 4K 可能瞬间吃 64MB），通过 inSampleSize + inTargetDensity，
     * 让解码后的 Bitmap 只占目标尺寸的内存（256x256 ≈ 256KB），避免缩略图列表把内存撑爆。
     */
    private fun decodeSampledBitmapDrawable(
        context: android.content.Context,
        path: String,
        reqWidth: Int,
        reqHeight: Int
    ): android.graphics.drawable.Drawable? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = ((bounds.outWidth / reqWidth).coerceAtLeast(bounds.outHeight / reqHeight).coerceAtLeast(1))
            .coerceAtMost(8)
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return null
        return androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(context.resources, bmp).apply {
            isCircular = false
            cornerRadius = 12f
        }
    }
}
