package dev.weft.osbridge.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import dev.weft.contracts.Notifications
import dev.weft.contracts.NotificationHandle
import dev.weft.contracts.NotificationSpec
import dev.weft.contracts.ScheduleFilter
import dev.weft.contracts.ScheduleSpec
import dev.weft.contracts.ScheduledNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/**
 * Android implementation of [Notifications].
 *
 * Composition:
 *   - showNow → NotificationManagerCompat on the default substrate channel.
 *   - schedule → AlarmManager.setExactAndAllowWhileIdle (or set() on devices that
 *     don't grant SCHEDULE_EXACT_ALARM), with a PendingIntent that fires
 *     [NotificationReceiver].
 *   - cancel → AlarmManager.cancel + remove from the [ScheduledNotificationStore].
 *   - listScheduled → reads from [ScheduledNotificationStore].
 *
 * The interface is platform-agnostic; everything below it is Android-specific.
 */
class AndroidNotifications(
    private val context: Context,
    private val store: ScheduledNotificationStore,
) : Notifications {

    private val alarms: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override suspend fun showNow(spec: NotificationSpec): NotificationHandle = withContext(Dispatchers.Default) {
        NotificationReceiver.ensureDefaultChannel(context)
        val notifId = randomNotificationId()
        val handle = NotificationHandle(UUID.randomUUID().toString())

        val builder = NotificationCompat.Builder(
            context,
            spec.channelId ?: NotificationReceiver.DEFAULT_CHANNEL_ID,
        )
            .setContentTitle(spec.title)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        spec.body?.let { builder.setContentText(it) }

        val mgr = NotificationManagerCompat.from(context)
        if (mgr.areNotificationsEnabled()) {
            mgr.notify(notifId, builder.build())
        }
        handle
    }

    override suspend fun schedule(spec: NotificationSpec, schedule: ScheduleSpec): NotificationHandle = withContext(Dispatchers.Default) {
        NotificationReceiver.ensureDefaultChannel(context)
        val triggerEpochMs = parseScheduleExpr(schedule.expr)
        val handle = NotificationHandle(UUID.randomUUID().toString())
        val notifId = randomNotificationId()

        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra(NotificationReceiver.EXTRA_TITLE, spec.title)
            spec.body?.let { putExtra(NotificationReceiver.EXTRA_BODY, it) }
            putExtra(NotificationReceiver.EXTRA_CHANNEL, spec.channelId ?: NotificationReceiver.DEFAULT_CHANNEL_ID)
            putExtra(NotificationReceiver.EXTRA_NOTIFICATION_ID, notifId)
            // Disambiguate distinct PendingIntents by handle id.
            action = "dev.weft.notif.fire.${handle.id}"
        }

        val pending = PendingIntent.getBroadcast(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpochMs, pending)
        } else {
            // Inexact fallback. The script catalog flag for "needs exact" can choose to
            // prompt the user for SCHEDULE_EXACT_ALARM before reaching here (Phase 3).
            alarms.set(AlarmManager.RTC_WAKEUP, triggerEpochMs, pending)
        }

        store.add(
            ScheduledNotification(
                handle = handle,
                spec = spec,
                nextRunIso = Instant.ofEpochMilli(triggerEpochMs).toString(),
            ),
        )
        handle
    }

    override suspend fun cancel(handle: NotificationHandle): Boolean = withContext(Dispatchers.Default) {
        val existing = store.get(handle) ?: return@withContext false

        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = "dev.weft.notif.fire.${handle.id}"
        }
        // We don't have the original notifId here; use FLAG_NO_CREATE + same action+component to retrieve.
        val pending = PendingIntent.getBroadcast(
            context,
            existing.spec.title.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pending?.let { alarms.cancel(it) }
        store.remove(handle)
        true
    }

    override suspend fun listScheduled(filter: ScheduleFilter?): List<ScheduledNotification> =
        withContext(Dispatchers.Default) { store.list(filter) }

    private fun randomNotificationId(): Int = (System.currentTimeMillis() and 0x7fffffff).toInt()

    /**
     * Parse the ScheduleSpec.expr. v1 accepts only ISO-8601 instants
     * (one-shot). Recurring RRULE-style expressions are deferred to Phase 5.
     */
    private fun parseScheduleExpr(expr: String): Long {
        return try {
            Instant.parse(expr).toEpochMilli()
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "ScheduleSpec.expr must be an ISO-8601 instant (e.g. 2026-06-01T09:00:00Z). " +
                    "Recurring expressions are deferred to v1.1.",
                e,
            )
        }
    }
}
