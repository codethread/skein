# Task 7: api-docs regen + acceptance / atomic-landing gate

**Document ID:** `TASK-Ru-007`
**Slice:** `PLAN-Ru-001.S7`  **Harness:** build  **Type:** AFK
**Depends on:** TASK-Ru-001..006

## TASK-Ru-007.P1 Scope

Type: AFK

Regenerate the touched spools' `*.api.md`, then run the full acceptance gate in one place and record each
result (`PROP-Ru-001.P6`, `DW1`–`DW5`). This is the **authoritative** gate for the full-suite-only
`skein.agent-run-test` add-libs shard `B` the S1–S4 spine deferred (`PLAN-Ru-001.A5`, `R1`, `V1`). No new
source files.

**Owned files:**
- `spools/agent-run.api.md`, `spools/delegation.api.md` (regenerated).
- `SPEC-Ru-001`–`004` marked as recorded no-change (no root-spec edit — all four deltas are no-change,
  `PLAN-Ru-001.CM2`, `TC2`).

## TASK-Ru-007.P2 Must implement exactly

- **TASK-Ru-007.MI1:** `make api-docs` — clean regen; `git status --short` shows only the expected
  `spools/agent-run.api.md`/`spools/delegation.api.md` changes; commit them (`docs-check` fails while a
  regenerated api.md is uncommitted) (`PROP-Ru-001.P6`).
- **TASK-Ru-007.MI2:** Run every P6 gate: `make build`;
  `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (full locked suite — the authoritative gate for the
  `skein.agent-run-test` add-libs shard `B`; bare `flock` from PATH, hold the lock, never a vendored
  absolute path; serialize across sibling agents); `(cd cli && go test ./...)`; `clojure -M:smoke`;
  `make fmt-check lint reflect-check docs-check` at zero findings.
- **TASK-Ru-007.MI3:** End-to-end check (`PROP-Ru-001.DW1`–`DW4`, `PLAN-Ru-001.S7`): a completing pi-json
  run records the four usage keys, a claude-json run records the same from its result object, a raw run
  records none; a pi terminal-error run records its cost; `strand vocab` lists the four keys under
  `agent-run` (owner `:skein/spools-shuttle`); `strand agent spend` returns the C7 JSON with derived
  per-run duration and harness/day grouping.
- **TASK-Ru-007.MI4:** `git status --short` clear of generated SQLite and runtime metadata artifacts.

## TASK-Ru-007.P3 Done when

- **TASK-Ru-007.DW1:** `PROP-Ru-001.DW1`–`DW5` proven in one atomic, additive landing — no migration, no
  backfill, no cutover, no weaver restart. All P6 gates green.
- **TASK-Ru-007.DW2:** Results recorded gate-by-gate in the task result so the coordinator can verify
  against ground truth.

## TASK-Ru-007.P4 Out of scope

- **TASK-Ru-007.OS1:** Landing/merging — coordinator-only (`strand land`); worker stops at
  implemented+committed. The landing is purely additive: no HITL cutover, no weaver restart. The Go
  `spend` subcommand is arg-spec data on an existing op, so `make build` is the CLI pickup; the canonical
  world otherwise picks the change up through the pickup ladder after landing — targeted
  `(require … :reload)` per changed namespace then `runtime-alpha/reload!`, no restart
  (`PROP-Ru-001.C10`, `PLAN-Ru-001.CM4`).
- **TASK-Ru-007.OS2:** Fixing failures beyond mechanical api.md regen: a red gate means stop and report,
  not improvise scope. There is no root-spec edit to make — the deltas are recorded no-change only.

## TASK-Ru-007.P5 Commit

- Atomic single commit for the api.md regen (if changed), devflow message (why-focused), **no push**.

## TASK-Ru-007.P6 References

- **TASK-Ru-007.REF1:** `PLAN-Ru-001.S7`, `V1`, `AA6` (shard `B` authoritative gate), `CM2` (no-change
  deltas), `TC2`.
- **TASK-Ru-007.REF2:** `PROP-Ru-001.P6` (gates), `P7` (`DW1`–`DW5`), `C10` (additive landing + pickup
  ladder); the landed Tasks 1–6.
