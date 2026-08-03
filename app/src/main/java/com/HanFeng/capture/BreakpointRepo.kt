package com.HanFeng.capture

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 断点规则库 + 命中后挂起 IO 线程等待用户裁决的协调器。
 *
 * - 规则集 [rules] 仅内存, 关抓包即由 [CaptureController.disable] 调 [clearAll] 清空(design correctness 4)
 * - 命中某 txnId 后, [CaptureController] 调 [awaitResume] 挂起 30s 硬超时, 超时自动透传(design correctness 13)
 * - 用户在详情页三选一调 [resolve], [awaitResume] 返回对应动作; 详见 design Components #5
 * - 复用 [BreakpointMatchRule.match] 做大小写不敏感匹配
 *
 * 引用 requirements R8 / design Components #5。
 */
object BreakpointRepo {

    /** 断点等待硬超时, 30s 自动透传避免 VPN 死锁(design correctness 13)。 */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    private val rules: CopyOnWriteArraySet<BreakpointMatchRule> = CopyOnWriteArraySet()

    /** 每个挂起的 txnId 对应一个延迟响应, 用户调 [resolve] 后 complete。 */
    private val pending =
        ConcurrentHashMap<Long, CompletableDeferred<BreakpointAction>>()

    fun addRule(rule: BreakpointMatchRule) {
        rules.add(rule)
    }

    fun removeRule(rule: BreakpointMatchRule) {
        rules.remove(rule)
    }

    fun hasRules(): Boolean = rules.isNotEmpty()

    /** 匹配请求方向; 任一 [BreakpointKind.REQUEST] 规则命中即返回。 */
    fun matchRequest(host: String, method: String, path: String): BreakpointMatchRule? =
        rules.firstOrNull { it.kind == BreakpointKind.REQUEST && it.match(host, method, path) }

    /** 匹配响应方向; 任一 [BreakpointKind.RESPONSE] 规则命中即返回。 */
    fun matchResponse(host: String, method: String, path: String): BreakpointMatchRule? =
        rules.firstOrNull { it.kind == BreakpointKind.RESPONSE && it.match(host, method, path) }

    /**
     * 阻塞 IO 线程直到用户裁决或 [DEFAULT_TIMEOUT_MS] 超时。
     *
     * 超时返回 [BreakpointAction.PassThrough](useOriginal=true), 防 VPN 死锁(design correctness 13)。
     * 在 HttpMitmFilter worker 线程上调用, 用 [kotlinx.coroutines.runBlocking] 包裹 deferred.await。
     */
    fun awaitResumeBlocking(txnId: Long): BreakpointAction {
        val deferred = CompletableDeferred<BreakpointAction>()
        pending[txnId] = deferred
        val result = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(DEFAULT_TIMEOUT_MS) { deferred.await() }
        }
        pending.remove(txnId)
        return result ?: BreakpointAction.PassThrough(useOriginal = true)
    }

    /** 挂起版本: 供协程上下文调用, 与 [awaitResumeBlocking] 共享同一 pending 表。 */
    suspend fun awaitResume(txnId: Long): BreakpointAction {
        val deferred = CompletableDeferred<BreakpointAction>()
        pending[txnId] = deferred
        val result = kotlinx.coroutines.withTimeoutOrNull(DEFAULT_TIMEOUT_MS) { deferred.await() }
        pending.remove(txnId)
        return result ?: BreakpointAction.PassThrough(useOriginal = true)
    }

    /**
     * 用户在详情页应用裁决, 唤醒对应挂起的 IO 线程。
     * - txnId 不在 pending 中(已被超时或双重 resolve) 返回 false
     * - 成功唤醒返回 true; 调用方据 Drop/ReplaceWith/PassThrough 决定后续行为
     */
    fun resolve(txnId: Long, action: BreakpointAction): Boolean {
        val deferred = pending.remove(txnId) ?: return false
        return deferred.complete(action)
    }

    /** 关抓包时清空所有规则与挂起。对被挂起的 txnId 立即透传。 */
    fun clearAll() {
        rules.clear()
        val snapshot = pending.values.toList()
        pending.clear()
        snapshot.forEach { it.complete(BreakpointAction.PassThrough(useOriginal = true)) }
    }

    /** 取当前所有规则的快照, 供 prefs 序列化(缺口 1 持久化)。 */
    fun snapshotRules(): List<BreakpointMatchRule> = rules.toList()

    /** 用 prefs 反序列化的规则集回填(design correctness 4: VPN 重启后规则不丢)。 */
    fun restoreRules(snap: Collection<BreakpointMatchRule>) {
        rules.clear()
        rules.addAll(snap)
    }

    /** 删除由用户在断点管理界面指定的命中条件。 */
    fun replaceAllRules(snap: Collection<BreakpointMatchRule>) {
        rules.clear()
        rules.addAll(snap)
    }
}
