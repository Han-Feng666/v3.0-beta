package com.HanFeng.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.data.AppFreezeManager.FreezeEntry
import com.HanFeng.databinding.ItemAppFreezeBinding

class AppFreezeAdapter(
    private val onToggle: (FreezeEntry) -> Unit
) : ListAdapter<FreezeEntry, AppFreezeAdapter.FreezeHolder>(DIFF_CALLBACK) {

    fun submit(items: List<FreezeEntry>) {
        submitList(items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FreezeHolder {
        return FreezeHolder(
            ItemAppFreezeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: FreezeHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FreezeHolder(private val binding: ItemAppFreezeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FreezeEntry) {
            binding.loadingIndicator.visibility = View.GONE
            binding.appIcon.setImageDrawable(item.icon)
            binding.appName.text = item.label
            binding.packageName.text = item.packageName

            val statusParts = mutableListOf<String>()
            if (item.frozen) statusParts += "已冻结"
            if (item.suspended) statusParts += "已暂停"
            if (item.systemApp) statusParts += "系统应用"
            if (item.critical) statusParts += "关键"
            if (statusParts.isEmpty()) {
                binding.statusText.visibility = View.GONE
            } else {
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = statusParts.joinToString(" · ")
            }

            binding.toggleButton.isEnabled = !item.critical
            if (item.critical) {
                binding.toggleButton.text = "保护"
                binding.toggleButton.alpha = 0.5f
            } else if (item.frozen) {
                binding.toggleButton.text = "解冻"
                binding.toggleButton.alpha = 1f
            } else {
                binding.toggleButton.text = "冻结"
                binding.toggleButton.alpha = 1f
            }
            binding.toggleButton.setOnClickListener {
                if (!item.critical) onToggle(item)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FreezeEntry>() {
            override fun areItemsTheSame(oldItem: FreezeEntry, newItem: FreezeEntry): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: FreezeEntry, newItem: FreezeEntry): Boolean =
                oldItem.frozen == newItem.frozen &&
                    oldItem.suspended == newItem.suspended &&
                    oldItem.label == newItem.label &&
                    oldItem.critical == newItem.critical
        }
    }
}
