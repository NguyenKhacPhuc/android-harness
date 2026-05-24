# 01 — Vision & Scope

## What this is

A foundational, open-source Kotlin Multiplatform substrate and reference app where:

- A user describes intent in natural language.
- An LLM (Claude) orchestrates pre-defined scripts and pre-built UI templates.
- A reliability / quality / observability / cost / behavior / memory harness wraps every LLM interaction.
- The result is a polished, native, App Store–compliant mobile experience built on a substrate other developers can adopt and extend.

The mental model: scripts are the **verbs** the app can do. Templates are the **nouns** the app can show. The LLM composes them per-request to fulfill intent. The substrate is the infrastructure that makes this composition reliable, observable, and reusable across apps.

## What this is NOT

- **Not a code-generation tool.** No runtime-generated UI or scripts in v1. Everything the LLM can do or show is pre-defined by you or by app authors.
- **Not a managed AI service.** Users bring their own Anthropic API key. No backend, no accounts, no billing on your side.
- **Not a chat wrapper.** The substrate enables apps that look and behave like real apps, not chat interfaces with tools.
- **Not commercial in v1.** Architected so a managed commercial layer can be added later by wrapping the substrate, but not part of v1 scope.
- **Not a full agent framework competitor to Koog/LangChain.** It builds on Koog. It's the mobile-specific substrate above the agent loop.

## Who this is for

Primary audience for v1:

- **Mobile developers** who want to build LLM-orchestrated apps without rebuilding the agent + harness stack from scratch.
- **Power users / prosumers** who tolerate BYO API keys in exchange for privacy and control.
- **OSS contributors** interested in agentic mobile infrastructure.

Not for v1 (commercial layer territory):

- General consumers who won't manage API keys.
- Enterprises needing centralized management, SSO, compliance certifications.

## Success criteria for v1

A v1 release is successful when all of the following are true:

1. **Substrate published** to Maven Central (Kotlin/JVM artifacts) and Swift Package Manager (iOS framework).
2. **Reference app shipped** on Google Play and the App Store, approved through normal review.
3. **A second example app exists** in the repo proving substrate reusability without modifying the substrate itself.
4. **Documentation site live** with quickstart, architecture overview, every script and template documented, and the harness extension model documented.
5. **External contribution merged.** At least one non-trivial PR from a contributor outside the original team.
6. **Harness verified.** Reliability, quality, observability, cost, and memory modules all integrated and tested against the reference app.
7. **Test coverage** above 80% on `core`, `contracts`, `scripts-core`, `harness/*`, and the intent router.
8. **Minimal evaluation suite** runs in CI against a small intent corpus (10–20 intents), catching catastrophic regressions on model upgrades.

## Non-goals (v1)

Explicitly out of scope. Each has a strong reason; defer doesn't mean ignore.

- **Full safety harness** (content classifiers, refusal policy engine, prompt-injection detection beyond basics). Reason: needs real usage data to design well. Deferred to v1.1.
- **Full evaluation suite** (large intent corpus, scoring dashboards, prompt A/B testing). Reason: same — design after observing usage.
- **Model-agnostic abstraction** (OpenAI client, Ollama client). Reason: ship Claude well first; generalize later.
- **Automatic background memory** (agent infers and stores without explicit calls). Reason: privacy risk; explicit memory is safer to ship first.
- **Streaming agent responses.** Reason: complicates tool-use handling significantly; v1 ships non-streaming for stability.
- **MCP client integration.** Reason: substrate doesn't need it for v1; add cleanly in v1.1 via the existing `LLMClient` seam.
- **iOS Shortcuts / Android Tasker integration.** Reason: nice-to-have; not core.
- **On-device LLM models** (Llama, Gemma). Reason: substantial engineering; quality gap to Claude is large; ship Claude path first.
- **Voice I/O, multimodal inputs.** Reason: not core to substrate value prop.
- **Plugin marketplace, no-code editor.** Reason: requires substrate maturity first.
- **Reproducible builds verified end-to-end** (especially iOS). Reason: stretch goal; v1 documents the path, v1.1 verifies it.

## Core design decisions (settled)

These won't be revisited without major cause:

1. **OSS first.** Commercial path stays open via swappable `LLMClient`, but v1 is OSS.
2. **Both platforms day one.** Android + iOS via Kotlin Multiplatform.
3. **On-device agent.** No backend; BYO Anthropic API key.
4. **Defense-in-depth local security.** Keystore/Keychain, network allowlist, sandboxed scripts, audit log.
5. **Pre-defined everything.** Scripts and templates ship with the app; nothing generated at runtime.
6. **Four-layer design system.** Tokens → components → templates → semantic intents.
7. **Harness as middleware.** Each harness concern is a separate `LLMMiddleware` composed into a chain.
8. **Explicit memory only.** LLM stores via `memory.store`; user sees everything in a viewer; can delete.
9. **Apache 2.0.**

## What "v1 done" feels like

A new developer can:

- `git clone` the substrate, build it, run the reference app on Android and iOS.
- Read the docs in an afternoon.
- Add a new script to a new app in a day.
- Add a new template in two days.
- Open a PR that gets reviewed at module-boundary level without confusion about ownership or impact.

A new user can:

- Install the reference app from either store.
- See a meaningful demo before being asked for a key.
- Paste their Anthropic API key into a paste screen that feels trustworthy.
- Have a real conversation with the app that produces real OS effects (reminders, journal entries, files).
- See in the app what Claude is doing on their behalf, what it costs, and what it remembers.

If both of those experiences land well, v1 is real.
