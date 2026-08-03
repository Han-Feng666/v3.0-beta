package com.HanFeng.capture

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 抓包条目环形缓冲。
 *
 * 设计要点:
 * - 固定容量, 满则丢弃最早条目(design correctness 3)
 * - 支持 `txnId -> entry` 快速索引, 用于在响应到达后定位补全字段(requirements R4.2)
 * - 全程无锁: 基于 `ConcurrentLinkedDeque` + `ConcurrentHashMap`, 旁路读 / UI 写互不阻塞 VPN 数据面线程(requirements R12.2)
 * - entry 序号由 [txnSeq] 单调递增, 与 [CaptureController] / [CaptureReplayEngine] 共享命名空间
 *
 * 引用 design.md Components #2。
 */
class CaptureRingBuffer(
    initialCapacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        /** 默认容量按 design 防止中端机内存吃紧: 200 条/会话。 */
        const val DEFAULT_CAPACITY = 200

        /** 触发 `App.onTrimMemory(RUNNING_LOW)` 时自动减半的容量上限。 */
        const val LOW_MEMORY_CAPACITY = 100
    }

    @Volatile
    var capacity: Int = initialCapacity.coerceAtLeast(1)
        private set

    private val entries = ConcurrentLinkedDeque<CaptureEntry>()
    private val byTxn = ConcurrentHashMap<Long, CaptureEntry>()

    /** 全局递增事务 id 高位; 与 [CaptureEntry.makeReplayTxnId] 隔离。 */
    private val txnSeq = AtomicLong(0)

    /** 分配下一个正常(非重放)txnId。 */
    fun nextTxnId(): Long = txnSeq.incrementAndGet()

    /** 当前条目数(并发 snapshot, 仅用于 UI 调度; 不保证读到精确数)。 */
    fun size(): Int = entries.size

    /**
     * 写入仅请求阶段的 entry。
     * - 容量满时丢弃 tail 最旧条目 + 对应索引项
     * - 同 txnId 已存在时不重写(防响应阶段误覆盖请求阶段)
     *
     * 引用 requirements R4.1 / R4.2 / R4.3。
     */
    fun putRequest(entry: CaptureEntry) {
        if (byTxn.containsKey(entry.txnId)) return
        val droppedEvict = evictIfFull()
        byTxn[entry.txnId] = entry
        entries.addFirst(entry)
        @Suppress("UNUSED_VARIABLE")
        val unused = droppedEvict // 防编译器优化掉
    }

    /**
     * 补全响应字段。若 txnId 未命中(已被淘汰), 静默返回。
     *
     * 引用 requirements R4.2。
     */
    fun putResponse(
        txnId: Long,
        responseStatus: Int,
        responseHeaders: Map<String, String>,
        responseBodyPreview: ByteArray?,
        responseBodyTruncated: Boolean,
        durationMs: Long,
        intercepted: Boolean
    ) {
        val existing = byTxn[txnId] ?: return
        val updated = existing.copy(
            responseStatus = responseStatus,
            responseHeaders = responseHeaders,
            responseBodyPreview = responseBodyPreview,
            responseBodyTruncated = responseBodyTruncated,
            durationMs = durationMs,
            intercepted = intercepted
        )
        byTxn[txnId] = updated
        // ConcurrentLinkedDeque 不便原地修改, 走"先删后加头部最简"
        entries.removeIf { it.txnId == txnId }
        entries.addFirst(updated)
    }

    /**
     * 整体替换某 txnId 的 entry(用于 TLS 元数据后续到达时合并,
     * 保留 [putResponse] 不会复制的 tlsMeta / error / replayed 字段)。
     *
     * txnId 未命中时静默返回; 命中则"先删后加头部"维持最新→最旧顺序。
     */
    fun replacePartial(updated: CaptureEntry) {
        if (byTxn[updated.txnId] == null) return
        byTxn[updated.txnId] = updated
        entries.removeIf { it.txnId == updated.txnId }
        entries.addFirst(updated)
    }

    /** 加载当前 snapshot 列表, 顺序 = 最新→最旧。 */
    fun snapshot(): List<CaptureEntry> = entries.toList()

    /** 按 txnId 取单条。 */
    fun get(txnId: Long): CaptureEntry? = byTxn[txnId]

    /**
     * 全清。引用 requirements R1.4。
     */
    fun clear() {
        entries.clear()
        byTxn.clear()
    }

    /**
     * 触发 onTrimMemory 时把容量减半, 防止内存峰值逼近。
     * 引用 requirements R12.3 / design correctness 3。
     */
    fun trimToLowMemory() {
        val newCap = (capacity / 2).coerceAtLeast(10)
        capacity = newCap
        // 立即淘汰超出新容量的旧条目
        var extra = entries.size - capacity
        while (extra > 0) {
            val dropped = entries.pollLast() ?: break
            byTxn.remove(dropped.txnId)
            extra--
        }
    }

    /**
     * 改变运行时容量(UI 设置切换档位时调用)。
     */
    fun setCapacity(newCapacity: Int) {
        val safe = newCapacity.coerceAtLeast(1)
        capacity = safe
        var extra = entries.size - safe
        while (extra > 0) {
            val dropped = entries.pollLast() ?: break
            byTxn.remove(dropped.txnId)
            extra--
        }
    }

    /** 容量满即淘汰 tail 最旧条目, 返回被丢弃的 entry 或 null。 */
    private fun evictIfFull(): CaptureEntry? {
        // 容量边界检查放在等待写入路径前; 由于并发, 极小概率短暂超出 +1 再修正
        if (entries.size < capacity) return null
        val dropped = entries.pollLast() ?: return null
        byTxn.remove(dropped.txnId)
        return dropped
    }
}
