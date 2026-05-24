package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.HapticEffect
import kotlinx.serialization.Serializable

/**
 * Play a short haptic feedback effect. Useful when the agent wants
 * tactile acknowledgement that an action landed — paired with a
 * confirmation message, after a destructive operation, on a successful
 * background task, etc. No permission required.
 */
public class HapticsTool(ctx: WeftContext) : WeftTool<HapticsTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "haptics_feedback",
        description = "Play a short haptic feedback effect on the device. " +
            "Effects: 'tick' (tiny tap), 'click' (button press), 'heavy_click' (firm tap), " +
            "'double_click' (two quick taps), 'success' (positive ascending), " +
            "'warning' (single long buzz), 'error' (sharp double buzz). " +
            "No-op on devices without a vibrator or with vibration disabled.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "effect",
                "Effect name. One of: tick, click, heavy_click, double_click, success, warning, error.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {

    @Serializable
    public data class Args(val effect: String)

    override suspend fun executeWeft(args: Args): String {
        val parsed = parse(args.effect)
            ?: return "Unknown effect '${args.effect}'. " +
                "Valid: ${HapticEffect.entries.joinToString { it.name.lowercase() }}."
        os.haptics.perform(parsed)
        return "Played haptic: ${parsed.name.lowercase()}."
    }

    private fun parse(raw: String): HapticEffect? =
        HapticEffect.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
}
