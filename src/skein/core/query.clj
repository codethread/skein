(ns skein.core.query
  "Compile trusted EDN query definitions into SQL fragments for strand selection.

  Query definitions are data-shaped expressions used by the daemon and REPL query
  registry. Invalid query forms fail loudly with ex-info data rather than being
  coerced into broad or empty SQL predicates."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.core.specs :as specs]))

(def ^:private field-columns
  {:id "id"
   :title "title"
   :state "state"
   :created_at "created_at"
   :updated_at "updated_at"})

(def ^:dynamic ^:private *strand-alias* "t")
(def ^:dynamic ^:private *allow-edge-predicates* true)
(def ^:dynamic ^:private *validating-query-def* false)

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- attr-segment-name [segment]
  (cond
    (string? segment) segment
    (keyword? segment) (subs (str segment) 1)
    :else (fail! "Attribute path segments must be keywords or strings" {:segment segment})))

(defn- quote-json-path-segment [segment]
  (let [segment-name (attr-segment-name segment)]
    (when (str/blank? segment-name)
      (fail! "Attribute path segments must not be blank" {:segment segment}))
    (str ".\"" (str/escape segment-name {\\ "\\\\" \" "\\\""}) "\"")))

(defn- attr-path [segments]
  (when-not (seq segments)
    (fail! "Attribute path must contain at least one segment" {:path segments}))
  (str "$" (str/join (map quote-json-path-segment segments))))

(defn- compile-field [field]
  (cond
    (contains? field-columns field) {:sql (str *strand-alias* "." (field-columns field)) :params []}

    (and (vector? field) (= :attr (first field)))
    {:sql (str "json_extract(" *strand-alias* ".attributes, ?)") :params [(attr-path (rest field))]}

    :else
    (fail! "Unknown query field" {:field field :allowed (keys field-columns)})))

(defn- param-value [params [_ k]]
  (when-not (keyword? k)
    (fail! "Query parameter references must use keyword names" {:param k}))
  (if (contains? params k)
    (get params k)
    (fail! "Missing query parameter" {:param k :provided (keys params)})))

(declare compile-expr)

(defn- compile-comparison [op field value params]
  (let [{field-sql :sql field-params :params} (compile-field field)
        value (if (and (vector? value) (= :param (first value)))
                (param-value params value)
                value)]
    {:sql (str field-sql " " op " ?")
     :params (conj (vec field-params) value)}))

(defn- join-compiled [operator compiled]
  {:sql (str "(" (str/join (str " " operator " ") (map :sql compiled)) ")")
   :params (vec (mapcat :params compiled))})

(defn- relation-value [relation params]
  (let [relation (if (and (vector? relation) (= :param (first relation)))
                   (if *validating-query-def*
                     "skein-query-param"
                     (param-value params relation))
                   relation)]
    (when-not (s/valid? ::specs/edge-type relation)
      (fail! "Edge predicate relation must be a valid relation name" {:relation relation}))
    relation))

(defn- compile-edge [direction relation endpoint-query params]
  (when-not *allow-edge-predicates*
    (fail! "Endpoint queries must not contain nested edge predicates" {:query endpoint-query}))
  (let [relation (relation-value relation params)
        [endpoint-alias join-clause candidate-clause]
        (case direction
          :out ["target" "target.id = e.to_strand_id" (str "e.from_strand_id = " *strand-alias* ".id")]
          :in ["source" "source.id = e.from_strand_id" (str "e.to_strand_id = " *strand-alias* ".id")])
        endpoint (binding [*strand-alias* endpoint-alias
                           *allow-edge-predicates* false]
                   (compile-expr endpoint-query params))]
    {:sql (str "EXISTS (SELECT 1 FROM strand_edges e "
               "JOIN strands " endpoint-alias " ON " join-clause " "
               "WHERE " candidate-clause " AND e.edge_type = ? AND " (:sql endpoint) ")")
     :params (vec (cons relation (:params endpoint)))}))

(defn- compile-expr
  ([expr] (compile-expr expr {}))
  ([expr params]
   (when-not (vector? expr)
     (fail! "Query expression must be an EDN vector" {:expr expr}))
   (let [[op & args] expr]
     (case op
       :and (do
              (when (empty? args) (fail! ":and requires at least one child expression" {:expr expr}))
              (join-compiled "AND" (map #(compile-expr % params) args)))
       :or (do
             (when (empty? args) (fail! ":or requires at least one child expression" {:expr expr}))
             (join-compiled "OR" (map #(compile-expr % params) args)))
       :not (do
              (when-not (= 1 (count args)) (fail! ":not requires exactly one child expression" {:expr expr}))
              (let [compiled (compile-expr (first args) params)]
                {:sql (str "(NOT " (:sql compiled) ")") :params (:params compiled)}))
       := (do (when-not (= 2 (count args)) (fail! ":= requires field and value" {:expr expr}))
              (compile-comparison "=" (first args) (second args) params))
       :!= (do (when-not (= 2 (count args)) (fail! ":!= requires field and value" {:expr expr}))
               (compile-comparison "<>" (first args) (second args) params))
       :< (do (when-not (= 2 (count args)) (fail! ":< requires field and value" {:expr expr}))
              (compile-comparison "<" (first args) (second args) params))
       :<= (do (when-not (= 2 (count args)) (fail! ":<= requires field and value" {:expr expr}))
               (compile-comparison "<=" (first args) (second args) params))
       :> (do (when-not (= 2 (count args)) (fail! ":> requires field and value" {:expr expr}))
              (compile-comparison ">" (first args) (second args) params))
       :>= (do (when-not (= 2 (count args)) (fail! ":>= requires field and value" {:expr expr}))
               (compile-comparison ">=" (first args) (second args) params))
       :in (do
             (when-not (= 2 (count args)) (fail! ":in requires field and values" {:expr expr}))
             (let [{field-sql :sql field-params :params} (compile-field (first args))
                   values (let [v (second args)]
                            (if (and (vector? v) (= :param (first v)))
                              (param-value params v)
                              v))]
               (when-not (and (coll? values) (seq values))
                 (fail! ":in values must be a non-empty collection" {:values values}))
               {:sql (str field-sql " IN (" (str/join ", " (repeat (count values) "?")) ")")
                :params (vec (concat field-params values))}))
       :exists (do
                 (when-not (= 1 (count args)) (fail! ":exists requires one field" {:expr expr}))
                 (let [compiled (compile-field (first args))]
                   {:sql (str (:sql compiled) " IS NOT NULL") :params (:params compiled)}))
       :missing (do
                  (when-not (= 1 (count args)) (fail! ":missing requires one field" {:expr expr}))
                  (let [compiled (compile-field (first args))]
                    {:sql (str (:sql compiled) " IS NULL") :params (:params compiled)}))
       :edge/out (do
                   (when-not (= 2 (count args)) (fail! ":edge/out requires relation and target query" {:expr expr}))
                   (compile-edge :out (first args) (second args) params))
       :edge/in (do
                  (when-not (= 2 (count args)) (fail! ":edge/in requires relation and source query" {:expr expr}))
                  (compile-edge :in (first args) (second args) params))
       (fail! "Unknown query operator" {:operator op :expr expr})))))

(defn read-edn-file
  "Read exactly one EDN form from path.

  Throws ex-info when the file is empty or contains trailing forms after the
  first value."
  [path]
  (let [eof ::eof]
    (with-open [r (io/reader (io/file path))]
      (let [reader (java.io.PushbackReader. r)
            value (edn/read {:eof eof} reader)]
        (when (= eof value)
          (fail! "EDN file must contain exactly one form" {:path path}))
        (when-not (= eof (edn/read {:eof eof} reader))
          (fail! "EDN file must contain exactly one form" {:path path}))
        value))))

(defn canonical-query-name
  "Return the registry key string for a simple query symbol or keyword.

  Query names are intentionally unqualified handles. Throws ex-info for namespaced,
  blank, or non-symbol/non-keyword names."
  [query-name]
  (let [canonical-name (cond
                         (and (or (symbol? query-name) (keyword? query-name))
                              (nil? (namespace query-name))) (name query-name)
                         :else (fail! "Query names must be simple symbols or keywords" {:query query-name}))]
    (when (str/blank? canonical-name)
      (fail! "Query names must not be blank" {:query query-name}))
    canonical-name))

(defn query-lookup-name
  "Return the canonical lookup key for a query name from REPL or CLI input.

  String names are trimmed and may use a leading `:` for keyword-style CLI input;
  symbol and keyword names use the registry-name rules unchanged."
  [query-name]
  (if (string? query-name)
    (let [trimmed (str/trim query-name)
          canonical-name (if (str/starts-with? trimmed ":")
                           (subs trimmed 1)
                           trimmed)]
      (when (str/blank? canonical-name)
        (fail! "Query names must not be blank" {:query query-name}))
      (when (str/includes? canonical-name "/")
        (fail! "Query names must be unqualified" {:query query-name}))
      canonical-name)
    (canonical-query-name query-name)))

(defn- registry-query-name [query-name]
  (if (string? query-name)
    query-name
    (canonical-query-name query-name)))

(defn query-def
  "Return a query definition from registry by simple symbol or keyword name.

  Registry keys may be strings, simple symbols, or simple keywords. Throws ex-info
  with available names when no matching definition exists."
  [registry query-name]
  (let [canonical-name (query-lookup-name query-name)
        normalized-registry (update-keys registry registry-query-name)]
    (or (get normalized-registry canonical-name)
        (fail! "Query not found" {:query query-name
                                  :canonical-query canonical-name
                                  :available (sort (keys normalized-registry))}))))

(defn- declared-param-names [query-def]
  (let [declared (:params query-def)]
    (when-not (or (nil? declared) (sequential? declared))
      (fail! "Query :params must be a sequential collection of keyword names" {:params declared}))
    (when-not (every? keyword? declared)
      (fail! "Query :params must be keyword names" {:params declared}))
    (set declared)))

(defn query-expr
  "Return the query expression for a vector or map query definition.

  Map definitions must provide `:where`; when they declare `:params`, provided
  runtime params must be a subset of those declared keyword names. Throws ex-info
  for malformed definitions or unknown params."
  [query-def params]
  (cond
    (vector? query-def) query-def
    (map? query-def) (do
                       (let [declared (declared-param-names query-def)
                             provided (set (keys params))]
                         (when-let [unknown (seq (remove declared provided))]
                           (fail! "Unknown query parameters" {:unknown (vec unknown)})))
                       (or (:where query-def)
                           (fail! "Query map must include :where" {:query query-def})))
    :else (fail! "Query definition must be a vector expression or map" {:query query-def})))

(defn referenced-params
  "Return ordered distinct `[:param kw]` references from a query where expression.

  Walks the query DSL shape without compiling SQL. Parameter references in
  comparison values, `:in` values, edge relation positions, and nested endpoint
  queries are all reported in first-seen order. Literal EDN values are not
  searched. Throws ex-info when a recognized `:param` position does not use a
  keyword name."
  [where-expr]
  (letfn [(param-name [form]
            (when (and (vector? form) (= :param (first form)))
              (let [[_ param-name] form]
                (when-not (keyword? param-name)
                  (fail! "Query parameter references must use keyword names" {:param param-name}))
                param-name)))
          (refs [expr]
            (when (vector? expr)
              (let [[op & args] expr]
                (case op
                  (:and :or) (mapcat refs args)
                  :not (refs (first args))
                  (:= :!= :< :<= :> :>= :in) (keep param-name [(second args)])
                  (:edge/out :edge/in) (concat (keep param-name [(first args)])
                                               (refs (second args)))
                  []))))]
    (vec (distinct (refs where-expr)))))

(declare compile-query)

(defn validate-query-def!
  "Validate that query-def compiles, then return it unchanged.

  Used when loading trusted query registry definitions so malformed query data
  fails at registration time. Parameter references are substituted with sentinel
  values during validation."
  [query-def]
  (let [params (if (map? query-def)
                 (zipmap (declared-param-names query-def) (repeat ["skein-query-param"]))
                 {})]
    (binding [*validating-query-def* true]
      (compile-query query-def params)))
  query-def)

(defn compile-query
  "Compile a query definition into a SQL predicate fragment.

  Accepts either a raw expression vector or a map containing `:where` and optional
  declared `:params`. Runtime params resolve `[:param :name]` references."
  [query-def params]
  (compile-expr (query-expr query-def params) params))
