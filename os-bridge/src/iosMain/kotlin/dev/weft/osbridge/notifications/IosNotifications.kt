package dev.weft.osbridge.notifications

import dev.weft.contracts.NotificationHandle
import dev.weft.contracts.NotificationSpec
import dev.weft.contracts.Notifications
import dev.weft.contracts.ScheduleFilter
import dev.weft.contracts.ScheduleSpec
import dev.weft.contracts.ScheduledNotification
import dev.weft.contracts.TapAction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationTrigger
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * iOS [Notifications] via `UserNotifications`. [showNow] adds an immediate
 * request (0.1s interval trigger); [schedule] parses the ISO-8601 instant in
 * [ScheduleSpec.expr] into calendar components for a `UNCalendarNotificationTrigger`,
 * falling back to a 60s interval trigger when the instant can't be parsed.
 * Authorization (alert + sound + badge) is requested lazily before each add.
 *
 * [listScheduled] reads the center's pending requests and rebuilds each
 * [NotificationSpec] from the stored content title/body — the tap action's
 * params aren't rehydrated here.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosNotifications : Notifications {

    private val center: UNUserNotificationCenter
        get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun showNow(spec: NotificationSpec): NotificationHandle {
        requestAuthorization()
        val id = NSUUID().UUIDString
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = IMMEDIATE_INTERVAL_S,
            repeats = false,
        )
        addRequest(id, content(spec), trigger)
        return NotificationHandle(id)
    }

    override suspend fun schedule(spec: NotificationSpec, schedule: ScheduleSpec): NotificationHandle {
        requestAuthorization()
        val id = NSUUID().UUIDString
        val date = NSISO8601DateFormatter().dateFromString(schedule.expr)
        val trigger = if (date != null) {
            val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
                NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
            val components = NSCalendar.currentCalendar.components(units, fromDate = date)
            UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
        } else {
            // expr wasn't a parseable instant — fall back to a near-future fire
            // rather than dropping the notification entirely.
            UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
                timeInterval = FALLBACK_INTERVAL_S,
                repeats = false,
            )
        }
        addRequest(id, content(spec), trigger)
        return NotificationHandle(id)
    }

    override suspend fun cancel(handle: NotificationHandle): Boolean {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(handle.id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(handle.id))
        return true
    }

    override suspend fun listScheduled(filter: ScheduleFilter?): List<ScheduledNotification> {
        val pending: List<UNNotificationRequest> = suspendCancellableCoroutine { cont ->
            center.getPendingNotificationRequestsWithCompletionHandler { requests ->
                if (cont.isActive) cont.resume(requests.orEmpty().filterIsInstance<UNNotificationRequest>())
            }
        }
        val nowIso = NSISO8601DateFormatter().stringFromDate(NSDate())
        val all = pending.map { req ->
            val content = req.content
            val nextRun = (req.trigger as? UNCalendarNotificationTrigger)
                ?.nextTriggerDate()
                ?.let { NSISO8601DateFormatter().stringFromDate(it) }
                ?: nowIso
            ScheduledNotification(
                handle = NotificationHandle(req.identifier),
                spec = NotificationSpec(
                    title = content.title,
                    body = content.body.takeIf { it.isNotEmpty() },
                ),
                nextRunIso = nextRun,
            )
        }
        if (filter == null) return all
        return all.filter { entry ->
            (filter.beforeIso?.let { entry.nextRunIso <= it } ?: true) &&
                (filter.afterIso?.let { entry.nextRunIso >= it } ?: true)
        }
    }

    private fun content(spec: NotificationSpec): UNMutableNotificationContent {
        val content = UNMutableNotificationContent()
        content.setTitle(spec.title)
        spec.body?.let { content.setBody(it) }
        spec.tapAction?.let { content.setUserInfo(userInfo(it)) }
        return content
    }

    private fun userInfo(tap: TapAction): Map<Any?, *> = mapOf(
        USER_INFO_TOOL to tap.tool,
        USER_INFO_PARAMS to tap.params.toString(),
    )

    private suspend fun addRequest(
        id: String,
        content: UNMutableNotificationContent,
        trigger: UNNotificationTrigger?,
    ) {
        val request = UNNotificationRequest.requestWithIdentifier(id, content, trigger)
        suspendCancellableCoroutine<Unit> { cont ->
            center.addNotificationRequest(request) { _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private suspend fun requestAuthorization() {
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        suspendCancellableCoroutine<Unit> { cont ->
            center.requestAuthorizationWithOptions(options) { _, _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private companion object {
        const val IMMEDIATE_INTERVAL_S = 0.1
        const val FALLBACK_INTERVAL_S = 60.0
        const val USER_INFO_TOOL = "weft.tapAction.tool"
        const val USER_INFO_PARAMS = "weft.tapAction.params"
    }
}
