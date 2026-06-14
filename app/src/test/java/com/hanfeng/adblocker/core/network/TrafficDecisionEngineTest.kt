package com.HanFeng.core.network

import android.system.OsConstants
import com.HanFeng.model.PacketInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficDecisionEngineTest {
    @Test
    fun `global mitm forces quic fallback for aggressive apps with tracked https target`() {
        val decision = TrafficDecisionEngine.shouldBlockQuicFlow(
            quicInput(
                domain = "api.example-reader.com",
                appName = "番茄小说",
                hasHttpsTarget = true,
                globalMitmFullCapture = true
            )
        )

        assertTrue(decision.blocked)
        assertEquals("global-mitm-force-tcp", decision.reason)
    }

    @Test
    fun `stable mitm keeps unknown quic for aggressive apps passthrough`() {
        val decision = TrafficDecisionEngine.shouldBlockQuicFlow(
            quicInput(
                domain = "api.example-reader.com",
                appName = "番茄小说",
                hasHttpsTarget = true,
                globalMitmFullCapture = false
            )
        )

        assertFalse(decision.blocked)
    }

    @Test
    fun `global mitm keeps protected quic domains passthrough`() {
        val decision = TrafficDecisionEngine.shouldBlockQuicFlow(
            quicInput(
                domain = "googleapis.com",
                appName = "番茄小说",
                hasHttpsTarget = true,
                globalMitmFullCapture = true
            )
        )

        assertFalse(decision.blocked)
    }

    @Test
    fun `encrypted dns quic is forced back to tcp`() {
        val decision = TrafficDecisionEngine.shouldBlockQuicFlow(
            quicInput(
                domain = "dns.google",
                appName = "普通应用",
                hasHttpsTarget = true,
                globalMitmFullCapture = true
            )
        )

        assertTrue(decision.blocked)
        assertEquals("encrypted-dns-force-tcp", decision.reason)
    }

    @Test
    fun `global mitm forces unknown quic fallback for aggressive apps`() {
        val decision = TrafficDecisionEngine.shouldBlockQuicFlow(
            quicInput(
                domain = null,
                appName = "七猫小说",
                hasHttpsTarget = false,
                globalMitmFullCapture = true
            )
        )

        assertTrue(decision.blocked)
        assertEquals("global-mitm-force-tcp", decision.reason)
    }

    @Test
    fun `global mitm keeps unknown quic passthrough for ordinary apps`() {
        val decision = TrafficDecisionEngine.shouldBlockQuicFlow(
            quicInput(
                domain = null,
                appName = "普通应用",
                hasHttpsTarget = false,
                globalMitmFullCapture = true
            )
        )

        assertFalse(decision.blocked)
    }

    @Test
    fun `direct encrypted dns endpoint on dot port is blocked`() {
        val blocked = TrafficDecisionEngine.shouldBlockEncryptedDnsDirectFlow(
            input = TrafficDecisionEngine.EncryptedDnsDirectInput(
                destinationIp = "1.1.1.1",
                port = 853,
                appName = "普通应用",
                trackedTargetDomain = null
            ),
            isCurrentDnsEndpoint = false,
            isKnownPublicDnsEndpoint = true
        )

        assertTrue(blocked)
    }

    private fun quicInput(
        domain: String?,
        appName: String,
        hasHttpsTarget: Boolean,
        globalMitmFullCapture: Boolean
    ): TrafficDecisionEngine.QuicBlockInput {
        return TrafficDecisionEngine.QuicBlockInput(
            packet = PacketInfo(
                version = 4,
                protocol = OsConstants.IPPROTO_UDP,
                sourceAddress = byteArrayOf(10, 0, 0, 2),
                destinationAddress = byteArrayOf(1, 1, 1, 1),
                sourcePort = 32000,
                destinationPort = 443,
                payload = byteArrayOf(0xC3.toByte(), 0, 0, 1)
            ),
            payloadLooksLikeQuic = true,
            localProxyTarget = false,
            domain = domain,
            appName = appName,
            vendor = "其它 (Other)",
            matchedRule = null,
            bypassReason = null,
            httpDecryptEnabled = true,
            hasHttpsTarget = hasHttpsTarget,
            globalMitmFullCapture = globalMitmFullCapture
        )
    }
}
