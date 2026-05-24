package dev.weft.osbridge.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Fires when AlarmManager triggers a scheduled notification. Posts the
 * notification on the default substrate channel.
 *
 * Notification id and content are passed as Intent extras (the AlarmManager
 * payload survives device restart because we re-register alarms on BOOT_COMPLETED
 * — that's deferred to v1.1).
 */
class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(EXTRA_BODY)
        val channelId = intent.getStringExtra(EXTRA_CHANNEL) ?: DEFAULT_CHANNEL_ID
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, intent.hashCode())

        ensureDefaultChannel(context)

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)

        if (body != null) builder.setContentText(body)

        NotificationManagerCompat.from(context).apply {
            if (areNotificationsEnabled()) {
                notify(notificationId, builder.build())
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "dev.weft.notif.title"
        const val EXTRA_BODY = "dev.weft.notif.body"
        const val EXTRA_CHANNEL = "dev.weft.notif.channel"
        const val EXTRA_NOTIFICATION_ID = "dev.weft.notif.id"

        const val DEFAULT_CHANNEL_ID = "mas.default"
        const val DEFAULT_CHANNEL_NAME = "Notifications"

        /** Ensure the default substrate channel exists. Safe to call repeatedly. */
        fun ensureDefaultChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(DEFAULT_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                DEFAULT_CHANNEL_ID,
                DEFAULT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(channel)
        }
    }
}
