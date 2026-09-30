package com.zidex.edms

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * In-app updates from the project's GitHub releases (same feed the desktop
 * app uses). The newest release's `*.apk` asset is downloaded with
 * DownloadManager and handed to the system installer; Android itself refuses
 * the install unless it is signed with the same key as this build.
 */
object Updater {
    private const val PREFS = "updater"
    private const val DAY_MS = 24 * 3600 * 1000L
    private val io = Executors.newSingleThreadExecutor()

    /** Automatic check at most once a day; [manual] always checks and reports "up to date". */
    fun check(activity: Activity, manual: Boolean = false) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!manual && System.currentTimeMillis() - prefs.getLong("last", 0) < DAY_MS) return
        io.execute {
            val result = runCatching {
                val rel = Api.getJson("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                val tag = rel.getString("tag_name").removePrefix("v")
                val assets = rel.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
                Triple(tag, apk?.getString("browser_download_url"), rel.optString("body"))
            }
            prefs.edit().putLong("last", System.currentTimeMillis()).apply()
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val (tag, url, notes) = result.getOrElse {
                    if (manual) toast(activity, activity.getString(R.string.update_failed))
                    return@runOnUiThread
                }
                when {
                    !isNewer(tag, BuildConfig.VERSION_NAME) ->
                        if (manual) toast(activity, activity.getString(R.string.update_none))
                    url == null ->
                        if (manual) toast(activity, activity.getString(R.string.update_no_apk, tag))
                    else -> AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.update_title, tag))
                        .setMessage(notes.take(1200).ifBlank { activity.getString(R.string.update_body) })
                        .setPositiveButton(R.string.update_install) { _, _ -> download(activity, url, tag) }
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

    private fun download(activity: Activity, url: String, tag: String) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, activity.getString(R.string.update_allow_install))
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        val dm = activity.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle(activity.getString(R.string.app_name) + " $tag")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "edms-$tag.apk"),
        )
        toast(activity, activity.getString(R.string.update_downloading))
        val app = activity.applicationContext
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id) ?: return toast(app, app.getString(R.string.update_failed))
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
}
