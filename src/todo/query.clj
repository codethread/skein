(ns todo.query
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def fields
  {:id "t.id"
   :title "t.title"
   :status "t.status"
   :created_at "t.created_at"
   :updated_at "t.updated_at"
   :final_at "t.final_at"})

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
  (str "$" (apply str (map quote-json-path-segment segments))))

(defn- compile-field [field]
  (cond
    (contains? fields field) {:sql (fields field) :params []}

    (and (vector? field) (= :attr (first field)))
    {:sql "json_extract(t.attributes, ?)" :params [(attr-path (rest field))]}

    :else
    (fail! "Unknown query field" {:field field :allowed (keys fields)})))

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

(defn compile-expr
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
       (fail! "Unknown query operator" {:operator op :expr expr})))))

(defn read-edn-file [path]
  (with-open [r (io/reader (io/file path))]
    (edn/read {:eof nil} (java.io.PushbackReader. r))))

(defn canonical-query-name [query-name]
  (let [canonical-name (cond
                         (and (symbol? query-name) (nil? (namespace query-name))) (name query-name)
                         (and (keyword? query-name) (nil? (namespace query-name))) (name query-name)
                         :else (fail! "Query names must be simple symbols or keywords" {:query query-name}))]
    (when (str/blank? canonical-name)
      (fail! "Query names must not be blank" {:query query-name}))
    canonical-name))

(defn- registry-query-name [query-name]
  (if (string? query-name)
    query-name
    (canonical-query-name query-name)))

(defn query-def [registry query-name]
  (let [canonical-name (canonical-query-name query-name)
        normalized-registry (update-keys registry registry-query-name)]
    (or (get normalized-registry canonical-name)
        (fail! "Query not found" {:query query-name
                                   :canonical-query canonical-name
                                   :available (sort (keys normalized-registry))}))))

(defn query-expr [query-def params]
  (cond
    (vector? query-def) query-def
    (map? query-def) (do
                       (let [declared (set (:params query-def))
                             provided (set (keys params))]
                         (when-not (every? keyword? declared)
                           (fail! "Query :params must be keyword names" {:params (:params query-def)}))
                         (when-let [unknown (seq (remove declared provided))]
                           (fail! "Unknown query parameters" {:unknown (vec unknown)})))
                       (or (:where query-def)
                           (fail! "Query map must include :where" {:query query-def})))
    :else (fail! "Query definition must be a vector expression or map" {:query query-def})))

(declare compile-query)

(defn validate-query-def! [query-def]
  (let [params (if (map? query-def)
                 (zipmap (:params query-def) (repeat "__atom_query_param__"))
                 {})]
    (compile-query query-def params))
  query-def)

(defn compile-query [query-def params]
  (compile-expr (query-expr query-def params) params))
