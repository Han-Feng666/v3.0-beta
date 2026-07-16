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
        val zygiskEnabled: Boolean,
        val zygiskNextDetected: Boolean,
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

        val zygiskEnabled = rootSolution == "Magisk" && checkZygiskEnabled()
        val zygiskNextDetected = checkZygiskNextInstalled()

        val recommendations = mutableListOf<String>()
        if (magiskAvailable) {
            recommendations.add("启用 Magisk DenyList 以隐藏 Root 特征")
            if (!zygiskEnabled && rootSolution == "Magisk") {
                recommendations.add("⚠️ Zygisk 未启用，请到 Magisk 设置中打开 Zygisk")
            }
        } else {
            recommendations.add("建议在 Magisk/KernelSU 环境下手动配置 DenyList")
        }

        val mountablePaths = mutableListOf<String>()
        val readonlyPaths = mutableListOf<String>()
        var systemMountable = false

        if (rootDetected == "已检测到 Root") {
            val existingPaths = knownDetectionPaths.filter { p -> pathExists(p) }
            // 只读检测，不真的 remount
            val mountCheck = runRootShell("mount | grep ' / ' | head -1")
            val isErfs = mountCheck.output.contains("erofs")
            val isRo = mountCheck.output.contains("ro,") || mountCheck.output.contains(" ro ") || isErfs
            systemMountable = !isRo
            if (isRo) {
                readonlyPaths.addAll(existingPaths)
            } else {
                mountablePaths.addAll(existingPaths)
            }
            if (isErfs) {
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
            zygiskEnabled = zygiskEnabled,
            zygiskNextDetected = zygiskNextDetected,
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

        // 从设备实际读取 denylist 包名列表
        val denyListPackages = listActuallyHidden()

        return HideStatus(
            systemWideHidden = systemHidden,
            hiddenPathCount = actualMounts,
            magiskDenyListCount = denyListPackages.size,
            processHiddenCount = hiddenPids
        )
    }

    /** 从设备实际读取 DenyList / KSU magiskhide 已隐藏的包名列表 */
    fun listActuallyHidden(): Set<String> {
        if (!suSession.isSessionOpen() && !suSession.open(15)) return emptySet()
        val combined = mutableSetOf<String>()

        // Magisk DenyList
        val magiskList = runRootShell("magisk --denylist ls 2>/dev/null | grep -v '^$' | cut -d' ' -f2", 10)
        combined.addAll(magiskList.output.lines().map { it.trim() }.filter { it.isNotBlank() })

        // KernelSU magiskhide
        val ksuList = runRootShell("ksud magiskhide ls 2>/dev/null | grep -v '^$' | cut -d' ' -f2", 10)
        combined.addAll(ksuList.output.lines().map { it.trim() }.filter { it.isNotBlank() })

        magiskDenyListPackages.addAll(combined)
        return combined
    }

    /** 一键解除所有隐藏（DenyList + KSU + 系统路径 + 进程 mount） */
    fun unhideAll(): Boolean {
        if (!suSession.isSessionOpen() && !suSession.open(30)) return false
        var allOk = true

        // Magisk DenyList 清空
        val pkgs = listActuallyHidden()
        for (pkg in pkgs) {
            val r = runRootShell("magisk --denylist rm '$pkg' 2>/dev/null && echo OK || echo FAIL", 5)
            if (!r.output.contains("OK")) {
                // 尝试多种 KSU 路径
                val ksuR = runRootShell(
                    "ksud magiskhide remove '$pkg' 2>/dev/null && echo OK || " +
                    "/data/adb/ksud magiskhide remove '$pkg' 2>/dev/null && echo OK || " +
                    "/data/adb/ksu/bin/ksud magiskhide remove '$pkg' 2>/dev/null && echo OK || echo FAIL",
                    5
                )
                if (!ksuR.output.contains("OK")) allOk = false
            }
            magiskDenyListPackages.remove(pkg)
        }

        // 系统路径 umount
        val sw = trySystemWideMount()
        if (sw != null) {
            runRootShell("for p in $SYS_HIDE_DIR/*; do umount \"\$p\" 2>/dev/null; done; rm -rf '$SYS_HIDE_DIR' 2>/dev/null", 10)
            systemWideApplied = false
            systemWidePathCount = 0
        }

        // 进程 mount 解除
        KernelProcessHider().unhideAll()
        return allOk
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

        // 自动启用 Zygisk（仅在 Magisk 上；KSU/APatch 自带实现）
        if (detectedRoot == "Magisk") {
            val zygOk = ensureZygiskEnabled()
            Log.d(TAG, "Zygisk ensure enabled: $zygOk (Zygisk Next 等隐藏模块依赖此)")
        }

        val existingPaths = knownDetectionPaths.filter { pathExists(it) }
        Log.d(TAG, "Found ${existingPaths.size} root traces: ${existingPaths.joinToString(", ")}")

        val magiskResults = mutableListOf<HideResult>()
        val remaining = mutableListOf<String>()

        for (pkg in packages) {
            // 0) 先尝试 prop 伪装 + mount bind（对所有目标 App 都做，最广泛的覆盖）
            // 1) KernelSU/APatch 优先走官方 magiskhide 兼容接口
            val ksuR = tryKsuHide(pkg)
            if (ksuR != null) {
                magiskResults.add(ksuR)
                if (ksuR.success) magiskDenyListPackages.add(pkg)
                continue
            }
            // 2) Magisk 设备走 DenyList（含自动启用 DenyList）
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
        // 尝试多种 KSU 路径
        val ks = runRootShell(
            "ksud magiskhide rm '$escapedPkg' 2>/dev/null && echo KS_OK || " +
            "/data/adb/ksud magiskhide rm '$escapedPkg' 2>/dev/null && echo KS_OK || " +
            "/data/adb/ksu/bin/ksud magiskhide rm '$escapedPkg' 2>/dev/null && echo KS_OK || echo KS_FAIL"
        )
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

    /** 检测 Magisk Zygisk 是否启用。 */
    fun checkZygiskEnabled(): Boolean {
        val r = runRootShell(
            "magisk --sqlite \"SELECT value FROM settings WHERE key='zygisk'\" 2>/dev/null | grep -qx 1 && echo ON || " +
                "(getprop persist.sys.zygisk.enabled 2>/dev/null | grep -qx 1 && echo ON) || " +
                "(test -d /data/adb/modules/zygisksu && echo ON) || echo OFF",
            5
        )
        return r.output.trim() == "ON"
    }

    /** 检测 Zygisk Next 模块是否安装（内置 Shamiko 同等能力，无需额外装 Shamiko）。 */
    fun checkZygiskNextInstalled(): Boolean {
        val r = runRootShell(
            "for base in /data/adb/modules /data/adb/modules_update; do " +
                "for f in \"\$base\"/[zZ]ygisk*/module.prop; do " +
                "test -f \"\$f\" || continue; " +
                "if grep -Eqi '^id=.*(zygisk[-_ ]?next|zygisksu)' \"\$f\" 2>/dev/null; then echo FOUND; exit 0; fi; " +
                "if grep -Eqi '^name=.*(Zygisk[ _]?Next|ZygiskSU|Zygisk-Next)' \"\$f\" 2>/dev/null; then echo FOUND; exit 0; fi; " +
                "done; " +
                "done 2>/dev/null; echo NOTFOUND",
            6
        )
        return r.output.trim() == "FOUND"
    }

    /** 尝试自动启用 Zygisk（Magisk 设置写入 + 服务重启）。只在 Magisk 自身上有效，KSU/APatch 有自家 Zygisk 实现。 */
    fun ensureZygiskEnabled(): Boolean {
        if (detectRootSolution() != "Magisk") return checkZygiskEnabled()
        if (checkZygiskEnabled()) return true
        val r = runRootShell(
            "magisk --sqlite \"INSERT OR REPLACE INTO settings (key, value) VALUES ('zygisk', 1)\" 2>/dev/null && echo ZYGISK_ON || echo FAIL",
            5
        )
        if (r.output.trim() != "ZYGISK_ON") return false
        // 重启 zygote 让设置生效（会闪一下屏，但避免用户手动 reboot）
        runRootShell("setprop ctl.restart zygote 2>/dev/null; echo KICKED", 3)
        Thread.sleep(800)
        return checkZygiskEnabled()
    }

    @Volatile private var cachedKsudAvailable: Boolean? = null

    private fun tryKsuHide(packageName: String): HideResult? {
        // KernelSU 的 su 一般提供 ksud CLI 与 MagiskDenyList 兼容接口，
        // 探测一次 ksud 是否存在，后续复用缓存
        val ksuOk = cachedKsudAvailable ?: run {
            // 尝试多个可能的 ksud 路径
            val detectResult = runRootShell(
                "if command -v ksud >/dev/null 2>&1; then echo KSUD_OK; " +
                "elif [ -x /data/adb/ksud ]; then echo KSUD_OK; " +
                "elif [ -x /data/adb/ksu/bin/ksud ]; then echo KSUD_OK; " +
                "else echo KSUD_NONE; fi",
                5
            )
            val ok = detectResult.output.contains("KSUD_OK")
            cachedKsudAvailable = ok
            ok
        }
        if (!ksuOk) return null

        val escapedPkg = packageName.replace("'", "'\\''")

        // 尝试多种 KernelSU 隐藏命令，兼容不同版本
        val commands = listOf(
            // KSU 1.0+ 的 magiskhide 子命令
            "ksud magiskhide enable 2>/dev/null; ksud magiskhide add '$escapedPkg' 2>/dev/null",
            // 完整路径尝试
            "/data/adb/ksud magiskhide enable 2>/dev/null; /data/adb/ksud magiskhide add '$escapedPkg' 2>/dev/null",
            "/data/adb/ksu/bin/ksud magiskhide enable 2>/dev/null; /data/adb/ksu/bin/ksud magiskhide add '$escapedPkg' 2>/dev/null",
            // KSU 旧版本的 hide 子命令
            "ksud hide add '$escapedPkg' 2>/dev/null",
            // KSU 通过 su 执行
            "su -c 'ksud magiskhide add $escapedPkg' 2>/dev/null"
        )

        for (cmd in commands) {
            val result = runRootShell(cmd, 5)
            if (result.exitCode == 0 || result.output.contains("KSU_ADD_OK")) {
                return HideResult(true, packageName, "ksu_magiskhide", "已加入 KernelSU MagiskHide")
            }
            // 检查是否成功添加到列表
            if (result.exitCode == 0) {
                // 验证是否真的添加成功
                val verifyResult = runRootShell(
                    "ksud magiskhide ls 2>/dev/null | grep -q '$escapedPkg' && echo FOUND || " +
                    "/data/adb/ksud magiskhide ls 2>/dev/null | grep -q '$escapedPkg' && echo FOUND || echo NOT_FOUND",
                    5
                )
                if (verifyResult.output.contains("FOUND")) {
                    return HideResult(true, packageName, "ksu_magiskhide", "已加入 KernelSU MagiskHide")
                }
            }
        }

        return HideResult(false, packageName, "ksu_magiskhide",
            "KSU 隐藏添加失败，请确认 KernelSU 版本支持 magiskhide 功能")
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
