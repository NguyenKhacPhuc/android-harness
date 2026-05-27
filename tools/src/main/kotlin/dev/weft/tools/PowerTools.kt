package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Keep the screen awake while the foreground Activity is alive. Use
 * for: read-aloud sessions, video playback, navigation, cooking-
 * timer overlays. Always pair an `enabled=true` call with an
 * `enabled=false` call when the flow ends.
 *
 * NOT a system-wide setting — when the user backgrounds the app the
 * flag is automatically dropped by the window manager.
 */
public class PowerKeepScreenOnTool(ctx: WeftContext) :
    WeftTool<PowerKeepScreenOnTool.Args, PowerKeepScreenOnTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "power_keep_screen_on",
            description = "Pin the screen on (enabled=true) or release (enabled=false). " +
                "Scoped to the foreground Activity — drops automatically on background. " +
                "Use for read-aloud, video, navigation. Pair true/false calls.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "enabled",
                    "true to keep screen on, false to release.",
                    ToolParameterType.Boolean,
                ),
            ),
            optionalParameters = emptyList(),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(val enabled: Boolean)

    @Serializable
    public data class Result(val success: Boolean)

    override suspend fun executeWeft(args: Args): Result =
        Result(success = os.power.keepScreenOn(args.enabled))
}

/**
 * Per-window screen brightness override (0..1). Use -1 to release the
 * override back to system default. Scoped to the foreground Activity
 * — drops on background.
 *
 * Use for: dimming during read-aloud (eye comfort), maxing brightness
 * for QR scan, hand-off to a darker theme.
 */
public class PowerSetBrightnessTool(ctx: WeftContext) :
    WeftTool<PowerSetBrightnessTool.Args, PowerSetBrightnessTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "power_set_brightness",
            description = "Set per-window screen brightness 0..1 (0=dark, 1=max). Use -1 to " +
                "release back to system default. NOT system-wide — scoped to the foreground " +
                "Activity. Use for read-aloud dimming, max-bright QR scans.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "normalized",
                    "Target 0..1, or -1 to release the override.",
                    ToolParameterType.Float,
                ),
            ),
            optionalParameters = emptyList(),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(val normalized: Float)

    @Serializable
    public data class Result(val success: Boolean)

    override suspend fun executeWeft(args: Args): Result =
        Result(success = os.power.setBrightness(args.normalized))
}
