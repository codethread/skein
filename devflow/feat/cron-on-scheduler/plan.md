# Cron on Scheduler Plan

**Document ID:** `PLAN-cron-on-scheduler-001`
**Feature:** `cron-on-scheduler`
**Proposal:** [proposal.md](./proposal.md) (`PROP-cron-on-scheduler-001`)
**RFC:** [RFC-009 Weaver Scheduler Primitive](../../rfcs/2026-06-29-weaver-scheduler.md)
**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md) (SPEC-004.P10d, consumed unchanged but for the generation-aware-retirement fix in `SPEC-004.C102`/new `.C102b`), [alpha-surface.md](../../specs/alpha-surface.md) (SPEC-005.C4, cron is userland)
**Feature specs:** [specs/README.md](./specs/README.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (`DELTA-cron-on-scheduler-runtime-001`)
**Status:** Draft
**Last Updated:** 2026-07-10

## PLAN-cron-on-scheduler-001.P1 Goal and scope

Rebuild the cron spool as the userland recurrence layer over the durable
scheduler wake primitive so a registered job's cadence survives weaver restart
and reload (`PROP-cron-on-scheduler-001.G1`), the weaver keeps one timing
substrate and one introspection surface (`.G2`/`.G3`), and cron gains a
documented at-least-once delivery contract (`.G4`). Cron stops owning any
timing: it persists a durable wake per job, reschedules the next wake from a
tiny handler on the shared event lane, offloads the job body to a cron-owned
execution executor, and records job outcomes cron-side without touching the
cadence. The in-repo `nvd_scan` job drops its hand-rolled `initial-delay-fn`
durability workaround (`.G5`) as the reference proof. See the proposal for why
this matters; scope boundaries are its non-goals (`.NG1`–`.NG6`).

## PLAN-cron-on-scheduler-001.P2 Approach

- **PLAN-cron-on-scheduler-001.A1 — cron as a scheduler consumer.** `register!`
  validates and stores the job's in-memory config (`:id`, `:interval-ms`,
  `:jitter-ms`, `:run!` symbol) in cron's runtime state, then persists a durable
  wake through `skein.api.scheduler.alpha/schedule!` keyed `cron/<id>`, with a
  single fixed cron fire-handler symbol (e.g. `skein.spools.cron/fire-wake`) and
  payload `{:job "<id>"}`. Wake-at is `now + interval + jitter`. `deregister!`
  becomes `skein.api.scheduler.alpha/cancel!` of `cron/<id>` (tolerating a
  missing key — see `.R1`) plus dropping the in-memory job entry. Cron owns no
  timer, no `:future`, and no `:next-fire-at` state: the durable wake is the sole
  authority for when a job next fires, and scheduler introspection is the single
  timing view (`PROP-cron-on-scheduler-001.G3`).

- **PLAN-cron-on-scheduler-001.A2 — the tiny wake handler on the event lane.**
  `fire-wake` runs on the weaver's shared serialized lane via the scheduler's
  `run-fire!` and must stay minimal so it never holds the lane on job work: (1)
  decode `{:job id}`; (2) look up the in-memory job — absent means deregistered,
  so return without rescheduling; (3) compute the next fire instant
  `now + interval + jitter` and `schedule!` the next `cron/<id>` wake (replace
  semantics) **before** offloading, so the cadence is persisted even if the
  offload fails; (4) submit the job's resolved `:run!` to the cron-owned
  execution executor, recording an offload-rejection loudly cron-side without
  throwing; (5) return, which lets the scheduler `complete-wake!` the delivery.
  This realizes the wake-delivery vs job-completion split
  (`PROP-cron-on-scheduler-001.S5`): a wake is delivered and the next fire
  persisted the moment the run is handed off; the run's own success/failure is
  recorded cron-side and never interrupts cadence. **Prerequisite:** this
  self-reschedule (a same-key replacement wake armed from inside the handler,
  step 3) only survives once the primitive retires the *delivered* generation
  rather than the key alone — the generation-aware-retirement fix in `.PH0`.
  Against the as-shipped retire-by-key primitive, `complete-wake!`/`fail-wake!`
  would delete the freshly armed replacement the instant `fire-wake` returns and
  cadence would die after one fire, so `.PH0` lands before this handler is
  built.

- **PLAN-cron-on-scheduler-001.A3 — execution executor, not a timing executor.**
  Cron's `ScheduledThreadPoolExecutor` (timing) becomes a plain, execution-only
  `ExecutorService` that runs offloaded `:run!` bodies. The executor task records
  the outcome (`:last-outcome`/`:last-fired-at` on success, `failures` +
  `:last-error` on throw, exactly as today) and decrements the in-flight latch
  (`.A5`) in a `finally`. It never reschedules — rescheduling already happened on
  the event lane (`.A2`).

- **PLAN-cron-on-scheduler-001.A4 — Q2 config-equality (preserve vs replace).**
  Re-registration preserves the pending wake (and its remaining countdown) when
  the cadence-defining config is unchanged, and replaces it (resetting the
  countdown to `now + interval + jitter`) otherwise. The equality tuple is
  `[interval-ms jitter-ms run!-symbol]` (`jitter-ms` defaulting to 0);
  `initial-delay-fn` is removed (`.A6`) and is not part of it. Decision table on
  `register!` for `cron/<id>`:
  - A pending wake exists **and** an in-memory job config exists **and** its
    tuple differs → replace (`schedule!` a fresh wake; cadence genuinely
    redefined).
  - A pending wake exists **and** (no in-memory config — a fresh JVM restart — or
    an equal tuple) → preserve: leave the wake armed, (re)install the in-memory
    config only.
  - No pending wake exists → `schedule!` a fresh wake (first-ever arm).

  Rationale: the durable wake encodes *when* the job next fires; interval/jitter/
  run! encode *what and how often*. The common reload path re-runs the identical
  `register!` call (nvd_scan does this every reload), so preserving the wake on an
  unchanged tuple is what delivers `PROP-cron-on-scheduler-001.G1` — the countdown
  is not reset by routine reload. A restart finds the durable wake with an empty
  in-memory table and adopts it (config is deterministic across restarts). A
  genuine cadence/handler change restarts the countdown so the new config takes
  effect promptly. A `:run!` symbol that is unchanged but points at redefined
  code is preserved by design: `fire-wake` resolves the symbol at fire time, so
  cadence survives while the code updates.

- **PLAN-cron-on-scheduler-001.A5 — deterministic join for offloaded jobs
  (`cron/await-idle!`).** Because job bodies run off the event lane on the
  execution executor, `skein.api.events.alpha/await-quiescent!` returns before a
  job finishes; tests need a join that never sleeps. Add a drain/quiescence seam
  `cron/await-idle!` (mirroring `await-quiescent!`): an in-flight latch (a counter
  plus a condition/monitor) is incremented **on the event lane inside
  `fire-wake`, before the executor submit**, and decremented in the executor
  task's `finally`; `await-idle!` blocks until the count reaches zero or a budget
  expires, throwing loudly on timeout (TEN-003). Incrementing on the lane before
  submit closes the race: once `advance!` → scheduler pump → `fire-wake` returns
  and the lane quiesces, any offloaded job is already counted, so the test
  sequence `advance!` → `events/await-quiescent!` → `cron/await-idle!` →
  assert-outcome is fully deterministic.

- **PLAN-cron-on-scheduler-001.A6 — deletions.** Remove cron's parallel timing
  substrate: `schedule-fire!`, `reschedule-delay-ms`'s timer use (the interval+
  jitter computation itself is reused to place the next wake), the `:future`/
  `:next-fire-at` in-memory timing fields, the clock pump machinery
  (`fire-due!`, `due?`, `register-pump!`, the `::pump` registration — the
  scheduler's own pump now drives manual-clock tests), and the `initial-delay-fn`
  path (`initial-delay-ms` and the `:initial-delay-fn` job key). In `nvd_scan`,
  remove `nvd-seed-delay-ms`, `nvd-seed-delay`, `most-recent-lock-created`, and
  the `:initial-delay-fn` wiring; keep the open-lock check (runtime coordination,
  not seeding). Delete the seed-delay test in `nvd_scan_test`.

- **PLAN-cron-on-scheduler-001.A7 — state-version bump.** Cron's spool-state
  shape changes (timing `ScheduledThreadPoolExecutor` → execution `ExecutorService`;
  add the in-flight latch; drop `:next-fire-at`/`:future` from job entries), so
  bump `state-version` 1 → 2 and update the `state-shape-matches-declared-version`
  key set. Nothing in cron's in-memory state needs to survive reload: durable
  cadence lives in the scheduler wake (SQLite), and the in-memory job table is
  repopulated by config re-running `register!` after each startup/reload (the same
  post-config resolution window the scheduler relies on, SPEC-004.C100). The
  version bump means the first reload after upgrade closes the old timing
  executor and reinits under SPEC-004.C95 semantics.

## PLAN-cron-on-scheduler-001.P3 Affected areas

| ID                             | Area                              | Expected change                                                                 |
| ------------------------------ | --------------------------------- | ------------------------------------------------------------------------------- |
| PLAN-cron-on-scheduler-001.AA1 | `spools/cron/src`                 | Rewrite `cron.clj` over `skein.api.scheduler.alpha`; execution-only executor; `fire-wake`; `await-idle!`; Q2 logic; delete timing/pump/seed machinery; state-version bump. |
| PLAN-cron-on-scheduler-001.AA2 | `spools/cron/README.md`           | At-least-once contract, wake-delivery/job-completion split (S5), duplicate-tolerance guidance for job authors, scheduler as the single timing view. |
| PLAN-cron-on-scheduler-001.AA3 | `spools/cron.cookbook.md`         | Update worked examples to registration-over-wakes; drop `initial-delay-fn` recipe; show `await-idle!` in tests. |
| PLAN-cron-on-scheduler-001.AA4 | `spools/cron.api.md`              | Regenerated via `make api-docs` after docstrings settle; never hand-edited.     |
| PLAN-cron-on-scheduler-001.AA5 | `test/skein/cron_test.clj`        | Rewrite for the wake-backed model (see `.PH2`).                                  |
| PLAN-cron-on-scheduler-001.AA6 | `test/skein` (new e2e ns)         | Restart-durability e2e + lane-hygiene test for a cron job.                       |
| PLAN-cron-on-scheduler-001.AA7 | `.skein/nvd_scan.clj`             | Drop seed machinery; register without `:initial-delay-fn`.                       |
| PLAN-cron-on-scheduler-001.AA8 | `test/skein/nvd_scan_test.clj`    | Delete the seed-delay test; keep lock-flow coverage.                             |
| PLAN-cron-on-scheduler-001.AA9 | `src/skein/core/db.clj`           | Generation-aware retirement (`.PH0`): `retire-wake!` retires by key + delivered `wake_at` and records history from the passed delivered row; `cancel-wake!` stays key-based. |
| PLAN-cron-on-scheduler-001.AA10 | `src/skein/core/weaver/scheduler.clj` | `run-fire!` passes the delivered generation (envelope `wake-at-millis`) and the re-read delivered row into `complete-wake!`/`fail-wake!` (`.PH0`). |
| PLAN-cron-on-scheduler-001.AA11 | scheduler test suites | Focused primitive tests (`.PH0`): handler self-reschedule (same key) — replacement survives completion and the fire is recorded in history; failure path the same; a superseded/vanished generation retires nothing but still records history. |

## PLAN-cron-on-scheduler-001.P4 Contract and migration impact

- **PLAN-cron-on-scheduler-001.CM1:** One root spec change — cron itself is
  userland (`SPEC-005.C4`), but building it on the scheduler exposed a latent
  primitive defect, so this feature stages a single narrow `SPEC-004` delta:
  generation-aware wake retirement (`DELTA-cron-on-scheduler-runtime-001` in
  [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) — amend
  `SPEC-004.C102`, add `SPEC-004.C102b`), promoted at finish (`.PH0`, `.V6`).
  The scheduler's wake model, storage, and API surface are otherwise consumed
  unchanged (`PROP-cron-on-scheduler-001.NG1`); rationale recorded in
  [specs/README.md](./specs/README.md). The durable contract change is otherwise in
  the cron spool docs: cadence is now durable across restart/reload, delivery is
  at-least-once (handlers must be idempotent), and `:initial-delay-fn` is removed
  from `register!`. No SQLite migration — cron adds no tables and reuses the
  existing scheduler wake tables; the only downstream code change is `nvd_scan`
  dropping its seed function.

## PLAN-cron-on-scheduler-001.P5 Implementation phases

### PLAN-cron-on-scheduler-001.PH0 Generation-aware wake retirement (primitive fix)

Outcome: the scheduler primitive retires the *delivered* generation, not the key,
so a `SPEC-004.C101`-blessed handler that schedules its own next same-key wake
keeps that replacement. In `src/skein/core/db.clj`, `retire-wake!` (and its
`complete-wake!`/`fail-wake!` callers) retire by key **and** the delivered wake
instant and record history from a passed-in delivered row rather than a fresh
key lookup; a vanished or superseded generation retires no pending row but still
records the delivered fire in history; `cancel-wake!` stays key-based. In
`src/skein/core/weaver/scheduler.clj`, `run-fire!` passes the delivered
generation (the envelope's `wake-at-millis`) and the row it already re-read
pre-invoke into `complete-wake!`/`fail-wake!`. Focused scheduler-test additions:
a handler that schedules a same-key replacement — the replacement survives
completion and the fire is recorded in history; the failure path proves the same
under a throwing handler; a superseded/vanished generation at retirement time
retires nothing yet still records the delivered fire. This is the retirement
half of the `SPEC-004.C102` generation discipline and is staged as
`DELTA-cron-on-scheduler-runtime-001` (amend `SPEC-004.C102`, add `.C102b`).
Diffs to the primitive are limited to the retirement mechanic and its tests.
Gate: `clojure -M:test` over the scheduler suites (`skein.core.scheduler-test`,
`skein.scheduler-runtime-test`, `skein.api.scheduler.alpha-test`,
`skein.scheduler-e2e-test`) green.

### PLAN-cron-on-scheduler-001.PH1 Cron rides the scheduler

Outcome: `cron.clj` registers/deregisters jobs as durable `cron/<id>` wakes with
a tiny event-lane `fire-wake` handler, an execution-only executor, the Q2
preserve/replace decision, the `await-idle!` join seam, and the state-version
bump; all cron timing, clock-pump, and `initial-delay-fn` machinery is deleted.
Gate: `clojure -M:test skein.cron-test` green with the rewritten suite.

### PLAN-cron-on-scheduler-001.PH2 Test rewrite and durability/lane proofs

Outcome: `cron_test.clj` rewritten to the wake-backed model — wake persisted on
register; `advance!` + `await-quiescent!` + `await-idle!` releases a fire and
records the outcome; the next `cron/<id>` wake is persisted after a fire (seeded
RNG for jitter bounds); a `:run!` throw lands in `failures` while the wake
completes and cadence continues; Q2 same-config-preserve vs changed-config-replace;
state-version drift alarm. Plus a new e2e ns: restart-durability for a cron job
(mirror `scheduler_e2e_test`, schedule on one weaver, stop, adopt an overdue
durable wake in a fresh weaver, job fires and reschedules) and a lane-hygiene
test (a latched job must not hold the event lane — `await-quiescent!` returns
while the job is still blocked, and a second event still dispatches).
Gate: `clojure -M:test skein.cron-test <cron-e2e-ns>`.

### PLAN-cron-on-scheduler-001.PH3 nvd_scan migration

Outcome: `nvd_scan.clj` registers `:nvd-scan` with no `:initial-delay-fn`, seed
machinery deleted, open-lock coordination retained; `nvd_scan_test.clj` drops the
seed-delay test and keeps the lock-flow coverage. Gate:
`clojure -M:test skein.nvd-scan-test`.

### PLAN-cron-on-scheduler-001.PH4 Docs and API regen

Outcome: `spools/cron/README.md` and `spools/cron.cookbook.md` document the
at-least-once contract, the S5 wake/job split, duplicate-tolerance guidance, and
scheduler as the single timing view; `spools/cron.api.md` regenerated with
`make api-docs`. Gate: `make docs-check` clean and `git status --short` shows the
regenerated api-doc as intended, nothing else generated.

### PLAN-cron-on-scheduler-001.PH5 Acceptance

Outcome: full locked suite, Go tests, and smoke green; the scheduler suites are
green, with primitive diffs limited to the PH0 retirement mechanic and its
focused tests (`V6`). Gate:
`flock -w 3600 /tmp/skein-test.lock clojure -M:test`, `(cd cli && go test ./...)`,
`clojure -M:smoke`, `make fmt-check lint reflect-check docs-check`, with a clean
intended `git status --short`.

## PLAN-cron-on-scheduler-001.P6 Validation strategy

- **PLAN-cron-on-scheduler-001.V1 — durability.** A cron job's cadence survives a
  real weaver stop/start: the restart-durability e2e proves a durable `cron/<id>`
  wake fires and reschedules in a fresh weaver on the same world (`G1`).
- **PLAN-cron-on-scheduler-001.V2 — lane hygiene.** A latched/blocking `:run!`
  must not hold the shared event lane: `await-quiescent!` returns while the job is
  still blocked (job offloaded), and a subsequent event still dispatches. This is
  the load-bearing proof that `fire-wake` stays tiny (`.A2`).
- **PLAN-cron-on-scheduler-001.V3 — deterministic join, no sleeps.** Every job
  outcome assertion joins via `cron/await-idle!`; no `Thread/sleep` and no wall
  waits. Manual-clock fires are released by the scheduler's clock pump under
  `advance!` (cron registers no pump of its own).
- **PLAN-cron-on-scheduler-001.V4 — cadence continuity under failure.** A `:run!`
  throw is recorded in `failures` with `:last-error`, the wake still completes, and
  the next wake is armed (`S5`).
- **PLAN-cron-on-scheduler-001.V5 — Q2 semantics.** Same-config re-register leaves
  the pending wake's `wake-at` unchanged; a changed interval/jitter/run! resets it.
- **PLAN-cron-on-scheduler-001.V6 — scheduler diffs bounded to the retirement
  fix.** The scheduler suites (`skein.core.scheduler-test`,
  `skein.scheduler-runtime-test`, `skein.api.scheduler.alpha-test`,
  `skein.scheduler-e2e-test`) run green, and the only source diffs to the
  primitive are the generation-aware-retirement mechanic (`.PH0`, `AA9`/`AA10`)
  plus its focused tests (`AA11`) — no other reshaping (`NG1`). The staged
  `SPEC-004` delta (`DELTA-cron-on-scheduler-runtime-001`) is promoted into the
  root spec at finish.
- **PLAN-cron-on-scheduler-001.V7 — acceptance gate.** Full locked suite, Go tests,
  smoke, and quality gates green with a clean `git status --short` (only the
  intended regenerated api-doc).

## PLAN-cron-on-scheduler-001.P7 Risks and open questions

- **PLAN-cron-on-scheduler-001.R1 — cancel! on a missing key throws.**
  `skein.api.scheduler.alpha/cancel!` fails loudly on an unknown key, but cron's
  `deregister!` must stay tolerant (`{:deregistered nil}` for an unknown job) and
  re-registration must not double-cancel. Mitigation: guard the cancel behind a
  `pending` check for `cron/<id>` (or catch the missing-key failure narrowly),
  keeping the loud-failure contract for genuine scheduler errors.
- **PLAN-cron-on-scheduler-001.R2 — duplicate delivery shifts the next fire.**
  At-least-once means `fire-wake` can run twice; the second run replaces the
  just-scheduled next wake (resetting `wake-at` to a fresh `now+interval+jitter`)
  and offloads `:run!` again. This is within the at-least-once/idempotent contract
  (`NG5`); document it as duplicate-tolerance guidance rather than engineering
  exactly-once. Generation-aware retirement (`.PH0`) keeps this well-behaved: each
  duplicate delivery retires only its own delivered generation, so a duplicate that
  fires after the replacement is already armed completes its own generation without
  clobbering the pending replacement.
- **PLAN-cron-on-scheduler-001.R3 — executor rejection during offload.** If the
  execution executor rejects a submit (shutdown/reload race), cadence must still
  continue. Mitigation: schedule the next wake before submit (`.A2`), and record a
  loud offload failure cron-side without throwing into the lane.
- **PLAN-cron-on-scheduler-001.Q1 — await-idle! precise semantics.** Whether
  `await-idle!` waits only on in-flight execution-executor tasks or also on queued
  submits is an implementation detail; it must be strong enough that a test
  observes a completed outcome deterministically. Settle during `.PH1`; the
  in-flight latch counting from the event lane (`.A5`) is the intended mechanism.

## PLAN-cron-on-scheduler-001.P8 Task context

- **PLAN-cron-on-scheduler-001.TC1:** The central constraint is that
  `skein.core.weaver.scheduler/run-fire!` invokes handlers **on the shared
  serialized event lane**. `fire-wake` therefore does the minimum (lookup,
  reschedule, offload) and never runs job bodies on the lane. Read
  `src/skein/core/weaver/scheduler.clj` and `src/skein/api/scheduler/alpha.clj`
  before touching cron.
- **PLAN-cron-on-scheduler-001.TC2:** `schedule!` uses replace semantics keyed by
  wake key and resets the attempt count; `cancel!` throws on a missing key. Cron
  keys every wake `cron/<id>` and owns exactly one wake per registered job.
- **PLAN-cron-on-scheduler-001.TC3:** Reference patterns — restart durability:
  `test/skein/scheduler_e2e_test.clj`; drain/quiescence join:
  `test/skein/events_quiescence_test.clj` (`events/await-quiescent!`); house plan
  style: `devflow/archive/26-07-05__weaver-scheduler/weaver-scheduler.plan.md`.
- **PLAN-cron-on-scheduler-001.TC4:** Versioned spool-state rules:
  `docs/writing-shared-spools.md` ("Versioned spool state") and SPEC-004.C95 — bump
  the version whenever `new-state`'s key set changes, or a post-upgrade reload
  reuses a stale map and schedules against a nil resource.
- **PLAN-cron-on-scheduler-001.TC5:** The only permitted primitive change is the
  PH0 generation-aware retirement fix scoped by `NG1` and the staged delta; do
  not otherwise touch the scheduler primitive, add cron-syntax/calendar
  expressions (`NG2`), push jitter into the primitive (`NG3`), add workflow
  timer gates (`NG4`), attempt exactly-once (`NG5`), or add a public mutating
  cron CLI verb (`NG6`). Registration stays trusted config/REPL.

## PLAN-cron-on-scheduler-001.P9 Developer Notes

### PLAN-cron-on-scheduler-001.DN1 Task 9cr8i: plan + spec decision — 2026-07-10

- Spec decision: no root deltas; cron is userland (`SPEC-005.C4`), scheduler
  consumed unchanged (`NG1`). Recorded in `specs/README.md`, with a finish-time
  cleanup flagged for the stale cron mention in `SPEC-004.C1a`.
- Q2 answered in `.A4`: config-equality tuple is `[interval-ms jitter-ms run!]`;
  a pending wake is preserved on an unchanged tuple (or a fresh restart with no
  in-memory config), and replaced on any change; `:initial-delay-fn` removed.
- Deterministic join chosen in `.A5`: `cron/await-idle!` over an execution-executor
  in-flight latch, counted on the event lane inside `fire-wake` before submit.

### PLAN-cron-on-scheduler-001.DN2 Task x559f: generation-aware retirement folded in — 2026-07-10

- Coordinator-verified finding: `.A2`'s in-handler self-reschedule dies against
  the as-shipped primitive. `db/retire-wake!` (`src/skein/core/db.clj` ~1510)
  DELETEs `WHERE key = ?` and re-reads the pending row by key, and
  `scheduler/run-fire!` calls `complete-wake!`/`fail-wake!` post-handler — so the
  handler-persisted same-key replacement is deleted and recorded completed the
  moment the handler returns. Cadence dies after one fire; `fail-wake!` shares it.
  `SPEC-004.C101` blesses the self-reschedule and `SPEC-004.C102` already made the
  delivery-attempt increment generation-specific; retirement was simply never
  given the same discipline.
- Delta chosen: amend `SPEC-004.C102` (name retirement as the second
  generation-sensitive transition) and add a new clause **`SPEC-004.C102b`** —
  retirement retires exactly the delivered generation (key + delivered wake
  instant), records history from the delivered row, leaves a same-key replacement
  untouched, records history even for a vanished/superseded generation, and keeps
  cancel-by-key key-based. Staged as `DELTA-cron-on-scheduler-runtime-001` in
  `specs/daemon-runtime.delta.md`; `specs/README.md` reversed from "no deltas" to
  one delta.
- Plan revision: primitive fix sequenced as `.PH0` (before `.PH1`, keeping
  `PH1`–`PH5` ids stable), `.A2` marks it a prerequisite, `AA9`–`AA11` added,
  `CM1` and `V6` updated, `R2` notes the duplicate-delivery interaction.
  `NG1`/`.P1` in the proposal carry the narrow-exception framing.
