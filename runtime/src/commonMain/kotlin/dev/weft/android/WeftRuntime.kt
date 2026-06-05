package dev.weft.android

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.weft.contracts.ContextRegistry
import dev.weft.contracts.DataSource
import dev.weft.contracts.DataSourceRegistry
import dev.weft.contracts.ToolProvider
import dev.weft.contracts.KeyVault
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.UiBridge
import dev.weft.harness.agents.WeftAgent
import dev.weft.harness.cost.QuotaPolicy
import dev.weft.harness.cost.UsageStore
import dev.weft.harness.memory.MemoryStore
import dev.weft.harness.observability.Redactor
import dev.weft.harness.observability.TraceStore
import dev.weft.mcp.HttpMcpClient
import dev.weft.mcp.McpRemoteTool
import dev.weft.mcp.McpServerConfig
import dev.weft.mcp.McpToolDescriptor
import dev.weft.mcp.translateToKoogDescriptor
import dev.weft.security.NetworkPolicy
import dev.weft.security.whitelistingHttpClient
import dev.weft.harness.conversation.ConversationStore
import dev.weft.android.persistence.SqlDelightConversationStore
import dev.weft.android.persistence.SqlDelightMemoryStore
import dev.weft.android.persistence.SqlDelightScriptStorage
import dev.weft.android.persistence.SqlDelightTraceStore
import dev.weft.android.persistence.SqlDelightUsageStore
import dev.weft.android.persistence.WeftDatabaseFactory
import dev.weft.android.persistence.pruneExpiredKeyValues
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import dev.weft.tools.FindToolTool
import dev.weft.tools.EagerToolProvider
import dev.weft.tools.context.DeviceContextProvider
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The substrate SDK composition root. Apps construct one of these at startup
 * and feed it their app-specific data sources, context providers, and tools.
 *
 * **What this module owns:** the agent + persistence + streaming + slash-
 * command primitives. It is Compose-free. The OS-capability tool catalog
 * (notify, calendar, contacts, files, …) is auto-injected; UI tools
 * (ui_render, ui_notify) live in `:substrate:android-ui` and apps wire
 * them in via [extraToolsFactory].
 *
 * The app owns:
 *   - its data sources (passed in via [dataSources]),
 *   - its network allowlist (via [networkPolicy]),
 *   - its app-specific tools and context providers ([extraToolsFactory],
 *     [extraContextProviders]),
 *   - its role preamble in the system prompt ([appPromptPreamble]),
 *   - the UI surface (compose with `WeftUi` from `:substrate:android-ui`).
 */
public class WeftRuntime(
    public val os: OsCapabilities,
    public val uiBridge: UiBridge,
    /**
     * SQLDelight database backing every persistent store the substrate
     * ships. Built by `WeftRuntime.create` from the app's platform
     * handle; tests can pass a JDBC in-memory variant; iOS hosts pass
     * a [NativeSqliteDriver]-backed variant.
     */
    private val database: dev.weft.android.db.WeftDatabase,
    /**
     * HTTP client used by `network_fetch` (and the MCP transport when
     * one is configured). [Companion.create] wires a host-allowlist
     * Ktor client (OkHttp on Android, Darwin on iOS). Apps can supply
     * their own — useful for tracing, custom retry, or a corporate proxy.
     */
    private val networkClient: io.ktor.client.HttpClient,
    /**
     * Per-turn device snapshot prepended to the user message. The `create`
     * factories wire a platform-specific provider; the default returns
     * empty.
     */
    private val deviceSnapshotProvider: () -> String = { "" },
    /** App-facing configuration — see [WeftRuntimeConfig]. */
    private val config: WeftRuntimeConfig,
) {
    // Unpack the config into the names the class body uses. Keeps the
    // constructor + the `assembleWeftRuntime` plumbing free of a 20-arg
    // bag; the public `create` factories build a [WeftRuntimeConfig].
    private val appPromptPreamble: String get() = config.appPromptPreamble
    public val networkPolicy: NetworkPolicy get() = config.networkPolicy
    private val extraToolsFactory get() = config.extraToolsFactory
    private val toolProviderOverride get() = config.toolProviderOverride
    private val componentMetadata get() = config.componentMetadata
    private val extraSystemNotes get() = config.extraSystemNotes
    private val dynamicSystemPromptSection get() = config.dynamicSystemPromptSection
    private val extraVolatilePrefix get() = config.extraVolatilePrefix
    private val extraMemoryProviders get() = config.extraMemoryProviders
    private val memoryStoreOverride get() = config.memoryStoreOverride
    private val conversationStoreOverride get() = config.conversationStoreOverride
    public val quotaPolicy: QuotaPolicy get() = config.quotaPolicy
    public val redactor: Redactor get() = config.redactor
    private val ttlSweepInterval get() = config.ttlSweepInterval
    private val maxIterations get() = config.maxIterations
    private val maxOutputTokens get() = config.maxOutputTokens
    private val mcpServers get() = config.mcpServers
    private val onMcpError get() = config.onMcpError
    private val mcpDiscoveryTimeout get() = config.mcpDiscoveryTimeout

    public val keyVault: KeyVault get() = os.keyVault

    /** Raw list snapshot — kept for prompt re-assembly when MCP tools resolve. */
    private val rawDataSources: List<DataSource> = config.dataSources

    public val dataSources: DataSourceRegistry = DataSourceRegistry(config.dataSources)

    /**
     * Map of [dev.weft.harness.agents.AgentDeclaration]s keyed by
     * [dev.weft.harness.agents.AgentDeclaration.name]. Auto-includes a
     * default declaration named
     * [dev.weft.harness.agents.AgentDeclaration.DEFAULT_AGENT_NAME]
     * when the host passed no `agents` list, so existing single-agent
     * apps see no behavior change.
     *
     * Phase 4.1 surfaces declarations only; built [WeftAgent]s come
     * from [buildAgent] (lifetime: caller-owned, fresh per call). A
     * future phase may add a cached `agents: Map<String, WeftAgent>`
     * keyed by provider identity.
     */
    public val agentDeclarations: Map<String, dev.weft.harness.agents.AgentDeclaration> = run {
        val agents = config.agents
        val effective = if (agents.isEmpty()) {
            listOf(dev.weft.harness.agents.AgentDeclaration.default())
        } else {
            // Apps that supplied agents but forgot a default get one
            // synthesized so `buildAgent(provider)` still works.
            val hasDefault = agents.any {
                it.name == dev.weft.harness.agents.AgentDeclaration.DEFAULT_AGENT_NAME
            }
            if (hasDefault) agents else agents + dev.weft.harness.agents.AgentDeclaration.default()
        }
        val byName = effective.associateBy { it.name }
        require(byName.size == effective.size) {
            "AgentDeclaration names must be unique. Got: ${effective.map { it.name }}"
        }
        byName
    }

    public val contextRegistry: ContextRegistry = ContextRegistry(
        listOf(DeviceContextProvider(os)) + config.extraContextProviders,
    )

    // `networkClient` is now a constructor arg — `Companion.create()`
    // wires the OkHttp-backed whitelisting variant on Android, and the
    // policy stays accessible separately via the `networkPolicy` field.

    /**
     * Coroutine scope tied to the runtime's lifetime. Used by Flow-backed
     * stores ([SqlDelightMemoryStore], [SqlDelightTraceStore],
     * [SqlDelightUsageStore]) to keep their StateFlow hot.
     */
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Records every agent turn — user prompt, LLM calls, tool calls,
     * tokens, durations. SQLDelight-backed; survives app restart, capped
     * at the most recent [SqlDelightTraceStore.DEFAULT_MAX_TRACES] turns.
     */
    public val traceStore: TraceStore = SqlDelightTraceStore(database, runtimeScope)

    /**
     * Token + dollar tracking. The UI reads [UsageStore.totals] for the
     * cost badge. Daily aggregates persist; lifetime totals are summed
     * from the daily rows. Last-call ephemeral state is in-memory only.
     */
    public val usageStore: UsageStore = SqlDelightUsageStore(database, runtimeScope)

    /**
     * Agent-curated memories. Defaults to a SQLDelight-backed store on
     * the substrate database (survives app restart, explicit-only per
     * ADR-002 — the LLM stores facts via `memory_store`, the user sees
     * every entry in a viewer and can delete any). Apps that want a
     * different backend supply [memoryStoreOverride].
     */
    public val memoryStore: MemoryStore = memoryStoreOverride
        ?: SqlDelightMemoryStore(database, runtimeScope)

    /**
     * Per-turn memory retrieval extension point. Aggregates the
     * substrate's own memory provider (wrapping [memoryStore]) plus
     * any [extraMemoryProviders] the app registered. Queried by
     * [WeftAgent] per `send` so relevant memories are inlined into the
     * user message — no `memory_recall` tool round-trip required.
     */
    public val memoryRegistry: dev.weft.harness.memory.MemoryRegistry =
        dev.weft.harness.memory.MemoryRegistry(
            providers = listOf(dev.weft.harness.memory.SubstrateMemoryProvider(memoryStore)) +
                extraMemoryProviders,
        )

    /**
     * Persistent USER / ASSISTANT chat history per conversation. Defaults
     * to a SQLDelight-backed store on the substrate database. Apps with
     * their own backend (multi-device sync, server-side storage) supply
     * [conversationStoreOverride].
     */
    public val conversationStore: ConversationStore = conversationStoreOverride
        ?: SqlDelightConversationStore(database, runtimeScope)

    init {
        // Background sweep of TTL-expired key_value rows. Lazy eviction in
        // SqlDelightScriptStorage.get() keeps reads honest; the sweep catches
        // accumulated cruft from values that were written with TTL and then
        // never read again (one-shot idempotency keys, abandoned polling
        // cursors). One DELETE per pass, off the main thread.
        runtimeScope.launch {
            runCatching { pruneExpiredKeyValues(database) }
            if (ttlSweepInterval != Duration.INFINITE) {
                while (true) {
                    delay(ttlSweepInterval)
                    runCatching { pruneExpiredKeyValues(database) }
                }
            }
        }
    }

    /**
     * Tool context exposed to every tool — gives them OS, UI bridge, and
     * per-tool persistent storage. Public so apps' `extraToolsFactory`
     * can construct tools that need it (and so `WeftUi.toolsFactory`
     * can build the UI tools).
     */
    public val toolContext: WeftContext = WeftContext(
        os = os,
        ui = uiBridge,
        storageFactory = { name -> SqlDelightScriptStorage(database, namespace = name) },
    )

    /**
     * The substrate's prebuilt tool list — substrate built-ins (see
     * [defaultToolCatalog]) + [extraToolsFactory]. App tools come last so
     * the LLM sees the stable substrate prelude first (helps prompt
     * caching). Does NOT include `find_tool` (which depends on
     * [toolProvider], which depends on this list — circularity broken by
     * separating the two). [tools] is the public view that appends
     * `find_tool` when [hasOnDemandTools].
     */
    private val prebuiltTools: List<WeftTool<*, *>> = defaultToolCatalog(
        ctx = toolContext,
        contextRegistry = contextRegistry,
        dataSources = this.dataSources,
        networkClient = networkClient,
        memoryStore = memoryStore,
    ) + extraToolsFactory(toolContext)

    /**
     * Stage 2 of `docs/architecture/tool-provider.md`. The runtime's
     * effective [ToolProvider] — either the host-supplied override or
     * an [EagerToolProvider] auto-wrapping [prebuiltTools]. Exposed
     * publicly so apps can introspect the catalog (e.g., devtools
     * showing what `find_tool` would surface) without reaching for
     * runtime internals.
     */
    public val toolProvider: ToolProvider = toolProviderOverride
        ?: EagerToolProvider(tools = prebuiltTools, defaultAlwaysOn = true)

    /**
     * True when [toolProvider] advertises any on-demand
     * (`alwaysOn = false`) tool. Drives whether `find_tool` is
     * auto-registered into every agent's catalog. With the default
     * auto-built [EagerToolProvider] this is always false (everything
     * eager) — back-compat preserved.
     */
    private val hasOnDemandTools: Boolean =
        toolProvider.available.any { !it.alwaysOn }

    /**
     * The public tool list = [prebuiltTools] plus `find_tool` when
     * the [toolProvider] has any on-demand tool. Hosts running the
     * default eager provider see exactly today's pre-Stage-2 list.
     */
    public val tools: List<WeftTool<*, *>> =
        if (hasOnDemandTools) prebuiltTools + FindToolTool(toolContext, toolProvider)
        else prebuiltTools

    /**
     * Pre-computed `extraNotes` payload for the system prompt — reused
     * by both [systemPrompt] (pre-MCP) and [resolvedSystemPrompt] (post-MCP).
     */
    private val systemPromptExtraNotes: String? =
        listOfNotNull(extraSystemNotes, dynamicSystemPromptSection?.invoke())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

    /**
     * Assembles the system prompt for any tool catalog with this runtime's
     * stable inputs (preamble, components, data-source descriptions, extra
     * notes) baked in. The pre-MCP, MCP-resolved, and per-agent prompts all
     * route through it.
     */
    private val promptComposer = SystemPromptComposer(
        appPreamble = appPromptPreamble,
        components = componentMetadata,
        // Registered DataSources are auto-listed in the prompt so apps don't
        // hand-document their data layer in the preamble.
        dataSources = rawDataSources,
        extraNotes = systemPromptExtraNotes,
    )

    /**
     * The assembled system prompt: app preamble + auto-generated tool
     * catalog + standard trailing notes + optional [extraSystemNotes].
     * Computed once at construction.
     *
     * **MCP caveat:** this reflects the *pre-MCP* tool catalog (substrate
     * + [extraToolsFactory] only). Tools discovered from [mcpServers]
     * are NOT advertised here — they join the agent's catalog at
     * [buildAgent] time via [resolvedSystemPrompt]. The wire-level
     * `tools` block the LLM sees on each call still includes MCP tools
     * once discovery completes; this field is for human / devtools
     * inspection of the substrate-stable prompt.
     */
    public val systemPrompt: String = promptComposer.forTools(tools)

    /**
     * Assembles [WeftAgent]s from [AgentDeclaration]s. Holds the
     * agent-loop collaborators; reaches MCP-resolution + delegate
     * recursion back through lambdas so the runtime keeps ownership of
     * its caches.
     */
    private val agentBuilder = AgentBuilder(
        toolContext = toolContext,
        agentDeclarations = agentDeclarations,
        promptComposer = promptComposer,
        deps = AgentLoopDeps(
            traceStore = traceStore,
            usageStore = usageStore,
            quotaPolicy = quotaPolicy,
            redactor = redactor,
            conversationStore = conversationStore,
            memoryRegistry = memoryRegistry,
            maxIterations = maxIterations,
            maxOutputTokens = maxOutputTokens,
            deviceSnapshotProvider = deviceSnapshotProvider,
            extraVolatilePrefix = extraVolatilePrefix,
            toolProvider = toolProvider,
            hasOnDemandTools = hasOnDemandTools,
        ),
        resolveTools = { resolvedTools() },
        resolvedSystemPrompt = { resolvedSystemPrompt() },
        resolveAgent = { name, prov, pool ->
            buildAgent(agentName = name, provider = prov, modelPoolOverride = pool)
        },
    )

    /**
     * MCP-discovered tools, resolved asynchronously. Always present
     * (resolves to an empty list when [mcpServers] is empty). Per-server
     * failures route to [onMcpError]; the deferred itself never rejects.
     *
     * Awaited internally by [buildAgent] before constructing the agent's
     * tool registry. Apps that want to surface "MCP loading…" UI can
     * observe `.isCompleted` directly.
     */
    public val mcpToolsReady: Deferred<List<WeftTool<*, *>>> = buildMcpToolsReady()

    private fun buildMcpToolsReady(): Deferred<List<WeftTool<*, *>>> {
        if (mcpServers.isEmpty()) {
            return CompletableDeferred(emptyList())
        }
        // Reuses the host-supplied [networkClient]; the host is
        // responsible for installing `ContentNegotiation` if MCP is
        // configured. The Android `WeftRuntime.create(...)` factory
        // installs it automatically; iOS hosts wire equivalent setup
        // when they construct the client.
        val mcpClient = HttpMcpClient(networkClient)
        return runtimeScope.async(Dispatchers.Default) {
            mcpServers.flatMap { server ->
                val discovered: List<McpToolDescriptor>? = withTimeoutOrNull(mcpDiscoveryTimeout) {
                    runCatching {
                        mcpClient.initialize(server)
                        mcpClient.listTools(server)
                    }.getOrElse { t ->
                        onMcpError(server, t)
                        null
                    }
                }
                if (discovered == null) {
                    // null means either runCatching swallowed the failure
                    // (already routed to onMcpError above) or the timeout
                    // fired. In the timeout case, emit a synthetic error
                    // so callers see the diagnostic.
                    onMcpError(server, McpDiscoveryTimeoutException(server, mcpDiscoveryTimeout))
                    emptyList()
                } else {
                    discovered.map { mcpTool ->
                        val qualified = "${server.name}:${mcpTool.name}"
                        McpRemoteTool(
                            ctx = toolContext,
                            client = mcpClient,
                            serverConfig = server,
                            remoteToolName = mcpTool.name,
                            descriptor = translateToKoogDescriptor(qualified, mcpTool),
                        ) as WeftTool<*, *>
                    }
                }
            }
        }
    }

    /**
     * In-memory cache for [resolvedSystemPrompt]. Computed lazily once
     * MCP discovery completes. Null until the first call.
     */
    @kotlin.concurrent.Volatile
    private var cachedResolvedSystemPrompt: String? = null

    /**
     * The system prompt awaiting MCP tools to be discovered first. Equal
     * to [systemPrompt] when there are no MCP tools; otherwise includes
     * MCP tools in the auto-generated catalog. Cached after first
     * resolution.
     */
    public suspend fun resolvedSystemPrompt(): String {
        cachedResolvedSystemPrompt?.let { return it }
        val mcp = mcpToolsReady.await()
        val prompt = if (mcp.isEmpty()) systemPrompt else promptComposer.forTools(tools + mcp)
        cachedResolvedSystemPrompt = prompt
        return prompt
    }

    /** Substrate + extra + MCP tools, in the order they reach the agent. */
    public suspend fun resolvedTools(): List<WeftTool<*, *>> =
        tools + mcpToolsReady.await()


    /**
     * Build a Koog-backed [WeftAgent] using a [WeftCredentialProvider].
     *
     * The provider's [WeftCredentialProvider.bearer] is invoked once at
     * agent-build time, then the resulting credential is reused for every
     * subsequent LLM call this agent makes. To rotate credentials (token
     * refresh, key rotation), build a fresh agent. The provider's
     * [WeftCredentialProvider.baseUrl] is honored — apps using
     * [dev.weft.android.credentials.ProxyServerProvider] get their LLM
     * traffic routed through their own server transparently.
     *
     * Each call creates a fresh agent — there's no shared session state
     * across calls. The agent's conversation history is wired up
     * separately via [WeftAgent.resume] / [WeftAgent.newChat].
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    public suspend fun buildAgent(provider: dev.weft.contracts.WeftCredentialProvider): WeftAgent =
        buildAgent(provider, modelPoolOverride = null, strategy = null)

    /**
     * Two-arg back-compat overload — equivalent to passing
     * `strategy = null`. Builds the default agent.
     */
    public suspend fun buildAgent(
        provider: dev.weft.contracts.WeftCredentialProvider,
        modelPoolOverride: dev.weft.harness.agents.routing.ModelPool?,
    ): WeftAgent = buildAgent(provider, modelPoolOverride, strategy = null)

    /**
     * Build the default agent with optional [modelPoolOverride] and
     * [strategy] override. Equivalent to
     * `buildAgent(AgentDeclaration.DEFAULT_AGENT_NAME, provider, ...)`
     * but the explicit-strategy form takes precedence over the
     * declaration's strategy.
     */
    public suspend fun buildAgent(
        provider: dev.weft.contracts.WeftCredentialProvider,
        modelPoolOverride: dev.weft.harness.agents.routing.ModelPool?,
        strategy: dev.weft.harness.agents.strategy.WeftStrategy?,
    ): WeftAgent = buildAgent(
        agentName = dev.weft.harness.agents.AgentDeclaration.DEFAULT_AGENT_NAME,
        provider = provider,
        modelPoolOverride = modelPoolOverride,
        strategyOverride = strategy,
    )

    /**
     * Build a named agent. The agent's tool catalog is filtered by the
     * declaration's [AgentDeclaration.allowedTools]; its system prompt
     * appends the declaration's
     * [AgentDeclaration.systemFragment]; its loop policy is the
     * declaration's [AgentDeclaration.strategy] (unless [strategyOverride]
     * is non-null).
     *
     * Throws [IllegalArgumentException] if [agentName] is not in
     * [agentDeclarations].
     */
    public suspend fun buildAgent(
        agentName: String,
        provider: dev.weft.contracts.WeftCredentialProvider,
        modelPoolOverride: dev.weft.harness.agents.routing.ModelPool? = null,
        strategyOverride: dev.weft.harness.agents.strategy.WeftStrategy? = null,
    ): WeftAgent {
        val declaration = agentDeclarations[agentName]
            ?: error(
                "Unknown agent: '$agentName'. " +
                    "Registered: ${agentDeclarations.keys}",
            )
        return agentBuilder.build(
            declaration = declaration,
            provider = provider,
            modelPoolOverride = modelPoolOverride,
            strategyOverride = strategyOverride,
        )
    }

    /**
     * Convenience overload: wraps a raw [apiKey] in a
     * [dev.weft.android.credentials.StaticKeyProvider]. Kept for back-compat
     * with the BYOK reference-app flow; new code should construct an explicit
     * provider so the credential model is visible at the call site.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    public suspend fun buildAgent(apiKey: String): WeftAgent =
        buildAgent(dev.weft.android.credentials.StaticKeyProvider(apiKey))

    public companion object {
        public const val ANTHROPIC_KEY_ALIAS: String = "anthropic"
        public const val OPENAI_KEY_ALIAS: String = "openai"
        public const val OPENROUTER_KEY_ALIAS: String = "openrouter"
        public const val DEEPSEEK_KEY_ALIAS: String = "deepseek"

        /**
         * DeepSeek catalog — defined here because Koog 1.0.0 doesn't
         * publish a DeepSeek client/catalog. We talk to api.deepseek.com
         * via [ai.koog.prompt.executor.clients.openai.OpenAILLMClient]
         * (OpenAI-compatible wire) and tag models with
         * [ai.koog.prompt.llm.LLMProvider.DeepSeek] so routing + cost
         * attribution stay distinct.
         */
        public const val DEEPSEEK_BASE_URL: String = "https://api.deepseek.com"

        public val DEEPSEEK_CHAT_MODEL: LLModel = LLModel(
            provider = LLMProvider.DeepSeek,
            id = "deepseek-chat",
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Completion,
            ),
            contextLength = 64_000,
            maxOutputTokens = 8_192,
        )

        public val DEEPSEEK_REASONER_MODEL: LLModel = LLModel(
            provider = LLMProvider.DeepSeek,
            id = "deepseek-reasoner",
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Completion,
            ),
            contextLength = 64_000,
            maxOutputTokens = 8_192,
        )

        /**
         * Per-turn cap on the agent's tool-call iterations. Bumped from
         * Koog's original 10 because Undercurrent-style multi-step
         * patterns (ui_event → data_upsert → data_query → ui_render,
         * with possible memory_recall and retry overhead) consistently
         * brushed against the old limit on tracker-shaped mini-apps.
         * Apps that need more (long agentic plans) can override via the
         * `maxIterations` parameter on [create].
         */
        public const val MAX_ITERATIONS_DEFAULT: Int = 25

        /**
         * Hard timeout per MCP server during initial tools/list
         * discovery. A misbehaving server otherwise hangs the
         * [mcpToolsReady] deferred and blocks the first agent build.
         */
        public val DEFAULT_MCP_DISCOVERY_TIMEOUT: Duration = 10.seconds

        /**
         * How often the periodic TTL sweeper runs after the initial startup
         * sweep. 1 hour balances cleanup latency against power use — on a
         * typical phone session apps are alive for minutes, not hours, so
         * the startup sweep handles the vast majority of cases.
         */
        public val DEFAULT_TTL_SWEEP_INTERVAL: Duration = 1.hours

        /**
         * Custom LLModel for Sonnet 4.6 (Koog 0.8.0's AnthropicModels catalog
         * only includes up to Sonnet 4.5). Registered via modelVersionsMap above.
         */
        public val SONNET_4_6_MODEL: LLModel = LLModel(
            provider = LLMProvider.Anthropic,
            id = "claude-sonnet-4-6",
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Vision.Image,
                LLMCapability.Completion,
            ),
            contextLength = 200_000,
            maxOutputTokens = 8_192,
        )
    }
}

/**
 * Thrown via [WeftRuntime]'s `onMcpError` sink when a single MCP server's
 * discovery exceeds the configured per-server timeout. The runtime
 * continues with whatever tools resolved; the failing server's tools
 * are omitted.
 */
public class McpDiscoveryTimeoutException(
    server: McpServerConfig,
    timeout: Duration,
) : RuntimeException("MCP discovery timed out for ${server.name} after $timeout")
