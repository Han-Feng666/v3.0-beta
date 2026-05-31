package com.HanFeng.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
    private lateinit var btnJoinGroupSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchShizuku = findViewById(R.id.switchUseShizuku)
        switchHideBackground = findViewById(R.id.switchHideBackground)
        textShizukuStatus = findViewById(R.id.textShizukuStatus)
        btnShizukuAdControl = findViewById(R.id.btnShizukuAdControl)
        btnShizukuAdControlBatch = findViewById(R.id.btnShizukuAdControlBatch)
        btnCoexistSettings = findViewById(R.id.btnCoexistSettings)
        btnJoinGroupSettings = findViewById(R.id.btnJoinGroupSettings)

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
        btnJoinGroupSettings.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=573309536&card_type=group&source=qrcode")
                    )
                )
            }.onFailure {
                MaterialAlertDialogBuilder(this)
                    .setMessage("未找到可用的 QQ 客户端")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuActionState()
    }

    private fun updateShizukuActionState() {
        val shizukuEnabled = AppSettingsRepository.isShizukuEnabled(this)
        val status = if (shizukuEnabled) ShizukuRepository.getStatus(this) else null
        val baseReady = status?.let { it.installed && it.binderAlive } == true
        val serviceHealthy = if (shizukuEnabled && baseReady) {
            if (ShizukuAdControlRepository.isServiceAlive()) {
                true
            } else {
                warmShizukuServices()
            }
        } else {
            false
        }
        val shizukuReady = status?.let { it.installed && it.binderAlive && (it.permissionGranted || serviceHealthy) } == true
        btnShizukuAdControl.isEnabled = shizukuEnabled && baseReady
        btnShizukuAdControlBatch.isEnabled = shizukuEnabled && baseReady
        btnShizukuAdControl.alpha = if (btnShizukuAdControl.isEnabled) 1f else 0.55f
        btnShizukuAdControlBatch.alpha = if (btnShizukuAdControlBatch.isEnabled) 1f else 0.55f
        textShizukuStatus.text = buildShizukuStatusText(shizukuEnabled, shizukuReady, serviceHealthy, status)
    }

    private fun warmShizukuServices(): Boolean {
        runCatching { ShizukuConnectionOwnerRepository.ensureBound(this) }
        runCatching { ShizukuAdControlRepository.ensureBound(this) }
        return runCatching { ShizukuAdControlRepository.checkServiceHealth(this) }.getOrDefault(false)
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
            !currentStatus.installed -> "Shizuku 状态：未安装"
            !currentStatus.binderAlive -> "Shizuku 状态：未启动"
            shizukuReady && serviceHealthy && !currentStatus.permissionGranted -> "Shizuku 状态：已连接 (${connectedMode} / 兼容模式)"
            !currentStatus.permissionStateKnown -> "Shizuku 状态：权限状态异常"
            !currentStatus.permissionGranted -> "Shizuku 状态：未授权"
            shizukuReady && serviceHealthy -> "Shizuku 状态：已连接 (${connectedMode})"
            else -> "Shizuku 状态：服务待绑定 (${connectedMode})"
        }
    }

    private fun openShizukuAdControlCatalog() {
        if (!ensureShizukuReady()) return
        val basePresets = ShizukuAdControlCatalog.allPresets()
        val installedPackages = basePresets.asSequence()
            .map { it.packageName }
            .filter { packageName ->
                ShizukuAdControlRepository.queryPackageStatus(this, packageName).installed
            }
            .toSet()
        val presets = basePresets
            .filter { it.packageName in installedPackages }
            .sortedWith(
            compareBy<ShizukuAdControlCatalog.Preset> { it.packageName !in installedPackages }
                .thenBy { it.category }
                .thenBy { it.title }
        )
        if (presets.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("暂无可治理项目")
                .setMessage("当前设备上还没有识别到已安装的系统推广项。请先确认 Shizuku 服务已连接，再重试。")
                .setPositiveButton("我知道了", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("选择治理项")
            .setMessage("已识别到 ${presets.size} 个已安装治理项。")
            .setItems(buildPresetLabels(presets, installedPackages).toTypedArray()) { _, which ->
                showPresetActionDialog(presets[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openBatchShizukuAdControlDialog() {
        if (!ensureShizukuReady()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("批量治理")
            .setMessage("这里的批量动作分为轻治理和整包治理。轻治理会优先关闭推送广告能力，整包治理会停用或暂停整个应用。浏览器、主题壁纸、锁屏和部分系统应用推荐会默认跳过整包停用，避免影响正常功能。")
            .setItems(arrayOf("智能治理已安装推广项", "关闭已安装推广项推送广告", "恢复已安装推广项推送广告", "整包停用已安装推广项", "恢复已安装推广项", "整包暂停已安装推广项", "恢复暂停的推广项")) { _, which ->
                when (which) {
                    0 -> runBatchShizukuAdControl(mode = BatchAdControlMode.SMART_GOVERN)
                    1 -> runBatchShizukuAdControl(mode = BatchAdControlMode.BLOCK_NOTIFICATIONS)
                    2 -> runBatchShizukuAdControl(mode = BatchAdControlMode.ALLOW_NOTIFICATIONS)
                    3 -> runBatchShizukuAdControl(mode = BatchAdControlMode.DISABLE)
                    4 -> runBatchShizukuAdControl(mode = BatchAdControlMode.ENABLE)
                    5 -> runBatchShizukuAdControl(mode = BatchAdControlMode.SUSPEND)
                    6 -> runBatchShizukuAdControl(mode = BatchAdControlMode.UNSUSPEND)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runBatchShizukuAdControl(mode: BatchAdControlMode) {
        val presets = ShizukuAdControlCatalog.allPresets()
        val installedPackages = presets.asSequence()
            .map { it.packageName }
            .distinct()
            .filter { packageName ->
                ShizukuAdControlRepository.queryPackageStatus(this, packageName).installed
            }
            .toList()
        if (installedPackages.isEmpty()) {
            showOperationResult("未发现可处理的已安装推广项")
            return
        }
        val installedPackageTargets = installedPackages.mapNotNull { packageName ->
            val relatedPresets = ShizukuAdControlCatalog.findPresetsByPackage(packageName)
            relatedPresets.firstOrNull()?.let { primaryPreset ->
                BatchPackageTarget(
                    packageName = packageName,
                    primaryPreset = primaryPreset,
                    relatedPresets = relatedPresets
                )
            }
        }.sortedWith(
            compareBy<BatchPackageTarget> { it.primaryPreset.category }
                .thenBy { it.primaryPreset.title }
                .thenBy { it.packageName }
        )
        val operated = mutableListOf<String>()
        val degradedToSuspend = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        installedPackageTargets.forEach { target ->
            val packageName = target.packageName
            val displayName = buildBatchTargetLabel(target)
            val shouldSkipDisable = mode == BatchAdControlMode.DISABLE &&
                ShizukuAdControlCatalog.shouldSkipBatchDisable(packageName)
            if (shouldSkipDisable) {
                val reason = ShizukuAdControlCatalog.batchProtectedReason(packageName) ?: "系统功能保护"
                skipped += "$displayName（已跳过：$reason）"
                return@forEach
            }
            val requested = when (mode) {
                BatchAdControlMode.SMART_GOVERN -> ShizukuAdControlRepository.blockPackageNotifications(this, packageName)
                BatchAdControlMode.BLOCK_NOTIFICATIONS -> ShizukuAdControlRepository.blockPackageNotifications(this, packageName)
                BatchAdControlMode.ALLOW_NOTIFICATIONS -> ShizukuAdControlRepository.allowPackageNotifications(this, packageName)
                BatchAdControlMode.DISABLE -> ShizukuAdControlRepository.disablePackage(this, packageName)
                BatchAdControlMode.ENABLE -> ShizukuAdControlRepository.enablePackage(this, packageName)
                BatchAdControlMode.SUSPEND -> ShizukuAdControlRepository.suspendPackage(this, packageName)
                BatchAdControlMode.UNSUSPEND -> ShizukuAdControlRepository.unsuspendPackage(this, packageName)
            }
            val status = ShizukuAdControlRepository.queryPackageStatus(this, packageName)
            val blockDisableFallback = mode == BatchAdControlMode.SMART_GOVERN &&
                !requested &&
                !ShizukuAdControlCatalog.shouldSkipBatchDisable(packageName) &&
                !isDisabledState(status.enabledState) &&
                ShizukuAdControlRepository.disablePackage(this, packageName)
            val disabledStatus = if (blockDisableFallback) {
                ShizukuAdControlRepository.queryPackageStatus(this, packageName)
            } else {
                status
            }
            val suspendFallbackSucceeded = mode == BatchAdControlMode.SMART_GOVERN &&
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
                BatchAdControlMode.DISABLE -> {
                    requested && isDisabledState(refreshedStatus.enabledState)
                }
                BatchAdControlMode.ENABLE -> {
                    requested && (
                        refreshedStatus.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                        refreshedStatus.enabledState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    )
                }
                BatchAdControlMode.SUSPEND -> requested && refreshedStatus.suspended
                BatchAdControlMode.UNSUSPEND -> requested && !refreshedStatus.suspended
            }
            if (succeeded) {
                operated += displayName
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
            BatchAdControlMode.SMART_GOVERN -> "治理"
            BatchAdControlMode.BLOCK_NOTIFICATIONS -> "关闭推送广告"
            BatchAdControlMode.ALLOW_NOTIFICATIONS -> "恢复推送广告"
            BatchAdControlMode.DISABLE -> "整包停用"
            BatchAdControlMode.ENABLE -> "恢复"
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

    private data class BatchPackageTarget(
        val packageName: String,
        val primaryPreset: ShizukuAdControlCatalog.Preset,
        val relatedPresets: List<ShizukuAdControlCatalog.Preset>
    )

    private fun isDisabledState(enabledState: Int): Boolean {
        return enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    private fun buildPresetLabels(
        presets: List<ShizukuAdControlCatalog.Preset>,
        installedPackages: Set<String>
    ): List<String> {
        return presets.map { preset ->
            val installed = preset.packageName in installedPackages
            val badge = if (installed) "已安装" else "未安装"
            "[$badge] ${preset.title} (${preset.category})"
        }
    }

    private fun buildBatchTargetLabel(target: BatchPackageTarget): String {
        val titles = target.relatedPresets.map { it.title }.distinct()
        return if (titles.size == 1) {
            titles.first()
        } else {
            "${target.primaryPreset.title}（同包：${titles.joinToString("、")}）"
        }
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
        val serviceHealthy = if (status.installed && status.binderAlive) {
            if (ShizukuAdControlRepository.isServiceAlive()) {
                true
            } else {
                warmShizukuServices()
            }
        } else {
            false
        }
        if (!status.installed || !status.binderAlive || (!status.permissionGranted && !serviceHealthy)) {
            val message = when {
                !status.installed -> "请先安装 Shizuku。"
                !status.binderAlive -> "请先启动 Shizuku。"
                !status.permissionStateKnown -> "当前 Shizuku 可以连通，但权限状态读取异常。请重新打开 Shizuku 后重试，必要时更换兼容性更好的版本。"
                else -> "请先授权 Shizuku。"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Shizuku 暂不可用")
                .setMessage(message)
                .setPositiveButton("我知道了", null)
                .show()
            return false
        }
        if (!warmShizukuServices()) {
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
        val relatedPresets = ShizukuAdControlCatalog.findPresetsByPackage(preset.packageName)
        val canDisable = status.installed && !isDisabledState(status.enabledState)
        val canEnable = status.installed && status.enabledState != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val canSuspend = status.installed && !status.suspended
        val canUnsuspend = status.installed && status.suspended
        val message = buildString {
            append(preset.description)
            if (relatedPresets.size > 1) {
                append("\n\n同包治理标签：")
                append(relatedPresets.joinToString("、") { it.title })
            }
            ShizukuAdControlCatalog.batchProtectedReason(preset.packageName)?.let { reason ->
                append("\n\n批量保护：")
                append("该项目属于")
                append(reason)
                append("，批量停用和智能治理会默认跳过，建议仅在确认风险后手动处理。")
            }
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
        if (status.installed && (canDisable || canSuspend)) {
            actions += "智能治理" to {
                val lightGoverned = ShizukuAdControlRepository.blockPackageNotifications(this, preset.packageName)
                val disableRequested = if (!lightGoverned && canDisable) {
                    ShizukuAdControlRepository.disablePackage(this, preset.packageName)
                } else {
                    false
                }
                val disabledStatus = ShizukuAdControlRepository.queryPackageStatus(this, preset.packageName)
                val disableSuccess = isDisabledState(disabledStatus.enabledState)
                val resultMessage = if (lightGoverned) {
                    "治理成功，当前已关闭推送广告能力"
                } else if (disableSuccess) {
                    "治理成功，当前已停用"
                } else {
                    val suspendRequested = if (!disabledStatus.suspended && canSuspend) {
                        ShizukuAdControlRepository.suspendPackage(this, preset.packageName)
                    } else {
                        false
                    }
                    val suspendStatus = ShizukuAdControlRepository.queryPackageStatus(this, preset.packageName)
                    val suspendSuccess = suspendRequested && suspendStatus.suspended
                    if (suspendSuccess) "停用未生效，已自动回退为暂停" else "治理失败，请确认系统支持停用或暂停"
                }
                showOperationResult(resultMessage)
            }
        }
        if (status.installed) {
            actions += "关闭推送广告" to {
                val success = ShizukuAdControlRepository.blockPackageNotifications(this, preset.packageName)
                showOperationResult(if (success) "关闭推送广告成功" else "关闭推送广告失败，请确认系统支持通知权限治理")
            }
            actions += "恢复推送广告" to {
                val success = ShizukuAdControlRepository.allowPackageNotifications(this, preset.packageName)
                showOperationResult(if (success) "恢复推送广告成功" else "恢复推送广告失败，请确认系统支持通知权限治理")
            }
        }
        if (canDisable) {
            actions += "停用" to {
                val requested = ShizukuAdControlRepository.disablePackage(this, preset.packageName)
                val refreshed = ShizukuAdControlRepository.queryPackageStatus(this, preset.packageName)
                val success = requested && isDisabledState(refreshed.enabledState)
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
        val operationSummary = ShizukuAdControlRepository.getLastOperationSummary(this)
            .takeIf { it.isNotBlank() && it != "idle" }
        MaterialAlertDialogBuilder(this)
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
            .show()
    }
}
