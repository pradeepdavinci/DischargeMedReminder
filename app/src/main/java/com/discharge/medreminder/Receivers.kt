package com.discharge.medreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val medId = intent.getIntExtra("medId", -1)
        val idx = intent.getIntExtra("idx", 0)
        val due = intent.getLongExtra("due", System.currentTimeMillis())
        val m = MedStore.find(ctx, medId) ?: return
        if (idx !in m.times.indices) return
        when (intent.action) {
            Scheduler.ACTION_DOSE -> {
                val repeat = intent.getBooleanExtra("snooze", false)
                if (!repeat) Scheduler.scheduleSlot(ctx, m, idx, System.currentTimeMillis() + 60_000L)
                if (Doses.isTaken(ctx, m, idx, due) || Doses.isSkipped(ctx, m, idx, due)) return
                Notifier.showDose(ctx, m, idx, due, repeat)
                val followUp = Prefs.followUp(ctx)
                if (!repeat && followUp > 0) Scheduler.snooze(ctx, m, idx, followUp, due)
            }
            Notifier.ACTION_TAKEN -> Doses.markTaken(ctx, m, idx, due)
            Notifier.ACTION_SKIP -> Doses.markSkipped(ctx, m, idx, due, "Skipped from notification")
            Notifier.ACTION_SNOOZE -> {
                NotificationManagerCompat.from(ctx).cancel(Notifier.notifId(medId, idx))
                Scheduler.snooze(ctx, m, idx, 10, due)
            }
        }
    }
}

/** Alarms are cleared when the phone restarts or the clock changes; this puts them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Scheduler.rescheduleAll(ctx)
}
