# Deterministic Test Time and Async Quiescence

**Document ID:** `RFC-Dtt-001`
**Status:** Open
**Date:** 2026-07-09
**Related:** [Test Concurrency RFC-016](./2026-07-03-test-concurrency.md) (serial islands this RFC pays down), [Weaver Scheduler RFC-009](./2026-06-29-weaver-scheduler.md), [Weaver Runtime spec](../specs/daemon-runtime.md), [Alpha Surface spec](../specs/alpha-surface.md), code: `src/skein/core/weaver/runtime.clj`, `src/skein/core/weaver/scheduler.clj`, `src/skein/api/events/alpha.clj`, `src/skein/api/runtime/alpha.clj`, `src/skein/test/alpha.clj`. Tenets: TEN-003 (FAIL LOUDLY), TEN-004 (Less is More).

## RFC-Dtt-001.P1 Problem

RFC-016 split the suite into a parallel batch, subprocess add-libs shards, and a serial island run by the parent. After the with-redefs DI pass (`1720a00`), the serial island still holds ~55.7s of wall time (measured 2026-07-09, note `qtdqa` on card `tkmvw`), of which ~38–40s is spent waiting on **real wall-clock time and real async delivery**, not on JVM-global hazards:

- **Real executor timers.** The scheduler subsystem arms a real `ScheduledExecutorService` (`src/skein/core/weaver/scheduler.clj:107`) and, for the not-already-deterministic suites, tests schedule a wake a fixed delay in the future and wait for it: `test/skein/scheduler_e2e_test.clj:47` schedules `(.plusMillis (Instant/now) 100)`, `test/skein/scheduler_runtime_test.clj:327` uses `(.plusMillis (Instant/now) 250)`. Cron arms the same kind of real timer with a wall-clock fire instant (`spools/cron/src/skein/spools/cron.clj:123-126`).
- **Hard sleeps polling async gate outcomes.** Treadle delivers gate outcomes through the runtime event worker (`spools/shuttle/src/skein/spools/treadle.clj:262` registers the `on-event` handler defined at `:228`), and its suite carries 34 wait-constructs including bare sleeps (`test/skein/treadle_test.clj:100,120,367`). Reed runs `:shell` gates on a real off-thread worker pool (`spools/src/skein/spools/reed.clj:2-4,67,186`) and its suite carries 13 wait-constructs. Chime runs its notifier command in async worker threads (`spools/chime/src/skein/spools/chime.clj:52,111`) and sleeps between polls (`test/skein/chime_test.clj:191,211,231`).
- **Event-bus delivery-order assertions under load.** `weaver-test` asserts the exact captured event vector; RFC-016.P7.3 documents that it registers its capture handler *after* creating fixture strands, so a queued fixture `:strand/added` event can leak into the captured stream under parallel load.

The single event worker is a serialized `poll(100ms)` loop over an `ArrayBlockingQueue` (`src/skein/core/weaver/runtime.clj:39,55-96`) with **no drain or quiescence hook** — `skein.api.events.alpha` exposes only `register!`/`enqueue!`/`handlers`/`recent-failures` (`src/skein/api/events/alpha.clj:50-85`), so a test cannot ask "is the lane idle?" and must sleep-and-poll instead. There is likewise no runtime-wide time source: subsystems read `(Instant/now)` directly (`scheduler.clj:111`, `cron.clj:124`, `chime.clj:52`, `reed.clj:90`).

The decision to make now: introduce two control seams — an injectable time source and an event-lane quiescence hook — chosen at the right API tier and with the right boundary against the durable scheduler primitive, so these suites become deterministic and graduate to the parallel batch. The seams touch long-lived architecture (the runtime map, the blessed alpha surface) and have several plausible placements; that is why this is an RFC and not a plan.

## RFC-Dtt-001.P2 Goals

- **RFC-Dtt-001.G1:** A runtime-scoped time source that timer-driven code reads instead of `(Instant/now)`, injectable so tests advance time deterministically.
- **RFC-Dtt-001.G2:** An event-lane quiescence primitive that blocks until asynchronous delivery has settled (or fails loudly on timeout), replacing sleep-and-poll for event-delivered outcomes.
- **RFC-Dtt-001.G3:** The added surface is minimal and each exposed alpha function is justified as a permanent contract (TEN-004).
- **RFC-Dtt-001.G4:** The timer-driven and event-delivered serial suites (`scheduler-*`, `cron`, `treadle`, `reed`, `weaver`, and the deterministic parts of `chime`) graduate to `parallel-namespaces`, shrinking the serial island toward ~15s (bench + singleton-semantics only) and eliminating the timing-budget flakes RFC-016.P7 catalogues.
- **RFC-Dtt-001.G5:** Default (production) behaviour is unchanged: a real weaver reads a real wall clock and its executors fire on real time.

## RFC-Dtt-001.P3 Non-goals

- **RFC-Dtt-001.NG1:** No change to what any test *covers* — same assertions, same subsystems exercised; only how they wait changes.
- **RFC-Dtt-001.NG2:** No change to the durable scheduler's persistence or at-least-once semantics (RFC-009); the wake-at contract is unchanged. See the boundary in RFC-Dtt-001.REC5.
- **RFC-Dtt-001.NG3:** Bench's container-engine subprocess tests stay real and serial — no seam removes a real container runtime (`test/skein/test_runner.clj:55-58`).
- **RFC-Dtt-001.NG4:** No CI or `-M:smoke` changes, and no change to the add-libs shard model or the singleton-semantics serial members (`repl-test`, `weaver-publication-test`, `peers-test`, `userland-test`).

## RFC-Dtt-001.P4 Options

### Decision A — time seam placement

| ID | Option | Pros | Cons |
| -- | ------ | ---- | ---- |
| RFC-Dtt-001.A1 | **Runtime-owned clock component**: a `:clock` slot on the runtime map (default `#(Instant/now)`), read by every timer-driven subsystem; tests install a manual clock and `advance!` it. Generalizes the seam scheduler state already carries (`scheduler.clj:111`, `set-clock!` at `scheduler.clj:127`). | Isolated per runtime, so parallel runtimes never race on it (unlike JVM-global redefs); one obvious place to read time; matches the proven scheduler precedent; no production behaviour change. | Needs each subsystem's due-detection to consult the clock, and real `ScheduledExecutorService` timers (cron/chime) must be made to release due work on `advance!` rather than wall delay (see A3 mechanism). |
| RFC-Dtt-001.A2 | **Test-only `with-redefs`/redef seam** on `Instant/now` call sites. | No production code change. | JVM-global — the exact hazard RFC-016 spent effort removing (`1720a00` deleted the last with-redefs seams to graduate three suites). Reintroducing it blocks parallelism and violates the runtime-isolation invariant. |
| RFC-Dtt-001.A3 | **Virtual-time scheduled-executor wrapper**: replace each subsystem's `ScheduledExecutorService` with a manual executor whose due queue is released by `advance!`. | Directly handles real-timer subsystems (cron/chime) that a read-only clock cannot advance. | A heavier abstraction than a clock read; on its own it does not give ad-hoc `(Instant/now)` reads (reed/chime timestamps) a deterministic value. |

### Decision B — API tier split

| ID | Option | Pros | Cons |
| -- | ------ | ---- | ---- |
| RFC-Dtt-001.B1 | **Minimal accretion**: one read accessor on `skein.api.runtime.alpha`; manual-clock controls (`set-clock!`, `advance!`) in `skein.test.alpha`. | Smallest permanent surface (TEN-004); `runtime.alpha` already owns runtime-scoped accessors (`spool-state`, `uses`); test-only controls stay in the author-side test namespace, not the forever-blessed production tier. | The read accessor and the control surface live in two namespaces; a reader must know time-reads are `runtime.alpha` and time-controls are `test.alpha`. |
| RFC-Dtt-001.B2 | **New `skein.api.time.alpha`** namespace owning both read and control. | One discoverable home for "time". | A whole subnamespace (accretion-compatible forever) for one read accessor is more surface than the capability warrants; control belongs in test tooling, not a production alpha tier. Rejected under TEN-004. |

### Decision C — quiescence seam

| ID | Option | Pros | Cons |
| -- | ------ | ---- | ---- |
| RFC-Dtt-001.C1 | **`skein.api.events.alpha` quiescence hook**: block until the event queue is empty and the worker is not mid-dispatch, throwing on timeout. | One seam covers every event-delivered subsystem (treadle, weaver fanout, scheduler fire lane) since they share the one worker (`runtime.clj:55-96`); a genuine runtime capability (graceful shutdown / land gates can want it too), not test-only; fails loudly per TEN-003. | Covers the shared event lane only; spool-owned worker pools (reed's shell workers, chime's notifier) need their own settle signal layered on top. |
| RFC-Dtt-001.C2 | **Per-spool completion callbacks** each spool exposes. | Precise per-subsystem completion. | N seams instead of one; every spool reinvents the same await; more permanent surface. |
| RFC-Dtt-001.C3 | **Status-polling only** (status quo): keep `poll-until` on durable outcomes. | No new surface. | Keeps the sleep-and-poll cost and the load-sensitivity that produces RFC-016.P7 flakes; does not fix the weaver-test fixture-event race, which is an ordering not a duration problem. |

## RFC-Dtt-001.P5 Recommendation

- **RFC-Dtt-001.REC1 (Decision A → A1, with the A3 mechanism underneath):** A runtime-owned clock component. The runtime map gains a `:clock` slot defaulting to `#(Instant/now)`, and timer-driven subsystems read it instead of `(Instant/now)`. This generalizes the seam the scheduler already proves works (`scheduler.clj:80-82,127-134`; `scheduler_runtime_test.clj:62,169` already injects a deterministic clock). For subsystems that arm real `ScheduledExecutorService` timers (cron, chime, and the scheduler's own executor), `advance!` must deterministically release the now-due work — the concrete executor mechanism (a manual/virtual-time executor per RFC-Dtt-001.A3, versus a clock-driven due-check plus an explicit pump as the scheduler already models via `dispatch-due!`) is fixed as a plan-level choice, but the *contract* is fixed here: **one `advance!` call moves the runtime clock forward and runs every clock-consuming subsystem's now-due work synchronously before returning.** A2 is rejected outright — it is the JVM-global hazard RFC-016 removed.

- **RFC-Dtt-001.REC2 (Decision B → B1):** Accrete a single read accessor onto `skein.api.runtime.alpha`; put the manual-clock controls in `skein.test.alpha`, which already orchestrates disposable weaver worlds. B2's new namespace is rejected under TEN-004.

- **RFC-Dtt-001.REC3 (Decision C → C1):** Add a blocking quiescence primitive to `skein.api.events.alpha`. reed and chime, whose async work leaves the shared event lane for a private worker pool, keep an outcome-keyed `poll-until` for the real subprocess step layered on top of lane quiescence — the sleep-and-poll cost collapses because the *dispatch* is now settled deterministically and the subprocess is a short local command.

- **RFC-Dtt-001.REC4 (exposed surface, TEN-004 — each fn kept forever, justified):**
  - `skein.api.runtime.alpha/now` → `Instant`. The one blessed time source spools read instead of `(Instant/now)`. Permanent because deterministic time is a permanent testability contract and spools need a *runtime-scoped* clock, not a JVM-global one. Data-first return (an `Instant`, TEN-001), not the clock fn.
  - `skein.test.alpha/set-clock!` `[runtime clock-fn]` and `skein.test.alpha/advance!` `[runtime duration]`. The author-side control pair: install a manual clock, then move it forward and pump due work. Kept as the blessed deterministic-time test path. They take an explicit `runtime` so the in-process serial suites (which drive `skein.spools.test-support/with-runtime`, not the nREPL `repl!` path) can call them directly. `advance!` **fails loudly** on a non-positive or backwards duration (TEN-003) — a manual clock never runs backwards.
  - `skein.api.events.alpha/await-quiescent!` `[runtime]` / `[runtime opts]`. Blocks until the event queue is empty and the worker is idle; throws an ex-info on timeout (default budget from `skein.spools.test-support/await-budget-ms`, honoured via an explicit `:timeout-ms`). One blocking primitive; no bare `quiescent?` predicate is added, because the useful operation is the await and a predicate invites a caller-rolled sleep loop — exactly what this replaces.
  - No new accessor is added for the clock component internals; core subsystems read it through a core-tier accessor (`skein.core.weaver.runtime`), not the alpha tier.

- **RFC-Dtt-001.REC5 (Decision E — explicit boundary):** `skein.api.scheduler.alpha`'s durable `wake-at` becomes a **consumer** of the clock seam, not a second time authority. Today the scheduler owns a private `:clock` in its spool-state (`scheduler.clj:111`) and its own `set-clock!` (`scheduler.clj:127`); those collapse into reads of the runtime clock. Due-detection and arming (`scheduler.clj:80-82,224-249`) read `now` from the runtime clock; the durable rows, at-least-once delivery, and the persisted `wake_at` millis are unchanged (RFC-Dtt-001.NG2). The scheduler stays the *durable* wake primitive; the clock is merely where it learns the current instant.

### RFC-Dtt-001.REC6 — migration disposition (Decision D)

| Suite | Current wait mechanism (verified) | Seam applied | Outcome |
| ----- | --------------------------------- | ------------ | ------- |
| `scheduler-runtime-test` | already injects a deterministic clock (`scheduler_runtime_test.clj:62,169`); one real-timer remnant (`:327`) | runtime clock + `await-quiescent!` | **parallel** |
| `scheduler-e2e-test` | real wake delays off `Instant/now` (`scheduler_e2e_test.clj:47,70`) | runtime clock + `advance!` + `await-quiescent!` | **parallel** |
| `api.scheduler.alpha-test` | `Instant/now` far/near wakes (`api/scheduler/alpha_test.clj:49,64`) | runtime clock + `advance!` | **parallel** |
| `cron-test` | real executor timers, `Instant/now` fire (`cron.clj:123-126`) | runtime clock drives due-ness; `advance!` fires | **parallel** |
| `treadle-test` | 34 wait-constructs, event-delivered outcomes (`treadle_test.clj:100,120,367`) | `await-quiescent!` replaces sleeps | **parallel** |
| `reed-test` | 13 wait-constructs, real `:shell` subprocesses (`reed.clj:67,186`) | `await-quiescent!` for dispatch + outcome-keyed `poll-until` | **parallel** |
| `weaver-test` | event-order assertions, fixture-event race (RFC-016.P7.3) | register capture handler before fixtures + `await-quiescent!` | **parallel** |
| `chime-test` | rule/notifier scheduling + real notifier subprocess (`chime.clj:52,111`; sleeps `chime_test.clj:191,211,231`) | runtime clock for rule timing; notifier-worker settle + `await-quiescent!` replaces the fixed sleeps; real notifier subprocess kept | **parallel** (real notifier subprocess retained) |
| `bench-test` | real container-engine subprocesses (`test_runner.clj:55-58`) | none — no seam removes a container runtime | **stays serial** (RFC-Dtt-001.NG3) |
| `repl-test`, `weaver-publication-test`, `peers-test`, `userland-test` | ambient-connection / published-singleton semantics (`test_runner.clj:25-31`) | none — not a timing concern | **stay serial** |

## RFC-Dtt-001.P6 Consequences

- **RFC-Dtt-001.C-1 (spec):** The `daemon-runtime` spec gains the runtime `:clock` component and its default; the `alpha-surface` spec records `runtime.alpha/now` and `events.alpha/await-quiescent!` as in-contract and the `test.alpha` controls as author-side. `skein.api.events.alpha` and `skein.api.runtime.alpha` docstrings are updated.
- **RFC-Dtt-001.C-2 (scheduler):** The scheduler's private `:clock` and `set-clock!` are removed in favour of the runtime clock; `scheduler_runtime_test`'s existing clock injection is rehosted onto `test.alpha/set-clock!`. Watch the versioned scheduler spool-state key set (`scheduler.clj:57-64`; `test-support/assert-state-shape`) — dropping `:clock` is a state-shape change and must bump `scheduler-state-version`.
- **RFC-Dtt-001.C-3 (spools):** cron/chime/reed replace `(Instant/now)` reads with `runtime.alpha/now`; cron's executor arming is restructured so due-ness derives from the runtime clock. These are the reference-spool timing paths; their contract docs note the clock dependency.
- **RFC-Dtt-001.C-4 (test runner):** the eight suites above move from `serial-namespaces` to `parallel-namespaces` in `test/skein/test_runner.clj:11-58`, with the serial comments trimmed to the surviving members (bench + singleton-semantics). Coordinate with any in-flight edit to the same island vectors.
- **RFC-Dtt-001.C-5 (flakes):** landing this closes RFC-016.P7.1 (chime body-line race), P7.2 (shuttle reap liveness, insofar as it shares the settle model), and P7.3 (weaver fixture-event leak) by construction rather than by widening budgets.
- **RFC-Dtt-001.C-6 (measured target):** serial island ~55.7s → ~15s; full-suite critical path drops toward the shard-A floor (~45s, note `qtdqa`). The gain is wall-clock and flake elimination, not new coverage.

## RFC-Dtt-001.P7 Outcome

- **RFC-Dtt-001.OUT1:** Decision pending owner sign-off. Recommendation carried into `PROP-Dtt-001` (feature `deterministic-test-time`): A1 (runtime-owned clock component), B1 (minimal accretion onto `runtime.alpha` + controls in `test.alpha`), C1 (`events.alpha` quiescence), with the scheduler durable wake-at as a clock consumer (REC5). On acceptance, update `daemon-runtime` and `alpha-surface` specs and continue to the feature plan.
