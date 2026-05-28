package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * On-device text translation via Google ML Kit. First call to a new
 * language pair downloads a ~30MB model (one-time, takes 5-60s
 * depending on network); afterwards runs fully offline.
 *
 * Source can be omitted — we auto-detect with the language-id model.
 *
 * NOT for translating documents — pass a single chunk of text. NOT
 * for languages outside ML Kit's set (~60 languages; call
 * `translate_supported_languages` to discover).
 */
class TranslateTextTool(ctx: WeftContext) :
    WeftTool<TranslateTextTool.Args, TranslateTextTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "translate_text",
            description = "Translate text on-device. 'target' is ISO 639-1 ('en','es','fr', " +
                "'ja','zh',etc). 'source' optional — auto-detected if omitted. First call to " +
                "a pair downloads ~30MB model; subsequent calls offline. Returns null on " +
                "failure (offline + first call, unsupported pair, empty text).",
            requiredParameters = listOf(
                ToolParameterDescriptor("text", "Text to translate.", ToolParameterType.String),
                ToolParameterDescriptor(
                    "target",
                    "ISO 639-1 target language ('en', 'es', 'fr', 'ja', 'zh', 'ko', 'de', ...).",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "source",
                    "ISO 639-1 source language. Omit to auto-detect.",
                    ToolParameterType.String,
                ),
            ),
        ),
        sideEffecting = false,
    ) {

    @Serializable
    data class Args(
        val text: String,
        val target: String,
        val source: String? = null,
    )

    @Serializable
    data class Result(val translated: String? = null, val success: Boolean)

    override suspend fun executeWeft(args: Args): Result {
        val out = os.translation.translate(args.text, args.target, args.source)
        return Result(translated = out, success = out != null)
    }
}

/**
 * Detect the language of a chunk of text. Returns ISO 639-1 code
 * ("en", "ja", "zh", "de"…) or "und" when undetermined. Fast (~10ms
 * after first call) and free.
 */
class DetectLanguageTool(ctx: WeftContext) :
    WeftTool<DetectLanguageTool.Args, DetectLanguageTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "detect_language",
            description = "Detect the language of a text string. Returns ISO 639-1 code " +
                "(en, es, fr, ja, zh, de, ...) or 'und' if undetermined. ML Kit language-id, " +
                "fully offline after first call.",
            requiredParameters = listOf(
                ToolParameterDescriptor("text", "Text to detect.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    data class Args(val text: String)

    @Serializable
    data class Result(val languageCode: String)

    override suspend fun executeWeft(args: Args): Result =
        Result(languageCode = os.translation.detectLanguage(args.text))
}
