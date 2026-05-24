package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

/**
 * Read the system clipboard. Returns the current primary clip's text, or
 * an empty string if the clipboard is empty / contains non-text data.
 *
 * Not flagged sideEffecting — reading is observable to the user (Android
 * 10+ shows a "Pasted from <app>" system toast on reads) but doesn't
 * modify state. Not flagged destructive — the user can always clear or
 * overwrite themselves.
 */
public class ClipboardReadTool(ctx: WeftContext) : WeftTool<ClipboardReadTool.Args, ClipboardReadTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "clipboard_read",
        description = "Read the current text in the system clipboard. Returns an empty string " +
            "if the clipboard is empty or contains non-text content (e.g. an image). On Android 10+ " +
            "the user sees a system 'Pasted from <app>' toast — treat reads as user-visible.",
        // Required placeholder String — see SystemInfoTools.kt for the
        // rationale (Anthropic crashes on empty schemas).
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're reading the clipboard, e.g. 'user asked'. Any short string; ignored.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {
    @Serializable
    public data class Args(val context: String = "")

    @Serializable
    public data class Result(val text: String)

    override suspend fun executeWeft(args: Args): Result =
        Result(text = os.clipboard.read() ?: "")
}

/**
 * Write text to the system clipboard, replacing the previous primary clip.
 * sideEffecting = true because it overwrites user state.
 */
public class ClipboardWriteTool(ctx: WeftContext) : WeftTool<ClipboardWriteTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "clipboard_write",
        description = "Copy text to the system clipboard. Replaces any previous clipboard contents. " +
            "Use to put an address, URL, code snippet, or formatted answer where the user can " +
            "paste it into another app.",
        requiredParameters = listOf(
            ToolParameterDescriptor("text", "Text to copy. Empty string clears the clipboard.", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
    sideEffecting = true,
) {
    @Serializable
    public data class Args(val text: String)

    override suspend fun executeWeft(args: Args): String {
        if (args.text.isEmpty()) {
            os.clipboard.clear()
            return "Clipboard cleared."
        }
        os.clipboard.write(args.text)
        // Surface a short preview so the trace shows what was actually
        // copied without flooding the log with megabyte payloads.
        val preview = if (args.text.length > PREVIEW_MAX) args.text.take(PREVIEW_MAX) + "…" else args.text
        return "Copied to clipboard: $preview"
    }

    private companion object {
        const val PREVIEW_MAX = 80
    }
}
