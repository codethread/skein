# Task 5: CI gates and land-family gap closes

**Document ID:** `TASK-Pilot-005`

## TASK-Pilot-005.P1 Scope

Type: AFK

Turn CI waits into `:ci` gates completed by a cron poller over `gh` checks, and close
the two land-family gaps the card sketch named: land cleanup kanban-finishes the card,
and the ci-green step self-completes. The poller lives in `.skein/pilot.clj`; the
land-family edits live in `.skein/workflows.clj` and must not change the hands-on land
path.

Owned files: `.skein/pilot.clj` (CI poller section), `.skein/workflows.clj`. Worker
discipline: record `progress`, `status=implemented` only on a green gate, one atomic
commit of owned files (no push), never close your strand or touch siblings, kill only
by PID, live validation in a disposable world only.

## TASK-Pilot-005.P2 Must implement exactly

- **TASK-Pilot-005.MI1 (`:ci` gate):** in the pilot family, a CI wait is a `:ci` gate
  (a waiter gate), not a `:self` step. The gate names the sha it waits on.
- **TASK-Pilot-005.MI2 (poller):** a cron poller in `.skein/pilot.clj` (shape from
  `.skein/nvd_scan.clj`) reads `gh pr checks` / `gh run list`, and when checks are green
  at the gate's sha, closes the gate `:by "pilot-ci-poller"` with a note naming the sha
  and the check evidence. It never closes a gate on evidence at a different sha.
- **TASK-Pilot-005.MI3 (land gap — kanban-finish):** land cleanup kanban-finishes the
  feature's card (the card the pilot run tracks). Add this to the land family's cleanup
  step in `.skein/workflows.clj` without changing the manual land path's observable
  behavior — the finish runs on the same cleanup the hands-on land already performs.
- **TASK-Pilot-005.MI4 (land gap — ci-green self-completion):** the land family's
  ci-green step self-completes once the `:ci` gate is closed, closing the
  PROP-Pilot-001.P1.1 gap for pilot runs while leaving the hands-on land family's
  behavior unchanged.
- **TASK-Pilot-005.MI5 (no gh in tests):** the poller must never shell to real `gh`
  from a direct config load or test (mirror how `nvd_scan.clj` keeps its scan out of
  `config_test`). Keep the gh-command layer behind a fn a test can stub.

## TASK-Pilot-005.P3 Done when

- **TASK-Pilot-005.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check
  lint reflect-check docs-check` reports zero findings.
- **TASK-Pilot-005.DW2:** `test/skein/pilot_test.clj` gains cases: the poller closes a
  `:ci` gate only when stubbed checks are green at the gate's sha, and closes with `:by
  "pilot-ci-poller"` and a sha-named note; green checks at a different sha do not close
  the gate. The existing `test/skein/config_test.clj` land-family assertions still pass
  (the manual path is unchanged). `flock -w 3600 /tmp/skein-test.lock env
  PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.
- **TASK-Pilot-005.DW3:** In a disposable world, drive a pilot run to its `:ci` gate,
  feed stubbed green checks, and confirm the gate closes and the ci-green step
  self-completes; drive a manual land run and confirm its behavior is unchanged and the
  card is kanban-finished at cleanup. Stop the disposable weaver by PID.
- **TASK-Pilot-005.DW4:** One atomic commit of `.skein/pilot.clj`,
  `.skein/workflows.clj`, and the `pilot_test.clj` additions (no push).

## TASK-Pilot-005.P4 Out of scope

- **TASK-Pilot-005.OS1:** Auto-signoff and train admission (task 6); the lock-anomaly
  chime rule (task 8). This slice provides the CI gate the signoff criteria will read.

## TASK-Pilot-005.P5 References

- **TASK-Pilot-005.REF1:** [PROP-Pilot-001](../proposal.md) S6 (CI half), P1;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH5; [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md)
  REC7 (CI bullet); card 3tgaj sketch (the two named gaps).
- **TASK-Pilot-005.REF2:** `.skein/workflows.clj` (land family, cleanup step),
  `.skein/nvd_scan.clj` (gh-command + cron shape), `test/skein/config_test.clj`
  (land-family assertions), `spools/cron.cookbook.md`.
