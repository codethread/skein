# Runtime View Primitives Proposal

**Document ID:** `RVP-PROP-001`
**Status:** Draft
**Date:** 2026-06-25
**Blocked by:** `devflow/feat/user-daemon-home` shipping connected daemon-world init and REPL workflows
**Related PRD:** [Runtime Transformations PRD](../../prd/runtime-transformations.md)
**Related RFCs:** None
**Relevant root specs:** [Daemon Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md), [Task Model](../../specs/task-model.md)

## RVP-PROP-001.P1 Problem

The runtime transformations PRD calls for userland Clojure views that compose SQL-backed candidate selection, graph expansion, batch hydration, and arbitrary trusted shaping. The project already has daemon-backed named queries, but it lacks the set-oriented daemon primitives and trusted view registry needed to build useful graph views without pulling the whole database into memory or adding bespoke CLI commands.

## RVP-PROP-001.P2 Goals

- **RVP-PROP-001.G1:** Add a first read-only runtime view slice that proves the PRD composition model: query ids, expand a `parent-of` graph, hydrate tasks, and shape results in trusted Clojure.
- **RVP-PROP-001.G2:** Keep runtime behavior daemon-owned and memory-only; views are loaded through trusted config or defined in a connected REPL, not persisted in SQLite.
- **RVP-PROP-001.G3:** Provide set-oriented primitives that avoid N+1 task hydration and avoid full-database scans for common view pipelines.
- **RVP-PROP-001.G4:** Expose the slice through daemon API and REPL helpers, including `todo daemon repl --stdin` once `user-daemon-home` ships.
- **RVP-PROP-001.G5:** Preserve the thin CLI boundary: no public `todo view` command in this feature.

## RVP-PROP-001.P3 Non-goals

- **RVP-PROP-001.NG1:** Do not add CLI view invocation or view output contracts.
- **RVP-PROP-001.NG2:** Do not persist views, functions, scores, groups, or other runtime behavior in SQLite.
- **RVP-PROP-001.NG3:** Do not add mutating runtime workflows.
- **RVP-PROP-001.NG4:** Do not design a comprehensive graph library; implement only the primitives needed for the first feature-DAG view slice.
- **RVP-PROP-001.NG5:** Do not add untrusted plugin execution, sandboxing, authorization, or remote daemon access.

## RVP-PROP-001.P4 Proposed scope

- **RVP-PROP-001.S1:** Add `query-ids!` for returning candidate task ids from an ad hoc query definition or registered query name.
- **RVP-PROP-001.S2:** Add `tasks-by-ids` for batch hydration of known task ids with normalized task rows.
- **RVP-PROP-001.S3:** Add `ancestor-root-ids` over `parent-of` to walk from seed work to feature or ownership roots selected by a caller-supplied root predicate.
- **RVP-PROP-001.S4:** Add `descendant-ids` or a minimal `subgraph` primitive over `parent-of` to expand from root ids back down to feature DAG work.
- **RVP-PROP-001.S5:** Add daemon-memory `register-view!` and `view!` for read-only trusted Clojure view functions.
- **RVP-PROP-001.S6:** Add REPL helper wrappers and a documented flagship example for active feature DAGs.

## RVP-PROP-001.P5 Open questions

- **RVP-PROP-001.Q1:** Should the first expansion primitive return only ids, or a compact subgraph containing ids and internal edges? Prefer the smallest shape that supports the flagship example during planning.
- **RVP-PROP-001.Q2:** What exact view function invocation context should `view!` pass: raw params only, or a map with params plus helper functions/metadata? Keep this minimal and Clojure-native.
