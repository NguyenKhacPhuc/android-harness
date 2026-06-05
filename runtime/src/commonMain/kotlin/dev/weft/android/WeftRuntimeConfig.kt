package dev.weft.android

import dev.weft.contracts.ComponentMetadata
import dev.weft.contracts.ContextProvider
import dev.weft.contracts.DataSource
import dev.weft.contracts.MemoryProvider
import dev.weft.contracts.ToolProvider
import dev.weft.harness.agents.AgentDeclaration
import dev.weft.harness.agents.WeftAgent
import dev.weft.harness.conversation.ConversationStore
import dev.weft.harness.cost.QuotaPolicy
import dev.weft.harness.memory.MemoryStore
import dev.weft.harness.observability.Redactor
import dev.weft.mcp.McpServerConfig
import dev.weft.security.NetworkPolicy
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlin.time.Duration

/**
 * App-facing configuration for a [WeftRuntime] — everything the host
 * tunes, separated from the platform-bound wiring ([os][WeftRuntime.os],
 * UI bridge, database, network client, device snapshot) the `create`
 * factories supply.
 *
 * Bundling these into one object keeps the [WeftRuntime] constructor and
 * the internal `assembleWeftRuntime` plumbing readable: they pass a single
 * `config` instead of threading ~20 loose arguments through each layer.
 * The public `WeftRuntime.create(...)` factories remain flat (named args +
 * per-field defaults) and build this internally.
 */
public class WeftRuntimeConfig(
    /**
     * App-specific opening for the system prompt — describes the app's
     * purpose, persona, and any high-level constraints. The substrate
     * appends the auto-generated tool catalog after this.
     */
    public val appPromptPreamble: String,
    /** App-specific [DataSource]s exposed to the `data_*` tools. */
    public val dataSources: List<DataSource> = emptyList(),
    /** Network allowlist used by `network_fetch`. Defaults to "no hosts allowed". */
    public val networkPolicy: NetworkPolicy = NetworkPolicy(coreAllowlist = emptySet()),
    /**
     * Extra context providers merged with the substrate default `device`
     * provider. Use to expose app data (user profile, subscription tier…) to
     * the agent via `system_user_context`.
     */
    public val extraContextProviders: List<ContextProvider> = emptyList(),
    /**
     * Hook for the app to register its own tools. Called with the
     * [WeftContext] and the result is appended after the substrate's
     * stable tool prelude.
     *
     * **This is where UI tools land.** Apps using `:substrate:android-ui`
     * pass `substrateUi.toolsFactory` here to register `ui_render` and
     * `ui_notify`. Apps with custom UI provide their own tools or none.
     */
    public val extraToolsFactory: (WeftContext) -> List<WeftTool<*, *>> = { _ -> emptyList() },
    /**
     * Stage 2 of `docs/architecture/tool-provider.md` — optional lazy
     * tool catalog. When null (default), the runtime auto-builds an
     * [dev.weft.tools.EagerToolProvider] wrapping the substrate's prebuilt
     * list plus anything `extraToolsFactory` produced. Apps that want lazy
     * MCP / app-domain tools pass a custom provider — typically
     * `compositeToolProvider(substrateProvider, appProvider, mcpProvider)`.
     */
    public val toolProviderOverride: ToolProvider? = null,
    /**
     * Component metadata for the system prompt's UI catalog (per ADR-007).
     * Apps using `:substrate:android-ui` pass `substrateUi.components` here.
     * Empty list = system prompt won't mention UI components at all.
     */
    public val componentMetadata: List<ComponentMetadata> = emptyList(),
    /**
     * Optional extra text appended to the system prompt after the standard
     * trailing notes — useful for app-specific tool-use hints.
     */
    public val extraSystemNotes: String? = null,
    /**
     * Optional supplier of additional per-session-stable text appended to
     * the system prompt **after** [extraSystemNotes]. Runs once at runtime
     * construction, so the result reaches the STATIC cache tier and stays
     * cached for the runtime's lifetime.
     *
     * Use for content the LLM should treat as **instructions** — user
     * persona, locale conventions, tone. Per-turn dynamic content belongs
     * in [extraVolatilePrefix]. CAUTION: anything that varies *within* a
     * session belongs in [extraVolatilePrefix], not here — changing this
     * across runtime instances re-creates the prompt and busts the cache.
     */
    public val dynamicSystemPromptSection: (() -> String)? = null,
    /**
     * App-supplied per-turn context — composes with the substrate's
     * built-in device snapshot, appended below it in the volatile prefix
     * layer (just above the user message). Use for short, churning context
     * the LLM should treat as **data**: current screen, active document id,
     * last UI action timestamp, etc. For stable-per-session content use
     * [dynamicSystemPromptSection] instead (it reaches the STATIC tier).
     */
    public val extraVolatilePrefix: () -> String = { "" },
    /**
     * App-registered [MemoryProvider]s. Queried per-turn alongside the
     * substrate's own memory provider; hits are injected into the user
     * message as "Relevant context" so the LLM sees them without needing
     * to call `memory_recall`. Useful for RAG, app-managed profile data,
     * vector-store retrieval, etc.
     */
    public val extraMemoryProviders: List<MemoryProvider> = emptyList(),
    /**
     * Optional override for the substrate's [MemoryStore]. Defaults to a
     * SQLDelight-backed store on the substrate database. Apps with their
     * own memory backend (remote KB, vector store) supply one here; the
     * `memory_*` tools then route through it.
     */
    public val memoryStoreOverride: MemoryStore? = null,
    /**
     * Optional override for the substrate's [ConversationStore]. Same
     * pattern as [memoryStoreOverride] — defaults to a SQLDelight-backed
     * store. Apps wanting a synced / multi-device conversation store supply
     * their own here.
     */
    public val conversationStoreOverride: ConversationStore? = null,
    public val quotaPolicy: QuotaPolicy = QuotaPolicy(),
    /**
     * Single redactor shared between tool-trace writes (applied by
     * [WeftAgent] before persisting previews / messages) and post-hoc
     * exports. Override to extend the rule set without losing defaults:
     * `Redactor(Redactor.DEFAULT_RULES + myRules)`; pass `emptyList()` to
     * disable.
     */
    public val redactor: Redactor = Redactor(),
    /**
     * How often to re-sweep TTL-expired `key_value` rows in the background.
     * The one-shot startup sweep handles the common case; the periodic
     * ticker covers long-lived sessions. [Duration.INFINITE] disables the
     * periodic sweep (the startup sweep still runs).
     */
    public val ttlSweepInterval: Duration = WeftRuntime.DEFAULT_TTL_SWEEP_INTERVAL,
    public val maxIterations: Int = WeftRuntime.MAX_ITERATIONS_DEFAULT,
    /**
     * Per-LLM-call `max_tokens` budget threaded into every agent built by
     * [WeftRuntime.buildAgent]. Clamped to the active model's own ceiling.
     */
    public val maxOutputTokens: Int = WeftAgent.DEFAULT_MAX_OUTPUT_TOKENS,
    /**
     * Registered agent declarations. Empty (default) = auto-synthesize a
     * single [AgentDeclaration.default] entry (pre-multi-agent behavior).
     * Non-empty = each is addressable via `buildAgent(name, provider)`.
     */
    public val agents: List<AgentDeclaration> = emptyList(),
    /**
     * MCP servers to discover tools from. Discovery runs asynchronously;
     * resolved tools join the agent's catalog on the first `buildAgent`.
     * Empty (default) = no MCP. The [networkPolicy] allowlist gates server
     * hosts too.
     */
    public val mcpServers: List<McpServerConfig> = emptyList(),
    /**
     * Per-server discovery error sink. Failures are isolated — one
     * unreachable server doesn't reject the rest; it routes here and its
     * tools are omitted.
     */
    public val onMcpError: (McpServerConfig, Throwable) -> Unit = { _, _ -> },
    /**
     * Hard timeout per MCP server discovery. On timeout the server is
     * treated like any other failure ([onMcpError] fires, tools omitted).
     */
    public val mcpDiscoveryTimeout: Duration = WeftRuntime.DEFAULT_MCP_DISCOVERY_TIMEOUT,
)
