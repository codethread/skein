(ns module-adapters
  "Repo-owned module reconciliation helper.

  `reconcile-help-transform` is not a branch adapter: it is this canonical
  world's config-election of the batteries reference help transform, kept here
  beside the batteries module ordering so the election has an owner."
  (:require [skein.api.current.alpha :as current]
            [skein.api.runtime.help-transform.alpha :as help-transform]))

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
