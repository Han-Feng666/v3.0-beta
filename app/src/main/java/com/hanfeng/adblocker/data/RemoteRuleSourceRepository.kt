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
    private const val CONNECT_TIMEOUT_MILLIS = 120_000  // 120 秒（大规则文件需要更长时间建立连接）
    private const val READ_TIMEOUT_MILLIS = 600_000  // 10 分钟（大规则文件下载可能需要更长时间）
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
        val startTime = System.currentTimeMillis()
        runCatching {
            val content = downloadText(context, source.url)
            val downloadTime = System.currentTimeMillis() - startTime
            
            // 优化：移除 analyze 步骤，直接导入
            // analyze 只用于用户手动导入时的预览，规则源同步不需要
            val importContent = when (whitelistImportMode) {
                WhitelistImportMode.REMOVE_CONFLICTS -> RuleRepository.removeWhitelistConflictLines(content)
                else -> content
            }
            
            val importStart = System.currentTimeMillis()
            val addedCount = RuleRepository.replaceRulesForRemoteSource(
                context,
                source.id,
                importContent,
                allowWhitelistDomains = true
            )
            val importTime = System.currentTimeMillis() - importStart
            val totalTime = System.currentTimeMillis() - startTime
            
            val nonAdCandidates = RuleRepository.getRemoteSourceNonAdCandidates(context, source.id)
            val updatedSource = source.copy(
                lastUpdatedAt = System.currentTimeMillis(),
                lastRuleCount = addedCount,
                lastError = if (addedCount <= 0) "未导入任何规则，可能规则格式不正确" else null
            )
            RuleRepository.updateRemoteRuleSource(context, updatedSource)
            
            if (addedCount <= 0) {
                LogRepository.append(context, "Remote rule source synced but no rules added: ${source.name}, whitelistMode=$whitelistImportMode, contentLength=${importContent.length}")
            } else if (nonAdCandidates.isNotEmpty()) {
                LogRepository.append(context, "Remote rule source synced: ${source.name} count=$addedCount candidateNonAds=${nonAdCandidates.size} downloadTime=${downloadTime}ms importTime=${importTime}ms totalTime=${totalTime}ms")
            } else {
                LogRepository.append(context, "Remote rule source synced: ${source.name} count=$addedCount downloadTime=${downloadTime}ms importTime=${importTime}ms totalTime=${totalTime}ms")
            }
            RemoteRuleSyncResult(
                source = updatedSource,
                success = addedCount > 0,
                addedCount = addedCount,
                filteredCount = 0,
                nonAdCandidates = nonAdCandidates,
                errorMessage = if (addedCount <= 0) "未导入任何规则 (格式问题)" else null
            )
        }.getOrElse { error ->
            val message = when (error) {
                is java.net.SocketTimeoutException -> "连接超时 (超过 5 分钟)，大规则文件可能需要更长时间，请检查网络或规则源大小"
                is java.net.ConnectException -> "无法连接到规则源服务器"
                is java.net.UnknownHostException -> "无法解析域名，请检查网络"
                is java.io.IOException -> error.message ?: "网络错误"
                else -> error.message ?: error.javaClass.simpleName
            }
            val updatedSource = source.copy(lastError = message)
            RuleRepository.updateRemoteRuleSource(context, updatedSource)
            val totalTime = System.currentTimeMillis() - startTime
            LogRepository.append(context, "Remote rule source sync failed: ${source.name} error=$message totalTime=${totalTime}ms")
            RemoteRuleSyncResult(source = updatedSource, success = false, addedCount = 0, errorMessage = message)
        }
    }

    private fun downloadText(context: Context, url: String): String {
        val startTime = System.currentTimeMillis()
        
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            setRequestProperty("Accept", "text/plain,*/*;q=0.1")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            setRequestProperty("Connection", "close")
            setRequestProperty("Accept-Encoding", "identity")
        }
        
        try {
            LogRepository.append(context, "downloadText: connecting to $url")
            
            val connectStart = System.currentTimeMillis()
            val responseCode = connection.responseCode
            val connectTime = System.currentTimeMillis() - connectStart
            LogRepository.append(context, "downloadText: responseCode=$responseCode, connectTime=${connectTime}ms")
            
            if (responseCode !in 200..299) {
                val errorText = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(200) }
                }.getOrNull().orEmpty().ifBlank { "HTTP $responseCode" }
                LogRepository.append(context, "downloadText: error response, code=$responseCode, body=$errorText")
                throw IOException("规则源下载失败：HTTP $responseCode ${errorText.trim()}")
            }
            
            val readStart = System.currentTimeMillis()
            val expectedLength = connection.contentLength
            LogRepository.append(context, "downloadText: expectedLength=$expectedLength bytes")
            
            val inputStream = connection.inputStream
            val bufferedReader = inputStream.bufferedReader(Charsets.UTF_8)
            val content = bufferedReader.use { it.readText() }
            val readTime = System.currentTimeMillis() - readStart
            val totalTime = System.currentTimeMillis() - startTime
            LogRepository.append(context, "downloadText: contentLength=${content.length}B, readTime=${readTime}ms, totalTime=${totalTime}ms")
            
            if (content.isBlank()) {
                throw IOException("规则源内容为空")
            }
            return content
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            LogRepository.append(context, "downloadText: failed after ${totalTime}ms, error=${e.message ?: e.javaClass.simpleName}")
            throw e
        } finally {
            connection.disconnect()
        }
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
