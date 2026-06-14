package com.HanFeng.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoGovernActionRepositoryTest {
    @Test
    fun `smart govern allows low risk startup ad components`() {
        val candidate = candidate(
            shortName = "SplashAdActivity",
            typeLabel = "Activity",
            score = 8,
            groupLabel = "启动广告 Activity",
            riskLabel = "低风险"
        )

        assertTrue(PromoGovernActionRepository.isSmartGovernSafeComponent(candidate))
    }

    @Test
    fun `smart govern allows medium risk push components`() {
        val candidate = candidate(
            shortName = "PromoPushReceiver",
            typeLabel = "Receiver",
            score = 6,
            groupLabel = "推送 Receiver",
            riskLabel = "中风险"
        )

        assertTrue(PromoGovernActionRepository.isSmartGovernSafeComponent(candidate))
    }

    @Test
    fun `smart govern rejects business critical components`() {
        val main = candidate("MainActivity", "Activity", 8, "主入口 Activity", "高风险")
        val login = candidate("LoginActivity", "Activity", 8, "账号登录 Activity", "高风险")
        val payment = candidate("CashierActivity", "Activity", 8, "支付/钱包 Activity", "高风险")
        val web = candidate("HybridWebViewActivity", "Activity", 8, "网页容器 Activity", "中风险")

        assertFalse(PromoGovernActionRepository.isSmartGovernSafeComponent(main))
        assertFalse(PromoGovernActionRepository.isSmartGovernSafeComponent(login))
        assertFalse(PromoGovernActionRepository.isSmartGovernSafeComponent(payment))
        assertFalse(PromoGovernActionRepository.isSmartGovernSafeComponent(web))
    }

    @Test
    fun `smart govern rejects disabled or weak signal components`() {
        val disabled = candidate("SplashAdActivity", "Activity", 8, "启动广告 Activity", "低风险", enabled = false)
        val weak = candidate("OperationActivity", "Activity", 4, "启动广告 Activity", "低风险")

        assertFalse(PromoGovernActionRepository.isSmartGovernSafeComponent(disabled))
        assertFalse(PromoGovernActionRepository.isSmartGovernSafeComponent(weak))
    }

    private fun candidate(
        shortName: String,
        typeLabel: String,
        score: Int,
        groupLabel: String,
        riskLabel: String,
        enabled: Boolean = true
    ): PromoComponentCandidate {
        return PromoComponentCandidate(
            componentName = "com.example/.${shortName}",
            shortName = shortName,
            typeLabel = typeLabel,
            enabled = enabled,
            score = score,
            groupLabel = groupLabel,
            recommendation = "test",
            riskLabel = riskLabel
        )
    }
}
