# ADR-006 — Koog as the agent foundation

- **Status:** Accepted
- **Date:** 2026-05-20
- **Deciders:** Tech lead
- **Supersedes:** Implicit "direct Anthropic transport" choice that existed before this ADR

## Context

The original plan (`docs/02-architecture.md:78`) said:

> Koog handles the mechanics. The substrate's contribution is wrapping it
> with the harness chain and connecting tool results into the script/UI
> catalogs.

The first cut of the substrate diverged from this. It implemented a
hand-rolled `DirectAnthropicClient` (Ktor against `api.anthropic.com`), a
custom `AgentRuntime` iteration loop, and a substrate-specific `Script`
interface with `JsonObject` parameters. Each piece worked, but each piece
also accumulated wire-format gotchas (tool-name dotted-namespace encoding,
`required` array shape, `tool_result.content` string wrapping, model-id
acceptance) that Koog already solves.

Three migration paths were considered:

1. **Keep direct-Anthropic, write an ADR superseding the Koog choice.**
   Lowest risk; preserves existing tested code; loses multi-provider future.
2. **Wrap Koog's executor as an `LLMClient` impl alongside the direct one.**
   Modest complexity reduction; introduces two paths to maintain.
3. **Full Koog migration: tools become `Tool<Args, Result>` subclasses, the
   loop becomes `AIAgent.run(...)`.** Honors the plan; biggest complexity
   reduction; breaking change to the `Script` interface.

## Decision

Adopt Koog fully (option 3).

- The `LLMClient` / `LLMMiddleware` / `Script` / `ToolDispatcher` /
  `AgentRuntime` abstractions are deleted.
- Tools subclass `SubstrateTool<TArgs, TResult>` (in `:scripts:core`), which
  itself extends `ai.koog.agents.core.tools.Tool`. Tools declare typed
  `@Serializable` Args data classes; Koog generates the JSON schema for
  Anthropic from the serializer + a manually-listed `ToolDescriptor`.
- The agent loop is `ai.koog.agents.core.agent.AIAgent`. Single-turn
  invocations via `agent.run(input: String): String`.
- Transport is `AnthropicLLMClient` from `ai.koog:koog-agents:0.8.0`,
  wrapped in `SingleLLMPromptExecutor` (or `MultiLLMPromptExecutor` for
  future multi-provider).
- Substrate-specific concerns (destructive gate, permission gate) live in
  `SubstrateTool`'s `final override execute()`, which runs the gates and
  then delegates to subclass `executeSubstrate(args)`.

Adopted Koog version: **0.8.0** (April 2026 release).

## Consequences

### Deleted (~2500 lines)

- `core/AgentRuntime.kt`, `SimpleScriptDispatcher.kt`, `ScriptRegistry.kt`,
  `Conversation.kt`, `AgentRuntimeTest.kt`.
- `scripts/core/ScriptExecutor.kt` and its tests.
- All 5 original `*Script.kt` files and their tests.
- `contracts/Script.kt`, `LLMClient.kt`, `LLMMiddleware.kt`,
  `ToolDispatcher.kt`, `ToolDescriptor.kt`, `ContentBlock.kt`, `Message.kt`,
  `Model.kt`.
- Test fixtures: `MockLLMClient.kt`, `MockMiddleware.kt`, `MockScript.kt`.
- Intermediate Stage-1 adapter: `KoogAnthropicClient.kt`,
  `SchemaConverter.kt`, `ToolNameEncodingTest.kt`.
- Tool-name `.` → `__` encoding shim (tools now use underscore names directly).

### Positive

- One framework owns the wire format, retries, tool serialization, and the
  agent loop. We own only the substrate-specific tool logic and the UI
  bridge.
- Multi-provider story is unblocked: swap `simpleAnthropicExecutor` for
  `simpleOpenAIExecutor` / `simpleOllamaAIExecutor` etc. No further work
  needed in `:scripts:core`.
- Tool schemas are derived from `@Serializable` data classes — no more
  hand-rolled JSON Schema gotchas.
- The plan's `02-architecture.md:78` is now actually true.

### Negative

- **Lost per-turn conversation history at the substrate level.** Koog
  0.8.0's `AIAgent.run(input)` is single-turn — Claude doesn't see prior
  sends. Multi-turn memory has to be re-introduced through Koog's
  `prompt`/history API (deferred).
- **Lost direct token-usage visibility.** Koog handles usage internally;
  surfacing it requires installing `handleEvents { ... }` features.
- **Coupled to Koog API stability.** Koog 0.8.0 is young (per R4 in
  `docs/11-risks-and-mitigations.md`). Breaking changes in 0.9.x are
  plausible and would force code changes.
- **The harness plan needs rewriting.** Our `LLMMiddleware` chain is gone.
  Koog's equivalent is its "feature" system (`handleEvents`, etc.). The
  v1 harness modules (`:harness:reliability`, `:harness:quality`, etc.)
  must be re-architected as Koog features, not middlewares. Phase 2.5
  scope changes accordingly.

### Neutral

- The `Tool<Args, Result>` constructor signature in Koog 0.8.0 takes a
  pre-built `ToolDescriptor` with manually-listed parameters. We list them
  by hand in each tool (~5 lines per tool) rather than generating them
  from the serializer. Koog's `typeToken`-based factory would generate
  them automatically; we may switch when its API stabilizes.

## Alternatives considered

- **Option 1 (direct-only).** Rejected: drifts further from the plan, no
  multi-provider story, we maintain wire-format edge cases forever.
- **Option 2 (Koog alongside direct).** Rejected: two paths, both need
  maintenance, no real complexity win.

## Implementation notes

The migration was staged:

- **Stage 1** (one turn) — `KoogAnthropicClient` implements `LLMClient`
  by wrapping `simpleAnthropicExecutor`. Direct client deleted. Everything
  else stays. Multi-provider unlocked but loop still substrate-owned.
- **Stage 2 + Stage 3** (one turn, combined) — `Script` →
  `SubstrateTool<Args, Result>`. `AgentRuntime` → `AIAgent`. All five tools
  rewritten with typed Args. `KoogAnthropicClient` removed (now redundant —
  `AnthropicLLMClient` is used directly).

The Stage-2 tool descriptors hand-list parameters (instead of using
`typeToken`) because Koog 0.8.0's `Tool` constructor #3 (the one taking
serializers + descriptor) was the path of least friction. If Koog stabilizes
the `typeToken`-based factory (constructor #2) in a future release, we
should switch and drop the manual descriptors.

## When to revisit

- **Koog reaches 1.0** or major API stability commitment → re-evaluate
  whether we can drop manual descriptors and use annotation-based tools.
- **Multi-provider becomes a real requirement** → wire a
  `MultiLLMPromptExecutor` with OpenAI / Ollama clients; verify our tools
  work against non-Anthropic providers.
- **A concrete Koog bug blocks shipping** → re-evaluate Option 1 (return
  to direct transport with Koog only as a tool-loop library).

## See also

- `docs/02-architecture.md` — original Koog-based plan
- `docs/04-locked-interfaces.md` — pre-migration interfaces (now obsolete;
  documents what was deleted)
- `docs/11-risks-and-mitigations.md` R4 — anticipated this migration risk
- `docs/13-v1.1-backlog.md` — multi-provider work now near-zero effort
