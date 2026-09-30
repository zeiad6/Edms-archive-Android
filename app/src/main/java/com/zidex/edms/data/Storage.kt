package com.zidex.edms.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.security.SecureRandom
import java.util.Calendar

/**
 * File storage with the desktop's layout (`storage/documents/YYYY/MM/<hex>.<ext>`,
 * thumbnails under `storage/thumbnails/...jpg`), so backups move both ways.
 */
object Storage {
    fun root(ctx: Context) = File(ctx.filesDir, "storage")

    /** Resolve a storage key, refusing anything that escapes the storage root. */
    fun file(ctx: Context, key: String): File {
        val root = root(ctx).canonicalFile
        val f = File(root, key).canonicalFile
        require(f.path.startsWith(root.path + File.separator)) { "bad storage key" }
        return f
    }

    fun genKey(ext: String): String {
        val id = ByteArray(10).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val c = Calendar.getInstance()
        return "documents/${c.get(Calendar.YEAR)}/${"%02d".format(c.get(Calendar.MONTH) + 1)}/$id.${ext.lowercase()}"
    }

    data class Stored(val key: String, val originalName: String, val mime: String, val ext: String, val size: Long, val pages: Int, val thumbKey: String?)

    /** Copy a picked/scanned file into storage and derive its metadata. */
    fun importUri(ctx: Context, uri: Uri, nameHint: String? = null): Stored {
        val cr = ctx.contentResolver
        val displayName = nameHint ?: cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "document"
        val mime = cr.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(displayName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val ext = displayName.substringAfterLast('.', "").lowercase().ifBlank {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        }
        val key = genKey(ext)
        val dest = file(ctx, key).apply { parentFile?.mkdirs() }
        cr.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        return Stored(key, displayName, mime, ext, dest.length(), pageCount(dest, mime), makeThumb(ctx, key, mime))
    }

    /** Store generated content (scanned JPEG/PDF). */
    fun storeBytes(ctx: Context, bytes: ByteArray, name: String, mime: String): Stored {
        val ext = name.substringAfterLast('.', "bin").lowercase()
        val key = genKey(ext)
        val dest = file(ctx, key).apply { parentFile?.mkdirs() }
        dest.writeBytes(bytes)
        return Stored(key, name, mime, ext, dest.length(), pageCount(dest, mime), makeThumb(ctx, key, mime))
    }

    fun pageCount(f: File, mime: String): Int =
        if (mime != "application/pdf") 1
        else runCatching { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } } }.getOrDefault(1)

    /** 400px JPEG preview for images and the first PDF page (same key scheme as the desktop). */
    fun makeThumb(ctx: Context, key: String, mime: String): String? = runCatching {
        val src = file(ctx, key)
        val bmp = when {
            mime.startsWith("image/") -> decodeSampled(src, 400)
            mime == "application/pdf" -> renderPdfPage(src, 0, 400)
            else -> null
        } ?: return null
        val thumbKey = key.replaceFirst(Regex("^documents/"), "thumbnails/").replace(Regex("\\.[^.]+$"), ".jpg")
        val dest = file(ctx, thumbKey).apply { parentFile?.mkdirs() }
        dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        thumbKey
    }.getOrNull()

    fun decodeSampled(f: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun renderPdfPage(f: File, page: Int, width: Int): Bitmap? =
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (page !in 0 until r.pageCount) return null
                r.openPage(page).use { p ->
                    val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        p.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    fun delete(ctx: Context, key: String?) {
        if (key != null) runCatching { file(ctx, key).delete() }
    }
}
