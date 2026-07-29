package com.HanFeng.core.network

/**
 * 触发 SNI 解析 / QUIC 分析 / TLS MITM 的端口集合。
 * 默认包含业内常见 TLS 端口集：标准 443、HTTP/3 的 8443、GCM 用的 5228、Caddy/Cloudflare 用的 2052-2096 等。
 * 配合 [isTlsPort] / [isQuicPort] 统一化全工程端口判定，避免每个调用点写死 443。
 */
object TlsPortSet {

    /** 默认监听 TLS / QUIC / HTTP/3 流量的目标端口集合，覆盖主流广告 SDK 与 CDN 端点 */
    private val DEFAULT_PORTS: Set<Int> = setOf(
        443,    // 标准 HTTPS / HTTP/3
        8443,   // 替代 HTTPS（许多内网/企业 SDK 用）
        2083,   // Cloudflare HTTPS Wallet
        2087,   // Cloudflare HTTPS cPanel
        2096,   // Cloudflare HTTPS WebHost Manager
        2052,   // Cloudflare HTTP/3
        2053,   // Cloudflare HTTPS WebDisk
        5228,   // GCM/Firebase Cloud Messaging（Google 自家广告/分析 SDK 通道）
        5229,   // GCM fallback
        5230    // GCM fallback
    )

    /** 当前生效端口集合（@Volatile 以保证 match 时不需加锁） */
    @Volatile
    private var ports: Set<Int> = DEFAULT_PORTS

    /** 取当前生效端口集合的只读视图。 */
    fun ports(): Set<Int> = ports

    /** 替换生效端口集合。任意 caller 在用户配置变更时调用。 */
    fun update(newPorts: Set<Int>) {
        val effective = if (newPorts.isEmpty()) DEFAULT_PORTS else newPorts
        ports = effective.toSet()
    }

    /** 是否为应触发 TLS SNI 解析 / TLS MITM 的 TCP 目标端口 */
    fun isTlsTcpPort(port: Int): Boolean = port in ports

    /** 是否为应触发 QUIC Initial 解析的 UDP 目标端口。当前等同 TLS 端口集，HTTP/3 也在这些端口。 */
    fun isQuicUdpPort(port: Int): Boolean = port in ports

    /** 默认是否启用过任意高位端口（>443）。用于 UI 显示"扩展端口启用"提醒 */
    fun hasHighPorts(): Boolean = ports.any { it > 443 }
}
