package dev.weft.harness.agents.subagents

import ai.koog.prompt.llm.LLModel
import dev.weft.harness.agents.routing.ModelPool

/**
 * Recipe for spinning up one isolated sub-agent.
 *
 * Constructed by [DelegateTool] / [DelegateParallelTool] from the
 * orchestrator LLM's tool-call arguments, then handed to
 * [SubAgentRunner.run] which builds a fresh
 * [dev.weft.harness.agents.WeftAgent] around it and runs the task to
 * completion.
 *
 * @property role short descriptive label ("researcher", "writer",
 *   "code-reader") — appears in the focused system prompt and in
 *   trace summaries. Helps the sub-agent stay on-task and helps a
 *   human reader skim the parent's tool calls.
 * @property task the actual instruction sent to the sub-agent as its
 *   first user message. Should be self-contained — the sub-agent has
 *   no shared history with the orchestrator.
 * @property tools tool names from the parent's catalog that the
 *   sub-agent is allowed to use. The runner filters this list and
 *   ALWAYS strips delegation tools (no nested sub-agents).
 * @property modelTier which pool slot to pin the sub-agent to. The
 *   orchestrator picks tier based on the sub-task's complexity.
 * @property maxIterations hard cap on the sub-agent's tool-call
 *   loop. Lower than the parent's default to fail-fast on runaway
 *   sub-agents.
 */
public data class SubAgentSpec(
    public val role: String,
    public val task: String,
    public val tools: Set<String> = emptySet(),
    public val modelTier: ModelTier = ModelTier.STANDARD,
    public val maxIterations: Int = 8,
)

/**
 * Coarse model selector for sub-agents — names a slot in the parent's
 * [ModelPool] rather than picking a specific [LLModel]. Lets the
 * orchestrator say "use the cheap one for this" without knowing the
 * actual model IDs of whatever provider happens to be wired today.
 */
public enum class ModelTier {
    CHEAP, STANDARD, HEAVY, VISION;

    /** Resolve this tier against the active model pool. */
    public fun resolve(pool: ModelPool): LLModel = when (this) {
        CHEAP -> pool.cheap
        STANDARD -> pool.standard
        HEAVY -> pool.heavy
        VISION -> pool.vision
    }
}
