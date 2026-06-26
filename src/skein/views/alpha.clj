(ns skein.views.alpha
  (:require [skein.client :as client]
            [skein.weaver.runtime :as runtime]
            [skein.repl :as repl]))

(defn- call-daemon [op & args]
  (if-let [rt @runtime/current-runtime]
    (apply (requiring-resolve (symbol "skein.weaver.api" (name op))) rt args)
    (apply client/call-world (repl/connected-config-dir) {} op args)))

(defn register-view!
  "Register a weaver-memory view name to a fully qualified weaver-resolvable function symbol.

  Duplicate names replace prior registrations. When called inside the weaver JVM,
  registers directly on the active weaver runtime. When called from a connected
  helper REPL, routes to the selected weaver world from `skein.repl/connect!` /
  `strand weaver repl`; connected users should register functions that are already
  loadable in the weaver JVM."
  [name fn-sym]
  (call-daemon :register-view! name fn-sym))

(defn view!
  "Invoke a registered weaver-side view with params through the selected weaver runtime.

  The weaver resolves the registered function symbol and calls it with
  `{:params params}`. Routes directly through the weaver runtime or the connected
  helper REPL world."
  [name params]
  (call-daemon :view! name params))

(defn views
  "Return serializable weaver-memory view registry entries through the selected weaver runtime.

  Routes directly through the weaver runtime or the connected helper REPL world."
  []
  (call-daemon :views))
