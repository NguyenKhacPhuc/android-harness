package dev.weft.android

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import android.content.Context
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
import dev.weft.harness.prompt.assembleSystemPrompt
import dev.weft.harness.cost.QuotaPolicy
import dev.weft.harness.cost.UsageStore
import dev.weft.harness.memory.MemoryCompactTool
import dev.weft.harness.memory.MemoryRecallTool
import dev.weft.harness.memory.MemoryStore
import dev.weft.harness.memory.MemoryStoreTool
import dev.weft.harness.observability.Redactor
import dev.weft.harness.observability.TraceStore
import dev.weft.mcp.HttpMcpClient
import dev.weft.mcp.McpRemoteTool
import dev.weft.mcp.McpServerConfig
import dev.weft.mcp.McpToolDescriptor
import dev.weft.mcp.translateToKoogDescriptor
import dev.weft.osbridge.AndroidOsCapabilities
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
import dev.weft.tools.AlarmSetTool
import dev.weft.tools.AppInstalledTool
import dev.weft.tools.AppListLaunchableTool
import dev.weft.tools.AudioRecordTool
import dev.weft.tools.BatteryStatusTool
import dev.weft.tools.ColorConvertTool
import dev.weft.tools.DetectLanguageTool
import dev.weft.tools.HashTool
import dev.weft.tools.ImageCropTool
import dev.weft.tools.ImageResizeTool
import dev.weft.tools.ImageRotateTool
import dev.weft.tools.JsonQueryTool
import dev.weft.tools.MathEvalTool
import dev.weft.tools.PhoneDialTool
import dev.weft.tools.PowerKeepScreenOnTool
import dev.weft.tools.PowerSetBrightnessTool
import dev.weft.tools.RandomChoiceTool
import dev.weft.tools.RegexMatchTool
import dev.weft.tools.SettingsOpenTool
import dev.weft.tools.ShortcutListTool
import dev.weft.tools.ShortcutPushTool
import dev.weft.tools.ShortcutRemoveTool
import dev.weft.tools.SmsComposeTool
import dev.weft.tools.TelephonyInfoTool
import dev.weft.tools.TextTransformTool
import dev.weft.tools.TranslateTextTool
import dev.weft.tools.UrlParseTool
import dev.weft.tools.VolumeGetTool
import dev.weft.tools.VolumeSetTool
import dev.weft.tools.WifiInfoTool
import dev.weft.tools.BiometricAuthenticateTool
import dev.weft.tools.BluetoothDeviceBatteryTool
import dev.weft.tools.BluetoothListPairedTool
import dev.weft.tools.BluetoothOpenSettingsTool
import dev.weft.tools.CalendarCreateTool
import dev.weft.tools.CameraCaptureTool
import dev.weft.tools.CalendarDeleteTool
import dev.weft.tools.CalendarReadTool
import dev.weft.tools.CalendarUpdateTool
import dev.weft.tools.ClipboardReadTool
import dev.weft.tools.ClipboardWriteTool
import dev.weft.tools.ContactsReadTool
import dev.weft.tools.DateComputeTool
import dev.weft.tools.DisplayInfoTool
import dev.weft.tools.HapticsTool
import dev.weft.tools.LocationCurrentTool
import dev.weft.tools.LocationGeocodeTool
import dev.weft.tools.LocationReverseGeocodeTool
import dev.weft.tools.MediaListRecentTool
import dev.weft.tools.MediaPickAnyTool
import dev.weft.tools.MediaPickImageTool
import dev.weft.tools.MediaPickVideoTool
import dev.weft.tools.MediaQueryTool
import dev.weft.tools.SensorAmbientLightTool
import dev.weft.tools.SensorStepsTodayTool
import dev.weft.tools.SpeechRecognizeTool
import dev.weft.tools.SpeechSayTool
import dev.weft.tools.VisionBarcodeTool
import dev.weft.tools.VisionOcrTool
import dev.weft.tools.DataDeleteTool
import dev.weft.tools.DataQueryTool
import dev.weft.tools.DataUpsertTool
import dev.weft.tools.DeviceInfoTool
import dev.weft.tools.ExternalLaunchAppTool
import dev.weft.tools.ExternalOpenUrlTool
import dev.weft.tools.ExternalShareTool
import dev.weft.tools.FilesReadTool
import dev.weft.tools.FilesSaveTool
import dev.weft.tools.FilesShareTool
import dev.weft.tools.MapsDirectionsTool
import dev.weft.tools.NetworkFetchTool
import dev.weft.tools.NetworkStatusTool
import dev.weft.tools.NotifyShowTool
import dev.weft.tools.PdfCreateTool
import dev.weft.tools.PdfReadTool
import dev.weft.tools.PdfRenderPagesTool
import dev.weft.tools.ScheduleCancelTool
import dev.weft.tools.ScheduleCreateTool
import dev.weft.tools.ScheduleListTool
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import dev.weft.tools.FindToolTool
import dev.weft.tools.EagerToolProvider
import dev.weft.tools.SystemUserContextTool
import dev.weft.tools.ToolMetadataOverride
import dev.weft.tools.compositeToolProvider
import dev.weft.tools.UiAskTool
import dev.weft.tools.UiDialogTool
import dev.weft.tools.UiNavigateTool
import dev.weft.tools.UiNotifyTool
import dev.weft.tools.UiRenderTool
import dev.weft.tools.UiRequestPermissionTool
import dev.weft.tools.context.DeviceContextProvider
import io.ktor.client.engine.okhttp.OkHttp
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
    public val context: Context,
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
     * ships. Built by [WeftRuntime.create] from the app's context;
     * tests can pass a JDBC in-memory variant.
     */
    private val database: dev.weft.android.db.WeftDatabase =
        WeftDatabaseFactory.create(dev.weft.android.persistence.WeftPlatform(context)),
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

    private val networkClient = whitelistingHttpClient(engine = OkHttp.create(), policy = networkPolicy)

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
     * The substrate's tool catalog plus any tools supplied by the app. App
     * tools come last so the LLM sees the stable substrate prelude first
     * (helps prompt caching once Koog ships Anthropic cache_control).
     *
     * Every tool here is platform-neutral — they talk through abstract
     * contracts (`OsCapabilities`, `UiBridge`, `DataSource`, `MemoryStore`).
     * `ui_render` / `ui_notify` delegate validation + rendering to the
     * app's [UiBridge] impl, so the SDK doesn't know or care which UI
     * framework the app is using.
     */
    /**
     * The substrate's prebuilt tool list — substrate built-ins +
     * [extraToolsFactory]. Does NOT include `find_tool` (which depends
     * on [toolProvider], which depends on this list — circularity
     * broken by separating the two). [tools] is the public view that
     * appends `find_tool` when [hasOnDemandTools].
     */
    private val prebuiltTools: List<WeftTool<*, *>> = listOf<WeftTool<*, *>>(
        NotifyShowTool(toolContext),
        ScheduleCreateTool(toolContext),
        ScheduleListTool(toolContext),
        ScheduleCancelTool(toolContext),
        UiAskTool(toolContext),
        UiDialogTool(toolContext),
        UiNavigateTool(toolContext),
        UiRequestPermissionTool(toolContext),
        UiRenderTool(toolContext),
        UiNotifyTool(toolContext),
        SystemUserContextTool(toolContext, contextRegistry),
        DataQueryTool(toolContext, this.dataSources),
        DataUpsertTool(toolContext, this.dataSources),
        DataDeleteTool(toolContext, this.dataSources),
        ExternalOpenUrlTool(toolContext),
        ExternalLaunchAppTool(toolContext),
        ExternalShareTool(toolContext),
        ContactsReadTool(toolContext),
        CalendarReadTool(toolContext),
        CalendarCreateTool(toolContext),
        CalendarUpdateTool(toolContext),
        CalendarDeleteTool(toolContext),
        ClipboardReadTool(toolContext),
        ClipboardWriteTool(toolContext),
        BiometricAuthenticateTool(toolContext),
        HapticsTool(toolContext),
        VisionOcrTool(toolContext),
        VisionBarcodeTool(toolContext),
        LocationCurrentTool(toolContext),
        LocationGeocodeTool(toolContext),
        LocationReverseGeocodeTool(toolContext),
        SpeechSayTool(toolContext),
        SpeechRecognizeTool(toolContext),
        AudioRecordTool(toolContext),
        CameraCaptureTool(toolContext),
        FilesSaveTool(toolContext),
        FilesReadTool(toolContext),
        FilesShareTool(toolContext),
        NetworkFetchTool(toolContext, networkClient),
        MemoryStoreTool(toolContext, memoryStore),
        MemoryRecallTool(toolContext, memoryStore),
        MemoryCompactTool(toolContext, memoryStore),
        // Read-only device-state tools (no permissions, no I/O of note).
        BatteryStatusTool(toolContext),
        NetworkStatusTool(toolContext),
        DeviceInfoTool(toolContext),
        DisplayInfoTool(toolContext),
        // Intent-launching tools — hand off to user-installed apps.
        MapsDirectionsTool(toolContext),
        AlarmSetTool(toolContext),
        // PDF — extract / render / create. PdfBox-Android backs read+create;
        // platform PdfRenderer backs render-pages.
        PdfReadTool(toolContext),
        PdfRenderPagesTool(toolContext),
        PdfCreateTool(toolContext),
        // Bluetooth — narrow read-side surface. List paired, open settings,
        // best-effort device battery. No scan/connect (Android-locked-down).
        BluetoothListPairedTool(toolContext),
        BluetoothOpenSettingsTool(toolContext),
        BluetoothDeviceBatteryTool(toolContext),
        // Gallery — read-only MediaStore queries. Returns content:// URIs
        // the agent can hand to vision_ocr / external_share / files_read.
        // Needs READ_MEDIA_* permissions; Play scrutinizes these. For
        // "user picks the file" flows prefer the picker tools below.
        MediaListRecentTool(toolContext),
        MediaQueryTool(toolContext),
        // Photo Picker — system-mediated, NO permission. Preferred over
        // the MediaLibrary tools whenever the user is the one choosing.
        MediaPickImageTool(toolContext),
        MediaPickVideoTool(toolContext),
        MediaPickAnyTool(toolContext),
        // Installed-apps discovery — useful for routing decisions
        // (which music app is installed? which maps?).
        AppInstalledTool(toolContext),
        AppListLaunchableTool(toolContext),
        // Lightweight sensors — step counter, ambient light. Both
        // returnable as "available=false" when the device lacks them.
        SensorStepsTodayTool(toolContext),
        SensorAmbientLightTool(toolContext),
        // Pure date arithmetic — no I/O. Eliminates LLM date-math errors.
        DateComputeTool(toolContext),
        // Telephony — Intent-handoff dial / SMS compose, plus carrier
        // info read. No permission for any of these.
        PhoneDialTool(toolContext),
        SmsComposeTool(toolContext),
        TelephonyInfoTool(toolContext),
        // Wifi state read. SSID needs LOCATION on Android 9+.
        WifiInfoTool(toolContext),
        // Volume control — per-stream get/set, normalized 0..1.
        VolumeGetTool(toolContext),
        VolumeSetTool(toolContext),
        // Power — keep screen on, per-window brightness. Scoped to
        // the foreground Activity; no permission needed.
        PowerKeepScreenOnTool(toolContext),
        PowerSetBrightnessTool(toolContext),
        // Settings deep-link — one tool, many panels via enum.
        SettingsOpenTool(toolContext),
        // App shortcuts — pin / remove / list dynamic launcher shortcuts.
        ShortcutPushTool(toolContext),
        ShortcutRemoveTool(toolContext),
        ShortcutListTool(toolContext),
        // Translation + language ID via ML Kit (~30MB model per pair).
        TranslateTextTool(toolContext),
        DetectLanguageTool(toolContext),
        // Image transforms — resize / crop / rotate via Bitmap APIs.
        ImageResizeTool(toolContext),
        ImageCropTool(toolContext),
        ImageRotateTool(toolContext),
        // Pure utility tools — no OS, no permissions. Reduce LLM
        // arithmetic / string / regex mistakes by routing through code.
        MathEvalTool(toolContext),
        TextTransformTool(toolContext),
        HashTool(toolContext),
        RegexMatchTool(toolContext),
        UrlParseTool(toolContext),
        ColorConvertTool(toolContext),
        RandomChoiceTool(toolContext),
        JsonQueryTool(toolContext),
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
    public val systemPrompt: String = assembleSystemPrompt(
        appPreamble = appPromptPreamble,
        tools = tools,
        components = componentMetadata,
        // Pass the registered DataSources so the substrate can auto-list
        // them in the system prompt. Apps no longer need to hand-document
        // their data layer in the preamble — register a DataSource with
        // a description and the SDK takes it from there.
        dataSources = dataSources,
        extraNotes = systemPromptExtraNotes,
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
        val mcpHttp = whitelistingHttpClient(
            engine = OkHttp.create(),
            policy = networkPolicy,
            extraConfig = { install(ContentNegotiation) { json(HttpMcpClient.DEFAULT_JSON) } },
        )
        val mcpClient = HttpMcpClient(mcpHttp)
        return runtimeScope.async(Dispatchers.IO) {
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
    @Volatile
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
        if (mcp.isEmpty()) {
            cachedResolvedSystemPrompt = systemPrompt
            return systemPrompt
        }
        val prompt = assembleSystemPrompt(
            appPreamble = appPromptPreamble,
            tools = tools + mcp,
            components = componentMetadata,
            dataSources = rawDataSources,
            extraNotes = systemPromptExtraNotes,
        )
        cachedResolvedSystemPrompt = prompt
        return prompt
    }

    /** Substrate + extra + MCP tools, in the order they reach the agent. */
    public suspend fun resolvedTools(): List<WeftTool<*, *>> =
        tools + mcpToolsReady.await()

    /**
     * Per-agent variant of [resolvedSystemPrompt]: rebuilds the system
     * prompt against [agentTools] (the filtered catalog) instead of the
     * full one. Called only from [buildAgentForDeclaration] when an
     * agent declares a non-empty [dev.weft.harness.agents.AgentDeclaration.allowedTools],
     * so the agent's prompt describes exactly the tools it can call —
     * not the substrate-wide ~50 + N-MCP catalog.
     *
     * Stage 1 of the [docs/architecture/tool-provider.md] design.
     * Not cached across calls: builds fresh each [buildAgent] invocation
     * for whichever agent is being constructed. Acceptable because
     * [buildAgent] is the slow path (provider / model change events);
     * the turn-loop hot path uses the closure captured at build time.
     */
    private fun systemPromptFor(agentTools: List<WeftTool<*, *>>): String =
        assembleSystemPrompt(
            appPreamble = appPromptPreamble,
            tools = agentTools,
            components = componentMetadata,
            dataSources = rawDataSources,
            extraNotes = systemPromptExtraNotes,
        )

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
        return buildAgentForDeclaration(
            declaration = declaration,
            provider = provider,
            modelPoolOverride = modelPoolOverride,
            strategyOverride = strategyOverride,
        )
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun buildAgentForDeclaration(
        declaration: dev.weft.harness.agents.AgentDeclaration,
        provider: dev.weft.contracts.WeftCredentialProvider,
        modelPoolOverride: dev.weft.harness.agents.routing.ModelPool?,
        strategyOverride: dev.weft.harness.agents.strategy.WeftStrategy?,
    ): WeftAgent {
        // Pick the Koog client + model pool + cache binder for the
        // configured provider. Adding a new provider here means: import the
        // Koog `*LLMClient`, pick the [ModelPool] (cheap/standard/vision/
        // heavy), choose a `CacheBinder` (Anthropic-shaped providers get
        // [AnthropicCacheBinder]; everything else falls back to
        // [NoOpCacheBinder] until/unless Koog grows explicit cache markers
        // for that provider). Per-turn model routing happens inside
        // [WeftAgent] via [dev.weft.harness.agents.routing.DefaultModelRouter].
        val (executor, defaultPool, cacheBinder) = buildExecutorFor(provider)
        val modelPool = modelPoolOverride ?: defaultPool

        // Sub-agent runner: spawns isolated WeftAgents on demand. Shares
        // the executor / pool / cacheBinder / usageStore / quota with the
        // parent so costs aggregate and the daily quota covers the whole
        // tree; isolates everything else (tools subset, fresh history,
        // discarded trace store). The `delegate` and `delegate_parallel`
        // tools wrap this runner so the orchestrator LLM can invoke it
        // via the normal tool-call mechanism.
        // Block the first buildAgent call until MCP discovery resolves.
        // Subsequent calls see the already-completed deferred at zero
        // cost. mcpToolsReady never rejects — failed servers go through
        // onMcpError and yield empty.
        val resolvedToolsAll = resolvedTools()

        // Apply this declaration's tool allowlist. Empty allowlist =
        // every tool is included (today's default-agent behavior).
        // Non-empty = filter to whitelisted names only; MCP tools are
        // matched in their qualified ${server}:${name} form.
        val agentTools = if (declaration.allowedTools.isEmpty()) {
            resolvedToolsAll
        } else {
            resolvedToolsAll.filter { it.descriptor.name in declaration.allowedTools }
        }

        // delegate_to_agent: present in every agent's catalog when more
        // than the default agent is registered. The factory captures
        // the active provider so the delegate inherits credentials.
        // Depth is bounded by DelegateToAgentTool.MAX_DELEGATION_DEPTH
        // via DelegationContext in the coroutine context — no extra
        // tracking needed here.
        val otherAgents = agentDeclarations.values.filter { it.name != declaration.name }
        val delegateTool = if (otherAgents.isEmpty()) {
            null
        } else {
            dev.weft.harness.agents.DelegateToAgentTool(
                ctx = toolContext,
                resolveAgent = { targetName ->
                    buildAgent(
                        agentName = targetName,
                        provider = provider,
                        modelPoolOverride = modelPoolOverride,
                        strategyOverride = null,
                    )
                },
                knownAgents = otherAgents,
            )
        }
        val allTools = if (delegateTool != null) agentTools + delegateTool else agentTools

        // Bind cache markers to the catalog: the LAST tool's descriptor
        // gets `cache_control = OneHour`, which Anthropic uses as the
        // caching breakpoint for the *entire* tool-definition prefix. For
        // a substrate with 40+ tools this caches far more tokens than the
        // system message alone. NoOpCacheBinder returns the list unchanged.
        val cachedTools = cacheBinder.markedTools(allTools, dev.weft.harness.prompt.cache.CacheTier.STATIC)
        val toolRegistry = ToolRegistry { cachedTools.forEach { tool(it) } }

        // Compose the effective system prompt for this declaration:
        // substrate's resolved prompt + the agent's role fragment.
        //
        // Catalog scoping (Stage 1 of docs/architecture/tool-provider.md):
        //   - Empty allowlist (default agent) → reuse the cached full
        //     catalog from resolvedSystemPrompt(). Identical to today.
        //   - Non-empty allowlist → rebuild the prompt against agentTools
        //     so it describes only the tools this agent can call. A
        //     writer agent with two allowed tools no longer pays tokens
        //     for ~50 unreachable tool descriptions.
        //
        // Cache trade-off: per-agent prompts don't share Anthropic's
        // cache prefix with each other or with the default. For multi-
        // agent hosts the catalog savings dominate the cache loss; for
        // single-agent hosts the default branch keeps today's caching
        // bit-for-bit.
        val baseSystemPrompt = if (declaration.allowedTools.isEmpty()) {
            resolvedSystemPrompt()
        } else {
            // Use allTools (filtered + delegate_to_agent if present)
            // so the prompt-catalog matches what the wire registry
            // actually accepts.
            systemPromptFor(allTools)
        }
        val effectiveSystemPrompt = if (declaration.systemFragment.isBlank()) {
            baseSystemPrompt
        } else {
            "$baseSystemPrompt\n\n## Role\n${declaration.systemFragment}"
        }
        return WeftAgent(
            executor = executor,
            modelPool = modelPool,
            // DefaultModelRouter (rules: vision → vision-capable, coding
            // hints → heavy, short fresh chat → cheap, else standard).
            // Apps that want a single fixed model can pass
            // dev.weft.harness.agents.routing.StaticModelRouter(modelPool.standard)
            // via a future WeftRuntime opt-out.
            toolRegistry = toolRegistry,
            traceStore = traceStore,
            baseSystemPromptSupplier = { effectiveSystemPrompt },
            // Compose: device snapshot first (substrate default), then
            // the app-supplied per-turn extension. Each section
            // separated by a blank line so the LLM can tell them apart.
            // Empty extensions are dropped so the prompt doesn't grow
            // unnecessarily.
            volatilePrefixSupplier = {
                listOf(deviceSnapshot(context), extraVolatilePrefix())
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            },
            maxIterations = maxIterations,
            usageStore = usageStore,
            quotaPolicy = quotaPolicy,
            redactor = redactor,
            maxOutputTokens = maxOutputTokens,
            conversationStore = conversationStore,
            cacheBinder = cacheBinder,
            memoryRegistry = memoryRegistry,
            // Explicit override beats the declaration's own strategy
            // (which itself defaults to DefaultStrategy() per the
            // AgentDeclaration data class).
            strategy = strategyOverride ?: declaration.strategy,
            agentName = declaration.name,
            // Stage 2: pass the runtime's lazy ToolProvider so the
            // agent's strategy can resolve names from find_tool searches
            // mid-turn. Pass null when the host is in pure eager mode
            // (no on-demand tools) — saves the activation node from
            // doing work it'd skip anyway.
            toolProvider = if (hasOnDemandTools) toolProvider else null,
        )
    }

    /**
     * Build the Koog [MultiLLMPromptExecutor], provider-specific
     * [dev.weft.harness.agents.routing.ModelPool], and matching
     * [dev.weft.harness.prompt.cache.CacheBinder] for [provider]. Switch on
     * [dev.weft.contracts.ProviderKind].
     *
     * The model pool is the **closed set** that [WeftAgent]'s router
     * picks from per turn. Adding new models means: pick them here, then
     * either update [dev.weft.harness.agents.routing.DefaultModelRouter] rules
     * if you want the default router to route to them, or pass a custom
     * router that does.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun buildExecutorFor(
        provider: dev.weft.contracts.WeftCredentialProvider,
    ): Triple<MultiLLMPromptExecutor, dev.weft.harness.agents.routing.ModelPool, dev.weft.harness.prompt.cache.CacheBinder> = when (provider.kind) {
        dev.weft.contracts.ProviderKind.Anthropic -> {
            val client = AnthropicLLMClient(
                apiKey = provider.bearer(),
                settings = AnthropicClientSettings(
                    // All four pool models share the same Anthropic client.
                    // modelVersionsMap maps Koog's LLModel.id values to the
                    // wire-API names. Sonnet 4.6 is the only custom (non-Koog-
                    // catalog) entry; the rest come from AnthropicModels.
                    modelVersionsMap = mapOf(SONNET_4_6_MODEL to "claude-sonnet-4-6"),
                    baseUrl = provider.baseUrl,
                ),
                // Koog 1.0.0 made the HTTP client a pluggable runtime dep
                // discovered via ServiceLoader. Android's packaging is
                // fragile around META-INF/services entries (R8 + AGP
                // resource-merging both can strip them), so we pass the
                // factory explicitly. The arg-less constructor uses
                // sensible defaults (Ktor's default HttpClient, SSE on,
                // KotlinLogging logger).
                httpClientFactory = ai.koog.http.client.ktor.KtorKoogHttpClient.Factory(),
            )
            val pool = dev.weft.harness.agents.routing.ModelPool(
                // Haiku 4.5 for short / fresh-chat turns. ~3.7× cheaper
                // input, ~3.7× cheaper output than Sonnet.
                cheap = ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Haiku_4_5,
                // Sonnet 4.6 (our custom model entry) handles the bulk
                // of turns: good reasoning, vision, tool use.
                standard = SONNET_4_6_MODEL,
                // Sonnet supports vision; no need for a separate vision
                // model on the Anthropic side.
                vision = SONNET_4_6_MODEL,
                // Opus 4.7 for explicit coding / heavy reasoning. ~5×
                // the cost of Sonnet; only fires on the coding-keyword
                // heuristic.
                heavy = ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Opus_4_7,
            )
            Triple(
                MultiLLMPromptExecutor(client),
                pool,
                dev.weft.harness.prompt.cache.AnthropicCacheBinder,
            )
        }
        dev.weft.contracts.ProviderKind.OpenAI -> {
            val client = ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
                apiKey = provider.bearer(),
                settings = ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(
                    baseUrl = provider.baseUrl,
                ),
                httpClientFactory = ai.koog.http.client.ktor.KtorKoogHttpClient.Factory(),
            )
            val pool = dev.weft.harness.agents.routing.ModelPool(
                cheap = ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT4oMini,
                standard = ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT4o,
                vision = ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT4o,
                // GPT-4o handles coding well enough; bumping to O3 for
                // coding would be an opt-in given its latency profile.
                heavy = ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT4o,
            )
            Triple(
                MultiLLMPromptExecutor(client),
                pool,
                // OpenAI caches stable prefixes server-side, no explicit
                // markers needed. The binder is a no-op for prompt building
                // and tool marking; cache hits show up as cachedTokens in
                // prompt_tokens_details (currently not surfaced by Koog 1.0.0's
                // metaInfo for OpenAI — observability gap to track).
                dev.weft.harness.prompt.cache.NoOpCacheBinder,
            )
        }
        dev.weft.contracts.ProviderKind.OpenRouter -> {
            val client = ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient(
                apiKey = provider.bearer(),
                settings = ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings(),
                httpClientFactory = ai.koog.http.client.ktor.KtorKoogHttpClient.Factory(),
            )
            // OpenRouter's value is the breadth — mix providers per tier
            // so users see what's possible from one key. Quality picks:
            // cheap = OpenAI's smallest (consistent + fast), standard +
            // vision = Claude 4.5 Sonnet, heavy = Claude 4.5 Opus.
            // Override in app settings later if users want to pin tiers
            // to specific OpenRouter catalog entries.
            val pool = dev.weft.harness.agents.routing.ModelPool(
                cheap = ai.koog.prompt.executor.clients.openrouter.OpenRouterModels.GPT4oMini,
                standard = ai.koog.prompt.executor.clients.openrouter.OpenRouterModels.Claude4_5Sonnet,
                vision = ai.koog.prompt.executor.clients.openrouter.OpenRouterModels.Claude4_5Sonnet,
                heavy = ai.koog.prompt.executor.clients.openrouter.OpenRouterModels.Claude4_5Opus,
            )
            Triple(
                MultiLLMPromptExecutor(client),
                pool,
                // OpenRouter is a passthrough; upstream's caching shows
                // through but we don't drive markers from our side.
                dev.weft.harness.prompt.cache.NoOpCacheBinder,
            )
        }
        dev.weft.contracts.ProviderKind.DeepSeek -> {
            // DeepSeek's API is OpenAI-compatible. Koog dropped its
            // dedicated DeepSeek client at 1.0.0, so we use OpenAILLMClient
            // pointed at api.deepseek.com. Models are constructed locally
            // (DEEPSEEK_*_MODEL on this companion) and tagged with
            // LLMProvider.DeepSeek so MultiLLMPromptExecutor dispatches
            // them here — but the executor map keys this client under
            // LLMProvider.DeepSeek explicitly to satisfy that lookup
            // (OpenAILLMClient doesn't care that its key isn't OpenAI).
            val client = ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
                apiKey = provider.bearer(),
                settings = ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(
                    baseUrl = DEEPSEEK_BASE_URL,
                ),
                httpClientFactory = ai.koog.http.client.ktor.KtorKoogHttpClient.Factory(),
            )
            // Two-model catalog: Chat for general, Reasoner for heavy.
            // No vision support — image attachments will fail at the API
            // layer. Document that in app-facing copy.
            val pool = dev.weft.harness.agents.routing.ModelPool(
                cheap = DEEPSEEK_CHAT_MODEL,
                standard = DEEPSEEK_CHAT_MODEL,
                vision = DEEPSEEK_CHAT_MODEL,
                heavy = DEEPSEEK_REASONER_MODEL,
            )
            Triple(
                MultiLLMPromptExecutor(
                    mapOf(LLMProvider.DeepSeek to client),
                ),
                pool,
                dev.weft.harness.prompt.cache.NoOpCacheBinder,
            )
        }
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

        /**
         * Convenience factory that constructs the substrate with the default
         * Android [OsCapabilities]. Apps using `:substrate:android-ui` will
         * also build a `WeftUi` and thread its `components` +
         * `toolsFactory` through.
         */
        public fun create(
            context: Context,
            uiBridge: UiBridge,
            appPromptPreamble: String,
            dataSources: List<DataSource> = emptyList(),
            networkPolicy: NetworkPolicy = NetworkPolicy(coreAllowlist = emptySet()),
            extraContextProviders: List<ContextProvider> = emptyList(),
            extraToolsFactory: (WeftContext) -> List<WeftTool<*, *>> = { _ -> emptyList() },
            /**
             * Stage 2 of `docs/architecture/tool-provider.md` — optional
             * lazy tool provider. Null (default) auto-wraps the
             * substrate's prebuilt list as an [EagerToolProvider] with
             * everything `alwaysOn`, preserving today's behavior.
             * Pass a custom provider (typically
             * `compositeToolProvider(substrateProvider, mcpProvider,
             * appProvider)`) when you want `find_tool` discovery + lazy
             * MCP/app-domain tool materialization.
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
            maxIterations: Int = MAX_ITERATIONS_DEFAULT,
            /**
             * MCP servers to discover tools from. Empty list = no MCP.
             * Discovery runs in the background on [Dispatchers.IO]; the
             * first [buildAgent] call awaits its completion. Same
             * [networkPolicy] allowlist applies to MCP server hosts.
             */
            mcpServers: List<McpServerConfig> = emptyList(),
            onMcpError: (McpServerConfig, Throwable) -> Unit = { _, _ -> },
            mcpDiscoveryTimeout: Duration = DEFAULT_MCP_DISCOVERY_TIMEOUT,
            /**
             * Multi-agent registry. Empty list = auto-default single
             * agent named [dev.weft.harness.agents.AgentDeclaration.DEFAULT_AGENT_NAME]
             * (preserves pre-multi-agent behavior). Non-empty = each
             * declaration becomes addressable via
             * [WeftRuntime.buildAgent(agentName, ...)][buildAgent].
             */
            agents: List<dev.weft.harness.agents.AgentDeclaration> = emptyList(),
        ): WeftRuntime {
            val appContext = context.applicationContext
            val database = WeftDatabaseFactory.create(
                dev.weft.android.persistence.WeftPlatform(appContext),
            )
            val scheduledNotificationStore =
                dev.weft.android.persistence.SqlDelightScheduledNotificationKeyStore(database)
            return WeftRuntime(
                context = appContext,
                os = AndroidOsCapabilities.create(
                    appContext,
                    scheduledNotificationStore = scheduledNotificationStore,
                ),
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
            )
        }

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
