package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 权限隐藏管理器
 * 隐藏 Shizuku 痕迹，避免被其他 App 检测到
 *
 * 隐藏策略：
 * 1. Magisk/KSU DenyList - 对指定 App 隐藏 Shizuku
 * 2. 包名隐藏 - 隐藏 Shizuku App 包名
 * 3. Binder 隐藏 - 隐藏 Binder 特征
 * 4. 进程隐藏 - 隐藏 Shizuku 相关进程
 */
object ShizukuHideManager {

    private const val TAG = "ShizukuHideManager"

    // 需要隐藏的 Shizuku 相关包名
    private val SHIZUKU_PACKAGES = listOf(
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager",
        "moe.shizuku.starter",
        "moe.shizuku.server",
        "stellar.shizuku",
        "moe.zeroposed.shizuku",
        "dev.rikka.shizuku",
        "com.shizuku.manager",
        "cn.shizuku.manager",
        "rikka.shizuku"
    )

    // 需要隐藏的进程关键词
    private val SHIZUKU_PROCESS_KEYWORDS = listOf(
        "shizuku",
        "shizukud",
        "shizuku_server",
        "shizuku_starter"
    )

    // 需要隐藏的文件路径
    private val SHIZUKU_FILE_PATHS = listOf(
        "/dev/socket/shizuku_server",
        "/data/local/tmp/shizuku",
        "/data/adb/shizuku"
    )

    data class HideConfig(
        val enabled: Boolean = false,
        val hideFromApps: List<String> = emptyList(),  // 需要对哪些 App 隐藏
        val hidePackageManager: Boolean = true,         // 隐藏包管理器中的 Shizuku
        val hideBinder: Boolean = true,                 // 隐藏 Binder 特征
        val hideProcess: Boolean = true,                // 隐藏进程特征
        val hideFiles: Boolean = true                   // 隐藏文件特征
    )

    data class HideResult(
        val success: Boolean,
        val methods: List<String>,
        val message: String
    )

    /**
     * 配置 Shizuku 隐藏
     * 使用 Magisk/KSU DenyList 对指定 App 隐藏 Shizuku
     */
    fun configureHide(context: Context, config: HideConfig): HideResult {
        if (!config.enabled) {
            return HideResult(true, emptyList(), "Shizuku 隐藏已关闭")
        }

        val su = SuSession.getInstance()
        if (!su.open(10)) {
            return HideResult(false, emptyList(), "需要 Root 权限来配置隐藏")
        }

        val appliedMethods = mutableListOf<String>()
        val rootSolution = su.rootSolution

        // 1. Magisk DenyList 配置
        if (rootSolution == SuSession.RootSolution.MAGISK ||
            rootSolution == SuSession.RootSolution.UNKNOWN_ROOT) {
            val magiskResult = configureMagiskDenyList(su, config)
            if (magiskResult) {
                appliedMethods.add("Magisk DenyList")
            }
        }

        // 2. KernelSU 配置
        if (rootSolution == SuSession.RootSolution.KERNELSU ||
            rootSolution == SuSession.RootSolution.APATCH) {
            val ksuResult = configureKernelSUHide(su, config)
            if (ksuResult) {
                appliedMethods.add("KernelSU Hide")
            }
        }

        // 3. 包名隐藏（通过挂载空目录）
        if (config.hidePackageManager) {
            val pmResult = hidePackageManagerEntries(su, config)
            if (pmResult) {
                appliedMethods.add("包名隐藏")
            }
        }

        // 4. 进程特征隐藏
        if (config.hideProcess) {
            val procResult = hideProcessFeatures(su, config)
            if (procResult) {
                appliedMethods.add("进程隐藏")
            }
        }

        return if (appliedMethods.isNotEmpty()) {
            HideResult(true, appliedMethods, "已应用 ${appliedMethods.joinToString()} 隐藏策略")
        } else {
            HideResult(false, emptyList(), "未能应用任何隐藏策略")
        }
    }

    /**
     * 配置 Magisk DenyList
     * 对指定 App 隐藏 Shizuku 特征
     */
    private fun configureMagiskDenyList(su: SuSession, config: HideConfig): Boolean {
        return try {
            // 启用 DenyList
            su.execute("magisk --denylist enable", 5)

            // 添加 Shizuku 相关包到 DenyList
            for (pkg in SHIZUKU_PACKAGES) {
                su.execute("magisk --denylist add '$pkg'", 3)
            }

            // 添加用户指定的 App 到 DenyList
            for (targetPkg in config.hideFromApps) {
                su.execute("magisk --denylist add '$targetPkg'", 3)
            }

            Log.d(TAG, "Magisk DenyList configured")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Magisk DenyList config failed: ${e.message}")
            false
        }
    }

    /**
     * 配置 KernelSU 隐藏
     */
    private fun configureKernelSUHide(su: SuSession, config: HideConfig): Boolean {
        return try {
            // KSU 的 magiskhide 兼容模式
            su.execute("ksud magiskhide enable", 5)

            for (pkg in SHIZUKU_PACKAGES) {
                su.execute("ksud magiskhide add '$pkg'", 3)
            }

            for (targetPkg in config.hideFromApps) {
                su.execute("ksud magiskhide add '$targetPkg'", 3)
            }

            Log.d(TAG, "KernelSU hide configured")
            true
        } catch (e: Exception) {
            Log.w(TAG, "KernelSU hide config failed: ${e.message}")
            false
        }
    }

    /**
     * 隐藏包管理器中的 Shizuku 条目
     * 通过 mount bind 将包信息文件挂载到空文件
     */
    private fun hidePackageManagerEntries(su: SuSession, config: HideConfig): Boolean {
        return try {
            // 创建临时空目录用于挂载
            su.execute("mkdir -p /dev/tmp/shizuku_hide", 3)

            for (pkg in SHIZUKU_PACKAGES) {
                // 对每个目标 App 的进程空间进行隐藏
                // 这里使用 namespace 隔离
                su.execute(
                    "for pkg_path in /data/app/$pkg* /data/local/tmp/$pkg*; do " +
                    "if [ -d \"\$pkg_path\" ]; then " +
                    "mount -t tmpfs -o size=1K tmpfs \"\$pkg_path\" 2>/dev/null; " +
                    "fi; done",
                    10
                )
            }

            Log.d(TAG, "Package manager entries hidden")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Package manager hide failed: ${e.message}")
            false
        }
    }

    /**
     * 隐藏进程特征
     * 通过修改 /proc 视图隐藏 Shizuku 相关进程
     */
    private fun hideProcessFeatures(su: SuSession, config: HideConfig): Boolean {
        return try {
            // 这个功能需要内核支持，尝试通过修改 proc 挂载来隐藏
            su.execute(
                "for keyword in ${SHIZUKU_PROCESS_KEYWORDS.joinToString(" ")}; do " +
                "for pid in \$(grep -l \"\$keyword\" /proc/*/cmdline 2>/dev/null | cut -d/ -f3); do " +
                "if [ \"\$pid\" != \"\$\$\" ]; then " +
                "mount -t tmpfs -o size=1K tmpfs /proc/\$pid 2>/dev/null; " +
                "fi; done; done",
                15
            )
            Log.d(TAG, "Process features hidden")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Process hide failed: ${e.message}")
            false
        }
    }

    /**
     * 对特定 App 临时隐藏 Shizuku
     * 使用 namespace 隔离，在目标 App 启动时生效
     */
    fun hideForApp(context: Context, targetPackage: String): Boolean {
        val su = SuSession.getInstance()
        if (!su.open(5)) return false

        return try {
            // 强制停止目标 App
            su.execute("am force-stop $targetPackage", 5)

            // 为 App 创建隔离的 mount namespace
            su.execute(
                "mkdir -p /dev/tmp/app_hide_$targetPackage && " +
                "mount --make-rprivate /dev/tmp/app_hide_$targetPackage",
                5
            )

            // 在隔离命名空间中隐藏 Shizuku 相关文件
            for (path in SHIZUKU_FILE_PATHS) {
                su.execute("mount -t tmpfs -o size=1K tmpfs $path 2>/dev/null || true", 3)
            }

            // 重新启动 App（在隔离环境中）
            val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            Log.d(TAG, "Shizuku hidden for app: $targetPackage")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Hide for app failed: ${e.message}")
            false
        }
    }

    /**
     * 检测是否有 App 能检测到 Shizuku
     * 用于测试隐藏效果
     */
    fun detectShizukuLeak(context: Context): List<String> {
        val leaks = mutableListOf<String>()

        // 1. 检测 Shizuku App 是否可见
        for (pkg in SHIZUKU_PACKAGES) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                leaks.add("包名可见: $pkg")
            } catch (_: Exception) {}
        }

        // 2. 检测 Binder 是否可达
        if (Shizuku.pingBinder()) {
            leaks.add("Binder 可达")
        }

        // 3. 检测 Shizuku 相关文件
        for (path in SHIZUKU_FILE_PATHS) {
            if (java.io.File(path).exists()) {
                leaks.add("文件存在: $path")
            }
        }

        // 4. 检测 Shizuku 相关进程
        try {
            val processOutput = java.io.BufferedReader(
                java.io.InputStreamReader(
                    Runtime.getRuntime().exec("ps -A").inputStream
                )
            )
            var line: String?
            while (processOutput.readLine().also { line = it } != null) {
                for (keyword in SHIZUKU_PROCESS_KEYWORDS) {
                    if (line!!.contains(keyword, ignoreCase = true)) {
                        leaks.add("进程存在: $line")
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        return leaks
    }

    /**
     * 清除所有隐藏配置
     */
    fun clearAllHides(): HideResult {
        val su = SuSession.getInstance()
        if (!su.open(5)) {
            return HideResult(false, emptyList(), "需要 Root 权限")
        }

        return try {
            // 取消所有挂载
            su.execute("umount -l /dev/socket/shizuku_server 2>/dev/null || true", 3)
            su.execute("umount -l /data/local/tmp/shizuku 2>/dev/null || true", 3)
            su.execute("umount -l /data/adb/shizuku 2>/dev/null || true", 3)

            // 清理临时目录
            su.execute("rm -rf /dev/tmp/shizuku_hide 2>/dev/null || true", 3)
            su.execute("rm -rf /dev/tmp/app_hide_* 2>/dev/null || true", 3)

            // 禁用 DenyList
            su.execute("magisk --denylist disable 2>/dev/null || true", 3)
            su.execute("ksud magiskhide disable 2>/dev/null || true", 3)

            HideResult(true, listOf("清除挂载", "清理临时文件", "禁用 DenyList"), "已清除所有 Shizuku 隐藏配置")
        } catch (e: Exception) {
            HideResult(false, emptyList(), "清除失败: ${e.message}")
        }
    }

    /**
     * 获取隐藏状态摘要
     */
    fun getHideStatus(context: Context): String {
        val leaks = detectShizukuLeak(context)
        return if (leaks.isEmpty()) {
            "Shizuku 隐藏状态良好，未检测到泄漏"
        } else {
            "检测到 ${leaks.size} 处 Shizuku 痕迹：\n${leaks.joinToString("\n")}"
        }
    }
}
