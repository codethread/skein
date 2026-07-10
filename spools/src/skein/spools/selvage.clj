(ns skein.spools.selvage
  "Attribute vocabulary linting for userland strand conventions.

  Selvage keeps opt-in attribute invariants in trusted spool state. It never
  changes the core open-attribute contract: callers register data-first
  vocabularies, run checks on demand, or watch asynchronous mutation events for
  post-hoc detection."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.events.alpha :as events]
            [skein.api.graph.alpha :as graph]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as api]
            [skein.spools.util :refer [fail!]]))

(def ^:private state-version
  "Shape version for selvage's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives `reload!`, so a post-upgrade reload would
  otherwise reuse a preserved map missing the new key (docs/writing-shared-spools.md
  'Versioned spool state', SPEC-004.C95). The `state-shape-matches-declared-version`
  test fails loudly if `new-state` and this version drift apart."
  1)

(defn- new-state []
  {:vocab-registry (atom {})
   :violation-log (atom [])})

(defn- state []
  (runtime/spool-state (current/runtime) ::state {:version state-version} new-state))

;; Event handlers run under `with-runtime-binding`, so `(current/runtime)`
;; resolves on the event worker thread as well as on caller threads.
(defn- vocab-registry [] (:vocab-registry (state)))
(defn- violation-log [] (:violation-log (state)))

(def ^:private allowed-spec-keys #{:checks :doc})
(def ^:private allowed-check-keys #{:attr :enum :kind :required-with :doc})
(def ^:private allowed-kinds #{:string :number :boolean :map :int-string})
(def ^:private event-key :skein.spools.selvage/watch)

(defn- check-name! [name]
  (when-not (or (keyword? name) (symbol? name) (string? name))
    (fail! "Vocabulary name must be a keyword, symbol, or string"
           {:name name :allowed #{:keyword :symbol :string}}))
  name)

(defn- unknown-keys [m allowed]
  (seq (remove allowed (keys m))))

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- check-type [check]
  (cond
    (contains? check :enum) :enum
    (contains? check :kind) :kind
    (contains? check :required-with) :required-with
    :else nil))

(defn- validate-check! [check]
  (when-not (map? check)
    (fail! "Vocabulary check must be a map" {:check check}))
  (when-let [keys (unknown-keys check allowed-check-keys)]
    (fail! "Vocabulary check contains unknown keys"
           {:unknown-keys (vec keys) :allowed-keys (vec (sort-by pr-str allowed-check-keys)) :check check}))
  (when-not (non-blank-string? (:attr check))
    (fail! "Vocabulary check requires non-blank string :attr" {:check check}))
  (let [type (check-type check)]
    (when-not type
      (fail! "Vocabulary check requires one check kind"
             {:check check :allowed-checks [:enum :kind :required-with]}))
    (when (not= 1 (count (filter #(contains? check %) [:enum :kind :required-with])))
      (fail! "Vocabulary check must use exactly one check kind"
             {:check check :allowed-checks [:enum :kind :required-with]}))
    (case type
      :enum (when-not (and (vector? (:enum check)) (seq (:enum check)))
              (fail! "Vocabulary :enum check requires a non-empty vector" {:check check}))
      :kind (when-not (allowed-kinds (:kind check))
              (fail! "Vocabulary :kind check is unknown"
                     {:kind (:kind check) :allowed-kinds (vec (sort-by name allowed-kinds)) :check check}))
      :required-with (when-not (non-blank-string? (:required-with check))
                       (fail! "Vocabulary :required-with check requires non-blank string attribute name"
                              {:check check}))))
  check)

(defn- validate-spec! [name spec]
  (check-name! name)
  (when-not (map? spec)
    (fail! "Vocabulary spec must be a map" {:vocab name :spec spec}))
  (when-let [keys (unknown-keys spec allowed-spec-keys)]
    (fail! "Vocabulary spec contains unknown keys"
           {:vocab name :unknown-keys (vec keys) :allowed-keys (vec (sort-by pr-str allowed-spec-keys))}))
  (when-not (vector? (:checks spec))
    (fail! "Vocabulary spec requires vector :checks" {:vocab name :spec spec}))
  (assoc spec :checks (mapv validate-check! (:checks spec))))

(defn defvocab!
  "Register or replace an attribute vocabulary for this weaver lifetime.

  `spec` is data with `:checks`, a vector of maps. Supported checks are
  `{:attr s :enum [...]}`, `{:attr s :kind k}`, and
  `{:attr s :required-with other-attr}`. Unknown keys and unknown kinds throw
  `ex-info` with allowed values. Returns the registered metadata."
  [name spec]
  (let [spec (validate-spec! name spec)
        entry {:name (check-name! name)
               :spec spec}]
    (swap! (vocab-registry) assoc name entry)
    entry))

(defn vocabs
  "Return registered vocabulary metadata in deterministic order."
  []
  (->> @(vocab-registry) vals (sort-by (comp pr-str :name)) vec))

(defn remove-vocab!
  "Remove a registered vocabulary by name.

  Missing vocabularies fail loudly. Returns `{:removed name}`."
  [name]
  (check-name! name)
  (when-not (contains? @(vocab-registry) name)
    (fail! "Vocabulary is not registered" {:vocab name :available (vec (sort-by pr-str (keys @(vocab-registry))))}))
  (swap! (vocab-registry) dissoc name)
  {:removed name})

(defn- attr-key [attr]
  (keyword attr))

(defn- present? [attributes attr]
  (contains? attributes (attr-key attr)))

(defn- attr-value [attributes attr]
  (get attributes (attr-key attr)))

(defn- kind-match? [kind value]
  (case kind
    :string (string? value)
    :number (number? value)
    :boolean (or (true? value) (false? value))
    :map (map? value)
    :int-string (and (string? value) (boolean (re-matches #"-?\d+" value)))))

(defn- violation [strand-id vocab-name check value message]
  {:strand-id strand-id
   :vocab vocab-name
   :attr (:attr check)
   :check (check-type check)
   :value value
   :message message})

(defn- check-one [strand vocab-name check]
  (let [attributes (:attributes strand)
        value (attr-value attributes (:attr check))]
    (case (check-type check)
      :enum (when (and (present? attributes (:attr check))
                       (not-any? #(= value %) (:enum check)))
              (violation (:id strand) vocab-name check value
                         (str "Attribute " (:attr check) " must be one of " (pr-str (:enum check)))))
      :kind (when (and (present? attributes (:attr check))
                       (not (kind-match? (:kind check) value)))
              (violation (:id strand) vocab-name check value
                         (str "Attribute " (:attr check) " must have kind " (:kind check))))
      :required-with (when (and (present? attributes (:required-with check))
                                (not (present? attributes (:attr check))))
                       (violation (:id strand) vocab-name check nil
                                  (str "Attribute " (:attr check) " is required when " (:required-with check) " is present"))))))

(defn- check-strand [strand]
  (->> (for [{vocab-name :name {:keys [checks]} :spec} (vocabs)
             check checks
             :let [v (check-one strand vocab-name check)]
             :when v]
         v)
       vec))

(defn- strand-map? [x]
  (and (map? x) (:id x) (contains? x :attributes)))

(defn check
  "Return vocabulary violations for one strand map or strand id.

  Missing strand ids fail loudly through the public graph surfaces. A clean
  strand returns an empty vector."
  [strand-or-id]
  (let [strand (if (strand-map? strand-or-id)
                 strand-or-id
                 (try
                   (or (first (graph/strands-by-ids (current/runtime) [strand-or-id]))
                       (fail! "Strand not found" {:strand-id strand-or-id}))
                   (catch clojure.lang.ExceptionInfo e
                     (throw (ex-info "Strand not found" {:strand-id strand-or-id} e)))))]
    (check-strand strand)))

(defn check-all
  "Return vocabulary violations across active strands.

  With no arguments checks all active strands. With `query-form`, checks only
  strands selected by that predicate DSL query."
  ([]
   (let [rt (current/runtime)]
     (vec (mapcat check-strand (api/list rt [:= :state "active"] {})))))
  ([query-form]
   (let [rt (current/runtime)]
     (vec (mapcat check-strand (api/list rt [:and [:= :state "active"] query-form] {}))))))

(defn- attr-namespace
  "The namespace segment of a selvage check's `:attr` — the part before the first
  `/`, or the whole attribute when it carries no namespace separator (a bare
  attribute is its own, typically undeclared, namespace)."
  [attr]
  (if-let [i (str/index-of attr "/")]
    (subs attr 0 i)
    attr))

(defn undeclared-checks
  "Return registered selvage checks whose `:attr` namespace no vocabulary
  declaration owns.

  Opt-in cross-check between selvage's linting vocabularies and the ownership
  registry (`skein.api.vocab.alpha`): reads the declared `:attr-namespace` names
  for the active runtime and returns one entry per registered check whose
  attribute namespace segment is undeclared, as
  `{:vocab name :attr s :namespace s :check check}` sorted like `vocabs`.

  Read-only composition sugar over `vocabs` — it references the registry, never
  enforces it. Registered nowhere by default: no watch, no new enforcement path,
  and no change to selvage's `:enum`/`:kind`/`:required-with` linting model."
  []
  (let [declared (into #{} (map :name)
                       (vocab/declarations (current/runtime) {:kind :attr-namespace}))]
    (vec (for [{vocab-name :name {:keys [checks]} :spec} (vocabs)
               check checks
               :let [ns (attr-namespace (:attr check))]
               :when (not (contains? declared ns))]
           {:vocab vocab-name
            :attr (:attr check)
            :namespace ns
            :check check}))))

(defn record-event!
  "Event handler that records violations for strand added/updated events.

  Intended for registration by `watch!`. Handler exceptions are deliberately not
  caught here so the weaver event failure surface records them."
  [event]
  (let [strand (or (:strand/after event) (:strand event))]
    (when-not strand
      (fail! "Selvage event did not include a strand payload"
             {:event/id (:event/id event) :event/type (:event/type event)}))
    (let [found (check-strand strand)]
      (when (seq found)
        (swap! (violation-log) into found))
      found)))

(defn watch!
  "Register the asynchronous mutation watcher for post-hoc violation recording."
  []
  (let [rt (current/runtime)]
    (events/register! rt event-key #{:strand/added :strand/updated} 'skein.spools.selvage/record-event!
                      {:purpose :selvage/attribute-vocabulary-lint})))

(defn violations
  "Return recorded watch-mode violations in delivery order."
  []
  @(violation-log))

(defn clear-violations!
  "Clear recorded watch-mode violations."
  []
  (reset! (violation-log) [])
  {:cleared true})

(defn install!
  "Install Selvage watch support into the active weaver and return metadata."
  []
  (watch!)
  {:installed true
   :namespace 'skein.spools.selvage
   :watcher event-key
   :vocabularies 'skein.spools.selvage/vocabs
   :checker 'skein.spools.selvage/check-all})
