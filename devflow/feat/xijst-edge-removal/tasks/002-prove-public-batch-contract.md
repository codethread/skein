# Task 2: Prove public hook event and API contract

**Document ID:** `TASK-Xer-002`

## TASK-Xer-002.P1 Scope

Type: AFK

Document and prove the normalized public transition contract through `skein.api.batch.alpha/apply!`, its pre-commit hook, and its batch event.

## TASK-Xer-002.P2 Must implement exactly

- **TASK-Xer-002.MI1:** Update the `apply!` docstring with both edge operations and the exact normalized transition shape.
- **TASK-Xer-002.MI2:** Assert that the returned `:edges`, hook `:batch/edge-ops`, and event `:batch/edges` are equal ordered values with decoded attribute maps.
- **TASK-Xer-002.MI3:** Assert a hook veto rolls back removal and emits no event, and malformed remove operations fail at the public boundary.
- **TASK-Xer-002.MI4:** Regenerate batch API documentation.

## TASK-Xer-002.P3 Done when

- **TASK-Xer-002.DW1:** `clojure -M:test skein.weaver-test skein.api.batch.alpha-test` passes cold.
- **TASK-Xer-002.DW2:** `make api-docs` is reproducible and leaves no unexplained generated diff.

## TASK-Xer-002.P4 Out of scope

- **TASK-Xer-002.OS1:** New event kinds, hooks, helper functions, CLI verbs, or a second normalization pass.

## TASK-Xer-002.P5 References

- **TASK-Xer-002.REF1:** [Proposal](../proposal.md), [plan](../xijst-edge-removal.plan.md), and `TASK-Xer-001`.
- **TASK-Xer-002.REF2:** `src/skein/api/batch/alpha.clj`, `test/skein/weaver_test.clj`, `test/skein/api/batch/alpha_test.clj`, and `docs/api/batch.api.md`.
