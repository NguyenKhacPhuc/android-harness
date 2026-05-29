package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.AppInfo
import dev.weft.contracts.Apps
import kotlinx.serialization.Serializable

/**
 * Check whether a specific app is installed and visible. Use BEFORE
 * `external_launch_app` so the agent can fall back when the preferred
 * app isn't available. Example: "is Spotify installed? If so, route the
 * play-music intent there; otherwise YouTube Music."
 *
 * Returns false for apps Android hides from this caller (package
 * visibility rules on Android 11+). Treat false as "not usable from
 * here", not as ground truth.
 */
class AppInstalledTool(ctx: WeftContext) :
    WeftTool<AppInstalledTool.Args, AppInstalledTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "app_installed",
            description = "Check whether a specific Android app is installed (by package " +
                "name, e.g. 'com.spotify.music'). Returns installed=true/false. Use before " +
                "external_launch_app to fall back when the preferred app is missing.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "packageName",
                    "App's Android package name, e.g. 'com.spotify.music', 'com.google.android.apps.maps'.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    data class Args(val packageName: String)

    @Serializable
    data class Result(val installed: Boolean)

    override suspend fun executeWeft(args: Args): Result {
        if (args.packageName.isBlank()) return Result(installed = false)
        return Result(installed = os.apps.isInstalled(args.packageName))
    }
}

/**
 * List apps that show up in the user's launcher drawer. Use when the
 * agent needs to discover what's installed (the user asks "what music
 * apps do I have?"). Capped at `limit` (default 50, max 500).
 *
 * Excludes system stubs the user can't actually open. Apps with no
 * launchable activity won't appear — query `app_installed` directly if
 * you know the package name.
 */
class AppListLaunchableTool(ctx: WeftContext) :
    WeftTool<AppListLaunchableTool.Args, AppListLaunchableTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "app_list_launchable",
            description = "List apps the user can open from the launcher (package, label, " +
                "version, system-flag). Sorted by label. Capped by limit (default 50, max " +
                "500). NOT every installed app — use app_installed for a specific package.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're listing, e.g. 'user asked what music apps they have'. Ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "limit",
                    "Max apps to return (default 50, max 500).",
                    ToolParameterType.Integer,
                ),
            ),
        ),
    ) {

    @Serializable
    data class Args(
        val context: String = "",
        val limit: Int = Apps.LAUNCHABLE_LIMIT_DEFAULT,
    )

    @Serializable
    data class Result(val apps: List<AppInfo>)

    override suspend fun executeWeft(args: Args): Result {
        val cap = args.limit.coerceIn(1, Apps.LAUNCHABLE_LIMIT_MAX)
        return Result(os.apps.listLaunchable(cap))
    }
}
