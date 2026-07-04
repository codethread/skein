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

  A flag-spec is a map with:

    :type      :string | :int | :boolean | :map   ; default :string
                 - :string/:int consume the following token as a typed value
                 - :boolean is a presence form (`--flag` -> true, no value)
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

(defn- coerce
  "Coerce a raw token to the flag/positional type, failing loudly on mismatch."
  [op arg type token]
  (case type
    :int (try (Long/parseLong token)
              (catch NumberFormatException _
                (fail! :type-violation
                       (str "Argument " arg " expects an integer, got " (pr-str token))
                       {:op op :arg arg :type :int :token token})))
    ;; :string (and the default) pass the token through unchanged.
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

(defn parse
  "Parse `argv` against `arg-spec`, resolving payload references from `payloads`.

  Returns a map of keyword arg names to parsed values. Throws a structured
  `ex-info` (ex-data carries `:reason` plus the offending token/flag and the
  op) on any violation: unknown flags, missing required args, type violations,
  duplicate non-repeat flags, malformed key=value tokens, trailing unconsumed
  tokens, dangling or unused payload references, and malformed :json/:jsonl
  payloads."
  ([arg-spec argv] (parse arg-spec argv {}))
  ([arg-spec argv payloads]
   (let [op (:op arg-spec)
         parsed (parse-argv arg-spec (vec argv))
         resolved (resolve-payloads op parsed payloads)]
     (reduce-kv
      (fn [m arg kind]
        (if (contains? m arg) (update m arg #(apply-parse op arg kind %)) m))
      resolved
      (parse-declarations arg-spec)))))

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

(defn explain
  "Render `arg-spec` as JSON-safe help data (args, types, docs, required flags,
  and payload-parse declarations) for the `help <op>` projection."
  [arg-spec]
  {:op (some-> (:op arg-spec) name)
   :doc (:doc arg-spec)
   :flags (mapv (fn [[k v]] (render-flag k v)) (sort-by key (:flags arg-spec)))
   :positionals (mapv render-positional (:positionals arg-spec))})
