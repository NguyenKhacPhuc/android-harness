package dev.weft.harness.agents.subagents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable

/**
 * `delegate` — spawn one focused sub-agent and wait for its reply.
 *
 * The orchestrator LLM calls this when a step is best handled in
 * isolation: long research dives that shouldn't pollute the main
 * conversation's context, focused single-task work like "summarise
 * this document," or anything where a constrained tool set + tighter
 * system prompt would yield a cleaner result.
 *
 * **Isolation invariants** (enforced by [SubAgentRunner]):
 *   - Sub-agent has its own fresh conversation history.
 *   - Tool registry is a subset of the parent's tools.
 *   - Delegation tools are stripped from that subset — no nested
 *     delegation. Depth is capped at 1.
 *   - Sub-agent's internal LLM calls / tool calls are *not* written to
 *     the shared trace store. Only this tool call (with the sub-agent's
 *     final reply as the result) appears in the parent's trace.
 *
 * Cost accounting: the sub-agent's token usage *does* aggregate into
 * the shared [dev.weft.harness.cost.UsageStore] — the orchestrator's
 * daily quota covers everything it delegates.
 */
public class DelegateTool(
    ctx: WeftContext,
    private val runner: SubAgentRunner,
) : WeftTool<DelegateTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = TOOL_NAME,
        description = (
            "Spawn an isolated sub-agent to handle a focused sub-task and wait for its reply. " +
                "Use when a step benefits from a constrained tool set or a clean context — e.g. " +
                "researching one specific question, summarising a long document, drafting one part " +
                "of a larger response. The sub-agent has empty conversation history and no UI " +
                "rendering; it returns a single text reply.\n\n" +
                "Args:\n" +
                "- role: short descriptive label, e.g. 'researcher', 'summariser', 'planner'.\n" +
                "- task: self-contained instruction. The sub-agent has no shared context, so " +
                "include everything it needs.\n" +
                "- tools: comma-separated tool names the sub-agent may use. Subset of the tools " +
                "you have available. Empty string = no tools (pure reasoning).\n" +
                "- modelTier: CHEAP / STANDARD / HEAVY / VISION — pick HEAVY for hard reasoning, " +
                "CHEAP for simple lookups, default STANDARD."
        ),
        requiredParameters = listOf(
            ToolParameterDescriptor("role", "Descriptive sub-agent role.", ToolParameterType.String),
            ToolParameterDescriptor("task", "Self-contained instruction.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "tools",
                "Comma-separated tool names. Empty for no tools.",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "modelTier",
                "CHEAP / STANDARD / HEAVY / VISION. Default STANDARD.",
                ToolParameterType.String,
            ),
        ),
    ),
) {

    @Serializable
    public data class Args(
        val role: String,
        val task: String,
        val tools: String = "",
        val modelTier: String = "STANDARD",
    )

    override suspend fun executeWeft(args: Args): String {
        val tier = parseTier(args.modelTier)
        val toolSet = args.tools.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val spec = SubAgentSpec(
            role = args.role,
            task = args.task,
            tools = toolSet,
            modelTier = tier,
        )
        return runner.run(spec)
    }

    public companion object {
        public const val TOOL_NAME: String = "delegate"

        internal fun parseTier(raw: String): ModelTier = runCatching {
            ModelTier.valueOf(raw.trim().uppercase())
        }.getOrDefault(ModelTier.STANDARD)
    }
}
