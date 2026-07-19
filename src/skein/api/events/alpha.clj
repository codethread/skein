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
            [skein.api.events.internal :as internal]))

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
   (let [entry {:key (internal/validate-handler-key! key)
                :types (internal/validate-handler-types! types)
                :fn fn-sym
                :fn-value (internal/resolve-handler-fn! runtime fn-sym)
                :metadata (internal/validate-handler-metadata! metadata)}]
     (swap! (internal/handler-registry runtime) assoc (:key entry) entry)
     (dissoc entry :fn-value))))

(defn unregister-handler!
  "Unregister the event handler stored under `key` in `runtime`.

  Validates `key` like registration, removes any entry stored under it (a
  key with no entry is a quiet no-op, so unregistration is idempotent), and
  returns `{:unregistered key}`."
  [runtime key]
  (let [key (internal/validate-handler-key! key)]
    (swap! (internal/handler-registry runtime) dissoc key)
    {:unregistered key}))

(defn handlers
  "Return `runtime`'s event handler registry as data-first entries.

  Each entry is `{:key :types :fn :metadata}` — never the resolved function
  value (SPEC-004.C66) — sorted by printed key so ordering is deterministic
  across mixed key types."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (comp pr-str :key) (vals @(internal/handler-registry runtime)))))

(defn recent-failures
  "Return `runtime`'s recent asynchronous handler failures, oldest first.

  Failures are bounded weaver-lifetime introspection state (SPEC-004.C67):
  each record carries `:handler/key`, `:handler/fn`, `:event/id`,
  `:event/type`, `:exception/message`, and `:failed/at`. Handler exceptions
  never fail the already-committed mutation that emitted the event."
  [runtime]
  @(internal/recent-failures-state runtime))

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
  :ret (s/coll-of map? :kind vector?))
