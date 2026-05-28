package dev.weft.harness.agents.strategy

import dev.weft.harness.agents.routing.ModelTier
import dev.weft.harness.behavior.Turn
import dev.weft.harness.prompt.cache.CacheTier
import dev.weft.harness.prompt.multimodal.WeftUserInput
import dev.weft.harness.reliability.RetryPolicy

/**
 * Cost-minimizing [WeftStrategy] for demos, dev builds, or quota-pressured
 * deployments. Pins every turn to the cheap tier, gives up after one
 * retry, caps tool iterations at 4, and applies STATIC caching
 * aggressively to maximize cache hits.
 *
 * Expect noticeably worse quality on complex tasks — the cheap tier
 * (Haiku 4.5 on Anthropic, GPT-4o-mini on OpenAI) is good for simple
 * Q&A and short tool sequences, not for multi-step agentic plans.
 */
data class FrugalStrategy(
    override val retry: RetryPolicy = RetryPolicy(maxAttempts = 2),
    override val cacheTiers: Map<String, CacheTier> = mapOf(
        "system" to CacheTier.STATIC,
        "history-older" to CacheTier.STATIC, // aggressive
        "history-tail" to CacheTier.VOLATILE,
        "tools-catalog" to CacheTier.STATIC,
    ),
    override val historyVolatileTailTurns: Int = 1, // keep more history cacheable
) : WeftStrategy {

    override fun pickTier(input: WeftUserInput, recent: List<Turn>): ModelTier =
        ModelTier.Cheap

    override fun maxIterations(input: WeftUserInput): Int = 4
}
