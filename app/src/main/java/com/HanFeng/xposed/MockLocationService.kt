package com.HanFeng.xposed

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlin.random.Random

/**
 * 免 Root 定位模拟服务。
 *
 * 注册自己成系统 LocationProvider "gps", 持续把 FakeDataStore 里假坐标注入系统。
 *
 * 反检测策略:
 * - 卫星数 (satellites): 随机 8-12
 * - HDOP: 随机 0.8-1.5
 * - 速度: 0 时加 0.05-0.3 微扰 (防"完全静止"特征)
 * - bearing: 在原值±5度抖动
 * - accuracy: 4-8 米区间抖动
 * - timestamp: SystemClock.elapsedRealtimeNanos() 而不是 fixed (防固定时戳被检测)
 * - extras 注入 NMEA 字段让 GPS 卫星信息看起来真实
 *
 * 系统会要求开发者选项中 "选择模拟位置应用" = 我们的 APP, 否则 addTestProvider() 会
 * throw SecurityException。我们启动前用 Intent 跳到该选项页提醒用户开。
 */
class MockLocationService : Service() {

    companion object {
        private const val TAG = "MockLocationService"
        const val ACTION_START = "com.HanFeng.action.START_MOCK"
        const val ACTION_STOP = "com.HanFeng.action.STOP_MOCK"
        const val CHANNEL_ID = "hf_mock_location"
        const val NOTIF_ID = 9201
        const val PROVIDER = LocationManager.GPS_PROVIDER

        // 同时注入 gps + network 两个 test provider: 多数地图/打车类 App 会同时请求这两个 provider,
        // 只注入 gps 时它们仍可能拿到真实的 network 定位(表现为"显示真实位置")。
        // 个别 ROM 不允许 addTestProvider("network"), 那一路会静默失败, gps 仍然生效。
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        private const val INTERVAL_MS = 1000L    // 每秒注入一次
    }

    private var running = false
    private var injectThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMockingForeground()
            ACTION_STOP -> { stopMocking(); stopSelf() }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startMockingForeground() {
        startForeground()
        if (running) return
        // 先分两类诊断: ① 定位权限缺失 ② mock 应用未设置 / root 兜底失败。
        // 之前只用 notifyUserGuide() 一条提示, 用户已正确设置 mock 应用仍会看到
        // "需要开发者选项授权" —— 实际真正缺的是运行时定位权限, 提示极具误导性。
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted || !coarseGranted) {
            notifyError("定位权限未授予(ACCESS_FINE/COARSE_LOCATION), 模拟位置无法注入其它 App。请到 设置→应用→HanFeng→权限 授予定位权限")
            stopSelf()
            return
        }
        // root 兜底: 有 root 时直接写入 mock_location 安全设置 + appop, 免去开发者选项手动选择,
        // 之后再走 addTestProvider()。root 不可用或写入失败时才提示用户手动开启。
        if (!hasMockPermission() && !ensureRootMockPermission()) {
            notifyUserGuide()
            stopSelf()
            return
        }
        // 定位总开关必须开启: 关闭时 setTestProviderLocation 注入的数据不会广播给其它 App,
        // 表现为"显示已启用但其它 App 读到真实位置"。有 root 直接打开, 否则提示。
        if (!isLocationEnabled()) {
            if (!ensureLocationEnabledByRoot()) {
                notifyError("系统定位开关未开启, 无法注入模拟位置")
                stopSelf()
                return
            }
        }

        running = true
        injectThread = Thread {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                val activeProviders = mutableListOf<String>()
                for (provider in PROVIDERS) {
                    runCatching {
                        lm.addTestProvider(
                            provider,
                            true,   // requiresNetwork
                            false,  // requiresSatellite
                            false,  // requiresCell
                            false,  // hasMonetaryCost
                            true,   // supportsAltitude
                            true,   // supportsSpeed
                            true,   // supportsBearing
                            android.location.Criteria.POWER_LOW,
                            android.location.Criteria.ACCURACY_FINE
                        )
                        lm.setTestProviderEnabled(provider, true)
                        activeProviders.add(provider)
                    }.onFailure { t ->
                        // 单路失败(如 ROM 限制 network mock)不致命, 其余 provider 仍会生效
                        android.util.Log.w(TAG, "addTestProvider($provider) failed: ${t.message}")
                    }
                }
                if (activeProviders.isEmpty()) {
                    running = false
                    notifyError("addTestProvider 全部失败, 模拟位置不会生效。请确认已在开发者选项把 HanFeng 设为模拟位置应用, 或检查 root 权限")
                    stopSelf()
                    return@Thread
                }
                android.util.Log.i(TAG, "addTestProvider succeeded: $activeProviders")

                var injectFailStreak = 0
                while (running) {
                    // 读私有 PREF: 本服务与 UI 同进程, 免 root 时 /data/local/tmp 不可读,
                    // 用 readLocationPublic() 会一直拿到 null → 永不注入 (见 FakeDataStore 注释).
                    val fake = FakeDataStore.readLocationPrivate(this@MockLocationService)
                    if (fake == null || !fake.enabled) {
                        Thread.sleep(2000); continue
                    }
                    var okInRound = 0
                    for (provider in activeProviders) {
                        val loc = buildLocation(provider, fake)
                        val injected = runCatching { lm.setTestProviderLocation(provider, loc) }.isSuccess
                        if (injected) okInRound++
                    }
                    // 连续多轮全部注入失败 → 说明 mock 权限被系统收回(如 appop 被重置), 及时告知用户
                    if (okInRound == 0) {
                        injectFailStreak++
                        android.util.Log.w(TAG, "setTestProviderLocation failed streak=$injectFailStreak")
                        if (injectFailStreak >= 5) {
                            running = false
                            notifyError("模拟位置注入连续失败, 请确认开发者选项已选 HanFeng 且未改变")
                            stopSelf()
                            return@Thread
                        }
                    } else {
                        injectFailStreak = 0
                    }
                    Thread.sleep(INTERVAL_MS)
                }
                // 退出清理
                for (provider in PROVIDERS) {
                    try { lm.setTestProviderEnabled(provider, false) } catch (e: Throwable) {}
                    try { lm.clearTestProviderLocation(provider) } catch (e: Throwable) {}
                    try { lm.removeTestProvider(provider) } catch (e: Throwable) {}
                }
            } catch (e: SecurityException) {
                // 开发者选项未把本 APP 设为模拟位置应用 (或 ROM 收紧 mock appop)
                running = false
                notifyUserGuide()
                stopSelf()
            } catch (e: Throwable) {
                // 异常时停掉自己并通知用户
                running = false
                notifyError(e.message ?: "MockLocation failed")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopMocking() {
        running = false
        try { injectThread?.interrupt() } catch (e: Throwable) {}
        injectThread = null
        // 主动清掉 provider
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (provider in PROVIDERS) {
                try { lm.setTestProviderEnabled(provider, false) } catch (e: Throwable) {}
                try { lm.clearTestProviderLocation(provider) } catch (e: Throwable) {}
                try { lm.removeTestProvider(provider) } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}
    }

    private fun buildLocation(provider: String, fake: FakeLocationPoint): Location {
        val loc = Location(provider).apply {
            latitude = jitterLat(fake.latitude)
            longitude = jitterLon(fake.longitude)
            altitude = fake.altitude + Random.nextDouble(-1.5, 1.5)
            accuracy = jitterAccuracy(fake.accuracy)
            speed = jitterSpeed(fake.speed)
            bearing = jitterBearing(fake.bearing)
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            // 关键反检测: 不要把 isFromMockProvider=true 放进去
            // Android API 31+ 通过 extras 控制该值, 我们 setExtrasEmpty
        }
        // 注入 NMEA / satellites extras 防检测
        val extras = Bundle().apply {
            // 卫星数 8-12 起
            putInt("satellites", Random.nextInt(8, 13))
            // 海拔精度抖动
            putFloat("verticalAccuracyMeters", Random.nextFloat() * 4 + 2)
            // 速度精度
            putFloat("speedAccuracyMetersPerSecond", Random.nextFloat() * 0.4f + 0.1f)
            // 方位精度
            putFloat("bearingAccuracyDegrees", Random.nextFloat() * 5 + 1)
            // NMEA 标记
            putString("nmea", buildNmea())
            // Android 12+ 把 mock flag 关键字段
            putBoolean("mockLocation", false)
            // 部分 ROM 检测 facilities 标记
            putBoolean("noGPSLocation", false)
        }
        loc.extras = extras
        return loc
    }

    /** 经度抖动 ~0.000005 度 (约 50cm) 防完全静止 */
    private fun jitterLat(lat: Double): Double {
        return lat + Random.nextDouble(-0.000005, 0.000005)
    }
    private fun jitterLon(lng: Double): Double {
        return lng + Random.nextDouble(-0.000005, 0.000005)
    }
    private fun jitterSpeed(s: Float): Float {
        if (s < 0.01f) return Random.nextFloat() * 0.3f + 0.05f   // 静止时也给极小速度
        return s + Random.nextFloat() * 0.5f - 0.25f
    }
    private fun jitterBearing(b: Float): Float {
        val r = b + Random.nextFloat() * 10 - 5
        return if (r < 0) r + 360 else if (r >= 360) r - 360 else r
    }
    private fun jitterAccuracy(a: Float): Float {
        return a + Random.nextFloat() * 4 - 2
    }

    /**
     * 构造一个看起来合法的 NMEA GGA 句子,防 NMEA 检测。
     */
    private fun buildNmea(): String {
        // $GPGGA,hhmmss.ss,ddmm.mmmm,N,dddmm.mmmm,E,1,12,0.8,XXXX.X,M,XX.X,M,,*CC
        val ts = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val hh = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        val ss = cal.get(java.util.Calendar.SECOND).toString().padStart(2, '0')
        val sats = Random.nextInt(8, 13)
        val hdop = String.format("%.1f", Random.nextDouble(0.8, 1.5))
        val alt = String.format("%.1f", Random.nextDouble(40.0, 100.0))
        val gga = "\$GPGGA,${hh}${mm}${ss}.00,0000.0000,N,00000.0000,E,1,${sats},${hdop},${alt},M,0.0,M,,"
        val cksum = gga.substring(1).fold(0) { acc, c -> acc xor c.toInt() }
        return "$gga*${String.format("%02X", cksum)}"
    }

    /**
     * root 兜底: 直接写入 mock_location 安全设置 + mock_location appop,
     * 免去用户手动进开发者选项选择模拟位置应用。
     * 返回是否已获得 mock 权限。
     */
    private fun ensureRootMockPermission(): Boolean {
        return runCatching {
            val su = com.HanFeng.adblocker.shizuku.SuSession.getInstance()
            if (!su.open(8)) return false
            val pkg = packageName
            su.execute(
                "settings put secure mock_location $pkg 2>/dev/null; " +
                    "appops set $pkg android:mock_location allow 2>/dev/null; " +
                    "settings get secure mock_location 2>/dev/null",
                8
            )
            hasMockPermission()
        }.getOrDefault(false)
    }

    private fun hasMockPermission(): Boolean {
        // 运行时定位权限
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse || !fine) return false
        // 关键: 开发者选项 "选择模拟位置信息应用" 必须设为本 APP, 否则 addTestProvider()
        // 会抛 SecurityException. 读 Settings.Secure.mock_location 判断 (值为包名时代表已选中).
        // 老系统该 key 是 "1"/"0" (全局允许), 同样放行.
        val mockSetting = runCatching {
            // MOCK_LOCATION 是 @hide 常量, 公共 SDK 无定义, 用字符串字面量 "mock_location".
            Settings.Secure.getString(contentResolver, "mock_location")
        }.getOrNull()?.trim() ?: ""
        // 标准 ROM 存包名; 老系统存 "1"; 个别 ROM 存 "package:<包名>" 或大小写不一致, 一并兼容。
        return mockSetting == packageName ||
            mockSetting.equals(packageName, ignoreCase = true) ||
            mockSetting == "package:$packageName" ||
            mockSetting == "1"
    }

    private fun isLocationEnabled(): Boolean {
        return runCatching {
            val mode = Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, -1)
            if (mode >= 0) {
                mode != Settings.Secure.LOCATION_MODE_OFF
            } else {
                Settings.Secure.getString(contentResolver, Settings.Secure.LOCATION_PROVIDERS_ALLOWED)
                    ?.contains("gps") == true
            }
        }.getOrDefault(false)
    }

    private fun ensureLocationEnabledByRoot(): Boolean {
        return runCatching {
            val su = com.HanFeng.adblocker.shizuku.SuSession.getInstance()
            if (!su.open(8)) return false
            su.execute("settings put secure location_mode 3 2>/dev/null", 8)
            isLocationEnabled()
        }.getOrDefault(false)
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "HanFeng 定位模拟", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HanFeng 定位模拟")
            .setContentText("正在模拟 GPS 位置")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun notifyUserGuide() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("hf_guide", "HanFeng 提示", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, "hf_guide")
            .setContentTitle("需要设置模拟位置应用")
            .setContentText("请到 设置 → 开发者选项 → 选择模拟位置信息应用, 选 HanFeng; 若已设置仍提示, 请确认已授予本 app 定位权限")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(9202, notif)
    }

    private fun notifyError(msg: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("hf_err", "HanFeng 错误", NotificationManager.IMPORTANCE_HIGH))
        }
        nm.notify(
            9203,
            NotificationCompat.Builder(this, "hf_err")
                .setContentTitle("定位模拟失败")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true).build()
        )
    }
}
