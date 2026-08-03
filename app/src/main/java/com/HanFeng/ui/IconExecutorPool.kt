package com.HanFeng.ui

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用级共享的 icon 加载线程池。所有 Activity/Adapter 共用，避免每个 List 页都新建 2 线程。
 * 用 daemon thread，hot path 不阻塞 JVM 关闭。
 */
internal object IconExecutorPool {
    private val workerId = AtomicInteger(0)

    val executor: Executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "icon-loader-${workerId.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
}
