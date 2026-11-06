package com.discharge.medreminder

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var warnBox: LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { st ->
            st.getStringArrayList("captured")?.let { captured.addAll(it) }
            st.getString("pending")?.let { pendingPhoto = Uri.parse(it) }
        }
        Notifier.ensureChannel(this)

        val root = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        root.addView(label("Discharge medication reminders", 22f, true))
        root.addView(label("Photograph the medication sheet given at discharge. You will check every medicine before any reminder is set.", 14f))
        root.addView(button("📷  Photograph the medication sheet") { startCamera() })
        root.addView(button("🖼  Choose photos from the gallery") { pickPhotos.launch("image/*") })
        root.addView(button("✍  Add a medicine manually") { startActivity(Intent(this, ReviewActivity::class.java)) })
        warnBox = vertical(); root.addView(warnBox)
        todayBox = vertical(); root.addView(todayBox)
        listBox = vertical(); root.addView(listBox)

        val tools = horizontal()
        tools.addView(button("Share schedule") { shareSchedule() }, weighted())
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
    }

    // ---------- capturing pages ----------

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
        startActivity(Intent(this, CropActivity::class.java).putStringArrayListExtra("uris", ArrayList(uris)))
    }

    // ---------- home screen ----------

    private fun refresh() {
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

        // Today's doses with taken / missed status
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
                val row = horizontal()
                val prefix = when { taken -> "✓"; due < now -> "⚠"; else -> "•" }
                row.addView(label("$prefix  $time   ${m.name} ${m.dose}".trim(), 15f, !taken && due < now), weighted())
                if (taken) row.addView(label("Taken", 14f, true))
                else row.addView(button("Mark taken") { Doses.markTaken(this, m, i, due); refresh() })
                c.addView(row)
            }
            todayBox.addView(c)
        }

        // All medicines
        listBox.removeAllViews()
        listBox.addView(label("Your medicines", 19f, true))
        if (meds.isEmpty()) listBox.addView(label("No medicines yet. Photograph the discharge sheet to get started.", 14f))
        val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        val dfmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        for (m in meds) {
            val c = card()
            c.addView(label(listOf(m.name, m.dose).filter { it.isNotBlank() }.joinToString(" — "), 17f, true))
            c.addView(label(if (m.times.isEmpty()) "As needed (no reminders)" else "Times: ${m.times.joinToString(", ")}" + everyText(m), 14f))
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
            listBox.addView(label("Recently marked as taken", 17f, true))
            logs.take(10).forEach { listBox.addView(label(it, 13f)) }
        }
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

    private fun shareSchedule() {
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
}
