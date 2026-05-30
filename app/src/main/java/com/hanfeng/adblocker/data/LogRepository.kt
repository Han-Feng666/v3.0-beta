package com.HanFeng.data

import android.content.Context
import androidx.core.content.FileProvider
import com.HanFeng.security.CertificateAuthorityManager
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogRepository {
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "adblock.log"
    private const val LOG_CHANNEL_CAPACITY = 2048
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<String>(capacity = LOG_CHANNEL_CAPACITY)
    private var writerJob: Job? = null
    private var currentContext: Context? = null
    private var snapshotExportJob: Job? = null
    private val droppedLogCount = AtomicInteger(0)
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
        "HTTPS request host=",
        "HTTPS response passthrough host=",
        "Accepted local HTTPS bridge client host=",
        "Connected local HTTPS bridge socket flow="
    )

    fun append(context: Context, message: String) {
        if (shouldDropNoisyLog(message)) return
        // Capture context once for the writer
        if (currentContext == null) currentContext = context.applicationContext
        flushDroppedNoticeIfNeeded()
        if (logChannel.trySend("${System.currentTimeMillis()} $message\n").isFailure) {
            droppedLogCount.incrementAndGet()
        }
        ensureWriterRunning()
        ensureSnapshotExport(context.applicationContext)
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
        if (writerJob?.isActive == true) return
        synchronized(this) {
            if (writerJob?.isActive == true) return
            writerJob = scope.launch {
                val ctx = currentContext ?: return@launch
                val file = logFile(ctx)
                file.parentFile?.mkdirs()
                val fos = FileOutputStream(file, true)
                val bos = BufferedOutputStream(fos, 8192)
                try {
                    while (isActive) {
                        val msg = logChannel.receive()
                        bos.write(msg.toByteArray())
                    }
                } catch (_: Exception) {
                    // Silent exit on channel close or error
                } finally {
                    runCatching { bos.flush() }
                    runCatching { fos.close() }
                }
            }
        }
    }

    fun flushAndClose() {
        logChannel.close()
        writerJob?.let { scope.runCatching { it.cancel() } }
    }

    fun exportZip(context: Context): android.net.Uri {
        val shareDir = File(context.cacheDir, "shared")
        shareDir.mkdirs()
        val zipFile = File(shareDir, "hanfeng-adblock-logs.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            val entryFile = logFile(context)
            if (entryFile.exists()) {
                zip.putNextEntry(ZipEntry(entryFile.name))
                zip.write(entryFile.readBytes())
                zip.closeEntry()
            }
            val suspiciousDomainReport = RuleRepository.exportUnknownVendorSamples(context)
            zip.putNextEntry(ZipEntry(String.format(Locale.US, "%s", "suspicious-domains.txt")))
            zip.write(suspiciousDomainReport.toByteArray())
            zip.closeEntry()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
    }

    private fun ensureSnapshotExport(context: Context) {
        if (snapshotExportJob?.isActive == true) return
        snapshotExportJob = scope.launch {
            delay(180_000L)
            runCatching {
                append(context, "Auto log snapshot triggered after 180 seconds, overwrite previous file")
                delay(300L)
                val snapshot = buildLogSnapshot(context)
                CertificateAuthorityManager.exportTextFileToDownloads(context, "日志文件.txt", snapshot)
            }
        }
    }

    private fun buildLogSnapshot(context: Context): String {
        val logText = runCatching {
            logFile(context).takeIf { it.exists() }?.readText().orEmpty()
        }.getOrDefault("")
        val header = buildString {
            append("寒枫运行日志快照\n")
            append("生成时间: ")
            append(System.currentTimeMillis())
            append("\n\n")
        }
        return header + logText
    }

    private fun logFile(context: Context): File = File(File(context.filesDir, LOG_DIR), LOG_FILE)
}
