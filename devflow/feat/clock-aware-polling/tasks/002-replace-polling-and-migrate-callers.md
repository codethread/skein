# Task 2: Replace polling and migrate shipped callers

**Document ID:** `TASK-Clp-002`

## TASK-Clp-002.P1 Scope

Type: AFK

Replace the pre-v1 epoch-deadline helper with required-Clock relative-timeout polling, then migrate workflow and roster without changing their result shapes or timeout defaults.

## TASK-Clp-002.P2 Must implement exactly

- **TASK-Clp-002.MI1:** In `src/skein/api/spool/alpha.clj`, delete `poll-until-deadline!` and add public-first `(poll-until! clock opts)`. The exact option keys are `:timeout-ms`, `:poll-ms`, `:check`, `:pred->result`, and `:on-timeout`; timeout is non-negative, cadence is positive, and function fields remain required. Validate the Clock and full boundary before checking or sleeping.
- **TASK-Clp-002.MI2:** Derive an `Instant` deadline from the supplied Clock. Check once immediately, return the first non-nil projected result, call `on-timeout` with the last value at or after the deadline, and otherwise call Clock `sleep!` with the polling Duration before recurring. No `System/currentTimeMillis` or `Thread/sleep` remains in the helper.
- **TASK-Clp-002.MI3:** Rewrite the spool API namespace docstring so it names the new helper and no longer frames the removed pre-v1 shape as a frozen compatibility promise. Update direct tests in `test/skein/api/spool_test.clj` to use an uninstalled manual Clock and prove success, timeout, exact options, malformed Clock/options, and positive cadence without wall time.
- **TASK-Clp-002.MI4:** Migrate `spools/workflow/src/skein/spools/workflow.clj` and `spools/roster/src/skein/spools/roster.clj` to pass `(runtime/clock runtime)`, supply relative timeout, and delete both caller-side `System/currentTimeMillis` deadline calculations. Tighten their caller-facing `:poll-ms` specs, docstrings, and error messages from non-negative to positive while keeping `:timeout-secs` non-negative.
- **TASK-Clp-002.MI5:** Update workflow and roster tests for positive cadence and deterministic timeout behavior using their existing runtime/test harnesses. Caller errors continue to name `:timeout-secs` and `:poll-ms`; internal `:timeout-ms` does not leak through their validation surface.

## TASK-Clp-002.P3 Done when

- **TASK-Clp-002.DW1:** `clojure -M:test skein.api.spool-test skein.spools.workflow-test skein.roster-test` passes cold.
- **TASK-Clp-002.DW2:** `rg -n "poll-until-deadline|System/currentTimeMillis|Thread/sleep" src/skein/api/spool/alpha.clj spools/workflow/src/skein/spools/workflow.clj spools/roster/src/skein/spools/roster.clj` finds none.
- **TASK-Clp-002.DW3:** Direct timeout tests finish against manual time and contain no elapsed-wall-time assertion.

## TASK-Clp-002.P4 Out of scope

- **TASK-Clp-002.OS1:** Do not add optional/defaulted Clock injection, preserve the old helper as an alias, or change workflow/roster return maps.
- **TASK-Clp-002.OS2:** Do not update root specs, broad authored guides, or generated API reference in this slice.

## TASK-Clp-002.P5 References

- **TASK-Clp-002.REF1:** [PLAN-Clp-001](../clock-aware-polling.plan.md), especially A4-A5, CM1-CM3, V2-V3, and R3-R4.
- **TASK-Clp-002.REF2:** [REPL API delta](../specs/repl-api.delta.md) CC4/D2 and [Alpha Surface delta](../specs/alpha-surface.delta.md) CC2.
- **TASK-Clp-002.REF3:** [PROP-Clp-001](../proposal.md) P1 and S4.
