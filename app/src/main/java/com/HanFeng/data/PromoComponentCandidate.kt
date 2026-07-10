package com.HanFeng.data

data class PromoComponentCandidate(
    val componentName: String,
    val shortName: String,
    val typeLabel: String,
    val enabled: Boolean,
    val score: Int,
    val groupLabel: String,
    val recommendation: String,
    val riskLabel: String
)
