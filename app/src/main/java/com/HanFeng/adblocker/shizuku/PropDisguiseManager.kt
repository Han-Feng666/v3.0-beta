package com.HanFeng.adblocker.shizuku

import android.util.Log

/**
 * Prop 伪装管理器：通过 resetprop/magisk resetprop 修改系统属性，
 * 让银行/支付类 App 在 Build/ro.* 层面读不到 Root/调试/解锁痕迹。
 *
 * 关键修改点（基于 LSPosed/EdXposed/SafetyNet 检测特征）：
 * - ro.debuggable         Android Studio 调试开关
 * - ro.secure             安全构建标志（银行强检测项）
 * - ro.build.type         改为 user，避免 eng/userdebug 暴露
 * - ro.build.tags         release-keys（金融 App 常检测）
 * - ro.boot.verifiedbootstate   改为 green
 * - ro.boot.flash.locked        改为 1，表示 bootloader 已锁
 * - ro.boot.unlocked             改为 0（MIUI / Xiaomi 系金融 App 检测）
 * - ro.boot.veritymode          改为 enforcing
 * - ro.boot.vbmeta.device_state 改为 locked
 * - ro.bootloader               伪装 bootloader 版本字串
 * - ro.baseband                  伪装基带版本
 * - init.svc.magisk_pfs、ro.magisk.version 等可直接卸载/置空
 *
 * 兼容 Magisk 20+ 的 resetprop。KernelSU/APatch 走 kd期刊 resetprop 命令或 manual 路径切换。
 */
object PropDisguiseManager {

    private const val TAG = "PropDisguiseManager"

    /**
     * 关键 prop 的伪装映射。
     * Key 为 prop 名，Value 为伪装后的"应呈现值"。
     * 对应原始值会被持久备份到 /data/adb/hanfeng/prop_backup.txt，
     * 以便 [restore] 还原。
     */
    private val PROP_OVERRIDES = linkedMapOf(
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.build.type" to "user",
        "ro.build.tags" to "release-keys",
        "ro.boot.verifiedbootstate" to "green",
        "ro.boot.flash.locked" to "1",
        "ro.boot.unlocked" to "0",
        "ro.boot.veritymode" to "enforcing",
        "ro.boot.vbmeta.device_state" to "locked",
        "ro.boot.warranty_bit" to "0",
        "ro.warranty_bit" to "0",
        "ro.build.selinux" to "1",
        "ro.bootloader" to "unknown",
        "ro.baseband" to "unknown",
        "init.svc.magisk_pfsd" to "",
        "init.svc.magisk_pfs" to "",
        "ro.magisk.version" to "",
        "ro.magisk.versionCode" to "",
        "persist.magiskhide" to "0"
    )

    private const val BACKUP_FILE = "/data/adb/hanfeng/prop_backup.txt"
    private const val STATE_FILE = "/data/adb/hanfeng/prop_disguise_state"

    enum class DisguiseState { NOT_APPLIED, PARTIAL, FULL }

    data class ApplyResult(
        val success: Boolean,
        val appliedKeys: List<String>,
        val failedKeys: List<String>,
        val detail: String
    )

    data class Status(
        val state: DisguiseState,
        val totalKeys: Int,
        val appliedKeys: Int,
        val values: Map<String, String>
    )

    private val suSession get() = SuSession.getInstance()

    fun isAvailable(): Boolean {
        if (!suSession.isSessionOpen() && !suSession.open(10)) return false
        val r = suSession.execute(
            "command -v resetprop >/dev/null 2>&1 && echo HAVE_RESETPROP || " +
                "command -v magisk >/dev/null 2>&1 && echo HAVE_MAGISK || " +
                "command -v kproprop >/dev/null 2>&1 && echo HAVE_KPROPC || echo NONE",
            5
        )
        return !r.output.contains("NONE")
    }

    fun apply(): ApplyResult {
        if (!suSession.isSessionOpen() && !suSession.open(30)) {
            return ApplyResult(false, emptyList(), emptyList(), "Root 不可用")
        }
        if (!isAvailable()) {
            return ApplyResult(false, emptyList(), emptyList(),
                "未找到 resetprop，请确认 Magisk/KSU 已安装")
        }
        suSession.execute("mkdir -p /data/adb/hanfeng && touch '$STATE_FILE'", 5)

        // 备份原始值
        val backupScript = StringBuilder().apply {
            append("echo -n '' > '$BACKUP_FILE'\n")
            for ((key, _) in PROP_OVERRIDES) {
                append("echo '$key='\"$(getprop '$key')\" >> '$BACKUP_FILE'\n")
            }
        }.toString()
        suSession.execute(backupScript, 10)

        // 检测 resetprop 入口
        val detect = suSession.execute(
            "command -v resetprop 2>/dev/null | head -1", 3
        )
        val resetBin = detect.output.trim()
        val resetCmd = if (resetBin.isNotBlank()) resetBin else "magisk resetprop"

        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((key, value) in PROP_OVERRIDES) {
            val escapedVal = value.replace("'", "'\\''")
            // 部分设备 resetprop 对空值 prop 需 -d 处理
            val cmd = if (value.isBlank()) {
                "$resetCmd --delete '$key' 2>/dev/null && echo OK || $resetCmd '$key' '' 2>/dev/null && echo OK || echo FAIL"
            } else {
                "$resetCmd '$key' '$escapedVal' 2>/dev/null && echo OK || echo FAIL"
            }
            val r = suSession.execute(cmd, 5)
            if (r.output.trim() == "OK") applied.add(key) else failed.add(key)
        }

        suSession.execute("echo '${applied.size}/${PROP_OVERRIDES.size}' > '$STATE_FILE'", 3)
        Log.d(TAG, "Prop disguise applied ${applied.size}/${PROP_OVERRIDES.size}, failed=${failed.size}")

        return ApplyResult(
            success = applied.size == PROP_OVERRIDES.size,
            appliedKeys = applied,
            failedKeys = failed,
            detail = "已伪装 ${applied.size}/${PROP_OVERRIDES.size} 项 prop" +
                if (failed.isNotEmpty()) "，失败: ${failed.joinToString(",")}" else ""
        )
    }

    fun restore(): Boolean {
        if (!suSession.isSessionOpen() && !suSession.open(15)) return false
        if (suSession.execute("test -f '$BACKUP_FILE' && echo YES || echo NO", 3)
                .output.trim() != "YES") {
            return false
        }
        // 读取备份并恢复原始值
        val r = suSession.execute(
            "while IFS='=' read -r key value; do " +
                "[ -n \"\$key\" ] && resetprop \"\$key\" \"\$value\" 2>/dev/null || " +
                "magisk resetprop \"\$key\" \"\$value\" 2>/dev/null || true; " +
                "done < '$BACKUP_FILE' 2>/dev/null && echo DONE",
            30
        )
        val ok = r.output.trim() == "DONE"
        if (ok) {
            suSession.execute("rm -f '$BACKUP_FILE' '$STATE_FILE' 2>/dev/null", 3)
            Log.d(TAG, "Prop disguise restored")
        }
        return ok
    }

    fun status(): Status {
        if (!suSession.isSessionOpen() && !suSession.open(8)) {
            return Status(DisguiseState.NOT_APPLIED, PROP_OVERRIDES.size, 0, emptyMap())
        }
        if (suSession.execute("test -f '$STATE_FILE' && echo YES || echo NO", 3)
                .output.trim() != "YES") {
            return Status(DisguiseState.NOT_APPLIED, PROP_OVERRIDES.size, 0, emptyMap())
        }
        // 读取当前 prop 值与目标比较
        val checkCmd = StringBuilder().apply {
            for ((key, _) in PROP_OVERRIDES) {
                append("echo '$key='\"$(getprop '$key')\"\n")
            }
        }.toString()
        val r = suSession.execute(checkCmd, 15)
        var appliedCount = 0
        val currentVals = mutableMapOf<String, String>()
        for (line in r.output.lines().map { it.trim() }.filter { it.contains('=') }) {
            val k = line.substringBefore('=').trim()
            val v = line.substringAfter('=').trim()
            currentVals[k] = v
            val expected = PROP_OVERRIDES[k]
            if (expected != null && v == expected) appliedCount++
        }
        val state = when {
            appliedCount == 0 -> DisguiseState.NOT_APPLIED
            appliedCount == PROP_OVERRIDES.size -> DisguiseState.FULL
            else -> DisguiseState.PARTIAL
        }
        return Status(state, PROP_OVERRIDES.size, appliedCount, currentVals)
    }

    /** 列出所有 prop 伪装键值对（给 UI 显示用） */
    fun listOverrides(): List<Pair<String, String>> = PROP_OVERRIDES.toList()
}
