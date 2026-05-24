# 02 — Architecture

## The big picture

```
┌───────────────────────────────────────────────────────────────────────┐
│                          User Intent (text)                           │
└───────────────────────────────────────────────────────────────────────┘
                                  ↓
┌───────────────────────────────────────────────────────────────────────┐
│                          Harness Layer (chain)                        │
│                                                                       │
│  Observability → Reliability → Cost → Quality → Behavior → Memory →   │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
                                  ↓
┌───────────────────────────────────────────────────────────────────────┐
│                       Agent Loop (Koog-based)                         │
│   - Iterates: send → receive → execute tools → send results → ...     │
│   - Iteration budget, parallel tool execution, error recovery         │
└───────────────────────────────────────────────────────────────────────┘
       ↓ functional channel                       ↓ visual channel
┌─────────────────────────────┐         ┌─────────────────────────────┐
│   Script Catalog (verbs)    │         │  Template Catalog (nouns)   │
│   data, schedule, notify,   │         │  Timer, List, Form, Prompt, │
│   calendar, contacts, …     │         │  Chat, Dashboard, …         │
└─────────────┬───────────────┘         └─────────────┬───────────────┘
              ↓                                       ↓
┌─────────────────────────────┐         ┌─────────────────────────────┐
│   OS Bridge (per platform)  │         │   Design System             │
│   Android: Intents, etc.    │         │   Tokens, components, slots │
│   iOS: EventKit, etc.       │         │   Compose + SwiftUI         │
└─────────────────────────────┘         └─────────────────────────────┘
                                  ↓
┌───────────────────────────────────────────────────────────────────────┐
│                    LLMClient (swappable transport)                    │
│        DirectAnthropic (v1)        ManagedBackend (future)            │
└───────────────────────────────────────────────────────────────────────┘
                                  ↓
                            Anthropic API
```

## The five-layer module hierarchy

The codebase is split into layers. Higher depends on lower. Lower never knows higher exists.

```
Layer 5: APP            reference-app, examples
                                ↓
Layer 4: DOMAIN         scripts-undercurrent, design-undercurrent
                                ↓
Layer 3: HARNESS        reliability, quality, observability,
                        cost, behavior, memory, evaluation
                                ↓
Layer 2: CAPABILITIES   scripts-core, design-system, llm-anthropic,
                        os-bridge, security, compliance
                                ↓
Layer 1: CORE           core, contracts, util
```

This ordering is a property of the dependency graph, enforced by Gradle. Cycles fail the build.

See `03-modules.md` for the full module list with responsibilities.

## The three output channels

Every agent response carries up to three independent outputs:

1. **Tool calls** — side effects on the OS (schedule something, save a file, navigate).
2. **UI** — what the user sees (which screen template, what props).
3. **Text** — what the LLM says alongside.

A single response can use any combination. "Set a timer" might use all three. "What time is it?" might use only text. "Show me my habits" might use a tool call (query) plus a UI (list) plus text (a summary).

Making UI a first-class channel — not an afterthought to chat — is what separates an LLM-driven app from a chat wrapper.

## The agent loop

The agent loop is **iterative and reactive**, not plan-then-execute:

```
1. User message in.
2. LLM sees message + tool list + system prompt.
3. LLM returns either:
   a. A final response (text + optional UI) → loop ends.
   b. One or more tool calls → execute them.
4. Tool results sent back to LLM.
5. Goto 2 with extended history.
6. Cap at N iterations (default 10) to prevent runaway.
```

This is the standard Anthropic tool-use loop. Koog handles the mechanics. The substrate's contribution is wrapping it with the harness chain and connecting tool results into the script/UI catalogs.

## The harness as middleware

Each harness concern is implemented as an `LLMMiddleware` and composed into a chain:

```
ObservabilityMiddleware
  ↓
ReliabilityMiddleware (retries, timeouts, fallback model)
  ↓
CostMiddleware (token tracking, quota enforcement)
  ↓
QualityMiddleware (schema validation, structured retry)
  ↓
BehaviorMiddleware (prompt composition, context pruning)
  ↓
MemoryMiddleware (explicit memory injection)
  ↓
LLMClient (actual API call)
```

Each middleware can:
- Modify the request before passing it down.
- Modify the response before returning it up.
- Short-circuit (e.g., quota exceeded → return error without calling).
- Retry (e.g., reliability layer on 5xx).
- Record (e.g., observability writes to trace store).

This is the pattern from OkHttp interceptors and Express middleware. Mature, well-understood, easy to test in isolation.

See `07-harness.md` for each middleware's behavior in detail.

## The design system's role

The design system is the **visual API surface** for the LLM, parallel to the script catalog (the functional API surface).

Four layers:

1. **Tokens** — colors, spacing, type, motion. Not exposed to LLM.
2. **Components** — buttons, cards, fields. Not exposed to LLM.
3. **Templates** — full screen layouts (Timer, List, Form, etc.). Exposed via intent.
4. **Semantic intents** — what the LLM picks (`ShowData`, `Countdown`, `Reflect`, etc.). Maps to templates via the `IntentRouter`.

The strictness principle: **the LLM picks what to show. The LLM does not pick how it looks.** Visual coherence is enforced by the router and the components, not delegated to the LLM.

See `06-design-system.md` for the catalog.

## Cross-platform strategy

Kotlin Multiplatform is used for everything **below the UI**. Each module declares its targets:

- `common` — shared types, business logic, agent loop, script registry, harness middleware.
- `androidMain` — Android-specific implementations (Intents, ContentResolver, Keystore, etc.).
- `iosMain` — iOS-specific implementations (EventKit, Keychain, etc.).

UI is **not shared**. Compose for Android, SwiftUI for iOS, written separately, conforming to the same prop schemas defined in `common`.

This is a deliberate choice. Compose Multiplatform for iOS exists but is not production-mature for a polished consumer experience in 2026. Writing UI twice is the cost of having native feel on both platforms. We pay it knowingly.

The shared piece is the **contract**: what props does a `TimerScreen` accept, what events does it emit. Both UI implementations honor the same contract.

## Data flow: from intent to effect

Walk through a concrete example — user says "remind me to drink water every 2 hours."

```
1. User types message into chat UI.
   ↓
2. AgentRuntime.send(message)
   ↓
3. ObservabilityMiddleware starts trace.
   ↓
4. ReliabilityMiddleware wraps the call with retry policy.
   ↓
5. CostMiddleware checks daily quota, allows.
   ↓
6. QualityMiddleware prepares validation hooks.
   ↓
7. BehaviorMiddleware composes system prompt + history + tools list.
   ↓
8. MemoryMiddleware injects relevant memories (if any recalled).
   ↓
9. LLMClient (DirectAnthropic) sends request to Anthropic API.
   ↓
10. Anthropic returns tool_use: schedule.create(...)
   ↓
11. QualityMiddleware validates the tool call schema. ✓
   ↓
12. ScriptExecutor finds the registered "schedule.create" script.
   ↓
13. Permission check: notifications permission. ✓ (already granted)
   ↓
14. OS bridge (Android: AlarmManager / iOS: UNUserNotificationCenter)
    schedules the recurring notification.
   ↓
15. Script returns ScriptResult.Ok(scheduleId, ...).
   ↓
16. Result sent back to LLM as tool_result.
   ↓
17. LLM responds with final text + UI update (NavigateTo("schedules") or message only).
   ↓
18. All middleware unwinds, observability writes final trace.
   ↓
19. UI renders response in chat; user sees confirmation.
```

Every step is testable in isolation. Failure at any step has a clear recovery path (handled by reliability or surfaced as an actionable error).

## Concurrency model

- Agent loop is suspending Kotlin code (`suspend fun`).
- Tool calls are dispatched in parallel when the LLM returns multiple independent calls.
- UI events from user (button taps on a rendered template) flow back via a `Channel<AgentEvent>`.
- Audit log writes are async, non-blocking.
- Cancellation propagates: user dismisses chat → agent loop cancelled cleanly → in-flight LLM call cancelled → middleware cleanup runs.

## Failure model

Three kinds of failure, each handled differently:

1. **Transient infrastructure failure** (Anthropic 5xx, network blip). Reliability middleware retries. User may not even notice.
2. **Tool execution failure** (permission denied, OS error, invalid input). Returned to LLM as `ScriptResult.Err` with `recoverable` flag and `hint`. LLM decides whether to retry differently, ask user, or surface the error.
3. **Catastrophic failure** (quota exhausted, key invalid, app bug). Surfaced to user with clear messaging and remediation. Agent loop exits cleanly.

No failure should crash the app. No failure should leave inconsistent state. Idempotency keys on side-effect scripts ensure retries are safe.

## State management

- **Conversation state** lives in memory during a session, persisted between sessions via `core`'s conversation store.
- **App data** (habits, journal entries, etc.) lives in app-owned SQLDelight databases.
- **Memory** (agent's explicit memory) lives in a substrate-managed SQLDelight database, scoped per-app.
- **Audit log** lives in a substrate-managed SQLDelight database.
- **Secrets** (API keys) live only in Keystore/Keychain.
- **Traces** live in a substrate-managed store (size-capped, rolling).
- **Settings** (quota caps, model preferences) live in encrypted shared preferences.

Each store has clear ownership; no module reaches into another's storage directly.

## Performance budgets

Soft targets, not hard limits, but signal when something is off:

- Agent loop overhead (middleware chain): < 50ms p50, < 100ms p99.
- Script execution (excluding OS calls): < 10ms p50.
- UI template render: < 16ms (one frame).
- Trace write: < 5ms, non-blocking.
- App cold start to chat-ready: < 1.5s on a mid-range device.

Benchmarked from Phase 2.5 onward; regressions tracked.

## What's intentionally simple in v1

We're deliberately avoiding complexity in places where it might be tempting:

- **No DI framework.** Manual constructor injection. The substrate is small enough.
- **No event bus.** Direct method calls and Kotlin Flow.
- **No actor system.** Coroutines and Channels.
- **No plugin/dynamic loading.** Everything compiled in.
- **No embedded scripting engine.** Scripts are typed Kotlin code, full stop.
- **No client/server within the substrate.** The substrate is a single process.

These are all things v2/v3 might add if real need emerges. Adding them speculatively now would inflate the substrate and slow adoption.
