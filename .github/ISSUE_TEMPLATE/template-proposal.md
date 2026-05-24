---
name: Template proposal
about: Propose a new UI template (noun the LLM can show).
title: "[template] "
labels: template-proposal
---

## Proposed template

**Name:** e.g., `MapScreen`

**One-sentence purpose:**

## Which semantic intent(s) would route to it?

<!-- From `docs/04-locked-interfaces.md` SemanticIntent set, or a proposed new one. If new intent: open a separate RFC. -->

## Why an existing template isn't enough

Before proposing a new template, confirm:

- [ ] The router can't handle this by varying props on an existing template.
- [ ] The layout is meaningfully different from all 8 existing templates.
- [ ] App-specific extension (in your app's `design-*` module) isn't the right home.

## Props sketch

```kotlin
data class <Name>ScreenProps(
    val title: String,
    // ...
)
```

## Slots

<!-- Which slots does it expose? (header, footer, primaryAction, etc.) -->

## Emphasis mapping

<!-- How does this template render each Emphasis value (subtle / default / prominent / celebratory / somber)? -->

## Cross-platform notes

- Compose implementation considerations:
- SwiftUI implementation considerations:
- Accessibility: minimum hit targets, screen-reader labels, RTL.

## Example designs

<!-- Mockup screenshots, Figma links. -->
