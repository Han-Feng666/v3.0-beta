package com.HanFeng.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
        syncHideBackgroundSwitch()
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
        }
    }

    private fun updateShizukuActionState() {
        val shizukuEnabled = AppSettingsRepository.isShizukuEnabled(this)
        val status = if (shizukuEnabled) ShizukuRepository.getStatus(this) else null
        val baseReady = status?.let { it.installed && it.binderAlive } == true
        val serviceHealthy = if (shizukuEnabled && baseReady) {
            val live = ShizukuAdControlRepository.isServiceAlive()
            if (!live) warmShizukuServices()
            live
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
            shizukuReady && serviceHealthy -> "Shizuku 状态：已连接 (${connectedMode})"
            !currentStatus.permissionStateKnown -> "Shizuku 状态：权限状态异常"
            !currentStatus.permissionGranted -> "Shizuku 状态：未授权"
            currentStatus.permissionGranted -> "Shizuku 状态：已授权 (${connectedMode}，服务连接中)"
            else -> "Shizuku 状态：服务连接中 (${connectedMode})"
        }
    }

    private fun openShizukuAdControlCatalog() {
        if (!ensureShizukuReady()) return
        val targets = discoverPromoGovernTargets(installedOnly = true, scope = PromoGovernScope.ALL)
        if (targets.isEmpty()) {
            val basePresets = ShizukuAdControlCatalog.allPresets()
            val installedPackages = basePresets.asSequence()
                .map { it.packageName }
                .distinct()
                .filter { packageName ->
                    ShizukuAdControlRepository.queryPackageStatus(this, packageName).installed
                }
                .toSet()
            MaterialAlertDialogBuilder(this)
                .setTitle("暂无可治理项目")
                .setMessage("当前没有识别到已安装治理项。你仍然可以打开预置目录查看支持的治理目标。")
                .setPositiveButton("查看预置目录") { _, _ ->
                    showPromoGovernTargetList(
                        basePresets.map {
                            PromoGovernTarget(
                                packageName = it.packageName,
                                title = it.title,
                                category = it.category,
                                description = it.description,
                                sourceLabel = "预置目录",
                                systemApp = true,
                                relatedPresets = ShizukuAdControlCatalog.findPresetsByPackage(it.packageName)
                            )
                        },
                        installedPackages
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择治理范围")
            .setItems(arrayOf("全部已安装治理项", "仅系统推广项", "仅第三方推广 App")) { _, which ->
                val scope = when (which) {
                    1 -> PromoGovernScope.SYSTEM_ONLY
                    2 -> PromoGovernScope.THIRD_PARTY_ONLY
                    else -> PromoGovernScope.ALL
                }
                val scopedTargets = discoverPromoGovernTargets(installedOnly = true, scope = scope)
                showPromoGovernTargetList(scopedTargets, scopedTargets.map { it.packageName }.toSet())
            }
            .setNegativeButton("取消", null)
            .show()
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
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择治理项")
            .setMessage("已识别到 ${targets.size} 个治理目标，已包含系统项和手机内已安装的第三方推广 App。")
            .setItems(buildPromoTargetLabels(targets, installedPackages).toTypedArray()) { _, which ->
                showPromoTargetActionDialog(targets[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openBatchShizukuAdControlDialog() {
        if (!ensureShizukuReady()) return
        AlertDialog.Builder(this)
            .setTitle("批量治理")
            .setMessage("批量治理已支持系统推广项和第三方推广 App。你可以先选择治理范围，再选择动作。")
            .setItems(arrayOf("全部已安装治理项", "仅系统推广项", "仅第三方推广 App")) { _, scopeWhich ->
                val scope = when (scopeWhich) {
                    1 -> PromoGovernScope.SYSTEM_ONLY
                    2 -> PromoGovernScope.THIRD_PARTY_ONLY
                    else -> PromoGovernScope.ALL
                }
                val installedTargets = discoverPromoGovernTargets(installedOnly = true, scope = scope)
                if (installedTargets.isEmpty()) {
                    showOperationResult("当前范围下没有识别到可批量治理的已安装推广项")
                    return@setItems
                }
                AlertDialog.Builder(this)
                    .setTitle("选择批量动作")
                    .setMessage("当前范围内识别到 ${installedTargets.size} 个可批量处理的已安装推广项。轻治理会优先关闭推送广告能力，整包治理会停用或暂停整个应用。浏览器、主题壁纸、锁屏和部分系统应用推荐会默认跳过整包停用，避免影响正常功能。")
                    .setItems(arrayOf("智能治理已安装推广项", "关闭已安装推广项推送广告", "恢复已安装推广项推送广告", "整包停用已安装推广项", "恢复已安装推广项", "整包暂停已安装推广项", "恢复暂停的推广项")) { _, which ->
                        when (which) {
                            0 -> runBatchShizukuAdControl(installedTargets, mode = BatchAdControlMode.SMART_GOVERN)
                            1 -> runBatchShizukuAdControl(installedTargets, mode = BatchAdControlMode.BLOCK_NOTIFICATIONS)
                            2 -> runBatchShizukuAdControl(installedTargets, mode = BatchAdControlMode.ALLOW_NOTIFICATIONS)
                            3 -> runBatchShizukuAdControl(installedTargets, mode = BatchAdControlMode.DISABLE)
                            4 -> runBatchShizukuAdControl(installedTargets, mode = BatchAdControlMode.ENABLE)
                            5 -> runBatchShizukuAdControl(installedTargets, mode = BatchAdControlMode.SUSPEND)
                            6 -> runBatchShizukuAdControl(installedTargets, mode = BatchAdControlMode.UNSUSPEND)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
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

    private enum class PromoGovernScope {
        ALL,
        SYSTEM_ONLY,
        THIRD_PARTY_ONLY
    }

    private data class BatchPackageTarget(
        val packageName: String,
        val target: PromoGovernTarget
    )

    private data class PromoGovernTarget(
        val packageName: String,
        val title: String,
        val category: String,
        val description: String,
        val sourceLabel: String,
        val systemApp: Boolean,
        val relatedPresets: List<ShizukuAdControlCatalog.Preset>
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
            "[$badge/$systemBadge] ${target.title} (${target.category} / ${target.sourceLabel})"
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
                .setMessage("Shizuku 已授权，但增强服务还未成功绑定。请稍后重试，或重新进入 Shizuku 后再回来。若刚完成授权，重新进入一次设置页通常即可触发连接。")
                .setPositiveButton("我知道了", null)
                .show()
            return false
        }
        return true
    }

    private fun showPromoTargetActionDialog(target: PromoGovernTarget) {
        val status = ShizukuAdControlRepository.queryPackageStatus(this, target.packageName)
        val relatedPresets = target.relatedPresets
        val canDisable = status.installed && !isDisabledState(status.enabledState)
        val canEnable = status.installed && status.enabledState != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
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
                append("，批量停用和智能治理会默认跳过，建议仅在确认风险后手动处理。")
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
                val lightGoverned = ShizukuAdControlRepository.blockPackageNotifications(this, target.packageName)
                val disableRequested = if (!lightGoverned && canDisable) {
                    ShizukuAdControlRepository.disablePackage(this, target.packageName)
                } else {
                    false
                }
                val disabledStatus = ShizukuAdControlRepository.queryPackageStatus(this, target.packageName)
                val disableSuccess = isDisabledState(disabledStatus.enabledState)
                val resultMessage = if (lightGoverned) {
                    "治理成功，当前已关闭推送广告能力"
                } else if (disableSuccess) {
                    "治理成功，当前已停用"
                } else {
                    val suspendRequested = if (!disabledStatus.suspended && canSuspend) {
                        ShizukuAdControlRepository.suspendPackage(this, target.packageName)
                    } else {
                        false
                    }
                    val suspendStatus = ShizukuAdControlRepository.queryPackageStatus(this, target.packageName)
                    val suspendSuccess = suspendRequested && suspendStatus.suspended
                    if (suspendSuccess) "停用未生效，已自动回退为暂停" else "治理失败，请确认系统支持停用或暂停"
                }
                showOperationResult(resultMessage)
            }
        }
        if (status.installed) {
            actions += "关闭推送广告" to {
                val success = ShizukuAdControlRepository.blockPackageNotifications(this, target.packageName)
                showOperationResult(if (success) "关闭推送广告成功" else "关闭推送广告失败，请确认系统支持通知权限治理")
            }
            actions += "恢复推送广告" to {
                val success = ShizukuAdControlRepository.allowPackageNotifications(this, target.packageName)
                showOperationResult(if (success) "恢复推送广告成功" else "恢复推送广告失败，请确认系统支持通知权限治理")
            }
        }
        if (canDisable) {
            actions += "停用" to {
                val requested = ShizukuAdControlRepository.disablePackage(this, target.packageName)
                val refreshed = ShizukuAdControlRepository.queryPackageStatus(this, target.packageName)
                val success = requested && isDisabledState(refreshed.enabledState)
                showOperationResult(if (success) "停用成功" else "停用失败，请确认该项目支持停用")
            }
        }
        if (canEnable) {
            actions += "恢复" to {
                val requested = ShizukuAdControlRepository.enablePackage(this, target.packageName)
                val refreshed = ShizukuAdControlRepository.queryPackageStatus(this, target.packageName)
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
                    ShizukuAdControlRepository.unsuspendPackage(this, target.packageName)
                } else {
                    ShizukuAdControlRepository.suspendPackage(this, target.packageName)
                }
                val refreshed = ShizukuAdControlRepository.queryPackageStatus(this, target.packageName)
                val success = requested && if (status.suspended) !refreshed.suspended else refreshed.suspended
                val actionText = if (status.suspended) "恢复暂停" else "暂停"
                showOperationResult(if (success) "${actionText}成功" else "${actionText}失败，请确认系统支持该操作")
            }
        }
        if (actions.isEmpty()) {
            showOperationResult("当前项目暂无可执行治理动作，请先确认目标应用已安装且 Shizuku 服务状态正常")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(target.title)
            .setMessage(message)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun discoverPromoGovernTargets(installedOnly: Boolean, scope: PromoGovernScope): List<PromoGovernTarget> {
        val presetTargets = ShizukuAdControlCatalog.allPresets()
            .groupBy { it.packageName }
            .mapNotNull { (packageName, relatedPresets) ->
                val status = ShizukuAdControlRepository.queryPackageStatus(this, packageName)
                if (installedOnly && !status.installed) return@mapNotNull null
                val primary = relatedPresets.first()
                PromoGovernTarget(
                    packageName = packageName,
                    title = primary.title,
                    category = primary.category,
                    description = primary.description,
                    sourceLabel = "预置目录",
                    systemApp = true,
                    relatedPresets = relatedPresets
                )
            }
        val presetPackages = presetTargets.mapTo(linkedSetOf()) { it.packageName }
        val autoTargets = packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != packageName }
            .filterNot { it.packageName in presetPackages }
            .mapNotNull { appInfo ->
                val target = buildThirdPartyPromoTarget(appInfo) ?: return@mapNotNull null
                if (installedOnly && !ShizukuAdControlRepository.queryPackageStatus(this, target.packageName).installed) return@mapNotNull null
                target
            }
            .toList()
        return (presetTargets + autoTargets)
            .distinctBy { it.packageName }
            .filter { target ->
                when (scope) {
                    PromoGovernScope.ALL -> true
                    PromoGovernScope.SYSTEM_ONLY -> target.systemApp
                    PromoGovernScope.THIRD_PARTY_ONLY -> !target.systemApp
                }
            }
            .sortedWith(compareBy<PromoGovernTarget> { !ShizukuAdControlRepository.queryPackageStatus(this, it.packageName).installed }.thenBy { it.category }.thenBy { it.title })
    }

    private fun buildThirdPartyPromoTarget(appInfo: ApplicationInfo): PromoGovernTarget? {
        val packageName = appInfo.packageName
        val label = packageManager.getApplicationLabel(appInfo)?.toString().orEmpty().ifBlank { packageName }
        val lowerLabel = label.lowercase()
        val lowerPackage = packageName.lowercase()
        if (!looksLikeThirdPartyPromoApp(lowerLabel, lowerPackage)) return null
        return PromoGovernTarget(
            packageName = packageName,
            title = label,
            category = inferPromoCategory(lowerLabel, lowerPackage),
            description = "适合治理该已安装推广 App 的通知广告、推荐流、营销入口和关联推广行为。",
            sourceLabel = "已安装第三方 App",
            systemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            relatedPresets = emptyList()
        )
    }

    private fun looksLikeThirdPartyPromoApp(lowerLabel: String, lowerPackage: String): Boolean {
        val labelHints = listOf(
            "应用商店", "软件商店", "浏览器", "阅读", "小说", "免费小说", "短剧", "视频", "资讯", "新闻",
            "壁纸", "主题", "锁屏", "搜索", "内容中心", "内容服务", "游戏中心", "游戏盒子", "助手",
            "推荐", "精选", "热点", "发现", "看看", "赚钱", "福利", "红包"
        )
        val packageHints = listOf(
            "appstore", "market", "browser", "reader", "novel", "book", "video", "news", "wallpaper",
            "theme", "lockscreen", "search", "assistant", "gamecenter", "gamebox", "content", "promo",
            "recommend", "discover", "hot", "reward", "benefit", "ad", "union"
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
            listOf("资讯", "新闻", "热点", "news", "hot").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "资讯推荐"
            listOf("应用商店", "软件商店", "market", "appstore").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "系统推广"
            listOf("搜索", "助手", "search", "assistant").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "内容推荐"
            listOf("游戏中心", "gamecenter").any { lowerLabel.contains(it) || lowerPackage.contains(it) } -> "系统推广"
            else -> "内容推荐"
        }
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
