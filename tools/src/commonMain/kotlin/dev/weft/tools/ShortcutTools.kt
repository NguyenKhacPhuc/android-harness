package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.ShortcutSpec
import kotlinx.serialization.Serializable

/**
 * Push a dynamic launcher shortcut. Long-press the app icon to see
 * them. Use for "save this as a quick action" flows — Pomodoro
 * preset, frequent contact, common navigation destination.
 *
 * Shortcuts are scoped to this app; the OS shows about 4-5 at a
 * time. Pushing a shortcut with an existing id replaces it.
 */
class ShortcutPushTool(ctx: WeftContext) :
    WeftTool<ShortcutPushTool.Args, ShortcutPushTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "shortcut_push",
            description = "Pin a dynamic launcher shortcut (long-press app icon to see). " +
                "'target' is what tapping the shortcut launches — URI scheme (tel:, mailto:, " +
                "https://, myapp://) or another app's package name.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "id",
                    "Stable id (alphanumeric + underscore). Re-using replaces an existing shortcut.",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "shortLabel",
                    "Short label under the icon (~10 chars).",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "target",
                    "Tap action: URI scheme or package name.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "longLabel",
                    "Longer label some surfaces show (~25 chars).",
                    ToolParameterType.String,
                ),
            ),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    data class Args(
        val id: String,
        val shortLabel: String,
        val target: String,
        val longLabel: String? = null,
    )

    @Serializable
    data class Result(val success: Boolean)

    override suspend fun executeWeft(args: Args): Result {
        val spec = ShortcutSpec(
            id = args.id,
            shortLabel = args.shortLabel,
            longLabel = args.longLabel,
            target = args.target,
        )
        return Result(success = os.shortcuts.push(spec))
    }
}

/**
 * Remove a dynamic launcher shortcut by id. No-op if missing.
 */
class ShortcutRemoveTool(ctx: WeftContext) :
    WeftTool<ShortcutRemoveTool.Args, ShortcutRemoveTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "shortcut_remove",
            description = "Remove a dynamic launcher shortcut by id. Use after the user " +
                "says 'unpin X' or when the underlying action is no longer relevant.",
            requiredParameters = listOf(
                ToolParameterDescriptor("id", "Shortcut id to remove.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    data class Args(val id: String)

    @Serializable
    data class Result(val success: Boolean)

    override suspend fun executeWeft(args: Args): Result =
        Result(success = os.shortcuts.remove(args.id))
}

/**
 * List dynamic shortcuts currently pinned by this app. Use before
 * pushing a new one to avoid id collisions, or to show "your saved
 * actions" to the user.
 */
class ShortcutListTool(ctx: WeftContext) :
    WeftTool<ShortcutListTool.Args, ShortcutListTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "shortcut_list",
            description = "List dynamic launcher shortcuts this app has pinned (id, label, " +
                "target). NOT every app's shortcuts — only this app's. No permission.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're listing. Any short string; ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    data class Args(val context: String = "")

    @Serializable
    data class Result(val shortcuts: List<ShortcutSpec>)

    override suspend fun executeWeft(args: Args): Result = Result(os.shortcuts.list())
}
