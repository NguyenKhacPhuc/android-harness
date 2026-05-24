# WeftStrategy — pluggable agent-loop policy

- **Status:** Design — approved direction, not yet implemented
- **Date:** 2026-05-24
- **Tracks:** `architecture-vision.md` § Active misalignments → "Pluggable strategy"
- **Estimated scope:** ~500 LOC (one new interface in `:contracts`, one
  default impl + two reference impls in `:harness:agents`, four wire-up
  points in `WeftAgent`, threading through `WeftRuntime.buildAgent`).

## Why this exists

Four policy decisions are currently hardcoded in
[WeftAgent.kt](../../harness/agents/src/main/kotlin/dev/weft/harness/agents/WeftAgent.kt):

1. **Model routing** — `modelRouter.route(...)` at
   [:576](../../harness/agents/src/main/kotlin/dev/weft/harness/agents/WeftAgent.kt#L576)
   (blocking path) and `:709` (streaming path).
2. **Retry policy** — `RetryPolicy()` constructor default at `:97`,
   threaded into `withRetry()` at `:274` / `:382`.
3. **Cache-tier mapping** — `cacheBinder.cachedSystem(..., STATIC)` at
   `:566`, `cacheBinder.cachedUser(..., SESSION)` for older history;
   the tail-volatile cutoff (`HISTORY_VOLATILE_TAIL_TURNS = 2`) at
   `:824`.
4. **Per-turn iteration cap** — `MAX_ITERATIONS_DEFAULT = 10` at `:803`,
   threaded into `AIAgentConfig.maxAgentIterations` at `:592`.

Apps can override the constructor defaults but cannot vary them per turn
or per agent. Hosts that want a "frugal demo" or "burst-mode researcher"
profile must fork `WeftAgent`. The SDK should expose one swap-in
interface that controls all four.

## Decision

`WeftStrategy` is a **per-`WeftAgent`** policy object, passed in as a
single constructor param. It replaces four currently-independent
constructor params. The default implementation reproduces current
behavior bit-for-bit so the migration is a no-op for existing apps.

Per-agent (not per-runtime) is deliberate: the [multi-agent
registry](multi-agent-registry.md) work will want each `AgentDeclaration`
to pick its own strategy ("researcher" gets `BurstStrategy`, "writer"
gets `FrugalStrategy`). Putting strategy on the runtime now would force
a re-plumb in 1500 LOC of new code; we'd rather pay the small
abstraction cost upfront.

## Contract

Lives in `:contracts` (or a new `:harness:strategy` module if we want
type isolation from the rest of harness; ~50 LOC either way).

```kotlin
public interface WeftStrategy {

    /**
     * Picks the model tier for this turn. Called once per [WeftAgent.send].
     * Return null to defer to the agent's stored default tier and let
     * [ModelRouter] decide from input shape; return a concrete tier to
     * pin (overrides routing heuristics).
     */
    public fun pickTier(
        input: WeftUserInput,
        recent: List<Turn>,
    ): ModelTier?

    /** Retry policy applied to LLM + tool failures via [withRetry]. */
    public val retry: RetryPolicy

    /**
     * Cache-tier mapping. Keyed by tool name (for the prompt-side
     * tool-catalog blocks) and special keys "system" / "history-tail".
     * Unknown keys default to NONE (uncached).
     */
    public val cacheTiers: Map<String, CacheTier>

    /**
     * Per-turn iteration cap. Distinct from [WeftAgent.maxIterations] —
     * this lets the strategy vary the cap by input (e.g., longer for
     * `BurstStrategy`, lower for `FrugalStrategy`).
     */
    public fun maxIterations(input: WeftUserInput): Int

    /**
     * Tail length to keep VOLATILE (uncached). Currently hardcoded to
     * `HISTORY_VOLATILE_TAIL_TURNS = 2`. Strategies can tune this:
     * larger for chat-heavy workloads where the last few turns vary a
     * lot, smaller for tool-heavy workloads where the same history
     * gets reused across many tool roundtrips.
     */
    public val historyVolatileTailTurns: Int get() = 2
}
```

Three reference implementations ship in `:harness:agents`:

- **`DefaultStrategy`** — what's hardcoded today. `RetryPolicy()` with
  current defaults; cache map `{ "system" → STATIC, "history-tail" →
  VOLATILE, * → SESSION }`; `maxIterations` returns 10; `pickTier`
  returns null (defers to `ModelRouter`).
- **`FrugalStrategy`** — always returns `ModelTier.Cheap`; aggressive
  STATIC cache on every tool catalog block; `maxIterations` returns 4;
  retry policy is `RetryPolicy(maxAttempts = 2)`.
- **`BurstStrategy`** — `pickTier` returns null but `maxIterations`
  returns 20; cache map keeps everything SESSION (no aggressive STATIC,
  since burst use breaks cache anyway); retry policy
  `RetryPolicy(maxAttempts = 1)` (fail fast, retry at the user layer).

## Wire-up in `WeftAgent`

Constructor change (preserving binary compatibility with a default
`DefaultStrategy()`):

```kotlin
public class WeftAgent(
    // ...existing params...
    private val strategy: WeftStrategy = DefaultStrategy(),
    // retryPolicy + maxIterations + behaviorConfig become derivable;
    // keep them as constructor params for one release with a
    // deprecation warning, then remove.
)
```

The four swap-ins:

1. **`buildAgentForThisTurn`** at `:550` — read
   `routedModelTier = strategy.pickTier(input, recentTurns) ?: tierHint`
   before calling `modelRouter.route(...)`. Pass through
   `RoutingContext.tierHint`.
2. **`withRetry` calls** at `:274` (blocking) and `:382` (streaming) —
   replace `retryPolicy` arg with `strategy.retry`.
3. **`cacheBinder` calls** at `:566` and the per-history-message loop
   — read `strategy.cacheTiers["system"] ?: CacheTier.STATIC` and
   `strategy.cacheTiers["history-tail"] ?: CacheTier.VOLATILE`. Cut at
   `strategy.historyVolatileTailTurns` instead of the constant.
4. **`AIAgentConfig.maxAgentIterations`** at `:592` / `:756` —
   `strategy.maxIterations(input)` instead of `maxIterations`.

## Wire-up in `WeftRuntime`

`buildAgent` at
[:460](../../android/src/main/kotlin/dev/weft/android/WeftRuntime.kt#L460)
gains an optional `strategy: WeftStrategy` arg that flows into the
`WeftAgent` constructor. `WeftRuntime.create` does NOT take a global
strategy in v1 — apps that want one app-wide strategy pass it on each
`buildAgent()` call. This avoids committing to a placement that #4 will
want to revise (per-`AgentDeclaration`).

## What this is NOT doing

- **Not introducing per-tool cache override APIs.** The `cacheTiers`
  map is enough for v1. If apps need fine-grained control later, a
  `Map<ToolDescriptor, CacheTier>` overload is additive.
- **Not changing `ModelRouter`.** Strategy returns a *hint*; the
  router still owns the routing logic. This keeps the two concerns
  orthogonal — a strategy is *policy*, a router is *mechanism*.
- **Not adding new lifecycle hooks.** Strategy is read at the start of
  each turn. `onTurnStart` / `onTurnComplete` callbacks are tempting
  but out of scope; if needed, that's the
  [LLMMiddleware](../adr/ADR-004-middleware-over-extension-point.md)
  chain's job, not strategy's.

## Migration plan

1. Add `WeftStrategy` interface + `DefaultStrategy` impl. Ship as
   internal first; verify the four substitution sites in `WeftAgent`
   compile and pass existing tests.
2. Make `DefaultStrategy` public; add `strategy` param to `WeftAgent`
   with default. Deprecate `retryPolicy` and `maxIterations` params
   (point users at strategy fields).
3. Add `FrugalStrategy` + `BurstStrategy` as public reference impls.
   Add a test fixture that swaps strategies and asserts retry / cache
   / iteration-cap behavior changes.
4. Update [follow-ups.md](../follow-ups.md) and
   [architecture-vision.md](../architecture-vision.md) to mark this as
   landed.

Removal of the deprecated params is a follow-up — out of scope for the
initial PR to keep diff size small.

## Open questions

- **Strategy mutability mid-conversation?** If the user switches modes
  (Settings → "Frugal mode"), does the active `WeftAgent` see the new
  strategy on its next turn, or does it require `runtime.buildAgent()`
  again? Default proposal: strategy is captured at construction (the
  user has to rebuild the agent — i.e., start a new chat — to switch).
  Apps that want hot-swap can wrap their strategy in a delegate that
  reads a `StateFlow<WeftStrategy>` on each call. This keeps the
  contract simple.
- **Provider-specific cache tiers.** Anthropic has TTL distinctions
  (5min vs 1hr) that other providers don't. Punt to `cacheTiers`
  values being opaque `CacheTier` enum values; the binder maps to
  provider-specific representations. No strategy change needed.

## Risks

- **Behavioral drift.** Replacing four hardcoded values with one
  pluggable contract is the canonical place for "I changed the
  defaults and didn't notice the test that relied on retry-count = 3."
  Mitigation: `DefaultStrategy` is byte-for-byte identical to today's
  hardcoded values; run the existing
  [WeftAgent](../../harness/agents/src/main/kotlin/dev/weft/harness/agents/WeftAgent.kt)
  test suite unchanged through migration.
- **Cache-key churn.** Re-mapping cache tiers per-strategy means
  swapping strategies invalidates the prompt cache on the next turn.
  Expected, but worth documenting so users don't conclude their cache
  is broken.
