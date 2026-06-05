package dev.weft.android

import dev.weft.contracts.ComponentMetadata
import dev.weft.contracts.ContextProvider
import dev.weft.contracts.DataSource
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.ToolProvider
import dev.weft.contracts.UiBridge
import dev.weft.android.persistence.WeftDatabaseFactory
import dev.weft.android.persistence.WeftPlatform
import dev.weft.harness.agents.AgentDeclaration
import dev.weft.harness.conversation.ConversationStore
import dev.weft.harness.cost.QuotaPolicy
import dev.weft.harness.memory.MemoryStore
import dev.weft.harness.observability.Redactor
import dev.weft.mcp.McpServerConfig
import dev.weft.osbridge.IosOsCapabilities
import dev.weft.security.NetworkPolicy
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import io.ktor.client.engine.darwin.Darwin
import kotlin.time.Duration

/**
 * iOS convenience factory for [WeftRuntime].
 *
 * Wires the iOS-specific defaults that the [androidMain
 * `WeftRuntime.create(...)`][dev.weft.android.create] factory wires on
 * Android:
 *
 *   - SQLDelight database against `NativeSqliteDriver` (Kotlin/Native
 *     links the system libsqlite3).
 *   - host-allowlist HTTP client backed by Ktor + Darwin
 *     (NSURLSession underneath) with `ContentNegotiation` pre-installed
 *     so MCP discovery can reuse it.
 *   - `iosDeviceSnapshot()` for the per-turn volatile prefix.
 *
 * [os] now defaults to [IosOsCapabilities] — a fully-wired capability
 * set, the same one-call shape the Android factory offers. Implemented
 * capabilities (KeyVault, Clipboard, Permissions, Haptics, Power,
 * ImageOps, SystemInfo, Sharing, Intents) work out of the box; the rest
 * are loud-failure stubs whose calls surface to the LLM as a tool error
 * (never a crash). Override individual capabilities as you implement
 * more, or pass a `FakeOsCapabilities` from `:harness:testing` for
 * silent no-ops:
 *
 * ```kotlin
 * // One call — no stand-ins needed:
 * val runtime = WeftRuntime.create(
 *     platform = WeftPlatform(),
 *     uiBridge = composeUiBridge,
 *     appPromptPreamble = "You are MyApp's assistant.",
 * )
 *
 * // …or override specific capabilities:
 * WeftRuntime.create(
 *     platform = WeftPlatform(),
 *     os = IosOsCapabilities(location = MyCustomLocation()),
 *     uiBridge = composeUiBridge,
 *     appPromptPreamble = "…",
 * )
 * ```
 *
 * See `docs/architecture/ios-os-capabilities.md` for the implementation
 * backlog of the still-stubbed sub-interfaces (Vision, Pdf, Calendar,
 * …) — which iOS native API to wrap and which tools each unblocks.
 */
public fun WeftRuntime.Companion.create(
    platform: WeftPlatform,
    os: OsCapabilities = IosOsCapabilities(),
    uiBridge: UiBridge,
    appPromptPreamble: String,
    dataSources: List<DataSource> = emptyList(),
    networkPolicy: NetworkPolicy = NetworkPolicy(coreAllowlist = emptySet()),
    extraContextProviders: List<ContextProvider> = emptyList(),
    extraToolsFactory: (WeftContext) -> List<WeftTool<*, *>> = { _ -> emptyList() },
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
    mcpServers: List<McpServerConfig> = emptyList(),
    onMcpError: (McpServerConfig, Throwable) -> Unit = { _, _ -> },
    mcpDiscoveryTimeout: Duration = WeftRuntime.DEFAULT_MCP_DISCOVERY_TIMEOUT,
    agents: List<AgentDeclaration> = emptyList(),
): WeftRuntime {
    val database = WeftDatabaseFactory.create(platform)
    return assembleWeftRuntime(
        os = os,
        database = database,
        networkEngine = Darwin.create(),
        deviceSnapshotProvider = { iosDeviceSnapshot() },
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
            mcpServers = mcpServers,
            onMcpError = onMcpError,
            mcpDiscoveryTimeout = mcpDiscoveryTimeout,
            agents = agents,
        ),
    )
}
