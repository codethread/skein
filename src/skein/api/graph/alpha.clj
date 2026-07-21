(ns skein.api.graph.alpha
  "Explicit-runtime API for the named-query registry, query selection, strand
  hydration, graph traversal, and burn.

  This namespace owns the blessed named-query registry surface and its
  validation, ad hoc and registered query id selection, strand hydration by
  id, relation-scoped traversal, edge adjacency, and burn with its pre-commit
  gate and event fanout — and reads in that order. The query compiler lives
  in `skein.core.query`, the SQL engine in `skein.core.db`, and the shared
  lifecycle and dispatch plumbing in `skein.core.weaver.*`.

  Callers own runtime selection and pass the target weaver runtime as the
  first argument to every function here."
  (:require [clojure.spec.alpha :as s]
            [next.jdbc :as jdbc]
            [skein.api.format.alpha :as format-alpha]
            [skein.core.db :as db]
            [skein.core.query :as query]
            [skein.core.specs :as specs]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :as lifecycle]))

(declare validated-entry where-clause details-entry)

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

;; A strand-id collection argument; ids are non-blank strings.
(s/def ::ids (s/coll-of ::specs/id :kind coll?))

;; --- named-query registry ---------------------------------------------------

;; Registration names are simple symbols or keywords; read-side lookups also
;; accept strings. The definition grammar itself is owned by
;; `skein.core.query`; these specs encode only the seam's top-level shape —
;; a bare where vector or a `:where`/`:params` map, registered under its
;; canonical string name.
(s/def ::query-name (s/or :symbol simple-symbol? :keyword simple-keyword?))
(s/def ::query-lookup (s/or :name ::query-name :string string?))
(s/def ::query-def (s/or :where-vector vector? :detailed map?))
(s/def ::query-registry (s/map-of string? ::query-def))

(defn register-query!
  "Register a named query definition and return its canonical API shape.

  Canonicalizes the simple symbol or keyword name and validates that the
  definition compiles before it reaches the registry, so malformed query
  data fails loudly at registration time; `skein.core.query` is the
  grammar authority for definitions and compiles the stored definition at
  each use."
  [runtime query-name query-def]
  (let [[canonical-name definition :as entry] (validated-entry query-name query-def)]
    (core-registry/put-entry! (access/query-store runtime) canonical-name definition)
    (into {} [entry])))

(s/fdef register-query!
  :args (s/cat :runtime ::runtime :query-name ::query-name :query-def ::query-def)
  :ret ::query-registry)

(defn queries
  "Return registered query definitions keyed by canonical string name."
  [runtime]
  (into (sorted-map) @(access/query-registry runtime)))

(s/fdef queries
  :args (s/cat :runtime ::runtime)
  :ret ::query-registry)

(defn resolve-query
  "Return the registered query definition for a simple symbol or keyword name.

  Throws ex-info listing the available names when no definition matches."
  [runtime query-name]
  (query/query-def @(access/query-registry runtime) query-name))

(s/fdef resolve-query
  :args (s/cat :runtime ::runtime :query-name ::query-lookup)
  :ret ::query-def)

;; The explain projection publishes unqualified keys for CLI/JSON callers.
(s/def ::name string?)
(s/def ::params (s/coll-of keyword? :kind vector?))
(s/def ::referenced-params (s/coll-of keyword? :kind vector?))
(s/def ::where vector?)
(s/def ::definition ::query-def)
(s/def ::where-form string?)
(s/def ::definition-form string?)
(s/def ::summary string?)
(s/def ::query-explanation
  (s/keys :req-un [::name ::params ::referenced-params ::where ::definition
                   ::where-form ::definition-form ::summary]))

(defn query-explain
  "Describe a registered query definition and how CLI callers invoke it."
  [runtime query-name]
  (let [query-def (resolve-query runtime query-name)
        lookup-name (query/query-lookup-name query-name)
        where (where-clause query-def)]
    (assoc (details-entry [lookup-name query-def])
           :where where
           :definition query-def
           :where-form (pr-str where)
           :definition-form (pr-str query-def)
           :summary (format-alpha/reflow
                     "|Invoke this query with `strand list --query <name>` or
                      |`strand ready --query <name>` and pass runtime values
                      |with repeated `--param key=value` arguments."))))

(s/fdef query-explain
  :args (s/cat :runtime ::runtime :query-name ::query-lookup)
  :ret ::query-explanation)

;; --- query selection --------------------------------------------------------

(defn query-ids
  "Return strand ids matching an ad hoc query definition or registered query name."
  [runtime query-or-name params]
  (let [query-def (if (or (vector? query-or-name) (map? query-or-name))
                    query-or-name
                    (resolve-query runtime query-or-name))]
    (db/query-strand-ids (access/ds runtime) query-def params)))

(s/fdef query-ids
  :args (s/cat :runtime ::runtime
               :query-or-name (s/or :ad-hoc ::query-def :registered ::query-lookup)
               :params (s/nilable map?))
  :ret (s/coll-of ::specs/id))

;; --- strand hydration -------------------------------------------------------

(defn strands-by-ids
  "Return normalized strands for ids, preserving first-seen input order."
  [runtime ids]
  (access/normalize (db/strands-by-ids (access/ds runtime) ids)))

(s/fdef strands-by-ids
  :args (s/cat :runtime ::runtime :ids ::ids)
  :ret (s/coll-of map?))

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
  :ret (s/coll-of ::specs/id))

;; A subgraph result carries normalized strand rows and edge rows.
(s/def ::strands (s/coll-of map?))
(s/def ::edges (s/coll-of map?))

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
  :ret (s/keys :req-un [::strands ::edges]))

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
  :ret ::edges)

(defn outgoing-edges
  "Return normalized `edge-type` edges whose source is one of `from-ids`.

  One indexed lookup for a strand's children; no graph traversal. Lenient
  adjacency: an absent id yields no rows rather than a missing-id error."
  [runtime from-ids edge-type]
  (access/normalize (db/outgoing-edges (access/ds runtime) from-ids edge-type)))

(s/fdef outgoing-edges
  :args (s/cat :runtime ::runtime :from-ids ::ids :edge-type ::specs/edge-type)
  :ret ::edges)

;; --- burn -------------------------------------------------------------------

;; A burn result names the removed ids and their count.
(s/def ::burned (s/coll-of ::specs/id :kind vector?))
(s/def ::count nat-int?)

(defn burn-by-ids!
  "Delete strands by id and enqueue burn events for removed rows.

  Loads the before-images and deletes inside one transaction, running the
  `:strand/burn-before-commit` validation gate between the two so a rejecting
  hook rolls the whole burn back; then enqueues the `:strand/burned` event
  carrying requested ids, burned ids, and before-images. The `req-ctx` arity
  threads an explicit request-context map (the same shape
  `skein.api.batch.alpha/apply!` accepts) into the gate; the two-argument
  form derives its own burn context."
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
  :ret (s/keys :req-un [::burned ::count]))

;; --- query-definition shape helpers -----------------------------------------
;;
;; A registered query definition is either a bare where vector or a map of
;; `:where` and `:params`; `skein.core.query` is the grammar authority.

(defn- validated-entry
  "Return a `[canonical-name query-def]` registry entry, validated loudly."
  [query-name query-def]
  [(query/canonical-query-name query-name)
   (query/validate-query-def! query-def)])

(defn- where-clause
  "Return the where expression of a bare-vector or map query definition."
  [query-def]
  (if (map? query-def)
    (:where query-def)
    query-def))

(defn- details-entry
  "Return the `:name`/`:params`/`:referenced-params` projection of an entry."
  [[lookup-name query-def]]
  {:name lookup-name
   :params (if (map? query-def) (vec (:params query-def)) [])
   :referenced-params (query/referenced-params (where-clause query-def))})
