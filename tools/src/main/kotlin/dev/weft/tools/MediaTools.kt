package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.MediaFilter
import dev.weft.contracts.MediaItem
import dev.weft.contracts.MediaKind
import dev.weft.contracts.MediaLibrary
import dev.weft.contracts.Permission
import kotlinx.serialization.Serializable

/**
 * List the user's most recent gallery items, newest first. Returns
 * content:// URIs the agent can hand to `vision_ocr`, `vision_barcode`,
 * `external_share`, or `files_read` for downstream processing.
 *
 * NOT for picking a specific photo — use `media_query` with a filter
 * (date range, name substring). NOT for the camera — use `camera_capture`.
 */
public class MediaListRecentTool(ctx: WeftContext) :
    WeftTool<MediaListRecentTool.Args, MediaListRecentTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "media_list_recent",
            description = "List the user's most recent gallery items (newest first). 'kinds' " +
                "is a comma-separated list of IMAGE,VIDEO,AUDIO (default IMAGE). Returns " +
                "content:// URIs usable with vision_ocr / external_share / files_read.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're listing, e.g. 'user asked for their last few photos'. Ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "kinds",
                    "Comma-separated subset of IMAGE,VIDEO,AUDIO. Default IMAGE.",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "limit",
                    "Max items to return (default 20, max 200).",
                    ToolParameterType.Integer,
                ),
            ),
        ),
        // We declare all three permissions — the tool gate will request
        // whichever ones map to the requested kinds. Apps that only
        // want images can wrap this tool with a narrower one.
        requiredPermissions = setOf(
            Permission.READ_MEDIA_IMAGES,
            Permission.READ_MEDIA_VIDEO,
            Permission.READ_MEDIA_AUDIO,
        ),
    ) {

    @Serializable
    public data class Args(
        val context: String = "",
        val kinds: String = "IMAGE",
        val limit: Int = MediaLibrary.LIST_LIMIT_DEFAULT,
    )

    @Serializable
    public data class Result(val items: List<MediaItem>)

    override suspend fun executeWeft(args: Args): Result {
        val kinds = parseKinds(args.kinds)
        val cap = args.limit.coerceIn(1, MediaLibrary.LIST_LIMIT_MAX)
        return Result(os.mediaLibrary.listRecent(kinds, cap))
    }
}

/**
 * Filtered gallery query — narrow to a date range, name substring, or
 * specific media kinds. Use when the user references something more
 * specific than "recent" ("photos from last weekend", "videos with
 * 'beach' in the name").
 */
public class MediaQueryTool(ctx: WeftContext) :
    WeftTool<MediaQueryTool.Args, MediaQueryTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "media_query",
            description = "Search the user's gallery with optional filters. 'kinds' is " +
                "comma-separated IMAGE,VIDEO,AUDIO (default IMAGE). Optional sinceEpochMs / " +
                "untilEpochMs bound the date range; nameContains filters by display name " +
                "(case-insensitive substring). Returns content:// URIs newest first.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're querying, e.g. 'user wants photos from last weekend'.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "kinds",
                    "Comma-separated subset of IMAGE,VIDEO,AUDIO. Default IMAGE.",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "sinceEpochMs",
                    "Earliest DATE_ADDED (epoch ms inclusive). Null = no lower bound.",
                    ToolParameterType.Integer,
                ),
                ToolParameterDescriptor(
                    "untilEpochMs",
                    "Latest DATE_ADDED (epoch ms inclusive). Null = no upper bound.",
                    ToolParameterType.Integer,
                ),
                ToolParameterDescriptor(
                    "nameContains",
                    "Case-insensitive substring filter on the display name.",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "limit",
                    "Max items to return (default 20, max 200).",
                    ToolParameterType.Integer,
                ),
            ),
        ),
        requiredPermissions = setOf(
            Permission.READ_MEDIA_IMAGES,
            Permission.READ_MEDIA_VIDEO,
            Permission.READ_MEDIA_AUDIO,
        ),
    ) {

    @Serializable
    public data class Args(
        val context: String = "",
        val kinds: String = "IMAGE",
        val sinceEpochMs: Long? = null,
        val untilEpochMs: Long? = null,
        val nameContains: String? = null,
        val limit: Int = MediaLibrary.LIST_LIMIT_DEFAULT,
    )

    @Serializable
    public data class Result(val items: List<MediaItem>)

    override suspend fun executeWeft(args: Args): Result {
        val filter = MediaFilter(
            kinds = parseKinds(args.kinds),
            sinceEpochMs = args.sinceEpochMs,
            untilEpochMs = args.untilEpochMs,
            nameContains = args.nameContains,
            limit = args.limit.coerceIn(1, MediaLibrary.LIST_LIMIT_MAX),
        )
        return Result(os.mediaLibrary.query(filter))
    }
}

private fun parseKinds(raw: String): Set<MediaKind> {
    if (raw.isBlank()) return setOf(MediaKind.IMAGE)
    return raw.split(',')
        .mapNotNull { token ->
            runCatching { MediaKind.valueOf(token.trim().uppercase()) }.getOrNull()
        }
        .toSet()
        .ifEmpty { setOf(MediaKind.IMAGE) }
}
