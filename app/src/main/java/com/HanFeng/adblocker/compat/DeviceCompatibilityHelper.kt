package com.HanFeng.adblocker.compat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.HanFeng.data.LogRepository

object DeviceCompatibilityHelper {

    enum class RomType {
        STOCK_ANDROID, XIAOMI, HUAWEI, HONOR, OPPO, VIVO, SAMSUNG,
        MEIZU, ONEPLUS, LENOVO, ZTE, NUBIA, GOOGLE, UNKNOWN
    }

    fun detectRomType(): RomType {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        val combined = "$manufacturer $brand $model $fingerprint"

        return when {
            combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") ->
                RomType.XIAOMI
            combined.contains("huawei") -> RomType.HUAWEI
            combined.contains("honor") || brand.contains("honor") || combined.contains("hihonor") ->
                RomType.HONOR
            combined.contains("oppo") || combined.contains("realme") || combined.contains("oneplus") ->
                RomType.OPPO
            combined.contains("vivo") || combined.contains("iqoo") ->
                RomType.VIVO
            combined.contains("samsung") || combined.contains("sm-") ->
                RomType.SAMSUNG
            combined.contains("meizu") -> RomType.MEIZU
            combined.contains("oneplus") -> RomType.ONEPLUS
            combined.contains("lenovo") || combined.contains("motorola") || combined.contains("moto") ->
                RomType.LENOVO
            combined.contains("nubia") || combined.contains("red magic") -> RomType.NUBIA
            combined.contains("zte") -> RomType.ZTE
            combined.contains("google") || combined.contains("pixel") -> RomType.GOOGLE
            else -> if (manufacturer in listOf("xiaomi", "huawei", "oppo", "vivo", "samsung", "meizu", "oneplus", "lenovo", "zte", "nubia"))
                RomType.UNKNOWN else RomType.STOCK_ANDROID
        }
    }

    fun isChineseRom(): Boolean {
        return when (detectRomType()) {
            RomType.XIAOMI, RomType.HUAWEI, RomType.HONOR, RomType.OPPO,
            RomType.VIVO, RomType.MEIZU, RomType.ONEPLUS, RomType.LENOVO,
            RomType.ZTE, RomType.NUBIA -> true
            else -> false
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }

    fun requestBatteryOptimizationExemption(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (isIgnoringBatteryOptimizations(activity)) return true
        return runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun openBatteryOptimizationSettings(activity: Activity): Boolean {
        val intent = when (detectRomType()) {
            RomType.XIAOMI -> buildIntent(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings"
            )
            RomType.HUAWEI -> buildIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"
            )
            RomType.OPPO -> buildIntent(
                "com.coloros.oppoguardelf",
                "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"
            )
            RomType.VIVO -> buildIntent(
                "com.vivo.abe",
                "com.vivo.abe.powersetting.PowerSettingActivity"
            )
            else -> null
        }
        return if (intent != null && canResolveIntent(activity, intent)) {
            runCatching { activity.startActivity(intent) }.isSuccess
        } else {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.isSuccess
        }
    }

    fun openAutoStartSettings(activity: Activity): Boolean {
        val intent = when (detectRomType()) {
            RomType.XIAOMI -> buildIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ) ?: buildIntent(
                "com.miui.securitycenter",
                "com.miui.appmanager.ApplicationsManagerActivity"
            )
            RomType.HUAWEI -> buildIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ) ?: buildIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            )
            RomType.HONOR -> buildIntent(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            RomType.OPPO -> buildIntent(
                "com.coloros.oppoguardelf",
                "com.coloros.oppoguardelf.MainActivity"
            ) ?: buildIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            RomType.VIVO -> buildIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ) ?: buildIntent(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AutoStartManageActivity"
            )
            RomType.SAMSUNG -> buildIntent(
                "com.samsung.android.sm",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            RomType.MEIZU -> buildIntent(
                "com.meizu.safe",
                "com.meizu.safe.permission.SmartBGActivity"
            )
            else -> null
        }
        return if (intent != null && canResolveIntent(activity, intent)) {
            return runCatching { activity.startActivity(intent) }.isSuccess
        } else false
    }

    fun openAppDetailsSettings(activity: Activity): Boolean {
        return runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
            )
        }.isSuccess
    }

    fun logDeviceInfo(context: Context) {
        LogRepository.append(context, buildString {
            append("DeviceCompat: manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} ")
            append("rom=${detectRomType().name} sdk=${Build.VERSION.SDK_INT} ")
            append("batteryOptExempt=${isIgnoringBatteryOptimizations(context)} ")
            append("isChineseRom=${isChineseRom()}")
        })
    }

    private fun buildIntent(pkg: String, cls: String): Intent? {
        return runCatching {
            Intent().setClassName(pkg, cls)
        }.getOrNull()
    }

    private fun canResolveIntent(context: Context, intent: Intent): Boolean {
        return runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
    }
}
