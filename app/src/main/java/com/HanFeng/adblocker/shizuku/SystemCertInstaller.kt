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
        data class Success(val method: String, val hashName: String, val persistent: Boolean) : InstallResult()
        data class Failure(val reason: String, val triedMethods: List<String>) : InstallResult()
    }

    sealed class UninstallResult {
        data class Success(val removedFrom: List<String>) : UninstallResult()
        data class Failure(val reason: String) : UninstallResult()
    }

    data class SystemCertInfo(val hash: String, val path: String, val permissions: String)

    /**
     * 安装证书到系统 CA 目录
     * 流程：
     * 1. 写入证书到 /data/local/tmp
     * 2. bind mount 到系统目录（当前会话生效）
     * 3. 如果系统分区可写，同时复制到系统目录（重启后仍生效）
     */
    fun installToSystem(): InstallResult {
        val cert = CertificateAuthorityManager.getPublicCertificateX509(context)
            ?: return InstallResult.Failure("CA 证书未生成，请先在 MITM 设置中生成证书", emptyList())
        val certBytes = CertificateAuthorityManager.getPublicCertificateBytes(context)
            ?: return InstallResult.Failure("证书数据为空", emptyList())

        val hash = computeCertHash(cert)
        val certFileName = "$hash.0"
        val triedMethods = mutableListOf<String>()
        val session = SuSession.getInstance()

        // 写入证书到 /data/local/tmp
        val tmpPath = "/data/local/tmp/hf_cert_$certFileName"
        val writeResult = session.execute(
            "printf '%b' '${certBytesToHex(certBytes)}' | xxd -r -p > '$tmpPath' && " +
            "chmod 644 '$tmpPath' && test -f '$tmpPath' && echo OK || echo FAIL",
            timeoutSeconds = 5
        )
        if (!writeResult.output.contains("OK")) {
            return InstallResult.Failure("证书写入临时目录失败", triedMethods)
        }

        // 方案1：bind mount（当前会话立即生效）
        triedMethods.add("bind_mount")
        val bindSuccess = bindMountCert(tmpPath, certFileName)

        // 方案2：直接复制到系统分区（重启后仍生效）
        triedMethods.add("system_copy")
        val copySuccess = copyToSystemPartition(tmpPath, certFileName)

        // 同时安装到 Android 14+ 的 conscrypt 目录
        if (bindSuccess || copySuccess) {
            triedMethods.add("conscrypt")
            installToConscrypt(tmpPath, certFileName)
        }

        // 清理临时文件
        session.execute("rm -f '$tmpPath'", timeoutSeconds = 2)

        return when {
            bindSuccess || copySuccess -> {
                val method = when {
                    bindSuccess && copySuccess -> "bind_mount+system_copy"
                    bindSuccess -> "bind_mount"
                    else -> "system_copy"
                }
                InstallResult.Success(method, certFileName, copySuccess)
            }
            else -> InstallResult.Failure(
                "所有安装方式均失败。请确认设备已 Root 且系统分区可写。",
                triedMethods
            )
        }
    }

    /**
     * Bind mount 方式安装（当前会话生效）
     */
    private fun bindMountCert(certSrcPath: String, certFileName: String): Boolean {
        return try {
            val session = SuSession.getInstance()
            val targetPath = "$SYSTEM_CACERTS_DIR/$certFileName"

            val result = session.execute(
                "mount --bind '$certSrcPath' '$targetPath' 2>/dev/null && " +
                "test -f '$targetPath' && echo OK || echo FAIL",
                timeoutSeconds = 3
            )
            val success = result.output.contains("OK")
            if (success) Log.d(TAG, "Bind mount succeeded")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Bind mount failed: ${e.message}")
            false
        }
    }

    /**
     * 直接复制到系统分区（重启后仍生效）
     */
    private fun copyToSystemPartition(certSrcPath: String, certFileName: String): Boolean {
        return try {
            val session = SuSession.getInstance()
            val targetPath = "$SYSTEM_CACERTS_DIR/$certFileName"

            val result = session.execute(
                "mount -o remount,rw /system 2>/dev/null || true && " +
                "cp '$certSrcPath' '$targetPath' && " +
                "chmod 644 '$targetPath' && " +
                "mount -o remount,ro /system 2>/dev/null || true && " +
                "test -f '$targetPath' && echo OK || echo FAIL",
                timeoutSeconds = 5
            )
            val success = result.output.contains("OK")
            if (success) Log.d(TAG, "System copy succeeded")
            success
        } catch (e: Exception) {
            Log.e(TAG, "System copy failed: ${e.message}")
            false
        }
    }

    /**
     * 安装到 Android 14+ 的 conscrypt 目录
     */
    private fun installToConscrypt(certSrcPath: String, certFileName: String): Boolean {
        return try {
            val session = SuSession.getInstance()
            val targetPath = "$CONSCRYPT_CACERTS_DIR/$certFileName"

            val result = session.execute(
                "mount -o remount,rw /apex/com.android.conscrypt 2>/dev/null || true && " +
                "cp '$certSrcPath' '$targetPath' 2>/dev/null && " +
                "chmod 644 '$targetPath' 2>/dev/null && " +
                "mount -o remount,ro /apex/com.android.conscrypt 2>/dev/null || true && " +
                "test -f '$targetPath' && echo OK || echo FAIL",
                timeoutSeconds = 5
            )
            val success = result.output.contains("OK")
            if (success) Log.d(TAG, "Conscrypt install succeeded")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Conscrypt install failed: ${e.message}")
            false
        }
    }

    /**
     * 卸载系统证书
     */
    fun uninstallFromSystem(): UninstallResult {
        val cert = CertificateAuthorityManager.getPublicCertificateX509(context)
            ?: return UninstallResult.Failure("CA 证书未找到")
        val hash = computeCertHash(cert)
        val certFileName = "$hash.0"
        val removedFrom = mutableListOf<String>()
        val session = SuSession.getInstance()

        // 解除 bind mount
        val systemPath = "$SYSTEM_CACERTS_DIR/$certFileName"
        session.execute("umount '$systemPath' 2>/dev/null; umount -l '$systemPath' 2>/dev/null", timeoutSeconds = 2)

        // 删除系统目录中的证书
        val systemResult = session.execute(
            "test -f '$systemPath' && " +
            "mount -o remount,rw /system 2>/dev/null && " +
            "rm -f '$systemPath' && " +
            "mount -o remount,ro /system 2>/dev/null && " +
            "test ! -f '$systemPath' && echo REMOVED || echo FAIL",
            timeoutSeconds = 5
        )
        if (systemResult.output.contains("REMOVED")) {
            removedFrom.add("system")
        }

        // 删除 conscrypt 目录中的证书
        val conscryptPath = "$CONSCRYPT_CACERTS_DIR/$certFileName"
        val conscryptResult = session.execute(
            "test -f '$conscryptPath' && " +
            "mount -o remount,rw /apex/com.android.conscrypt 2>/dev/null && " +
            "rm -f '$conscryptPath' && " +
            "mount -o remount,ro /apex/com.android.conscrypt 2>/dev/null && " +
            "test ! -f '$conscryptPath' && echo REMOVED || echo FAIL",
            timeoutSeconds = 5
        )
        if (conscryptResult.output.contains("REMOVED")) {
            removedFrom.add("conscrypt")
        }

        return when {
            removedFrom.isNotEmpty() -> UninstallResult.Success(removedFrom)
            else -> UninstallResult.Failure("未找到已安装的证书")
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

    private fun certBytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
