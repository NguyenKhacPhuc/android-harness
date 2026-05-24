# 12 — First Week Action Items

Concrete actions to start moving. Day-by-day so it's actionable, not just inspirational.

## Day 1 (Monday) — Repo and basics

- [ ] Create a private GitHub repo: `mobile-agent-substrate` (or whatever name lands).
- [ ] `git init`, add Apache 2.0 LICENSE, basic README.
- [ ] Add `.gitignore` for KMP / Gradle / Android / iOS / Xcode.
- [ ] Create initial `CONTRIBUTING.md` stub.
- [ ] Create initial `SECURITY.md` stub.
- [ ] Create initial `CODE_OF_CONDUCT.md` (Contributor Covenant).
- [ ] Commit + push.
- [ ] Enable GitHub Issues, Discussions, Projects.
- [ ] Create issue templates: bug, feature, RFC, script proposal, template proposal.
- [ ] Create PR template.

End of day: empty repo, structurally complete.

## Day 2 (Tuesday) — KMP skeleton

- [ ] Initialize KMP project (use the KMP wizard).
- [ ] Add targets: `androidTarget()`, `iosX64()`, `iosArm64()`, `iosSimulatorArm64()`, `jvm()`.
- [ ] Configure `settings.gradle.kts` with module declarations from `03-modules.md`.
- [ ] Create empty Gradle modules (no code, just `build.gradle.kts` per module).
- [ ] Set up central version catalog (`gradle/libs.versions.toml`).
- [ ] Verify the project builds end-to-end (will produce empty artifacts).
- [ ] Commit + push.

End of day: project builds, modules exist as empty packages.

## Day 3 (Wednesday) — Blank apps + CI

- [ ] Create `apps/undercurrent/android` Android app stub. Single empty Activity.
- [ ] Create `apps/undercurrent/ios` iOS app stub. Single empty SwiftUI view.
- [ ] Verify both run on emulator/simulator.
- [ ] Run both on real device (Android + iPhone you have access to).
- [ ] Set up GitHub Actions workflow: `.github/workflows/ci.yml` — build, lint, test on PR.
- [ ] Add Gradle lint configuration (detekt or ktlint).
- [ ] Verify CI passes on initial PR.

End of day: both apps run blank screens; CI green.

## Day 4 (Thursday) — iOS UI strategy validation

The single most important risk to de-risk in week 1.

- [ ] Create a tiny `expect`/`actual` in `util` (e.g., `expect fun platformName(): String`).
- [ ] Export the KMP `util` module as a Swift framework.
- [ ] Consume it from the iOS app: display `platformName()` on the screen.
- [ ] Test: does it work in Xcode? Debug experience tolerable?
- [ ] Now create a SwiftUI view that takes typed input from KMP — verify the API export is clean.
- [ ] Write up findings as `docs/adr/ADR-003-ios-ui-strategy.md`. Accepted or revised.

If this is rough, address it now. The whole plan rests on KMP working below the UI line.

End of day: SwiftUI consuming KMP works; ADR-003 accepted.

## Day 5 (Friday) — Docs site + ADRs

- [ ] Set up docs site (MkDocs Material or similar) under `docs/`.
- [ ] Add this plan (`00-README.md` through `13-v1.1-backlog.md`) to the docs site.
- [ ] Verify site builds and serves locally.
- [ ] Write ADR-001 (no streaming in v1).
- [ ] Write ADR-002 (explicit memory only).
- [ ] Finalize ADR-003 (Compose + SwiftUI) — drafted Day 4; today, fold in the validation evidence and move status to Accepted.
- [ ] Write ADR-004 (middleware pattern over single extension point).
- [ ] Write ADR-005 (SemanticIntent location).
- [ ] Commit + push.

End of week 1: repo is live, builds, has CI, docs site renders, all foundational ADRs accepted, iOS strategy validated.

## Week 2 — Begin Phase 0 final touches and Phase 1 setup

- [ ] Fill out `CONTRIBUTING.md` with the process rules from `10-ownership-and-process.md`.
- [ ] Fill out `SECURITY.md` with disclosure process.
- [ ] Write the module contract doc template (`docs/modules/_template.md`).
- [ ] Create stub contract docs for every module (5 minutes each, just the template + owner).
- [ ] Open the first batch of RFC issues for design decisions still open:
  - RFC-001: Script definition syntax (Kotlin DSL vs annotations vs JSON).
  - RFC-002: Template prop schema serialization.
  - RFC-003: Middleware chain composition API details.
  - RFC-004: Memory recall ranking (FTS5 score vs. recency-weighted).
- [ ] Begin work on `contracts` module — implement types from `04-locked-interfaces.md`.

End of week 2: Phase 0 complete. Phase 1 (locked interfaces) begun.

## Recruit a collaborator

In parallel with weeks 1–2:

- [ ] Identify one potential collaborator (current colleague, OSS friend, Twitter/X mutual).
- [ ] Share the plan (docs site link).
- [ ] Have a conversation about what they'd own.
- [ ] If they're in: assign their first module after Phase 1 lands.
- [ ] If they're not: continue solo with the slip plan in `09-roadmap.md`.

Even a part-time collaborator (5 hours/week) on the platform you're weaker in compounds dramatically over 7 months.

## What NOT to do in week 1

These are tempting and wrong:

- ❌ Don't start implementing scripts. Contracts come first.
- ❌ Don't start designing UI templates. Tokens come first.
- ❌ Don't pick a fancy DI framework. Manual injection until you prove you need more.
- ❌ Don't set up Slack/Discord. GitHub is enough.
- ❌ Don't make the docs site look beautiful. Functional > pretty in week 1.
- ❌ Don't fight Gradle. Use defaults; tune later.
- ❌ Don't try to integrate Koog yet. Mock the LLM client first.

The discipline of week 1 is: **build foundations, not features.**

## Success criteria for week 1

If at end of week 1 you have:

1. A repo that builds and CIs cleanly.
2. Blank apps running on Android and iOS devices.
3. KMP successfully exporting types to SwiftUI.
4. All ADRs written and accepted.
5. Docs site live with the full plan.

…you're on track. Start week 2 with confidence.

If you have less than 3 of those, slow down and finish week 1 properly in week 2 before starting Phase 1 work. The foundation matters.

## A note on momentum

The first two weeks are deceptively important. They feel like "just setup," but they're the work that makes everything afterward possible (or impossible). Solo, in particular, momentum compounds. A clean foundation in week 1 means a productive Phase 1; a messy one means everything is harder for months.

Resist the urge to skip ahead to the "fun" parts. The fun parts will be more fun on top of a clean foundation.
