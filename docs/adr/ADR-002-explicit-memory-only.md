# ADR-002 — Memory is explicit-only in v1

- **Status:** Accepted
- **Date:** 2026-05-19
- **Deciders:** Tech lead
- **Supersedes:** —

## Context

The substrate's memory module (`harness-memory`) lets the agent persist
information across sessions. Two designs are possible:

1. **Explicit:** the LLM calls `memory.store(...)` and `memory.recall(...)` as
   normal tool calls. Every storage event is visible to the user.
2. **Implicit:** the substrate infers what's worth remembering from
   conversation context (named entities, stated preferences, etc.) and stores
   them in the background.

Implicit is more impressive when it works and disastrous when it gets the
defaults wrong — a memory store that silently records the user's home address
or political views is a privacy incident even if the underlying storage is
local-only.

The v1 audience (developers, prosumers, BYO-key) is more likely to trust the
substrate the more visible its memory behavior is. The substrate ships before
we have signal on what kinds of memory users actually find useful.

## Decision

In v1, memory is **explicit-only**:

- The agent stores memories *only* by calling the `memory.store` script.
- Every store call is recorded in the audit log and visible in the user-facing
  "Agent Memories" screen.
- The user can delete individual memories or wipe everything.
- No background memory inference. No silent persistence based on conversation
  parsing.

The substrate's system prompt nudges the agent toward asking the user before
storing anything non-trivial ("would you like me to remember that?").

## Consequences

**Positive**

- Users can fully predict what the agent remembers about them.
- Privacy story is simple and defensible.
- PII detection only needs to gate `memory.store` invocations, not the
  conversation stream.
- The reference app can build user-facing memory UX (review, delete, scope)
  on top of clear semantics.

**Negative**

- The agent occasionally forgets things a smarter system would have stored.
- Power users may want the convenience of inferred memory; they can wait for
  v1.1 (with confirmation flow) or build it themselves.

**Neutral**

- `harness-memory` middleware is straightforward: inject recalled memories
  before LLM call, intercept `memory.store` for PII checks. No background
  inference loop.

## Alternatives considered

- **Implicit by default, with opt-out.** Rejected: wrong defaults compound;
  privacy mistakes are hard to walk back.
- **Implicit with batched user confirmation.** Plausible v1.1 design, but
  requires UX iteration we don't have time for in v1.

## When to revisit

After v1 ships and we observe how often users wish the agent had remembered
something. The plan for v1.1 (see `docs/13-v1.1-backlog.md` "Automatic
background memory") is implicit-with-confirmation: the agent proposes
memories, the user accepts or rejects in batch.
