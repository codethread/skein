(ns skein.api.vocab.alpha
  "Explicit-runtime vocabulary registry: the blessed home for declaring and
  reading Skein's attribute-namespace and edge-type vocabulary.

  Every seed and consumer module `declare!`s the attribute namespaces and edge
  types it owns and reads the whole picture back, so the vocabulary a strand
  graph actually uses is discoverable data instead of tribal knowledge. A
  declaration is a small map (`:kind`, `:name`, `:owner`, `:doc`, plus `:keys`
  for an attribute namespace or `:family`/`:direction`/`:declared-acyclic?` for
  an edge). The registry is runtime-owned per-spool state that survives
  `reload!`, versioned so a shape change cannot silently reuse a stale map, and
  seeded at init with the reflected `relations.alpha` edge catalog plus the
  core-owned `note/*` attribute namespace.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument, per the blessed-namespace convention; nothing here reads the
  published ambient runtime."
  (:require [clojure.string :as str]
            [skein.api.relations.alpha :as relations]
            [skein.api.runtime.alpha :as runtime]
            [skein.spools.util :refer [fail! reject-unknown-keys!]]))

;; --- C1 declaration shape ------------------------------------------------

(def ^:private declaration-kinds
  "The two vocabulary kinds a declaration may describe: an attribute namespace
  segment or an edge (relation) type."
  #{:attr-namespace :edge})

(def ^:private common-declaration-keys
  "Keys every declaration carries regardless of kind."
  #{:kind :name :owner :doc})

(def ^:private allowed-keys-by-kind
  "Full permitted key set per kind. `:attr-namespace` adds the advisory `:keys`
  list; `:edge` adds the catalog-reflected `:family`/`:direction`/
  `:declared-acyclic?`."
  {:attr-namespace (conj common-declaration-keys :keys)
   :edge (into common-declaration-keys [:family :direction :declared-acyclic?])})

(def ^:private required-keys-by-kind
  "Keys that must be present per kind. `:keys` stays optional (advisory); the
  edge catalog fields are required because an edge declaration is only
  meaningful with them."
  {:attr-namespace common-declaration-keys
   :edge (into common-declaration-keys [:family :direction :declared-acyclic?])})

(defn- validate-declaration!
  "Return `declaration` after validating the C1 shape, or fail loudly.

  Rejects a non-map, an unknown `:kind`, unknown keys for that kind, and missing
  required keys, then type-checks the identity/description fields. This is the
  validate-then-record half of the selvage validate-then-record pattern."
  [declaration]
  (when-not (map? declaration)
    (fail! "Vocabulary declaration must be a map" {:declaration declaration}))
  (let [kind (:kind declaration)]
    (when-not (contains? declaration-kinds kind)
      (fail! "Vocabulary declaration :kind must be :attr-namespace or :edge"
             {:kind kind :allowed (vec (sort declaration-kinds)) :declaration declaration}))
    (reject-unknown-keys! "vocab/declare!" (allowed-keys-by-kind kind) declaration)
    (when-let [missing (seq (remove #(contains? declaration %) (required-keys-by-kind kind)))]
      (fail! "Vocabulary declaration is missing required keys"
             {:missing (vec (sort missing)) :kind kind :declaration declaration}))
    (when-not (and (string? (:name declaration)) (not (str/blank? (:name declaration))))
      (fail! "Vocabulary declaration :name must be a non-blank string" {:declaration declaration}))
    (when-not (or (keyword? (:owner declaration)) (symbol? (:owner declaration)) (string? (:owner declaration)))
      (fail! "Vocabulary declaration :owner must be a keyword, symbol, or string" {:declaration declaration}))
    (when-not (string? (:doc declaration))
      (fail! "Vocabulary declaration :doc must be a string" {:declaration declaration})))
  declaration)

;; --- Core seed -----------------------------------------------------------

(defn- edge-declaration
  "Reflect one `relations.alpha/catalog` entry into a core-owned `:edge`
  declaration, preserving its family/direction/acyclicity metadata."
  [{:keys [relation family direction declared-acyclic? help]}]
  {:kind :edge
   :name relation
   :owner :skein/core
   :family family
   :direction direction
   :declared-acyclic? declared-acyclic?
   :doc help})

(def ^:private note-namespace-declaration
  "The core-owned `note/*` attribute namespace written by
  `skein.api.notes.alpha/note!`."
  {:kind :attr-namespace
   :name "note"
   :owner 'skein.api.notes.alpha
   :keys ["note/text" "note/at" "note/by" "note/round"]
   :doc "Immutable note-strand memory attributes written by skein.api.notes.alpha/note!."})

(defn- seed-declarations
  "Return the core seed as a vector of valid C1 declarations: one `:edge` per
  reflected catalog entry plus the `note/*` attribute namespace. Reflecting the
  catalog keeps the edge set from being re-listed in this source."
  []
  (conj (mapv edge-declaration relations/catalog) note-namespace-declaration))

(defn- index-by-key
  "Index declarations under their `[:kind :name]` registry key."
  [declarations]
  (into {} (map (fn [d] [[(:kind d) (:name d)] d])) declarations))

;; --- Versioned runtime-owned store ---------------------------------------

(def ^:private state-version
  "Shape version for vocab's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives `reload!`, so a post-upgrade reload would
  otherwise reuse a preserved map missing the new key. The
  `state-shape-matches-declared-version` test fails loudly if `new-state` and
  this version drift apart."
  1)

(defn- new-state
  "Build the initial registry state, already carrying the core seed. This
  init-fn is the seed site (there is no `install!` hook): a fresh runtime reads
  the reflected edges and `note/*` back before any spool activation runs."
  []
  {:registry (atom (index-by-key (seed-declarations)))})

(defn- state [runtime]
  (runtime/spool-state runtime ::state {:version state-version} new-state))

(defn- registry [runtime]
  (:registry (state runtime)))

;; --- Public surface ------------------------------------------------------

(defn declare!
  "Record C1 `declaration` in `runtime`'s vocabulary registry and return it.

  Validates the shape (fails loudly on an unknown kind, unknown keys, or missing
  required keys). Recording is keyed by `[:kind :name]`: a re-declaration by the
  *same* `:owner` is an idempotent replace, while a *different* owner throws
  `ex-info` carrying `:name`/`:kind`/`:existing-owner`/`:declaring-owner`, so
  ownership of a namespace or edge type is a hard, single-owner edge."
  [runtime declaration]
  (let [declaration (validate-declaration! declaration)
        {kind :kind decl-name :name owner :owner} declaration
        k [kind decl-name]
        reg (registry runtime)]
    (when-let [existing (get @reg k)]
      (when (not= (:owner existing) owner)
        (throw (ex-info "Vocabulary declaration owner conflict"
                        {:name decl-name
                         :kind kind
                         :existing-owner (:owner existing)
                         :declaring-owner owner}))))
    (swap! reg assoc k declaration)
    declaration))

(defn declarations
  "Return `runtime`'s declarations as full C1 maps, sorted by `[:kind :name]`.

  With `{:kind k}` opts, narrows to that kind. Reads the runtime store
  explicitly — never the published ambient singleton."
  ([runtime] (declarations runtime nil))
  ([runtime opts]
   (when (some? opts)
     (reject-unknown-keys! "vocab/declarations" #{:kind} opts))
   (let [kind (:kind opts)]
     (->> (vals @(registry runtime))
          (filter (fn [d] (or (nil? kind) (= kind (:kind d)))))
          (sort-by (juxt :kind :name))
          vec))))

(defn declaration
  "Return the one declaration in `runtime` under `[kind name]`, or `nil` when
  that namespace or edge type is undeclared."
  [runtime kind name]
  (get @(registry runtime) [kind name]))
