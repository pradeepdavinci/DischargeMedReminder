package com.discharge.medreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One medicine. [times] are daily reminder times ("HH:mm", 24h). The course runs for [days]
 * days from [startMillis] (local midnight) and doses fall on every [every]-th day
 * (1 = daily, 2 = alternate days, 7 = weekly).
 */
data class Med(
    var id: Int,
    var name: String,
    var dose: String,
    var times: MutableList<String>,
    var days: Int,
    var startMillis: Long,
    var note: String,
    var every: Int = 1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("dose", dose)
        put("times", JSONArray(times)); put("days", days)
        put("start", startMillis); put("note", note); put("every", every)
    }

    companion object {
        fun fromJson(o: JSONObject): Med {
            val t = o.getJSONArray("times")
            return Med(
                o.getInt("id"), o.getString("name"), o.optString("dose"),
                MutableList(t.length()) { t.getString(it) }, o.getInt("days"),
                o.getLong("start"), o.optString("note"), o.optInt("every", 1).coerceAtLeast(1)
            )
        }

        fun listToJson(list: List<Med>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): MutableList<Med> {
            if (s.isNullOrBlank()) return mutableListOf()
            val a = JSONArray(s)
            return MutableList(a.length()) { fromJson(a.getJSONObject(it)) }
        }
    }
}

/** Everything is stored privately on the phone; nothing leaves the device. */
object MedStore {
    private const val PREFS = "meds"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Med> = Med.listFromJson(p(ctx).getString("list", null))

    fun save(ctx: Context, list: List<Med>) {
        p(ctx).edit().putString("list", Med.listToJson(list)).commit()
    }

    fun find(ctx: Context, id: Int): Med? = load(ctx).firstOrNull { it.id == id }

    fun nextId(ctx: Context): Int {
        val n = p(ctx).getInt("nextId", 1)
        p(ctx).edit().putInt("nextId", n + 1).commit()
        return n
    }

    fun addLog(ctx: Context, line: String) {
        val old = JSONArray(p(ctx).getString("log", "[]"))
        val out = JSONArray().put(line)
        for (i in 0 until minOf(old.length(), 49)) out.put(old.getString(i))
        p(ctx).edit().putString("log", out.toString()).commit()
    }

    fun logs(ctx: Context): List<String> {
        val a = JSONArray(p(ctx).getString("log", "[]"))
        return List(a.length()) { a.getString(it) }
    }

    /** Doses marked as taken, keyed "medId|yyyyMMdd|timeIndex"; kept for 60 days. */
    fun setTaken(ctx: Context, medId: Int, day: String, idx: Int) {
        val set = HashSet(p(ctx).getStringSet("taken", emptySet()) ?: emptySet())
        set.add("$medId|$day|$idx")
        val cutoff = Doses.dayKey(System.currentTimeMillis() - 60L * 86_400_000L)
        set.removeAll { key -> (key.split("|").getOrNull(1) ?: "") < cutoff }
        p(ctx).edit().putStringSet("taken", set).commit()
    }

    fun isTaken(ctx: Context, medId: Int, day: String, idx: Int): Boolean =
        p(ctx).getStringSet("taken", emptySet())?.contains("$medId|$day|$idx") == true
}
