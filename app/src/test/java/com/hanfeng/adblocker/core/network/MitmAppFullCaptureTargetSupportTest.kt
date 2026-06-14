package com.HanFeng.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MitmAppFullCaptureTargetSupportTest {
    @Test
    fun `rejects novel targets and allows coolapk targets`() {
        assertFalse(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.dragon.read",
                label = "番茄免费小说",
                isSystemApp = false
            )
        )
        assertFalse(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.qimao.reader",
                label = "七猫免费小说",
                isSystemApp = false
            )
        )
        assertTrue(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.coolapk.market",
                label = "酷安",
                isSystemApp = false
            )
        )
    }

    @Test
    fun `rejects system browser payment and social targets`() {
        assertFalse(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "android",
                label = "Android 系统",
                isSystemApp = true
            )
        )
        assertFalse(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.android.browser",
                label = "浏览器",
                isSystemApp = false
            )
        )
        assertFalse(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.eg.android.AlipayGphone",
                label = "支付宝",
                isSystemApp = false
            )
        )
        assertFalse(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.tencent.mm",
                label = "微信",
                isSystemApp = false
            )
        )
    }

    @Test
    fun `allows shizuku managed content targets but rejects managed browsers and markets`() {
        assertTrue(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.android.thememanager",
                label = "小米主题壁纸推荐",
                isSystemApp = true,
                managedPromoCategory = "主题壁纸"
            )
        )
        assertTrue(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.miui.personalassistant",
                label = "小米智能助理",
                isSystemApp = true,
                managedPromoCategory = "负一屏推荐"
            )
        )
        assertFalse(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.android.browser",
                label = "小米浏览器推荐",
                isSystemApp = true,
                managedPromoCategory = "浏览器推荐"
            )
        )
        assertFalse(
            MitmAppFullCaptureTargetSupport.isSafeTarget(
                packageName = "com.xiaomi.mipicks",
                label = "小米应用商店推广",
                isSystemApp = true,
                managedPromoCategory = "系统推广"
            )
        )
    }
}
