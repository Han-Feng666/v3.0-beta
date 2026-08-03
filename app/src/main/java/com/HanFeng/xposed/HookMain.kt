package com.HanFeng.xposed

import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.random.Random

/**
 * HanFeng LSPosed 模块入口。
 *
 * 工作:
 * - 作用域: system + 联网关键应用(目标 APP 进程)
 * - Hook WifiManager 的常用读 WiFi 信息方法, 返回 FakeDataStore 里的值
 * - 同时 Hook 反检测点: isMockProvider / isFromMockProvider / getMockLocationProvider
 *   抹掉"我们 APP 在 MockLocation 列表里"的痕迹
 *
 * 注意:
 * - WifiInfo 是个 immutable Parcelable, 通过反射改它的字段 (mBSSID / mSSID / mMacAddress)
 * - 新 ROM (Android 12+) 让 WifiInfo 字段 final, 需要先解锁 Field 修改限制 setAccessible(true)
 */
class HookMain : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "HF-Hook"
        private const val SETTINGS_PKG = "com.android.settings"

        // 记录 origin listener → proxy 的映射, removeUpdates 时换回原 listener 才能成功移除
        private val listenerProxies = ConcurrentHashMap<LocationListener, LocationListener>()

        /**
         * 构造假 Location (App 进程内直接返回, 不走系统 mock provider 广播)。
         */
        @JvmStatic
        internal fun buildFakeLocation(fake: FakeLocationPoint, provider: String): Location {
            return Location(provider).apply {
                latitude = fake.latitude + Random.nextDouble(-0.000005, 0.000005)
                longitude = fake.longitude + Random.nextDouble(-0.000005, 0.000005)
                altitude = fake.altitude
                accuracy = fake.accuracy
                speed = fake.speed
                bearing = fake.bearing
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        XposedBridge.log("[$TAG] loaded in $pkg")

        // 1. Hook WiFi Info
        hookWifiManager(lpparam)

        // 2. Hook 目标 App 进程内的定位读取: isFromMockProvider=false + 拦截
        //    getLastKnownLocation / requestLocationUpdates / getCurrentLocation 返回假坐标
        hookLocationInAppProcess(lpparam)

        // 3. Hook 基站定位 (TelephonyManager.getCellLocation / getAllCellInfo / getNeighboringCellInfo)
        hookTelephonyCell(lpparam)

        // 4. Hook MockLocation 反检测 (system_server 进程)
        if (pkg == "android" || pkg == "com.google.android.gms") {
            hookMockLocationAntiDetect(lpparam)
        }
    }

    // ---------------- WiFi Info Hook ----------------

    private fun hookWifiManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 仅在联网关键应用 + system_server 中 hook (省 CPU)
        // 也包括所有 Settings 等用户应用 — 覆盖 LSPosed 默认 scope 用户手动勾选的具体 APP
        val pkg = lpparam.packageName
        if (pkg.startsWith("com.android.")) return  // 系统 UI 等除外
        if (pkg == "com.HanFeng") return  // 不 hook 自己

        val clsWifiManager = XposedHelpers.findClass("android.net.wifi.WifiManager", lpparam.classLoader)
        val clsWifiInfo = XposedHelpers.findClass("android.net.wifi.WifiInfo", lpparam.classLoader)

        // Hook getConnectionInfo: 替换返回的 WifiInfo 里的字段
        XposedHelpers.findAndHookMethod(
            clsWifiManager, "getConnectionInfo",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val fake = FakeDataStore.readWifiInfoPublic() ?: return
                    if (!fake.enabled) return
                    val info = param.result ?: return
                    patchWifiInfo(info, fake)
                }
            }
        )

        // Android 12+ 的 getScanResults 返回 List<ScanResult>
        try {
            XposedHelpers.findAndHookMethod(
                clsWifiManager, "getScanResults",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fake = FakeDataStore.readWifiInfoPublic() ?: return
                        if (!fake.enabled || fake.fakeScanResults.isEmpty()) return
                        @Suppress("UNCHECKED_CAST")
                        val orig = param.result as? List<Any> ?: return
                        // 在原 list 前面插入伪造 scan result + 把第一条改为我们的假 AP
                        val clsScanResult = XposedHelpers.findClass("android.net.wifi.ScanResult", lpparam.classLoader)
                        val fakeResults = fake.fakeScanResults.map { fr ->
                            constructScanResult(clsScanResult, fr)
                        }
                        // 合并: 先假后真 (假排在前面让 APP 当成最近 AP)
                        param.result = fakeResults + orig
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] hookScanResults skip: ${e.message}")
        }

        // Hook getBSSID / getSSID / getConnectionInfo().getXxx 直接走 getConnectionInfo afterHook 已覆盖
        // 单独 hook BSSID getter 是冗余, 仅在被直接通过 connectionInfo 拿时才触发生效

        // Hook startScan: 让 APP 以为扫描失败但下次 getScanResults 仍是我们的伪造值 (避免真扫描覆盖)
        try {
            XposedHelpers.findAndHookMethod(
                clsWifiManager, "startScan",
                XC_MethodReplacement.returnConstant(true)
            )
        } catch (e: Throwable) {
            // 老 ROM 没这方法跳过
        }
    }

    /**
     * 用反射把 WifiInfo 实例的字段改成假数据。
     * Android 12+ WifiInfo 字段是 final, 通过 setAccessible(true) + 反射 setField 操作。
     */
    private fun patchWifiInfo(info: Any, fake: FakeWifiInfo) {
        try {
            // SSID 字段 (Android 11+: mWifiSsid 对象 WifiSsid, 旧版 mSSID String)
            setFieldSilently(info, "mSSID", "\"${fake.ssid}\"")
            try {
                val clsWifiSsid = XposedHelpers.findClass("android.net.wifi.WifiSsid", info.javaClass.classLoader)
                val ssidObj = clsWifiSsid.getMethod("fromUtf8Text", ByteArray::class.java)
                    .invoke(null, fake.ssid.toByteArray())
                    ?: clsWifiSsid.getMethod("createFromAsciiExpressed", String::class.java)
                        .invoke(null, "\"${fake.ssid}\"")
                setFieldSilently(info, "mWifiSsid", ssidObj)
            } catch (e: Throwable) {
                // 没 WifiSsid 类, 旧版用 mSSID String 即可
            }
            setFieldSilently(info, "mBSSID", fake.bssid)
            setFieldSilently(info, "mMacAddress", fake.mac)
            setFieldSilently(info, "mRssi", fake.rssi)
            setFieldSilently(info, "mLinkSpeed", fake.linkSpeed)
            setFieldSilently(info, "mFrequency", fake.frequency)
            setFieldSilently(info, "mIpAddress", fake.ipAddress)
            setFieldSilently(info, "mNetworkId", fake.networkId)
            setFieldSilently(info, "mHiddenSSID", fake.hiddenSSID)
            // Android 12+ 还有 mPasspoint/FQDN, 留默认不补

            XposedBridge.log("[HF-Hook] patched WifiInfo ssid=${fake.ssid} bssid=${fake.bssid}")
        } catch (e: Throwable) {
            XposedBridge.log("[HF-Hook] patchWifiInfo failed: ${e.message}")
        }
    }

    private fun setFieldSilently(target: Any, fieldName: String, value: Any?) {
        try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            // Android 12+ final 字段: 通过 base type 找 declared field, 用 setAccessible 解锁修改
            // 反射 setField 在 ART 上对 final 非 String 仍可写 (除非被 strict final 标记)
            XposedHelpers.setObjectField(target, fieldName, value)
        } catch (e: Throwable) {
            // 字段名变了或不存在, 忽略
        }
    }

    /**
     * 构造 ScanResult 实例 (默认构造无参 + 反射塞值)。
     */
    private fun constructScanResult(clsScanResult: Class<*>, fr: FakeScanResult): Any {
        // 优先用无参构造
        val instance = try {
            clsScanResult.getDeclaredConstructor().newInstance()
        } catch (e: Throwable) {
            // 某些 ROM 上 ScanResult 仅有两参构造
            clsScanResult.getDeclaredConstructor(java.lang.String::class.java, java.lang.String::class.java)
                .newInstance(fr.ssid, fr.bssid)
        }
        try {
            XposedHelpers.setObjectField(instance, "SSID", fr.ssid)
            XposedHelpers.setObjectField(instance, "BSSID", fr.bssid)
            XposedHelpers.setIntField(instance, "level", fr.rssi)
            XposedHelpers.setIntField(instance, "frequency", fr.frequency)
            XposedHelpers.setLongField(instance, "timestamp", fr.timestamp)
            try {
                XposedHelpers.setObjectField(instance, "capabilities", fr.capabilities)
            } catch (e: Throwable) {}
            try {
                XposedHelpers.setIntField(instance, "channelWidth", fr.channelWidth)
            } catch (e: Throwable) {}
        } catch (e: Throwable) {
            XposedBridge.log("[HF-Hook] constructScanResult failed: ${e.message}")
        }
        return instance
    }

    // ---------------- 目标 App 进程内定位 hook ----------------

    /**
     * 在目标 App 进程内 hook 定位读取链路。
     *
     * 只注入 gps/network test provider 时, 很多 App 根本不用 LocationManager 的 mock 结果:
     * - App 检测到 isFromMockProvider()==true 会丢弃 mock 坐标回退真实定位;
     * - App 用 getLastKnownLocation / requestLocationUpdates / getCurrentLocation 时,
     *   mock 注入也要看系统是否广播; 部分 ROM 不广播给非特权 App。
     *
     * 这里直接在本进程覆盖:
     * - Location.isFromMockProvider() → false (App 不会因"是 mock"而丢弃)
     * - getLastKnownLocation(String) → 假坐标
     * - requestLocationUpdates(各重载) → 包装 LocationListener, 回调注入假坐标
     * - getCurrentLocation(...) (API 30+) → 直接回调假坐标
     */
    private fun hookLocationInAppProcess(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg.startsWith("com.android.")) return
        if (pkg == "com.HanFeng") return
        try {
            val clsLoc = XposedHelpers.findClass("android.location.Location", lpparam.classLoader)
            try {
                XposedHelpers.findAndHookMethod(
                    clsLoc, "isFromMockProvider",
                    XC_MethodReplacement.returnConstant(false)
                )
            } catch (e: Throwable) {}

            val clsLocMan = XposedHelpers.findClass("android.location.LocationManager", lpparam.classLoader)

            // getLastKnownLocation(String) → 假坐标
            try {
                XposedHelpers.findAndHookMethod(
                    clsLocMan, "getLastKnownLocation",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val fake = FakeDataStore.readLocationPublic() ?: return
                            if (!fake.enabled) return
                            val provider = param.args.getOrNull(0) as? String ?: fake.provider
                            param.result = buildFakeLocation(fake, provider)
                        }
                    }
                )
            } catch (e: Throwable) {}

            // requestLocationUpdates(String, long, float, LocationListener)
            try {
                XposedHelpers.findAndHookMethod(
                    clsLocMan, "requestLocationUpdates",
                    String::class.java, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    LocationListener::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { wrapListenerIfNeeded(param, 3) }
                    }
                )
            } catch (e: Throwable) {}
            // requestLocationUpdates(String, long, float, LocationListener, Looper)
            try {
                XposedHelpers.findAndHookMethod(
                    clsLocMan, "requestLocationUpdates",
                    String::class.java, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    LocationListener::class.java, Looper::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { wrapListenerIfNeeded(param, 3) }
                    }
                )
            } catch (e: Throwable) {}
            // requestLocationUpdates(String, long, float, Criteria, LocationListener, Looper)
            try {
                XposedHelpers.findAndHookMethod(
                    clsLocMan, "requestLocationUpdates",
                    String::class.java, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Criteria::class.java, LocationListener::class.java, Looper::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { wrapListenerIfNeeded(param, 4) }
                    }
                )
            } catch (e: Throwable) {}
            // requestLocationUpdates(LocationRequest, LocationListener, Looper)  (API 19+ @hide)
            try {
                XposedHelpers.findAndHookMethod(
                    clsLocMan, "requestLocationUpdates",
                    LocationRequest::class.java, LocationListener::class.java, Looper::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { wrapListenerIfNeeded(param, 1) }
                    }
                )
            } catch (e: Throwable) {}
            // requestLocationUpdates(LocationRequest, Executor, LocationListener)  (API 30+)
            try {
                XposedHelpers.findAndHookMethod(
                    clsLocMan, "requestLocationUpdates",
                    LocationRequest::class.java, Executor::class.java, LocationListener::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { wrapListenerIfNeeded(param, 2) }
                    }
                )
            } catch (e: Throwable) {}

            // removeUpdates: 换回原 listener 保证能成功移除
            try {
                XposedHelpers.findAndHookMethod(
                    clsLocMan, "removeUpdates",
                    LocationListener::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val orig = param.args.getOrNull(0) as? LocationListener ?: return
                            val proxy = listenerProxies.remove(orig)
                            if (proxy != null) param.args[0] = proxy
                        }
                    }
                )
            } catch (e: Throwable) {}

            // getCurrentLocation(String, CancellationSignal, Executor, Consumer<Location>)  (API 30+)
            try {
                XposedHelpers.findAndHookMethod(
                    clsLocMan, "getCurrentLocation",
                    String::class.java, CancellationSignal::class.java,
                    Executor::class.java, Consumer::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            val fake = FakeDataStore.readLocationPublic()
                            if (fake?.enabled == true) {
                                val provider = param.args.getOrNull(0) as? String ?: fake.provider
                                val exec = param.args.getOrNull(2) as? Executor
                                @Suppress("UNCHECKED_CAST")
                                val consumer = param.args.getOrNull(3) as? Consumer<Location>
                                if (exec != null && consumer != null) {
                                    exec.execute { consumer.accept(buildFakeLocation(fake, provider)) }
                                    return null
                                }
                            }
                            return invokeOriginal(param)
                        }
                    }
                )
            } catch (e: Throwable) {}
            // getCurrentLocation(LocationRequest, CancellationSignal, Executor, Consumer<Location>)
            try {
                XposedHelpers.findAndHookMethod(
                    clsLocMan, "getCurrentLocation",
                    LocationRequest::class.java, CancellationSignal::class.java,
                    Executor::class.java, Consumer::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            val fake = FakeDataStore.readLocationPublic()
                            if (fake?.enabled == true) {
                                val exec = param.args.getOrNull(2) as? Executor
                                @Suppress("UNCHECKED_CAST")
                                val consumer = param.args.getOrNull(3) as? Consumer<Location>
                                if (exec != null && consumer != null) {
                                    exec.execute { consumer.accept(buildFakeLocation(fake, "gps")) }
                                    return null
                                }
                            }
                            return invokeOriginal(param)
                        }
                    }
                )
            } catch (e: Throwable) {}
        } catch (e: Throwable) {
            XposedBridge.log("[HF-Hook] hookLocationInAppProcess skip: ${e.message}")
        }
    }

    /**
     * 把 requestLocationUpdates 里的 listener 包装成 FakeLocationListenerProxy,
     * 回调时注入假坐标。App 拿到的是原 listener 被替换后的 proxy, 无需感知。
     */
    private fun wrapListenerIfNeeded(param: XC_MethodHook.MethodHookParam, index: Int) {
        if (index < 0 || index >= param.args.size) return
        val orig = param.args[index] as? LocationListener ?: return
        if (orig is FakeLocationListenerProxy) return
        val fake = FakeDataStore.readLocationPublic() ?: return
        if (!fake.enabled) return
        val proxy = FakeLocationListenerProxy(orig, fake.provider)
        listenerProxies[orig] = proxy
        param.args[index] = proxy
    }

    private fun invokeOriginal(param: XC_MethodHook.MethodHookParam): Any? {
        return try {
            val m: Method = param.method as Method
            m.invoke(param.thisObject, *param.args)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 构造假 Location (App 进程内直接返回, 不走系统 mock provider 广播)。
     */
    private fun buildFakeLocation(fake: FakeLocationPoint, provider: String): Location {
        return Location(provider).apply {
            latitude = fake.latitude + Random.nextDouble(-0.000005, 0.000005)
            longitude = fake.longitude + Random.nextDouble(-0.000005, 0.000005)
            altitude = fake.altitude
            accuracy = fake.accuracy
            speed = fake.speed
            bearing = fake.bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    /**
     * LocationListener 代理: 假坐标开启时回调注入假值, 关闭时透传原值。
     */
    private class FakeLocationListenerProxy(
        private val original: LocationListener,
        private val provider: String
    ) : LocationListener {

        override fun onLocationChanged(location: Location) {
            val fake = FakeDataStore.readLocationPublic()
            if (fake?.enabled == true) {
                original.onLocationChanged(HookMain.buildFakeLocation(fake, provider))
            } else {
                original.onLocationChanged(location)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            original.onStatusChanged(provider, status, extras)
        }

        override fun onProviderEnabled(provider: String) {
            original.onProviderEnabled(provider)
        }

        override fun onProviderDisabled(provider: String) {
            original.onProviderDisabled(provider)
        }
    }

    // ---------------- 基站定位 hook ----------------

    /**
     * Hook TelephonyManager 基站定位接口, 返回假 LAC/CID。
     *
     * 高德/百度等定位 SDK 的"网络定位"主要把 LAC+CID (getCellLocation) 上报到服务器
     * 查基站库换坐标。覆盖这几处让 App 读不到真实基站, 迫使它走我们注入的假 gps。
     */
    private fun hookTelephonyCell(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg.startsWith("com.android.")) return
        if (pkg == "com.HanFeng") return
        try {
            val clsTm = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)

            // getCellLocation() → 反射改 GsmCellLocation/CdmaCellLocation 的 lac/cid
            try {
                XposedHelpers.findAndHookMethod(
                    clsTm, "getCellLocation",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val cell = param.result ?: return
                            val fake = FakeDataStore.readCellInfo() ?: return
                            if (!fake.enabled) return
                            try {
                                val clsGsm = XposedHelpers.findClass("android.telephony.gsm.GsmCellLocation", lpparam.classLoader)
                                if (clsGsm.isInstance(cell)) {
                                    XposedHelpers.setIntField(cell, "lac", fake.lac)
                                    XposedHelpers.setIntField(cell, "cid", fake.cid)
                                    if (fake.mcc >= 0) setIntSilently(cell, "mcc", fake.mcc)
                                    if (fake.mnc >= 0) setIntSilently(cell, "mnc", fake.mnc)
                                }
                            } catch (e: Throwable) {}
                            try {
                                val clsCdma = XposedHelpers.findClass("android.telephony.cdma.CdmaCellLocation", lpparam.classLoader)
                                if (clsCdma.isInstance(cell)) {
                                    setIntSilently(cell, "mBaseStationId", fake.cid)
                                    setIntSilently(cell, "mNetworkId", fake.lac)
                                    setIntSilently(cell, "mSystemId", fake.lac)
                                }
                            } catch (e: Throwable) {}
                        }
                    }
                )
            } catch (e: Throwable) {}

            // getAllCellInfo() → 改 CellIdentity 的 mcc/mnc/lac/cid 等字段
            try {
                XposedHelpers.findAndHookMethod(
                    clsTm, "getAllCellInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            @Suppress("UNCHECKED_CAST")
                            val list = param.result as? List<*> ?: return
                            val fake = FakeDataStore.readCellInfo() ?: return
                            if (!fake.enabled) return
                            for (ci in list) {
                                val cell = ci ?: continue
                                patchCellIdentity(cell, fake)
                            }
                        }
                    }
                )
            } catch (e: Throwable) {}

            // getNeighboringCellInfo() (旧 API)
            try {
                XposedHelpers.findAndHookMethod(
                    clsTm, "getNeighboringCellInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            @Suppress("UNCHECKED_CAST")
                            val list = param.result as? List<*> ?: return
                            val fake = FakeDataStore.readCellInfo() ?: return
                            if (!fake.enabled) return
                            for (ci in list) {
                                val cell = ci ?: continue
                                try {
                                    XposedHelpers.setIntField(cell, "lac", fake.lac)
                                    XposedHelpers.setIntField(cell, "cid", fake.cid)
                                } catch (e: Throwable) {}
                            }
                        }
                    }
                )
            } catch (e: Throwable) {}
        } catch (e: Throwable) {
            XposedBridge.log("[HF-Hook] hookTelephonyCell skip: ${e.message}")
        }
    }

    /**
     * 反射改写 CellInfo 内部 CellIdentity 的基站字段。
     * 各制式字段名不同: GSM/WCDMA/TD-SCDMA 用 mLac/mCid, LTE 用 mTac/mCi, CDMA 用 mBasestationId 等。
     * mcc/mnc 保持真实值 (除非 fake 显式配置), 避免 App 判断运营商错误。
     */
    private fun patchCellIdentity(cellInfo: Any, fake: FakeCellInfo) {
        try {
            val ident = XposedHelpers.getObjectField(cellInfo, "mCellIdentity") ?: return
            if (fake.mcc >= 0) setIntSilently(ident, "mMcc", fake.mcc)
            if (fake.mnc >= 0) setIntSilently(ident, "mMnc", fake.mnc)
            setIntSilently(ident, "mLac", fake.lac)
            setIntSilently(ident, "mCid", fake.cid)
            setIntSilently(ident, "mCi", fake.cid)
            setIntSilently(ident, "mTac", fake.lac)
            setIntSilently(ident, "mBasestationId", fake.cid)
            setIntSilently(ident, "mNetworkId", fake.lac)
            setIntSilently(ident, "mSystemId", fake.lac)
        } catch (e: Throwable) {
            XposedBridge.log("[HF-Hook] patchCellIdentity failed: ${e.message}")
        }
    }

    private fun setIntSilently(target: Any, fieldName: String, value: Int) {
        try {
            XposedHelpers.setIntField(target, fieldName, value)
        } catch (e: Throwable) {}
    }

    // ---------------- MockLocation 反检测 ----------------

    /**
     * 在 system_server + Google Play Services 中 hook 反 mock 检测, 让 APP 拿到的
     * "当前正在用 mock provider 吗"返回 false,
     * Location.isFromMockProvider() 也返回 false。
     */
    private fun hookMockLocationAntiDetect(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clsLoc = XposedHelpers.findClass("android.location.Location", lpparam.classLoader)
            // Location.isFromMockProvider()
            XposedHelpers.findAndHookMethod(
                clsLoc, "isFromMockProvider",
                XC_MethodReplacement.returnConstant(false)
            )
        } catch (e: Throwable) {
            XposedBridge.log("[HF-Hook] hookIsFromMockProvider skip: ${e.message}")
        }

        try {
            val clsLocMan = XposedHelpers.findClass("android.location.LocationManager", lpparam.classLoader)
            // isProviderEnabledForPrivileged / isMockProvider
            for (name in listOf("isMockProvider", "isProviderEnabledForPrivileged")) {
                try {
                    XposedHelpers.findAndHookMethod(
                        clsLocMan, name,
                        String::class.java,
                        XC_MethodReplacement.returnConstant(false)
                    )
                } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}

        // Hook Settings.Secure.getString(ALLOW_MOCK_LOCATION) 返回 "0" (假装没开模拟)
        try {
            val clsSettings = XposedHelpers.findClass("android.provider.Settings\$Secure", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clsSettings, "getString",
                android.content.ContentResolver::class.java,
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(1) as? String ?: return
                        if (key == "mock_location") {
                            param.result = "0"
                        }
                    }
                }
            )
        } catch (e: Throwable) {}
    }
}
