---
name: Script proposal
about: Propose a new substrate or app-level script (verb the LLM can call).
title: "[script] "
labels: script-proposal
---

## Proposed script

**Namespace.name:** e.g., `calendar.invite`

**One-sentence purpose:**

## Why an existing script isn't enough

Before proposing a new script, see `docs/05-script-catalog.md` "Anti-patterns to refuse in review":

- [ ] Not the same OS operation as an existing script with different framing.
- [ ] Parameters could not fit as a filter field on an existing query.
- [ ] Not a "convenience wrapper" over existing scripts.
- [ ] Name is capability-shaped, not use-case-shaped.
- [ ] Does not internally compose two existing scripts (the LLM should compose).

## Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|

## Returns

<!-- Shape of the success result. -->

## Permissions required

## Destructive / side-effecting flags

- destructive: yes / no — explain.
- sideEffecting: yes / no — explain. If yes, what's the idempotency key strategy?

## Platform support

- Android: <!-- API to use --> 
- iOS: <!-- API to use -->

## Sample LLM invocations

<!-- 2–3 example tool calls showing intent. -->
