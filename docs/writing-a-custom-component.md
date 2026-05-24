# Writing a custom UI component

The substrate ships 20 components ([reference](ui-components.md)) — but every
app eventually needs widgets the substrate doesn't have. Maybe a recorder, a
heart-rate chart, a recipe-step navigator. This guide walks through how to
write one and register it so the agent can summon it like any built-in.

The pattern is the same one used by every substrate-provided component, so
this also works as a deeper read-through of how the SDK extension point is
shaped.

---

## Anatomy of a `SubstrateComponent`

Three pieces:

1. **A typed props data class.** kotlinx.serialization-annotated. This is the
   schema the LLM will fill in.
2. **A class extending `SubstrateComponent<TProps>`** with a `name`,
   `description`, `propsSerializer`, and a `@Composable Render(...)` method.
3. **Registration** — pass an instance to `extraComponents` on
   `SubstrateRuntime.create`.

That's it. No subclassing chains, no DI scaffolding, no special build steps.

---

## A concrete example: a `Quote` component

Say your app displays inspirational quotes, and you want the agent to be
able to render one nicely:

> *"Show me a daily quote: 'The only way out is through.' — Robert Frost"*

### 1. Define the props

```kotlin
package com.example.myapp.ui.components

import kotlinx.serialization.Serializable

@Serializable
data class QuoteProps(
    val text: String,
    val author: String = "",
)
```

### 2. Implement the component

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mas.substrate.android.ui.components.ComponentEvent
import dev.mas.substrate.android.ui.components.SubstrateComponent

class QuoteComponent : SubstrateComponent<QuoteProps>(
    name = "Quote",
    description = "A formatted quotation. Required: text. Optional: author.",
    category = ComponentCategory.DISPLAY,        // required — pick the primary intent
    propsSerializer = QuoteProps.serializer(),
    // Optional but recommended — see "Structured guidance fields" below.
    example = """{"type": "Quote", "props": {"text": "The only way out is through.", "author": "Robert Frost"}}""",
) {
    @Composable
    override fun Render(
        props: QuoteProps,
        children: @Composable () -> Unit,
        onEvent: (ComponentEvent) -> Unit,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "“${props.text}”",
                style = MaterialTheme.typography.headlineSmall,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (props.author.isNotBlank()) {
                Spacer(modifier = Modifier.padding(4.dp))
                Text(
                    text = "— ${props.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
```

### 3. Register at app startup

```kotlin
val substrate = SubstrateRuntime.create(
    context = appContext,
    uiBridge = uiBridge,
    appPromptPreamble = "...",
    extraComponents = listOf(QuoteComponent()),
)
```

That's the whole loop. On next launch:

- `Quote` appears in the system-prompt component catalog with its description.
- The LLM can render trees like:
  ```json
  {"type": "Quote", "props": {"text": "The only way out is through.", "author": "Robert Frost"}}
  ```
- The `TreeRenderer` finds your component in the registry, deserializes props, calls `Render`, displays the result.

---

## Rules of thumb

**Keep props small.** 1–4 props per component. Anything you'd otherwise expose
as a layout param (margin, elevation, custom colors) — bake into the component
or expose as a `variant` string. Token-based props (`"md"`, `"primary"`) keep
the LLM consistent; freeform numbers / colors don't.

**Use `name` carefully.** It's the public API for the LLM. `Quote` is fine.
`MyAppQuoteWidget` is bad — wastes tokens, makes prompts harder to read. Names
collide with substrate defaults by `name` (last-wins), so apps can also
override `Button` etc. with their own.

**Owning state? Use Compose's `remember` + `LaunchedEffect`.** The component
runs inside the substrate's tree renderer; it's just a normal Composable.
See [Timer](ui-components.md#timer)'s implementation in
`substrate/android/.../Macro.kt` for a worked example of stateful
behavior.

**Need to render children selectively (tabs, carousels, switchers)?** Use
the two `CompositionLocal`s the renderer provides:

```kotlin
@Composable
override fun Render(props, children, onEvent) {
    val node = LocalCurrentNode.current               // your own ComponentNode
    val renderNode = LocalNodeRenderer.current        // @Composable (ComponentNode) -> Unit

    var selected by remember { mutableIntStateOf(0) }
    Column {
        // ...your tab bar / control surface...
        // Render only the selected child:
        node.children.getOrNull(selected)?.let { renderNode(it) }
    }
}
```

The default `children` lambda renders all of them in order — fine for
Column/Row/Card. Switcher components ignore it and recurse manually via
`renderNode`. The `Tabs` component is the worked example.

**Emit events sparingly.** The agent pays a turn for every `ComponentEvent.Action`
you fire. Local state changes belong inside the component (`remember`).
Round-trip only when the user has reached a semantic conclusion (timer
complete, form submitted, choice confirmed).

**Validate inputs gracefully.** kotlinx.serialization will throw if the LLM
sends malformed props — the renderer catches and shows an inline error
placeholder. For props that pass type-checking but are semantically wrong
(empty option list on a Picker, negative duration on a Timer), render a
sensible fallback rather than crashing.

**Document the LLM-facing description carefully.** That `description` string
goes verbatim into the system prompt. Keep it focused on **what** the
component is. Put layout constraints and canonical examples in the
dedicated fields — they format consistently in the prompt and force you to
think about each dimension up front. See the next section.

## Structured fields you set on `SubstrateComponent`

| Field | Purpose | Required? |
|---|---|---|
| `name` | LLM-facing component name; what appears as `"type"` in JSON trees. | yes |
| `description` | One-sentence "what is this." Goes verbatim into the system prompt. | yes |
| `category` | One of `DISPLAY`, `LAYOUT`, `ACTION`, `INPUT`, `MACRO`, `EMBED`. Drives how the catalog is grouped in the system prompt and signals to LLMs what to expect. | **yes** |
| `propsSerializer` | `KSerializer<TProps>` — usually `MyProps.serializer()`. Drives prop deserialization + pre-render validation. | yes |
| `layoutNotes` | Layout / placement constraints (e.g., "needs 360dp width", "render at top level"). Surfaced as `Layout: …` in the prompt. | optional |
| `example` | Canonical JSON tree snippet. Surfaced as `Example: …`. Highly recommended for non-trivial components — the LLM is much more reliable with a concrete shape. | optional |

### Picking a category

Choose the *primary* intent:

| Category | When to pick it |
|---|---|
| `DISPLAY` | Renders content. No state, no events. (Text, Icon, Chart, Avatar…) |
| `LAYOUT` | Arranges children. No content of its own. (Column, Card, Grid…) |
| `ACTION` | Fires an event when interacted with. The agent receives an `action` key. (Button, IconButton, ListItem-with-action…) |
| `INPUT` | Captures user data. Maintains local state. Declares an `id`. (TextField, Slider, DatePicker…) |
| `MACRO` | Behaviorally-complete widget — bundles primitives + state + controls. One semantic event back to the agent. (Timer, Form, Picker…) |
| `EMBED` | Wraps external content. (WebView, future Video/Audio players.) |

If a component straddles two categories, pick the one that matches its *primary load-bearing behavior*. ListItem-with-action lands in `ACTION` because the tap matters more than the row display; Chip with filter variant lands in `ACTION` because most chip use is the simpler assist/suggestion variant.

A complete entry looks like:

```kotlin
class QuoteComponent : SubstrateComponent<QuoteProps>(
    name = "Quote",
    description = "A formatted quotation.",
    propsSerializer = QuoteProps.serializer(),
    layoutNotes = "Renders edge-to-edge; place at top level for impact.",
    example = """{"type": "Quote", "props": {"text": "...", "author": "..."}}""",
) { ... }
```

The LLM sees:

```
- Quote: A formatted quotation.
    Layout: Renders edge-to-edge; place at top level for impact.
    Example: {"type": "Quote", "props": {"text": "...", "author": "..."}}
```

**When to use each:**
- `description`: every component.
- `layoutNotes`: only if your component has real placement constraints
  (needs minimum width, must be top-level, can't nest, etc.). Don't pad
  with obvious info — empty is fine.
- `example`: highly recommended for any non-trivial component. The LLM is
  much more reliable when it has a concrete shape to model on.

---

## Where custom components live

```
apps/yourapp/android/src/main/kotlin/com/yourcompany/yourapp/ui/components/
    QuoteComponent.kt
    RecorderComponent.kt
    HeartRateChartComponent.kt
```

Or, if a family of components is reusable across multiple of your apps, factor
them into a shared module:

```
shared/components/src/main/kotlin/com/yourcompany/shared/components/
    QuoteComponent.kt
```

…and depend on it from each app.

---

## When to ship a new component vs. compose primitives

If you can write the screen using existing components and the LLM produces
trees of acceptable visual quality, **don't ship a new component**. Component
sprawl is real — every new component is more system-prompt tokens, more
testing surface, more code to maintain.

A new component is justified when one of these is true:

- **The behavior is stateful** and would otherwise require an agent round-trip
  per interaction (timers, animations, recordings).
- **The use case repeats** across many screens or apps (worth promoting from
  ad-hoc primitives to a named widget).
- **The implementation needs platform APIs** the LLM can't compose
  (camera, microphone, sensors).
- **The visual is bespoke** in a way that primitives can't match (charts,
  custom illustrations, branded animations).

For everything else, prefer composing the built-in primitives.

---

## See also

- [`ui-components.md`](ui-components.md) — full reference for substrate-provided components.
- [`adr/ADR-007-component-tree-ui-protocol.md`](adr/ADR-007-component-tree-ui-protocol.md) — why the protocol looks like this.
- [`MacroComponents.kt`](../substrate/android/src/main/kotlin/dev/mas/substrate/android/ui/components/MacroComponents.kt) — worked examples of stateful + macro components.
