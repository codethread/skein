# Task 5: CI poller driving the land op

**Document ID:** `TASK-Pilot-005`

## TASK-Pilot-005.P1 Scope

Type: AFK

Add a cron CI poller to `.skein/pilot.clj` that drives the land family's `:ci-green`
`:self` step through the existing land op. The land family is a `:self`-step workflow
that needs a live driver; the poller is that driver for pilot runs. Once `gh` checks
are green at the step's sha it runs `land complete <feature> step=ci-green` with
HEAD-sha evidence in the completion note, exactly as a human coordinator drives that
wait. The land family (`.skein/workflows.clj`) is not edited — there is no
`:self`→`:ci` gate conversion, so REC7's promise to leave the hands-on land family
alone holds.

Owned files: `.skein/pilot.clj` (CI poller section). Worker discipline: record
`progress`, `status=implemented` only on a green gate, one atomic commit of owned files
(no push), never close your strand or touch siblings, kill only by PID, live validation
in a disposable world only.

## TASK-Pilot-005.P2 Must implement exactly

- **TASK-Pilot-005.MI1 (poller drives the land op):** a cron poller in
  `.skein/pilot.clj` (shape from `.skein/nvd_scan.clj`) reads `gh pr checks` / `gh run
  list` for the pilot run's branch. When checks are green at the `:ci-green` step's sha
  it runs `land complete <feature> step=ci-green` as an unattended seat, `:by
  "pilot-ci-poller"`, with a note naming the sha and the check evidence — the same op a
  human coordinator uses. It never completes the step on evidence at a different sha.
- **TASK-Pilot-005.MI2 (land family untouched):** do not edit `.skein/workflows.clj` or
  any land stage definition. `:ci-green` stays a `:self` step; the poller acts through
  the public land op so the hands-on land path is byte-for-byte unchanged. No
  `:self`→`:ci` gate conversion.
- **TASK-Pilot-005.MI3 (kanban-finish is already shipped — no work):** the land cleanup
  step already kanban-finishes the card for card-backed runs
  (`.skein/workflows.clj`, asserted by `land-cleanup-instruction-interpolates-the-real-card-id`
  in `test/skein/config_test.clj`, and stated in RFC-021.P1.1). It is not a gap and this
  slice adds nothing there. If you find yourself editing land cleanup, stop — you are
  re-doing shipped behavior.
- **TASK-Pilot-005.MI4 (no gh in tests):** the poller must never shell to real `gh` from
  a direct config load or test (mirror how `nvd_scan.clj` keeps its scan out of
  `config_test`). Keep the gh-command layer behind a fn a test can stub, and keep the
  `land complete` invocation behind a seam a test can observe without running land.

## TASK-Pilot-005.P3 Done when

- **TASK-Pilot-005.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check
  lint reflect-check docs-check` reports zero findings.
- **TASK-Pilot-005.DW2:** `test/skein/pilot_test.clj` gains cases: with stubbed checks
  green at the step's sha, the poller invokes `land complete <feature> step=ci-green`
  `:by "pilot-ci-poller"` with a sha-named note; green checks at a different sha do not
  drive the step. The existing `test/skein/config_test.clj` land-family assertions still
  pass unchanged (the manual path and the shipped cleanup kanban-finish are untouched).
  `flock -w 3600 /tmp/skein-test.lock env PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
  clojure -M:test` green.
- **TASK-Pilot-005.DW3:** In a disposable world, drive a pilot run to its `:ci-green`
  step, feed stubbed green checks, and confirm the poller completes the step through the
  land op and the run advances; confirm a manual land run's behavior is unchanged. Stop
  the disposable weaver by PID.
- **TASK-Pilot-005.DW4:** One atomic commit of `.skein/pilot.clj` and the `pilot_test.clj`
  additions (no push).

## TASK-Pilot-005.P4 Out of scope

- **TASK-Pilot-005.OS1:** Auto-signoff and train admission (task 6); the lock-anomaly
  chime rule (task 8). This slice provides the CI driver the signoff criteria read.
- **TASK-Pilot-005.OS2:** Any edit to the land family or its cleanup step — the land
  family is driven, never modified.

## TASK-Pilot-005.P5 References

- **TASK-Pilot-005.REF1:** [PROP-Pilot-001](../proposal.md) S6 (CI half), P1;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH5; [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md)
  REC7 (CI bullet), P1.1.
- **TASK-Pilot-005.REF2:** `.skein/workflows.clj` (land family — the `:ci-green` `:self`
  step and the `land complete` op; read only), `.skein/nvd_scan.clj` (gh-command + cron
  shape), `test/skein/config_test.clj` (land-family assertions, including the shipped
  cleanup kanban-finish), `spools/cron.cookbook.md`.
