package com.HanFeng.core.network

import com.HanFeng.data.RuleRepository

object DoHDetector {
    val dohPathKeywords = listOf(
        "/dns-query", "/resolve", "/query", "/dns", "/httpdns", "/resolver", "/dns/resolve", "/doh"
    )

    val dohContentTypeKeywords = listOf(
        "application/dns-message",
        "application/dns-message+xml",
        "application/dns-udpwireformat",
        "application/dns-json",
        "application/oblivious-dns-message",
        "application/x-javascript",
        "application/json+dns"
    )

    fun isDohRequest(host: String, path: String, headers: Map<String, String>): Boolean {
        val lowerHost = host.lowercase()
        val lowerPath = path.lowercase()
        if (RuleRepository.isBypassProtectionDomain(lowerHost)) return true
        if (dohPathKeywords.any(lowerPath::contains)) {
            if (lowerPath.contains("dns=") || lowerPath.contains("name=") || lowerPath.contains("type=") || lowerPath.contains("ct=")) {
                return true
            }
        }
        val contentType = headers["content-type"].orEmpty().lowercase()
        val accept = headers["accept"].orEmpty().lowercase()
        if (dohContentTypeKeywords.any { keyword -> contentType.contains(keyword) || accept.contains(keyword) }) {
            return true
        }
        return lowerHost.contains("httpdns") || lowerHost.contains("dns-query") || lowerHost.contains("resolver")
    }
}
