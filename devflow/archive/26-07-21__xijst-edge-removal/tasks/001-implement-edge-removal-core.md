# Task 1: Implement exact edge removal and core transitions

**Document ID:** `TASK-Xer-001`

## TASK-Xer-001.P1 Scope

Type: AFK

Implement the exact `:remove` edge operation and uniform storage-shaped edge transitions in the database batch engine.

**Owned files (disjoint from Tasks 2 and 3):**

- `src/skein/core/db.clj`
- `test/skein/core/db_test.clj`
- `test/skein/core/contract_props_test.clj`

## TASK-Xer-001.P2 Must implement exactly

- **TASK-Xer-001.MI1:** Add the closed remove grammar, including exact keys and top-level pre-bound endpoint refs.
- **TASK-Xer-001.MI2:** Execute edge operations in submitted order; an absent exact edge throws with the exact `PROP-Xer-001.C2` data and rolls back the transaction.
- **TASK-Xer-001.MI3:** Replace the batch edge outcome's `:edge` key with exact `:before` and `:after` storage rows for both upsert and remove.
- **TASK-Xer-001.MI4:** Extend `contract_props_test.clj` so valid coherent batches generate `:remove` operations, or add a focused acceptance property proving valid remove batches are accepted. Cover the proposal proof obligations at the database boundary, including ordered same-source `serves` replacement, DAG reversal, repeated identities, and atomic multi-operation failure.
- **TASK-Xer-001.MI5:** Lock `PROP-Xer-001.PO7` semantics with explicit cases: removing `depends-on` may make work ready; removing `parent-of` may create a root or hide a subtree. Both commit without a new core rejection.

## TASK-Xer-001.P3 Done when

- **TASK-Xer-001.DW1:** `clojure -M:test skein.core.db-test skein.core.contract-props-test` passes cold.
- **TASK-Xer-001.DW2:** Core tests assert exact remove diagnostics, rollback, operation order, transition keys, and raw JSON attributes.

## TASK-Xer-001.P4 Out of scope

- **TASK-Xer-001.OS1:** Public API documentation, event plumbing changes, CLI surfaces, projectors, rewiring, and graph connectivity policy.

## TASK-Xer-001.P5 References

- **TASK-Xer-001.REF1:** [Proposal](../proposal.md), [plan](../xijst-edge-removal.plan.md), and [strand-model delta](../specs/strand-model.delta.md).
- **TASK-Xer-001.REF2:** `src/skein/core/db.clj`, `test/skein/core/db_test.clj`, and `test/skein/core/contract_props_test.clj`.

## TASK-Xer-001.P6 Commit discipline

- **TASK-Xer-001.CD1:** Make one atomic conventional commit, `feat(core): add exact batch edge removal`, containing only owned files. Do not amend, push, or include generated runtime artifacts.
- **TASK-Xer-001.CD2:** Before committing, `git status --short` must contain only expected owned-file changes; after committing, the worktree must be clean.
