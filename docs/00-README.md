# Mobile Agent Substrate — Implementation Plan

This is the complete implementation plan for an open-source Kotlin Multiplatform substrate that lets users describe intent in natural language and have a polished native mobile app fulfill it through pre-defined scripts and pre-built UI templates, with Claude as the orchestrator.

## How to read this plan

The plan is split into focused documents. Read in order if you're new; jump around if you're looking for specifics.

| # | Document | Purpose |
|---|---|---|
| 00 | `00-README.md` | This file. Orientation. |
| 01 | `01-vision-and-scope.md` | What this is, what it isn't, success criteria. |
| 02 | `02-architecture.md` | The four-layer architecture, the harness, the data flow. |
| 03 | `03-modules.md` | Every module: purpose, API, dependencies, owner, platform targets. |
| 04 | `04-locked-interfaces.md` | The five interfaces that must be designed first. Detailed sketches. |
| 05 | `05-script-catalog.md` | The ~22 substrate scripts with parameters and descriptions. |
| 06 | `06-design-system.md` | Tokens, components, templates, semantic intents. |
| 07 | `07-harness.md` | Reliability, quality, observability, cost, behavior, memory, evaluation. |
| 08 | `08-security-and-compliance.md` | Local security model and store-compliance requirements. |
| 09 | `09-roadmap.md` | Phased build plan, ~7.5 months. |
| 10 | `10-ownership-and-process.md` | Who owns what, contribution rules, parallelization plan. |
| 11 | `11-risks-and-mitigations.md` | What could go wrong and how to handle it. |
| 12 | `12-first-week.md` | Concrete actions to start moving. |
| 13 | `13-v1.1-backlog.md` | What's explicitly deferred and why. |

## Quick summary

- **What:** OSS substrate for building LLM-orchestrated native mobile apps.
- **Stack:** Kotlin Multiplatform; Compose (Android) + SwiftUI (iOS); Koog for agent framework.
- **Architecture:** Five-layer module graph: core → capabilities → harness → domain → app.
- **Timeline:** ~7.5 months to v1, solo. Parallelizable across ~8 tracks once foundations are in place.
- **Audience:** Developers and power users who bring their own Anthropic API key.
- **First reference app:** Undercurrent (reflective journaling).
- **License:** Apache 2.0.

## Decisions already made

These were settled in design discussion and won't be re-litigated without strong cause:

1. Open-source substrate, not a commercial product. Commercial path stays open via swappable `LLMClient`.
2. Both platforms day-one (Android + iOS via KMP).
3. On-device agent loop; users bring their own Anthropic API key.
4. Defense-in-depth local security; no backend.
5. Pre-defined scripts (no runtime code generation).
6. Pre-built UI templates the LLM picks and parameterizes (no runtime UI generation).
7. Design system at four layers: tokens → components → templates → semantic intents.
8. Harness layer (Tier 1 + Tier 2) in v1, full safety/evaluation deferred to v1.1.
9. Memory is explicit-only (LLM decides; user sees and controls).
10. Reference app is Undercurrent.

## Decisions still open (RFCs in Phase 0–1)

These need to be resolved early but aren't fatal if revisited mid-build:

1. Script definition syntax (Kotlin DSL vs. annotations vs. JSON manifest) — RFC-001.
2. Template prop schema serialization details — RFC-002.

Decisions previously listed here but now settled (kept for traceability):

- iOS UI strategy: SwiftUI native consuming KMP framework — settled in `02-architecture.md` and codified as ADR-003.
- `LLMMiddleware` chain composition API — locked in `04-locked-interfaces.md`.
- Memory storage backend — SQLite FTS5 for v1 (`07-harness.md`); embedding path deferred to v1.1 (`13-v1.1-backlog.md`).

## How to use this with contributors

If you bring another developer in:

1. Have them read `01`, `02`, `03` in order (~30 minutes).
2. Assign a module from `10-ownership-and-process.md`.
3. They read the module's contract doc (`docs/modules/<name>.md`, to be created during Phase 0).
4. They read `04-locked-interfaces.md` for the contracts they'll consume.
5. They open a draft PR; you review at module-boundary level.

That's the contributor on-ramp. Should be under a day to first PR.
