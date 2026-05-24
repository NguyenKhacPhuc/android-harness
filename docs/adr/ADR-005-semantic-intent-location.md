# ADR-005 — `SemanticIntent` and `IntentRouter` live in `design-system-api`, not `contracts`

- **Status:** Accepted
- **Date:** 2026-05-19
- **Deciders:** Tech lead + designer
- **Supersedes:** —

## Context

`SemanticIntent` is what the LLM emits when it wants to show UI: `ShowData`,
`Countdown`, `Reflect`, etc. `IntentRouter` maps intents to `UIUpdate`s
(usually navigating to a `ScreenSpec`, sometimes an overlay, sometimes
nothing). Both types could plausibly live in `contracts` (so any module can
reference them) or in `design-system-api` (since they are visual-layer
concepts).

`contracts` (Layer 1) is the substrate's shared vocabulary. Everything depends
on it. Anything that lives there imposes its concepts on every module —
including `core`, `os-bridge`, `security`, all the harness modules.

`design-system-api` (Layer 2) is the visual API surface. Modules that don't
do UI work don't need to know it exists.

`SemanticIntent` and `IntentRouter` are visual concepts. The harness doesn't
need to know about `Density` or `Emphasis` or `CountdownStyle`. The script
catalog doesn't either.

## Decision

`SemanticIntent`, `IntentRouter`, `RenderContext`, `IntentAction`, and the
related supporting types (`DataItem`, `InputField`, `EmptyState`, etc.) live
in `design-system-api`.

`UIUpdate`, `ScreenSpec`, and `OverlaySpec` live in `contracts` — these are
the transport types that flow between the agent loop and the rendering layer,
so the agent loop (in `core`) needs to handle them.

The split:

- `contracts.UIUpdate` — what the agent loop emits (Navigate / Replace /
  Patch / Dismiss / Overlay / None).
- `contracts.ScreenSpec` — a template id plus props. Opaque to the agent loop.
- `design-system-api.SemanticIntent` — what the LLM emits, what the router
  consumes.
- `design-system-api.IntentRouter` — the mapping function.

## Consequences

**Positive**

- `core`, the harness modules, the script catalog, and `os-bridge` can ignore
  `SemanticIntent` entirely. They only see `UIUpdate`/`ScreenSpec`.
- The visual vocabulary evolves under `design-system-api`'s ownership without
  rippling into `contracts`.
- Adding new intents or refactoring `IntentRouter`'s implementation doesn't
  force a `contracts` migration.

**Negative**

- The script that the LLM calls for UI (`ui.navigate`) takes a
  `SemanticIntent` parameter — and `scripts-core`, where most UI scripts live,
  must depend on `design-system-api` to type that parameter. This is fine
  (scripts-core can depend on design-system-api; it's a Layer 2 cross-edge),
  but it's a dependency edge worth noting.

**Neutral**

- The LLM's prompt is written in terms of `SemanticIntent`. Changing the
  intent vocabulary changes prompts; this is the leverage point for visual
  evolution.

## Alternatives considered

- **Put `SemanticIntent` in `contracts`.** Rejected: pollutes the foundation
  module with visual concepts.
- **Put `UIUpdate`/`ScreenSpec` in `design-system-api`.** Rejected:
  `AgentRuntime` in `core` needs to handle `UIUpdate` directly to emit it on
  its event channel; putting it above `core` inverts a layer.

## When to revisit

If a non-UI module discovers it genuinely needs to reason about
`SemanticIntent` (e.g., a hypothetical voice/audio output channel that picks
voice tone from `Emphasis`), the split may need to move. Not anticipated for
v1 or v1.1.
