package dev.weft.android

import android.content.Context
import dev.weft.contracts.ComponentMetadata
import dev.weft.contracts.ContextProvider
import dev.weft.contracts.DataSource
import dev.weft.contracts.ToolProvider
import dev.weft.contracts.UiBridge
import dev.weft.android.persistence.SqlDelightScheduledNotificationKeyStore
import dev.weft.android.persistence.WeftDatabaseFactory
import dev.weft.android.persistence.WeftPlatform
import dev.weft.harness.agents.AgentDeclaration
import dev.weft.harness.agents.WeftAgent
import dev.weft.harness.conversation.ConversationStore
import dev.weft.harness.cost.QuotaPolicy
import dev.weft.harness.memory.MemoryStore
import dev.weft.harness.observability.Redactor
import dev.weft.mcp.McpServerConfig
import dev.weft.osbridge.AndroidOsCapabilities
import dev.weft.security.NetworkPolicy
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.time.Duration

/**
 * Android convenience factory for [WeftRuntime].
 *
 * Wires Android-specific defaults so app code can spin up a runtime
 * with one call:
 *
 * ```kotlin
 * val runtime = WeftRuntime.create(
 *     context = appContext,
 *     uiBridge = composeUiBridge,
 *     appPromptPreamble = "You are Undercurrent's assistant.",
 * )
 * ```
 *
 * Wires:
 *   - the SqlDelight database against `AndroidSqliteDriver` + bundled SQLite,
 *   - `AndroidOsCapabilities` (Camera, Location, Pdf, etc.) on top of the
 *     scheduled-notification SQLDelight store,
 *   - the host-allowlist HTTP client backed by Ktor + OkHttp,
 *   - the Android device-snapshot supplier (Build.VERSION, locale,
 *     connectivity) for the per-turn volatile prefix.
 *
 * iOS hosts call the `WeftRuntime` constructor directly (or write their
 * own `WeftRuntime.Companion.create` extension in their iosMain) and
 * wire the iOS-equivalent of each capability.
 */
public fun WeftRuntime.Companion.create(
    context: Context,
    uiBridge: UiBridge,
    appPromptPreamble: String,
    dataSources: List<DataSource> = emptyList(),
    networkPolicy: NetworkPolicy = NetworkPolicy(coreAllowlist = emptySet()),
    extraContextProviders: List<ContextProvider> = emptyList(),
    extraToolsFactory: (WeftContext) -> List<WeftTool<*, *>> = { _ -> emptyList() },
    /**
     * Stage 2 of `docs/architecture/tool-provider.md` — optional lazy
     * tool provider. Null (default) auto-wraps the substrate's prebuilt
     * list as an `EagerToolProvider` with everything `alwaysOn`,
     * preserving today's behavior. Pass a custom provider (typically
     * `compositeToolProvider(substrateProvider, mcpProvider, appProvider)`)
     * when you want `find_tool` discovery + lazy MCP/app-domain tool
     * materialization.
     */
    toolProvider: ToolProvider? = null,
    componentMetadata: List<ComponentMetadata> = emptyList(),
    extraSystemNotes: String? = null,
    dynamicSystemPromptSection: (() -> String)? = null,
    extraVolatilePrefix: () -> String = { "" },
    extraMemoryProviders: List<dev.weft.contracts.MemoryProvider> = emptyList(),
    memoryStoreOverride: MemoryStore? = null,
    conversationStoreOverride: ConversationStore? = null,
    quotaPolicy: QuotaPolicy = QuotaPolicy(),
    redactor: Redactor = Redactor(),
    maxIterations: Int = WeftRuntime.MAX_ITERATIONS_DEFAULT,
    /**
     * Per-turn output token budget. Clamped to the active model's own
     * ceiling, so a value above what the model supports is safe. Raise it
     * for turns that emit large payloads (HTML mini-app documents, big
     * `ui_render` trees) that the default would truncate.
     */
    maxOutputTokens: Int = WeftAgent.DEFAULT_MAX_OUTPUT_TOKENS,
    /**
     * MCP servers to discover tools from. Empty list = no MCP.
     * Discovery runs in the background; the first `buildAgent` call
     * awaits its completion. The same [networkPolicy] allowlist applies
     * to MCP server hosts.
     */
    mcpServers: List<McpServerConfig> = emptyList(),
    onMcpError: (McpServerConfig, Throwable) -> Unit = { _, _ -> },
    mcpDiscoveryTimeout: Duration = WeftRuntime.DEFAULT_MCP_DISCOVERY_TIMEOUT,
    /**
     * Multi-agent registry. Empty list = auto-default single agent named
     * `AgentDeclaration.DEFAULT_AGENT_NAME` (preserves pre-multi-agent
     * behavior). Non-empty = each declaration becomes addressable via
     * `runtime.buildAgent(agentName, …)`.
     */
    agents: List<AgentDeclaration> = emptyList(),
): WeftRuntime {
    val appContext = context.applicationContext
    val database = WeftDatabaseFactory.create(WeftPlatform(appContext))
    val scheduledNotificationStore = SqlDelightScheduledNotificationKeyStore(database)
    return assembleWeftRuntime(
        os = AndroidOsCapabilities.create(
            appContext,
            scheduledNotificationStore = scheduledNotificationStore,
        ),
        database = database,
        networkEngine = OkHttp.create(),
        deviceSnapshotProvider = { deviceSnapshot(appContext) },
        uiBridge = uiBridge,
        config = WeftRuntimeConfig(
            appPromptPreamble = appPromptPreamble,
            dataSources = dataSources,
            networkPolicy = networkPolicy,
            extraContextProviders = extraContextProviders,
            extraToolsFactory = extraToolsFactory,
            toolProviderOverride = toolProvider,
            componentMetadata = componentMetadata,
            extraSystemNotes = extraSystemNotes,
            dynamicSystemPromptSection = dynamicSystemPromptSection,
            extraVolatilePrefix = extraVolatilePrefix,
            extraMemoryProviders = extraMemoryProviders,
            memoryStoreOverride = memoryStoreOverride,
            conversationStoreOverride = conversationStoreOverride,
            quotaPolicy = quotaPolicy,
            redactor = redactor,
            maxIterations = maxIterations,
            maxOutputTokens = maxOutputTokens,
            mcpServers = mcpServers,
            onMcpError = onMcpError,
            mcpDiscoveryTimeout = mcpDiscoveryTimeout,
            agents = agents,
        ),
    )
}
