# Task 3: System ops, guild/text-search, in-repo local spools, smoke

**Document ID:** `TASK-Lhc-003`

## TASK-Lhc-003.P1 Scope

Type: AFK

Adopt per-leaf classes for the built-in system ops (`help`, `about`, `prime` —
one registrar in `skein.core.weaver.help`), the guild and text-search spools,
the in-repo local spools (`workflow`, `chime`, `cron`), and the smoke suite's
expectations. Mechanical adoption of the Task 1 mechanism.

## TASK-Lhc-003.P2 Must implement exactly

- **TASK-Lhc-003.MI1:** `register-built-in-ops!` declares all three system ops
  with `:hook-class :read` / `:deadline-class :standard` in their arg-spec
  leaves (they are flat declared ops).
- **TASK-Lhc-003.MI2:** Guild and text-search op registrations/entries declare
  leaf classes matching current behavior; hand-assembled entry constructors
  carry node classes through without defaults (DELTA-Lhc-002.CC2 shape,
  tolerance still on until Task 5).
- **TASK-Lhc-003.MI3:** `spools/workflow`, `spools/chime`, `spools/cron` ops
  declare leaf classes (workflow's blocking verbs keep `:unbounded` at the
  blocking leaf only, per DELTA-Lhc-002.CC4).
- **TASK-Lhc-003.MI4:** Smoke suite (`clojure -M:smoke`) updated for the new
  help envelope/node shape and any surface it asserts.

## TASK-Lhc-003.P3 Done when

- **TASK-Lhc-003.DW1:** Cold `clojure -M:test` green on the owned test
  namespaces (system-op/help registrar tests, guild, text-search, workflow,
  chime, cron owners).
- **TASK-Lhc-003.DW2:** `clojure -M:smoke` green; `make fmt-check lint
  reflect-check docs-check` green; clean `git status --short`.

## TASK-Lhc-003.P4 Out of scope / ownership

- **TASK-Lhc-003.OS1:** No edits to batteries files (Tasks 1–2), `.skein/`
  (Task 4), or core mechanism files beyond the `register-built-in-ops!`
  declaration block in `src/skein/core/weaver/help.clj` (coordinate: that file
  is Task 1-owned; Task 3 starts only after Task 1 closes, and touches only the
  registrar's op declarations).
- Owns: the registrar declaration block in `src/skein/core/weaver/help.clj`,
  guild source/tests, `spools/text-search/`, `spools/workflow/`,
  `spools/chime/`, `spools/cron/` (+ co-located tests), the smoke suite files.
