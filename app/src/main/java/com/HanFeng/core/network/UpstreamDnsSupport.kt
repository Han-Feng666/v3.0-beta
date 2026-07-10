package com.HanFeng.core.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object UpstreamDnsSupport {
    private const val DNS_RACE_CONCURRENCY = 3
    private const val DNS_RACE_TIMEOUT_MS = 2500L

    private val dnsRaceExecutor = Executors.newFixedThreadPool(DNS_RACE_CONCURRENCY) { runnable ->
        Thread(runnable).apply {
            name = "dns-race-worker"
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private val dnsRecvBuffer = ThreadLocal.withInitial { ByteArray(4096) }

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
        val futures = wave.map { server ->
            dnsRaceExecutor.submit<Unit> {
                if (done.get()) return@submit
                repeat(2) { attempt ->
                    if (done.get()) return@submit
                    val socket = acquireSocket(server) ?: return@repeat
                    runCatching {
                        socket.soTimeout = if (attempt == 0) 800 else 1200
                        socket.connect(server, 53)
                        socket.send(DatagramPacket(payload, payload.size))
                        val recvBuf = dnsRecvBuffer.get()
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
        futures.forEach { runCatching { it.get(DNS_RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS) } }
        if (!done.get()) {
            futures.forEach { it.cancel(true) }
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
