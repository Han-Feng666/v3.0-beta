package com.HanFeng.security

import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object TlsMitmContextFactory {
    private const val KEYSTORE_TYPE = "PKCS12"
    private const val TLS_PROTOCOL = "TLS"
    private const val PASSWORD = "hanfeng_https_mitm"

    fun createServerContext(pkcs12Path: String): Result<PreparedTlsContext> {
        return runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            FileInputStream(pkcs12Path).use { input ->
                keyStore.load(input, PASSWORD.toCharArray())
            }
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, PASSWORD.toCharArray())
            val sslContext = SSLContext.getInstance(TLS_PROTOCOL)
            sslContext.init(keyManagerFactory.keyManagers, null, null)
            PreparedTlsContext(
                pkcs12Path = pkcs12Path,
                protocol = sslContext.protocol,
                sslContext = sslContext
            )
        }
    }

    data class PreparedTlsContext(
        val pkcs12Path: String,
        val protocol: String,
        val sslContext: SSLContext
    )
}
