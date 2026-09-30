package com.zidex.edms.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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

    BackHandler(enabled = drawer.isOpen || !atRoot) {
        if (drawer.isOpen) scope.launch { drawer.close() } else Nav.back()
    }

    val items = buildList<Triple<Screen, ImageVector, Boolean>> {
        add(Triple(Screen.Dashboard, Icons.Default.Home, true))
        add(Triple(Screen.Documents(), Icons.Default.Description, true))
        add(Triple(Screen.Scan, Icons.Default.DocumentScanner, Perm.can(user, "documents.create")))
        add(Triple(Screen.Approvals, Icons.Default.TaskAlt, true))
        add(Triple(Screen.Notifications, Icons.Default.Notifications, true))
        add(Triple(Screen.Reports, Icons.Default.BarChart, Perm.can(user, "reports.view")))
        add(Triple(Screen.Folders, Icons.Default.Folder, true))
        add(Triple(Screen.Departments, Icons.Default.Apartment, true))
        add(Triple(Screen.Tags, Icons.AutoMirrored.Filled.Label, Perm.can(user, "tags.manage")))
        add(Triple(Screen.DocTypes, Icons.Default.Category, Perm.can(user, "doc-types.manage")))
        add(Triple(Screen.Templates, Icons.Default.ViewQuilt, true))
        add(Triple(Screen.Users, Icons.Default.People, Perm.can(user, "users.view")))
        add(Triple(Screen.Audit, Icons.Default.History, Perm.can(user, "audit.view")))
        add(Triple(Screen.Trash, Icons.Default.Delete, Perm.can(user, "trash.view")))
        add(Triple(Screen.Settings, Icons.Default.Settings, true))
    }.filter { it.third }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Row(Modifier.card().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(user.name, user.avatarColor, 46)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(user.name, fontWeight = FontWeight.Bold)
                            Text(com.zidex.edms.data.ROLE_LABELS[user.role] ?: user.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    items.forEach { (s, icon, _) ->
                        NavigationDrawerItem(
                            label = { Text(s.title) }, icon = { Icon(icon, null) },
                            selected = screen::class == s::class,
                            onClick = { Nav.root(s); scope.launch { drawer.close() } },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Ink, selectedTextColor = Color.White, selectedIconColor = Color.White,
                            ),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text("تسجيل الخروج") }, icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) }, selected = false,
                        onClick = { logout = true },
                    )
                    Spacer(Modifier.height(16.dp))
                    Credits()
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
