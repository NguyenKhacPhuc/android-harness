package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.contracts.OverlayKind
import dev.weft.contracts.OverlaySpec
import dev.weft.contracts.UIUpdate
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

/**
 * `ui_notify` — show an ephemeral overlay (toast or banner) without
 * disturbing the current rendered surface.
 *
 * Use this for short feedback the user should see briefly but doesn't
 * need to interact with:
 *   - Confirmations: "Saved!", "Reminder set", "Copied to clipboard"
 *   - Errors / warnings: "Network unavailable, retrying…"
 *   - Status: "Syncing", "5 new entries"
 *
 * **Toast** (default) auto-dismisses after `durationMs` (default 3s).
 * **Banner** persists until the user dismisses (or another notify replaces it).
 *
 * This is a fire-and-forget side channel — the LLM doesn't get an event
 * back when the toast disappears. If you need user confirmation, use
 * `ui_dialog` (modal) or `ui_render` with a Button (in-tree action).
 */
public class UiNotifyTool(ctx: WeftContext) : WeftTool<UiNotifyTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "ui_notify",
        description = "Show a brief overlay message — toast (auto-dismissing) or banner (persistent). " +
            "Use for confirmations, status, errors that don't need user interaction. " +
            "Required: text. Optional: kind ('toast' default | 'banner'), title (banners only), " +
            "durationMs (toast auto-dismiss; default 3000).",
        requiredParameters = listOf(
            ToolParameterDescriptor("text", "The message to show.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("kind", "'toast' (default, auto-dismisses) or 'banner' (persists until dismissed).", ToolParameterType.String),
            ToolParameterDescriptor("title", "Optional title (renders above the message). Banners only.", ToolParameterType.String),
            ToolParameterDescriptor("durationMs", "Auto-dismiss duration for toasts. Default 3000 (3s). Set to -1 for default.", ToolParameterType.Integer),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    public data class Args(
        val text: String,
        val kind: String = "toast",
        val title: String = "",
        val durationMs: Long = DEFAULT_DURATION_MS,
    )

    override suspend fun executeWeft(args: Args): String {
        val overlayKind = when (args.kind.lowercase()) {
            "banner" -> OverlayKind.BANNER
            else -> OverlayKind.TOAST
        }
        ui.emit(
            UIUpdate.Overlay(
                OverlaySpec(
                    kind = overlayKind,
                    title = if (args.title.isNotBlank()) args.title else args.text,
                    body = if (args.title.isNotBlank()) args.text else null,
                    durationMs = args.durationMs,
                ),
            ),
        )
        return "shown (kind=${overlayKind.name.lowercase()})"
    }

    public companion object {
        public const val DEFAULT_DURATION_MS: Long = 3000L
    }
}
