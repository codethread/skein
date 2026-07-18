(ns skein.api.cli.internal.validation
  "Structural validation plumbing for declarative CLI arg-specs."
  (:require [clojure.string :as str]
            [skein.api.cli.internal.shared :as shared]))

(def supported-arg-types
  "Argument types understood by the parser."
  #{:string :int :boolean :boolean-token :map})

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
        (validate-declared-type! op subcommand :flags flag spec)))))

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
         op subcommand :positionals (:name spec) spec)))))

(defn validate-subcommands!
  "Validate structural rules for an arg-spec declaring `:subcommands`.

  Subcommand specs are one level deep. Top-level flags or positionals may not be
  mixed with them, and the nested `subcommand` arg name is reserved."
  [arg-spec reserved-names]
  (when (contains? arg-spec :subcommands)
    (let [op (:op arg-spec)
          subcommands (:subcommands arg-spec)]
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
