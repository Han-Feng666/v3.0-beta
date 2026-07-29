package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import java.io.File

object HotspotInterceptor {

    private const val TAG = "HotspotInterceptor"
    private const val TMP_DIR = "/data/local/tmp"
    private const val DNSMASQ_CONF = "$TMP_DIR/hf_dnsmasq.conf"
    private const val DNSMASQ_PID = "$TMP_DIR/hf_dnsmasq.pid"
    private const val HOSTS_FILE = "$TMP_DIR/hf_hotspot_hosts.txt"
    private const val DNSMASQ_PORT = 5354
    private const val AUTO_RESTART_INTERVAL_MS = 30_000L

    @Volatile
    private var lastRestartTime: Long = 0

    @Volatile
    private var currentHotspotInterface: String? = null

    @Volatile
    private var blockedQueryCount: Long = 0

    data class HotspotStatus(
        val running: Boolean,
        val interfaceName: String?,
        val connectedDevices: List<ConnectedDevice>,
        val dnsmasqPath: String?,
        val iptablesRules: Int,
        val blockedQueries: Long
    )

    data class ConnectedDevice(
        val ip: String,
        val mac: String,
        val hostname: String?
    )

    fun startDnsHijack(context: Context): Boolean {
        val session = SuSession.getInstance()
        if (!session.isSessionOpen() && !session.open(15)) {
            LogRepository.append(context, "Hotspot DNS hijack: root not available")
            return false
        }

        stopDnsHijack(context)

        val rules = RuleRepository.getRules(context)
        if (rules.isEmpty()) {
            LogRepository.append(context, "Hotspot DNS hijack: no rules to block")
            return false
        }

        val hostsContent = buildString {
            append("# HanFeng hotspot ad block hosts\n")
            for (rule in rules) {
                val domain = rule.domain.trim()
                if (domain.isNotBlank() && !domain.startsWith("#") && !domain.startsWith("@@")) {
                    append("0.0.0.0 ")
                    append(domain)
                    append('\n')
                }
            }
        }

        val localHosts = File(context.cacheDir, "hf_hotspot_hosts.txt")
        localHosts.writeText(hostsContent)

        val remoteHosts = HOSTS_FILE
        val copyResult = session.execute("cp '${localHosts.absolutePath}' '$remoteHosts' && chmod 644 '$remoteHosts' && echo OK", 10)
        if (!copyResult.output.contains("OK")) {
            LogRepository.append(context, "Hotspot DNS hijack: failed to copy hosts file")
            return false
        }

        val confContent = buildString {
            append("port=$DNSMASQ_PORT\n")
            append("listen-address=0.0.0.0\n")
            append("bind-interfaces\n")
            append("no-hosts\n")
            append("addn-hosts=$remoteHosts\n")
            append("log-queries\n")
            append("log-facility=$TMP_DIR/hf_dnsmasq.log\n")
            append("no-resolv\n")
            append("server=8.8.8.8\n")
            append("server=1.1.1.1\n")
            append("server=223.5.5.5\n")
        }

        val localConf = File(context.cacheDir, "hf_dnsmasq.conf")
        localConf.writeText(confContent)

        val remoteConf = DNSMASQ_CONF
        val copyConfResult = session.execute("cp '${localConf.absolutePath}' '$remoteConf' && chmod 644 '$remoteConf' && echo OK", 10)
        if (!copyConfResult.output.contains("OK")) {
            LogRepository.append(context, "Hotspot DNS hijack: failed to copy conf file")
            return false
        }

        val dnsmasqPath = findOrDetectDnsmasq(session)
        if (dnsmasqPath == null) {
            LogRepository.append(context, "Hotspot DNS hijack: dnsmasq not found on device")
            return false
        }

        val startCmd = "$dnsmasqPath --conf-file='$remoteConf' --pid-file='$DNSMASQ_PID' 2>&1"
        val startResult = session.execute(startCmd, 10)

        val checkResult = session.execute("test -f '$DNSMASQ_PID' && cat '$DNSMASQ_PID' && echo RUNNING || echo NOTRUNNING", 5)
        val running = checkResult.output.contains("RUNNING")

        if (running) {
            val iface = setupIptablesRedirect(context, session)
            currentHotspotInterface = iface
            lastRestartTime = System.currentTimeMillis()
            blockedQueryCount = 0
            LogRepository.append(context, "Hotspot DNS hijack started: dnsmasq=$dnsmasqPath port=$DNSMASQ_PORT rules=${rules.size} interface=${iface ?: "all"}")
        } else {
            LogRepository.append(context, "Hotspot DNS hijack failed to start: ${startResult.output.take(200)}")
        }

        return running
    }

    private fun findOrDetectDnsmasq(session: SuSession): String? {
        val whichResult = session.execute("which dnsmasq 2>/dev/null || echo NOTFOUND", 5)
        if (!whichResult.output.contains("NOTFOUND") && whichResult.output.isNotBlank()) {
            return whichResult.output.trim().lines().firstOrNull { it.isNotBlank() } ?: "dnsmasq"
        }
        return findDnsmasqBinary(session)
    }

    private fun findDnsmasqBinary(session: SuSession): String? {
        val paths = listOf(
            "/system/bin/dnsmasq",
            "/system/xbin/dnsmasq",
            "/sbin/dnsmasq",
            "/vendor/bin/dnsmasq",
            "/data/adb/magisk/dnsmasq",
            "/data/adb/ksu/bin/dnsmasq",
            "/data/adb/magisk/bin/dnsmasq",
            "/data/adb/ksu/bin/dnsmasq"
        )
        for (path in paths) {
            val result = session.execute("test -x '$path' && echo FOUND || echo NO", 3)
            if (result.output.contains("FOUND")) return path
        }
        return null
    }

    private fun setupIptablesRedirect(context: Context, session: SuSession): String? {
        val hotspotInterface = detectHotspotInterface(session)
        if (hotspotInterface != null) {
            val cmds = listOf(
                "iptables -t nat -D PREROUTING -i $hotspotInterface -p udp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT 2>/dev/null",
                "iptables -t nat -D PREROUTING -i $hotspotInterface -p tcp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT 2>/dev/null",
                "iptables -t nat -I PREROUTING -i $hotspotInterface -p udp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT",
                "iptables -t nat -I PREROUTING -i $hotspotInterface -p tcp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT"
            )
            for (cmd in cmds) {
                session.execute(cmd, 5)
            }
            LogRepository.append(context, "Hotspot iptables redirect on $hotspotInterface -> port $DNSMASQ_PORT")
            return hotspotInterface
        } else {
            val cmds = listOf(
                "iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT 2>/dev/null",
                "iptables -t nat -D PREROUTING -p tcp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT 2>/dev/null",
                "iptables -t nat -I PREROUTING -p udp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT",
                "iptables -t nat -I PREROUTING -p tcp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT"
            )
            for (cmd in cmds) {
                session.execute(cmd, 5)
            }
            LogRepository.append(context, "Hotspot iptables redirect (all interfaces) -> port $DNSMASQ_PORT")
            return null
        }
    }

    private fun detectHotspotInterface(session: SuSession): String? {
        val ifaces = listOf("wlan0", "ap0", "softap0", "wlan1", "swlan0", "wifi-ap0", "ap1")
        for (iface in ifaces) {
            val result = session.execute("ip link show '$iface' 2>/dev/null && echo EXISTS || echo NO", 3)
            if (result.output.contains("EXISTS")) {
                val upResult = session.execute("cat /sys/class/net/$iface/operstate 2>/dev/null", 3)
                if (upResult.output.trim() == "up") return iface
            }
        }
        return null
    }

    fun stopDnsHijack(context: Context) {
        val session = SuSession.getInstance()
        if (!session.isSessionOpen()) return

        val hotspotInterface = currentHotspotInterface ?: detectHotspotInterface(session)
        val ifaceOpt = hotspotInterface?.let { "-i $it " } ?: ""
        val cleanupCmds = listOf(
            "iptables -t nat -D PREROUTING ${ifaceOpt}-p udp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT 2>/dev/null",
            "iptables -t nat -D PREROUTING ${ifaceOpt}-p tcp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT 2>/dev/null",
            "iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT 2>/dev/null",
            "iptables -t nat -D PREROUTING -p tcp --dport 53 -j REDIRECT --to-ports $DNSMASQ_PORT 2>/dev/null"
        )
        for (cmd in cleanupCmds) {
            session.execute(cmd, 5)
        }

        session.execute("test -f '$DNSMASQ_PID' && kill \$(cat '$DNSMASQ_PID') 2>/dev/null; rm -f '$DNSMASQ_PID'", 5)
        session.execute("pkill -f hf_dnsmasq 2>/dev/null", 3)
        session.execute("rm -f '$DNSMASQ_CONF' '$HOSTS_FILE' '$TMP_DIR/hf_dnsmasq.log'", 3)

        currentHotspotInterface = null
        blockedQueryCount = 0
        LogRepository.append(context, "Hotspot DNS hijack stopped")
    }

    fun isDnsHijackRunning(): Boolean {
        val session = SuSession.getInstance()
        if (!session.isSessionOpen()) return false
        val result = session.execute("test -f '$DNSMASQ_PID' && kill -0 \$(cat '$DNSMASQ_PID') 2>/dev/null && echo RUNNING || echo NOTRUNNING", 5)
        return result.output.contains("RUNNING")
    }

    fun refreshRules(context: Context) {
        if (isDnsHijackRunning()) {
            startDnsHijack(context)
        }
    }

    fun getHotspotStatus(context: Context): HotspotStatus {
        val session = SuSession.getInstance()
        if (!session.isSessionOpen()) {
            return HotspotStatus(false, null, emptyList(), null, 0, blockedQueryCount)
        }

        val running = isDnsHijackRunning()
        val iface = currentHotspotInterface ?: detectHotspotInterface(session)
        val devices = if (iface != null) detectConnectedDevices(session, iface) else emptyList()
        val dnsmasqPath = findOrDetectDnsmasq(session)
        val iptablesRules = countIptablesRules(session)
        val queries = if (running) readBlockedQueryCount(context, session) else blockedQueryCount

        return HotspotStatus(running, iface, devices, dnsmasqPath, iptablesRules, queries)
    }

    private fun detectConnectedDevices(session: SuSession, interfaceName: String): List<ConnectedDevice> {
        val devices = mutableListOf<ConnectedDevice>()
        try {
            val result = session.execute("ip neigh show dev $interfaceName 2>/dev/null | grep -v 'FAILED\\|INCOMPLETE'", 5)
            result.output.lines().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 5) {
                    val ip = parts[0]
                    val mac = parts[4]
                    val hostname = resolveHostname(session, ip)
                    devices.add(ConnectedDevice(ip, mac, hostname))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect connected devices", e)
        }
        return devices
    }

    private fun resolveHostname(session: SuSession, ip: String): String? {
        return try {
            val result = session.execute("nslookup $ip 127.0.0.1 2>/dev/null | grep 'name = ' | head -1", 3)
            result.output.substringAfter("name = ").substringBefore(" ").trim().removeSuffix(".")
                .ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun countIptablesRules(session: SuSession): Int {
        return try {
            val result = session.execute("iptables -t nat -S PREROUTING 2>/dev/null | grep -c 'REDIRECT.*$DNSMASQ_PORT' || echo 0", 3)
            result.output.trim().toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun readBlockedQueryCount(context: Context, session: SuSession): Long {
        return try {
            val result = session.execute("grep -c 'blocked\\|NXDOMAIN\\|0.0.0.0' '$TMP_DIR/hf_dnsmasq.log' 2>/dev/null || echo 0", 3)
            val count = result.output.trim().toLongOrNull() ?: 0L
            blockedQueryCount = count
            count
        } catch (e: Exception) {
            blockedQueryCount
        }
    }

    fun autoRestartIfNeeded(context: Context): Boolean {
        if (!isDnsHijackRunning()) {
            val now = System.currentTimeMillis()
            if (now - lastRestartTime > AUTO_RESTART_INTERVAL_MS) {
                LogRepository.append(context, "Hotspot DNS hijack: dnsmasq crashed, attempting auto-restart")
                val success = startDnsHijack(context)
                if (success) {
                    LogRepository.append(context, "Hotspot DNS hijack: auto-restart successful")
                }
                return success
            }
        }
        return false
    }

    fun checkAndFixIptables(context: Context): Boolean {
        val session = SuSession.getInstance()
        if (!session.isSessionOpen()) return false
        if (!isDnsHijackRunning()) return false

        val currentRules = countIptablesRules(session)
        if (currentRules < 2) {
            LogRepository.append(context, "Hotspot iptables rules missing ($currentRules/2), re-applying")
            setupIptablesRedirect(context, session)
            return true
        }
        return false
    }
}
