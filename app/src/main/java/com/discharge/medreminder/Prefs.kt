package com.discharge.medreminder

import android.content.Context

/** The patient's usual times for morning / afternoon / evening / night doses. */
data class Slots(
    val morning: String = "08:00",
    val afternoon: String = "14:00",
    val evening: String = "18:00",
    val night: String = "21:00"
)

private val TIME_RE = Regex("""^([01]?\d|2[0-3])[:.]([0-5]\d)$""")

/** Accepts "8:00", "08.00", "20:30" … and returns "HH:mm", or null if invalid. */
fun normalizeTime(t: String): String? {
    val m = TIME_RE.matchEntire(t.trim()) ?: return null
    return "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
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
            p.getString("night", null) ?: d.night
        )
    }

    fun saveSlots(ctx: Context, s: Slots) {
        p(ctx).edit().putString("morning", s.morning).putString("afternoon", s.afternoon)
            .putString("evening", s.evening).putString("night", s.night).commit()
    }

    /** Minutes after a dose to remind again if it was not marked as taken (0 = off). */
    fun followUp(ctx: Context): Int = p(ctx).getInt("followUp", 15)
    fun setFollowUp(ctx: Context, minutes: Int) { p(ctx).edit().putInt("followUp", minutes).commit() }

    /** Accessibility option for larger text across the app. */
    fun largeText(ctx: Context): Boolean = p(ctx).getBoolean("largeText", false)
    fun setLargeText(ctx: Context, enabled: Boolean) { p(ctx).edit().putBoolean("largeText", enabled).commit() }
}
