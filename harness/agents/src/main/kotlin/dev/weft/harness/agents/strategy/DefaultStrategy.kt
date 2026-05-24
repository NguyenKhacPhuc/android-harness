package dev.weft.harness.agents.strategy

import dev.weft.harness.agents.routing.ModelTier
import dev.weft.harness.behavior.Turn
import dev.weft.harness.prompt.cache.CacheTier
import dev.weft.harness.prompt.multimodal.WeftUserInput
import dev.weft.harness.reliability.RetryPolicy

/**
 * Status-quo [WeftStrategy]. Reproduces the agent loop's hardcoded
 * behavior bit-for-bit so swapping a default-constructed
 * `DefaultStrategy` in for the previously-separate `retryPolicy` +
 * `maxIterations` constructor params is a zero-behavior-change
 * migration.
 *
 * Apps that want a different profile pick a different impl
 * ([FrugalStrategy], [BurstStrategy]) or roll their own. Apps that want
 * to tweak one knob (e.g. raise iter cap to 50) copy this class and
 * override the relevant member — Kotlin data-class `copy()` works for
 * most fields, the `maxIterationsValue` constructor arg keeps the
 * scalar tuning ergonomic.
 */
public data class DefaultStrategy(
    /**
     * Configurable iter cap. Default 10 matches
     * `WeftAgent.MAX_ITERATIONS_DEFAULT` (the per-agent default). When
     * the host runtime overrides to a larger value (Undercurrent uses
     * 25), construct `DefaultStrategy(maxIterationsValue = 25)` to
     * mirror that.
     */
    public val maxIterationsValue: Int = DEFAULT_MAX_ITERATIONS,
    override val retry: RetryPolicy = RetryPolicy(),
    override val cacheTiers: Map<String, CacheTier> = DEFAULT_CACHE_TIERS,
    override val historyVolatileTailTurns: Int = 2,
) : WeftStrategy {

    override fun pickTier(input: WeftUserInput, recent: List<Turn>): ModelTier? = null

    override fun maxIterations(input: WeftUserInput): Int = maxIterationsValue

    public companion object {
        public const val DEFAULT_MAX_ITERATIONS: Int = 10

        public val DEFAULT_CACHE_TIERS: Map<String, CacheTier> = mapOf(
            "system" to CacheTier.STATIC,
            "history-older" to CacheTier.SESSION,
            "history-tail" to CacheTier.VOLATILE,
            "tools-catalog" to CacheTier.STATIC,
        )
    }
}
