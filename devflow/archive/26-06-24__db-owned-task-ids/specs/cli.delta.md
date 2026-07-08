# CLI Surface Delta: DB-owned Task IDs

**Document ID:** `DELTA-002` **Status:** Merged **Target root spec:** [CLI Surface](../../../specs/cli.md) **Feature:** `db-owned-task-ids` **Last Updated:** 2026-06-24 **Configuration identification:** `DELTA-002` is the CLI delta for `db-owned-task-ids`. Every nested point ID is prefixed with `DELTA-002`.

## DELTA-002.P1 Summary

The CLI creation path changes from caller-supplied task ids to generated task ids, and adds a small generic creation-time edge shorthand.

## DELTA-002.P2 Command changes

- **DELTA-002.C1:** Change `add <id> <title> [--attr key=value ...]` to `add <title> [--attr key=value ...] [--link edge-type:to-id ...]`.
- **DELTA-002.C2:** `add` creates one task with a generated id and returns the created task row. Human output prints the generated id; EDN/JSON output returns machine-readable id-bearing data.
- **DELTA-002.C3:** `--link` is repeatable. Each value creates an edge from the newly created task id to the target task id using the supplied edge type.
- **DELTA-002.C4:** `--link depends-on:ue72w` on `add "review"` is equivalent to creating `review`, capturing its generated id, then calling `link <review-id> ue72w depends-on`.
- **DELTA-002.C5:** `--link` supports edge type and target id only. Edge attributes remain the responsibility of the full `link` command.
- **DELTA-002.C6:** `--link` values are split on the last colon and fail loudly when missing a colon, missing an edge type, missing a target id, or referring to a nonexistent target task.
- **DELTA-002.C7:** Generated ids cannot contain colons, so parsing `edge-type:to-id` keeps generated target ids unambiguous while still allowing colons inside edge types. The full `link` command remains the path for linking to any legacy colon-bearing id.
- **DELTA-002.C8:** `link`, `show`, `deps`, `transitive-deps`, `blocking`, `ready`, `by-attr`, and `done` continue to operate on durable task ids.

## DELTA-002.P3 Alpha compatibility position

- **DELTA-002.A1:** Per [TENETS](../../../TENETS.md), the CLI should not keep a backwards-compatible `add <id> <title>` mode. Agents should update scripts to capture the id returned by `add <title>`.
