# Task 1: Pilot family and checkpoint ownership

**Document ID:** `TASK-Pilot-001`

## TASK-Pilot-001.P1 Scope

Type: AFK

Create the `.skein/pilot.clj` concern file and register a pilot workflow family that
strings the existing devflow stages (intake → design → plan/tasks → AFK) into one
run-id and routes into the land family as a continuation, with the pilot
checkpoint-ownership map applied. Resolve the devflow reuse seam (PROP-Pilot-001.Q1)
by reading the `skein.spools.devflow` composition surface; if reuse forces forking
stage definitions, stop and escalate with a note rather than forking silently.

Owned files: `.skein/pilot.clj` (new), `.skein/init.clj`. This is the first link in
the `pilot.clj` chain; every later pilot slice depends on the shape you establish
here. Worker discipline: record `progress` on your strand as you go, set
`status=implemented` only when the validation gate is green, make one atomic commit of
your owned files (no push), never close your strand or touch siblings, kill only by
PID, and do all live validation in a disposable `--workspace` world — never the
canonical `.skein` world.

## TASK-Pilot-001.P2 Must implement exactly

- **TASK-Pilot-001.MI1:** New `.skein/pilot.clj` with an `ns`-style docstring (this
  is a concern file loaded by `runtime-alpha/use!`, so match the `load-file` style of
  `workflows.clj`/`attention.clj`, not a compiled `ns` — follow the sibling files'
  header form). No module-level atoms: any pilot state is runtime-owned via
  `skein.api.runtime.alpha/spool-state`.
- **TASK-Pilot-001.MI2:** A `pilot/install!` fn that registers a pilot workflow family
  (via `skein.spools.workflow`) stringing the devflow stages as one run-id. Reuse the
  `skein.spools.devflow` stage definitions through its composition surface; route into
  the land family (`.skein/workflows.clj`) as a continuation. Do not fork devflow or
  land stage definitions.
- **TASK-Pilot-001.MI3 (Q1 resolution):** Before wiring, read `spools/devflow.md` and
  `src/skein/spools/devflow.clj` to confirm the composition surface exposes enough
  stage reuse. Record the seam you used (or the blocker) in a `progress` attr. If reuse
  is impossible without forking stage definitions, set `status=blocked`, write a
  `progress` attr naming exactly what the surface lacks, and stop — do not fork.
- **TASK-Pilot-001.MI4 (checkpoint ownership):** Apply the PROP-Pilot-001.S2 map. Brief
  capture and RFC/scope acceptance-or-abort stay `:kind :human` with `workflow/hitl`.
  Spec-plan sign-off, task-breakdown acceptance, and AFK acceptance become agent
  checkpoints: each gains an `escalate` choice (required `:reason` input, routes to a
  chime-notified waiting state) and has no `abort` choice. Aborting a feature stays
  human-only.
- **TASK-Pilot-001.MI5 (evidence before checkpoint):** Because a routed choice is a
  hard cutover that closes the stage's remaining steps, structure every stage so its
  evidence is recorded before its checkpoint. Document this rule in a comment at the
  family definition.
- **TASK-Pilot-001.MI6:** Register the `:pilot` module in `.skein/init.clj` via
  `runtime-alpha/use!`, ordered `:after` `:config`, `:workflows`, and `:harnesses`
  (pilot composes all three). Mirror the existing `use!` blocks' shape.

## TASK-Pilot-001.P3 Done when

- **TASK-Pilot-001.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check
  lint reflect-check docs-check` reports zero findings.
- **TASK-Pilot-001.DW2:** The pilot config loads in an isolated runtime without error.
  Prove it in a disposable world: `ws=$(mktemp -d)`; seed it with the repo `.skein`
  config plus your `pilot.clj`; start a weaver with `mill weaver start --workspace
  "${ws:?}"`; confirm `mill weaver status --workspace "${ws:?}"` is healthy and the
  pilot family is registered (query the family through the weaver repl). Stop the
  disposable weaver by PID when done. Never touch the canonical `.skein` world.
- **TASK-Pilot-001.DW3:** A focused test under `test/skein/pilot_test.clj` asserts the
  checkpoint-ownership map: the three human-only checkpoints carry `:kind :human` +
  `workflow/hitl`; the three converted checkpoints carry an `escalate` choice with a
  required `:reason` and no `abort` choice. Run under the shared lock:
  `flock -w 3600 /tmp/skein-test.lock env PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
  clojure -M:test`.
- **TASK-Pilot-001.DW4:** One atomic commit of `.skein/pilot.clj`, `.skein/init.clj`,
  and `test/skein/pilot_test.clj` (no push).

## TASK-Pilot-001.P4 Out of scope

- **TASK-Pilot-001.OS1:** Seats, lease, dispatcher, review routing, CI gates, train,
  hang budgets, chime rules — later slices. This slice only stands up the family and
  checkpoint ownership.

## TASK-Pilot-001.P5 References

- **TASK-Pilot-001.REF1:** [PROP-Pilot-001](../proposal.md) S1–S2, R1;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH1; [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md)
  REC2–REC3.
- **TASK-Pilot-001.REF2:** `.skein/workflows.clj` (land family, the continuation
  target), `.skein/init.clj` (`runtime-alpha/use!` ordering), `spools/devflow.md` and
  `src/skein/spools/devflow.clj` (the reuse seam), `test/skein/config_test.clj`
  (`with-config-runtime` load pattern).
