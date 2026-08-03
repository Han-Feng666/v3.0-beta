package com.hanfeng.adblocker.capture

import com.HanFeng.capture.BreakpointAction
import com.HanFeng.capture.BreakpointKind
import com.HanFeng.capture.BreakpointMatch
import com.HanFeng.capture.BreakpointRule
import com.HanFeng.capture.BreakpointRuleCodec
import com.HanFeng.capture.BreakpointRuleMatcher
import com.HanFeng.capture.PathMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakpointRuleMatcherTest {

    private fun rule(
        id: Long, name: String = "r$id", priority: Int = 100, order: Int = 0,
        enabled: Boolean = true, kind: BreakpointKind = BreakpointKind.REQUEST,
        host: String = "", method: String? = null,
        path: PathMatcher = PathMatcher.Any, ct: String? = null,
        query: String? = null, action: BreakpointAction = BreakpointAction.PassThrough(useOriginal = true)
    ) = BreakpointRule(id, name, priority, order, enabled, BreakpointMatch(kind, host, method, path, ct, query), action)

    @Test
    fun `priority asc 排序后首条命中终止`() {
        val lowPrio = rule(id = 1, priority = 200, host = "a.com", action = BreakpointAction.PassThrough(useOriginal = false))
        val highPrio = rule(id = 2, priority = 10, host = "a.com", action = BreakpointAction.Drop)
        val list = listOf(lowPrio, highPrio)
        val act = BreakpointRuleMatcher.resolveRequest(list, "a.com", "GET", "/x", null)
        assertTrue(act is BreakpointAction.Drop)
    }

    @Test
    fun `同 priority 时 order asc 决胜`() {
        val r2 = rule(id = 1, priority = 50, order = 5, host = "h", action = BreakpointAction.Drop)
        val r1 = rule(id = 2, priority = 50, order = 1, host = "h", action = BreakpointAction.PassThrough(useOriginal = false))
        val act = BreakpointRuleMatcher.resolveRequest(listOf(r2, r1), "h", "GET", "/", null)
        assertTrue(act is BreakpointAction.PassThrough)
    }

    @Test
    fun `禁用规则不被评估`() {
        val disabled = rule(id = 1, host = "x.com", enabled = false, action = BreakpointAction.Drop)
        val act = BreakpointRuleMatcher.resolveRequest(listOf(disabled), "x.com", "GET", "/", null)
        assertNull(act)
    }

    @Test
    fun `host 后缀通配命中子域`() {
        val r = rule(id = 1, host = ".example.com", kind = BreakpointKind.REQUEST)
        assertTrue(r.match.match("a.b.example.com", "GET", "/", null, ""))
        assertTrue(r.match.match("example.com", "GET", "/", null, ""))
        assertFalse(r.match.match("notexample.com", "GET", "/", null, ""))
    }

    @Test
    fun `path 前缀 + Method 联合匹配`() {
        val r = rule(id = 1, host = "h", method = "POST", path = PathMatcher.Prefix("/api"))
        assertTrue(r.match.match("h", "post", "/api/v1", null, ""))
        assertFalse(r.match.match("h", "get", "/api/v1", null, ""))
        assertFalse(r.match.match("h", "POST", "/other", null, ""))
    }

    @Test
    fun `query 包含是否用大小写不敏感匹配`() {
        val r = rule(id = 1, host = "h", query = "token=abc")
        assertTrue(r.match.match("h", "GET", "/", null, "foo=bar&token=ABCDEF"))
        assertFalse(r.match.match("h", "GET", "/", null, "foo=bar"))
    }

    @Test
    fun `response direction 与 request direction 互不串走`() {
        val req = rule(id = 1, kind = BreakpointKind.REQUEST, host = "h", action = BreakpointAction.Drop)
        val rsp = rule(id = 2, kind = BreakpointKind.RESPONSE, host = "h", action = BreakpointAction.PassThrough(useOriginal = false))
        val list = listOf(req, rsp)
        val reqAct = BreakpointRuleMatcher.resolveRequest(list, "h", "GET", "/", null)
        val rspAct = BreakpointRuleMatcher.resolveResponse(list, "h", "GET", "/", 200, null)
        assertTrue(reqAct is BreakpointAction.Drop)
        assertTrue(rspAct is BreakpointAction.PassThrough)
    }

    @Test
    fun `path regex 全匹配匹配而不匹配部分`() {
        val r = rule(id = 1, path = PathMatcher.Regex("/v[0-9]+/user"))
        assertTrue(r.match.match("h", "GET", "/v2/user", null, ""))
        assertFalse(r.match.match("h", "GET", "/v2/user/extra", null, ""))
        assertFalse(r.match.match("h", "GET", "/v2X/user", null, ""))
    }

    @Test
    fun `path 包含 query 段时 matcher 自动拆 query 与 path`() {
        val ruleWithQuery = rule(id = 1, host = "h", query = "filter=1")
        val act = BreakpointRuleMatcher.resolveRequest(listOf(ruleWithQuery), "h", "GET", "/api?filter=1&sort=2", null)
        assertTrue(act is BreakpointAction.PassThrough)
    }

    // ---------- codec 圆环 ----------

    @Test
    fun `codec 反序列化保留全字段 (ReplaceWith + headersOverride)`() {
        val orig = listOf(
            rule(id = 7, name = "edit-response", priority = 3, order = 1, enabled = false,
                kind = BreakpointKind.RESPONSE, host = ".example.com", method = "GET",
                path = PathMatcher.Regex("/v[0-9]+/x"), ct = "json", query = "token=1",
                action = BreakpointAction.ReplaceWith(
                    replacement = "hello".toByteArray(),
                    headersOverride = mapOf("X-Wrap" to "1"),
                    statusLineOverride = "HTTP/1.1 201 Created"
                )
            )
        )
        val json = BreakpointRuleCodec.toJson(orig)
        val back = BreakpointRuleCodec.fromJson(json)
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(7, r.id)
        assertEquals("edit-response", r.name)
        assertEquals(3, r.priority)
        assertEquals(1, r.order)
        assertFalse(r.enabled)
        assertEquals(BreakpointKind.RESPONSE, r.match.kind)
        assertEquals(".example.com", r.match.host)
        assertEquals("GET", r.match.method)
        assertTrue(r.match.pathMatcher is PathMatcher.Regex)
        assertEquals("/v[0-9]+/x", (r.match.pathMatcher as PathMatcher.Regex).value)
        assertEquals("json", r.match.contentTypeContains)
        assertEquals("token=1", r.match.queryContains)
        val act = r.action as BreakpointAction.ReplaceWith
        assertEquals("hello", String(act.replacement, Charsets.UTF_8))
        assertEquals("1", act.headersOverride?.get("X-Wrap"))
        assertEquals("HTTP/1.1 201 Created", act.statusLineOverride)
    }

    @Test
    fun `codec drop 与 passThrough 路径`() {
        val orig = listOf(
            rule(id = 1, action = BreakpointAction.Drop),
            rule(id = 2, kind = BreakpointKind.RESPONSE, action = BreakpointAction.PassThrough(useOriginal = false))
        )
        val json = BreakpointRuleCodec.toJson(orig)
        val back = BreakpointRuleCodec.fromJson(json)
        assertEquals(2, back.size)
        assertTrue(back[0].action is BreakpointAction.Drop)
        assertTrue(back[1].action is BreakpointAction.PassThrough)
        assertFalse((back[1].action as BreakpointAction.PassThrough).useOriginal)
    }

    @Test
    fun `codec kind 不匹配的脏 json 返回空列表`() {
        val bad = """{"version":1,"kind":"unknown","rules":[]}"""
        val back = BreakpointRuleCodec.fromJson(bad)
        assertTrue(back.isEmpty())
    }
}
