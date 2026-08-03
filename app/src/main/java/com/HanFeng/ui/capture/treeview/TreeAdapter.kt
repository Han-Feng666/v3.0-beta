package com.HanFeng.ui.capture.treeview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R

/**
 * 把 [TreeNode] 列表渲染为缩进的扁平列表; 每行节点用 monospace + 缩进字符串表达。
 */
class TreeAdapter(private val compactNodes: List<TreeNode>) :
    RecyclerView.Adapter<TreeAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvIndent: TextView = itemView.findViewById(R.id.tvIndent)
        val tvExpand: TextView = itemView.findViewById(R.id.tvExpand)
        val tvNodeText: TextView = itemView.findViewById(R.id.tvNodeText)
    }

    init { setHasStableIds(true) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tree_node, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val n = compactNodes[position]
        holder.tvIndent.text = "    ".repeat(n.depth)
        holder.tvExpand.text = if (n.isContainer) "▾" else ""
        holder.tvNodeText.text = if (n.value != null) {
            "${n.name}: ${n.value}"
        } else {
            n.name
        }
    }

    override fun getItemCount(): Int = compactNodes.size

    override fun getItemId(position: Int): Long = position.toLong()
}
