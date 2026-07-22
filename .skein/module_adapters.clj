(ns module-adapters
  "Branch-only module adapters for the owner-scoped-live-refresh cutover.

  Task 11 (PLAN-Olr-001.PH3) declares every workspace concern as a stable
  runtime module. First-party spools carry their own contribute/reconcile entry
  points; the peer spools (agent-run, delegation, bench, kanban, devflow,
  subagent) still register through their imperative `install!` because their
  owner-partitioned kinds land in peer Tasks 12-15. Until then each peer module
  contributes nothing and its reconcile calls that `install!` — the BRANCH-ONLY
  adapters below. Task 16 removes them once every peer converts.

  `reconcile-help-transform` is not a branch adapter: it is this canonical
  world's config-election of the batteries reference help transform, kept here
  beside the batteries module ordering so the election has an owner."
  (:require [skein.api.current.alpha :as current]
            [skein.api.runtime.help-transform.alpha :as help-transform]))

(defn nil-contribution
  "The empty declarative contribution for an install!-driven peer module.

  An explicit contribution keeps the default authoring-form collector off the
  peer namespace, whose registrations stay imperative until it converts, so the
  module publishes nothing and its reconcile does all the work."
  [_ctx]
  {})

(defn- run-install!
  "Invoke a peer spool's no-arg `install!` under an explicit runtime binding.

  The symbol resolves at reconcile time, when the module's own `:ns` source is
  already ledger-loaded, so this never plain-requires across a synced root. The
  runtime binding scopes the refreshing runtime so the peer `install!`'s ambient
  `current/runtime` reads resolve to it across the branch-only boundary."
  [runtime install-sym reconciled]
  (current/with-runtime runtime ((requiring-resolve install-sym)))
  {:reconciled reconciled :adapter :branch-only})

(defn reconcile-shuttle
  "BRANCH-ONLY: install the agent-run (shuttle) spool. Removed by Task 16."
  [{:keys [runtime]}]
  (run-install! runtime 'ct.spools.agent-run/install! :agent-run))

(defn reconcile-delegation
  "BRANCH-ONLY: install the delegation spool. Removed by Task 16."
  [{:keys [runtime]}]
  (run-install! runtime 'ct.spools.delegation/install! :delegation))

(defn reconcile-bench
  "BRANCH-ONLY: install the bench spool. Removed by Task 16."
  [{:keys [runtime]}]
  (run-install! runtime 'ct.spools.bench/install! :bench))

(defn reconcile-devflow
  "BRANCH-ONLY: install the devflow spool. Removed by Task 16."
  [{:keys [runtime]}]
  (run-install! runtime 'ct.spools.devflow/install! :devflow))

(defn reconcile-kanban
  "BRANCH-ONLY: install the kanban spool. Removed by Task 16."
  [{:keys [runtime]}]
  (run-install! runtime 'ct.spools.kanban/install! :kanban))

(defn reconcile-subagent
  "BRANCH-ONLY: install the subagent gate executor. Removed by Task 16."
  [{:keys [runtime]}]
  (run-install! runtime 'ct.spools.executors.subagent/install! :subagent))

(defn reconcile-help-transform
  "Elect the batteries reference help transform for this canonical world.

  Batteries exports its default transform but never auto-registers it, so this
  world opts in as trusted config (DELTA-Dtf-002.D1). `--json` always bypasses
  the slot, so a broken transform never bricks help (DELTA-Dtf-001.CC4). The
  batteries namespace ships on the classpath and is loaded by its module before
  this reconcile runs, so the transform value resolves without a synced load."
  [{:keys [runtime]}]
  (current/with-runtime runtime
    (help-transform/register-default-help-transform!
     runtime
     {:transform @(requiring-resolve 'skein.spools.batteries/default-help-transform)
      :owner 'skein.spools.batteries}))
  {:reconciled :help-transform})
