package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.contracts.FileSaveSpec
import dev.weft.contracts.ShareTarget
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

/**
 * Save text or base64-encoded bytes to the app's private storage.
 *
 * Returns a content:// URI that other tools (and other apps via files_share)
 * can read. No filesystem permissions required — everything stays in the
 * app sandbox.
 */
public class FilesSaveTool(ctx: WeftContext) : WeftTool<FilesSaveTool.Args, FilesSaveTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "files_save",
        description = "Save content to a file in the app's private storage. " +
            "Pass either `text` (utf-8 string) or `contentBase64` (binary). " +
            "Returns a URI that can be read with files_read or shared with files_share. " +
            "Common use cases: export journal entries, save a generated summary, persist a draft.",
        requiredParameters = listOf(
            ToolParameterDescriptor("mimeType", "MIME type of the content, e.g. 'text/plain', 'application/json', 'application/pdf'.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("text", "UTF-8 text content. Use this OR contentBase64.", ToolParameterType.String),
            ToolParameterDescriptor("contentBase64", "Base64-encoded bytes. Use this OR text.", ToolParameterType.String),
            ToolParameterDescriptor("name", "Optional file name. If omitted, a unique name is generated based on mimeType.", ToolParameterType.String),
            ToolParameterDescriptor("directory", "Optional sub-directory inside the app's files dir (created if missing).", ToolParameterType.String),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    public data class Args(
        val mimeType: String,
        val text: String? = null,
        val contentBase64: String? = null,
        val name: String? = null,
        val directory: String? = null,
    )

    @Serializable
    public data class Result(val uri: String, val sizeBytes: Long)

    override suspend fun executeWeft(args: Args): Result {
        if (args.text == null && args.contentBase64 == null) {
            error("files_save requires either `text` or `contentBase64`.")
        }
        val ref = os.files.save(
            FileSaveSpec(
                contentBase64 = args.contentBase64,
                text = args.text,
                mimeType = args.mimeType,
                name = args.name,
                directory = args.directory,
            ),
        )
        return Result(uri = ref.uri, sizeBytes = ref.sizeBytes)
    }
}

/**
 * Read a file written by files_save (or any content:// URI granted to this app).
 */
public class FilesReadTool(ctx: WeftContext) : WeftTool<FilesReadTool.Args, FilesReadTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "files_read",
        description = "Read a file by URI. Returns text by default; set asBase64=true to get binary content. " +
            "Useful for reading back a previously-saved export.",
        requiredParameters = listOf(
            ToolParameterDescriptor("uri", "File URI (typically content://... from files_save).", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("asBase64", "If true, return base64-encoded bytes instead of text.", ToolParameterType.Boolean),
        ),
    ),
) {

    @Serializable
    public data class Args(val uri: String, val asBase64: Boolean = false)

    @Serializable
    public data class Result(
        val text: String? = null,
        val base64: String? = null,
        val mimeType: String,
        val sizeBytes: Long,
    )

    override suspend fun executeWeft(args: Args): Result {
        val content = os.files.read(args.uri, asBase64 = args.asBase64)
        return Result(
            text = content.text,
            base64 = content.base64,
            mimeType = content.mimeType,
            sizeBytes = content.sizeBytes,
        )
    }
}

/**
 * Share a previously-saved file via the system share sheet (or to a specific app).
 */
public class FilesShareTool(ctx: WeftContext) : WeftTool<FilesShareTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "files_share",
        description = "Share a file (by URI returned from files_save) via the system share sheet. " +
            "Pass `appId` to target a specific app (e.g. Drive, Mail) instead of the chooser.",
        requiredParameters = listOf(
            ToolParameterDescriptor("uri", "File URI to share (from files_save).", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("appId", "Package id of a specific app to share to. Omit for the system chooser.", ToolParameterType.String),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    public data class Args(val uri: String, val appId: String? = null)

    override suspend fun executeWeft(args: Args): String {
        val target: ShareTarget = args.appId?.let { ShareTarget.SpecificApp(it) } ?: ShareTarget.SystemSheet
        val ok = os.files.share(uri = args.uri, target = target)
        return if (ok) "Shared ${args.uri}" else "Failed to share ${args.uri}"
    }
}
