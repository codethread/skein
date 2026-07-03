(ns skein.api.current.alpha
  "Isolated convenience facade for reading and scoping the current Skein runtime.

  This namespace is the blessed public facade for trusted in-process config,
  spool, and REPL code that must capture the active weaver runtime explicitly and
  then pass it to `skein.api.*.alpha` functions. It never falls back to client or
  connected REPL state."
  (:require [skein.core.weaver.runtime :as runtime]))

(defn runtime
  "Return the thread-bound or published in-process weaver runtime.

  Trusted startup, reload, and nREPL contexts bind a per-thread runtime. Daemon
  processes also publish one ambient runtime for legacy REPL ergonomics. When
  neither exists, fail loudly."
  []
  (or runtime/*runtime*
      @runtime/current-runtime
      (throw (ex-info "No active Skein weaver runtime" {}))))

(defn with-runtime*
  "Call `thunk` with `runtime` bound as the thread-local ambient runtime.

  Trusted in-process code uses this to scope a chosen runtime for a dynamic
  extent so nested `(runtime)` reads and explicit-runtime callees agree on the
  same runtime without threading it through every call."
  [runtime thunk]
  (when (nil? runtime)
    (throw (ex-info "Cannot scope a nil Skein runtime" {})))
  (runtime/with-runtime-binding runtime thunk))

(defmacro with-runtime
  "Evaluate `body` with `runtime` bound as the thread-local ambient runtime."
  [runtime & body]
  `(with-runtime* ~runtime (fn [] ~@body)))
