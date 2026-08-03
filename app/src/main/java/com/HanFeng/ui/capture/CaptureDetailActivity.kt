package com.HanFeng.ui.capture

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.HanFeng.capture.BreakpointAction
import com.HanFeng.capture.CaptureController
import com.HanFeng.capture.CaptureDraftRequest
import com.HanFeng.capture.CaptureDraftResponse
import com.HanFeng.capture.CaptureEntry
import com.HanFeng.capture.CaptureReplayEngine
import com.HanFeng.capture.CaptureTemplate
import com.HanFeng.ui.applyCustomAssetBackground
import com.HanFeng.ui.applyCustomFileBackground
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.HanFeng.R

/**
 * 抓包条目详情: 5 个 Tab(概览/请求/响应/TLS/导出 + 断点动作栏 + 顶部菜单编辑/重放)。
 *
 * - 接收 [EXTRA_TXN_ID] 取得对应 CaptureEntry
 * - toolbar 菜单提供 "编辑" 切换 + "重放"
 * - 当 entry 处于 [CaptureEntry.isPendingBreakpoint] 时显示底部断点动作栏
 *
 * 引用 design Components #12 / requirements R5 / R6 / R8 / R10。
 */
class CaptureDetailActivity : com.HanFeng.ui.BaseActivity() {

    companion object {
        const val EXTRA_TXN_ID = "txn_id"
    }

    private var txnId: Long = 0L
    val editSession = EditSession()
    private var editMode: Boolean = false
    private var requestTab: RequestTabFragment? = null
    private var responseTab: ResponseTabFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        txnId = intent.getLongExtra(EXTRA_TXN_ID, 0L)
        setContentView(R.layout.activity_capture_detail)
        applyBackgroundImage(findViewById(R.id.detailBackground))
        val toolbar = findViewById<Toolbar>(R.id.detailToolbar)
        setSupportActionBar(toolbar)
        toolbar.title = getString(R.string.capture_detail_title)
        toolbar.setNavigationOnClickListener { finish() }
        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.detailPager)
        val tabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.detailTabs)
        pager.adapter = DetailPagerAdapter(this, txnId)
        com.google.android.material.tabs.TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.capture_detail_tab_overview)
                1 -> getString(R.string.capture_detail_tab_request)
                2 -> getString(R.string.capture_detail_tab_response)
                3 -> getString(R.string.capture_detail_tab_tls)
                else -> getString(R.string.capture_detail_tab_export)
            }
        }.attach()
        refreshBreakpointBar()
    }

    fun registerRequestTab(fragment: RequestTabFragment?) {
        requestTab = fragment
        fragment?.applyEditMode(editMode)
    }

    private fun applyBackgroundImage(imageView: android.widget.ImageView) {
        val ctx = imageView.context.applicationContext
        val customPath = com.HanFeng.data.FeatureSettingsRepository.getCustomBackgroundPath(ctx)
        if (!customPath.isNullOrEmpty()) {
            if (!java.io.File(customPath).isFile) {
                com.HanFeng.data.FeatureSettingsRepository.removeCustomBackgroundPath(ctx, customPath)
            }
            imageView.applyCustomFileBackground(customPath)
        } else {
            imageView.applyCustomAssetBackground("custom/background")
        }
    }

    fun registerResponseTab(fragment: ResponseTabFragment?) {
        responseTab = fragment
        fragment?.applyEditMode(editMode)
    }

    fun unregisterTabs(fragment: Fragment) {
        if (fragment === requestTab) requestTab = null
        if (fragment === responseTab) responseTab = null
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.capture_detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu?): Boolean {
        menu?.findItem(R.id.action_edit_toggle)?.title =
            if (editMode) getString(R.string.capture_detail_apply_breakpoint) else getString(R.string.capture_detail_action_edit)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_edit_toggle -> {
                if (editMode) {
                    // 应用替换: 收集当前草稿并并入断点 Resolve 或仅本地草稿
                    flushEditSessionToController()
                    editMode = false
                } else {
                    editMode = true
                    editSession.resetAll()
                    requestTab?.applyEditMode(true)
                    responseTab?.applyEditMode(true)
                }
                invalidateOptionsMenu()
                true
            }
            R.id.action_replay -> {
                launchReplay()
                true
            }
            R.id.action_breakpoint_rules -> {
                startActivity(BreakpointRulesActivity.createIntent(this))
                true
            }
            R.id.action_breakpoint_add_request -> {
                prefillAddFromEntry(com.HanFeng.capture.BreakpointKind.REQUEST)
                true
            }
            R.id.action_breakpoint_add_response -> {
                prefillAddFromEntry(com.HanFeng.capture.BreakpointKind.RESPONSE)
                true
            }
            R.id.action_add_to_adblock -> {
                promptAddToAdBlock()
                true
            }
            R.id.action_save_as_rule_request -> {
                launchSaveAsRule(com.HanFeng.capture.BreakpointKind.REQUEST)
                true
            }
            R.id.action_save_as_rule_response -> {
                launchSaveAsRule(com.HanFeng.capture.BreakpointKind.RESPONSE)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 批次 E7b: 把当前 entry 转成持久改写规则草稿, 跳 RewriteRulesActivity 并打开 dialog。
     * 路由参数只传必要标量 id+kind, 进入 activity 后再次从 CaptureController.get 拿 entry 全量。
     */
    private fun launchSaveAsRule(kind: com.HanFeng.capture.BreakpointKind) {
        val e = CaptureController.get(txnId) ?: run {
            android.widget.Toast.makeText(this, R.string.capture_error_entry_not_found, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (kind == com.HanFeng.capture.BreakpointKind.RESPONSE && e.responseStatus == 0) {
            android.widget.Toast.makeText(this, R.string.rewrite_rules_saved_from_entry, android.widget.Toast.LENGTH_SHORT).show()
        }
        val intent = android.content.Intent(this, RewriteRulesActivity::class.java).apply {
            putExtra(RewriteRulesActivity.EXTRA_PREFILL_ENTRY_TXN_ID, txnId)
            putExtra(RewriteRulesActivity.EXTRA_PREFILL_KIND, kind.name)
        }
        startActivity(intent)
    }

    /** 联动: 弹底部 BottomSheet 让用户选择"加入广告拦截"的具体方式 (host/path/ws)。 */
    private fun promptAddToAdBlock() {
        val entry = CaptureController.get(txnId) ?: return
        val isWsUpgrade = entry.requestHeaders.entries.any {
            it.key.equals("upgrade", true) && it.value.equals("websocket", true)
        }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (isWsUpgrade) {
            options += getString(R.string.capture_adblock_kind_ws) to {
                runAddToAdBlock { ctx ->
                    com.HanFeng.capture.CaptureAdBlockBridge.addWebSocketHostBlock(ctx, entry.host).getOrThrow()
                }
            }
        }
        options += getString(R.string.capture_adblock_kind_host) to {
            runAddToAdBlock { ctx ->
                com.HanFeng.capture.CaptureAdBlockBridge.addHostBlocklist(ctx, entry.host).getOrThrow()
            }
        }
        options += getString(R.string.capture_adblock_kind_path) to {
            runAddToAdBlock { ctx ->
                com.HanFeng.capture.CaptureAdBlockBridge.addPathBlocklist(ctx, entry.host, entry.path).getOrThrow()
            }
        }
        val labels = options.map { it.first }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.capture_adblock_prompt_title)
            .setItems(labels) { _, which ->
                options[which].second.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 在 IO 线程执行联动操作然后 Toast 反馈。 */
    private fun runAddToAdBlock(block: (android.content.Context) -> Unit) {
        val ctx = this
        val entryHost = CaptureController.get(txnId)?.host ?: ""
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val summary = runCatching {
                block(ctx)
                entryHost
            }.fold(
                onSuccess = { host ->
                    getString(R.string.capture_adblock_added_toast, host)
                },
                onFailure = { err ->
                    getString(R.string.capture_adblock_add_failed_toast, err.message ?: err.javaClass.simpleName)
                }
            )
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(ctx, summary, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 缺口 1: 详情页快捷添加断点规则(根据当前 entry 的 host/method/path 预填弹窗)。
     */
    private fun prefillAddFromEntry(kind: com.HanFeng.capture.BreakpointKind) {
        val e = com.HanFeng.capture.CaptureController.get(txnId) ?: return
        val rule = com.HanFeng.capture.BreakpointMatchRule(
            host = e.host,
            method = e.method,
            pathPrefix = "/",
            kind = kind
        )
        com.HanFeng.capture.CaptureController.addBreakpointRule(this, rule)
        android.widget.Toast.makeText(this, "已添加 $kind 断点: ${e.host}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun flushEditSessionToController() {
        val entry = CaptureController.get(txnId) ?: return
        val reqDraft = requestTab?.collectDraft(entry)
        val respDraft = responseTab?.collectDraft(entry)
        if (reqDraft != null || respDraft != null) {
            // 任何方向有草稿则把 entry 锁在断点等待状态 (UI 层已不会出现)
            // 若用户正在响应头 ready 的流程可见中, 同时 Apply 即向上对 HTBM 触发 resume
            val action = if (respDraft != null) {
                BreakpointAction.ReplaceWith(
                    replacement = respDraft.body,
                    headersOverride = respDraft.headers.takeIf { it.isNotEmpty() },
                    statusLineOverride = respDraft.statusLine.takeIf { it.isNotBlank() }
                )
            } else if (reqDraft != null) {
                BreakpointAction.ReplaceWith(
                    replacement = reqDraft.body ?: ByteArray(0),
                    headersOverride = reqDraft.headers.takeIf { it.isNotEmpty() }
                )
            } else BreakpointAction.PassThrough(true)
            CaptureController.resumeFromBreakpoint(txnId, action)
        }
        requestTab?.applyEditMode(false)
        responseTab?.applyEditMode(false)
    }

    private fun launchReplay() {
        val entry = CaptureController.get(txnId) ?: return
        // 构造 template 仅用于重放(design correctness 7: 重放走系统默认 CA)
        val template = CaptureTemplate(
            id = "replay-${entry.txnId}-${System.currentTimeMillis()}",
            label = "From entry ${entry.txnId}",
            createdAt = System.currentTimeMillis(),
            method = entry.method,
            scheme = entry.scheme,
            host = entry.host,
            path = entry.path,
            headers = entry.requestHeaders,
            body = entry.requestBodyPreview
        )
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            CaptureReplayEngine.replay(template)
        }
    }

    private fun refreshBreakpointBar() {
        val entry = CaptureController.get(txnId) ?: return
        val bar = findViewById<android.view.View>(R.id.breakpointActionBar)
        bar.visibility = if (entry.isPendingBreakpoint) android.view.View.VISIBLE else android.view.View.GONE
        if (!entry.isPendingBreakpoint) return
        findViewById<android.view.View>(R.id.btnPassThrough).setOnClickListener {
            CaptureController.resumeFromBreakpoint(
                txnId, BreakpointAction.PassThrough(true)
            )
            bar.visibility = android.view.View.GONE
        }
        findViewById<android.view.View>(R.id.btnReplace).setOnClickListener {
            editMode = true
            editSession.resetAll()
            invalidateOptionsMenu()
            requestTab?.applyEditMode(true)
            responseTab?.applyEditMode(true)
        }
        findViewById<android.view.View>(R.id.btnDrop).setOnClickListener {
            CaptureController.resumeFromBreakpoint(
                txnId, BreakpointAction.Drop
            )
            bar.visibility = android.view.View.GONE
        }
    }

    fun Activity_getEditSession(): EditSession = editSession
}

class DetailPagerAdapter(activity: FragmentActivity, private val txnId: Long) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 5
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> OverviewFragment.newInstance(txnId)
            1 -> RequestTabFragment.newInstance(txnId)
            2 -> ResponseTabFragment.newInstance(txnId)
            3 -> TlsTabFragment.newInstance(txnId)
            else -> ExportTabFragment.newInstance(txnId)
        }
    }
}

// ====================== Tab 实现的基础设施 ======================

abstract class TextOnlyTab(layoutRes: Int) : Fragment(layoutRes)

class OverviewFragment : TextOnlyTab(R.layout.detail_tab_textview) {
    private val txnId: Long by lazy { arguments?.getLong(ARG_TXN_ID) ?: 0L }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val e = CaptureController.get(txnId) ?: return
        view.findViewById<android.view.View>(R.id.rgViewMode).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.treeView).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.previewContainer).visibility = android.view.View.GONE
        val headersEditor = view.findViewById<EditText>(R.id.headersEditor)
        view.findViewById<android.widget.TextView>(R.id.headerLabel).text = ""
        view.findViewById<android.widget.TextView>(R.id.bodyLabel).visibility = android.view.View.GONE
        view.findViewById<EditText>(R.id.bodyEditor).visibility = android.view.View.GONE
        val sb = StringBuilder()
        sb.appendLine("Host: ${e.host}")
        sb.appendLine("Path: ${e.path}")
        sb.appendLine("Method: ${e.method}")
        sb.appendLine("Scheme: ${e.scheme}")
        sb.appendLine("HTTP: ${e.httpVersion}")
        sb.appendLine("Timestamp: ${e.timestampMs}")
        sb.appendLine("Duration(ms): ${e.durationMs}")
         sb.appendLine("App: ${e.appName ?: "?"}  ${e.packageName ?: ""}")
        if (e.sessionId.isNotEmpty()) sb.appendLine("Session: ${e.sessionId}")
        if (e.replayed) sb.appendLine("(replayed)")
        if (e.intercepted) sb.appendLine("**intercepted**")
        if (e.error != null) sb.appendLine("Error: ${e.error}")
        headersEditor.isEnabled = false
        headersEditor.setText(sb)
    }

    companion object {
        private const val ARG_TXN_ID = "txn_id"
        fun newInstance(txnId: Long): OverviewFragment = OverviewFragment().apply {
            arguments = Bundle().apply { putLong(ARG_TXN_ID, txnId) }
        }
    }
}

/** 把多行 "key: value" 文本拆为 Map<String,String>。 */
private fun parseHeadersText(text: String): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    text.split("\n").forEach { line ->
        val l = line.trim()
        if (l.isEmpty()) return@forEach
        val idx = l.indexOf(':')
        if (idx <= 0) return@forEach
        m[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
    }
    return m
}

class RequestTabFragment : TextOnlyTab(R.layout.detail_tab_textview) {
    private val txnId: Long by lazy { arguments?.getLong(ARG_TXN_ID) ?: 0L }
    private var editMode = false

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val e = CaptureController.get(txnId) ?: return
        view.findViewById<android.widget.TextView>(R.id.headerLabel).text =
            getString(R.string.capture_detail_request_headers_label)
        view.findViewById<android.widget.TextView>(R.id.bodyLabel).text =
            getString(R.string.capture_detail_body_label)
        val hEdit = view.findViewById<EditText>(R.id.headersEditor)
        val bEdit = view.findViewById<EditText>(R.id.bodyEditor)
        val hs = e.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        hEdit.setText(hs)
        bEdit.setText(e.requestBodyPreview?.toString(Charsets.UTF_8) ?: "")
        (activity as? CaptureDetailActivity)?.registerRequestTab(this)
        applyEditModeInternal()
        // B 批次: 绑定 Raw/Tree/Preview 三态切换。请求方向 contentType 从 request headers 取。
        val ct = e.requestHeaders.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        val ce = e.requestHeaders.entries.firstOrNull { it.key.equals("content-encoding", true) }?.value
        // 批次 E5e: 若发现 content-encoding 提示解压信息于 bodyLabel
        if (!ce.isNullOrBlank()) {
            val original = e.requestBodyPreview?.size ?: 0
            val decompressed = com.HanFeng.capture.BodyDecompressor.decompress(e.requestBodyPreview ?: ByteArray(0), ce)
            if (decompressed.decompressed) {
                view.findViewById<android.widget.TextView>(R.id.bodyLabel).text =
                    getString(R.string.capture_detail_body_label_decompressed, ce, original, decompressed.body.size)
            }
        }
        com.HanFeng.ui.capture.treeview.bindViewModeSwitcher(
            root = view,
            bodyRaw = bEdit,
            bodyTree = view.findViewById(R.id.treeView),
            bodyPreview = view.findViewById(R.id.previewContainer),
            imagePreview = view.findViewById(R.id.imgPreview),
            hexView = view.findViewById(R.id.hexView),
            inputBytes = { e.requestBodyPreview ?: ByteArray(0) },
            contentType = { ct },
            contentEncoding = { ce }
        )
    }

    fun applyEditMode(enabled: Boolean) {
        editMode = enabled
        applyEditModeInternal()
    }

    private fun applyEditModeInternal() {
        view?.findViewById<EditText>(R.id.headersEditor)?.isEnabled = editMode
        view?.findViewById<EditText>(R.id.bodyEditor)?.isEnabled = editMode
    }

    fun collectDraft(entry: CaptureEntry): CaptureDraftRequest {
        val h = view?.findViewById<EditText>(R.id.headersEditor)?.text?.toString().orEmpty()
        val b = view?.findViewById<EditText>(R.id.bodyEditor)?.text?.toString().orEmpty()
        return CaptureDraftRequest(
            method = entry.method,
            host = entry.host,
            path = entry.path,
            headers = parseHeadersText(h),
            body = b.takeIf { it.isNotEmpty() }?.toByteArray()
        )
    }

    override fun onDestroyView() {
        (activity as? CaptureDetailActivity)?.unregisterTabs(this)
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TXN_ID = "txn_id"
        fun newInstance(txnId: Long): RequestTabFragment = RequestTabFragment().apply {
            arguments = Bundle().apply { putLong(ARG_TXN_ID, txnId) }
        }
    }
}

class ResponseTabFragment : TextOnlyTab(R.layout.detail_tab_textview) {
    private val txnId: Long by lazy { arguments?.getLong(ARG_TXN_ID) ?: 0L }
    private var editMode = false

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val e = CaptureController.get(txnId) ?: return
        view.findViewById<android.widget.TextView>(R.id.headerLabel).text =
            getString(R.string.capture_detail_response_headers_label)
        view.findViewById<android.widget.TextView>(R.id.bodyLabel).text =
            getString(R.string.capture_detail_body_label)
        val hEdit = view.findViewById<EditText>(R.id.headersEditor)
        val bEdit = view.findViewById<EditText>(R.id.bodyEditor)
        val statusLine = "HTTP/${e.httpVersion.removePrefix("HTTP/")} ${e.responseStatus} OK"
        val hs = e.responseHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        hEdit.setText("$statusLine\n$hs")
        bEdit.setText(e.responseBodyPreview?.toString(Charsets.UTF_8) ?: "")
        (activity as? CaptureDetailActivity)?.registerResponseTab(this)
        applyEditModeInternal()
        // B 批次: 响应方向 contentType 从 response headers 取。
        val ct = e.responseHeaders.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        val ce = e.responseHeaders.entries.firstOrNull { it.key.equals("content-encoding", true) }?.value
        // 批次 E5e: 若发现 content-encoding 提示解压信息于 bodyLabel
        if (!ce.isNullOrBlank()) {
            val original = e.responseBodyPreview?.size ?: 0
            val decompressed = com.HanFeng.capture.BodyDecompressor.decompress(e.responseBodyPreview ?: ByteArray(0), ce)
            if (decompressed.decompressed) {
                view.findViewById<android.widget.TextView>(R.id.bodyLabel).text =
                    getString(R.string.capture_detail_body_label_decompressed, ce, original, decompressed.body.size)
            }
        }
        com.HanFeng.ui.capture.treeview.bindViewModeSwitcher(
            root = view,
            bodyRaw = bEdit,
            bodyTree = view.findViewById(R.id.treeView),
            bodyPreview = view.findViewById(R.id.previewContainer),
            imagePreview = view.findViewById(R.id.imgPreview),
            hexView = view.findViewById(R.id.hexView),
            inputBytes = { e.responseBodyPreview ?: ByteArray(0) },
            contentType = { ct },
            contentEncoding = { ce }
        )
    }

    fun applyEditMode(enabled: Boolean) {
        editMode = enabled
        applyEditModeInternal()
    }

    private fun applyEditModeInternal() {
        view?.findViewById<EditText>(R.id.headersEditor)?.isEnabled = editMode
        view?.findViewById<EditText>(R.id.bodyEditor)?.isEnabled = editMode
    }

    fun collectDraft(entry: CaptureEntry): CaptureDraftResponse {
        val h = view?.findViewById<EditText>(R.id.headersEditor)?.text?.toString().orEmpty()
        val b = view?.findViewById<EditText>(R.id.bodyEditor)?.text?.toString().orEmpty()
        // 第一行 = statusLine; 剩下 = headers
        val (statusLine, headers) = if ("\n" in h) {
            h.substringBefore("\n") to parseHeadersText(h.substringAfter("\n"))
        } else h to emptyMap()
        return CaptureDraftResponse(
            statusLine = if (statusLine.isNotBlank()) statusLine else "HTTP/1.1 ${entry.responseStatus} OK",
            headers = headers,
            body = b.toByteArray()
        )
    }

    override fun onDestroyView() {
        (activity as? CaptureDetailActivity)?.unregisterTabs(this)
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TXN_ID = "txn_id"
        fun newInstance(txnId: Long): ResponseTabFragment = ResponseTabFragment().apply {
            arguments = Bundle().apply { putLong(ARG_TXN_ID, txnId) }
        }
    }
}

class TlsTabFragment : TextOnlyTab(R.layout.detail_tab_textview) {
    private val txnId: Long by lazy { arguments?.getLong(ARG_TXN_ID) ?: 0L }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val e = CaptureController.get(txnId)
        view.findViewById<android.view.View>(R.id.rgViewMode).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.treeView).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.previewContainer).visibility = android.view.View.GONE
        view.findViewById<android.widget.TextView>(R.id.headerLabel).text = ""
        view.findViewById<android.widget.TextView>(R.id.bodyLabel).visibility = android.view.View.GONE
        val hEdit = view.findViewById<EditText>(R.id.headersEditor)
        view.findViewById<EditText>(R.id.bodyEditor).visibility = android.view.View.GONE
        hEdit.isEnabled = false
        val sb = StringBuilder()
        val t = e?.tlsMeta
        if (t == null) {
            sb.append(getString(R.string.capture_tls_not_available))
        } else {
            sb.appendLine("SNI: ${t.sni ?: "-"}")
            sb.appendLine("Protocol: ${t.protocol}")
            sb.appendLine("CipherSuite: ${t.cipherSuite}")
            sb.appendLine("ALPN: ${t.alpn ?: "-"}")
            if (t.error != null) sb.appendLine("Error: ${t.error}")
            sb.appendLine()
            sb.appendLine("Peer certs: ${t.peerCertificates.size}")
            t.peerCertificates.forEachIndexed { i, c ->
                sb.appendLine("#${i + 1} subject=${c.subject}")
                sb.appendLine("    issuer=${c.issuer}")
                sb.appendLine("    valid ${c.notBefore}..${c.notAfter}")
                sb.appendLine("    sha256=${c.sha256Fingerprint}")
            }
        }
        hEdit.setText(sb)
    }

    companion object {
        private const val ARG_TXN_ID = "txn_id"
        fun newInstance(txnId: Long): TlsTabFragment = TlsTabFragment().apply {
            arguments = Bundle().apply { putLong(ARG_TXN_ID, txnId) }
        }
    }
}

class ExportTabFragment : TextOnlyTab(R.layout.detail_tab_textview) {
    private val txnId: Long by lazy { arguments?.getLong(ARG_TXN_ID) ?: 0L }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<android.view.View>(R.id.rgViewMode).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.treeView).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.previewContainer).visibility = android.view.View.GONE
        view.findViewById<android.widget.TextView>(R.id.headerLabel).text = ""
        view.findViewById<android.widget.TextView>(R.id.bodyLabel).visibility = android.view.View.GONE
        val hEdit = view.findViewById<EditText>(R.id.headersEditor)
        view.findViewById<EditText>(R.id.bodyEditor).visibility = android.view.View.GONE
        hEdit.isEnabled = false
        val e = CaptureController.get(txnId)
        val sb = StringBuilder()
        if (e != null) {
            // 联动批次 D: ExportTab 按 redactExport 设置项决定是否脱敏
            val redact = com.HanFeng.capture.CaptureRepository.loadConfig(requireContext()).redactExport
            val bytes = com.HanFeng.capture.CaptureExporter.export(
                listOf(e), com.HanFeng.capture.CaptureExporter.Format.CURL, redactMode = redact
            )
            sb.append(String(bytes, Charsets.UTF_8))
            sb.appendLine()
            sb.appendLine("---- HAR (truncated preview) ----")
            val har = com.HanFeng.capture.CaptureExporter.export(
                listOf(e), com.HanFeng.capture.CaptureExporter.Format.HAR, redactMode = redact
            )
            val previewSize = minOf(har.size, 2048)
            sb.append(String(har.copyOfRange(0, previewSize), Charsets.UTF_8))
        }
        hEdit.setText(sb)

        // 批次 E5b: 在 headersEditor 上方插入 分享 HAR / cURL / JSON 三按钮
        installShareButtons(view, hEdit, e)
    }

    private fun installShareButtons(
        root: android.view.View,
        anchor: android.view.View,
        entry: CaptureEntry?
    ) {
        val ctx = root.context
        val parent = anchor.parent as? android.widget.LinearLayout ?: return
        val anchorIndex = parent.indexOfChild(anchor)
        if (anchorIndex < 0) return
        if (entry == null) return
        // 已存在则跳过(防止重复创建)
        if (parent.findViewWithTag<android.view.View>("capture_export_share_row") != null) return

        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            tag = "capture_export_share_row"
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        val btnHar = makeShareButton(ctx, R.string.capture_export_share_har)
        val btnCurl = makeShareButton(ctx, R.string.capture_export_share_curl)
        val btnJson = makeShareButton(ctx, R.string.capture_export_share_json)
        val redact = com.HanFeng.capture.CaptureRepository.loadConfig(ctx).redactExport
        btnHar.setOnClickListener {
            val bytes = com.HanFeng.capture.CaptureExporter.export(
                listOf(entry), com.HanFeng.capture.CaptureExporter.Format.HAR, redactMode = redact
            )
            shareFile(ctx, "capture.har", "application/json", "hanfeng_capture_${entry.txnId}.har", bytes)
        }
        btnCurl.setOnClickListener {
            val bytes = com.HanFeng.capture.CaptureExporter.export(
                listOf(entry), com.HanFeng.capture.CaptureExporter.Format.CURL, redactMode = redact
            )
            shareFile(ctx, "capture.curl.sh", "text/x-shellscript", "hanfeng_capture_${entry.txnId}.sh", bytes)
        }
        btnJson.setOnClickListener {
            val bytes = com.HanFeng.capture.CaptureExporter.export(
                listOf(entry), com.HanFeng.capture.CaptureExporter.Format.PLAIN_SUMMARY, redactMode = redact
            )
            shareFile(ctx, "capture.json", "application/json", "hanfeng_capture_${entry.txnId}.txt", bytes)
        }
        row.addView(btnHar, linearWeight(1f))
        row.addView(btnCurl, linearWeight(1f))
        row.addView(btnJson, linearWeight(1f))
        parent.addView(row, anchorIndex)
    }

    private fun makeShareButton(ctx: android.content.Context, @androidx.annotation.StringRes textRes: Int): android.widget.Button {
        return android.widget.Button(ctx).apply {
            text = ctx.getString(textRes)
            textSize = 12f
            val lp = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.weight = 1f
            layoutParams = lp
        }
    }

    private fun linearWeight(w: Float): android.widget.LinearLayout.LayoutParams {
        return android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, w
        )
    }

    private fun shareFile(
        ctx: android.content.Context,
        cacheSubPath: String,
        mimeType: String,
        fileName: String,
        bytes: ByteArray
    ) {
        if (bytes.isEmpty()) {
            android.widget.Toast.makeText(ctx, R.string.capture_export_share_failed, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = java.io.File(ctx.cacheDir, "capture_export")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, fileName)
            file.outputStream().use { it.write(bytes) }
            val authority = ctx.packageName + ".fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, authority, file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, ctx.getString(R.string.capture_export_share_chooser)))
        } catch (t: Throwable) {
            android.widget.Toast.makeText(ctx, R.string.capture_export_share_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ARG_TXN_ID = "txn_id"
        fun newInstance(txnId: Long): ExportTabFragment = ExportTabFragment().apply {
            arguments = Bundle().apply { putLong(ARG_TXN_ID, txnId) }
        }
    }
}
