package com.zidex.edms.remote

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.zidex.edms.Api
import com.zidex.edms.BuildConfig
import com.zidex.edms.Link
import com.zidex.edms.LinkStore
import com.zidex.edms.Pins
import com.zidex.edms.R
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * «الاتصال بسطح المكتب» mode: the full desktop UI (served by the paired
 * desktop through its TLS gateway) inside a locked-down WebView, plus native
 * pieces the web page cannot do well on a phone: document capture, downloads.
 * Pairing itself happens on the login screen (ui/LoginScreen.kt).
 */
class RemoteActivity : ComponentActivity() {
    private val io = Executors.newCachedThreadPool()
    private lateinit var store: LinkStore
    private var link: Link? = null
    private var web: WebView? = null

    /** Where the next document-scanner result goes. */
    private sealed interface ScanTarget {
        class Chooser(val callback: ValueCallback<Array<Uri>>, val wantsPdf: Boolean, val multiple: Boolean) : ScanTarget
        data object Bridge : ScanTarget
    }
    private var scanTarget: ScanTarget? = null
    private var chooserCallback: ValueCallback<Array<Uri>>? = null

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val target = scanTarget.also { scanTarget = null } ?: return@registerForActivityResult
        val result = if (res.resultCode == RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(res.data) else null
        when (target) {
            is ScanTarget.Chooser -> {
                val pdf = result?.pdf?.uri
                val pages = result?.pages?.map { it.imageUri }.orEmpty()
                target.callback.onReceiveValue(
                    when {
                        result == null -> null
                        target.wantsPdf && pdf != null -> arrayOf(pdf)
                        target.multiple -> pages.toTypedArray()
                        else -> pages.take(1).toTypedArray()
                    },
                )
            }
            ScanTarget.Bridge -> result?.pages?.map { it.imageUri }?.let(::deliverPagesToPage)
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = chooserCallback.also { chooserCallback = null } ?: return@registerForActivityResult
        val data = res.data
        val uris = when {
            res.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        cb.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LinkStore(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                if (w != null && w.canGoBack()) w.goBack() else finish()
            }
        })
        val saved = store.load() ?: return finish()
        connect(saved)
    }

    private fun showStatus(text: String, retry: (() -> Unit)? = null) {
        web?.destroy()
        web = null
        setContentView(
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(48, 48, 48, 48)
                addView(TextView(context).apply { this.text = text; gravity = android.view.Gravity.CENTER; textSize = 16f })
                if (retry != null) addView(Button(context).apply { this.text = getString(R.string.retry); setOnClickListener { retry() } })
                addView(Button(context).apply { this.text = "رجوع"; setOnClickListener { finish() } })
            },
        )
    }

    private fun connect(saved: Link) {
        showStatus(getString(R.string.connecting, saved.serverName))
        io.execute {
            val host = Api.reachableHost(saved)
            runOnUiThread {
                if (host == null) showStatus(getString(R.string.unreachable, saved.serverName)) { connect(saved) }
                else {
                    val l = saved.copy(host = host)
                    store.save(l)
                    showWeb(l)
                }
            }
        }
    }

    private fun unpair() {
        store.clear()
        link = null
        CookieManager.getInstance().removeAllCookies(null)
        web?.clearCache(true)
        finish()
    }

    // ───────────────────────── WebView ─────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(l: Link) {
        link = l
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(l.origin, "edms_device=${l.token}; Path=/; Secure; HttpOnly")
            flush()
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        w.addJavascriptInterface(WebBridge(), "EdmsAndroid")
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Self-signed by design: accept ONLY the paired desktop's pinned certificate.
                if (isOurOrigin(Uri.parse(error.url)) && Pins.matches(error.certificate, l.fingerprint)) handler.proceed()
                else handler.cancel()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isOurOrigin(request.url)) return false
                openExternal(request.url) // mailto:, TikTok, YouTube, …
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val accept = params.acceptTypes.joinToString(",").lowercase()
                val wantsPdf = accept.isBlank() || "pdf" in accept || "*/*" in accept
                val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                var handled = false
                AlertDialog.Builder(this@RemoteActivity)
                    .setItems(arrayOf(getString(R.string.scan_document), getString(R.string.choose_file))) { _, which ->
                        handled = true
                        if (which == 0) startScan(ScanTarget.Chooser(callback, wantsPdf, multiple))
                        else {
                            chooserCallback = callback
                            runCatching { fileLauncher.launch(params.createIntent()) }
                                .onFailure { chooserCallback = null; callback.onReceiveValue(null) }
                        }
                    }
                    .setOnDismissListener { if (!handled) callback.onReceiveValue(null) }
                    .show()
                return true
            }
        }
        w.setDownloadListener { url, _, disposition, mime, _ -> download(url, disposition, mime) }
        web = w
        setContentView(w)
        w.loadUrl(l.origin + "/")
    }

    private fun isOurOrigin(u: Uri): Boolean {
        val l = link ?: return false
        return u.scheme == "https" && u.host == l.host && u.port == l.port
    }

    private fun openExternal(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u))
        } catch (_: ActivityNotFoundException) {
            toast(u.toString())
        }
    }

    // ───────────────────────── Document capture ─────────────────────────

    /**
     * Google's document scanner (Play services): live edge detection with
     * auto-capture once the page is steady, perspective correction, ML
     * cleanup of stains/fingers, filters, reorder and gallery import.
     */
    private fun startScan(target: ScanTarget) {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(if (target is ScanTarget.Chooser && !target.multiple && !target.wantsPdf) 1 else 50)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener {
                scanTarget = target
                scanLauncher.launch(IntentSenderRequest.Builder(it).build())
            }
            .addOnFailureListener {
                toast(getString(R.string.scanner_unavailable))
                if (target is ScanTarget.Chooser) target.callback.onReceiveValue(null)
            }
    }

    /** Scan page of the web app: hand pages back as JPEG data URLs. */
    private fun deliverPagesToPage(pages: List<Uri>) = io.execute {
        val urls = pages.mapNotNull { uri -> runCatching { jpegDataUrl(uri) }.getOrNull() }
        val js = "window.dispatchEvent(new CustomEvent('edms:native-scan',{detail:${JSONArray(urls)}}))"
        runOnUiThread { web?.evaluateJavascript(js, null) }
    }

    /** Downscale to ≤2400 px long edge (plenty for A4 at ~200 dpi) to keep uploads light. */
    private fun jpegDataUrl(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        val bmp = contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }!!
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        bmp.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ───────────────────────── Downloads ─────────────────────────

    private fun download(url: String, disposition: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, disposition, mime).replace('/', '_')
        when {
            url.startsWith("blob:") -> web?.evaluateJavascript(
                // blob: URLs live only inside the page — read them there.
                "fetch(${JSONArray(listOf(url))}[0]).then(r=>r.blob()).then(b=>{const f=new FileReader();" +
                    "f.onload=()=>EdmsAndroid.saveDataUrl(f.result,${JSONArray(listOf(name))}[0]);f.readAsDataURL(b)})",
                null,
            )
            url.startsWith("data:") -> io.execute { saveDataUrl(url, name) }
            isOurOrigin(Uri.parse(url)) -> io.execute {
                val l = link ?: return@execute
                val r = runCatching {
                    val c = Pins.open(url, l.fingerprint)
                    CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("cookie", it) }
                    c.setRequestProperty("x-edms-device", l.token)
                    val f = downloadFile(name)
                    c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                    f
                }
                runOnUiThread { r.onSuccess(::openFile).onFailure { toast(getString(R.string.download_failed)) } }
            }
            else -> openExternal(Uri.parse(url))
        }
    }

    private fun downloadFile(name: String): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!.apply { mkdirs() }
        return File(dir, name.ifBlank { "document" })
    }

    private fun saveDataUrl(dataUrl: String, name: String) {
        val r = runCatching {
            val comma = dataUrl.indexOf(',')
            require(dataUrl.startsWith("data:") && comma > 0)
            val f = downloadFile(File(name).name)
            f.writeBytes(Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT))
            f
        }
        runOnUiThread { r.onSuccess(::openFile).onFailure { toast(getString(R.string.download_failed)) } }
    }

    private fun openFile(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, f.name))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.saved_to, f.absolutePath))
        }
    }

    /** Called from the web page (window.EdmsAndroid). Keep this surface tiny. */
    inner class WebBridge {
        @JavascriptInterface
        fun scanDocument() = runOnUiThread { startScan(ScanTarget.Bridge) }

        @JavascriptInterface
        fun saveDataUrl(dataUrl: String, name: String) = io.execute { this@RemoteActivity.saveDataUrl(dataUrl, name) }
    }

    // ───────────────────────── Menu / about ─────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val paired = web != null
        menu.findItem(R.id.menu_scan).isVisible = paired
        menu.findItem(R.id.menu_reload).isVisible = paired
        menu.findItem(R.id.menu_unpair).isVisible = store.load() != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_scan -> web?.loadUrl(link!!.origin + "/scan")
            R.id.menu_reload -> web?.reload()
            R.id.menu_update -> com.zidex.edms.Updater.check(this, manual = true)
            R.id.menu_about -> showAbout()
            R.id.menu_unpair -> AlertDialog.Builder(this)
                .setMessage(R.string.unpair_confirm)
                .setPositiveButton(R.string.unpair) { _, _ -> unpair() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showAbout() {
        val v = LayoutInflater.from(this).inflate(R.layout.view_credits, null)
        bindCredits(v)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME)
            .setView(v)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun bindCredits(v: View) {
        v.findViewById<View>(R.id.dev_mail).setOnClickListener { openExternal(Uri.parse("mailto:" + getString(R.string.dev_email))) }
        v.findViewById<View>(R.id.dev_tiktok).setOnClickListener { openExternal(Uri.parse(getString(R.string.dev_tiktok_url))) }
        v.findViewById<View>(R.id.dev_youtube).setOnClickListener { openExternal(Uri.parse(getString(R.string.dev_youtube_url))) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }
}
