# Task 1: Pilot family and checkpoint ownership

**Document ID:** `TASK-Pilot-001`

## TASK-Pilot-001.P1 Scope

Type: AFK

Create the `.skein/pilot.clj` concern file and register a pilot workflow family that
strings the existing devflow stages (intake → proposal → spec-plan → route-after-plan
→ task-breakdown → run-afk-loop, or direct-implementation) into one run-id and routes
into the land family as a continuation, with the pilot checkpoint-ownership map
applied. devflow is an external git-pinned spool (`codethread/devflow` in
`.skein/spools.edn`), so read its composition surface from the synced spool checkout,
not a repo-local source file. Resolve the devflow reuse seam (PROP-Pilot-001.Q1) early
against that pinned surface; the recorded fallback is wrapping the shipped devflow run
as a black box (continuations around it). If even wrapping forces forking stage
definitions, stop and escalate with a note rather than forking silently.

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
  land stage definitions. If the composition surface does not expose stage reuse, use
  the recorded fallback: wrap the shipped devflow run as a black box, routing pilot
  continuations around it.
- **TASK-Pilot-001.MI3 (Q1 resolution):** Before wiring, read the devflow contract via
  the redirect in `spools/devflow.md` and its source from the synced pinned-spool
  checkout (resolve the `codethread/devflow` sha in `.skein/spools.edn` on the
  classpath / via the REPL, or clone that pin) to confirm what the composition surface
  exposes. Do this as the very first action so the whole chain is not sequenced behind
  a possibly-unavailable composition path. Record the seam you used (string the stages,
  or wrap the run as a black box) in a `progress` attr. If neither is possible without
  forking stage definitions, set `status=blocked`, write a `progress` attr naming
  exactly what the surface lacks, and stop — do not fork.
- **TASK-Pilot-001.MI4 (checkpoint ownership):** Apply the PROP-Pilot-001.S2 map
  against the real pinned devflow graph. Brief capture is a `:self` step
  (`:capture-brief`), not a checkpoint. The human-only checkpoints — the intake
  worktree checkpoint (`:create-or-confirm-worktree`), the proposal sign-off
  (`:human-signoff-proposal`), and every abort — stay `:kind :human` with
  `workflow/hitl`. `:discuss-scope` and `:route-after-plan` are already agent
  checkpoints; seats decide them as shipped. The conversion targets
  (`:human-signoff-spec-plan`, `:human-signoff-tasks`, `:human-acceptance-afk`) become
  agent checkpoints: each gains an `escalate` choice (required `:reason` input, routes
  to a chime-notified waiting state) and has no `abort` choice. Aborting a feature
  stays human-only. If the Q1 seam does not let pilot set checkpoint kinds without
  forking, leave those sign-offs human under the black-box fallback and record that in
  `progress`.
- **TASK-Pilot-001.MI4b (human-authority boundary):** the human-only checkpoints stay
  `workflow/hitl` precisely so the seat classifier (task 3) excludes them — the
  classifier is the boundary because `checkpoint-kind` is provenance-only and
  unenforced (TEN-002; RFC-021.REC4.INV). Keep the ownership map so that no human-only
  point is ever reachable as an agent checkpoint.
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
  checkpoint-ownership map: the human-only checkpoints (`:create-or-confirm-worktree`,
  `:human-signoff-proposal`) carry `:kind :human` + `workflow/hitl`; the three
  conversion targets (`:human-signoff-spec-plan`, `:human-signoff-tasks`,
  `:human-acceptance-afk`) carry an `escalate` choice with a required `:reason` and no
  `abort` choice; and no `workflow/hitl` checkpoint is exposed as an agent checkpoint
  (the boundary invariant, RFC-021.REC4.INV — the classifier exclusion itself is tested
  in task 3). Run under the shared lock:
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
  target), `.skein/init.clj` (`runtime-alpha/use!` ordering), `spools/devflow.md` (the
  redirect to the external spool) and the synced `codethread/devflow` checkout pinned
  in `.skein/spools.edn` (the reuse seam — source is not in this repo),
  `test/skein/config_test.clj` (`with-config-runtime` load pattern).
