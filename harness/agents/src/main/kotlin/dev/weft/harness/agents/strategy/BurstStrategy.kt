package dev.weft.harness.agents.strategy

import dev.weft.harness.agents.routing.ModelTier
import dev.weft.harness.behavior.Turn
import dev.weft.harness.prompt.cache.CacheTier
import dev.weft.harness.prompt.multimodal.WeftUserInput
import dev.weft.harness.reliability.RetryPolicy

/**
 * High-throughput [WeftStrategy] for agentic workloads — research
 * agents, long-running plans, multi-step tool sequences. Raises the
 * iteration cap to 20, fails fast (single attempt, no retry), defers
 * tier selection to the router's heuristics, and relaxes caching since
 * burst use breaks cache hit rates anyway.
 *
 * Pair with the heavy tier (Opus / O3) when you specifically want both
 * deep reasoning AND many tool calls. The default `pickTier = null`
 * lets the router pick based on input shape.
 */
public data class BurstStrategy(
    override val retry: RetryPolicy = RetryPolicy(maxAttempts = 1),
    override val cacheTiers: Map<String, CacheTier> = mapOf(
        "system" to CacheTier.SESSION, // relaxed from STATIC
        "history-older" to CacheTier.VOLATILE, // bursts churn history
        "history-tail" to CacheTier.VOLATILE,
        "tools-catalog" to CacheTier.STATIC, // tools stable, still worth caching
    ),
    override val historyVolatileTailTurns: Int = 4, // keep a longer tail volatile
) : WeftStrategy {

    override fun pickTier(input: WeftUserInput, recent: List<Turn>): ModelTier? = null

    override fun maxIterations(input: WeftUserInput): Int = 20
}
