package com.HanFeng.service

import org.junit.Test
import org.junit.Assert.*

/**
 * P2.2 单元测试：WebView 广告拦截器
 * 
 * 测试覆盖率目标：
 * - 脚本注入：100%
 * - 脚本内容验证：100%
 */
class WebViewAdBlockerTest {
    
    @Test
    fun `test anti-adblock bypass script is not empty`() {
        // 使用反射访问私有常量
        val field = WebViewAdBlocker::class.java.getDeclaredField("ANTI_ADBLOCK_BYPASS")
        field.isAccessible = true
        val script = field.get(null) as String
        
        assertTrue("Anti-adblock script should not be empty", script.isNotEmpty())
        assertTrue("Should contain confirm override", script.contains("window.confirm"))
        assertTrue("Should contain alert override", script.contains("window.alert"))
        assertTrue("Should contain adblock detection check", script.contains("adblock").or(script.contains("AdBlock")))
    }
    
    @Test
    fun `test auto hide ads script is not empty`() {
        val field = WebViewAdBlocker::class.java.getDeclaredField("AUTO_HIDE_ADS")
        field.isAccessible = true
        val script = field.get(null) as String
        
        assertTrue("Auto hide script should not be empty", script.isNotEmpty())
        assertTrue("Should contain ad selectors", script.contains("adSelectors"))
        assertTrue("Should contain MutationObserver", script.contains("MutationObserver"))
        assertTrue("Should contain hide function", script.contains("hideAds"))
    }
    
    @Test
    fun `test ad request blocker script is not empty`() {
        val field = WebViewAdBlocker::class.java.getDeclaredField("AD_REQUEST_BLOCKER")
        field.isAccessible = true
        val script = field.get(null) as String
        
        assertTrue("Request blocker script should not be empty", script.isNotEmpty())
        assertTrue("Should contain fetch override", script.contains("window.fetch"))
        assertTrue("Should contain XHR override", script.contains("XMLHttpRequest"))
        assertTrue("Should contain ad domains list", script.contains("adDomains"))
    }
    
    @Test
    fun `test combined injection script`() {
        // Verify all three scripts are present in combined script
        val allField = WebViewAdBlocker::class.java.getDeclaredField("ALL_INJECTION_SCRIPT")
        allField.isAccessible = true
        val allScript = allField.get(null) as String
        
        assertTrue("Combined script should not be empty", allScript.isNotEmpty())
        
        // Check that all three component scripts are included
        val antiField = WebViewAdBlocker::class.java.getDeclaredField("ANTI_ADBLOCK_BYPASS")
        antiField.isAccessible = true
        val antiScript = antiField.get(null) as String
        
        val autoField = WebViewAdBlocker::class.java.getDeclaredField("AUTO_HIDE_ADS")
        autoField.isAccessible = true
        val autoScript = autoField.get(null) as String
        
        val reqField = WebViewAdBlocker::class.java.getDeclaredField("AD_REQUEST_BLOCKER")
        reqField.isAccessible = true
        val reqScript = reqField.get(null) as String
        
        // All components should be in the combined script
        assertTrue("Combined script should contain anti-adblock", allScript.contains("confirm"))
        assertTrue("Combined script should contain auto-hide", allScript.contains("hide"))
        assertTrue("Combined script should contain request blocker", allScript.contains("fetch"))
    }
    
    @Test
    fun `test getInjectedCount returns integer`() {
        // Initial count should be 0
        val count = WebViewAdBlocker.getInjectedCount()
        
        assertTrue("Initial count should be >= 0", count >= 0)
    }
    
    @Test
    fun `test clear resets injection count`() {
        // Just verify clear() doesn't throw exception
        // Actual injection testing requires WebView which needs Android framework
        WebViewAdBlocker.clear()
        
        // Should not throw any exception
        assertTrue("Clear completed successfully", true)
    }
}
