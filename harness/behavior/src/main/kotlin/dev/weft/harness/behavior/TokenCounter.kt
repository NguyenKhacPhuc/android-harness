package dev.weft.harness.behavior

/**
 * Pluggable token estimator. The [Compactor] consults this when deciding
 * whether the conversation history is too large to send unchanged; the
 * cost middleware consults a related path when attributing usage.
 *
 * Every provider tokenizes differently — there is no single right answer.
 * Use [HeuristicTokenCounter] when "good enough to trigger compaction"
 * suffices; swap in a real per-provider tokenizer (Anthropic
 * count-tokens endpoint, OpenAI's `tiktoken`, Gemini's `count_tokens`)
 * when accurate budget tracking matters.
 */
fun interface TokenCounter {
    /** Estimate the token count of [text] for this counter's target model. */
    fun estimate(text: String): Int
}

/**
 * Character-ratio fallback. Defaults to 4 chars/token which fits English
 * for both Claude and GPT to within ~10%. Asian-language-heavy
 * workloads should bump the ratio down (closer to 1.5–2) via the
 * [charsPerToken] override.
 *
 * Cheap, deterministic, no I/O. Suitable as the default for the
 * substrate; production deployments that bill per-token should replace
 * with a real tokenizer.
 */
class HeuristicTokenCounter(
    private val charsPerToken: Double = DEFAULT_CHARS_PER_TOKEN,
) : TokenCounter {
    init {
        require(charsPerToken > 0) { "charsPerToken must be > 0, was $charsPerToken" }
    }

    override fun estimate(text: String): Int =
        (text.length / charsPerToken).toInt().coerceAtLeast(0)

    companion object {
        const val DEFAULT_CHARS_PER_TOKEN: Double = 4.0
    }
}
