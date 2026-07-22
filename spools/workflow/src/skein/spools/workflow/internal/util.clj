(ns skein.spools.workflow.internal.util
  "Shared low-level validation and ref-normalization primitives for the workflow
  spool's internal concerns.

  These fail loudly (TEN-003) on malformed builder/compile input and normalize
  the keyword/symbol/string step refs the rest of the pipeline threads. Held in
  one leaf namespace so the compile, query, and routing concerns can share them
  without a cycle back through the public story file."
  (:require [clojure.string :as str]
            [skein.api.spool.alpha :refer [fail!]]))

(defn non-blank-string?
  "True when `value` is a non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(defn require-non-blank!
  "Return `value` when it is a non-blank string, else fail loudly citing `path`."
  [value path]
  (when-not (non-blank-string? value)
    (fail! "Workflow value must be a non-blank string" {:path path :value value}))
  value)

(defn require-map!
  "Return `value` when it is a map, else fail loudly citing `path`."
  [value path]
  (when-not (map? value)
    (fail! "Workflow value must be a map" {:path path :value value}))
  value)

(defn require-vector!
  "Return `value` when it is a vector, else fail loudly citing `path`."
  [value path]
  (when-not (vector? value)
    (fail! "Workflow value must be a vector" {:path path :value value}))
  value)

(defn require-keyword!
  "Return `value` when it is a keyword, else fail loudly citing `path`."
  [value path]
  (when-not (keyword? value)
    (fail! "Workflow value must be a keyword" {:path path :value value}))
  value)

(defn normalize-ref
  "Return `value` as a canonical keyword step ref.

  Keywords pass through; symbols and non-blank strings become keywords; anything
  else fails loudly citing `path`."
  [value path]
  (cond
    (keyword? value) value
    (symbol? value) (keyword (name value))
    (non-blank-string? value) (keyword value)
    :else (fail! "Workflow step references must be keywords, symbols, or non-blank strings"
                 {:path path :value value})))
