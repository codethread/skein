# Task 3: subagent install! declares gate/* + residual survivor sweep (subagent.clj)

**Document ID:** `TASK-Vr-003`
**Slice:** `PLAN-Vr-001.S2b`  **Harness:** build  **Type:** AFK
**Depends on:** TASK-Vr-001

## TASK-Vr-003.P1 Scope

Type: AFK

Seed the `gate/*` attribute namespace from the subagent gate executor's `install!`, owned by its
`.skein/init.clj` use-key `:skein/spools-treadle` (`PROP-Vr-001.C5`). The activation module, not the
file location, is the owner: `gate/*` source sits in the agent-run package but the treadle executor owns
it. Enumerate any residual treadle-era survivor namespace from the live tree — do not guess
(`PROP-Vr-001.C5`, `Q4`, `PLAN-Vr-001.R2`). Disjoint file from the other five S2 seeds and S3/S4/S5 —
parallel after Task 1 (`PLAN-Vr-001.A3`).

**Owned files (disjoint):**
- `spools/agent-run/src/skein/spools/executors/subagent.clj`

## TASK-Vr-003.P2 Must implement exactly

Per `PROP-Vr-001.C5` (seed table):

- **TASK-Vr-003.MI1:** Add one `vocab/declare!` call to the existing `install!` (`subagent.clj:236`)
  declaring `:kind :attr-namespace`, `:name "gate"`, `:owner :skein/spools-treadle`, `:keys` enumerating
  the known keys `deliver-run!`/`spawn-for-gate!` stamp (`subagent.clj:114,184`; keys advisory,
  `PROP-Vr-001.C1`, `C8`), and a one-line `:doc`.
- **TASK-Vr-003.MI2:** Enumerate any residual treadle-era survivor durable namespace from the live tree
  (`PROP-Vr-001.C5`, `Q4`). Declare each confirmed survivor with owner `:skein/spools-treadle`.
  Deliberately exclude the non-durable namespaces `PROP-Vr-001.C5` names — `peer/*` (error codes),
  `batch/*`/`mutation/*` (event payload keys), `handle/*` (in-memory backend handles) — they are not
  durable strand attributes.
- **TASK-Vr-003.MI3:** Owner is `:skein/spools-treadle` — the single verified use-key; no task chooses
  an owner (`PLAN-Vr-001.S2`, `PROP-Vr-001.R2`).
- **TASK-Vr-003.MI4:** Add a focused assertion to `skein.executors.subagent-test` that the install
  declares `gate/*` (and any confirmed survivor) with owner `:skein/spools-treadle`.

## TASK-Vr-003.P3 Done when

- **TASK-Vr-003.DW1:** The subagent `install!` declares `gate/*` (plus any live residual survivor) with
  the single owner `:skein/spools-treadle`; the excluded non-durable namespaces are not seeded
  (`PROP-Vr-001.C5`, `DW2`).
- **TASK-Vr-003.DW2:** Cold focused run `clojure -M:test skein.executors.subagent-test` green
  (focused-runnable, `PLAN-Vr-001.TC4`). Do not run the full suite here.
- **TASK-Vr-003.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Vr-003.P4 Out of scope

- **TASK-Vr-003.OS1:** `agent-run/*` (Task 2, `agent_run.clj` — disjoint file) and the other four S2
  seeds.
- **TASK-Vr-003.OS2:** The excluded non-durable namespaces (`peer/*`, `batch/*`, `mutation/*`,
  `handle/*`) — enumerated as *out* by `PROP-Vr-001.C5`, do not seed them.

## TASK-Vr-003.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-003.P6 References

- **TASK-Vr-003.REF1:** `PLAN-Vr-001.S2` (S2b row), `PLAN-Vr-001.A3`, `PLAN-Vr-001.AA3`,
  `PLAN-Vr-001.R2`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-003.REF2:** `PROP-Vr-001.C5` (seed table, `gate/*` → `:skein/spools-treadle`; excluded
  non-durable set), `Q4`, `R2`; the landed Task 1 `declare!`.
