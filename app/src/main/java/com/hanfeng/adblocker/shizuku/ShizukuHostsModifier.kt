package com.hanfeng.adblocker.shizuku

import android.content.pm.PackageManager
import android.os.IInterface
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.Method

/**
 * Shizuku 增强工具 - Hosts 文件修改
 * 
 * 功能：
 * 1. 读取系统 Hosts 文件
 * 2. 添加/删除广告域名拦截规则
 * 3. 自动备份原始 Hosts
 */
class ShizukuHostsModifier {
    
    companion object {
        private const val TAG = "ShizukuHostsModifier"
        private const val HOSTS_PATH = "/system/etc/hosts"
        private const val HOSTS_BACKUP_PATH = "/data/local/tmp/hosts.backup"
        
        // 我们的拦截规则标记
        private const val MARKER_START = "# === HF AdBlocker Start ==="
        private const val MARKER_END = "# === HF AdBlocker End ==="
    }
    
    private var packageManager: PackageManager? = null
    
    interface Callback {
        fun onSuccess(message: String)
        fun onError(error: String)
    }
    
    data class HostsEntry(
        val ip: String,
        val hostname: String,
        val isComment: Boolean = false
    )
    
    /**
     * 设置 PackageManager（用于获取应用包名）
     */
    fun setPackageManager(pm: PackageManager) {
        this.packageManager = pm
    }
    
    /**
     * 读取当前 Hosts 文件内容
     */
    fun readHosts(): List<String> {
        return try {
            val process = ProcessBuilder("su", "-c", "cat $HOSTS_PATH").start()
            process.inputStream.bufferedReader().readLines()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read hosts", e)
            emptyList()
        }
    }
    
    /**
     * 添加拦截规则（将域名指向 127.0.0.1）
     */
    fun addBlockedDomains(domains: List<String>, callback: Callback) {
        Thread {
            try {
                // 1. 备份原始 Hosts
                backupHosts()
                
                // 2. 读取当前 Hosts
                val currentLines = readHosts()
                
                // 3. 移除旧的拦截规则（如果有）
                val filteredLines = removeOldRules(currentLines)
                
                // 4. 添加新的拦截规则
                val newLines = filteredLines + MARKER_START +
                        domains.map { "127.0.0.1 $it" } +
                        MARKER_END
                
                // 5. 写回 Hosts 文件
                writeHosts(newLines)
                
                callback.onSuccess("已添加 ${domains.size} 个域名到 Hosts 拦截列表")
                Log.d(TAG, "Added ${domains.size} domains to hosts blocklist")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add blocked domains", e)
                callback.onError("修改 Hosts 失败：${e.message}")
            }
        }.start()
    }
    
    /**
     * 移除拦截规则
     */
    fun removeBlockedDomains(callback: Callback) {
        Thread {
            try {
                val currentLines = readHosts()
                val filteredLines = removeOldRules(currentLines)
                writeHosts(filteredLines)
                callback.onSuccess("已清除 Hosts 拦截规则")
            } catch (e: Exception) {
                callback.onError("清除失败：${e.message}")
            }
        }.start()
    }
    
    /**
     * 移除旧的拦截规则
     */
    private fun removeOldRules(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        var inBlock = false
        
        for (line in lines) {
            when {
                line.trim() == MARKER_START -> {
                    inBlock = true
                }
                line.trim() == MARKER_END -> {
                    inBlock = false
                }
                !inBlock -> {
                    result.add(line)
                }
            }
        }
        
        return result
    }
    
    /**
     * 备份原始 Hosts 文件
     */
    private fun backupHosts() {
        try {
            Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "cp $HOSTS_PATH $HOSTS_BACKUP_PATH"
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to backup hosts", e)
        }
    }
    
    /**
     * 写回 Hosts 文件
     */
    private fun writeHosts(lines: List<String>) {
        val content = lines.joinToString("\n")
        val process = ProcessBuilder("su", "-c", "cat > $HOSTS_PATH").start()
        process.outputStream.writer().use {
            it.write(content)
            it.flush()
        }
        process.waitFor()
        
        // 设置正确权限
        Runtime.getRuntime().exec(arrayOf(
            "su", "-c",
            "chmod 644 $HOSTS_PATH"
        ))
    }
    
    /**
     * 恢复原始 Hosts 文件
     */
    fun restoreOriginalHosts() {
        try {
            Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "if [ -f $HOSTS_BACKUP_PATH ]; then cp $HOSTS_BACKUP_PATH $HOSTS_PATH; fi"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore hosts", e)
        }
    }
    
    /**
     * 检查 Hosts 修改是否生效
     */
    fun verifyHostsBlock(hostname: String): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "getent hosts $hostname").start()
            val result = process.inputStream.bufferedReader().readText()
            result.contains("127.0.0.1") || result.contains("::1")
        } catch (e: Exception) {
            false
        }
    }
}
