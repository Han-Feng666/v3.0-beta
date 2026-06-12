package com.HanFeng.security

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.HanFeng.data.HttpsMitmRepository
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

object CertificateAuthorityManager {
    private const val KEYSTORE_TYPE = "PKCS12"
    private const val CERT_ALIAS = "hanfeng_mitm_ca"
    private const val CERT_PASSWORD = "hanfeng_https_mitm"
    private const val CERT_DIR = "certs"
    private const val CERT_FILE_NAME = "HanFeng.p12"
    private const val CERT_PUBLIC_FILE_NAME = "HanFeng.cer"
    private const val DOWNLOAD_SUBDIR = "HanFeng"
    private val legacyCertificateNames = setOf("HanFeng.cer")
    private val bcProvider by lazy(LazyThreadSafetyMode.NONE) { BouncyCastleProvider() }
    private val leafCertCache = ConcurrentHashMap<String, GeneratedLeafCertificate>(512, 0.75f, 16)

    fun ensureCaInstalledFiles(context: Context): Result<GeneratedCertificate> {
        return runCatching {
            val certDir = File(context.filesDir, CERT_DIR).apply { mkdirs() }
            val certFile = File(certDir, CERT_FILE_NAME)
            val publicCertFile = File(certDir, CERT_PUBLIC_FILE_NAME)
            val storedCertFileName = HttpsMitmRepository.getCertificateFileName(context)
            val storedKeystoreFileName = HttpsMitmRepository.getCaKeystoreFileName(context)
            val hasMatchingStoredMeta = storedCertFileName == CERT_PUBLIC_FILE_NAME && storedKeystoreFileName == CERT_FILE_NAME
            val newlyGenerated = !hasMatchingStoredMeta || !isValidCertificateFile(certFile) || !isValidCertificateFile(publicCertFile)
            if (newlyGenerated) {
                val generated = generateCaCertificate()
                storePkcs12(certFile, generated.keyPair, generated.certificate)
                storePublicCertificate(publicCertFile, generated.certificate)
            }
            HttpsMitmRepository.saveCertificateMeta(context, CERT_ALIAS, CERT_PASSWORD, CERT_PUBLIC_FILE_NAME, CERT_FILE_NAME)
            val downloadDisplayPath = if (newlyGenerated || HttpsMitmRepository.getCertificateExportPath(context).isNullOrBlank()) {
                exportCertificateToDownloads(context, publicCertFile)
            } else {
                HttpsMitmRepository.getCertificateExportPath(context)
            }
            GeneratedCertificate(
                filePath = publicCertFile.absolutePath,
                downloadDisplayPath = downloadDisplayPath,
                newlyGenerated = newlyGenerated
            )
        }
    }

    fun isCaInstalledInSystem(context: Context): Boolean {
        return runCatching {
            val certDir = File(context.filesDir, CERT_DIR)
            val publicCertFile = File(certDir, CERT_PUBLIC_FILE_NAME)
            if (!isValidCertificateFile(publicCertFile)) return false
            val expectedCertificate = FileInputStream(publicCertFile).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            }
            val androidCaStore = KeyStore.getInstance("AndroidCAStore")
            androidCaStore.load(null, null)
            val aliases = androidCaStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val installed = androidCaStore.getCertificate(alias) as? X509Certificate ?: continue
                if (certificateMatchesExpected(installed, expectedCertificate)) {
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    fun syncInstalledState(context: Context): Boolean {
        val installed = HttpsMitmRepository.isCertificateInstalled(context)
        if (installed) return true
        val actuallyInstalled = isCaInstalledInSystem(context)
        if (actuallyInstalled) {
            HttpsMitmRepository.markCertificateInstalled(context)
        }
        return actuallyInstalled
    }

    private fun certificateMatchesExpected(installed: X509Certificate, expected: X509Certificate): Boolean {
        if (installed.encoded.contentEquals(expected.encoded)) return true
        if (installed.subjectX500Principal == expected.subjectX500Principal && installed.publicKey.encoded.contentEquals(expected.publicKey.encoded)) {
            return true
        }
        if (installed.issuerX500Principal == expected.issuerX500Principal && installed.serialNumber == expected.serialNumber) {
            return true
        }
        val expectedSubject = expected.subjectX500Principal.name
        val installedSubject = installed.subjectX500Principal.name
        if (installedSubject.contains("HanFeng HTTPS MITM CA") && installed.publicKey.encoded.contentEquals(expected.publicKey.encoded)) {
            return true
        }
        if (installedSubject.contains("HanFeng HTTPS MITM CA") && expectedSubject.contains("HanFeng HTTPS MITM CA")) {
            return true
        }
        return false
    }

    private fun isValidCertificateFile(file: File): Boolean {
        return file.exists() && file.length() > 0
    }

    fun ensureLeafCertificate(context: Context, hostName: String): Result<GeneratedLeafCertificate> {
        val normalizedHost = hostName.trim().lowercase()
        require(normalizedHost.isNotBlank()) { "host is blank" }
        leafCertCache[normalizedHost]?.let { return Result.success(it) }
        return runCatching {
            val certDir = File(context.filesDir, CERT_DIR).apply { mkdirs() }
            val leafFile = File(certDir, buildLeafFileName(normalizedHost))
            if (!isValidCertificateFile(leafFile)) {
                val caBundle = runCatching { loadCaBundle(context) }
                    .getOrElse {
                        ensureCaInstalledFiles(context).getOrThrow()
                        loadCaBundle(context)
                    }
                val leafKeyPair = generateRsaKeyPair()
                val leafCertificate = generateLeafCertificate(normalizedHost, caBundle, leafKeyPair)
                storePkcs12WithChain(leafFile, buildLeafAlias(normalizedHost), leafKeyPair.private, arrayOf(leafCertificate, caBundle.certificate))
            }
            GeneratedLeafCertificate(host = normalizedHost, filePath = leafFile.absolutePath).also {
                leafCertCache[normalizedHost] = it
                while (leafCertCache.size > 256) {
                    val firstKey = leafCertCache.entries.firstOrNull()?.key ?: break
                    leafCertCache.remove(firstKey)
                }
            }
        }
    }

    private fun generateCaCertificate(): GeneratedKeyMaterial {
        val keyPair = generateRsaKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L)
        val notAfter = Date(now + 3650L * 24L * 60L * 60L * 1000L)
        val issuer = X500Name("CN=HanFeng HTTPS MITM CA, O=HanFeng, C=CN")
        val certificateBuilder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            issuer,
            keyPair.public
        )
        val extUtils = JcaX509ExtensionUtils()
        certificateBuilder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(keyPair.public))
        certificateBuilder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(keyPair.public))
        certificateBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val holder: X509CertificateHolder = certificateBuilder.build(
            JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(keyPair.private)
        )
        val certificate = JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(holder)
        certificate.verify(keyPair.public)
        return GeneratedKeyMaterial(keyPair, certificate)
    }

    private fun generateLeafCertificate(hostName: String, caBundle: CaBundle, leafKeyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L)
        val notAfter = Date(now + 90L * 24L * 60L * 60L * 1000L)
        val subject = X500Name("CN=$hostName, O=HanFeng HTTPS MITM, C=CN")
        val builder = JcaX509v3CertificateBuilder(
            X500Name(caBundle.certificate.subjectX500Principal.name),
            BigInteger.valueOf(now xor hostName.hashCode().toLong()),
            notBefore,
            notAfter,
            subject,
            leafKeyPair.public
        )
        val extUtils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(leafKeyPair.public))
        builder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(caBundle.certificate))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, hostName))
        )
        val holder = builder.build(
            JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(caBundle.privateKey)
        )
        return JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(holder)
    }

    private fun generateRsaKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        return keyPairGenerator.generateKeyPair()
    }

    private fun loadCaBundle(context: Context): CaBundle {
        val certDir = File(context.filesDir, CERT_DIR)
        val caKeystoreName = HttpsMitmRepository.getCaKeystoreFileName(context) ?: CERT_FILE_NAME
        val caKeystoreFile = File(certDir, caKeystoreName)
        check(caKeystoreFile.exists() && caKeystoreFile.length() > 0) {
            "CA keystore file missing or empty: ${caKeystoreFile.absolutePath}"
        }
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        FileInputStream(caKeystoreFile).use { input ->
            keyStore.load(input, CERT_PASSWORD.toCharArray())
        }
        val privateKey = keyStore.getKey(CERT_ALIAS, CERT_PASSWORD.toCharArray()) as? PrivateKey
            ?: error("CA keystore missing entry '$CERT_ALIAS'")
        val certificate = keyStore.getCertificate(CERT_ALIAS) as? X509Certificate
            ?: error("CA keystore certificate missing for entry '$CERT_ALIAS'")
        return CaBundle(privateKey, certificate)
    }

    private fun storePkcs12(file: File, keyPair: KeyPair, certificate: X509Certificate) {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, null)
        keyStore.setKeyEntry(CERT_ALIAS, keyPair.private, CERT_PASSWORD.toCharArray(), arrayOf(certificate))
        FileOutputStream(file).use { output ->
            keyStore.store(output, CERT_PASSWORD.toCharArray())
        }
    }

    private fun storePkcs12WithChain(file: File, alias: String, privateKey: PrivateKey, chain: Array<X509Certificate>) {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, null)
        keyStore.setKeyEntry(alias, privateKey, CERT_PASSWORD.toCharArray(), chain)
        FileOutputStream(file).use { output ->
            keyStore.store(output, CERT_PASSWORD.toCharArray())
        }
    }

    private fun storePublicCertificate(file: File, certificate: X509Certificate) {
        FileOutputStream(file).use { output ->
            output.write(certificate.encoded)
        }
    }

    private fun exportCertificateToDownloads(context: Context, sourceFile: File): String? {
        return runCatching {
            writeToDownloadSubdir(
                context = context,
                fileName = "HanFeng.crt",
                mimeType = "application/x-x509-ca-cert",
                bytes = sourceFile.readBytes(),
                cleanupFileNames = legacyCertificateNames
            )
        }.getOrNull()
    }

    fun exportBinaryFileToDownloads(context: Context, fileName: String, mimeType: String, bytes: ByteArray, cleanupFileNames: Set<String> = emptySet()): String? {
        return runCatching {
            writeToDownloadSubdir(
                context = context,
                fileName = fileName,
                mimeType = mimeType,
                bytes = bytes,
                cleanupFileNames = cleanupFileNames
            )
        }.getOrNull()
    }

    fun exportTextFileToDownloads(context: Context, fileName: String, content: String): String? {
        return exportBinaryFileToDownloads(
            context = context,
            fileName = fileName,
            mimeType = "text/plain",
            bytes = content.toByteArray()
        )
    }

    private fun writeToDownloadSubdir(context: Context, fileName: String, mimeType: String, bytes: ByteArray, cleanupFileNames: Set<String> = emptySet()): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativePath = "Download/$DOWNLOAD_SUBDIR"
            cleanupFileNames.forEach { legacyName ->
                findExistingDownloadUris(context, legacyName, relativePath).forEach { duplicateUri ->
                    runCatching { resolver.delete(duplicateUri, null, null) }
                }
            }
            val existingUris = findExistingDownloadUris(context, fileName, relativePath)
            val targetUri = existingUris.firstOrNull()
                ?: resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    android.content.ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                )
                ?: return "下载/$DOWNLOAD_SUBDIR/$fileName"
            resolver.openOutputStream(targetUri, "wt")?.use { output ->
                output.write(bytes)
            }
            runCatching {
                resolver.update(targetUri, android.content.ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            }
            existingUris.drop(1).forEach { duplicateUri ->
                runCatching { resolver.delete(duplicateUri, null, null) }
            }
            "下载/$DOWNLOAD_SUBDIR/$fileName"
        } else {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadDir, DOWNLOAD_SUBDIR).apply { mkdirs() }
            cleanupFileNames.forEach { legacyName ->
                if (legacyName != fileName) {
                    runCatching { File(targetDir, legacyName).delete() }
                }
            }
            val targetFile = File(targetDir, fileName)
            FileOutputStream(targetFile).use { output: OutputStream ->
                output.write(bytes)
            }
            targetFile.absolutePath
        }
    }

    private fun findExistingDownloadUris(context: Context, fileName: String, relativePath: String): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DATE_MODIFIED)
        val normalizedPath = if (relativePath.endsWith('/')) relativePath else "$relativePath/"
        val selection = buildString {
            append("${MediaStore.Downloads.DISPLAY_NAME}=? AND (")
            append("${MediaStore.Downloads.RELATIVE_PATH}=? OR ")
            append("${MediaStore.Downloads.RELATIVE_PATH}=?")
            append(')')
        }
        val selectionArgs = arrayOf(fileName, relativePath, normalizedPath)
        val matches = mutableListOf<Pair<Long, Long>>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val modifiedAt = cursor.getLong(1)
                matches += id to modifiedAt
            }
        }
        return matches
            .sortedByDescending { it.second }
            .map { (id, _) -> Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()) }
    }

    private fun buildLeafAlias(hostName: String): String = "leaf_${hostName.replace(Regex("[^a-z0-9._-]"), "_")}"

    private fun buildLeafFileName(hostName: String): String = "leaf_${hostName.replace(Regex("[^a-z0-9._-]"), "_")}.p12"

    private data class GeneratedKeyMaterial(
        val keyPair: KeyPair,
        val certificate: X509Certificate
    )

    private data class CaBundle(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    data class GeneratedCertificate(
        val filePath: String,
        val downloadDisplayPath: String?,
        val newlyGenerated: Boolean
    )

    data class GeneratedLeafCertificate(
        val host: String,
        val filePath: String
    )
}
