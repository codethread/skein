(ns atom.plugin.alpha
  (:require [todo.client :as client]
            [todo.daemon.runtime :as runtime]
            [todo.repl :as repl]))

(defn- call-daemon [op & args]
  (if-let [rt @runtime/current-runtime]
    (apply (requiring-resolve (symbol "todo.daemon.api" (name op))) rt args)
    (apply client/call-world (repl/connected-config-dir) {} op args)))

(defn register!
  "Register or replace plugin metadata in the current daemon lifetime."
  [metadata]
  (call-daemon :register-plugin metadata))

(defn plugins
  "Return loaded plugin metadata for the current daemon lifetime."
  []
  (call-daemon :plugins))

(defn plugin
  "Return metadata for one loaded plugin by name, or nil when absent."
  [plugin-name]
  (call-daemon :plugin plugin-name))
