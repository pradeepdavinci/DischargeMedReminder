package com.discharge.medreminder

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Lets the user correct the medicines before any reminder is created (also used for editing). */
class ReviewActivity : ComponentActivity() {
    private class Row(
        val root: LinearLayout,
        val name: EditText,
        val dose: EditText,
        val times: EditText,
        val chipsBox: LinearLayout,
        val every: EditText,
        val days: EditText,
        val note: EditText,
        val loud: CheckBox,
        val startBtn: Button,
        var start: Long
    )

    private lateinit var container: LinearLayout
    private val rows = mutableListOf<Row>()
    private var editId = -1
    private var scanUris = arrayListOf<String>()
    private var previewBmp: Bitmap? = null
    private val dateFmt = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editId = intent.getIntExtra("editId", -1)
        title = if (editId > 0) "Edit medicine" else "Check medicines"
        val meds = Med.listFromJson(intent.getStringExtra("meds"))
        val raw = intent.getStringExtra("raw") ?: ""
        scanUris = ArrayList(intent.getStringArrayListExtra("uris") ?: MedStore.lastScanUris(this))

        val outer = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }

        // Rescan bar at the top when reviewing scanned medicines or adding from scan
        if (editId <= 0) {
            val rescanCard = card()
            rescanCard.addView(label("Not happy with the scan?", 14f, true))
            val rescanRow = horizontal()
            if (scanUris.isNotEmpty()) {
                rescanRow.addView(button("🔄 Re-adjust & Rescan") {
                    startActivity(
                        Intent(this, CropActivity::class.java)
                            .putStringArrayListExtra("uris", ArrayList(scanUris))
                    )
                    finish()
                }, weighted())
            }
            rescanRow.addView(button("📷 Rescan new sheet") {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("triggerRescan", true)
                )
                finish()
            }, weighted())
            rescanCard.addView(rescanRow)
            outer.addView(rescanCard)
        }

        // Scanned sheet thumbnail preview at top of review screen
        if (editId <= 0 && raw.isNotBlank()) {
            previewBmp = OcrPipeline.loadPreview(this)
            previewBmp?.let { bmp ->
                val imgCard = card()
                imgCard.addView(label("Scanned discharge sheet (tap to enlarge)", 14f, true))
                val iv = ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    maxHeight = dp(180)
                    setOnClickListener { showImageDialog("Scanned discharge sheet", bmp) }
                }
                imgCard.addView(iv)
                imgCard.addView(button("🔍 View full scanned sheet") {
                    showImageDialog("Scanned discharge sheet", bmp)
                })
                outer.addView(imgCard)
            }
        }

        if (raw.isNotBlank()) outer.addView(label(
            "Found ${meds.size} medicine(s). Compare every entry with the printed sheet and correct anything that is wrong. " +
            "Items marked “please check” need special attention.", 14f, true))
        outer.addView(label("Tap “+ Add time” or any time button to pick times with a clock, or type 24-hour HH:MM separated by commas. " +
            "Leave times empty for ‘as needed’ (SOS) medicines.", 13f))
        container = vertical(); outer.addView(container)
        if (meds.isEmpty()) addRow(null) else meds.forEach { addRow(it) }
        if (editId <= 0) outer.addView(button("+ Add another medicine") { addRow(null) })
        outer.addView(button("✅  Save and set reminders") { save() })
        if (raw.isNotBlank()) {
            outer.addView(label("Text read from the sheet (for reference)", 14f, true))
            outer.addView(label(raw, 12f).apply { setTextIsSelectable(true) })
        }
        setContentView(ScrollView(this).apply { addView(outer) })
    }

    private fun parseTimesField(edit: EditText): MutableList<String> =
        edit.text.toString().split(',', ';', ' ')
            .mapNotNull { normalizeTime(it.trim()) }
            .distinct()
            .sorted()
            .toMutableList()

    private fun refreshTimeChips(row: Row) {
        row.chipsBox.removeAllViews()
        val current = parseTimesField(row.times)
        if (current.isEmpty()) {
            val emptyRow = horizontal()
            emptyRow.addView(badge("As needed (SOS – no alarms)", 0xFF64748B.toInt()))
            row.chipsBox.addView(emptyRow)
        } else {
            current.chunked(3).forEach { chunk ->
                val chipsRow = horizontal()
                chunk.forEach { t ->
                    val idx = current.indexOf(t)
                    chipsRow.addView(button("🕒 $t") {
                        AlertDialog.Builder(this)
                            .setTitle("Dose time $t")
                            .setItems(arrayOf("Change time", "Remove $t")) { _, which ->
                                if (which == 0) {
                                    pickTime(t) { newTime ->
                                        val updated = parseTimesField(row.times)
                                        if (idx in updated.indices) updated[idx] = newTime else updated.add(newTime)
                                        row.times.setText(updated.distinct().sorted().joinToString(", "))
                                        refreshTimeChips(row)
                                    }
                                } else {
                                    val updated = parseTimesField(row.times)
                                    updated.remove(t)
                                    row.times.setText(updated.joinToString(", "))
                                    refreshTimeChips(row)
                                }
                            }
                            .show()
                    }, weighted())
                }
                row.chipsBox.addView(chipsRow)
            }
        }

        val actionsRow = horizontal()
        actionsRow.addView(button("+ Add time") {
            val defaultTime = if (current.isEmpty()) Prefs.slots(this).morning else "20:00"
            pickTime(defaultTime) { picked ->
                val updated = parseTimesField(row.times)
                updated.add(picked)
                row.times.setText(updated.distinct().sorted().joinToString(", "))
                refreshTimeChips(row)
            }
        }, weighted())
        actionsRow.addView(button("As needed (SOS)") {
            row.times.setText("")
            refreshTimeChips(row)
        }, weighted())
        row.chipsBox.addView(actionsRow)
    }

    private fun addRow(m: Med?) {
        val c = card()
        val startBtn = Button(this).apply { isAllCaps = false }
        val timesField = field("Times (e.g. 08:00, 20:00)", m?.times?.joinToString(", ") ?: Prefs.slots(this).morning)
        val chipsBox = vertical()
        val loudCheck = checkBox("🔔 Loud continuous alarm (for critical medicine)", m?.loud ?: false)
        val row = Row(
            c,
            field("Medicine (e.g. Tab Pantoprazole)", m?.name ?: ""),
            field("Dose (e.g. 40 mg / 1 tablet)", m?.dose ?: ""),
            timesField,
            chipsBox,
            field("Every N days (1 = daily)", (m?.every ?: 1).toString(), numeric = true),
            field("Number of days", (m?.days ?: 7).toString(), numeric = true),
            field("Instructions (e.g. after food)", m?.note ?: ""),
            loudCheck,
            startBtn,
            m?.startMillis ?: todayMidnight()
        )
        refreshTimeChips(row)
        startBtn.text = "Starts: ${dateFmt.format(Date(row.start))}"
        startBtn.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = row.start }
            DatePickerDialog(this, { _, y, mo, d ->
                row.start = Calendar.getInstance().apply { clear(); set(y, mo, d) }.timeInMillis
                startBtn.text = "Starts: ${dateFmt.format(Date(row.start))}"
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        c.addView(label("Medicine", 13f, true)); c.addView(row.name)
        c.addView(label("Dose", 13f, true)); c.addView(row.dose)
        c.addView(label("Reminder times", 13f, true))
        c.addView(row.chipsBox)
        c.addView(row.times)
        val r2 = horizontal()
        val left = vertical().apply { addView(label("Every N days", 13f, true)); addView(row.every) }
        val right = vertical().apply { addView(label("Number of days", 13f, true)); addView(row.days) }
        r2.addView(left, weighted()); r2.addView(right, weighted())
        c.addView(r2)
        c.addView(startBtn)
        c.addView(label("Instructions / notes", 13f, true)); c.addView(row.note)
        c.addView(row.loud)
        if (editId <= 0) c.addView(button("Remove this medicine") { container.removeView(c); rows.remove(row) })
        rows.add(row)
        container.addView(c)
    }

    private fun save() {
        val out = mutableListOf<Med>()
        for ((i, r) in rows.withIndex()) {
            val name = r.name.text.toString().trim()
            if (name.isEmpty()) { toast("Medicine ${i + 1}: please enter a name"); return }
            val times = mutableListOf<String>()
            for (t in r.times.text.toString().split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
                val n = normalizeTime(t) ?: run { toast("$name: '$t' is not a valid time (use HH:MM, e.g. 08:00)"); return }
                times.add(n)
            }
            val every = r.every.text.toString().toIntOrNull()
            if (every == null || every !in 1..30) { toast("$name: 'every N days' must be 1–30"); return }
            val days = r.days.text.toString().toIntOrNull()
            if (days == null || days !in 1..365) { toast("$name: number of days must be 1–365"); return }
            out.add(Med(0, name, r.dose.text.toString().trim(), times.distinct().sorted().toMutableList(),
                days, r.start, r.note.text.toString().trim(), every, r.loud.isChecked))
        }
        if (out.isEmpty()) { toast("Nothing to save"); return }

        val all = MedStore.load(this)
        out.forEachIndexed { i, med ->
            val oldIndex = if (i == 0 && editId > 0) all.indexOfFirst { it.id == editId } else -1
            if (oldIndex >= 0) {
                Scheduler.cancelMed(this, all[oldIndex])
                med.id = editId
                all[oldIndex] = med
            } else {
                med.id = MedStore.nextId(this)
                all.add(med)
            }
        }
        MedStore.save(this, all)
        out.forEach { Scheduler.scheduleMed(this, it) }
        toast(if (editId > 0) "Medicine updated" else "Reminders set for ${out.size} medicine(s)")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewBmp?.recycle()
        previewBmp = null
    }
}
