(ns skein.core.weaver.core-registry
  "Owner-partition backing for the five core weaver registries.

  CLI ops, named queries, weave patterns, lifecycle hooks, and event handlers
  each become one kind declared on an `owner-registry` kernel. Every declaration
  is owned by a stable keyword owner in a fixed layer (`:defaults < :spools <
  :workspace < :direct`); replacing an owner partition is complete, so an
  omitted key disappears, and cross-owner collisions or missing override intent
  fail before the atom changes.

  A `store` is `{:kind <kind-id> :kernel <owner-registry-atom> :effective
  <projection-atom>}`. The projection atom mirrors the winning entry per key so
  the existing dispatch, help, and lifecycle readers keep the flat map shape
  they read today (`{entry-key -> entry-value}`). This projection is the
  temporary internal adapter that lets current callers stay green through this
  slice; Task 16 removes it once every reader consumes effective snapshots
  directly (TASK-Olr-002.MI4/MI5).

  Direct per-entry writes carry an explicit owner and layer through the
  `*owner*`/`*layer*` context, defaulting to the direct/REPL owner; built-in
  installation binds the system owner in the defaults layer. Owner-complete
  replacement and removal are the sharp tools the module-contribution path in
  later tasks reuses without forking these semantics."
  (:require [clojure.spec.alpha :as s]
            [skein.core.weaver.owner-registry :as owner-registry]))

;; Permissive entry specs: the API layer runs each registry's rich validator
;; before an entry reaches the kernel, so the kind spec only pins the coarse
;; stored shape a projection reader depends on.
(s/def ::op-entry map?)
(s/def ::query-entry (s/or :where-vector vector? :detailed map?))
(s/def ::pattern-entry map?)
(s/def ::hook-entry map?)
(s/def ::event-entry map?)

(def ^:private kind-declarations
  "Declaration datum per core kind: registered entry spec and binding moment
  (DELTA-OlrDrt-001.CC10). The binding moment is retained declaration data;
  this slice does not yet dispatch on it."
  {:ops {:entry-spec ::op-entry :binding-moment :op/invocation}
   :queries {:entry-spec ::query-entry :binding-moment :query/use}
   :patterns {:entry-spec ::pattern-entry :binding-moment :pattern/invocation}
   :hooks {:entry-spec ::hook-entry :binding-moment :hook/dispatch-start}
   :events {:entry-spec ::event-entry :binding-moment :event/dispatch-start}})

(def system-owner
  "Owner keyword for Skein-shipped defaults such as the built-in help op."
  :skein.owner/system)

(def system-layer
  "Layer the system owner contributes in; the lowest fixed precedence layer."
  :defaults)

(def repl-owner
  "Default owner for direct per-entry registration from init files or the REPL."
  :skein.owner/repl)

(def repl-layer
  "Layer direct/REPL registration contributes in; the highest fixed layer."
  :direct)

(def ^:dynamic *owner*
  "Stable owner keyword every direct registration write is attributed to."
  repl-owner)

(def ^:dynamic *layer*
  "Fixed precedence layer the current owner contributes in."
  repl-layer)

(defn with-owner*
  "Call `f` with `owner`/`layer` as the ambient direct-registration attribution."
  [owner layer f]
  (binding [*owner* owner *layer* layer]
    (f)))

(defn- declare-core-kind! [kernel kind-id]
  (let [{:keys [entry-spec binding-moment]} (kind-declarations kind-id)]
    (owner-registry/declare-kind! kernel
                                  {:id kind-id
                                   :entry-spec entry-spec
                                   :binding-moment binding-moment
                                   :layer-policy owner-registry/layer-precedence})))

(defn backed-registry
  "Create a backed store for `kind-id` with its kind declared and an empty
  effective projection."
  [kind-id]
  (when-not (contains? kind-declarations kind-id)
    (throw (ex-info "Unknown core registry kind" {:kind kind-id})))
  (let [kernel (owner-registry/registry)]
    (declare-core-kind! kernel kind-id)
    {:kind kind-id :kernel kernel :effective (atom {})}))

(defn effective
  "Return the store's flat effective projection: `{entry-key -> entry-value}`."
  [store]
  @(:effective store))

(defn snapshot
  "Return the store kernel's immutable owner-partition snapshot."
  [store]
  (owner-registry/snapshot (:kernel store)))

(defn- refresh! [{:keys [kind kernel effective]}]
  (reset! effective (owner-registry/effective-values (owner-registry/snapshot kernel) kind))
  effective)

(defn- owner-partition [kernel kind owner]
  (get-in (owner-registry/snapshot kernel) [:partitions kind owner]))

(defn put-entry!
  "Add or replace `key`→`value` in the current owner's partition and refresh the
  projection. Other owners and the owner's other keys are untouched. The current
  owner's existing override intents and layer are preserved."
  [{:keys [kind kernel] :as store} key value]
  (let [existing (owner-partition kernel kind *owner*)
        entries (assoc (:entries existing {}) key value)]
    (owner-registry/replace-owner! kernel kind *owner*
                                   {:layer (:layer existing *layer*)
                                    :entries entries
                                    :overrides (:overrides existing #{})})
    (refresh! store)
    value))

(defn remove-entry!
  "Remove `key` from the current owner's partition and refresh the projection.

  Removing the owner's last key removes the owner partition. Removing an absent
  key is an idempotent no-op."
  [{:keys [kind kernel] :as store} key]
  (let [existing (owner-partition kernel kind *owner*)
        entries (dissoc (:entries existing {}) key)]
    (if (seq entries)
      (owner-registry/replace-owner! kernel kind *owner*
                                     {:layer (:layer existing *layer*)
                                      :entries entries
                                      :overrides (disj (:overrides existing #{}) key)})
      (owner-registry/remove-owner! kernel kind *owner*))
    (refresh! store)))

(defn replace-owner!
  "Replace `owner`'s complete partition in `store` and refresh the projection.

  `partition` is `{:layer <layer> :entries {<key> <value> ...} :overrides #{<key>
  ...}}`; keys the owner omits disappear. A same-layer duplicate or missing
  cross-layer override intent throws before the kernel changes."
  [store owner partition]
  (owner-registry/replace-owner! (:kernel store) (:kind store) owner partition)
  (refresh! store))

(defn remove-owner!
  "Remove `owner`'s partition from `store` and refresh the projection. Unrelated
  owners are untouched."
  [store owner]
  (owner-registry/remove-owner! (:kernel store) (:kind store) owner)
  (refresh! store))

(defn reset-store!
  "Clear every owner partition in `store` back to its freshly declared, empty
  state. Used by weaver-lifetime reload, which reinstalls built-in and userland
  contributions afterward."
  [{:keys [kind kernel effective] :as store}]
  (reset! kernel (owner-registry/normalize {}))
  (declare-core-kind! kernel kind)
  (reset! effective {})
  store)
