(ns atom.libs.alpha
  (:refer-clojure :exclude [sync use])
  (:require [skein.client :as client]
            [skein.weaver.runtime :as runtime]
            [skein.repl :as repl]))

(defn- call-daemon [op & args]
  (if-let [rt @runtime/current-runtime]
    (apply (requiring-resolve (symbol "skein.weaver.api" (name op))) rt args)
    (apply client/call-world (repl/connected-config-dir) {} op args)))

(defn approved
  "Return normalized approved library config from the selected daemon config-dir libs.edn."
  []
  (call-daemon :approved-libs))

(defn sync!
  "Sync approved local roots into the selected weaver runtime and return per-library results."
  []
  (call-daemon :sync-approved-libs))

(defn syncs
  "Return daemon-lifetime approved library sync state."
  []
  (call-daemon :approved-lib-syncs))

(defn use!
  "Activate a daemon-side module and record its use state."
  [key opts]
  (call-daemon :use! key opts))

(defn uses
  "Return daemon-lifetime module-use state."
  []
  (call-daemon :uses))

(defn use
  "Return one daemon-lifetime module-use entry by key."
  [key]
  (call-daemon :use key))
