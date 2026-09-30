package com.zidex.edms.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zidex.edms.data.Department
import androidx.compose.ui.unit.sp
import com.zidex.edms.data.DocFilter
import com.zidex.edms.data.Document
import com.zidex.edms.data.Perm
import com.zidex.edms.data.Repo
import com.zidex.edms.data.STATUS_LABELS
import com.zidex.edms.data.Storage
import androidx.compose.foundation.layout.fillMaxSize

/** Bottom padding so the last card clears the floating navigation pill. */
val NavClearance = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 120.dp)

@Composable
fun DashboardScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val repo = Repo.get(ctx)
    val st = load { repo.stats(u) } ?: return Loading()
    val types = load { repo.docTypes() }.orEmpty()
    val unread = load(u.id) { repo.unreadCount(u) } ?: 0
    val upload = rememberUpload()

    LazyColumn(Modifier.imeScrollSafe(), contentPadding = NavClearance, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header: title + greeting, round actions (reference: "My Files ⚙")
        item {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("أرشيفي", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("مرحباً، ${u.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                RoundIconButton(Icons.Default.Notifications, "الإشعارات", badge = unread) { Nav.go(Screen.Notifications) }
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Default.Settings, "الإعدادات") { Nav.go(Screen.Settings) }
            }
        }
        item { SearchPill("", {}, "ابحث في المستندات…", readOnlyClick = { Nav.root(Screen.Documents()) }) }

        // Archive overview card: total + segmented status bar + legend (reference: "Total storage")
        item {
            Column(Modifier.card { Nav.root(Screen.Documents()) }.padding(18.dp)) {
                Text("إجمالي الأرشيف", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${st.total}", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("  مستند · ${formatBytes(st.totalSize)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                SegmentedBar(st.byStatus.map { (k, n) -> n to statusColor(k) })
                Spacer(Modifier.height(14.dp))
                st.byStatus.forEach { (k, n) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { Nav.root(Screen.Documents(DocFilter(status = k))) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(statusColor(k)))
                        Spacer(Modifier.width(10.dp))
                        Text(STATUS_LABELS[k] ?: k, Modifier.weight(1f), fontSize = 14.sp)
                        Text("$n", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Quick actions
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (Perm.can(u, "documents.create")) {
                    QuickAction("مسح ضوئي", Icons.Default.DocumentScanner, Brand, Modifier.weight(1f)) { Nav.go(Screen.Scan) }
                    QuickAction("رفع ملف", Icons.Default.UploadFile, Color(0xFF2563EB), Modifier.weight(1f), upload)
                }
                QuickAction(
                    if (st.pendingApprovals > 0) "موافقات (${st.pendingApprovals})" else "الموافقات",
                    Icons.Default.TaskAlt, Color(0xFFF59E0B), Modifier.weight(1f),
                ) { Nav.root(Screen.Approvals) }
            }
        }

        // Recently added (reference: "Recently opened → View all")
        if (st.recent.isNotEmpty()) {
            item { SectionHeader("أحدث المستندات", "عرض الكل") { Nav.root(Screen.Documents()) } }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(st.recent, key = { it.id }) { d -> RecentCard(d) }
                }
            }
        }

        // Categories grid (reference: coloured circle tiles, "835 item • 1.8 MB")
        if (types.isNotEmpty()) {
            item { SectionHeader("التصنيفات") }
            val counts = st.byType.toMap()
            items(types.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { t ->
                        Column(Modifier.weight(1f).card { Nav.root(Screen.Documents(DocFilter(docType = t.name))) }.padding(16.dp)) {
                            IconCircle(typeIcon(t.name), parseColor(t.color))
                            Spacer(Modifier.height(14.dp))
                            Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${counts[t.name] ?: 0} مستند • ${formatBytes(st.typeSizes[t.name] ?: 0)}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.card(onClick).padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconCircle(icon, color, 42)
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RecentCard(d: Document) {
    Column(Modifier.width(158.dp).card { Nav.go(Screen.DocView(d.id)) }.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileBadge(d.mimeType, d.fileExt, 42)
            Spacer(Modifier.weight(1f))
            if (d.confidential) Icon(Icons.Default.Lock, "سري", Modifier.size(16.dp), tint = Color(0xFF7C3AED))
        }
        Spacer(Modifier.height(14.dp))
        Text(d.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(40.dp))
        Text("${d.docType ?: "مستند"} • ${formatBytes(d.fileSize)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Multi-colour bar, one segment per non-empty group (reference: storage bar). */
@Composable
fun SegmentedBar(parts: List<Pair<Int, Color>>) {
    val shown = parts.filter { it.first > 0 }
    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (shown.isEmpty()) Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        shown.forEach { (n, c) -> Box(Modifier.weight(n.toFloat()).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c)) }
    }
}

fun typeIcon(name: String): ImageVector = when (name) {
    "قرار إداري" -> Icons.Default.Gavel
    "فاتورة" -> Icons.Default.Receipt
    "عقد" -> Icons.Default.Handshake
    "محضر اجتماع" -> Icons.Default.Groups
    "تقرير" -> Icons.Default.Assessment
    "مراسلة رسمية" -> Icons.Default.Mail
    "نموذج" -> Icons.Default.Assignment
    "صورة ضوئية" -> Icons.Default.DocumentScanner
    else -> Icons.Default.Description
}

@Composable
fun Bar(label: String, n: Int, total: Int, color: Color, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row { Text(label, Modifier.weight(1f), fontSize = 13.sp); Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else n.toFloat() / total }, color = color, trackColor = color.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
fun DocumentsScreen(initial: DocFilter) {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    var filter by remember(initial) { mutableStateOf(initial) }
    val repo = Repo.get(ctx)
    val types = load { repo.docTypes() }.orEmpty()
    val depts = load { repo.departments() }.orEmpty()
    val tags = load { repo.tags() }.orEmpty()
    val docs = load(filter) { repo.documents(u, filter) }
    // Cards or a table: the table is denser and sortable, which is what a
    // registry clerk wants when working through a long list. The choice sticks.
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var table by remember { mutableStateOf(prefs.getBoolean("docsTable", false)) }
    val chip = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surface, selectedContainerColor = Ink, selectedLabelColor = Color.White,
    )

    Column {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("المستندات", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            RoundIconButton(
                if (table) Icons.Default.ViewAgenda else Icons.Default.TableRows,
                if (table) "عرض كبطاقات" else "عرض كجدول",
            ) { table = !table; prefs.edit().putBoolean("docsTable", table).apply() }
            Spacer(Modifier.width(8.dp))
            if (Perm.can(u, "documents.create")) RoundIconButton(Icons.Default.UploadFile, "رفع ملف", onClick = rememberUpload())
        }
        SearchPill(filter.query, { filter = filter.copy(query = it) }, "بحث بالعنوان أو الرقم أو المحتوى…", Modifier.padding(16.dp, 10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STATUS_LABELS.forEach { (k, l) ->
                FilterChip(filter.status == k, { filter = filter.copy(status = if (filter.status == k) null else k) }, { Text(l) }, colors = chip)
            }
            types.forEach { t ->
                FilterChip(filter.docType == t.name, { filter = filter.copy(docType = if (filter.docType == t.name) null else t.name) }, { Text(t.name) }, colors = chip)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            depts.forEach { d ->
                FilterChip(filter.departmentId == d.id, { filter = filter.copy(departmentId = if (filter.departmentId == d.id) null else d.id) }, { Text(d.name) }, colors = chip)
            }
            tags.forEach { t ->
                FilterChip(filter.tagId == t.id, { filter = filter.copy(tagId = if (filter.tagId == t.id) null else t.id) }, { Text("#${t.name}") }, colors = chip)
            }
        }
        when {
            docs == null -> Loading()
            docs.isEmpty() -> Empty("لا توجد مستندات مطابقة")
            table -> DocTable(docs, depts, Modifier.weight(1f))
            else -> LazyColumn(Modifier.imeScrollSafe(), contentPadding = NavClearance, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("${docs.size} مستند", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(docs, key = { it.id }) { DocRow(it) }
            }
        }
    }
}

@Composable
fun DocRow(d: Document) {
    ClickRow(onClick = { Nav.go(Screen.DocView(d.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(d, 50)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.confidential) Icon(Icons.Default.Lock, "سري", Modifier.size(14.dp).padding(end = 2.dp), tint = Color(0xFF7C3AED))
                    Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(d.docNumber, d.docType, formatBytes(d.fileSize), shortDate(d.docDate ?: d.createdAt).take(10)).joinToString(" • "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Pill(STATUS_LABELS[d.status] ?: d.status, statusColor(d.status))
        }
    }
}

/**
 * Denser registry view: sortable columns instead of cards. Sorting is done in
 * memory on the already-permission-filtered list, so it can never surface a
 * document the user is not allowed to see.
 */
private enum class DocColumn(val label: String) {
    TITLE("المستند"), NUMBER("الرقم"), TYPE("النوع"), DEPT("القسم"),
    STATUS("الحالة"), SIZE("الحجم"), DATE("التاريخ"),
}

@Composable
fun DocTable(docs: List<Document>, depts: List<Department>, modifier: Modifier = Modifier) {
    var sortBy by remember { mutableStateOf(DocColumn.DATE) }
    var asc by remember { mutableStateOf(false) }

    fun sort(list: List<Document>): List<Document> {
        val cmp: Comparator<Document> = when (sortBy) {
            DocColumn.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: Document -> it.title }
            DocColumn.NUMBER -> compareBy(nullsLast()) { it: Document -> it.docNumber }
            DocColumn.TYPE -> compareBy(nullsLast()) { it: Document -> it.docType }
            DocColumn.DEPT -> compareBy(nullsLast(naturalOrder())) { it: Document -> it.departmentId }
            DocColumn.STATUS -> compareBy { it: Document -> it.status }
            DocColumn.SIZE -> compareBy { it: Document -> it.fileSize }
            DocColumn.DATE -> compareBy { it: Document -> it.docDate ?: it.createdAt }
        }
        return if (asc) list.sortedWith(cmp) else list.sortedWith(cmp.reversed())
    }

    val total = docs.sumOf { it.fileSize }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${docs.size} مستند · ${formatBytes(total)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.UnfoldMore, "عكس الترتيب", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("ترتيب", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Header row doubles as the sort control.
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DocColumn.entries.forEach { c ->
                val active = c == sortBy
                Row(
                    Modifier.weight(columnWeight(c)).clickable { if (active) asc = !asc else { sortBy = c; asc = true } }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        c.label,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (active) Icon(
                        if (asc) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        val rows = remember(docs, sortBy, asc) { sort(docs) }
        LazyColumn(Modifier.imeScrollSafe(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 120.dp)) {
            items(rows, key = { it.id }) { d ->
                val name = depts.firstOrNull { it.id == d.departmentId }?.name ?: "—"
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { Nav.go(Screen.DocView(d.id)) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(columnWeight(DocColumn.TITLE)), verticalAlignment = Alignment.CenterVertically) {
                        FileBadge(d.mimeType, d.fileExt, 26)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                d.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                            if (d.confidential) Text("سري", fontSize = 10.sp, color = Color(0xFF7C3AED))
                        }
                    }
                    Cell(d.docNumber, DocColumn.NUMBER)
                    Cell(d.docType, DocColumn.TYPE)
                    Cell(name, DocColumn.DEPT)
                    Pill(STATUS_LABELS[d.status] ?: d.status, statusColor(d.status), Modifier.weight(columnWeight(DocColumn.STATUS)))
                    Cell(formatBytes(d.fileSize), DocColumn.SIZE)
                    Cell(shortDate(d.docDate ?: d.createdAt).take(10), DocColumn.DATE)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Title gets the wide column; everything else is a fixed slice. */
private fun columnWeight(c: DocColumn) = when (c) {
    DocColumn.TITLE -> 2.6f
    DocColumn.NUMBER -> 1f
    DocColumn.TYPE -> 1.2f
    DocColumn.DEPT -> 1.2f
    DocColumn.STATUS -> 1.1f
    DocColumn.SIZE -> 0.9f
    DocColumn.DATE -> 1f
}

@Composable
private fun RowScope.Cell(text: String?, column: DocColumn) {
    Text(
        text ?: "—",
        modifier = Modifier.weight(columnWeight(column)).padding(horizontal = 4.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Stored thumbnail for images/PDFs, otherwise the colour-coded type badge. */
@Composable
fun Thumb(d: Document, size: Int) {
    val ctx = LocalContext.current
    val bmp = load(d.thumbKey) { d.thumbKey?.let { Storage.decodeSampled(Storage.file(ctx, it), size * 3) } }
    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.5).dp)))
    else FileBadge(d.mimeType, d.fileExt, size)
}

@Composable
fun ReportsScreen() {
    val ctx = LocalContext.current
    val u = Session.user ?: return
    val st = load { Repo.get(ctx).stats(u) } ?: return Loading()
    LazyColumn(Modifier.imeScrollSafe(), contentPadding = NavClearance, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Section("حسب الحالة") { SegmentedBar(st.byStatus.map { (k, n) -> n to statusColor(k) }); Spacer(Modifier.height(8.dp)); st.byStatus.forEach { (k, n) -> Bar(STATUS_LABELS[k] ?: k, n, st.total, statusColor(k)) } } }
        item { Section("حسب القسم") { st.byDepartment.forEach { (k, n) -> Bar(k, n, st.total, Brand) } } }
        item { Section("حسب النوع") { st.byType.forEach { (k, n) -> Bar("$k — ${formatBytes(st.typeSizes[k] ?: 0)}", n, st.total, Color(0xFF7C3AED)) { Nav.go(Screen.Documents(DocFilter(docType = k))) } } } }
        item { Section("الإيداع الشهري (آخر 6 أشهر)") { st.byMonth.forEach { (k, n) -> Bar(k, n, st.byMonth.maxOfOrNull { it.second } ?: 0, Color(0xFF16A34A)) } } }
    }
}
