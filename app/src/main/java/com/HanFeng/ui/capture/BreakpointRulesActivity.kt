package com.HanFeng.ui.capture

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.capture.BreakpointKind
import com.HanFeng.capture.BreakpointMatchRule
import com.HanFeng.capture.CaptureController
import com.HanFeng.ui.BaseActivity

/**
 * 断点规则管理页(缺口 1)。
 *
 * - 列表展示当前 [BreakpointRepo.rules] 全部规则, 支持删除
 * - "+ 添加规则" 弹出 [BreakpointRuleDialogFragment] 收 host/method/path/kind, 调 [CaptureController.addBreakpointRule]
 * - 操作后立即落盘到 prefs([CaptureRepository.snapshotBreakpointRules])
 * - VPN 重启后由 [CaptureController.syncFromPrefs] 自动 restoreRules(design correctness 4)
 *
 * 引用 requirements R8.1 / design Components #5。
 */
class BreakpointRulesActivity : com.HanFeng.ui.BaseActivity() {

    private lateinit var adapter: RulesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_breakpoint_rules)
        findViewById<android.widget.Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<android.widget.Button>(R.id.btnAddRule).setOnClickListener { showAddDialog() }

        adapter = RulesAdapter(
            CaptureController.breakpointRules().toMutableList(),
            onDelete = { rule -> removeRule(rule) }
        )
        val rv = findViewById<RecyclerView>(R.id.rvRules)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        refreshEmptyState()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean = false

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_breakpoint_rule, null, false)
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_breakpoint_add)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val kind = if (view.findViewById<RadioButton>(R.id.rbRequest).isChecked) {
                    BreakpointKind.REQUEST
                } else BreakpointKind.RESPONSE
                val host = view.findViewById<EditText>(R.id.etHost).text?.toString()?.trim().orEmpty()
                val method = view.findViewById<EditText>(R.id.etMethod).text?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "*" }
                val path = view.findViewById<EditText>(R.id.etPath).text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                if (host.isEmpty() || host.contains(" ")) {
                    android.widget.Toast.makeText(this, R.string.capture_breakpoint_invalid_host, android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val rule = BreakpointMatchRule(host = host, method = method, pathPrefix = path, kind = kind)
                CaptureController.addBreakpointRule(this, rule)
                adapter.add(rule)
                refreshEmptyState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeRule(rule: BreakpointMatchRule) {
        CaptureController.removeBreakpointRule(this, rule)
        adapter.remove(rule)
        refreshEmptyState()
    }

    private fun refreshEmptyState() {
        val empty = adapter.itemCount == 0
        findViewById<View>(R.id.emptyHint).visibility = if (empty) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rvRules).visibility = if (empty) View.GONE else View.VISIBLE
    }

    companion object {
        fun createIntent(context: Context) = android.content.Intent(context, BreakpointRulesActivity::class.java)
    }
}

class RulesAdapter(
    private val items: MutableList<BreakpointMatchRule>,
    private val onDelete: (BreakpointMatchRule) -> Unit
) : RecyclerView.Adapter<RulesAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvKind: TextView = itemView.findViewById(R.id.tvKind)
        val tvHost: TextView = itemView.findViewById(R.id.tvHost)
        val tvMatch: TextView = itemView.findViewById(R.id.tvMatch)
        val btnDelete: android.widget.Button = itemView.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_breakpoint_rule, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.tvKind.text = if (r.kind == BreakpointKind.REQUEST) "[请求] break" else "[响应] break"
        holder.tvHost.text = r.host
        holder.tvMatch.text = buildString {
            append("method=").append(r.method ?: "*")
            append("  pathPrefix=").append(r.pathPrefix ?: "*")
        }
        holder.btnDelete.setOnClickListener { onDelete(r) }
    }

    override fun getItemCount(): Int = items.size

    fun add(rule: BreakpointMatchRule) {
        if (items.add(rule)) notifyItemInserted(items.size - 1)
    }

    fun remove(rule: BreakpointMatchRule) {
        val idx = items.indexOf(rule)
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }
}
