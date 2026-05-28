package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.Permission
import kotlinx.serialization.Serializable

/**
 * Record audio from the microphone for up to a bounded duration. Returns
 * the file URI on success — the agent can then `external_share` the
 * recording or pass the URI to a future speech-to-text tool. Requires
 * MICROPHONE.
 *
 * sideEffecting = true. Not destructive — it only creates a new file in
 * the app's cache directory.
 */
class AudioRecordTool(ctx: WeftContext) :
    WeftTool<AudioRecordTool.Args, AudioRecordTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "audio_record",
            description = "Record audio from the microphone for up to maxDurationSeconds " +
                "(default 30, max 600). Saves as .m4a (AAC) in the app's cache directory. " +
                "Returns the file URI on success, or hasFile=false if permission was denied or " +
                "recording failed. Use the returned URI with external_share or future STT tools.",
            // `context` is a required placeholder String — see
            // SystemInfoTools.kt. Anthropic crashes if a tool has zero
            // required params (emits "" for input). Forcing one required
            // String stops the bug.
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're calling this tool, e.g. 'user asked to record'. Any short string; ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "maxDurationSeconds",
                    "Hard cap in seconds (default 30, max 600).",
                    ToolParameterType.Integer,
                ),
                ToolParameterDescriptor(
                    "namePrefix",
                    "Filename prefix (alphanumeric/underscore/hyphen only). Default 'recording'.",
                    ToolParameterType.String,
                ),
            ),
        ),
        sideEffecting = true,
        requiredPermissions = setOf(Permission.MICROPHONE),
    ) {

    @Serializable
    data class Args(
        val context: String = "",
        val maxDurationSeconds: Int = DEFAULT_SECONDS,
        val namePrefix: String = "recording",
    )

    @Serializable
    data class Result(
        val hasFile: Boolean,
        val uri: String? = null,
        val sizeBytes: Long? = null,
    )

    override suspend fun executeWeft(args: Args): Result {
        val secondsCap = args.maxDurationSeconds.coerceIn(1, MAX_SECONDS)
        val ref = os.audio.record(secondsCap * 1000L, args.namePrefix)
            ?: return Result(hasFile = false)
        return Result(hasFile = true, uri = ref.uri, sizeBytes = ref.sizeBytes)
    }

    private companion object {
        const val DEFAULT_SECONDS = 30
        const val MAX_SECONDS = 600
    }
}
