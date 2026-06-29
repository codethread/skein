# Task 3: Gate simple strand mutations

**Document ID:** `WLH-TASK-003`

## WLH-TASK-003.P1 Scope

Type: AFK

Add validation-only pre-commit hooks for add, update, and burn mutation paths, including update edge candidates.

## WLH-TASK-003.P2 Must implement exactly

- **WLH-TASK-003.MI1:** Invoke `:strand/add-before-commit` for `api/add` after attribute normalization and candidate row construction but before commit/event enqueue. Add context must include common pre-commit keys `:request/source`, `:request/operation`, and `:mutation/operation`, plus candidate created strand data and no before row.
- **WLH-TASK-003.MI2:** Invoke `:strand/update-before-commit` for `api/update` inside the mutation transaction after before/after candidate construction and candidate edge operations are known, but before commit/event enqueue.
- **WLH-TASK-003.MI3:** Update hook context must include common pre-commit keys `:request/source`, `:request/operation`, and `:mutation/operation`, plus strand id, submitted patch, normalized before row, normalized after candidate row, and candidate edge operations requested by the patch.
- **WLH-TASK-003.MI4:** Invoke `:strand/burn-before-commit` for `api/burn-by-ids` inside the burn transaction after requested ids and normalized before rows are known, but before delete commit/event enqueue. Burn context must include common pre-commit keys `:request/source`, `:request/operation`, and `:mutation/operation`.
- **WLH-TASK-003.MI5:** Hook rejection must abort the mutation transaction, leave storage unchanged, and enqueue no mutation events.
- **WLH-TASK-003.MI6:** Hook-approved mutations must preserve existing return values and existing post-commit event payloads.

## WLH-TASK-003.P3 Done when

- **WLH-TASK-003.DW1:** Tests prove add hook context includes the normalized candidate and can reject creation before it persists.
- **WLH-TASK-003.DW2:** Tests prove update hook context includes before/after rows and edge operation candidates, and can reject title/state/attributes/edge changes without committing any part of the update.
- **WLH-TASK-003.DW3:** Tests prove burn hook context includes requested ids and pre-delete rows, and can reject without deleting strands or edges.
- **WLH-TASK-003.DW4:** Tests prove successful add/update/burn still emit the existing `:strand/added`, `:strand/updated`, and `:strand/burned` events after commit.
- **WLH-TASK-003.DW5:** Relevant Clojure tests pass.

## WLH-TASK-003.P4 Out of scope

- **WLH-TASK-003.OS1:** Do not implement supersede hooks in this task.
- **WLH-TASK-003.OS2:** Do not implement batch, pattern, or JSON socket payload hooks in this task.
- **WLH-TASK-003.OS3:** Do not add edge attribute normalization.

## WLH-TASK-003.P5 References

- **WLH-TASK-003.REF1:** [Plan](../weaver-lifecycle-hooks.plan.md) `WLH-PLAN-001.PH2`.
- **WLH-TASK-003.REF2:** [Weaver Runtime delta](../specs/daemon-runtime.delta.md) `WLH-DELTA-001.CC14`, `WLH-DELTA-001.CC15`, `WLH-DELTA-001.CC19` through `WLH-DELTA-001.CC21`.
- **WLH-TASK-003.REF3:** `src/skein/weaver/api.clj` `add`, `update`, `apply-edges!`, `burn-by-ids`, and event emission tests in `test/skein/weaver_test.clj`.
