package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.contracts.NotificationSpec
import dev.weft.contracts.Permission
import dev.weft.contracts.ScheduleSpec
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

class ScheduleCreateTool(ctx: WeftContext) : WeftTool<ScheduleCreateTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "schedule_create",
        description = "Schedule a one-time notification at a specific time. " +
            "Use for time-based reminders. whenExpr must be an ISO-8601 instant " +
            "(e.g. '2026-06-01T09:00:00Z'). Recurring schedules are not supported yet.",
        requiredParameters = listOf(
            ToolParameterDescriptor("title", "Notification title.", ToolParameterType.String),
            ToolParameterDescriptor(
                "whenExpr",
                "ISO-8601 instant when the notification should fire (e.g. '2026-06-01T09:00:00Z').",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("body", "Optional notification body.", ToolParameterType.String),
            ToolParameterDescriptor(
                "timezone",
                "Optional IANA timezone name (e.g. 'America/Los_Angeles').",
                ToolParameterType.String,
            ),
        ),
    ),
    sideEffecting = true,
    requiredPermissions = setOf(Permission.NOTIFICATIONS),
) {

    @Serializable
    data class Args(
        val title: String,
        val whenExpr: String,
        val body: String? = null,
        val timezone: String? = null,
    )

    override suspend fun executeWeft(args: Args): String {
        val handle = os.notifications.schedule(
            spec = NotificationSpec(title = args.title, body = args.body),
            schedule = ScheduleSpec(expr = args.whenExpr, timezone = args.timezone),
        )
        return "Scheduled at ${args.whenExpr} (id=${handle.id})"
    }
}
