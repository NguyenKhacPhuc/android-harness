# ADR-001 — No streaming agent responses in v1

- **Status:** Accepted
- **Date:** 2026-05-19
- **Deciders:** Tech lead
- **Supersedes:** —

## Context

Streaming LLM responses (server-sent events from Anthropic, rendered
incrementally in the UI) is a real UX win. It's also a meaningful architectural
choice that affects every layer of the harness and every UI surface that renders
agent output.

The wrinkle: streaming and tool use don't compose cleanly. The Anthropic API
streams tokens as they're produced; tool calls arrive as partial JSON inside
content blocks. Validating a malformed tool call mid-stream, or wrapping a
streamed response in observability/quality/cost middleware, requires either:

1. Buffering until the response completes (which negates the UX benefit), or
2. Building stream-aware middleware that operates on deltas, with all the
   complexity that implies (partial validation, incremental retries, broken
   tool-call recovery).

v1 ships a substrate with a six-middleware chain plus a tool-use agent loop.
Adding stream-awareness to all of that in v1 adds substantial scope for an
incremental UX win.

## Decision

v1 ships non-streaming only. The `LLMClient` interface (in `contracts`) has
`supportsStreaming` on `LLMCapabilities` so callers can detect future stream
support, but `DirectAnthropicClient.capabilities.supportsStreaming = false` and
no middleware in v1 implements streaming.

The harness chain operates on completed `LLMResponse` objects. Token usage,
retry, validation, and observability all see whole responses, which keeps each
middleware testable in isolation against canned `LLMResponse` fixtures.

## Consequences

**Positive**

- Significantly simpler middleware: each middleware is a pure
  `(LLMRequest) → LLMResponse` function (with retry as the only nuance).
- Each middleware is testable against canned responses without stream fixtures.
- Tool-use handling stays straightforward: tool calls are validated when the
  response arrives, not incrementally.

**Negative**

- "Time to first token" is slower than chat-UI competitors. For long responses,
  the user waits.
- We pay a UX deficit relative to apps that stream.

**Neutral**

- The `LLMClient` interface is forward-compatible: streaming can be added in
  v1.1 by introducing an additional method (e.g. `chatStream(...) → Flow<…>`)
  and stream-aware middleware variants.

## Alternatives considered

- **Ship streaming in v1.** Rejected: substantial scope expansion, harder
  middleware design, would push v1 past the 7.5-month budget.
- **Ship streaming for text-only responses; non-streaming for tool calls.**
  Rejected: forks the loop into two code paths, doubles testing surface.

## When to revisit

When the substrate has at least one app shipping and we have real signal on
how often the "time to first token" gap is noted by users. The current bet is
that for an agent that *does things* (tool calls), users care less about token
latency than about the action completing.

See also: `docs/13-v1.1-backlog.md` "Streaming agent responses".
