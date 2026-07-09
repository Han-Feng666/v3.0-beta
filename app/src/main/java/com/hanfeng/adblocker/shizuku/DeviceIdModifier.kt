package com.hanfeng.adblocker.shizuku

import android.util.Log

class DeviceIdModifier {

    companion object {
        private const val TAG = "DeviceIdModifier"
        private const val SETTINGS_DB = "/data/data/com.android.providers.settings/databases/settings.db"
        private const val SSAID_FILE = "/data/system/users/0/settings_ssaid.xml"

        fun generateRandomAndroidId(): String {
            val chars = "0123456789abcdef"
            return (1..16).map { chars.random() }.joinToString("")
        }

        fun generateRandomSerial(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val prefix = "HF"
            val suffix = (1..8).map { chars.random() }.joinToString("")
            return "$prefix$suffix"
        }

        fun isRootAvailable(): Boolean {
            val session = SuSession.getInstance()
            if (session.isSessionOpen()) return true
            return session.open(timeoutSeconds = 60)
        }
    }

    data class ShellResult(val exitCode: Int, val output: String)

    fun readAndroidId(): ShellResult {
        val raw = runRootShell(
            "aid='';" +
            "for s in /system/bin/sqlite3 /system/xbin/sqlite3 sqlite3; do " +
            "if command -v \"\${s}\" >/dev/null 2>&1; then " +
            "aid=\$(\"\${s}\" '" + SETTINGS_DB + "' \"SELECT value FROM secure WHERE name='android_id';\" 2>/dev/null); " +
            "break; fi; " +
            "done; " +
            "if [ -z \"\${aid}\" ]; then " +
            "aid=\$(content query --uri content://settings/secure --projection value --where \"name='android_id'\" 2>/dev/null | sed -n 's/.*value=//p'); " +
            "fi; " +
            "if [ -z \"\${aid}\" ] && [ -f '" + SSAID_FILE + "' ]; then " +
            "aid=\$(sed -n 's/.*value=\"\\([^\"]*\\)\".*/\\1/p' '" + SSAID_FILE + "' 2>/dev/null); " +
            "fi; " +
            "[ -n \"\${aid}\" ] && echo \"\${aid}\" || echo ''"
        )
        if (raw.output.isBlank()) {
            return ShellResult(-1, "无法读取 Android ID：sqlite3/content query/ssaid.xml 均不可用")
        }
        return raw
    }

    fun writeAndroidId(newId: String): ShellResult {
        if (newId.length != 16) return ShellResult(-1, "Android ID 必须为 16 位十六进制字符串")
        if (!newId.matches(Regex("[0-9a-fA-F]+"))) return ShellResult(-1, "Android ID 只能包含十六进制字符 [0-9a-fA-F]")

        val cmds = mutableListOf<String>()
        
        cmds.add("settings put secure android_id $newId")
        
        cmds.add(
            "for s in /system/bin/sqlite3 /system/xbin/sqlite3 sqlite3; do " +
            "if command -v \"\${s}\" >/dev/null 2>&1; then " +
            "\"\${s}\" '" + SETTINGS_DB + "' \"UPDATE secure SET value='$newId' WHERE name='android_id';\" 2>/dev/null; " +
            "break; fi; " +
            "done"
        )
        
        cmds.add("if [ -f '" + SSAID_FILE + "' ]; then sed -i 's/value=\"[0-9a-fA-F]*\"/value=\"$newId\"/' '" + SSAID_FILE + "' 2>/dev/null; fi")
        
        cmds.add("killall com.android.providers.settings 2>/dev/null || true")
        cmds.add("sleep 1")
        cmds.add("echo 'DONE'")

        return runRootShell(cmds.joinToString(" ; "))
    }

    fun readSerialNo(): ShellResult {
        return runRootShell("getprop ro.serialno 2>/dev/null || getprop ro.boot.serialno 2>/dev/null || echo ''")
    }

    fun writeSerialNo(newSerial: String): ShellResult {
        if (newSerial.isBlank()) return ShellResult(-1, "序列号不能为空")
        if (newSerial.length < 4) return ShellResult(-1, "序列号长度至少 4 位")
        if (!newSerial.matches(Regex("[a-zA-Z0-9]+"))) return ShellResult(-1, "序列号只能包含字母和数字")

        val cmds = mutableListOf<String>()
        
        cmds.add("mkdir -p /data/property/persist")
        cmds.add("echo '$newSerial' > /data/property/persist.serialno 2>/dev/null")
        cmds.add("chmod 644 /data/property/persist.serialno 2>/dev/null")
        
        cmds.add("setprop persist.sys.serialno '$newSerial' 2>/dev/null || true")
        cmds.add("setprop ro.serialno '$newSerial' 2>/dev/null || true")
        cmds.add("setprop ro.boot.serialno '$newSerial' 2>/dev/null || true")
        
        cmds.add("resetprop ro.serialno '$newSerial' 2>/dev/null || true")
        cmds.add("resetprop ro.boot.serialno '$newSerial' 2>/dev/null || true")
        
        cmds.add("if [ -f /data/system/users/0/settings_global.xml ]; then " +
                "sed -i 's|<string name=\"device_serial\">[^<]*</string>|<string name=\"device_serial\">$newSerial</string>|' /data/system/users/0/settings_global.xml 2>/dev/null; " +
                "fi")
        
        cmds.add("echo 'DONE'")

        return runRootShell(cmds.joinToString(" ; "))
    }

    private fun runRootShell(command: String): ShellResult {
        val result = SuSession.getInstance().execute(command)
        return ShellResult(result.exitCode, result.output)
    }
}
