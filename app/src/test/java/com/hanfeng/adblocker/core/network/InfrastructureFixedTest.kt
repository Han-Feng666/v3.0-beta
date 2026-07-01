package com.HanFeng.core.network

import com.HanFeng.service.VpnConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.LinkedHashMap

class InfrastructureFixedTest {

    @Test
    fun `VpnConstants has non-empty DNS addresses`() {
        assertTrue(VpnConstants.LOCAL_DNS_V4.isNotBlank())
        assertTrue(VpnConstants.LOCAL_DNS_V6.isNotBlank())
    }

    @Test
    fun `VpnConstants cache sizes are positive`() {
        assertTrue(VpnConstants.DNS_RESPONSE_CACHE_MAX_SIZE > 0)
        assertTrue(VpnConstants.AD_IP_TARGET_CACHE_MAX_SIZE > 0)
        assertTrue(VpnConstants.HTTPS_DECRYPT_IP_CACHE_MAX_SIZE > 0)
        assertTrue(VpnConstants.PASSTHROUGH_TCP_FLOW_CACHE_MAX_SIZE > 0)
    }

    @Test
    fun `VpnConstants TTL values are positive`() {
        assertTrue(VpnConstants.STALE_CACHE_GRACE_MILLIS > 0)
        assertTrue(VpnConstants.DNS_SERVER_CACHE_TTL_MILLIS > 0)
    }

    @Test
    fun `FlowCacheSupport putPruned respects max size`() {
        val cache = Collections.synchronizedMap(
            LinkedHashMap<String, String>(8, 0.75f, true)
        ) as MutableMap<String, String>

        repeat(20) { i ->
            FlowCacheSupport.putPruned(cache, "key$i", "value$i", 10)
        }

        assertTrue(cache.size <= 10)
    }

    @Test
    fun `FlowCacheSupport updateIfPresent works`() {
        val cache = Collections.synchronizedMap(
            LinkedHashMap<String, String>(8, 0.75f, true)
        ) as MutableMap<String, String>

        FlowCacheSupport.putPruned(cache, "a", "1", 10)
        val updated = FlowCacheSupport.updateIfPresent(cache, "a") { "2" }
        assertTrue(updated == "2")
    }

    @Test
    fun `FlowCacheSupport returns null for missing key`() {
        val cache = Collections.synchronizedMap(
            LinkedHashMap<String, String>(8, 0.75f, true)
        ) as MutableMap<String, String>

        val result = FlowCacheSupport.updateIfPresent(cache, "missing") { "x" }
        assertTrue(result == null)
    }

    @Test
    fun `ExpiringTargetCacheSupport putAllPrunedLocked respects max size`() {
        val cache = Collections.synchronizedMap(
            LinkedHashMap<String, Long>(8, 0.75f, true)
        ) as MutableMap<String, Long>

        synchronized(cache) {
            val entries = (1..30).map { "key$it" to it.toLong() }
            ExpiringTargetCacheSupport.putAllPrunedLocked(cache, entries, 10)
        }

        assertTrue(cache.size <= 10)
    }

    @Test
    fun `ExpiringTargetCacheSupport pruneExpiredLocked removes expired entries`() {
        val cache = Collections.synchronizedMap(
            LinkedHashMap<String, Long>(8, 0.75f, true)
        ) as MutableMap<String, Long>

        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache["fresh"] = now + 10_000L
            cache["stale"] = now - 10_000L
        }

        synchronized(cache) {
            ExpiringTargetCacheSupport.pruneExpiredLocked(cache, now) { it }
        }

        assertTrue(cache.containsKey("fresh"))
        assertFalse(cache.containsKey("stale"))
    }
}
