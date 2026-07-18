(ns skein.api.cli.alpha
  "Blessed declarative argv parser for weaver ops (SPEC-003-D003.C1/C2).

  An arg-spec is minimal EDN data (no functions) describing an op's flags and
  positionals. `parse` turns an envelope's argv plus attached payloads into a
  data map of keyword arg names to parsed values, or throws a loud structured
  `ex-info`. `explain` renders the same arg-spec as JSON-safe help data for the
  `help <op>` projection. The namespace is pure: no registry, socket, or runtime
  coupling and no module-level state.

  Arg-spec shape:

    {:op        <keyword-or-string>   ; optional, echoed into errors and help
     :doc       <string>              ; optional op summary
     :flags     {<name-kw> <flag-spec>}
     :positionals [<positional-spec> ...]}  ; ordered, trailing may be variadic

  Multi-verb ops may instead declare one level of subcommands:

    {:op <keyword-or-string>
     :doc <string>
     :subcommands {<name-string> {:doc <string>
                                  :flags {<name-kw> <flag-spec>}
                                  :positionals [<positional-spec> ...]}}}

  Subcommand arg-specs route on the first argv token and return the nested
  parsed args merged with `:subcommand` set to the matched subcommand name.
  `:subcommand` is reserved and may not be declared as a nested flag or
  positional name.

  A flag-spec is a map with:

    :type      :string | :int | :boolean | :boolean-token | :map   ; default :string
                 - :string/:int/:boolean-token consume the following token as a typed value
                 - :boolean is a presence form (`--flag` -> true, no value)
                 - :boolean-token consumes `true` or `false` as a boolean value
                 - :map accumulates `--flag key=value` tokens into a {k v} map
    :repeat?   truthy -> repeatable, values collect into a vector (:string/:int)
    :required? truthy -> must appear
    :parse     :json | :jsonl   ; parse the resolved string value
    :doc       <string>

  A positional-spec is a map with :name (keyword), :type, :required?,
  :variadic? (trailing only, collects remaining tokens into a vector), :parse,
  and :doc.

  Payload references (SPEC-003-D003.C2): after argv parsing, any whole string
  value equal to `:stdin` or `:payload/<name>` resolves to the matching entry in
  the envelope payloads map. Matching is whole-value only (a value that merely
  contains `:stdin` as a substring is untouched). A reference with no matching
  payload throws; an attached payload that no reference consumed throws."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- fail!
  "Throw a structured parse error carrying `:reason` and any offending context."
  [reason msg data]
  (throw (ex-info msg (assoc data ::error true :reason reason))))

(def ^:private supported-arg-types
  "Argument types understood by this parser."
  #{:string :int :boolean :boolean-token :map})

(def reserved-subcommand-names
  "Subcommand names reserved for dispatch-level help aliases.

  The single source of truth: registration/parse/explain validation here and
  the weaver's dispatch-time help alias must agree on this set."
  #{"help" "-h" "--help"})

(defn- coerce
  "Coerce a raw token to the flag/positional type, failing loudly on mismatch."
  [op arg type token]
  (case type
    :int (try (Long/parseLong token)
              (catch NumberFormatException _
                (fail! :type-violation
                       (str "Argument " arg " expects an integer, got " (pr-str token))
                       {:op op :arg arg :type :int :token token})))
    :boolean-token (case token
                     "true" true
                     "false" false
                     (fail! :type-violation
                            (str "Argument " arg " expects true or false, got " (pr-str token))
                            {:op op :arg arg :type :boolean-token :token token}))
    ;; :string (and the default) passes the token through unchanged.
    token))

(defn- check-required-flags
  "Fail loudly for any required flag absent from the parsed accumulator."
  [op flags acc]
  (doseq [[flag-kw spec] flags
          :when (:required? spec)]
    (when-not (contains? acc flag-kw)
      (fail! :missing-required
             (str "Missing required flag --" (name flag-kw))
             {:op op :flag (str "--" (name flag-kw))})))
  acc)

(defn- assign-positionals
  "Bind ordered positional tokens to their specs, honouring a trailing variadic."
  [op acc pos-specs positionals]
  (let [variadic? (boolean (:variadic? (last pos-specs)))
        fixed-specs (if variadic? (vec (butlast pos-specs)) (vec pos-specs))
        fixed-count (count fixed-specs)]
    (when (and (not variadic?) (> (count positionals) (count pos-specs)))
      (fail! :trailing-tokens
             (str "Unexpected extra arguments: "
                  (pr-str (vec (drop (count pos-specs) positionals))))
             {:op op :extra (vec (drop (count pos-specs) positionals))}))
    (let [acc (reduce
               (fn [m i]
                 (let [spec (nth fixed-specs i)
                       nm (:name spec)
                       token (nth positionals i nil)]
                   (cond
                     (some? token) (assoc m nm (coerce op (name nm) (:type spec :string) token))
                     (:required? spec) (fail! :missing-required
                                              (str "Missing required argument " (name nm))
                                              {:op op :arg nm})
                     :else m)))
               acc
               (range fixed-count))]
      (if variadic?
        (let [spec (last pos-specs)
              nm (:name spec)
              rest-tokens (vec (drop fixed-count positionals))]
          (when (and (:required? spec) (empty? rest-tokens))
            (fail! :missing-required
                   (str "Missing required argument " (name nm))
                   {:op op :arg nm}))
          (assoc acc nm (mapv #(coerce op (name nm) (:type spec :string) %) rest-tokens)))
        acc))))

(defn- validate-declared-type!
  "Validate a flag or positional declared :type when one is present."
  [op subcommand field arg spec]
  (let [type (:type spec :string)]
    (when-not (contains? supported-arg-types type)
      (fail! :invalid-arg-type
             (str "Unknown argument type " (pr-str type))
             (cond-> {:op op
                      :field field
                      :arg arg
                      :type type
                      :value spec
                      :supported-types (vec (sort-by name supported-arg-types))}
               subcommand (assoc :subcommand subcommand))))))

(defn- validate-flags!
  "Validate a :flags container and entries."
  [op subcommand flags]
  (let [container-reason (if subcommand :invalid-subcommand-flags :invalid-flags)
        entry-reason (if subcommand :invalid-subcommand-flag :invalid-flag)]
    (when (some? flags)
      (when-not (map? flags)
        (fail! container-reason
               ":flags must be a map of keyword to flag-spec map"
               (cond-> {:op op :field :flags :value flags}
                 subcommand (assoc :subcommand subcommand))))
      (doseq [[flag spec] flags]
        (when-not (keyword? flag)
          (fail! entry-reason
                 "Flag names must be keywords"
                 (cond-> {:op op :field :flags :arg flag :value spec}
                   subcommand (assoc :subcommand subcommand))))
        (when-not (map? spec)
          (fail! entry-reason
                 "Flag specs must be maps"
                 (cond-> {:op op :field :flags :arg flag :value spec}
                   subcommand (assoc :subcommand subcommand))))
        (validate-declared-type! op subcommand :flags flag spec)))))

(defn- validate-positionals!
  "Validate a :positionals container and entries."
  [op subcommand positionals]
  (let [container-reason (if subcommand :invalid-subcommand-positionals :invalid-positionals)
        entry-reason (if subcommand :invalid-subcommand-positional :invalid-positional)]
    (when (some? positionals)
      (when-not (sequential? positionals)
        (fail! container-reason
               ":positionals must be a sequential collection of positional-spec maps"
               (cond-> {:op op :field :positionals :value positionals}
                 subcommand (assoc :subcommand subcommand))))
      (doseq [[idx spec] (map-indexed vector positionals)]
        (when-not (map? spec)
          (fail! entry-reason
                 "Positional specs must be maps"
                 (cond-> {:op op :field :positionals :index idx :value spec}
                   subcommand (assoc :subcommand subcommand))))
        (when-not (keyword? (:name spec))
          (fail! entry-reason
                 "Positional specs must declare keyword :name"
                 (cond-> {:op op :field :positionals :index idx :value spec}
                   subcommand (assoc :subcommand subcommand))))
        (validate-declared-type! op subcommand :positionals (:name spec) spec)))))

(defn- validate-subcommands!
  "Validate the structural rules for an arg-spec declaring `:subcommands`.

  Subcommand specs are intentionally one level deep: top-level
  flags/positionals may not be mixed with `:subcommands`, nested subcommands are
  rejected, and `subcommand` is a reserved nested arg name because parse results
  use `:subcommand` for the matched verb. Throws structured `ex-info` on any
  violation so registries can fail before invocation."
  [arg-spec]
  (when (contains? arg-spec :subcommands)
    (let [op (:op arg-spec)
          subcommands (:subcommands arg-spec)]
      (when (contains? arg-spec :flags)
        (fail! :invalid-subcommands
               "Arg-spec with :subcommands may not declare top-level :flags"
               {:op op :field :flags}))
      (when (contains? arg-spec :positionals)
        (fail! :invalid-subcommands
               "Arg-spec with :subcommands may not declare top-level :positionals"
               {:op op :field :positionals}))
      (when-not (map? subcommands)
        (fail! :invalid-subcommands
               ":subcommands must be a map of subcommand name to arg-spec"
               {:op op :field :subcommands :value subcommands}))
      (doseq [[subcommand nested] subcommands]
        (when (or (not (string? subcommand)) (str/blank? subcommand))
          (fail! :invalid-subcommand-name
                 "Subcommand names must be non-blank strings"
                 {:op op :subcommand subcommand :field :subcommands :value subcommand}))
        (when (contains? reserved-subcommand-names subcommand)
          (fail! :reserved-subcommand-name
                 (str "Subcommand name " (pr-str subcommand) " is reserved for help aliases")
                 {:op op :subcommand subcommand :field :subcommands :name subcommand}))
        (when-not (map? nested)
          (fail! :invalid-subcommand-spec
                 "Nested subcommand specs must be maps"
                 {:op op :subcommand subcommand :field :subcommands :value nested}))
        (when (contains? nested :subcommands)
          (fail! :invalid-subcommands
                 "Nested :subcommands are not supported"
                 {:op op :subcommand subcommand :field :subcommands :value (:subcommands nested)}))
        (validate-flags! op subcommand (:flags nested))
        (validate-positionals! op subcommand (:positionals nested))
        (when (some #(= "subcommand" (name %)) (keys (:flags nested)))
          (fail! :reserved-subcommand
                 "Nested flag name :subcommand is reserved"
                 {:op op :subcommand subcommand :arg :subcommand :kind :flag}))
        (when (some #(= "subcommand" (some-> (:name %) name)) (:positionals nested))
          (fail! :reserved-subcommand
                 "Nested positional name :subcommand is reserved"
                 {:op op :subcommand subcommand :arg :subcommand :kind :positional})))))
  arg-spec)

(defn validate!
  "Validate any parser arg-spec shape, returning it unchanged on success.

  Flat arg-specs validate their top-level flags and positionals. Subcommand
  arg-specs additionally enforce the one-level subcommand contract and reserved
  `:subcommand` result key. Throws structured `ex-info` on malformed specs so
  op registration fails before help or invocation can drift from the contract."
  [arg-spec]
  (when-not (map? arg-spec)
    (fail! :invalid-arg-spec
           "Arg-spec must be a map"
           {:value arg-spec}))
  (if (contains? arg-spec :subcommands)
    (validate-subcommands! arg-spec)
    (let [op (:op arg-spec)]
      (validate-flags! op nil (:flags arg-spec))
      (validate-positionals! op nil (:positionals arg-spec))
      arg-spec)))

(defn- parse-argv
  "First pass: fold argv into a raw arg map (payload refs and :parse untouched)."
  [arg-spec argv]
  (let [op (:op arg-spec)
        flags (:flags arg-spec)
        pos-specs (vec (:positionals arg-spec))]
    (loop [tokens (seq argv)
           acc {}
           seen #{}
           positionals []]
      (if-let [[token & more] tokens]
        (if (str/starts-with? token "--")
          (let [flag-kw (keyword (subs token 2))
                spec (get flags flag-kw)]
            (when-not spec
              (fail! :unknown-flag
                     (str "Unknown flag " token)
                     {:op op :flag token
                      :known-flags (mapv #(str "--" (name %)) (sort (keys flags)))}))
            (case (:type spec :string)
              :boolean
              (do (when (contains? seen flag-kw)
                    (fail! :duplicate-flag (str "Duplicate flag " token) {:op op :flag token}))
                  (recur more (assoc acc flag-kw true) (conj seen flag-kw) positionals))

              :map
              (let [kv (first more)]
                (when (or (nil? kv) (str/starts-with? kv "--"))
                  (fail! :malformed-kv
                         (str "Flag " token " expects a key=value token")
                         {:op op :flag token :token kv}))
                (let [idx (str/index-of kv "=")]
                  (when (or (nil? idx) (zero? idx))
                    (fail! :malformed-kv
                           (str "Malformed key=value token " (pr-str kv) " for " token)
                           {:op op :flag token :token kv}))
                  (recur (next more)
                         (update acc flag-kw (fnil assoc {}) (subs kv 0 idx) (subs kv (inc idx)))
                         seen positionals)))

              ;; value flags: :string / :int
              (let [value-token (first more)]
                (when (nil? value-token)
                  (fail! :missing-value (str "Flag " token " expects a value") {:op op :flag token}))
                (let [value (coerce op token (:type spec :string) value-token)]
                  (if (:repeat? spec)
                    (recur (next more) (update acc flag-kw (fnil conj []) value) seen positionals)
                    (do (when (contains? seen flag-kw)
                          (fail! :duplicate-flag (str "Duplicate flag " token) {:op op :flag token}))
                        (recur (next more) (assoc acc flag-kw value) (conj seen flag-kw) positionals)))))))
          (recur more acc seen (conj positionals token)))
        ;; argv exhausted: enforce required flags, then bind positionals.
        (assign-positionals op (check-required-flags op flags acc) pos-specs positionals)))))

(defn- payload-ref
  "Return the payload name a whole-value reference points at, else nil."
  [s]
  (cond
    (= s ":stdin") "stdin"
    (str/starts-with? s ":payload/") (subs s (count ":payload/"))
    :else nil))

(defn- resolve-value
  "Replace whole-value payload references, tracking consumed names in `consumed`."
  [op payloads consumed value]
  (cond
    (string? value)
    (if-let [nm (payload-ref value)]
      (if (contains? payloads nm)
        (do (swap! consumed conj nm) (get payloads nm))
        (fail! :missing-payload
               (str "No payload attached for reference " value)
               {:op op :ref value :payload-name nm :available (vec (sort (keys payloads)))}))
      value)
    (vector? value) (mapv #(resolve-value op payloads consumed %) value)
    (map? value) (reduce-kv (fn [m k v] (assoc m k (resolve-value op payloads consumed v))) {} value)
    :else value))

(defn- resolve-payloads
  "Resolve payload references across all parsed values and enforce both loud rules."
  [op parsed payloads]
  (let [payloads (reduce-kv (fn [m k v] (assoc m (name k) v)) {} payloads)
        consumed (atom #{})
        resolved (reduce-kv (fn [m k v] (assoc m k (resolve-value op payloads consumed v))) {} parsed)
        unused (remove @consumed (keys payloads))]
    (when (seq unused)
      (fail! :unused-payloads
             (str "Attached payloads not referenced by any argument: " (str/join ", " (sort unused)))
             {:op op :unused (vec (sort unused))}))
    resolved))

(defn- parse-json [op arg s]
  (try (json/read-str s)
       (catch Exception e
         (fail! :malformed-json
                (str "Argument " (name arg) " is not valid JSON: " (ex-message e))
                {:op op :arg arg}))))

(defn- parse-jsonl [op arg s]
  (->> (str/split-lines s)
       (map-indexed (fn [i line] [(inc i) line]))
       (remove (fn [[_ line]] (str/blank? line)))
       (mapv (fn [[n line]]
               (try (json/read-str line)
                    (catch Exception e
                      (fail! :malformed-jsonl
                             (str "Argument " (name arg) " line " n " is not valid JSON: " (ex-message e))
                             {:op op :arg arg :line n})))))))

(defn- apply-parse
  "Apply a :json/:jsonl declaration to a resolved value (scalar, repeat, or map)."
  [op arg kind value]
  (let [f (case kind
            :json #(parse-json op arg %)
            :jsonl #(parse-jsonl op arg %))]
    (cond
      (string? value) (f value)
      (vector? value) (mapv f value)
      (map? value) (reduce-kv (fn [m k v] (assoc m k (f v))) {} value)
      :else value)))

(defn- parse-declarations
  "Collect {arg-name parse-kind} from every flag and positional declaring :parse."
  [arg-spec]
  (merge
   (reduce-kv (fn [m k spec] (if-let [p (:parse spec)] (assoc m k p) m)) {} (:flags arg-spec))
   (reduce (fn [m spec] (if-let [p (:parse spec)] (assoc m (:name spec) p) m)) {} (:positionals arg-spec))))

(defn- parse-flat
  "Parse a non-subcommand arg-spec after routing has selected the active spec."
  [arg-spec argv payloads]
  (let [op (:op arg-spec)
        parsed (parse-argv arg-spec (vec argv))
        resolved (resolve-payloads op parsed payloads)]
    (reduce-kv
     (fn [m arg kind]
       (if (contains? m arg) (update m arg #(apply-parse op arg kind %)) m))
     resolved
     (parse-declarations arg-spec))))

(defn- parse-subcommand
  "Route argv to a nested subcommand arg-spec and merge the matched name."
  [arg-spec argv payloads]
  (let [op (:op arg-spec)
        subcommands (:subcommands arg-spec)
        available (vec (sort (keys subcommands)))
        subcommand (first argv)]
    (when-not subcommand
      (fail! :missing-subcommand
             "Missing subcommand"
             {:op op :available-subcommands available}))
    (let [nested (get subcommands subcommand)]
      (when-not nested
        (fail! :unknown-subcommand
               (str "Unknown subcommand " (pr-str subcommand))
               {:op op :token subcommand :available-subcommands available}))
      (assoc (parse-flat (assoc nested :op op) (subvec (vec argv) 1) payloads)
             :subcommand subcommand))))

(defn parse
  "Parse `argv` against `arg-spec`, resolving payload references from `payloads`.

  Returns a map of keyword arg names to parsed values. For subcommand arg-specs,
  the first argv token selects the nested spec and the result includes the
  matched `:subcommand` string. Throws a structured `ex-info` (ex-data carries
  `:reason` plus the offending token/flag and the op) on any violation: unknown
  flags, missing required args, type violations, duplicate non-repeat flags,
  malformed key=value tokens, trailing unconsumed tokens, missing/unknown
  subcommands, dangling or unused payload references, and malformed
  :json/:jsonl payloads."
  ([arg-spec argv] (parse arg-spec argv {}))
  ([arg-spec argv payloads]
   (let [arg-spec (validate! arg-spec)]
     (if (contains? arg-spec :subcommands)
       (parse-subcommand arg-spec (vec argv) payloads)
       (parse-flat arg-spec argv payloads)))))

(defn- render-flag [flag-kw spec]
  {:name (name flag-kw)
   :flag (str "--" (name flag-kw))
   :type (name (:type spec :string))
   :required (boolean (:required? spec))
   :repeat (boolean (:repeat? spec))
   :parse (some-> (:parse spec) name)
   :doc (:doc spec)})

(defn- render-positional [spec]
  {:name (name (:name spec))
   :type (name (:type spec :string))
   :required (boolean (:required? spec))
   :variadic (boolean (:variadic? spec))
   :parse (some-> (:parse spec) name)
   :doc (:doc spec)})

(defn- explain-flat [arg-spec]
  {:op (some-> (:op arg-spec) name)
   :doc (:doc arg-spec)
   :flags (mapv (fn [[k v]] (render-flag k v)) (sort-by key (:flags arg-spec)))
   :positionals (mapv render-positional (:positionals arg-spec))})

(defn- render-subcommand [[nm nested]]
  (let [rendered (explain-flat nested)]
    {:name nm
     :doc (:doc rendered)
     :flags (:flags rendered)
     :positionals (:positionals rendered)}))

(defn explain
  "Render `arg-spec` as JSON-safe help data (args, types, docs, required flags,
  subcommands, and payload-parse declarations) for the `help <op>` projection."
  [arg-spec]
  (let [arg-spec (validate! arg-spec)]
    (if (contains? arg-spec :subcommands)
      (assoc (explain-flat (dissoc arg-spec :subcommands))
             :subcommands (mapv render-subcommand (sort-by key (:subcommands arg-spec))))
      (explain-flat arg-spec))))
