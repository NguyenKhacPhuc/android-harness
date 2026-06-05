package dev.weft.android

import ai.koog.agents.core.tools.ToolRegistry
import dev.weft.contracts.ToolProvider
import dev.weft.contracts.WeftCredentialProvider
import dev.weft.harness.agents.AgentDeclaration
import dev.weft.harness.agents.DelegateToAgentTool
import dev.weft.harness.agents.WeftAgent
import dev.weft.harness.agents.routing.ModelPool
import dev.weft.harness.agents.strategy.WeftStrategy
import dev.weft.harness.conversation.ConversationStore
import dev.weft.harness.cost.QuotaPolicy
import dev.weft.harness.cost.UsageStore
import dev.weft.harness.memory.MemoryRegistry
import dev.weft.harness.observability.Redactor
import dev.weft.harness.observability.TraceStore
import dev.weft.harness.prompt.cache.CacheTier
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool

/**
 * The agent-loop collaborators a [WeftAgent] needs that are stable across
 * every agent a runtime builds (i.e. everything except the provider-derived
 * executor/pool/cacheBinder and the per-agent tools/prompt). Bundled so
 * [AgentBuilder]'s constructor stays readable instead of taking a dozen
 * loose stores.
 */
internal class AgentLoopDeps(
    val traceStore: TraceStore,
    val usageStore: UsageStore,
    val quotaPolicy: QuotaPolicy,
    val redactor: Redactor,
    val conversationStore: ConversationStore,
    val memoryRegistry: MemoryRegistry,
    val maxIterations: Int,
    val maxOutputTokens: Int,
    val deviceSnapshotProvider: () -> String,
    val extraVolatilePrefix: () -> String,
    val toolProvider: ToolProvider,
    val hasOnDemandTools: Boolean,
)

/**
 * Assembles a [WeftAgent] for one [AgentDeclaration]: picks the provider
 * executor + pool + cache binder, scopes the tool catalog to the
 * declaration's allowlist, wires `delegate_to_agent` for multi-agent
 * setups, composes the agent's system prompt, and constructs the agent.
 *
 * Extracted from [WeftRuntime] so the composition root doesn't carry the
 * ~140-line assembly. Dependencies are inverted as lambdas
 * ([resolveTools], [resolvedSystemPrompt], [resolveAgent]) rather than a
 * back-reference to the runtime, so the builder depends on capabilities,
 * not on its owner — and the runtime keeps ownership of MCP-resolution
 * caching behind those lambdas.
 */
internal class AgentBuilder(
    private val toolContext: WeftContext,
    private val agentDeclarations: Map<String, AgentDeclaration>,
    private val promptComposer: SystemPromptComposer,
    private val deps: AgentLoopDeps,
    /** Substrate + extra + MCP tools (awaits MCP discovery on first call). */
    private val resolveTools: suspend () -> List<WeftTool<*, *>>,
    /** The runtime's cached full-catalog system prompt. */
    private val resolvedSystemPrompt: suspend () -> String,
    /** Recurses into the runtime to build a delegate target by name. */
    private val resolveAgent: suspend (String, WeftCredentialProvider, ModelPool?) -> WeftAgent,
) {

    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun build(
        declaration: AgentDeclaration,
        provider: WeftCredentialProvider,
        modelPoolOverride: ModelPool?,
        strategyOverride: WeftStrategy?,
    ): WeftAgent {
        // Provider-derived: Koog executor, default pool, cache binder.
        // Per-turn model routing happens inside WeftAgent via DefaultModelRouter.
        val (executor, defaultPool, cacheBinder) = buildProviderExecutor(provider)

        // Block the first build until MCP discovery resolves; later calls
        // see the completed deferred at zero cost.
        val agentTools = scopedTools(declaration, resolveTools())
        val delegate = delegateTool(declaration, provider, modelPoolOverride)
        val allTools = if (delegate != null) agentTools + delegate else agentTools

        // Bind cache markers: the LAST tool's descriptor gets cache_control,
        // which Anthropic uses as the breakpoint for the whole tool prefix.
        // NoOpCacheBinder returns the list unchanged.
        val cachedTools = cacheBinder.markedTools(allTools, CacheTier.STATIC)
        val toolRegistry = ToolRegistry { cachedTools.forEach { tool(it) } }

        val effectiveSystemPrompt = composePrompt(declaration, allTools)

        return WeftAgent(
            executor = executor,
            modelPool = modelPoolOverride ?: defaultPool,
            toolRegistry = toolRegistry,
            traceStore = deps.traceStore,
            baseSystemPromptSupplier = { effectiveSystemPrompt },
            // Device snapshot (substrate default) then the app's per-turn
            // extension, blank-line separated; empty sections dropped.
            volatilePrefixSupplier = {
                listOf(deps.deviceSnapshotProvider(), deps.extraVolatilePrefix())
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            },
            maxIterations = deps.maxIterations,
            usageStore = deps.usageStore,
            quotaPolicy = deps.quotaPolicy,
            redactor = deps.redactor,
            maxOutputTokens = deps.maxOutputTokens,
            conversationStore = deps.conversationStore,
            cacheBinder = cacheBinder,
            memoryRegistry = deps.memoryRegistry,
            // Explicit override beats the declaration's own strategy.
            strategy = strategyOverride ?: declaration.strategy,
            agentName = declaration.name,
            // Lazy ToolProvider lets the strategy resolve find_tool searches
            // mid-turn; null in pure-eager mode saves the activation node work.
            toolProvider = if (deps.hasOnDemandTools) deps.toolProvider else null,
        )
    }

    /**
     * Apply the declaration's tool allowlist. Empty allowlist = every tool
     * (the default-agent behavior). Non-empty = whitelisted names only; MCP
     * tools match in their qualified `${server}:${name}` form.
     */
    private fun scopedTools(
        declaration: AgentDeclaration,
        all: List<WeftTool<*, *>>,
    ): List<WeftTool<*, *>> =
        if (declaration.allowedTools.isEmpty()) all
        else all.filter { it.descriptor.name in declaration.allowedTools }

    /**
     * `delegate_to_agent`, present whenever more than this agent is
     * registered. The factory captures the active provider so delegates
     * inherit credentials; depth is bounded by DelegateToAgentTool via the
     * coroutine context. Null when there's no one to delegate to.
     */
    private fun delegateTool(
        declaration: AgentDeclaration,
        provider: WeftCredentialProvider,
        modelPoolOverride: ModelPool?,
    ): WeftTool<*, *>? {
        val otherAgents = agentDeclarations.values.filter { it.name != declaration.name }
        if (otherAgents.isEmpty()) return null
        return DelegateToAgentTool(
            ctx = toolContext,
            resolveAgent = { targetName -> resolveAgent(targetName, provider, modelPoolOverride) },
            knownAgents = otherAgents,
        )
    }

    /**
     * The agent's effective system prompt: substrate catalog + role fragment.
     *
     * Catalog scoping (Stage 1 of docs/architecture/tool-provider.md):
     *   - Empty allowlist (default agent) → reuse the cached full-catalog
     *     prompt. Identical to pre-Stage-1 behavior, keeps Anthropic caching.
     *   - Non-empty allowlist → rebuild against [allTools] so the prompt
     *     describes only the tools this agent can call (token savings;
     *     per-agent prompts don't share the cache prefix — for multi-agent
     *     hosts the catalog savings dominate).
     */
    private suspend fun composePrompt(
        declaration: AgentDeclaration,
        allTools: List<WeftTool<*, *>>,
    ): String {
        val base = if (declaration.allowedTools.isEmpty()) {
            resolvedSystemPrompt()
        } else {
            promptComposer.forTools(allTools)
        }
        return if (declaration.systemFragment.isBlank()) {
            base
        } else {
            "$base\n\n## Role\n${declaration.systemFragment}"
        }
    }
}
