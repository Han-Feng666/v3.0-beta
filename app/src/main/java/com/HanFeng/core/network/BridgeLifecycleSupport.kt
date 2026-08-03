package com.HanFeng.core.network

import java.util.LinkedHashMap

object BridgeLifecycleSupport {
    data class ClientFinTransition(
        val state: String,
        val nextServerSequenceToSend: Long,
        val storedServerNextSequence: Long,
        val clientAcknowledgement: Long,
        val lastSeenAt: Long
    )

    fun resolveClientFinTransition(
        serverInitialSequence: Long?,
        serverNextSequence: Long?,
        clientSequenceNumber: Long,
        payloadLength: Long,
        now: Long,
        synthesizeServerSequence: () -> Long
    ): ClientFinTransition {
        val serverSeqBase = serverInitialSequence ?: synthesizeServerSequence()
        val nextServerSeq = serverNextSequence ?: (serverSeqBase + 1)
        val ackNumber = clientSequenceNumber + 1 + payloadLength
        return ClientFinTransition(
            state = "fin_ack_sent",
            nextServerSequenceToSend = nextServerSeq,
            storedServerNextSequence = nextServerSeq + 1,
            clientAcknowledgement = ackNumber,
            lastSeenAt = now
        )
    }

    /**
     * 注册新建 bridge socket session 至缓存。
     *
     * 注意：cache 是有 maxSize 上限的 LRU LinkedHashMap。直接 cache[key] = session
     * 在超过阈值时会让 LRU 默默"驱逐"最老的 session，而这些 session 持有底层 Socket / fd。
     * 不调用 close 会导致 fd 泄漏，长时间运行后 fd 耗尽导致网络异常甚至崩溃。
     *
     * 提供 maxSize 与 onEvict 后本函数会按 max 自动 prune，并对最老的项调用 onEvict
     * （通常用来关闭 socket）。size 在阈值内时与旧实现一致。
     */
    fun <TSession> registerConnectedSession(
        cache: MutableMap<String, TSession>,
        flowKey: String,
        session: TSession,
        maxSize: Int = Int.MAX_VALUE,
        onEvict: (TSession) -> Unit = {}
    ) {
        synchronized(cache) {
            cache[flowKey] = session
            if ( maxSize != Int.MAX_VALUE && cache.size > maxSize) {
                val toEvict = cache.size - maxSize
                if (toEvict > 0) {
                    val evictedEntries = ArrayList<Pair<String, TSession>>(toEvict)
                    val iter = cache.entries.iterator()
                    var n = 0
                    while (iter.hasNext() && n < toEvict) {
                        val entry = iter.next()
                        evictedEntries += entry.key to entry.value
                        iter.remove()
                        n++
                    }
                    evictedEntries.forEach { (_, v) -> runCatching { onEvict(v) } }
                }
            }
        }
    }
}
