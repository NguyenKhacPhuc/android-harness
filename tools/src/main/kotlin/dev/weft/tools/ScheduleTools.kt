package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.contracts.NotificationHandle
import dev.weft.contracts.ScheduleFilter
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

/**
 * List scheduled notifications. Useful for "what reminders do I have?" prompts.
 */
class ScheduleListTool(ctx: WeftContext) : WeftTool<ScheduleListTool.Args, ScheduleListTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "schedule_list",
        description = "List currently scheduled notifications (e.g. reminders the user has set up). " +
            "Optional ISO-8601 instants narrow the window. Pass no args to list all.",
        // Required placeholder String — see SystemInfoTools.kt for the
        // rationale (Anthropic crashes on tools with zero required params).
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're listing scheduled notifications, e.g. 'user asked'. Any short string; ignored.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("beforeIso", "Only include notifications scheduled before this ISO-8601 instant.", ToolParameterType.String),
            ToolParameterDescriptor("afterIso", "Only include notifications scheduled after this ISO-8601 instant.", ToolParameterType.String),
        ),
    ),
) {

    @Serializable
    data class Args(
        val context: String = "",
        val beforeIso: String? = null,
        val afterIso: String? = null,
    )

    @Serializable
    data class Entry(val id: String, val title: String, val body: String? = null, val nextRunIso: String)

    @Serializable
    data class Result(val items: List<Entry>)

    override suspend fun executeWeft(args: Args): Result {
        val filter = if (args.beforeIso == null && args.afterIso == null) {
            null
        } else {
            ScheduleFilter(beforeIso = args.beforeIso, afterIso = args.afterIso)
        }
        val scheduled = os.notifications.listScheduled(filter)
        return Result(
            items = scheduled.map { Entry(id = it.handle.id, title = it.spec.title, body = it.spec.body, nextRunIso = it.nextRunIso) },
        )
    }
}

/**
 * Cancel a previously-scheduled notification. Reverses schedule_create.
 *
 * Not flagged destructive — the user explicitly asked to cancel; an automatic
 * confirmation prompt would be friction, not safety.
 */
class ScheduleCancelTool(ctx: WeftContext) : WeftTool<ScheduleCancelTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "schedule_cancel",
        description = "Cancel a previously-scheduled notification by id. " +
            "Use schedule_list first if you don't have the id.",
        requiredParameters = listOf(
            ToolParameterDescriptor("scheduleId", "The id returned by schedule_create or schedule_list.", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
    sideEffecting = true,
) {

    @Serializable
    data class Args(val scheduleId: String)

    override suspend fun executeWeft(args: Args): String {
        val cancelled = os.notifications.cancel(NotificationHandle(args.scheduleId))
        return if (cancelled) "Cancelled $args.scheduleId" else "No scheduled notification with id ${args.scheduleId}"
    }
}
