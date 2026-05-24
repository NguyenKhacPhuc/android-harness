# Module: `<name>`

> Use this template when adding a new module. Copy it to `docs/modules/<name>.md`
> and fill in every section. Keep it short — a reader should grasp the module in
> ~5 minutes.

## Purpose

One sentence. What does this module do, and why does it exist as a separate
module instead of being part of an adjacent one?

## Public API

The surface other modules depend on. List exhaustively. Group by category if
helpful (interfaces, data classes, factories). Any change here is breaking and
requires the PR process in `10-ownership-and-process.md`.

## Internal API

Implementation details exposed only for tests, or for use by sibling files
within the module. Listed here so reviewers know what is *not* a stable
contract — these can change without notice.

## Stability

- **v0.x:** any breaking change allowed without migration guide.
- **v1.x:** breaking changes require a migration guide and a major version
  bump per `10-ownership-and-process.md`.

## Test coverage targets

- Lines: X%
- Branches: Y%

## Owner

- Role + GitHub handle. See `10-ownership-and-process.md` for the canonical
  ownership matrix.

## Dependencies

Explicit list. Adding a new dependency here requires PR review by the
dependency's owner (so they know who's consuming them).

| Module / library | Direction | Why |
|------------------|-----------|-----|

## Notes

- Anything else contributors need to know: platform divergences, gotchas,
  pending RFCs that may change this module's shape, links to relevant ADRs.
