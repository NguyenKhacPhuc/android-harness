# ADR-007: Component-tree UI protocol

**Status:** Accepted

**Date:** 2026-05-20

**Supersedes:** none

**Related:**
[ADR-003](ADR-003-compose-swiftui-strategy.md) (Compose+SwiftUI strategy),
[ADR-005](ADR-005-semantic-intent-location.md) (SemanticIntent location).

## Context

Phase 4 ([09-roadmap.md](../09-roadmap.md)) calls for the LLM to render real
UI — not just chat — for things like a 25-minute Pomodoro timer, a form,
a list of agent-curated items. Two design approaches surfaced:

**A. Static templates** — the substrate ships a fixed set of screen
templates (Timer, Form, List, Prompt, Detail) and the LLM picks one by
name with typed params. Predictable, safe, low-latency. The cost: any
screen the developer didn't anticipate can't exist.

**B. Component-tree composition** — the substrate registers atomic
components (Text, Button, Card, …) and the LLM emits a JSON tree of those
components per turn. Strictly more expressive: infinite screens from a
finite palette. The cost: bigger args, more output tokens, layout quality
depends on the LLM, action wiring needs careful design.

This is the industry pattern called **Server-Driven UI** (Airbnb DLS,
LinkedIn Hopper, Spotify HubFramework) — adapted here so the "server"
producing the UI tree is the LLM rather than a backend service.

## Decision

Ship the **component-tree model**. Templates, if we ship any, are
Kotlin factory functions that return well-known trees — convenience, not
constraint.

**Concretely:**

1. **Components are the SDK primitive.** Apps register a palette of
   `SubstrateComponent<TProps>` implementations with the substrate at
   startup. Substrate ships ~8 Tier-1 wrappers around Material 3
   components.

2. **Components wrap M3, they don't replace it.** Each substrate
   component is a small Compose function that internally renders the M3
   equivalent with a constrained, LLM-shaped props schema. The substrate
   does not ship a parallel design system.

3. **Theming is M3's job.** Apps wrap their content in their own
   `MaterialTheme(colorScheme = …, typography = …)` and every substrate
   component inherits it. The substrate ships zero theming code.

4. **The wire format is a nested JSON tree.**
   ```json
   {
     "type": "Column",
     "props": {"spacing": "md"},
     "children": [
       {"type": "Text", "props": {"text": "Focus", "variant": "h1"}},
       {"type": "Button", "props": {"text": "Start", "action": "start", "variant": "primary"}}
     ]
   }
   ```
   Each `type` must match a registered component name. Props are
   validated against the component's `paramsSerializer`. Unknown types
   render an inline error placeholder so the LLM sees the failure and
   can correct on the next turn.

5. **Two LLM-facing tools:**
   - `ui_render(tree)` — render an arbitrary component tree.
   - `ui_navigate(screen)` — render a known `ScreenSpec` (template+props),
     for app authors who prefer the typed-template path.
   Both end up as `UIUpdate.RenderTree` / `UIUpdate.Navigate` on the
   `UiBridge`; one render path.

   **Default surface: full screen, not overlay.** When `UIUpdate.RenderTree`
   lands, the substrate's `AgentRenderedTreeScreen` takes over the surface
   (the chat view is hidden). The agent's tree becomes the canvas; chat is
   for conversation. Back button clears `lastUpdate` and returns to chat.
   `AgentRenderedTreePanel` (inline-over-chat) is shipped as an alternative
   for apps that want small inline interactions, but is not the default.

6. **Events flow back as synthetic user messages.** When the user taps a
   button with `action: "reset"`, the substrate appends a system-style
   message to the next agent turn: *"User interacted with rendered UI:
   action=reset, source=Button id=… "*. This keeps the chat history
   honest, requires no special agent-loop path, and is debuggable. (See
   "Future considerations".)

7. **Mitigations for LLM-produced tree quality:**
   - **Coarse layout components** — `Column`, `Row`, `Card`; not raw
     `Box(modifier=…)`. Keeps spatial layout consistent.
   - **Token-based props** — `padding: "md"` not `padding: "12dp"`.
     Prevents the LLM from inventing aesthetic disasters.
   - **Variant strings** — `variant: "primary" | "secondary" | "text"`
     instead of arbitrary colors. Forces a small consistent vocabulary.
   - **Depth limit** — trees deeper than 6 levels are rejected with a
     structured error. Anything deeper is almost certainly the LLM
     getting confused.

## Why not static templates

The template model is simpler today but caps the substrate's ceiling.
Once an app ships and users start asking for screens the developer
didn't anticipate ("show me my last 5 journal entries as cards, sorted
by date, with the longest one expanded"), the template list becomes a
permanent backlog. Component composition treats every novel screen as a
free creation.

Templates as a *constraint* are out; templates as a *convenience* (a
Kotlin factory returning a known good tree) remain a fine option and
will be added as the substrate matures.

## Why not let the LLM ship raw Compose code

The honest dynamic-UI alternative is sending the LLM-generated
JSX/Compose snippets to the device for runtime evaluation. We refuse for
four reasons:

- **Safety** — no eval of arbitrary code at runtime. Every component the
  user can render is hand-written, reviewed, and shipped in the APK.
- **Predictability** — props schemas are enforced by `KSerializer`. The
  LLM can't sneak in a button that opens an arbitrary URL.
- **Performance** — components are pre-compiled. No tree-shaking at
  render time.
- **Theming consistency** — `MaterialTheme` applies automatically. No
  way for LLM-generated screens to drift from the app's visual language.

## Why ride Material 3

M3 ships ~50 production-grade components, all accessible, themeable,
dark-mode-aware, dynamic-color-aware. Reinventing them would be
~1000 lines of work for zero substrate-grade value. The substrate's
value is the agent protocol, not the design system. Apps that want
non-M3 looks override `MaterialTheme` (most cases) or register their own
non-M3 components (escape hatch).

This makes Android the natural first platform. iOS would parallel with
SwiftUI's system controls — same protocol, different wrappers.

## Implementation plan

Phase 4 (Android-only), 1 week:

| Day | Scope |
|---|---|
| 1 | `ComponentNode`/`UIUpdate.RenderTree` in `:contracts`; `SubstrateComponent`/`ComponentRegistry`/`TreeRenderer` in `:substrate:android`; 8 Tier-1 wrappers (Text, Button, Card, TextField, Column, Row, Spacer, Icon); `ui_render` tool; auto-list components in system prompt |
| 2 | Event protocol end-to-end: LLM renders a tree with a Button → user taps → synthetic user message → agent reacts. **Load-bearing demo day.** |
| 3 | Tier-2 wrappers (ListItem, Switch, Checkbox, Slider, Image, RadioGroup, Divider); first dynamic-tree demo ("show me my last 5 journal entries as cards") |
| 4 | `IntentRouter` default impl, error handling polish, template factory examples |
| 5 | Build + install + smoke-test; documentation pass |

## Tradeoffs accepted

- **Tokens spent on UI.** Every rendered screen costs 300–1000 output
  tokens versus ~50 for a static template. Will revisit if it becomes a
  cost issue in practice; mitigation is to ship templates for the
  common cases.
- **Layout quality variance.** The LLM's spatial sense is mediocre. The
  token/variant/coarse-layout mitigations reduce but don't eliminate
  this. Day-2 demo is the gate: if the timer looks broken, we tighten
  the system prompt with more examples before continuing.
- **Substrate has an opinion.** Every app using `:substrate:android`
  ships UI that looks like Material 3. Apps wanting a fundamentally
  different look replace components or theme aggressively. Acceptable
  given Compose's idiomatic look *is* M3.

## Future considerations

- **Server-pushed components.** Apps could fetch additional components
  at runtime from a remote registry for A/B testing or remote-config.
  Out of v1 scope.
- **Stateful overlays** (Snackbar, Dialog, BottomSheet) — Tier-3
  components, deferred. The current `ui_dialog` and
  `PendingRequestRenderer` cover the common cases.
- **Better event protocol.** If synthetic user messages get noisy, swap
  for a `tool_result`-style channel that's visible to the agent loop but
  hidden from the user-facing chat transcript.
- **Template factory layer.** Once the component palette stabilizes,
  ship 5 Kotlin factories (`Timer`, `Form`, `List`, `Prompt`, `Detail`)
  that return canonical trees for common cases. LLM can call them by
  name or compose freely.

## Consequences

**Positive:**
- The substrate's ceiling for LLM-driven UI is no longer "5 templates."
- Apps add new agent-renderable surfaces by writing one component, not
  by editing the substrate.
- The same protocol works for iOS later via SwiftUI wrappers.
- Theming is free via `MaterialTheme`.

**Negative:**
- Higher per-turn token cost for UI-heavy interactions.
- Day-2 load-bearing demo carries real "does the LLM compose decent
  trees?" risk; the rest of the phase depends on it landing cleanly.
- Substrate adopts Material 3 as a hard dependency — backing out would
  be expensive.

**Neutral:**
- Existing `ScreenSpec`/`UIUpdate.Navigate` survives unchanged — the
  template path is still supported, just no longer the primary surface.
