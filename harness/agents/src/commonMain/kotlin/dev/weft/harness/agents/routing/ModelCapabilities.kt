package dev.weft.harness.agents.routing

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel

/**
 * Provider-neutral description of what an LLM can do.
 *
 * Weft routes and degrades behaviour based on these flags rather than
 * branching on provider name. A router targeting Gemini (no parallel
 * tools) vs. Claude (parallel OK) should consult
 * [supportsParallelToolCalls]; the agent loop should skip the tool path
 * entirely when [supportsTools] is false (Llama-style fallback prompting
 * is a separate concern in the host).
 *
 * Built once per [LLModel] and stashed on the [ModelPool] keyed by
 * [LLModel.id]. [derivedFrom] reads Koog's existing capability list as a
 * starting point; providers with quirky behaviour can override by
 * constructing the data class directly.
 */
data class ModelCapabilities(
    /** Native tool/function-call API. False = need text-prompted fallback. */
    val supportsTools: Boolean,
    /** Image attachments. Video / audio not tracked separately yet. */
    val supportsVision: Boolean,
    /**
     * Provider can emit multiple tool calls in one assistant turn. Anthropic
     * + OpenAI yes; Gemini historically no, recent models yes. When false
     * the harness should not depend on parallel-tool semantics.
     */
    val supportsParallelToolCalls: Boolean,
    /**
     * Honors explicit cache-control markers (Anthropic `cache_control`,
     * Bedrock cache points). When false the [dev.weft.harness.prompt.cache.CacheBinder]
     * should be `NoOpCacheBinder` — automatic server-side caching (OpenAI)
     * still happens but doesn't need directives.
     */
    val supportsPromptCaching: Boolean,
    /**
     * Native structured-output mode (OpenAI JSON-schema, Gemini
     * responseSchema). When true the harness can request a parseable
     * JSON response via the provider's own validation; when false we
     * fall back to instruction + parse + retry.
     */
    val supportsStructuredOutput: Boolean,
    /** Total context window in tokens. Used by compaction triggers. */
    val contextWindow: Int,
    /** Cap on a single completion's output tokens. */
    val maxOutputTokens: Int,
    /**
     * Approximate per-character→token ratio. Used by the heuristic token
     * counter when no native tokenizer is configured. Anthropic and OpenAI
     * cluster around 4 chars/token for English; CJK is closer to 1.5–2.
     * Override per model only if the default skews compaction or quota.
     */
    val charsPerToken: Double = DEFAULT_CHARS_PER_TOKEN,
) {
    companion object {
        const val DEFAULT_CHARS_PER_TOKEN: Double = 4.0

        /**
         * Conservative fallback for models the router has no metadata for.
         * Assumes a modern frontier model with tools enabled; better to
         * over-provision flags and let runtime errors surface than to
         * silently disable features.
         */
        val UNKNOWN: ModelCapabilities = ModelCapabilities(
            supportsTools = true,
            supportsVision = false,
            supportsParallelToolCalls = true,
            supportsPromptCaching = false,
            supportsStructuredOutput = false,
            contextWindow = 128_000,
            maxOutputTokens = 4_096,
        )

        /**
         * Build [ModelCapabilities] from Koog's [LLModel] capability list +
         * context length. Reads the native flags Koog already tracks
         * ([LLMCapability.Tools], [LLMCapability.Vision.Image],
         * [LLMCapability.PromptCaching], [LLMCapability.Schema.JSON]) and
         * falls back to [UNKNOWN]'s values when fields are absent. Override
         * by constructing the data class directly for provider quirks
         * Koog doesn't yet encode (Gemini's lack of parallel tools is the
         * canonical example).
         */
        fun derivedFrom(model: LLModel): ModelCapabilities {
            val hasTools = model.supports(LLMCapability.Tools)
            val hasVisionImage = model.supports(LLMCapability.Vision.Image)
            val hasPromptCaching = model.supports(LLMCapability.PromptCaching)
            val hasJsonSchema = model.capabilities?.any { it is LLMCapability.Schema.JSON } ?: false
            return ModelCapabilities(
                supportsTools = hasTools,
                supportsVision = hasVisionImage,
                // Koog has no dedicated capability flag for parallel-tool
                // calls; default to true for tool-capable models. Providers
                // known to lack it (early Gemini) opt out by constructing
                // manually.
                supportsParallelToolCalls = hasTools,
                supportsPromptCaching = hasPromptCaching,
                supportsStructuredOutput = hasJsonSchema,
                contextWindow = model.contextLength?.toInt() ?: UNKNOWN.contextWindow,
                maxOutputTokens = model.maxOutputTokens?.toInt() ?: UNKNOWN.maxOutputTokens,
            )
        }
    }
}
