package com.hanfeng.adblocker.shizuku

import android.util.Log

class RootHideManager {

    companion object {
        private const val TAG = "RootHideManager"
    }

    data class HideResult(
        val success: Boolean,
        val packageName: String,
        val method: String,
        val detail: String
    )

    private val knownDetectionPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/data/adb/magisk",
        "/data/adb/magisk.db",
        "/data/adb/ksu",
        "/data/adb/ap",
        "/data/adb/modules",
        "/data/adb/lspd",
        "/data/adb/lsp",
        "/data/adb/tricky_store",
        "/data/adb/zygisk",
        "/dev/zygisk",
        "/debug_ramdisk",
        "/sbin/.magisk",
        "/system/etc/init/magisk",
        "/cache/.disable_magisk"
    )

    private val suSession get() = SuSession.getInstance()

    fun hideFromPackage(packageName: String): HideResult {
        if (!suSession.isSessionOpen() && !suSession.open(30)) {
            return HideResult(false, packageName, "error", "Root 权限不可用")
        }

        var result = tryMagiskDenyList(packageName)
        if (result != null) return result

        result = trySystemWideMount()
        if (result != null) {
            return HideResult(
                result.success,
                packageName,
                result.method,
                if (result.success) "已对所有应用隐藏 ${result.detail} 条路径" else result.detail
            )
        }

        result = tryMountForRunningProcesses(packageName)
        if (result != null) return result

        return HideResult(false, packageName, "none", "所有隐藏方法均失败，请尝试手动在 Magisk 中配置 DenyList")
    }

    fun hideFromAllSelectedPackages(packages: Set<String>): List<HideResult> {
        if (!suSession.isSessionOpen() && !suSession.open(30)) {
            return packages.map { HideResult(false, it, "error", "Root 权限不可用") }
        }

        val detectedRoot = detectRootSolution()
        Log.d(TAG, "Detected root solution: $detectedRoot")

        val existingPaths = knownDetectionPaths.filter { path ->
            runRootShell("test -e '$path' 2>/dev/null && echo YES || echo NO")
                .output.trim() == "YES"
        }
        Log.d(TAG, "Found ${existingPaths.size} root traces: ${existingPaths.joinToString(", ")}")

        var appliedSystemWide = false
        var systemWideDetail = ""

        val magiskResults = mutableListOf<HideResult>()
        val remaining = mutableListOf<String>()

        for (pkg in packages) {
            val r = tryMagiskDenyList(pkg)
            if (r != null) {
                magiskResults.add(r)
            } else {
                remaining.add(pkg)
            }
        }
        Log.d(TAG, "Magisk DenyList: ${magiskResults.size} packages, remaining: ${remaining.size}")

        if (remaining.isNotEmpty() && !appliedSystemWide) {
            val swResult = trySystemWideMount()
            if (swResult != null && swResult.success) {
                appliedSystemWide = true
                systemWideDetail = swResult.detail
                Log.d(TAG, "System-wide mount: hidden $systemWideDetail paths")
            } else {
                Log.d(TAG, "System-wide mount: failed or not available")
            }
        }

        val finalResults = mutableListOf<HideResult>()
        finalResults.addAll(magiskResults)

        for (pkg in remaining) {
            if (appliedSystemWide) {
                finalResults.add(HideResult(true, pkg, "system_wide_mount", "系统级隐藏: $systemWideDetail 条路径"))
            } else {
                val r = tryMountForRunningProcesses(pkg)
                finalResults.add(r ?: HideResult(false, pkg, "none", "所有隐藏方法均不可用，请确认 Magisk/KernelSU DenyList 已启用"))
            }
        }

        return finalResults
    }

    private fun detectRootSolution(): String {
        val check = runRootShell("""
            if [ -d /data/adb/magisk ]; then echo "Magisk";
            elif [ -d /data/adb/ksu ]; then echo "KernelSU";
            elif [ -d /data/adb/ap ]; then echo "APatch";
            elif [ -f /system/bin/su ] || [ -f /system/xbin/su ]; then echo "UnknownRoot";
            else echo "None"; fi
        """.trimIndent())
        return check.output.trim()
    }

    private fun tryMagiskDenyList(packageName: String): HideResult? {
        val checkResult = runRootShell("magisk --denylist ls 2>/dev/null")
        if (checkResult.exitCode != 0 && !checkResult.output.contains("denylist")) {
            Log.d(TAG, "Magisk DenyList: not available (no magisk --denylist command)")
            return null
        }

        var isEnabled = false
        val statusResult = runRootShell("magisk --denylist status 2>/dev/null")
        if (statusResult.output.contains("enabled")) {
            isEnabled = true
        } else if (statusResult.output.contains("disabled")) {
            val enableResult = runRootShell("magisk --denylist enable 2>/dev/null")
            if (enableResult.exitCode == 0) {
                isEnabled = true
                Log.d(TAG, "Magisk DenyList: enabled")
            }
        }

        val addResult = runRootShell("magisk --denylist add $packageName 2>/dev/null")
        if (addResult.exitCode == 0) {
            return HideResult(true, packageName, "magisk_denylist",
                if (isEnabled) "已加入 Magisk DenyList (已启用)" else "已加入 Magisk DenyList (需重启或手动启用)")
        }

        if (addResult.output.contains("not found") || addResult.output.contains("Unknown")) {
            return null
        }

        return HideResult(false, packageName, "magisk_denylist", "添加失败: ${addResult.output.take(200)}")
    }

    private fun trySystemWideMount(): HideResult? {
        val existingPaths = knownDetectionPaths.filter { path ->
            runRootShell("test -e '$path' 2>/dev/null && echo YES || echo NO")
                .output.trim() == "YES"
        }
        if (existingPaths.isEmpty()) {
            return HideResult(false, "", "system_wide_mount", "未检测到任何 Root/模块文件")
        }

        val emptyDir = "/data/local/tmp/.hf_sys_hide"
        runRootShell("mkdir -p '$emptyDir' && touch '$emptyDir/.empty'")

        val mountScript = buildString {
            for (path in existingPaths) {
                append("nsenter -t 1 -m -- mount --bind '$emptyDir' '$path' 2>/dev/null && echo 'HID $path' || echo 'FAIL $path'\n")
            }
        }
        val mountResult = runRootShell(mountScript, 60)
        val hidden = mountResult.output.lines().count { it.startsWith("HID ") }
        val failed = mountResult.output.lines().filter { it.startsWith("FAIL ") }

        if (failed.isNotEmpty()) {
            Log.d(TAG, "System-wide mount failures: ${failed.joinToString(", ")}")
        }

        if (hidden > 0) {
            return HideResult(true, "", "system_wide_mount", "$hidden")
        }
        return HideResult(false, "", "system_wide_mount",
            if (failed.isNotEmpty()) "挂载失败 (可能 / 分区为只读 EROFS): ${failed.first().removePrefix("FAIL ")}"
            else "系统挂载失败 (所有路径挂载均失败)")
    }

    private fun tryMountForRunningProcesses(packageName: String): HideResult? {
        val pids = getProcIds(packageName)
        if (pids.isEmpty()) {
            Log.d(TAG, "Process mount: $packageName is not running")
            return HideResult(false, packageName, "process_mount", "应用未运行，仅对已运行的进程生效。请启动应用后重试。")
        }

        var pathsHidden = 0
        val kernelHider = KernelProcessHider()

        for (pid in pids) {
            val peekResult = hidePathsInNamespace(pid)
            if (peekResult > 0) pathsHidden += peekResult
            kernelHider.hideProcessDeep(pid, "/dev/null")
        }

        if (pathsHidden > 0) {
            return HideResult(true, packageName, "process_mount", "已隐藏 ${pids.size} 个进程的 $pathsHidden 条路径")
        }
        return HideResult(false, packageName, "process_mount", "进程挂载失败 (PID: ${pids.joinToString()})")
    }

    fun checkRootAvailable(): Boolean {
        if (suSession.isSessionOpen() && suSession.checkPermission()) return true
        return suSession.open(timeoutSeconds = 30)
    }

    private fun hidePathsInNamespace(pid: Int): Int {
        val timestamp = System.currentTimeMillis()
        val emptyDir = "/data/local/tmp/.hf_empty_$timestamp"
        runRootShell("mkdir -p '$emptyDir' && touch '$emptyDir/.empty'")

        val pathsToHide = knownDetectionPaths.filter { path ->
            runRootShell("test -e '$path' 2>/dev/null && echo YES || echo NO")
                .output.trim() == "YES"
        }

        val mountScript = buildString {
            append("mkdir -p '$emptyDir'\n")
            for (path in pathsToHide) {
                append("nsenter -t $pid -m -- mount --bind '$emptyDir' '$path' 2>/dev/null && echo 'HID $path' || true\n")
            }
        }

        val result = runRootShell(mountScript)
        val hidden = result.output.lines().count { it.startsWith("HID ") }
        runRootShell("rm -rf '$emptyDir'")
        return hidden
    }

    private fun getProcIds(packageName: String): List<Int> {
        val result = runRootShell("""
            for p in /proc/*/cmdline; do
                tr '\0' '\n' < "${'$'}p" 2>/dev/null | grep -qxF '$packageName' && echo "${'$'}p" | sed 's|/proc/||; s|/cmdline||'
            done 2>/dev/null
        """.trimIndent())
        return result.output.trim().lines().mapNotNull { it.trim().toIntOrNull() }
    }

    private fun runRootShell(command: String, timeoutSeconds: Long = 30): ShellResult {
        if (!suSession.isSessionOpen()) {
            suSession.open(timeoutSeconds = timeoutSeconds)
        }
        val result = suSession.execute(command, timeoutSeconds)
        return ShellResult(result.exitCode, result.output)
    }

    private data class ShellResult(val exitCode: Int, val output: String)
}
