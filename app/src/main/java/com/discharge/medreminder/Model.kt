package com.discharge.medreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One medicine. [times] are daily reminder times (HH:mm). */
data class Med(
    var id: Int,
    var name: String,
    var dose: String,
    var times: MutableList<String>,
    var days: Int,
    var startMillis: Long,
    var note: String,
    var every: Int = 1,
    var loud: Boolean = false,
    var photoPath: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("dose", dose); put("times", JSONArray(times))
        put("days", days); put("start", startMillis); put("note", note); put("every", every)
        put("loud", loud); put("photoPath", photoPath)
    }

    companion object {
        fun fromJson(o: JSONObject): Med {
            val t = o.getJSONArray("times")
            return Med(o.getInt("id"), o.getString("name"), o.optString("dose"),
                MutableList(t.length()) { t.getString(it) }, o.getInt("days"), o.getLong("start"),
                o.optString("note"), o.optInt("every", 1).coerceAtLeast(1),
                o.optBoolean("loud", false), o.optString("photoPath"))
        }
        fun listToJson(list: List<Med>) = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
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
    fun load(ctx: Context) = Med.listFromJson(p(ctx).getString("list", null))
    fun save(ctx: Context, list: List<Med>) { p(ctx).edit().putString("list", Med.listToJson(list)).commit() }
    fun find(ctx: Context, id: Int) = load(ctx).firstOrNull { it.id == id }
    fun nextId(ctx: Context): Int { val n = p(ctx).getInt("nextId", 1); p(ctx).edit().putInt("nextId", n + 1).commit(); return n }
    fun addLog(ctx: Context, line: String) {
        val old = JSONArray(p(ctx).getString("log", "[]")); val out = JSONArray().put(line)
        for (i in 0 until minOf(old.length(), 199)) out.put(old.getString(i))
        p(ctx).edit().putString("log", out.toString()).commit()
    }
    fun logs(ctx: Context): List<String> { val a = JSONArray(p(ctx).getString("log", "[]")); return List(a.length()) { a.getString(it) } }
    fun setTaken(ctx: Context, medId: Int, day: String, idx: Int) {
        val set = HashSet(p(ctx).getStringSet("taken", emptySet()) ?: emptySet()); set.add("$medId|$day|$idx")
        val cutoff = Doses.dayKey(System.currentTimeMillis() - 60L * 86_400_000L)
        set.removeAll { (it.split("|").getOrNull(1) ?: "") < cutoff }
        p(ctx).edit().putStringSet("taken", set).commit(); unsetSkipped(ctx, medId, day, idx)
    }
    fun unsetTaken(ctx: Context, medId: Int, day: String, idx: Int) {
        val set = HashSet(p(ctx).getStringSet("taken", emptySet()) ?: emptySet()); set.remove("$medId|$day|$idx"); p(ctx).edit().putStringSet("taken", set).commit()
    }
    fun isTaken(ctx: Context, medId: Int, day: String, idx: Int) = p(ctx).getStringSet("taken", emptySet())?.contains("$medId|$day|$idx") == true
    fun setSkipped(ctx: Context, medId: Int, day: String, idx: Int, reason: String) {
        val obj = try { JSONObject(p(ctx).getString("skipped", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val cutoff = Doses.dayKey(System.currentTimeMillis() - 60L * 86_400_000L)
        val keys = mutableListOf<String>(); val it = obj.keys(); while (it.hasNext()) keys.add(it.next())
        keys.filter { (it.split("|").getOrNull(1) ?: "") < cutoff }.forEach { obj.remove(it) }
        obj.put("$medId|$day|$idx", reason.ifBlank { "Skipped" }); p(ctx).edit().putString("skipped", obj.toString()).commit(); unsetTaken(ctx, medId, day, idx)
    }
    fun unsetSkipped(ctx: Context, medId: Int, day: String, idx: Int) {
        val obj = try { JSONObject(p(ctx).getString("skipped", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        obj.remove("$medId|$day|$idx"); p(ctx).edit().putString("skipped", obj.toString()).commit()
    }
    fun skipReason(ctx: Context, medId: Int, day: String, idx: Int): String? {
        val obj = try { JSONObject(p(ctx).getString("skipped", "{}") ?: "{}") } catch (_: Exception) { return null }
        return if (obj.has("$medId|$day|$idx")) obj.optString("$medId|$day|$idx", "Skipped") else null
    }
    fun isSkipped(ctx: Context, medId: Int, day: String, idx: Int) = skipReason(ctx, medId, day, idx) != null
    fun saveLastScanUris(ctx: Context, uris: List<String>) { p(ctx).edit().putString("lastScanUris", JSONArray().apply { uris.forEach { put(it) } }.toString()).commit() }
    fun lastScanUris(ctx: Context): List<String> = try {
        val a = JSONArray(p(ctx).getString("lastScanUris", "[]") ?: "[]"); List(a.length()) { a.getString(it) }
    } catch (_: Exception) { emptyList() }
    fun exportBackupJson(ctx: Context): String {
        val s = Prefs.slots(ctx)
        return JSONObject().apply {
            put("version", 3); put("meds", JSONArray(Med.listToJson(load(ctx))))
            put("slots", JSONObject().apply { put("morning", s.morning); put("afternoon", s.afternoon); put("evening", s.evening); put("night", s.night) })
            put("followUp", Prefs.followUp(ctx)); put("reviewAt", Prefs.reviewAt(ctx)); put("readAloud", Prefs.readAloud(ctx)); put("logs", JSONArray(logs(ctx)))
        }.toString(2)
    }
    fun importBackupJson(ctx: Context, json: String): Int {
        val root = JSONObject(json)
        root.optJSONObject("slots")?.let { so -> val d = Slots(); Prefs.saveSlots(ctx, Slots(
            normalizeTime(so.optString("morning", d.morning)) ?: d.morning,
            normalizeTime(so.optString("afternoon", d.afternoon)) ?: d.afternoon,
            normalizeTime(so.optString("evening", d.evening)) ?: d.evening,
            normalizeTime(so.optString("night", d.night)) ?: d.night)) }
        if (root.has("followUp")) Prefs.setFollowUp(ctx, root.optInt("followUp", 15).coerceIn(0, 240))
        if (root.has("reviewAt")) Prefs.setReviewAt(ctx, root.optLong("reviewAt", 0L))
        if (root.has("readAloud")) Prefs.setReadAloud(ctx, root.optBoolean("readAloud", false))
        val arr = root.optJSONArray("meds") ?: JSONArray(); val imported = MutableList(arr.length()) { Med.fromJson(arr.getJSONObject(it)) }
        val existing = load(ctx)
        imported.forEach { it.id = nextId(ctx); existing.add(it); Scheduler.scheduleMed(ctx, it) }
        save(ctx, existing); return imported.size
    }
}
