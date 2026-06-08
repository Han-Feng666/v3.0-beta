package com.HanFeng.core.network

object HttpsPipeline {
    data class ClientHelloInput(
        val targetDomain: String,
        val sniHost: String,
        val blockedTarget: Boolean,
        val blockedSni: Boolean,
        val targetGeneralAd: Boolean,
        val sniGeneralAd: Boolean,
        val targetSocialCore: Boolean,
        val sniSocialCore: Boolean,
        val targetProtected: Boolean,
        val sniProtected: Boolean,
        val targetWhitelisted: Boolean,
        val sniWhitelisted: Boolean,
        val targetSensitive: Boolean,
        val sniSensitive: Boolean
    )

    data class ClientHelloDecision(
        val shouldObserve: Boolean,
        val reason: String
    )

    data class TransparentProxyInput(
        val domain: String,
        val sensitive: Boolean,
        val socialCore: Boolean,
        val tcpFlags: Int,
        val hasPreparedBridgePort: Boolean,
        val hasPayload: Boolean,
        val currentState: String?
    )

    data class TransparentProxyDecision(
        val shouldTrack: Boolean,
        val reason: String,
        val nextState: String?
    )

    fun decideClientHello(input: ClientHelloInput): ClientHelloDecision {
        if (input.targetSocialCore || input.sniSocialCore) {
            return ClientHelloDecision(false, "social-core")
        }
        if (input.targetProtected || input.sniProtected) {
            return ClientHelloDecision(false, "protected-domain")
        }
        if (!input.blockedTarget && !input.targetGeneralAd && input.targetWhitelisted) {
            return ClientHelloDecision(false, "target-whitelisted")
        }
        if (!input.blockedSni && !input.sniGeneralAd && input.sniWhitelisted) {
            return ClientHelloDecision(false, "sni-whitelisted")
        }
        if (input.targetSensitive || input.sniSensitive) {
            return ClientHelloDecision(false, "sensitive-domain")
        }
        return ClientHelloDecision(true, "observe")
    }

    fun decideTransparentProxy(input: TransparentProxyInput): TransparentProxyDecision {
        if (input.sensitive) {
            return TransparentProxyDecision(false, "sensitive-domain", null)
        }
        if (input.socialCore) {
            return TransparentProxyDecision(false, "social-core", null)
        }
        return TransparentProxyDecision(
            shouldTrack = true,
            reason = "track",
            nextState = nextTransparentProxyState(
                flags = input.tcpFlags,
                hasPreparedBridgePort = input.hasPreparedBridgePort,
                hasPayload = input.hasPayload,
                currentState = input.currentState
            )
        )
    }

    fun nextTransparentProxyState(
        flags: Int,
        hasPreparedBridgePort: Boolean,
        hasPayload: Boolean,
        currentState: String?
    ): String {
        return when {
            hasTcpFlag(flags, 0x02) && !hasTcpFlag(flags, 0x10) -> "syn_seen"
            hasTcpFlag(flags, 0x02) && hasTcpFlag(flags, 0x10) -> "syn_ack_seen"
            hasTcpFlag(flags, 0x01) -> "fin_seen"
            hasTcpFlag(flags, 0x04) -> "rst_seen"
            hasPreparedBridgePort -> "bridge_bound"
            hasPayload -> "payload_seen"
            else -> currentState ?: "tracked"
        }
    }

    private fun hasTcpFlag(flags: Int, flag: Int): Boolean {
        return flags and flag == flag
    }
}
