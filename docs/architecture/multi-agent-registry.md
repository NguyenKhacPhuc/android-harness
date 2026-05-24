# Multi-agent registry

- **Status:** Design — approved direction, not yet implemented
- **Date:** 2026-05-24
- **Tracks:** `architecture-vision.md` § Active misalignments → "Multi-agent"
- **Depends on:** [strategy-hook.md](strategy-hook.md) — per-agent
  [WeftStrategy](strategy-hook.md) is a load-bearing assumption here.
- **Estimated scope:** ~1500–2000 LOC across `:harness:agents` (registry
  + delegation tool), `:android` (DI), conversation persistence
  (agent attribution), and `:android-compose-defaults` (selector UI).

## Why this exists

[WeftRuntime](../../android/src/main/kotlin/dev/weft/android/WeftRuntime.kt)
ships exactly one
[WeftAgent](../../harness/agents/src/main/kotlin/dev/weft/harness/agents/WeftAgent.kt)
today via `buildAgent()` at
[:460](../../android/src/main/kotlin/dev/weft/android/WeftRuntime.kt#L460).
Apps that want specialized agents — a "research" agent with web tools
and `BurstStrategy`, a "writer" agent with a different preamble and
fewer tools — have to fork the runtime. Sub-agents already exist
internally (via `SubAgentRunner` in `:harness:agents`), but they're not
first-class: the user can't address them, and the orchestrating LLM
can't delegate to them by name.

The goal: a host registers N agents, each with its own role, tool
allowlist, preamble fragment, and (per #5) strategy; the substrate
routes turns to the right one based on either user `@mention` syntax
or LLM-driven delegation.

## Decision

Introduce `AgentDeclaration` as the host-side registration shape, an
`AgentRegistry` in `:harness:agents` that builds + caches `WeftAgent`
instances, and a substrate-supplied `delegate_to_agent` tool that
collapses the existing `SubAgentRunner` machinery into the new model
(sub-agents become a special case of multi-agent: same registration
shape, just not user-addressable).

## Contracts

### Host-facing — `AgentDeclaration`

```kotlin
public data class AgentDeclaration(
    /** Stable identifier — used by `@mention` parsing and `delegate_to_agent`. */
    public val name: String,                    // "researcher", "writer"
    /** Display label for the agent selector / `@mention` autocomplete. */
    public val displayName: String,             // "Researcher"
    /** One-line description; surfaced in `delegate_to_agent`'s tool schema. */
    public val description: String,
    /**
     * Role preamble. Concatenated AFTER the app's `appPromptPreamble`
     * so the host's identity wins on conflict. Empty string for the
     * default/orchestrator agent.
     */
    public val systemFragment: String,
    /**
     * Tool allowlist by name (matched against `WeftTool.descriptor.name`).
     * Empty set = all tools the runtime would normally expose to a
     * default agent. Non-empty = whitelist filter applied to the
     * runtime's tool registry.
     */
    public val allowedTools: Set<String> = emptySet(),
    /** Per-agent strategy. Defaults to the host's DefaultStrategy(). */
    public val strategy: WeftStrategy = DefaultStrategy(),
    /**
     * Whether the user can address this agent directly (via `@name` or
     * the selector). false = the agent is only reachable via
     * delegation from another agent. Sub-agents register with `false`.
     */
    public val userAddressable: Boolean = true,
)
```

### Runtime API additions

```kotlin
public class WeftRuntime internal constructor(
    // ...existing...
    private val agentDeclarations: List<AgentDeclaration>,
) {

    /**
     * Map of every registered agent, keyed by [AgentDeclaration.name].
     * Includes the default agent under the name "default" when no
     * declarations name themselves that.
     */
    public val agents: Map<String, WeftAgent> by lazy { /* build map */ }

    /** Lookup; throws if the name is not registered. */
    public fun agent(name: String): WeftAgent =
        agents[name] ?: error("Unknown agent: $name. Registered: ${agents.keys}")

    /** The default/orchestrator agent. Always present. */
    public val defaultAgent: WeftAgent get() = agents.getValue(DEFAULT_AGENT_NAME)
}

public fun WeftRuntime.Companion.create(
    // ...existing...
    agents: List<AgentDeclaration> = emptyList(),
): WeftRuntime
```

When `agents` is empty, the runtime auto-creates a single
`AgentDeclaration` with name = `DEFAULT_AGENT_NAME`, no allowlist, no
fragment — preserving v1 behavior.

### Sub-agent collapse via `delegate_to_agent`

Substrate-supplied tool, registered into every agent's tool catalog by
the registry. Schema:

```kotlin
public data class DelegateArgs(
    /** Name of the target agent. Must be in the host's AgentDeclaration list. */
    public val agent: String,
    /** Task description for the delegate. */
    public val prompt: String,
    /** Optional return-shape hint ("summary", "citations", "json"). */
    public val expects: String? = null,
)
```

Description (load-bearing — see
[writing-a-custom-tool.md](../writing-a-custom-tool.md)):

> "Delegate a focused sub-task to another agent by name. Use when the
> sub-task needs specialized tools or a different persona — e.g.,
> hand off to `researcher` for citations, `writer` for long-form
> prose. NOT for navigation between user-visible screens."

The tool's `executeWeft` calls `runtime.agent(agent).send(prompt)` and
returns the delegate's final assistant text. Each `AgentDeclaration`
filters this tool out of its own catalog if it's not allowed to
delegate (controlled by another future flag; for v1, all agents can
delegate to all others).

This means **`SubAgentRunner` is deleted**; every place it's used now
goes through `delegate_to_agent`. That's part of the LOC estimate.

## Conversation attribution

Today
[ConversationStore](../../harness/conversation/src/main/kotlin/dev/weft/harness/conversation/ConversationStore.kt)
persists turns with `(role, text, conversationId)`. We need a fourth
field: `agentName: String`. This unlocks two UX wins:

1. The chat surface can label each assistant turn with the agent that
   produced it ("Researcher · 2s ago").
2. Resuming a conversation routes the *next* turn to the same agent
   that handled the last turn by default (the user can re-mention to
   override).

SQLDelight migration: add `agent_name TEXT NOT NULL DEFAULT 'default'`
to the `conversation_messages` table. Existing rows backfill to
'default'. ~30 lines of migration + ~10 lines of API change.

## Routing the user's turn

Two sources of "which agent gets this":

1. **Explicit `@name` mention** at the start of the user message. A
   small parser strips it and dispatches to `runtime.agent(name)`. Lives
   in the chat surface, not the substrate (different hosts may want
   different syntaxes). The substrate exposes a helper
   `AgentMentionParser.parse(text): Pair<String?, String>` (name, rest)
   in `:harness:agents`.
2. **Last-active-agent fallback** when no mention is present. The chat
   surface tracks the most recent assistant turn's `agentName` and uses
   it. Initial state: `DEFAULT_AGENT_NAME`.

UI sketch (`:android-compose-defaults`): a small dropdown above the
input field showing the active agent. Tap to switch. Power-user
shortcut is the `@mention` syntax.

## Module breakdown

| Module | What lands | Approx LOC |
| --- | --- | --- |
| `:harness:agents` | `AgentDeclaration`, `AgentRegistry`, `delegate_to_agent`, `AgentMentionParser`, deletion of `SubAgentRunner` | ~600 |
| `:harness:conversation` | `agentName` column + migration + API | ~80 |
| `:android` | `WeftRuntime.agents` map, `create(agents = ...)` overload, wiring registry | ~250 |
| `:android-compose-defaults` | Agent selector composable, `@mention` highlight in input field, per-turn label in chat list | ~400 |
| Tests + fixtures | Multi-agent integration test, delegation round-trip test, mention parser tests | ~300 |
| Docs | This file + an ADR codifying the decision after first ship | ~50 |

**Total: ~1700 LOC.** In the 1500–2000 range projected in
`architecture-vision.md`.

## What this is NOT doing

- **Not introducing inter-agent messaging.** Agents can delegate (call
  another agent as a tool) but cannot subscribe to each other's
  events or pass long-lived state. If a use case demands that, it's
  v2.
- **Not changing the prompt-assembly catalog structure.** Each agent
  still sees the substrate-assembled system prompt; the
  `systemFragment` is concatenated after the app preamble. No
  per-agent catalog editing.
- **Not auto-routing based on intent classification.** No "the
  substrate guesses which agent the user wants." Mention or last-active
  only. Intent-classification routing is a strategy concern that an
  app can layer on top using [WeftStrategy](strategy-hook.md).
- **Not exposing agents over MCP.** A registered agent is in-process
  only; if a host wants to expose them as MCP-serveable, that's a
  separate concern.

## Migration plan

Phased to ship behind a feature flag — risky surface area, want to
shake it out in the reference app before SDK consumers see it.

1. **Phase 1 — registry plumbing, no UX.** Add `AgentDeclaration` +
   `AgentRegistry` + `WeftRuntime.agents` map. Auto-create the default
   declaration when none provided. No `delegate_to_agent`, no UI.
   Existing code paths use `runtime.defaultAgent` everywhere
   `runtime.agent` was used. Tests: existing suite passes; new test
   verifies an empty `agents` list produces a working default.
2. **Phase 2 — delegation.** Add `delegate_to_agent` tool. Delete
   `SubAgentRunner` and migrate its call sites. This is where most of
   the regression risk lives — the existing sub-agent paths need
   careful test coverage before deletion.
3. **Phase 3 — conversation attribution.** SQLDelight migration; chat
   surface labels assistant turns.
4. **Phase 4 — user UX.** `@mention` parser; agent selector composable
   in `:android-compose-defaults`. Reference app (Undercurrent) ships
   a 2-agent config ("default" + "writer") to dogfood.

Each phase is independently shippable; the v1.1 release can include
any prefix of these.

## Open questions

- **Per-agent memory scope?** A "writer" and a "researcher" may want
  separate memory namespaces (the writer doesn't care about the
  researcher's URL bookmarks). Today
  [MemoryStore](../../harness/memory/src/main/kotlin/dev/weft/harness/memory/MemoryStore.kt)
  is single-namespace. Punting: v1 has one shared memory. Apps that
  need partitioning can do it at the tool layer (e.g., agent prefixes
  every memory tag with its own name).
- **Cost attribution.** Per-agent USD breakdown would be useful for
  cost screens — needs UsageStore to also gain an `agent_name` column.
  Cheap once we're already adding it to conversation. Worth including
  in Phase 3.
- **Delegation depth limit.** Agent A → delegates to B → delegates to
  C → ... can run away. Propose: hard cap at 3 levels, with the limit
  enforced by `delegate_to_agent`'s `executeWeft`. Aligns with the
  existing `maxAgentIterations` philosophy: bounded recursion.
- **Streaming during delegation.** The orchestrator's turn streams
  text; when it delegates, the delegate's turn also streams. UX
  question: do we show both streams interleaved, or buffer the
  delegate and stream the orchestrator's wrap-up text? Default
  proposal: interleave, labeled by agent. Re-evaluate after the
  reference-app dogfood in Phase 4.

## Risks

- **Sub-agent regression.** Phase 2 deletes `SubAgentRunner`. Any
  existing path that imports it breaks. Mitigation: integration test
  covering every current sub-agent invocation before the delete; ship
  Phase 2 in a feature branch with the reference app green.
- **Tool-catalog confusion.** Adding `delegate_to_agent` is another
  tool the LLM sees. Per the
  [tool-authoring rules](../writing-a-custom-tool.md), the name,
  description, and neighbor disambiguation need to be tight or the LLM
  will misroute. Specifically: it must NOT be selected when the user
  asks the orchestrator a question the orchestrator can handle.
- **Conversation-store schema change.** SQLDelight migration on a
  table users have data in. Standard migration risk; we own the
  test path.
- **Prompt-cache invalidation per agent.** Each agent has its own
  system fragment, so each agent has its own cache prefix. Apps with
  many agents will see worse cache hit rates. Document as a known
  trade-off; don't try to clever-share across agents.
