# Task 25: Add dispatch snapshots and owner introspection

**Document ID:** `TASK-Olr-025`

## TASK-Olr-025.P1 Scope

Type: AFK

Complete core owner-registry adoption after Task 2 by moving dispatch/invocation readers to immutable effective snapshots and exposing deterministic owner/shadow/override introspection. Own dispatch paths in weaver runtime/lifecycle and the five alpha registry namespaces plus focused concurrency tests.

## TASK-Olr-025.P2 Must implement exactly

- **TASK-Olr-025.MI1:** CLI op invocation, event dispatch, lifecycle hook invocation, query resolution, and pattern resolution each read one effective snapshot for a call already beginning.
- **TASK-Olr-025.MI2:** An in-progress call completes against its captured entry/set; later calls observe the replacement. Event replacement never clears queue or recent failures.
- **TASK-Olr-025.MI3:** Registry introspection returns effective owner/provenance plus shadowed/override diagnostics as data while stripping function objects and internal registry handles.
- **TASK-Olr-025.MI4:** Keep direct mutation's explicit owner/authorization behavior and current domain error envelopes.
- **TASK-Olr-025.MI5:** Partition file ownership from Task 3: finish all runtime/access registry-slot and dispatch changes in this task before the ledger task adds load-state slots.

## TASK-Olr-025.P3 Done when

- **TASK-Olr-025.DW1:** Concurrency tests prove old-or-new snapshot behavior for op, event, and hook calls and prove no mixed owner set within one dispatch.
- **TASK-Olr-025.DW2:** `clojure -M:test skein.weaver-test skein.api.events.alpha-test skein.api.hooks.alpha-test skein.alpha-test` passes.
- **TASK-Olr-025.DW3:** `make fmt-check lint reflect-check` passes.

## TASK-Olr-025.P4 Out of scope

- **TASK-Olr-025.OS1:** Do not add module collection, loaded-code accounting, or spool-domain registries.

## TASK-Olr-025.P5 References

- **TASK-Olr-025.REF1:** `DELTA-OlrDrt-001.CC9`, `PLAN-Olr-001.A4`, and task-DAG review note `t9itj` M1/M2.
