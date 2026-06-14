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
                mitmFullCaptureEnabled = true
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.NONE, mode)
    }

    @Test
    fun `local proxy full capture has priority`() {
        val mode = FullCaptureRoutingSupport.resolve(
            input(
                localProxyFullCapture = true,
                mitmAppFullCaptureEnabled = true,
                mitmFullCaptureEnabled = true
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.LOCAL_PROXY, mode)
    }

    @Test
    fun `mitm app full capture is guarded by decrypt certificate and circuit`() {
        val enabled = FullCaptureRoutingSupport.resolve(input(mitmAppFullCaptureEnabled = true))
        val circuitOpen = FullCaptureRoutingSupport.resolve(
            input(
                mitmAppFullCaptureEnabled = true,
                mitmCircuitOpen = true
            )
        )
        val missingCertificate = FullCaptureRoutingSupport.resolve(
            input(
                mitmAppFullCaptureEnabled = true,
                mitmCertificateInstalled = false
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.MITM_APP, enabled)
        assertEquals(FullCaptureRoutingSupport.Mode.NONE, circuitOpen)
        assertEquals(FullCaptureRoutingSupport.Mode.NONE, missingCertificate)
    }

    @Test
    fun `local proxy full capture still works when mitm circuit is open`() {
        val mode = FullCaptureRoutingSupport.resolve(
            input(
                localProxyFullCapture = true,
                mitmCircuitOpen = true
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.LOCAL_PROXY, mode)
    }

    @Test
    fun `global mitm full capture remains opt in and guarded`() {
        val enabled = FullCaptureRoutingSupport.resolve(input(mitmFullCaptureEnabled = true))
        val circuitOpen = FullCaptureRoutingSupport.resolve(
            input(
                mitmFullCaptureEnabled = true,
                mitmCircuitOpen = true
            )
        )
        val missingCertificate = FullCaptureRoutingSupport.resolve(
            input(
                mitmFullCaptureEnabled = true,
                mitmCertificateInstalled = false
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.MITM_GLOBAL, enabled)
        assertEquals(FullCaptureRoutingSupport.Mode.NONE, circuitOpen)
        assertEquals(FullCaptureRoutingSupport.Mode.NONE, missingCertificate)
    }

    @Test
    fun `mitm decrypt does not enable full capture by default`() {
        val mode = FullCaptureRoutingSupport.resolve(
            input(
                httpDecryptEnabled = true,
                mitmCertificateInstalled = true,
                mitmFullCaptureEnabled = false,
                mitmCircuitOpen = false
            )
        )

        assertEquals(FullCaptureRoutingSupport.Mode.NONE, mode)
    }

    @Test
    fun `mitm full capture stays disabled without flag`() {
        val mode = FullCaptureRoutingSupport.resolve(input())

        assertEquals(FullCaptureRoutingSupport.Mode.NONE, mode)
    }

    private fun input(
        stableMode: Boolean = false,
        localProxyFullCapture: Boolean = false,
        httpDecryptEnabled: Boolean = true,
        mitmCertificateInstalled: Boolean = true,
        mitmAppFullCaptureEnabled: Boolean = false,
        mitmFullCaptureEnabled: Boolean = false,
        mitmCircuitOpen: Boolean = false
    ): FullCaptureRoutingSupport.Input {
        return FullCaptureRoutingSupport.Input(
            stableMode = stableMode,
            localProxyFullCapture = localProxyFullCapture,
            httpDecryptEnabled = httpDecryptEnabled,
            mitmCertificateInstalled = mitmCertificateInstalled,
            mitmAppFullCaptureEnabled = mitmAppFullCaptureEnabled,
            mitmFullCaptureEnabled = mitmFullCaptureEnabled,
            mitmCircuitOpen = mitmCircuitOpen
        )
    }
}
