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
  Built-ins cover calendar, contacts, clipboard, files, network,
  scheduling, notifications, biometrics, PDF, vision (OCR / barcode),
  location, maps, system info, audio, haptics, speech, Bluetooth, and
  data CRUD. Apps add their own by subclassing `WeftTool`.
- **Components** are the nouns — Compose UI the LLM can *render*.
  Built-ins ship in `:android-compose-defaults` (timers, forms, pickers,
  galleries, lists, web). Apps register their own `WeftComponent`s and
  the LLM calls `ui_render` with props.
- **Skills** are local fast-path verbs — slash-commands that resolve
  on-device without an LLM round-trip. Apps register a `SkillRegistry`;
  `/help` is auto-injected.
- **The harness** wraps every model call: streaming, retries with
  jittered backoff, circuit breaker, cost accounting, trace capture,
  payload redaction, quota enforcement, behavior policy.

## What's in the box

| Module | What it gives you |
| --- | --- |
| `:contracts` | Pure-Kotlin interfaces: `WeftCredentialProvider`, `ProviderKind`, `OsCapabilities`, `UiBridge`, `DataSource`, `MemoryProvider`. |
| `:tools` | 29 built-in device tools as `WeftTool<Args, Result>` subclasses, plus the permission/destructive gates that wrap every execution. |
| `:os-bridge` | Android implementations of the OS capability interfaces (files, sharing, scheduling, notifications, permissions, biometrics, audio…). |
| `:security` | `NetworkPolicy` allowlists, `Redactor` for trace sanitization. |
| `:harness:agents` | `WeftAgent` — streaming send, model routing (cheap / standard / vision / heavy tiers), per-turn volatile prompt slots, regenerate-last. Pure JVM. |
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
| `:android` | Composition root — `WeftRuntime.create(...)` wires the agent + persistence + OS bridges + tool catalog. |
| `:android-compose` | Framework for custom UI components — `WeftComponent` base, registry, `ComposeUiBridge`, tree renderer. No Material dependency. |
| `:android-compose-defaults` | Default M3 palette + a stock set of `WeftComponent`s. Opt-out: apps with custom design systems depend only on `:android-compose`. |
| `:android-devtools` | Debug-build overlay for live runtime inspection. |

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

Long-form design + architecture docs live under `docs/`. Some of them
predate the current code (they describe the Phase 0 plan when the
project was still being scoped as KMP) — treat them as historical
context plus a roadmap, not a current API reference. The code under
each module's `src/main` is the source of truth for what actually
ships.

Pointers that are mostly current:

- `docs/02-architecture.md` — module boundaries.
- `docs/03-modules.md` — per-module surface area.
- `docs/07-harness.md` — reliability / observability / cost / behavior.
- `docs/ui-components.md` — `WeftComponent` framework.
- `docs/writing-a-custom-component.md` — step-by-step recipe.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security disclosure:
[SECURITY.md](SECURITY.md). Conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

Apache 2.0. See [LICENSE](LICENSE).
