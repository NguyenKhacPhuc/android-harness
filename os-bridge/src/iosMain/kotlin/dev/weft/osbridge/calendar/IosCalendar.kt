package dev.weft.osbridge.calendar

import dev.weft.contracts.Calendar
import dev.weft.contracts.CalendarEvent
import dev.weft.contracts.CalendarEventId
import dev.weft.contracts.CalendarFilter
import dev.weft.contracts.CalendarPatch

/**
 * iOS stub for [Calendar]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `EventKit.EKEventStore` —
 * `predicateForEvents(withStart:end:calendars:)` + `events(matching:)` for read,
 * `EKEvent(eventStore:)` + `save(_:span:)` for create/update,
 * `remove(_:span:)` for delete. Permission via `requestAccess(to: .event)`
 * (iOS 16 →
 * `requestFullAccessToEvents`).
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosCalendar : Calendar {
    override suspend fun read(filter: CalendarFilter): List<CalendarEvent> =
        TODO("IosCalendar.read — wrap EKEventStore.events(matching: predicateForEvents(withStart:end:calendars:))")

    override suspend fun create(event: CalendarEvent): CalendarEventId =
        TODO("IosCalendar.create — wrap EKEvent(eventStore:) + EKEventStore.save(_:span:)")

    override suspend fun update(id: CalendarEventId, patch: CalendarPatch): Boolean =
        TODO("IosCalendar.update — wrap EKEventStore.event(withIdentifier:) + apply patch + save(_:span:)")

    override suspend fun delete(id: CalendarEventId): Boolean =
        TODO("IosCalendar.delete — wrap EKEventStore.event(withIdentifier:) + remove(_:span:)")
}
