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

(defn burn-by-ids!
  "Burn strands by id through the selected weaver runtime.

  Burning physically deletes each strand and its incident edges. Missing ids fail
  loudly in the weaver operation. Routes directly through the weaver runtime or
  the connected helper REPL world."
  [ids]
  (call-daemon :burn-by-ids ids))

(defn burn-by-id!
  "Burn one strand by id through the selected weaver runtime."
  [id]
  (call-daemon :burn-by-id id))

(defn strands-by-ids
  "Hydrate strands by id through the selected weaver runtime.

  Duplicate ids are collapsed by first occurrence, empty input returns [], and
  missing ids fail loudly in the weaver operation. Routes directly through
  the weaver runtime or the connected helper REPL world."
  [ids]
  (call-daemon :strands-by-ids ids))

(defn ancestor-root-ids
  "Return ancestor root ids for seed ids through the selected weaver runtime.

  Opts may include `:type` for the declared acyclic relation to traverse;
  omitted `:type` defaults to `parent-of`. With opts `:where`, returns topmost
  matching ancestors on each path; without opts, returns graph roots with no
  parent in the traversed relation. Routes directly through the weaver runtime
  or the connected helper REPL world."
  ([seed-ids]
   (call-daemon :ancestor-root-ids seed-ids))
  ([seed-ids opts]
   (call-daemon :ancestor-root-ids seed-ids opts)))

(defn subgraph
  "Return a relation-scoped subgraph for root ids through the selected weaver runtime.

  Opts may include `:type` for the declared acyclic relation to traverse;
  omitted `:type` defaults to `parent-of`. The result shape is
  `{:root-ids [...] :strands [...] :edges [...]}`. Routes directly through the
  weaver runtime or the connected helper REPL world."
  ([root-ids]
   (call-daemon :subgraph root-ids))
  ([root-ids opts]
   (call-daemon :subgraph root-ids opts)))
