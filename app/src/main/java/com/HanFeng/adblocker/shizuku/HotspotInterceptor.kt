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
            append("log-queries=extra\n")
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

        val whichResult = session.execute("which dnsmasq 2>/dev/null || echo NOTFOUND", 5)
        val dnsmasqPath = if (whichResult.output.contains("NOTFOUND") || whichResult.output.isBlank()) {
            findDnsmasqBinary(session)
        } else {
            whichResult.output.trim().lines().firstOrNull { it.isNotBlank() } ?: "dnsmasq"
        }

        if (dnsmasqPath == null) {
            LogRepository.append(context, "Hotspot DNS hijack: dnsmasq not found on device")
            return false
        }

        val startCmd = "$dnsmasqPath --conf-file='$remoteConf' --pid-file='$DNSMASQ_PID' 2>&1"
        val startResult = session.execute(startCmd, 10)

        val checkResult = session.execute("test -f '$DNSMASQ_PID' && cat '$DNSMASQ_PID' && echo RUNNING || echo NOTRUNNING", 5)
        val running = checkResult.output.contains("RUNNING")

        if (running) {
            setupIptablesRedirect(context, session)
            LogRepository.append(context, "Hotspot DNS hijack started: dnsmasq=$dnsmasqPath port=$DNSMASQ_PORT rules=${rules.size}")
        } else {
            LogRepository.append(context, "Hotspot DNS hijack failed to start: ${startResult.output.take(200)}")
        }

        return running
    }

    private fun findDnsmasqBinary(session: SuSession): String? {
        val paths = listOf(
            "/system/bin/dnsmasq",
            "/system/xbin/dnsmasq",
            "/sbin/dnsmasq",
            "/vendor/bin/dnsmasq",
            "/data/adb/magisk/dnsmasq",
            "/data/adb/ksu/bin/dnsmasq"
        )
        for (path in paths) {
            val result = session.execute("test -x '$path' && echo FOUND || echo NO", 3)
            if (result.output.contains("FOUND")) return path
        }
        return null
    }

    private fun setupIptablesRedirect(context: Context, session: SuSession) {
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
        }
    }

    private fun detectHotspotInterface(session: SuSession): String? {
        val ifaces = listOf("wlan0", "ap0", "softap0", "wlan1", "swlan0", "wifi-ap0")
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

        val hotspotInterface = detectHotspotInterface(session)
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
        session.execute("rm -f '$DNSMASQ_CONF' '$HOSTS_FILE'", 3)

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
}
