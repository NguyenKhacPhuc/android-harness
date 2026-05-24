package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.contracts.Permission
import dev.weft.contracts.ScreenSpec
import dev.weft.contracts.UIUpdate
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken
import kotlinx.serialization.json.JsonObject

/**
 * Navigate to a named template in the design system.
 *
 * Phase-3 limitation: until the template registry lands (Phase 4), the host
 * just records the navigation request. The agent's intent is preserved in
 * the trace; rendering becomes real once templates exist.
 */
public class UiNavigateTool(ctx: WeftContext) : WeftTool<UiNavigateTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "ui_navigate",
        description = "Navigate to a named screen template. Pass props for the template. " +
            "Use to show the user a screen as part of the response. Template names will be " +
            "documented under design system; for now valid names are reserved for Phase 4.",
        requiredParameters = listOf(
            ToolParameterDescriptor("template", "Template id (e.g. 'List', 'Timer', 'Form').", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "props",
                "Template-specific props object.",
                ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList()),
            ),
        ),
    ),
) {

    @Serializable
    public data class Args(
        val template: String,
        val props: JsonObject = JsonObject(emptyMap()),
    )

    override suspend fun executeWeft(args: Args): String {
        ui.emit(UIUpdate.Navigate(ScreenSpec(template = args.template, props = args.props)))
        return "Navigated to ${args.template}"
    }
}

/**
 * Show an informational modal dialog. Suspends until the user dismisses it.
 *
 * For YES/NO or text input, use ui_ask. For destructive confirmations, the
 * substrate runs its own gate — don't call ui_dialog for that.
 */
public class UiDialogTool(ctx: WeftContext) : WeftTool<UiDialogTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "ui_dialog",
        description = "Show an informational dialog to the user and wait for them to dismiss it. " +
            "Use for explanations, warnings, or final messages that don't require an answer.",
        requiredParameters = listOf(
            ToolParameterDescriptor("title", "Dialog title.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("body", "Optional body text.", ToolParameterType.String),
        ),
    ),
) {

    @Serializable
    public data class Args(val title: String, val body: String? = null)

    override suspend fun executeWeft(args: Args): String {
        ui.showInfo(title = args.title, body = args.body)
        return "Dialog dismissed"
    }
}

/**
 * Request a runtime permission from the user.
 *
 * Phase-3 limitation: the Android implementation in :os-bridge currently
 * checks permission state but does not actually launch the system prompt
 * (that needs an Activity-hosted ActivityResultLauncher). For now this
 * returns the current state; an Activity-level prompt flow lands later.
 */
public class UiRequestPermissionTool(ctx: WeftContext) : WeftTool<UiRequestPermissionTool.Args, UiRequestPermissionTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "ui_request_permission",
        description = "Request a runtime permission from the user. Returns the resulting state " +
            "(GRANTED, DENIED, DENIED_FOREVER, NOT_DETERMINED). Call this when another tool " +
            "returned PERMISSION_DENIED with a hint to invoke this one.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "permission",
                "Permission identifier. Allowed values: NOTIFICATIONS, CALENDAR_READ, CALENDAR_WRITE, CONTACTS_READ, LOCATION, CAMERA, MICROPHONE.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("rationale", "Optional explanation shown to the user about why this is needed.", ToolParameterType.String),
        ),
    ),
) {

    @Serializable
    public data class Args(val permission: String, val rationale: String? = null)

    @Serializable
    public data class Result(val state: String)

    override suspend fun executeWeft(args: Args): Result {
        val perm = Permission.entries.firstOrNull { it.name == args.permission.uppercase() }
            ?: error("Unknown permission '${args.permission}'. Allowed: ${Permission.entries.joinToString { it.name }}")
        val state = os.permissions.request(perm)
        return Result(state = state.name)
    }
}
