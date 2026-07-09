(ns skein.api.events.alpha
  "Explicit-runtime API for registering, inspecting, and submitting weaver events.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns event handler validation, function resolution,
  registry state, asynchronous failure capture, and event submission; the queue
  submission and worker dispatch live in `skein.core.weaver.dispatch` and
  `skein.core.weaver.runtime`."
  (:require [clojure.string :as str]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]))

(defn- validate-event-handler-key! [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Event handler key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Event handler key string must be non-blank" {:key key})))
  key)

(defn- validate-event-types! [types]
  (when-not (set? types)
    (throw (ex-info "Event handler types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Event handler types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Event handler types must be keywords" {:type type :types types}))))
  types)

(defn- resolve-event-handler-fn! [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Event handler function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (try
                   (access/with-spool-classloader runtime #(requiring-resolve fn-sym))
                   (catch Throwable t
                     (throw (ex-info "Event handler function could not be resolved" {:fn fn-sym} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Event handler symbol must resolve to a callable value" {:fn fn-sym :resolved-class (str (class value))})))
    value))

(defn- validate-event-handler-metadata! [metadata]
  (let [metadata (or metadata {})]
    (when-not (map? metadata)
      (throw (ex-info "Event handler metadata must be a map" {:metadata metadata})))
    (when-not (dispatch/data-first-value? metadata)
      (throw (ex-info "Event handler metadata must contain only data-first values" {:metadata metadata})))
    metadata))

(defn register!
  "Register or replace an event handler in `runtime` for selected event types."
  ([runtime key types fn-sym]
   (register! runtime key types fn-sym {}))
  ([runtime key types fn-sym metadata]
   (let [entry {:key (validate-event-handler-key! key)
                :types (validate-event-types! types)
                :fn fn-sym
                :fn-value (resolve-event-handler-fn! runtime fn-sym)
                :metadata (validate-event-handler-metadata! metadata)}]
     (swap! (:handler-registry (access/event-system runtime)) assoc (:key entry) entry)
     (dissoc entry :fn-value))))

(defn unregister!
  "Unregister an event handler by stable key from `runtime`."
  [runtime key]
  (let [key (validate-event-handler-key! key)]
    (swap! (:handler-registry (access/event-system runtime)) dissoc key)
    {:unregistered key}))

(defn handlers
  "Return data-first event handler registry entries from `runtime`."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (comp pr-str :key) (vals @(:handler-registry (access/event-system runtime))))))

(defn recent-failures
  "Return recent asynchronous event handler failures from `runtime`."
  [runtime]
  @(:recent-failures (access/event-system runtime)))

(defn enqueue!
  "Submit an event map to `runtime`'s event system for asynchronous dispatch."
  [runtime event]
  (dispatch/enqueue! runtime event))

(defn await-quiescent!
  "Block until `runtime`'s event lane settles, then return `runtime`.

  Settled means the bounded event queue is empty *and* no handler dispatch is in
  flight; the worker raises its dispatch-in-progress flag before it claims an
  event, so this never reports settled while a just-claimed dispatch is still
  running. Throws an `ex-info` on timeout. The default budget comes from
  `skein.spools.test-support/await-budget-ms`; override it with `:timeout-ms`.

  This is a lane-only primitive: it says nothing about off-lane completion
  signals a handler may have kicked off (poll-until loops, shuttle awaits)."
  ([runtime] (await-quiescent! runtime {}))
  ([runtime {:keys [timeout-ms]}]
   (let [event-system (access/event-system runtime)
         queue ^java.util.concurrent.BlockingQueue (:queue event-system)
         dispatch-in-progress? (:dispatch-in-progress? event-system)
         timeout-ms (or timeout-ms ((requiring-resolve 'skein.spools.test-support/await-budget-ms)))
         _ (when-not (and (integer? timeout-ms) (pos? timeout-ms))
             (throw (ex-info "await-quiescent! :timeout-ms must be a positive integer"
                             {:timeout-ms timeout-ms})))
         deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (and (.isEmpty queue) (not @dispatch-in-progress?)) runtime
         (> (System/currentTimeMillis) deadline)
         (throw (ex-info "Timed out awaiting event-lane quiescence"
                         {:timeout-ms timeout-ms
                          :queue-size (.size queue)
                          :dispatch-in-progress? @dispatch-in-progress?}))
         :else (do (Thread/sleep 5) (recur)))))))
