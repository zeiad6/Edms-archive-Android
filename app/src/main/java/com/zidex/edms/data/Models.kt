package com.zidex.edms.data

import android.database.Cursor

data class User(
    val id: Int, val name: String, val username: String, val email: String, val passwordHash: String?,
    val mustChangePassword: Boolean, val jobTitle: String?, val role: String, val departmentId: Int?,
    val avatarColor: String, val active: Boolean, val createdAt: String,
) {
    companion object {
        fun of(c: Cursor) = User(
            c.i("id"), c.s("name"), c.s("username"), c.s("email"), c.sn("password_hash"), c.i("must_change_password") == 1,
            c.sn("job_title"), c.s("role"), c.inn("department_id"), c.s("avatar_color"), c.i("active") == 1, c.s("created_at"),
        )
    }
}

data class Department(val id: Int, val name: String, val code: String?, val description: String?, val color: String) {
    companion object {
        fun of(c: Cursor) = Department(c.i("id"), c.s("name"), c.sn("code"), c.sn("description"), c.s("color"))
    }
}

data class Folder(val id: Int, val name: String, val parentId: Int?, val departmentId: Int?, val description: String?) {
    companion object {
        fun of(c: Cursor) = Folder(c.i("id"), c.s("name"), c.inn("parent_id"), c.inn("department_id"), c.sn("description"))
    }
}

data class Tag(val id: Int, val name: String, val color: String) {
    companion object {
        fun of(c: Cursor) = Tag(c.i("id"), c.s("name"), c.s("color"))
    }
}

data class DocType(val id: Int, val name: String, val nameEn: String?, val color: String, val sortOrder: Int) {
    companion object {
        fun of(c: Cursor) = DocType(c.i("id"), c.s("name"), c.sn("name_en"), c.s("color"), c.i("sort_order"))
    }
}

data class Document(
    val id: Int, val title: String, val description: String?, val docNumber: String?, val docType: String?,
    val docTypeId: Int?, val status: String, val confidential: Boolean, val storageKey: String, val thumbKey: String?,
    val originalName: String, val mimeType: String, val fileExt: String?, val fileSize: Long, val pageCount: Int,
    val contentText: String?, val departmentId: Int?, val folderId: Int?, val version: Int, val docDate: String?,
    val keywords: String?, val source: String?, val notes: String?, val uploadedById: Int, val deletedAt: String?,
    val createdAt: String, val updatedAt: String,
) {
    val isImage get() = mimeType.startsWith("image/")
    val isPdf get() = mimeType == "application/pdf"

    companion object {
        fun of(c: Cursor) = Document(
            c.i("id"), c.s("title"), c.sn("description"), c.sn("doc_number"), c.sn("doc_type"), c.inn("doc_type_id"),
            c.s("status"), c.i("confidential") == 1, c.s("storage_key"), c.sn("thumb_key"), c.s("original_name"),
            c.s("mime_type"), c.sn("file_ext"), c.l("file_size"), c.inn("page_count") ?: 1, c.sn("content_text"),
            c.inn("department_id"), c.inn("folder_id"), c.i("version"), c.sn("doc_date"), c.sn("keywords"), c.sn("source"),
            c.sn("notes"), c.i("uploaded_by_id"), c.sn("deleted_at"), c.s("created_at"), c.s("updated_at"),
        )
    }
}

data class Version(
    val id: Int, val version: Int, val storageKey: String, val originalName: String, val fileSize: Long,
    val note: String?, val uploaderName: String, val createdAt: String,
)

data class Audit(val id: Int, val userName: String?, val action: String, val entityType: String?, val entityId: Int?, val details: String?, val createdAt: String)

data class Approval(
    val id: Int, val documentId: Int, val docTitle: String, val requestedById: Int, val requesterName: String,
    val assignedToId: Int, val assigneeName: String, val status: String, val comment: String?, val responseNote: String?,
    val respondedAt: String?, val createdAt: String,
)

data class Notice(val id: Int, val type: String, val title: String, val message: String, val documentId: Int?, val read: Boolean, val createdAt: String)

data class Signature(val id: Int, val userName: String, val label: String, val dataUrl: String, val createdAt: String)

data class Template(
    val id: Int, val name: String, val description: String?, val titlePattern: String, val departmentId: Int?,
    val folderId: Int?, val docType: String?, val defaultTags: String?, val status: String,
) {
    companion object {
        fun of(c: Cursor) = Template(
            c.i("id"), c.s("name"), c.sn("description"), c.s("title_pattern"), c.inn("department_id"),
            c.inn("folder_id"), c.sn("doc_type"), c.sn("default_tags"), c.s("status"),
        )
    }
}

/** Mirrors the desktop RBAC map (src/lib/permissions.ts). */
object Perm {
    private val map = mapOf(
        "documents.create" to setOf("admin", "manager", "staff"),
        "documents.read_all" to setOf("admin", "manager"),
        "documents.update_all" to setOf("admin"),
        "documents.delete" to setOf("admin", "manager"),
        "documents.restore" to setOf("admin", "manager"),
        "documents.force_delete" to setOf("admin"),
        "departments.manage" to setOf("admin"),
        "users.view" to setOf("admin", "manager"),
        "users.manage" to setOf("admin"),
        "folders.manage" to setOf("admin", "manager"),
        "tags.manage" to setOf("admin", "manager"),
        "doc-types.manage" to setOf("admin", "manager"),
        "templates.manage" to setOf("admin", "manager"),
        "approvals.manage" to setOf("admin", "manager"),
        "settings.manage" to setOf("admin"),
        "audit.view" to setOf("admin"),
        "reports.view" to setOf("admin", "manager"),
        "trash.view" to setOf("admin", "manager"),
        "trash.purge" to setOf("admin"),
    )

    fun can(u: User?, key: String) = u != null && map[key]?.contains(u.role) == true

    /** Desktop canAccessDocument(): admin all; staff never confidential; else own department or own upload. */
    fun canAccess(u: User?, d: Document): Boolean = when {
        u == null -> false
        u.role == "admin" -> true
        d.confidential && u.role == "staff" -> false
        else -> d.departmentId == u.departmentId || d.uploadedById == u.id
    }

    fun canEdit(u: User?, d: Document) = can(u, "documents.update_all") || (u != null && d.uploadedById == u.id && canAccess(u, d))
}

val STATUS_LABELS = linkedMapOf("active" to "نشط", "pending_review" to "قيد المراجعة", "draft" to "مسودة", "archived" to "مؤرشف")
val ROLE_LABELS = linkedMapOf("admin" to "مدير النظام", "manager" to "مشرف قسم", "staff" to "موظف")
