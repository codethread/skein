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
  batteries namespace is loaded from its approved spool by the guarded module
  before this reconcile runs, so the transform value resolves from that synced
  provider."
  [{:keys [runtime]}]
  (current/with-runtime runtime
    (help-transform/register-default-help-transform!
     runtime
     {:transform @(requiring-resolve 'skein.spools.batteries/default-help-transform)
      :owner 'skein.spools.batteries}))
  {:reconciled :help-transform})

;; Entry-point declaration under the uniform `def spool` convention
;; (PROP-Dsp-001.G1/Q4): this file module is not a spool, but one name and one
;; rule cover every module-loadable namespace, so its `:reconcile` entry point
;; lives here instead of in the init.clj declaration. Unqualified symbols
;; resolve against this file's namespace.
(def spool
  {:reconcile 'reconcile-help-transform})
