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
    private const val MAX_LOG_FILE_BYTES = 2L * 1024 * 1024
    private const val LOG_TRUNCATE_KEEP_BYTES = 512L * 1024
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
    private const val WRITER_FLUSH_INTERVAL_MILLIS = 2_000L
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
                    latestByKey["${entry.type}:${entry.domain}"] = entry
                }
            }
        } catch (e: Exception) {
            append(context, "getDomainDecisionEntries parse error: ${e.message ?: e.javaClass.simpleName}")
        }
        return latestByKey.values.sortedByDescending(DomainDecisionEntry::timestamp)
    }

    private fun parseDomainDecision(timestamp: Long, message: String): DomainDecisionEntry? {
        val blockedMatcher = blockedDomainPattern.matcher(message)
        if (blockedMatcher.find()) {
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = blockedMatcher.group(1).orEmpty(),
                type = DomainDecisionType.BLOCKED,
                appName = extractAppName(message),
                message = message
            )
        }
        val passedMatcher = passedDomainPattern.matcher(message)
        if (passedMatcher.find()) {
            return DomainDecisionEntry(
                timestamp = timestamp,
                domain = passedMatcher.group(1).orEmpty(),
                type = DomainDecisionType.ALLOWED,
                appName = extractAppName(message),
                message = message
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

    data class DomainDecisionEntry(
        val timestamp: Long,
        val domain: String,
        val type: DomainDecisionType,
        val appName: String,
        val message: String
    )

    fun toggleDomainDecision(context: Context, domain: String, currentType: DomainDecisionType) {
        val newType = if (currentType == DomainDecisionType.BLOCKED) DomainDecisionType.ALLOWED else DomainDecisionType.BLOCKED
        val message = if (newType == DomainDecisionType.BLOCKED) {
            "Blocked request host=$domain via manual-toggle app=user"
        } else {
            "Passed request host=$domain via manual-toggle app=user"
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
