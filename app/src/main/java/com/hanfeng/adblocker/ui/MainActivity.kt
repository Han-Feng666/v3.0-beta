package com.HanFeng.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpDecryptRouteRepository
import com.HanFeng.data.HttpsDecryptRouteRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RemoteRuleSourceRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.databinding.ActivityMainBinding
import com.HanFeng.security.CertificateAuthorityManager
import com.HanFeng.adblocker.compat.DeviceCompatibilityHelper
import com.HanFeng.adblocker.compat.DeviceCompatibilityHelper.RomType
import com.HanFeng.ui.SuspiciousDomainsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : BaseActivity() {
    companion object {
        private const val PERMISSION_PREFS = "permission_flow"
        private const val KEY_FIRST_LAUNCH_CHECK_DONE = "first_launch_check_done"
        private const val KEY_LAST_SUSPICIOUS_PROMPT_AT = "last_suspicious_prompt_at"
        private const val KEY_BATTERY_OPT_PROMPT_AT = "last_battery_opt_prompt_at"
        private const val BATTERY_OPT_PROMPT_COOLDOWN = 7L * 24L * 60L * 60L * 1000L
        const val EXTRA_AUTO_REMOVE_FROM_RECENTS = "extra_auto_remove_from_recents"
    }

    private lateinit var binding: ActivityMainBinding
    private var pendingVpnStartAfterPermission = false
    private var suspiciousPromptShown = false
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != ShizukuRepository.REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        handleShizukuPermissionResult(granted) {
            refreshHomeStatus()
        }
    }
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startVpnService(userInitiated = true)
        } else {
            pendingVpnStartAfterPermission = false
            FeatureSettingsRepository.setAdBlockEnabled(this, false)
            NetworkKernel.markStopped()
            refreshHomeStatus()
            showShortToast("未授予 VPN 权限，无法开启拦截")
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            showNotificationPermissionDeniedDialog()
        }
        if (pendingVpnStartAfterPermission) {
            pendingVpnStartAfterPermission = false
            continueVpnStartFlow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            (application as? com.HanFeng.HanFengApp)?.writeStartupLog("MainActivity.onCreate start")
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        runCatching {
            (application as? com.HanFeng.HanFengApp)?.writeStartupLog("Shizuku listener attached")
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupMainContent(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        scheduleSuspiciousDomainPrompt()
        runCatching {
            (application as? com.HanFeng.HanFengApp)?.writeStartupLog("MainActivity.onCreate complete")
        }
    }

    override fun onResume() {
        super.onResume()
        syncMitmCertificateStateIfNeeded()
        ensureVpnServiceMatchesUserIntent()
        refreshHomeStatus()
        checkAndPromptBatteryOptimization()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun ensureVpnServiceMatchesUserIntent() {
        if (!FeatureSettingsRepository.isAdBlockEnabled(this)) return
        if (NetworkKernel.isRunning()) return
        val prepareIntent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (prepareIntent == null) {
            FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
            NetworkKernel.start(this, userInitiated = false)
        } else {
            FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, true)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun setupMainContent(savedInstanceState: Bundle?) {
        val isTabletLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            resources.configuration.smallestScreenWidthDp >= 600
        val pager = binding.root.findViewById<androidx.viewpager2.widget.ViewPager2?>(R.id.pager)
        if (!isTabletLandscape) {
            pager?.isSaveEnabled = false
            pager?.adapter = null
            pager?.adapter = MainPagerAdapter(this)
            pager?.offscreenPageLimit = 1
            pager?.setCurrentItem(1, false)
            return
        }
        if (savedInstanceState != null) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.rulesContainer, RulesFragment())
            .replace(R.id.homeContainer, HomeFragment())
            .replace(R.id.statsContainer, StatsFragment())
            .commitNowAllowingStateLoss()
    }

    private fun syncMitmCertificateStateIfNeeded() {
        if (!FeatureSettingsRepository.isHttpDecryptEnabled(this)) return
        val wasInstalled = HttpsMitmRepository.isCertificateInstalled(this)
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.Default) {
                CertificateAuthorityManager.syncInstalledState(applicationContext)
            }
            if (!installed || isFinishing || isDestroyed) return@launch
            if (!wasInstalled) {
                NetworkKernel.reloadIfRunning(this@MainActivity)
            }
            refreshHomeStatus()
        }
    }

    fun requestToggleVpn() {
        if (NetworkKernel.isRunning()) {
            stopVpnService()
            return
        }
        if (needsNotificationPermission()) {
            pendingVpnStartAfterPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueVpnStartFlow()
    }

    private fun continueVpnStartFlow() {
        runCatching {
            VpnService.prepare(this)
        }.onSuccess { prepareIntent ->
                if (prepareIntent != null) {
                    vpnLauncher.launch(prepareIntent)
                } else {
                    startVpnService(userInitiated = true)
                }
            }.onFailure {
                LogRepository.append(this, "VPN prepare failed: ${it.message ?: it.javaClass.simpleName}")
                FeatureSettingsRepository.setAdBlockEnabled(this, false)
                NetworkKernel.markStopped()
                refreshHomeStatus()
                showShortToast("无法申请 VPN 权限")
            }
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded() {
        val prefs = getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FIRST_LAUNCH_CHECK_DONE, false)) return
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_CHECK_DONE, true).apply()
        if (needsNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun scheduleRemoteRuleSync() {
        binding.root.post {
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        RuleRepository.ensureSecurityRuleSource(applicationContext)
                    }
                }
                if (!RemoteRuleSourceRepository.shouldSyncOnAppLaunch(applicationContext)) {
                    return@launch
                }
                runCatching {
                    RemoteRuleSourceRepository.syncEnabledSources(applicationContext)
                }.onSuccess { results ->
                    val successCount = results.count { it.success }
                    val enabledCount = results.size
                    if (successCount > 0 && NetworkKernel.isRunning()) {
                        NetworkKernel.reload(this@MainActivity)
                    }
                    LogRepository.append(
                        this@MainActivity,
                        "Remote rule sync checked on app launch: success=$successCount/$enabledCount lastSyncAt=${RemoteRuleSourceRepository.getLastSyncAt(applicationContext)}"
                    )
                }.onFailure {
                    LogRepository.append(this@MainActivity, "Remote rule sync failed on app launch: ${it.message ?: it.javaClass.simpleName}")
                }
            }
        }
    }

    private fun checkAndPromptBatteryOptimization() {
        if (isFinishing || isDestroyed || batteryOptimizationPromptShown) return
        if (DeviceCompatibilityHelper.isIgnoringBatteryOptimizations(this)) return
        val prefs = getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        val lastPromptAt = prefs.getLong(KEY_BATTERY_OPT_PROMPT_AT, 0L)
        val now = System.currentTimeMillis()
        if (lastPromptAt > 0L && now - lastPromptAt < BATTERY_OPT_PROMPT_COOLDOWN) return
        batteryOptimizationPromptShown = true
        val romType = DeviceCompatibilityHelper.detectRomType()
        val isChineseRom = DeviceCompatibilityHelper.isChineseRom()
        val brand = Build.BRAND.take(1).uppercase() + Build.BRAND.drop(1)
        runCatching {
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
                .setTitle("保持后台运行")
                .setMessage(
                    buildString {
                        append("为确保广告拦截持续生效，建议关闭 $brand 对此应用的电池优化限制。")
                        if (isChineseRom) {
                            append("\n\n国产手机通常还需要：")
                            append("\n- 允许自启动")
                            append("\n- 锁定后台任务")
                            append("\n- 关闭省电策略")
                        }
                    }
                )
                .setPositiveButton("关闭电池优化") { _, _ ->
                    rememberBatteryOptimizationPrompt(now)
                    batteryOptimizationPromptShown = false
                    DeviceCompatibilityHelper.requestBatteryOptimizationExemption(this@MainActivity)
                }
                .setNeutralButton("自启动设置") { _, _ ->
                    rememberBatteryOptimizationPrompt(now)
                    batteryOptimizationPromptShown = false
                    val opened = DeviceCompatibilityHelper.openAutoStartSettings(this@MainActivity)
                    if (!opened) {
                        showShortToast("无法打开自启动设置，请手动到系统设置中设置")
                    }
                }
                .setNegativeButton("稍后") { _, _ ->
                    rememberBatteryOptimizationPrompt(now)
                    batteryOptimizationPromptShown = false
                }
                .create()
                .also { dialog ->
                    if (romType == RomType.SAMSUNG || romType == RomType.GOOGLE || romType == RomType.STOCK_ANDROID) {
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
                    }
                }
                .showSafely(this, "Show battery optimization dialog failed")
        }.onFailure {
            batteryOptimizationPromptShown = false
        }
    }

    private fun rememberBatteryOptimizationPrompt(timestamp: Long) {
        getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BATTERY_OPT_PROMPT_AT, timestamp)
            .apply()
    }

    private fun scheduleSuspiciousDomainPrompt() {
        binding.root.post {
            lifecycleScope.launch {
                val pendingDomains = withContext(Dispatchers.Default) {
                    RuleRepository.getPendingSuspiciousDomainsForPrompt(applicationContext)
                }
                if (pendingDomains.isEmpty()) return@launch
                val latestSeenAt = pendingDomains.maxOfOrNull { it.lastSeenAt } ?: 0L
                val prefs = getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
                val lastPromptAt = prefs.getLong(KEY_LAST_SUSPICIOUS_PROMPT_AT, 0L)
                if (latestSeenAt <= lastPromptAt) return@launch
                if (isFinishing || isDestroyed || suspiciousPromptShown) return@launch
                suspiciousPromptShown = true
                showSuspiciousDomainPrompt(pendingDomains)
            }
        }
    }

    private fun showSuspiciousDomainPrompt(pendingDomains: List<RuleRepository.SuspiciousDomainSample>) {
        val count = pendingDomains.size
        val sampleText = pendingDomains.take(5).joinToString("\n") { sample ->
            "- ${sample.domain}"
        }
        val latestSeenAt = pendingDomains.maxOfOrNull { it.lastSeenAt } ?: System.currentTimeMillis()
        runCatching {
            val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
                .setTitle("发现疑似广告域名")
                .setMessage(
                    buildString {
                        append("上次运行期间抓到了 ")
                        append(count)
                        append(" 条高置信度疑似广告域名。是否直接加入拦截？")
                        if (sampleText.isNotBlank()) {
                            append("\n\n示例：\n")
                            append(sampleText)
                        }
                    }
                )
                .setPositiveButton("加入拦截", null)
                .setNeutralButton("稍后处理") { _, _ ->
                    rememberSuspiciousPrompt(latestSeenAt)
                    suspiciousPromptShown = false
                }
                .setNegativeButton("查看列表") { _, _ ->
                    rememberSuspiciousPrompt(latestSeenAt)
                    suspiciousPromptShown = false
                    runCatching {
                        startActivity(SuspiciousDomainsActivity.createIntent(this))
                    }.onFailure {
                        showShortToast("打开疑似广告域名列表失败")
                    }
                }
                .create()
            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    lifecycleScope.launch {
                        val addedCount = withContext(Dispatchers.Default) {
                            val candidateDomains = pendingDomains
                                .asSequence()
                                .map { it.domain }
                                .distinct()
                                .toList()
                            RuleRepository.addRules(
                                applicationContext,
                                candidateDomains,
                                com.HanFeng.model.RuleSource.MANUAL
                            ).size
                        }
                        rememberSuspiciousPrompt(latestSeenAt)
                        suspiciousPromptShown = false
                        dialog.dismiss()
                        if (addedCount > 0) {
                            if (NetworkKernel.isRunning()) {
                                NetworkKernel.reload(this@MainActivity)
                            }
                            showLongToast("已加入 $addedCount 条疑似广告域名到拦截规则")
                        } else {
                            showShortToast("这些疑似广告域名已经在规则里了")
                        }
                    }
                }
            }
            dialog.showSafely(this, "Show suspicious domain prompt dialog failed") ?: return@runCatching
        }.onFailure {
            suspiciousPromptShown = false
            LogRepository.append(this, "Show suspicious domain prompt failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun rememberSuspiciousPrompt(timestamp: Long) {
        getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SUSPICIOUS_PROMPT_AT, timestamp)
            .apply()
    }

    private fun startVpnService(silent: Boolean = false, userInitiated: Boolean = false) {
        runCatching {
            FeatureSettingsRepository.setAdBlockEnabled(this, true)
            FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
            refreshHomeStatus()
            requestNotificationPermissionIfNeeded()
            NetworkKernel.start(this, userInitiated = userInitiated)
        }.onSuccess {
            if (!silent) {
                showShortToast("已请求开启拦截")
            }
        }.onFailure {
            FeatureSettingsRepository.setAdBlockEnabled(this, false)
            NetworkKernel.markStopped()
            refreshHomeStatus()
            LogRepository.append(this, "Start VPN service failed: ${it.message ?: it.javaClass.simpleName}")
            showShortToast("开启拦截失败")
        }
    }

    private fun stopVpnService() {
        NetworkKernel.markStopped()
        FeatureSettingsRepository.setAdBlockEnabled(this, false)
        FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
        refreshHomeStatus()
        NetworkKernel.stop(this)
        showShortToast("已停止拦截")
    }

    fun showGuideDialog() {
        runCatching {
            startActivity(
                GuideActivity.createIntent(
                    this,
                    "使用说明",
                    ""
                )
            )
        }.onFailure {
            LogRepository.append(this, "Guide dialog failed: ${it.message ?: it.javaClass.simpleName}")
            showShortToast("打开使用说明失败")
        }
    }

    fun openWhitelist() {
        runCatching {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }.onFailure {
            LogRepository.append(this, "Open whitelist failed: ${it.message ?: it.javaClass.simpleName}")
            showShortToast("打开白名单失败")
        }
    }

    fun openCoexistApps() {
        runCatching {
            startActivity(
                Intent(this, WhitelistActivity::class.java).putExtra(
                    WhitelistActivity.EXTRA_MODE,
                    WhitelistActivity.MODE_COEXIST
                )
            )
        }.onFailure {
            LogRepository.append(this, "Open coexist apps failed: ${it.message ?: it.javaClass.simpleName}")
            showShortToast("打开加速器共存失败")
        }
    }

    fun openSettings() {
        runCatching {
            startActivity(Intent(this, SettingsActivity::class.java))
        }.onFailure {
            LogRepository.append(this, "Open settings failed: ${it.message ?: it.javaClass.simpleName}")
            showShortToast("打开设置失败")
        }
    }

    fun requestShizukuAccess() {
        handleShizukuAccessRequest {
            refreshHomeStatus()
        }
    }

    private fun warmShizukuServices() {
        warmShizukuServicesBlocking()
    }

    private fun refreshHomeStatus() {
        refreshHomeStatus(supportFragmentManager.fragments)
    }

    private fun refreshHomeStatus(fragments: List<androidx.fragment.app.Fragment>) {
        fragments.forEach { fragment ->
            if (fragment is HomeFragment && fragment.isAdded) {
                fragment.refreshStatusFromHost()
            }
            val childFragments = fragment.childFragmentManager.fragments
            if (childFragments.isNotEmpty()) {
                refreshHomeStatus(childFragments)
            }
        }
    }

    fun openTrafficCardPage() {
        openExternalUrl("https://h5.lot-ml.com/ProductEn/Index/120d6424545c4be5")
    }

    fun joinQqGroup() {
        openExternalUrl("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=573309536&card_type=group&source=qrcode")
    }

    fun shareLogs() {
        val uri = LogRepository.exportZip(this) ?: run {
            showShortToast("日志导出失败")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "导出日志"))
        }.onFailure {
            showShortToast("打开日志导出失败")
        }
    }

    fun openRuleDownloadPage() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            showShortToast("系统剪贴板当前不可用")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("提取码", "aehi"))
        showShortToast("提取码已复制")
        openExternalUrl("https://hanfengnb.lanzoul.com/b0j1elsrg")
    }

    private fun openAppDetailsSettings() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }.onFailure {
            showShortToast("无法打开应用设置")
        }
    }

    private fun showNotificationPermissionDeniedDialog() {
        StableDialog.builder(this)
            .setTitle("通知权限未开启")
            .setMessage("前台服务通知需要通知权限才能更稳定显示。若已拒绝，请到系统设置中手动允许该权限。")
            .setPositiveButton("去设置") { _: DialogInterface, _: Int -> openAppDetailsSettings() }
            .setNegativeButton("稍后再说", null)
            .showSafely(this, "Show notification permission denied dialog failed")
    }

    fun onHttpDecryptSettingChanged(enabled: Boolean, onFinished: (Boolean) -> Unit = {}) {
        if (!enabled) {
            HttpDecryptRouteRepository.clear(this)
            HttpsDecryptRouteRepository.clear(this)
            HttpsMitmRepository.clearRuntimeState(this)
            NetworkKernel.reloadIfRunning(this)
            onFinished(true)
            return
        }
        if (needsCertificateStoragePermission()) {
            pendingHttpDecryptEnableAfterPermission = true
            pendingHttpDecryptCompletion = onFinished
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        prepareHttpsMitmCertificate(onFinished)
    }

    private fun prepareHttpsMitmCertificate(onFinished: (Boolean) -> Unit) {
        lifecycleScope.launch {
            val generated = withContext(Dispatchers.Default) {
                CertificateAuthorityManager.ensureCaInstalledFiles(applicationContext)
            }
            generated.onSuccess { cert ->
                HttpsMitmRepository.saveCertificateExportPath(this@MainActivity, cert.downloadDisplayPath)
                HttpsMitmRepository.clearRuntimeState(this@MainActivity)
                NetworkKernel.reloadIfRunning(this@MainActivity)
                val certificateInstalled = HttpsMitmRepository.isCertificateInstalled(this@MainActivity) ||
                    CertificateAuthorityManager.syncInstalledState(this@MainActivity)
                if (!certificateInstalled) {
                    HttpsMitmRepository.markCertificateInstallRequested(this@MainActivity)
                }
                onFinished(true)
                if (certificateInstalled) {
                    showShortToast("MITM 模式已开启")
                } else {
                    // 检查是否是证书重新生成
                    if (cert.newlyGenerated) {
                        showInstallCaDialog(cert, isNewCertificate = true)
                    } else {
                        showInstallCaDialog(cert, isNewCertificate = false)
                    }
                }
            }.onFailure {
                LogRepository.append(this@MainActivity, "Prepare HTTPS MITM certificate failed: ${it.message ?: it.javaClass.simpleName}")
                showShortToast("MITM 证书准备失败")
                onFinished(false)
            }
        }
    }

    private fun showInstallCaDialog(certificate: CertificateAuthorityManager.GeneratedCertificate, isNewCertificate: Boolean = false) {
        val downloadPathText = certificate.downloadDisplayPath ?: "Download/HanFeng/HanFeng.crt"
        if (isFinishing || isDestroyed) {
            LogRepository.append(this, "Skip certificate install dialog: activity not ready")
            return
        }
        runCatching {
            StableDialog.builder(this)
                .setTitle("安装MITM证书")
                .setMessage(
                    buildString {
                        if (isNewCertificate) {
                            append("检测到证书已更新，需要重新安装新证书。\n\n")
                            append("这可能是因为：\n")
                            append("- 卸载后重新安装了 App\n")
                            append("- App 数据被清空\n")
                            append("- 证书文件损坏已自动修复\n\n")
                        } else {
                            append("检测到您还未安装 MITM 证书。\n\n")
                        }
                        append("证书文件位置：\n")
                        append(downloadPathText)
                        append("\n\n安装步骤：\n1. 打开手机设置。\n2. 搜索安装证书或进入安全 - 密码与隐私 - 更多安全设置中的证书安装入口。\n3. 选择 CA 证书或从存储设备安装证书。\n4. 前往 Download/HanFeng/ 目录，选择 HanFeng.crt 完成安装。\n5. 安装完成后返回应用，应用会自动检查证书状态。\n\n")
                        append("提示：如果是从应用商店更新 App，不需要重新安装证书。只有卸载后重装才需要。")
                    }
                )
                .setPositiveButton("我知道了", null)
                .setNegativeButton("稍后安装", null)
                .showSafely(this, "Show install CA dialog failed")
        }.onFailure {
            LogRepository.append(this, "Show certificate install dialog failed: ${it.message ?: it.javaClass.simpleName}")
            if (isNewCertificate) {
                showLongToast("证书已更新，请到系统设置手动安装新证书：$downloadPathText")
            } else {
                showLongToast("证书已导出到 $downloadPathText，请到系统设置手动安装")
            }
        }
    }

    private fun needsCertificateStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }

    private var pendingHttpDecryptEnableAfterPermission = false
    private var pendingHttpDecryptCompletion: ((Boolean) -> Unit)? = null
    private var batteryOptimizationPromptShown = false
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingHttpDecryptEnableAfterPermission) {
            val completion = pendingHttpDecryptCompletion
            pendingHttpDecryptEnableAfterPermission = false
            pendingHttpDecryptCompletion = null
            if (granted) {
                prepareHttpsMitmCertificate(completion ?: {})
            } else {
                showShortToast("需要存储权限以导出证书")
                completion?.invoke(false)
            }
        }
    }

}
