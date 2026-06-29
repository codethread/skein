# Task 2: Add hook runner and attr normalization

**Document ID:** `WLH-TASK-002`

## WLH-TASK-002.P1 Scope

Type: AFK

Add the synchronous hook invocation helpers and prove the transform contract by applying `:attributes/normalize` to simple add/update attribute entry points.

## WLH-TASK-002.P2 Must implement exactly

- **WLH-TASK-002.MI1:** Add internal hook runner helpers in `src/skein/weaver/api.clj` for validation hooks and transform hooks selected by hook type.
- **WLH-TASK-002.MI2:** Invoke hook functions through the runtime library classloader and in deterministic registry order.
- **WLH-TASK-002.MI3:** Validation hook return values must be ignored; success is returning normally and rejection is throwing.
- **WLH-TASK-002.MI4:** Transform hooks must thread `{:hook/value ...}` through each matching hook. Returning `nil`, a non-wrapper value, or a wrapper without `:hook/value` must fail loudly.
- **WLH-TASK-002.MI5:** Wrap hook invocation failures as `ex-info` with top-level `:code "hook/failed"`, hook type, hook key, hook function symbol, original exception class/message, original `ex-info` data when present, and `:hook/cause-code` when the original data contains `:code`.
- **WLH-TASK-002.MI6:** Apply per-strand `:attributes/normalize` to `api/add` and the `:attributes` patch of `api/update` before storage consumes those attributes. Preserve existing behavior when a mutation has no attributes.
- **WLH-TASK-002.MI7:** Validate normalized attributes with the same JSON-compatible/storage checks already used by the mutation path; invalid transformed values must fail before commit.
- **WLH-TASK-002.MI8:** Populate normalization context with at least `:hook/type`, `:hook/value`, `:request/source`, `:request/operation`, `:mutation/operation`, and available strand keys for add/update.

## WLH-TASK-002.P3 Done when

- **WLH-TASK-002.DW1:** Tests prove ordered transform chaining can convert CLI-like string attributes such as `{"storyPoints" "3"}` or Clojure attribute maps into stored JSON-compatible normalized attributes.
- **WLH-TASK-002.DW2:** Tests prove no-op transform hooks must return `{:hook/value current-attributes}` and that nil/non-wrapper/invalid return values fail loudly.
- **WLH-TASK-002.DW3:** Tests prove hook exceptions surface as `hook/failed` with hook metadata and original cause data, including nested cause code.
- **WLH-TASK-002.DW4:** Tests prove failed normalization aborts add/update storage changes and does not enqueue mutation events.
- **WLH-TASK-002.DW5:** Tests prove hook-approved add/update mutations still return existing result shapes and still enqueue existing post-commit events.

## WLH-TASK-002.P4 Out of scope

- **WLH-TASK-002.OS1:** Do not add validation pre-commit hooks for strand add/update/burn/supersede in this task except as needed to exercise the runner internally.
- **WLH-TASK-002.OS2:** Do not normalize edge attributes.
- **WLH-TASK-002.OS3:** Do not integrate batch, pattern, or JSON socket payload hooks in this task.

## WLH-TASK-002.P5 References

- **WLH-TASK-002.REF1:** [Plan](../weaver-lifecycle-hooks.plan.md) `WLH-PLAN-001.PH2`.
- **WLH-TASK-002.REF2:** [Weaver Runtime delta](../specs/daemon-runtime.delta.md) `WLH-DELTA-001.CC6` through `WLH-DELTA-001.CC10`, `WLH-DELTA-001.CC22` through `WLH-DELTA-001.CC23`.
- **WLH-TASK-002.REF3:** `src/skein/weaver/api.clj` `add`, `update`, `with-library-classloader`, and event enqueue paths.
- **WLH-TASK-002.REF4:** `src/skein/db.clj` attribute JSON validation/encoding behavior.
