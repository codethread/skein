# Task 5: Validation and live verification

**Document ID:** `TASK-Werg-005`

## TASK-Werg-005.P1 Scope

Type: HITL (coordinator-owned)

Full-suite validation, deep review, live weaver verification of PLAN-Werg-001.V2,
and feature finish.

## TASK-Werg-005.P2 Must implement exactly

- **TASK-Werg-005.MI1:** `clojure -M:test`, `(cd cli && go test ./...)`, `clojure -M:smoke` all green.
- **TASK-Werg-005.MI2:** `code-review --deep` over the change set; action material findings.
- **TASK-Werg-005.MI3:** reload the canonical weaver and drive a live verification run through the new ops proving every PLAN-Werg-001.V2 point.
- **TASK-Werg-005.MI4:** devflow finish: delta marked Merged (contract docs updated in-place), plan Status/Developer Notes updated, feature archived per the devflow skill FINISH_ARCHIVE procedure.

## TASK-Werg-005.P3 Done when

- **TASK-Werg-005.DW1:** All validation green; V2 checklist demonstrated against the live weaver; feature folder archived.

## TASK-Werg-005.P4 Out of scope

- **TASK-Werg-005.OS1:** Commits unless the user asks.

## TASK-Werg-005.P5 References

- **TASK-Werg-005.REF1:** [PLAN-Werg-001](../workflow-ergonomics.plan.md) PH5, P6.
