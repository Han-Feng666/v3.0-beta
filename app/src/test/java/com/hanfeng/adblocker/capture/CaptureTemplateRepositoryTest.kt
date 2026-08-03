package com.hanfeng.adblocker.capture

import android.content.Context
import com.HanFeng.capture.CaptureTemplate
import com.HanFeng.capture.template.CaptureTemplateRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class CaptureTemplateRepositoryTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = Mockito.mock(Context::class.java)
        val prefs = Mockito.mock(android.content.SharedPreferences::class.java)
        val editor = Mockito.mock(android.content.SharedPreferences.Editor::class.java)
        val mem = HashMap<String?, Any?>()
        Mockito.`when`(ctx.applicationContext).thenReturn(ctx)
        Mockito.`when`(ctx.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(prefs)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(prefs.getString(Mockito.any(), Mockito.any()))
            .thenAnswer { mem[it.getArgument(0)] as? String ?: it.getArgument(1) }
        Mockito.`when`(editor.putString(Mockito.any(), Mockito.any()))
            .thenAnswer { mem[it.getArgument(0)] = it.getArgument(1); editor }
        Mockito.`when`(editor.remove(Mockito.any())).thenReturn(editor)
        Mockito.`when`(editor.apply()).thenAnswer { }
        Mockito.`when`(editor.commit()).thenReturn(true)
    }

    @After
    fun tearDown() {
        CaptureTemplateRepository.clearForTesting(ctx)
    }

    @Test
    fun `upsert 与 get 双向可读`() {
        val t = CaptureTemplate(
            id = "id1",
            label = "Test API",
            createdAt = System.currentTimeMillis(),
            method = "GET",
            scheme = "https",
            host = "h.example.com",
            path = "/v1",
            headers = mapOf("Authorization" to "Bearer abc"),
            body = null
        )
        CaptureTemplateRepository.upsert(ctx, t)
        val got = CaptureTemplateRepository.get(ctx, "id1")
        assertNotNull(got)
        assertEquals("GET", got!!.method)
        assertEquals("Bearer abc", got.headers["authorization"])
        assertNull(got.body)
    }

    @Test
    fun `list 返回所有模板 按 createdAt 倒序`() {
        CaptureTemplateRepository.upsert(ctx, CaptureTemplate("a", "a", 1L, "GET", "https", "h", "/", emptyMap(), null))
        CaptureTemplateRepository.upsert(ctx, CaptureTemplate("b", "b", 2L, "POST", "https", "h", "/", emptyMap(), null))
        CaptureTemplateRepository.upsert(ctx, CaptureTemplate("c", "c", 3L, "DELETE", "https", "h", "/", emptyMap(), null))
        val list = CaptureTemplateRepository.list(ctx)
        assertEquals(3, list.size)
        assertEquals(3L, list[0].createdAt)
        assertEquals(2L, list[1].createdAt)
        assertEquals(1L, list[2].createdAt)
    }

    @Test
    fun `delete 移除模板并持久化`() {
        CaptureTemplateRepository.upsert(ctx, CaptureTemplate("x", "x", 1L, "GET", "https", "h", "/", emptyMap(), null))
        assertTrue(CaptureTemplateRepository.delete(ctx, "x"))
        assertNull(CaptureTemplateRepository.get(ctx, "x"))
        assertFalse(CaptureTemplateRepository.delete(ctx, "x"))
    }

    @Test
    fun `body 非 null 时 通过 base64 编解码往返不丢失`() {
        val t = CaptureTemplate(
            id = "with-body",
            label = "POST Body",
            createdAt = 0L,
            method = "POST",
            scheme = "https",
            host = "h.example.com",
            path = "/submit",
            headers = mapOf("Content-Type" to "application/json"),
            body = "{\"k\":\"v\"}".toByteArray()
        )
        CaptureTemplateRepository.upsert(ctx, t)
        val got = CaptureTemplateRepository.get(ctx, "with-body")
        assertNotNull(got)
        assertNotNull(got!!.body)
        assertEquals("{\"k\":\"v\"}", String(got.body!!))
    }

    @Test
    fun `clearForTesting 后任何 get 返回 null`() {
        CaptureTemplateRepository.upsert(ctx, CaptureTemplate("c", "c", 1L, "GET", "https", "h", "/", emptyMap(), null))
        CaptureTemplateRepository.clearForTesting(ctx)
        assertNull(CaptureTemplateRepository.get(ctx, "c"))
        assertEquals(0, CaptureTemplateRepository.list(ctx).size)
    }
}

