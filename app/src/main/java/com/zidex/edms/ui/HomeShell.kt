package com.zidex.edms.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zidex.edms.data.DocFilter
import com.zidex.edms.data.DocMeta
import com.zidex.edms.data.Perm
import com.zidex.edms.data.Repo
import com.zidex.edms.data.Storage
import kotlinx.coroutines.launch

sealed class Screen(val title: String) {
    data object Dashboard : Screen("لوحة التحكم")
    data class Documents(val filter: DocFilter = DocFilter()) : Screen("المستندات")
    data class DocView(val id: Int) : Screen("تفاصيل المستند")
    data class DocEdit(val id: Int? = null, val stored: Storage.Stored? = null, val prefill: DocMeta? = null) : Screen(if (id == null) "مستند جديد" else "تعديل المستند")
    data object Scan : Screen("المسح الضوئي")
    data object Approvals : Screen("الموافقات")
    data object Notifications : Screen("الإشعارات")
    data object Departments : Screen("الأقسام")
    data object Folders : Screen("المجلدات")
    data object Tags : Screen("الوسوم")
    data object DocTypes : Screen("أنواع المستندات")
    data object Templates : Screen("القوالب")
    data object Users : Screen("المستخدمون")
    data object Audit : Screen("سجل التدقيق")
    data object Trash : Screen("سلة المحذوفات")
    data object Reports : Screen("التقارير")
    data object Settings : Screen("الإعدادات")
}

/** One drawer entry: destination, icon, unread count. */
data class Item(val screen: Screen, val icon: ImageVector, val badge: Int = 0)

/** Tiny back stack — the app has one level of drill-down at most, a nav library is not worth it. */
object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Dashboard)
    val current get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun root(s: Screen) { stack.clear(); stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
}

/** Upload entry point shared by the dashboard and the documents list: pick → metadata form. */
@Composable
fun rememberUpload(): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.act {
            val stored = Storage.importUri(ctx, uri)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Nav.go(Screen.DocEdit(stored = stored, prefill = DocMeta(title = stored.originalName.substringBeforeLast('.'))))
            }
        }
    }
    return { pick.launch(arrayOf("*/*")) }
}

@Composable
fun HomeShell() {
    val ctx = LocalContext.current
    val user = Session.user ?: return
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val screen = Nav.current
    val unread = load(user.id) { Repo.get(ctx).unreadCount(user) } ?: 0
    var logout by remember { mutableStateOf(false) }
    val atRoot = Nav.stack.size == 1
    LaunchedEffect(Unit) { Session.backdrop = readBackdrop(ctx) }

    BackHandler(enabled = drawer.isOpen || !atRoot) {
        if (drawer.isOpen) scope.launch { drawer.close() } else Nav.back()
    }

    // Grouped so the drawer reads as three short sections instead of fifteen
    // equally-weighted pills. `badge` is the unread count, 0 when there is none.

    val mainItems = listOf(
        Item(Screen.Dashboard, Icons.Default.Home),
        Item(Screen.Documents(), Icons.Default.Description),
        Item(Screen.Scan, Icons.Default.DocumentScanner),
        Item(Screen.Approvals, Icons.Default.TaskAlt),
        Item(Screen.Notifications, Icons.Default.Notifications, unread),
    )
    val manageItems = buildList {
        if (Perm.can(user, "reports.view")) add(Item(Screen.Reports, Icons.Default.BarChart))
        add(Item(Screen.Folders, Icons.Default.Folder))
        add(Item(Screen.Departments, Icons.Default.Apartment))
        if (Perm.can(user, "tags.manage")) add(Item(Screen.Tags, Icons.AutoMirrored.Filled.Label))
        if (Perm.can(user, "doc-types.manage")) add(Item(Screen.DocTypes, Icons.Default.Category))
        add(Item(Screen.Templates, Icons.AutoMirrored.Filled.ViewQuilt))
        if (Perm.can(user, "users.view")) add(Item(Screen.Users, Icons.Default.People))
    }
    val systemItems = buildList {
        if (Perm.can(user, "audit.view")) add(Item(Screen.Audit, Icons.Default.History))
        if (Perm.can(user, "trash.view")) add(Item(Screen.Trash, Icons.Default.Delete))
        add(Item(Screen.Settings, Icons.Default.Settings))
    }

    val pick: (Screen) -> Unit = { target -> Nav.root(target); scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                // Cap the width so on tablets the drawer stays a drawer instead of
                // becoming the whole screen (it was overflowing and clipping icons).
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(horizontal = 12.dp),
                ) {
                    DrawerHeader(user)
                    Spacer(Modifier.height(14.dp))

                    DrawerGroup("الرئيسية", mainItems, screen, onPick = pick)
                    Spacer(Modifier.height(6.dp))
                    DrawerGroup("الإدارة", manageItems, screen, onPick = pick)
                    Spacer(Modifier.height(6.dp))
                    DrawerGroup("النظام", systemItems, screen, onPick = pick)

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))

                    DrawerRow(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        label = "تسجيل الخروج",
                        selected = false,
                        onClick = { logout = true },
                    )
                    Spacer(Modifier.height(10.dp))
                    Credits()
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Decoration sits under the content, so cards keep their contrast.
            ArchiveBackdrop(Modifier.matchParentSize(), enabled = Session.backdrop)
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                // Dashboard and Documents draw their own large headers (reference layout).
                if (screen !is Screen.Dashboard && screen !is Screen.Documents) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!atRoot) RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") { Nav.back() }
                        else RoundIconButton(Icons.Default.Menu, "القائمة") { scope.launch { drawer.open() } }
                        Spacer(Modifier.width(12.dp))
                        Text(screen.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (screen !is Screen.Notifications) RoundIconButton(Icons.Default.Notifications, "الإشعارات", badge = unread) { Nav.go(Screen.Notifications) }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (val s = screen) {
                        Screen.Dashboard -> DashboardScreen()
                        is Screen.Documents -> DocumentsScreen(s.filter)
                        is Screen.DocView -> DocumentScreen(s.id)
                        is Screen.DocEdit -> EditDocumentScreen(s.id, s.stored, s.prefill)
                        Screen.Scan -> ScanScreen()
                        Screen.Approvals -> ApprovalsScreen()
                        Screen.Notifications -> NotificationsScreen()
                        Screen.Departments -> DepartmentsScreen()
                        Screen.Folders -> FoldersScreen()
                        Screen.Tags -> TagsScreen()
                        Screen.DocTypes -> DocTypesScreen()
                        Screen.Templates -> TemplatesScreen()
                        Screen.Users -> UsersScreen()
                        Screen.Audit -> AuditScreen()
                        Screen.Trash -> TrashScreen()
                        Screen.Reports -> ReportsScreen()
                        Screen.Settings -> SettingsScreen()
                    }
                }
            }
            if (atRoot) NavPill(
                screen = screen, canScan = Perm.can(user, "documents.create"),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
                onMore = { scope.launch { drawer.open() } },
            )
        }
    }

    if (logout) Confirm("تسجيل الخروج من النظام؟", "خروج", onDismiss = { logout = false }) {
        scope.act { Repo.get(ctx).audit(user, "auth.logout", "user", user.id, "تسجيل الخروج") }
        Session.user = null
        Nav.root(Screen.Dashboard)
    }
}


/** Drawer identity card: avatar, name, role — tinted by the account's own colour. */
@Composable
private fun DrawerHeader(user: com.zidex.edms.data.User) {
    val accent = parseColor(user.avatarColor)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.22f), MaterialTheme.colorScheme.surfaceContainerHigh),
                ),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(user.name, user.avatarColor, 46)
            // A ring keyed to the account colour: the one place the user's own
            // colour is used as identity rather than as a badge.
            Box(
                Modifier.matchParentSize().clip(CircleShape)
                    .border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
            Text(
                com.zidex.edms.data.ROLE_LABELS[user.role] ?: user.role,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DrawerGroup(title: String, items: List<Item>, current: Screen, onPick: (Screen) -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, bottom = 4.dp, top = 2.dp),
    )
    items.forEach { DrawerRow(it.icon, it.screen.title, current::class == it.screen::class, { onPick(it.screen) }, it.badge) }
}

/**
 * One drawer row. The old version used a Material NavigationDrawerItem with a
 * full-width pill container, which at 15 entries produced oversized blocks whose
 * rounded corners visually collided. This is a compact 46dp row with a small
 * leading indicator instead, and it takes its colours from the theme so it works
 * in both schemes (the old one hard-coded a near-black container and white text,
 * which vanished in light mode).
 */
@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, scheme.primary.copy(alpha = 0.25f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                .background(if (selected) scheme.primary.copy(alpha = 0.18f) else scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge > 0) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(scheme.error),
                contentAlignment = Alignment.Center,
            ) { Text(if (badge > 9) "9+" else "$badge", color = scheme.onError, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * Floating dark navigation pill (reference: bottom bar of the QClay / WorkONE shots):
 * home · documents · [scan] · approvals · more. The selected tab is a white disc.
 */
@Composable
private fun NavPill(screen: Screen, canScan: Boolean, modifier: Modifier, onMore: () -> Unit) {
    val shape = RoundedCornerShape(36.dp)
    Row(
        modifier.shadow(18.dp, shape).clip(shape).background(Ink).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        PillTab(Icons.Default.Home, "الرئيسية", screen is Screen.Dashboard) { Nav.root(Screen.Dashboard) }
        PillTab(Icons.Default.Description, "المستندات", screen is Screen.Documents) { Nav.root(Screen.Documents()) }
        if (canScan) Box(
            Modifier.size(58.dp).clip(CircleShape).background(Brand).clickable { Nav.go(Screen.Scan) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.DocumentScanner, "مسح مستند", tint = Color.White, modifier = Modifier.size(26.dp)) }
        PillTab(Icons.Default.TaskAlt, "الموافقات", screen is Screen.Approvals) { Nav.root(Screen.Approvals) }
        PillTab(Icons.Default.Menu, "المزيد", false, onMore)
    }
}

@Composable
private fun PillTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (selected) Color.White else Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (selected) Ink else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp)) }
}

@Composable
fun AddFab(onClick: () -> Unit) = Box(
    Modifier.size(56.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(Ink).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Icon(Icons.Default.Add, "إضافة", tint = Color.White) }
