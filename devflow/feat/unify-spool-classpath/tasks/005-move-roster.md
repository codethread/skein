# Task 5: move roster to per-spool root + coordinate + guard

**Document ID:** `TASK-usc-005`
**Slice:** `PLAN-usc-001.PH2` (roster — activated spool)  **Harness:** grunt/patch-gpt  **Type:** AFK
**Branch:** `unify-spool-classpath`

## TASK-usc-005.P1 Scope

Type: AFK

Move `roster` to `spools/roster/src` behind a coordinate and its own `:spools`-guarded activation
(`PLAN-usc-001.PH2` activated shape). No `:file` module requires `roster`, so it creates no
`:config`/`:workflows` edge. `spools/src` stays on `:paths`; ns path unchanged (`PLAN-usc-001.TC3`).

**Owned files (SHARED with other PH2 slices — sequential):**
- `spools/src/skein/spools/roster.clj` → `spools/roster/src/skein/spools/roster.clj` (`git mv`)
- `deps.edn` (`:test`/`:reflect-check` `:extra-paths`; `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` args)
- `scripts/generate_api_docs.clj` (roster `:source`)
- `.skein/spools.edn` (new coordinate)
- `.skein/init.clj` (`:skein/spools-roster` guard)
- `test/skein/config_test.clj` (coordinate assertions)

## TASK-usc-005.P2 Must implement exactly

- **TASK-usc-005.MI1:** `git mv` `roster.clj` to `spools/roster/src/skein/spools/roster.clj`.
- **TASK-usc-005.MI2:** Add `spools/roster/src` to the `deps.edn` `:test`/`:reflect-check` `:extra-paths` and
  the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists (`PROP-usc-001.C5`).
- **TASK-usc-005.MI3:** Repoint `scripts/generate_api_docs.clj`'s `roster` `:source` to the new root.
- **TASK-usc-005.MI4:** Add `skein.spools/roster {:local/root "../spools/roster"}` to `.skein/spools.edn`.
- **TASK-usc-005.MI5:** Add `:spools ['skein.spools/roster]` to the `:skein/spools-roster` `use!` in
  `.skein/init.clj`, preserving `:after` verbatim.
- **TASK-usc-005.MI6:** Add the `roster` coordinate to the `test/skein/config_test.clj` assertions.

## TASK-usc-005.P3 Done when

- **TASK-usc-005.DW1:** Cold focused gate green (`PLAN-usc-001.PH2`):
  `clojure -M:test skein.roster-test skein.config-test`
- **TASK-usc-005.DW2:** `make fmt-check lint reflect-check` pass.

## TASK-usc-005.P4 Out of scope

- **TASK-usc-005.OS1:** PH3 consent sweep / assertion gate (Task 12); PH4 classpath flip (Task 13); docs
  (Task 14).

## TASK-usc-005.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused,
  **no push**.

## TASK-usc-005.P6 References

- **TASK-usc-005.REF1:** `PLAN-usc-001.PH2` (activated shape), `AA4`/`AA10`/`AA11`, `TC3`, `V1`/`V5`.
- **TASK-usc-005.REF2:** `PROP-usc-001.C3`/`C5`/`C6`/`C8`; `DELTA-usc-repl-001.CC3` (roster reframed as an
  opt-in worked example — the load-mechanism change this move realizes; the spec edit itself is Task 14).

## TASK-usc-005.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate
  sibling or parent strands; commit only your own slice.
