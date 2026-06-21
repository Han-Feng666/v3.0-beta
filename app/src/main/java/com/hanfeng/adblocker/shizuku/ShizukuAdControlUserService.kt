package com.HanFeng.shizuku

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit
import androidx.annotation.Keep

class ShizukuAdControlUserService() : IAdControlService.Stub() {

    private var serviceContext: Context? = null
    @Volatile private var lastOperationSummary: String = "idle"

    @Keep
    constructor(context: Context) : this() {
        serviceContext = context.applicationContext
    }

    override fun ping(): Boolean = true

    override fun blockPackageNotifications(packageName: String): Boolean {
        val notificationDisabled = runShellCommandWithFallback(
            listOf(
                listOf("cmd", "notification", "set_enabled", packageName, "0"),
                listOf("cmd", "notification", "set_enabled", packageName, "false")
            )
        )
        val channelsBlocked = blockAllNotificationChannels(packageName)
        val settingsBlocked = runShellCommandWithFallback(
            listOf(
                listOf("settings", "put", "secure", "${packageName}_notification", "0")
            )
        )
        val appOpsBlocked = runAppOpsBatch(
            packageName,
            modes = listOf(
                "POST_NOTIFICATION" to "ignore",
                "VIBRATE" to "ignore",
                "RUN_ANY_IN_BACKGROUND" to "ignore",
                "RUN_IN_BACKGROUND" to "ignore",
                "START_FOREGROUND" to "ignore",
                "WAKE_LOCK" to "ignore"
            )
        )
        return notificationDisabled || channelsBlocked || settingsBlocked || appOpsBlocked
    }

    override fun allowPackageNotifications(packageName: String): Boolean {
        val notificationEnabled = runShellCommandWithFallback(
            listOf(
                listOf("cmd", "notification", "set_enabled", packageName, "1"),
                listOf("cmd", "notification", "set_enabled", packageName, "true")
            )
        )
        val channelsRestored = restoreAllNotificationChannels(packageName)
        val settingsRestored = runShellCommandWithFallback(
            listOf(
                listOf("settings", "put", "secure", "${packageName}_notification", "1")
            )
        )
        val appOpsAllowed = runAppOpsBatch(
            packageName,
            modes = listOf(
                "POST_NOTIFICATION" to "allow",
                "VIBRATE" to "allow",
                "RUN_ANY_IN_BACKGROUND" to "allow",
                "RUN_IN_BACKGROUND" to "allow",
                "START_FOREGROUND" to "allow",
                "WAKE_LOCK" to "allow"
            )
        )
        return notificationEnabled || channelsRestored || settingsRestored || appOpsAllowed
    }

    override fun disablePackage(packageName: String): Boolean {
        return runShellCommandWithFallback(
            listOf(
                listOf("pm", "disable-user", "--user", "0", packageName),
                listOf("cmd", "package", "disable-user", "--user", "0", packageName)
            )
        )
    }

    override fun enablePackage(packageName: String): Boolean {
        return runShellCommandWithFallback(
            listOf(
                listOf("pm", "enable", packageName),
                listOf("cmd", "package", "enable", packageName)
            )
        )
    }

    override fun disableComponent(componentName: String): Boolean {
        val normalized = componentName.trim()
        if (normalized.isBlank()) return false
        return runShellCommandWithFallback(
            listOf(
                listOf("pm", "disable-user", "--user", "0", normalized),
                listOf("cmd", "package", "disable-user", "--user", "0", normalized)
            )
        )
    }

    override fun enableComponent(componentName: String): Boolean {
        val normalized = componentName.trim()
        if (normalized.isBlank()) return false
        return runShellCommandWithFallback(
            listOf(
                listOf("pm", "enable", normalized),
                listOf("cmd", "package", "enable", normalized)
            )
        )
    }

    override fun suspendPackage(packageName: String): Boolean {
        return runShellCommandWithFallback(
            listOf(
                listOf("pm", "suspend", "--user", "0", packageName),
                listOf("cmd", "package", "suspend", "--user", "0", packageName)
            )
        )
    }

    override fun unsuspendPackage(packageName: String): Boolean {
        return runShellCommandWithFallback(
            listOf(
                listOf("pm", "unsuspend", "--user", "0", packageName),
                listOf("cmd", "package", "unsuspend", "--user", "0", packageName)
            )
        )
    }

    override fun setNetworkBlocked(packageName: String, blocked: Boolean): Boolean {
        val mode = if (blocked) "ignore" else "allow"
        return runAppOpsBatch(packageName, listOf("INTERNET" to mode))
    }

    override fun setBackgroundRestricted(packageName: String, restricted: Boolean): Boolean {
        val mode = if (restricted) "ignore" else "allow"
        val standbyBucket = if (restricted) "restricted" else "active"
        val appOpsOk = runAppOpsBatch(
            packageName,
            listOf(
                "RUN_IN_BACKGROUND" to mode,
                "RUN_ANY_IN_BACKGROUND" to mode,
                "WAKE_LOCK" to mode
            )
        )
        val standbyOk = runShellCommandWithFallback(
            listOf(
                listOf("am", "set-standby-bucket", packageName, standbyBucket),
                listOf("cmd", "usage", "set-standby-bucket", packageName, standbyBucket)
            )
        )
        return appOpsOk || standbyOk
    }

    override fun syncHostsBlocklist(domains: Array<String>): Boolean {
        val sanitized = domains.map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' } }
            .distinct()
        if (sanitized.isEmpty()) return clearHostsBlocklist()
        val block = buildString {
            appendLine("# === HanFeng AdBlock Start ===")
            sanitized.forEach { domain -> appendLine("0.0.0.0 $domain") }
            appendLine("# === HanFeng AdBlock End ===")
        }
        val script = "tmp=\$(mktemp); sed '/# === HanFeng AdBlock Start ===/,/# === HanFeng AdBlock End ===/d' /system/etc/hosts > \$tmp; printf '%s\\n' '${escapeShellSingleQuoted(block)}' >> \$tmp; cp \$tmp /system/etc/hosts; chmod 644 /system/etc/hosts"
        return runShellCommandWithFallback(listOf(listOf("sh", "-c", script)))
    }

    override fun clearHostsBlocklist(): Boolean {
        val script = "tmp=\$(mktemp); sed '/# === HanFeng AdBlock Start ===/,/# === HanFeng AdBlock End ===/d' /system/etc/hosts > \$tmp; cp \$tmp /system/etc/hosts; chmod 644 /system/etc/hosts"
        return runShellCommandWithFallback(listOf(listOf("sh", "-c", script)))
    }

    override fun uninstallPackageForUser(packageName: String, userId: Int): Boolean {
        val safeUserId = if (userId >= 0) userId else 0
        return runShellCommandWithFallback(
            listOf(
                listOf("pm", "uninstall", "--user", safeUserId.toString(), packageName),
                listOf("cmd", "package", "uninstall", "--user", safeUserId.toString(), packageName)
            )
        )
    }

    override fun isPackageInstalled(packageName: String): Boolean {
        val context = serviceContext ?: return false
        return runCatching {
            context.packageManager.getPackageInfo(packageName, packageQueryFlags())
            true
        }.getOrDefault(false)
    }

    override fun getPackageEnabledState(packageName: String): Int {
        val context = serviceContext ?: return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        return runCatching {
            context.packageManager.getApplicationEnabledSetting(packageName)
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    override fun isPackageSuspended(packageName: String): Boolean {
        val context = serviceContext ?: return false
        return runCatching {
            context.packageManager.getPackageInfo(packageName, packageQueryFlags()).applicationInfo?.flags?.let { flags ->
                (flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
            } ?: false
        }.getOrDefault(false)
    }

    override fun getLastOperationSummary(): String = lastOperationSummary

    override fun destroy() {
        System.exit(0)
    }

    private fun blockAllNotificationChannels(packageName: String): Boolean {
        val listResult = runCommand(listOf("cmd", "notification", "list", packageName))
        if (!listResult.success) return false
        val channelIds = parseNotificationChannelIds(listResult.summary)
        if (channelIds.isEmpty()) return false
        var anyBlocked = false
        channelIds.forEach { channelId ->
            val blockResult = runCommand(listOf("cmd", "notification", "set_importance", packageName, channelId, "0"))
            if (blockResult.success) anyBlocked = true
        }
        return anyBlocked
    }

    private fun restoreAllNotificationChannels(packageName: String): Boolean {
        val listResult = runCommand(listOf("cmd", "notification", "list", packageName))
        if (!listResult.success) return false
        val channelIds = parseNotificationChannelIds(listResult.summary)
        if (channelIds.isEmpty()) {
            return runShellCommandWithFallback(
                listOf(
                    listOf("cmd", "notification", "set_enabled", packageName, "1"),
                    listOf("cmd", "notification", "set_enabled", packageName, "true")
                )
            )
        }
        var anyRestored = false
        channelIds.forEach { channelId ->
            val restoreResult = runCommand(listOf("cmd", "notification", "set_importance", packageName, channelId, "3"))
            if (restoreResult.success) anyRestored = true
        }
        return anyRestored
    }

    private fun parseNotificationChannelIds(summary: String): List<String> {
        val channelIds = mutableListOf<String>()
        val regex = Regex("id=([^\\s,}]+)")
        regex.findAll(summary).forEach { match ->
            val id = match.groupValues.getOrNull(1)?.trim() ?: return@forEach
            if (id.isNotBlank() && id != "miscellaneous") {
                channelIds.add(id)
            }
        }
        return channelIds.distinct()
    }

    private fun runShellCommandWithFallback(commands: List<List<String>>): Boolean {
        val attemptSummaries = mutableListOf<String>()
        commands.forEachIndexed { index, command ->
            val result = runCommand(command)
            attemptSummaries += "第${index + 1}次：${result.summary}"
            if (result.success) {
                lastOperationSummary = attemptSummaries.joinToString(" | ")
                return true
            }
            if (!result.retryable) {
                lastOperationSummary = attemptSummaries.joinToString(" | ")
                return false
            }
        }
        lastOperationSummary = attemptSummaries.joinToString(" | ")
        return false
    }

    private fun runAppOpsBatch(packageName: String, modes: List<Pair<String, String>>): Boolean {
        val attemptSummaries = mutableListOf<String>()
        var anySuccess = false
        modes.forEach { (opName, opMode) ->
            val commands = listOf(
                listOf("cmd", "appops", "set", packageName, opName, opMode),
                listOf("appops", "set", packageName, opName, opMode)
            )
            var opSucceeded = false
            commands.forEachIndexed { index, command ->
                val result = runCommand(command)
                attemptSummaries += "${translateAppOpName(opName)}第${index + 1}次：${result.summary}"
                if (result.success) {
                    opSucceeded = true
                    anySuccess = true
                    return@forEachIndexed
                }
                if (!result.retryable) {
                    return@forEachIndexed
                }
            }
            if (!opSucceeded) {
                attemptSummaries += "${translateAppOpName(opName)}未成功"
            }
        }
        lastOperationSummary = attemptSummaries.joinToString(" | ")
        return anySuccess
    }

    private fun runCommand(command: List<String>): CommandResult {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching CommandResult(false, true, "执行超时，命令=${command.joinToString(" ")}")
            }
            val output = process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(1024)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }.trim()
            val exitCode = process.waitFor()
            val commandText = command.joinToString(" ")
            val summary = buildSummary(commandText, exitCode, output)
            when {
                exitCode == 0 -> CommandResult(true, false, "执行成功，$summary")
                isUnsupportedCommand(output) -> CommandResult(false, true, "当前命令不受系统支持，$summary")
                isPermissionFailure(output) -> CommandResult(false, false, "权限不足，$summary")
                output.contains("SecurityException", ignoreCase = true) -> CommandResult(false, false, "系统安全限制，$summary")
                output.contains("Unknown package", ignoreCase = true) -> CommandResult(false, false, "未找到目标应用，$summary")
                else -> CommandResult(false, false, "执行失败，$summary")
            }
        }.getOrElse {
            CommandResult(false, true, "执行异常，命令=${command.joinToString(" ")}，错误=${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun escapeShellSingleQuoted(value: String): String {
        return value.replace("'", "'\\''")
    }

    private fun buildSummary(command: String, exitCode: Int, output: String): String {
        return buildString {
            append("命令=")
            append(command)
            append("，退出码=")
            append(exitCode)
            if (output.isNotBlank()) {
                append("，输出=")
                append(output.replace('\n', ' ').take(240))
            }
        }
    }

    private fun translateAppOpName(opName: String): String {
        return when (opName) {
            "POST_NOTIFICATION" -> "通知权限"
            "RUN_ANY_IN_BACKGROUND" -> "后台运行权限"
            "RUN_IN_BACKGROUND" -> "后台活动权限"
            "WAKE_LOCK" -> "唤醒锁权限"
            "INTERNET" -> "联网权限"
            else -> opName
        }
    }

    private fun packageQueryFlags(): Int {
        return PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
    }

    private fun isUnsupportedCommand(output: String): Boolean {
        return output.contains("Unknown command", ignoreCase = true) ||
            output.contains("Unknown option", ignoreCase = true) ||
            output.contains("Unsupported", ignoreCase = true) ||
            output.contains("not found", ignoreCase = true)
    }

    private fun isPermissionFailure(output: String): Boolean {
        return output.contains("Permission", ignoreCase = true) ||
            output.contains("not allowed", ignoreCase = true) ||
            output.contains("Operation not permitted", ignoreCase = true)
    }

    private data class CommandResult(
        val success: Boolean,
        val retryable: Boolean,
        val summary: String
    )
}
