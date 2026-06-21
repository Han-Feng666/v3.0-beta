package com.HanFeng.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.HanFeng.model.RemoteRuleSourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

object RemoteRuleSourceRepository {
    private const val PREFS = "remote_rule_source_repo"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val CONNECT_TIMEOUT_MILLIS = 20_000
    private const val READ_TIMEOUT_MILLIS = 120_000
    private const val SYNC_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
    private const val MAX_TEXT_DOWNLOAD_BYTES = 512 * 1024

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
        val addedCount: Int? = null,
        val detail: String? = null
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
        var downloadedBytes = 0L
        runCatching {
            onDetailedProgress?.invoke(RemoteRuleSyncProgress(1, 1, source, RemoteRuleSyncProgress.Stage.CONNECTING))
            onDetailedProgress?.invoke(RemoteRuleSyncProgress(1, 1, source, RemoteRuleSyncProgress.Stage.DOWNLOADING))
            val tempFile = downloadToFile(context, source.url) { bytesRead, totalBytes ->
                downloadedBytes = bytesRead
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
            var handOffTempFileToBackground = false
            val addedCount = try {
                downloadedBytes = tempFile.length()
                onDetailedProgress?.invoke(RemoteRuleSyncProgress(1, 1, source, RemoteRuleSyncProgress.Stage.IMPORTING, bytesRead = downloadedBytes))
                RuleRepository.prepareForRuleImport(context, "remote source ${source.id}, size=${formatBytes(downloadedBytes)}")
                val fastAdded = RuleRepository.replaceRulesForRemoteSourceStreaming(
                    context,
                    source.id,
                    tempFile.inputStream(),
                    allowWhitelistDomains = whitelistImportMode != WhitelistImportMode.BLOCK
                ) { detail ->
                    onDetailedProgress?.invoke(
                        RemoteRuleSyncProgress(
                            current = 1,
                            total = 1,
                            source = source,
                            stage = RemoteRuleSyncProgress.Stage.IMPORTING,
                            bytesRead = downloadedBytes,
                            detail = detail
                        )
                    )
                }
                RuleRepository.scheduleBackgroundAdvancedImport(
                    context = context,
                    sourceLabel = source.name,
                    source = com.HanFeng.model.RuleSource.IMPORTED,
                    remoteSourceId = source.id,
                    allowWhitelistDomains = whitelistImportMode != WhitelistImportMode.BLOCK,
                    deleteFileWhenDone = tempFile
                ) {
                    tempFile.inputStream()
                }
                handOffTempFileToBackground = true
                fastAdded
            } finally {
                if (!handOffTempFileToBackground) runCatching { tempFile.delete() }
            }
            val totalTime = System.currentTimeMillis() - startTime

            val updatedSource = source.copy(
                lastUpdatedAt = System.currentTimeMillis(),
                lastRuleCount = addedCount,
                lastError = if (addedCount <= 0) "未导入任何规则，可能规则格式不正确" else null
            )
            RuleRepository.updateRemoteRuleSource(context, updatedSource)

            if (addedCount <= 0) {
                LogRepository.append(context, "规则源同步完成：${source.name}，未导入规则（格式问题）")
            } else {
                LogRepository.append(context, "规则源同步完成：${source.name}，导入${addedCount}条规则，耗时${totalTime / 1000}秒")
            }
            onDetailedProgress?.invoke(RemoteRuleSyncProgress(1, 1, updatedSource, RemoteRuleSyncProgress.Stage.COMPLETED, bytesRead = downloadedBytes, addedCount = addedCount))
            RemoteRuleSyncResult(
                source = updatedSource,
                success = addedCount > 0,
                addedCount = addedCount,
                errorMessage = if (addedCount <= 0) "未导入任何规则 (格式问题)" else null
            )
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
                is OutOfMemoryError -> "运行内存不足，导入失败。规则源大小：${formatBytes(downloadedBytes)}；请先减少已启用规则源数量或重启 App 后重试"
                else -> error.message ?: "未知错误"
            }
            val updatedSource = source.copy(lastError = message)
            RuleRepository.updateRemoteRuleSource(context, updatedSource)
            val totalTime = System.currentTimeMillis() - startTime
            LogRepository.append(context, "规则源同步失败：${source.name}，url=${source.url}，errorClass=${error.javaClass.name}，raw=${error.message ?: ""}，mapped=$message，耗时${totalTime / 1000}秒")
            RemoteRuleSyncResult(source = updatedSource, success = false, addedCount = 0, errorMessage = message)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0)
            else -> "${bytes}B"
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
        return withRuleSourceConnectionRetry(context, url) { connection ->
            val responseCode = connection.responseCode
            
            if (responseCode !in 200..299) {
                val errorText = runCatching {
                    connection.errorStream?.readTextPrefix(200)
                }.getOrNull().orEmpty().ifBlank { "HTTP $responseCode" }
                throw IOException("规则源下载失败：HTTP $responseCode ${errorText.trim()}")
            }
            
            val content = connection.inputStream.readLimitedText(MAX_TEXT_DOWNLOAD_BYTES)
            
            if (content.isBlank()) {
                throw IOException("规则源内容为空")
            }
            content
        }
    }

    // P0.4 新增：流式下载到临时文件（支持超大文件）
    private fun downloadToFile(context: Context, url: String, onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null): java.io.File {
        val cacheDir = context.cacheDir ?: throw IOException("无法访问缓存目录，请检查存储权限")
        val tempFile = java.io.File.createTempFile("rulesync_", ".txt", cacheDir)

        runCatching {
            val host = URL(url).host
            InetAddress.getByName(host)
        }.onFailure {
            throw IOException("无法解析规则源域名，请检查网络连接：$url")
        }
        
        try {
            withRuleSourceConnectionRetry(context, url) { connection ->
                val responseCode = connection.responseCode
                
                if (responseCode !in 200..299) {
                    val errorText = runCatching {
                        connection.errorStream?.readTextPrefix(200)
                    }.getOrNull().orEmpty().ifBlank { "HTTP $responseCode" }
                    throw IOException("规则源下载失败：HTTP $responseCode ${errorText.trim()}")
                }
                
                val contentLength = connection.contentLengthLong.takeIf { it > 0L }
                
                // P0.5 优化：使用更大的缓冲区，减少 IO 操作
                val buffer = ByteArray(256 * 1024) // 256KB 缓冲区
                var bytesRead: Int
                var totalBytes = 0L
                var lastProgressAt = 0L
                onProgress?.invoke(0L, contentLength)
                tempFile.outputStream().use { outputStream ->
                    connection.inputStream.use { inputStream ->
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            val now = System.currentTimeMillis()
                            if (now - lastProgressAt >= 500L || contentLength != null && totalBytes >= contentLength) {
                                lastProgressAt = now
                                onProgress?.invoke(totalBytes, contentLength)
                            }
                        }
                    }
                }
                onProgress?.invoke(totalBytes, contentLength)
                
                if (totalBytes == 0L) {
                    tempFile.delete()
                    throw IOException("规则源内容为空")
                }
            }
            return tempFile
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun openRuleSourceConnection(context: Context, url: String, preferNonVpnNetwork: Boolean = true): HttpURLConnection {
        val parsedUrl = URL(url)
        val connection = if (preferNonVpnNetwork) runCatching {
            selectNonVpnNetwork(context)?.openConnection(parsedUrl) as? HttpURLConnection
        }.getOrNull() ?: parsedUrl.openConnection() as HttpURLConnection else parsedUrl.openConnection() as HttpURLConnection
        return connection.apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            setRequestProperty("Accept", "text/plain,*/*;q=0.1")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            setRequestProperty("Connection", "close")
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun <T> withRuleSourceConnectionRetry(context: Context, url: String, block: (HttpURLConnection) -> T): T {
        var firstError: Throwable? = null
        listOf(true, false).forEach { preferNonVpnNetwork ->
            val connection = openRuleSourceConnection(context, url, preferNonVpnNetwork)
            try {
                return block(connection)
            } catch (error: Throwable) {
                firstError = error
                val canRetry = preferNonVpnNetwork && isLikelyVpnInterferenceError(error)
                if (!canRetry) throw error
                LogRepository.append(
                    context,
                    "Rule source non-VPN network failed, retrying with default network: url=$url, error=${error.message ?: error.javaClass.simpleName}"
                )
            } finally {
                connection.disconnect()
            }
        }
        throw firstError ?: IOException("规则源连接失败")
    }

    private fun isLikelyVpnInterferenceError(error: Throwable): Boolean {
        if (isNetworkBindPermissionError(error)) return true
        val message = error.message.orEmpty().lowercase()
        return message.contains("connect") && (message.contains("refused") || message.contains("timeout")) ||
            message.contains("network is unreachable") ||
            message.contains("no route to host") ||
            message.contains("host is unreachable") ||
            error.cause?.let(::isLikelyVpnInterferenceError) == true
    }

    private fun isNetworkBindPermissionError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("EPERM", ignoreCase = true) ||
            message.contains("Operation not permitted", ignoreCase = true) ||
            message.contains("Binding socket to network", ignoreCase = true) ||
            error.cause?.let(::isNetworkBindPermissionError) == true
    }

    @Suppress("DEPRECATION")
    private fun selectNonVpnNetwork(context: Context): android.net.Network? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val allNetworks = connectivityManager.allNetworks
        val nonVpnNetwork = allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (nonVpnNetwork != null) return nonVpnNetwork
        return allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun InputStream.readLimitedText(maxBytes: Int): String {
        return bufferedReader(Charsets.UTF_8).use { reader ->
            val builder = StringBuilder()
            val buffer = CharArray(4096)
            var bytes = 0
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                bytes += read * 2
                if (bytes > maxBytes) {
                    throw IOException("规则源文本内容超过 ${maxBytes / 1024}KB，请使用流式同步导入")
                }
                builder.append(buffer, 0, read)
            }
            builder.toString()
        }
    }

    private fun InputStream.readTextPrefix(maxChars: Int): String {
        return bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(maxChars)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
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
