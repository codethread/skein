
-----
# <a name="skein.api.scheduler.alpha">skein.api.scheduler.alpha</a>


Explicit-runtime API for the weaver-owned no-poller scheduler primitive.

  A weaver owns one durable clock trigger: trusted code persists a wake keyed
  by a stable caller key, an absolute instant, and a fully qualified handler
  symbol, and the runtime re-arms and dispatches it through the weaver's
  shared serialized async lane so clock-triggered handlers and post-commit
  event handlers observe one mutation order (RFC-009, DELTA-weaver-scheduler-
  repl-001). Delivery is at-least-once: handlers must be idempotent.

  This namespace owns wake validation, handler resolution, and JSON
  normalization of persisted rows into data-first maps; durable storage lives
  in `skein.core.db` and timer arming/dispatch in `skein.core.weaver.scheduler`.

  Pull-based `wake-at` strand attributes plus views remain the default answer
  when a poller already exists. Reach for this namespace only for the
  no-poller case where something must proactively happen at instant T with no
  client polling to trigger it.

  Callers own runtime selection and pass the target weaver runtime as the
  first argument to every function here; capture it with
  `skein.api.current.alpha/runtime` only at trusted entry points.




## <a name="skein.api.scheduler.alpha/cancel!">`cancel!`</a>
``` clojure
(cancel! runtime key)
```
Function.

Cancel a pending wake in `runtime` by stable key.

  Returns the cancellation's history row. A missing key fails loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/scheduler/alpha.clj#L64-L71">Source</a></sub></p>

## <a name="skein.api.scheduler.alpha/pending">`pending`</a>
``` clojure
(pending runtime)
```
Function.

Return all pending wakes in `runtime`, ordered by wake-at ascending.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/scheduler/alpha.clj#L73-L76">Source</a></sub></p>

## <a name="skein.api.scheduler.alpha/schedule!">`schedule!`</a>
``` clojure
(schedule! runtime wake)
```
Function.

Persist or replace a durable wake in `runtime` and arm it for dispatch.

  `wake` is a map of :key (stable non-blank string), :wake-at
  (java.time.Instant), :handler (fully qualified symbol resolvable in
  `runtime`'s spool classloader), and optional :payload (nil or a map that
  encodes to a JSON object). Replacing an existing key resets its attempt
  count. Malformed keys/instants/payloads, unknown wake keys, and unresolvable
  handlers fail loudly; no wake is persisted on failure.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/scheduler/alpha.clj#L47-L62">Source</a></sub></p>
