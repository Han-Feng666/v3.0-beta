package com.HanFeng.core.network

object BridgeFailureSupport {
    fun formatFailureDetail(error: Throwable): String {
        return error.message ?: error.javaClass.simpleName
    }

    fun buildFailureLog(prefix: String, flowKey: String, error: Throwable): String {
        return "$prefix flow=$flowKey: ${formatFailureDetail(error)}"
    }
}
