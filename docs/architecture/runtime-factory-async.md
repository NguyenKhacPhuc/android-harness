# Async-friendly `WeftRuntime` factory

- **Status:** Design — approved direction, not yet implemented
- **Date:** 2026-05-24
- **Tracks:** `architecture-vision.md` § Active misalignments →
  "`runBlocking` on suspend factories"
- **Estimated scope:** ~250–400 LOC (factory signature change in
  `:android`, MCP discovery lifecycle moves into the runtime, three
  `runBlocking` call sites in the reference app deleted).

## Why this exists

`WeftRuntime.createWithMcpServers` at
[WeftRuntime.kt:857](../../android/src/main/kotlin/dev/weft/android/WeftRuntime.kt#L857)
is suspend because it talks to every configured MCP server (HTTP
`initialize` + `tools/list`) before constructing the runtime. Host
apps using Koin must wrap the call in `runBlocking { }` because
`single { }` providers are synchronous:

| File | Line | What |
| --- | --- | --- |
| [`AppModule.kt`](../../../undercurrent/app/src/main/kotlin/dev/weft/undercurrent/di/AppModule.kt#L139) | 139 | Blocks the main thread during DI graph construction. ~500–1500ms on cold start with one connected integration. **The expensive one.** |
| [`AppModule.kt`](../../../undercurrent/app/src/main/kotlin/dev/weft/undercurrent/di/AppModule.kt#L266) | 266 | DataStore read for `initialEnabled`. Cheap (~ms). Not really a problem. |
| [`AppModule.kt`](../../../undercurrent/app/src/main/kotlin/dev/weft/undercurrent/di/AppModule.kt#L288) | 288 | DataStore read for enabled-integration set inside `mcpServersFor`. Cheap. Not really a problem. |

The real issue is the first one: blocking the main thread inside
Koin's synchronous factory. The substrate should let apps construct
`WeftRuntime` synchronously without sacrificing MCP support.

## Decision

`WeftRuntime.create()` becomes synchronous and accepts `mcpServers`
directly. MCP discovery is launched **inside the runtime** on its
`runtimeScope` as a `Deferred<List<McpToolDescriptor>>`. The first
`WeftAgent.send()` awaits the deferred before building the per-turn
agent; subsequent sends see the already-resolved value at zero cost.

This is the "block first turn on MCP" UX selected by the user — the
visible behavior is identical to today (the user feels MCP discovery
the first time they send a message), but the DI thread is no longer
blocked at app startup.

`createWithMcpServers` is **removed** in favor of a unified `create()`
that takes an optional `mcpServers` list. One factory, one shape.

## Factory shape

```kotlin
public class WeftRuntime internal constructor(/* ... */) {

    /**
     * Resolves once MCP discovery has completed (or failed gracefully
     * — per-server errors don't reject the deferred; they're routed to
     * [onMcpError] and the failing server's tools are omitted).
     * Always non-null after first resolution.
     */
    internal val mcpToolsReady: Deferred<List<McpRegisteredTool>>

    public companion object {
        public fun create(
            context: Context,
            uiBridge: UiBridge,
            appPromptPreamble: String,
            mcpServers: List<McpServerConfig> = emptyList(),
            // ... all existing create() params ...
            onMcpError: (McpServerConfig, Throwable) -> Unit = { _, _ -> },
        ): WeftRuntime
    }
}
```

Notes:

- **No `suspend` keyword.** Synchronous from the caller's perspective.
- **`mcpServers` empty case** still completes the deferred immediately
  with an empty list, so the await is a no-op.
- **`onMcpError`** keeps the existing per-server fault-isolation
  contract.
- **Network policy applies to MCP hosts** identically to today — the
  same `whitelistingHttpClient` is constructed inside `create()` when
  `mcpServers` is non-empty.

## Lifecycle inside the runtime

In `WeftRuntime`'s `init` block (already exists; see
[:325](../../android/src/main/kotlin/dev/weft/android/WeftRuntime.kt#L325)):

```kotlin
internal val mcpToolsReady: Deferred<List<McpRegisteredTool>> =
    if (mcpServers.isEmpty()) {
        CompletableDeferred(emptyList())
    } else {
        runtimeScope.async(Dispatchers.IO) {
            mcpServers.flatMap { server ->
                runCatching {
                    mcpClient.initialize(server)
                    mcpClient.listTools(server).map { McpRegisteredTool(server, it) }
                }.getOrElse { t ->
                    onMcpError(server, t)
                    emptyList()
                }
            }
        }
    }
```

Tool registration becomes two-phase:

1. **Eager phase** (today's
   [tools build](../../android/src/main/kotlin/dev/weft/android/WeftRuntime.kt#L359)
   at `:359`): substrate built-ins + `extraToolsFactory(...)` results.
2. **Deferred phase**: when `mcpToolsReady` resolves, the resulting
   `McpRegisteredTool`s are added to the `ToolRegistry` consumed by
   `WeftAgent`.

The `ToolRegistry` needs to be mutable for this — today it's effectively
immutable per-runtime. Options:

- **(a)** Make `ToolRegistry` a `MutableMap<ToolDescriptor, WeftTool>`
  behind an interface; `WeftAgent.buildAgentForThisTurn` reads the
  current snapshot. Cleanest but more surface area.
- **(b)** Keep `ToolRegistry` immutable, but each `WeftAgent.send()`
  awaits `mcpToolsReady` and constructs the registry on first turn,
  cached on subsequent turns. Smaller change.

**Proposal: (b).** The MCP tool list never changes during a runtime's
lifetime today (the reference app force-restarts when integrations
change — see [`MainActivity.kt`](
../../../undercurrent/app/src/main/kotlin/dev/weft/undercurrent/core/MainActivity.kt)
restart logic), so we don't need real mutability. Just lazy
materialization.

Specifically: `WeftAgent` gains a `private val toolRegistryProvider:
suspend () -> ToolRegistry` constructor param. `send()` calls it,
which awaits `mcpToolsReady` the first time and caches the result.

## Host-app migration

Reference app (Undercurrent) becomes:

```kotlin
// AppModule.kt
single<WeftRuntime> {
    WeftRuntime.create(
        context = androidContext(),
        uiBridge = get<ComposeUiBridge>(),
        appPromptPreamble = ASSISTANT_APP_PREAMBLE,
        mcpServers = mcpServersFor(get(), get()),
        // ... rest unchanged ...
    )
}
```

The `runBlocking` at `AppModule.kt:139` is **deleted**. The other two
(`:266`, `:288`) are out of scope — they're cheap DataStore reads and
the architecture doc doesn't call them out as misalignments. We could
clean them up by hoisting the read into a coroutine-friendly site, but
that's separate work that doesn't depend on this factory change.

## What this is NOT doing

- **Not introducing hot-reload of MCP servers.** Adding or removing an
  integration still requires the reference app's process-restart flow
  (see `restartProcess(context)` in
  [`MainActivity.kt`](../../../undercurrent/app/src/main/kotlin/dev/weft/undercurrent/core/MainActivity.kt)).
  Dynamic MCP registration is a separate, larger design.
- **Not changing UX during MCP load.** First turn still suspends until
  MCP discovery completes. Apps that want a "loading integrations…"
  banner can subscribe to a new `WeftRuntime.mcpStatus: StateFlow`
  (out of scope here, ~20 LOC follow-up).
- **Not removing the `runBlocking` calls that read DataStore at
  `:266` / `:288`.** Those are cheap and unrelated to the suspend-factory
  problem. Touching them risks scope creep.
- **Not introducing a `WeftRuntime.Builder` pattern.** Builder was
  considered (per the architecture-vision sketch) but is more surface
  area than the problem needs. The sync `create()` + internal
  deferred achieves the same outcome with fewer concepts.

## Migration plan

Single-commit migration in the SDK; reference-app cleanup in a
follow-up.

1. **SDK PR.** Merge `createWithMcpServers` into `create()`; add the
   `mcpToolsReady` deferred + lazy tool-registry materialization in
   `WeftAgent`. Existing `create()` callers (no MCP) see zero
   behavior change. New unified factory is non-suspend.
   - Compile-check: `createWithMcpServers` is referenced in
     `undercurrent/app/.../AppModule.kt:139`. SDK should keep the old
     symbol as a thin `@Deprecated` shim delegating to the new
     `create()` for one release.
2. **Reference app PR.** Switch to the new `create()`; delete the
   `runBlocking` at `:139`. Verify cold-start time drops by the
   expected 500–1500ms.
3. **Cleanup PR.** Remove the deprecated `createWithMcpServers` shim
   after the reference app has migrated. Update
   [follow-ups.md](../follow-ups.md) and
   [architecture-vision.md](../architecture-vision.md).

## Open questions

- **What if MCP discovery never completes?** A misbehaving server
  could hang the deferred forever, blocking every `send()`. Today
  `HttpMcpClient` has implicit timeout behavior via OkHttp defaults.
  Should we wrap discovery in a hard timeout (e.g., 10s) at the
  runtime level? Proposal: yes, with the timeout exposed as a
  `createMcpDiscoveryTimeout: Duration = 10.seconds` param. On
  timeout, treat the server like any other failure — emit to
  `onMcpError`, omit its tools.
- **Caller's coroutine context for `runtimeScope`.** Today
  `runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
  Should the MCP discovery launch on `Dispatchers.IO` explicitly?
  Proposal: yes — IO-bound work, matches the
  [current behavior](../../android/src/main/kotlin/dev/weft/android/WeftRuntime.kt#L891).
- **Per-server progress reporting.** Apps may want to surface
  "Connected to Linear, connecting to Notion…" during the first
  send's wait. Out of scope for v1; the `mcpStatus: StateFlow` idea
  above is the natural extension.

## Risks

- **Hidden suspension at first send.** Today the suspension is
  visible at DI time (the host's `runBlocking` is a clear marker).
  After this change, the suspension moves to the *first send*, where
  some host that does `runBlocking { agent.send(...) }` from a
  non-coroutine site could see worse behavior than before. Mitigation:
  the SDK's `WeftAgent.send` is already suspend, so callers are
  already in a coroutine — but doc the new "first send may take
  longer if MCP discovery is still in flight" behavior in the
  factory's KDoc.
- **Tool catalog stability for prompt cache.** The system prompt's
  tool catalog block is cached (`CacheTier.STATIC` for substrate
  prelude, `SESSION` thereafter — see
  [strategy-hook.md](strategy-hook.md)). MCP tools join the catalog
  on first send, which means the first-send catalog differs from any
  "would-have-been" catalog before MCP resolved. Not a real risk
  today because no `send()` happens before MCP resolves under this
  design — but worth flagging if we ever decouple the two.
- **Backwards compatibility with apps on the published SDK.** The
  `@Deprecated` shim for `createWithMcpServers` keeps source-compat
  for one release. Binary-compat is harder if anyone reflects on the
  symbol; probably not worth defending against.
