package com.HanFeng.xposed

import android.content.Context
import com.HanFeng.adblocker.shizuku.SuSession

/**
 * root 一键"全局生效": 把 HanFeng 的 LSPosed 作用域扩展到所有已安装应用 + system_server。
 *
 * 原理: LSPosed 的模块作用域存储在 /data/adb/lspd/config/modules_config.db 的 scope 表,
 * 有 root 时直接写入即可, 无需在 LSPosed Manager 里逐个勾选目标 App。
 *
 * 生效前提: LSPosed(Zygisk 版) 已安装且已激活 HanFeng 模块; 本机有 root。
 * 改库后必须触发一次 framework 软重启 (reboot,soft), LSPosed daemon 才会重新读取
 * scope 数据库并更新内存缓存 —— 软重启会重启整个 framework (含 lspd daemon),
 * 所有 App 进程随之重建并从新作用域加载 HanFeng 模块。
 */
object RootLsposedScope {

    private const val TAG = "RootLsposedScope"
    private const val DB_PATH = "/data/adb/lspd/config/modules_config.db"

    sealed class Result {
        data class Success(val added: Int) : Result()
        data class Failure(val reason: String) : Result()
    }

    fun applyGlobalScope(context: Context): Result {
        return runCatching {
            val su = SuSession.getInstance()
            if (!su.open(8)) return Result.Failure("未获得 root 权限")

            if (!su.fileExists(DB_PATH)) {
                return Result.Failure("未找到 LSPosed 配置($DB_PATH)。请确认已安装并激活 LSPosed(Zygisk 版)")
            }

            // 备份: 出错时可手动恢复
            su.execute("cp -f '$DB_PATH' '$DB_PATH.bak' 2>/dev/null", 10)

            val apkPath = context.applicationInfo.sourceDir
            val cmd = "CLASSPATH=$apkPath app_process /system/bin " +
                "com.HanFeng.xposed.RootDbTool ${context.packageName} $DB_PATH $apkPath"
            val r = su.execute(cmd, 30)
            val out = r.output.trim()

            if (out.startsWith("OK_ADDED=")) {
                val n = out.removePrefix("OK_ADDED=").toIntOrNull() ?: 0
                android.util.Log.i(TAG, "global scope applied: $n apps")
                Result.Success(n)
            } else {
                android.util.Log.w(TAG, "applyGlobalScope failed: $out")
                Result.Failure("写入作用域失败: ${out.take(200)}")
            }
        }.getOrElse { t ->
            android.util.Log.e(TAG, "applyGlobalScope exception", t)
            Result.Failure(t.message ?: "未知错误")
        }
    }

    /**
     * framework 软重启: 重启 system_server/zygote/所有进程(含 lspd daemon), 使新作用域完整生效。
     * 比整机重启快, 不进 bootloader; 当前 App 也会随之重启。
     */
    fun softReboot(): Boolean {
        return runCatching {
            val su = SuSession.getInstance()
            if (!su.open(8)) return false
            val r = su.execute("setprop sys.powerctl reboot,soft", 10)
            r.exitCode == 0
        }.getOrDefault(false)
    }
}
