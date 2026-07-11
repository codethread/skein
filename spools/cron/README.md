# Skein Cron Spool

## Overview

`skein.spools.cron` is a userland recurrence layer over the weaver's durable
scheduler wake primitive. It registers named jobs that fire on a fixed interval
with optional uniform jitter. Each job owns one durable scheduler wake keyed
`cron/<id>`.

Cron owns no timing loop and no clock pump. The scheduler decides when a job is
due and is the single timing view. Cron's job listing is a status projection: it
shows registered jobs, their config, and recent outcomes. To inspect when a job
will next fire, read the scheduler's pending wakes.

A caller registers a job by fully-qualified `:run!` symbol resolving to
`(fn [runtime] ..)`. Cron resolves that symbol when the wake fires, so reloads
can update job code without replacing the pending wake.

Cron keeps only runtime-local weaver-lifetime state: an execution executor, the
job table, an in-flight latch, the failure log, and a jitter RNG. That state
lives on the active runtime through `skein.api.runtime.alpha/spool-state`, so
separate runtimes in one JVM do not share jobs or failures.

Cron itself spawns no external processes and ships no jobs. Because real jobs
often escalate capability, cron is an approved local-root spool rather than a
shipped classpath spool.

For recipes, see the [cookbook](../cron.cookbook.md): registering
interval+jitter jobs, keeping job startup out of broad config tests,
coordinating many weavers, inspecting status, and testing offloaded jobs.

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
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/sync! runtime)
(runtime/use! runtime :cron
  {:ns 'skein.spools.cron
   :spools ['skein.spools/cron]
   :call 'skein.spools.cron/install!
   :required? true})
```

`install!` creates the execution executor and registers no jobs. Trusted config
registers jobs afterwards with `register!`, so the repo decides what runs on a
cadence.

## Registering jobs

A job is registered by fully-qualified `:run!` symbol:

```clojure
(require '[skein.spools.cron :as cron])

(cron/register! runtime
  {:id :nightly-report
   :interval-ms (* 24 60 60 1000)     ; base period between fires
   :jitter-ms (* 60 60 1000)          ; each fire offset uniformly in [-1h, +1h]
   :run! 'my.jobs/emit-report})       ; (fn [runtime] ..) run on each fire
```

- `:id` is the durable job identity. Cron stores the cadence in scheduler key
  `cron/<id>`.
- `:interval-ms` is the base period between fires.
- `:jitter-ms` is optional and defaults to `0`. Each fire is offset uniformly in
  `[-jitter, +jitter]`.
- `:run!` runs for every delivered wake. Its return value is recorded as
  `:last-outcome`; a thrown exception is recorded in `failures` and does not stop
  the cadence.

Re-registering the same `:id` preserves the pending wake when the
cadence-defining tuple `[interval-ms jitter-ms run!]` is unchanged. This is the
normal reload path: config re-runs, the in-memory job table is repopulated, and
the existing durable countdown remains in place. A changed interval, jitter, or
`:run!` symbol replaces the wake and starts the next countdown from now.

Cron no longer has a first-fire seed hook. The first fire, and every later fire,
is represented by the durable scheduler wake. If code needs a different
next-fire instant, change the registration cadence or schedule through the
scheduler primitive directly in userland.

## Delivery contract

Cron delivery is at-least-once. A due scheduler wake may be delivered more than
once, so job authors must make `:run!` idempotent or otherwise
duplicate-tolerant.

When a `cron/<id>` wake is delivered, scheduler invokes
`skein.spools.cron/fire-wake` on the weaver's shared event lane. That handler
does only the cadence work:

1. Decode the job id from the wake payload.
2. Look up the in-memory job config. If the job was deregistered, return without
   rescheduling.
3. Persist the next `cron/<id>` wake before running the job body.
4. Hand the job body to cron's execution executor.
5. Return so the scheduler completes the delivered wake.

This is the wake-delivery/job-completion split. A wake is considered delivered
when the next wake has been persisted and the job body has been handed off. The
job body's success or failure is cron status, not scheduler timing state. A
thrown job records a failure and the cadence continues.

A duplicate delivery follows the same path. It replaces the next pending
`cron/<id>` wake with a fresh `now + interval + jitter` wake and offloads the job
body again. This is expected under the at-least-once contract; write handlers so
the second run is harmless.

## Inspecting and removing

```clojure
(cron/jobs runtime)        ; registered job status: id, interval, jitter, run!, last outcome/error
(cron/failures runtime)    ; recorded :run and :offload failures
(cron/deregister! runtime :nightly-report)
```

`jobs` does not expose the next fire time. Read
`skein.api.scheduler.alpha/pending` for the pending `cron/<id>` wake when you
need timing. That keeps the scheduler as the single timing surface.

Job execution failures are recorded, not swallowed (TEN-003): a job whose
`:run!` throws stays registered, keeps its cadence, and records the error in
`failures` and on the job status.

## See also

- [`../README.md`](../README.md) — shipped and approved local-root spool index.
- [`../chime/README.md`](../chime/README.md) — the sibling notification engine
  this spool mirrors for local-root layout, runtime-owned state, and loud failure
  recording.
- [`../../docs/writing-shared-spools.md`](../../docs/writing-shared-spools.md) —
  runtime-owned state, versioned spool-state, and injectable side-effect
  boundaries.
