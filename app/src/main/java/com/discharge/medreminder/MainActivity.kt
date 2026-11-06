package com.discharge.medreminder

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    private lateinit var warnBox: LinearLayout
    private lateinit var rescanBox: LinearLayout
    private lateinit var todayBox: LinearLayout
    private lateinit var listBox: LinearLayout

    private val captured = arrayListOf<String>()
    private var pendingPhoto: Uri? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingPhoto
        pendingPhoto = null
        if (ok && u != null) {
            captured.add(u.toString())
            askForMorePages()
        } else if (captured.isNotEmpty()) {
            openCrop(captured.toList())
        }
    }

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                if (uris.size > 5) toast("Using the first 5 photos.")
                openCrop(uris.take(5).map { it.toString() })
            }
        }

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                toast("Converting PDF pages…")
                Thread {
                    val rendered = try {
                        ImageTools.renderPdfToCache(this, uri, 5)
                    } catch (e: Exception) { emptyList() }
                    runOnUiThread {
                        if (rendered.isEmpty()) {
                            toast("Could not read PDF file. Try selecting a clear photo instead.")
                        } else {
                            openCrop(rendered.map { it.toString() })
                        }
                    }
                }.start()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { st ->
            st.getStringArrayList("captured")?.let { captured.addAll(it) }
            st.getString("pending")?.let { pendingPhoto = Uri.parse(it) }
        }
        Notifier.ensureChannel(this)

        val root = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        root.addView(label("Discharge medication reminders", 22f, true))
        root.addView(label("Photograph or import the medication sheet given at discharge. You will check every medicine before any reminder is set.", 14f))
        root.addView(button("📷  Photograph the medication sheet") { startCamera() })
        root.addView(button("🖼  Choose photos from the gallery") { pickPhotos.launch("image/*") })
        root.addView(button("📄  Import PDF discharge summary") { pickPdf.launch(arrayOf("application/pdf")) })
        rescanBox = vertical(); root.addView(rescanBox)
        root.addView(button("✍  Add a medicine manually") { startActivity(Intent(this, ReviewActivity::class.java)) })
        warnBox = vertical(); root.addView(warnBox)
        todayBox = vertical(); root.addView(todayBox)
        listBox = vertical(); root.addView(listBox)

        val tools = horizontal()
        tools.addView(button("Share / Export") { showExportMenu() }, weighted())
        tools.addView(button("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }, weighted())
        tools.addView(button("Test") { Notifier.showTest(this) }, weighted())
        root.addView(tools)
        root.addView(label("Free & open-source (MIT License). Works offline – nothing leaves this phone. " +
            "This is a reminder aid, not a medical device: always follow the printed sheet and your doctor.", 12f))
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (intent.getBooleanExtra("triggerRescan", false)) {
            intent.removeExtra("triggerRescan")
            showRescanDialog()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("triggerRescan", false) == true) {
            intent.removeExtra("triggerRescan")
            showRescanDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("captured", captured)
        pendingPhoto?.let { outState.putString("pending", it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- capturing & rescanning pages ----------

    private fun showRescanDialog() {
        val lastUris = MedStore.lastScanUris(this)
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (lastUris.isNotEmpty()) {
            options.add("🔄 Re-adjust & rescan last sheet" to { openCrop(lastUris) })
        }
        options.add("📷 Photograph sheet again (Camera)" to { startCamera() })
        options.add("🖼 Pick photos from gallery" to { pickPhotos.launch("image/*") })
        options.add("📄 Import PDF discharge summary" to { pickPdf.launch(arrayOf("application/pdf")) })

        AlertDialog.Builder(this)
            .setTitle("Rescan discharge sheet")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCamera() {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        if (captured.isEmpty()) {
            val dayAgo = System.currentTimeMillis() - 86_400_000L
            dir.listFiles()?.forEach { if (it.lastModified() < dayAgo) it.delete() }
        }
        val f = File(dir, "page_${System.currentTimeMillis()}.jpg")
        val u = FileProvider.getUriForFile(this, "$packageName.files", f)
        pendingPhoto = u
        try {
            takePhoto.launch(u)
        } catch (e: ActivityNotFoundException) {
            pendingPhoto = null
            toast("No camera app found – please choose photos from the gallery instead.")
        }
    }

    private fun askForMorePages() {
        if (captured.size >= 5) { openCrop(captured.toList()); return }
        AlertDialog.Builder(this)
            .setTitle("Page ${captured.size} captured")
            .setMessage("Does the medication list continue on another page?")
            .setPositiveButton("Add another page") { _, _ -> startCamera() }
            .setNegativeButton("No, continue") { _, _ -> openCrop(captured.toList()) }
            .setCancelable(false)
            .show()
    }

    private fun openCrop(uris: List<String>) {
        captured.clear()
        MedStore.saveLastScanUris(this, uris)
        startActivity(Intent(this, CropActivity::class.java).putStringArrayListExtra("uris", ArrayList(uris)))
    }

    // ---------- home screen ----------

    private fun refresh() {
        rescanBox.removeAllViews()
        val lastUris = MedStore.lastScanUris(this)
        rescanBox.addView(button(if (lastUris.isNotEmpty()) "🔄  Rescan discharge sheet" else "🔄  Rescan / scan another sheet") {
            showRescanDialog()
        })

        warnBox.removeAllViews()
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            warnBox.addView(label("⚠ Notifications are turned off – reminders will not appear.", 14f, true))
            warnBox.addView(button("Turn on notifications") {
                if (Build.VERSION.SDK_INT >= 26)
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                else
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            warnBox.addView(label("⚠ Exact alarms are not allowed – reminders may arrive late.", 14f, true))
            warnBox.addView(button("Allow exact reminder times") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
        }

        val meds = MedStore.load(this)
        val now = System.currentTimeMillis()
        val today = todayMidnight()

        // Today's doses with taken / skipped / overdue / upcoming status
        todayBox.removeAllViews()
        val doses = meds.flatMap { m ->
            if (m.times.isEmpty() || !Scheduler.isDoseDay(m, today)) emptyList()
            else m.times.indices.map { i -> Triple(m.times[i], m, i) }
        }.sortedBy { it.first }
        if (doses.isNotEmpty()) {
            todayBox.addView(label("Today", 19f, true))
            val c = card()
            for ((time, m, i) in doses) {
                val due = Scheduler.dueOn(m, i, today)
                val taken = Doses.isTaken(this, m, i, due)
                val skipReason = Doses.skipReason(this, m, i, due)
                val skipped = skipReason != null
                val itemBox = vertical().apply { setPadding(0, dp(4), 0, dp(4)) }
                val row = horizontal()
                val statusBadge = when {
                    taken -> badge("✓ Taken", 0xFF16A34A.toInt())
                    skipped -> badge("⏭ Skipped", 0xFF64748B.toInt())
                    due < now -> badge("⚠ Overdue", 0xFFDC2626.toInt())
                    else -> badge("• $time", 0xFF2563EB.toInt())
                }
                row.addView(statusBadge)
                row.addView(label("  $time  ${m.name} ${m.dose}".trimEnd(), 15f, !taken && !skipped && due < now), weighted())
                when {
                    taken -> {
                        row.addView(button("Undo") { Doses.unmarkTaken(this, m, i, due); refresh() })
                    }
                    skipped -> {
                        row.addView(button("Undo") { Doses.unmarkSkipped(this, m, i, due); refresh() })
                    }
                    else -> {
                        row.addView(button("Taken") { Doses.markTaken(this, m, i, due); refresh() })
                        row.addView(button("Skip") { promptSkipDose(m, i, due) })
                    }
                }
                itemBox.addView(row)
                if (skipped && !skipReason.isNullOrBlank()) {
                    itemBox.addView(label("   Reason: $skipReason", 12f))
                }
                c.addView(itemBox)
            }
            todayBox.addView(c)
        }

        // All medicines
        listBox.removeAllViews()
        listBox.addView(label("Your medicines", 19f, true))
        if (meds.isEmpty()) listBox.addView(label("No medicines yet. Photograph or import the discharge sheet to get started.", 14f))
        val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        val dfmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        for (m in meds) {
            val c = card()
            val header = horizontal()
            header.addView(label(listOf(m.name, m.dose).filter { it.isNotBlank() }.joinToString(" — "), 17f, true), weighted())
            if (m.loud) header.addView(badge("🔔 Loud alarm", 0xFFD97706.toInt()))
            c.addView(header)
            val timesJoined = m.times.joinToString(", ")
            c.addView(label(if (m.times.isEmpty()) "As needed (no reminders)" else "Times: $timesJoined" + everyText(m), 14f))
            c.addView(label("Course: ${m.days} day(s), ${dfmt.format(Date(m.startMillis))} – ${dfmt.format(Date(Scheduler.endMillis(m) - 1))}", 14f))
            if (m.note.isNotBlank()) c.addView(label(m.note, 13f))
            val next = Scheduler.nextDose(m)
            if (m.times.isNotEmpty())
                c.addView(label(if (next == null) "Course finished" else "Next reminder: ${fmt.format(Date(next))}", 14f, true))
            val actions = horizontal()
            actions.addView(button("Edit") {
                startActivity(Intent(this, ReviewActivity::class.java)
                    .putExtra("meds", Med.listToJson(listOf(m))).putExtra("editId", m.id))
            }, weighted())
            actions.addView(button("Delete") { confirmDelete(m) }, weighted())
            c.addView(actions)
            listBox.addView(c)
        }

        val logs = MedStore.logs(this)
        if (logs.isNotEmpty()) {
            listBox.addView(label("Recent adherence log", 17f, true))
            logs.take(15).forEach { listBox.addView(label(it, 13f)) }
        }
    }

    private fun promptSkipDose(m: Med, idx: Int, due: Long) {
        val reasons = arrayOf(
            "Doctor advised to skip",
            "Fasting / before test",
            "Side effects / nausea",
            "Medicine out of stock",
            "Other reason…"
        )
        val slot = m.times.getOrNull(idx) ?: ""
        AlertDialog.Builder(this)
            .setTitle("Skip ${m.name} ($slot)?")
            .setItems(reasons) { _, which ->
                if (which < reasons.size - 1) {
                    Doses.markSkipped(this, m, idx, due, reasons[which])
                    refresh()
                } else {
                    val input = field("Reason for skipping", "")
                    AlertDialog.Builder(this)
                        .setTitle("Enter skip reason")
                        .setView(input)
                        .setPositiveButton("Skip dose") { _, _ ->
                            Doses.markSkipped(this, m, idx, due, input.text.toString())
                            refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun everyText(m: Med) = when (m.every) {
        1 -> ""
        2 -> " (alternate days)"
        7 -> " (once a week)"
        else -> " (every ${m.every} days)"
    }

    private fun confirmDelete(m: Med) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${m.name}?")
            .setMessage("Its reminders will stop.")
            .setPositiveButton("Delete") { _, _ ->
                Scheduler.cancelMed(this, m)
                MedStore.save(this, MedStore.load(this).filter { it.id != m.id })
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- sharing, calendar (.ics), PDF report, and JSON backup ----------

    private fun showExportMenu() {
        val items = arrayOf(
            "📋 Share schedule as text",
            "📅 Export .ics calendar file (for caregivers)",
            "🖨 Export printable PDF adherence report",
            "💾 Export JSON backup (to transfer/sync)",
            "📥 Import JSON backup"
        )
        AlertDialog.Builder(this)
            .setTitle("Share & Caregiver Tools")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> shareScheduleText()
                    1 -> exportIcsCalendar()
                    2 -> exportPdfReport()
                    3 -> exportJsonBackup()
                    4 -> promptImportJsonBackup()
                }
            }
            .show()
    }

    private fun shareScheduleText() {
        val meds = MedStore.load(this)
        if (meds.isEmpty()) { toast("No medicines to share yet."); return }
        val dfmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val text = buildString {
            append("Medication schedule\n\n")
            for (m in meds) {
                append("• ").append(listOf(m.name, m.dose).filter { it.isNotBlank() }.joinToString(" — ")).append('\n')
                append("   ").append(if (m.times.isEmpty()) "As needed" else m.times.joinToString(", ") + everyText(m))
                append(", ${dfmt.format(Date(m.startMillis))} to ${dfmt.format(Date(Scheduler.endMillis(m) - 1))}\n")
                if (m.note.isNotBlank()) append("   ").append(m.note).append('\n')
            }
            append("\nShared from Discharge Med Reminder (free & open source). Always follow the printed discharge sheet.")
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            "Share medication schedule"
        ))
    }

    private fun exportIcsCalendar() {
        val meds = MedStore.load(this).filter { it.times.isNotEmpty() }
        if (meds.isEmpty()) { toast("No scheduled medicines to export to calendar."); return }
        val utcFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val ics = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//DischargeMedReminder//FOSS//EN\r\n")
            for (m in meds) {
                for ((idx, _) in m.times.withIndex()) {
                    val firstDue = Scheduler.dueOn(m, idx, m.startMillis)
                    val endDue = firstDue + 15 * 60_000L
                    val count = (m.days + m.every - 1) / m.every
                    val cleanNote = m.note.replace('\n', ' ')
                    append("BEGIN:VEVENT\r\n")
                    append("UID:med-${m.id}-$idx-${m.startMillis}@dischargemedreminder\r\n")
                    append("DTSTAMP:${utcFmt.format(Date())}\r\n")
                    append("DTSTART:${utcFmt.format(Date(firstDue))}\r\n")
                    append("DTEND:${utcFmt.format(Date(endDue))}\r\n")
                    append("RRULE:FREQ=DAILY;INTERVAL=${m.every};COUNT=$count\r\n")
                    append("SUMMARY:Take ${m.name} ${m.dose}".trim() + "\r\n")
                    if (cleanNote.isNotBlank()) append("DESCRIPTION:$cleanNote\r\n")
                    append("END:VEVENT\r\n")
                }
            }
            append("END:VCALENDAR\r\n")
        }
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "medication_schedule.ics")
        f.writeText(ics)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/calendar")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Export .ics calendar file"
        ))
    }

    private fun exportPdfReport() {
        val meds = MedStore.load(this)
        val logs = MedStore.logs(this)
        if (meds.isEmpty() && logs.isEmpty()) { toast("Nothing to include in the PDF report yet."); return }

        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas: Canvas = page.canvas
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 16f; isFakeBoldText = true }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 11f; isFakeBoldText = true }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10f }
        val dfmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        var y = 40f
        canvas.drawText("Discharge Medication Schedule & Adherence Report", 36f, y, titlePaint)
        y += 18f
        canvas.drawText("Generated on ${dfmt.format(Date())} · Always verify against printed hospital discharge sheet", 36f, y, bodyPaint)
        y += 24f

        canvas.drawText("Prescribed Medicines", 36f, y, subPaint)
        y += 16f
        for (m in meds) {
            if (y > 760f) break
            val line1 = "• ${m.name} ${m.dose}".trim() + " — " +
                (if (m.times.isEmpty()) "As needed (SOS)" else m.times.joinToString(", ") + everyText(m))
            val line2 = "   Course: ${m.days} days (${dfmt.format(Date(m.startMillis))} – ${dfmt.format(Date(Scheduler.endMillis(m) - 1)))}" +
                if (m.note.isNotBlank()) " · ${m.note}" else ""
            canvas.drawText(line1.take(95), 36f, y, bodyPaint); y += 14f
            canvas.drawText(line2.take(100), 36f, y, bodyPaint); y += 16f
        }

        y += 10f
        if (logs.isNotEmpty() && y < 760f) {
            canvas.drawText("Recent Dose Adherence Log", 36f, y, subPaint)
            y += 16f
            for (entry in logs.take(25)) {
                if (y > 800f) break
                canvas.drawText("• ${entry.take(95)}", 36f, y, bodyPaint)
                y += 13f
            }
        }
        pdf.finishPage(page)

        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "adherence_report.pdf")
        FileOutputStream(f).use { pdf.writeTo(it) }
        pdf.close()

        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share PDF adherence report"
        ))
    }

    private fun exportJsonBackup() {
        val json = MedStore.exportBackupJson(this)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Discharge Med Reminder Backup")
                .putExtra(Intent.EXTRA_TEXT, json),
            "Export JSON backup"
        ))
    }

    private fun promptImportJsonBackup() {
        val input = field("Paste backup JSON here", "")
        AlertDialog.Builder(this)
            .setTitle("Import JSON backup")
            .setMessage("Paste the JSON backup shared from another phone:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                try {
                    val count = MedStore.importBackupJson(this, input.text.toString().trim())
                    toast("Imported $count medicine(s)")
                    refresh()
                } catch (e: Exception) {
                    toast("Invalid backup JSON format.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
