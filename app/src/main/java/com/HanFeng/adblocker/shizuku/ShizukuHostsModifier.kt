package com.HanFeng.adblocker.shizuku

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShizukuHostsModifier {

    companion object {
        private const val TAG = "ShizukuHostsModifier"
        private const val HOSTS_PATH = "/system/etc/hosts"
        private const val HOSTS_BACKUP_DIR = "/data/local/tmp"
        private const val MAX_BACKUPS = 5

        private const val MARKER_START = "# === HF AdBlocker Start ==="
        private const val MARKER_END = "# === HF AdBlocker End ==="
    }

    interface Callback {
        fun onSuccess(message: String)
        fun onError(error: String)
    }

    data class HostsEntry(
        val ip: String,
        val hostname: String,
        val isComment: Boolean = false
    )

    fun readHosts(): List<String> {
        return try {
            val result = SuSession.getInstance().execute("cat $HOSTS_PATH")
            if (result.exitCode == 0) result.output.lines() else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read hosts", e)
            emptyList()
        }
    }

    fun parseHosts(): List<HostsEntry> {
        return readHosts().mapNotNull { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> null
                trimmed.startsWith("#") -> HostsEntry("", trimmed, isComment = true)
                else -> {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 2) HostsEntry(parts[0], parts[1])
                    else HostsEntry("", trimmed, isComment = true)
                }
            }
        }
    }

    fun addBlockedDomains(domains: Set<String>, callback: Callback? = null) {
        Thread {
            try {
                val existingContent = readHosts()
                if (!checkFileSizeSafe()) {
                    callback?.onError("Hosts 文件过大，拒绝操作以保护系统稳定性")
                    return@Thread
                }

                if (!backupHosts()) {
                    callback?.onError("备份原始 Hosts 文件失败")
                    return@Thread
                }

                val newHostsContent = buildModifiedHosts(existingContent, domains)
                if (writeHostsAtomic(newHostsContent)) {
                    verifyAndFlush()
                    callback?.onSuccess("成功添加 ${domains.size} 个拦截域名")
                } else {
                    callback?.onError("写入 Hosts 文件失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add blocked domains", e)
                callback?.onError("添加拦截域名失败: ${e.message}")
            }
        }.start()
    }

    fun removeOldRules(callback: Callback? = null) {
        Thread {
            try {
                val existingContent = readHosts()
                val cleanedContent = stripHfRules(existingContent)

                if (writeHostsAtomic(cleanedContent.joinToString("\n"))) {
                    verifyAndFlush()
                    callback?.onSuccess("已移除所有拦截规则")
                } else {
                    callback?.onError("移除规则失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove rules", e)
                callback?.onError("移除规则失败: ${e.message}")
            }
        }.start()
    }

    fun restoreOriginalHosts(callback: Callback? = null) {
        Thread {
            try {
                val backups = listBackups()
                if (backups.isEmpty()) {
                    callback?.onError("未找到备份文件")
                    return@Thread
                }

                val latestBackup = backups.first()
                val content = SuSession.getInstance().execute("cat $latestBackup").output
                if (content.isBlank()) {
                    callback?.onError("备份文件为空")
                    return@Thread
                }

                val cleaned = stripHfRules(content.lines())
                if (writeHostsAtomic(cleaned.joinToString("\n"))) {
                    verifyAndFlush()
                    callback?.onSuccess("已从备份恢复原始 Hosts")
                } else {
                    callback?.onError("恢复 Hosts 失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore hosts", e)
                callback?.onError("恢复失败: ${e.message}")
            }
        }.start()
    }

    fun diffWithOriginal(): List<String> {
        val current = readHosts()
        val currentHfLines = current.filter { line ->
            val inBlock = false
            current.indexOf(line) > current.indexOf(MARKER_START)
        }
        return currentHfLines.filter { it.isNotBlank() && !it.startsWith("#") && it.contains("127.0.0.1") }
    }

    fun getInterceptedDomains(): Set<String> {
        val content = readHosts()
        var inBlock = false
        val domains = mutableSetOf<String>()
        for (line in content) {
            if (line.trim() == MARKER_START) inBlock = true
            else if (line.trim() == MARKER_END) inBlock = false
            else if (inBlock && !line.startsWith("#")) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 2 && parts[0] == "127.0.0.1") {
                    domains.add(parts[1])
                }
            }
        }
        return domains
    }

    fun listBackups(): List<String> {
        val result = SuSession.getInstance().execute("ls -1t $HOSTS_BACKUP_DIR/hosts.backup.* 2>/dev/null || echo NONE")
        return result.output.lines().filter { it.isNotBlank() && it != "NONE" }
    }

    fun verifyHostsBlock(domain: String): Boolean {
        val result = SuSession.getInstance().execute("getent hosts $domain 2>/dev/null || ping -c 1 -W 1 $domain 2>/dev/null | head -1 || echo FAIL")
        return result.output.contains("127.0.0.1")
    }

    private fun checkFileSizeSafe(): Boolean {
        val result = SuSession.getInstance().execute("stat -c%s $HOSTS_PATH 2>/dev/null || wc -c < $HOSTS_PATH 2>/dev/null || echo 0")
        val size = result.output.trim().toLongOrNull() ?: 0
        return size < 10 * 1024 * 1024
    }

    private fun backupHosts(): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val backupPath = "$HOSTS_BACKUP_DIR/hosts.backup.$timestamp"

        val result = SuSession.getInstance().execute("cp $HOSTS_PATH $backupPath && test -f $backupPath && echo OK || echo FAIL")
        if (!result.output.contains("OK")) return false

        cleanupOldBackups()
        return true
    }

    private fun cleanupOldBackups() {
        val backups = listBackups()
        if (backups.size > MAX_BACKUPS) {
            backups.drop(MAX_BACKUPS).forEach { path ->
                SuSession.getInstance().execute("rm -f $path 2>/dev/null")
            }
        }
    }

    private fun writeHostsAtomic(content: String): Boolean {
        val tmpPath = "$HOSTS_BACKUP_DIR/hosts.tmp.${System.currentTimeMillis()}"
        val session = SuSession.getInstance()

        val escaped = session.escapeShell(content)
        val writeResult = session.execute(
            "echo '$escaped' > $tmpPath && " +
            "mount -o remount,rw /system 2>/dev/null || true && " +
            "cat $tmpPath > $HOSTS_PATH && " +
            "mount -o remount,ro /system 2>/dev/null || true && " +
            "rm -f $tmpPath && " +
            "wc -l < $HOSTS_PATH"
        )

        val lineCount = writeResult.output.trim().toIntOrNull() ?: 0
        return writeResult.exitCode == 0 && lineCount > 0
    }

    private fun verifyAndFlush() {
        val session = SuSession.getInstance()
        session.execute("echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true")
        session.execute("killall -HUP dnsmasq 2>/dev/null || true")
        session.execute("ndc resolver flushdefaultif 2>/dev/null || true")
        session.execute("ndc resolver flushif wlan0 2>/dev/null || true")
        session.execute("setprop ctl.restart netd 2>/dev/null || true")
        Log.d(TAG, "DNS cache flushed")
    }

    private fun buildModifiedHosts(existingLines: List<String>, domains: Set<String>): String {
        val cleaned = stripHfRules(existingLines).toMutableList()
        cleaned.add("")
        cleaned.add(MARKER_START)
        cleaned.add("# HanFeng AdBlocker - 自动生成于 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        cleaned.add("# 拦截域名数: ${domains.size}")
        domains.sorted().forEach { domain ->
            cleaned.add("127.0.0.1 $domain")
        }
        cleaned.add(MARKER_END)
        return cleaned.joinToString("\n")
    }

    private fun stripHfRules(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        var skippingBlock = false
        for (line in lines) {
            if (line.trim() == MARKER_START) {
                skippingBlock = true
                continue
            }
            if (line.trim() == MARKER_END) {
                skippingBlock = false
                continue
            }
            if (!skippingBlock) result.add(line)
        }
        return result
    }
}
