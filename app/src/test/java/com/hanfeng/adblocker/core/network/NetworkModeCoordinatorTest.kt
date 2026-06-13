package com.HanFeng.core.network

import android.system.OsConstants
import com.HanFeng.model.LocalProxyCoexistConfig
import com.HanFeng.model.PacketInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModeCoordinatorTest {
    @Test
    fun `local proxy config without targets does not capture traffic`() {
        val flags = featureFlags(
            localProxyConfig = LocalProxyCoexistConfig(enabled = true, host = "127.0.0.1", port = 7890),
            localProxyTargetPackages = emptySet()
        )

        assertFalse(NetworkModeCoordinator.shouldCaptureFullTraffic(flags))
    }

    @Test
    fun `local proxy config with targets captures traffic`() {
        val flags = featureFlags(
            localProxyConfig = LocalProxyCoexistConfig(enabled = true, host = "127.0.0.1", port = 7890),
            localProxyTargetPackages = setOf("com.example.game")
        )

        assertTrue(NetworkModeCoordinator.shouldCaptureFullTraffic(flags))
    }

    @Test
    fun `invalid local proxy config does not capture traffic`() {
        val flags = featureFlags(
            localProxyConfig = LocalProxyCoexistConfig(enabled = true, host = "127.0.0.1", port = null),
            localProxyTargetPackages = setOf("com.example.game")
        )

        assertFalse(NetworkModeCoordinator.shouldCaptureFullTraffic(flags))
    }

    @Test
    fun `empty target packages route no tcp`() {
        val common = routeInput(targetPackages = emptySet(), destinationIp = "203.0.113.8")
        val proxyEndpoint = routeInput(targetPackages = emptySet(), destinationIp = "127.0.0.1", destinationPort = 7890)

        assertFalse(TrafficDecisionEngine.shouldRouteViaLocalProxy(common))
        assertFalse(TrafficDecisionEngine.shouldRouteViaLocalProxy(proxyEndpoint))
    }

    @Test
    fun `selected target packages route only matching apps`() {
        val targetPackages = setOf("com.example.game")
        val matched = routeInput(targetPackages = targetPackages, appName = "Example Game (com.example.game)")
        val unmatched = routeInput(targetPackages = targetPackages, appName = "Browser (com.example.browser)")

        assertTrue(TrafficDecisionEngine.shouldRouteViaLocalProxy(matched))
        assertFalse(TrafficDecisionEngine.shouldRouteViaLocalProxy(unmatched))
    }

    private fun featureFlags(
        localProxyConfig: LocalProxyCoexistConfig,
        localProxyTargetPackages: Set<String>
    ): NetworkFeatureFlags {
        return NetworkFeatureFlags(
            httpDecryptEnabled = false,
            mitmCertificateInstalled = false,
            shizukuConnectionOwnerReady = false,
            shizukuAdControlReady = false,
            shizukuStrictAppAdBlockEnabled = false,
            localProxyConfig = localProxyConfig,
            localProxyTargetPackages = localProxyTargetPackages,
            lightweightPassThroughMode = false
        )
    }

    private fun routeInput(
        targetPackages: Set<String>,
        destinationIp: String,
        destinationPort: Int = 443,
        appName: String? = null
    ): TrafficDecisionEngine.LocalProxyRouteInput {
        return TrafficDecisionEngine.LocalProxyRouteInput(
            packet = PacketInfo(
                version = 4,
                protocol = OsConstants.IPPROTO_TCP,
                sourceAddress = byteArrayOf(10, 0, 0, 2),
                destinationAddress = byteArrayOf(1, 1, 1, 1),
                sourcePort = 32000,
                destinationPort = destinationPort,
                payload = byteArrayOf()
            ),
            configHost = "127.0.0.1",
            configPort = 7890,
            configEnabled = true,
            localProxyReachable = true,
            targetPackages = targetPackages,
            cachedFlowDecision = null,
            cachedSourcePortDecision = null,
            destinationIp = destinationIp,
            appName = appName,
            belongsToTargetApp = appName?.let { NetworkModeCoordinator.belongsToLocalProxyTarget(it, targetPackages) } == true,
            belongsToTargetUid = false
        )
    }

    private fun routeInput(
        targetPackages: Set<String>,
        appName: String
    ): TrafficDecisionEngine.LocalProxyRouteInput {
        return routeInput(
            targetPackages = targetPackages,
            destinationIp = "203.0.113.8",
            appName = appName
        )
    }
}
