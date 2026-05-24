<!-- One PR = one module. Cross-module changes need justification below. -->

## Summary

<!-- 1–2 sentences. What changes and why. -->

## Module(s) touched

- [ ] Single module: `<name>`
- [ ] Multiple modules — justification:

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / internal change (no public API impact)
- [ ] Public API change (requires ADR — link below)
- [ ] Docs / process

## Public API impact

- Does this change a public API in `contracts`, `core`, `scripts-core`, `design-system-api`, or a locked harness interface?
  - [ ] No
  - [ ] Yes — ADR: `docs/adr/ADR-XXX-<slug>.md`

## Tests

- [ ] Unit tests added/updated
- [ ] Integration tests added/updated (if touching cross-module behavior)
- [ ] Snapshot tests updated (UI changes)
- [ ] N/A — explain:

## Docs

- [ ] Module contract doc (`docs/modules/<name>.md`) updated
- [ ] Changelog entry added
- [ ] No docs needed — explain:

## Checklist

- [ ] DCO sign-off present on all commits (`git commit -s`)
- [ ] Lint passes (detekt / ktlint)
- [ ] No new `OsCapabilities` interface added without a corresponding `os-bridge` implementation
- [ ] No raw `Color(0xFF…)` / raw OS calls outside `os-bridge`

## Linked issues
