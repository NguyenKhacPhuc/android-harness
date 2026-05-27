package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.MediaPickerKind
import kotlinx.serialization.Serializable

/**
 * Open the system Photo Picker so the user explicitly selects an
 * image (or video). NO permission required — the OS picker mediates
 * access. Prefer this over `media_list_recent` / `media_query`
 * whenever the user picks the file, not the agent.
 *
 * Use for: "attach a photo", "share a screenshot of …", "pick from
 * your gallery". Suspends until the user picks or cancels.
 */
public class MediaPickImageTool(ctx: WeftContext) :
    WeftTool<MediaPickImageTool.Args, MediaPickImageTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "media_pick_image",
            description = "Open the system Photo Picker so the user explicitly chooses " +
                "image(s). No permission required. Use for 'pick a photo', 'attach an " +
                "image'. maxItems=1 (default) picks one; >1 enables multi-select.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're asking the user to pick, e.g. 'attach to share'. Ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "maxItems",
                    "1 (default) for single, 2-100 for multi-select.",
                    ToolParameterType.Integer,
                ),
            ),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(val context: String = "", val maxItems: Int = 1)

    @Serializable
    public data class Result(val uris: List<String>)

    override suspend fun executeWeft(args: Args): Result =
        Result(os.mediaPicker.pick(MediaPickerKind.IMAGE, args.maxItems.coerceAtLeast(1)))
}

/**
 * Same as `media_pick_image` but for video files. No permission.
 */
public class MediaPickVideoTool(ctx: WeftContext) :
    WeftTool<MediaPickVideoTool.Args, MediaPickVideoTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "media_pick_video",
            description = "Open the system Photo Picker so the user explicitly chooses " +
                "video(s). No permission required. Use for 'pick a video', 'attach a clip'.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're asking the user to pick. Ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "maxItems",
                    "1 (default) or 2-100 for multi-select.",
                    ToolParameterType.Integer,
                ),
            ),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(val context: String = "", val maxItems: Int = 1)

    @Serializable
    public data class Result(val uris: List<String>)

    override suspend fun executeWeft(args: Args): Result =
        Result(os.mediaPicker.pick(MediaPickerKind.VIDEO, args.maxItems.coerceAtLeast(1)))
}

/**
 * Open the Photo Picker for image OR video. No permission. Use when
 * the user request doesn't disambiguate ("pick something to share").
 */
public class MediaPickAnyTool(ctx: WeftContext) :
    WeftTool<MediaPickAnyTool.Args, MediaPickAnyTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "media_pick_any",
            description = "Open the Photo Picker for image OR video — user chooses kind. " +
                "No permission. Use when the user's request doesn't say which.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're asking. Ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "maxItems",
                    "1 (default) or 2-100.",
                    ToolParameterType.Integer,
                ),
            ),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(val context: String = "", val maxItems: Int = 1)

    @Serializable
    public data class Result(val uris: List<String>)

    override suspend fun executeWeft(args: Args): Result =
        Result(os.mediaPicker.pick(MediaPickerKind.IMAGE_OR_VIDEO, args.maxItems.coerceAtLeast(1)))
}
