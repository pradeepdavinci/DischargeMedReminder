package com.discharge.medreminder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/**
 * Fully offline, open-source text reading with Tesseract OCR.
 * Each page is perspective-corrected, cleaned and read in several ways; the reading that
 * yields the most recognisable medicines (then the highest confidence) wins.
 */
object OcrPipeline {
    class PageSpec(val uri: Uri, val rotation: Int, val corners: Array<PointF>)
    private class Candidate(val text: String, val conf: Int, val meds: Int)

    /** Copies the bundled model to private storage once (Tesseract needs a real file path). */
    private fun ensureModel(ctx: Context): String {
        val root = File(ctx.filesDir, "tesseract")
        val dir = File(root, "tessdata").apply { mkdirs() }
        val f = File(dir, "eng.traineddata")
        val assetLen = try { ctx.assets.openFd("tessdata/eng.traineddata").use { it.length } } catch (e: Exception) { -1L }
        if (!f.exists() || f.length() == 0L || (assetLen > 0 && f.length() != assetLen)) {
            val tmp = File(dir, "eng.traineddata.tmp")
            ctx.assets.open("tessdata/eng.traineddata").use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
        return root.absolutePath
    }

    private fun read(tess: TessBaseAPI, bmp: Bitmap, psm: Int, slots: Slots): Candidate {
        tess.setPageSegMode(psm)
        tess.setImage(bmp)
        val text = tess.getUTF8Text() ?: ""
        val conf = tess.meanConfidence()
        return Candidate(text, conf, MedParser.parse(text, slots).size)
    }

    private fun better(a: Candidate, b: Candidate) =
        if (b.meds > a.meds || (b.meds == a.meds && b.conf > a.conf)) b else a

    fun run(ctx: Context, pages: List<PageSpec>, slots: Slots, progress: (String) -> Unit): String {
        progress("Preparing the text reader…")
        val tess = TessBaseAPI()
        if (!tess.init(ensureModel(ctx), "eng")) {
            tess.recycle()
            throw IllegalStateException("OCR model could not be loaded")
        }
        try {
            tess.setVariable("user_defined_dpi", "300")
            tess.setVariable("preserve_interword_spaces", "1")
            val out = StringBuilder()
            pages.forEachIndexed { i, p ->
                val tag = if (pages.size > 1) "Page ${i + 1} of ${pages.size}: " else ""
                progress("${tag}straightening and cleaning the image…")
                val full = ImageTools.load(ctx, p.uri, 4000) ?: return@forEachIndexed
                val rot = ImageTools.rotate(full, p.rotation)
                val warped = ImageTools.warp(rot, p.corners.map { PointF(it.x * rot.width, it.y * rot.height) })
                rot.recycle()
                val clean = ImageTools.binarize(warped)

                progress("${tag}reading text (pass 1 of 2)…")
                var best = read(tess, clean, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, slots)   // keeps table rows together
                progress("${tag}reading text (pass 2 of 2)…")
                best = better(best, read(tess, clean, TessBaseAPI.PageSegMode.PSM_AUTO, slots))  // free-form layouts
                clean.recycle()
                if (best.meds == 0) {
                    progress("${tag}trying again on the original photo…")
                    best = better(best, read(tess, warped, TessBaseAPI.PageSegMode.PSM_AUTO, slots))
                }
                warped.recycle()
                out.append(best.text).append('\n')
            }
            return out.toString()
        } finally {
            tess.recycle()
        }
    }
}
