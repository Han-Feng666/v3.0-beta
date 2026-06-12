package com.HanFeng.data

import android.content.Context
import android.content.pm.PackageManager

object PromoGovernComponentRepository {
    fun discoverCandidates(context: Context, packageName: String): List<PromoComponentCandidate> {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES
            )
        }.getOrNull() ?: return emptyList()

        val candidates = mutableListOf<PromoComponentCandidate>()
        packageInfo.activities.orEmpty().forEach { info ->
            val fullName = info.name.orEmpty()
            val shortName = fullName.substringAfterLast('.')
            val score = promoComponentScore(fullName.lowercase(), "Activity")
            if (score > 0) candidates += buildCandidate(fullName, shortName, "Activity", info.isEnabled, score)
        }
        packageInfo.receivers.orEmpty().forEach { info ->
            val fullName = info.name.orEmpty()
            val shortName = fullName.substringAfterLast('.')
            val score = promoComponentScore(fullName.lowercase(), "Receiver")
            if (score > 0) candidates += buildCandidate(fullName, shortName, "Receiver", info.isEnabled, score)
        }
        packageInfo.services.orEmpty().forEach { info ->
            val fullName = info.name.orEmpty()
            val shortName = fullName.substringAfterLast('.')
            val score = promoComponentScore(fullName.lowercase(), "Service")
            if (score > 0) candidates += buildCandidate(fullName, shortName, "Service", info.isEnabled, score)
        }
        return candidates.sortedWith(compareByDescending<PromoComponentCandidate> { it.score }.thenBy { it.shortName }).take(20)
    }

    private fun buildCandidate(
        fullName: String,
        shortName: String,
        typeLabel: String,
        enabled: Boolean,
        score: Int
    ): PromoComponentCandidate {
        val lowerName = fullName.lowercase()
        val groupLabel = when {
            typeLabel == "Activity" && listOf("splash", "startup", "launchad", "interstitial").any(lowerName::contains) -> "启动广告 Activity"
            typeLabel == "Activity" && listOf("feed", "recommend", "discover", "hot", "operation", "market").any(lowerName::contains) -> "推荐流/活动页 Activity"
            typeLabel == "Receiver" && listOf("push", "alarm", "message", "notice").any(lowerName::contains) -> "推送 Receiver"
            typeLabel == "Service" && listOf("push", "job", "message", "notice").any(lowerName::contains) -> "推送 Service"
            typeLabel == "Service" && listOf("ad", "advert", "union", "reward").any(lowerName::contains) -> "广告 Service"
            else -> "疑似推广组件"
        }
        val riskLabel = when {
            typeLabel == "Activity" && score >= 5 -> "低风险"
            typeLabel == "Receiver" && score >= 5 -> "中风险"
            typeLabel == "Service" && score >= 5 -> "中风险"
            else -> "需确认"
        }
        val recommendation = when (groupLabel) {
            "启动广告 Activity" -> "优先治理，通常影响启动页广告。"
            "推荐流/活动页 Activity" -> "谨慎治理，可能影响活动页或推荐入口。"
            "推送 Receiver" -> "适合治理推送广告，失败时可恢复组件。"
            "推送 Service" -> "适合治理后台推送广告，建议保留恢复路径。"
            "广告 Service" -> "适合治理广告加载服务，操作前确认不是主业务服务。"
            else -> "命中推广关键词，建议确认组件名称后处理。"
        }
        return PromoComponentCandidate(fullName, shortName, typeLabel, enabled, score, groupLabel, recommendation, riskLabel)
    }

    private fun promoComponentScore(lowerName: String, typeLabel: String): Int {
        var score = 0
        val strongHints = listOf(
            "splash", "startup", "launchad", "advert", "adactivity", "adservice", "adreceiver",
            "push", "recommend", "promo", "feedad", "reward", "interstitial", "union"
        )
        val moderateHints = listOf(
            "guide", "popup", "notice", "message", "operation", "market", "discover", "hot", "brand"
        )
        strongHints.forEach { if (lowerName.contains(it)) score += 3 }
        moderateHints.forEach { if (lowerName.contains(it)) score += 1 }
        if (typeLabel == "Activity" && listOf("splash", "startup", "launch", "ad").any(lowerName::contains)) score += 2
        if (typeLabel == "Receiver" && listOf("push", "alarm", "recommend", "ad").any(lowerName::contains)) score += 2
        if (typeLabel == "Service" && listOf("push", "ad", "recommend", "job").any(lowerName::contains)) score += 2
        return score
    }
}
