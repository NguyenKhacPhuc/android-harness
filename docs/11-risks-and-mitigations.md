# 11 — Risks & Mitigations

What could go wrong, ordered by likelihood × impact.

## Top risks

### R1. KMP iOS friction undermines the multiplatform story
- **Likelihood:** Medium
- **Impact:** High
- **Why:** KMP for iOS works but has rough edges: framework export complexity, Swift interop friction, debugging hassles, occasional toolchain breakage.
- **Mitigation:**
  - Validate the iOS UI strategy in Phase 0 with a real (if small) framework export.
  - Keep KMP scope below the UI line — SwiftUI is native, calls into KMP via clean interfaces.
  - If KMP iOS proves untenable mid-build, have a Plan B: substrate is JVM/Kotlin only on the data + agent side, iOS app re-implements scripts and harness natively (significant scope expansion; would force a major plan revision).

### R2. Solo bandwidth runs out before v1
- **Likelihood:** High
- **Impact:** High
- **Why:** 7.5 months solo is a lot. Real life, day job (you're a full-time Android dev), motivation cycles.
- **Mitigation:**
  - Phase boundaries are real slip points. Slip a phase, don't collapse it.
  - Cut ruthlessly when needed (see `09-roadmap.md`'s "If you slip" section).
  - Recruit a collaborator by Phase 3 if possible — even part-time. The parallelization is real.
  - Ship something publicly at Phase 5 (substrate + minimal example), even if not Undercurrent. Public commitment > private intent.

### R3. Store rejection delays v1 launch
- **Likelihood:** Medium
- **Impact:** High
- **Why:** AI policies tightening; reviewers skeptical of AI-built/AI-using apps; first submission likely to surface unexpected requirements.
- **Mitigation:**
  - Compliance workstream from day one (not Phase 7).
  - Stub submission in Phase 2 to learn review behavior before v1 is on the line.
  - Demo mode + clear functional value baked into the reference app.
  - Plan for two resubmission rounds in Phase 7.

### R4. Koog limitations discovered late
- **Likelihood:** Medium
- **Impact:** Medium
- **Why:** Koog is young, has bugs, may not cover edge cases.
- **Mitigation:**
  - Wrap Koog in a thin layer (`llm-anthropic`); don't deeply depend on internals.
  - Be ready to swap to direct Anthropic SDK if needed. The substrate's `LLMClient` interface is provider-agnostic.
  - Maintain a list of Koog issues encountered; contribute fixes upstream when possible.

### R5. Scope creep
- **Likelihood:** Very High
- **Impact:** High
- **Why:** Everything looks possible from the architecture diagram. New ideas during build are constant.
- **Mitigation:**
  - Strict v1 scope as documented.
  - Every addition requires a deferral.
  - Maintain a v1.1 backlog visible to all contributors.
  - Tech lead has explicit authority to refuse scope additions.

### R6. The harness latency tax is too high
- **Likelihood:** Low
- **Impact:** Medium
- **Why:** Stacking 6+ middleware on every LLM call adds up.
- **Mitigation:**
  - Benchmark from Phase 2.5; budget < 50ms p50 chain overhead.
  - Make heavy middleware (memory, observability) async where possible.
  - Bypass middleware in special cases (e.g., trace recording only on opt-in for high-throughput automation).

### R7. The script catalog grows unboundedly
- **Likelihood:** High
- **Impact:** Medium
- **Why:** Every intent the LLM can't handle feels like it needs a new script.
- **Mitigation:**
  - Hard cap at 22 scripts in `scripts-core` (plus 2 memory scripts in `harness-memory`) for v1 — 24 total tools exposed to the LLM.
  - Discipline test for new scripts (see `05-script-catalog.md` anti-patterns).
  - When tempted to add a script, first ask: parameter on an existing one? Filter field? Composition of existing scripts?

### R8. Compose ≠ SwiftUI: design system drift
- **Likelihood:** Medium
- **Impact:** Medium
- **Why:** Two implementations written separately will drift in behavior, accessibility, polish.
- **Mitigation:**
  - Shared prop schemas in `design-system-api`. The contract is identical even if rendering is separate.
  - Side-by-side visual reviews at every milestone.
  - Snapshot tests on both sides for the same prop combinations.
  - Designer sign-off required on both.

### R9. Module boundaries reveal themselves as wrong mid-build
- **Likelihood:** Medium
- **Impact:** Medium
- **Why:** First module design is rarely the right one. Real usage reveals friction.
- **Mitigation:**
  - Phase 6 (reference app) is when you'll discover this. Budget for some module-boundary refactors.
  - Don't over-commit to module structure pre-1.0. Re-org allowed.
  - Internal vs public visibility discipline lets you refactor internals freely.

### R10. PII detection in memory module produces false positives
- **Likelihood:** High
- **Impact:** Low
- **Why:** Pattern-based detection over-triggers.
- **Mitigation:**
  - Conservative initial patterns.
  - User can override ("yes, store this anyway").
  - Iterate patterns based on real reports.
  - Document clearly that PII detection is best-effort, not a guarantee.

### R11. Anthropic API or pricing changes mid-build
- **Likelihood:** Medium
- **Impact:** Low–Medium
- **Why:** Anthropic releases models, deprecates models, adjusts prices.
- **Mitigation:**
  - Model selection is config, not hardcoded.
  - Pricing config in a file, not embedded in code.
  - Watch Anthropic's changelog; update as needed.
  - Pin to stable models; adopt new ones deliberately.

### R12. User trust failure on BYO key
- **Likelihood:** Low (for OSS audience), High (for general consumers)
- **Impact:** Medium for OSS, High if mistargeted
- **Why:** Pasting an API key is a real trust ask.
- **Mitigation:**
  - Visible trust scaffolding (Keystore claim, network claim, code links).
  - Open source from day one with reproducible builds documented.
  - Acknowledge skepticism directly in the paste UX.
  - Don't target audiences for whom this is wrong (general consumers).

### R13. Reference app (Undercurrent) design changes mid-build
- **Likelihood:** Medium
- **Impact:** Low
- **Why:** Product design evolves; what felt right at Phase 0 may not at Phase 6.
- **Mitigation:**
  - Reference app changing is signal, not failure. It informs substrate improvements.
  - Substrate stays product-agnostic. Undercurrent's needs are absorbed through the extension points (custom scripts, custom templates).

### R14. iOS reproducible build is impractical
- **Likelihood:** High
- **Impact:** Low
- **Why:** iOS code signing makes true reproducibility very hard.
- **Mitigation:**
  - Document the limit in `SECURITY.md`.
  - Android reproducibility is more achievable and provides most of the value.
  - Accept that iOS users trust based on open source + signed releases, not bit-for-bit reproducibility.

### R15. Community doesn't materialize
- **Likelihood:** Medium
- **Impact:** Low for v1, Medium long-term
- **Why:** OSS visibility is hard. Most projects don't get contributors.
- **Mitigation:**
  - The substrate is useful on its own; community is a bonus, not a requirement.
  - Clear contributor docs and "good first issue" labels remove obvious friction.
  - Promote the launch in relevant places (HN, niche communities, conference talks).
  - One real contributor by Phase 8 is sufficient for v1.

### R16. Privacy manifest gaps cause silent rejection
- **Likelihood:** Medium
- **Impact:** Medium
- **Why:** New requirement; easy to miss for a transitive dependency.
- **Mitigation:**
  - CI job audits manifest presence for every dependency.
  - Manual audit in Phase 7.
  - Submission rehearsal: submit a version specifically to check for ITMS-91053 type errors.

### R17. Destructive-action confirmation creates UX friction
- **Likelihood:** Medium
- **Impact:** Low
- **Why:** "Are you sure?" on every action feels annoying.
- **Mitigation:**
  - Only truly destructive scripts marked destructive.
  - Bulk confirmation possible: "Don't ask again for this conversation" for repeat patterns.
  - Confirmation UX is tasteful, not jarring.

### R18. App Store policy changes between Phase 7 and launch
- **Likelihood:** Low–Medium
- **Impact:** Medium
- **Why:** Policies update; current rules might change.
- **Mitigation:**
  - Track Apple Developer News and Google Play policy changelogs.
  - Build in margin: ship with policies as of submission week.
  - Accept some rework if policy shifts; rebuild quickly.

## Failure modes that need explicit recovery plans

Three scenarios specifically:

### A. Anthropic outage during a user's session
- App detects (via reliability middleware circuit breaker).
- Shows banner: "Claude is currently unavailable. You can still [list of offline-capable scripts]."
- Queues user messages locally; retries on circuit close.
- Audit log records the outage window for transparency.

### B. User's quota exhausted mid-conversation
- Cost middleware short-circuits.
- Shows clear message with remaining time until reset.
- Offers option to raise cap, or to use a cheaper model.
- Does not silently degrade.

### C. Malformed LLM response that retries can't fix
- Quality middleware exhausts retries.
- Surfaces error to user: "Claude returned an unexpected response. Could you rephrase?"
- Trace recorded for debugging.
- User can mark as bug → goes to local feedback queue.

## What no mitigation can fix

A few risks have no real mitigation; flag them honestly:

- **Anthropic changes pricing 10x.** App stays usable but expensive. Users with BYO key choose to use or not.
- **A novel exploit against the script sandbox.** Audited at v1, but no defense is perfect. Disclosed via SECURITY.md if found.
- **A bug in the LLM that causes harmful tool calls in edge cases.** Destructive action confirmation is the backstop; audit log catches after-the-fact.
- **Open source means anyone can fork and abuse.** Inherent to the model. License doesn't prevent it; reputation does.

These are accepted risks of the design.

## Risk register maintenance

This document is updated each phase based on new risks discovered. New risks added; resolved risks struck through (with date). Review at each phase boundary.
