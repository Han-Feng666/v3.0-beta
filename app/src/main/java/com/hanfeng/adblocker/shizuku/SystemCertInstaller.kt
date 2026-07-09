package com.hanfeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import com.HanFeng.security.CertificateAuthorityManager
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.io.File

class SystemCertInstaller(private val context: Context) {

    companion object {
        private const val TAG = "SystemCertInstaller"
        private const val SYSTEM_CACERTS_DIR = "/system/etc/security/cacerts"
        private const val CONSCRYPT_CACERTS_DIR = "/apex/com.android.conscrypt/cacerts"
        private const val MAGISK_MODULE_BASE = "/data/adb/modules/hf_cert"

        fun isRootAvailable(): Boolean {
            return SuSession.getInstance().open(timeoutSeconds = 30)
        }
    }

    sealed class InstallResult {
        data class Success(val method: String, val hashName: String, val needsReboot: Boolean) : InstallResult()
        data class Failure(val reason: String, val triedMethods: List<String>) : InstallResult()
    }

    fun installToSystem(): InstallResult {
        val cert = CertificateAuthorityManager.getPublicCertificateX509(context)
            ?: return InstallResult.Failure("CA certificate not found. Please generate it first.", emptyList())
        val certBytes = CertificateAuthorityManager.getPublicCertificateBytes(context)
            ?: return InstallResult.Failure("CA certificate bytes not found.", emptyList())

        val hash = computeCertHash(cert)
        val certFileName = "$hash.0"
        val triedMethods = mutableListOf<String>()

        val certDir = File(context.cacheDir, "hf_cert")
        certDir.mkdirs()
        val tmpCertFile = File(certDir, certFileName)
        runCatching {
            tmpCertFile.writeBytes(certBytes)
            tmpCertFile.setReadable(true, false)
        }.onFailure {
            return InstallResult.Failure("Failed to write temporary cert: ${it.message}", triedMethods)
        }

        val tmpRootPath = "/data/local/tmp/$certFileName"
        val cpResult = runRootShell("cp '${tmpCertFile.absolutePath}' '$tmpRootPath' && chmod 644 '$tmpRootPath' && test -f '$tmpRootPath' && echo COPY_OK || echo COPY_FAIL")
        val certSrcPath = if (cpResult.output.trim().contains("COPY_OK")) tmpRootPath else tmpCertFile.absolutePath

        triedMethods.add("direct_remount")
        if (installViaDirectRemount(certSrcPath, certFileName)) {
            val needsReboot = !promptConscryptRefresh()
            runRootShell("rm -f '$tmpRootPath' 2>/dev/null")
            return InstallResult.Success("direct_remount", certFileName, needsReboot)
        }

        triedMethods.add("magisk_module")
        if (installViaMagiskModule(certSrcPath, certFileName)) {
            runRootShell("rm -f '$tmpRootPath' 2>/dev/null")
            return InstallResult.Success("magisk_module", certFileName, true)
        }

        triedMethods.add("bind_mount")
        if (installViaBindMount(certSrcPath, certFileName)) {
            val needsReboot = !promptConscryptRefresh()
            runRootShell("rm -f '$tmpRootPath' 2>/dev/null")
            return InstallResult.Success("bind_mount", certFileName, needsReboot)
        }

        triedMethods.add("conscrypt_direct")
        if (installToConscrypt(certSrcPath, certFileName)) {
            runRootShell("rm -f '$tmpRootPath' 2>/dev/null")
            return InstallResult.Success("conscrypt_direct", certFileName, false)
        }

        runRootShell("rm -f '$tmpRootPath' 2>/dev/null")
        tmpCertFile.delete()
        return InstallResult.Failure(
            "所有安装方式均失败。设备可能锁定了系统分区，或 Magisk/KernelSU 模块未安装。",
            triedMethods
        )
    }

    private fun installViaDirectRemount(certSrcPath: String, certFileName: String): Boolean {
        return try {
            val targetPath = "$SYSTEM_CACERTS_DIR/$certFileName"
            runRootShell("mount -o remount,rw /system 2>/dev/null || mount -o rw,remount /system 2>/dev/null || true")
            runRootShell("mount -o remount,rw / 2>/dev/null || true")
            val copyResult = runRootShell("cp '$certSrcPath' '$targetPath' && chmod 644 '$targetPath' && chcon u:object_r:system_file:s0 '$targetPath' 2>/dev/null ; test -f '$targetPath' && echo VERIFY_OK || echo VERIFY_FAIL")
            runRootShell("mount -o remount,ro /system 2>/dev/null || mount -o ro,remount /system 2>/dev/null || true")
            val success = copyResult.output.contains("VERIFY_OK")
            if (success) Log.d(TAG, "Direct remount install succeeded")
            else Log.w(TAG, "Direct remount verification failed: output=${copyResult.output}, exitCode=${copyResult.exitCode}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Direct remount failed: ${e.message}")
            false
        }
    }

    private fun installViaMagiskModule(certSrcPath: String, certFileName: String): Boolean {
        return try {
            val moduleCertDir = "$MAGISK_MODULE_BASE/system/etc/security/cacerts"
            val targetPath = "$moduleCertDir/$certFileName"
            runRootShell("mkdir -p '$moduleCertDir'")
            val cpResult = runRootShell("cp '$certSrcPath' '$targetPath' && chmod 644 '$targetPath' ; test -f '$targetPath' && echo VERIFY_OK || echo VERIFY_FAIL")
            if (!cpResult.output.contains("VERIFY_OK")) {
                Log.w(TAG, "Magisk module copy failed: output=${cpResult.output}")
                return false
            }
            runRootShell("echo 'HanFeng CA Certificate' > '$MAGISK_MODULE_BASE/module.prop'")
            runRootShell("echo 'id=hf_cert' >> '$MAGISK_MODULE_BASE/module.prop'")
            runRootShell("echo 'name=HanFeng CA Certificate' >> '$MAGISK_MODULE_BASE/module.prop'")
            runRootShell("echo 'version=v1' >> '$MAGISK_MODULE_BASE/module.prop'")
            runRootShell("echo 'versionCode=1' >> '$MAGISK_MODULE_BASE/module.prop'")
            runRootShell("echo 'author=HanFeng' >> '$MAGISK_MODULE_BASE/module.prop'")
            runRootShell("echo 'description=System CA certificate for HTTPS filtering' >> '$MAGISK_MODULE_BASE/module.prop'")
            Log.d(TAG, "Magisk module install succeeded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Magisk module install failed: ${e.message}")
            false
        }
    }

    private fun installViaBindMount(certSrcPath: String, certFileName: String): Boolean {
        return try {
            val targetPath = "$SYSTEM_CACERTS_DIR/$certFileName"
            val mountResult = runRootShell("mount --bind '$certSrcPath' '$targetPath' 2>/dev/null && chmod 644 '$targetPath' && echo MOUNT_OK || echo MOUNT_FAIL")
            val success = mountResult.output.contains("MOUNT_OK")
            if (success) Log.d(TAG, "Bind mount install succeeded")
            else Log.w(TAG, "Bind mount verification failed: output=${mountResult.output}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Bind mount install failed: ${e.message}")
            false
        }
    }

    private fun installToConscrypt(certSrcPath: String, certFileName: String): Boolean {
        return try {
            val conscryptDir = File(CONSCRYPT_CACERTS_DIR)
            if (!conscryptDir.exists() || !conscryptDir.canWrite()) {
                runRootShell("mount -o remount,rw /apex/com.android.conscrypt 2>/dev/null || true")
            }
            val targetPath = "$CONSCRYPT_CACERTS_DIR/$certFileName"
            val cmds = arrayOf(
                "cp '$certSrcPath' '$targetPath' 2>/dev/null",
                "chmod 644 '$targetPath' 2>/dev/null",
                "chown system:system '$targetPath' 2>/dev/null",
                "chcon u:object_r:system_file:s0 '$targetPath' 2>/dev/null || true",
                "test -f '$targetPath' && echo VERIFY_OK || echo VERIFY_FAIL"
            )
            val result = runRootShell(cmds.joinToString(" && "))
            val success = result.output.contains("VERIFY_OK")
            if (success) {
                promptConscryptRefresh()
                Log.d(TAG, "Conscrypt direct install succeeded")
            } else {
                Log.w(TAG, "Conscrypt install failed: output=${result.output}, exitCode=${result.exitCode}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Conscrypt install failed: ${e.message}")
            false
        }
    }

    private fun promptConscryptRefresh(): Boolean {
        return try {
            val cmds = arrayOf(
                "setprop ctl.restart keystore 2>/dev/null || true",
                "killall -HUP system_server 2>/dev/null || true"
            )
            val result = runRootShell(cmds.joinToString(" ; "))
            result.exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    fun isSystemPartitionWritable(): Boolean {
        val result = runRootShell("touch /system/etc/security/cacerts/.hf_test 2>/dev/null && rm /system/etc/security/cacerts/.hf_test 2>/dev/null && echo OK || echo FAIL")
        return result.output.trim() == "OK"
    }

    fun isMagiskInstalled(): Boolean {
        val result = runRootShell("test -d /data/adb/modules && echo OK || echo FAIL")
        return result.output.trim() == "OK"
    }

    fun checkCurrentInstallStatus(): CertInstallStatus {
        val cert = CertificateAuthorityManager.getPublicCertificateX509(context)
            ?: return CertInstallStatus.NOT_GENERATED
        val hash = computeCertHash(cert)
        val certFileName = "$hash.0"

        val systemResult = runRootShell("test -f '$SYSTEM_CACERTS_DIR/$certFileName' && echo OK || echo FAIL")
        if (systemResult.output.trim() == "OK") return CertInstallStatus.INSTALLED("$SYSTEM_CACERTS_DIR/$certFileName")

        val magiskResult = runRootShell("test -f '$MAGISK_MODULE_BASE/system/etc/security/cacerts/$certFileName' && echo OK || echo FAIL")
        if (magiskResult.output.trim() == "OK") return CertInstallStatus.INSTALLED("$MAGISK_MODULE_BASE/system/etc/security/cacerts/$certFileName")

        val conscryptResult = runRootShell("test -f '$CONSCRYPT_CACERTS_DIR/$certFileName' && echo OK || echo FAIL")
        if (conscryptResult.output.trim() == "OK") return CertInstallStatus.INSTALLED("$CONSCRYPT_CACERTS_DIR/$certFileName")

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

    private fun runRootShell(command: String): ShellResult {
        val result = SuSession.getInstance().execute(command)
        return ShellResult(result.exitCode, result.output)
    }

    private data class ShellResult(val exitCode: Int, val output: String)
}
