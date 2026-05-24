# 03 — Modules

## Design principles

The module graph satisfies four constraints, in priority order:

1. **One module = one concern.** A new contributor reads the module name and knows what's inside.
2. **Dependencies form a DAG.** No cycles, ever. Enforced by Gradle.
3. **Lower modules know nothing about higher modules.** `core` doesn't know `scripts` exists.
4. **Each module is independently testable.** No "you need the whole app to test this."

Plus two soft rules:
- Module owner can ship a PR without blocking another module's owner.
- A platform-specific change touches at most one module's `androidMain` or `iosMain`.

## Layered overview

```
Layer 5: APP                  reference-app, examples/*
                                         ↓
Layer 4: DOMAIN              scripts-undercurrent, design-undercurrent
                                         ↓
Layer 3: HARNESS             reliability, quality, observability,
                             cost, behavior, memory, evaluation
                                         ↓
Layer 2: CAPABILITIES        scripts-core, design-system, llm-anthropic,
                             os-bridge, security, compliance
                                         ↓
Layer 1: CORE                core, contracts, util
```

## Module catalog

Each module entry: **purpose**, **public API**, **dependencies**, **platform targets**, **owner notes**.

---

### Layer 1: Core

#### `util`
- **Purpose:** Small shared utilities. JSON helpers, time, ID generation, result types.
- **Public API:** A handful of helper functions, `Result`, `Either`, time helpers.
- **Depends on:** nothing.
- **Platforms:** common.
- **Owner notes:** Keep it tiny. > 500 lines means something's wrong.

#### `contracts`
- **Purpose:** Pure type definitions. No logic. Shared vocabulary for every other module.
- **Public API:** `Message`, `Tool`, `ToolDescriptor`, `ToolCall`, `ToolResult`, `Script`, `ScriptResult`, `ScriptContext`, `ScriptStorage`, `AgentRequest`, `AgentResponse`, `UIUpdate`, `ScreenSpec`, `OverlaySpec`, `LLMClient` (interface), `LLMMiddleware` (interface), `OsCapabilities` and its sub-interfaces (`Notifications`, `Calendar`, `Contacts`, `Files`, `Sharing`, `Intents`, `KeyVault`, `UserContext`), `Permission`, `ErrorCode`, `Logger`, all serializers.
- **Depends on:** `util`.
- **Platforms:** common.
- **Owner notes:** Zero behavior. Readable in 15 minutes. Any change is breaking by nature; coordinate with downstream owners. `OsCapabilities` lives here (not `os-bridge`) so `ScriptContext` can reference it without inverting the layer order; concrete platform implementations live in `os-bridge`.

#### `core`
- **Purpose:** The agent loop. The thing that takes a user message, runs it through the middleware chain, asks the LLM, executes tools, returns a response.
- **Public API:** `AgentRuntime`, `Conversation`, `ConversationStore` (interface).
- **Depends on:** `contracts`, `util`.
- **Platforms:** common.
- **Owner notes:** This is the heart. Changes ripple. Strong test coverage required.

---

### Layer 2: Capabilities

#### `scripts-core`
- **Purpose:** Script registry, executor, parameter validation. The ~22 substrate scripts as interfaces; common (non-OS) implementations.
- **Public API:** `ScriptRegistry`, `ScriptExecutor`, `@Script` DSL, the script interfaces (data, schedule, notify, calendar, contacts, files, ui, external, network, memory, system).
- **Depends on:** `contracts`, `util`, `os-bridge`.
- **Platforms:** common, delegates to `os-bridge` for platform.
- **Owner notes:** Adding a new substrate script = PR here + corresponding platform impls in `os-bridge`. Two contributors can split: script interface here, platform impls there.

#### `design-system`
- **Purpose:** Tokens, components, templates, semantic intents. The visual vocabulary.
- **Sub-modules:**
  - `design-system-api` — token definitions, template registry, `SemanticIntent`, `IntentRouter`. Common Kotlin.
  - `design-system-compose` — Compose UI implementations of components + templates. androidMain.
  - `design-system-swiftui` — SwiftUI implementations. Exported as Swift package.
- **Public API:** `Tokens`, `Templates`, `IntentRouter`; per-platform UI sets.
- **Depends on:** `contracts`, `util`.
- **Platforms:** see sub-modules.
- **Owner notes:** Designer + UI dev pair. Compose and SwiftUI work parallel after API is locked.

#### `llm-anthropic`
- **Purpose:** Concrete `LLMClient` implementation that calls Anthropic via Koog.
- **Public API:** `AnthropicClient(apiKey, options)`.
- **Depends on:** `contracts`, `util`, Koog, Ktor.
- **Platforms:** common.
- **Owner notes:** Self-contained. A future `llm-openai` would live alongside this with no changes elsewhere.

#### `os-bridge`
- **Purpose:** Platform-specific implementations of the OS capability interfaces. The interfaces themselves live in `contracts` so that `ScriptContext` (Layer 1) can carry them without a layer-up dependency; this module provides the concrete platform code.
- **Public API:** Concrete `expect`/`actual` implementations for `OsCapabilities` and its sub-interfaces (`Notifications`, `Calendar`, `Contacts`, `Files`, `Sharing`, `Intents`, `KeyVault`, `UserContext`). A `defaultOsCapabilities()` factory per platform.
- **Depends on:** `contracts`, `util`.
- **Platforms:** common entrypoint + androidMain + iosMain via `expect`/`actual`.
- **Owner notes:** Highest platform-specific complexity. Splits naturally by capability. The interface contracts live in `contracts`; if you need to add a new capability, change `contracts` first, then add impls here.

  Sub-structure for parallel work:
  ```
  os-bridge/
    api/                  ← shared interfaces (common)
    android/
      notifications/      ← Owner A
      calendar/           ← Owner B
      contacts/           ← Owner B
      files/              ← Owner A
      intents/            ← Owner A
      keyvault/           ← Owner A (Keystore)
    ios/
      notifications/      ← Owner C
      calendar/           ← Owner D
      contacts/           ← Owner D
      files/              ← Owner C
      shortcuts/          ← Owner C
      keyvault/           ← Owner C (Keychain)
  ```

#### `security`
- **Purpose:** Secrets storage, network allowlist, audit log, sandbox enforcement.
- **Public API:** `KeyVault` (consumes os-bridge), `NetworkPolicy`, `AuditLog`, `Sandbox`.
- **Depends on:** `contracts`, `util`, `os-bridge`.
- **Platforms:** common.
- **Owner notes:** Sensitive code. Security-conscious reviewer mandatory. Tests are non-negotiable.

#### `compliance`
- **Purpose:** Store-compliance affordances. Consent modals, AI labeling, in-app reporting.
- **Public API:** `ConsentManager`, `AILabel` (composable / SwiftUI view), `ReportingFlow`.
- **Depends on:** `contracts`, `design-system`.
- **Platforms:** common API + per-platform UI.
- **Owner notes:** Boring but mandatory. Good first-PR territory.

---

### Layer 3: Harness

Each module implements `LLMMiddleware`. They compose into a chain at startup.

#### `harness-reliability`
- **Purpose:** Retries, timeouts, fallback model, circuit breaker.
- **Public API:** `ReliabilityMiddleware(config)`, `RetryPolicy`, `CircuitBreaker`.
- **Depends on:** `contracts`, `util`.
- **Platforms:** common.
- **Owner notes:** Self-contained. Testable against the mock LLM client.

#### `harness-quality`
- **Purpose:** Schema validation on tool calls; structured retry for malformed responses.
- **Public API:** `QualityMiddleware(config)`, `ToolCallValidator`.
- **Depends on:** `contracts`, `util`.
- **Platforms:** common.
- **Owner notes:** Self-contained.

#### `harness-observability`
- **Purpose:** Trace recording, metrics, feedback collection, debug viewer.
- **Public API:** `ObservabilityMiddleware`, `TraceStore`, `TraceViewer` (UI).
- **Depends on:** `contracts`, `util`, `design-system`, SQLDelight.
- **Platforms:** common (storage) + per-platform (viewer UI).
- **Owner notes:** Backend + UI pair work well here.

#### `harness-cost`
- **Purpose:** Token tracking, quota enforcement, model selection.
- **Public API:** `CostMiddleware(policy)`, `QuotaPolicy` interface, default policies, `UsageStore`.
- **Depends on:** `contracts`, `util`, SQLDelight.
- **Platforms:** common.
- **Owner notes:** Self-contained.

#### `harness-behavior`
- **Purpose:** System prompt composition, conversation pruning at context limit.
- **Public API:** `BehaviorMiddleware`, `PromptComposer`, `ContextPruner`.
- **Depends on:** `contracts`, `util`.
- **Platforms:** common.
- **Owner notes:** Self-contained.

#### `harness-memory`
- **Purpose:** Explicit memory storage and recall scripts; user-visible storage; PII detection.
- **Public API:** `MemoryMiddleware`, `MemoryStore`, `memory.store` / `memory.recall` script implementations, `MemoryViewerScreen`.
- **Depends on:** `contracts`, `scripts-core`, `design-system`, SQLDelight, SQLite FTS5.
- **Platforms:** common + per-platform (viewer UI).
- **Owner notes:** Privacy-sensitive. Reviewer must understand explicit-only invariant.

#### `harness-evaluation`
- **Purpose:** Minimal eval harness for regression testing.
- **Public API:** `IntentTest`, `Expectation`, `EvalRunner`.
- **Depends on:** `core`, `contracts`.
- **Platforms:** common (JVM tests).
- **Owner notes:** Doesn't ship in user-facing app. Runs in CI.

---

### Layer 4: Domain (per-app)

#### `scripts-undercurrent`
- **Purpose:** Domain scripts for Undercurrent (journal entries, dropped identities, evaluations).
- **Public API:** Undercurrent-specific scripts registered with `ScriptRegistry`.
- **Depends on:** `contracts`, `scripts-core`, `os-bridge`, Undercurrent data layer.
- **Platforms:** common.

#### `design-undercurrent`
- **Purpose:** Undercurrent-specific templates and tokens override.
- **Public API:** `UndercurrentTheme`, custom templates (ReflectionScreen, BucketEvaluationScreen, IdentityListScreen).
- **Depends on:** `design-system`.
- **Platforms:** common API + per-platform UI.

---

### Layer 5: App

#### `reference-app`
- **Purpose:** Undercurrent shipped as a working product. Wires all modules together.
- **Public API:** none.
- **Depends on:** all of layers 1–4.
- **Platforms:** Android app + iOS app.
- **Owner notes:** Integration point. Reveals where module boundaries are wrong.

#### `examples/`
- **Purpose:** Smaller sample apps demonstrating substrate patterns.
- **Owner notes:** Each example is one folder, one tiny app, one purpose. Good contributor onboarding.

---

## Dependency graph (cycle check)

Edges point from the depending module *down* to its dependency. Every higher module is implicitly allowed to depend on `core`/`contracts`/`util`; those arrows are omitted from the diagram for readability and noted in the per-module entries above.

```
Layer 5:                         reference-app
                          ┌────────┬────────┬────────┐
                          ↓        ↓        ↓        ↓
Layer 4:        scripts-undercurrent   design-undercurrent
                          │                  │
              ┌───────────┘                  └───────────┐
              ↓                                          ↓
Layer 3:  scripts-core       harness-*  (reliability, quality,
              │              observability, cost, behavior,
              │              memory, evaluation)
              │                  │
              │           ┌──────┴───────┐
              │           ↓              ↓
Layer 2:  os-bridge   design-system   llm-anthropic   security   compliance
              │           │              │             │           │
              │           │              │             ↓           ↓
              │           │              │         os-bridge   design-system
              │           │              │             │
              └───────────┴──────────────┴─────────────┘
                                ↓
Layer 1:                       core
                                ↓
                            contracts
                                ↓
                              util
```

Selected concrete edges (not exhaustive — see each module's "Depends on" entry):

- `scripts-core` → `contracts`, `util`, `os-bridge`
- `os-bridge` → `contracts`, `util`
- `security` → `contracts`, `util`, `os-bridge`
- `compliance` → `contracts`, `design-system`
- `llm-anthropic` → `contracts`, `util` (+ Koog, Ktor)
- `harness-observability` → `contracts`, `util`, `design-system`, SQLDelight
- `harness-memory` → `contracts`, `scripts-core`, `design-system`, SQLDelight (FTS5)
- All other `harness-*` → `contracts`, `util`
- `core` → `contracts`, `util`
- `contracts` → `util`
- `util` → ∅

No cycles. Gradle enforces this on every PR.

## Recommended repo layout

```
substrate/
├── settings.gradle.kts              # declares all modules
├── build.gradle.kts                 # root config
├── gradle/libs.versions.toml        # central version catalog
│
├── util/
├── contracts/
├── core/
│
├── llm/
│   └── anthropic/                   # llm-anthropic
│
├── os-bridge/
│   ├── api/
│   ├── android/
│   └── ios/
│
├── security/
├── compliance/
│
├── scripts/
│   └── core/                        # scripts-core
│
├── design-system/
│   ├── api/
│   ├── compose/                     # Android UI
│   └── swiftui/                     # iOS UI (Swift package)
│
├── harness/
│   ├── reliability/
│   ├── quality/
│   ├── observability/
│   ├── cost/
│   ├── behavior/
│   ├── memory/
│   └── evaluation/
│
├── apps/
│   ├── undercurrent/
│   │   ├── scripts/                 # scripts-undercurrent
│   │   ├── design/                  # design-undercurrent
│   │   ├── android/                 # Android app
│   │   └── ios/                     # iOS app
│   └── examples/
│       └── timer-demo/
│
└── docs/
    ├── architecture/
    ├── modules/                     # contract docs per module
    ├── contributing/
    └── adr/                         # architecture decision records
```

## Module contract doc template

Each module ships with a `docs/modules/<name>.md` that follows this template:

```
# Module: <name>

## Purpose
One sentence.

## Public API
Surface that other modules depend on. Listed exhaustively.

## Internal API
Listed for reviewers; can change without notice.

## Stability
- v0.x: any breaking change allowed
- v1.x: breaking changes require migration guide

## Test coverage targets
- Lines: X%
- Branches: Y%

## Owner
Role + GitHub handle

## Dependencies
Explicit list. Adding a new dependency requires PR review by the dep's owner.

## Notes
Anything else contributors need to know.
```

## Module creation order (Phase 0–2)

Building order in the first two months:

| Week | Modules created (scaffolded or completed) |
|---|---|
| 1 | `util`, `contracts` (scaffolding) |
| 2 | `contracts` (full v1 types), `core` (skeleton) |
| 3 | `security`, `os-bridge/api` (interfaces only) |
| 4 | `llm-anthropic` (real Claude calls), `core` (agent loop closed) |
| 5 | `compliance` skeleton, `harness-reliability` + `harness-quality` (prove the chain works) |
| 6 | Remaining harness modules scaffolded; `scripts-core` skeleton; `design-system/api` skeleton |

By end of week 6 every module exists as a scaffolded package with its public API defined. Then waves of parallel work fill them in.
