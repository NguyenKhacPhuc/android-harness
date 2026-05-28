package dev.weft.osbridge.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import dev.weft.contracts.Calendar
import dev.weft.contracts.CalendarEvent
import dev.weft.contracts.CalendarEventId
import dev.weft.contracts.CalendarFilter
import dev.weft.contracts.CalendarPatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Android implementation of [Calendar]. Reads from / writes to the system
 * CalendarContract provider. Requires READ_CALENDAR (read) and WRITE_CALENDAR
 * (create) at runtime.
 *
 * Phase-3 scope:
 *   - read(): events between two ISO-8601 instants, optional calendarIds + search.
 *   - create(): single-occurrence event inserted into the default writable
 *     calendar (first calendar with `CAL_ACCESS_OWNER`). Recurrence rules
 *     are not yet supported (Phase 5+).
 */
class AndroidCalendar(private val context: Context) : Calendar {

    override suspend fun read(filter: CalendarFilter): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val startMs = Instant.parse(filter.startIso).toEpochMilli()
        val endMs = Instant.parse(filter.endIso).toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
        )

        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        clauses += "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?"
        args += startMs.toString()
        args += endMs.toString()

        if (!filter.calendarIds.isNullOrEmpty()) {
            val placeholders = filter.calendarIds!!.joinToString(",") { "?" }
            clauses += "${CalendarContract.Events.CALENDAR_ID} IN ($placeholders)"
            args += filter.calendarIds!!
        }

        filter.search?.let {
            clauses += "(${CalendarContract.Events.TITLE} LIKE ? OR ${CalendarContract.Events.DESCRIPTION} LIKE ?)"
            args += "%$it%"
            args += "%$it%"
        }

        val results = mutableListOf<CalendarEvent>()
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            clauses.joinToString(" AND "),
            args.toTypedArray(),
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val calIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
            val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)

            while (cursor.moveToNext()) {
                results += CalendarEvent(
                    id = CalendarEventId(cursor.getLong(idIdx).toString()),
                    title = cursor.getString(titleIdx).orEmpty(),
                    startIso = Instant.ofEpochMilli(cursor.getLong(startIdx)).toString(),
                    endIso = Instant.ofEpochMilli(cursor.getLong(endIdx)).toString(),
                    location = cursor.getString(locIdx),
                    notes = cursor.getString(descIdx),
                    calendarId = cursor.getLong(calIdx).toString(),
                )
            }
        }
        results
    }

    override suspend fun create(event: CalendarEvent): CalendarEventId = withContext(Dispatchers.IO) {
        val calendarId = event.calendarId?.toLongOrNull() ?: findDefaultWritableCalendar()
            ?: error("No writable calendar found. Grant WRITE_CALENDAR and ensure at least one calendar is set up.")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, Instant.parse(event.startIso).toEpochMilli())
            put(CalendarContract.Events.DTEND, Instant.parse(event.endIso).toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            event.notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Failed to insert calendar event")

        CalendarEventId(ContentUris.parseId(uri).toString())
    }

    override suspend fun update(id: CalendarEventId, patch: CalendarPatch): Boolean = withContext(Dispatchers.IO) {
        val rowId = id.value.toLongOrNull() ?: return@withContext false
        val values = ContentValues().apply {
            patch.title?.let { put(CalendarContract.Events.TITLE, it) }
            patch.startIso?.let { put(CalendarContract.Events.DTSTART, Instant.parse(it).toEpochMilli()) }
            patch.endIso?.let { put(CalendarContract.Events.DTEND, Instant.parse(it).toEpochMilli()) }
            // Empty string ("") clears these fields; non-null non-empty
            // overwrites; null leaves them untouched.
            patch.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            patch.notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        if (values.size() == 0) return@withContext false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rowId)
        val rowsUpdated = context.contentResolver.update(uri, values, null, null)
        rowsUpdated > 0
    }

    override suspend fun delete(id: CalendarEventId): Boolean = withContext(Dispatchers.IO) {
        val rowId = id.value.toLongOrNull() ?: return@withContext false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rowId)
        val rowsDeleted = context.contentResolver.delete(uri, null, null)
        rowsDeleted > 0
    }

    /** Find the first calendar the user can write to. */
    private fun findDefaultWritableCalendar(): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            ),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }
}
