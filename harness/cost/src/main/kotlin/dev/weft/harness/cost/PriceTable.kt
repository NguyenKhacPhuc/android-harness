package dev.weft.harness.cost

import kotlinx.serialization.Serializable

/**
 * Per-million-token prices, in USD. Loaded at app construction; Anthropic
 * adjusts prices periodically, so app authors can swap this for an updated
 * table without recompiling the substrate.
 *
 * Numbers as of 2026-05 for the models we currently use:
 *   - Sonnet 4.x / 4.6:  $3.00 input  / $15.00 output  per MTok
 *   - Opus  4.x / 4.7: $15.00 input  / $75.00 output per MTok
 *   - Haiku 3.5 / 4.5:  $0.80 input  / $4.00  output per MTok
 *
 * Cache prices (prompt caching) are tracked separately for when we wire
 * Anthropic cache_control through Koog (see `docs/follow-ups.md`).
 */
@Serializable
data class PriceTable(
    /** Map: model id → per-MTok prices. */
    val byModel: Map<String, ModelPrice> = DEFAULT_PRICES,
) {
    fun lookup(modelId: String): ModelPrice? = byModel[modelId]

    companion object {
        val DEFAULT_PRICES: Map<String, ModelPrice> = mapOf(
            "claude-sonnet-4-6" to ModelPrice(inputPerMTok = 3.00, outputPerMTok = 15.00),
            "claude-sonnet-4-5" to ModelPrice(inputPerMTok = 3.00, outputPerMTok = 15.00),
            "claude-opus-4-7" to ModelPrice(inputPerMTok = 15.00, outputPerMTok = 75.00),
            "claude-opus-4-1" to ModelPrice(inputPerMTok = 15.00, outputPerMTok = 75.00),
            "claude-haiku-4-5-20251001" to ModelPrice(inputPerMTok = 0.80, outputPerMTok = 4.00),
            "claude-haiku-3-5" to ModelPrice(inputPerMTok = 0.80, outputPerMTok = 4.00),
        )
    }
}

@Serializable
data class ModelPrice(
    val inputPerMTok: Double,
    val outputPerMTok: Double,
    val cacheReadPerMTok: Double = inputPerMTok * 0.1,         // Anthropic cache reads ~10×
    val cacheWritePerMTok: Double = inputPerMTok * 1.25,       // cache writes ~1.25×
) {
    /**
     * Compute the dollar cost of one LLM call. Cache token counts are
     * optional (zero when not surfaced).
     */
    fun costUsd(
        inputTokens: Int,
        outputTokens: Int,
        cacheReadTokens: Int = 0,
        cacheWriteTokens: Int = 0,
    ): Double {
        val billableInput = (inputTokens - cacheReadTokens - cacheWriteTokens).coerceAtLeast(0)
        val input = billableInput.toDouble() * inputPerMTok / MTOK
        val output = outputTokens.toDouble() * outputPerMTok / MTOK
        val cacheRead = cacheReadTokens.toDouble() * cacheReadPerMTok / MTOK
        val cacheWrite = cacheWriteTokens.toDouble() * cacheWritePerMTok / MTOK
        return input + output + cacheRead + cacheWrite
    }

    private companion object {
        const val MTOK = 1_000_000.0
    }
}
