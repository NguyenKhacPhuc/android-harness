package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.SettingsPanel
import kotlinx.serialization.Serializable

/**
 * Deep-link into a system Settings panel. One tool, many destinations
 * via the [SettingsPanel] enum. Use when a tool needs the user to
 * flip a setting we can't change ourselves — turn on WiFi, grant a
 * revoked permission, change DND.
 *
 * NOT for changing settings programmatically — that's WRITE_SETTINGS
 * and is intentionally out of scope.
 */
public class SettingsOpenTool(ctx: WeftContext) :
    WeftTool<SettingsOpenTool.Args, SettingsOpenTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "settings_open",
            description = "Deep-link to a system Settings panel. 'panel' values: APP_DETAILS, " +
                "APP_NOTIFICATIONS, NOTIFICATIONS, WIFI, BLUETOOTH, DATA_USAGE, LOCATION, DATE, " +
                "ACCESSIBILITY, BATTERY, DISPLAY, SOUND, STORAGE, DEFAULT_APPS, ROOT. The user " +
                "must change settings manually — we never auto-toggle.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "panel",
                    "Which panel to open (see description). Case-insensitive.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(val panel: String)

    @Serializable
    public data class Result(val opened: Boolean, val resolvedPanel: String? = null)

    override suspend fun executeWeft(args: Args): Result {
        val panel = runCatching { SettingsPanel.valueOf(args.panel.trim().uppercase()) }
            .getOrNull() ?: return Result(opened = false)
        return Result(opened = os.settings.open(panel), resolvedPanel = panel.name)
    }
}
