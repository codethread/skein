(ns skein.api.scheduler.alpha
  "Explicit-runtime API for the weaver-owned no-poller scheduler primitive.

  A weaver owns one durable clock trigger: trusted code persists a wake keyed
  by a stable caller key, an absolute instant, and a fully qualified handler
  symbol, and the runtime re-arms and dispatches it through the weaver's
  shared serialized async lane so clock-triggered handlers and post-commit
  event handlers observe one mutation order (RFC-009, DELTA-weaver-scheduler-
  repl-001). Delivery is at-least-once: handlers must be idempotent.

  This namespace owns wake validation, handler resolution, and JSON
  normalization of persisted rows into data-first maps; durable storage lives
  in `skein.core.db` and timer arming/dispatch in `skein.core.weaver.scheduler`.

  Pull-based `wake-at` strand attributes plus named queries remain the default
  answer when a poller already exists. Reach for this namespace only for the
  no-poller case where something must proactively happen at instant T with no
  client polling to trigger it.

  Callers own runtime selection and pass the target weaver runtime as the
  first argument to every function here; capture it with
  `skein.api.current.alpha/runtime` only at trusted entry points."
  (:require [skein.core.db :as db]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.scheduler :as scheduler]))

(defn- normalize-wake
  "Decode a scheduler wake/history row's JSON payload and handler symbol."
  [row]
  (some-> row
          (update :payload db/<-json)
          (update :handler symbol)))

(defn- resolve-scheduler-handler-fn! [runtime handler]
  (when-not (and (symbol? handler) (namespace handler))
    (throw (ex-info "Scheduler handler must be a fully qualified symbol" {:handler handler})))
  (let [resolved (try
                   (access/with-spool-classloader runtime #(requiring-resolve handler))
                   (catch Throwable t
                     (throw (ex-info "Scheduler handler could not be resolved" {:handler handler} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Scheduler handler symbol must resolve to a callable value"
                      {:handler handler :resolved-class (str (class value))})))
    value))

(defn schedule!
  "Persist or replace a durable wake in `runtime` and arm it for dispatch.

  `wake` is a map of :key (stable non-blank string), :wake-at
  (java.time.Instant), :handler (fully qualified symbol resolvable in
  `runtime`'s spool classloader), and optional :payload (nil or a map that
  encodes to a JSON object). Replacing an existing key resets its attempt
  count. Malformed keys/instants/payloads, unknown wake keys, and unresolvable
  handlers fail loudly; no wake is persisted on failure."
  [runtime wake]
  (when-not (map? wake)
    (throw (ex-info "Scheduler wake must be a map" {:wake wake})))
  (resolve-scheduler-handler-fn! runtime (:handler wake))
  (let [created (normalize-wake (db/schedule-wake! (access/ds runtime) wake))]
    (scheduler/arm! runtime)
    created))

(defn cancel!
  "Cancel a pending wake in `runtime` by stable key.

  Returns the cancellation's history row. A missing key fails loudly."
  [runtime key]
  (let [cancelled (normalize-wake (db/cancel-wake! (access/ds runtime) key))]
    (scheduler/arm! runtime)
    cancelled))

(defn pending
  "Return all pending wakes in `runtime`, ordered by wake-at ascending."
  [runtime]
  (mapv normalize-wake (db/pending-wakes (access/ds runtime))))
