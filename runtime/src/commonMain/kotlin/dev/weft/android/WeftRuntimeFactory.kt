package dev.weft.android

import dev.weft.android.db.WeftDatabase
import dev.weft.contracts.ComponentMetadata
import dev.weft.contracts.ContextProvider
import dev.weft.contracts.DataSource
import dev.weft.contracts.MemoryProvider
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.ToolProvider
import dev.weft.contracts.UiBridge
import dev.weft.harness.agents.AgentDeclaration
import dev.weft.harness.conversation.ConversationStore
import dev.weft.harness.cost.QuotaPolicy
import dev.weft.harness.memory.MemoryStore
import dev.weft.harness.observability.Redactor
import dev.weft.mcp.HttpMcpClient
import dev.weft.mcp.McpServerConfig
import dev.weft.security.NetworkPolicy
import dev.weft.security.whitelistingHttpClient
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Duration

/**
 * Shared cross-platform assembly for [WeftRuntime].
 *
 * The Android `WeftRuntime.create(context, …)` factory and the iOS
 * `WeftRuntime.create(platform, os, …)` factory both delegate here.
 * They differ only in the three platform-bound pieces they supply: the
 * [os] capabilities, the HTTP [networkEngine] (OkHttp / Darwin), and the
 * [deviceSnapshotProvider]. Everything else — the host-allowlist network
 * client (with `ContentNegotiation` pre-installed so MCP discovery can
 * reuse it) and the full runtime construction — lives here, so there is
 * one composition rather than two parallel ones.
 */
@Suppress("LongParameterList")
internal fun assembleWeftRuntime(
    os: OsCapabilities,
    database: WeftDatabase,
    networkEngine: HttpClientEngine,
    deviceSnapshotProvider: () -> String,
    uiBridge: UiBridge,
    appPromptPreamble: String,
    dataSources: List<DataSource>,
    networkPolicy: NetworkPolicy,
    extraContextProviders: List<ContextProvider>,
    extraToolsFactory: (WeftContext) -> List<WeftTool<*, *>>,
    toolProvider: ToolProvider?,
    componentMetadata: List<ComponentMetadata>,
    extraSystemNotes: String?,
    dynamicSystemPromptSection: (() -> String)?,
    extraVolatilePrefix: () -> String,
    extraMemoryProviders: List<MemoryProvider>,
    memoryStoreOverride: MemoryStore?,
    conversationStoreOverride: ConversationStore?,
    quotaPolicy: QuotaPolicy,
    redactor: Redactor,
    maxIterations: Int,
    mcpServers: List<McpServerConfig>,
    onMcpError: (McpServerConfig, Throwable) -> Unit,
    mcpDiscoveryTimeout: Duration,
    agents: List<AgentDeclaration>,
): WeftRuntime = WeftRuntime(
    os = os,
    uiBridge = uiBridge,
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
    agents = agents,
    mcpServers = mcpServers,
    onMcpError = onMcpError,
    mcpDiscoveryTimeout = mcpDiscoveryTimeout,
    database = database,
    networkClient = whitelistingHttpClient(
        engine = networkEngine,
        policy = networkPolicy,
        extraConfig = {
            install(ContentNegotiation) { json(HttpMcpClient.DEFAULT_JSON) }
        },
    ),
    deviceSnapshotProvider = deviceSnapshotProvider,
)
