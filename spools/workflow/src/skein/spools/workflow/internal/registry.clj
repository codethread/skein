(ns skein.spools.workflow.internal.registry
  "Owner-partitioned registries for the workflow spool.

  Workflow constructors and executors are replaceable declarations published
  owner-complete through `skein.api.registry.alpha`: a complete owner partition
  replaces the owner's prior contribution, so a route or executor a refresh
  omits disappears by omission with no global reload (DELTA-OlrDrt-001.CC2/CC4).
  The handle is a single runtime-owned `registry.alpha` value held directly in
  `spool-state` — never nested — so the refresh kernel discovers its kinds
  alongside the core registries.

  Binding time follows DELTA-OlrDrt-001.CC10. Constructor entries are qualified
  symbols resolved at each named route transition, so devflow's live route
  re-pointing takes effect on the next transition while a run already between
  stages keeps the value it started with. Executor entries are qualified symbols
  resolved to a function value at each gate evaluation; that resolution is the
  per-evaluation snapshot.

  A raw executor predicate *function value* carries no symbol — the unavoidable
  direct/REPL case (review note `vovp1` finding 3). Rather than store a function
  object as owner-partition declaration data (DELTA-OlrDrt-001.CC8, D1), those
  values live in a separate runtime-owned resource map; the declarative kind
  stays symbols-only."
  (:require [clojure.spec.alpha :as s]
            [skein.api.registry.alpha :as registry]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [fail!]]))

(def constructor-kind
  "Kind id for the workflow-name -> constructor-symbol registry."
  :skein.spools.workflow/constructor)

(def executor-kind
  "Kind id for the gate-waiter -> stall-predicate-symbol registry."
  :skein.spools.workflow/executor)

(def ^:private repl-owner
  "Owner keyword for direct/REPL registrations, published in the `:direct` layer
  (DELTA-OlrDrt-001.CC3), matching the core registries' `:skein.owner/repl`."
  :skein.owner/repl)

;; Entry specs the kinds validate against. Constructors and executors are both
;; fully qualified symbols; a raw function value never reaches the kind.
(s/def ::constructor-symbol qualified-symbol?)
(s/def ::executor-symbol qualified-symbol?)

(def ^:private registry-state-version
  "Shape version for the workflow registry handle. Bump when the declared kinds
  change: spool-state survives refresh, so a version mismatch reinitializes
  rather than reuse a stale handle."
  1)

(defn- new-registry-handle []
  (doto (registry/registry)
    (registry/declare-kind! {:id constructor-kind
                             :entry-spec ::constructor-symbol
                             :binding-moment :route-transition})
    (registry/declare-kind! {:id executor-kind
                             :entry-spec ::executor-symbol
                             :binding-moment :gate-evaluation})))

(defn registry-handle
  "Return `rt`'s workflow registry handle, materializing it on first use.

  The handle is a direct `spool-state` value so the refresh kernel discovers its
  constructor and executor kinds. Realizing it also declares the kinds, so a
  module contribution naming them finds them already declared."
  [rt]
  (runtime/spool-state rt ::registry
                       {:version registry-state-version}
                       new-registry-handle))

(def ^:private executor-fns-version
  "Shape version for the raw executor-function resource map. Bump when
  `new-executor-fns` changes shape."
  1)

(defn- new-executor-fns []
  {:executor-fns (atom {})})

(defn- executor-fns-state [rt]
  (runtime/spool-state rt ::executor-fns
                       {:version executor-fns-version}
                       new-executor-fns))

(defn executor-fns
  "Return `rt`'s raw executor-function map atom (waiter string -> predicate).

  This holds direct/REPL predicates supplied as bare function values, which
  cannot be owner-partition declaration data. It is runtime-owned resource
  state (DELTA-OlrDrt-001.CC8)."
  [rt]
  (:executor-fns (executor-fns-state rt)))

(defn- waiter-key [waiter]
  (name waiter))

(defn- direct-partition [handle kind-id key value]
  ;; Read-modify-write the direct/REPL owner partition, keeping the owner's
  ;; other live entries and restating override intent for every key (safe even
  ;; when a key shadows nothing).
  (let [entries (assoc (get-in (registry/snapshot handle)
                               [:partitions kind-id repl-owner :entries]
                               {})
                       key value)]
    {:layer :direct :entries entries :overrides (set (keys entries))}))

(defn register-constructor!
  "Register constructor symbol `sym` under keyword `name` at the direct layer.

  Replaces any prior direct entry under `name`; other direct constructors are
  retained. Returns `name`."
  [rt name sym]
  (let [handle (registry-handle rt)]
    (registry/replace-owner! handle constructor-kind repl-owner
                             (direct-partition handle constructor-kind name sym))
    name))

(defn register-executor-symbol!
  "Register executor predicate symbol `sym` for `waiter` at the direct layer.

  Replaces any prior direct entry for `waiter`; other direct executors are
  retained. Also drops any raw function value previously held for `waiter` so
  the two sources never disagree. Returns the waiter as a keyword."
  [rt waiter sym]
  (let [handle (registry-handle rt)
        key (waiter-key waiter)]
    (swap! (executor-fns rt) dissoc key)
    (registry/replace-owner! handle executor-kind repl-owner
                             (direct-partition handle executor-kind key sym))
    (keyword key)))

(defn register-executor-fn!
  "Register a raw executor predicate function value for `waiter`.

  The value is runtime-owned resource state, not owner-partition declaration
  data. Also drops any direct symbol previously held for `waiter`. Returns the
  waiter as a keyword."
  [rt waiter pred]
  (let [handle (registry-handle rt)
        key (waiter-key waiter)
        entries (dissoc (get-in (registry/snapshot handle)
                                [:partitions executor-kind repl-owner :entries]
                                {})
                        key)]
    (registry/replace-owner! handle executor-kind repl-owner
                             {:layer :direct :entries entries
                              :overrides (set (keys entries))})
    (swap! (executor-fns rt) assoc key pred)
    (keyword key)))

(defn workflow-constructors
  "Return the effective workflow name (keyword) -> constructor symbol map."
  [rt]
  (registry/effective (registry-handle rt) constructor-kind))

(defn workflow-definition
  "Return the effective constructor symbol registered under keyword `name`,
  failing loudly (TEN-003) when `name` is not registered.

  The lookup reads the current effective snapshot, so a re-pointed route
  resolves the replacement at this named transition (DELTA-OlrDrt-001.CC10)."
  [rt name]
  (let [registry (workflow-constructors rt)]
    (or (get registry name)
        (fail! "Unknown registered workflow"
               {:name name :registered (vec (keys registry))}))))

(defn executor-for
  "Return the stall predicate for a ready gate's `waiter`, or nil.

  A raw direct/REPL function value wins; otherwise the effective executor symbol
  is resolved to its current Var, the per-gate-evaluation snapshot
  (DELTA-OlrDrt-001.CC10)."
  [rt waiter]
  (let [key (waiter-key waiter)]
    (or (get @(executor-fns rt) key)
        (when-let [sym (get (registry/effective (registry-handle rt) executor-kind) key)]
          (requiring-resolve sym)))))

(defn executor-map
  "Return the merged waiter-string -> predicate map of every effective executor.

  Effective symbols resolve to their current Var; raw function values shadow a
  same-waiter symbol."
  [rt]
  (merge (into {}
               (map (fn [[key sym]] [key (requiring-resolve sym)]))
               (registry/effective (registry-handle rt) executor-kind))
         @(executor-fns rt)))
