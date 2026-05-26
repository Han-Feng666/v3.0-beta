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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.HttpDecryptRouteRepository
import com.HanFeng.data.HttpsDecryptRouteRepository
import com.HanFeng.data.HttpsMitmRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.databinding.ActivityMainBinding
import com.HanFeng.security.CertificateAuthorityManager
import com.HanFeng.service.AdBlockVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_PREFS = "permission_flow"
        private const val KEY_FIRST_LAUNCH_CHECK_DONE = "first_launch_check_done"
    }

    private lateinit var binding: ActivityMainBinding
    private var pendingVpnStartAfterPermission = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            pendingVpnStartAfterPermission = false
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.pager.adapter = MainPagerAdapter(this)
        binding.pager.offscreenPageLimit = 1
        binding.pager.currentItem = 1
        requestRequiredPermissionsOnFirstLaunch()
        preloadBundledRules()
        restoreVpnIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        CertificateAuthorityManager.syncInstalledState(this)
        restoreVpnIfNeeded()
    }

    private fun restoreVpnIfNeeded() {
        if (!FeatureSettingsRepository.isAdBlockEnabled(this)) return
        if (AdBlockVpnService.isRunning) return
        runCatching { VpnService.prepare(this) }
            .onSuccess { prepareIntent ->
                if (prepareIntent != null) {
                    LogRepository.append(this, "VPN restore pending: system permission confirmation required")
                    return
                }
                mainHandler.removeCallbacksAndMessages("restore-vpn")
                mainHandler.postAtTime(
                    {
                        if (!isFinishing && !isDestroyed && FeatureSettingsRepository.isAdBlockEnabled(this) && !AdBlockVpnService.isRunning) {
                            startVpnService(silent = true)
                        }
                    },
                    "restore-vpn",
                    SystemClock.uptimeMillis() + 300
                )
            }
            .onFailure {
                LogRepository.append(this, "VPN restore check failed: ${it.message ?: it.javaClass.simpleName}")
            }
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

    private fun requestRequiredPermissionsOnFirstLaunch() {
        val prefs = getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FIRST_LAUNCH_CHECK_DONE, false)) return
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_CHECK_DONE, true).apply()

        lifecycleScope.launch {
            val hasAppListAccess = withContext(Dispatchers.Default) {
                WhitelistRepository.hasAppListAccess(applicationContext)
            }
            if (!hasAppListAccess) {
                showAppListPermissionDialog()
            }
            if (needsNotificationPermission()) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun preloadBundledRules() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                RuleRepository.ensureBundledReferenceRules(applicationContext)
                RuleRepository.prewarmCaches(applicationContext)
            }
            LogRepository.append(this@MainActivity, "Rules prewarmed")
        }
    }

    private fun continueVpnStartFlow() {
        runCatching {
            VpnService.prepare(this)
        }.onSuccess { prepareIntent ->
            if (prepareIntent != null) {
                vpnLauncher.launch(prepareIntent)
            } else {
                startVpnService()
            }
        }.onFailure {
            LogRepository.append(this, "VPN prepare failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(this, "无法申请 VPN 权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    private fun startVpnService(silent: Boolean = false) {
        val serviceIntent = Intent(this, AdBlockVpnService::class.java)
        runCatching {
            FeatureSettingsRepository.setAdBlockEnabled(this, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }.onSuccess {
            if (!silent) {
                Toast.makeText(this, "正在开启拦截", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            FeatureSettingsRepository.setAdBlockEnabled(this, false)
            AdBlockVpnService.isRunning = false
            LogRepository.append(this, "Start VPN service failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(this, "开启拦截失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVpnService() {
        AdBlockVpnService.isRunning = false
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

    fun openTrafficCardPage() {
        openExternal("https://h5.lot-ml.com/ProductEn/Index/120d6424545c4be5")
    }

    fun joinQqGroup() {
        openExternal("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=573309536&card_type=group&source=qrcode")
    }

    fun shareLogs() {
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

    private fun showAppListPermissionDialog() {
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
            .setTitle("需要应用列表权限")
            .setMessage("当前手机可能限制了应用列表读取，黑白名单与应用识别可能不完整。请到系统设置中手动允许“获取应用列表”或类似权限。")
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
