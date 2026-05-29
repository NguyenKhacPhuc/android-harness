# Weft — Claude working notes

Auto-loaded as context by Claude Code. Keep terse — every session pays
for these tokens. Deeper reads live under `docs/`.

## What this repo is

Weft is a KMP substrate (Android + iOS) for building LLM-orchestrated
apps. The agent calls device tools, renders Compose UI, persists
conversations, remembers facts. Apps depend on `:runtime` (+ optionally
`:compose`, `:compose-defaults`) and register their own tools /
components on top.

Reference app: <https://github.com/NguyenKhacPhuc/undercurrent> —
sibling repo. Use it as the canonical example of host-app wiring.

## The architectural rule (read first)

> **The SDK provides everything. The app just registers.**

The full split is documented in `docs/architecture-vision.md`. Headline:
tools, memory, conversation, traces, cost, routing, prompt assembly,
data-binding, MCP, OAuth, OS bridges, multi-agent (future), strategy
(future) — all SDK. The app contributes identity + screens + DI wiring +
which tools/components/data-sources/MCP-servers to register. When
considering where new logic goes, ask: *would another Weft host need
this same behavior?* If yes → SDK. If it's about the specific app's
identity / UX / branding → app.

## Module layout (skim before searching)

- `:contracts` — pure-Kotlin interfaces. `WeftCredentialProvider`,
  `OsCapabilities`, `KeyVault`, `Permission`, etc.
- `:tools` — built-in `WeftTool` subclasses + the `WeftContext` / base
  class machinery. **~30 tools today**; new app-specific tools go in
  the host app, not here.
- `:os-bridge` — Android impls of the OS capability interfaces. KMP
  module with empty iOS targets; iOS hosts implement OsCapabilities
  themselves against CoreLocation / Vision / PDFKit / etc.
- `:security` — `NetworkPolicy` + `Redactor`.
- `:harness:agents` — `WeftAgent`. Streaming, model routing, regenerate.
- `:harness:prompt` — prompt assembly, cache binder, multimodal input.
- `:harness:memory` / `:harness:conversation` — SQLDelight-backed stores.
- `:harness:{reliability,observability,cost,behavior,skills}` — the
  agent-loop wrap.
- `:mcp` — MCP client. `:oauth` — OAuth 2.0 + PKCE for per-user auth.
- `:runtime` — composition root, `WeftRuntime.create(...)`. Android-bound
  today (KMP-published with empty iOS targets); iOS hosts wire their own
  composition via host-defined factories (see undercurrent's
  `IosWeftAgentFactory`).
- `:compose` / `:compose-defaults` — UI surface. `:compose` is fully
  commonMain (Compose Multiplatform); `:compose-defaults` is commonMain
  except `:compose-defaults`'s WebView / Html (androidMain — wraps
  `android.webkit.WebView`; iOS gets an empty `EmbedComponents` actual).
- `:devtools` — debug overlay. KMP-published with empty iOS targets
  (panel reads androidMain-only `WeftRuntime`).

> **Naming history.** Modules used to be `:android` / `:android-compose`
> / `:android-compose-defaults` / `:android-devtools` from when the
> substrate was Android-only. After the KMP migration the prefix is
> misleading — the renames dropped it. Old composite-build
> substitutions like `dev.weft:weft-android` are now
> `dev.weft:weft-runtime` (and similar for the others).

## Build target quirks (will bite you)

- **Substrate modules target JVM 17.** App consumers usually target
  JVM 11 for older-device support. This means **inline reified
  functions in the substrate can't be inlined from a JVM 11 caller**
  — the bytecode is JVM 17.
- The most common case is `ai.koog.serialization.typeToken<T>()`. In a
  JVM 11 host module, use the non-inline form:
  ```kotlin
  import ai.koog.serialization.KotlinTypeToken
  import kotlin.reflect.typeOf
  // …
  argsType = KotlinTypeToken(typeOf<MyArgs>())
  ```

## Tool-authoring rules (load-bearing)

Tool selection is a soft attention process. Names + descriptions are
not cosmetic — they determine whether the model picks the tool or
silently skips it. Full guide: `docs/writing-a-custom-tool.md`. The
five rules that matter most:

1. **Name: `<verb>_<noun>`, ≤3 words, lowercase_snake_case.**
   `open_map`, `send_email`, `set_theme_palette`. Compound names with
   prepositions (`show_location_on_map`) get skipped.
2. **Description: lead with the action.** "Open the map app pinned
   at…" beats "This tool is used when…".
3. **Cap descriptions at ~3 sentences / 250 chars.** Long
   descriptions cause tool-skip behavior.
4. **Disambiguate from neighbors.** "NOT for directions — use
   `maps_open_directions` for navigation" prevents misrouting.
5. **Group by prefix.** `location_*`, `calendar_*`, `maps_*`.

When a tool gets ignored, check (in order): app restart, fresh
conversation, name shape, description length, neighbor confusion.
Add a `Log.d` on entry to `executeWeft` for diagnostics — `adb
logcat -s YourTag` confirms whether the tool fired or got skipped at
selection time.

## Anti-hallucination preamble note

Claude sometimes narrates "I'll call X" without emitting the
tool_use block. The cure is an explicit directive in the host app's
`appPromptPreamble`:

> "Never narrate a tool call you don't make. If you tell the user
> 'opening the map' or 'let me check X', you MUST emit the
> corresponding tool_use block in the same turn."

This isn't a substrate concern (different apps have different
preambles), but mention it whenever a host's tools are silently
skipped.

## Conventions worth knowing

- **`internal`/`public` discipline.** Substrate modules export only
  what host apps need; everything else is `internal`. If you add a
  type that should be reachable from `:runtime` or further, mark it
  `public`.
- **No emojis in code or commits** unless the user explicitly asks.
- **KMP status — every module ships KMP artifacts now**
  (jvm + androidTarget + iosArm64 + iosSimulatorArm64). What lives in
  commonMain vs androidMain varies: `:contracts`, `:security`,
  `:harness:*`, `:mcp`, `:oauth`, `:compose`, `:tools` are fully or
  mostly commonMain. `:runtime`, `:os-bridge`, `:devtools`, and the
  WebView/Html bits of `:compose-defaults` stay androidMain — they
  bind Android APIs (Context-backed composition root, ML Kit, Play
  Services, `android.webkit.WebView`). iOS hosts wire equivalents in
  their own iosMain (see undercurrent's `IosWeftAgentFactory` +
  iOS-side OsCapabilities impls).
- **SQLDelight** for any persistent storage. See `:harness:memory`
  and `:harness:conversation` for the pattern.
- **Permissions live in two places.** The `Permission` enum lives in
  `:contracts`; the Android mapping is in `:os-bridge`'s
  `AndroidPermissions.toAndroidPermission`. Adding a new permission
  requires editing both.

## Common commands

```bash
# Build everything (from the repo root).
./gradlew build

# Run detekt across the repo.
./gradlew detekt

# Build a specific module.
./gradlew :tools:build
./gradlew :runtime:assembleDebug

# Most useful when iterating on a tool:
./gradlew :tools:compileKotlin
```

## What NOT to do

- Don't add app-specific tools to `:tools`. They belong in the host
  app via `extraToolsFactory`.
- Don't use inline reified `typeToken<T>()` in code that needs to be
  consumable from a JVM 11 host. Use `KotlinTypeToken(typeOf<T>())`.
- Don't commit changes that bump JVM target across the substrate
  without flagging — host apps may have made compatibility
  assumptions.
- Don't reformat or rewrite descriptions without testing — small
  wording changes measurably shift Claude's tool-selection behavior.
