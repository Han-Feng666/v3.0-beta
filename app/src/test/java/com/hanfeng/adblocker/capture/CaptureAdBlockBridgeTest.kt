package com.hanfeng.adblocker.capture

import android.content.Context
import android.content.SharedPreferences
import com.HanFeng.capture.CaptureAdBlockBridge
import com.HanFeng.data.ShizukuEnhanceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * CaptureAdBlockBridge 联动桥的纯 prefs 路径测试。
 *
 * 不连接 Shizuku service → syncHostsBlocklist 必失败, Bridge 应返回 Result.failure,
 * 但 hostsDomains 持久层应已经更新(用户后续手动 sync 仍可生效)。
 */
class CaptureAdBlockBridgeTest {

    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val mem = HashMap<String?, Any?>()

    @Before
    fun setUp() {
        ctx = Mockito.mock(Context::class.java)
        prefs = Mockito.mock(SharedPreferences::class.java)
        editor = Mockito.mock(SharedPreferences.Editor::class.java)
        mem.clear()
        Mockito.`when`(ctx.applicationContext).thenReturn(ctx)
        // 所有 prefs 调用(任意 name) 路由到同一个 mock prefs → 共享 mem
        Mockito.`when`(ctx.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(prefs)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(prefs.getString(Mockito.any(), Mockito.any()))
            .thenAnswer { mem[it.getArgument(0)] as? String ?: it.getArgument(1) }
        Mockito.`when`(editor.putString(Mockito.any(), Mockito.any()))
            .thenAnswer { mem[it.getArgument(0)] = it.getArgument(1); editor }
        Mockito.`when`(editor.putStringSet(Mockito.any(), Mockito.any()))
            .thenAnswer { mem[it.getArgument(0)] = it.getArgument(1); editor }
        Mockito.`when`(editor.apply()).thenAnswer { }
        Mockito.`when`(editor.commit()).thenReturn(true)
    }

    @Test
    fun `addHostBlocklist host 为空时静默成功 且不写 prefs`() {
        val r = CaptureAdBlockBridge.addHostBlocklist(ctx, "   ")
        assertTrue(r.isSuccess)
        // prefs 不应被写
        assertTrue(mem["hosts_domains"] == null)
    }

    @Test
    fun `addHostBlocklist 首次添加 host 写入 prefs 但 sync 失败时返回 failure`() {
        val r = CaptureAdBlockBridge.addHostBlocklist(ctx, "Ads.Example.COM")
        // 因 mock Shizuku service 不可用 → sync 返回 false → error 抛 → Result.failure
        assertTrue(r.isFailure)
        // 但 prefs 已被写入小写归一化后的 host
        val saved = ShizukuEnhanceRepository.getHostsDomains(ctx)
        assertTrue(saved.contains("ads.example.com"))
    }

    @Test
    fun `addHostBlocklist 重复添加同 host 跳过写入`() {
        // 先填入一个 host
        ShizukuEnhanceRepository.saveHostsDomains(ctx, listOf("ads.example.com"))
        val before = mem["hosts_domains"] as String
        val r = CaptureAdBlockBridge.addHostBlocklist(ctx, "ads.example.com")
        // host 已存在 → runCatching 内 return@runCatching(无 sync 调用) → Result.success
        assertTrue(r.isSuccess)
        // 内容未变
        assertEquals(before, mem["hosts_domains"] as String)
    }

    @Test
    fun `addPathBlocklist path 为空或根 退化为 host 拦截`() {
        val r1 = CaptureAdBlockBridge.addPathBlocklist(ctx, "ads.example.com", "")
        assertTrue(r1.isFailure) // sync 失败,但 host 已存
        assertTrue(ShizukuEnhanceRepository.getHostsDomains(ctx).contains("ads.example.com"))

        mem.clear()
        ShizukuEnhanceRepository.saveHostsDomains(ctx, listOf("ads.example.com"))
        val r2 = CaptureAdBlockBridge.addPathBlocklist(ctx, "ADS.example.com", "/")
        // host 已在 → 跳过 → success
        assertTrue(r2.isSuccess)
    }

    @Test
    fun `addPathBlocklist host 已全拦时 path 细分被跳过`() {
        ShizukuEnhanceRepository.saveHostsDomains(ctx, listOf("ads.example.com"))
        val r = CaptureAdBlockBridge.addPathBlocklist(ctx, "ads.example.com", "/banner.png")
        assertTrue(r.isSuccess)
        val saved = ShizukuEnhanceRepository.getHostsDomains(ctx)
        // 不应增加 "host path" 条目
        assertFalse(saved.any { it.contains(" ") })
        assertEquals(1, saved.size)
    }

    @Test
    fun `addPathBlocklist 首次添加带 path 条目写入 prefs 且 sync 失败返回 failure`() {
        val r = CaptureAdBlockBridge.addPathBlocklist(ctx, "tracker.com", "/pixel.gif")
        assertTrue(r.isFailure)
        val saved = ShizukuEnhanceRepository.getHostsDomains(ctx)
        // 应含 "tracker.com /pixel.gif" 条目 (path 规整未变)
        assertTrue(saved.contains("tracker.com /pixel.gif"))
    }

    @Test
    fun `addPathBlocklist 重复相同 path 条目跳过`() {
        CaptureAdBlockBridge.addPathBlocklist(ctx, "tracker.com", "/pixel.gif")
        val before = mem["hosts_domains"] as String
        val r = CaptureAdBlockBridge.addPathBlocklist(ctx, "tracker.com", "/pixel.gif")
        // 已存在 → 跳过 sync → success
        assertTrue(r.isSuccess)
        assertEquals(before, mem["hosts_domains"] as String)
    }

    @Test
    fun `addWebSocketHostBlock 等同于 host 拦截`() {
        val r = CaptureAdBlockBridge.addWebSocketHostBlock(ctx, "ws.ads.com")
        assertTrue(r.isFailure) // sync 失败
        assertTrue(ShizukuEnhanceRepository.getHostsDomains(ctx).contains("ws.ads.com"))
    }

    @Test
    fun `hosts 小写归一化后形如全大写也能命中已存在条目`() {
        ShizukuEnhanceRepository.saveHostsDomains(ctx, listOf("ads.example.com"))
        val r = CaptureAdBlockBridge.addHostBlocklist(ctx, "ADS.EXAMPLE.COM")
        assertTrue(r.isSuccess)
        // 仅一个条目(去重)
        assertEquals(1, ShizukuEnhanceRepository.getHostsDomains(ctx).size)
    }
}
