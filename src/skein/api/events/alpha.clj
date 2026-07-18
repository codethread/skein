(ns skein.api.events.alpha
  "Explicit-runtime API for registering and inspecting weaver event handlers.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns event handler validation, function resolution,
  registry state, and asynchronous failure capture. Internal mutation APIs
  submit events through `skein.core.weaver.dispatch`; this public namespace is
  observe-only."
  (:require [clojure.string :as str]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.test.alpha :as test-alpha]))

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

(defn register-handler!
  "Register or replace an event handler in `runtime` for selected event types."
  ([runtime key types fn-sym]
   (register-handler! runtime key types fn-sym {}))
  ([runtime key types fn-sym metadata]
   (let [entry {:key (validate-event-handler-key! key)
                :types (validate-event-types! types)
                :fn fn-sym
                :fn-value (resolve-event-handler-fn! runtime fn-sym)
                :metadata (validate-event-handler-metadata! metadata)}]
     (swap! (:handler-registry (access/event-system runtime)) assoc (:key entry) entry)
     (dissoc entry :fn-value))))

(defn unregister-handler!
  "Unregister an event handler by stable key from `runtime`."
  [runtime key]
  (let [key (validate-event-handler-key! key)]
    (swap! (:handler-registry (access/event-system runtime)) dissoc key)
    {:unregistered key}))

(defn ^:deprecated register!
  "Renamed to register-handler! (card d6xgt); this alias is removed before the v1 stamp."
  [& args]
  (apply register-handler! args))

(defn ^:deprecated unregister!
  "Renamed to unregister-handler! (card d6xgt); this alias is removed before the v1 stamp."
  [& args]
  (apply unregister-handler! args))

(defn handlers
  "Return data-first event handler registry entries from `runtime`."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (comp pr-str :key) (vals @(:handler-registry (access/event-system runtime))))))

(defn recent-failures
  "Return recent asynchronous event handler failures from `runtime`."
  [runtime]
  @(:recent-failures (access/event-system runtime)))

(defn await-quiescent!
  "Delegate to `skein.test.alpha/await-quiescent!`.

  This compatibility alias moves to the author-side test API and will be
  removed before the v1 stamp, after agent-harness.spool v3 migrates."
  ([runtime] (test-alpha/await-quiescent! runtime))
  ([runtime {:keys [timeout-ms]}]
   (test-alpha/await-quiescent! runtime {:timeout-ms timeout-ms})))
