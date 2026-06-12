package com.HanFeng.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.HanFeng.model.RemoteRuleSourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object RemoteRuleSourceRepository {
    private const val PREFS = "remote_rule_source_repo"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val CONNECT_TIMEOUT_MILLIS = 180_000  // 180 秒（3 分钟，大规则文件需要更长时间建立连接）
    private const val READ_TIMEOUT_MILLIS = 1_800_000  // 30 分钟（超大规则文件下载可能需要更长时间）
    private const val SYNC_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

    enum class WhitelistImportMode {
        BLOCK,
        ALLOW,
        REMOVE_CONFLICTS
    }

    data class RemoteRuleSyncProgress(
        val current: Int,
        val total: Int,
        val source: RemoteRuleSourceConfig,
        val stage: Stage,
        val bytesRead: Long = 0L,
        val totalBytes: Long? = null,
        val addedCount: Int? = null
    ) {
        enum class Stage { CONNECTING, DOWNLOADING, IMPORTING, COMPLETED }
    }

    suspend fun syncEnabledSources(
        context: Context,
        allowWhitelistDomains: Boolean = false,
        onProgress: ((current: Int, total: Int, source: RemoteRuleSourceConfig) -> Unit)? = null,
        onDetailedProgress: ((RemoteRuleSyncProgress) -> Unit)? = null
    ): List<RemoteRuleSyncResult> = withContext(Dispatchers.IO) {
        val enabledSources = RuleRepository.getRemoteRuleSources(context).filter { it.enabled }
        val results = enabledSources.mapIndexed { index, source ->
            onProgress?.invoke(index + 1, enabledSources.size, source)
            syncSource(context, source, allowWhitelistDomains) { progress ->
                onDetailedProgress?.invoke(progress.copy(current = index + 1, total = enabledSources.size))
            }
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

    suspend fun syncSource(
        context: Context,
        source: RemoteRuleSourceConfig,
        allowWhitelistDomains: Boolean = false,
        onDetailedProgress: ((RemoteRuleSyncProgress) -> Unit)? = null
    ): RemoteRuleSyncResult = withContext(Dispatchers.IO) {
        syncSource(
            context = context,
            source = source,
            whitelistImportMode = if (allowWhitelistDomains) WhitelistImportMode.ALLOW else WhitelistImportMode.BLOCK,
            onDetailedProgress = onDetailedProgress
        )
    }

    suspend fun syncEnabledSources(
        context: Context,
        whitelistImportMode: WhitelistImportMode,
        onProgress: ((current: Int, total: Int, source: RemoteRuleSourceConfig) -> Unit)? = null,
        onDetailedProgress: ((RemoteRuleSyncProgress) -> Unit)? = null
    ): List<RemoteRuleSyncResult> = withContext(Dispatchers.IO) {
        val enabledSources = RuleRepository.getRemoteRuleSources(context).filter { it.enabled }
        val results = enabledSources.mapIndexed { index, source ->
            onProgress?.invoke(index + 1, enabledSources.size, source)
            syncSource(context, source, whitelistImportMode) { progress ->
                onDetailedProgress?.invoke(progress.copy(current = index + 1, total = enabledSources.size))
            }
        }
        updateLastSyncAtIfSuccessful(context, results)
        results
    }

    suspend fun syncSource(
        context: Context,
        source: RemoteRuleSourceConfig,
        whitelistImportMode: WhitelistImportMode,
        onDetailedProgress: ((RemoteRuleSyncProgress) -> Unit)? = null
    ): RemoteRuleSyncResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        runCatching {
            onDetailedProgress?.invoke(RemoteRuleSyncProgress(1, 1, source, RemoteRuleSyncProgress.Stage.CONNECTING))
            // P0.4 增强：使用临时文件流式下载，避免大文件 OOM
            val tempFile = downloadToFile(context, source.url) { bytesRead, totalBytes ->
                onDetailedProgress?.invoke(
                    RemoteRuleSyncProgress(
                        current = 1,
                        total = 1,
                        source = source,
                        stage = RemoteRuleSyncProgress.Stage.DOWNLOADING,
                        bytesRead = bytesRead,
                        totalBytes = totalBytes
                    )
                )
            }
            try {
                // 优化：直接使用文件流式导入
                onDetailedProgress?.invoke(RemoteRuleSyncProgress(1, 1, source, RemoteRuleSyncProgress.Stage.IMPORTING, bytesRead = tempFile.length()))
                val addedCount = RuleRepository.replaceRulesForRemoteSourceStreaming(
                    context,
                    source.id,
                    tempFile.inputStream(),
                    allowWhitelistDomains = whitelistImportMode != WhitelistImportMode.BLOCK
                )
                val totalTime = System.currentTimeMillis() - startTime
                
                val updatedSource = source.copy(
                    lastUpdatedAt = System.currentTimeMillis(),
                    lastRuleCount = addedCount,
                    lastError = if (addedCount <= 0) "未导入任何规则，可能规则格式不正确" else null
                )
                RuleRepository.updateRemoteRuleSource(context, updatedSource)
                
                // P0.4 增强：详细日志记录
                if (addedCount <= 0) {
                    LogRepository.append(context, "规则源同步完成：${source.name}，未导入规则（格式问题）")
                } else {
                    LogRepository.append(context, "规则源同步完成：${source.name}，导入${addedCount}条规则，耗时${totalTime / 1000}秒")
                }
                onDetailedProgress?.invoke(RemoteRuleSyncProgress(1, 1, updatedSource, RemoteRuleSyncProgress.Stage.COMPLETED, bytesRead = tempFile.length(), addedCount = addedCount))
                RemoteRuleSyncResult(
                    source = updatedSource,
                    success = addedCount > 0,
                    addedCount = addedCount,
                    errorMessage = if (addedCount <= 0) "未导入任何规则 (格式问题)" else null
                )
            } finally {
                // 清理临时文件
                runCatching { tempFile.delete() }
            }
        }.getOrElse { error ->
            val message = when (error) {
                is java.net.SocketTimeoutException -> "连接超时（超过 30 分钟），规则文件过大或网络过慢"
                is java.net.ConnectException -> "无法连接到规则源服务器：${error.message ?: "连接被拒绝或路由不可达"}"
                is java.net.UnknownHostException -> "无法解析规则源域名：${error.message ?: "DNS 查询失败"}"
                is java.io.IOException -> {
                    if (source.url.contains("githubusercontent.com", ignoreCase = true)) {
                        "无法连接 GitHub 规则源：${error.message ?: "网络错误"}\n建议：\n1. 切换网络（WiFi↔移动数据）\n2. 修改 DNS: 8.8.8.8\n3. 稍后重试"
                    } else {
                        error.message ?: "网络错误，请检查 VPN 状态"
                    }
                }
                is IllegalStateException -> "VPN 服务未运行，规则源同步失败"
                is OutOfMemoryError -> "内存不足，规则文件过大"
                else -> error.message ?: "未知错误"
            }
            val updatedSource = source.copy(lastError = message)
            RuleRepository.updateRemoteRuleSource(context, updatedSource)
            val totalTime = System.currentTimeMillis() - startTime
            LogRepository.append(context, "规则源同步失败：${source.name}，url=${source.url}，errorClass=${error.javaClass.name}，raw=${error.message ?: ""}，mapped=$message，耗时${totalTime / 1000}秒")
            RemoteRuleSyncResult(source = updatedSource, success = false, addedCount = 0, errorMessage = message)
        }
    }

    // P0.4 新增：检测 hosts 格式文件
    private fun detectHostsFormat(file: java.io.File): Boolean {
        return file.inputStream().use { inputStream ->
            inputStream.bufferedReader().useLines { lines ->
                lines.take(100).any { line ->
                    val trimmed = line.trim()
                    trimmed.startsWith("0.0.0.0") || trimmed.startsWith("127.0.0.1")
                }
            }
        }
    }

    private fun downloadText(context: Context, url: String): String {
        val connection = openRuleSourceConnection(context, url).apply {
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
            val responseCode = connection.responseCode
            
            if (responseCode !in 200..299) {
                val errorText = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(200) }
                }.getOrNull().orEmpty().ifBlank { "HTTP $responseCode" }
                throw IOException("规则源下载失败：HTTP $responseCode ${errorText.trim()}")
            }
            
            val inputStream = connection.inputStream
            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            
            if (content.isBlank()) {
                throw IOException("规则源内容为空")
            }
            return content
        } catch (e: Exception) {
            throw e
        } finally {
            connection.disconnect()
        }
    }

    // P0.4 新增：流式下载到临时文件（支持超大文件）
    private fun downloadToFile(context: Context, url: String, onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null): java.io.File {
        val tempFile = java.io.File.createTempFile("rulesync_", ".txt", context.cacheDir)
        
        val connection = openRuleSourceConnection(context, url).apply {
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
            val responseCode = connection.responseCode
            
            if (responseCode !in 200..299) {
                val errorText = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(200) }
                }.getOrNull().orEmpty().ifBlank { "HTTP $responseCode" }
                throw IOException("规则源下载失败：HTTP $responseCode ${errorText.trim()}")
            }
            
            val inputStream = connection.inputStream
            val outputStream = tempFile.outputStream()
            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            
            // P0.5 优化：使用更大的缓冲区，减少 IO 操作
            val buffer = ByteArray(256 * 1024) // 256KB 缓冲区
            var bytesRead: Int
            var totalBytes = 0L
            var lastProgressAt = 0L
            onProgress?.invoke(0L, contentLength)
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
                val now = System.currentTimeMillis()
                if (now - lastProgressAt >= 500L || contentLength != null && totalBytes >= contentLength) {
                    lastProgressAt = now
                    onProgress?.invoke(totalBytes, contentLength)
                }
            }
            onProgress?.invoke(totalBytes, contentLength)
            
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            if (totalBytes == 0L) {
                tempFile.delete()
                throw IOException("规则源内容为空")
            }
            
            return tempFile
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun openRuleSourceConnection(context: Context, url: String): HttpURLConnection {
        val parsedUrl = URL(url)
        val connection = runCatching {
            selectNonVpnNetwork(context)?.openConnection(parsedUrl) as? HttpURLConnection
        }.getOrNull() ?: parsedUrl.openConnection() as HttpURLConnection
        return connection
    }

    @Suppress("DEPRECATION")
    private fun selectNonVpnNetwork(context: Context): android.net.Network? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
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
