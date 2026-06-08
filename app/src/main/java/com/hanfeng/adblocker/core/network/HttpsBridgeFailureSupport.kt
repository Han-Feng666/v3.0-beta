package com.HanFeng.core.network

object HttpsBridgeFailureSupport {
    fun buildBypassReason(error: Throwable): String {
        return "io-bridge:${BridgeFailureSupport.formatFailureDetail(error)}"
    }

    fun buildResetMessage(action: String, domain: String): String {
        return "Bridge $action reset HTTPS proxy flow domain=$domain"
    }
}
