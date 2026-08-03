package com.HanFeng.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.model.RemoteRuleSourceConfig

class RemoteRuleSourceListAdapter(
    private val onToggle: (RemoteRuleSourceConfig) -> Unit,
    private val onSync: (RemoteRuleSourceConfig) -> Unit,
    private val onEdit: (RemoteRuleSourceConfig) -> Unit,
    private val onDelete: (RemoteRuleSourceConfig) -> Unit
) : ListAdapter<RemoteRuleSourceConfig, RemoteRuleSourceListAdapter.ViewHolder>(DiffCallback) {

    fun submit(list: List<RemoteRuleSourceConfig>) {
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_rule_source, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.sourceTitle)
        private val summary = itemView.findViewById<TextView>(R.id.sourceSummary)
        private val toggle = itemView.findViewById<Button>(R.id.btnToggle)
        private val sync = itemView.findViewById<Button>(R.id.btnSync)
        private val edit = itemView.findViewById<Button>(R.id.btnEdit)
        private val delete = itemView.findViewById<Button>(R.id.btnDelete)

        fun bind(item: RemoteRuleSourceConfig) {
            title.text = item.name
            val statusLine = when {
                item.lastSyncStartedAt > 0L -> "同步中"
                item.lastError != null && item.lastError.isNotBlank() -> "失败"
                item.lastUpdatedAt > 0L -> "成功"
                else -> "未运行"
            }
            summary.text = buildString {
                append(if (item.enabled) "已启用" else "已停用")
                append(" · ")
                append(statusLine)
                append(" · ")
                append(item.url)
                item.lastUpdatedAt.takeIf { it > 0L }?.let {
                    append("\n上次更新时间：")
                    append(dateFormatter.format(java.util.Date(it)))
                }
                item.lastError?.takeIf { it.isNotBlank() }?.let {
                    append("\n最近错误：")
                    append(it)
                }
            }
            // 长按失败原因一键复制到剪贴板，方便用户反馈或排查
            if (item.lastError?.isNotBlank() == true) {
                itemView.setOnLongClickListener {
                    val cm = itemView.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ruleSourceError", item.lastError))
                    android.widget.Toast.makeText(itemView.context, "失败原因已复制", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            } else {
                itemView.setOnLongClickListener(null)
            }
            // 运行中状态禁用按钮，避免重复触发同步
            val syncing = item.lastSyncStartedAt > 0L
            sync.isEnabled = !syncing
            toggle.text = if (item.enabled) "停用" else "启用"
            toggle.setOnClickListener { 
                try {
                    onToggle(item)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(itemView.context, "操作失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            sync.setOnClickListener { 
                try {
                    onSync(item)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(itemView.context, "同步失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            edit.setOnClickListener { 
                try {
                    onEdit(item)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(itemView.context, "编辑失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            delete.setOnClickListener { 
                try {
                    onDelete(item)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(itemView.context, "删除失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private companion object {
        private val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

        val DiffCallback = object : DiffUtil.ItemCallback<RemoteRuleSourceConfig>() {
            override fun areItemsTheSame(oldItem: RemoteRuleSourceConfig, newItem: RemoteRuleSourceConfig): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: RemoteRuleSourceConfig, newItem: RemoteRuleSourceConfig): Boolean {
                return oldItem == newItem
            }
        }
    }
}
