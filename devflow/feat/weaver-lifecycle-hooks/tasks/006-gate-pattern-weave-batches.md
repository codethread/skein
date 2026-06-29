# Task 6: Gate pattern weave batches

**Document ID:** `WLH-TASK-006`

## WLH-TASK-006.P1 Scope

Type: AFK

Route pattern-created create-only batches through the reviewed batch hook policy once, with pattern context and per-strand attribute normalization.

## WLH-TASK-006.P2 Must implement exactly

- **WLH-TASK-006.MI1:** Factor or wrap `db/add-strand-batch!` internals so `api/weave!` can perform normal create-only batch validation/mutation inside a transaction it controls and receive uncommitted result/context before commit.
- **WLH-TASK-006.MI2:** Preserve public `db/add-strand-batch!` behavior and result shape for existing direct callers.
- **WLH-TASK-006.MI3:** After pattern input spec validation and pattern function invocation, apply per-strand `:attributes/normalize` to pattern-produced strand attributes before storage consumes them.
- **WLH-TASK-006.MI4:** Invoke `:batch/apply-before-commit` exactly once for `weave!`; do not add or invoke a separate `:pattern/weave-before-commit` hook family.
- **WLH-TASK-006.MI5:** Pattern-created batch hook context must use the same common batch schema as graph batches with common pre-commit keys `:request/source`, `:request/operation`, and `:mutation/operation`, plus `:batch/source :weave`, a normalized create-only `:batch/payload`, `:batch/updated []`, `:batch/burned []`, empty entries for inapplicable data, `:request/operation :weave`, `:pattern/name`, and `:pattern/input`.
- **WLH-TASK-006.MI6:** Hook rejection must roll back all pattern-created strands and edges and enqueue no events.
- **WLH-TASK-006.MI7:** Hook-approved `weave!` must preserve current result shape for pattern-created batches.

## WLH-TASK-006.P3 Done when

- **WLH-TASK-006.DW1:** Tests prove pattern-produced strand attributes are normalized before create-only batch commit.
- **WLH-TASK-006.DW2:** Tests prove a hook registered for `:batch/apply-before-commit` sees `:batch/source :weave`, pattern name/input, create-only payload, final refs, created rows, empty updated/burned vectors, and edge operations where present.
- **WLH-TASK-006.DW3:** Tests prove the batch hook runs once for `weave!`, not once as both pattern and batch policy.
- **WLH-TASK-006.DW4:** Tests prove hook rejection rolls back pattern-created rows/edges and leaves existing pattern input validation behavior unchanged.
- **WLH-TASK-006.DW5:** Existing pattern/weave compatibility tests continue to pass.

## WLH-TASK-006.P4 Out of scope

- **WLH-TASK-006.OS1:** Do not add a `:pattern/weave-before-commit` hook type.
- **WLH-TASK-006.OS2:** Do not change pattern registration, input spec validation, or pattern explanation contracts.
- **WLH-TASK-006.OS3:** Do not add arbitrary graph batch mutation to the public CLI.

## WLH-TASK-006.P5 References

- **WLH-TASK-006.REF1:** [Plan](../weaver-lifecycle-hooks.plan.md) `WLH-PLAN-001.PH3`.
- **WLH-TASK-006.REF2:** [Weaver Runtime delta](../specs/daemon-runtime.delta.md) `WLH-DELTA-001.CC9`, `WLH-DELTA-001.CC16` through `WLH-DELTA-001.CC21`.
- **WLH-TASK-006.REF3:** `src/skein/weaver/api.clj` `weave!`; `src/skein/db.clj` `add-strand-batch!`; pattern tests in `test/skein/weaver_test.clj`.
