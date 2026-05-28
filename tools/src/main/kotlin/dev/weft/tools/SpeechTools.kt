package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.contracts.Permission
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

/**
 * Speak text aloud through the device's audio output. Use for hands-free
 * flows (driving, cooking), accessibility, or anywhere the user asks the
 * assistant to "read this to me". Suspends until the utterance completes.
 */
class SpeechSayTool(ctx: WeftContext) : WeftTool<SpeechSayTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "speech_say",
        description = "Read text aloud through the device's audio output (TTS). Suspends until " +
            "the utterance completes. Use for hands-free flows, accessibility, or read-aloud " +
            "requests. Optional locale (e.g. 'en-US', 'fr-FR') — falls back to device default if " +
            "unsupported.",
        requiredParameters = listOf(
            ToolParameterDescriptor("text", "Text to speak.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("locale", "BCP-47 locale tag (e.g. 'en-US').", ToolParameterType.String),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    data class Args(val text: String, val locale: String? = null)

    override suspend fun executeWeft(args: Args): String {
        if (args.text.isBlank()) return "Empty text — nothing to say."
        val ok = os.speech.say(args.text, args.locale)
        return if (ok) "Spoken." else "TTS unavailable or failed."
    }
}

/**
 * Listen on the microphone and return what the user said. Use for
 * voice input flows ("dictate a note", "what did the user say?"), or
 * any hands-free interaction where typing isn't an option. Requires
 * MICROPHONE.
 *
 * NOT for ambient recording — use `audio_record` for that. This tool
 * stops as soon as the user pauses speaking, returns the transcript,
 * and tears down the recognizer.
 */
class SpeechRecognizeTool(ctx: WeftContext) :
    WeftTool<SpeechRecognizeTool.Args, SpeechRecognizeTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "speech_recognize",
            description = "Listen on the microphone and transcribe what the user says " +
                "(speech-to-text). Suspends until the user pauses or maxDurationSeconds " +
                "elapses, then returns the transcript. Use for dictation, voice commands, " +
                "or hands-free input. NOT for recording — use audio_record for that.",
            // Placeholder required parameter — same Anthropic-empty-args
            // workaround used by battery_status / network_status. See
            // SystemInfoTools.kt for the full explanation.
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're listening, e.g. 'user asked to dictate a note'.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "locale",
                    "BCP-47 locale tag (e.g. 'en-US', 'ja-JP'). Defaults to device locale.",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "maxDurationSeconds",
                    "Hard cap on listening time (default 10, max 60).",
                    ToolParameterType.Integer,
                ),
            ),
        ),
        sideEffecting = true,
        requiredPermissions = setOf(Permission.MICROPHONE),
    ) {

    @Serializable
    data class Args(
        val context: String = "",
        val locale: String? = null,
        val maxDurationSeconds: Int = DEFAULT_SECONDS,
    )

    @Serializable
    data class Result(
        val recognized: Boolean,
        val text: String? = null,
        val confidence: Float? = null,
        val alternatives: List<String> = emptyList(),
    )

    override suspend fun executeWeft(args: Args): Result {
        val secondsCap = args.maxDurationSeconds.coerceIn(1, MAX_SECONDS)
        val outcome = os.speech.recognize(args.locale, secondsCap * 1000L)
            ?: return Result(recognized = false)
        return Result(
            recognized = true,
            text = outcome.text,
            confidence = outcome.confidence,
            alternatives = outcome.alternatives,
        )
    }

    private companion object {
        const val DEFAULT_SECONDS = 10
        const val MAX_SECONDS = 60
    }
}
