# 04 — Locked Interfaces

These five interfaces are the foundation. Get them right in Phase 1; the rest of the substrate moves fast independently. Get them wrong and every module pays the cost.

## Why "locked"

These five appear in `contracts` and are imported by every other module. Changing them is a coordinated migration. They should be designed deliberately, reviewed by everyone who'll consume them, and locked before downstream work begins.

| # | Interface | Module | Consumers |
|---|---|---|---|
| 1 | `LLMClient` | `contracts` | `llm-anthropic`, `core`, every harness middleware |
| 2 | `LLMMiddleware` | `contracts` | `core`, every `harness-*` module |
| 3 | `Script` + `ScriptResult` | `contracts` | `scripts-core`, every script (substrate + app) |
| 4 | `UIUpdate` + `ScreenSpec` | `contracts` | `design-system`, `core`, every UI consumer |
| 5 | `SemanticIntent` + `IntentRouter` | `design-system-api` | every template, the LLM prompt engineering |

## 1. LLMClient

The transport seam. Implementations: `DirectAnthropicClient` (v1), `ManagedBackendClient` (future commercial), `MockLLMClient` (testing).

```kotlin
package mas.contracts

interface LLMClient {
    /**
     * Send a chat request to the underlying LLM and return its response.
     * Implementations handle authentication, transport, and parsing.
     * They do NOT implement retries, validation, or cost tracking —
     * those are the middleware's job.
     */
    suspend fun chat(request: LLMRequest): LLMResponse

    /**
     * Validate that this client is configured correctly (key valid, network reachable).
     * Called on first run and periodically.
     */
    suspend fun validate(): ValidationResult

    /**
     * The client's capabilities. Used by middleware to know what's possible.
     */
    val capabilities: LLMCapabilities
}

data class LLMRequest(
    val model: Model,
    val systemPrompt: String,
    val messages: List<Message>,
    val tools: List<ToolDescriptor>,
    val temperature: Double = 1.0,
    val maxTokens: Int = 4096,
    val metadata: Map<String, String> = emptyMap()  // for tracing/billing
)

data class LLMResponse(
    val content: List<ContentBlock>,
    val stopReason: StopReason,
    val usage: TokenUsage,
    val model: Model
)

sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : ContentBlock()
}

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, STOP_SEQUENCE, OVERLOAD, OTHER }

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int = 0,
    val cacheCreationTokens: Int = 0
)

data class LLMCapabilities(
    val supportsTools: Boolean,
    val supportsParallelToolUse: Boolean,
    val supportsStreaming: Boolean,
    val supportsCaching: Boolean,
    val maxContextTokens: Int,
    val availableModels: List<Model>
)

sealed class ValidationResult {
    object Ok : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
    data class Unreachable(val cause: Throwable) : ValidationResult()
}
```

Notes:
- `Model` is an enum or sealed type (`SONNET_4_5`, `HAIKU_4_5`, etc.) defined in `contracts` and updated when new models release.
- No streaming in v1 (`supportsStreaming: false` on `DirectAnthropicClient`).
- The `metadata` field lets middleware pass tracing info downstream without polluting the request body.

## 2. LLMMiddleware

The harness composition unit. Pattern adapted from OkHttp interceptors.

```kotlin
package mas.contracts

interface LLMMiddleware {
    /**
     * Wrap an LLM call with cross-cutting behavior.
     * Implementations MAY:
     *   - modify the request before passing to chain.proceed(...)
     *   - modify the response before returning it
     *   - short-circuit by returning without calling chain.proceed()
     *   - retry by calling chain.proceed() multiple times
     *   - throw to propagate fatal errors
     */
    suspend fun intercept(request: LLMRequest, chain: Chain): LLMResponse

    /**
     * Identifier for tracing and configuration.
     */
    val name: String

    interface Chain {
        suspend fun proceed(request: LLMRequest): LLMResponse
        val request: LLMRequest
        val callDepth: Int  // how many times proceed() has been called in this chain
    }
}

/**
 * Composes middleware into an executable chain ending in an LLMClient call.
 */
class MiddlewareChain(
    private val middlewares: List<LLMMiddleware>,
    private val terminal: LLMClient
) {
    suspend fun execute(request: LLMRequest): LLMResponse = invokeAt(0, request)

    private suspend fun invokeAt(index: Int, request: LLMRequest): LLMResponse {
        if (index == middlewares.size) {
            return terminal.chat(request)
        }
        val chain = object : LLMMiddleware.Chain {
            override val request = request
            override val callDepth = 0  // tracked per-middleware in real impl
            override suspend fun proceed(req: LLMRequest) = invokeAt(index + 1, req)
        }
        return middlewares[index].intercept(request, chain)
    }
}
```

Composition order (outermost first, so first listed wraps everything below):

```kotlin
val chain = MiddlewareChain(
    middlewares = listOf(
        observabilityMiddleware,
        reliabilityMiddleware,
        costMiddleware,
        qualityMiddleware,
        behaviorMiddleware,
        memoryMiddleware,
    ),
    terminal = anthropicClient
)
```

## 3. Script + ScriptResult

Every script the LLM can call.

```kotlin
package mas.contracts

interface Script {
    /**
     * Stable identifier. Namespaced: "schedule.create", "data.query", etc.
     * Used in LLM tool descriptors and audit logs.
     */
    val name: String

    /**
     * Human-readable description shown to the LLM.
     * Should explain when to use, when NOT to use, and link to alternatives.
     */
    val description: String

    /**
     * JSON Schema for parameters. Used for LLM tool descriptor + validation.
     */
    val parameterSchema: JsonObject

    /**
     * Permissions required to execute this script. Checked before invocation.
     */
    val requiredPermissions: Set<Permission>

    /**
     * If true, this script makes a destructive change that should require user confirmation
     * via ui.ask before execution, even if the LLM "decided" to call it.
     */
    val destructive: Boolean

    /**
     * If true, this script may have side effects. Idempotency keys are required.
     */
    val sideEffecting: Boolean

    /**
     * Execute the script with validated parameters.
     */
    suspend fun execute(
        params: JsonObject,
        context: ScriptContext
    ): ScriptResult
}

sealed class ScriptResult {
    data class Ok(
        val data: JsonElement,
        val meta: ResultMeta? = null
    ) : ScriptResult()

    data class Pending(
        val handle: String,
        val estimate: Duration? = null,
        val pollScript: String? = null
    ) : ScriptResult()

    data class Err(
        val code: ErrorCode,
        val message: String,
        val recoverable: Boolean,
        val hint: String? = null   // for the LLM, e.g. "call ui.requestPermission('contacts') and retry"
    ) : ScriptResult()
}

enum class ErrorCode {
    NOT_FOUND,
    INVALID_INPUT,
    PERMISSION_DENIED,
    USER_CANCELLED,
    QUOTA_EXCEEDED,
    NETWORK_ERROR,
    OS_ERROR,
    NOT_IMPLEMENTED,
    INTERNAL_ERROR
}

data class ScriptContext(
    val conversationId: String,
    val turnId: String,
    val invocationId: String,
    val os: OsCapabilities,
    val storage: ScriptStorage,
    val logger: Logger,
    val confirmedByUser: Boolean = false  // set true after ui.ask returned positively
)

data class ResultMeta(
    val durationMs: Long,
    val tags: Map<String, String> = emptyMap()
)

/**
 * Namespaced key-value storage scoped to a single script. The substrate
 * provides each Script its own ScriptStorage instance keyed by script name,
 * isolated from every other script.
 *
 * Used for idempotency keys, polling cursors, retry bookkeeping — small
 * per-script state. NOT for app data (that goes through `data.*` scripts
 * and the app's own database).
 */
interface ScriptStorage {
    suspend fun get(key: String): JsonElement?
    suspend fun put(key: String, value: JsonElement, ttl: Duration? = null)
    suspend fun remove(key: String)
    suspend fun list(prefix: String = ""): List<String>
}

/**
 * Provider-agnostic description of a tool, sent to the LLM as part of an
 * LLMRequest and validated against `Script.parameterSchema` on the way back.
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,        // JSON Schema for parameters
    val cacheable: Boolean = true        // hint to providers that cache prompts
)

/**
 * Tiny structured-logging interface. Defined in `util` and re-exported here
 * for visibility. Implementations write to platform logs and (where wired)
 * to the trace store via the observability middleware.
 */
interface Logger {
    fun debug(msg: String, fields: Map<String, Any?> = emptyMap())
    fun info(msg: String, fields: Map<String, Any?> = emptyMap())
    fun warn(msg: String, fields: Map<String, Any?> = emptyMap())
    fun error(msg: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null)
}
```

Notes:
- Scripts are stateless. Per-call state goes in `ScriptContext`.
- `ScriptStorage` gives each script its own namespaced KV store (idempotency keys, polling cursors, etc.).
- `OsCapabilities` lives in `contracts` (not `os-bridge`) so this context can carry it without inverting the layer order — see `03-modules.md`.
- The `destructive` flag is enforced by `ScriptExecutor`, not by the script itself — that way an LLM bug can't bypass it.

## 4. UIUpdate + ScreenSpec

The visual output channel.

```kotlin
package mas.contracts

/**
 * What the agent wants to do to the UI on this turn, if anything.
 */
sealed class UIUpdate {
    /** Navigate to a new screen (push). */
    data class Navigate(val screen: ScreenSpec) : UIUpdate()

    /** Replace the current screen entirely. */
    data class Replace(val screen: ScreenSpec) : UIUpdate()

    /** Update specific props on the current screen without navigating. */
    data class Patch(val patches: List<PropPatch>) : UIUpdate()

    /** Dismiss the current screen (pop or specific destination). */
    data class Dismiss(val to: String? = null) : UIUpdate()

    /** Show an ephemeral overlay (dialog, sheet, toast). */
    data class Overlay(val overlay: OverlaySpec) : UIUpdate()

    object None : UIUpdate()
}

/**
 * A screen the LLM has chosen to render, identified by template name with props.
 */
data class ScreenSpec(
    /** Template id, e.g. "Timer", "List", "Form". Must exist in the template registry. */
    val template: String,

    /** Template-specific props, validated against the template's prop schema. */
    val props: JsonObject,

    /**
     * If non-null, declares that this screen should be agent-aware:
     * events from the screen (button presses, text input) flow back to the agent
     * under this context tag.
     */
    val agentContext: AgentContext? = null
)

data class AgentContext(
    val contextId: String,
    val systemPromptAddendum: String? = null,
    val allowedTools: Set<String>? = null  // restrict which scripts agent can call from this screen
)

data class PropPatch(
    val path: String,    // JSON pointer
    val value: JsonElement
)

data class OverlaySpec(
    val kind: OverlayKind,
    val title: String,
    val body: String? = null,
    val actions: List<OverlayAction> = emptyList()
)

enum class OverlayKind { DIALOG, SHEET, TOAST, BANNER }

data class OverlayAction(
    val label: String,
    val tool: String? = null,         // optional: tap → call this tool
    val params: JsonObject? = null,
    val isPrimary: Boolean = false,
    val isDestructive: Boolean = false
)
```

Notes:
- `Patch` lets the LLM update an existing screen without re-rendering it (timer countdown, list refresh).
- `AgentContext` is how a screen stays connected to the agent — see `06-design-system.md` for the screen-event-back-to-agent protocol.
- Templates and their prop schemas are defined in `design-system-api` (next interface).

## 5. SemanticIntent + IntentRouter

How the LLM picks UI without picking visual details.

```kotlin
package mas.designsystem

/**
 * What the LLM emits to request UI. The router maps each intent to a
 * UIUpdate — usually `Navigate(ScreenSpec)`, but `Confirm` becomes
 * `Overlay(OverlaySpec)` and `Message` becomes `None`.
 *
 * The LLM reasons in semantic terms (show data, capture input, countdown)
 * rather than visual ones (which template to use, what colors).
 */
sealed class SemanticIntent {
    data class ShowData(
        val title: String,
        val items: List<DataItem>,
        val density: Density = Density.COMFORTABLE,
        val emphasis: Emphasis = Emphasis.DEFAULT,
        val emptyState: EmptyState? = null,
        val actions: List<IntentAction> = emptyList()
    ) : SemanticIntent()

    data class CaptureInput(
        val title: String,
        val prompt: String,
        val fields: List<InputField>,
        val submitAction: IntentAction,
        val emphasis: Emphasis = Emphasis.DEFAULT
    ) : SemanticIntent()

    data class Countdown(
        val label: String,
        val durationSeconds: Int,
        val style: CountdownStyle = CountdownStyle.CIRCULAR,
        val onComplete: IntentAction? = null,
        val controls: List<IntentAction> = emptyList()
    ) : SemanticIntent()

    data class Confirm(
        val title: String,
        val body: String? = null,
        val confirmLabel: String,
        val cancelLabel: String = "Cancel",
        val destructive: Boolean = false,
        val confirmAction: IntentAction
    ) : SemanticIntent()

    data class Reflect(
        val prompt: String,
        val placeholder: String? = null,
        val submitAction: IntentAction
    ) : SemanticIntent()

    data class Compare(
        val title: String,
        val series: List<DataSeries>,
        val kind: CompareKind = CompareKind.AUTO
    ) : SemanticIntent()

    data class Message(
        val text: String,
        val emphasis: Emphasis = Emphasis.DEFAULT
    ) : SemanticIntent()
}

enum class Density { COMPACT, COMFORTABLE, SPACIOUS }
enum class Emphasis { SUBTLE, DEFAULT, PROMINENT, CELEBRATORY, SOMBER }
enum class CountdownStyle { CIRCULAR, LINEAR, MINIMAL }
enum class CompareKind { AUTO, BAR, LINE, PIE }

data class DataItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val tags: List<String> = emptyList(),
    val tapAction: IntentAction? = null
)

data class InputField(
    val id: String,
    val label: String,
    val kind: InputKind,
    val placeholder: String? = null,
    val initialValue: String? = null,
    val required: Boolean = true,
    val helper: String? = null
)

enum class InputKind { TEXT, MULTILINE, NUMBER, EMAIL, DATE, TIME, CHOICE }

data class EmptyState(val title: String, val body: String? = null, val action: IntentAction? = null)
data class DataSeries(val name: String, val points: List<Point>)
data class Point(val x: String, val y: Double)

data class IntentAction(
    val label: String,
    val tool: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val isPrimary: Boolean = false,
    val isDestructive: Boolean = false
)

/**
 * Maps semantic intents to UI updates. Lives in your code, not in prompts —
 * change here to evolve the visual layer without retraining.
 *
 * The router returns `UIUpdate` rather than `ScreenSpec` directly so that
 * intents like `Confirm` (which is an overlay) and `Message` (which is text
 * only, no screen change) can be expressed without forcing them through
 * the template catalog.
 */
interface IntentRouter {
    fun route(intent: SemanticIntent, context: RenderContext): UIUpdate
}

data class RenderContext(
    val deviceClass: DeviceClass,
    val isDarkMode: Boolean,
    val hasNetwork: Boolean,
    val a11yPreferences: A11yPreferences
)

enum class DeviceClass { PHONE, TABLET, FOLDABLE }
```

Notes on the strictness principle:
- The LLM picks `SemanticIntent`. The router decides the `UIUpdate` — including which template (`ScreenSpec`), whether it's an overlay, or whether to update the UI at all.
- The LLM can express *feeling* via `Emphasis` (subtle, prominent, celebratory, somber) but never raw colors or sizes.
- Templates implement their own rendering of emphasis variants, consistent with the design system.

## Phase 1 deliverables for these interfaces

Before Phase 2 can really start, the following should ship:

1. All five interfaces in `contracts` (or `design-system-api`), with kdoc.
2. Serialization (kotlinx.serialization) on all data classes.
3. `MockLLMClient`, `MockScript`, `MockMiddleware` for testing.
4. Unit tests demonstrating composition (a mock middleware chain wrapping a mock client).
5. ADRs (`docs/adr/`) capturing key decisions, especially:
   - Why streaming is deferred (ADR-001).
   - Why memory is explicit-only (ADR-002).
   - Why we picked Compose+SwiftUI over Compose Multiplatform (ADR-003).
   - Why middleware over a single `AgentRunner` extension point (ADR-004).
   - Why `SemanticIntent` lives in `design-system-api` and not `contracts` (ADR-005).

After this lands, every downstream module has a stable target to implement against.
