package com.HanFeng.core.network

import java.net.InetSocketAddress
import java.net.Socket
import java.util.LinkedHashMap

object BridgeConnectionPool {
    private const val MAX_POOLED_CONNECTIONS = 16
    private const val POOLED_CONNECTION_TTL_MILLIS = 30_000L

    private data class PooledSocket(
        val socket: Socket,
        val pooledAt: Long
    )

    private val pool = LinkedHashMap<String, PooledSocket>(MAX_POOLED_CONNECTIONS, 0.75f, true)

    @Synchronized
    fun obtain(host: String, port: Int): Socket? {
        val key = "$host:$port"
        val entry = pool[key] ?: return null
        if (entry.socket.isClosed) {
            pool.remove(key)
            return null
        }
        if (System.currentTimeMillis() - entry.pooledAt > POOLED_CONNECTION_TTL_MILLIS) {
            runCatching { entry.socket.close() }
            pool.remove(key)
            return null
        }
        pool.remove(key)
        return entry.socket
    }

    @Synchronized
    fun recycle(host: String, port: Int, socket: Socket) {
        if (socket.isClosed) return
        try {
            socket.getInputStream().available()
        } catch (_: Exception) {
            runCatching { socket.close() }
            return
        }
        val key = "$host:$port"
        val existing = pool[key]
        if (existing != null) {
            runCatching { existing.socket.close() }
        }
        while (pool.size >= MAX_POOLED_CONNECTIONS) {
            val it = pool.entries.iterator()
            if (!it.hasNext()) break
            val oldest = it.next()
            runCatching { oldest.value.socket.close() }
            it.remove()
        }
        pool[key] = PooledSocket(socket, System.currentTimeMillis())
    }

    @Synchronized
    fun clear() {
        pool.values.forEach { runCatching { it.socket.close() } }
        pool.clear()
    }
}
