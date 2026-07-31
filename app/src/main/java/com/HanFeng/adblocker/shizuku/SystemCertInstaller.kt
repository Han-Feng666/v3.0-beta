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
                "cp -f '$tmpPath' '$targetPath' && chmod 644 '$targetPath' && sync && " +
                "test -f '$targetPath' && echo OK || echo FAIL",
            timeoutSeconds = 6
        )
        diagnostics["${dirName}_remount_cp"] = remountResult.output.trim().take(500)
        if (remountResult.output.contains("OK")) {
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
            append("  echo OK; ")
            append("else echo FAIL; fi")
        }
        val overlayResult = session.execute(overlayCmd, timeoutSeconds = 10)
        diagnostics["${dirName}_overlay_bind"] = overlayResult.output.trim().take(500)
        if (overlayResult.output.contains("OK")) {
            // 注意：staging 目录不要立即 rm — bind mount 持有的源对象必须保留
            return DirInstallResult(success = true, persistent = false)
        }

        // (c) 单证书 bind mount：兜底，前两条都失败的最后尝试，与 (b) 一样也 nsenter 到 zygote 各进程
        val singleBindCmd = buildString {
            append("mount --bind '$tmpPath' '$targetPath' 2>&1 | head -1; ")
            append("for pid in 1 \$(pgrep zygote) \$(pgrep zygote64); do ")
            append("  [ -d /proc/\$pid/ns/mnt ] && nsenter --mount=/proc/\$pid/ns/mnt -- mount --bind '$tmpPath' '$targetPath' 2>/dev/null; ")
            append("done; ")
            append("test -f '$targetPath' && echo OK || echo FAIL")
        }
        val bindResult = session.execute(singleBindCmd, timeoutSeconds = 6)
        diagnostics["${dirName}_single_bind"] = bindResult.output.trim().take(500)
        return if (bindResult.output.contains("OK")) {
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
            removedFrom.isNotEmpty() -> UninstallResult.Success(removedFrom)
            else -> UninstallResult.Failure("未找到已安装的证书（可能已被重启清除）")
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
