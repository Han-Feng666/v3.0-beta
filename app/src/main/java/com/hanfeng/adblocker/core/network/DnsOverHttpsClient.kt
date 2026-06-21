package com.HanFeng.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object DnsOverHttpsClient {
    val DOH_SERVERS = listOf(
        "https://dns.alidns.com/dns-query",
        "https://doh.pub/dns-query",
        "https://1.12.12.12/dns-query",
        "https://doh.360.cn/dns-query",
        "https://cloudflare-dns.com/dns-query",
    )

    data class DohResult(
        val serverUrl: String,
        val response: ByteArray
    )

    fun query(context: Context, dnsMessage: ByteArray, serverUrl: String, timeoutMs: Int = 2000): DohResult? {
        val network = selectNonVpnNetwork(context) ?: return null
        return runCatching {
            val url = URL(serverUrl)
            val connection = network.openConnection(url) as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/dns-message")
            connection.setRequestProperty("Accept", "application/dns-message")
            try {
                connection.outputStream.use { it.write(dnsMessage); it.flush() }
                if (connection.responseCode != 200) return@runCatching null
                val buffer = ByteArrayOutputStream()
                connection.inputStream.use { input -> input.copyTo(buffer) }
                if (buffer.size() <= 0) return@runCatching null
                DohResult(serverUrl, buffer.toByteArray())
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun selectNonVpnNetwork(context: Context): android.net.Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val nonVpn = cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return nonVpn ?: cm.activeNetwork
    }
}
