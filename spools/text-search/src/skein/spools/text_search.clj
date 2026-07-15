(ns skein.spools.text-search
  "UNSAFE: uses skein.core.db for substring search over strand titles and
  attribute values, including archived rows the query language cannot see.

  This spool is a deliberate, maintained example of breaking Skein's
  namespace-tier rules in the open — the Clojure equivalent of a Rust `unsafe`
  block. It is **not** a blessed path. It exists to show, honestly, what
  reaching past the contract looks like and what you owe the next reader when
  you do it.

  ## Why it is unsafe

  The blessed query language (`skein.api.weaver.alpha`) has no text/substring
  operator, and its compiled predicates read only hot attribute rows: archived
  values are structurally invisible to every query. That invisibility is a
  deliberate invariant, not an oversight — archived attributes are memory and
  teaching material, not authority (see `devflow/PHILOSOPHY.md`, \"The work
  record is not the source of truth\"). To offer `LIKE`-style search — and to
  reach archived rows at all — this namespace requires `skein.core.db` directly
  and runs SQL against the physical `strands` and `attributes` tables. That
  reaches under the attribute-map contract (TEN-007, storage is the core's
  burden) and couples to a storage shape `skein.core.*` is free to change
  without notice (TEN-000).

  ## The contract you are opting into

  Because it binds to core internals, this spool may break on any Skein upgrade.
  It is maintained in-repo, in lockstep with the storage it reads, precisely so
  that breakage surfaces here and gets fixed here. An external spool that copies
  this pattern earns no such guarantee and owns its own breakage.

  See `spools/text-search.md` for the full unsafe declaration and design
  rationale. Every query pattern is parameter-bound — user text is never spliced
  into SQL — and the op is read-only: it mutates no strands, edges, or state."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as weaver]
            ;; UNSAFE: physical-table access. A blessed spool builds on
            ;; skein.api.*.alpha only; this one reaches past the contract on
            ;; purpose (see the ns docstring).
            [skein.core.db :as db]
            [skein.api.spool.alpha :refer [fail!]]))

(def default-limit
  "Default row cap for `search`. Overflow fails loudly rather than truncating,
  so a caller always sees a complete result set or a clear instruction to narrow
  it."
  50)

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(s/def ::text non-blank-string?)
(s/def ::archived? boolean?)
(s/def :skein.spools.text-search.search/key non-blank-string?)
(s/def ::limit pos-int?)
(s/def ::search-opts
  (s/keys :req-un [::text]
          :opt-un [::archived? :skein.spools.text-search.search/key ::limit]))
(s/def ::id string?)
(s/def ::title string?)
(s/def :skein.spools.text-search.result/key (s/nilable string?))
(s/def ::snippet string?)
(s/def ::result-row
  (s/keys :req-un [::id ::title :skein.spools.text-search.result/key ::snippet]))

(defn- require-search-opts! [opts]
  (when-not (s/valid? ::search-opts opts)
    (fail! "text-search opts are invalid"
           {:opts opts
            :explain (s/explain-str ::search-opts opts)}))
  opts)

(defn- require-result-rows! [rows]
  (doseq [row rows]
    (when-not (s/valid? ::result-row row)
      (fail! "text-search result row is invalid"
             {:row row
              :explain (s/explain-str ::result-row row)})))
  rows)

(defn- runtime-datasource
  "Return the runtime's raw SQLite datasource, failing loudly when absent.

  UNSAFE: the datasource is a `skein.core.*`-owned handle with no compatibility
  promise; a blessed spool would never touch it."
  [runtime]
  (or (:datasource runtime)
      (fail! "text-search could not resolve a datasource from the runtime"
             {:runtime-keys (vec (keys runtime))})))

(defn- like-escape
  "Escape LIKE metacharacters in `text` so it matches as a literal substring
  under `ESCAPE '\\'`.

  Backslash is escaped first so the escapes added for `%` and `_` are not
  themselves re-escaped."
  [text]
  (-> text
      (str/replace "\\" "\\\\")
      (str/replace "%" "\\%")
      (str/replace "_" "\\_")))

(def ^:private title-select
  "Title-branch select: a title hit carries no attribute key, so `key` is NULL
  and the whole title is the snippet."
  "SELECT id, title, NULL AS key, title AS snippet FROM strands WHERE title LIKE ? ESCAPE '\\'")

(defn- attr-select
  "Attribute-branch select. Excludes archived rows unless `archived?`, and scopes
  to one attribute `key?` when supplied. Only the fixed clauses vary; the
  patterns stay bound parameters."
  [archived? key?]
  (str "SELECT s.id AS id, s.title AS title, a.key AS key, a.value AS snippet "
       "FROM attributes a JOIN strands s ON s.id = a.strand_id "
       "WHERE a.value LIKE ? ESCAPE '\\'"
       (when-not archived? " AND a.archived = 0")
       (when key? " AND a.key = ?")))

(defn search
  "Return strand rows whose title or an attribute value contains `text`.

  `opts` is a map: `:text` (required, non-blank substring matched literally),
  `:archived?` (include archived/cold attribute rows the query language cannot
  see; default false — hot rows only), `:key` (scope the attribute-value search
  to one attribute key, which also skips the title branch), and `:limit`
  (default `default-limit`).

  Each row is `{:id :title :key :snippet}`: `:key` is nil for a title hit or the
  matching attribute key otherwise, and `:snippet` is the matched text (the
  title, or the attribute value as stored JSON). Rows are ordered by strand id
  then key.

  Read-only. Fails loudly (TEN-003) on malformed opts or overflow: `search`
  fetches one row past `:limit` and, if the result exceeds it, throws naming
  `--limit` and query-narrowing rather than silently truncating."
  [runtime opts]
  (let [{:keys [text archived? key limit] :or {archived? false limit default-limit}}
        (require-search-opts! opts)
        ds (runtime-datasource runtime)
        like (str "%" (like-escape text) "%")
        attr-sql (attr-select archived? key)
        ;; A --key search is an attribute-value search only; titles are not
        ;; attributes, so the title branch is dropped when key scopes the query.
        [inner params] (if key
                         [attr-sql [like key]]
                         [(str title-select " UNION ALL " attr-sql) [like like]])
        capped (inc limit)
        sql (str "SELECT id, title, key, snippet FROM (" inner ") ORDER BY id, key LIMIT ?")
        rows (db/execute! ds (into [sql] (conj params capped)))]
    (when (> (count rows) limit)
      (fail! (str "text-search matched more than the --limit of " limit
                  " rows; narrow the query (scope with --key or a more specific pattern) "
                  "or raise --limit — results are capped, never truncated")
             {:reason :overflow :limit limit}))
    (require-result-rows! (mapv #(select-keys % [:id :title :key :snippet]) rows))))

(defn search-op
  "Handle `strand search ...`, threading parsed args into `search`.

  The registered op handler; resolved by symbol at dispatch time, so it is public
  like the other spools' op handlers."
  [ctx]
  (let [{:keys [text archived key limit]} (:op/args ctx)]
    (search (:op/runtime ctx)
            (cond-> {:text text :archived? (boolean archived)}
              key (assoc :key key)
              limit (assoc :limit limit)))))

(def ^:private search-doc
  "UNSAFE substring search over strand titles and attribute values, including archived rows.")

(def ^:private search-arg-spec
  "Declared command surface for the `search` op."
  {:op "search"
   :doc search-doc
   :flags {:archived {:type :boolean
                      :doc "Include archived (cold) attribute rows, which the query language cannot see."}
           :key {:type :string
                 :doc "Scope the attribute-value search to one attribute key (skips the title branch)."}
           :limit {:type :int
                   :doc (str "Row cap (default " default-limit "); overflow fails loudly rather than truncating.")}}
   :positionals [{:name :text :type :string :required? true
                  :doc "Substring to search for, matched literally."}]})

(def ^:private search-return
  {:type :collection
   :items {:type :map
           :required {:id :string
                      :title :string
                      :key [:nullable :string]
                      :snippet :string}}})

(defn install!
  "Install the UNSAFE `search` op into the active weaver.

  Resolving the ambient runtime here matches the activation boundary used by the
  other shipped spools; the op handler and `search` itself take the runtime
  explicitly. Returns installation metadata carrying `:unsafe true` so callers
  can see what they activated."
  []
  (let [rt (current/runtime)]
    {:installed true
     :namespace 'skein.spools.text-search
     :unsafe true
     :ops [(weaver/register-op! rt 'search
                                {:doc search-doc
                                 :arg-spec search-arg-spec
                                 :returns search-return
                                 :hook-class :read}
                                'skein.spools.text-search/search-op)]}))
