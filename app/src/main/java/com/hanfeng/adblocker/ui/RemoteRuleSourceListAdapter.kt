package com.HanFeng.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.model.RemoteRuleSourceConfig

class RemoteRuleSourceListAdapter(
    private val onToggle: (RemoteRuleSourceConfig) -> Unit,
    private val onSync: (RemoteRuleSourceConfig) -> Unit,
    private val onEdit: (RemoteRuleSourceConfig) -> Unit,
    private val onDelete: (RemoteRuleSourceConfig) -> Unit
) : RecyclerView.Adapter<RemoteRuleSourceListAdapter.ViewHolder>() {

    private var items: List<RemoteRuleSourceConfig> = emptyList()

    fun submit(list: List<RemoteRuleSourceConfig>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_rule_source, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.sourceTitle)
        private val summary = itemView.findViewById<TextView>(R.id.sourceSummary)
        private val toggle = itemView.findViewById<Button>(R.id.btnToggle)
        private val sync = itemView.findViewById<Button>(R.id.btnSync)
        private val edit = itemView.findViewById<Button>(R.id.btnEdit)
        private val delete = itemView.findViewById<Button>(R.id.btnDelete)

        fun bind(item: RemoteRuleSourceConfig) {
            title.text = item.name
            summary.text = buildString {
                append(if (item.enabled) "已启用" else "已停用")
                append(" · ")
                append(item.url)
                item.lastUpdatedAt.takeIf { it > 0L }?.let {
                    append("\n上次更新时间：")
                    append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(it)))
                }
                item.lastError?.takeIf { it.isNotBlank() }?.let {
                    append("\n最近错误：")
                    append(it)
                }
            }
            toggle.text = if (item.enabled) "停用" else "启用"
            toggle.setOnClickListener { onToggle(item) }
            sync.setOnClickListener { onSync(item) }
            edit.setOnClickListener { onEdit(item) }
            delete.setOnClickListener { onDelete(item) }
        }
    }
}
