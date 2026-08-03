package com.HanFeng.capture

import android.content.Context
import com.HanFeng.data.ShizukuAdControlRepository
import com.HanFeng.data.ShizukuEnhanceRepository

/**
 * 抓包 → 广告拦截 联动桥。
 *
 * 把抓包详情页 entry 的 host / path / (方法+path) 转换为可以注入广告拦截侧的规则:
 *  - host 黑名单(全局阻断该 FQDN): 走 ShizukuEnhanceRepository + ShizukuAdControlRepository.syncHostsBlocklist
 *  - path 路径拦截: 通过把"BLOCK@<method> <host><path>"的格式追加到 hostsDomains;
 *    当前 ShizukuHostsModifier 仅支持 FQDN 形态, 故 path 拦截先入 hostsDomain 列表后由 UI 高亮,
 *    具体整 path 拦截由 [HttpMitmFilter] 层的 inspect 决策基于 hosts 黑名单做精确段匹配兜底
 *
 * 设计: 同步非阻塞 UI 调用 → 调用方需在 worker 线程执行 syncHostsBlocklist(网络/Shizuku IPC)
 */
object CaptureAdBlockBridge {

    /** 加入 host 黑名单 → 同步到 Shizuku hosts modifier。 */
    fun addHostBlocklist(context: Context, host: String): Result<Unit> {
        return runCatching {
            val canon = host.trim().lowercase()
            if (canon.isEmpty()) return@runCatching
            val domains = ShizukuEnhanceRepository.getHostsDomains(context).toMutableList()
            if (domains.contains(canon)) return@runCatching
            domains += canon
            ShizukuEnhanceRepository.saveHostsDomains(context, domains)
            val ok = ShizukuAdControlRepository.syncHostsBlocklist(context, domains)
            if (!ok) error(ShizukuAdControlRepository.getLastOperationSummary(context))
        }
    }

    /** 加入 path 拦截 — 当前实现复用 hosts 黑名单(h2/h1 inspect 已基于 host 黑名单做 path 区分)。
     *  未来 path 拦截若引入更精细规则表时, 在此扩展。 */
    fun addPathBlocklist(context: Context, host: String, path: String): Result<Unit> {
        return runCatching {
            val canon = host.trim().lowercase()
            if (canon.isEmpty()) return@runCatching
            // 当 path 为根 "/" 时退化为 host 拦截
            if (path.isEmpty() || path == "/") {
                addHostBlocklist(context, canon).getOrThrow()
                return@runCatching
            }
            // 路径拦截以特殊前缀形式 append 到 hostsDomains 让 HttpMitmFilter inspect 基于此做更精细拒绝
            val domains = ShizukuEnhanceRepository.getHostsDomains(context).toMutableList()
            val entry = "$canon $path"
            // host 已全拦 → 不再细分
            if (domains.contains(canon)) return@runCatching
            // 已存在相同 path 条目 → 跳过
            if (domains.contains(entry)) return@runCatching
            domains += entry
            ShizukuEnhanceRepository.saveHostsDomains(context, domains)
            val ok = ShizukuAdControlRepository.syncHostsBlocklist(context, domains)
            if (!ok) error(ShizukuAdControlRepository.getLastOperationSummary(context))
        }
    }

    /** 加入 WebSocket 升级拦截 = host 维度 hosts 黑名单(广告拦截 inspect 中已识别 WS Connect)。 */
    fun addWebSocketHostBlock(context: Context, host: String): Result<Unit> = addHostBlocklist(context, host)
}
