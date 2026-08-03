package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 内置 Shizuku 激活管理器.
 *
 * 实现策略与官方 RikkaApps/Shizuku 的 StarterActivity.kt + Starter.kt 完全一致, 仅做必要 applicationId 改名:
 *   - Root 激活: su -c '<nativeLibraryDir>/libshizuku.so --apk=<applicationInfo.sourceDir>'
 *   - 无线调试激活: 用官方 AdbClient + AdbKey 走一次 shellCommand(Starter.internalCommand)
 *
 * Shizuku 的 server 由本 app 内置的 starter nativitve binary (fork starter.cpp) 拉起,
 * 它通过 binder 推回本 app 的 ShizukuProvider. 不再依赖外部 Shizuku APK.
 *
 * 不再做任何 pgrep / binder-wait / zip-extract 等附加兜底,
 * 这些曾是过去激活失败的根因 — 与官方一致保持简单链路.
 */
object BuiltInShizukuStarter {

    private const val TAG = "BuiltInShizukuStarter"

    private const val STARTER_BINARY_NAME = "libshizuku.so"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class ActivationResult(
        val success: Boolean,
        val mode: String,
        val message: String
    )

    // ---------------------------------------------------------------
    // Root 激活 - 直接对齐官方 Starter.kt.internalCommand
    // ---------------------------------------------------------------

    fun activateViaRoot(): ActivationResult {
        val context = appContext
            ?: return ActivationResult(false, "root", "BuiltInShizukuStarter 未初始化")

        val su = SuSession.getInstance()
        if (!su.open(10)) {
            return ActivationResult(false, "root", "Root 权限获取失败")
        }

        val apkPath = context.applicationInfo.sourceDir
        if (apkPath.isBlank() || !File(apkPath).isFile) {
            return ActivationResult(false, "root", "无法获取本 app APK 路径: $apkPath")
        }

        val libDir = context.applicationInfo.nativeLibraryDir
        if (libDir.isBlank()) {
            return ActivationResult(false, "root", "无法获取本 app nativeLibraryDir")
        }

        val starterBinary = pickStarterBinaryPath(libDir)
            ?: return ActivationResult(
                false, "root",
                "无法定位内置 starter ($STARTER_BINARY_NAME). 检查 APK 是否完整安装或重新安装本 app"
            )

        // 关键链路: server JVM (app_process) 需要 dlopen librish.so, 其路径由 starter 的
        // -Dshizuku.library.path 决定. starter.cpp 默认推算 <apk_dir>/lib/<ABI>, 这依赖 PM 安装时
        // 把 .so 解压到磁盘 (useLegacyPackaging). 部分安装方式/旧包不会解压, 导致
        // "dlopen failed: library .../librish.so not found" 直接崩溃在 server main.
        //
        // 这里显式保证 librish.so 落在 /data/local/tmp/hanfeng_shizuku_lib/ 并用 --library-path
        // 传给 starter, 彻底不依赖 PM 解压状态:
        //   1. 优先用 nativeLibraryDir 里已解压的 librish.so (新包正常安装即在此)
        //   2. 不存在则从本 APK zip 里抽取到 filesDir (APK 里一定带 lib/<ABI>/librish.so)
        //   3. 连 APK zip 里都没有 → 设备装的是旧包, 直接给明确诊断, 而不是让它崩溃成不明不白的失败
        val librishSource = resolveLibrishSo(context, libDir, apkPath)
            ?: return ActivationResult(
                false, "root",
                "本 APK 中未找到 librish.so (疑似旧版本安装包).\n\n" +
                    "请卸载当前 app, 重新安装最新构建的 APK (含 lib/arm64-v8a/librish.so) 后再激活.\n\n" +
                    "若重装后仍崩溃, 请以 root 执行以下命令确认解压结果:\n" +
                    "  ls /data/app/*/com.HanFeng-*/lib/arm64-v8a/"
            )

        // 落盘到稳定可读路径 (root 进程 + server 进程均可访问 /data/local/tmp)
        val libDirOverride = "/data/local/tmp/hanfeng_shizuku_lib"
        val ensureLibDir = buildString {
            append("mkdir -p '").append(libDirOverride).append("' 2>/dev/null; ")
            append("cp '").append(librishSource).append("' '").append(libDirOverride).append("/librish.so' 2>/dev/null; ")
            append("chmod 755 '").append(libDirOverride).append("/librish.so' 2>/dev/null; ")
        }

        // 与官方 Starter.kt 一致:
        //   internalCommand = "$starterBinary --apk=$apkPath"
        // 用 root shell 执行一次即可. starter.cpp fork 后 child setsid + execvp app_process,
        // parent 立即 printf "info: shizuku_starter exit with 0" 然后退出.
        // server 进程是 child 跑起后异步 binder 推回, 本函数返回后由 ShizukuProvider 接收 binder.
        val cmd = buildString {
            append("chmod 755 '").append(starterBinary).append("' 2>/dev/null; ")
            append(ensureLibDir)
            append("'").append(starterBinary).append("' --apk='").append(apkPath).append("'")
            append(" --library-path=").append(libDirOverride)
        }

        Log.d(TAG, "root activation cmd: $cmd")
        val result = try {
            su.execute(cmd, 15)
        } catch (t: Throwable) {
            Log.e(TAG, "root activation threw", t)
            return ActivationResult(false, "root", "执行失败: ${t.message}")
        }

        val out = result.output.trim()
        Log.d(TAG, "root activation exitCode=${result.exitCode} out=$out")

        // 判据必须以 binder 真的上来为准, 不能只看 starter 输出 "exit with 0".
        // starter.cpp fork 后 parent 立即 exit(0), child 进程 app_process 跑 ShizukuService.main 异步启动,
        // 期间可能挂在 HiddenApiBypass / RishConfig.loadLibrary UnsatisfiedLinkError / BinderSender
        // 找不到 provider 等任意一步, server 进程没注册上 binder, 激活等于没成功.
        // 必须以 Shizuku.pingBinder() / getBinder().isBinderAlive 为准, 否则是假激活.
        if (out.contains("info: shizuku_starter exit with 0", ignoreCase = true) ||
            out.contains("info: shizuku_server pid is", ignoreCase = true)
        ) {
            val binderUp = waitForBinderAlive(8_000L)
            return if (binderUp) {
                ActivationResult(true, "root", buildString {
                    append("已通过 Root 内置 starter 启动 Shizuku 服务\n\n")
                    append("starter 输出:\n").append(out.take(400))
                })
            } else {
                ActivationResult(false, "root", buildString {
                    append("starter 已 fork 子进程, 但 8 秒内 binder 未上来, server main 启动可能已崩溃.\n\n")
                    append("starter 输出:\n").append(out.take(400))
                    append("\n\n崩溃栈 (如果 server main 抛异常):\n")
                    append(dumpServerCrashStack())
                })
            }
        }

        return ActivationResult(false, "root", buildString {
            append("starter 输出:\n").append(out.take(800))
            append("\n\n崩溃栈 (如果 server main 抛异常):\n")
            append(dumpServerCrashStack())
        })
    }

    private fun waitForBinderAlive(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false) ||
                runCatching { Shizuku.getBinder()?.isBinderAlive == true }.getOrDefault(false)
            if (alive) return true
            try { Thread.sleep(300) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    // ---------------------------------------------------------------
    // Root 重启自愈 - 修复"已激活但本应用未授权"
    // ---------------------------------------------------------------
    // 根因: 本 app 作为内置管理端, fork server 会在 attach 时自动授权本应用
    // (isManager → attach 回复 permissionGranted=true)。若首次 attach 发生在 server
    // 尚未注册 binder 之前(或旧 server 已死), SDK 会缓存 permissionGranted=false,
    // 之后即使 server 活了也不会重新 attach, checkSelfPermission() 恒为 DENIED。
    // 修复: root 杀掉旧 server → 重新激活 → server 重新 push binder → SDK 重新 attach
    // → 拿到 manager 自动授权。

    private const val SERVER_PROCESS_PATTERN = "hanfeng_shizuku_server"

    fun restartViaRoot(): ActivationResult {
        val context = appContext
            ?: return ActivationResult(false, "root", "BuiltInShizukuStarter 未初始化")

        val su = SuSession.getInstance()
        if (!su.open(10)) {
            return ActivationResult(false, "root", "Root 权限获取失败")
        }

        // 1. 杀掉旧的 server 进程 (只匹配内置 server 的进程名, 不影响其它进程)
        val killCmd = "for p in \$(pgrep -f $SERVER_PROCESS_PATTERN 2>/dev/null); do kill \$p 2>/dev/null; done; sleep 1"
        val killResult = try {
            su.execute(killCmd, 10)
        } catch (t: Throwable) {
            Log.e(TAG, "restartViaRoot kill failed", t)
        }

        // 2. 清掉 SDK 缓存的旧 binder, 强制下次 push 走全新 attach
        runCatching { rikka.shizuku.Shizuku.onBinderReceived(null, context.packageName) }

        // 3. 重新激活
        return activateViaRoot()
    }

    /** 服务激活后自检本应用授权; 若 root 场景下仍未授权, 用 restartViaRoot 自愈一次。 */
    fun ensureSelfAuthorized(context: Context): ActivationResult {
        val granted = runCatching {
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) {
            return ActivationResult(true, "self", "本应用已获得 Shizuku 授权")
        }
        val restart = restartViaRoot()
        if (!restart.success) return restart
        val deadline = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < deadline) {
            val nowGranted = runCatching {
                rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (nowGranted) {
                return ActivationResult(true, "self", "已自动修复本应用 Shizuku 授权")
            }
            try { Thread.sleep(300) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return ActivationResult(false, "self", "自愈等待被中断")
            }
        }
        return ActivationResult(false, "self", "重启后本应用仍未获得授权, 请查看诊断日志")
    }

    private fun dumpServerCrashStack(): String {
        // starter.cpp 把 server 进程 stderr 重定向到 /data/local/tmp/hanfeng_shizuku_starter.err,
        // server main 抛异常时 ShizukuService.main 会 catch 后写 .crash 文件.
        // 二者都试一次, 任一非空都直接贴给用户看真实根因.
        return try {
            val su = SuSession.getInstance()
            if (!su.open(3)) return "(无法获取 root 读取崩溃栈文件)"
            buildString {
                append("[starter.err 后 40 行]\n")
                append(su.execute("tail -40 /data/local/tmp/hanfeng_shizuku_starter.err 2>/dev/null", 3).output.trim())
                append("\n\n[.crash 后 30 行]\n")
                append(su.execute("tail -30 /data/local/tmp/hanfeng_shizuku_server.crash 2>/dev/null", 3).output.trim())
            }
        } catch (t: Throwable) {
            "(获取崩溃栈失败: ${t.message})"
        }
    }

    // ---------------------------------------------------------------
    // 无线调试配对 + 激活
    // ---------------------------------------------------------------

    fun pairAndActivateViaWirelessDebug(
        pairingCode: String,
        host: String,
        port: Int
    ): ActivationResult {
        val context = appContext
            ?: return ActivationResult(false, "wireless", "BuiltInShizukuStarter 未初始化")

        try {
            return WirelessDebugPairer(context).pairAndActivate(pairingCode, host, port)
        } catch (t: Throwable) {
            Log.e(TAG, "wireless activation failed", t)
            return ActivationResult(false, "wireless", "无线调试激活异常: ${t.message}")
        }
    }

    // ---------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------

    /**
     * 定位 starter binary 实际所在路径.
     * nativeLibraryDir 一般就是 <apk_dir>/lib/<ABI> 并 direkt 包含 libshizuku.so (useLegacyPackaging=true).
     */
    private fun pickStarterBinaryPath(nativeLibraryDir: String): String? {
        if (nativeLibraryDir.isBlank()) return null
        val direct = "$nativeLibraryDir/$STARTER_BINARY_NAME"
        if (File(direct).isFile) return direct
        // 兜底: 列目录找 (个别 ROM 不稳定, nativeLibraryDir 是符号链接)
        val dir = File(nativeLibraryDir)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { f ->
                if (f.name == STARTER_BINARY_NAME && f.isFile && f.canRead()) {
                    return f.absolutePath
                }
            }
        }
        return null
    }

    fun isShizukuBuiltin(): Boolean = true

    fun isShizukuAppInstalled(context: Context): Boolean = false

    /**
     * 解析可用的 librish.so 源文件 (本进程可读), 优先级:
     * 1. nativeLibraryDir/librish.so — 新包正常安装时 PM 已解压到此
     * 2. 从本 APK zip 抽取 lib/<abi>/librish.so 到 filesDir — 安装方式未解压时的兜底
     * 3. 都没有 → 返回 null (旧包, 需要重装)
     */
    private fun resolveLibrishSo(
        context: Context,
        nativeLibraryDir: String,
        apkPath: String
    ): String? {
        val direct = File(nativeLibraryDir, "librish.so")
        if (direct.isFile && direct.canRead()) return direct.absolutePath

        // APK zip 兜底: 抽取与主 ABI 匹配的 librish.so
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return runCatching {
            java.util.zip.ZipFile(apkPath).use { zip ->
                val entry = zip.entries().asSequence()
                    .filter { it.name.endsWith("/librish.so") && it.name.contains("lib/$abi/") }
                    .firstOrNull() ?: return null
                val target = File(context.filesDir, "hanfeng_librish.so")
                zip.getInputStream(entry).use { input ->
                    java.io.FileOutputStream(target).use { out ->
                        input.copyTo(out)
                    }
                }
                if (target.isFile && target.canRead()) target.absolutePath else null
            }
        }.getOrNull()
    }
}
