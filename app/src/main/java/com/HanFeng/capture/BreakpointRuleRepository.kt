package com.HanFeng.capture

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * 批次 E6: 持久改写规则仓库 (JSON 单文件)。
 *
 * - 存储: SharedPreferences "hanfeng_breakpoint_rules" 单 KEY (`rules_v1` JSON 字符串)
 * - 进程内 cache: [AtomicReference] 包裹的 List, 读多写少
 * - 写入: 全量覆盖 (规则集不大, 100 条内 OK)
 * - id 分配: AtomicLong 单调递增, 跨进程不保证 (导入时按 max(source 自身 id, 现有最大 id) + 1 接续)
 *
 * 与 [BreakpointRepo] 区别:
 *   [BreakpointRepo] = 实时断点 (用户在详情页加单条规则, 关抓包即清)
 *   [BreakpointRuleRepository] = 持久规则 (跨抓包会话保留, 可导入导出)
 *
 * 引用 design correctness 4 / 13 + requirements R8。
 */
object BreakpointRuleRepository {

    private const val PREFS_NAME = "hanfeng_breakpoint_rules"
    private const val KEY_RULES_V1 = "rules_v1"

    private val cache = java.util.concurrent.atomic.AtomicReference<List<BreakpointRule>>(emptyList())
    private val seq = AtomicLong(0L)

    fun load(context: Context): List<BreakpointRule> {
        val cached = cache.get()
        if (cached.isNotEmpty()) return cached
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RULES_V1, null) ?: return emptyList()
        val rules = runCatching { BreakpointRuleCodec.fromJson(json) }.getOrDefault(emptyList())
        cache.set(rules)
        reseedSeq(rules)
        return rules
    }

    fun save(context: Context, newRules: List<BreakpointRule>): Boolean {
        val json = BreakpointRuleCodec.toJson(newRules)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ok = prefs.edit().putString(KEY_RULES_V1, json).commit()
        cache.set(newRules)
        reseedSeq(newRules)
        return ok
    }

    fun upsert(context: Context, rule: BreakpointRule): Boolean {
        val current = load(context).toMutableList()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) current[idx] = rule else current.add(rule)
        return save(context, current)
    }

    fun remove(context: Context, id: Long): Boolean {
        val current = load(context).toMutableList()
        val removed = current.removeAll { it.id == id }
        if (!removed) return false
        return save(context, current)
    }

    fun setEnabled(context: Context, id: Long, enabled: Boolean): Boolean {
        val current = load(context)
        val target = current.firstOrNull { it.id == id } ?: return false
        if (target.enabled == enabled) return true
        return upsert(context, target.copy(enabled = enabled))
    }

    fun nextId(context: Context): Long {
        load(context)
        return seq.incrementAndGet()
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    /**
     * 导入外部 JSON: 反序列化并合并入现有库 (相同 id 视为更新; 不同 id 添加)。
     */
    fun importJson(context: Context, json: String, merge: Boolean = true): List<BreakpointRule> {
        val parsed = runCatching { BreakpointRuleCodec.fromJson(json) }.getOrDefault(emptyList())
        if (parsed.isEmpty()) return load(context)
        val current = if (merge) load(context).toMutableList() else mutableListOf()
        val byId = current.associateBy { it.id }.toMutableMap()
        parsed.forEach { r -> byId[r.id] = r }
        val result = byId.values.sortedWith(compareBy({ it.priority }, { it.order }, { it.id }))
        save(context, result)
        return result
    }

    fun exportJson(context: Context): String =
        BreakpointRuleCodec.toJson(load(context))

    private fun reseedSeq(rules: List<BreakpointRule>) {
        val maxId = rules.maxOfOrNull { it.id } ?: 0L
        if (maxId > seq.get()) seq.set(maxId)
    }
}
