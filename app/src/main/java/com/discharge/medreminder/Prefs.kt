package com.discharge.medreminder

import android.content.Context

/**
 * The patient's usual times for morning / afternoon / evening / night doses.
 * [beforeFoodOffset] shifts "before food / empty stomach" medicines earlier by N minutes
 * (default 0 in base constructor for unit tests; 30 minutes in app settings).
 */
data class Slots(
    val morning: String = "08:00",
    val afternoon: String = "14:00",
    val evening: String = "18:00",
    val night: String = "21:00",
    val beforeFoodOffset: Int = 0
)

private val TIME_RE = Regex("""^([01]?\d|2[0-3])[:.]([0-5]\d)$""")

/** Accepts "8:00", "08.00", "20:30" … and returns "HH:mm", or null if invalid. */
fun normalizeTime(t: String): String? {
    val m = TIME_RE.matchEntire(t.trim()) ?: return null
    return "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
}

/** Shifts a 24-hour "HH:mm" time by [deltaMinutes] (e.g. -30 turns "08:00" into "07:30"). */
fun shiftMinutes(time: String, deltaMinutes: Int): String {
    val norm = normalizeTime(time) ?: return time
    val parts = norm.split(":")
    val total = ((parts[0].toInt() * 60 + parts[1].toInt() + deltaMinutes) % 1440 + 1440) % 1440
    return "%02d:%02d".format(total / 60, total % 60)
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun slots(ctx: Context): Slots {
        val d = Slots()
        val p = p(ctx)
        return Slots(
            p.getString("morning", null) ?: d.morning,
            p.getString("afternoon", null) ?: d.afternoon,
            p.getString("evening", null) ?: d.evening,
            p.getString("night", null) ?: d.night,
            p.getInt("beforeFoodOffset", 30)
        )
    }

    fun saveSlots(ctx: Context, s: Slots) {
        p(ctx).edit()
            .putString("morning", s.morning)
            .putString("afternoon", s.afternoon)
            .putString("evening", s.evening)
            .putString("night", s.night)
            .putInt("beforeFoodOffset", s.beforeFoodOffset)
            .commit()
    }

    /** Minutes after a dose to remind again if it was not marked as taken (0 = off). */
    fun followUp(ctx: Context): Int = p(ctx).getInt("followUp", 15)
    fun setFollowUp(ctx: Context, minutes: Int) { p(ctx).edit().putInt("followUp", minutes).commit() }

    /** Accessibility option for larger text across the app. */
    fun largeText(ctx: Context): Boolean = p(ctx).getBoolean("largeText", false)
    fun setLargeText(ctx: Context, enabled: Boolean) { p(ctx).edit().putBoolean("largeText", enabled).commit() }

    /** Speaks reminders and schedules aloud using Android's offline TextToSpeech. */
    fun voiceReadout(ctx: Context): Boolean = p(ctx).getBoolean("voiceReadout", true)
    fun setVoiceReadout(ctx: Context, enabled: Boolean) { p(ctx).edit().putBoolean("voiceReadout", enabled).commit() }
}
