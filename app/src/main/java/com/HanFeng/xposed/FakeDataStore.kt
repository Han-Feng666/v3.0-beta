package com.HanFeng.xposed

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 跨进程假数据存储。
 *
 * LSPosed hook 模块在 system_server / 各目标应用进程中读取我们 APP 写入的假数据,
 * 必须用文件 + SharedPreferences#WorldReadable 等机制。Android 7+ MODE_WORLD_READABLE 已抛 SecurityException,
 * 所以改用 JSON 文件落地在 /data/local/tmp/.hf_fakedata.json (该路径 root 后 chmod 644 可被任意进程读,
 * 但免 root 时 LSPosed hook 也能读我们 APP 的 filedir 通过 LSPosed 提供的 SharedPreferences#FilenameOverride)。
 *
 * 现实策略:
 * - 主路径: /data/local/tmp/.hf_fake_data.json (root + LSPosed 通用)
 * - 备路径: Context#filesDir/.hf_fake_data.json (我们的私有目录, LSPosed hook 进程通过 XSharedPreferences 读,
 *   需要在 LSPosed 模块配置里声明 readable)
 */
object FakeDataStore {
    const val PREF_NAME = "hanfeng_fake_data"
    const val PUBLIC_PATH = "/data/local/tmp/.hf_fake_data.json"

    const val KEY_WIFI = "wifi"
    const val KEY_LOCATION = "location"
    const val KEY_CELL = "cell"
    const val KEY_ENABLED = "enabled"

    /**
     * 写入假 WiFi 信息。同时落地 JSON 到公开路径 + 私有 PREF,
     * 分别给 root 路径 / xposed 路径使用。
     */
    fun writeWifiInfo(ctx: Context, info: FakeWifiInfo) {
        val json = JSONObject().apply {
            put("enabled", info.enabled)
            put("ssid", info.ssid)
            put("bssid", info.bssid)
            put("mac", info.mac)
            put("rssi", info.rssi)
            put("linkSpeed", info.linkSpeed)
            put("frequency", info.frequency)
            put("ipAddress", info.ipAddress)
            put("networkId", info.networkId)
            put("hiddenSSID", info.hiddenSSID)
            put("scanResults", JSONArray(info.fakeScanResults.map {
                JSONObject().apply {
                    put("ssid", it.ssid); put("bssid", it.bssid); put("rssi", it.rssi)
                    put("frequency", it.frequency); put("channelWidth", it.channelWidth)
                    put("timestamp", it.timestamp); put("capabilities", it.capabilities)
                }
            }))
        }
        val root = JSONObject().apply { put(KEY_WIFI, json) }
        writeBoth(ctx, root)
    }

    /**
     * 写入假定位信息。
     */
    fun writeLocation(ctx: Context, point: FakeLocationPoint) {
        val json = JSONObject().apply {
            put("enabled", point.enabled)
            put("latitude", point.latitude)
            put("longitude", point.longitude)
            put("altitude", point.altitude)
            put("accuracy", point.accuracy)
            put("speed", point.speed)
            put("bearing", point.bearing)
            put("provider", point.provider)
            put("timestamp", point.timestamp)
        }
        val root = JSONObject().apply { put(KEY_LOCATION, json) }
        writeBoth(ctx, root)
    }

    /**
     * 写假基站信息 (hook TelephonyManager 用)。
     */
    fun writeCellInfo(ctx: Context, info: FakeCellInfo) {
        val json = JSONObject().apply {
            put("enabled", info.enabled)
            put("mcc", info.mcc)
            put("mnc", info.mnc)
            put("lac", info.lac)
            put("cid", info.cid)
        }
        val root = JSONObject().apply { put(KEY_CELL, json) }
        writeBoth(ctx, root)
    }

    /**
     * 读假基站信息 (hook 进程读取)。
     */
    fun readCellInfo(): FakeCellInfo? {
        return readPublic().optJSONObject(KEY_CELL)?.let { json ->
            FakeCellInfo(
                enabled = json.optBoolean("enabled", false),
                mcc = json.optInt("mcc", -1),
                mnc = json.optInt("mnc", -1),
                lac = json.optInt("lac", 0),
                cid = json.optInt("cid", 0)
            )
        }
    }

    /**
     * 读假 WiFi 信息 (用于 hook 进程读取, 需要从公开路径读)。
     */
    fun readWifiInfoPublic(): FakeWifiInfo? {
        return readPublic().optJSONObject(KEY_WIFI)?.let { json ->
            val scanArr = json.optJSONArray("scanResults") ?: JSONArray()
            val scans = (0 until scanArr.length()).map { i ->
                val o = scanArr.getJSONObject(i)
                FakeScanResult(
                    ssid = o.optString("ssid"), bssid = o.optString("bssid"),
                    rssi = o.optInt("rssi", -60), frequency = o.optInt("frequency", 2437),
                    channelWidth = o.optInt("channelWidth", 0), timestamp = o.optLong("timestamp"),
                    capabilities = o.optString("capabilities", "[WPA2-PSK-CCMP][ESS]")
                )
            }
            FakeWifiInfo(
                enabled = json.optBoolean("enabled", false),
                ssid = json.optString("ssid"), bssid = json.optString("bssid"),
                mac = json.optString("mac"), rssi = json.optInt("rssi", -55),
                linkSpeed = json.optInt("linkSpeed", 433), frequency = json.optInt("frequency", 2437),
                ipAddress = json.optInt("ipAddress", 0x0100A8C0),
                networkId = json.optInt("networkId", -1),
                hiddenSSID = json.optBoolean("hiddenSSID", false),
                fakeScanResults = scans
            )
        }
    }

    /**
     * 读假定位信息 (用于 hook 进程读取)。
     */
    fun readLocationPublic(): FakeLocationPoint? {
        return readPublic().optJSONObject(KEY_LOCATION)?.let { json ->
            FakeLocationPoint(
                enabled = json.optBoolean("enabled", false),
                latitude = json.optDouble("latitude", 0.0),
                longitude = json.optDouble("longitude", 0.0),
                altitude = json.optDouble("altitude", 0.0),
                accuracy = json.optDouble("accuracy", 5.0).toFloat(),
                speed = json.optDouble("speed", 0.0).toFloat(),
                bearing = json.optDouble("bearing", 0.0).toFloat(),
                provider = json.optString("provider", "gps"),
                timestamp = json.optLong("timestamp")
            )
        }
    }

    /**
     * 读假定位信息 (APP 自身 UI 用)。
     *
     * /data/local/tmp 仅 root/adb 可写, 免 root 时 writeBoth 只落到私有 PREF,
     * 若 UI 也用 readLocationPublic 会永远读不到 → 状态栏恒显"未启动模拟".
     * APP 自己读必须走私有 SharedPreferences, 公开 JSON 只给 LSPosed hook 进程读.
     */
    fun readLocationPrivate(ctx: Context): FakeLocationPoint? {
        val root = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("root", null)
            ?: return null
        return runCatching {
            JSONObject(root).optJSONObject(KEY_LOCATION)?.let { json ->
                FakeLocationPoint(
                    enabled = json.optBoolean("enabled", false),
                    latitude = json.optDouble("latitude", 0.0),
                    longitude = json.optDouble("longitude", 0.0),
                    altitude = json.optDouble("altitude", 0.0),
                    accuracy = json.optDouble("accuracy", 5.0).toFloat(),
                    speed = json.optDouble("speed", 0.0).toFloat(),
                    bearing = json.optDouble("bearing", 0.0).toFloat(),
                    provider = json.optString("provider", "gps"),
                    timestamp = json.optLong("timestamp")
                )
            }
        }.getOrNull()
    }

    /**
     * 清除所有假数据 (停止模拟时调用)。
     */
    fun clearAll(ctx: Context) {
        writeBoth(ctx, JSONObject())
    }

    // ----------------- 内部 -----------------

    private fun writeBoth(ctx: Context, root: JSONObject) {
        // 合并写入: 保留已有键, 避免 wifi/location/cell 三个功能互相覆盖。
        val merged = runCatching {
            val prevTxt = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("root", null) ?: "{}"
            val prev = JSONObject(prevTxt)
            val out = JSONObject()
            val it = prev.keys()
            while (it.hasNext()) { val k = it.next(); out.put(k, prev.get(k)) }
            val it2 = root.keys()
            while (it2.hasNext()) { val k = it2.next(); out.put(k, root.get(k)) }
            out
        }.getOrElse { root }

        // 1. 写私有 PREF (用于我们的 APP 内部读)
        val sp: SharedPreferences = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putString("root", merged.toString()).apply()

        // 2. 写公共 JSON 文件供 LSPosed hook 读
        try {
            java.io.File(PUBLIC_PATH).writeText(merged.toString())
            // 仅自己可读写; LSPosed hook 进程是 root/system 权限能读
            java.io.File(PUBLIC_PATH).setReadable(true, false)
        } catch (e: Exception) {
            // 没写权限(无 root 时), 仅依赖 PREF 路径。LSPosed 通过 XSharedPreferences 读
        }

        // 3. root 可用时把坐标/基站同步到系统属性: 目标 App 进程(untrusted_app 域)读不了
        //    /data/local/tmp 与我们的私有 PREF, 但任何进程都能读 prop。LSPosed hook 在
        //    目标 App 进程内经 prop 拿假数据, 否则 hookLocationInAppProcess / hookTelephonyCell
        //    拿到的 FakeDataStore 恒空 → hook 实际不生效, 表现为"有些 App 仍读真实位置"。
        pushPropsToSystem(merged)
    }

    private fun pushPropsToSystem(root: JSONObject) {
        try {
            val su = com.HanFeng.adblocker.shizuku.SuSession.getInstance()
            if (!su.open(8)) return
            val sb = StringBuilder()
            val loc = root.optJSONObject(KEY_LOCATION)
            if (loc != null) {
                sb.append("setprop sys.hf_fake_loc '")
                    .append(loc.optDouble("latitude", 0.0)).append(',')
                    .append(loc.optDouble("longitude", 0.0)).append(',')
                    .append(if (loc.optBoolean("enabled", false)) '1' else '0')
                    .append("'; ")
            }
            val cell = root.optJSONObject(KEY_CELL)
            if (cell != null) {
                sb.append("setprop sys.hf_fake_cell '")
                    .append(cell.optInt("mcc", -1)).append(',')
                    .append(cell.optInt("mnc", -1)).append(',')
                    .append(cell.optInt("lac", 0)).append(',')
                    .append(cell.optInt("cid", 0)).append(',')
                    .append(if (cell.optBoolean("enabled", false)) '1' else '0')
                    .append("'; ")
            }
            val wifi = root.optJSONObject(KEY_WIFI)
            if (wifi != null) {
                // 用 | 分隔防 SSID 含逗号; scans 简化成 "bssid:rssi:freq|..." 塞进单独 prop
                sb.append("setprop sys.hf_fake_wifi '")
                    .append(if (wifi.optBoolean("enabled", false)) '1' else '0').append('|')
                    .append(wifi.optString("ssid", "")).append('|')
                    .append(wifi.optString("bssid", "")).append('|')
                    .append(wifi.optString("mac", "")).append('|')
                    .append(wifi.optInt("rssi", -55)).append('|')
                    .append(wifi.optInt("linkSpeed", 433)).append('|')
                    .append(wifi.optInt("frequency", 2437))
                    .append("'; ")
                val scans = wifi.optJSONArray("scanResults")
                if (scans != null && scans.length() > 0) {
                    val parts = (0 until scans.length()).mapNotNull { i ->
                        val o = scans.optJSONObject(i)
                        val bssid = o?.optString("bssid", "") ?: return@mapNotNull null
                        if (bssid.isBlank()) return@mapNotNull null
                        "${bssid}:${o.optInt("rssi", -60)}:${o.optInt("frequency", 2437)}"
                    }
                    if (parts.isNotEmpty()) {
                        sb.append("setprop sys.hf_fake_wifi_scans '")
                            .append(parts.joinToString("|")).append("'; ")
                    }
                }
            }
            if (sb.isNotEmpty()) {
                su.execute(sb.toString(), 6)
            }
        } catch (t: Throwable) {
            // prop 同步失败不影响主路径
        }
    }

    private fun readPublic(): JSONObject {
        return try {
            val txt = java.io.File(PUBLIC_PATH).readText()
            if (txt.isBlank()) JSONObject() else JSONObject(txt)
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
