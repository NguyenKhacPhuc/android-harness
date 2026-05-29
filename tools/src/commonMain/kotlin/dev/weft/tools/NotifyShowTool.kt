package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.NotificationSpec
import dev.weft.contracts.Permission
import kotlinx.serialization.Serializable

class NotifyShowTool(ctx: WeftContext) : WeftTool<NotifyShowTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "notify_show",
        description = "Show an immediate notification or in-app banner. " +
            "Use for time-relevant alerts the user should see now. " +
            "Do NOT use for scheduled reminders — call schedule_create instead.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "title",
                description = "Notification title (shown in the system tray).",
                type = ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                name = "body",
                description = "Optional notification body text.",
                type = ToolParameterType.String,
            ),
        ),
    ),
    sideEffecting = true,
    requiredPermissions = setOf(Permission.NOTIFICATIONS),
) {

    @Serializable
    data class Args(val title: String, val body: String? = null)

    override suspend fun executeWeft(args: Args): String {
        val handle = os.notifications.showNow(NotificationSpec(title = args.title, body = args.body))
        return "Notification shown (handle=${handle.id})"
    }
}
