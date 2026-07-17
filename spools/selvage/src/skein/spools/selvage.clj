(ns skein.spools.selvage
  "Attribute vocabulary linting for userland strand conventions.

  Selvage keeps opt-in attribute invariants in trusted spool state. It never
  changes the core open-attribute contract: callers register data-first
  checksets, run checks on demand, or watch asynchronous mutation events for
  post-hoc detection."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.events.alpha :as events]
            [skein.api.graph.alpha :as graph]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.spool.alpha :refer [fail!]]))

(def ^:private state-version
  "Shape version for selvage's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives `reload!`, so a post-upgrade reload would
  otherwise reuse a preserved map missing the new key (docs/spools/writing-shared-spools.md
  'Versioned spool state', SPEC-004.C95). The `state-shape-matches-declared-version`
  test fails loudly if `new-state` and this version drift apart."
  1)

(defn- new-state []
  {:checkset-registry (atom {})
   :violation-log (atom [])})

(defn- state []
  (runtime/spool-state (current/runtime) ::state {:version state-version} new-state))

;; Event handlers run under `with-runtime-binding`, so `(current/runtime)`
;; resolves on the event worker thread as well as on caller threads.
(defn- checkset-registry [] (:checkset-registry (state)))
(defn- violation-log [] (:violation-log (state)))

(def ^:private allowed-spec-keys #{:checks :doc})
(def ^:private allowed-check-keys #{:attr :enum :type :required-with :doc})
(def ^:private allowed-types #{:string :number :boolean :map :int-string})
(def ^:private event-key :skein.spools.selvage/watch)

(defn- check-name! [name]
  (when-not (or (keyword? name) (symbol? name) (string? name))
    (fail! "Checkset name must be a keyword, symbol, or string"
           {:name name :allowed #{:keyword :symbol :string}}))
  name)

(defn- unknown-keys [m allowed]
  (seq (remove allowed (keys m))))

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

;; A check's FORM is which constraint it states (:enum/:type/:required-with); the
;; :type form's value is a value TYPE (:string/:number/...).
(defn- check-form [check]
  (cond
    (contains? check :enum) :enum
    (contains? check :type) :type
    (contains? check :required-with) :required-with
    :else nil))

(defn- validate-check! [check]
  (when-not (map? check)
    (fail! "Check must be a map" {:check check}))
  (when-let [keys (unknown-keys check allowed-check-keys)]
    (fail! "Check contains unknown keys"
           {:unknown-keys (vec keys) :allowed-keys (vec (sort-by pr-str allowed-check-keys)) :check check}))
  (when-not (non-blank-string? (:attr check))
    (fail! "Check requires non-blank string :attr" {:check check}))
  (let [form (check-form check)]
    (when-not form
      (fail! "Check requires one check form"
             {:check check :allowed-checks [:enum :type :required-with]}))
    (when (not= 1 (count (filter #(contains? check %) [:enum :type :required-with])))
      (fail! "Check must use exactly one check form"
             {:check check :allowed-checks [:enum :type :required-with]}))
    (case form
      :enum (when-not (and (vector? (:enum check)) (seq (:enum check)))
              (fail! "Check :enum form requires a non-empty vector" {:check check}))
      :type (when-not (allowed-types (:type check))
              (fail! "Check :type form is unknown"
                     {:type (:type check) :allowed-types (vec (sort-by name allowed-types)) :check check}))
      :required-with (when-not (non-blank-string? (:required-with check))
                       (fail! "Check :required-with form requires non-blank string attribute name"
                              {:check check}))))
  check)

(defn- validate-spec! [name spec]
  (check-name! name)
  (when-not (map? spec)
    (fail! "Checkset spec must be a map" {:checkset name :spec spec}))
  (when-let [keys (unknown-keys spec allowed-spec-keys)]
    (fail! "Checkset spec contains unknown keys"
           {:checkset name :unknown-keys (vec keys) :allowed-keys (vec (sort-by pr-str allowed-spec-keys))}))
  (when-not (vector? (:checks spec))
    (fail! "Checkset spec requires vector :checks" {:checkset name :spec spec}))
  (assoc spec :checks (mapv validate-check! (:checks spec))))

(defn register-checkset!
  "Register or replace a named checkset for this weaver lifetime.

  `spec` is data with `:checks`, a vector of maps. Supported checks are
  `{:attr s :enum [...]}`, `{:attr s :type t}`, and
  `{:attr s :required-with other-attr}`. Unknown keys and unknown types throw
  `ex-info` with allowed values. Returns the registered metadata."
  [name spec]
  (let [spec (validate-spec! name spec)
        entry {:name (check-name! name)
               :spec spec}]
    (swap! (checkset-registry) assoc name entry)
    entry))

(defn checksets
  "Return registered checkset metadata in deterministic order."
  []
  (->> @(checkset-registry) vals (sort-by (comp pr-str :name)) vec))

(defn unregister-checkset!
  "Unregister a checkset by name.

  Missing checksets fail loudly. Returns `{:unregistered name}`."
  [name]
  (check-name! name)
  (when-not (contains? @(checkset-registry) name)
    (fail! "Checkset is not registered"
           {:checkset name :available (vec (sort-by pr-str (keys @(checkset-registry))))}))
  (swap! (checkset-registry) dissoc name)
  {:unregistered name})

(defn- attr-key [attr]
  (keyword attr))

(defn- present? [attributes attr]
  (contains? attributes (attr-key attr)))

(defn- attr-value [attributes attr]
  (get attributes (attr-key attr)))

(defn- type-match? [type value]
  (case type
    :string (string? value)
    :number (number? value)
    :boolean (or (true? value) (false? value))
    :map (map? value)
    :int-string (and (string? value) (boolean (re-matches #"-?\d+" value)))))

(defn- violation [strand-id checkset-name check value message]
  {:strand-id strand-id
   :checkset checkset-name
   :attr (:attr check)
   :check (check-form check)
   :value value
   :message message})

(defn- check-one [strand checkset-name check]
  (let [attributes (:attributes strand)
        value (attr-value attributes (:attr check))]
    (case (check-form check)
      :enum (when (and (present? attributes (:attr check))
                       (not-any? #(= value %) (:enum check)))
              (violation (:id strand) checkset-name check value
                         (str "Attribute " (:attr check) " must be one of " (pr-str (:enum check)))))
      :type (when (and (present? attributes (:attr check))
                       (not (type-match? (:type check) value)))
              (violation (:id strand) checkset-name check value
                         (str "Attribute " (:attr check) " must have type " (:type check))))
      :required-with (when (and (present? attributes (:required-with check))
                                (not (present? attributes (:attr check))))
                       (violation (:id strand) checkset-name check nil
                                  (str "Attribute " (:attr check) " is required when " (:required-with check) " is present"))))))

(defn- check-strand [strand]
  (->> (for [{checkset-name :name {:keys [checks]} :spec} (checksets)
             check checks
             :let [v (check-one strand checkset-name check)]
             :when v]
         v)
       vec))

(defn- strand-map? [x]
  (and (map? x) (:id x) (contains? x :attributes)))

(defn check
  "Return checkset violations for one strand map or strand id.

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
  "Return checkset violations across active strands.

  With no arguments checks all active strands. With `query-form`, checks only
  strands selected by that predicate DSL query."
  ([]
   (let [rt (current/runtime)]
     (vec (mapcat check-strand (weaver/list rt [:= :state "active"] {})))))
  ([query-form]
   (let [rt (current/runtime)]
     (vec (mapcat check-strand (weaver/list rt [:and [:= :state "active"] query-form] {}))))))

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

  Opt-in cross-check between selvage's checksets and the ownership registry
  (`skein.api.vocab.alpha`): reads the declared `:attr-namespace` names for the
  active runtime and returns one entry per registered check whose attribute
  namespace segment is undeclared, as
  `{:checkset name :attr s :namespace s :check check}` sorted like `checksets`.

  Read-only composition sugar over `checksets` — it references the registry,
  never enforces it. Registered nowhere by default: no watch, no new enforcement
  path, and no change to selvage's `:enum`/`:type`/`:required-with` linting
  model."
  []
  (let [declared (into #{} (map :name)
                       (vocab/declarations (current/runtime) {:kind :attr-namespace}))]
    (vec (for [{checkset-name :name {:keys [checks]} :spec} (checksets)
               check checks
               :let [attr-ns (attr-namespace (:attr check))]
               :when (not (contains? declared attr-ns))]
           {:checkset checkset-name
            :attr (:attr check)
            :namespace attr-ns
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
   :checksets 'skein.spools.selvage/checksets
   :checker 'skein.spools.selvage/check-all})
