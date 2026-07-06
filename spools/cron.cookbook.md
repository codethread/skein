# Skein Cron Spool — Cookbook

Composition recipes for `skein.spools.cron`: how to put spool-owned work on a
timer, and *why* each shape is the right one.

This is the **how/why** half of the cron docs. The other two halves are:

- [`cron/README.md`](./cron/README.md) — the **contract**: the job shape, the
  interval+jitter cadence, seeding, and the last-outcome/failure guarantees. Read
  it for what the engine promises.
- [`cron.api.md`](./cron.api.md) — the **generated reference**: every public fn's
  signature, arity, and docstring, produced from source.

Division of truth: signatures and argument lists live in the generated API doc;
narrative and composition live here and in the contract. This cookbook never
restates a fn signature — it links to them. When a recipe needs an exact arity,
follow the link.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches
your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which pieces combine, and how.
3. **Snippet** — a complete, runnable form (assume
   `(require '[skein.spools.cron :as cron])`).
4. **Why this shape** — the reasoning: why cron is built this way, and what the
   alternative would cost.

Each recipe cites the honest source it was distilled from — this repo's own
`:nvd-scan` job, or the cron test suite — so you can read the load-bearing
version.

The one idea under all of it: **cron owns the timing, your job owns the work.**
The engine ships no jobs and spawns no processes. A caller registers a job by
fully-qualified `:run!` symbol; cron owns only the cadence, the status listing,
and a loud failure log. Everything below is about writing a good job and handing
it to that engine.

---

## Recipe: Register a job that fires on an interval

**Situation.** You have periodic work — a report, a cleanup, a scan — that should
run every so often for the lifetime of the weaver, and you want spread so many
weavers (or many jobs) don't all fire on the same tick.

**Composition.** One `register!` call taking `:interval-ms` (the base period),
`:jitter-ms` (each fire offset uniformly in ±jitter), and a `:run!` symbol
resolving to `(fn [runtime] ..)`. Register it from trusted startup config, after
cron's `install!` has created the executor.

```clojure
(ns my.jobs
  (:require [skein.spools.cron :as cron]
            [skein.api.current.alpha :as current]))

(defn emit-report
  "cron :run! — one tick of the periodic work. Its return value is recorded as
  the job's :last-outcome; a throw lands in failures and never stops the cadence."
  [runtime]
  (current/with-runtime runtime
    ;; ... do the periodic work ...
    {:outcome :reported}))

;; from startup config, once cron/install! has run:
(cron/register! (current/runtime)
  {:id          :nightly-report
   :interval-ms (* 24 60 60 1000)   ; base period between fires
   :jitter-ms   (* 60 60 1000)      ; each fire offset uniformly in [-1h, +1h]
   :run!        'my.jobs/emit-report})
```

**Why this shape.**

- **`:run!` is a symbol, not a function value.** Cron resolves it at fire time, so
  a job registered from a startup file stays valid across a reload that reloads
  the namespace — the timer keeps pointing at the current definition. A raw
  function value would pin the old one.
- **Jitter is a first-class knob because synchronised timers are a hazard.** Many
  weavers all firing the same expensive job on the same tick is a thundering herd;
  `:jitter-ms` spreads each fire uniformly across ±jitter so they scatter. For a
  single local job the jitter can be small or zero; for anything shared across
  machines it earns its keep (see the lock recipe below).
- **Re-registering the same `:id` is idempotent.** It cancels the existing job's
  pending fire first, so re-running startup config on reload replaces the job
  cleanly instead of stacking a second timer.

Honest source: the job shape in [`cron/README.md`](./cron/README.md) and
`register-lists-and-deregisters` / `fires-and-records-last-outcome` in
[`test/skein/cron_test.clj`](../test/skein/cron_test.clj), which register a job,
assert it fires, and read `:last-outcome` back off the status listing.

---

## Recipe: Seed the first fire from external state

**Situation.** The interval is right for the steady state, but the *first* fire
shouldn't be a full interval away — it should be computed from when the work last
actually happened, which lives outside the weaver (a file, a database row, a
GitHub issue's timestamp).

**Composition.** Add `:initial-delay-fn`, a fully-qualified symbol resolving to
`(fn [runtime] -> delay-ms)`. It runs once, at registration, to compute the delay
until the first fire. Reuse `cron/jitter-offset-ms` inside it so the seed shares
the engine's single jitter definition. Keep the arithmetic pure and inject the
external read, so the seed is unit-testable without I/O.

```clojure
(defn seed-delay-ms
  "Pure first-fire delay: ms from now until (last-run or now) + interval + jitter.
  A computed past instant floors to a near-immediate fire."
  [^java.time.Instant now ^java.time.Instant last-run ^java.util.Random rng]
  (let [base   (or last-run now)
        jitter (cron/jitter-offset-ms (* 60 60 1000) rng)
        target (-> base (.plusMillis (* 24 60 60 1000)) (.plusMillis jitter))]
    (max 0 (- (.toEpochMilli target) (.toEpochMilli now)))))

(defn seed-delay
  "cron :initial-delay-fn — reads the external last-run marker, then defers to the
  pure calculation above."
  [_runtime]
  (seed-delay-ms (java.time.Instant/now)
                 (read-last-run-from-somewhere)   ; the injected external read
                 (java.util.Random.)))

(cron/register! (current/runtime)
  {:id :nightly-report
   :interval-ms (* 24 60 60 1000)
   :jitter-ms   (* 60 60 1000)
   :run!  'my.jobs/emit-report
   :initial-delay-fn 'my.jobs/seed-delay})
```

**Why this shape.**

- **The seed decides *when to start*, the interval decides *how often*.** Without
  a seed the first fire is a whole interval out — wrong when a weaver restarts an
  hour after the last run and shouldn't wait a full cadence. `:initial-delay-fn`
  lets "resume where we left off" fall out of the external timestamp.
- **A bad seed degrades, it doesn't crash.** A throw or a non-delay result is
  recorded loudly in `failures` and the engine falls back to plain
  interval+jitter, so a flaky external read never takes down weaver startup.
- **Split pure math from the external read for testability.** Keeping the
  arithmetic in a pure `seed-delay-ms` (now, last-run, and an injected `Random`
  in; ms out) means the "past instant floors to immediate", "recent run defers
  the remaining time" cases test without any I/O — the `:initial-delay-fn` is a
  thin wrapper that only supplies the real clock and read.

Honest source: this repo's `nvd-seed-delay-ms` / `nvd-seed-delay` in
[`.skein/config.clj`](../.skein/config.clj), seeded from the most recent
`scan-lock running` GitHub issue's creation time; the pure calculation's cases
are pinned by `seed-delay-computes-first-fire` in
[`test/skein/nvd_scan_test.clj`](../test/skein/nvd_scan_test.clj).

---

## Recipe: Register the job from init.clj, not install!

**Situation.** Your job's first act is a real side effect — a `gh` call, a network
read to seed the schedule. But the spool's `install!` also runs under test, and
you do not want a config-loading unit test to fire that side effect against the
real world.

**Composition.** Split the two registrations. Cron's own `install!` (a module
`:call` in startup config) only creates the executor and registers no jobs. Put
your `register!` call in `init.clj` as an explicit top-level form, *not* inside
your config's `install!`. A test that loads the config namespace and calls
`install!` directly then never touches the timer.

```clojure
;; config.clj — the job's run!/seed fns live here beside the other repo policy,
;; but registration does NOT happen in install!:
(defn register-report-job! []
  (cron/register! (current/runtime)
    {:id :nightly-report
     :interval-ms (* 24 60 60 1000)
     :jitter-ms   (* 60 60 1000)
     :run!  'config/report-tick
     :initial-delay-fn 'config/report-seed}))

;; init.clj — startup wiring. Cron is synced (its install! builds the executor)
;; before config loads, and the job is registered here as a top-level form:
(runtime-alpha/use! runtime :skein/spools-cron
  {:ns 'skein.spools.cron :spools ['skein.spools/cron]
   :call 'skein.spools.cron/install! :required? true})
;; ... config module loads here ...
((requiring-resolve 'config/register-report-job!))
```

**Why this shape.**

- **`install!` is a test entry point; a startup file is not.** The config test
  loads `config.clj` and calls `install!` to check registration wiring. If the
  cron `register!` lived in `install!`, that test would fire the job's startup
  seed — a real `gh` call — against the live repo. Keeping registration in
  `init.clj` draws the line exactly where the test stops.
- **The `:run!`/seed fns still live in config, beside the rest of the policy.**
  Only the *registration call* moves to `init.clj`. The job's behaviour stays with
  the other repo conventions, so there is one place to read what the job does.
- **It stays idempotent on reload.** `init.clj` re-runs on reload, and
  re-registering the same `:id` cancels the prior pending fire, so the top-level
  registration form re-seeds and re-registers cleanly every time.

Honest source: this repo's `register-nvd-scan-job!` in
[`.skein/config.clj`](../.skein/config.clj), called as a top-level form in
[`.skein/init.clj`](../.skein/init.clj) with the explicit comment that it is kept
out of `config/install!` so `config_test` never triggers the startup `gh` seed.

---

## Recipe: Coordinate many weavers with a best-effort lock, and raise a card on findings

**Situation.** The same job runs on every maintainer's weaver, and the work is
expensive enough that you'd rather not have all of them do it at once — but a
double-run is harmless, so you want cheap coordination, not a real distributed
lock. When the job finds something, a human needs a durable to-do, not a log
line.

**Composition.** Use shared external state as a best-effort lock: check for an
open marker (a GitHub issue with a known title) at the top of the tick and skip
if another weaver holds it; otherwise create the marker, do the work, and release
it in a `finally`. Lean on jitter so the weavers rarely reach the check together.
On a finding, call a kanban `add!` to raise a card — before any further external
call, so a later failure can't drop the alert.

```clojure
(defn run-scan!
  "One tick with every side effect injected, so the flow is unit-testable.
  :run-cmd runs the external tool + lock calls; :raise-card! files a kanban card."
  [{:keys [run-cmd raise-card!]}]
  (cond
    (open-lock-held? run-cmd)                 ; another weaver is running right now
    {:outcome :skipped-locked}

    :else
    (let [lock (acquire-lock! run-cmd)]       ; create the marker issue
      (try
        (let [findings (do-the-work run-cmd)]
          (when (seq findings)
            ;; raise the card first: it is the alert of record, so a later
            ;; comment/close failure must not be able to drop it
            (raise-card! {:title "Scan: findings" :body (report findings)}))
          {:outcome :scanned :findings findings})
        (finally
          (release-lock! run-cmd lock))))))    ; always release, even on throw

(defn scan-tick [runtime]
  (run-scan!
    {:run-cmd  run-command
     :raise-card! (fn [card]
                    (current/with-runtime runtime
                      ((requiring-resolve 'skein.spools.kanban/add!)
                       (:title card) {"--body" (:body card) "--priority" "p1"})))}))
```

**Why this shape.**

- **Best-effort is the honest promise.** An open marker issue means "someone is
  scanning now"; jitter keeps two weavers from checking on the same tick. A
  genuine double-scan is harmless, so paying for a real distributed lock would be
  over-engineering. The design accepts the rare double-run and only prevents the
  common stampede.
- **Release in a `finally`, acquire before the work.** The lock is created before
  the expensive step and released whether it succeeds or throws, so a crashed scan
  can't leave a stale marker wedging every other weaver out forever.
- **The card is the alert of record, raised first.** Filing the kanban card before
  any follow-up external call (a comment, a close) means a `gh` hiccup afterward
  can't swallow the finding — the durable human to-do already exists on the local
  weaver's board. This is why the side effects are injected: `raise-card!` and
  `run-cmd` are seams, so the ordering and the skip/fail/raise branches all test
  without shelling out or touching GitHub.
- **The job fails loudly on a real error.** A missing API key or a `gh` error
  throws, which cron records in `failures` — coordination is best-effort, but a
  broken scan is never silently read as a clean one.

Honest source: this repo's `:nvd-scan` job — `run-nvd-scan!`, `nvd-scan-tick`,
the `scan-lock running` issue lock, and the p1-card raise — in
[`.skein/config.clj`](../.skein/config.clj); the skip-when-locked, fail-without-key,
and findings-raise-a-card branches are covered by `run-skips-when-open-lock-held`,
`run-fails-loudly-without-key`, and `run-findings-raise-p1-card-and-still-close`
in [`test/skein/nvd_scan_test.clj`](../test/skein/nvd_scan_test.clj).

---

## Recipe: See when a job last fired, and what failed

**Situation.** A scheduled job is quiet and you want to know whether it has fired,
what it last returned, and whether anything threw — without adding logging to the
job itself.

**Composition.** Read the two inspection fns. `(cron/jobs runtime)` lists every
registered job's status (id, interval, jitter, `:run!` symbol, `:next-fire-at`,
and once fired `:last-outcome`/`:last-fired-at`/`:last-error`).
`(cron/failures runtime)` returns the recorded seed and execution failures,
oldest first.

```clojure
(cron/jobs runtime)
;; => [{:id :nvd-scan :interval-ms 518400000 :jitter-ms 3600000
;;      :run! config/nvd-scan-tick :next-fire-at "..." :last-outcome {...}}]

(cron/failures runtime)
;; => [{:kind :run :job :nvd-scan :message "..." :at "..."}]

(cron/deregister! runtime :nvd-scan)   ; cancel the pending fire and remove it
```

**Why this shape.**

- **A throw is recorded, never fatal (TEN-003).** A job whose `:run!` throws stays
  scheduled and keeps its cadence; the failure lands in `failures` and the error
  string on the job's `:last-error`. So "the job is gone" and "the job is failing"
  are distinguishable: check `jobs` for presence and `next-fire-at`, `failures`
  for why the last run went wrong.
- **Status is a read, not a side effect.** `jobs` and `failures` project the
  runtime's own job table and failure log, so you can poll them from a REPL or a
  dashboard without perturbing the schedule.
- **State is runtime-owned.** Two runtimes in one JVM keep independent executors,
  job tables, and failure logs, so a test weaver's jobs never bleed into the real
  one's listing — pass the runtime you mean.

Honest source: the status and failure shapes in [`cron/README.md`](./cron/README.md)
and `records-run-failure-without-stopping-cadence` in
[`test/skein/cron_test.clj`](../test/skein/cron_test.clj), which fires a throwing
job and reads both the `failures` entry and the `:last-error` on its status.

---

## See also

- [`cron/README.md`](./cron/README.md) — the contract: job shape, cadence,
  seeding, status, and failure semantics.
- [`cron.api.md`](./cron.api.md) — generated signatures and docstrings for every
  fn referenced above.
- [`chime.cookbook.md`](./chime.cookbook.md) — the sibling engine's recipes; cron
  and chime share the local-root layout, runtime-owned state, and loud-failure
  discipline.
- [`workflow.cookbook.md`](./workflow.cookbook.md) — the cookbook template these
  recipes follow.
