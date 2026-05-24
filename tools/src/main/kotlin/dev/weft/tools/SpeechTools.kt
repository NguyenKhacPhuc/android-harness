package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

/**
 * Speak text aloud through the device's audio output. Use for hands-free
 * flows (driving, cooking), accessibility, or anywhere the user asks the
 * assistant to "read this to me". Suspends until the utterance completes.
 */
public class SpeechSayTool(ctx: WeftContext) : WeftTool<SpeechSayTool.Args, String>(
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
    public data class Args(val text: String, val locale: String? = null)

    override suspend fun executeWeft(args: Args): String {
        if (args.text.isBlank()) return "Empty text — nothing to say."
        val ok = os.speech.say(args.text, args.locale)
        return if (ok) "Spoken." else "TTS unavailable or failed."
    }
}
