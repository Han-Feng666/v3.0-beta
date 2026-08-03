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
            else -> {
                android.util.Log.e("RuleListAdapter", "unexpected view type: $viewType, falling back to domain view")
                DomainHolder(ItemRuleDomainBinding.inflate(inflater, parent, false))
            }
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
            // 关键修复: 当 rule.domain == "*" 或不含 "." 时 (来自历史误导入的 ||*^、*:443$network 等),
            // 直接显示 "*" 用户会困惑 ("只剩一个 * 号, 也找不到原规则")。
            // 改为拼接 destinationPorts / keywordPattern / regexPattern / ipCidr / pathPattern 等修饰信息,
            // 让用户能看到完整的原始规则语义, 便于识别和处理。
            binding.domainText.text = RuleListFormat.formatRuleDisplayText(item.rule)
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

/**
 * 统一格式化 BlockRule 作为列表条目的显示文本。
 *
 * 关键背景: 用户反馈"导入规则后列表里只剩一个星号, 也搜不到原规则" —
 * 历史遗留的星号端口规则、双竖线星号通配、纯 modifier 等规则入库后 domain 字段是 "*",
 * 单纯显示星号用户完全无法识别原始意图。这里把所有可识别的修饰信息拼接出来,
 * 例如端口、关键字、正则、IP CIDR、路径修饰等都附加展示。
 *
 * 普通域名规则无修饰时, 直接返回 domain, 行为不变。
 */
object RuleListFormat {
    fun formatRuleDisplayText(rule: com.HanFeng.model.BlockRule): String {
        if (!rule.rawText.isNullOrBlank()) return rule.rawText
        val domain = rule.domain
        val hasModifier = rule.destinationPorts.isNotEmpty() ||
            rule.destinationPortRanges.isNotEmpty() ||
            rule.sourcePorts.isNotEmpty() ||
            rule.sourcePortRanges.isNotEmpty() ||
            rule.keywordPattern != null ||
            rule.regexPattern != null ||
            rule.ipCidr != null ||
            rule.pathPattern != null ||
            rule.thirdParty || rule.firstParty || rule.important ||
            rule.exceptionRule ||
            rule.appPackages.isNotEmpty() || rule.dnsTypes != null ||
            rule.network
        if (!hasModifier) return domain

        val sb = StringBuilder()
        sb.append(domain)
        if (rule.destinationPorts.isNotEmpty()) {
            sb.append(":").append(rule.destinationPorts.sorted().joinToString(","))
        }
        if (rule.destinationPortRanges.isNotEmpty()) {
            sb.append(" dst[").append(rule.destinationPortRanges.joinToString(",") { "${it.first}-${it.last}" }).append("]")
        }
        if (rule.sourcePorts.isNotEmpty()) {
            sb.append(" src:").append(rule.sourcePorts.sorted().joinToString(","))
        }
        if (rule.sourcePortRanges.isNotEmpty()) {
            sb.append(" src[").append(rule.sourcePortRanges.joinToString(",") { "${it.first}-${it.last}" }).append("]")
        }
        if (rule.keywordPattern != null) sb.append(" keyword=").append(rule.keywordPattern)
        if (rule.regexPattern != null) sb.append(" regex=").append(rule.regexPattern)
        if (rule.ipCidr != null) sb.append(" ip-cidr=").append(rule.ipCidr)
        if (rule.pathPattern != null) sb.append(" path=").append(rule.pathPattern)
        if (rule.appPackages.isNotEmpty()) sb.append(" apps=").append(rule.appPackages.joinToString(","))
        if (rule.important) sb.append(" §important")
        if (rule.exceptionRule) sb.append(" §exception")
        if (rule.network) sb.append(" §network")
        if (rule.thirdParty) sb.append(" §3p")
        return sb.toString()
    }
}

