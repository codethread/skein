(ns skein.api.graph.alpha
  "Explicit-runtime API for the named-query registry, query selection, strand
  hydration, graph traversal, and burn.

  This namespace owns the blessed named-query registry surface and its
  validation, ad hoc and registered query id selection, strand hydration by
  id, relation-scoped traversal, edge adjacency, and burn with its pre-commit
  gate and event fanout — and reads in that order. The query compiler lives
  in `skein.core.query`, the SQL engine in `skein.core.db`, and the shared
  lifecycle and dispatch plumbing in `skein.core.weaver.*`; query-definition
  shape mechanics are plumbing in `skein.api.graph.internal.query-defs`.

  Callers own runtime selection and pass the target weaver runtime as the
  first argument to every function here."
  (:require [clojure.spec.alpha :as s]
            [next.jdbc :as jdbc]
            [skein.api.graph.internal.query-defs :as query-defs]
            [skein.core.db :as db]
            [skein.core.query :as query]
            [skein.core.specs :as specs]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :as lifecycle]))

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

;; A strand-id collection argument; ids are non-blank strings.
(s/def ::ids (s/coll-of ::specs/id :kind coll?))

;; --- named-query registry ---------------------------------------------------

(defn register-query!
  "Register a named query definition and return its canonical API shape.

  Canonicalizes the simple symbol or keyword name and compiles the
  definition once before it reaches the registry, so malformed query data
  fails loudly at registration time; `skein.core.query` is the grammar
  authority for definitions."
  [runtime query-name query-def]
  (let [entry (query-defs/validated-entry query-name query-def)]
    (swap! (access/query-registry runtime) conj entry)
    (into {} [entry])))

(s/fdef register-query!
  :args (s/cat :runtime ::runtime :query-name any? :query-def any?)
  :ret map?)

(defn queries
  "Return registered query definitions keyed by canonical string name."
  [runtime]
  (into (sorted-map) @(access/query-registry runtime)))

(s/fdef queries
  :args (s/cat :runtime ::runtime)
  :ret map?)

(defn resolve-query
  "Return the registered query definition for a simple symbol or keyword name.

  Throws ex-info listing the available names when no definition matches."
  [runtime query-name]
  (query/query-def @(access/query-registry runtime) query-name))

(s/fdef resolve-query
  :args (s/cat :runtime ::runtime :query-name any?))

(defn query-explain
  "Describe a registered query definition and how CLI callers invoke it."
  [runtime query-name]
  (let [query-def (resolve-query runtime query-name)
        lookup-name (query/query-lookup-name query-name)
        where (query-defs/where-clause query-def)]
    (assoc (query-defs/details-entry [lookup-name query-def])
           :where where
           :definition query-def
           :where-form (pr-str where)
           :definition-form (pr-str query-def)
           :summary (str "Invoke this query with `strand list --query <name>` or"
                         " `strand ready --query <name>` and pass runtime values"
                         " with repeated `--param key=value` arguments."))))

(s/fdef query-explain
  :args (s/cat :runtime ::runtime :query-name any?)
  :ret map?)

;; --- query selection --------------------------------------------------------

(defn query-ids
  "Return strand ids matching an ad hoc query definition or registered query name."
  [runtime query-or-name params]
  (let [query-def (if (or (vector? query-or-name) (map? query-or-name))
                    query-or-name
                    (resolve-query runtime query-or-name))]
    (db/query-strand-ids (access/ds runtime) query-def params)))

(s/fdef query-ids
  :args (s/cat :runtime ::runtime :query-or-name any? :params any?)
  :ret coll?)

;; --- strand hydration -------------------------------------------------------

(defn strands-by-ids
  "Return normalized strands for ids, preserving first-seen input order."
  [runtime ids]
  (access/normalize (db/strands-by-ids (access/ds runtime) ids)))

(s/fdef strands-by-ids
  :args (s/cat :runtime ::runtime :ids ::ids)
  :ret coll?)

;; --- graph traversal --------------------------------------------------------

(defn ancestor-root-ids
  "Return ancestor root ids reachable from `seed-ids`."
  ([runtime seed-ids]
   (ancestor-root-ids runtime seed-ids {}))
  ([runtime seed-ids opts]
   (db/ancestor-root-ids (access/ds runtime) seed-ids opts)))

(s/fdef ancestor-root-ids
  :args (s/or :default (s/cat :runtime ::runtime :seed-ids ::ids)
              :with-opts (s/cat :runtime ::runtime :seed-ids ::ids :opts map?))
  :ret coll?)

(defn subgraph
  "Return a normalized strand subgraph rooted at `root-ids`."
  ([runtime root-ids]
   (subgraph runtime root-ids {}))
  ([runtime root-ids opts]
   (let [{:keys [strands edges] :as result} (db/subgraph (access/ds runtime) root-ids opts)]
     (assoc result
            :strands (access/normalize strands)
            :edges (access/normalize edges)))))

(s/fdef subgraph
  :args (s/or :default (s/cat :runtime ::runtime :root-ids ::ids)
              :with-opts (s/cat :runtime ::runtime :root-ids ::ids :opts map?))
  :ret map?)

;; --- edge adjacency ---------------------------------------------------------

(defn incoming-edges
  "Return normalized `edge-type` edges whose target is one of `to-ids`.

  One indexed lookup for a strand's parents/annotators; no graph traversal.
  Adjacency is lenient: an id absent from storage yields no rows rather than
  a missing-id error (unlike subgraph/ancestor-root-ids seeds)."
  [runtime to-ids edge-type]
  (access/normalize (db/incoming-edges (access/ds runtime) to-ids edge-type)))

(s/fdef incoming-edges
  :args (s/cat :runtime ::runtime :to-ids ::ids :edge-type ::specs/edge-type)
  :ret coll?)

(defn outgoing-edges
  "Return normalized `edge-type` edges whose source is one of `from-ids`.

  One indexed lookup for a strand's children; no graph traversal. Lenient
  adjacency: an absent id yields no rows rather than a missing-id error."
  [runtime from-ids edge-type]
  (access/normalize (db/outgoing-edges (access/ds runtime) from-ids edge-type)))

(s/fdef outgoing-edges
  :args (s/cat :runtime ::runtime :from-ids ::ids :edge-type ::specs/edge-type)
  :ret coll?)

;; --- burn -------------------------------------------------------------------

(defn burn-by-ids!
  "Delete strands by id and enqueue burn events for removed rows.

  Loads the before-images and deletes inside one transaction, running the
  `:strand/burn-before-commit` validation gate between the two so a rejecting
  hook rolls the whole burn back; then enqueues the `:strand/burned` event
  carrying requested ids, burned ids, and before-images."
  ([runtime ids]
   (burn-by-ids! runtime ids (lifecycle/request-context :burn)))
  ([runtime ids req-ctx]
   (let [requested-ids (vec ids)
         {:keys [before result]}
         (jdbc/with-transaction [tx (access/ds runtime)]
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

(s/fdef burn-by-ids!
  :args (s/or :default (s/cat :runtime ::runtime :ids ::ids)
              :with-ctx (s/cat :runtime ::runtime :ids ::ids :req-ctx map?))
  :ret map?)
