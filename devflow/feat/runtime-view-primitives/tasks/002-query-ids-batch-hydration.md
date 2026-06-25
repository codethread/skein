# Query IDs and Batch Hydration

**Document ID:** `RVP-TASK-002`
**Status:** Blocked
**Plan:** [runtime-view-primitives.plan.md](../runtime-view-primitives.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md)

## RVP-TASK-002.P1 Scope

Type: AFK

Implement the first id-pipeline primitives after the feature is unblocked: query execution that returns ids only, and batch task hydration by ids.

## RVP-TASK-002.P2 Implementation notes

- **RVP-TASK-002.I1:** Add database/daemon API support for `query-ids!` from an ad hoc query definition or registered query name plus params.
- **RVP-TASK-002.I2:** Add `tasks-by-ids` to hydrate many task ids in one call with normalized task rows.
- **RVP-TASK-002.I3:** Preserve existing query registry failure behavior for missing named queries.
- **RVP-TASK-002.I4:** Do not add CLI or JSON socket view operations.

## RVP-TASK-002.P3 Done when

- **RVP-TASK-002.D1:** Unit tests cover ad hoc query ids, named query ids, params, empty results, and missing query errors.
- **RVP-TASK-002.D2:** Unit tests cover batch hydration for multiple ids and normalized attributes.
- **RVP-TASK-002.D3:** Relevant Clojure tests pass.
