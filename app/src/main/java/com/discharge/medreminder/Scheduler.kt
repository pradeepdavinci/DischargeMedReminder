package com.discharge.medreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

fun todayMidnight(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** One alarm per medicine per daily time; each alarm re-arms the next dose when it fires. */
object Scheduler {
    const val ACTION_DOSE = "com.discharge.medreminder.DOSE"

    private fun reqCode(medId: Int, idx: Int, snooze: Boolean) = medId * 100 + idx + if (snooze) 50 else 0

    fun endMillis(m: Med): Long = Calendar.getInstance().apply {
        timeInMillis = m.startMillis; add(Calendar.DAY_OF_YEAR, m.days)
    }.timeInMillis

    private fun hm(time: String): Pair<Int, Int> {
        val (h, mi) = time.split(":").map { it.trim().toInt() }
        return h to mi
    }

    /** Next dose time strictly after [after], or null when the course has ended. */
    fun nextTrigger(m: Med, time: String, after: Long): Long? {
        val (h, mi) = hm(time)
        val every = max(1, m.every)
        val end = endMillis(m)
        val c = Calendar.getInstance().apply {
            timeInMillis = m.startMillis
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var day = 0
        while (c.timeInMillis <= after || day % every != 0) {
            c.add(Calendar.DAY_OF_YEAR, 1); day++
            if (c.timeInMillis >= end) return null
        }
        return if (c.timeInMillis < end) c.timeInMillis else null
    }

    fun nextDose(m: Med): Long? =
        m.times.mapNotNull { nextTrigger(m, it, System.currentTimeMillis()) }.minOrNull()

    /** True if the course has a dose on the day starting at [dayMidnight]. */
    fun isDoseDay(m: Med, dayMidnight: Long): Boolean {
        val dayIndex = ((dayMidnight - m.startMillis + 43_200_000L) / 86_400_000L).toInt()
        return dayMidnight >= m.startMillis - 43_200_000L && dayIndex in 0 until m.days && dayIndex % max(1, m.every) == 0
    }

    fun dueOn(m: Med, idx: Int, dayMidnight: Long): Long {
        val (h, mi) = hm(m.times[idx])
        return Calendar.getInstance().apply {
            timeInMillis = dayMidnight
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun pending(ctx: Context, m: Med, idx: Int, snooze: Boolean, due: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DOSE)
            .putExtra("medId", m.id).putExtra("idx", idx).putExtra("snooze", snooze).putExtra("due", due)
        return PendingIntent.getBroadcast(ctx, reqCode(m.id, idx, snooze), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun scheduleSlot(ctx: Context, m: Med, idx: Int, after: Long = System.currentTimeMillis()) {
        if (idx !in m.times.indices) return
        val t = nextTrigger(m, m.times[idx], after) ?: return
        setAlarm(ctx, t, pending(ctx, m, idx, false, t))
    }

    fun scheduleMed(ctx: Context, m: Med) = m.times.indices.forEach { scheduleSlot(ctx, m, it) }

    /** Repeat reminder for one dose (snooze or "not yet taken" follow-up). */
    fun snooze(ctx: Context, m: Med, idx: Int, minutes: Int, due: Long) =
        setAlarm(ctx, System.currentTimeMillis() + minutes * 60_000L, pending(ctx, m, idx, true, due))

    fun cancelSnooze(ctx: Context, m: Med, idx: Int) =
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, m, idx, true, 0L))

    fun cancelMed(ctx: Context, m: Med) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (idx in m.times.indices) {
            am.cancel(pending(ctx, m, idx, false, 0L))
            am.cancel(pending(ctx, m, idx, true, 0L))
            NotificationManagerCompat.from(ctx).cancel(Notifier.notifId(m.id, idx))
        }
    }

    fun rescheduleAll(ctx: Context) = MedStore.load(ctx).forEach { scheduleMed(ctx, it) }
}

object Doses {
    fun dayKey(millis: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))

    fun isTaken(ctx: Context, m: Med, idx: Int, due: Long) = MedStore.isTaken(ctx, m.id, dayKey(due), idx)

    fun isSkipped(ctx: Context, m: Med, idx: Int, due: Long) = MedStore.isSkipped(ctx, m.id, dayKey(due), idx)

    fun skipReason(ctx: Context, m: Med, idx: Int, due: Long) = MedStore.skipReason(ctx, m.id, dayKey(due), idx)

    fun markTaken(ctx: Context, m: Med, idx: Int, due: Long) {
        MedStore.setTaken(ctx, m.id, dayKey(due), idx)
        NotificationManagerCompat.from(ctx).cancel(Notifier.notifId(m.id, idx))
        Scheduler.cancelSnooze(ctx, m, idx)
        val now = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date())
        val slot = m.times.getOrNull(idx) ?: ""
        MedStore.addLog(ctx, "$now — ${m.name} ($slot dose) taken ✓")
    }

    fun unmarkTaken(ctx: Context, m: Med, idx: Int, due: Long) {
        MedStore.unsetTaken(ctx, m.id, dayKey(due), idx)
        val now = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date())
        val slot = m.times.getOrNull(idx) ?: ""
        MedStore.addLog(ctx, "$now — ${m.name} ($slot dose) unmarked (undo)")
    }

    fun markSkipped(ctx: Context, m: Med, idx: Int, due: Long, reason: String) {
        val cleanReason = reason.trim().ifBlank { "Skipped" }
        MedStore.setSkipped(ctx, m.id, dayKey(due), idx, cleanReason)
        NotificationManagerCompat.from(ctx).cancel(Notifier.notifId(m.id, idx))
        Scheduler.cancelSnooze(ctx, m, idx)
        val now = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date())
        val slot = m.times.getOrNull(idx) ?: ""
        MedStore.addLog(ctx, "$now — ${m.name} ($slot dose) skipped ($cleanReason)")
    }

    fun unmarkSkipped(ctx: Context, m: Med, idx: Int, due: Long) {
        MedStore.unsetSkipped(ctx, m.id, dayKey(due), idx)
    }
}
