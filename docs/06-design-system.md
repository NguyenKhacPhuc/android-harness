# 06 — Design System

The design system is the visual API surface for the LLM, parallel to the script catalog.

## The four layers

```
Layer 4: Semantic Intents      ← LLM picks these
            ↓
Layer 3: Templates             ← Router maps intent → template
            ↓
Layer 2: Components            ← Templates use these
            ↓
Layer 1: Foundations (tokens)  ← Components use these
```

The strictness principle: **the LLM picks what to show. The LLM does not pick how it looks.**

## Layer 1 — Foundations (tokens)

Not exposed to the LLM. Defined once per theme (light, dark, app-specific overrides).

### Colors (semantic)
```
primary           accent for primary actions
onPrimary         text/icons on primary
surface           default background
surfaceVariant    subtle background variation
onSurface         default text
onSurfaceMuted    secondary text
border            divider, outline
success, warning, error, info
celebratory       reserved for Emphasis.CELEBRATORY
somber            reserved for Emphasis.SOMBER
```

### Spacing
```
xxs: 2, xs: 4, sm: 8, md: 16, lg: 24, xl: 32, xxl: 48
```

### Radii
```
none: 0, sm: 4, md: 8, lg: 16, full: 9999
```

### Typography
Semantic roles, not point sizes:
```
displayLarge, displayMedium, displaySmall
headlineLarge, headlineMedium, headlineSmall
titleLarge, titleMedium, titleSmall
bodyLarge, bodyMedium, bodySmall
labelLarge, labelMedium, labelSmall
```

### Motion
```
durations: instant (0), fast (150ms), medium (300ms), slow (500ms)
easings: standard, emphasized, decelerated, accelerated
```

### Elevation
```
0, 1, 2, 3 — used sparingly
```

## Layer 2 — Components

Composed from tokens. Not exposed to the LLM directly; used by templates.

The substrate ships ~10 component primitives:

1. **ActionButton** — `variant: PRIMARY | SECONDARY | DESTRUCTIVE | GHOST`, `size: SM | MD | LG`, `state: IDLE | LOADING | DISABLED`
2. **TitleHeader** — `kind: LARGE | MEDIUM`, with optional subtitle
3. **BodyText** — `kind: DEFAULT | SECONDARY | MUTED`
4. **EmptyState** — icon, title, optional body, optional action
5. **ContentCard** — heading, body, optional footer, with elevation variants
6. **LabeledField** — label, input, optional helper, optional error
7. **Tag** — `kind: NEUTRAL | ACCENT | SUCCESS | WARNING | INFO`
8. **Avatar** — image or initials, sizes
9. **Countdown** — visual countdown variants
10. **ListRow** — title, subtitle, leading visual, trailing action

Each component:
- Lives in `design-system-compose` (Compose) and `design-system-swiftui` (SwiftUI) with matching API shape.
- Uses only tokens from Layer 1 — never raw values.
- Has its own unit tests (Compose: ComposeTestRule; SwiftUI: XCTest snapshot).
- Accessible by default: minimum 44pt hit targets, WCAG AA contrast, screen-reader labels.

## Layer 3 — Templates

Full screen layouts. Each maps from one or more semantic intents.

v1 ships 8 templates:

| Template | Used by intents | Purpose |
|---|---|---|
| `TimerScreen` | Countdown | Visual timer with controls |
| `ListScreen` | ShowData (default) | Browse data |
| `DashboardScreen` | ShowData (multi-section), Compare | Multi-metric view |
| `ChartScreen` | Compare (when chart-shaped) | Visualize series |
| `FormScreen` | CaptureInput (multi-field) | Structured input |
| `PromptScreen` | CaptureInput (1 field), Reflect | Single-question focus |
| `DetailScreen` | (direct, post-list tap) | Single-item deep view |
| `ChatScreen` | (default conversation surface) | The agent conversation itself |

Each template:
- Has a strongly typed `Props` data class.
- Uses **slots** for variability: `header: SlotContent?`, `footer: SlotContent?`, etc.
- Maps `Emphasis` to consistent styling treatments per template.
- Supports `AgentContext` for screens that stay agent-aware.

### Slot pattern

```kotlin
data class ListScreenProps(
    val title: String,
    val items: List<ListItem>,
    val header: SlotContent? = null,
    val itemRenderer: ItemRenderer = ItemRenderer.ROW,
    val footer: SlotContent? = null,
    val emptyState: EmptyStateContent? = null,
    val primaryAction: ActionContent? = null,
    val itemActions: List<ItemAction> = emptyList(),
    val emphasis: Emphasis = Emphasis.DEFAULT
)
```

Slots let the LLM compose flexibly without breaking layout integrity. The template owns spacing, typography, hierarchy.

## Layer 4 — Semantic Intents

What the LLM emits. Defined in `04-locked-interfaces.md`. The full set:

- `ShowData` — most "show me X" intents
- `CaptureInput` — forms and questions
- `Countdown` — timers, stopwatches
- `Confirm` — confirmation prompts (often used for destructive actions)
- `Reflect` — reflective single-prompt input
- `Compare` — comparison/visualization intents
- `Message` — pure text response, no UI

## The IntentRouter

Maps intent → template. Lives in `design-system-api`. Implementations can be overridden per-app for advanced cases.

Default router logic (sketch):

```kotlin
class DefaultIntentRouter : IntentRouter {
    override fun route(intent: SemanticIntent, ctx: RenderContext): UIUpdate = when (intent) {
        is SemanticIntent.ShowData -> UIUpdate.Navigate(when {
            intent.items.size <= 4 && intent.density == Density.SPACIOUS ->
                ScreenSpec("Dashboard", intent.toDashboardProps())
            intent.items.firstOrNull()?.tags?.contains("chart") == true ->
                ScreenSpec("Chart", intent.toChartProps())
            else ->
                ScreenSpec("List", intent.toListProps())
        })
        is SemanticIntent.CaptureInput -> UIUpdate.Navigate(when {
            intent.fields.size == 1 ->
                ScreenSpec("Prompt", intent.toPromptProps())
            else ->
                ScreenSpec("Form", intent.toFormProps())
        })
        is SemanticIntent.Countdown -> UIUpdate.Navigate(ScreenSpec("Timer", intent.toTimerProps()))
        is SemanticIntent.Confirm  -> UIUpdate.Overlay(intent.toOverlaySpec())          // overlay, no template
        is SemanticIntent.Reflect  -> UIUpdate.Navigate(ScreenSpec("Prompt", intent.toReflectProps(emphasis = SOMBER)))
        is SemanticIntent.Compare  -> UIUpdate.Navigate(ScreenSpec("Chart", intent.toChartProps()))
        is SemanticIntent.Message  -> UIUpdate.None                                      // chat surface renders the text; no screen change
    }
}
```

Notes:
- `Confirm` returns `UIUpdate.Overlay(OverlaySpec)` rather than a template — keeps the 8-template catalog clean.
- `Message` returns `UIUpdate.None` — the chat surface (the `ChatScreen` template) already renders the text from the LLM response; the router doesn't need to direct anywhere.

The router is the leverage point for evolving visual design without re-prompting. Add a new template, update the router, ship — LLM prompts unchanged.

## Screen ↔ Agent event protocol

Screens with `agentContext` non-null are agent-aware. User interactions on those screens emit events back to the agent loop.

```kotlin
sealed class ScreenEvent {
    data class ActionInvoked(val actionId: String, val payload: JsonObject?) : ScreenEvent()
    data class ValueChanged(val fieldId: String, val value: JsonElement) : ScreenEvent()
    data class Dismissed(val reason: DismissReason) : ScreenEvent()
}

enum class DismissReason { USER_BACK, USER_GESTURE, AGENT_REPLACED, TIMEOUT }
```

Events queue into a `Channel<ScreenEvent>` consumed by `AgentRuntime`. Each event becomes a "user message" to the agent (with structured payload, not natural language), and the agent loop runs again.

This is the magic that makes screens dual-purpose: regular interactive UI *and* contexts the agent can act on.

## App-specific extension

Apps can:

1. **Override the theme** — replace token values with their own palette (Undercurrent's Terracotta or Moss).
2. **Add app-specific templates** — `ReflectionScreen`, `BucketEvaluationScreen` for Undercurrent.
3. **Add app-specific intents** — for highly opinionated experiences. Discouraged in favor of parameterizing existing intents.
4. **Override the router** — custom intent → template mapping for app-specific intents.

Theme override pattern:

```kotlin
// In design-undercurrent
object UndercurrentTheme : SubstrateTheme {
    override val colors = TerracottaPalette
    override val typography = SerifTypography
    override val motion = MotionTokens.default.copy(medium = 400.ms)  // slightly slower, more reflective
}
```

## Accessibility, internationalization, dark mode

All non-negotiable from day one:

- **Dark mode** — every token has both light and dark variants. The substrate honors system preference.
- **Dynamic Type / large text** — type tokens are in scalable units.
- **A11y** — every component declares semantic role and label; tests verify with TalkBack/VoiceOver.
- **i18n** — all user-visible strings in templates go through a localization layer (substrate-provided wrappers around the platform's native i18n).
- **RTL** — layouts use logical directions (start/end), not left/right.

## Designer ↔ developer workflow

For app teams (including yours):

1. Designer defines/updates tokens in Figma (mirrored to Kotlin).
2. Designer specifies templates as Figma component sets matching the template's slots.
3. Developer implements the template's Compose version against tokens.
4. iOS dev implements SwiftUI version with matching API.
5. Designer reviews both side-by-side; signs off when consistent.
6. Template enters the registry.

For Undercurrent specifically, the Terracotta vs Moss palette decision is the first major designer deliverable. That decision feeds into Layer 1 and propagates automatically.

## Estimated implementation cost

| Item | Effort |
|---|---|
| Token definition (Layer 1) | 1 week (designer-heavy) |
| 10 component primitives (Compose + SwiftUI) | 3 weeks |
| 8 templates (Compose + SwiftUI) | 3 weeks |
| Semantic intent + router | 1 week |
| Screen-event protocol | 1 week |
| App-specific extensions for Undercurrent | 2 weeks |
| **Total** | **~11 weeks** |

Parallel work: Compose track and SwiftUI track run side-by-side after API is locked. Two devs at this stage cuts wall-clock roughly in half (~6 weeks).
