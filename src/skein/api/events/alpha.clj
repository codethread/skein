(ns skein.api.events.alpha
  "Explicit-runtime API for managing and inspecting weaver event handlers.

  Registration and unregistration mutate the runtime's weaver-lifetime
  handler registry; `handlers` and `recent-failures` are the data-first
  reads over registry and failure state. Every registration is validated
  loudly at the seam — stable key, non-empty keyword type set, fully
  qualified function symbol resolvable under the runtime spool
  classloader, data-first metadata — and entries replace by key for
  reload workflows. Event submission is not public surface: internal
  mutation APIs submit events through `skein.core.weaver.dispatch`
  (SPEC-004.C73), and the event-lane quiescence await ships in
  `skein.test.alpha` (SPEC-004.C74b).

  Callers own runtime selection and pass the target weaver runtime as
  the first argument."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]))

(declare handler-registry recent-failures-state
         validate-handler-key! validate-handler-types! validate-handler-metadata!
         resolve-handler-fn!)

(defn register-handler!
  "Register or replace an event handler in `runtime` for selected event types.

  Builds the registry entry from loudly validated pieces — `key` a keyword,
  symbol, or non-blank string; `types` a non-empty set of event type
  keywords; `fn-sym` a fully qualified symbol resolving to a callable under
  the runtime spool classloader (resolution happens here, so a bad symbol
  fails registration, not dispatch); `metadata` a data-first map — swaps it
  into the registry, replacing any prior entry with the same key, and
  returns the entry as data (the resolved function value stays internal)."
  ([runtime key types fn-sym]
   (register-handler! runtime key types fn-sym {}))
  ([runtime key types fn-sym metadata]
   (let [entry {:key (validate-handler-key! key)
                :types (validate-handler-types! types)
                :fn fn-sym
                :fn-value (resolve-handler-fn! runtime fn-sym)
                :metadata (validate-handler-metadata! metadata)}]
     (swap! (handler-registry runtime) assoc (:key entry) entry)
     (dissoc entry :fn-value))))

(defn unregister-handler!
  "Unregister the event handler stored under `key` in `runtime`.

  Validates `key` like registration, removes any entry stored under it (a
  key with no entry is a quiet no-op, so unregistration is idempotent), and
  returns `{:unregistered key}`."
  [runtime key]
  (let [key (validate-handler-key! key)]
    (swap! (handler-registry runtime) dissoc key)
    {:unregistered key}))

(defn handlers
  "Return `runtime`'s event handler registry as data-first entries.

  Each entry is `{:key :types :fn :metadata}` — never the resolved function
  value (SPEC-004.C66) — sorted by printed key so ordering is deterministic
  across mixed key types."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (comp pr-str :key) (vals @(handler-registry runtime)))))

(defn recent-failures
  "Return `runtime`'s recent asynchronous handler failures, oldest first.

  Failures are bounded weaver-lifetime introspection state (SPEC-004.C67):
  each record carries `:handler/key`, `:handler/fn`, `:event/id`,
  `:event/type`, `:exception/message`, and `:failed/at`. Handler exceptions
  never fail the already-committed mutation that emitted the event."
  [runtime]
  @(recent-failures-state runtime))

;; --- seam specs ---------------------------------------------------------------

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

(s/def ::key
  (s/or :keyword keyword?
        :symbol symbol?
        :string (s/and string? (complement str/blank?))))

(s/def ::types (s/coll-of keyword? :kind set? :min-count 1))

(s/def ::fn qualified-symbol?)

;; Metadata must additionally hold only data-first values; the recursive
;; grammar is defined by `skein.core.weaver.dispatch/data-first-value?`, the
;; authority the seam enforces, so the spec states only the map shape rather
;; than mirroring it (SPEC-003.C19a).
(s/def ::metadata (s/nilable map?))

;; The data-first registry entry: what registration returns and `handlers`
;; lists; the resolved function value never leaves the registry.
(s/def ::handler-entry (s/keys :req-un [::key ::types ::fn ::metadata]))

(s/def ::unregistered ::key)

;; The promised failure-record key set (SPEC-004.C67). Its qualified keys stay
;; unregistered here on purpose: the record is written by the dispatch worker,
;; so this spec pins the promised keys without claiming shared `:event/*` and
;; `:handler/*` key specs the writer does not consult.
(s/def ::failure-record
  (s/keys :req [:handler/key :handler/fn :event/id :event/type
                :exception/message :failed/at]))

(s/fdef register-handler!
  :args (s/cat :runtime ::runtime :key ::key :types ::types :fn-sym ::fn
               :metadata (s/? ::metadata))
  :ret ::handler-entry)

(s/fdef unregister-handler!
  :args (s/cat :runtime ::runtime :key ::key)
  :ret (s/keys :req-un [::unregistered]))

(s/fdef handlers
  :args (s/cat :runtime ::runtime)
  :ret (s/coll-of ::handler-entry :kind vector?))

(s/fdef recent-failures
  :args (s/cat :runtime ::runtime)
  :ret (s/coll-of ::failure-record :kind vector?))

;; --- event-system state access ------------------------------------------------

(defn- handler-registry
  "Return `runtime`'s event handler registry atom (a map keyed by handler key)."
  [runtime]
  (:handler-registry (access/event-system runtime)))

(defn- recent-failures-state
  "Return `runtime`'s bounded recent handler failure state atom (a vector)."
  [runtime]
  (:recent-failures (access/event-system runtime)))

;; --- handler seam validation ----------------------------------------------------

(defn- validate-handler-key!
  "Return `key` when it is a keyword, symbol, or non-blank string; throw otherwise."
  [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Event handler key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Event handler key string must be non-blank" {:key key})))
  key)

(defn- validate-handler-types!
  "Return `types` when it is a non-empty set of keywords; throw otherwise."
  [types]
  (when-not (set? types)
    (throw (ex-info "Event handler types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Event handler types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Event handler types must be keywords" {:type type :types types}))))
  types)

(defn- validate-handler-metadata!
  "Return `metadata` (nil becomes `{}`) when it is a data-first map; throw otherwise."
  [metadata]
  (let [metadata (or metadata {})]
    (when-not (map? metadata)
      (throw (ex-info "Event handler metadata must be a map" {:metadata metadata})))
    (when-not (dispatch/data-first-value? metadata)
      (throw (ex-info "Event handler metadata must contain only data-first values"
                      {:metadata metadata})))
    metadata))

;; --- handler function resolution ------------------------------------------------

(defn- resolve-handler-fn!
  "Resolve `fn-sym` under `runtime`'s spool classloader to a callable value.

  Throws when the symbol is not fully qualified, cannot be resolved, or names
  a non-callable value."
  [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Event handler function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (try
                   (access/with-spool-classloader runtime #(requiring-resolve fn-sym))
                   (catch Throwable t
                     (throw (ex-info "Event handler function could not be resolved"
                                     {:fn fn-sym} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Event handler symbol must resolve to a callable value"
                      {:fn fn-sym :resolved-class (str (class value))})))
    value))
