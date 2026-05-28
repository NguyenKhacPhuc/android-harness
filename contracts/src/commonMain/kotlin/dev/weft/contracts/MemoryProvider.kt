package dev.weft.contracts

/**
 * App-implementable extension point for **per-turn memory retrieval**.
 *
 * Providers are queried with each user turn's text; their hits are
 * injected into the user message as "Relevant context" before the LLM
 * sees the turn. This is the difference between:
 *
 *   - the LLM calling `memory_recall` (a tool round-trip — costs a turn,
 *     LLM has to remember to ask), and
 *   - this hook (no round-trip — relevant facts are already in the
 *     prompt when the LLM reads the message).
 *
 * Use a [MemoryProvider] for things that:
 *   - You want the LLM to see automatically (user preferences, persona,
 *     active context like "currently editing document X").
 *   - Live in a backend the substrate doesn't know about (a RAG vector
 *     store, a remote knowledge base, your app's user-profile service).
 *
 * Keep a memory provider out of the loop when:
 *   - The content rarely matters per-turn (better expressed as static
 *     system-prompt fragment via `dynamicSystemPromptSection`).
 *   - Retrieval is expensive and the LLM can decide when to ask
 *     (wrap as a tool instead, so the LLM only pays the cost when it
 *     needs the data).
 *
 * Providers run **concurrently per turn** and each gets the same query
 * (the user's current message text). Exceptions are caught and logged;
 * a failing provider doesn't block the turn.
 *
 * Default substrate provider [SubstrateMemoryProvider][dev.weft.harness.memory.SubstrateMemoryProvider]
 * wraps the runtime's `MemoryStore`. Apps add their own via
 * `WeftRuntime(extraMemoryProviders = listOf(...))`.
 */
interface MemoryProvider {
    /**
     * Stable identifier surfaced in [MemoryHit.source] so the LLM (and
     * trace viewers) can see which provider contributed which fact.
     * Convention: snake_case.
     */
    val name: String

    /**
     * Human-readable description. Currently not shown to the LLM — kept
     * for parity with [ContextProvider] in case we surface a
     * `memory_providers_list` tool later.
     */
    val description: String

    /**
     * Retrieve up to [limit] memory hits relevant to [query]. Empty
     * list when nothing relevant. The substrate runs every provider's
     * `retrieve` in parallel per turn; aggregate across providers
     * after they all return.
     *
     * Throwing here is treated as "this provider had nothing this turn"
     * — the substrate logs the failure but doesn't propagate it. Make
     * the implementation robust to whatever backend churn it depends on.
     */
    suspend fun retrieve(query: String, limit: Int = 5): List<MemoryHit>
}

/**
 * One unit of retrieved context — a fact, a passage, a record. Joined
 * with sibling hits and inlined into the user message as bullet points.
 *
 * @property content the text the LLM sees. Should be self-contained;
 *   the LLM has no other handle to follow back to the source.
 * @property source optional provenance string. Surfaced in the injected
 *   text as `(source: name)` so the LLM (and trace viewers) can attribute
 *   what came from where. Defaults to the provider's name when emitted
 *   by the substrate's aggregator.
 * @property score optional relevance score (0.0–1.0). Used by the
 *   aggregator to rank hits across providers when a global limit kicks
 *   in. Providers that don't compute scores can leave it at 0f — the
 *   aggregator falls back to per-provider ordering.
 */
data class MemoryHit(
    val content: String,
    val source: String = "",
    val score: Float = 0f,
)
