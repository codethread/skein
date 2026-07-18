(ns skein.api.graph.alpha
  "Explicit-runtime API for the named-query registry, query selection, strand hydration, graph traversal, and burn.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns the blessed named-query registry surface and its
  validation, ad hoc and registered query id selection, strand hydration by id,
  relation-scoped traversal, edge adjacency, and burn with its pre-commit gate
  and event fanout. The query compiler lives in `skein.core.query`, the SQL
  engine in `skein.core.db`, and the shared lifecycle and dispatch plumbing in
  `skein.core.weaver.*`."
  (:require [next.jdbc :as jdbc]
            [skein.core.db :as db]
            [skein.core.query :as query]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :as lifecycle]))

(defn- validated-query-entry [[query-name query-def]]
  [(query/canonical-query-name query-name)
   (query/validate-query-def! query-def)])

(defn register-query!
  "Register a named query definition and return its canonical API shape."
  [runtime query-name query-def]
  (let [entry (validated-query-entry [query-name query-def])]
    (swap! (access/query-registry runtime) conj entry)
    (into {} [entry])))

(defn queries
  "Return registered query definitions keyed by canonical string name."
  [runtime]
  (into (sorted-map) @(access/query-registry runtime)))

(defn resolve-query
  "Return the registered query definition for a simple symbol or keyword name."
  [runtime query-name]
  (query/query-def @(access/query-registry runtime) query-name))

(defn- query-where [query-def]
  (if (map? query-def)
    (:where query-def)
    query-def))

(defn- query-details-entry [[name query-def]]
  {:name name
   :params (if (map? query-def) (vec (:params query-def)) [])
   :referenced-params (query/referenced-params (query-where query-def))})

(defn query-explain
  "Describe a registered query definition and how CLI callers invoke it."
  [runtime query-name]
  (let [query-def (resolve-query runtime query-name)
        name (query/query-lookup-name query-name)
        where (query-where query-def)]
    (assoc (query-details-entry [name query-def])
           :where where
           :definition query-def
           :where-form (pr-str where)
           :definition-form (pr-str query-def)
           :summary "Invoke this query with `strand list --query <name>` or `strand ready --query <name>` and pass runtime values with repeated `--param key=value` arguments.")))

(defn query-ids
  "Return strand ids matching an ad hoc query definition or registered query name."
  [runtime query-or-name params]
  (let [query-def (if (or (vector? query-or-name) (map? query-or-name))
                    query-or-name
                    (resolve-query runtime query-or-name))]
    (db/query-strand-ids (access/ds runtime) query-def params)))

(defn burn-by-ids!
  "Delete strands by id and enqueue burn events for removed rows."
  ([runtime ids]
   (burn-by-ids! runtime ids (lifecycle/request-context :burn)))
  ([runtime ids req-ctx]
   (let [requested-ids (vec ids)
         {:keys [before result]} (jdbc/with-transaction [tx (access/ds runtime)]
                                   (let [before (access/normalize (db/strands-by-ids tx requested-ids))]
                                     (lifecycle/run-validation-hooks! runtime
                                                                      :strand/burn-before-commit
                                                                      (merge req-ctx
                                                                             {:mutation/operation :strand/burn
                                                                              :strand/requested-ids requested-ids
                                                                              :strand/before before}))
                                     {:before before
                                      :result (db/burn-by-ids! tx requested-ids)}))]
     (dispatch/enqueue! runtime (assoc (lifecycle/event-base :strand/burned)
                                       :strand/requested-ids requested-ids
                                       :strand/burned-ids (:burned result)
                                       :strand/before before))
     result)))

(defn strands-by-ids
  "Return normalized strands for ids, preserving first-seen input order."
  [runtime ids]
  (access/normalize (db/strands-by-ids (access/ds runtime) ids)))

(defn ancestor-root-ids
  "Return ancestor root ids reachable from `seed-ids`."
  ([runtime seed-ids]
   (ancestor-root-ids runtime seed-ids {}))
  ([runtime seed-ids opts]
   (db/ancestor-root-ids (access/ds runtime) seed-ids opts)))

(defn subgraph
  "Return a normalized strand subgraph rooted at `root-ids`."
  ([runtime root-ids]
   (subgraph runtime root-ids {}))
  ([runtime root-ids opts]
   (let [{:keys [strands edges] :as result} (db/subgraph (access/ds runtime) root-ids opts)]
     (assoc result
            :strands (access/normalize strands)
            :edges (access/normalize edges)))))

(defn incoming-edges
  "Return normalized `edge-type` edges whose target is one of `to-ids`.

  One indexed lookup for a strand's parents/annotators; no graph traversal.
  Adjacency is lenient: an id absent from storage yields no rows rather than a
  missing-id error (unlike subgraph/ancestor-root-ids seeds)."
  [runtime to-ids edge-type]
  (access/normalize (db/incoming-edges (access/ds runtime) to-ids edge-type)))

(defn outgoing-edges
  "Return normalized `edge-type` edges whose source is one of `from-ids`.

  One indexed lookup for a strand's children; no graph traversal. Lenient
  adjacency: an absent id yields no rows rather than a missing-id error."
  [runtime from-ids edge-type]
  (access/normalize (db/outgoing-edges (access/ds runtime) from-ids edge-type)))
