# ADR-003 — Native UI per platform (Compose + SwiftUI) over Compose Multiplatform

- **Status:** Proposed (Phase 0 iOS validation deferred; will move to Accepted once Day 4 of `docs/12-first-week.md` runs)
- **Date:** 2026-05-19
- **Deciders:** Tech lead + (TBD) iOS lead
- **Supersedes:** —

## Context

The substrate is Kotlin Multiplatform below the UI line. Above the UI line,
two options exist:

1. **Native UI per platform.** Compose for Android, SwiftUI for iOS, both
   consuming the same KMP framework. UI written twice, against the same prop
   schemas.
2. **Compose Multiplatform.** A single Compose UI codebase running on both
   Android and iOS via the JetBrains Compose runtime for iOS.

Compose Multiplatform exists, ships from JetBrains, and is steadily improving.
For our specific use case — a polished consumer mobile app shipped to the App
Store as a v1 OSS reference for the substrate — its maturity and "native feel"
are not yet at parity with native iOS UI in 2026.

The substrate's pitch to OSS adopters is "build a real native app, not a
chat wrapper." If iOS users notice the app feels off (animation timing, scroll
physics, system-specific UX affordances), the substrate's credibility takes a
hit independent of whether the underlying agent loop is excellent.

## Decision

Native UI per platform: Compose for Android, SwiftUI for iOS. Both implementations conform to identical prop schemas defined in `design-system-api` (KMP common code).

The contract — `Templates`, `ScreenSpec`, prop data classes — lives in
`design-system-api`. Both UI implementations honor the same contract; the
agent loop produces `ScreenSpec` objects without knowing which platform will
render them.

## Consequences

**Positive**

- iOS app feels native. Animations, scroll, gestures, system integrations all
  follow Apple's expectations.
- Designer can review side-by-side against platform conventions.
- Future contributors can specialize: Android-UI dev, iOS-UI dev, working
  against the same prop contract.

**Negative**

- UI written twice. ~30–40% net cost increase on UI work.
- Risk of drift: two implementations may diverge in behavior or polish.
  Mitigated by snapshot tests and side-by-side reviews (see
  `docs/11-risks-and-mitigations.md` R8).

**Neutral**

- The KMP framework export to iOS is a real engineering surface to manage.
  This is validated on Day 4 of week 1.

## Alternatives considered

- **Compose Multiplatform for both.** Rejected: native polish is the
  substrate's pitch; CMP-iOS is not yet at parity for consumer-quality apps.
  Revisit at v2 if CMP-iOS matures.
- **Native UI on both (no shared UI code at all).** Functionally equivalent to
  this decision; what matters is that the *prop schemas* are shared. We have
  that.

## When to revisit

Two triggers:

1. JetBrains ships a Compose Multiplatform for iOS release with verifiably
   native-quality animation and gesture behavior.
2. We measure (via Undercurrent users) that the UI-written-twice cost is
   significantly more than the polish benefit.

## Day 4 validation evidence

> _To be filled in after Day 4 of `docs/12-first-week.md` runs. Expected
> outcome: a tiny `expect`/`actual` in `:util` is exported as a Swift
> framework and consumed by a SwiftUI view that displays its output. If that
> works smoothly, ADR moves to Accepted. If it doesn't, R1 (KMP iOS friction)
> escalates and the plan re-bases._
