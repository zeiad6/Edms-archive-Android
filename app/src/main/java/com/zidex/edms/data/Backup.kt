package com.zidex.edms.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.zidex.edms.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup / restore in the desktop's ZIP layout
 * (`manifest.json`, `database/edms.db*`, `storage/...`). A desktop backup
 * restores on the phone and a phone backup restores on the desktop
 * (Settings → Backup → Restore).
 */
object Backup {

    fun export(ctx: Context, u: User, out: OutputStream) {
        if (!Perm.can(u, "settings.manage")) throw UserError("النسخ الاحتياطي للمدير فقط")
        val db = Db.get(ctx).writableDatabase
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        val parts = JSONArray()
        ZipOutputStream(out.buffered(65536)).use { zip ->
            fun add(file: File, name: String) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().buffered(65536).use { it.copyTo(zip, bufferSize = 65536) }
                zip.closeEntry()
            }
            val dbFiles = Db.files(ctx).filter { it.exists() }
            dbFiles.forEach { add(it, "database/${it.name}") }
            parts.put(part("database", "قاعدة البيانات", dbFiles))
            val root = Storage.root(ctx)
            val stored = root.walkTopDown().filter { it.isFile }.toList()
            stored.forEach { add(it, "storage/" + it.relativeTo(root).invariantSeparatorsPath) }
            if (stored.isNotEmpty()) parts.put(part("storage", "ملفات التخزين", stored))
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                JSONObject().put("app", "edms-archive").put("version", BuildConfig.VERSION_NAME)
                    .put("created", Instant.now().toString()).put("source", "android").put("parts", parts)
                    .toString(2).toByteArray(),
            )
            zip.closeEntry()
        }
        Repo.get(ctx).audit(u, "backup.export", null, null, "تصدير نسخة احتياطية كاملة")
    }

    private fun part(id: String, name: String, files: List<File>) =
        JSONObject().put("id", id).put("name", name).put("size", files.sumOf { it.length() }).put("count", files.size)

    /**
     * Replace the whole local archive with a backup. Extracts to a staging
     * folder and validates the database before touching live data; the
     * caller must sign the user out afterwards.
     */
    fun restore(ctx: Context, u: User, input: InputStream) {
        if (!Perm.can(u, "settings.manage")) throw UserError("الاستعادة للمدير فقط")
        val stage = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            var manifestOk = false
            ZipInputStream(input.buffered(65536)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    if (e.isDirectory) continue
                    if (name == "manifest.json") {
                        manifestOk = runCatching { JSONObject(zip.readBytes().decodeToString()).optString("app") == "edms-archive" }.getOrDefault(false)
                        continue
                    }
                    if (!name.startsWith("database/") && !name.startsWith("storage/")) continue // scripts/, config/ are desktop-only
                    val dest = File(stage, name).canonicalFile
                    if (!dest.path.startsWith(stage.canonicalPath + File.separator)) continue // zip-slip guard
                    dest.parentFile?.mkdirs()
                    dest.outputStream().buffered(65536).use { zip.copyTo(it, bufferSize = 65536) }
                }
            }
            if (!manifestOk) throw UserError("الملف ليس نسخة احتياطية من نظام الأرشفة")
            val newDb = File(stage, "database/${Db.NAME}")
            if (!newDb.exists()) throw UserError("النسخة لا تحتوي قاعدة البيانات")
            SQLiteDatabase.openDatabase(newDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { check ->
                if (check.count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('users','documents')") < 2) {
                    throw UserError("قاعدة البيانات في النسخة غير صالحة")
                }
                check.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }

            Db.reset()
            Db.files(ctx).forEach { it.delete() }
            val target = ctx.getDatabasePath(Db.NAME).apply { parentFile?.mkdirs() }
            newDb.copyTo(target, overwrite = true)
            val newStorage = File(stage, "storage")
            val root = Storage.root(ctx)
            root.deleteRecursively()
            if (newStorage.exists()) newStorage.copyRecursively(root, overwrite = true) else root.mkdirs()
            Repo.get(ctx).audit(null, "backup.restore", null, null, "استعادة نسخة احتياطية على الهاتف بواسطة ${u.name}")
        } finally {
            stage.deleteRecursively()
        }
    }
}
