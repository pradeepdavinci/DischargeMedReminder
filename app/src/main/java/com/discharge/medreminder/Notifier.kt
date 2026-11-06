package com.discharge.medreminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CHANNEL = "doses"
    const val CHANNEL_LOUD = "doses_loud"
    const val ACTION_TAKEN = "com.discharge.medreminder.TAKEN"
    const val ACTION_SNOOZE = "com.discharge.medreminder.SNOOZE"
    const val ACTION_SKIP = "com.discharge.medreminder.SKIP"

    fun notifId(medId: Int, idx: Int) = medId * 100 + idx

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "Medication reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders to take discharge medicines"
                enableVibration(true)
            }
            nm.createNotificationChannel(ch)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val loudCh = NotificationChannel(CHANNEL_LOUD, "Critical medication alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Loud continuous alarms for critical discharge medicines"
                enableVibration(true)
                setSound(alarmUri, attrs)
            }
            nm.createNotificationChannel(loudCh)
        }
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun action(ctx: Context, action: String, m: Med, idx: Int, due: Long, code: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action)
            .putExtra("medId", m.id).putExtra("idx", idx).putExtra("due", due)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showDose(ctx: Context, m: Med, idx: Int, due: Long, repeat: Boolean) {
        ensureChannel(ctx)
        if (!allowed(ctx)) return
        val id = notifId(m.id, idx)
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val time = m.times.getOrNull(idx) ?: ""
        val title = if (repeat) "Reminder: ${m.name} ($time) not marked as taken" else "Time to take ${m.name} ($time)"
        val body = listOf(m.dose, m.note).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { "Scheduled $time dose" }
        val channelId = if (m.loud) CHANNEL_LOUD else CHANNEL
        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (m.loud) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (m.loud) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setWhen(due).setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Taken", action(ctx, ACTION_TAKEN, m, idx, due, id * 10 + 1))
            .addAction(0, "Snooze 10 min", action(ctx, ACTION_SNOOZE, m, idx, due, id * 10 + 2))
            .addAction(0, "Skip", action(ctx, ACTION_SKIP, m, idx, due, id * 10 + 3))
        if (m.loud) {
            builder.setFullScreenIntent(open, true)
        }
        val n = builder.build()
        if (m.loud) {
            n.flags = n.flags or Notification.FLAG_INSISTENT
        }
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    fun showTest(ctx: Context) {
        ensureChannel(ctx)
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Test reminder")
            .setContentText("Medication reminders are working on this phone.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(999_999, n)
    }
}
