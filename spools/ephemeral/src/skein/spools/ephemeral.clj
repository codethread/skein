(ns skein.spools.ephemeral
  "Helpers for temporary, parent-owned work strands.

  This namespace is intentionally authorable example code: it composes the
  documented weaver, graph, and current helper surfaces and owns no privileged
  loader/config/runtime implementation."
  (:require [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]))

(defn add
  "Create an ephemeral strand under parent-id.

  The strand is persistent, carries `:attr ephemeral/entry \"true\"`, and hangs
  off a parent-of edge from the parent. It can be burned later with
  `burn-all!`."
  ([parent-id title]
   (add parent-id title {}))
  ([parent-id title attributes]
   (let [rt (current/runtime)
         strand (weaver/add rt {:title title
                                :attributes (merge {:ephemeral/entry "true"} attributes)})]
     (weaver/update rt parent-id {:edges [{:type "parent-of" :to (:id strand)}]})
     strand)))

(def query
  "Query form selecting active ephemeral strands."
  [:and [:= [:attr "ephemeral/entry"] "true"] [:= :state "active"]])

(defn ids
  "Return active ephemeral strand ids."
  []
  (graph/query-ids (current/runtime) query {}))

(defn burn-all!
  "Burn all active ephemeral strands."
  []
  (let [entry-ids (ids)]
    (if (seq entry-ids)
      (graph/burn-by-ids! (current/runtime) entry-ids)
      {:burned [] :count 0})))

(def ^:private ephemeral-namespace-declaration
  "The ephemeral-owned `ephemeral/*` attribute namespace stamped onto scratch
  strands by `add`. `:keys` is advisory."
  {:kind :attr-namespace
   :name "ephemeral"
   :owner :skein/spools-ephemeral
   :keys ["ephemeral/entry"]
   :doc "Marker attribute on temporary parent-owned strands created by skein.spools.ephemeral/add."})

(defn install!
  "Install ephemeral strand helpers into the active weaver.

  Declares the `ephemeral` attribute namespace this spool owns and returns the
  installation metadata: the marker attribute plus the `add`/`burn` fns as a
  symbol map."
  []
  (vocab/declare! (current/runtime) ephemeral-namespace-declaration)
  {:installed true
   :namespace 'skein.spools.ephemeral
   :ephemeral {:attribute :ephemeral/entry
               :add 'skein.spools.ephemeral/add
               :burn 'skein.spools.ephemeral/burn-all!}})
