# 09 — Roadmap

Phased build plan, ~7.5 months to v1. Solo. Buffer for one slip per phase.

## Phase summary

| Phase | Weeks | Goal | Key deliverable |
|---|---|---|---|
| 0 | 1–2 | Foundations | Repo builds; blank apps run |
| 1 | 3–4 | Core contracts | Locked interfaces; mock loop works |
| 2 | 5–6 | LLM + security plumbing | Real Claude call works |
| 2.5 | 7–9 | Harness Tier 1 | Middleware chain; reliability/quality/observability/behavior |
| 3 | 10–13 | First 5 scripts | "Set me a reminder" works end-to-end |
| 4 | 14–17 | Design system + templates | Timer screen renders on both platforms |
| 5 | 18–21 | Remaining scripts + templates | Catalogs feature-complete |
| 5.5 | 22–24 | Harness Tier 2 | Cost + memory modules |
| 6 | 25–28 | Reference app | Undercurrent runs end-to-end |
| 7 | 29–30 | Compliance + store prep | Submitted to both stores |
| 8 | 31+ | Launch | Public OSS release |

## Phase 0 — Foundations (Weeks 1–2)

**Goal:** skeleton you can build on.

Tasks:
- Create GitHub repo (private initially).
- KMP project setup; Android + iOS targets.
- Gradle modules scaffolded per `03-modules.md` (empty packages, no code).
- `settings.gradle.kts` declares all modules; `gradle/libs.versions.toml` central catalog.
- CI/CD: lint, test, build on PR via GitHub Actions.
- LICENSE (Apache 2.0), README, CONTRIBUTING.md, SECURITY.md, CODE_OF_CONDUCT.md.
- Coding standards doc.
- Empty Android app + iOS app stubs that compile and run on devices.
- Documentation site scaffold (MkDocs).
- iOS UI strategy decision documented (SwiftUI native consuming KMP framework — confirmed via small test in this phase).
- ADR-001 (no streaming in v1), ADR-002 (explicit memory only), ADR-003 (Compose + SwiftUI over Compose Multiplatform), ADR-004 (middleware over single AgentRunner extension), ADR-005 (SemanticIntent in design-system-api).

**Deliverable:** repo that builds, blank apps run on both platforms, docs landing page live, all ADRs accepted.

**Owner:** tech lead + iOS dev (for the strategy validation).

## Phase 1 — Core Contracts (Weeks 3–4)

**Goal:** the five locked interfaces and the mock plumbing.

Tasks:
- Implement all types from `04-locked-interfaces.md` in `contracts`.
- kotlinx.serialization on all data classes.
- `MockLLMClient`, `MockScript`, `MockMiddleware` for testing.
- Skeleton `AgentRuntime` in `core` that wires mock client + mock middleware + mock script registry.
- End-to-end unit test: user message in → mock loop runs → response out.
- Audit log interface defined.
- Permission model defined.

**Deliverable:** unit-tested core contracts; mock loop with empty middleware chain works.

**Owner:** tech lead. PRs reviewed by future downstream module owners (they need to know what they're consuming).

## Phase 2 — LLM Client + Security Plumbing (Weeks 5–6)

**Goal:** real Claude calls; secrets handled correctly.

Tasks:
- `DirectAnthropicClient` via Koog. Real `chat()` and `validate()`.
- Keystore wrapper (Android, `KeyVault.android`).
- Keychain wrapper (iOS, `KeyVault.ios`).
- Key paste flow (UI in `compliance` module + storage in `security`).
- Validation call on paste.
- Network allowlist enforcement (`WhitelistingHttpClient`).
- Audit log persistence (SQLDelight schema + writes).
- Initial AI consent modal.
- Stub-app submission to TestFlight + Play Internal Testing to learn review behavior early.

**Deliverable:** debug app that takes a pasted key, makes a real Claude call, logs the call. No scripts, no UI templates, no harness chain yet (or middleware chain is empty).

**Owner:** backend dev (LLM client), security-conscious dev (vault), UI dev (paste screen).

## Phase 2.5 — Harness Tier 1 (Weeks 7–9)

**Goal:** middleware chain wraps every LLM call; reliability, quality, observability, behavior in place.

Tasks (parallelizable across these four modules):

`harness-reliability`:
- Retry policy + exponential backoff.
- Timeout enforcement.
- Fallback model selection.
- Circuit breaker.
- Degraded-mode UI signal.

`harness-quality`:
- Tool call schema validator.
- Structured retry on malformed responses.
- Type coercion with warnings.
- Truncated response handling.

`harness-observability`:
- Trace recorder + SQLDelight schema.
- Debug viewer screen (Compose + SwiftUI).
- Feedback collection UI.
- JSON export.

`harness-behavior`:
- System prompt composer.
- Conversation pruning.
- Tool list filtering.

Integration:
- All four middlewares composed in the chain in the test app.
- Stress tests simulating failures, malformed responses.
- Benchmark: chain overhead < 50ms p50.

**Deliverable:** middleware chain functional; debug viewer shows real traces; retries and fallback verified against simulated failures.

**Owner:** backend dev (reliability, quality, behavior), backend + UI pair (observability viewer).

## Phase 3 — Script Engine + First 5 Scripts (Weeks 10–13)

**Goal:** the agent loop calls real OS-touching scripts.

Tasks:
- `ScriptRegistry` + `ScriptExecutor` (with envelope handling, error mapping, async/cancellation, destructive-action gate).
- Permission gate integrated.
- Idempotency support.
- Implement scripts: `data.query`, `data.upsert`, `notify.show`, `schedule.create`, `ui.ask`.
- For each: Android (`os-bridge/android/*`) and iOS (`os-bridge/ios/*`) implementations.
- Unit + integration tests on both platforms.
- Per-script docs following template in `05-script-catalog.md`.

**Deliverable:** "set me a reminder in 5 minutes" works end-to-end on Android and iOS, with the harness in place.

**Owner:** backend dev (script registry, common scripts), Android dev (os-bridge/android), iOS dev (os-bridge/ios). Three parallel tracks once registry is done.

## Phase 4 — Design System + Template Engine (Weeks 14–17)

**Goal:** LLM produces real UI, not just chat.

Tasks:
- Token catalog (designer + dev pair).
- 10 component primitives in Compose.
- 10 component primitives in SwiftUI (matching API).
- 5 templates in Compose (Timer, List, Form, Prompt, Detail).
- 5 templates in SwiftUI (matching).
- `IntentRouter` (default implementation).
- `UIUpdate` channel wired through `AgentRuntime`.
- Screen-event-back-to-agent protocol implemented.

**Deliverable:** "I want a 25-minute Pomodoro timer" produces a real, styled TimerScreen on both platforms; Reset and Extend buttons work; events flow back to the agent on agent-aware screens.

**Owner:** designer (tokens), Android UI dev (Compose), iOS UI dev (SwiftUI), backend dev (router + UIUpdate wiring).

## Phase 5 — Remaining Scripts + Templates (Weeks 18–21)

**Goal:** catalogs feature-complete for v1.

Tasks:
- Scripts: `data.delete`, `schedule.cancel`, `schedule.list`, `calendar.read`, `calendar.create`, `contacts.read`, `files.save`, `files.read`, `files.share`, `ui.navigate`, `ui.dialog`, `ui.requestPermission`, `external.launchApp`, `external.openUrl`, `external.share`, `network.fetch`, `system.userContext` (~17 scripts).
- Templates: `Chat`, `Dashboard`, `Chart` (3 templates).
- Intent router branching logic for choosing between similar templates.
- Documentation for every new script and template.
- Stress test the LLM with a variety of intents to find missing scripts / awkward parameters.

**Deliverable:** full v1 catalogs shipped. Substrate is now "feature-complete" for general apps; reference app implementation can begin.

**Owner:** distributed across Android/iOS/backend devs as in Phase 3.

## Phase 5.5 — Harness Tier 2: Cost + Memory (Weeks 22–24)

**Goal:** cost transparency and explicit memory live.

`harness-cost`:
- Token tracking middleware.
- `QuotaPolicy` interface + default lenient policy.
- Per-conversation, daily, monthly aggregation.
- Cost UI: per-message badge, settings panel, cap configuration.
- Warning + block flows.
- Opt-in auto-downgrade.

`harness-memory`:
- `memory.store` and `memory.recall` script implementations.
- SQLite FTS5 backing storage.
- Scoping (session, permanent, per-app).
- "Agent Memories" screen on both platforms.
- Delete individual / wipe all / export JSON.
- PII pattern detection → confirmation prompt.
- Provenance wrapping on recall.

**Deliverable:** cost and memory modules shippable; reference app uses both.

**Owner:** backend + UI pair for each.

## Phase 6 — Reference App (Undercurrent) (Weeks 25–28)

**Goal:** prove the substrate by shipping a real app on it.

Tasks:
- Undercurrent design system overrides (Terracotta or Moss palette finalized).
- Domain scripts in `scripts-undercurrent`: CRUD for journal entries, dropped identities, evaluations.
- Domain templates in `design-undercurrent`: ReflectionScreen, BucketEvaluationScreen, IdentityListScreen.
- Wire full Undercurrent product flow.
- Beta test with small group (10–20 users) for 2 weeks during this phase.
- Iterate on substrate-level issues uncovered.
- Use memory and cost modules in the user-visible Undercurrent UI.

**Deliverable:** Undercurrent runs end-to-end as a real product on top of the substrate. Substrate gains a handful of improvements based on real usage.

**Owner:** product lead + designer + UI devs.

## Phase 7 — Compliance & Store Prep (Weeks 29–30)

**Goal:** ready to submit.

Tasks:
- Consent modal polish.
- AI content labeling everywhere.
- In-app reporting flow.
- Privacy manifest audit (every dependency).
- Privacy policy hosted at stable URL.
- Store listings: name, description, screenshots, content rating.
- Demo mode polished and reviewer-friendly.
- Test accounts and review notes.
- TestFlight beta rounds.
- Play Internal Testing rounds.
- External tester confirmation on full flow.

**Deliverable:** Undercurrent submitted to both stores; substrate 1.0.0 published to Maven Central + Swift Package Manager.

**Owner:** product lead, compliance specialist if available.

## Phase 8 — Launch + Iterate (Week 31+)

**Goal:** public OSS release.

Tasks:
- Public announcement (blog, social, HN, relevant subreddits).
- Documentation site live with quickstart, architecture, all script/template docs, harness extension guide.
- Second example app (smaller, demonstrating substrate reuse without modification).
- Community processes active: issue triage rotation, PR review SLA, monthly roadmap update.
- Begin gathering real-user feedback for v1.1 priorities.

**Deliverable:** OSS substrate live; reference app shipping in stores; first community PRs landing.

## Total timeline

~31 weeks ≈ 7 months, plus 1–2 weeks of buffer scattered across phases ≈ **7.5 months** to v1.

## Parallelization plan

Solo dev: serial through phases.

Two devs (you + one collaborator):
- Phase 3 onward: backend dev (scripts, harness) + UI dev (design system, templates) run parallel. Cuts wall-clock ~30%.

Four devs (you + 3):
- After Phase 2: split into backend, Android UI, iOS UI, design/product. Cuts wall-clock ~50%.

Six devs: hits diminishing returns; coordination overhead grows. Probably too many for v1.

## Phase-by-phase risk hot spots

- **Phase 0:** iOS UI strategy validation. If SwiftUI native + KMP-below proves untenable, the whole plan re-bases. De-risk first.
- **Phase 2:** First store submission (stub app). Surfaces compliance surprises early.
- **Phase 3:** First real OS-touching scripts. Permissions and platform divergence emerge.
- **Phase 4:** First UI rendering loop. Latency and prop-schema issues surface.
- **Phase 6:** First real app. Module boundaries get stress-tested.
- **Phase 7:** Store submission. Always more friction than expected; plan for resubmission.

## If you slip

Right cuts, in order of preference:

1. Drop the second example app (Phase 8). Substrate + Undercurrent alone are enough.
2. Defer `harness-cost` to v1.1. Memory is the higher-value Tier 2 module.
3. Cut template count from 8 to 6 (drop Dashboard, Chart). Add in v1.1.
4. Cut script count from ~22 to ~18 (drop network.fetch, calendar.create). Add in v1.1.
5. Defer all of Tier 2. Ship Tier 1 harness + scripts + templates + Undercurrent.

Wrong cuts (don't do these):

- Don't cut the consent modal, content labeling, or reporting — store will reject.
- Don't cut audit log — user trust depends on it.
- Don't cut destructive-action confirmation — high-risk for harm.
- Don't cut the harness's reliability + quality middleware — without them, the app feels broken.
- Don't cut tests to save time. They save more time than they cost over 7 months.

## What "Phase 8 done" looks like

You can hand a new contributor the docs site URL and they can:

- Understand the architecture in 30 minutes.
- Pick a module from the open roadmap.
- Set up locally and run tests in under an hour.
- Open a draft PR within their first day.

You can hand a new user the App Store link and they can:

- Install the app.
- See real value in the demo before pasting a key.
- Paste their key with confidence (or skip and use demo mode).
- Have a real conversation that does real things.

If both of those land, v1 is done.
