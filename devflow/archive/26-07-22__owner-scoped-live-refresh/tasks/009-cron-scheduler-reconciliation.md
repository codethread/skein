# Task 9: Convert cron and scheduler references

**Document ID:** `TASK-Olr-009`

## TASK-Olr-009.P1 Scope

Type: AFK

Implement owner-complete cron job reconciliation across its runtime job table and durable scheduler wake, plus joined status for generic durable wakes whose handler symbols become unresolved.

## TASK-Olr-009.P2 Must implement exactly

- **TASK-Olr-009.MI1:** Partition cron job declarations by module owner. Preserve the two-layer model: runtime job entries hold user handler/cadence; durable `cron/<id>` wakes keep `skein.spools.cron/fire-wake` with job-id payload.
- **TASK-Olr-009.MI2:** Replacing a job reconciles cadence and wake exactly once. Removing an owner/job removes the runtime declaration and cancels its pending wake. A failure in one layer records a degraded/inconsistent outcome and a concrete remedy; it cannot silently leave the other layer active.
- **TASK-Olr-009.MI3:** Preserve cron executor, in-flight count, RNG, failures, and close function identities in versioned spool-state.
- **TASK-Olr-009.MI4:** Add scheduler status projection for retained wakes with missing/renamed handler symbols. Refresh never rewrites arbitrary durable wakes; explicit migrate/cancel/reschedule remains required.
- **TASK-Olr-009.MI5:** Convert `.skein/nvd_scan.clj` to owner job reconciliation without reseeding or double-arming on unchanged refresh.

## TASK-Olr-009.P3 Done when

- **TASK-Olr-009.DW1:** Tests cover unchanged, cadence change, handler change, owner deletion, partial reconcile failure, no double fire, and durable/runtime consistency.
- **TASK-Olr-009.DW2:** Generic scheduler tests prove handler Var replacement is seen at later delivery and handler removal appears in status/failure without deleting the durable row.
- **TASK-Olr-009.DW3:** `clojure -M:test skein.cron-test skein.scheduler-runtime-test skein.scheduler-e2e-test skein.nvd-scan-test` and `make fmt-check lint reflect-check` pass, including deterministic-clock cases.

## TASK-Olr-009.P4 Out of scope

- **TASK-Olr-009.OS1:** Do not change at-least-once scheduler delivery, cron cadence vocabulary, retry policy, or SQLite scheduler schema.

## TASK-Olr-009.P5 References

- **TASK-Olr-009.REF1:** `DELTA-OlrDrt-001.CC11`, terra-med note `zt2r1` finding 1, and `PLAN-Olr-001.V5`.
