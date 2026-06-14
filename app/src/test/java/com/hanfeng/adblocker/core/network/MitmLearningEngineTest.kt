package com.HanFeng.core.network

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MitmLearningEngineTest {
    @Test
    fun `aggressive apps produce learning candidate after combined signals`() {
        MitmLearningEngine.prune()

        val appName = "免费小说大全"
        val domain = "adloader-learning.example.com"
        val ip = "203.0.113.10"

        MitmLearningEngine.observe(signal(appName, domain, ip, MitmLearningEngine.SignalType.DNS_UNKNOWN), enabled = true)
        MitmLearningEngine.observe(signal(appName, domain, ip, MitmLearningEngine.SignalType.QUIC_DOWNGRADE), enabled = true)
        val candidate = MitmLearningEngine.observe(
            signal(appName, domain, ip, MitmLearningEngine.SignalType.HTTPDNS_HINT),
            enabled = true
        )

        assertNotNull(candidate)
    }

    @Test
    fun `cert pinning cooldown suppresses learning candidates`() {
        val domain = "pinning-ad.example.com"
        MitmLearningEngine.markCertPinningFailure(domain)

        val candidate = MitmLearningEngine.observe(
            signal("免费小说大全", domain, "203.0.113.11", MitmLearningEngine.SignalType.BODY_CONFIRMED_AD),
            enabled = true
        )

        assertNull(candidate)
    }

    private fun signal(appName: String, domain: String, ip: String, type: MitmLearningEngine.SignalType): MitmLearningEngine.Signal {
        return MitmLearningEngine.Signal(
            appName = appName,
            domain = domain,
            ip = ip,
            sniHost = domain,
            alpnProtocols = listOf("h3"),
            type = type
        )
    }
}
