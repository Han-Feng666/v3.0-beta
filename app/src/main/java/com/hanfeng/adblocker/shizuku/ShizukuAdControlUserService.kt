package com.HanFeng.shizuku

import android.content.Context
import android.content.pm.PackageManager
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
        return runAppOpsBatch(
            packageName,
            modes = listOf(
                "POST_NOTIFICATION" to "ignore",
                "RUN_ANY_IN_BACKGROUND" to "ignore",
                "WAKE_LOCK" to "ignore"
            )
        )
    }

    override fun allowPackageNotifications(packageName: String): Boolean {
        return runAppOpsBatch(
            packageName,
            modes = listOf(
                "POST_NOTIFICATION" to "allow",
                "RUN_ANY_IN_BACKGROUND" to "allow",
                "WAKE_LOCK" to "allow"
            )
        )
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
            context.packageManager.getPackageInfo(packageName, 0)
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
            context.packageManager.getPackageInfo(packageName, 0).applicationInfo?.flags?.let { flags ->
                (flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
            } ?: false
        }.getOrDefault(false)
    }

    override fun getLastOperationSummary(): String = lastOperationSummary

    override fun destroy() {
        System.exit(0)
    }

    private fun runShellCommandWithFallback(commands: List<List<String>>): Boolean {
        val attemptSummaries = mutableListOf<String>()
        commands.forEachIndexed { index, command ->
            val result = runCommand(command)
            attemptSummaries += "try${index + 1}:${result.summary}"
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
                attemptSummaries += "$opName-try${index + 1}:${result.summary}"
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
                attemptSummaries += "$opName-no-success"
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
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            val commandText = command.joinToString(" ")
            val summary = buildSummary(commandText, exitCode, output)
            when {
                exitCode == 0 -> CommandResult(true, false, "success $summary")
                isUnsupportedCommand(output) -> CommandResult(false, true, "unsupported $summary")
                isPermissionFailure(output) -> CommandResult(false, false, "permission $summary")
                output.contains("SecurityException", ignoreCase = true) -> CommandResult(false, false, "security $summary")
                output.contains("Unknown package", ignoreCase = true) -> CommandResult(false, false, "package-missing $summary")
                else -> CommandResult(false, false, "failed $summary")
            }
        }.getOrElse {
            CommandResult(false, true, "exception command=${command.joinToString(" ")} error=${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun escapeShellSingleQuoted(value: String): String {
        return value.replace("'", "'\\''")
    }

    private fun buildSummary(command: String, exitCode: Int, output: String): String {
        return buildString {
            append("command=")
            append(command)
            append(" exit=")
            append(exitCode)
            if (output.isNotBlank()) {
                append(" output=")
                append(output.replace('\n', ' ').take(240))
            }
        }
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
