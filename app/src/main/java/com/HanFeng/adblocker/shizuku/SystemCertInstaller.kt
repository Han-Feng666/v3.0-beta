package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import com.HanFeng.security.CertificateAuthorityManager
import java.security.MessageDigest
import java.security.cert.X509Certificate

class SystemCertInstaller(private val context: Context) {

    companion object {
        private const val TAG = "SystemCertInstaller"
        private const val SYSTEM_CACERTS_DIR = "/system/etc/security/cacerts"
        private const val CONSCRYPT_CACERTS_DIR = "/apex/com.android.conscrypt/cacerts"

        fun isRootAvailable(): Boolean {
            return SuSession.getInstance().open(timeoutSeconds = 5)
        }
    }

    sealed class InstallResult {
        /** 安装成功。method 是什么方式成功的；diagnostics 是各步骤详细输入便于诊断 */
        data class Success(val method: String, val hashName: String, val persistent: Boolean,
                            val diagnostics: Map<String, String> = emptyMap()) : InstallResult()
        /** 安装失败；triedMethods 简短任务名；diagnostics 详细 shell 输出供诊断 */
        data class Failure(val reason: String, val triedMethods: List<String>,
                            val diagnostics: Map<String, String> = emptyMap()) : InstallResult()
    }

    sealed class UninstallResult {
        data class Success(val removedFrom: List<String>) : UninstallResult()
        data class Failure(val reason: String) : UninstallResult()
    }

    data class SystemCertInfo(val hash: String, val path: String, val permissions: String)

    /**
     * 安装证书到系统 CA 目录。Android 14+ 实际加载路径在 /apex/com.android.conscrypt/cacerts，
     * 老版本在 /system/etc/security/cacerts。为兼容性，两个目录都尝试安装。
     *
     * 算法（参考 ProxyPinCA Magisk 模块 post-fs-data.sh 的标准安装方式）：
     *   1. 把证书 cp 到 /data/local/tmp/<hash>.0，并 chown/chmod 让 SELinux 上下文符合
     *   2. 对每个目录分别尝试：
     *      (a) 持久化写盘：mount -o remount,rw + cp + remount,ro。老 Android 主要走这条
     *      (b) tmpfs overlay：mktemp staging dir → 复制原目录所有 *.0 + 新证书 → 把 staging
     *          整体 bind mount 到目标目录。Android 14+ APEX 不可写时走这条（不破坏系统重启）
     *      (c) bind mount：仅本进程级 mount namespace，但配 nsenter 到 pid=1 + 所有 zygote 进程，
     *          让每个 App 的私有 mount namespace 也看到挂载点（关键：只在 uid=1 nsenter 不够）
     *
     * 任一目录任一手段成功即返回 Success。persistent=true 当且仅当(a) 写盘成功（重启保留）。
     * 失败时把 diagnostics 一并随 Failure 返回，UI 弹窗里可以看到具体哪一步挂了。
     */
    fun installToSystem(): InstallResult {
        val cert = CertificateAuthorityManager.getPublicCertificateX509(context)
            ?: return InstallResult.Failure("CA 证书未生成，请先在 MITM 设置中生成证书", emptyList())

        val hash = computeCertHash(cert)
        val certFileName = "$hash.0"
        val triedMethods = mutableListOf<String>()
        val diagnostics = LinkedHashMap<String, String>()
        val session = SuSession.getInstance()

        // 确保证书已生成并落盘到 app 内部 filesDir/certs/HanFeng.cer
        // 关键修复: 用 app 内部绝对路径而非 MediaStore Downloads 路径.
        // Android 10+ MediaStore 返回的展示路径是中文"下载/HanFeng/HanFeng.crt",
        // 但 root shell 工作目录是 / 且真实路径是英文 Download/, 直接 cp 中文相对路径必失败.
        // root 可以读 /data/data/com.HanFeng/files/certs/HanFeng.cer (绝对路径全国统一).
        CertificateAuthorityManager.ensureCaInstalledFiles(context)
        val srcCertPath = CertificateAuthorityManager.getPublicCertAbsolutePath(context)
            ?: return InstallResult.Failure("找不到 app 内部证书文件, 请先在 MITM 设置中生成证书", triedMethods, diagnostics)

        // 预检源文件 root 可读, 失败给出明确诊断而非让 cp 信息一闪而过
        val srcCheck = session.execute("test -f '$srcCertPath' && echo FOUND || echo MISSING", timeoutSeconds = 3)
        if (!srcCheck.output.contains("FOUND")) {
            return InstallResult.Failure(
                "app 内部证书路径不可读: $srcCertPath (root 无法访问 app 私有目录, 可能 SELinux 限制)",
                triedMethods, diagnostics
            )
        }

        val tmpPath = "/data/local/tmp/hf_cert_$certFileName"

        // 1. root cp 证书到 /data/local/tmp
        val copyCmd = "cp -f '$srcCertPath' '$tmpPath' && chmod 644 '$tmpPath' && " +
            "chcon u:object_r:system_security_file:s0 '$tmpPath' 2>/dev/null; " +
            "test -s '$tmpPath' && echo OK || echo FAIL"
        val copyResult = session.execute(copyCmd, timeoutSeconds = 6)
        diagnostics["copy_to_tmp"] = copyResult.output.trim().take(500)
        if (!copyResult.output.contains("OK")) {
            return InstallResult.Failure(
                "证书复制到 /data/local/tmp 失败：${copyResult.output.trim()}", triedMethods, diagnostics
            )
        }

        var installedAt: String? = null
        var persistent = false

        // 2. 先处理 /system/etc/security/cacerts（老 Android 主路径，写盘成功率较高）
        triedMethods.add("system")
        val systemResult = installCertToDir(tmpPath, certFileName, SYSTEM_CACERTS_DIR, "system", diagnostics)
        if (systemResult.success) {
            installedAt = "system"
            persistent = persistent || systemResult.persistent
            triedMethods.add(if (systemResult.persistent) "system_persist_ok" else "system_overlay_ok")
        } else {
            triedMethods.add("system_fail")
        }

        // 3. 再处理 /apex/com.android.conscrypt/cacerts（Android 14+ 实际加载点）
        //    不论 system 目录是否成功，conscrypt 都装一遍，双写保证 Android 16 走 conscrypt 引擎的
        //    App 也能看到证书。这条路径几乎不可写，会走 tmpfs overlay 或 nsenter bind mount。
        triedMethods.add("conscrypt")
        val conscryptResult = installCertToDir(tmpPath, certFileName, CONSCRYPT_CACERTS_DIR, "conscrypt", diagnostics)
        if (conscryptResult.success) {
            installedAt = installedAt ?: "conscrypt"
            persistent = persistent || conscryptResult.persistent
            triedMethods.add(if (conscryptResult.persistent) "conscrypt_persist_ok" else "conscrypt_overlay_ok")
        } else {
            triedMethods.add("conscrypt_fail")
        }

        // 清理 tmp：写盘已落地、bind 路径需要 tmpPath 保留作挂载点源
        if (persistent) {
            session.execute("rm -f '$tmpPath'", timeoutSeconds = 2)
        }

        // 即时挂载 (走 SuSession 当前进程 root) 走一层.
        // Android 14+/16 EROFS 只读 fs 上 (a)(b)(c) 三段即时路径基本全失败 - APEX 不可写,
        // bind mount 也可能因严格 mount namespace 策略оюза言语让系统全员只看到 init 进程的 ns,
        // 单 app 上下文挂载进入-init:进入-zygote 的 nsbind mount 是关键点但是仍然可能失败.
        //
        // 所以最终兜底: 写 root 模块 hf_system_cacerts, post-fs-data.sh 在 boot 早段
        // (zygote 启动前) staging bind mount, 所有 app 启动时立刻就看到证书, 绕开 EROFS 限制.
        // 这样即便 SuSession 即时挂载失败, 用户重启一次后证书永久生效, 模块也会自动重做挂载.
        triedMethods.add("module")
        val moduleResult = installToSystemModule(tmpPath, certFileName, diagnostics)
        if (moduleResult) {
            triedMethods.add("module_ok")
            // 模块成功后, 即便 SuSession 即时挂载没成, 也算 success - 但 persistent=true,
            // 提示用户需要重启一次让 post-fs-data.sh 真正跑起来 mount.
            return if (installedAt != null) {
                // 即时挂载也成 - 完美 case
                InstallResult.Success(
                    "installed_at=$installedAt, module_ok=true, persistent=true",
                    certFileName, true, diagnostics
                )
            } else {
                // SuSession 即时挂载失败, 但模块已写盘 - 重启后生效
                InstallResult.Success(
                    "SuSession 即时挂载失败但已写入模块 hf_system_cacerts, persistent=true (重启后生效)",
                    certFileName, true, diagnostics
                )
            }
        } else {
            triedMethods.add("module_fail")
        }

        return if (installedAt != null) {
            val methodLabel = buildString {
                append("installed_at=").append(installedAt)
                append(", persistent=").append(persistent)
                append(", success_methods=[")
                append(triedMethods.filter { it.endsWith("_ok") }.joinToString(", "))
                append("]")
            }
            InstallResult.Success(methodLabel, certFileName, persistent, diagnostics)
        } else {
            InstallResult.Failure(
                "所有安装方式均失败，详见诊断信息。\n已尝试：${triedMethods.joinToString(", ")}",
                triedMethods, diagnostics
            )
        }
    }

    /**
     * 把证书写入 root 模块 hf_system_cacerts, post-fs-data.sh 在 boot 早段 staging bind mount
     * 到 /system/etc/security/cacerts 和 /apex/com.android.conscrypt/cacerts.
     *
     * 这是 Android 14+/16 EROFS 上唯一稳定的持久化方式 - SuSession 即时 bind mount 即便成功,
     * 重启即失效; 而 root 模块方式是 init 阶段 post-fs-data 时挂载, 在 zygote 启动前就装好,
     * 所有 app 启动时全军就看到证书, 且重启不丢.
     *
     * 参考 ProxyPinCA Magisk 模块 post-fs-data.sh 的标准模式:
     *   1. mkdir /data/adb/hf_cacerts 持久 cert staging
     *   2. mkdir /data/adb/modules/hf_system_cacerts 写 module.prop + post-fs-data.sh
     *   3. post-fs-data.sh: 复制原系统证书 + 我们的证书到临时 staging → bind mount 到两个目录
     *   4. setcap + chcon 让 SELinux label 与原系统证书一致
     */
    private fun installToSystemModule(
        tmpPath: String,
        certFileName: String,
        diagnostics: LinkedHashMap<String, String>
    ): Boolean {
        val session = SuSession.getInstance()
        val cacertsStagingDir = "/data/adb/hf_cacerts"
        val moduleDir = "/data/adb/modules/hf_system_cacerts"

        val cmd = buildString {
            // 1. 把证书持久存到 /data/adb/hf_cacerts (root-only 持久目录, 重启不丢)
            append("mkdir -p '$cacertsStagingDir' && ")
            append("cp -f '$tmpPath' '$cacertsStagingDir/$certFileName' && ")
            append("chmod 644 '$cacertsStagingDir/$certFileName' && ")
            // 写一个 sentinel 文件让模块 post-fs-data.sh 知道有证书要装
            append("touch '$cacertsStagingDir/.install_marker' && ")

            // 2. 创建 root 模块
            append("mkdir -p '$moduleDir' && ")

            // module.prop
            append("cat > '$moduleDir/module.prop' << 'EOPROP'\n")
            append("id=hf_system_cacerts\n")
            append("name=寒枫系统证书安装\n")
            append("version=v1.0\n")
            append("versionCode=1\n")
            append("author=HanFeng\n")
            append("description=把寒枫 MITM CA 证书安装到 /system/etc/security/cacerts 和 /apex/com.android.conscrypt/cacerts, 在 boot 早段 staging bind mount, 绕开 Android 14+/16 EROFS 只读 fs 限制\n")
            append("EOPROP\n")

            // post-fs-data.sh - 在 zygote 启动前跑,
            // 参考 ProxyPinCA 模块. 关键: bind mount 整个 staging 目录到系统目录,
            // 必须先把原系统证书全 cp 到 staging 才能覆盖原证书列表.
            append("cat > '$moduleDir/post-fs-data.sh' << 'EOPFD'\n")
            append("#!/system/bin/sh\n")
            append("MODDIR=\${0%/*}\n")
            append("STAGING_DIR=/data/adb/hf_cacerts/staging\n")
            append("CERT_SOURCE=/data/adb/hf_cacerts\n")
            append("\n")
            append("# 没有 sentinel 文件就不做事, 让模块纯净\n")
            append("[ -f \"\$CERT_SOURCE/.install_marker\" ] || exit 0\n")
            append("\n")
            append("# staging: 复制原系统证书 + 我们的证书, 然后 bind 整个 staging 到两个 CA 目录\n")
            append("setup_cacerts_dir() {\n")
            append("  local target_dir=\$1\n")
            append("  [ -d \"\$target_dir\" ] || return 1\n")
            append("  local staging=\"\$STAGING_DIR/\$(basename \"\$target_dir\")\"\n")
            append("  rm -rf \"\$staging\"\n")
            append("  mkdir -p -m 700 \"\$staging\"\n")
            append("  cp -f \"\$target_dir\"/* \"\$staging\"/ 2>/dev/null\n")
            append("  cp -f \"\$CERT_SOURCE\"/*.0 \"\$staging\"/ 2>/dev/null\n")
            append("  chmod 644 \"\$staging\"/*.0 2>/dev/null\n")
            append("  # 复用原目录的 SELinux label\n")
            append("  local ctx=\$(ls -Zd \"\$target_dir\" 2>/dev/null | awk '{print \$1}')\n")
            append("  if [ -n \"\$ctx\" ] && [ \"\$ctx\" != \"?\" ]; then chcon -R \"\$ctx\" \"\$staging\" 2>/dev/null; fi\n")
            append("  chown -R 0:0 \"\$staging\" 2>/dev/null\n")
            append("  # 完整性检查 - 证书数太少防止挂坏\n")
            append("  local n=\$(ls -1 \"\$staging\" 2>/dev/null | wc -l)\n")
            append("  if [ \"\$n\" -gt 10 ]; then\n")
            append("    mount --bind \"\$staging\" \"\$target_dir\"\n")
            append("    return 0\n")
            append("  else\n")
            append("    return 1\n")
            append("  fi\n")
            append("}\n")
            append("\n")
            append("setup_cacerts_dir /system/etc/security/cacerts\n")
            append("# Android 14+ real loading path - 不存在就跳过\n")
            append("[ -d /apex/com.android.conscrypt/cacerts ] && setup_cacerts_dir /apex/com.android.conscrypt/cacerts\n")
            append("EOPFD\n")
            append("chmod 755 '$moduleDir/post-fs-data.sh' && ")

            // update sentinel 让 Magisk/KSU 下次 boot 时刷一遍模块
            append("touch '$moduleDir/update' && ")

            // 验证
            append("if [ -f '$moduleDir/module.prop' ] && [ -x '$moduleDir/post-fs-data.sh' ] && [ -f '$cacertsStagingDir/$certFileName' ]; then echo OK; else echo FAIL; fi")
        }

        val result = session.execute(cmd, timeoutSeconds = 10)
        diagnostics["module_install"] = result.output.trim().take(800)
        return result.output.contains("OK")
    }


    /** 单目录安装结果 */
    private data class DirInstallResult(val success: Boolean, val persistent: Boolean)

    /**
     * 对单个 CA 目录依次按优先级尝试：(a) remount rw + cp 持久写 (b) tmpfs overlay + 完整 bind
     * (c) nsenter bind mount 到所有 zygote namespace。任一成功就返回。
     * 参考 ProxyPin 模块 post-fs-data.sh 的标准做法。
     */
    private fun installCertToDir(tmpPath: String, certFileName: String,
                                  dirPath: String, dirName: String,
                                  diagnostics: LinkedHashMap<String, String>): DirInstallResult {
        val session = SuSession.getInstance()
        val targetPath = "$dirPath/$certFileName"

        // (a) 写盘：remount,rw + cp。APEX / 现代 /system 多半会失败，但老 Android 走这条
        val remountResult = session.execute(
            "mount -o remount,rw '$dirPath' 2>&1 | head -2; " +
                "mount -o remount,rw /system 2>/dev/null; mount -o remount,rw / 2>/dev/null; " +
                // 写盘必须 cp 真成功, 决不能跟 remount 失败并行: 用分量 && 链+最后做一致性校验
                "cp -f '$tmpPath' '$targetPath' && chmod 644 '$targetPath' && sync || true; " +
                // 严格校验: 文件存在 + 大小非0 + md5 == 源, 三条都过才算 OK
                "if [ -f '$targetPath' ] && [ -s '$targetPath' ]; then " +
                "  SRC_MD5=\$(md5sum '$tmpPath' 2>/dev/null | awk '{print \$1}'); " +
                "  DST_MD5=\$(md5sum '$targetPath' 2>/dev/null | awk '{print \$1}'); " +
                "  if [ -n \"\$SRC_MD5\" ] && [ \"\$SRC_MD5\" = \"\$DST_MD5\" ]; then echo OK; " +
                "  else echo MD5_MISMATCH; fi; " +
                "else echo FAIL; fi; " +
                // remount 失败 / cp / test 失败都恢复只读, 防止误把系统留在 rw 状态
                "mount -o remount,ro /system 2>/dev/null; mount -o remount,ro / 2>/dev/null; true",
            timeoutSeconds = 8
        )
        diagnostics["${dirName}_remount_cp"] = remountResult.output.trim().take(500)
        if (remountResult.output.contains("OK") && !remountResult.output.contains("MD5_MISMATCH")) {
            // 恢复只读保证系统稳定
            session.execute("mount -o remount,ro /system 2>/dev/null; mount -o remount,ro / 2>/dev/null; true",
                timeoutSeconds = 2)
            return DirInstallResult(success = true, persistent = true)
        }

        // (b) tmpfs overlay：staging → 全量 cp 原 *.0 + 新证书 → bind staging 到目标目录
        //     再对每个 zygote / init 进程 nsenter 进它自己的 mount namespace bind 一遍
        //     这是 ProxyPin 模块在 Android 14+ 实际工作的方式，关键点：
        //       - 整个目录 bind 而不是单证书 bind（避免覆盖原系统证书列表）
        //       - 完整性检查（新目录证书数 >10 才 mount，防止挂错把系统弄挂）
        //       - 遍历 pid=1 + 所有 zygote 进程做 nsenter，让每个 App 的私有 mount namespace 都看到
        val overlayCmd = buildString {
            append("STAGING=/data/local/tmp/hf_cacerts_$dirName; ")
            append("rm -rf \$STAGING 2>/dev/null; ")
            append("mkdir -p -m 700 \$STAGING && ")
            append("cp -f '$dirPath'/* \$STAGING/ 2>/dev/null; ")
            append("cp -f '$tmpPath' \$STAGING/$certFileName && chmod 644 \$STAGING/*.0 2>/dev/null; ")
            // 上下文用原目录的 SELinux label，避免 chcon 写死错上下文
            append("CTX=\$(ls -Zd '$dirPath' 2>/dev/null | awk '{print \$1}'); ")
            append("if [ -n \"\$CTX\" ] && [ \"\$CTX\" != \"?\" ]; then chcon -R \$CTX \$STAGING 2>/dev/null; fi; ")
            append("chown -R 0:0 \$STAGING 2>/dev/null; ")
            // 完整性检查：证书目录空了就别 mount，否则系统无能解 HTTPS
            append("CERTS_NUM=\$(ls -1 \$STAGING 2>/dev/null | wc -l); ")
            append("if [ \"\$CERTS_NUM\" -gt 10 ]; then ")
            append("  mount -o bind \$STAGING '$dirPath' 2>&1 | head -1; ")
            // nsenter 到 init + zygote + zygote64，每个 mount namespace 单独 bind
            append("  for pid in 1 \$(pgrep zygote) \$(pgrep zygote64); do ")
            append("    [ -d /proc/\$pid/ns/mnt ] && nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$STAGING '$dirPath' 2>/dev/null; ")
            append("  done; ")
            // 关键: 必须真正校验挂载上了 - 用 findmnt 看 dirPath 的 SOURCE 是 STAGING,
            // 而且目标目录里能直接 ls 到 certFileName 才算 OK,
            // 否则前几步"挂载了"的 echo 可能被前面 nsenter 报错覆盖误判.
            append("  MOUNT_SRC=\$(findmnt -n -o SOURCE '$dirPath' 2>/dev/null); ")
            append("  if [ \"\$MOUNT_SRC\" = \"\$STAGING\" ] && [ -f '$dirPath/$certFileName' ]; then echo OK; ")
            append("  else echo MOUNT_NOT_VALID; fi; ")
            append("else echo STAGING_EMPTY; fi")
        }
        val overlayResult = session.execute(overlayCmd, timeoutSeconds = 10)
        diagnostics["${dirName}_overlay_bind"] = overlayResult.output.trim().take(500)
        if (overlayResult.output.contains("OK") &&
            !overlayResult.output.contains("MOUNT_NOT_VALID") &&
            !overlayResult.output.contains("STAGING_EMPTY")
        ) {
            // 注意：staging 目录不要立即 rm — bind mount 持有的源对象必须保留
            return DirInstallResult(success = true, persistent = false)
        }

        // (c) 单证书 bind mount：兜底，前两条都失败的最后尝试，与 (b) 一样也 nsenter 到 zygote 各进程
        val singleBindCmd = buildString {
            append("mount --bind '$tmpPath' '$targetPath' 2>&1 | head -1; ")
            append("for pid in 1 \$(pgrep zygote) \$(pgrep zygote64); do ")
            append("  [ -d /proc/\$pid/ns/mnt ] && nsenter --mount=/proc/\$pid/ns/mnt -- mount --bind '$tmpPath' '$targetPath' 2>/dev/null; ")
            append("done; ")
            // 同样严格: findmnt source 是 tmpPath, targetPath 内容非空 md5 与源一致
            append("MOUNT_SRC=\$(findmnt -n -o SOURCE '$targetPath' 2>/dev/null); ")
            append("if [ \"\$MOUNT_SRC\" = '$tmpPath' ] && [ -s '$targetPath' ]; then ")
            append("  SRC_MD5=\$(md5sum '$tmpPath' 2>/dev/null | awk '{print \$1}'); ")
            append("  DST_MD5=\$(md5sum '$targetPath' 2>/dev/null | awk '{print \$1}'); ")
            append("  if [ \"\$SRC_MD5\" = \"\$DST_MD5\" ]; then echo OK; else echo MD5_MISMATCH; fi; ")
            append("else echo MOUNT_NOT_VALID; fi")
        }
        val bindResult = session.execute(singleBindCmd, timeoutSeconds = 6)
        diagnostics["${dirName}_single_bind"] = bindResult.output.trim().take(500)
        return if (bindResult.output.contains("OK") && !bindResult.output.contains("MD5_MISMATCH")) {
            DirInstallResult(success = true, persistent = false)
        } else {
            DirInstallResult(success = false, persistent = false)
        }
    }

    /**
     * 卸载系统证书：umount bind 后删除实际证书文件。
     * 由于安装时可能 bind 到所有 zygote namespace，卸载时也要 nsenter 进各 namespace 单独 umount。
     */
    fun uninstallFromSystem(): UninstallResult {
        val cert = CertificateAuthorityManager.getPublicCertificateX509(context)
            ?: return UninstallResult.Failure("CA 证书未找到")
        val hash = computeCertHash(cert)
        val certFileName = "$hash.0"
        val removedFrom = mutableListOf<String>()
        val session = SuSession.getInstance()

        // 对两个目录分别处理：先 umount bind mount，再删实际文件
        listOf(
            "system" to "$SYSTEM_CACERTS_DIR/$certFileName",
            "conscrypt" to "$CONSCRYPT_CACERTS_DIR/$certFileName"
        ).forEach { (name, path) ->
            // 在 init + 所有 zygote namespace 都 umount 一遍
            session.execute(
                "umount '$path' 2>/dev/null; umount -l '$path' 2>/dev/null; " +
                "for pid in 1 \$(pgrep zygote) \$(pgrep zygote64); do " +
                "  if [ -d /proc/\$pid/ns/mnt ]; then " +
                "    nsenter --mount=/proc/\$pid/ns/mnt -- umount '$path' 2>/dev/null; " +
                "    nsenter --mount=/proc/\$pid/ns/mnt -- umount -l '$path' 2>/dev/null; " +
                "  fi; done",
                timeoutSeconds = 4
            )
            // 删实际证书（bind 解除后才能看到底层文件）
            val rmResult = session.execute(
                "test -f '$path' && " +
                "mount -o remount,rw /system 2>/dev/null; " +
                "rm -f '$path' 2>/dev/null && " +
                "sync && " +
                "test ! -f '$path' && echo REMOVED || echo FAIL",
                timeoutSeconds = 6
            )
            if (rmResult.output.contains("REMOVED")) {
                removedFrom.add(name)
            }
        }

        return when {
            removedFrom.isNotEmpty() -> {
                // 即时挂载删了, 但模块里的证书 staging 没删 - 也清掉, 防止重启后 post-fs-data 又装回来
                val module = "/data/adb/modules/hf_system_cacerts"
                val staging = "/data/adb/hf_cacerts"
                session.execute(
                    "rm -rf '$module' '$staging' 2>/dev/null; " +
                        "echo MODULE_CLEANED",
                    timeoutSeconds = 4
                )
                UninstallResult.Success(removedFrom)
            }
            else -> {
                // 即时挂载可能未装, 但模块未清 - 直接清模块状态, 然后告诉用户
                val module = "/data/adb/modules/hf_system_cacerts"
                val staging = "/data/adb/hf_cacerts"
                val anyExist = session.execute(
                    "if [ -d '$module' ] || [ -d '$staging' ]; then echo EXISTS; else echo NONE; fi",
                    timeoutSeconds = 3
                ).output.trim()
                if (anyExist.contains("EXISTS")) {
                    session.execute(
                        "rm -rf '$module' '$staging' 2>/dev/null; echo MODULE_CLEANED",
                        timeoutSeconds = 4
                    )
                    UninstallResult.Success(listOf("module"))
                } else {
                    UninstallResult.Failure("未找到已安装的证书（可能已被重启清除）")
                }
            }
        }
    }

    fun listSystemCerts(): List<SystemCertInfo> {
        val result = mutableListOf<SystemCertInfo>()
        val session = SuSession.getInstance()

        listOf(SYSTEM_CACERTS_DIR, CONSCRYPT_CACERTS_DIR).forEach { dir ->
            val lsResult = session.execute("ls -la '$dir'/*.0 2>/dev/null || echo EMPTY", timeoutSeconds = 3)
            if (!lsResult.output.contains("EMPTY")) {
                lsResult.output.lines().forEach { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 9 && parts.last().endsWith(".0")) {
                        result.add(SystemCertInfo(
                            hash = parts.last().removeSuffix(".0"),
                            path = "$dir/${parts.last()}",
                            permissions = parts[0]
                        ))
                    }
                }
            }
        }
        return result
    }

    /**
     * 检查当前安装状态
     */
    fun checkCurrentInstallStatus(): CertInstallStatus {
        val cert = CertificateAuthorityManager.getPublicCertificateX509(context)
            ?: return CertInstallStatus.NOT_GENERATED

        val hash = computeCertHash(cert)
        val certFileName = "$hash.0"
        val session = SuSession.getInstance()

        // 检查系统目录
        val systemResult = session.execute(
            "test -f '$SYSTEM_CACERTS_DIR/$certFileName' && echo OK || echo FAIL",
            timeoutSeconds = 2
        )
        if (systemResult.output.contains("OK")) {
            return CertInstallStatus.INSTALLED("$SYSTEM_CACERTS_DIR/$certFileName")
        }

        // 检查 conscrypt 目录
        val conscryptResult = session.execute(
            "test -f '$CONSCRYPT_CACERTS_DIR/$certFileName' && echo OK || echo FAIL",
            timeoutSeconds = 2
        )
        if (conscryptResult.output.contains("OK")) {
            return CertInstallStatus.INSTALLED("$CONSCRYPT_CACERTS_DIR/$certFileName")
        }

        return CertInstallStatus.NOT_INSTALLED
    }

    sealed class CertInstallStatus {
        data object NOT_GENERATED : CertInstallStatus()
        data object NOT_INSTALLED : CertInstallStatus()
        data class INSTALLED(val location: String) : CertInstallStatus()
    }

    private fun computeCertHash(cert: X509Certificate): String {
        val subjectDer = cert.subjectX500Principal.encoded
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(subjectDer)
        val littleEndian = ((digest[3].toInt() and 0xff) shl 24) or
            ((digest[2].toInt() and 0xff) shl 16) or
            ((digest[1].toInt() and 0xff) shl 8) or
            (digest[0].toInt() and 0xff)
        return String.format("%08x", littleEndian)
    }
}
