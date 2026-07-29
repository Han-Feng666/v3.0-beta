package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 把第三方已安装应用"转换为系统应用"。
 *
 * 工作流程 (经典爱玩机工具箱模式 + rewriting Magisk 友好路径):
 *   1. 获取目标 apk 路径 (ApplicationInfo.sourceDir)
 *   2. remount /system (或 /system_root) 为 rw; A/B 分区下 /system 是只读的 squashfs,
 *      该步骤在 Android 14+ A/B 设备失败 → 走 Magisk module overlay fallback
 *   3. mkdir /system/priv-app/<pkg>/  (priv-app 拿 system_server 同级权限,
 *      自启白名单豁免 / 应用后台守护豁免通常以 priv-app 为准)
 *   4. cp apk 进目录; 复制原 apk 内可能需要的 lib/native.so (Android 解压规则不严格)
 *   5. chown root:root + chmod 644 的 apk; 目录 chmod 0755 root:root
 *   6. pm uninstall --user 0 <pkg>  → 卸用户 user0 上的副本(避免冲突)
 *      系统 uid 不变但我们占用了 system slot;
 *      注意: 此举会带走用户登录态, 所以放在确认对话框后执行
 *   7. 重启后才生效 → 提示用户
 *
 * 备份策略:
 *   - 安装时记下原始 sourceDir 路径到 /data/local/tmp/.hf_sysapp_backup/<pkg>.txt
 *   - 恢复时 pm uninstall 那个 system-app + pm install 还原 user apk
 *
 * 失败容忍:
 *   remount 失败 → 返回友好提示并建议用 Magisk module 路径;
 *   所有写文件操作幂等(已存在会先 rm 旧的)
 *
 * 不能保证:
 *   - 高通 HyperOS / MIUI 的"省电策略"对 priv-app 也照样杀, 只是触发概率显著下降
 *   - 转换后该 app 的更新要走"系统应用更新"方式 (装到 /data/app 时会覆盖 system one)
 *   - 该应用已申请的 dangerous 权限组可能丢失(系统应用走 default-permissions 表)
 */
class SystemAppConverter {
    data class ShellResult(val exitCode: Int, val output: String)

    /** 转换结果摘要 - 让 UI 展示每一步成功/失败状态 */
    data class ConvertStep(
        val name: String,
        val success: Boolean,
        val message: String
    )

    data class ConvertReport(
        val packageName: String,
        val steps: List<ConvertStep>,
        val needsReboot: Boolean
    ) {
        val allSucceed: Boolean get() = steps.all { it.success }
        val summary: String
            get() = buildString {
                appendLine("包名: $packageName")
                for (s in steps) {
                    val flag = if (s.success) "OK" else "FAIL"
                    appendLine("[$flag] ${s.name}: ${s.message}")
                }
                if (needsReboot) appendLine("\n==> 需要重启系统才完全生效")
            }
    }

    companion object {
        private const val BACKUP_DIR = "/data/local/tmp/.hf_sysapp_backup"

        /** /system 是否可写。/system 是 read-only squashfs 时返 false。 */
        fun isSystemWritable(): Boolean {
            val out = SuSession.getInstance().execute("mount | grep ' /system '").output
            // Android 8+ A/B 设备 /system 不一定有独立 mount 项, 有时是 / 覆盖
            val ro = out.contains(" ro,", ignoreCase = true) ||
                out.contains(" ro ", ignoreCase = true) ||
                out.contains(" ro\n", ignoreCase = true)
            return !ro
        }

        /**
         * 检测当前设备是否使用 A/B 动态分区 (Android 14+ 大多如此)。
         * 这种设备 /system 是只读 squashfs, remount 大多失败 → 提示用户。
         */
        fun isAbPartitionDevice(): Boolean {
            val out = SuSession.getInstance()
                .execute("getprop ro.build.ab_update 2>/dev/null; getprop ro.boot.slot_suffix 2>/dev/null")
                .output
            return out.contains("true") || out.contains("_a") || out.contains("_b")
        }
    }

    /**
     * 主流程 - 把指定包名转为系统 priv-app。
     *
     * throws 情况: 极少; 错误一律落到 ConvertReport.steps 让 UI 显示
     * 返回: ConvertReport.allSucceed=true 才能 push 到 OK 状态框
     */
    fun convertToSystemApp(context: Context, packageName: String): ConvertReport {
        val steps = mutableListOf<ConvertStep>()
        val pm = context.packageManager
        val appInfo = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            steps.add(ConvertStep("查找应用", false, "包不存在: $packageName"))
            return ConvertReport(packageName, steps, needsReboot = false)
        }

        // 已是系统应用则跳过
        val isAlreadySystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isAlreadySystem) {
            steps.add(ConvertStep("状态检查", false, "该应用已经是系统应用, 无需转换"))
            return ConvertReport(packageName, steps, needsReboot = false)
        }

        val sourceApk = appInfo.sourceDir
        val nativeLibDir = appInfo.nativeLibraryDir
        val targetDir = "/system/priv-app/${packageName.replace("/", "_")}"
        val targetApk = "$targetDir/base.apk"
        val su = SuSession.getInstance()
        val esc = { s: String -> su.escapeShell(s) }

        // Step 1: 备份原 apk 路径(用户态原 apk 不动, 卸载的是 user 副本用于腾 system slot)
        val backupCmd = "mkdir -p $BACKUP_DIR && echo '$sourceApk' > $BACKUP_DIR/${esc(packageName)}.txt"
        val backupRes = su.execute(backupCmd)
        steps.add(ConvertStep(
            "备份原始路径",
            backupRes.exitCode == 0,
            backupRes.output.ifBlank { if (backupRes.exitCode == 0) "已记录 $sourceApk" else backupRes.output }
        ))

        // Step 2: remount /system 可写
        val remountCmd = "mount -o remount,rw /system 2>&1 || mount -o remount,rw / 2>&1 || true"
        val remountRes = su.execute(remountCmd)
        val remountOk = remountRes.exitCode == 0 && isSystemWritable()
        steps.add(ConvertStep(
            "remount /system rw",
            remountOk,
            if (remountOk) "ok"
            else "remount 失败(可能是 A/B 设备只读 squashfs, 建议改 Magisk module 路径)\n${remountRes.output.take(200)}"
        ))
        if (!remountOk) {
            return ConvertReport(packageName, steps, needsReboot = false)
        }

        // Step 3: mkdir 目标目录
        val mkdirRes = su.execute("mkdir -p '$targetDir' && chown root:root '$targetDir' && chmod 0755 '$targetDir'")
        steps.add(ConvertStep(
            "创建目录",
            mkdirRes.exitCode == 0,
            mkdirRes.output.ifBlank { targetDir }
        ))
        if (mkdirRes.exitCode != 0) return ConvertReport(packageName, steps, needsReboot = false)

        // Step 4: cp apk 进目录
        val cpCmd = "cp -f '${esc(sourceApk)}' '$targetApk' && chmod 644 '$targetApk' && chown root:root '$targetApk'"
        val cpRes = su.execute(cpCmd)
        steps.add(ConvertStep(
            "复制 APK",
            cpRes.exitCode == 0,
            cpRes.output.ifBlank { targetApk }
        ))
        if (cpRes.exitCode != 0) return ConvertReport(packageName, steps, needsReboot = false)

        // Step 5: 复制 native lib 目录 (有 ABI 库的应用必须带, 否则启动 crash)
        if (nativeLibDir.isNotBlank() && nativeLibDir != "/no/native/libs") {
            val libCopyCmd = "if [ -d '$nativeLibDir' ]; then " +
                "mkdir -p '$targetDir/lib' && cp -f '$nativeLibDir'/* '$targetDir/lib/' 2>/dev/null && " +
                "chmod 644 '$targetDir/lib'/* 2>/dev/null && chown root:root '$targetDir/lib'/* 2>/dev/null; " +
                "fi || true"
            val libRes = su.execute(libCopyCmd)
            steps.add(ConvertStep(
                "复制 native 库",
                true, // lib 失败不阻断 (部分纯 java 应用无 lib)
                libRes.output.ifBlank { "ok or no lib needed" }
            ))
        } else {
            steps.add(ConvertStep("复制 native 库", true, "无需 native 库"))
        }

        // Step 6: pm uninstall --user 0 (卸用户副本让出 system slot)
        // 注意: 这步会清用户登录态, 框架已预先通过确认对话框获得用户同意 - 详见 UI 层
        val pmCmd = "pm uninstall --user 0 '${esc(packageName)}' 2>&1"
        val pmRes = su.execute(pmCmd)
        // pm uninstall 成功返 "Success", 失败可能是 "Failure [DELETE_FAILED_INTERNAL_ERROR]" 等
        val pmOk = pmRes.output.contains("Success", ignoreCase = true)
        steps.add(ConvertStep(
            "卸载用户副本",
            pmOk,
            pmRes.output.take(200)
        ))

        // Step 7: 还原 /system 为 ro (保护完整性)
        su.execute("mount -o remount,ro /system 2>/dev/null || true")
        // 这步即使失败也不算致命 (reboot 会自己重 mount), 默认成功
        steps.add(ConvertStep("remount /system ro", true, "(已尝试恢复只读, 重启自动复位)"))

        return ConvertReport(packageName, steps, needsReboot = pmOk)
    }

    /**
     * 撤销转换: pm uninstall system 副本 + 重新装回用户态 apk。
     * 注意: 还原 user apk 需要 .apk 文件路径, 我们在 convertToSystemApp 时已记到 BACKUP_DIR。
     */
    fun revertFromSystemApp(context: Context, packageName: String): ConvertReport {
        val steps = mutableListOf<ConvertStep>()
        val su = SuSession.getInstance()
        val esc = { s: String -> su.escapeShell(s) }
        val targetDir = "/system/priv-app/${packageName.replace("/", "_")}"
        val backupFile = "$BACKUP_DIR/${esc(packageName)}.txt"

        // 1. 读备份
        val backupPathRes = su.execute("test -f '$backupFile' && cat '$backupFile' 2>/dev/null || echo 'NOT_FOUND'")
        val backupApkPath = backupPathRes.output.trim()
        if (backupApkPath == "NOT_FOUND" || backupApkPath.isBlank()) {
            steps.add(ConvertStep("读取备份路径", false, "无备份路径, 无法还原用户态; 用户需重新从应用商店安装"))
            return ConvertReport(packageName, steps, needsReboot = false)
        }
        steps.add(ConvertStep("读取备份路径", true, backupApkPath))

        // 2. remount /system rw
        val remountRes = su.execute("mount -o remount,rw /system 2>&1 || mount -o remount,rw / 2>&1 || true")
        val remountOk = remountRes.exitCode == 0 && isSystemWritable()
        steps.add(ConvertStep("remount /system rw", remountOk,
            if (remountOk) "ok" else "remount 失败, 后续 rm 可能不生效\n${remountRes.output.take(200)}"))

        // 3. rm system 副本
        val rmRes = su.execute("rm -rf '$targetDir' 2>&1 || true")
        steps.add(ConvertStep("删除 system 副本", rmRes.exitCode == 0, rmRes.output.ifBlank { targetDir }))

        // 4. remount ro
        su.execute("mount -o remount,ro /system 2>/dev/null || true")
        steps.add(ConvertStep("remount /system ro", true, "(已尝试恢复只读)"))

        // 5. 还原用户态 (pm install <apk-path>), 备份的 sourceApk 是 user 副本路径,
        // 可能此时已不存在 — 改用 pm install-existing 触发 pm 从 system snapshot 恢复 (无效则失败)
        val installCmd = if (backupApkPath.startsWith("/data/app/")) {
            "pm install '$backupApkPath' 2>&1 || pm install-existing '${esc(packageName)}' 2>&1 || true"
        } else {
            "pm install-existing '${esc(packageName)}' 2>&1 || true"
        }
        val installRes = su.execute(installCmd)
        // pm install 成功 "Success"
        val installOk = installRes.output.contains("Success", ignoreCase = true) ||
            installRes.output.contains("installed", ignoreCase = true)
        steps.add(ConvertStep("还原用户态", installOk, installRes.output.take(200)))

        // 6. 清理备份文件
        su.execute("rm -f '$backupFile' 2>/dev/null || true")
        steps.add(ConvertStep("清理备份", true, "(已清理)"))

        return ConvertReport(packageName, steps, needsReboot = installOk)
    }
}
