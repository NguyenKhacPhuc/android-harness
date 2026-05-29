package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.contracts.ShareContent
import dev.weft.contracts.ShareTarget
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

/**
 * Open a URL in the browser (or an in-app Custom Tab if `inApp=true`).
 */
class ExternalOpenUrlTool(ctx: WeftContext) : WeftTool<ExternalOpenUrlTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "external_open_url",
        description = "Open a URL. By default opens in the system browser; set inApp=true for an in-app browser tab.",
        requiredParameters = listOf(
            ToolParameterDescriptor("url", "The URL to open (http/https).", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("inApp", "If true, open in an in-app browser tab. Defaults to false.", ToolParameterType.Boolean),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    data class Args(val url: String, val inApp: Boolean = false)

    override suspend fun executeWeft(args: Args): String {
        val opened = os.intents.openUrl(url = args.url, inApp = args.inApp)
        return if (opened) "Opened ${args.url}" else "Failed to open ${args.url}"
    }
}

/**
 * Launch another installed app via package id (e.g. "com.whatsapp") or a
 * URI scheme (e.g. "tel:+15551234", "mailto:user@example.com").
 */
class ExternalLaunchAppTool(ctx: WeftContext) : WeftTool<ExternalLaunchAppTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "external_launch_app",
        description = "Launch another installed app. " +
            "Pass a package id ('com.whatsapp') or a URI scheme ('tel:…', 'mailto:…', 'sms:…'). " +
            "Returns false if the target isn't installed or no app handles the scheme.",
        requiredParameters = listOf(
            ToolParameterDescriptor("target", "Android package id or URI scheme.", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
    sideEffecting = true,
) {

    @Serializable
    data class Args(val target: String)

    override suspend fun executeWeft(args: Args): String {
        val launched = os.intents.launchApp(args.target, payload = null)
        return if (launched) "Launched ${args.target}" else "Couldn't launch ${args.target} (not installed?)"
    }
}

/**
 * Share text and/or a URL via the system share sheet (or to a specific app
 * by package id).
 */
class ExternalShareTool(ctx: WeftContext) : WeftTool<ExternalShareTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "external_share",
        description = "Share text and/or a URL via the system share sheet. " +
            "Pass either `text`, `url`, or both (they're concatenated). " +
            "Pass `appId` to target a specific app (e.g. 'com.twitter.android') instead of the chooser.",
        // Required placeholder String — see SystemInfoTools.kt for the
        // rationale (Anthropic crashes on tools with zero required params).
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're sharing, e.g. 'user asked'. Any short string; ignored.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("text", "Text content to share.", ToolParameterType.String),
            ToolParameterDescriptor("url", "URL to share.", ToolParameterType.String),
            ToolParameterDescriptor("appId", "Package id of a specific app to share to. Omit for the system chooser.", ToolParameterType.String),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    data class Args(
        val context: String = "",
        val text: String? = null,
        val url: String? = null,
        val appId: String? = null,
    )

    override suspend fun executeWeft(args: Args): String {
        if (args.text.isNullOrBlank() && args.url.isNullOrBlank()) {
            return "Nothing to share — pass text or url."
        }
        val target: ShareTarget = args.appId?.let { ShareTarget.SpecificApp(it) } ?: ShareTarget.SystemSheet
        val shared = os.sharing.share(content = ShareContent(text = args.text, url = args.url), target = target)
        return if (shared) "Shared" else "Failed to share"
    }
}
