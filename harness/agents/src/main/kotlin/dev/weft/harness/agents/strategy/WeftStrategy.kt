package dev.weft.harness.agents.strategy

import dev.weft.harness.agents.routing.ModelTier
import dev.weft.harness.behavior.Turn
import dev.weft.harness.prompt.cache.CacheTier
import dev.weft.harness.prompt.multimodal.WeftUserInput
import dev.weft.harness.reliability.RetryPolicy

/**
 * Pluggable agent-loop policy. One [WeftStrategy] per [WeftAgent][dev.weft.harness.agents.WeftAgent].
 *
 * Apps that want to customize retry, cache, routing, or iteration policy
 * without forking the agent loop plug a strategy here. Three reference
 * impls ship in this package: [DefaultStrategy] (status quo),
 * [FrugalStrategy] (cheap-tier-only, low iter cap), and [BurstStrategy]
 * (high iter cap, fail-fast retry).
 *
 * Per-agent (not per-runtime) by design — multi-agent setups will want
 * each registered agent to pick its own profile. See
 * [docs/architecture/strategy-hook.md][1] for the full rationale.
 *
 * [1]: https://github.com/NguyenKhacPhuc/android-harness/blob/main/docs/architecture/strategy-hook.md
 */
interface WeftStrategy {

    /**
     * Pick a model tier for this turn. Returning `null` defers to the
     * agent's installed [ModelRouter][dev.weft.harness.agents.routing.ModelRouter]
     * heuristics (input-shape based: vision → vision tier, coding hints
     * → heavy, etc.). Returning a concrete tier pins the choice and
     * overrides the router's input-driven logic.
     *
     * Called once per [send][dev.weft.harness.agents.WeftAgent.send] /
     * `sendStreaming` invocation, before agent construction.
     */
    fun pickTier(input: WeftUserInput, recent: List<Turn>): ModelTier?

    /**
     * Retry policy applied to LLM + tool failures via
     * [withRetry][dev.weft.harness.reliability.withRetry]. Read once per
     * `send` invocation; mutating between turns doesn't break anything
     * but a fresh retry policy on every turn is what the contract
     * promises.
     */
    val retry: RetryPolicy

    /**
     * Cache-tier mapping. Special keys:
     *  - `"system"` — the system message
     *  - `"history-older"` — older history before the volatile tail
     *  - `"history-tail"` — the last [historyVolatileTailTurns] turns
     *  - `"tools-catalog"` — the prompt-side tool-definition prefix
     *
     * Unknown keys (or absent keys for the special ones) default to
     * [CacheTier.VOLATILE] — uncached. Tool-name keys are not consulted
     * by the default integration; reserved for future per-tool
     * cache-control granularity.
     */
    val cacheTiers: Map<String, CacheTier>

    /**
     * Per-turn iteration cap on tool-calls. Used to populate
     * `AIAgentConfig.maxAgentIterations`. Vary by input to give simple
     * Q&A turns a lower cap than agentic plans, for example.
     */
    fun maxIterations(input: WeftUserInput): Int

    /**
     * Number of trailing turns kept VOLATILE (uncached). Default 2 —
     * the last user message + its assistant reply almost always change
     * between turns; caching them just churns the cache. Larger values
     * keep more of the recent conversation uncached (good for chat-heavy
     * workloads where the last few turns vary a lot); smaller values
     * cache more aggressively (good for tool-heavy workloads where the
     * same history gets reused across many tool round-trips).
     */
    val historyVolatileTailTurns: Int get() = 2
}
