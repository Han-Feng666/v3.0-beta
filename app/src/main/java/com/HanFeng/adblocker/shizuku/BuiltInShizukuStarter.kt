package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 内置 Shizuku 激活管理器（A 路线：仍使用 dev.rikka.shizuku:api SDK，但 Shizuku server 由本 app 内置的 native starter 拉起）。
 *
 * 启动链路：
 *   1. 主 app 通过 implementation(project(":shizuku-fork:manager")) 把 fork 出的 libshizuku.so / librish.so
 *      打包进自己 APK 的 nativeLibraryDir
 *   2. Root shell 执行 `<自己 nativeLibraryDir>/libshizuku.so --apk=<自己 sourceDir>`
 *      该 native binary 是 fork 的 starter.cpp fork+setsid+execvp app_process 跑 server 的入口
 *      启动成功后 Shizuku server 通过 binder 推到本 app
 *   3. 等待 binder 上来（dev.rikka.shizuku:api 的 Shizuku.addBinderReceivedListener 在本 app 进程接收）
 *
 * 重要：不再依赖外部 Shizuku APK。整条启动链由本 app 自己完成，正是 fork 内置 Shizuku 的核心价值。
 *
 * 同时提供无线调试配对：通过 root shell 跑 `adb pair <code> <host:port>`，配对完再 fallback 走 root 激活。
 */
object BuiltInShizukuStarter {

    private const val TAG = "BuiltInShizukuStarter"

    /**
     * fork 内置 Shizuku 的 starter native 二进制名。
     * 在 shizuku-fork/manager/src/main/jni/CMakeLists.txt 中声明：
     *   add_executable(libshizuku.so starter.cpp misc.cpp selinux.cpp cgroup.cpp)
     * 名字虽带 .so 后缀但实为可执行 ELF，这是 Shizuku 把可执行伪装成 so 以便 PM 把它打进 APK 的标志做法。
     */
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

    /**
     * Root 方式激活 Shizuku（从依赖外部 Shizuku APK 改为本 app 内置 starter）。
     */
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

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        if (nativeLibDir.isBlank()) {
            return ActivationResult(false, "root", "无法获取本 app nativeLibraryDir")
        }

        val soPath = pickStarterBinaryPath(nativeLibDir)
            ?: return ActivationResult(
                false, "root",
                "无法定位内置 starter（$STARTER_BINARY_NAME）。检查 APK 是否完整安装，或重新安装本 app 让系统重新解压 native 库"
            )

        // --- 启动前的准备 ---
        // 1) 清一次 logcat 缓冲区, 让启动失败时的诊断日志更容易聚焦在最近一次 server 进程活动上
        try { SuSession.getInstance().execute("logcat -c 2>/dev/null", 3) } catch (_: Exception) {}

        Log.d(TAG, "Starter binary: apkPath=$apkPath libDir=$nativeLibDir soPath=$soPath")

        // 给 starter 二进制加可执行权限并后台启动
        // starter.cpp 一开始就检查 uid==0 / uid==2000，所以必须 root 执行
        val dollar = "\$"
        val starterCmd = buildString {
            append("chmod 755 '").append(soPath).append("' 2>/dev/null; ")
            // 立即 fork+execvp 启动 server（&必须：starter.cpp fork 后 child setsid，parent 退出）
            append("exec '").append(soPath).append("' --apk='").append(apkPath).append("' & ")
            append("STARTER_PID=${dollar}!; ")
            append("for i in 1 2 3 4; do ")
            append("  if ! kill -0 ${dollar}STARTER_PID 2>/dev/null; then break; fi; ")
            append("  sleep 1; ")
            append("done; ")
            append("sleep 1; ")
            // 我们 fork 把 SERVER_NAME 改为 hanfeng_shizuku_server，故 pgrep 用这个名字过滤
            append("PGREP_SHIZUKU=${dollar}(pgrep -f hanfeng_shizuku_server 2>/dev/null | head -1); ")
            append("if [ -n \"${dollar}PGREP_SHIZUKU\" ]; then ")
            append("  echo \"SHIZUKU_OK pid=${dollar}PGREP_SHIZUKU\"; ")
            append("else ")
            append("  if kill -0 ${dollar}STARTER_PID 2>/dev/null; then ")
            append("    echo \"SHIZUKU_PENDING starter=${dollar}STARTER_PID\"; ")
            append("  else ")
            append("    echo \"SHIZUKU_FAIL\"; ")
            append("  fi; ")
            append("fi")
        }

        val result = su.execute(starterCmd, 15)
        Log.d(TAG, "root starter exitCode=${result.exitCode} out=${result.output.take(500)}")

        val output = result.output
        // 拉一段完整 logcat 输出以便用户看到真正根因 — 不限制 tag,
        // server 进程崩在 onCreate 时栈打印在 AndroidRuntime 标签上,
        // 之前的 -s ShizukuStarter:* 过滤太严抓不到。
        val diagLog = try {
            SuSession.getInstance().execute(
                "logcat -d -b main,system,crash " +
                    "-s 'AndroidRuntime:*' 'ShizukuStarter:*' 'ShizukuService:*' " +
                    "'starter:*' 'BinderSender:*' 'System.err:*' " +
                    "'art:*' 'dvmh:*' 'ActivityManager:*' " +
                    "ShizukuService I starter D AndroidRuntime E 2>/dev/null | " +
                    "tail -200",
                5
            ).output
        } catch (_: Exception) { "" }

        // 关键修复:starter 进程在 / cmdline 含 hanfeng_shizuku_server 不代表 server 真的活着。
        // app_process 阶段可能因为 UnsatisfiedLinkError、找不到 ShizukuService 类、SELinux 阻挡
        // 等原因崩溃,而 starter 的 pgrep 检查可能命中到僵尸进程或刚启动未崩溃的过渡状态。
        // 必须以「binder 真的能 ping 通」为准,否则就是假激活。
        val starterPidRaw = extractPid(output)
        val binderReallyUp = waitForBinderAlive(8_000L)

        // 读 starter stderr 诊断文件,以便 server 启动失败时给出真实根因
        val starterErr = try {
            SuSession.getInstance().execute(
                "cat /data/local/tmp/hanfeng_shizuku_starter.err 2>/dev/null | tail -50",
                3
            ).output
        } catch (_: Exception) { "" }

        return when {
            // binder 真的活着才算成功,不管 starter 输出说啥
            binderReallyUp -> {
                ActivationResult(
                    true, "root",
                    buildString {
                        append("已通过 Root 内置 starter 启动 Shizuku 服务")
                        if (starterPidRaw != "?") append("，进程 pid=$starterPidRaw")
                        if (diagLog.isNotBlank() && diagLog.contains("FATAL", ignoreCase = true)) {
                            append("\n[诊断] server 启动有报错：").append(diagLog.takeLast(300))
                        }
                    }
                )
            }
            output.contains("SHIZUKU_OK") || output.contains("SHIZUKU_PENDING") -> {
                // starter 自认为启动了,但 binder 没上来 —— 典型的假激活场景
                // 补一段 root 自己的 SELinux 路径自验证输出, 让用户/开发者直接看到 SELinux transfer 是否被拒
                val selinuxProbe = runCatching {
                    SuSession.getInstance().execute(
                        "echo '--start_selinux_probe--'; " +
                            "getenforce 2>/dev/null; " +
                            "echo '--server-context--'; " +
                            "ps -A -o CONTEXT,NAME 2>/dev/null | grep -E 'hanfeng_shizuku_server|ShizukuService|app_process' | head -5; " +
                            "echo '--starter-err-tail--'; " +
                            "tail -100 /data/local/tmp/hanfeng_shizuku_starter.err 2>/dev/null; " +
                            "echo '--server-crash-tail--'; " +
                            "cat /data/local/tmp/hanfeng_shizuku_server.crash 2>/dev/null | tail -120",
                        4
                    ).output
                }.getOrDefault("(获取失败)")

                ActivationResult(
                    false, "root",
                    buildString {
                        append("Shizuku server 启动后未能在 8 秒内完成 binder 注册,大概率启动失败。\n")
                        append("[starter stdout]\n").append(output.take(800)).append("\n")
                        if (starterErr.isNotBlank()) {
                            append("\n[starter stderr recent]\n").append(starterErr.takeLast(600))
                        }
                        if (diagLog.isNotBlank()) {
                            append("\n[logcat last 1500 chars]\n").append(diagLog.takeLast(1500))
                        }
                        append("\n[SELinux probe / crash file]\n").append(selinuxProbe.take(2500)).append("\n")
                        append("\n=== 常见根因 ===\n")
                        append("1. server onCreate 阶段崩在 hidden API 调用(已通过 HiddenApiBypass 修复,如仍然包含 NoSuchMethodError/SecurityException,请将 logcat 报给开发者)\n")
                        append("2. server UnsatisfiedLinkError: 找不到 libshizuku.so / libstub.so\n")
                        append("3. APK 路径不可读: shell uid 无法读 /data/app/<pkg>/base.apk\n")
                        append("4. SELinux 阻挡 binder transfer\n")
                        append("\n=== 建议 ===\n")
                        append("- 把上面的 logcat 末尾 + starter stdout 整段复制反馈给开发者\n")
                        append("- 你的 ROM 若锁了 SELinux binder transfer, 改用「无线调试激活」 — 无需 root 路径\n")
                    }
                )
            }
            output.contains("SHIZUKU_FAIL") -> {
                ActivationResult(
                    false, "root",
                    buildString {
                        append("Shizuku 启动失败。")
                        if (starterErr.isNotBlank()) {
                            append("\n[starter stderr]\n").append(starterErr)
                        } else if (diagLog.isNotBlank()) {
                            append('\n').append(diagLog)
                        }
                    }
                )
            }
            else -> ActivationResult(
                false, "root",
                buildString {
                    append("Shizuku 启动异常：${output.take(200)}")
                    if (starterErr.isNotBlank()) {
                        append("\n[starter stderr]\n").append(starterErr.takeLast(400))
                    }
                }
            )
        }
    }

    /**
     * 阻塞等待 Shizuku binder 上来,最长 [timeoutMs] 毫秒。
     * 用于 activateViaRoot 在返回成功前验证 server 真的活着,避免假激活。
     */
    private fun waitForBinderAlive(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false) ||
                runCatching { rikka.shizuku.Shizuku.getBinder()?.isBinderAlive == true }.getOrDefault(false)
            if (alive) return true
            try { Thread.sleep(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        return false
    }

    private fun extractPid(out: String): String {
        val regex = Regex("pid=(\\d+)")
        return regex.find(out)?.groupValues?.getOrNull(1) ?: "?"
    }

    /**
     * 主 app 的 nativeLibraryDir 已经是 PM 解压后带 ABI 的路径，starter 二进制直接在这里。
     * 但有些 ROM PM 不解压，直接读 zip 内的 so。多种方式兜底。
     */
    private fun pickStarterBinaryPath(nativeLibDir: String): String? {
        val direct = "$nativeLibDir/$STARTER_BINARY_NAME"
        if (File(direct).isFile) return direct

        val su = SuSession.getInstance()
        val canSu = su.open(3)
        if (canSu) {
            if (su.execute("test -f '$direct' && echo YES || echo NO", 3).output.contains("YES")) {
                return direct
            }
            val pkg = appContext?.packageName
            if (pkg != null) {
                val pmOut = su.execute("pm path '$pkg' 2>/dev/null", 5).output.trim()
                for (line in pmOut.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) {
                        val apkP = trimmed.removePrefix("package:").trim()
                        val apkFile = File(apkP)
                        val libParent = apkFile.parentFile
                        if (libParent != null) {
                            val candidates = su.execute("ls '$libParent/lib' 2>/dev/null", 3).output.lines()
                                .map { it.trim() }.filter { it.isNotEmpty() }
                            for (abi in candidates) {
                                val candidate = "$libParent/lib/$abi/$STARTER_BINARY_NAME"
                                if (su.execute("test -f '$candidate' && echo YES || echo NO", 3)
                                        .output.contains("YES")) {
                                    return candidate
                                }
                            }
                        }
                    }
                }
            }
        }

        // 终极兜底：从 APK 内提取 lib/<abi>/libshizuku.so 写入 app cacheDir 并 chmod 755
        return extractStarterFromApk()
    }

    /**
     * 直接打开本 app APK 文件，从 zip 内找出 lib/<abi>/libshizuku.so，复制到 cacheDir/shizuku_starter/libshizuku.so。
     * 解决极端场景下 PM 不解压 / 解压权限受限的问题。
     */
    private fun extractStarterFromApk(): String? {
        val context = appContext ?: return null
        val apkPath = context.applicationInfo.sourceDir
        if (apkPath.isBlank() || !File(apkPath).isFile) return null
        val targetDir = File(context.cacheDir, "shizuku_starter")
        if (!targetDir.exists()) targetDir.mkdirs()
        val targetFile = File(targetDir, STARTER_BINARY_NAME)

        val chosenAbi = chooseAbi() ?: return null
        val zipEntryPath = "lib/$chosenAbi/$STARTER_BINARY_NAME"
        try {
            java.util.zip.ZipFile(File(apkPath)).use { zip ->
                val entry = zip.getEntry(zipEntryPath) ?: return null
                zip.getInputStream(entry).use { input ->
                    java.io.FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            if (!targetFile.isFile || targetFile.length() < 1024) return null
            val su = SuSession.getInstance()
            if (su.open(3)) {
                su.execute("chmod 755 '${targetFile.absolutePath}'", 3)
            } else {
                targetFile.setExecutable(true, true)
            }
            Log.d(TAG, "extractStarterFromApk: extracted to ${targetFile.absolutePath} (abi=$chosenAbi, size=${targetFile.length()})")
            return targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "extractStarterFromApk failed: ${e.message}")
            return null
        }
    }

    private fun chooseAbi(): String? {
        val abis = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.os.Build.SUPPORTED_ABIS.toList()
        } else {
            listOf(android.os.Build.CPU_ABI)
        }
        val arm64 = abis.firstOrNull { it == "arm64-v8a" }
        if (arm64 != null) return arm64
        val arm = abis.firstOrNull { it == "armeabi-v7a" }
        if (arm != null) return arm
        val x64 = abis.firstOrNull { it == "x86_64" }
        if (x64 != null) return x64
        val x86 = abis.firstOrNull { it == "x86" }
        if (x86 != null) return x86
        return null
    }

    /**
     * ADB / Shizuku App 引导
     */
    fun activateViaAdb(context: Context, adbHost: String = "127.0.0.1", adbPort: Int = 5555): ActivationResult {
        val su = SuSession.getInstance()
        if (su.open(5)) {
            return activateViaRoot()
        }
        return ActivationResult(
            false,
            "adb",
            "未 Root：在电脑上用 `adb shell sh /sdcard/Android/data/${context.packageName}/start.sh` 启动 Shizuku"
        )
    }

    /**
     * 无线调试激活状态
     */
    fun activateViaWirelessDebug(context: Context): ActivationResult {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return ActivationResult(false, "wireless", "无线调试激活需要 Android 11 及以上版本")
        }

        val adbEnabled = android.provider.Settings.Global.getInt(
            context.contentResolver, "adb_enabled", 0
        ) == 1
        if (!adbEnabled) {
            return ActivationResult(false, "wireless", "请先在开发者选项开启 USB 调试")
        }

        return ActivationResult(
            false,
            "wireless",
            "请在通知栏输入配对码以触发配对，配对成功后自动以 root 启动内置 Shizuku"
        )
    }

    /**
     * 给配对码完成配对 + 通过本机 adbd 拉起 Shizuku server。
     *
     * 真实无线调试激活流程（一比一照搬官方 Shizuku AdbPairingClient + AdbClient 协议）:
     *   1. 用 fork 内的 moe.shizuku.manager.adb.AdbPairingClient 跟本机 adbd 的 _adb-tls-pairing._tcp 端口做
     *      SPAKE2 + TLS 1.3 握手,把本 app 的 RSA 公钥写到 /data/misc/adb/adb_keys,完成 ADB 配对。
     *      (此步骤不需要 root 也不依赖 /system/bin/adb,与桌面端 adb pair 动作等价)
     *   2. mDNS 发现 _adb-tls-connect._tcp 端口(adbd 配对完成后才会暴露 connect 服务)。
     *   3. 用 AdbClient.connect(host, connectPort, key) 通过 ADB 协议跟 adbd 握手并完成 TLS。
     *   4. AdbClient.shellCommand("$starterPath --apk=$apkPath") 让 adbd 通过 shell 执行 starter 二进制,
     *      starter.cpp 以 uid=2000 (shell) fork+execvp app_process 启动 Shizuku server,
     *      server 通过 BinderSender 把 binder 推给本 app。
     *   5. waitForBinderAlive 等待 binder 真的上来。
     *
     * 注意 PairingContext 通过 JNI 调用 src/main/jni/adb_pairing.cpp,在 libadb.so 里实现 SPAKE2。
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    fun pairAndActivateViaWirelessDebug(pairingCode: String, host: String, port: Int): ActivationResult {
        val context = appContext
            ?: return ActivationResult(false, "wireless", "BuiltInShizukuStarter 未初始化")
        if (pairingCode.length != 6) {
            return ActivationResult(false, "wireless", "配对码应为 6 位数字")
        }

        // 0. 准备本 app 的 ADB RSA Key (跟 adbd 配对成功后该 key 被写到 /data/misc/adb/adb_keys)
        val prefs = context.getSharedPreferences("wireless_adb_key", android.content.Context.MODE_PRIVATE)
        val keyStore = moe.shizuku.manager.adb.PreferenceAdbKeyStore(prefs)
        val key = runCatching {
            moe.shizuku.manager.adb.AdbKey(keyStore, context.packageName)
        }.getOrElse {
            return ActivationResult(false, "wireless", "ADB Key 生成失败: ${it.message}")
        }

        // 1. 跟本机 adbd 做 SPAKE2+TLS 握手
        runCatching {
            moe.shizuku.manager.adb.AdbPairingClient(host, port, pairingCode, key).use { client ->
                val ok = client.start()
                if (!ok) return@use
                Log.d(TAG, "adb pair succeeded, key written to /data/misc/adb/adb_keys")
            }
        }.onFailure {
            return ActivationResult(false, "wireless", "ADB 配对失败: ${it.message}")
        }

        // 2. mDNS 发现 connect 端口(_adb-tls-connect._tcp),最长 5 秒
        val helper = com.HanFeng.adblocker.shizuku.WirelessDebugPairingHelper(context)
        val connectPort = discoverConnectPortSynchronous(helper, 5000L)
        helper.stopDiscovery()
        if (connectPort == null) {
            return ActivationResult(false, "wireless", "配对成功但 5 秒内未发现 ADB 连接端口,请稍后重试")
        }
        Log.d(TAG, "adb connect port discovered: $connectPort")

        // 3~4. AdbClient.connect + shellCommand 跑 starter
        val apkPath = context.applicationInfo.sourceDir
        val soPath = pickStarterBinaryPath(context.applicationInfo.nativeLibraryDir)
            ?: return ActivationResult(
                false, "wireless",
                "无法定位内置 starter($STARTER_BINARY_NAME)。请重新安装本 app"
            )
        // starter 二进制在 /data/app/<pkg>/lib/<abi>/libshizuku.so,需要赋予 0755 才能被 shell 执行。
        // extractNativeLibs=true 时 PM 已解压 .so 到磁盘并设好执行位,但仍保险一下。
        val starterShellCmd = "chmod 755 '$soPath' 2>/dev/null; exec '$soPath' --apk='$apkPath'"
        val buffer = StringBuilder()
        runCatching {
            moe.shizuku.manager.adb.AdbClient(host, connectPort, key).use { client ->
                client.connect()
                client.shellCommand(starterShellCmd) { bytes ->
                    // starter 输出收集前 2KB,作为诊断日志
                    if (buffer.length < 2048) {
                        buffer.append(String(bytes))
                    }
                }
            }
        }.onFailure {
            return ActivationResult(false, "wireless", "ADB 连接/shell 执行失败: ${it.message}\n[starter out]\n$buffer")
        }
        Log.d(TAG, "starter shell exec launched, out=${buffer.take(500)}")

        // 5. 等 binder 上来
        val binderUp = waitForBinderAlive(8_000L)
        return if (binderUp) {
            ActivationResult(true, "wireless", "已通过无线调试激活 Shizuku 服务")
        } else {
            ActivationResult(
                false, "wireless",
                "Starter 已发起,但 8 秒内未收到 Binder,可能 adbd 入口启动失败。\n[starter out]\n${buffer.take(500)}"
            )
        }
    }

    /**
     * 同步等待 mDNS 发现 _adb-tls-connect._tcp 端口,超时返回 null。
     */
    private fun discoverConnectPortSynchronous(
        helper: com.HanFeng.adblocker.shizuku.WirelessDebugPairingHelper,
        timeoutMs: Long
    ): Int? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val portRef = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        helper.startConnectDiscovery({ port ->
            if (port != null) {
                portRef.set(port)
                latch.countDown()
            } else {
                latch.countDown()
            }
        }, timeoutMs + 1000L)
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        return portRef.get()
    }

    /**
     * 一键智能激活
     */
    fun smartActivate(context: Context): ActivationResult {
        val su = SuSession.getInstance()
        if (su.open(5)) {
            return activateViaRoot()
        }
        return activateViaWirelessDebug(context)
    }

    /**
     * 检查主 app 是否已内置 Shizuku starter 二进制。
     */
    fun isShizukuBuiltin(): Boolean {
        val context = appContext ?: return false
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return File("$nativeLibDir/$STARTER_BINARY_NAME").isFile
    }

    fun isShizukuAppInstalled(context: Context): Boolean {
        // A 路线下不要求外部 Shizuku APK，但旧用户可能仍装着，做兼容判断
        val pkgs = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager", "stellar.shizuku")
        return pkgs.any { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }
    }

    /**
     * 获取当前可用的激活方式列表
     */
    fun getAvailableModes(context: Context): List<String> {
        val modes = mutableListOf<String>()

        if (SuSession.getInstance().open(3)) {
            modes.add("Root 激活（内置 starter）")
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            modes.add("无线调试 (Android 11+)")
        }

        if (modes.isEmpty()) {
            modes.add("无线调试引导")
        }

        return modes
    }

    /**
     * 停止 Shizuku 服务
     */
    fun stop(context: Context): ActivationResult {
        val su = SuSession.getInstance()
        if (!su.open(5)) {
            return ActivationResult(false, "root", "Root 权限获取失败")
        }

        val result = su.execute(
            "pkill -f 'hanfeng_shizuku_server' 2>/dev/null; " +
                "pkill -f 'rikka.shizuku.server.ShizukuService' 2>/dev/null; " +
                "pkill -f 'libshizuku.so' 2>/dev/null; " +
                "rm -f /dev/socket/hanfeng_shizuku_server 2>/dev/null; " +
                "echo STOP_DONE",
            10
        )

        val ok = result.output.contains("STOP_DONE")
        return ActivationResult(ok, "root", if (ok) "Shizuku 服务停止命令已执行" else "Shizuku 服务停止失败：${result.output}")
    }

    /**
     * 获取 Shizuku 启动日志
     */
    fun getStartupLogs(): String {
        return try {
            val su = SuSession.getInstance()
            if (su.open(3)) {
                su.execute("logcat -d -s ShizukuStarter:* ShizukuService:* starter:* BuiltInShizukuStarter:*", 5)
                    .output.takeLast(2000)
            } else {
                "无法获取 Root 权限"
            }
        } catch (e: Exception) {
            "获取日志失败: ${e.message}"
        }
    }
}
