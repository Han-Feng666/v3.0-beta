package com.HanFeng.core.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

object UpstreamDnsSupport {
    data class UpstreamDnsResult(
        val server: InetAddress,
        val response: ByteArray
    )

    fun queryUpstreamDns(
        payload: ByteArray,
        servers: List<InetAddress>,
        acquireSocket: (InetAddress) -> DatagramSocket?,
        releaseSocket: (InetAddress, DatagramSocket) -> Unit,
        markSuccess: (InetAddress) -> Unit,
        markFailure: (InetAddress) -> Unit,
        onServerFailed: (InetAddress, Throwable) -> Unit
    ): UpstreamDnsResult? {
        val receiveBuffer = ByteArray(4096)
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
        servers.forEach { server ->
            repeat(2) { attempt ->
                val socket = acquireSocket(server) ?: return@repeat
                runCatching {
                    socket.soTimeout = if (attempt == 0) 1200 else 1800
                    socket.connect(server, 53)
                    socket.send(DatagramPacket(payload, payload.size))
                    packet.length = receiveBuffer.size
                    socket.receive(packet)
                    markSuccess(server)
                    releaseSocket(server, socket)
                    return UpstreamDnsResult(server, receiveBuffer.copyOf(packet.length))
                }.onFailure {
                    socket.disconnect()
                    markFailure(server)
                    if (attempt == 1) {
                        onServerFailed(server, it)
                    }
                    releaseSocket(server, socket)
                }
            }
        }
        return null
    }

    fun acquireDnsSocket(
        pool: ConcurrentLinkedQueue<Pair<InetAddress, DatagramSocket>>,
        lock: Any,
        server: InetAddress,
        createSocket: () -> DatagramSocket?
    ): DatagramSocket? {
        synchronized(lock) {
            var scanned = 0
            val maxScan = pool.size.coerceAtMost(4)
            while (scanned < maxScan) {
                val poolEntry = pool.poll() ?: break
                scanned++
                if (poolEntry.first == server && !poolEntry.second.isClosed) {
                    return poolEntry.second
                }
                if (poolEntry.second.isClosed) {
                    continue
                }
                pool.offer(poolEntry)
            }
        }
        return createSocket()
    }

    fun releaseDnsSocket(
        pool: ConcurrentLinkedQueue<Pair<InetAddress, DatagramSocket>>,
        lock: Any,
        server: InetAddress,
        socket: DatagramSocket,
        maxPoolSize: Int = 4
    ) {
        if (socket.isClosed) return
        synchronized(lock) {
            if (pool.size < maxPoolSize) {
                pool.offer(server to socket)
            } else {
                socket.close()
            }
        }
    }
}
