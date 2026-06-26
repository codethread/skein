(ns skein.views.alpha
  (:require [skein.client :as client]
            [skein.weaver.runtime :as runtime]
            [skein.repl :as repl]))

(defn- call-daemon [op & args]
  (if-let [rt @runtime/current-runtime]
    (apply (requiring-resolve (symbol "skein.weaver.api" (name op))) rt args)
    (apply client/call-world (repl/connected-config-dir) {} op args)))

(defn register-view!
  "Register a daemon-memory view name to a fully qualified daemon-resolvable function symbol.

  Duplicate names replace prior registrations. When called inside the daemon JVM,
  registers directly on the active weaver runtime. When called from a connected
  helper REPL, routes to the selected weaver world from `skein.repl/connect!` /
  `strand weaver repl`; connected users should register functions that are already
  loadable in the daemon JVM."
  [name fn-sym]
  (call-daemon :register-view! name fn-sym))

(defn view!
  "Invoke a registered daemon-side view with params through the selected weaver runtime.

  The daemon resolves the registered function symbol and calls it with
  `{:params params}`. Routes directly daemon-side or through the connected helper
  REPL world."
  [name params]
  (call-daemon :view! name params))

(defn views
  "Return serializable daemon-memory view registry entries through the selected weaver runtime.

  Routes directly daemon-side or through the connected helper REPL world."
  []
  (call-daemon :views))
