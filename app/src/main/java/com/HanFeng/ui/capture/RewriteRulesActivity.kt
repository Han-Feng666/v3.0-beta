package com.HanFeng.ui.capture

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.capture.BreakpointAction
import com.HanFeng.capture.BreakpointKind
import com.HanFeng.capture.BreakpointMatch
import com.HanFeng.capture.BreakpointRule
import com.HanFeng.capture.BreakpointRuleRepository
import com.HanFeng.capture.PathMatcher
import com.HanFeng.ui.BaseActivity

/**
 * 批次 E6: 改写规则库 GUI。
 *
 * - 列表查看 + 启停切换 + 优先级/方向显示
 * - FAB 新增 / 长按编辑 / 右侧切换启停
 * - toolbar 导入 / 导出 / 清空
 *
 * 导入导出走 SAF (ACTION_OPEN_DOCUMENT 与 FileProvider + ACTION_SEND)。
 */
class RewriteRulesActivity : BaseActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var hintView: View
    private val adapter: RuleAdapter = RuleAdapter()

    private var openRuleEditDialog: AlertDialog? = null

    companion object {
        const val EXTRA_PREFILL_ENTRY_TXN_ID = "prefill_entry_txn_id"
        const val EXTRA_PREFILL_KIND = "prefill_kind"
        fun createIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, RewriteRulesActivity::class.java)
    }

    /**
     * SAF 导入 launcher — 选取 rules.json, 读 contentResolver 输入流, 调 [BreakpointRuleRepository.importJson]。
     */
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@registerForActivityResult
            val n = BreakpointRuleRepository.importJson(this, json).size
            Toast.makeText(this, getString(R.string.rewrite_rules_import_success, n), Toast.LENGTH_SHORT).show()
            reload()
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.rewrite_rules_import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rewrite_rules)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.rewriteToolbar)
        setSupportActionBar(toolbar)
        toolbar.title = getString(R.string.rewrite_rules_title)
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.rewrite_rules_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_rules -> {
                    pickFile.launch(arrayOf("application/json", "text/plain", "*/*"))
                    true
                }
                R.id.action_export_rules -> {
                    exportRules()
                    true
                }
                else -> false
            }
        }

        list = findViewById(R.id.rewriteList)
        emptyView = findViewById(R.id.rewriteEmpty)
        hintView = findViewById(R.id.rewriteHint)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.rewriteFab).setOnClickListener { showEditDialog(null) }

        reload()

        // 批次 E7b: 从详情页跳入 → 立即弹出预填的 "保存为改写规则" dialog
        val prefillTxnId = intent.getLongExtra(EXTRA_PREFILL_ENTRY_TXN_ID, -1L)
        val prefillKindStr = intent.getStringExtra(EXTRA_PREFILL_KIND)
        if (prefillTxnId > 0L && prefillKindStr != null) {
            val kind = runCatching { com.HanFeng.capture.BreakpointKind.valueOf(prefillKindStr) }
                .getOrDefault(com.HanFeng.capture.BreakpointKind.REQUEST)
            val entry = com.HanFeng.capture.CaptureController.get(prefillTxnId)
            if (entry != null) {
                val seed = if (kind == com.HanFeng.capture.BreakpointKind.RESPONSE && entry.responseStatus == 0) {
                    com.HanFeng.capture.BreakpointRule.fromCaptureEntry(
                        entry = entry,
                        kind = com.HanFeng.capture.BreakpointKind.REQUEST,
                        newId = com.HanFeng.capture.BreakpointRuleRepository.nextId(this),
                        nameSuffix = getString(R.string.rewrite_rules_kind_request)
                    )
                } else {
                    com.HanFeng.capture.BreakpointRule.fromCaptureEntry(
                        entry = entry,
                        kind = kind,
                        newId = com.HanFeng.capture.BreakpointRuleRepository.nextId(this),
                        nameSuffix = if (kind == com.HanFeng.capture.BreakpointKind.REQUEST)
                            getString(R.string.rewrite_rules_kind_request) else getString(R.string.rewrite_rules_kind_response)
                    )
                }
                showEditDialog(null /* 但下面 override 预填 */, seedOverride = seed)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        openRuleEditDialog?.dismiss()
    }

    private fun reload() {
        val rules = BreakpointRuleRepository.load(this)
        adapter.submit(rules)
        val empty = rules.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        emptyView.setOnClickListener { showEditDialog(null) }
        hintView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showEditDialog(existing: BreakpointRule?, seedOverride: BreakpointRule? = null) {
        /** 批次 E7b: 若有 seedOverride (来自详情页 "保存为规则"), 优先用其值预填, 忽略 existing。 */
        val seed: BreakpointRule? = seedOverride ?: existing
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_rewrite_rule_edit, null)
        val etName = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etName)
        val spKind = view.findViewById<Spinner>(R.id.spKind)
        val spAction = view.findViewById<Spinner>(R.id.spAction)
        val etHost = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etHost)
        val etMethod = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etMethod)
        val spPathMode = view.findViewById<Spinner>(R.id.spPathMode)
        val etPathValue = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPathValue)
        val etContentType = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etContentType)
        val etQuery = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etQuery)
        val etPriority = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPriority)
        val etStatusLine = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etStatusLine)
        val etHeaders = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etHeaders)
        val etReplacement = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etReplacement)

        val kindLabels = listOf(R.string.rewrite_rules_kind_request, R.string.rewrite_rules_kind_response)
        spKind.adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            kindLabels.map { getString(it) })
        val actionLabels = listOf(R.string.rewrite_rules_action_pass, R.string.rewrite_rules_action_replace, R.string.rewrite_rules_action_drop)
        spAction.adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            actionLabels.map { getString(it) })
        val pathLabels = listOf(R.string.rewrite_rules_pathmode_any, R.string.rewrite_rules_pathmode_prefix,
            R.string.rewrite_rules_pathmode_suffix, R.string.rewrite_rules_pathmode_contains,
            R.string.rewrite_rules_pathmode_equals, R.string.rewrite_rules_pathmode_regex)
        spPathMode.adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            pathLabels.map { getString(it) })

        // 预填 (seed 优先 existing)
        etName.setText(seed?.name.orEmpty())
        spKind.setSelection(if (seed?.match?.kind == BreakpointKind.RESPONSE) 1 else 0)
        etHost.setText(seed?.match?.host.orEmpty())
        etMethod.setText(seed?.match?.method.orEmpty())
        etPathValue.setText(runCatching {
            val m = seed?.match?.pathMatcher
            when (m) {
                is PathMatcher.Prefix -> m.value
                is PathMatcher.Suffix -> m.value
                is PathMatcher.Contains -> m.value
                is PathMatcher.Equals -> m.value
                is PathMatcher.Regex -> m.value
                else -> ""
            }
        }.getOrNull().orEmpty())
        spPathMode.setSelection(pathModeIndex(seed))
        etContentType.setText(seed?.match?.contentTypeContains.orEmpty())
        etQuery.setText(seed?.match?.queryContains.orEmpty())
        etPriority.setText((seed?.priority ?: 100).toString())
        val preAct = seed?.action
        spAction.setSelection(actionIndex(preAct))
        etStatusLine.setText(runCatching { (preAct as? BreakpointAction.ReplaceWith)?.statusLineOverride }.getOrNull().orEmpty())
        etHeaders.setText(runCatching { (preAct as? BreakpointAction.ReplaceWith)?.headersOverride?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } }.getOrNull().orEmpty())
        etReplacement.setText(runCatching { String((preAct as? BreakpointAction.ReplaceWith)?.replacement ?: ByteArray(0), Charsets.UTF_8) }.getOrNull().orEmpty())

        openRuleEditDialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.rewrite_rules_add else R.string.rewrite_rules_edit)
            .setView(view)
            .setPositiveButton(R.string.rewrite_rules_save) { _, _ ->
                val r = buildRuleFromDialog(
                    view, existing?.id ?: seed?.id ?: BreakpointRuleRepository.nextId(this)
                )
                if (r == null || (r.match.host.isBlank() && r.match.pathMatcher is PathMatcher.Any)) {
                    Toast.makeText(this, R.string.rewrite_rules_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                BreakpointRuleRepository.upsert(this, r)
                Toast.makeText(this, R.string.rewrite_rules_saved, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(R.string.rewrite_rules_cancel, null)
            .also { if (existing != null) it.setNeutralButton(R.string.rewrite_rules_delete) { _, _ -> deleteRule(existing) } }
            .create()
        openRuleEditDialog?.show()
    }

    private fun buildRuleFromDialog(view: View, id: Long): BreakpointRule? {
        val etName = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etName)
        val spKind = view.findViewById<Spinner>(R.id.spKind)
        val spAction = view.findViewById<Spinner>(R.id.spAction)
        val etHost = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etHost)
        val etMethod = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etMethod)
        val spPathMode = view.findViewById<Spinner>(R.id.spPathMode)
        val etPathValue = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPathValue)
        val etContentType = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etContentType)
        val etQuery = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etQuery)
        val etPriority = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPriority)
        val etStatusLine = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etStatusLine)
        val etHeaders = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etHeaders)
        val etReplacement = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etReplacement)

        val kind = if (spKind.selectedItemPosition == 1) BreakpointKind.RESPONSE else BreakpointKind.REQUEST
        val pm: PathMatcher = when (spPathMode.selectedItemPosition) {
            1 -> PathMatcher.Prefix(etPathValue.text.toString().trim())
            2 -> PathMatcher.Suffix(etPathValue.text.toString().trim())
            3 -> PathMatcher.Contains(etPathValue.text.toString().trim())
            4 -> PathMatcher.Equals(etPathValue.text.toString().trim())
            5 -> PathMatcher.Regex(etPathValue.text.toString().trim())
            else -> PathMatcher.Any
        }
        val action: BreakpointAction = when (spAction.selectedItemPosition) {
            2 -> BreakpointAction.Drop
            1 -> {
                val body = etReplacement.text.toString().toByteArray(Charsets.UTF_8)
                val hmap = parseHeaderText(etHeaders.text.toString())
                BreakpointAction.ReplaceWith(
                    replacement = body,
                    headersOverride = hmap.takeIf { it.isNotEmpty() },
                    statusLineOverride = etStatusLine.text.toString().trim().ifEmpty { null }
                )
            }
            else -> BreakpointAction.PassThrough(useOriginal = true)
        }
        return BreakpointRule(
            id = id,
            name = etName.text.toString().trim().ifEmpty { "rule-$id" },
            priority = etPriority.text.toString().trim().toIntOrNull() ?: 100,
            order = 0,
            enabled = true,
            match = BreakpointMatch(
                kind = kind,
                host = etHost.text.toString().trim(),
                method = etMethod.text.toString().trim().ifEmpty { null },
                pathMatcher = pm,
                contentTypeContains = etContentType.text.toString().trim().ifEmpty { null },
                queryContains = etQuery.text.toString().trim().ifEmpty { null }
            ),
            action = action
        )
    }

    private fun pathModeIndex(r: BreakpointRule?): Int {
        return when (r?.match?.pathMatcher) {
            is PathMatcher.Prefix -> 1
            is PathMatcher.Suffix -> 2
            is PathMatcher.Contains -> 3
            is PathMatcher.Equals -> 4
            is PathMatcher.Regex -> 5
            else -> 0
        }
    }

    private fun actionIndex(a: BreakpointAction?): Int {
        return when (a) {
            is BreakpointAction.ReplaceWith -> 1
            BreakpointAction.Drop -> 2
            else -> 0
        }
    }

    private fun parseHeaderText(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        text.split('\n').forEach { l ->
            val c = l.indexOf(':')
            if (c > 0) {
                val k = l.substring(0, c).trim()
                val v = l.substring(c + 1).trim()
                if (k.isNotEmpty()) out[k] = v
            }
        }
        return out
    }

    private fun deleteRule(rule: BreakpointRule) {
        BreakpointRuleRepository.remove(this, rule.id)
        Toast.makeText(this, R.string.rewrite_rules_deleted, Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun exportRules() {
        try {
            val json = BreakpointRuleRepository.exportJson(this)
            val file = java.io.File(cacheDir, "capture_export").apply { if (!exists()) mkdirs() }
                .let { java.io.File(it, "hanfeng_rewrite_rules.json") }
            file.writeText(json)
            val authority = packageName + ".fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(this, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.rewrite_rules_export_chooser)))
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.rewrite_rules_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ----- adapter -----

    inner class RuleAdapter : RecyclerView.Adapter<RuleAdapter.VH>() {
        private val items = mutableListOf<BreakpointRule>()

        fun submit(rules: List<BreakpointRule>) {
            items.clear()
            items.addAll(rules)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rewrite_rule, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.ruleName)
            private val summary: TextView = view.findViewById(R.id.ruleSummary)
            private val meta: TextView = view.findViewById(R.id.ruleMeta)
            private val toggle: SwitchCompat = view.findViewById(R.id.ruleEnabled)

            fun bind(rule: BreakpointRule) {
                name.text = if (rule.name.isNotEmpty()) rule.name else "rule-${rule.id}"
                val kindStr = if (rule.match.kind == BreakpointKind.RESPONSE)
                    getString(R.string.rewrite_rules_kind_response) else getString(R.string.rewrite_rules_kind_request)
                val actStr = when (rule.action) {
                    is BreakpointAction.PassThrough -> getString(R.string.rewrite_rules_action_pass)
                    is BreakpointAction.ReplaceWith -> getString(R.string.rewrite_rules_action_replace)
                    BreakpointAction.Drop -> getString(R.string.rewrite_rules_action_drop)
                }
                summary.text = "$kindStr · $actStr"
                val p = rule.match.pathMatcher
                val pStr = when (p) {
                    is PathMatcher.Any -> getString(R.string.rewrite_rules_pathmode_any)
                    is PathMatcher.Prefix -> "${getString(R.string.rewrite_rules_pathmode_prefix)} /${p.value}"
                    is PathMatcher.Suffix -> "${getString(R.string.rewrite_rules_pathmode_suffix)} ${p.value}"
                    is PathMatcher.Contains -> "${getString(R.string.rewrite_rules_pathmode_contains)} ${p.value}"
                    is PathMatcher.Equals -> "${getString(R.string.rewrite_rules_pathmode_equals)} ${p.value}"
                    is PathMatcher.Regex -> "${getString(R.string.rewrite_rules_pathmode_regex)} ${p.value}"
                }
                meta.text = "host=${rule.match.host} · $pStr · p=${rule.priority} · ${if (rule.enabled) "on" else "off"}"
                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = rule.enabled
                toggle.setOnCheckedChangeListener { _, checked ->
                    BreakpointRuleRepository.setEnabled(this@RewriteRulesActivity, rule.id, checked)
                    reload()
                }
                itemView.setOnClickListener { showEditDialog(rule) }
                itemView.setOnLongClickListener {
                    AlertDialog.Builder(this@RewriteRulesActivity)
                        .setTitle(rule.name)
                        .setItems(arrayOf(getString(R.string.rewrite_rules_delete))) { _, _ -> deleteRule(rule) }
                        .show()
                    true
                }
            }
        }
    }
}
