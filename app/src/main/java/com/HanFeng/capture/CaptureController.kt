package com.HanFeng.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 抓包控制中枢。
 *
 * 设计要点:
 * - 唯一可信状态机, [Snapshot] 通过 [AtomicReference] + [MutableStateFlow] 对外只读发布(design Components #1)
 * - 旁路 4 个入口 [onDecodedRequest] / [onDecodedHttp2Headers] / [onDecodedResponse] / [onDecodedHttp2Body] 全部 try/catch + IO 线程, 不阻主线程
 * - 与广告拦截共用 [MitmGate] 同一套 CA, 不重新生成(design correctness 2)
 * - 抓包 close 不动 MITM 全局开关(design correctness 2 / requirements R3.4)
 *
 * 引用 design.md Components #1 与 requirements R2 / R3 / R4。
 */
object CaptureController {

    enum class Mode { BY_APP, ALL_APPS }

    /** 抓包启用 / 失败 / 错误的状态码。详情见 [enable]。 */
    enum class EnableResult { OK, PENDING_CERT }

    data class Snapshot(
        val active: Boolean,
        val mode: Mode,
        val targetApps: Set<String>,
        val maxEntries: Int,
        val bodyPreviewBytesAll: Int,
        val bodyPreviewBytesByApp: Int
    ) {
        companion object {
            val INITIAL = Snapshot(
                active = false,
                mode = Mode.BY_APP,
                targetApps = emptySet(),
                maxEntries = CaptureRingBuffer.DEFAULT_CAPACITY,
                bodyPreviewBytesAll = 8 * 1024,
                bodyPreviewBytesByApp = 32 * 1024
            )
        }
    }

    private val _current = MutableStateFlow(Snapshot.INITIAL)
    val current: StateFlow<Snapshot> = _current.asStateFlow()

    /**
     * 抓包条目 push 流, replay=0(订阅者只收订阅后的新条目),
     * 缓冲 512 在订阅者短暂 unsubscribed 时不丢要求 R4 持续进入的 entry。
     */
    private val _entries = MutableSharedFlow<CaptureEntry>(replay = 0, extraBufferCapacity = 512)
    val entries: SharedFlow<CaptureEntry> = _entries.asSharedFlow()

    /**
     * 断点命中事件流, replay=1 缓存最近命中(design correctness 11)。
     * 元素为 txnId; 订阅者(悬浮窗 + CaptureFragment)据此跳详情。
     */
    private val _breakpointHits = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 32)
    val breakpointHits: SharedFlow<Long> = _breakpointHits.asSharedFlow()

    /**
     * 窗口最近的 TLS 元数据, 由 [onTlsHandshakeComplete] 写入; key=txnId。
     * 内存窗口短期持有, 不进入 ring buffer 在 entry 写入前合并。
     */
    private val pendingTls = java.util.concurrent.ConcurrentHashMap<Long, TlsMeta>()

    /** 批次 D 防泄漏: 保留 enable 时获得的 ApplicationContext 弱引用, disable 时调 CaptureFloatingService.stop。 */
    private val appContextRef = java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<Context>>(java.lang.ref.WeakReference(null))

    /** 批次 E6d: 取 application context (enable 期间可用; disable 后返回 null)。供无 context 入参的旁路使用。 */
    private fun appContext(): Context? = appContextRef.get().get()

    /**
     * 批次 E1 历史落盘: 单线程 daemon executor 防止 CaptureStore 同步调用阻塞 worker。
     * 与 [StatsRepository] 同款模式; IO 写入 + LRU 清理都通过这里异步执行。
     */
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "CaptureController-Persist").apply { isDaemon = true }
    }

    /**
     * 抓包底层环形缓冲。由 [enable] 决定容量, 由 [disable] clear。
     * 默认实例化即停用态, capacity 任意值都 OK。
     */
    @Volatile
    private var ring: CaptureRingBuffer = CaptureRingBuffer()

    /** 可注入 [MitmGate]; 测试时换桩; 生产用 [DefaultMitmGate]。 */
    /** 可注入 [MitmGate]; 测试时换桩; 生产用 [DefaultMitmGate]。 */
    @Volatile
    var mitmGate: MitmGate = DefaultMitmGate()

    /**
     * 启用抓包。
     *
     * 流程:
     * 1. 校验本地 CA 与安装状态: 未就绪 → [EnableResult.PENDING_CERT], 不切 active(requirements R3.2)
     * 2. 共用 CA: 若 HTTPS MITM 全局开关未开, 强制打开(requirements R3.1)
     * 3. 切 [Snapshot] 到 active, 重建 [ring] 容量(关抓包后容量可能曾被调小)
     * 4. 不动广告拦截决策链路旁路语义(design correctness 1)
     *
     * 引用 requirements R3 / R2。返回 [EnableResult.OK] 时已成功进入 active。
     */
    fun enable(
        context: Context,
        mode: Mode,
        targetApps: Set<String>,
        maxEntries: Int = CaptureRingBuffer.DEFAULT_CAPACITY,
        /** 批次 D 联动: 50/70/90 三档 UI 抽象回填字节上限; -1 = 保留当前。 */
        bodyPreviewBytesAll: Int = -1,
        bodyPreviewBytesByApp: Int = -1
    ): EnableResult {
        // 批次 D 防泄漏: 缓存 ApplicationContext 弱引用(供 disable 调浮窗 stop)
        appContextRef.set(java.lang.ref.WeakReference(context.applicationContext ?: context))
        // 1. 证书检查
        //    前置同步: 抓包不需要 Shizuku/root, 用户装好用户证书(或 root 装系统证书)即可用.
        //    syncInstalledState 会从 AndroidCAStore 探测已安装的 CA 并置位 cert_installed,
        //    否则 SP 里 stale 的 false 会让用户在"已装证书"后仍被误报"证书未就绪".
        runCatching {
            com.HanFeng.security.CertificateAuthorityManager.syncInstalledState(
                context.applicationContext ?: context
            )
        }
        if (!mitmGate.isCertificateReady(context)) {
            // CA 尚未生成: 自动生成并导出到 Downloads, 引导用户装一次用户证书 (免 root).
            // 生成失败才返回 PENDING_CERT; 生成成功但还没装 → 同样 PENDING_CERT + 提示装证书.
            runCatching {
                val appCtx = context.applicationContext ?: context
                com.HanFeng.security.CertificateAuthorityManager.ensureCaInstalledFiles(appCtx)
                    .onSuccess { cert ->
                        com.HanFeng.data.HttpsMitmRepository.saveCertificateExportPath(appCtx, cert.downloadDisplayPath)
                        com.HanFeng.data.HttpsMitmRepository.clearRuntimeState(appCtx)
                        com.HanFeng.security.CertificateAuthorityManager.syncInstalledState(appCtx)
                    }
            }
        }
        if (!mitmGate.isCertificateReady(context)) {
            _current.value = _current.value.copy(active = false, mode = mode, targetApps = targetApps)
            return EnableResult.PENDING_CERT
        }
        if (!mitmGate.isCertificateInstalled(context)) {
            _current.value = _current.value.copy(active = false, mode = mode, targetApps = targetApps)
            return EnableResult.PENDING_CERT
        }
        // 2. 强制启用 HTTPS MITM, 共用现有 CA
        if (!mitmGate.isHttpDecryptEnabled(context)) {
            mitmGate.setHttpDecryptEnabled(context, true)
        }
        // 3. 切 active, 重建 ring buffer
        ring = CaptureRingBuffer(initialCapacity = maxEntries.coerceAtLeast(1))
        val newAll = if (bodyPreviewBytesAll > 0) bodyPreviewBytesAll else _current.value.bodyPreviewBytesAll
        val newByApp = if (bodyPreviewBytesByApp > 0) bodyPreviewBytesByApp else _current.value.bodyPreviewBytesByApp
        val wasActive = _current.value.active
        _current.value = _current.value.copy(
            active = true,
            mode = mode,
            targetApps = if (mode == Mode.ALL_APPS) emptySet() else targetApps,
            maxEntries = maxEntries,
            bodyPreviewBytesAll = newAll,
            bodyPreviewBytesByApp = newByApp
        )
        // 4. 让 VPN 重建路由: 全量 MITM 路由在接口重建时才生效 (见 AdBlockVpnService.resolveFullCaptureRouteMode).
        //    未运行则启动拦截管线 (广告拦截需开启); 运行中则无缝 reload。
        if (!wasActive) {
            val action = runCatching {
                val ctx = context.applicationContext ?: context
                if (com.HanFeng.core.network.NetworkKernel.isRunning()) {
                    com.HanFeng.core.network.NetworkKernel.reload(ctx)
                    "reload"
                } else {
                    com.HanFeng.core.network.NetworkKernel.start(ctx, userInitiated = true)
                    "start"
                }
            }.getOrElse { t ->
                com.HanFeng.data.LogRepository.append(
                    context.applicationContext ?: context,
                    "抓包启用: VPN 联动失败 ${t.message ?: t.javaClass.simpleName}"
                )
                "error"
            }
            if (action != "error") {
                com.HanFeng.data.LogRepository.append(
                    context.applicationContext ?: context,
                    "抓包启用: mode=${mode} 已切 active, VPN $action 完成, 全量 MITM 路由在接口重建后生效 (广告拦截需开启)"
                )
            }
        }
        return EnableResult.OK
    }

    /** 批次 D 联动: 设置页"应用"按钮调用, 即时刷新 body preview 上限 — 仅影响后续入 ring 新 entry。 */
    fun updateBodyPreviewCaps(all: Int, byApp: Int) {
        _current.value = _current.value.copy(
            bodyPreviewBytesAll = all.coerceAtLeast(512),
            bodyPreviewBytesByApp = byApp.coerceAtLeast(512)
        )
    }

    /**
     * 关闭抓包。清空 ring buffer 与所有断点规则并切回 inactive。
     *
     * 严格不动 HTTPS MITM 全局开关(requirements R3.4)。
     */
    fun disable() {
        // 顺序: 先关 UI 浮窗(防 disable 后 ring 已清空但浮窗还在响应订阅) → 清 ring → 清 pending TLS → 清断点 → 切 inactive
        runCatching {
            val ctx = appContextRef.get().get()
            if (ctx != null) com.HanFeng.service.CaptureFloatingService.stop(ctx)
        }
        ring.clear()
        pendingTls.clear()
        BreakpointRepo.clearAll()
        _current.value = _current.value.copy(active = false, targetApps = emptySet())
        // 关抓包后让 VPN 重建路由: 撤掉全局全量 MITM 路由, 回到仅按域名解密/直通 (R3.4 不动 MITM 全局开关)。
        runCatching {
            val ctx = appContextRef.get().get()
            if (ctx != null) {
                com.HanFeng.core.network.NetworkKernel.reloadIfRunning(ctx)
                com.HanFeng.data.LogRepository.append(ctx, "抓包关闭: 已触发 VPN 路由重建, 全量 MITM 路由已撤下")
            }
        }
    }

    /**
     * VPN 启动时调用, 让抓包配置跨 VPN 重启恢复(design 现有组件改动表 / requirements R3.5)。
     *
     * 行为:
     * - 若持久化开关为 true, 则按持久化 mode/targetApps/maxEntries 重新 enable 抓包
     * - 若当前 inactive 但持久化 enabled=true 且证书已就绪, 则启用; 证书未就绪则维持 inactive
     * - 不主动关 HTTPS MITM 全局开关
     */
    fun syncFromPrefs(context: Context) {
        val cfg = CaptureRepository.loadConfig(context)
        if (!cfg.enabled) {
            // 持久化关闭: 维持 inactive
            if (_current.value.active) disable()
            return
        }
        if (_current.value.active) return
        // 持久化为开启但当前 inactive: 尝试 enable
        enable(
            context = context,
            mode = cfg.mode,
            targetApps = cfg.targetApps,
            maxEntries = cfg.maxEntries,
            bodyPreviewBytesAll = cfg.bodyPreviewBytesAll,
            bodyPreviewBytesByApp = cfg.bodyPreviewBytesByApp
        )
        // 重建 ring buffer 后预填上次快照(注: 为避免新旧 txnId 冲突, 仅恢复正文/状态, 不再过 active 流水线)
        val recovered = CaptureRepository.loadSnapshot(context)
        recovered.forEach { insertReplayedEntry(it) }
        // 缺口 1: VPN 重启后从 prefs 恢复断点规则(design correctness 4)
        val rules = CaptureRepository.loadBreakpointRules(context)
        if (rules.isNotEmpty()) BreakpointRepo.restoreRules(rules)
    }

    /** 在 VPN 关闭或周期 flush 时把当前快照写盘(由 VPN Service 调起)。 */
    fun flushSnapshotToPrefs(context: Context) {
        if (!_current.value.active) {
            CaptureRepository.clearSnapshot(context)
            return
        }
        CaptureRepository.snapshotRingBuffer(context, ring.snapshot())
        // 缺口 1: 一并把断点规则写盘, 避免被 VPN 关停丢规则
        CaptureRepository.snapshotBreakpointRules(context, BreakpointRepo.snapshotRules())
    }

    /** 当前快照列表, 顺序 = 最新→最旧。 inactive 时返回空。 */
    fun snapshot(): List<CaptureEntry> = ring.snapshot()

    /** 按 txnId 取单条; 命中则返回。 */
    fun get(txnId: Long): CaptureEntry? = ring.get(txnId)

    /** 测试/降级用: 触发 onTrimMemory 时减半 ring buffer(design correctness 3)。 */
    fun onTrimMemoryLow() {
        ring.trimToLowMemory()
    }

    // ==================== 旁路入口 (4 个 decode-on-success) ====================

    /**
     * HTTP/1.1 请求解码完成旁路。
     *
     * - 当前 inactive → 静默返回, 不采样(design correctness 1)
     * - 当前 active 但模式为 BY_APP 且 targetApps 不含 packageName → 跳过(requirements R2.2)
     * - 写入 ring buffer 的"仅请求阶段"条目; emit 到 [entries] Flow 通知订阅者
     *
     * 默认行为只读; 若 [BreakpointRepo] 命中该 host+method 会挂起等待用户裁决,
     * 超时 30s 自动透传(详见 [BreakpointRepo.awaitResume])。
     *
     * 引用 requirements R4 / R8 / design 旁路接入点表。
     */
    fun onDecodedRequest(
        method: String,
        host: String,
        path: String,
        scheme: String,
        httpVersion: String,
        requestHeaders: Map<String, String>,
        requestBodyPreview: ByteArray?,
        requestBodyTruncated: Boolean,
        appName: String?,
        packageName: String?,
        tlsMetaOverride: TlsMeta? = null,
        /** 批次 C3: 多会话隔离 sessionId = TlsMitmSession.flowKey, 默认空串兼容重放等无 session 路径。 */
        sessionId: String = ""
    ): RequestOutcome {
        val s = _current.value
        if (!s.active) return RequestOutcome.Inactive
        // BY_APP 模式: appName 与 packageName 任一命中即采样(design R2.2, 满足 TlsMitmSession 仅持 appName 的现实)。
        if (s.mode == Mode.BY_APP && !s.targetApps.contains(appName) && !s.targetApps.contains(packageName)) return RequestOutcome.Inactive

        val txnId = ring.nextTxnId()
        val previewCap = if (s.mode == Mode.ALL_APPS) s.bodyPreviewBytesAll else s.bodyPreviewBytesByApp
        val cappedBody = capPreview(requestBodyPreview, previewCap)
        val entry = CaptureEntry(
            txnId = txnId,
            timestampMs = System.currentTimeMillis(),
            appName = appName,
            packageName = packageName,
            scheme = scheme,
            method = method,
            host = host,
            path = path,
            httpVersion = httpVersion,
            requestHeaders = requestHeaders,
            requestBodyPreview = cappedBody.first,
            requestBodyTruncated = cappedBody.second,
            responseStatus = 0,
            responseHeaders = emptyMap(),
            responseBodyPreview = null,
            responseBodyTruncated = false,
            durationMs = 0,
            error = null,
            intercepted = false,
            tlsMeta = tlsMetaOverride ?: pendingTls.remove(txnId),
            replayed = false,
            sessionId = sessionId
        )
        ring.putRequest(entry)
        emitEntry(entry)
        appContext()?.let { persistEntry(it, entry) }
        // 批次 E6d: 持久规则优先评估 — 命中后无需挂起等待用户裁决, 直接执行 action (PassThrough/ReplaceWith/Drop)
        val ctx = appContext()
        val persistedRules = runCatching { if (ctx != null) BreakpointRuleRepository.load(ctx) else emptyList() }.getOrDefault(emptyList())
        val persistedAction = BreakpointRuleMatcher.resolveRequest(
            rules = persistedRules,
            host = host,
            method = method,
            path = path,
            contentType = requestHeaders.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        )
        if (persistedAction != null) {
            return RequestOutcome.Pending(txnId = txnId, action = persistedAction)
        }
        // 缺口 2: 实时断点路径 — 命中后 await 用户裁决, 草稿在 resumeFromBreakpoint() 时合入
        val rule = BreakpointRepo.matchRequest(host, method, path)
        if (rule != null) {
            _breakpointHits.tryEmit(txnId)
            val action = BreakpointRepo.awaitResumeBlocking(txnId)
            return RequestOutcome.Pending(txnId = txnId, action = action)
        }
        return RequestOutcome.Pending(txnId = txnId, action = BreakpointAction.PassThrough(useOriginal = true))
    }

    /**
     * HTTP/1.1 / HTTP/2 响应解码完成旁路。
     *
     * - 把 phase=Request 的 entry 补全 responseStatus / responseHeaders / responseBodyPreview
     * - 同样受 BY_APP 模式保护(若 entry 已在 ring buffer 中, 则其 packageName 已匹配过)
     * - 断点命中时挂起 await, 默认透传原响应
     */
    fun onDecodedResponse(
        txnId: Long,
        responseStatus: Int,
        responseHeaders: Map<String, String>,
        responseBodyPreview: ByteArray?,
        responseBodyTruncated: Boolean,
        durationMs: Long,
        intercepted: Boolean
    ): ResponseOutcome {
        val s = _current.value
        if (!s.active) return ResponseOutcome.Inactive

        val previewCap = if (s.mode == Mode.ALL_APPS) s.bodyPreviewBytesAll else s.bodyPreviewBytesByApp
        val cappedBody = capPreview(responseBodyPreview, previewCap)
        ring.putResponse(
            txnId = txnId,
            responseStatus = responseStatus,
            responseHeaders = responseHeaders,
            responseBodyPreview = cappedBody.first,
            responseBodyTruncated = cappedBody.second,
            durationMs = durationMs,
            intercepted = intercepted
        )
        ring.get(txnId)?.let { emitEntry(it) }
        val ctx = appContext()
        ctx?.let {
            persistResponse(
                it, txnId, responseStatus, responseHeaders,
                cappedBody.first, cappedBody.second, durationMs, intercepted
            )
        }

        val existing = ring.get(txnId) ?: return ResponseOutcome.NotFound
        // 批次 E6d: 持久规则优先评估 (响应方向), 命中直接执行, 不挂起
        val persistedRules = runCatching { if (ctx != null) BreakpointRuleRepository.load(ctx) else emptyList() }.getOrDefault(emptyList())
        val persistedAction = BreakpointRuleMatcher.resolveResponse(
            rules = persistedRules,
            host = existing.host,
            method = existing.method,
            path = existing.path,
            responseStatus = existing.responseStatus,
            contentType = responseHeaders.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        )
        if (persistedAction != null) {
            return ResponseOutcome.Pending(txnId = txnId, action = persistedAction)
        }
        // 缺口 2: 实时断点路径, 命中挂起 await 用户裁决
        val rule = BreakpointRepo.matchResponse(existing.host, existing.method, existing.path)
        if (rule != null) {
            _breakpointHits.tryEmit(txnId)
            val action = BreakpointRepo.awaitResumeBlocking(txnId)
            return ResponseOutcome.Pending(txnId = txnId, action = action)
        }
        return ResponseOutcome.Pending(txnId = txnId, action = BreakpointAction.PassThrough(useOriginal = true))
    }

    /**
     * HTTP/2 请求头部解码旁路: 与 [onDecodedRequest] 结构相同, 但通过 [Http2HeaderInspection]
     * 输入字段而非手解。仅采集, 不再 await(请求方向断点链路在 [onDecodedRequest]
     * 由 path/method/host 决定命中, [BreakpointRepo.matchRequest] 复用)。
     */
    fun onDecodedHttp2Headers(
        method: String?,
        host: String,
        path: String?,
        scheme: String,
        httpVersion: String,
        requestHeaders: Map<String, String>,
        appName: String?,
        packageName: String?,
        tlsMetaOverride: TlsMeta? = null,
        /** 批次 C3: 透传 H2 会话 = TlsMitmSession.flowKey。 */
        sessionId: String = ""
    ): RequestOutcome {
        return onDecodedRequest(
            method = method ?: "GET",
            host = host,
            path = path ?: "/",
            scheme = scheme,
            httpVersion = httpVersion,
            requestHeaders = requestHeaders,
            requestBodyPreview = null,
            requestBodyTruncated = false,
            appName = appName,
            packageName = packageName,
            tlsMetaOverride = tlsMetaOverride,
            sessionId = sessionId
        )
    }

    /**
     * HTTP/2 数据帧解码旁路(只读采样写入, 无断点)。
     */
    fun onDecodedHttp2Body(
        txnId: Long,
        sample: ByteArray?
    ) {
        val s = _current.value
        if (!s.active) return
        // HTTP/2 数据帧已分片, 这里仅打标记当 bytesPreview 补充用; design 接入点表明确"仅只读采样写入"
        val existing = ring.get(txnId) ?: return
        if (existing.responseStatus == 0 && sample != null) {
            val previewCap = if (s.mode == Mode.ALL_APPS) s.bodyPreviewBytesAll else s.bodyPreviewBytesByApp
            val capped = capPreview(sample, previewCap)
            // HTTP/2 body 还没有响应字段可补全, 严格意义上不在本接口里更新; 这里只保留前缀首段
            @Suppress("UNUSED_VARIABLE")
            val unused = capped
        }
    }

    /**
     * TLS/MITM 连接级旁路。HTTP 解码失败或被 bypass 时也写一条 CONNECT 记录,
     * 让抓包页展示目标域名和失败原因, 避免用户看到"暂无抓包条目"。
     */
    fun onTlsConnectionEvent(
        context: Context,
        host: String,
        appName: String?,
        packageName: String?,
        targetIp: String?,
        targetPort: Int,
        flowKey: String,
        error: String?
    ) {
        val s = _current.value
        if (!s.active) return
        if (s.mode == Mode.BY_APP && !s.targetApps.contains(appName) && !s.targetApps.contains(packageName)) return
        val txnId = ring.nextTxnId()
        val entry = CaptureEntry(
            txnId = txnId,
            timestampMs = System.currentTimeMillis(),
            appName = appName,
            packageName = packageName,
            scheme = "https",
            method = "CONNECT",
            host = host,
            path = if (targetIp.isNullOrBlank()) ":$targetPort" else "$targetIp:$targetPort",
            httpVersion = "TLS",
            requestHeaders = buildMap {
                put("sni", host)
                if (!targetIp.isNullOrBlank()) put("target-ip", targetIp)
                put("target-port", targetPort.toString())
                if (!error.isNullOrBlank()) put("mitm-bypass", error)
            },
            requestBodyPreview = null,
            requestBodyTruncated = false,
            responseStatus = 0,
            responseHeaders = emptyMap(),
            responseBodyPreview = null,
            responseBodyTruncated = false,
            durationMs = 0,
            error = error,
            intercepted = false,
            tlsMeta = TlsMeta(
                sni = host,
                protocol = "TLS",
                cipherSuite = "unknown",
                alpn = null,
                peerCertificates = emptyList(),
                error = error
            ),
            replayed = false,
            sessionId = flowKey
        )
        ring.putRequest(entry)
        emitEntry(entry)
        persistEntry(context.applicationContext ?: context, entry)
    }

    // ==================== 批次 E1 历史落盘编排 ====================

    /** 把当前 ring 中 entry 持久化到 [CaptureStore](异步)。 */
    private fun persistEntry(context: Context, e: CaptureEntry) {
        val ctx = context.applicationContext ?: context
        persistExecutor.execute {
            try {
                CaptureStore.upsertEntry(ctx, e)
                if (e.requestBodyPreview != null && e.requestBodyPreview.isNotEmpty() && !e.requestBodyTruncated) {
                    // 仅当 body 未截断(完整 ≤ preview 上限)时入体; 否则留给 E2 流式拼装。
                    CaptureStore.putBodyChunked(ctx, e.txnId, CaptureChunkDirection.REQUEST, e.requestBodyPreview)
                }
                applyStoreRetention(ctx)
            } catch (_: Throwable) {
                // 落盘失败不影响抓包主路(Boxed); 仅静默吞异常。
            }
        }
    }

    /** 补丁响应字段到大库 + 入响应 body(若非截断)。 */
    private fun persistResponse(
        context: Context,
        txnId: Long,
        responseStatus: Int,
        responseHeaders: Map<String, String>,
        responseBodyPreview: ByteArray?,
        responseBodyTruncated: Boolean,
        durationMs: Long,
        intercepted: Boolean
    ) {
        val ctx = context.applicationContext ?: context
        persistExecutor.execute {
            try {
                CaptureStore.upsertResponse(
                    ctx,
                    txnId = txnId,
                    responseStatus = responseStatus,
                    responseHeaders = responseHeaders,
                    responseBodyPreview = responseBodyPreview,
                    responseBodyTruncated = responseBodyTruncated,
                    durationMs = durationMs,
                    intercepted = intercepted
                )
                if (responseBodyPreview != null && responseBodyPreview.isNotEmpty() && !responseBodyTruncated) {
                    // upsertResponse 已把 body 写为 CHUNK; 此处不再重复加 chunk
                }
            } catch (_: Throwable) { }
        }
    }

    /**
     * 批次 E2 大响应跨 DATA 帧拼接整流入口。
     *
     * 由 [HttpsTlsBridgeManager.logHttp2Events] 在响应方向 endStream 时调用一次,
     * body 是该 stream 已累计的完整响应字节(上限 [HTTPS_TLS_H2_FULL_BODY_LIMIT_BYTES])。
     *
     * sessionId 用于定位该 H2 stream 对应的 capture entry:
     * - 在请求方向 onDecodedHttp2Headers 时 [CaptureEntry.sessionId] = session.flowKey + ':' + streamId
     *   (这要求 onDecoded 时把 sessionId 写为 "flowKey:streamId" 而非仅 flowKey —— 见 E2 commit hook 同步修)
     * 若实际入参仅 session.flowKey(无 streamId), 这里退化为按 sessionId 取最近 entry 写入 response body。
     *
     * 调用方线程: HttpsTlsBridgeManager worker; 此处立即投递到 [persistExecutor],
     * 主路不被同步 SQLite 阻塞。引 design correctness 14 / E1.2。
     */
    fun onH2CompleteBody(
        context: Context,
        sessionId: String,
        streamId: Int,
        body: ByteArray,
        truncated: Boolean
    ) {
        val ctx = context.applicationContext ?: context
        persistExecutor.execute {
            try {
                // 找到该 sessionId 对应的最新 in-flight entry (response 未补则 responseStatus==0, 或已补 ring 但 body 走 chunked)
                val candidates = ring.snapshot().filter {
                    it.sessionId.startsWith(sessionId, ignoreCase = true)
                }
                val target = candidates.firstOrNull { it.responseStatus == 0 }
                    ?: candidates.firstOrNull()
                if (target == null) {
                    // ring 已 evict; 走 store list paging 找 sessionId 最近一条
                    val persisted = CaptureStore.list(ctx, offset = 0, limit = 5, session = sessionId)
                    persisted.firstOrNull()?.let {
                        if (it.responseBodyTruncated) {
                            CaptureStore.upsertResponse(
                                ctx,
                                txnId = it.txnId,
                                responseStatus = it.responseStatus,
                                responseHeaders = it.responseHeaders,
                                responseBodyPreview = body,
                                responseBodyTruncated = truncated,
                                durationMs = it.durationMs,
                                intercepted = it.intercepted
                            )
                        }
                    }
                } else {
                    CaptureStore.upsertResponse(
                        ctx,
                        txnId = target.txnId,
                        responseStatus = target.responseStatus,
                        responseHeaders = target.responseHeaders,
                        responseBodyPreview = body,
                        responseBodyTruncated = truncated,
                        durationMs = target.durationMs,
                        intercepted = target.intercepted
                    )
                }
            } catch (_: Throwable) { /* best effort */ }
        }
    }

    /**
     * 批次 E3: WebSocket Upgrade 后整流 byte-feed 入 body_chunks(RESPONSE direction)。
     *
     * WS demo 帧按 RFC6455 是 mask'd 客户端→服务端, server→客户端不带 mask; 与其它方向无关
     * 我们仅持久化原始字节; 详情页 TreeView 调用 [WsGrpcFrameDecoder] 提供解码视图。
     *
     * txnId 取自 capture 请求阶段的 captureTxnRef (HTBM 传入)。raw 字节长度不限,
     * CaptureStore.upsertResponse chunked writer 自动按 [CaptureDb.CHUNK_SIZE] 拆块。
     *
     * 引 design correctness 14 / E1.2 / E2 模式。
     */
    fun onWsDataFrame(
        context: Context,
        txnId: Long,
        raw: ByteArray
    ) {
        if (raw.isEmpty()) return
        val ctx = context.applicationContext ?: context
        persistExecutor.execute {
            try {
                val existing = CaptureStore.get(ctx, txnId)
                if (existing == null) {
                    com.HanFeng.data.LogRepository.append(ctx, "WS frame unknown txnId=$txnId dropped")
                    return@execute
                }
                // 把 raw 追加到现有响应 body(连续 WS frame) — 用 putBodyChunked 即可
                val prev = CaptureStore.readBody(ctx, txnId, CaptureChunkDirection.RESPONSE)
                val combined = prev + raw
                // 清旧 + 重写
                CaptureStore.upsertResponse(
                    ctx,
                    txnId = txnId,
                    responseStatus = existing.responseStatus,
                    responseHeaders = existing.responseHeaders,
                    responseBodyPreview = combined,
                    responseBodyTruncated = false,
                    durationMs = existing.durationMs,
                    intercepted = existing.intercepted
                )
            } catch (_: Throwable) { /* best effort */ }
        }
    }

    /**
     * 应用持久化层 retention(用户可配 maxEntries / maxAgeDays)。
     *  - maxAgeDays > 0 → 删 ALL_ENTRIES < cutoffMs
     *  - maxEntries > 0 → LRU trimToMaxEntries
     * 在 persistExecutor 异步; 单线程串行避免并发覆写。
     */
    fun applyStoreRetention(context: Context) {
        val cfg = CaptureRepository.loadConfig(context)
        val maxAgeDays = cfg.maxAgeDays
        val maxEntries = cfg.maxStoreEntries
        if (maxAgeDays > 0) {
            val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 3600L * 1000L
            CaptureStore.deleteOlderThan(context, cutoff)
        }
        if (maxEntries > 0) {
            CaptureStore.trimToMaxEntries(context, maxEntries)
        }
    }

    /**
     * 批次 E1.3: 列表查询合并 ring + 持久层 pr
     * - ring 是热活最新 N 条
     * - 持久层是从 ring 移除 evicted 后(如有)的旧条目
     */
    fun listPersisted(context: Context, offset: Int = 0, limit: Int = 500): List<CaptureEntry> {
        return try {
            CaptureStore.list(context.applicationContext ?: context, offset = offset, limit = limit)
        } catch (_: Throwable) { emptyList() }
    }

    /** E1: 重启清理, 关掉 executor 与 helper 并 close db。 供 VPN 终结 / 服务回收时调用。 */
    fun shutdownPersist() {
        persistExecutor.shutdownNow()
    }

    /**
     * TLS 握手完成旁路(requirements R11.2)。frame 元数据合并到下一条 entry 写入时取走。
     */
    fun onTlsHandshakeComplete(
        txnId: Long,
        sni: String?,
        protocol: String,
        cipherSuite: String,
        alpn: String?,
        peerCertificates: List<CertMeta>,
        error: String? = null
    ) {
        val meta = TlsMeta(
            sni = sni,
            protocol = protocol,
            cipherSuite = cipherSuite,
            alpn = alpn,
            peerCertificates = peerCertificates,
            error = error
        )
        // 1. 合并到现存 entry(若有)
        val existing = ring.get(txnId)
        if (existing != null) {
            val updated = existing.copy(tlsMeta = meta)
            ring.replacePartial(updated)
            emitEntry(updated)
        } else {
            // 2. 暂存到 pendingTls, 待 [onDecodedRequest] 取走合并
            pendingTls[txnId] = meta
            if (pendingTls.size > 64) {
                // 防并发泄漏: 短期内 TLS 完成但无对应请求 → 清最旧
                val oldest = pendingTls.keys.first()
                pendingTls.remove(oldest)
            }
        }
    }

    // ==================== 断点回复 ====================

    /**
     * 详情页应用断点三类动作(requirements R8.3)。
     * 替换字节 + headers/statusLine override 仅在 [BreakpointAction.ReplaceWith] 时生效。
     */
    fun resumeFromBreakpoint(txnId: Long, action: BreakpointAction): Boolean {
        return BreakpointRepo.resolve(txnId, action)
    }

    // ==================== 断点规则管理(缺口 1 GUI 调用) ====================

    /**
     * 添加一条断点命中规则并立即落盘到 prefs。
     * - GUI 入口: 详情页 "下次该 request/response 命中暂停" 按钮 -> addBreakpointRule + context
     * - 不要求抓包处于 active 状态, 但 inactive 下无限效果直到开启
     */
    fun addBreakpointRule(context: Context, rule: BreakpointMatchRule) {
        BreakpointRepo.addRule(rule)
        CaptureRepository.snapshotBreakpointRules(context, BreakpointRepo.snapshotRules())
    }

    /** 删除一条规则并立即落盘。 */
    fun removeBreakpointRule(context: Context, rule: BreakpointMatchRule) {
        BreakpointRepo.removeRule(rule)
        CaptureRepository.snapshotBreakpointRules(context, BreakpointRepo.snapshotRules())
    }

    /** 取所有规则的快照(断点管理列表展示)。 */
    fun breakpointRules(): List<BreakpointMatchRule> = BreakpointRepo.snapshotRules()

    // ==================== 内部 helper ====================

    /**
     * 直接插入一条预组装好的 entry(供 [CaptureReplayEngine] 重放使用)。
     * - 仅在 active 时进入 ring buffer; inactive 时丢弃
     * - txnId 由调用方通过 [CaptureEntry.makeReplayTxnId] 分配命名空间隔离
     *
     * 引用 design correctness 5。
     */
    fun insertReplayedEntry(entry: CaptureEntry) {
        val s = _current.value
        if (!s.active) return
        ring.putRequest(entry)
        emitEntry(entry)
    }

    private fun emitEntry(entry: CaptureEntry) {
        _entries.tryEmit(entry)
    }

    /** body 字节数组按 mode 容量预览截断。 */
    private fun capPreview(body: ByteArray?, cap: Int): Pair<ByteArray?, Boolean> {
        if (body == null) return null to false
        if (body.size <= cap) return body to false
        return body.copyOf(cap) to true
    }

    // 测试 helper: 直接覆盖 ring buffer(单元测试用)
    internal fun setRingForTesting(ring: CaptureRingBuffer) {
        this.ring = ring
    }
}
