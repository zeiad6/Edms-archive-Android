package com.zidex.edms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zidex.edms.data.DocFilter
import com.zidex.edms.data.Perm
import com.zidex.edms.data.ROLE_LABELS
import com.zidex.edms.data.Repo
import com.zidex.edms.data.Template
import com.zidex.edms.data.User

private val PALETTE = listOf("#4f46e5", "#059669", "#d97706", "#0ea5e9", "#be123c", "#7c3aed", "#dc2626", "#64748b")

/** List + add/edit dialog + delete, shared by every reference-data screen. */
@Composable
private fun <T> ManageList(
    items: List<T>?, canManage: Boolean, key: (T) -> Int, row: @Composable (T) -> Unit,
    onOpen: ((T) -> Unit)? = null, editor: @Composable (T?, () -> Unit) -> Unit, onDelete: (T) -> Unit,
) {
    var editing by remember { mutableStateOf<Pair<T?, Boolean>?>(null) }
    var deleting by remember { mutableStateOf<T?>(null) }
    Box(Modifier.fillMaxSize()) {
        when {
            items == null -> Loading()
            items.isEmpty() -> Empty("لا توجد عناصر")
            else -> LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 180.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = key) { item ->
                    ClickRow(onClick = { if (canManage) editing = item to true else onOpen?.invoke(item) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { row(item) }
                            if (onOpen != null && canManage) TextButton({ onOpen(item) }) { Text("المستندات") }
                            if (canManage) IconButton({ deleting = item }) { Icon(Icons.Default.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        if (canManage) Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 104.dp)) { AddFab { editing = null to true } }
    }
    editing?.let { (item, _) -> editor(item) { editing = null } }
    deleting?.let { item -> Confirm("حذف هذا العنصر نهائياً؟", "حذف", onDismiss = { deleting = null }) { onDelete(item) } }
}

@Composable
private fun ColorRow(color: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        PALETTE.forEach { c ->
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(parseColor(c))
                    .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun EditDialog(title: String, onDismiss: () -> Unit, onSave: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { TextButton(onSave) { Text("حفظ") } },
        dismissButton = { TextButton(onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun Dot(color: String) = Box(Modifier.size(12.dp).clip(CircleShape).background(parseColor(color)))

@Composable
fun DepartmentsScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    ManageList(
        load { repo.departments() }, Perm.can(u, "departments.manage"), { it.id },
        row = { d -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(d.color); Spacer(Modifier.width(10.dp)); Column { Text(d.name, fontWeight = FontWeight.SemiBold); d.description?.let { Text(it, fontSize = 12.sp) } } } },
        onOpen = { d -> Nav.go(Screen.Documents(DocFilter(departmentId = d.id))) },
        editor = { d, close ->
            var name by remember { mutableStateOf(d?.name.orEmpty()) }
            var code by remember { mutableStateOf(d?.code.orEmpty()) }
            var desc by remember { mutableStateOf(d?.description.orEmpty()) }
            var color by remember { mutableStateOf(d?.color ?: PALETTE[0]) }
            EditDialog(if (d == null) "قسم جديد" else "تعديل القسم", close, { scope.act("تم الحفظ", close) { repo.saveDepartment(u, d?.id, name, code, color, desc) } }) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") })
                OutlinedTextField(code, { code = it }, label = { Text("الرمز") })
                OutlinedTextField(desc, { desc = it }, label = { Text("الوصف") })
                ColorRow(color) { color = it }
            }
        },
        onDelete = { d -> scope.act("تم الحذف") { repo.deleteDepartment(u, d.id) } },
    )
}

@Composable
fun FoldersScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    val depts = load { repo.departments() }.orEmpty()
    val folders = load { repo.folders() }
    ManageList(
        folders, Perm.can(u, "folders.manage"), { it.id },
        row = { f ->
            Column {
                Text((f.parentId?.let { p -> folders?.firstOrNull { it.id == p }?.name?.plus(" / ") } ?: "") + f.name, fontWeight = FontWeight.SemiBold)
                Text(depts.firstOrNull { it.id == f.departmentId }?.name ?: "كل الأقسام", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        onOpen = { f -> Nav.go(Screen.Documents(DocFilter(folderId = f.id))) },
        editor = { f, close ->
            var name by remember { mutableStateOf(f?.name.orEmpty()) }
            var dept by remember { mutableStateOf(f?.departmentId) }
            var parent by remember { mutableStateOf(f?.parentId) }
            var desc by remember { mutableStateOf(f?.description.orEmpty()) }
            EditDialog(if (f == null) "مجلد جديد" else "تعديل المجلد", close, { scope.act("تم الحفظ", close) { repo.saveFolder(u, f?.id, name, dept, parent, desc) } }) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") })
                Picker("القسم", depts.map { it.id to it.name }, dept, { dept = it })
                Picker("المجلد الأب", folders.orEmpty().filter { it.id != f?.id }.map { it.id to it.name }, parent, { parent = it })
                OutlinedTextField(desc, { desc = it }, label = { Text("الوصف") })
            }
        },
        onDelete = { f -> scope.act("تم الحذف") { repo.deleteFolder(u, f.id) } },
    )
}

@Composable
fun TagsScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    ManageList(
        load { repo.tags() }, Perm.can(u, "tags.manage"), { it.id },
        row = { t -> Pill(t.name, parseColor(t.color)) },
        onOpen = { t -> Nav.go(Screen.Documents(DocFilter(tagId = t.id))) },
        editor = { t, close ->
            var name by remember { mutableStateOf(t?.name.orEmpty()) }
            var color by remember { mutableStateOf(t?.color ?: PALETTE[7]) }
            EditDialog(if (t == null) "وسم جديد" else "تعديل الوسم", close, { scope.act("تم الحفظ", close) { repo.saveTag(u, t?.id, name, color) } }) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") })
                ColorRow(color) { color = it }
            }
        },
        onDelete = { t -> scope.act("تم الحذف") { repo.deleteTag(u, t.id) } },
    )
}

@Composable
fun DocTypesScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    ManageList(
        load { repo.docTypes() }, Perm.can(u, "doc-types.manage"), { it.id },
        row = { t -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(t.color); Spacer(Modifier.width(10.dp)); Text(t.name, fontWeight = FontWeight.SemiBold); t.nameEn?.let { Text("  ($it)", fontSize = 12.sp) } } },
        onOpen = { t -> Nav.go(Screen.Documents(DocFilter(docType = t.name))) },
        editor = { t, close ->
            var name by remember { mutableStateOf(t?.name.orEmpty()) }
            var en by remember { mutableStateOf(t?.nameEn.orEmpty()) }
            var order by remember { mutableStateOf((t?.sortOrder ?: 0).toString()) }
            var color by remember { mutableStateOf(t?.color ?: PALETTE[0]) }
            EditDialog(if (t == null) "نوع جديد" else "تعديل النوع", close, { scope.act("تم الحفظ", close) { repo.saveDocType(u, t?.id, name, en, color, order.toIntOrNull() ?: 0) } }) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") })
                OutlinedTextField(en, { en = it }, label = { Text("الاسم بالإنجليزية") })
                OutlinedTextField(order, { order = it }, label = { Text("الترتيب") })
                ColorRow(color) { color = it }
            }
        },
        onDelete = { t -> scope.act("تم الحذف") { repo.deleteDocType(u, t.id) } },
    )
}

@Composable
fun TemplatesScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    val depts = load { repo.departments() }.orEmpty()
    val folders = load { repo.folders() }.orEmpty()
    val types = load { repo.docTypes() }.orEmpty()
    ManageList(
        load { repo.templates() }, Perm.can(u, "templates.manage"), { it.id },
        row = { t -> Column { Text(t.name, fontWeight = FontWeight.SemiBold); Text(listOfNotNull(t.docType, t.titlePattern).joinToString(" · "), fontSize = 12.sp) } },
        editor = { t, close ->
            var e by remember { mutableStateOf(t ?: Template(0, "", null, "{{title}}", null, null, null, null, "active")) }
            EditDialog(if (t == null) "قالب جديد" else "تعديل القالب", close, { scope.act("تم الحفظ", close) { repo.saveTemplate(u, t?.id, e) } }) {
                OutlinedTextField(e.name, { e = e.copy(name = it) }, label = { Text("الاسم") })
                OutlinedTextField(e.titlePattern, { e = e.copy(titlePattern = it) }, label = { Text("نمط العنوان") }, supportingText = { Text("{{title}} {{date}} {{year}}") })
                Picker("النوع", types.map { it.name to it.name }, e.docType, { e = e.copy(docType = it) })
                Picker("القسم", depts.map { it.id to it.name }, e.departmentId, { e = e.copy(departmentId = it) })
                Picker("المجلد", folders.map { it.id to it.name }, e.folderId, { e = e.copy(folderId = it) })
                OutlinedTextField(e.defaultTags.orEmpty(), { e = e.copy(defaultTags = it) }, label = { Text("وسوم افتراضية (مفصولة بفواصل)") })
                OutlinedTextField(e.description.orEmpty(), { e = e.copy(description = it) }, label = { Text("الوصف") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("مفعّل", Modifier.weight(1f)); Switch(e.status == "active", { e = e.copy(status = if (it) "active" else "archived") })
                }
            }
        },
        onDelete = { t -> scope.act("تم الحذف") { repo.deleteTemplate(u, t.id) } },
    )
}

@Composable
fun UsersScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    val depts = load { repo.departments() }.orEmpty()
    var resetFor by remember { mutableStateOf<User?>(null) }
    ManageList(
        load { repo.users() }, Perm.can(u, "users.manage"), { it.id },
        row = { x ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(x.name, x.avatarColor)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(x.name + if (!x.active) " (معطّل)" else "", fontWeight = FontWeight.SemiBold, color = if (x.active) Color.Unspecified else MaterialTheme.colorScheme.error)
                    Text("${x.username} · ${ROLE_LABELS[x.role]} · ${depts.firstOrNull { it.id == x.departmentId }?.name ?: "—"}", fontSize = 12.sp)
                }
            }
        },
        editor = { x, close ->
            var name by remember { mutableStateOf(x?.name.orEmpty()) }
            var username by remember { mutableStateOf(x?.username.orEmpty()) }
            var email by remember { mutableStateOf(x?.email.orEmpty()) }
            var role by remember { mutableStateOf(x?.role ?: "staff") }
            var dept by remember { mutableStateOf(x?.departmentId) }
            var job by remember { mutableStateOf(x?.jobTitle.orEmpty()) }
            var active by remember { mutableStateOf(x?.active ?: true) }
            EditDialog(if (x == null) "مستخدم جديد" else "تعديل المستخدم", close, { scope.act("تم الحفظ", close) { repo.saveUser(u, x?.id, name, username, email, role, dept, job, active) } }) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم الكامل") })
                OutlinedTextField(username, { username = it }, label = { Text("اسم المستخدم") })
                OutlinedTextField(email, { email = it }, label = { Text("البريد (اختياري)") })
                OutlinedTextField(job, { job = it }, label = { Text("المسمى الوظيفي") })
                Picker("الدور", ROLE_LABELS.map { it.key to it.value }, role, { role = it ?: "staff" }, allowNone = false)
                Picker("القسم", depts.map { it.id to it.name }, dept, { dept = it })
                Row(verticalAlignment = Alignment.CenterVertically) { Text("الحساب مفعّل", Modifier.weight(1f)); Switch(active, { active = it }) }
                if (x == null) Text("كلمة المرور الأولى: 12345678 — تُطلب تغييرها عند أول دخول.", fontSize = 12.sp)
                else TextButton({ resetFor = x; close() }) { Text("إعادة تعيين كلمة المرور") }
            }
        },
        onDelete = { x -> scope.act("تم الحذف") { repo.deleteUser(u, x.id) } },
    )
    resetFor?.let { x ->
        Confirm("إعادة كلمة مرور ${x.name} إلى 12345678 مع فرض تغييرها؟", onDismiss = { resetFor = null }) {
            scope.act("تمت إعادة التعيين") { repo.resetPassword(u, x.id) }
        }
    }
}
