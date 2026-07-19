(ns skein.api.patterns.internal.registry
  "Pattern-entry construction and validation plumbing for `skein.api.patterns.alpha`."
  (:require [clojure.string :as str]
            [skein.core.query :as query]
            [skein.core.weaver.access :refer [validate-fn-symbol!]]))

(defn canonical-name
  "Return the canonical registry name for a simple symbol or keyword pattern name."
  [pattern-name]
  (query/canonical-query-name pattern-name))

(defn- validate-spec! [spec-name]
  (when-not (or (keyword? spec-name) (symbol? spec-name))
    (throw (ex-info "Pattern input spec must be a keyword or symbol" {:spec spec-name})))
  spec-name)

(defn- validate-doc! [doc]
  (when-not (and (string? doc) (not (str/blank? doc)))
    (throw (ex-info "Pattern doc must be a non-blank string" {:doc doc})))
  doc)

(defn entry
  "Build a validated pattern registry entry; `doc` may be nil for a doc-less entry."
  [pattern-name doc fn-sym input-spec]
  (cond-> {:name (canonical-name pattern-name)
           :fn (validate-fn-symbol! "Pattern" fn-sym)
           :input-spec (validate-spec! input-spec)}
    doc (assoc :doc (validate-doc! doc))))
