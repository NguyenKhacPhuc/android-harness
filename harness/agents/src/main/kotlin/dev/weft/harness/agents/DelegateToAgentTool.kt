package dev.weft.harness.agents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.KotlinTypeToken
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.reflect.typeOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * `delegate_to_agent` — hand a focused sub-task to another registered
 * agent and wait for its reply.
 *
 * The target agent is selected by name from
 * [WeftRuntime.agentDeclarations]. Its declared role fragment, tool
 * allowlist, and strategy apply — the orchestrator can't reach past
 * those boundaries, by design.
 *
 * **Depth cap.** Delegations are bounded to
 * [MAX_DELEGATION_DEPTH] levels via [DelegationContext] in the
 * coroutine context. Exceeding it returns an error string to the
 * orchestrator instead of throwing — the LLM gets a chance to recover.
 *
 * **Conversation persistence.** The delegated agent runs against its
 * own fresh conversation id (the orchestrator's conversation isn't
 * polluted by the sub-agent's internal back-and-forth). The
 * delegated agent's trace store rows ARE persisted, linked to the
 * orchestrator's trace via `parentTraceId` propagated through
 * [dev.weft.harness.observability.TraceContext].
 *
 * Replaces the pre-#4 `delegate` and `delegate_parallel` tools.
 * Parallel fan-out can be reintroduced as a separate tool once the
 * single-target path stabilizes.
 */
public class DelegateToAgentTool(
    ctx: WeftContext,
    /**
     * Build the delegated [WeftAgent] for [agentName]. Closes over the
     * active provider / pool / strategy at orchestrator-build time, so
     * the delegate inherits the same credentials.
     */
    private val resolveAgent: suspend (agentName: String) -> WeftAgent,
    /**
     * Snapshot of registered agents the orchestrator may target.
     * Injected into the tool description so the LLM picks valid names.
     * Filtered to user-addressable + non-self (callers can pre-filter
     * if they want to restrict further).
     */
    private val knownAgents: List<AgentDeclaration>,
) : WeftTool<DelegateToAgentTool.Args, String>(
    ctx = ctx,
    argsType = KotlinTypeToken(typeOf<Args>()),
    resultType = KotlinTypeToken(typeOf<String>()),
    descriptor = ToolDescriptor(
        name = TOOL_NAME,
        description = buildDescription(knownAgents),
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "agent",
                "Name of the agent to delegate to. Must match one of the listed agents.",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "prompt",
                "Self-contained task description. The delegated agent has no shared context.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "expects",
                "Return-shape hint, e.g. 'summary', 'citations', 'json'. Optional.",
                ToolParameterType.String,
            ),
        ),
    ),
) {

    @Serializable
    public data class Args(
        val agent: String,
        val prompt: String,
        val expects: String? = null,
    )

    override suspend fun executeWeft(args: Args): String {
        val depth = coroutineContext[DelegationContext]?.depth ?: 0
        if (depth >= MAX_DELEGATION_DEPTH) {
            return "delegate_to_agent: max delegation depth ($MAX_DELEGATION_DEPTH) reached. " +
                "Handle this directly instead of delegating further."
        }
        if (knownAgents.none { it.name == args.agent }) {
            return "delegate_to_agent: unknown agent '${args.agent}'. " +
                "Registered: ${knownAgents.joinToString { it.name }}"
        }
        val delegate = resolveAgent(args.agent)
        val taskWithExpectsHint = if (args.expects.isNullOrBlank()) {
            args.prompt
        } else {
            "${args.prompt}\n\nReturn shape: ${args.expects}."
        }
        return withContext(DelegationContext(depth + 1)) {
            delegate.send(taskWithExpectsHint)
        }
    }

    public companion object {
        public const val TOOL_NAME: String = "delegate_to_agent"

        /**
         * Maximum nesting depth for delegations: A → B → C → D would
         * hit the cap before D actually delegates. Three is generous
         * for legitimate sub-task decomposition while bounding the
         * cost-of-recursion blast radius.
         */
        public const val MAX_DELEGATION_DEPTH: Int = 3

        private fun buildDescription(knownAgents: List<AgentDeclaration>): String {
            val list = knownAgents
                .filter { it.name != AgentDeclaration.DEFAULT_AGENT_NAME }
                .joinToString("\n") { "- ${it.name}: ${it.description}" }
            val listOrNone = if (list.isBlank()) "(no specialized agents registered)" else list
            return (
                "Delegate a focused sub-task to another registered agent and wait for its reply. " +
                    "Use when the sub-task needs a specialized persona or tool subset. " +
                    "NOT for navigation between user-visible screens. " +
                    "NOT for trivial one-step lookups handled inline.\n\n" +
                    "Available agents:\n$listOrNone\n\n" +
                    "Each agent has its own role, tools, and strategy. The delegated agent " +
                    "has empty conversation history and returns a single text reply."
                )
        }
    }
}

/**
 * Coroutine-context element tracking delegation depth. Read by
 * [DelegateToAgentTool] to enforce
 * [DelegateToAgentTool.MAX_DELEGATION_DEPTH] across nested calls.
 *
 * Internal — apps don't construct this; the tool installs it via
 * [withContext] when invoking the delegated agent.
 */
internal data class DelegationContext(val depth: Int) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key

    companion object Key : CoroutineContext.Key<DelegationContext>
}
