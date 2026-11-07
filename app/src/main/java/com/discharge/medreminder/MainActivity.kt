package com.discharge.medreminder

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
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
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    private lateinit var warnBox: LinearLayout
    private lateinit var rescanBox: LinearLayout
    private lateinit var todayBox: LinearLayout
    private lateinit var followUpBox: LinearLayout
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
        followUpBox = vertical(); root.addView(followUpBox)
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("captured", captured)
        pendingPhoto?.let { outState.putString("pending", it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (intent?.getBooleanExtra("triggerRescan", false) == true) {
            intent.removeExtra("triggerRescan")
            showRescanDialog()
        }
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

    private fun divider(): View = View(this).apply {
        setBackgroundColor(if (isDarkTheme()) 0xFF334155.toInt() else 0xFFD0DCE5.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(0, dp(6), 0, dp(6))
        }
    }

    private fun speakTodaySchedule(doses: List<Triple<String, Med, Int>>, today: Long) {
        val pending = doses.filter { (_, m, i) ->
            val due = Scheduler.dueOn(m, i, today)
            !Doses.isTaken(this, m, i, due) && !Doses.isSkipped(this, m, i, due)
        }
        if (pending.isEmpty()) {
            SpeechHelper.speak(this, "All scheduled medicines for today have been taken.", force = true)
            toast("Speaking schedule…")
            return
        }
        val speech = buildString {
            append("You have ${pending.size} remaining dose")
            if (pending.size > 1) append("s")
            append(" today. ")
            pending.forEach { (time, m, _) ->
                val rel = if (Scheduler.isBeforeFoodMed(m)) "before food" else "after food"
                append("At $time, $rel: ${m.name} ${m.dose}. ")
            }
        }
        SpeechHelper.speak(this, speech, force = true)
        toast("Speaking today's remaining doses…")
    }

    private fun shiftBeforeFoodMedsEarlier() {
        val slots = Prefs.slots(this)
        val offset = slots.beforeFoodOffset.coerceAtLeast(15)
        val standardSlots = setOf(slots.morning, slots.afternoon, slots.evening, slots.night)
        val all = MedStore.load(this)
        var changed = 0
        for (m in all) {
            if (Scheduler.isBeforeFoodMed(m) && m.times.any { it in standardSlots }) {
                Scheduler.cancelMed(this, m)
                m.times = m.times.map { t ->
                    if (t in standardSlots) shiftMinutes(t, -offset) else t
                }.distinct().sorted().toMutableList()
                if (!m.note.contains("Before food", ignoreCase = true)) {
                    m.note = listOf("Before food", m.note).filter { it.isNotBlank() }.joinToString("; ")
                }
                Scheduler.scheduleMed(this, m)
                changed++
            }
        }
        if (changed > 0) {
            MedStore.save(this, all)
            toast("Shifted $changed before-food medicine(s) $offset min earlier")
            refresh()
        }
    }

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

        // Today's doses grouped by meal routine (Before Breakfast, After Breakfast, Lunch, Dinner)
        todayBox.removeAllViews()
        val doses = meds.flatMap { m ->
            if (m.times.isEmpty() || !Scheduler.isDoseDay(m, today)) emptyList()
            else m.times.indices.map { i -> Triple(m.times[i], m, i) }
        }.sortedWith(compareBy({ Scheduler.mealGroup(it.second, it.first).first }, { it.first }))

        if (doses.isNotEmpty()) {
            val todayHeader = horizontal()
            todayHeader.addView(label("Today's Routine", 19f, true), weighted())
            todayHeader.addView(button("🔊 Speak today") { speakTodaySchedule(doses, today) })
            todayBox.addView(todayHeader)

            val slots = Prefs.slots(this)
            val standardSlots = setOf(slots.morning, slots.afternoon, slots.evening, slots.night)
            val hasUnshiftedBeforeFood = meds.any { m ->
                Scheduler.isBeforeFoodMed(m) && m.times.any { it in standardSlots }
            }
            if (hasUnshiftedBeforeFood) {
                val tipCard = card()
                tipCard.addView(label("💡 Separate Empty-Stomach & After-Food times?", 14f, true))
                tipCard.addView(label("Some empty-stomach or insulin medicines are set to the exact same time as your after-food medicines.", 13f))
                tipCard.addView(button("🌅 Shift Before-Food medicines ${slots.beforeFoodOffset.coerceAtLeast(15)} min earlier") {
                    shiftBeforeFoodMedsEarlier()
                })
                todayBox.addView(tipCard)
            }

            val grouped = doses.groupBy { Scheduler.mealGroup(it.second, it.first) }
            for ((groupKey, groupDoses) in grouped) {
                val groupTitle = groupKey.second
                val c = card()
                val groupHeader = horizontal()
                groupHeader.addView(label(groupTitle, 16f, true), weighted())
                c.addView(groupHeader)

                val pendingInGroup = groupDoses.filter { (_, m, i) ->
                    val due = Scheduler.dueOn(m, i, today)
                    !Doses.isTaken(this, m, i, due) && !Doses.isSkipped(this, m, i, due)
                }
                if (pendingInGroup.size >= 2) {
                    c.addView(button("✓ Mark all ${pendingInGroup.size} in this group taken") {
                        pendingInGroup.forEach { (_, m, i) ->
                            Doses.markTaken(this, m, i, Scheduler.dueOn(m, i, today))
                        }
                        refresh()
                    })
                }

                groupDoses.forEachIndexed { doseIdx, (time, m, i) ->
                    if (doseIdx > 0 || pendingInGroup.size >= 2) c.addView(divider())
                    val due = Scheduler.dueOn(m, i, today)
                    val taken = Doses.isTaken(this, m, i, due)
                    val skipReason = Doses.skipReason(this, m, i, due)
                    val skipped = skipReason != null
                    val itemBox = vertical().apply { setPadding(0, dp(4), 0, dp(4)) }

                    // Row 1: Time/Status badge + full-width medicine name & dose
                    val topRow = horizontal()
                    val statusBadge = when {
                        taken -> badge("✓ $time Taken", 0xFF16A34A.toInt())
                        skipped -> badge("⏭ $time Skipped", 0xFF64748B.toInt())
                        due < now -> badge("⚠ $time Overdue", 0xFFDC2626.toInt())
                        else -> badge("🕒 $time", 0xFF2563EB.toInt())
                    }
                    topRow.addView(statusBadge)
                    val medTitle = listOf(m.name, m.dose).filter { it.isNotBlank() }.joinToString(" — ")
                    topRow.addView(
                        label("  $medTitle", 16f, true).apply { setPadding(dp(8), dp(4), 0, dp(4)) },
                        weighted()
                    )
                    itemBox.addView(topRow)

                    if (m.note.isNotBlank()) {
                        itemBox.addView(label(m.note, 13f))
                    }
                    if (skipped && !skipReason.isNullOrBlank()) {
                        itemBox.addView(label("Reason: $skipReason", 12f))
                    }

                    // Row 2: Action buttons on their own row
                    val actionRow = horizontal()
                    when {
                        taken -> {
                            actionRow.addView(button("↩ Undo taken") { Doses.unmarkTaken(this, m, i, due); refresh() }, weighted())
                        }
                        skipped -> {
                            actionRow.addView(button("↩ Undo skip") { Doses.unmarkSkipped(this, m, i, due); refresh() }, weighted())
                        }
                        else -> {
                            actionRow.addView(button("✓ Mark taken") { Doses.markTaken(this, m, i, due); refresh() }, weighted())
                            actionRow.addView(button("⏭ Skip") { promptSkipDose(m, i, due) }, weighted())
                        }
                    }
                    itemBox.addView(actionRow)
                    c.addView(itemBox)
                }
                todayBox.addView(c)
            }
        }

        // Doctor follow-up checkup card
        followUpBox.removeAllViews()
        val dfmt = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
        val fuDate = MedStore.followUpDate(this)
        val fuCard = card()
        val fuHeader = horizontal()
        fuHeader.addView(label("🏥 Doctor Follow-Up Visit", 17f, true), weighted())
        if (fuDate > 0L) {
            val diffDays = ((fuDate - today + 43_200_000L) / 86_400_000L).toInt()
            val statusText = when {
                diffDays < 0 -> "${-diffDays}d ago"
                diffDays == 0 -> "Today!"
                else -> "In $diffDays day(s)"
            }
            val color = if (diffDays <= 1) 0xFFD97706.toInt() else 0xFF2563EB.toInt()
            fuHeader.addView(badge(statusText, color))
        }
        fuCard.addView(fuHeader)
        if (fuDate > 0L) {
            fuCard.addView(label("Scheduled: ${dfmt.format(Date(fuDate))}", 15f, true))
            fuCard.addView(label(MedStore.followUpNote(this), 13f))
        } else {
            fuCard.addView(label("No follow-up checkup date set yet.", 13f))
        }
        val fuBtns = horizontal()
        fuBtns.addView(button(if (fuDate > 0L) "📅 Change date" else "📅 Set follow-up date") {
            promptFollowUpDate(fuDate.takeIf { it > 0L } ?: (today + 7 * 86_400_000L))
        }, weighted())
        if (fuDate > 0L) {
            fuBtns.addView(button("Clear") {
                MedStore.saveFollowUpDate(this, 0L)
                Scheduler.scheduleFollowUpVisit(this)
                refresh()
            })
        }
        fuCard.addView(fuBtns)
        followUpBox.addView(fuCard)

        // All medicines with "Day X of Y" progress badge
        listBox.removeAllViews()
        listBox.addView(label("Your medicines", 19f, true))
        if (meds.isEmpty()) listBox.addView(label("No medicines yet. Photograph or import the discharge sheet to get started.", 14f))
        val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        val shortDateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        for (m in meds) {
            val c = card()
            val header = horizontal()
            header.addView(label(listOf(m.name, m.dose).filter { it.isNotBlank() }.joinToString(" — "), 17f, true), weighted())
            if (m.loud) header.addView(badge("🔔 Loud alarm", 0xFFD97706.toInt()))
            c.addView(header)

            val progressRow = horizontal()
            val progressText = Scheduler.courseProgressText(m, today)
            val progressColor = if (progressText.startsWith("✓")) 0xFF64748B.toInt() else 0xFF0284C7.toInt()
            progressRow.addView(badge(progressText, progressColor))
            if (Scheduler.isBeforeFoodMed(m)) {
                progressRow.addView(label("  🌅 Empty stomach / Before food", 13f, true))
            }
            c.addView(progressRow)

            val timesJoined = m.times.joinToString(", ")
            c.addView(label(if (m.times.isEmpty()) "As needed (no reminders)" else "Times: $timesJoined" + everyText(m), 14f))
            c.addView(label("Course: ${m.days} day(s), ${shortDateFmt.format(Date(m.startMillis))} – ${shortDateFmt.format(Date(Scheduler.endMillis(m) - 1))}", 13f))
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

    private fun promptFollowUpDate(initialMillis: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = initialMillis }
        DatePickerDialog(this, { _, y, mo, d ->
            val picked = Calendar.getInstance().apply { clear(); set(y, mo, d) }.timeInMillis
            val noteInput = field("Follow-up note (e.g. OPD review with blood sugar report)", MedStore.followUpNote(this))
            AlertDialog.Builder(this)
                .setTitle("Follow-up details")
                .setView(noteInput)
                .setPositiveButton("Save reminder") { _, _ ->
                    MedStore.saveFollowUpDate(this, picked, noteInput.text.toString())
                    Scheduler.scheduleFollowUpVisit(this)
                    toast("Doctor follow-up reminder set")
                    refresh()
                }
                .show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
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
        val fuDate = MedStore.followUpDate(this)
        val fuNote = MedStore.followUpNote(this)
        val text = buildString {
            append("Medication schedule\n\n")
            for (m in meds) {
                append("• ").append(listOf(m.name, m.dose).filter { it.isNotBlank() }.joinToString(" — ")).append('\n')
                append("   ").append(if (m.times.isEmpty()) "As needed" else m.times.joinToString(", ") + everyText(m))
                append(", ${dfmt.format(Date(m.startMillis))} to ${dfmt.format(Date(Scheduler.endMillis(m) - 1))}\n")
                if (m.note.isNotBlank()) append("   ").append(m.note).append('\n')
            }
            if (fuDate > 0L) {
                append("\nDoctor Follow-Up: ${dfmt.format(Date(fuDate))} ($fuNote)\n")
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

        val fuDate = MedStore.followUpDate(this)
        if (fuDate > 0L) {
            canvas.drawText("Doctor Follow-Up: ${dfmt.format(Date(fuDate))} — ${MedStore.followUpNote(this)}", 36f, y, subPaint)
            y += 20f
        }

        canvas.drawText("Prescribed Medicines", 36f, y, subPaint)
        y += 16f
        for (m in meds) {
            if (y > 760f) break
            val line1 = "• ${m.name} ${m.dose}".trim() + " — " +
                (if (m.times.isEmpty()) "As needed (SOS)" else m.times.joinToString(", ") + everyText(m))
            val line2 = "   Course: ${m.days} days (${dfmt.format(Date(m.startMillis))} – ${dfmt.format(Date(Scheduler.endMillis(m) - 1))})" +
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
