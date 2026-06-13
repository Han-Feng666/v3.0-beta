package com.HanFeng.core.network

object FullCaptureRoutingSupport {
    enum class Mode {
        NONE,
        LOCAL_PROXY,
        MITM
    }

    data class Input(
        val stableMode: Boolean,
        val localProxyFullCapture: Boolean,
        val httpDecryptEnabled: Boolean,
        val mitmCertificateInstalled: Boolean,
        val mitmExperimentEnabled: Boolean,
        val mitmCircuitOpen: Boolean
    )

    fun resolve(input: Input): Mode {
        if (input.stableMode) return Mode.NONE
        if (input.localProxyFullCapture) return Mode.LOCAL_PROXY
        if (!input.httpDecryptEnabled) return Mode.NONE
        if (!input.mitmCertificateInstalled) return Mode.NONE
        if (!input.mitmExperimentEnabled) return Mode.NONE
        if (input.mitmCircuitOpen) return Mode.NONE
        return Mode.MITM
    }
}
