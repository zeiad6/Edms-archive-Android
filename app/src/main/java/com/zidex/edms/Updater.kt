package com.zidex.edms

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * In-app updates from the project's GitHub releases.
 *
 * Two signing lineages exist for `com.zidex.edms` (see [CERT_EDMS] and
 * [CERT_ZEIDEX]). Android refuses an update signed by a different key, and the
 * usual "fix" — uninstalling — deletes the internal database and every stored
 * document. So this class checks three things before the installer is involved:
 *
 *  1. it picks the release asset tagged for the certificate this build carries;
 *  2. it re-checks the downloaded APK's package, signer and versionCode;
 *  3. on a mismatch it deletes the file and explains why, instead of opening an
 *     installer that would fail and push the user towards uninstalling.
 */
object Updater {
    private const val PREFS = "updater"
    private const val DAY_MS = 24 * 3600 * 1000L
    private val io = Executors.newSingleThreadExecutor()

    // SHA-256 of the signing certificate. Certificates are public; the digest is
    // here only to recognise which lineage this installation belongs to.
    internal const val CERT_EDMS = "a341357bb1ed3fa47733079f33f9cc0cefac0e4b1624d32ea4f4fdbca2757060"
    internal const val CERT_ZEIDEX = "7c361301a64cecbc2c1941dc034753209eccc7f5211933225e43e3db4695ba0c"

    private data class Release(val tag: String, val url: String?, val notes: String, val hasApk: Boolean)

    /** Automatic check at most once a day; [manual] always checks and reports "up to date". */
    fun check(activity: Activity, manual: Boolean = false) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!manual && System.currentTimeMillis() - prefs.getLong("last", 0) < DAY_MS) return
        io.execute {
            val self = certSha256(activity)
            val result = runCatching {
                val rel = Api.getJson("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                val tag = rel.getString("tag_name").removePrefix("v")
                val assets = rel.getJSONArray("assets")
                val names = (0 until assets.length()).map { assets.getJSONObject(it).getString("name") }
                val chosen = pickApkName(names, self)
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name") == chosen }
                Release(tag, apk?.getString("browser_download_url"), rel.optString("body"), names.any { it.endsWith(".apk", true) })
            }
            prefs.edit().putLong("last", System.currentTimeMillis()).apply()
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val rel = result.getOrElse {
                    if (manual) toast(activity, activity.getString(R.string.update_failed))
                    return@runOnUiThread
                }
                when {
                    !isNewer(rel.tag, BuildConfig.VERSION_NAME) ->
                        if (manual) toast(activity, activity.getString(R.string.update_none))
                    rel.url == null ->
                        if (manual) toast(
                            activity,
                            activity.getString(
                                if (rel.hasApk) R.string.update_no_match else R.string.update_no_apk,
                                rel.tag,
                            ),
                        )
                    else -> AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.update_title, rel.tag))
                        .setMessage(
                            rel.notes.take(1200).ifBlank { activity.getString(R.string.update_body) } +
                                "\n\n" + activity.getString(R.string.update_safe_data),
                        )
                        .setPositiveButton(R.string.update_install) { _, _ -> download(activity, rel.url, rel.tag) }
                        .setNegativeButton(R.string.later, null)
                        .show()
                }
            }
        }
    }

    /** Numeric dotted compare: "1.2.10" > "1.2.9". Non-numeric parts count as 0. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val d = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /**
     * The APK whose name carries this build's signer tag. Returning null is a
     * feature: "no matching update" is better than downloading an APK the
     * installer will reject, because a rejected install is what makes users
     * uninstall and lose their archive.
     */
    internal fun pickApkName(names: List<String>, selfSha: String?): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty() || selfSha == null) return null
        return when (selfSha.lowercase()) {
            CERT_EDMS -> apks.firstOrNull { it.contains("-edms", ignoreCase = true) }
                ?: apks.firstOrNull { !it.contains("-zeidex", ignoreCase = true) }
            CERT_ZEIDEX -> apks.firstOrNull { it.contains("-zeidex", ignoreCase = true) }
            else -> null
        }
    }

    /** SHA-256 of the certificate this installation is signed with. */
    internal fun certSha256(ctx: Context): String? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        signerOf(ctx.packageManager.getPackageInfo(ctx.packageName, flags))?.let(::sha256)
    }.getOrNull()

    private fun signerOf(info: PackageInfo?): ByteArray? {
        if (info == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Apk(val packageName: String?, val versionCode: Long, val sha256: String?)

    private fun inspectApk(ctx: Context, file: File): Apk? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val info = ctx.packageManager.getPackageArchiveInfo(file.path, flags) ?: return null
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        Apk(info.packageName, code, signerOf(info)?.let(::sha256))
    }.getOrNull()

    private fun download(activity: Activity, url: String, tag: String) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, activity.getString(R.string.update_allow_install))
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        val app = activity.applicationContext
        val dm = app.getSystemService(DownloadManager::class.java)
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return toast(activity, activity.getString(R.string.update_failed))
        // Unique name: DownloadManager refuses to reuse an existing destination.
        val fileName = "edms-$tag-${System.currentTimeMillis()}.apk"
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle(activity.getString(R.string.app_name) + " $tag")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName),
        )
        toast(activity, activity.getString(R.string.update_downloading))
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id)
                val apk = inspectApk(app, File(dir, fileName))
                val self = certSha256(app)
                when {
                    uri == null || apk == null -> reject(dm, id, app, R.string.update_failed)
                    apk.packageName != app.packageName -> reject(dm, id, app, R.string.update_wrong_app)
                    self == null || apk.sha256 != self -> reject(dm, id, app, R.string.update_wrong_signer)
                    apk.versionCode <= BuildConfig.VERSION_CODE.toLong() -> reject(dm, id, app, R.string.update_wrong_version)
                    else -> app.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    /** Remove a rejected download before the installer can see it. */
    private fun reject(dm: DownloadManager, id: Long, ctx: Context, message: Int) {
        dm.remove(id)
        toast(ctx, ctx.getString(message))
    }

    private fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
}
