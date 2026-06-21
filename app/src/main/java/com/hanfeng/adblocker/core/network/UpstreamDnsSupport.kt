package com.HanFeng.core.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

object UpstreamDnsSupport {
    private const val DNS_RACE_CONCURRENCY = 3
    private const val DNS_RACE_TIMEOUT_MS = 2500L

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
        // Race DNS servers in waves - query up to 3 concurrently, first to respond wins
        servers.chunked(DNS_RACE_CONCURRENCY).forEach { wave ->
            val result = raceDnsWave(payload, wave, acquireSocket, releaseSocket, markSuccess, markFailure, onServerFailed)
            if (result != null) return result
        }
        return null
    }

    private fun raceDnsWave(
        payload: ByteArray,
        wave: List<InetAddress>,
        acquireSocket: (InetAddress) -> DatagramSocket?,
        releaseSocket: (InetAddress, DatagramSocket) -> Unit,
        markSuccess: (InetAddress) -> Unit,
        markFailure: (InetAddress) -> Unit,
        onServerFailed: (InetAddress, Throwable) -> Unit
    ): UpstreamDnsResult? {
        val resultRef = AtomicReference<UpstreamDnsResult>()
        val done = AtomicBoolean(false)
        val threads = wave.map { server ->
            thread(name = "dns-race-${server.hostAddress}") {
                if (done.get()) return@thread
                repeat(2) { attempt ->
                    if (done.get()) return@thread
                    val socket = acquireSocket(server) ?: return@repeat
                    runCatching {
                        socket.soTimeout = if (attempt == 0) 800 else 1200
                        socket.connect(server, 53)
                        socket.send(DatagramPacket(payload, payload.size))
                        val recvBuf = ByteArray(4096)
                        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                        socket.receive(recvPacket)
                        if (done.compareAndSet(false, true)) {
                            resultRef.set(UpstreamDnsResult(server, recvBuf.copyOf(recvPacket.length)))
                            markSuccess(server)
                        }
                        releaseSocket(server, socket)
                    }.onFailure { error ->
                        runCatching { socket.disconnect() }
                        markFailure(server)
                        if (attempt == 1 && !done.get()) {
                            onServerFailed(server, error)
                        }
                        releaseSocket(server, socket)
                    }
                }
            }
        }
        threads.forEach { it.join(DNS_RACE_TIMEOUT_MS) }
        if (!done.get()) {
            threads.forEach { if (it.isAlive) it.interrupt() }
        }
        return resultRef.get()
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
