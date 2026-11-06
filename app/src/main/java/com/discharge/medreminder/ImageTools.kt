package com.discharge.medreminder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Open-source image clean-up: loading, PDF rendering, page detection, perspective correction, shadow removal. */
object ImageTools {

    /** Renders up to [maxPages] pages of a PDF discharge summary into cached JPEG URIs for offline OCR. */
    fun renderPdfToCache(ctx: Context, pdfUri: Uri, maxPages: Int = 5): List<Uri> {
        val out = mutableListOf<Uri>()
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        val pfd = ctx.contentResolver.openFileDescriptor(pdfUri, "r") ?: return emptyList()
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val count = minOf(renderer.pageCount, maxPages)
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        val scale = 2200f / max(1, max(page.width, page.height))
                        val w = max(1, (page.width * scale).roundToInt())
                        val h = max(1, (page.height * scale).roundToInt())
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val f = File(dir, "pdf_page_${System.currentTimeMillis()}_$i.jpg")
                        FileOutputStream(f).use { stream -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, stream) }
                        bmp.recycle()
                        out.add(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
                    }
                }
            }
        }
        return out
    }

    /** Loads a photo no larger than [maxDim] on its long side, upright according to its EXIF data. */
    fun load(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val big = max(bmp.width, bmp.height)
        if (big > maxDim) {
            val s = maxDim.toFloat() / big
            val scaled = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        val deg = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) { 0 }
        return rotate(bmp, deg)
    }

    /** Returns a rotated copy (the input is recycled) or the input itself for 0°. */
    fun rotate(b: Bitmap, deg: Int): Bitmap {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) return b
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r != b) b.recycle()
        return r
    }

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        gray.forEach { hist[it]++ }
        val total = gray.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var thr = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        return thr
    }

    /**
     * Finds the sheet of paper in a photo: the largest bright region; its 4 extreme points
     * become the corners. Returns corners normalised to 0..1 (TL, TR, BR, BL).
     */
    fun detectCorners(b: Bitmap): Array<PointF> {
        val s = 256f / max(b.width, b.height)
        val w = max(8, (b.width * s).roundToInt()); val h = max(8, (b.height * s).roundToInt())
        val sm = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        sm.getPixels(px, 0, w, 0, 0, w, h)
        if (sm != b) sm.recycle()
        val gray = IntArray(w * h) { lum(px[it]) }
        val thr = otsu(gray)
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var bestArea = 0
        var best: Array<PointF>? = null
        for (start in 0 until w * h) {
            if (seen[start] || gray[start] <= thr) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var area = 0
            var tlV = Int.MAX_VALUE; var brV = Int.MIN_VALUE; var trV = Int.MIN_VALUE; var blV = Int.MAX_VALUE
            var tl = 0; var br = 0; var tr = 0; var bl = 0
            while (head < tail) {
                val p = queue[head++]; area++
                val x = p % w; val y = p / w
                if (x + y < tlV) { tlV = x + y; tl = p }
                if (x + y > brV) { brV = x + y; br = p }
                if (x - y > trV) { trV = x - y; tr = p }
                if (x - y < blV) { blV = x - y; bl = p }
                if (x > 0) { val q = p - 1; if (!seen[q] && gray[q] > thr) { seen[q] = true; queue[tail++] = q } }
                if (x < w - 1) { val q = p + 1; if (!seen[q] && gray[q] > thr) { seen[q] = true; queue[tail++] = q } }
                if (y > 0) { val q = p - w; if (!seen[q] && gray[q] > thr) { seen[q] = true; queue[tail++] = q } }
                if (y < h - 1) { val q = p + w; if (!seen[q] && gray[q] > thr) { seen[q] = true; queue[tail++] = q } }
            }
            if (area > bestArea) {
                bestArea = area
                fun pt(p: Int) = PointF(((p % w) + 0.5f) / w, ((p / w) + 0.5f) / h)
                best = arrayOf(pt(tl), pt(tr), pt(br), pt(bl))
            }
        }
        if (best == null || bestArea < 0.15f * w * h) return CropView.defaultCorners()
        return best
    }

    /** Perspective-corrects the quadrilateral [pts] (TL, TR, BR, BL, pixel coords) into a flat page. */
    fun warp(src: Bitmap, pts: List<PointF>, targetWidth: Int = 2400, maxPixels: Int = 7_000_000): Bitmap {
        fun d(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)
        val w = max(max(d(pts[0], pts[1]), d(pts[3], pts[2])), 16f)
        val h = max(max(d(pts[0], pts[3]), d(pts[1], pts[2])), 16f)
        var s = targetWidth / w
        if (w * s * h * s > maxPixels) s = sqrt(maxPixels / (w * h))
        val outW = max(1, (w * s).roundToInt()); val outH = max(1, (h * s).roundToInt())
        val m = Matrix()
        val srcPts = floatArrayOf(pts[0].x, pts[0].y, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y)
        val dstPts = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /**
     * Adaptive (Bradley) threshold: each pixel is compared with its neighbourhood average,
     * which removes shadows and uneven lighting from phone photos.
     */
    fun binarize(src: Bitmap, percent: Int = 15): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) px[i] = lum(px[i])
        val stride = w + 1
        val integral = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                rowSum += px[y * w + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
            }
        }
        val r = max(8, w / 32)
        for (y in 0 until h) {
            val y1 = max(0, y - r); val y2 = minOf(h - 1, y + r)
            for (x in 0 until w) {
                val x1 = max(0, x - r); val x2 = minOf(w - 1, x + r)
                val count = ((x2 - x1 + 1) * (y2 - y1 + 1)).toLong()
                val sum = integral[(y2 + 1) * stride + x2 + 1].toLong() - integral[y1 * stride + x2 + 1].toLong() -
                    integral[(y2 + 1) * stride + x1].toLong() + integral[y1 * stride + x1].toLong()
                val i = y * w + x
                px[i] = if (px[i] * count * 100 <= sum * (100 - percent)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
