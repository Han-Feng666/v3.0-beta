package com.HanFeng.adblocker.shizuku

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
        private const val APEX_CACERTS_DIR = "/apex/com.android.conscrypt/cacerts"

        fun isRootAvailable(): Boolean {
            return SuSession.getInstance().open(timeoutSeconds = 30)
        }
    }

    sealed class InstallResult {
        data class Success(val method: String, val hashName: String, val needsReboot: Boolean) : InstallResult()
        data class Failure(val reason: String, val triedMethods: List<String>) : InstallResult()
    }

    sealed class UninstallResult {
        data class Success(val removedFrom: List<String>) : UninstallResult()
        data class Partial(val removedFrom: List<String>, val failedAt: List<String>) : UninstallResult()
        data class Failure(val reason: String) : UninstallResult()
    }

    data class SystemCertInfo(val hash: String, val path: String, val owner: String, val permissions: String)

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
        val session = SuSession.getInstance()
        session.copyFile(tmpCertFile.absolutePath, tmpRootPath)
        val certSrcPath = if (session.fileExists(tmpRootPath)) tmpRootPath else tmpCertFile.absolutePath

        val isErofs = detectErofs()
        val isSystemWritable = isErofs || isDmVerityEnabled()
        val sdkInt = runCatching {
            runRootShell("getprop ro.build.version.sdk").output.trim().toIntOrNull()
        }.getOrNull() ?: android.os.Build.VERSION.SDK_INT
        val isAndroid14Plus = sdkInt >= 34

        // Android 14+: 系统从 /apex/com.android.conscrypt/cacerts 读 CA，先走 conscrypt_direct
        if (isAndroid14Plus) {
            triedMethods.add("conscrypt_direct")
            if (installToConscrypt(certSrcPath, certFileName)) {
                session.deleteFile(tmpRootPath)
                return InstallResult.Success("conscrypt_direct", certFileName, false)
            }
        }

        triedMethods.add("direct_remount")
        if (installViaDirectRemount(certSrcPath, certFileName)) {
            val needsReboot = !promptConscryptRefresh()
            session.deleteFile(tmpRootPath)
            return InstallResult.Success("direct_remount", certFileName, needsReboot)
        }

        triedMethods.add("magisk_module")
        if (installViaMagiskModule(certSrcPath, certFileName)) {
            session.deleteFile(tmpRootPath)
            return InstallResult.Success("magisk_module", certFileName, true)
        }

        triedMethods.add("bind_mount")
        if (installViaBindMount(certSrcPath, certFileName)) {
            val needsReboot = !promptConscryptRefresh()
            session.deleteFile(tmpRootPath)
            return InstallResult.Success("bind_mount", certFileName, needsReboot)
        }

        // Android14+ 兜底再试 conscrypt_direct（前面直接 remount/bind_mount 均失败后）
        if (!isAndroid14Plus) {
            triedMethods.add("conscrypt_direct")
            if (installToConscrypt(certSrcPath, certFileName)) {
                session.deleteFile(tmpRootPath)
                return InstallResult.Success("conscrypt_direct", certFileName, false)
            }
        }

        session.deleteFile(tmpRootPath)
        tmpCertFile.delete()
        val diagnosticInfo = buildString {
            append("系统分区可写: $isSystemWritable, ")
            append("EROFS: $isErofs, ")
            append("Magisk: ${isMagiskInstalled()}")
        }
        return InstallResult.Failure(
            "所有安装方式均失败。$diagnosticInfo",
            triedMethods
        )
    }

    fun uninstallFromSystem(): UninstallResult {
        val cert = CertificateAuthorityManager.getPublicCertificateX509(context)
            ?: return UninstallResult.Failure("CA certificate not found")
        val hash = computeCertHash(cert)
        val certFileName = "$hash.0"
        val removedFrom = mutableListOf<String>()
        val failedAt = mutableListOf<String>()

        val systemPath = "$SYSTEM_CACERTS_DIR/$certFileName"
        if (runRootShell("test -f '$systemPath' && echo EXISTS || echo NOT_FOUND").output.contains("EXISTS")) {
            if (runRootShell("mount -o remount,rw /system 2>/dev/null ; rm -f '$systemPath' ; mount -o remount,ro /system 2>/dev/null ; test -f '$systemPath' && echo STILL_EXISTS || echo REMOVED").output.contains("REMOVED")) {
                removedFrom.add("system")
            } else {
                failedAt.add("system")
            }
        }

        val magiskPath = "$MAGISK_MODULE_BASE/system/etc/security/cacerts/$certFileName"
        if (runRootShell("test -f '$magiskPath' && echo EXISTS || echo NOT_FOUND").output.contains("EXISTS")) {
            if (runRootShell("rm -f '$magiskPath' ; rm -rf '$MAGISK_MODULE_BASE' 2>/dev/null ; test -d '$MAGISK_MODULE_BASE' && echo STILL_EXISTS || echo REMOVED").output.contains("REMOVED")) {
                removedFrom.add("magisk_module")
            } else {
                failedAt.add("magisk_module")
            }
        }

        val conscryptPath = "$CONSCRYPT_CACERTS_DIR/$certFileName"
        if (runRootShell("test -f '$conscryptPath' && echo EXISTS || echo NOT_FOUND").output.contains("EXISTS")) {
            if (runRootShell("mount -o remount,rw /apex/com.android.conscrypt 2>/dev/null ; rm -f '$conscryptPath' ; test -f '$conscryptPath' && echo STILL_EXISTS || echo REMOVED").output.contains("REMOVED")) {
                removedFrom.add("conscrypt")
            } else {
                failedAt.add("conscrypt")
            }
        }

        val bindMounted = runRootShell("mount | grep '$certFileName' 2>/dev/null").output
        if (bindMounted.isNotBlank()) {
            if (runRootShell("umount '$systemPath' 2>/dev/null ; umount -l '$systemPath' 2>/dev/null ; test -f '$systemPath' && echo STILL_EXISTS || echo UNMOUNTED").output.contains("UNMOUNTED")) {
                removedFrom.add("bind_mount")
            } else {
                failedAt.add("bind_mount")
            }
        }

        promptConscryptRefresh()

        return when {
            removedFrom.isNotEmpty() && failedAt.isEmpty() -> UninstallResult.Success(removedFrom)
            removedFrom.isNotEmpty() -> UninstallResult.Partial(removedFrom, failedAt)
            else -> UninstallResult.Failure("未找到已安装的证书")
        }
    }

    fun listSystemCerts(): List<SystemCertInfo> {
        val result = mutableListOf<SystemCertInfo>()
        listOf(SYSTEM_CACERTS_DIR, CONSCRYPT_CACERTS_DIR, APEX_CACERTS_DIR).forEach { dir ->
            val lsResult = runRootShell("ls -la '$dir'/*.0 2>/dev/null || echo EMPTY")
            if (!lsResult.output.contains("EMPTY")) {
                lsResult.output.lines().forEach { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 9 && parts.last().endsWith(".0")) {
                        result.add(SystemCertInfo(
                            hash = parts.last().removeSuffix(".0"),
                            path = "$dir/${parts.last()}",
                            owner = "${parts[2]}:${parts[3]}",
                            permissions = parts[0]
                        ))
                    }
                }
            }
        }
        return result
    }

    fun detectErofs(): Boolean {
        val result = runRootShell("mount | grep ' / ' | grep erofs && echo EROFS || echo NOT_EROFS")
        return result.output.contains("EROFS")
    }

    fun detectDmVerity(): Boolean {
        val result = runRootShell("getprop ro.boot.veritymode 2>/dev/null || getprop ro.boot.verifiedbootstate 2>/dev/null || echo unknown")
        val output = result.output.lowercase()
        return output.contains("enforcing") || output.contains("green")
    }

    fun getSystemInfo(): String {
        return buildString {
            append("EROFS: ${detectErofs()}\n")
            append("DM-Verity: ${detectDmVerity()}\n")
            append("Magisk: ${isMagiskInstalled()}\n")
            append("System writable: ${isSystemPartitionWritable()}\n")
            append("Root solution: ${SuSession.getInstance().rootSolution.name}\n")
            append("Root version: ${SuSession.getInstance().rootVersion}\n")
            append("Certs count: ${listSystemCerts().size}")
        }
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
            else Log.w(TAG, "Direct remount verification failed: output=${copyResult.output}")
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
                Log.w(TAG, "Magisk module copy failed")
                return false
            }
            runRootShell("cat > '$MAGISK_MODULE_BASE/module.prop' << 'EOF'\nid=hf_cert\nname=HanFeng CA Certificate\nversion=v1\nversionCode=1\nauthor=HanFeng\ndescription=System CA certificate for HTTPS filtering\nEOF")
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
            else Log.w(TAG, "Bind mount verification failed")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Bind mount install failed: ${e.message}")
            false
        }
    }

    private fun installToConscrypt(certSrcPath: String, certFileName: String): Boolean {
        return try {
            val targetPath = "$CONSCRYPT_CACERTS_DIR/$certFileName"

            // 方案A：APEX 分区可写则直接 cp
            runRootShell("mount -o remount,rw /apex/com.android.conscrypt 2>/dev/null || true")
            val directCmds = "cp '$certSrcPath' '$targetPath' 2>/dev/null && chmod 644 '$targetPath' 2>/dev/null && chcon u:object_r:system_file:s0 '$targetPath' 2>/dev/null && test -f '$targetPath' && echo VERIFY_OK || echo VERIFY_FAIL"
            val directResult = runRootShell(directCmds)
            if (directResult.output.contains("VERIFY_OK")) {
                // 用 nsenter 把修改传播到所有已运行进程（Android14+ 关键步骤）
                propagateConscryptAwareAcrossNamespaces(targetPath)
                promptConscryptRefresh()
                Log.d(TAG, "Conscrypt direct install succeeded")
                return true
            }

            // 方案B：bind mount 方式（APEX 不可写时）
            // 1. 临时复制整套 cacerts 到 /data/local/tmp/hf_conscrypt/
            val tmpDir = "/data/local/tmp/hf_conscrypt"
            val stageCmds = "rm -rf '$tmpDir' 2>/dev/null ; mkdir -p '$tmpDir' && cp -a '$CONSCRYPT_CACERTS_DIR/.' '$tmpDir/' 2>/dev/null && cp '$certSrcPath' '$tmpDir/$certFileName' && chmod 644 '$tmpDir/$certFileName' && chcon u:object_r:system_file:s0 '$tmpDir/$certFileName' 2>/dev/null && test -f '$tmpDir/$certFileName' && echo STAGE_OK || echo STAGE_FAIL"
            val stageResult = runRootShell(stageCmds)
            if (!stageResult.output.contains("STAGE_OK")) {
                Log.w(TAG, "Conscrypt staging failed: ${stageResult.output}")
                return false
            }

            // 2. 用 bind mount 把 tmpDir 覆盖到 /apex/com.android.conscrypt/cacerts
            val bindCmds = "mount --bind '$tmpDir' '$CONSCRYPT_CACERTS_DIR' 2>/dev/null && test -f '$targetPath' && echo BIND_OK || echo BIND_FAIL"
            val bindResult = runRootShell(bindCmds)
            if (!bindResult.output.contains("BIND_OK")) {
                Log.w(TAG, "Conscrypt bind mount failed: ${bindResult.output}")
                return false
            }

            // 3. 对所有已运行进程的命名空间同步 bind mount（Android14+ 必需）
            propagateConscryptAwareAcrossNamespaces(targetPath)
            promptConscryptRefresh()
            Log.d(TAG, "Conscrypt bind-mount install succeeded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Conscrypt install failed: ${e.message}")
            false
        }
    }

    private fun propagateConscryptAwareAcrossNamespaces(targetPath: String) {
        runCatching {
            val tmpDir = "/data/local/tmp/hf_conscrypt"
            val result = runRootShell("for PID in \$(ls /proc | grep -E '^[0-9]+\$'); do " +
                "nsenter -t \$PID -m -- mount --bind '$tmpDir' '$CONSCRYPT_CACERTS_DIR' 2>/dev/null; " +
                "done >/dev/null 2>&1; echo PROPAGATE_DONE")
            Log.d(TAG, "Conscrypt namespace propagation: ${result.output.trim()}")
        }
    }

    private fun promptConscryptRefresh(): Boolean {
        return try {
            runRootShell("setprop ctl.restart keystore 2>/dev/null || true ; killall -HUP system_server 2>/dev/null || true").exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    fun isSystemPartitionWritable(): Boolean {
        val result = runRootShell("touch /system/etc/security/cacerts/.hf_test 2>/dev/null && rm /system/etc/security/cacerts/.hf_test 2>/dev/null && echo OK || echo FAIL")
        return result.output.trim() == "OK"
    }

    fun isDmVerityEnabled(): Boolean = detectDmVerity()

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

    private fun runRootShell(command: String): SuSession.ShellResult {
        return SuSession.getInstance().execute(command)
    }
}
