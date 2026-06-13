package com.HanFeng.service

import org.junit.Test
import org.junit.Assert.*

/**
 * P2.2 单元测试：智能场景模式管理
 * 
 * 测试覆盖率目标：
 * - App 类型识别：100%
 * - 策略生成：100%
 * - 缓存机制：80%+
 */
class SceneModeManagerTest {
    
    @Test
    fun `test novel app detection`() {
        val testCases = listOf(
            "com.qidian.reader" to SceneMode.AGGRESSIVE,
            "com.jinjiang.app" to SceneMode.AGGRESSIVE,
            "com.fanqie.novel" to SceneMode.AGGRESSIVE,
            "com.qimao.reader" to SceneMode.AGGRESSIVE,
            "com.hongguo.drama" to SceneMode.AGGRESSIVE,
            "com.example.manga" to SceneMode.AGGRESSIVE,
            "com.example.duanju" to SceneMode.AGGRESSIVE,
            "com.example.shortdrama" to SceneMode.AGGRESSIVE,
            "com.example.minidrama.episode" to SceneMode.AGGRESSIVE,
            "com.example.audio.listen" to SceneMode.AGGRESSIVE
        )
        
        testCases.forEach { (packageName, expectedMode) ->
            val appName = packageName.split('.').last()
            val mode = SceneModeManager.autoDetect(appName, packageName)
            assertEquals("Failed for: $packageName", expectedMode, mode)
        }
    }
    
    @Test
    fun `test video app detection`() {
        val testCases = listOf(
            "com.tencent.video" to SceneMode.AGGRESSIVE,
            "com.qiyi.video" to SceneMode.AGGRESSIVE,
            "com.youku.phone" to SceneMode.AGGRESSIVE,
            "tv.danmaku.bili" to SceneMode.AGGRESSIVE
        )
        
        testCases.forEach { (packageName, expectedMode) ->
            val appName = packageName.split('.').last()
            val mode = SceneModeManager.autoDetect(appName, packageName)
            assertEquals("Failed for: $packageName", expectedMode, mode)
        }
    }
    
    @Test
    fun `test finance app detection`() {
        val testCases = listOf(
            "com.alipay.android.app" to SceneMode.COMPATIBLE,  // Contains 'ipay' which is close enough
            "com.mobilebanking.test" to SceneMode.COMPATIBLE,  // Contains 'banking'
            "com.finance.app" to SceneMode.COMPATIBLE  // Contains 'finance'
        )
        
        testCases.forEach { (packageName, expectedMode) ->
            val appName = packageName.split('.').last()
            val mode = SceneModeManager.autoDetect(appName, packageName)
            assertEquals("Failed for: $packageName", expectedMode, mode)
        }
    }
    
    @Test
    fun `test game app detection`() {
        val testCases = listOf(
            "com.tencent.tmgp.sgame" to SceneMode.GAME,  // Contains 'game'
            "com.netease.game" to SceneMode.GAME,  // Contains 'game'
            "com.game.test" to SceneMode.GAME  // Contains 'game'
        )
        
        testCases.forEach { (packageName, expectedMode) ->
            val appName = packageName.split('.').last()
            val mode = SceneModeManager.autoDetect(appName, packageName)
            assertEquals("Failed for: $packageName", expectedMode, mode)
        }
    }
    
    @Test
    fun `test default balanced mode`() {
        val testCases = listOf(
            "com.example.normal",
            "com.android.settings",
            "com.google.android.apps.maps"
        )
        
        testCases.forEach { packageName ->
            val appName = packageName.split('.').last()
            val mode = SceneModeManager.autoDetect(appName, packageName)
            assertEquals("Failed for: $packageName", SceneMode.BALANCED, mode)
        }
    }
    
    @Test
    fun `test blocking strategy for aggressive mode`() {
        val strategy = SceneModeManager.getBlockingStrategy(SceneMode.AGGRESSIVE)
        
        assertTrue(strategy.enableKeywordBlock)
        assertTrue(strategy.enablePathBlock)
        assertTrue(strategy.enableBodyInspection)
        assertTrue(strategy.enableDeepInspection)
        assertTrue(strategy.enableCosmeticFilter)
        assertTrue(strategy.enableRequestRewrite)
        assertFalse(strategy.protectWhitelist)
        assertTrue(strategy.aggressiveMode)
    }
    
    @Test
    fun `test blocking strategy for compatible mode`() {
        val strategy = SceneModeManager.getBlockingStrategy(SceneMode.COMPATIBLE)
        
        assertFalse(strategy.enableKeywordBlock)
        assertTrue(strategy.enablePathBlock)
        assertFalse(strategy.enableBodyInspection)
        assertFalse(strategy.enableDeepInspection)
        assertFalse(strategy.enableCosmeticFilter)
        assertFalse(strategy.enableRequestRewrite)
        assertTrue(strategy.protectWhitelist)
        assertFalse(strategy.aggressiveMode)
    }
    
    @Test
    fun `test manual mode setting`() {
        val packageName = "com.test.manualapp"
        SceneModeManager.setManualMode(packageName, SceneMode.COMPATIBLE)
        
        val mode = SceneModeManager.getMode(packageName)
        assertEquals("Manual mode not set correctly", SceneMode.COMPATIBLE, mode)
        
        SceneModeManager.clearCache()
    }
    
    @Test
    fun `test blocking strategy helper functions`() {
        val aggressiveStrategy = SceneModeManager.getBlockingStrategy(SceneMode.AGGRESSIVE)
        val compatibleStrategy = SceneModeManager.getBlockingStrategy(SceneMode.COMPATIBLE)
        
        // Test shouldInspectBody
        assertTrue(aggressiveStrategy.shouldInspectBody())
        assertFalse(compatibleStrategy.shouldInspectBody())
        
        // Test shouldDeepInspect
        assertTrue(aggressiveStrategy.shouldDeepInspect())
        assertFalse(compatibleStrategy.shouldDeepInspect())
    }
}
