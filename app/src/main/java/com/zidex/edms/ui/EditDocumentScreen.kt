package com.zidex.edms.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zidex.edms.data.DocMeta
import com.zidex.edms.data.Perm
import com.zidex.edms.data.Repo
import com.zidex.edms.data.STATUS_LABELS
import com.zidex.edms.data.Storage
import java.time.LocalDate

/**
 * Create (from an uploaded/scanned file) or edit a document's metadata.
 * New documents can start from a template (department, folder, type, tags, title pattern).
 */
@Composable
fun EditDocumentScreen(id: Int?, stored: Storage.Stored?, prefill: DocMeta?) {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    val existing = if (id != null) load(id) { repo.document(u, id) to repo.documentTags(id).map { it.id }.toSet() } else null
    if (id != null && existing == null) return Loading()

    val start = remember(existing) {
        existing?.let { (d, t) ->
            DocMeta(d.title, d.description, d.docNumber, d.docType, d.status, d.confidential, d.departmentId, d.folderId, d.docDate, d.keywords, d.source, d.notes, t)
        } ?: (prefill ?: DocMeta(title = "")).let { it.copy(departmentId = it.departmentId ?: u.departmentId, docDate = it.docDate ?: LocalDate.now().toString()) }
    }
    var m by remember(start) { mutableStateOf(start) }
    val depts = load { repo.departments() }.orEmpty()
    val folders = load { repo.folders() }.orEmpty()
    val types = load { repo.docTypes() }.orEmpty()
    val tags = load { repo.tags() }.orEmpty()
    val templates = if (id == null) load { repo.templates().filter { it.status == "active" } }.orEmpty() else emptyList()
    var template by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val admin = Perm.can(u, "documents.update_all")

    LaunchedEffect(template) {
        val t = templates.firstOrNull { it.id == template } ?: return@LaunchedEffect
        val tagIds = t.defaultTags?.split(',')?.map { it.trim() }?.mapNotNull { n -> tags.firstOrNull { it.name == n }?.id }?.toSet().orEmpty()
        m = m.copy(
            title = t.titlePattern.replace("{{title}}", m.title).replace("{{date}}", LocalDate.now().toString()).replace("{{year}}", "${LocalDate.now().year}"),
            departmentId = t.departmentId ?: m.departmentId, folderId = t.folderId ?: m.folderId, docType = t.docType ?: m.docType,
            tagIds = m.tagIds + tagIds,
        )
    }

    Column(Modifier.verticalScroll(rememberScrollState()).imeScrollSafe().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stored?.let {
            Text("الملف: ${it.originalName} · ${formatBytes(it.size)} · ${it.pages} صفحة", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (templates.isNotEmpty()) Picker("قالب (اختياري)", templates.map { it.id to it.name }, template, { template = it })
        OutlinedTextField(m.title, { m = m.copy(title = it) }, label = { Text("العنوان *") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(m.docNumber.orEmpty(), { m = m.copy(docNumber = it) }, label = { Text("رقم المستند") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(m.docDate.orEmpty(), { m = m.copy(docDate = it) }, label = { Text("التاريخ (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Picker("نوع المستند", types.map { it.name to it.name }, m.docType, { m = m.copy(docType = it) })
        Picker("القسم", depts.map { it.id to it.name }, m.departmentId, { m = m.copy(departmentId = it) }, modifier = Modifier.fillMaxWidth())
        Picker(
            "المجلد", folders.filter { m.departmentId == null || it.departmentId == null || it.departmentId == m.departmentId }.map { it.id to it.name },
            m.folderId, { m = m.copy(folderId = it) },
        )
        if (admin || id == null) {
            Picker("الحالة", STATUS_LABELS.map { it.key to it.value }, m.status, { m = m.copy(status = it ?: "active") }, allowNone = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("مستند سري", Modifier.weight(1f))
                Switch(m.confidential, { m = m.copy(confidential = it) })
            }
        }
        OutlinedTextField(m.description.orEmpty(), { m = m.copy(description = it) }, label = { Text("الوصف") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(m.keywords.orEmpty(), { m = m.copy(keywords = it) }, label = { Text("كلمات مفتاحية") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(m.source.orEmpty(), { m = m.copy(source = it) }, label = { Text("المصدر / الجهة") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(m.notes.orEmpty(), { m = m.copy(notes = it) }, label = { Text("ملاحظات") }, modifier = Modifier.fillMaxWidth())
        if (tags.isNotEmpty()) {
            Text("الوسوم", fontWeight = FontWeight.Bold)
            ChipsSelect(tags.map { it.id to it.name }, m.tagIds) { t -> m = m.copy(tagIds = if (t in m.tagIds) m.tagIds - t else m.tagIds + t) }
        }
        Button(
            enabled = !saving && m.title.isNotBlank(),
            onClick = {
                saving = true
                scope.act("تم الحفظ", then = { saving = false }) {
                    try {
                        if (id != null) {
                            repo.updateDocument(u, id, m)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Nav.back() }
                        } else {
                            val s = stored ?: throw com.zidex.edms.data.UserError("لا يوجد ملف")
                            val newId = repo.createDocument(u, s, m, scanned = prefill?.source == SCAN_SOURCE)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Nav.replace(Screen.DocView(newId)) }
                        }
                    } finally {
                        saving = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (id == null) "إيداع في الأرشيف" else "حفظ التعديلات") }
    }
}

/** Marks documents created from the camera flow (audit action document.scan). */
const val SCAN_SOURCE = "مسح ضوئي بالهاتف"
