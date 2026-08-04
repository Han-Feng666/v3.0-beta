package com.HanFeng.xposed

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * app_process 入口: 以 root 直接修改 LSPosed 作用域数据库, 让 HanFeng 对全部已安装应用生效。
 * 由 RootLsposedScope 通过 `su -c "CLASSPATH=<apk> app_process /system/bin <this> <pkg> <db> <apkPath>"`
 * 启动。仅依赖 boot classpath (android.database.sqlite / java.io), 不依赖任何 Activity/Context。
 *
 * LSPosed 作用域库结构 (Zygisk 版, /data/adb/lspd/config/modules_config.db):
 * - modules(mid INTEGER PRIMARY KEY AUTOINCREMENT, module_pkg_name TEXT UNIQUE, apk_path TEXT, enabled INTEGER)
 * - scope(mid INTEGER, app_pkg_name TEXT, user_id INTEGER, PRIMARY KEY(mid, app_pkg_name, user_id))
 *   app_pkg_name='system' 表示 system_server 作用域 (master 版); 老版本用 'android'。
 * 该库允许任意进程以 root 写入; LSPosed daemon 重启后会重新读取并更新内存缓存。
 */
object RootDbTool {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("ERR_ARGS")
            return
        }
        val modulePkg = args[0]
        val dbPath = args[1]
        val apkPath = args.getOrNull(2) ?: ""

        try {
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                var mid = queryMid(db, modulePkg)
                if (mid < 0 && apkPath.isNotEmpty()) {
                    // 模块从未在 LSPosed Manager 里启用过: 补注册模块行, 否则 scope 无处挂靠。
                    // apk_path 指向应用 base.apk, LSPosed 重启后能正常解析出模块入口。
                    db.execSQL(
                        "INSERT OR IGNORE INTO modules(module_pkg_name, apk_path, enabled) VALUES(?,?,1)",
                        arrayOf(modulePkg, apkPath)
                    )
                    mid = queryMid(db, modulePkg)
                }
                if (mid < 0) {
                    println("ERR_NO_MODULE")
                    return
                }

                val pkgs = HashSet<String>()
                runCatching {
                    File("/data/system/packages.list").forEachLine { line ->
                        val name = line.substringBefore(' ').trim()
                        if (name.isNotEmpty() && name != modulePkg) pkgs.add(name)
                    }
                }
                // system_server 作用域: master 版用 'system', 老版本用 'android', 一并写入兼容。
                pkgs.add("system")
                pkgs.add("android")

                db.beginTransaction()
                var added = 0
                pkgs.forEach { pkg ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO scope(mid, app_pkg_name, user_id) VALUES(?,?,0)",
                        arrayOf(mid, pkg)
                    )
                    added++
                }
                db.setTransactionSuccessful()
                db.endTransaction()
                println("OK_ADDED=$added")
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            println("ERR:${t.message}")
        }
    }

    private fun queryMid(db: SQLiteDatabase, pkg: String): Long {
        db.rawQuery("SELECT mid FROM modules WHERE module_pkg_name=?", arrayOf(pkg)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return -1L
    }
}
