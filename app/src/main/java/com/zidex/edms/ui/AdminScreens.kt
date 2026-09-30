package com.zidex.edms.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zidex.edms.BuildConfig
import com.zidex.edms.Updater
import com.zidex.edms.data.Backup
import com.zidex.edms.data.Perm
import com.zidex.edms.data.Repo
import com.zidex.edms.data.STATUS_LABELS
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun AuditScreen() {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val rows = load(q) { Repo.get(ctx).auditLog(q) }
    Column {
        OutlinedTextField(
            q, { q = it }, placeholder = { Text("بحث في السجل…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        when {
            rows == null -> Loading()
            rows.isEmpty() -> Empty("لا توجد سجلات")
            else -> LazyColumn(contentPadding = NavClearance, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.id }) { a ->
                    ClickRow(onClick = { if (a.entityType == "document" && a.entityId != null) Nav.go(Screen.DocView(a.entityId)) }) {
                        Column {
                            Row {
                                Text(a.action, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(shortDate(a.createdAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(listOfNotNull(a.userName, a.details).joinToString(" — "), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrashScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    val docs = load { repo.documents(u, deleted = true) }
    var purge by remember { mutableStateOf<Int?>(null) }
    var empty by remember { mutableStateOf(false) }
    Column {
        if (Perm.can(u, "trash.purge") && !docs.isNullOrEmpty()) {
            OutlinedButton({ empty = true }, Modifier.fillMaxWidth().padding(12.dp)) { Text("إفراغ السلة نهائياً") }
        }
        when {
            docs == null -> Loading()
            docs.isEmpty() -> Empty("سلة المحذوفات فارغة")
            else -> LazyColumn(contentPadding = NavClearance, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(docs, key = { it.id }) { d ->
                    Section(d.title) {
                        Text("حُذف في ${shortDate(d.deletedAt)} · ${STATUS_LABELS[d.status]}", fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ scope.act("تمت الاستعادة") { repo.restore(u, d.id) } }) { Text("استعادة") }
                            if (Perm.can(u, "trash.purge")) TextButton({ purge = d.id }) { Text("حذف نهائي", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    purge?.let { id -> Confirm("حذف المستند وكل إصداراته نهائياً؟ لا يمكن التراجع.", "حذف نهائي", onDismiss = { purge = null }) { scope.act("حُذف نهائياً") { repo.purge(u, id) } } }
    if (empty) Confirm("حذف كل مستندات السلة نهائياً؟ لا يمكن التراجع.", "إفراغ", onDismiss = { empty = false }) { scope.act("أُفرغت السلة") { repo.emptyTrash(u) } }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val scope = rememberCoroutineScope()
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.act("تم حفظ النسخة الاحتياطية") {
            ctx.contentResolver.openOutputStream(uri)!!.use { Backup.export(ctx, u, it) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    LazyColumn(contentPadding = NavClearance, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("حسابي") {
                KeyValue("الاسم", u.name)
                KeyValue("اسم المستخدم", u.username)
                KeyValue("الدور", com.zidex.edms.data.ROLE_LABELS[u.role])
                var change by remember { mutableStateOf(false) }
                if (change) ChangePasswordForm(forced = false) { change = false }
                else TextButton({ change = true }) { Text("تغيير كلمة المرور") }
            }
        }
        if (Perm.can(u, "settings.manage")) item {
            Section("النسخ الاحتياطي والاستعادة") {
                Text(
                    "نسخة كاملة (قاعدة البيانات وكل الملفات) بصيغة نظام سطح المكتب نفسها: يمكن استعادة نسخة الهاتف على الحاسوب ونسخة الحاسوب على الهاتف.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button({
                        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
                        exportLauncher.launch("edms-backup-$stamp.zip")
                    }) { Text("تصدير نسخة") }
                    OutlinedButton({ importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("استعادة نسخة") }
                }
            }
        }
        item {
            Section("التحديثات") {
                Text("الإصدار الحالي ${BuildConfig.VERSION_NAME}. يتحقق التطبيق تلقائياً مرة يومياً.", fontSize = 13.sp)
                TextButton({ (ctx as? Activity)?.let { Updater.check(it, manual = true) } }) { Text("التحقق من التحديثات الآن") }
            }
        }
        item {
            Section("حول التطبيق") {
                Text("نظام الأرشفة الإلكتروني — نسخة الهاتف. يعمل محلياً بالكامل، أو يتصل بنظام سطح المكتب من شاشة الدخول.", fontSize = 13.sp)
                Credits()
            }
        }
    }

    restoreUri?.let { uri ->
        Confirm("ستُستبدل كل بيانات الأرشيف على هذا الهاتف بمحتوى النسخة. هل تريد المتابعة؟", "استعادة", onDismiss = { restoreUri = null }) {
            scope.act("تمت الاستعادة — سجّل الدخول من جديد", then = { Session.user = null; Nav.root(Screen.Dashboard) }) {
                ctx.contentResolver.openInputStream(uri)!!.use { Backup.restore(ctx, u, it) }
            }
        }
    }
}
