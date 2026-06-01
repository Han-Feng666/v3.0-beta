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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpDecryptRouteRepository
import com.HanFeng.data.HttpsDecryptRouteRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RemoteRuleSourceRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuConnectionOwnerRepository
import com.HanFeng.databinding.ActivityMainBinding
import com.HanFeng.security.CertificateAuthorityManager
import com.HanFeng.service.AdBlockVpnService
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
        const val EXTRA_AUTO_REMOVE_FROM_RECENTS = "extra_auto_remove_from_recents"
    }

    private lateinit var binding: ActivityMainBinding
    private var pendingVpnStartAfterPermission = false
    private var suspiciousPromptShown = false
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != ShizukuRepository.REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if (granted) {
            warmShizukuServices()
        }
        val message = if (granted) "Shizuku 授权成功" else "Shizuku 授权失败"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        refreshHomeStatus()
    }
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startVpnService(userInitiated = true)
        } else {
            pendingVpnStartAfterPermission = false
            FeatureSettingsRepository.setAdBlockEnabled(this, false)
            AdBlockVpnService.isRunning = false
            refreshHomeStatus()
            Toast.makeText(this, "未授予 VPN 权限，无法开启拦截", Toast.LENGTH_SHORT).show()
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
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupMainContent(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        scheduleRemoteRuleSync()
        scheduleSuspiciousDomainPrompt()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshHomeStatus()
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
            pager?.adapter = MainPagerAdapter(this)
            pager?.offscreenPageLimit = 1
            pager?.currentItem = 1
            return
        }
        if (savedInstanceState != null) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.rulesContainer, RulesFragment())
            .replace(R.id.homeContainer, HomeFragment())
            .replace(R.id.statsContainer, StatsFragment())
            .commitNowAllowingStateLoss()
    }

    fun requestToggleVpn() {
        if (AdBlockVpnService.isRunning) {
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
                AdBlockVpnService.isRunning = false
                refreshHomeStatus()
                Toast.makeText(this, "无法申请 VPN 权限", Toast.LENGTH_SHORT).show()
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
                if (!RemoteRuleSourceRepository.shouldSyncOnAppLaunch(applicationContext)) {
                    return@launch
                }
                runCatching {
                    RemoteRuleSourceRepository.syncEnabledSources(applicationContext)
                }.onSuccess { results ->
                    val successCount = results.count { it.success }
                    val enabledCount = results.size
                    if (successCount > 0 && AdBlockVpnService.isRunning) {
                        startService(Intent(this@MainActivity, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
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
                    startActivity(SuspiciousDomainsActivity.createIntent(this))
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
                            if (AdBlockVpnService.isRunning) {
                                startService(Intent(this@MainActivity, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
                            }
                            Toast.makeText(this@MainActivity, "已加入 $addedCount 条疑似广告域名到拦截规则", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "这些疑似广告域名已经在规则里了", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            dialog.show()
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
        val serviceIntent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
            putExtra(AdBlockVpnService.EXTRA_USER_INITIATED, userInitiated)
        }
        runCatching {
            FeatureSettingsRepository.setAdBlockEnabled(this, true)
            FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
            refreshHomeStatus()
            requestNotificationPermissionIfNeeded()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }.onSuccess {
            if (!silent) {
                Toast.makeText(this, "已请求开启拦截", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            FeatureSettingsRepository.setAdBlockEnabled(this, false)
            AdBlockVpnService.isRunning = false
            refreshHomeStatus()
            LogRepository.append(this, "Start VPN service failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(this, "开启拦截失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVpnService() {
        AdBlockVpnService.isRunning = false
        FeatureSettingsRepository.setAdBlockEnabled(this, false)
        FeatureSettingsRepository.setVpnRevokedByOtherVpn(this, false)
        refreshHomeStatus()
        startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP))
        Toast.makeText(this, "已停止拦截", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "打开使用说明失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWhitelist() {
        startActivity(Intent(this, WhitelistActivity::class.java))
    }

    fun openCoexistApps() {
        startActivity(
            Intent(this, WhitelistActivity::class.java).putExtra(
                WhitelistActivity.EXTRA_MODE,
                WhitelistActivity.MODE_COEXIST
            )
        )
    }

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    fun requestShizukuAccess() {
        if (!AppSettingsRepository.isShizukuEnabled(this)) {
            Toast.makeText(this, "Shizuku 增强已在设置中关闭", Toast.LENGTH_SHORT).show()
            refreshHomeStatus()
            return
        }
        val status = ShizukuRepository.getStatus(this)
        val serviceHealthy = if (status.installed && status.binderAlive) {
            runCatching {
                ShizukuConnectionOwnerRepository.ensureBound(this)
                ShizukuAdControlRepository.ensureBound(this)
                ShizukuAdControlRepository.checkServiceHealth(this)
            }.getOrDefault(false)
        } else {
            false
        }
        when {
            !status.installed -> {
                showShizukuGuideDialog(
                    title = "需要先安装 Shizuku",
                    message = "Shizuku 增强模式需要先安装并启动 Shizuku。安装完成后，再回到寒枫进行授权。",
                    positiveLabel = "前往下载"
                ) {
                    ShizukuRepository.openDownloadPage(this)
                }
            }
            !status.binderAlive -> {
                showShizukuGuideDialog(
                    title = "需要先启动 Shizuku",
                    message = "请先在 Shizuku App 中启动服务。Android 11 及以上通常可通过无线调试启动，已 Root 设备也可以直接启动。",
                    positiveLabel = "我知道了"
                ) {
                    refreshHomeStatus()
                }
            }
            serviceHealthy && !status.permissionGranted -> {
                Toast.makeText(this, "Shizuku 已可用，当前按兼容模式接入增强能力", Toast.LENGTH_SHORT).show()
                refreshHomeStatus()
            }
            !status.permissionStateKnown -> {
                showShizukuGuideDialog(
                    title = "Shizuku 权限状态异常",
                    message = "当前 Shizuku Binder 可以连通，但权限状态读取异常。请重新进入 Shizuku 后再返回寒枫，必要时更换兼容性更好的版本。",
                    positiveLabel = "我知道了"
                ) {
                    refreshHomeStatus()
                }
            }
            status.permissionGranted -> {
                warmShizukuServices()
                Toast.makeText(this, "Shizuku 已授权，增强服务会继续完成连接", Toast.LENGTH_SHORT).show()
                refreshHomeStatus()
            }
            ShizukuRepository.requestPermission() -> {
                Toast.makeText(this, "正在请求 Shizuku 授权", Toast.LENGTH_SHORT).show()
            }
            else -> {
                showShizukuGuideDialog(
                    title = "Shizuku 需要手动授权",
                    message = "请确认 Shizuku 已运行，并在弹出的授权界面中允许寒枫访问。如果之前拒绝过，需要先在 Shizuku 中清理授权状态。",
                    positiveLabel = "我知道了"
                ) {
                    refreshHomeStatus()
                }
            }
        }
    }

    private fun showShizukuGuideDialog(title: String, message: String, positiveLabel: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveLabel) { _, _ -> action() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun warmShizukuServices() {
        runCatching { ShizukuConnectionOwnerRepository.ensureBound(this) }
        runCatching { ShizukuAdControlRepository.ensureBound(this) }
        runCatching { ShizukuAdControlRepository.checkServiceHealth(this) }
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
        openExternal("https://h5.lot-ml.com/ProductEn/Index/120d6424545c4be5")
    }

    fun joinQqGroup() {
        openExternal("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=573309536&card_type=group&source=qrcode")
    }

    fun shareLogs() {
        val exportedPath = LogRepository.exportZipToDownloads(this)
        if (!exportedPath.isNullOrBlank()) {
            Toast.makeText(this, "日志已导出到 $exportedPath", Toast.LENGTH_LONG).show()
            return
        }
        val uri = LogRepository.exportZip(this)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出日志"))
    }

    fun openRuleDownloadPage() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("提取码", "aehi"))
        Toast.makeText(this, "提取码已复制", Toast.LENGTH_SHORT).show()
        openExternal("https://hanfengnb.lanzoul.com/b0j1elsrg")
    }

    private fun openExternal(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "未找到可用应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppDetailsSettings() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }.onFailure {
            Toast.makeText(this, "无法打开应用设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNotificationPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
            .setTitle("通知权限未开启")
            .setMessage("前台服务通知需要通知权限才能更稳定显示。若已拒绝，请到系统设置中手动允许该权限。")
            .setPositiveButton("去设置") { _: DialogInterface, _: Int -> openAppDetailsSettings() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    fun onHttpDecryptSettingChanged(enabled: Boolean, onFinished: (Boolean) -> Unit = {}) {
        if (!enabled) {
            HttpDecryptRouteRepository.clear(this)
            HttpsDecryptRouteRepository.clear(this)
            HttpsMitmRepository.clearRuntimeState(this)
            if (AdBlockVpnService.isRunning) {
                startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
            }
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
                if (AdBlockVpnService.isRunning) {
                    startService(Intent(this@MainActivity, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
                }
                val certificateInstalled = HttpsMitmRepository.isCertificateInstalled(this@MainActivity) ||
                    CertificateAuthorityManager.syncInstalledState(this@MainActivity)
                if (!certificateInstalled) {
                    HttpsMitmRepository.markCertificateInstallRequested(this@MainActivity)
                }
                onFinished(true)
                if (certificateInstalled) {
                    Toast.makeText(this@MainActivity, "MITM 模式已开启", Toast.LENGTH_SHORT).show()
                } else {
                    showInstallCaDialog(cert)
                }
            }.onFailure {
                LogRepository.append(this@MainActivity, "Prepare HTTPS MITM certificate failed: ${it.message ?: it.javaClass.simpleName}")
                Toast.makeText(this@MainActivity, "MITM 证书准备失败", Toast.LENGTH_SHORT).show()
                onFinished(false)
            }
        }
    }

    private fun showInstallCaDialog(certificate: CertificateAuthorityManager.GeneratedCertificate) {
        val downloadPathText = certificate.downloadDisplayPath ?: "Download/HanFeng/HanFeng.crt"
        if (isFinishing || isDestroyed) {
            LogRepository.append(this, "Skip certificate install dialog: activity not ready")
            return
        }
        runCatching {
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
                .setTitle("安装 MITM 证书")
                .setMessage(
                    buildString {
                        append(if (certificate.newlyGenerated) "本地 CA 证书已生成。" else "已复用现有 CA 证书。")
                        append("\n\n证书文件位置：\n")
                        append(downloadPathText)
                        append("\n\n安装步骤：\n1. 打开手机“设置”。\n2. 搜索“安装证书”或进入“安全 / 密码与隐私 / 更多安全设置”中的证书安装入口。\n3. 选择“CA 证书”或“从存储设备安装证书”。\n4. 前往 Download/HanFeng/ 目录，选择 HanFeng.crt 完成安装。\n5. 安装完成后返回应用，应用会自动检查证书状态。")
                    }
                )
                .setPositiveButton("我知道了", null)
                .setNegativeButton("稍后安装", null)
                .show()
        }.onFailure {
            LogRepository.append(this, "Show certificate install dialog failed: ${it.message ?: it.javaClass.simpleName}")
            if (certificate.newlyGenerated) {
                Toast.makeText(this, "证书已导出到 Download/HanFeng/HanFeng.crt，请到系统设置手动安装", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun needsCertificateStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }

    private var pendingHttpDecryptEnableAfterPermission = false
    private var pendingHttpDecryptCompletion: ((Boolean) -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingHttpDecryptEnableAfterPermission) {
            val completion = pendingHttpDecryptCompletion
            pendingHttpDecryptEnableAfterPermission = false
            pendingHttpDecryptCompletion = null
            if (granted) {
                prepareHttpsMitmCertificate(completion ?: {})
            } else {
                Toast.makeText(this, "需要存储权限以导出证书", Toast.LENGTH_SHORT).show()
                completion?.invoke(false)
            }
        }
    }

}
