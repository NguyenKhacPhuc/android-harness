package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.VolumeStream
import kotlinx.serialization.Serializable

/**
 * Read the current 0..1 volume for an audio stream. No permission.
 * Use for: "is my ringer silenced?", "what's media volume?", "show
 * volume bars".
 */
public class VolumeGetTool(ctx: WeftContext) : WeftTool<VolumeGetTool.Args, VolumeGetTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "volume_get",
        description = "Read the current 0..1 volume for an audio stream " +
            "(MEDIA/RING/NOTIFICATION/ALARM/VOICE_CALL/SYSTEM). No permission required.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "stream",
                "Audio stream: MEDIA, RING, NOTIFICATION, ALARM, VOICE_CALL, SYSTEM.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {
    @Serializable
    public data class Args(val stream: String)

    @Serializable
    public data class Result(val stream: String, val normalized: Float)

    override suspend fun executeWeft(args: Args): Result {
        val s = parseStream(args.stream) ?: return Result(args.stream, 0f)
        return Result(s.name, os.volume.get(s))
    }
}

/**
 * Set a 0..1 volume for an audio stream. Use for "mute the ringer",
 * "turn media down", "set alarm to 50%". The system enforces DND
 * policy — returns success=false when blocked.
 */
public class VolumeSetTool(ctx: WeftContext) : WeftTool<VolumeSetTool.Args, VolumeSetTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "volume_set",
        description = "Set the 0..1 volume for an audio stream. Use for 'mute ringer', " +
            "'turn down music', 'set alarm volume'. NOT for muting the mic (different system).",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "stream",
                "MEDIA, RING, NOTIFICATION, ALARM, VOICE_CALL, SYSTEM.",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "normalized",
                "Target volume 0..1 (0 = silent, 1 = max). Values outside the range are clamped.",
                ToolParameterType.Float,
            ),
        ),
        optionalParameters = emptyList(),
    ),
    sideEffecting = true,
) {
    @Serializable
    public data class Args(val stream: String, val normalized: Float)

    @Serializable
    public data class Result(val success: Boolean, val applied: Float? = null)

    override suspend fun executeWeft(args: Args): Result {
        val s = parseStream(args.stream) ?: return Result(success = false)
        val ok = os.volume.set(s, args.normalized)
        return Result(success = ok, applied = if (ok) args.normalized.coerceIn(0f, 1f) else null)
    }
}

private fun parseStream(token: String): VolumeStream? =
    runCatching { VolumeStream.valueOf(token.trim().uppercase()) }.getOrNull()
