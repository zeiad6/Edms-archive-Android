package com.zidex.edms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zidex.edms.data.Approval
import com.zidex.edms.data.Perm
import com.zidex.edms.data.Repo

private val APPROVAL_LABELS = mapOf("pending" to "معلق", "approved" to "موافق عليه", "rejected" to "مرفوض")
private fun approvalColor(s: String) = when (s) { "approved" -> Color(0xFF059669); "rejected" -> Color(0xFFDC2626); else -> Color(0xFFD97706) }

@Composable
fun ApprovalsScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val canManage = Perm.can(u, "approvals.manage")
    var tab by remember { mutableIntStateOf(if (canManage) 0 else 1) }
    val list = load(tab) { Repo.get(ctx).approvals(u, mine = tab == 1) }
    Column {
        TabRow(selectedTabIndex = if (canManage) tab else 0) { // staff see only the «طلباتي» tab
            if (canManage) Tab(tab == 0, { tab = 0 }, text = { Text(if (u.role == "admin") "كل الطلبات" else "المسندة إليّ") })
            Tab(tab == 1, { tab = 1 }, text = { Text("طلباتي") })
        }
        when {
            list == null -> Loading()
            list.isEmpty() -> Empty("لا توجد طلبات موافقة")
            else -> LazyColumn(Modifier.imeScrollSafe(), contentPadding = NavClearance, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { ApprovalRow(it, compact = false) }
            }
        }
    }
}

@Composable
fun ApprovalRow(a: Approval, compact: Boolean) {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val scope = rememberCoroutineScope()
    var respond by remember { mutableStateOf<Boolean?>(null) }
    var note by remember { mutableStateOf("") }
    val mayRespond = a.status == "pending" && Perm.can(u, "approvals.manage") && (u.role == "admin" || a.assignedToId == u.id)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (compact) Color.Transparent else MaterialTheme.colorScheme.surface).padding(if (compact) 0.dp else 12.dp),
    ) {
        Row {
            Column(Modifier.weight(1f)) {
                if (!compact) TextButton({ Nav.go(Screen.DocView(a.documentId)) }) { Text(a.docTitle, fontWeight = FontWeight.Bold) }
                Text("من ${a.requesterName} إلى ${a.assigneeName} · ${shortDate(a.createdAt)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill(APPROVAL_LABELS[a.status] ?: a.status, approvalColor(a.status))
        }
        a.comment?.let { Text("الطلب: $it", fontSize = 13.sp) }
        a.responseNote?.let { Text("الرد: $it", fontSize = 13.sp) }
        if (mayRespond) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button({ respond = true }) { Text("موافقة") }
            OutlinedButton({ respond = false }) { Text("رفض") }
        }
    }
    respond?.let { approve ->
        AlertDialog(
            onDismissRequest = { respond = null },
            title = { Text(if (approve) "الموافقة على المستند" else "رفض المستند") },
            text = { OutlinedTextField(note, { note = it }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton({
                    respond = null
                    scope.act(if (approve) "تمت الموافقة" else "تم الرفض") { Repo.get(ctx).respond(u, a.id, approve, note) }
                }) { Text("تأكيد") }
            },
            dismissButton = { TextButton({ respond = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
fun NotificationsScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val scope = rememberCoroutineScope()
    val list = load { Repo.get(ctx).notices(u) }
    // Opening the list marks everything read (the desktop does the same on its page).
    LaunchedEffect(Unit) { scope.act { Repo.get(ctx).markAllRead(u) } }
    when {
        list == null -> Loading()
        list.isEmpty() -> Empty("لا توجد إشعارات")
        else -> LazyColumn(Modifier.imeScrollSafe(), contentPadding = NavClearance, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { n ->
                ClickRow(onClick = { n.documentId?.let { Nav.go(Screen.DocView(it)) } }) {
                    Column {
                        Text(n.title, fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold)
                        Text(n.message, fontSize = 13.sp)
                        Text(shortDate(n.createdAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
