package com.HanFeng.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.databinding.ItemAppBinding
import com.HanFeng.model.InstalledApp
import com.bumptech.glide.Glide

class AppListAdapter(
    private val checkedSelector: (InstalledApp) -> Boolean = { it.whitelisted },
    private val onToggle: (InstalledApp, Boolean) -> Unit
) : ListAdapter<InstalledApp, AppListAdapter.AppHolder>(DIFF_CALLBACK) {

    fun submit(items: List<InstalledApp>) {
        submitList(items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder {
        return AppHolder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: AppHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppHolder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: InstalledApp) {
            binding.loadingIndicator.visibility = View.GONE
            Glide.with(binding.appIcon)
                .load(item.icon)
                .into(binding.appIcon)
            binding.appName.text = item.label
            binding.packageName.text = item.packageName
            binding.whitelistBox.setOnCheckedChangeListener(null)
            binding.whitelistBox.isChecked = checkedSelector(item)
            binding.whitelistBox.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<InstalledApp>() {
            override fun areItemsTheSame(oldItem: InstalledApp, newItem: InstalledApp): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: InstalledApp, newItem: InstalledApp): Boolean =
                oldItem == newItem
        }
    }
}
