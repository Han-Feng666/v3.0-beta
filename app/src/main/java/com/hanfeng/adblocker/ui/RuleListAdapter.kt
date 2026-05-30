package com.HanFeng.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.databinding.ItemRuleDomainBinding
import com.HanFeng.databinding.ItemRuleGroupBinding
import com.HanFeng.model.RuleListItem
import com.HanFeng.model.RuleSource

class RuleListAdapter(
    private val onGroupClick: (String) -> Unit,
    private val onGroupLongPress: (String) -> Unit,
    private val onDomainClick: (RuleListItem.Domain) -> Unit,
    private val onDomainLongPress: (RuleListItem.Domain) -> Unit,
    private val onSelectionChanged: (RuleListItem.Domain, Boolean) -> Unit
) : ListAdapter<RuleListItem, RecyclerView.ViewHolder>(RuleItemDiffCallback()) {

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_DOMAIN = 1
        private const val TYPE_MORE = 2
    }

    fun submit(items: List<RuleListItem>) {
        submitList(items)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is RuleListItem.Group -> TYPE_GROUP
            is RuleListItem.Domain -> TYPE_DOMAIN
            is RuleListItem.More -> TYPE_MORE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GROUP -> GroupHolder(ItemRuleGroupBinding.inflate(inflater, parent, false))
            TYPE_DOMAIN -> DomainHolder(ItemRuleDomainBinding.inflate(inflater, parent, false))
            TYPE_MORE -> MoreHolder(com.google.android.material.textview.MaterialTextView(parent.context).apply {
                setPadding(32, 8, 32, 8)
                textSize = 13f
                setTextColor(ContextCompat.getColor(parent.context, R.color.hf_text_secondary))
                gravity = android.view.Gravity.CENTER
            })
            else -> throw IllegalArgumentException("unexpected view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is RuleListItem.Group -> (holder as GroupHolder).bind(item)
            is RuleListItem.Domain -> (holder as DomainHolder).bind(item)
            is RuleListItem.More -> (holder as MoreHolder).bind(item)
        }
    }

    inner class GroupHolder(private val binding: ItemRuleGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RuleListItem.Group) {
            binding.groupTitle.text = "${item.vendor} (${item.count})"
            binding.groupArrow.text = if (item.expanded) "▼" else "▶"
            binding.root.setOnClickListener { onGroupClick(item.vendor) }
            binding.root.setOnLongClickListener {
                onGroupLongPress(item.vendor)
                true
            }
        }
    }

    inner class DomainHolder(private val binding: ItemRuleDomainBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RuleListItem.Domain) {
            binding.domainText.text = item.rule.domain
            binding.sourceTag.text = item.rule.source.label
            binding.sourceTag.visibility = android.view.View.VISIBLE
            binding.domainText.setTextColor(ContextCompat.getColor(binding.root.context, R.color.hf_text_primary))
            binding.selectBox.visibility = if (item.selectionMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.alpha = if (item.selected) 0.78f else 1f
            binding.root.setOnClickListener {
                if (item.selectionMode) {
                    binding.selectBox.toggle()
                } else {
                    onDomainClick(item)
                }
            }
            binding.root.setOnLongClickListener {
                onDomainLongPress(item)
                true
            }
            binding.selectBox.setOnCheckedChangeListener(null)
            binding.selectBox.isChecked = item.selected
            binding.selectBox.setOnCheckedChangeListener { _, checked -> onSelectionChanged(item, checked) }
        }
    }

    inner class MoreHolder(private val textView: android.widget.TextView) : RecyclerView.ViewHolder(textView) {
        fun bind(item: RuleListItem.More) {
            textView.text = "... 以及另外 ${item.remainingCount} 条规则 (点击展开)"
            textView.setOnClickListener { onGroupClick(item.vendor) }
        }
    }

    private class RuleItemDiffCallback : DiffUtil.ItemCallback<RuleListItem>() {
        override fun areItemsTheSame(oldItem: RuleListItem, newItem: RuleListItem): Boolean {
            return when {
                oldItem is RuleListItem.Group && newItem is RuleListItem.Group -> oldItem.vendor == newItem.vendor
                oldItem is RuleListItem.Domain && newItem is RuleListItem.Domain -> oldItem.rule.id == newItem.rule.id
                oldItem is RuleListItem.More && newItem is RuleListItem.More -> oldItem.vendor == newItem.vendor
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: RuleListItem, newItem: RuleListItem): Boolean {
            return oldItem == newItem
        }
    }
}
