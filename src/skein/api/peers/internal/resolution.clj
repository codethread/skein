(ns skein.api.peers.internal.resolution
  "Resolve a caller's peerish argument to exactly one running peer row.
  Bare tokens match logical friendly names; explicitly path-like input
  matches the canonical selected workspace path. Unknown, stale, and
  ambiguous matches fail loudly with domain-style `:code` data
  (SPEC-004.C87)."
  (:require [clojure.string :as str]
            [skein.api.peers.internal.shared :as shared]))

(defn path-like?
  "True when the caller explicitly wrote a filesystem path: contains a `/` or
  starts with `~`. Bare tokens always resolve as logical peer names, so a
  local directory named like a peer can never shadow the name."
  [value]
  (when (string? value)
    (or (str/includes? value "/")
        (str/starts-with? value "~"))))

(defn candidate-summary
  "Project `rows` to their identifying keys for loud failure data."
  [rows]
  (mapv shared/peer-identity rows))

(defn resolve-peer
  "Resolve exactly one running peer among `rows` by friendly name or
  selected workspace path.

  Explicitly path-like input (contains `/`, or starts with `~`) matches the
  canonical selected workspace path; any bare token matches friendly names,
  so a local directory named like a peer never shadows the logical name.
  Stale, missing, and ambiguous matches fail loudly with domain-style
  `:code` data."
  [rows name-or-workspace]
  (let [by-path? (path-like? name-or-workspace)
        wanted (if by-path?
                 (shared/canonical-path name-or-workspace)
                 (str name-or-workspace))
        matches (filterv (fn [row]
                           (if by-path?
                             (= wanted (shared/canonical-path (:workspace row)))
                             (= wanted (:name row))))
                         rows)
        running (filterv :running? matches)]
    (cond
      (empty? matches)
      (throw (ex-info "No matching peer weaver"
                      {:code :peer/not-found
                       :query name-or-workspace
                       :match-by (if by-path? :workspace :name)}))

      (empty? running)
      (throw (ex-info "Matching peer weaver is stale"
                      {:code :peer/stale
                       :query name-or-workspace
                       :match-by (if by-path? :workspace :name)
                       :candidates (candidate-summary matches)}))

      (> (count running) 1)
      (throw (ex-info "Ambiguous peer weaver name"
                      {:code :peer/ambiguous
                       :query name-or-workspace
                       :candidates (candidate-summary running)}))

      :else
      (first running))))

(defn resolved-peer
  "Return `peerish` when it is already a peer row; otherwise enumerate rows
  via `list-peers` (called only in that case) and resolve one running peer."
  [peerish list-peers]
  (if (map? peerish)
    peerish
    (resolve-peer (list-peers) peerish)))
