# Task 1: Add the Clock runtime and manual test seam

**Document ID:** `TASK-Clp-001`

## TASK-Clp-001.P1 Scope

Type: AFK

Deliver the complete runtime-owned Clock path before changing polling: public capability and system implementation, runtime storage and access, manual test implementation, every existing `set-clock!` caller migration, and focused deterministic coverage.

## TASK-Clp-001.P2 Must implement exactly

- **TASK-Clp-001.MI1:** Add public-first `src/skein/api/clock/alpha.clj` with the validated Clock capability (`clock`/`clock?` plus `now`/`sleep!`) and `system-clock`. `now` returns an `Instant`; `sleep!` accepts a non-negative `Duration`, returns nil, and fails loudly with the rejected value for nil, wrong-type, or negative input. Keep all source lines within 96 columns.
- **TASK-Clp-001.MI2:** Change `src/skein/core/weaver/runtime.clj` to store Clock values, add a core Clock accessor, route its `now` through the capability, and start with a system Clock. Change `src/skein/core/weaver/scheduler.clj`'s cycle-avoiding direct read to call the Clock capability. Add `skein.api.runtime.alpha/clock` and keep `runtime/now` data-first.
- **TASK-Clp-001.MI3:** In `src/skein/test/alpha.clj`, add public `manual-clock`, change `set-clock!` to accept a Clock, and keep `advance!` runtime-first. Manual control state rides on the capability map; no protocol is involved. Uninstalled sleep advances and runs zero pumps; installed zero or positive sleep advances and runs that runtime's pumps; explicit advance rejects zero/negative Duration; one manual Clock cannot install into two runtimes; advancing a non-manual Clock fails loudly.
- **TASK-Clp-001.MI4:** Migrate every existing clock-function test caller in `test/skein/notes_test.clj`, `test/skein/scheduler_runtime_test.clj`, `test/skein/scheduler_e2e_test.clj`, `test/skein/api/scheduler/alpha_test.clj`, `test/skein/cron_test.clj`, and `test/skein/cron_e2e_test.clj`. The notes test replaces its mutable function closure by installing a fresh manual Clock at each absolute timestamp, including backwards timestamps; do not add a backwards-advance seam.
- **TASK-Clp-001.MI5:** Add capability/system coverage in `skein.api.clock.alpha-test` and manual-control coverage in the existing `skein.test.alpha-test`, including observable pump execution and single-runtime installation. Register the new Clock test namespace in the focused runner's declared island list. Do not assert exact system time or call positive-duration system sleep in tests.

## TASK-Clp-001.P3 Done when

- **TASK-Clp-001.DW1:** `clojure -M:test skein.api.clock.alpha-test skein.api.runtime.alpha-test skein.test.alpha-test skein.notes-test skein.scheduler-runtime-test skein.scheduler-e2e-test skein.api.scheduler.alpha-test skein.cron-test skein.cron-e2e-test` passes cold.
- **TASK-Clp-001.DW2:** The changed tests contain no new `Thread/sleep`, wall-clock deadline, or nondeterministic coordination.
- **TASK-Clp-001.DW3:** A source search finds no `set-clock!` call still passing a function.

## TASK-Clp-001.P4 Out of scope

- **TASK-Clp-001.OS1:** Do not change the spool polling helper, workflow, roster, root specs, authored guides, or generated API reference in this slice.
- **TASK-Clp-001.OS2:** Do not add arbitrary-thread virtual scheduling or fold event-lane quiescence into Clock.

## TASK-Clp-001.P5 References

- **TASK-Clp-001.REF1:** [PLAN-Clp-001](../clock-aware-polling.plan.md), especially A1-A3, V1, R1-R2, and TC3-TC4.
- **TASK-Clp-001.REF2:** [REPL API delta](../specs/repl-api.delta.md) and [Weaver Runtime delta](../specs/daemon-runtime.delta.md).
- **TASK-Clp-001.REF3:** `RFC-Dtt-001.REC1`, `REC2`, and `REC4` in the linked deterministic-test-time RFC.
