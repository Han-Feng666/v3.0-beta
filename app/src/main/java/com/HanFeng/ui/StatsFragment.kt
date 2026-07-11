package com.HanFeng.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.data.StatsRepository
import com.HanFeng.databinding.FragmentStatsBinding
import com.HanFeng.databinding.ItemRankingRowBinding
import com.HanFeng.databinding.ItemStatCardBinding
import com.HanFeng.databinding.ViewRankingCardBinding
import com.HanFeng.model.DashboardStats
import com.HanFeng.model.RankingEntry
import com.HanFeng.model.RankingBundle
import com.HanFeng.model.RankingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsFragment : Fragment(R.layout.fragment_stats) {
    private var _binding: FragmentStatsBinding? = null
    private var lastDashboard: DashboardStats? = null
    private var lastRankings: RankingBundle? = null
    private var pendingRender = false
    private var medalGold: Drawable? = null
    private var medalSilver: Drawable? = null
    private var medalBronze: Drawable? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentStatsBinding.bind(view)
        val binding = _binding ?: return
        view.findViewById<ImageView>(R.id.statsBackground).applyCustomAssetBackground("custom/stats_background")
        val initialTopPadding = binding.statsScroll.paddingTop
        val initialBottomPadding = binding.statsScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.statsScroll) { content, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(
                content.paddingLeft,
                initialTopPadding + systemBars.top,
                content.paddingRight,
                initialBottomPadding + systemBars.bottom
            )
            insets
        }
        StatsRepository.updates.observe(viewLifecycleOwner) {
            requestRender(force = true)
        }
    }

    override fun onResume() {
        super.onResume()
        requestRender(force = true)
    }

    override fun onDestroyView() {
        pendingRender = false
        super.onDestroyView()
        _binding = null
    }

    private fun requestRender(force: Boolean = false) {
        if (pendingRender && !force) return
        pendingRender = true
        view?.post {
            pendingRender = false
            if (!isAdded || _binding == null) return@post
            render(force)
        }
    }

    private data class RenderData(
        val dashboard: DashboardStats,
        val rankings: RankingBundle
    )

    private fun render(force: Boolean) {
        val binding = _binding ?: return
        val ctx = context ?: return
        if (!force && lastDashboard != null && lastRankings != null) {
            // Background — fetch + diff, only touch UI if changed
            viewLifecycleOwner.lifecycleScope.launch {
                val data = withContext(Dispatchers.Default) {
                    val dashboard = StatsRepository.getDashboard(ctx)
                    val rankings = StatsRepository.getRankings(ctx)
                    RenderData(dashboard, rankings)
                }
                if (!isAdded || _binding == null) return@launch
                if (!force && data.dashboard == lastDashboard && data.rankings == lastRankings) return@launch
                lastDashboard = data.dashboard
                lastRankings = data.rankings
                updateViews(binding, data.dashboard, data.rankings)
            }
        } else {
            // No previous data — fetch on background, fallback path
            viewLifecycleOwner.lifecycleScope.launch {
                val data = withContext(Dispatchers.Default) {
                    val dashboard = StatsRepository.getDashboard(ctx)
                    val rankings = StatsRepository.getRankings(ctx)
                    RenderData(dashboard, rankings)
                }
                if (!isAdded || _binding == null) return@launch
                lastDashboard = data.dashboard
                lastRankings = data.rankings
                updateViews(binding, data.dashboard, data.rankings)
            }
        }
    }

    private fun updateViews(
        binding: FragmentStatsBinding,
        dashboard: DashboardStats,
        rankings: RankingBundle
    ) {
        binding.statGrid.removeAllViews()
        addStatCard(binding, "今日拦截", dashboard.todayBlocked.toString())
        addStatCard(binding, "累计拦截", dashboard.totalBlocked.toString())
        addStatCard(binding, "DNS 总拦截", dashboard.dnsBlocked.toString())
        addStatCard(binding, "请求总数", dashboard.requestTotal.toString())
        addStatCard(binding, "响应总数", dashboard.responseTotal.toString())
        addStatCard(binding, "累计节省流量", formatBytes(dashboard.bytesSaved))

        binding.leftColumn.removeAllViews()
        binding.rightColumn.removeAllViews()
        addRankingCard(binding.leftColumn, "厂商拦截排行", RankingType.VENDOR_BLOCKED, rankings.vendorBlocked)
        addRankingCard(binding.leftColumn, "厂商请求排行", RankingType.VENDOR_REQUEST, rankings.vendorRequest)
        addRankingCard(binding.leftColumn, "厂商响应排行", RankingType.VENDOR_RESPONSE, rankings.vendorResponse)
        addRankingCard(binding.rightColumn, "应用拦截排行", RankingType.APP_BLOCKED, rankings.appBlocked)
        addRankingCard(binding.rightColumn, "应用请求排行", RankingType.APP_REQUEST, rankings.appRequest)
        addRankingCard(binding.rightColumn, "应用响应排行", RankingType.APP_RESPONSE, rankings.appResponse)
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
            else -> "${bytes / 1024 / 1024 / 1024} GB"
        }
    }

    private fun addStatCard(binding: FragmentStatsBinding, title: String, value: String) {
        val card = ItemStatCardBinding.inflate(layoutInflater, binding.statGrid, false)
        card.statTitle.text = title
        card.statValue.text = value
        binding.statGrid.addView(card.root)
    }

    private fun addRankingCard(container: LinearLayout, title: String, type: RankingType, data: List<RankingEntry>) {
        val ctx = context ?: return
        val card = ViewRankingCardBinding.inflate(layoutInflater, container, false)
        card.cardTitle.text = title

        fun renderList() {
            card.cardList.removeAllViews()
            val previewCount = 5
            val visible = data.take(previewCount)
            card.cardList.layoutParams = card.cardList.layoutParams.apply {
                height = resources.getDimensionPixelSize(R.dimen.rank_card_collapsed_height)
            }
            visible.forEachIndexed { index, entry ->
                val row = ItemRankingRowBinding.inflate(layoutInflater, card.cardList, false)
                val displayEntry = displayEntry(title, entry)
                val medalDrawable = when (index) {
                    0 -> getMedalDrawable(0)
                    1 -> getMedalDrawable(1)
                    2 -> getMedalDrawable(2)
                    else -> null
                }
                val hasMedal = medalDrawable != null
                row.rankMedal.visibility = if (hasMedal) View.VISIBLE else View.GONE
                row.rankLabel.visibility = if (hasMedal) View.GONE else View.VISIBLE
                if (medalDrawable != null) {
                    row.rankMedal.setImageDrawable(medalDrawable)
                }
                row.rankLabel.text = (index + 1).toString()
                row.rankName.text = displayEntry.name
                row.rankValue.text = displayEntry.value.toString()
                card.cardList.addView(row.root)
            }
            repeat((previewCount - visible.size).coerceAtLeast(0)) {
                val filler = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        resources.getDimensionPixelSize(R.dimen.rank_row_height)
                    )
                }
                card.cardList.addView(filler)
            }
            card.toggleMore.visibility = if (data.size > previewCount) View.VISIBLE else View.INVISIBLE
            card.toggleMore.text = "查看完整榜单 ▼"
        }

        card.toggleMore.setOnClickListener {
            showFullRankingPage(title, type)
        }
        renderList()
        container.addView(card.root)
    }

    private fun showFullRankingPage(title: String, type: RankingType) {
        val host = activity as? MainActivity ?: return
        runCatching {
            startActivity(RankingDetailActivity.createIntent(host, title, type))
        }.onFailure {
            context?.let {
                android.widget.Toast.makeText(it, "打开排行榜详情失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun displayEntry(title: String, entry: RankingEntry): RankingEntry {
        if (!title.startsWith("应用")) return entry
        return entry.copy(name = simplifyAppName(entry.name))
    }

    private fun simplifyAppName(rawName: String): String {
        val trimmed = rawName.trim()
        val suffix = Regex("\\s*\\([a-zA-Z0-9_.]+\\)$")
        return trimmed.replace(suffix, "").ifBlank { trimmed }
    }

    private fun getMedalDrawable(index: Int): Drawable? {
        val ctx = context ?: return null
        return when (index) {
            0 -> medalGold ?: loadCustomAssetDrawable(ctx, "custom/medal_gold")?.also { medalGold = it }
            1 -> medalSilver ?: loadCustomAssetDrawable(ctx, "custom/medal_silver")?.also { medalSilver = it }
            2 -> medalBronze ?: loadCustomAssetDrawable(ctx, "custom/medal_bronze")?.also { medalBronze = it }
            else -> null
        }
    }
}
