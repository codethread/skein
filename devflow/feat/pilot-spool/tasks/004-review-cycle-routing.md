# Task 4: Review cycle as routing

**Document ID:** `TASK-Pilot-004`

## TASK-Pilot-004.P1 Scope

Type: AFK

Add the review cycle to `.skein/pilot.clj`: after AFK the run pours a review stage at
an explicit `<base>..HEAD` range; a seat reads the synthesis; a clean synthesis
advances toward land, findings route a bounded revise loop where the seat sizes fix
tasks, treadle executes them, and re-review runs against the new HEAD; exhausted rounds
escalate with the full round history.

Owned files: `.skein/pilot.clj` (review section only). Worker discipline: record
`progress`, `status=implemented` only on a green gate, one atomic commit of owned files
(no push), never close your strand or touch siblings, kill only by PID, live validation
in a disposable world only.

## TASK-Pilot-004.P2 Must implement exactly

- **TASK-Pilot-004.MI1 (review pour):** on AFK completion, pour a review stage running
  the declared roster at an explicit `<base>..HEAD` commit range (the `change-review`
  roster in `.skein/reviewers.clj`; the range form is `strand agent review <target>
  --roster change-review --commit-range <base>..HEAD`).
- **TASK-Pilot-004.MI2 (synthesis routing):** a seat reads the synthesis. Clean
  synthesis routes the run toward land. Findings route a revise loop.
- **TASK-Pilot-004.MI3 (revise loop):** the seat sizes fix tasks per the delegation
  policy (`strand agent about` :task-sizing), treadle executes them, and a re-review
  runs against the new HEAD. Rounds are bounded by `pilot/max-review-rounds` (default
  3). Exhaustion escalates to a human with the full round history (escalation wiring in
  task 8; here, expose the exhaustion signal and carry the round history).
- **TASK-Pilot-004.MI4 (HEAD-pinned evidence):** review findings are live only for the
  HEAD sha they name (RFC-021.A4). Every review and re-review runs against an explicit
  range; a synthesis at any older sha is non-evidence for the advance decision. Pin the
  evidence to the sha in the seat's decision note.
- **TASK-Pilot-004.MI5 (fixed-vs-remaining):** re-review notes state which prior
  findings are fixed and which remain, so a successor seat reads a true delta.

## TASK-Pilot-004.P3 Done when

- **TASK-Pilot-004.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check
  lint reflect-check docs-check` reports zero findings.
- **TASK-Pilot-004.DW2:** `test/skein/pilot_test.clj` gains cases: a clean synthesis
  routes toward land; findings open a revise round; the round counter is bounded at
  `pilot/max-review-rounds` and exhaustion raises the escalation signal with round
  history; a synthesis whose sha is not HEAD is rejected as non-evidence. `flock -w
  3600 /tmp/skein-test.lock env PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure
  -M:test` green.
- **TASK-Pilot-004.DW3:** In a disposable world, drive a pilot run through one review
  round with a fabricated finding and confirm a sized fix task is poured and a
  re-review runs at the new HEAD. Stop the disposable weaver by PID.
- **TASK-Pilot-004.DW4:** One atomic commit of `.skein/pilot.clj` and the `pilot_test.clj`
  additions (no push).

## TASK-Pilot-004.P4 Out of scope

- **TASK-Pilot-004.OS1:** CI gates and the merge train (tasks 5–6); the chime rule that
  fires on round exhaustion (task 8). This slice owns review routing and the round
  bound only.

## TASK-Pilot-004.P5 References

- **TASK-Pilot-004.REF1:** [PROP-Pilot-001](../proposal.md) S5;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH4; [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md)
  REC6, A4.
- **TASK-Pilot-004.REF2:** `.skein/reviewers.clj` (the `change-review` roster), `strand
  agent about` (review, delegate, :task-sizing), `spools/agents.md` (roster review and
  synthesis).
