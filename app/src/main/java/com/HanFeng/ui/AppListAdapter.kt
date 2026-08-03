package com.HanFeng.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.databinding.ItemAppBinding
import com.HanFeng.model.InstalledApp

class AppListAdapter(
    private val checkedSelector: (InstalledApp) -> Boolean = { it.whitelisted },
    private val onToggle: (InstalledApp, Boolean) -> Unit
) : ListAdapter<InstalledApp, AppListAdapter.AppHolder>(DIFF_CALLBACK) {

    private val iconCache = androidx.collection.LruCache<String, Drawable>(128)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun submit(items: List<InstalledApp>) {
        submitList(items)
    }

    fun shutdown() {
        iconCache.evictAll()
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
            val existing = item.icon ?: iconCache[item.packageName]
            if (existing != null) {
                binding.appIcon.setImageDrawable(existing)
            } else {
                binding.appIcon.setImageDrawable(null)
                val ctx = binding.root.context
                IconExecutorPool.executor.execute {
                    val dr = runCatching {
                        ctx.packageManager.getApplicationIcon(item.packageName)
                    }.getOrNull()
                    if (dr != null) {
                        iconCache.put(item.packageName, dr)
                        mainHandler.post {
                            val pos = bindingAdapterPosition
                            if (pos >= 0 && pos < itemCount &&
                                getItem(pos).packageName == item.packageName) {
                                binding.appIcon.setImageDrawable(dr)
                            }
                        }
                    }
                }
            }
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
