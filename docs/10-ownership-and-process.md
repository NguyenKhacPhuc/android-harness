# 10 — Ownership & Process

How the work splits and how contributors collaborate.

## Ownership matrix (module → role)

| Module | Primary owner | Secondary / reviewer |
|---|---|---|
| `util` | Tech lead | — |
| `contracts` | Tech lead | All future module owners |
| `core` | Tech lead | Senior backend |
| `scripts-core` | Backend dev | Tech lead |
| `os-bridge/api` | Tech lead | Android + iOS leads |
| `os-bridge/android` | Senior Android dev | — |
| `os-bridge/ios` | Senior iOS dev | — |
| `design-system/api` | Tech lead + designer | — |
| `design-system/compose` | Android UI dev | Designer |
| `design-system/swiftui` | iOS UI dev | Designer |
| `llm-anthropic` | Backend dev | Tech lead |
| `security` | Security-conscious dev | Tech lead |
| `compliance` | Any dev | Tech lead |
| `harness-reliability` | Backend dev | — |
| `harness-quality` | Backend dev | — |
| `harness-observability` | Backend + UI pair | — |
| `harness-cost` | Backend dev | — |
| `harness-behavior` | Backend dev | — |
| `harness-memory` | Backend + UI pair | Privacy reviewer |
| `harness-evaluation` | Any dev (good first-PR) | — |
| `scripts-undercurrent` | Product dev | — |
| `design-undercurrent` | Product dev + designer | — |
| `reference-app` | Product lead | — |

## Roles needed

The minimum roles for a healthy team:

1. **Tech lead** — owns architecture, contracts, core. Reviews API changes.
2. **Backend dev** — scripts, harness middleware. Strongest Kotlin generalist.
3. **Senior Android dev** — Android OS bridge, Android UI implementations.
4. **Senior iOS dev** — iOS OS bridge, SwiftUI implementations.
5. **Designer** — design system tokens, templates, app theming.
6. **Product lead** — reference app (Undercurrent), prioritization.

The minimum *people*: 1 person can do it solo (slowly). 2 people can do it well (you covering tech lead + backend + one platform, collaborator covering the other platform + design + product). 4 people fit naturally. 6+ runs into coordination overhead.

## Parallelization windows

Once Phase 2 (LLM + security) is done, work splits cleanly:

```
Phase 3+ parallel tracks:

Track 1: Core + LLM evolution            (tech lead)
Track 2: Scripts backend                 (backend dev)
Track 3: Android OS bridge               (Android dev)
Track 4: iOS OS bridge                   (iOS dev)
Track 5: Compose UI (design + templates) (Android UI dev + designer)
Track 6: SwiftUI UI (design + templates) (iOS UI dev + designer)
Track 7: Harness modules                 (backend dev)
Track 8: Compliance + reference app      (product lead)
```

Solo: serial. Two-person: tracks 1+2+3+5 vs 4+6+7+8. Four-person: pair on each side of the platform split.

## Process rules

These are the rules that let the modular split actually work.

### 1. One PR = one module
PRs touching multiple modules require justification in the description. If a "feature" naturally spans modules, split into a chain of PRs (one per module) with clear sequence.

### 2. Public API changes need ADR
Any change to a public API in `contracts`, `core`, `scripts-core`, `design-system-api`, or the locked harness interfaces requires an Architecture Decision Record in `docs/adr/`. Format: title, status (proposed/accepted/superseded), context, decision, consequences. Reviewed by tech lead + downstream consumers.

### 3. Tests live in the module they test
No cross-module test files. If you need to test integration across two modules, create that test in a higher module that depends on both (or create a small dedicated integration test module).

### 4. Each module ships its own changelog entry
`CHANGELOG.md` per module. Substrate-wide changelog assembled at release time.

### 5. Internal APIs are internal
Use Kotlin's `internal` visibility aggressively. If it doesn't need to be public, mark it internal. Reduces surface area; lets you refactor freely.

### 6. `util` and `contracts` are read-only for most contributors
Changes require maintainer review with at least 2 approvals. These are foundations; changing them ripples.

### 7. No circular dependencies, ever
CI enforces this via Gradle's dependency check. Cycles fail the build, not just lint.

### 8. New modules require RFC
Don't proliferate modules without discussion. Open a GitHub Discussion or issue describing: purpose, why it can't live in an existing module, expected size, owner, integration points. Tech lead approval required.

### 9. Backward-incompatible changes require migration guide
Pre-1.0: breaking changes allowed in minor bumps. Document them.
Post-1.0: breaking changes only in major bumps. Migration guide is part of the PR.

### 10. Performance budgets are in CI
Benchmark tests run on PR; regressions over thresholds fail the build. Specifically: middleware overhead, script execution, UI render time.

## PR review process

Two-tier review:

**Tier 1: Module-internal changes** — implementation changes that don't touch public API. Single review from module owner or co-owner. Merge fast.

**Tier 2: Public API changes** — anything in a module's published API. Two reviews: module owner + at least one consumer module owner. ADR required if non-trivial.

**Tier 3: Cross-cutting / substrate-wide changes** — anything in `contracts`, build setup, or affecting multiple modules. Tech lead + two reviewers. ADR mandatory.

## Communication channels

- **GitHub Issues** — feature requests, bug reports.
- **GitHub Discussions** — design conversations, RFCs.
- **GitHub Pull Requests** — code review.
- **`docs/adr/`** — decisions of record.

No Slack/Discord required for v1. If the project grows, add async chat. Keep written record in GitHub as the source of truth.

## Release cadence

Pre-v1: rolling releases as phases complete. Versions `0.1.0`, `0.2.0`, etc.

v1.0.0: aligned with reference app store submission.

Post-v1:
- **Patch releases (1.0.x)** — bug fixes, weekly or as needed.
- **Minor releases (1.x.0)** — new modules, additive features, monthly.
- **Major releases (x.0.0)** — breaking changes, quarterly at most.

Semantic versioning strictly enforced.

## How a new contributor onboards

The path:

1. **Read** the plan (`00–04` of these docs). 1 hour.
2. **Build** the project locally. 30 min.
3. **Run** the reference app. 15 min.
4. **Run** the test suite. 15 min.
5. **Pick** a "good first issue" labeled in GitHub.
6. **Read** the relevant module's contract doc.
7. **Open** a draft PR within their first day.
8. **Iterate** with the module owner.

If any of these steps takes more than the budgeted time, that's a docs bug — fix the docs.

## Contributor agreement

- License: contributions licensed Apache 2.0 to match the project.
- DCO (Developer Certificate of Origin) sign-off on commits.
- No CLA in v1 (simpler; revisit if commercial layer warrants it).
- Code of Conduct: Contributor Covenant.

## Maintainer responsibilities

Per module owner:

- Respond to PRs within 5 business days.
- Triage issues within 7 business days.
- Maintain test coverage above the module's target.
- Update changelog with every merge.
- Update module contract doc when public API changes.
- Be available for a monthly maintainer sync (when team > 2).

If a maintainer goes inactive for 30+ days, ownership transfers (with notice).

## Decision-making

For technical decisions:

1. Anyone can propose via PR, issue, or discussion.
2. If consensus: merge.
3. If contested: open ADR, gather feedback, tech lead decides.
4. Tech lead decisions are final but documented.

For project direction (roadmap, scope):

- Open governance during v1 (tech lead has final say but seeks consensus).
- Post-v1, consider a steering committee if the project has multiple active contributors.

## What to do when scope creep hits

Inevitably someone (probably you) will propose adding X to v1. The discipline:

1. Open an issue.
2. Apply the test: does X belong in v1, v1.1, or never?
3. If v1: what's being cut to make room? Every addition requires a deferral.
4. If v1.1: add to backlog; close the v1 conversation.
5. If never: explain and close.

This conversation happens in writing, in GitHub. The discipline is in the writing; the writing is the discipline.

## Recognition

OSS thrives on recognition. Minimum:

- AUTHORS file maintained.
- Release notes credit contributors.
- "Hall of fame" page on docs site.
- Yearly recap acknowledging contributions.

Small but real.

## Funding (optional, not for v1)

If the project gets traction:

- GitHub Sponsors enabled.
- Open Collective for transparent funds.
- Funds used for: hosting docs site, security audits, contributor stipends if material.

No paywalled features. The substrate stays free.

This is post-v1 territory; flagged here for awareness.
