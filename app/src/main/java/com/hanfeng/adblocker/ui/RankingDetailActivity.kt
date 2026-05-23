package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.HanFeng.data.StatsRepository
import com.HanFeng.databinding.ActivityGuideBinding
import com.HanFeng.model.RankingType

class RankingDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGuideBinding
    private var rankingType: RankingType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialTopPadding = binding.guideRoot.paddingTop
        val initialBottomPadding = binding.guideRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.guideRoot) { view, insets ->
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
        binding.contentText.text = buildContent(rankingType)
    }

    private fun buildContent(type: RankingType?): String {
        val rankingType = type ?: return "当前暂无数据"
        val data = StatsRepository.getRanking(this, rankingType)
        if (data.isEmpty()) return "当前暂无数据"
        return data.mapIndexed { index, entry ->
            val displayEntry = if (rankingType.name.startsWith("APP_")) entry.copy(name = simplifyAppName(entry.name)) else entry
            "${index + 1}. ${displayEntry.name}    ${displayEntry.value}"
        }.joinToString("\n")
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
