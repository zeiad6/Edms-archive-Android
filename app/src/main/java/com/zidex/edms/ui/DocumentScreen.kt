package com.zidex.edms.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.zidex.edms.data.Document
import com.zidex.edms.data.Perm
import com.zidex.edms.data.Repo
import com.zidex.edms.data.STATUS_LABELS
import com.zidex.edms.data.Storage
import java.io.ByteArrayOutputStream
import java.io.File

@Composable
fun DocumentScreen(id: Int) {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    val d = load(id) { repo.document(u, id) } ?: return Loading()
    val tags = load(id) { repo.documentTags(id) }.orEmpty()
    val versions = load(id) { repo.versions(id) }.orEmpty()
    val approvals = load(id) { repo.documentApprovals(id) }.orEmpty()
    val signatures = load(id) { repo.signatures(id) }.orEmpty()
    val depts = load { repo.departments() }.orEmpty()
    val folders = load { repo.folders() }.orEmpty()
    val uploader = load(d.uploadedById) { repo.user(d.uploadedById)?.name }
    var askDelete by remember { mutableStateOf(false) }
    var askApproval by remember { mutableStateOf(false) }
    var sign by remember { mutableStateOf(false) }
    LaunchedEffect(id) { scope.act { repo.logView(u, d) } }

    val newVersion = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.act("تم رفع الإصدار الجديد") { repo.addVersion(u, id, Storage.importUri(ctx, uri), "") }
    }

    LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(d.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                Pill(STATUS_LABELS[d.status] ?: d.status, statusColor(d.status))
                if (d.confidential) Pill("سري", Color(0xFF7C3AED))
                Pill("الإصدار ${d.version}", MaterialTheme.colorScheme.primary)
                tags.forEach { Pill(it.name, parseColor(it.color)) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ openFile(ctx, d.storageKey, d.mimeType, share = false) }, { Text("فتح") }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) })
                AssistChip({ openFile(ctx, d.storageKey, d.mimeType, share = true) }, { Text("مشاركة") }, leadingIcon = { Icon(Icons.Default.Share, null) })
                if (Perm.canEdit(u, d)) {
                    AssistChip({ Nav.go(Screen.DocEdit(id = d.id)) }, { Text("تعديل") }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                    AssistChip({ newVersion.launch(arrayOf("*/*")) }, { Text("إصدار جديد") }, leadingIcon = { Icon(Icons.Default.UploadFile, null) })
                }
                AssistChip({ askApproval = true }, { Text("طلب موافقة") }, leadingIcon = { Icon(Icons.Default.Send, null) })
                AssistChip({ sign = true }, { Text("توقيع") }, leadingIcon = { Icon(Icons.Default.Draw, null) })
                if (Perm.can(u, "documents.delete") || d.uploadedById == u.id) {
                    AssistChip({ askDelete = true }, { Text("حذف") }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                }
            }
        }
        item { Preview(d) }
        item {
            Section("البيانات") {
                KeyValue("رقم المستند", d.docNumber)
                KeyValue("النوع", d.docType)
                KeyValue("القسم", depts.firstOrNull { it.id == d.departmentId }?.name)
                KeyValue("المجلد", folders.firstOrNull { it.id == d.folderId }?.name)
                KeyValue("تاريخ المستند", d.docDate)
                KeyValue("الوصف", d.description)
                KeyValue("الكلمات المفتاحية", d.keywords)
                KeyValue("المصدر", d.source)
                KeyValue("ملاحظات", d.notes)
                KeyValue("الملف", "${d.originalName} (${formatBytes(d.fileSize)}، ${d.pageCount} صفحة)")
                KeyValue("أودعه", uploader)
                KeyValue("تاريخ الإيداع", shortDate(d.createdAt))
                KeyValue("آخر تعديل", shortDate(d.updatedAt))
            }
        }
        if (versions.isNotEmpty()) item {
            Section("الإصدارات") {
                versions.forEach { v ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("الإصدار ${v.version} — ${v.originalName}", fontSize = 13.sp)
                            Text("${v.uploaderName} · ${shortDate(v.createdAt)} · ${formatBytes(v.fileSize)}${v.note?.let { " · $it" } ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton({ openFile(ctx, v.storageKey, d.mimeType, share = false) }) { Text("فتح") }
                    }
                }
            }
        }
        if (approvals.isNotEmpty()) item {
            Section("طلبات الموافقة") { approvals.forEach { ApprovalRow(it, compact = true) } }
        }
        if (signatures.isNotEmpty()) item {
            Section("التوقيعات") {
                signatures.forEach { s ->
                    val bmp = remember(s.id) { decodeDataUrl(s.dataUrl) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${s.label} — ${s.userName}", fontSize = 13.sp)
                            Text(shortDate(s.createdAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        bmp?.let { Image(it.asImageBitmap(), null, Modifier.height(48.dp).background(Color.White)) }
                    }
                }
            }
        }
    }

    if (askDelete) Confirm("نقل المستند إلى سلة المحذوفات؟", "حذف", onDismiss = { askDelete = false }) {
        scope.act("نُقل إلى سلة المحذوفات", then = { Nav.back() }) { repo.softDelete(u, id) }
    }
    if (askApproval) ApprovalRequestDialog(d, onDismiss = { askApproval = false })
    if (sign) SignatureDialog(onDismiss = { sign = false }) { label, png ->
        scope.act("تمت إضافة التوقيع") { repo.addSignature(u, id, label, png) }
    }
}

@Composable
private fun Preview(d: Document) {
    val ctx = LocalContext.current
    when {
        d.isImage -> {
            val bmp = load(d.storageKey) { Storage.decodeSampled(Storage.file(ctx, d.storageKey), 1600) }
            bmp?.let {
                Image(it.asImageBitmap(), d.title, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White))
            }
        }
        d.isPdf -> {
            // First pages inline; the full file opens in a PDF app.
            var shown by remember(d.id) { mutableStateOf(3) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in 0 until minOf(shown, d.pageCount)) {
                    val bmp = load(d.storageKey, p) { Storage.renderPdfPage(Storage.file(ctx, d.storageKey), p, 1200) }
                    bmp?.let { Image(it.asImageBitmap(), "صفحة ${p + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().border(1.dp, Color(0x22000000))) }
                }
                if (d.pageCount > shown) TextButton({ shown += 5 }) { Text("عرض صفحات أخرى (${d.pageCount - shown} متبقية)") }
            }
        }
        else -> Empty("لا توجد معاينة لهذا النوع — استخدم «فتح»")
    }
}

fun openFile(ctx: Context, key: String, mime: String, share: Boolean) {
    val f: File = Storage.file(ctx, key)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
    else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(intent, if (share) "مشاركة" else "فتح بواسطة"))
    } catch (_: ActivityNotFoundException) {
        ctx.toast("لا يوجد تطبيق لفتح هذا الملف")
    }
}

fun decodeDataUrl(dataUrl: String): Bitmap? = runCatching {
    val b = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(b, 0, b.size)
}.getOrNull()

@Composable
private fun ApprovalRequestDialog(d: Document, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val scope = rememberCoroutineScope()
    val approvers = load { Repo.get(ctx).approvers() }.orEmpty()
    var assignee by remember { mutableStateOf<Int?>(null) }
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إرسال للموافقة") },
        text = {
            Column {
                Picker("المسؤول عن الموافقة", approvers.map { it.id to it.name }, assignee, { assignee = it }, allowNone = false)
                OutlinedTextField(comment, { comment = it }, label = { Text("ملاحظة") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton({
                val a = assignee ?: return@TextButton Session.say("اختر المسؤول عن الموافقة")
                scope.act("أُرسل طلب الموافقة", then = onDismiss) { Repo.get(ctx).submitApproval(u, d.id, a, comment) }
            }) { Text("إرسال") }
        },
        dismissButton = { TextButton(onDismiss) { Text("إلغاء") } },
    )
}

/** Finger-drawn signature, stored like the desktop: PNG data URL. */
@Composable
private fun SignatureDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var label by remember { mutableStateOf("توقيع") }
    val ink = Color(0xFF1E1B4B)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة توقيع") },
        text = {
            Column {
                OutlinedTextField(label, { label = it }, label = { Text("الوصف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Canvas(
                    Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .border(1.dp, Color(0x33000000), RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            canvasSize = size
                            detectDragGestures(
                                onDragStart = { current = listOf(it) },
                                onDrag = { ch, _ -> current = current + ch.position },
                                onDragEnd = { strokes.add(current); current = emptyList() },
                            )
                        },
                ) {
                    (strokes + listOf(current)).forEach { pts ->
                        if (pts.size > 1) drawPath(
                            Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } },
                            ink, style = Stroke(width = 5f, cap = StrokeCap.Round),
                        )
                    }
                }
                TextButton({ strokes.clear() }) { Text("مسح") }
            }
        },
        confirmButton = {
            TextButton({
                if (strokes.isEmpty()) return@TextButton Session.say("ارسم التوقيع أولاً")
                onSave(label, strokesToPng(strokes, canvasSize))
                onDismiss()
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onDismiss) { Text("إلغاء") } },
    )
}

private fun strokesToPng(strokes: List<List<Offset>>, size: IntSize): String {
    val w = 600
    val h = (w.toFloat() * size.height / size.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    val s = w.toFloat() / size.width.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint().apply {
        color = 0xFF1E1B4B.toInt(); strokeWidth = 5f * s; style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; isAntiAlias = true
    }
    strokes.filter { it.size > 1 }.forEach { pts ->
        val p = android.graphics.Path().apply { moveTo(pts[0].x * s, pts[0].y * s); pts.drop(1).forEach { lineTo(it.x * s, it.y * s) } }
        c.drawPath(p, paint)
    }
    val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
