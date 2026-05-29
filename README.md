# Weft

An Android substrate for building LLM-orchestrated apps where the model
drives native capabilities — calling device tools, rendering real Compose
UI, persisting conversations, and remembering across sessions — instead
of just exchanging text.

You bring an API key (Anthropic, OpenAI, OpenRouter, or DeepSeek), the
substrate handles the rest: streaming, tool execution, conversation
storage, memory, cost tracking, reliability, permission gating. Apps
plug in custom tools and custom UI components without touching the
agent loop.

**Status:** pre-1.0, actively developed. Used in production by the
[Undercurrent](https://github.com/NguyenKhacPhuc/undercurrent) reference app (sibling
repo). API surfaces stabilize before each Undercurrent release; expect
breaking changes between minor versions until 1.0.

## Mental model

- **Tools** are the verbs — anything the LLM can *do* on the device.
  ~50 built-ins cover calendar, contacts, clipboard, files, network,
  scheduling, notifications, biometrics, PDF, vision (OCR / barcode),
  location, maps, system info, audio, speech (TTS + STT), haptics,
  Bluetooth, WiFi info, telephony (Intent-based dial/SMS), volume,
  power (wake-lock, brightness), gallery (MediaStore + Photo Picker),
  app discovery, sensors (step counter + ambient light), display
  state, ML Kit translation, image transforms (resize/crop/rotate),
  app shortcuts, system-settings deep links, and a pile of pure
  utility tools (math, regex, url, color, hashing, base64, JSON
  query, date arithmetic, random). Apps add their own by subclassing
  `WeftTool`.
- **Components** are the nouns — Compose UI the LLM can *render*.
  Built-ins ship in `:compose-defaults` (timers, forms, pickers,
  galleries, lists, web). Apps register their own `WeftComponent`s and
  the LLM calls `ui_render` with props.
- **Skills** are local fast-path verbs — slash-commands that resolve
  on-device without an LLM round-trip. Apps register a `SkillRegistry`;
  `/help` is auto-injected.
- **Agents** are addressable personas — one default, plus any number
  the host registers via `AgentDeclaration` (each with its own role
  fragment, tool allowlist, model tier, and strategy). The LLM hands
  off via `delegate_to_agent`; users address via `@writer` mentions.
  Per-agent prompts are catalog-scoped (Stage 1 of the tool-provider
  design) so a writer agent with two allowed tools no longer pays
  tokens for the substrate's full ~50-tool catalog.
- **The harness** wraps every model call: streaming, retries with
  jittered backoff, circuit breaker, cost accounting, trace capture,
  payload redaction, quota enforcement, plan-mode + approval-mode
  gates, hooks, pluggable strategy (`DefaultStrategy` /
  `FrugalStrategy` / `BurstStrategy` / custom), behavior policy.
- **`find_tool` + lazy catalog** is the discovery surface for
  on-demand tools — the LLM searches the substrate's `ToolProvider`,
  the activation node mutates `llm.tools` mid-turn, and the new
  tools become callable in the *same* user-visible turn. The
  catalog stays small in every prompt; rarely-used tools materialize
  only when the LLM asks for them.

## What's in the box

| Module | What it gives you |
| --- | --- |
| `:contracts` | Pure-Kotlin interfaces: `WeftCredentialProvider`, `ProviderKind`, `OsCapabilities` (32 sub-interfaces), `UiBridge`, `DataSource`, `MemoryProvider`, `ToolProvider`, `ToolActivationSink`, `WeftHook`, `Plan`, `ApprovalMode`, `ToolRisk`. |
| `:tools` | ~50 built-in `WeftTool<Args, Result>` subclasses, plus the permission/destructive/approval/hook gates that wrap every execution. `EagerToolProvider` + `compositeToolProvider` for Stage-2 lazy-catalog assembly. |
| `:os-bridge` | Android implementations of every `OsCapabilities` sub-interface (files, sharing, scheduling, notifications, permissions, biometrics, audio, vision, location, speech, camera, system-info, PDF, Bluetooth, media library + picker, apps, sensors, telephony, WiFi, volume, power, settings, shortcuts, translation, image-ops). |
| `:security` | `NetworkPolicy` allowlists, `Redactor` for trace sanitization. |
| `:harness:agents` | `WeftAgent` — streaming send, model routing (cheap / standard / vision / heavy tiers), per-turn volatile prompt slots, regenerate-last, multi-agent + `delegate_to_agent`, plan mode, structured outputs, pluggable `WeftStrategy`, mid-turn tool activation via `find_tool`. Pure JVM. |
| `:harness:prompt` | Prompt assembly primitives (system prompt builder, cache binder, multimodal input). |
| `:harness:memory` | `MemoryStore` + `memory_store` / `memory_recall` tools. SQLDelight-backed. |
| `:harness:conversation` | `ConversationStore` — persistent threads, search, hydration. SQLDelight-backed. |
| `:harness:reliability` | Circuit breaker, retry policy, timeout config. |
| `:harness:observability` | `AgentTrace` capture, trace export. |
| `:harness:cost` | Per-turn token + dollar accounting per provider/model. |
| `:harness:behavior` | App-level behavioral policy (sensitive-action confirmations, refusal handling). |
| `:harness:skills` | `Skill`, `SkillRegistry`, `withHelp` — local slash-command fast-path. |
| `:mcp` | Model Context Protocol client. Connect external MCP servers; their tools appear in the agent's registry as `{server}:{tool}`. |
| `:oauth` | OAuth 2.0 + PKCE client for services behind per-user auth (Linear, Gmail, GitHub, …). |
| `:runtime` | Composition root — `WeftRuntime.create(...)` wires the agent + persistence + OS bridges + tool catalog. Android-bound today; iOS hosts wire their own composition. |
| `:compose` | Framework for custom UI components — `WeftComponent` base, registry, `ComposeUiBridge`, tree renderer. No Material dependency. KMP (Compose Multiplatform). |
| `:compose-defaults` | Default M3 palette + a stock set of `WeftComponent`s. Opt-out: apps with custom design systems depend only on `:compose`. KMP; WebView/Html stays androidMain. |
| `:devtools` | Debug-build overlay for live runtime inspection. Android-only panel; KMP-published with empty iOS targets. |

## Providers

Multi-provider via Koog. Each app registers a `WeftCredentialProvider`
per backend; the runtime picks the cache binder + cost table from
`ProviderKind`:

- **Anthropic** — Claude family. Native cache control.
- **OpenAI** — GPT family. Bearer-token auth.
- **OpenRouter** — gateway to dozens of upstream models behind one key.
- **DeepSeek** — rides OpenAI-compatible client (api.deepseek.com).

Per-tier model overrides live in the host app (see Undercurrent's
`ModelPrefsRepository` for the pattern). Switching provider at runtime
rebuilds the agent; in-flight streams cancel cleanly.

## Hello-world

```kotlin
val runtime = WeftRuntime.create(
    context = applicationContext,
    uiBridge = ComposeUiBridge(componentRegistry),
    appPromptPreamble = "You are an assistant running on the user's phone.",
    networkPolicy = NetworkPolicy.OPEN,
    extraToolsFactory = { ctx -> listOf(MyAppTool(ctx)) },
    componentMetadata = myComponents,
)

val agent = runtime.buildAgent(StaticKeyProvider(apiKey))
agent.resume()

agent.sendStreaming("What's on my calendar today?").collect { chunk ->
    when (chunk) {
        is StreamChunk.TextDelta -> append(chunk.text)
        is StreamChunk.ToolStarting -> showInline("Using ${chunk.toolName}…")
        is StreamChunk.Done -> finalize()
        else -> Unit
    }
}
```

See the [Undercurrent app](https://github.com/NguyenKhacPhuc/undercurrent) for a
complete wiring (MVI store, Koin DI, theming, multi-provider key vault,
persona system, settings surfaces).

## Reference app

[Undercurrent](https://github.com/NguyenKhacPhuc/undercurrent) is a personal-assistant
app built end-to-end on Weft. It exercises the full surface area:
streaming chat, multi-provider key management, custom personas, custom
agent tools (theme switching, persona selection from chat),
memory-browser UI, trace export, in-app conversation search, palette
themes. Treat it as the canonical example of how a host app composes
the substrate.

## Stack

- Android (`compileSdk` 35, `minSdk` 26).
- Kotlin 2.x with `kotlin.compose` plugin for the Compose modules.
- [Koog](https://github.com/JetBrains/koog) as the agent framework
  foundation (`ToolRegistry`, `LLMClient`, streaming primitives).
- SQLDelight for conversation + memory persistence.
- AndroidX DataStore for prefs.
- Apache 2.0 licensed.

The root build declares `kotlin.multiplatform` plugin aliases — that's
forward-looking; today every module is JVM or Android. iOS / KMP is a
direction, not a current target.

## Docs

The full set of design + architecture docs lives under `docs/`. Code
under each module's `src/main` is the source of truth for what
actually ships; the docs are the why-and-how for anything that
crosses a module boundary.

### Architecture — start here

- **[`docs/architecture-diagrams.md`](docs/architecture-diagrams.md)**
  — visual map. SDK/app boundary, module DAG, runtime composition,
  turn lifecycle, `find_tool` activation flow, tool-execution gate
  stack, OS capabilities domain map, lazy catalog flow, multi-agent
  + per-agent prompt scoping. Mermaid; renders in GitHub + mkdocs.
- **[`docs/architecture-vision.md`](docs/architecture-vision.md)** —
  the north star (prose). The rule "The SDK provides everything, the
  app just registers" and the per-area split between substrate and
  host. Read this when you need words rather than pictures.
- **[`docs/02-architecture.md`](docs/02-architecture.md)** — module
  boundaries (high level).
- **[`docs/03-modules.md`](docs/03-modules.md)** — per-module surface
  area.
- **[`docs/07-harness.md`](docs/07-harness.md)** — reliability /
  observability / cost / behavior.

### Architecture deep-dives (ADR-style)

Per-change design docs under `docs/architecture/`. Each one captures
why the change exists, what landed, what's deferred, and links to
the code:

- **[`tool-provider.md`](docs/architecture/tool-provider.md)** —
  per-agent prompt scoping (Stage 1, shipped) + lazy `ToolProvider`
  + `find_tool` mid-turn discovery (Stage 2 core, shipped). MCP
  on-demand migration + sticky activation persistence still
  outstanding.
- **[`tool-provider-koog-probe.md`](docs/architecture/tool-provider-koog-probe.md)**
  — Stage 2 prerequisite probe of Koog's `AIAgent` API. Result:
  Path A viable via `llm.writeSession { tools = ... }` +
  `toolRegistry.add(...)`. Single-turn UX for `find_tool`.
- **[`multi-agent-registry.md`](docs/architecture/multi-agent-registry.md)**
  — `AgentDeclaration`, `delegate_to_agent` tool, depth cap, per-agent
  system fragments + tool allowlists. Shipped.
- **[`strategy-hook.md`](docs/architecture/strategy-hook.md)** —
  pluggable `WeftStrategy` interface controlling retry, cache tiers,
  routing tier hint, iteration cap. `DefaultStrategy` /
  `FrugalStrategy` / `BurstStrategy` reference impls. Shipped.
- **[`runtime-factory-async.md`](docs/architecture/runtime-factory-async.md)**
  — sync `WeftRuntime.create(mcpServers = ...)` with background MCP
  discovery (no more `runBlocking` on suspend factories). Shipped.

### Producing UI

- **[`ui-components.md`](docs/ui-components.md)** — `WeftComponent`
  framework, registry, tree renderer.
- **[`writing-a-custom-component.md`](docs/writing-a-custom-component.md)**
  — step-by-step recipe.

### Producing tools

- **[`writing-a-custom-tool.md`](docs/writing-a-custom-tool.md)** —
  `WeftTool` authoring rules, especially the naming + description
  conventions that make the LLM actually pick the tool. Read this
  before adding any tool; the rules are load-bearing for LLM
  selection.

### Production readiness (Play Store-shaped concerns)

- **[`PLAY-POLICY.md`](docs/PLAY-POLICY.md)** — per-permission
  status table (normal / runtime / restricted / conditional), Play
  Console Permissions Declaration form copy for restricted
  permissions, pre-submission checklist.
- **[`PRIVACY.md`](docs/PRIVACY.md)** — paste-ready Play Console
  Data Safety form mapping: which substrate tool surfaces which
  data type, why we collect it, whether it's shared with the LLM
  provider, optional-vs-required.
- **[`manifest-merging.md`](docs/manifest-merging.md)** — how host
  apps strip substrate permissions they don't use via
  `tools:node="remove"` (e.g. drop exact-alarm declarations if your
  app isn't a reminder/calendar app). Five worked recipes plus
  `<queries><package>` patterns for `app_installed` against specific
  packages.

### Historical / forward-looking

Some docs (`01-vision-and-scope.md`, `09-roadmap.md`,
`13-v1.1-backlog.md`, `12-first-week.md`, the ADRs in `docs/adr/`)
predate the current code — they describe the Phase 0 plan when the
project was still being scoped as KMP. Treat them as historical
context plus a roadmap, not a current API reference.

- **[`follow-ups.md`](docs/follow-ups.md)** — implementation-time
  deferrals ("ship the simpler thing, come back to this"). Distinct
  from the v1.1 backlog; updated as work happens.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security disclosure:
[SECURITY.md](SECURITY.md). Conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

Apache 2.0. See [LICENSE](LICENSE).
