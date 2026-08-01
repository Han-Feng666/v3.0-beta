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

        /**
         * 生成随机主板 ID —— 按模板生成同格式串。
         *
         * "主板 ID" 在中文 Android 工具圈(爱玩机工具箱)在不同厂商暴露形态不同：
         *   - 高通机型以 ro.boot.cpuid 暴露：0x000004483bb3a50d (前缀0x + 16 hex)
         *   - 红米 K70 走 ro.boot.cell_id：AG353A70K6AD3 (字母+9位数字+3字母)
         *   - 其它厂商各自不同。
         *
         * 我们按 readMainboardId 取到的"第一条有值 prop"做格式模板：
         *   - 字符种类分布保持不变 (hex/digit/letter)
         *   - 长度保持不变
         *   - 字母位置保持字母 (但内容随机), 数字位置保持数字
         *   - 分隔符(0x/-/_/)保留原位
         *
         * 没有模板时 fallback 到 cpuid hex 形态(高通主流)。
         */
        fun generateRandomMainboardId(template: String? = null): String {
            if (template.isNullOrBlank()) return generateRandomCpuIdLikeHex()

            val clean = template.trim()
            // 检测纯 hex (含 0x 前缀)
            val hexPart = if (clean.startsWith("0x") || clean.startsWith("0X")) clean.substring(2) else clean
            if (hexPart.matches(Regex("^[0-9a-fA-F]+$")) && hexPart.length in 8..20) {
                // 看着像 SoC cpuid hex (0x000004483bb3a50d)，按原 template 长度生成同长度 hex
                val targetLen = hexPart.length
                return "0x" + (1..targetLen).map { "0123456789abcdef".random() }.joinToString("")
            }

            // 否则按位置逐字符替换：保持符号/位置不变，数字位换数字，字母位换字母 (大小写保留)
            val out = StringBuilder(clean.length)
            for (ch in clean) {
                when {
                    ch.isDigit() -> out.append("0123456789".random())
                    ch in 'a'..'z' -> out.append("abcdefghijkmnpqrstuvwxyz".random())  // 去掉 l/o 避免与 1/0 混淆
                    ch in 'A'..'Z' -> out.append("ABCDEFGHJKLMNPQRSTUVWXYZ".random())  // 去掉 I/L/O 避免与 1/L/0 混淆
                    else -> out.append(ch)  // 连字符/下划线/斜杠/小数点等保留
                }
            }
            return out.toString()
        }

        /**
         * 没有模板时 fallback 生成高通 cpuid hex 形态。
         * 高 8 hex 取自高通主流 SoC 系列前缀(共享),低 8 hex 随机模拟唯一序列。
         */
        private fun generateRandomCpuIdLikeHex(): String {
            val socPrefixes = listOf(
                "0x00000448",  // SM8650 (SD 8 Gen 3)
                "0x00000553",  // SM8550 (SD 8 Gen 2)
                "0x00000441",  // SM8450 (SD 8 Gen 1)
                "0x00000345",  // SM8350 (SD 888)
                "0x00000263",  // SM8250 (SD 865)
                "0x00000156",  // SM8150 (SD 855)
                "0x00000732",  // SM7675 (SD 7+ Gen 3)
                "0x00000625"   // SM6450 (SD 6 Gen 1)
            )
            val hex = "0123456789abcdef"
            return socPrefixes.random() + (1..8).map { hex.random() }.joinToString("")
        }


        /**
         * 生成随机 SN 串号 (形如 R3YT02307 / 50936/R3YT02307)
         * 模仿常见 SN 码格式：首段数字 + 斜杠 + 字母数字组合
         */
        fun generateRandomSn(): String {
            val digits = "0123456789"
            val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
            val prefix = (1..5).map { digits.random() }.joinToString("")
            val body = (1..8).map { upper.random() }.joinToString("")
            return "$prefix/$body"
        }

        /**
         * 生成随机 IMEI (15 位 GSM/WCDMA/LTE/5G 通用格式)
         *   前 8 位 TAC (Type Allocation Code, 厂商+机型码)
         *   中 6 位 SNR (序列号)
         *   末 1 位 Luhn 校验和
         *
         * TAC 取自公开段前缀, 避免随机出来的 TAC 触发 GSMA IMEI DB 校验失败。
         * SNR 6 位纯数字随机。返回字符串严格 15 位。
         */
        fun generateRandomImei(): String {
            val tacPrefixes = listOf(
                "86100606", "86746605", "86222210", "86638902", "86440505",
                "35209901", "35824006", "35693802", "35979506", "86320402",
                "35084660", "86849200", "86071303", "86416706", "86203102"
            )
            val tac = tacPrefixes.random()
            val snr = (1..6).map { "0123456789".random() }.joinToString("")
            val base = tac + snr
            val luhn = computeLuhnCheckDigit(base)
            return base + luhn
        }

        /**
         * 生成随机 MEID (14 hex 形式)
         *   前 8 位 厂商码 + 机型码
         *   后 6 位 序列号 hex
         *
         * 拨号盘 *#06# 在 CDMA 机型显示 "MEID" 行, MEID 由 14 hex 组成。
         * 字符集严格 0-9A-F 大写。
         */
        fun generateRandomMeid(): String {
            val meidPrefixes = listOf(
                "99000601", "99000506", "99000802", "A1000001", "A1000046",
                "A0000015", "A0000035", "A0000047", "99000235", "99000318"
            )
            val prefix = meidPrefixes.random()
            val suffix = (1..6).map { "0123456789ABCDEF".random() }.joinToString("")
            return prefix + suffix
        }

        /**
         * Luhn 算法计算 IMEI 校验位 (15 位 IMEI 末位)
         * 从右起偶数位 ×2 超 9 减 9, 总和模 10 后用 10 减
         */
        private fun computeLuhnCheckDigit(baseWithoutCheck: String): String {
            if (!baseWithoutCheck.all { it.isDigit() } || baseWithoutCheck.isEmpty()) return "0"
            var sum = 0
            val reversed = baseWithoutCheck.reversed()
            for ((idx, ch) in reversed.withIndex()) {
                var d = ch - '0'
                if (idx % 2 == 0) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            val mod = sum % 10
            return ((10 - mod) % 10).toString()
        }

        fun isValidImei15(imei: String): Boolean {
            if (imei.length != 15) return false
            if (!imei.all { it.isDigit() }) return false
            return computeLuhnCheckDigit(imei.substring(0, 14)) == imei.substring(14, 15)
        }

        fun isValidMeid14(meid: String): Boolean {
            if (meid.length != 14) return false
            return meid.uppercase().all { it in '0'..'9' || it in 'A'..'F' }
        }

        fun isRootAvailable(): Boolean {
            val session = SuSession.getInstance()
            if (session.isSessionOpen()) return true
            return session.open(timeoutSeconds = 60)
        }
    }

    data class ShellResult(val exitCode: Int, val output: String)

    /**
     * 统一的"设备属性持久化 module"。
     * 所有 root 区域修改设备 ID 功能(序列号/SN/主板ID/手机型号) 共用同一个 Magisk/KSU module:
     *   /data/adb/modules/hf_device_props/
     * 不管用户用几个功能, Magisk 管理器中只显示一个 module。
     *
     * module 内容:
     *   - module.prop  - 标准 module 元数据(只写一次, 后续不动)
     *   - service.sh   - 通用脚本, 读 props.list 循环 resetprop(只写一次, 后续不动)
     *   - props.list   - 真正的 "<prop_key>=<value>" 逐行 KV 配置, 每次修改任一字段只增删此文件
     *
      * service.sh 与 props.list 解耦 - 改任何字段都不需要重新生成 service.sh,
      * 只增删 props.list 一行: 由 [upsertModuleProp] 与 [removeModuleProp] 完成原子操作。
      */

    // root 区域用户已经刷过的老 module 目录, 首次升级到合并 module 时们会自动迁走
    // 这些老 module 存在 service.sh, 重启也会自动跑, 我们清掉它们避免与 hf_device_props 双跑重复
    private val LEGACY_MODULE_DIRS = listOf(
        "/data/adb/modules/hf_deviceid",
        "/data/adb/modules/hf_sn",
        "/data/adb/modules/hf_mainboard",
        "/data/adb/modules/hf_model"
    )

    /**
     * 把单个 prop key=value 写入统一 module 的 props.list。
     * 已存在该 key 的行会被替换, 没有则追加。同时确保 module.prop / service.sh 已存在。
     * 多 prop 字段调用方要逐 key 调多次或用 [upsertModuleProps] 批量。
     */
    private fun upsertModuleProp(propKey: String, rawValueEscaped: String): Boolean {
        return upsertModuleProps(listOf(propKey to rawValueEscaped))
    }

    /**
     * 批量写入多个 prop key=value 到统一 module。
     *
     * @param pairs 列表项 (propKey, escapedValue) - value 必须已 escapeShell 过(只含可安全出现在单引号字符串内的字符)
     */
    private fun upsertModuleProps(pairs: List<Pair<String, String>>): Boolean {
        if (pairs.isEmpty()) return true
        val moduleDir = "/data/adb/modules/hf_device_props"
        val propList = "$moduleDir/props.list"
        val cmds = mutableListOf<String>()

        // 1. 创建 module 目录
        cmds.add("mkdir -p '$moduleDir'")

        // 2. 写 module.prop (只在不存在时写, 不覆盖现有 - 用户可能改过版本号)
        cmds.add(
            "if [ ! -f '$moduleDir/module.prop' ]; then " +
                "cat > '$moduleDir/module.prop' << 'MP_EOF'\n" +
                "id=hf_device_props\n" +
                "name=寒枫root区域功能模块\n" +
                "version=v2.0\n" +
                "versionCode=20\n" +
                "author=HanFeng\n" +
                "description=寒枫root区域功能模块\n" +
                "MP_EOF\n" +
                "chcon u:object_r:system_file:s0 '$moduleDir/module.prop' 2>/dev/null || true; " +
            "fi"
        )

        // 3. 写 service.sh (只在不存在时写 — 它与 props.list 解耦, 不需要跟随配置变化更新)
        // shell 逻辑: 每行 props.list 形如 KEY='value', 直接 read 行 + 截取 KEY/VAL + resetprop
        // Kotlin raw string 里 ${'$'} = 字面 $ (避免 Kotlin 模板把 $KEY 当变量)
        val D = "${'$'}"
        val serviceScript = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Auto-generated by HanFeng. Do not edit; modify props.list instead.")
            appendLine("# Reads each KEY='VALUE' line from props.list and runs resetprop KEY 'VALUE'.")
            appendLine("PL=\"/data/adb/modules/hf_device_props/props.list\"")
            appendLine("[ -r \"").append(D).append("PL\"] || exit 0")
            appendLine("while IFS= read -r line; do")
            appendLine("  case \"").append(D).append("line\" in")
            appendLine("    ''|\\#*) continue ;;")
            appendLine("  esac")
            appendLine("  KEY=\"").append(D).append("{line%%=*}\"")
            appendLine("  VAL=\"").append(D).append("{line#*=}\"")
            appendLine("  # strip surrounding single quotes")
            appendLine("  VAL=\"").append(D).append("{VAL#").append("\\'").append("}\"")
            appendLine("  VAL=\"").append(D).append("{VAL%").append("\\'").append("}\"")
            appendLine("  [ -n \"").append(D).append("KEY\" ] || continue")
            appendLine("  resetprop \"").append(D).append("KEY\" \"").append(D).append("VAL\" 2>/dev/null || setprop \"").append(D).append("KEY\" \"").append(D).append("VAL\" 2>/dev/null || true")
            appendLine("done < \"").append(D).append("PL\"")
        }.trimEnd()
        // 注意 service.sh 内含 $ 变量 - 用 'EOF' (quoted heredoc) 让 shell 不解析 $, 文件按宇面写入
        cmds.add(
            "if [ ! -f '$moduleDir/service.sh' ]; then " +
                "cat > '$moduleDir/service.sh' << 'SS_EOF'\n" +
                serviceScript +
                "\nSS_EOF\n" +
                "chmod 755 '$moduleDir/service.sh'; " +
                "chcon u:object_r:system_file:s0 '$moduleDir/service.sh' 2>/dev/null || true; " +
            "fi"
        )

        // 4. 创建空 props.list (如不存在)
        cmds.add("if [ ! -f '$propList' ]; then printf '' > '$propList'; chmod 644 '$propList'; fi")

        // 5. 移除模块 disable 标记(若之前清空过)
        cmds.add("rm -f '$moduleDir/disable' 2>/dev/null; rm -f '$moduleDir/remove' 2>/dev/null")

        // 6. 对每对 (key, value) 做行替换/追加
        //    使用 sed -i "/^key=/d" + 追加新行, 避免复杂引号嵌套
        for ((key, vEsc) in pairs) {
            // sed 命令里 key 含特殊字符的可能性(例 ro.boot.cell_id 等号被 . 隔开)
            // key 由代码控制, 全部是合法 prop 名(字母数字点下划线), sed 模式安全
            // 用 awk 替代 sed 保证更稳的行替换
            // 写入格式: KEY='VALUE', readline 脚本已支持剥离单引号
            val newLine = "$key='$vEsc'"
            // 写临时文件再原子替换更稳: awk "$0 !~ /^key=/" file > tmp, echo "$newLine" >> tmp, mv tmp file
            cmds.add(
                "awk -F= -v K='$key' 'BEGIN{IGNORECASE=0} \$0 !~ (\"^\" K \"=\")' '$propList' > '$propList.tmp' 2>/dev/null; " +
                "printf '%s\\n' '$newLine' >> '$propList.tmp'; " +
                "mv '$propList.tmp' '$propList'"
            )
        }

        // 7. 清掉老 module (一次性, 升级到统一 module 都是清理)
        // 注意 此清理不删除老的 service.sh 内容中的 resetprop 痕迹 — 老模块 disable 后下次启动不再执行
        for (oldDir in LEGACY_MODULE_DIRS) {
            cmds.add(
                "if [ -d '$oldDir' ]; then " +
                    "touch '$oldDir/disable'; " +
                    "rm -rf '$oldDir/module.prop' '$oldDir/service.sh' '$oldDir/system.prop' '$oldDir/post-fs-data.d' 2>/dev/null; " +
                    "rmdir '$oldDir' 2>/dev/null || true; " +
                "fi"
            )
        }

        cmds.add("echo 'UPSERT_OK'")
        val r = runRootShell(cmds.joinToString("\n"))
        return r.exitCode == 0 || r.output.contains("UPSERT_OK")
    }

    /**
     * 从统一 module props.list 中删除指定的 prop keys。若 props.list 删空则一并 disable module。
     */
    private fun removeModuleProps(propKeys: List<String>): Boolean {
        if (propKeys.isEmpty()) return true
        val moduleDir = "/data/adb/modules/hf_device_props"
        val propList = "$moduleDir/props.list"
        val cmds = mutableListOf<String>()
        cmds.add("if [ ! -f '$propList' ]; then echo 'REMOVE_OK'; exit 0; fi")
        for (key in propKeys) {
            cmds.add("awk -F= -v K='$key' '\$0 !~ (\"^\" K \"=\")' '$propList' > '$propList.tmp' 2>/dev/null; mv '$propList.tmp' '$propList'")
        }
        // 若 props.list 删除后只剩空行/注释, 直接 disable module + 删除文件
        cmds.add(
            "if [ -f '$propList' ] && [ -z \"\$(grep -v '^[[:space:]]*\$\\|^[[:space:]]*#' '$propList' 2>/dev/null)\" ]; then " +
                "touch '$moduleDir/disable'; " +
                "rm -f '$propList'; " +
            "else " +
                "rm -f '$moduleDir/disable'; " +
            "fi"
        )
        cmds.add("echo 'REMOVE_OK'")
        val r = runRootShell(cmds.joinToString("\n"))
        return r.exitCode == 0 || r.output.contains("REMOVE_OK")
    }

    /**
     * 完整清除统一 module (用户主动要求"恢复默认" 时调用)。
     * 删除所有 props.list 内容 + disable module, 让设备下次启动回到原 prop 默认。
     * 当前运行时内存中的 prop 值不在此清理 (调用方需自行 resetprop --delete 各 key)。
     */
    private fun clearUnifiedModule(): ShellResult {
        val moduleDir = "/data/adb/modules/hf_device_props"
        val cmds = mutableListOf<String>()
        cmds.add("if [ -d '$moduleDir' ]; then " +
            "touch '$moduleDir/disable'; " +
            "rm -f '$moduleDir/props.list'; " +
            "fi")
        cmds.add("echo 'CLEAR_OK'")
        return runRootShell(cmds.joinToString("\n"))
    }

    data class DeviceIdSnapshot(
        val androidId: String,
        val serialNo: String,
        val buildSerial: String,
        val buildFingerprint: String,
        val timestamp: Long
    )

    fun backupCurrent(): ShellResult {
        val androidId = readAndroidId().output.trim()
        val serialNo = readSerialNo().output.trim()
        val buildSerial = runRootShell("getprop ro.build.serialno 2>/dev/null || echo ''").output.trim()
        val buildFingerprint = runRootShell("getprop ro.build.fingerprint 2>/dev/null || echo ''").output.trim()
        val timestamp = System.currentTimeMillis()
        // 把每个字段单独写入临时文件，再合并成单行备份文件，避免原文中含单引号/$/反引号破坏 shell 字符串拼接
        val esc = { v: String -> v.replace("\\", "\\\\").replace("\n", "\\n") }
        val merged = listOf(esc(androidId), esc(serialNo), esc(buildSerial), esc(buildFingerprint), timestamp.toString())
            .joinToString("|")
        return runRootShell(
            "cat > $BACKUP_FILE << 'HF_BACKUP_EOF'\n" +
            merged + "\n" +
            "HF_BACKUP_EOF\n" +
            "if [ -s $BACKUP_FILE ]; then echo BACKUP_OK; else echo BACKUP_FAIL; fi"
        )
    }

    fun restoreFromBackup(): ShellResult {
        val backupContent = runRootShell("cat $BACKUP_FILE 2>/dev/null || echo BACKUP_NOT_FOUND").output
        if (backupContent.contains("BACKUP_NOT_FOUND")) {
            return ShellResult(-1, "未找到备份文件")
        }

        val firstLine = backupContent.lineSequence().firstOrNull { it.isNotBlank() }
            ?: return ShellResult(-1, "备份文件为空")
        val parts = firstLine.split("|")
        if (parts.size < 5) return ShellResult(-1, "备份文件格式错误")

        // 与 backupCurrent 配套的反转义: \\ -> \, \n -> 换行
        val unesc = { v: String -> v.replace("\\n", "\n").replace("\\\\", "\\") }

        val androidId = unesc(parts[0])
        val serialNo = unesc(parts[1])
        val buildSerial = unesc(parts[2])

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
            val escBuild = SuSession.getInstance().escapeShell(buildSerial).replace("\$", "\\$")
            runRootShell("resetprop ro.build.serialno '$escBuild' 2>/dev/null || true")
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

        // 3. 落地到统一 module props.list (覆盖 ro.serialno / ro.boot.serialno / persist.sys.serialno)
        //    不再刷入独立 hf_deviceid module, 与其它字段共用 hf_device_props
        upsertModuleProps(listOf(
            "ro.serialno" to escaped,
            "ro.boot.serialno" to escaped,
            "persist.sys.serialno" to escaped
        ))

        cmds.add("echo 'DONE'")

        // 4. 让 Settings/系统层相关进程重读 prop，否则缓存着旧值即时不变
        cmds.add("am force-stop com.android.providers.settings 2>/dev/null || true")
        cmds.add("killall com.android.providers.settings 2>/dev/null || true")
        cmds.add("am force-stop com.android.settings 2>/dev/null || true")
        cmds.add("sleep 1")

        val result = runRootShell(cmds.joinToString("\n"))

        val verify = readSerialNo()
        if (verify.output.trim() != newSerial) {
            Log.w(TAG, "序列号写入后验证不一致: 期望=$newSerial, 实际=${verify.output}（重启后由 hf_device_props module service.sh 自动覆盖生效）")
        }

        return result
    }

    fun clearPersistedSerialNoModule(): ShellResult {
        // 删除统一 module 里 ro.serialno / ro.boot.serialno / persist.sys.serialno 三条
        removeModuleProps(listOf("ro.serialno", "ro.boot.serialno", "persist.sys.serialno"))
        // 实时把 prop 删掉, 让系统回到 bootloader 写入的原始值
        runRootShell(
            "resetprop --delete ro.serialno 2>/dev/null || true; " +
            "resetprop --delete ro.boot.serialno 2>/dev/null || true; " +
            "setprop persist.sys.serialno '' 2>/dev/null || true; " +
            "echo 'CLEAR_DONE'"
        )
        return ShellResult(0, "已清除序列号覆盖, 重启后恢复默认")
    }

    // ==================== SN 码 (拨号盘 *#06# 显示的 SN 行) ====================
    // 拨号盘 *#06# 第三行 "SN:" 在 HyperOS/MIUI 上实际由下列 prop 驱动 (红米 K70 实测)：
    //   gsm.sn        = 50936/R3YT02300    ← 拨号盘直接读这条 (TelephonyManager 内部 getSn())
    //   persist.sys.sn = 50936/R3YT02300   ← 与 gsm.sn 同源, HyperOS 持久化备份
    //   ril.sn        = 50936/R3YT02300    ← RIL 层 SN 同源
    // 这三条任一改了拨号盘都变 (但 RIL/TelephonyManager 缓存会让 *#06# 暂时不变,需 kill com.android.phone)
    //
    // ro.serialno / ro.boot.serialno / persist.sys.serialno 在你 HyperOS 上**不驱动 *#06# SN**，
    // 它们承载的是"设备序列号"(Android ID 兄弟字段),归"修改主板序列号"工具职责。
    //   实测 打印 persist.sys.serialno = "7e632422"（与 SN "50936/R3YT02300" 不一致），佐证两者不同源。
    //
    // 拨号盘 *#06# SN 行的承载 prop 与"主板序列号"(writeSerialNo)不一样:
    //   - 主板序列号功能覆盖 ro.serialno / ro.boot.serialno / persist.sys.serialno
    //   - 拨号盘 SN 行在不同机型从 BIN/sim 卡槽 / modem 读, 高通 MTK 走 gsm.sn / ril.sn,
    //     HyperOS 部分机型走 persist.sys.sn2 / ro.boot.sn / sys.sn
    // 为避免和主板序列号功能互相覆盖, 拨号盘 SN 写入**不动** ro.*serialno 系列 prop
    private val SN_WRITE_PROPS = listOf(
        "gsm.sn",
        "persist.sys.sn",
        "ril.sn",
        "persist.sys.sn2",
        "ro.boot.sn",
        "sys.sn"
    )

    // 读取集比写入集更宽: 也包含 ro.serialno / ro.boot.serialno / persist.sys.serialno
    // 作为后各选 prop 集来诊断能力, 任一条非空都能读到显示出来, 不再返回空 (但要避免暴露 prop 给用户)
    private val SN_READ_PROPS = SN_WRITE_PROPS + listOf(
        "ro.serialno",
        "ro.boot.serialno",
        "persist.sys.serialno"
    )

    fun readSn(): ShellResult {
        val sb = StringBuilder()
        for (key in SN_READ_PROPS) {
            val v = runRootShell("getprop $key 2>/dev/null").output.trim()
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("$key=${v.ifBlank { "(空)" }}")
        }
        return ShellResult(0, sb.toString())
    }

    fun writeSn(newSn: String): ShellResult {
        if (newSn.isBlank()) return ShellResult(-1, "SN 码不能为空")
        if (newSn.length < 2) return ShellResult(-1, "SN 码长度至少 2 位")
        if (newSn.length > 64) return ShellResult(-1, "SN 码长度过长")
        if (!newSn.matches(Regex("[A-Za-z0-9._/\\-]+"))) return ShellResult(-1, "SN 码仅允许字母数字、点号、斜杠、下划线、连字符")

        val escaped = SuSession.getInstance().escapeShell(newSn).replace("\$", "\\$")

        // 备份原值 (全量备份所有 SN_READ_PROPS)
        val backupSb = StringBuilder("echo -n '' > /data/local/tmp/.hf_sn_backup\n")
        for (key in SN_READ_PROPS) {
            backupSb.append("echo '$key='\"\\$(getprop '$key')\" >> /data/local/tmp/.hf_sn_backup\n")
        }
        runRootShell(backupSb.toString())

        val cmds = mutableListOf<String>()
        for (key in SN_WRITE_PROPS) {
            cmds.add("resetprop $key '$escaped' 2>/dev/null || setprop $key '$escaped' 2>/dev/null || true")
        }
        // 同步写 settings_global.xml 的 device_serial, 让 Settings 详情里也变
        cmds.add(
            "if [ -f '$GLOBAL_XML' ]; then " +
            "sed -i 's|<string name=\"device_serial\">[^<]*</string>|<string name=\"device_serial\">$escaped</string>|' '$GLOBAL_XML' 2>/dev/null; " +
            "fi || true"
        )
        // 杀掉 RIL/TelephonyManager 进程让 *#06# 拨号盘下次显示的是新值，
        // 否则 TelephonyManager 缓存着旧值，即便 prop 改了拨号盘也不刷新
        cmds.add("killall com.android.phone 2>/dev/null || true")
        cmds.add("am force-stop com.android.phone 2>/dev/null || true")
        cmds.add("am force-stop com.android.providers.settings 2>/dev/null || true")
        cmds.add("echo 'SN_DONE'")

        // 落地到统一 module hf_device_props (与序列号/主板 ID/手机型号共用, 不再单独刷 hf_sn)
        val shEscSn = SuSession.getInstance().escapeShell(newSn)
        upsertModuleProps(SN_WRITE_PROPS.map { it to shEscSn })

        return runRootShell(cmds.joinToString("\n"))
    }

    fun restoreSn(): ShellResult {
        val backupRead = runRootShell(
            "test -f /data/local/tmp/.hf_sn_backup && cat /data/local/tmp/.hf_sn_backup 2>/dev/null || echo 'BACKUP_NOT_FOUND'"
        )
        if (backupRead.output.contains("BACKUP_NOT_FOUND")) {
            return ShellResult(-1, "未找到 SN 备份")
        }
        // 从统一 module 删除 SN_WRITE_PROPS (清掉重启持久化生效)
        removeModuleProps(SN_WRITE_PROPS)
        // 按备份逐条恢复 - 仅恢复 SN_WRITE_PROPS 范围内的 prop, 不动 ro.*.serialno (主板序列号功能职责)
        val snWriteSet = SN_WRITE_PROPS.toSet()
        val cmds = mutableListOf<String>()
        for (line in backupRead.output.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key !in snWriteSet) continue
            val value = line.substring(eq + 1).trim()
            val escaped = SuSession.getInstance().escapeShell(value).replace("\$", "\\$")
            cmds.add("resetprop $key '$escaped' 2>/dev/null || true")
        }
        cmds.add("rm -f /data/local/tmp/.hf_sn_backup 2>/dev/null")
        cmds.add("echo 'SN_RESTORED'")
        return runRootShell(cmds.joinToString("\n"))
    }

    // ==================== MEID / IMEI ====================
    //
    // 这两类号在拨号盘 *#06# 分别对应"IMEI"行(主号)/"MEID"行(CDMA 机型)。
    // 实测在 HyperOS/MIUI 上,拨号盘显示行为由三处驱动:
    //   1) modem EFS / NV item  (本质真值, TelephonyManager.getImei() 内部经 RIL 走到这里)
    //   2) com.android.phone 进程缓存  (RILD 启动时一次读 NV 后缓存到 ServiceState)
    //   3) prop: gsm.imei/ril.imei/persist.sys.imei 等供 SDK 反射读取
    //
    // 真正改"所有 App 都能拿到的 IMEI" → 必须改 modem EFS。但普通 root 上 nv 写入
    // 几乎都被 OEM 锁死, 标准做法是用 QPST / Box 工具重刷 QCN, 普通用户不便操作。
    //
    // 本工具采取的双层策略 - "尽量改 NV, 至少改 prop":
    //   Layer 1 (真改 NV): 通过 service call <iduel/atcmd> 系列 service 尝试 RIL atc
    //     "at+egmr=1,7,\"<newImei>\""  写入 NV550。绝大多数机型这条会失败 (OEM 锁),
    //     我们捕获失败码反馈给用户:"该机型 NV 锁死, prop 伪装层已生效"。
    //   Layer 2 (prop 伪装): resetprop 写 gsm.imei 等同源 prop, 沿用 SN 码套路:
    //     - 备份原值到 /data/local/tmp/.hf_imei_backup
    //     - resetprop + setprop 双跑
    //     - 落地统一 module hf_device_props 的 props.list
    //     - kill com.android.phone 让 com.android.phone 进程下次重启后从 prop 回读
    //   prop 伪装对"只 getprop 读 IMEI 的 App"立即生效; 对"TelephonyManager.getImei()"
    //   这类经 binder 走 RIL 的 App, 仅在 com.android.phone 重启读到 prop 缓存后才生效
    //   (部分机型硬编码从 NV 读, kill 也救不回来, 这时只能上 NV 改)。

    /** prop 同源集, 主路 gsm.imei (HyperOS 实测) */
    private val IMEI_WRITE_PROPS = listOf(
        "gsm.imei",
        "ril.imei",
        "persist.sys.imei",
        "persist.sys.imei2",
        "ro.boot.imei",
        "sys.imei"
    )

    /**
     * 读集更宽, 覆盖 HyperOS / MIUI 14+ / MTK / Qualcomm 各平台常见 prop 项.
     * Android 13+ service call iphonesubinfo 因 READ_PRIVILEGED_PHONE_STATE 限制对 root 都失败,
     * 实际可靠源只剩 prop. 这里尽可能拉全厂商衍生项, 任一非空都能展示.
     */
    private val IMEI_READ_PROPS = IMEI_WRITE_PROPS + listOf(
        // MIUI / HyperOS boot 段
        "ro.boot.imei1",
        "ro.boot.imei2",
        "ro.boot.miui.imei1",
        "ro.boot.miui.imei2",
        // Qualcomm / vendor radio 段
        "persist.vendor.radio.imei",
        "persist.vendor.radio.imei2",
        "ro.vendor.radio.imei",
        "ro.vendor.radio.imei2",
        // MTK 平台常见
        "ro.boot.imei_label",
        // 临时段 (有时被 vendor init 写)
        "vendor.radio.imei",
        "vendor.radio.imei2"
    )

    /** MEID prop 同源集 (CDMA 机型使用, 与 GSM IMEI 互斥) */
    private val MEID_WRITE_PROPS = listOf(
        "gsm.meid",
        "ril.meid",
        "persist.sys.meid",
        "ro.boot.meid",
        "sys.meid"
    )

    private val MEID_READ_PROPS = MEID_WRITE_PROPS + listOf(
        "ro.boot.miui.meid",
        "persist.sys.meid2",
        "persist.vendor.radio.meid",
        "ro.vendor.radio.meid",
        "vendor.radio.meid"
    )

    /** EFS / QCN 关键备份路径(用于 NV 写入前手动备份) */
    private val IMEI_BACKUP = "/data/local/tmp/.hf_imei_backup"
    private val MEID_BACKUP = "/data/local/tmp/.hf_meid_backup"

    /** NV 写入会落到的关键 EFS 目录(仅用于 backup, 不直接写覆盖避 brick) */
    private val EFS_BACKUP_DIRS = listOf(
        "/mnt/vendor/efs",
        "/vendor/efs",
        "/persist/rfs",
        "/persist/hlos_rfs"
    )

    /**
     * 读取当前 IMEI (多源 fallback)
     *   1) service call iphonesubinfo 1 (slot 0) → 实际 RIL 真值
     *   2) 失败则逐条读 IMEI_READ_PROPS  (prop 视图, 可能是被 resetprop 改过的)
     */
    fun readImei(): ShellResult {
        val debugSb = StringBuilder()   // 所有解析过程信息 → 给 result.output, UI 若要展示诊断可看
        // 优先 RIL 真值 (多种 service call 号 + dumpsys)
        for (svcId in listOf(1, 4, 11)) {
            val raw = runRootShell("service call iphonesubinfo $svcId 2>&1").output.trim()
            if (raw.isBlank()) continue
            // Android 13+ 上 service call 多半返回 Permission Denial 或 Result: Parcel(00000000 ...)
            // 这些信息直接贴给用户看, 不再用 contains("Exception") 当过滤条件 (会把 deny 也漏掉)
            debugSb.append("[service call $svcId raw] ${raw.take(300)}\n")
            val parsed = extractImeiFromParcel(raw)
            if (!parsed.isNullOrBlank()) {
                debugSb.append("iphonesubinfo.$svcId=$parsed\n")
            }
        }
        val dumpOut = runRootShell("dumpsys iphonesubinfo 2>&1").output.trim()
        if (dumpOut.isNotBlank()) {
            debugSb.append("[dumpsys iphonesubinfo raw] ${dumpOut.take(300)}\n")
            val m = Regex("(?i)imei[\\s:=]+([0-9]{14,15})").find(dumpOut)
            if (m != null) debugSb.append("dumpsys.imei=${m.groupValues[1]}\n")
        }
        // 选取第一段成功解析出的纯 IMEI 15 位数字作为最终 RIL(slot0) 真值
        val rilImei = Regex("\\b\\d{15}\\b").find(debugSb.toString())?.value
        val sb = StringBuilder()
        sb.append("RIL(slot0)=${if (rilImei.isNullOrBlank()) "(空)" else rilImei}")
        if (debugSb.isNotBlank()) {
            sb.append("\n# 解析明细 (供诊断):\n")
            sb.append(debugSb.toString().trim())
        }
        // prop 视图 - Android 13+ 上这是最可靠源
        val propSb = StringBuilder()
        for (key in IMEI_READ_PROPS) {
            val v = runRootShell("getprop $key 2>/dev/null").output.trim()
            propSb.append("$key=${v.ifBlank { "(空)" }}\n")
        }
        sb.append("\n# prop 视图:\n").append(propSb.toString().trim())
        return ShellResult(0, sb.toString())
    }

    fun readMeid(): ShellResult {
        val debugSb = StringBuilder()
        for (svcId in listOf(6, 7, 12)) {
            val raw = runRootShell("service call iphonesubinfo $svcId 2>&1").output.trim()
            if (raw.isBlank()) continue
            debugSb.append("[service call $svcId raw] ${raw.take(300)}\n")
            val parsed = extractMeidFromParcel(raw)
            if (!parsed.isNullOrBlank()) {
                debugSb.append("iphonesubinfo.$svcId=$parsed\n")
            }
        }
        val dumpOut = runRootShell("dumpsys iphonesubinfo 2>&1").output.trim()
        if (dumpOut.isNotBlank()) {
            debugSb.append("[dumpsys iphonesubinfo raw] ${dumpOut.take(300)}\n")
            val m = Regex("(?i)meid[\\s:=]+([0-9a-fA-F]{14,18})").find(dumpOut)
            if (m != null) debugSb.append("dumpsys.meid=${m.groupValues[1]}\n")
        }
        val rilMeid = Regex("\\b[0-9a-fA-F]{14,18}\\b").find(debugSb.toString())?.value
        val sb = StringBuilder()
        sb.append("RIL(slot0)=${if (rilMeid.isNullOrBlank()) "(空)" else rilMeid}")
        if (debugSb.isNotBlank()) {
            sb.append("\n# 解析明细 (供诊断):\n")
            sb.append(debugSb.toString().trim())
        }
        val propSb = StringBuilder()
        for (key in MEID_READ_PROPS) {
            val v = runRootShell("getprop $key 2>/dev/null").output.trim()
            propSb.append("$key=${v.ifBlank { "(空)" }}\n")
        }
        sb.append("\n# prop 视图:\n").append(propSb.toString().trim())
        return ShellResult(0, sb.toString())
    }

    /**
     * 从 service call 的 Parcel hex 输出中提取 IMEI (15 位数字).
     *
     * service call 输出形如:
     *   Result: Parcel(0049d60f 0f000000 35 33 35 33 ... 'i' 'm' 'e' 'i' '=' '3' '5' '6' '7' ...)
     * 或纯 hexparcel 行无 'x' 标记 (老版本):
     *   Result: Parcel(6d920100 09000000 69000000 6d000000 65000000 69000000 ...)
     *
     * 旧版 'i' 'm' 'e' 'i' 这种带引号的可打印字符能直接串成字符串.
     * 老版 hexparcel 格式每个字符占 4 字节, ASCII 字符在末位.
     *
     * 提取策略: 把所有被 'X' 引号包围的 ascii 字符串起来 → 找 15 位数字模式;
     * 退化路径: 把全部 hex 视作 2 位字节序列, 找 ASCII 0x30..0x39 (数字) 提取连成串;
     * 任一路径提得出 15 位 IMEI 即返回.
     */
    private fun extractImeiFromParcel(parcelText: String): String? {
        // 路径1: printable 字符串直接 join
        val printable = Regex("'.'").findAll(parcelText)
            .map { it.value.trim('\'') }
            .joinToString("")
        Regex("\\d{15}").find(printable)?.let { return it.value }

        // 路径2: hex 字节序列解析 — 按规则从 4 字节小端或 1 字节大端解码
        // 形如 69000000 (i) 这种 4-byte 小端 ascii in parcel
        val hexTokens = parcelText.split(Regex("\\s+"))
            .filter { it.matches(Regex("[0-9a-fA-F]{8}")) }
        val sb = StringBuilder()
        for (token in hexTokens) {
            // 取最后 1 字节 (ascii char 装在小端 parcel 末位)
            val byte = token.substring(6, 8).toInt(16)
            if (byte in 0x20..0x7e) sb.append(byte.toChar())
        }
        Regex("\\d{15}").find(sb.toString())?.let { return it.value }

        // 路径3: 2 字节 token (每个 byte 直接是 ascii)
        val hex2 = parcelText.split(Regex("\\s+"))
            .filter { it.matches(Regex("[0-9a-fA-F]{2}")) }
        val sb2 = StringBuilder()
        for (t in hex2) {
            val b = t.toInt(16)
            if (b in 0x20..0x7e) sb2.append(b.toChar())
        }
        Regex("\\d{15}").find(sb2.toString())?.let { return it.value }
        return null
    }

    /**
     * 从 service call Parcel 提取 MEID (14 位 hex).
     * MEID 是 14 位十六进制,与 IMEI 15 位数字不同.
     */
    private fun extractMeidFromParcel(parcelText: String): String? {
        val printable = Regex("'.'").findAll(parcelText)
            .map { it.value.trim('\'') }
            .joinToString("")
        Regex("[0-9a-fA-F]{14}").find(printable)?.let { return it.value }

        val hexTokens = parcelText.split(Regex("\\s+"))
            .filter { it.matches(Regex("[0-9a-fA-F]{8}")) }
        val sb = StringBuilder()
        for (token in hexTokens) {
            val byte = token.substring(6, 8).toInt(16)
            if (byte in 0x20..0x7e) sb.append(byte.toChar())
        }
        Regex("[0-9a-fA-F]{14}").find(sb.toString())?.let { return it.value }

        val hex2 = parcelText.split(Regex("\\s+"))
            .filter { it.matches(Regex("[0-9a-fA-F]{2}")) }
        val sb2 = StringBuilder()
        for (t in hex2) {
            val b = t.toInt(16)
            if (b in 0x20..0x7e) sb2.append(b.toChar())
        }
        Regex("[0-9a-fA-F]{14}").find(sb2.toString())?.let { return it.value }
        return null
    }

    /**
     * NV 写入尝试 - 通过 RIL AT 命令通道
     *   高通/MTK 多数 RIL 实现 "AT+EGMR=1,7,\"<imei>\"" 用于写 IMEI 至 NV
     *   EGMR 实测在 XMM/Theia 平台 (Intel/三星自主) 不支持, 多数 OEM 锁
     *   失败属预期 → 返回 exitCode != 0 表示该机型 NV 不开放
     *
     * 通过 diag device 写入(NV item 550)需 SELinux 上下文切换且 OEM 签名鉴权,
     * 普通用户态普通应用直写必定失败且可能损伤 RIL → 本工具走 **只 AT 通道**路径,
     * 失败优雅降级到 prop 伪装层。
     *
     * slot: 7=slot0 IMEI1, 8=slot1 IMEI2 (常见约定, 部分机型相反)
     */
    private fun tryWriteNvImeiAtc(newImei: String, slot: Int = 7): ShellResult {
        if (!isValidImei15(newImei)) return ShellResult(-1, "NV 写入要求 IMEI 严格 15 位 + Luhn 合规")
        val esc = SuSession.getInstance().escapeShell(newImei).replace("\$", "\\$")
        // service call 向 ITelephony.sendMessageRequestAtc 走 binder,部分平台暴露在 slot 0
        // 失败码常见: transact returned -1 / service not found
        val cmd = "service call phone 113 s16 \"$esc\" i32 $slot 2>&1 || true"
        val out = runRootShell(cmd).output.trim()
        // 也尝试 atinout 直接写 /dev/smd0 /dev/ttyGSM0(部分 MTK 平台 smd0 是 active MD)
        val diag = runRootShell(
            "for dev in /dev/smd0 /dev/ttyGSM0 /dev/md_ut /dev/radio0 /dev/smd7 /dev/ttyMT0 /dev/ttyHS0; do " +
                "if [ -e \"\$dev\" ]; then " +
                "atinout -  \"\$dev\" <<EOF 2>/dev/null\nAT+EGMR=1,$slot,\"$esc\"\nEOF\n" +
                "break; " +
                "fi; " +
                "done 2>&1 || true"
        ).output.trim()
        val combined = buildString {
            appendLine("service_call: ${out.ifBlank { "(no output)" }}")
            appendLine("atc_egmr: ${diag.ifBlank { "(无可用 AT 通道设备)" }}")
        }
        // RIL AT 命令成功响应会含 "OK",  失败含 "ERROR" / "COMMAND NOT SUPPORT" / "Permission denied"
        val success = out.contains("OK", ignoreCase = true) || diag.contains("OK", ignoreCase = true)
        val unsupported = out.contains("not support", ignoreCase = true) ||
            diag.contains("not support", ignoreCase = true) ||
            out.contains("Transact", ignoreCase = true)
        return when {
            success -> ShellResult(0, "NV 写入成功 (RIL 已确认 OK)\n$combined")
            unsupported -> ShellResult(-1, "该机型 NV 不开放 EGMR / service call 不允许写入\n$combined")
            else -> ShellResult(-2, "NV 写入结果不确定, 多半被 OEM 拦截\n$combined")
        }
    }

    /** MEID 没有 service 层统一的 atc 写入通道, NV 改一般要 OEM 签名鉴权 */
    private fun tryWriteNvMeidAtc(newMeid: String, slot: Int = 7): ShellResult {
        if (!isValidMeid14(newMeid)) return ShellResult(-1, "NV 写入要求 MEID 严格 14 hex")
        val esc = SuSession.getInstance().escapeShell(newMeid).replace("\$", "\\$")
        val combined = runRootShell(
            "for dev in /dev/smd0 /dev/ttyGSM0 /dev/md_ut /dev/radio0 /dev/smd7 /dev/ttyMT0 /dev/ttyHS0; do " +
                "if [ -e \"\$dev\" ]; then " +
                "atinout -  \"\$dev\" <<EOF 2>/dev/null\nAT+EGMR=1,$slot,\"$esc\"\nEOF\n" +
                "break; " +
                "fi; " +
                "done 2>&1 || true"
        ).output.trim()
        // 用 service call iphonesubinfo slot 6 (MEID) 写
        val svcOut = runRootShell(
            "service call iphonesubinfo 12 s16 \"$esc\" 2>&1 || true"
        ).output.trim()
        val text = buildString {
            appendLine("atc_egmr: ${combined.ifBlank { "(无 AT 通道)" }}")
            appendLine("iphonesubinfo_12: ${svcOut.ifBlank { "(no output)" }}")
        }
        return ShellResult(-1, "MEID NV 写入需 OEM 鉴权, 普通权限几乎必失败\n$text")
    }

    /** 单独备份 EFS (NV 写入前置), 失败不阻断主流程 */
    private fun backupEfs(): ShellResult {
        val cmds = mutableListOf<String>()
        cmds.add("mkdir -p /data/local/tmp/.hf_efs_backup 2>/dev/null")
        for (d in EFS_BACKUP_DIRS) {
            cmds.add(
                "if [ -d '$d' ]; then " +
                "cp -r '$d' /data/local/tmp/.hf_efs_backup/$(basename '$d') 2>/dev/null; " +
                "fi || true"
            )
        }
        cmds.add("ls -la /data/local/tmp/.hf_efs_backup 2>/dev/null || echo 'EFS_EMPTY'")
        cmds.add("echo 'EFS_BACKUP_DONE'")
        return runRootShell(cmds.joinToString("\n"))
    }

    /**
     * 备份当前 IMEI prop + RIL 真值到本地备份, 用户后续可 restore
     *
     * RIL 真值存原始 service call 输出整段, restore 时由 [readImei] 的解析函数
     * 提取 15 位数字. 不再用 cut -c 52-66 这种位置硬切割, 因 Android 版本/ROM 不同位置不定.
     */
    private fun backupCurrentImei() {
        val backupSb = StringBuilder("echo -n '' > $IMEI_BACKUP 2>/dev/null\n")
        // RIL 真值 - 原始输出全保留, 解析在 restore 端做
        backupSb.append("echo 'RIL_RAW='\"\\$(service call iphonesubinfo 1 2>/dev/null)\" >> $IMEI_BACKUP\n")
        backupSb.append("echo 'DUMPSYS='\"\\$(dumpsys iphonesubinfo 2>/dev/null)\" >> $IMEI_BACKUP\n")
        for (key in IMEI_READ_PROPS) {
            backupSb.append("echo '$key='\"\\$(getprop '$key')\" >> $IMEI_BACKUP\n")
        }
        runRootShell(backupSb.toString())
    }

    private fun backupCurrentMeid() {
        val backupSb = StringBuilder("echo -n '' > $MEID_BACKUP 2>/dev/null\n")
        backupSb.append("echo 'RIL_RAW='\"\\$(service call iphonesubinfo 6 2>/dev/null)\" >> $MEID_BACKUP\n")
        backupSb.append("echo 'DUMPSYS='\"\\$(dumpsys iphonesubinfo 2>/dev/null)\" >> $MEID_BACKUP\n")
        for (key in MEID_READ_PROPS) {
            backupSb.append("echo '$key='\"\\$(getprop '$key')\" >> $MEID_BACKUP\n")
        }
        runRootShell(backupSb.toString())
    }

    /**
     * prop 伪装层 - 沿用 writeSn 套路 (resetprop + setprop + kill phone + module 落地)
     */
    private fun writeImeiProp(newImei: String): ShellResult {
        val escaped = SuSession.getInstance().escapeShell(newImei).replace("\$", "\\$")
        val cmds = mutableListOf<String>()
        for (key in IMEI_WRITE_PROPS) {
            cmds.add("resetprop $key '$escaped' 2>/dev/null || setprop $key '$escaped' 2>/dev/null || true")
        }
        // 杀 com.android.phone 让它下次从 prop 回读
        cmds.add("killall com.android.phone 2>/dev/null || true")
        cmds.add("am force-stop com.android.phone 2>/dev/null || true")
        cmds.add("echo 'IMEI_PROP_DONE'")
        // 落地到统一 module
        val shEsc = SuSession.getInstance().escapeShell(newImei)
        upsertModuleProps(IMEI_WRITE_PROPS.map { it to shEsc })
        return runRootShell(cmds.joinToString("\n"))
    }

    private fun writeMeidProp(newMeid: String): ShellResult {
        val escaped = SuSession.getInstance().escapeShell(newMeid).replace("\$", "\\$")
        val cmds = mutableListOf<String>()
        for (key in MEID_WRITE_PROPS) {
            cmds.add("resetprop $key '$escaped' 2>/dev/null || setprop $key '$escaped' 2>/dev/null || true")
        }
        cmds.add("killall com.android.phone 2>/dev/null || true")
        cmds.add("am force-stop com.android.phone 2>/dev/null || true")
        cmds.add("echo 'MEID_PROP_DONE'")
        val shEsc = SuSession.getInstance().escapeShell(newMeid)
        upsertModuleProps(MEID_WRITE_PROPS.map { it to shEsc })
        return runRootShell(cmds.joinToString("\n"))
    }

    /**
     * 复合写 IMEI - 先 EFS 备份 → 再 NV 写入(可能失败) → 必跑 prop 伪装
     * 返回 ShellResult, output 含两层执行结果摘要
     */
    fun writeImei(newImei: String): ShellResult {
        if (newImei.isBlank()) return ShellResult(-1, "IMEI 不能为空")
        if (!isValidImei15(newImei)) {
            return ShellResult(-1, "IMEI 必须是 15 位数字 + Luhn 校验和合规")
        }
        // 1) 备份 + EFS 备份
        backupCurrentImei()
        backupEfs()
        // 2) NV 写入(可失败)
        val nvResult = tryWriteNvImeiAtc(newImei)
        // 3) prop 伪装(必跑, 即便 NV 失败也对 SDK 反射有效)
        val propResult = writeImeiProp(newImei)
        val summary = buildString {
            appendLine("=== NV 写入层 ===")
            appendLine("exitCode=${nvResult.exitCode}")
            appendLine(nvResult.output)
            appendLine()
            appendLine("=== Prop 伪装层 ===")
            appendLine(propResult.output)
            appendLine()
            appendLine("已备份原值到 $IMEI_BACKUP  (EFS 备份在 /data/local/tmp/.hf_efs_backup)")
            appendLine("可使用 restoreImei() 一键恢复")
        }
        return ShellResult(propResult.exitCode, summary)
    }

    fun writeMeid(newMeid: String): ShellResult {
        if (newMeid.isBlank()) return ShellResult(-1, "MEID 不能为空")
        if (!isValidMeid14(newMeid)) {
            return ShellResult(-1, "MEID 必须严格是 14 位十六进制 (0-9A-F)")
        }
        backupCurrentMeid()
        // 注意: MEID NV 写入几乎必失败, 走它仅为日志信息, 不影响后续
        val nvResult = tryWriteNvMeidAtc(newMeid)
        val propResult = writeMeidProp(newMeid)
        val summary = buildString {
            appendLine("=== NV 写入层 ===")
            appendLine("exitCode=${nvResult.exitCode}")
            appendLine(nvResult.output)
            appendLine()
            appendLine("=== Prop 伪装层 ===")
            appendLine(propResult.output)
            appendLine()
            appendLine("已备份原值到 $MEID_BACKUP")
            appendLine("可使用 restoreMeid() 一键恢复")
        }
        return ShellResult(propResult.exitCode, summary)
    }

    fun restoreImei(): ShellResult {
        val backupRead = runRootShell(
            "test -f $IMEI_BACKUP && cat $IMEI_BACKUP 2>/dev/null || echo 'BACKUP_NOT_FOUND'"
        )
        if (backupRead.output.contains("BACKUP_NOT_FOUND")) {
            return ShellResult(-1, "未找到 IMEI 备份")
        }
        // 从 module 删除 IMEI_WRITE_PROPS (清掉持久化段)
        removeModuleProps(IMEI_WRITE_PROPS)
        val writePropsSet = IMEI_WRITE_PROPS.toSet()
        val cmds = mutableListOf<String>()
        var focusedKey: String? = null
        for (line in backupRead.output.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key == "RIL") {
                // RIL 真值记下来, prop layer 不能直接回写 RIL NV
                continue
            }
            if (key !in writePropsSet) continue
            // 找第一个非空 prop 当优先恢复目标
            if (value.isNotBlank() && focusedKey == null) {
                focusedKey = key
            }
            val escaped = SuSession.getInstance().escapeShell(value).replace("\$", "\\$")
            cmds.add("resetprop $key '$escaped' 2>/dev/null || true")
        }
        cmds.add("killall com.android.phone 2>/dev/null || true")
        cmds.add("am force-stop com.android.phone 2>/dev/null || true")
        cmds.add("rm -f $IMEI_BACKUP 2>/dev/null")
        cmds.add("echo 'IMEI_RESTORED'")
        if (focusedKey == null) {
            val msg = "备份中未找到 IMEI prop (仅 RIL 真值), 只清空了 module 段。" +
                "若拨号盘仍显 NV 原值: 该机型 prop 伪装影响窄, RIL 真值只能 QCN 重刷恢复"
            return ShellResult(-1, msg + "\n" + runRootShell(cmds.joinToString("\n")).output)
        }
        return runRootShell(cmds.joinToString("\n"))
    }

    fun restoreMeid(): ShellResult {
        val backupRead = runRootShell(
            "test -f $MEID_BACKUP && cat $MEID_BACKUP 2>/dev/null || echo 'BACKUP_NOT_FOUND'"
        )
        if (backupRead.output.contains("BACKUP_NOT_FOUND")) {
            return ShellResult(-1, "未找到 MEID 备份")
        }
        removeModuleProps(MEID_WRITE_PROPS)
        val writePropsSet = MEID_WRITE_PROPS.toSet()
        val cmds = mutableListOf<String>()
        for (line in backupRead.output.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key == "RIL") continue
            if (key !in writePropsSet) continue
            val value = line.substring(eq + 1).trim()
            val escaped = SuSession.getInstance().escapeShell(value).replace("\$", "\\$")
            cmds.add("resetprop $key '$escaped' 2>/dev/null || true")
        }
        cmds.add("killall com.android.phone 2>/dev/null || true")
        cmds.add("am force-stop com.android.phone 2>/dev/null || true")
        cmds.add("rm -f $MEID_BACKUP 2>/dev/null")
        cmds.add("echo 'MEID_RESTORED'")
        return runRootShell(cmds.joinToString("\n"))
    }

    fun clearImeiModule(): ShellResult {
        removeModuleProps(IMEI_WRITE_PROPS)
        val cmds = mutableListOf<String>()
        for (key in IMEI_WRITE_PROPS) {
            cmds.add("resetprop --delete $key 2>/dev/null || true")
        }
        cmds.add("echo 'IMEI_CLEAR_DONE'")
        return runRootShell(cmds.joinToString("\n"))
    }

    fun clearMeidModule(): ShellResult {
        removeModuleProps(MEID_WRITE_PROPS)
        val cmds = mutableListOf<String>()
        for (key in MEID_WRITE_PROPS) {
            cmds.add("resetprop --delete $key 2>/dev/null || true")
        }
        cmds.add("echo 'MEID_CLEAR_DONE'")
        return runRootShell(cmds.joinToString("\n"))
    }

    // ==================== 主板 ID ====================
    //
    // 爱玩机工具箱中"修改主板 ID"该字段在红米 K70 上读到的就是：
    //   ro.boot.cpuid = 0x000004483bb3a50d  (前缀 0x + 16 hex digit)
    // 由 bootloader 从 SoC 硬件熔丝 / chipid 寄存器烧入 —— 真正的 SoC 唯一芯片 ID。
    // 在该工具里"主板 ID"与"cpuid"是同一个东西，cpuid 作为"主板 ID"显示字段。
    //
    // 我们因此不再把 SoC 平台代号 (pineapple/lahaina) 当主板 ID，也不再分两个工具，
    // 直接以 ro.boot.cpuid 作为"主板 ID"主路 prop。
    //
    // 同源覆盖 prop 集 (这些 prop 在不同机型分别 fallback 作为"主板 ID"显示):
    //   ro.boot.cpuid           — 主路，绝大多数高通机型走这条
    //   ro.boot.cell_id         — SoC cell/batch ID (如 AG353A70K6AD3)，部分机型当主板 ID 显示
    //   ro.boot.hardware.id     — 厂商写入的主板硬件 ID (部分非高通机型走这条)
    //   ro.boot.mlbid            — Main Logic Board ID (部分厂商)
    //   ro.boot.em.modelid       — Embedded Model ID (部分厂商)
    //   persist.sys.motherboard_id — 厂商 persist 主板 ID (定制 ROM)
    //   ro.boot.mifavor.id       — 中兴 mifavor 主板标识
    //
    // 注意: ro.boot.hardware.revision / ro.boot.hwversion / ro.boot.hwlevel
    //    是 PVT/EVT/3.9.0 类工程版本号字符串，不是 hex chipid —— 严格语义不是"主板 ID"，
    //    本工具不写入这些 prop，避免把核对版本号的检测逻辑搞乱。
    private val MAINBOARD_PROPS = listOf(
        "ro.boot.cpuid",
        "ro.boot.cell_id",
        "ro.boot.hardware.id",
        "ro.boot.mlbid",
        "ro.boot.em.modelid",
        "persist.sys.motherboard_id",
        "ro.boot.mifavor.id"
    )

    /**
     * 仅供 read 展示，本工具不写入的 prop：
     * - ro.boot.hardware.revision / ro.boot.hwversion / ro.boot.hwlevel (工程版本号 PVT/EVT/3.9.0)
     * - ro.boot.product.hardware.sku / ro.boot.hardware.sku (机型代号 manet)
     * - ro.board.platform / ro.boot.product.vendor.sku (SoC 平台代号 pineapple)
     * - ro.hardware / ro.boot.hardware / ro.board.hardware / ro.soc.model
     * 让用户看见但确保不被本工具当作"主板 ID"误改。
     */
    private val MAINBOARD_DISPLAY_ONLY_PROPS = listOf(
        "ro.boot.hardware.revision",
        "ro.boot.hwversion",
        "ro.boot.hwlevel",
        "ro.boot.product.hardware.sku",
        "ro.boot.hardware.sku",
        "ro.board.platform",
        "ro.boot.board.platform",
        "ro.boot.product.vendor.sku",
        "ro.hardware",
        "ro.boot.hardware",
        "ro.board.hardware",
        "ro.soc.model"
    )

    fun readMainboardId(): ShellResult {
        val sb = StringBuilder()
        // 先展示可改的 prop (MAINBOARD_PROPS)
        for (key in MAINBOARD_PROPS) {
            val v = runRootShell("getprop $key 2>/dev/null").output.trim()
            if (v.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("$key=$v")
            }
        }
        // 再展示 display-only prop（仅信息展示，本工具不会写入）
        for (key in MAINBOARD_DISPLAY_ONLY_PROPS) {
            val v = runRootShell("getprop $key 2>/dev/null").output.trim()
            if (v.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("$key=$v  (仅显示，不在本工具修改范围)")
            }
        }
        if (sb.isBlank()) {
            sb.append("(未发现任何主板 ID prop；该 bootloader 可能未暴露 cpuid / cell_id)")
        }
        return ShellResult(0, sb.toString())
    }

    fun writeMainboardId(newId: String): ShellResult {
        return writeMainboardIdForProp(null, newId)
    }

    /**
     * 修改单条主板 ID 属性。targetKey 为 null 时写入 MAINBOARD_PROPS 全部(兼容旧调用)。
     * targetKey 必须是 MAINBOARD_PROPS 中的成员。
     */
    fun writeMainboardIdForProp(targetKey: String?, newId: String): ShellResult {
        if (newId.isBlank()) return ShellResult(-1, "主板 ID 不能为空")
        if (newId.length < 2) return ShellResult(-1, "主板 ID 长度过短")
        if (newId.length > 64) return ShellResult(-1, "主板 ID 长度过长 (实际 < 32 字符)")

        val cleaned = newId.trim()

        // 主板 ID 接受两种形态：
        //   1) SoC cpuid 类 hex 串：0x000004483bb3a50d  (0x 前缀 + 8-20 hex digit)
        //   2) cell_id / mlbid / hwid 类字母+数字混合串：AG353A70K6AD3
        // 共同约束：仅含可打印 ASCII、字符种类为 [0-9a-fA-F]+(可能含 .-_/ 空格)
        // 仍禁止：含空字节、控制字符
        val isHexLike = (cleaned.startsWith("0x") || cleaned.startsWith("0X")) &&
                cleaned.substring(2).matches(Regex("^[0-9a-fA-F]+$")) &&
                cleaned.substring(2).length in 8..20
        val isAlphaNumericMixed = cleaned.matches(Regex("^[0-9a-zA-Z._\\-/]+$")) &&
                cleaned.any { it.isDigit() } &&
                cleaned.any { it.isLetter() }

        if (!isHexLike && !isAlphaNumericMixed) {
            return ShellResult(-1, "主板 ID 格式不符合：要么是 hex (e.g. 0x000004483bb3a50d)，要么是字母数字混合 (e.g. AG353A70K6AD3)")
        }

        if (cleaned.length < 4) {
            return ShellResult(-1, "主板 ID 长度过短 (实测 cell_id 13 字符 / cpuid 18 字符)")
        }

        val targetProp: String? = targetKey?.takeIf { MAINBOARD_PROPS.contains(it) }

        val escaped = SuSession.getInstance().escapeShell(cleaned).replace("\$", "\\\$")

        val cmds = mutableListOf<String>()
        val targets = if (targetProp != null) listOf(targetProp) else MAINBOARD_PROPS
        for (key in targets) {
            cmds.add("resetprop $key '$escaped' 2>/dev/null || setprop $key '$escaped' 2>/dev/null || true")
        }
        cmds.add("echo 'MAINBOARD_DONE'")

        // 让 prop 缓存的进程重读新值，否则 Settings 等还显示旧值
        cmds.add("am force-stop com.android.providers.settings 2>/dev/null || true")
        cmds.add("killall com.android.providers.settings 2>/dev/null || true")

        // 落地到统一 module hf_device_props (不再单独刷 hf_mainboard)
        upsertModuleProps(targets.map { it to escaped })

        return runRootShell(cmds.joinToString("\n"))
    }

    /**
     * 只读指定 prop，空字符串表示读不到。
     */
    fun readMainboardIdForProp(key: String): String {
        return try {
            val out = runRootShell("getprop $key").output.trim()
            out
        } catch (e: Exception) {
            ""
        }
    }

    fun clearMainboardIdModule(): ShellResult {
        // 删除统一 module 内所有 MAINBOARD_PROPS 对应行
        removeModuleProps(MAINBOARD_PROPS)
        // 实时删除运行中的 prop 值
        val cmds = mutableListOf<String>()
        for (key in MAINBOARD_PROPS) {
            cmds.add("resetprop --delete $key 2>/dev/null || true")
        }
        cmds.add("echo 'MAINBOARD_CLEAR_DONE'")
        return runRootShell(cmds.joinToString("\n"))
    }

    /**
     * 只清除单条 prop 的覆盖段。targetKey 必须是 MAINBOARD_PROPS 中的成员。
     * 从统一 module props.list 删除该 key 对应行。
     */
    fun clearMainboardIdForProp(targetKey: String?): ShellResult {
        if (targetKey == null) return clearMainboardIdModule()
        if (!MAINBOARD_PROPS.contains(targetKey)) {
            return ShellResult(-1, "不支持的 prop: $targetKey")
        }
        removeModuleProps(listOf(targetKey))
        val r = runRootShell(
            "resetprop --delete $targetKey 2>/dev/null || true; " +
            "echo 'MAINBOARD_CLEAR_DONE'"
        )
        return r
    }

    // ==================== 手机型号 (参考阿灵全局机型模拟 KSU 模块) ====================
    //
    // 阿灵的 KSU 模块核心只改 4 条 prop 就完成伪装：
    //   ro.product.manufacturer  厂商
    //   ro.product.brand         品牌
    //   ro.product.model         内部型号代号 (如 PLZ110、23127PN0CC)
    //   ro.product.marketname    市场宣传名 (如 OnePlus 15 T、小米 15)
    //
    // 为兼容更广泛的检测/查询场景，每个字段同步写产品分区衍生 prop：
    //   vendor.* / odm.* / system.* / system_ext.* 以及 ro.build.product / ro.build.model
    //
    // 4 个字段全部由用户自定义，留空的字段保留原始值不动。

    data class ModelFields(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val marketname: String
    )

    // 每个字段对应需同步覆盖的 prop 集合, model 字段同时包含 ro.build.* 兼容老读取点
    private val MANUFACTURER_PROPS = listOf(
        "ro.product.manufacturer",
        "ro.product.vendor.manufacturer",
        "ro.product.odm.manufacturer",
        "ro.product.system.manufacturer",
        "ro.product.system_ext.manufacturer"
    )

    private val BRAND_PROPS = listOf(
        "ro.product.brand",
        "ro.product.vendor.brand",
        "ro.product.odm.brand",
        "ro.product.system.brand",
        "ro.product.system_ext.brand"
    )

    private val MODEL_PROPS = listOf(
        "ro.product.model",
        "ro.product.vendor.model",
        "ro.product.odm.model",
        "ro.product.system.model",
        "ro.product.system_ext.model",
        "ro.build.product",
        "ro.build.model"
    )

    private val MARKETNAME_PROPS = listOf(
        "ro.product.marketname",
        "ro.product.vendor.marketname",
        "ro.product.odm.marketname",
        "ro.product.system.marketname",
        "ro.product.system_ext.marketname"
    )

    fun readModel(): ShellResult {
        val sb = StringBuilder()
        val allKeys = MANUFACTURER_PROPS + BRAND_PROPS + MODEL_PROPS + MARKETNAME_PROPS
        for (key in allKeys) {
            val v = runRootShell("getprop $key 2>/dev/null").output.trim()
            // 只显示每个字段的主键（以 ro.product.xxx 开头，避免 vendor/odm 衍生位噪声）
            if (key.startsWith("ro.product.") && !key.contains(".vendor.") && !key.contains(".odm.") &&
                !key.contains(".system.") && !key.contains(".system_ext.")
            ) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("$key=${v.ifBlank { "(空)" }}")
            }
        }
        if (sb.isBlank()) sb.append("(无法读取)")
        return ShellResult(0, sb.toString())
    }

    /**
     * 修改手机型号 (4 字段全用户自定义)
     * 任一字段为空 → 该字段保留原值不动
     * 任一字段非空 → 该字段同步写入对应 prop 集合
     */
    fun writeModel(fields: ModelFields): ShellResult {
        val sb = StringBuilder()
        if (fields.manufacturer.isBlank() && fields.brand.isBlank() &&
            fields.model.isBlank() && fields.marketname.isBlank()
        ) {
            return ShellResult(-1, "至少填写一个字段")
        }

        // 各字段做长度与字符校验 (拒绝含单引号/反引号/$ 的值, 避免 shell 命令注入)
        fun validField(name: String, v: String) {
            if (v.isBlank()) return
            if (v.length > 96) sb.append("$name 长度过长\n")
            // 允许: 字母数字 空格 . _ - + ( ) & , /  以及中文 Unicode
            // 不允许: 单引号 反引号 $ \ 双引号 = 等会在 shell 上下文/prop 文件解析处破坏的字元
            if (!v.matches(Regex("[A-Za-z0-9 ._\\-/+()&,\\u4e00-\\u9fa5]+"))) sb.append("$name 含不支持字符\n")
        }
        validField("厂商", fields.manufacturer)
        validField("品牌", fields.brand)
        validField("型号", fields.model)
        validField("市场名", fields.marketname)
        if (sb.isNotEmpty()) return ShellResult(-1, sb.toString().trim())

        val esc = { v: String -> SuSession.getInstance().escapeShell(v).replace("\$", "\\$") }

        val cmds = mutableListOf<String>()

        // 列出 (字段值 → prop 集合) 的写入计划, 跳过空字段
        val plan = mutableListOf<Pair<String, List<String>>>()
        if (fields.manufacturer.isNotBlank()) plan.add(fields.manufacturer to MANUFACTURER_PROPS)
        if (fields.brand.isNotBlank()) plan.add(fields.brand to BRAND_PROPS)
        if (fields.model.isNotBlank()) plan.add(fields.model to MODEL_PROPS)
        if (fields.marketname.isNotBlank()) plan.add(fields.marketname to MARKETNAME_PROPS)

        for ((value, props) in plan) {
            val escaped = esc(value)
            for (key in props) {
                cmds.add("resetprop $key '$escaped' 2>/dev/null || setprop $key '$escaped' 2>/dev/null || true")
            }
        }
        cmds.add("echo 'MODEL_DONE'")

        // 落地到统一 module hf_device_props (不再单独刷 hf_model
        // - 4 字段对应 MANUFACTURER_PROPS/BRAND_PROPS/MODEL_PROPS/MARKETNAME_PROPS 全部以 KEY='VALUE' 入 props.list)
        val upsertPairs = mutableListOf<Pair<String, String>>()
        val shEsc = { v: String -> SuSession.getInstance().escapeShell(v) }
        for ((value, props) in plan) {
            val shv = shEsc(value)
            for (key in props) upsertPairs.add(key to shv)
        }
        upsertModuleProps(upsertPairs)

        return runRootShell(cmds.joinToString("\n"))
    }

    fun clearModelModule(): ShellResult {
        // 从统一 module 删除所有手机型号相关 prop
        removeModuleProps(MANUFACTURER_PROPS + BRAND_PROPS + MODEL_PROPS + MARKETNAME_PROPS)
        // 实时删除运行时 prop
        val cmds = mutableListOf<String>()
        for (key in MANUFACTURER_PROPS + BRAND_PROPS + MODEL_PROPS + MARKETNAME_PROPS) {
            cmds.add("resetprop --delete $key 2>/dev/null || true")
        }
        cmds.add("echo 'MODEL_CLEAR_DONE'")
        return runRootShell(cmds.joinToString("\n"))
    }

    private fun inferManufacturerFromModel(model: String): String {
        val m = model.lowercase()
        return when {
            m.startsWith("pixel") -> "Google"
            m.startsWith("sm-") || m.startsWith("samsung") -> "samsung"
            m.startsWith("redmi") || m.startsWith("mi ") || m.startsWith("2") -> "Xiaomi"
            m.startsWith("p") && (m.contains("pro") || m.contains("20")) -> "Huawei"
            m.startsWith("oneplus") || m.startsWith("ace") || m.startsWith("pjt") -> "OnePlus"
            m.contains("vivo") -> "vivo"
            m.contains("oppo") -> "OPPO"
            m.contains("realme") -> "realme"
            else -> "Xiaomi"
        }
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
