# UI Component Reference

The substrate ships **34 components** the LLM can render via `ui_render`. Per
[ADR-007](adr/ADR-007-component-tree-ui-protocol.md), each is a thin wrapper
over Material 3 with an LLM-shaped prop schema.

This page is the canonical human-readable reference. The LLM gets the same
information auto-assembled from each component's `description`,
`layoutNotes`, and `example` fields into the system prompt — grouped by
category.

**Six categories** (also how the system prompt groups them):

- **[Display](#display)** — render content, no interaction (7)
- **[Layout](#layout)** — arrange children, no content of its own (4)
- **[Action](#action)** — fire an event on interaction (6)
- **[Input](#input)** — capture user data, maintain local state (10)
- **[Macro](#macro)** — behaviorally-complete widgets, one semantic event (5)
- **[Embed](#embed)** — wrap external content (1)

**Spacing tokens** (used by `padding`, `spacing`, `size` props): `none`,
`xs` (4dp), `sm` (8dp), `md` (12dp), `lg` (16dp), `xl` (24dp), `xxl` (32dp).

---

## Display

Render content. Stateless except for internal animations (loading spinners,
indeterminate progress).

### Text

| Prop | Type | Default | Notes |
|---|---|---|---|
| `text` | string | required | The text to render. |
| `variant` | string | `"body"` | `body`, `label`, `title`, `headline`, `display`. |
| `align` | string | `"start"` | `start` / `center` / `end`. |

### Icon

| Prop | Type | Default | Notes |
|---|---|---|---|
| `name` | string | required | One of: `add`, `arrow_back`, `check`, `close`, `delete`, `done`, `info`, `refresh`, `settings`, `star`, `warning`. |
| `size` | token | `"md"` | `xs` (16dp), `sm` (20dp), `md` (24dp), `lg` (32dp), `xl` (48dp). |

### Image

Coil-backed. Loading + error states are rendered as visible placeholders so
failures are debuggable.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `url` | string | required | URL or content URI. |
| `size` | token | `"md"` | `xs` (32dp), `sm` (64dp), `md` (96dp), `lg` (160dp), `xl` (240dp), `fill` (200dp tall full-width). |
| `contentScale` | string | `"fit"` | `fit`, `crop`, `fill`. |
| `contentDescription` | string | `""` | Accessibility label. |

### Badge

| Prop | Type | Default | Notes |
|---|---|---|---|
| `text` | string | required | |
| `variant` | string | `"default"` | `default`, `primary`, `success`, `warning`, `error`. |

### Alert

| Prop | Type | Default | Notes |
|---|---|---|---|
| `text` | string | required | |
| `title` | string | `""` | Optional title above the message. |
| `variant` | string | `"info"` | `info`, `success`, `warning`, `error` — picks colors + icon. |

### Divider

| Prop | Type | Default | Notes |
|---|---|---|---|
| `padding` | token | `"none"` | Vertical space above and below. |

### ProgressBar

| Prop | Type | Default | Notes |
|---|---|---|---|
| `value` | float | `null` | 0..1. If null, indeterminate looping animation. |
| `variant` | string | `"linear"` | `linear` or `circular`. |
| `label` | string | `""` | Auto-renders the percentage on the right when `value` is set. |

---

## Layout

Arrange children. Doesn't own content of its own.

### Column

| Prop | Type | Default | Notes |
|---|---|---|---|
| `spacing` | token | `"sm"` | Vertical spacing between children. |
| `padding` | token | `"none"` | Outer padding. |
| `align` | string | `"start"` | Horizontal alignment of children: `start`, `center`, `end`. |

### Row

Children are vertically centered.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `spacing` | token | `"sm"` | Horizontal spacing between children. |
| `padding` | token | `"none"` | Outer padding. |
| `align` | string | `"start"` | `start`, `center`, `end`, `space_between`, `space_evenly`. |

### Card

| Prop | Type | Default | Notes |
|---|---|---|---|
| `variant` | string | `"elevated"` | `elevated`, `default`, `outlined`. |
| `padding` | token | `"md"` | Inner padding. |
| `spacing` | token | `"sm"` | Spacing between children inside. |

### Spacer

| Prop | Type | Default | Notes |
|---|---|---|---|
| `size` | token | `"md"` | Magnitude of the gap. |
| `direction` | string | `"vertical"` | `vertical` or `horizontal`. |

### Tabs

Tabbed content. **Children align positionally** — emit one child per tab in
the same order. Only the selected tab's child renders; tab switching is
local (no agent round-trip).

| Prop | Type | Default | Notes |
|---|---|---|---|
| `tabs` | list of strings | required | Tab labels, in order. |
| `initial` | int | `0` | Initially selected index. |

```json
{
  "type": "Tabs",
  "props": {"tabs": ["Today", "Week", "Month"]},
  "children": [
    {"type": "Text", "props": {"text": "Today's stuff"}},
    {"type": "Text", "props": {"text": "This week"}},
    {"type": "Text", "props": {"text": "This month"}}
  ]
}
```

The first slot-based component — uses substrate's `LocalNodeRenderer` /
`LocalCurrentNode` so it can render only the selected child. App authors
follow the same pattern for custom switchers (BottomSheet, Carousel, etc.).

---

## Action

Fire a semantic event on tap. Each declares an `action` (or similar) key
the agent receives via `ComponentEvent.Action` on the next turn.

### Button

| Prop | Type | Default | Notes |
|---|---|---|---|
| `text` | string | required | Button label. |
| `action` | string | required | Action key. |
| `variant` | string | `"primary"` | `primary` (filled), `secondary` (outlined), `text`. |
| `fillWidth` | boolean | `false` | Stretches to parent width — useful in a Row for equal-width buttons. |

### IconButton

| Prop | Type | Default | Notes |
|---|---|---|---|
| `icon` | string | required | Same vocabulary as `Icon`. |
| `action` | string | required | Action key. |
| `variant` | string | `"default"` | `default` (no fill), `filled`, `tonal`. |
| `label` | string | `""` | Accessibility label. |

### Fab

| Prop | Type | Default | Notes |
|---|---|---|---|
| `icon` | string | required | |
| `action` | string | required | |
| `label` | string | `""` | If non-empty, promotes to the Extended FAB variant. |

### Chip

| Prop | Type | Default | Notes |
|---|---|---|---|
| `text` | string | required | |
| `variant` | string | `"assist"` | `assist`, `filter` (toggleable), `suggestion`, `input` (with delete). |
| `action` | string | `""` | If non-empty, fires Action on tap. |
| `selected` | boolean | `false` | For `filter` variant — current selection state. |
| `icon` | string | `""` | Optional leading icon. |

Note: filter / input variants have selection state but the agent re-renders
the tree with the new `selected` value on each tap (round-trip per change).
Use `Picker` macro for cheaper local selection.

### ListItem

| Prop | Type | Default | Notes |
|---|---|---|---|
| `headline` | string | required | Primary text. |
| `supporting` | string | `""` | Subtitle / second line. |
| `overline` | string | `""` | Small text above the headline. |
| `trailing` | string | `""` | Right-side text. |
| `action` | string | `""` | If non-empty, makes the row tappable and fires Action. |

### Countdown

A bare ticking counter — display + onComplete event, no controls. Prefer
the `Timer` macro when you need Pause / Reset / Extend.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `durationMs` | long | required | Duration in milliseconds. |
| `onCompleteAction` | string | `"timer_completed"` | Fired when the timer hits zero. |
| `variant` | string | `"display"` | `display`, `headline`, `title`. |

---

## Input

Capture user data — maintain local state. Each declares an `id` prop so
values can be bundled into the next sibling Action event's payload.

### TextField

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | required | Stable key — routes TextChanged events. |
| `label` | string | `""` | Optional. |
| `placeholder` | string | `""` | |
| `initialValue` | string | `""` | |
| `singleLine` | boolean | `true` | Set false for multi-line input. |

### Switch

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | required | |
| `label` | string | `""` | |
| `initial` | boolean | `false` | |

Fires `ToggleChanged(sourceId, value)`.

### Checkbox

Same shape as Switch.

### RadioGroup

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | required | |
| `options` | list of strings | required | |
| `initial` | string | first option | |
| `label` | string | `""` | |

Fires `TextChanged` with the selected option's text.

### Slider

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | required | |
| `label` | string | `""` | Label + current value displayed above. |
| `min` | float | `0.0` | |
| `max` | float | `1.0` | |
| `initial` | float | `0.0` | Coerced to `[min, max]`. |
| `steps` | int | `0` | 0 = continuous. |

### RangeSlider

Two handles for selecting a range. Fires `TextChanged` with `"start,end"`
on every drag.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | required | |
| `label` | string | `""` | |
| `min` | float | `0.0` | |
| `max` | float | `1.0` | |
| `initialStart` | float | `0.0` | |
| `initialEnd` | float | `1.0` | |
| `steps` | int | `0` | |

### DatePicker

**Layout:** needs ~360dp horizontal — render at top level OR in a Card with
`padding="none"`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | required | |
| `initialEpochMs` | long? | today | |
| `label` | string | `""` | |

Fires `TextChanged` with ISO `YYYY-MM-DD`.

### TimePicker

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | required | |
| `initialHour` | int | `12` | |
| `initialMinute` | int | `0` | |
| `is24Hour` | boolean | `false` | |
| `label` | string | `""` | |

Fires `TextChanged` with `HH:mm`.

### DateRangePicker

**Layout:** same constraint as DatePicker.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | required | |
| `label` | string | `""` | |

Fires `TextChanged` with `"YYYY-MM-DD to YYYY-MM-DD"` once both ends are picked.

### SegmentedButton

Connected row of mutually-exclusive selection buttons.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | required | |
| `options` | list of strings | required | |
| `initial` | string | first option | |
| `label` | string | `""` | |

Fires `TextChanged` with the selected option's text.

---

## Macro

Behaviorally-complete widgets. Bundle multiple primitives + local state +
controls. Fire one or two **semantic** events; everything else stays local.
Prefer macros when a use case fits them — no round-trips for mechanical
interactions.

### Timer

Countdown with built-in Reset / Pause / Extend controls. All controls local.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `durationMs` | long | required | 25-min Pomodoro = `1500000`. |
| `title` | string | `""` | |
| `onComplete` | string | `"timer_completed"` | |
| `showReset` | boolean | `true` | |
| `showPause` | boolean | `true` | |
| `extendByMs` | long | `300000` | If > 0, renders `+Nm` button. |

### Stopwatch

Counts up. Start / Pause / Lap / Reset / Done.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `title` | string | `""` | |
| `onDone` | string | `"stopwatch_done"` | Fired when user taps Done. |
| `showLaps` | boolean | `true` | |

### Form

Multi-field form with validation, submit, optional cancel. All typing local;
one event on submit with every field's value bundled.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `title` | string | `""` | |
| `fields` | list of FormField | required | See below. |
| `submitLabel` | string | `"Submit"` | |
| `onSubmit` | string | `"form_submitted"` | |
| `cancelLabel` | string | `""` | If non-empty, render a Cancel button. |
| `onCancel` | string | `"form_cancelled"` | |

**FormField:** `{ id, label, type, placeholder, initialValue, required }`.
`type`: `text`, `email`, `number`, `multiline`.

### Picker

Pick-one. Two styles.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `title` | string | `""` | |
| `options` | list of strings | required | |
| `style` | string | `"radio"` | `radio` (pick + confirm) or `buttons` (each option a button, fires immediately). |
| `initial` | string | first option | Only used in `radio`. |
| `confirmLabel` | string | `"Confirm"` | Only used in `radio`. |
| `onPicked` | string | `"picked"` | Choice arrives in `sourceLabel`. |

### DateCountdown

Calendar picker + live countdown to the picked date. All interactions local.

**Layout:** same as DatePicker — needs ~360dp horizontal.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `title` | string | `""` | |
| `initialEpochMs` | long? | today + 30 days | |
| `onArrived` | string | `"date_arrived"` | Fired when the picked date is reached. |

---

## Embed

Wrap external content.

### WebView

In-app web page. User stays in the app, sees your top bar.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `url` | string | required | |
| `height` | token | `"fill"` | `md` (300dp), `lg` (500dp), `xl` (700dp), `fill` (full screen). |
| `title` | string | `""` | Optional title above. |

Prefer rendering proper UI components over WebView; prefer WebView over
`external_open_url` (which leaves the app entirely).

---

## Error behavior

**Unknown component type.** `ui_render` rejects the call with a structured
error listing registered components; the LLM sees the error in
`tool_result` and retries.

**Bad props.** `ui_render` pre-validates each node's props against its
component's schema and rejects the whole render if any are bad. Failure
goes to the LLM as a clean error message.

**Tree too deep.** Trees deeper than 6 levels are rejected with a
structured error. Flatten.

---

## Adding your own component

See [writing-a-custom-component.md](writing-a-custom-component.md).
