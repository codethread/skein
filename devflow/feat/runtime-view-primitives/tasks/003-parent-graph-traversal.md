# Parent Graph Traversal

**Document ID:** `RVP-TASK-003`
**Status:** Blocked
**Plan:** [runtime-view-primitives.plan.md](../runtime-view-primitives.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md)

## RVP-TASK-003.P1 Scope

Type: AFK

Implement the minimal recursive `parent-of` traversal primitives needed for runtime feature-DAG views.

## RVP-TASK-003.P2 Implementation notes

- **RVP-TASK-003.I1:** Add `ancestor-root-ids` over reverse `parent-of` traversal from seed task ids.
- **RVP-TASK-003.I2:** Add the selected downward primitive, defaulting to `descendant-ids` unless implementation review records that `subgraph` is required.
- **RVP-TASK-003.I3:** Prefer SQLite recursive CTEs and set inputs over row-at-a-time loops.
- **RVP-TASK-003.I4:** Keep traversal limited to `parent-of` for this feature.

## RVP-TASK-003.P3 Done when

- **RVP-TASK-003.D1:** Tests cover multi-level ancestors, multiple seeds, no-root/empty cases, and multiple roots.
- **RVP-TASK-003.D2:** Tests cover downward expansion from one or more roots.
- **RVP-TASK-003.D3:** Relevant Clojure tests pass.
