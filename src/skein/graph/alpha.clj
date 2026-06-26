(ns skein.graph.alpha
  (:require [skein.client :as client]
            [skein.weaver.runtime :as runtime]
            [skein.repl :as repl]))

(defn- call-daemon [op & args]
  (if-let [rt @runtime/current-runtime]
    (apply (requiring-resolve (symbol "skein.weaver.api" (name op))) rt args)
    (apply client/call-world (repl/connected-config-dir) {} op args)))

(defn query-ids!
  "Return strand ids matching an ad hoc query definition or weaver-registered query name.

  When called inside the weaver JVM, executes directly against the active weaver
  runtime. When called from a connected helper REPL, routes to the selected
  weaver world from `skein.repl/connect!` / `strand weaver repl`."
  [query params]
  (call-daemon :query-ids query params))

(defn strands-by-ids
  "Hydrate strands by id through the selected weaver runtime.

  Duplicate ids are collapsed by first occurrence, empty input returns [], and
  missing ids fail loudly in the weaver operation. Routes directly through
  the weaver runtime or the connected helper REPL world."
  [ids]
  (call-daemon :strands-by-ids ids))

(defn ancestor-root-ids
  "Return parent-of ancestor root ids for seed ids through the selected weaver runtime.

  With opts `:where`, returns topmost matching ancestors on each path; without
  opts, returns graph roots with no parent-of parent. Routes directly through the
  weaver runtime or the connected helper REPL world."
  [seed-ids opts]
  (call-daemon :ancestor-root-ids seed-ids opts))

(defn subgraph
  "Return a parent-of subgraph for root ids through the selected weaver runtime.

  The result shape is `{:root-ids [...] :strands [...] :edges [...]}`. Routes
  directly through the weaver runtime or the connected helper REPL world."
  [root-ids]
  (call-daemon :subgraph root-ids))
