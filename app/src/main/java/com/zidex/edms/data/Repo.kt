package com.zidex.edms.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import java.time.Instant

/** Thrown for anything the user should read (Arabic message). */
class UserError(message: String) : Exception(message)

data class DocMeta(
    val title: String, val description: String? = null, val docNumber: String? = null, val docType: String? = null,
    val status: String = "active", val confidential: Boolean = false, val departmentId: Int? = null,
    val folderId: Int? = null, val docDate: String? = null, val keywords: String? = null, val source: String? = null,
    val notes: String? = null, val tagIds: Set<Int> = emptySet(),
)

data class DocFilter(
    val query: String = "", val status: String? = null, val departmentId: Int? = null, val docType: String? = null,
    val tagId: Int? = null, val folderId: Int? = null,
)

data class Stats(
    val total: Int, val byStatus: Map<String, Int>, val byDepartment: List<Pair<String, Int>>, val byType: List<Pair<String, Int>>, val typeSizes: Map<String, Long>,
    val byMonth: List<Pair<String, Int>>, val totalSize: Long, val pendingApprovals: Int, val users: Int, val recent: List<Document>,
)

/**
 * Every read and write of the local archive. Blocking — call from
 * Dispatchers.IO. Business rules mirror the desktop server actions
 * (permissions, audit actions, approval flow, trash).
 */
class Repo private constructor(private val ctx: Context) {
    private val db: SQLiteDatabase get() = Db.get(ctx).writableDatabase

    companion object {
        // Holds applicationContext (see get()), so there is no Activity to leak.
        @Suppress("StaticFieldLeak")
        @Volatile private var instance: Repo? = null
        fun get(ctx: Context): Repo = instance ?: synchronized(this) { instance ?: Repo(ctx.applicationContext).also { instance = it } }
    }
    private fun now() = Instant.now().toString()

    // ───────────── auth ─────────────

    private val failures = mutableMapOf<String, Pair<Int, Long>>()

    fun login(username: String, password: String): User {
        val key = username.trim().lowercase()
        failures[key]?.let { (n, until) -> if (n >= 5 && System.currentTimeMillis() < until) throw UserError("محاولات كثيرة — انتظر دقيقة ثم أعد المحاولة") }
        val u = db.one("SELECT * FROM users WHERE lower(username) = ? OR lower(email) = ?", key, key, map = { c -> User.of(c) })
        if (u == null || !Passwords.verify(Passwords.sanitize(password), u.passwordHash)) {
            val n = (failures[key]?.first ?: 0) + 1
            failures[key] = n to System.currentTimeMillis() + 60_000
            throw UserError("اسم المستخدم أو كلمة المرور غير صحيحة")
        }
        failures.remove(key)
        if (!u.active) throw UserError("هذا الحساب معطّل. تواصل مع مدير النظام.")
        audit(u, "auth.login", "user", u.id, "تسجيل الدخول إلى النظام (الهاتف)")
        return u
    }

    fun user(id: Int) = db.one("SELECT * FROM users WHERE id = ?", id, map = { c -> User.of(c) })

    fun changePassword(u: User, current: String?, new: String): User {
        val pw = Passwords.sanitize(new)
        if (pw.length < 8) throw UserError("كلمة المرور يجب أن تكون 8 أحرف على الأقل")
        if (pw == Passwords.DEFAULT_PASSWORD) throw UserError("اختر كلمة مرور غير الكلمة الافتراضية")
        val fresh = user(u.id) ?: throw UserError("المستخدم غير موجود")
        if (current != null && !Passwords.verify(Passwords.sanitize(current), fresh.passwordHash)) throw UserError("كلمة المرور الحالية غير صحيحة")
        db.update("users", cv("password_hash" to Passwords.hash(pw), "must_change_password" to 0), "id = ?", arrayOf("${u.id}"))
        audit(u, "user.change_password", "user", u.id, "تغيير كلمة المرور")
        return user(u.id)!!
    }

    // ───────────── lookups ─────────────

    fun departments() = db.list("SELECT * FROM departments ORDER BY id", map = { c -> Department.of(c) })
    fun folders() = db.list("SELECT * FROM folders ORDER BY name", map = { c -> Folder.of(c) })
    fun tags() = db.list("SELECT * FROM tags ORDER BY name", map = { c -> Tag.of(c) })
    fun docTypes() = db.list("SELECT * FROM doc_types ORDER BY sort_order, name", map = { c -> DocType.of(c) })
    fun users() = db.list("SELECT * FROM users ORDER BY name", map = { c -> User.of(c) })
    fun approvers() = db.list("SELECT * FROM users WHERE role != 'staff' AND active = 1 ORDER BY name", map = { c -> User.of(c) })
    fun templates() = db.list("SELECT * FROM document_templates ORDER BY sort_order, name", map = { c -> Template.of(c) })

    // ───────────── documents ─────────────

    fun documents(u: User, f: DocFilter = DocFilter(), deleted: Boolean = false): List<Document> {
        val where = mutableListOf(if (deleted) "d.deleted_at IS NOT NULL" else "d.deleted_at IS NULL")
        val args = mutableListOf<Any?>()
        if (!Perm.can(u, "documents.read_all")) {
            where += "(d.department_id = ? OR d.uploaded_by_id = ?)"; args += u.departmentId ?: -1; args += u.id
        }
        if (u.role == "staff") where += "d.confidential = 0"
        f.status?.let { where += "d.status = ?"; args += it }
        f.departmentId?.let { where += "d.department_id = ?"; args += it }
        f.folderId?.let { where += "d.folder_id = ?"; args += it }
        f.docType?.let { where += "d.doc_type = ?"; args += it }
        f.tagId?.let { where += "EXISTS (SELECT 1 FROM document_tags t WHERE t.document_id = d.id AND t.tag_id = ?)"; args += it }
        val rows = db.list("SELECT d.* FROM documents d WHERE ${where.joinToString(" AND ")} ORDER BY d.updated_at DESC", *args.toTypedArray(), map = { c -> Document.of(c) })
        val terms = Arabic.terms(f.query)
        if (terms.isEmpty()) return rows
        return rows.filter { d ->
            val hay = Arabic.normalize(listOfNotNull(d.title, d.docNumber, d.description, d.keywords, d.originalName, d.contentText, d.source, d.notes, d.docType).joinToString(" "))
            terms.all { it in hay }
        }
    }

    fun document(u: User, id: Int): Document {
        val d = db.one("SELECT * FROM documents WHERE id = ?", id, map = { c -> Document.of(c) }) ?: throw UserError("المستند غير موجود")
        if (!Perm.canAccess(u, d)) throw UserError("هذا المستند مقيد أو سري ولا يحق لمستخدمك الحالي عرضه.")
        return d
    }

    fun documentTags(id: Int) = db.list("SELECT t.* FROM tags t JOIN document_tags dt ON dt.tag_id = t.id WHERE dt.document_id = ? ORDER BY t.name", id, map = { c -> Tag.of(c) })

    private fun docValues(m: DocMeta) = cv(
        "title" to m.title.trim(), "description" to m.description?.trim()?.ifBlank { null }, "doc_number" to m.docNumber?.trim()?.ifBlank { null },
        "doc_type" to m.docType, "doc_type_id" to m.docType?.let { t -> db.one("SELECT id FROM doc_types WHERE name = ?", t) { it.getInt(0) } },
        "status" to m.status, "confidential" to m.confidential, "department_id" to m.departmentId, "folder_id" to m.folderId,
        "doc_date" to m.docDate?.ifBlank { null }, "keywords" to m.keywords?.ifBlank { null }, "source" to m.source?.ifBlank { null },
        "notes" to m.notes?.ifBlank { null }, "updated_at" to now(),
    )

    private fun setTags(docId: Int, tagIds: Set<Int>) {
        db.delete("document_tags", "document_id = ?", arrayOf("$docId"))
        tagIds.forEach { db.insert("document_tags", cv("document_id" to docId, "tag_id" to it)) }
    }

    fun createDocument(u: User, s: Storage.Stored, m: DocMeta, scanned: Boolean = false): Int {
        if (m.title.isBlank()) throw UserError("العنوان مطلوب")
        return db.inTx {
            val id = insert("documents", docValues(m).apply {
                put("storage_key", s.key); put("thumb_key", s.thumbKey); put("original_name", s.originalName)
                put("file_name", s.key.substringAfterLast('/')); put("mime_type", s.mime); put("file_ext", s.ext)
                put("file_size", s.size); put("page_count", s.pages); put("uploaded_by_id", u.id); put("created_at", now())
            }).toInt()
            insert("document_versions", cv(
                "document_id" to id, "version" to 1, "storage_key" to s.key, "original_name" to s.originalName,
                "file_size" to s.size, "note" to "الإصدار الأول", "uploaded_by_id" to u.id, "created_at" to now(),
            ))
            setTags(id, m.tagIds)
            audit(u, if (scanned) "document.scan" else "document.upload", "document", id, "إيداع المستند: ${m.title}")
            id
        }
    }

    fun updateDocument(u: User, id: Int, m: DocMeta) {
        val d = document(u, id)
        if (!Perm.canEdit(u, d)) throw UserError("ليس لديك صلاحية تعديل هذا المستند")
        if (m.title.isBlank()) throw UserError("العنوان مطلوب")
        // Like the desktop: only admins change status / confidentiality / department.
        val meta = if (Perm.can(u, "documents.update_all")) m else m.copy(status = d.status, confidential = d.confidential, departmentId = d.departmentId)
        db.inTx {
            update("documents", docValues(meta), "id = ?", arrayOf("$id"))
            setTags(id, meta.tagIds)
            audit(u, "document.update", "document", id, "تعديل بيانات المستند: ${meta.title}")
        }
    }

    fun addVersion(u: User, id: Int, s: Storage.Stored, note: String) {
        val d = document(u, id)
        if (!Perm.canEdit(u, d)) throw UserError("ليس لديك صلاحية تعديل هذا المستند")
        val v = d.version + 1
        db.inTx {
            insert("document_versions", cv(
                "document_id" to id, "version" to v, "storage_key" to s.key, "original_name" to s.originalName,
                "file_size" to s.size, "note" to note.ifBlank { null }, "uploaded_by_id" to u.id, "created_at" to now(),
            ))
            update("documents", cv(
                "storage_key" to s.key, "thumb_key" to s.thumbKey, "original_name" to s.originalName, "file_name" to s.key.substringAfterLast('/'),
                "mime_type" to s.mime, "file_ext" to s.ext, "file_size" to s.size, "page_count" to s.pages, "version" to v, "updated_at" to now(),
            ), "id = ?", arrayOf("$id"))
            audit(u, "document.version", "document", id, "رفع الإصدار $v")
        }
    }

    fun versions(id: Int) = db.list(
        "SELECT v.*, COALESCE(u.name, '') AS uname FROM document_versions v LEFT JOIN users u ON u.id = v.uploaded_by_id WHERE v.document_id = ? ORDER BY v.version DESC", id,
    ) { c -> Version(c.i("id"), c.i("version"), c.s("storage_key"), c.s("original_name"), c.l("file_size"), c.sn("note"), c.s("uname"), c.s("created_at")) }

    fun logView(u: User, d: Document) = audit(u, "document.view", "document", d.id, "عرض مستند: ${d.title}")

    fun softDelete(u: User, id: Int) {
        val d = document(u, id)
        if (!Perm.can(u, "documents.delete") && d.uploadedById != u.id) throw UserError("ليس لديك صلاحية حذف المستند")
        db.update("documents", cv("deleted_at" to now(), "deleted_by_id" to u.id), "id = ?", arrayOf("$id"))
        audit(u, "document.soft_delete", "document", id, "نقل إلى سلة المحذوفات: ${d.title}")
    }

    fun restore(u: User, id: Int) {
        if (!Perm.can(u, "documents.restore")) throw UserError("ليس لديك صلاحية الاستعادة")
        db.update("documents", cv("deleted_at" to null, "deleted_by_id" to null), "id = ?", arrayOf("$id"))
        audit(u, "document.restore", "document", id, "استعادة مستند من السلة")
    }

    fun purge(u: User, id: Int) {
        if (!Perm.can(u, "documents.force_delete")) throw UserError("الحذف النهائي للمدير فقط")
        val d = db.one("SELECT * FROM documents WHERE id = ?", id, map = { c -> Document.of(c) }) ?: return
        val keys = versions(id).map { it.storageKey } + d.storageKey + listOfNotNull(d.thumbKey)
        db.delete("documents", "id = ?", arrayOf("$id"))
        keys.distinct().forEach { Storage.delete(ctx, it) }
        audit(u, "document.force_delete", "document", id, "حذف نهائي: ${d.title}")
    }

    fun emptyTrash(u: User) {
        documents(u, deleted = true).forEach { purge(u, it.id) }
        audit(u, "trash.empty", "document", null, "إفراغ سلة المحذوفات")
    }

    // ───────────── approvals & notifications ─────────────

    private val approvalSql = """
        SELECT a.*, d.title AS doc_title, r.name AS req_name, s.name AS asg_name FROM approval_requests a
        JOIN documents d ON d.id = a.document_id LEFT JOIN users r ON r.id = a.requested_by_id LEFT JOIN users s ON s.id = a.assigned_to_id
    """

    private fun approvalOf(c: android.database.Cursor) = Approval(
        c.i("id"), c.i("document_id"), c.s("doc_title"), c.i("requested_by_id"), c.sn("req_name") ?: "", c.i("assigned_to_id"),
        c.sn("asg_name") ?: "", c.s("status"), c.sn("comment"), c.sn("response_note"), c.sn("responded_at"), c.s("created_at"),
    )

    fun approvals(u: User, mine: Boolean): List<Approval> = if (mine) db.list("$approvalSql WHERE a.requested_by_id = ? ORDER BY a.created_at DESC", u.id, map = { c -> approvalOf(c) })
    else if (u.role == "admin") db.list("$approvalSql ORDER BY a.status = 'pending' DESC, a.created_at DESC", map = { c -> approvalOf(c) })
    else db.list("$approvalSql WHERE a.assigned_to_id = ? ORDER BY a.status = 'pending' DESC, a.created_at DESC", u.id, map = { c -> approvalOf(c) })

    fun documentApprovals(id: Int) = db.list("$approvalSql WHERE a.document_id = ? ORDER BY a.created_at DESC", id, map = { c -> approvalOf(c) })

    fun submitApproval(u: User, docId: Int, assigneeId: Int, comment: String) {
        val d = document(u, docId)
        val a = user(assigneeId) ?: throw UserError("المستخدم المسؤول عن الموافقة غير موجود")
        if (a.role == "staff") throw UserError("المستخدم غير مؤهل للموافقة")
        if (db.count("SELECT COUNT(*) FROM approval_requests WHERE document_id = ? AND status = 'pending'", docId) > 0) {
            throw UserError("يوجد طلب موافقة معلق لهذا المستند بالفعل")
        }
        db.inTx {
            insert("approval_requests", cv(
                "document_id" to docId, "requested_by_id" to u.id, "assigned_to_id" to assigneeId,
                "comment" to comment.ifBlank { "يرجى الموافقة على هذا المستند" }, "created_at" to now(), "updated_at" to now(),
            ))
            audit(u, "approval.submit", "document", docId, "إرسال المستند للموافقة")
            notify(assigneeId, "approval_requested", "طلب موافقة جديد", "طلب منك ${u.name} الموافقة على المستند: ${d.title}", docId)
        }
    }

    fun respond(u: User, requestId: Int, approve: Boolean, note: String) {
        if (!Perm.can(u, "approvals.manage")) throw UserError("ليس لديك صلاحية لهذه العملية")
        val a = db.one("$approvalSql WHERE a.id = ?", requestId, map = { c -> approvalOf(c) }) ?: throw UserError("الطلب غير موجود")
        if (u.role != "admin" && a.assignedToId != u.id) throw UserError("هذا الطلب ليس مسنداً إليك")
        db.inTx {
            // compare-and-swap on status, like the desktop, so a request is answered once
            val n = update("approval_requests", cv(
                "status" to if (approve) "approved" else "rejected", "response_note" to note.ifBlank { null },
                "responded_at" to now(), "updated_at" to now(),
            ), "id = ? AND status = 'pending'", arrayOf("$requestId"))
            if (n == 0) throw UserError("تمت المعالجة بالفعل")
            if (approve) execSQL("UPDATE documents SET status = 'active', updated_at = ? WHERE id = ? AND status = 'pending_review'", arrayOf(now(), a.documentId))
            audit(u, if (approve) "approval.approve" else "approval.reject", "document", a.documentId,
                (if (approve) "تمت الموافقة على المستند" else "تم رفض المستند") + (if (note.isNotBlank()) " مع ملاحظة: ${note.take(100)}" else ""))
            notify(
                a.requestedById, if (approve) "approval_approved" else "approval_rejected",
                if (approve) "تمت الموافقة على المستند" else "تم رفض المستند",
                "${if (approve) "وافق" else "رفض"} ${u.name} ${if (approve) "على" else ""} المستند: ${a.docTitle}".replace("  ", " "), a.documentId,
            )
        }
    }

    private fun notify(userId: Int, type: String, title: String, message: String, docId: Int?) {
        db.insert("notifications", cv("user_id" to userId, "type" to type, "title" to title, "message" to message, "document_id" to docId, "created_at" to now()))
    }

    fun notices(u: User) = db.list("SELECT * FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT 200", u.id) { c ->
        Notice(c.i("id"), c.s("type"), c.s("title"), c.s("message"), c.inn("document_id"), c.sn("read_at") != null, c.s("created_at"))
    }

    fun unreadCount(u: User) = db.count("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", u.id)
    fun markAllRead(u: User) = db.execSQL("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL", arrayOf(now(), u.id))

    // ───────────── signatures ─────────────

    fun signatures(docId: Int) = db.list(
        "SELECT s.*, COALESCE(u.name,'') AS uname FROM signatures s LEFT JOIN users u ON u.id = s.user_id WHERE s.document_id = ? ORDER BY s.created_at DESC", docId,
    ) { c -> Signature(c.i("id"), c.s("uname"), c.sn("label") ?: "توقيع", c.s("data_url"), c.s("created_at")) }

    fun addSignature(u: User, docId: Int, label: String, pngDataUrl: String) {
        document(u, docId)
        db.insert("signatures", cv("document_id" to docId, "user_id" to u.id, "label" to label.ifBlank { "توقيع" }, "data_url" to pngDataUrl, "created_at" to now()))
        audit(u, "signature.create", "document", docId, "إضافة توقيع: ${label.ifBlank { "توقيع" }}")
    }

    // ───────────── reference data (admin) ─────────────

    private fun guard(u: User, perm: String) { if (!Perm.can(u, perm)) throw UserError("ليس لديك صلاحية لهذه العملية") }

    private inline fun <T> unique(block: () -> T): T = try { block() } catch (_: SQLiteConstraintException) { throw UserError("الاسم مستخدم مسبقاً أو مرتبط ببيانات أخرى") }

    fun saveDepartment(u: User, id: Int?, name: String, code: String, color: String, description: String) = unique {
        guard(u, "departments.manage")
        if (name.isBlank()) throw UserError("الاسم مطلوب")
        val v = cv("name" to name.trim(), "code" to code.trim().ifBlank { null }, "color" to color, "description" to description.ifBlank { null })
        if (id == null) audit(u, "department.create", "department", db.insert("departments", v).toInt(), "إنشاء قسم: $name")
        else { db.update("departments", v, "id = ?", arrayOf("$id")); audit(u, "department.update", "department", id, "تعديل قسم: $name") }
    }

    fun deleteDepartment(u: User, id: Int) = unique {
        guard(u, "departments.manage")
        if (db.count("SELECT (SELECT COUNT(*) FROM documents WHERE department_id = ?) + (SELECT COUNT(*) FROM users WHERE department_id = ?)", id, id) > 0) {
            throw UserError("لا يمكن حذف قسم مرتبط بمستندات أو مستخدمين")
        }
        db.execSQL("UPDATE folders SET department_id = NULL WHERE department_id = ?", arrayOf(id))
        db.delete("departments", "id = ?", arrayOf("$id"))
        audit(u, "department.delete", "department", id, "حذف قسم")
    }

    fun saveFolder(u: User, id: Int?, name: String, departmentId: Int?, parentId: Int?, description: String) = unique {
        guard(u, "folders.manage")
        if (name.isBlank()) throw UserError("الاسم مطلوب")
        val v = cv("name" to name.trim(), "department_id" to departmentId, "parent_id" to parentId, "description" to description.ifBlank { null })
        if (id == null) audit(u, "folder.create", "folder", db.insert("folders", v).toInt(), "إنشاء مجلد: $name")
        else { db.update("folders", v, "id = ?", arrayOf("$id")); audit(u, "folder.update", "folder", id, "تعديل مجلد: $name") }
    }

    fun deleteFolder(u: User, id: Int) = unique {
        guard(u, "folders.manage")
        db.inTx {
            execSQL("UPDATE documents SET folder_id = NULL WHERE folder_id = ?", arrayOf(id))
            execSQL("UPDATE folders SET parent_id = NULL WHERE parent_id = ?", arrayOf(id))
            execSQL("UPDATE document_templates SET folder_id = NULL WHERE folder_id = ?", arrayOf(id))
            delete("folders", "id = ?", arrayOf("$id"))
        }
        audit(u, "folder.delete", "folder", id, "حذف مجلد")
    }

    fun saveTag(u: User, id: Int?, name: String, color: String) = unique {
        guard(u, "tags.manage")
        if (name.isBlank()) throw UserError("الاسم مطلوب")
        val v = cv("name" to name.trim(), "color" to color)
        if (id == null) audit(u, "tag.create", "tag", db.insert("tags", v).toInt(), "إنشاء وسم: $name")
        else { db.update("tags", v, "id = ?", arrayOf("$id")); audit(u, "tag.update", "tag", id, "تعديل وسم: $name") }
    }

    fun deleteTag(u: User, id: Int) = unique {
        guard(u, "tags.manage")
        db.delete("tags", "id = ?", arrayOf("$id"))
        audit(u, "tag.delete", "tag", id, "حذف وسم")
    }

    fun saveDocType(u: User, id: Int?, name: String, nameEn: String, color: String, sortOrder: Int) = unique {
        guard(u, "doc-types.manage")
        if (name.isBlank()) throw UserError("الاسم مطلوب")
        val v = cv("name" to name.trim(), "name_en" to nameEn.ifBlank { null }, "color" to color, "sort_order" to sortOrder)
        if (id == null) audit(u, "doctype.create", "doc_type", db.insert("doc_types", v).toInt(), "إنشاء نوع: $name")
        else db.inTx {
            val old = one("SELECT name FROM doc_types WHERE id = ?", id) { it.getString(0) }
            update("doc_types", v, "id = ?", arrayOf("$id"))
            if (old != null && old != name.trim()) execSQL("UPDATE documents SET doc_type = ? WHERE doc_type_id = ?", arrayOf(name.trim(), id))
            audit(u, "doctype.update", "doc_type", id, "تعديل نوع: $name")
        }
    }

    fun deleteDocType(u: User, id: Int) = unique {
        guard(u, "doc-types.manage")
        if (db.count("SELECT COUNT(*) FROM documents WHERE doc_type_id = ?", id) > 0) throw UserError("لا يمكن حذف نوع مستخدم في مستندات")
        db.delete("doc_types", "id = ?", arrayOf("$id"))
        audit(u, "doctype.delete", "doc_type", id, "حذف نوع")
    }

    fun saveTemplate(u: User, id: Int?, t: Template) = unique {
        guard(u, "templates.manage")
        if (t.name.isBlank()) throw UserError("الاسم مطلوب")
        val v = cv(
            "name" to t.name.trim(), "description" to t.description?.ifBlank { null }, "title_pattern" to t.titlePattern.ifBlank { "{{title}}" },
            "department_id" to t.departmentId, "folder_id" to t.folderId, "doc_type" to t.docType, "default_tags" to t.defaultTags,
            "status" to t.status, "updated_at" to now(),
        )
        if (id == null) db.insert("document_templates", v.apply { put("created_by_id", u.id); put("created_at", now()) })
        else db.update("document_templates", v, "id = ?", arrayOf("$id"))
        audit(u, if (id == null) "template.create" else "template.update", "template", id, "قالب: ${t.name}")
    }

    fun deleteTemplate(u: User, id: Int) {
        guard(u, "templates.manage")
        db.delete("document_templates", "id = ?", arrayOf("$id"))
        audit(u, "template.delete", "template", id, "حذف قالب")
    }

    // ───────────── users (admin) ─────────────

    /**
     * [initialPassword] is only read when creating a user, and only when the
     * admin typed one: it replaces the shared 12345678 default. Still forces a
     * change on first login, so knowing it is never enough to skip that.
     */
    fun saveUser(u: User, id: Int?, name: String, username: String, email: String, role: String, departmentId: Int?, jobTitle: String, active: Boolean, initialPassword: String? = null) = unique {
        guard(u, "users.manage")
        if (name.isBlank() || username.isBlank()) throw UserError("الاسم واسم المستخدم مطلوبان")
        if (!Regex("^[A-Za-z0-9._-]{3,32}$").matches(username.trim())) throw UserError("اسم المستخدم: أحرف إنجليزية وأرقام و . _ - (3 أحرف على الأقل)")
        if (id == u.id && (role != "admin" || !active)) throw UserError("لا يمكنك إزالة صلاحية المدير أو تعطيل حسابك")
        val v = cv(
            "name" to name.trim(), "username" to username.trim(), "email" to email.trim().ifBlank { "${username.trim()}@edms.local" },
            "role" to role, "department_id" to departmentId, "job_title" to jobTitle.ifBlank { null }, "active" to active,
        )
        if (id == null) {
            val pw = initialPassword?.takeIf { it.isNotBlank() } ?: Passwords.DEFAULT_PASSWORD
            if (pw.length < 8) throw UserError("كلمة المرور الأولى: 8 أحرف على الأقل")
            v.put("password_hash", Passwords.hash(pw)); v.put("must_change_password", 1)
            audit(u, "user.create", "user", db.insert("users", v).toInt(), "إنشاء مستخدم: $username")
        } else {
            db.update("users", v, "id = ?", arrayOf("$id"))
            audit(u, "user.update", "user", id, "تعديل مستخدم: $username")
        }
    }

    fun resetPassword(u: User, id: Int) {
        guard(u, "users.manage")
        db.update("users", cv("password_hash" to Passwords.hash(Passwords.DEFAULT_PASSWORD), "must_change_password" to 1), "id = ?", arrayOf("$id"))
        audit(u, "user.update", "user", id, "إعادة تعيين كلمة المرور إلى الافتراضية")
    }

    fun deleteUser(u: User, id: Int) = unique {
        guard(u, "users.manage")
        if (id == u.id) throw UserError("لا يمكنك حذف حسابك")
        db.delete("users", "id = ?", arrayOf("$id"))
        audit(u, "user.delete", "user", id, "حذف مستخدم")
    }

    // ───────────── audit & stats ─────────────

    fun audit(u: User?, action: String, entityType: String?, entityId: Int?, details: String?) {
        db.insert("audit_logs", cv(
            "user_id" to u?.id, "user_name" to u?.name, "action" to action, "entity_type" to entityType,
            "entity_id" to entityId, "details" to details, "created_at" to now(),
        ))
    }

    fun auditLog(query: String = "", limit: Int = 500): List<Audit> {
        val q = "%${query.trim()}%"
        return db.list(
            "SELECT * FROM audit_logs WHERE ? = '%%' OR action LIKE ? OR details LIKE ? OR user_name LIKE ? ORDER BY created_at DESC, id DESC LIMIT $limit",
            q, q, q, q,
        ) { c -> Audit(c.i("id"), c.sn("user_name"), c.s("action"), c.sn("entity_type"), c.inn("entity_id"), c.sn("details"), c.s("created_at")) }
    }

    fun stats(u: User): Stats {
        val where = mutableListOf("d.deleted_at IS NULL")
        val args = mutableListOf<Any?>()
        if (!Perm.can(u, "documents.read_all")) {
            where += "(d.department_id = ? OR d.uploaded_by_id = ?)"; args += u.departmentId ?: -1; args += u.id
        }
        if (u.role == "staff") where += "d.confidential = 0"
        val whereClause = where.joinToString(" AND ")
        val argArray = args.toTypedArray()

        val total = db.count("SELECT COUNT(*) FROM documents d WHERE $whereClause", *argArray)

        val statusCounts = buildMap<String, Int> {
            db.list("SELECT status, COUNT(*) FROM documents d WHERE $whereClause GROUP BY status", *argArray) { c ->
                c.getString(0)?.let { put(it, c.getInt(1)) }
            }
        }
        val byStatus = STATUS_LABELS.keys.associateWith { statusCounts[it] ?: 0 }

        val byDepartment = db.list(
            "SELECT COALESCE(dp.name, 'بدون قسم'), COUNT(d.id) FROM documents d LEFT JOIN departments dp ON dp.id = d.department_id WHERE $whereClause GROUP BY d.department_id ORDER BY COUNT(d.id) DESC",
            *argArray
        ) { c -> (c.getString(0) ?: "بدون قسم") to c.getInt(1) }

        val typeCountsAndSizes = db.list(
            "SELECT COALESCE(d.doc_type, 'غير مصنف'), COUNT(d.id), COALESCE(SUM(d.file_size), 0) FROM documents d WHERE $whereClause GROUP BY d.doc_type ORDER BY COUNT(d.id) DESC",
            *argArray
        ) { c -> Triple(c.getString(0) ?: "غير مصنف", c.getInt(1), c.getLong(2)) }

        val byType = typeCountsAndSizes.map { it.first to it.second }
        val typeSizes = typeCountsAndSizes.associate { it.first to it.third }

        val byMonth = db.list(
            "SELECT substr(d.created_at, 1, 7), COUNT(*) FROM documents d WHERE $whereClause GROUP BY substr(d.created_at, 1, 7) ORDER BY substr(d.created_at, 1, 7) ASC",
            *argArray
        ) { c -> (c.getString(0) ?: "") to c.getInt(1) }.takeLast(6)

        val totalSize = db.one("SELECT COALESCE(SUM(file_size), 0) FROM documents d WHERE $whereClause", *argArray) { it.getLong(0) } ?: 0L

        val pendingApprovals = if (u.role == "admin") db.count("SELECT COUNT(*) FROM approval_requests WHERE status = 'pending'")
        else db.count("SELECT COUNT(*) FROM approval_requests WHERE status = 'pending' AND assigned_to_id = ?", u.id)

        val users = db.count("SELECT COUNT(*) FROM users")

        val recent = db.list("SELECT d.* FROM documents d WHERE $whereClause ORDER BY d.updated_at DESC LIMIT 5", *argArray) { c -> Document.of(c) }

        return Stats(
            total = total,
            byStatus = byStatus,
            byDepartment = byDepartment,
            byType = byType,
            typeSizes = typeSizes,
            byMonth = byMonth,
            totalSize = totalSize,
            pendingApprovals = pendingApprovals,
            users = users,
            recent = recent,
        )
    }
}

/** Arabic search normalization — same rules as the desktop (src/lib/arabic.ts). */
object Arabic {
    private val stop = setOf("من", "في", "عن", "على", "الى", "حتى", "ثم", "او", "و", "ف", "ب", "ك", "ل", "ان", "قد", "هذا", "هذه", "التي", "الذي", "مع")

    fun normalize(s: String): String {
        val len = s.length
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val ch = s[i]
            if (ch in 'ً'..'ْ' || ch == 'ٰ' || ch == 'ـ') continue
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in 'A'..'Z' -> sb.append(ch.lowercaseChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Normalized, light-stemmed terms: "المستندات" → "مستند". */
    fun terms(q: String): List<String> = normalize(q).split(Regex("\\s+")).filter { it.isNotBlank() && it !in stop }.map(::stem)

    private fun stem(w: String): String {
        var s = w
        Regex("^(وال|فال|بال|كال|ال|و|ف|ب|ك|ل)").find(s)?.let { if (s.length - it.value.length >= 3) s = s.substring(it.value.length) }
        Regex("(ات|ون|ين|ان|ها|هم|ه|ي)$").find(s)?.let { if (s.length - it.value.length >= 3) s = s.dropLast(it.value.length) }
        return s
    }
}
