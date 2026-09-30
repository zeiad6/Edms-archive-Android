package com.zidex.edms.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.zidex.edms.Api
import com.zidex.edms.BuildConfig
import com.zidex.edms.Link
import com.zidex.edms.LinkStore
import com.zidex.edms.R
import com.zidex.edms.data.Passwords
import com.zidex.edms.data.Repo
import com.zidex.edms.remote.RemoteActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry screen. The user chooses how to use the app:
 *  - «على هذا الهاتف»: the full archive runs locally (own database and files);
 *  - «الاتصال بسطح المكتب»: open the paired desktop system over LAN/Internet.
 */
@Composable
fun LoginScreen(pendingPair: String?, onPairHandled: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var tab by remember { mutableIntStateOf(if (pendingPair != null) 1 else prefs.getInt("mode", 0)) }
    LaunchedEffect(pendingPair) { if (pendingPair != null) tab = 1 }

    // Dark hero + dark bottom sheet (reference: Dribbble «Document Management App UI/UX»),
    // recoloured to the archive's indigo brand.
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6366F1), onPrimary = Color.White, background = LoginBg, surface = SheetBg,
            onSurface = Color.White, onSurfaceVariant = Color(0xFF9CA3AF), error = Color(0xFFF87171),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().background(LoginBg).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.fillMaxWidth().height(300.dp)
                    .background(Brush.radialGradient(listOf(Color(0xFF4338CA), LoginBg), radius = 900f)),
            ) {
                Row(Modifier.statusBarsPadding().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    // painterResource cannot draw adaptive-icon XML, so compose the icon from its layers.
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand))
                    Spacer(Modifier.width(10.dp))
                    Text("أرشيف", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Binders(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(250.dp, 190.dp))
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(SheetBg).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("مرحباً بعودتك", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "وصول آمن إلى أرشيف مؤسستك في أي وقت ومن أي مكان.", color = Color(0xFF9CA3AF), fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
                )
                ModeSwitch(tab) { tab = it; prefs.edit().putInt("mode", it).apply() }
                Spacer(Modifier.height(18.dp))
                Column(Modifier.fillMaxWidth()) {
                    if (tab == 0) LocalLogin() else DesktopLink(pendingPair, onPairHandled)
                }
                Spacer(Modifier.height(28.dp))
                Credits(light = true)
                Text("الإصدار ${BuildConfig.VERSION_NAME}", color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp)
                Spacer(Modifier.navigationBarsPadding().height(8.dp))
            }
        }
    }
}

private val LoginBg = Color(0xFF07070C)
private val SheetBg = Color(0xFF0F0F17)
private val FieldBg = Color(0xFF1B1B26)
private val FieldShape = RoundedCornerShape(28.dp)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = FieldBg, unfocusedContainerColor = FieldBg, unfocusedBorderColor = Color.Transparent,
    focusedBorderColor = Color(0xFF6366F1), focusedTextColor = Color.White, unfocusedTextColor = Color.White,
)

/** Two-option segmented pill: run on this phone / connect to the desktop. */
@Composable
private fun ModeSwitch(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(FieldShape).background(FieldBg).padding(5.dp)) {
        listOf(Icons.Default.PhoneAndroid to "على هذا الهاتف", Icons.Default.Computer to "الاتصال بسطح المكتب").forEachIndexed { i, (icon, label) ->
            val on = selected == i
            Row(
                Modifier.weight(1f).clip(FieldShape).background(if (on) Color.White else Color.Transparent).clickable { onSelect(i) }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (on) Ink else Color(0xFF9CA3AF), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = if (on) Ink else Color(0xFF9CA3AF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Hero illustration: a shelf of archive binders, one being pulled out (drawn, no bitmap assets). */
@Composable
private fun Binders(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bw = w / 7.5f
        val shelfY = h * 0.92f
        val colors = listOf(Color(0xFF374151), Color(0xFF4B5563), Color(0xFF374151), Color(0xFF6366F1), Color(0xFF4B5563), Color(0xFF374151))
        colors.forEachIndexed { i, c ->
            val x = w * 0.08f + i * (bw + w * 0.02f)
            val lift = if (i == 3) h * 0.16f else 0f
            val top = h * 0.12f - lift
            withTransform({ rotate(if (i == 3) -8f else 0f, pivot = Offset(x + bw / 2, shelfY - lift)) }) {
                drawRoundRect(c, Offset(x, top), Size(bw, shelfY - top - lift), CornerRadius(10f))
                // spine label + finger ring
                drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(x + bw * 0.2f, top + h * 0.12f), Size(bw * 0.6f, h * 0.2f), CornerRadius(6f))
                drawCircle(Color(0xFF111827), bw * 0.16f, Offset(x + bw / 2, shelfY - lift - h * 0.14f))
            }
        }
        drawRoundRect(Color(0xFF1F2937), Offset(0f, shelfY), Size(w, h * 0.06f), CornerRadius(8f))
    }
}

@Composable
private fun LocalLogin() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // First-run hint, only while the seeded admin still has the default password.
    val firstRun = load { Repo.get(ctx).users().let { it.size == 1 && it[0].mustChangePassword } }

    Text("يعمل النظام بالكامل على الهاتف: قاعدة بيانات وملفات محلية، دون حاجة لحاسوب أو إنترنت.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        username, { username = it }, placeholder = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        shape = FieldShape, colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
    OutlinedTextField(
        password, { password = it }, placeholder = { Text("كلمة المرور") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = FieldShape, colors = fieldColors(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
    )
    Button(
        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
        onClick = {
            busy = true
            scope.launch {
                try {
                    Session.user = withContext(Dispatchers.IO) { Repo.get(ctx).login(username, password) }
                } catch (e: Exception) {
                    Session.say(errorText(e))
                } finally {
                    busy = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(56.dp),
    ) { Text(if (busy) "جارٍ التحقق…" else "تسجيل الدخول", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

    if (firstRun == true) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(18.dp)).background(FieldBg).padding(14.dp)) {
            Text("الحساب الافتراضي عند أول تشغيل", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("اسم المستخدم: admin · كلمة المرور: ${Passwords.DEFAULT_PASSWORD}", fontSize = 13.sp, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
            Text("سيُطلب تغيير كلمة المرور فور الدخول.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DesktopLink(pendingPair: String?, onPairHandled: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LinkStore(ctx) }
    var link by remember { mutableStateOf(store.load()) }
    var pasted by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<Link, String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun offer(raw: String) {
        val parsed = Link.parsePairUri(raw)
        if (parsed == null) Session.say("رابط الاقتران غير صالح") else confirm = parsed
    }
    LaunchedEffect(pendingPair) { pendingPair?.let { offer(it); onPairHandled() } }

    val current = link
    if (current != null) {
        Text("مقترن بالحاسوب: ${current.serverName}", fontWeight = FontWeight.Bold)
        Text(current.hosts.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
        Button({ ctx.startActivity(Intent(ctx, RemoteActivity::class.java)) }, Modifier.fillMaxWidth().padding(top = 14.dp).height(56.dp)) { Text("فتح نظام سطح المكتب") }
        TextButton({ store.clear(); link = null; android.webkit.CookieManager.getInstance().removeAllCookies(null) }) { Text("إلغاء الاقتران") }
        return
    }

    Text(
        "من الحاسوب: الإعدادات ← النظام ← «ربط تطبيق الجوال» ← فعّل الخدمة واضغط «إنشاء رمز اقتران جديد»، ثم امسح الرمز هنا. " +
            "الاتصال مشفّر ومثبّت على شهادة حاسوبك فقط.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = {
            GmsBarcodeScanning.getClient(ctx, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                .startScan()
                .addOnSuccessListener { b -> b.rawValue?.let(::offer) }
                .addOnFailureListener { Session.say("ماسح الرموز غير متاح — الصق رابط الاقتران") }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(56.dp),
    ) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("مسح رمز الاقتران") }
    OutlinedTextField(
        pasted, { pasted = it }, placeholder = { Text("أو الصق رابط الاقتران edms://pair?…") }, singleLine = true,
        shape = FieldShape, colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
    )
    OutlinedButton({ offer(pasted) }, Modifier.fillMaxWidth(), enabled = pasted.isNotBlank() && !busy) { Text(if (busy) "جارٍ الاقتران…" else "اقتران بالرابط") }

    confirm?.let { (candidate, code) ->
        // A pairing link can come from any app (deep link): the user confirms which desktop to trust.
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("الاقتران بهذا الحاسوب؟") },
            text = { Text("الحاسوب: ${candidate.serverName}\nالعناوين: ${candidate.hosts.joinToString("، ")}\n\nاقترن فقط إذا أنشأت هذا الرمز بنفسك من حاسوبك.") },
            confirmButton = {
                TextButton({
                    confirm = null
                    busy = true
                    scope.launch {
                        try {
                            val l = withContext(Dispatchers.IO) { Api.pair(candidate, code, "${Build.MANUFACTURER} ${Build.MODEL}") }
                            store.save(l); link = l
                            ctx.startActivity(Intent(ctx, RemoteActivity::class.java))
                        } catch (e: Exception) {
                            Session.say(e.message ?: "فشل الاقتران")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("اقتران") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("إلغاء") } },
        )
    }
}

/** Developer credits with the platform logos (e-mail, TikTok, YouTube). */
@Composable
fun Credits(light: Boolean = false) {
    val ctx = LocalContext.current
    val fg = if (light) Color.White else MaterialTheme.colorScheme.onSurface
    fun open(u: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("تم تطوير البرنامج بواسطة", color = fg.copy(alpha = 0.6f), fontSize = 12.sp)
        Text(ctx.getString(R.string.dev_name), color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(ctx.getString(R.string.dev_email), color = fg.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            IconButton({ open("mailto:" + ctx.getString(R.string.dev_email)) }) { Image(painterResource(R.drawable.ic_mail), "بريد المطور", Modifier.size(28.dp)) }
            IconButton({ open(ctx.getString(R.string.dev_tiktok_url)) }) { Image(painterResource(R.drawable.ic_tiktok), "حساب المطور على تيك توك", Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)) }
            IconButton({ open(ctx.getString(R.string.dev_youtube_url)) }) { Image(painterResource(R.drawable.ic_youtube), "قناة المطور على يوتيوب", Modifier.size(28.dp)) }
        }
    }
}

/** Forced first-login change (and voluntary change from Settings). */
@Composable
fun ChangePasswordForm(forced: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        if (forced) Text("يجب تغيير كلمة المرور الافتراضية قبل المتابعة.", color = MaterialTheme.colorScheme.error)
        if (!forced) OutlinedTextField(current, { current = it }, label = { Text("كلمة المرور الحالية") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(new, { new = it }, label = { Text("كلمة المرور الجديدة (8 أحرف على الأقل)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(again, { again = it }, label = { Text("تأكيد كلمة المرور") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                if (new != again) { Session.say("كلمتا المرور غير متطابقتين"); return@Button }
                val u = Session.user ?: return@Button
                scope.act("تم تغيير كلمة المرور", then = onDone) {
                    Session.user = Repo.get(ctx).changePassword(u, if (forced) null else current, new)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("حفظ كلمة المرور") }
    }
}
