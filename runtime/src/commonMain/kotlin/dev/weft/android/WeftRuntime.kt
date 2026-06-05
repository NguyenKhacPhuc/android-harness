package dev.weft.android

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.weft.contracts.ComponentMetadata
import dev.weft.contracts.ContextProvider
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
     * App-specific opening for the system prompt — describes the app's
     * purpose, persona, and any high-level constraints. The substrate
     * appends the auto-generated tool catalog after this.
     */
    private val appPromptPreamble: String,
    /** App-specific [DataSource]s exposed to the `data_*` tools. */
    dataSources: List<DataSource> = emptyList(),
    /** Network allowlist used by `network_fetch`. Defaults to "no hosts allowed". */
    public val networkPolicy: NetworkPolicy = NetworkPolicy(coreAllowlist = emptySet()),
    /**
     * Extra context providers merged with the substrate default `device`
     * provider. Use to expose app data (user profile, subscription tier…) to
     * the agent via `system_user_context`.
     */
    extraContextProviders: List<ContextProvider> = emptyList(),
    /**
     * Hook for the app to register its own tools. Called with the
     * [WeftContext] and the result is appended after the substrate's
     * stable tool prelude.
     *
     * **This is where UI tools land.** Apps using `:substrate:android-ui`
     * pass `substrateUi.toolsFactory` here to register `ui_render` and
     * `ui_notify`. Apps with custom UI provide their own tools or none.
     */
    private val extraToolsFactory: (WeftContext) -> List<WeftTool<*, *>> = { _ -> emptyList() },
    /**
     * Stage 2 of `docs/architecture/tool-provider.md` — optional lazy
     * tool catalog. When null (default), the runtime auto-builds an
     * [EagerToolProvider] wrapping the substrate's prebuilt list plus
     * anything `extraToolsFactory` produced, with the substrate's
     * always-on subset (memory_*, system_user_context, find_tool)
     * tagged accordingly. Existing single-provider hosts see zero
     * behavior change.
     *
     * Apps that want lazy MCP / app-domain tools pass a custom
     * provider — typically `compositeToolProvider(substrateProvider,
     * appProvider, mcpProvider)`. The activation node in the agent
     * strategy resolves names from `find_tool` searches against this
     * provider mid-turn.
     */
    private val toolProviderOverride: ToolProvider? = null,
    /**
     * Component metadata for the system prompt's UI catalog (per ADR-007).
     * Apps using `:substrate:android-ui` pass `substrateUi.components` here.
     * Empty list = system prompt won't mention UI components at all.
     */
    private val componentMetadata: List<ComponentMetadata> = emptyList(),
    /**
     * Optional extra text appended to the system prompt after the standard
     * trailing notes — useful for app-specific tool-use hints.
     */
    private val extraSystemNotes: String? = null,
    /**
     * Optional supplier of additional per-session-stable text appended
     * to the system prompt **after** [extraSystemNotes]. Runs once at
     * runtime construction, so the resulting text reaches the STATIC
     * cache tier and stays cached for the runtime's lifetime.
     *
     * Use this for content the LLM should treat as **instructions** —
     * user persona, locale conventions, tone preferences. Per-turn
     * dynamic content (current screen, last action, recent memory)
     * belongs in [extraVolatilePrefix] instead, which goes into the
     * user message layer.
     *
     * CAUTION: anything that varies *within* a session (e.g., the user
     * changing their preferences mid-conversation) belongs in
     * [extraVolatilePrefix], not here — changing this value across
     * runtime instances re-creates the prompt and busts the cache.
     */
    private val dynamicSystemPromptSection: (() -> String)? = null,
    /**
     * App-supplied per-turn context — composes with the substrate's
     * built-in device snapshot. The returned text is appended below
     * the device snapshot inside the volatile prefix layer (which sits
     * just above the user message). Use for short, churning context the
     * LLM should treat as **data** rather than instructions: current
     * screen, active document id, last UI action timestamp, etc.
     *
     * For stable-per-session content, use [dynamicSystemPromptSection]
     * instead — that reaches the STATIC cache tier.
     */
    private val extraVolatilePrefix: () -> String = { "" },
    /**
     * App-registered [MemoryProvider]s. Queried per-turn alongside the
     * substrate's own memory provider; hits are injected into the user
     * message as "Relevant context" so the LLM sees them without
     * needing to call `memory_recall`. Useful for RAG, app-managed user
     * profile data, vector-store retrieval, etc.
     */
    private val extraMemoryProviders: List<dev.weft.contracts.MemoryProvider> = emptyList(),
    /**
     * Optional override for the substrate's [MemoryStore]. Defaults to
     * a SQLDelight-backed store on the substrate database. Apps with
     * their own memory backend (remote KB, vector store) supply an
     * implementation here; the `memory_*` tools then route through it.
     */
    private val memoryStoreOverride: MemoryStore? = null,
    /**
     * Optional override for the substrate's [ConversationStore]. Same
     * pattern as [memoryStoreOverride] — defaults to a SQLDelight-backed
     * store on the substrate database. Apps that want a synced /
     * multi-device conversation store supply their own implementation
     * here.
     */
    private val conversationStoreOverride: ConversationStore? = null,
    public val quotaPolicy: QuotaPolicy = QuotaPolicy(),
    /**
     * Single redactor instance shared between tool-trace writes (applied by
     * [WeftAgent] before persisting argsPreview / resultPreview /
     * finalAssistantMessage / error messages) and post-hoc exports
     * (apps should redact the JSON string before sharing it externally).
     *
     * Override to extend the rule set without losing defaults:
     * `redactor = Redactor(Redactor.DEFAULT_RULES + myRules)`. Pass an
     * empty list to disable: `Redactor(rules = emptyList())`.
     */
    public val redactor: Redactor = Redactor(),
    /**
     * How often to re-sweep TTL-expired `key_value` rows in the background.
     * The one-shot startup sweep handles the common case; the periodic
     * ticker covers long-lived sessions (apps that stay alive for hours).
     * Pass [Duration.INFINITE] to disable the periodic sweep entirely (the
     * startup sweep still runs).
     */
    private val ttlSweepInterval: Duration = DEFAULT_TTL_SWEEP_INTERVAL,
    private val maxIterations: Int = MAX_ITERATIONS_DEFAULT,
    /**
     * Per-LLM-call `max_tokens` budget threaded into every agent built
     * by [buildAgent]. See [WeftAgent.DEFAULT_MAX_OUTPUT_TOKENS]
     * for why this defaults to 8192 instead of Koog's 2048.
     */
    private val maxOutputTokens: Int = WeftAgent.DEFAULT_MAX_OUTPUT_TOKENS,
    /**
     * Registered agent declarations. Empty list (default) =
     * auto-synthesize a single
     * [dev.weft.harness.agents.AgentDeclaration.default] entry, which
     * reproduces pre-multi-agent behavior. Apps that want multiple
     * agents (e.g. "writer" + "researcher") pass declarations here;
     * `runtime.buildAgent(name, provider)` selects by name.
     */
    agents: List<dev.weft.harness.agents.AgentDeclaration> = emptyList(),
    /**
     * MCP servers to discover tools from. Discovery (HTTP initialize +
     * tools/list) runs asynchronously in [runtimeScope] on
     * [Dispatchers.IO]; the resulting tools resolve through
     * [mcpToolsReady] and are appended to the agent's tool catalog the
     * first time [buildAgent] is called.
     *
     * Empty list (default) = no MCP, no background work. Same
     * [networkPolicy] gates apply: every server URL must pass the
     * allowlist or the request fails.
     */
    private val mcpServers: List<McpServerConfig> = emptyList(),
    /**
     * Per-server error sink. Discovery isolates failures — a single
     * unreachable server doesn't reject [mcpToolsReady]; it routes
     * through here and the failing server's tools are omitted. Use to
     * log diagnostics or surface a "reconnect" hint.
     */
    private val onMcpError: (McpServerConfig, Throwable) -> Unit = { _, _ -> },
    /**
     * Hard timeout per MCP server discovery. A misbehaving server can
     * otherwise hang the deferred forever, blocking the first
     * [buildAgent] call. On timeout the server is treated like any
     * other failure — [onMcpError] fires and its tools are omitted.
     */
    private val mcpDiscoveryTimeout: Duration = DEFAULT_MCP_DISCOVERY_TIMEOUT,
    /**
     * SQLDelight database backing every persistent store the substrate
     * ships. Built by `WeftRuntime.create` from the app's platform
     * handle; tests can pass a JDBC in-memory variant; iOS hosts pass
     * a [NativeSqliteDriver]-backed variant.
     */
    private val database: dev.weft.android.db.WeftDatabase,
    /**
     * HTTP client used by `network_fetch` (and the MCP transport when
     * one is configured). [Companion.create] on Android wires a
     * Ktor + OkHttp client wrapped with the host-allowlist policy; iOS
     * hosts wire Ktor + Darwin similarly. Apps can supply their own —
     * useful for adding tracing, custom retry, or a corporate proxy.
     */
    private val networkClient: io.ktor.client.HttpClient,
    /**
     * Per-turn device snapshot prepended to the user message. Android's
     * `create` factory wires the substrate's built-in
     * `deviceSnapshot(context)` (Build.VERSION + locale + connectivity);
     * iOS hosts wire a UIDevice-backed equivalent. Default returns
     * empty — the LLM still gets all the other context, just without a
     * platform-specific device block.
     */
    private val deviceSnapshotProvider: () -> String = { "" },
) {
    public val keyVault: KeyVault get() = os.keyVault

    /** Raw list snapshot — kept for prompt re-assembly when MCP tools resolve. */
    private val rawDataSources: List<DataSource> = dataSources

    public val dataSources: DataSourceRegistry = DataSourceRegistry(dataSources)

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
        listOf(DeviceContextProvider(os)) + extraContextProviders,
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
        public val DEEPSEEK_BASE_URL: String = "https://api.deepseek.com"

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

        // `create(...)` Android-convenience factory lives as a
        // Companion-extension in `WeftRuntimeAndroid.kt` so the class
        // declaration itself can lift to commonMain. iOS hosts write
        // their own `create(WeftPlatform, …)` extension that wires the
        // Darwin Ktor engine + their iOS-side OsCapabilities + a
        // UIDevice-backed deviceSnapshotProvider.
    }
}

/**
 * Thrown via [WeftRuntime]'s `onMcpError` sink when a single MCP server's
 * discovery exceeds the configured per-server timeout. The runtime
 * continues with whatever tools resolved; the failing server's tools
 * are omitted.
 */
public class McpDiscoveryTimeoutException(
    public val server: McpServerConfig,
    public val timeout: Duration,
) : RuntimeException("MCP discovery timed out for ${server.name} after $timeout")
