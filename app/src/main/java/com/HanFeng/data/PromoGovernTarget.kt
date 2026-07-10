package com.HanFeng.data

data class PromoGovernTarget(
    val packageName: String,
    val title: String,
    val category: String,
    val description: String,
    val sourceLabel: String,
    val systemApp: Boolean,
    val detectionTags: List<String>,
    val relatedPresets: List<ShizukuAdControlCatalog.Preset>,
    val packageStatus: ShizukuAdControlRepository.PackageControlStatus
)
