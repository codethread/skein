(ns skein.api.graph.internal.query-defs
  "Query-definition shape plumbing for `skein.api.graph.alpha`.

  A registered query definition is either a bare where vector or a map of
  `:where` and `:params`; `skein.core.query` is the grammar authority. This
  namespace holds the entry construction and shape projection mechanics the
  alpha surface composes."
  (:require [skein.core.query :as query]))

(defn validated-entry
  "Return a `[canonical-name query-def]` registry entry, validated loudly.

  Canonicalizes the simple symbol or keyword name and compiles the
  definition once so malformed query data fails at registration time."
  [query-name query-def]
  [(query/canonical-query-name query-name)
   (query/validate-query-def! query-def)])

(defn where-clause
  "Return the where expression of a bare-vector or map query definition."
  [query-def]
  (if (map? query-def)
    (:where query-def)
    query-def))

(defn details-entry
  "Return the `:name`/`:params`/`:referenced-params` projection of an entry."
  [[lookup-name query-def]]
  {:name lookup-name
   :params (if (map? query-def) (vec (:params query-def)) [])
   :referenced-params (query/referenced-params (where-clause query-def))})
