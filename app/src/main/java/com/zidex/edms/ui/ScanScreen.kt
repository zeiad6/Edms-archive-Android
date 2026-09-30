package com.zidex.edms.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.zidex.edms.data.DocMeta
import com.zidex.edms.data.Storage
import com.zidex.edms.scan.Enhance
import com.zidex.edms.scan.JpegPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.material.icons.automirrored.filled.RotateRight

private class Page(val uri: Uri, val preview: Bitmap, val blank: Boolean, rotation: Int = 0) {
    var rotation by mutableStateOf(rotation)
}

/**
 * Camera capture → review → deposit.
 *
 * Capture: Google's document scanner (live edge detection, auto-capture when
 * the page is steady, perspective correction, stain/finger removal).
 * Review adds what the vFlat analysis (Android/REPORT_ar.md) showed is worth
 * having and the scanner lacks: a document enhancement filter
 * (paper-colour division, black point, whitening, sharpening), blank-page
 * detection (the "white paper" shortcut), and barcode → document number.
 */
@Composable
fun ScanScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<Page>() }
    var mode by remember { mutableStateOf(Enhance.Mode.ENHANCE) }
    var barcode by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val result = if (res.resultCode == Activity.RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(res.data) else null
        val uris = result?.pages?.map { it.imageUri }.orEmpty()
        if (uris.isEmpty()) { if (pages.isEmpty()) Nav.back(); return@rememberLauncherForActivityResult }
        busy = "جارٍ تحليل الصفحات…"
        scope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val preview = decode(ctx, uri, 700)!!
                        Page(uri, preview, Enhance.isBlank(preview))
                    }
                }
                pages.addAll(added)
                if (barcode == null) barcode = withContext(Dispatchers.IO) { added.firstNotNullOfOrNull { detectBarcode(ctx, it.uri) } }
            } catch (e: Exception) {
                Session.say(errorText(e))
            } finally {
                busy = null
            }
        }
    }

    fun startScanner() {
        val activity = ctx as? Activity ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { Session.say("ماسح المستندات يتطلب خدمات Google Play المحدّثة") }
    }

    LaunchedEffect(Unit) { if (pages.isEmpty()) startScanner() }

    if (pages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                busy?.let { Text(it); LinearProgressIndicator(Modifier.padding(top = 8.dp)) }
                    ?: Button({ startScanner() }) { Icon(Icons.Default.DocumentScanner, null); Text("  بدء المسح بالكاميرا") }
            }
        }
        return
    }

    val blankCount = pages.count { it.blank }
    Column {
        busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Enhance.Mode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, { Text(m.label) }) }
        }
        barcode?.let { Text("رقم مستند مقروء من الباركود: $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 14.dp)) }
        if (blankCount > 0) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("اكتُشفت $blankCount صفحة فارغة", Modifier.weight(1f), color = Color(0xFFD97706), fontSize = 13.sp)
            OutlinedButton({ pages.removeAll { it.blank } }) { Text("حذف الفارغة") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(pages, key = { _, p -> p.uri.toString() }) { i, p ->
                val shown = load(p.uri, p.rotation, mode) {
                    Enhance.apply(if (p.rotation == 0) p.preview else Enhance.rotate(p.preview, p.rotation), mode)
                }
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("صفحة ${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (p.blank) Pill("فارغة", Color(0xFFD97706))
                        IconButton({ p.rotation = (p.rotation + 90) % 360 }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "تدوير") }
                        IconButton({ if (i > 0) pages.add(i - 1, pages.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "أعلى") }
                        IconButton({ if (i < pages.lastIndex) pages.add(i + 1, pages.removeAt(i)) }, enabled = i < pages.lastIndex) { Icon(Icons.Default.ArrowDownward, "أسفل") }
                        IconButton({ pages.removeAt(i) }) { Icon(Icons.Default.Delete, "حذف") }
                    }
                    shown?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().background(Color.White)) }
                        ?: Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { LinearProgressIndicator() }
                }
            }
        }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ startScanner() }, Modifier.weight(1f), enabled = busy == null) { Text("إضافة صفحات") }
            Button(
                enabled = busy == null,
                onClick = {
                    busy = "جارٍ تجهيز المستند…"
                    val snapshot = pages.map { it.uri to it.rotation }
                    scope.launch {
                        try {
                            val stored = withContext(Dispatchers.IO) { buildDocument(ctx, snapshot, mode) }
                            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            Nav.replace(Screen.DocEdit(stored = stored, prefill = DocMeta(
                                title = "مستند ممسوح $stamp", docNumber = barcode, docType = "صورة ضوئية", source = SCAN_SOURCE,
                            )))
                        } catch (e: Exception) {
                            Session.say(errorText(e))
                        } finally {
                            busy = null
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("متابعة (${pages.size})") }
        }
    }
}

/** Full-resolution pass: rotate, enhance, JPEG; one page → image document, several → one PDF (like the desktop). */
private fun buildDocument(ctx: android.content.Context, pages: List<Pair<Uri, Int>>, mode: Enhance.Mode): Storage.Stored {
    val jpegs = pages.map { (uri, rot) ->
        var b = decode(ctx, uri, 2200) ?: throw IllegalStateException("تعذرت قراءة الصفحة")
        if (rot != 0) b = Enhance.rotate(b, rot)
        val e = Enhance.apply(b, mode)
        JpegPdf.Page(Enhance.jpeg(e, 85), e.width, e.height).also { if (e !== b) e.recycle(); b.recycle() }
    }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    return if (jpegs.size == 1) Storage.storeBytes(ctx, jpegs[0].jpeg, "scan-$stamp.jpg", "image/jpeg")
    else Storage.storeBytes(ctx, JpegPdf.build(jpegs), "scan-$stamp.pdf", "application/pdf")
}

private fun decode(ctx: android.content.Context, uri: Uri, maxSide: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
}

/** First barcode / QR on the page → document number (desktop does the same in its scanner). */
private fun detectBarcode(ctx: android.content.Context, uri: Uri): String? = runCatching {
    val bmp = decode(ctx, uri, 1600) ?: return null
    val codes = Tasks.await(BarcodeScanning.getClient().process(InputImage.fromBitmap(bmp, 0)))
    codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf { v -> v.isNotEmpty() && v.length <= 64 } }
}.getOrNull()
