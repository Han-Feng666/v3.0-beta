package com.HanFeng.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class FullCaptureRoutingSupportTest {
    @Test
    fun `stable mode disables full capture`() {
        val mode = FullCaptureRoutingSupport.resolve(
            input(
                stableMode = true,
                localProxyFullCapture = true,
                mitmExperimentEnabled = true
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.NONE, mode)
    }

    @Test
    fun `local proxy full capture has priority`() {
        val mode = FullCaptureRoutingSupport.resolve(
            input(
                localProxyFullCapture = true,
                mitmExperimentEnabled = true
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.LOCAL_PROXY, mode)
    }

    @Test
    fun `local proxy full capture still works when mitm circuit is open`() {
        val mode = FullCaptureRoutingSupport.resolve(
            input(
                localProxyFullCapture = true,
                mitmExperimentEnabled = true,
                mitmCircuitOpen = true
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.LOCAL_PROXY, mode)
    }

    @Test
    fun `mitm full capture requires decrypt certificate experiment and closed circuit`() {
        val enabled = FullCaptureRoutingSupport.resolve(input(mitmExperimentEnabled = true))
        val circuitOpen = FullCaptureRoutingSupport.resolve(
            input(
                mitmExperimentEnabled = true,
                mitmCircuitOpen = true
            )
        )
        val missingCertificate = FullCaptureRoutingSupport.resolve(
            input(
                mitmExperimentEnabled = true,
                mitmCertificateInstalled = false
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.MITM, enabled)
        assertEquals(FullCaptureRoutingSupport.Mode.NONE, circuitOpen)
        assertEquals(FullCaptureRoutingSupport.Mode.NONE, missingCertificate)
    }

    private fun input(
        stableMode: Boolean = false,
        localProxyFullCapture: Boolean = false,
        httpDecryptEnabled: Boolean = true,
        mitmCertificateInstalled: Boolean = true,
        mitmExperimentEnabled: Boolean = false,
        mitmCircuitOpen: Boolean = false
    ): FullCaptureRoutingSupport.Input {
        return FullCaptureRoutingSupport.Input(
            stableMode = stableMode,
            localProxyFullCapture = localProxyFullCapture,
            httpDecryptEnabled = httpDecryptEnabled,
            mitmCertificateInstalled = mitmCertificateInstalled,
            mitmExperimentEnabled = mitmExperimentEnabled,
            mitmCircuitOpen = mitmCircuitOpen
        )
    }
}
