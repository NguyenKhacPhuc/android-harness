# ADR-004 — Harness as a middleware chain, not a single AgentRunner extension point

- **Status:** Accepted
- **Date:** 2026-05-19
- **Deciders:** Tech lead
- **Supersedes:** —

## Context

The substrate wraps every LLM call with multiple cross-cutting concerns:
reliability, quality, observability, cost, behavior, memory. There are two
shapes this can take:

1. **Single extension point:** a `AgentRunner` exposes one or two hooks
   (e.g., `beforeLLMCall`, `afterLLMCall`) and all concerns implement those
   hooks. Order is managed at runtime by priority numbers or similar.
2. **Middleware chain:** each concern is its own `LLMMiddleware` and they
   compose into an ordered chain that wraps the terminal `LLMClient.chat(...)`
   call. Pattern from OkHttp interceptors, Express middleware, Django
   middleware.

The chain is more code surface upfront (an interface, a chain composer, a
test harness) but markedly easier to reason about as concerns multiply.
Retries, short-circuits, and conditional bypasses all compose naturally.

## Decision

The harness is a middleware chain. The interface lives in `contracts` as
`LLMMiddleware`, with `MiddlewareChain` composing an ordered list and
terminating in the `LLMClient`. See `docs/04-locked-interfaces.md` for the
locked interface.

Composition order (outermost first):

```
Observability → Reliability → Cost → Quality → Behavior → Memory → LLMClient
```

Each middleware can modify the request before passing it down, modify the
response on the way up, short-circuit by not calling `proceed()`, or retry
by calling `proceed()` multiple times.

## Consequences

**Positive**

- Each middleware is independently testable against a mock chain.
- Retry (reliability) and validation-retry (quality) compose naturally:
  reliability wraps the validation cycle.
- Adding a new concern is "implement `LLMMiddleware`, insert in chain" — no
  central dispatcher to modify.
- Disabling a middleware (for testing or debug builds) is "remove from list".
- The chain composes to a single `suspend fun` for the agent loop — no
  callback maze.

**Negative**

- Composition order matters and isn't enforced by the type system. Wrong
  order (e.g., quality outside cost) is a real bug. Documented in
  `docs/07-harness.md` and verified in integration tests.
- Slightly more boilerplate for trivial wrappers.

**Neutral**

- Most developers have seen the pattern (OkHttp, Express). Low cognitive cost.

## Alternatives considered

- **Single extension point with priority ordering.** Rejected: priority
  numbers are a code smell; "what runs before what" should be visible in the
  chain construction, not encoded in numbers.
- **Aspect-oriented framework.** Overkill for a six-concern problem.

## When to revisit

If a real concern emerges that doesn't fit the
`(LLMRequest) → LLMResponse` shape — e.g., streaming (ADR-001 defers this
to v1.1), or sub-LLM-call concerns like per-chunk validation. The chain
shape will need extension at that point.
