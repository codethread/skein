(ns skein.api.cli.internal.validation
  "Structural validation plumbing for declarative CLI arg-specs."
  (:require [clojure.string :as str]
            [skein.api.cli.internal.shared :as shared]))

(def supported-arg-types
  "Argument types understood by the parser."
  #{:string :int :boolean :boolean-token :map})

(def supported-parse-kinds
  "Declared whole-value parse kinds the parser can apply."
  #{:json :jsonl})

(defn validate-declared-parse!
  "Validate a flag or positional declared :parse when one is present."
  [op subcommand field arg spec]
  (when-let [kind (:parse spec)]
    (when-not (contains? supported-parse-kinds kind)
      (shared/fail!
       :invalid-parse-kind
       (str "Unknown parse kind " (pr-str kind))
       (cond-> {:op op
                :field field
                :arg arg
                :parse kind
                :value spec
                :supported-parse-kinds (vec (sort-by name supported-parse-kinds))}
         subcommand (assoc :subcommand subcommand))))))

(defn validate-declared-type!
  "Validate a flag or positional declared :type when one is present."
  [op subcommand field arg spec]
  (let [type (:type spec :string)]
    (when-not (contains? supported-arg-types type)
      (shared/fail!
       :invalid-arg-type
       (str "Unknown argument type " (pr-str type))
       (cond-> {:op op
                :field field
                :arg arg
                :type type
                :value spec
                :supported-types (vec (sort-by name supported-arg-types))}
         subcommand (assoc :subcommand subcommand))))))

(def annotation-keys
  "The closed key set of an arg-spec node's authored annotation sub-map: each is
  an optional array of strings; `failure-modes` entries are glossary
  outcome-name references (DELTA-Dtf-003.CC2)."
  #{:use-when :notes :failure-modes})

(defn validate-annotations!
  "Structurally validate an arg-spec node's `:annotations` sub-map.

  Purely structural (SPEC-003.C64/C63d, no runtime dependency): a present
  `:annotations` must be a map with only `use-when`/`notes`/`failure-modes` keys,
  each a sequential collection of non-blank strings. The glossary-ref existence
  check for `failure-modes` names runs separately at `register-op!` time
  (DELTA-Dtf-003.CC2), which holds the runtime glossary."
  [op subcommand annotations]
  (when (some? annotations)
    (let [reason (if subcommand :invalid-subcommand-annotations :invalid-annotations)
          ctx (cond-> {:op op :field :annotations}
                subcommand (assoc :subcommand subcommand))]
      (when-not (map? annotations)
        (shared/fail!
         reason
         ":annotations must be a map of use-when/notes/failure-modes to string arrays"
         (assoc ctx :value annotations)))
      (when-let [unknown (seq (remove annotation-keys (keys annotations)))]
        (shared/fail!
         reason
         (str ":annotations has unknown keys " (pr-str (vec unknown)))
         (assoc ctx
                :unknown (vec unknown)
                :allowed (vec (sort-by name annotation-keys)))))
      (doseq [key annotation-keys
              :when (contains? annotations key)
              :let [value (get annotations key)
                    non-blank-string? #(and (string? %) (not (str/blank? %)))]]
        (when-not (and (sequential? value) (every? non-blank-string? value))
          (shared/fail!
           reason
           (str ":annotations " (name key) " must be an array of non-blank strings")
           (assoc ctx :annotation key :value value)))))))

(defn validate-flags!
  "Validate a :flags container and its entries."
  [op subcommand flags]
  (let [container-reason (if subcommand
                           :invalid-subcommand-flags
                           :invalid-flags)
        entry-reason (if subcommand
                       :invalid-subcommand-flag
                       :invalid-flag)]
    (when (some? flags)
      (when-not (map? flags)
        (shared/fail!
         container-reason
         ":flags must be a map of keyword to flag-spec map"
         (cond-> {:op op :field :flags :value flags}
           subcommand (assoc :subcommand subcommand))))
      (doseq [[flag spec] flags]
        (when-not (keyword? flag)
          (shared/fail!
           entry-reason
           "Flag names must be keywords"
           (cond-> {:op op :field :flags :arg flag :value spec}
             subcommand (assoc :subcommand subcommand))))
        (when-not (map? spec)
          (shared/fail!
           entry-reason
           "Flag specs must be maps"
           (cond-> {:op op :field :flags :arg flag :value spec}
             subcommand (assoc :subcommand subcommand))))
        (validate-declared-type! op subcommand :flags flag spec)
        (validate-declared-parse! op subcommand :flags flag spec)))))

(defn validate-positionals!
  "Validate a :positionals container and its entries."
  [op subcommand positionals]
  (let [container-reason (if subcommand
                           :invalid-subcommand-positionals
                           :invalid-positionals)
        entry-reason (if subcommand
                       :invalid-subcommand-positional
                       :invalid-positional)]
    (when (some? positionals)
      (when-not (sequential? positionals)
        (shared/fail!
         container-reason
         ":positionals must be a sequential collection of positional-spec maps"
         (cond-> {:op op :field :positionals :value positionals}
           subcommand (assoc :subcommand subcommand))))
      (doseq [[idx spec] (map-indexed vector positionals)]
        (when-not (map? spec)
          (shared/fail!
           entry-reason
           "Positional specs must be maps"
           (cond-> {:op op :field :positionals :index idx :value spec}
             subcommand (assoc :subcommand subcommand))))
        (when-not (keyword? (:name spec))
          (shared/fail!
           entry-reason
           "Positional specs must declare keyword :name"
           (cond-> {:op op :field :positionals :index idx :value spec}
             subcommand (assoc :subcommand subcommand))))
        (validate-declared-type!
         op subcommand :positionals (:name spec) spec)
        (validate-declared-parse!
         op subcommand :positionals (:name spec) spec)))))

(defn validate-subcommands!
  "Validate structural rules for an arg-spec declaring `:subcommands`.

  Subcommand specs are one level deep. Top-level flags or positionals may not be
  mixed with them, and the nested `subcommand` arg name is reserved."
  [arg-spec reserved-names]
  (when (contains? arg-spec :subcommands)
    (let [op (:op arg-spec)
          subcommands (:subcommands arg-spec)]
      (validate-annotations! op nil (:annotations arg-spec))
      (when (contains? arg-spec :flags)
        (shared/fail!
         :invalid-subcommands
         "Arg-spec with :subcommands may not declare top-level :flags"
         {:op op :field :flags}))
      (when (contains? arg-spec :positionals)
        (shared/fail!
         :invalid-subcommands
         "Arg-spec with :subcommands may not declare top-level :positionals"
         {:op op :field :positionals}))
      (when-not (map? subcommands)
        (shared/fail!
         :invalid-subcommands
         ":subcommands must be a map of subcommand name to arg-spec"
         {:op op :field :subcommands :value subcommands}))
      (doseq [[subcommand nested] subcommands]
        (when (or (not (string? subcommand)) (str/blank? subcommand))
          (shared/fail!
           :invalid-subcommand-name
           "Subcommand names must be non-blank strings"
           {:op op
            :subcommand subcommand
            :field :subcommands
            :value subcommand}))
        (when (contains? reserved-names subcommand)
          (shared/fail!
           :reserved-subcommand-name
           (str "Subcommand name " (pr-str subcommand)
                " is reserved for help aliases")
           {:op op
            :subcommand subcommand
            :field :subcommands
            :name subcommand}))
        (when-not (map? nested)
          (shared/fail!
           :invalid-subcommand-spec
           "Nested subcommand specs must be maps"
           {:op op
            :subcommand subcommand
            :field :subcommands
            :value nested}))
        (when (contains? nested :subcommands)
          (shared/fail!
           :invalid-subcommands
           "Nested :subcommands are not supported"
           {:op op
            :subcommand subcommand
            :field :subcommands
            :value (:subcommands nested)}))
        (validate-flags! op subcommand (:flags nested))
        (validate-positionals! op subcommand (:positionals nested))
        (validate-annotations! op subcommand (:annotations nested))
        (when (some #(= "subcommand" (name %)) (keys (:flags nested)))
          (shared/fail!
           :reserved-subcommand
           "Nested flag name :subcommand is reserved"
           {:op op
            :subcommand subcommand
            :arg :subcommand
            :kind :flag}))
        (when (some #(= "subcommand" (some-> (:name %) name))
                    (:positionals nested))
          (shared/fail!
           :reserved-subcommand
           "Nested positional name :subcommand is reserved"
           {:op op
            :subcommand subcommand
            :arg :subcommand
            :kind :positional})))))
  arg-spec)
