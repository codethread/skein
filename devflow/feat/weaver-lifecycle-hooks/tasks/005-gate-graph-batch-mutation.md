# Task 5: Gate graph batch mutation

**Document ID:** `WLH-TASK-005`

## WLH-TASK-005.P1 Scope

Type: AFK

Add attribute normalization and `:batch/apply-before-commit` validation for trusted transactional graph batch mutation through `api/apply-batch` and `skein.batch.alpha/apply!`.

## WLH-TASK-005.P2 Must implement exactly

- **WLH-TASK-005.MI1:** Factor `src/skein/db.clj` graph batch internals so `skein.weaver.api/apply-batch` can run normal batch validation/mutation inside a transaction it controls and receive uncommitted result/context before commit.
- **WLH-TASK-005.MI2:** Preserve public `db/apply-batch!` behavior and result shape for existing direct callers.
- **WLH-TASK-005.MI3:** Apply per-strand `:attributes/normalize` to batch created and updated strand entries before storage consumes their attribute maps. Do not normalize edge attributes.
- **WLH-TASK-005.MI4:** Invoke `:batch/apply-before-commit` after uncommitted batch result construction and before transaction commit.
- **WLH-TASK-005.MI5:** Batch hook context for graph batch mutation must include common pre-commit keys `:request/source`, `:request/operation`, and `:mutation/operation`, plus `:batch/source :apply`, `:batch/payload`, `:batch/refs`, `:batch/created`, `:batch/updated`, `:batch/burned`, and `:batch/edge-ops`; inapplicable collections/maps must be empty rather than omitted.
- **WLH-TASK-005.MI6:** Hook rejection must roll back all created strands, updated strands, edge upserts, and burns from the batch and enqueue no batch or fanout events.
- **WLH-TASK-005.MI7:** Hook-approved batches must preserve existing result shape and existing `:batch/applied` plus compatibility fanout event behavior.

## WLH-TASK-005.P3 Done when

- **WLH-TASK-005.DW1:** Tests prove batch-created and batch-updated strand attributes are normalized before validation/commit.
- **WLH-TASK-005.DW2:** Tests prove batch hook context has the common schema and matches the eventual committed result for refs, created rows, updated before/after rows, burned rows, and edge operations.
- **WLH-TASK-005.DW3:** Tests prove a failing batch hook rolls back mixed create/update/edge/burn work atomically and emits no events.
- **WLH-TASK-005.DW4:** Tests prove successful batch behavior and event ordering remain compatible with existing `weaver-apply-batch-*` tests.
- **WLH-TASK-005.DW5:** `skein.batch.alpha/apply!` benefits from the hook gate because it routes through `api/apply-batch`.

## WLH-TASK-005.P4 Out of scope

- **WLH-TASK-005.OS1:** Do not gate pattern-created batches in this task.
- **WLH-TASK-005.OS2:** Do not add public CLI batch commands.
- **WLH-TASK-005.OS3:** Do not implement partial batch acceptance; a hook rejection rejects the whole batch.

## WLH-TASK-005.P5 References

- **WLH-TASK-005.REF1:** [Plan](../weaver-lifecycle-hooks.plan.md) `WLH-PLAN-001.PH3` and `WLH-PLAN-001.A6`.
- **WLH-TASK-005.REF2:** [Weaver Runtime delta](../specs/daemon-runtime.delta.md) `WLH-DELTA-001.CC9`, `WLH-DELTA-001.CC16` through `WLH-DELTA-001.CC21`.
- **WLH-TASK-005.REF3:** `src/skein/db.clj` `apply-batch!`; `src/skein/weaver/api.clj` `apply-batch`; `src/skein/batch/alpha.clj`.
