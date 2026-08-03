package com.HanFeng.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.adblocker.shizuku.BuiltInShizukuStarter
import com.HanFeng.data.AuthorizedApp
import com.HanFeng.data.ShizukuAuthorizationRepository
import com.HanFeng.data.ShizukuRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku 权限管理页面
 * 参考 Shizuku 官方 App 的激活流程实现
 */
class ShizukuPermissionManageActivity : BaseActivity() {

    companion object {
        private const val TAG = "ShizukuPermissionManage"
        private const val CHANNEL_ID = "shizuku_wireless_debug"
        private const val NOTIFICATION_ID = 10001
        private const val ACTION_PAIRING_CODE = "com.HanFeng.ACTION_PAIRING_CODE"
        private const val EXTRA_PAIRING_CODE = "pairing_code"
        private const val SHIZUKU_BINDER_CHECK_INTERVAL = 2000L
    }

    private lateinit var textShizukuStatus: android.widget.TextView
    private lateinit var textAuthorizedApps: android.widget.TextView
    private lateinit var showAllAppsSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var appList: RecyclerView
    private lateinit var loadingOverlay: android.widget.ProgressBar
    private lateinit var searchInput: android.widget.EditText

    private var statusRefreshJob: Job? = null
    private var wirelessDebugMonitorJob: Job? = null
    private var lastShizukuStatusKey: String? = null
    private var selfPermissionRequestedForBinder = false

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != ShizukuRepository.REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            showShortToast(if (granted) "本应用已获得 Shizuku 权限" else "未授权本应用，授权管理功能不可用")
            refreshShizukuStatus()
            loadAuthorizedAppsAsync()
        }
    }

    private val appListAdapter = AppListAdapter()
    private var allApps = mutableListOf<AppItem>()
    private var filteredApps = mutableListOf<AppItem>()

    /** 用于 Android 13+ 请求 POST_NOTIFICATIONS 权限的 launcher */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showPairingNotification()
            } else {
                Toast.makeText(this, "没有通知权限，无法在通知栏接收配对码", Toast.LENGTH_LONG).show()
                openNotificationSettings()
            }
        }

    /** 用于无线调试激活入口的延迟回调：权限就绪后执行实际通知发送 */
    private fun proceedWirelessDebugAfterPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                showPairingNotification()
            } else {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            showPairingNotification()
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(intent)
        }
    }

    /** 已授权列表加载任务（避免与 loadApps/initViews 抢屏幕） */
    private var authorizedLoadJob: Job? = null
    private var authorizationOpJob: Job? = null

    private val pairingCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d(TAG, "pairingCodeReceiver onReceive: action=${intent?.action}, hasRemoteInput=${RemoteInput.getResultsFromIntent(intent ?: return) != null}")
            val bundle = RemoteInput.getResultsFromIntent(intent ?: return) ?: return
            val code = bundle.getCharSequence(EXTRA_PAIRING_CODE)?.toString()?.trim() ?: return
            android.util.Log.d(TAG, "pairingCodeReceiver code=$code, length=${code.length}")
            if (code.length != 6 || !code.all { it.isDigit() }) {
                Toast.makeText(context, "配对码应为 6 位数字,收到: '$code'", Toast.LENGTH_LONG).show()
                return
            }
            // 取 6 位 code 成功后,弹 dialog 让用户输入 host:port
            promptForHostAndPair(code)
        }
    }

    private fun promptForHostAndPair(code: String) {
        val et = android.widget.EditText(this).apply {
            hint = "如 192.168.1.5:43254"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        StableDialog.builder(this)
            .setTitle("输入无线调试 IP:端口")
            .setMessage(
                "开发者选项 → 无线调试 → 使用配对码配对设备\n" +
                "页面上方的「IP 地址和端口」形如 192.168.1.5:43254\n" +
                "注意是配对端口,不是连接端口"
            )
            .setView(et)
            .setPositiveButton("配对") { dialog, _ ->
                val raw = et.text?.toString().orEmpty().trim()
                if (raw.isEmpty()) {
                    Toast.makeText(this, "请输入 IP:端口", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val parts = raw.split(":", limit = 2)
                if (parts.size < 2) {
                    Toast.makeText(this, "格式应为 IP:端口(缺冒号)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val host = parts[0].ifBlank { "127.0.0.1" }
                val port = parts[1].toIntOrNull()
                if (port == null || port < 1 || port > 65535) {
                    Toast.makeText(this, "端口非法(1-65535)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                dialog.dismiss()
                startPairingAndActivate(code, host, port)
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show host:port pair dialog failed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shizuku_permission_manage)

        createNotificationChannel()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 通知栏 RemoteInput 卡点的"发送"按钮由 SystemUI 发广播,源 UID 跟本 app 不同
            // 必须用 RECEIVER_EXPORTED 才能收到,NOT_EXPORTED 会被系统直接拒绝 → 点了没反应
            registerReceiver(pairingCodeReceiver, IntentFilter(ACTION_PAIRING_CODE), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pairingCodeReceiver, IntentFilter(ACTION_PAIRING_CODE))
        }

        initViews()
        setupListeners()
        startStatusRefresh()
        handlePairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    private fun handlePairingIntent(intent: Intent?) {
        if (intent == null) return
        val remoteCode = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(EXTRA_PAIRING_CODE)
            ?.toString()
            ?.trim()
        if (!remoteCode.isNullOrEmpty()) {
            if (remoteCode.length == 6 && remoteCode.all { it.isDigit() }) {
                promptForHostAndPair(remoteCode)
            } else {
                Toast.makeText(this, "配对码应为 6 位数字,收到: '$remoteCode'", Toast.LENGTH_LONG).show()
                promptForPairingCode()
            }
            return
        }
        val codeExtra = intent.getStringExtra(EXTRA_PAIRING_CODE)?.trim()
        if (!codeExtra.isNullOrEmpty() && codeExtra.length == 6 && codeExtra.all { it.isDigit() }) {
            promptForHostAndPair(codeExtra)
            return
        }
        if (intent.getBooleanExtra("trigger_pairing", false)) {
            promptForPairingCode()
        }
    }

    /**
     * 弹一个对话框让用户输入配对码 + 端口，作为通知 RemoteInput 的补充入口。
     * 部分国产 ROM 把通知 RemoteInput 折叠成普通通知,本入口确保用户一定能从通知跳转后弹框输入。
     */
    private fun promptForPairingCode() {
        val et = android.widget.EditText(this).apply {
            hint = "6 位配对码"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        StableDialog.builder(this)
            .setTitle("输入配对码")
            .setView(et)
            .setPositiveButton("输入端口") { _, _ ->
                val code = et.text?.toString().orEmpty().trim()
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    Toast.makeText(this, "配对码应为 6 位数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                promptForHostAndPair(code)
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show pairing code dialog failed")
    }

    private fun initViews() {
        textShizukuStatus = findViewById(R.id.textShizukuStatus)
        textAuthorizedApps = findViewById(R.id.textAuthorizedApps)
        showAllAppsSwitch = findViewById(R.id.switchShowAllApps)
        appList = findViewById(R.id.appList)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        searchInput = findViewById(R.id.searchInput)

        appList.layoutManager = LinearLayoutManager(this)
        appList.adapter = appListAdapter

        findViewById<android.widget.Button>(R.id.btnActivateRoot).setOnClickListener {
            activateViaRoot()
        }
        findViewById<android.widget.Button>(R.id.btnActivateAdb).setOnClickListener {
            openAdbActivationGuide()
        }
        findViewById<android.widget.Button>(R.id.btnActivateWireless).setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Toast.makeText(this, "无线调试需要 Android 11 及以上版本", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return@setOnClickListener
            }
            // 跳开发者选项无线调试页,同时启悬浮窗让用户直接输配对码,无需下拉通知栏
            com.HanFeng.service.WirelessDebugFloatingService.start(this)
            startWirelessDebugActivation()
        }
        findViewById<android.widget.Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun setupListeners() {
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })
        searchInput.setOnClickListener {
            searchInput.requestFocus()
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        appListAdapter.onAppCheckedChanged = { app, isChecked ->
            applyAuthorizationChange(app, isChecked)
        }
        // 默认只显示"声明了 Shizuku 客户端权限"的可授权应用(对齐官方 getApplications 行为);
        // 打开"显示全部"才列出所有第三方应用。
        showAllAppsSwitch.setOnCheckedChangeListener { _, _ ->
            loadAuthorizedAppsAsync()
        }
    }

    private fun applyAuthorizationChange(app: AppItem, isChecked: Boolean) {
        if (!app.declaresClientPermission) {
            // 这条 app 不支持 Shizuku —— 授权对它无效, 提示用户并恢复 UI, 不调 binder
            showShortToast("${app.label} 未声明 Shizuku 客户端权限, 授权开关对它无效")
            loadAuthorizedAppsAsync()
            return
        }
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        android.util.Log.i("ShizukuDiag", "applyAuth: binderAlive=$binderAlive pkg=${app.packageName} uid=${app.uid} isChecked=$isChecked")
        // 授权操作只依赖 server 存活: server 端按 managerAppId==本应用 appId 认授权方,
        // 与 manager 自身 checkSelfPermission 状态无关(官方 AuthorizationManager 同款行为)。
        if (!binderAlive) {
            showShortToast("请先激活 Shizuku，然后再给其它应用授权")
            loadAuthorizedAppsAsync()
            return
        }
        if (authorizationOpJob?.isActive == true) {
            showShortToast("正在处理上一项授权，请稍候")
            loadAuthorizedAppsAsync()
            return
        }
        authorizationOpJob = lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            showLoading(true)
            var resultMsg = ""
            val ok = withContext(Dispatchers.IO) {
                val flagsBefore = try { Shizuku.getFlagsForUid(app.uid, 6) } catch (_: Exception) { -1 }
                val grantOk = if (isChecked) {
                    ShizukuAuthorizationRepository.grantAuthorization(app.packageName, app.uid)
                } else {
                    ShizukuAuthorizationRepository.revokeAuthorization(app.packageName, app.uid)
                }
                val flagsAfter = try { Shizuku.getFlagsForUid(app.uid, 6) } catch (_: Exception) { -1 }
                resultMsg = "flags: $flagsBefore → $flagsAfter"
                android.util.Log.i("ShizukuDiag", "applyAuth result: $resultMsg grantOk=$grantOk")
                grantOk
            }
            if (isFinishing || isDestroyed) return@launch
            showLoading(false)
            if (ok) {
                showShortToast(if (isChecked) "已授权 ${app.label} ($resultMsg)" else "已取消 ${app.label} 的授权 ($resultMsg)")
            } else {
                showShortToast("操作失败 ($resultMsg)，请确认 Shizuku 已激活且本应用已授权")
            }
            loadAuthorizedAppsAsync()
        }
    }

    private fun confirmGrantAuthorization(app: AppItem) {
        StableDialog.builder(this)
            .setTitle("授予 Shizuku 权限")
            .setMessage("确认授权 ${app.label} 使用 Shizuku 服务吗？\n\n授权后该应用将能调用 Shizuku 接口执行特权命令，请仅对可信应用操作。")
            .setPositiveButton("授权") { _, _ ->
                grantAndRefresh(app)
            }
            .setNegativeButton("取消") { _, _ ->
                loadAuthorizedAppsAsync()
            }
            .setCancelable(false)
            .showSafely(this, "Show grant auth dialog failed")
    }

    private fun grantAndRefresh(app: AppItem) {
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            showLoading(true)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ShizukuAuthorizationRepository.grantAuthorization(
                        app.packageName,
                        app.uid
                    )
                }.getOrDefault(false)
            }
            if (isFinishing || isDestroyed) return@launch
            showLoading(false)
            if (ok) {
                showShortToast("已授权 ${app.label}")
                loadAuthorizedAppsAsync()
            } else {
                showShortToast("授权失败,请先确保 Shizuku 已激活")
                loadAuthorizedAppsAsync()
            }
        }
    }

    private fun confirmRevokeAuthorization(app: AppItem) {
        StableDialog.builder(this)
            .setTitle("取消授权")
            .setMessage("确认取消 ${app.label} 的 Shizuku 授权吗？\n\n" +
                    "该应用下次使用 Shizuku 服务时需要重新申请授权。\n" +
                    "为使授权变更立即生效，Shizuku 服务进程将被停止——\n" +
                    "本 app 主页的「激活 Shizuku」按钮或下次开机自启会重新拉起服务。")
            .setPositiveButton("取消授权") { _, _ ->
                revokeAndRefresh(app)
            }
            .setNegativeButton("保留", null)
            .showSafely(this, "Show revoke auth dialog failed")
    }

    private fun revokeAndRefresh(app: AppItem) {
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            showLoading(true)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ShizukuAuthorizationRepository.revokeAuthorization(
                        app.packageName,
                        app.uid
                    )
                }.getOrDefault(false)
            }
            if (isFinishing || isDestroyed) return@launch
            showLoading(false)
            if (ok) {
                showShortToast("已取消 ${app.label} 的授权")
                loadAuthorizedAppsAsync()
            } else {
                showShortToast("取消授权失败,请先确保 Shizuku 已激活")
                loadAuthorizedAppsAsync()
            }
        }
    }

    private fun startStatusRefresh() {
        statusRefreshJob?.cancel()
        statusRefreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshShizukuStatus()
                    delay(SHIZUKU_BINDER_CHECK_INTERVAL)
                }
            }
        }
    }

    private suspend fun refreshShizukuStatus() {
        withContext(Dispatchers.IO) {
            val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val checkPerm = if (binderAlive) runCatching { Shizuku.checkSelfPermission() }.getOrNull() else null
            val serverUid = if (binderAlive) runCatching { Shizuku.getUid() }.getOrNull() else null
            val binderObj = if (binderAlive) runCatching { Shizuku.getBinder() }.getOrNull() else null
            val binderObjAlive = binderObj?.isBinderAlive == true
            val status = runCatching {
                ShizukuRepository.getStatus(this@ShizukuPermissionManageActivity)
            }.getOrNull()

            // 诊断: 输出原始状态以便排查
            android.util.Log.i("ShizukuDiag", "binderAlive=$binderAlive checkPerm=$checkPerm serverUid=$serverUid binderObjAlive=$binderObjAlive status=${status?.runningMode}")

            if (isFinishing || isDestroyed) return@withContext

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                // 主界面面板按 use_shizuku 开关判断是否启用, 这里检测到 server 存活就联动打开,
                // 保证"权限管理页已激活" 与 "主界面已启用" 始终一致。
                if (binderAlive && !com.HanFeng.data.AppSettingsRepository.isShizukuEnabled(this@ShizukuPermissionManageActivity)) {
                    com.HanFeng.data.AppSettingsRepository.setShizukuEnabled(this@ShizukuPermissionManageActivity, true)
                }
                if (status == null) {
                    textShizukuStatus.text = "Shizuku 状态读取异常"
                    if (textAuthorizedApps.text.isNullOrBlank()) {
                        textAuthorizedApps.text = "已授权应用：-"
                    }
                    return@withContext
                }
                // 构建诊断后缀
                val diag = buildString {
                    append(" [")
                    append("binder=")
                    append(if (binderAlive) "Y" else "N")
                    append(" 自授权=")
                    append(when (checkPerm) {
                        PackageManager.PERMISSION_GRANTED -> "已授权"
                        PackageManager.PERMISSION_DENIED -> "已拒绝"
                        else -> "未知"
                    })
                    append(" uid=")
                    append(serverUid ?: "?")
                    when (serverUid) {
                        0 -> append("(root)")
                        2000 -> append("(adb)")
                    }
                    if (!binderObjAlive) append(" binderObj异常")
                    append("]")
                }
                val statusText = when {
                    !status.installed -> "Shizuku 未安装 · 可尝试 Root/ADB/无线调试激活$diag"
                    !status.binderAlive -> "Shizuku 未激活 · 请选择下方一种方式启动服务$diag"
                    status.permissionGranted -> "Shizuku 已激活并已授权本应用 · 可管理其它应用授权 (${status.runningMode})$diag"
                    !status.permissionStateKnown -> "Shizuku 已启动 · 权限状态读取异常，请重启 Shizuku 后再试$diag"
                    binderAlive -> "Shizuku 已启动但本应用未授权 · 请在授权弹窗中允许本应用 (${status.runningMode})$diag"
                    else -> "Shizuku 已安装，等待服务连接$diag"
                }
                textShizukuStatus.text = statusText
                if (textAuthorizedApps.text.isNullOrBlank()) {
                    textAuthorizedApps.text = "已授权应用：-"
                }
                val statusKey = "${status.installed}:${status.binderAlive}:${status.permissionGranted}:${status.permissionStateKnown}:${status.runningMode}"
                if (statusKey != lastShizukuStatusKey) {
                    lastShizukuStatusKey = statusKey
                    if (status.binderAlive) loadAuthorizedAppsAsync()
                }
                if (status.binderAlive && !status.permissionGranted && status.permissionStateKnown) {
                    requestSelfShizukuPermissionIfNeeded()
                }
            }
        }
    }

    // ==================== Root 激活 ====================

    private fun activateViaRoot() {
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            showLoading(true)
            val result = withContext(Dispatchers.IO) {
                runCatching { BuiltInShizukuStarter.activateViaRoot() }
                    .getOrDefault(BuiltInShizukuStarter.ActivationResult(false, "root", "激活异常"))
            }
            if (isFinishing || isDestroyed) return@launch
            showLoading(false)
            if (result.success) {
                Toast.makeText(this@ShizukuPermissionManageActivity, result.message, Toast.LENGTH_SHORT).show()
                waitForBinder()
            } else {
                // 失败时弹详细诊断对话框,带「复制日志」按钮,方便用户反馈给开发者
                showActivationFailureDialog(result.message)
            }
        }
    }

    private fun showActivationFailureDialog(message: String) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val tv = android.widget.TextView(this).apply {
            text = message
            textSize = 13f
            setTextIsSelectable(true)
            setOnClickListener { /* 让用户能长按选取 */ }
        }
        container.addView(tv)
        val dialog = StableDialog.builder(this)
            .setTitle("Shizuku 启动失败")
            .setView(container)
            .setPositiveButton("复制日志") { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("shizuku_log", message))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .showSafely(this, "Show activation failure dialog failed")
    }

    // ==================== ADB 激活 ====================

    private fun openAdbActivationGuide() {
        // 一比一复刻官方 Shizuku 的 ADB 激活:展示 starter 命令,让用户在电脑上 adb shell 跑
        val userCommand = try {
            moe.shizuku.manager.starter.Starter.userCommand
        } catch (e: Exception) {
            "(无法读取 starter 命令: ${e.message})"
        }
        val adbCommand = try {
            moe.shizuku.manager.starter.Starter.adbCommand
        } catch (e: Exception) {
            "adb shell $userCommand"
        }
        // 让命令可复制
        val message = buildString {
            append("ADB 激活步骤:\n\n")
            append("1. 在电脑上安装 adb 工具\n")
            append("2. 手机开启 USB 调试,用数据线连接电脑\n")
            append("3. 在电脑终端执行以下命令(可长按复制):\n\n")
            append(adbCommand).append("\n\n")
            append("激活后本 app 会自动检测 Shizuku 运行状态")
        }
        val tv = android.widget.TextView(this).apply {
            text = message
            textSize = 13f
            setPadding(50, 40, 50, 40)
            setTextIsSelectable(true)
        }
        StableDialog.builder(this)
            .setTitle("ADB 激活")
            .setView(tv)
            .setPositiveButton("复制命令") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("adb_command", adbCommand))
                Toast.makeText(this, "命令已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("打开开发者选项") { _, _ ->
                openDeveloperSettings()
            }
            .setNegativeButton("关闭", null)
            .showSafely(this, "Show adb guide dialog failed")
    }

    // ==================== 无线调试激活 ====================

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startWirelessDebugActivation() {
        // 先发通知 + 起 mDNS 自动发现;通知让用户可以随时回到 app 输配对码,
        // 发现到端口就直接弹框只让输配对码;10s 超时回退到手动输 IP:端口
        proceedWirelessDebugAfterPermission()
        autoDiscoverPairingPortAndPrompt()
    }

    private var pairingHelper: com.HanFeng.adblocker.shizuku.WirelessDebugPairingHelper? = null
    private var discoveringDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * 自动通过 mDNS 发现无线调试配对端口,发现后:
     *  - 端口自动填入,用户只需输入 6 位配对码
     *  - 超时(10 秒)没发现 → 退回到让用户手动输入 IP:端口的兜底对话框
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun autoDiscoverPairingPortAndPrompt() {
        if (discoveringDialog?.isShowing == true) {
            discoveringDialog?.dismiss()
        }
        pairingHelper?.stopDiscovery()

        val helper = com.HanFeng.adblocker.shizuku.WirelessDebugPairingHelper(this)
        pairingHelper = helper

        // 显示「正在搜索配对端口」的进度对话框
        val progBar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(50, 40, 50, 40)
            addView(progBar, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 30 })
            addView(android.widget.TextView(this@ShizukuPermissionManageActivity).apply {
                text = "正在搜索无线调试配对端口..."
                textSize = 14f
            })
        }
        discoveringDialog = StableDialog.builder(this)
            .setTitle("无线调试配对")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                helper.stopDiscovery()
            }
            .setNeutralButton("手动输入") { _, _ ->
                helper.stopDiscovery()
                promptForPairingCodeAndHost()
            }
            .showSafely(this, "Show discovering dialog failed")

        // 启动 mDNS 发现
        helper.startDiscovery { host, port ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                discoveringDialog?.dismiss()
                Toast.makeText(this, "已发现配对端口: $port", Toast.LENGTH_SHORT).show()
                promptForPairingCodeOnly(host, port)
            }
        }

        // 10 秒超时退回手动模式
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (helper.getDiscoveredHostPort() == null) {
                discoveringDialog?.dismiss()
                helper.stopDiscovery()
                Toast.makeText(
                    this,
                    "未自动发现配对端口,请手动输入",
                    Toast.LENGTH_LONG
                ).show()
                promptForPairingCodeAndHost()
            }
        }, 10_000L)
    }

    /**
     * 端口已自动识别,只让用户输入配对码。
     */
    private fun promptForPairingCodeOnly(host: String, port: Int) {
        val et = android.widget.EditText(this).apply {
            hint = "6 位配对码"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        StableDialog.builder(this)
            .setTitle("输入配对码")
            .setMessage(
                "已识别端口: $host:$port\n" +
                "在开发者选项 → 无线调试 → 使用配对码配对设备\n" +
                "的页面里显示 6 位配对码"
            )
            .setView(et)
            .setPositiveButton("配对") { dialog, _ ->
                val code = et.text?.toString().orEmpty().trim()
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    Toast.makeText(this, "配对码应为 6 位数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                dialog.dismiss()
                startPairingAndActivate(code, host, port)
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "Show pairing code only dialog failed")
    }

    /**
     * 兜底:端口没自动识别到时,让用户手动输入配对码 + IP:端口。
     */
    private fun promptForPairingCodeAndHost() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val codeEt = android.widget.EditText(this).apply {
            hint = "6 位配对码"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val hostEt = android.widget.EditText(this).apply {
            hint = "IP:端口 如 127.0.0.1:43254"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        container.addView(codeEt)
        container.addView(hostEt)

        StableDialog.builder(this)
            .setTitle("无线调试配对")
            .setMessage(
                "开发者选项 → 无线调试 → 使用配对码配对设备\n" +
                "该页面同时显示配对码和「IP 地址和端口」"
            )
            .setView(container)
            .setPositiveButton("配对") { dialog, _ ->
                val code = codeEt.text?.toString().orEmpty().trim()
                val hostRaw = hostEt.text?.toString().orEmpty().trim()
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    Toast.makeText(this, "配对码应为 6 位数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (hostRaw.isEmpty()) {
                    Toast.makeText(this, "请输入 IP:端口", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val parts = hostRaw.split(":", limit = 2)
                if (parts.size < 2) {
                    Toast.makeText(this, "格式应为 IP:端口", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val host = parts[0].ifBlank { "127.0.0.1" }
                val port = parts[1].toIntOrNull()
                if (port == null || port < 1 || port > 65535) {
                    Toast.makeText(this, "端口非法(1-65535)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                dialog.dismiss()
                startPairingAndActivate(code, host, port)
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("开开发者选项") { _, _ ->
                openDeveloperSettings()
            }
            .showSafely(this, "Show pairing code+host dialog failed")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun showPairingNotification() {
        val pairIntent = Intent(this, ShizukuPermissionManageActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("trigger_pairing", true)
        }
        val remoteInput = RemoteInput.Builder(EXTRA_PAIRING_CODE)
            .setLabel("输入 6 位配对码")
            .build()
        val pairPendingIntent = PendingIntent.getActivity(
            this, 0, pairIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "立即配对",
            pairPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        // 点击通知本体打开主页面，方便用户直接进入对话框
        val contentIntent = Intent(this, ShizukuPermissionManageActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("trigger_pairing", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 1, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 把 channel 提升到 IMPORTANCE_HIGH + 声音 + 振动，确保在所有厂商 ROM 上都弹横幅
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shizuku 无线调试配对")
            .setContentText("点击「立即配对」输入 6 位配对码")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)  // 常驻在通知栏,用户没输完配对码不让系统自动清掉
            .setContentIntent(contentPendingIntent)
            .setVibrate(longArrayOf(0, 100, 100, 100))
            .addAction(action)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("点下方「立即配对」输入 6 位配对码;若没开启无线调试,先在开发者选项里开启,再点「使用配对码配对设备」拿到端口和配对码"))

        getSystemService(NotificationManager::class.java).let { mgr ->
            // Android 8+：先删除旧 channel 让新的 IMPORTANCE_HIGH 配置生效
            // (Android 不允许通过代码升级已有 channel 的重要性,只能删除重建)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { mgr.deleteNotificationChannel(CHANNEL_ID) }
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Shizuku 无线调试",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "用于 Shizuku 无线调试配对"
                    setShowBadge(true)
                    enableLights(true)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                mgr.createNotificationChannel(channel)
            }
            mgr.notify(NOTIFICATION_ID, builder.build())
        }
        startWirelessDebugMonitor()
    }

    private fun startPairingAndActivate(pairingCode: String, host: String, port: Int) {
        // 取消配对通知
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)

        // 立即给用户反馈:配对已开始执行
        showLoading(true)
        Toast.makeText(this, "正在配对 $host:$port ...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            val result = withContext(Dispatchers.IO) {
                runCatching { BuiltInShizukuStarter.pairAndActivateViaWirelessDebug(pairingCode, host, port) }
                    .getOrDefault(BuiltInShizukuStarter.ActivationResult(false, "wireless", "配对激活异常"))
            }
            if (isFinishing || isDestroyed) return@launch
            showLoading(false)
            Toast.makeText(this@ShizukuPermissionManageActivity, result.message, Toast.LENGTH_LONG).show()

            if (result.success) {
                waitForBinder()
            } else {
                showActivationFailureDialog(result.message)
            }
        }
    }

    private fun startWirelessDebugMonitor() {
        wirelessDebugMonitorJob?.cancel()
        wirelessDebugMonitorJob = lifecycleScope.launch {
            while (isActive) {
                val wirelessEnabled = isWirelessDebuggingEnabled()
                if (wirelessEnabled) {
                    // 无线调试已开启，等待 Shizuku 启动
                    delay(3000)
                    if (Shizuku.pingBinder()) {
                        // Shizuku 已成功启动
                        val notificationManager = getSystemService(NotificationManager::class.java)
                        notificationManager.cancel(NOTIFICATION_ID)
                        Toast.makeText(this@ShizukuPermissionManageActivity, "Shizuku 已通过无线调试激活", Toast.LENGTH_SHORT).show()
                        break
                    }
                }
                delay(2000)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isWirelessDebuggingEnabled(): Boolean {
        return try {
            val adbEnabled = Settings.Global.getInt(contentResolver, "adb_enabled", 0) == 1
            val wirelessAdbEnabled = Settings.Global.getInt(contentResolver, "development_wireless_adb", 0) == 1
            adbEnabled && wirelessAdbEnabled
        } catch (e: Exception) {
            false
        }
    }

    private fun waitForBinder() {
        lifecycleScope.launch {
            var attempts = 0
            val maxAttempts = 15

            while (attempts < maxAttempts) {
                if (isFinishing || isDestroyed) return@launch
                delay(2000)
                if (isFinishing || isDestroyed) return@launch
                val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                if (alive) {
                    // 防假激活: 官方 server 进程是 starter fork 出的 app_process, 启动瞬间
                    // pingBinder 可能短暂为真, 但随后进程被杀/崩溃导致 binder 断开.
                    // 这里 3 秒后再确认一次, 只有 binder 持续在才宣告激活成功。
                    delay(3000)
                    if (isFinishing || isDestroyed) return@launch
                    val stillAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                    if (!stillAlive) {
                        val diagPath = withContext(Dispatchers.IO) { saveShizukuStartupFailureDiag() }
                        Toast.makeText(
                            this@ShizukuPermissionManageActivity,
                            "Shizuku 服务进程启动后即退出(疑似被系统回收或崩溃)\n诊断日志:" + (diagPath ?: "(写入失败)"),
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    Toast.makeText(this@ShizukuPermissionManageActivity, "Shizuku 已激活", Toast.LENGTH_SHORT).show()
                    // 主界面面板按 AppSettingsRepository.isShizukuEnabled 判断"是否启用",
                    // 激活成功后必须联动打开该开关, 否则主界面仍显示"未启用"(两端状态不一致)。
                    com.HanFeng.data.AppSettingsRepository.setShizukuEnabled(this@ShizukuPermissionManageActivity, true)
                    requestSelfShizukuPermissionIfNeeded()
                    autoHideShizukuPermission()
                    return@launch
                }
                attempts++
            }
            if (isFinishing || isDestroyed) return@launch
            val diagPath = withContext(Dispatchers.IO) { saveShizukuStartupFailureDiag() }
            if (isFinishing || isDestroyed) return@launch
            val displayPath = diagPath ?: "(写日志失败,看 logcat)"
            Toast.makeText(this@ShizukuPermissionManageActivity,
                "Shizuku 启动超时\n诊断日志已写入:\n$displayPath", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 收集 Shizuku server 启动失败的完整诊断信息并写入独立日志文件。
     * 文件路径在 app 内部 filesDir 下,可通过导出日志或 adb pull 访问。
     */
    private fun saveShizukuStartupFailureDiag(): String? {
        return runCatching {
            val su = com.HanFeng.adblocker.shizuku.SuSession.getInstance()
            if (!su.open(5)) return@runCatching null
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val diagFile = java.io.File(filesDir, "shizuku_diag_$ts.txt")
            val sb = StringBuilder()
            sb.append("=== Shizuku 启动诊断 ===\n")
            sb.append("时间: ").append(java.util.Date().toString()).append('\n')
            sb.append("App version: ").append(packageManager.getPackageInfo(packageName, 0).versionName).append('\n')
            sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            sb.append("设备: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n')
            sb.append('\n')

            // 1. server 进程现状
            val serverAliveCmd = "pgrep -af hanfeng_shizuku_server 2>/dev/null | grep -v 'pgrep -af'"
            val serverAlive = su.execute(serverAliveCmd, 3).output.trim()
            sb.append("--- server 进程现状 (排除 grep 自身) ---\n")
            sb.append(serverAlive.ifBlank { "(空,server 已退出)" }).append('\n')
            sb.append("进程数: ").append(serverAlive.lines().filter { it.isNotBlank() }.size).append('\n')
            sb.append('\n')

            // 1.5 starter stderr 文件 (app_process 真实崩溃输出)
            val starterErr = su.execute("cat /data/local/tmp/hanfeng_shizuku_starter.err 2>/dev/null", 3).output
            sb.append("--- starter stderr (/data/local/tmp/hanfeng_shizuku_starter.err) ---\n")
            sb.append(starterErr.ifBlank { "(空)" }).append('\n')
            sb.append('\n')

            // 1.6 BEGIN 区块:app_process 如果 fork 出来但还没死,跑 siege 检查 cmdline
            val appProcessCheck = su.execute(
                "ps -A -o PID,NAME,ARGS 2>/dev/null | grep -E 'app_process|ShizukuService|hanfeng_shizuku' | grep -v grep",
                4
            ).output
            sb.append("--- ps app_process ---\n")
            sb.append(appProcessCheck.ifBlank { "(无)" }).append('\n')
            sb.append('\n')

            // 2. 一段时间内的关键 logcat (这次拿全 tag 的 -b all 多 buffer)
            val logcatOut = su.execute(
                "logcat -d -b all -s ShizukuService:* ShizukuConfig:* BinderSender:* ShizukuStarter:* " +
                    "Shizuku:* ActivityThread:* AndroidRuntime:* starter:* libc:* -t 500 2>/dev/null", 8
            ).output
            sb.append("--- logcat (all buffers, 后 500 行) ---\n")
            sb.append(logcatOut.ifBlank { "(空)" }).append('\n')
            sb.append('\n')

            // 2.5 不带 tag 过滤,扫一次最近 200 行 app_process 任意关键字
            val recentLog = su.execute(
                "logcat -d -b main -t 500 2>/dev/null | grep -Ei 'shizuku|app_process|StarterNot|hanfeng|libshizuku|execv|execvp' | grep -v grep | tail -100",
                6
            ).output
            sb.append("--- 最近 500 行 main buffer 中匹配 shizuku/app_process 关键字 ---\n")
            sb.append(recentLog.ifBlank { "(无)" }).append('\n')
            sb.append('\n')

            // 3. 关键字快速定位
            val keys = listOf(
                "manager app is uninstalled",
                "MANAGER_APP_NOT_FOUND",
                "System.exit",
                "FATAL",
                "shizuku_server pid is",
                "starting server",
                "can not get path",
                "can not access manager",
                "app_process",
                "SecurityException",
                "ClassNotFoundException",
                "NoClassDefFoundError",
                "Process: moe.shizuku.server",
                "libshizuku",
                "starter",
                "Execution failed"
            )
            val matched = logcatOut.lines().filter { line ->
                keys.any { line.contains(it, ignoreCase = true) }
            }
            sb.append("--- 关键字命中行 ---\n")
            if (matched.isEmpty()) sb.append("(无)\n")
            else matched.forEach { sb.append(it).append('\n') }

            // 4. 主 app 是否已安装
            val pmCheck = su.execute("pm path com.HanFeng 2>/dev/null", 3).output.trim()
            sb.append('\n')
            sb.append("--- 主 app 安装状态 ---\n")
            sb.append("pm path com.HanFeng: ").append(pmCheck.ifBlank { "(空?!" }).append('\n')
            sb.append("ServerConstants MANAGER_APPLICATION_ID = ")
                .append(rikka.shizuku.server.ServerConstants.MANAGER_APPLICATION_ID).append('\n')

            // 5. 落盘
            java.io.FileOutputStream(diagFile).use { it.write(sb.toString().toByteArray()) }

            // 同步追加到主 app 日志,方便后续导出 zip 看一眼
            runCatching {
                com.HanFeng.data.LogRepository.append(this@ShizukuPermissionManageActivity, "[Shizuku-Diag] saved to ${diagFile.absolutePath}")
                com.HanFeng.data.LogRepository.append(this@ShizukuPermissionManageActivity, "[Shizuku-Diag] 关键字命中: ${matched.size} 行")
            }

            diagFile.absolutePath
        }.getOrNull()
    }

    /** Shizuku 服务激活后,自动为本 app 拉 Shizuku 授权(若当前未授权) */
    private fun requestSelfShizukuPermissionIfNeeded() {
        if (selfPermissionRequestedForBinder) return
        selfPermissionRequestedForBinder = true
        val requested = runCatching {
            com.HanFeng.data.ShizukuRepository.requestPermission()
        }.getOrDefault(false)
        if (!requested) {
            // 可能已授权或不可请求,显示友好提示让用户去权限页查看
            android.util.Log.d(TAG, "requestPermission not triggered (already authorized or unavailable)")
            selfPermissionRequestedForBinder = false
        }
        // 弹窗不一定能落地: fork server 对本应用(管理端)在 attach 时自动授权,
        // 若 SDK 缓存了旧的 DENIED, 需要 root 重启 server 触发重新 attach 自愈。
        verifySelfAuthorizationAfterRequest()
    }

    private fun verifySelfAuthorizationAfterRequest() {
        lifecycleScope.launch {
            delay(2500)
            if (isFinishing || isDestroyed) return@launch
            val granted = runCatching {
                rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (granted) return@launch
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.HanFeng.adblocker.shizuku.BuiltInShizukuStarter.ensureSelfAuthorized(this@ShizukuPermissionManageActivity)
                }.getOrDefault(
                    com.HanFeng.adblocker.shizuku.BuiltInShizukuStarter.ActivationResult(false, "self", "自愈执行异常")
                )
            }
            if (isFinishing || isDestroyed) return@launch
            if (result.success) {
                Toast.makeText(this@ShizukuPermissionManageActivity, result.message, Toast.LENGTH_SHORT).show()
                refreshShizukuStatus()
            } else {
                android.util.Log.w(TAG, "self-authorization self-heal failed: ${result.message}")
            }
        }
    }

    /**
     * 自动隐藏 Shizuku 痕迹。
     *
     * 注意：hideProcess/hideFiles 会用 tmpfs 覆盖 /proc/[pid] 目录，
     * 当被授权的第三方 App 启动后，其 /proc 信息如果含 "shizuku" 关键字
     * 会被一并遮蔽，导致 binder 通信/权限校验链路异常；
     * 同时还会让 Shizuku server 自身的 /proc 信息丢失，影响授权 App 调 checkSelfPermission。
     *
     * 因此默认关闭 process 与 files 隐藏，仅在用户显式开启「完全隐藏」时启用。
     */
    private fun autoHideShizukuPermission() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val config = com.HanFeng.adblocker.shizuku.ShizukuHideManager.HideConfig(
                    enabled = true,
                    hidePackageManager = true,
                    hideBinder = false,
                    hideProcess = false,
                    hideFiles = false
                )
                val result = com.HanFeng.adblocker.shizuku.ShizukuHideManager.configureHide(
                    this@ShizukuPermissionManageActivity,
                    config
                )
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        Toast.makeText(
                            this@ShizukuPermissionManageActivity,
                            "Shizuku 痕迹已自动隐藏（仅包管理器）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Auto hide failed: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        // 只在真正要弹通知时(showPairingNotification)统一创建并 deleteNotificationChannel 重建
        // 避免在 onCreate 阶段固化 channel 重要性，到弹通知时无法再升级
    }

    private fun openDeveloperSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * 加载当前已通过 Shizuku 授权的应用列表
     * 数据源:Shizuku server 通过 binder 事务 getApplications 返回的全部 installed packages,
     * 再用本 app PackageManager 异步补 label/icon(避免 binder 路径加载大图标阻塞)
     */
    private fun loadApps() {
        loadAuthorizedAppsAsync()
    }

    private fun loadAuthorizedAppsAsync() {
        authorizedLoadJob?.cancel()
        authorizedLoadJob = lifecycleScope.launch {
            loadingOverlay.visibility = View.VISIBLE
            // 两步加载: 1) 本地 PackageManager 拿第三方 App 列表  2) Shizuku 可用时叠加授权状态
            val result = withContext(Dispatchers.IO) {
                loadAppListWithAuthorization()
            }
            if (isFinishing || isDestroyed) return@launch
            val (items, statusHint) = result

            // 默认只显示声明了 Shizuku 客户端权限的可授权 App(对齐官方 getApplications 只返回相关应用);
            // 打开"显示全部"才列出所有第三方 App —— 否则满屏"未声明权限"的无效开关会让用户误以为用不了。
            val showAll = showAllAppsSwitch.isChecked
            val displayItems = if (showAll) items else items.filter { it.declaresClientPermission }
            val authorizableCount = items.count { it.declaresClientPermission }
            val authorizedCount = items.count { it.isChecked }

            allApps.clear()
            allApps.addAll(displayItems)
            filteredApps.clear()
            val currentQuery = searchInput.text?.toString().orEmpty()
            if (currentQuery.isBlank()) {
                filteredApps.addAll(displayItems)
                appListAdapter.updateItems(displayItems)
            } else {
                val q = currentQuery.lowercase().trim()
                filteredApps.addAll(displayItems.filter {
                    it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                })
                appListAdapter.updateItems(filteredApps)
            }

            textAuthorizedApps.text = if (showAll) {
                "已安装应用 ${items.size} 个 · 已授权 $authorizedCount 个 $statusHint"
            } else {
                "可授权 $authorizableCount 个 · 已授权 $authorizedCount 个 $statusHint"
            }

            // 授权开关只需 server 存活即可用: server 端按 managerAppId 校验调用方,
            // 不要求 manager 自身 checkSelfPermission 已落地(官方同款行为)。
            appListAdapter.switchesEnabled = ShizukuAuthorizationRepository.isServerAlive()

            if (items.isEmpty()) {
                textAuthorizedApps.text = "无法读取已安装应用列表，请检查权限设置"
            } else if (displayItems.isEmpty()) {
                // 未发现声明 Shizuku 客户端权限的应用: 本机没有集成 Shizuku SDK 的 App。
                // 授权只对这类 App 有效, 普通 App 开了也没用 —— 如实提示而非显示满屏灰开关。
                textAuthorizedApps.text =
                    "未发现声明 Shizuku 客户端权限的应用\n可打开「显示全部」查看所有应用（普通应用授权无效）"
            }

            loadingOverlay.visibility = View.GONE
        }
    }

    /**
     * 两层加载: 基础层用 PackageManager 拿手机里所有第三方 App；
     * 若 Shizuku 已启动且本应用已授权，再通过 binder 查询每个 App 的授权状态。
     *
     * @return (AppItem 列表, 状态提示文字)
     */
    private fun loadAppListWithAuthorization(): Pair<MutableList<AppItem>, String> {
        val pm = packageManager
        val result = mutableListOf<AppItem>()
        var statusHint = ""
        try {
            // 基础层: 本地 PackageManager 拿所有已安装应用, 排除本 app
            // 关键修复: 用 GET_PERMISSIONS 拉 requestedPermissions, 否则无法判断哪些 app 真支持 Shizuku
            val localPkgs = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
            val shizukuAlive = ShizukuAuthorizationRepository.isServerAlive()

            if (shizukuAlive) {
                // Shizuku 可用: 查每个 app 的授权状态(server 端按 managerAppId 认本应用,
                // 无需本应用先拿到自授权 —— 与官方 AuthorizationManager 行为一致)
                for (pkg in localPkgs) {
                    val pkgName = pkg.packageName ?: continue
                    if (pkgName == packageName) continue
                    val appInfo = pkg.applicationInfo ?: continue
                    val uid = appInfo.uid
                    val flags = runCatching { Shizuku.getFlagsForUid(uid, 6) }.getOrDefault(0)
                    val isAllowed = (flags and 2) == 2
                    val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(pkgName)
                    val icon = runCatching { appInfo.loadIcon(pm) }.getOrNull()
                    val declared = ShizukuAuthorizationRepository.isClientPermissionDeclared(pkg)
                    result.add(AppItem(
                        label = label,
                        packageName = pkgName,
                        icon = icon,
                        isChecked = isAllowed,
                        isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                        uid = uid,
                        declaresClientPermission = declared
                    ))
                }
                statusHint = " · Shizuku 授权表"
            } else {
                // Shizuku 未启动: 仍展示所有第三方 App, 开关全部关闭
                for (pkg in localPkgs) {
                    val pkgName = pkg.packageName ?: continue
                    if (pkgName == packageName) continue
                    val appInfo = pkg.applicationInfo ?: continue
                    val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(pkgName)
                    val icon = runCatching { appInfo.loadIcon(pm) }.getOrNull()
                    val declared = ShizukuAuthorizationRepository.isClientPermissionDeclared(pkg)
                    result.add(AppItem(
                        label = label,
                        packageName = pkgName,
                        icon = icon,
                        isChecked = false,
                        isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                        uid = appInfo.uid,
                        declaresClientPermission = declared
                    ))
                }
                statusHint = " · 请先激活 Shizuku (开关暂不可用)"
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "loadAppListWithAuthorization failed: ${e.message}", e)
        }
        result.sortWith(
            compareBy<AppItem> { if (it.isSystemApp) 1 else 0 }
                .thenBy { it.label.lowercase() }
        )
        return result to statusHint
    }

    private fun filterApps(query: String) {
        val q = query.lowercase().trim()
        filteredApps.clear()
        if (q.isEmpty()) {
            filteredApps.addAll(allApps)
        } else {
            filteredApps.addAll(allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        appListAdapter.refreshFilter(filteredApps)
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        // 关键修复: 每次回到本页都重新加载授权列表, 避免 onresume 后只加载一次导致
        // Shizuku 激活/binder 变可用后回到页面也不刷新授权列表的问题
        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusRefreshJob?.cancel()
        wirelessDebugMonitorJob?.cancel()
        authorizedLoadJob?.cancel()
        authorizationOpJob?.cancel()
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        pairingHelper?.stopDiscovery()
        runCatching { unregisterReceiver(pairingCodeReceiver) }
        // 注意:onDestroy 不取消配对通知 — 用户切到「开发者选项」页面让本 Activity onStop/onDestroy
        // 是无线调试激活流程的常态,通知应按 setTimeoutAfter(5分钟) 自然到期,不在这里清
    }

    data class AppItem(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable?,
        var isChecked: Boolean,
        val isSystemApp: Boolean = false,
        val uid: Int = -1,
        /** 该 app 是否在 manifest 声明了 Shizuku 客户端 permission。false = 授权开关无效, 用户会被误导。 */
        val declaresClientPermission: Boolean = true
    )

    class AppListAdapter : ListAdapter<AppItem, AppListAdapter.ViewHolder>(DIFF) {
        var onAppCheckedChanged: ((AppItem, Boolean) -> Unit)? = null
        var switchesEnabled: Boolean = true

        fun updateItems(newItems: List<AppItem>) {
            submitList(newItems.toList())
        }

        fun refreshFilter(newItems: List<AppItem>) {
            // search 过滤时使用：让 ListAdapter 做 DiffUtil 增量比对
            submitList(newItems.toList())
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_shizuku_authorized_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            private val iconView: android.widget.ImageView =
                itemView.findViewById(R.id.appIcon)
            private val labelView: android.widget.TextView =
                itemView.findViewById(R.id.appLabel)
            private val pkgView: android.widget.TextView =
                itemView.findViewById(R.id.appPackage)
            private val revokeBtn: androidx.appcompat.widget.SwitchCompat =
                itemView.findViewById(R.id.btnRevoke)

            fun bind(item: AppItem) {
                labelView.text = if (item.isSystemApp) "${item.label} (系统)" else item.label
                // 未声明客户端权限的 app 即使开关打开也不生效 —— 在副标题明示提示,
                // 避免用户以为"我点开了为什么对方还说没权限"
                pkgView.text = if (item.declaresClientPermission) {
                    "${item.packageName}  ·  uid=${item.uid}"
                } else {
                    "${item.packageName}  ·  uid=${item.uid}  ·  该 app 未声明 Shizuku 客户端权限, 授权对其无效"
                }
                if (item.icon != null) {
                    iconView.setImageDrawable(item.icon)
                } else {
                    iconView.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                revokeBtn.setOnCheckedChangeListener(null)
                revokeBtn.isChecked = item.isChecked
                // 未声明客户端权限的 app 开关禁用, 提示用户该 app 接口不支持 Shizuku
                revokeBtn.isEnabled = switchesEnabled && item.declaresClientPermission
                if (!item.declaresClientPermission) {
                    revokeBtn.alpha = 0.35f
                } else {
                    revokeBtn.alpha = 1f
                }
                revokeBtn.setOnCheckedChangeListener { _, isChecked ->
                    if (switchesEnabled && item.declaresClientPermission) {
                        onAppCheckedChanged?.invoke(item, isChecked)
                    }
                }
                itemView.setOnClickListener {
                    if (switchesEnabled && item.declaresClientPermission) revokeBtn.toggle()
                }
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
                override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
                    oldItem.packageName == newItem.packageName && oldItem.uid == newItem.uid

                override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
                    oldItem.label == newItem.label &&
                    oldItem.isChecked == newItem.isChecked &&
                    oldItem.icon === newItem.icon &&
                    oldItem.isSystemApp == newItem.isSystemApp
            }
        }
    }
}
