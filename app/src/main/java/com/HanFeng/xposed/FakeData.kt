package com.HanFeng.xposed

/**
 * 跨进程共享的假 WiFi 信息模型。
 *
 * 只能在 app + xposed hook 进程中共享,序列化用SharedPreferences 持久化(跨读写)。
 * 字段全部字符串,BSSID/MAC 用冒号分隔(00:11:22:33:44:55)。
 */
data class FakeWifiInfo(
    val enabled: Boolean = false,
    val ssid: String = "",        // 不带引号的裸 SSID, 例如 HanFeng-WiFi
    val bssid: String = "",       // 形式 AA:BB:CC:DD:EE:FF, 大写或小写都行
    val mac: String = "",         // 客户端 MAC (RandomMAC/固定), 同样 AA:BB:CC:DD:EE:FF
    val rssi: Int = -55,           // 信号强度 dBm, -30 (极佳) ~ -100 (极弱)
    val linkSpeed: Int = 433,      // Mbps, 连接速率
    val frequency: Int = 2437,      // MHz, 2412~2484 (2.4G) / 5180~5885 (5G)
    val ipAddress: Int = 0x0100A8C0, // 点分十进制 192.168.0.1 大端 int 表示
    val networkId: Int = -1,        // WifiConfigStore 内部 networkId, 模拟用 -1 没配置
    val hiddenSSID: Boolean = false,
    val fakeScanResults: List<FakeScanResult> = emptyList()
)

/**
 * 模拟扫描结果 (getScanResults 返回的项)。
 */
data class FakeScanResult(
    val ssid: String = "",
    val bssid: String = "",
    val rssi: Int = -60,
    val frequency: Int = 2437,
    val channelWidth: Int = 0,    // 0=20MHz,1=40MHz,2=80MHz,3=160MHz
    val timestamp: Long = System.currentTimeMillis() * 1000, // 微秒
    val capabilities: String = "[WPA2-PSK-CCMP][ESS]"
)

/**
 * 假基站信息 (用于 Xposed hook TelephonyManager 基站定位)。
 *
 * LAC/CID 是基站定位的核心字段: 高德/百度等 SDK 会把 LAC+CID 上报到自家服务器
 * 查基站数据库换坐标。覆盖这两字段即可让它们读不到真实基站。
 * mcc/mnc 默认 -1 表示继承设备真实运营商 (避免 App 判断运营商错误)。
 */
data class FakeCellInfo(
    val enabled: Boolean = false,
    val mcc: Int = -1,        // -1 继承真实 MCC
    val mnc: Int = -1,        // -1 继承真实 MNC
    val lac: Int = 0,         // 位置区码
    val cid: Int = 0          // 小区 ID
)

/**
 * 假定位坐标点 (用于 MockLocationProvider 注入)。
 */
data class FakeLocationPoint(
    val enabled: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,        // 米 (海拔)
    val accuracy: Float = 5.0f,         // 米 (水平精度)
    val speed: Float = 0.0f,            // 米/秒
    val bearing: Float = 0.0f,          // 度
    val provider: String = "gps",       // "gps" / "network" / "fused" / "passive"
    val timestamp: Long = System.currentTimeMillis()
)
