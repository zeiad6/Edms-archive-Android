package com.zidex.edms.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zidex.edms.data.User
import com.zidex.edms.data.UserError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-wide UI state: signed-in user, data refresh tick, snackbar message. */
object Session {
    var user by mutableStateOf<User?>(null)
    var tick by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)

    /** The drifting background motif; the settings screen can switch it off. */
    var backdrop by mutableStateOf(true)

    fun refresh() { tick++ }
    fun say(m: String) { message = m }
}

/** Load data off the main thread; reloads when [keys] or Session.tick change. Errors go to the snackbar. */
@Composable
fun <T> load(vararg keys: Any?, block: suspend () -> T): T? {
    val v by produceState<T?>(null, *keys, Session.tick) {
        value = try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e // screen left — not an error
        } catch (e: Exception) {
            Session.say(errorText(e)); null
        }
    }
    return v
}

/** Run a mutation off the main thread; on success refresh screens and optionally report. */
fun CoroutineScope.act(success: String? = null, then: () -> Unit = {}, block: suspend () -> Unit) = launch {
    try {
        withContext(Dispatchers.IO) { block() }
        Session.refresh()
        success?.let(Session::say)
        then()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Session.say(errorText(e))
    }
}

fun errorText(e: Throwable) = if (e is UserError) e.message ?: "خطأ" else "حدث خطأ: ${e.message ?: e.javaClass.simpleName}"

// Visual language: Dribbble «File Manager Mobile App: My Files» (Keitoto) — soft grey
// canvas, white 20dp cards with hairline borders, colour-coded file badges — plus a
// floating dark navigation pill and a dark hero login.
// The palette itself (Brand, Ink, CardShape, EdmsTheme) now lives in Appearance.kt.

fun parseColor(hex: String?): Color = runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748b")) }.getOrDefault(Color.Gray)

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

fun statusColor(s: String) = when (s) {
    "active" -> Color(0xFF059669); "pending_review" -> Color(0xFFD97706); "draft" -> Color(0xFF64748B); else -> Color(0xFF0EA5E9)
}

@Composable
fun Avatar(name: String, color: String, size: Int = 36) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(parseColor(color)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
fun Empty(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Dropdown for an optional id/value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(label: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T?) -> Unit, modifier: Modifier = Modifier, allowNone: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "—", onValueChange = {}, readOnly = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone) DropdownMenuItem(text = { Text("—") }, onClick = { onSelect(null); open = false })
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(v); open = false }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipsSelect(options: List<Pair<T, String>>, selected: Set<T>, onToggle: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, l) -> FilterChip(selected = v in selected, onClick = { onToggle(v) }, label = { Text(l) }) }
    }
}

@Composable
fun Confirm(text: String, confirm: String = "تأكيد", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** White card: 20dp corners, hairline border (the reference design's basic surface). */
fun Modifier.card(onClick: (() -> Unit)? = null): Modifier = composed {
    fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.card().padding(16.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

/** "Recently opened ........ View all →" */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (action != null) Text(
            "$action ←", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp),
        )
    }
}

/** Rounded search field in the reference style (white, hairline border, magnifier). */
@Composable
fun SearchPill(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, readOnlyClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().height(50.dp).clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (readOnlyClick != null) Modifier.clickable(onClick = readOnlyClick) else Modifier).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            if (readOnlyClick == null) BasicTextField(
                value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Colour-coded file-type badge (PDF red, image blue, Word indigo, Excel green…). */
@Composable
fun FileBadge(mime: String, ext: String?, size: Int = 44) {
    val (label, color) = fileKind(mime, ext)
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.5).dp)).background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontWeight = FontWeight.ExtraBold, fontSize = (size / 4).sp, maxLines = 1)
    }
}

fun fileKind(mime: String, ext: String?): Pair<String, Color> {
    val e = (ext ?: mime.substringAfter('/')).lowercase()
    return when {
        mime == "application/pdf" || e == "pdf" -> "PDF" to Color(0xFFE11D48)
        mime.startsWith("image/") -> "IMG" to Color(0xFF2563EB)
        e in setOf("doc", "docx", "rtf", "odt") -> "DOC" to Color(0xFF4F46E5)
        e in setOf("xls", "xlsx", "csv", "ods") -> "XLS" to Color(0xFF16A34A)
        e in setOf("ppt", "pptx") -> "PPT" to Color(0xFFEA580C)
        e == "txt" -> "TXT" to Color(0xFF64748B)
        else -> e.take(3).uppercase().ifBlank { "FILE" } to Color(0xFF64748B)
    }
}

/** Coloured circle with an icon (category tiles). */
@Composable
fun IconCircle(icon: ImageVector, color: Color, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.5).dp))
    }
}

/** Circular outlined icon button (header actions in the reference design). */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, badge: Int = 0, onClick: () -> Unit) {
    Box {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, description, modifier = Modifier.size(20.dp)) }
        if (badge > 0) Box(
            Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE11D48)),
            contentAlignment = Alignment.Center,
        ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun KeyValue(k: String, v: String?) {
    if (v.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(v, modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun ClickRow(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(modifier.card(onClick).padding(14.dp)) { content() }

fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
    else -> "%.2f GB".format(n / 1073741824.0)
}

/** "2026-09-30T10:15:00Z" / "2026-09-30 10:15:00" → "2026-09-30 10:15" */
fun shortDate(s: String?) = s?.replace('T', ' ')?.take(16) ?: ""

fun Context.toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
