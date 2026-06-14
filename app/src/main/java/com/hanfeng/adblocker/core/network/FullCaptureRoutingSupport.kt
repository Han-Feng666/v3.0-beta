package com.HanFeng.core.network

object FullCaptureRoutingSupport {
    enum class Mode {
        NONE,
        LOCAL_PROXY,
        MITM_APP,
        MITM_GLOBAL
    }

    data class Input(
        val stableMode: Boolean,
        val localProxyFullCapture: Boolean,
        val httpDecryptEnabled: Boolean,
        val mitmCertificateInstalled: Boolean,
        val mitmAppFullCaptureEnabled: Boolean,
        val mitmFullCaptureEnabled: Boolean,
        val mitmCircuitOpen: Boolean
    )

    fun resolve(input: Input): Mode {
        if (input.stableMode) return Mode.NONE
        if (input.localProxyFullCapture) return Mode.LOCAL_PROXY
        if (!input.httpDecryptEnabled) return Mode.NONE
        if (!input.mitmCertificateInstalled) return Mode.NONE
        if (input.mitmCircuitOpen) return Mode.NONE
        if (input.mitmAppFullCaptureEnabled) return Mode.MITM_APP
        if (input.mitmFullCaptureEnabled) return Mode.MITM_GLOBAL
        return Mode.NONE
    }
}
