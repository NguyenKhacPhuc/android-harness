package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.Permission
import kotlinx.serialization.Serializable

/**
 * Steps walked today, as best the device can tell. Returns
 * available=false when the device has no step counter. The first call
 * after midnight returns 0 (we're establishing the baseline);
 * subsequent calls return the delta.
 *
 * Use for: activity-aware reminders ("you've been still all morning,
 * want a stretch alarm?"), fitness goal nudges, conversational replies
 * about how much the user has moved today.
 */
class SensorStepsTodayTool(ctx: WeftContext) :
    WeftTool<SensorStepsTodayTool.Args, SensorStepsTodayTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "sensor_steps_today",
            description = "Read approximate steps walked since midnight (TYPE_STEP_COUNTER). " +
                "Returns available=false if the device has no step sensor. The first call " +
                "after midnight returns 0 — it establishes the daily baseline.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're asking, e.g. 'user asked how many steps today'. Ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
        // ACTIVITY_RECOGNITION is runtime on API 29+. Pre-29 the
        // os-bridge mapper returns null → gate treats as implicit GRANTED.
        requiredPermissions = setOf(Permission.ACTIVITY_RECOGNITION),
    ) {

    @Serializable
    data class Args(val context: String = "")

    @Serializable
    data class Result(val available: Boolean, val steps: Int? = null)

    override suspend fun executeWeft(args: Args): Result {
        val n = os.sensors.stepsToday()
        return if (n == null) Result(available = false) else Result(available = true, steps = n)
    }
}

/**
 * One-shot ambient light reading in lux. Returns available=false when
 * the sensor isn't present (most tablets, many low-end phones).
 *
 * Lux reference: bright sunlight ~100,000; office ~500; dim room ~10;
 * dark room <1.
 *
 * Use for: "the room is dark — should I dim the rendered chart?",
 * heuristic dark-mode suggestions, ambient-aware UX.
 */
class SensorAmbientLightTool(ctx: WeftContext) :
    WeftTool<SensorAmbientLightTool.Args, SensorAmbientLightTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "sensor_ambient_light",
            description = "Read ambient light in lux (TYPE_LIGHT). Reference: sunlight " +
                "~100000, office ~500, dim room ~10, dark <1. Returns available=false when " +
                "the sensor isn't present or didn't fire within ~1.5s.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're checking, e.g. 'user asked if it's dark'. Ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    data class Args(val context: String = "")

    @Serializable
    data class Result(val available: Boolean, val lux: Float? = null)

    override suspend fun executeWeft(args: Args): Result {
        val lux = os.sensors.ambientLightLux()
        return if (lux == null) Result(available = false) else Result(available = true, lux = lux)
    }
}
