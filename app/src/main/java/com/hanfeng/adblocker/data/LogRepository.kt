package com.HanFeng.data

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogRepository {
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "adblock.log"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private var writerJob: Job? = null
    private var currentContext: Context? = null

    fun append(context: Context, message: String) {
        // Capture context once for the writer
        if (currentContext == null) currentContext = context.applicationContext
        logChannel.trySend("${System.currentTimeMillis()} $message\n")
        ensureWriterRunning()
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

    private fun logFile(context: Context): File = File(File(context.filesDir, LOG_DIR), LOG_FILE)
}
