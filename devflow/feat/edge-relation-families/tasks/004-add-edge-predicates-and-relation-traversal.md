# Task 4: Add edge predicates and relation traversal

**Document ID:** `ERF-TASK-004`

## ERF-TASK-004.P1 Scope

Type: AFK

Add direct relationship-aware query predicates and relation-scoped traversal helpers over declared acyclic relations.

## ERF-TASK-004.P2 Must implement exactly

- **ERF-TASK-004.MI1:** Extend `src/skein/query.clj` with `[:edge/out relation target-query]` and `[:edge/in relation source-query]` operators that compile to correlated direct-edge `EXISTS` subqueries.
- **ERF-TASK-004.MI2:** Allow relation operands to be valid relation-name strings or `[:param :name]` references resolved from the existing query parameter map.
- **ERF-TASK-004.MI3:** Restrict endpoint subqueries to strand-local predicates. Nested `:edge/out` or `:edge/in` inside endpoint queries must fail loudly.
- **ERF-TASK-004.MI4:** Re-express or preserve `ready` semantics as equivalent to `[:and [:= :state "active"] [:not [:edge/out "depends-on" [:= :state "active"]]]]`.
- **ERF-TASK-004.MI5:** Update `ancestor-root-ids` and `subgraph` storage/weaver/`skein.graph.alpha` helpers to accept an optional relation `:type`, defaulting to `parent-of`.
- **ERF-TASK-004.MI6:** Make traversal fail loudly if the requested relation is not declared acyclic.
- **ERF-TASK-004.MI7:** Preserve existing traversal behavior for empty input, missing ids, stable ordering, duplicate handling, `:where` filtering, and returned edge shape.
- **ERF-TASK-004.MI8:** Add tests for edge predicates, named query registration with edge predicates, parameterized relation names, endpoint nested-edge rejection, ready behavior, and relation-scoped traversal.

## ERF-TASK-004.P3 Done when

- **ERF-TASK-004.DW1:** Focused Clojure query/db/alpha helper tests pass.
- **ERF-TASK-004.DW2:** A custom declared acyclic relation can be traversed with `subgraph`/`ancestor-root-ids` by passing `:type`.
- **ERF-TASK-004.DW3:** An annotation relation cannot be traversed by these helpers and fails loudly.

## ERF-TASK-004.P4 Out of scope

- **ERF-TASK-004.OS1:** Recursive/path query predicates in `list` or `ready`.
- **ERF-TASK-004.OS2:** Edge-attribute predicates.
- **ERF-TASK-004.OS3:** Supersession operation.

## ERF-TASK-004.P5 References

- **ERF-TASK-004.REF1:** `devflow/feat/edge-relation-families/specs/strand-model.delta.md`
- **ERF-TASK-004.REF2:** `devflow/feat/edge-relation-families/specs/repl-api.delta.md`
- **ERF-TASK-004.REF3:** `src/skein/query.clj`
- **ERF-TASK-004.REF4:** `src/skein/graph/alpha.clj`
