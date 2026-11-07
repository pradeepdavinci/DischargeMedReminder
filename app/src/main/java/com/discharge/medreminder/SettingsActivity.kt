package com.discharge.medreminder

import android.os.Bundle
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.ComponentActivity

/** The patient's daily routine, used when reading new sheets (e.g. BD = morning + night). */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        val s = Prefs.slots(this)
        val root = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        root.addView(label("Usual meal / dose times", 19f, true))
        root.addView(label("Used when reading new sheets: OD = morning, BD = morning + night, " +
            "TDS = morning + afternoon + night, QID = all four, 1-0-1 = morning + night. " +
            "Tap Pick to use the clock or type HH:MM.", 13f))
        val morning = field("Morning", s.morning)
        val afternoon = field("Afternoon", s.afternoon)
        val evening = field("Evening", s.evening)
        val night = field("Night", s.night)

        fun slotRow(titleText: String, edit: EditText) {
            root.addView(label(titleText, 13f, true))
            val r = horizontal()
            r.addView(edit, weighted())
            r.addView(button("🕒 Pick") {
                pickTime(edit.text.toString()) { picked -> edit.setText(picked) }
            })
            root.addView(r)
        }

        slotRow("Morning (after breakfast)", morning)
        slotRow("Afternoon (after lunch)", afternoon)
        slotRow("Evening", evening)
        slotRow("Night (after dinner / bedtime)", night)

        root.addView(label("Before-food / empty-stomach offset", 19f, true))
        root.addView(label("Automatically schedule 'before food' medicines (such as antacids and pre-meal insulin) this many minutes earlier than your meal times (e.g. 30 min → 07:30 instead of 08:00).", 13f))
        val beforeOffset = field("Minutes before meal", s.beforeFoodOffset.toString(), numeric = true)
        root.addView(beforeOffset)

        root.addView(label("Repeat reminder", 19f, true))
        root.addView(label("If a dose is not marked as taken, remind again after this many minutes (0 = off).", 13f))
        val follow = field("Minutes", Prefs.followUp(this).toString(), numeric = true)
        root.addView(follow)

        root.addView(label("Voice & Accessibility", 19f, true))
        val voiceCheck = checkBox("🔊 Speak reminders aloud (offline voice read-out)", Prefs.voiceReadout(this))
        root.addView(voiceCheck)
        val largeTextCheck = checkBox("Use larger text across the app", Prefs.largeText(this))
        root.addView(largeTextCheck)

        root.addView(button("Save") {
            val vals = listOf(morning, afternoon, evening, night).map { normalizeTime(it.text.toString()) }
            val offsetVal = beforeOffset.text.toString().toIntOrNull()
            val f = follow.text.toString().toIntOrNull()
            when {
                vals.any { it == null } -> toast("Please enter times as HH:MM, e.g. 08:00")
                offsetVal == null || offsetVal !in 0..120 -> toast("Before-food offset must be 0–120 minutes")
                f == null || f !in 0..240 -> toast("Repeat reminder must be 0–240 minutes")
                else -> {
                    Prefs.saveSlots(this, Slots(vals[0]!!, vals[1]!!, vals[2]!!, vals[3]!!, offsetVal))
                    Prefs.setFollowUp(this, f)
                    Prefs.setVoiceReadout(this, voiceCheck.isChecked)
                    Prefs.setLargeText(this, largeTextCheck.isChecked)
                    toast("Settings saved")
                    finish()
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
