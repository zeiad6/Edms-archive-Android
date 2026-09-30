package com.zidex.edms.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Local archive database. The schema is the desktop's (src/lib/seed.ts DDL),
 * table for table and column for column, so a desktop backup's `edms.db` opens
 * here as-is and a phone backup restores on the desktop.
 */
class Db private constructor(private val ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    // An imported desktop DB has user_version 0, so onCreate runs on it too —
    // every statement is IF NOT EXISTS, which makes that harmless.
    override fun onCreate(db: SQLiteDatabase) = DDL.forEach(db::execSQL)

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    override fun onOpen(db: SQLiteDatabase) {
        DDL.forEach(db::execSQL)
        addMissingColumns(db)
        seed(db)
    }

    /** Same late columns the desktop adds in ensureSchema(). */
    private fun addMissingColumns(db: SQLiteDatabase) {
        fun cols(t: String) = db.rawQuery("PRAGMA table_info($t)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        val u = cols("users")
        if ("password_hash" !in u) db.execSQL("ALTER TABLE users ADD COLUMN password_hash TEXT")
        if ("must_change_password" !in u) db.execSQL("ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0")
        val d = cols("documents")
        for (c in listOf("keywords", "source", "notes", "deleted_at")) if (c !in d) db.execSQL("ALTER TABLE documents ADD COLUMN $c TEXT")
        if ("deleted_by_id" !in d) db.execSQL("ALTER TABLE documents ADD COLUMN deleted_by_id INTEGER REFERENCES users(id)")
    }

    /**
     * Fresh install: the administrator only (`admin` / 12345678, forced change
     * on first login) plus the desktop's reference lists so the UI is usable.
     */
    private fun seed(db: SQLiteDatabase) {
        if (db.count("SELECT COUNT(*) FROM users") > 0) return
        db.inTx {
            val depts = listOf(
                arrayOf("الإدارة العامة", "GEN", "#4f46e5", "الإدارة العليا والأمانة العامة"),
                arrayOf("الشؤون المالية", "FIN", "#059669", "الميزانية والحسابات والمدفوعات"),
                arrayOf("الموارد البشرية", "HR", "#d97706", "شؤون الموظفين والتوظيف"),
                arrayOf("تقنية المعلومات", "IT", "#0ea5e9", "الأنظمة والبنية التحتية الرقمية"),
                arrayOf("الشؤون القانونية", "LEG", "#be123c", "الاستشارات والمراجعة القانونية"),
                arrayOf("العقود والمشتريات", "PROC", "#7c3aed", "المناقصات وإدارة العقود"),
            ).map { (n, c, col, d) -> insert("departments", cv("name" to n, "code" to c, "color" to col, "description" to d)) }
            insert(
                "users",
                cv(
                    "name" to "Zidex", "username" to "admin", "email" to "admin@edms.local",
                    "password_hash" to Passwords.hash(Passwords.DEFAULT_PASSWORD), "must_change_password" to 1,
                    "job_title" to "المدير العام", "role" to "admin", "department_id" to depts[0], "avatar_color" to "#4f46e5",
                ),
            )
            listOf(
                "المراسلات الصادرة" to 0, "المراسلات الواردة" to 0, "القرارات الإدارية" to 0, "الفواتير والمدفوعات" to 1,
                "ملفات الموظفين" to 2, "تقارير المشاريع" to 3, "الوثائق القانونية" to 4, "العقود" to 5,
            ).forEach { (n, d) -> insert("folders", cv("name" to n, "department_id" to depts[d])) }
            listOf(
                "عاجل" to "#dc2626", "سري" to "#7c3aed", "موقّع" to "#059669",
                "قيد المراجعة" to "#d97706", "مؤرشف" to "#0ea5e9", "نسخة طبق الأصل" to "#64748b",
            ).forEach { (n, c) -> insert("tags", cv("name" to n, "color" to c)) }
            listOf(
                Triple("قرار إداري", "Administrative Decision", "#4f46e5"), Triple("فاتورة", "Invoice", "#059669"),
                Triple("عقد", "Contract", "#7c3aed"), Triple("محضر اجتماع", "Meeting Minutes", "#d97706"),
                Triple("تقرير", "Report", "#0ea5e9"), Triple("مراسلة رسمية", "Official Correspondence", "#dc2626"),
                Triple("نموذج", "Form", "#64748b"), Triple("صورة ضوئية", "Scan", "#0891b2"),
            ).forEachIndexed { i, (n, en, c) -> insert("doc_types", cv("name" to n, "name_en" to en, "color" to c, "sort_order" to i + 1)) }
        }
    }

    companion object {
        const val NAME = "edms.db"

        // Holds applicationContext (see get()), so there is no Activity to leak.
        @Suppress("StaticFieldLeak")
        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db = instance ?: synchronized(this) { instance ?: Db(ctx.applicationContext).also { instance = it } }

        /** Close so the DB files can be swapped (restore); the next get() reopens. */
        fun reset() = synchronized(this) { instance?.close(); instance = null }

        fun files(ctx: Context): List<File> {
            val f = ctx.getDatabasePath(NAME)
            return listOf(f, File(f.path + "-wal"), File(f.path + "-shm"))
        }

        private val DDL = listOf(
            "CREATE TABLE IF NOT EXISTS departments (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_en TEXT, code TEXT, description TEXT, color TEXT NOT NULL DEFAULT '#4f46e5', created_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, username TEXT NOT NULL UNIQUE, email TEXT NOT NULL UNIQUE, password_hash TEXT, must_change_password INTEGER NOT NULL DEFAULT 0, job_title TEXT, role TEXT NOT NULL DEFAULT 'staff' CHECK(role IN ('admin','manager','staff')), department_id INTEGER REFERENCES departments(id), avatar_color TEXT NOT NULL DEFAULT '#4f46e5', active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE TABLE IF NOT EXISTS folders (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, parent_id INTEGER, department_id INTEGER REFERENCES departments(id), description TEXT, created_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX IF NOT EXISTS folders_parent_idx ON folders(parent_id)",
            "CREATE INDEX IF NOT EXISTS folders_dept_idx ON folders(department_id)",
            "CREATE INDEX IF NOT EXISTS users_dept_idx ON users(department_id)",
            "CREATE TABLE IF NOT EXISTS tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, color TEXT NOT NULL DEFAULT '#64748b')",
            "CREATE TABLE IF NOT EXISTS doc_types (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, name_en TEXT, color TEXT NOT NULL DEFAULT '#64748b', sort_order INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX IF NOT EXISTS doc_types_sort_idx ON doc_types(sort_order)",
            "CREATE TABLE IF NOT EXISTS documents (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, description TEXT, doc_number TEXT, doc_type TEXT, doc_type_id INTEGER REFERENCES doc_types(id), status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('draft','pending_review','active','archived')), confidential INTEGER NOT NULL DEFAULT 0, storage_key TEXT NOT NULL, thumb_key TEXT, original_name TEXT NOT NULL, file_name TEXT NOT NULL, mime_type TEXT NOT NULL, file_ext TEXT, file_size INTEGER NOT NULL, page_count INTEGER DEFAULT 1, content_text TEXT, ocr_processed INTEGER NOT NULL DEFAULT 0, department_id INTEGER REFERENCES departments(id), folder_id INTEGER REFERENCES folders(id), version INTEGER NOT NULL DEFAULT 1, doc_date TEXT, uploaded_by_id INTEGER NOT NULL REFERENCES users(id), deleted_at TEXT, deleted_by_id INTEGER REFERENCES users(id), created_at TEXT NOT NULL DEFAULT (datetime('now')), updated_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX IF NOT EXISTS documents_dept_idx ON documents(department_id)",
            "CREATE INDEX IF NOT EXISTS documents_folder_idx ON documents(folder_id)",
            "CREATE INDEX IF NOT EXISTS documents_status_idx ON documents(status)",
            "CREATE INDEX IF NOT EXISTS documents_uploader_idx ON documents(uploaded_by_id)",
            "CREATE INDEX IF NOT EXISTS documents_type_idx ON documents(doc_type)",
            "CREATE INDEX IF NOT EXISTS documents_created_at_idx ON documents(created_at)",
            "CREATE INDEX IF NOT EXISTS documents_updated_at_idx ON documents(updated_at)",
            "CREATE INDEX IF NOT EXISTS documents_deleted_at_idx ON documents(deleted_at)",
            "CREATE INDEX IF NOT EXISTS documents_doc_date_idx ON documents(doc_date)",
            "CREATE INDEX IF NOT EXISTS documents_doc_type_id_idx ON documents(doc_type_id)",
            "CREATE TABLE IF NOT EXISTS document_tags (document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE, tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE, PRIMARY KEY (document_id, tag_id))",
            "CREATE INDEX IF NOT EXISTS doctags_tag_idx ON document_tags(tag_id)",
            "CREATE TABLE IF NOT EXISTS document_versions (id INTEGER PRIMARY KEY AUTOINCREMENT, document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE, version INTEGER NOT NULL, storage_key TEXT NOT NULL, original_name TEXT NOT NULL, file_size INTEGER NOT NULL, note TEXT, uploaded_by_id INTEGER NOT NULL REFERENCES users(id), created_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX IF NOT EXISTS versions_doc_idx ON document_versions(document_id)",
            "CREATE TABLE IF NOT EXISTS audit_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER REFERENCES users(id), user_name TEXT, action TEXT NOT NULL, entity_type TEXT, entity_id INTEGER, details TEXT, created_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX IF NOT EXISTS audit_entity_idx ON audit_logs(entity_type, entity_id)",
            "CREATE INDEX IF NOT EXISTS audit_user_idx ON audit_logs(user_id)",
            "CREATE INDEX IF NOT EXISTS audit_created_at_idx ON audit_logs(created_at)",
            "CREATE TABLE IF NOT EXISTS approval_requests (id INTEGER PRIMARY KEY AUTOINCREMENT, document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE, requested_by_id INTEGER NOT NULL REFERENCES users(id), assigned_to_id INTEGER NOT NULL REFERENCES users(id), status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected')), comment TEXT, response_note TEXT, responded_at TEXT, created_at TEXT NOT NULL DEFAULT (datetime('now')), updated_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX IF NOT EXISTS approval_doc_idx ON approval_requests(document_id)",
            "CREATE INDEX IF NOT EXISTS approval_assigned_idx ON approval_requests(assigned_to_id)",
            "CREATE INDEX IF NOT EXISTS approval_status_idx ON approval_requests(status)",
            "CREATE INDEX IF NOT EXISTS approval_created_at_idx ON approval_requests(created_at)",
            "CREATE TABLE IF NOT EXISTS notifications (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id), type TEXT NOT NULL CHECK(type IN ('approval_requested','approval_approved','approval_rejected')), title TEXT NOT NULL, message TEXT NOT NULL, document_id INTEGER REFERENCES documents(id) ON DELETE SET NULL, reference_id INTEGER, read_at TEXT, created_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX IF NOT EXISTS notif_user_idx ON notifications(user_id)",
            "CREATE INDEX IF NOT EXISTS notif_read_idx ON notifications(user_id, read_at)",
            "CREATE INDEX IF NOT EXISTS notif_created_at_idx ON notifications(created_at)",
            "CREATE TABLE IF NOT EXISTS signatures (id INTEGER PRIMARY KEY AUTOINCREMENT, document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE, user_id INTEGER NOT NULL REFERENCES users(id), label TEXT DEFAULT 'توقيع', data_url TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX IF NOT EXISTS sig_doc_idx ON signatures(document_id)",
            "CREATE INDEX IF NOT EXISTS sig_user_idx ON signatures(user_id)",
            "CREATE TABLE IF NOT EXISTS document_templates (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, description TEXT, title_pattern TEXT NOT NULL DEFAULT '{{title}}', department_id INTEGER REFERENCES departments(id), folder_id INTEGER REFERENCES folders(id), doc_type TEXT, default_tags TEXT, status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('draft','active','archived')), sort_order INTEGER NOT NULL DEFAULT 0, created_by_id INTEGER REFERENCES users(id), created_at TEXT NOT NULL DEFAULT (datetime('now')), updated_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX IF NOT EXISTS templates_status_idx ON document_templates(status)",
            "CREATE INDEX IF NOT EXISTS templates_sort_idx ON document_templates(sort_order)",
        )
    }
}

// ───────────── small SQLite helpers ─────────────

fun cv(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
    for ((k, v) in pairs) when (v) {
        null -> putNull(k)
        is String -> put(k, v)
        is Int -> put(k, v)
        is Long -> put(k, v)
        is Boolean -> put(k, if (v) 1 else 0)
        is Double -> put(k, v)
        else -> put(k, v.toString())
    }
}

fun SQLiteDatabase.insert(table: String, values: ContentValues): Long = insertOrThrow(table, null, values)

fun SQLiteDatabase.count(sql: String, vararg args: Any?): Int =
    rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { if (it.moveToFirst()) it.getInt(0) else 0 }

fun <T> SQLiteDatabase.inTx(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}

fun <T> SQLiteDatabase.list(sql: String, vararg args: Any?, map: (Cursor) -> T): List<T> =
    rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

fun <T> SQLiteDatabase.one(sql: String, vararg args: Any?, map: (Cursor) -> T): T? = list(sql, *args, map = map).firstOrNull()

fun Cursor.s(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
fun Cursor.sn(col: String): String? = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
fun Cursor.i(col: String): Int = getInt(getColumnIndexOrThrow(col))
fun Cursor.inn(col: String): Int? = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let(::getInt)
fun Cursor.l(col: String): Long = getLong(getColumnIndexOrThrow(col))
