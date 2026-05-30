package com.HanFeng.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.HanFeng.R
import com.HanFeng.data.AppSettingsRepository
import com.HanFeng.data.ShizukuAdControlCatalog
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuConnectionOwnerRepository
import com.HanFeng.data.ShizukuRepository
import com.HanFeng.service.AdBlockVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : BaseActivity() {

    private lateinit var switchShizuku: Switch
    private lateinit var switchHideBackground: Switch
    private lateinit var textShizukuStatus: TextView
    private lateinit var btnShizukuAdControl: Button
    private lateinit var btnShizukuAdControlBatch: Button
    private lateinit var btnCoexistSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchShizuku = findViewById(R.id.switchUseShizuku)
        switchHideBackground = findViewById(R.id.switchHideBackground)
        textShizukuStatus = findViewById(R.id.textShizukuStatus)
        btnShizukuAdControl = findViewById(R.id.btnShizukuAdControl)
        btnShizukuAdControlBatch = findViewById(R.id.btnShizukuAdControlBatch)
        btnCoexistSettings = findViewById(R.id.btnCoexistSettings)

        switchShizuku.isChecked = AppSettingsRepository.isShizukuEnabled(this)
        switchHideBackground.isChecked = AppSettingsRepository.isHideBackgroundEnabled(this)
        updateShizukuActionState()

        switchShizuku.setOnCheckedChangeListener { _, isChecked ->
            AppSettingsRepository.setShizukuEnabled(this, isChecked)
            if (isChecked) {
                warmShizukuServices()
            }
            updateShizukuActionState()
        }
        switchHideBackground.setOnCheckedChangeListener { _, isChecked ->
            AppSettingsRepository.setHideBackgroundEnabled(this, isChecked)
            applyHideBackgroundPolicy(isChecked)
        }
        btnShizukuAdControl.setOnClickListener {
            openShizukuAdControlCatalog()
        }
        btnShizukuAdControlBatch.setOnClickListener {
            openBatchShizukuAdControlDialog()
        }
        btnCoexistSettings.setOnClickListener {
            startActivity(
                Intent(this, WhitelistActivity::class.java).putExtra(
                    WhitelistActivity.EXTRA_MODE,
                    WhitelistActivity.MODE_COEXIST
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuActionState()
    }

    private fun updateShizukuActionState() {
        val shizukuEnabled = AppSettingsRepository.isShizukuEnabled(this)
        val shizukuReady = ShizukuRepository.canUseEnhancedMode(this)
        if (shizukuEnabled && shizukuReady) {
            warmShizukuServices()
        }
        btnShizukuAdControl.isEnabled = shizukuEnabled && shizukuReady
        btnShizukuAdControlBatch.isEnabled = shizukuEnabled && shizukuReady
        btnShizukuAdControl.alpha = if (btnShizukuAdControl.isEnabled) 1f else 0.55f
        btnShizukuAdControlBatch.alpha = if (btnShizukuAdControlBatch.isEnabled) 1f else 0.55f
        textShizukuStatus.text = buildShizukuStatusText(shizukuEnabled, shizukuReady)
    }

    private fun warmShizukuServices() {
        runCatching { ShizukuConnectionOwnerRepository.ensureBound(this) }
        runCatching { ShizukuAdControlRepository.ensureBound(this) }
        runCatching { ShizukuAdControlRepository.checkServiceHealth(this) }
    }

    private fun buildShizukuStatusText(shizukuEnabled: Boolean, shizukuReady: Boolean): String {
        if (!shizukuEnabled) return "Shizuku 状态：未启用"
        val status = ShizukuRepository.getStatus(this)
        return when {
            !status.installed -> "Shizuku 状态：未安装"
            !status.binderAlive -> "Shizuku 状态：未启动"
            !status.permissionGranted -> "Shizuku 状态：未授权"
            shizukuReady && ShizukuAdControlRepository.checkServiceHealth(this) -> "Shizuku 状态：已连接 (${status.runningMode})"
            else -> "Shizuku 状态：服务待绑定 (${status.runningMode})"
        }
    }

    private fun openShizukuAdControlCatalog() {
        if (!ensureShizukuReady()) return
        val presets = ShizukuAdControlCatalog.allPresets()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择治理项")
            .setItems(ShizukuAdControlCatalog.labels().toTypedArray()) { _, which ->
                showPresetActionDialog(presets[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openBatchShizukuAdControlDialog() {
        if (!ensureShizukuReady()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("批量治理")
            .setItems(arrayOf("停用已安装推广项", "恢复已安装推广项", "暂停已安装推广项", "恢复暂停的推广项")) { _, which ->
                when (which) {
                    0 -> runBatchShizukuAdControl(mode = BatchAdControlMode.DISABLE)
                    1 -> runBatchShizukuAdControl(mode = BatchAdControlMode.ENABLE)
                    2 -> runBatchShizukuAdControl(mode = BatchAdControlMode.SUSPEND)
                    3 -> runBatchShizukuAdControl(mode = BatchAdControlMode.UNSUSPEND)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runBatchShizukuAdControl(mode: BatchAdControlMode) {
        val presets = ShizukuAdControlCatalog.allPresets()
        val installedPresets = presets.filter { preset ->
            ShizukuAdControlRepository.queryPackageStatus(this, preset.packageName).installed
        }
        if (installedPresets.isEmpty()) {
            showOperationResult("未发现可处理的已安装推广项")
            return
        }
        val operated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        installedPresets.forEach { preset ->
            val requested = when (mode) {
                BatchAdControlMode.DISABLE -> ShizukuAdControlRepository.disablePackage(this, preset.packageName)
                BatchAdControlMode.ENABLE -> ShizukuAdControlRepository.enablePackage(this, preset.packageName)
                BatchAdControlMode.SUSPEND -> ShizukuAdControlRepository.suspendPackage(this, preset.packageName)
                BatchAdControlMode.UNSUSPEND -> ShizukuAdControlRepository.unsuspendPackage(this, preset.packageName)
            }
            val status = ShizukuAdControlRepository.queryPackageStatus(this, preset.packageName)
            val succeeded = requested && when (mode) {
                BatchAdControlMode.DISABLE -> {
                    status.enabledState != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                }
                BatchAdControlMode.ENABLE -> {
                    status.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                        status.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }
                BatchAdControlMode.SUSPEND -> status.suspended
                BatchAdControlMode.UNSUSPEND -> !status.suspended
            }
            if (succeeded) {
                operated += preset.title
            } else {
                failed += preset.title
            }
        }
        val actionLabel = when (mode) {
            BatchAdControlMode.DISABLE -> "停用"
            BatchAdControlMode.ENABLE -> "恢复"
            BatchAdControlMode.SUSPEND -> "暂停"
            BatchAdControlMode.UNSUSPEND -> "恢复暂停"
        }
        val message = buildString {
            if (operated.isNotEmpty()) {
                append("批量${actionLabel}成功 ${operated.size} 项：")
                append(operated.joinToString("、"))
            }
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("处理失败 ${failed.size} 项：")
                append(failed.joinToString("、"))
            }
            if (isEmpty()) {
                append("批量${actionLabel}失败")
            }
        }
        showOperationResult(message)
    }

    private enum class BatchAdControlMode {
        DISABLE,
        ENABLE,
        SUSPEND,
        UNSUSPEND
    }

    private fun ensureShizukuReady(): Boolean {
        if (!AppSettingsRepository.isShizukuEnabled(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Shizuku 未启用")
                .setMessage("请先开启设置中的 Shizuku 增强。")
                .setPositiveButton("我知道了", null)
                .show()
            return false
        }
        val status = ShizukuRepository.getStatus(this)
        if (!status.installed || !status.binderAlive || !status.permissionGranted) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Shizuku 暂不可用")
                .setMessage("请先安装、启动并授权 Shizuku，然后再使用系统推广治理。")
                .setPositiveButton("我知道了", null)
                .show()
            return false
        }
        warmShizukuServices()
        if (!ShizukuAdControlRepository.checkServiceHealth(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Shizuku 服务连接失败")
                .setMessage("Shizuku 已授权，但增强服务还未成功绑定。请稍后重试，或重新进入 Shizuku 后再回来。")
                .setPositiveButton("我知道了", null)
                .show()
            return false
        }
        return true
    }

    private fun showPresetActionDialog(preset: ShizukuAdControlCatalog.Preset) {
        val status = ShizukuAdControlRepository.queryPackageStatus(this, preset.packageName)
        val canDisable = status.installed && status.enabledState != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        val canEnable = status.installed && status.enabledState != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val canSuspend = status.installed && !status.suspended
        val canUnsuspend = status.installed && status.suspended
        val message = buildString {
            append(preset.description)
            append("\n\n分类：")
            append(preset.category)
            append("\n包名：")
            append(preset.packageName)
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
        if (canDisable) {
            actions += "停用" to {
                val requested = ShizukuAdControlRepository.disablePackage(this, preset.packageName)
                val refreshed = ShizukuAdControlRepository.queryPackageStatus(this, preset.packageName)
                val success = requested && refreshed.enabledState != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                showOperationResult(if (success) "停用成功" else "停用失败，请确认该项目支持停用")
            }
        }
        if (canEnable) {
            actions += "恢复" to {
                val requested = ShizukuAdControlRepository.enablePackage(this, preset.packageName)
                val refreshed = ShizukuAdControlRepository.queryPackageStatus(this, preset.packageName)
                val success = requested && (
                    refreshed.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                        refreshed.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    )
                showOperationResult(if (success) "恢复成功" else "恢复失败，请确认该项目仍然存在")
            }
        }
        if (canSuspend || canUnsuspend) {
            actions += (if (status.suspended) "恢复暂停" else "暂停") to {
                val requested = if (status.suspended) {
                    ShizukuAdControlRepository.unsuspendPackage(this, preset.packageName)
                } else {
                    ShizukuAdControlRepository.suspendPackage(this, preset.packageName)
                }
                val refreshed = ShizukuAdControlRepository.queryPackageStatus(this, preset.packageName)
                val success = requested && if (status.suspended) !refreshed.suspended else refreshed.suspended
                val actionText = if (status.suspended) "恢复暂停" else "暂停"
                showOperationResult(if (success) "${actionText}成功" else "${actionText}失败，请确认系统支持该操作")
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(preset.title)
            .setMessage(message)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showOperationResult(message: String) {
        if (AdBlockVpnService.isRunning) {
            startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD))
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }
}
