package com.HanFeng.core.network

import android.system.OsConstants
import com.HanFeng.model.PacketInfo
import java.io.File
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

object ProcNetResolver {
    data class Result(
        val uid: Int,
        val protocol: Int,
        val state: String,
        val source: String,
        val expiresAt: Long
    )

    private data class Entry(
        val localAddress: String,
        val localPort: Int,
        val remoteAddress: String,
        val remotePort: Int,
        val state: String,
        val uid: Int,
        val source: String
    )

    private const val MAX_CACHE_SIZE = 2048
    private const val EVICTION_RATIO = 0.25
    // 解析 /proc/net/{tcp,udp,tcp6,udp6} 每行的空白分隔符；纲领 Pattern 缓存，避免每行重编译
    private val WS_REGEX = Regex("\\s+")

    private val cache = ConcurrentHashMap<String, Result>()

    fun resolve(packet: PacketInfo, now: Long = System.currentTimeMillis()): Result? {
        val key = key(packet)
        cache[key]?.let { cached ->
            if (cached.expiresAt > now) return cached
            cache.remove(key)
        }
        val localAddress = formatAddress(packet.sourceAddress)
        val remoteAddress = formatAddress(packet.destinationAddress)
        val entries = when (packet.protocol) {
            OsConstants.IPPROTO_TCP -> readEntries("/proc/net/tcp", OsConstants.IPPROTO_TCP) + readEntries("/proc/net/tcp6", OsConstants.IPPROTO_TCP)
            OsConstants.IPPROTO_UDP -> readEntries("/proc/net/udp", OsConstants.IPPROTO_UDP) + readEntries("/proc/net/udp6", OsConstants.IPPROTO_UDP)
            else -> emptyList()
        }
        val match = entries.firstOrNull { entry ->
            entry.localPort == packet.sourcePort &&
                entry.remotePort == packet.destinationPort &&
                addressMatches(entry.localAddress, localAddress) &&
                addressMatches(entry.remoteAddress, remoteAddress)
        } ?: entries.firstOrNull { entry ->
            entry.localPort == packet.sourcePort && entry.remotePort == packet.destinationPort
        } ?: return null
        if (match.uid <= 0) return null
        val ttl = if (packet.protocol == OsConstants.IPPROTO_UDP) 8_000L else if (isClosedTcpState(match.state)) 10_000L else 25_000L
        val result = Result(
            uid = match.uid,
            protocol = packet.protocol,
            state = match.state,
            source = match.source,
            expiresAt = now + ttl
        )
        if (cache.size > MAX_CACHE_SIZE) {
            val numToEvict = (MAX_CACHE_SIZE * EVICTION_RATIO).toInt()
            val toRemove = cache.keys.asSequence().take(numToEvict).toList()
            toRemove.forEach { cache.remove(it) }
        }
        cache[key] = result
        return result
    }

    private fun readEntries(path: String, protocol: Int): List<Entry> {
        val file = File(path)
        if (!file.canRead()) return emptyList()
        return runCatching {
            file.bufferedReader().useLines { lines ->
                lines.drop(1).mapNotNull { line -> parseLine(line, path, protocol) }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseLine(line: String, source: String, protocol: Int): Entry? {
        val parts = line.trim().split(WS_REGEX)
        if (parts.size < 8) return null
        val local = parts.getOrNull(1) ?: return null
        val remote = parts.getOrNull(2) ?: return null
        val state = parts.getOrNull(3).orEmpty()
        val uid = parts.getOrNull(7)?.toIntOrNull() ?: return null
        val localAddress = parseProcAddress(local.substringBefore(':'), source.contains("6")) ?: return null
        val remoteAddress = parseProcAddress(remote.substringBefore(':'), source.contains("6")) ?: return null
        val localPort = local.substringAfter(':', "").toIntOrNull(16) ?: return null
        val remotePort = remote.substringAfter(':', "").toIntOrNull(16) ?: return null
        return Entry(localAddress, localPort, remoteAddress, remotePort, state, uid, source)
    }

    private fun parseProcAddress(raw: String, ipv6: Boolean): String? {
        return runCatching {
            if (!ipv6) {
                val value = raw.toLong(16)
                val bytes = byteArrayOf(
                    (value and 0xFF).toByte(),
                    ((value shr 8) and 0xFF).toByte(),
                    ((value shr 16) and 0xFF).toByte(),
                    ((value shr 24) and 0xFF).toByte()
                )
                InetAddress.getByAddress(bytes).hostAddress
            } else {
                val bytes = ByteArray(16)
                raw.chunked(8).forEachIndexed { index, chunk ->
                    val value = chunk.toLong(16).toInt()
                    ByteBuffer.wrap(bytes, index * 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
                }
                InetAddress.getByAddress(bytes).hostAddress
            }
        }.getOrNull()
    }

    private fun formatAddress(bytes: ByteArray): String {
        return runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrDefault("") ?: ""
    }

    private fun addressMatches(left: String, right: String): Boolean {
        if (left == right) return true
        return left == "0.0.0.0" || left == "::" || right == "0.0.0.0" || right == "::"
    }

    private fun isClosedTcpState(state: String): Boolean {
        return state.equals("06", ignoreCase = true) || state.equals("07", ignoreCase = true) || state.equals("08", ignoreCase = true)
    }

    private fun key(packet: PacketInfo): String {
        return listOf(
            packet.protocol.toString(),
            formatAddress(packet.sourceAddress),
            packet.sourcePort.toString(),
            formatAddress(packet.destinationAddress),
            packet.destinationPort.toString()
        ).joinToString(":")
    }
}
