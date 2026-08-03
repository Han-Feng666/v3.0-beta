package com.HanFeng.data

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import com.HanFeng.security.CertificateAuthorityManager
import com.HanFeng.model.RuleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogRepository {
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "adblock.log"
    private const val LOG_EXPORT_FILE = "HanFeng-logs.zip"
    private const val LOG_CHANNEL_CAPACITY = 2048
    private const val LOG_SNAPSHOT_MAX_BYTES = 512 * 1024
    private const val MAX_LOG_FILE_BYTES = 8L * 1024 * 1024
    private const val LOG_TRUNCATE_KEEP_BYTES = 2L * 1024 * 1024
    private val legacyLogExportNames = setOf("hanfeng-adblock-logs.zip")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var logChannel = Channel<String>(capacity = LOG_CHANNEL_CAPACITY)
    @Volatile private var writerJob: Job? = null
    private var currentContext: Context? = null
    private var snapshotExportJob: Job? = null
    private val droppedLogCount = AtomicInteger(0)
    @Volatile private var currentLogSessionId: String? = null
    @Volatile private var lastWriterFlushAt = 0L
    @Volatile private var lastFileTruncateAt = 0L
    private const val WRITER_FLUSH_INTERVAL_MILLIS = 500L
    private const val FILE_TRUNCATE_CHECK_INTERVAL_MILLIS = 120_000L
    private val noisyLogPrefixes = listOf(
        "HTTP/2 frame ",
        "HTTP/2 headers decoded ",
        "HTTP/2 header inspection ",
        "HTTP/2 action decision ",
        "HTTP/2 stream ",
        "HTTP/2 data inspection ",
        "HTTP/2 preface ",
        "HTTP/2 client rewrite buffering ",
        "HTTP/2 payload suppressed ",
        "HTTP/2 frame filtering tail-preserved ",
        "HTTP/2 request payload rewritten host=",
        "HTTPS request host=",
        "HTTPS response passthrough host=",
        "HTTPS response neutralized host=",
        "Accepted local HTTPS bridge client host=",
        "Connected local HTTPS bridge socket flow=",
        "TUN debug rate",
        "Skip suspicious sample:",
        "MITM full-capture circuit opened for",
        "Enabled MITM app full-capture routes",
        "Enabled global MITM full-capture routes",
        "Skipped MITM full-capture routes",
        "Scheduled VPN reload",
        "Reloaded VPN for new HTTP decrypt routes"
    )
    private val blockedDomainPattern = Pattern.compile("Blocked [^\\n]* domain=([^\\s]+)")
    private val passedDomainPattern = Pattern.compile("Passed [^\\n]* domain=([^\\s]+)")
    private val appPattern = Pattern.compile(" app=([^\\n]+?)(?: vendor=| reason=| source=| route=| bypass=|$)")
    // 加密 DNS ClientHello 拦截用 sni= 字段
    private val blockedSniPattern = Pattern.compile("Blocked [^\\n]*?\\bsni=([^\\s]+)")
    // BY-SNI 走 domain= 已被 blockedDomainPattern 命中；以下兜底处理无 domain= 的事件
    private val blockedIpCidrPattern = Pattern.compile("Blocked IP-CIDR flow ip=([^\\s]+).*?cidr=([^\\s]+)")
    private val blockedPortOnlyPattern = Pattern.compile("Blocked port-only flow ip=([^\\s]+).*?sourcePort=([^\\s]+)")
    private val blockedQuicCidrPattern = Pattern.compile("Blocked QUIC/HTTP3 via CIDR block list ip=([^\\s]+)")
    private val blockedQuicFlowIpPattern = Pattern.compile("Blocked QUIC/HTTP3 flow ip=([^\\s]+).*?route=global-mitm")
    private val blockedEncryptedDnsSniPattern = Pattern.compile("Blocked encrypted DNS .*?\\bsni=([^\\s]+)")
    private val blockedLearningFeedbackIpPattern = Pattern.compile("Blocked learning-feedback IP ip=([^\\s]+)")
    private val blockedAdIpCidrPattern = Pattern.compile("Blocked QUIC/HTTP3 via CIDR|Blocked IP-CIDR flow")

    fun append(context: Context, message: String) {
        if (shouldDropNoisyLog(message)) return
        // Capture context once for the writer
        if (currentContext == null) currentContext = context.applicationContext
        ensureFreshLogFile(context.applicationContext)
        flushDroppedNoticeIfNeeded()
        if (logChannel.trySend("${System.currentTimeMillis()} $message\n").isFailure) {
            droppedLogCount.incrementAndGet()
        }
        ensureWriterRunning()
    }

    private fun shouldDropNoisyLog(message: String): Boolean {
        return noisyLogPrefixes.any { prefix -> message.startsWith(prefix) }
    }

    private fun flushDroppedNoticeIfNeeded() {
        val dropped = droppedLogCount.getAndSet(0)
        if (dropped <= 0) return
        if (logChannel.trySend("${System.currentTimeMillis()} Log queue pressure dropped=$dropped\n").isFailure) {
            droppedLogCount.addAndGet(dropped)
        }
    }

    private fun ensureWriterRunning() {
        val job = writerJob
        if (job?.isActive == true) return
        synchronized(this) {
            if (writerJob?.isActive == true) return
            writerJob = scope.launch {
                var fos: FileOutputStream? = null
                var bos: BufferedOutputStream? = null
                try {
                    val ctx = currentContext ?: return@launch
                    val file = logFile(ctx)
                    file.parentFile?.mkdirs()
                    fos = FileOutputStream(file, true)
                    bos = BufferedOutputStream(fos, 8192)
                    while (isActive) {
                        val msg = logChannel.receive()
                        bos.write(msg.toByteArray())
                        val now = System.currentTimeMillis()
                        if (now - lastWriterFlushAt >= WRITER_FLUSH_INTERVAL_MILLIS) {
                            bos.flush()
                            lastWriterFlushAt = now
                        }
                        if (now - lastFileTruncateAt >= FILE_TRUNCATE_CHECK_INTERVAL_MILLIS) {
                            bos.flush()
                            truncateLogFileIfNeeded(ctx)
                            lastFileTruncateAt = now
                        }
                    }
                } catch (error: Throwable) {
                    if (isActive) {
                        Log.e("HanFengLogRepository", "Log writer stopped: ${error.message ?: error.javaClass.simpleName}", error)
                    }
                } finally {
                    runCatching { bos?.flush() }
                    runCatching { fos?.close() }
                    synchronized(this@LogRepository) {
                        writerJob = null
                    }
                }
            }
        }
    }

    private fun ensureFreshLogFile(context: Context) {
        val sessionId = context.packageName + ":" + android.os.Process.myPid()
        if (currentLogSessionId == sessionId) return
        synchronized(this) {
            if (currentLogSessionId == sessionId) return
            val file = logFile(context)
            file.parentFile?.mkdirs()
            runCatching { FileOutputStream(file, false).use { } }
            currentLogSessionId = sessionId
        }
    }

    private fun truncateLogFileIfNeeded(context: Context) {
        val file = logFile(context)
        if (!file.exists() || file.length() <= MAX_LOG_FILE_BYTES) return
        val keepStart = file.length() - LOG_TRUNCATE_KEEP_BYTES
        val tempFile = File(file.parentFile, "${LOG_FILE}.tmp")
        try {
            file.inputStream().use { input ->
                var remaining = keepStart
                while (remaining > 0L) {
                    val skipped = input.skip(remaining)
                    if (skipped <= 0L) break
                    remaining -= skipped
                }
                tempFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            tempFile.renameTo(file)
            append(context, "Log file truncated from ${file.length()} bytes")
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
        }
    }

    fun flushAndClose() {
        synchronized(this) {
            runCatching { logChannel.close() }
            writerJob?.let { scope.runCatching { it.cancel() } }
            writerJob = null
            logChannel = Channel(capacity = LOG_CHANNEL_CAPACITY)
        }
    }

    fun exportZip(context: Context): android.net.Uri? {
        return runCatching {
            val shareDir = File(context.cacheDir, "shared")
            shareDir.mkdirs()
            val zipFile = File(shareDir, LOG_EXPORT_FILE)
            FileOutputStream(zipFile).use { output -> output.write(buildZipBytes(context)) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        }.getOrNull()
    }

    fun exportZipToDownloads(context: Context): String? {
        return CertificateAuthorityManager.exportBinaryFileToDownloads(
            context = context,
            fileName = LOG_EXPORT_FILE,
            mimeType = "application/zip",
            bytes = buildZipBytes(context),
            cleanupFileNames = legacyLogExportNames
        )
    }

    fun getDomainDecisionEntries(context: Context): List<DomainDecisionEntry> {
        val file = logFile(context)
        if (!file.exists()) return emptyList()
        val latestByKey = linkedMapOf<String, DomainDecisionEntry>()
        try {
            file.forEachLine { rawLine ->
                val firstSpace = rawLine.indexOf(' ')
                if (firstSpace <= 0) return@forEachLine
                val timestamp = rawLine.substring(0, firstSpace).toLongOrNull() ?: return@forEachLine
                val message = rawLine.substring(firstSpace + 1)
                parseDomainDecision(timestamp, message)?.let { entry ->
                    // 用 (type, scope, identifier) 作 key 去重，避免不同 scope 但相同字符串的条目相互覆盖
                    latestByKey["${entry.type}:${entry.scope}:${entry.identifier}"] = entry
                }
            }
        } catch (e: Exception) {
            append(context, "getDomainDecisionEntries parse error: ${e.message ?: e.javaClass.simpleName}")
        }
        // 用 RuleRepository 当前 exceptionRule 状态纠正显示：日志只反映历史决策，
        // 规则库才是真相。若状态不一致则按规则库改判并回写一条日志以便下次直接命中。
        // 仅 DOMAIN scope 才能被规则库 toggle，IP/CIDR/Port/EncryptedDNS/Learning 类别
        // 不参与 rule-sync（规则库不支持），按日志原值返回。
        val exceptionDomains = runCatching { RuleRepository.getExceptedDomains(context) }.getOrDefault(emptySet())
        val userOwnedBlockedDomains = runCatching { RuleRepository.getUserOwnedBlockedDomains(context) }.getOrDefault(emptySet())
        val corrected = linkedMapOf<String, DomainDecisionEntry>()
        val seenKeys = linkedSetOf<String>()
        // 先放 ALLOWED（用户主动放行的优先显示在上方更直观），再放 BLOCKED
        latestByKey.values.forEach { entry ->
            val key = "${entry.type}:${entry.scope}:${entry.identifier}"
            if (key in seenKeys) return@forEach
            // 仅 DOMAIN 类型做 rule overlay 纠正
            if (entry.scope != DecisionScope.DOMAIN) {
                corrected[key] = entry
                seenKeys += key
                return@forEach
            }
            val now = System.currentTimeMillis()
            val correctedType = when {
                entry.domain in exceptionDomains -> DomainDecisionType.ALLOWED
                entry.domain in userOwnedBlockedDomains -> DomainDecisionType.BLOCKED
                else -> entry.type
            }
            if (correctedType != entry.type) {
                append(context, if (correctedType == DomainDecisionType.ALLOWED) {
                    "Passed request domain=${entry.domain} via rule-sync app=user"
                } else {
                    "Blocked request domain=${entry.domain} via rule-sync app=user"
                })
                val newKey = "${correctedType}:${entry.scope}:${entry.identifier}"
                corrected[newKey] = entry.copy(timestamp = now, type = correctedType)
                seenKeys += newKey
            } else {
                corrected[key] = entry
                seenKeys += key
            }
        }
        return corrected.values.sortedByDescending(DomainDecisionEntry::timestamp)
    }

    private fun parseDomainDecision(timestamp: Long, message: String): DomainDecisionEntry? {
        val appName = extractAppName(message)
        // 1) 带 domain= 字段的拦截（DNS / SNI / HTTP / HTTPS / ad-IP / QUIC-with-domain 等）— 最常见
        val blockedMatcher = blockedDomainPattern.matcher(message)
        if (blockedMatcher.find()) {
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = blockedMatcher.group(1).orEmpty(),
                type = DomainDecisionType.BLOCKED,
                appName = appName,
                message = message,
                identifier = blockedMatcher.group(1).orEmpty(),
                scope = DecisionScope.DOMAIN
            )
        }
        val passedMatcher = passedDomainPattern.matcher(message)
        if (passedMatcher.find()) {
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = passedMatcher.group(1).orEmpty(),
                type = DomainDecisionType.ALLOWED,
                appName = appName,
                message = message,
                identifier = passedMatcher.group(1).orEmpty(),
                scope = DecisionScope.DOMAIN
            )
        }
        // 2) IP-CIDR 拦截
        val ipCidrMatcher = blockedIpCidrPattern.matcher(message)
        if (ipCidrMatcher.find()) {
            val ip = ipCidrMatcher.group(1).orEmpty()
            val cidr = ipCidrMatcher.group(2).orEmpty()
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = ip,
                type = DomainDecisionType.BLOCKED,
                appName = appName,
                message = message,
                identifier = "$ip ($cidr)",
                scope = DecisionScope.IP_CIDR
            )
        }
        // 3) Port-only 拦截
        val portOnlyMatcher = blockedPortOnlyPattern.matcher(message)
        if (portOnlyMatcher.find()) {
            val ip = portOnlyMatcher.group(1).orEmpty()
            val sourcePort = portOnlyMatcher.group(2).orEmpty()
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = ip,
                type = DomainDecisionType.BLOCKED,
                appName = appName,
                message = message,
                identifier = "$ip:sourcePort=$sourcePort",
                scope = DecisionScope.PORT_ONLY
            )
        }
        // 4) QUIC CIDR block list 拦截
        val quicCidrMatcher = blockedQuicCidrPattern.matcher(message)
        if (quicCidrMatcher.find()) {
            val ip = quicCidrMatcher.group(1).orEmpty()
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = ip,
                type = DomainDecisionType.BLOCKED,
                appName = appName,
                message = message,
                identifier = ip,
                scope = DecisionScope.IP_CIDR
            )
        }
        // 5) QUIC global-mitm flow 拦截（未带 domain=，仅 IP）
        val quicFlowIpMatcher = blockedQuicFlowIpPattern.matcher(message)
        if (quicFlowIpMatcher.find()) {
            val ip = quicFlowIpMatcher.group(1).orEmpty()
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = ip,
                type = DomainDecisionType.BLOCKED,
                appName = appName,
                message = message,
                identifier = ip,
                scope = DecisionScope.IP_CIDR
            )
        }
        // 6) 加密 DNS ClientHello by blocker sni= 拦截
        val encDnsSniMatcher = blockedEncryptedDnsSniPattern.matcher(message)
        if (encDnsSniMatcher.find()) {
            val sni = encDnsSniMatcher.group(1).orEmpty()
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = sni,
                type = DomainDecisionType.BLOCKED,
                appName = appName,
                message = message,
                identifier = sni,
                scope = DecisionScope.ENCRYPTED_DNS_SNI
            )
        }
        // 7) Learning feedback IP 拦截
        val learningMatcher = blockedLearningFeedbackIpPattern.matcher(message)
        if (learningMatcher.find()) {
            val ip = learningMatcher.group(1).orEmpty()
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = ip,
                type = DomainDecisionType.BLOCKED,
                appName = appName,
                message = message,
                identifier = ip,
                scope = DecisionScope.LEARNING_FEEDBACK
            )
        }
        return null
    }

    private fun extractAppName(message: String): String {
        val matcher = appPattern.matcher(message)
        if (!matcher.find()) return "未知应用"
        return matcher.group(1).orEmpty().trim().ifBlank { "未知应用" }
    }

    private fun ensureSnapshotExport(context: Context) {}

    private fun buildLogSnapshot(context: Context): String {
        val logText = runCatching {
            logFile(context).takeIf { it.exists() }?.readRecentText(LOG_SNAPSHOT_MAX_BYTES).orEmpty()
        }.getOrDefault("")
        val header = buildString {
            append("寒枫运行日志快照\n")
            append("生成时间: ")
            append(System.currentTimeMillis())
            append("\n\n")
        }
        return header + logText
    }

    private fun buildZipBytes(context: Context): ByteArray {
        return java.io.ByteArrayOutputStream().use { byteStream ->
            ZipOutputStream(byteStream).use { zip ->
                val entryFile = logFile(context)
                if (entryFile.exists()) {
                    zip.putNextEntry(ZipEntry(entryFile.name))
                    entryFile.inputStream().use { input ->
                        input.copyTo(zip, bufferSize = 256 * 1024)
                    }
                    zip.closeEntry()
                }
                val suspiciousDomainReport = RuleRepository.exportUnknownVendorSamples(context)
                zip.putNextEntry(ZipEntry(String.format(Locale.US, "%s", "suspicious-domains.txt")))
                zip.write(suspiciousDomainReport.toByteArray())
                zip.closeEntry()
                val crashDir = File(context.filesDir, "crashes")
                val crashFiles = crashDir.listFiles()?.filter {
                    it.name.startsWith("crash_") && it.name.endsWith(".txt")
                } ?: emptyList()
                crashFiles.forEach { file ->
                    zip.putNextEntry(ZipEntry("crashes/${file.name}"))
                    file.inputStream().use { input ->
                        input.copyTo(zip, bufferSize = 256 * 1024)
                    }
                    zip.closeEntry()
                }
            }
            byteStream.toByteArray()
        }
    }

    private fun logFile(context: Context): File = File(File(context.filesDir, LOG_DIR), LOG_FILE)

    private fun File.readRecentText(maxBytes: Int): String {
        val start = (length() - maxBytes).coerceAtLeast(0L)
        return inputStream().use { input ->
            var remaining = start
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped <= 0L) break
                remaining -= skipped
            }
            val prefix = if (start > 0L) "... 已省略较早日志，仅显示最近 ${maxBytes / 1024}KB ...\n" else ""
            prefix + input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    enum class DomainDecisionType {
        BLOCKED,
        ALLOWED
    }

    enum class DecisionScope {
        DOMAIN,
        IP_CIDR,
        PORT_ONLY,
        ENCRYPTED_DNS_SNI,
        LEARNING_FEEDBACK
    }

    data class DomainDecisionEntry(
        val timestamp: Long,
        val domain: String,
        val type: DomainDecisionType,
        val appName: String,
        val message: String,
        val identifier: String = domain,
        val scope: DecisionScope = DecisionScope.DOMAIN
    )

    fun toggleDomainDecision(context: Context, domain: String, currentType: DomainDecisionType) {
        val newType = if (currentType == DomainDecisionType.BLOCKED) DomainDecisionType.ALLOWED else DomainDecisionType.BLOCKED
        val message = if (newType == DomainDecisionType.BLOCKED) {
            "Blocked request domain=$domain via manual-toggle app=user"
        } else {
            "Passed request domain=$domain via manual-toggle app=user"
        }
        append(context, "[DecisionToggle] 域名 $domain 已从 ${if (currentType == DomainDecisionType.BLOCKED) "拦截" else "放行"} 切换为 ${if (newType == DomainDecisionType.BLOCKED) "拦截" else "放行"}")
        append(context, message)

        if (newType == DomainDecisionType.BLOCKED) {
            val rules = RuleRepository.getRules(context)
            val exceptionIds = rules.filter {
                it.domain.equals(domain, ignoreCase = true) && it.source == RuleSource.MANUAL && it.exceptionRule
            }.map { it.id }.toSet()
            if (exceptionIds.isNotEmpty()) {
                val removed = RuleRepository.removeRulesByIds(context, exceptionIds)
                append(context, "已同步移除 $removed 条例外规则（转拦截）")
            }
            val rule = RuleRepository.addRule(context, domain, RuleSource.MANUAL)
            if (rule != null) {
                append(context, "已同步添加拦截规则: $domain")
            }
        } else {
            val rules = RuleRepository.getRules(context)
            val manualIds = rules.filter {
                it.domain.equals(domain, ignoreCase = true) && it.source == RuleSource.MANUAL && !it.exceptionRule
            }.map { it.id }.toSet()
            if (manualIds.isNotEmpty()) {
                val removed = RuleRepository.removeRulesByIds(context, manualIds)
                append(context, "已同步移除 $removed 条拦截规则")
            }
            val exceptionRule = RuleRepository.addExceptionRule(context, domain)
            if (exceptionRule != null) {
                append(context, "已同步添加放行规则: $domain")
            }
        }
    }
}
