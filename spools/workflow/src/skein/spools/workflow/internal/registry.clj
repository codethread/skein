(ns skein.spools.workflow.internal.registry
  "Runtime-owned registry spool-state for the workflow spool.

  Holds the workflow-name and executor registries as weaver-lifetime spool
  state (SPEC-004.C95): both survive `reload!`, and startup config re-registers
  their entries during reload and after a restart. The public register/lookup
  builders in the story file resolve the ambient runtime and thread it here."
  (:require [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [fail!]]))

(def ^:private registry-state-version
  "Shape version for workflow's runtime registry state. Version 1 contains the
  workflow-name and executor registry atoms. Bump this when `new-registry-state`
  changes shape: spool state survives `reload!`, so a version mismatch must
  reinitialize rather than reuse stale state (SPEC-004.C95)."
  1)

(defn- new-registry-state []
  {:workflow-name-registry (atom {})
   :executor-registry (atom {})})

(defn- registry-state [rt]
  (runtime/spool-state rt ::registry-state
                       {:version registry-state-version}
                       new-registry-state))

(defn workflow-name-registry
  "Return `rt`'s workflow-name registry atom.

  The runtime owns this state for its lifetime. Its spool-state entry survives
  `reload!`; startup config re-registers definitions during reload and after a
  restart."
  [rt]
  (:workflow-name-registry (registry-state rt)))

(defn executor-registry
  "Return `rt`'s executor registry atom.

  The runtime owns this state for its lifetime. Its spool-state entry survives
  `reload!`; startup config re-registers executors during reload and after a
  restart."
  [rt]
  (:executor-registry (registry-state rt)))

(defn executor-for
  "Return the registered stall predicate for a ready gate's `waiter` name, or nil."
  [rt waiter]
  (get @(executor-registry rt) waiter))

(defn workflow-definition
  "Return the constructor symbol registered under keyword `name` on `rt`, failing
  loudly (TEN-003) when `name` is not registered."
  [rt name]
  (let [registry @(workflow-name-registry rt)]
    (or (get registry name)
        (fail! "Unknown registered workflow"
               {:name name :registered (vec (keys registry))}))))
