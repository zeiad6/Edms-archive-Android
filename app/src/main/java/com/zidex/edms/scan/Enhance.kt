package com.zidex.edms.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Document clean-up for scanned pages.
 *
 * The stage order follows the pipeline documented in Android/REPORT_ar.md
 * (vFlat `enhance_cs`: background "distillation" division → black point →
 * contrast → whitening, then a 3×3 unsharp mask). vFlat drives the background
 * and black-point maps with proprietary TFLite models; here both are estimated
 * with classic image processing, so nothing from their APK is shipped:
 *
 *  - background = heavily downscaled page, max-filtered (erases ink) and
 *    box-blurred, sampled back bilinearly;
 *  - black point = 1st percentile of the flattened luma.
 */
object Enhance {
    enum class Mode(val label: String) { ORIGINAL("أصلي"), ENHANCE("تحسين"), GRAY("رمادي"), BW("أبيض وأسود") }

    private const val EQUAL_EPS = 0.05f          // pixel ≈ background → treat as paper
    private const val WHITE_LUMA = 205f / 255f  // above this, push to pure white
    private const val WHITEN = 255f / 230f
    private const val INK_LUMA = 0.55f           // blank-page detector: "ink" pixel
    private const val BLANK_INK_RATIO = 0.0015f

    fun apply(src: Bitmap, mode: Mode): Bitmap {
        if (mode == Mode.ORIGINAL) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val bg = Background(px, w, h)
        // Divided pixels are kept packed in 8 bits (`out`), not as three float
        // planes, so a 2000×2800 page stays around 70 MB instead of ~140 MB.
        val out = IntArray(w * h)
        val hist = IntArray(256)

        // 1) divide by the estimated paper colour — removes shadows and uneven light
        val bgOut = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val p = px[i]
            val pr = (p shr 16 and 255) / 255f; val pg = (p shr 8 and 255) / 255f; val pb = (p and 255) / 255f
            bg.sampleRgb(x, y, bgOut)
            var br = bgOut[0]; var bgc = bgOut[1]; var bb = bgOut[2]
            if (abs(pr - br) < EQUAL_EPS && abs(pg - bgc) < EQUAL_EPS && abs(pb - bb) < EQUAL_EPS) { br = pr; bgc = pg; bb = pb }
            val dr = (pr / max(br, 1e-3f)).coerceIn(0f, 1f)
            val dg = (pg / max(bgc, 1e-3f)).coerceIn(0f, 1f)
            val db = (pb / max(bb, 1e-3f)).coerceIn(0f, 1f)
            out[i] = rgb(dr, dg, db)
            hist[(luma(dr, dg, db) * 255).toInt().coerceIn(0, 255)]++
        }

        // 2) black point: stretch so the darkest 1 % of ink really is black
        var acc = 0; var bp = 0
        while (bp < 200 && acc + hist[bp] < out.size / 100) { acc += hist[bp]; bp++ }
        val black = bp / 255f
        val span = max(1f - black, 1e-3f)

        // Precompute LUT for 256 byte values: eliminates millions of Math.pow calls per page
        val contrastLut = FloatArray(256) { b ->
            contrast(((b / 255f - black) / span).coerceIn(0f, 1f))
        }

        for (i in out.indices) {
            val d = out[i]
            var cr = contrastLut[d shr 16 and 255]
            var cg = contrastLut[d shr 8 and 255]
            var cb = contrastLut[d and 255]
            // 3) whitening: near-white paper becomes clean white
            if (luma(cr, cg, cb) > WHITE_LUMA) { cr = min(cr * WHITEN, 1f); cg = min(cg * WHITEN, 1f); cb = min(cb * WHITEN, 1f) }
            out[i] = when (mode) {
                Mode.GRAY -> gray(luma(cr, cg, cb))
                Mode.BW -> if (luma(cr, cg, cb) > 0.62f) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                else -> rgb(cr, cg, cb)
            }
        }
        val res = if (mode == Mode.BW) out else sharpen(out, w, h)
        return Bitmap.createBitmap(res, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Empty page (back side, separator sheet): almost no ink after flattening the background. */
    fun isBlank(src: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(src, 400, max(1, 400 * src.height / src.width), true)
        val e = apply(small, Mode.GRAY)
        val px = IntArray(e.width * e.height).also { e.getPixels(it, 0, e.width, 0, 0, e.width, e.height) }
        // ignore a 5 % border: page edges and scanner shadows are not content
        val mx = e.width / 20; val my = e.height / 20
        var ink = 0; var n = 0
        for (y in my until e.height - my) for (x in mx until e.width - mx) {
            n++
            if ((px[y * e.width + x] and 255) / 255f < INK_LUMA) ink++
        }
        return n > 0 && ink.toFloat() / n < BLANK_INK_RATIO
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    fun jpeg(b: Bitmap, quality: Int = 85): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    // ───────────── helpers ─────────────

    private fun luma(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

    /** Gentle S-curve: darker strokes, cleaner paper; fixed points 0, 0.5, 1. */
    private fun contrast(x: Float): Float =
        if (x < 0.5f) 0.5f * (2f * x).pow(1.35f) else 1f - 0.5f * (2f * (1f - x)).pow(1.35f)

    private fun rgb(r: Float, g: Float, b: Float) =
        (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()

    private fun gray(y: Float): Int { val v = (y * 255).toInt().coerceIn(0, 255); return (0xFF shl 24) or (v shl 16) or (v shl 8) or v }

    /** Unsharp mask with the 1-2-1 Gaussian kernel, amount 1. */
    private fun sharpen(px: IntArray, w: Int, h: Int): IntArray {
        val out = px.copyOf()
        val k = intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var sr = 0; var sg = 0; var sb = 0; var j = 0
            for (dy in -1..1) for (dx in -1..1) {
                val p = px[(y + dy) * w + x + dx]
                sr += (p shr 16 and 255) * k[j]; sg += (p shr 8 and 255) * k[j]; sb += (p and 255) * k[j]; j++
            }
            val p = px[y * w + x]
            val r = (2 * (p shr 16 and 255) - sr / 16).coerceIn(0, 255)
            val g = (2 * (p shr 8 and 255) - sg / 16).coerceIn(0, 255)
            val b = (2 * (p and 255) - sb / 16).coerceIn(0, 255)
            out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** Paper-colour map on a coarse grid (≈ 1/24 of the page), bilinearly sampled. */
    private class Background(px: IntArray, private val w: Int, private val h: Int) {
        private val gw = max(8, w / 24)
        private val gh = max(8, h / 24)
        private val cr = FloatArray(gw * gh); private val cg = FloatArray(gw * gh); private val cb = FloatArray(gw * gh)

        init {
            // cell maximum = brightest (paper) value in the cell; ink disappears
            for (y in 0 until h) {
                val cy = y * gh / h
                for (x in 0 until w) {
                    val c = cy * gw + x * gw / w
                    val p = px[y * w + x]
                    cr[c] = max(cr[c], (p shr 16 and 255) / 255f)
                    cg[c] = max(cg[c], (p shr 8 and 255) / 255f)
                    cb[c] = max(cb[c], (p and 255) / 255f)
                }
            }
            repeat(2) { blur(cr); blur(cg); blur(cb) }
        }

        private fun blur(a: FloatArray) {
            val t = a.copyOf()
            for (y in 0 until gh) for (x in 0 until gw) {
                var s = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = y + dy; val xx = x + dx
                    if (yy in 0 until gh && xx in 0 until gw) { s += t[yy * gw + xx]; n++ }
                }
                a[y * gw + x] = s / n
            }
        }

        private fun sample(a: FloatArray, x: Int, y: Int): Float {
            val fx = (x.toFloat() / w * gw - 0.5f).coerceIn(0f, gw - 1f)
            val fy = (y.toFloat() / h * gh - 0.5f).coerceIn(0f, gh - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, gw - 1); val y1 = min(y0 + 1, gh - 1)
            val ax = fx - x0; val ay = fy - y0
            val top = a[y0 * gw + x0] * (1 - ax) + a[y0 * gw + x1] * ax
            val bot = a[y1 * gw + x0] * (1 - ax) + a[y1 * gw + x1] * ax
            return top * (1 - ay) + bot * ay
        }

        fun sampleRgb(x: Int, y: Int, out: FloatArray) {
            val fx = (x.toFloat() / w * gw - 0.5f).coerceIn(0f, gw - 1f)
            val fy = (y.toFloat() / h * gh - 0.5f).coerceIn(0f, gh - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, gw - 1); val y1 = min(y0 + 1, gh - 1)
            val ax = fx - x0; val ay = fy - y0
            val idx00 = y0 * gw + x0; val idx01 = y0 * gw + x1
            val idx10 = y1 * gw + x0; val idx11 = y1 * gw + x1
            val w1 = 1f - ax; val w2 = ax
            val wy1 = 1f - ay; val wy2 = ay
            out[0] = (cr[idx00] * w1 + cr[idx01] * w2) * wy1 + (cr[idx10] * w1 + cr[idx11] * w2) * wy2
            out[1] = (cg[idx00] * w1 + cg[idx01] * w2) * wy1 + (cg[idx10] * w1 + cg[idx11] * w2) * wy2
            out[2] = (cb[idx00] * w1 + cb[idx01] * w2) * wy1 + (cb[idx10] * w1 + cb[idx11] * w2) * wy2
        }

        fun r(x: Int, y: Int) = sample(cr, x, y)
        fun g(x: Int, y: Int) = sample(cg, x, y)
        fun b(x: Int, y: Int) = sample(cb, x, y)
    }
}

/**
 * Minimal PDF writer: one JPEG per page, embedded as-is (DCTDecode), page
 * size from the image aspect at A4 width. Far smaller than android's
 * PdfDocument, which re-encodes bitmaps losslessly.
 */
object JpegPdf {
    data class Page(val jpeg: ByteArray, val width: Int, val height: Int)

    fun build(pages: List<Page>): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(body: () -> Unit) { offsets += out.size(); w("${offsets.size} 0 obj\n"); body(); w("\nendobj\n") }

        w("%PDF-1.4\n%âãÏÓ\n")
        val n = pages.size
        // object ids: 1 catalog, 2 pages, then per page: page, content, image
        obj { w("<< /Type /Catalog /Pages 2 0 R >>") }
        obj { w("<< /Type /Pages /Count $n /Kids [${(0 until n).joinToString(" ") { "${3 + it * 3} 0 R" }}] >>") }
        pages.forEachIndexed { i, p ->
            val pw = 595f
            val ph = pw * p.height / p.width
            val pageId = 3 + i * 3
            obj { w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${"%.2f".format(java.util.Locale.US, pw)} ${"%.2f".format(java.util.Locale.US, ph)}] /Resources << /XObject << /Im0 ${pageId + 2} 0 R >> >> /Contents ${pageId + 1} 0 R >>") }
            val content = "q ${"%.2f".format(java.util.Locale.US, pw)} 0 0 ${"%.2f".format(java.util.Locale.US, ph)} 0 0 cm /Im0 Do Q"
            obj { w("<< /Length ${content.length} >>\nstream\n$content\nendstream") }
            obj {
                w("<< /Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n")
                out.write(p.jpeg)
                w("\nendstream")
            }
        }
        val xref = out.size()
        w("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { w("%010d 00000 n \n".format(it)) }
        w("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}
