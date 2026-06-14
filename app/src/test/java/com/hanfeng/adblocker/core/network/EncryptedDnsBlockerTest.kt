package com.HanFeng.core.network

import android.system.OsConstants
import com.HanFeng.model.PacketInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class EncryptedDnsBlockerTest {
    @Test
    fun `known encrypted dns hosts are recognized`() {
        assertTrue(EncryptedDnsBlocker.isKnownEncryptedDnsHost("dns.google"))
        assertTrue(EncryptedDnsBlocker.isKnownEncryptedDnsHost("mozilla.cloudflare-dns.com"))
        assertTrue(EncryptedDnsBlocker.isKnownEncryptedDnsHost("httpdns.aliyun.com"))
        assertFalse(EncryptedDnsBlocker.isKnownEncryptedDnsHost("api.example.com"))
    }

    @Test
    fun `dot and doq ports are blocked when fallback is forced`() {
        val tcpDot = packet(protocol = OsConstants.IPPROTO_TCP, destinationIp = "8.8.8.8", destinationPort = 853)
        val udpDoq = packet(protocol = OsConstants.IPPROTO_UDP, destinationIp = "1.1.1.1", destinationPort = 8853)

        assertTrue(
            EncryptedDnsBlocker.shouldBlockDirectFlow(
                packet = tcpDot,
                destinationIp = "8.8.8.8",
                appName = "免费小说大全",
                forceEncryptedDnsFallback = true,
                protectedApp = false
            ).blocked
        )
        assertTrue(
            EncryptedDnsBlocker.shouldBlockDirectFlow(
                packet = udpDoq,
                destinationIp = "1.1.1.1",
                appName = "免费小说大全",
                forceEncryptedDnsFallback = true,
                protectedApp = false
            ).blocked
        )
    }

    @Test
    fun `protected apps bypass encrypted dns blocker`() {
        val tcpDot = packet(protocol = OsConstants.IPPROTO_TCP, destinationIp = "8.8.8.8", destinationPort = 853)

        assertFalse(
            EncryptedDnsBlocker.shouldBlockDirectFlow(
                packet = tcpDot,
                destinationIp = "8.8.8.8",
                appName = "微信",
                forceEncryptedDnsFallback = true,
                protectedApp = true
            ).blocked
        )
    }

    @Test
    fun `client hello with known doh sni and h2 is blocked`() {
        val decision = EncryptedDnsBlocker.shouldBlockClientHello(
            destinationIp = "8.8.8.8",
            metadata = EncryptedDnsBlocker.ClientHelloMetadata(
                sniHost = "dns.google",
                alpnProtocols = listOf("h2")
            ),
            appName = "免费小说大全",
            forceEncryptedDnsFallback = true,
            protectedApp = false
        )

        assertTrue(decision.blocked)
    }

    private fun packet(protocol: Int, destinationIp: String, destinationPort: Int): PacketInfo {
        return PacketInfo(
            version = 4,
            sourceAddress = InetAddress.getByName("10.99.0.1").address,
            destinationAddress = InetAddress.getByName(destinationIp).address,
            protocol = protocol,
            sourcePort = 43210,
            destinationPort = destinationPort,
            payload = byteArrayOf(0xC3.toByte(), 0x00, 0x00, 0x00, 0x01)
        )
    }
}
