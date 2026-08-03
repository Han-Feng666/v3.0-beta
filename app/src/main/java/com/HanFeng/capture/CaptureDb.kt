package com.HanFeng.capture

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 抓包历史持久化 SQLite Schema 与版本管理 (E1.0)。
 *
 * 与广告拦截侧 prefs 不同, 抓包历史规模可达数千条且结构化丰富, 直接走平台内置
 * [SQLiteOpenHelper] 而不引入 Room (项目无 ksp/kapt 工具链, 避免编译验证盲点)。
 *
 * 实施策略:
 * - 表 [TABLE_ENTRIES] 一行一条 entry; txnId 既是内存 ring 的主键也是持久化主键
 *   (重放用 [CaptureEntry.makeReplayTxnId] 的高位 1L<<60 区间避免与正常 txn 冲突),
 * - 表 [TABLE_BODY_CHUNKS] 拆分超 [CHUNK_SIZE] 的 req/resp body 块, 顺序拼回原体,
 *   解 E2 大响应跨 DATA 帧超 32KB 上限问题,
 * - 表 [TABLE_BREAKPOINT_RULES] 镜像现有 [BreakpointRule] 持久化(也可继续用 prefs, 为
 *   统一迁移到 SQL 这里镜像一个 schema_v2 备用)
 *
 * 引用 design correctness 14 / requirements R12。
 */
object CaptureDb {

    const val DB_NAME = "hanfeng_capture.db"
    const val DB_VERSION = 1

    const val TABLE_ENTRIES = "capture_entries"
    const val TABLE_BODY_CHUNKS = "capture_body_chunks"
    const val TABLE_BREAKPOINT_RULES = "capture_breakpoint_rules"

    /** body chunk 单块体积(64KB → 总 body / 大响应跨 DATA 帧32KB 之后用 chunked 存全部)。 */
    const val CHUNK_SIZE = 64 * 1024

    // 表结构 (entries)
    const val COL_TXN_ID = "txn_id"
    const val COL_TIMESTAMP = "timestamp_ms"
    const val COL_APP_NAME = "app_name"
    const val COL_PACKAGE_NAME = "package_name"
    const val COL_SCHEME = "scheme"
    const val COL_METHOD = "method"
    const val COL_HOST = "host"
    const val COL_PATH = "path"
    const val COL_HTTP_VERSION = "http_version"
    const val COL_REQUEST_HEADERS_JSON = "request_headers_json"
    const val COL_REQUEST_CONTENT_TYPE = "request_content_type"
    const val COL_REQUEST_BODY_TRUNCATED = "request_body_truncated"
    const val COL_RESPONSE_STATUS = "response_status"
    const val COL_RESPONSE_HEADERS_JSON = "response_headers_json"
    const val COL_RESPONSE_CONTENT_TYPE = "response_content_type"
    const val COL_RESPONSE_BODY_TRUNCATED = "response_body_truncated"
    const val COL_DURATION_MS = "duration_ms"
    const val COL_ERROR = "error"
    const val COL_INTERCEPTED = "intercepted"
    const val COL_TLS_META_JSON = "tls_meta_json"
    const val COL_REPLAYED = "replayed"
    const val COL_SESSION_ID = "session_id"

    // body_chunks 表
    const val COL_CHUNK_ENTRY_ID = "entry_id"
    const val COL_CHUNK_DIRECTION = "direction"
    const val COL_CHUNK_ORDINAL = "ordinal"
    const val COL_CHUNK_BLOB = "blob"

    // 断点规则镜像表 (备用)
    const val COL_RULE_ID = "id"
    const val COL_RULE_HOST = "host"
    const val COL_RULE_METHOD = "method"
    const val COL_RULE_PATH = "path"
    const val COL_RULE_KIND = "kind"

    private const val SQL_CREATE_ENTRIES = """
        CREATE TABLE IF NOT EXISTS $TABLE_ENTRIES (
            $COL_TXN_ID INTEGER PRIMARY KEY,
            $COL_TIMESTAMP INTEGER NOT NULL,
            $COL_APP_NAME TEXT,
            $COL_PACKAGE_NAME TEXT,
            $COL_SCHEME TEXT NOT NULL,
            $COL_METHOD TEXT NOT NULL,
            $COL_HOST TEXT NOT NULL,
            $COL_PATH TEXT NOT NULL,
            $COL_HTTP_VERSION TEXT NOT NULL,
            $COL_REQUEST_HEADERS_JSON TEXT,
            $COL_REQUEST_CONTENT_TYPE TEXT,
            $COL_REQUEST_BODY_TRUNCATED INTEGER NOT NULL DEFAULT 0,
            $COL_RESPONSE_STATUS INTEGER NOT NULL DEFAULT 0,
            $COL_RESPONSE_HEADERS_JSON TEXT,
            $COL_RESPONSE_CONTENT_TYPE TEXT,
            $COL_RESPONSE_BODY_TRUNCATED INTEGER NOT NULL DEFAULT 0,
            $COL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
            $COL_ERROR TEXT,
            $COL_INTERCEPTED INTEGER NOT NULL DEFAULT 0,
            $COL_TLS_META_JSON TEXT,
            $COL_REPLAYED INTEGER NOT NULL DEFAULT 0,
            $COL_SESSION_ID TEXT NOT NULL DEFAULT ''
        )
    """

    private const val SQL_CREATE_BODY_CHUNKS = """
        CREATE TABLE IF NOT EXISTS $TABLE_BODY_CHUNKS (
            $COL_CHUNK_ENTRY_ID INTEGER NOT NULL,
            $COL_CHUNK_DIRECTION INTEGER NOT NULL,
            $COL_CHUNK_ORDINAL INTEGER NOT NULL,
            $COL_CHUNK_BLOB BLOB NOT NULL,
            PRIMARY KEY ($COL_CHUNK_ENTRY_ID, $COL_CHUNK_DIRECTION, $COL_CHUNK_ORDINAL)
        )
    """

    private const val SQL_CREATE_BREAKPOINT_RULES = """
        CREATE TABLE IF NOT EXISTS $TABLE_BREAKPOINT_RULES (
            $COL_RULE_ID TEXT PRIMARY KEY,
            $COL_RULE_HOST TEXT NOT NULL,
            $COL_RULE_METHOD TEXT,
            $COL_RULE_PATH TEXT,
            $COL_RULE_KIND TEXT NOT NULL
        )
    """

    private const val SQL_INDEX_ENTRIES_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_entries_timestamp ON $TABLE_ENTRIES($COL_TIMESTAMP)"

    private const val SQL_INDEX_ENTRIES_HOST =
        "CREATE INDEX IF NOT EXISTS idx_entries_host ON $TABLE_ENTRIES($COL_HOST)"

    private const val SQL_INDEX_ENTRIES_SESSION =
        "CREATE INDEX IF NOT EXISTS idx_entries_session ON $TABLE_ENTRIES($COL_SESSION_ID)"

    private const val SQL_INDEX_BODY_CHUNKS_ENTRY =
        "CREATE INDEX IF NOT EXISTS idx_body_chunks_entry ON $TABLE_BODY_CHUNKS($COL_CHUNK_ENTRY_ID)"

    class Helper(context: Context) : SQLiteOpenHelper(
        context.applicationContext,
        DB_NAME,
        /* factory = */ null,
        DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(SQL_CREATE_ENTRIES)
            db.execSQL(SQL_CREATE_BODY_CHUNKS)
            db.execSQL(SQL_CREATE_BREAKPOINT_RULES)
            db.execSQL(SQL_INDEX_ENTRIES_TIMESTAMP)
            db.execSQL(SQL_INDEX_ENTRIES_HOST)
            db.execSQL(SQL_INDEX_ENTRIES_SESSION)
            db.execSQL(SQL_INDEX_BODY_CHUNKS_ENTRY)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 初始版本; 未来版本在此加 ALTER / 迁移
            if (oldVersion < 1) {
                // Drop & recreate (历史数据可丢, ring buffer 仍是数据源直到迁移稳定)
                db.execSQL("DROP TABLE IF EXISTS $TABLE_BODY_CHUNKS")
                db.execSQL("DROP TABLE IF EXISTS $TABLE_ENTRIES")
                db.execSQL("DROP TABLE IF EXISTS $TABLE_BREAKPOINT_RULES")
                onCreate(db)
            }
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }
    }
}

/** body chunk 方向: 0=请求, 1=响应。 */
object CaptureChunkDirection {
    const val REQUEST = 0
    const val RESPONSE = 1
}
