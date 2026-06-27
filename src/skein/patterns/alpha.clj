(ns skein.patterns.alpha
  (:require [skein.client :as client]
            [skein.weaver.runtime :as runtime]))

(defn- call-daemon [op & args]
  (if-let [rt @runtime/current-runtime]
    (apply (requiring-resolve (symbol "skein.weaver.api" (name op))) rt args)
    (apply client/call-world ((requiring-resolve 'skein.repl/connected-config-dir)) {} op args)))

(defn register-pattern!
  "Register a weaver-memory weave pattern.

  Pattern names are simple names. `fn-sym` must be a fully qualified function
  symbol loadable in the weaver JVM. `input-spec` is a clojure.spec name used
  for pre-invocation validation and caller explanation. Routes directly when
  called inside the weaver JVM, or through the connected helper REPL world."
  [name fn-sym input-spec]
  (call-daemon :register-pattern! name fn-sym input-spec))

(defn patterns
  "Return serializable weaver-memory pattern registry entries."
  []
  (call-daemon :patterns))

(defn pattern
  "Return one registered pattern entry, or fail loudly if missing."
  [name]
  (call-daemon :resolve-pattern name))

(defn explain
  "Return caller guidance for a registered pattern's input contract."
  [name]
  (call-daemon :pattern-explain name))

(defn weave!
  "Invoke a registered pattern with input data and atomically create its strand batch."
  [name input]
  (call-daemon :weave! name input))
