# 07 — Harness

The harness wraps every LLM interaction with operational guarantees. It's what turns a Claude wrapper into production-quality agent infrastructure.

## Why a harness

A raw LLM call is brittle:
- Network blips drop responses.
- Models occasionally return malformed tool calls.
- Costs accumulate silently.
- Behavior drifts across model updates.
- There's no record of what happened or why.
- Memory across sessions has to be designed deliberately.

The harness addresses each concern with a dedicated middleware. Each is independently testable, replaceable, or disable-able.

## Composition

All middleware implement `LLMMiddleware` from `contracts`. They compose into a chain (see `04-locked-interfaces.md` for the interface).

```kotlin
val chain = MiddlewareChain(
    middlewares = listOf(
        ObservabilityMiddleware(traceStore),
        ReliabilityMiddleware(retryPolicy, circuitBreaker),
        CostMiddleware(quotaPolicy, usageStore),
        QualityMiddleware(schemaValidator),
        BehaviorMiddleware(promptComposer, contextPruner),
        MemoryMiddleware(memoryStore, piiDetector),
    ),
    terminal = anthropicClient
)
```

Order matters:
- **Observability** outermost: traces everything including retries and short-circuits.
- **Reliability** next: retries call into deeper middleware multiple times.
- **Cost** before quality: don't burn validation on requests we'll block.
- **Quality** next: validate the actual outbound request and inbound response.
- **Behavior** next: composes prompt, prunes context for the specific request shape.
- **Memory** innermost: injects memories just before the LLM call.

## Tier 1 (mandatory in v1)

### `harness-reliability`

**Goal:** survive transient failures and degrade gracefully.

**Behaviors:**

- **Retry policy:** exponential backoff with jitter on 429, 5xx, network errors. Default: 3 attempts, base delay 1s, max 30s.
- **Timeout:** per-call timeout enforced via `withTimeout`. Default 60s.
- **Model fallback:** on `OVERLOAD` stop reason or repeated 529s, fall back to a cheaper/faster model (Sonnet → Haiku) if configured.
- **Circuit breaker:** after N consecutive failures within a window, open the circuit; subsequent calls fast-fail until a probe succeeds.
- **Degraded-mode signal:** when circuit is open, app can show a "Claude is currently unreachable" banner and offer offline-capable scripts only.

**Config:**

```kotlin
data class ReliabilityConfig(
    val maxRetries: Int = 3,
    val baseDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val timeout: Duration = 60.seconds,
    val fallbackModel: Model? = Model.HAIKU_4_5,
    val circuitBreaker: CircuitBreakerConfig = CircuitBreakerConfig.default()
)
```

**Testing:** mock LLM client that returns configurable failures; assert retry counts, fallback invocations, circuit state transitions.

### `harness-quality`

**Goal:** catch malformed LLM outputs and recover automatically.

**Behaviors:**

- **Tool call schema validation:** every `ToolUse` block validated against the registered script's `parameterSchema`. On mismatch, retry with structured feedback to the LLM.
- **Type coercion:** mild forgiveness — coerce `"5"` → `5` for `int` params, etc., with a logged warning. No silent semantic changes.
- **Structured retry:** when validation fails, append a synthetic `ToolResult` with `is_error: true` and a precise error message ("parameter 'duration' must be an integer, got string"). Re-call LLM. Max 2 retries.
- **Truncated response handling:** if `MAX_TOKENS` reached mid-tool-call, retry with higher token budget if available, else error.

**Config:**

```kotlin
data class QualityConfig(
    val maxValidationRetries: Int = 2,
    val coerceTypes: Boolean = true,
    val coerceWarningThreshold: Int = 3  // warn user if >3 coercions in a conversation
)
```

### `harness-observability`

**Goal:** know what happened. Locally only in v1; no telemetry.

**Behaviors:**

- **Trace recording:** every LLM call writes a structured `AgentTrace` with request, response, retries, latency, tokens, cost.
- **Tool call traces:** nested under the LLM trace; record params (redacted by configurable rules), result, duration.
- **Feedback collection:** user can mark any agent turn as good (👍) or bad (👎) with optional note.
- **Debug viewer:** an in-app screen (available in debug builds and behind a settings toggle in release) showing recent traces.
- **Trace export:** JSON export for sharing/debugging.

**Storage:** SQLDelight, size-capped (default 100MB, rolling oldest-first), encrypted at rest.

**Privacy:**
- No telemetry off-device by default.
- Optional opt-in for anonymized aggregate metrics in v1.1+.
- Users can wipe trace history with one tap.

### `harness-behavior`

**Goal:** consistent prompt composition, context management, and per-turn cost optimization (compaction + provider prompt caching).

Post-ADR-006 this is a Koog feature, not an `LLMMiddleware`. It runs per turn inside `SubstrateAgent.send()` (or its successor), composing the outbound message list before Koog's executor sends it.

**Behaviors:**

- **System prompt composition** — assembled from layers (outermost first): substrate base + app system prompt + current screen's `AgentContext.systemPromptAddendum` + dynamic tool descriptions. Volatile bits (the device snapshot — time, timezone, locale) live in the latest user message, NOT the system prompt, so the system layer stays cacheable.
- **Conversation compaction** — when the history grows past a configured threshold (turn count or input-token estimate), reduce it before sending. Strategy is pluggable.
- **Provider prompt caching** — for providers that support it (Anthropic at minimum), mark stable prefixes with `cache_control` so subsequent turns hit the server-side cache and bill at ~10% of the input rate.
- **Tool list filtering** — if a screen has `allowedTools`, restrict the visible tool list for that turn.

#### The cache-layer model

Every outbound message list is assembled top-to-bottom in increasing volatility. Cache breakpoints sit between layers. Each layer either always hits the cache (after the first request) or always misses (the volatile tail):

| Layer | Stability | Cache breakpoint | Owned by |
|---|---|---|---|
| 1. Substrate base system prompt | Forever | ✓ (breakpoint #1) | substrate |
| 2. App system prompt + persona | App session | grouped with #1 | app |
| 3. Tool definitions | Session | grouped with #1 | substrate registry |
| 4. User profile + style preferences | User session (invalidate on settings change) | ✓ (breakpoint #2) | app |
| 5. Stable reference data (e.g. registered data-source names, journal categories) | Until schema change | grouped with #2 | app |
| 6. Conversation history (compacted) | Grows turn-over-turn; each prior turn becomes cacheable starting at the next turn | ✓ (breakpoint #3) | session |
| 7. Volatile tail (device snapshot, latest user message) | Every turn | — (never cache) | session + harness |

Anthropic permits up to 4 cache markers per request; we use 3 and reserve 1 for future expansion.

#### Compaction strategies

```kotlin
sealed class CompactionStrategy {
    object None : CompactionStrategy()
    /** Drop oldest user/assistant pairs until under the threshold. Cheapest; loses early context. */
    object DropOldest : CompactionStrategy()
    /** Replace dropped turns with a single summary message produced by a cheap model. */
    data class Summarize(val recapModel: Model) : CompactionStrategy()
    /** Sliding window: keep only the last N turns verbatim. */
    data class SlidingWindow(val keepLastN: Int) : CompactionStrategy()
}
```

Compaction triggers on whichever fires first: turn count threshold, estimated-input-token threshold, or `pruneAtTokenFraction` of the model's context window.

**Config:**

```kotlin
data class BehaviorConfig(
    val substrateBasePrompt: String,

    // --- Composition ---
    val deviceSnapshotInUserMessage: Boolean = true,    // false would put it back in system (breaks caching)

    // --- Compaction ---
    val compactionStrategy: CompactionStrategy = CompactionStrategy.DropOldest,
    val compactionTriggerTurns: Int = 30,
    val compactionTriggerInputTokens: Int = 50_000,
    val pruneAtTokenFraction: Double = 0.8,             // hard cap at 80% of context window
    val keepLastNTurnsVerbatim: Int = 10,               // never compact more recent than this

    // --- Provider prompt caching ---
    val cacheEnabled: Boolean = true,
    val cacheSystemAndTools: Boolean = true,            // breakpoint #1
    val cacheProfileAndReference: Boolean = true,       // breakpoint #2
    val cacheHistory: Boolean = true,                   // breakpoint #3
    val cacheMinTokens: Int = 1024,                     // Anthropic's minimum cacheable block
    val cacheTtl: CacheTtl = CacheTtl.FiveMinutes,      // or .OneHour with extended-caching beta
)

enum class CacheTtl { FiveMinutes, OneHour }
```

**Cost note:** Anthropic's cache writes cost 1.25× standard input; cache reads cost 0.1×. Break-even after one cache hit. A 20-turn conversation with a 2000-token system prompt and ~500 tokens per turn sees roughly **7× input-cost reduction** under this scheme.

## Tier 2 (in v1; memory is firm, cost is droppable under slip)

Both ship in v1. If the schedule slips badly, `harness-cost` is the first thing to defer (see `09-roadmap.md` "If you slip"); `harness-memory` is firm because it shapes the reference app's UX.

### `harness-cost`

**Goal:** users see and control AI spending.

**Behaviors:**

- **Token + cost tracking:** every response's `usage` recorded, multiplied by model price, accumulated per-conversation, per-day, per-month.
- **Quota policy:** pluggable. Default: warn at 80% of daily cap, block at 100%. Block returns a clear error message with reset time.
- **Model selection by complexity:** optional auto-downgrade. Simple queries → Haiku, complex multi-tool reasoning → Sonnet. Off by default; opt-in for cost-sensitive users.
- **Cost UI:** per-message cost badge (toggle in settings), running daily total in chat header, full breakdown in settings.

**Config:**

```kotlin
data class CostConfig(
    val dailyCapUsd: Double? = null,        // null = no cap
    val monthlyCapUsd: Double? = null,
    val warningThresholdFraction: Double = 0.8,
    val autoDowngrade: Boolean = false,
    val downgradeFrom: Model = Model.SONNET_4_5,
    val downgradeTo: Model = Model.HAIKU_4_5,
    val showPerMessageCost: Boolean = false
)
```

**Pricing:** model prices live in a config that ships with the app and is updateable. When Anthropic changes prices, a substrate update reflects them.

### `harness-memory` (explicit only)

**Goal:** the LLM can save what's worth remembering; user sees everything; user controls everything.

**Behaviors:**

- **Storage scripts:** `memory.store` and `memory.recall` (see `05-script-catalog.md`).
- **Storage backend:** SQLDelight with SQLite FTS5 for lexical search. No embeddings in v1.
- **Scoping:** memories scoped per-app. No cross-app reads.
- **PII detection:** content matched against patterns (SSN, credit card, etc.) before storage; on match, suspend and ask user to confirm.
- **Provenance on recall:** recalled memories returned to LLM as `[Memory stored on 2026-04-12, scope: permanent, tags: preferences]: <content>` so the LLM treats them as memories rather than current input.
- **User-visible viewer:** "Agent Memories" screen lists all memories with tags, dates, scope. Delete individual or wipe all.
- **Export:** JSON export of all memories.
- **No automatic memory:** the agent never stores memories silently. Every storage is an explicit `memory.store` call. (Automatic memory deferred to v1.1.)

**Config:**

```kotlin
data class MemoryConfig(
    val maxPermanentMemories: Int = 1000,
    val piiPatterns: List<Regex> = defaultPiiPatterns(),
    val recallDefaultLimit: Int = 5,
    val recallMaxLimit: Int = 50,
    val warnOnSilentStoreAttempt: Boolean = true  // log attempts where LLM seems to want to store implicitly
)
```

**Privacy guarantees:**
- Memories never sent in telemetry.
- Memories not included in trace exports unless user explicitly opts in.
- Memories deleted from store within 30 days of user marking deletion (longer retention possible only if the OS journaling doesn't clear immediately — documented).

## Tier 3 (deferred to v1.1; stub in v1)

### `harness-evaluation`

**Goal in v1:** minimal regression harness. Catches "did upgrading to a new model break everything."

**Behaviors in v1:**

- `IntentTest` data class: description, user message, expected outcome (tool called, UI shown, refusal, text contains).
- `EvalRunner` runs a list of `IntentTest` against the configured `AgentRuntime` (with a real or mock `LLMClient`), reports pass/fail.
- Sample tests for the reference app — 10–20 hand-written intents.
- CI integration as optional job (costs real API calls when run against real client).

**Deferred to v1.1:**

- Large intent corpus (100+).
- Multi-turn intent evaluation.
- Quality scoring (semantic match, not just exact match).
- A/B prompt testing.
- Performance regression tracking.
- Dashboards.

## Safety harness (deferred to v1.1 entirely)

A real safety harness requires:
- Input classification (is this user input violating policy?)
- Output classification (is the LLM's response problematic?)
- Tool-call policy enforcement (is this script call allowed in this context?)
- Refusal UX (how do we tell the user we won't do this?)

These are all real engineering and they need usage data to design well.

What v1 ships in lieu of a real safety harness:
- Anthropic's built-in safety (Claude refuses many things on its own).
- Destructive-action confirmation (via `ui.ask` for any script marked `destructive`).
- Network allowlist (no surprise data exfiltration).
- Audit log (post-hoc accountability).
- AI content labeling (compliance requirement; users know what's AI).
- Reporting affordance (users flag problems for the app developer to review).

v1.1 will add a real `harness-safety` middleware with pluggable classifiers and policies.

## Model-agnostic abstraction (deferred to v1.1)

v1 ships only `DirectAnthropicClient`. The `LLMClient` interface is designed to be model-agnostic, but the substrate is not generalized in v1.

What v1.1 adds:
- `llm-openai` module.
- `llm-ollama` module (for on-device).
- Tool format translation layer (OpenAI tool format ↔ Anthropic ↔ Ollama).
- Capability negotiation (some clients don't support parallel tool use).

What v1 does to keep the door open:
- `LLMClient` interface has no Anthropic-specific types.
- Tool descriptors are model-agnostic.
- Token counts, usage, costs use a model-agnostic shape.

## Middleware testing strategy

Each middleware tested in isolation:

1. **Unit tests:** middleware composed with a mock chain that returns canned responses. Verify behavior (retries, validation, recording).
2. **Integration tests:** middleware chain composed with `MockLLMClient` configured to simulate failures. End-to-end through `AgentRuntime`.
3. **Stress tests:** rapid-fire requests, simulated rate limits, network jitter. Verify circuit breaker behavior and resource cleanup.

## Performance impact budget

Middleware adds latency. Budget:

| Layer | p50 overhead | p99 overhead |
|---|---|---|
| Observability | < 5ms | < 20ms (write contention) |
| Reliability | 0ms (no failure path) | varies (backoff dominates on failures) |
| Cost | < 2ms | < 5ms |
| Quality | < 5ms (validation) | < 20ms (retry adds full call) |
| Behavior | < 10ms (prompt composition) | < 50ms (pruning) |
| Memory | < 10ms (FTS5 query) | < 100ms (large stores) |
| **Total chain overhead** | **< 50ms** | **< 200ms (no retries/recalls)** |

Benchmarked from Phase 2.5 and regression-tracked.

## Estimated implementation cost (harness)

| Module | Effort |
|---|---|
| `harness-reliability` | 1.5 weeks |
| `harness-quality` | 1 week |
| `harness-observability` | 1.5 weeks (incl. viewer UI on both platforms) |
| `harness-behavior` | 1 week |
| `harness-cost` | 1.5 weeks |
| `harness-memory` | 2.5 weeks (incl. viewer UI + PII detection) |
| `harness-evaluation` (stub) | 0.5 week |
| **Total** | **~9.5 weeks** |

Mostly serial in solo work; some parallelism possible between observability and cost.
