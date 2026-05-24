package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Hand off to the user's clock app to create a new alarm. Returns
 * success based on whether the intent launched — the alarm itself
 * isn't created until the user confirms in their clock app. We
 * deliberately don't pass `SKIP_UI=true` even though Android allows
 * it; silently creating alarms without confirmation would be a
 * hostile surprise.
 *
 * For reminder-style "ping me in 5 minutes" cases, the agent should
 * prefer `schedule_create` (which uses the substrate's own scheduler)
 * — that's a notification, not a true OS alarm, and doesn't require
 * a user-confirmation handoff.
 */
public class AlarmSetTool(ctx: WeftContext) : WeftTool<AlarmSetTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "alarm_set",
        description = "Open the user's clock app pre-filled with a new alarm at " +
            "[hour]:[minute] (24-hour format). [label] is an optional " +
            "title for the alarm. The user confirms or cancels inside " +
            "their clock app — we can't tell which.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "hour",
                "Hour in 24-hour format (0–23).",
                ToolParameterType.Integer,
            ),
            ToolParameterDescriptor(
                "minute",
                "Minute (0–59).",
                ToolParameterType.Integer,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "label",
                "Optional alarm label (e.g. 'Standup', 'Pick up the kids').",
                ToolParameterType.String,
            ),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    public data class Args(
        val hour: Int,
        val minute: Int,
        val label: String? = null,
    )

    override suspend fun executeWeft(args: Args): String {
        val opened = os.intents.openAlarmSet(
            hour = args.hour,
            minute = args.minute,
            label = args.label,
        )
        return if (opened) {
            val timeStr = "%02d:%02d".format(args.hour.coerceIn(0, 23), args.minute.coerceIn(0, 59))
            val labelSuffix = args.label?.takeIf { it.isNotBlank() }?.let { " '$it'" } ?: ""
            "Opened the clock app with a new alarm at $timeStr$labelSuffix. " +
                "User needs to confirm it inside the app."
        } else {
            // Common on emulators / minimal ROMs / Android 16 base
            // images where no app handles ACTION_SET_ALARM. Tell the
            // LLM exactly what to suggest next: schedule_create
            // produces a notification at the requested time, which is
            // a reasonable (though softer) degradation from a real
            // OS alarm. The LLM should explain the difference to the
            // user before falling back.
            "No clock app is installed to handle the alarm intent on this device. " +
                "Tell the user this, and suggest using `schedule_create` instead — " +
                "that fires a notification at the requested time (without the loud " +
                "alarm ring or wake-from-DND behavior of a real alarm). " +
                "If the user prefers a real alarm, suggest they install Google Clock " +
                "(or any clock app) from the Play Store."
        }
    }
}
