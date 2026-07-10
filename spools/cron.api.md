
-----
# <a name="skein.spools.cron">skein.spools.cron</a>


Generic timer/scheduling engine for the Skein weaver.

  Cron registers named jobs that fire on a fixed interval with uniform jitter,
  each on a spool-owned scheduled executor created at activation and closed when
  the runtime stops (weaver-lifetime, like agent-run's supervision; a
  version-mismatch reload reinitialises it). It knows nothing about any
  particular job: a caller registers
  a job by fully-qualified `:run!` symbol resolving to a `(fn [runtime] ..)`,
  and the engine owns only the timing, the last-outcome/next-fire status
  listing, and a loud inspectable failure log. It is deliberately just timers —
  workflow/gate integration is intentionally out of scope.

  State is runtime-owned via `skein.api.runtime.alpha/spool-state`, so two
  runtimes in one JVM keep independent executors, job tables, and failure logs.
  A job execution that throws is recorded in `failures` and never stops the
  cadence (TEN-003).

  Due-ness reads the runtime clock (`skein.api.runtime.alpha/now`): in
  production that clock tracks the wall clock, so the real scheduled executor
  fires unchanged, but a runtime under a manual clock releases due jobs through
  a registered clock-pump instead of waiting on wall time
  (DELTA-Dtt-001.CC3).




## <a name="skein.spools.cron/deregister!">`deregister!`</a>
``` clojure
(deregister! runtime id)
```
Function.

Cancel a cron job's pending fire and remove it from `runtime`.

  Returns `{:deregistered id}` when the job existed, else `{:deregistered nil}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/cron/src/skein/spools/cron.clj#L119-L128">Source</a></sub></p>

## <a name="skein.spools.cron/failures">`failures`</a>
``` clojure
(failures runtime)
```
Function.

Return recorded cron failures (seed and execution) for this runtime's weaver
  lifetime, oldest first. Each entry carries `:kind` (`:run` or `:initial-delay`),
  `:job`, a `:message`, and `:at`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/cron/src/skein/spools/cron.clj#L81-L86">Source</a></sub></p>

## <a name="skein.spools.cron/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Activate cron on the current runtime, creating the scheduled executor.

  Registers no jobs — trusted config registers jobs with `register!`. Also
  (re)registers the clock-consumer pump so deterministic tests can drive due
  jobs off the runtime clock. Called as a no-arg module `:call` at
  startup/reload.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/cron/src/skein/spools/cron.clj#L273-L286">Source</a></sub></p>

## <a name="skein.spools.cron/jitter-offset-ms">`jitter-offset-ms`</a>
``` clojure
(jitter-offset-ms bound-ms rng)
```
Function.

Return a uniform jitter offset in the range [-bound-ms, bound-ms].

  `rng` is a `java.util.Random`; pass a seeded one for deterministic tests. A
  zero or negative bound yields 0. Exposed so a caller's `:initial-delay-fn` can
  reuse the engine's single jitter definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/cron/src/skein/spools/cron.clj#L88-L97">Source</a></sub></p>

## <a name="skein.spools.cron/jobs">`jobs`</a>
``` clojure
(jobs runtime)
```
Function.

Return the cron jobs registered on `runtime` as status maps, sorted by id.

  Each map carries `:id`, `:interval-ms`, `:jitter-ms`, the `:run!` symbol,
  `:next-fire-at`, and (once fired) `:last-outcome`/`:last-fired-at`/`:last-error`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/cron/src/skein/spools/cron.clj#L265-L271">Source</a></sub></p>

## <a name="skein.spools.cron/register!">`register!`</a>
``` clojure
(register! runtime job)
```
Function.

Register (or replace) a named cron job on `runtime`'s scheduled executor.

  `job` keys:
  - `:id` — keyword or non-blank string identifying the job. Re-registering the
    same id cancels the existing job's pending fire first.
  - `:interval-ms` — positive integer base period between fires.
  - `:jitter-ms` — non-negative integer; each fire is offset by a uniform value
    in [-jitter, +jitter]. Optional, default 0.
  - `:run!` — fully-qualified symbol resolving to `(fn [runtime] ..)`, invoked on
    every fire. Its return value is recorded as `:last-outcome`; a thrown
    exception is recorded in `failures` and does not stop the cadence.
  - `:initial-delay-fn` — optional fully-qualified symbol resolving to
    `(fn [runtime] -> delay-ms)` for the FIRST fire only (e.g. a seed derived
    from external state). A throw or non-delay result is recorded loudly and the
    engine falls back to interval+jitter. Absent -> interval+jitter.

  Returns the job's status map.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/cron/src/skein/spools/cron.clj#L229-L263">Source</a></sub></p>
