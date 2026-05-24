# Tool provider — per-agent prompt scoping + lazy materialization

- **Status:** Design — approved direction, not implemented
- **Date:** 2026-05-25
- **Tracks:** Token-cost-per-turn leak in the multi-agent registry +
  forward-looking accommodation for rapidly growing tool catalogs
  (especially via MCP integrations).
- **Estimated scope:** Stage 1 ~50 LOC. Stage 2 ~600 LOC + a Koog
  probe (1 hour) before committing.

## Why this exists

Two distinct problems, same root cause: **the substrate eagerly
assembles a single tool catalog at runtime construction and embeds
that catalog into every agent's system prompt, regardless of which
tools that agent can actually call.**

### The immediate leak

[WeftRuntime.buildAgentForDeclaration](../../android/src/main/kotlin/dev/weft/android/WeftRuntime.kt#L724)
filters tools for the wire-level `ToolRegistry` via
`AgentDeclaration.allowedTools`, but the system prompt comes from
`resolvedSystemPrompt()` which calls
[assembleSystemPrompt](../../harness/prompt/src/main/kotlin/dev/weft/harness/prompt/SystemPrompt.kt)
against the **full** tool list:

```kotlin
// In buildAgentForDeclaration — current shape
val agentTools = if (declaration.allowedTools.isEmpty()) {
    resolvedToolsAll
} else {
    resolvedToolsAll.filter { it.descriptor.name in declaration.allowedTools }
}
// ...
val baseSystemPrompt = resolvedSystemPrompt()  // uses ALL tools, not agentTools
```

A writer agent that declares `allowedTools = setOf("memory_store",
"memory_recall")` cannot call camera, calendar, network, or any
other substrate tool — but its system prompt still describes all
~50 of them. Token waste is roughly the catalog size (~3-4KB
markdown) every turn, which on Anthropic's cache-eligible content
is ~750-1000 input tokens per turn even with cache hits. Worse:
when the prompt changes between agents (the catalog stays the same
but the role fragment differs), cache prefix invalidates and the
catalog re-encodes uncached.

This is a strict regression vs. the design intent of multi-agent —
the whole point of `allowedTools` was to give specialized agents a
tighter footprint.

### The forward-looking problem

MCP integrations expand the universe. One Linear server adds 5-10
tools; a Notion server adds another 8; a Drive integration 5+;
substrate tools already number ~50. A host with 4 integrations
plus the substrate prelude could reach 80+ tools in the catalog.
At that point even a non-multi-agent prompt is paying tens of
thousands of tokens per turn for descriptions of tools the LLM
won't touch in this conversation.

The eager-catalog model doesn't scale past ~50 tools. The catalog
needs to become a *retrievable* surface, not a constantly-embedded
one.

## Decision

Two stages, shipped independently. Stage 1 is a one-line behavior
fix; Stage 2 is the architectural change.

## Stage 1 — per-agent prompt scoping (small)

`buildAgentForDeclaration` regenerates the system prompt against
`agentTools` (the filtered list), not the full resolved list. New
internal helper on `WeftRuntime`:

```kotlin
// Internal, called only from buildAgentForDeclaration.
private suspend fun systemPromptFor(
    tools: List<WeftTool<*, *>>,
): String = assembleSystemPrompt(
    appPreamble = appPromptPreamble,
    tools = tools,
    components = componentMetadata,
    dataSources = rawDataSources,
    extraNotes = systemPromptExtraNotes,
)
```

In `buildAgentForDeclaration`:

```kotlin
// Replace `val baseSystemPrompt = resolvedSystemPrompt()` with:
val baseSystemPrompt = if (declaration.allowedTools.isEmpty()) {
    resolvedSystemPrompt()  // unchanged: full catalog, cached
} else {
    systemPromptFor(agentTools)  // per-agent catalog, not cached across agents
}
```

### What changes observably

- A writer agent with 2 allowed tools sees a system prompt with 2
  tools described, not 50. Per-turn tokens drop proportionally.
- Default agent (empty allowlist) keeps today's full-catalog
  behavior bit-for-bit. Zero behavior change for single-agent apps.

### Cache implications

- Per-agent prompts no longer share a cached prefix. If the user
  bounces between "default" and "writer", neither benefits from the
  other's cache hits. For multi-agent hosts the wash is favorable —
  the catalog savings dominate the cache loss — but worth flagging.
- `resolvedSystemPrompt()` cache (the existing
  `cachedResolvedSystemPrompt` field) only covers the full-catalog
  path. Per-agent prompts compute fresh each `buildAgent` call.
  Acceptable because `buildAgent` is the slow path
  (provider/key/model change events); turn-loop hot path uses the
  closure captured at agent-build time.

### Tests

Add `WeftRuntimeAgentScopingTest` to `:android:src/test`. Two
agents registered, distinct `allowedTools`. Assert each agent's
constructed `WeftAgent` was supplied a prompt that contains only
its allowed tool names. Cheap to write; locks the contract.

## Stage 2 — `ToolProvider` + discovery meta-tool

Stage 1 plugs the leak but doesn't address scale. Stage 2
introduces lazy materialization + LLM-driven discovery.

### Contract

```kotlin
public interface ToolProvider {
    /**
     * Lightweight metadata for every tool this provider can produce.
     * Reading this is O(catalog) and side-effect-free — runs on the
     * hot path of system-prompt assembly + find_tool's search.
     */
    public val available: List<ToolMetadata>

    /**
     * Materialize a tool by descriptor name. Called once per (agent,
     * tool) tuple at agent-build time. May return null if the tool
     * was advertised in [available] but isn't currently constructible
     * (e.g., a network-dependent tool with no connection — return null
     * + log, don't throw).
     */
    public suspend fun resolve(name: String, ctx: WeftContext): WeftTool<*, *>?
}

public data class ToolMetadata(
    public val name: String,
    public val description: String,
    /**
     * Optional category — used by find_tool for grouped search results
     * ("show me memory tools"). Free-form string; suggested taxonomy
     * documented in docs/writing-a-custom-tool.md.
     */
    public val category: String? = null,
    /**
     * Whether this tool's full descriptor goes into every prompt
     * (always-on) or is hidden until find_tool surfaces it.
     * Default false — most tools are on-demand once we have discovery.
     */
    public val alwaysOn: Boolean = false,
)
```

`WeftRuntime.create` gains:

```kotlin
public fun create(
    // ...existing...
    toolProvider: ToolProvider = EagerToolProvider(/* today's prebuilt list */),
): WeftRuntime
```

Default = `EagerToolProvider` that wraps the current eager construction.
Existing apps see zero change.

### Always-on core

A small set of tools is `alwaysOn = true` — guaranteed to be in every
prompt regardless of agent or discovery state. Suggested:

- `memory_store`, `memory_recall`, `memory_compact` — apps rely on
  the LLM remembering to use these without being told. Hiding them
  breaks the explicit-memory contract from ADR-002.
- `system_user_context` — substrate-level introspection; should
  always be visible.
- `find_tool` — the discovery meta-tool itself (see below).
- App-supplied "core" tools (apps tag their own with
  `alwaysOn = true` when they're essential to the host's identity —
  e.g., Undercurrent's navigation tools).

Everything else (camera, calendar, BLE, vision, MCP tools, app-specific
domain tools) is on-demand.

### `find_tool` discovery meta-tool

Substrate-supplied tool registered into every agent's catalog when
`ToolProvider` has any on-demand tools.

```kotlin
data class FindToolArgs(
    val query: String,
    val category: String? = null,
    val limit: Int = 8,
)
```

Description (load-bearing per
[writing-a-custom-tool.md](../writing-a-custom-tool.md)):

> "Find tools available beyond the ones listed above. Use when you
> need to do something the listed tools don't cover — camera, file
> I/O, calendar, network, integrations, etc. Returns matching tool
> names + descriptions; after calling this, the matching tools
> become available for you to call in this turn."

Body:

1. Score every `available` entry against the query (substring on
   name + description, optionally category-filtered).
2. Return top-N matches as a formatted string (JSON or markdown).
3. **Side-effect:** mark those tools as "active for this turn" via
   a `ToolActivation` coroutine-context element. The runtime's
   per-turn agent builder (`buildAgentForThisTurn` in `WeftAgent`)
   reads this and unions the activations into the registry for the
   next iteration.

### The Koog mid-turn registry mutation question

This is the implementation risk Stage 2 hinges on. Two paths:

**Path A — rebuild agent mid-iteration.** The agent loop's
`maxAgentIterations` runs N LLM calls in one turn. Today each
iteration uses the same `AIAgent`. If we can intercept the
between-iterations point and rebuild the `AIAgent` with an
expanded `ToolRegistry`, find_tool's effect propagates within the
same turn (single user message → single final reply).

**Path B — defer to next turn.** find_tool's result tells the LLM
"tools loaded — issue your follow-up request and they'll be
available." The runtime remembers activations and applies them at
the next `send`. UX cost: every novel tool category requires two
user-visible turns instead of one.

**Probe needed before committing.** ~1 hour to spike whether
Koog's `AIAgent.toolRegistry` is mutable mid-loop, or whether
`AIAgent` can be reconstructed cheaply enough mid-`run()` to do
Path A without breaking trace IDs or streaming state. If Path A is
clean, Stage 2 is straightforward; if it's not, Path B is the
fallback and the LLM-facing description of find_tool must be
honest about the two-turn cost.

### App-side registration

Apps move from `extraToolsFactory: (WeftContext) -> List<WeftTool>` to
either:

```kotlin
// Eager (back-compat shim — equivalent to today)
toolProvider = EagerToolProvider(extraToolsFactory(toolContext))

// Lazy (new)
toolProvider = compositeToolProvider(
    SubstrateToolProvider(),  // built-in tools
    AppToolProvider(/* ... */),  // host's tools, metadata-only until resolve
    McpToolProvider(mcpToolsReady),  // MCP tools surface here
)
```

A `compositeToolProvider` helper composes multiple providers; conflicts
(same name in two providers) throw at construction.

### MCP integration

MCP tools are the canonical case for on-demand. Today they're loaded
eagerly through `extraToolsFactory` (see Phase 2.1 — `mcpToolsReady`
populates them). Under Stage 2:

- `McpToolProvider` reads `mcpToolsReady` and exposes the discovered
  tools as `ToolMetadata` (qualified names like `linear:create_issue`,
  descriptions from the MCP `tools/list` response).
- Tools materialize via `resolve` on demand; `McpRemoteTool`
  instances are cached per-name after first resolve.
- Default `alwaysOn = false` — MCP tools surface only through
  `find_tool` discovery. Hosts can override per-server if they want
  one integration's tools always visible.

This is the biggest single token-cost win: a host with 4 MCP
integrations no longer pays for 30+ tool descriptions in every prompt.

## What this is NOT doing

- **Not removing `AgentDeclaration.allowedTools`.** Per-agent
  hard-limits still apply on top of provider availability. An agent
  with `allowedTools = setOf("memory_store")` can call only
  `memory_store` even if find_tool surfaces other names.
- **Not introducing per-tool authorization.** Find_tool's results are
  "what's available in this provider"; whether the user has granted
  permissions to invoke them is the substrate's existing
  Permission gate, unchanged.
- **Not enabling hot-reload of tool catalogs.** Apps add tools at
  runtime construction. Mid-process addition (plugin install, feature
  flag enable) is out of scope — would require a mutable provider
  with a `changes: Flow<...>` signal and per-turn re-discovery.
- **Not changing the wire-level tool-call protocol.** Tools are still
  serialized to Anthropic's `tools: [...]` API the same way; only the
  set varies.

## Migration plan

### Stage 1 (one PR)

1. Add `systemPromptFor(tools)` helper in `WeftRuntime`.
2. Branch in `buildAgentForDeclaration` — full vs filtered.
3. Test in `:android:src/test/.../WeftRuntimeAgentScopingTest.kt`
   asserting per-agent prompt content.
4. No host-app changes required.

### Stage 2 (probe → spec → ship)

1. **Probe** (~1 hour) — spike Koog mid-loop registry mutation.
   Decide Path A vs Path B. Update this doc with the answer.
2. **Spec** (~1 day) — add `ToolProvider` + `ToolMetadata` +
   `EagerToolProvider` + `compositeToolProvider`. No behavior
   change yet; `WeftRuntime.create` accepts the new param with
   `EagerToolProvider(prebuilt)` default.
3. **Discovery** (~1 day) — implement `find_tool` + the activation
   propagation (Path A or B per probe). Register into every agent
   with on-demand tools.
4. **MCP migration** (~½ day) — split `McpToolProvider` out;
   default to on-demand.
5. **Substrate refactor** (~½ day) — rewrite the eager `tools`
   block in `WeftRuntime` as `SubstrateToolProvider` with metadata.
   Tag the always-on subset.
6. **Reference app dogfood** (~½ day) — Undercurrent switches to
   the new provider shape. Verify token reduction in trace viewer.

## Open questions

- **Find_tool ranking quality.** Naive substring matching probably
  works for v1; if it doesn't, swap in a small embedding model
  (Anthropic has cheap embedding endpoints) or punt to FTS5 against
  the metadata. Defer until we see real misses.
- **Per-conversation activation persistence.** Should activations
  from find_tool persist across turns within a conversation
  (sticky), or reset every turn (clean)? Sticky is more
  conversational ("the LLM remembers the camera is loaded"); clean
  is cheaper to reason about. Likely sticky — track via
  `ConversationStore` metadata or a session-scope coroutine
  context.
- **App-side categories taxonomy.** Free-form strings are flexible
  but lead to drift ("memory" vs "Memory" vs "memories"). Worth
  documenting a suggested list in
  [writing-a-custom-tool.md](../writing-a-custom-tool.md).
- **Always-on threshold.** How many tools is "small enough to
  always include"? Heuristic: 5-10 max, totaling under ~1KB of
  description. Anything past that loses the token-saving benefit.

## Risks

- **Stage 2's mid-turn registry mutation could be impractical.**
  If Koog forces Path B (defer to next turn), every novel tool
  category becomes a two-turn UX. That's worse for chat-driven
  agents than today's eager catalog. Probe before committing.
- **LLM under-uses `find_tool`.** If the LLM doesn't reach for
  the discovery meta-tool when it should, it'll either fail tasks
  or fall back to suggesting the user open the app manually. The
  tool's description has to be tuned — same as every other soft-
  attention tool. Worth a `Log.d` on entry to count invocations
  during early testing.
- **Cache invalidation explosion.** If each agent gets its own
  per-prompt catalog (Stage 1) AND tools activate dynamically
  (Stage 2), the system-prompt cache prefix becomes per-(agent,
  activation-set). Anthropic's prompt cache works best with stable
  prefixes; we'll see lower hit rates. Net effect should still be
  positive because the prompts are smaller, but worth measuring.
- **App-author confusion.** Today's `extraToolsFactory` is dead
  simple. `ToolProvider` is more contract surface. The
  back-compat `EagerToolProvider` shim is important — most apps
  shouldn't need to know about lazy resolution at all.
