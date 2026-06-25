# Runtime View Primitives Plan

**Document ID:** `RVP-PLAN-001`
**Status:** Draft
**Last Updated:** 2026-06-25
**Blocked by:** `devflow/feat/user-daemon-home` shipping and promoting daemon-world init plus connected REPL contracts
**Proposal:** [proposal.md](./proposal.md)
**Spec deltas:** [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md), [cli.delta.md](./specs/cli.delta.md)
**Related PRD:** [Runtime Transformations PRD](../../prd/runtime-transformations.md)
**Related RFCs:** None

## RVP-PLAN-001.P1 Goal and scope

Deliver the first read-only runtime transformation slice after `user-daemon-home`: trusted Clojure code can compose SQL-backed query id selection, minimal `parent-of` graph traversal, batch task hydration, and daemon-memory registered views. The public CLI view command remains out of scope.

## RVP-PLAN-001.P2 Approach

- **RVP-PLAN-001.A1:** Build on the daemon-core model: SQLite stores durable tasks and edges; daemon memory stores named queries and views; trusted config/REPL code performs orchestration.
- **RVP-PLAN-001.A2:** Keep primitives set-oriented. Prefer id pipelines and batch hydration over row-at-a-time helper loops.
- **RVP-PLAN-001.A3:** Extend existing query registry execution rather than introducing a separate candidate-selection language.
- **RVP-PLAN-001.A4:** Implement only `parent-of` graph traversal needed for the flagship feature-DAG workflow.
- **RVP-PLAN-001.A5:** Keep view registration and invocation in trusted Clojure surfaces only: daemon API and REPL helpers. Do not add JSON socket allowlist entries or CLI command syntax.
- **RVP-PLAN-001.A6:** Use `todo daemon repl --stdin` examples once `user-daemon-home` exists, so agents can run trusted view code without a TTY while choosing their own output shape.

## RVP-PLAN-001.P3 Affected areas

| ID | Area | Impact |
| --- | --- | --- |
| RVP-PLAN-001.AA1 | `src/todo/db.clj` | Add id-only query execution, batch hydration, and recursive `parent-of` traversal queries. |
| RVP-PLAN-001.AA2 | `src/todo/daemon/api.clj` | Expose read primitives and view registry operations at the semantic daemon boundary. |
| RVP-PLAN-001.AA3 | `src/todo/repl.clj` | Add helper wrappers for query ids, graph traversal, batch hydration, view registration, and view invocation. |
| RVP-PLAN-001.AA4 | `test/todo/*` | Cover primitive behavior, registry failure modes, and REPL helper composition. |
| RVP-PLAN-001.AA5 | `dev/todo/smoke.clj` and docs | Add a minimal trusted view smoke/demo after connected REPL startup exists. |

## RVP-PLAN-001.P4 Contract and migration impact

- **RVP-PLAN-001.CM1:** This feature is additive to daemon/REPL contracts but intentionally excludes public CLI contracts.
- **RVP-PLAN-001.CM2:** View registry contents are daemon-lifetime runtime state and disappear on restart, like named queries.
- **RVP-PLAN-001.CM3:** Existing persisted task/edge schemas should not change.
- **RVP-PLAN-001.CM4:** The feature depends on `user-daemon-home` replacing database-path `open!` workflows with connected daemon-world REPL workflows.

## RVP-PLAN-001.P5 Implementation phases

### RVP-PLAN-001.PH1 Query id and batch hydration primitives

Add daemon/database functions that return ids from ad hoc or named queries and hydrate many ids in one call. Preserve normalized task row behavior and loud errors for invalid query names or ids.

### RVP-PLAN-001.PH2 Minimal parent graph traversal

Add recursive traversal for `parent-of`: upward traversal from seed work to root candidates, plus one downward expansion primitive from roots. Choose id-only `descendant-ids` unless implementation review shows that returning internal edges is required for the flagship example.

### RVP-PLAN-001.PH3 Trusted view registry

Add daemon-memory view registration and invocation for read-only trusted Clojure functions. Reuse query registry name normalization where appropriate, fail loudly on missing views or non-function registrations, and keep views out of the JSON socket allowlist.

### RVP-PLAN-001.PH4 REPL helpers and flagship example

Expose helper functions through `todo.repl`. Add a small active-feature-DAG example that composes query ids, ancestor roots, downward expansion, hydration, and Clojure shaping from a connected REPL or `daemon repl --stdin`.

### RVP-PLAN-001.PH5 Validation and spec promotion prep

Update tests, smoke/demo coverage, and feature-local docs so the slice can be promoted into root daemon/repl specs when shipped.

## RVP-PLAN-001.P6 Validation strategy

- **RVP-PLAN-001.V1:** Clojure unit tests cover `query-ids!`, `tasks-by-ids`, upward and downward `parent-of` traversal, empty-set behavior, deterministic ordering where specified, and loud failures.
- **RVP-PLAN-001.V2:** Daemon API tests cover view registration, replacement, invocation, missing view errors, and view exceptions.
- **RVP-PLAN-001.V3:** REPL tests cover helper composition after connection and failure before connection using the post-`user-daemon-home` connection model.
- **RVP-PLAN-001.V4:** Smoke/demo validation registers one view from trusted Clojure and invokes it through connected REPL/stdin workflow.
- **RVP-PLAN-001.V5:** Full validation remains `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

## RVP-PLAN-001.P7 Risks and open questions

- **RVP-PLAN-001.R1:** A too-rich first graph API could overfit future needs. Mitigation: start with `parent-of` and id-first primitives.
- **RVP-PLAN-001.R2:** View return shapes may tempt premature CLI contracts. Mitigation: keep output Clojure-native and defer CLI view invocation.
- **RVP-PLAN-001.R3:** Running arbitrary trusted view functions can blur read-only expectations. Mitigation: document this as trusted Clojure behavior and keep mutating workflow contracts out of scope.
- **RVP-PLAN-001.Q1:** Choose between `descendant-ids` and minimal `subgraph` during implementation review; default to `descendant-ids` unless the flagship example needs edge materialization.
- **RVP-PLAN-001.Q2:** Decide whether root selection for `ancestor-root-ids` should accept a query definition/name, a predicate function, or both. Prefer query/name first for SQL-backed selection.

## RVP-PLAN-001.P8 Task context

- **RVP-PLAN-001.TC1:** This feature is deliberately blocked until `user-daemon-home` ships. Do not run implementation tasks against the old database-path REPL/open model.
- **RVP-PLAN-001.TC2:** Do not add public CLI view invocation, JSON socket allowlist entries, or view output contracts.
- **RVP-PLAN-001.TC3:** Do not change SQLite schema unless implementation proves a hard blocker; the PRD expects durable facts to remain tasks, attributes, and edges.
- **RVP-PLAN-001.TC4:** Preserve fail-loud behavior. Missing runtime definitions are operational errors, not empty results.
- **RVP-PLAN-001.TC5:** Keep examples oriented around trusted config or connected REPL workflows, especially `daemon repl --stdin` for agent use.

## RVP-PLAN-001.P9 Developer Notes

### RVP-PLAN-001.DN1 Initial blocked feature draft — 2026-06-25

Created proposal, spec deltas, and draft plan from `PRD-001` and the active `user-daemon-home` feature. Marked the feature blocked because runtime view primitives should target the new config-dir daemon world, trusted `init.clj`, `connect!`, and connected `todo daemon repl --stdin` contracts rather than the old database-path connection model.
