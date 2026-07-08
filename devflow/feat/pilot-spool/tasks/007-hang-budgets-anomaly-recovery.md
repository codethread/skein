# Task 7: Hang budgets and anomaly recovery

**Document ID:** `TASK-Pilot-007`

## TASK-Pilot-007.P1 Scope

Type: AFK

Add per-harness hang budgets to `.skein/pilot.clj` and the triage recovery path: the
dispatcher flags an in-flight run past its budget as an attention state; a triage seat
inspects `agent logs` and either kills by PID through `agent kill` and retries within
`max-attempts`, or extends the budget once with a recorded reason. One automatic
kill-and-retry per attempt chain, then escalate. Pattern kills stay forbidden.

Owned files: `.skein/pilot.clj` (hang-budget section — plugs into the task-3 dispatcher
seam). Worker discipline: record `progress`, `status=implemented` only on a green gate,
one atomic commit of owned files (no push), never close your strand or touch siblings,
kill only by PID, live validation in a disposable world only.

## TASK-Pilot-007.P2 Must implement exactly

- **TASK-Pilot-007.MI1 (budget policy):** per-harness hang budgets as policy data
  (`pilot/hang-budget-secs`), with a documented default. Record whether the budget is
  measured from spawn or from last observed activity (PROP-Pilot-001.Q3) and keep the
  choice in one place so it can be tuned.
- **TASK-Pilot-007.MI2 (breach detection):** the dispatcher (task 3 seam) flags an
  in-flight run past its budget as a `hang-budget-breach` attention state.
- **TASK-Pilot-007.MI3 (triage seat):** a triage seat inspects `agent logs` and either
  (a) kills the run by PID through `agent kill` and retries within `max-attempts`, or
  (b) extends the budget once with a recorded reason. Exactly one automatic
  kill-and-retry per attempt chain; after that, escalate (RFC-021.A2). Kills are
  PID-only via `agent kill` — never `pkill -f`/pattern kills.
- **TASK-Pilot-007.MI4 (bound):** the automatic kill-and-retry is bounded by
  `max-attempts`; exhaustion raises the escalation signal (chime wiring is task 8).

## TASK-Pilot-007.P3 Done when

- **TASK-Pilot-007.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check
  lint reflect-check docs-check` reports zero findings.
- **TASK-Pilot-007.DW2:** `test/skein/pilot_test.clj` gains cases: a run past budget is
  flagged as a breach; the triage path allows exactly one automatic kill-and-retry per
  attempt chain, then escalates; a budget extension requires a reason. `flock -w 3600
  /tmp/skein-test.lock env PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`
  green.
- **TASK-Pilot-007.DW3:** In a disposable world, fabricate an over-budget in-flight run
  and confirm the dispatcher flags it and the triage seat performs one PID kill-and-retry
  then escalates on the next breach. Stop the disposable weaver by PID. (The live
  induced-hang drill is task 10.)
- **TASK-Pilot-007.DW4:** One atomic commit of `.skein/pilot.clj` and the `pilot_test.clj`
  additions (no push).

## TASK-Pilot-007.P4 Out of scope

- **TASK-Pilot-007.OS1:** The chime rule that fires on hang-budget breach (task 8); the
  induced-anomaly drill (task 10). This slice owns the budget policy and the triage
  recovery path.

## TASK-Pilot-007.P5 References

- **TASK-Pilot-007.REF1:** [PROP-Pilot-001](../proposal.md) S7, Q3;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH7; [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md)
  A2.
- **TASK-Pilot-007.REF2:** `strand agent about` (`agent kill`, `agent logs`,
  `max-attempts`, `agent retry`); repo CLAUDE.md "Hard rules" (PID-only kills).
