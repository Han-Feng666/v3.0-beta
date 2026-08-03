package com.HanFeng.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsHandshakeEngineTest {
    @Test
    fun `passthrough accepts non 443 target port when bridge port matches`() {
        val decision = HttpsHandshakeEngine.decide(
            input(destinationPort = 5228, bridgePort = 5228)
        )

        assertTrue(decision.shouldHandle)
        assertEquals(HttpsHandshakeEngine.Event.SYN_OPEN, decision.event)
    }

    @Test
    fun `local proxy still accepts https destination through separate bridge port`() {
        val decision = HttpsHandshakeEngine.decide(
            input(destinationPort = 443, bridgePort = 7890)
        )

        assertTrue(decision.shouldHandle)
        assertEquals(HttpsHandshakeEngine.Event.SYN_OPEN, decision.event)
    }

    @Test
    fun `unmatched non 443 port is ignored`() {
        // 选择一个明确不在 TlsPortSet 中的端口做"不会被 MITM 处理"的代表
        val decision = HttpsHandshakeEngine.decide(
            input(destinationPort = 9999, bridgePort = 443)
        )

        assertFalse(decision.shouldHandle)
        assertEquals(HttpsHandshakeEngine.Event.NONE, decision.event)
    }

    @Test
    fun `global mitm accepts initial https syn before bridge is prepared`() {
        val decision = HttpsHandshakeEngine.decide(
            input(destinationPort = 443, bridgePort = null)
        )

        assertTrue(decision.shouldHandle)
        assertEquals(HttpsHandshakeEngine.Event.SYN_OPEN, decision.event)
    }

    @Test
    fun `global mitm accepts ack establish before bridge is prepared`() {
        val decision = HttpsHandshakeEngine.decide(
            input(
                destinationPort = 443,
                bridgePort = null,
                state = "syn_ack_sent",
                syn = false,
                ack = true
            )
        )

        assertTrue(decision.shouldHandle)
        assertEquals(HttpsHandshakeEngine.Event.ACK_ESTABLISH, decision.event)
    }

    @Test
    fun `global mitm waits for bridge before forwarding client hello`() {
        val waiting = HttpsHandshakeEngine.decide(
            input(
                destinationPort = 443,
                bridgePort = null,
                state = "established",
                syn = false,
                ack = true,
                psh = true,
                payloadLength = 64L
            )
        )
        val ready = HttpsHandshakeEngine.decide(
            input(
                destinationPort = 443,
                bridgePort = 8443,
                state = "bridge_bound",
                syn = false,
                ack = true,
                psh = true,
                payloadLength = 64L
            )
        )

        assertFalse(waiting.shouldHandle)
        assertTrue(ready.shouldHandle)
        assertEquals(HttpsHandshakeEngine.Event.CLIENT_PAYLOAD, ready.event)
    }

    private fun input(
        destinationPort: Int,
        bridgePort: Int?,
        state: String = "new",
        syn: Boolean = true,
        ack: Boolean = false,
        psh: Boolean = false,
        payloadLength: Long = 0L
    ): HttpsHandshakeEngine.Input {
        return HttpsHandshakeEngine.Input(
            protocol = 6,
            destinationPort = destinationPort,
            hasFlow = true,
            bridgePort = bridgePort,
            state = state,
            syn = syn,
            ack = ack,
            fin = false,
            rst = false,
            psh = psh,
            payloadLength = payloadLength
        )
    }
}
