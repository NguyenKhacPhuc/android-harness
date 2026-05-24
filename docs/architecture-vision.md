# Architecture vision — separation of concerns

This document captures the SDK/app responsibility split Weft is converging
toward. Use it as a north star when reviewing PRs or proposing changes
that move logic across the boundary.

## The rule

> **The SDK provides everything. The app just registers.**

Concretely:

| SDK owns (and apps don't touch) | App owns (and the SDK doesn't know) |
| --- | --- |
| Tool framework (`WeftTool`, registry, dispatch, permission/destructive gates) | Which tools to register (`extraToolsFactory`) |
| Memory store + `memory_*` tools | What to remember (the agent decides per-turn) |
| Conversation store, trace store, cost store, circuit breaker | Which provider key + how to UX it (Settings) |
| Model routing (`ModelTier`, `ModelPool`, default pools per provider) | Which provider is active, per-tier overrides |
| Prompt assembly: tool catalog, UI catalog, data-source catalog, behavioral rules, binding DSL docs | Identity + persona text (small `appPromptPreamble`) |
| Data-binding system: `BindingEvaluator`, `BindingAwareRenderer`, `ActionExecutor`, reactive `DataSource.changes` | Which `DataSource`s exist (descriptions go to the SDK auto-rendered) |
| MCP discovery, OAuth client, scheduled notifications, all OS bridges | Which MCP servers are connected (drawer UI) |
| Compose UI primitives (`WeftComponent`, `TreeRenderer`, `AgentRenderedTreeScreen`) | Custom components for app-specific use (`extraComponentsFactory`) |
| Multi-agent registry + routing (future) | Which sub-agents an app ships |
| Pluggable strategy hook (future) | Which strategy to plug in |

The app's responsibilities collapse to **registration + UI shell**. Every
substrate-level concern that doesn't depend on which app is consuming it
should live in the SDK.

## Active misalignments

Tracked here so they show up in code review:

- **`runBlocking` on suspend factories.** Apps still wrap
  `createWithMcpServers` in `runBlocking` at DI configuration time
  because the factory is suspend (MCP discovery is async). The fix is
  either a sync factory + lazy MCP discovery, or a builder pattern the
  app can construct in a coroutine-friendly site. See
  [issue / design doc placeholder].
- **Multi-agent.** `WeftRuntime` ships exactly one `WeftAgent` today.
  Hosts that want specialized agents (a "research" agent, a "writing"
  agent) have to fork the runtime. Design sketch below.
- **Pluggable strategy.** Retry / cache / routing / quota policies are
  hardcoded paths in the agent loop. Design sketch below.

## Multi-agent — design sketch

The goal: a host registers multiple agents, each with its own role +
tool allowlist + system-prompt fragment, and the substrate routes turns
to the right one. Like sub-agents (which already exist) but exposed as
first-class top-level agents the user can address directly.

Contract shape (proposed):

```kotlin
public data class AgentDeclaration(
    public val name: String,          // "researcher", "writer"
    public val displayName: String,   // "Researcher"
    public val description: String,   // "Use for deep, cited research"
    public val systemFragment: String, // Role preamble; concatenated with app preamble
    public val allowedTools: Set<String>, // Tool name whitelist (defaults to all)
    public val modelTier: ModelTier? = null, // Pin the tier if needed
)

// Add to WeftRuntime.create:
agents: List<AgentDeclaration> = emptyList(),

// And a runtime-side accessor:
public fun agent(name: String): WeftAgent
public val agents: Map<String, WeftAgent>
```

UX side: a one-line agent selector on the chat surface (or an
"@<agent>" mention parser) lets the user steer. The orchestration LLM
can also delegate via a `delegate_to_agent` substrate tool, which
plugs into the existing `SubAgentRunner` machinery — sub-agents
become a special case of multi-agent.

Estimated scope: ~1500-2000 LOC across `:harness:agents` (registry,
routing) + `:android` (DI) + maybe `:android-compose-defaults` (the
selector). Substantial. Worth a focused multi-session effort.

## Pluggable strategy — design sketch

A `WeftStrategy` interface that apps swap to customize agent-loop
behavior without forking. Things a strategy controls:

```kotlin
public interface WeftStrategy {
    // Which model tier should this user input default to?
    public fun pickTier(input: WeftUserInput, recent: List<Turn>): ModelTier

    // Retry policy on tool failures + LLM failures
    public val retry: RetryPolicy

    // Cache-binding policy (which tools are STATIC vs SESSION vs VOLATILE)
    public val cacheTiers: Map<String, CacheTier>

    // Per-turn iteration cap (currently a single MAX_ITERATIONS_DEFAULT constant)
    public fun maxIterations(input: WeftUserInput): Int
}

// Add to WeftRuntime.create:
strategy: WeftStrategy = DefaultStrategy(),
```

Three reference strategies the SDK ships:

- **DefaultStrategy** — what's hardcoded today. Status quo behavior.
- **FrugalStrategy** — always picks Cheap tier; aggressive cache hits;
  low iteration cap. Demo/dev profile.
- **BurstStrategy** — parallelizes tool calls aggressively; higher
  iteration cap; less retry tolerance.

Apps pick one or write their own. Same separation rule: SDK provides
the interface + reference impls + the integration point in the agent
loop; the app picks/configures.

Estimated scope: ~500 LOC. Smaller than multi-agent and a natural
prerequisite for it (multi-agent will want per-agent strategies).

## When in doubt

Test against the rule: *would another Weft host need this same
behavior?* If yes, it goes in the SDK. If it's purely about
Undercurrent's identity, screens, or design system, it stays in the
app.
