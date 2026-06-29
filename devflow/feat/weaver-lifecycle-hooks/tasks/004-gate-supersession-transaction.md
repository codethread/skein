# Task 4: Gate supersession transaction

**Document ID:** `WLH-TASK-004`

## WLH-TASK-004.P1 Scope

Type: AFK

Add `:strand/supersede-before-commit` using an uncommitted-write seam so hook context matches the actual supersession result and hook failure rolls back the transaction.

## WLH-TASK-004.P2 Must implement exactly

- **WLH-TASK-004.MI1:** Factor `src/skein/db.clj` supersession internals so `skein.weaver.api/supersede` can run the normal validation/mutation logic inside a transaction it controls and receive the uncommitted supersession result before commit.
- **WLH-TASK-004.MI2:** Preserve the public `db/supersede-strand!` behavior for existing callers, including transactionality and result shape.
- **WLH-TASK-004.MI3:** Invoke `:strand/supersede-before-commit` after normal supersession validation/mutation has produced uncommitted result data, but before the transaction exits.
- **WLH-TASK-004.MI4:** Supersede hook context must include common pre-commit keys `:request/source`, `:request/operation`, and `:mutation/operation`, plus old id, replacement id, normalized old before/after candidate rows, supersedes edge candidate, and dependency rewiring candidate data.
- **WLH-TASK-004.MI5:** Hook rejection must roll back old-strand state change, supersedes edge insertion, and dependency rewiring.
- **WLH-TASK-004.MI6:** Hook-approved supersession must preserve the existing API result and `:strand/superseded` event payload.

## WLH-TASK-004.P3 Done when

- **WLH-TASK-004.DW1:** Tests prove a supersede hook can inspect old before/after, replacement id, supersedes edge, and rewired dependencies.
- **WLH-TASK-004.DW2:** Tests prove a failing supersede hook leaves the old strand active, writes no supersedes edge, leaves incoming `depends-on` edges unchanged, and emits no event.
- **WLH-TASK-004.DW3:** Tests prove successful supersession still returns the existing normalized result and emits the existing semantic event.
- **WLH-TASK-004.DW4:** Existing supersession validation failures and cycle checks still fail loudly with their current domain behavior.

## WLH-TASK-004.P4 Out of scope

- **WLH-TASK-004.OS1:** Do not change supersession domain semantics or edge direction.
- **WLH-TASK-004.OS2:** Do not implement batch or pattern hook gates in this task.
- **WLH-TASK-004.OS3:** Do not expose supersession planning as a public API.

## WLH-TASK-004.P5 References

- **WLH-TASK-004.REF1:** [Plan](../weaver-lifecycle-hooks.plan.md) `WLH-PLAN-001.PH3` and `WLH-PLAN-001.A6`.
- **WLH-TASK-004.REF2:** [Weaver Runtime delta](../specs/daemon-runtime.delta.md) `WLH-DELTA-001.CC14`, `WLH-DELTA-001.CC15`, `WLH-DELTA-001.CC19` through `WLH-DELTA-001.CC21`.
- **WLH-TASK-004.REF3:** `src/skein/db.clj` `supersede-strand!`; `src/skein/weaver/api.clj` `supersede`; supersession tests in `test/skein/db_test.clj` and `test/skein/weaver_test.clj`.
