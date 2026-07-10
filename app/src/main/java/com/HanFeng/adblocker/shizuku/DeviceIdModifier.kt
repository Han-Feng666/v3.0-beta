package com.HanFeng.adblocker.shizuku

import android.util.Log

class DeviceIdModifier {

    companion object {
        private const val TAG = "DeviceIdModifier"
        private const val SETTINGS_DB = "/data/data/com.android.providers.settings/databases/settings.db"
        private const val SSAID_FILE = "/data/system/users/0/settings_ssaid.xml"
        private const val BACKUP_FILE = "/data/local/tmp/.hf_deviceid_backup"
        private const val GLOBAL_XML = "/data/system/users/0/settings_global.xml"

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

    data class DeviceIdSnapshot(
        val androidId: String,
        val serialNo: String,
        val buildSerial: String,
        val buildFingerprint: String,
        val timestamp: Long
    )

    fun backupCurrent(): ShellResult {
        val androidId = readAndroidId()
        val serialNo = readSerialNo()
        val buildSerial = runRootShell("getprop ro.build.serialno 2>/dev/null || echo ''").output.trim()
        val buildFingerprint = runRootShell("getprop ro.build.fingerprint 2>/dev/null || echo ''").output.trim()
        val timestamp = System.currentTimeMillis()

        val snapshot = "$androidId.output|\n${serialNo.output}|\n$buildSerial|\n$buildFingerprint|\n$timestamp"
        return runRootShell("echo '$snapshot' > $BACKUP_FILE && echo BACKUP_OK || echo BACKUP_FAIL")
    }

    fun restoreFromBackup(): ShellResult {
        val backupContent = runRootShell("cat $BACKUP_FILE 2>/dev/null || echo BACKUP_NOT_FOUND").output
        if (backupContent.contains("BACKUP_NOT_FOUND")) {
            return ShellResult(-1, "未找到备份文件")
        }

        val parts = backupContent.split("|").map { it.trim() }
        if (parts.size < 5) return ShellResult(-1, "备份文件格式错误")

        val androidId = parts.getOrElse(0) { "" }
        val serialNo = parts.getOrElse(1) { "" }
        val buildSerial = parts.getOrElse(2) { "" }

        val results = mutableListOf<String>()
        if (androidId.isNotBlank() && androidId.length == 16) {
            val aidResult = writeAndroidId(androidId)
            results.add("Android ID: ${if (aidResult.exitCode == 0) "已恢复" else "恢复失败: ${aidResult.output}"}")
        }
        if (serialNo.isNotBlank()) {
            val snResult = writeSerialNo(serialNo)
            results.add("序列号: ${if (snResult.exitCode == 0) "已恢复" else "恢复失败: ${snResult.output}"}")
        }
        if (buildSerial.isNotBlank()) {
            runRootShell("resetprop ro.build.serialno '$buildSerial' 2>/dev/null || true")
            results.add("Build Serial: 已恢复")
        }

        return ShellResult(0, results.joinToString("\n"))
    }

    fun readAllDeviceIds(): ShellResult {
        val sb = StringBuilder()
        sb.appendLine("=== 设备标识信息 ===")
        sb.appendLine("Android ID: ${readAndroidId().output}")
        sb.appendLine("Serial No: ${readSerialNo().output}")
        sb.appendLine("Build Serial: ${runRootShell("getprop ro.build.serialno 2>/dev/null || echo '(none)'").output}")
        sb.appendLine("Build Fingerprint: ${runRootShell("getprop ro.build.fingerprint 2>/dev/null || echo '(none)'").output}")
        sb.appendLine("Boot Serial: ${runRootShell("getprop ro.boot.serialno 2>/dev/null || echo '(none)'").output}")
        sb.appendLine("Persist Serial: ${runRootShell("getprop persist.sys.serialno 2>/dev/null || echo '(none)'").output}")
        sb.appendLine("Global XML Serial: ${readGlobalXmlSerial()}")
        sb.appendLine("Magisk resetprop: ${runRootShell("resetprop ro.serialno 2>/dev/null || echo '(unavailable)'").output}")
        return ShellResult(0, sb.toString())
    }

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
            "[ -n \"\${aid}\" ] && echo \"\${aid}\" || echo 'none'"
        )
        if (raw.output.isBlank() || raw.output == "none") {
            return ShellResult(-1, "无法读取 Android ID")
        }
        return raw
    }

    fun writeAndroidId(newId: String): ShellResult {
        if (newId.length != 16) return ShellResult(-1, "Android ID 必须为 16 位十六进制字符串")
        if (!newId.matches(Regex("[0-9a-fA-F]+"))) return ShellResult(-1, "Android ID 只能包含十六进制字符 [0-9a-fA-F]")

        backupCurrent()

        val cmds = mutableListOf<String>()

        cmds.add("settings put secure android_id $newId")

        cmds.add(
            "for s in /system/bin/sqlite3 /system/xbin/sqlite3 sqlite3; do " +
            "if command -v \"\${s}\" >/dev/null 2>&1; then " +
            "\"\${s}\" '" + SETTINGS_DB + "' \"UPDATE secure SET value='$newId' WHERE name='android_id';\" 2>/dev/null; " +
            "break; fi; " +
            "done"
        )

        val escapedId = escapeSedValue(newId)
        cmds.add("if [ -f '" + SSAID_FILE + "' ]; then sed -i 's|\\(setting.*name=\"android_id\"[^v]*value=\"\\)[^\"]*\\(.*\\)|\\1$escapedId\\2|' '" + SSAID_FILE + "' 2>/dev/null; fi")

        cmds.add("killall com.android.providers.settings 2>/dev/null || true")
        cmds.add("sleep 1")
        cmds.add("echo 'DONE'")

        val result = runRootShell(cmds.joinToString(" ; "))

        val verify = readAndroidId()
        if (verify.output != newId) {
            Log.w(TAG, "写入验证失败: 期望=$newId, 实际=${verify.output}")
            return ShellResult(0, "写入完成但验证不匹配。期望=$newId 实际=${verify.output}")
        }

        return result
    }

    fun readSerialNo(): ShellResult {
        return runRootShell("getprop ro.serialno 2>/dev/null || getprop ro.boot.serialno 2>/dev/null || echo 'none'")
    }

    fun writeSerialNo(newSerial: String): ShellResult {
        if (newSerial.isBlank()) return ShellResult(-1, "序列号不能为空")
        if (newSerial.length < 4) return ShellResult(-1, "序列号长度至少 4 位")
        if (!newSerial.matches(Regex("[a-zA-Z0-9]+"))) return ShellResult(-1, "序列号只能包含字母和数字")

        val escaped = SuSession.getInstance().escapeShell(newSerial)
        val escapedForSh = escaped.replace("\$", "\\$")

        val cmds = mutableListOf<String>()

        // 1. 立即生效：resetprop 改 property service 内存
        cmds.add("resetprop ro.serialno '$escaped' 2>/dev/null || true")
        cmds.add("resetprop ro.boot.serialno '$escaped' 2>/dev/null || true")
        cmds.add("setprop persist.sys.serialno '$escaped' 2>/dev/null || true")

        // 2. 写入 settings_global.xml 的 device_serial 字段
        cmds.add("if [ -f '$GLOBAL_XML' ]; then " +
                "sed -i 's|<string name=\"device_serial\">[^<]*</string>|<string name=\"device_serial\">$escapedForSh</string>|' '$GLOBAL_XML' 2>/dev/null; " +
                "fi")

        cmds.add("echo 'STEP1_DONE'")

        // 3. 落地到 Magisk/KSU module 的 post-fs-data 脚本，确保重启后 resetprop 自动覆盖
        val moduleDir = "/data/adb/modules/hf_deviceid"
        cmds.add("mkdir -p '$moduleDir'")
        cmds.add("mkdir -p '$moduleDir/post-fs-data.d'")

        // module.prop：让 Magisk/KSU 识别为一个 module
        cmds.add(
            "cat > '$moduleDir/module.prop' << 'MODULE_PROP_EOF'\n" +
            "id=hf_deviceid\n" +
            "name=HanFeng DeviceID Persist\n" +
            "version=v1.0\n" +
            "versionCode=1\n" +
            "author=HanFeng\n" +
            "description=Persist ro.serialno/ro.boot.serialno override on every boot\n" +
            "MODULE_PROP_EOF"
        )

        // service.sh：late_start 阶段用 resetprop 把 ro.* 覆盖回新值（bootloader 传入的值会再次生效）
        cmds.add(
            "cat > '$moduleDir/service.sh' << 'SERVICE_SH_EOF'\n" +
            "#!/system/bin/sh\n" +
            "while [ \"\$(getprop ro.boot.serialno)\" != \"$newSerial\" ]; do\n" +
            "  resetprop ro.serialno '$newSerial' 2>/dev/null\n" +
            "  resetprop ro.boot.serialno '$newSerial' 2>/dev/null\n" +
            "  resetprop persist.sys.serialno '$newSerial' 2>/dev/null\n" +
            "  sleep 2\n" +
            "done\n" +
            "resetprop ro.serialno '$newSerial' 2>/dev/null\n" +
            "resetprop ro.boot.serialno '$newSerial' 2>/dev/null\n" +
            "resetprop persist.sys.serialno '$newSerial' 2>/dev/null\n" +
            "SERVICE_SH_EOF"
        )
        cmds.add("chmod 755 '$moduleDir/service.sh'")
        cmds.add("chcon u:object_r:system_file:s0 '$moduleDir/service.sh' 2>/dev/null || true")
        cmds.add("chcon u:object_r:system_file:s0 '$moduleDir/module.prop' 2>/dev/null || true")

        cmds.add("echo 'DONE'")

        val result = runRootShell(cmds.joinToString("\n"))

        val verify = readSerialNo()
        if (verify.output.trim() != newSerial) {
            Log.w(TAG, "序列号写入后验证不一致: 期望=$newSerial, 实际=${verify.output}（重启后由 module service.sh 自动覆盖生效）")
        }

        return result
    }

    fun clearPersistedSerialNoModule(): ShellResult {
        val cmds = mutableListOf<String>()
        val moduleDir = "/data/adb/modules/hf_deviceid"
        cmds.add("if [ -d '$moduleDir' ]; then")
        cmds.add("  touch '$moduleDir/disable'")
        cmds.add("  rm -rf '$moduleDir/module.prop' '$moduleDir/service.sh' 2>/dev/null")
        cmds.add("  rmdir '$moduleDir' 2>/dev/null || true")
        cmds.add("fi")
        cmds.add("echo 'CLEAR_DONE'")
        return runRootShell(cmds.joinToString("\n"))
    }

    private fun readGlobalXmlSerial(): String {
        return runRootShell("grep -oP 'device_serial\">\\K[^<]+' $GLOBAL_XML 2>/dev/null || echo 'none'").output.trim()
    }

    private fun escapeSedValue(value: String): String {
        return value.replace("&", "\\&").replace("/", "\\/").replace("\\", "\\\\")
    }

    private fun runRootShell(command: String): ShellResult {
        val result = SuSession.getInstance().execute(command)
        return ShellResult(result.exitCode, result.output)
    }
}
