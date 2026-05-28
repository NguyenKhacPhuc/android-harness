package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.contracts.CalendarEvent
import dev.weft.contracts.CalendarEventId
import dev.weft.contracts.CalendarFilter
import dev.weft.contracts.CalendarPatch
import dev.weft.contracts.Permission
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

/**
 * Read calendar events in a time window. Requires CALENDAR_READ.
 */
class CalendarReadTool(ctx: WeftContext) : WeftTool<CalendarReadTool.Args, CalendarReadTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "calendar_read",
        description = "Read calendar events in an ISO-8601 time window. " +
            "Optional substring search across title + description. " +
            "Returns title, start/end, location, notes per event.",
        requiredParameters = listOf(
            ToolParameterDescriptor("startIso", "ISO-8601 instant: start of the window (inclusive).", ToolParameterType.String),
            ToolParameterDescriptor("endIso", "ISO-8601 instant: end of the window (exclusive).", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("search", "Substring match (case-insensitive) on title or description.", ToolParameterType.String),
        ),
    ),
    requiredPermissions = setOf(Permission.CALENDAR_READ),
) {

    @Serializable
    data class Args(val startIso: String, val endIso: String, val search: String? = null)

    @Serializable
    data class Event(
        val id: String?,
        val title: String,
        val startIso: String,
        val endIso: String,
        val location: String? = null,
        val notes: String? = null,
    )

    @Serializable
    data class Result(val events: List<Event>)

    override suspend fun executeWeft(args: Args): Result {
        val events = os.calendar.read(
            CalendarFilter(startIso = args.startIso, endIso = args.endIso, search = args.search),
        )
        return Result(
            events = events.map {
                Event(
                    id = it.id?.value,
                    title = it.title,
                    startIso = it.startIso,
                    endIso = it.endIso,
                    location = it.location,
                    notes = it.notes,
                )
            },
        )
    }
}

/**
 * Create a calendar event. Requires CALENDAR_WRITE.
 *
 * sideEffecting = true. Not flagged destructive (creating is additive); use
 * a separate `calendar_delete` for destructive operations (not in v1).
 */
class CalendarCreateTool(ctx: WeftContext) : WeftTool<CalendarCreateTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "calendar_create",
        description = "Create a calendar event. Inserts into the user's default writable calendar. " +
            "Recurrence rules are not yet supported — pass a single occurrence.",
        requiredParameters = listOf(
            ToolParameterDescriptor("title", "Event title.", ToolParameterType.String),
            ToolParameterDescriptor("startIso", "ISO-8601 instant: event start.", ToolParameterType.String),
            ToolParameterDescriptor("endIso", "ISO-8601 instant: event end.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("location", "Optional event location.", ToolParameterType.String),
            ToolParameterDescriptor("notes", "Optional event description/notes.", ToolParameterType.String),
        ),
    ),
    sideEffecting = true,
    requiredPermissions = setOf(Permission.CALENDAR_WRITE),
) {

    @Serializable
    data class Args(
        val title: String,
        val startIso: String,
        val endIso: String,
        val location: String? = null,
        val notes: String? = null,
    )

    override suspend fun executeWeft(args: Args): String {
        val event = CalendarEvent(
            title = args.title,
            startIso = args.startIso,
            endIso = args.endIso,
            location = args.location,
            notes = args.notes,
        )
        val id = os.calendar.create(event)
        return "Event created (id=${id.value})"
    }
}

/**
 * Update an existing calendar event. Pass only the fields you want to
 * change — null fields are left alone. Requires CALENDAR_WRITE.
 *
 * sideEffecting = true. Not flagged destructive because edits are
 * generally reversible (re-update with the old values); a `calendar_delete`
 * separate tool handles the actually-destructive case.
 */
class CalendarUpdateTool(ctx: WeftContext) : WeftTool<CalendarUpdateTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "calendar_update",
        description = "Update an existing calendar event. Pass only the fields to change. " +
            "Omit a field to leave it untouched. Use empty string ('') to clear a free-text field " +
            "(location, notes). Returns ok/not-found.",
        requiredParameters = listOf(
            ToolParameterDescriptor("id", "Event id (from calendar_read).", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("title", "New event title.", ToolParameterType.String),
            ToolParameterDescriptor("startIso", "New start, ISO-8601 instant.", ToolParameterType.String),
            ToolParameterDescriptor("endIso", "New end, ISO-8601 instant.", ToolParameterType.String),
            ToolParameterDescriptor("location", "New location ('' to clear).", ToolParameterType.String),
            ToolParameterDescriptor("notes", "New description ('' to clear).", ToolParameterType.String),
        ),
    ),
    sideEffecting = true,
    requiredPermissions = setOf(Permission.CALENDAR_WRITE),
) {

    @Serializable
    data class Args(
        val id: String,
        val title: String? = null,
        val startIso: String? = null,
        val endIso: String? = null,
        val location: String? = null,
        val notes: String? = null,
    )

    override suspend fun executeWeft(args: Args): String {
        // Guard: at least one field must be present, otherwise it's a no-op
        // round-trip the LLM didn't mean to make.
        val patch = CalendarPatch(
            title = args.title,
            startIso = args.startIso,
            endIso = args.endIso,
            location = args.location,
            notes = args.notes,
        )
        if (listOfNotNull(patch.title, patch.startIso, patch.endIso, patch.location, patch.notes).isEmpty()) {
            return "No fields supplied to update."
        }
        val ok = os.calendar.update(CalendarEventId(args.id), patch)
        return if (ok) "Event ${args.id} updated." else "No event found with id=${args.id}."
    }
}

/**
 * Delete a calendar event by id. Requires CALENDAR_WRITE. Gated by
 * `destructive = true` so the substrate runs the confirm-destructive
 * dialog before executing — irreversible operations should always
 * cross a confirmation surface.
 */
class CalendarDeleteTool(ctx: WeftContext) : WeftTool<CalendarDeleteTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "calendar_delete",
        description = "Delete a calendar event by id. Irreversible — the substrate will ask " +
            "the user to confirm before running.",
        requiredParameters = listOf(
            ToolParameterDescriptor("id", "Event id (from calendar_read).", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
    destructive = true,
    sideEffecting = true,
    requiredPermissions = setOf(Permission.CALENDAR_WRITE),
) {

    @Serializable
    data class Args(val id: String)

    override suspend fun executeWeft(args: Args): String {
        val ok = os.calendar.delete(CalendarEventId(args.id))
        return if (ok) "Event ${args.id} deleted." else "No event found with id=${args.id}."
    }
}
