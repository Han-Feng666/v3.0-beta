package com.HanFeng.adblocker.shizuku

import android.util.Log

class RootHideManager {

    companion object {
        private const val TAG = "RootHideManager"
        private const val SYS_HIDE_DIR = "/data/local/tmp/.hf_sys_hide"
    }

    data class HideResult(
        val success: Boolean,
        val packageName: String,
        val method: String,
        val detail: String
    )

    data class PreCheckResult(
        val rootDetected: String,
        val rootSolution: String,
        val mountablePaths: List<String>,
        val readonlyPaths: List<String>,
        val magiskDenyListAvailable: Boolean,
        val systemMountable: Boolean,
        val recommendations: List<String>
    )

    data class HideStatus(
        val systemWideHidden: Boolean,
        val hiddenPathCount: Int,
        val magiskDenyListCount: Int,
        val processHiddenCount: Int
    )

    private val knownDetectionPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/data/adb/magisk.db",
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

    // 这些路径会被 root 管理器读取以加载模块/管理模块配置，
    // 对它们整目录 bind mount 会让模块显示为 0 / 作用域损坏，必须排除。
    private val moduleConfigPaths = setOf(
        "/data/adb/magisk",
        "/data/adb/ksu",
        "/data/adb/ap",
        "/data/adb/modules",
        "/data/adb/modules_update",
    )

    private val suSession get() = SuSession.getInstance()
    private var systemWideApplied = false
    private var systemWidePathCount = 0
    private var magiskDenyListPackages = mutableSetOf<String>()

    fun preCheck(): PreCheckResult {
        val rootSolution = detectRootSolution()
        val rootDetected = if (rootSolution != "None") "已检测到 Root" else "未检测到 Root"
        val magiskAvailable = rootSolution == "Magisk" || rootSolution == "KernelSU"

        val recommendations = mutableListOf<String>()
        if (magiskAvailable) recommendations.add("启用 Magisk DenyList 以隐藏 Root 特征")
        else recommendations.add("建议在 Magisk/KernelSU 环境下手动配置 DenyList")

        val mountablePaths = mutableListOf<String>()
        val readonlyPaths = mutableListOf<String>()
        var systemMountable = false

        if (rootDetected == "已检测到 Root") {
            val existingPaths = knownDetectionPaths.filter { p -> pathExists(p) }
            for (path in existingPaths) {
                val testResult = runRootShell("mount -o remount,rw / 2>/dev/null && echo RW || echo RO; mount -o remount,ro / 2>/dev/null || true")
                if (testResult.output.contains("RW")) {
                    mountablePaths.add(path)
                    systemMountable = true
                } else {
                    readonlyPaths.add(path)
                }
            }
            val erofsCheck = runRootShell("mount | grep ' / ' | grep -q erofs && echo EROFS || echo NOT_EROFS")
            if (erofsCheck.output.contains("EROFS")) {
                recommendations.add("系统分区为 EROFS 只读文件系统，无法直接挂载隐藏，请使用进程级隐藏")
                systemMountable = false
            }
        }

        recommendations.add("对于运行中的应用，使用进程级 mount_bind 隐藏路径 (procfs)")
        if (!magiskAvailable) recommendations.add("Root 方案未检测到，部分隐藏方法可能不可用")

        return PreCheckResult(
            rootDetected = rootDetected,
            rootSolution = rootSolution,
            mountablePaths = mountablePaths,
            readonlyPaths = readonlyPaths,
            magiskDenyListAvailable = magiskAvailable,
            systemMountable = systemMountable,
            recommendations = recommendations
        )
    }

    fun getHiddenStatus(): HideStatus {
        val systemHidden = systemWideApplied
        val pathCount = if (systemHidden) systemWidePathCount else 0

        val mountCheck = runRootShell("mount | grep '$SYS_HIDE_DIR' | wc -l")
        val actualMounts = mountCheck.output.trim().toIntOrNull() ?: 0

        val kernelHider = KernelProcessHider()
        val hiddenPids = kernelHider.getHiddenPids().size

        return HideStatus(
            systemWideHidden = systemHidden,
            hiddenPathCount = actualMounts,
            magiskDenyListCount = magiskDenyListPackages.size,
            processHiddenCount = hiddenPids
        )
    }

    fun hideFromPackage(packageName: String): HideResult {
        if (!suSession.isSessionOpen() && !suSession.open(30)) {
            return HideResult(false, packageName, "error", "Root 权限不可用")
        }

        var result = tryKsuHide(packageName)
        if (result != null) {
            if (result.success) magiskDenyListPackages.add(packageName)
            return result
        }

        result = tryMagiskDenyList(packageName)
        if (result != null) {
            if (result.success) magiskDenyListPackages.add(packageName)
            return result
        }

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

        val existingPaths = knownDetectionPaths.filter { pathExists(it) }
        Log.d(TAG, "Found ${existingPaths.size} root traces: ${existingPaths.joinToString(", ")}")

        val magiskResults = mutableListOf<HideResult>()
        val remaining = mutableListOf<String>()

        for (pkg in packages) {
            // 1) KernelSU/APatch 优先走官方 magiskhide 兼容接口
            val ksuR = tryKsuHide(pkg)
            if (ksuR != null) {
                magiskResults.add(ksuR)
                if (ksuR.success) magiskDenyListPackages.add(pkg)
                continue
            }
            // 2) Magisk 设备走 DenyList
            val r = tryMagiskDenyList(pkg)
            if (r != null) {
                magiskResults.add(r)
                if (r.success) magiskDenyListPackages.add(pkg)
            } else {
                remaining.add(pkg)
            }
        }
        Log.d(TAG, "Ksu/Magisk DenyList: ${magiskResults.size} packages, remaining: ${remaining.size}")

        if (remaining.isNotEmpty() && !systemWideApplied) {
            val swResult = trySystemWideMount()
            if (swResult != null && swResult.success) {
                systemWideApplied = true
                systemWidePathCount = swResult.detail.toIntOrNull() ?: 0
                Log.d(TAG, "System-wide mount: hidden $systemWidePathCount paths")
            } else {
                Log.d(TAG, "System-wide mount: failed or not available")
            }
        }

        val finalResults = mutableListOf<HideResult>()
        finalResults.addAll(magiskResults)

        for (pkg in remaining) {
            if (systemWideApplied) {
                finalResults.add(HideResult(true, pkg, "system_wide_mount", "系统级隐藏: $systemWidePathCount 条路径"))
            } else {
                val r = tryMountForRunningProcesses(pkg)
                finalResults.add(r ?: HideResult(false, pkg, "none", "所有隐藏方法均不可用，请确认 Magisk/KernelSU DenyList 已启用"))
            }
        }

        return finalResults
    }

    fun restoreAll(): Int {
        var unmountedCount = 0

        val existingPaths = knownDetectionPaths.filter { path ->
            runRootShell("mount | grep -q 'on $path ' && echo MOUNTED || echo NOT").output.trim() == "MOUNTED"
        }

        for (path in existingPaths) {
            if (runRootShell("umount '$path' 2>/dev/null || nsenter -t 1 -m umount '$path' 2>/dev/null && echo OK || echo FAIL")
                    .output.trim() == "OK") {
                unmountedCount++
            }
        }

        runRootShell("rm -rf '$SYS_HIDE_DIR'")

        systemWideApplied = false
        systemWidePathCount = 0

        val kernelHider = KernelProcessHider()
        unmountedCount += kernelHider.unhideAll()

        Log.d(TAG, "Restored $unmountedCount paths/processes")
        return unmountedCount
    }

    fun restoreForPackage(packageName: String): Boolean {
        magiskDenyListPackages.remove(packageName)
        val escapedPkg = packageName.replace("'", "'\\''")
        // 同时尝试 Magisk 与 KernelSU 的实例
        val mg = runRootShell("magisk --denylist rm '$escapedPkg' 2>/dev/null && echo MG_OK || echo MG_FAIL")
        val ks = runRootShell("ksud magiskhide rm '$escapedPkg' 2>/dev/null && echo KS_OK || echo KS_FAIL")
        return mg.output.contains("MG_OK") || ks.output.contains("KS_OK")
    }

    fun checkRootAvailable(): Boolean {
        if (suSession.isSessionOpen() && suSession.checkPermission()) return true
        return suSession.open(timeoutSeconds = 30)
    }

    private fun detectRootSolution(): String {
        val check = runRootShell(
            "if [ -d /data/adb/magisk ]; then echo Magisk; " +
            "elif [ -d /data/adb/ksu ]; then echo KernelSU; " +
            "elif [ -d /data/adb/ap ]; then echo APatch; " +
            "elif [ -f /system/bin/su ] || [ -f /system/xbin/su ]; then echo UnknownRoot; " +
            "else echo None; fi"
        )
        return check.output.trim()
    }

    private fun tryKsuHide(packageName: String): HideResult? {
        // KernelSU 的 su 一般提供 ksud CLI 与 MagiskDenyList 兼容接口，
        // 探测一下 ksud 是否存在、profile/aidl 是否可调用。
        val detectResult = runRootShell("command -v ksud >/dev/null 2>&1 && echo KSUD_OK || echo KSUD_NONE")
        if (!detectResult.output.contains("KSUD_OK")) return null

        val escapedPkg = packageName.replace("'", "'\\''")
        // KSU 1.0+ 的 hide 子命令（与 MagiskDenyList 行为类似）
        val enableResult = runRootShell("ksud magiskhide enable 2>/dev/null ; ksud magiskhide add '$escapedPkg' 2>/dev/null && echo KSU_ADD_OK || echo KSU_ADD_FAIL")
        return when {
            enableResult.output.contains("KSU_ADD_OK") -> HideResult(
                true, packageName, "ksu_magiskhide", "已加入 KernelSU MagiskHide"
            )
            else -> HideResult(false, packageName, "ksu_magiskhide",
                "KSU 隐藏添加失败: ${enableResult.output.take(200)}")
        }
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

        if (addResult.output.contains("not found") || addResult.output.contains("Unknown")) return null

        return HideResult(false, packageName, "magisk_denylist", "添加失败: ${addResult.output.take(200)}")
    }

    private fun trySystemWideMount(): HideResult? {
        val existingPaths = knownDetectionPaths.filter { path -> pathExists(path) }
        if (existingPaths.isEmpty()) {
            return HideResult(false, "", "system_wide_mount", "未检测到任何 Root/模块文件")
        }

        runRootShell("mkdir -p '$SYS_HIDE_DIR' && touch '$SYS_HIDE_DIR/.empty'")

        val mountScript = buildString {
            for (path in existingPaths) {
                append("nsenter -t 1 -m -- mount --bind '$SYS_HIDE_DIR' '$path' 2>/dev/null && echo 'HID $path' || echo 'FAIL $path'\n")
            }
        }
        val mountResult = runRootShell(mountScript, 60)
        val hidden = mountResult.output.lines().count { it.startsWith("HID ") }
        val failed = mountResult.output.lines().filter { it.startsWith("FAIL ") }

        if (failed.isNotEmpty()) {
            Log.d(TAG, "System-wide mount failures: ${failed.joinToString(", ")}")
        }

        if (hidden > 0) {
            systemWideApplied = true
            systemWidePathCount = hidden
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

    private fun hidePathsInNamespace(pid: Int): Int {
        val timestamp = System.currentTimeMillis()
        val emptyDir = "/data/local/tmp/.hf_empty_$timestamp"
        runRootShell("mkdir -p '$emptyDir' && touch '$emptyDir/.empty'")

        val pathsToHide = knownDetectionPaths.filter { pathExists(it) }

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
        val result = runRootShell(
            "for p in /proc/*/cmdline; do " +
            "tr '\\0' '\\n' < \"\$p\" 2>/dev/null | grep -qxF '$packageName' && echo \"\$p\" | sed 's|/proc/||; s|/cmdline||'; " +
            "done 2>/dev/null"
        )
        return result.output.trim().lines().mapNotNull { it.trim().toIntOrNull() }
    }

    private fun pathExists(path: String): Boolean {
        return runRootShell("test -e '$path' 2>/dev/null && echo YES || echo NO").output.trim() == "YES"
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
