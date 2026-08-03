package com.HanFeng.adblocker.shizuku

import android.util.Log

/**
 * Root WiFi 信息改写工具。
 *
 * 三个层次持久化 / 生效:
 * 1. 网卡 MAC: ip link set wlan0 down + address XX:XX:XX:XX:XX:XX + up. vendor init watchdog 会还原,
 *    仅作为短暂生效;持久化走 resetprop 模块 service.d 脚本重放。
 * 2. Prop 写入: wlan.ssid / wlan.bssid / wlan.mac (部分 ROM vendor init 写这些, WifiManager 可能读),
 *    持久化到统一 module 配置。
 * 3. WifiConfigStore.xml 注入: 删原 + 写新版 SSID/BSSID, force-stop com.android.wifi 让其重读,
 *    适配 AOSP/Pixel;MIUI/HyperOS 文件加密则跳过仅做日志。
 *
 * 同时配合 LSPosed HookMain 走 hook 真改 APP 端读到的值, Root 路径算合并加固。
 */
class WifiModifier {

    companion object {
        private const val TAG = "HF-WifiModifier"
        private const val CONF_DIR = "/data/adb/hf_dictator"
        private const val CONF_FILE = "$CONF_DIR/wifi_mac.conf"
        private const val BACKUP_FILE = "$CONF_DIR/wifi_mac_backup"
        private const val WIFICONFIG_PATH = "/data/misc/wifi/WifiConfigStore.xml"
        private const val WIFICONFIG_BACKUP = "$CONF_DIR/wificonfig_backup.xml"
    }

    data class ShellResult(val exitCode: Int, val output: String)

    /** 走 SuSession 执行一条 shell, 返回统一 ShellResult。 */
    private fun exec(command: String): ShellResult {
        val r = SuSession.getInstance().execute(command)
        return ShellResult(r.exitCode, r.output)
    }

    /** 一次执行多条命令用换行分隔。 */
    private fun execLines(lines: List<String>): ShellResult {
        return exec(lines.joinToString("\n"))
    }

    /**
     * 改 wlan0 网卡 MAC。备份原值, 应用新值, 落地到模块开机重放。
     */
    fun setMacAddress(iface: String = "wlan0", newMac: String): ShellResult {
        if (!isValidMac(newMac)) {
            return ShellResult(-1, "MAC 格式不合法 (应为 AA:BB:CC:DD:EE:FF)")
        }
        val sb = StringBuilder()
        try {
            ensureConfDir()
            // 备份原 MAC (首次)
            val curMacRes = exec("cat /sys/class/net/$iface/address 2>/dev/null")
            val curMac = curMacRes.output.trim()
            if (curMac.isNotBlank() && !fileExists(BACKUP_FILE)) {
                exec("echo '$curMac' > $BACKUP_FILE")
            }
            val ifaceReal = resolveIface(iface)
            // 关闭网卡 → 改 MAC → 启动网卡
            val cmds = """
                ip link set $ifaceReal down
                ip link set $ifaceReal address $newMac
                ip link set $ifaceReal up
                cat /sys/class/net/$ifaceReal/address
            """.trimIndent()
            val r = exec(cmds)
            val appliedMac = r.output.trim().lineSequence().lastOrNull { it.isNotBlank() } ?: ""
            sb.append("应用结果 exit=${r.exitCode}\n")
            sb.append("读取应用后 MAC: $appliedMac\n")

            // 落地配置 + 安装开机重放脚本
            exec("echo '$newMac' > $CONF_FILE")
            sb.append(installMacReplayScript(ifaceReal, newMac))
            sb.append("持久化已写入: $CONF_FILE + Magisk service.d 脚本")
            return ShellResult(r.exitCode, sb.toString())
        } catch (e: Throwable) {
            Log.e(TAG, "setMacAddress failed", e)
            return ShellResult(-1, "改 MAC 失败: ${e.message}")
        }
    }

    /**
     * 写 prop, 部分 ROM 的 WifiManager 会读这些 prop 作为兜底。
     */
    fun setWifiProps(ssid: String?, bssid: String?, mac: String?): ShellResult {
        val cmds = mutableListOf<String>()
        if (!ssid.isNullOrBlank()) {
            cmds.add("resetprop wlan.ssid '$ssid' 2>/dev/null || setprop wlan.ssid '$ssid' 2>/dev/null || true")
        }
        if (!bssid.isNullOrBlank()) {
            cmds.add("resetprop wlan.bssid '$bssid' 2>/dev/null || setprop wlan.bssid '$bssid' 2>/dev/null || true")
        }
        if (!mac.isNullOrBlank()) {
            cmds.add("resetprop wlan.mac '$mac' 2>/dev/null || setprop wlan.mac '$mac' 2>/dev/null || true")
            cmds.add("resetprop persist.sys.wifi.mac '$mac' 2>/dev/null || true")
        }
        if (cmds.isEmpty()) return ShellResult(-1, "无可写字段 (ssid/bssid/mac 全空)")
        cmds.add("echo 'WIFI_PROP_DONE'")
        val r = execLines(cmds)
        return ShellResult(r.exitCode, r.output)
    }

    /**
     * WifiConfigStore.xml 注入新的 SSID/BSSID。
     * 仅 AOSP/Pixel 已验证; MIUI/HyperOS 加密版本会失败 (该场景只能靠 LSPosed hook)。
     * 完整重写文件, 写入我们伪 AP 的 SSID/BSSID 节点。
     */
    fun setWifiConfigStore(ssid: String, bssid: String): ShellResult {
        if (ssid.isBlank() || !isValidMac(bssid)) {
            return ShellResult(-1, "SSID 或 BSSID 格式不合法 (BSSID 应为 AA:BB:CC:DD:EE:FF)")
        }
        val sb = StringBuilder()
        if (!fileExists(WIFICONFIG_PATH)) {
            sb.append("WifiConfigStore.xml 不存在 ($WIFICONFIG_PATH), 跳过 (可能 ROM 用新型 WifiConfigStoreSoftAp)\n")
            return ShellResult(0, sb.toString())
        }
        try {
            // 备份
            if (!fileExists(WIFICONFIG_BACKUP)) {
                exec("cp $WIFICONFIG_PATH $WIFICONFIG_BACKUP")
                sb.append("已备份原 WifiConfigStore 到 $WIFICONFIG_BACKUP\n")
            }
            // 用 sed 替换 SSID/BSSID 节点
            // WifiConfigStore.xml 格式 <string name="SSID">"<HanFeng>"</string>
            //                       <string name="BSSID">XX:XX:XX:XX:XX:XX</string>
            val amp = "&"
            val escSsid = ssid
                .replace("\"", amp + "quot" + ";")
                .replace("<", amp + "lt" + ";")
                .replace(">", amp + "gt" + ";")
            val cmds = """
                sed -i 's|<string name="SSID">"[^<]*"</string>|<string name="SSID">"${escSsid}"</string>|g' $WIFICONFIG_PATH
                sed -i 's|<string name="BSSID">[^<]*</string>|<string name="BSSID">${bssid}</string>|g' $WIFICONFIG_PATH
                md5sum $WIFICONFIG_PATH
            """.trimIndent()
            val r = exec(cmds)
            sb.append("sed 应用结果 exit=${r.exitCode}\n")

            // 强让 wifi 服务重读
            exec("am force-stop com.android.wifi 2>/dev/null || true")
            exec("stop wifi 2>/dev/null || start wifi 2>/dev/null || true")
            exec("killall com.android.wifi 2>/dev/null || true")
            sb.append("已强制 com.android.wifi 重启加载新配置\n")
            sb.append("\n(若 WifiConfigStore 是加密版会跳过生效, 仅靠 LSPosed hook 继续生效)")
            return ShellResult(r.exitCode, sb.toString())
        } catch (e: Throwable) {
            return ShellResult(-1, "WifiConfigStore 修改失败: ${e.message}")
        }
    }

    /**
     * 恢复 MAC + prop + WifiConfigStore 原值。
     */
    fun restore(): ShellResult {
        val sb = StringBuilder()
        val macBackupRes = exec("test -f $BACKUP_FILE && cat $BACKUP_FILE 2>/dev/null || echo 'NO_BACKUP'")
        val macBackup = macBackupRes.output.trim()
        if (macBackup.isNotBlank() && macBackup != "NO_BACKUP") {
            val ifaceReal = resolveIface("wlan0")
            exec("""
                ip link set $ifaceReal down 2>/dev/null
                ip link set $ifaceReal address $macBackup 2>/dev/null
                ip link set $ifaceReal up 2>/dev/null
            """.trimIndent())
            sb.append("已恢复 MAC 到 $macBackup\n")
        }
        exec("rm -f $CONF_FILE $BACKUP_FILE")
        // 删开机重放脚本
        exec("rm -f /data/adb/service.d/hf_wifi_mac.sh")
        // 清 prop
        exec("""
            resetprop --delete wlan.ssid 2>/dev/null || true
            resetprop --delete wlan.bssid 2>/dev/null || true
            resetprop --delete wlan.mac 2>/dev/null || true
            resetprop --delete persist.sys.wifi.mac 2>/dev/null || true
        """.trimIndent())
        sb.append("已清 prop 持久化\n")
        // 恢复 WifiConfigStore.xml
        if (fileExists(WIFICONFIG_BACKUP)) {
            exec("cp $WIFICONFIG_BACKUP $WIFICONFIG_PATH")
            exec("am force-stop com.android.wifi 2>/dev/null || true")
            sb.append("已恢复 WifiConfigStore.xml 自备份\n")
        }
        return ShellResult(0, sb.toString())
    }

    /**
     * 读当前 MAC + SSID + BSSID 摘要供 UI 展示。
     */
    fun readCurrent(): String {
        val mac0 = exec("cat /sys/class/net/wlan0/address 2>/dev/null").output.trim()
        val mac1 = exec("cat /sys/class/net/wlan1/address 2>/dev/null").output.trim()
        val curSsid = exec("getprop wlan.ssid 2>/dev/null").output.trim()
        val curBssid = exec("getprop wlan.bssid 2>/dev/null").output.trim()
        val prMac = exec("getprop persist.sys.wifi.mac 2>/dev/null").output.trim()
        val sb = StringBuilder()
        sb.append("wlan0 MAC: ${mac0.ifBlank { "(空)" }}\n")
        if (mac1.isNotBlank()) sb.append("wlan1 MAC: $mac1\n")
        sb.append("wlan.ssid prop: ${curSsid.ifBlank { "(空)" }}\n")
        sb.append("wlan.bssid prop: ${curBssid.ifBlank { "(空)" }}\n")
        sb.append("persist.sys.wifi.mac prop: ${prMac.ifBlank { "(空)" }}\n")
        sb.append("WifiConfigStore.xml 备份: ${if (fileExists(WIFICONFIG_BACKUP)) "存在" else "无"}\n")
        sb.append("MAC 备份: ${if (fileExists(BACKUP_FILE)) "存在" else "无"}\n")
        sb.append("MAC 模块重放脚本: ${if (fileExists("/data/adb/service.d/hf_wifi_mac.sh")) "已安装" else "未安装"}")
        return sb.toString()
    }

    // ---------------- 私有 ----------------

    private fun ensureConfDir() { exec("mkdir -p $CONF_DIR") }

    /** Android 设备某些 ROM 把 wifi 接口命名为 wlan0 / swlan0 / wifi0, 实际匹配 */
    private fun resolveIface(default: String): String {
        val candidates = listOf(default, "wlan0", "swlan0", "wifi0", "wlan1")
        for (c in candidates) {
            val rx = exec("test -d /sys/class/net/$c && echo OK").output.trim()
            if (rx == "OK") return c
        }
        return default
    }

    private fun fileExists(path: String): Boolean {
        return exec("test -f $path && echo OK").output.trim() == "OK"
    }

    private fun isValidMac(mac: String): Boolean {
        val m = mac.trim()
        // AA:BB:CC:DD:EE:FF (冒号分隔, 不区分大小写)
        if (!Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").matches(m)) return false
        // 第二位最低位不能是 1/3/5/7/9/F (locally administered bit) — 但工具允许改成 locally administered,
        // 因为大多数 APP 不严格校验。仅强制首位不能是全 1 (广播)
        val firstOctet = m.substring(0, 2).toInt(16)
        if (firstOctet == 0xFF) return false
        return true
    }

    /**
     * 写入开机重放脚本到 Magisk service.d, 让重启后自动应用 MAC。
     */
    private fun installMacReplayScript(iface: String, mac: String): String {
        val script = """#!/system/bin/sh
# HanFeng WiFi MAC 重放脚本
IFACE=$iface
MAC=$mac
# 等待 wifi 接口起来
i=0
while [ ! -d /sys/class/net/${'$'}IFACE ] && [ ${'$'}i -lt 60 ]; do
    sleep 1
    i=${'$'}((i+1))
done
if [ -d /sys/class/net/${'$'}IFACE ]; then
    ip link set ${'$'}IFACE down 2>/dev/null || true
    ip link set ${'$'}IFACE address ${'$'}MAC 2>/dev/null || true
    ip link set ${'$'}IFACE up 2>/dev/null || true
else
    echo "[hf_wifi_mac] iface ${'$'}IFACE not found after 60s" >> /data/adb/hf_dictator/wifi_mac.log
fi
""".trimIndent()
        val scriptPath = "$CONF_DIR/wifi_mac.sh"
        exec("cat > '$scriptPath' <<'HANFENG_EOF'\n$script\nHANFENG_EOF")
        exec("chmod +x '$scriptPath'")
        // 安装到 magisk service.d
        val installCmd = """
            MAGISK_SERVICE_DIR=/data/adb/service.d
            if [ -d "${'$'}MAGISK_SERVICE_DIR" ]; then
                ln -sf "$scriptPath" "${'$'}MAGISK_SERVICE_DIR/hf_wifi_mac.sh" 2>/dev/null || cp $scriptPath "${'$'}MAGISK_SERVICE_DIR/hf_wifi_mac.sh" 2>/dev/null
                echo "已安装到 Magisk service.d"
            else
                echo "未检测到 Magisk, 仅写入脚本"
            fi
        """.trimIndent()
        val r = exec(installCmd)
        return r.output
    }
}
