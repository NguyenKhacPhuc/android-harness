package dev.weft.osbridge.calendar

import dev.weft.contracts.Calendar
import dev.weft.contracts.CalendarEvent
import dev.weft.contracts.CalendarEventId
import dev.weft.contracts.CalendarFilter
import dev.weft.contracts.CalendarPatch
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKSpan
import platform.Foundation.NSISO8601DateFormatter
import kotlin.coroutines.resume

/**
 * iOS [Calendar] via EventKit. Access is requested lazily on first use —
 * the iOS 17+ full-access API with a fallback to the legacy
 * `requestAccessToEntityType`. When access is denied, [read] returns
 * empty and [update] / [delete] return false; [create] still attempts the
 * save and surfaces an EventKit error only if the store rejects it.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosCalendar : Calendar {

    private val store = EKEventStore()
    private val iso = NSISO8601DateFormatter()

    override suspend fun read(filter: CalendarFilter): List<CalendarEvent> {
        if (!ensureAccess()) return emptyList()
        val start = iso.dateFromString(filter.startIso) ?: return emptyList()
        val end = iso.dateFromString(filter.endIso) ?: return emptyList()

        val predicate = store.predicateForEventsWithStartDate(start, endDate = end, calendars = null)
        val events = store.eventsMatchingPredicate(predicate).filterIsInstance<EKEvent>()

        val search = filter.search?.takeIf { it.isNotBlank() }
        return events
            .filter { event ->
                search == null ||
                    event.title.orEmpty().contains(search, ignoreCase = true) ||
                    event.notes.orEmpty().contains(search, ignoreCase = true)
            }
            .map { it.toCalendarEvent() }
    }

    override suspend fun create(event: CalendarEvent): CalendarEventId {
        ensureAccess()
        val e = EKEvent.eventWithEventStore(store)
        e.title = event.title
        iso.dateFromString(event.startIso)?.let { e.startDate = it }
        iso.dateFromString(event.endIso)?.let { e.endDate = it }
        event.location?.let { e.location = it }
        event.notes?.let { e.notes = it }
        e.calendar = store.defaultCalendarForNewEvents
        store.saveEvent(e, span = EKSpan.EKSpanThisEvent, error = null)
        return CalendarEventId(e.eventIdentifier.orEmpty())
    }

    override suspend fun update(id: CalendarEventId, patch: CalendarPatch): Boolean {
        if (!ensureAccess()) return false
        val e = store.eventWithIdentifier(id.value) ?: return false
        patch.title?.let { e.title = it }
        patch.startIso?.let { s -> iso.dateFromString(s)?.let { e.startDate = it } }
        patch.endIso?.let { s -> iso.dateFromString(s)?.let { e.endDate = it } }
        patch.location?.let { e.location = it }
        patch.notes?.let { e.notes = it }
        return store.saveEvent(e, span = EKSpan.EKSpanThisEvent, error = null)
    }

    override suspend fun delete(id: CalendarEventId): Boolean {
        if (!ensureAccess()) return false
        val e = store.eventWithIdentifier(id.value) ?: return false
        return store.removeEvent(e, span = EKSpan.EKSpanThisEvent, error = null)
    }

    private fun EKEvent.toCalendarEvent(): CalendarEvent = CalendarEvent(
        id = eventIdentifier?.let { CalendarEventId(it) },
        title = title.orEmpty(),
        startIso = startDate?.let { iso.stringFromDate(it) }.orEmpty(),
        endIso = endDate?.let { iso.stringFromDate(it) }.orEmpty(),
        location = location,
        notes = notes,
        calendarId = calendar?.calendarIdentifier,
    )

    /**
     * Request calendar access once, preferring the iOS 17+ full-access
     * entry point and falling back to the pre-17 entity-type call.
     */
    private suspend fun ensureAccess(): Boolean = suspendCancellableCoroutine { cont ->
        val handler: (Boolean, platform.Foundation.NSError?) -> Unit = { granted, _ ->
            if (cont.isActive) cont.resume(granted)
        }
        runCatching { store.requestFullAccessToEventsWithCompletion(handler) }
            .recoverCatching {
                store.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent, completion = handler)
            }
            .onFailure { if (cont.isActive) cont.resume(false) }
    }
}
