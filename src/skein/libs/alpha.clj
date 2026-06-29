(ns skein.libs.alpha
  "Connected helper API for trusted runtime library workflows.

  This namespace routes library allowlist, sync, config reload, and module-use
  operations to the selected Skein weaver runtime. Inside a daemon process calls
  use the in-process runtime; from a connected helper REPL they route through the
  active weaver client connection."
  (:refer-clojure :exclude [sync use])
  (:require [skein.client :as client]
            [skein.repl :as repl]
            [skein.weaver.runtime :as runtime]))

(defn- call-daemon [op & args]
  (if-let [rt @runtime/current-runtime]
    (apply (requiring-resolve (symbol "skein.weaver.api" (name op))) rt args)
    (apply client/call-world (repl/connected-config-dir) {} op args)))

(defn approved
  "Return the normalized library allowlist for the selected weaver config dir.

  Reads `libs.edn` through the active runtime and returns `{:libs ...}` with
  canonical local roots. Malformed allowlists fail loudly with ExceptionInfo."
  []
  (call-daemon :approved-libs))

(defn sync!
  "Load approved local roots into the selected weaver runtime.

  Returns `{:libs ...}` with one result per approved library and records the
  results in weaver-lifetime sync state. Structural allowlist errors throw;
  per-library load failures are returned as failed result maps."
  []
  (call-daemon :sync-approved-libs))

(defn syncs
  "Return the selected weaver runtime's most recent approved-library sync state."
  []
  (call-daemon :approved-lib-syncs))

(defn reload!
  "Reload `init.clj` from the selected config dir in the active weaver.

  Clears runtime extension registries before loading and returns the load result
  map. Throws when the selected config dir has no reloadable init file."
  []
  (call-daemon :reload-config!))

(defn use!
  "Activate a weaver-side module and record its use state.

  `key` must be a keyword. `opts` selects exactly one module source with `:ns`
  or `:file`, and may include `:libs`, `:after`, `:call`, and `:required?` gates.
  Returns a loaded, skipped, or failed module-use result map."
  [key opts]
  (call-daemon :use! key opts))

(defn uses
  "Return the selected weaver runtime's module-use registry as data-first maps."
  []
  (call-daemon :uses))

(defn use
  "Return one module-use registry entry from the selected weaver runtime by key."
  [key]
  (call-daemon :use key))
