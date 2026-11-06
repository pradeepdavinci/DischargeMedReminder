package com.discharge.medreminder

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Lets the user check each page: rotate it and drag the corners onto the edges of the sheet. */
class CropActivity : ComponentActivity() {
    private class Page(val uri: Uri) {
        var rotation = 0
        var corners: Array<PointF>? = null
    }

    private val pages = mutableListOf<Page>()
    private var index = 0
    private var display: Bitmap? = null
    private lateinit var cropView: CropView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var backBtn: Button
    private lateinit var nextBtn: Button
    private val controls = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Adjust the sheet"
        val uris = intent.getStringArrayListExtra("uris").orEmpty()
        if (uris.isEmpty()) { finish(); return }
        uris.forEach { pages.add(Page(Uri.parse(it))) }

        val root = vertical().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        info = label("", 14f); root.addView(info)
        cropView = CropView(this)
        root.addView(cropView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        status = label("", 15f, true); root.addView(status)

        val row1 = horizontal()
        listOf(
            button("⟳ Rotate") { rotate() },
            button("Auto-detect") { display?.let { cropView.corners = ImageTools.detectCorners(it) } },
            button("Whole photo") { cropView.corners = CropView.defaultCorners(0f) }
        ).forEach { row1.addView(it, weighted()); controls.add(it) }
        root.addView(row1)

        val row2 = horizontal()
        backBtn = button("‹ Previous") { savePage(); show(index - 1) }
        nextBtn = button("") { savePage(); if (index < pages.size - 1) show(index + 1) else readAll() }
        row2.addView(backBtn, weighted()); row2.addView(nextBtn, weighted())
        controls.add(backBtn); controls.add(nextBtn)
        root.addView(row2)
        setContentView(root)
        show(0)
    }

    private fun savePage() {
        pages[index].corners = cropView.corners.map { PointF(it.x, it.y) }.toTypedArray()
    }

    private fun show(i: Int) {
        index = i.coerceIn(0, pages.size - 1)
        val p = pages[index]
        cropView.bitmap = null
        display?.recycle()
        display = null
        val loaded = ImageTools.load(this, p.uri, 1600)
        if (loaded == null) {
            toast("Could not open page ${index + 1}")
        } else {
            val shown = ImageTools.rotate(loaded, p.rotation)
            display = shown
            cropView.bitmap = shown
            val c = p.corners ?: ImageTools.detectCorners(shown)
            p.corners = c
            cropView.corners = c
        }
        info.text = "Page ${index + 1} of ${pages.size}: drag the 4 blue dots onto the corners of the " +
            "medication list. Make sure the text is upright – use Rotate if needed."
        backBtn.isEnabled = index > 0
        nextBtn.text = if (index < pages.size - 1) "Next page ›" else "✅ Read medicines"
    }

    private fun rotate() {
        pages[index].rotation = (pages[index].rotation + 90) % 360
        pages[index].corners = null
        show(index)
    }

    private fun readAll() {
        controls.forEach { it.isEnabled = false }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Reading… this can take up to a minute per page."
        val specs = pages.map { OcrPipeline.PageSpec(it.uri, it.rotation, it.corners ?: CropView.defaultCorners(0f)) }
        val slots = Prefs.slots(this)
        cropView.bitmap = null
        display?.recycle()
        display = null
        val app = applicationContext
        Thread {
            val text: String? = try {
                OcrPipeline.run(app, specs, slots) { msg -> runOnUiThread { status.text = msg } }
            } catch (t: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (text == null) {
                    status.text = ""
                    toast("Sorry, the sheet could not be read. Try a sharper photo in good light, or add medicines manually.")
                    controls.forEach { it.isEnabled = true }
                    show(index)
                    return@runOnUiThread
                }
                val meds = MedParser.parse(text, slots)
                if (meds.isEmpty()) toast("No medicines recognised automatically – please add them manually.")
                startActivity(
                    Intent(this, ReviewActivity::class.java)
                        .putExtra("meds", Med.listToJson(meds))
                        .putExtra("raw", text)
                )
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cropView.isInitialized) cropView.bitmap = null
        display?.recycle()
        display = null
    }
}
