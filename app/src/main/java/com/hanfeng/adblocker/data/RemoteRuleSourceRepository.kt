package com.HanFeng.data

import android.content.Context
import com.HanFeng.model.RemoteRuleSourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object RemoteRuleSourceRepository {
    private const val PREFS = "remote_rule_source_repo"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 20_000
    private const val SYNC_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

    enum class WhitelistImportMode {
        BLOCK,
        ALLOW,
        REMOVE_CONFLICTS
    }

    suspend fun syncEnabledSources(context: Context, allowWhitelistDomains: Boolean = false): List<RemoteRuleSyncResult> = withContext(Dispatchers.IO) {
        val results = RuleRepository.getRemoteRuleSources(context).filter { it.enabled }.map { source ->
            syncSource(context, source, allowWhitelistDomains)
        }
        updateLastSyncAtIfSuccessful(context, results)
        results
    }

    fun shouldSyncOnAppLaunch(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val lastSyncAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_AT, 0L)
        return lastSyncAt <= 0L || now - lastSyncAt >= SYNC_INTERVAL_MILLIS
    }

    fun getLastSyncAt(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_AT, 0L)
    }

    suspend fun syncSource(context: Context, source: RemoteRuleSourceConfig, allowWhitelistDomains: Boolean = false): RemoteRuleSyncResult = withContext(Dispatchers.IO) {
        syncSource(
            context = context,
            source = source,
            whitelistImportMode = if (allowWhitelistDomains) WhitelistImportMode.ALLOW else WhitelistImportMode.BLOCK
        )
    }

    suspend fun syncEnabledSources(context: Context, whitelistImportMode: WhitelistImportMode): List<RemoteRuleSyncResult> = withContext(Dispatchers.IO) {
        val results = RuleRepository.getRemoteRuleSources(context).filter { it.enabled }.map { source ->
            syncSource(context, source, whitelistImportMode)
        }
        updateLastSyncAtIfSuccessful(context, results)
        results
    }

    suspend fun syncSource(
        context: Context,
        source: RemoteRuleSourceConfig,
        whitelistImportMode: WhitelistImportMode
    ): RemoteRuleSyncResult = withContext(Dispatchers.IO) {
        runCatching {
            val content = downloadText(source.url)
            val analysis = RuleRepository.analyzeImportContent(context, content)
            if (analysis.whitelistConflictRules > 0 && whitelistImportMode == WhitelistImportMode.BLOCK) {
                return@withContext RemoteRuleSyncResult(
                    source = source,
                    success = false,
                    addedCount = 0,
                    whitelistConflictRules = analysis.whitelistConflictRules,
                    whitelistConflictSamples = analysis.sampleWhitelistConflictLines,
                    errorMessage = "规则源包含疑似白名单规则，等待确认"
                )
            }
            val importContent = when (whitelistImportMode) {
                WhitelistImportMode.REMOVE_CONFLICTS -> RuleRepository.removeWhitelistConflictLines(content)
                else -> content
            }
            val addedCount = RuleRepository.replaceRulesForRemoteSource(
                context,
                source.id,
                importContent,
                whitelistImportMode == WhitelistImportMode.ALLOW
            )
            val nonAdCandidates = RuleRepository.getRemoteSourceNonAdCandidates(context, source.id)
            val updatedSource = source.copy(
                lastUpdatedAt = System.currentTimeMillis(),
                lastRuleCount = addedCount,
                lastError = null
            )
            RuleRepository.updateRemoteRuleSource(context, updatedSource)
            LogRepository.append(context, "Remote rule source synced: ${source.name} count=$addedCount candidateNonAds=${nonAdCandidates.size}")
            RemoteRuleSyncResult(
                source = updatedSource,
                success = true,
                addedCount = addedCount,
                filteredCount = 0,
                nonAdCandidates = nonAdCandidates
            )
        }.getOrElse { error ->
            val message = error.message ?: error.javaClass.simpleName
            val updatedSource = source.copy(lastError = message)
            RuleRepository.updateRemoteRuleSource(context, updatedSource)
            LogRepository.append(context, "Remote rule source sync failed: ${source.name} error=$message")
            RemoteRuleSyncResult(source = updatedSource, success = false, addedCount = 0, errorMessage = message)
        }
    }

    private fun downloadText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HanFeng/5.9.7-beta")
        }
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorText = runCatching {
                connection.errorStream?.bufferedReader()?.use { it.readText().take(200) }
            }.getOrNull().orEmpty().ifBlank { "HTTP $responseCode" }
            throw IOException("规则源下载失败: HTTP $responseCode ${errorText.trim()}")
        }
        val content = connection.inputStream.bufferedReader().use { it.readText() }
        if (content.isBlank()) {
            throw IOException("规则源内容为空")
        }
        return content
    }

    private fun updateLastSyncAtIfSuccessful(context: Context, results: List<RemoteRuleSyncResult>) {
        if (results.isEmpty()) return
        if (results.none { it.success }) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
            .apply()
    }

    data class RemoteRuleSyncResult(
        val source: RemoteRuleSourceConfig,
        val success: Boolean,
        val addedCount: Int,
        val filteredCount: Int = 0,
        val nonAdCandidates: List<RuleRepository.RemoteRuleRemovalCandidate> = emptyList(),
        val whitelistConflictRules: Int = 0,
        val whitelistConflictSamples: List<String> = emptyList(),
        val errorMessage: String? = null
    )
}
