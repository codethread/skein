(ns skein.spools.ephemeral
  "Userland helpers for temporary, parent-owned work strands.

  This namespace is intentionally authorable example code: it composes the
  documented explicit-runtime weaver and graph helper surfaces and owns no
  privileged loader/config/runtime implementation."
  (:require [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]))

(defn ephemeral!
  "Create a userland ephemeral strand under parent-id.

  This uses userland attributes, not core :ephemeral lifecycle. The strand is
  persistent with `:attr ephemeral true` and a parent-of edge from the parent.
  It can be burned later with `burn-ephemeral!`."
  ([parent-id title]
   (ephemeral! parent-id title {}))
  ([parent-id title attributes]
   (let [rt (current/runtime)
         strand (weaver/add rt {:title title
                                :attributes (merge {:ephemeral "true"} attributes)})]
     (weaver/update rt parent-id {:edges [{:type "parent-of" :to (:id strand)}]})
     strand)))

(def ephemeral-query
  "Query form selecting active userland ephemeral strands."
  [:and [:= [:attr :ephemeral] "true"] [:= :state "active"]])

(defn ephemeral-ids
  "Return active userland ephemeral strand ids."
  ([]
   (ephemeral-ids {}))
  ([_opts]
   (graph/query-ids (current/runtime) ephemeral-query {})))

(defn burn-ephemeral!
  "Burn all active userland ephemeral strands."
  []
  (let [ids (ephemeral-ids)]
    (if (seq ids)
      (graph/burn-by-ids! (current/runtime) ids)
      {:burned [] :count 0})))

(defn install!
  "Install ephemeral strand helpers into the active weaver."
  []
  {:installed true
   :namespace 'skein.spools.ephemeral
   :ephemeral {:attribute :ephemeral
               :creator 'skein.spools.ephemeral/ephemeral!
               :burner 'skein.spools.ephemeral/burn-ephemeral!}})
