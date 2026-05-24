package dev.weft.harness.agents.subagents

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import dev.weft.harness.agents.WeftAgent
import dev.weft.harness.prompt.assembleSystemPrompt
import dev.weft.harness.prompt.cache.CacheBinder
import dev.weft.harness.agents.routing.ModelPool
import dev.weft.harness.agents.routing.StaticModelRouter
import dev.weft.harness.behavior.BehaviorConfig
import dev.weft.harness.cost.QuotaPolicy
import dev.weft.harness.cost.UsageStore
import dev.weft.harness.observability.Redactor
import dev.weft.harness.observability.TraceStore
import dev.weft.tools.WeftTool

/**
 * Runs a [SubAgentSpec] to completion and returns the sub-agent's
 * final reply. Each call is **isolated** in the things that matter
 * for correctness — fresh `WeftAgent`, constrained tool registry,
 * empty conversation history, no persistence — but **shares the
 * parent's [TraceStore]** so the sub-agent's traces are persisted and
 * linked to the parent's delegation tool call via `parentTraceId`.
 *
 * The link is propagated automatically: the orchestrator's
 * `WeftAgent.send` wraps its body in
 * `withContext(TraceContext(parentTraceId))`, so when the sub-agent
 * runs inside [run] and itself calls `traceStore.startTrace`, the id
 * flows through the coroutine context as `parentTraceId`. DevTools
 * reads this column to render nested traces.
 *
 * Shared with the parent across sub-agent calls:
 *   - [executor] — same Koog LLM client, no extra connection overhead.
 *   - [modelPool] — same provider's model menu (tier picks the slot).
 *   - [cacheBinder] — same provider's cache strategy.
 *   - [usageStore] — costs aggregate across orchestrator + sub-agents.
 *   - [quotaPolicy] — sub-agent calls count against the daily cap.
 *   - [redactor] — same privacy rules.
 *   - [traceStore] — sub-agent traces persist alongside the parent's,
 *     distinguished by `parentTraceId`.
 *
 * Isolated from the parent:
 *   - tools (subset of [parentTools], with delegation tools filtered)
 *   - conversation history (empty — sub-agent starts cold)
 *   - conversation store binding (sub-agents don't append to chat history)
 *   - system prompt (focused on the sub-agent's role + its subset of tools)
 *   - model routing (pinned by [SubAgentSpec.modelTier] — no per-turn re-routing
 *     since sub-agents are single-purpose)
 */
public class SubAgentRunner(
    private val executor: PromptExecutor,
    private val modelPool: ModelPool,
    private val cacheBinder: CacheBinder,
    private val parentTools: List<WeftTool<*, *>>,
    private val traceStore: TraceStore,
    private val usageStore: UsageStore,
    private val quotaPolicy: QuotaPolicy,
    private val redactor: Redactor,
    private val behaviorConfig: BehaviorConfig = BehaviorConfig(),
    private val maxOutputTokens: Int = WeftAgent.DEFAULT_MAX_OUTPUT_TOKENS,
) {

    /**
     * Run [spec] and return the sub-agent's final text reply. Suspends
     * until the sub-agent finishes its tool-use loop (or hits the spec's
     * `maxIterations` cap, whichever comes first).
     *
     * Exceptions thrown by the sub-agent propagate to the caller. The
     * orchestrator's [DelegateTool] catches them and surfaces a tool
     * failure to the LLM rather than crashing the parent turn.
     */
    public suspend fun run(spec: SubAgentSpec): String {
        // 0. Read the orchestrator's TraceContext. Sub-agent inherits
        //    its conversationId so all traces from this user turn share
        //    one conversation id and conversation-scoped queries return
        //    the whole tree. The trace-id linkage (parentTraceId) is
        //    handled automatically when the sub-agent's WeftAgent.send
        //    reads the same TraceContext to populate startTrace's
        //    parentTraceId param — no extra threading needed here.
        //
        //    Falls back to a fresh UUID when there's no enclosing
        //    TraceContext, which means the runner was invoked outside
        //    a parent WeftAgent.send. Shouldn't happen via the normal
        //    DelegateTool path, but the fallback keeps the API robust
        //    for tests / direct invocation.
        val parentConversationId = kotlin.coroutines.coroutineContext[
            dev.weft.harness.observability.TraceContext,
        ]?.conversationId ?: java.util.UUID.randomUUID().toString()

        // 1. Resolve the requested tool names. Unknown names are silently
        //    dropped — the orchestrator might hallucinate a tool name; we
        //    prefer "missing tool" over "sub-agent fails to construct."
        //    Delegation tools are ALWAYS filtered (no nested sub-agents).
        val subAgentTools = parentTools.filter { tool ->
            val name = tool.descriptor.name
            name in spec.tools && name !in BLOCKED_TOOL_NAMES
        }
        val toolRegistry = ToolRegistry { subAgentTools.forEach { tool(it) } }

        // 2. Build a focused system prompt — role preamble + only the
        //    sub-agent's allowed tools in the catalog. No UI components,
        //    no app preamble (sub-agents aren't part of the user-facing
        //    conversation, so their prompt doesn't need that context).
        val systemPrompt = assembleSystemPrompt(
            appPreamble = rolePreamble(spec),
            tools = subAgentTools,
            components = emptyList(),
            extraNotes = SUB_AGENT_NOTES,
        )

        // 3. Pin the model — sub-agents are single-purpose so per-turn
        //    routing doesn't add value. Use the tier the orchestrator
        //    requested.
        val pinnedModel = spec.modelTier.resolve(modelPool)
        val router = StaticModelRouter(pinnedModel)

        // 4. Build the sub-agent. Shares the parent's trace store so
        //    sub-agent traces persist with `parentTraceId` linking back
        //    to the orchestrator's delegation tool call (propagated via
        //    TraceContext through the coroutine context, no explicit
        //    threading needed). No ConversationStore wired — sub-agents
        //    don't append to chat history. Lower default maxIterations
        //    (8) than orchestrators (10+) to fail-fast.
        val subAgent = WeftAgent(
            executor = executor,
            modelPool = modelPool,
            modelRouter = router,
            toolRegistry = toolRegistry,
            traceStore = traceStore,
            baseSystemPromptSupplier = { systemPrompt },
            volatilePrefixSupplier = { "" },  // no device snapshot — sub-agents shouldn't need it
            conversationId = parentConversationId,
            maxIterations = spec.maxIterations,
            usageStore = usageStore,
            quotaPolicy = quotaPolicy,
            redactor = redactor,
            behaviorConfig = behaviorConfig,
            maxOutputTokens = maxOutputTokens,
            conversationStore = null,
            cacheBinder = cacheBinder,
        )

        // 5. Run the task and return the reply. send() is suspending and
        //    completes when the sub-agent emits its final assistant message.
        return subAgent.send(spec.task)
    }

    private fun rolePreamble(spec: SubAgentSpec): String = """
        You are a focused sub-agent invoked by an orchestrating agent.
        Role: ${spec.role}.
        Task: ${spec.task}.

        You have a constrained tool set listed below. You CANNOT delegate
        further — depth is capped at 1. Complete the task as concisely as
        possible and return a single text reply that the orchestrator can
        consume directly. Do not ask clarifying questions; if information
        is missing, do your best with the task as stated and note any
        assumptions in the reply.
    """.trimIndent()

    private companion object {
        /**
         * Tool names a sub-agent is never allowed to call, regardless of
         * what the orchestrator requested. Currently just the delegation
         * tools themselves — enforces the depth=1 invariant structurally
         * (the sub-agent's tool registry literally doesn't contain them).
         */
        val BLOCKED_TOOL_NAMES: Set<String> = setOf(
            DelegateTool.TOOL_NAME,
            DelegateParallelTool.TOOL_NAME,
        )

        /**
         * Extra trailing notes for the sub-agent's system prompt. Short
         * because the sub-agent doesn't need the UI / memory / scheduling
         * protocols the orchestrator has — it's a focused worker.
         */
        val SUB_AGENT_NOTES = """
            Respond directly with the result of the task. No preamble,
            no "I'll help you with that" — go straight to the answer.
        """.trimIndent()
    }
}
