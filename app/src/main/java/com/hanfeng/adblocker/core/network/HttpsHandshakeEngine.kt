package com.HanFeng.core.network

object HttpsHandshakeEngine {
    enum class Event {
        SYN_OPEN,
        ACK_ESTABLISH,
        CLIENT_PAYLOAD,
        SERVER_ACK,
        BRIDGE_FIN_ACK,
        CLIENT_FIN,
        CLIENT_RST,
        NONE
    }

    data class Input(
        val protocol: Int,
        val destinationPort: Int,
        val hasFlow: Boolean,
        val bridgePort: Int?,
        val state: String,
        val syn: Boolean,
        val ack: Boolean,
        val fin: Boolean,
        val rst: Boolean,
        val psh: Boolean,
        val payloadLength: Long
    )

    data class Decision(
        val shouldHandle: Boolean,
        val event: Event
    )

    fun decide(input: Input): Decision {
        if (input.protocol != 6 || input.destinationPort != 443) {
            return Decision(false, Event.NONE)
        }
        if (!input.hasFlow || input.bridgePort == null) {
            return Decision(false, Event.NONE)
        }
        if (input.syn && !input.ack) {
            return Decision(true, Event.SYN_OPEN)
        }
        if (input.state == "syn_ack_sent" && input.ack && input.payloadLength == 0L) {
            return Decision(true, Event.ACK_ESTABLISH)
        }
        if ((input.state == "established" || input.state == "payload_acknowledged") && (input.payloadLength > 0 || input.psh)) {
            return Decision(true, Event.CLIENT_PAYLOAD)
        }
        if ((input.state == "server_payload_sent" || input.state == "payload_acknowledged") && input.ack && input.payloadLength == 0L) {
            return Decision(true, Event.SERVER_ACK)
        }
        if (input.state == "bridge_fin_sent" && input.ack && input.payloadLength == 0L) {
            return Decision(true, Event.BRIDGE_FIN_ACK)
        }
        if (input.fin) {
            return Decision(true, Event.CLIENT_FIN)
        }
        if (input.state == "fin_ack_sent" && input.ack && input.payloadLength == 0L) {
            return Decision(true, Event.BRIDGE_FIN_ACK)
        }
        if (input.rst) {
            return Decision(true, Event.CLIENT_RST)
        }
        return Decision(false, Event.NONE)
    }
}
