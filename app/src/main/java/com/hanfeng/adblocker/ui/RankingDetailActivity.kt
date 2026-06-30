package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.data.StatsRepository
import com.HanFeng.databinding.ActivityRankingDetailBinding
import com.HanFeng.model.RankingEntry
import com.HanFeng.model.RankingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class RankingDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityRankingDetailBinding
    private var rankingType: RankingType? = null
    private var lastRenderedContent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityRankingDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialTopPadding = binding.rankingDetailRoot.paddingTop
        val initialBottomPadding = binding.rankingDetailRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.rankingDetailRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top + initialTopPadding, view.paddingRight, systemBars.bottom + initialBottomPadding)
            insets
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "排行榜详情"
        rankingType = intent.getStringExtra(EXTRA_TYPE)?.let { runCatching { RankingType.valueOf(it) }.getOrNull() }
        binding.titleText.text = title
        renderContent()
        binding.btnBack.setOnClickListener { finish() }
        StatsRepository.updates.observe(this) {
            renderContent()
        }
    }

    override fun onResume() {
        super.onResume()
        renderContent()
    }

    private fun renderContent() {
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { buildContent(rankingType) }
            if (content == lastRenderedContent) return@launch
            val previousScrollY = binding.rankingScroll.scrollY
            lastRenderedContent = content
            binding.contentText.text = content
            binding.rankingScroll.post {
                binding.rankingScroll.scrollTo(0, previousScrollY)
            }
        }
    }

    private fun buildContent(type: RankingType?): String {
        val rankingType = type ?: return "当前暂无数据"
        val data = StatsRepository.getRanking(this, rankingType)
        if (data.isEmpty()) return "当前暂无数据"
        val rankingLines = data.mapIndexed { index, entry ->
            val displayEntry = if (rankingType.name.startsWith("APP_")) entry.copy(name = simplifyAppName(entry.name)) else entry
            "${index + 1}. ${displayEntry.name}    ${displayEntry.value}"
        }.joinToString("\n")
        val reasonSummary = buildReasonSummary(rankingType, data)
        return if (reasonSummary.isBlank()) {
            rankingLines
        } else {
            rankingLines + "\n\n最近原因摘要\n" + reasonSummary
        }
    }

    private fun buildReasonSummary(type: RankingType, data: List<RankingEntry>): String {
        val logFile = File(File(filesDir, "logs"), "adblock.log")
        if (!logFile.exists()) return ""
        val trackedEntries = data.take(10)
        if (trackedEntries.isEmpty()) return ""
        val reasonCounts = linkedMapOf<String, Int>()
        runCatching {
            readRecentLogLines(logFile, maxBytes = 256 * 1024, maxLines = 1500).forEach { line ->
                val reason = line.substringAfter(" reason=", "").substringBefore(' ').trim()
                if (reason.isBlank()) return@forEach
                if (!matchesRankingLine(line, type, trackedEntries)) return@forEach
                reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
            }
        }
        if (reasonCounts.isEmpty()) return ""
        return reasonCounts.entries
            .sortedByDescending { it.value }
            .take(8)
            .joinToString("\n") { (reason, count) -> "$reason    $count" }
    }

    private fun readRecentLogLines(file: File, maxBytes: Int, maxLines: Int): List<String> {
        if (!file.exists() || file.length() <= 0L) return emptyList()
        return RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - maxBytes).coerceAtLeast(0L)
            val size = (raf.length() - start).coerceAtMost(maxBytes.toLong()).toInt()
            val buffer = ByteArray(size)
            raf.seek(start)
            raf.readFully(buffer)
            String(buffer, Charsets.UTF_8).lines().takeLast(maxLines)
        }
    }

    private fun matchesRankingLine(line: String, type: RankingType, entries: List<RankingEntry>): Boolean {
        return when (type) {
            RankingType.VENDOR_BLOCKED,
            RankingType.VENDOR_REQUEST,
            RankingType.VENDOR_RESPONSE -> {
                val vendor = line.substringAfter(" vendor=", "").substringBefore(" reason=").substringBefore(' ').trim()
                vendor.isNotBlank() && entries.any { it.name == vendor }
            }

            RankingType.APP_BLOCKED,
            RankingType.APP_REQUEST,
            RankingType.APP_RESPONSE -> {
                val appName = line.substringAfter(" app=", "").substringBefore(" vendor=").trim()
                val normalizedAppName = simplifyAppName(appName)
                normalizedAppName.isNotBlank() && entries.any { simplifyAppName(it.name) == normalizedAppName }
            }
        }
    }

    private fun simplifyAppName(rawName: String): String {
        val trimmed = rawName.trim()
        val suffix = Regex("\\s*\\([a-zA-Z0-9_.]+\\)$")
        return trimmed.replace(suffix, "").ifBlank { trimmed }
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TYPE = "extra_type"

        fun createIntent(context: Context, title: String, type: RankingType): Intent {
            return Intent(context, RankingDetailActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TYPE, type.name)
        }
    }
}
