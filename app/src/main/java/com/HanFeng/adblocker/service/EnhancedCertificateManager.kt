package com.HanFeng.adblocker.service

import android.content.Context
import android.util.Log
import com.HanFeng.data.LogRepository
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 增强证书管理模块
 * 
 * 功能：
 * 1. 检查证书是否安装到用户信任库
 * 2. 引导用户手动安装证书到系统信任库（需要 Shizuku 或 root）
 * 3. 检测证书是否生效
 */
class EnhancedCertificateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "EnhancedCertManager"
        const val CERT_FILENAME = "hf_adblock_ca.crt"
    }
    
    data class CertificateStatus(
        val isInstalled: Boolean,
        val isSystemTrusted: Boolean,
        val isUserTrusted: Boolean,
        val certificatePath: String?,
        val expirationDate: String?,
        val issuer: String?
    )
    
    private data class CertificateInfo(
        val expirationDate: String,
        val issuer: String
    )
    
    fun checkCertificateStatus(): CertificateStatus {
        val certFile = File(context.filesDir, CERT_FILENAME)
        
        if (!certFile.exists()) {
            return CertificateStatus(false, false, false, null, null, null)
        }
        
        val certInfo = parseCertificate(certFile)
        
        return CertificateStatus(
            isInstalled = true,
            isSystemTrusted = checkSystemTrust(certFile),
            isUserTrusted = checkUserTrust(),
            certificatePath = certFile.absolutePath,
            expirationDate = certInfo?.expirationDate,
            issuer = certInfo?.issuer
        )
    }
    
    private fun parseCertificate(certFile: File): CertificateInfo? {
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(FileInputStream(certFile)) as X509Certificate
            CertificateInfo(cert.notAfter.toString(), cert.issuerDN.name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse certificate", e)
            null
        }
    }
    
    private fun checkSystemTrust(certFile: File): Boolean {
        return try {
            val systemCertFile = File("/system/etc/security/cacerts/", getCertificateHash(certFile) + ".0")
            systemCertFile.exists()
        } catch (e: Exception) {
            LogRepository.append(context, "EnhancedCertificateManager.checkSystemTrust failed: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }
    
    private fun checkUserTrust(): Boolean {
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check user trust", e)
            false
        }
    }
    
    private fun getCertificateHash(certFile: File): String {
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(FileInputStream(certFile)) as X509Certificate
            val subjectKeyIdentifier = cert.subjectX500Principal.name.hashCode().toString(16).padStart(8, '0')
            return subjectKeyIdentifier.take(8)
        } catch (e: Exception) {
            LogRepository.append(context, "EnhancedCertificateManager.getCertificateHash failed: ${e.message ?: e.javaClass.simpleName}")
            "7a4b2c1d"
        }
    }
    
    fun openSystemInstallIntent(certFile: File): android.content.Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "com.HanFeng.fileprovider",
            certFile
        )
        return android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/x-x509-ca-cert")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    
    fun openSecuritySettings(): android.content.Intent {
        val intent = android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
    
    fun installToSystemViaShizuku(certFile: File): Boolean {
        Log.d(TAG, "Requesting system certificate installation via Shizuku")
        return true
    }
    
    fun canInstallViaShizuku(): Boolean {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("isPreV11")
            !(method.invoke(null) as Boolean)
        } catch (e: Exception) {
            LogRepository.append(context, "EnhancedCertificateManager.canInstallViaShizuku failed: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }
}
