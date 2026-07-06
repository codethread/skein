# Skein Cron Spool

## Overview

`skein.spools.cron` is a generic timer/scheduling engine for the Skein weaver.
It registers named jobs that fire on a fixed interval with uniform jitter, each
on a spool-owned scheduled executor created at activation and closed when the
runtime stops (weaver-lifetime; a version-mismatch reload reinitialises it).

Cron knows nothing about any particular job: a caller registers a job by
fully-qualified `:run!` symbol resolving to a `(fn [runtime] ..)`, and the
engine owns only the timing, the last-outcome/next-fire status listing, and a
loud inspectable failure log. It is deliberately just timers — workflow/gate
integration is intentionally out of scope.

It owns only runtime-local weaver-lifetime state (the executor, the job table,
the failure log, and a jitter RNG), kept on the active runtime via
`skein.api.runtime.alpha/spool-state` and isolated from other runtimes in the
same JVM.

Cron itself spawns no external processes and ships no jobs. Because a real job
almost always escalates capability (a shell subprocess, a network call), cron is
an approved local-root spool like shuttle rather than a shipped classpath spool.

For composition recipes — registering an interval+jitter job, seeding the first
fire from external state, registering from `init.clj` rather than `install!`, and
coordinating many weavers with a best-effort lock — see the
[cookbook](../cron.cookbook.md).

## Dependency information

Cron has no spool prerequisites. Approve the local root from the selected
workspace's `spools.edn`:

```clojure
;; spools.edn
{:spools {skein.spools/cron {:local/root "../spools/cron"}}}
```

## Activation

Activate it from trusted startup config after syncing approved roots:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))
(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :cron
  {:ns 'skein.spools.cron
   :spools ['skein.spools/cron]
   :call 'skein.spools.cron/install!
   :required? true})
```

`install!` only creates the scheduled executor; it registers no jobs. Trusted
config registers jobs afterwards with `register!`, so the repo decides what runs
on a timer.

## Registering jobs

A job is registered by fully-qualified `:run!` symbol so registration stays
runtime-portable and resolvable across reloads:

```clojure
(require '[skein.spools.cron :as cron])

(cron/register! runtime
  {:id :nightly-report
   :interval-ms (* 24 60 60 1000)     ; base period between fires
   :jitter-ms (* 60 60 1000)          ; each fire offset uniformly in [-1h, +1h]
   :run! 'my.jobs/emit-report})       ; (fn [runtime] ..) run on each fire
```

- `:run!` runs on every fire. Its return value is recorded as the job's
  `:last-outcome`; a thrown exception is recorded in `failures` and never stops
  the cadence.
- `:initial-delay-fn` (optional) is a fully-qualified symbol resolving to
  `(fn [runtime] -> delay-ms)` that computes the delay until the **first** fire
  — use it to seed the first fire from external state. A throw or non-delay
  result is recorded loudly and the engine falls back to interval+jitter, so a
  failed seed never crashes startup. Absent, the first fire is also
  interval+jitter.
- `jitter-offset-ms` is exposed so an `:initial-delay-fn` can reuse the engine's
  single jitter definition (pass a seeded `java.util.Random` for deterministic
  tests).

Re-registering the same `:id` cancels the existing job's pending fire first, so
re-running startup config on reload is idempotent.

## Inspecting and removing

```clojure
(cron/jobs runtime)        ; status maps: id, interval, jitter, run! symbol,
                           ; next-fire-at, last-outcome/last-fired-at/last-error
(cron/failures runtime)    ; recorded :run and :initial-delay failures
(cron/deregister! runtime :nightly-report)
```

Job execution failures are recorded, not swallowed (TEN-003): a job whose
`:run!` throws stays scheduled, and its failure is queryable through `failures`.

## See also

- [`../README.md`](../README.md) — shipped and approved local-root spool index.
- [`../chime/README.md`](../chime/README.md) — the sibling notification engine
  this spool mirrors for local-root layout, runtime-owned state, and loud
  failure recording.
- [`../../docs/writing-shared-spools.md`](../../docs/writing-shared-spools.md) —
  runtime-owned state, versioned spool-state, and injectable side-effect
  boundaries.
