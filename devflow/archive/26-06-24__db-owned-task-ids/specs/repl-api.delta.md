# REPL API Delta: DB-owned Task IDs

**Document ID:** `DELTA-003` **Status:** Merged **Target root spec:** [REPL API](../../../specs/repl-api.md) **Feature:** `db-owned-task-ids` **Last Updated:** 2026-06-24 **Configuration identification:** `DELTA-003` is the REPL API delta for `db-owned-task-ids`. Every nested point ID is prefixed with `DELTA-003`.

## DELTA-003.P1 Summary

The REPL helper for task creation changes from caller-owned ids to generated ids while keeping id-addressed graph helpers after creation.

## DELTA-003.P2 Contract changes

- **DELTA-003.C1:** Change `task!` from `(task! id title)` and `(task! id title attributes)` to generated-id creation forms such as `(task! title)` and `(task! title attributes)`.
- **DELTA-003.C2:** `task!` returns the created task row with normalized attributes and the generated `:id`.
- **DELTA-003.C3:** `edge!`, `depends!`, `done!`, `task`, `deps`, `transitive-deps`, `blocking`, and `graph` continue to use durable task ids after creation.
- **DELTA-003.C4:** If the REPL exposes a creation-time link convenience, it should follow the same generic relationship model as the CLI rather than adding dependency-specific helper proliferation.

## DELTA-003.P3 Alpha compatibility position

- **DELTA-003.A1:** Per [TENETS](../../../TENETS.md), the REPL API should drop caller-owned `task!` creation forms instead of keeping compatibility overloads.
