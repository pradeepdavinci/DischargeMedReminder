package com.discharge.medreminder

import android.os.Bundle
import android.widget.ScrollView
import androidx.activity.ComponentActivity

/** The patient's daily routine, used when reading new sheets (e.g. BD = morning + night). */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        val s = Prefs.slots(this)
        val root = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        root.addView(label("Usual dose times", 19f, true))
        root.addView(label("Used when reading new sheets: OD = morning, BD = morning + night, " +
            "TDS = morning + afternoon + night, QID = all four, 1-0-1 = morning + night. " +
            "Medicines already saved keep their own times (use Edit to change them).", 13f))
        val morning = field("Morning", s.morning)
        val afternoon = field("Afternoon", s.afternoon)
        val evening = field("Evening", s.evening)
        val night = field("Night", s.night)
        listOf("Morning" to morning, "Afternoon" to afternoon, "Evening" to evening, "Night / bedtime" to night).forEach {
            root.addView(label(it.first, 13f, true)); root.addView(it.second)
        }
        root.addView(label("Repeat reminder", 19f, true))
        root.addView(label("If a dose is not marked as taken, remind again after this many minutes (0 = off).", 13f))
        val follow = field("Minutes", Prefs.followUp(this).toString(), numeric = true)
        root.addView(follow)
        root.addView(button("Save") {
            val vals = listOf(morning, afternoon, evening, night).map { normalizeTime(it.text.toString()) }
            val f = follow.text.toString().toIntOrNull()
            when {
                vals.any { it == null } -> toast("Please enter times as HH:MM, e.g. 08:00")
                f == null || f !in 0..240 -> toast("Repeat reminder must be 0–240 minutes")
                else -> {
                    Prefs.saveSlots(this, Slots(vals[0]!!, vals[1]!!, vals[2]!!, vals[3]!!))
                    Prefs.setFollowUp(this, f)
                    toast("Settings saved")
                    finish()
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
