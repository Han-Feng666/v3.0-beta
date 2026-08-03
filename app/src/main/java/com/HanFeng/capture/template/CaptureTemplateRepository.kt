package com.HanFeng.capture.template

import android.content.Context
import com.HanFeng.capture.CaptureTemplate
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 抓包模板持久化 (跨会话)。requirements R9.4。
 *
 * - 存储: SharedPreferences 单一 key, JSON 数组编码
 * - 并发: [templates] ConcurrentHashMap 由 CRUD 入口保证一致性
 *
 * 引用 design.md Components #7。
 */
object CaptureTemplateRepository {

    private const val PREFS_NAME = "capture_templates"
    private const val KEY_TEMPLATES = "templates_json"

    private val templates = ConcurrentHashMap<String, CaptureTemplate>()
    @Volatile private var loaded = false

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_TEMPLATES, null)
            if (!json.isNullOrBlank()) {
                runCatching {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val headers = LinkedHashMap<String, String>()
                        val ha = o.optJSONArray("headers")
                        if (ha != null) {
                            for (j in 0 until ha.length()) {
                                val h = ha.getJSONObject(j)
                                headers[h.getString("k")] = h.getString("v")
                            }
                        }
                        val bodyStr = o.optString("body_b64", "")
                        val body = if (bodyStr.isBlank()) null
                        else android.util.Base64.decode(bodyStr, android.util.Base64.NO_WRAP)
                        val t = CaptureTemplate(
                            id = o.getString("id"),
                            label = o.getString("label"),
                            createdAt = o.getLong("createdAt"),
                            method = o.getString("method"),
                            scheme = o.getString("scheme"),
                            host = o.getString("host"),
                            path = o.getString("path"),
                            headers = headers,
                            body = body
                        )
                        templates[t.id] = t
                    }
                }
            }
            loaded = true
        }
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        templates.values.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id)
            o.put("label", t.label)
            o.put("createdAt", t.createdAt)
            o.put("method", t.method)
            o.put("scheme", t.scheme)
            o.put("host", t.host)
            o.put("path", t.path)
            val ha = JSONArray()
            t.headers.forEach { (k, v) ->
                val h = JSONObject()
                h.put("k", k)
                h.put("v", v)
                ha.put(h)
            }
            o.put("headers", ha)
            if (t.body != null) {
                o.put("body_b64", android.util.Base64.encodeToString(t.body, android.util.Base64.NO_WRAP))
            }
            arr.put(o)
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TEMPLATES, arr.toString()).apply()
    }

    fun list(context: Context): List<CaptureTemplate> {
        ensureLoaded(context)
        return templates.values.sortedByDescending { it.createdAt }
    }

    fun get(context: Context, id: String): CaptureTemplate? {
        ensureLoaded(context)
        return templates[id]
    }

    /** 保存或覆盖 (相同 id 来直接 put)。返回被写入的模板。 */
    fun upsert(context: Context, template: CaptureTemplate): CaptureTemplate {
        ensureLoaded(context)
        templates[template.id] = template
        persist(context)
        return template
    }

    fun delete(context: Context, id: String): Boolean {
        ensureLoaded(context)
        val removed = templates.remove(id) != null
        if (removed) persist(context)
        return removed
    }

    /** 测试 helper: 清空内存 + 持久化值。 */
    internal fun clearForTesting(context: Context) {
        synchronized(this) {
            templates.clear()
            loaded = true
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_TEMPLATES).apply()
        }
    }
}
