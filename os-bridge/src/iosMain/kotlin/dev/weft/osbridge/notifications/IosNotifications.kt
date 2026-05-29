package dev.weft.osbridge.notifications

import dev.weft.contracts.NotificationHandle
import dev.weft.contracts.NotificationSpec
import dev.weft.contracts.Notifications
import dev.weft.contracts.ScheduleFilter
import dev.weft.contracts.ScheduleSpec
import dev.weft.contracts.ScheduledNotification

/**
 * iOS stub for [Notifications]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UserNotifications.UNUserNotificationCenter` —
 * `add(UNNotificationRequest)` for immediate + scheduled (using
 * `UNTimeIntervalNotificationTrigger` / `UNCalendarNotificationTrigger`),
 * `removePendingNotificationRequests(withIdentifiers:)` for cancel, and
 * `getPendingNotificationRequests` for the scheduled list.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosNotifications : Notifications {
    override suspend fun showNow(spec: NotificationSpec): NotificationHandle =
        TODO("IosNotifications.showNow — wrap UNUserNotificationCenter.current().add(UNNotificationRequest) with a nil trigger")

    override suspend fun schedule(spec: NotificationSpec, schedule: ScheduleSpec): NotificationHandle =
        TODO("IosNotifications.schedule — wrap UNUserNotificationCenter.add with UNCalendarNotificationTrigger / UNTimeIntervalNotificationTrigger")

    override suspend fun cancel(handle: NotificationHandle): Boolean =
        TODO("IosNotifications.cancel — wrap UNUserNotificationCenter.removePendingNotificationRequests(withIdentifiers:)")

    override suspend fun listScheduled(filter: ScheduleFilter?): List<ScheduledNotification> =
        TODO("IosNotifications.listScheduled — wrap UNUserNotificationCenter.getPendingNotificationRequests")
}
